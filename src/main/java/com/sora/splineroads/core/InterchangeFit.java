package com.sora.splineroads.core;

import java.util.Arrays;
import java.util.function.UnaryOperator;

public final class InterchangeFit {
   public static InterchangePlanner.Plan fit(RoadGeometry.Node[] original, RoadGeometry.Settings a, RoadGeometry.Settings b, InterchangePlanner.Options options) {
      return fit(original, a, b, options, null);
   }

   public static InterchangePlanner.Plan fit(
      RoadGeometry.Node[] original,
      RoadGeometry.Settings a,
      RoadGeometry.Settings b,
      InterchangePlanner.Options options,
      UnaryOperator<RoadGeometry.V> conformance
   ) {
      RoadPlanningBudget.check();
      InterchangePlanner.Options fixed = options.adjust(false);
      IllegalArgumentException initial = new IllegalArgumentException("未找到符合净空、坡度和半径的布局");
      InterchangePlanner.Plan fallback = null;

      try {
         InterchangePlanner.Plan originalPlan = InterchangePlanner.plan(original, a, b, fixed, conformance);
         if (!options.allowShrink()) {
            return originalPlan;
         }

         fallback = originalPlan;
      } catch (IllegalArgumentException var42) {
         initial = var42;
      }

      RoadGeometry.Node[] expanded = InterchangePlanner.expand(original, fixed);
      RoadGeometry.V u = expanded[1].position().sub(expanded[0].position()).horizontalUnit();
      RoadGeometry.V v = expanded[3].position().sub(expanded[2].position()).horizontalUnit();
      double det = cross(u, v);
      if (Math.abs(det) < 0.5) {
         if (fallback != null) {
            return fallback;
         } else {
            throw initial;
         }
      } else {
         RoadGeometry.V center = expanded[0].position().add(u.mul(cross(expanded[2].position().sub(expanded[0].position()), v) / det));
         double ya = (expanded[0].position().y() + expanded[1].position().y()) / 2.0;
         double yb = (expanded[2].position().y() + expanded[3].position().y()) / 2.0;
         boolean abUpper = Math.abs(ya - yb) < 1.0E-6 ? options.upper() == 1 : ya > yb;
         double bottom = Math.min(ya, yb);
         double gap = InterchangePlanner.minimumDifference(a, b, fixed);
         double allowedDrop = options.allowShrink() && !options.preset().three ? options.maxLowering() : 0.0;
         double upper = Math.max(bottom + gap, Math.max(ya, yb) - allowedDrop);
         if (options.flatConnector()) {
            upper = Math.max(upper, options.connectorHeight());
         }

         RoadGeometry.Node[] leveled = (RoadGeometry.Node[])original.clone();

         for (int i = 0; i < leveled.length; i++) {
            RoadPlanningBudget.check();
            RoadGeometry.V p = original[i].position();
            double y = i < 2 == abUpper ? upper : bottom;

            for (int j = i < 2 ? 0 : 2; j < Math.min(original.length, i < 2 ? 2 : 4); j++) {
               y = Math.max(y, original[j].position().y() - allowedDrop);
            }

            leveled[i] = new RoadGeometry.Node(new RoadGeometry.V(p.x(), y, p.z()), original[i].yaw(), 0.0);
         }

         if (!Arrays.equals((Object[])original, (Object[])leveled)) {
            try {
               InterchangePlanner.Plan leveledPlan = InterchangePlanner.plan(leveled, a, b, fixed, conformance);
               if (!options.allowShrink()) {
                  return leveledPlan;
               }

               fallback = leveledPlan;
            } catch (IllegalArgumentException var44) {
               initial = var44;
               if (Boolean.getBoolean("sr.fitDebug")) {
                  System.err.println("Leveled fit: " + var44.getMessage());
               }
            }
         }

         double shortest = Double.POSITIVE_INFINITY;

         for (RoadGeometry.Node node : leveled) {
            shortest = Math.min(shortest, node.position().sub(center).horizontalLength());
         }

         if (fixed.preset() == InterchangePlanner.Preset.DOUBLE_TRUMPET) {
            InterchangePlanner.Plan local = doubleTrumpet(leveled, center, a, b, fixed, conformance, options.allowShrink());
            if (local != null) {
               return local;
            }
         }

         double failed = options.allowShrink() ? 45.0 : Math.max(45.0, shortest);
         double target = failed;
         double limit = fixed.preset() == InterchangePlanner.Preset.DOUBLE_TRUMPET ? 2048.0 : 1024.0;
         InterchangePlanner.Plan best = null;

         for (int tries = 0; tries < 20 && target < limit; tries++) {
            RoadPlanningBudget.check();
            target = Math.min(limit, Math.ceil(Math.max(target + 16.0, target * 1.18) / 4.0) * 4.0);

            try {
               best = InterchangePlanner.plan(extend(leveled, center, target, options.allowShrink()), a, b, fixed, conformance);
               break;
            } catch (IllegalArgumentException var43) {
               initial = var43;
               if (Boolean.getBoolean("sr.fitDebug")) {
                  System.err.println("Fit distance=" + target + ": " + var43.getMessage());
               }

               failed = target;
            }
         }

         if (best != null) {
            for (int i = 0; i < 8 && target - failed > 4.0; i++) {
               RoadPlanningBudget.check();
               double mid = Math.ceil((target + failed) / 8.0) * 4.0;
               if (mid >= target) {
                  break;
               }

               try {
                  InterchangePlanner.Plan result = InterchangePlanner.plan(extend(leveled, center, mid, options.allowShrink()), a, b, fixed, conformance);
                  best = result;
                  target = mid;
               } catch (IllegalArgumentException var41) {
                  failed = mid;
               }
            }

            if (fixed.preset() == InterchangePlanner.Preset.DOUBLE_TRUMPET) {
               best = restoreThroughEnds(best, leveled, center, a, b, fixed, conformance);
            }

            return best;
         } else if (fallback != null) {
            return fallback;
         } else {
            throw new IllegalArgumentException("自动调整端点后仍不满足要求：" + initial.getMessage() + "；请调整主路夹角、宽度或预设");
         }
      }
   }

