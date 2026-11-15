package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class InterchangeEndpoints {
   static InterchangeEndpoints.Endpoint read(ServerLevel level, ServerPlayer player, CompoundTag saved, int i) {
      long[] points = saved.getLongArray("Points");
      BlockPos pos = BlockPos.of(points[i]);

      try (RoadWorkChunks chunks = RoadWorkChunks.open(level)) {
         chunks.node(pos);
         if (level.getBlockEntity(pos) instanceof NodeEntity) {
            NodeEntity marker = RoadData.requireNode(level, pos, player);
            return new InterchangeEndpoints.Endpoint(marker.constructionNode(), marker, marker.saveWithoutMetadata(level.registryAccess()));
         } else {
            ListTag nodes = saved.getList("Nodes", 10);
            RoadData data = RoadData.get(level);
            if (!saved.hasUUID("Id")
               || !saved.hasUUID("Owner")
               || nodes.size() != points.length
               || data.index.roads.values().stream().noneMatch(r -> saved.getUUID("Id").equals(r.record.assembly()))) {
               throw new IllegalArgumentException("端点 " + (char)(65 + i) + " 缺失，且没有完整的立交端点记录");
            } else {
               if (player != null) {
                  RoadData.requireOwner(player, saved.getUUID("Owner"));
                  if (!player.mayBuild() || !level.mayInteract(player, pos)) {
                     throw new IllegalArgumentException("此处没有建设权限");
                  }
               }

               BlockState state = level.getBlockState(pos);
               boolean ownRoad = RoadBlocks.isCollider(state)
                  && !data.index.at(pos.asLong()).isEmpty()
                  && data.index.at(pos.asLong()).stream().allMatch(id -> saved.getUUID("Id").equals(data.index.roads.get(id).record.assembly()));
               if (level.getBlockEntity(pos) == null && (TunnelAir.isAirSpace(state) || state.is((Block)SplineRoads.NODE.get()) || ownRoad)) {
                  RoadGeometry.Node node = RoadRecord.readNode(nodes.getCompound(i));
                  RoadGeometry.V p = node.position();
                  if (Double.isFinite(p.x())
                     && Double.isFinite(p.y())
                     && Double.isFinite(p.z())
                     && Double.isFinite(node.yaw())
                     && Double.isFinite(node.grade())) {
                     CompoundTag snapshot = new CompoundTag();
                     snapshot.putUUID("Owner", saved.getUUID("Owner"));
                     snapshot.put("Node", RoadRecord.writeNode(node));
                     snapshot.putBoolean("HeightExplicit", true);
                     return new InterchangeEndpoints.Endpoint(node, null, snapshot);
                  } else {
                     throw new IllegalArgumentException("立交端点记录无效，请恢复存档备份");
                  }
               } else {
                  throw new IllegalArgumentException("立交端点 " + (char)(65 + i) + " 缺失，原位置被占用：" + pos.toShortString());
               }
            }
         }
      }
   }

   private InterchangeEndpoints() {
   }

   static record Endpoint(RoadGeometry.Node node, NodeEntity marker, CompoundTag snapshot) {
   }
}
