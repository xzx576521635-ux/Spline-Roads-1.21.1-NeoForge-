package com.sora.splineroads.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.Map.Entry;

public final class StreetLayouts {
   private final StreetPlan.Input input;
   private final StreetPlan.Terrain terrain;
   private final StreetPlan.Options options;
   private final SplittableRandom random;
   private final List<StreetLayouts.Line> lines = new ArrayList<>();
   private final List<StreetPlan.Issue> issues = new ArrayList<>();
   private final List<StreetPlan.Point> centers = new ArrayList<>();
   private final double cx;
   private final double cz;
   private final double cos;
   private final double sin;
   private final double reach;
   private final double spacing;

   private StreetLayouts(StreetPlan.Input input, StreetPlan.Terrain terrain) {
      this.input = input;
      this.terrain = terrain;
      this.options = input.options();
      this.random = new SplittableRandom(new SplittableRandom(this.options.seed()).nextLong());
      double minX = input.selection().stream().mapToDouble(c -> (double)(c.x() * 16)).min().orElseThrow();
      double maxX = input.selection().stream().mapToDouble(c -> (double)(c.x() * 16 + 16)).max().orElseThrow();
      double minZ = input.selection().stream().mapToDouble(c -> (double)(c.z() * 16)).min().orElseThrow();
      double maxZ = input.selection().stream().mapToDouble(c -> (double)(c.z() * 16 + 16)).max().orElseThrow();
      this.cx = input.centers().isEmpty() ? (minX + maxX) / 2.0 : input.centers().get(0).x();
      this.cz = input.centers().isEmpty() ? (minZ + maxZ) / 2.0 : input.centers().get(0).z();
      this.reach = Math.hypot(Math.max(this.cx - minX, maxX - this.cx), Math.max(this.cz - minZ, maxZ - this.cz));
      this.cos = Math.cos(Math.toRadians(this.options.direction()));
      this.sin = Math.sin(Math.toRadians(this.options.direction()));
      this.spacing = this.options.spacing();
   }

   public static StreetPlan.Plan generate(StreetPlan.Input input, StreetPlan.Terrain terrain) {
      return new StreetLayouts(input, terrain).generate();
   }

   private StreetPlan.Point world(double x, double z) {
      return snap(new StreetPlan.Point(this.cx + x * this.cos - z * this.sin, this.cz + x * this.sin + z * this.cos));
   }

   private static StreetPlan.Point snap(StreetPlan.Point p) {
      return new StreetPlan.Point(Math.floor(p.x()) + 0.5, Math.floor(p.z()) + 0.5);
   }

   private StreetPlan.Point anchor(StreetPlan.Point p) {
      for (StreetPlan.Entry e : this.input.entries()) {
         if (e.point().distance(p) < (e.existing() ? 0.8 : 2.0)) {
            return e.point();
         }
      }

      return snap(p);
   }

   private void line(StreetPlan.Point a, StreetPlan.Point b, int rank) {
      if (a.distance(b) > 8.0) {
         this.lines.add(new StreetLayouts.Line(a, b, rank));
      }
   }

   private void local(double x0, double z0, double x1, double z1, int rank) {
      this.line(this.world(x0, z0), this.world(x1, z1), rank);
   }

   private double[] stations(double step, boolean varied) {
      ArrayList<Double> values = new ArrayList<>();
      values.add(0.0);

      for (int sign : new int[]{-1, 1}) {
         double p = 0.0;

         while (Math.abs(p) < this.reach) {
            p += (double)sign * step * (varied ? 0.78 + this.random.nextDouble() * 0.48 : 1.0);
            values.add(p);
         }
      }

      return values.stream().sorted().mapToDouble(Double::doubleValue).toArray();
   }

   private StreetPlan.Plan generate() {
      RoadPlanningBudget.check();
      switch (this.options.style()) {
         case DOWNTOWN:
         case NEW_TOWN:
            this.grid();
            break;
         case OLD_TOWN:
            this.oldTown();
            break;
         case TOWN:
            this.town();
            break;
         case SUBURB:
         case RURAL:
            this.settlements();
      }

      if (this.centers.isEmpty()) {
         this.centers.addAll(this.input.centers().isEmpty() ? List.of(this.world(0.0, 0.0)) : this.input.centers());
      }

      this.reserveEntries();
      this.attachEntries();
      ArrayList<StreetLayouts.Line> clipped = new ArrayList<>();

      for (StreetLayouts.Line line : this.lines) {
         RoadPlanningBudget.check();
         this.clip(line, clipped);
      }

      return this.graph(clipped);
   }

