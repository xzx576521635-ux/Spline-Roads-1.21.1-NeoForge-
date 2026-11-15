package com.sora.splineroads.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderStateShard.LayeringStateShard;
import net.minecraft.client.renderer.RenderStateShard.ShaderStateShard;
import net.minecraft.client.renderer.RenderStateShard.TextureStateShard;
import net.minecraft.client.renderer.RenderType.CompositeState;
import net.minecraft.resources.ResourceLocation;

public final class RoadRenderTypes extends RenderType {
   private RoadRenderTypes() {
      super("splineroads_unused", DefaultVertexFormat.NEW_ENTITY, Mode.QUADS, 256, false, false, () -> {
      }, () -> {
      });
   }

   public static RenderType glass(ResourceLocation texture) {
      return create(
         "splineroads_noise_glass",
         DefaultVertexFormat.NEW_ENTITY,
         Mode.QUADS,
         262144,
         false,
         true,
         CompositeState.builder()
            .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
            .setTextureState(new TextureStateShard(texture, false, false))
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setLightmapState(LIGHTMAP)
            .setOverlayState(OVERLAY)
            .setCullState(CULL)
            .setDepthTestState(LEQUAL_DEPTH_TEST)
            .setWriteMaskState(COLOR_WRITE)
            .createCompositeState(false)
      );
   }

   public static RenderType conflict(ResourceLocation texture) {
      return create(
         "splineroads_conflict",
         DefaultVertexFormat.NEW_ENTITY,
         Mode.QUADS,
         262144,
         false,
         true,
         CompositeState.builder()
            .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
            .setTextureState(new TextureStateShard(texture, false, false))
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setLightmapState(LIGHTMAP)
            .setOverlayState(OVERLAY)
            .setCullState(NO_CULL)
            .setDepthTestState(NO_DEPTH_TEST)
            .setWriteMaskState(COLOR_WRITE)
            .createCompositeState(false)
      );
   }

   public static RenderType surface(ResourceLocation texture, boolean marking) {
      return surface(texture, marking, false);
   }

   public static RenderType solid(ResourceLocation texture) {
      return surface(texture, false, true);
   }

   private static RenderType surface(ResourceLocation texture, boolean marking, boolean solid) {
      float factor = solid ? 0.0F : (marking ? -2.0F : -1.0F);
      float units = solid ? 0.0F : (marking ? -16.0F : -8.0F);
      LayeringStateShard offset = new LayeringStateShard(marking ? "road_marking_offset" : "road_surface_offset", () -> {
         RenderSystem.polygonOffset(factor, units);
         RenderSystem.enablePolygonOffset();
      }, () -> {
         RenderSystem.polygonOffset(0.0F, 0.0F);
         RenderSystem.disablePolygonOffset();
      });
      return create(
         marking ? "splineroads_markings" : "splineroads_pavement",
         DefaultVertexFormat.NEW_ENTITY,
         Mode.QUADS,
         262144,
         false,
         false,
         CompositeState.builder()
            .setShaderState(new ShaderStateShard(() -> RoadShaders.opaque(texture)))
            .setTextureState(
               new TextureStateShard(texture, false, texture.equals(ResourceLocation.fromNamespaceAndPath("splineroads", "textures/road/asphalt.png")))
            )
            .setLightmapState(LIGHTMAP)
            .setOverlayState(OVERLAY)
            .setCullState(NO_CULL)
            .setDepthTestState(LEQUAL_DEPTH_TEST)
            .setWriteMaskState(COLOR_DEPTH_WRITE)
            .setLayeringState(offset)
            .createCompositeState(false)
      );
   }
}
