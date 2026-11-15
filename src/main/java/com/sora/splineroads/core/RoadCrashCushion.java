package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RoadCrashCushion {
   public static final String MODEL = "sr:crash_cushion";

   public static List<RoadSurface.Face> faces(RoadStructures.Part part) {
      ArrayList<RoadSurface.Face> faces = new ArrayList<>();
      RoadGeometry.V center = part.a();
      double r = part.width() / 2.0;
      double[] heights = new double[]{0.0, 0.08, 0.32, 0.43, 0.69, 0.8, 1.0};

      for (int band = 0; band + 1 < heights.length; band++) {
         for (int i = 0; i < 12; i++) {
            double a = (Math.PI * 2) * (double)i / 12.0;
            double b = (Math.PI * 2) * (double)(i + 1) / 12.0;
            double lo = heights[band] * part.height();
            double hi = heights[band + 1] * part.height();
            double radius = band == 0 ? r : r * 0.94;
            int color = band == 0 ? 3356730 : (band != 2 && band != 4 ? 15314706 : 15986896);
            faces.add(
               new RoadSurface.Face(
                  List.of(
                     center.add(new RoadGeometry.V(radius * Math.cos(b), lo, radius * Math.sin(b))),
                     center.add(new RoadGeometry.V(radius * Math.cos(a), lo, radius * Math.sin(a))),
                     center.add(new RoadGeometry.V(radius * Math.cos(a), hi, radius * Math.sin(a))),
                     center.add(new RoadGeometry.V(radius * Math.cos(b), hi, radius * Math.sin(b)))
                  ),
                  color
               )
            );
         }
      }

      for (int i = 0; i < 12; i++) {
         double a = (Math.PI * 2) * (double)i / 12.0;
         double b = (Math.PI * 2) * (double)(i + 1) / 12.0;
         double y = part.height();
         double foot = 0.08 * part.height();
         faces.add(
            new RoadSurface.Face(
               List.of(
                  center.add(new RoadGeometry.V(r * Math.cos(a), foot, r * Math.sin(a))),
                  center.add(new RoadGeometry.V(r * 0.94 * Math.cos(a), foot, r * 0.94 * Math.sin(a))),
                  center.add(new RoadGeometry.V(r * 0.94 * Math.cos(b), foot, r * 0.94 * Math.sin(b))),
                  center.add(new RoadGeometry.V(r * Math.cos(b), foot, r * Math.sin(b)))
               ),
               3356730
            )
         );
         faces.add(
            new RoadSurface.Face(
               List.of(
                  center.add(new RoadGeometry.V(0.0, y - 0.025, 0.0)),
                  center.add(new RoadGeometry.V(r * 0.94 * Math.cos(b), y, r * 0.94 * Math.sin(b))),
                  center.add(new RoadGeometry.V(r * 0.94 * Math.cos(a), y, r * 0.94 * Math.sin(a)))
               ),
               12356627
            )
         );
      }

      return List.copyOf(faces);
   }

   public static List<RoadStructures.Part> plan(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> neighbors, List<RoadStructures.Part> parts) {
      if (ramp.settings().style().ramp() && ramp.settings().structure() != RoadGeometry.Structure.TUNNEL) {
         ArrayList<RoadGeometry.Mesh> decks = new ArrayList<>(neighbors);
         decks.add(ramp);
         List<RoadGeometry.Mesh> motors = decks.stream().map(LaneDeck::motorOnly).toList();
         RoadSolidOverlap.Index occupied = new RoadSolidOverlap.Index(
            parts.stream().filter(p -> p.top().stream().anyMatch(v -> v.y() > RoadQueries.horizontal(ramp, v).sample().center().y() + 0.03)).toList()
         );
         ArrayList<RoadStructures.Part> out = new ArrayList<>();

         label118:
         for (RoadGeometry.Mesh host : neighbors) {
            if (!host.settings().style().ramp() || RoadJunction.sharedRampFork(ramp, host)) {
               RoadQueries.Projection departure = RoadQueries.horizontal(host, ramp.first().center());
               if (!(departure.horizontalDistance() > departure.sample().halfWidth() + ramp.first().halfWidth() + 0.1)
                  && !(Math.abs(departure.sample().center().y() - ramp.first().center().y()) > 0.3)
                  && !(Math.abs(departure.sample().left().dot(ramp.first().left())) < 0.9)) {
                  int flow = departure.sample().left().dot(ramp.first().left()) >= 0.0 ? 1 : -1;
                  SmartSigns.Support support = ForkSignPlacement.find(ramp, host, parts, flow, false);
                  if (support != null) {
                     RoadStructures.Part base = null;
                     double closest = Double.POSITIVE_INFINITY;

                     for (RoadStructures.Part part : parts) {
                        if (ForkSignPlacement.isBase(part)) {
                           RoadGeometry.V delta = part.b().sub(part.a());
                           double len = delta.horizontalLength();
                           if (!(len < 0.1)) {
                              double t = Math.max(
                                 0.0, Math.min(1.0, support.foot().sub(part.a()).dot(new RoadGeometry.V(delta.x(), 0.0, delta.z())) / (len * len))
                              );
                              double distance = part.a().add(delta.mul(t)).sub(support.foot()).horizontalLength();
                              if (distance <= part.width() / 2.0 + 0.1 && distance < closest) {
                                 base = part;
                                 closest = distance;
                              }
                           }
                        }
                     }

                     if (base != null) {
                        RoadGeometry.V forward = RoadQueries.horizontal(ramp, support.foot()).tangent().horizontalUnit();
                        RoadGeometry.V along = base.b().sub(base.a()).horizontalUnit();
                        if (along.dot(forward) < 0.0) {
                           along = along.mul(-1.0);
                        }

                        RoadGeometry.V nose = base.a().dot(along) < base.b().dot(along) ? base.a() : base.b();

                        for (double gap = 0.27; gap <= 0.57; gap += 0.05) {
                           for (double sideways : new double[]{0.0, 0.08, 0.16, 0.24}) {
                              RoadGeometry.V point = nose.sub(along.mul(gap));
                              RoadQueries.Projection q = RoadQueries.horizontal(ramp, point);
                              RoadGeometry.Sample at = q.sample();
                              point = point.add(at.left().mul(Math.signum(q.lateral()) * sideways));
                              RoadGeometry.V foot = new RoadGeometry.V(point.x(), at.center().y(), point.z());
                              if (!RoadStreetscape.underground(ramp, at.distance())) {
                                 RoadStructures.Part barrel = new RoadStructures.Part(
                                    foot, foot, 0.46, 0.8, true, RoadStructures.Material.CONCRETE, null, null, "sr:crash_cushion"
                                 );
                                 if (!occupied.intersects(barrel) && !motors.stream().anyMatch(m -> RoadClearance.structureInvades(barrel, m, 4.25))) {
                                    boolean supported = barrel.base()
                                       .stream()
                                       .allMatch(p -> decks.stream().anyMatch(m -> RoadQueries.contains(m, p, 0.01, 0.08)));
                                    if (supported) {
                                       if (out.stream().noneMatch(p -> p.a().distance(barrel.a()) < 2.0)) {
                                          out.add(barrel);
                                       }
                                       continue label118;
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         return List.copyOf(out);
      } else {
         return List.of();
      }
   }

   private RoadCrashCushion() {
   }
}
