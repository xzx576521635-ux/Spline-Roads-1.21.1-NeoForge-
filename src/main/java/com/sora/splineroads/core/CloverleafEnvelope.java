package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;

public final class CloverleafEnvelope {
   public static boolean clear(RoadGeometry.Mesh right, RoadGeometry.Mesh loop) {
      ArrayList<RoadGeometry.V> sorted = new ArrayList<>(loop.samples().stream().map(RoadGeometry.Sample::center).toList());
      sorted.sort(Comparator.comparingDouble(RoadGeometry.V::x).thenComparingDouble(RoadGeometry.V::z));
      ArrayList<RoadGeometry.V> hull = new ArrayList<>();

      for (RoadGeometry.V p : sorted) {
         while (hull.size() > 1 && turn(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 1.0E-9) {
            hull.remove(hull.size() - 1);
         }

         hull.add(p);
      }

      int lower = hull.size();

      for (int i = sorted.size() - 2; i >= 0; i--) {
         RoadGeometry.V p = sorted.get(i);

         while (hull.size() > lower && turn(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 1.0E-9) {
            hull.remove(hull.size() - 1);
         }

         hull.add(p);
      }

      if (hull.size() < 4) {
         return true;
      } else {
         hull.remove(hull.size() - 1);
         double loopHalf = loop.samples().stream().mapToDouble(RoadGeometry.Sample::halfWidth).max().orElse(0.0);

         for (int j = 1; j < right.samples().size(); j++) {
            RoadGeometry.Sample first = right.samples().get(j - 1);
            RoadGeometry.Sample last = right.samples().get(j);
            double padding = loopHalf + Math.max(first.halfWidth(), last.halfWidth());
            double lo = 0.0;
            double hi = 1.0;

            for (int i = 0; i < hull.size() && lo <= hi; i++) {
               RoadGeometry.V a = hull.get(i);
               RoadGeometry.V b = hull.get((i + 1) % hull.size());
               double edge = b.sub(a).horizontalLength();
               double start = turn(a, b, first.center()) + padding * edge - 1.0E-6;
               double end = turn(a, b, last.center()) + padding * edge - 1.0E-6;
               double delta = end - start;
               if (Math.abs(delta) < 1.0E-12) {
                  if (start <= 0.0) {
                     lo = 1.0;
                     hi = 0.0;
                  }
               } else if (delta > 0.0) {
                  lo = Math.max(lo, -start / delta);
               } else {
                  hi = Math.min(hi, -start / delta);
               }
            }

            if (lo < hi - 1.0E-10) {
               return false;
            }
         }

         return true;
      }
   }

   private static double turn(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V p) {
      return (b.x() - a.x()) * (p.z() - a.z()) - (b.z() - a.z()) * (p.x() - a.x());
   }

   private CloverleafEnvelope() {
   }
}
