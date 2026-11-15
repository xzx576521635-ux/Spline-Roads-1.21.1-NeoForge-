package com.sora.splineroads.core;

import java.util.List;

public final class TollPlaza {
   public static RoadGeometry.Settings port(TollPlaza.Spec s, boolean a) {
      RoadGeometry.Settings bound = a ? s.portA() : s.portB();
      TollPlaza.Config c = s.config();
      RoadLanes.Counts counts = new RoadLanes.Counts(c.forward().lanes(a), c.twoWay() ? c.backward().lanes(a) : 0);
      return bound != null
            && RoadLanes.counts(bound).equals(counts)
            && bound.options().leftTraffic() == c.leftTraffic()
            && !(Math.abs(RoadProfile.layout(bound, bound.width()).laneWidth() - c.laneWidth()) > 1.0E-6)
         ? bound
         : port(c, a);
   }

   public static RoadGeometry.Settings port(TollPlaza.Config c, boolean a) {
      RoadLanes.Counts counts = new RoadLanes.Counts(c.forward().lanes(a), c.twoWay() ? c.backward().lanes(a) : 0);
      RoadProfile.Type type = c.highway() ? RoadProfile.Type.HIGHWAY : RoadProfile.Type.ORDINARY;
      RoadGeometry.Style style = RoadLanes.carrier(type, counts, c.twoWay() ? RoadProfile.Median.RAIL : RoadProfile.Median.NONE);
      RoadProfile.Options options = RoadProfile.Options.DEFAULT
         .traffic(c.leftTraffic())
         .lanes(counts)
         .cycleFinish(RoadProfile.Options.CycleFinish.NONE)
         .sidewalk(RoadSidewalks.Config.DEFAULT.enabled(false));
      RoadGeometry.Settings result = new RoadGeometry.Settings(
            RoadGeometry.Mode.STRAIGHT, style, RoadProfile.width(style, options, c.laneWidth()), 1.0, 0.35, 90.0
         )
         .options(options)
         .structure(c.structure());
      RoadProfile.Layout l = RoadProfile.layout(result, result.width());
      boolean reverse = !c.twoWay() && c.reverse();
      double sign = reverse ? -1.0 : 1.0;
      RoadTransitions.Port port = new RoadTransitions.Port(
         reverse ? -l.motorMax() : l.motorMin(),
         reverse ? -l.motorMin() : l.motorMax(),
         l.median(),
         l.dividers().stream().map(d -> d * sign).sorted().toList(),
         0.0,
         0.0,
         sign * l.medianCenter()
      );
      return result.options(options.ends(RoadTransitions.Ends.NONE.port(port)));
   }

   private TollPlaza() {
   }

   public static enum Adjust {
      NONE,
      AB,
      A,
      B;
   }

   public static record Config(
      boolean twoWay,
      boolean reverse,
      boolean leftTraffic,
      boolean highway,
      TollPlaza.Direction forward,
      TollPlaza.Direction backward,
      double laneWidth,
      double passageWidth,
      double clearHeight,
      RoadGeometry.Structure structure,
      String name,
      int chargedDirections
   ) {
      public static final TollPlaza.Config DEFAULT = new TollPlaza.Config(
         false, false, false, false, new TollPlaza.Direction(2, 2, 1, 2), new TollPlaza.Direction(2, 2, 1, 2), 4.0, 5.0, 6.0, RoadGeometry.Structure.AUTO
      );

      public Config(
         boolean twoWay,
         boolean reverse,
         boolean leftTraffic,
         boolean highway,
         TollPlaza.Direction forward,
         TollPlaza.Direction backward,
         double laneWidth,
         double passageWidth,
         double clearHeight,
         RoadGeometry.Structure structure,
         String name
      ) {
         this(twoWay, reverse, leftTraffic, highway, forward, backward, laneWidth, passageWidth, clearHeight, structure, name, twoWay ? 3 : 1);
      }

      public Config(
         boolean twoWay,
         boolean reverse,
         boolean leftTraffic,
         boolean highway,
         TollPlaza.Direction forward,
         TollPlaza.Direction backward,
         double laneWidth,
         double passageWidth,
         double clearHeight,
         RoadGeometry.Structure structure
      ) {
         this(twoWay, reverse, leftTraffic, highway, forward, backward, laneWidth, passageWidth, clearHeight, structure, "");
      }

      public Config(
         boolean twoWay,
         boolean reverse,
         boolean leftTraffic,
         boolean highway,
         TollPlaza.Direction forward,
         TollPlaza.Direction backward,
         double laneWidth,
         double passageWidth,
         double clearHeight,
         RoadGeometry.Structure structure,
         String name,
         int chargedDirections
      ) {
         if (chargedDirections >= 1 && chargedDirections <= 3 && (twoWay || chargedDirections == 1)) {
            name = name == null ? "" : name.strip();
            if (name.codePointCount(0, name.length()) > 40 || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 167)) {
               throw new IllegalArgumentException("收费站名称最多40个字符，不能包含换行或格式控制符");
            } else if (forward == null
               || backward == null
               || structure == null
               || !RoadGeometry.finite(laneWidth, passageWidth, clearHeight)
               || laneWidth < 2.0
               || laneWidth > 5.0
               || passageWidth < 4.0
               || passageWidth > 7.0
               || clearHeight < 6.0
               || clearHeight > 10.0) {
               throw new IllegalArgumentException("接口车道宽 2–5、收费通道净宽 4–7、最低净高 6–10 格");
            } else if (structure == RoadGeometry.Structure.TUNNEL) {
               throw new IllegalArgumentException("本版收费站支持地面与高架，不支持封闭隧道站");
            } else {
               this.twoWay = twoWay;
               this.reverse = reverse;
               this.leftTraffic = leftTraffic;
               this.highway = highway;
               this.forward = forward;
               this.backward = backward;
               this.laneWidth = laneWidth;
               this.passageWidth = passageWidth;
               this.clearHeight = clearHeight;
               this.structure = structure;
               this.name = name;
               this.chargedDirections = chargedDirections;
            }
         } else {
            throw new IllegalArgumentException("收费方向无效");
         }
      }

