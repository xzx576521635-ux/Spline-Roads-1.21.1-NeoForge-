package com.sora.splineroads.core;

public final class RoadWaterPolicy {
   public static boolean permeable(boolean slab, boolean retainedTerrain, boolean tunnelOwner, boolean shell, boolean dryInterior) {
      return !slab && !retainedTerrain && !dryInterior ? !tunnelOwner || shell : false;
   }

   public static boolean waterlogged(boolean permeable, boolean terrainWater, boolean previousWater, boolean originalWater) {
      return permeable && (terrainWater || previousWater || originalWater);
   }

   private RoadWaterPolicy() {
   }
}
