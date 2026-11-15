package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadLaneLines;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

public final class LaneLineTool extends Item {
   public LaneLineTool() {
      super(new Properties().stacksTo(1));
   }

   public InteractionResult useOn(UseOnContext c) {
      if (c.getLevel().isClientSide) {
         return InteractionResult.SUCCESS;
      } else {
         if (c.getPlayer() instanceof ServerPlayer p) {
            try {
               RoadIndex.Built built = RoadTool.pick(p);
               if (built == null) {
                  throw new IllegalArgumentException("请右键要编辑的道路");
               }

               if (built.record.junction() != null || built.record.toll() != null) {
                  throw new IllegalArgumentException("请选中路口外的独立路段");
               }

               RoadData.requireOwner(p, built.record.owner());
               if (!RoadProfile.modern(built.record.settings().style())) {
                  throw new IllegalArgumentException("请先把旧式道路更新为普通道路或高速公路");
               }

               CompoundTag t = new CompoundTag();
               t.putString("Kind", "laneLines");
               t.putUUID("Id", built.record.id());
               t.putInt("Signature", GantryTool.signature(built.record));
               t.put("Settings", RoadRecord.writeSettings(built.record.settings()));
               ListTag lines = new ListTag();
               int selected = 0;
               int n = 0;
               double best = Double.POSITIVE_INFINITY;
               Vec3 hit = c.getClickLocation();
               double lateral = RoadQueries.horizontal(built.mesh, new RoadGeometry.V(hit.x, hit.y, hit.z)).lateral();

               for (RoadLaneLines.Line line : RoadLaneLines.lines(built.mesh)) {
                  CompoundTag e = new CompoundTag();
                  e.putString("Key", line.key());
                  e.putString("Label", line.label());
                  e.putDouble("Offset", line.offset());
                  lines.add(e);
                  double d = Math.abs(line.offset() - lateral);
                  if (d < best) {
                     best = d;
                     selected = n;
                  }

                  n++;
               }

               RoadGeometry.Sample at = RoadQueries.horizontal(built.mesh, new RoadGeometry.V(hit.x, hit.y, hit.z)).sample();
               double station = Math.min(built.mesh.length() - 1.0E-6, Math.max(0.0, at.distance()));
               RoadAttachments.Range range = RoadAttachments.range(built.mesh, station);
               RoadAttachments.Paint paint = RoadAttachments.paint(built.mesh, station);
               t.putDouble("Station", station);
               t.putDouble("RangeStart", range.start());
               t.putDouble("RangeEnd", range.end());
               t.put(
                  "Settings",
                  RoadRecord.writeSettings(
                     built.record.settings().options(built.record.settings().options().laneLines(paint.lines()).hideArrows(paint.hideArrows()))
                  )
               );
               t.putDouble("RoadYaw", RoadPlanner.yaw(at.left().left().mul(-1.0)));
               t.putDouble("PlayerYaw", (double)p.getYRot());
               t.put("Lines", lines);
               t.putInt("Selected", selected);
               t.putDouble("Width", built.record.settings().width());
               RoadNetwork.open(p, t);
            } catch (IllegalArgumentException var18) {
               p.displayClientMessage(Component.literal(var18.getMessage()), false);
            }
         }

         return InteractionResult.CONSUME;
      }
   }
}
