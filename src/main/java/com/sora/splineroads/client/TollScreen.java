package com.sora.splineroads.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.core.TollPlanner;
import com.sora.splineroads.core.TollPlaza;
import com.sora.splineroads.world.TollCodec;
import com.sora.splineroads.world.TollInputs;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

public final class TollScreen extends Screen {
   private static CompoundTag draft;
   private static TollScreen waiting;
   private static long requests;
   private final CompoundTag payload;
   private final List<Runnable> captures = new ArrayList<>();
   private final List<TollScreen.Label> labels = new ArrayList<>();
   private int page;
   private int left;
   private int top;
   private int panel;
   private int previewDelay = 8;
   private double uiScale = 1.0;
   private List<RoadSurface.Face> mapFaces = List.of();
   private boolean initializing;
   private boolean pending;
   private boolean awaitingPreview;
   private boolean deleteConfirm;
   private Button build;
   private Button preview;
   private Button calculate;
   private String status = "正在准备俯视预览…";
   private UUID token;
   private long request;
   private RoadPreviewData.Toll checked;
   private double mapMinX;
   private double mapMinZ;
   private double mapMaxX;
   private double mapMaxZ;

   public TollScreen(CompoundTag t) {
      super(Component.literal("Spline Roads · 收费站"));
      this.payload = t.copy();
      this.payload.remove("Token");
      if (!this.inline()) {
         TollCodec.standaloneMode(this.payload.getCompound("Spec"), this.payload.getCompound("Spec").getBoolean("TwoWay"));
      }

      this.request = ++requests;
   }

   public static void clear() {
      Minecraft mc = Minecraft.getInstance();
      TollScreen owner = waiting != null ? waiting : (mc.screen instanceof TollScreen screen ? screen : null);
      if (waiting != null && (waiting.token != null || waiting.pending)) {
         waiting.cancelPreview();
      }

      boolean owned = owner != null && ClientRoads.endEditor(owner);
      if (owner != null) {
         owner.pending = false;
         owner.awaitingPreview = false;
      }

      draft = null;
      waiting = null;
      requests++;
      if (owned) {
         clearPublished();
         if (mc.screen == owner) {
            mc.setScreen(null);
         }
      }
   }

   private static void clearPublished() {
      TollPreview.clear();
      JunctionScreen.previewMesh = null;
      JunctionScreen.previewFaces = List.of();
      ClientRoads.preview = null;
      ClientRoads.nodePreviews = List.of();
   }

   private static void resume(TollScreen screen) {
      ClientRoads.beginEditor(screen);
      screen.showWorld();
      Minecraft.getInstance().setScreen(screen);
   }

   private void showWorld() {
      if (ClientRoads.ownsPreview(this)) {
         ClientRoads.conflictPreview = false;
         if (this.checked == null) {
            clearPublished();
         } else {
            TollPreview.show(TollCodec.read(this.payload.getCompound("Spec")), this.checked.plan());
            ClientRoads.preview = this.checked.mesh();
            ClientRoads.nodePreviews = this.checked.roads();
            JunctionScreen.previewMesh = this.checked.mesh();
            JunctionScreen.previewFaces = this.checked.faces();
         }
      }
   }

   public static void open(CompoundTag fresh) {
      open(fresh, null);
   }

   public static void open(CompoundTag fresh, RoadPreviewData prepared) {
      String kind = fresh.getString("Kind");
      if (kind.equals("tollReset")) {
         TollScreen screen = waiting != null ? waiting : (Minecraft.getInstance().screen instanceof TollScreen current ? current : null);
         if (!fresh.contains("Request") || screen != null && screen.request == fresh.getLong("Request")) {
            clear();
         }
      } else if (kind.equals("tollCheck")) {
         if (waiting != null) {
            waiting.checked(fresh, prepared);
         }
      } else if (kind.equals("tollResume")) {
         if (waiting != null) {
            resume(waiting);
         } else if (draft != null) {
            resume(new TollScreen(draft));
         }
      } else if (waiting != null && !fresh.getBoolean("DeleteOnly") && sameSelection(fresh, waiting.payload)) {
         resume(waiting);
      } else {
         if (waiting != null) {
            waiting.cancelPreview();
            if (ClientRoads.ownsPreview(waiting)) {
               clearPublished();
            }

            waiting.request = ++requests;
            waiting = null;
         }

         CompoundTag t = fresh.copy();
         if (draft != null && !t.getBoolean("DeleteOnly") && sameSelection(t, draft)) {
            TollPlaza.Spec current = TollCodec.read(t.getCompound("Spec"));
            TollPlaza.Spec previous = TollCodec.read(draft.getCompound("Spec"));
            t.put("Spec", TollCodec.write(new TollPlaza.Spec(current.a(), current.b(), previous.config(), current.portA(), current.portB(), current.path())));
            t.putString("Adjust", draft.getString("Adjust"));
         }

         Minecraft.getInstance().setScreen(new TollScreen(t));
      }
   }

