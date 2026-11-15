package com.sora.splineroads.client;

import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneRampGrade;
import com.sora.splineroads.core.RoadConflictIds;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.world.LanePointCodec;
import com.sora.splineroads.world.RoadRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

public final class LaneRampScreen extends Screen {
   private static LaneRampScreen active;
   private static long sequence;
   private final CompoundTag payload;
   private final int rampLanes;
   private LanePoints.Path path;
   private boolean pending;
   private boolean gradeOverride;
   private boolean allowTunnel;
   private LanePoints.Departure departure;
   private LanePoints.Arrival arrival;
   private LanePoints.Elevation elevation;
   private LanePoints.Landing landing;
   private List<RoadGeometry.Mesh> checkedRoads = List.of();
   private List<RoadGeometry.Mesh> conflictRoads = List.of();
   private String radiusText;
   private String transitionText;
   private EditBox radius;
   private EditBox transition;
   private Button build;
   private Button previewButton;
   private Button details;
   private Button back;
   private Button worldButton;
   private Button deleteButton;
   private boolean diagnostic;
   private UUID token;
   private RoadGeometry.Mesh checkedMesh;
   private final List<RoadGeometry.Mesh> contextRoads = new ArrayList<>();
   private long request;
   private long pendingSince;
   private int x;
   private int y;
   private int control;
   private int panelWidth;
   private int panelHeight;
   private int widgetHeight;
   private double uiScale;
   private String status;

   public LaneRampScreen(CompoundTag t) {
      super(Component.literal(t.hasUUID("Id") ? "编辑匝道" : "匝道连接器"));
      this.request = ++sequence;
      this.widgetHeight = 20;
      this.uiScale = 1.0;
      this.status = "设置完成后点击计算预览；实景预览可切换到世界。Shift＋右键清除选点。";
      this.payload = t.copy();
      LanePoints.Options o = LanePointCodec.options(t.getCompound("Options"));
      this.rampLanes = o.lanes();
      this.path = o.path();
      this.departure = o.departure();
      this.arrival = o.arrival();
      this.elevation = o.elevation();
      this.landing = o.landing();
      this.gradeOverride = o.gradeOverride();
      this.allowTunnel = o.allowTunnel();
      this.radiusText = number(o.radius());
      this.transitionText = number(o.transition());
      if (t.getBoolean("SourceRamp")) {
         this.departure = LanePoints.Departure.BRANCH;
      } else if (t.getBoolean("SourceTunnel") || t.getBoolean("SourceHighway")) {
         this.departure = LanePoints.Departure.EXTRA;
      }

      if (t.getBoolean("TargetRamp")) {
         this.arrival = LanePoints.Arrival.FLOW;
      } else if (t.getBoolean("TargetTunnel") || t.getBoolean("TargetHighway")) {
         this.arrival = LanePoints.Arrival.EXTRA;
      }

      for (Tag value : t.getList("ContextRoads", 10)) {
         this.contextRoads.add(RoadRecord.load((CompoundTag)value).mesh());
      }

      if (t.contains("Road")) {
         this.checkedMesh = RoadRecord.load(t.getCompound("Road")).mesh();
      }
   }

   private static LanePoints.Arrival nextArrival(LanePoints.Arrival current) {
      List<LanePoints.Arrival> choices = List.of(LanePoints.Arrival.MERGE, LanePoints.Arrival.ADD, LanePoints.Arrival.EXTRA, LanePoints.Arrival.FLOW);
      return choices.get((choices.indexOf(current) + 1) % choices.size());
   }

   private static String number(double value) {
      return String.format(Locale.ROOT, "%s", value == (double)((int)value) ? Integer.toString((int)value) : Double.toString(value));
   }

   public static void open(CompoundTag t) {
      if (active == null
         || !t.hasUUID("Id")
         || !active.payload.hasUUID("Id")
         || !t.getUUID("Id").equals(active.payload.getUUID("Id"))
         || t.getInt("Signature") != active.payload.getInt("Signature")) {
         clear();
         active = new LaneRampScreen(t);
      }

      resume();
   }

