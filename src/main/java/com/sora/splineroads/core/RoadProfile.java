package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RoadProfile {
   public static int trafficSign(boolean left) {
      return left ? -1 : 1;
   }

   public static RoadGeometry.V flowOnSide(RoadGeometry.V tangent, RoadProfile.Side side, boolean left) {
      RoadGeometry.V horizontal = tangent.horizontalUnit();
      return horizontal.left().dot(side.vector()) * (double)trafficSign(left) < 0.0 ? tangent.mul(-1.0) : tangent;
   }

   public static List<RoadProfile.Side> sides(RoadGeometry.V tangent) {
      return Math.abs(tangent.z()) >= Math.abs(tangent.x())
         ? List.of(RoadProfile.Side.EAST, RoadProfile.Side.WEST)
         : List.of(RoadProfile.Side.SOUTH, RoadProfile.Side.NORTH);
   }

   public static RoadProfile.Catalog catalog(RoadGeometry.Style s) {
      return RoadProfile.Catalogs.VALUES[s.ordinal()];
   }

   public static RoadProfile.Catalog catalog(RoadGeometry.Style s, RoadProfile.Options o) {
      RoadProfile.Catalog c = catalog(s);
      RoadLanes.Counts n = o.lanes();
      if (s.connectorRamp() && o.lanePoints().link() != null) {
         return new RoadProfile.Catalog(c.type(), o.lanePoints().link().options().lanes(), false, RoadProfile.Median.NONE, false);
      } else {
         return n.explicit() && (c.type() == RoadProfile.Type.ORDINARY || c.type() == RoadProfile.Type.HIGHWAY || s.connectorRamp())
            ? new RoadProfile.Catalog(c.type(), n.total(), n.twoWay(), n.twoWay() ? c.median() : RoadProfile.Median.NONE, c.shoulder())
            : c;
      }
   }

   public static RoadProfile.Catalog catalog(RoadGeometry.Settings s) {
      return catalog(s.style(), s.options());
   }

   private static RoadProfile.Catalog makeCatalog(RoadGeometry.Style s) {
      return switch (s) {
         case O8_YELLOW -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 8, true, RoadProfile.Median.DOUBLE_YELLOW, false);
         case O8_RAIL -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 8, true, RoadProfile.Median.RAIL, false);
         case O8_GREEN -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 8, true, RoadProfile.Median.GREEN, false);
         case O4_ONE -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 4, false, RoadProfile.Median.NONE, false);
         case H8_RAIL -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 8, true, RoadProfile.Median.RAIL, true);
         case H8_GREEN -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 8, true, RoadProfile.Median.GREEN, true);
         case H4_ONE -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 4, false, RoadProfile.Median.NONE, true);
         case O2_YELLOW -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 2, true, RoadProfile.Median.DOUBLE_YELLOW, false);
         case O4_YELLOW -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 4, true, RoadProfile.Median.DOUBLE_YELLOW, false);
         case O6_YELLOW -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 6, true, RoadProfile.Median.DOUBLE_YELLOW, false);
         case O2_DASHED -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 2, true, RoadProfile.Median.DASHED_YELLOW, false);
         case O2_RAIL -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 2, true, RoadProfile.Median.RAIL, false);
         case O4_RAIL -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 4, true, RoadProfile.Median.RAIL, false);
         case O6_RAIL -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 6, true, RoadProfile.Median.RAIL, false);
         case O2_GREEN -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 2, true, RoadProfile.Median.GREEN, false);
         case O4_GREEN -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 4, true, RoadProfile.Median.GREEN, false);
         case O6_GREEN -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 6, true, RoadProfile.Median.GREEN, false);
         case O1_ONE -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 1, false, RoadProfile.Median.NONE, false);
         case O2_ONE -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 2, false, RoadProfile.Median.NONE, false);
         case O3_ONE -> new RoadProfile.Catalog(RoadProfile.Type.ORDINARY, 3, false, RoadProfile.Median.NONE, false);
         case H4_RAIL -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 4, true, RoadProfile.Median.RAIL, true);
         case H6_RAIL -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 6, true, RoadProfile.Median.RAIL, true);
         case H4_GREEN -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 4, true, RoadProfile.Median.GREEN, true);
         case H6_GREEN -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 6, true, RoadProfile.Median.GREEN, true);
         case H4_YELLOW -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 4, true, RoadProfile.Median.DOUBLE_YELLOW, true);
         case H6_YELLOW -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 6, true, RoadProfile.Median.DOUBLE_YELLOW, true);
         case H1_ONE -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 1, false, RoadProfile.Median.NONE, false);
         case H2_ONE -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 2, false, RoadProfile.Median.NONE, true);
         case H3_ONE -> new RoadProfile.Catalog(RoadProfile.Type.HIGHWAY, 3, false, RoadProfile.Median.NONE, true);
         case C1_RAMP, C1_HIGHWAY_RAMP -> new RoadProfile.Catalog(RoadProfile.Type.RAMP, 1, false, RoadProfile.Median.NONE, false);
         case R1 -> new RoadProfile.Catalog(RoadProfile.Type.RAMP, 1, false, RoadProfile.Median.NONE, false);
         case R2 -> new RoadProfile.Catalog(RoadProfile.Type.RAMP, 2, false, RoadProfile.Median.NONE, false);
         case R1_SHOULDER -> new RoadProfile.Catalog(RoadProfile.Type.RAMP, 1, false, RoadProfile.Median.NONE, true);
         case R2_SHOULDER -> new RoadProfile.Catalog(RoadProfile.Type.RAMP, 2, false, RoadProfile.Median.NONE, true);
         default -> new RoadProfile.Catalog(
         RoadProfile.Type.LEGACY,
         s == RoadGeometry.Style.FOUR_LANE ? 4 : (s == RoadGeometry.Style.RAMP_ONE ? 1 : 2),
         s == RoadGeometry.Style.TWO_LANE || s == RoadGeometry.Style.FOUR_LANE || s == RoadGeometry.Style.UNMARKED,
         RoadProfile.Median.NONE,
         false
      );
      };
   }

   public static boolean highway(RoadGeometry.Style s) {
      return catalog(s).type() == RoadProfile.Type.HIGHWAY || s == RoadGeometry.Style.C1_HIGHWAY_RAMP;
   }

   public static boolean modern(RoadGeometry.Style s) {
      return catalog(s).type() != RoadProfile.Type.LEGACY;
   }

   public static RoadGeometry.Settings editable(RoadGeometry.Settings s) {
      if (s.style() != RoadGeometry.Style.H4_YELLOW && s.style() != RoadGeometry.Style.H6_YELLOW) {
         return s;
      } else {
         RoadGeometry.Style style = s.style() == RoadGeometry.Style.H4_YELLOW ? RoadGeometry.Style.H4_RAIL : RoadGeometry.Style.H6_RAIL;
         double width = width(style, s.options(), layout(s, s.width()).laneWidth());
         return new RoadGeometry.Settings(
            s.mode(),
            style,
            width,
            s.thickness(),
            s.tension(),
            s.arcDegrees(),
            s.startWidth() + width - s.width(),
            s.endWidth() + width - s.width(),
            s.structure(),
            s.taperVersion(),
            s.rampTurn(),
            s.options()
         );
      }
   }

   public static List<RoadGeometry.Style> styles(RoadProfile.Type type) {
      return Arrays.stream(RoadGeometry.Style.values())
         .filter(s -> catalog(s).type() == type)
         .filter(
            s -> s != RoadGeometry.Style.H4_YELLOW
                  && s != RoadGeometry.Style.H6_YELLOW
                  && !s.connectorRamp()
                  && s != RoadGeometry.Style.TOLL
                  && s != RoadGeometry.Style.RAILBED
         )
         .toList();
   }

   public static RoadGeometry.Style choose(RoadProfile.Type type, int lanes, boolean twoWay, RoadProfile.Median median, boolean shoulder) {
      RoadProfile.Median supportedMedian = type == RoadProfile.Type.HIGHWAY && twoWay && median != RoadProfile.Median.GREEN ? RoadProfile.Median.RAIL : median;
      return styles(type).stream().filter(s -> {
         RoadProfile.Catalog c = catalog(s);
         return c.lanes() == lanes && c.twoWay() == twoWay && c.median() == supportedMedian && c.shoulder() == shoulder;
      }).findFirst().orElseThrow();
   }

   public static double width(RoadGeometry.Style style, RoadProfile.Options o, double laneWidth) {
      if (style.connectorRamp()) {
         return laneWidth;
      } else {
         RoadProfile.Catalog c = catalog(style, o);
         int sides = c.twoWay() ? 2 : 1;
         return (double)c.lanes() * laneWidth
            + medianWidth(c, o)
            + 1.0
            + (double)(c.shoulder() ? 3 * sides : 0)
            + (
               c.type() == RoadProfile.Type.ORDINARY
                  ? (o.cycle() ? (2.5 + RoadStreetscape.separatorWidth(o)) * (double)sides : 0.0) + (o.curb() ? 0.5 * (double)sides : 0.0)
                  : 0.0
            );
      }
   }

   public static double medianWidth(RoadProfile.Catalog c, RoadProfile.Options o) {
      return c.median() == RoadProfile.Median.GREEN
         ? o.streetscape().plantings().greenWidth()
         : (c.median() == RoadProfile.Median.RAIL ? o.streetscape().plantings().railWidth() : 0.0);
   }

   public static double medianWidth(RoadProfile.Catalog c) {
      return c.median() == RoadProfile.Median.RAIL ? 1.0 : (c.median() == RoadProfile.Median.GREEN ? 3.0 : 0.0);
   }

   public static RoadProfile.Layout layout(RoadGeometry.Settings s, double actualWidth) {
      RoadProfile.Catalog c = catalog(s);
      RoadProfile.Options o = s.options();
      if (!s.style().connectorRamp() && (o.lanePoints().link() == null || !o.lanePoints().link().protectedMerge() || c.lanes() != 1)) {
         if (o.lanes().explicit() && o.ends().port() != null) {
            RoadTransitions.Port p = o.ends().port();
            RoadGeometry.Settings clean = s.options(o.ends(RoadTransitions.Ends.NONE));
            RoadProfile.Layout b = layout(clean, actualWidth);
            return new RoadProfile.Layout(
               c,
               (p.motorMax() - p.motorMin() - p.median()) / (double)c.lanes(),
               p.median(),
               p.motorMin(),
               p.motorMax(),
               b.cycleWidth(),
               b.curbWidth(),
               b.shoulderWidth(),
               b.outside(),
               p.dividers(),
               p.medianCenter()
            );
         } else {
            int sides = c.twoWay() ? 2 : 1;
            int outside = trafficSign(o.leftTraffic());
            double cycle = c.type() == RoadProfile.Type.ORDINARY && o.cycle() ? 2.5 + RoadStreetscape.separatorWidth(o) : 0.0;
            double curb = c.type() == RoadProfile.Type.ORDINARY && o.curb() ? 0.5 : 0.0;
            double shoulder = c.shoulder() ? 3.0 : 0.0;
            double median = medianWidth(c, o);
            double sectionWidth = o.lanePoints().link() != null && c.lanes() == 1 ? actualWidth : s.width();
            double nominal = (sectionWidth - median - 1.0 - (cycle + curb + shoulder) * (double)sides) / (double)c.lanes();
            double scale = s.style().ramp() ? actualWidth / s.width() : 1.0;
            double lw = nominal * scale;
            double m = median * scale;
            double center = c.twoWay() ? 0.0 : (double)(-outside) * (cycle + curb + shoulder) * scale / 2.0;
            double half = (lw * (double)c.lanes() + m) / 2.0;
            return new RoadProfile.Layout(
               c,
               lw,
               m,
               center - half,
               center + half,
               cycle * scale,
               curb * scale,
               shoulder * scale,
               outside,
               null,
               c.twoWay() && o.lanes().explicit()
                  ? (double)(o.leftTraffic() ? o.lanes().forward() - o.lanes().reverse() : o.lanes().reverse() - o.lanes().forward()) * lw / 2.0
                  : 0.0
            );
         }
      } else {
         RoadTransitions.Port p = o.ends().port();
         double low = p == null ? -actualWidth / 2.0 : p.motorMin();
         double high = p == null ? actualWidth / 2.0 : p.motorMax();
         return new RoadProfile.Layout(
            new RoadProfile.Catalog(c.type(), c.lanes(), false, RoadProfile.Median.NONE, false),
            (high - low) / (double)c.lanes(),
            0.0,
            low,
            high,
            0.0,
            0.0,
            0.0,
            trafficSign(o.leftTraffic()),
            null
         );
      }
   }

   public static RoadProfile.Layout layout(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample) {
      List<LanePoints.Band> bands = mesh.settings().options().lanePoints().bands();
      if (mesh.settings().style().connectorRamp() && !bands.isEmpty()) {
         double d = sample.distance() + mesh.settings().options().ends().trimmedStart();
         int lo = 0;
         int hi = bands.size() - 1;

         while (lo + 1 < hi) {
            int m = lo + hi >>> 1;
            if (bands.get(m).station() <= d) {
               lo = m;
            } else {
               hi = m;
            }
         }

         LanePoints.Band a = bands.get(lo);
         LanePoints.Band b = bands.get(hi);
         double t = hi == lo ? 0.0 : Math.max(0.0, Math.min(1.0, (d - a.station()) / (b.station() - a.station())));
         double low = a.low() + (b.low() - a.low()) * t;
         double high = a.high() + (b.high() - a.high()) * t;
         int count = catalog(mesh.settings()).lanes();
         return new RoadProfile.Layout(
            new RoadProfile.Catalog(RoadProfile.Type.RAMP, count, false, RoadProfile.Median.NONE, false),
            (high - low) / (double)count,
            0.0,
            low,
            high,
            0.0,
            0.0,
            0.0,
            trafficSign(mesh.settings().options().leftTraffic()),
            count == 2 ? List.of((low + high) / 2.0) : List.of(),
            0.0
         );
      } else {
         RoadProfile.Layout reserved = LaneSections.layout(mesh, sample);
         RoadProfile.Layout base = reserved != null ? reserved : RoadTransitions.layout(mesh, sample);
         if (mesh.settings().structure() == RoadGeometry.Structure.TUNNEL) {
            return base.catalog().median() == RoadProfile.Median.GREEN ? RaisedRoadProfile.apply(base, 1.0, RoadTransitions.railWidth(mesh, sample)) : base;
         } else if (base.catalog().median() == RoadProfile.Median.GREEN && RoadStreetscape.tunnelBlend(mesh, sample) > 0.0) {
            return RaisedRoadProfile.apply(
               base,
               Math.max(RoadStreetscape.tunnelBlend(mesh, sample), RoadStreetscape.medianConversionBlend(mesh, sample)),
               RoadTransitions.railWidth(mesh, sample)
            );
         } else {
            return reserved == null && base.catalog().twoWay()
               ? RaisedRoadProfile.apply(base, RoadStreetscape.medianConversionBlend(mesh, sample), RoadTransitions.railWidth(mesh, sample))
               : base;
         }
      }
   }

   public static double curbExtent(RoadProfile.Layout layout, RoadGeometry.Sample sample, int side) {
      if (!(layout.curbWidth() <= 0.0) && (layout.catalog().twoWay() || layout.outside() == side)) {
         double available = sample.halfWidth() - Math.abs(layout.outer(side)) - layout.cycleWidth() - layout.shoulderWidth();
         return Math.max(0.0, Math.min(available, 2.0 * layout.curbWidth()));
      } else {
         return 0.0;
      }
   }

   public static String medianName(RoadProfile.Median m) {
      return switch (m) {
         case DOUBLE_YELLOW -> "双黄实线";
         case DASHED_YELLOW -> "黄虚线";
         case RAIL -> "中央护栏";
         case GREEN -> "中央绿化带";
         case NONE -> "无中央隔离";
      };
   }

   private RoadProfile() {
   }

   public static record Catalog(RoadProfile.Type type, int lanes, boolean twoWay, RoadProfile.Median median, boolean shoulder) {
      public double laneWidth() {
         return this.type == RoadProfile.Type.HIGHWAY ? 5.0 : 4.0;
      }

      public String name() {
         return (this.twoWay ? "双向" : "单向") + this.lanes + "车道";
      }
   }

   private static final class Catalogs {
      static final RoadProfile.Catalog[] VALUES = Arrays.stream(RoadGeometry.Style.values()).map(RoadProfile::makeCatalog).toArray(RoadProfile.Catalog[]::new);
   }

   public static record Control(UUID id, RoadGeometry.V position) {
      public Control(UUID id, RoadGeometry.V position) {
         Objects.requireNonNull(id);
         if (RoadGeometry.finite(position.x(), position.y(), position.z())
            && !(Math.abs(position.x()) > 2.9999984E7)
            && !(Math.abs(position.z()) > 2.9999984E7)
            && !(Math.abs(position.y()) > 4096.0)) {
            this.id = id;
            this.position = position;
         } else {
            throw new IllegalArgumentException("控制点坐标超出范围");
         }
      }
   }

   public static enum JoinMode {
      ADDED,
      OUTER_LANES;
   }

   public static record Layout(
      RoadProfile.Catalog catalog,
      double laneWidth,
      double median,
      double motorMin,
      double motorMax,
      double cycleWidth,
      double curbWidth,
      double shoulderWidth,
      int outside,
      List<Double> transitionDividers,
      double medianCenter
   ) {
      public Layout(
         RoadProfile.Catalog catalog,
         double laneWidth,
         double median,
         double motorMin,
         double motorMax,
         double cycleWidth,
         double curbWidth,
         double shoulderWidth,
         int outside,
         List<Double> dividers
      ) {
         this(catalog, laneWidth, median, motorMin, motorMax, cycleWidth, curbWidth, shoulderWidth, outside, dividers, 0.0);
      }

      public double medianEdge(int side) {
         return this.medianCenter + (double)side * this.median / 2.0;
      }

      public int lanesOnSide(int side) {
         if (!this.catalog.twoWay()) {
            return this.catalog.lanes();
         } else {
            double span = side < 0 ? this.medianEdge(-1) - this.motorMin : this.motorMax - this.medianEdge(1);
            return Math.max(0, (int)Math.floor(span / Math.max(0.001, this.laneWidth) + 0.5));
         }
      }

      public double outer(int side) {
         return side < 0 ? this.motorMin : this.motorMax;
      }

      public double motorCenter() {
         return (this.motorMin + this.motorMax) / 2.0;
      }

      public int[] outsideSides() {
         return this.catalog.twoWay() ? new int[]{-1, 1} : new int[]{this.outside};
      }

      public List<Double> dividers() {
         if (this.transitionDividers != null) {
            return this.transitionDividers;
         } else {
            List<Double> out = new ArrayList<>();
            if (this.catalog.twoWay()) {
               for (int sign : new int[]{-1, 1}) {
                  for (int n = 1; n < this.lanesOnSide(sign); n++) {
                     out.add(this.medianCenter + (double)sign * (this.median / 2.0 + (double)n * this.laneWidth));
                  }
               }
            } else {
               for (int n = 1; n < this.catalog.lanes(); n++) {
                  out.add(this.motorMin + (double)n * this.laneWidth);
               }
            }

            return out;
         }
      }

      public double attachment(int side) {
         return this.outer(side) + (double)side * (this.shoulderWidth > 0.0 ? this.shoulderWidth / 2.0 : -0.25);
      }
   }

   public static enum Median {
      DOUBLE_YELLOW,
      DASHED_YELLOW,
      RAIL,
      GREEN,
      NONE;
   }

   public static record Options(
      boolean leftTraffic,
      boolean cycle,
      boolean cycleRail,
      boolean curb,
      double liftPosition,
      double liftHeight,
      RoadProfile.Ports ports,
      RoadProfile.Routing routing,
      RoadProfile.OuterRail outerRail,
      RoadTransitions.Ends ends,
      RoadSidewalks.Config sidewalk,
      RoadInfrastructure.Config infrastructure,
      List<RoadLaneLines.Edit> laneLines,
      boolean cycleAsphalt,
      boolean hideArrows,
      RoadStreetscape.Config streetscape,
      RoadAttachments.Data attachments,
      LanePoints.Data lanePoints,
      RoadLanes.Counts lanes,
      RailbedProfile railbed,
      List<RailwayCrossings.Surface> levelCrossings
   ) {
      public static final RoadProfile.Options DEFAULT = new RoadProfile.Options(false, false, false, false, 0.5, 0.0);

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends,
         RoadSidewalks.Config sidewalk,
         RoadInfrastructure.Config infrastructure,
         List<RoadLaneLines.Edit> laneLines,
         boolean cycleAsphalt,
         boolean hideArrows,
         RoadStreetscape.Config streetscape,
         RoadAttachments.Data attachments,
         LanePoints.Data lanePoints,
         RoadLanes.Counts lanes,
         RailbedProfile railbed
      ) {
         this(
            leftTraffic,
            cycle,
            cycleRail,
            curb,
            liftPosition,
            liftHeight,
            ports,
            routing,
            outerRail,
            ends,
            sidewalk,
            infrastructure,
            laneLines,
            cycleAsphalt,
            hideArrows,
            streetscape,
            attachments,
            lanePoints,
            lanes,
            railbed,
            List.of()
         );
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends,
         RoadSidewalks.Config sidewalk,
         RoadInfrastructure.Config infrastructure,
         List<RoadLaneLines.Edit> laneLines,
         boolean cycleAsphalt,
         boolean hideArrows,
         RoadStreetscape.Config streetscape,
         RoadAttachments.Data attachments,
         LanePoints.Data lanePoints,
         RoadLanes.Counts lanes
      ) {
         this(
            leftTraffic,
            cycle,
            cycleRail,
            curb,
            liftPosition,
            liftHeight,
            ports,
            routing,
            outerRail,
            ends,
            sidewalk,
            infrastructure,
            laneLines,
            cycleAsphalt,
            hideArrows,
            streetscape,
            attachments,
            lanePoints,
            lanes,
            RailbedProfile.NONE
         );
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends,
         RoadSidewalks.Config sidewalk,
         RoadInfrastructure.Config infrastructure,
         List<RoadLaneLines.Edit> laneLines,
         boolean cycleAsphalt,
         boolean hideArrows,
         RoadStreetscape.Config streetscape,
         RoadAttachments.Data attachments,
         LanePoints.Data lanePoints
      ) {
         this(
            leftTraffic,
            cycle,
            cycleRail,
            curb,
            liftPosition,
            liftHeight,
            ports,
            routing,
            outerRail,
            ends,
            sidewalk,
            infrastructure,
            laneLines,
            cycleAsphalt,
            hideArrows,
            streetscape,
            attachments,
            lanePoints,
            RoadLanes.Counts.AUTO
         );
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends,
         RoadSidewalks.Config sidewalk,
         RoadInfrastructure.Config infrastructure,
         List<RoadLaneLines.Edit> laneLines,
         boolean cycleAsphalt,
         boolean hideArrows,
         RoadStreetscape.Config streetscape
      ) {
         this(
            leftTraffic,
            cycle,
            cycleRail,
            curb,
            liftPosition,
            liftHeight,
            ports,
            routing,
            outerRail,
            ends,
            sidewalk,
            infrastructure,
            laneLines,
            cycleAsphalt,
            hideArrows,
            streetscape,
            RoadAttachments.Data.EMPTY
         );
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends,
         RoadSidewalks.Config sidewalk,
         RoadInfrastructure.Config infrastructure,
         List<RoadLaneLines.Edit> laneLines,
         boolean cycleAsphalt,
         boolean hideArrows,
         RoadStreetscape.Config streetscape,
         RoadAttachments.Data attachments
      ) {
         this(
            leftTraffic,
            cycle,
            cycleRail,
            curb,
            liftPosition,
            liftHeight,
            ports,
            routing,
            outerRail,
            ends,
            sidewalk,
            infrastructure,
            laneLines,
            cycleAsphalt,
            hideArrows,
            streetscape,
            attachments,
            LanePoints.Data.EMPTY
         );
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends,
         RoadSidewalks.Config sidewalk,
         RoadInfrastructure.Config infrastructure,
         List<RoadLaneLines.Edit> laneLines,
         boolean cycleAsphalt,
         boolean hideArrows
      ) {
         this(
            leftTraffic,
            cycle,
            cycleRail,
            curb,
            liftPosition,
            liftHeight,
            ports,
            routing,
            outerRail,
            ends,
            sidewalk,
            infrastructure,
            laneLines,
            cycleAsphalt,
            hideArrows,
            RoadStreetscape.Config.DEFAULT.separator(cycleRail ? RoadStreetscape.Separator.RAIL : RoadStreetscape.Separator.LINE)
         );
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends,
         RoadSidewalks.Config sidewalk,
         RoadInfrastructure.Config infrastructure,
         List<RoadLaneLines.Edit> laneLines,
         boolean cycleAsphalt
      ) {
         this(
            leftTraffic,
            cycle,
            cycleRail,
            curb,
            liftPosition,
            liftHeight,
            ports,
            routing,
            outerRail,
            ends,
            sidewalk,
            infrastructure,
            laneLines,
            cycleAsphalt,
            false
         );
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends,
         RoadSidewalks.Config sidewalk,
         RoadInfrastructure.Config infrastructure,
         List<RoadLaneLines.Edit> laneLines
      ) {
         this(leftTraffic, cycle, cycleRail, curb, liftPosition, liftHeight, ports, routing, outerRail, ends, sidewalk, infrastructure, laneLines, false);
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends,
         RoadSidewalks.Config sidewalk,
         RoadInfrastructure.Config infrastructure
      ) {
         this(leftTraffic, cycle, cycleRail, curb, liftPosition, liftHeight, ports, routing, outerRail, ends, sidewalk, infrastructure, List.of());
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends,
         RoadSidewalks.Config sidewalk
      ) {
         this(leftTraffic, cycle, cycleRail, curb, liftPosition, liftHeight, ports, routing, outerRail, ends, sidewalk, RoadInfrastructure.Config.DEFAULT);
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends
      ) {
         this(leftTraffic, cycle, cycleRail, curb, liftPosition, liftHeight, ports, routing, outerRail, ends, RoadSidewalks.Config.DEFAULT);
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail
      ) {
         this(leftTraffic, cycle, cycleRail, curb, liftPosition, liftHeight, ports, routing, outerRail, RoadTransitions.Ends.NONE);
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing
      ) {
         this(leftTraffic, cycle, cycleRail, curb, liftPosition, liftHeight, ports, routing, RoadProfile.OuterRail.AUTO);
      }

      public Options(boolean left, boolean bike, boolean rail, boolean edge, double pos, double lift) {
         this(left, bike, rail, edge, pos, lift, RoadProfile.Ports.DEFAULT, RoadProfile.Routing.DEFAULT);
      }

      public Options(boolean left, boolean bike, boolean rail, boolean edge, double pos, double lift, RoadProfile.Ports ports) {
         this(left, bike, rail, edge, pos, lift, ports, RoadProfile.Routing.DEFAULT);
      }

      public Options(
         boolean leftTraffic,
         boolean cycle,
         boolean cycleRail,
         boolean curb,
         double liftPosition,
         double liftHeight,
         RoadProfile.Ports ports,
         RoadProfile.Routing routing,
         RoadProfile.OuterRail outerRail,
         RoadTransitions.Ends ends,
         RoadSidewalks.Config sidewalk,
         RoadInfrastructure.Config infrastructure,
         List<RoadLaneLines.Edit> laneLines,
         boolean cycleAsphalt,
         boolean hideArrows,
         RoadStreetscape.Config streetscape,
         RoadAttachments.Data attachments,
         LanePoints.Data lanePoints,
         RoadLanes.Counts lanes,
         RailbedProfile railbed,
         List<RailwayCrossings.Surface> levelCrossings
      ) {
         levelCrossings = List.copyOf(levelCrossings);
         if (levelCrossings.size() > 64) {
            throw new IllegalArgumentException("Too many railway crossings");
         } else {
            if (railbed == null) {
               railbed = RailbedProfile.NONE;
            }

            if (lanes == null) {
               lanes = RoadLanes.Counts.AUTO;
            }

            if (lanePoints == null) {
               lanePoints = LanePoints.Data.EMPTY;
            }

            if (attachments == null) {
               attachments = RoadAttachments.Data.EMPTY;
            }

            Objects.requireNonNull(streetscape);
            cycleRail = cycle && !streetscape.parking() && streetscape.separator() == RoadStreetscape.Separator.RAIL;
            if (cycle && streetscape.parking()) {
               cycleAsphalt = true;
            }

            if (!cycle || !sidewalk.enabled() || streetscape.separator() == RoadStreetscape.Separator.LINE) {
               streetscape = streetscape.walkLamps(false);
            }

            sidewalk = RoadStreetscape.fitWalk(sidewalk, streetscape);
            laneLines = List.copyOf(laneLines);
            if (laneLines.size() > 32 || laneLines.stream().map(RoadLaneLines.Edit::key).distinct().count() != (long)laneLines.size()) {
               throw new IllegalArgumentException("车道线编辑记录重复或过多");
            } else if (infrastructure != null
               && sidewalk != null
               && ends != null
               && outerRail != null
               && ports != null
               && routing != null
               && RoadGeometry.finite(liftPosition, liftHeight)
               && !(liftPosition < 0.15)
               && !(liftPosition > 0.85)
               && !(Math.abs(liftHeight) > 64.0)) {
               this.leftTraffic = leftTraffic;
               this.cycle = cycle;
               this.cycleRail = cycleRail;
               this.curb = curb;
               this.liftPosition = liftPosition;
               this.liftHeight = liftHeight;
               this.ports = ports;
               this.routing = routing;
               this.outerRail = outerRail;
               this.ends = ends;
               this.sidewalk = sidewalk;
               this.infrastructure = infrastructure;
               this.laneLines = laneLines;
               this.cycleAsphalt = cycleAsphalt;
               this.hideArrows = hideArrows;
               this.streetscape = streetscape;
               this.attachments = attachments;
               this.lanePoints = lanePoints;
               this.lanes = lanes;
               this.railbed = railbed;
               this.levelCrossings = levelCrossings;
            } else {
               throw new IllegalArgumentException("控制点位置 15–85%，抬升 -64–64 格");
            }
         }
      }

      public RoadProfile.Options levelCrossings(List<RailwayCrossings.Surface> value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            value
         );
      }

      public RoadProfile.Options railbed(RailbedProfile value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            value,
            this.levelCrossings
         );
      }

      public RoadProfile.Options lanes(RoadLanes.Counts value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            value,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options lanePoints(LanePoints.Data value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            value,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options attachments(RoadAttachments.Data value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            value,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options streetscape(RoadStreetscape.Config v) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            v,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options hideArrows(boolean value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            value,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options.CycleFinish cycleFinish() {
         return !this.cycle
            ? RoadProfile.Options.CycleFinish.NONE
            : (
               this.streetscape.parking()
                  ? RoadProfile.Options.CycleFinish.PARKING
                  : (this.cycleAsphalt ? RoadProfile.Options.CycleFinish.ASPHALT : RoadProfile.Options.CycleFinish.GREEN)
            );
      }

      public RoadProfile.Options cycleFinish(RoadProfile.Options.CycleFinish value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            value != RoadProfile.Options.CycleFinish.NONE,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            value == RoadProfile.Options.CycleFinish.ASPHALT || value == RoadProfile.Options.CycleFinish.PARKING,
            this.hideArrows,
            this.streetscape.parking(value == RoadProfile.Options.CycleFinish.PARKING),
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options laneLines(List<RoadLaneLines.Edit> value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            value,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options infrastructure(RoadInfrastructure.Config value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            value,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options sidewalk(RoadSidewalks.Config value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            value,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options ends(RoadTransitions.Ends value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            value,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options outerRail(RoadProfile.OuterRail value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            value,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options ports(RoadProfile.Ports value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            value,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options sides(RoadProfile.Side a, RoadProfile.Side b) {
         return this.ports(new RoadProfile.Ports(a, b, this.ports.sourceOutset(), this.ports.targetOutset(), this.ports.sourceJoin(), this.ports.targetJoin()));
      }

      public RoadProfile.Options outsets(double a, double b) {
         return this.ports(new RoadProfile.Ports(this.ports.source(), this.ports.target(), a, b, this.ports.sourceJoin(), this.ports.targetJoin()));
      }

      public RoadProfile.Options joins(RoadProfile.JoinMode a, RoadProfile.JoinMode b) {
         return this.ports(new RoadProfile.Ports(this.ports.source(), this.ports.target(), this.ports.sourceOutset(), this.ports.targetOutset(), a, b));
      }

      public RoadProfile.Options route(RoadProfile.Routing value) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            value,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options traffic(boolean value) {
         return new RoadProfile.Options(
            value,
            this.cycle,
            this.cycleRail,
            this.curb,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options extras(boolean bike, boolean rail, boolean edge) {
         return new RoadProfile.Options(
            this.leftTraffic,
            bike,
            rail,
            edge,
            this.liftPosition,
            this.liftHeight,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            rail != this.cycleRail ? this.streetscape.separator(rail ? RoadStreetscape.Separator.RAIL : RoadStreetscape.Separator.LINE) : this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public RoadProfile.Options lift(double pos, double height) {
         return new RoadProfile.Options(
            this.leftTraffic,
            this.cycle,
            this.cycleRail,
            this.curb,
            pos,
            height,
            this.ports,
            this.routing,
            this.outerRail,
            this.ends,
            this.sidewalk,
            this.infrastructure,
            this.laneLines,
            this.cycleAsphalt,
            this.hideArrows,
            this.streetscape,
            this.attachments,
            this.lanePoints,
            this.lanes,
            this.railbed,
            this.levelCrossings
         );
      }

      public static enum CycleFinish {
         NONE("无非机动车道"),
         GREEN("绿色非机动车道"),
         ASPHALT("沥青非机动车道"),
         PARKING("停车线（临时车位）");

         public final String label;

         private CycleFinish(String s) {
            this.label = s;
         }
      }
   }

   public static enum OuterRail {
      AUTO("自动"),
      ON("开启"),
      OFF("关闭"),
      SOUND_LEFT("绿色隔音板·左侧"),
      SOUND_RIGHT("绿色隔音板·右侧"),
      SOUND_BOTH("绿色隔音板·双侧");

      public final String label;

      public boolean sound(int side) {
         return this == SOUND_BOTH || this == SOUND_LEFT && side < 0 || this == SOUND_RIGHT && side > 0;
      }

      private OuterRail(String label) {
         this.label = label;
      }
   }

   public static record Ports(
      RoadProfile.Side source,
      RoadProfile.Side target,
      double sourceOutset,
      double targetOutset,
      RoadProfile.JoinMode sourceJoin,
      RoadProfile.JoinMode targetJoin
   ) {
      public static final RoadProfile.Ports DEFAULT = new RoadProfile.Ports(RoadProfile.Side.AUTO, RoadProfile.Side.AUTO, 0.0, 0.0);

      public Ports(RoadProfile.Side a, RoadProfile.Side b, double x, double y) {
         this(a, b, x, y, RoadProfile.JoinMode.ADDED, RoadProfile.JoinMode.ADDED);
      }

      public Ports(
         RoadProfile.Side source,
         RoadProfile.Side target,
         double sourceOutset,
         double targetOutset,
         RoadProfile.JoinMode sourceJoin,
         RoadProfile.JoinMode targetJoin
      ) {
         if (source != null
            && target != null
            && sourceJoin != null
            && targetJoin != null
            && RoadGeometry.finite(sourceOutset, targetOutset)
            && !(sourceOutset < 0.0)
            && !(targetOutset < 0.0)
            && !(sourceOutset > 64.0)
            && !(targetOutset > 64.0)) {
            this.source = source;
            this.target = target;
            this.sourceOutset = sourceOutset;
            this.targetOutset = targetOutset;
            this.sourceJoin = sourceJoin;
            this.targetJoin = targetJoin;
         } else {
            throw new IllegalArgumentException("无效的匝道接入侧或渐宽范围");
         }
      }
   }

   public static enum RouteKind {
      DIRECT("直接连接"),
      VIA("定向曲线"),
      LOOP_LEFT("左环绕"),
      LOOP_RIGHT("右环绕");

      public final String label;

      private RouteKind(String label) {
         this.label = label;
      }
   }

   public static record Routing(
      RoadProfile.RouteKind kind,
      double radius,
      double offsetX,
      double offsetZ,
      boolean fitEdges,
      double transition,
      int fanArms,
      double fanStart,
      double fanEnd,
      List<RoadProfile.Control> controls,
      boolean through
   ) {
      public static final RoadProfile.Routing DEFAULT = new RoadProfile.Routing(RoadProfile.RouteKind.DIRECT, 24.0, 0.0, 0.0, true);

      public Routing(
         RoadProfile.RouteKind kind,
         double radius,
         double x,
         double z,
         boolean fit,
         double transition,
         int arms,
         double start,
         double end,
         List<RoadProfile.Control> controls
      ) {
         this(kind, radius, x, z, fit, transition, arms, start, end, controls, false);
      }

      public Routing(RoadProfile.RouteKind kind, double radius, double x, double z, boolean fit, double transition, int arms, double start, double end) {
         this(kind, radius, x, z, fit, transition, arms, start, end, List.of());
      }

      public Routing(RoadProfile.RouteKind kind, double radius, double x, double z, boolean fit, double transition) {
         this(kind, radius, x, z, fit, transition, 0, 0.0, 0.0);
      }

      public Routing(RoadProfile.RouteKind kind, double radius, double x, double z, boolean fit) {
         this(kind, radius, x, z, fit, 36.0);
      }

      public Routing(
         RoadProfile.RouteKind kind,
         double radius,
         double offsetX,
         double offsetZ,
         boolean fitEdges,
         double transition,
         int fanArms,
         double fanStart,
         double fanEnd,
         List<RoadProfile.Control> controls,
         boolean through
      ) {
         controls = List.copyOf(controls);
         if (controls.size() > 32 || controls.stream().map(RoadProfile.Control::id).distinct().count() != (long)controls.size()) {
            throw new IllegalArgumentException("曲线控制点最多32个，且不能重复");
         } else if ((fanArms == 0 || fanArms == 5 || fanArms == 6)
            && RoadGeometry.finite(fanStart, fanEnd)
            && !(fanStart < 0.0)
            && !(fanEnd < 0.0)
            && !(fanStart > 512.0)
            && !(fanEnd > 512.0)) {
            if (kind != null
               && RoadGeometry.finite(radius, offsetX, offsetZ, transition)
               && !(transition < 18.0)
               && !(transition > 96.0)
               && !(radius < 8.0)
               && !(radius > 96.0)
               && !(Math.abs(offsetX) > 128.0)
               && !(Math.abs(offsetZ) > 128.0)) {
               this.kind = kind;
               this.radius = radius;
               this.offsetX = offsetX;
               this.offsetZ = offsetZ;
               this.fitEdges = fitEdges;
               this.transition = transition;
               this.fanArms = fanArms;
               this.fanStart = fanStart;
               this.fanEnd = fanEnd;
               this.controls = controls;
               this.through = through;
            } else {
               throw new IllegalArgumentException("过渡 18–96 格，环绕半径 8–96 格，水平偏移 ±128 格");
            }
         } else {
            throw new IllegalArgumentException("多向分流参数无效");
         }
      }

      public RoadProfile.Routing shape(RoadProfile.RouteKind value, double r) {
         return new RoadProfile.Routing(
            value, r, this.offsetX, this.offsetZ, this.fitEdges, this.transition, this.fanArms, this.fanStart, this.fanEnd, this.controls, this.through
         );
      }

      public RoadProfile.Routing offset(double x, double z) {
         return new RoadProfile.Routing(
            this.kind, this.radius, x, z, this.fitEdges, this.transition, this.fanArms, this.fanStart, this.fanEnd, this.controls, this.through
         );
      }

      public RoadProfile.Routing transition(double value) {
         return new RoadProfile.Routing(
            this.kind, this.radius, this.offsetX, this.offsetZ, this.fitEdges, value, this.fanArms, this.fanStart, this.fanEnd, this.controls, this.through
         );
      }

      public RoadProfile.Routing fit(boolean value) {
         return new RoadProfile.Routing(
            this.kind, this.radius, this.offsetX, this.offsetZ, value, this.transition, this.fanArms, this.fanStart, this.fanEnd, this.controls, this.through
         );
      }

      public RoadProfile.Routing controls(List<RoadProfile.Control> value) {
         return new RoadProfile.Routing(
            this.kind, this.radius, this.offsetX, this.offsetZ, this.fitEdges, this.transition, this.fanArms, this.fanStart, this.fanEnd, value, this.through
         );
      }

      public RoadProfile.Routing through(boolean value) {
         return new RoadProfile.Routing(
            this.kind, this.radius, this.offsetX, this.offsetZ, this.fitEdges, this.transition, this.fanArms, this.fanStart, this.fanEnd, this.controls, value
         );
      }

      public RoadProfile.Routing fan(int arms, double start, double end) {
         return new RoadProfile.Routing(
            this.kind, this.radius, this.offsetX, this.offsetZ, this.fitEdges, this.transition, arms, start, end, this.controls, this.through
         );
      }
   }

   public static enum Side {
      AUTO,
      EAST,
      WEST,
      NORTH,
      SOUTH;

      public RoadGeometry.V vector() {
         return switch (this) {
            case AUTO -> new RoadGeometry.V(0.0, 0.0, 0.0);
            case EAST -> new RoadGeometry.V(1.0, 0.0, 0.0);
            case WEST -> new RoadGeometry.V(-1.0, 0.0, 0.0);
            case NORTH -> new RoadGeometry.V(0.0, 0.0, -1.0);
            case SOUTH -> new RoadGeometry.V(0.0, 0.0, 1.0);
         };
      }

      public String label() {
         return switch (this) {
            case AUTO -> "自动";
            case EAST -> "东侧";
            case WEST -> "西侧";
            case NORTH -> "北侧";
            case SOUTH -> "南侧";
         };
      }
   }

   public static enum Type {
      ORDINARY,
      HIGHWAY,
      RAMP,
      LEGACY;
   }
}
