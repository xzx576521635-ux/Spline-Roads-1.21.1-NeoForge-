package com.sora.splineroads.world;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.net.RoadNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

public final class RoundaboutTool extends Item {
   public RoundaboutTool() {
      super(new Properties().stacksTo(1));
   }

   public InteractionResult useOn(UseOnContext c) {
      if (c.getLevel().isClientSide) {
         return InteractionResult.SUCCESS;
      } else if (c.getPlayer() instanceof ServerPlayer p) {
         try {
            InteractionResult var14;
            try (RoadWorkChunks work = RoadWorkChunks.open(p.serverLevel())) {
               BlockPos pos = RoadTool.endpointAtHit(c);
               RoadData data = RoadData.get(p.serverLevel());
               CompoundTag ring = AutoJunctions.center(data, pos);
               if (ring == null) {
                  ring = AutoJunctions.ringAt(data, pos);
               }

               if (ring == null) {
                  NodeEntity node = RoadData.requireNode(p.serverLevel(), pos, p);
                  if (data.linked(pos)) {
                     throw new IllegalArgumentException("请选择空闲端点作为环岛圆心");
                  }

                  CompoundTag tool = ItemNBT.edit(c.getItemInHand());
                  tool.putLong("Center", pos.asLong());
                  tool.putString("Dimension", p.level().dimension().location().toString());
                  CompoundTag t = new CompoundTag();
                  t.putString("Kind", "roundabout");
                  t.putLong("Center", pos.asLong());
                  t.put("Node", RoadRecord.writeNode(node.constructionNode()));
                  t.putDouble("Radius", 20.0);
                  t.putInt("Lanes", 1);
                  RoadNetwork.open(p, t);
                  return InteractionResult.CONSUME;
               }

               RoadNetwork.open(p, Junctions.payload(p.serverLevel(), p, new long[0], ring.getUUID("Id")));
               var14 = InteractionResult.CONSUME;
            }

            return var14;
         } catch (IllegalArgumentException var12) {
            if (ManualPlanner.inSession()) {
               throw var12;
            } else {
               p.displayClientMessage(Component.literal(var12.getMessage()), false);
               return InteractionResult.CONSUME;
            }
         }
      } else {
         return InteractionResult.PASS;
      }
   }

   public InteractionResultHolder<ItemStack> use(Level l, Player p, InteractionHand h) {
      if (!l.isClientSide && p instanceof ServerPlayer s) {
         RoadIndex.Built hit = RoadTool.pick(s);
         if (hit != null && hit.record.junction() != null) {
            RoadNetwork.open(s, Junctions.selectedPayload(s.serverLevel(), s, hit.record));
         }
      }

      return InteractionResultHolder.sidedSuccess(p.getItemInHand(h), l.isClientSide);
   }

   public static JunctionSpec specification(CompoundTag t) {
      double radius = t.getDouble("Radius");
      int lanes = t.getInt("Lanes");
      if (lanes >= 1 && lanes <= 2 && !(radius < 10.0) && !(radius > 48.0)) {
         RoadGeometry.V center = RoadRecord.readNode(t.getCompound("Node")).position();
         return new JunctionSpec(
            center,
            JunctionSpec.Kind.ROUNDABOUT,
            t.getBoolean("LeftTraffic"),
            4.0,
            radius - (double)(lanes * 2),
            lanes,
            4.0,
            1.0,
            JunctionSpec.Control.YIELD,
            20,
            3,
            1,
            0,
            true,
            true,
            t.getBoolean("OuterRail"),
            List.of()
         );
      } else {
         throw new IllegalArgumentException("环岛中心线半径 10–48 格，支持单／双车道");
      }
   }

   public static List<RoadGeometry.Node> ports(JunctionSpec spec) {
      double radius = spec.islandRadius() + (double)spec.ringLanes() * spec.ringLaneWidth();
      ArrayList<RoadGeometry.Node> nodes = new ArrayList<>();
      int[] directions = new int[]{0, 4, 6, 2, 7, 1, 5, 3};

      for (int i : directions) {
         double angle = (-Math.PI / 2) + (double)i * Math.PI / 4.0;
         RoadGeometry.V out = new RoadGeometry.V(Math.cos(angle), 0.0, Math.sin(angle));
         nodes.add(new RoadGeometry.Node(spec.center().add(out.mul(radius)), RoadPlanner.yaw(out), 0.0));
      }

      return nodes;
   }

   public static String build(ServerLevel l, ServerPlayer p, CompoundTag t, ItemStack stack) {
      String var8;
      try (RoadWorkChunks work = RoadWorkChunks.open(l)) {
         if (!(stack.getItem() instanceof RoundaboutTool)
            || ItemNBT.get(stack).getLong("Center") != t.getLong("Center")
            || !l.dimension().location().toString().equals(ItemNBT.get(stack).getString("Dimension"))) {
            throw new IllegalArgumentException("请用环岛生成器重新选择圆心");
         }

         BlockPos center = BlockPos.of(t.getLong("Center"));
         NodeEntity node = RoadData.requireNode(l, center, p);
         if (RoadData.get(l).linked(center)) {
            throw new IllegalArgumentException("圆心已有道路");
         }

         if (!node.constructionNode().equals(RoadRecord.readNode(t.getCompound("Node")))) {
            throw new IllegalArgumentException("圆心已变化，请重新选择");
         }

         JunctionSpec spec = specification(t);
         AutoJunctions.createRing(l, p, center, spec);
         ItemNBT.edit(stack).remove("Center");
         var8 = "环岛已生成；用道路连接器从 A–H 八个方向端点接路";
      }

      return var8;
   }

   public void appendHoverText(ItemStack s, TooltipContext l, List<Component> lines, TooltipFlag f) {
      lines.add(Component.literal("右键空闲端点作为圆心，设置半径及单双车道"));
      lines.add(Component.literal("生成东南西北及四个斜向接路点，用道路连接器接路"));
   }
}