      public TollPlaza.Config named(String value) {
         return new TollPlaza.Config(
            this.twoWay,
            this.reverse,
            this.leftTraffic,
            this.highway,
            this.forward,
            this.backward,
            this.laneWidth,
            this.passageWidth,
            this.clearHeight,
            this.structure,
            value,
            this.chargedDirections
         );
      }

      public int units() {
         return this.forward.units() + (this.twoWay ? this.backward.units() : 0);
      }

      public boolean charges(int direction) {
         return (this.chargedDirections & 1 << direction) != 0;
      }

      public TollPlaza.Config direction(boolean both, boolean reversed) {
         return new TollPlaza.Config(
            both,
            reversed,
            this.leftTraffic,
            this.highway,
            this.forward,
            this.backward,
            this.laneWidth,
            this.passageWidth,
            this.clearHeight,
            this.structure,
            this.name
         );
      }
   }

   public static record Direction(int aLanes, int bLanes, int etc, int manual) {
      public Direction(int aLanes, int bLanes, int etc, int manual) {
         if (aLanes >= 1 && aLanes <= 7 && bLanes >= 1 && bLanes <= 7) {
            if (etc >= 0 && manual >= 0 && etc + manual >= 1 && etc + manual <= 7) {
               this.aLanes = aLanes;
               this.bLanes = bLanes;
               this.etc = etc;
               this.manual = manual;
            } else {
               throw new IllegalArgumentException("每方向 ETC 与人工通道合计须为 1–7");
            }
         } else {
            throw new IllegalArgumentException("收费站每端每方向须为 1–7 车道");
         }
      }

      public int units() {
         return this.etc + this.manual;
      }

      public int lanes(boolean a) {
         return a ? this.aLanes : this.bLanes;
      }
   }

   public static record Spec(
      RoadGeometry.Node a,
      RoadGeometry.Node b,
      TollPlaza.Config config,
      RoadGeometry.Settings portA,
      RoadGeometry.Settings portB,
      List<RoadGeometry.Sample> path,
      boolean rigidCore,
      boolean curvedDeck
   ) {
      public Spec(
         RoadGeometry.Node a,
         RoadGeometry.Node b,
         TollPlaza.Config config,
         RoadGeometry.Settings portA,
         RoadGeometry.Settings portB,
         List<RoadGeometry.Sample> path,
         boolean rigidCore
      ) {
         this(a, b, config, portA, portB, path, rigidCore, false);
      }

      public Spec(
         RoadGeometry.Node a,
         RoadGeometry.Node b,
         TollPlaza.Config config,
         RoadGeometry.Settings portA,
         RoadGeometry.Settings portB,
         List<RoadGeometry.Sample> path
      ) {
         this(a, b, config, portA, portB, path, true, true);
      }

      public Spec(RoadGeometry.Node a, RoadGeometry.Node b, TollPlaza.Config config, RoadGeometry.Settings portA, RoadGeometry.Settings portB) {
         this(a, b, config, portA, portB, List.of());
      }

      public Spec(RoadGeometry.Node a, RoadGeometry.Node b, TollPlaza.Config config) {
         this(a, b, config, null, null);
      }

      public Spec(
         RoadGeometry.Node a,
         RoadGeometry.Node b,
         TollPlaza.Config config,
         RoadGeometry.Settings portA,
         RoadGeometry.Settings portB,
         List<RoadGeometry.Sample> path,
         boolean rigidCore,
         boolean curvedDeck
      ) {
         if (a != null && b != null && config != null) {
            path = List.copyOf(path);
            if (path.isEmpty() || path.size() >= 2 && path.size() <= 16384) {
               this.a = a;
               this.b = b;
               this.config = config;
               this.portA = portA;
               this.portB = portB;
               this.path = path;
               this.rigidCore = rigidCore;
               this.curvedDeck = curvedDeck;
            } else {
               throw new IllegalArgumentException("收费站断面路径无效");
            }
         } else {
            throw new IllegalArgumentException("收费站端点缺失");
         }
      }
   }
}
