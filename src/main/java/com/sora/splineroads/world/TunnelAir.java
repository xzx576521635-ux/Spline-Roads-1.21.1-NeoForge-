package com.sora.splineroads.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

public final class TunnelAir extends AirBlock implements LiquidBlockContainer {
   public TunnelAir() {
      super(Properties.of().noCollission().noOcclusion().replaceable().strength(-1.0F, 3600000.0F).noLootTable());
   }

   public static boolean isAirSpace(BlockState state) {
      return state.isAir() || state.getBlock() instanceof TunnelAir;
   }

   public boolean canPlaceLiquid(Player player, BlockGetter world, BlockPos pos, BlockState state, Fluid fluid) {
      return false;
   }

   public boolean placeLiquid(LevelAccessor world, BlockPos pos, BlockState state, FluidState fluid) {
      return false;
   }
}
