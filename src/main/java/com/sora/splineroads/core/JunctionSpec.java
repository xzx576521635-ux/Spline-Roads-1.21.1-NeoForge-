package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record JunctionSpec(
   RoadGeometry.V center,
   JunctionSpec.Kind kind,
   boolean leftTraffic,
   double cornerRadius,
   double islandRadius,
   int ringLanes,
   double ringLaneWidth,
   double thickness,
   JunctionSpec.Control control,
   int greenSeconds,
   int yellowSeconds,
   int allRedSeconds,
   int timeOffset,
   boolean guides,
   boolean greenIsland,
   boolean outerRail,
   List<JunctionSpec.Arm> arms,
   boolean parallelApproaches,
   OneSidedJunction.Source oneSide
) {
   public static final int STRAIGHT = 1;
   public static final int LEFT = 2;
   public static final int RIGHT = 4;
   public static final int UTURN = 8;
   public static final int[] TURNS = new int[]{1, 2, 4, 8};

   public JunctionSpec(
      RoadGeometry.V center,
      JunctionSpec.Kind kind,
      boolean leftTraffic,
      double cornerRadius,
      double islandRadius,
      int ringLanes,
      double ringLaneWidth,
      double thickness,
      JunctionSpec.Control control,
      int greenSeconds,
      int yellowSeconds,
      int allRedSeconds,
      int timeOffset,
      boolean guides,
      boolean greenIsland,
      boolean outerRail,
      List<JunctionSpec.Arm> arms,
      boolean parallelApproaches
   ) {
      this(
         center,
         kind,
         leftTraffic,
         cornerRadius,
         islandRadius,
         ringLanes,
         ringLaneWidth,
         thickness,
         control,
         greenSeconds,
         yellowSeconds,
         allRedSeconds,
         timeOffset,
         guides,
         greenIsland,
         outerRail,
         arms,
         parallelApproaches,
         null
      );
   }

   public JunctionSpec(
      RoadGeometry.V center,
      JunctionSpec.Kind kind,
      boolean leftTraffic,
      double cornerRadius,
      double islandRadius,
      int ringLanes,
      double ringLaneWidth,
      double thickness,
      JunctionSpec.Control control,
      int greenSeconds,
      int yellowSeconds,
      int allRedSeconds,
      int timeOffset,
      boolean guides,
      boolean greenIsland,
      boolean outerRail,
      List<JunctionSpec.Arm> arms
   ) {
      this(
         center,
         kind,
         leftTraffic,
         cornerRadius,
         islandRadius,
         ringLanes,
         ringLaneWidth,
         thickness,
         control,
         greenSeconds,
         yellowSeconds,
         allRedSeconds,
         timeOffset,
         guides,
         greenIsland,
         outerRail,
         arms,
         false
      );
   }

   public JunctionSpec(
      RoadGeometry.V center,
      JunctionSpec.Kind kind,
      boolean leftTraffic,
      double cornerRadius,
      double islandRadius,
      int ringLanes,
      double ringLaneWidth,
      double thickness,
      JunctionSpec.Control control,
      int greenSeconds,
      int yellowSeconds,
      int allRedSeconds,
      int timeOffset,
      boolean guides,
      boolean greenIsland,
      List<JunctionSpec.Arm> arms
   ) {
      this(
         center,
         kind,
         leftTraffic,
         cornerRadius,
         islandRadius,
         ringLanes,
         ringLaneWidth,
         thickness,
         control,
         greenSeconds,
         yellowSeconds,
         allRedSeconds,
         timeOffset,
         guides,
         greenIsland,
         false,
         arms
      );
   }

   public JunctionSpec(
      RoadGeometry.V center,
      JunctionSpec.Kind kind,
      boolean leftTraffic,
      double cornerRadius,
      double islandRadius,
      int ringLanes,
      double ringLaneWidth,
      double thickness,
      JunctionSpec.Control control,
      int greenSeconds,
      int yellowSeconds,
      int allRedSeconds,
      int timeOffset,
      boolean guides,
      boolean greenIsland,
      boolean outerRail,
      List<JunctionSpec.Arm> arms,
      boolean parallelApproaches,
      OneSidedJunction.Source oneSide
   ) {
      arms = List.copyOf(arms);
      if (center != null
         && kind != null
         && control != null
         && (kind == JunctionSpec.Kind.INTERSECTION ? arms.size() >= 2 && arms.size() <= 6 : arms.size() <= 8)
         && RoadGeometry.finite(center.x(), center.y(), center.z(), cornerRadius, islandRadius, ringLaneWidth, thickness)
         && !(Math.abs(center.x()) > 2.9999E7)
         && !(Math.abs(center.z()) > 2.9999E7)
         && !(center.y() < -2040.0)
         && !(center.y() > 2040.0)
         && !(cornerRadius < 2.0)
         && !(cornerRadius > 32.0)
         && !(islandRadius < 6.0)
         && !(islandRadius > 48.0)
         && ringLanes >= 1
         && ringLanes <= 3
         && !(ringLaneWidth < 2.0)
         && !(ringLaneWidth > 6.0)
         && !(thickness < 0.25)
         && !(thickness > 4.0)
         && greenSeconds >= 5
         && greenSeconds <= 120
         && yellowSeconds >= 0
         && yellowSeconds <= 10
         && allRedSeconds >= 0
         && allRedSeconds <= 10
         && Math.abs(timeOffset) <= 86400) {
         this.center = center;
         this.kind = kind;
         this.leftTraffic = leftTraffic;
         this.cornerRadius = cornerRadius;
         this.islandRadius = islandRadius;
         this.ringLanes = ringLanes;
         this.ringLaneWidth = ringLaneWidth;
         this.thickness = thickness;
         this.control = control;
         this.greenSeconds = greenSeconds;
         this.yellowSeconds = yellowSeconds;
         this.allRedSeconds = allRedSeconds;
         this.timeOffset = timeOffset;
         this.guides = guides;
         this.greenIsland = greenIsland;
         this.outerRail = outerRail;
         this.arms = arms;
         this.parallelApproaches = parallelApproaches;
         this.oneSide = oneSide;
      } else {
         throw new IllegalArgumentException("路口参数无效：普通路口 2–6 向，环岛 0–8 个接入口，转角 2–32 格，环岛半径 6–48 格");
      }
   }

   public JunctionSpec oneSide(OneSidedJunction.Source value) {
      return new JunctionSpec(
         this.center,
         this.kind,
         this.leftTraffic,
         this.cornerRadius,
         this.islandRadius,
         this.ringLanes,
         this.ringLaneWidth,
         this.thickness,
         this.control,
         this.greenSeconds,
         this.yellowSeconds,
         this.allRedSeconds,
         this.timeOffset,
         this.guides,
         this.greenIsland,
         this.outerRail,
         this.arms,
         this.parallelApproaches,
         value
      );
   }

   public JunctionSpec parallel(boolean value) {
      return new JunctionSpec(
         this.center,
         this.kind,
         this.leftTraffic,
         this.cornerRadius,
         this.islandRadius,
         this.ringLanes,
         this.ringLaneWidth,
         this.thickness,
         this.control,
         this.greenSeconds,
         this.yellowSeconds,
         this.allRedSeconds,
         this.timeOffset,
         this.guides,
         this.greenIsland,
         this.outerRail,
         this.arms,
         value,
         this.oneSide
      );
   }

   public static JunctionSpec.Arm arm(RoadGeometry.Node node, RoadGeometry.V inward, RoadGeometry.Settings external, boolean incomingOneWay, int phase) {
      RoadProfile.Layout layout = RoadProfile.layout(external, external.width());
      RoadProfile.Catalog c = layout.catalog();
      int in = c.twoWay() ? RoadLanes.counts(external).forward() : (incomingOneWay ? c.lanes() : 0);
      int out = c.twoWay() ? RoadLanes.counts(external).reverse() : (incomingOneWay ? 0 : c.lanes());
      double extras = c.twoWay() ? 1.0 : 0.5;
      return new JunctionSpec.Arm(
         node,
         inward,
         external,
         in,
         out,
         external.width(),
         layout.median(),
         c.median(),
         layout.cycleWidth() * extras,
         layout.curbWidth() * extras,
         true,
         4.0,
         3.0,
         1.5,
         phase,
         Collections.nCopies(in, JunctionSpec.Lane.AUTO),
         false
      );
   }

   public JunctionSpec arms(List<JunctionSpec.Arm> value) {
      return new JunctionSpec(
         this.center,
         this.kind,
         this.leftTraffic,
         this.cornerRadius,
         this.islandRadius,
         this.ringLanes,
         this.ringLaneWidth,
         this.thickness,
         this.control,
         this.greenSeconds,
         this.yellowSeconds,
         this.allRedSeconds,
         this.timeOffset,
         this.guides,
         this.greenIsland,
         this.outerRail,
         value,
         this.parallelApproaches,
         this.oneSide
      );
   }

   public JunctionSpec outerRail(boolean value) {
      return new JunctionSpec(
         this.center,
         this.kind,
         this.leftTraffic,
         this.cornerRadius,
         this.islandRadius,
         this.ringLanes,
         this.ringLaneWidth,
         this.thickness,
         this.control,
         this.greenSeconds,
         this.yellowSeconds,
         this.allRedSeconds,
         this.timeOffset,
         this.guides,
         this.greenIsland,
         value,
         this.arms,
         this.parallelApproaches,
         this.oneSide
      );
   }

   public JunctionSpec supportedCrossings() {
      return this.arms(this.arms.stream().map(a -> a.crosswalk() && !a.hasSlowTraffic() ? a.crossing(false) : a).toList());
   }

   public static String maskName(int mask) {
      if (mask < 0) {
         return "自动";
      } else if (mask == 0) {
         return "禁行";
      } else {
         ArrayList<String> names = new ArrayList<>();
         if ((mask & 2) != 0) {
            names.add("左转");
         }

         if ((mask & 1) != 0) {
            names.add("直行");
         }

         if ((mask & 4) != 0) {
            names.add("右转");
         }

         if ((mask & 8) != 0) {
            names.add("掉头");
         }

         return String.join("＋", names);
      }
   }

   public static record Arm(
      RoadGeometry.Node endpoint,
      RoadGeometry.V inward,
      RoadGeometry.Settings external,
      int incoming,
      int outgoing,
      double width,
      double median,
      RoadProfile.Median medianKind,
      double cycleWidth,
      double curbWidth,
      boolean crosswalk,
      double crossingWidth,
      double crossingSetback,
      double stopGap,
      int phase,
      List<JunctionSpec.Lane> lanes,
      boolean attached,
      boolean advanceSign,
      boolean nameSign,
      boolean rightSlip
   ) {
      public Arm(
         RoadGeometry.Node endpoint,
         RoadGeometry.V inward,
         RoadGeometry.Settings external,
         int incoming,
         int outgoing,
         double width,
         double median,
         RoadProfile.Median medianKind,
         double cycleWidth,
         double curbWidth,
         boolean crosswalk,
         double crossingWidth,
         double crossingSetback,
         double stopGap,
         int phase,
         List<JunctionSpec.Lane> lanes,
         boolean attached,
         boolean advanceSign,
         boolean nameSign
      ) {
         this(
            endpoint,
            inward,
            external,
            incoming,
            outgoing,
            width,
            median,
            medianKind,
            cycleWidth,
            curbWidth,
            crosswalk,
            crossingWidth,
            crossingSetback,
            stopGap,
            phase,
            lanes,
            attached,
            advanceSign,
            nameSign,
            false
         );
      }

      public Arm(
         RoadGeometry.Node endpoint,
         RoadGeometry.V inward,
         RoadGeometry.Settings external,
         int incoming,
         int outgoing,
         double width,
         double median,
         RoadProfile.Median medianKind,
         double cycleWidth,
         double curbWidth,
         boolean crosswalk,
         double crossingWidth,
         double crossingSetback,
         double stopGap,
         int phase,
         List<JunctionSpec.Lane> lanes,
         boolean attached
      ) {
         this(
            endpoint,
            inward,
            external,
            incoming,
            outgoing,
            width,
            median,
            medianKind,
            cycleWidth,
            curbWidth,
            crosswalk,
            crossingWidth,
            crossingSetback,
            stopGap,
            phase,
            lanes,
            attached,
            true,
            true
         );
      }

      public Arm(
         RoadGeometry.Node endpoint,
         RoadGeometry.V inward,
         RoadGeometry.Settings external,
         int incoming,
         int outgoing,
         double width,
         double median,
         RoadProfile.Median medianKind,
         double cycleWidth,
         double curbWidth,
         boolean crosswalk,
         double crossingWidth,
         double crossingSetback,
         double stopGap,
         int phase,
         List<JunctionSpec.Lane> lanes,
         boolean attached,
         boolean advanceSign,
         boolean nameSign,
         boolean rightSlip
      ) {
         lanes = List.copyOf(lanes);
         if (Math.abs(inward.horizontalLength() - 1.0) > 1.0E-12) {
            inward = inward.horizontalUnit();
         }

         inward = new RoadGeometry.V(inward.x() == 0.0 ? 0.0 : inward.x(), 0.0, inward.z() == 0.0 ? 0.0 : inward.z());
         if (incoming < 0
            || incoming > 7
            || outgoing < 0
            || outgoing > 7
            || incoming + outgoing == 0
            || lanes.size() != incoming
            || !RoadGeometry.finite(width, median, cycleWidth, curbWidth, crossingWidth, crossingSetback, stopGap)
            || width < 4.0
            || width > 96.0
            || median < 0.0
            || median > 8.0
            || cycleWidth < 0.0
            || cycleWidth > 4.0
            || curbWidth < 0.0
            || curbWidth > 2.0
            || (width - median - 1.0 - 2.0 * (cycleWidth + curbWidth)) / (double)(incoming + outgoing) < 2.0
            || crossingWidth < 2.0
            || crossingWidth > 8.0
            || crossingSetback < 1.0
            || crossingSetback > 16.0
            || stopGap < 0.5
            || stopGap > 8.0
            || phase < 0
            || phase > 7
            || RoadProfile.catalog(external.style()).type() != RoadProfile.Type.ORDINARY) {
            throw new IllegalArgumentException("普通道路断面无效：每条车道至少 2 格，进／出各 0–7 车道，总宽 4–96 格");
         } else if ((incoming == 0 || outgoing == 0) && median > 0.0) {
            throw new IllegalArgumentException("单行接入口不能设置中央分隔带");
         } else {
            this.endpoint = endpoint;
            this.inward = inward;
            this.external = external;
            this.incoming = incoming;
            this.outgoing = outgoing;
            this.width = width;
            this.median = median;
            this.medianKind = medianKind;
            this.cycleWidth = cycleWidth;
            this.curbWidth = curbWidth;
            this.crosswalk = crosswalk;
            this.crossingWidth = crossingWidth;
            this.crossingSetback = crossingSetback;
            this.stopGap = stopGap;
            this.phase = phase;
            this.lanes = lanes;
            this.attached = attached;
            this.advanceSign = advanceSign;
            this.nameSign = nameSign;
            this.rightSlip = rightSlip;
         }
      }

      public double laneWidth() {
         return (this.width - this.median - 1.0 - 2.0 * (this.cycleWidth + this.curbWidth)) / (double)(this.incoming + this.outgoing);
      }

      public double divider(boolean left) {
         return (double)(this.outgoing - this.incoming) * this.laneWidth() / 2.0 * (double)(left ? -1 : 1);
      }

      public double laneCenter(boolean incomingLane, int lane, boolean left) {
         int count = incomingLane ? this.incoming : this.outgoing;
         int order = left ? count - 1 - lane : lane;
         double side = (double)((incomingLane ? 1 : -1) * (left ? -1 : 1));
         return this.divider(left) + side * (this.median / 2.0 + ((double)order + 0.5) * this.laneWidth());
      }

      public double motorEdge(boolean incomingLane, boolean left) {
         int n = incomingLane ? this.incoming : this.outgoing;
         return this.divider(left) + (double)((incomingLane ? 1 : -1) * (left ? -1 : 1)) * (this.median / 2.0 + (double)n * this.laneWidth());
      }

      public JunctionSpec.Arm lanes(List<JunctionSpec.Lane> value) {
         return new JunctionSpec.Arm(
            this.endpoint,
            this.inward,
            this.external,
            this.incoming,
            this.outgoing,
            this.width,
            this.median,
            this.medianKind,
            this.cycleWidth,
            this.curbWidth,
            this.crosswalk,
            this.crossingWidth,
            this.crossingSetback,
            this.stopGap,
            this.phase,
            value,
            this.attached,
            this.advanceSign,
            this.nameSign,
            this.rightSlip
         );
      }

      public JunctionSpec.Arm node(RoadGeometry.Node value, RoadGeometry.V direction, RoadGeometry.Settings profile) {
         return new JunctionSpec.Arm(
            value,
            direction,
            profile,
            this.incoming,
            this.outgoing,
            this.width,
            this.median,
            this.medianKind,
            this.cycleWidth,
            this.curbWidth,
            this.crosswalk,
            this.crossingWidth,
            this.crossingSetback,
            this.stopGap,
            this.phase,
            this.lanes,
            this.attached,
            this.advanceSign,
            this.nameSign,
            this.rightSlip
         );
      }

      public JunctionSpec.Arm attached(boolean value) {
         return new JunctionSpec.Arm(
            this.endpoint,
            this.inward,
            this.external,
            this.incoming,
            this.outgoing,
            this.width,
            this.median,
            this.medianKind,
            this.cycleWidth,
            this.curbWidth,
            this.crosswalk,
            this.crossingWidth,
            this.crossingSetback,
            this.stopGap,
            this.phase,
            this.lanes,
            value,
            this.advanceSign,
            this.nameSign,
            this.rightSlip
         );
      }

      public JunctionSpec.Arm signs(boolean advance, boolean name) {
         return new JunctionSpec.Arm(
            this.endpoint,
            this.inward,
            this.external,
            this.incoming,
            this.outgoing,
            this.width,
            this.median,
            this.medianKind,
            this.cycleWidth,
            this.curbWidth,
            this.crosswalk,
            this.crossingWidth,
            this.crossingSetback,
            this.stopGap,
            this.phase,
            this.lanes,
            this.attached,
            advance,
            name,
            this.rightSlip
         );
      }

      public JunctionSpec.Arm crossing(boolean enabled) {
         return new JunctionSpec.Arm(
            this.endpoint,
            this.inward,
            this.external,
            this.incoming,
            this.outgoing,
            this.width,
            this.median,
            this.medianKind,
            this.cycleWidth,
            this.curbWidth,
            enabled,
            this.crossingWidth,
            this.crossingSetback,
            this.stopGap,
            this.phase,
            this.lanes,
            this.attached,
            this.advanceSign,
            this.nameSign,
            this.rightSlip
         );
      }

      public boolean hasSlowTraffic() {
         return this.external.options().sidewalk().enabled() || this.cycleWidth > 0.01;
      }

      public JunctionSpec.Arm slip(boolean enabled) {
         return new JunctionSpec.Arm(
            this.endpoint,
            this.inward,
            this.external,
            this.incoming,
            this.outgoing,
            this.width,
            this.median,
            this.medianKind,
            this.cycleWidth,
            this.curbWidth,
            this.crosswalk,
            this.crossingWidth,
            this.crossingSetback,
            this.stopGap,
            this.phase,
            this.lanes,
            this.attached,
            this.advanceSign,
            this.nameSign,
            enabled
         );
      }

      public boolean signEnabled(int slot) {
         return slot == 0 ? this.advanceSign : slot == 1 && this.nameSign;
      }
   }

   public static enum Control {
      NONE,
      YIELD,
      SIGNALS;
   }

   public static enum Kind {
      INTERSECTION,
      ROUNDABOUT;
   }

   public static record Lane(int mask, List<Integer> targets, List<Integer> targetLanes, boolean splitLeft, int leftPhase, boolean controlRight) {
      public static final JunctionSpec.Lane AUTO = new JunctionSpec.Lane(-1, List.of(-1, -1, -1, -1), List.of(-1, -1, -1, -1));

      public Lane(int mask, List<Integer> targets, List<Integer> targetLanes, boolean splitLeft, int leftPhase) {
         this(mask, targets, targetLanes, splitLeft, leftPhase, false);
      }

      public Lane(int mask, List<Integer> targets, List<Integer> targetLanes) {
         this(mask, targets, targetLanes, false, -1);
      }

      public Lane(int mask, List<Integer> targets, List<Integer> targetLanes, boolean splitLeft, int leftPhase, boolean controlRight) {
         targets = List.copyOf(targets);
         targetLanes = List.copyOf(targetLanes);
         if (leftPhase >= -1
            && leftPhase <= 15
            && mask >= -1
            && mask <= 15
            && targets.size() == 4
            && targetLanes.size() == 4
            && !targets.stream().anyMatch(n -> n < -1 || n > 7)
            && !targetLanes.stream().anyMatch(n -> n < -1 || n >= 7)) {
            this.mask = mask;
            this.targets = targets;
            this.targetLanes = targetLanes;
            this.splitLeft = splitLeft;
            this.leftPhase = leftPhase;
            this.controlRight = controlRight;
         } else {
            throw new IllegalArgumentException("车道通行配置无效");
         }
      }

      public JunctionSpec.Lane signals(boolean split, int phase) {
         return new JunctionSpec.Lane(this.mask, this.targets, this.targetLanes, split, phase, this.controlRight);
      }

      public JunctionSpec.Lane rightSignal(boolean enabled) {
         return new JunctionSpec.Lane(this.mask, this.targets, this.targetLanes, this.splitLeft, this.leftPhase, enabled);
      }

      public int effectiveLeftPhase(int arm) {
         return this.leftPhase < 0 ? 8 + arm : this.leftPhase;
      }

      public JunctionSpec.Lane mask(int value) {
         return new JunctionSpec.Lane(value, this.targets, this.targetLanes, this.splitLeft, this.leftPhase, this.controlRight);
      }

      public JunctionSpec.Lane target(int turn, int arm, int lane) {
         ArrayList<Integer> a = new ArrayList<>(this.targets);
         ArrayList<Integer> b = new ArrayList<>(this.targetLanes);
         a.set(turn, arm);
         b.set(turn, lane);
         return new JunctionSpec.Lane(this.mask, a, b, this.splitLeft, this.leftPhase, this.controlRight);
      }
   }
}
