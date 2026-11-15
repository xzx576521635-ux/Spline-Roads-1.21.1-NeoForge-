package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

public final class TerrainEdgeFaces {
   public static List<TerrainEdgeFaces.Face> surface(List<RoadRaster.Box> boxes) {
      LinkedHashMap<TerrainEdgeFaces.Plane, List<TerrainEdgeFaces.Rect>> planes = new LinkedHashMap<>();

      for (RoadRaster.Box box : boxes) {
         for (int axis = 0; axis < 3; axis++) {
            for (boolean positive : new boolean[]{false, true}) {
               double at = bound(box, axis, positive);
               ArrayList<TerrainEdgeFaces.Rect> visible = new ArrayList<>();
               visible.add(rect(box, axis));

               for (RoadRaster.Box other : boxes) {
                  if (other != box && Math.abs(bound(other, axis, !positive) - at) < 1.0E-9) {
                     ArrayList<TerrainEdgeFaces.Rect> next = new ArrayList<>();

                     for (TerrainEdgeFaces.Rect r : visible) {
                        subtract(next, r, rect(other, axis));
                     }

                     visible = next;
                     if (next.isEmpty()) {
                        break;
                     }
                  }
               }

               planes.computeIfAbsent(new TerrainEdgeFaces.Plane(axis, positive, at), k -> new ArrayList<>()).addAll(visible);
            }
         }
      }

      ArrayList<TerrainEdgeFaces.Face> out = new ArrayList<>();

      for (Entry<TerrainEdgeFaces.Plane, List<TerrainEdgeFaces.Rect>> entry : planes.entrySet()) {
         List<TerrainEdgeFaces.Rect> rects = entry.getValue();
         boolean again = true;

         label98:
         while (again) {
            again = false;

            for (int i = 0; i < rects.size(); i++) {
               for (int j = i + 1; j < rects.size(); j++) {
                  TerrainEdgeFaces.Rect a = rects.get(i);
                  TerrainEdgeFaces.Rect b = rects.get(j);
                  TerrainEdgeFaces.Rect merged = null;
                  if (!eq(a.a, b.a) || !eq(a.c, b.c) || !eq(a.d, b.b) && !eq(b.d, a.b)) {
                     if (eq(a.b, b.b) && eq(a.d, b.d) && (eq(a.c, b.a) || eq(b.c, a.a))) {
                        merged = new TerrainEdgeFaces.Rect(Math.min(a.a, b.a), a.b, Math.max(a.c, b.c), a.d);
                     }
                  } else {
                     merged = new TerrainEdgeFaces.Rect(a.a, Math.min(a.b, b.b), a.c, Math.max(a.d, b.d));
                  }

                  if (merged != null) {
                     rects.set(i, merged);
                     rects.remove(j);
                     again = true;
                     continue label98;
                  }
               }
            }
         }

         TerrainEdgeFaces.Plane p = entry.getKey();

         for (TerrainEdgeFaces.Rect r : rects) {
            out.add(new TerrainEdgeFaces.Face(p.axis, p.positive, switch (p.axis) {
               case 0 -> new RoadRaster.Box(p.at, r.a, r.b, p.at, r.c, r.d);
               case 1 -> new RoadRaster.Box(r.a, p.at, r.b, r.c, p.at, r.d);
               default -> new RoadRaster.Box(r.a, r.b, p.at, r.c, r.d, p.at);
            }));
         }
      }

      return List.copyOf(out);
   }

   private static boolean eq(double a, double b) {
      return Math.abs(a - b) < 1.0E-9;
   }

   private static double bound(RoadRaster.Box b, int a, boolean p) {
      return switch (a) {
         case 0 -> p ? b.x1() : b.x0();
         case 1 -> p ? b.y1() : b.y0();
         default -> p ? b.z1() : b.z0();
      };
   }

   private static TerrainEdgeFaces.Rect rect(RoadRaster.Box b, int a) {
      return switch (a) {
         case 0 -> new TerrainEdgeFaces.Rect(b.y0(), b.z0(), b.y1(), b.z1());
         case 1 -> new TerrainEdgeFaces.Rect(b.x0(), b.z0(), b.x1(), b.z1());
         default -> new TerrainEdgeFaces.Rect(b.x0(), b.y0(), b.x1(), b.y1());
      };
   }

   private static void subtract(List<TerrainEdgeFaces.Rect> out, TerrainEdgeFaces.Rect r, TerrainEdgeFaces.Rect cut) {
      double a = Math.max(r.a, cut.a);
      double b = Math.max(r.b, cut.b);
      double c = Math.min(r.c, cut.c);
      double d = Math.min(r.d, cut.d);
      if (!(a >= c - 1.0E-9) && !(b >= d - 1.0E-9)) {
         if (r.a < a - 1.0E-9) {
            out.add(new TerrainEdgeFaces.Rect(r.a, r.b, a, r.d));
         }

         if (c < r.c - 1.0E-9) {
            out.add(new TerrainEdgeFaces.Rect(c, r.b, r.c, r.d));
         }

         if (r.b < b - 1.0E-9) {
            out.add(new TerrainEdgeFaces.Rect(a, r.b, c, b));
         }

         if (d < r.d - 1.0E-9) {
            out.add(new TerrainEdgeFaces.Rect(a, d, c, r.d));
         }
      } else {
         out.add(r);
      }
   }

   private TerrainEdgeFaces() {
   }

   public static record Face(int axis, boolean positive, RoadRaster.Box box) {
   }

   private static record Plane(int axis, boolean positive, double at) {
   }

   private static record Rect(double a, double b, double c, double d) {
   }
}