   public static void resume() {
      if (active != null) {
         ClientRoads.beginEditor(active);
         active.showWorld();
         Minecraft.getInstance().setScreen(active);
      }
   }

   private void showWorld() {
      if (ClientRoads.ownsPreview(this)) {
         ClientRoads.conflictPreview = !this.conflictRoads.isEmpty();
         ClientRoads.preview = this.conflictRoads.isEmpty() ? this.checkedMesh : this.conflictRoads.get(0);
         ClientRoads.nodePreviews = this.conflictRoads.isEmpty() ? this.checkedRoads : this.conflictRoads;
      }
   }

   public static void resume(CompoundTag t) {
      if (active == null && t.contains("Fallback")) {
         open(t.getCompound("Fallback"));
      } else {
         resume();
      }
   }

   public static boolean clear() {
      boolean owned = ClientRoads.endEditor(active);
      if (owned) {
         ClientRoads.conflictPreview = false;
         ClientRoads.preview = null;
         ClientRoads.nodePreviews = List.of();
      }

      active = null;
      sequence++;
      return owned;
   }

   public static void checkedReply(CompoundTag t) {
      if (active != null) {
         active.checked(t);
      }
   }

   public static void checkedReply(CompoundTag t, RoadPreviewData prepared) {
      if (active != null) {
         active.checked(t, prepared);
      }
   }

   static void deliveryRecovery() {
      if (active != null && active.pending && active.token == null) {
         active.cancelPreview();
         active.request = ++sequence;
         active.failed("预览传输繁忙，请重新预览");
      }
   }

   public static void failedPending(String message, CompoundTag reply) {
      if (active != null && active.pending && active.token != null) {
         active.failed(message, reply);
      }
   }

   private int row(int offset) {
      return this.y + (int)Math.round((double)offset * this.uiScale);
   }

