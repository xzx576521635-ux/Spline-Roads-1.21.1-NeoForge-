package com.sora.splineroads.client;

import com.sora.splineroads.core.CurvedRoadPlans;
import com.sora.splineroads.core.DoubleTrumpetToll;
import com.sora.splineroads.core.InterchangePlanner;
import com.sora.splineroads.core.LatestPlanningTask;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadGrades;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadPlantings;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadRenderMesh;
import com.sora.splineroads.world.HostAxes;
import com.sora.splineroads.world.Interchanges;
import com.sora.splineroads.world.ParallelSpui;
import com.sora.splineroads.world.RoadRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class InterchangeScreen extends Screen {
   private static final Map<Integer, CompoundTag> drafts = new HashMap<>();
   private static CompoundTag draft;
   private static final LatestPlanningTask<InterchangePlanner.Plan> WORKER = new LatestPlanningTask<>("SplineRoads interchange preview");
   private static long generation;
   private final CompoundTag payload;
   private final Map<String, EditBox> fields = new LinkedHashMap<>();
   private InterchangePlanner.Options options;
   private InterchangePlanner.Plan plan;
   private Button save;
   private Button preview;
   private Button heights;
   private int left;
   private int top;
   private int panel;
   private int panelHeight;
   private int control;
   private int delay;
   private int page;
   private int axis = 1;
   private int endpointTextBottom;
   private int stylePage;
   private boolean doubleDetails;
   private EditBox tollName;
   private String deleteConfirmation = "";
   private boolean pending;
   private boolean initializing;
   private boolean computing;
   private boolean needsPlan;
   private String status = "正在计算整座立交…";

   private void edited() {
      ClientRoads.editorChanged(this);
      this.pending = false;
   }

   public static void clear() {
      drafts.clear();
      draft = null;
      generation++;
      WORKER.cancel();
      ClientRoads.nodePreviews = List.of();
   }

   public static void open(CompoundTag fresh) {
      Minecraft mc = Minecraft.getInstance();
      int arms = fresh.contains("GeneratorArms") ? fresh.getInt("GeneratorArms") : fresh.getLongArray("Points").length;
      draft = drafts.get(arms);
      String kind = fresh.getString("Kind");
      if (kind.equals("interchangeReset")) {
         drafts.remove(arms);
         draft = null;
         generation++;
         WORKER.cancel();
         ClientRoads.nodePreviews = List.of();
         ClientRoads.preview = null;
      } else if (kind.equals("interchangeResume")) {
         if (draft != null) {
            mc.setScreen(new InterchangeScreen(draft));
         } else if (mc.player != null) {
            mc.player
               .displayClientMessage(
                  Component.literal(
                     arms == 3
                        ? "先选 A/B 主路两端，再选 C 支路端点"
                        : (arms == 4 ? "依次选择 A/B 与 C/D 两条主路端点" : (arms == 5 ? "依次选择 A/B、C/D 两条主路与 E 支路端点" : "依次选择 A/B、C/D、E/F 三条主路端点"))
                  ),
                  true
               );
         }
      } else {
         CompoundTag merged = fresh.copy();
         if (draft != null
            && Arrays.equals(draft.getLongArray("Points"), fresh.getLongArray("Points"))
            && draft.hasUUID("Id") == fresh.hasUUID("Id")
            && (!draft.hasUUID("Id") || draft.getUUID("Id").equals(fresh.getUUID("Id")))) {
            for (String key : List.of("Options", "Main1", "Main2", "Main3")) {
               merged.put(key, draft.getCompound(key).copy());
            }
         }

         draft = merged.copy();
         drafts.put(arms, draft);
         mc.setScreen(new InterchangeScreen(merged));
      }
   }

   public InterchangeScreen(CompoundTag payload) {
      super(Component.literal("Spline Roads · 自动立交"));
      this.payload = payload.copy();

      for (String key : payload.getLongArray("Points").length >= 5 ? List.of("Main1", "Main2", "Main3") : List.of("Main1", "Main2")) {
         this.payload.put(key, RoadRecord.writeSettings(RoadProfile.editable(RoadRecord.readSettings(this.payload.getCompound(key)))));
      }

      this.options = Interchanges.read(payload.getCompound("Options"));
      this.normalizeJunctionAxis();
      if (payload.getBoolean("Partial")) {
         this.status = "立交已有路段被单独删除；整体更新将重新生成完整布局";
      }

      if (!HostAxes.presets(payload).contains(this.options.preset())) {
         this.options = this.changed(
            HostAxes.presets(payload).get(0), this.options.leftTraffic(), this.options.lanes(), this.options.rampWidth(), this.options.adjustEndpoints()
         );
         this.status = HostAxes.retiredInterchange(payload) ? "请选择两条直线主路" : "旧布局已停用；更新时将使用当前预设，也可仅删除匝道保留主路";
      }
   }

   private Button button(String label, int x, int y, int w, Runnable click) {
      return (Button)this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
         if (this.capture()) {
            try {
               CompoundTag before = this.payload.copy();
               InterchangePlanner.Options beforeOptions = this.options;
               click.run();
               if (!before.equals(this.payload) || !beforeOptions.equals(this.options)) {
                  this.edited();
               }

               WORKER.cancel();
               generation++;
               this.computing = false;
               this.plan = null;
               this.stash();
               this.rebuildWidgets();
            } catch (IllegalArgumentException var5x) {
               this.status = var5x.getMessage();
            }
         }
      }).bounds(x, y, w, 20).build());
   }

   private void tab(String text, int x, int y, int w, int target) {
      this.addRenderableWidget(Button.builder(Component.literal((this.page == target ? "• " : "") + text), b -> {
         if (this.capture()) {
            this.persist();
            this.page = target;
            this.rebuildWidgets();
         }
      }).bounds(x, y, w, 20).build());
   }

   private InterchangePlanner.Options changed(InterchangePlanner.Preset preset, boolean leftTraffic, int lanes, double width, boolean adjust) {
      return new InterchangePlanner.Options(
         preset,
         leftTraffic,
         lanes,
         this.options.transition(),
         this.options.radius(),
         this.options.clearance(),
         this.options.upper(),
         width,
         adjust,
         this.options.allowShrink(),
         this.options.maxLowering(),
         this.options.junctionAxis(),
         this.options.loopMask(),
         this.options.allowAbove(),
         this.options.flatConnector(),
         this.options.connectorHeight(),
         this.options.toll()
      );
   }

   private boolean highway(int axis) {
      return RoadProfile.catalog(RoadRecord.readSettings(this.payload.getCompound("Main" + (axis + 1)))).type() == RoadProfile.Type.HIGHWAY;
   }

   private void normalizeJunctionAxis() {
      if ((this.options.preset() == InterchangePlanner.Preset.DIAMOND || this.options.preset() == InterchangePlanner.Preset.SPUI)
         && this.highway(this.options.junctionAxis())
         && !this.highway(1 - this.options.junctionAxis())) {
         this.options = this.options.junction(1 - this.options.junctionAxis());
      }
   }

   RoadGeometry.Settings mainSettings() {
      return RoadRecord.readSettings(this.payload.getCompound("Main" + this.axis));
   }

   void mainSettings(RoadGeometry.Settings value) {
      value.validate();
      this.payload.put("Main" + this.axis, RoadRecord.writeSettings(value));
   }

   void changeExtras(RoadProfile.Options options) {
      RoadGeometry.Settings old = this.mainSettings();
      double lane = RoadProfile.layout(old, old.width()).laneWidth();
      this.mainSettings(
         new RoadGeometry.Settings(RoadGeometry.Mode.STRAIGHT, old.style(), RoadProfile.width(old.style(), options, lane), old.thickness(), 0.4, 90.0)
            .options(options)
            .structure(old.structure())
      );
   }

   void changeStyle(RoadGeometry.Style next) {
      RoadGeometry.Settings old = this.mainSettings();
      double lane = RoadProfile.layout(old, old.width()).laneWidth();
      this.mainSettings(
         new RoadGeometry.Settings(RoadGeometry.Mode.STRAIGHT, next, RoadProfile.width(next, old.options(), lane), old.thickness(), 0.4, 90.0)
            .options(old.options())
            .structure(old.structure())
      );
   }

   protected void init() {
      this.initializing = true;
      boolean parallel = ParallelSpui.present(this.payload);
      if (parallel) {
         this.page = 0;
      }

      this.fields.clear();
      this.tollName = null;
      this.panel = Math.min(700, this.width - 16);
      if (this.page == 3) {
         this.page = 0;
      }

      this.left = (this.width - this.panel) / 2;
      this.panelHeight = Math.min(316, this.height - 8);
      this.top = Math.max(4, (this.height - this.panelHeight) / 2);
      this.control = Math.min(306, (int)((double)(this.panel - 36) * 0.54));
      int x = this.left + 12;
      int y = this.top + 26;
      int w = this.control;
      int half = (w - 8) / 2;
      int third = (w - 8) / 3;
      if (HostAxes.retiredInterchange(this.payload)) {
         this.status = "请选择两条直线主路";
         this.needsPlan = false;
         this.initializing = false;
         this.page = 0;
         this.save = (Button)this.addRenderableWidget(Button.builder(Component.literal("曲线立交建造已撤下"), bx -> {
         }).bounds(x, y, w, 20).build());
         this.save.active = false;
         this.preview = this.save;
         this.addRenderableWidget(Button.builder(Component.literal("关闭"), bx -> this.onClose()).bounds(x, y + 28, w, 20).build());
         if (this.payload.hasUUID("Id")) {
            for (int i = 0; i < 2; i++) {
               String action = i == 0 ? "interchangeDeleteRamps" : "interchangeDelete";
               String label = i == 0 ? "仅删匝道，保留主路" : "删除整座";
               this.addRenderableWidget(Button.builder(Component.literal(label), bx -> {
                  if (!action.equals(this.deleteConfirmation)) {
                     this.deleteConfirmation = action;
                     bx.setMessage(Component.literal("确认：" + label));
                  } else {
                     this.submit(action);
                  }
               }).bounds(x, y + 56 + i * 28, w, 20).build());
            }
         }
      } else {
         int tabs = 3;
         int tw = (w - 4 * (tabs - 1)) / tabs;
         this.tab("布局 / 匝道", x, y, tw, 0);
         if (!parallel) {
            this.tab("主路样式", x + tw + 4, y, tw, 1);
            this.tab("端点调整", x + 2 * (tw + 4), y, tw, 2);
         }

         y += 26;
         if (this.page == 0) {
            this.button(
               this.options.preset().label + (this.payload.getLongArray("Points").length >= 5 ? "" : " ▸"),
               x,
               y,
               w,
               () -> {
                  ArrayList<InterchangePlanner.Preset> choices = new ArrayList<>(HostAxes.presets(this.payload));
                  this.options = this.changed(
                     choices.get((choices.indexOf(this.options.preset()) + 1) % choices.size()),
                     this.options.leftTraffic(),
                     this.options.lanes(),
                     this.options.rampWidth(),
                     this.options.adjustEndpoints()
                  );
               }
            );
            this.button(
               this.options.leftTraffic() ? "靠左行驶" : "靠右行驶",
               x,
               y + 24,
               half,
               () -> this.options = this.changed(
                     this.options.preset(), !this.options.leftTraffic(), this.options.lanes(), this.options.rampWidth(), this.options.adjustEndpoints()
                  )
            );
            ListTag nodes = this.payload.getList("Nodes", 10);
            double ab = (RoadRecord.readNode(nodes.getCompound(0)).position().y() + RoadRecord.readNode(nodes.getCompound(1)).position().y()) / 2.0;
            double cd = (
                  RoadRecord.readNode(nodes.getCompound(2)).position().y() + RoadRecord.readNode(nodes.getCompound(nodes.size() == 3 ? 2 : 3)).position().y()
               )
               / 2.0;
            double minimum = InterchangePlanner.minimumDifference(
               RoadRecord.readSettings(this.payload.getCompound("Main1")),
               RoadRecord.readSettings(this.payload.getCompound("Main2")),
               HostAxes.curved(this.payload) ? CurvedRoadPlans.planningOptions(this.options) : this.options
            );
            this.heights = (Button)this.addRenderableWidget(
               Button.builder(
                     Component.literal(
                        String.format(
                           Locale.ROOT,
                           this.payload.getLongArray("Points").length >= 5
                              ? "分层升降 · 自动拟合"
                              : (this.options.preset().three ? "高差 %.0f · 最低 0" : "原高差 %.0f / 最低 %.0f"),
                           Math.abs(ab - cd),
                           minimum
                        )
                     ),
                     bx -> {
                     }
                  )
                  .bounds(x + half + 8, y + 24, half, 20)
                  .build()
            );
            this.heights.active = false;
            if (parallel) {
               this.heights.setMessage(Component.literal("路口：沿用下层已有路口"));
            } else if (this.options.preset() == InterchangePlanner.Preset.DIAMOND || this.options.preset() == InterchangePlanner.Preset.SPUI) {
               this.heights.visible = false;
               this.button("路口设在：" + (this.options.junctionAxis() == 0 ? "AB" : "CD") + " 主路 ▸", x + half + 8, y + 24, half, () -> {
                  if (!this.highway(1 - this.options.junctionAxis())) {
                     this.options = this.options.junction(1 - this.options.junctionAxis());
                  }
               });
            }

            if (this.options.preset() == InterchangePlanner.Preset.HYBRID) {
               this.heights.visible = false;
               StringBuilder names = new StringBuilder();
               String[] arms = new String[]{"A", "C", "B", "D"};

               for (int i = 0; i < 4; i++) {
                  if ((this.options.loopMask() & 1 << i) != 0) {
                     names.append(arms[i]);
                  }
               }

               this.button("绕圈左转：" + names + " ▸", x + half + 8, y + 24, half, () -> {
                  List<Integer> masks = List.of(5, 10, 3, 6, 12, 9);
                  this.options = this.options.loops(masks.get((masks.indexOf(this.options.loopMask()) + 1) % masks.size()));
               });
            }

            this.updateHeightLabel();
            this.button(
               this.options.lanes() + " 车道匝道 ▸",
               x,
               y + 58,
               half,
               () -> {
                  int lanes = 3 - this.options.lanes();
                  double oldDefault = this.options.lanes() == 1 ? 5.0 : 9.0;
                  double value = Math.abs(this.options.width() - oldDefault) < 1.0E-7
                     ? 0.0
                     : Math.max((double)(lanes * 4), this.options.width() * (double)lanes / (double)this.options.lanes());
                  this.options = this.changed(this.options.preset(), this.options.leftTraffic(), lanes, value, this.options.adjustEndpoints());
               }
            );
            this.field(
               parallel ? "匝道中段总宽 / 格" : "匝道总宽 / 格",
               "rampWidth",
               parallel
                  ? (double)this.options.lanes()
                        * RoadProfile.layout(
                              RoadRecord.readSettings(this.payload.getCompound("Main2")), RoadRecord.readSettings(this.payload.getCompound("Main2")).width()
                           )
                           .laneWidth()
                     + 1.5
                  : this.options.width(),
               x + half + 8,
               y + 48,
               half
            );
            if (this.panelHeight < 280) {
               this.field("过渡 / 格", "transition", this.options.transition(), x, y + 82, third);
               this.field("半径 / 格", "radius", this.options.radius(), x + third + 4, y + 82, third);
               if (parallel) {
                  this.button("净空按主路校验", x + 2 * (third + 4), y + 92, third, () -> {
                  }).active = false;
               } else {
                  this.field("净高 / 格", "clearance", this.options.clearance(), x + 2 * (third + 4), y + 82, third);
               }

               this.button(
                  "调整端点：" + (this.options.adjustEndpoints() ? "允许" : "关闭"),
                  x,
                  y + 114,
                  w,
                  () -> this.options = this.options.adjust(!this.options.adjustEndpoints())
               );
            } else {
               this.field("过渡 / 格", "transition", this.options.transition(), x, y + 80, half);
               this.field("环绕半径 / 格", "radius", this.options.radius(), x + half + 8, y + 80, half);
               if (parallel) {
                  this.button("净空按主路校验", x, y + 122, half, () -> {
                  }).active = false;
               } else {
                  this.field("交叉净高 / 格", "clearance", this.options.clearance(), x, y + 112, half);
               }

               this.button(
                  "调整端点：" + (this.options.adjustEndpoints() ? "允许" : "关闭"),
                  x + half + 8,
                  y + 122,
                  half,
                  () -> this.options = this.options.adjust(!this.options.adjustEndpoints())
               );
            }

            if (this.options.aboveAvailable()) {
               this.button(
                  "允许匝道高于主路：" + (this.options.allowAbove() ? "开" : "关"), x, y + 146, w, () -> this.options = this.options.above(!this.options.allowAbove())
               );
            }

            if (this.options.preset() == InterchangePlanner.Preset.DOUBLE_TRUMPET) {
               this.doubleView("双喇叭配置", x, y + 146, half, true);
               this.doubleView("立交俯视图", x + half + 8, y + 146, w - half - 8, false);
               if (this.doubleDetails) {
                  this.doubleSettings(this.left + this.control + 24, this.top + 38, this.panel - this.control - 36);
               }
            }
         } else if (this.page == 1) {
            if (this.payload.getLongArray("Points").length >= 5) {
               this.button((this.axis == 1 ? "• " : "") + "AB 主路", x, y, third, () -> this.axis = 1);
               this.button((this.axis == 2 ? "• " : "") + "CD 主路", x + third + 4, y, third, () -> this.axis = 2);
               this.button(
                  (this.axis == 3 ? "• " : "") + (this.payload.getLongArray("Points").length == 5 ? "E 支路" : "EF 主路"),
                  x + 2 * (third + 4),
                  y,
                  w - 2 * (third + 4),
                  () -> this.axis = 3
               );
            } else {
               this.button((this.axis == 1 ? "• " : "") + "AB 主路", x, y, half, () -> this.axis = 1);
               this.button((this.axis == 2 ? "• " : "") + "CD 主路", x + half + 8, y, half, () -> this.axis = 2);
            }

            RoadGeometry.Settings road = this.mainSettings();
            RoadProfile.Catalog catalog = RoadProfile.catalog(road);
            this.button(catalog.type() == RoadProfile.Type.HIGHWAY ? "高速道路 ▸" : "普通道路 ▸", x, y + 24, half, () -> {
               RoadProfile.Type type = catalog.type() == RoadProfile.Type.HIGHWAY ? RoadProfile.Type.ORDINARY : RoadProfile.Type.HIGHWAY;
               this.mainSettings(RoadLanes.configure(road, type, RoadLanes.counts(road), RoadProfile.layout(road, road.width()).laneWidth()));
            });
            this.button(RoadLanes.counts(road).label() + "…", x + half + 8, y + 24, half, () -> {
               int editedAxis = this.axis;
               this.minecraft.setScreen(new RoadLaneConfigScreen(this, catalog.type(), RoadLanes.counts(road), (type, counts) -> {
                  this.axis = editedAxis;
                  this.mainSettings(RoadLanes.configure(this.mainSettings(), type, counts, RoadProfile.layout(road, road.width()).laneWidth()));
                  this.edited();
                  this.plan = null;
                  this.stash();
               }, true));
            });
            RoadPlantings plants = road.options().streetscape().plantings();
            this.field("护栏间距 / 格", "railGap", plants.railGap(), x, y + 48, half);
            this.field("绿化带宽度 / 格", "greenWidth", plants.greenWidth(), x + half + 8, y + 48, half);
            this.field("主路总宽 / 格", "mainWidth", road.width(), x, y + 80, half);
            this.field("路板厚度 / 格", "thickness", road.thickness(), x + half + 8, y + 80, half);
            this.button(
               "外侧护栏：" + road.options().outerRail().label + " ▸",
               x,
               y + 114,
               half,
               () -> this.mainSettings(
                     this.mainSettings()
                        .options(
                           this.mainSettings()
                              .options()
                              .outerRail(RoadProfile.OuterRail.values()[(road.options().outerRail().ordinal() + 1) % RoadProfile.OuterRail.values().length])
                        )
                  )
            );
            this.button(
               "龙门架：" + (road.options().infrastructure().gantry() == RoadInfrastructure.Gantry.OFF ? "关闭" : "开启"),
               x + half + 8,
               y + 114,
               half,
               () -> {
                  RoadGeometry.Settings current = this.mainSettings();
                  RoadInfrastructure.Config infra = current.options().infrastructure();
                  this.mainSettings(
                     current.options(
                        current.options()
                           .infrastructure(
                              infra.gantry(infra.gantry() == RoadInfrastructure.Gantry.OFF ? RoadInfrastructure.Gantry.FRAME : RoadInfrastructure.Gantry.OFF)
                           )
                     )
                  );
               }
            );
            if (catalog.type() == RoadProfile.Type.ORDINARY && this.panelHeight >= 280) {
               this.button("非机动车道：" + (road.options().cycle() ? "开" : "关"), x, y + 138, half, () -> {
                  RoadProfile.Options o = this.mainSettings().options();
                  this.changeExtras(o.extras(!o.cycle(), o.cycleRail(), o.curb()));
               });
               this.button("路缘石：" + (road.options().curb() ? "开" : "关"), x + half + 8, y + 138, half, () -> {
                  RoadProfile.Options o = this.mainSettings().options();
                  this.changeExtras(o.extras(o.cycle(), o.cycleRail(), !o.curb()));
               });
            }
         } else if (this.page == 2) {
            this.button(
               "调整端点：" + (this.options.adjustEndpoints() ? "允许" : "关闭"), x, y, half, () -> this.options = this.options.adjust(!this.options.adjustEndpoints())
            );
            this.button(
               "允许缩小：" + (this.options.allowShrink() ? "开" : "关"), x + half + 8, y, half, () -> this.options = this.options.shrink(!this.options.allowShrink())
            );
            AbstractSliderButton lowering = this.addRenderableWidget(
               new AbstractSliderButton(x, y + 26, w, 20, Component.empty(), this.options.maxLowering() / 64.0) {
                  {
                     this.updateMessage();
                  }

                  protected void updateMessage() {
                     this.setMessage(Component.literal("高度最多降低：" + Math.round(this.value * 64.0) + " 格"));
                  }

                  protected void applyValue() {
                     InterchangeScreen.this.edited();
                     InterchangeScreen.WORKER.cancel();
                     InterchangeScreen.generation++;
                     InterchangeScreen.this.computing = false;
                     InterchangeScreen.this.options = InterchangeScreen.this.options.lowering((double)Math.round(this.value * 64.0));
                     InterchangeScreen.this.plan = null;
                     InterchangeScreen.this.delay = 8;
                     InterchangeScreen.this.needsPlan = true;
                     InterchangeScreen.this.save.active = false;
                     InterchangeScreen.this.preview.active = false;
                     InterchangeScreen.this.stash();
                  }
               }
            );
            lowering.active = this.options.allowShrink() && this.options.adjustEndpoints();
         }

         if (this.page == 1) {
            int sx = this.left + this.control + 24;
            int sw = this.panel - this.control - 36;
            this.button("◀", sx + sw - 52, this.top + 26, 24, () -> this.stylePage = Math.floorMod(this.stylePage - 1, 8));
            this.button("▶", sx + sw - 24, this.top + 26, 24, () -> this.stylePage = (this.stylePage + 1) % 8);
            InterchangeStyleOptions.build(this, this.stylePage, sx, this.top + 54, sw, this.panelHeight - 110);
         }

         int footer = this.top + this.panelHeight - 26;
         int n = this.payload.hasUUID("Id") ? 5 : 3;
         int bw = (this.panel - 24 - 8 * (n - 1)) / n;
         this.save = (Button)this.addRenderableWidget(
            Button.builder(Component.literal(this.payload.hasUUID("Id") ? "整体更新" : "整体建造"), bx -> this.submit("interchange")).bounds(x, footer, bw, 20).build()
         );
         this.preview = (Button)this.addRenderableWidget(Button.builder(Component.literal("实景预览"), bx -> {
            this.persist();
            ManualPlannerClient.worldPreview();
            this.minecraft.setScreen(null);
         }).bounds(x + bw + 8, footer, bw, 20).build());
         this.addRenderableWidget(Button.builder(Component.literal("关闭"), bx -> this.onClose()).bounds(x + (bw + 8) * 2, footer, bw, 20).build());
         if (n == 5) {
            for (int index = 0; index < 2; index++) {
               boolean rampsOnly = index == 0;
               this.addRenderableWidget(Button.builder(Component.literal(rampsOnly ? "仅删匝道" : "删除整座"), bx -> {
                  String action = rampsOnly ? "interchangeDeleteRamps" : "interchangeDelete";
                  if (!action.equals(this.deleteConfirmation)) {
                     this.deleteConfirmation = action;
                     bx.setMessage(Component.literal(rampsOnly ? "确认删匝道" : "确认删整座"));
                     this.status = parallel ? "删除四条连接匝道；恢复原有车道，保留下层路口" : (rampsOnly ? "保留贯通主路；三/五向末端支路一并删除，仅保留端点" : "删除全部路段和设施，保留端点");
                  } else {
                     this.submit(action);
                  }
               }).bounds(x + (bw + 8) * (3 + index), footer, bw, 20).build());
            }
         }

         this.save.active = this.plan != null && !this.pending && !this.needsPlan && !this.computing;
         this.preview.active = this.plan != null && !this.needsPlan && !this.computing;
         if (parallel) {
            this.fields.get("rampWidth").active = false;

            for (GuiEventListener widget : this.children()) {
               if (widget instanceof Button) {
                  Button b = (Button)widget;
                  String label = b.getMessage().getString();
                  if (label.equals("靠左行驶") || label.equals("靠右行驶")) {
                     b.active = false;
                  }
               }
            }
         }

         this.initializing = false;
         this.delay = 1;
         this.needsPlan = this.needsPlan | this.plan == null;
      }
   }

   private void doubleView(String title, int x, int y, int w, boolean details) {
      this.addRenderableWidget(Button.builder(Component.literal((this.doubleDetails == details ? "• " : "") + title), b -> {
         if (this.capture()) {
            this.stash();
            this.doubleDetails = details;
            this.rebuildWidgets();
         }
      }).bounds(x, y, w, 20).build());
   }

   private int connectorLanes() {
      RoadLanes.Counts a = RoadLanes.counts(RoadRecord.readSettings(this.payload.getCompound("Main1")));
      RoadLanes.Counts b = RoadLanes.counts(RoadRecord.readSettings(this.payload.getCompound("Main2")));
      return Math.min(Math.min(a.forward(), a.reverse()), Math.min(b.forward(), b.reverse()));
   }

   private void doubleSettings(int x, int y, int w) {
      int half = (w - 8) / 2;
      DoubleTrumpetToll.Config c = this.options.toll();
      int n = this.connectorLanes();
      this.button(
         "中央水平段：" + (this.options.flatConnector() ? "开" : "关"),
         x,
         y,
         half,
         () -> {
            ListTag nodes = this.payload.getList("Nodes", 10);
            double a = RoadRecord.readNode(nodes.getCompound(0)).position().y();
            double b = RoadRecord.readNode(nodes.getCompound(2)).position().y();
            this.options = this.options
               .connector(!this.options.flatConnector(), Math.max(Math.ceil(Math.min(a, b)), Math.min(Math.floor(Math.max(a, b)), Math.rint((a + b) / 2.0))));
         }
      );
      this.field("水平高度 / 格", "connectorHeight", this.options.connectorHeight(), x + half + 8, y - 10, w - half - 8);
      this.fields.get("connectorHeight").active = this.options.flatConnector();
      this.button(
         "生成收费站：" + (c.enabled() ? "开" : "关"),
         x,
         y + 27,
         w,
         () -> this.options = this.options.toll(this.options.toll().enabled(!this.options.toll().enabled()))
      );
      this.button(
            c.twoWay() ? "双向收费" : "单向收费",
            x,
            y + 51,
            half,
            () -> this.options = this.options.toll(this.options.toll().mode(!this.options.toll().twoWay(), this.options.toll().reverse()))
         )
         .active = c.enabled();
      this.button(
            c.twoWay() ? "两个方向" : (c.reverse() ? "CD → AB" : "AB → CD"),
            x + half + 8,
            y + 51,
            w - half - 8,
            () -> this.options = this.options.toll(this.options.toll().mode(false, !this.options.toll().reverse()))
         )
         .active = c.enabled() && !c.twoWay();
      this.button(
            "位置：靠近 " + c.near() + " 主路",
            x,
            y + 75,
            w,
            () -> this.options = this.options
                  .toll(
                     this.options.toll().near(this.options.toll().near() == DoubleTrumpetToll.Near.AB ? DoubleTrumpetToll.Near.CD : DoubleTrumpetToll.Near.AB)
                  )
         )
         .active = c.enabled();
      this.tollSlider(x, y + 99, w, n, false, c.enabled() && (c.twoWay() || !c.reverse()));
      this.tollSlider(x, y + 123, w, n, true, c.enabled() && (c.twoWay() || c.reverse()));
      this.tollName = new EditBox(this.font, x, y + 159, w, 18, Component.literal("收费站名称（选填）"));
      this.tollName.setMaxLength(80);
      this.tollName.setValue(c.name());
      this.tollName.active = c.enabled();
      this.tollName.setResponder(v -> {
         if (!this.initializing) {
            this.edited();
            WORKER.cancel();
            generation++;
            this.computing = false;
            this.delay = 8;
            this.needsPlan = true;
            this.save.active = false;
            this.preview.active = false;
         }
      });
      this.addRenderableWidget(this.tollName);
   }

   private void tollSlider(int x, int y, int w, final int lanes, final boolean reverse, boolean enabled) {
      int count = Math.min(lanes, reverse ? this.options.toll().reverseEtc() : this.options.toll().forwardEtc());
      AbstractSliderButton slider = this.addRenderableWidget(new AbstractSliderButton(x, y, w, 20, Component.empty(), (double)count / (double)lanes) {
         {
            this.updateMessage();
         }

         protected void updateMessage() {
            int etc = (int)Math.round(this.value * (double)lanes);
            this.setMessage(Component.literal((reverse ? "CD→AB" : "AB→CD") + "  ETC " + etc + " / 人工 " + (lanes - etc)));
         }

         protected void applyValue() {
            int etc = (int)Math.round(this.value * (double)lanes);
            DoubleTrumpetToll.Config c = InterchangeScreen.this.options.toll();
            if (etc != (reverse ? c.reverseEtc() : c.forwardEtc())) {
               InterchangeScreen.this.edited();
               InterchangeScreen.WORKER.cancel();
               InterchangeScreen.generation++;
               InterchangeScreen.this.computing = false;
               InterchangeScreen.this.options = InterchangeScreen.this.options.toll(reverse ? c.etc(c.forwardEtc(), etc) : c.etc(etc, c.reverseEtc()));
               InterchangeScreen.this.plan = null;
               InterchangeScreen.this.delay = 8;
               InterchangeScreen.this.needsPlan = true;
               InterchangeScreen.this.save.active = false;
               InterchangeScreen.this.preview.active = false;
               InterchangeScreen.this.stash();
            }
         }
      });
      slider.active = enabled;
   }

   private void field(String label, String key, double value, int x, int y, int w) {
      EditBox f = new EditBox(this.font, x, y + 10, w, 18, Component.literal(label));
      f.setMaxLength(32);
      f.setValue(Double.toString(value));
      f.setResponder(v -> {
         if (!this.initializing) {
            this.edited();
            WORKER.cancel();
            generation++;
            this.computing = false;
            this.delay = 8;
            this.needsPlan = true;
            this.save.active = false;
            this.preview.active = false;
         }
      });
      this.fields.put(key, f);
      this.addRenderableWidget(f);
   }

   Button styleButton(String text, int x, int y, int w, Runnable action) {
      return this.button(text, x, y, w, action);
   }

   void styleField(String label, String key, double value, int x, int y, int w) {
      this.field(label, key, value, x, y, w);
   }

   boolean leftTraffic() {
      return this.options.leftTraffic();
   }

   void toggleTraffic() {
      this.options = this.changed(
         this.options.preset(), !this.options.leftTraffic(), this.options.lanes(), this.options.rampWidth(), this.options.adjustEndpoints()
      );

      for (int a = 1; a <= 3; a++) {
         String key = "Main" + a;
         if (this.payload.contains(key)) {
            RoadGeometry.Settings s = RoadRecord.readSettings(this.payload.getCompound(key));
            this.payload.put(key, RoadRecord.writeSettings(s.options(s.options().traffic(this.options.leftTraffic()))));
         }
      }
   }

   private double number(String key, double fallback) {
      return this.fields.containsKey(key) ? Double.parseDouble(this.fields.get(key).getValue()) : fallback;
   }

   private boolean capture() {
      try {
         this.options = new InterchangePlanner.Options(
            this.options.preset(),
            this.options.leftTraffic(),
            this.options.lanes(),
            this.number("transition", this.options.transition()),
            this.number("radius", this.options.radius()),
            this.number("clearance", this.options.clearance()),
            this.options.upper(),
            this.number("rampWidth", this.options.rampWidth()),
            this.options.adjustEndpoints(),
            this.options.allowShrink(),
            this.options.maxLowering(),
            this.options.junctionAxis(),
            this.options.loopMask(),
            this.options.allowAbove(),
            this.options.flatConnector(),
            this.number("connectorHeight", this.options.connectorHeight()),
            this.tollName == null ? this.options.toll() : this.options.toll().named(this.tollName.getValue())
         );
         if (this.page == 1) {
            RoadGeometry.Settings old = this.mainSettings();
            RoadProfile.Options next = InterchangeStyleOptions.capture(old.options(), this::number);
            double lane = RoadProfile.layout(old, old.width()).laneWidth();
            double width = this.number("mainWidth", old.width());
            if (!next.streetscape().plantings().equals(old.options().streetscape().plantings()) && Math.abs(width - old.width()) < 1.0E-7) {
               width = RoadProfile.width(old.style(), next, lane);
               this.initializing = true;
               this.fields.get("mainWidth").setValue(Double.toString(width));
               this.initializing = false;
            }

            this.mainSettings(
               new RoadGeometry.Settings(RoadGeometry.Mode.STRAIGHT, old.style(), width, this.number("thickness", old.thickness()), 0.4, 90.0)
                  .options(next)
                  .structure(old.structure())
            );
         }

         this.normalizeJunctionAxis();
         return true;
      } catch (IllegalArgumentException var7) {
         this.status = var7 instanceof NumberFormatException ? "请输入有效数字" : var7.getMessage();
         return false;
      }
   }

   private void stash() {
      this.payload.put("Options", Interchanges.write(this.options));
      draft = this.payload.copy();
      drafts.put(this.payload.getLongArray("Points").length, draft);
   }

   private void persist() {
      if (this.capture()) {
         this.stash();
      }
   }

   public void tick() {
      super.tick();
      if (this.delay > 0) {
         this.delay--;
      }

      if (this.delay == 0 && this.needsPlan && !this.computing) {
         this.calculate();
      }
   }

   private void calculate() {
      if (HostAxes.retiredInterchange(this.payload)) {
         this.needsPlan = false;
         this.status = "请选择两条直线主路";
      } else if (!this.capture()) {
         this.needsPlan = false;
      } else {
         this.persist();
         this.plan = null;
         this.save.active = false;
         this.preview.active = false;
         this.status = "正在检查全部转向、15% 纵坡与交叉净高…";
         this.computing = true;
         this.needsPlan = false;
         long token = ++generation;
         CompoundTag inputs = this.payload.copy();
         WORKER.submit(
            () -> Interchanges.plan(inputs),
            (result, error) -> Minecraft.getInstance()
                  .execute(
                     () -> {
                        if (token == generation && draft != null) {
                           this.computing = false;
                           if (this.needsPlan) {
                              this.delay = 1;
                           } else if (error != null) {
                              Throwable cause = error.getCause() == null ? error : error.getCause();
                              this.status = cause.getMessage() == null ? "预设无法放入当前范围" : cause.getMessage();
                              ClientRoads.preview = null;
                              ClientRoads.nodePreviews = List.of();
                           } else {
                              this.plan = result;
                              this.updateHeightLabel();
                              ClientRoads.nodePreviews = result.legs().stream().map(l -> RoadRenderMesh.simplify(l.mesh())).toList();
                              ClientRoads.preview = ClientRoads.nodePreviews.get(0);

                              this.status = result.movements()
                                 + " 条转向 · 最小半径 "
                                 + Math.round(result.minRadius())
                                 + " 格 · 最大坡度 "
                                 + String.format(
                                    Locale.ROOT, "%.1f%%", result.legs().stream().mapToDouble(l -> RoadGrades.maximum(l.mesh())).max().orElse(0.0) * 100.0
                                 )
                                 + " · 最高 Y="
                                 + Math.round(result.highest())
                                 + switch (this.options.preset()) {
                                    case DIAMOND, PARCLO, SPUI -> ParallelSpui.present(this.payload)
                                    ? " · 沿用下层已有路口"
                                    : " · " + (this.options.junctionAxis() == 0 ? "AB" : "CD") + " 主路设路口";
                                    case ROUNDABOUT -> " · 双主路直通 / 独立中层环道";
                                    case STACK -> " · 四层堆栈";
                                    default -> " · 分层匝道";
                                 };
                              if (this.payload.getBoolean("Partial")) {
                                 this.status = this.status + " · 更新将补回已删除路段";
                              }

                              if (this.adjusted()) {
                                 this.status = this.status + " · 端点将调整，见端点页";
                              }

                              this.save.active = !this.pending;
                              this.preview.active = true;
                           }
                        }
                     }
                  )
         );
      }
   }

   public void removed() {
      WORKER.cancel();
      generation++;
      if (this.computing) {
         this.needsPlan = true;
      }

      this.computing = false;
      super.removed();
   }

   private void updateHeightLabel() {
      if (this.heights != null && ParallelSpui.present(this.payload)) {
         this.heights.setMessage(Component.literal("路口：沿用下层已有路口"));
      } else if (this.heights != null && this.plan != null && this.plan.anchors().size() >= 3 && this.plan.anchors().size() <= 4) {
         ListTag old = this.payload.getList("Nodes", 10);
         List<RoadGeometry.Node> fitted = this.plan.anchors();
         double before = Math.abs(RoadRecord.readNode(old.getCompound(0)).position().y() - RoadRecord.readNode(old.getCompound(2)).position().y());
         double after = Math.abs(fitted.get(0).position().y() - fitted.get(2).position().y());
         if (this.options.preset().three) {
            double upper = Math.max(fitted.get(0).position().y(), fitted.get(2).position().y());
            this.heights
               .setMessage(Component.literal(String.format(Locale.ROOT, "高差 %.0f · %s", after, this.plan.highest() > upper + 1.0E-6 ? "匝道上跨" : "层间布置")));
         } else {
            this.heights.setMessage(Component.literal(String.format(Locale.ROOT, "高差 %.0f → %.0f（已拟合）", before, after)));
         }
      }
   }

   private void submit(String action) {
      boolean deletion = !action.equals("interchange");
      if (!this.pending && (deletion || this.plan != null && !this.needsPlan && !this.computing)) {
         this.persist();
         CompoundTag t = new CompoundTag();

         for (String key : List.of("Id", "Points", "Options", "Main1", "Main2", "Main3")) {
            if (this.payload.contains(key)) {
               t.put(key, this.payload.get(key).copy());
            }
         }

         t.putString("Action", action);
         this.pending = true;
         this.save.active = false;
         t.putInt("SourceAxesHash", HostAxes.signature(this.payload));
         t.put("SourceNodes", this.payload.getList("Nodes", 10).copy());
         if (this.plan != null) {
            ListTag targets = new ListTag();
            this.plan.anchors().forEach(node -> targets.add(RoadRecord.writeNode(node)));
            t.put("FittedNodes", targets);
         }

         this.status = deletion ? "正在删除并恢复地形…" : (this.minecraft.hasSingleplayerServer() ? "正在检查地形并建造立交…" : "正在请求服务器检查并建造立交…");
         ClientRoads.sendAction(t);
      }
   }

   void completed() {
      this.pending = false;
   }

   public void failed(String reason) {
      this.pending = false;
      this.status = reason;
      this.save.active = this.plan != null && !HostAxes.retiredInterchange(this.payload);
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void onClose() {
      generation++;
      WORKER.cancel();
      this.persist();
      ClientRoads.preview = null;
      ClientRoads.nodePreviews = List.of();
      super.onClose();
   }

   private boolean adjusted() {
      if (this.plan == null) {
         return false;
      } else {
         ListTag old = this.payload.getList("Nodes", 10);

         for (int i = 0; i < old.size(); i++) {
            if (RoadRecord.readNode(old.getCompound(i)).position().distance(this.plan.anchors().get(i).position()) > 1.0E-6) {
               return true;
            }
         }

         return false;
      }
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
      super.renderBackground(g, mouseX, mouseY, partial);
      g.fill(this.left, this.top, this.left + this.panel, this.top + this.panelHeight, -283630288);
      g.drawString(this.font, this.title, this.left + 12, this.top + 10, 16777215, false);
      if (this.page == 0 && this.doubleDetails && this.options.preset() == InterchangePlanner.Preset.DOUBLE_TRUMPET) {
         g.fill(this.left + this.control + 20, this.top + 26, this.left + this.panel - 8, this.top + this.panelHeight - 64, -16181729);
      }

      super.render(g, mouseX, mouseY, partial);

      for (EditBox f : this.fields.values()) {
         g.drawString(this.font, f.getMessage(), f.getX(), f.getY() - 9, 13163237, false);
      }

      int statusY = this.top + this.panelHeight - (this.panelHeight < 260 ? 42 : 54);
      List<Component> endpointHint = null;
      this.endpointTextBottom = 0;
      if (this.page == 2) {
         ListTag old = this.payload.getList("Nodes", 10);
         g.drawString(
            this.font,
            this.options.adjustEndpoints() ? (this.options.allowShrink() ? "自动采用搜索到的最小可行范围" : "原布点不可行时向外调整") : "自动调整已关闭",
            this.left + 12,
            this.top + 105,
            16768145,
            false
         );
         int y = this.top + 117;
         int room = statusY - 6 - y;
         boolean stacked = room >= old.size() * 22 + 13;
         int row = stacked ? 22 : Math.max(10, Math.min(12, room / Math.max(1, old.size())));

         for (int i = 0; i < old.size(); i++) {
            RoadGeometry.V before = RoadRecord.readNode(old.getCompound(i)).position();
            RoadGeometry.V after = this.plan == null ? before : this.plan.anchors().get(i).position();
            String original = String.format(Locale.ROOT, "%c 原：%.1f, %.1f, %.1f", 65 + i, before.x(), before.y(), before.z());
            String updated = String.format(Locale.ROOT, "%c → %.1f, %.1f, %.1f", 65 + i, after.x(), after.y(), after.z());
            if (stacked) {
               this.endpointLine(g, original, y, 11123400);
            }

            this.endpointLine(g, updated, y + (stacked ? 11 : 0), before.distance(after) > 1.0E-6 ? 16765564 : 12576721);
            if ((!stacked || this.font.width(original) > this.control || this.font.width(updated) > this.control)
               && mouseX >= this.left + 12
               && mouseX < this.left + 12 + this.control
               && mouseY >= y
               && mouseY < y + row) {
               endpointHint = List.of(Component.literal(original), Component.literal(updated));
            }

            y += row;
         }

         if (y + 13 <= statusY - 6) {
            this.endpointLine(g, "整体建造 / 更新时才移动端点", y + 4, 14739695);
         }
      }

      if (this.page == 1) {
         g.drawString(
            this.font,
            InterchangeStyleOptions.TITLES[this.stylePage] + " · " + (this.stylePage + 1) + "/8",
            this.left + this.control + 24,
            this.top + 14,
            16777215,
            false
         );
      } else if (this.page == 0 && this.doubleDetails && this.options.preset() == InterchangePlanner.Preset.DOUBLE_TRUMPET) {
         if (this.tollName != null) {
            g.drawString(this.font, this.tollName.getMessage(), this.tollName.getX(), this.tollName.getY() - 10, 13163237, false);
         }

         if (this.panelHeight >= 280) {
            g.drawString(this.font, "通道总数固定，每方向 " + this.connectorLanes() + " 条", this.left + this.control + 24, this.top + 220, 11520211, false);
         }
      } else {
         this.map(g, this.left + this.control + 24, this.top + 28, this.panel - this.control - 36, this.panelHeight - 92);
      }

      List<FormattedCharSequence> lines = this.font.split(Component.literal(this.status == null ? "" : this.status), this.panel - 24);

      for (int i = 0; i < Math.min(this.panelHeight < 260 ? 1 : 2, lines.size()); i++) {
         g.drawString(this.font, lines.get(i), this.left + 12, statusY + i * 10, this.plan == null ? 16758435 : 13167066, false);
      }

      if (lines.size() > (this.panelHeight < 260 ? 1 : 2) && mouseY >= statusY && mouseY < statusY + 22) {
         g.renderTooltip(this.font, Component.literal(this.status), mouseX, mouseY);
      }

      if (endpointHint != null) {
         g.renderComponentTooltip(this.font, endpointHint, mouseX, mouseY);
      }
   }

   private void endpointLine(GuiGraphics g, String text, int y, int color) {
      String visible = this.font.width(text) <= this.control ? text : this.font.plainSubstrByWidth(text, this.control - 8) + "…";
      g.drawString(this.font, visible, this.left + 12, y, color, false);
      this.endpointTextBottom = Math.max(this.endpointTextBottom, y + 9);
   }

   private void map(GuiGraphics g, int x, int y, int w, int h) {
      g.fill(x, y, x + w, y + h, -16181729);
      if (this.plan == null) {
         g.drawString(this.font, "方案生成后显示整体俯视图", x + 8, y + 8, 11520211, false);
      } else {
         List<RoadGeometry.Mesh> meshes = this.plan.legs().stream().map(InterchangePlanner.Leg::mesh).toList();
         double minX = meshes.stream().mapToDouble(m -> m.min().x()).min().orElse(0.0);
         double minZ = meshes.stream().mapToDouble(m -> m.min().z()).min().orElse(0.0);
         double maxX = meshes.stream().mapToDouble(m -> m.max().x()).max().orElse(1.0);
         double maxZ = meshes.stream().mapToDouble(m -> m.max().z()).max().orElse(1.0);
         double scale = Math.min((double)(w - 20) / (maxX - minX), (double)(h - 20) / (maxZ - minZ));
         int originX = x + (int)(((double)w - (maxX - minX) * scale) / 2.0);
         int originY = y + (int)(((double)h - (maxZ - minZ) * scale) / 2.0);
         List<RoadGeometry.Sample> samples = new ArrayList<>();

         for (RoadGeometry.Mesh m : meshes) {
            for (int i = 0; i < m.samples().size(); i += 3) {
               samples.add(m.samples().get(i));
            }
         }

         samples.sort(Comparator.comparingDouble(pxx -> pxx.center().y()));

         for (RoadGeometry.Sample p : samples) {
            int px = originX + (int)((p.center().x() - minX) * scale);
            int py = originY + (int)((p.center().z() - minZ) * scale);
            int r = Math.max(1, (int)(p.halfWidth() * scale));
            int shade = (int)Math.max(0.0, Math.min(150.0, (p.center().y() - this.plan.center().y()) * 5.0));
            g.fill(px - r, py - r, px + r + 1, py + r + 1, -12083262 + (shade << 16));
         }

         for (InterchangePlanner.Leg leg : this.plan.legs()) {
            if (leg.toll() != null) {
               List<RoadGeometry.Sample> edge = leg.mesh().samples();

               for (int i = 1; i < edge.size(); i++) {
                  for (int side : new int[]{-1, 1}) {
                     mapLine(
                        g,
                        edge.get(i - 1).at((double)side * edge.get(i - 1).halfWidth(), 0.0),
                        edge.get(i).at((double)side * edge.get(i).halfWidth(), 0.0),
                        originX,
                        originY,
                        minX,
                        minZ,
                        scale
                     );
                  }
               }

               for (RoadGeometry.Sample p : List.of(edge.get(0), edge.get(edge.size() - 1))) {
                  mapLine(g, p.at(-p.halfWidth(), 0.0), p.at(p.halfWidth(), 0.0), originX, originY, minX, minZ, scale);
               }
            }
         }

         ListTag nodes = this.payload.getList("Nodes", 10);

         for (int i = 0; i < nodes.size(); i++) {
            RoadGeometry.V p = this.plan.anchors().get(i).position();
            int px = originX + (int)((p.x() - minX) * scale);
            int py = originY + (int)((p.z() - minZ) * scale);
            g.drawString(
               this.font, String.valueOf((char)(65 + i)), Math.min(x + w - 9, Math.max(x + 2, px)), Math.min(y + h - 10, Math.max(y + 2, py)), 16777215, false
            );
         }
      }
   }

   private static void mapLine(GuiGraphics g, RoadGeometry.V a, RoadGeometry.V b, int x, int y, double minX, double minZ, double scale) {
      double ax = (double)x + (a.x() - minX) * scale;
      double ay = (double)y + (a.z() - minZ) * scale;
      double bx = (double)x + (b.x() - minX) * scale;
      double by = (double)y + (b.z() - minZ) * scale;
      int steps = Math.max(1, (int)Math.ceil(Math.max(Math.abs(bx - ax), Math.abs(by - ay))));

      for (int i = 0; i <= steps; i++) {
         double t = (double)i / (double)steps;
         int px = (int)Math.round(ax + (bx - ax) * t);
         int py = (int)Math.round(ay + (by - ay) * t);
         g.fill(px, py, px + 2, py + 2, -11442);
      }
   }
}
