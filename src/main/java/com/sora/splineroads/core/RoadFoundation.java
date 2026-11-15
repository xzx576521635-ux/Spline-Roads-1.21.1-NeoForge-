package com.sora.splineroads.core;

import java.util.Map;

public final class RoadFoundation {
   public static <T> T source(long key, T current, boolean owned, boolean air, Map<Long, T> original, Map<Long, T> retained, T empty) {
      if (owned || air && original.containsKey(key)) {
         return original.containsKey(key) ? original.get(key) : retained.getOrDefault(key, empty);
      } else {
         return current;
      }
   }

   private RoadFoundation() {
   }
}
