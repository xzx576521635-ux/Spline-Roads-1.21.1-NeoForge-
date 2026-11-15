package com.sora.splineroads.world;

import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.net.RoadNetwork;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

public class GantryTool extends Item {
   public GantryTool() {
      super(new Properties().stacksTo(1));
   }

   public static int signature(RoadRecord r) {
      return Objects.hash(r.start(), r.end(), r.settings(), r.structures());
   }

   public InteractionResult useOn(UseOnContext context) {
      if (context.getLevel().isClientSide) {
         return InteractionResult.SUCCESS;
      } else if (context.getPlayer() instanceof ServerPlayer player) {
         try {
            RoadData data = RoadData.get(player.serverLevel());
            Vec3 hit = context.getClickLocation();
            RoadGeometry.V point = new RoadGeometry.V(hit.x, hit.y, hit.z);
            RoadIndex.Built chosen = null;
            int slot = -1;
            double best = Double.POSITIVE_INFINITY;

            for (UUID id : data.index.at(context.getClickedPos().asLong())) {
               RoadIndex.Built built = data.index.roads.get(id);
               if (built.record.junction() == null && built.record.toll() == null) {
                  for (int i : RoadGantry.slots(built.mesh)) {
                     RoadGantry.Station station = RoadGantry.station(built.mesh, i);
                     RoadGeometry.Sample sample = station.sample();
                     RoadGeometry.V forward = sample.left().left().mul(-1.0);
                     double score = Math.abs(point.sub(sample.center()).dot(forward)) + Math.abs(point.y() - sample.center().y()) * 0.01;
                     if (score < best) {
                        best = score;
                        chosen = built;
                        slot = i;
                     }
                  }
               }
            }

            if (chosen == null) {
               throw new IllegalArgumentException("请右键龙门架，或右键其下方道路选择最近的一架");
            }

            RoadData.requireOwner(player, chosen.record.owner());
            if (!player.mayBuild() || player.isSpectator()) {
               throw new IllegalArgumentException("没有建设权限");
            }

            CompoundTag t = new CompoundTag();
            t.putString("Kind", "gantry");
            t.putUUID("Id", chosen.record.id());
            t.putInt("Slot", slot);
            t.putInt("Count", RoadGantry.slots(chosen.mesh).size());
            t.putInt("Signature", signature(chosen.record));
            t.putDouble("MaxOffset", RoadGantry.maxOffset(chosen.mesh, slot));
            RoadGantry.Station at = RoadGantry.station(chosen.mesh, slot);
            Vec3 look = player.getLookAngle();
            t.putInt("PlayerFlow", RoadGantry.playerFlow(at.sample(), new RoadGeometry.V(look.x, look.y, look.z)));
            LaneSections.Live lanes = LaneSections.live(chosen.mesh, at.distance());
            t.putBoolean("TwoWay", lanes.count(1) > 0 && lanes.count(-1) > 0);
            t.put("Settings", RoadRecord.writeSettings(chosen.record.settings()));
            t.putBoolean("SignUpdating", data.smartSigns.pending());
            t.put("ForwardSign", SmartSignCodec.write(data.smartSigns.peek(new SmartSigns.Key(chosen.record.id(), slot, 1))));
            t.put("ReverseSign", SmartSignCodec.write(data.smartSigns.peek(new SmartSigns.Key(chosen.record.id(), slot, -1))));
            RoadNetwork.open(player, t);
         } catch (IllegalArgumentException var20) {
            player.displayClientMessage(Component.literal(var20.getMessage()), false);
         }

         return InteractionResult.CONSUME;
      } else {
         return InteractionResult.PASS;
      }
   }

   public void appendHoverText(ItemStack s, TooltipContext level, List<Component> lines, TooltipFlag flag) {
      lines.add(Component.translatable("tooltip.splineroads.gantry_editor"));
   }
}
