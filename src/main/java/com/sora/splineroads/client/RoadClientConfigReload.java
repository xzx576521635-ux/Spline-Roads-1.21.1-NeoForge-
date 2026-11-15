package com.sora.splineroads.client;

import com.sora.splineroads.config.RoadClientConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.fml.event.config.ModConfigEvent.Loading;

@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT},
   bus = Bus.MOD
)
public final class RoadClientConfigReload {
   @SubscribeEvent
   public static void loaded(Loading event) {
      if (event.getConfig().getSpec() == RoadClientConfig.SPEC) {
         ;
      }
   }

   private RoadClientConfigReload() {
   }
}
