package com.sora.splineroads.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent.BreakEvent;
import net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent;

final class RoadEditProtection {
   static boolean probing;

   static void check(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState current, final BlockState target) {
      if (player != null && !current.equals(target)) {
         if (level.mayInteract(player, pos) && player.mayUseItemAt(pos, Direction.UP, player.getMainHandItem())) {
            boolean prior = probing;
            probing = true;

            try {
               if (!current.isAir() && ((BreakEvent)NeoForge.EVENT_BUS.post(new BreakEvent(level, pos, current, player))).isCanceled()) {
                  throw denied(pos);
               }

               BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
               var event = new EntityPlaceEvent(snapshot, current, player) {
                  public BlockState getPlacedBlock() {
                     return target;
                  }

                  public BlockState getState() {
                     return target;
                  }
               };
               if (((EntityPlaceEvent)NeoForge.EVENT_BUS.post(event)).isCanceled()) {
                  throw denied(pos);
               }
            } finally {
               probing = prior;
            }
         } else {
            throw new IllegalArgumentException("道路施工范围受保护：" + pos.toShortString());
         }
      }
   }

   private static IllegalArgumentException denied(BlockPos pos) {
      return new IllegalArgumentException("道路施工被区域保护拒绝：" + pos.toShortString());
   }

   private RoadEditProtection() {
   }
}
