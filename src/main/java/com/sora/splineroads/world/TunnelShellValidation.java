package com.sora.splineroads.world;

import com.sora.splineroads.core.LaneDeck;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadStructures;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TunnelShellValidation {
   static void check(List<RoadIndex.Built> planned, Set<UUID> changed, Map<UUID, RoadIndex.Built> previous) {
      HashMap<UUID, Boolean> travelMatches = new HashMap<>();

      for (RoadIndex.Built tube : planned) {
         if (tube.hasTunnel()) {
            RoadIndex.Built oldTube = previous.get(tube.record.id());
            Set<RoadStructures.Part> saved = (Set<RoadStructures.Part>)(oldTube == null ? Set.of() : new HashSet<>(oldTube.record.structures()));

            for (RoadIndex.Built other : planned) {
               if (!other.record.id().equals(tube.record.id())
                  && (changed.contains(tube.record.id()) || changed.contains(other.record.id()))
                  && RoadIndex.overlapXZ(tube.mesh, other.mesh, 2.0)) {
                  boolean unchangedTravel = travelMatches.computeIfAbsent(other.record.id(), id -> {
                     RoadIndex.Built oldOther = previous.get(id);
                     return oldOther != null && sameTravel(oldOther.mesh, other.mesh);
                  });

                  for (RoadStructures.Part part : tube.record.structures()) {
                     if (part.material() == RoadStructures.Material.TUNNEL
                        && (!unchangedTravel || !saved.contains(part))
                        && RoadInteractions.invades(part, other.mesh)) {
                        throw new LaneRamps.CandidateRejected(
                           "隧道墙顶侵入另一条道路的通行空间：隧道 "
                              + tube.record.id()
                              + "，道路 "
                              + other.record.id()
                              + String.format(Locale.ROOT, "，结构位置 %.2f %.2f %.2f；请调整高度或走线", part.a().x(), part.a().y(), part.a().z())
                        );
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean sameTravel(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      if (a == b) {
         return true;
      } else if (a.settings().thickness() == b.settings().thickness() && a.samples().equals(b.samples())) {
         List<RoadGeometry.Sample> samples = a.samples();

         for (int i = 1; i < samples.size(); i++) {
            if ((i & 63) == 0) {
               RoadPlanningBudget.check();
            }

            List<LaneDeck.Strip> before = LaneDeck.drivingStrips(a, samples.get(i - 1), samples.get(i));
            List<LaneDeck.Strip> after = LaneDeck.drivingStrips(b, b.samples().get(i - 1), b.samples().get(i));
            if (before.size() != after.size()) {
               return false;
            }

            for (int j = 0; j < before.size(); j++) {
               LaneDeck.Strip x = before.get(j);
               LaneDeck.Strip y = after.get(j);
               if (!x.al().equals(y.al()) || !x.ar().equals(y.ar()) || !x.bl().equals(y.bl()) || !x.br().equals(y.br())) {
                  return false;
               }
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private TunnelShellValidation() {
   }
}
