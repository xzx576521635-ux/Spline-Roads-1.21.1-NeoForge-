package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.world.LanePointTool;
import com.sora.splineroads.world.LaneRampTool;
import com.sora.splineroads.world.LaneTopology;
import com.sora.splineroads.world.RoadIndex;
import java.util.List;
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
public final class LanePointRenderer {
   static boolean accepts(ItemStack s) {
      return s.getItem() instanceof LanePointTool || s.getItem() instanceof LaneRampTool;
   }

   @SubscribeEvent
   public static void render(RenderLevelStageEvent e) {
      if (e.getStage() == Stage.AFTER_BLOCK_ENTITIES) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.player != null && (accepts(mc.player.getMainHandItem()) || accepts(mc.player.getOffhandItem()))) {
            Vec3 camera = e.getCamera().getPosition();
            PoseStack pose = e.getPoseStack();
            BufferSource buffer = mc.renderBuffers().bufferSource();
            VertexConsumer lines = buffer.getBuffer(RenderType.lines());
            pose.pushPose();
            pose.translate(-camera.x, -camera.y, -camera.z);

            for (RoadIndex.Built road : ClientToolIndex.near(camera.x, camera.z, true)) {
               if (!road.record.railway() && ClientRoads.active(road.record.id())) {
                  for (LanePoints.Point p : LaneTopology.visiblePoints(road.record)) {
                     RoadGeometry.V v = p.position();
                     if (!(camera.distanceToSqr(v.x(), v.y(), v.z()) > 9216.0)) {
                        LevelRenderer.renderLineBox(
                           pose, lines, new AABB(v.x() - 0.24, v.y() + 0.04, v.z() - 0.24, v.x() + 0.24, v.y() + 0.53, v.z() + 0.24), 0.12F, 0.45F, 1.0F, 1.0F
                        );
                        RoadGeometry.V d = LanePoints.lane(road.mesh, p).direction();
                        RoadGeometry.V tip = v.add(d.mul(0.9));
                        RoadGeometry.V rear = v.add(d.mul(0.45));

                        for (RoadGeometry.V end : List.of(v, rear.add(d.left().mul(0.22)), rear.sub(d.left().mul(0.22)))) {
                           RoadGeometry.V a = tip.add(new RoadGeometry.V(0.0, 0.12, 0.0));
                           RoadGeometry.V b = end.add(new RoadGeometry.V(0.0, 0.12, 0.0));
                           RoadGeometry.V n = b.sub(a).horizontalUnit();
                           lines.addVertex(pose.last().pose(), (float)a.x(), (float)a.y(), (float)a.z())
                              .setColor(0.12F, 0.55F, 1.0F, 1.0F)
                              .setNormal(pose.last(), (float)n.x(), 0.0F, (float)n.z());
                           lines.addVertex(pose.last().pose(), (float)b.x(), (float)b.y(), (float)b.z())
                              .setColor(0.12F, 0.55F, 1.0F, 1.0F)
                              .setNormal(pose.last(), (float)n.x(), 0.0F, (float)n.z());
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
