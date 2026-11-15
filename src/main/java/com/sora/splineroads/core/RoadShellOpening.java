package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.DoubleUnaryOperator;
import java.util.function.ToDoubleFunction;

public final class RoadShellOpening {
   private final Map<Long, List<RoadShellOpening.Patch>> grid = new HashMap<>();

   public static RoadShellOpening solids(List<RoadStructures.Part> parts) {
      RoadShellOpening result = new RoadShellOpening();

      for (RoadStructures.Part part : parts) {
         List<RoadGeometry.V> base = part.base();
         List<RoadGeometry.V> top = part.top();
         if (planar(base) && planar(top) && Math.abs(JunctionPaint.area(base.subList(0, 3))) > 1.0E-14) {
            result.add(new RoadShellOpening.Patch(base, top.get(0).y() - base.get(0).y(), top.get(1).y() - base.get(1).y(), top.get(2).y() - base.get(2).y()));
         } else {
            for (int i = 1; i + 1 < base.size(); i++) {
               result.add(
                  new RoadShellOpening.Patch(
                     List.of(base.get(0), base.get(i), base.get(i + 1)),
                     top.get(0).y() - base.get(0).y(),
                     top.get(i).y() - base.get(i).y(),
                     top.get(i + 1).y() - base.get(i + 1).y()
                  )
               );
            }
         }
      }

      return result;
   }

   public RoadShellOpening(RoadGeometry.Mesh mesh, double from, double to, DoubleUnaryOperator height) {
      this(mesh, from, to, height, 1.0);
   }

   public RoadShellOpening(RoadGeometry.Mesh mesh, double from, double to, DoubleUnaryOperator height, double step) {
      this(mesh, from, to, height, step, 0.08);
   }

   public RoadShellOpening(RoadGeometry.Mesh mesh, double from, double to, DoubleUnaryOperator height, double step, double edgePadding) {
      if (!(step <= 0.0) && Double.isFinite(step)) {
         from = Math.max(mesh.first().distance(), from);
         to = Math.min(mesh.last().distance(), to);
         if (!(to <= from)) {
            TreeSet<Double> stations = new TreeSet<>();
            stations.add(from);
            stations.add(to);

            for (RoadGeometry.Sample sample : mesh.samples()) {
               if (sample.distance() > from && sample.distance() < to) {
                  stations.add(sample.distance());
               }
            }

            for (double d = from + step; d < to - 1.0E-7; d += step) {
               stations.add(d);
            }

            ArrayList<RoadGeometry.Sample> nodes = new ArrayList<>();
            ArrayList<Double> roofs = new ArrayList<>();

            for (double d : stations) {
               nodes.add(RoadStructures.sample(mesh, d));
               roofs.add(height.applyAsDouble(d));
            }

            double depth = mesh.settings().thickness() + 0.02;
            int i = 0;

            while (i < nodes.size() - 1) {
               int end = i + 1;

               for (int candidate = end + 1; candidate < nodes.size() && nodes.get(candidate).distance() - nodes.get(i).distance() <= 64.0; end = candidate++) {
                  RoadGeometry.Sample a = nodes.get(i);
                  RoadGeometry.Sample b = nodes.get(candidate);
                  boolean linear = true;

                  for (int k = i + 1; k < candidate && linear; k++) {
                     RoadGeometry.Sample m = nodes.get(k);
                     double t = (m.distance() - a.distance()) / (b.distance() - a.distance());
                     if (Math.abs(roofs.get(k) - (roofs.get(i) * (1.0 - t) + roofs.get(candidate) * t)) > 1.0E-8) {
                        linear = false;
                     }

                     for (int side : new int[]{-1, 1}) {
                        if (a.at((double)side * (a.halfWidth() + 0.08), depth)
                              .mul(1.0 - t)
                              .add(b.at((double)side * (b.halfWidth() + 0.08), depth).mul(t))
                              .distance(m.at((double)side * (m.halfWidth() + 0.08), depth))
                           > 1.0E-8) {
                           linear = false;
                        }
                     }
                  }

                  if (!linear) {
                     break;
                  }
               }

               RoadGeometry.Sample a = nodes.get(i);
               RoadGeometry.Sample b = nodes.get(end);
               double ha = roofs.get(i);
               double hb = roofs.get(end);
               RoadGeometry.V al = a.at(a.halfWidth() + edgePadding, depth);
               RoadGeometry.V ar = a.at(-a.halfWidth() - edgePadding, depth);
               RoadGeometry.V bl = b.at(b.halfWidth() + edgePadding, depth);
               RoadGeometry.V br = b.at(-b.halfWidth() - edgePadding, depth);
               List<RoadGeometry.V> quad = List.of(al, ar, br, bl);
               if (planar(quad)) {
                  this.add(new RoadShellOpening.Patch(quad, ha + depth, ha + depth, hb + depth));
               } else {
                  this.add(new RoadShellOpening.Patch(List.of(al, ar, br), ha + depth, ha + depth, hb + depth));
                  this.add(new RoadShellOpening.Patch(List.of(al, br, bl), ha + depth, hb + depth, hb + depth));
               }

               i = end;
            }
         }
      } else {
         throw new IllegalArgumentException("Invalid shell sample step");
      }
   }

