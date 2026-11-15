package com.sora.splineroads.core;

import java.util.List;

public final class DoubleTrumpetToll {
   public static TollPlaza.Config station(RoadGeometry.Settings road, InterchangePlanner.Options options) {
      DoubleTrumpetToll.Config c = options.toll();
      RoadLanes.Counts counts = RoadLanes.counts(road);
      int f = counts.forward();
      int b = counts.reverse();
      if (f >= 1 && b >= 1) {
         int fe = Math.min(c.forwardEtc(), f);
         int be = Math.min(c.reverseEtc(), b);
         return new TollPlaza.Config(
            true,
            false,
            options.leftTraffic(),
            false,
            new TollPlaza.Direction(f, f, fe, f - fe),
            new TollPlaza.Direction(b, b, be, b - be),
            RoadProfile.layout(road, road.width()).laneWidth(),
            4.0,
            6.0,
            road.structure(),
            c.name(),
            c.twoWay() ? 3 : (c.reverse() ? 2 : 1)
         );
      } else {
         throw new IllegalArgumentException("双喇叭第三道必须保持双向通行");
      }
   }

   public static double length(RoadGeometry.Settings road, InterchangePlanner.Options options) {
      TollPlaza.Spec s = new TollPlaza.Spec(
         new RoadGeometry.Node(new RoadGeometry.V(0.0, 0.0, 0.0), 0.0, 0.0),
         new RoadGeometry.Node(new RoadGeometry.V(0.0, 0.0, 1024.0), 0.0, 0.0),
         station(road, options),
         road,
         road
      );
      TollPlanner.Lengths lengths = TollPlanner.lengths(s);
      return Math.ceil((2.0 * Math.max(lengths.a(), lengths.b()) + 28.0 + 2.0) / 4.0) * 4.0;
   }

   public static boolean connector(String name) {
      return name.equals("双喇叭中央连接段") || name.startsWith("双喇叭中央连接段 · ");
   }

   public static List<InterchangePlanner.Leg> split(RoadGeometry.Mesh road, InterchangePlanner.Options options) {
      double span = length(road.settings(), options);
      double from = options.toll().near() == DoubleTrumpetToll.Near.AB ? 8.0 : road.length() - 8.0 - span;
      double to = from + span;
      RoadGeometry.Mesh segment = RoadSlicing.slice(road, from, to, road.settings()).mesh();
      RoadGeometry.V axis = segment.last().center().sub(segment.first().center()).horizontalUnit();

      for (RoadGeometry.Sample p : segment.samples()) {
         if (Math.abs(p.center().sub(segment.first().center()).dot(axis.left())) > 1.0E-6 || p.left().dot(axis.left()) < 0.99999999) {
            throw new IllegalArgumentException("双喇叭收费站必须完整位于所选主路一端的直线段，请扩大范围");
         }
      }

      TollPlaza.Spec spec = new TollPlaza.Spec(
         RoadRibbon.start(segment), RoadRibbon.end(segment), station(road.settings(), options), road.settings(), road.settings(), segment.samples()
      );
      TollPlanner.Plan station = TollPlanner.plan(spec);
      return List.of(
         new InterchangePlanner.Leg("双喇叭中央连接段 · AB端", -1, -1, RoadSlicing.slice(road, 0.0, from, road.settings()).mesh()),
         new InterchangePlanner.Leg("双喇叭中央连接段 · 收费站", -1, -1, station.mesh(), null, spec),
         new InterchangePlanner.Leg("双喇叭中央连接段 · CD端", -1, -1, RoadSlicing.slice(road, to, road.length(), road.settings()).mesh())
      );
   }

   private DoubleTrumpetToll() {
   }

   public static record Config(boolean enabled, boolean twoWay, boolean reverse, DoubleTrumpetToll.Near near, int forwardEtc, int reverseEtc, String name) {
      public static final DoubleTrumpetToll.Config OFF = new DoubleTrumpetToll.Config(false, true, false, DoubleTrumpetToll.Near.AB, 0, 0, "");

      public Config(boolean enabled, boolean twoWay, boolean reverse, DoubleTrumpetToll.Near near, int forwardEtc, int reverseEtc, String name) {
         name = name == null ? "" : name.strip();
         if (near != null
            && forwardEtc >= 0
            && forwardEtc <= 7
            && reverseEtc >= 0
            && reverseEtc <= 7
            && name.codePointCount(0, name.length()) <= 40
            && !name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 167)) {
            this.enabled = enabled;
            this.twoWay = twoWay;
            this.reverse = reverse;
            this.near = near;
            this.forwardEtc = forwardEtc;
            this.reverseEtc = reverseEtc;
            this.name = name;
         } else {
            throw new IllegalArgumentException("双喇叭收费配置无效：ETC 0–7，名称最多40字");
         }
      }

      public DoubleTrumpetToll.Config enabled(boolean value) {
         return new DoubleTrumpetToll.Config(value, this.twoWay, this.reverse, this.near, this.forwardEtc, this.reverseEtc, this.name);
      }

      public DoubleTrumpetToll.Config mode(boolean both, boolean reversed) {
         return new DoubleTrumpetToll.Config(this.enabled, both, reversed, this.near, this.forwardEtc, this.reverseEtc, this.name);
      }

      public DoubleTrumpetToll.Config near(DoubleTrumpetToll.Near value) {
         return new DoubleTrumpetToll.Config(this.enabled, this.twoWay, this.reverse, value, this.forwardEtc, this.reverseEtc, this.name);
      }

      public DoubleTrumpetToll.Config etc(int forward, int backward) {
         return new DoubleTrumpetToll.Config(this.enabled, this.twoWay, this.reverse, this.near, forward, backward, this.name);
      }

      public DoubleTrumpetToll.Config named(String value) {
         return new DoubleTrumpetToll.Config(this.enabled, this.twoWay, this.reverse, this.near, this.forwardEtc, this.reverseEtc, value);
      }
   }

   public static enum Near {
      AB,
      CD;
   }
}
