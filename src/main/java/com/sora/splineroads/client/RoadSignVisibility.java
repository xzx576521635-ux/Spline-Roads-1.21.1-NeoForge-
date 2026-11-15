package com.sora.splineroads.client;

import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadSignFilter;
import com.sora.splineroads.core.RoadStructures;
import java.util.List;

public final class RoadSignVisibility {
   private static boolean observed = false;

   public static boolean enabled() {
      return (Boolean)RoadClientConfig.SHOW_SIGNS.get();
   }

   public static void set(boolean visible) {
      RoadClientConfig.SHOW_SIGNS.set(visible);
      beginFrame();
   }

   public static void beginFrame() {
      boolean next = enabled();
      if (next != observed) {
         observed = next;
         SmartSignClient.visibilityChanged();
         TollNameRenderer.clear();
         RoadRenderer.reset();
         RoadRenderer.changed(ClientRoads.INDEX.roads.keySet());
      }
   }

   public static List<RoadStructures.Part> parts(RoadGeometry.Mesh mesh, List<RoadStructures.Part> parts) {
      return enabled() ? parts : RoadSignFilter.withoutSigns(mesh, parts);
   }

   private RoadSignVisibility() {
   }
}
