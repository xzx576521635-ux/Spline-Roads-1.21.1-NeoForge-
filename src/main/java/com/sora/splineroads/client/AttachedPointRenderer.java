package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.world.AttachedPointTool;
import com.sora.splineroads.world.ElevatedPointTool;
import com.sora.splineroads.world.InterchangeTool;
import com.sora.splineroads.world.LaneLineTool;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;

@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT}
)
public final class AttachedPointRenderer {
   private static boolean accepts(ItemStack s) {
      return s.getItem() instanceof RoadTool
         || s.getItem() instanceof ElevatedPointTool
         || s.getItem() instanceof AttachedPointTool
         || s.getItem() instanceof LaneLineTool
         || s.getItem() instanceof InterchangeTool;
   }

   @SubscribeEvent
   public static void render(RenderLevelStageEvent e) {
      if (e.getStage() == Stage.AFTER_BLOCK_ENTITIES) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.player != null
            && !LanePointRenderer.accepts(mc.player.getMainHandItem())
            && !LanePointRenderer.accepts(mc.player.getOffhandItem())
            && (accepts(mc.player.getMainHandItem()) || accepts(mc.player.getOffhandItem()))) {
            Vec3 camera = e.getCamera().getPosition();
            PoseStack pose = e.getPoseStack();
            BufferSource buffer = mc.renderBuffers().bufferSource();
            VertexConsumer lines = buffer.getBuffer(RenderType.lines());
            pose.pushPose();
            pose.translate(-camera.x, -camera.y, -camera.z);

            for (RoadIndex.Built road : ClientToolIndex.near(camera.x, camera.z, false)) {
               if (ClientRoads.active(road.record.id())) {
                  for (RoadAttachments.Point p : road.record.settings().options().attachments().points()) {
                     if (!road.record.settings().options().routing().controls().stream().anyMatch(c -> c.id().equals(p.id()))) {
                        RoadGeometry.V v = p.position();
                        if (!(camera.distanceToSqr(v.x(), v.y(), v.z()) > 9216.0)) {
                           LevelRenderer.renderLineBox(
                              pose,
                              lines,
                              new AABB(v.x() - 0.22, v.y() + 1.65, v.z() - 0.22, v.x() + 0.22, v.y() + 2.15, v.z() + 0.22),
                              p.elevated() ? 0.2F : 1.0F,
                              p.elevated() ? 0.8F : 0.12F,
                              p.elevated() ? 1.0F : 0.12F,
                              1.0F
                           );
                           LevelRenderer.renderLineBox(
                              pose,
                              lines,
                              new AABB(v.x() - 0.025, v.y() + 0.05, v.z() - 0.025, v.x() + 0.025, v.y() + 1.65, v.z() + 0.025),
                              1.0F,
                              0.45F,
                              0.35F,
                              1.0F
                           );
                        }
                     }
                  }
               }
            }

            pose.popPose();
            buffer.endBatch(RenderType.lines());
         }
      }
   }
}
