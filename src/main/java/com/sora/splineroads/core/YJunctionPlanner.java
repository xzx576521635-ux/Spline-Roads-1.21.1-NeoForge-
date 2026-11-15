package com.sora.splineroads.core;

import java.util.List;

public final class YJunctionPlanner {
   public static YJunctionPlanner.Plan plan(
      RoadGeometry.Node a,
      RoadGeometry.Node b,
      RoadGeometry.Node c,
      RoadGeometry.Settings main,
      RoadGeometry.Settings out,
      RoadGeometry.Settings in,
      double tension
   ) {
      RoadProfile.Catalog profile = RoadProfile.catalog(main);
      if (profile.twoWay() && profile.type() != RoadProfile.Type.RAMP && profile.type() != RoadProfile.Type.LEGACY) {
         for (RoadGeometry.Settings s : List.of(out, in)) {
            if (RoadProfile.catalog(s).twoWay() || !RoadProfile.modern(s.style())) {
               throw new IllegalArgumentException("B、C 须为单向道路");
            }
         }

         if (Double.isFinite(tension) && !(tension < 0.2) && !(tension > 0.8)) {
            RoadGeometry.V forward = a.direction();
            double distance = Math.min(a.position().sub(b.position()).horizontalLength(), a.position().sub(c.position()).horizontalLength());
            if (distance < 24.0) {
               throw new IllegalArgumentException("A 至 B/C 至少 24 格，以留出分流过渡空间");
            } else {
               double lead = Math.min(12.0, distance * 0.18);
               RoadGeometry.Node throat = new RoadGeometry.Node(
                  a.position().add(forward.mul(lead)).add(new RoadGeometry.V(0.0, a.grade() * lead, 0.0)), a.yaw(), a.grade()
               );
               RoadGeometry.Settings mainFlat = flat(main, tension);
               RoadGeometry.Mesh stem = RoadGeometry.build(a, throat, mainFlat);
               RoadProfile.Layout layout = RoadProfile.layout(stem, stem.last());
               int side = RoadProfile.trafficSign(main.options().leftTraffic());
               RoadTransitions.Section outgoing = half(main, layout, side, false);
               RoadTransitions.Section incoming = half(main, layout, -side, true);
               double outCenter = carriagewayCenter(stem.last().halfWidth(), layout, side);
               double inCenter = carriagewayCenter(stem.last().halfWidth(), layout, -side);
               RoadGeometry.V right = forward.left();
               RoadGeometry.Node first = new RoadGeometry.Node(throat.position().add(right.mul(outCenter)), a.yaw(), a.grade());
               RoadGeometry.Node last = new RoadGeometry.Node(throat.position().add(right.mul(inCenter)), a.yaw() + 180.0, -a.grade());
               RoadGeometry.Settings outSettings = branch(out, main, tension, outgoing, true);
               RoadGeometry.Settings inSettings = branch(in, main, tension, incoming, false);
               RoadGeometry.Mesh outbound = RoadGeometry.build(first, b, outSettings);
               RoadGeometry.Mesh inbound = RoadGeometry.build(c, last, inSettings);

               for (RoadGeometry.Sample sx : outbound.samples()) {
                  RoadQueries.Projection q = RoadQueries.horizontal(inbound, sx.center());
                  if (sx.center().sub(q.sample().center()).horizontalLength() < sx.halfWidth() + q.sample().halfWidth() - 0.15
                     && Math.abs(sx.center().y() - q.sample().center().y()) < 3.5) {
                     throw new IllegalArgumentException("B/C 方位导致两股车流交叉，请交换 B、C 或调整端点朝向");
                  }
               }

               outbound = phase(outbound, stem.length());
               inbound = phase(inbound, 3.0 - stem.length() - inbound.length());
               return new YJunctionPlanner.Plan(stem, outbound, inbound, throat);
            }
         } else {
            throw new IllegalArgumentException("曲线强度须为 0.2–0.8");
         }
      } else {
         throw new IllegalArgumentException("A 必须为普通或高速双向道路");
      }
   }

   private static RoadGeometry.Mesh phase(RoadGeometry.Mesh mesh, double phase) {
      RoadGeometry.Settings s = mesh.settings();
      return RoadRibbon.mesh(mesh.samples(), s.options(s.options().ends(s.options().ends().paintPhase(phase))));
   }

