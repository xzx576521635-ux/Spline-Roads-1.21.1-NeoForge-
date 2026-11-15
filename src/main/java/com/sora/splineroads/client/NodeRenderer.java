package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.world.NodeEntity;
import com.sora.splineroads.world.RoadBlocks;
import com.sora.splineroads.world.RoadFillEntity;
import com.sora.splineroads.world.RoadTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Block;
import net.neoforged.neoforge.client.model.data.ModelData;

@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT},
   bus = Bus.MOD
)
public final class NodeRenderer implements BlockEntityRenderer<NodeEntity> {
   public NodeRenderer(Context context) {
   }

   @SubscribeEvent
   public static void register(RegisterRenderers e) {
      e.registerBlockEntityRenderer((BlockEntityType)SplineRoads.NODE_ENTITY.get(), NodeRenderer::new);
   }

   @SubscribeEvent
   public static void colors(Block event) {
      event.register(
         (state, level, pos, tint) -> level != null && pos != null && state.getValue(RoadBlocks.Road.FILL) == RoadBlocks.Fill.GRASS_BLOCK
               ? BiomeColors.getAverageGrassColor(level, pos)
               : 16777215,
         new net.minecraft.world.level.block.Block[]{(net.minecraft.world.level.block.Block)SplineRoads.COLLIDER.get()}
      );
      event.register(
         (state, level, pos, tint) -> level != null && pos != null && level.getBlockEntity(pos) instanceof RoadFillEntity fill
               ? Minecraft.getInstance().getBlockColors().getColor(fill.fill(), level, pos, tint)
               : 16777215,
         new net.minecraft.world.level.block.Block[]{(net.minecraft.world.level.block.Block)SplineRoads.FILLED_COLLIDER.get()}
      );
   }

   public void render(NodeEntity n, float partial, PoseStack pose, MultiBufferSource buffer, int light, int overlay) {
      LocalPlayer p = Minecraft.getInstance().player;
      if (p != null && (RoadTool.showsNodes(p.getMainHandItem()) || RoadTool.showsNodes(p.getOffhandItem()))) {
         BlockRenderDispatcher renderer = Minecraft.getInstance().getBlockRenderer();
         renderer.getModelRenderer()
            .renderModel(
               pose.last(),
               buffer.getBuffer(RenderType.solid()),
               n.getBlockState(),
               RoadTerrainModels.markerModel(renderer.getBlockModel(n.getBlockState())),
               1.0F,
               1.0F,
               1.0F,
               light,
               overlay,
               ModelData.EMPTY,
               RenderType.solid()
            );
         pose.pushPose();
         pose.translate(0.5 + n.offsetX, n.offsetY + 0.05, 0.5 + n.offsetZ);
         pose.mulPose(Axis.YP.rotationDegrees(-((float)n.yaw)));
         VertexConsumer c = buffer.getBuffer(RenderType.lines());
         this.line(pose, c, 0.0F, 0.0F, -0.6F, 0.0F, 0.0F, 0.8F);
         this.line(pose, c, 0.0F, 0.0F, 0.8F, -0.3F, 0.0F, 0.4F);
         this.line(pose, c, 0.0F, 0.0F, 0.8F, 0.3F, 0.0F, 0.4F);
         this.line(pose, c, -0.3F, 0.0F, -0.3F, 0.3F, 0.0F, -0.3F);
         this.line(pose, c, 0.3F, 0.0F, -0.3F, 0.3F, 0.0F, 0.3F);
         this.line(pose, c, 0.3F, 0.0F, 0.3F, -0.3F, 0.0F, 0.3F);
         this.line(pose, c, -0.3F, 0.0F, 0.3F, -0.3F, 0.0F, -0.3F);
         pose.popPose();
      }
   }

   private void line(PoseStack p, VertexConsumer c, float x, float y, float z, float xx, float yy, float zz) {
      float dx = xx - x;
      float dy = yy - y;
      float dz = zz - z;
      float l = (float)Math.sqrt((double)(dx * dx + dy * dy + dz * dz));
      c.addVertex(p.last().pose(), x, y, z).setColor(70, 255, 223, 255).setNormal(p.last(), dx / l, dy / l, dz / l);
      c.addVertex(p.last().pose(), xx, yy, zz).setColor(70, 255, 223, 255).setNormal(p.last(), dx / l, dy / l, dz / l);
   }

   public boolean shouldRenderOffScreen(NodeEntity n) {
      return true;
   }
}
