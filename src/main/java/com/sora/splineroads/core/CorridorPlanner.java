package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class CorridorPlanner {
   private static CorridorPlanner.Lead lead(CorridorPlanner.Port p, double clear, InterchangePlanner.Options o) {
      double grow = Math.max(o.transition() * 0.4, Math.sqrt(6.0 * Math.abs(p.attached(o.width()) - p.initial()) * o.radius()) + 2.0);
      double peel = Math.max(o.transition() * 0.4, Math.sqrt(6.0 * Math.abs(clear - p.attached(o.width())) * o.radius()) + 2.0);
      return new CorridorPlanner.Lead(Math.max(12.0, grow), Math.max(8.0, o.width() * 1.5), Math.max(12.0, peel));
   }

   private static double climb(double ya, double yb) {
      return Math.abs(ya - yb) < 0.001 ? 8.0 : Math.max(24.0, 2.05 * Math.abs(ya - yb) / 0.15);
   }

   private static double span(CorridorPlanner.Port a, CorridorPlanner.Port b, double clear, double ya, double yb, InterchangePlanner.Options o) {
      return lead(a, clear, o).length() + climb(ya, yb) + lead(b, clear, o).length();
   }

   private static boolean direct(CorridorPlanner.Kind kind, double ya, double yb) {
      return kind == CorridorPlanner.Kind.FRONTAGE && Math.abs(ya - yb) < 0.001;
   }

   private static double transfer(CorridorPlanner.Port a, CorridorPlanner.Port b, InterchangePlanner.Options o) {
      return Math.max(o.transition(), Math.sqrt(6.0 * Math.abs(b.attached(o.width()) - a.attached(o.width())) * o.radius()) + 2.0);
   }

   private static double span(
      CorridorPlanner.Kind kind, CorridorPlanner.Port a, CorridorPlanner.Port b, double clear, double ya, double yb, InterchangePlanner.Options o
   ) {
      if (!direct(kind, ya, yb)) {
         return span(a, b, clear, ya, yb, o);
      } else {
         CorridorPlanner.Lead la = lead(a, clear, o);
         CorridorPlanner.Lead lb = lead(b, clear, o);
         return la.grow + la.hold + transfer(a, b, o) + lb.hold + lb.grow;
      }
   }

   public static InterchangePlanner.Plan plan(
      RoadGeometry.Node[] nodes, RoadGeometry.Settings main, RoadGeometry.Settings secondary, InterchangePlanner.Options o, CorridorPlanner.Config c
   ) {
      RoadPlanningBudget.check();

      try {
         return fixed(nodes, main, secondary, o, c);
      } catch (IllegalArgumentException var7) {
         if (o.adjustEndpoints() && nodes.length == (c.kind == CorridorPlanner.Kind.FRONTAGE ? 2 : 4)) {
            RoadGeometry.Node[] fitted = fit(nodes, main, secondary, o, c);
            return fixed(fitted, main, secondary, o, c);
         } else {
            throw var7;
         }
      }
   }

   private static RoadGeometry.Node[] fit(
      RoadGeometry.Node[] nodes, RoadGeometry.Settings main, RoadGeometry.Settings secondary, InterchangePlanner.Options o, CorridorPlanner.Config c
   ) {
      RoadPlanningBudget.check();
      RoadGeometry.V origin = nodes[0].position();
      RoadGeometry.V u = nodes[1].position().sub(origin).horizontalUnit();
      if (nodes[1].position().sub(origin).horizontalLength() < 1.0) {
         throw new IllegalArgumentException("A/B 水平距离至少 1 格");
      } else {
         boolean startOnly = c.adjustment == CorridorPlanner.Adjustment.START;
         boolean endOnly = c.adjustment == CorridorPlanner.Adjustment.END;
         double y = startOnly ? nodes[1].position().y() : (endOnly ? origin.y() : (origin.y() + nodes[1].position().y()) / 2.0);
         double otherY = c.frontageY;
         double offset = 0.0;
         double[] low = new double[nodes.length / 2];
         double[] high = new double[low.length];
         boolean reverse = false;

         for (int i = 0; i < low.length; i++) {
            double a = nodes[2 * i].position().sub(origin).dot(u);
            double b = nodes[2 * i + 1].position().sub(origin).dot(u);
            low[i] = Math.min(a, b);
            high[i] = Math.max(a, b);
            if (i == 1) {
               reverse = a > b;
            }
         }

         if (c.kind == CorridorPlanner.Kind.LAYERED) {
            RoadGeometry.V mid = startOnly
               ? nodes[reverse ? 2 : 3].position()
               : (endOnly ? nodes[reverse ? 3 : 2].position() : nodes[2].position().add(nodes[3].position()).mul(0.5));
            double maxOffset = Math.max(main.width(), secondary.width()) / 2.0;
            offset = mid.sub(origin).dot(u.left());
            if (!startOnly && !endOnly) {
               offset = Math.max(-maxOffset, Math.min(maxOffset, offset));
            } else if (Math.abs(offset) > maxOffset) {
               throw new IllegalArgumentException("固定端横向偏离过大，请允许两端调整");
            }

            otherY = mid.y();
            double required = o.clearance() + Math.max(main.thickness(), secondary.thickness());
            if (Math.abs(otherY - y) < required) {
               if (startOnly || endOnly) {
                  throw new IllegalArgumentException("固定端的双层净高不足；仅单端调整不能改变该端高度，请改为两端调整");
               }

               if (otherY >= y) {
                  otherY = y + required;
               } else {
                  y = otherY + required;
               }
            }
         }

         double required = 64.0;
         if (c.access != CorridorPlanner.Access.NONE) {
            for (int side : new int[]{1, -1}) {
               if (c.sides.includes(side)) {
                  CorridorPlanner.Port primary = port(main, side, 0.0);
                  CorridorPlanner.Port other;
                  double clear;
                  if (c.kind == CorridorPlanner.Kind.FRONTAGE) {
                     other = new CorridorPlanner.Port((double)side * (main.width() / 2.0 + c.gap), -side);
                     clear = (double)side * (main.width() / 2.0 + c.gap / 2.0);
                  } else {
                     other = port(secondary, side, offset);
                     clear = (double)side * (Math.max(main.width() / 2.0, secondary.width() / 2.0 + (double)side * offset) + o.width() / 2.0 + c.gap + 0.15);
                  }

                  int count = c.access == CorridorPlanner.Access.BOTH ? 2 : 1;
                  required = Math.max(required, Math.ceil(span(c.kind, primary, other, clear, y, otherY, o) * (double)count + (double)(24 * (count + 1))) + 2.0);
               }
            }
         }

         double lo = Arrays.stream(low).max().orElseThrow();
         double hi = Arrays.stream(high).min().orElseThrow();
         double center = (lo + hi) / 2.0;
         double targetLo = endOnly ? lo : Math.min(lo, (startOnly ? hi : center + required / 2.0) - required);
         double targetHi = startOnly ? hi : Math.max(hi, (endOnly ? lo : center - required / 2.0) + required);
         RoadGeometry.Node[] result = (RoadGeometry.Node[])nodes.clone();

         for (int axis = 0; axis < low.length; axis++) {
            RoadPlanningBudget.check();
            double a = endOnly ? low[axis] : Math.min(low[axis], targetLo);
            double b = startOnly ? high[axis] : Math.max(high[axis], targetHi);
            if (b - a > 2048.0) {
               throw new IllegalArgumentException("自动调整后道路超过 2048 格；请减少出入口或缩小高差/过渡长度");
            }

            boolean back = axis == 1 && reverse;

            for (int end = 0; end < 2; end++) {
               int ix = axis * 2 + end;
               RoadGeometry.V p = point(origin, u, end == 0 ^ back ? a : b, axis == 0 ? 0.0 : offset, axis == 0 ? y : otherY);
               result[ix] = p.distance(nodes[ix].position()) < 1.0E-8 ? nodes[ix] : new RoadGeometry.Node(p, nodes[ix].yaw(), 0.0);
            }
         }

         return result;
      }
   }

   private static InterchangePlanner.Plan fixed(
      RoadGeometry.Node[] nodes, RoadGeometry.Settings main, RoadGeometry.Settings secondary, InterchangePlanner.Options o, CorridorPlanner.Config c
   ) {
      RoadPlanningBudget.check();
      if (nodes.length != (c.kind == CorridorPlanner.Kind.FRONTAGE ? 2 : 4)) {
         throw new IllegalArgumentException(c.kind == CorridorPlanner.Kind.FRONTAGE ? "请选择主路 A/B 两端" : "请选择第一层 A/B 和第二层 C/D 两端");
      } else {
         main.validate();
         secondary.validate();
         if (RoadProfile.modern(main.style()) && RoadProfile.catalog(main.style()).twoWay()) {
            if (c.kind != CorridorPlanner.Kind.FRONTAGE
               || !RoadProfile.catalog(secondary.style()).twoWay() && RoadProfile.catalog(secondary.style()).type() == RoadProfile.Type.ORDINARY) {
               if (c.kind != CorridorPlanner.Kind.LAYERED || RoadProfile.modern(secondary.style()) && RoadProfile.catalog(secondary.style()).twoWay()) {
                  RoadGeometry.V start = nodes[0].position();
                  RoadGeometry.V delta = nodes[1].position().sub(start);
                  RoadGeometry.V u = delta.horizontalUnit();
                  double length = delta.horizontalLength();
                  if (length < 64.0 || length > 2048.0) {
                     throw new IllegalArgumentException("主路长度需为 64–2048 格");
                  } else if (Math.abs(delta.y()) > 0.001) {
                     throw new IllegalArgumentException("A/B 两端需要等高");
                  } else {
                     double lo = 0.0;
                     double hi = length;
                     double secondOffset = 0.0;
                     double secondY = c.frontageY;
                     if (c.kind == CorridorPlanner.Kind.LAYERED) {
                        RoadGeometry.V cd = nodes[3].position().sub(nodes[2].position());
                        if (cd.horizontalLength() < 64.0
                           || cd.horizontalLength() > 2048.0
                           || Math.abs(cd.y()) > 0.001
                           || Math.abs(cd.horizontalUnit().dot(u)) < 0.9999) {
                           throw new IllegalArgumentException("双层出入口需要两条平直、各自等高且平行的道路");
                        }

                        double a = nodes[2].position().sub(start).dot(u);
                        double b = nodes[3].position().sub(start).dot(u);
                        lo = Math.max(lo, Math.min(a, b));
                        hi = Math.min(length, Math.max(a, b));
                        secondOffset = nodes[2].position().sub(start).dot(u.left());
                        secondY = nodes[2].position().y();
                        if (Math.abs(secondOffset) > Math.max(main.width(), secondary.width()) / 2.0) {
                           throw new IllegalArgumentException("两层道路横向偏离过大；请选择上下重叠的平行道路");
                        }

                        double required = o.clearance() + Math.max(main.thickness(), secondary.thickness());
                        if (Math.abs(secondY - start.y()) < required) {
                           throw new IllegalArgumentException(String.format(Locale.ROOT, "双层道路高差至少 %.1f 格（含路板厚度）", required));
                        }
                     }

                     main = main.options(main.options().traffic(o.leftTraffic()));
                     secondary = secondary.options(secondary.options().traffic(o.leftTraffic()));
                     ArrayList<InterchangePlanner.Leg> legs = new ArrayList<>();
                     legs.add(new InterchangePlanner.Leg("主路 AB", 0, 1, straight(start, nodes[1].position(), main)));
                     RoadGeometry.Mesh lower = null;
                     if (c.kind == CorridorPlanner.Kind.LAYERED) {
                        lower = straight(nodes[2].position(), nodes[3].position(), secondary);
                        legs.add(new InterchangePlanner.Leg("第二层 CD", 2, 3, lower));
                     }

                     int movements = 0;
                     double minRadius = Double.POSITIVE_INFINITY;

                     for (int side : new int[]{1, -1}) {
                        if (c.sides.includes(side)) {
                           RoadPlanningBudget.check();
                           int direction = side * RoadProfile.trafficSign(o.leftTraffic());
                           double auxiliary = (double)side * (main.width() / 2.0 + c.gap + secondary.width() / 2.0);
                           if (c.kind == CorridorPlanner.Kind.FRONTAGE) {
                              RoadGeometry.V ax = point(start, u, 0.0, auxiliary, secondY);
                              RoadGeometry.V bx = point(start, u, length, auxiliary, secondY);
                              legs.add(
                                 new InterchangePlanner.Leg(
                                    (side > 0 ? "右" : "左") + "侧辅路", -1, -1, straight(direction > 0 ? ax : bx, direction > 0 ? bx : ax, secondary)
                                 )
                              );
                           }

                           if (c.access != CorridorPlanner.Access.NONE) {
                              CorridorPlanner.Port primary = port(main, side, 0.0);
                              double clear;
                              CorridorPlanner.Port other;
                              if (c.kind == CorridorPlanner.Kind.FRONTAGE) {
                                 if (c.gap < o.width() + 2.0) {
                                    throw new IllegalArgumentException("主辅路净间隔至少为匝道宽度＋2 格");
                                 }

                                 other = new CorridorPlanner.Port(auxiliary - (double)side * secondary.width() / 2.0, -side);
                                 clear = (double)side * (main.width() / 2.0 + c.gap / 2.0);
                              } else {
                                 other = port(secondary, side, secondOffset);
                                 clear = (double)side
                                    * (Math.max(main.width() / 2.0, secondary.width() / 2.0 + (double)side * secondOffset) + o.width() / 2.0 + c.gap + 0.15);
                              }

                              double span = span(c.kind, primary, other, clear, start.y(), secondY, o);
                              int count = c.access == CorridorPlanner.Access.BOTH ? 2 : 1;
                              double required = span * (double)count + (double)(24 * (count + 1));
                              if (hi - lo < required) {
                                 throw new IllegalArgumentException(
                                    String.format(Locale.ROOT, "出入口重叠路段至少需 %.0f 格（当前 %.0f）；可选仅汇入/汇出或缩短过渡", Math.ceil(required), hi - lo)
                                 );
                              }

                              double margin = (hi - lo - span * (double)count) / (double)(count + 1);
                              RoadGeometry.Mesh firstRamp = null;

                              for (int index = 0; index < count; index++) {
                                 RoadPlanningBudget.check();
                                 boolean exit = c.access == CorridorPlanner.Access.EXIT || c.access == CorridorPlanner.Access.BOTH && index == 0;
                                 double origin = direction > 0 ? lo + margin + (double)index * (span + margin) : hi - margin - (double)index * (span + margin);
                                 RoadGeometry.Mesh ramp = ramp(
                                    c.kind,
                                    start,
                                    u,
                                    origin,
                                    direction,
                                    exit ? primary : other,
                                    exit ? other : primary,
                                    clear,
                                    exit ? start.y() : secondY,
                                    exit ? secondY : start.y(),
                                    o
                                 );
                                 if (count == 2) {
                                    double grow = lead(other, clear, o).grow;
                                    ramp = clip(ramp, u, direction, index == 0 ? 0.0 : grow, index == 0 ? span - grow : span);
                                    if (index == 0) {
                                       firstRamp = ramp;
                                    } else {
                                       legs.add(
                                          new InterchangePlanner.Leg(
                                             (side > 0 ? "右" : "左") + "侧共通段",
                                             side,
                                             side,
                                             straight(firstRamp.last().center(), ramp.first().center(), ramp.settings())
                                          )
                                       );
                                    }
                                 }

                                 RoadGrades.validate(ramp);
                                 double radius = RoadRibbon.minRadius(ramp);
                                 if (radius + 0.1 < o.radius()) {
                                    throw new IllegalArgumentException("出入口弯道半径不足，请增加过渡长度");
                                 }

                                 minRadius = Math.min(minRadius, radius);
                                 legs.add(new InterchangePlanner.Leg((side > 0 ? "右" : "左") + "侧" + (exit ? "主路汇出" : "汇入主路"), side, side, ramp));
                                 movements++;
                              }
                           }
                        }
                     }

                     verify(legs, o.clearance());
                     return new InterchangePlanner.Plan(
                        List.copyOf(legs), movements, minRadius, Math.max(start.y(), secondY), point(start, u, length / 2.0, 0.0, start.y()), List.of(nodes)
                     );
                  }
               } else {
                  throw new IllegalArgumentException("双层道路的第二层也需要双向普通道路或高速");
               }
            } else {
               throw new IllegalArgumentException("辅路请选择单向 1／2／3／4 车道普通道路");
            }
         } else {
            throw new IllegalArgumentException("主路需要双向普通道路或高速");
         }
      }
   }

   private static CorridorPlanner.Port port(RoadGeometry.Settings road, int side, double offset) {
      return new CorridorPlanner.Port(offset + (double)side * road.width() / 2.0, side);
   }

   private static RoadGeometry.V point(RoadGeometry.V origin, RoadGeometry.V u, double d, double lateral, double y) {
      RoadGeometry.V p = origin.add(u.mul(d)).add(u.left().mul(lateral));
      return new RoadGeometry.V(p.x(), y, p.z());
   }

   private static RoadGeometry.Mesh straight(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.Settings s) {
      RoadGeometry.V left = b.sub(a).horizontalUnit().left();
      ArrayList<RoadGeometry.Sample> points = new ArrayList<>();
      int steps = (int)Math.ceil(a.distance(b));

      for (int i = 0; i <= steps; i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         points.add(new RoadGeometry.Sample(a.add(b.sub(a).mul((double)i / (double)steps)), left, 0.0, s.width() / 2.0));
      }

      return RoadRibbon.mesh(points, s);
   }

   private static double[] leadAt(CorridorPlanner.Port p, double clear, CorridorPlanner.Lead l, double d, InterchangePlanner.Options o) {
      if (d < l.grow) {
         double t = RoadGeometry.Settings.smooth(d / l.grow);
         return new double[]{p.initial() + (p.attached(o.width()) - p.initial()) * t, 0.5 + (o.width() - 0.5) * t};
      } else if (d < l.grow + l.hold) {
         return new double[]{p.attached(o.width()), o.width()};
      } else {
         double t = RoadGeometry.Settings.smooth(Math.min(1.0, (d - l.grow - l.hold) / l.peel));
         return new double[]{p.attached(o.width()) + (clear - p.attached(o.width())) * t, o.width()};
      }
   }

   private static RoadGeometry.Mesh clip(RoadGeometry.Mesh mesh, RoadGeometry.V axis, int direction, double from, double to) {
      ArrayList<RoadGeometry.Sample> out = new ArrayList<>();
      RoadGeometry.V origin = mesh.first().center();
      double begin = mesh.first().center().sub(origin).dot(axis);

      for (double station : new double[]{from, to}) {
         double target = begin + (double)direction * station;
         RoadGeometry.Sample found = mesh.first();

         for (int i = 1; i < mesh.samples().size(); i++) {
            if ((i & 63) == 0) {
               RoadPlanningBudget.check();
            }

            RoadGeometry.Sample a = mesh.samples().get(i - 1);
            RoadGeometry.Sample b = mesh.samples().get(i);
            double x = a.center().sub(origin).dot(axis);
            double y = b.center().sub(origin).dot(axis);
            if ((target - x) * (target - y) <= 1.0E-9) {
               double t = Math.max(0.0, Math.min(1.0, (target - x) / (y - x)));
               double width = a.halfWidth() + (b.halfWidth() - a.halfWidth()) * t;
               found = new RoadGeometry.Sample(
                  a.center().add(b.center().sub(a.center()).mul(t)),
                  a.left().add(b.left().sub(a.left()).mul(t)).horizontalUnit(),
                  a.distance() + (b.distance() - a.distance()) * t,
                  width
               );
               break;
            }
         }

         if (station == from) {
            out.add(found);
         } else {
            double first = out.get(0).distance();

            for (RoadGeometry.Sample s : mesh.samples()) {
               if (s.distance() > first + 1.0E-7 && s.distance() < found.distance() - 1.0E-7) {
                  out.add(s);
               }
            }

            out.add(found);
         }
      }

      return RoadRibbon.mesh(out, mesh.settings());
   }

   private static RoadGeometry.Mesh ramp(
      CorridorPlanner.Kind kind,
      RoadGeometry.V origin,
      RoadGeometry.V u,
      double station,
      int direction,
      CorridorPlanner.Port a,
      CorridorPlanner.Port b,
      double clear,
      double ya,
      double yb,
      InterchangePlanner.Options o
   ) {
      CorridorPlanner.Lead la = lead(a, clear, o);
      CorridorPlanner.Lead lb = lead(b, clear, o);
      double climb = climb(ya, yb);
      double total = span(kind, a, b, clear, ya, yb, o);
      int steps = (int)Math.ceil(total / 0.35);
      ArrayList<RoadGeometry.V> centers = new ArrayList<>();
      ArrayList<Double> widths = new ArrayList<>();

      for (int i = 0; i <= steps; i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         double d = total * (double)i / (double)steps;
         double lateral;
         double y;
         double width;
         if (direct(kind, ya, yb)) {
            double start = la.grow + la.hold;
            double end = total - lb.grow - lb.hold;
            if (d < start) {
               double[] at = leadAt(a, clear, la, d, o);
               lateral = at[0];
               width = at[1];
            } else if (d > end) {
               double[] at = leadAt(b, clear, lb, total - d, o);
               lateral = at[0];
               width = at[1];
            } else {
               lateral = a.attached(o.width()) + (b.attached(o.width()) - a.attached(o.width())) * RoadGeometry.Settings.smooth((d - start) / (end - start));
               width = o.width();
            }

            y = ya;
         } else if (d < la.length()) {
            double[] at = leadAt(a, clear, la, d, o);
            lateral = at[0];
            width = at[1];
            y = ya;
         } else if (d > la.length() + climb) {
            double[] at = leadAt(b, clear, lb, total - d, o);
            lateral = at[0];
            width = at[1];
            y = yb;
         } else {
            lateral = clear;
            y = ya + (yb - ya) * RoadGeometry.Settings.smooth((d - la.length()) / climb);
            width = o.width();
         }

         centers.add(point(origin, u, station + (double)direction * d, lateral, y));
         widths.add(Math.max(0.5, width));
      }

      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

      for (int i = 0; i <= steps; i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         RoadGeometry.V tangent = centers.get(Math.min(steps, i + 1)).sub(centers.get(Math.max(0, i - 1))).horizontalUnit();
         samples.add(new RoadGeometry.Sample(centers.get(i), tangent.left(), 0.0, widths.get(i) / 2.0));
      }

      RoadGeometry.Style style = o.lanes() == 1 ? RoadGeometry.Style.R1 : RoadGeometry.Style.R2;
      RoadGeometry.Settings settings = new RoadGeometry.Settings(RoadGeometry.Mode.CURVE, style, o.width(), 1.0, 0.4, 90.0)
         .options(RoadProfile.Options.DEFAULT.traffic(o.leftTraffic()));
      return RoadRibbon.mesh(samples, settings);
   }

   static void verify(List<InterchangePlanner.Leg> legs, double clearance) {
      for (int i = 0; i < legs.size(); i++) {
         for (int j = 0; j < i; j++) {
            RoadPlanningBudget.check();
            RoadGeometry.Mesh a = legs.get(i).mesh();
            RoadGeometry.Mesh b = legs.get(j).mesh();
            if (!(a.max().x() < b.min().x()) && !(b.max().x() < a.min().x()) && !(a.max().z() < b.min().z()) && !(b.max().z() < a.min().z())) {
               for (int k = 0; k < a.samples().size(); k += 2) {
                  if ((k & 63) == 0) {
                     RoadPlanningBudget.check();
                  }

                  RoadGeometry.Sample s = a.samples().get(k);

                  for (double side : new double[]{-0.98, 0.0, 0.98}) {
                     RoadGeometry.V p = s.at(side * s.halfWidth(), 0.0);
                     RoadQueries.Projection q = RoadQueries.horizontal(b, p);
                     if (!(q.horizontalDistance() > q.sample().halfWidth() - 0.1)) {
                        double along = p.sub(q.sample().center()).dot(q.tangent().horizontalUnit());
                        if ((!(q.sample().distance() < 1.0E-6) || !(along < -0.05)) && (!(q.sample().distance() > b.length() - 1.0E-6) || !(along > 0.05))) {
                           double gap = Math.abs(p.y() - q.sample().center().y());
                           if (gap > 0.12 && gap < clearance + Math.max(a.settings().thickness(), b.settings().thickness()) - 0.05) {
                              throw new IllegalArgumentException(
                                 legs.get(i).name()
                                    + " 与 "
                                    + legs.get(j).name()
                                    + String.format(Locale.ROOT, " 在 %.1f, %.1f, %.1f 附近净空不足（高差 %.2f），请增加侧向间隔或过渡长度", p.x(), p.y(), p.z(), gap)
                              );
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private CorridorPlanner() {
   }

   public static enum Access {
      BOTH("汇出＋汇入"),
      EXIT("仅主路汇出"),
      ENTRY("仅汇入主路"),
      NONE("仅建道路");

      public final String label;

      private Access(String s) {
         this.label = s;
      }
   }

   public static enum Adjustment {
      AUTO("自动避障"),
      BOTH("两端调整"),
      START("仅 A 侧"),
      END("仅 B 侧");

      public final String label;

      private Adjustment(String value) {
         this.label = value;
      }
   }

   public static record Config(
      CorridorPlanner.Kind kind,
      CorridorPlanner.Sides sides,
      CorridorPlanner.Access access,
      double gap,
      double frontageY,
      CorridorPlanner.Adjustment adjustment
   ) {
      public Config(CorridorPlanner.Kind kind, CorridorPlanner.Sides sides, CorridorPlanner.Access access, double gap, double y) {
         this(kind, sides, access, gap, y, CorridorPlanner.Adjustment.BOTH);
      }

      public Config(
         CorridorPlanner.Kind kind,
         CorridorPlanner.Sides sides,
         CorridorPlanner.Access access,
         double gap,
         double frontageY,
         CorridorPlanner.Adjustment adjustment
      ) {
         if (kind != null
            && sides != null
            && access != null
            && adjustment != null
            && RoadGeometry.finite(gap, frontageY)
            && !(gap < (double)(kind == CorridorPlanner.Kind.LAYERED ? 0 : 4))
            && !(gap > 96.0)
            && !(frontageY < -64.0)
            && !(frontageY > 320.0)) {
            this.kind = kind;
            this.sides = sides;
            this.access = access;
            this.gap = gap;
            this.frontageY = frontageY;
            this.adjustment = adjustment;
         } else {
            throw new IllegalArgumentException("双层外绕间隔需为 0–96 格，主辅路间隔需为 4–96 格，辅路高度需在 -64–320 内");
         }
      }

      public CorridorPlanner.Config adjustment(CorridorPlanner.Adjustment value) {
         return new CorridorPlanner.Config(this.kind, this.sides, this.access, this.gap, this.frontageY, value);
      }
   }

   public static enum Kind {
      FRONTAGE,
      LAYERED;
   }

   private static record Lead(double grow, double hold, double peel) {
      double length() {
         return this.grow + this.hold + this.peel;
      }
   }

   private static record Port(double edge, int outward) {
      double initial() {
         return this.edge - (double)this.outward * 0.25;
      }

      double attached(double width) {
         return this.edge + (double)this.outward * (width / 2.0 - 1.0);
      }
   }

   public static enum Sides {
      BOTH("两侧"),
      RIGHT("A→B 右侧"),
      LEFT("A→B 左侧");

      public final String label;

      private Sides(String s) {
         this.label = s;
      }

      boolean includes(int side) {
         return this == BOTH || side == (this == RIGHT ? 1 : -1);
      }
   }
}
