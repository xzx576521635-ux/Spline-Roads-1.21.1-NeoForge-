package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hook for add-on mods that want to charge players for building a new road (survival balance).
 *
 * <p>The base mod owns no costs itself: with no listener registered nothing changes. Add-ons
 * register a {@link Listener} (e.g. Spline Roads: Quick Build charges materials and durability).</p>
 */
public final class RoadCostHooks {
   /** Two-phase hook: {@code reserve} may abort the build, {@code charge} runs after success. */
   public interface Listener {
      /**
       * Called before the road is written. Throw {@link IllegalArgumentException} to abort and show
       * the message to the player (e.g. "not enough materials").
       */
      void reserve(ServerPlayer player, ServerLevel level, BlockPos a, BlockPos b, RoadGeometry.Settings settings);

      /** Called after the road was written successfully; deduct the reserved resources here. */
      void charge(ServerPlayer player, ServerLevel level, BlockPos a, BlockPos b, RoadGeometry.Settings settings);
   }

   private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

   public static void register(Listener listener) {
      LISTENERS.add(listener);
   }

   static void reserve(ServerPlayer player, ServerLevel level, BlockPos a, BlockPos b, RoadGeometry.Settings settings) {
      for (Listener listener : LISTENERS) {
         listener.reserve(player, level, a, b, settings);
      }
   }

   static void charge(ServerPlayer player, ServerLevel level, BlockPos a, BlockPos b, RoadGeometry.Settings settings) {
      for (Listener listener : LISTENERS) {
         listener.charge(player, level, a, b, settings);
      }
   }

   private RoadCostHooks() {
   }
}
