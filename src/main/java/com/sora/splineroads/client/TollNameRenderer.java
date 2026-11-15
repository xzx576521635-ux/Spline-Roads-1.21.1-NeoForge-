package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.TollInlineGeometry;
import com.sora.splineroads.core.TollPlanner;
import com.sora.splineroads.core.TollPlaza;
import com.sora.splineroads.world.RoadIndex;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class TollNameRenderer {
   private static long revision = -1L;
   private static int chunkX = Integer.MIN_VALUE;
   private static int chunkZ = Integer.MIN_VALUE;
   private static List<TollPlaza.Spec> nearby = List.of();

   static void clear() {
      revision = -1L;
      chunkZ = Integer.MIN_VALUE;
      chunkX = Integer.MIN_VALUE;
      nearby = List.of();
   }

   static List<TollPlaza.Spec> nearby(int cx, int cz) {
      RoadIndex index = ClientRoads.INDEX;
      if (revision == index.revision() && cx == chunkX && cz == chunkZ) {
         return nearby;
      } else {
         HashSet<UUID> ids = new HashSet<>();

         for (int x = cx - 12; x <= cx + 12; x++) {
            for (int z = cz - 12; z <= cz + 12; z++) {
               ids.addAll(index.inChunk(ChunkPos.asLong(x, z)));
            }
         }

         LinkedHashSet<TollPlaza.Spec> specs = new LinkedHashSet<>();

         for (UUID id : ids) {
            RoadIndex.Built road = index.roads.get(id);
            if (road != null && ClientRoads.active(id) && road.record.toll() != null && !road.record.toll().config().name().isEmpty()) {
               specs.add(road.record.toll());
            }
         }

         nearby = List.copyOf(specs);
         revision = index.revision();
         chunkX = cx;
         chunkZ = cz;
         return nearby;
      }
   }

   public static void render(RenderLevelStageEvent event) {
      if (RoadSignVisibility.enabled()) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.level != null) {
            Vec3 eye = event.getCamera().getPosition();

            for (TollPlaza.Spec spec : nearby((int)Math.floor(eye.x) >> 4, (int)Math.floor(eye.z) >> 4)) {
               draw(spec, event);
            }

            if (TollPreview.namedPreview != null && ClientRoads.preview != null) {
               draw(TollPreview.namedPreview.spec(), event);
            }
         }
      }
   }

   private static void draw(TollPlaza.Spec spec, RenderLevelStageEvent event) {
      String text = spec.config().name();
      if (!text.isEmpty()) {
         Minecraft mc = Minecraft.getInstance();
         Vec3 camera = event.getCamera().getPosition();
         RoadGeometry.V axis = spec.b().position().sub(spec.a().position()).horizontalUnit();
         TollPlanner.Lengths lengths = TollPlanner.lengths(spec);
         RoadGeometry.V station = spec.a().position().add(axis.mul((lengths.current() + lengths.a() - lengths.b()) / 2.0));
         RoadGeometry.V center = new RoadGeometry.V(station.x(), (spec.a().position().y() + spec.b().position().y()) / 2.0, station.z());
         if (!spec.path().isEmpty()) {
            double d = lengths.current() / 2.0;
            center = TollInlineGeometry.equipmentAt(spec, d, 0.0, 0.0);
            axis = TollInlineGeometry.equipmentAt(spec, d + 0.05, 0.0, 0.0).sub(TollInlineGeometry.equipmentAt(spec, d - 0.05, 0.0, 0.0)).horizontalUnit();
         }

         RoadGeometry.V eye = new RoadGeometry.V(camera.x, camera.y, camera.z);
         if (!(eye.distance(center) > 192.0)) {
            MutableComponent styled = Component.literal(text).withStyle(ChatFormatting.BOLD);
            PoseStack pose = event.getPoseStack();
            BufferSource buffers = mc.renderBuffers().bufferSource();

            for (int side : TollPlanner.nameSides(spec.config())) {
               double span = TollPlanner.nameWidth(spec.config(), side) - 0.36;
               double scale = Math.min(0.16, span / (double)Math.max(1, mc.font.width(styled)));
               RoadGeometry.V front = axis.mul((double)side);
               RoadGeometry.V right = front.left().mul(-1.0);
               RoadGeometry.V at = center.add(axis.left().mul(TollPlanner.nameCenter(spec.config(), side)))
                  .add(front.mul(5.765))
                  .add(new RoadGeometry.V(0.0, spec.config().clearHeight() + 2.35 + 4.0 * scale, 0.0));
               if (!(eye.sub(at).dot(front) <= 0.0)) {
                  AABB box = new AABB(at.x() - span, at.y() - 2.0, at.z() - span, at.x() + span, at.y() + 1.0, at.z() + span);
                  if (event.getFrustum().isVisible(box)) {
                     for (int layer = 0; layer < 4; layer++) {
                        RoadGeometry.V point = at.add(front.mul((double)layer * 0.035));
                        pose.pushPose();
                        pose.translate(point.x() - camera.x, point.y() - camera.y, point.z() - camera.z);
                        pose.mulPose(Axis.YP.rotation((float)Math.atan2(-right.z(), right.x())));
                        pose.scale((float)scale, (float)(-scale), (float)scale);
                        mc.font
                           .drawInBatch(
                              styled,
                              (float)(-mc.font.width(styled)) / 2.0F,
                              0.0F,
                              layer == 3 ? 15483448 : 9251109,
                              false,
                              pose.last().pose(),
                              buffers,
                              DisplayMode.NORMAL,
                              0,
                              15728880
                           );
                        pose.popPose();
                     }
                  }
               }
            }
         }
      }
   }

   private TollNameRenderer() {
   }
}
