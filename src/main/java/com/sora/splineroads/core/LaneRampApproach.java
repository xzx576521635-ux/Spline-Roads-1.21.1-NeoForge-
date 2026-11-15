package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

public final class LaneRampApproach {
   public static List<RoadGeometry.Sample> build(
      DoubleFunction<LaneRampApproach.Station> stations, boolean source, RoadGeometry.Settings ramp, double transition, boolean tunnel
   ) {
      double taper = tunnel ? Math.max(64.0, transition * 2.0) : taper(transition);
      return build(stations, source, ramp.width(), RoadProfile.layout(ramp, ramp.width()).motorCenter(), taper, taper + parallel(transition), tunnel);
   }

   public static double taper(double transition) {
      return Math.max(40.0, transition * 1.5);
   }

   public static double parallel(double transition) {
      return Math.max(16.0, transition / 2.0);
   }

   public static double length(double transition) {
      return taper(transition) + parallel(transition);
   }

   public static double taper(RoadGeometry.Mesh host, double transition) {
      return host.settings().structure() == RoadGeometry.Structure.TUNNEL ? Math.max(64.0, transition * 2.0) : taper(transition);
   }

   public static double length(RoadGeometry.Mesh host, double transition) {
      return taper(host, transition) + parallel(transition);
   }

   public static LanePoints.Lane outerLane(RoadGeometry.Mesh host, double station, int index) {
      LanePoints.Lane selected = LanePoints.lane(host, station, index);
      RoadGeometry.Sample at = RoadStructures.sample(host, station);
      RoadProfile.Layout layout = RoadProfile.layout(host, at);
      int side = layout.catalog().twoWay() ? (selected.position().sub(at.center()).dot(at.left()) < layout.medianCenter() ? -1 : 1) : layout.outside();
      LanePoints.Lane best = null;
      double far = Double.NEGATIVE_INFINITY;

      for (int i : LaneAdditions.slots(host, station)) {
         if (LaneSections.active(host, station, i)) {
            LanePoints.Lane lane = LanePoints.lane(host, station, i);
            double lateral = (double)side * lane.position().sub(at.center()).dot(at.left());
            if (lane.sign() == selected.sign() && lateral > far) {
               best = lane;
               far = lateral;
            }
         }
      }

      if (best == null) {
         throw new IllegalArgumentException("当前位置所选方向没有开放的外侧车道");
      } else {
         return best;
      }
   }

   public static List<RoadGeometry.Sample> build(RoadGeometry.Mesh host, int lane, double station, boolean source, double width, double transition) {
      return build(host, lane, station, source, width, transition, 0.0, host.settings().structure() == RoadGeometry.Structure.TUNNEL);
   }

   public static List<RoadGeometry.Sample> build(
      RoadGeometry.Mesh host, int lane, double station, boolean source, RoadGeometry.Settings ramp, double transition
   ) {
      return build(host, lane, station, source, ramp, transition, host.settings().structure() == RoadGeometry.Structure.TUNNEL);
   }

   public static List<RoadGeometry.Sample> build(
      RoadGeometry.Mesh host, int lane, double station, boolean source, RoadGeometry.Settings ramp, double transition, boolean tunnel
   ) {
      return build(host, lane, station, source, ramp.width(), transition, RoadProfile.layout(ramp, ramp.width()).motorCenter(), tunnel);
   }

   private static List<RoadGeometry.Sample> build(
      RoadGeometry.Mesh host, int lane, double station, boolean source, double width, double transition, double motorCenter, boolean tunnel
   ) {
      LanePoints.Lane selected = outerLane(host, station, lane);
      final int laneIndex = selected.index();
      int sign = selected.sign();
      double taper = tunnel ? Math.max(64.0, transition * 2.0) : taper(transition);
      double length = taper + parallel(transition);
      double from = source ? station : station - (double)sign * length;
      double to = source ? station + (double)sign * length : station;
      if (!(Math.min(from, to) < -1.0E-6) && !(Math.max(from, to) > host.length() + 1.0E-6)) {
         return build(d -> new LaneRampApproach.Station(host, laneIndex, from + (double)sign * d), source, width, motorCenter, taper, length, tunnel);
      } else {
         throw new IllegalArgumentException((source ? "汇出" : "汇入") + "额外车道需要沿主路 " + (int)length + " 格（渐变段＋并行段），当前位置空间不足");
      }
   }

   private static List<RoadGeometry.Sample> build(
      DoubleFunction<LaneRampApproach.Station> stations, boolean source, double width, double motorCenter, double taper, double length, boolean tunnel
   ) {
      ArrayList<RoadGeometry.Sample> out = new ArrayList<>();
      int n = (int)Math.ceil(length / 0.5);
      double distance = 0.0;

      for (int i = 0; i <= n; i++) {
         double d = length * (double)i / (double)n;
         LaneRampApproach.Station point = stations.apply(d);
         RoadGeometry.Mesh host = point.mesh();
         double at = point.distance();
         RoadGeometry.Sample s = RoadStructures.sample(host, at);
         LanePoints.Lane l = outerLane(host, at, point.lane());
         RoadProfile.Layout layout = RoadProfile.layout(host, s);
         double lateral = l.position().sub(s.center()).dot(s.left());
         int side = layout.catalog().twoWay() ? (lateral < layout.medianCenter() ? -1 : 1) : layout.outside();
         boolean motorEdge = layout.cycleWidth() > 0.01;
         double edge = motorEdge ? (double)side * layout.outer(side) + 0.02 : s.halfWidth() - 0.02;
         if (tunnel) {
            edge = Math.max(edge, s.halfWidth() + 1.55 + 0.05);
         }

         double offset = edge + width / 2.0 - (double)side * lateral + (double)side * motorCenter;
         double grow = RoadGeometry.Settings.smooth(Math.min(1.0, (source ? d : length - d) / taper));
         RoadGeometry.V position = l.position().add(s.left().mul((double)side * offset * grow));
         if (i > 0) {
            distance += position.sub(out.get(i - 1).center()).horizontalLength();
         }

         double narrow = Math.min(width, l.width() + 0.75);
         double halfWidth = (narrow + (width - narrow) * grow) / 2.0;
         out.add(new RoadGeometry.Sample(position, l.direction().left(), distance, halfWidth));
      }

      ArrayList<RoadGeometry.Sample> result = new ArrayList<>();

      for (int i = 0; i < out.size(); i++) {
         RoadGeometry.Sample sx = out.get(i);
         RoadGeometry.V direction = i != 0 && i != out.size() - 1
            ? out.get(i + 1).center().sub(out.get(i - 1).center()).horizontalUnit()
            : sx.left().left().mul(-1.0);
         result.add(new RoadGeometry.Sample(sx.center(), direction.left(), sx.distance(), sx.halfWidth()));
      }

      return List.copyOf(result);
   }

   private LaneRampApproach() {
   }

   public static record Station(RoadGeometry.Mesh mesh, int lane, double distance) {
   }
}
