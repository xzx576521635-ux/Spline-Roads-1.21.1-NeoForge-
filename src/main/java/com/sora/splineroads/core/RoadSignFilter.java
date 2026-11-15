package com.sora.splineroads.core;

import java.util.HashSet;
import java.util.List;

public final class RoadSignFilter {
   public static boolean signPart(RoadStructures.Part p, RoadGeometry.Mesh mesh) {
      String id = p.model();
      return !id.startsWith("sr:gantry/")
            && !id.startsWith("sr:toll_model_name_")
            && !id.startsWith("sr:toll_model_label_")
            && !id.equals("sr:toll_ETC")
            && !id.equals("sr:toll_manual")
         ? id.isEmpty()
            && mesh.settings().structure() != RoadGeometry.Structure.TUNNEL
            && (
               p.material() == RoadStructures.Material.SIGN_GREEN
                  || p.material() == RoadStructures.Material.SIGN_BLUE
                  || p.material() == RoadStructures.Material.SIGN_WHITE
                  || p.material() == RoadStructures.Material.GANTRY_FRAME
            )
         : true;
   }

   public static List<RoadStructures.Part> withoutSigns(RoadGeometry.Mesh mesh, List<RoadStructures.Part> saved) {
      HashSet<RoadStructures.Part> removed = new HashSet<>();
      if (saved.stream().noneMatch(p -> p.model().startsWith("sr:gantry/"))) {
         for (int slot : RoadGantry.slots(mesh)) {
            try {
               removed.addAll(RoadGantry.members(mesh, slot, saved));
            } catch (IllegalArgumentException var6) {
            }
         }
      }

      return saved.stream().filter(p -> !signPart(p, mesh) && !removed.contains(p)).toList();
   }

   private RoadSignFilter() {
   }
}
