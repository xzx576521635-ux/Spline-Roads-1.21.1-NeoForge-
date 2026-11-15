package com.sora.splineroads.world;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

final class RoadEditRasters implements AutoCloseable {
   private static final ThreadLocal<RoadEditRasters> CURRENT = new ThreadLocal<>();
   private final RoadEditRasters parent;
   private final Set<RoadIndex.Built> roads = Collections.newSetFromMap(new IdentityHashMap<>());
   private boolean closed;

   private RoadEditRasters() {
      this.parent = CURRENT.get();
      CURRENT.set(this);
   }

   static RoadEditRasters open() {
      return new RoadEditRasters();
   }

   static void used(RoadIndex.Built road) {
      RoadEditRasters scope = CURRENT.get();
      if (scope != null) {
         scope.roads.add(road);
      }
   }

   int size() {
      return this.roads.size();
   }

   @Override
   public void close() {
      if (!this.closed) {
         if (CURRENT.get() != this) {
            throw new IllegalStateException("Road raster scopes closed out of order");
         } else {
            this.closed = true;
            if (this.parent != null) {
               this.parent.roads.addAll(this.roads);
               CURRENT.set(this.parent);
            } else {
               CURRENT.remove();

               for (RoadIndex.Built road : this.roads) {
                  road.releaseEditRaster();
               }
            }

            this.roads.clear();
         }
      }
   }
}