   protected void init() {
      this.x = 12;
      this.y = Math.max(6, (this.height - 360) / 2);
      this.panelWidth = this.width - 24;
      this.panelHeight = this.height - 2 * this.y;
      this.control = Math.min(330, (this.panelWidth - 36) / 2);
      this.uiScale = Math.min(1.0, (double)(this.height - 24) / 340.0);
      this.widgetHeight = Math.max(14, (int)Math.round(20.0 * this.uiScale));

      for (LanePoints.Path choice : LanePoints.Path.values()) {
         this.addRenderableWidget(Button.builder(Component.literal((choice == this.path ? "● " : "") + choice.label), b -> {
            this.path = choice;
            this.invalidate();
            this.rebuildWidgets();
         }).bounds(this.x + 12 + choice.ordinal() * (this.control / 5), this.row(51), this.control / 5 - 2, this.widgetHeight).build());
      }

      Button sourceButton = (Button)this.addRenderableWidget(
         Button.builder(
               Component.literal(
                  "汇出："
                     + this.departure.label
                     + (
                        this.payload.getBoolean("SourceRamp")
                           ? "（匝道）"
                           : (this.payload.getBoolean("SourceHighway") ? "（高速）" : (this.payload.getBoolean("SourceTunnel") ? "（隧道）" : ""))
                     )
               ),
               b -> {
                  this.departure = LanePoints.Departure.values()[(this.departure.ordinal() + 1) % LanePoints.Departure.values().length];
                  this.invalidate();
                  this.rebuildWidgets();
               }
            )
            .bounds(this.x + 12, this.row(81), this.control / 2 - 5, this.widgetHeight)
            .build()
      );
      Button targetButton = (Button)this.addRenderableWidget(
         Button.builder(
               Component.literal(
                  "汇入："
                     + this.arrival.label
                     + (
                        this.payload.getBoolean("TargetRamp")
                           ? "（匝道）"
                           : (this.payload.getBoolean("TargetHighway") ? "（高速）" : (this.payload.getBoolean("TargetTunnel") ? "（隧道）" : ""))
                     )
               ),
               b -> {
                  this.arrival = nextArrival(this.arrival);
                  this.invalidate();
                  this.rebuildWidgets();
               }
            )
            .bounds(this.x + 17 + this.control / 2, this.row(81), this.control / 2 - 5, this.widgetHeight)
            .build()
      );
      sourceButton.active = !this.payload.getBoolean("SourceHighway") && !this.payload.getBoolean("SourceTunnel") && !this.payload.getBoolean("SourceRamp");
      targetButton.active = !this.payload.getBoolean("TargetHighway") && !this.payload.getBoolean("TargetTunnel") && !this.payload.getBoolean("TargetRamp");
      this.addRenderableWidget(Button.builder(Component.literal(this.elevation.label), b -> {
         this.elevation = LanePoints.Elevation.values()[(this.elevation.ordinal() + 1) % LanePoints.Elevation.values().length];
         this.invalidate();
         this.rebuildWidgets();
      }).bounds(this.x + 12, this.row(108), this.control / 2 - 5, this.widgetHeight).build());
      this.addRenderableWidget(Button.builder(Component.literal(this.landing.label), b -> {
         this.landing = LanePoints.Landing.values()[(this.landing.ordinal() + 1) % LanePoints.Landing.values().length];
         this.invalidate();
         this.rebuildWidgets();
      }).bounds(this.x + 17 + this.control / 2, this.row(108), this.control / 2 - 5, this.widgetHeight).build());
      this.radius = new EditBox(this.font, this.x + 12, this.row(146), this.control / 2 - 5, this.widgetHeight, Component.literal("参考半径"));
      this.radius.setMaxLength(32);
      this.radius.setValue(this.radiusText);
      this.radius.setResponder(s -> {
         this.radiusText = s;
         this.invalidate();
      });
      this.radius.setTooltip(Tooltip.create(Component.literal("规划参考半径：圆弧按此半径布线；直接连接及端口拟合的实际最小半径以已检查预览为准。")));
      this.addRenderableWidget(this.radius);
      this.transition = new EditBox(
         this.font, this.x + 17 + this.control / 2, this.row(146), this.control / 2 - 5, this.widgetHeight, Component.literal("过渡长度")
      );
      this.transition.setMaxLength(32);
      this.transition.setValue(this.transitionText);
      this.transition.setResponder(s -> {
         this.transitionText = s;
         this.invalidate();
      });
      this.addRenderableWidget(this.transition);
      this.addRenderableWidget(Button.builder(Component.literal(this.gradeOverride ? "坡比超限：开（普通25% / 涉高速20%）" : "坡比超限：关（普通20% / 涉高速15%）"), b -> {
         this.gradeOverride = !this.gradeOverride;
         this.invalidate();
         this.rebuildWidgets();
      }).bounds(this.x + 12, this.row(173), this.control, this.widgetHeight).build());
      this.addRenderableWidget(Button.builder(Component.literal("允许隧道：" + (this.allowTunnel ? "开" : "关") + "（地下接头始终允许）"), b -> {
         this.allowTunnel = !this.allowTunnel;
         this.invalidate();
         this.rebuildWidgets();
      }).bounds(this.x + 12, this.row(197), this.control, this.widgetHeight).build());
      int third = (this.control - 8) / 3;
      this.previewButton = (Button)this.addRenderableWidget(
         Button.builder(Component.literal("计算预览"), b -> this.preview()).bounds(this.x + 12, this.row(227), third, this.widgetHeight).build()
      );
      this.previewButton.active = !this.pending;
      this.build = (Button)this.addRenderableWidget(
         Button.builder(Component.literal(this.payload.hasUUID("Id") ? "保存匝道" : "建造 A → B"), b -> this.submit())
            .bounds(this.x + 20 + third * 2, this.row(227), third, this.widgetHeight)
            .build()
      );
      this.build.active = this.token != null && !this.pending;
      this.back = (Button)this.addRenderableWidget(Button.builder(Component.literal(this.pending && this.token == null ? "取消计算并返回" : "返回实景 / 预览"), b -> {
         if (this.pending && this.token == null) {
            this.cancelPreview();
            this.invalidate();
         }

         this.showWorld();
         ManualPlannerClient.worldPreview();
         this.onClose();
      }).bounds(this.x + 16 + third, this.row(227), third, this.widgetHeight).build());
      this.worldButton = this.back;
      if (ManualPlannerClient.hasMap()) {
         this.addRenderableWidget(Button.builder(Component.literal("规划地图"), b -> {
            this.showWorld();
            ManualPlannerClient.mapPreview(this);
         }).bounds(this.x + 12, this.row(251), third, this.widgetHeight).build());
      }

      this.details = (Button)this.addRenderableWidget(
         Button.builder(Component.literal("失败详情"), b -> Minecraft.getInstance().setScreen(new LaneRampScreen.FailureDetails(this, this.status)))
            .bounds(this.x + 16 + third, this.row(251), third, this.widgetHeight)
            .build()
      );
      this.details.visible = this.diagnostic;
      this.details.active = this.diagnostic;
      if (this.payload.hasUUID("Id")) {
         this.deleteButton = (Button)this.addRenderableWidget(Button.builder(Component.literal("删除匝道"), b -> {
            if (this.pending) {
               this.cancelPreview();
            }

            this.invalidate();
            CompoundTag t = new CompoundTag();
            t.putString("Action", "laneRampDeletePreview");
            t.putUUID("Id", this.payload.getUUID("Id"));
            t.putInt("Signature", this.payload.getInt("Signature"));
            t.put("From", this.payload.getCompound("From").copy());
            t.put("To", this.payload.getCompound("To").copy());
            ClientRoads.sendAction(t);
         }).bounds(this.x + 20 + third * 2, this.row(251), third, this.widgetHeight).build());
         this.deleteButton.active = !this.pending;
      }
   }

