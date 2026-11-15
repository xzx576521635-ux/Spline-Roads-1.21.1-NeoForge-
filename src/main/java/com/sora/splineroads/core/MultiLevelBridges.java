package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MultiLevelBridges {
   public static final String MODEL = "sr:stacked_bridge:";

   public static boolean compatible(RoadGeometry.Mesh lower, RoadGeometry.Mesh upper) {
      if (lower != upper
         && !lower.closed()
         && !upper.closed()
         && !(lower.length() < 8.0)
         && !(upper.length() < 8.0)
         && lower.settings().structure() != RoadGeometry.Structure.TUNNEL
         && upper.settings().structure() != RoadGeometry.Structure.TUNNEL
         && elevatedDeck(lower)
         && elevatedDeck(upper)
         && !LaneDeck.hasOpenings(lower)
         && !LaneDeck.hasOpenings(upper)) {
         double clearance = RoadStructureClearance.headroom(lower);
         if (lower.settings().options().railbed().active() && lower.settings().options().railbed().fittings().catenary()) {
            clearance = Math.max(clearance, lower.settings().options().railbed().fittings().wireHeight() + 0.55);
         }

         for (RoadGeometry.Sample s : lower.samples()) {
            RoadQueries.Projection q = RoadQueries.horizontal(upper, s.center());
            RoadGeometry.Sample t = q.sample();
            double along = s.center().sub(t.center()).dot(q.tangent().horizontalUnit());
            if ((!(t.distance() < 1.0E-6) || !(along < -0.6)) && (!(t.distance() > upper.length() - 1.0E-6) || !(along > 0.6))) {
               double gap = t.center().y() - upper.settings().thickness() - RoadInfrastructure.girderDepth(upper) - s.center().y();
               if (!(gap < clearance - 1.0E-7)
                  && !(gap > 96.0)
                  && !(Math.abs(t.left().horizontalUnit().dot(s.left().horizontalUnit())) < 0.985)
                  && !(q.horizontalDistance() > Math.min(s.halfWidth(), t.halfWidth()) + 0.01)
                  && !RoadStreetscape.underground(lower, s.distance())
                  && !RoadStreetscape.underground(upper, t.distance())) {
                  continue;
               }

               return false;
            }

            return false;
         }

         return true;
      } else {
         return false;
      }
   }

   private static boolean elevatedDeck(RoadGeometry.Mesh mesh) {
      return mesh.settings().structure() == RoadGeometry.Structure.BRIDGE
         || mesh.settings().structure() == RoadGeometry.Structure.AUTO
            && mesh.samples().stream().anyMatch(s -> RoadStreetscape.raised(mesh, s, -1) && RoadStreetscape.raised(mesh, s, 1));
   }

   public static List<RoadStructures.Part> supports(
      RoadGeometry.Mesh upper,
      List<RoadGeometry.Mesh> lowers,
      RoadGeometry.Sample at,
      double thickness,
      double depth,
      double width,
      double offset,
      RoadStructures.Ground ground
   ) {
      ArrayList<RoadGeometry.Mesh> beneath = new ArrayList<>();
      double reach = offset;

      for (RoadGeometry.Mesh lower : lowers) {
         RoadQueries.Projection q = RoadQueries.horizontal(lower, at.center());
         double along = at.center().sub(q.sample().center()).dot(q.tangent().horizontalUnit());
         if ((!(q.sample().distance() < 1.0E-6) || !(along < -0.1))
            && (!(q.sample().distance() > lower.length() - 1.0E-6) || !(along > 0.1))
            && RoadStreetscape.raised(lower, q.sample(), -1)
            && RoadStreetscape.raised(lower, q.sample(), 1)) {
            beneath.add(lower);
            reach = Math.max(reach, q.horizontalDistance() + q.sample().halfWidth() + width / 2.0 + 0.6);
         }
      }

      if (beneath.isEmpty()) {
         return null;
      } else {
         List<RoadStructures.Part> frame = RoadSupports.portal(at, thickness, depth, reach, width, ground);
         if (frame.isEmpty()) {
            throw new IllegalArgumentException("多层桥的共用桥墩没有安全基础，请调整桥宽或桥位");
         } else {
            List<RoadStructures.Part> shafts = frame.stream().filter(RoadStructures.Part::pier).toList();
            if (shafts.size() < 2) {
               throw new IllegalArgumentException("多层桥缺少成对支撑");
            } else {
               RoadStructures.Part min = shafts.stream().min(Comparator.comparingDouble(p -> p.a().sub(at.center()).dot(at.left()))).orElseThrow();
               RoadStructures.Part max = shafts.stream().max(Comparator.comparingDouble(p -> p.a().sub(at.center()).dot(at.left()))).orElseThrow();
               ArrayList<RoadStructures.Part> parts = new ArrayList<>(frame);

               for (RoadGeometry.Mesh lowerx : beneath) {
                  RoadQueries.Projection q = RoadQueries.horizontal(lowerx, at.center());
                  double top = q.sample().center().y() - lowerx.settings().thickness();
                  double floor = ground.top(q.sample().center().x(), q.sample().center().z(), top);
                  if (!Double.isFinite(floor) || !(floor >= top - 0.2)) {
                     RoadStructures.Part beam = new RoadStructures.Part(
                        new RoadGeometry.V(min.a().x(), top - 0.8, min.a().z()),
                        new RoadGeometry.V(max.a().x(), top - 0.8, max.a().z()),
                        Math.max(1.5, width),
                        0.8,
                        false,
                        RoadStructures.Material.CONCRETE
                     );
                     if (ground.blocked(beam)) {
                        throw new IllegalArgumentException("多层桥横梁侵入既有通行空间，请调整层间净空");
                     }

                     parts.add(beam);
                  }
               }

               String model = "sr:stacked_bridge:" + upper.settings().options().infrastructure().bridge().name();
               return parts.stream()
                  .map(p -> new RoadStructures.Part(p.a(), p.b(), p.width(), p.height(), p.pier(), p.material(), p.frameA(), p.frameB(), model, p.heights()))
                  .toList();
            }
         }
      }
   }

   private MultiLevelBridges() {
   }
}
