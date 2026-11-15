package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

public final class CurvedRoadPlans {
   public static List<InterchangePlanner.Preset> presets(int count, boolean curved) {
      return count == 4 && curved
         ? List.of(InterchangePlanner.Preset.CLOVERLEAF, InterchangePlanner.Preset.HYBRID, InterchangePlanner.Preset.STACK)
         : InterchangePlanner.presets(count);
   }

   public static InterchangePlanner.Options planningOptions(InterchangePlanner.Options o) {
      return new InterchangePlanner.Options(
         o.preset(),
         o.leftTraffic(),
         o.lanes(),
         o.transition(),
         o.radius(),
         Math.min(12.0, o.clearance() + 0.5),
         o.upper(),
         o.rampWidth(),
         o.adjustEndpoints(),
         o.allowShrink(),
         o.maxLowering(),
         o.junctionAxis(),
         o.loopMask(),
         o.allowAbove(),
         o.flatConnector(),
         o.connectorHeight(),
         o.toll()
      );
   }

   public static InterchangePlanner.Plan interchange(
      RoadGeometry.Node[] nodes, RoadGeometry.Settings first, RoadGeometry.Settings second, InterchangePlanner.Options options, List<RoadAxis> axes
   ) {
      if (axes.stream().noneMatch(RoadAxis::curved)) {
         return InterchangePlanner.plan(nodes, first, second, options);
      } else if (!presets(nodes.length, true).contains(options.preset())) {
         throw new IllegalArgumentException("曲线四向立交仅支持苜蓿、苜蓿＋定向、堆栈");
      } else if (axes.size() == 2 && !axes.stream().anyMatch(ax -> !ax.flat())) {
         RoadAxis a = axes.get(0);
         RoadAxis b = axes.get(1);
         RoadAxis.Crossing cross = RoadAxis.crossing(a, b, nodes.length == 3);
         RoadGeometry.V center = cross.point();
         RoadGeometry.V u = a.tangent(cross.first());
         RoadGeometry.V actualV = b.tangent(cross.second());
         RoadGeometry.V v = u.left().mul(Math.signum(RoadAxis.cross(u, actualV)));
         double det = RoadAxis.cross(u, v);
         if (Math.abs(RoadAxis.cross(u, actualV)) < 0.9) {
            throw new IllegalArgumentException("曲线主路在交点的夹角需为 65°–115°");
         } else {
            RoadGeometry.Node[] flat = (RoadGeometry.Node[])nodes.clone();

            for (int i = 0; i < nodes.length; i++) {
               int axis = i / 2;
               double station = (i % 2 == 0 ? 0.0 : axes.get(axis).length()) - (axis == 0 ? cross.first() : cross.second());
               RoadGeometry.V p = center.add((axis == 0 ? u : v).mul(station));
               flat[i] = new RoadGeometry.Node(new RoadGeometry.V(p.x(), nodes[i].position().y(), p.z()), nodes[i].yaw(), 0.0);
            }

            UnaryOperator<RoadGeometry.V> mapping = px -> {
               RoadGeometry.V d = px.sub(center);
               double x = RoadAxis.cross(d, v) / det;
               double z = RoadAxis.cross(u, d) / det;
               RoadGeometry.V pa = a.at(cross.first() + x);
               RoadGeometry.V pb = b.at(cross.second() + z);
               RoadGeometry.V ta = a.tangent(cross.first() + x);
               RoadGeometry.V tb = b.tangent(cross.second() + z);
               RoadGeometry.V fa = pa.add(rotate(v, u, ta).mul(z));
               RoadGeometry.V fb = pb.add(rotate(u, v, tb).mul(x));
               double xx = x * x;
               double zz = z * z;
               double w = xx + zz < 1.0E-10 ? 0.5 : xx * xx / (xx * xx + zz * zz);
               RoadGeometry.V out = fa.mul(w).add(fb.mul(1.0 - w));
               return new RoadGeometry.V(out.x(), px.y(), out.z());
            };

            try {
               InterchangePlanner.Options fitOptions = planningOptions(options);
               InterchangePlanner.Plan planned = InterchangePlanner.plan(flat, first, second, fitOptions, mapping);
               List<InterchangePlanner.Leg> legs = planned.legs();
               validate(legs, options);
               ArrayList<RoadGeometry.Node> anchors = new ArrayList<>();

               for (int i = 0; i < nodes.length; i++) {
                  RoadGeometry.Node fitted = planned.anchors().get(i);
                  RoadGeometry.V p = mapping.apply(fitted.position());
                  anchors.add(
                     p.distance(nodes[i].position()) < 1.0E-5
                        ? nodes[i]
                        : new RoadGeometry.Node(p, YJunctionPlanner.yaw(axes.get(i / 2).tangent(axes.get(i / 2).project(p))), 0.0)
                  );
               }

               return finish(planned, legs, anchors, mapping.apply(planned.center()));
            } catch (IllegalArgumentException var23) {
               throw new IllegalArgumentException("曲线立交无法满足半径、纵坡或净空：" + var23.getMessage());
            }
         }
      } else {
         throw new IllegalArgumentException("曲线立交的每条主路需各自等高");
      }
   }

   private static RoadGeometry.V rotate(RoadGeometry.V vector, RoadGeometry.V old, RoadGeometry.V next) {
      double cos = old.dot(next);
      double sin = RoadAxis.cross(old, next);
      return new RoadGeometry.V(vector.x() * cos - vector.z() * sin, 0.0, vector.x() * sin + vector.z() * cos);
   }

