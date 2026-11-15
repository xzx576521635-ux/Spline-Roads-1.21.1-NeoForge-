package com.sora.splineroads.client;

import java.util.BitSet;

final class RoadColdSync {
   private static final int MASK = 1048575;
   private final BitSet retired = new BitSet(1048576);
   private long generation;
   private long sentGeneration;
   private long lastRequest = -4611686018427387904L;
   private boolean pending;
   private boolean sent;

   private static long hash(long value) {
      value = (value ^ value >>> 30) * -4658895280553007687L;
      value = (value ^ value >>> 27) * -7723592293110705685L;
      return value ^ value >>> 31;
   }

   void evicted(long chunk) {
      long h = hash(chunk);
      this.retired.set((int)h & 1048575);
      this.retired.set((int)(h >>> 32) & 1048575);
   }

   void arrived(long chunk) {
      long h = hash(chunk);
      if (this.retired.get((int)h & 1048575) && this.retired.get((int)(h >>> 32) & 1048575)) {
         this.generation++;
         this.pending = true;
      }
   }

   boolean request(long tick) {
      if (this.pending && tick - this.lastRequest >= 41L) {
         this.sent = true;
         this.sentGeneration = this.generation;
         this.lastRequest = tick;
         return true;
      } else {
         return false;
      }
   }

   void acknowledged() {
      if (this.sent && this.sentGeneration == this.generation) {
         this.pending = false;
         this.sent = false;
      }
   }

   boolean pending() {
      return this.pending;
   }

   void clear() {
      this.retired.clear();
      this.generation = this.sentGeneration = 0L;
      this.lastRequest = -4611686018427387904L;
      this.pending = this.sent = false;
   }
}
