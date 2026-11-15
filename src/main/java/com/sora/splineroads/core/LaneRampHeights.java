package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class LaneRampHeights {
   public static RoadGeometry.Mesh adjust(RoadGeometry.Mesh base, double from, double to, double lift) {
      return adjust(base, from, to, lift, 0.15);
   }

   public static RoadGeometry.Mesh adjust(RoadGeometry.Mesh base, double from, double to, double lift, double maxGrade) {
      LaneRampGrade.checked(maxGrade);
      if (to - from < 8.0) {
         throw new IllegalArgumentException("主路之间没有足够的升降过渡空间");
      } else {
         ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

         for (RoadGeometry.Sample s : base.samples()) {
            double t = Math.max(0.0, Math.min(1.0, (s.distance() - from) / (to - from)));
            double dy = lift * Math.pow(Math.sin(Math.PI * t), 2.0);
            RoadGeometry.V p = s.center().add(new RoadGeometry.V(0.0, dy, 0.0));
            if (!samples.isEmpty()) {
               RoadGeometry.V d = p.sub(samples.get(samples.size() - 1).center());
               if (LaneRampGrade.exceeds(d.y(), d.horizontalLength(), maxGrade)) {
                  throw new IllegalArgumentException("上跨／下穿过渡的坡度超过 " + LaneRampGrade.label(maxGrade));
               }
            }

            samples.add(new RoadGeometry.Sample(p, s.left(), s.distance(), s.halfWidth()));
         }

         RoadGeometry.Mesh result = RoadRibbon.mesh(samples, base.settings());
         RoadRibbon.checkSelfIntersections(result, 4.0);
         LaneRampPaths.checkVolume(result);
         return result;
      }
   }

   public static RoadGeometry.Mesh solve(RoadGeometry.Mesh base, double freeFrom, double freeTo, List<LaneRampHeights.Constraint> constraints, boolean over) {
      return solve(base, freeFrom, freeTo, constraints, over, 0.15);
   }

   public static RoadGeometry.Mesh solve(
      RoadGeometry.Mesh base, double freeFrom, double freeTo, List<LaneRampHeights.Constraint> constraints, boolean over, double maxGrade
   ) {
      LaneRampGrade.checked(maxGrade);
      if (constraints.isEmpty()) {
         return base;
      } else if (base.settings().style().connectorRamp()) {
         return LaneRampCorridor.solve(base, freeFrom, freeTo, constraints, over, maxGrade);
      } else {
         int n = base.samples().size();
         double[] horizontal = new double[n];

         for (int i = 1; i < n; i++) {
            horizontal[i] = horizontal[i - 1] + base.samples().get(i).center().sub(base.samples().get(i - 1).center()).horizontalLength();
         }

         double lo = horizontalAt(base, horizontal, freeFrom);
         double hi = horizontalAt(base, horizontal, freeTo);
         ArrayList<LaneRampHeights.Plateau> plateaus = new ArrayList<>();

         for (LaneRampHeights.Constraint c : constraints) {
            if (c.amount() > 0.001) {
               if (c.amount() > 48.0) {
                  throw new IllegalArgumentException("自动跨越需要升降超过 48 格，请扩大道路间距或修改端点高度");
               }

               plateaus.add(
                  new LaneRampHeights.Plateau(horizontalAt(base, horizontal, c.from()) - 1.0, horizontalAt(base, horizontal, c.to()) + 1.0, c.amount())
               );
            }
         }

         if (plateaus.isEmpty()) {
            return base;
         } else {
            plateaus.sort(Comparator.comparingDouble(LaneRampHeights.Plateau::from));
            int i = 1;

            while (i < plateaus.size()) {
               LaneRampHeights.Plateau a = plateaus.get(i - 1);
               LaneRampHeights.Plateau b = plateaus.get(i);
               if (b.from() <= a.to() + 0.01) {
                  plateaus.set(i - 1, merge(a, b));
                  plateaus.remove(i);
               } else {
                  i++;
               }
            }

            ArrayList<LaneRampHeights.Profile> profiles = new ArrayList<>();

            boolean merged;
            do {
               profiles.clear();
               merged = false;

               for (int ix = 0; ix < plateaus.size(); ix++) {
                  LaneRampHeights.Plateau p = plateaus.get(ix);
                  double rise = span(base, horizontal, p, true, over, p.from() - lo, maxGrade);
                  double fall = span(base, horizontal, p, false, over, hi - p.to(), maxGrade);
                  LaneRampHeights.Profile profile = new LaneRampHeights.Profile(p, rise, fall);
                  if (!profiles.isEmpty()) {
                     LaneRampHeights.Profile before = profiles.get(profiles.size() - 1);
                     if (p.from() - rise <= before.plateau().to() + before.fall()) {
                        plateaus.set(ix - 1, merge(before.plateau(), p));
                        plateaus.remove(ix);
                        merged = true;
                        break;
                     }
                  }

                  profiles.add(profile);
               }
            } while (merged);

            ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

            for (int ix = 0; ix < n; ix++) {
               RoadGeometry.Sample s = base.samples().get(ix);
               double delta = 0.0;
               double x = horizontal[ix];

               for (LaneRampHeights.Profile profile : profiles) {
                  LaneRampHeights.Plateau p = profile.plateau();
                  double w = x < p.from()
                     ? smooth((x - p.from() + profile.rise()) / profile.rise())
                     : (x > p.to() ? smooth((p.to() + profile.fall() - x) / profile.fall()) : 1.0);
                  delta = Math.max(delta, p.height() * w);
               }

               RoadGeometry.V position = s.center().add(new RoadGeometry.V(0.0, over ? delta : -delta, 0.0));
               if (!samples.isEmpty()) {
                  RoadGeometry.V d = position.sub(samples.get(samples.size() - 1).center());
                  if (LaneRampGrade.exceeds(d.y(), d.horizontalLength(), maxGrade)) {
                     throw new IllegalArgumentException("障碍高程拟合后的坡度超过 " + LaneRampGrade.label(maxGrade) + "，需要更长的接头或升降空间");
                  }
               }

               samples.add(new RoadGeometry.Sample(position, s.left(), s.distance(), s.halfWidth()));
            }

            RoadGeometry.Mesh result = RoadRibbon.mesh(samples, base.settings());
            RoadRibbon.checkSelfIntersections(result, 4.0);
            LaneRampPaths.checkVolume(result);
            return result;
         }
      }
   }

   private static LaneRampHeights.Plateau merge(LaneRampHeights.Plateau a, LaneRampHeights.Plateau b) {
      return new LaneRampHeights.Plateau(Math.min(a.from(), b.from()), Math.max(a.to(), b.to()), Math.max(a.height(), b.height()));
   }

   private static double span(RoadGeometry.Mesh base, double[] x, LaneRampHeights.Plateau p, boolean rise, boolean over, double available, double maxGrade) {
      double trial = Math.max(2.0, 1.9 * p.height() / (2.0 * maxGrade));
      double minimum = trial;

      while (trial <= available + 0.001) {
         boolean good = true;
         double edge = rise ? p.from() : p.to();

         for (int i = 1; i < x.length; i++) {
            if (rise ? !(x[i] < edge - trial) && !(x[i - 1] > edge) : !(x[i] < edge) && !(x[i - 1] > edge + trial)) {
               double wa = rise ? smooth((x[i - 1] - edge + trial) / trial) : smooth((edge + trial - x[i - 1]) / trial);
               double wb = rise ? smooth((x[i] - edge + trial) / trial) : smooth((edge + trial - x[i]) / trial);
               double delta = base.samples().get(i).center().y() - base.samples().get(i - 1).center().y() + (double)(over ? 1 : -1) * p.height() * (wb - wa);
               if (LaneRampGrade.exceeds(delta, x[i] - x[i - 1], maxGrade)) {
                  good = false;
                  break;
               }
            }
         }

         if (good) {
            return trial;
         }

         if (trial >= available - 0.001) {
            break;
         }

         trial = Math.min(available, trial * 1.22 + 1.0);
      }

      throw new IllegalArgumentException(
         String.format(
            Locale.ROOT,
            "%s障碍区%s仅余 %.1f 格，无法为 %.2f 格高程调整安排不超过 %s 的平滑坡道（候选起始跨度 %.1f 格）",
            over ? "上跨" : "下穿",
            rise ? "之前" : "之后",
            Math.max(0.0, available),
            p.height(),
            LaneRampGrade.label(maxGrade),
            minimum
         )
      );
   }

   private static double smooth(double t) {
      return RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, t)));
   }

   private static double horizontalAt(RoadGeometry.Mesh mesh, double[] horizontal, double station) {
      List<RoadGeometry.Sample> p = mesh.samples();
      if (station <= 0.0) {
         return 0.0;
      } else if (station >= mesh.length()) {
         return horizontal[horizontal.length - 1];
      } else {
         int lo = 0;
         int hi = p.size() - 1;

         while (hi - lo > 1) {
            int mid = lo + hi >>> 1;
            if (p.get(mid).distance() < station) {
               lo = mid;
            } else {
               hi = mid;
            }
         }

         double t = (station - p.get(lo).distance()) / (p.get(hi).distance() - p.get(lo).distance());
         return horizontal[lo] + t * (horizontal[hi] - horizontal[lo]);
      }
   }

   private LaneRampHeights() {
   }

   public static record Constraint(double from, double to, double amount) {
      public Constraint(double from, double to, double amount) {
         if (RoadGeometry.finite(from, to, amount) && !(from > to) && !(amount < 0.0)) {
            this.from = from;
            this.to = to;
            this.amount = amount;
         } else {
            throw new IllegalArgumentException("跨越高程约束无效");
         }
      }
   }

   private static record Plateau(double from, double to, double height) {
   }

   private static record Profile(LaneRampHeights.Plateau plateau, double rise, double fall) {
   }
}
