package com.sora.splineroads.net;

import java.util.function.LongSupplier;

final class RoadPublicationBudget {
   static final int FAMILIES = 4;
   static final int MAX_VISITS = 64;
   static final int MAX_BYTES = 262144;
   private final LongSupplier clock;
   private int nextFamily;

   RoadPublicationBudget() {
      this(System::nanoTime);
   }

   RoadPublicationBudget(LongSupplier clock) {
      this.clock = clock;
   }

   int publish(long deadline, RoadPublicationBudget.Publisher publisher) {
      int bytes = 0;

      for (int visits = 0; visits < 64 && bytes < 262144 && this.clock.getAsLong() < deadline; visits++) {
         int family = this.nextFamily;
         this.nextFamily = (family + 1) % 4;
         int sent = publisher.step(family, 262144 - bytes);
         if (sent < 0 || sent > 262144 - bytes) {
            throw new IllegalStateException("Invalid road publication accounting");
         }

         bytes += sent;
      }

      return bytes;
   }

   @FunctionalInterface
   interface Publisher {
      int step(int var1, int var2);
   }
}
