package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

final class RaisedRoadProfile {
   static RoadProfile.Layout apply(RoadProfile.Layout base) {
      return apply(base, 1.0);
   }

   static RoadProfile.Layout apply(RoadProfile.Layout base, double weight) {
      return apply(base, weight, 1.0);
   }

   static RoadProfile.Layout apply(RoadProfile.Layout base, double weight, double railWidth) {
      if (weight <= 0.0) {
         return base;
      } else {
         double median = base.median() + (railWidth - base.median()) * weight;
         RoadProfile.Catalog c = base.catalog();
         if (c.twoWay() && (c.median() != RoadProfile.Median.RAIL || !(Math.abs(base.median() - median) < 1.0E-9))) {
            double width = base.motorMax() - base.motorMin();
            double laneScale = (width - median) / Math.max(1.0E-9, width - base.median());
            double center = base.motorCenter() + (base.medianCenter() - base.motorCenter()) * laneScale;
            ArrayList<Double> dividers = new ArrayList<>();

            for (double divider : base.dividers()) {
               int side = divider < base.medianCenter() ? -1 : 1;
               double outer = (double)side * (base.outer(side) - base.medianCenter());
               double oldSpan = outer - base.median() / 2.0;
               double fraction = (Math.abs(divider - base.medianCenter()) - base.median() / 2.0) / Math.max(1.0E-9, oldSpan);
               dividers.add(center + (double)side * (median / 2.0 + Math.max(0.0, Math.min(1.0, fraction)) * (outer - base.median() / 2.0) * laneScale));
            }

            return new RoadProfile.Layout(
               new RoadProfile.Catalog(c.type(), c.lanes(), true, weight >= 1.0 ? RoadProfile.Median.RAIL : c.median(), c.shoulder()),
               base.laneWidth() * laneScale,
               median,
               base.motorMin(),
               base.motorMax(),
               base.cycleWidth(),
               base.curbWidth(),
               base.shoulderWidth(),
               base.outside(),
               List.copyOf(dividers),
               center
            );
         } else {
            return base;
         }
      }
   }

   private RaisedRoadProfile() {
   }
}
