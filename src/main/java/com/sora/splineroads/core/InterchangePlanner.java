package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

public final class InterchangePlanner {
   private static final RoadGeometry.V[] ARMS = new RoadGeometry.V[]{
      new RoadGeometry.V(-1.0, 0.0, 0.0), new RoadGeometry.V(0.0, 0.0, -1.0), new RoadGeometry.V(1.0, 0.0, 0.0), new RoadGeometry.V(0.0, 0.0, 1.0)
   };
   private final RoadGeometry.Node[] nodes;
   private final RoadGeometry.Settings[] main;
   private final InterchangePlanner.Options options;
   private final RoadGeometry.V center;
   private final RoadGeometry.V u;
   private final RoadGeometry.V v;
   private final double[] lengths = new double[4];
   private final int traffic;
   private final double base;
   private final double step;
   private final double loopRadius;
   private final double[] heights;
   private final List<InterchangePlanner.Leg> roads = new ArrayList<>();
   private final List<InterchangePlanner.Route> routes = new ArrayList<>();
   private final List<Double> serviceCenters = new ArrayList<>();
   private double directCurve = 0.4;
   private final Map<RoadGeometry.Mesh, Map<RoadGeometry.Mesh, double[]>> mouthSides = new IdentityHashMap<>();
   private double cornerScale = 1.0;
   private final UnaryOperator<RoadGeometry.V> conformance;
   private final Set<String> emittedPorts = new HashSet<>();

   public static List<InterchangePlanner.Preset> presets(int anchors) {
      if (anchors == 5) {
         return List.of(InterchangePlanner.Preset.DIRECTIONAL_FIVE);
      } else if (anchors == 6) {
         return List.of(InterchangePlanner.Preset.DIRECTIONAL_SIX);
      } else if (anchors != 3 && anchors != 4) {
         throw new IllegalArgumentException("请选择三至六个端点");
      } else {
         return Arrays.stream(InterchangePlanner.Preset.values())
            .filter(
               p -> p.supported()
                     && p != InterchangePlanner.Preset.DIRECTIONAL_FIVE
                     && p != InterchangePlanner.Preset.DIRECTIONAL_SIX
                     && p.three == (anchors == 3)
            )
            .toList();
      }
   }

   public static RoadGeometry.Node[] expand(RoadGeometry.Node[] anchors, InterchangePlanner.Options options) {
      if (anchors.length == 4) {
         return (RoadGeometry.Node[])anchors.clone();
      } else if (anchors.length != 3) {
         throw new IllegalArgumentException("请选择 A/B 与 C，或 A/B 与 C/D");
      } else if (!options.preset.three) {
         throw new IllegalArgumentException("三个端点只能选择三向立交");
      } else {
         RoadGeometry.V a = anchors[0].position();
         RoadGeometry.V b = anchors[1].position();
         RoadGeometry.V c = anchors[2].position();
         RoadGeometry.V ab = b.sub(a).horizontalUnit();
         RoadGeometry.V foot = a.add(ab.mul(c.sub(a).dot(ab)));
         RoadGeometry.V d = new RoadGeometry.V(2.0 * foot.x() - c.x(), c.y(), 2.0 * foot.z() - c.z());
         return new RoadGeometry.Node[]{anchors[0], anchors[1], anchors[2], new RoadGeometry.Node(d, anchors[2].yaw(), 0.0)};
      }
   }

   private InterchangePlanner(
      RoadGeometry.Node[] nodes,
      RoadGeometry.Settings a,
      RoadGeometry.Settings b,
      InterchangePlanner.Options options,
      UnaryOperator<RoadGeometry.V> conformance
   ) {
      this.conformance = conformance;
      if (!options.preset.supported()) {
         throw new IllegalArgumentException("此立交预设已移除，请改选其他布局");
      } else {
         this.nodes = (RoadGeometry.Node[])nodes.clone();
         this.main = new RoadGeometry.Settings[]{a, b};
         this.options = options;

         for (RoadGeometry.Settings s : this.main) {
            s.validate();
         }

         for (RoadGeometry.Settings s : this.main) {
            if (s.style().ramp() || !RoadProfile.catalog(s).twoWay()) {
               throw new IllegalArgumentException("预设立交的两条主路需要双向道路");
            }
         }

         if (options.preset == InterchangePlanner.Preset.DIAMOND || options.preset == InterchangePlanner.Preset.SPUI) {
            if (RoadProfile.catalog(a).type() == RoadProfile.Type.HIGHWAY && RoadProfile.catalog(b).type() == RoadProfile.Type.HIGHWAY) {
               throw new IllegalArgumentException("AB、CD 均为高速，不能使用菱形或单点菱形立交");
            }

            if (RoadProfile.catalog(this.main[options.junctionAxis]).type() == RoadProfile.Type.HIGHWAY) {
               throw new IllegalArgumentException("菱形／单点菱形的路口只能设置在非高速主路上，请切换路口主路");
            }
         }

         RoadGeometry.V ab = nodes[1].position().sub(nodes[0].position());
         RoadGeometry.V cd = nodes[3].position().sub(nodes[2].position());
         if (!(ab.horizontalLength() < 100.0)
            && !(cd.horizontalLength() < 70.0)
            && !(ab.horizontalLength() > (double)(options.preset == InterchangePlanner.Preset.DOUBLE_TRUMPET ? 4096 : 2048) + 1.0E-6)
            && !(cd.horizontalLength() > (double)(options.preset != InterchangePlanner.Preset.DOUBLE_TRUMPET && !options.preset.three ? 2048 : 4096) + 1.0E-6)) {
            this.u = ab.horizontalUnit();
            this.v = cd.horizontalUnit();
            double det = cross(this.u, this.v);
            if (Math.abs(det) < 0.9) {
               throw new IllegalArgumentException("预设立交需要两条约垂直的主路，夹角需为 65°–115°");
            } else {
               RoadGeometry.V delta = nodes[2].position().sub(nodes[0].position());
               double along = cross(delta, this.v) / det;
               this.center = nodes[0].position().add(this.u.mul(along));
               this.lengths[0] = along;
               this.lengths[2] = ab.horizontalLength() - along;
               double cv = this.center.sub(nodes[2].position()).dot(this.v);
               this.lengths[1] = cv;
               this.lengths[3] = cd.horizontalLength() - cv;
               if (!(Math.min(this.lengths[0], this.lengths[2]) < 45.0) && !(this.lengths[1] < 45.0) && (options.preset.three || !(this.lengths[3] < 45.0))) {
                  if (options.preset.three) {
                     this.lengths[3] = Math.max(this.lengths[1], Math.min(this.lengths[0], this.lengths[2]));
                  }

                  this.traffic = (options.leftTraffic ? -1 : 1) * (det < 0.0 ? -1 : 1);
                  InterchangePlanner.Levels levels = levels(nodes, a, b, options);
                  this.heights = new double[]{levels.first(), levels.second()};
                  this.base = Math.min(this.heights[0], this.heights[1]);
                  this.step = layeredDifference(a, b, options) / (double)options.preset.heightSlots;
                  double gap = Math.abs(this.heights[0] - this.heights[1]);
                  double sweep = Math.PI + Math.asin(Math.min(1.0, Math.abs(det)));
                  this.loopRadius = Math.max(
                     options.radius,
                     options.preset != InterchangePlanner.Preset.CLOVERLEAF && options.preset != InterchangePlanner.Preset.HYBRID
                        ? Math.max(gap, options.preset.three ? layeredDifference(a, b, options) : 0.0) * 2.5
                        : (gap * 1.25 / 0.15 + 42.0) / sweep + 2.0
                  );
               } else {
                  throw new IllegalArgumentException("两条主路需要在端点之间相交，交点离各有效方向端点至少 45 格");
               }
            }
         } else {
            throw new IllegalArgumentException(
               options.preset == InterchangePlanner.Preset.DOUBLE_TRUMPET ? "双喇叭两轴总长上限 4096 格" : "AB 总长需为 100–2048 格，四向 CD 总长 70–2048 格，三向支路不超过 2048 格"
            );
         }
      }
   }

   public static InterchangePlanner.Plan plan(RoadGeometry.Node[] nodes, RoadGeometry.Settings a, RoadGeometry.Settings b, InterchangePlanner.Options options) {
      return plan(nodes, a, b, options, null);
   }

   public static InterchangePlanner.Plan plan(
      RoadGeometry.Node[] nodes,
      RoadGeometry.Settings a,
      RoadGeometry.Settings b,
      InterchangePlanner.Options options,
      UnaryOperator<RoadGeometry.V> conformance
   ) {
      RoadPlanningBudget.check();
      if (nodes.length >= 5) {
         return MultiInterchange.plan(nodes, new RoadGeometry.Settings[]{a, b, b}, options);
      } else if (options.preset().multi()) {
         throw new IllegalArgumentException("五、六向布局需要对应数量的端点");
      } else if (options.adjustEndpoints) {
         return InterchangeFit.fit(nodes, a, b, options, conformance);
      } else if (options.preset == InterchangePlanner.Preset.DOUBLE_TRUMPET) {
         return DoubleTrumpetPlanner.plan(nodes, a, b, options);
      } else {
         IllegalArgumentException first = null;
         double[] curves = options.preset != InterchangePlanner.Preset.DIAMOND
               && options.preset != InterchangePlanner.Preset.SPUI
               && options.preset != InterchangePlanner.Preset.ROUNDABOUT
            ? (
               options.preset == InterchangePlanner.Preset.CLOVERLEAF
                  ? new double[]{0.4, 0.52, 0.56, 0.6, 0.36, 0.32, 0.28, 0.24, 0.2, 0.16, 0.12, 0.64, 0.68, 0.72}
                  : new double[]{0.4, 0.36, 0.32, 0.28, 0.24, 0.2, 0.16, 0.52, 0.6}
            )
            : new double[]{0.4};
         double[] cornerScales = conformance != null && options.preset == InterchangePlanner.Preset.STACK
            ? new double[]{1.0, 1.25, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0}
            : new double[]{1.0};

         for (double corner : cornerScales) {
            for (double curve : curves) {
               RoadPlanningBudget.check();
               InterchangePlanner planner = new InterchangePlanner(expand(nodes, options), a, b, options, conformance);
               planner.directCurve = curve;
               planner.cornerScale = corner;

               try {
                  InterchangePlanner.Plan result = planner.build();
                  if (conformance != null) {
                     CurvedRoadPlans.validate(result.legs, options);
                  }

                  return new InterchangePlanner.Plan(
                     result.legs, result.movements, result.minRadius, result.highest, result.center, List.copyOf(Arrays.asList(nodes))
                  );
               } catch (IllegalArgumentException var20) {
                  if (Boolean.getBoolean("sr.curveDebug")) {
                     System.err.println(options.preset + " curve=" + curve + " " + var20.getMessage());
                  }

                  if (first == null) {
                     first = var20;
                  }

                  if (var20 instanceof InterchangePlanner.CornerRadiusFailure) {
                     break;
                  }
               }
            }
         }

         throw first;
      }
   }

   private RoadGeometry.V world(RoadGeometry.V p) {
      return this.center.add(this.u.mul(p.x())).add(this.v.mul(p.z())).add(new RoadGeometry.V(0.0, p.y() - this.center.y(), 0.0));
   }

   private RoadGeometry.V local(RoadGeometry.V p) {
      RoadGeometry.V q = p.sub(this.center);
      double d = cross(this.u, this.v);
      return new RoadGeometry.V(cross(q, this.v) / d, p.y(), cross(this.u, q) / d);
   }

   private RoadGeometry.V direction(RoadGeometry.V d) {
      return this.u.mul(d.x()).add(this.v.mul(d.z())).horizontalUnit();
   }

   public static InterchangePlanner.Levels levels(
      RoadGeometry.Node[] nodes, RoadGeometry.Settings a, RoadGeometry.Settings b, InterchangePlanner.Options options
   ) {
      nodes = expand(nodes, options);

      for (RoadGeometry.Node node : nodes) {
         if (!RoadGeometry.finite(node.position().x(), node.position().y(), node.position().z(), node.grade(), node.yaw())) {
            throw new IllegalArgumentException("立交端点数值无效");
         }
      }

      double y1 = (nodes[0].position().y() + nodes[1].position().y()) / 2.0;
      double y2 = (nodes[2].position().y() + nodes[3].position().y()) / 2.0;

      for (int i = 0; i < 4; i += 2) {
         if (Math.abs(nodes[i].position().y() - nodes[i + 1].position().y()) > 1.0E-6) {
            throw new IllegalArgumentException("自动立交要求 AB、CD 各自等高；请先调平主路端点");
         }
      }

      double required = minimumDifference(a, b, options);
      double gap = Math.abs(y1 - y2);
      if (gap + 1.0E-6 < required) {
         String upper = y1 > y2 ? "AB" : "CD";
         throw new IllegalArgumentException(
            String.format(Locale.ROOT, "%s：当前高差 %.1f 格，最低 %.1f 格；请将 %s 再抬高 %.1f 格", options.preset.label, gap, required, upper, required - gap)
         );
      } else {
         return new InterchangePlanner.Levels(y1, y2, gap, required);
      }
   }

   public static double minimumDifference(RoadGeometry.Settings a, RoadGeometry.Settings b, InterchangePlanner.Options options) {
      if (options.preset.three) {
         return 0.0;
      } else {
         return !options.allowAbove && options.preset != InterchangePlanner.Preset.DOUBLE_TRUMPET
            ? layeredDifference(a, b, options)
            : Math.ceil(options.clearance + Math.max(1.0, Math.max(a.thickness(), b.thickness())) + 0.3);
      }
   }

   public static double layeredDifference(RoadGeometry.Settings a, RoadGeometry.Settings b, InterchangePlanner.Options options) {
      int slots = options.preset == InterchangePlanner.Preset.CLOVERLEAF ? 1 : options.preset.heightSlots;
      return Math.ceil(
         (double)slots * (options.clearance + Math.max(1.0, Math.max(a.thickness(), b.thickness())) + 0.3)
            + (double)(options.preset == InterchangePlanner.Preset.SPUI ? 4 : 0)
      );
   }

   private double height(int axis, double coordinate) {
      return this.heights[axis];
   }

