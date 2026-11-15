package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.sora.splineroads.core.RailbedCandidates;
import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.world.RailbedTool;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;

@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT}
)
public final class RailbedHighlight {
   @SubscribeEvent
   public static void render(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_BLOCK_ENTITIES) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.player != null) {
            if (mc.player.getMainHandItem().getItem() instanceof RailbedTool || mc.player.getOffhandItem().getItem() instanceof RailbedTool) {
               LinkedHashMap<String, RailbedPath.Path> paths = new LinkedHashMap<>();
               RailbedScreen.candidates.forEach(px -> paths.put(RailbedCandidates.key(px), px));
               HashSet<String> selected = new HashSet<>();
               RailbedScreen.highlighted.forEach(px -> {
                  paths.put(RailbedCandidates.key(px), px);
                  selected.add(RailbedCandidates.key(px));
               });
               Vec3 camera = event.getCamera().getPosition();
               PoseStack pose = event.getPoseStack();
               pose.pushPose();
               pose.translate(-camera.x, -camera.y, -camera.z);
               BufferSource buffers = mc.renderBuffers().bufferSource();
               VertexConsumer line = buffers.getBuffer(RenderType.lines());

               for (Entry<String, RailbedPath.Path> entry : paths.entrySet()) {
                  boolean active = selected.contains(entry.getKey());
                  List<RoadGeometry.V> points = entry.getValue().positions();

                  for (int i = 1; i < points.size(); i++) {
                     RoadGeometry.V a = points.get(i - 1);
                     RoadGeometry.V b = points.get(i);
                     if (!(camera.distanceToSqr(a.x(), a.y(), a.z()) > 16384.0) || !(camera.distanceToSqr(b.x(), b.y(), b.z()) > 16384.0)) {
                        RoadGeometry.V n = b.sub(a).horizontalUnit();

                        for (RoadGeometry.V p : List.of(a, b)) {
                           line.addVertex(pose.last().pose(), (float)p.x(), (float)p.y() + 0.2F, (float)p.z())
                              .setColor(active ? 0.15F : 1.0F, active ? 1.0F : 0.7F, active ? 0.3F : 0.15F, 1.0F)
                              .setNormal(pose.last(), (float)n.x(), 0.0F, (float)n.z());
                        }
                     }
                  }
               }

               pose.popPose();
               buffers.endBatch(RenderType.lines());
            }
         }
      }
   }

   @SubscribeEvent
   public static void logout(LoggingOut e) {
      RailbedScreen.reset();
   }

   private RailbedHighlight() {
   }
}
