package com.sora.splineroads.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

public final class LaneRampCorridor {
   public static RoadGeometry.Mesh solve(
      RoadGeometry.Mesh base, double freeFrom, double freeTo, List<LaneRampHeights.Constraint> constraints, boolean over, double grade
   ) {
      return solveMixed(base, freeFrom, freeTo, constraints.stream().map(c -> new LaneRampCorridor.Bound(c.from(), c.to(), c.amount(), over)).toList(), grade);
   }

   public static RoadGeometry.Mesh solveMixed(RoadGeometry.Mesh base, double freeFrom, double freeTo, List<LaneRampCorridor.Bound> constraints, double grade) {
      return solveMixed(base, freeFrom, freeTo, constraints, grade, false);
   }

   public static RoadGeometry.Mesh solveMixed(
      RoadGeometry.Mesh base, double freeFrom, double freeTo, List<LaneRampCorridor.Bound> constraints, double grade, boolean monotoneOnly
   ) {
      return solveMixed(base, freeFrom, freeTo, constraints, grade, monotoneOnly, null);
   }

   public static RoadGeometry.Mesh solveMixed(
      RoadGeometry.Mesh base,
      double freeFrom,
      double freeTo,
      List<LaneRampCorridor.Bound> constraints,
      double grade,
      boolean monotoneOnly,
      double[] terrainFloors
   ) {
      LaneRampGrade.checked(grade);
      int n = base.samples().size();
      double[] x = new double[n];
      double[] lo = new double[n];
      double[] hi = new double[n];
      double[] y = new double[n];

      for (int i = 0; i < n; i++) {
         RoadGeometry.Sample s = base.samples().get(i);
         y[i] = s.center().y();
         if (i > 0) {
            x[i] = x[i - 1] + s.center().sub(base.samples().get(i - 1).center()).horizontalLength();
         }

         lo[i] = terrainFloors == null ? Double.NEGATIVE_INFINITY : terrainFloors[i];
         hi[i] = Double.POSITIVE_INFINITY;
      }

      int[] begin = new int[constraints.size()];
      int[] end = new int[constraints.size()];
      long visits = 0L;

      for (int c = 0; c < constraints.size(); c++) {
         RoadPlanningBudget.check();
         LaneRampCorridor.Bound b = constraints.get(c);
         if (b.amount() > 48.0) {
            throw new IllegalArgumentException("自动跨越需要升降超过 48 格，请扩大道路间距或修改端点高度");
         }

         begin[c] = firstBoundSample(base, b.from() - 1.0E-7);
         end[c] = lastBoundSample(base, b.to() + 1.0E-7);
         visits += (long)Math.max(0, end[c] - begin[c] + 1);
      }

      if (visits <= 8L * (long)n + 32L * (long)constraints.size()) {
         for (int c = 0; c < constraints.size(); c++) {
            LaneRampCorridor.Bound b = constraints.get(c);

            for (int i = begin[c]; i <= end[c]; i++) {
               if (b.over()) {
                  lo[i] = Math.max(lo[i], y[i] + b.amount());
               } else {
                  hi[i] = Math.min(hi[i], y[i] - b.amount());
               }
            }
         }
      } else {
         record Active(int last, double amount, boolean over) {
         }

         HashMap<Integer, List<Active>> starts = new HashMap<>();

         for (int c = 0; c < constraints.size(); c++) {
            if (begin[c] <= end[c]) {
               LaneRampCorridor.Bound b = constraints.get(c);
               starts.computeIfAbsent(begin[c], kx -> new ArrayList<>()).add(new Active(end[c], b.amount(), b.over()));
            }
         }

         Comparator<Active> order = Comparator.comparingDouble(Active::amount).reversed();
         PriorityQueue<Active> above = new PriorityQueue<>(order);
         PriorityQueue<Active> below = new PriorityQueue<>(order);

         for (int ix = 0; ix < n; ix++) {
            for (Active cx : starts.getOrDefault(ix, List.of())) {
               (cx.over() ? above : below).add(cx);
            }

            while (!above.isEmpty() && above.peek().last() < ix) {
               above.remove();
            }

            while (!below.isEmpty() && below.peek().last() < ix) {
               below.remove();
            }

            if (!above.isEmpty()) {
               lo[ix] = Math.max(lo[ix], y[ix] + above.peek().amount());
            }

            if (!below.isEmpty()) {
               hi[ix] = y[ix] - below.peek().amount();
            }
         }
      }

      for (int ix = 0; ix < n; ix++) {
         if (ix <= 1 || ix >= n - 2 || base.samples().get(ix).distance() <= freeFrom + 1.0E-7 || base.samples().get(ix).distance() >= freeTo - 1.0E-7) {
            if (y[ix] < lo[ix] - 1.0E-7 || y[ix] > hi[ix] + 1.0E-7) {
               throw failure(base, freeFrom, freeTo, grade, ix, "固定接头与真实障碍冲突");
            }

            lo[ix] = hi[ix] = y[ix];
         }
      }

      double[] lower = (double[])lo.clone();
      double[] upper = (double[])hi.clone();
      int direction = y[n - 1] > y[0] + 1.0E-7 ? 1 : (y[n - 1] < y[0] - 1.0E-7 ? -1 : 0);
      boolean tangentAllows = direction == 0
         ? Math.abs(y[1] - y[0]) < 1.0E-7 && Math.abs(y[n - 1] - y[n - 2]) < 1.0E-7
         : (double)direction * (y[1] - y[0]) >= -1.0E-7 && (double)direction * (y[n - 1] - y[n - 2]) >= -1.0E-7;
      int monotone = tangentAllows ? direction : 2;
      if (monotone == 2 || !propagate(x, lo, hi, grade, monotone)) {
         if (monotoneOnly) {
            throw failure(base, freeFrom, freeTo, grade, firstConflict(lo, hi), "此候选无法单调连接，继续检查其他路线");
         }

         lo = lower;
         hi = upper;
         monotone = 2;
         if (!propagate(x, lower, upper, grade, monotone)) {
            throw failure(base, freeFrom, freeTo, grade, firstConflict(lower, upper), "端口、障碍与逐段坡比约束无法同时满足");
         }
      }

      double[] desired = tautProfile(x, lo, hi, y[0], y[n - 1]);
      y[0] = clamp(y[0], lo[0], hi[0]);

      for (int ixx = 1; ixx < n; ixx++) {
         double dx = x[ixx] - x[ixx - 1];
         double down = monotone != 1 && monotone != 0 ? grade * dx : 0.0;
         double up = monotone != -1 && monotone != 0 ? grade * dx : 0.0;
         double low = Math.max(lo[ixx], y[ixx - 1] - down);
         double high = Math.min(hi[ixx], y[ixx - 1] + up);
         if (low > high + 1.0E-7) {
            throw failure(base, freeFrom, freeTo, grade, ixx, "坡线连续解不可达");
         }

         y[ixx] = clamp(desired[ixx], low, high);
      }

      y = roundProfile(x, y, lo, hi, grade, monotone);

      for (int stride : new int[]{1}) {
         for (int pass = 0; pass < 36; pass++) {
            for (int k = 1; k < n - 1; k++) {
               if ((k & 127) == 1) {
                  RoadPlanningBudget.check();
               }

               int ixx = (pass & 1) == 0 ? k : n - 1 - k;
               double dl = x[ixx] - x[ixx - 1];
               double dr = x[ixx + 1] - x[ixx];
               if (!(dl < 1.0E-9) && !(dr < 1.0E-9)) {
                  double low = Math.max(lo[ixx], Math.max(y[ixx - 1] - grade * dl, y[ixx + 1] - grade * dr));
                  double high = Math.min(hi[ixx], Math.min(y[ixx - 1] + grade * dl, y[ixx + 1] + grade * dr));
                  if (monotone == 1) {
                     low = Math.max(low, y[ixx - 1]);
                     high = Math.min(high, y[ixx + 1]);
                  } else if (monotone == -1) {
                     low = Math.max(low, y[ixx + 1]);
                     high = Math.min(high, y[ixx - 1]);
                  } else if (monotone == 0) {
                     low = Math.max(low, y[0]);
                     high = Math.min(high, y[0]);
                  }

                  if (!(low > high + 1.0E-6)) {
                     double numerator = 0.0;
                     double denominator = 0.0;

                     for (int term = -1; term <= 1; term++) {
                        int center = ixx + term * stride;
                        int a = center - stride;
                        int cx = center + stride;
                        if (a >= 0 && cx < n) {
                           double left = x[center] - x[a];
                           double right = x[cx] - x[center];
                           if (!(left < 1.0E-9) && !(right < 1.0E-9)) {
                              double ca = 1.0 / left;
                              double cb = -1.0 / left - 1.0 / right;
                              double cc = 1.0 / right;
                              double own = ixx == a ? ca : (ixx == center ? cb : cc);
                              double other = ca * y[a] + cb * y[center] + cc * y[cx] - own * y[ixx];
                              double weight = 2.0 / (left + right);
                              numerator += own * other * weight;
                              denominator += own * own * weight;
                           }
                        }
                     }

                     if (denominator > 1.0E-12) {
                        y[ixx] = clamp(0.25 * y[ixx] - 0.75 * numerator / denominator, low, high);
                     }
                  }
               }
            }
         }
      }

      ArrayList<RoadGeometry.Sample> result = new ArrayList<>();
      int ixx = 0;

      while (ixx < n) {
         RoadGeometry.Sample s = base.samples().get(ixx);
         if (ixx > 0 && LaneRampGrade.exceeds(y[ixx] - y[ixx - 1], x[ixx] - x[ixx - 1], grade)) {
            throw failure(base, freeFrom, freeTo, grade, ixx, "平滑后的局部坡比未通过");
         }

         if (!(y[ixx] < lo[ixx] - 1.0E-6) && !(y[ixx] > hi[ixx] + 1.0E-6)) {
            if (ixx <= 0
               || monotone == 2
               || (monotone == 0 ? !(Math.abs(y[ixx] - y[ixx - 1]) > 1.0E-6) : !((double)monotone * (y[ixx] - y[ixx - 1]) < -1.0E-6))) {
               result.add(new RoadGeometry.Sample(new RoadGeometry.V(s.center().x(), y[ixx], s.center().z()), s.left(), s.distance(), s.halfWidth()));
               ixx++;
               continue;
            }

            throw failure(base, freeFrom, freeTo, grade, ixx, "无障碍反向起伏未消除");
         }

         throw failure(base, freeFrom, freeTo, grade, ixx, "平滑后的净空边界未通过");
      }

      RoadGeometry.Mesh mesh = RoadRibbon.mesh(result, base.settings());
      RoadRibbon.checkSelfIntersections(mesh, 4.0);
      LaneRampPaths.checkVolume(mesh);
      return mesh;
   }