   private RoadGeometry.Settings setting(int axis) {
      RoadGeometry.Settings s = this.main[axis];
      return s.options(s.options().traffic(this.options.leftTraffic).route(s.options().routing().fit(false)).ends(RoadTransitions.Ends.NONE))
         .structure(
            s.structure() != RoadGeometry.Structure.BRIDGE && s.structure() != RoadGeometry.Structure.TUNNEL ? RoadGeometry.Structure.AUTO : s.structure()
         );
   }

   private InterchangePlanner.Plan build() {
      for (int axis = 0; axis < 2; axis++) {
         RoadPlanningBudget.check();
         double lo = -this.lengths[axis == 0 ? 0 : 1];
         double hi = axis == 1 && this.options.preset.three ? -this.terminalStation() : this.lengths[axis == 0 ? 2 : 3];
         RoadGeometry.Settings settings = this.setting(axis);
         if (axis == 1 && this.options.preset.three) {
            settings = RoadTransitions.ends(settings, null, this.terminalSection());
            settings = settings.options(settings.options().ends(new RoadTransitions.Ends(null, this.terminalSection(), true)));
         }

         List<RoadGeometry.Sample> samples = new ArrayList<>();
         int count = (int)Math.ceil((hi - lo) / 0.5);

         for (int i = 0; i <= count; i++) {
            if ((i & 63) == 0) {
               RoadPlanningBudget.check();
            }

            double x = lo + (hi - lo) * (double)i / (double)count;
            RoadGeometry.V p = this.world(axis == 0 ? new RoadGeometry.V(x, this.height(axis, x), 0.0) : new RoadGeometry.V(0.0, this.height(axis, x), x));
            RoadGeometry.V d = axis == 0 ? this.u : this.v;
            samples.add(
               new RoadGeometry.Sample(
                  p,
                  d.left(),
                  0.0,
                  axis == 1 && this.options.preset.three
                     ? RoadTransitions.width(settings, x - lo, hi - lo) / 2.0
                     : this.main[axis].width() / 2.0 + this.padding(axis)
               )
            );
         }

         this.roads.add(new InterchangePlanner.Leg("主路 " + (axis + 1), axis == 0 ? 0 : 1, axis == 0 ? 2 : 3, RoadRibbon.mesh(samples, settings)));
      }

      if (this.options.preset == InterchangePlanner.Preset.ROUNDABOUT) {
         return this.roundabout();
      } else if (this.servicePreset()) {
         return this.service();
      } else {
         if (this.collectorLoops()) {
            this.addCollectors();
         }

         int active = this.options.preset.three ? 3 : 4;

         for (int from = 0; from < active; from++) {
            for (int to = 0; to < active; to++) {
               RoadPlanningBudget.check();
               if (to != from && Math.abs(from - to) != 2) {
                  boolean right = (to - from + 4) % 4 == (this.traffic > 0 ? 3 : 1);
                  boolean leaf = !right
                     && (
                        this.options.preset == InterchangePlanner.Preset.CLOVERLEAF
                           || this.options.preset == InterchangePlanner.Preset.HYBRID && (this.options.loopMask & 1 << from) != 0
                           || this.options.preset == InterchangePlanner.Preset.TRUMPET && from == 1
                     );
                  this.routes.add(this.route(from, to, right, leaf));
               }
            }
         }

         if (this.conformance != null) {
            for (int i = 0; i < this.roads.size(); i++) {
               RoadPlanningBudget.check();
               InterchangePlanner.Leg leg = this.roads.get(i);

               for (RoadGeometry.Sample sample : leg.mesh().samples()) {
                  this.checkBounds(sample.center());
               }

               this.roads.set(i, new InterchangePlanner.Leg(leg.name(), leg.from(), leg.to(), CurvedRoadPlans.map(leg.mesh(), this.conformance)));
            }

            for (InterchangePlanner.Route route : this.routes) {
               RoadPlanningBudget.check();

               for (RoadGeometry.V p : route.points) {
                  this.checkBounds(p);
               }

               route.points = route.points.stream().map(px -> {
                  RoadPlanningBudget.check();
                  return this.conformance.apply(px);
               }).toList();
            }

            for (InterchangePlanner.Route route : this.routes) {
               RoadPlanningBudget.check();
               double radius = RoadRibbon.minRadius(this.horizontal(route));
               if (radius + 0.1 < this.options.radius) {
                  String message = String.format(Locale.ROOT, "%s 实际转弯半径 %.1f 格，要求 %.1f 格", route.name, radius, this.options.radius);
                  if (this.options.preset == InterchangePlanner.Preset.STACK && route.priority == 2) {
                     throw new InterchangePlanner.CornerRadiusFailure(message);
                  }

                  throw new IllegalArgumentException(message);
               }
            }
         }

         this.routes.sort(Comparator.comparing(rx -> rx.priority));

         for (InterchangePlanner.Route r : this.routes) {
            this.extendThroat(r);
         }

         List<RoadGeometry.Mesh> flat = this.routes.stream().map(this::horizontal).toList();
         if (this.collectorLoops()) {
            for (int i = 0; i < this.routes.size(); i++) {
               if (this.routes.get(i).priority == 0) {
                  for (int j = 0; j < this.routes.size(); j++) {
                     if (this.routes.get(j).priority == 1 && !CloverleafEnvelope.clear(flat.get(i), flat.get(j))) {
                        throw new IllegalArgumentException("苜蓿右转匝道必须绕过左转圆环外侧；请扩大范围或减小回环半径");
                     }
                  }
               }
            }
         }

         List<RoadGeometry.Mesh> mains = this.roads.stream().map(InterchangePlanner.Leg::mesh).toList();
         int mainCount = mains.size();

         for (int ix = 0; ix < this.routes.size(); ix++) {
            RoadPlanningBudget.check();
            InterchangePlanner.Route r = this.routes.get(ix);
            int var10000;
            if (r.priority < 2) {
               var10000 = 1;
            } else {
               switch (this.options.preset) {
                  case TURBINE:
                     var10000 = r.from + 1;
                     break;
                  case STACK:
                     var10000 = r.from % 2 + 1;
                     break;
                  case HYBRID:
                     var10000 = 2;
                     break;
                  case TRUMPET:
                  case DIRECTIONAL_T:
                  case Y:
                     var10000 = r.from == 1 ? 2 : 1;
                     break;
                  case DIAMOND:
                  case PARCLO:
                  default:
                     var10000 = 1;
                     break;
                  case DOUBLE_TRUMPET:
                     var10000 = r.name.startsWith("C") ? 1 : 2;
               }
            }

            int slot = var10000;
            if (this.options.preset == InterchangePlanner.Preset.HYBRID && r.priority > 0) {
               slot = this.heights[r.from % 2] > this.heights[r.to % 2] ? 1 : 2;
            }

            if ((this.options.preset == InterchangePlanner.Preset.TRUMPET || this.options.preset == InterchangePlanner.Preset.DOUBLE_TRUMPET)
               && r.priority == 1) {
               slot = 2;
            }

            r.layer = this.options.preset == InterchangePlanner.Preset.STACK && r.priority == 2
               ? this.base + Math.abs(this.heights[0] - this.heights[1]) * (double)(this.heights[r.from % 2] < this.heights[r.to % 2] ? 1 : 2) / 3.0
               : (
                  !this.options.allowAbove
                        && (
                           !this.options.preset.three
                              || !(Math.abs(this.heights[0] - this.heights[1]) + 1.0E-6 < layeredDifference(this.main[0], this.main[1], this.options))
                        )
                     ? this.base + (double)slot * this.step
                     : Math.max(this.heights[0], this.heights[1]) + (double)slot * this.step
               );
            List<RoadGeometry.Mesh> others = new ArrayList<>(mains);
            others.addAll(flat);

            for (int k = 0; k < others.size(); k++) {
               RoadPlanningBudget.check();
               if (k != ix + mainCount
                  && (
                     r.priority >= 2
                        || k < mainCount
                        || r.priority == 1 && this.options.preset == InterchangePlanner.Preset.TRUMPET && this.routes.get(k - mainCount).priority >= 2
                  )) {
                  RoadGeometry.Mesh other = others.get(k);

                  for (int jx = 0; jx < flat.get(ix).samples().size(); jx += 2) {
                     if ((jx & 63) == 0) {
                        RoadPlanningBudget.check();
                     }

                     RoadGeometry.Sample p = flat.get(ix).samples().get(jx);
                     RoadQueries.Projection q = this.overlap(other, p);
                     if (q != null
                        && !this.shared(r, jx, p, k < mainCount ? null : this.routes.get(k - mainCount), k, other, q)
                        && jx > r.leadEnd
                        && jx < r.tailStart) {
                        r.plateauStart = r.plateauStart < 0 ? jx : Math.min(r.plateauStart, jx);
                        r.plateauEnd = Math.max(r.plateauEnd, jx);
                     }
                  }
               }
            }
         }

         List<RoadGeometry.Mesh> ramps = this.elevateCrossings(flat);

         for (RoadGeometry.Mesh mesh : ramps) {
            RoadRibbon.checkSelfIntersections(mesh, this.options.clearance);
         }

         this.validateExactClearance(ramps);

         for (int ix = 0; ix < ramps.size(); ix++) {
            InterchangePlanner.Route rx = this.routes.get(ix);
            this.addWithSharedPorts(rx, ramps.get(ix));
         }

         double min = ramps.stream().mapToDouble(RoadRibbon::minRadius).min().orElse(0.0);
         double highest = this.roads.stream().mapToDouble(l -> l.mesh.max().y()).max().orElse(this.base);
         if (this.conformance == null) {
            for (InterchangePlanner.Leg road : this.roads) {
               for (RoadGeometry.Sample p : road.mesh.samples()) {
                  this.checkBounds(p.center());
               }
            }
         }

         for (InterchangePlanner.Leg road : this.roads) {
            RoadGrades.validate(road.mesh());
         }

         return new InterchangePlanner.Plan(List.copyOf(this.roads), ramps.size(), min, highest, this.center);
      }
   }

   private void checkBounds(RoadGeometry.V point) {
      RoadGeometry.V q = this.local(point);
      double pad = Math.max(this.main[0].width(), this.main[1].width());
      if (q.x() < -this.lengths[0] - pad || q.x() > this.lengths[2] + pad || q.z() < -this.lengths[1] - pad || q.z() > this.lengths[3] + pad) {
         throw new IllegalArgumentException("该预设超出选定范围，请减小环绕半径或扩大范围");
      }
   }

   public double transitionLength() {
      return Math.max(80.0, Math.max(this.options.transition, this.options.lanes == 1 ? 80.0 : 108.0));
   }

   private double terminalStation() {
      double shortest = Math.min(this.lengths[0], Math.min(this.lengths[1], this.lengths[2]));
      return Math.min(this.lengths[1] - Math.max(this.transitionLength(), 100.0), shortest * 0.94 - this.transitionLength());
   }

   private RoadTransitions.Section terminalSection() {
      RoadTransitions.Section section = new RoadTransitions.Section(
         this.options.lanes() == 1 ? RoadGeometry.Style.O2_RAIL : RoadGeometry.Style.O4_RAIL,
         this.options.width() * 2.0 + 1.0,
         false,
         false,
         false,
         this.main[1].options().outerRail()
      );
      if (this.options.lanes() == 2) {
         RoadProfile.Layout layout = section.layout(this.options.leftTraffic());
         double center = this.options.width() / 2.0 + 0.5;
         section = section.port(new RoadTransitions.Port(layout.motorMin(), layout.motorMax(), layout.median(), List.of(-center, center), 0.0, 0.0));
      }

      return section;
   }

   private boolean collectorLoops() {
      return this.options.preset == InterchangePlanner.Preset.CLOVERLEAF || this.options.preset == InterchangePlanner.Preset.HYBRID;
   }

   private RoadGeometry.V[] leafPorts(int from, int to) {
      RoadGeometry.V da = this.direction(ARMS[from].mul(-1.0));
      RoadGeometry.V db = this.direction(ARMS[to]);
      double hand = this.options.leftTraffic ? -1.0 : 1.0;
      RoadGeometry.V a = this.world(new RoadGeometry.V(0.0, 0.0, 0.0)).add(da.left().mul(hand * (this.auxiliaryOffset(from % 2) + this.loopRadius)));
      RoadGeometry.V b = this.world(new RoadGeometry.V(0.0, 0.0, 0.0)).add(db.left().mul(hand * (this.auxiliaryOffset(to % 2) + this.loopRadius)));
      RoadGeometry.V delta = b.sub(a);
      double det = da.x() * db.z() - da.z() * db.x();
      RoadGeometry.V circle = a.add(da.mul((delta.x() * db.z() - delta.z() * db.x()) / det));
      return new RoadGeometry.V[]{circle.sub(da.left().mul(hand * this.loopRadius)), circle.sub(db.left().mul(hand * this.loopRadius))};
   }

   private void addCollectors() {
      for (int arm = 0; arm < 4; arm++) {
         RoadPlanningBudget.check();
         boolean departure = this.options.preset == InterchangePlanner.Preset.CLOVERLEAF || (this.options.loopMask & 1 << arm) != 0;
         int arrivingFrom = (arm + (this.traffic > 0 ? 1 : 3)) % 4;
         boolean arrival = this.options.preset == InterchangePlanner.Preset.CLOVERLEAF || (this.options.loopMask & 1 << arrivingFrom) != 0;
         if (departure || arrival) {
            int axis = arm % 2;
            double offset = this.auxiliaryOffset(axis);
            RoadGeometry.V flow = this.direction(ARMS[arm].mul(-1.0));
            RoadGeometry.V side = flow.left().mul(this.options.leftTraffic ? -1.0 : 1.0);
            RoadGeometry.V b = this.leafPorts(arrivingFrom, (arm + 2) % 4)[1];
            RoadGeometry.V c = this.leafPorts(arm, (arm + (this.traffic > 0 ? 1 : 3)) % 4)[0];
            List<RoadGeometry.V> path = new ArrayList<>();
            List<Double> widths = new ArrayList<>();
            if (arrival && departure) {
               int count = (int)Math.ceil(c.sub(b).horizontalLength() / 0.4);

               for (int i = 0; i <= count; i++) {
                  if ((i & 63) == 0) {
                     RoadPlanningBudget.check();
                  }

                  path.add(b.add(c.sub(b).mul((double)i / (double)count)));
                  widths.add(this.options.width());
               }
            } else {
               double lead = this.transitionLength();
               double shift = offset - this.attachment(axis);
               RoadGeometry.V first = departure ? c.sub(flow.mul(lead)).sub(side.mul(shift)) : b;
               RoadGeometry.V last = departure ? c : b.add(flow.mul(lead)).sub(side.mul(shift));
               this.appendLead(
                  axis,
                  path,
                  widths,
                  first,
                  last,
                  flow,
                  lead,
                  departure ? this.portWidth(axis) : this.options.width(),
                  departure ? this.options.width() : this.portWidth(axis),
                  false
               );
            }

            List<RoadGeometry.Sample> samples = new ArrayList<>();

            for (int i = 0; i < path.size(); i++) {
               if ((i & 63) == 0) {
                  RoadPlanningBudget.check();
               }

               RoadGeometry.V p = path.get(i);
               RoadGeometry.V tangent = path.get(Math.min(i + 1, path.size() - 1)).sub(path.get(Math.max(0, i - 1))).horizontalUnit();
               samples.add(new RoadGeometry.Sample(new RoadGeometry.V(p.x(), this.heights[axis], p.z()), tangent.left(), 0.0, widths.get(i) / 2.0));
            }

            this.roads.add(new InterchangePlanner.Leg("连续集散道 " + arm, arm, (arm + 2) % 4, RoadRibbon.mesh(samples, this.rampSettings())));
         }
      }
   }

