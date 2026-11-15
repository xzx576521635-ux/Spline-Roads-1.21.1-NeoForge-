package com.sora.splineroads.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Registers the optional Distant Horizons hooks without hard-linking the mod.
 * The actual integration class is only loaded when DH is installed.
 */
@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT},
   bus = Bus.MOD
)
public final class RoadDistantHorizonsInit {
   @SubscribeEvent
   public static void setup(FMLClientSetupEvent event) {
      if (!ModList.get().isLoaded("distanthorizons")) {
         return;
      }

      try {
         Class.forName("com.sora.splineroads.compat.dh.DhRoadTerrain").getMethod("init").invoke(null);
      } catch (Throwable error) {
         System.getLogger("SplineRoads").log(System.Logger.Level.WARNING, "Unable to enable Distant Horizons road LOD colors", error);
      }
   }

   private RoadDistantHorizonsInit() {
   }
}