   private void grid() {
      boolean newer = this.options.style() == StreetPlan.Style.NEW_TOWN;
      double step = this.spacing * (newer ? 1.42 : 1.0) * (0.96 + this.random.nextDouble() * 0.08);
      double phase = (this.random.nextDouble() - 0.5) * step * 0.3;
      double[] xs = this.stations(step, false);
      double[] zs = this.stations(step * (newer ? 1.1 : 1.0), false);

      for (int i = 0; i < xs.length; i++) {
         double x = xs[i];
         int rank = Math.abs(x) < 0.01 ? 0 : (newer ? (i % 2 == 0 ? 1 : 2) : (i % 3 == 0 ? 0 : 2));
         double at = x == 0.0 ? 0.0 : x + phase;
         this.local(at, -this.reach, at, this.reach, rank);
      }

      for (int i = 0; i < zs.length; i++) {
         double z = zs[i];
         int rank = Math.abs(z) < 0.01 ? 0 : (newer ? 1 : (i % 3 == 0 ? 1 : 2));
         double at = z == 0.0 ? 0.0 : z + phase;
         this.local(-this.reach, at, this.reach, at, rank);
      }
   }

   private void oldTown() {
      double upper = Math.tan(Math.toRadians(10.0 + this.random.nextDouble() * 9.0));
      double lower = -Math.tan(Math.toRadians(7.0 + this.random.nextDouble() * 8.0));
      double[] xs = this.stations(this.spacing * 1.08, true);
      double[] zs = this.stations(this.spacing, true);

      for (double x : xs) {
         int rank = Math.abs(x) < 0.01 ? 0 : 2;
         this.local(x + lower * -this.reach, -this.reach, x, 0.0, rank);
         this.local(x, 0.0, x + upper * this.reach, this.reach, rank);
      }

      for (double z : zs) {
         double shear = z < 0.0 ? lower : upper;
         this.local(-this.reach + shear * z, z, this.reach + shear * z, z, Math.abs(z) < 0.01 ? 0 : 1);
      }
   }

   private void town() {
      double s = this.spacing * (0.96 + this.random.nextDouble() * 0.08);
      double shift = 0.0;
      this.local(-this.reach, shift, this.reach, shift, 0);

      for (int side : new int[]{-1, 1}) {
         this.local(-2.35 * s, (double)side * s + shift, 2.35 * s, (double)side * s + shift, 2);
         this.local(-1.15 * s, (double)side * 2.3 * s + shift, 1.15 * s, (double)side * 2.3 * s + shift, 2);
      }

      for (double x : new double[]{-2.35, -1.15, 0.0, 1.15, 2.35}) {
         double end = Math.abs(x) > 2.0 ? 1.0 : 2.3;
         this.local(x * s, -end * s + shift, x * s, end * s + shift, x == 0.0 ? 1 : 2);
      }

      this.centers.addAll(this.input.centers().isEmpty() ? List.of(this.world(0.0, shift)) : this.input.centers());
   }

