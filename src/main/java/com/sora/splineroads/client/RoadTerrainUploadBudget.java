package com.sora.splineroads.client;

final class RoadTerrainUploadBudget {
   static final int BYTES = 524288;
   static final int UPLOADS = 2;
   private int bytes;
   private int uploads;
   private long deadline;

   void begin() {
      this.bytes = this.uploads = 0;
      this.deadline = 0L;
   }

   boolean claim(int size, boolean clock) {
      if (this.uploads <= 0 || this.uploads < 2 && (long)this.bytes + (long)size <= 524288L && (!clock || System.nanoTime() < this.deadline)) {
         if (this.uploads == 0) {
            this.deadline = System.nanoTime() + 750000L;
         }

         this.bytes += size;
         this.uploads++;
         return true;
      } else {
         return false;
      }
   }

   int bytes() {
      return this.bytes;
   }

   int uploads() {
      return this.uploads;
   }
}
