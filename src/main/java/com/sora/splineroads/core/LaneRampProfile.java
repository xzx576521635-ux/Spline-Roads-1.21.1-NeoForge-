package com.sora.splineroads.core;

import java.util.Locale;

public final class LaneRampProfile {
   public static double height(double from, double to, double firstGrade, double lastGrade, double length, double station) {
      validate(from, to, firstGrade, lastGrade, length, station);
      double d = Math.max(0.0, Math.min(length, station));
      double delta = to - from;
      if (Math.abs(firstGrade) < 1.0E-10 && Math.abs(lastGrade) < 1.0E-10) {
         double t = d / length;
         return from + delta * t * t * (3.0 - 2.0 * t);
      } else {
         double sign = Math.signum(delta);
         if (sign != 0.0 && sign * firstGrade >= 0.0 && sign * lastGrade >= 0.0) {
            return monotone(from, to, firstGrade, lastGrade, length, d, Double.POSITIVE_INFINITY);
         } else {
            double g = delta / length;
            double span = span(length);
            return from + g * d + (firstGrade - g) * bump(d, span) - (lastGrade - g) * bump(length - d, span);
         }
      }
   }

   public static double height(double from, double to, double firstGrade, double lastGrade, double length, double station, double maxGrade) {
      validate(from, to, firstGrade, lastGrade, length, station);
      LaneRampGrade.checked(maxGrade);
      if (!LaneRampGrade.exceeds(firstGrade, 1.0, maxGrade) && !LaneRampGrade.exceeds(lastGrade, 1.0, maxGrade)) {
         double delta = to - from;
         double sign = Math.signum(delta);
         double d = Math.max(0.0, Math.min(length, station));
         if (Math.abs(delta) > maxGrade * length + 1.0E-9) {
            throw new IllegalArgumentException(
               String.format(Locale.ROOT, "总水平布坡长度 %.1f 格小于端点高差所需下限 %.1f 格（上限 %s）", length, Math.abs(delta) / maxGrade, LaneRampGrade.label(maxGrade))
            );
         } else if (sign == 0.0 || !(sign * firstGrade >= 0.0) || !(sign * lastGrade >= 0.0)) {
            return height(from, to, firstGrade, lastGrade, length, d);
         } else {
            return Math.abs(firstGrade) < 1.0E-10 && Math.abs(lastGrade) < 1.0E-10 && 1.5 * Math.abs(delta) / length <= maxGrade
               ? height(from, to, firstGrade, lastGrade, length, d)
               : monotone(from, to, firstGrade, lastGrade, length, d, maxGrade);
         }
      } else {
         throw new IllegalArgumentException("端口坡度超过 " + LaneRampGrade.label(maxGrade));
      }
   }

   private static double monotone(double from, double to, double first, double last, double length, double d, double cap) {
      double sign = Math.signum(to - from);
      double rise = Math.abs(to - from);
      double a = sign * first;
      double b = sign * last;
      double s = span(length);
      if (a + b > 0.0) {
         s = Math.min(s, 1.8 * rise / (a + b));
      }

      if (Double.isFinite(cap) && cap - (a + b) / 2.0 > 1.0E-12) {
         s = Math.min(s, 0.98 * Math.max(0.0, cap * length - rise) / (cap - (a + b) / 2.0));
      }

      if (s < 1.0E-10) {
         if (!(Math.abs(a - rise / length) > 1.0E-7) && !(Math.abs(b - rise / length) > 1.0E-7)) {
            return from + (to - from) * (d / length);
         } else {
            throw new IllegalArgumentException("全部水平长度已耗尽坡比预算，无法保留端口纵坡；请增加少量过渡空间");
         }
      } else {
         double middle = (rise - s * (a + b) / 2.0) / (length - s);
         if (d <= s) {
            return from + sign * integral(a, middle, d, s);
         } else {
            return d >= length - s ? to - sign * integral(b, middle, length - d, s) : from + sign * (s * (a + middle) / 2.0 + middle * (d - s));
         }
      }
   }

   private static double integral(double a, double b, double d, double span) {
      double t = d / span;
      return a * d + (b - a) * span * (t * t * t - 0.5 * t * t * t * t);
   }

   private static double span(double length) {
      return Math.min(length / 2.0, Math.max(8.0, Math.min(24.0, length / 4.0)));
   }

   private static double bump(double d, double span) {
      if (d >= span) {
         return 0.0;
      } else {
         double t = 1.0 - d / span;
         return d * t * t;
      }
   }

   private static void validate(double from, double to, double first, double last, double length, double station) {
      if (!RoadGeometry.finite(from, to, first, last, length, station) || length <= 0.0) {
         throw new IllegalArgumentException("无效匝道纵断面");
      }
   }

   private LaneRampProfile() {
   }
}