   static int firstBoundSample(RoadGeometry.Mesh mesh, double from) {
      List<RoadGeometry.Sample> samples = mesh.samples();
      int lo = 0;
      int hi = samples.size();

      while (lo < hi) {
         int mid = lo + hi >>> 1;
         if (samples.get(mid).distance() < from) {
            lo = mid + 1;
         } else {
            hi = mid;
         }
      }

      return lo == samples.size() ? lo : Math.max(0, lo - 1);
   }

   private static double[] roundProfile(double[] x, double[] y, double[] lo, double[] hi, double grade, int monotone) {
      int n = x.length;
      ArrayList<Integer> knots = new ArrayList<>();
      knots.add(0);

      for (int i = 1; i < n - 1; i++) {
         double a = x[i] - x[i - 1];
         double b = x[i + 1] - x[i];
         if (a < 1.0E-9 || b < 1.0E-9 || Math.abs((y[i] - y[i - 1]) / a - (y[i + 1] - y[i]) / b) > 1.0E-7) {
            knots.add(i);
         }
      }

      knots.add(n - 1);
      double[] slopes = new double[knots.size()];
      double[] result = (double[])y.clone();

      for (int k = 1; k < knots.size() - 1; k++) {
         int a = knots.get(k - 1);
         int b = knots.get(k);
         int c = knots.get(k + 1);
         double dl = x[b] - x[a];
         double dr = x[c] - x[b];
         if (!(dl < 1.0E-9) && !(dr < 1.0E-9)) {
            double left = (y[b] - y[a]) / dl;
            double right = (y[c] - y[b]) / dr;
            if (left * right > 0.0) {
               slopes[k] = 3.0 * (dl + dr) / ((2.0 * dr + dl) / left + (dr + 2.0 * dl) / right);
            }
         }
      }

      slopes[0] = (y[1] - y[0]) / Math.max(1.0E-9, x[1] - x[0]);
      slopes[slopes.length - 1] = (y[n - 1] - y[n - 2]) / Math.max(1.0E-9, x[n - 1] - x[n - 2]);

      for (int kx = 0; kx < knots.size() - 1; kx++) {
         int a = knots.get(kx);
         int b = knots.get(kx + 1);
         double length = x[b] - x[a];
         if (!(length < 1.0E-9)) {
            boolean valid = true;
            double previous = y[a];

            for (int ix = a + 1; ix <= b; ix++) {
               double t = (x[ix] - x[a]) / length;
               double t2 = t * t;
               double t3 = t2 * t;
               double value = (2.0 * t3 - 3.0 * t2 + 1.0) * y[a]
                  + (t3 - 2.0 * t2 + t) * length * slopes[kx]
                  + (-2.0 * t3 + 3.0 * t2) * y[b]
                  + (t3 - t2) * length * slopes[kx + 1];
               result[ix] = value;
               if (value < lo[ix] - 1.0E-8
                  || value > hi[ix] + 1.0E-8
                  || LaneRampGrade.exceeds(value - previous, x[ix] - x[ix - 1], grade)
                  || monotone != 2 && (monotone == 0 ? Math.abs(value - previous) > 1.0E-8 : (double)monotone * (value - previous) < -1.0E-8)) {
                  valid = false;
               }

               previous = value;
            }

            if (!valid) {
               System.arraycopy(y, a + 1, result, a + 1, b - a);
            }
         }
      }

      return result;
   }