   private void settlements() {
      boolean rural = this.options.style() == StreetPlan.Style.RURAL;
      double s = Math.max(68.0, this.spacing * (rural ? 0.82 : 1.0));
      if (!this.input.centers().isEmpty()) {
         this.centers.addAll(this.input.centers());
      } else {
         double separation = rural ? 1.55 : 1.25;
         StreetPlan.Point left = this.world(-separation * s, 0.0);
         StreetPlan.Point right = this.world(separation * s, 0.0);
         if (this.reach >= (rural ? 3.6 : 2.9) * s && this.input.allowed(left.x(), left.z()) && this.input.allowed(right.x(), right.z())) {
            this.centers.add(left);
            this.centers.add(right);
         } else {
            this.centers.add(this.world(0.0, 0.0));
            this.issues.add(new StreetPlan.Issue(this.world(0.0, 0.0), "当前选区按单个" + (rural ? "村落" : "片区") + "组织；扩大区域可增加中心", false));
         }
      }

      ArrayList<Double> sizes = new ArrayList<>();

      for (int index = 0; index < this.centers.size(); index++) {
         StreetPlan.Point center = this.centers.get(index);
         double angle = Math.toRadians(this.options.direction());
         double c = Math.cos(angle);
         double sn = Math.sin(angle);
         double step = s * (rural ? 1.0 : (index % 2 == 0 ? 0.84 : 0.78)) * (0.96 + this.random.nextDouble() * 0.08);
         sizes.add(step);

         for (int axis = 0; axis < 2; axis++) {
            for (int k = -1; k <= 1; k++) {
               double p = (double)k * step;
               StreetPlan.Point a = offset(center, axis == 0 ? p : -step, axis == 0 ? -step : p, c, sn);
               StreetPlan.Point b = offset(center, axis == 0 ? p : step, axis == 0 ? step : p, c, sn);
               this.line(a, b, k == 0 ? 1 : 2);
            }
         }
      }

      HashSet<Integer> connected = new HashSet<>();
      connected.add(0);
      ArrayList<Set<Integer>> usedPorts = new ArrayList<>();

      for (StreetPlan.Point p : this.centers) {
         usedPorts.add(new HashSet<>());
      }

      while (connected.size() < this.centers.size()) {
         int from = -1;
         int to = -1;
         double best = Double.POSITIVE_INFINITY;

         for (int a : connected) {
            if (usedPorts.get(a).size() < 4) {
               for (int b = 0; b < this.centers.size(); b++) {
                  if (!connected.contains(b)) {
                     double d = this.centers.get(a).distance(this.centers.get(b));
                     if (d < best) {
                        best = d;
                        from = a;
                        to = b;
                     }
                  }
               }
            }
         }

         StreetPlan.Point ax = this.settlementPort(from, to, sizes, usedPorts);
         StreetPlan.Point bx = this.settlementPort(to, from, sizes, usedPorts);
         this.line(ax, bx, 0);
         connected.add(to);
      }

      if (!rural && this.centers.size() > 1) {
         StreetPlan.Point ax = this.centers.get(0);
         StreetPlan.Point bx = this.centers.get(this.centers.size() - 1);
         StreetPlan.Point outerA = offset(ax, 0.0, s * 1.75, this.cos, this.sin);
         StreetPlan.Point outerB = offset(bx, 0.0, s * 1.75, this.cos, this.sin);
         this.line(ax, outerA, 1);
         this.line(outerA, outerB, 1);
         this.line(outerB, bx, 1);
      }
   }

   private StreetPlan.Point settlementPort(int from, int to, List<Double> sizes, List<Set<Integer>> used) {
      StreetPlan.Point origin = this.centers.get(from);
      StreetPlan.Point target = this.centers.get(to);
      int selected = -1;
      double best = Double.POSITIVE_INFINITY;

      for (int side = 0; side < 4; side++) {
         if (!used.get(from).contains(side)) {
            StreetPlan.Point p = offset(
               origin,
               side < 2 ? (double)(side == 0 ? 1 : -1) * sizes.get(from) : 0.0,
               side >= 2 ? (double)(side == 2 ? 1 : -1) * sizes.get(from) : 0.0,
               this.cos,
               this.sin
            );
            double d = p.distance(target);
            if (d < best) {
               best = d;
               selected = side;
            }
         }
      }

      if (selected < 0) {
         throw new IllegalArgumentException("中心连接方向过多，请调整片区中心");
      } else {
         used.get(from).add(selected);
         return offset(
            origin,
            selected < 2 ? (double)(selected == 0 ? 1 : -1) * sizes.get(from) : 0.0,
            selected >= 2 ? (double)(selected == 2 ? 1 : -1) * sizes.get(from) : 0.0,
            this.cos,
            this.sin
         );
      }
   }

   private static StreetPlan.Point offset(StreetPlan.Point p, double x, double z, double cos, double sin) {
      return snap(new StreetPlan.Point(p.x() + x * cos - z * sin, p.z() + x * sin + z * cos));
   }

   private void reserveEntries() {
      this.lines.removeIf(line -> {
         double length = line.a.distance(line.b);

         for (StreetPlan.Entry e : this.input.entries()) {
            double r = Math.toRadians(e.yaw());
            double dot = Math.abs((line.b.x() - line.a.x()) * -Math.sin(r) + (line.b.z() - line.a.z()) * Math.cos(r)) / length;
            if (dot < 0.95 && project(e.point(), line).distance(e.point()) < this.options.primaryWidth() + 38.0) {
               return true;
            }
         }

         return false;
      });
   }