   public static boolean fixedFrontageHeight(RoadGeometry.Node[] nodes, CorridorPlanner.Config config, RoadAxis axis) {
      return config.kind() == CorridorPlanner.Kind.FRONTAGE && (Math.abs(nodes[0].position().y() - nodes[1].position().y()) > 0.001 || !axis.flat());
   }

   public static InterchangePlanner.Plan corridor(
      RoadGeometry.Node[] nodes,
      RoadGeometry.Settings main,
      RoadGeometry.Settings secondary,
      InterchangePlanner.Options options,
      CorridorPlanner.Config config,
      List<RoadAxis> axes
   ) {
      RoadAxis axis = axes.get(0);
      boolean fixedY = fixedFrontageHeight(nodes, config, axis);
      if (config.kind() != CorridorPlanner.Kind.FRONTAGE) {
         if (axes.stream().anyMatch(a -> a.curved() || !a.flat())) {
            throw new IllegalArgumentException("双层同侧出入口仍需要平直且各自等高的主路");
         } else {
            return CorridorPlanner.plan(nodes, main, secondary, options, config);
         }
      } else if (!axis.curved() && !fixedY) {
         return CorridorPlanner.plan(nodes, main, secondary, options, config);
      } else {
         double y = nodes[0].position().y();
         if (fixedY) {
            config = new CorridorPlanner.Config(config.kind(), config.sides(), CorridorPlanner.Access.NONE, config.gap(), y, config.adjustment());
            options = options.adjust(false);
         }

         RoadGeometry.Node[] flat = new RoadGeometry.Node[]{
            new RoadGeometry.Node(new RoadGeometry.V(0.0, y, 0.0), -90.0, 0.0), new RoadGeometry.Node(new RoadGeometry.V(axis.length(), y, 0.0), -90.0, 0.0)
         };
         InterchangePlanner.Plan result = CorridorPlanner.plan(flat, main, secondary, options, config);
         UnaryOperator<RoadGeometry.V> mapping = px -> {
            RoadGeometry.V c = axis.at(px.x());
            RoadGeometry.V out = c.add(axis.tangent(px.x()).left().mul(px.z()));
            return new RoadGeometry.V(out.x(), px.y(), out.z());
         };
         ArrayList<InterchangePlanner.Leg> legs = new ArrayList<>();

         for (int i = 0; i < result.legs().size(); i++) {
            InterchangePlanner.Leg leg = result.legs().get(i);
            boolean preserveGrade = fixedY && i == 0;
            RoadGeometry.Mesh mesh = map(leg.mesh(), px -> {
               RoadGeometry.V q = mapping.apply(px);
               return preserveGrade ? new RoadGeometry.V(q.x(), axis.at(px.x()).y(), q.z()) : q;
            });
            legs.add(new InterchangePlanner.Leg(leg.name(), leg.from(), leg.to(), mesh));
         }

         validate(legs, options);
         ArrayList<RoadGeometry.Node> anchors = new ArrayList<>();

         for (int i = 0; i < 2; i++) {
            RoadGeometry.V p = legs.get(0).mesh().samples().get(i == 0 ? 0 : legs.get(0).mesh().samples().size() - 1).center();
            anchors.add(
               p.distance(nodes[i].position()) < 1.0E-5
                  ? nodes[i]
                  : new RoadGeometry.Node(p, YJunctionPlanner.yaw(axis.tangent(result.anchors().get(i).position().x())), 0.0)
            );
         }

         return finish(result, legs, anchors, mapping.apply(result.center()));
      }
   }

   private static InterchangePlanner.Plan finish(
      InterchangePlanner.Plan base, List<InterchangePlanner.Leg> legs, List<RoadGeometry.Node> nodes, RoadGeometry.V center
   ) {
      double radius = legs.stream()
         .filter(l -> l.mesh().settings().style().ramp())
         .mapToDouble(l -> RoadRibbon.minRadius(l.mesh()))
         .min()
         .orElse(Double.POSITIVE_INFINITY);
      return new InterchangePlanner.Plan(
         List.copyOf(legs), base.movements(), radius, legs.stream().mapToDouble(l -> l.mesh().max().y()).max().orElse(center.y()), center, List.copyOf(nodes)
      );
   }

   static RoadGeometry.Mesh map(RoadGeometry.Mesh source, UnaryOperator<RoadGeometry.V> mapping) {
      List<RoadGeometry.V> points = source.samples().stream().map(s -> mapping.apply(s.center())).toList();
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

      for (int i = 0; i < points.size(); i++) {
         RoadGeometry.Sample before = source.samples().get(i);
         RoadGeometry.V tangent = before.left().left().mul(-1.0);
         RoadGeometry.V d = mapping.apply(before.center().add(tangent.mul(0.05))).sub(mapping.apply(before.center().sub(tangent.mul(0.05)))).horizontalUnit();
         samples.add(new RoadGeometry.Sample(points.get(i), d.left(), 0.0, before.halfWidth()));
      }

      return RoadRibbon.mesh(samples, source.settings());
   }

   static void validate(List<InterchangePlanner.Leg> legs, InterchangePlanner.Options options) {
      for (InterchangePlanner.Leg leg : legs) {
         RoadGrades.validate(leg.mesh());
         RoadRibbon.checkSelfIntersections(leg.mesh(), options.clearance());
         if (leg.mesh().settings().style().ramp() && RoadRibbon.minRadius(leg.mesh()) + 0.1 < options.radius()) {
            throw new IllegalArgumentException(
               String.format(Locale.ROOT, "%s 实际转弯半径 %.1f 格，要求 %.1f 格", leg.name(), RoadRibbon.minRadius(leg.mesh()), options.radius())
            );
         }
      }

      CorridorPlanner.verify(legs, options.clearance());
   }

   private CurvedRoadPlans() {
   }
}