   static int lastBoundSample(RoadGeometry.Mesh mesh, double to) {
      List<RoadGeometry.Sample> samples = mesh.samples();
      int lo = 0;
      int hi = samples.size();

      while (lo < hi) {
         int mid = lo + hi >>> 1;
         if (samples.get(mid).distance() <= to) {
            lo = mid + 1;
         } else {
            hi = mid;
         }
      }

      return lo == 0 ? -1 : Math.min(samples.size() - 1, lo);
   }

   private static double[] tautProfile(double[] x, double[] lo, double[] hi, double first, double last) {
      int n = x.length;
      double[] result = new double[n];
      result[0] = first;
      result[n - 1] = last;
      ArrayDeque<int[]> spans = new ArrayDeque<>();
      spans.push(new int[]{0, n - 1});

      while (!spans.isEmpty()) {
         RoadPlanningBudget.check();
         int[] span = spans.pop();
         int a = span[0];
         int b = span[1];
         int split = -1;
         double worst = 1.0E-7;
         double value = 0.0;
         double length = x[b] - x[a];

         for (int i = a + 1; i < b; i++) {
            double t = length < 1.0E-9 ? 0.0 : (x[i] - x[a]) / length;
            double target = result[a] + t * (result[b] - result[a]);
            double bound = clamp(target, lo[i], hi[i]);
            double violation = Math.abs(target - bound);
            if (violation > worst) {
               worst = violation;
               split = i;
               value = bound;
            }

            result[i] = target;
         }

         if (split >= 0) {
            result[split] = value;
            spans.push(new int[]{split, b});
            spans.push(new int[]{a, split});
         }
      }

      return result;
   }