   private void attachEntries() {
      List<StreetLayouts.Line> skeleton = List.copyOf(this.lines);

      for (StreetPlan.Entry entry : this.input.entries()) {
         RoadPlanningBudget.check();
         StreetPlan.Point start = entry.point();
         double r = Math.toRadians(entry.yaw());
         if (entry.existing() || !skeleton.stream().anyMatch(l -> project(start, l).distance(start) < 2.0)) {
            StreetPlan.Point lead = entry.existing()
               ? snap(new StreetPlan.Point(start.x() - Math.sin(r) * this.spacing, start.z() + Math.cos(r) * this.spacing))
               : start;
            StreetPlan.Point best = null;
            double score = Double.POSITIVE_INFINITY;

            for (StreetLayouts.Line line : skeleton) {
               StreetPlan.Point p = project(lead, line);
               double d = p.distance(lead);
               double distance = start.distance(p);
               double forward = (p.x() - start.x()) * -Math.sin(r) + (p.z() - start.z()) * Math.cos(r);
               if (d < score
                  && distance >= 32.0
                  && forward >= distance * 0.35
                  && this.input.allowed(p.x(), p.z())
                  && this.terrain.at(p.x(), p.z()).ground() == StreetPlan.Ground.LAND) {
                  score = d;
                  best = p;
               }
            }

            if (best == null) {
               this.issues.add(new StreetPlan.Issue(start, "接口附近没有可连接的骨架道路", true));
            } else {
               if (entry.existing()) {
                  this.line(start, lead, 0);
               }

               if (lead.distance(best) > 2.0) {
                  this.line(lead, best, 0);
               } else if (!entry.existing()) {
                  this.issues.add(new StreetPlan.Issue(start, "接口与骨架重合，将使用现有规划节点", false));
               }
            }
         }
      }
   }

   private static StreetPlan.Point project(StreetPlan.Point p, StreetLayouts.Line line) {
      double dx = line.b.x() - line.a.x();
      double dz = line.b.z() - line.a.z();
      double t = ((p.x() - line.a.x()) * dx + (p.z() - line.a.z()) * dz) / (dx * dx + dz * dz);
      return snap(line.a.mix(line.b, Math.max(0.0, Math.min(1.0, t))));
   }

   private boolean allowed(StreetPlan.Point p, double half) {
      for (int axis = 0; axis < 8; axis++) {
         double angle = (double)axis * Math.PI / 4.0;
         double x = p.x() + Math.cos(angle) * half;
         double z = p.z() + Math.sin(angle) * half;
         if (!this.input.allowed(x, z) || this.terrain.at(x, z).ground() != StreetPlan.Ground.LAND) {
            return false;
         }
      }

      return this.input.allowed(p.x(), p.z()) && this.terrain.at(p.x(), p.z()).ground() == StreetPlan.Ground.LAND;
   }

   private void clip(StreetLayouts.Line source, List<StreetLayouts.Line> output) {
      int steps = Math.max(1, (int)Math.ceil(source.a.distance(source.b) / 2.0));
      int start = -1;
      boolean cut = false;

      for (int i = 0; i <= steps; i++) {
         if ((i & 31) == 0) {
            RoadPlanningBudget.check();
         }

         StreetPlan.Point p = source.a.mix(source.b, (double)i / (double)steps);
         boolean valid = this.allowed(p, this.options.fullWidth(source.rank) / 2.0);
         if (valid && start < 0) {
            start = i;
         }

         if (start >= 0 && (!valid || i == steps)) {
            int end = valid ? i : i - 1;
            StreetPlan.Point a = this.anchor(source.a.mix(source.b, (double)start / (double)steps));
            StreetPlan.Point b = this.anchor(source.a.mix(source.b, (double)end / (double)steps));
            if (a.distance(b) >= 44.0) {
               output.add(new StreetLayouts.Line(a, b, source.rank));
            }

            start = -1;
         }

         if (!valid && this.input.allowed(p.x(), p.z()) && this.terrain.at(p.x(), p.z()).ground() != StreetPlan.Ground.LAND) {
            cut = true;
         }
      }

      if (cut) {
         this.issues.add(new StreetPlan.Issue(source.a.mix(source.b, 0.5), "道路避开水体、建筑障碍或未读取区域；被截断的片区将检查可达性", false));
      }
   }

