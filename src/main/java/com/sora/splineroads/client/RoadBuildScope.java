package com.sora.splineroads.client;

final class RoadBuildScope {
   private boolean initialized;
   private boolean terrain;
   private double x;
   private double z;
   private int distance;

   boolean changed(double nextX, double nextZ, int nextDistance, boolean nextTerrain) {
      double dx = nextX - this.x;
      double dz = nextZ - this.z;
      if (this.initialized && dx * dx + dz * dz <= 256.0 && this.distance == nextDistance && this.terrain == nextTerrain) {
         return false;
      } else {
         this.initialized = true;
         this.x = nextX;
         this.z = nextZ;
         this.distance = nextDistance;
         this.terrain = nextTerrain;
         return true;
      }
   }

   void clear() {
      this.initialized = false;
   }
}