   private void addWithSharedPorts(InterchangePlanner.Route r, RoadGeometry.Mesh mesh) {
      boolean sharedStart = r.leadCut > 0
         && this.routes
            .stream()
            .anyMatch(
               o -> o != r
                     && o.from == r.from
                     && o.points.get(0).distance(r.points.get(0)) < 0.01
                     && o.points.get(o.leadCut).distance(r.points.get(r.leadCut)) < 0.01
                     && o.points.get(o.leadCut * 4 / 5).distance(r.points.get(r.leadCut * 4 / 5)) < 0.01
            );
      boolean sharedEnd = r.tailCut < mesh.samples().size() - 1
         && this.routes
            .stream()
            .anyMatch(
               o -> o != r
                     && o.to == r.to
                     && o.points.get(o.points.size() - 1).distance(r.points.get(r.points.size() - 1)) < 0.01
                     && o.points.get(o.tailCut).distance(r.points.get(r.tailCut)) < 0.01
                     && o.points.get((o.tailCut * 4 + o.points.size() - 1) / 5).distance(r.points.get((r.tailCut * 4 + r.points.size() - 1) / 5)) < 0.01
            );
      if (sharedStart && this.emittedPorts.add("out:" + r.from)) {
         this.roads.add(new InterchangePlanner.Leg("共用汇出 " + r.from, r.from, -1, RoadRibbon.mesh(mesh.samples().subList(0, r.leadCut + 1), mesh.settings())));
      }

      if (sharedEnd && this.emittedPorts.add("in:" + r.to)) {
         this.roads
            .add(
               new InterchangePlanner.Leg("共用汇入 " + r.to, -1, r.to, RoadRibbon.mesh(mesh.samples().subList(r.tailCut, mesh.samples().size()), mesh.settings()))
            );
      }

      int first = sharedStart ? r.leadCut : 0;
      int last = sharedEnd ? r.tailCut : mesh.samples().size() - 1;
      this.roads.add(new InterchangePlanner.Leg(r.name, r.from, r.to, RoadRibbon.mesh(mesh.samples().subList(first, last + 1), mesh.settings())));
   }

   private boolean servicePreset() {
      return switch (this.options.preset) {
         case DIAMOND, PARCLO, ROUNDABOUT, SPUI -> true;
         default -> false;
      };
   }

   private RoadGeometry.V serviceFrame(RoadGeometry.V p) {
      return this.options.junctionAxis() == 1 ? p : new RoadGeometry.V(-p.z(), p.y(), p.x());
   }

   private RoadGeometry.V serviceWorld(RoadGeometry.V p) {
      return this.world(this.serviceFrame(p));
   }

   private RoadGeometry.V serviceDirection(RoadGeometry.V p) {
      return this.direction(this.serviceFrame(p));
   }

   private static String armName(int arm) {
      return new String[]{"A", "C", "B", "D"}[arm];
   }

   private InterchangePlanner.Plan service() {
      boolean single = this.options.preset == InterchangePlanner.Preset.SPUI;
      int cross = this.options.junctionAxis();
      int host = 1 - cross;
      double width = this.options.width();
      double station = Math.max(this.options.radius * 2.0, this.main[host].width() / 2.0 + width + 26.0);
      if (this.options.preset == InterchangePlanner.Preset.PARCLO) {
         station = this.loopRadius * 1.5 + this.main[host].width() * 2.0;
      }

      double corner = Math.max(24.0, width * 2.0);
      if (!single && station + this.main[cross].width() > Math.min(this.lengths[cross], this.lengths[cross + 2]) - 16.0) {
         throw new IllegalArgumentException("菱形的两个匝道端部路口放不下，请扩大 " + (cross == 0 ? "A/B" : "C/D") + " 范围");
      } else {
         double singleStation = this.main[host].width() / 2.0 + width / 2.0 + 3.0;
         if (single) {
            this.intersection(0.0, corner, singleStation + width / 2.0);
         } else {
            for (int side : new int[]{-1, 1}) {
               this.intersection((double)side * station, corner, width / 2.0);
            }
         }

         for (int arm : new int[]{0, 2}) {
            for (boolean exit : new boolean[]{true, false}) {
               double orientation = arm == 0 ? 1.0 : -1.0;
               double side = (double)this.traffic * orientation * (double)(exit ? 1 : -1);
               int actualArm = (arm + host) % 4;
               double lead = this.transitionLength();
               double source = this.lengths[actualArm] - 8.0;
               double outer = this.detachedOffset(host);
               RoadGeometry.V a = this.serviceWorld(new RoadGeometry.V(-orientation * source, this.heights[host], side * this.attachment(host)));
               RoadGeometry.V b = this.serviceWorld(new RoadGeometry.V(-orientation * (source - lead), this.heights[host], side * outer));
               RoadGeometry.V forward = this.serviceDirection(new RoadGeometry.V(orientation, 0.0, 0.0));
               List<RoadGeometry.V> points = new ArrayList<>();
               List<Double> widths = new ArrayList<>();
               this.appendLead(host, points, widths, a, b, forward, lead, this.portWidth(host), width, false);
               int hold = points.size() - 1;
               double targetX = this.main[cross].width() / 2.0 + corner + (single ? singleStation : 0.0);
               RoadGeometry.V target = this.serviceWorld(
                  new RoadGeometry.V(-orientation * targetX, this.heights[cross], side * (single ? singleStation : station))
               );
               if (source - lead < targetX + 24.0) {
                  throw new IllegalArgumentException("四条菱形匝道需要独立的减速段和爬坡段，请延长 " + (host == 0 ? "A/B" : "C/D") + " 范围");
               }

               List<InterchangePlanner.Knot> approach = new ArrayList<>();
               approach.add(new InterchangePlanner.Knot(b, this.leadTangent(a, b, forward, lead, host)));
               if (single) {
                  approach.add(
                     new InterchangePlanner.Knot(
                        this.serviceWorld(new RoadGeometry.V(-orientation * (targetX + 75.0), this.heights[cross], side * outer)), forward
                     )
                  );
               }

               approach.add(new InterchangePlanner.Knot(target, forward));
               appendCurve(points, widths, curve(approach), width);
               RoadGeometry.Mesh ramp = this.serviceRamp(
                  points, widths, hold, this.heights[host], this.heights[cross], single ? 85.0 : 12.0, !exit, armName(actualArm) + (exit ? " 汇出" : " 汇入")
               );
               this.roads
                  .add(
                     new InterchangePlanner.Leg(
                        armName(actualArm) + (exit ? " 汇出至" : " 汇入自") + (single ? "单点中心路口" : "菱形端部路口"), exit ? actualArm : -1, exit ? -1 : actualArm, ramp
                     )
                  );
            }
         }

         if (this.options.preset == InterchangePlanner.Preset.PARCLO) {
            for (int arm : new int[]{0, 2}) {
               int to = (arm + (this.traffic > 0 ? 1 : 3)) % 4;
               InterchangePlanner.Route r = this.route(arm, to, false, true);
               RoadGeometry.Mesh m = this.horizontal(r);
               List<RoadGeometry.Mesh> mains = this.roads.subList(0, 2).stream().map(InterchangePlanner.Leg::mesh).toList();

               for (int i = r.leadEnd + 1; i < r.tailStart; i++) {
                  for (RoadGeometry.Mesh main : mains) {
                     if (this.overlap(main, m.samples().get(i)) != null) {
                        r.plateauStart = r.plateauStart < 0 ? i : Math.min(r.plateauStart, i);
                        r.plateauEnd = Math.max(r.plateauEnd, i);
                     }
                  }
               }

               r.layer = this.base + this.step;
               this.roads.add(new InterchangePlanner.Leg(r.name, arm, to, this.elevate(r)));
            }
         }

         if (this.options.preset == InterchangePlanner.Preset.DIAMOND || this.options.preset == InterchangePlanner.Preset.SPUI) {
            this.unifiedServiceJunctions();
         }

         this.validateService();
         return this.finish(8);
      }
   }

   private RoadGeometry.Settings rampSettings() {
      RoadGeometry.Style style = this.options.lanes == 1 ? RoadGeometry.Style.R1 : RoadGeometry.Style.R2;
      return new RoadGeometry.Settings(RoadGeometry.Mode.CURVE, style, this.options.width(), 1.0, 0.4, 90.0)
         .options(RoadProfile.Options.DEFAULT.traffic(this.options.leftTraffic).route(RoadProfile.Routing.DEFAULT.fit(false)));
   }

   private RoadGeometry.Mesh straight(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.Settings s) {
      List<RoadGeometry.Sample> samples = new ArrayList<>();
      int n = (int)Math.ceil(a.sub(b).horizontalLength() / 0.5);
      RoadGeometry.V left = this.direction(b.sub(a)).left();

      for (int i = 0; i <= n; i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         samples.add(new RoadGeometry.Sample(this.world(a.mul(1.0 - (double)i / (double)n).add(b.mul((double)i / (double)n))), left, 0.0, s.width() / 2.0));
      }

      return RoadRibbon.mesh(samples, s);
   }

   private void intersection(double station, double corner, double rampHalf) {
      if (this.options.preset != InterchangePlanner.Preset.DIAMOND && this.options.preset != InterchangePlanner.Preset.SPUI) {
         double inner = this.main[this.options.junctionAxis()].width() / 2.0;
         double reach = inner + corner;
         RoadGeometry.Settings s = new RoadGeometry.Settings(
               RoadGeometry.Mode.STRAIGHT,
               RoadGeometry.Style.UNMARKED,
               Math.min(64.0, rampHalf * 2.0),
               Math.max(1.0, this.main[this.options.junctionAxis()].thickness()),
               0.4,
               90.0
            )
            .structure(RoadGeometry.Structure.AUTO)
            .options(RoadProfile.Options.DEFAULT.route(RoadProfile.Routing.DEFAULT.fit(false)));
         List<RoadGeometry.Sample> samples = new ArrayList<>();
         int n = (int)Math.ceil(reach * 4.0);

         for (int i = 0; i <= n; i++) {
            if ((i & 63) == 0) {
               RoadPlanningBudget.check();
            }

            double x = -reach + 2.0 * reach * (double)i / (double)n;
            double offset = Math.max(0.0, Math.abs(x) - inner);
            double half = rampHalf + corner - Math.sqrt(Math.max(0.0, corner * corner - (corner - offset) * (corner - offset)));
            samples.add(
               new RoadGeometry.Sample(
                  this.serviceWorld(new RoadGeometry.V(x, this.heights[this.options.junctionAxis()], station)),
                  this.serviceDirection(new RoadGeometry.V(1.0, 0.0, 0.0)).left(),
                  0.0,
                  Math.min(32.0, half)
               )
            );
         }

         this.roads
            .add(new InterchangePlanner.Leg(this.options.preset == InterchangePlanner.Preset.SPUI ? "单点中心路口" : "菱形端部路口", -1, -1, RoadRibbon.mesh(samples, s)));
      } else {
         this.serviceCenters.add(station);
      }
   }

