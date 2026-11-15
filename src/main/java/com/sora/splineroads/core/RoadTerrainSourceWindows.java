package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RoadTerrainSourceWindows<K> {
   private final Map<K, RoadTerrainMesh.Source> windows = new HashMap<>();
   private final Map<K, Long> costs = new HashMap<>();
   private final Map<RoadTerrainMesh.Chunk, Map<K, RoadSurface.Geometry>> parts = new LinkedHashMap<>();
   private final Map<RoadTerrainMesh.Chunk, RoadSurface.Geometry> combined = new LinkedHashMap<>();
   private long bytes;

   public RoadTerrainSourceWindows.Publication replace(K window, RoadTerrainMesh.Source source) {
      LinkedHashSet<RoadTerrainMesh.Chunk> changed = new LinkedHashSet<>(source.chunks().keySet());
      RoadTerrainMesh.Source old = this.windows.put(window, source);
      if (old != null) {
         for (RoadTerrainMesh.Chunk chunk : old.chunks().keySet()) {
            changed.add(chunk);
            Map<K, RoadSurface.Geometry> contributions = this.parts.get(chunk);
            if (contributions != null) {
               contributions.remove(window);
               if (contributions.isEmpty()) {
                  this.parts.remove(chunk);
               }
            }
         }
      }

      source.chunks().forEach((chunkx, geometryx) -> this.parts.computeIfAbsent(chunkx, key -> new LinkedHashMap<>()).put(window, geometryx));

      for (RoadTerrainMesh.Chunk chunkx : changed) {
         Map<K, RoadSurface.Geometry> contributions = this.parts.get(chunkx);
         if (contributions == null || contributions.isEmpty()) {
            this.combined.remove(chunkx);
         } else if (contributions.size() == 1) {
            this.combined.put(chunkx, contributions.values().iterator().next());
         } else {
            ArrayList<RoadSurface.Face> pavement = new ArrayList<>();
            ArrayList<RoadSurface.Face> paint = new ArrayList<>();

            for (RoadSurface.Geometry geometry : contributions.values()) {
               pavement.addAll(geometry.pavement());
               paint.addAll(geometry.markings());
            }

            this.combined.put(chunkx, new RoadSurface.Geometry(List.copyOf(pavement), List.copyOf(paint)));
         }
      }

      long cost = bytes(source);
      this.bytes = this.bytes + (cost - this.costs.getOrDefault(window, 0L));
      this.costs.put(window, cost);
      return new RoadTerrainSourceWindows.Publication(
         new RoadTerrainMesh.Source(Collections.unmodifiableMap(new LinkedHashMap<>(this.combined))), Set.copyOf(changed), this.bytes
      );
   }

   public static long bytes(RoadTerrainMesh.Source source) {
      long result = 0L;
      Set<RoadSurface.Face> faces = Collections.newSetFromMap(new IdentityHashMap<>());

      for (RoadSurface.Geometry geometry : source.chunks().values()) {
         for (List<RoadSurface.Face> list : List.of(geometry.pavement(), geometry.markings())) {
            result += 64L + (long)list.size() * 16L;
            faces.addAll(list);
         }
      }

      for (RoadSurface.Face face : faces) {
         result += 96L + (long)face.points().size() * 64L;
      }

      return result;
   }

   public static record Publication(RoadTerrainMesh.Source source, Set<RoadTerrainMesh.Chunk> changed, long bytes) {
   }
}
