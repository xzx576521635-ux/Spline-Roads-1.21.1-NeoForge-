package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.net.RoadNetwork;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

public final class ElevatedPointTool extends Item {
   public ElevatedPointTool() {
      super(new Properties().stacksTo(1));
   }

   public static boolean supported(RoadRecord r) {
      RoadProfile.Type type = RoadProfile.catalog(r.settings()).type();
      return r.assembly() == null
         && r.junction() == null
         && r.toll() == null
         && !r.railway()
         && r.settings().mode() != RoadGeometry.Mode.RING
         && LaneTopology.metadata(r).link() == null
         && (type == RoadProfile.Type.ORDINARY || type == RoadProfile.Type.HIGHWAY);
   }

   public static RoadAttachments.Point create(ServerLevel level, ServerPlayer player, RoadIndex.Built road, RoadGeometry.V hit) {
      if (road != null && supported(road.record)) {
         if (player != null) {
            if (!player.mayBuild() || player.isSpectator()) {
               throw new IllegalArgumentException("没有建设权限");
            }

            RoadData.requireOwner(player, road.record.owner());
         }

         RoadQueries.Projection q = RoadQueries.horizontal(road.mesh, hit);
         if (!(q.horizontalDistance() > q.sample().halfWidth() + 0.05) && !(Math.abs(hit.y() - q.sample().center().y()) > 2.5)) {
            RoadProfile.Layout layout = RoadProfile.layout(road.mesh, q.sample());
            RoadGeometry.V at = q.sample().at(layout.catalog().twoWay() ? layout.medianCenter() : 0.0, 0.0);
            RoadAttachments.Data metadata = road.record.settings().options().attachments();

            for (RoadAttachments.Point point : metadata.points()) {
               if (point.elevated() && point.position().distance(at) < 1.1) {
                  return point;
               }
            }

            RoadAttachments.Point pointx = new RoadAttachments.Point(UUID.randomUUID(), at, q.sample().center(), false, at, true);
            ArrayList<RoadAttachments.Point> points = new ArrayList<>(metadata.points());
            points.add(pointx);
            RoadData.get(level).updatePointMetadata(level, player, road.record, metadata.points(points));
            return pointx;
         } else {
            throw new IllegalArgumentException("请在道路路面创建高架出入点");
         }
      } else {
         throw new IllegalArgumentException("高架出入点只能创建在道路上，不能位于路口或自动生成的分支");
      }
   }

   public InteractionResult useOn(UseOnContext context) {
      if (context.getLevel().isClientSide) {
         return InteractionResult.SUCCESS;
      } else {
         if (context.getPlayer() instanceof ServerPlayer player) {
            try {
               Vec3 hit = context.getClickLocation();
               RoadIndex.Built road = RoadTool.pick(player);
               RoadAttachments.Point point = create(player.serverLevel(), player, road, new RoadGeometry.V(hit.x, hit.y, hit.z));
               RoadIndex.Built updated = RoadData.get(player.serverLevel()).index.roads.get(road.record.id());
               RoadNetwork.open(player, AttachedPointTool.payload(updated.record, point));
            } catch (IllegalArgumentException var7) {
               player.displayClientMessage(Component.literal(var7.getMessage()), false);
            }
         }

         return InteractionResult.CONSUME;
      }
   }
}
