package com.sora.splineroads.core;

public final class RoadFurniture {
   public static double modulo(double value, double period) {
      double r = value - Math.floor(value / period) * period;
      return !(r < 1.0E-7) && !(period - r < 1.0E-7) ? r : 0.0;
   }

   private RoadFurniture() {
   }

   public static record Phase(double origin, int direction) {
      public static final RoadFurniture.Phase DEFAULT = new RoadFurniture.Phase(0.0, 1);

      public Phase(double origin, int direction) {
         if (!Double.isFinite(origin) || Math.abs(direction) != 1) {
            throw new IllegalArgumentException("无效的道路设施间距基准");
         } else if (Math.abs(origin) > 1.0E12) {
            throw new IllegalArgumentException("设施间距基准超出范围");
         } else {
            this.origin = origin;
            this.direction = direction;
         }
      }

      public RoadFurniture.Phase shift(double distance) {
         return new RoadFurniture.Phase(this.origin + (double)this.direction * distance, this.direction);
      }

      public double first(double offset, double spacing) {
         return RoadFurniture.modulo((offset - this.origin) * (double)this.direction, spacing);
      }
   }
}
