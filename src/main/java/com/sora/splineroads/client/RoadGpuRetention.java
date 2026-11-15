package com.sora.splineroads.client;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

final class RoadGpuRetention<K> {
   private final LinkedHashMap<K, Long> cold = new LinkedHashMap<>();
   private long bytes;

   void update(K key, long allocationBytes, boolean loaded) {
      if (!loaded && allocationBytes > 0L) {
         Long previous = this.cold.put(key, Long.valueOf(allocationBytes));
         this.bytes = this.bytes + (allocationBytes - (previous == null ? 0L : previous));
      } else {
         this.remove(key);
      }
   }

   void remove(K key) {
      Long previous = this.cold.remove(key);
      if (previous != null) {
         this.bytes = this.bytes - previous;
      }
   }

   K takeOldest(long limit) {
      if (this.bytes <= Math.max(0L, limit)) {
         return null;
      } else {
         Iterator<Entry<K, Long>> iterator = this.cold.entrySet().iterator();
         Entry<K, Long> entry = iterator.next();
         K key = entry.getKey();
         this.bytes = this.bytes - entry.getValue();
         iterator.remove();
         return key;
      }
   }

   long bytes() {
      return this.bytes;
   }

   int size() {
      return this.cold.size();
   }

   void clear() {
      this.cold.clear();
      this.bytes = 0L;
   }
}
