package com.sora.splineroads.core;

public final class RoadVertical {
   public static boolean rising(RoadGeometry.Settings s) {
      return s.structure() == RoadGeometry.Structure.BRIDGE && s.options().infrastructure().bridgeRise() > 0.0;
   }

   public static boolean automatic(RoadGeometry.Settings s) {
      return rising(s) || RoadTunnel.dipping(s);
   }

   public static double maximum(RoadGeometry.Node a, RoadGeometry.Node b, RoadGeometry.Settings s) {
      return Math.max(a.position().y(), b.position().y()) + s.options().infrastructure().bridgeRise();
   }

   public static double limit(RoadGeometry.Settings s) {
      if (s.structure() != RoadGeometry.Structure.TUNNEL && s.structure() != RoadGeometry.Structure.BRIDGE) {
         return 0.5;
      } else {
         double configured = s.options().infrastructure().maxGrade();
         return configured > 0.0 ? configured : (RoadTunnel.dipping(s) ? 0.2 : 0.5);
      }
   }

   public static double height(double t, RoadGeometry.Node a, RoadGeometry.Node b, RoadGeometry.Settings s, double scaleA, double scaleB) {
      if (!rising(s)) {
         return RoadTunnel.height(t, a, b, s, scaleA, scaleB);
      } else if (!(a.grade() < -1.0E-7) && !(b.grade() > 1.0E-7)) {
         double high = maximum(a, b, s);
         if (t <= 0.3) {
            return RoadTunnel.blend(t / 0.3, a.position().y(), high, a.grade() * scaleA * 0.3, 0.0, s);
         } else {
            return t >= 0.7 ? RoadTunnel.blend((t - 0.7) / 0.3, high, b.position().y(), 0.0, b.grade() * scaleB * 0.3, s) : high;
         }
      } else {
         throw new IllegalArgumentException("自动抬升需入口平直或上坡、出口平直或下坡");
      }
   }

   private RoadVertical() {
   }
}
