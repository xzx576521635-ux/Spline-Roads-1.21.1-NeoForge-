package com.sora.splineroads.world;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class CurveControlNodes {
   public static final String KEY = "CurveControlNodes";

   public static String add(ServerLevel level, ServerPlayer player, ItemStack tool, BlockPos pos) {
      return add(level, player, tool, pos, false);
   }

   static String add(ServerLevel level, ServerPlayer player, ItemStack tool, BlockPos pos, boolean map) {
      CompoundTag tag = ItemNBT.edit(tool);
      if (tag.contains("Start") && level.dimension().location().toString().equals(tag.getString("Dimension"))) {
         if (pos.asLong() == tag.getLong("Start")) {
            throw new IllegalArgumentException("起点不能同时作为控制点");
         } else if (!map && player.distanceToSqr(pos.getCenter()) > 64.0) {
            throw new IllegalArgumentException("控制点离玩家太远");
         } else {
            NodeEntity marker = RoadData.requireNode(level, pos, player);
            if (RoadData.get(level).linked(pos)) {
               throw new IllegalArgumentException("已有道路使用的端点不能转换为曲线控制点");
            } else {
               ListTag list = tag.getList("CurveControlNodes", 10);
               if (list.size() >= 32) {
                  throw new IllegalArgumentException("最多添加32个控制点");
               } else {
                  for (Tag entry : list) {
                     if (((CompoundTag)entry).getLong("Pos") == pos.asLong()) {
                        throw new IllegalArgumentException("这个控制点已选中");
                     }
                  }

                  CompoundTag entryx = new CompoundTag();
                  entryx.putLong("Pos", pos.asLong());
                  entryx.putUUID("Id", UUID.randomUUID());
                  entryx.put("Node", RoadRecord.writeNode(marker.constructionNode()));
                  list.add(entryx);
                  tag.put("CurveControlNodes", list);
                  return map
                     ? "已添加" + (tag.getBoolean("CurveThrough") ? "经过点 " : "控制点 ") + list.size() + "；继续加点或选择终点 B"
                     : "已添加控制点 " + list.size() + "；继续左 Alt＋右键添加，普通右键选择终点";
               }
            }
         }
      } else {
         throw new IllegalArgumentException("请先普通右键选择起点，再用左 Alt＋右键添加控制点");
      }
   }

   public static List<RoadProfile.Control> controls(CompoundTag tool) {
      ArrayList<RoadProfile.Control> out = new ArrayList<>();

      for (Tag value : tool.getList("CurveControlNodes", 10)) {
         CompoundTag c = (CompoundTag)value;
         out.add(new RoadProfile.Control(c.getUUID("Id"), RoadRecord.readNode(c.getCompound("Node")).position()));
      }

      return List.copyOf(out);
   }

   public static List<BlockPos> validate(ServerLevel level, ServerPlayer player, CompoundTag tool, RoadGeometry.Settings settings, BlockPos a, BlockPos b) {
      ListTag selected = tool.getList("CurveControlNodes", 10);
      List<RoadProfile.Control> controls = settings.options().routing().controls();
      if (!controls.isEmpty() && settings.options().routing().through() != tool.getBoolean("CurveThrough")) {
         throw new IllegalArgumentException("经过点/控制点类型与当前选择不一致");
      } else if (selected.size() != controls.size()) {
         throw new IllegalArgumentException("控制点选择已失效，请重新选择");
      } else {
         ArrayList<BlockPos> out = new ArrayList<>();

         for (int i = 0; i < selected.size(); i++) {
            CompoundTag c = selected.getCompound(i);
            BlockPos pos = BlockPos.of(c.getLong("Pos"));
            NodeEntity node = RoadData.requireNode(level, pos, player);
            if (!c.getUUID("Id").equals(controls.get(i).id())
               || !node.constructionNode().equals(RoadRecord.readNode(c.getCompound("Node")))
               || pos.equals(a)
               || pos.equals(b)
               || out.contains(pos)
               || RoadData.get(level).linked(pos)) {
               throw new IllegalArgumentException("控制端点已移动、被使用或重复，请重新选择");
            }

            out.add(pos);
         }

         return List.copyOf(out);
      }
   }

   public static RoadAttachments.Data attachments(RoadAttachments.Data old, List<RoadProfile.Control> before, List<RoadProfile.Control> after) {
      HashSet<UUID> ids = new HashSet<>();

      for (RoadProfile.Control c : before) {
         ids.add(c.id());
      }

      for (RoadProfile.Control c : after) {
         ids.add(c.id());
      }

      ArrayList<RoadAttachments.Point> points = new ArrayList<>();

      for (RoadAttachments.Point p : old.points()) {
         if (!ids.contains(p.id())) {
            points.add(p);
         }
      }

      return old.points(points);
   }

   private CurveControlNodes() {
   }
}