   private void unifiedServiceJunctions() {
      int cross = this.options.junctionAxis();
      InterchangePlanner.Leg mainLeg = this.roads.get(cross);
      RoadGeometry.Mesh full = mainLeg.mesh();
      double reach = this.options.preset == InterchangePlanner.Preset.SPUI
         ? this.main[1 - cross].width() / 2.0 + this.options.width() + 36.0
         : Math.max(28.0, (this.main[cross].width() + this.options.width()) / 4.0 + 16.0);
      ArrayList<double[]> cuts = new ArrayList<>();
      ArrayList<InterchangePlanner.Leg> pieces = new ArrayList<>();

      for (double station : this.serviceCenters) {
         RoadGeometry.V center = this.serviceWorld(new RoadGeometry.V(0.0, this.heights[cross], station));
         ArrayList<JunctionSpec.Arm> arms = new ArrayList<>();

         for (int side : new int[]{-1, 1}) {
            RoadGeometry.V endpoint = this.serviceWorld(new RoadGeometry.V(0.0, this.heights[cross], station + (double)side * reach));
            RoadGeometry.V inward = center.sub(endpoint).horizontalUnit();
            arms.add(
               JunctionSpec.arm(new RoadGeometry.Node(endpoint, RoadPlanner.yaw(inward), 0.0), inward, this.setting(cross), true, arms.size()).attached(true)
            );
         }

         for (InterchangePlanner.Leg leg : this.roads) {
            if (leg.mesh().settings().style().ramp() && (leg.from() < 0 || leg.to() < 0)) {
               boolean incoming = leg.to() < 0;
               RoadGeometry.Mesh mesh = leg.mesh();
               RoadGeometry.Sample endpoint = incoming ? mesh.last() : mesh.first();
               RoadGeometry.V local = this.local(endpoint.center());
               double along = cross == 1 ? local.z() : -local.x();
               if (this.serviceCenters.size() <= 1 || !(Math.abs(along - station) > 1.0)) {
                  RoadGeometry.V inward = endpoint.left().left().mul(incoming ? -1.0 : 1.0);
                  if (this.options.preset == InterchangePlanner.Preset.SPUI) {
                     RoadGeometry.V axis = this.serviceDirection(new RoadGeometry.V(1.0, 0.0, 0.0));
                     inward = axis.mul(inward.dot(axis) >= 0.0 ? 1.0 : -1.0);
                  }

                  RoadGeometry.Settings ordinary = new RoadGeometry.Settings(
                        RoadGeometry.Mode.STRAIGHT,
                        this.options.lanes == 1 ? RoadGeometry.Style.O1_ONE : RoadGeometry.Style.O2_ONE,
                        mesh.settings().width(),
                        1.0,
                        0.4,
                        90.0
                     )
                     .options(mesh.settings().options())
                     .structure(RoadGeometry.Structure.AUTO);
                  arms.add(
                     JunctionSpec.arm(new RoadGeometry.Node(endpoint.center(), RoadPlanner.yaw(inward), 0.0), inward, ordinary, incoming, arms.size())
                        .attached(true)
                  );
               }
            }
         }

         boolean elevated = this.main[cross].structure() == RoadGeometry.Structure.BRIDGE || this.heights[cross] > this.heights[1 - cross] + 1.0;
         arms.replaceAll(ax -> (elevated && !this.main[cross].options().sidewalk().enabled() ? ax.crossing(false) : ax).signs(false, false));
         JunctionSpec spec = new JunctionSpec(
               center,
               JunctionSpec.Kind.INTERSECTION,
               this.options.leftTraffic,
               6.0,
               12.0,
               1,
               4.0,
               Math.max(1.0, this.main[cross].thickness()),
               JunctionSpec.Control.SIGNALS,
               20,
               3,
               1,
               0,
               true,
               true,
               true,
               arms
            )
            .parallel(this.options.preset == InterchangePlanner.Preset.SPUI);
         double junctionRadius = JunctionPlanner.radius(spec);

         for (int i = 0; i < 2; i++) {
            JunctionSpec.Arm arm = arms.get(i);
            double required = junctionRadius + arm.crossingSetback() + (arm.crosswalk() ? arm.crossingWidth() + arm.stopGap() : 0.0) + 6.0;
            double current = arm.endpoint().position().sub(center).horizontalLength();
            if (current < required) {
               RoadGeometry.V endpoint = center.sub(arm.inward().mul(required));
               arms.set(i, arm.node(new RoadGeometry.Node(endpoint, arm.endpoint().yaw(), 0.0), arm.inward(), arm.external()));
            }
         }

         spec = spec.arms(arms);
         JunctionPlanner.Plan plan = JunctionPlanner.plan(spec);

         for (int ix = 0; ix < plan.pieces().size(); ix++) {
            pieces.add(
               new InterchangePlanner.Leg(
                  this.options.preset == InterchangePlanner.Preset.SPUI ? "单点中心路口" : "菱形端部路口",
                  -1,
                  -1,
                  plan.pieces().get(ix).mesh(),
                  new JunctionPlanner.Ref(spec, ix)
               )
            );
         }

         double a = RoadQueries.horizontal(full, arms.get(0).endpoint().position()).sample().distance();
         double b = RoadQueries.horizontal(full, arms.get(1).endpoint().position()).sample().distance();
         cuts.add(new double[]{Math.min(a, b), Math.max(a, b)});
      }

      cuts.sort(Comparator.comparingDouble(c -> c[0]));
      this.roads.remove(cross);
      double start = 0.0;
      int index = 0;

      for (double[] cut : cuts) {
         if (cut[0] < start - 1.0E-6) {
            throw new IllegalArgumentException("菱形两个路口的引道重叠，请增大半径或扩大范围");
         }

         if (cut[0] > start + 1.0E-6) {
            this.roads.add(new InterchangePlanner.Leg(mainLeg.name() + " · " + index++, mainLeg.from(), mainLeg.to(), serviceSlice(full, start, cut[0])));
         }

         start = cut[1];
      }

      if (start < full.length() - 1.0E-6) {
         this.roads.add(new InterchangePlanner.Leg(mainLeg.name() + " · " + index, mainLeg.from(), mainLeg.to(), serviceSlice(full, start, full.length())));
      }

      this.roads.addAll(pieces);
   }

   private static RoadGeometry.Mesh serviceSlice(RoadGeometry.Mesh mesh, double start, double end) {
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
      samples.add(RoadStructures.sample(mesh, start));

      for (RoadGeometry.Sample s : mesh.samples()) {
         if (s.distance() > start + 1.0E-7 && s.distance() < end - 1.0E-7) {
            samples.add(s);
         }
      }

      samples.add(RoadStructures.sample(mesh, end));
      return RoadRibbon.mesh(samples, mesh.settings());
   }

   private static void appendCurve(List<RoadGeometry.V> points, List<Double> widths, List<RoadGeometry.V> curve, double width) {
      for (int i = 1; i < curve.size(); i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         points.add(curve.get(i));
         widths.add(width);
      }
   }

