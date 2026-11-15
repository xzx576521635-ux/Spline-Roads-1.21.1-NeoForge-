package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class RoadClearing {
   static void validate(ServerLevel level, BlockPos pos, BlockState state, boolean placedSidewalk) {
      boolean softSurface = state.is(Blocks.SNOW)
         || state.is(Blocks.SNOW_BLOCK)
         || state.is(Blocks.ICE)
         || state.is(Blocks.PACKED_ICE)
         || state.is(Blocks.BLUE_ICE)
         || state.is(Blocks.FROSTED_ICE);
      // Invisible road markers are unbreakable by design and may still sit in the
      // clearance volume of an older road. They are ours, so building through them
      // must never report a phantom "unbreakable block".
      boolean marker = state.is((Block)SplineRoads.MARKER.get());
      if (!RoadBlocks.isCollider(state)
         && !state.is((Block)SplineRoads.TUNNEL_AIR.get())
         && !placedSidewalk
         && !softSurface
         && !marker
         && state.getDestroySpeed(level, pos) < 0.0F) {
         throw new IllegalArgumentException("无法清除不可破坏方块：" + pos.toShortString());
      } else if (state.hasBlockEntity() && !RoadBlocks.isCollider(state)) {
         throw new IllegalArgumentException("道路或隧道范围被方块实体占用，无法清除：" + pos.toShortString());
      }
   }

   private RoadClearing() {
   }
}
