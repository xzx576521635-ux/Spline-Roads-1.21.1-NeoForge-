package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MultiInterchange {
   private static final double STEP = 1.5;
   private static final double MARGIN = 0.4;
   private final RoadGeometry.Node[] anchors;
   private final RoadGeometry.Settings[] settings;
   private final InterchangePlanner.Options options;
   private final int n;
   private final RoadGeometry.V center;
   private final RoadGeometry.V[] radial;
   private final double[] lengths;
   private final double[] levels;
   private final double gap;
   private final List<RoadGeometry.Mesh> mains = new ArrayList<>();
   private final List<MultiInterchange.Route> routes = new ArrayList<>();

   private MultiInterchange(RoadGeometry.Node[] nodes, RoadGeometry.Settings[] main, InterchangePlanner.Options options, double baseHeight) {
      this.anchors = (RoadGeometry.Node[])nodes.clone();
      this.n = nodes.length;
      this.settings = (RoadGeometry.Settings[])main.clone();
      this.options = options;
      if ((this.n == 5 || this.n == 6) && main.length == 3) {
         for (RoadGeometry.Settings s : this.settings) {
            s.validate();
            if (!RoadProfile.catalog(s).twoWay() || s.style().ramp()) {
               throw new IllegalArgumentException("主路必须为双向普通道路或高速道路");
            }
         }

         RoadGeometry.V a = nodes[0].position();
         RoadGeometry.V b = nodes[1].position();
         RoadGeometry.V c = nodes[2].position();
         RoadGeometry.V d = nodes[3].position();
         RoadGeometry.V ab = b.sub(a).horizontalUnit();
         RoadGeometry.V cd = d.sub(c).horizontalUnit();
         double det = cross(ab, cd);
         if (Math.abs(det) < 0.4) {
            throw new IllegalArgumentException("AB 与 CD 夹角过小，请选择穿过同一中心的主路");
         } else {
            RoadGeometry.V hit = a.add(ab.mul(cross(c.sub(a), cd) / det));
            this.gap = Math.ceil((options.clearance() + Arrays.stream(main).mapToDouble(RoadGeometry.Settings::thickness).max().orElse(1.0) + 0.5) * 2.0) / 2.0;
            double bottom = Arrays.stream(nodes).mapToDouble(v -> v.position().y()).min().orElse(baseHeight);
            double drop = options.adjustEndpoints() && options.allowShrink() ? options.maxLowering() : 0.0;
            double base = Math.max(Math.min(baseHeight, bottom), bottom - drop);
            this.center = new RoadGeometry.V(hit.x(), base, hit.z());
            this.levels = new double[3];

            for (int axis = 0; axis < 3; axis++) {
               this.levels[axis] = nodes[axis * 2].position().y();
            }

            double[] original = (double[])this.levels.clone();
            Integer[] order = new Integer[]{0, 1, 2};
            Arrays.sort(order, Comparator.<Integer>comparingDouble(ix -> original[ix]).thenComparing(Comparator.reverseOrder()));
            this.levels[order[0]] = Math.max(base, original[order[0]] - drop);

            for (int i = 1; i < 3; i++) {
               this.levels[order[i]] = Math.max(original[order[i]] - drop, this.levels[order[i - 1]] + this.gap);
            }

            if (options.adjustEndpoints()) {
               for (int i = 0; i < this.n; i++) {
                  double rise = this.levels[i / 2] - original[i / 2];
                  RoadGeometry.V p = nodes[i].position();
                  this.anchors[i] = new RoadGeometry.Node(new RoadGeometry.V(p.x(), p.y() + rise, p.z()), nodes[i].yaw(), nodes[i].grade());
               }
            }

            this.radial = new RoadGeometry.V[this.n];
            this.lengths = new double[this.n];

            for (int i = 0; i < this.n; i++) {
               RoadGeometry.V delta = nodes[i].position().sub(this.center);
               this.lengths[i] = delta.horizontalLength();
               if (!RoadGeometry.finite(delta.x(), delta.y(), delta.z()) || this.lengths[i] < 69.999999 || this.lengths[i] > 1024.000001) {
                  throw new IllegalArgumentException("每个端点离中心需为 70–1024 格");
               }

               this.radial[i] = delta.horizontalUnit();
               if (i % 2 == 1 && this.radial[i].dot(this.radial[i - 1]) > -0.9999) {
                  throw new IllegalArgumentException("AB、CD、EF 各自需穿过同一个中心且方向相反");
               }
            }

            for (int i = 0; i < this.n; i++) {
               for (int j = i + 1; j < this.n; j++) {
                  if (this.radial[i].dot(this.radial[j]) > Math.cos(Math.toRadians(25.0))) {
                     throw new IllegalArgumentException("相邻方向夹角至少 25°");
                  }
               }
            }
         }
      } else {
         throw new IllegalArgumentException("五、六向立交需要 AB、CD、E 或 EF 三组端点");
      }
   }

   public static InterchangePlanner.Plan plan(RoadGeometry.Node[] nodes, RoadGeometry.Settings[] settings, InterchangePlanner.Options options) {
      return plan(nodes, settings, options, Arrays.stream(nodes).mapToDouble(v -> v.position().y()).min().orElseThrow());
   }

   public static InterchangePlanner.Plan plan(
      RoadGeometry.Node[] nodes, RoadGeometry.Settings[] settings, InterchangePlanner.Options options, double baseHeight
   ) {
      if (!InterchangePlanner.presets(nodes.length).contains(options.preset())) {
         throw new IllegalArgumentException("请选择对应向数的全定向立交");
      } else {
         InterchangePlanner.Plan initial = null;

         IllegalArgumentException failure;
         try {
            initial = new MultiInterchange(nodes, settings, options, baseHeight).build();
            if (!options.adjustEndpoints() || !options.allowShrink()) {
               return initial;
            }

            failure = new IllegalArgumentException("收紧占地");
         } catch (IllegalArgumentException var27) {
            failure = var27;
            if (!options.adjustEndpoints()) {
               throw var27;
            }
         }

         RoadGeometry.V a = nodes[0].position();
         RoadGeometry.V u = nodes[1].position().sub(a).horizontalUnit();
         RoadGeometry.V b = nodes[2].position();
         RoadGeometry.V v = nodes[3].position().sub(b).horizontalUnit();
         double det = cross(u, v);
         if (Math.abs(det) < 0.4) {
            throw failure;
         } else {
            RoadGeometry.V center = a.add(u.mul(cross(b.sub(a), v) / det));
            double shortest = Arrays.stream(nodes).mapToDouble(p -> p.position().sub(center).horizontalLength()).min().orElse(70.0);
            double failed = options.allowShrink() ? 70.0 : Math.max(70.0, shortest);
            double radius = failed;
            InterchangePlanner.Plan best = null;

            for (int attempt = 0; attempt < 17 && radius < 1024.0; attempt++) {
               radius = Math.min(1024.0, Math.ceil(Math.max(radius + 24.0, radius * 1.24) / 4.0) * 4.0);

               try {
                  best = new MultiInterchange(extend(nodes, center, radius, options.allowShrink()), settings, options, baseHeight).build();
                  break;
               } catch (IllegalArgumentException var26) {
                  failed = radius;
                  failure = var26;
               }
            }

            if (best != null) {
               for (int k = 0; k < 6 && radius - failed > 8.0; k++) {
                  double middle = Math.ceil((radius + failed) / 8.0) * 4.0;

                  try {
                     InterchangePlanner.Plan p = new MultiInterchange(extend(nodes, center, middle, options.allowShrink()), settings, options, baseHeight)
                        .build();
                     best = p;
                     radius = middle;
                  } catch (IllegalArgumentException var25) {
                     failed = middle;
                  }
               }

               return initial != null && extent(best) >= extent(initial) - 0.5 ? initial : best;
            } else if (initial != null) {
               return initial;
            } else {
               throw new IllegalArgumentException("五/六向布局无法满足净空、坡度或半径：" + failure.getMessage());
            }
         }
      }
   }

   private static double extent(InterchangePlanner.Plan plan) {
      return plan.anchors().stream().mapToDouble(p -> p.position().sub(plan.center()).horizontalLength()).max().orElseThrow();
   }

   private static RoadGeometry.Node[] extend(RoadGeometry.Node[] nodes, RoadGeometry.V center, double length, boolean shrink) {
      RoadGeometry.Node[] result = (RoadGeometry.Node[])nodes.clone();

      for (int i = 0; i < nodes.length; i++) {
         RoadGeometry.V delta = nodes[i].position().sub(center);
         RoadGeometry.V p = center.add(delta.horizontalUnit().mul(shrink ? length : Math.max(length, delta.horizontalLength())));
         result[i] = new RoadGeometry.Node(new RoadGeometry.V(p.x(), nodes[i].position().y(), p.z()), nodes[i].yaw(), nodes[i].grade());
      }

      return result;
   }

   private static double cross(RoadGeometry.V a, RoadGeometry.V b) {
      return a.x() * b.z() - a.z() * b.x();
   }

   private static RoadGeometry.V cubic(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c, RoadGeometry.V d, double t) {
      double u = 1.0 - t;
      return a.mul(u * u * u).add(b.mul(3.0 * u * u * t)).add(c.mul(3.0 * u * t * t)).add(d.mul(t * t * t));
   }

   private RoadGeometry.Settings mainSettings(int axis) {
      RoadGeometry.Settings s = this.settings[axis];
      return s.structure(
            s.structure() != RoadGeometry.Structure.BRIDGE && s.structure() != RoadGeometry.Structure.TUNNEL
               ? (
                  s.structure() == RoadGeometry.Structure.GROUND && Math.abs(this.levels[axis] - this.center.y()) < 0.001
                     ? RoadGeometry.Structure.GROUND
                     : RoadGeometry.Structure.AUTO
               )
               : s.structure()
         )
         .options(s.options().traffic(this.options.leftTraffic()).ends(RoadTransitions.Ends.NONE).route(s.options().routing().fit(false).fan(this.n, 0.0, 0.0)));
   }

   private InterchangePlanner.Plan build() {
      ArrayList<InterchangePlanner.Leg> legs = new ArrayList<>();

      for (int axis = 0; axis < 3; axis++) {
         int first = axis * 2;
         int second = first + 1;
         RoadGeometry.V from = this.anchors[first].position();
         RoadGeometry.V to = second < this.n ? this.anchors[second].position() : this.center.add(this.radial[first].mul(this.lengths[first] * 0.86));
         double length = to.sub(from).horizontalLength();
         int steps = (int)Math.ceil(length / 1.5);
         List<RoadGeometry.Sample> samples = new ArrayList<>();
         RoadGeometry.Settings main = this.mainSettings(axis);
         if (axis == 2 && this.n == 5) {
            RoadTransitions.Section end = new RoadTransitions.Section(
               this.options.lanes() == 1 ? RoadGeometry.Style.O2_RAIL : RoadGeometry.Style.O4_RAIL,
               this.options.width() * 2.0 + 1.0,
               false,
               false,
               false,
               main.options().outerRail()
            );
            main = RoadTransitions.ends(main, null, end).options(main.options().ends(new RoadTransitions.Ends(null, end, true)));
         }

         for (int k = 0; k <= steps; k++) {
            double t = (double)k / (double)steps;
            RoadGeometry.V p = from.add(to.sub(from).mul(t));
            double r = p.sub(this.center).horizontalLength();
            int end = p.sub(this.center).dot(this.radial[first]) >= 0.0 ? first : second;
            double y = this.levels[axis];
            if (end < this.n) {
               double blend = smooth((this.lengths[end] - r - 4.0) / (this.lengths[end] * 0.13 - 4.0));
               y = this.anchors[end].position().y() + (this.levels[axis] - this.anchors[end].position().y()) * blend;
            }

            samples.add(
               new RoadGeometry.Sample(
                  new RoadGeometry.V(p.x(), y, p.z()), to.sub(from).horizontalUnit().left(), 0.0, RoadTransitions.width(main, t * length, length) / 2.0
               )
            );
         }

         RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, main);
         if (RoadGrades.maximum(mesh) > 0.1500001) {
            throw new IllegalArgumentException("主路升降段超过 15% 纵坡；请允许调整端点或延长主路");
         }

         this.mains.add(mesh);
         legs.add(new InterchangePlanner.Leg(axis == 2 && this.n == 5 ? "E 支路" : "主路 " + (axis + 1), first, second < this.n ? second : first, mesh));
      }

      for (int i = 0; i < this.n; i++) {
         for (int j = 0; j < this.n; j++) {
            if (i != j && i / 2 != j / 2) {
               this.routes.add(this.route(i, j));
            }
         }
      }

      List<MultiInterchange.Grid> grids = this.mains.stream().map(MultiInterchange.Grid::new).toList();

      for (MultiInterchange.Route r : this.routes) {
         r.holdStart = sharedEnd(r.flat, grids.get(r.from / 2), true);
         r.holdEnd = sharedEnd(r.flat, grids.get(r.to / 2), false);
      }

      List<MultiInterchange.Grid> routeGrids = this.routes.stream().map(rx -> new MultiInterchange.Grid(rx.flat)).toList();
      double[][] sharedFrom = new double[this.routes.size()][this.routes.size()];
      double[][] sharedTo = new double[this.routes.size()][this.routes.size()];

      for (int i = 0; i < this.routes.size(); i++) {
         for (int jx = 0; jx < this.routes.size(); jx++) {
            if (i != jx) {
               MultiInterchange.Route a = this.routes.get(i);
               MultiInterchange.Route b = this.routes.get(jx);
               sharedFrom[i][jx] = a.from == b.from ? sharedEnd(a.flat, routeGrids.get(jx), true) : -1.0;
               sharedTo[i][jx] = a.to == b.to ? sharedEnd(a.flat, routeGrids.get(jx), false) : a.flat.length() + 1.0;
               rHold(a, sharedFrom[i][jx], sharedTo[i][jx]);
            }
         }
      }

      for (int i = 0; i < this.routes.size(); i++) {
         MultiInterchange.Route r = this.routes.get(i);

         for (RoadGeometry.Sample sample : r.flat.samples()) {
            double d = sample.distance();

            for (int m = 0; m < this.mains.size(); m++) {
               MultiInterchange.Hit hit = grids.get(m).hit(sample);
               if (hit != null && (m != r.from / 2 || !(d <= r.holdStart + 0.01)) && (m != r.to / 2 || !(d >= r.holdEnd - 0.01))) {
                  crossing(r, d);

                  for (int layer = 0; layer < 16; layer++) {
                     if (Math.abs(this.center.y() + (double)layer * this.gap - this.levels[m]) < this.options.clearance() + this.settings[m].thickness() + 0.4) {
                        r.mainCross.add(layer);
                     }
                  }
               }
            }

            for (int jxx = 0; jxx < this.routes.size(); jxx++) {
               if (jxx != i) {
                  MultiInterchange.Hit hit = routeGrids.get(jxx).hit(sample);
                  if (hit != null && !(d <= sharedFrom[i][jxx] + 0.01) && !(d >= sharedTo[i][jxx] - 0.01)) {
                     crossing(r, d);
                     r.conflicts.add(jxx);
                     this.routes.get(jxx).conflicts.add(i);
                  }
               }
            }
         }
      }

      this.assignLayers();
      double minRadius = Double.POSITIVE_INFINITY;
      ArrayList<RoadGeometry.Mesh> elevated = new ArrayList<>();

      for (MultiInterchange.Route r : this.routes) {
         RoadGeometry.Mesh mesh = this.elevate(r);
         RoadGrades.validate(mesh);
         RoadRibbon.checkSelfIntersections(mesh, this.options.clearance());
         double radius = minRadius(mesh);
         minRadius = Math.min(minRadius, radius);
         if (radius + 1.0E-4 < this.options.radius()) {
            throw new IllegalArgumentException(name(r) + " 转弯半径 " + (int)radius + "，需至少 " + (int)this.options.radius() + " 格；请延长端点");
         }

         elevated.add(mesh);
      }

      this.verify(elevated, sharedFrom, sharedTo);
      double highest = Math.max(Arrays.stream(this.levels).max().orElseThrow(), elevated.stream().mapToDouble(mx -> mx.max().y()).max().orElseThrow());
      Set<MultiInterchange.Edge> owned = new HashSet<>();

      for (int i = 0; i < this.routes.size(); i++) {
         MultiInterchange.Route r = this.routes.get(i);
         RoadGeometry.Mesh mesh = elevated.get(i);
         List<RoadGeometry.Sample> run = new ArrayList<>();

         for (int k = 1; k < mesh.samples().size(); k++) {
            RoadGeometry.Sample a = mesh.samples().get(k - 1);
            RoadGeometry.Sample b = mesh.samples().get(k);
            if (owned.add(new MultiInterchange.Edge(a, b))) {
               if (run.isEmpty()) {
                  run.add(a);
               }

               run.add(b);
            } else if (!run.isEmpty()) {
               legs.add(new InterchangePlanner.Leg(name(r), r.from, r.to, RoadRibbon.mesh(run, mesh.settings())));
               run = new ArrayList<>();
            }
         }

         if (!run.isEmpty()) {
            legs.add(new InterchangePlanner.Leg(name(r), r.from, r.to, RoadRibbon.mesh(run, mesh.settings())));
         }
      }

      return new InterchangePlanner.Plan(List.copyOf(legs), this.routes.size(), minRadius, highest, this.center, List.of(this.anchors));
   }

   private void assignLayers() {
      int[] allowed = new int[this.routes.size()];

      for (int i = 0; i < this.routes.size(); i++) {
         MultiInterchange.Route r = this.routes.get(i);

         for (int layer = 0; layer < 16; layer++) {
            double y = this.center.y() + (double)layer * this.gap;
            if (!r.mainCross.contains(layer)
               && (
                  !(r.crossEnd >= 0.0)
                     || !(Math.abs(y - r.sourceHeight) * 1.25 / 0.15 > r.crossStart - r.holdStart - 3.0 + 0.01)
                        && !(Math.abs(y - r.targetHeight) * 1.25 / 0.15 > r.holdEnd - r.crossEnd - 3.0 + 0.01)
               )) {
               allowed[i] |= 1 << layer;
            }
         }

         if (allowed[i] == 0) {
            throw new IllegalArgumentException(name(r) + " 没有满足净空及 15% 坡度的可达层高");
         }
      }

      for (int ceiling = 0; ceiling < 16; ceiling++) {
         int mask = (1 << ceiling + 1) - 1;
         boolean possible = true;

         for (int a : allowed) {
            if ((a & mask) == 0) {
               possible = false;
               break;
            }
         }

         if (possible) {
            int[] budget = new int[]{40000};
            if (this.color(allowed, mask, 0, budget)) {
               return;
            }
         }
      }

      throw new IllegalArgumentException("匝道冲突无法在可达层高内分离，请增加端点距离");
   }

   private boolean color(int[] allowed, int ceiling, int count, int[] budget) {
      if (count == this.routes.size()) {
         return true;
      } else if (--budget[0] < 0) {
         return false;
      } else {
         int best = -1;
         int choices = 0;
         int fewest = 17;
         int degree = -1;

         for (int i = 0; i < this.routes.size(); i++) {
            if (this.routes.get(i).layer < 0) {
               MultiInterchange.Route r = this.routes.get(i);
               int available = allowed[i] & ceiling;

               for (int j : r.conflicts) {
                  if (this.routes.get(j).layer >= 0) {
                     available &= ~(1 << this.routes.get(j).layer);
                  }
               }

               int size = Integer.bitCount(available);
               if (size == 0) {
                  return false;
               }

               if (size < fewest || size == fewest && r.conflicts.size() > degree) {
                  best = i;
                  choices = available;
                  fewest = size;
                  degree = r.conflicts.size();
               }
            }
         }

         for (MultiInterchange.Route r = this.routes.get(best); choices != 0; r.layer = -1) {
            int layer = Integer.numberOfTrailingZeros(choices);
            choices &= choices - 1;
            r.layer = layer;
            if (this.color(allowed, ceiling, count + 1, budget)) {
               return true;
            }
         }

         return false;
      }
   }

   private static void rHold(MultiInterchange.Route a, double start, double end) {
      a.holdStart = Math.max(a.holdStart, start);
      a.holdEnd = Math.min(a.holdEnd, end);
   }

   private static void crossing(MultiInterchange.Route r, double distance) {
      r.crossStart = Math.min(r.crossStart, distance);
      r.crossEnd = Math.max(r.crossEnd, distance);
   }

   private static String name(MultiInterchange.Route r) {
      return "定向 " + (char)(65 + r.from) + " → " + (char)(65 + r.to);
   }

   private static int directionLanes(RoadGeometry.Settings s, int arm, boolean arrival) {
      RoadLanes.Counts c = RoadLanes.counts(s);
      return arm % 2 == 0 != arrival ? c.forward() : c.reverse();
   }

   private static int directionSide(RoadGeometry.Settings s, int arm, boolean arrival) {
      return (arm % 2 == 0 != arrival ? 1 : -1) * RoadProfile.trafficSign(s.options().leftTraffic());
   }

   private MultiInterchange.Route route(int from, int to) {
      double sign = this.options.leftTraffic() ? -1.0 : 1.0;
      RoadGeometry.V in = this.radial[from].mul(-1.0);
      RoadGeometry.V out = this.radial[to];
      RoadProfile.Layout a = RoadProfile.layout(this.settings[from / 2], this.settings[from / 2].width());
      RoadProfile.Layout b = RoadProfile.layout(this.settings[to / 2], this.settings[to / 2].width());
      if ((from == 4 && this.n == 5 || directionLanes(this.settings[from / 2], from, false) >= this.options.lanes())
         && (to == 4 && this.n == 5 || directionLanes(this.settings[to / 2], to, true) >= this.options.lanes())) {
         boolean highwayA = a.catalog().type() == RoadProfile.Type.HIGHWAY;
         boolean highwayB = b.catalog().type() == RoadProfile.Type.HIGHWAY;
         double portA = highwayA ? a.shoulderWidth() : 0.5;
         double portB = highwayB ? b.shoulderWidth() : 0.5;
         double offsetA = highwayA
            ? Math.abs(a.outer(directionSide(this.settings[from / 2], from, false))) + portA / 2.0
            : this.settings[from / 2].width() / 2.0 - 0.2 + portA / 2.0;
         double offsetB = highwayB
            ? Math.abs(b.outer(directionSide(this.settings[to / 2], to, true))) + portB / 2.0
            : this.settings[to / 2].width() / 2.0 - 0.2 + portB / 2.0;
         if (this.n == 5 && from == 4) {
            RoadProfile.Layout end = RoadProfile.layout(this.mains.get(2), this.mains.get(2).last());
            portA = this.options.width();
            offsetA = end.median() / 2.0 + portA / 2.0;
         }

         if (this.n == 5 && to == 4) {
            RoadProfile.Layout end = RoadProfile.layout(this.mains.get(2), this.mains.get(2).last());
            portB = this.options.width();
            offsetB = end.median() / 2.0 + portB / 2.0;
         }

         List<RoadGeometry.Sample> departure = this.feeder(from, to, false, offsetA, portA);
         List<RoadGeometry.Sample> arrival = this.feeder(to, from, true, offsetB, portB);
         ArrayList<RoadGeometry.Sample> samples = new ArrayList<>(departure);
         RoadGeometry.V lead1 = departure.get(departure.size() - 1).center();
         RoadGeometry.V tail0 = arrival.get(arrival.size() - 1).center();
         double span = lead1.sub(tail0).horizontalLength();
         double control = span * 0.46;
         curve(samples, lead1, lead1.add(in.mul(control)), tail0.sub(out.mul(control)), tail0, in, out, this.options.width() / 2.0);

         for (int k = arrival.size() - 2; k >= 0; k--) {
            RoadGeometry.Sample p = arrival.get(k);
            samples.add(new RoadGeometry.Sample(p.center(), new RoadGeometry.V(-p.left().x(), 0.0, -p.left().z()), 0.0, p.halfWidth()));
         }

         RoadGeometry.Settings ramp = new RoadGeometry.Settings(
               RoadGeometry.Mode.CURVE, this.options.lanes() == 1 ? RoadGeometry.Style.R1 : RoadGeometry.Style.R2, this.options.width(), 1.0, 0.4, 90.0
            )
            .rampTurn(RoadGeometry.RampTurn.AUTO)
            .options(RoadProfile.Options.DEFAULT.traffic(this.options.leftTraffic()));
         return new MultiInterchange.Route(from, to, RoadRibbon.mesh(samples, ramp), this.levels[from / 2], this.levels[to / 2]);
      } else {
         throw new IllegalArgumentException("主路单向车道少于匝道车道，请增加主路车道或选择单车道匝道");
      }
   }

   private int rank(int from, int to, boolean arrival) {
      ArrayList<Integer> destinations = new ArrayList<>();

      for (int j = 0; j < this.n; j++) {
         if (j != from && j / 2 != from / 2) {
            destinations.add(j);
         }
      }

      double sign = (double)((this.options.leftTraffic() ? -1 : 1) * (arrival ? -1 : 1));
      destinations.sort(Comparator.comparingDouble(jx -> {
         double angle = Math.atan2(cross(this.radial[from], this.radial[jx]) * sign, this.radial[from].dot(this.radial[jx]));
         return angle < 0.0 ? angle + (Math.PI * 2) : angle;
      }));
      return destinations.indexOf(to);
   }

   private List<RoadGeometry.Sample> feeder(int arm, int other, boolean arrival, double offset, double port) {
      int rank = this.rank(arm, other, arrival);
      int count = this.n - (this.n == 5 && arm == 4 ? 1 : 2);
      double sign = (double)((this.options.leftTraffic() ? -1 : 1) * (arrival ? -1 : 1));
      double spacing = this.options.width() + 3.0;
      RoadGeometry.V inward = this.radial[arm].mul(-1.0);
      boolean terminal = this.n == 5 && arm == 4;
      double outer = terminal
         ? Math.max(offset, RoadProfile.layout(this.mains.get(2), this.mains.get(2).last()).median() / 2.0 + this.options.width() / 2.0 + 0.25)
         : this.settings[arm / 2].width() / 2.0 + this.options.width() / 2.0 + 0.25;
      double root = this.lengths[arm] * (terminal ? 0.86 : 0.97);
      double leaf = this.lengths[arm] * (this.n == 5 ? 0.32 : 0.4);
      double lead = terminal ? 32.0 : Math.max(this.options.lanes() == 2 ? 108.0 : 80.0, this.options.transition());
      double fork = Math.max(48.0, Math.sqrt(this.options.radius() * spacing * 2.5 * 6.0));
      double secondFork = Math.max(32.0, Math.sqrt(this.options.radius() * spacing * 0.5 * 6.0));
      double total = lead + 18.0 + fork + 18.0 + secondFork;
      if (root - leaf < total + 24.0) {
         throw new IllegalArgumentException("端点过近，无法容纳两级分合流与升降段");
      } else {
         ArrayList<RoadGeometry.Sample> out = new ArrayList<>();
         RoadProfile.Layout layout = RoadProfile.layout(this.settings[arm / 2], this.settings[arm / 2].width());
         boolean highway = !terminal && layout.catalog().type() == RoadProfile.Type.HIGHWAY;
         double auxiliary = highway ? Math.abs(layout.outer(directionSide(this.settings[arm / 2], arm, arrival))) + (this.options.width() - 1.0) / 2.0 : outer;
         int steps = (int)Math.ceil(lead / 1.5);

         for (int k = 0; k <= steps; k++) {
            double t = (double)k / (double)steps;
            double grow = RoadGeometry.Settings.smooth(Math.min(1.0, t / 0.32));
            double shift = highway
               ? (auxiliary - offset) * grow + (outer - auxiliary) * RoadGeometry.Settings.smooth(Math.max(0.0, (t - 0.65) / 0.35))
               : (outer - offset) * (terminal ? grow : RoadGeometry.Settings.smooth(t));
            out.add(
               new RoadGeometry.Sample(
                  this.point(arm, root - lead * t, (offset + shift) * sign), inward.left(), 0.0, (port + (this.options.width() - port) * grow) / 2.0
               )
            );
         }

         for (int k = 1; k < steps; k++) {
            RoadGeometry.Sample p = out.get(k);
            RoadGeometry.V tangent = out.get(k + 1).center().sub(out.get(k - 1).center()).horizontalUnit();
            out.set(k, new RoadGeometry.Sample(p.center(), tangent.left(), 0.0, p.halfWidth()));
         }

         double station = root - lead;
         RoadGeometry.V a = this.point(arm, station, outer * sign);
         line(out, a, this.point(arm, station - 18.0, outer * sign), this.options.width() / 2.0);
         station -= 18.0;
         int group = rank / 2;
         int first = group * 2;
         int last = Math.min(count - 1, first + 1);
         double middle = outer + (double)(first + last) * 0.5 * spacing;
         double target = outer + (double)rank * spacing;
         RoadGeometry.V b = this.point(arm, station - fork, middle * sign);
         a = this.point(arm, station, outer * sign);
         curve(out, a, a.add(inward.mul(fork / 3.0)), b.sub(inward.mul(fork / 3.0)), b, inward, inward, this.options.width() / 2.0);
         station -= fork;
         line(out, b, this.point(arm, station - 18.0, middle * sign), this.options.width() / 2.0);
         station -= 18.0;
         a = this.point(arm, station, middle * sign);
         b = this.point(arm, station - secondFork, target * sign);
         curve(out, a, a.add(inward.mul(secondFork / 3.0)), b.sub(inward.mul(secondFork / 3.0)), b, inward, inward, this.options.width() / 2.0);
         line(out, b, this.point(arm, leaf, target * sign), this.options.width() / 2.0);
         return out;
      }
   }

   private RoadGeometry.V point(int arm, double radius, double offset) {
      return this.center.add(this.radial[arm].mul(radius)).add(this.radial[arm].mul(-1.0).left().mul(offset));
   }

   private static void curve(
      List<RoadGeometry.Sample> samples,
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V c,
      RoadGeometry.V d,
      RoadGeometry.V start,
      RoadGeometry.V end,
      double width
   ) {
      int steps = (int)Math.ceil((b.sub(a).horizontalLength() + c.sub(b).horizontalLength() + d.sub(c).horizontalLength()) / 1.5);

      for (int k = samples.isEmpty() ? 0 : 1; k <= steps; k++) {
         double t = (double)k / (double)steps;
         RoadGeometry.V p = cubic(a, b, c, d, t);
         RoadGeometry.V tangent = k == 0
            ? start
            : (k == steps ? end : cubic(a, b, c, d, Math.min(1.0, t + 1.0E-5)).sub(cubic(a, b, c, d, Math.max(0.0, t - 1.0E-5))).horizontalUnit());
         samples.add(new RoadGeometry.Sample(p, tangent.left(), 0.0, width));
      }
   }

   private static void line(List<RoadGeometry.Sample> list, RoadGeometry.V a, RoadGeometry.V b, double width) {
      int count = (int)Math.ceil(a.sub(b).horizontalLength() / 1.5);
      RoadGeometry.V left = b.sub(a).horizontalUnit().left();

      for (int k = list.isEmpty() ? 0 : 1; k <= count; k++) {
         list.add(new RoadGeometry.Sample(a.add(b.sub(a).mul((double)k / (double)count)), left, 0.0, width));
      }
   }

   private static double sharedEnd(RoadGeometry.Mesh mesh, MultiInterchange.Grid other, boolean start) {
      List<RoadGeometry.Sample> samples = mesh.samples();
      int begin = start ? 0 : samples.size() - 1;
      int increment = start ? 1 : -1;
      double last = start ? 0.0 : mesh.length();

      for (int i = begin; i >= 0 && i < samples.size(); i += increment) {
         RoadGeometry.Sample s = samples.get(i);
         if (other.hit(s, 0.1) == null) {
            break;
         }

         last = s.distance();
      }

      return last;
   }

   private RoadGeometry.Mesh elevate(MultiInterchange.Route r) {
      double d0 = r.holdStart + 1.0;
      double d1 = r.holdEnd - 1.0;
      double enter = r.crossStart - 2.0;
      double leave = r.crossEnd + 2.0;
      double layer = this.center.y() + (double)r.layer * this.gap;
      if (d1 <= d0) {
         throw new IllegalArgumentException(name(r) + " 分合流区域重叠，请增加端点距离");
      } else {
         boolean crossing = r.crossEnd >= 0.0;
         if (crossing) {
            climb(name(r), layer - r.sourceHeight, enter - d0);
            climb(name(r), r.targetHeight - layer, d1 - leave);
         } else {
            climb(name(r), r.targetHeight - r.sourceHeight, d1 - d0);
         }

         ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

         for (RoadGeometry.Sample s : r.flat.samples()) {
            double d = s.distance();
            double y;
            if (d <= d0) {
               y = r.sourceHeight;
            } else if (d >= d1) {
               y = r.targetHeight;
            } else if (!crossing) {
               y = r.sourceHeight + (r.targetHeight - r.sourceHeight) * smooth((d - d0) / (d1 - d0));
            } else if (d < enter) {
               y = r.sourceHeight + (layer - r.sourceHeight) * smooth((d - d0) / (enter - d0));
            } else if (d > leave) {
               y = layer + (r.targetHeight - layer) * smooth((d - leave) / (d1 - leave));
            } else {
               y = layer;
            }

            samples.add(new RoadGeometry.Sample(new RoadGeometry.V(s.center().x(), y, s.center().z()), s.left(), 0.0, s.halfWidth()));
         }

         return RoadRibbon.mesh(samples, r.flat.settings());
      }
   }

   private static void climb(String name, double rise, double distance) {
      double need = Math.abs(rise) * 1.25 / 0.15;
      if (distance + 0.01 < need) {
         throw new IllegalArgumentException(name + " 升降段可用 " + (int)distance + " 格，需 " + (int)Math.ceil(need) + " 格");
      }
   }

   private static double smooth(double t) {
      t = Math.max(0.0, Math.min(1.0, t));
      if (t < 0.2) {
         return t * t / 0.32;
      } else {
         return t > 0.8 ? 1.0 - (1.0 - t) * (1.0 - t) / 0.32 : (t - 0.1) / 0.8;
      }
   }

   private static double minRadius(RoadGeometry.Mesh mesh) {
      double min = 1.0E12;
      List<RoadGeometry.Sample> s = mesh.samples();

      for (int k = 2; k < s.size(); k++) {
         RoadGeometry.V a = s.get(k - 2).center();
         RoadGeometry.V b = s.get(k - 1).center();
         RoadGeometry.V c = s.get(k).center();
         double ab = b.sub(a).horizontalLength();
         double bc = c.sub(b).horizontalLength();
         double ca = c.sub(a).horizontalLength();
         double twice = Math.abs(cross(b.sub(a), c.sub(a)));
         if (twice > 1.0E-8) {
            min = Math.min(min, ab * bc * ca / (2.0 * twice));
         }
      }

      return min;
   }

   private void verify(List<RoadGeometry.Mesh> elevated, double[][] sharedFrom, double[][] sharedTo) {
      ArrayList<MultiInterchange.Grid> grids = new ArrayList<>();
      this.mains.forEach(m -> grids.add(new MultiInterchange.Grid(m)));
      elevated.forEach(m -> grids.add(new MultiInterchange.Grid(m)));

      for (int i = 0; i < this.routes.size(); i++) {
         MultiInterchange.Route r = this.routes.get(i);
         RoadGeometry.Mesh mesh = elevated.get(i);

         for (int sampleIndex = 0; sampleIndex < mesh.samples().size(); sampleIndex++) {
            RoadGeometry.Sample sample = mesh.samples().get(sampleIndex);
            double d = r.flat.samples().get(sampleIndex).distance();

            for (int j = 0; j < grids.size(); j++) {
               if (j != i + 3) {
                  MultiInterchange.Hit hit = grids.get(j).hit(sample);
                  if (hit != null) {
                     double dy = Math.abs(sample.center().y() - hit.height());
                     boolean shared = j >= 3
                        ? d <= sharedFrom[i][j - 3] + 2.0 || d >= sharedTo[i][j - 3] - 2.0
                        : j == r.from / 2 && d <= r.holdStart + 2.0 || j == r.to / 2 && d >= r.holdEnd - 2.0;
                     if (!shared || !(dy < 0.05)) {
                        double thick = Math.max(mesh.settings().thickness(), grids.get(j).mesh.settings().thickness());
                        if (dy < this.options.clearance() + thick - 0.05) {
                           throw new IllegalArgumentException(name(r) + " 与其他道路净空不足，请增加端点距离");
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private MultiInterchange() {
      throw new AssertionError();
   }

   private static record Edge(long ax, long ay, long az, long bx, long by, long bz, long aw, long bw) {
      Edge(RoadGeometry.Sample a, RoadGeometry.Sample b) {
         this(
            q(a.center().x()),
            q(a.center().y()),
            q(a.center().z()),
            q(b.center().x()),
            q(b.center().y()),
            q(b.center().z()),
            q(a.halfWidth()),
            q(b.halfWidth())
         );
      }

      private static long q(double v) {
         return Math.round(v * 1000000.0);
      }
   }

   private static final class Grid {
      final RoadGeometry.Mesh mesh;
      final Map<Long, List<Integer>> cells = new HashMap<>();
      final int[] visited;
      int visit;

      Grid(RoadGeometry.Mesh m) {
         this.mesh = m;
         this.visited = new int[m.samples().size()];

         for (int i = 1; i < m.samples().size(); i++) {
            RoadGeometry.Sample a = m.samples().get(i - 1);
            RoadGeometry.Sample b = m.samples().get(i);
            double w = Math.max(a.halfWidth(), b.halfWidth()) + 0.4;
            int x0 = cell(Math.min(a.center().x(), b.center().x()) - w);
            int x1 = cell(Math.max(a.center().x(), b.center().x()) + w);
            int z0 = cell(Math.min(a.center().z(), b.center().z()) - w);
            int z1 = cell(Math.max(a.center().z(), b.center().z()) + w);

            for (int x = x0; x <= x1; x++) {
               for (int z = z0; z <= z1; z++) {
                  this.cells.computeIfAbsent(key(x, z), v -> new ArrayList<>()).add(i);
               }
            }
         }
      }

      static int cell(double v) {
         return (int)Math.floor(v / 16.0);
      }

      static long key(int x, int z) {
         return (long)x << 32 ^ (long)z & 4294967295L;
      }

      MultiInterchange.Hit hit(RoadGeometry.Sample s) {
         return this.hit(s, 0.0);
      }

      MultiInterchange.Hit hit(RoadGeometry.Sample s, double contact) {
         MultiInterchange.Hit best = null;
         double width = s.halfWidth() + 0.4;
         int stamp = ++this.visit;

         for (int x = cell(s.center().x() - width); x <= cell(s.center().x() + width); x++) {
            for (int z = cell(s.center().z() - width); z <= cell(s.center().z() + width); z++) {
               for (int i : this.cells.getOrDefault(key(x, z), List.of())) {
                  if (this.visited[i] != stamp) {
                     this.visited[i] = stamp;
                     RoadGeometry.Sample a = this.mesh.samples().get(i - 1);
                     RoadGeometry.Sample b = this.mesh.samples().get(i);
                     RoadGeometry.V d = b.center().sub(a.center());
                     double ll = d.x() * d.x() + d.z() * d.z();
                     if (!(ll < 1.0E-10)) {
                        RoadGeometry.V q = s.center().sub(a.center());
                        double t = Math.max(0.0, Math.min(1.0, (q.x() * d.x() + q.z() * d.z()) / ll));
                        RoadGeometry.V p = a.center().add(d.mul(t));
                        double distance = p.sub(s.center()).horizontalLength();
                        double separation = distance - (s.halfWidth() + a.halfWidth() * (1.0 - t) + b.halfWidth() * t - 0.05);
                        if (separation < contact && (best == null || separation < best.gap())) {
                           best = new MultiInterchange.Hit(a.distance() + (b.distance() - a.distance()) * t, separation, p.y(), s.left().dot(a.left()));
                        }
                     }
                  }
               }
            }
         }

         return best;
      }
   }

   private static record Hit(double distance, double gap, double height, double dot) {
   }

   private static final class Route {
      final int from;
      final int to;
      final RoadGeometry.Mesh flat;
      final double sourceHeight;
      final double targetHeight;
      double holdStart;
      double holdEnd;
      double crossStart = Double.POSITIVE_INFINITY;
      double crossEnd = -1.0;
      final Set<Integer> mainCross = new HashSet<>();
      final Set<Integer> conflicts = new HashSet<>();
      int layer = -1;

      Route(int from, int to, RoadGeometry.Mesh flat, double h0, double h1) {
         this.from = from;
         this.to = to;
         this.flat = flat;
         this.sourceHeight = h0;
         this.targetHeight = h1;
         this.holdEnd = flat.length();
      }
   }
}