   private StreetPlan.Plan graph(List<StreetLayouts.Line> source) {
      ArrayList<TreeSet<Double>> cuts = new ArrayList<>();

      for (StreetLayouts.Line line : source) {
         TreeSet<Double> values = new TreeSet<>();
         values.add(0.0);
         values.add(1.0);
         cuts.add(values);
      }

      for (int i = 0; i < source.size(); i++) {
         for (StreetPlan.Entry entry : this.input.entries()) {
            if (project(entry.point(), source.get(i)).distance(entry.point()) < (entry.existing() ? 0.5 : 2.0)) {
               cutAt(entry.point(), source.get(i), cuts.get(i));
            }
         }
      }

      for (int i = 0; i < source.size(); i++) {
         for (int j = i + 1; j < source.size(); j++) {
            RoadPlanningBudget.check();
            intersect(source.get(i), source.get(j), cuts.get(i), cuts.get(j));
         }
      }

      LinkedHashMap<StreetPlan.Point, Integer> points = new LinkedHashMap<>();
      LinkedHashMap<Long, StreetPlan.Edge> edges = new LinkedHashMap<>();

      for (int i = 0; i < source.size(); i++) {
         StreetLayouts.Line line = source.get(i);
         Double previous = null;

         for (double t : cuts.get(i)) {
            if (previous != null) {
               StreetPlan.Point a = this.anchor(line.a.mix(line.b, previous));
               StreetPlan.Point b = this.anchor(line.a.mix(line.b, t));
               if (a.distance(b) > 0.75) {
                  int ai = points.computeIfAbsent(a, px -> points.size());
                  int bi = points.computeIfAbsent(b, px -> points.size());
                  if (ai != bi) {
                     long key = edgeKey(ai, bi);
                     StreetPlan.Edge old = edges.get(key);
                     edges.put(Long.valueOf(key), new StreetPlan.Edge(ai, bi, old == null ? line.rank : Math.min(old.rank(), line.rank)));
                  }
               }
            }

            previous = t;
         }
      }

      ArrayList<StreetPlan.Vertex> vertices = new ArrayList<>();

      for (Entry<StreetPlan.Point, Integer> entryx : points.entrySet()) {
         double y = this.terrain.at(entryx.getKey().x(), entryx.getKey().z()).y();

         for (StreetPlan.Entry in : this.input.entries()) {
            if (in.point().distance(entryx.getKey()) < 1.0 && in.existing()) {
               y = in.height();
            }
         }

         vertices.add(new StreetPlan.Vertex(entryx.getValue(), entryx.getKey(), y));
      }

      ArrayList<StreetPlan.Edge> result = new ArrayList<>(edges.values());
      this.mergeStraight(vertices, result);
      result.removeIf(ex -> {
         StreetPlan.Vertex a = vertices.get(ex.a());
         StreetPlan.Vertex b = vertices.get(ex.b());
         String error = this.terrainError(a, b, ex.rank());
         if (error != null) {
            this.issues.add(new StreetPlan.Issue(a.point().mix(b.point(), 0.5), error, false));
            return true;
         } else {
            return false;
         }
      });

      boolean changed;
      do {
         changed = false;
         int[] degree = degree(vertices.size(), result);
         Iterator<StreetPlan.Edge> it = result.iterator();

         while (it.hasNext()) {
            StreetPlan.Edge e = it.next();
            double length = vertices.get(e.a()).point().distance(vertices.get(e.b()).point());
            double minimum = degree[e.a()] >= 3 && degree[e.b()] >= 3
               ? this.options.width(e.rank()) * 2.0 + 42.0
               : (degree[e.a()] < 3 && degree[e.b()] < 3 ? 12.0 : this.options.width(e.rank()) + 24.0);
            if (length < minimum) {
               this.issues.add(new StreetPlan.Issue(vertices.get(e.a()).point(), "路口间距不足，已减少短支路", false));
               it.remove();
               changed = true;
            }
         }

         if (changed) {
            this.mergeStraight(vertices, result);
         }
      } while (changed);

      this.pruneUnjoinedOverlaps(vertices, result);
      this.retainConnected(vertices, result);

      for (int i = 0; i < result.size(); i++) {
         StreetPlan.Edge e = result.get(i);
         StreetPlan.Vertex a = vertices.get(e.a());
         StreetPlan.Vertex b = vertices.get(e.b());
         double bend = StreetCurves.proposed(this.input, a, b, e.rank());
         if (bend != 0.0 && this.terrainError(a, b, e.rank(), bend) == null && this.curveClear(vertices, result, e, bend)) {
            result.set(i, new StreetPlan.Edge(e.a(), e.b(), e.rank(), bend));
         }
      }

      int[] degree = degree(vertices.size(), result);

      for (StreetPlan.Vertex v : vertices) {
         if (degree[v.id()] > 4) {
            this.issues.add(new StreetPlan.Issue(v.point(), "该连接超过首版三四向路口能力，请调整中心或接口", true));
         }

         if (degree[v.id()] >= 2) {
            ArrayList<Double> headings = new ArrayList<>();

            for (StreetPlan.Edge e : result) {
               if (e.a() == v.id() || e.b() == v.id()) {
                  StreetPlan.Point p = vertices.get(e.a() == v.id() ? e.b() : e.a()).point();
                  headings.add(Math.atan2(p.z() - v.point().z(), p.x() - v.point().x()));
               }
            }

            Collections.sort(headings);

            for (int ix = 0; ix < headings.size(); ix++) {
               double gap = headings.get((ix + 1) % headings.size()) - headings.get(ix);
               if (gap <= 0.0) {
                  gap += Math.PI * 2;
               }

               if (gap < Math.toRadians(25.0)) {
                  this.issues.add(new StreetPlan.Issue(v.point(), "接入口夹角不足25°，请调整中心或接口", true));
               }
            }
         }
      }

      for (StreetPlan.Entry entryx : this.input.entries()) {
         if (vertices.stream().noneMatch(v -> degree[v.id()] > 0 && v.point().distance(entryx.point()) < 1.0)) {
            this.issues.add(new StreetPlan.Issue(entryx.point(), "指定接口未接通；请调整位置、走向或明确连接走廊", true));
         }
      }

      if (result.size() > 160) {
         this.issues.add(new StreetPlan.Issue(this.world(0.0, 0.0), "道路数量超过首版上限，请降低密度或缩小选区", true));
      }

      if (result.isEmpty()) {
         this.issues.add(new StreetPlan.Issue(this.world(0.0, 0.0), "选区过小、过窄或受阻，无法容纳合法道路和路口", true));
      }

      return new StreetPlan.Plan(
         this.input,
         vertices,
         result,
         this.centers,
         this.issues.stream().distinct().sorted(Comparator.comparing(StreetPlan.Issue::fatal).reversed()).limit(128L).toList()
      );
   }

