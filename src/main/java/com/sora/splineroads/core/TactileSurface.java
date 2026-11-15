package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class TactileSurface {
   private final Map<Long, List<RoadStructures.Part>> cells = new HashMap<>();
   private final Map<Long, List<RoadStructures.Part>> neighborhoods = new HashMap<>();
   private final Map<RoadStructures.Part, List<RoadGeometry.V>> bases = new IdentityHashMap<>();
   private final Map<RoadStructures.Part, Double> tops = new IdentityHashMap<>();
   private final Map<RoadGeometry.V, Double> grades = new HashMap<>();

   private static long key(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   public TactileSurface(List<RoadStructures.Part> parts) {
      for (RoadStructures.Part p : parts) {
         if (p.material().name().startsWith("WALK_")) {
            List<RoadGeometry.V> v = p.base();
            this.bases.put(p, v);
            this.tops.put(p, v.stream().mapToDouble(RoadGeometry.V::y).max().orElseThrow() + p.height());
            int x0 = (int)Math.floor(v.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow() / 4.0);
            int x1 = (int)Math.floor(v.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow() / 4.0);
            int z0 = (int)Math.floor(v.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow() / 4.0);
            int z1 = (int)Math.floor(v.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow() / 4.0);

            for (int x = x0; x <= x1; x++) {
               for (int z = z0; z <= z1; z++) {
                  this.cells.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(p);
               }
            }
         }
      }
   }

   public static double triangle(RoadGeometry.V p, RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c) {
      double den = (b.z() - c.z()) * (a.x() - c.x()) + (c.x() - b.x()) * (a.z() - c.z());
      if (Math.abs(den) < 1.0E-12) {
         return Double.NaN;
      } else {
         double u = ((b.z() - c.z()) * (p.x() - c.x()) + (c.x() - b.x()) * (p.z() - c.z())) / den;
         double v = ((c.z() - a.z()) * (p.x() - c.x()) + (a.x() - c.x()) * (p.z() - c.z())) / den;
         return u >= -1.0E-5 && v >= -1.0E-5 && u + v <= 1.00001 ? u * a.y() + v * b.y() + (1.0 - u - v) * c.y() : Double.NaN;
      }
   }

   public double height(RoadGeometry.V point) {
      double top = Double.NEGATIVE_INFINITY;

      for (RoadStructures.Part p : this.cells.getOrDefault(key((int)Math.floor(point.x() / 4.0), (int)Math.floor(point.z() / 4.0)), List.of())) {
         List<RoadGeometry.V> q = this.bases.get(p);
         double y = triangle(point, q.get(0), q.get(1), q.get(2));
         if (!Double.isFinite(y)) {
            y = triangle(point, q.get(0), q.get(2), q.get(3));
         }

         y += p.height();
         if (Double.isFinite(y) && Math.abs(y - point.y()) < 2.0) {
            top = Math.max(top, y);
         }
      }

      return top;
   }

   private double gradedHeight(RoadGeometry.V point) {
      return this.grades.computeIfAbsent(point, this::computeGrade);
   }

   private double computeGrade(RoadGeometry.V point) {
      double top = this.height(point);
      int x = (int)Math.floor(point.x() / 4.0);
      int z = (int)Math.floor(point.z() / 4.0);

      for (RoadStructures.Part p : this.neighborhoods.computeIfAbsent(key(x, z), ignored -> {
         HashSet<RoadStructures.Part> found = new HashSet<>();

         for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
               found.addAll(this.cells.getOrDefault(key(x + dx, z + dz), List.of()));
            }
         }

         return List.copyOf(found);
      })) {
         List<RoadGeometry.V> q = this.bases.get(p);
         if (!(this.tops.get(p) <= top)) {
            for (int i = 0; i < 4; i++) {
               RoadGeometry.V a = q.get(i);
               RoadGeometry.V b = q.get((i + 1) % 4);
               RoadGeometry.V delta = b.sub(a);
               double den = delta.x() * delta.x() + delta.z() * delta.z();
               double t = den < 1.0E-10 ? 0.0 : Math.max(0.0, Math.min(1.0, ((point.x() - a.x()) * delta.x() + (point.z() - a.z()) * delta.z()) / den));
               RoadGeometry.V at = a.add(delta.mul(t));
               double y = at.y() + p.height();
               if (!(Math.abs(y - point.y()) > 1.5)) {
                  top = Math.max(top, y - 0.2 * at.sub(point).horizontalLength());
               }
            }
         }
      }

      return top;
   }

   public List<RoadStructures.Part> gradePaving(List<RoadStructures.Part> parts) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();

      for (RoadStructures.Part p : parts) {
         RoadPlanningBudget.check();
         if (!p.material().name().startsWith("WALK_")) {
            out.add(p);
         } else {
            List<RoadGeometry.V> base = p.base();
            int n = Math.max(1, (int)Math.ceil(p.a().distance(p.b()) / 0.4));

            for (int j = 0; j < n; j++) {
               double a = (double)j / (double)n;
               double b = (double)(j + 1) / (double)n;
               ArrayList<RoadGeometry.V> q = new ArrayList<>(
                  List.of(
                     base.get(0).add(base.get(3).sub(base.get(0)).mul(a)),
                     base.get(1).add(base.get(2).sub(base.get(1)).mul(a)),
                     base.get(1).add(base.get(2).sub(base.get(1)).mul(b)),
                     base.get(0).add(base.get(3).sub(base.get(0)).mul(b))
                  )
               );

               for (int k = 0; k < 4; k++) {
                  RoadGeometry.V v = q.get(k);
                  double y = this.gradedHeight(v);
                  if (Double.isFinite(y)) {
                     q.set(k, new RoadGeometry.V(v.x(), Math.max(v.y(), y - p.height()), v.z()));
                  }
               }

               RoadGeometry.V start = q.get(0).add(q.get(1)).mul(0.5);
               RoadGeometry.V end = q.get(2).add(q.get(3)).mul(0.5);
               out.add(new RoadStructures.Part(start, end, p.width(), p.height(), false, p.material(), q.get(0).sub(start), q.get(3).sub(end), p.model()));
            }
         }
      }

      return List.copyOf(out);
   }

   public List<RoadStructures.Part> conform(List<RoadStructures.Part> parts) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();

      for (RoadStructures.Part p : parts) {
         if (p.material() != RoadStructures.Material.TACTILE) {
            out.add(p);
         } else {
            ArrayList<RoadGeometry.V> q = new ArrayList<>(p.base());
            double lift = p.height() > 0.018 ? 0.015 : 0.0;

            for (int i = 0; i < 4; i++) {
               RoadGeometry.V v = q.get(i);
               double y = this.height(v);
               if (Double.isFinite(y)) {
                  q.set(i, new RoadGeometry.V(v.x(), y + lift + 0.001, v.z()));
               }
            }

            RoadGeometry.V a = q.get(0).add(q.get(1)).mul(0.5);
            RoadGeometry.V b = q.get(2).add(q.get(3)).mul(0.5);
            out.add(new RoadStructures.Part(a, b, p.width(), p.height(), false, p.material(), q.get(0).sub(a), q.get(3).sub(b), p.model()));
         }
      }

      return List.copyOf(out);
   }
}
