package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RoadTransitions {
   private static final WeakIdentityCache<RoadTransitions.Section, RoadTransitions.SectionLayouts> SECTION_LAYOUTS = new WeakIdentityCache<>(512, 1024L, v -> 2);

   public static boolean compatible(RoadGeometry.Settings a, RoadGeometry.Settings b) {
      if (!a.style().ramp() && !b.style().ramp()) {
         RoadProfile.Catalog x = RoadProfile.catalog(a);
         RoadProfile.Catalog y = RoadProfile.catalog(b);
         return (
               x.type() == y.type()
                  || x.type() == RoadProfile.Type.ORDINARY && y.type() == RoadProfile.Type.HIGHWAY
                  || x.type() == RoadProfile.Type.HIGHWAY && y.type() == RoadProfile.Type.ORDINARY
            )
            && x.twoWay() == y.twoWay()
            && Math.abs(RoadLanes.counts(a).forward() - RoadLanes.counts(b).forward()) <= 1
            && Math.abs(RoadLanes.counts(a).reverse() - RoadLanes.counts(b).reverse()) <= 1;
      } else {
         return true;
      }
   }

   public static void requireCompatible(RoadGeometry.Settings a, RoadGeometry.Settings b) {
      if (!compatible(a, b)) {
         throw new IllegalArgumentException("接点两侧断面不匹配：" + description(a) + " ↔ " + description(b) + "；需单双向一致，且每方向车道数最多相差 1");
      }
   }

   private static String description(RoadGeometry.Settings s) {
      return RoadLanes.counts(s).label();
   }

   public static RoadTransitions.Section common(RoadGeometry.Settings a, RoadGeometry.Settings b) {
      requireCompatible(a, b);
      RoadTransitions.Section x = RoadTransitions.Section.of(a);
      RoadTransitions.Section y = RoadTransitions.Section.of(b);
      Comparator<RoadTransitions.Section> order = Comparator.<RoadTransitions.Section>comparingInt(s -> RoadProfile.catalog(s.settings(false)).lanes())
         .thenComparingDouble(RoadTransitions.Section::width)
         .thenComparingInt(s -> RoadLanes.counts(s.settings(false)).forward())
         .thenComparingInt(s -> RoadLanes.counts(s.settings(false)).reverse())
         .thenComparing(s -> s.style().name())
         .thenComparing(RoadTransitions.Section::cycle)
         .thenComparing(RoadTransitions.Section::cycleAsphalt)
         .thenComparing(RoadTransitions.Section::curb)
         .thenComparing(RoadTransitions.Section::cycleRail)
         .thenComparing(s -> s.outerRail().name())
         .thenComparing(s -> s.port() == null ? "" : s.port().toString())
         .thenComparing(s -> s.sidewalk() == null ? "" : s.sidewalk().toString())
         .thenComparing(s -> s.streetscape().toString())
         .thenComparing(s -> s.lanes().toString());
      RoadTransitions.Section chosen = order.compare(x, y) >= 0 ? x : y;
      RoadSidewalks.Config wx = a.options().sidewalk();
      RoadSidewalks.Config wy = b.options().sidewalk();
      Comparator<RoadSidewalks.Config> walkOrder = Comparator.<RoadSidewalks.Config, Boolean>comparing(w -> w.enabled())
         .thenComparingInt(RoadSidewalks.Config::width)
         .thenComparing(w -> w.side().name())
         .thenComparing(w -> w.material())
         .thenComparing(w -> w.smooth())
         .thenComparing(w -> w.tactile());
      RoadSidewalks.Config walk = (walkOrder.compare(wx, wy) >= 0 ? wx : wy).tactile(wx.enabled() && wy.enabled() && wx.tactile() && wy.tactile());
      RoadTransitions.Section shared = new RoadTransitions.Section(
         chosen.style(),
         chosen.width(),
         chosen.cycle(),
         chosen.cycleRail(),
         chosen.curb(),
         chosen.outerRail(),
         walk,
         chosen.cycleAsphalt(),
         chosen.port(),
         chosen.streetscape(),
         chosen.lanes()
      );
      return a.structure() != RoadGeometry.Structure.TUNNEL && b.structure() != RoadGeometry.Structure.TUNNEL ? shared : TunnelMedian.seam(shared);
   }

   public static RoadGeometry.Settings join(RoadGeometry.Settings s, RoadGeometry.Settings a, RoadGeometry.Settings b) {
      if (!s.style().ramp() && RoadProfile.modern(s.style())) {
         RoadTransitions.Section start = a != null && !a.style().ramp() ? common(s, a) : null;
         RoadTransitions.Section end = b != null && !b.style().ramp() ? common(s, b) : null;
         return ends(s, start, end);
      } else {
         return s;
      }
   }

   public static RoadGeometry.Settings ends(RoadGeometry.Settings s, RoadTransitions.Section a, RoadTransitions.Section b) {
      if (s.options().ends().persistent()) {
         if (a == null) {
            a = s.options().ends().start();
         }

         if (b == null) {
            b = s.options().ends().end();
         }
      }

      return s.taper(a == null ? s.width() : a.width(), b == null ? s.width() : b.width())
         .options(
            s.options()
               .ends(
                  new RoadTransitions.Ends(
                     a,
                     b,
                     s.options().ends().persistent(),
                     s.options().ends().trimmedStart(),
                     s.options().ends().trimmedEnd(),
                     s.options().ends().port(),
                     s.options().ends().paintPhase(),
                     s.options().ends().startHold(),
                     s.options().ends().endHold()
                  )
               )
         );
   }

   public static double span(RoadGeometry.Settings s, double length) {
      double delta = Math.max(Math.abs(s.startWidth() - s.width()), Math.abs(s.endWidth() - s.width()));
      RoadTransitions.Ends e = s.options().ends();
      boolean both = e.start() != null && !e.start().equals(RoadTransitions.Section.of(s)) && e.end() != null && !e.end().equals(RoadTransitions.Section.of(s));
      return Math.min(length * (both ? 0.45 : 0.9), Math.max(36.0, delta * 8.0));
   }

   private static double weight(double d, double span) {
      return 1.0 - RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, d / Math.max(0.001, span))));
   }

   public static double endWeight(RoadGeometry.Settings s, double d, double length, boolean start, double requestedSpan) {
      RoadTransitions.Ends e = s.options().ends();
      if (e.startHold() == 0.0 && e.endHold() == 0.0) {
         return weight(d, requestedSpan);
      } else {
         double hold = start ? e.startHold() : e.endHold();
         double available = Math.max(0.001, length - e.startHold() - e.endHold());
         return weight(d - hold, Math.min(requestedSpan, available * 0.9));
      }
   }

   private static double endWeight(RoadGeometry.Settings s, double d, double length, boolean start) {
      return endWeight(s, d, length, start, span(s, length));
   }

   public static double width(RoadGeometry.Settings s, double d, double length) {
      return s.width() + (s.startWidth() - s.width()) * endWeight(s, d, length, true) + (s.endWidth() - s.width()) * endWeight(s, length - d, length, false);
   }

   private static RoadTransitions.Blend blend(RoadGeometry.Mesh mesh, RoadGeometry.Sample p) {
      RoadGeometry.Settings s = mesh.settings();
      RoadProfile.Layout base = RoadProfile.layout(s, p.halfWidth() * 2.0);
      RoadTransitions.Ends ends = s.options().ends();
      double station = p.distance() + ends.trimmedStart();
      double length = mesh.length() + ends.trimmedStart() + ends.trimmedEnd();
      RoadTransitions.Section baseSection = RoadTransitions.Section.of(s);
      double startWeight = ends.start() != null && !ends.start().equals(baseSection) ? endWeight(s, station, length, true) : 0.0;
      double endWeight = ends.end() != null && !ends.end().equals(baseSection) ? endWeight(s, length - station, length, false) : 0.0;
      boolean start = startWeight >= endWeight;
      RoadTransitions.Section target = start ? ends.start() : ends.end();
      double w = Math.max(startWeight, endWeight);
      return new RoadTransitions.Blend(base, target == null ? base : target.layout(s.options().leftTraffic()), w);
   }

   public static double greenPortWeight(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample) {
      RoadTransitions.Ends e = mesh.settings().options().ends();
      double station = sample.distance() + e.trimmedStart();
      double length = mesh.length() + e.trimmedStart() + e.trimmedEnd();
      double weight = 0.0;
      if (e.start() != null
         && e.start().port() != null
         && e.start().layout(mesh.settings().options().leftTraffic()).catalog().median() == RoadProfile.Median.GREEN) {
         weight = endWeight(mesh.settings(), station, length, true);
      }

      if (e.end() != null && e.end().port() != null && e.end().layout(mesh.settings().options().leftTraffic()).catalog().median() == RoadProfile.Median.GREEN) {
         weight = Math.max(weight, endWeight(mesh.settings(), length - station, length, false));
      }

      return weight;
   }

   public static double railSeparation(RoadGeometry.Mesh mesh, RoadGeometry.Sample p, double band) {
      return railMeasure(mesh, p, band, false);
   }

   public static double railWidth(RoadGeometry.Mesh mesh, RoadGeometry.Sample p) {
      return railMeasure(mesh, p, 0.0, true);
   }

   private static double railMeasure(RoadGeometry.Mesh mesh, RoadGeometry.Sample p, double band, boolean median) {
      RoadGeometry.Settings s = mesh.settings();
      RoadTransitions.Ends ends = s.options().ends();
      RoadTransitions.Section own = RoadTransitions.Section.of(s);
      double station = p.distance() + ends.trimmedStart();
      double length = mesh.length() + ends.trimmedStart() + ends.trimmedEnd();
      double a = ends.start() != null && !ends.start().equals(own) ? endWeight(s, station, length, true) : 0.0;
      double b = ends.end() != null && !ends.end().equals(own) ? endWeight(s, length - station, length, false) : 0.0;
      RoadTransitions.Section target = a >= b ? ends.start() : ends.end();
      double gap = s.options().streetscape().plantings().railGap();
      double base = railValue(gap, band, median);
      if (target == null) {
         return base;
      } else {
         double other = target.streetscape().plantings().railGap();
         return lerp(base, railValue(other, band, median), Math.max(a, b));
      }
   }

   private static double railValue(double gap, double band, boolean median) {
      return gap == 0.0 ? (double)(median ? 1 : 0) : gap + (median ? 1.5 : band);
   }

   public static RoadProfile.Layout layout(RoadGeometry.Mesh mesh, RoadGeometry.Sample p) {
      if (mesh.settings().options().ends().equals(RoadTransitions.Ends.NONE)) {
         return RoadProfile.layout(mesh.settings(), p.halfWidth() * 2.0);
      } else {
         RoadTransitions.Blend b = blend(mesh, p);
         RoadProfile.Layout a = b.base();
         RoadProfile.Layout z = b.target();
         double w = b.weight();
         int lanes = a.catalog().lanes();

         for (RoadTransitions.Section end : new RoadTransitions.Section[]{mesh.settings().options().ends().start(), mesh.settings().options().ends().end()}) {
            if (end != null) {
               lanes = Math.max(lanes, end.layout(false).catalog().lanes());
            }
         }

         List<Double> dividers = new ArrayList<>();
         if (a.catalog().twoWay()) {
            for (int sign : new int[]{-1, 1}) {
               for (int n = 1; n < dividerLanes(mesh, a, sign); n++) {
                  dividers.add(lerp(divider(a, n, sign), divider(z, n, sign), w));
               }
            }
         } else {
            for (int n = 1; n < lanes; n++) {
               dividers.add(
                  lerp(Math.min(a.motorMax(), a.motorMin() + (double)n * a.laneWidth()), Math.min(z.motorMax(), z.motorMin() + (double)n * z.laneWidth()), w)
               );
            }
         }

         return new RoadProfile.Layout(
            w >= 0.5 ? z.catalog() : a.catalog(),
            lerp(a.laneWidth(), z.laneWidth(), w),
            lerp(a.median(), z.median(), w),
            lerp(a.motorMin(), z.motorMin(), w),
            lerp(a.motorMax(), z.motorMax(), w),
            lerp(a.cycleWidth(), z.cycleWidth(), w),
            lerp(a.curbWidth(), z.curbWidth(), w),
            lerp(a.shoulderWidth(), z.shoulderWidth(), w),
            a.outside(),
            List.copyOf(dividers),
            lerp(a.medianCenter(), z.medianCenter(), w)
         );
      }
   }

   public static boolean greenCycle(RoadGeometry.Mesh mesh, RoadGeometry.Sample p) {
      RoadGeometry.Settings s = mesh.settings();
      RoadTransitions.Ends e = s.options().ends();
      double station = p.distance() + e.trimmedStart();
      double length = mesh.length() + e.trimmedStart() + e.trimmedEnd();
      boolean green = s.options().cycle() && !s.options().cycleAsphalt();
      double a = e.start() == null ? 0.0 : endWeight(s, station, length, true);
      double b = e.end() == null ? 0.0 : endWeight(s, length - station, length, false);
      RoadTransitions.Section target = a >= b ? e.start() : e.end();
      return target != null && target.cycle() && (!s.options().cycle() || Math.max(a, b) >= 0.5) ? !target.cycleAsphalt() : green;
   }

   public static double green(RoadGeometry.Mesh mesh, RoadGeometry.Sample p) {
      RoadTransitions.Blend b = blend(mesh, p);
      return lerp(
            b.base().catalog().median() == RoadProfile.Median.GREEN ? 1.0 : 0.0,
            b.target().catalog().median() == RoadProfile.Median.GREEN ? 1.0 : 0.0,
            b.weight()
         )
         * (1.0 - RoadStreetscape.tunnelBlend(mesh, p));
   }

   public static double dropBoundary(RoadGeometry.Mesh mesh, RoadGeometry.Sample p) {
      RoadTransitions.Ends e = mesh.settings().options().ends();
      if (e.persistent() && e.end() != null) {
         int oldLanes = RoadProfile.catalog(mesh.settings()).lanes();
         int newLanes = RoadProfile.catalog(e.end().settings(false)).lanes();
         if (newLanes >= oldLanes) {
            return Double.NaN;
         } else {
            double length = mesh.length() + e.trimmedStart() + e.trimmedEnd();
            double t = 1.0 - (mesh.length() + e.trimmedEnd() - p.distance()) / span(mesh.settings(), length);
            if (t <= 0.0) {
               return Double.NaN;
            } else {
               RoadProfile.Layout layout = layout(mesh, p);
               double outer = Math.abs(layout.outer(1)) - 0.2;
               double first = Math.min(outer, layout.median() / 2.0 + (double)newLanes / 2.0 * layout.laneWidth());
               return outer - Math.max(0.0, outer - first) * RoadGeometry.Settings.smooth(Math.min(1.0, t * 4.0));
            }
         }
      } else {
         return Double.NaN;
      }
   }

   private static int dividerLanes(RoadGeometry.Mesh mesh, RoadProfile.Layout base, int side) {
      int count = base.lanesOnSide(side);

      for (RoadTransitions.Section end : new RoadTransitions.Section[]{mesh.settings().options().ends().start(), mesh.settings().options().ends().end()}) {
         if (end != null) {
            count = Math.max(count, end.layout(mesh.settings().options().leftTraffic()).lanesOnSide(side));
         }
      }

      return count;
   }

   private static double divider(RoadProfile.Layout l, int lane, int side) {
      if (l.transitionDividers() != null) {
         List<Double> offsets = l.dividers().stream().map(d -> (d - l.medianCenter()) * (double)side).filter(d -> d > 1.0E-7).sorted().toList();
         if (lane <= offsets.size()) {
            return l.medianCenter() + (double)side * offsets.get(lane - 1);
         }
      }

      return l.medianCenter() + (double)side * Math.min(Math.abs(l.outer(side) - l.medianCenter()), l.median() / 2.0 + (double)lane * l.laneWidth());
   }

   private static double lerp(double a, double b, double t) {
      return a + (b - a) * t;
   }

   private RoadTransitions() {
   }

   private static record Blend(RoadProfile.Layout base, RoadProfile.Layout target, double weight) {
   }

   public static record Ends(
      RoadTransitions.Section start,
      RoadTransitions.Section end,
      boolean persistent,
      double trimmedStart,
      double trimmedEnd,
      RoadTransitions.Port port,
      double paintPhase,
      double startHold,
      double endHold
   ) {
      public static final RoadTransitions.Ends NONE = new RoadTransitions.Ends(null, null, false);

      public Ends(
         RoadTransitions.Section start,
         RoadTransitions.Section end,
         boolean persistent,
         double trimmedStart,
         double trimmedEnd,
         RoadTransitions.Port port,
         double paintPhase
      ) {
         this(start, end, persistent, trimmedStart, trimmedEnd, port, paintPhase, 0.0, 0.0);
      }

      public Ends(
         RoadTransitions.Section start, RoadTransitions.Section end, boolean persistent, double trimmedStart, double trimmedEnd, RoadTransitions.Port port
      ) {
         this(start, end, persistent, trimmedStart, trimmedEnd, port, 0.0);
      }

      public Ends(RoadTransitions.Section start, RoadTransitions.Section end, boolean persistent) {
         this(start, end, persistent, 0.0, 0.0, null);
      }

      public Ends(
         RoadTransitions.Section start,
         RoadTransitions.Section end,
         boolean persistent,
         double trimmedStart,
         double trimmedEnd,
         RoadTransitions.Port port,
         double paintPhase,
         double startHold,
         double endHold
      ) {
         if (RoadGeometry.finite(trimmedStart, trimmedEnd, paintPhase, startHold, endHold)
            && !(startHold < 0.0)
            && !(endHold < 0.0)
            && !(startHold > 8192.0)
            && !(endHold > 8192.0)
            && !(trimmedStart < 0.0)
            && !(trimmedEnd < 0.0)
            && !(trimmedStart > 8192.0)
            && !(trimmedEnd > 8192.0)) {
            this.start = start;
            this.end = end;
            this.persistent = persistent;
            this.trimmedStart = trimmedStart;
            this.trimmedEnd = trimmedEnd;
            this.port = port;
            this.paintPhase = paintPhase;
            this.startHold = startHold;
            this.endHold = endHold;
         } else {
            throw new IllegalArgumentException("道路裁剪范围无效");
         }
      }

      public Ends(RoadTransitions.Section start, RoadTransitions.Section end) {
         this(start, end, false);
      }

      public RoadTransitions.Ends hold(double a, double b) {
         return new RoadTransitions.Ends(this.start, this.end, this.persistent, this.trimmedStart, this.trimmedEnd, this.port, this.paintPhase, a, b);
      }

      public RoadTransitions.Ends trim(double a, double b) {
         return new RoadTransitions.Ends(
            this.start, this.end, this.persistent, this.trimmedStart + a, this.trimmedEnd + b, this.port, this.paintPhase, this.startHold, this.endHold
         );
      }

      public RoadTransitions.Ends port(RoadTransitions.Port p) {
         return new RoadTransitions.Ends(
            this.start, this.end, this.persistent, this.trimmedStart, this.trimmedEnd, p, this.paintPhase, this.startHold, this.endHold
         );
      }

      public RoadTransitions.Ends paintPhase(double phase) {
         return new RoadTransitions.Ends(
            this.start, this.end, this.persistent, this.trimmedStart, this.trimmedEnd, this.port, phase, this.startHold, this.endHold
         );
      }
   }

   public static record Port(double motorMin, double motorMax, double median, List<Double> dividers, double curbLeft, double curbRight, double medianCenter) {
      public Port(double motorMin, double motorMax, double median, List<Double> dividers, double curbLeft, double curbRight) {
         this(motorMin, motorMax, median, dividers, curbLeft, curbRight, 0.0);
      }

      public Port(double motorMin, double motorMax, double median, List<Double> dividers, double curbLeft, double curbRight, double medianCenter) {
         dividers = List.copyOf(dividers);
         if (RoadGeometry.finite(motorMin, motorMax, median, curbLeft, curbRight, medianCenter)
            && !(motorMin >= motorMax)
            && !(Math.abs(motorMin) > 64.0)
            && !(Math.abs(motorMax) > 64.0)
            && !(median > 10.0)
            && !(median < 0.0)
            && !(curbLeft < 0.0)
            && !(curbRight < 0.0)
            && !(curbLeft > 8.0)
            && !(curbRight > 8.0)
            && dividers.size() <= 24
            && !dividers.stream().anyMatch(d -> !Double.isFinite(d) || Math.abs(d) > 64.0)) {
            this.motorMin = motorMin;
            this.motorMax = motorMax;
            this.median = median;
            this.dividers = dividers;
            this.curbLeft = curbLeft;
            this.curbRight = curbRight;
            this.medianCenter = medianCenter;
         } else {
            throw new IllegalArgumentException("路口接缝断面无效");
         }
      }
   }

   public static record Section(
      RoadGeometry.Style style,
      double width,
      boolean cycle,
      boolean cycleRail,
      boolean curb,
      RoadProfile.OuterRail outerRail,
      RoadSidewalks.Config sidewalk,
      boolean cycleAsphalt,
      RoadTransitions.Port port,
      RoadStreetscape.Config streetscape,
      RoadLanes.Counts lanes
   ) {
      public Section(
         RoadGeometry.Style style,
         double width,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         RoadProfile.OuterRail rail,
         RoadSidewalks.Config walk,
         boolean asphalt,
         RoadTransitions.Port port,
         RoadStreetscape.Config streetscape
      ) {
         this(style, width, cycle, cycleRail, curb, rail, walk, asphalt, port, streetscape, RoadLanes.Counts.AUTO);
      }

      public Section(
         RoadGeometry.Style style,
         double width,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         RoadProfile.OuterRail rail,
         RoadSidewalks.Config walk,
         boolean asphalt,
         RoadTransitions.Port port
      ) {
         this(
            style,
            width,
            cycle,
            cycleRail,
            curb,
            rail,
            walk,
            asphalt,
            port,
            RoadStreetscape.Config.DEFAULT.separator(cycleRail ? RoadStreetscape.Separator.RAIL : RoadStreetscape.Separator.LINE)
         );
      }

      public Section(
         RoadGeometry.Style style,
         double width,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         RoadProfile.OuterRail rail,
         RoadSidewalks.Config walk,
         boolean asphalt
      ) {
         this(style, width, cycle, cycleRail, curb, rail, walk, asphalt, null);
      }

      public Section(
         RoadGeometry.Style style, double width, boolean cycle, boolean cycleRail, boolean curb, RoadProfile.OuterRail rail, RoadSidewalks.Config walk
      ) {
         this(style, width, cycle, cycleRail, curb, rail, walk, false);
      }

      public Section(RoadGeometry.Style style, double width, boolean cycle, boolean cycleRail, boolean curb, RoadProfile.OuterRail outerRail) {
         this(style, width, cycle, cycleRail, curb, outerRail, null);
      }

      public Section(
         RoadGeometry.Style style,
         double width,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         RoadProfile.OuterRail outerRail,
         RoadSidewalks.Config sidewalk,
         boolean cycleAsphalt,
         RoadTransitions.Port port,
         RoadStreetscape.Config streetscape,
         RoadLanes.Counts lanes
      ) {
         if (lanes == null) {
            lanes = RoadLanes.Counts.AUTO;
         }

         if (style != null && outerRail != null && Double.isFinite(width) && !(width < 2.0) && !(width > RoadLimits.maximumWidth(style))) {
            this.style = style;
            this.width = width;
            this.cycle = cycle;
            this.cycleRail = cycleRail;
            this.curb = curb;
            this.outerRail = outerRail;
            this.sidewalk = sidewalk;
            this.cycleAsphalt = cycleAsphalt;
            this.port = port;
            this.streetscape = streetscape;
            this.lanes = lanes;
         } else {
            throw new IllegalArgumentException("接缝断面无效");
         }
      }

      public static RoadTransitions.Section of(RoadGeometry.Settings s) {
         RoadProfile.Options o = s.options();
         return new RoadTransitions.Section(
            s.style(),
            s.width(),
            o.cycle(),
            o.cycleRail(),
            o.curb(),
            o.outerRail(),
            o.sidewalk(),
            o.cycleAsphalt(),
            o.ends().port(),
            o.streetscape(),
            o.lanes()
         );
      }

      public RoadGeometry.Settings settings(boolean left) {
         return new RoadGeometry.Settings(RoadGeometry.Mode.STRAIGHT, this.style, this.width, 1.0, 0.4, 90.0)
            .options(
               RoadProfile.Options.DEFAULT
                  .traffic(left)
                  .extras(this.cycle, this.cycleRail, this.curb)
                  .cycleFinish(
                     !this.cycle
                        ? RoadProfile.Options.CycleFinish.NONE
                        : (this.cycleAsphalt ? RoadProfile.Options.CycleFinish.ASPHALT : RoadProfile.Options.CycleFinish.GREEN)
                  )
                  .outerRail(this.outerRail)
                  .sidewalk(this.sidewalk == null ? RoadSidewalks.Config.DEFAULT : this.sidewalk)
                  .ends(RoadTransitions.Ends.NONE.port(this.port))
                  .streetscape(this.streetscape)
                  .lanes(this.lanes)
            );
      }

      public RoadTransitions.Section port(RoadTransitions.Port value) {
         return new RoadTransitions.Section(
            this.style,
            this.width,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.outerRail,
            this.sidewalk,
            this.cycleAsphalt,
            value,
            this.streetscape,
            this.lanes
         );
      }

      public RoadProfile.Layout layout(boolean left) {
         RoadTransitions.SectionLayouts layouts = RoadTransitions.SECTION_LAYOUTS
            .get(this, s -> new RoadTransitions.SectionLayouts(s.makeLayout(false), s.makeLayout(true)));
         return left ? layouts.left() : layouts.right();
      }

      private RoadProfile.Layout makeLayout(boolean left) {
         RoadProfile.Layout base = RoadProfile.layout(this.settings(left), this.width);
         return this.port == null
            ? base
            : new RoadProfile.Layout(
               base.catalog(),
               (this.port.motorMax() - this.port.motorMin() - this.port.median()) / (double)base.catalog().lanes(),
               this.port.median(),
               this.port.motorMin(),
               this.port.motorMax(),
               base.cycleWidth(),
               base.curbWidth(),
               base.shoulderWidth(),
               base.outside(),
               this.port.dividers(),
               this.port.medianCenter()
            );
      }
   }

   private static record SectionLayouts(RoadProfile.Layout right, RoadProfile.Layout left) {
   }
}
