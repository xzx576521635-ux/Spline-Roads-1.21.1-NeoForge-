package com.sora.splineroads.client;

import com.sora.splineroads.core.RailbedGeometry;
import com.sora.splineroads.core.RailbedOptions;
import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.net.RoadNetwork;
import com.sora.splineroads.world.RailbedCodec;
import com.sora.splineroads.world.RoadRecord;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class RailbedScreen extends Screen {
   private static RoadGeometry.Mesh owned;
   private static final Set<UUID> cancelledTokens = new LinkedHashSet<>();
   private static RailbedGeometry.Plan cachedPlan;
   private static String cachedPlanKey;
   static List<RoadStructures.Part> structures = List.of();
   private static UUID previewToken;
   private static double previewWidth;
   private static RoadGeometry.Structure previewMode;
   private static String previewSelection = "";
   private static RailbedOptions previewOptions;
   private static CompoundTag lastPreview;
   private Button buildButton;
   private Button deleteButton;
   private int previewDelay;
   private boolean previewReady;
   private String structureProblem = "";
   static List<RailbedPath.Path> highlighted = List.of();
   static List<RailbedPath.Path> candidates = List.of();
   private final CompoundTag payload;
   private final List<RailbedPath.Path> paths;
   private RoadGeometry.Structure mode;
   private RailbedOptions options;
   private int page;
   private double deckWidth;
   private int x;
   private int y;
   private String status = "";
   private RailbedGeometry.Plan plan;
   private EditBox widthBox;

   private static void cancelled(UUID token) {
      cancelledTokens.add(token);

      while (cancelledTokens.size() > 32) {
         cancelledTokens.remove(cancelledTokens.iterator().next());
      }
   }

   public RailbedScreen(CompoundTag tag) {
      super(Component.literal("SR 铁路路基"));
      this.payload = tag.copy();
      this.paths = tag.getList("Paths", 10).stream().map(t -> RailbedCodec.read((CompoundTag)t)).toList();
      this.mode = RoadGeometry.Structure.valueOf(tag.getString("Mode"));
      this.deckWidth = tag.getDouble("Width");
      this.options = RailbedCodec.options(tag.getCompound("Options"));
   }

   public static void open(CompoundTag tag) {
      if (!tag.hasUUID("Token") || !cancelledTokens.contains(tag.getUUID("Token"))) {
         if (!tag.getString("Kind").equals("railbedStructurePreview")) {
            if (tag.getString("Kind").equals("railbedReset")) {
               if (tag.hasUUID("Token") && previewToken != null && !previewToken.equals(tag.getUUID("Token"))) {
                  return;
               }

               reset();
               if (Minecraft.getInstance().screen instanceof RailbedScreen) {
                  Minecraft.getInstance().setScreen(null);
               }
            } else {
               lastPreview = tag.contains("CachedPreview") ? tag.getCompound("CachedPreview").copy() : null;
               RailbedScreen screen = new RailbedScreen(tag);
               highlighted = screen.paths;
               candidates = tag.getList("Candidates", 10).stream().map(t -> RailbedCodec.read((CompoundTag)t)).toList();
               if (tag.getString("Kind").equals("railbedPreview")) {
                  screen.refresh();
                  screen.previewDelay = 0;
                  screen.restorePreview();
               } else {
                  Minecraft.getInstance().setScreen(screen);
               }
            }
         } else if (tag.getUUID("Token").equals(previewToken)
            && tag.getDouble("Width") == previewWidth
            && previewMode != null
            && tag.getString("Mode").equals(previewMode.name())
            && tag.getString("Selection").equals(previewSelection)
            && RailbedCodec.options(tag.getCompound("Options")).equals(previewOptions)) {
            lastPreview = tag.copy();
            Screen current = Minecraft.getInstance().screen;
            if (owned != null && ClientRoads.preview == owned && !ClientRoads.toolScreen(current)) {
               String problem = tag.getString("Problem");
               structures = List.of();
               if (tag.contains("Road")) {
                  RoadRecord road = RoadRecord.load(tag.getCompound("Road"));
                  structures = road.structures();
                  owned = road.mesh();
                  ClientRoads.preview = owned;
               }

               if (Minecraft.getInstance().screen instanceof RailbedScreen screen) {
                  screen.previewDelay = 0;
                  screen.previewReady = problem.isEmpty() && tag.contains("Road");
                  screen.structureProblem = problem;
                  screen.updateBuild();
               }
            }
         }
      }
   }

   private static void clear() {
      if (ClientRoads.preview == owned) {
         ClientRoads.preview = null;
      }

      owned = null;
      structures = List.of();
   }

   public static void reset() {
      clear();
      previewToken = null;
      previewOptions = null;
      lastPreview = null;
      cachedPlan = null;
      cachedPlanKey = null;
      highlighted = List.of();
      candidates = List.of();
   }

   protected void init() {
      this.x = (this.width - 340) / 2;
      this.y = (this.height - 220) / 2;
      this.addRenderableWidget(Button.builder(Component.literal(new String[]{"基本 1/3", "护栏/照明 2/3", "接触网 3/3"}[this.page]), b -> {
         this.page = (this.page + 1) % 3;
         this.rebuildWidgets();
      }).bounds(this.x + 218, this.y + 9, 110, 20).build());
      if (this.page == 0) {
         this.addRenderableWidget(
            Button.builder(
                  Component.literal("路基样式：" + this.label()),
                  b -> {
                     this.mode = this.mode == RoadGeometry.Structure.AUTO
                        ? RoadGeometry.Structure.BRIDGE
                        : (this.mode == RoadGeometry.Structure.BRIDGE ? RoadGeometry.Structure.TUNNEL : RoadGeometry.Structure.AUTO);
                     b.setMessage(Component.literal("路基样式：" + this.label()));
                     this.refresh();
                  }
               )
               .bounds(this.x + 12, this.y + 35, 316, 20)
               .build()
         );
         this.addRenderableWidget(
            Button.builder(
                  Component.literal("桥梁样式：" + this.bridgeLabel()),
                  b -> {
                     List<RoadInfrastructure.Bridge> choices = List.of(
                        RoadInfrastructure.Bridge.STANDARD,
                        RoadInfrastructure.Bridge.SUSPENSION,
                        RoadInfrastructure.Bridge.CABLE,
                        RoadInfrastructure.Bridge.ARCH
                     );
                     this.options = this.options
                        .styles(choices.get((choices.indexOf(this.options.bridge()) + 1) % choices.size()), this.options.tunnel(), this.options.fence());
                     this.rebuildWidgets();
                  }
               )
               .bounds(this.x + 12, this.y + 62, 316, 20)
               .build()
         );
         this.addRenderableWidget(
            Button.builder(
                  Component.literal("隧道样式：" + (this.options.tunnel() == RoadInfrastructure.Tunnel.BOX ? "箱型" : "圆形")),
                  b -> {
                     this.options = this.options
                        .styles(
                           this.options.bridge(),
                           this.options.tunnel() == RoadInfrastructure.Tunnel.BOX ? RoadInfrastructure.Tunnel.ROUND : RoadInfrastructure.Tunnel.BOX,
                           this.options.fence()
                        );
                     this.rebuildWidgets();
                  }
               )
               .bounds(this.x + 12, this.y + 88, 316, 20)
               .build()
         );
         this.widthBox = new EditBox(this.font, this.x + 132, this.y + 116, 80, 20, Component.literal("路基总宽"));
         this.widthBox.setValue(this.deckWidth == 0.0 ? "自动" : String.valueOf(this.deckWidth));
         this.widthBox.setResponder(value -> {
            try {
               this.deckWidth = !value.trim().equals("自动") && !value.trim().equalsIgnoreCase("auto") ? Double.parseDouble(value.trim()) : 0.0;
               this.refresh();
            } catch (NumberFormatException var3x) {
               this.plan = null;
               clear();
               this.previewReady = false;
               this.status = "请输入总宽，或输入“自动”";
               this.updateBuild();
            }
         });
         this.addRenderableWidget(this.widthBox);
         this.addRenderableWidget(Button.builder(Component.literal("−"), b -> this.change(-0.5)).bounds(this.x + 216, this.y + 116, 26, 20).build());
         this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.change(0.5)).bounds(this.x + 246, this.y + 116, 26, 20).build());
         this.addRenderableWidget(Button.builder(Component.literal("自动"), b -> this.widthBox.setValue("自动")).bounds(this.x + 276, this.y + 116, 48, 20).build());
      } else if (this.page == 1) {
         this.addRenderableWidget(Button.builder(Component.literal("左侧护栏：" + on(this.options.leftRail())), b -> {
            this.options = this.options.flags(!this.options.leftRail(), this.options.rightRail(), this.options.catenary());
            this.rebuildWidgets();
         }).bounds(this.x + 12, this.y + 40, 154, 20).build());
         this.addRenderableWidget(Button.builder(Component.literal("右侧护栏：" + on(this.options.rightRail())), b -> {
            this.options = this.options.flags(this.options.leftRail(), !this.options.rightRail(), this.options.catenary());
            this.rebuildWidgets();
         }).bounds(this.x + 174, this.y + 40, 154, 20).build());
         this.addRenderableWidget(
            Button.builder(
                  Component.literal("地面护栏样式：" + this.options.fence().label),
                  b -> {
                     this.options = this.options
                        .styles(
                           this.options.bridge(),
                           this.options.tunnel(),
                           this.options.fence() == RailbedOptions.Fence.SOLID ? RailbedOptions.Fence.MESH : RailbedOptions.Fence.SOLID
                        );
                     this.rebuildWidgets();
                  }
               )
               .bounds(this.x + 12, this.y + 67, 316, 20)
               .build()
         );
      } else {
         this.addRenderableWidget(Button.builder(Component.literal("露天接触网：" + on(this.options.catenary())), b -> {
            this.options = this.options.flags(this.options.leftRail(), this.options.rightRail(), !this.options.catenary());
            this.rebuildWidgets();
         }).bounds(this.x + 12, this.y + 35, 316, 20).build());
         Button side = Button.builder(
               Component.literal(this.paths.size() == 1 ? "单轨支柱：" + (this.options.singleSide() > 0 ? "左侧" : "右侧") : "多轨：轨间支柱，双向悬臂"), b -> {
                  this.options = this.options.wire(this.options.poleSpacing(), this.options.wireHeight(), -this.options.singleSide());
                  this.rebuildWidgets();
               }
            )
            .bounds(this.x + 12, this.y + 62, 316, 20)
            .build();
         side.active = this.paths.size() == 1;
         this.addRenderableWidget(side);
         this.number("支柱间距（米）", this.options.poleSpacing(), 90, true);
         this.number("接触网高度（米）", this.options.wireHeight(), 116, false);
      }

      Button build = Button.builder(Component.literal(this.payload.hasUUID("Id") ? "保存" : "建造"), b -> {
         if (this.plan != null && this.previewReady && RailbedGeometry.unsupported(this.paths) == null) {
            this.send("railbedBuild");
            this.minecraft.setScreen(null);
         }
      }).bounds(this.x + 12, this.y + 184, 74, 20).build();
      this.buildButton = build;
      build.active = false;
      this.addRenderableWidget(build);
      this.addRenderableWidget(Button.builder(Component.literal("实景预览"), b -> {
         ManualPlannerClient.worldPreview();
         this.onClose();
      }).bounds(this.x + 92, this.y + 184, 82, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> {
         cancelled(this.payload.getUUID("Token"));
         this.previewDelay = 0;
         this.send("railbedCancel");
         reset();
         this.minecraft.setScreen(null);
      }).bounds(this.x + 180, this.y + 184, 68, 20).build());
      Button delete = Button.builder(Component.literal("删除"), b -> {
         this.send("railbedDelete");
         this.minecraft.setScreen(null);
      }).bounds(this.x + 254, this.y + 184, 74, 20).build();
      this.deleteButton = delete;
      this.addRenderableWidget(delete);
      this.refresh();
      this.restorePreview();
   }

   private static String on(boolean enabled) {
      return enabled ? "开" : "关";
   }

   private void number(String title, double value, int row, boolean spacing) {
      EditBox box = new EditBox(this.font, this.x + 202, this.y + row, 126, 20, Component.literal(title));
      box.setValue(String.valueOf(value));
      box.setResponder(text -> {
         try {
            double n = Double.parseDouble(text.trim());
            this.options = this.options.wire(spacing ? n : this.options.poleSpacing(), spacing ? this.options.wireHeight() : n, this.options.singleSide());
            this.refresh();
         } catch (IllegalArgumentException var5x) {
            this.plan = null;
            clear();
            this.previewReady = false;
            this.structureProblem = "";
            this.status = spacing ? "间距请输入 8–64 米" : "高度请输入 6.1–12 米";
            this.updateBuild();
         }
      });
      this.addRenderableWidget(box);
   }

   private String bridgeLabel() {
      return this.options.bridge() == RoadInfrastructure.Bridge.STANDARD
         ? "高架"
         : (this.options.bridge() == RoadInfrastructure.Bridge.SUSPENSION ? "悬索" : (this.options.bridge() == RoadInfrastructure.Bridge.CABLE ? "斜拉索" : "拱桥"));
   }

   private String label() {
      return this.mode == RoadGeometry.Structure.AUTO ? "自动识别" : (this.mode == RoadGeometry.Structure.BRIDGE ? "桥梁" : "隧道");
   }

   private void restorePreview() {
      if (lastPreview != null) {
         open(lastPreview.copy());
      }
   }

   private void change(double delta) {
      try {
         double minimum = this.plan == null ? RailbedGeometry.plan(this.paths, 0.0, this.mode).minimumWidth() : this.plan.minimumWidth();
         double base = this.deckWidth != 0.0 && Double.isFinite(this.deckWidth) ? this.deckWidth : minimum;
         this.widthBox.setValue(String.valueOf(Math.max(minimum, Math.min(64.0, base + delta))));
      } catch (RuntimeException var7) {
         this.status = var7.getMessage();
      }
   }

   private void refresh() {
      this.previewReady = false;
      this.structureProblem = "";
      this.previewDelay = 8;
      previewToken = this.payload.getUUID("Token");
      previewWidth = this.deckWidth;
      previewMode = this.mode;
      previewOptions = this.options;
      previewSelection = this.payload.getString("Selection");

      try {
         String key = this.payload.getUUID("Token") + ":" + this.payload.getString("Selection") + ":" + this.deckWidth + ":" + this.mode;
         if (!key.equals(cachedPlanKey)) {
            cachedPlan = RailbedGeometry.plan(this.paths, this.deckWidth, this.mode);
            cachedPlanKey = key;
         }

         this.plan = cachedPlan;
         clear();
         owned = this.plan.mesh();
         ClientRoads.preview = owned;
         String unsupported = RailbedGeometry.unsupported(this.paths);
         this.status = unsupported == null
            ? String.format(Locale.ROOT, "已选 %d 轨 · %.1f 格 · 最小总宽 %.1f", this.paths.size(), this.paths.get(0).length(), this.plan.minimumWidth())
            : unsupported;
      } catch (RuntimeException var3) {
         this.plan = null;
         clear();
         this.status = var3.getMessage();
      }

      this.updateBuild();
   }

   private void updateBuild() {
      boolean reading = this.payload.getString("SourceStatus").startsWith("正在核验");
      if (this.buildButton != null) {
         this.buildButton.active = !reading && this.plan != null && this.previewReady && RailbedGeometry.unsupported(this.paths) == null;
      }

      if (this.deleteButton != null) {
         this.deleteButton.active = this.payload.hasUUID("Id") && !reading;
      }
   }

   public void tick() {
      super.tick();
      if (this.previewDelay > 0 && --this.previewDelay == 0 && this.plan != null) {
         this.send("railbedSettings");
      }
   }

   private void send(String action) {
      CompoundTag tag = new CompoundTag();
      tag.putString("Action", action);
      tag.putUUID("Token", this.payload.getUUID("Token"));
      if (this.payload.hasUUID("PlannerSession")) {
         tag.putUUID("PlannerSession", this.payload.getUUID("PlannerSession"));
      }

      tag.putDouble("Width", this.deckWidth);
      tag.putString("Mode", this.mode.name());
      tag.put("Options", RailbedCodec.options(this.options));
      RoadNetwork.sendToServer(new RoadNetwork.Action(tag));
   }

   public void onClose() {
      if (this.plan != null && this.previewDelay > 0) {
         this.send("railbedSettings");
         this.previewDelay = 0;
      }

      this.minecraft.setScreen(null);
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.x, this.y, this.x + 340, this.y + 220, -300604626);
      g.drawString(this.font, this.title, this.x + 12, this.y + 12, 16777215, false);
      if (this.page == 0) {
         g.drawString(this.font, "路基总宽度", this.x + 12, this.y + 122, 16777215, false);
      } else if (this.page == 1) {
         g.drawWordWrap(this.font, Component.literal("左右以 A → B 为准。隧道内不另设护栏；关闭一侧即移除该侧墙，预留站台接口。"), this.x + 12, this.y + 94, 316, 13359583);
         g.drawWordWrap(this.font, Component.literal("有顶隧道每 16 米设置真实照明。开放隧道不计入。"), this.x + 12, this.y + 121, 316, 13359583);
      } else {
         g.drawString(this.font, "支柱间距（8–64 米）", this.x + 12, this.y + 96, 16777215, false);
         g.drawString(this.font, "高度（6.1–12 米）", this.x + 12, this.y + 122, 16777215, false);
      }

      g.drawWordWrap(
         this.font,
         Component.literal(this.structureProblem.isEmpty() ? this.status + (this.previewReady ? "" : " · 校验结构中…") : this.structureProblem),
         this.x + 12,
         this.y + 143,
         316,
         this.plan != null && this.structureProblem.isEmpty() ? 13359583 : 16746632
      );
      g.drawString(this.font, this.font.plainSubstrByWidth(this.payload.getString("SourceStatus"), 316), this.x + 12, this.y + 171, 16764023, false);
      super.render(g, mx, my, dt);
   }
}