   private static boolean sameSelection(CompoundTag a, CompoundTag b) {
      return Objects.equals(a.get("InlineRoad"), b.get("InlineRoad"))
         && a.getDouble("InlineStation") == b.getDouble("InlineStation")
         && a.getInt("InlineSignature") == b.getInt("InlineSignature")
         && Arrays.equals(a.getLongArray("Points"), b.getLongArray("Points"))
         && Objects.equals(a.hasUUID("Id") ? a.getUUID("Id") : null, b.hasUUID("Id") ? b.getUUID("Id") : null);
   }

   private void remember() {
      if (!this.payload.getBoolean("DeleteOnly")) {
         draft = this.payload.copy();
      }
   }

   private void cancelPreview() {
      this.awaitingPreview = false;
      if (Minecraft.getInstance().getConnection() != null) {
         CompoundTag t = new CompoundTag();
         t.putString("Action", "tollCancelPreview");
         t.putLong("Request", this.request);
         if (this.token != null) {
            t.putUUID("Token", this.token);
         }

         ClientRoads.sendAction(t);
      }
   }

   private void changed() {
      if (!this.initializing && (!this.pending || this.awaitingPreview)) {
         ClientRoads.editorChanged(this);
         if (this.token != null || this.pending) {
            this.cancelPreview();
         }

         this.token = null;
         this.checked = null;
         this.pending = false;
         this.awaitingPreview = false;
         this.request = ++requests;
         this.status = "参数已改变，请点击计算更新俯视预览";
         this.previewDelay = 0;
         this.mapFaces = List.of();
         if (this.build != null) {
            this.build.active = false;
         }

         if (this.preview != null) {
            this.preview.active = false;
         }

         if (this.calculate != null) {
            this.calculate.active = true;
         }

         if (ClientRoads.ownsPreview(this)) {
            clearPublished();
         }
      }
   }

   private void watch(EditBox box) {
      String[] previous = new String[]{box.getValue()};
      box.setResponder(value -> {
         if (!value.equals(previous[0])) {
            previous[0] = value;
            this.changed();
         }
      });
   }

   private boolean capture() {
      if (this.pending && !this.awaitingPreview) {
         return true;
      } else {
         try {
            this.captures.forEach(Runnable::run);
            TollCodec.read(this.payload.getCompound("Spec"));
            this.remember();
            return true;
         } catch (IllegalArgumentException var2) {
            this.status = var2.getMessage();
            this.token = null;
            return false;
         }
      }
   }

   private boolean inline() {
      return this.payload.hasUUID("InlineRoad") || this.payload.getCompound("Spec").contains("Path");
   }

