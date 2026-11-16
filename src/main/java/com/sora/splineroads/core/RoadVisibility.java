package com.sora.splineroads.core;

public final class RoadVisibility {
   public static boolean within(double minX, double minZ, double maxX, double maxZ, double cameraX, double cameraZ, int renderChunks) {
      if (RoadGeometry.finite(minX, minZ, maxX, maxZ, cameraX, cameraZ) && !(minX > maxX) && !(minZ > maxZ)) {
         double reach = (double)Math.max(0, renderChunks) * 16.0 + 32.0;
         double dx = Math.max(0.0, Math.max(minX - cameraX, cameraX - maxX));
         double dz = Math.max(0.0, Math.max(minZ - cameraZ, cameraZ - maxZ));
         return dx <= reach && dz <= reach;
      } else {
         return false;
      }
   }

   /**
    * Strict variant: the whole bounding box must lie inside the render square. Bounding-box
    * based culling ({@link #within}) keeps a 4x4-chunk section alive while any corner is still
    * inside, so the far half can be drawn well past the terrain horizon - where only Distant
    * Horizons LODs exist and roads end up drawn "through" mountains.
    */
   public static boolean inside(double minX, double minZ, double maxX, double maxZ, double cameraX, double cameraZ, int renderChunks) {
      if (RoadGeometry.finite(minX, minZ, maxX, maxZ, cameraX, cameraZ) && !(minX > maxX) && !(minZ > maxZ)) {
         double reach = (double)Math.max(0, renderChunks) * 16.0;
         double dx = Math.max(Math.abs(minX - cameraX), Math.abs(maxX - cameraX));
         double dz = Math.max(Math.abs(minZ - cameraZ), Math.abs(maxZ - cameraZ));
         return dx <= reach && dz <= reach;
      } else {
         return false;
      }
   }

   private RoadVisibility() {
   }
}
