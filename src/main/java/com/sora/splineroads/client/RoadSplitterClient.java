package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.world.RoadSplitGeometry;
import com.sora.splineroads.world.RoadSplitTool;
import com.sora.splineroads.world.RoadSplitter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;

@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT}
)
public final class RoadSplitterClient {
   private static RoadSplitter.Hit hit;
   private static UUID token;
   private static String status = "";
   private static long request;
   private static long stableSince;
   private static long sent;
   private static boolean wanted;
   private static Object world;
   private static List<RoadSplitGeometry.Range> ranges = List.of();
   private static List<RoadSplitGeometry.Range> cachedRanges = List.of();
   private static RoadRecord rangeRoad;
   private static RoadGeometry.Mesh ownPreview;

   private static boolean holding() {
      LocalPlayer p = Minecraft.getInstance().player;
      return p != null && (p.getMainHandItem().getItem() instanceof RoadSplitTool || p.getOffhandItem().getItem() instanceof RoadSplitTool);
   }

   public static void clear() {
      if (ownPreview != null && ClientRoads.preview == ownPreview) {
         ClientRoads.preview = null;
         ClientRoads.nodePreviews = List.of();
         ClientRoads.conflictPreview = false;
         ClientRoads.error = "";
      }

      ownPreview = null;
      hit = null;
      token = null;
      status = "";
      wanted = false;
      sent = 0L;
      request++;
      ranges = List.of();
   }

