package com.sora.splineroads.core;

public record RoadPlantings(
   double greenWidth,
   double railGap,
   RoadStreetscape.Planting medianTrees,
   double medianSpacing,
   RoadStreetscape.Planting separatorTrees,
   double separatorSpacing
) {
   public static final RoadPlantings DEFAULT = new RoadPlantings(3.0, 0.0, RoadStreetscape.Planting.NONE, 16.0, RoadStreetscape.Planting.NONE, 16.0);

   public RoadPlantings(
      double greenWidth,
      double railGap,
      RoadStreetscape.Planting medianTrees,
      double medianSpacing,
      RoadStreetscape.Planting separatorTrees,
      double separatorSpacing
   ) {
      if (RoadGeometry.finite(greenWidth, railGap, medianSpacing, separatorSpacing)
         && !(greenWidth < 0.5)
         && !(greenWidth > 8.0)
         && !(railGap < 0.0)
         && !(railGap > 7.0)
         && !(Math.abs(greenWidth * 2.0 - Math.rint(greenWidth * 2.0)) > 1.0E-7)
         && !(Math.abs(railGap * 2.0 - Math.rint(railGap * 2.0)) > 1.0E-7)
         && !(medianSpacing < 8.0)
         && !(medianSpacing > 64.0)
         && !(separatorSpacing < 8.0)
         && !(separatorSpacing > 64.0)
         && medianTrees != null
         && separatorTrees != null
         && medianTrees != RoadStreetscape.Planting.FLOWERS
         && separatorTrees != RoadStreetscape.Planting.FLOWERS) {
         this.greenWidth = greenWidth;
         this.railGap = railGap;
         this.medianTrees = medianTrees;
         this.medianSpacing = medianSpacing;
         this.separatorTrees = separatorTrees;
         this.separatorSpacing = separatorSpacing;
      } else {
         throw new IllegalArgumentException("中央绿化宽 0.5–8 格，护栏间距 0–7 格（步进 0.5）；植树间距 8–64 格");
      }
   }

   public double railWidth() {
      return this.railGap == 0.0 ? 1.0 : 1.5 + this.railGap;
   }

   public RoadPlantings greenWidth(double v) {
      return new RoadPlantings(v, this.railGap, this.medianTrees, this.medianSpacing, this.separatorTrees, this.separatorSpacing);
   }

   public RoadPlantings railGap(double v) {
      return new RoadPlantings(this.greenWidth, v, this.medianTrees, this.medianSpacing, this.separatorTrees, this.separatorSpacing);
   }

   public RoadPlantings medianTrees(RoadStreetscape.Planting v) {
      return new RoadPlantings(this.greenWidth, this.railGap, v, this.medianSpacing, this.separatorTrees, this.separatorSpacing);
   }

   public RoadPlantings medianSpacing(double v) {
      return new RoadPlantings(this.greenWidth, this.railGap, this.medianTrees, v, this.separatorTrees, this.separatorSpacing);
   }

   public RoadPlantings separatorTrees(RoadStreetscape.Planting v) {
      return new RoadPlantings(this.greenWidth, this.railGap, this.medianTrees, this.medianSpacing, v, this.separatorSpacing);
   }

   public RoadPlantings separatorSpacing(double v) {
      return new RoadPlantings(this.greenWidth, this.railGap, this.medianTrees, this.medianSpacing, this.separatorTrees, v);
   }
}
