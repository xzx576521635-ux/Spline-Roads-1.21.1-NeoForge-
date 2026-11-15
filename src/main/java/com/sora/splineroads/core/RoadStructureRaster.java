package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

public final class RoadStructureRaster {
   public static RoadStructureRaster.Result build(List<RoadStructures.Part> parts) {
      boolean tagged = parts.stream().anyMatch(p -> p.luminous() || p.material() == RoadStructures.Material.TUNNEL || RoadsideBypass.walkway(p));
      if (!tagged) {
         return new RoadStructureRaster.Result(RoadRaster.structures(parts, null), Set.of(), Set.of(), Map.of(), parts.size());
      } else {
         Map<RoadRaster.Cell, List<RoadRaster.Box>> cells = new HashMap<>();
         Set<RoadRaster.Cell> lights = new HashSet<>();
         Set<RoadRaster.Cell> shells = new HashSet<>();
         Map<RoadRaster.Cell, Double> walkTops = new HashMap<>();

         for (RoadStructures.Part part : parts) {
            boolean luminous = part.luminous();
            boolean shell = part.material() == RoadStructures.Material.TUNNEL;
            boolean walk = RoadsideBypass.walkway(part);
            Map<RoadRaster.Cell, List<RoadRaster.Box>> raster = RoadRaster.structures(List.of(part), null);

            for (Entry<RoadRaster.Cell, List<RoadRaster.Box>> e : raster.entrySet()) {
               RoadRaster.Cell cell = e.getKey();
               List<RoadRaster.Box> boxes = e.getValue();
               cells.computeIfAbsent(cell, key -> new ArrayList<>()).addAll(boxes);
               if (luminous) {
                  lights.add(cell);
               }

               if (shell) {
                  shells.add(cell);
               }

               if (walk) {
                  walkTops.merge(cell, boxes.stream().mapToDouble(b -> (double)cell.y() + b.y1()).max().orElse((double)cell.y()), Math::max);
               }
            }
         }

         return new RoadStructureRaster.Result(cells, lights, shells, walkTops, parts.size());
      }
   }

   private RoadStructureRaster() {
   }

   public static record Result(
      Map<RoadRaster.Cell, List<RoadRaster.Box>> cells,
      Set<RoadRaster.Cell> lights,
      Set<RoadRaster.Cell> shells,
      Map<RoadRaster.Cell, Double> walkTops,
      int rasterizedParts
   ) {
   }
}
