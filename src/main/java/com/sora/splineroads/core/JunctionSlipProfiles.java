package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class JunctionSlipProfiles {
   final JunctionSpec spec;
   final List<RoadGeometry.Mesh> approaches;
   final boolean exact;
   final boolean smoothEdges;

   private static double smooth(double t) {
      return RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, t)));
   }

   public JunctionSlipProfiles(JunctionSpec s, List<RoadGeometry.Mesh> m) {
      this(s, m, true, true);
   }

   public JunctionSlipProfiles(JunctionSpec s, List<RoadGeometry.Mesh> m, boolean exact) {
      this(s, m, exact, false);
   }

   public JunctionSlipProfiles(JunctionSpec s, List<RoadGeometry.Mesh> m, boolean exact, boolean smoothEdges) {
      this.spec = s;
      this.approaches = m;
      this.exact = exact;
      this.smoothEdges = smoothEdges;
   }

   private List<RoadGeometry.Sample> samples(int arm) {
      RoadGeometry.Mesh m = this.approaches.get(arm);
      if (!this.exact) {
         return m.samples();
      } else {
         TreeSet<Double> stations = new TreeSet<>();

         for (RoadGeometry.Sample p : m.samples()) {
            stations.add(p.distance());
         }

         for (int side : new int[]{-1, 1}) {
            double start = this.cut(arm, side);
            if (Double.isFinite(start)) {
               for (double delta : new double[]{-32.0, 0.0, 8.0, 24.0}) {
                  double d = start + delta;
                  if (d > 0.0 && d < m.length()) {
                     stations.removeIf(v -> Math.abs(v - d) < 1.0E-7);
                     stations.add(d);
                  }
               }
            }
         }

         return stations.stream().map(dx -> RoadSlicing.at(m, dx)).toList();
      }
   }

   public double addition(int i, double d) {
      JunctionSpec.Arm a = this.spec.arms().get(i);
      if (!a.rightSlip()) {
         return 0.0;
      } else {
         int to = JunctionSlips.target(this.spec, i);
         double split = this.approaches.get(i).length() - JunctionSlips.run(this.spec, i, to);
         double t = Math.max(0.0, (d - split + 32.0) / 32.0);
         return a.laneWidth() * (this.smoothEdges ? smooth(t) : t * t) * (1.0 - smooth((d - split) / 24.0));
      }
   }

   public double reservation(int i, double d) {
      if (!JunctionSlips.receives(this.spec, i)) {
         return 0.0;
      } else {
         int from = 0;

         while (!this.spec.arms().get(from).rightSlip() || JunctionSlips.target(this.spec, from) != i) {
            from++;
         }

         double join = this.approaches.get(i).length() - JunctionSlips.run(this.spec, from, i);
         return this.spec.arms().get(i).laneWidth() * smooth((d - join) / 8.0);
      }
   }

   public RoadGeometry.V map(int i, RoadGeometry.V p) {
      RoadQueries.Projection q = RoadQueries.horizontal(this.approaches.get(i), p);
      JunctionSpec.Arm a = this.spec.arms().get(i);
      double x = q.lateral();
      double d = q.sample().distance();
      double delta = 0.0;
      double high = a.motorEdge(true, false);
      double low = a.motorEdge(false, false);
      double width = a.laneWidth();
      if (x >= high - 0.3) {
         delta += this.addition(i, d);
      }

      if (x < low + width) {
         delta += this.reservation(i, d) * Math.min(1.0, (low + width - x) / width);
      }

      return p.add(q.sample().left().mul(delta));
   }

   private int arm(RoadGeometry.V p) {
      int best = -1;
      double distance = Double.POSITIVE_INFINITY;

      for (int i = 0; i < this.approaches.size(); i++) {
         RoadQueries.Projection q = RoadQueries.horizontal(this.approaches.get(i), p);
         double d = q.horizontalDistance();
         if (d < distance) {
            distance = d;
            best = i;
         }
      }

      return best;
   }

   private double cut(int arm, int side) {
      for (int i = 0; i < this.spec.arms().size(); i++) {
         if (this.spec.arms().get(i).rightSlip()) {
            int to = JunctionSlips.target(this.spec, i);
            if (side > 0 && i == arm || side < 0 && to == arm) {
               return this.approaches.get(arm).length() - JunctionSlips.run(this.spec, i, to);
            }
         }
      }

      return Double.POSITIVE_INFINITY;
   }

   private double edge(int i, int side, double d) {
      JunctionSpec.Arm a = this.spec.arms().get(i);
      return side > 0 ? a.motorEdge(true, false) : a.motorEdge(false, false) + this.reservation(i, d);
   }

   public List<RoadGeometry.Mesh> refugeStrips() {
      ArrayList<RoadGeometry.Mesh> result = new ArrayList<>();

      for (int i = 0; i < this.approaches.size(); i++) {
         for (int side : new int[]{-1, 1}) {
            double start = this.cut(i, side);
            if (Double.isFinite(start)) {
               ArrayList<RoadGeometry.Sample> points = new ArrayList<>();

               for (RoadGeometry.Sample p : this.samples(i)) {
                  if (p.distance() >= start - 1.0E-8) {
                     RoadGeometry.V inner = p.at(this.edge(i, side, p.distance()), 0.0);
                     RoadGeometry.V outer = this.map(i, p.at((double)side * p.halfWidth(), 0.0));
                     RoadGeometry.V delta = outer.sub(inner);
                     points.add(new RoadGeometry.Sample(inner.add(outer).mul(0.5), delta.horizontalUnit(), 0.0, delta.horizontalLength() / 2.0));
                  }
               }

               if (points.size() > 1) {
                  result.add(footprint(points, JunctionPlanner.settings(4.0, this.spec.thickness())));
               }
            }
         }
      }

      return List.copyOf(result);
   }

   RoadGeometry.Mesh approachMesh(int i) {
      ArrayList<RoadGeometry.Sample> points = new ArrayList<>();

      for (RoadGeometry.Sample p : this.samples(i)) {
         RoadGeometry.V low = (this.exact ? !(p.distance() > this.cut(i, -1) + 1.0E-8) : !(p.distance() >= this.cut(i, -1)))
            ? this.map(i, p.at(-p.halfWidth(), 0.0))
            : p.at(this.edge(i, -1, p.distance()), 0.0);
         RoadGeometry.V high = (this.exact ? !(p.distance() > this.cut(i, 1) + 1.0E-8) : !(p.distance() >= this.cut(i, 1)))
            ? this.map(i, p.at(p.halfWidth(), 0.0))
            : p.at(this.edge(i, 1, p.distance()), 0.0);
         points.add(new RoadGeometry.Sample(low.add(high).mul(0.5), p.left(), p.distance(), high.sub(low).horizontalLength() / 2.0));
      }

      RoadGeometry.Mesh m = this.approaches.get(i);
      ArrayList<RoadGeometry.V> bounds = new ArrayList<>();

      for (RoadGeometry.Sample p : points) {
         bounds.add(p.at(-p.halfWidth(), 0.0));
         bounds.add(p.at(p.halfWidth(), 0.0));
      }

      return new RoadGeometry.Mesh(
         List.copyOf(points),
         m.settings(),
         new RoadGeometry.V(
            bounds.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(), m.min().y(), bounds.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow()
         ),
         new RoadGeometry.V(
            bounds.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(), m.max().y(), bounds.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()
         ),
         m.length(),
         false
      );
   }

   private static RoadGeometry.Mesh footprint(List<RoadGeometry.Sample> raw, RoadGeometry.Settings settings) {
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
      ArrayList<RoadGeometry.V> bounds = new ArrayList<>();
      double d = 0.0;
      RoadGeometry.V before = null;

      for (RoadGeometry.Sample p : raw) {
         if (before != null) {
            d += before.distance(p.center());
         }

         samples.add(new RoadGeometry.Sample(p.center(), p.left(), d, p.halfWidth()));
         bounds.add(p.at(-p.halfWidth(), 0.0));
         bounds.add(p.at(p.halfWidth(), 0.0));
         before = p.center();
      }

      return new RoadGeometry.Mesh(
         List.copyOf(samples),
         settings,
         new RoadGeometry.V(
            bounds.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(),
            bounds.stream().mapToDouble(RoadGeometry.V::y).min().orElseThrow() - settings.thickness(),
            bounds.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow()
         ),
         new RoadGeometry.V(
            bounds.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(),
            bounds.stream().mapToDouble(RoadGeometry.V::y).max().orElseThrow(),
            bounds.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()
         ),
         d,
         false
      );
   }

   public RoadGeometry.V map(RoadGeometry.V p) {
      return this.map(this.arm(p), p);
   }

   public RoadStructures.Part part(int i, RoadStructures.Part p) {
      RoadGeometry.V foot = this.exact ? RoadStreetscape.lampFoot(p) : null;
      if (foot != null) {
         RoadGeometry.V delta = (i < 0 ? this.map(foot) : this.map(i, foot)).sub(foot);
         return new RoadStructures.Part(
            p.a().add(delta), p.b().add(delta), p.width(), p.height(), p.pier(), p.material(), p.frameA(), p.frameB(), p.model(), p.heights()
         );
      } else {
         RoadGeometry.V a = i < 0 ? this.map(p.a()) : this.map(i, p.a());
         RoadGeometry.V b = i < 0 ? this.map(p.b()) : this.map(i, p.b());
         if (p.pier()) {
            return new RoadStructures.Part(a, b, p.width(), p.height(), true, p.material(), p.frameA(), p.frameB(), p.model(), p.heights());
         } else {
            List<RoadGeometry.V> corners = p.base();
            RoadGeometry.V fa = (i < 0 ? this.map(corners.get(0)) : this.map(i, corners.get(0))).sub(a);
            RoadGeometry.V fb = (i < 0 ? this.map(corners.get(3)) : this.map(i, corners.get(3))).sub(b);
            return new RoadStructures.Part(a, b, p.width(), p.height(), p.pier(), p.material(), fa, fb, p.model(), p.heights());
         }
      }
   }

   private RoadGeometry.Mesh mesh(int i, RoadGeometry.Mesh m) {
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
      ArrayList<RoadGeometry.V> bounds = new ArrayList<>();

      for (RoadGeometry.Sample p : m.samples()) {
         RoadGeometry.V a = i < 0 ? this.map(p.at(-p.halfWidth(), 0.0)) : this.map(i, p.at(-p.halfWidth(), 0.0));
         RoadGeometry.V b = i < 0 ? this.map(p.at(p.halfWidth(), 0.0)) : this.map(i, p.at(p.halfWidth(), 0.0));
         RoadGeometry.V delta = b.sub(a);
         samples.add(
            new RoadGeometry.Sample(
               a.add(b).mul(0.5), delta.horizontalLength() < 1.0E-8 ? p.left() : delta.horizontalUnit(), p.distance(), delta.horizontalLength() / 2.0
            )
         );
         bounds.add(a);
         bounds.add(b);
      }

      return new RoadGeometry.Mesh(
         List.copyOf(samples),
         m.settings(),
         new RoadGeometry.V(
            bounds.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(), m.min().y(), bounds.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow()
         ),
         new RoadGeometry.V(
            bounds.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(), m.max().y(), bounds.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()
         ),
         m.length(),
         m.closed()
      );
   }

   public JunctionPlanner.Plan apply(JunctionPlanner.Plan p) {
      ArrayList<JunctionPlanner.Piece> pieces = new ArrayList<>();

      for (JunctionPlanner.Piece q : p.pieces()) {
         int i = q.arm();
         ArrayList<RoadSurface.Face> paint = new ArrayList<>();

         for (RoadSurface.Face f : q.paint()) {
            List<RoadGeometry.V> points = f.points().stream().map(v -> (RoadGeometry.V)(i < 0 ? v : this.map(i, v))).toList();
            if (Math.abs(JunctionPaint.area(points)) > 1.0E-8) {
               paint.add(new RoadSurface.Face(points, f.color()));
            }
         }

         pieces.add(
            new JunctionPlanner.Piece(
               i < 0 ? q.mesh() : this.approachMesh(i),
               List.copyOf(paint),
               q.structures().stream().map(v -> (RoadStructures.Part)(i < 0 ? v : this.part(i, v))).toList(),
               i
            )
         );
      }

      HashMap<RoadStructures.Part, JunctionSignals.Head> heads = new HashMap<>();
      p.heads().forEach((part, head) -> heads.put(this.part(head.arm(), part), head));
      return new JunctionPlanner.Plan(List.copyOf(pieces), p.movements(), p.boundary(), p.sidewalks(), p.signals(), Map.copyOf(heads));
   }
}
