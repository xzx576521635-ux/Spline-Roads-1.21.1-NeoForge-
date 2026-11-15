package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class LaneMerge {
   public static LaneSections.Event event(RoadGeometry.Mesh mesh, LanePoints.Point point) {
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);
      LanePoints.Lane lane = LanePoints.lane(raw, point);
      RoadProfile.Catalog c = RoadProfile.catalog(raw.settings().style());
      if (!point.automatic() && !(point.mergeLength() < 8.0) && LanePoints.supported(raw.settings())) {
         if (!LaneSections.edge(raw, lane.station(), point.lane(), mesh.settings().options().lanePoints().cuts(), point.id())) {
            throw new IllegalArgumentException("合流缩减仅支持当前位置的最外侧车道，不能挖走内部车道");
         } else {
            int neighbor = LaneSections.receiver(mesh, lane.station(), point.lane(), point.id());
            double remaining = lane.sign() > 0 ? raw.length() - lane.station() : lane.station();
            if (remaining < point.mergeLength() + 2.0) {
               throw new IllegalArgumentException("下游长度不足以完成合流渐变，请前移此车道点");
            } else {
               for (double d = 0.0; d <= point.mergeLength(); d += 0.5) {
                  double at = lane.station() + (double)lane.sign() * d;
                  if (!LaneSections.active(mesh, at, neighbor)) {
                     throw new IllegalArgumentException("同向接收车道在合流区已封闭，不能把车流导入空位");
                  }
               }

               return new LaneSections.Event(point.id(), LaneSections.Kind.DEPART, point.lane(), lane.sign(), lane.station(), point.mergeLength());
            }
         }
      } else {
         throw new IllegalArgumentException("请选择独立道路上的手动外侧车道点");
      }
   }

   public static boolean allowed(RoadGeometry.Mesh mesh, LanePoints.Point point) {
      try {
         event(mesh, point.merge(32.0));
         return true;
      } catch (IllegalArgumentException var3) {
         return false;
      }
   }

   public static boolean sameDefinitions(LanePoints.Data a, LanePoints.Data b) {
      return a.points().stream().filter(p -> p.mergeLength() != 0.0).toList().equals(b.points().stream().filter(p -> p.mergeLength() != 0.0).toList());
   }

   public static boolean linkedTo(RoadGeometry.Mesh host, RoadGeometry.Mesh candidate) {
      LanePoints.Link l = candidate.settings().options().lanePoints().link();
      if (l == null) {
         return false;
      } else {
         for (LanePoints.Point point : host.settings().options().lanePoints().points()) {
            if (point.id().equals(l.from().point()) || point.id().equals(l.to().point())) {
               return true;
            }
         }

         return false;
      }
   }

   public static boolean merging(RoadGeometry.Mesh mesh, double station, int slot) {
      for (LanePoints.Point p : mesh.settings().options().lanePoints().points()) {
         if (p.mergeLength() > 0.0 && p.lane() == slot) {
            LanePoints.Lane at = LanePoints.lane(reference(mesh), p);
            double d = (double)at.sign() * (station - at.station());
            if (d >= -8.0 && d <= p.mergeLength()) {
               return true;
            }
         }
      }

      return false;
   }

   private static RoadGeometry.Mesh reference(RoadGeometry.Mesh m) {
      return LaneSections.reference(m);
   }

   public static List<RoadJunction.Paint> guides(RoadGeometry.Mesh mesh) {
      ArrayList<RoadJunction.Paint> out = new ArrayList<>();
      RoadGeometry.Mesh raw = reference(mesh);

      for (LanePoints.Point point : mesh.settings().options().lanePoints().points()) {
         if (point.mergeLength() > 0.0) {
            LanePoints.Lane lane = LanePoints.lane(raw, point);
            double station = lane.station() + (double)lane.sign() * Math.min(4.0, point.mergeLength() * 0.12);
            int receiver = LaneSections.receiver(mesh, lane.station(), point.lane(), point.id());
            LanePoints.Lane at = LanePoints.lane(raw, station, point.lane());
            RoadGeometry.V lateral = at.direction().left();
            double side = Math.signum(LanePoints.lane(raw, station, receiver).position().sub(at.position()).dot(lateral));
            ArrayList<RoadJunction.Paint> group = new ArrayList<>();
            boolean fits = true;

            for (RoadJunction.Paint poly : RoadMergeArrow.local(at.width(), side)) {
               ArrayList<RoadGeometry.V> vs = new ArrayList<>();

               for (RoadGeometry.V q : poly.points()) {
                  LanePoints.Lane frame = LanePoints.lane(raw, station + (double)lane.sign() * q.x(), point.lane());
                  RoadGeometry.V v = frame.position().add(frame.direction().left().mul(q.z()));
                  if (!RoadQueries.contains(mesh, v, -0.1, 0.1)) {
                     fits = false;
                  }

                  vs.add(v);
               }

               group.add(new RoadJunction.Paint(List.copyOf(vs), poly.color()));
            }

            if (fits) {
               out.addAll(group);
            }
         }
      }

      return List.copyOf(out);
   }

   private LaneMerge() {
   }
}
