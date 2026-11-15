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

   private RoadVisibility() {
   }
}