   public static void activate() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         if (mc.player.isShiftKeyDown()) {
            clear();
            CompoundTag t = new CompoundTag();
            t.putString("Action", "splitCancel");
            send(t);
            mc.player.displayClientMessage(Component.literal("已取消分割"), true);
         } else if (hit == null) {
            mc.setScreen(new RoadSplitScreen());
         } else if (wanted) {
            if (token != null) {
               commit();
            }
         } else {
            ClientRoads.beginWorldEdit();
            wanted = true;
            preview();
         }
      }
   }

   private static void send(CompoundTag tag) {
      ClientRoads.sendAction(tag);
   }

   private static void preview() {
      if (hit != null) {
         CompoundTag t = new CompoundTag();
         t.putString("Action", "splitPreview");
         t.putUUID("Id", hit.road().record.id());
         t.putInt("Signature", hit.road().record.header().hashCode());
         t.putDouble("Station", hit.station());
         t.putString("Mode", ((RoadSlicing.Connection)RoadClientConfig.SPLIT_CONNECTION.get()).name());
         t.putLong("Request", ++request);
         sent = System.nanoTime();
         status = "正在复核分割位置…";
         send(t);
      }
   }

   public static void checked(CompoundTag reply) {
      Minecraft mc = Minecraft.getInstance();
      if (reply.getLong("Request") == request && mc.level != null && mc.level.dimension().location().toString().equals(reply.getString("Dimension"))) {
         if (reply.contains("Error")) {
            status = reply.getString("Error");
            wanted = false;
            token = null;
         } else {
            token = reply.getUUID("Token");
            List<RoadGeometry.Mesh> paths = reply.getList("PreviewRoads", 10).stream().map(t -> RoadRecord.load((CompoundTag)t).mesh()).toList();
            if (!paths.isEmpty()) {
               ClientRoads.nodePreviews = paths;
               ownPreview = paths.get(0);
               ClientRoads.preview = ownPreview;
               ClientRoads.conflictPreview = false;
               ClientRoads.error = "";
            }

            status = "位置已锁定 · 再次右键分割 · Shift＋右键取消";
         }
      }
   }

   private static void commit() {
      if (token != null) {
         CompoundTag t = new CompoundTag();
         t.putString("Action", "splitCommit");
         t.putUUID("Token", token);
         send(t);
         clear();
         status = "正在提交分割…";
      }
   }

   @SubscribeEvent
   public static void tick(Post event) {
      Minecraft mc = Minecraft.getInstance();
      if (world != mc.level) {
         clear();
         rangeRoad = null;
         cachedRanges = List.of();
         world = mc.level;
      }

      if (holding() && mc.screen == null && mc.level != null) {
         if (!wanted) {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 look = mc.player.getLookAngle();
            RoadGeometry.V origin = new RoadGeometry.V(eye.x, eye.y, eye.z);
            RoadGeometry.V direction = new RoadGeometry.V(look.x, look.y, look.z);
            RoadSplitter.Hit next = RoadSplitter.hit(
               ClientRoads.INDEX.rayCandidates(origin, direction, 64.0).stream().filter(r -> ClientRoads.active(r.record.id())).toList(),
               origin,
               direction,
               64.0
            );
            boolean same = next != null && hit != null && next.road().record == hit.road().record && Math.abs(next.station() - hit.station()) < 0.005;
            if (!same) {
               clear();
               hit = next;
               stableSince = System.nanoTime();
               if (hit != null) {
                  if (rangeRoad != hit.road().record) {
                     rangeRoad = hit.road().record;
                     cachedRanges = RoadSplitGeometry.protection(rangeRoad);
                  }

                  ranges = cachedRanges;

                  try {
                     RoadSplitGeometry.requireClear(ranges, hit.station(), hit.station());
                     status = "右键锁定此位置";
                  } catch (IllegalArgumentException var9) {
                     status = var9.getMessage();
                  }
               }
            }
         }
      } else {
         if (hit != null) {
            clear();
         }
      }
   }

   @SubscribeEvent
   public static void overlay(net.neoforged.neoforge.client.event.RenderGuiLayerEvent.Post event) {
      Minecraft mc = Minecraft.getInstance();
      if (holding() && hit != null && mc.screen == null && event.getName().toString().equals("minecraft:crosshair")) {
         GuiGraphics g = event.getGuiGraphics();
         int x = 12;
         int y = mc.getWindow().getGuiScaledHeight() - 62;
         String label = RoadClientConfig.SPLIT_CONNECTION.get() == RoadSlicing.Connection.CONTINUOUS ? "连续" : "路口";
         g.drawString(
            mc.font,
            String.format(
               Locale.ROOT,
               "分割 %s · %s · A–C %.2f / C–B %.2f 格",
               hit.road().record.id().toString().substring(0, 8),
               label,
               hit.station(),
               hit.road().mesh.length() - hit.station()
            ),
            x,
            y,
            16777215,
            true
         );
         g.drawWordWrap(mc.font, Component.literal(status), x, y + 15, mc.getWindow().getGuiScaledWidth() - 24, token == null ? 16763777 : 8585136);
      }
   }

   @SubscribeEvent
   public static void render(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_BLOCK_ENTITIES && hit != null && holding()) {
         Minecraft mc = Minecraft.getInstance();
         PoseStack pose = event.getPoseStack();
         Vec3 camera = event.getCamera().getPosition();
         pose.pushPose();
         pose.translate(-camera.x, -camera.y, -camera.z);
         BufferSource buffers = mc.renderBuffers().bufferSource();
         VertexConsumer lines = buffers.getBuffer(RenderType.lines());
         RoadGeometry.Mesh mesh = hit.road().mesh;
         RoadGeometry.Sample at = RoadSlicing.at(mesh, hit.station());
         line(pose, lines, at.at(-at.halfWidth(), -0.08), at.at(at.halfWidth(), -0.08), token == null ? 1.0F : 0.2F, token == null ? 0.35F : 1.0F, 0.2F);
         RoadGeometry.V node = RoadSplitGeometry.node(mesh, hit.station()).position();
         LevelRenderer.renderLineBox(
            pose, lines, node.x() - 0.15, node.y() + 0.03, node.z() - 0.15, node.x() + 0.15, node.y() + 0.5, node.z() + 0.15, 0.2F, 1.0F, 0.3F, 1.0F
         );

         for (RoadSplitGeometry.Range range : ranges) {
            if (range.intersects(hit.station(), hit.station())) {
               for (double d : new double[]{Math.max(0.0, range.from()), Math.min(mesh.length(), range.to())}) {
                  RoadGeometry.Sample end = RoadSlicing.at(mesh, d);
                  line(pose, lines, end.at(-end.halfWidth(), -0.1), end.at(end.halfWidth(), -0.1), 1.0F, 0.1F, 0.1F);
               }
            }
         }

         pose.popPose();
         buffers.endBatch(RenderType.lines());
      }
   }

   private static void line(PoseStack pose, VertexConsumer out, RoadGeometry.V a, RoadGeometry.V b, float r, float g, float blue) {
      RoadGeometry.V normal = b.sub(a).horizontalUnit();

      for (RoadGeometry.V p : List.of(a, b)) {
         out.addVertex(pose.last().pose(), (float)p.x(), (float)p.y(), (float)p.z())
            .setColor(r, g, blue, 1.0F)
            .setNormal(pose.last(), (float)normal.x(), (float)normal.y(), (float)normal.z());
      }
   }

   private RoadSplitterClient() {
   }
}
