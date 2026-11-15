package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.net.RoadNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AttachedPointTool extends Item {
   static boolean supported(RoadRecord r) {
      return SideHosts.attachable(r)
         || r.junction() == null
            && r.toll() == null
            && !r.railway()
            && !r.settings().style().ramp()
            && r.settings().mode() != RoadGeometry.Mode.RING
            && (RoadProfile.catalog(r.settings()).type() == RoadProfile.Type.ORDINARY || RoadProfile.catalog(r.settings()).type() == RoadProfile.Type.HIGHWAY);
   }

   public static AttachedPointTool.Pick pick(ServerPlayer player) {
      Vec3 eye = player.getEyePosition();
      Vec3 direction = player.getLookAngle();
      Vec3 end = eye.add(direction.scale(96.0));
      double stop = player.pick(96.0, 0.0F, false).getLocation().distanceTo(eye) + 0.7;
      RoadIndex index = RoadData.get(player.serverLevel()).index;
      HashSet<UUID> ids = new HashSet<>();

      for (int d = 0; d <= 96; d += 8) {
         Vec3 at = eye.add(direction.scale((double)d));
         int cx = (int)Math.floor(at.x) >> 4;
         int cz = (int)Math.floor(at.z) >> 4;

         for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
               ids.addAll(index.inChunk(ChunkPos.asLong(cx + x, cz + z)));
            }
         }
      }

      AttachedPointTool.Pick best = null;

      for (UUID id : ids) {
         RoadIndex.Built road = index.roads.get(id);
         if (road != null && (supported(road.record) || ElevatedPointTool.supported(road.record))) {
            for (RoadAttachments.Point point : road.record.settings().options().attachments().points()) {
               if ((point.elevated() || supported(road.record))
                  && !road.record.settings().options().routing().controls().stream().anyMatch(c -> c.id().equals(point.id()))) {
                  RoadGeometry.V p = point.position();
                  Optional<Vec3> hit = new AABB(p.x() - 0.26, p.y() + 1.6, p.z() - 0.26, p.x() + 0.26, p.y() + 2.2, p.z() + 0.26).clip(eye, end);
                  if (hit.isPresent()) {
                     double distance = hit.get().distanceTo(eye);
                     if (distance <= stop && (best == null || distance < best.distance())) {
                        best = new AttachedPointTool.Pick(road, point, distance);
                     }
                  }
               }
            }
         }
      }

      return best;
   }

   public AttachedPointTool() {
      super(new Properties().stacksTo(1));
   }

   static RoadAttachments.Point create(ServerLevel level, ServerPlayer player, RoadIndex.Built road, RoadGeometry.V hit) {
      RoadQueries.Projection q = RoadQueries.horizontal(road.mesh, hit);
      if (q.horizontalDistance() > q.sample().halfWidth() + 0.05) {
         throw new IllegalArgumentException("附属点必须位于道路路面");
      } else {
         RoadGeometry.V pos = q.sample().center();
         RoadAttachments.Point existing = nearest(road, pos);
         if (existing != null) {
            return existing;
         } else {
            RoadAttachments.Point point = new RoadAttachments.Point(UUID.randomUUID(), pos, pos, false);
            RoadAttachments.Data metadata = road.record.settings().options().attachments();
            ArrayList<RoadAttachments.Point> points = new ArrayList<>(metadata.points());
            points.add(point);
            RoadData.get(level).updatePointMetadata(level, player, road.record, metadata.points(points));
            return point;
         }
      }
   }

   public static RoadAttachments.Point nearest(RoadIndex.Built road, RoadGeometry.V hit) {
      return road.record
         .settings()
         .options()
         .attachments()
         .points()
         .stream()
         .filter(p -> road.record.settings().options().routing().controls().stream().noneMatch(c -> c.id().equals(p.id())))
         .filter(p -> p.position().sub(hit).horizontalLength() < 1.1 && hit.y() - p.position().y() >= -0.2 && hit.y() - p.position().y() <= 2.5)
         .min(Comparator.comparingDouble(p -> p.position().sub(hit).horizontalLength()))
         .orElse(null);
   }

   public InteractionResult useOn(UseOnContext c) {
      if (c.getLevel().isClientSide) {
         return InteractionResult.SUCCESS;
      } else {
         if (c.getPlayer() instanceof ServerPlayer p) {
            try {
               if (p.mayBuild() && !p.isSpectator()) {
                  Vec3 hit = c.getClickLocation();
                  RoadGeometry.V v = new RoadGeometry.V(hit.x, hit.y, hit.z);
                  RoadIndex.Built road = SideHosts.hostAt(RoadData.get(p.serverLevel()), RoadTool.pick(p), v);
                  if (road != null && supported(road.record)) {
                     RoadData.requireOwner(p, road.record.owner());
                     RoadAttachments.Point point = nearest(road, v);
                     if (point == null) {
                        point = create(p.serverLevel(), p, road, v);
                        road = RoadData.get(p.serverLevel()).index.roads.get(road.record.id());
                     }

                     RoadNetwork.open(p, payload(road.record, point));
                     return InteractionResult.CONSUME;
                  }

                  throw new IllegalArgumentException("请右键道路路面，不能选路口中心铺装");
               }

               throw new IllegalArgumentException("没有建设权限");
            } catch (IllegalArgumentException var7) {
               p.displayClientMessage(Component.literal(var7.getMessage()), false);
            }
         }

         return InteractionResult.CONSUME;
      }
   }

   public static CompoundTag payload(RoadRecord r, RoadAttachments.Point p) {
      CompoundTag t = new CompoundTag();
      t.putString("Kind", "attachedPoint");
      t.putBoolean("Elevated", p.elevated());
      t.put("Road", r.header());
      t.putUUID("Id", r.id());
      t.putUUID("Point", p.id());
      t.putInt("Signature", GantryTool.signature(r));
      t.put("Position", RoadRecord.writeNode(new RoadGeometry.Node(p.position(), 0.0, 0.0)));
      t.put("Origin", RoadRecord.writeNode(new RoadGeometry.Node(p.origin(), 0.0, 0.0)));
      t.putBoolean("MarkingsOnly", r.assembly() != null);
      return t;
   }

   public static String edit(ServerLevel level, ServerPlayer player, CompoundTag t) {
      RoadData data = RoadData.get(level);
      RoadIndex.Built built = data.index.roads.get(t.getUUID("Id"));
      if (built != null && built.record.toll() == null) {
         RoadRecord r = built.record;
         if (player != null) {
            RoadData.requireOwner(player, r.owner());
            if (!player.mayBuild() || player.isSpectator()) {
               throw new IllegalArgumentException("没有建设权限");
            }
         }

         if (t.getInt("Signature") != GantryTool.signature(r)) {
            throw new IllegalArgumentException("道路已改变，请重新选择附属点");
         } else {
            RoadAttachments.Data metadata = r.settings().options().attachments();
            RoadAttachments.Point point = metadata.points()
               .stream()
               .filter(p -> p.id().equals(t.getUUID("Point")))
               .findFirst()
               .orElseThrow(() -> new IllegalArgumentException("附属点已改变所属道路，请重新选择"));
            ArrayList<RoadAttachments.Point> points = new ArrayList<>(metadata.points());
            if (t.getBoolean("Delete")) {
               double d = RoadAttachments.station(built.mesh, point.position());
               if (d > 1.0E-5
                  && d < built.mesh.length() - 1.0E-5
                  && !RoadAttachments.paint(built.mesh, d - 1.0E-5).equals(RoadAttachments.paint(built.mesh, d + 1.0E-5))) {
                  throw new IllegalArgumentException("附属点两侧标线不同，请先统一两个区间的标线后再删除");
               } else {
                  points.remove(point);
                  if (point.controlled()) {
                     data.removeAttachedPoint(level, player, r, metadata.points(points), point.id());
                  } else {
                     data.updatePointMetadata(level, player, r, metadata.points(points));
                  }

                  return "附属点已删除";
               }
            } else {
               throw new IllegalArgumentException("附属点固定在道路断面中央，不能调整坐标或用作曲线控制点");
            }
         }
      } else {
         throw new IllegalArgumentException("收费站请使用收费站生成器编辑");
      }
   }

   public static record Pick(RoadIndex.Built road, RoadAttachments.Point point, double distance) {
   }
}
