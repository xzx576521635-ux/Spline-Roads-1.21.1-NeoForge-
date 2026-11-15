package com.sora.splineroads.world;

import com.sora.splineroads.core.RailbedFurniture;
import com.sora.splineroads.core.RailbedProfile;
import com.sora.splineroads.core.RailbedStructures;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadSolidOverlap;
import com.sora.splineroads.core.RoadStructures;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class RoadTravelValidation {
   static void check(List<RoadIndex.Built> roads, Set<UUID> changed, Map<UUID, RoadIndex.Built> previous) {
      ArrayList<Integer> railway = new ArrayList<>();

      for (int i = 0; i < roads.size(); i++) {
         if (roads.get(i).record.settings().options().railbed().active()) {
            railway.add(i);
         }
      }

      for (int ix : railway) {
         for (int j = 0; j < roads.size(); j++) {
            RoadIndex.Built a = roads.get(ix);
            RoadIndex.Built b = roads.get(j);
            if (ix != j
               && (j >= ix || !b.record.settings().options().railbed().active())
               && (changed.contains(a.record.id()) || changed.contains(b.record.id()))
               && RoadIndex.overlapXZ(a.mesh, b.mesh, 2.0)) {
               RoadIndex.Built oldA = previous.get(a.record.id());
               RoadIndex.Built oldB = previous.get(b.record.id());
               boolean sameA = oldA != null
                  && oldA.mesh.samples().equals(a.mesh.samples())
                  && oldA.mesh.settings().thickness() == a.mesh.settings().thickness();
               boolean sameB = oldB != null
                  && oldB.mesh.samples().equals(b.mesh.samples())
                  && oldB.mesh.settings().thickness() == b.mesh.settings().thickness();
               if (!sameA || !sameB) {
                  RoadClearance.check(a.mesh, b.mesh);
               }

               fixtures(a, b, oldA, sameB);
               fixtures(b, a, oldB, sameA);
            }
         }
      }
   }

   private static void fixtures(RoadIndex.Built source, RoadIndex.Built target, RoadIndex.Built old, boolean sameTravel) {
      if (target.record.railway()) {
         Set<RoadStructures.Part> retained = (Set<RoadStructures.Part>)(old == null ? Set.of() : new HashSet<>(old.record.structures()));
         RailbedProfile profile = target.record.settings().options().railbed();
         RoadSolidOverlap.Index trainSpace = profile.active() ? RailbedStructures.clearance(profile.paths()) : null;
         RoadSolidOverlap.Index poleSpace = profile.active() ? RailbedStructures.catenaryClearance(profile.paths()) : null;

         for (RoadStructures.Part part : source.record.structures()) {
            if (!RailbedFurniture.visualOnly(part)
               && (!sameTravel || !retained.contains(part))
               && !RoadClearance.belowSurface(part, target.mesh, 0.025)
               && (
                  trainSpace == null
                     ? RoadInteractions.invades(part, target.mesh)
                     : (part.model().startsWith("sr:catenary") ? poleSpace : trainSpace).intersects(part)
               )) {
               throw new IllegalArgumentException(
                  String.format(Locale.ROOT, "规划后的构件与铁路路基通行空间相交：%.2f, %.2f, %.2f（%s）", part.a().x(), part.a().y(), part.a().z(), part.material())
               );
            }
         }
      }
   }

   static List<RoadStructures.Part> deckParts(RoadGeometry.Mesh mesh) {
      ArrayList<RoadStructures.Part> result = new ArrayList<>();
      List<RoadGeometry.Sample> samples = mesh.samples();
      double depth = mesh.settings().thickness();

      for (int i = 1; i < samples.size(); i++) {
         RoadGeometry.Sample a = samples.get(i - 1);
         RoadGeometry.Sample b = samples.get(i);
         result.add(
            new RoadStructures.Part(
               a.at(0.0, depth),
               b.at(0.0, depth),
               1.0,
               depth,
               false,
               RoadStructures.Material.CONCRETE,
               a.left().mul(a.halfWidth()),
               b.left().mul(b.halfWidth()),
               ""
            )
         );
      }

      return result;
   }

   private RoadTravelValidation() {
   }
}
