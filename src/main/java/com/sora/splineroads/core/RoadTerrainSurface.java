package com.sora.splineroads.core;

import java.util.function.DoubleUnaryOperator;

public final class RoadTerrainSurface {
   public static double connected(double roadY, DoubleUnaryOperator solidTop) {
      double probe = roadY + 1.0E-4;

      for (int i = 0; (double)i < 1536.0 && probe <= roadY + 96.0; i++) {
         RoadPlanningBudget.check();
         double top = solidTop.applyAsDouble(probe);
         if (!Double.isFinite(top) || top <= probe + 1.0E-7) {
            return top;
         }

         probe = top + 1.0E-4;
      }

      return roadY + 96.0;
   }

   private RoadTerrainSurface() {
   }
}
