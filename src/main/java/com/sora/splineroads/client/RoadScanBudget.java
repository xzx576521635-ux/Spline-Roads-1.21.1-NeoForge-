package com.sora.splineroads.client;

import java.util.function.LongSupplier;

final class RoadScanBudget {
   private final int limit;
   private final long deadline;
   private final LongSupplier clock;
   private int visits;

   RoadScanBudget(int limit, long deadline) {
      this(limit, deadline, System::nanoTime);
   }

   RoadScanBudget(int limit, long deadline, LongSupplier clock) {
      this.limit = Math.max(0, limit);
      this.deadline = deadline;
      this.clock = clock;
   }

   boolean take() {
      if (this.visits < this.limit && (this.visits <= 0 || this.clock.getAsLong() < this.deadline)) {
         this.visits++;
         return true;
      } else {
         return false;
      }
   }

   int visits() {
      return this.visits;
   }
}
