package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class LaneRampPaint {
   public static List<RoadJunction.Paint> arrows(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> neighbors) {
      if (!ramp.settings().style().connectorRamp()) {
         return List.of();
      } else {
         LanePoints.Link link = ramp.settings().options().lanePoints().link();
         if (link != null && link.options().targetExtra() && link.to().road() != null) {
            RoadGeometry.Mesh target = null;
            LanePoints.Point point = null;

            for (RoadGeometry.Mesh other : neighbors) {
               for (LanePoints.Point p : other.settings().options().lanePoints().points()) {
                  if (p.id().equals(link.to().point())) {
                     target = other;
                     point = p;
                     break;
                  }
               }
            }

            if (target == null) {
               return List.of();
            } else {
               boolean highway = RoadProfile.highway(target.settings().style());
               RoadGeometry.Mesh protectedDeck = highway ? LaneDeck.motorOnly(target) : target;
               double length = LaneRampApproach.length(target, link.options().transition());
               double taper = LaneRampApproach.taper(target, link.options().transition());
               double min = Math.max(4.0, ramp.length() - length * 1.5);
               double max = ramp.length() - 4.0;
               double preferred = Math.max(min, ramp.length() - taper - 8.0);
               LinkedHashSet<Double> candidates = new LinkedHashSet<>();
               candidates.add(Double.valueOf(preferred));

               for (double d = preferred - 1.0; d >= min; d--) {
                  candidates.add(Double.valueOf(d));
               }

               for (double d = preferred + 1.0; d <= max; d++) {
                  candidates.add(Double.valueOf(d));
               }

               for (double d : candidates) {
                  if (!highway || !(ramp.length() - d < taper + 2.0) && !(ramp.length() - d > length - 2.0)) {
                     RoadGeometry.Sample at = RoadStructures.sample(ramp, d);
                     RoadQueries.Projection q = RoadQueries.horizontal(target, at.center());
                     LanePoints.Lane lane = LaneRampApproach.outerLane(target, q.sample().distance(), point.lane());
                     RoadGeometry.V forward = at.left().left().mul(-1.0);
                     if (!(forward.dot(lane.direction()) < 0.94) && !(Math.abs(at.center().y() - lane.position().y()) > 0.15)) {
                        double lateral = lane.position().sub(at.center()).dot(at.left());
                        if (!(Math.abs(lateral) < at.halfWidth() + 0.5)) {
                           double side = Math.signum(lateral);
                           List<RoadJunction.Paint> faces = guide(ramp, d, side);
                           boolean fits = !faces.isEmpty();

                           for (RoadJunction.Paint face : faces) {
                              for (RoadGeometry.V px : face.points()) {
                                 RoadQueries.Projection here = RoadQueries.horizontal(ramp, px);
                                 RoadProfile.Layout layout = RoadProfile.layout(ramp, here.sample());
                                 if (here.sample().distance() < 1.0
                                    || here.sample().distance() > ramp.length() - 1.0
                                    || here.lateral() < layout.motorMin() + 0.18
                                    || here.lateral() > layout.motorMax() - 0.18
                                    || !RoadQueries.contains(ramp, px, -0.1, 0.08)
                                    || RoadQueries.contains(protectedDeck, px, 0.1, 0.15)) {
                                    fits = false;
                                 }
                              }
                           }

                           if (fits) {
                              return faces;
                           }
                        }
                     }
                  }
               }

               return List.of();
            }
         } else {
            return List.of();
         }
      }
   }

   private static List<RoadJunction.Paint> guide(RoadGeometry.Mesh mesh, double station, double side) {
      ArrayList<RoadJunction.Paint> out = new ArrayList<>();
      double width = RoadProfile.layout(mesh, RoadStructures.sample(mesh, station)).laneWidth();

      for (RoadJunction.Paint shape : RoadMergeArrow.local(width, side)) {
         ArrayList<RoadGeometry.V> vertices = new ArrayList<>();

         for (RoadGeometry.V p : shape.points()) {
            RoadGeometry.Sample at = RoadStructures.sample(mesh, station + p.x());
            RoadProfile.Layout profile = RoadProfile.layout(mesh, at);
            vertices.add(at.at(profile.motorCenter() + p.z(), 0.0));
         }

         out.add(new RoadJunction.Paint(List.copyOf(vertices), shape.color()));
      }

      return List.copyOf(out);
   }

   public static List<LaneRampPaint.Auxiliary> auxiliary(RoadGeometry.Mesh host, List<RoadGeometry.Mesh> neighbors) {
      ArrayList<LaneRampPaint.Auxiliary> out = new ArrayList<>();
      ArrayList<RoadGeometry.Mesh> context = new ArrayList<>(neighbors);
      if (!context.contains(host)) {
         context.add(host);
      }

      for (RoadGeometry.Mesh ramp : neighbors) {
         if (ramp.settings().options().lanePoints().link() != null) {
            double[] interval = RoadRailJoin.auxiliaryInterval(ramp, host, context);
            if (interval != null) {
               double low = Double.POSITIVE_INFINITY;
               double high = Double.NEGATIVE_INFINITY;
               int side = 0;

               for (double d = interval[0]; d <= interval[1] + 0.01; d += 0.5) {
                  RoadGeometry.Sample sample = RoadStructures.sample(ramp, Math.min(d, interval[1]));
                  RoadQueries.Projection q = RoadQueries.horizontal(host, sample.center());
                  low = Math.min(low, q.sample().distance());
                  high = Math.max(high, q.sample().distance());
                  if (Math.abs(q.lateral()) > 0.1) {
                     side = q.lateral() < 0.0 ? -1 : 1;
                  }
               }

               if (side != 0 && high > low) {
                  out.add(new LaneRampPaint.Auxiliary(ramp, side, low - 1.0, high + 1.0));
               }
            }
         }
      }

      return List.copyOf(out);
   }

   public static boolean auxiliaryAt(RoadGeometry.Mesh host, RoadGeometry.Sample a, RoadGeometry.Sample b, int side, List<LaneRampPaint.Auxiliary> candidates) {
      RoadGeometry.Sample m = RoadStructures.sample(host, (a.distance() + b.distance()) / 2.0);
      RoadProfile.Layout layout = RoadProfile.layout(host, m);
      double edge = layout.cycleWidth() > 0.01 ? (double)side * layout.outer(side) : m.halfWidth();

      for (LaneRampPaint.Auxiliary item : candidates) {
         if (item.side() == side
            && m.distance() >= item.from()
            && m.distance() <= item.to()
            && RoadQueries.contains(item.ramp(), m.at((double)side * (edge + 0.35), 0.0), 0.0, 0.06)
            && RoadQueries.contains(item.ramp(), m.at((double)side * (edge + 0.7), 0.0), 0.0, 0.06)) {
            return true;
         }
      }

      return false;
   }

   public static boolean auxiliaryGuideAt(
      RoadGeometry.Mesh host, RoadGeometry.Sample a, RoadGeometry.Sample b, int side, List<LaneRampPaint.Auxiliary> candidates
   ) {
      if (!auxiliaryAt(host, a, b, side, candidates)) {
         return false;
      } else {
         for (LaneRampPaint.Auxiliary item : candidates) {
            if (item.side() == side && guideFits(host, a, b, side, item.ramp())) {
               return true;
            }
         }

         return false;
      }
   }

   public static boolean guideFits(RoadGeometry.Mesh host, RoadGeometry.Sample a, RoadGeometry.Sample b, int side, RoadGeometry.Mesh ramp) {
      for (RoadGeometry.Sample at : List.of(a, RoadStructures.sample(host, (a.distance() + b.distance()) / 2.0), b)) {
         RoadProfile.Layout layout = RoadProfile.layout(host, at);
         double edge = layout.cycleWidth() > 0.01 ? layout.outer(side) : (double)side * at.halfWidth();

         for (double offset : new double[]{0.15, 1.25}) {
            if (!RoadQueries.contains(ramp, at.at(edge + (double)side * offset, 0.0), 0.0, 0.06)) {
               return false;
            }
         }
      }

      return true;
   }

   private LaneRampPaint() {
   }

   public static record Auxiliary(RoadGeometry.Mesh ramp, int side, double from, double to) {
   }
}
