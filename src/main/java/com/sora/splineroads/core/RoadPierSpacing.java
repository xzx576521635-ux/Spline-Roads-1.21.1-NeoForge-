package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RoadPierSpacing {
   public static final double MIN_SPACING = 8.0;
   public static final double MAX_TOP_DIFFERENCE = 2.0;
   private final Map<RoadPierSpacing.Cell, List<RoadPierSpacing.Shaft>> cells;

   private static boolean main(RoadStructures.Part p) {
      return p.pier() && p.material() == RoadStructures.Material.CONCRETE && p.height() > 2.0;
   }

   private static RoadPierSpacing.Shaft shaft(RoadStructures.Part p) {
      return new RoadPierSpacing.Shaft(
         (p.a().x() + p.b().x()) * 0.5, (p.a().z() + p.b().z()) * 0.5, Math.min(p.a().y(), p.b().y()), Math.max(p.a().y(), p.b().y()) + p.height()
      );
   }

   private static int grid(double v) {
      return (int)Math.floor(v / 8.0);
   }

   public RoadPierSpacing(Collection<RoadStructures.Part> parts) {
      HashMap<RoadPierSpacing.Cell, List<RoadPierSpacing.Shaft>> mutable = new HashMap<>();

      for (RoadStructures.Part p : parts) {
         if (main(p)) {
            RoadPierSpacing.Shaft s = shaft(p);
            mutable.computeIfAbsent(new RoadPierSpacing.Cell(grid(s.x()), grid(s.z())), key -> new ArrayList<>()).add(s);
         }
      }

      HashMap<RoadPierSpacing.Cell, List<RoadPierSpacing.Shaft>> frozen = new HashMap<>();
      mutable.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
      this.cells = Map.copyOf(frozen);
   }

   public boolean tooClose(RoadStructures.Part part) {
      if (!main(part)) {
         return false;
      } else {
         RoadPierSpacing.Shaft p = shaft(part);
         int x = grid(p.x());
         int z = grid(p.z());

         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               for (RoadPierSpacing.Shaft q : this.cells.getOrDefault(new RoadPierSpacing.Cell(x + dx, z + dz), List.of())) {
                  if (!(Math.abs(p.top() - q.top()) > 2.0) && !(Math.min(p.top(), q.top()) <= Math.max(p.bottom(), q.bottom()) + 0.1)) {
                     double sx = p.x() - q.x();
                     double sz = p.z() - q.z();
                     if (sx * sx + sz * sz < 63.99999999) {
                        return true;
                     }
                  }
               }
            }
         }

         return false;
      }
   }

   private static record Cell(int x, int z) {
   }

   private static record Shaft(double x, double z, double bottom, double top) {
   }
}