   private Button button(String label, int x, int y, int w, Runnable action) {
      return (Button)this.addRenderableWidget(
         Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, Math.max(14, (int)Math.round(20.0 * this.uiScale))).build()
      );
   }

   private void change(Runnable action) {
      if (!this.pending || this.awaitingPreview) {
         if (this.capture()) {
            action.run();
            this.changed();
            this.remember();
            this.rebuildWidgets();
         }
      }
   }

   private void field(String name, CompoundTag t, String key, int col, int row, boolean integer) {
      int w = (this.panel - 30) / 2;
      int x = this.left + 10 + col * (w + 10);
      int y = this.row(56 + row * 38);
      this.labels.add(new TollScreen.Label(name, x, y));
      EditBox box = new EditBox(this.font, x, y + 10, w, Math.max(12, (int)Math.round(18.0 * this.uiScale)), Component.literal(name));
      box.setMaxLength(8);
      box.setValue(integer ? Integer.toString(t.getInt(key)) : String.format(Locale.ROOT, "%.2f", t.getDouble(key)));
      this.watch(box);
      this.addRenderableWidget(box);
      if (this.inline() && Set.of("A", "B", "LaneWidth").contains(key)) {
         box.setEditable(false);
      }

      this.captures.add(() -> {
         try {
            if (integer) {
               t.putInt(key, Integer.parseInt(box.getValue()));
            } else {
               t.putDouble(key, Double.parseDouble(box.getValue()));
            }
         } catch (NumberFormatException var6x) {
            throw new IllegalArgumentException(name + "：请输入有效数字");
         }
      });
   }

   protected void init() {
      this.initializing = true;
      this.captures.clear();
      this.labels.clear();
      this.panel = Math.min(330, (this.width - 48) / 2);
      this.left = 12;
      this.top = 8;
      this.uiScale = Math.min(1.0, (double)(this.height - 20) / 340.0);
      int half = (this.panel - 30) / 2;
      int x = this.left + 10;
      String[] tabs = new String[]{
         "模式/端点",
         this.payload.getCompound("Spec").getBoolean("TwoWay") ? "A→B 参数" : "单向参数",
         this.payload.getCompound("Spec").getBoolean("TwoWay") ? "B→A 参数" : "双向预设",
         "尺寸"
      };

      for (int i = 0; i < 4; i++) {
         final int index = i;
         this.button((this.page == i ? "● " : "") + tabs[i], x + i * (this.panel - 20) / 4, this.row(26), (this.panel - 26) / 4, () -> {
            if (this.capture()) {
               this.page = index;
               this.rebuildWidgets();
            }
         });
      }

      CompoundTag s = this.payload.getCompound("Spec");
      if (this.page == 0) {
         this.button(
               "模式：" + (s.getBoolean("TwoWay") ? "双向" : "单向"),
               x,
               this.row(60),
               half,
               () -> this.change(() -> TollCodec.standaloneMode(s, !s.getBoolean("TwoWay")))
            )
            .active = !this.inline();
         Button reverse = this.button(
            "单向：" + (s.getBoolean("Reverse") ? "B→A" : "A→B"),
            x + half + 10,
            this.row(60),
            half,
            () -> this.change(() -> s.putBoolean("Reverse", !s.getBoolean("Reverse")))
         );
         reverse.active = !s.getBoolean("TwoWay") && !this.inline();
         this.button(
               s.getBoolean("LeftTraffic") ? "左侧通行" : "右侧通行",
               x,
               this.row(86),
               this.panel - 20,
               () -> this.change(() -> s.putBoolean("LeftTraffic", !s.getBoolean("LeftTraffic")))
            )
            .active = !this.inline();
         this.button(
               this.inline() ? "原路断面接口固定" : "调整：" + adjustLabel(this.payload.getString("Adjust")), x, this.row(112), this.panel - 20, () -> this.change(() -> {
                     TollPlaza.Adjust[] values = TollPlaza.Adjust.values();
                     this.payload
                        .putString("Adjust", values[(TollPlaza.Adjust.valueOf(this.payload.getString("Adjust")).ordinal() + 1) % values.length].name());
                  })
            )
            .active = !this.inline();
         this.labels.add(new TollScreen.Label("收费站名称（选填）", x, this.row(138)));
         EditBox name = new EditBox(
            this.font, x, this.row(150), this.panel - 20, Math.max(14, (int)Math.round(20.0 * this.uiScale)), Component.literal("收费站名称（选填，默认空）")
         );
         name.setMaxLength(80);
         name.setValue(s.getString("Name"));
         this.watch(name);
         this.addRenderableWidget(name);
         this.captures.add(() -> s.putString("Name", name.getValue()));
      } else if (this.page != 1 && this.page != 2) {
         this.field("接口车道宽 2–5", s, "LaneWidth", 0, 0, false);
         this.field("收费通道净宽 4–7", s, "PassageWidth", 1, 0, false);
         this.field("最低净高 6–10", s, "ClearHeight", 0, 1, false);
         this.button("结构：" + s.getString("Structure"), x + half + 10, this.row(101), half, () -> this.change(() -> {
               String var1x = s.getString("Structure");

               s.putString("Structure", switch (var1x) {
                  case "AUTO" -> "GROUND";
                  case "GROUND" -> "BRIDGE";
                  default -> "AUTO";
               });
            }));
      } else {
         CompoundTag d = s.getCompound(this.page == 1 ? "Forward" : "Backward");
         this.field("A 端车道数 1–7", d, "A", 0, 0, true);
         this.field("B 端车道数 1–7", d, "B", 1, 0, true);
         this.field("ETC 通道（合计 1–7）", d, "ETC", 0, 1, true);
         this.field("人工通道（合计 1–7）", d, "Manual", 1, 1, true);
         this.labels.add(new TollScreen.Label(this.page == 2 && !s.getBoolean("TwoWay") ? "单向只用第一组数量；反转保持 A/B 配置归属" : "每个单元对应一条收费通道；两向分别保存", x, this.row(140)));
      }

      int foot = this.height - 54;
      int third = (this.panel - 28) / 3;
      this.calculate = this.button("计算", x, foot, third, () -> this.requestPreview());
      this.calculate.active = !this.pending && !this.payload.getBoolean("DeleteOnly");
      this.preview = this.button("实景预览", x + third + 4, foot, third, () -> this.preview());
      this.preview.active = this.token != null && this.checked != null && !this.pending;
      this.build = this.button(this.payload.hasUUID("Id") ? "更新收费站" : "建造收费站", x + 2 * (third + 4), foot, third, () -> this.submit("tollBuild"));
      this.build.active = this.token != null && this.checked != null && !this.pending;
      this.button("关闭", x, this.height - 30, third, () -> this.onClose());
      if (this.payload.hasUUID("Id")) {
         this.button(this.deleteConfirm ? "确认删除收费站" : "删除收费站", x + third + 4, this.height - 30, 2 * third + 4, () -> {
            if (!this.deleteConfirm) {
               this.deleteConfirm = true;
               this.rebuildWidgets();
            } else {
               this.submit("tollDelete");
            }
         });
      }

      if (this.payload.getBoolean("DeleteOnly")) {
         this.preview.active = false;
         this.build.active = false;
      }

      this.initializing = false;
   }

   private static String adjustLabel(String s) {
      return switch (s) {
         case "AB" -> "AB 两端等量延长";
         case "A" -> "仅移动 A";
         case "B" -> "仅移动 B";
         default -> "固定 A/B";
      };
   }

   private int row(int offset) {
      return this.top + (int)Math.round((double)offset * this.uiScale);
   }

   public void tick() {
      if (this.previewDelay > 0 && --this.previewDelay == 0 && !this.payload.getBoolean("DeleteOnly")) {
         this.requestPreview();
      }
   }

   private void preview() {
      if (!this.pending && this.checked != null && this.token != null) {
         this.showWorld();
         ManualPlannerClient.worldPreview();
         Minecraft.getInstance().setScreen(null);
      }
   }

   private void requestPreview() {
      if (!this.pending && this.capture()) {
         this.previewDelay = 0;
         ClientRoads.editorChanged(this);

         try {
            this.token = null;
            this.pending = true;
            this.awaitingPreview = true;
            waiting = this;
            this.request = ++requests;
            this.status = "正在计算并校验地形、端点与外接道路…";
            this.build.active = false;
            this.preview.active = false;
            this.calculate.active = false;
            CompoundTag t = TollInputs.command(this.payload);
            t.putString("Action", "tollPreview");
            t.putLong("Request", this.request);
            ClientRoads.sendAction(t);
         } catch (IllegalArgumentException var2) {
            this.pending = false;
            this.awaitingPreview = false;
            this.status = var2.getMessage();
            this.token = null;
            this.build.active = false;
            this.calculate.active = true;
         }
      }
   }

   private static List<RoadSurface.Face> faces(TollPlanner.Plan p) {
      ArrayList<RoadSurface.Face> out = new ArrayList<>(p.paint());

      for (RoadStructures.Part part : p.structures()) {
         out.addAll(part.faces());
      }

      return List.copyOf(out);
   }

   private void checked(CompoundTag t, RoadPreviewData prepared) {
      if (t.getLong("Request") == this.request && this.awaitingPreview) {
         this.pending = false;
         this.awaitingPreview = false;
         if (this.calculate != null) {
            this.calculate.active = true;
         }

         if (t.contains("Error")) {
            if (this.preview != null) {
               this.preview.active = false;
            }

            this.status = t.getString("Error");
            this.token = null;
            this.checked = null;
            if (this.build != null) {
               this.build.active = false;
            }

            if (ClientRoads.ownsPreview(this)) {
               clearPublished();
               Minecraft.getInstance().setScreen(this);
            }
         } else {
            CompoundTag result = t.getCompound("Inputs");
            this.token = result.getUUID("Token");
            this.checked = prepared == null ? RoadPreviewData.preparePayload(t).toll() : prepared.toll();
            this.prepareMap();
            this.showWorld();
            if (this.preview != null) {
               this.preview.setMessage(Component.literal("实景预览"));
            }

            this.status = String.format(
               Locale.ROOT, "可建造 · A 移动 %.1f / B 移动 %.1f 格 · 联动外接道路 %d 条", result.getDouble("MovedA"), result.getDouble("MovedB"), result.getInt("Affected")
            );
            if (this.build != null) {
               this.build.active = true;
            }

            if (this.preview != null) {
               this.preview.active = true;
            }

            this.remember();
            if (ClientRoads.ownsPreview(this) && Minecraft.getInstance().screen == null && Minecraft.getInstance().player != null) {
               Minecraft.getInstance().player.displayClientMessage(Component.literal("收费站预览已就绪，可返回设置建造"), true);
            }
         }
      }
   }

   private void submit(String action) {
      if (!this.pending && this.capture()) {
         if (!action.equals("tollBuild") || this.token != null && this.checked != null) {
            CompoundTag t = TollInputs.command(this.payload);
            t.putString("Action", action);
            if (this.token != null) {
               t.putUUID("Token", this.token);
            }

            this.pending = true;
            this.awaitingPreview = false;
            waiting = this;
            this.build.active = false;
            this.preview.active = false;
            this.calculate.active = false;
            ClientRoads.sendAction(t);
         }
      }
   }

   public static void result(TollScreen owner, boolean success, String message) {
      if (waiting == owner && owner.pending) {
         TollScreen screen = waiting;
         screen.pending = false;
         screen.awaitingPreview = false;
         screen.status = message;
         if (success) {
            ManualPlannerClient.completed(owner);
            clear();
         } else {
            screen.token = null;
            if (screen.build != null) {
               screen.build.active = false;
            }

            if (screen.preview != null) {
               screen.preview.active = false;
            }

            if (screen.calculate != null) {
               screen.calculate.active = true;
            }
         }
      }
   }

   static void deliveryRecovery() {
      if (waiting != null && waiting.pending && waiting.awaitingPreview) {
         TollScreen screen = waiting;
         screen.cancelPreview();
         screen.pending = false;
         screen.awaitingPreview = false;
         screen.checked = null;
         screen.request = ++requests;
         screen.status = "预览传输繁忙，请重新预览";
         if (screen.preview != null) {
            screen.preview.active = false;
         }

         if (screen.calculate != null) {
            screen.calculate.active = true;
         }

         if (screen.build != null) {
            screen.build.active = false;
         }

         if (ClientRoads.ownsPreview(screen)) {
            clearPublished();
         }
      }
   }

   public void onClose() {
      this.capture();
      Minecraft.getInstance().setScreen(null);
   }

   public boolean isPauseScreen() {
      return false;
   }

   private void prepareMap() {
      this.mapMinX = this.mapMinZ = Double.POSITIVE_INFINITY;
      this.mapMaxX = this.mapMaxZ = Double.NEGATIVE_INFINITY;

      for (RoadGeometry.Sample sample : this.checked.mesh().samples()) {
         for (double side : new double[]{-1.0, 1.0}) {
            RoadGeometry.V p = sample.at(side * sample.halfWidth(), 0.0);
            this.mapMinX = Math.min(this.mapMinX, p.x());
            this.mapMinZ = Math.min(this.mapMinZ, p.z());
            this.mapMaxX = Math.max(this.mapMaxX, p.x());
            this.mapMaxZ = Math.max(this.mapMaxZ, p.z());
         }
      }

      ArrayList<RoadSurface.Face> faces = new ArrayList<>();
      ArrayList<RoadGeometry.Mesh> roads = new ArrayList<>(this.checked.roads());
      if (!roads.contains(this.checked.mesh())) {
         roads.add(this.checked.mesh());
      }

      for (RoadGeometry.Mesh road : roads) {
         for (int i = 1; i < road.samples().size(); i++) {
            RoadGeometry.Sample a = road.samples().get(i - 1);
            RoadGeometry.Sample b = road.samples().get(i);
            faces.add(
               new RoadSurface.Face(List.of(a.at(a.halfWidth(), 0.0), a.at(-a.halfWidth(), 0.0), b.at(-b.halfWidth(), 0.0), b.at(b.halfWidth(), 0.0)), 6518917)
            );
         }
      }

      faces.addAll(this.checked.faces());
      faces.sort(Comparator.comparingDouble(f -> f.points().stream().mapToDouble(RoadGeometry.V::y).average().orElse(0.0)));
      this.mapFaces = List.copyOf(faces);
   }

   private void map(GuiGraphics g) {
      int x = this.left + this.panel + 12;
      int y = this.top + 26;
      int w = this.width - x - 12;
      int h = this.height - y - 12;
      g.fill(x, y, x + w, y + h, -16181729);
      g.drawString(this.font, "北 ↑", x + w - 36, y + 8, 16777215, false);
      g.drawString(this.font, this.checked != null ? "俯视预览" : (this.pending ? "计算与校验中…" : "等待有效参数"), x + 8, y + 8, 10409958, false);
      if (this.checked != null) {
         RoadGeometry.Mesh mesh = this.checked.mesh();
         double dx = Math.max(1.0, this.mapMaxX - this.mapMinX);
         double dz = Math.max(1.0, this.mapMaxZ - this.mapMinZ);
         double scale = Math.min((double)(w - 24) / dx, (double)(h - 50) / dz);
         double ox = (double)x + ((double)w - dx * scale) / 2.0;
         double oz = (double)(y + 28) + ((double)(h - 50) - dz * scale) / 2.0;
         g.enableScissor(x + 2, y + 23, x + w - 2, y + h - 2);
         g.flush();
         RenderSystem.disableCull();
         RenderSystem.enableBlend();
         RenderSystem.defaultBlendFunc();
         RenderSystem.setShader(GameRenderer::getPositionColorShader);
         BufferBuilder buffer = Tesselator.getInstance().begin(Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
         Matrix4f pose = g.pose().last().pose();

         for (RoadSurface.Face f : this.mapFaces) {
            List<RoadGeometry.V> points = f.points();
            int c = f.color();

            for (int i = 1; i + 1 < points.size(); i++) {
               for (RoadGeometry.V v : List.of(points.get(0), points.get(i), points.get(i + 1))) {
                  buffer.addVertex(pose, (float)(ox + (v.x() - this.mapMinX) * scale), (float)(oz + (v.z() - this.mapMinZ) * scale), 0.0F)
                     .setColor(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF, 255);
               }
            }
         }

         BufferUploader.drawWithShader(buffer.buildOrThrow());
         RenderSystem.disableBlend();
         RenderSystem.enableCull();

         for (int i = 0; i < 2; i++) {
            RoadGeometry.V v = (i == 0 ? mesh.first() : mesh.last()).center();
            String label = i == 0 ? "A" : "B";
            int px = (int)(ox + (v.x() - this.mapMinX) * scale);
            int pz = (int)(oz + (v.z() - this.mapMinZ) * scale);
            g.drawString(
               this.font,
               label,
               Math.max(x + 4, Math.min(x + w - this.font.width(label) - 4, px)),
               Math.max(y + 25, Math.min(y + h - 9 - 4, pz)),
               16777215,
               false
            );
         }

         g.disableScissor();
      }
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.left, this.top, this.width - 12, this.height - 8, -334028504);
      g.drawString(this.font, "SR · 收费站", this.left + 10, this.top + 9, 16777215);
      this.map(g);
      super.render(g, mx, my, dt);

      for (TollScreen.Label l : this.labels) {
         g.drawString(this.font, l.text(), l.x(), l.y(), 14476776);
      }

      try {
         TollPlaza.Spec spec = TollCodec.read(this.payload.getCompound("Spec"));
         TollPlanner.Lengths n = TollPlanner.lengths(spec);
         g.drawString(
            this.font,
            this.font.plainSubstrByWidth(String.format(Locale.ROOT, "当前 %.1f / 最低 %.1f / 尚缺 %.1f 格", n.current(), n.minimum(), n.deficit()), this.panel - 20),
            this.left + 10,
            this.row(185),
            n.deficit() > 1.0E-5 ? 16759671 : 11003057
         );
         g.drawString(
            this.font,
            this.font
               .plainSubstrByWidth(
                  "收费单元："
                     + spec.config().forward().units()
                     + (spec.config().twoWay() ? " + " + spec.config().backward().units() : "")
                     + " · "
                     + adjustLabel(this.payload.getString("Adjust")),
                  this.panel - 20
               ),
            this.left + 10,
            this.row(200),
            12702426
         );
      } catch (IllegalArgumentException var7) {
         this.token = null;
         if (this.build != null) {
            this.build.active = false;
         }
      }

      g.enableScissor(this.left + 8, this.row(222), this.left + this.panel - 8, this.height - 60);
      g.drawWordWrap(this.font, Component.literal(this.status), this.left + 10, this.row(222), this.panel - 20, 16770481);
      g.disableScissor();
   }

   private static record Label(String text, int x, int y) {
   }
}