   private void cancelPreview() {
      CompoundTag t = new CompoundTag();
      t.putString("Action", "laneRampCancel");
      t.putLong("Request", this.request);
      ClientRoads.sendAction(t);
   }

   private void invalidate() {
      ClientRoads.editorChanged(this);
      this.conflictRoads = List.of();
      ClientRoads.conflictPreview = false;
      if (this.pending) {
         this.cancelPreview();
      }

      this.diagnostic = false;
      if (this.details != null) {
         this.details.visible = false;
         this.details.active = false;
      }

      this.token = null;
      this.checkedMesh = null;
      this.checkedRoads = List.of();
      this.pending = false;
      if (this.build != null) {
         this.build.active = false;
      }

      if (this.previewButton != null) {
         this.previewButton.active = true;
      }

      ClientRoads.preview = null;
      ClientRoads.nodePreviews = List.of();
      this.request = ++sequence;

      this.status = switch (this.departure) {
         case TEMPORARY -> "保留车道分离：直连匝道，主路暂时关闭该车道；净空安全后恢复。地面封闭绿化、高架直角孔区。请预览。";
         case DETACH -> "整车道分离：直连匝道，主路下游取消该车道。请预览。";
         case BRANCH -> "普通分流：原车道继续直行；不是先关闭再恢复。请预览。";
         case EXTRA -> "额外扩出：保持既有车道，另拓出匝道。请预览。";
      };
   }

   private CompoundTag command(String action) {
      CompoundTag t = new CompoundTag();
      t.putString("Action", action);
      t.put("From", this.payload.getCompound("From").copy());
      t.put("To", this.payload.getCompound("To").copy());
      if (this.payload.hasUUID("Id")) {
         t.putUUID("Id", this.payload.getUUID("Id"));
         t.putInt("Signature", this.payload.getInt("Signature"));
      }

      t.put(
         "Options",
         LanePointCodec.options(
            new LanePoints.Options(
               this.path,
               this.departure,
               this.arrival,
               Double.parseDouble(this.radiusText),
               Double.parseDouble(this.transitionText),
               this.elevation,
               this.landing,
               this.gradeOverride,
               this.allowTunnel,
               this.rampLanes
            )
         )
      );
      t.putLong("Request", this.request);
      return t;
   }

   private void preview() {
      try {
         this.invalidate();
         CompoundTag t = this.command("laneRampPreview");
         this.pending = true;
         this.pendingSince = System.nanoTime();
         this.previewButton.active = false;
         if (this.deleteButton != null) {
            this.deleteButton.active = false;
         }

         ClientRoads.sendAction(t);
         this.status = "正在后台检查方向、汇入范围与净空，可取消计算…";
      } catch (IllegalArgumentException var2) {
         this.failed(var2.getMessage());
      }
   }