   private static InterchangePlanner.Plan doubleTrumpet(
      RoadGeometry.Node[] nodes,
      RoadGeometry.V center,
      RoadGeometry.Settings a,
      RoadGeometry.Settings b,
      InterchangePlanner.Options fixed,
      UnaryOperator<RoadGeometry.V> conformance,
      boolean shrink
   ) {
      double failed = shrink
         ? 45.0
         : Math.max(45.0, Math.min(nodes[0].position().sub(center).horizontalLength(), nodes[2].position().sub(center).horizontalLength()));
      double target = failed;
      InterchangePlanner.Plan best = null;

      for (int attempt = 0; attempt < 20 && target < 2048.0; attempt++) {
         RoadPlanningBudget.check();
         target = Math.min(2048.0, Math.ceil(Math.max(target + 16.0, target * 1.18) / 4.0) * 4.0);

         try {
            best = InterchangePlanner.plan(trumpetEnds(nodes, center, target, shrink), a, b, fixed, conformance);
            break;
         } catch (IllegalArgumentException var17) {
            failed = target;
         }
      }

      if (best == null) {
         return null;
      } else {
         for (int i = 0; i < 8 && target - failed > 4.0; i++) {
            RoadPlanningBudget.check();
            double mid = Math.ceil((target + failed) / 8.0) * 4.0;
            if (mid >= target) {
               break;
            }

            try {
               best = InterchangePlanner.plan(trumpetEnds(nodes, center, mid, shrink), a, b, fixed, conformance);
               target = mid;
            } catch (IllegalArgumentException var16) {
               failed = mid;
            }
         }

         return best;
      }
   }

   private static RoadGeometry.Node[] trumpetEnds(RoadGeometry.Node[] nodes, RoadGeometry.V center, double target, boolean shrink) {
      RoadGeometry.Node[] out = extend(nodes, center, target, shrink);

      for (int i : new int[]{1, 3}) {
         double original = nodes[i].position().sub(center).horizontalLength();
         if (!shrink || target >= original) {
            out[i] = nodes[i];
         }
      }

      return out;
   }

   private static InterchangePlanner.Plan restoreThroughEnds(
      InterchangePlanner.Plan best,
      RoadGeometry.Node[] original,
      RoadGeometry.V center,
      RoadGeometry.Settings a,
      RoadGeometry.Settings b,
      InterchangePlanner.Options fixed,
      UnaryOperator<RoadGeometry.V> conformance
   ) {
      for (int index : new int[]{1, 3}) {
         if (!(best.anchors().get(index).position().sub(center).horizontalLength() <= original[index].position().sub(center).horizontalLength() + 1.0E-7)) {
            RoadGeometry.Node[] candidate = best.anchors().toArray(RoadGeometry.Node[]::new);
            candidate[index] = original[index];

            try {
               best = InterchangePlanner.plan(candidate, a, b, fixed, conformance);
            } catch (IllegalArgumentException var24) {
               double low = original[index].position().sub(center).horizontalLength();
               double high = best.anchors().get(index).position().sub(center).horizontalLength();

               for (int i = 0; i < 8 && high - low > 4.0; i++) {
                  RoadPlanningBudget.check();
                  double mid = Math.ceil((low + high) / 8.0) * 4.0;
                  if (mid >= high) {
                     break;
                  }

                  candidate = best.anchors().toArray(RoadGeometry.Node[]::new);
                  RoadGeometry.V p = original[index].position();
                  RoadGeometry.V delta = p.sub(center).horizontalUnit();
                  RoadGeometry.V at = center.add(delta.mul(mid));
                  candidate[index] = new RoadGeometry.Node(new RoadGeometry.V(at.x(), p.y(), at.z()), original[index].yaw(), 0.0);

                  try {
                     best = InterchangePlanner.plan(candidate, a, b, fixed, conformance);
                     high = mid;
                  } catch (IllegalArgumentException var23) {
                     low = mid;
                  }
               }
            }
         }
      }

      return best;
   }

   private static RoadGeometry.Node[] extend(RoadGeometry.Node[] nodes, RoadGeometry.V center, double distance, boolean shrink) {
      RoadGeometry.Node[] out = (RoadGeometry.Node[])nodes.clone();

      for (int i = 0; i < out.length; i++) {
         RoadPlanningBudget.check();
         RoadGeometry.V delta = nodes[i].position().sub(center);
         double length = delta.horizontalLength();
         if (length < 1.0E-6) {
            throw new IllegalArgumentException("端点不能位于交点");
         }

         RoadGeometry.V p = center.add(delta.horizontalUnit().mul(shrink ? distance : Math.max(length, distance)));
         out[i] = new RoadGeometry.Node(new RoadGeometry.V(p.x(), nodes[i].position().y(), p.z()), nodes[i].yaw(), 0.0);
      }

      return out;
   }

   private static double cross(RoadGeometry.V a, RoadGeometry.V b) {
      return a.x() * b.z() - a.z() * b.x();
   }

   private InterchangeFit() {
   }
}
