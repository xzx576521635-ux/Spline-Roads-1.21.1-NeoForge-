package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sora.splineroads.world.RoadFillEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;

public final class RoadFillRenderer implements BlockEntityRenderer<RoadFillEntity> {
   public RoadFillRenderer(Context context) {
   }

   public int getViewDistance() {
      return 256;
   }

   public void render(RoadFillEntity entity, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
      if (!entity.fill().isAir() && !(entity.cap() < 1.0)) {
         pose.pushPose();
         pose.translate(0.0, -0.002, 0.0);
         Minecraft.getInstance().getBlockRenderer().renderSingleBlock(entity.fill(), pose, buffers, light, overlay);
         pose.popPose();
      }
   }
}
