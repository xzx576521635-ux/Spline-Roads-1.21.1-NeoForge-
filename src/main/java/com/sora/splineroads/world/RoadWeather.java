package com.sora.splineroads.world;

import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent.Load;

@EventBusSubscriber(
   modid = "splineroads"
)
public final class RoadWeather {
   @SubscribeEvent
   public static void loaded(Load event) {
      long started = System.nanoTime();
      refresh(event.getChunk());
      RoadPerf.heightmaps(System.nanoTime() - started);

      if (event.getLevel() instanceof ServerLevel level) {
         long markerStart = System.nanoTime();
         RoadMarkerBuilder.chunkLoaded(level, event.getChunk());
         RoadPerf.markers(System.nanoTime() - markerStart);
      }

      RoadPerf.maybeLog();
   }

   public static boolean refresh(ChunkAccess chunk) {
      boolean road = false;

      for (LevelChunkSection section : chunk.getSections()) {
         if (section.maybeHas(RoadBlocks::isCollider)) {
            road = true;
            break;
         }
      }

      if (!road) {
         return false;
      } else {
         Heightmap.primeHeightmaps(chunk, EnumSet.of(Types.MOTION_BLOCKING, Types.MOTION_BLOCKING_NO_LEAVES, Types.OCEAN_FLOOR));
         return true;
      }
   }

   private RoadWeather() {
   }
}