   private String terrainError(StreetPlan.Vertex a, StreetPlan.Vertex b, int rank) {
      return this.terrainError(a, b, rank, 0.0);
   }

   private String terrainError(StreetPlan.Vertex a, StreetPlan.Vertex b, int rank, double bend) {
      double length = a.point().distance(b.point());
      if (Double.isFinite(a.height()) && Double.isFinite(b.height())) {
         if (1.5 * Math.abs(a.height() - b.height()) / length > 0.08) {
            return "道路纵坡超过首版8%缓坡范围，未自动生成桥隧";
         } else {
            int count = Math.max(1, (int)Math.ceil(length / 2.0));

            for (int i = 0; i <= count; i++) {
               StreetPlan.Point p = StreetCurves.point(a.point(), b.point(), bend, (double)i / (double)count);
               if (!this.allowed(p, this.options.fullWidth(rank) / 2.0)) {
                  return "完整道路占地进入保护区、选区外或障碍";
               }

               double t = (double)i / (double)count;
               double y = a.height() + (b.height() - a.height()) * t * t * (3.0 - 2.0 * t);

               for (int axis = 0; axis < 8; axis++) {
                  double angle = (double)axis * Math.PI / 4.0;
                  double half = this.options.fullWidth(rank) / 2.0;
                  double ground = this.terrain.at(p.x() + Math.cos(angle) * half, p.z() + Math.sin(angle) * half).y();
                  if (Math.abs(ground - y) > this.options.earthwork() + 0.5) {
                     return "完整路幅地形起伏超过挖填预算，需调整道路或保护范围";
                  }
               }

               if (Math.abs(this.terrain.at(p.x(), p.z()).y() - y) > this.options.earthwork() + 0.5) {
                  return "地形起伏超过挖填预算，需调整道路或保护范围";
               }
            }

            return null;
         }
      } else {
         return "地形尚未读取";
      }
   }

   private static double distance(StreetPlan.Point p, StreetPlan.Point a, StreetPlan.Point b) {
      double dx = b.x() - a.x();
      double dz = b.z() - a.z();
      double t = ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / (dx * dx + dz * dz);
      return p.distance(a.mix(b, Math.max(0.0, Math.min(1.0, t))));
   }