   private void add(RoadShellOpening.Patch p) {
      if (!(Math.abs(JunctionPaint.area(p.floor())) < 1.0E-12)) {
         int x0 = tile(p.floor().stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow());
         int x1 = tile(p.floor().stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow());
         int z0 = tile(p.floor().stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow());
         int z1 = tile(p.floor().stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow());

         for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
               this.grid.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(p);
            }
         }
      }
   }

   public List<RoadStructures.Part> cut(RoadStructures.Part part) {
      List<RoadGeometry.V> base = part.base();
      LinkedHashSet<RoadShellOpening.Patch> near = new LinkedHashSet<>();
      int x0 = tile(base.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow());
      int x1 = tile(base.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow());
      int z0 = tile(base.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow());
      int z1 = tile(base.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow());

      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            near.addAll(this.grid.getOrDefault(key(x, z), List.of()));
         }
      }

      ArrayList<RoadShellOpening.Slice> initial = new ArrayList<>();
      List<RoadGeometry.V> top = part.top();
      if (planar(base) && planar(top)) {
         initial.add(new RoadShellOpening.Slice(base, RoadShellOpening.Plane.of(base), RoadShellOpening.Plane.of(top)));
      } else {
         for (int i = 1; i + 1 < base.size(); i++) {
            if (valid(List.of(base.get(0), base.get(i), base.get(i + 1)))) {
               initial.add(
                  new RoadShellOpening.Slice(
                     List.of(base.get(0), base.get(i), base.get(i + 1)),
                     RoadShellOpening.Plane.of(List.of(base.get(0), base.get(i), base.get(i + 1))),
                     RoadShellOpening.Plane.of(List.of(top.get(0), top.get(i), top.get(i + 1)))
                  )
               );
            }
         }
      }

      List<RoadShellOpening.Slice> pieces = initial;

      for (RoadShellOpening.Patch patch : near) {
         ArrayList<RoadShellOpening.Slice> next = new ArrayList<>();

         for (RoadShellOpening.Slice piece : pieces) {
            clip(piece, patch, next);
         }

         pieces = next;
         if (next.isEmpty()) {
            break;
         }
      }

      if (pieces.equals(initial)) {
         return List.of(part);
      } else {
         ArrayList<RoadStructures.Part> result = new ArrayList<>();

         for (RoadShellOpening.Slice piece : pieces) {
            emit(result, piece, part);
         }

         return List.copyOf(result);
      }
   }

   public List<RoadStructures.Part> retain(RoadStructures.Part part) {
      List<RoadStructures.Part> outside = this.cut(part);
      if (outside.isEmpty()) {
         return List.of(part);
      } else {
         return outside.size() == 1 && outside.get(0).equals(part) ? List.of() : solids(outside).cut(part);
      }
   }

   private static boolean planar(List<RoadGeometry.V> p) {
      if (!valid(p)) {
         return false;
      } else {
         RoadShellOpening.Plane plane = RoadShellOpening.Plane.of(p);
         return p.stream().allMatch(v -> Math.abs(v.y() - plane.at(v)) < 1.0E-8);
      }
   }

   private static boolean valid(List<RoadGeometry.V> p) {
      return p.size() >= 3 && Math.abs(JunctionPaint.area(p)) > 1.0E-12;
   }

   private static void slice(List<RoadShellOpening.Slice> out, List<RoadGeometry.V> polygon, RoadShellOpening.Plane bottom, RoadShellOpening.Plane top) {
      polygon = half(polygon, p -> top.at(p) - bottom.at(p));
      if (valid(polygon) && !(polygon.stream().mapToDouble(p -> top.at(p) - bottom.at(p)).max().orElse(0.0) <= 1.0E-7)) {
         out.add(new RoadShellOpening.Slice(polygon, bottom, top));
      }
   }

   private static List<RoadGeometry.V> half(List<RoadGeometry.V> p, ToDoubleFunction<RoadGeometry.V> value) {
      if (p.isEmpty()) {
         return p;
      } else {
         ArrayList<RoadGeometry.V> out = new ArrayList<>();
         RoadGeometry.V a = p.get(p.size() - 1);
         double va = value.applyAsDouble(a);

         for (RoadGeometry.V b : p) {
            double vb = value.applyAsDouble(b);
            if (va >= 0.0 != vb >= 0.0) {
               out.add(a.add(b.sub(a).mul(va / (va - vb))));
            }

            if (vb >= 0.0) {
               out.add(b);
            }

            a = b;
            va = vb;
         }

         return out;
      }
   }

   private static void clip(RoadShellOpening.Slice part, RoadShellOpening.Patch patch, List<RoadShellOpening.Slice> out) {
      List<RoadGeometry.V> hit = RoadSurface.intersectVolume(part.base(), patch.floor());
      if (!valid(hit)) {
         out.add(part);
      } else {
         RoadShellOpening.Plane floor = RoadShellOpening.Plane.of(patch.floor());
         RoadShellOpening.Plane roof = RoadShellOpening.Plane.of(patch.floor().stream().map(v -> v.add(new RoadGeometry.V(0.0, patch.height(v), 0.0))).toList());
         if (!hit.stream().allMatch(v -> part.bottom().at(v) >= roof.at(v) - 1.0E-7) && !hit.stream().allMatch(v -> part.top().at(v) <= floor.at(v) + 1.0E-7)) {
            for (List<RoadGeometry.V> polygon : RoadSurface.subtract(part.base(), patch.floor())) {
               slice(out, polygon, part.bottom(), part.top());
            }

            slice(out, half(hit, p -> part.bottom().at(p) - roof.at(p)), part.bottom(), part.top());
            slice(out, half(hit, p -> roof.at(p) - part.bottom().at(p)), roof, part.top());
            slice(out, half(hit, p -> floor.at(p) - part.top().at(p)), part.bottom(), part.top());
            slice(out, half(hit, p -> part.top().at(p) - floor.at(p)), part.bottom(), floor);
         } else {
            out.add(part);
         }
      }
   }

   private static void emit(List<RoadStructures.Part> out, RoadShellOpening.Slice slice, RoadStructures.Part source) {
      ArrayList<RoadGeometry.V> polygon = new ArrayList<>();

      for (RoadGeometry.V v : slice.base()) {
         if (polygon.isEmpty() || v.sub(polygon.get(polygon.size() - 1)).horizontalLength() > 1.0E-8) {
            polygon.add(v);
         }
      }

      if (polygon.size() > 1 && polygon.get(0).sub(polygon.get(polygon.size() - 1)).horizontalLength() < 1.0E-8) {
         polygon.remove(polygon.size() - 1);
      }

      int i = 0;

      while (i < polygon.size() && polygon.size() > 3) {
         int n = polygon.size();
         RoadGeometry.V a = polygon.get((i + n - 1) % n);
         RoadGeometry.V b = polygon.get(i);
         RoadGeometry.V c = polygon.get((i + 1) % n);
         if (2.0 * Math.abs(JunctionPaint.area(List.of(a, b, c))) <= a.sub(c).horizontalLength() * 1.0E-8) {
            polygon.remove(i);
            i = 0;
         } else {
            i++;
         }
      }

      if (valid(polygon)) {
         if (polygon.size() == 4) {
            emitPart(out, polygon, slice, source);
         } else {
            for (int ix = 1; ix + 1 < polygon.size(); ix++) {
               emitPart(out, List.of(polygon.get(0), polygon.get(ix), polygon.get(ix + 1), polygon.get(ix + 1)), slice, source);
            }
         }
      }
   }

   private static void emitPart(List<RoadStructures.Part> out, List<RoadGeometry.V> polygon, RoadShellOpening.Slice slice, RoadStructures.Part source) {
      double longest = 0.0;

      for (int i = 0; i < polygon.size(); i++) {
         longest = Math.max(longest, polygon.get(i).sub(polygon.get((i + 1) % polygon.size())).horizontalLength());
      }

      if (!(2.0 * Math.abs(JunctionPaint.area(polygon)) <= longest * 1.0E-7)) {
         List<RoadGeometry.V> p = polygon.stream().map(v -> new RoadGeometry.V(v.x(), slice.bottom().at(v), v.z())).toList();
         double[] h = new double[4];
         double height = 0.0;
         double min = Double.POSITIVE_INFINITY;

         for (int i = 0; i < 4; i++) {
            h[i] = Math.max(0.0, slice.top().at(p.get(i)) - p.get(i).y());
            height = Math.max(height, h[i]);
            min = Math.min(min, h[i]);
         }

         if (!(height <= 1.0E-7)) {
            RoadGeometry.V a = p.get(0).add(p.get(1)).mul(0.5);
            RoadGeometry.V b = p.get(2).add(p.get(3)).mul(0.5);
            out.add(
               new RoadStructures.Part(
                  a,
                  b,
                  Math.max(0.001, Math.min(8.0, p.get(0).sub(p.get(1)).horizontalLength())),
                  height,
                  false,
                  source.material(),
                  p.get(0).sub(a),
                  p.get(3).sub(b),
                  source.model(),
                  height - min < 1.0E-8 ? null : new RoadStructures.Heights(h[0], h[1], h[2], h[3])
               )
            );
         }
      }
   }

   private static int tile(double v) {
      return (int)Math.floor(v / 8.0);
   }

   private static long key(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   private RoadShellOpening() {
   }

   private static record Patch(List<RoadGeometry.V> floor, double first, double second, double third) {
      double height(RoadGeometry.V p) {
         if (this.first == this.second && this.first == this.third) {
            return this.first;
         } else {
            RoadGeometry.V a = this.floor.get(0);
            RoadGeometry.V b = this.floor.get(1).sub(a);
            RoadGeometry.V c = this.floor.get(2).sub(a);
            RoadGeometry.V q = p.sub(a);
            double det = b.x() * c.z() - b.z() * c.x();
            double u = (q.x() * c.z() - q.z() * c.x()) / det;
            double v = (b.x() * q.z() - b.z() * q.x()) / det;
            return this.first + (this.second - this.first) * u + (this.third - this.first) * v;
         }
      }
   }

   private static record Plane(RoadGeometry.V origin, double x, double z) {
      double at(RoadGeometry.V p) {
         return this.origin.y() + this.x * (p.x() - this.origin.x()) + this.z * (p.z() - this.origin.z());
      }

      static RoadShellOpening.Plane of(List<RoadGeometry.V> points) {
         RoadGeometry.V a = points.get(0);

         for (int i = 1; i + 1 < points.size(); i++) {
            RoadGeometry.V b = points.get(i).sub(a);
            RoadGeometry.V c = points.get(i + 1).sub(a);
            double det = b.x() * c.z() - b.z() * c.x();
            if (!(Math.abs(det) < 1.0E-14)) {
               return new RoadShellOpening.Plane(a, (b.y() * c.z() - c.y() * b.z()) / det, (b.x() * c.y() - c.x() * b.y()) / det);
            }
         }

         throw new IllegalArgumentException("Degenerate shell plane");
      }
   }

   private static record Slice(List<RoadGeometry.V> base, RoadShellOpening.Plane bottom, RoadShellOpening.Plane top) {
   }
}
