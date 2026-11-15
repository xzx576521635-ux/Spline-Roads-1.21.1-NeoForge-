package com.sora.splineroads.core;

final class TunnelMedian {
   static RoadTransitions.Section seam(RoadTransitions.Section section) {
      RoadProfile.Layout layout = section.layout(false);
      RoadProfile.Catalog c = layout.catalog();
      if (c.twoWay() && c.median() == RoadProfile.Median.GREEN) {
         layout = RaisedRoadProfile.apply(layout, 1.0, section.streetscape().plantings().railWidth());
         RoadGeometry.Style rail = RoadLanes.carrier(c.type(), RoadLanes.counts(section.settings(false)), RoadProfile.Median.RAIL);
         RoadTransitions.Port old = section.port();
         RoadTransitions.Port port = new RoadTransitions.Port(
            layout.motorMin(),
            layout.motorMax(),
            layout.median(),
            layout.dividers(),
            old == null ? layout.curbWidth() : old.curbLeft(),
            old == null ? layout.curbWidth() : old.curbRight(),
            layout.medianCenter()
         );
         return new RoadTransitions.Section(
            rail,
            section.width(),
            section.cycle(),
            section.cycleRail(),
            section.curb(),
            section.outerRail(),
            section.sidewalk(),
            section.cycleAsphalt(),
            port,
            section.streetscape(),
            section.lanes()
         );
      } else {
         return section;
      }
   }

   private TunnelMedian() {
   }
}