   private boolean shared(StreetPlan.Edge a, StreetPlan.Edge b) {
      return a.a() == b.a() || a.a() == b.b() || a.b() == b.a() || a.b() == b.b();
   }

   private boolean curveClear(List<StreetPlan.Vertex> vertices, List<StreetPlan.Edge> edges, StreetPlan.Edge own, double bend) {
      StreetPlan.Point a = vertices.get(own.a()).point();
      StreetPlan.Point b = vertices.get(own.b()).point();

      for (StreetPlan.Edge other : edges) {
         if (other != own && !this.shared(own, other)) {
            StreetPlan.Point c = vertices.get(other.a()).point();
            StreetPlan.Point d = vertices.get(other.b()).point();
            double gap = (this.options.fullWidth(own.rank()) + this.options.fullWidth(other.rank())) / 2.0 + 1.0;
            double pad = gap + Math.abs(bend) + Math.abs(other.bend());
            if (!(Math.min(a.x(), b.x()) > Math.max(c.x(), d.x()) + pad)
               && !(Math.max(a.x(), b.x()) < Math.min(c.x(), d.x()) - pad)
               && !(Math.min(a.z(), b.z()) > Math.max(c.z(), d.z()) + pad)
               && !(Math.max(a.z(), b.z()) < Math.min(c.z(), d.z()) - pad)) {
               int n = Math.max(1, (int)Math.ceil(a.distance(b) / 2.0));
               int m = Math.max(1, (int)Math.ceil(c.distance(d) / 8.0));

               for (int i = 0; i <= n; i++) {
                  StreetPlan.Point p = StreetCurves.point(a, b, bend, (double)i / (double)n);

                  for (int j = 0; j < m; j++) {
                     if (distance(
                           p,
                           StreetCurves.point(c, d, other.bend(), (double)j / (double)m),
                           StreetCurves.point(c, d, other.bend(), (double)(j + 1) / (double)m)
                        )
                        < gap) {
                        return false;
                     }
                  }
               }
            }
         }
      }

      return true;
   }

