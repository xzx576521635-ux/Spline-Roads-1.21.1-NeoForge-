package com.sora.splineroads.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

final class RoadLightReadSet {
   static final int PACKED_MASK = 16777215;
   static final int OPAQUE = 16777216;
   static final int READ_OPAQUE = 33554432;
   static final int READ_PACKED = 67108864;
   static final int UNAVAILABLE_OPAQUE = 134217728;
   static final int UNAVAILABLE_PACKED = 268435456;
   static final RoadLightReadSet EMPTY = new RoadLightReadSet(new long[0], new int[0], false);
   private final long[] positions;
   private final int[] values;
   private final boolean inconsistent;

   private RoadLightReadSet(long[] positions, int[] values, boolean inconsistent) {
      this.positions = positions;
      this.values = values;
      this.inconsistent = inconsistent;
   }

   int size() {
      return this.positions.length;
   }

   long bytes() {
      return this.positions.length == 0 ? 0L : 64L + 12L * (long)this.positions.length;
   }

   RoadLightReadSet.Cursor cursor() {
      return new RoadLightReadSet.Cursor();
   }

   private boolean changedAt(int index, RoadLightReadSet.Access access) {
      int requested = this.values[index] & 100663296;
      int actual = access.sample(this.positions[index], requested);
      int mask = ((requested & 33554432) != 0 && (actual & 134217728) == 0 ? 16777216 : 0)
         | ((requested & 67108864) != 0 && (actual & 268435456) == 0 ? 16777215 : 0);
      return (actual & mask) != (this.values[index] & mask);
   }

   boolean changed(RoadLightReadSet.Access access) {
      if (this.inconsistent) {
         return true;
      } else {
         for (int i = 0; i < this.positions.length; i++) {
            if (this.changedAt(i, access)) {
               return true;
            }
         }

         return false;
      }
   }

   private int remembered(long position, int requested, int otherwise) {
      int index = Arrays.binarySearch(this.positions, position);
      return index >= 0 && (this.values[index] & requested) != 0 ? this.values[index] : otherwise;
   }

   interface Access {
      int sample(long var1, int var3);
   }

   static final class Builder {
      private final Long2IntOpenHashMap values = new Long2IntOpenHashMap();
      private final RoadLightReadSet previous;
      private boolean inconsistent;

      Builder() {
         this(RoadLightReadSet.EMPTY);
      }

      Builder(RoadLightReadSet previous) {
         this.previous = previous;
      }

      private int remembered(long position, int requested, int otherwise) {
         int present = this.values.get(position);
         return (present & requested) != 0 ? present : this.previous.remembered(position, requested, otherwise);
      }

      boolean opaque(long position, boolean value, boolean available) {
         if (!available) {
            value = (this.remembered(position, 33554432, value ? 16777216 : 0) & 16777216) != 0;
         }

         this.opaque(position, value);
         return value;
      }

      int packed(long position, int value, boolean available) {
         if (!available) {
            value = this.remembered(position, 67108864, value) & 16777215;
         }

         this.packed(position, value);
         return value;
      }

      void opaque(long position, boolean opaque) {
         int before = this.values.get(position);
         int value = opaque ? 16777216 : 0;
         if ((before & 33554432) != 0) {
            if ((before & 16777216) != value) {
               this.inconsistent = true;
               this.values.put(position, before & -16777217 | value);
            }
         } else {
            this.values.put(position, before | 33554432 | value);
         }
      }

      void packed(long position, int packed) {
         int before = this.values.get(position);
         if ((before & 67108864) != 0) {
            if ((before & 16777215) != packed) {
               this.inconsistent = true;
               this.values.put(position, before & 0xFF000000 | packed);
            }
         } else {
            this.values.put(position, before | 67108864 | packed);
         }
      }

      RoadLightReadSet build() {
         if (this.values.isEmpty()) {
            return RoadLightReadSet.EMPTY;
         } else {
            long[] positions = this.values.keySet().toLongArray();
            Arrays.sort(positions);
            int[] samples = new int[positions.length];

            for (int i = 0; i < positions.length; i++) {
               samples[i] = this.values.get(positions[i]);
            }

            return new RoadLightReadSet(positions, samples, this.inconsistent);
         }
      }
   }

   final class Cursor {
      private int index;
      private boolean changed = RoadLightReadSet.this.inconsistent;

      int advance(RoadLightReadSet.Access access, int allowance) {
         int read;
         for (read = 0; !this.changed && this.index < RoadLightReadSet.this.positions.length && read < Math.max(0, allowance); read++) {
            this.changed = RoadLightReadSet.this.changedAt(this.index++, access);
         }

         return read;
      }

      boolean complete() {
         return this.changed || this.index == RoadLightReadSet.this.positions.length;
      }

      boolean changed() {
         return this.changed;
      }
   }
}