   public void checked(CompoundTag t) {
      this.checked(t, null);
   }

   private void checked(CompoundTag t, RoadPreviewData prepared) {
      if (t.getLong("Request") == this.request) {
         this.pending = false;
         if (t.contains("Error")) {
            if (prepared == null) {
               this.failed(t.getString("Error"), t);
            } else {
               this.failed(t.getString("Error"), prepared.conflicts());
            }
         } else {
            try {
               RoadPreviewData.Lane ready = prepared == null ? RoadPreviewData.preparePayload(t).lane() : prepared.lane();
               RoadRecord r = ready.road();
               this.checkedMesh = ready.mesh();
               this.checkedRoads = ready.roads();
               this.showWorld();
               this.token = t.getUUID("Token");
               this.build.active = true;
               this.previewButton.active = true;
               this.worldButton.active = true;
               if (this.deleteButton != null) {
                  this.deleteButton.active = true;
               }

               double offset = t.getDouble("TargetOffset");
               String landing = this.payload.getCompound("To").hasUUID("Junction") ? "" : String.format(Locale.ROOT, " 汇入口：沿 B 行驶方向 %+.1f 格。", offset);
               String grade = t.contains("GradeLimit") ? " 坡比上限 " + LaneRampGrade.label(t.getDouble("GradeLimit")) + "。" : "";
               if (t.contains("GradeAvailable")) {
                  grade = String.format(
                     Locale.ROOT,
                     " 水平 %.1f / 可布坡 %.1f 格，当前变程需≥%.1f 格；实际最大 %.2f%%，上限 %s。",
                     t.getDouble("GradeHorizontal"),
                     t.getDouble("GradeAvailable"),
                     t.getDouble("GradeMinimum"),
                     t.getDouble("ActualGrade") * 100.0,
                     LaneRampGrade.label(t.getDouble("GradeLimit"))
                  );
               }

               String closure = t.getBoolean("TemporaryClosure")
                  ? (
                     t.getBoolean("RectangularClosure")
                        ? String.format(Locale.ROOT, " 主路从 A 暂时关闭此车道，下游 %.1f 格处恢复；封闭区不收尖。", t.getDouble("RestoredAfter"))
                        : String.format(Locale.ROOT, " 主路从A暂时关闭此车道，下游 %.1f 格开始恢复、%.1f 格恢复完整。", t.getDouble("ReopenAfter"), t.getDouble("RestoredAfter"))
                  )
                  : "";
               String targetClosure = t.getBoolean("TargetClosure")
                  ? String.format(Locale.ROOT, " 目标车道在汇入口前 %.1f 格开始封闭，到匝道完整接入后开放；其他车道保持通行。", t.getDouble("TargetClosedBefore"))
                  : "";
               double minimumRadius = ready.minimumRadius();
               String radiusLabel = Double.isFinite(minimumRadius) ? String.format(Locale.ROOT, "%.2f 格", minimumRadius) : "直线";
               this.status = String.format(
                  Locale.ROOT,
                  "预览有效：%s，长 %.1f 格，实际最小半径 %s。%s%s%s%s 右键空气返回并建造。",
                  t.contains("ResolvedPath") ? LanePoints.Path.valueOf(t.getString("ResolvedPath")).label : this.path.label,
                  r.mesh().length(),
                  radiusLabel,
                  landing,
                  closure,
                  targetClosure,
                  grade
               );
            } catch (IllegalArgumentException var14) {
               this.failed(var14.getMessage());
            }
         }
      }
   }

   private void submit() {
      try {
         if (this.token == null || this.pending) {
            return;
         }

         CompoundTag t = this.command("laneRamp");
         t.putUUID("Token", this.token);
         this.pending = true;
         this.pendingSince = System.nanoTime();
         this.status = "正在建造：服务端校验接头、生成设施并写入道路，请勿重复提交。";
         this.build.active = false;
         this.previewButton.active = false;
         ClientRoads.sendAction(t);
      } catch (IllegalArgumentException var2) {
         this.failed(var2.getMessage());
      }
   }

