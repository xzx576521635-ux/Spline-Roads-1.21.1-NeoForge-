package com.sora.splineroads.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

@EventBusSubscriber(
   modid = "splineroads",
   bus = Bus.MOD,
   value = {Dist.CLIENT}
)
public final class RoadShaders {
   private static ShaderInstance worldSolid;
   private static ShaderInstance worldOpaque;
   private static ShaderInstance worldAsphalt;
   private static final Map<ResourceLocation, Boolean> opaqueTextures = new HashMap<>();

   static void texturesReloaded() {
      opaqueTextures.clear();
   }

   private static boolean isOpaque(ResourceLocation texture) {
      return opaqueTextures.computeIfAbsent(texture, key -> {
         try {
            Boolean var11;
            try (InputStream stream = Minecraft.getInstance().getResourceManager().open(key)) {
               NativeImage image;
               label106: {
                  image = NativeImage.read(stream);

                  Boolean var5;
                  try {
                     int y = 0;

                     label78:
                     while (true) {
                        if (y >= image.getHeight()) {
                           var11 = true;
                           break label106;
                        }

                        for (int x = 0; x < image.getWidth(); x++) {
                           if (image.getPixelRGBA(x, y) >>> 24 != 255) {
                              var5 = false;
                              break label78;
                           }
                        }

                        y++;
                     }
                  } catch (Throwable var8) {
                     if (image != null) {
                        try {
                           image.close();
                        } catch (Throwable var7) {
                           var8.addSuppressed(var7);
                        }
                     }

                     throw var8;
                  }

                  if (image != null) {
                     image.close();
                  }

                  return var5;
               }

               if (image != null) {
                  image.close();
               }
            }

            return var11;
         } catch (IOException var10) {
            return false;
         }
      });
   }

   @SubscribeEvent
   public static void register(RegisterShadersEvent event) throws IOException {
      event.registerShader(
         new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("splineroads", "road_world_solid"), DefaultVertexFormat.NEW_ENTITY
         ),
         s -> worldSolid = s
      );
      event.registerShader(
         new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("splineroads", "road_world_opaque"), DefaultVertexFormat.NEW_ENTITY
         ),
         s -> worldOpaque = s
      );
      event.registerShader(
         new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("splineroads", "road_world_asphalt"), DefaultVertexFormat.NEW_ENTITY
         ),
         s -> worldAsphalt = s
      );
   }

   public static ShaderInstance solid() {
      return worldSolid != null && !ShaderPackState.current().active() ? worldSolid : GameRenderer.getRendertypeEntityCutoutNoCullShader();
   }

   public static ShaderInstance opaque(ResourceLocation texture) {
      return !isOpaque(texture) ? solid() : opaque(texture.getPath().equals("textures/road/asphalt.png"));
   }

   public static ShaderInstance opaque(boolean asphalt) {
      ShaderInstance shader = asphalt ? worldAsphalt : worldOpaque;
      return shader != null && !ShaderPackState.current().active() ? shader : solid();
   }

   private RoadShaders() {
   }
}