   private RoadGeometry.Mesh serviceRamp(
      List<RoadGeometry.V> points, List<Double> widths, int hold, double startY, double endY, double flat, boolean reverse, String name
   ) {
      double[] distance = new double[points.size()];

      for (int i = 1; i < points.size(); i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         distance[i] = distance[i - 1] + points.get(i).sub(points.get(i - 1)).horizontalLength();
      }

      double begin = distance[hold];
      double end = distance[distance.length - 1] - flat;
      if (this.options.preset == InterchangePlanner.Preset.DIAMOND || this.options.preset == InterchangePlanner.Preset.SPUI) {
         ArrayList<RoadGeometry.Sample> probe = new ArrayList<>();

         for (int i = 0; i < points.size(); i++) {
            RoadGeometry.V p = points.get(i);
            RoadGeometry.V d = points.get(Math.min(i + 1, points.size() - 1)).sub(points.get(Math.max(0, i - 1)));
            probe.add(new RoadGeometry.Sample(new RoadGeometry.V(p.x(), startY, p.z()), d.horizontalUnit().left(), 0.0, widths.get(i) / 2.0));
         }

         RoadGeometry.Mesh horizontal = RoadRibbon.mesh(probe, this.rampSettings());

         for (RoadClearance.DetailedContact contact : RoadClearance.detailedContacts(horizontal, this.roads.get(1 - this.options.junctionAxis()).mesh())) {
            if (contact.contact().from() < distance[hold] + this.transitionLength()) {
               begin = Math.max(begin, distance[InterchangeGradeProfile.ceil(distance, contact.contact().to())]);
            }
         }
      }

      checkClimb(name, "主路过渡与平交路口之间的升降区", endY - startY, end - begin);
      List<RoadGeometry.Sample> samples = new ArrayList<>();

      for (int i = 0; i < points.size(); i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         RoadGeometry.V p = points.get(i);
         RoadGeometry.V dir = points.get(Math.min(i + 1, points.size() - 1)).sub(points.get(Math.max(0, i - 1)));
         double y = startY + (endY - startY) * grade((distance[i] - begin) / (end - begin));
         samples.add(new RoadGeometry.Sample(new RoadGeometry.V(p.x(), y, p.z()), dir.horizontalUnit().left(), 0.0, widths.get(i) / 2.0));
      }

      if (reverse) {
         Collections.reverse(samples);
         samples.replaceAll(q -> new RoadGeometry.Sample(q.center(), new RoadGeometry.V(-q.left().x(), 0.0, -q.left().z()), 0.0, q.halfWidth()));
      }

      RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, this.rampSettings());
      RoadGrades.validate(mesh);
      RoadRibbon.checkSelfIntersections(mesh, this.options.clearance);
      return mesh;
   }

   private InterchangePlanner.Plan roundabout() {
      double width = this.options.width();
      double radius = Math.max(100.0, Math.max(this.options.radius * 2.0, Math.max(this.main[0].width(), this.main[1].width()) + width + 48.0));
      double ringY = (this.heights[0] + this.heights[1]) / 2.0;
      List<RoadGeometry.Sample> samples = new ArrayList<>();
      int count = (int)Math.ceil((Math.PI * 2) * radius / 0.4);

      for (int i = 0; i <= count; i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         double angle = (double)(-this.traffic * 2) * Math.PI * (double)i / (double)count;
         RoadGeometry.V p = new RoadGeometry.V(radius * Math.cos(angle), ringY, radius * Math.sin(angle));
         RoadGeometry.V d = this.direction(new RoadGeometry.V((double)this.traffic * Math.sin(angle), 0.0, (double)(-this.traffic) * Math.cos(angle)));
         samples.add(new RoadGeometry.Sample(this.world(p), d.left(), 0.0, width / 2.0));
      }

      RoadGeometry.Mesh ring = RoadRibbon.mesh(samples, this.rampSettings());
      this.roads
         .add(
            new InterchangePlanner.Leg(
               "独立中层环道", -1, -1, new RoadGeometry.Mesh(ring.samples(), ring.settings(), ring.min(), ring.max(), ring.length(), true, null)
            )
         );

      for (int arm = 0; arm < 4; arm++) {
         for (boolean exit : new boolean[]{true, false}) {
            int axis = arm % 2;
            int sign = exit ? 1 : -1;
            RoadGeometry.V inward = ARMS[arm].mul(-1.0);
            RoadGeometry.V side = inward.left().mul((double)(this.traffic * sign));
            RoadGeometry.V forward = this.direction(inward);
            double source = this.lengths[arm] - 8.0;
            double lead = this.transitionLength();
            if (source - lead < radius + 90.0) {
               throw new IllegalArgumentException("独立环道和八条匝道需要更大范围，请将各端点外移");
            }

            double outer = this.detachedOffset(axis);
            RoadGeometry.V a = this.world(ARMS[arm].mul(source).add(side.mul(this.attachment(axis))));
            RoadGeometry.V b = this.world(ARMS[arm].mul(source - lead).add(side.mul(outer)));
            double angle = 0.62;
            RoadGeometry.V radial = ARMS[arm].mul(Math.cos(angle)).add(side.mul(Math.sin(angle)));
            RoadGeometry.V target = this.world(new RoadGeometry.V(radial.x() * radius, ringY, radial.z() * radius));
            RoadGeometry.V tangent = this.direction(
               new RoadGeometry.V((double)this.traffic * radial.z() * (double)sign, 0.0, (double)(-this.traffic) * radial.x() * (double)sign)
            );
            List<RoadGeometry.V> points = new ArrayList<>();
            List<Double> widths = new ArrayList<>();
            this.appendLead(axis, points, widths, a, b, forward, lead, this.portWidth(axis), width, false);
            int hold = points.size() - 1;
            RoadGeometry.V approach = this.world(ARMS[arm].mul(radius + 60.0).add(side.mul(outer)));
            appendCurve(
               points,
               widths,
               curve(
                  List.of(
                     new InterchangePlanner.Knot(b, this.leadTangent(a, b, forward, lead, axis)),
                     new InterchangePlanner.Knot(approach, forward),
                     new InterchangePlanner.Knot(target, tangent)
                  )
               ),
               width
            );
            RoadGeometry.Mesh mesh = this.serviceRamp(points, widths, hold, this.heights[axis], ringY, 22.0, !exit, "环道匝道 " + arm);
            this.roads.add(new InterchangePlanner.Leg("环道 " + arm + (exit ? " 汇出" : " 汇入"), exit ? arm : -1, exit ? -1 : arm, mesh));
         }
      }

      this.validateService();
      return this.finish(8);
   }

   private void validateService() {
      if (this.options.preset != InterchangePlanner.Preset.DIAMOND && this.options.preset != InterchangePlanner.Preset.SPUI) {
         this.validateLegacyService();
      } else {
         for (int i = 1; i < this.roads.size(); i++) {
            for (int j = 0; j < i; j++) {
               RoadPlanningBudget.check();

               for (RoadClearance.DetailedContact detail : RoadClearance.detailedContacts(this.roads.get(i).mesh(), this.roads.get(j).mesh())) {
                  if (!(Math.max(Math.abs(detail.minimumDifference()), Math.abs(detail.maximumDifference())) <= 1.0E-5)) {
                     this.exactClearance(this.roads.get(i).name(), this.roads.get(j).name(), detail.contact());
                  }
               }
            }
         }
      }
   }

   private void validateLegacyService() {
      for (int i = 2; i < this.roads.size(); i++) {
         RoadPlanningBudget.check();
         RoadGeometry.Mesh a = this.roads.get(i).mesh();

         for (int j = 0; j < i; j++) {
            RoadGeometry.Mesh b = this.roads.get(j).mesh();

            for (int k = 0; k < a.samples().size(); k += 3) {
               if ((k & 63) == 0) {
                  RoadPlanningBudget.check();
               }

               RoadGeometry.Sample p = a.samples().get(k);
               RoadQueries.Projection q = this.overlap(b, p);
               if (q != null) {
                  double dy = p.center().y() - q.sample().center().y();
                  if (!(Math.abs(dy) < 0.12)) {
                     double need = this.options.clearance + (dy >= 0.0 ? a.settings().thickness() : b.settings().thickness());
                     if (Math.abs(dy) < need - 0.05) {
                        throw new IllegalArgumentException(this.roads.get(i).name + "与" + this.roads.get(j).name + "净高不足，请扩大范围");
                     }
                  }
               }
            }
         }
      }
   }

   private InterchangePlanner.Plan finish(int movements) {
      for (InterchangePlanner.Leg road : this.roads) {
         RoadGrades.validate(road.mesh());
      }

      for (InterchangePlanner.Leg road : this.roads) {
         for (RoadGeometry.Sample sample : road.mesh.samples()) {
            RoadGeometry.V p = this.local(sample.center());
            double pad = Math.max(this.main[0].width(), this.main[1].width());
            if (p.x() < -this.lengths[0] - pad || p.x() > this.lengths[2] + pad || p.z() < -this.lengths[1] - pad || p.z() > this.lengths[3] + pad) {
               throw new IllegalArgumentException("该预设超出选定范围，请扩大范围");
            }
         }
      }

      double min = this.roads.stream().filter(l -> l.mesh.settings().style().ramp()).mapToDouble(l -> RoadRibbon.minRadius(l.mesh)).min().orElse(0.0);
      return new InterchangePlanner.Plan(
         List.copyOf(this.roads), movements, min, this.roads.stream().mapToDouble(l -> l.mesh.max().y()).max().orElse(this.base), this.center
      );
   }

   private InterchangePlanner.Route route(int from, int to, boolean right, boolean leaf) {
      RoadGeometry.V di = ARMS[from].mul(-1.0);
      RoadGeometry.V dj = ARMS[to];
      int ia = from % 2;
      int ib = to % 2;
      double width = this.options.width();
      double lead = this.transitionLength();
      double oa = leaf && this.collectorLoops() ? this.auxiliaryOffset(ia) : this.detachedOffset(ia);
      double ob = leaf && this.collectorLoops() ? this.auxiliaryOffset(ib) : this.detachedOffset(ib);
      if (leaf && this.options.preset.three) {
         lead = Math.max(lead, Math.max(oa, ob) + this.loopRadius + 24.0);
      }

      double sa = Math.min(this.lengths[from] - 8.0, Math.min(this.lengths[0], Math.min(this.lengths[1], this.lengths[2])) * 0.94);
      double sb = Math.min(this.lengths[to] - 8.0, sa);
      if (!this.options.preset.three) {
         double shortest = Arrays.stream(this.lengths).min().orElseThrow();
         sa = Math.min(sa, shortest * 0.94);
         sb = Math.min(sb, shortest * 0.94);
      }

      RoadGeometry.V centerB;
      RoadGeometry.V centerA;
      if (leaf) {
         double station = Math.max(oa, ob) + this.loopRadius;
         centerA = di.mul(station - lead);
         centerB = dj.mul(-(station - lead));
      } else {
         centerA = ARMS[from].mul(sa);
         centerB = ARMS[to].mul(sb);
      }

      RoadGeometry.V fwa = this.direction(di);
      RoadGeometry.V fwb = this.direction(dj);
      RoadGeometry.V wa = this.world(centerA);
      RoadGeometry.V wb = this.world(centerB);
      double atA = this.attachment(ia);
      double atB = this.attachment(ib);
      RoadGeometry.V start = wa.add(fwa.left().mul((double)(this.options.leftTraffic ? -1 : 1) * atA));
      RoadGeometry.V end = wb.add(fwb.left().mul((double)(this.options.leftTraffic ? -1 : 1) * atB));
      RoadGeometry.V aa = wa.add(fwa.mul(lead)).add(fwa.left().mul((double)(this.options.leftTraffic ? -1 : 1) * oa));
      RoadGeometry.V bb = wb.sub(fwb.mul(lead)).add(fwb.left().mul((double)(this.options.leftTraffic ? -1 : 1) * ob));
      if (leaf && this.collectorLoops()) {
         RoadGeometry.V[] ports = this.leafPorts(from, to);
         aa = ports[0];
         bb = ports[1];
      }

      RoadGeometry.V loopA = aa;
      RoadGeometry.V loopB = bb;
      boolean terminalA = this.options.preset.three && from == 1;
      boolean terminalB = this.options.preset.three && to == 1;
      boolean collector = leaf && this.collectorLoops();
      if (terminalA) {
         aa = this.world(ARMS[1].mul(this.terminalStation())).add(fwa.left().mul((double)(this.options.leftTraffic ? -1 : 1) * (width / 2.0 + 0.5)));
      }

      if (terminalB) {
         bb = this.world(ARMS[1].mul(this.terminalStation())).add(fwb.left().mul((double)(this.options.leftTraffic ? -1 : 1) * (width / 2.0 + 0.5)));
      }

      List<RoadGeometry.V> path = new ArrayList<>();
      List<Double> widths = new ArrayList<>();
      double pwA = this.portWidth(ia);
      double pwB = this.portWidth(ib);
      if (!terminalA && !collector) {
         this.appendLead(ia, path, widths, start, aa, fwa, lead, pwA, width, false, !leaf);
      } else {
         path.add(aa);
         widths.add(width);
      }

      int leadEnd = path.size() - 1;
      double slopeA = !leaf && !terminalA && !collector ? this.peelSlope(ia, lead) : 0.0;
      double slopeB = !leaf && !terminalB && !collector ? this.peelSlope(ib, lead) : 0.0;
      int handed = this.options.leftTraffic ? -1 : 1;
      RoadGeometry.V tangentA = fwa.add(fwa.left().mul((double)handed * slopeA)).horizontalUnit();
      RoadGeometry.V tangentB = fwb.sub(fwb.left().mul((double)handed * slopeB)).horizontalUnit();
      List<RoadGeometry.V> middle;
      if (leaf) {
         RoadLoop loop = new RoadLoop(loopA, loopB, tangentA, tangentB, this.loopRadius, !this.options.leftTraffic);
         int n = (int)Math.ceil(loop.length() / 0.45);
         middle = new ArrayList<>();
         if (terminalA) {
            middle.addAll(curve(List.of(new InterchangePlanner.Knot(aa, fwa), new InterchangePlanner.Knot(loopA, fwa))));
         }

         for (int k = terminalA ? 1 : 0; k <= n; k++) {
            if ((k & 63) == 0) {
               RoadPlanningBudget.check();
            }

            middle.add(loop.point((double)k / (double)n));
         }
      } else if (right) {
         middle = curve(List.of(new InterchangePlanner.Knot(aa, tangentA), new InterchangePlanner.Knot(bb, tangentB)), this.directCurve);
      } else if (this.options.preset != InterchangePlanner.Preset.TURBINE && this.options.preset != InterchangePlanner.Preset.Y) {
         List<InterchangePlanner.Knot> knots = new ArrayList<>();
         knots.add(new InterchangePlanner.Knot(aa, tangentA));
         double room = Math.min(sa, sb) - lead;
         if (!right && this.options.preset != InterchangePlanner.Preset.TURBINE && this.options.preset != InterchangePlanner.Preset.Y) {
            double reach = this.options.preset == InterchangePlanner.Preset.STACK
               ? Math.max(0.0, room - this.cornerScale * Math.max(this.options.radius, Math.max(oa, ob) * 2.5 + width * 1.5 + 2.0))
               : Math.max(20.0, room * 0.42);
            if (reach > 1.0) {
               knots.add(new InterchangePlanner.Knot(aa.add(fwa.mul(reach)), fwa));
               knots.add(new InterchangePlanner.Knot(bb.sub(fwb.mul(reach)), fwb));
            }
         }

         knots.add(new InterchangePlanner.Knot(bb, tangentB));
         middle = curve(knots, right ? 0.18 : 0.4);
      } else {
         double radius = Math.max(this.options.radius * 2.0, Math.max(this.main[0].width(), this.main[1].width()) * 2.0 + 18.0);
         RoadGeometry.V enter = this.world(dj.mul(-radius));
         RoadGeometry.V leave = this.world(di.mul(radius));
         middle = new ArrayList<>(curve(List.of(new InterchangePlanner.Knot(aa, tangentA), new InterchangePlanner.Knot(enter, fwa))));
         int n = (int)Math.ceil(Math.PI * radius / 0.8);

         for (int i = 1; i <= n; i++) {
            if ((i & 63) == 0) {
               RoadPlanningBudget.check();
            }

            double angle = (Math.PI / 2) * (double)i / (double)n;
            middle.add(this.world(dj.mul(-radius * Math.cos(angle)).add(di.mul(radius * Math.sin(angle)))));
         }

         List<RoadGeometry.V> tail = curve(List.of(new InterchangePlanner.Knot(leave, fwb), new InterchangePlanner.Knot(bb, tangentB)));
         middle.addAll(tail.subList(1, tail.size()));
      }

      for (int i = 1; i < middle.size(); i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         path.add(middle.get(i));
         widths.add(width);
      }

      int tailStart = path.size() - 1;
      if (!terminalB && !collector) {
         this.appendLead(ib, path, widths, bb, end, fwb, lead, width, pwB, true, !leaf);
      }

      if (path.size() > 16384) {
         throw new IllegalArgumentException("立交匝道过长，请缩小范围或半径");
      } else {
         return new InterchangePlanner.Route(
            (char)(65 + (from == 1 ? 2 : (from == 2 ? 1 : from)))
               + "→"
               + (char)(65 + (to == 1 ? 2 : (to == 2 ? 1 : to)))
               + (leaf ? " 环绕" : (right ? (this.options.leftTraffic ? " 左转" : " 右转") : (this.options.leftTraffic ? " 右转" : " 左转"))),
            from,
            to,
            path,
            widths,
            leadEnd,
            tailStart,
            right ? 0 : (leaf ? 1 : 2)
         );
      }
   }

   private double padding(int axis) {
      if (!this.main[axis].options().routing().fitEdges()) {
         return 0.0;
      } else {
         RoadGeometry.V d = axis == 0 ? this.u : this.v;
         RoadGeometry.V p = this.nodes[axis == 0 ? 0 : 2].position();
         double coordinate;
         if (Math.abs(d.x()) > 0.99999) {
            coordinate = p.z();
         } else {
            if (!(Math.abs(d.z()) > 0.99999)) {
               return 0.0;
            }

            coordinate = p.x();
         }

         double half = this.main[axis].width() / 2.0;
         return Math.abs(coordinate - Math.floor(coordinate) - 0.5) < 1.0E-7 ? Math.ceil(coordinate + half - 1.0E-7) - coordinate - half : 0.0;
      }
   }

   private double detachedOffset(int axis) {
      return this.main[axis].width() / 2.0 + this.padding(axis) + this.options.width() / 2.0 + 0.5;
   }

   private double auxiliaryOffset(int axis) {
      RoadProfile.Layout layout = RoadProfile.layout(this.main[axis], this.main[axis].width() + 2.0 * this.padding(axis));
      return layout.catalog().type() == RoadProfile.Type.HIGHWAY
         ? Math.abs(layout.outer(layout.outside())) + (this.options.width() - 1.0) / 2.0
         : this.main[axis].width() / 2.0 + this.padding(axis) + this.options.width() / 2.0 - 0.2;
   }

   private double attachment(int axis) {
      if (RoadProfile.catalog(this.main[axis].style()).type() == RoadProfile.Type.HIGHWAY) {
         RoadProfile.Layout layout = RoadProfile.layout(this.main[axis], this.main[axis].width() + 2.0 * this.padding(axis));
         return Math.abs(layout.outer(layout.outside())) + layout.shoulderWidth() / 2.0;
      } else {
         return this.main[axis].width() / 2.0 + this.padding(axis) - 0.2 + this.portWidth(axis) / 2.0;
      }
   }

   private double portWidth(int axis) {
      return RoadProfile.catalog(this.main[axis].style()).type() == RoadProfile.Type.HIGHWAY
         ? RoadProfile.layout(this.main[axis], this.main[axis].width() + 2.0 * this.padding(axis)).shoulderWidth()
         : 0.5;
   }

   private RoadGeometry.V leadTangent(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V forward, double lead, int axis) {
      RoadGeometry.V side = b.sub(a).sub(forward.mul(lead)).horizontalUnit();
      return forward.add(side.mul(this.peelSlope(axis, lead))).horizontalUnit();
   }

   private double peelSlope(int axis, double lead) {
      return this.servicePreset() ? 0.0 : 2.0 * (this.detachedOffset(axis) - this.auxiliaryOffset(axis)) / (0.35 * lead);
   }

   private void appendLead(
      int axis,
      List<RoadGeometry.V> out,
      List<Double> widths,
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V forward,
      double lead,
      double wa,
      double wb,
      boolean skip
   ) {
      this.appendLead(axis, out, widths, a, b, forward, lead, wa, wb, skip, false);
   }

   private void appendLead(
      int axis,
      List<RoadGeometry.V> out,
      List<Double> widths,
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V forward,
      double lead,
      double wa,
      double wb,
      boolean skip,
      boolean continuous
   ) {
      RoadGeometry.V side = b.sub(a).sub(forward.mul(lead));
      int n = (int)Math.ceil(lead / 0.4);

      for (int i = skip ? 1 : 0; i <= n; i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         double t = (double)i / (double)n;
         boolean merging = wa > wb;
         double q = merging ? 1.0 - t : t;
         double grow = RoadGeometry.Settings.smooth(Math.min(1.0, q / 0.32));
         double total = side.horizontalLength();
         double occupied = this.auxiliaryOffset(axis) - this.attachment(axis);
         double peel = Math.max(0.0, Math.min(1.0, (q - 0.65) / 0.35));
         double leave = continuous ? peel * peel : RoadGeometry.Settings.smooth(peel);
         double shift = total < 1.0E-8 ? grow : (occupied * grow + Math.max(0.0, total - occupied) * leave) / total;
         if (merging) {
            grow = 1.0 - grow;
            shift = 1.0 - shift;
         }

         out.add(a.add(forward.mul(lead * t)).add(side.mul(shift)));
         widths.add(wa + (wb - wa) * grow);
      }
   }

   private static List<RoadGeometry.V> curve(List<InterchangePlanner.Knot> knots) {
      return curve(knots, 0.4);
   }

   private static List<RoadGeometry.V> curve(List<InterchangePlanner.Knot> knots, double strength) {
      List<RoadGeometry.V> out = new ArrayList<>();

      for (int j = 1; j < knots.size(); j++) {
         RoadPlanningBudget.check();
         InterchangePlanner.Knot a = knots.get(j - 1);
         InterchangePlanner.Knot b = knots.get(j);
         double chord = a.p.sub(b.p).horizontalLength();
         double handle = chord * strength;
         RoadGeometry.V c = a.p.add(a.direction.mul(handle));
         RoadGeometry.V d = b.p.sub(b.direction.mul(handle));
         int n = (int)Math.ceil(chord * 1.65 / 0.4);

         for (int i = j == 1 ? 0 : 1; i <= n; i++) {
            if ((i & 63) == 0) {
               RoadPlanningBudget.check();
            }

            double t = (double)i / (double)n;
            double u = 1.0 - t;
            out.add(a.p.mul(u * u * u).add(c.mul(3.0 * u * u * t)).add(d.mul(3.0 * u * t * t)).add(b.p.mul(t * t * t)));
         }
      }

      return out;
   }

   private void extendThroat(InterchangePlanner.Route r) {
      int originalA = r.leadEnd;
      int originalB = r.tailStart;

      for (int i = originalA; i < r.points.size() / 2; i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         r.leadEnd = Math.max(r.leadEnd, i);
         if (this.overlap(this.roads.get(r.from % 2).mesh, this.routeSample(r, i)) == null) {
            break;
         }
      }

      for (int i = originalB; i > r.points.size() / 2; i--) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         r.tailStart = Math.min(r.tailStart, i);
         if (this.overlap(this.roads.get(r.to % 2).mesh, this.routeSample(r, i)) == null) {
            break;
         }
      }

      if (this.collectorLoops() && r.priority == 1) {
         double landing = this.options.width() * 2.0 + 4.0;
         double distance = 0.0;

         for (int i = 1; i < r.points.size() / 2; i++) {
            distance += r.points.get(i).distance(r.points.get(i - 1));
            if (distance > landing) {
               break;
            }

            r.leadEnd = Math.max(r.leadEnd, i);
         }

         distance = 0.0;

         for (int i = r.points.size() - 2; i > r.points.size() / 2; i--) {
            distance += r.points.get(i).distance(r.points.get(i + 1));
            if (distance > landing) {
               break;
            }

            r.tailStart = Math.min(r.tailStart, i);
         }
      }

      if (this.collectorLoops()) {
         for (int i = originalA; i < r.points.size() / 2; i++) {
            if ((i & 63) == 0) {
               RoadPlanningBudget.check();
            }

            RoadGeometry.Sample p = this.routeSample(r, i);
            boolean touches = false;

            for (int k = 2; k < this.roads.size(); k++) {
               if (this.roads.get(k).from == r.from && this.overlap(this.roads.get(k).mesh, p) != null) {
                  touches = true;
               }
            }

            r.leadEnd = Math.max(r.leadEnd, i);
            if (!touches) {
               break;
            }
         }

         for (int i = originalB; i > r.points.size() / 2; i--) {
            if ((i & 63) == 0) {
               RoadPlanningBudget.check();
            }

            RoadGeometry.Sample p = this.routeSample(r, i);
            boolean touches = false;

            for (int kx = 2; kx < this.roads.size(); kx++) {
               if (this.roads.get(kx).to == r.to && this.overlap(this.roads.get(kx).mesh, p) != null) {
                  touches = true;
               }
            }

            r.tailStart = Math.min(r.tailStart, i);
            if (!touches) {
               break;
            }
         }
      }

      for (InterchangePlanner.Route other : this.routes) {
         RoadPlanningBudget.check();
         if (other != r) {
            if (r.from == other.from && r.points.get(0).distance(other.points.get(0)) < 0.1) {
               int limit = Math.min(originalB, r.points.size() / 2);

               for (int i = originalA; i < limit; i++) {
                  r.leadEnd = Math.max(r.leadEnd, i);
                  if (!near(other, r.points.get(i), r.widths.get(i) / 2.0 + (double)(this.options.lanes * 2) + 3.0)) {
                     break;
                  }
               }
            }

            if (r.to == other.to && r.points.get(r.points.size() - 1).distance(other.points.get(other.points.size() - 1)) < 0.1) {
               int limit = Math.max(originalA, r.points.size() / 2);

               for (int ix = originalB; ix > limit; ix--) {
                  r.tailStart = Math.min(r.tailStart, ix);
                  if (!near(other, r.points.get(ix), r.widths.get(ix) / 2.0 + (double)(this.options.lanes * 2) + 3.0)) {
                     break;
                  }
               }
            }
         }
      }
   }

   private RoadGeometry.V routeTangent(InterchangePlanner.Route r, int i) {
      if (this.collectorLoops() && r.priority == 1) {
         if (i == 0) {
            return this.direction(ARMS[r.from].mul(-1.0));
         }

         if (i == r.points.size() - 1) {
            return this.direction(ARMS[r.to]);
         }
      }

      return r.points.get(Math.min(r.points.size() - 1, i + 1)).sub(r.points.get(Math.max(0, i - 1))).horizontalUnit();
   }

   private RoadGeometry.Sample routeSample(InterchangePlanner.Route r, int i) {
      RoadGeometry.V before = r.points.get(Math.max(0, i - 1));
      RoadGeometry.V after = r.points.get(Math.min(r.points.size() - 1, i + 1));
      return new RoadGeometry.Sample(r.points.get(i), this.routeTangent(r, i).left(), 0.0, r.widths.get(i) / 2.0);
   }

   private static boolean near(InterchangePlanner.Route r, RoadGeometry.V p, double radius) {
      for (int i = 0; i < r.points.size(); i += 3) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         if (r.points.get(i).sub(p).horizontalLength() < radius) {
            return true;
         }
      }

      return false;
   }

   private RoadGeometry.Mesh horizontal(InterchangePlanner.Route r) {
      List<RoadGeometry.Sample> points = new ArrayList<>();

      for (int i = 0; i < r.points.size(); i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         RoadGeometry.V p = r.points.get(i);
         if (i == 0) {
            r.points.get(1).sub(p);
         } else if (i == r.points.size() - 1) {
            p.sub(r.points.get(i - 1));
         } else {
            r.points.get(i + 1).sub(r.points.get(i - 1));
         }

         points.add(new RoadGeometry.Sample(new RoadGeometry.V(p.x(), this.base, p.z()), this.routeTangent(r, i).left(), 0.0, r.widths.get(i) / 2.0));
      }

      return RoadRibbon.mesh(points, this.rampSettings());
   }

   private double nominalHeight(InterchangePlanner.Route r, double[] d, double at) {
      double h0 = this.hostHeight(r.from, r.points.get(r.leadEnd));
      double h1 = this.hostHeight(r.to, r.points.get(r.tailStart));
      if (at <= d[r.leadEnd]) {
         return h0;
      } else if (at >= d[r.tailStart]) {
         return h1;
      } else if (r.plateauStart < 0) {
         return h0 + (h1 - h0) * grade((at - d[r.leadEnd]) / (d[r.tailStart] - d[r.leadEnd]));
      } else if (at < d[r.plateauStart]) {
         return h0 + (r.layer - h0) * grade((at - d[r.leadEnd]) / (d[r.plateauStart] - d[r.leadEnd]));
      } else {
         return at > d[r.plateauEnd] ? r.layer + (h1 - r.layer) * grade((at - d[r.plateauEnd]) / (d[r.tailStart] - d[r.plateauEnd])) : r.layer;
      }
   }

   private double[] reachable(InterchangePlanner.Route r, double[] d, double from, double to) {
      double h0 = this.hostHeight(r.from, r.points.get(r.leadEnd));
      double h1 = this.hostHeight(r.to, r.points.get(r.tailStart));
      double lo = Double.NEGATIVE_INFINITY;
      double hi = Double.POSITIVE_INFINITY;

      for (double at : new double[]{from, to}) {
         double a = Math.max(0.0, at - d[r.leadEnd]);
         double b = Math.max(0.0, d[r.tailStart] - at);
         lo = Math.max(lo, Math.max(h0 - a * 0.15, h1 - b * 0.15));
         hi = Math.min(hi, Math.min(h0 + a * 0.15, h1 + b * 0.15));
      }

      return new double[]{lo, hi};
   }

   private List<RoadGeometry.Mesh> elevateCrossings(List<RoadGeometry.Mesh> flat) {
      int count = this.routes.size();
      int total = 0;
      int[] offset = new int[count];
      double[][] distance = new double[count][];

      for (int r = 0; r < count; r++) {
         offset[r] = total;
         List<RoadGeometry.V> points = this.routes.get(r).points;
         total += points.size();
         distance[r] = new double[points.size()];

         for (int i = 1; i < points.size(); i++) {
            distance[r][i] = distance[r][i - 1] + points.get(i).sub(points.get(i - 1)).horizontalLength();
         }
      }

      double[] preferred = new double[total];
      double[] lower = new double[total];
      double[] upper = new double[total];
      Arrays.fill(lower, Double.NEGATIVE_INFINITY);
      Arrays.fill(upper, Double.POSITIVE_INFINITY);
      ArrayList<InterchangeGradeProfile.Edge> edges = new ArrayList<>();

      for (int ri = 0; ri < count; ri++) {
         InterchangePlanner.Route r = this.routes.get(ri);
         int n = r.points.size();
         int start = offset[ri];
         double[] d = distance[ri];
         if (r.tailStart <= r.leadEnd) {
            throw new IllegalArgumentException(r.name + " 没有独立升降区，请扩大范围");
         }

         double h0 = this.hostHeight(r.from, r.points.get(r.leadEnd));
         double h1 = this.hostHeight(r.to, r.points.get(r.tailStart));

         for (int i = 0; i < n; i++) {
            preferred[start + i] = h0 + (h1 - h0) * grade((d[i] - d[r.leadEnd]) / (d[r.tailStart] - d[r.leadEnd]));
            if (i <= r.leadEnd) {
               lower[start + i] = upper[start + i] = h0;
            }

            if (i >= r.tailStart) {
               lower[start + i] = upper[start + i] = h1;
            }

            if (i > 0) {
               double reach = 0.149999 * (d[i] - d[i - 1]);
               edges.add(new InterchangeGradeProfile.Edge(start + i - 1, start + i, -reach));
               edges.add(new InterchangeGradeProfile.Edge(start + i, start + i - 1, -reach));
            }
         }

         for (int k = 0; k <= this.roads.size() + ri; k++) {
            InterchangePlanner.Leg host = k < this.roads.size() ? this.roads.get(k) : null;
            int oi = k - this.roads.size();
            InterchangePlanner.Route other = host == null ? this.routes.get(oi) : null;
            RoadGeometry.Mesh against = host == null ? flat.get(oi) : host.mesh();

            for (RoadClearance.DetailedContact detail : oi == ri
               ? RoadClearance.detailedSelfContacts(flat.get(ri), this.options.width() * 2.0)
               : RoadClearance.detailedContacts(flat.get(ri), against)) {
               RoadClearance.Contact c = detail.contact();
               if (oi != ri || !(detail.otherFrom() <= c.to() + this.options.width() * 2.0)) {
                  InterchangePlanner.ClearanceMouth mouth = clearanceMouth(r.from, r.to, flat.get(ri), r.leadEnd, r.tailStart, c.from(), c.to());
                  InterchangePlanner.ClearanceMouth otherMouth = other == null
                     ? null
                     : clearanceMouth(other.from, other.to, against, other.leadEnd, other.tailStart, detail.otherFrom(), detail.otherTo());
                  RoadClearance.DetailedContact footprint = new RoadClearance.DetailedContact(c, detail.otherFrom(), detail.otherTo(), 0.0, 0.0);
                  if (!this.sharedMouthOnCarriageway(flat.get(ri), mouth, against, otherMouth, host, footprint)) {
                     double ours = this.nominalHeight(r, d, (c.from() + c.to()) / 2.0);
                     double theirs = host == null
                        ? this.nominalHeight(other, distance[oi], (detail.otherFrom() + detail.otherTo()) / 2.0)
                        : against.first().center().y();
                     if (oi == ri && Math.abs(ours - theirs) < 1.0E-6) {
                        double ds = (c.from() + c.to()) / 2.0;
                        double dt = (detail.otherFrom() + detail.otherTo()) / 2.0;
                        ours = Math.min(ds - d[r.leadEnd], d[r.tailStart] - ds);
                        theirs = Math.min(dt - d[r.leadEnd], d[r.tailStart] - dt);
                     }

                     boolean above = ours >= theirs;
                     double need = Math.max(this.options.clearance, c.required()) + 0.08;
                     int first = InterchangeGradeProfile.floor(d, c.from());
                     int last = InterchangeGradeProfile.ceil(d, c.to());
                     if (this.options.preset == InterchangePlanner.Preset.HYBRID && other != null && other != r) {
                        double[] ourReach = this.reachable(r, d, c.from(), c.to());
                        double[] theirReach = this.reachable(other, distance[oi], detail.otherFrom(), detail.otherTo());
                        double aboveDepth = flat.get(ri).settings().thickness() + RoadInfrastructure.girderDepth(flat.get(ri));
                        double belowDepth = against.settings().thickness() + RoadInfrastructure.girderDepth(against);
                        boolean canAbove = ourReach[1] >= theirReach[0] + need + aboveDepth - 1.0E-7;
                        boolean canBelow = theirReach[1] >= ourReach[0] + need + belowDepth - 1.0E-7;
                        if (above && !canAbove && canBelow) {
                           above = false;
                        } else if (!above && !canBelow && canAbove) {
                           above = true;
                        }
                     }

                     if (host != null) {
                        double bound = above ? this.base + c.raiseBound() + need - c.required() - 0.1 : this.base - c.lowerBound() - need + c.required() + 0.1;

                        for (int i = first; i <= last; i++) {
                           if (above) {
                              lower[start + i] = Math.max(lower[start + i], bound);
                           } else {
                              upper[start + i] = Math.min(upper[start + i], bound);
                           }
                        }
                     } else {
                        int a = InterchangeGradeProfile.floor(distance[oi], detail.otherFrom());
                        int b = InterchangeGradeProfile.ceil(distance[oi], detail.otherTo());
                        double depth = above
                           ? flat.get(ri).settings().thickness() + RoadInfrastructure.girderDepth(flat.get(ri))
                           : against.settings().thickness() + RoadInfrastructure.girderDepth(against);

                        for (int ix = first; ix <= last; ix++) {
                           for (int j = a; j <= b; j++) {
                              edges.add(
                                 new InterchangeGradeProfile.Edge(above ? offset[oi] + j : start + ix, above ? start + ix : offset[oi] + j, need + depth)
                              );
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      double[] y = InterchangeGradeProfile.solve(preferred, lower, upper, edges, offset, distance);
      ArrayList<RoadGeometry.Mesh> result = new ArrayList<>();

      for (int ri = 0; ri < count; ri++) {
         InterchangePlanner.Route r = this.routes.get(ri);
         ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

         for (int ix = 0; ix < r.points.size(); ix++) {
            RoadGeometry.V p = r.points.get(ix);
            samples.add(
               new RoadGeometry.Sample(new RoadGeometry.V(p.x(), y[offset[ri] + ix], p.z()), this.routeTangent(r, ix).left(), 0.0, r.widths.get(ix) / 2.0)
            );
         }

         RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, this.rampSettings());
         RoadGrades.validate(mesh);
         result.add(mesh);
      }

      return result;
   }

   private RoadGeometry.Mesh elevate(InterchangePlanner.Route r) {
      RoadPlanningBudget.check();
      double[] distance = new double[r.points.size()];

      for (int i = 1; i < distance.length; i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         distance[i] = distance[i - 1] + r.points.get(i).sub(r.points.get(i - 1)).horizontalLength();
      }

      double d0 = distance[r.leadEnd];
      double d1 = distance[r.tailStart];
      double h0 = this.hostHeight(r.from, r.points.get(r.leadEnd));
      double h1 = this.hostHeight(r.to, r.points.get(r.tailStart));
      if (r.plateauStart >= 0) {
         checkClimb(r.name, "汇出后至首个交叉平台", r.layer - h0, distance[r.plateauStart] - d0);
         checkClimb(r.name, "末个交叉平台至汇入", h1 - r.layer, d1 - distance[r.plateauEnd]);
      } else {
         checkClimb(r.name, "汇出与汇入之间的升降区", h1 - h0, d1 - d0);
      }

      List<RoadGeometry.Sample> samples = new ArrayList<>();

      for (int i = 0; i < r.points.size(); i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         RoadGeometry.V p = r.points.get(i);
         double y;
         if (i <= r.leadEnd) {
            y = this.hostHeight(r.from, p);
         } else if (i >= r.tailStart) {
            y = this.hostHeight(r.to, p);
         } else {
            double t = (distance[i] - d0) / (d1 - d0);
            if (r.plateauStart >= 0) {
               double begin = distance[r.plateauStart];
               double end = distance[r.plateauEnd];
               if (distance[i] < begin) {
                  y = h0 + (r.layer - h0) * grade((distance[i] - d0) / (begin - d0));
               } else if (distance[i] > end) {
                  y = r.layer + (h1 - r.layer) * grade((distance[i] - end) / (d1 - end));
               } else {
                  y = r.layer;
               }
            } else {
               y = h0 + (h1 - h0) * grade(t);
            }
         }

         if (i == 0) {
            r.points.get(1).sub(p);
         } else if (i == r.points.size() - 1) {
            p.sub(r.points.get(i - 1));
         } else {
            r.points.get(i + 1).sub(r.points.get(i - 1));
         }

         samples.add(new RoadGeometry.Sample(new RoadGeometry.V(p.x(), y, p.z()), this.routeTangent(r, i).left(), 0.0, r.widths.get(i) / 2.0));
      }

      RoadGeometry.Style style = this.options.lanes == 1 ? RoadGeometry.Style.R1 : RoadGeometry.Style.R2;
      RoadGeometry.Settings s = new RoadGeometry.Settings(RoadGeometry.Mode.CURVE, style, this.options.width(), 1.0, 0.4, 90.0)
         .rampTurn(RoadGeometry.RampTurn.AUTO)
         .options(RoadProfile.Options.DEFAULT.traffic(this.options.leftTraffic));

      try {
         RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, s);
         RoadGrades.validate(mesh);
         return mesh;
      } catch (IllegalArgumentException var22) {
         throw new IllegalArgumentException(
            r.name
               + "："
               + var22.getMessage()
               + (
                  Boolean.getBoolean("sr.debug")
                     ? " holds="
                        + r.leadEnd
                        + ","
                        + r.tailStart
                        + " plate="
                        + r.plateauStart
                        + ","
                        + r.plateauEnd
                        + " d="
                        + d0
                        + ","
                        + d1
                        + " hp="
                        + (r.plateauStart < 0 ? "none" : distance[r.plateauStart] + "," + distance[r.plateauEnd])
                        + " layer="
                        + r.layer
                        + " endpoints="
                        + h0
                        + ","
                        + h1
                     : ""
               )
         );
      }
   }

   private static void checkClimb(String name, String section, double height, double available) {
      double need = Math.abs(height) * 1.25 / 0.15;
      if (available + 1.0E-6 < need) {
         throw new IllegalArgumentException(
            String.format(Locale.ROOT, "%s：%s可用 %.1f 格，升降 %.1f 格按 15%% 纵坡至少需 %.1f 格；这是该段约束，非端点到交点距离", name, section, available, Math.abs(height), need)
         );
      }
   }

   private static double grade(double t) {
      t = Math.max(0.0, Math.min(1.0, t));
      double ease = 0.2;
      double scale = 1.0 / (1.0 - ease);
      if (t < ease) {
         return scale * t * t / (2.0 * ease);
      } else {
         return t > 1.0 - ease ? 1.0 - scale * (1.0 - t) * (1.0 - t) / (2.0 * ease) : scale * (t - ease / 2.0);
      }
   }

   private double hostHeight(int arm, RoadGeometry.V point) {
      RoadGeometry.V local = this.local(point);
      return this.height(arm % 2, arm % 2 == 0 ? local.x() : local.z());
   }

   static InterchangePlanner.ClearanceMouth clearanceMouth(int fromArm, int toArm, RoadGeometry.Mesh mesh, int leadEnd, int tailStart, double from, double to) {
      if (from >= mesh.first().distance() - 1.0E-7 && to <= mesh.samples().get(leadEnd).distance() + 1.0E-7) {
         return new InterchangePlanner.ClearanceMouth(fromArm, true);
      } else {
         return from >= mesh.samples().get(tailStart).distance() - 1.0E-7 && to <= mesh.last().distance() + 1.0E-7
            ? new InterchangePlanner.ClearanceMouth(toArm, false)
            : null;
      }
   }

   static boolean sharedMouth(
      RoadGeometry.Mesh a,
      InterchangePlanner.ClearanceMouth ours,
      RoadGeometry.Mesh b,
      InterchangePlanner.ClearanceMouth other,
      InterchangePlanner.Leg host,
      RoadClearance.DetailedContact detail
   ) {
      if (ours != null && detail.coplanar()) {
         RoadClearance.Contact contact = detail.contact();
         double dot = RoadStructures.sample(a, (contact.from() + contact.to()) / 2.0)
            .left()
            .dot(RoadStructures.sample(b, (detail.otherFrom() + detail.otherTo()) / 2.0).left());
         if (host != null) {
            if (host.from() < 0 || host.from() % 2 != ours.arm() % 2) {
               return false;
            } else {
               return host.mesh().settings().style().ramp() ? (ours.entering() ? host.from() : host.to()) == ours.arm() && dot > 0.3 : Math.abs(dot) > 0.3;
            }
         } else {
            return other != null && ours.arm() == other.arm() && ours.entering() == other.entering() && dot > 0.3;
         }
      } else {
         return false;
      }
   }

   private boolean sharedMouthOnCarriageway(
      RoadGeometry.Mesh a,
      InterchangePlanner.ClearanceMouth ours,
      RoadGeometry.Mesh b,
      InterchangePlanner.ClearanceMouth other,
      InterchangePlanner.Leg host,
      RoadClearance.DetailedContact detail
   ) {
      if (!sharedMouth(a, ours, b, other, host, detail)) {
         return false;
      } else if (host != null && !host.mesh().settings().style().ramp()) {
         RoadClearance.Contact contact = detail.contact();
         RoadGeometry.Sample at = RoadQueries.horizontal(b, contact.ours()).sample();
         RoadGeometry.Sample sample = RoadStructures.sample(a, (contact.from() + contact.to()) / 2.0);
         if (this.options.preset.three && Math.abs(sample.left().dot(at.left())) < 0.85) {
            return false;
         } else {
            double[] sides = this.mouthSides
               .computeIfAbsent(a, k -> new IdentityHashMap<>())
               .computeIfAbsent(
                  b,
                  k -> {
                     RoadGeometry.Sample first = RoadQueries.horizontal(b, a.first().center()).sample();
                     RoadGeometry.Sample last = RoadQueries.horizontal(b, a.last().center()).sample();
                     return new double[]{
                        Math.signum(a.first().center().sub(first.center()).dot(first.left())),
                        Math.signum(a.last().center().sub(last.center()).dot(last.left()))
                     };
                  }
               );
            double side = sides[ours.entering() ? 0 : 1];
            return side != 0.0 && side * contact.ours().sub(at.center()).dot(at.left()) >= -1.0E-7;
         }
      } else {
         return true;
      }
   }

   static boolean sharedHosts(InterchangePlanner.Leg a, InterchangePlanner.Leg b, RoadClearance.DetailedContact detail) {
      if (a.from() >= 0 && b.from() >= 0 && a.from() % 2 == b.from() % 2 && detail.coplanar()) {
         boolean aCollector = a.mesh().settings().style().ramp();
         boolean bCollector = b.mesh().settings().style().ramp();
         if (!aCollector && !bCollector) {
            return false;
         } else {
            double dot = RoadStructures.sample(a.mesh(), (detail.contact().from() + detail.contact().to()) / 2.0)
               .left()
               .dot(RoadStructures.sample(b.mesh(), (detail.otherFrom() + detail.otherTo()) / 2.0).left());
            return aCollector && bCollector ? a.from() == b.from() && a.to() == b.to() && dot > 0.3 : Math.abs(dot) > 0.3;
         }
      } else {
         return false;
      }
   }

   private void validateExactClearance(List<RoadGeometry.Mesh> ramps) {
      for (int i = 0; i < this.roads.size(); i++) {
         for (int j = 0; j < i; j++) {
            RoadPlanningBudget.check();
            InterchangePlanner.Leg a = this.roads.get(i);
            InterchangePlanner.Leg b = this.roads.get(j);

            for (RoadClearance.DetailedContact detail : RoadClearance.detailedContacts(a.mesh(), b.mesh())) {
               if (!sharedHosts(a, b, detail)) {
                  this.exactClearance(a.name(), b.name(), detail.contact());
               }
            }
         }
      }

      for (int i = 0; i < this.routes.size(); i++) {
         RoadPlanningBudget.check();
         InterchangePlanner.Route route = this.routes.get(i);
         RoadGeometry.Mesh mesh = ramps.get(i);

         for (int k = 0; k <= this.roads.size() + i; k++) {
            InterchangePlanner.Leg host = k < this.roads.size() ? this.roads.get(k) : null;
            InterchangePlanner.Route other = host == null ? this.routes.get(k - this.roads.size()) : null;
            RoadGeometry.Mesh against = host == null ? ramps.get(k - this.roads.size()) : host.mesh();

            for (RoadClearance.DetailedContact detailx : k == this.roads.size() + i
               ? RoadClearance.detailedSelfContacts(mesh, this.options.width() * 2.0)
               : RoadClearance.detailedContacts(mesh, against)) {
               RoadClearance.Contact c = detailx.contact();
               if ((k != this.roads.size() + i || !(detailx.otherFrom() <= c.to() + this.options.width() * 2.0))
                  && !(c.usableClearance() + 1.0E-7 >= Math.max(this.options.clearance, c.required()))) {
                  InterchangePlanner.ClearanceMouth mouth = clearanceMouth(route.from, route.to, mesh, route.leadEnd, route.tailStart, c.from(), c.to());
                  InterchangePlanner.ClearanceMouth otherMouth = other == null
                     ? null
                     : clearanceMouth(other.from, other.to, against, other.leadEnd, other.tailStart, detailx.otherFrom(), detailx.otherTo());
                  if (!this.sharedMouthOnCarriageway(mesh, mouth, against, otherMouth, host, detailx)) {
                     this.exactClearance(route.name, host == null ? other.name : host.name(), c);
                  }
               }
            }
         }
      }
   }

   private void exactClearance(String ours, String other, RoadClearance.Contact contact) {
      double required = Math.max(this.options.clearance, contact.required());
      if (!(contact.usableClearance() + 1.0E-7 >= required)) {
         throw new IllegalArgumentException(
            String.format(
               Locale.ROOT,
               "%s 与%s精确路面净空不足：X=%.2f Z=%.2f，%s / 要求 %.2f 格；请扩大范围或调整预设",
               ours,
               other,
               contact.ours().x(),
               contact.ours().z(),
               RoadClearance.clearanceLabel(contact.usableClearance()),
               required
            )
         );
      }
   }

   private InterchangePlanner.Collision conflict(
      RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> mains, List<RoadGeometry.Mesh> previous, InterchangePlanner.Route route, int index
   ) {
      int mainCount = mains.size();
      List<RoadGeometry.Mesh> all = new ArrayList<>(mains);
      all.addAll(previous);

      for (int k = 0; k < all.size(); k++) {
         RoadPlanningBudget.check();
         RoadGeometry.Mesh other = all.get(k);

         for (int i = 0; i < mesh.samples().size(); i += 3) {
            if ((i & 63) == 0) {
               RoadPlanningBudget.check();
            }

            RoadGeometry.Sample p = mesh.samples().get(i);
            RoadQueries.Projection q = this.overlap(other, p);
            if (q != null) {
               double dy = p.center().y() - q.sample().center().y();
               if (!(Math.abs(dy) < 0.2) || !this.shared(route, i, p, k < mainCount ? null : this.routes.get(k - mainCount), k, other, q)) {
                  double need = this.options.clearance + (dy >= 0.0 ? mesh.settings().thickness() : other.settings().thickness());
                  if (Math.abs(dy) < need - 0.05) {
                     return new InterchangePlanner.Collision(
                        i, q.sample().center().y(), "与" + (k < mainCount ? this.roads.get(k).name : this.routes.get(k - mainCount).name) + "净高不足"
                     );
                  }
               }
            }
         }
      }

      return null;
   }

   private RoadQueries.Projection overlap(RoadGeometry.Mesh other, RoadGeometry.Sample p) {
      if (!(p.center().x() + p.halfWidth() < other.min().x())
         && !(p.center().x() - p.halfWidth() > other.max().x())
         && !(p.center().z() + p.halfWidth() < other.min().z())
         && !(p.center().z() - p.halfWidth() > other.max().z())) {
         RoadQueries.Projection q = RoadQueries.horizontal(other, p.center());
         double along = p.center().sub(q.sample().center()).dot(q.tangent().horizontalUnit());
         double extension = p.halfWidth() * Math.abs(p.left().dot(q.tangent().horizontalUnit()));
         if ((!(q.sample().distance() < 1.0E-6) || !(along < -extension)) && (!(q.sample().distance() > other.length() - 1.0E-6) || !(along > extension))) {
            double serviceInset = this.conformance == null && this.servicePreset() ? 0.3 : 0.0;
            return q.horizontalDistance() < p.halfWidth() + q.sample().halfWidth() - serviceInset ? q : null;
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private boolean shared(
      InterchangePlanner.Route r, int i, RoadGeometry.Sample p, InterchangePlanner.Route other, int k, RoadGeometry.Mesh mesh, RoadQueries.Projection q
   ) {
      double dot = p.left().dot(q.sample().left());
      int host = i <= r.leadEnd + 4 ? r.from % 2 : (i >= r.tailStart - 4 ? r.to % 2 : -1);
      if (host < 0) {
         return false;
      } else if (other == null) {
         return this.roads.get(k).from % 2 == host && Math.abs(dot) > 0.3;
      } else {
         int otherHost = q.sample().distance() <= mesh.samples().get(other.leadEnd).distance() + 2.0
            ? other.from % 2
            : (q.sample().distance() >= mesh.samples().get(other.tailStart).distance() - 2.0 ? other.to % 2 : -1);
         return host == otherHost && dot > 0.3;
      }
   }

   private static double cross(RoadGeometry.V a, RoadGeometry.V b) {
      return a.x() * b.z() - a.z() * b.x();
   }

   private InterchangePlanner() {
      throw new AssertionError();
   }

   static record ClearanceMouth(int arm, boolean entering) {
   }

   private static record Collision(int sample, double height, String reason) {
   }

   private static final class CornerRadiusFailure extends IllegalArgumentException {
      CornerRadiusFailure(String message) {
         super(message);
      }
   }

   private static record Knot(RoadGeometry.V p, RoadGeometry.V direction) {
   }

   public static record Leg(String name, int from, int to, RoadGeometry.Mesh mesh, JunctionPlanner.Ref junction, TollPlaza.Spec toll) {
      public Leg(String name, int from, int to, RoadGeometry.Mesh mesh) {
         this(name, from, to, mesh, null, null);
      }

      public Leg(String name, int from, int to, RoadGeometry.Mesh mesh, JunctionPlanner.Ref junction) {
         this(name, from, to, mesh, junction, null);
      }
   }

   public static record Levels(double first, double second, double difference, double required) {
   }

   public static record Options(
      InterchangePlanner.Preset preset,
      boolean leftTraffic,
      int lanes,
      double transition,
      double radius,
      double clearance,
      int upper,
      double rampWidth,
      boolean adjustEndpoints,
      boolean allowShrink,
      double maxLowering,
      int junctionAxis,
      int loopMask,
      boolean allowAbove,
      boolean flatConnector,
      double connectorHeight,
      DoubleTrumpetToll.Config toll
   ) {
      public static final InterchangePlanner.Options DEFAULT = new InterchangePlanner.Options(
         InterchangePlanner.Preset.CLOVERLEAF, false, 1, 96.0, 20.0, 5.0, 2
      );

      public Options(
         InterchangePlanner.Preset preset,
         boolean leftTraffic,
         int lanes,
         double transition,
         double radius,
         double clearance,
         int upper,
         double rampWidth,
         boolean adjustEndpoints,
         boolean allowShrink,
         double maxLowering,
         int junctionAxis,
         int loopMask,
         boolean allowAbove,
         boolean flatConnector,
         double connectorHeight
      ) {
         this(
            preset,
            leftTraffic,
            lanes,
            transition,
            radius,
            clearance,
            upper,
            rampWidth,
            adjustEndpoints,
            allowShrink,
            maxLowering,
            junctionAxis,
            loopMask,
            allowAbove,
            flatConnector,
            connectorHeight,
            DoubleTrumpetToll.Config.OFF
         );
      }

      public Options(
         InterchangePlanner.Preset preset,
         boolean leftTraffic,
         int lanes,
         double transition,
         double radius,
         double clearance,
         int upper,
         double rampWidth,
         boolean adjustEndpoints,
         boolean allowShrink,
         double maxLowering,
         int junctionAxis,
         int loopMask
      ) {
         this(
            preset,
            leftTraffic,
            lanes,
            transition,
            radius,
            clearance,
            upper,
            rampWidth,
            adjustEndpoints,
            allowShrink,
            maxLowering,
            junctionAxis,
            loopMask,
            false,
            false,
            0.0
         );
      }

      public Options(
         InterchangePlanner.Preset preset,
         boolean leftTraffic,
         int lanes,
         double transition,
         double radius,
         double clearance,
         int upper,
         double rampWidth,
         boolean adjustEndpoints,
         boolean allowShrink,
         double maxLowering,
         int junctionAxis
      ) {
         this(preset, leftTraffic, lanes, transition, radius, clearance, upper, rampWidth, adjustEndpoints, allowShrink, maxLowering, junctionAxis, 5);
      }

      public Options(
         InterchangePlanner.Preset preset,
         boolean leftTraffic,
         int lanes,
         double transition,
         double radius,
         double clearance,
         int upper,
         double rampWidth,
         boolean adjustEndpoints,
         boolean allowShrink,
         double maxLowering
      ) {
         this(preset, leftTraffic, lanes, transition, radius, clearance, upper, rampWidth, adjustEndpoints, allowShrink, maxLowering, 1);
      }

      public Options(
         InterchangePlanner.Preset preset,
         boolean leftTraffic,
         int lanes,
         double transition,
         double radius,
         double clearance,
         int upper,
         double rampWidth,
         boolean adjustEndpoints,
         boolean allowShrink
      ) {
         this(preset, leftTraffic, lanes, transition, radius, clearance, upper, rampWidth, adjustEndpoints, allowShrink, 8.0);
      }

      public Options(
         InterchangePlanner.Preset preset,
         boolean leftTraffic,
         int lanes,
         double transition,
         double radius,
         double clearance,
         int upper,
         double rampWidth,
         boolean adjustEndpoints
      ) {
         this(preset, leftTraffic, lanes, transition, radius, clearance, upper, rampWidth, adjustEndpoints, false);
      }

      public Options(InterchangePlanner.Preset preset, boolean leftTraffic, int lanes, double transition, double radius, double clearance, int upper) {
         this(preset, leftTraffic, lanes, transition, radius, clearance, upper, 0.0, false);
      }

      public Options(
         InterchangePlanner.Preset preset,
         boolean leftTraffic,
         int lanes,
         double transition,
         double radius,
         double clearance,
         int upper,
         double rampWidth,
         boolean adjustEndpoints,
         boolean allowShrink,
         double maxLowering,
         int junctionAxis,
         int loopMask,
         boolean allowAbove,
         boolean flatConnector,
         double connectorHeight,
         DoubleTrumpetToll.Config toll
      ) {
         toll = toll == null ? DoubleTrumpetToll.Config.OFF : toll;
         if (preset != InterchangePlanner.Preset.DOUBLE_TRUMPET) {
            toll = DoubleTrumpetToll.Config.OFF;
         }

         allowAbove = allowAbove && (preset == InterchangePlanner.Preset.TURBINE || preset == InterchangePlanner.Preset.HYBRID);
         flatConnector = flatConnector && preset == InterchangePlanner.Preset.DOUBLE_TRUMPET;
         if (Double.isFinite(connectorHeight) && connectorHeight == Math.rint(connectorHeight)) {
            if (loopMask >= 0
               && loopMask <= 15
               && Integer.bitCount(loopMask) == 2
               && junctionAxis >= 0
               && junctionAxis <= 1
               && preset != null
               && lanes >= 1
               && lanes <= 2
               && RoadGeometry.finite(transition, radius, clearance, rampWidth, maxLowering)
               && !(maxLowering < 0.0)
               && !(maxLowering > 64.0)
               && (rampWidth == 0.0 || !(rampWidth < (double)(lanes * 4)) && !(rampWidth > 32.0))
               && !(transition < 24.0)
               && !(transition > 192.0)
               && !(radius < 12.0)
               && !(radius > 64.0)
               && !(clearance < 4.0)
               && !(clearance > 12.0)
               && upper >= 1
               && upper <= 2) {
               this.preset = preset;
               this.leftTraffic = leftTraffic;
               this.lanes = lanes;
               this.transition = transition;
               this.radius = radius;
               this.clearance = clearance;
               this.upper = upper;
               this.rampWidth = rampWidth;
               this.adjustEndpoints = adjustEndpoints;
               this.allowShrink = allowShrink;
               this.maxLowering = maxLowering;
               this.junctionAxis = junctionAxis;
               this.loopMask = loopMask;
               this.allowAbove = allowAbove;
               this.flatConnector = flatConnector;
               this.connectorHeight = connectorHeight;
               this.toll = toll;
            } else {
               throw new IllegalArgumentException("立交参数无效：匝道宽度每车道至少 4 格、总宽不超过 32；过渡 24–192（实际至少 80）、半径 12–64、净高 4–12 格");
            }
         } else {
            throw new IllegalArgumentException("双喇叭水平段高度必须为整数");
         }
      }

      public double width() {
         return this.rampWidth == 0.0 ? (double)(this.lanes == 1 ? 5 : 9) : this.rampWidth;
      }

      public InterchangePlanner.Options adjust(boolean value) {
         return new InterchangePlanner.Options(
            this.preset,
            this.leftTraffic,
            this.lanes,
            this.transition,
            this.radius,
            this.clearance,
            this.upper,
            this.rampWidth,
            value,
            this.allowShrink,
            this.maxLowering,
            this.junctionAxis,
            this.loopMask,
            this.allowAbove,
            this.flatConnector,
            this.connectorHeight,
            this.toll
         );
      }

      public InterchangePlanner.Options shrink(boolean value) {
         return new InterchangePlanner.Options(
            this.preset,
            this.leftTraffic,
            this.lanes,
            this.transition,
            this.radius,
            this.clearance,
            this.upper,
            this.rampWidth,
            this.adjustEndpoints || value,
            value,
            this.maxLowering,
            this.junctionAxis,
            this.loopMask,
            this.allowAbove,
            this.flatConnector,
            this.connectorHeight,
            this.toll
         );
      }

      public InterchangePlanner.Options lowering(double value) {
         return new InterchangePlanner.Options(
            this.preset,
            this.leftTraffic,
            this.lanes,
            this.transition,
            this.radius,
            this.clearance,
            this.upper,
            this.rampWidth,
            this.adjustEndpoints,
            this.allowShrink,
            value,
            this.junctionAxis,
            this.loopMask,
            this.allowAbove,
            this.flatConnector,
            this.connectorHeight,
            this.toll
         );
      }

      public InterchangePlanner.Options junction(int axis) {
         return new InterchangePlanner.Options(
            this.preset,
            this.leftTraffic,
            this.lanes,
            this.transition,
            this.radius,
            this.clearance,
            this.upper,
            this.rampWidth,
            this.adjustEndpoints,
            this.allowShrink,
            this.maxLowering,
            axis,
            this.loopMask,
            this.allowAbove,
            this.flatConnector,
            this.connectorHeight,
            this.toll
         );
      }

      public InterchangePlanner.Options loops(int mask) {
         return new InterchangePlanner.Options(
            this.preset,
            this.leftTraffic,
            this.lanes,
            this.transition,
            this.radius,
            this.clearance,
            this.upper,
            this.rampWidth,
            this.adjustEndpoints,
            this.allowShrink,
            this.maxLowering,
            this.junctionAxis,
            mask,
            this.allowAbove,
            this.flatConnector,
            this.connectorHeight,
            this.toll
         );
      }

      public boolean aboveAvailable() {
         return this.preset == InterchangePlanner.Preset.TURBINE || this.preset == InterchangePlanner.Preset.HYBRID;
      }

      public InterchangePlanner.Options above(boolean value) {
         return new InterchangePlanner.Options(
            this.preset,
            this.leftTraffic,
            this.lanes,
            this.transition,
            this.radius,
            this.clearance,
            this.upper,
            this.rampWidth,
            this.adjustEndpoints,
            this.allowShrink,
            this.maxLowering,
            this.junctionAxis,
            this.loopMask,
            value,
            this.flatConnector,
            this.connectorHeight,
            this.toll
         );
      }

      public InterchangePlanner.Options connector(boolean flat, double height) {
         return new InterchangePlanner.Options(
            this.preset,
            this.leftTraffic,
            this.lanes,
            this.transition,
            this.radius,
            this.clearance,
            this.upper,
            this.rampWidth,
            this.adjustEndpoints,
            this.allowShrink,
            this.maxLowering,
            this.junctionAxis,
            this.loopMask,
            this.allowAbove,
            flat,
            height,
            this.toll
         );
      }

      public InterchangePlanner.Options toll(DoubleTrumpetToll.Config value) {
         return new InterchangePlanner.Options(
            this.preset,
            this.leftTraffic,
            this.lanes,
            this.transition,
            this.radius,
            this.clearance,
            this.upper,
            this.rampWidth,
            this.adjustEndpoints,
            this.allowShrink,
            this.maxLowering,
            this.junctionAxis,
            this.loopMask,
            this.allowAbove,
            this.flatConnector,
            this.connectorHeight,
            value
         );
      }
   }

   public static record Plan(
      List<InterchangePlanner.Leg> legs, int movements, double minRadius, double highest, RoadGeometry.V center, List<RoadGeometry.Node> anchors
   ) {
      public Plan(List<InterchangePlanner.Leg> legs, int movements, double minRadius, double highest, RoadGeometry.V center) {
         this(legs, movements, minRadius, highest, center, List.of());
      }
   }

   public static enum Preset {
      CLOVERLEAF("苜蓿叶 · 四向", false, 2),
      TURBINE("涡轮 · 四向", false, 5),
      STACK("堆栈 · 四向", false, 3),
      HYBRID("苜蓿叶＋定向 · 四向", false, 3),
      TRUMPET("喇叭 · 三向", true, 3),
      DIRECTIONAL_T("定向 T · 三向", true, 3),
      Y("定向 Y · 三向", true, 3),
      DIAMOND("菱形 · 四向", false, 1),
      PARCLO("部分苜蓿叶 · 四向", false, 2),
      DOUBLE_TRUMPET("双喇叭 · 四向", false, 3),
      ROUNDABOUT("三层环形 · 四向", false, 2),
      SPUI("单点式菱形 · 四向", false, 1),
      DIRECTIONAL_FIVE("全定向堆栈 · 五向", false, 3),
      DIRECTIONAL_SIX("全定向堆栈 · 六向", false, 3);

      public final String label;
      public final boolean three;
      public final int heightSlots;

      public boolean supported() {
         return this != PARCLO;
      }

      public boolean multi() {
         return this == DIRECTIONAL_FIVE || this == DIRECTIONAL_SIX;
      }

      private Preset(String label, boolean three, int heightSlots) {
         this.label = label;
         this.three = three;
         this.heightSlots = heightSlots;
      }
   }

   private static final class Route {
      String name;
      int from;
      int to;
      int leadEnd;
      int tailStart;
      int priority;
      int leadCut;
      int tailCut;
      List<RoadGeometry.V> points;
      List<Double> widths;
      int plateauStart = -1;
      int plateauEnd = -1;
      double layer;

      Route(String n, int f, int t, List<RoadGeometry.V> p, List<Double> w, int a, int b, int priority) {
         this.priority = priority;
         this.name = n;
         this.from = f;
         this.to = t;
         this.points = p;
         this.widths = w;
         this.leadEnd = this.leadCut = a;
         this.tailStart = this.tailCut = b;
      }
   }
}
