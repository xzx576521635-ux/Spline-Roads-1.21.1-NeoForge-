package com.sora.splineroads.core;

import java.util.List;

public final class RoadPreferences {
   public static RoadGeometry.Settings fresh(RoadGeometry.Settings saved) {
      double lane = RoadProfile.modern(saved.style()) ? (double)Math.round(RoadProfile.layout(saved, saved.width()).laneWidth() * 2.0) / 2.0 : 0.0;
      RoadProfile.Options o = saved.options();
      RoadStreetscape.Config decor = o.streetscape();
      o = o.ends(RoadTransitions.Ends.NONE)
         .lanePoints(LanePoints.Data.EMPTY)
         .attachments(RoadAttachments.Data.EMPTY)
         .laneLines(List.of())
         .outsets(0.0, 0.0)
         .streetscape(
            decor.raisedSpans(List.of())
               .sideStructures(new RoadStreetscape.SideStructures(List.of(), List.of(), List.of(), List.of()))
               .tunnels(List.of())
               .crossings(List.of())
         );
      RoadGeometry.Settings result = saved.options(o).taper(saved.width(), saved.width());
      return RoadProfile.modern(saved.style()) ? RoadLanes.width(result, lane) : result;
   }

   private RoadPreferences() {
   }
}
