package com.sora.splineroads.client;

import com.mojang.logging.LogUtils;
import com.sora.splineroads.net.RoadNetwork;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.client.event.InputEvent.Key;
import net.neoforged.neoforge.client.event.ScreenEvent.Opening;

@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT}
)
public final class ManualPlannerClient {
   static ManualPlannerScreen map;
   private static Screen resume;
   private static Screen lastChild;
   private static boolean world;
   private static ResourceKey<Level> dimension;
   private static long openingAt;
   private static UUID openingRequest;
   private static Screen mapPreviewEditor;
   private static final Set<Screen> children = Collections.newSetFromMap(new WeakHashMap<>());
   static final KeyMapping KEY = new KeyMapping("key.splineroads.manual_planner", 80, "key.categories.splineroads");

   static boolean hasMap() {
      return map != null;
   }

   static boolean previewing(Object owner) {
      return map != null && Minecraft.getInstance().screen == map && mapPreviewEditor == owner;
   }

   static boolean hasMapPreview() {
      return mapPreviewEditor != null;
   }

   static void mapPreview(Screen editor) {
      if (map != null) {
         mapPreviewEditor = editor;
         resume = null;
         world = false;
         lastChild = null;
         Minecraft.getInstance().setScreen(map);
         map.refresh();
      }
   }

   static void resumeMapPreview() {
      if (mapPreviewEditor != null) {
         Screen editor = mapPreviewEditor;
         mapPreviewEditor = null;
         ClientRoads.beginEditor(editor);
         Minecraft.getInstance().setScreen(editor);
      }
   }

   static void abandonMapPreview() {
      if (mapPreviewEditor != null) {
         mapPreviewEditor = null;
         ClientRoads.preview = null;
         ClientRoads.nodePreviews = List.of();
         ClientRoads.conflictPreview = false;
      }
   }

   @SubscribeEvent
   public static void input(Key e) {
      Minecraft mc = Minecraft.getInstance();
      if (e.getAction() == 1 && mc.screen == null && KEY.matches(e.getKey(), e.getScanCode())) {
         while (mc.options.keySocialInteractions.consumeClick()) {
         }
      }
   }

