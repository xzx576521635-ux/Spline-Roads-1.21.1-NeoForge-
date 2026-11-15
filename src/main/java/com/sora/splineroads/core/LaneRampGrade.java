package com.sora.splineroads.core;

import java.util.Locale;

public final class LaneRampGrade {
   public static final double LEGACY = 0.15;

   public static double limit(boolean involvesHighway, boolean override) {
      return involvesHighway ? (override ? 0.2 : 0.15) : (override ? 0.25 : 0.2);
   }

   public static double checked(double limit) {
      if (Double.isFinite(limit) && !(limit < 0.01) && !(limit > 0.25)) {
         return limit;
      } else {
         throw new IllegalArgumentException("匝道坡比上限无效");
      }
   }

   public static boolean exceeds(double rise, double run, double limit) {
      return !RoadGeometry.finite(rise, run, limit) || run < 0.0 || Math.abs(rise) > (limit + 1.0E-6) * run + 1.0E-9;
   }

   public static String label(double limit) {
      return String.format(Locale.ROOT, "%.0f%%", limit * 100.0);
   }

   public static void validate(RoadGeometry.Mesh mesh, double limit) {
      checked(limit);

      for (int i = 1; i < mesh.samples().size(); i++) {
         RoadGeometry.V d = mesh.samples().get(i).center().sub(mesh.samples().get(i - 1).center());
         if (exceeds(d.y(), d.horizontalLength(), limit)) {
            throw new IllegalArgumentException("匝道实际路面坡度超过 " + label(limit) + "，请扩大间距或降低高差");
         }
      }
   }

   public static LaneRampGrade.Report report(RoadGeometry.Mesh mesh, double from, double to, double limit) {
      checked(limit);
      double horizontal = 0.0;
      double available = 0.0;
      double travel = 0.0;
      double maximum = 0.0;

      for (int i = 1; i < mesh.samples().size(); i++) {
         RoadGeometry.Sample a = mesh.samples().get(i - 1);
         RoadGeometry.Sample b = mesh.samples().get(i);
         RoadGeometry.V d = b.center().sub(a.center());
         double run = d.horizontalLength();
         double span = b.distance() - a.distance();
         horizontal += run;
         maximum = Math.max(maximum, run > 1.0E-10 ? Math.abs(d.y()) / run : (Math.abs(d.y()) > 1.0E-10 ? Double.POSITIVE_INFINITY : 0.0));
         double overlap = Math.max(0.0, Math.min(to, b.distance()) - Math.max(from, a.distance()));
         double fraction = span > 1.0E-10 ? Math.min(1.0, overlap / span) : 0.0;
         available += run * fraction;
         travel += Math.abs(d.y()) * fraction;
      }

      return new LaneRampGrade.Report(horizontal, available, travel / limit, maximum, travel);
   }

   private LaneRampGrade() {
   }

   public static record Report(double horizontal, double available, double minimum, double maximum, double travel) {
   }
}
