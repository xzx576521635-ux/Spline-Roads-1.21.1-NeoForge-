package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadSurface;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Lane paint cells derived from a road's rendered markings.
 *
 * <p>The paint is stored on the top road collider cell as a block state property instead of
 * invisible marker blocks, so Xaero's map can colour lane markings without any extra blocks
 * in the world (and therefore without any cleanup after a road is deleted).</p>
 */
final class RoadPaint {
   private static final Map<RoadIndex.Built, Map<Long, RoadBlocks.Paint>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

   static Map<Long, RoadBlocks.Paint> cells(RoadIndex.Built road) {
      Map<Long, RoadBlocks.Paint> cached;
      synchronized (CACHE) {
         cached = CACHE.get(road);
      }

      if (cached != null) {
         return cached;
      }

      Map<Long, RoadBlocks.Paint> built = build(road);
      synchronized (CACHE) {
         if (CACHE.size() > 512) {
            CACHE.clear();
         }

         CACHE.put(road, built);
      }

      return built;
   }

   static RoadBlocks.Paint at(RoadIndex.Built road, int x, int z) {
      return cells(road).getOrDefault(key(x, z), RoadBlocks.Paint.NONE);
   }

   static long key(int x, int z) {
      return (long)x & 4294967295L | ((long)z & 4294967295L) << 32;
   }

   private static Map<Long, RoadBlocks.Paint> build(RoadIndex.Built road) {
      RoadSurface.Geometry geometry;
      try {
         geometry = RoadSurface.build(road.renderMesh(), List.of(), List.of());
      } catch (Throwable ignored) {
         return Map.of();
      }

      Map<Long, RoadBlocks.Paint> out = new HashMap<>();

      for (RoadSurface.Face face : geometry.markings()) {
         List<RoadGeometry.V> points = face.points();
         if (points.size() < 3) {
            continue;
         }

         double minX = Double.MAX_VALUE;
         double maxX = -Double.MAX_VALUE;
         double minZ = Double.MAX_VALUE;
         double maxZ = -Double.MAX_VALUE;

         for (RoadGeometry.V point : points) {
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z());
            maxZ = Math.max(maxZ, point.z());
         }

         if (maxX - minX > 96.0 || maxZ - minZ > 96.0) {
            continue;
         }

         int rgb = face.color() & 16777215;
         int r = rgb >> 16 & 0xFF;
         int g = rgb >> 8 & 0xFF;
         int b = rgb & 0xFF;
         RoadBlocks.Paint paint = r > 180 && g > 140 && b < 150 ? RoadBlocks.Paint.YELLOW : RoadBlocks.Paint.WHITE;

         for (int x = (int)Math.floor(minX); x <= (int)Math.floor(maxX); x++) {
            for (int z = (int)Math.floor(minZ); z <= (int)Math.floor(maxZ); z++) {
               if (inside(points, (double)x + 0.5, (double)z + 0.5)) {
                  out.put(key(x, z), paint);
               }
            }
         }
      }

      return Map.copyOf(out);
   }

   private static boolean inside(List<RoadGeometry.V> polygon, double x, double z) {
      boolean inside = false;

      for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
         RoadGeometry.V a = polygon.get(i);
         RoadGeometry.V b = polygon.get(j);
         if ((a.z() > z) != (b.z() > z) && x < (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) {
            inside = !inside;
         }
      }

      return inside;
   }

   private RoadPaint() {
   }
}
