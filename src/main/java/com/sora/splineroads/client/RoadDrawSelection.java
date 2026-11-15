package com.sora.splineroads.client;

import java.util.Arrays;

final class RoadDrawSelection {
   static final RoadDrawSelection EMPTY = new RoadDrawSelection(false, new int[0], 0L, 0L);
   final boolean all;
   final int[] spans;
   final long indices;
   final long surfaceIndices;

   private RoadDrawSelection(boolean all, int[] spans, long indices, long surfaceIndices) {
      this.all = all;
      this.spans = spans;
      this.indices = indices;
      this.surfaceIndices = surfaceIndices;
   }

   static final class Builder {
      private int[] spans = new int[8];
      private int size;
      private boolean all = true;
      private long indices;
      private long surfaceIndices;

      void add(int first, int count, boolean visible, boolean surface) {
         this.all &= visible;
         if (visible && count != 0) {
            this.indices += (long)count;
            if (surface) {
               this.surfaceIndices += (long)count;
            }

            if (this.size > 0 && this.spans[this.size - 2] + this.spans[this.size - 1] == first) {
               this.spans[this.size - 1] = this.spans[this.size - 1] + count;
            } else {
               if (this.size + 2 > this.spans.length) {
                  this.spans = Arrays.copyOf(this.spans, this.spans.length * 2);
               }

               this.spans[this.size++] = first;
               this.spans[this.size++] = count;
            }
         }
      }

      RoadDrawSelection build() {
         return this.indices == 0L
            ? RoadDrawSelection.EMPTY
            : new RoadDrawSelection(this.all, this.all ? new int[0] : Arrays.copyOf(this.spans, this.size), this.indices, this.surfaceIndices);
      }
   }
}
