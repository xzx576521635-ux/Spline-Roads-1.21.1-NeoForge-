package com.sora.splineroads.core;

public final class RoadConnectionChecks {
   public static RoadPlanner.Hint atLevel(RoadPlanner.Hint h, double y) {
      if (h == null) {
         return null;
      } else {
         RoadGeometry.Node n = h.node();
         return new RoadPlanner.Hint(
            new RoadGeometry.Node(new RoadGeometry.V(n.position().x(), y, n.position().z()), n.yaw(), 0.0), h.headingLocked(), true, h.linked()
         );
      }
   }

   public static void require(RoadPlanner.Plan plan, RoadPlanner.Hint a, RoadPlanner.Hint b) {
      if (!plan.mesh().closed() && !plan.settings().laneRamp() && !plan.settings().style().ramp()) {
         RoadTunnelFit.Adjustment adjust = plan.settings().options().infrastructure().adjustment();
         boolean fit = RoadTunnelFit.enabled(plan.settings());
         endpoint("A", plan.mesh().first(), plan.start().grade(), a, fit && adjust != RoadTunnelFit.Adjustment.END);
         endpoint("B", plan.mesh().last(), plan.end().grade(), b, fit && adjust != RoadTunnelFit.Adjustment.START);
      }
   }

   private static void endpoint(String name, RoadGeometry.Sample actual, double grade, RoadPlanner.Hint required, boolean fitMayMove) {
      if (required != null && required.linked()) {
         if (!fitMayMove && actual.center().distance(required.node().position()) > 1.0E-6) {
            throw new IllegalArgumentException(name + " 端接点高程/位置不一致；预览不允许建造，请调整端点");
         } else {
            RoadGeometry.V forward = actual.left().left().mul(-1.0).horizontalUnit();
            if (required.headingLocked() && forward.dot(required.node().direction()) < 0.99999999
               || required.gradeLocked() && Math.abs(grade - required.node().grade()) > 1.0E-5) {
               throw new IllegalArgumentException(name + " 端接缝方向或坡度不连续；请切回智能模式或调整端点");
            }
         }
      }
   }

   private RoadConnectionChecks() {
   }
}
