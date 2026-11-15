package com.sora.splineroads.core;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class RoadStructureClearance {
   private final Map<RoadGeometry.Mesh, List<RoadGeometry.Mesh>> cycleDecks = new IdentityHashMap<>();

   public static double headroom(RoadGeometry.Mesh road) {
      return Math.max(road.settings().style() == RoadGeometry.Style.RAILBED ? 6.0 : 4.25, RoadInfrastructure.clearance(road.settings()));
   }

   public boolean cycleBlocked(RoadStructures.Part part, RoadGeometry.Mesh road) {
      if (part.pier() || part.material() == RoadStructures.Material.CONCRETE && !(part.height() <= 0.4)) {
         for (RoadGeometry.Mesh deck : this.cycleDecks.computeIfAbsent(road, RoadsideBypass::travel)) {
            if (RoadClearance.structureInvades(part, deck, 4.4)) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static boolean selfSupportBlocked(RoadStructures.Part part, RoadGeometry.Mesh road) {
      if (part.pier() || part.material() == RoadStructures.Material.CONCRETE && !(part.width() < 1.0) && !(part.height() < 0.5)) {
         return !part.pier() && part.material() == RoadStructures.Material.CONCRETE && RoadClearance.belowSurface(part, road, 0.025)
            ? false
            : RoadClearance.structureInvades(part, road, headroom(road));
      } else {
         return false;
      }
   }

   public static boolean pierBlocked(RoadStructures.Part part, RoadGeometry.Mesh road, List<RoadGeometry.Mesh> medianContext) {
      if (part.material() == RoadStructures.Material.CONCRETE && RoadSidewalks.blocksTactile(part, road)) {
         return true;
      } else if (!RoadGantryMounts.foot(part) && RoadStructures.fitsMedian(part, road, medianContext)) {
         return false;
      } else {
         return RoadGantryMounts.foot(part)
            ? RoadGantryMounts.blockedByRoad(part, road, headroom(road))
            : RoadClearance.structureInvades(part, road, headroom(road));
      }
   }
}
