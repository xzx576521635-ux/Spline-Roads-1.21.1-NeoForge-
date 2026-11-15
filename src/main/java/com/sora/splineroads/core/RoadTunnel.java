package com.sora.splineroads.core;

public final class RoadTunnel {
   public static boolean dipping(RoadGeometry.Settings s) {
      return s.structure() == RoadGeometry.Structure.TUNNEL && s.options().infrastructure().tunnelDepth() > 0.0;
   }

   public static double minimum(RoadGeometry.Node a, RoadGeometry.Node b, RoadGeometry.Settings s) {
      return Math.min(a.position().y(), b.position().y()) - s.options().infrastructure().tunnelDepth();
   }

   public static double height(double t, RoadGeometry.Node a, RoadGeometry.Node b, RoadGeometry.Settings s, double scaleA, double scaleB) {
      double low = minimum(a, b, s);
      if (a.grade() > 1.0E-7 || b.grade() < -1.0E-7) {
         throw new IllegalArgumentException("自动下潜需入口平直或下坡、出口平直或上坡；请调整端点坡度");
      } else if (t <= 0.45) {
         return blend(t / 0.45, a.position().y(), low, a.grade() * scaleA * 0.45, 0.0, s);
      } else {
         return t >= 0.55 ? blend((t - 0.55) / 0.45, low, b.position().y(), 0.0, b.grade() * scaleB * 0.45, s) : low;
      }
   }

   static double blend(double t, double a, double b, double da, double db, RoadGeometry.Settings s) {
      if (!s.options().infrastructure().efficientDip()) {
         double t2 = t * t;
         double t3 = t2 * t;
         double t4 = t3 * t;
         double t5 = t4 * t;
         return a + (b - a) * (10.0 * t3 - 15.0 * t4 + 6.0 * t5) + da * (t - 6.0 * t3 + 8.0 * t4 - 3.0 * t5) + db * (-4.0 * t3 + 7.0 * t4 - 3.0 * t5);
      } else {
         double ease = 0.2;
         double delta = b - a;
         double grade = (delta - ease * (da + db) / 2.0) / (1.0 - ease);
         if (t < ease) {
            return a + da * t + (grade - da) * ease * integral(t / ease);
         } else if (t > 1.0 - ease) {
            double u = 1.0 - t;
            return b - db * u - (grade - db) * ease * integral(u / ease);
         } else {
            return a + ease * (da + grade) / 2.0 + grade * (t - ease);
         }
      }
   }

   private static double integral(double u) {
      return u * u * u * (1.0 - u / 2.0);
   }

   private RoadTunnel() {
   }
}