   private static boolean propagate(double[] x, double[] lo, double[] hi, double grade, int monotone) {
      for (int pass = 0; pass < 2; pass++) {
         for (int i = 1; i < x.length; i++) {
            if ((i & 0xFF) == 1) {
               RoadPlanningBudget.check();
            }

            double dx = x[i] - x[i - 1];
            double down = monotone != 1 && monotone != 0 ? grade * dx : 0.0;
            double up = monotone != -1 && monotone != 0 ? grade * dx : 0.0;
            lo[i] = Math.max(lo[i], lo[i - 1] - down);
            hi[i] = Math.min(hi[i], hi[i - 1] + up);
         }

         for (int i = x.length - 2; i >= 0; i--) {
            if ((i & 0xFF) == 0) {
               RoadPlanningBudget.check();
            }

            double dx = x[i + 1] - x[i];
            double down = monotone != -1 && monotone != 0 ? grade * dx : 0.0;
            double up = monotone != 1 && monotone != 0 ? grade * dx : 0.0;
            lo[i] = Math.max(lo[i], lo[i + 1] - down);
            hi[i] = Math.min(hi[i], hi[i + 1] + up);
         }
      }

      for (int i = 0; i < x.length; i++) {
         if (lo[i] > hi[i] + 1.0E-7) {
            return false;
         }
      }

      return true;
   }

