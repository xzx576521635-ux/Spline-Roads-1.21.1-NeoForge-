package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RoadSolidOverlap {
   private static final double EPS = 1.0E-7;
   private static final RoadGeometry.V UP = new RoadGeometry.V(0.0, 1.0, 0.0);

   private static RoadSolidOverlap.Body body(RoadStructures.Part part) {
      List<RoadGeometry.V> base = part.base();
      List<RoadGeometry.V> top = part.top();
      ArrayList<RoadGeometry.V[]> prisms = new ArrayList<>();

      for (int i = 1; i < base.size() - 1; i++) {
         RoadGeometry.V a = base.get(0);
         RoadGeometry.V b = base.get(i);
         RoadGeometry.V c = base.get(i + 1);
         if (!(Math.abs(cross(b.sub(a), c.sub(a)).y()) < 1.0E-12)) {
            prisms.add(new RoadGeometry.V[]{a, b, c, top.get(0), top.get(i), top.get(i + 1)});
         }
      }

      return new RoadSolidOverlap.Body(
         List.copyOf(prisms),
         base.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(),
         base.stream().mapToDouble(RoadGeometry.V::y).min().orElseThrow(),
         base.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow(),
         base.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(),
         base.stream().mapToDouble(RoadGeometry.V::y).max().orElseThrow() + part.height(),
         base.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()
      );
   }

   public static boolean intersects(RoadStructures.Part a, RoadStructures.Part b) {
      return intersects(body(a), body(b));
   }

   private static boolean intersects(RoadSolidOverlap.Body a, RoadSolidOverlap.Body b) {
      if (!(a.x1 <= b.x0 + 1.0E-7)
         && !(b.x1 <= a.x0 + 1.0E-7)
         && !(a.y1 <= b.y0 + 1.0E-7)
         && !(b.y1 <= a.y0 + 1.0E-7)
         && !(a.z1 <= b.z0 + 1.0E-7)
         && !(b.z1 <= a.z0 + 1.0E-7)) {
         for (RoadGeometry.V[] p : a.prisms) {
            for (RoadGeometry.V[] q : b.prisms) {
               if (prisms(p, q)) {
                  return true;
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private static boolean prisms(RoadGeometry.V[] a, RoadGeometry.V[] b) {
      RoadGeometry.V[] ea = new RoadGeometry.V[]{a[1].sub(a[0]), a[2].sub(a[1]), a[0].sub(a[2]), UP};
      RoadGeometry.V[] eb = new RoadGeometry.V[]{b[1].sub(b[0]), b[2].sub(b[1]), b[0].sub(b[2]), UP};
      if (!separates(cross(ea[0], ea[1]), a, b)
         && !separates(cross(eb[0], eb[1]), a, b)
         && !separates(cross(a[4].sub(a[3]), a[5].sub(a[3])), a, b)
         && !separates(cross(b[4].sub(b[3]), b[5].sub(b[3])), a, b)) {
         for (int i = 0; i < 3; i++) {
            if (separates(cross(ea[i], UP), a, b) || separates(cross(eb[i], UP), a, b)) {
               return false;
            }
         }

         if (parallel(a) && parallel(b)) {
            for (RoadGeometry.V x : ea) {
               for (RoadGeometry.V y : eb) {
                  if (separates(cross(x, y), a, b)) {
                     return false;
                  }
               }
            }

            return true;
         } else {
            ArrayList<RoadGeometry.V> ax = new ArrayList<>(Arrays.asList(ea));
            ArrayList<RoadGeometry.V> bx = new ArrayList<>(Arrays.asList(eb));

            for (int ix = 0; ix < 3; ix++) {
               ax.add(a[3 + (ix + 1) % 3].sub(a[3 + ix]));
               bx.add(b[3 + (ix + 1) % 3].sub(b[3 + ix]));
            }

            for (RoadGeometry.V x : ax) {
               for (RoadGeometry.V yx : bx) {
                  if (separates(cross(x, yx), a, b)) {
                     return false;
                  }
               }
            }

            return true;
         }
      } else {
         return false;
      }
   }

   private static boolean parallel(RoadGeometry.V[] p) {
      double h = p[3].y() - p[0].y();
      return Math.abs(p[4].y() - p[1].y() - h) < 1.0E-10 && Math.abs(p[5].y() - p[2].y() - h) < 1.0E-10;
   }

   private static boolean separates(RoadGeometry.V axis, RoadGeometry.V[] a, RoadGeometry.V[] b) {
      double norm = Math.sqrt(axis.dot(axis));
      if (norm < 1.0E-12) {
         return false;
      } else {
         double amin = Double.POSITIVE_INFINITY;
         double amax = -amin;
         double bmin = amin;
         double bmax = -amin;
         RoadGeometry.V origin = a[0];

         for (RoadGeometry.V v : a) {
            double t = v.sub(origin).dot(axis);
            amin = Math.min(amin, t);
            amax = Math.max(amax, t);
         }

         for (RoadGeometry.V v : b) {
            double t = v.sub(origin).dot(axis);
            bmin = Math.min(bmin, t);
            bmax = Math.max(bmax, t);
         }

         return amax <= bmin + 1.0E-7 * norm || bmax <= amin + 1.0E-7 * norm;
      }
   }

   private static RoadGeometry.V cross(RoadGeometry.V a, RoadGeometry.V b) {
      return new RoadGeometry.V(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
   }

   private static long key(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   private RoadSolidOverlap() {
   }

   private static record Body(List<RoadGeometry.V[]> prisms, double x0, double y0, double z0, double x1, double y1, double z1) {
   }

   public static final class Index {
      private final Map<Long, List<RoadSolidOverlap.Body>> bins = new HashMap<>();

      public Index(Collection<RoadStructures.Part> parts) {
         for (RoadStructures.Part p : parts) {
            RoadSolidOverlap.Body b = RoadSolidOverlap.body(p);

            for (int x = (int)Math.floor(b.x0 / 16.0); x <= (int)Math.floor(b.x1 / 16.0); x++) {
               for (int z = (int)Math.floor(b.z0 / 16.0); z <= (int)Math.floor(b.z1 / 16.0); z++) {
                  this.bins.computeIfAbsent(RoadSolidOverlap.key(x, z), k -> new ArrayList<>()).add(b);
               }
            }
         }
      }

      public boolean intersects(RoadStructures.Part p) {
         RoadSolidOverlap.Body b = RoadSolidOverlap.body(p);
         Set<RoadSolidOverlap.Body> visited = Collections.newSetFromMap(new IdentityHashMap<>());

         for (int x = (int)Math.floor(b.x0 / 16.0); x <= (int)Math.floor(b.x1 / 16.0); x++) {
            for (int z = (int)Math.floor(b.z0 / 16.0); z <= (int)Math.floor(b.z1 / 16.0); z++) {
               for (RoadSolidOverlap.Body other : this.bins.getOrDefault(RoadSolidOverlap.key(x, z), List.of())) {
                  if (visited.add(other) && RoadSolidOverlap.intersects(b, other)) {
                     return true;
                  }
               }
            }
         }

         return false;
      }
   }
}
