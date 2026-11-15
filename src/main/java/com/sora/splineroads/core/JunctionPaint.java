package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class JunctionPaint {
   public static final int WHITE = 15593186;
   public static final int YELLOW = 16433462;

   public static void line(List<RoadSurface.Face> out, RoadGeometry.V a, RoadGeometry.V b, double width, int color) {
      if (!(a.sub(b).horizontalLength() < 1.0E-7)) {
         RoadGeometry.V n = b.sub(a).horizontalUnit().left().mul(width / 2.0);
         out.add(new RoadSurface.Face(List.of(a.add(n), a.sub(n), b.sub(n), b.add(n)), color));
      }
   }

   public static void path(List<RoadSurface.Face> out, List<RoadGeometry.V> points, double width, boolean dashed, int color) {
      path(out, points, width, dashed, color, 0.0);
   }

   public static void path(List<RoadSurface.Face> out, List<RoadGeometry.V> points, double width, boolean dashed, int color, double phaseOffset) {
      double distance = (phaseOffset % 6.0 + 6.0) % 6.0;

      for (int i = 1; i < points.size(); i++) {
         RoadGeometry.V a = points.get(i - 1);
         RoadGeometry.V b = points.get(i);
         double length = b.sub(a).horizontalLength();
         double d = 0.0;

         while (d < length - 1.0E-7) {
            double phase = (distance + d) % 6.0;
            double span = Math.min(length - d, (double)(phase < 3.0 ? 3 : 6) - phase);
            if (!dashed || phase < 3.0) {
               line(out, a.add(b.sub(a).mul(d / length)), a.add(b.sub(a).mul((d + span) / length)), width, color);
            }

            d += Math.max(span, 1.0E-6);
         }

         distance += length;
      }
   }

   public static void ribbon(List<RoadSurface.Face> out, List<RoadGeometry.V> points, double width, int color) {
      ArrayList<RoadGeometry.V> normals = new ArrayList<>();

      for (int i = 0; i < points.size(); i++) {
         RoadGeometry.V before = points.get(Math.max(1, i)).sub(points.get(Math.max(0, i - 1))).horizontalUnit();
         RoadGeometry.V after = points.get(Math.min(points.size() - 1, i + 1)).sub(points.get(Math.min(points.size() - 2, i))).horizontalUnit();
         if (i == 0) {
            before = after;
         }

         if (i == points.size() - 1) {
            after = before;
         }

         RoadGeometry.V normal = before.left().add(after.left()).horizontalUnit();
         normals.add(normal.mul(width / 2.0 / Math.max(0.25, normal.dot(after.left()))));
      }

      for (int i = 1; i < points.size(); i++) {
         out.add(
            new RoadSurface.Face(
               List.of(
                  points.get(i - 1).add(normals.get(i - 1)),
                  points.get(i - 1).sub(normals.get(i - 1)),
                  points.get(i).sub(normals.get(i)),
                  points.get(i).add(normals.get(i))
               ),
               color
            )
         );
      }
   }

   private static void head(List<RoadSurface.Face> out, RoadGeometry.V tip, RoadGeometry.V direction, double length, double halfWidth) {
      RoadGeometry.V f = direction.horizontalUnit();
      RoadGeometry.V side = f.left();
      out.add(new RoadSurface.Face(List.of(tip, tip.sub(f.mul(length)).add(side.mul(halfWidth)), tip.sub(f.mul(length)).sub(side.mul(halfWidth))), 15593186));
   }

   public static void arrow(List<RoadSurface.Face> out, RoadGeometry.V at, RoadGeometry.V forward, int mask, double scale) {
      RoadGeometry.V f = forward.horizontalUnit().mul(scale);
      RoadGeometry.V right = forward.horizontalUnit().left().mul(scale);
      if (mask == 0) {
         line(out, at.sub(f), at.add(f), 0.16, 15593186);
         line(out, at.sub(right.mul(0.65)), at.add(right.mul(0.65)), 0.2, 15593186);
      } else {
         boolean singleTurn = mask == 2 || mask == 4;
         if (!singleTurn) {
            line(out, at.sub(f.mul(2.1)), at.add(f.mul(0.25)), 0.22 * scale, 15593186);
         }

         if ((mask & 1) != 0) {
            line(out, at, at.add(f.mul(1.85)), 0.22 * scale, 15593186);
            head(out, at.add(f.mul(2.65)), forward, 0.95 * scale, 0.4 * scale);
         }

         for (int direction : new int[]{-1, 1}) {
            int flag = direction < 0 ? 2 : 4;
            if ((mask & flag) != 0) {
               RoadGeometry.V start = at.sub(f.mul(0.25));
               RoadGeometry.V bend = at.add(f.mul(0.9));
               RoadGeometry.V end = bend.add(right.mul((double)direction * 0.75));
               ArrayList<RoadGeometry.V> curve = new ArrayList<>();
               if (singleTurn) {
                  curve.add(at.sub(f.mul(2.1)));
               }

               curve.add(start);

               for (int k = 1; k <= 8; k++) {
                  double t = (double)k / 8.0;
                  double u = 1.0 - t;
                  RoadGeometry.V p = start.mul(u * u).add(bend.mul(2.0 * u * t)).add(end.mul(t * t));
                  curve.add(p);
               }

               RoadGeometry.V tip = bend.add(right.mul((double)direction * 1.55));
               curve.add(tip.sub(right.mul((double)direction * 0.4)));
               ribbon(out, curve, 0.22 * scale, 15593186);
               head(out, tip, right.mul((double)direction), 0.65 * scale, 0.32 * scale);
            }
         }

         if ((mask & 8) != 0) {
            RoadGeometry.V a = at.add(f.mul(0.8));
            RoadGeometry.V b = a.sub(right.mul(0.85));
            RoadGeometry.V c = b.sub(f.mul(1.1));
            line(out, at, a, 0.18 * scale, 15593186);
            line(out, a, b, 0.18 * scale, 15593186);
            line(out, b, c, 0.18 * scale, 15593186);
            head(out, c, forward.mul(-1.0), 0.65 * scale, 0.32 * scale);
         }
      }
   }

   public static double area(List<RoadGeometry.V> p) {
      double sum = 0.0;
      if (p.isEmpty()) {
         return 0.0;
      } else {
         RoadGeometry.V origin = p.get(0);

         for (int i = 0; i < p.size(); i++) {
            RoadGeometry.V a = p.get(i).sub(origin);
            RoadGeometry.V b = p.get((i + 1) % p.size()).sub(origin);
            sum += a.x() * b.z() - a.z() * b.x();
         }

         return sum / 2.0;
      }
   }

   public static boolean inside(List<RoadGeometry.V> polygon, RoadGeometry.V p) {
      boolean yes = false;
      int i = 0;

      for (int j = polygon.size() - 1; i < polygon.size(); j = i++) {
         RoadGeometry.V a = polygon.get(i);
         RoadGeometry.V b = polygon.get(j);
         if (a.z() > p.z() != b.z() > p.z() && p.x() < (b.x() - a.x()) * (p.z() - a.z()) / (b.z() - a.z()) + a.x()) {
            yes = !yes;
         }
      }

      return yes;
   }

   public static List<List<RoadGeometry.V>> triangulate(List<RoadGeometry.V> input) {
      ArrayList<RoadGeometry.V> p = new ArrayList<>();

      for (RoadGeometry.V v : input) {
         if (p.isEmpty() || v.sub(p.get(p.size() - 1)).horizontalLength() > 1.0E-7) {
            p.add(v);
         }
      }

      if (p.size() > 1 && p.get(0).sub(p.get(p.size() - 1)).horizontalLength() < 1.0E-7) {
         p.remove(p.size() - 1);
      }

      boolean changed = true;

      while (changed && p.size() > 3) {
         changed = false;

         for (int i = 0; i < p.size(); i++) {
            RoadGeometry.V a = p.get((i + p.size() - 1) % p.size());
            RoadGeometry.V b = p.get(i);
            RoadGeometry.V c = p.get((i + 1) % p.size());
            if (Math.abs(cross(a, b, c)) < 1.0E-8 && b.sub(a).dot(b.sub(c)) <= 1.0E-8) {
               p.remove(i);
               changed = true;
               break;
            }
         }
      }

      for (int ix = 0; ix < p.size(); ix++) {
         for (int j = ix + 2; j < p.size(); j++) {
            if (ix != 0 || j != p.size() - 1) {
               RoadGeometry.V a = p.get(ix);
               RoadGeometry.V b = p.get((ix + 1) % p.size());
               RoadGeometry.V c = p.get(j);
               RoadGeometry.V d = p.get((j + 1) % p.size());
               double ab1 = cross(a, b, c);
               double ab2 = cross(a, b, d);
               double cd1 = cross(c, d, a);
               double cd2 = cross(c, d, b);
               if (ab1 * ab2 < -1.0E-12 && cd1 * cd2 < -1.0E-12) {
                  throw new IllegalArgumentException("路口外缘自交，请拉开接入口或减小转角半径");
               }
            }
         }
      }

      if (area(p) < 0.0) {
         Collections.reverse(p);
      }

      ArrayList<List<RoadGeometry.V>> triangles = new ArrayList<>();
      int safety = p.size() * p.size();

      label103:
      while (p.size() > 3 && safety-- > 0) {
         boolean found = false;

         for (int ix = 0; ix < p.size(); ix++) {
            RoadGeometry.V a = p.get((ix + p.size() - 1) % p.size());
            RoadGeometry.V b = p.get(ix);
            RoadGeometry.V c = p.get((ix + 1) % p.size());
            if (!(cross(a, b, c) <= 1.0E-8)) {
               boolean occupied = false;

               for (RoadGeometry.V vx : p) {
                  if (vx.sub(a).horizontalLength() > 1.0E-7
                     && vx.sub(b).horizontalLength() > 1.0E-7
                     && vx.sub(c).horizontalLength() > 1.0E-7
                     && cross(a, b, vx) >= -1.0E-8
                     && cross(b, c, vx) >= -1.0E-8
                     && cross(c, a, vx) >= -1.0E-8) {
                     occupied = true;
                     break;
                  }
               }

               if (!occupied) {
                  triangles.add(List.of(a, b, c));
                  p.remove(ix);
                  found = true;
                  if (!found) {
                     throw new IllegalArgumentException("路口外缘自交，请拉开接入口或减小转角半径");
                  }
                  continue label103;
               }
            }
         }
         break;
      }

      if (p.size() == 3 && Math.abs(area(p)) > 1.0E-8) {
         triangles.add(List.copyOf(p));
      }

      return List.copyOf(triangles);
   }

   public static List<List<RoadGeometry.V>> triangulateLegacy(List<RoadGeometry.V> input) {
      ArrayList<RoadGeometry.V> p = new ArrayList<>(input);
      if (legacyArea(p) < 0.0) {
         Collections.reverse(p);
      }

      ArrayList<List<RoadGeometry.V>> triangles = new ArrayList<>();
      int safety = p.size() * p.size();

      label67:
      while (p.size() > 3 && safety-- > 0) {
         boolean found = false;

         for (int i = 0; i < p.size(); i++) {
            RoadGeometry.V a = p.get((i + p.size() - 1) % p.size());
            RoadGeometry.V b = p.get(i);
            RoadGeometry.V c = p.get((i + 1) % p.size());
            if (!(cross(a, b, c) <= 1.0E-8)) {
               boolean occupied = false;

               for (RoadGeometry.V v : p) {
                  if (v != a && v != b && v != c && cross(a, b, v) >= -1.0E-8 && cross(b, c, v) >= -1.0E-8 && cross(c, a, v) >= -1.0E-8) {
                     occupied = true;
                     break;
                  }
               }

               if (!occupied) {
                  triangles.add(List.of(a, b, c));
                  p.remove(i);
                  found = true;
                  if (!found) {
                     throw new IllegalArgumentException("路口外缘自交，请拉开接入口或减小转角半径");
                  }
                  continue label67;
               }
            }
         }
         break;
      }

      if (p.size() == 3 && Math.abs(legacyArea(p)) > 1.0E-8) {
         triangles.add(List.copyOf(p));
      }

      return List.copyOf(triangles);
   }

   private static double legacyArea(List<RoadGeometry.V> p) {
      double sum = 0.0;

      for (int i = 0; i < p.size(); i++) {
         RoadGeometry.V a = p.get(i);
         RoadGeometry.V b = p.get((i + 1) % p.size());
         sum += a.x() * b.z() - a.z() * b.x();
      }

      return sum / 2.0;
   }

   private static double cross(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c) {
      return (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
   }

   private JunctionPaint() {
   }
}
