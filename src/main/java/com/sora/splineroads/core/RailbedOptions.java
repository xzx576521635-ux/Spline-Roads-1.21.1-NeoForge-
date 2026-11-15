package com.sora.splineroads.core;

import java.util.Set;

public record RailbedOptions(
   boolean leftRail,
   boolean rightRail,
   boolean catenary,
   double poleSpacing,
   double wireHeight,
   int singleSide,
   RoadInfrastructure.Bridge bridge,
   RoadInfrastructure.Tunnel tunnel,
   RailbedOptions.Fence fence
) {
   public static final RailbedOptions DEFAULT = new RailbedOptions(true, true, false, 24.0, 6.5, 1);

   public RailbedOptions(boolean left, boolean right, boolean catenary, double spacing, double height, int side) {
      this(left, right, catenary, spacing, height, side, RoadInfrastructure.Bridge.STANDARD, RoadInfrastructure.Tunnel.BOX, RailbedOptions.Fence.SOLID);
   }

   public RailbedOptions(
      boolean leftRail,
      boolean rightRail,
      boolean catenary,
      double poleSpacing,
      double wireHeight,
      int singleSide,
      RoadInfrastructure.Bridge bridge,
      RoadInfrastructure.Tunnel tunnel,
      RailbedOptions.Fence fence
   ) {
      if (bridge == null
         || tunnel == null
         || fence == null
         || !Set.of(RoadInfrastructure.Bridge.STANDARD, RoadInfrastructure.Bridge.ARCH, RoadInfrastructure.Bridge.CABLE, RoadInfrastructure.Bridge.SUSPENSION)
            .contains(bridge)
         || tunnel == RoadInfrastructure.Tunnel.ARCH) {
         throw new IllegalArgumentException("无效的铁路桥隧样式");
      } else if (!Double.isFinite(poleSpacing) || poleSpacing < 8.0 || poleSpacing > 64.0) {
         throw new IllegalArgumentException("接触网间距需为 8–64 米");
      } else if (!Double.isFinite(wireHeight) || wireHeight < 6.1 || wireHeight > 12.0) {
         throw new IllegalArgumentException("接触网高度需为 6.1–12 米");
      } else if (singleSide != -1 && singleSide != 1) {
         throw new IllegalArgumentException("单轨接触网侧别无效");
      } else {
         this.leftRail = leftRail;
         this.rightRail = rightRail;
         this.catenary = catenary;
         this.poleSpacing = poleSpacing;
         this.wireHeight = wireHeight;
         this.singleSide = singleSide;
         this.bridge = bridge;
         this.tunnel = tunnel;
         this.fence = fence;
      }
   }

   public RailbedOptions flags(boolean left, boolean right, boolean wire) {
      return new RailbedOptions(left, right, wire, this.poleSpacing, this.wireHeight, this.singleSide, this.bridge, this.tunnel, this.fence);
   }

   public RailbedOptions wire(double spacing, double height, int side) {
      return new RailbedOptions(this.leftRail, this.rightRail, this.catenary, spacing, height, side, this.bridge, this.tunnel, this.fence);
   }

   public RailbedOptions styles(RoadInfrastructure.Bridge bridge, RoadInfrastructure.Tunnel tunnel, RailbedOptions.Fence fence) {
      return new RailbedOptions(this.leftRail, this.rightRail, this.catenary, this.poleSpacing, this.wireHeight, this.singleSide, bridge, tunnel, fence);
   }

   public boolean rail(int side) {
      return side > 0 ? this.leftRail : this.rightRail;
   }

   public static enum Fence {
      SOLID("混凝土护栏"),
      MESH("铁路护栏网");

      public final String label;

      private Fence(String label) {
         this.label = label;
      }
   }
}
