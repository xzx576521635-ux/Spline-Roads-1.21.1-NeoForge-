package com.sora.splineroads.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.sora.splineroads.config.RoadClientConfig;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT}
)
public final class RoadClientCommands {
   @SubscribeEvent
   public static void register(RegisterClientCommandsEvent event) {
      LiteralArgumentBuilder<CommandSourceStack> perf = (LiteralArgumentBuilder<CommandSourceStack>)Commands.literal("perf")
         .executes(c -> perf((CommandSourceStack)c.getSource(), !(Boolean)RoadClientConfig.PERF_OVERLAY.get()));
      perf.then(Commands.literal("on").executes(c -> perf((CommandSourceStack)c.getSource(), true)));
      perf.then(Commands.literal("off").executes(c -> perf((CommandSourceStack)c.getSource(), false)));
      event.getDispatcher()
         .register(
            (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("sr").then(perf))
                  .then(
                     ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("signs")
                              .executes(c -> signs((CommandSourceStack)c.getSource(), !RoadSignVisibility.enabled())))
                           .then(Commands.literal("on").executes(c -> signs((CommandSourceStack)c.getSource(), true))))
                        .then(Commands.literal("off").executes(c -> signs((CommandSourceStack)c.getSource(), false)))
                  ))
               .then(
                  ((LiteralArgumentBuilder)Commands.literal("sf")
                        .executes(
                           c -> {
                              ((CommandSourceStack)c.getSource())
                                 .sendSuccess(() -> Component.literal("SR 表面模式：" + RoadClientConfig.SURFACE_BACKEND.get() + "；/sr sf auto|VBO|terrain"), false);
                              return 1;
                           }
                        ))
                     .then(
                        Commands.argument("mode", StringArgumentType.word())
                           .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("auto", "VBO", "terrain"), b))
                           .executes(c -> {
                              String name = StringArgumentType.getString(c, "mode").toUpperCase(Locale.ROOT);
                              if (name.equals("TERRIAN")) {
                                 name = "TERRAIN";
                              }

                              RoadClientConfig.SurfaceBackend mode;
                              try {
                                 mode = RoadClientConfig.SurfaceBackend.valueOf(name);
                              } catch (IllegalArgumentException var4) {
                                 throw new SimpleCommandExceptionType(Component.literal("mode 可选：auto、VBO、terrain")).create();
                              }

                              RoadClientConfig.SURFACE_BACKEND.set(mode);
                              RoadTerrainModels.beginFrame();
                              ((CommandSourceStack)c.getSource()).sendSuccess(() -> Component.literal("SR 表面模式已切换为 " + mode), false);
                              return 1;
                           })
                     )
               )
         );
   }

   private static int signs(CommandSourceStack source, boolean enabled) {
      RoadSignVisibility.set(enabled);
      source.sendSuccess(() -> Component.literal("SR 全部标牌已" + (enabled ? "显示" : "隐藏（性能排查）") + "；/sr signs on|off"), false);
      return 1;
   }

   private static int perf(CommandSourceStack source, boolean enabled) {
      RoadClientConfig.PERF_OVERLAY.set(enabled);
      source.sendSuccess(() -> Component.literal("SR 性能显示已" + (enabled ? "开启" : "关闭")), false);
      return 1;
   }

   private RoadClientCommands() {
   }
}
