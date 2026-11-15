package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public final class RoadGeometry {
   public static boolean finite(double... values) {
      for (double d : values) {
         if (!Double.isFinite(d)) {
            return false;
         }
      }

      return true;
   }

   public static RoadGeometry.Mesh endCaps(RoadGeometry.Mesh mesh, int mask) {
      return endCaps(mesh, mask, 0.0, 0.0);
   }

   public static RoadGeometry.Mesh endCaps(RoadGeometry.Mesh mesh, int mask, double startGrade, double endGrade) {
      if (mask != 0 && !mesh.closed()) {
         List<RoadGeometry.Sample> points = new ArrayList<>(mesh.samples());

         for (boolean first : new boolean[]{true, false}) {
            if ((mask & (first ? 1 : 2)) != 0) {
               RoadGeometry.Sample p = first ? mesh.first() : mesh.last();
               RoadGeometry.V forward = p.left().left().mul(first ? 1.0 : -1.0);
               boolean xAxis = Math.abs(forward.x()) > 0.999999;
               boolean zAxis = Math.abs(forward.z()) > 0.999999;
               if (xAxis || zAxis) {
                  double value = xAxis ? p.center().x() : p.center().z();
                  double sign = xAxis ? forward.x() : forward.z();
                  double edge = sign > 0.0 ? Math.ceil(value - 1.0E-7) : Math.floor(value + 1.0E-7);
                  double amount = Math.abs(edge - value);
                  if (!(amount < 1.0E-6) && !(amount > 1.0)) {
                     double grade = first ? -startGrade : endGrade;
                     RoadGeometry.V center = p.center().add(forward.mul(amount)).add(new RoadGeometry.V(0.0, grade * amount, 0.0));
                     RoadGeometry.Sample cap = new RoadGeometry.Sample(center, p.left(), 0.0, p.halfWidth());
                     if (first) {
                        points.add(0, cap);
                     } else {
                        points.add(cap);
                     }
                  }
               }
            }
         }

         List<RoadGeometry.Sample> out = new ArrayList<>();
         RoadGeometry.V min = mesh.min();
         RoadGeometry.V max = mesh.max();
         RoadGeometry.V prior = null;
         double distance = 0.0;

         for (RoadGeometry.Sample p : points) {
            if (prior != null) {
               distance += p.center().distance(prior);
            }

            out.add(new RoadGeometry.Sample(p.center(), p.left(), distance, p.halfWidth()));
            prior = p.center();

            for (int side : new int[]{-1, 1}) {
               RoadGeometry.V edge = p.at((double)side * p.halfWidth(), 0.0);
               min = new RoadGeometry.V(Math.min(min.x(), edge.x()), Math.min(min.y(), edge.y() - mesh.settings().thickness()), Math.min(min.z(), edge.z()));
               max = new RoadGeometry.V(Math.max(max.x(), edge.x()), Math.max(max.y(), edge.y()), Math.max(max.z(), edge.z()));
            }
         }

         return new RoadGeometry.Mesh(List.copyOf(out), mesh.settings(), min, max, distance, false, mesh.controlPoint(), mesh.controls());
      } else {
         return mesh;
      }
   }

   private static void validate(RoadGeometry.Node n) {
      if (!finite(n.position.x, n.position.y, n.position.z, n.yaw, n.grade) || Math.abs(n.grade) > 0.5) {
         throw new IllegalArgumentException("端点数值无效，坡度范围为 -50%–50%");
      } else if (Math.abs(n.position.x) > 2.9999E7 || Math.abs(n.position.z) > 2.9999E7) {
         throw new IllegalArgumentException("超出世界边界");
      }
   }

   public static RoadGeometry.Mesh build(RoadGeometry.Node a, RoadGeometry.Node b, RoadGeometry.Settings s) {
      if (s.options().attachments().points().isEmpty()) {
         return buildBase(a, b, s);
      } else {
         RoadGeometry.Settings plain = s.options(s.options().attachments(RoadAttachments.Data.EMPTY));
         return RoadAttachments.deform(buildBase(a, b, plain), s);
      }
   }

   private static RoadGeometry.Mesh buildBase(RoadGeometry.Node a, RoadGeometry.Node b, RoadGeometry.Settings s) {
      if (s.mode() == RoadGeometry.Mode.AUTO) {
         return RoadPlanner.plan(RoadPlanner.Hint.free(a), RoadPlanner.Hint.free(b), s).mesh();
      } else {
         validate(a);
         validate(b);
         s.validate();
         RoadGeometry.V delta = b.position.sub(a.position);
         double chord = delta.horizontalLength();
         if (!(chord < 2.0) && !(chord > 2048.0)) {
            RoadGeometry.Path path = new RoadGeometry.Path(a, b, s);
            double estimate = 0.0;
            RoadGeometry.V last = path.point(0.0);

            for (int i = 1; i <= 128; i++) {
               RoadGeometry.V p = path.point((double)i / 128.0);
               estimate += p.distance(last);
               last = p;
            }

            if (estimate > 4096.0) {
               throw new IllegalArgumentException("单段道路曲线总长不能超过 4096 格，请增加中间端点");
            } else {
               int count = Math.max(8, (int)Math.ceil(estimate / 0.4));
               if (s.mode == RoadGeometry.Mode.RING && count % 2 != 0) {
                  count++;
               }

               List<RoadGeometry.Sample> samples = new ArrayList<>();
               double distance = 0.0;
               RoadGeometry.V min = new RoadGeometry.V(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
               RoadGeometry.V max = new RoadGeometry.V(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE);
               RoadGeometry.Sample prev = null;
               SortedSet<Double> parameters = new TreeSet<>();

               for (int i = 0; i <= count; i++) {
                  parameters.add(Double.valueOf((double)i / (double)count));
               }

               if (path.through != null) {
                  path.through.knots(parameters);
               }

               if (s.laneRamp()) {
                  parameters.add(Double.valueOf(s.startEdge(chord)));
                  parameters.add(Double.valueOf(1.0 - s.endEdge(chord)));
               }

               if (s.options().liftHeight() != 0.0) {
                  parameters.add(Double.valueOf(s.liftParameter(chord)));
               }

               if (RoadVertical.automatic(s)) {
                  parameters.add(Double.valueOf(RoadVertical.rising(s) ? 0.3 : 0.45));
                  parameters.add(Double.valueOf(0.5));
                  parameters.add(Double.valueOf(RoadVertical.rising(s) ? 0.7 : 0.55));
               }

               boolean fitGrid = !s.style().ramp() && RoadProfile.modern(s.style()) && s.options().routing().fitEdges() && s.mode() != RoadGeometry.Mode.RING;
               double firstPadding = fitGrid && s.options().ends().start() == null
                  ? gridPadding(path.point(0.0), path.tangent(0.0), s.widthAt(0.0, chord) / 2.0)
                  : 0.0;
               double endPadding = fitGrid && s.options().ends().end() == null
                  ? gridPadding(path.point(1.0), path.tangent(1.0), s.widthAt(1.0, chord) / 2.0)
                  : 0.0;

               for (double t : parameters) {
                  RoadGeometry.V p = path.point(t);
                  RoadGeometry.V tangent = path.tangent(t);
                  RoadGeometry.V left = tangent.horizontalUnit().left();
                  double half = s.widthAt(t, chord) / 2.0;
                  if (fitGrid) {
                     double blend = RoadGeometry.Settings.smooth(t);
                     half += firstPadding * (1.0 - blend) + endPadding * blend;
                  }

                  if (prev != null) {
                     if (prev.left().dot(left) < 0.0) {
                        throw new IllegalArgumentException("道路出现急剧折返，请拉远端点或切换智能选线");
                     }

                     RoadGeometry.V d = p.sub(prev.center);
                     double h = d.horizontalLength();
                     if (h < 1.0E-7 || Math.abs(d.y) / h > 0.505) {
                        throw new IllegalArgumentException("道路坡度超过 50%，请拉长道路或调整端点坡度");
                     }

                     if (Math.abs(d.y) / h > RoadVertical.limit(s) + 1.0E-4) {
                        throw new IllegalArgumentException(String.format(Locale.ROOT, "桥隧坡度超过 %.0f%%；请降低高差、延长道路或开启端点调整", RoadVertical.limit(s) * 100.0));
                     }

                     if (RoadTunnel.dipping(s) && p.y() < RoadTunnel.minimum(a, b, s) - 1.0E-6) {
                        throw new IllegalArgumentException("端点坡度使道路越过最低点；请减小端点坡度");
                     }

                     if (RoadVertical.rising(s) && p.y() > RoadVertical.maximum(a, b, s) + 1.0E-6) {
                        throw new IllegalArgumentException("端点坡度使桥面越过设定最高点；请减小端点坡度");
                     }

                     for (int side : new int[]{-1, 1}) {
                        RoadGeometry.V edge = p.add(left.mul((double)side * half)).sub(prev.at((double)side * prev.halfWidth(), 0.0));
                        if (edge.dot(d) <= 0.0) {
                           throw new IllegalArgumentException("弯道半径过小，道路内侧重叠；请减小宽度或放大弯道");
                        }
                     }

                     distance += p.distance(prev.center);
                  }

                  RoadGeometry.Sample sample = new RoadGeometry.Sample(p, left, distance, half);
                  samples.add(sample);
                  prev = sample;

                  for (int sidex : new int[]{-1, 1}) {
                     RoadGeometry.V edge = sample.at((double)sidex * half, 0.0);
                     min = new RoadGeometry.V(Math.min(min.x, edge.x), Math.min(min.y, edge.y - s.thickness), Math.min(min.z, edge.z));
                     max = new RoadGeometry.V(Math.max(max.x, edge.x), Math.max(max.y, edge.y), Math.max(max.z, edge.z));
                  }
               }

               count = samples.size() - 1;
               if (s.mode() != RoadGeometry.Mode.STRAIGHT) {
                  Map<Long, List<Integer>> bins = new HashMap<>();

                  for (int j = 0; j < count; j++) {
                     RoadGeometry.V r = samples.get(j).center;
                     RoadGeometry.V u = samples.get(j + 1).center;
                     int x0 = (int)Math.floor(Math.min(r.x, u.x) / 8.0);
                     int x1 = (int)Math.floor(Math.max(r.x, u.x) / 8.0);
                     int z0 = (int)Math.floor(Math.min(r.z, u.z) / 8.0);
                     int z1 = (int)Math.floor(Math.max(r.z, u.z) / 8.0);
                     Set<Integer> candidates = new HashSet<>();

                     for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                           candidates.addAll(bins.getOrDefault((long)x << 32 ^ (long)z & 4294967295L, List.of()));
                        }
                     }

                     for (int i : candidates) {
                        if (j >= i + 2 && (s.mode != RoadGeometry.Mode.RING || i != 0 || j != count - 1)) {
                           RoadGeometry.V px = samples.get(i).center;
                           RoadGeometry.V q = samples.get(i + 1).center;
                           double cross = cross(q.sub(px), u.sub(r));
                           if (!(Math.abs(cross) < 1.0E-9)) {
                              double t = cross(r.sub(px), u.sub(r)) / cross;
                              double v = cross(r.sub(px), q.sub(px)) / cross;
                              if (t > 1.0E-6
                                 && t < 0.999999
                                 && v > 1.0E-6
                                 && v < 0.999999
                                 && Math.abs(px.y + (q.y - px.y) * t - r.y - (u.y - r.y) * v) < s.thickness + 2.0) {
                                 throw new IllegalArgumentException("道路在同一高度自相交，请拆分为独立路段");
                              }
                           }
                        }
                     }

                     for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                           bins.computeIfAbsent((long)x << 32 ^ (long)z & 4294967295L, k -> new ArrayList<>()).add(j);
                        }
                     }
                  }
               }

               if (s.options().ends().equals(RoadTransitions.Ends.NONE)) {
                  return new RoadGeometry.Mesh(
                     List.copyOf(samples),
                     s,
                     min,
                     max,
                     distance,
                     s.mode == RoadGeometry.Mode.RING,
                     path.point(s.liftParameter(chord)),
                     heightControls(path, s, chord)
                  );
               } else {
                  List<RoadGeometry.Sample> tapered = new ArrayList<>();

                  for (RoadGeometry.Sample px : samples) {
                     tapered.add(new RoadGeometry.Sample(px.center(), px.left(), px.distance(), RoadTransitions.width(s, px.distance(), distance) / 2.0));
                  }

                  RoadGeometry.Mesh ribbon = RoadRibbon.mesh(tapered, s);
                  return new RoadGeometry.Mesh(
                     ribbon.samples(),
                     s,
                     ribbon.min(),
                     ribbon.max(),
                     ribbon.length(),
                     s.mode == RoadGeometry.Mode.RING,
                     path.point(s.liftParameter(chord)),
                     heightControls(path, s, chord)
                  );
               }
            }
         } else {
            throw new IllegalArgumentException("端点水平距离需为 2–2048 格");
         }
      }
   }

   private static List<RoadGeometry.V> heightControls(RoadGeometry.Path path, RoadGeometry.Settings s, double chord) {
      return List.of(path.point(s.liftParameter(chord)));
   }

   public static RoadGeometry.Mesh controls(RoadGeometry.Mesh original, RoadGeometry.Mesh changed) {
      List<RoadGeometry.V> points = original.controls().stream().map(p -> {
         RoadQueries.Projection q = RoadQueries.horizontal(changed, p);
         return new RoadGeometry.V(p.x(), q.sample().center().y(), p.z());
      }).toList();
      return new RoadGeometry.Mesh(
         changed.samples(),
         changed.settings(),
         changed.min(),
         changed.max(),
         changed.length(),
         changed.closed(),
         points.isEmpty() ? null : points.get(0),
         points
      );
   }

   private static double gridPadding(RoadGeometry.V p, RoadGeometry.V tangent, double half) {
      RoadGeometry.V side = tangent.horizontalUnit().left();
      boolean ns = Math.abs(side.x()) > 0.999999;
      boolean ew = Math.abs(side.z()) > 0.999999;
      double coordinate = ns ? p.x() : p.z();
      return (ns || ew) && Math.abs(coordinate - Math.floor(coordinate) - 0.5) < 1.0E-7 ? Math.ceil(coordinate + half - 1.0E-7) - coordinate - half : 0.0;
   }

   private static double cross(RoadGeometry.V a, RoadGeometry.V b) {
      return a.x * b.z - a.z * b.x;
   }

   private static double elevation(double t, double a, double b, double ma, double mb) {
      if (a == b && ma == 0.0 && mb == 0.0) {
         return a;
      } else {
         double t2 = t * t;
         double t3 = t2 * t;
         return (2.0 * t3 - 3.0 * t2 + 1.0) * a + (t3 - 2.0 * t2 + t) * ma + (-2.0 * t3 + 3.0 * t2) * b + (t3 - t2) * mb;
      }
   }

   private RoadGeometry() {
   }

   public static record Mesh(
      List<RoadGeometry.Sample> samples,
      RoadGeometry.Settings settings,
      RoadGeometry.V min,
      RoadGeometry.V max,
      double length,
      boolean closed,
      RoadGeometry.V controlPoint,
      List<RoadGeometry.V> controls,
      RoadGeometry.Mesh reference,
      RoadSlicing.Geometry slicing
   ) {
      public Mesh(
         List<RoadGeometry.Sample> samples,
         RoadGeometry.Settings settings,
         RoadGeometry.V min,
         RoadGeometry.V max,
         double length,
         boolean closed,
         RoadGeometry.V point,
         List<RoadGeometry.V> controls,
         RoadGeometry.Mesh reference
      ) {
         this(samples, settings, min, max, length, closed, point, controls, reference, null);
      }

      public Mesh(
         List<RoadGeometry.Sample> samples,
         RoadGeometry.Settings settings,
         RoadGeometry.V min,
         RoadGeometry.V max,
         double length,
         boolean closed,
         RoadGeometry.V point,
         List<RoadGeometry.V> controls
      ) {
         this(samples, settings, min, max, length, closed, point, controls, null);
      }

      public Mesh(
         List<RoadGeometry.Sample> samples,
         RoadGeometry.Settings settings,
         RoadGeometry.V min,
         RoadGeometry.V max,
         double length,
         boolean closed,
         RoadGeometry.V point
      ) {
         this(samples, settings, min, max, length, closed, point, point == null ? List.of() : List.of(point));
      }

      public Mesh(List<RoadGeometry.Sample> samples, RoadGeometry.Settings settings, RoadGeometry.V min, RoadGeometry.V max, double length, boolean closed) {
         this(samples, settings, min, max, length, closed, null);
      }

      public RoadGeometry.Sample first() {
         return this.samples.get(0);
      }

      public RoadGeometry.Sample last() {
         return this.samples.get(this.samples.size() - 1);
      }
   }

   public static enum Mode {
      STRAIGHT,
      CURVE,
      ARC,
      RING,
      AUTO;
   }

   public static record Node(RoadGeometry.V position, double yaw, double grade) {
      public Node(RoadGeometry.V position, double yaw, double grade) {
         yaw = yaw == 0.0 ? 0.0 : yaw;
         grade = grade == 0.0 ? 0.0 : grade;
         this.position = position;
         this.yaw = yaw;
         this.grade = grade;
      }

      public RoadGeometry.V direction() {
         double r = Math.toRadians(this.yaw);
         return new RoadGeometry.V(-Math.sin(r), 0.0, Math.cos(r));
      }
   }

   private static final class Path {
      final RoadGeometry.Node a;
      final RoadGeometry.Node b;
      final RoadGeometry.Settings s;
      final RoadGeometry.V delta;
      final RoadGeometry.V center;
      final RoadGeometry.V radius;
      final double length;
      final double angle;
      final double chord;
      final RoadLoop loop;
      final List<RoadGeometry.V> bezier;
      final RoadThroughCurve through;

      Path(RoadGeometry.Node a, RoadGeometry.Node b, RoadGeometry.Settings s) {
         this.a = a;
         this.b = b;
         this.s = s;
         this.delta = b.position.sub(a.position);
         this.chord = this.delta.horizontalLength();
         this.angle = s.mode == RoadGeometry.Mode.RING ? Math.PI * 2 : Math.toRadians(s.arcDegrees);
         RoadGeometry.V middle = a.position.add(b.position).mul(0.5);
         this.center = s.mode == RoadGeometry.Mode.ARC
            ? middle.add(this.delta.horizontalUnit().left().mul(this.chord / (2.0 * Math.tan(this.angle / 2.0))))
            : middle;
         this.radius = a.position.sub(this.center);
         this.length = s.mode != RoadGeometry.Mode.ARC && s.mode != RoadGeometry.Mode.RING ? this.chord : Math.abs(this.angle) * this.radius.horizontalLength();
         RoadProfile.Routing route = s.options().routing();
         this.through = route.through() && !route.controls().isEmpty() ? new RoadThroughCurve(a, b, s) : null;
         if (this.through == null || s.options().liftHeight() == 0.0 && !RoadVertical.automatic(s)) {
            if (!route.controls().isEmpty() && this.through == null) {
               ArrayList<RoadGeometry.V> points = new ArrayList<>();
               points.add(a.position());
               double ha = Math.min(this.chord * s.tension(), a.position().distance(route.controls().get(0).position()) * 0.5);
               double hb = Math.min(this.chord * s.tension(), b.position().distance(route.controls().get(route.controls().size() - 1).position()) * 0.5);
               points.add(a.position().add(a.direction().mul(ha)).add(new RoadGeometry.V(0.0, a.grade() * ha, 0.0)));

               for (RoadProfile.Control c : route.controls()) {
                  if (c.position().distance(a.position()) > 2048.0 || c.position().distance(b.position()) > 2048.0) {
                     throw new IllegalArgumentException("控制点距离端点不能超过2048格");
                  }

                  points.add(c.position());
               }

               points.add(b.position().sub(b.direction().mul(hb)).add(new RoadGeometry.V(0.0, -b.grade() * hb, 0.0)));
               points.add(b.position());
               this.bezier = List.copyOf(points);
            } else {
               this.bezier = List.of();
            }

            if (s.laneRamp() && (route.kind() == RoadProfile.RouteKind.LOOP_LEFT || route.kind() == RoadProfile.RouteKind.LOOP_RIGHT)) {
               if (route.radius() <= s.width() / 2.0 + 1.0) {
                  throw new IllegalArgumentException("环绕半径需大于路宽的一半加 1 格");
               }

               RoadGeometry.V aa = a.position.add(a.direction().mul(s.startLead())).add(this.portShift(true));
               RoadGeometry.V bb = b.position.sub(b.direction().mul(s.endLead())).add(this.portShift(false));
               this.loop = new RoadLoop(aa, bb, a.direction(), b.direction(), route.radius(), route.kind() == RoadProfile.RouteKind.LOOP_RIGHT);
            } else {
               this.loop = null;
            }
         } else {
            throw new IllegalArgumentException("经过点已指定高程，请关闭中间抬升及自动桥隧纵坡");
         }
      }

      private RoadGeometry.V portShift(boolean first) {
         double side = this.s.taperVersion() >= 3
            ? (double)RoadProfile.trafficSign(this.s.options().leftTraffic())
            : (this.s.options().leftTraffic() ? 1.0 : -1.0);
         double width = first ? this.s.startWidth() : this.s.endWidth();
         double outset = first ? this.s.options().ports().sourceOutset() : this.s.options().ports().targetOutset();
         return (first ? this.a : this.b)
            .direction()
            .left()
            .mul(
               this.s.separatedPort(first)
                  ? side * ((this.s.width() - width) / 2.0 + outset)
                  : (this.s.newRamp() && width < 1.0 ? side * (this.s.width() - width) / 2.0 : 0.0)
            );
      }

      private RoadGeometry.V rampPoint(double t) {
         double span = this.chord + this.s.startLead() + this.s.endLead();
         double edge = this.s.startEdge(this.chord);
         double endEdge = this.s.endEdge(this.chord);
         RoadGeometry.V da = new RoadGeometry.V(this.a.direction().x(), this.a.grade(), this.a.direction().z());
         RoadGeometry.V db = new RoadGeometry.V(this.b.direction().x(), this.b.grade(), this.b.direction().z());
         RoadGeometry.V shiftA = this.portShift(true);
         RoadGeometry.V shiftB = this.portShift(false);
         if (t <= edge) {
            return this.a.position.add(da.mul(t * span)).add(shiftA.mul(this.s.portBlend(t, this.chord, true)));
         } else if (t >= 1.0 - endEdge) {
            return this.b.position.sub(db.mul((1.0 - t) * span)).add(shiftB.mul(this.s.portBlend(t, this.chord, false)));
         } else {
            RoadGeometry.V aa = this.a.position.add(da.mul(this.s.startLead())).add(shiftA);
            RoadGeometry.V bb = this.b.position.sub(db.mul(this.s.endLead())).add(shiftB);
            double v = (t - edge) / (1.0 - edge - endEdge);
            double u = 1.0 - v;
            double handle = aa.sub(bb).horizontalLength() * this.s.tension;
            RoadGeometry.V c1 = aa.add(this.a.direction().mul(handle));
            RoadGeometry.V c2 = bb.sub(this.b.direction().mul(handle));
            RoadGeometry.V h = aa.mul(u * u * u).add(c1.mul(3.0 * u * u * v)).add(c2.mul(3.0 * u * v * v)).add(bb.mul(v * v * v));
            double derivative = handle * 3.0;
            if (this.loop != null) {
               h = this.loop.point(v);
               derivative = this.loop.length();
            }

            RoadProfile.Routing route = this.s.options().routing();
            double control = this.s.options().liftPosition();
            double bump = RoadGeometry.Settings.smooth(v <= control ? v / control : (1.0 - v) / (1.0 - control));
            h = h.add(new RoadGeometry.V(route.offsetX() * bump, 0.0, route.offsetZ() * bump));
            return new RoadGeometry.V(h.x, RoadGeometry.elevation(v, aa.y, bb.y, this.a.grade * derivative, this.b.grade * derivative), h.z);
         }
      }

      RoadGeometry.V horizontal(double t) {
         if (this.s.laneRamp()) {
            return this.rampPoint(t);
         } else {
            return switch (this.s.mode) {
               case STRAIGHT -> this.a.position.add(this.delta.mul(t));
               case CURVE -> {
                  double u = 1.0 - t;
                  RoadGeometry.V c1 = this.a.position.add(this.a.direction().mul(this.chord * this.s.tension));
                  RoadGeometry.V c2 = this.b.position.sub(this.b.direction().mul(this.chord * this.s.tension));
                  yield this.a.position.mul(u * u * u).add(c1.mul(3.0 * u * u * t)).add(c2.mul(3.0 * u * t * t)).add(this.b.position.mul(t * t * t));
               }
               case ARC, RING -> {
                  double c = Math.cos(this.angle * t);
                  double sn = Math.sin(this.angle * t);
                  yield new RoadGeometry.V(this.center.x + this.radius.x * c - this.radius.z * sn, 0.0, this.center.z + this.radius.x * sn + this.radius.z * c);
               }
               case AUTO -> throw new IllegalStateException("AUTO must be resolved before sampling");
            };
         }
      }

      private static RoadGeometry.V evaluate(List<RoadGeometry.V> points, double t) {
         ArrayList<RoadGeometry.V> work = new ArrayList<>(points);

         for (int n = work.size() - 1; n > 0; n--) {
            for (int i = 0; i < n; i++) {
               work.set(i, work.get(i).mul(1.0 - t).add(work.get(i + 1).mul(t)));
            }
         }

         return work.get(0);
      }

      RoadGeometry.V tangent(double t) {
         if (this.through != null) {
            return this.through.tangent(t);
         } else if (!this.bezier.isEmpty()) {
            ArrayList<RoadGeometry.V> derivative = new ArrayList<>();

            for (int i = 1; i < this.bezier.size(); i++) {
               derivative.add(this.bezier.get(i).sub(this.bezier.get(i - 1)).mul((double)(this.bezier.size() - 1)));
            }

            return evaluate(derivative, t);
         } else if (!this.s.laneRamp()) {
            return switch (this.s.mode) {
               case STRAIGHT -> this.delta;
               case CURVE -> {
                  RoadGeometry.V c1 = this.a.position.add(this.a.direction().mul(this.chord * this.s.tension));
                  RoadGeometry.V c2 = this.b.position.sub(this.b.direction().mul(this.chord * this.s.tension));
                  double u = 1.0 - t;
                  yield c1.sub(this.a.position).mul(3.0 * u * u).add(c2.sub(c1).mul(6.0 * u * t)).add(this.b.position.sub(c2).mul(3.0 * t * t));
               }
               case ARC, RING -> {
                  RoadGeometry.V r = this.horizontal(t).sub(this.center);
                  yield new RoadGeometry.V(-r.z * this.angle, 0.0, r.x * this.angle);
               }
               case AUTO -> throw new IllegalStateException("AUTO must be resolved before sampling");
            };
         } else if (this.s.newRamp() && (this.s.startWidth() < 1.0 || this.s.separatedPort(true)) && t <= this.s.startEdge(this.chord)) {
            return this.a.direction();
         } else if (this.s.newRamp() && (this.s.endWidth() < 1.0 || this.s.separatedPort(false)) && t >= 1.0 - this.s.endEdge(this.chord)) {
            return this.b.direction();
         } else if (t < 1.0E-7) {
            return this.a.direction();
         } else {
            return t > 0.9999999 ? this.b.direction() : this.rampPoint(Math.min(1.0, t + 1.0E-6)).sub(this.rampPoint(Math.max(0.0, t - 1.0E-6)));
         }
      }

      RoadGeometry.V point(double t) {
         if (this.through != null) {
            return this.through.point(t);
         } else if (!this.bezier.isEmpty()) {
            return evaluate(this.bezier, t).add(new RoadGeometry.V(0.0, this.s.liftAt(t, this.chord), 0.0));
         } else if (this.s.laneRamp()) {
            return this.rampPoint(t).add(new RoadGeometry.V(0.0, this.s.liftAt(t, this.chord), 0.0));
         } else {
            RoadGeometry.V h = this.horizontal(t);
            double y;
            if (this.s.mode == RoadGeometry.Mode.RING) {
               y = t <= 0.5
                  ? RoadGeometry.elevation(t * 2.0, this.a.position.y, this.b.position.y, this.a.grade * this.length / 2.0, this.b.grade * this.length / 2.0)
                  : RoadGeometry.elevation(
                     t * 2.0 - 1.0, this.b.position.y, this.a.position.y, this.b.grade * this.length / 2.0, this.a.grade * this.length / 2.0
                  );
            } else {
               double scaleA = this.s.mode == RoadGeometry.Mode.CURVE ? this.tangent(0.0).horizontalLength() : this.length;
               double scaleB = this.s.mode == RoadGeometry.Mode.CURVE ? this.tangent(1.0).horizontalLength() : this.length;
               y = RoadVertical.automatic(this.s)
                  ? RoadVertical.height(t, this.a, this.b, this.s, scaleA, scaleB)
                  : RoadGeometry.elevation(t, this.a.position.y, this.b.position.y, this.a.grade * scaleA, this.b.grade * scaleB);
            }

            return new RoadGeometry.V(h.x, y + this.s.liftAt(t, this.chord), h.z);
         }
      }
   }

   public static enum RampTurn {
      LEGACY,
      AUTO,
      LEFT,
      RIGHT;
   }

   public static record Sample(RoadGeometry.V center, RoadGeometry.V left, double distance, double halfWidth) {
      public RoadGeometry.V at(double offset, double down) {
         return this.center.add(this.left.mul(offset)).add(new RoadGeometry.V(0.0, -down, 0.0));
      }
   }

   public static record Settings(
      RoadGeometry.Mode mode,
      RoadGeometry.Style style,
      double width,
      double thickness,
      double tension,
      double arcDegrees,
      double startWidth,
      double endWidth,
      RoadGeometry.Structure structure,
      int taperVersion,
      RoadGeometry.RampTurn rampTurn,
      RoadProfile.Options options
   ) {
      public Settings(
         RoadGeometry.Mode mode,
         RoadGeometry.Style style,
         double width,
         double thickness,
         double tension,
         double arcDegrees,
         double startWidth,
         double endWidth,
         RoadGeometry.Structure structure,
         int taperVersion,
         RoadGeometry.RampTurn rampTurn,
         RoadProfile.Options options
      ) {
         if (options != null && !options.routing().controls().isEmpty()) {
            mode = RoadGeometry.Mode.CURVE;
         }

         if (options != null && structure == RoadGeometry.Structure.TUNNEL) {
            options = options.streetscape(options.streetscape().parking(false).separator(RoadStreetscape.Separator.RAIL));
         }

         if (options != null && options.lanes().explicit() && (style.ramp() || !RoadProfile.modern(style))) {
            options = options.lanes(RoadLanes.Counts.AUTO);
         }

         if (options != null && !RoadStreetscape.walkLampAllowed(options, structure, style) && options.streetscape().walkLamps()) {
            options = options.streetscape(options.streetscape().walkLamps(false));
         }

         if (options != null
            && options.sidewalk().enabled()
            && (structure == RoadGeometry.Structure.TUNNEL || style.ramp() || RoadProfile.catalog(style).type() == RoadProfile.Type.HIGHWAY)) {
            options = options.sidewalk(options.sidewalk().enabled(false));
         }

         this.mode = mode;
         this.style = style;
         this.width = width;
         this.thickness = thickness;
         this.tension = tension;
         this.arcDegrees = arcDegrees;
         this.startWidth = startWidth;
         this.endWidth = endWidth;
         this.structure = structure;
         this.taperVersion = taperVersion;
         this.rampTurn = rampTurn;
         this.options = options;
      }

      public Settings(
         RoadGeometry.Mode mode,
         RoadGeometry.Style style,
         double width,
         double thickness,
         double tension,
         double arcDegrees,
         double startWidth,
         double endWidth,
         RoadGeometry.Structure structure,
         int taperVersion,
         RoadGeometry.RampTurn rampTurn
      ) {
         this(mode, style, width, thickness, tension, arcDegrees, startWidth, endWidth, structure, taperVersion, rampTurn, RoadProfile.Options.DEFAULT);
      }

      public Settings(
         RoadGeometry.Mode mode,
         RoadGeometry.Style style,
         double width,
         double thickness,
         double tension,
         double arcDegrees,
         double startWidth,
         double endWidth,
         RoadGeometry.Structure structure,
         int taperVersion
      ) {
         this(mode, style, width, thickness, tension, arcDegrees, startWidth, endWidth, structure, taperVersion, RoadGeometry.RampTurn.LEGACY);
      }

      public Settings(
         RoadGeometry.Mode mode,
         RoadGeometry.Style style,
         double width,
         double thickness,
         double tension,
         double arcDegrees,
         double startWidth,
         double endWidth,
         RoadGeometry.Structure structure
      ) {
         this(mode, style, width, thickness, tension, arcDegrees, startWidth, endWidth, structure, RoadProfile.modern(style) ? 3 : 2);
      }

      public Settings(
         RoadGeometry.Mode mode,
         RoadGeometry.Style style,
         double width,
         double thickness,
         double tension,
         double arcDegrees,
         double startWidth,
         double endWidth
      ) {
         this(mode, style, width, thickness, tension, arcDegrees, startWidth, endWidth, RoadGeometry.Structure.AUTO);
      }

      public Settings(RoadGeometry.Mode mode, RoadGeometry.Style style, double width, double thickness, double tension, double arcDegrees) {
         this(mode, style, width, thickness, tension, arcDegrees, width, width);
      }

      public RoadGeometry.Settings options(RoadProfile.Options value) {
         return new RoadGeometry.Settings(
            this.mode,
            this.style,
            this.width,
            this.thickness,
            this.tension,
            this.arcDegrees,
            this.startWidth,
            this.endWidth,
            this.structure,
            this.taperVersion,
            this.rampTurn,
            value
         );
      }

      public RoadGeometry.Settings taperVersion(int value) {
         return new RoadGeometry.Settings(
            this.mode,
            this.style,
            this.width,
            this.thickness,
            this.tension,
            this.arcDegrees,
            this.startWidth,
            this.endWidth,
            this.structure,
            value,
            this.rampTurn,
            this.options
         );
      }

      public boolean separatedPort(boolean start) {
         return this.newRamp() && this.taperVersion >= 3 && (start ? this.options.ports().sourceOutset() : this.options.ports().targetOutset()) > 0.0;
      }

      private boolean shoulderPort(boolean start) {
         return this.taperVersion >= 4
            && this.separatedPort(start)
            && (start ? this.startWidth : this.endWidth) >= 1.0
            && (start ? this.options.ports().sourceJoin() : this.options.ports().targetJoin()) == RoadProfile.JoinMode.ADDED;
      }

      private double portBlend(double t, double chord, boolean start) {
         double q = this.separatedPort(start) ? this.leadProgress(t, chord, start) : (start ? t / this.startEdge(chord) : (1.0 - t) / this.endEdge(chord));
         if (!this.shoulderPort(start)) {
            return smooth(q);
         } else {
            double port = start ? this.startWidth : this.endWidth;
            double outset = start ? this.options.ports().sourceOutset() : this.options.ports().targetOutset();
            double full = (this.width - port) / 2.0 + outset;
            double occupied = (this.width - port) / 2.0 - 0.5;
            double grow = smooth(Math.min(1.0, q / 0.32));
            double peel = smooth(Math.max(0.0, Math.min(1.0, (q - 0.65) / 0.35)));
            return (occupied * grow + (full - occupied) * peel) / full;
         }
      }

      public double leadProgress(double t, double chord, boolean start) {
         double time = (start ? t : 1.0 - t) * (chord + this.startLead() + this.endLead());
         double lead = start ? this.startLead() : this.endLead();
         double hold = (start ? this.startWidth : this.endWidth) < 1.0 ? 0.0 : 6.0;
         return Math.max(0.0, Math.min(1.0, (time - hold) / (lead - hold)));
      }

      public boolean newRamp() {
         return this.laneRamp() && RoadProfile.modern(this.style);
      }

      public double startLead() {
         if (this.taperVersion < 4 || !this.separatedPort(true) && (!this.newRamp() || !(this.startWidth < 1.0))) {
            return this.separatedPort(true) ? (double)(this.startWidth < 1.0 ? 18 : 24) : (this.newRamp() && this.startWidth < 1.0 ? 18.0 : 6.0);
         } else {
            return Math.max(this.options.routing().transition(), this.width * 5.0) + (double)(this.startWidth < 1.0 ? 0 : 6);
         }
      }

      public double endLead() {
         if (this.taperVersion < 4 || !this.separatedPort(false) && (!this.newRamp() || !(this.endWidth < 1.0))) {
            return this.separatedPort(false) ? (double)(this.endWidth < 1.0 ? 18 : 24) : (this.newRamp() && this.endWidth < 1.0 ? 18.0 : 6.0);
         } else {
            return Math.max(this.options.routing().transition(), this.width * 5.0) + (double)(this.endWidth < 1.0 ? 0 : 6);
         }
      }

      public double startEdge(double chord) {
         return this.startLead() / (chord + this.startLead() + this.endLead());
      }

      public double endEdge(double chord) {
         return this.endLead() / (chord + this.startLead() + this.endLead());
      }

      public double liftParameter(double chord) {
         double start = this.laneRamp() ? this.startEdge(chord) : 0.0;
         double end = this.laneRamp() ? this.endEdge(chord) : 0.0;
         return start + (1.0 - start - end) * this.options.liftPosition();
      }

      public double liftAt(double t, double chord) {
         double start = this.laneRamp() ? this.startEdge(chord) : 0.0;
         double end = this.laneRamp() ? this.endEdge(chord) : 0.0;
         double u = Math.max(0.0, Math.min(1.0, (t - start) / (1.0 - start - end)));
         double p = this.options.liftPosition();
         return this.options.liftHeight() * smooth(u <= p ? u / p : (1.0 - u) / (1.0 - p));
      }

      public static double smooth(double u) {
         return u * u * u * (10.0 + u * (-15.0 + 6.0 * u));
      }

      public boolean laneRamp() {
         return this.style.ramp() && !this.style.connectorRamp() && this.rampTurn != RoadGeometry.RampTurn.LEGACY;
      }

      public RoadGeometry.Settings rampTurn(RoadGeometry.RampTurn turn) {
         return new RoadGeometry.Settings(
            this.mode,
            this.style,
            this.width,
            this.thickness,
            this.tension,
            this.arcDegrees,
            this.startWidth,
            this.endWidth,
            this.structure,
            this.taperVersion,
            turn,
            this.options
         );
      }

      public RoadGeometry.Settings taper(double start, double end) {
         return new RoadGeometry.Settings(
            this.mode,
            this.style,
            this.width,
            this.thickness,
            this.tension,
            this.arcDegrees,
            start,
            end,
            this.structure,
            this.taperVersion,
            this.rampTurn,
            this.options
         );
      }

      public RoadGeometry.Settings structure(RoadGeometry.Structure value) {
         return new RoadGeometry.Settings(
            this.mode,
            this.style,
            this.width,
            this.thickness,
            this.tension,
            this.arcDegrees,
            this.startWidth,
            this.endWidth,
            value,
            this.taperVersion,
            this.rampTurn,
            this.options
         );
      }

      public double widthAt(double t, double chord) {
         if (!this.options.ends().equals(RoadTransitions.Ends.NONE)) {
            return RoadTransitions.width(this, t * chord, chord);
         } else if (this.laneRamp()) {
            if (this.newRamp()) {
               double ea = this.startEdge(chord);
               double eb = this.endEdge(chord);
               double ta = this.separatedPort(true)
                  ? this.leadProgress(t, chord, true)
                  : (this.startWidth < 1.0 ? Math.min(1.0, t / ea) : Math.max(0.0, Math.min(1.0, (t - ea) / 0.25)));
               double tb = this.separatedPort(false)
                  ? this.leadProgress(t, chord, false)
                  : (this.endWidth < 1.0 ? Math.min(1.0, (1.0 - t) / eb) : Math.max(0.0, Math.min(1.0, (1.0 - t - eb) / 0.25)));
               if (this.shoulderPort(true)) {
                  ta = Math.min(1.0, ta / 0.32);
               }

               if (this.shoulderPort(false)) {
                  tb = Math.min(1.0, tb / 0.32);
               }

               return this.width + (this.startWidth - this.width) * (1.0 - smooth(ta)) + (this.endWidth - this.width) * (1.0 - smooth(tb));
            } else {
               double edge = 6.0 / (chord + 12.0);
               double span = Math.min(0.45, edge + 0.25);
               double a = Math.max(0.0, Math.min(1.0, (t - edge) / (span - edge)));
               double b = Math.max(0.0, Math.min(1.0, (1.0 - t - edge) / (span - edge)));
               return this.width + (this.startWidth - this.width) * (1.0 - ease(a)) + (this.endWidth - this.width) * (1.0 - ease(b));
            }
         } else if (this.style.ramp() && this.taperVersion >= 2) {
            double span = Math.min(0.65, Math.max(0.45, 6.0 * Math.max(Math.abs(this.startWidth - this.width), Math.abs(this.endWidth - this.width)) / chord));
            double hold = span * 0.25;
            double a = Math.max(0.0, Math.min(1.0, (t - hold) / (span - hold)));
            double b = Math.max(0.0, Math.min(1.0, (1.0 - t - hold) / (span - hold)));
            return this.width + (this.startWidth - this.width) * (1.0 - ease(a)) + (this.endWidth - this.width) * (1.0 - ease(b));
         } else {
            double span = Math.min(0.4, Math.max(0.12, 2.0 * Math.max(Math.abs(this.startWidth - this.width), Math.abs(this.endWidth - this.width)) / chord));
            return this.width
               + (this.startWidth - this.width) * (1.0 - ease(Math.min(1.0, t / span)))
               + (this.endWidth - this.width) * (1.0 - ease(Math.min(1.0, (1.0 - t) / span)));
         }
      }

      private static double ease(double t) {
         return t * t * (3.0 - 2.0 * t);
      }

      public static RoadGeometry.Settings defaults() {
         return new RoadGeometry.Settings(RoadGeometry.Mode.AUTO, RoadGeometry.Style.O2_YELLOW, 9.0, 1.0, 0.35, 90.0);
      }

      public void validate() {
         if (this.mode == null
            || this.style == null
            || this.structure == null
            || this.rampTurn == null
            || this.options == null
            || this.taperVersion < 1
            || this.taperVersion > 6
            || !RoadGeometry.finite(this.width, this.thickness, this.tension, this.arcDegrees, this.startWidth, this.endWidth)) {
            throw new IllegalArgumentException("参数必须为有限数值");
         } else if (this.startWidth < (this.newRamp() ? 0.5 : 2.0)
            || this.startWidth > RoadLimits.maximumWidth(this.style)
            || this.endWidth < (this.newRamp() ? 0.5 : 2.0)
            || this.endWidth > RoadLimits.maximumWidth(this.style)
            || this.width < 2.0
            || this.width > RoadLimits.maximumWidth(this.style)
            || this.thickness < 0.125
            || this.thickness > 2.0
            || this.tension < 0.05
            || this.tension > 1.5) {
            throw new IllegalArgumentException("宽度 2–" + (int)RoadLimits.maximumWidth(this.style) + "，厚度 0.125–2，曲线强度 0.05–1.5");
         } else if (RoadProfile.modern(this.style) && RoadProfile.layout(this, this.width).laneWidth() < 2.0) {
            throw new IllegalArgumentException("断面过窄，每根机动车道至少 2 格");
         } else if (!RoadVertical.automatic(this) || !this.style.ramp() && this.mode != RoadGeometry.Mode.RING && this.options.liftHeight() == 0.0) {
            if (this.mode == RoadGeometry.Mode.ARC && (Math.abs(this.arcDegrees) < 5.0 || Math.abs(this.arcDegrees) > 300.0)) {
               throw new IllegalArgumentException("圆弧角度绝对值需在 5–300°");
            }
         } else {
            throw new IllegalArgumentException("自动下潜 / 抬升用于普通道路或高速；请关闭中间临时抬升后使用");
         }
      }
   }

   public static enum Structure {
      AUTO,
      GROUND,
      BRIDGE,
      TUNNEL;
   }

   public static enum Style {
      TWO_LANE,
      FOUR_LANE,
      ONE_WAY,
      UNMARKED,
      RAMP_ONE,
      RAMP_TWO,
      O2_YELLOW,
      O4_YELLOW,
      O6_YELLOW,
      O2_DASHED,
      O2_RAIL,
      O4_RAIL,
      O6_RAIL,
      O2_GREEN,
      O4_GREEN,
      O6_GREEN,
      O1_ONE,
      O2_ONE,
      O3_ONE,
      H4_RAIL,
      H6_RAIL,
      H4_GREEN,
      H6_GREEN,
      H4_YELLOW,
      H6_YELLOW,
      H2_ONE,
      H3_ONE,
      R1,
      R2,
      R1_SHOULDER,
      R2_SHOULDER,
      O8_YELLOW,
      O8_RAIL,
      O8_GREEN,
      O4_ONE,
      H8_RAIL,
      H8_GREEN,
      H4_ONE,
      H1_ONE,
      C1_RAMP,
      C1_HIGHWAY_RAMP,
      TOLL,
      RAILBED;

      public boolean connectorRamp() {
         return this == C1_RAMP || this == C1_HIGHWAY_RAMP;
      }

      public boolean ramp() {
         return this == RAMP_ONE || this == RAMP_TWO || RoadProfile.catalog(this).type() == RoadProfile.Type.RAMP;
      }

      public double defaultWidth() {
         if (RoadProfile.modern(this)) {
            return RoadProfile.width(this, RoadProfile.Options.DEFAULT, RoadProfile.catalog(this).laneWidth());
         } else {
            return this == FOUR_LANE ? 17.0 : (this == RAMP_ONE ? 5.0 : 9.0);
         }
      }
   }

   public static record V(double x, double y, double z) {
      public RoadGeometry.V add(RoadGeometry.V v) {
         return new RoadGeometry.V(this.x + v.x, this.y + v.y, this.z + v.z);
      }

      public RoadGeometry.V sub(RoadGeometry.V v) {
         return new RoadGeometry.V(this.x - v.x, this.y - v.y, this.z - v.z);
      }

      public RoadGeometry.V mul(double n) {
         return new RoadGeometry.V(this.x * n, this.y * n, this.z * n);
      }

      public double horizontalLength() {
         return Math.hypot(this.x, this.z);
      }

      public double distance(RoadGeometry.V v) {
         RoadGeometry.V d = this.sub(v);
         return Math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z);
      }

      public RoadGeometry.V horizontalUnit() {
         double d = this.horizontalLength();
         if (d < 1.0E-8) {
            throw new IllegalArgumentException("道路出现零长度或折返切线");
         } else {
            return new RoadGeometry.V(this.x / d, 0.0, this.z / d);
         }
      }

      public RoadGeometry.V left() {
         return new RoadGeometry.V(-this.z, 0.0, this.x);
      }

      public double dot(RoadGeometry.V v) {
         return this.x * v.x + this.y * v.y + this.z * v.z;
      }
   }
}