   private static int firstConflict(double[] lo, double[] hi) {
      for (int i = 0; i < lo.length; i++) {
         if (lo[i] > hi[i] + 1.0E-7) {
            return i;
         }
      }

      return 0;
   }

   private static double clamp(double v, double lo, double hi) {
      return Math.max(lo, Math.min(hi, v));
   }

   private static IllegalArgumentException failure(RoadGeometry.Mesh m, double from, double to, double grade, int i, String reason) {
      LaneRampGrade.Report metrics = LaneRampGrade.report(m, from, to, grade);
      double rise = Math.abs(m.last().center().y() - m.first().center().y());
      RoadGeometry.Sample sample = m.samples().get(i);
      RoadGeometry.V point = sample.center();
      boolean locked = i <= 1 || i >= m.samples().size() - 2 || sample.distance() <= from + 1.0E-7 || sample.distance() >= to - 1.0E-7;
      return new IllegalArgumentException(
         String.format(
            Locale.ROOT,
            "%s；冲突在%s X=%.1f Y=%.1f Z=%.1f（冲突站位／距 A 沿线 %.1f 格，距 B %.1f 格）。%s输入最大坡比 %.2f%% / 上限 %s；总水平路径 %.1f 格，可布坡 %.1f 格，端点变高至少需 %.1f 格。%s",
            reason,
            locked ? "固定接头" : "可调坡段",
            point.x(),
            point.y(),
            point.z(),
            sample.distance(),
            Math.max(0.0, m.length() - sample.distance()),
            metrics.maximum() <= grade + 1.0E-7 ? "这不是输入坡比超限，而是接头／障碍所需高程与局部可达范围冲突。" : "输入路线自身也存在坡比超限。",
            metrics.maximum() * 100.0,
            LaneRampGrade.label(grade),
            metrics.horizontal(),
            metrics.available(),
            rise / grade,
            locked ? "此处必须保持原车道高度；请改变跨越位置或接头位置，单纯提高坡比不能解除固定接头碰撞。" : "请检查该位置的上下跨越关系及距固定接头的升降空间；总长度足够不代表局部净空可达。"
         )
      );
   }

   private LaneRampCorridor() {
   }

   public static record Bound(double from, double to, double amount, boolean over) {
      public Bound(double from, double to, double amount, boolean over) {
         if (RoadGeometry.finite(from, to, amount) && !(from > to)) {
            this.from = from;
            this.to = to;
            this.amount = amount;
            this.over = over;
         } else {
            throw new IllegalArgumentException("跨越高程约束无效");
         }
      }
   }
}
