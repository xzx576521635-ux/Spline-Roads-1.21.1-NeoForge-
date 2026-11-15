package com.sora.splineroads.core;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public final class StreetTerrain implements StreetPlan.Terrain {
   private final Map<StreetPlan.Cell, StreetTerrain.Chunk> chunks;

   public StreetTerrain(Map<StreetPlan.Cell, StreetTerrain.Chunk> chunks) {
      if (chunks.size() > 1024) {
         throw new IllegalArgumentException("地形快照区块过多");
      } else {
         this.chunks = Collections.unmodifiableMap(new TreeMap<>(chunks));
      }
   }

   public Map<StreetPlan.Cell, StreetTerrain.Chunk> chunks() {
      return this.chunks;
   }

   @Override
   public StreetPlan.Surface at(double x, double z) {
      StreetTerrain.Chunk chunk = this.chunks.get(StreetPlan.Cell.at(x, z));
      return chunk == null ? new StreetPlan.Surface(Double.NaN, StreetPlan.Ground.UNKNOWN) : chunk.at(column(x, z));
   }

   public static int column(double x, double z) {
      return Math.floorMod((int)Math.floor(x), 16) + 16 * Math.floorMod((int)Math.floor(z), 16);
   }

   public boolean complete(StreetPlan.Input input) {
      return this.chunks.keySet().containsAll(input.captureArea());
   }

   public StreetPlan.Terrain forPlan(StreetPlan.Input input) {
      return (x, z) -> {
         StreetPlan.Surface surface = this.at(x, z);
         if (surface.ground() == StreetPlan.Ground.ROAD) {
            for (StreetPlan.Entry entry : input.entries()) {
               if (entry.existing() && entry.point().distance(new StreetPlan.Point(x, z)) <= input.options().fullWidth(0) / 2.0 + 1.0) {
                  return new StreetPlan.Surface(entry.height(), StreetPlan.Ground.LAND);
               }
            }
         }

         return surface;
      };
   }

   public static final class Chunk {
      private final int[] height;
      private final byte[] ground;
      private final long[] fingerprint;

      public Chunk(int[] height, byte[] ground, long[] fingerprint) {
         if (height.length == 256 && ground.length == 256 && fingerprint.length == 256) {
            this.height = (int[])height.clone();
            this.ground = (byte[])ground.clone();
            this.fingerprint = (long[])fingerprint.clone();

            for (int i = 0; i < 256; i++) {
               if (ground[i] < 0 || ground[i] >= StreetPlan.Ground.values().length || height[i] < -2048 || height[i] > 4096) {
                  throw new IllegalArgumentException("地形快照数据无效");
               }
            }
         } else {
            throw new IllegalArgumentException("地形快照尺寸无效");
         }
      }

      public int[] heights() {
         return (int[])this.height.clone();
      }

      public byte[] ground() {
         return (byte[])this.ground.clone();
      }

      public long[] fingerprints() {
         return (long[])this.fingerprint.clone();
      }

      public StreetPlan.Surface at(int column) {
         return new StreetPlan.Surface((double)this.height[column], StreetPlan.Ground.values()[this.ground[column]]);
      }

      public long fingerprint(int column) {
         return this.fingerprint[column];
      }
   }
}
