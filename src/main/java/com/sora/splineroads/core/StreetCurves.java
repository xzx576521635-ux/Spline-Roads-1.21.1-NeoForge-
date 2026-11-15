package com.sora.splineroads.core;

import java.util.SplittableRandom;

public final class StreetCurves {
   public static double proposed(StreetPlan.Input input, StreetPlan.Vertex a, StreetPlan.Vertex b, int rank) {
      StreetPlan.Style style = input.options().style();
      if (style != StreetPlan.Style.DOWNTOWN && style != StreetPlan.Style.NEW_TOWN) {
         double length = a.point().distance(b.point());
         if (length < 72.0) {
            return 0.0;
         } else if (input.entries().stream().anyMatch(e -> e.point().distance(a.point()) < 1.0 || e.point().distance(b.point()) < 1.0)) {
            return 0.0;
         } else {
            long key = input.options().seed()
               ^ Double.doubleToLongBits(a.point().x() + b.point().x())
               ^ Long.rotateLeft(Double.doubleToLongBits(a.point().z() + b.point().z()), 23)
               ^ (long)rank;
            SplittableRandom random = new SplittableRandom(key);
            if (!(random.nextDouble() < 0.25) && (style != StreetPlan.Style.TOWN || rank != 0)) {
               double scale = style == StreetPlan.Style.RURAL ? 0.075 : (style == StreetPlan.Style.SUBURB ? 0.06 : 0.045);
               return (double)(random.nextBoolean() ? 1 : -1) * Math.min(16.0, length * scale) * (0.7 + 0.3 * random.nextDouble());
            } else {
               return 0.0;
            }
         }
      } else {
         return 0.0;
      }
   }

   public static StreetPlan.Point point(StreetPlan.Point a, StreetPlan.Point b, double bend, double t) {
      StreetPlan.Point p = a.mix(b, t);
      double length = a.distance(b);
      double offset = bend * 16.0 * t * t * (1.0 - t) * (1.0 - t);
      return new StreetPlan.Point(p.x() - (b.z() - a.z()) / length * offset, p.z() + (b.x() - a.x()) / length * offset);
   }

   public static StreetPlan.Point point(StreetPlan.Plan plan, StreetPlan.Edge edge, double t) {
      return point(plan.vertices().get(edge.a()).point(), plan.vertices().get(edge.b()).point(), edge.bend(), t);
   }

   public static RoadGeometry.V direction(StreetPlan.Point a, StreetPlan.Point b, double bend, double t) {
      double dx = b.x() - a.x();
      double dz = b.z() - a.z();
      double length = a.distance(b);
      double slope = bend * 32.0 * t * (1.0 - t) * (1.0 - 2.0 * t);
      return new RoadGeometry.V(dx - dz / length * slope, 0.0, dz + dx / length * slope).horizontalUnit();
   }

   private StreetCurves() {
   }
}
