package com.sora.splineroads.core;

import java.util.Locale;

public final class RoadTunnelFit {
   public static boolean enabled(RoadGeometry.Settings s) {
      return (s.structure() == RoadGeometry.Structure.TUNNEL || s.structure() == RoadGeometry.Structure.BRIDGE)
         && s.options().infrastructure().adjustment() != RoadTunnelFit.Adjustment.OFF;
   }

   public static RoadPlanner.Plan plan(RoadPlanner.Hint a, RoadPlanner.Hint b, RoadGeometry.Settings s) {
      try {
         return RoadPlanner.plan(a, b, s);
      } catch (IllegalArgumentException var19) {
         if (!enabled(s)) {
            throw var19;
         } else {
            double length = b.node().position().sub(a.node().position()).horizontalLength();
            double limit = 2048.0 - length;
            double low = 0.0;
            double high = 8.0;
            RoadPlanner.Plan best = null;

            for (int i = 0; i < 24 && limit > 0.0; i++) {
               high = Math.min(limit, high);

               try {
                  best = extended(a, b, s, high);
                  break;
               } catch (IllegalArgumentException var18) {
                  low = high;
                  if (high >= limit) {
                     break;
                  }

                  high = Math.min(limit, Math.ceil(high * 1.4 + 8.0));
               }
            }

            if (best == null) {
               throw new IllegalArgumentException("自动延长后仍不能满足桥隧高差；请检查端点方向、坡度或分段建造");
            } else {
               for (int i = 0; i < 10 && high - low > 1.0; i++) {
                  double mid = Math.floor((low + high) / 2.0);

                  try {
                     RoadPlanner.Plan candidate = extended(a, b, s, mid);
                     best = candidate;
                     high = mid;
                  } catch (IllegalArgumentException var17) {
                     low = mid;
                  }
               }

               return best;
            }
         }
      }
   }

   private static RoadPlanner.Plan extended(RoadPlanner.Hint a, RoadPlanner.Hint b, RoadGeometry.Settings s, double extension) {
      RoadGeometry.V axis = b.node().position().sub(a.node().position()).horizontalUnit();
      RoadTunnelFit.Adjustment mode = s.options().infrastructure().adjustment();
      double start = mode == RoadTunnelFit.Adjustment.END ? 0.0 : (mode == RoadTunnelFit.Adjustment.START ? extension : extension / 2.0);
      double end = extension - start;
      RoadPlanner.Plan p = RoadPlanner.plan(move(a, axis.mul(-start)), move(b, axis.mul(end)), s);
      return new RoadPlanner.Plan(p.start(), p.end(), p.settings(), p.mesh(), String.format(Locale.ROOT, "桥隧端点调整：起点 %.1f / 终点 %.1f 格", start, end));
   }

   private static RoadPlanner.Hint move(RoadPlanner.Hint h, RoadGeometry.V delta) {
      RoadGeometry.Node n = h.node();
      return new RoadPlanner.Hint(new RoadGeometry.Node(n.position().add(delta), n.yaw(), n.grade()), h.headingLocked(), h.gradeLocked(), h.linked());
   }

   private RoadTunnelFit() {
   }

   public static enum Adjustment {
      OFF("关闭"),
      BOTH("两端"),
      START("仅起点"),
      END("仅终点");

      public final String label;

      private Adjustment(String label) {
         this.label = label;
      }
   }
}
