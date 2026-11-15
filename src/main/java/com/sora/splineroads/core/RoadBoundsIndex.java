package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RoadBoundsIndex {
   private static final int MAX_BUCKETS = 1024;
   private final int tile;
   private final List<RoadBoundsIndex.Bounds> entries = new ArrayList<>();
   private final Map<Long, Set<Integer>> buckets = new HashMap<>();
   private final Set<Integer> large = new HashSet<>();
   private long examined;
   private long queries;
   private final boolean spatial;

   public RoadBoundsIndex(List<RoadBoundsIndex.Bounds> bounds) {
      this(bounds, 64);
   }

   public RoadBoundsIndex(List<RoadBoundsIndex.Bounds> bounds, int tile) {
      if (tile < 1) {
         throw new IllegalArgumentException("Invalid spatial tile size");
      } else {
         this.tile = tile;
         this.spatial = bounds.size() > 64;
         this.entries.addAll(bounds);
         if (this.spatial) {
            for (int i = 0; i < this.entries.size(); i++) {
               this.add(i, this.entries.get(i));
            }
         }
      }
   }

   private RoadBoundsIndex.Grid grid(RoadBoundsIndex.Bounds b, double margin) {
      return new RoadBoundsIndex.Grid(
         (int)Math.floor(Math.nextDown(b.minX - margin) / (double)this.tile),
         (int)Math.floor(Math.nextDown(b.minZ - margin) / (double)this.tile),
         (int)Math.floor(Math.nextUp(b.maxX + margin) / (double)this.tile),
         (int)Math.floor(Math.nextUp(b.maxZ + margin) / (double)this.tile)
      );
   }

   private static long key(int x, int z) {
      return (long)x & 4294967295L | (long)z << 32;
   }

   private void add(int slot, RoadBoundsIndex.Bounds b) {
      RoadBoundsIndex.Grid g = this.grid(b, 0.0);
      if (!g.small()) {
         this.large.add(slot);
      } else {
         for (long x = (long)g.x0; x <= (long)g.x1; x++) {
            for (long z = (long)g.z0; z <= (long)g.z1; z++) {
               this.buckets.computeIfAbsent(key((int)x, (int)z), k -> new HashSet<>()).add(slot);
            }
         }
      }
   }

   public void replace(int slot, RoadBoundsIndex.Bounds next) {
      Objects.requireNonNull(next);
      RoadBoundsIndex.Bounds before = this.entries.get(slot);
      if (!before.equals(next)) {
         if (!this.spatial) {
            this.entries.set(slot, next);
         } else {
            RoadBoundsIndex.Grid g = this.grid(before, 0.0);
            if (g.small()) {
               for (long x = (long)g.x0; x <= (long)g.x1; x++) {
                  for (long z = (long)g.z0; z <= (long)g.z1; z++) {
                     long k = key((int)x, (int)z);
                     Set<Integer> set = this.buckets.get(k);
                     set.remove(slot);
                     if (set.isEmpty()) {
                        this.buckets.remove(k);
                     }
                  }
               }
            } else {
               this.large.remove(slot);
            }

            this.entries.set(slot, next);
            this.add(slot, next);
         }
      }
   }

   public List<Integer> query(RoadBoundsIndex.Bounds bounds, double margin) {
      if (Double.isFinite(margin) && !(margin < 0.0)) {
         this.queries++;
         RoadBoundsIndex.Grid g = this.grid(bounds, margin);
         if (this.spatial && g.small() && this.large.size() <= this.entries.size() / 2) {
            BitSet candidates = new BitSet(this.entries.size());
            int count = 0;

            for (int i : this.large) {
               candidates.set(i);
               count++;
            }

            for (long x = (long)g.x0; x <= (long)g.x1; x++) {
               for (long z = (long)g.z0; z <= (long)g.z1; z++) {
                  Set<Integer> found = this.buckets.get(key((int)x, (int)z));
                  if (found != null) {
                     if (found.size() > this.entries.size() / 2) {
                        return this.scan(bounds, margin);
                     }

                     for (int i : found) {
                        if (!candidates.get(i)) {
                           candidates.set(i);
                           count++;
                        }
                     }

                     if (count > this.entries.size() / 2) {
                        return this.scan(bounds, margin);
                     }
                  }
               }
            }

            ArrayList<Integer> out = new ArrayList<>();

            for (int ix = candidates.nextSetBit(0); ix >= 0; ix = candidates.nextSetBit(ix + 1)) {
               this.examined++;
               if (this.entries.get(ix).overlaps(bounds, margin)) {
                  out.add(ix);
               }
            }

            return out;
         } else {
            return this.scan(bounds, margin);
         }
      } else {
         throw new IllegalArgumentException("Invalid road query margin");
      }
   }

   private List<Integer> scan(RoadBoundsIndex.Bounds bounds, double margin) {
      ArrayList<Integer> out = new ArrayList<>();

      for (int i = 0; i < this.entries.size(); i++) {
         this.examined++;
         if (this.entries.get(i).overlaps(bounds, margin)) {
            out.add(i);
         }
      }

      return out;
   }

   public long examined() {
      return this.examined;
   }

   public long queries() {
      return this.queries;
   }

   public int bucketCount() {
      return this.buckets.size();
   }

   public static record Bounds(double minX, double minZ, double maxX, double maxZ) {
      public Bounds(double minX, double minZ, double maxX, double maxZ) {
         if (Double.isFinite(minX) && Double.isFinite(minZ) && Double.isFinite(maxX) && Double.isFinite(maxZ) && !(minX > maxX) && !(minZ > maxZ)) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
         } else {
            throw new IllegalArgumentException("Invalid road bounds");
         }
      }

      public boolean overlaps(RoadBoundsIndex.Bounds other, double margin) {
         return this.minX <= other.maxX + margin && this.maxX + margin >= other.minX && this.minZ <= other.maxZ + margin && this.maxZ + margin >= other.minZ;
      }
   }

   private static record Grid(int x0, int z0, int x1, int z1) {
      boolean small() {
         long x = (long)this.x1 - (long)this.x0 + 1L;
         long z = (long)this.z1 - (long)this.z0 + 1L;
         return x > 0L && z > 0L && x <= 1024L && z <= 1024L && x * z <= 1024L;
      }
   }
}
