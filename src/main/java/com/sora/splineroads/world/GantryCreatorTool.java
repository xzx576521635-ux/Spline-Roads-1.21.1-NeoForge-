package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadQueries;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

public final class GantryCreatorTool extends GantryTool {
   @Override
   public InteractionResult useOn(UseOnContext context) {
      if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
         return super.useOn(context);
      } else if (context.getLevel().isClientSide) {
         return InteractionResult.SUCCESS;
      } else if (context.getPlayer() instanceof ServerPlayer player) {
         try {
            RoadData data = RoadData.get(player.serverLevel());
            Vec3 hit = context.getClickLocation();
            RoadGeometry.V point = new RoadGeometry.V(hit.x, hit.y, hit.z);
            RoadIndex.Built built = data.index
               .at(context.getClickedPos().asLong())
               .stream()
               .map(data.index.roads::get)
               .filter(Objects::nonNull)
               .filter(b -> b.record.junction() == null && b.record.toll() == null && !b.mesh.settings().style().ramp())
               .min(Comparator.comparingDouble(b -> Math.abs(RoadQueries.horizontal(b.mesh, point).sample().center().y() - point.y())))
               .orElseThrow(() -> new IllegalArgumentException("请右键道路表面，在点击位置创建独立龙门架"));
            data.createGantry(player.serverLevel(), player, built.record.id(), point);
            player.displayClientMessage(Component.literal("独立龙门架已创建；Shift＋右键可编辑，也可使用龙门架编辑器"), true);
         } catch (IllegalArgumentException var7) {
            player.displayClientMessage(Component.literal(var7.getMessage()), false);
         }

         return InteractionResult.CONSUME;
      } else {
         return InteractionResult.PASS;
      }
   }

   @Override
   public void appendHoverText(ItemStack s, TooltipContext level, List<Component> lines, TooltipFlag flag) {
      lines.add(Component.translatable("tooltip.splineroads.gantry_creator"));
   }
}