   public void failed(String s) {
      this.failed(s, new CompoundTag());
   }

   void completed() {
      this.pending = false;
      this.token = null;
      this.status = "匝道已建成";
      if (this.build != null) {
         this.build.active = false;
      }

      if (this.previewButton != null) {
         this.previewButton.active = false;
      }
   }

   public void failed(String s, CompoundTag reply) {
      ArrayList<RoadGeometry.Mesh> roads = new ArrayList<>();

      for (Tag value : reply.getList("ConflictRoads", 10)) {
         try {
            roads.add(RoadRecord.load((CompoundTag)value).mesh());
         } catch (IllegalArgumentException var7) {
         }
      }

      this.failed(s, List.copyOf(roads));
   }

   void failed(String s, List<RoadGeometry.Mesh> roads) {
      if (this.deleteButton != null) {
         this.deleteButton.active = true;
      }

      this.conflictRoads = roads;
      this.status = RoadConflictIds.display(s) + (roads.isEmpty() ? "" : "；冲突道路已标红，返回实景查看，Shift＋右键清除。");
      this.diagnostic = true;
      if (this.details != null) {
         this.details.visible = true;
         this.details.active = true;
      }

      this.pending = false;
      if (this.build != null) {
         this.build.active = false;
      }

      if (this.previewButton != null) {
         this.previewButton.active = true;
      }

      this.token = null;
      this.checkedMesh = null;
      this.checkedRoads = List.of();
      this.showWorld();
   }

   void failureGeometry(String message, List<RoadGeometry.Mesh> roads) {
      this.conflictRoads = roads;
      this.status = RoadConflictIds.display(message) + (roads.isEmpty() ? "" : "；冲突道路已标红，返回实景查看，Shift＋右键清除。");
      this.showWorld();
   }

   private static String brief(String message) {
      int end = message.indexOf(65307);
      if (end < 0) {
         end = message.indexOf(12290);
      }

      String first = end > 0 ? message.substring(0, end) : message;
      return (first.length() > 70 ? first.substring(0, 70) + "…" : first) + "。点击“失败详情”查看冲突坐标和完整原因。";
   }

   public void onClose() {
      super.onClose();
   }

   public boolean isPauseScreen() {
      return false;
   }

