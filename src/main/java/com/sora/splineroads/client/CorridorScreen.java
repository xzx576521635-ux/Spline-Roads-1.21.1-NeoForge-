package com.sora.splineroads.client;

import com.sora.splineroads.core.CorridorPlanner;
import com.sora.splineroads.core.InterchangePlanner;
import com.sora.splineroads.core.LatestPlanningTask;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadGrades;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadRenderMesh;
import com.sora.splineroads.world.Corridors;
import com.sora.splineroads.world.HostAxes;
import com.sora.splineroads.world.Interchanges;
import com.sora.splineroads.world.RoadRecord;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class CorridorScreen extends Screen {
   private static final Map<String, CompoundTag> DRAFTS = new HashMap<>();
   private static final LatestPlanningTask<InterchangePlanner.Plan> WORKER = new LatestPlanningTask<>("SR corridor preview");
   private static long generation;
   private final CompoundTag payload;
   private final Map<String, EditBox> fields = new LinkedHashMap<>();
   private CorridorPlanner.Config config;
   private InterchangePlanner.Options options;
   private InterchangePlanner.Plan plan;
   private Button save;
   private Button preview;
   private int x;
   private int y;
   private int w;
   private int h;
   private int page;
   private int delay;
   private int waitTicks;
   private int axis = 1;
   private long request;
   private boolean initializing;
   private boolean dirty = true;
   private boolean computing;
   private boolean awaitingServer;
   private boolean pending;
   private String status = "正在计算同侧出入口…";
   private String confirmation = "";

   static void deliveryRecovery() {
      if (Minecraft.getInstance().screen instanceof CorridorScreen screen && screen.computing && screen.awaitingServer) {
         screen.invalidate(false);
         screen.delay = 1;
         screen.status = "同步已重置，正在重新请求预览…";
      }
   }

   public static void clear() {
      DRAFTS.clear();
      generation++;
      WORKER.cancel();
   }

   public static void open(CompoundTag fresh) {
      if (fresh.getString("Kind").equals("corridorCheck")) {
         if (Minecraft.getInstance().screen instanceof CorridorScreen screen) {
            screen.checked(fresh);
         }
      } else {
         String mode = fresh.contains("GeneratorMode") ? fresh.getString("GeneratorMode") : fresh.getCompound("Corridor").getString("Mode");
         String kind = fresh.getString("Kind");
         CompoundTag old = DRAFTS.get(mode);
         Minecraft mc = Minecraft.getInstance();
         if (kind.equals("corridorReset")) {
            DRAFTS.remove(mode);
            generation++;
            WORKER.cancel();
            ClientRoads.preview = null;
            ClientRoads.nodePreviews = List.of();
         } else if (kind.equals("corridorResume")) {
            if (old != null) {
               mc.setScreen(new CorridorScreen(old));
            }
         } else {
            CompoundTag merged = fresh.copy();
            if (old != null
               && Arrays.equals(old.getLongArray("Points"), fresh.getLongArray("Points"))
               && old.hasUUID("Id") == fresh.hasUUID("Id")
               && (!old.hasUUID("Id") || old.getUUID("Id").equals(fresh.getUUID("Id")))) {
               for (String key : List.of("Corridor", "Options", "Main1", "Main2")) {
                  merged.put(key, old.getCompound(key).copy());
               }
            }

            mc.setScreen(new CorridorScreen(merged));
         }
      }
   }

   public CorridorScreen(CompoundTag t) {
      super(Component.literal(t.getCompound("Corridor").getString("Mode").equals("FRONTAGE") ? "Spline Roads · 主辅路" : "Spline Roads · 双层同侧出入口"));
      this.payload = t.copy();
      HostAxes.normalizeCorridor(this.payload);
      this.config = Corridors.read(this.payload.getCompound("Corridor"));
      this.options = Interchanges.read(t.getCompound("Options"));
      if (t.getBoolean("Partial")) {
         this.status = "已有路段被删除；整体更新会补回所选布局";
      }
   }

   private void edited() {
      ClientRoads.editorChanged(this);
      this.pending = false;
   }

   private void invalidate() {
      this.invalidate(true);
   }

   private void invalidate(boolean edited) {
      if (edited) {
         this.edited();
      }

      WORKER.cancel();
      generation++;
      this.computing = false;
      this.awaitingServer = false;
      this.dirty = true;
      this.delay = 8;
      this.plan = null;
      if (this.save != null) {
         this.save.active = false;
      }

      if (this.preview != null) {
         this.preview.active = false;
      }
   }

   private Button button(String label, int bx, int by, int bw, Runnable action) {
      return (Button)this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
         if (this.capture()) {
            CompoundTag before = this.payload.copy();
            CorridorPlanner.Config beforeConfig = this.config;
            InterchangePlanner.Options beforeOptions = this.options;
            action.run();
            boolean changed = !before.equals(this.payload) || !beforeConfig.equals(this.config) || !beforeOptions.equals(this.options);
            this.stash();
            this.invalidate(changed);
            this.rebuildWidgets();
         }
      }).bounds(bx, by, bw, 20).build());
   }

   private void field(String key, String label, double value, int bx, int by, int bw) {
      EditBox f = new EditBox(this.font, bx, by + 10, bw, 18, Component.literal(label));
      f.setMaxLength(32);
      f.setValue(Double.toString(value));
      f.setResponder(v -> {
         if (!this.initializing) {
            this.invalidate();
         }
      });
      this.fields.put(key, f);
      this.addRenderableWidget(f);
   }

   private double number(String key, double fallback) {
      return this.fields.containsKey(key) ? Double.parseDouble(this.fields.get(key).getValue()) : fallback;
   }

   private RoadGeometry.Settings settings(int axis) {
      return RoadRecord.readSettings(this.payload.getCompound("Main" + axis));
   }

   private void setSettings(RoadGeometry.Settings s) {
      s.validate();
      this.payload.put("Main" + this.axis, RoadRecord.writeSettings(s));
   }

   private void changeExtras(RoadProfile.Options options) {
      RoadGeometry.Settings old = this.settings(this.axis);
      double lane = RoadProfile.layout(old, old.width()).laneWidth();
      this.setSettings(
         new RoadGeometry.Settings(RoadGeometry.Mode.STRAIGHT, old.style(), RoadProfile.width(old.style(), options, lane), old.thickness(), 0.4, 90.0)
            .structure(old.structure())
            .options(options)
      );
   }

   private void changeStyle(RoadGeometry.Style style) {
      RoadGeometry.Settings old = this.settings(this.axis);
      double lane = RoadProfile.layout(old, old.width()).laneWidth();
      this.setSettings(
         new RoadGeometry.Settings(RoadGeometry.Mode.STRAIGHT, style, RoadProfile.width(style, old.options(), lane), old.thickness(), 0.4, 90.0)
            .structure(old.structure())
            .options(old.options())
      );
   }

   protected void init() {
      this.initializing = true;
      this.fields.clear();
      this.w = Math.min(680, this.width - 12);
      this.h = Math.min(312, this.height - 8);
      this.x = (this.width - this.w) / 2;
      this.y = (this.height - this.h) / 2;
      int bx = this.x + 12;
      int by = this.y + 27;
      int c = this.w >= 540 ? 286 : this.w - 24;
      int half = (c - 6) / 2;
      int tab = (c - 12) / 3;
      this.button((this.page == 0 ? "• " : "") + "出入口", bx, by, tab, () -> this.page = 0);
      this.button((this.page == 1 ? "• " : "") + "端点调整", bx + tab + 6, by, tab, () -> this.page = 1);
      this.button((this.page == 2 ? "• " : "") + "道路样式", bx + 2 * (tab + 6), by, tab, () -> this.page = 2);
      by += 28;
      if (this.page == 0) {
         this.button(
            this.config.sides().label + " ▸",
            bx,
            by,
            half,
            () -> this.config = new CorridorPlanner.Config(
                  this.config.kind(),
                  CorridorPlanner.Sides.values()[(this.config.sides().ordinal() + 1) % 3],
                  this.config.access(),
                  this.config.gap(),
                  this.config.frontageY(),
                  this.config.adjustment()
               )
         );
         if (!HostAxes.fixedFrontage(this.payload)) {
            this.button(
               this.config.access().label + " ▸",
               bx + half + 6,
               by,
               half,
               () -> this.config = new CorridorPlanner.Config(
                     this.config.kind(),
                     this.config.sides(),
                     CorridorPlanner.Access.values()[(this.config.access().ordinal() + 1) % 4],
                     this.config.gap(),
                     this.config.frontageY(),
                     this.config.adjustment()
                  )
            );
         } else {
            this.button("仅建道路（主路有高差）", bx + half + 6, by, half, () -> {
            }).active = false;
         }

         by += 25;
         this.button(
            this.options.leftTraffic() ? "靠左行驶" : "靠右行驶", bx, by, half, () -> this.options = this.changed(!this.options.leftTraffic(), this.options.lanes())
         );
         this.button(
            "连接匝道：" + this.options.lanes() + " 车道",
            bx + half + 6,
            by,
            half,
            () -> this.options = this.changed(this.options.leftTraffic(), 3 - this.options.lanes())
         );
         by += 26;
         this.field("gap", this.config.kind() == CorridorPlanner.Kind.FRONTAGE ? "主辅路净间隔" : "匝道外绕净间隔", this.config.gap(), bx, by, half);
         if (this.config.kind() == CorridorPlanner.Kind.FRONTAGE && !HostAxes.fixedFrontage(this.payload)) {
            this.field("height", "辅路路面 Y", this.config.frontageY(), bx + half + 6, by, half);
         } else if (this.config.kind() == CorridorPlanner.Kind.FRONTAGE) {
            this.button("辅路 Y = A：" + this.config.frontageY(), bx + half + 6, by + 10, half, () -> {
            }).active = false;
         }

         by += 39;
         this.field("transition", "两端过渡长度", this.options.transition(), bx, by, half);
         this.field("radius", "最小半径", this.options.radius(), bx + half + 6, by, half);
         by += 39;
      } else if (this.page == 1) {
         this.button(
            "调整端点：" + (this.options.adjustEndpoints() ? "允许" : "关闭"), bx, by, c, () -> this.options = this.options.adjust(!this.options.adjustEndpoints())
         );
         by += 26;
         this.button(
            "端点范围：" + this.config.adjustment().label + " ▸",
            bx,
            by,
            c,
            () -> this.config = this.config
                  .adjustment(CorridorPlanner.Adjustment.values()[(this.config.adjustment().ordinal() + 1) % CorridorPlanner.Adjustment.values().length])
         );
         by += 30;
         this.field("clearance", "交叠道路净高", this.options.clearance(), bx, by, c);
      } else {
         this.button((this.axis == 1 ? "• " : "") + "AB 主路", bx, by, half, () -> this.axis = 1);
         this.button(
            (this.axis == 2 ? "• " : "") + (this.config.kind() == CorridorPlanner.Kind.FRONTAGE ? "同向辅路" : "CD 第二层"),
            bx + half + 6,
            by,
            half,
            () -> this.axis = 2
         );
         RoadGeometry.Settings road = this.settings(this.axis);
         RoadProfile.Catalog catalog = RoadProfile.catalog(road);
         boolean frontage = this.config.kind() == CorridorPlanner.Kind.FRONTAGE && this.axis == 2;
         this.button(catalog.type() == RoadProfile.Type.HIGHWAY ? "高速道路 ▸" : "普通道路 ▸", bx, by + 24, half, () -> {
            RoadProfile.Type type = catalog.type() == RoadProfile.Type.HIGHWAY ? RoadProfile.Type.ORDINARY : RoadProfile.Type.HIGHWAY;
            this.setSettings(RoadLanes.configure(road, type, RoadLanes.counts(road), RoadProfile.layout(road, road.width()).laneWidth()));
         });
         this.button(RoadLanes.counts(road).label() + "…", bx + half + 6, by + 24, half, () -> {
            int edited = this.axis;
            this.minecraft.setScreen(new RoadLaneConfigScreen(this, catalog.type(), RoadLanes.counts(road), (type, counts) -> {
               this.axis = edited;
               if (frontage && counts.reverse() > 0) {
                  this.status = "同向辅路请选择单向车道";
               } else {
                  this.setSettings(RoadLanes.configure(this.settings(this.axis), type, counts, RoadProfile.layout(road, road.width()).laneWidth()));
                  this.stash();
                  this.invalidate();
               }
            }, !frontage));
         });
         Button medianButton = this.button(
            RoadProfile.medianName(catalog.median()) + " ▸",
            bx,
            by + 48,
            c,
            () -> {
               List<RoadProfile.Median> medians = catalog.type() == RoadProfile.Type.HIGHWAY
                  ? List.of(RoadProfile.Median.RAIL, RoadProfile.Median.GREEN)
                  : List.of(RoadProfile.Median.DOUBLE_YELLOW, RoadProfile.Median.RAIL, RoadProfile.Median.GREEN);
               this.changeStyle(
                  RoadLanes.carrier(catalog.type(), RoadLanes.counts(road), medians.get((medians.indexOf(catalog.median()) + 1) % medians.size()))
               );
            }
         );
         medianButton.active = catalog.twoWay();
         this.field("width" + this.axis, "道路总宽 / 格", road.width(), bx, by + 72, half);
         this.field("thickness" + this.axis, "路板厚度 / 格", road.thickness(), bx + half + 6, by + 72, half);
         this.button(
            "外侧护栏：" + road.options().outerRail().label + " ▸",
            bx,
            by + 106,
            half,
            () -> this.setSettings(
                  this.settings(this.axis)
                     .options(
                        this.settings(this.axis)
                           .options()
                           .outerRail(RoadProfile.OuterRail.values()[(road.options().outerRail().ordinal() + 1) % RoadProfile.OuterRail.values().length])
                     )
               )
         );
         this.button(
            "龙门架：" + (road.options().infrastructure().gantry() == RoadInfrastructure.Gantry.OFF ? "关闭" : "开启"),
            bx + half + 6,
            by + 106,
            half,
            () -> {
               RoadGeometry.Settings current = this.settings(this.axis);
               RoadInfrastructure.Config infra = current.options().infrastructure();
               this.setSettings(
                  current.options(
                     current.options()
                        .infrastructure(
                           infra.gantry(infra.gantry() == RoadInfrastructure.Gantry.OFF ? RoadInfrastructure.Gantry.FRAME : RoadInfrastructure.Gantry.OFF)
                        )
                  )
               );
            }
         );
         if (catalog.type() == RoadProfile.Type.ORDINARY && this.h >= 280) {
            this.button("非机动车道：" + (road.options().cycle() ? "开" : "关"), bx, by + 130, half, () -> {
               RoadProfile.Options o = this.settings(this.axis).options();
               this.changeExtras(o.extras(!o.cycle(), o.cycleRail(), o.curb()));
            });
            this.button("路缘石：" + (road.options().curb() ? "开" : "关"), bx + half + 6, by + 130, half, () -> {
               RoadProfile.Options o = this.settings(this.axis).options();
               this.changeExtras(o.extras(o.cycle(), o.cycleRail(), !o.curb()));
            });
         }
      }

      int n = this.payload.hasUUID("Id") ? 5 : 3;
      int bw = (this.w - 24 - 6 * (n - 1)) / n;
      int footer = this.y + this.h - 26;
      this.save = (Button)this.addRenderableWidget(
         Button.builder(Component.literal(this.payload.hasUUID("Id") ? "整体更新" : "整体建造"), b -> this.submit("interchange")).bounds(bx, footer, bw, 20).build()
      );
      this.preview = (Button)this.addRenderableWidget(Button.builder(Component.literal("实景预览"), b -> {
         if (this.capture()) {
            this.stash();
            ManualPlannerClient.worldPreview();
            this.minecraft.setScreen(null);
         }
      }).bounds(bx + bw + 6, footer, bw, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> this.onClose()).bounds(bx + 2 * (bw + 6), footer, bw, 20).build());
      if (n == 5) {
         for (int i = 0; i < 2; i++) {
            String action = i == 0 ? "interchangeDeleteRamps" : "interchangeDelete";
            boolean ramps = i == 0;
            this.addRenderableWidget(Button.builder(Component.literal(ramps ? "仅删匝道" : "删除组合"), b -> {
               if (!action.equals(this.confirmation)) {
                  this.confirmation = action;
                  b.setMessage(Component.literal("再次点击确认"));
                  this.status = ramps ? "保留主路与辅路/第二层道路，解除组合关系" : "删除此组合的所有道路，保留端点";
               } else {
                  this.submit(action);
               }
            }).bounds(bx + (3 + i) * (bw + 6), footer, bw, 20).build());
         }
      }

      this.save.active = this.preview.active = this.plan != null && !this.dirty && !this.computing && !this.pending;
      this.initializing = false;
      this.delay = 1;
   }

   private InterchangePlanner.Options changed(boolean left, int lanes) {
      return new InterchangePlanner.Options(
         this.options.preset(), left, lanes, this.options.transition(), this.options.radius(), this.options.clearance(), 1, 0.0, this.options.adjustEndpoints()
      );
   }

   private boolean capture() {
      try {
         this.config = new CorridorPlanner.Config(
            this.config.kind(),
            this.config.sides(),
            this.config.access(),
            this.number("gap", this.config.gap()),
            this.number("height", this.config.frontageY()),
            this.config.adjustment()
         );
         this.options = new InterchangePlanner.Options(
            this.options.preset(),
            this.options.leftTraffic(),
            this.options.lanes(),
            this.number("transition", this.options.transition()),
            this.number("radius", this.options.radius()),
            this.number("clearance", this.options.clearance()),
            1,
            this.options.rampWidth(),
            this.options.adjustEndpoints()
         );

         for (int i = 1; i <= 2; i++) {
            RoadGeometry.Settings old = this.settings(i);
            RoadGeometry.Settings next = new RoadGeometry.Settings(
                  RoadGeometry.Mode.STRAIGHT, old.style(), this.number("width" + i, old.width()), this.number("thickness" + i, old.thickness()), 0.4, 90.0
               )
               .structure(old.structure())
               .options(old.options());
            next.validate();
            this.payload.put("Main" + i, RoadRecord.writeSettings(next));
         }

         this.payload.put("Corridor", Corridors.write(this.config));
         HostAxes.normalizeCorridor(this.payload);
         this.config = Corridors.read(this.payload.getCompound("Corridor"));
         return true;
      } catch (IllegalArgumentException var4) {
         this.status = var4 instanceof NumberFormatException ? "请输入有效数字" : var4.getMessage();
         return false;
      }
   }

   private void stash() {
      this.payload.put("Corridor", Corridors.write(this.config));
      this.payload.put("Options", Interchanges.write(this.options));
      DRAFTS.put(this.config.kind().name(), this.payload.copy());
   }

   public void tick() {
      super.tick();
      if (this.delay > 0) {
         this.delay--;
      }

      if (this.computing && ++this.waitTicks > 400) {
         WORKER.cancel();
         this.computing = false;
         this.request = ++generation;
         this.status = "预览检查超时，请调整参数或重新打开后重试";
      }

      if (this.delay == 0 && this.dirty && !this.computing) {
         this.calculate();
      }
   }

   private void calculate() {
      this.dirty = false;
      if (this.capture()) {
         this.stash();
         ClientRoads.editorChanged(this);
         this.computing = true;
         this.awaitingServer = true;
         this.waitTicks = 0;
         this.request = ++generation;
         CompoundTag command = new CompoundTag();

         for (String key : List.of("Id", "Points", "Main1", "Main2", "Options", "Corridor")) {
            if (this.payload.contains(key)) {
               command.put(key, this.payload.get(key).copy());
            }
         }

         command.putString("Action", "interchangePreview");
         command.putLong("Request", this.request);
         this.status = "正在核对既有道路、端点占用与可调整方向…";
         ClientRoads.sendAction(command);
      }
   }

   private void checked(CompoundTag reply) {
      if (reply.getLong("Request") == this.request
         && this.computing
         && this.minecraft.level != null
         && this.minecraft.level.dimension().location().toString().equals(reply.getString("Dimension"))) {
         this.awaitingServer = false;
         if (this.dirty) {
            this.computing = false;
            this.delay = 1;
         } else if (reply.contains("Error")) {
            this.computing = false;
            this.status = reply.getString("Error");
            ClientRoads.preview = null;
            ClientRoads.nodePreviews = List.of();
         } else {
            CompoundTag inputs = reply.getCompound("Inputs").copy();
            this.payload.put("Nodes", inputs.getList("Nodes", 10).copy());
            this.payload.putString("ResolvedFit", inputs.getString("ResolvedFit"));
            this.payload.put("HostAxes", inputs.getList("HostAxes", 10).copy());
            this.payload.put("Corridor", inputs.getCompound("Corridor").copy());
            this.config = Corridors.read(this.payload.getCompound("Corridor"));
            long token = this.request;
            this.waitTicks = 0;
            this.status = "检查通过，正在生成道路预览…";
            WORKER.submit(
               () -> Interchanges.plan(inputs),
               (result, error) -> Minecraft.getInstance()
                     .execute(
                        () -> {
                           if (token == generation) {
                              this.computing = false;
                              if (this.dirty) {
                                 this.delay = 1;
                              } else if (error != null) {
                                 Throwable cause = error.getCause() == null ? error : error.getCause();
                                 this.status = cause.getMessage();
                                 ClientRoads.preview = null;
                                 ClientRoads.nodePreviews = List.of();
                              } else {
                                 this.plan = result;
                                 ClientRoads.nodePreviews = result.legs().stream().map(l -> RoadRenderMesh.simplify(l.mesh())).toList();
                                 ClientRoads.preview = ClientRoads.nodePreviews.get(0);
                                 this.status = (
                                       this.options.adjustEndpoints()
                                          ? "端点：" + CorridorPlanner.Adjustment.valueOf(inputs.getString("ResolvedFit")).label + "；"
                                          : ""
                                    )
                                    + result.movements()
                                    + " 条同侧出入口；最大纵坡 "
                                    + String.format(
                                       Locale.ROOT, "%.1f%%", result.legs().stream().mapToDouble(l -> RoadGrades.maximum(l.mesh())).max().orElse(0.0) * 100.0
                                    )
                                    + "。左右以 A→B 为准。";
                                 int moved = 0;
                                 double distance = 0.0;
                                 ListTag source = this.payload.getList("Nodes", 10);

                                 for (int i = 0; i < result.anchors().size(); i++) {
                                    double d = result.anchors().get(i).position().distance(RoadRecord.readNode(source.getCompound(i)).position());
                                    if (d > 1.0E-6) {
                                       moved++;
                                       distance = Math.max(distance, d);
                                    }
                                 }

                                 if (HostAxes.fixedFrontage(this.payload)) {
                                    this.status = "主路有高差：仅建辅路，不设出入口；辅路固定在 A 端高度 " + this.config.frontageY() + "。";
                                 }

                                 if (moved > 0) {
                                    this.status = this.status + " 自动调整 " + moved + " 个端点，最大移动 " + String.format(Locale.ROOT, "%.1f", distance) + " 格（实景预览可查看）。";
                                 }

                                 if (this.payload.getBoolean("Partial")) {
                                    this.status = this.status + " 整体更新会补回已删除路段。";
                                 }

                                 this.save.active = this.preview.active = !this.pending;
                              }
                           }
                        }
                     )
            );
         }
      }
   }

   private void submit(String action) {
      if (!this.pending && (!action.equals("interchange") || this.plan != null && !this.dirty && !this.computing)) {
         if (this.capture()) {
            this.stash();
            CompoundTag command = new CompoundTag();

            for (String key : List.of("Id", "Points", "Main1", "Main2", "Options", "Corridor")) {
               if (this.payload.contains(key)) {
                  command.put(key, this.payload.get(key).copy());
               }
            }

            command.putInt("SourceAxesHash", HostAxes.signature(this.payload));
            command.put("SourceNodes", this.payload.getList("Nodes", 10).copy());
            if (this.plan != null) {
               ListTag fitted = new ListTag();
               this.plan.anchors().forEach(n -> fitted.add(RoadRecord.writeNode(n)));
               command.put("FittedNodes", fitted);
            }

            command.putString("Action", action);
            this.pending = true;
            this.save.active = false;
            this.preview.active = false;
            this.status = "正在检查并提交道路组合…";
            ClientRoads.sendAction(command);
         }
      }
   }

   void completed() {
      this.pending = false;
   }

   public void failed(String reason) {
      this.pending = false;
      this.status = reason;
      this.save.active = this.plan != null && !this.dirty;
      this.preview.active = this.save.active;
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void removed() {
      WORKER.cancel();
      generation++;
      if (this.computing) {
         this.dirty = true;
      }

      this.computing = false;
      super.removed();
   }

   public void onClose() {
      WORKER.cancel();
      generation++;
      if (this.capture()) {
         this.stash();
      }

      ClientRoads.preview = null;
      ClientRoads.nodePreviews = List.of();
      super.onClose();
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float partial) {
      super.renderBackground(g, mx, my, partial);
      g.fill(this.x, this.y, this.x + this.w, this.y + this.h, -283630288);
      g.drawString(this.font, this.title, this.x + 12, this.y + 10, 16777215, false);
      super.render(g, mx, my, partial);

      for (EditBox f : this.fields.values()) {
         g.drawString(this.font, f.getMessage(), f.getX(), f.getY() - 9, 13163237, false);
      }

      if (this.w >= 540) {
         int px = this.x + 312;
         int py = this.y + 55;
         int pw = this.w - 324;
         int ph = this.h - 123;
         g.fill(px, py, px + pw, py + ph, -16181729);
         if (this.plan != null) {
            RoadGeometry.V origin = this.plan.anchors().get(0).position();
            RoadGeometry.V axis = this.plan.anchors().get(1).position().sub(origin).horizontalUnit();
            double length = this.plan.anchors().get(1).position().distance(origin);
            double extent = this.plan
               .legs()
               .stream()
               .flatMap(l -> l.mesh().samples().stream())
               .mapToDouble(sx -> Math.abs(sx.center().sub(origin).dot(axis.left())) + sx.halfWidth())
               .max()
               .orElse(20.0);
            double scale = Math.min((double)(pw - 16) / length, (double)(ph - 30) / (extent * 2.0));

            for (InterchangePlanner.Leg leg : this.plan.legs()) {
               for (int i = 0; i < leg.mesh().samples().size(); i += 2) {
                  RoadGeometry.Sample s = leg.mesh().samples().get(i);
                  RoadGeometry.V d = s.center().sub(origin);
                  int xx = px + 8 + (int)(d.dot(axis) * scale);
                  int zz = py + ph / 2 + (int)(d.dot(axis.left()) * scale);
                  int r = Math.max(1, (int)(s.halfWidth() * scale));
                  g.fill(
                     xx - r, zz - r, xx + r + 1, zz + r + 1, leg.mesh().settings().style().ramp() ? -12947 : (leg.name().contains("辅路") ? -9320539 : -11035447)
                  );
               }
            }

            g.drawString(this.font, "A → B 俯视 · 黄色为连接匝道", px + 6, py + 6, 16777215, false);
         }
      }

      List<FormattedCharSequence> lines = this.font.split(Component.literal(this.status == null ? "" : this.status), this.w - 24);

      for (int i = 0; i < Math.min(2, lines.size()); i++) {
         g.drawString(this.font, lines.get(i), this.x + 12, this.y + this.h - 51 + i * 10, this.plan == null ? -18781 : -3610150, false);
      }

      if (lines.size() > 2 && my >= this.y + this.h - 52 && my < this.y + this.h - 29) {
         g.renderTooltip(this.font, Component.literal(this.status), mx, my);
      }
   }
}
