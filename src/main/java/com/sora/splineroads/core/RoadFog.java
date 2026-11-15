package com.sora.splineroads.core;

public final class RoadFog {
   public static double distance(RoadGeometry.V world, RoadGeometry.V camera, boolean cylindrical) {
      RoadGeometry.V p = world.sub(camera);
      return cylindrical ? Math.max(p.horizontalLength(), Math.abs(p.y())) : Math.sqrt(p.dot(p));
   }

   private RoadFog() {
   }
}