   private static RoadGeometry.Settings flat(RoadGeometry.Settings s, double t) {
      return new RoadGeometry.Settings(
         RoadGeometry.Mode.CURVE,
         s.style(),
         s.width(),
         s.thickness(),
         t,
         90.0,
         s.width(),
         s.width(),
         s.structure(),
         s.taperVersion(),
         s.rampTurn(),
         s.options()
            .ends(RoadTransitions.Ends.NONE.port(s.options().ends().port()))
            .infrastructure(s.options().infrastructure().clearEdits())
            .laneLines(List.of())
      );
   }

   private static double carriagewayCenter(double halfWidth, RoadProfile.Layout layout, int side) {
      double lo = side < 0 ? -halfWidth : layout.medianEdge(1);
      double hi = side < 0 ? layout.medianEdge(-1) : halfWidth;
      return (lo + hi) / 2.0;
   }

   private static RoadTransitions.Section half(RoadGeometry.Settings main, RoadProfile.Layout layout, int side, boolean reverse) {
      double halfWidth = main.width() / 2.0;
      double center = carriagewayCenter(halfWidth, layout, side);
      double lo = side < 0 ? -halfWidth : layout.medianEdge(1);
      double hi = side < 0 ? layout.medianEdge(-1) : halfWidth;
      double motor0 = side < 0 ? layout.motorMin() : layout.medianEdge(1);
      double motor1 = side < 0 ? layout.medianEdge(-1) : layout.motorMax();
      double flip = reverse ? -1.0 : 1.0;
      List<Double> lines = layout.dividers().stream().filter(d -> d > motor0 + 1.0E-6 && d < motor1 - 1.0E-6).map(d -> flip * (d - center)).sorted().toList();
      double m0 = flip * (motor0 - center);
      double m1 = flip * (motor1 - center);
      int trafficSide = RoadProfile.trafficSign(main.options().leftTraffic());
      int count = side == trafficSide ? RoadLanes.counts(main).forward() : RoadLanes.counts(main).reverse();
      RoadLanes.Counts counts = new RoadLanes.Counts(count, 0);
      RoadGeometry.Style style = RoadLanes.carrier(layout.catalog().type(), counts, RoadProfile.Median.NONE);
      int outside = side * (reverse ? -1 : 1);
      RoadSidewalks.Config walk = main.options().sidewalk().side(outside < 0 ? RoadSidewalks.Side.LEFT : RoadSidewalks.Side.RIGHT);
      RoadTransitions.Port port = new RoadTransitions.Port(
         Math.min(m0, m1), Math.max(m0, m1), 0.0, lines, outside < 0 ? layout.curbWidth() : 0.0, outside > 0 ? layout.curbWidth() : 0.0
      );
      RoadProfile.Options o = main.options();
      return new RoadTransitions.Section(
         style, hi - lo, o.cycle(), o.cycleRail(), o.curb(), o.outerRail(), walk, o.cycleAsphalt(), port, o.streetscape(), counts
      );
   }

   private static RoadGeometry.Settings branch(
      RoadGeometry.Settings target, RoadGeometry.Settings main, double tension, RoadTransitions.Section half, boolean outgoing
   ) {
      RoadGeometry.Settings s = flat(target, tension).options(flat(target, tension).options().traffic(main.options().leftTraffic()));
      RoadTransitions.Section full = RoadTransitions.Section.of(s);
      RoadTransitions.Section base = outgoing ? full : half;
      RoadProfile.Options options = s.options()
         .lanes(base.lanes())
         .ends(new RoadTransitions.Ends(outgoing ? half : full, outgoing ? full : half, true).port(base.port()));
      return new RoadGeometry.Settings(
         RoadGeometry.Mode.CURVE,
         base.style(),
         base.width(),
         s.thickness(),
         tension,
         90.0,
         outgoing ? half.width() : s.width(),
         outgoing ? s.width() : half.width(),
         s.structure(),
         s.taperVersion(),
         s.rampTurn(),
         options
      );
   }

   public static double yaw(RoadGeometry.V direction) {
      return Math.toDegrees(Math.atan2(-direction.x(), direction.z()));
   }

   private YJunctionPlanner() {
   }

   public static record Plan(RoadGeometry.Mesh stem, RoadGeometry.Mesh outbound, RoadGeometry.Mesh inbound, RoadGeometry.Node throat) {
   }
}