   private void pruneUnjoinedOverlaps(List<StreetPlan.Vertex> vertices, List<StreetPlan.Edge> edges) {
      boolean changed;
      label67:
      do {
         changed = false;
         int[] degrees = degree(vertices.size(), edges);

         for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
               StreetPlan.Edge a = edges.get(i);
               StreetPlan.Edge b = edges.get(j);
               if (!this.shared(a, b)) {
                  StreetPlan.Point ap = vertices.get(a.a()).point();
                  StreetPlan.Point aq = vertices.get(a.b()).point();
                  StreetPlan.Point bp = vertices.get(b.a()).point();
                  StreetPlan.Point bq = vertices.get(b.b()).point();
                  double gap = (this.options.fullWidth(a.rank()) + this.options.fullWidth(b.rank())) / 2.0 + 1.0;
                  if (!(Math.min(Math.min(distance(ap, bp, bq), distance(aq, bp, bq)), Math.min(distance(bp, ap, aq), distance(bq, ap, aq))) >= gap)) {
                     boolean spurA = degrees[a.a()] == 1 || degrees[a.b()] == 1;
                     boolean spurB = degrees[b.a()] == 1 || degrees[b.b()] == 1;
                     StreetPlan.Edge remove = spurA != spurB
                        ? (spurA ? a : b)
                        : (a.rank() != b.rank() ? (a.rank() > b.rank() ? a : b) : (ap.distance(aq) < bp.distance(bq) ? a : b));
                     edges.remove(remove);
                     this.issues.add(new StreetPlan.Issue(vertices.get(remove.a()).point(), "边界短支路无法形成完整路口，已留出道路间距", false));
                     changed = true;
                     continue label67;
                  }
               }
            }
         }
      } while (changed);
   }

   private void retainConnected(List<StreetPlan.Vertex> vertices, List<StreetPlan.Edge> edges) {
      ArrayList<List<Integer>> adjacency = new ArrayList<>();

      for (StreetPlan.Vertex v : vertices) {
         adjacency.add(new ArrayList<>());
      }

      for (StreetPlan.Edge e : edges) {
         adjacency.get(e.a()).add(e.b());
         adjacency.get(e.b()).add(e.a());
      }

      HashSet<Integer> seen = new HashSet<>();
      List<Integer> largest = List.of();

      for (StreetPlan.Vertex v : vertices) {
         if (!adjacency.get(v.id()).isEmpty() && !seen.contains(v.id())) {
            ArrayList<Integer> component = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(v.id());

            while (!queue.isEmpty()) {
               int id = queue.remove();
               if (seen.add(id)) {
                  component.add(id);
                  queue.addAll(adjacency.get(id));
               }
            }

            if (component.size() > largest.size()) {
               largest = component;
            }
         }
      }

      HashSet<Integer> keep = new HashSet<>(largest);
      boolean removed = edges.removeIf(e -> !keep.contains(e.a()));
      if (removed) {
         this.issues.add(new StreetPlan.Issue(this.world(0.0, 0.0), "部分受阻片区无法接入主体路网，已标为留白；可调整保护区或指定接口后重算", false));
      }
   }

   private static int[] degree(int count, List<StreetPlan.Edge> edges) {
      int[] result = new int[count];

      for (StreetPlan.Edge e : edges) {
         result[e.a()]++;
         result[e.b()]++;
      }

      return result;
   }

   private void mergeStraight(List<StreetPlan.Vertex> vertices, List<StreetPlan.Edge> edges) {
      boolean changed;
      do {
         changed = false;
         int[] degree = degree(vertices.size(), edges);

         for (StreetPlan.Vertex v : vertices) {
            if (degree[v.id()] == 2 && this.input.entries().stream().noneMatch(e -> e.point().distance(v.point()) < 1.0)) {
               List<StreetPlan.Edge> adjacent = edges.stream().filter(e -> e.a() == v.id() || e.b() == v.id()).toList();
               StreetPlan.Edge x = adjacent.get(0);
               StreetPlan.Edge y = adjacent.get(1);
               int a = x.a() == v.id() ? x.b() : x.a();
               int b = y.a() == v.id() ? y.b() : y.a();
               StreetPlan.Point p = vertices.get(a).point();
               StreetPlan.Point q = vertices.get(b).point();
               double dot = ((p.x() - v.point().x()) * (q.x() - v.point().x()) + (p.z() - v.point().z()) * (q.z() - v.point().z()))
                  / (p.distance(v.point()) * q.distance(v.point()));
               if (dot < -0.999 && x.rank() == y.rank()) {
                  edges.remove(x);
                  edges.remove(y);
                  if (a != b) {
                     edges.add(new StreetPlan.Edge(a, b, x.rank()));
                  }

                  changed = true;
                  break;
               }
            }
         }
      } while (changed);
   }

   private static long edgeKey(int a, int b) {
      return (long)Math.min(a, b) << 32 ^ (long)Math.max(a, b) & 4294967295L;
   }

   private static double cross(double ax, double az, double bx, double bz) {
      return ax * bz - az * bx;
   }

   private static void intersect(StreetLayouts.Line a, StreetLayouts.Line b, Set<Double> ca, Set<Double> cb) {
      double ax = a.b.x() - a.a.x();
      double az = a.b.z() - a.a.z();
      double bx = b.b.x() - b.a.x();
      double bz = b.b.z() - b.a.z();
      double dx = b.a.x() - a.a.x();
      double dz = b.a.z() - a.a.z();
      double det = cross(ax, az, bx, bz);
      if (!(Math.abs(det) < 1.0E-7)) {
         double ta = cross(dx, dz, bx, bz) / det;
         double tb = cross(dx, dz, ax, az) / det;
         if (ta >= 0.0 && ta <= 1.0 && tb >= 0.0 && tb <= 1.0) {
            ca.add(ta);
            cb.add(tb);
         }
      } else {
         if (Math.abs(cross(dx, dz, ax, az)) < 1.0E-4) {
            for (StreetPlan.Point p : List.of(b.a, b.b)) {
               cutAt(p, a, ca);
            }

            for (StreetPlan.Point p : List.of(a.a, a.b)) {
               cutAt(p, b, cb);
            }
         }
      }
   }

   private static void cutAt(StreetPlan.Point p, StreetLayouts.Line line, Set<Double> cuts) {
      double dx = line.b.x() - line.a.x();
      double dz = line.b.z() - line.a.z();
      double t = ((p.x() - line.a.x()) * dx + (p.z() - line.a.z()) * dz) / (dx * dx + dz * dz);
      if (t > 0.0 && t < 1.0) {
         cuts.add(t);
      }
   }

   private static record Line(StreetPlan.Point a, StreetPlan.Point b, int rank) {
   }
}
