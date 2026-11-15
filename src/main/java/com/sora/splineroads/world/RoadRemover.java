package com.sora.splineroads.world;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class RoadRemover extends Item {
   public RoadRemover() {
      super(new Properties().stacksTo(1));
   }

   private void select(Player player) {
      if (player instanceof ServerPlayer p) {
         try {
            RoadIndex.Built road = RoadTool.pick(p);
            if (road == null) {
               p.displayClientMessage(Component.literal("请指向 256 格内已加载的道路，再右键选择删除"), true);
            } else {
               RoadTool.openRoad(p, road.record, true);
            }
         } catch (IllegalArgumentException var4) {
            p.displayClientMessage(Component.literal(var4.getMessage()), false);
         }
      }
   }

   public InteractionResult useOn(UseOnContext context) {
      this.select(context.getPlayer());
      return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
   }

   public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
      this.select(player);
      return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
   }

   public void appendHoverText(ItemStack s, TooltipContext l, List<Component> lines, TooltipFlag flag) {
      lines.add(Component.translatable("tooltip.splineroads.remover"));
   }
}
