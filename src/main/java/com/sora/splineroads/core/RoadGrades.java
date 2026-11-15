package com.sora.splineroads.core;

import java.util.Locale;

public final class RoadGrades {
   public static final double MAX_RAMP_GRADE = 0.15;

   public static double maximum(RoadGeometry.Mesh mesh) {
      double max = 0.0;

      for (int i = 1; i < mesh.samples().size(); i++) {
         RoadGeometry.V d = mesh.samples().get(i).center().sub(mesh.samples().get(i - 1).center());
         max = Math.max(max, Math.abs(d.y()) / d.horizontalLength());
      }

      return max;
   }

   public static void validate(RoadGeometry.Mesh mesh) {
      if (mesh.settings().style().ramp()) {
         double grade = maximum(mesh);
         if (grade > 0.1500001) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "匝道最大纵坡 %.1f%%，超过 15%% 上限；请延长爬坡段或降低高差", grade * 100.0));
         }
      }
   }

   private RoadGrades() {
   }
}
