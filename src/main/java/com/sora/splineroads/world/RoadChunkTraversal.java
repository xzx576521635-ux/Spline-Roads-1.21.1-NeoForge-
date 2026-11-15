package com.sora.splineroads.world;

import java.util.LinkedHashSet;
import java.util.Set;

final class RoadChunkTraversal {
   private static long chunk(int x, int z) {
      return (long)x & 4294967295L | ((long)z & 4294967295L) << 32;
   }

   static Set<Long> trace(double ox, double oz, double vx, double vz, double range) {
      if (!(range < 0.0) && Double.isFinite(ox) && Double.isFinite(oz) && Double.isFinite(vx) && Double.isFinite(vz) && Double.isFinite(range)) {
         int x = (int)Math.floor(ox / 16.0);
         int z = (int)Math.floor(oz / 16.0);
         int sx = vx > 0.0 ? 1 : (vx < 0.0 ? -1 : 0);
         int sz = vz > 0.0 ? 1 : (vz < 0.0 ? -1 : 0);
         double dx = sx == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(vx);
         double dz = sz == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(vz);
         double tx = sx == 0 ? Double.POSITIVE_INFINITY : ((double)(sx > 0 ? x + 1 : x) * 16.0 - ox) / vx;
         double tz = sz == 0 ? Double.POSITIVE_INFINITY : ((double)(sz > 0 ? z + 1 : z) * 16.0 - oz) / vz;
         boolean xBoundary = sx == 0 && ox == (double)x * 16.0;
         boolean zBoundary = sz == 0 && oz == (double)z * 16.0;
         LinkedHashSet<Long> chunks = new LinkedHashSet<>();
         if (ox == (double)x * 16.0) {
            chunks.add(Long.valueOf(chunk(x - 1, z)));
         }

         if (oz == (double)z * 16.0) {
            chunks.add(Long.valueOf(chunk(x, z - 1)));
         }

         if (ox == (double)x * 16.0 && oz == (double)z * 16.0) {
            chunks.add(Long.valueOf(chunk(x - 1, z - 1)));
         }

         while (true) {
            chunks.add(Long.valueOf(chunk(x, z)));
            if (xBoundary) {
               chunks.add(Long.valueOf(chunk(x - 1, z)));
            }

            if (zBoundary) {
               chunks.add(Long.valueOf(chunk(x, z - 1)));
            }

            if (xBoundary && zBoundary) {
               chunks.add(Long.valueOf(chunk(x - 1, z - 1)));
            }

            double next = Math.min(tx, tz);
            if (!Double.isFinite(next) || next > range) {
               return chunks;
            }

            if (Math.abs(tx - tz) <= 1.0E-12 * Math.max(1.0, Math.abs(next))) {
               chunks.add(Long.valueOf(chunk(x + sx, z)));
               chunks.add(Long.valueOf(chunk(x, z + sz)));
               x += sx;
               z += sz;
               tx += dx;
               tz += dz;
            } else if (tx < tz) {
               x += sx;
               tx += dx;
            } else {
               z += sz;
               tz += dz;
            }
         }
      } else {
         return Set.of();
      }
   }

   private RoadChunkTraversal() {
   }
}
