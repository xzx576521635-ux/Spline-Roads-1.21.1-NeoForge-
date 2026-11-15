package com.sora.splineroads.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SignCandidateIndex<K> {
   private static final int CELL_SIZE = 64;
   private final Map<K, Long> membership = new HashMap<>();
   private final Map<Long, Set<K>> buckets = new HashMap<>();

   void put(K key, double x, double z) {
      long cell = key(cell(x), cell(z));
      Long old = this.membership.get(key);
      if (old == null || old != cell) {
         this.remove(key);
         this.membership.put(key, cell);
         this.buckets.computeIfAbsent(cell, k -> new HashSet<>()).add(key);
      }
   }

   void remove(K key) {
      Long cell = this.membership.remove(key);
      if (cell != null) {
         Set<K> bucket = this.buckets.get(cell);
         bucket.remove(key);
         if (bucket.isEmpty()) {
            this.buckets.remove(cell);
         }
      }
   }

   List<K> near(double x, double z) {
      ArrayList<K> result = new ArrayList<>();
      int x0 = cell(x - 144.0);
      int x1 = cell(x + 144.0);
      int z0 = cell(z - 144.0);
      int z1 = cell(z + 144.0);

      for (long cx = (long)x0; cx <= (long)x1; cx++) {
         for (long cz = (long)z0; cz <= (long)z1; cz++) {
            Set<K> bucket = this.buckets.get(key((int)cx, (int)cz));
            if (bucket != null) {
               result.addAll(bucket);
            }
         }
      }

      return result;
   }

   void clear() {
      this.membership.clear();
      this.buckets.clear();
   }

   int size() {
      return this.membership.size();
   }

   int bucketCount() {
      return this.buckets.size();
   }

   private static int cell(double value) {
      return (int)Math.floor(value / 64.0);
   }

   private static long key(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }
}