   @SubscribeEvent
   public static void tick(Post e) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null) {
         clear();
         dimension = null;
      } else {
         if (dimension != null && !dimension.equals(mc.level.dimension())) {
            clear();
            if (mc.screen instanceof ManualPlannerScreen) {
               mc.setScreen(null);
            }
         }

         dimension = mc.level.dimension();
         long now = Util.getMillis();
         if (openingAt != 0L && now - openingAt > 30000L || map != null && map.snapshotExpired(now)) {
            deliveryFailure("等待服务器超时，请关闭地图后重试");
         }

         if (mc.screen != null) {
            lastChild = children.contains(mc.screen) ? mc.screen : null;
         } else if (lastChild != null) {
            lastChild = null;
            if (world) {
               world = false;
            } else if (map != null) {
               mc.setScreen(map);
               ClientRoads.beginEditor(map);
               map.refresh();
            }
         }

         while (KEY.consumeClick()) {
            if (mc.screen == null) {
               if (resume != null) {
                  Screen next = resume;
                  resume = null;
                  ClientRoads.beginEditor(next);
                  mc.setScreen(next);
               } else if (openingRequest == null) {
                  CompoundTag t = new CompoundTag();
                  t.putString("Action", "manualOpen");
                  openingRequest = UUID.randomUUID();
                  t.putUUID("OpenRequest", openingRequest);
                  openingAt = now;
                  RoadNetwork.sendToServer(new RoadNetwork.Action(t));
               }
            }
         }
      }
   }

   @SubscribeEvent
   public static void opening(Opening e) {
      if (e.getNewScreen() != null) {
         cancelOpening();
      }

      if (map != null) {
         Screen old = Minecraft.getInstance().screen;
         if (e.getNewScreen() == null && children.contains(old)) {
            if (world) {
               world = false;
               return;
            }

            e.setNewScreen(map);
            ClientRoads.beginEditor(map);
            map.refresh();
         } else if (e.getNewScreen() != null && e.getNewScreen() != map && children.contains(old)) {
            children.add(e.getNewScreen());
         }
      }
   }

   static void child(Screen screen) {
      children.add(screen);
      lastChild = screen;
   }

   static void completed(Screen screen) {
      if (resume == screen) {
         resume = null;
      }

      if (mapPreviewEditor == screen) {
         mapPreviewEditor = null;
      }

      if (Minecraft.getInstance().screen != screen) {
         if (lastChild == screen) {
            lastChild = null;
         }

         children.remove(screen);
      }
   }

   private static void cancelOpening() {
      if (openingRequest != null && Minecraft.getInstance().getConnection() != null) {
         CompoundTag t = new CompoundTag();
         t.putString("Action", "manualCancelOpen");
         t.putUUID("OpenRequest", openingRequest);
         RoadNetwork.sendToServer(new RoadNetwork.Action(t));
      }

      openingRequest = null;
      openingAt = 0L;
   }

   static void clear() {
      cancelOpening();
      abandonMapPreview();
      if (map != null) {
         map.release();
      }

      map = null;
      resume = null;
      lastChild = null;
      world = false;
      children.clear();
   }

   static void deliveryFailure(String message) {
      if (openingRequest != null || map != null && map.loading()) {
         cancelOpening();
         if (map != null) {
            map.failSnapshot(message);
         }

         Minecraft mc = Minecraft.getInstance();
         if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("规划地图读取失败：" + message), false);
         }

         LogUtils.getLogger().warn("Manual planner delivery failed: {}", message);
      }
   }

   static boolean openResult(CompoundTag t) {
      if (!t.hasUUID("OpenRequest")) {
         return false;
      } else {
         if (t.getUUID("OpenRequest").equals(openingRequest) && !t.getBoolean("Success")) {
            cancelOpening();
         }

         return true;
      }
   }

   static void opened(CompoundTag t) {
      if (map != null && t.hasUUID("PlannerSession") && map.session.equals(t.getUUID("PlannerSession"))) {
         Screen screen = Minecraft.getInstance().screen;
         if (screen != null && screen != map) {
            child(screen);
         }
      }
   }

   static void worldPreview() {
      Screen screen = Minecraft.getInstance().screen;
      if (map != null && children.contains(screen)) {
         resume = screen;
         world = true;
      } else if (screen instanceof ElevatedAccessScreen) {
         resume = screen;
      }
   }

   static void decorate(CompoundTag t) {
      Screen screen = Minecraft.getInstance().screen;
      if (map != null && (screen == map || children.contains(screen) || t.hasUUID("PlannerSession"))) {
         t.putUUID("PlannerSession", map.session);
      }
   }

   static boolean receive(CompoundTag t) {
      String kind = t.getString("Kind");
      if (kind.equals("manualTerrain")) {
         if (map != null && t.hasUUID("PlannerSession") && map.session.equals(t.getUUID("PlannerSession"))) {
            map.terrain(t);
         }

         return true;
      } else if (kind.equals("manualMapPage")) {
         if (map != null && t.hasUUID("PlannerSession") && map.session.equals(t.getUUID("PlannerSession"))) {
            map.page(t);
         }

         return true;
      } else if (!kind.equals("manualPlanner") && !kind.equals("manualState")) {
         return false;
      } else {
         if (kind.equals("manualPlanner")) {
            if (openingRequest == null || !t.hasUUID("OpenRequest") || !openingRequest.equals(t.getUUID("OpenRequest"))) {
               return true;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.screen != null) {
               cancelOpening();
               return true;
            }

            openingRequest = null;
            openingAt = 0L;
            if (map == null || !map.session.equals(t.getUUID("PlannerSession"))) {
               clear();
               map = new ManualPlannerScreen(t);
            }

            map.state(t);
            if (t.hasUUID("MapSnapshot")) {
               map.beginSnapshot(t);
            }

            mc.setScreen(map);
            ClientRoads.beginEditor(map);
         } else if (map != null && map.session.equals(t.getUUID("PlannerSession"))) {
            map.state(t);
            if (t.hasUUID("MapSnapshot")) {
               map.beginSnapshot(t);
            }
         }

         return true;
      }
   }

   static void close() {
      if (map != null) {
         CompoundTag t = new CompoundTag();
         t.putString("Action", "manualClose");
         t.putUUID("PlannerSession", map.session);
         RoadNetwork.sendToServer(new RoadNetwork.Action(t));
         clear();
         Minecraft.getInstance().setScreen(null);
      }
   }

   private ManualPlannerClient() {
   }

   @EventBusSubscriber(
      modid = "splineroads",
      value = {Dist.CLIENT},
      bus = Bus.MOD
   )
   public static final class Setup {
      @SubscribeEvent
      public static void keys(RegisterKeyMappingsEvent e) {
         e.register(ManualPlannerClient.KEY);
      }
   }
}