   private void map(GuiGraphics g, int left, int top, int w, int h) {
      g.fill(left, top, left + w, top + h, -16181729);
      ArrayList<RoadGeometry.Mesh> roads = new ArrayList<>(this.contextRoads);
      roads.addAll(this.checkedRoads);
      roads.addAll(this.conflictRoads);
      if (this.checkedMesh != null) {
         roads.add(this.checkedMesh);
      }

      if (roads.isEmpty()) {
         g.drawString(this.font, "选择 A、B 后计算俯视预览", left + 8, top + 30, 11520211, false);
      } else {
         double minX = roads.stream().mapToDouble(m -> m.min().x()).min().orElse(0.0);
         double maxX = roads.stream().mapToDouble(m -> m.max().x()).max().orElse(1.0);
         double minZ = roads.stream().mapToDouble(m -> m.min().z()).min().orElse(0.0);
         double maxZ = roads.stream().mapToDouble(m -> m.max().z()).max().orElse(1.0);
         double scale = Math.min((double)(w - 24) / Math.max(1.0, maxX - minX), (double)(h - 42) / Math.max(1.0, maxZ - minZ));
         double ox = (double)left + ((double)w - (maxX - minX) * scale) / 2.0;
         double oy = (double)(top + 22) + ((double)(h - 42) - (maxZ - minZ) * scale) / 2.0;

         for (RoadGeometry.Mesh road : roads) {
            int color = this.conflictRoads.contains(road) ? -2072480 : (road == this.checkedMesh ? -10367517 : -11310214);

            for (int i = 0; i < road.samples().size(); i += 2) {
               RoadGeometry.Sample p = road.samples().get(i);
               int px = (int)(ox + (p.center().x() - minX) * scale);
               int py = (int)(oy + (p.center().z() - minZ) * scale);
               int r = Math.max(1, (int)Math.ceil(p.halfWidth() * scale));
               g.fill(Math.max(left + 2, px - r), Math.max(top + 20, py - r), Math.min(left + w - 2, px + r + 1), Math.min(top + h - 2, py + r + 1), color);
            }
         }

         if (this.checkedMesh != null) {
            for (int i = 0; i < 2; i++) {
               RoadGeometry.V p = (i == 0 ? this.checkedMesh.first() : this.checkedMesh.last()).center();
               g.drawString(this.font, i == 0 ? "A" : "B", (int)(ox + (p.x() - minX) * scale), (int)(oy + (p.z() - minZ) * scale), 16777215, false);
            }
         }

         g.drawString(this.font, "北 ↑  N", left + w - 55, top + 6, 16777215, false);
         g.drawString(this.font, this.pending ? "计算中…" : (this.checkedMesh == null ? "道路位置 · 等待预览" : "俯视预览"), left + 8, top + 6, 10409958, false);
      }
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      if (this.back != null) {
         this.back.setMessage(Component.literal(this.pending && this.token == null ? "取消计算并返回" : "返回实景 / 预览"));
      }

      g.fill(this.x, this.y, this.x + this.panelWidth, this.y + this.panelHeight, -367713746);
      g.drawString(this.font, this.payload.hasUUID("Id") ? "编辑匝道 · A 汇出 → B 汇入" : "匝道连接器 · A 汇出 → B 汇入", this.x + 12, this.row(12), 6731263, false);
      g.drawString(
         this.font,
         this.payload.getCompound("To").hasUUID("Junction") ? "目标：路口接入口" : (this.landing == LanePoints.Landing.EXACT ? "目标：锁定 B" : "目标：整条连续同车道"),
         this.x + 12,
         this.row(32),
         13820653,
         false
      );
      g.drawString(this.font, "参考半径", this.x + 12, this.row(134), 16777215, false);
      g.drawString(this.font, "过渡长度", this.x + 17 + this.control / 2, this.row(134), 16777215, false);
      this.map(g, this.x + this.control + 24, this.row(32), this.panelWidth - this.control - 36, (int)Math.round(239.0 * this.uiScale));
      g.enableScissor(this.x + 10, this.row(283), this.x + this.panelWidth - 10, this.y + this.panelHeight - 6);
      g.drawWordWrap(
         this.font,
         Component.literal(
            this.pending
               ? this.status + String.format(Locale.ROOT, " 已等待 %.1f 秒。", (double)(System.nanoTime() - this.pendingSince) / 1.0E9)
               : (this.diagnostic ? brief(this.status) : this.status)
         ),
         this.x + 12,
         this.row(283),
         this.panelWidth - 24,
         16764812
      );
      g.disableScissor();
      this.previewButton.setTooltip(Tooltip.create(Component.literal(this.status)));
      super.render(g, mx, my, dt);
   }

   private static final class FailureDetails extends Screen {
      private final LaneRampScreen parent;
      private final String message;

      FailureDetails(LaneRampScreen parent, String message) {
         super(Component.literal("匝道建造失败详情"));
         this.parent = parent;
         this.message = message;
      }

      protected void init() {
         this.addRenderableWidget(
            Button.builder(Component.literal("返回匝道设置"), b -> this.onClose()).bounds((this.width - 160) / 2, this.height - 30, 160, 20).build()
         );
      }

      public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
      }

      public void render(GuiGraphics g, int mx, int my, float dt) {
         super.renderBackground(g, mx, my, dt);
         g.fill(12, 12, this.width - 12, this.height - 12, -267050450);
         g.drawString(this.font, "建造未通过：完整诊断", 24, 24, 16764812, false);
         g.drawWordWrap(this.font, Component.literal(this.message), 24, 44, Math.max(100, this.width - 48), 16777215);
         super.render(g, mx, my, dt);
      }

      public void onClose() {
         Minecraft.getInstance().setScreen(this.parent);
      }

      public boolean isPauseScreen() {
         return false;
      }
   }
}
