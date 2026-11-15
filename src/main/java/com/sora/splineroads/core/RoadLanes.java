package com.sora.splineroads.core;

public final class RoadLanes {
   public static final int MAX_DIRECTION = 7;

   public static RoadLanes.Counts counts(RoadGeometry.Style style, RoadProfile.Options options) {
      if (options.lanes().explicit()) {
         return options.lanes();
      } else {
         RoadProfile.Catalog c = RoadProfile.catalog(style);
         return new RoadLanes.Counts(c.twoWay() ? c.lanes() / 2 : c.lanes(), c.twoWay() ? c.lanes() / 2 : 0);
      }
   }

   public static RoadLanes.Counts counts(RoadGeometry.Settings s) {
      return counts(s.style(), s.options());
   }

   public static RoadGeometry.Style carrier(RoadProfile.Type type, RoadLanes.Counts counts, RoadProfile.Median median) {
      if (type != RoadProfile.Type.ORDINARY && type != RoadProfile.Type.HIGHWAY) {
         throw new IllegalArgumentException("独立车道数仅适用于普通道路与高速");
      } else if (!counts.explicit()) {
         throw new IllegalArgumentException("尚未设置车道数");
      } else if (!counts.twoWay()) {
         RoadGeometry.Style var10000;
         if (type == RoadProfile.Type.HIGHWAY) {
            switch (counts.forward()) {
               case 1:
                  var10000 = RoadGeometry.Style.H1_ONE;
                  break;
               case 2:
                  var10000 = RoadGeometry.Style.H2_ONE;
                  break;
               case 3:
                  var10000 = RoadGeometry.Style.H3_ONE;
                  break;
               default:
                  var10000 = RoadGeometry.Style.H4_ONE;
            }
         } else {
            switch (counts.forward()) {
               case 1:
                  var10000 = RoadGeometry.Style.O1_ONE;
                  break;
               case 2:
                  var10000 = RoadGeometry.Style.O2_ONE;
                  break;
               case 3:
                  var10000 = RoadGeometry.Style.O3_ONE;
                  break;
               default:
                  var10000 = RoadGeometry.Style.O4_ONE;
            }
         }

         return var10000;
      } else {
         if (median == RoadProfile.Median.NONE) {
            median = type == RoadProfile.Type.HIGHWAY ? RoadProfile.Median.RAIL : RoadProfile.Median.DOUBLE_YELLOW;
         }

         if (type == RoadProfile.Type.HIGHWAY && median != RoadProfile.Median.GREEN) {
            median = RoadProfile.Median.RAIL;
         }

         int n = Math.min(8, Math.max(counts.forward(), counts.reverse()) * 2);
         if (type == RoadProfile.Type.HIGHWAY) {
            n = Math.max(4, n);
         }

         if (median == RoadProfile.Median.DASHED_YELLOW && n != 2) {
            median = RoadProfile.Median.DOUBLE_YELLOW;
         }

         return RoadProfile.choose(type, n, true, median, type == RoadProfile.Type.HIGHWAY);
      }
   }

   public static RoadGeometry.Settings configure(RoadGeometry.Settings s, RoadProfile.Type type, RoadLanes.Counts counts, double laneWidth) {
      RoadGeometry.Style style = carrier(type, counts, RoadProfile.catalog(s.style()).median());
      RoadProfile.Options options = s.options().lanes(counts).ends(RoadTransitions.Ends.NONE);
      options = options.lanePoints(LaneAdditions.reserveAuthored(options.lanePoints(), counts.total()));
      double w = RoadProfile.width(style, options, laneWidth);
      return new RoadGeometry.Settings(s.mode(), style, w, s.thickness(), s.tension(), s.arcDegrees()).structure(s.structure()).options(options);
   }

   public static RoadGeometry.Settings width(RoadGeometry.Settings s, double laneWidth) {
      RoadProfile.Options options = s.options().ends(s.options().ends().port(null));
      return new RoadGeometry.Settings(s.mode(), s.style(), RoadProfile.width(s.style(), options, laneWidth), s.thickness(), s.tension(), s.arcDegrees())
         .structure(s.structure())
         .options(options);
   }

   private RoadLanes() {
   }

   public static record Counts(int forward, int reverse) {
      public static final RoadLanes.Counts AUTO = new RoadLanes.Counts(0, 0);

      public Counts(int forward, int reverse) {
         if (forward >= 0 && forward <= 7 && reverse >= 0 && reverse <= 7 && (forward != 0 || reverse == 0)) {
            this.forward = forward;
            this.reverse = reverse;
         } else {
            throw new IllegalArgumentException("每个行驶方向须为 1–7 车道；单向不设反向车道");
         }
      }

      public boolean explicit() {
         return this.forward > 0;
      }

      public boolean twoWay() {
         return this.reverse > 0;
      }

      public int total() {
         return this.forward + this.reverse;
      }

      public RoadLanes.Counts mirrored() {
         return this.twoWay() ? new RoadLanes.Counts(this.reverse, this.forward) : this;
      }

      public String label() {
         return this.twoWay() ? "双向 " + this.forward + "+" + this.reverse + " 车道" : "单向 " + this.forward + " 车道";
      }
   }
}
