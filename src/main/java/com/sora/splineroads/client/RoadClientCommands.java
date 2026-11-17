package com.sora.splineroads.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client-only maintenance commands.
 *
 * <p>{@code /srclient resync} drops the road render caches and asks the server for a full road
 * sync. Useful when a section of road lost its rendering and chunk reloads do not help, without
 * having to leave and re-enter the world.</p>
 */
@EventBusSubscriber(modid = "splineroads", value = Dist.CLIENT)
public final class RoadClientCommands {
   private RoadClientCommands() {
   }

   @SubscribeEvent
   public static void register(RegisterClientCommandsEvent event) {
      LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("srclient")
         .then(
            Commands.literal("resync")
               .executes(
                  context -> {
                     RoadRenderer.reset();
                     RoadNetwork.requestRoadSync();
                     context.getSource()
                        .sendSuccess(() -> Component.literal("Spline Roads：已重置渲染缓存并请求重新同步道路数据"), false);
                     return 1;
                  }
               )
         )
         .then(
            Commands.literal("sections")
               .executes(
                  context -> {
                     net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                     if (mc.player == null) {
                        context.getSource().sendFailure(Component.literal("Spline Roads：当前没有客户端玩家"));
                        return 0;
                     }

                     var camera = mc.player.getEyePosition();
                     var lines = RoadRenderer.debugSections(camera.x, camera.y, camera.z);
                     if (lines.isEmpty()) {
                        context.getSource().sendSuccess(() -> Component.literal("Spline Roads：附近没有渲染 section"), false);
                     } else {
                        for (String line : lines) {
                           context.getSource().sendSuccess(() -> Component.literal(line), false);
                        }
                     }

                     return lines.size();
                  }
               )
         )
         .then(
            Commands.literal("uploads")
               .executes(
                  context -> {
                     var lines = RoadRenderer.uploaderReport();
                     for (String line : lines) {
                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                     }

                     return lines.size();
                  }
               )
         );
      event.getDispatcher().register(root);
   }
}
