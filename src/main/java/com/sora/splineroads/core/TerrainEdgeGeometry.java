package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

public final class TerrainEdgeGeometry {
   public static List<List<RoadGeometry.V>> retain(RoadGeometry.V origin, List<RoadGeometry.Mesh> roads) {
      List<List<RoadGeometry.V>> pieces = List.of(
         List.of(new RoadGeometry.V(0.0, 0.0, 0.0), new RoadGeometry.V(1.0, 0.0, 0.0), new RoadGeometry.V(1.0, 0.0, 1.0), new RoadGeometry.V(0.0, 0.0, 1.0))
      );
      RoadBoundsIndex.Bounds cell = new RoadBoundsIndex.Bounds(origin.x(), origin.z(), origin.x() + 1.0, origin.z() + 1.0);

      for (RoadGeometry.Mesh mesh : roads) {
         for (int index : RoadPathIndex.segments(mesh).query(cell, mesh.settings().width() / 2.0 + 2.0)) {
            RoadGeometry.Sample a = mesh.samples().get(index);
            RoadGeometry.Sample b = mesh.samples().get(index + 1);

            for (LaneDeck.Strip strip : LaneDeck.strips(mesh, a, b)) {
               List<RoadGeometry.V> cut = List.of(strip.al(), strip.ar(), strip.br(), strip.bl())
                  .stream()
                  .map(p -> new RoadGeometry.V(p.x() - origin.x(), 0.0, p.z() - origin.z()))
                  .toList();
               ArrayList<List<RoadGeometry.V>> next = new ArrayList<>();

               for (List<RoadGeometry.V> piece : pieces) {
                  next.addAll(RoadSurface.subtract(piece, cut));
               }

               pieces = next;
               if (next.isEmpty()) {
                  return List.of();
               }
            }
         }
      }

      ArrayList<List<RoadGeometry.V>> result = new ArrayList<>(pieces);
      boolean changed = true;

      label48:
      while (changed) {
         changed = false;

         for (int i = 0; i < result.size(); i++) {
            for (int j = i + 1; j < result.size(); j++) {
               ArrayList<RoadGeometry.V> points = new ArrayList<>(result.get(i));
               points.addAll(result.get(j));
               List<RoadGeometry.V> hull = hull(points);
               if (Math.abs(RoadSurface.area(hull) - RoadSurface.area(result.get(i)) - RoadSurface.area(result.get(j))) < 1.0E-9) {
                  result.set(i, hull);
                  result.remove(j);
                  changed = true;
                  continue label48;
               }
            }
         }
      }

      return result.stream().map(List::copyOf).toList();
   }

   private static List<RoadGeometry.V> hull(List<RoadGeometry.V> points) {
      List<RoadGeometry.V> sorted = points.stream()
         .distinct()
         .sorted(Comparator.comparingDouble(RoadGeometry.V::x).thenComparingDouble(RoadGeometry.V::z))
         .toList();
      ArrayList<RoadGeometry.V> hull = new ArrayList<>();

      for (RoadGeometry.V p : sorted) {
         while (hull.size() > 1 && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 1.0E-10) {
            hull.remove(hull.size() - 1);
         }

         hull.add(p);
      }

      int lower = hull.size();

      for (int i = sorted.size() - 2; i >= 0; i--) {
         RoadGeometry.V p = sorted.get(i);

         while (hull.size() > lower && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 1.0E-10) {
            hull.remove(hull.size() - 1);
         }

         hull.add(p);
      }

      if (hull.size() > 1) {
         hull.remove(hull.size() - 1);
      }

      return hull;
   }

   private static double cross(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c) {
      return (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
   }

   public static List<RoadRaster.Box> collision(List<List<RoadGeometry.V>> pieces) {
      ArrayList<RoadRaster.Box> boxes = new ArrayList<>();

      for (List<RoadGeometry.V> piece : pieces) {
         TreeSet<Double> levels = new TreeSet<>();
         piece.forEach(p -> levels.add(p.z()));

         for (int i = 0; i <= 8; i++) {
            levels.add((double)i / 8.0);
         }

         ArrayList<Double> zs = new ArrayList<>(levels);

         for (int i = 1; i < zs.size(); i++) {
            double lo = zs.get(i - 1);
            double hi = zs.get(i);
            double[] a = range(piece, lo + 1.0E-9);
            double[] b = range(piece, hi - 1.0E-9);
            double left = Math.max(a[0], b[0]);
            double right = Math.min(a[1], b[1]);
            if (right - left > 1.0E-8 && hi - lo > 1.0E-8) {
               boxes.add(new RoadRaster.Box(Math.max(0.0, left), 0.0, Math.max(0.0, lo), Math.min(1.0, right), 1.0, Math.min(1.0, hi)));
            }
         }
      }

      return List.copyOf(boxes);
   }

   private static double[] range(List<RoadGeometry.V> p, double z) {
      double lo = Double.POSITIVE_INFINITY;
      double hi = Double.NEGATIVE_INFINITY;

      for (int i = 0; i < p.size(); i++) {
         RoadGeometry.V a = p.get(i);
         RoadGeometry.V b = p.get((i + 1) % p.size());
         if (!(z < Math.min(a.z(), b.z())) && !(z > Math.max(a.z(), b.z())) && !(Math.abs(b.z() - a.z()) < 1.0E-12)) {
            double x = a.x() + (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z());
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
         }
      }

      return new double[]{lo, hi};
   }

   public static List<TerrainEdgeGeometry.Face> faces(List<List<RoadGeometry.V>> pieces) {
      ArrayList<TerrainEdgeGeometry.Face> result = new ArrayList<>();

      for (List<RoadGeometry.V> piece : pieces) {
         ArrayList<RoadGeometry.V> top = new ArrayList<>();

         for (RoadGeometry.V p : piece) {
            top.add(new RoadGeometry.V(p.x(), 1.0, p.z()));
         }

         Collections.reverse(top);
         result.add(new TerrainEdgeGeometry.Face(1, true, List.copyOf(top)));
         result.add(new TerrainEdgeGeometry.Face(1, false, piece));

         for (int i = 0; i < piece.size(); i++) {
            RoadGeometry.V a = piece.get(i);
            RoadGeometry.V b = piece.get((i + 1) % piece.size());
            if (!(a.distance(b) < 1.0E-8)) {
               boolean internal = false;

               for (List<RoadGeometry.V> other : pieces) {
                  if (other != piece) {
                     for (int k = 0; k < other.size(); k++) {
                        if (a.distance(other.get((k + 1) % other.size())) < 1.0E-8 && b.distance(other.get(k)) < 1.0E-8) {
                           internal = true;
                        }
                     }
                  }
               }

               if (!internal) {
                  RoadGeometry.V delta = b.sub(a);
                  int axis = Math.abs(delta.z()) > Math.abs(delta.x()) ? 0 : 2;
                  boolean positive = axis == 0 ? delta.z() > 0.0 : delta.x() < 0.0;
                  result.add(
                     new TerrainEdgeGeometry.Face(axis, positive, List.of(a, new RoadGeometry.V(a.x(), 1.0, a.z()), new RoadGeometry.V(b.x(), 1.0, b.z()), b))
                  );
               }
            }
         }
      }

      return List.copyOf(result);
   }

   private TerrainEdgeGeometry() {
   }

   public static record Face(int axis, boolean positive, List<RoadGeometry.V> points) {
   }
}
