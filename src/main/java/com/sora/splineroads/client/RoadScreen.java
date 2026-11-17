package com.sora.splineroads.client;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.LaneRampAlignment;
import com.sora.splineroads.core.RoadConflictIds;
import com.sora.splineroads.core.RoadConnectionChecks;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadPlantings;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.core.RoadTransitions;
import com.sora.splineroads.core.RoadTunnel;
import com.sora.splineroads.core.RoadTunnelFit;
import com.sora.splineroads.core.RoadVertical;
import com.sora.splineroads.net.RoadDraft;
import com.sora.splineroads.world.AutoJunctions;
import com.sora.splineroads.world.JunctionCodec;
import com.sora.splineroads.world.JunctionRoads;
import com.sora.splineroads.world.RoadCrossingTransitions;
import com.sora.splineroads.world.RoadData;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.world.SideRoads;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

public final class RoadScreen extends Screen {
   private final CompoundTag payload;
   private final boolean node;
   private RoadProfile.Options options = RoadProfile.Options.DEFAULT;
   private boolean extraPage;
   private boolean liftPage;
   private boolean mapPage;
   private int controlIndex;
   private int optionsPage;
   private final Map<String, EditBox> fields = new LinkedHashMap<>();
   private RoadGeometry.Mode mode;
   private RoadGeometry.Style style;
   private RoadGeometry.Structure structure;
   private double roadWidth;
   private double thickness;
   private double tension;
   private double arc;
   private int left;
   private int top;
   private int debounce;
   private String status = "";
   private boolean pending;
   private boolean advanced;
   private boolean savedPreviewAllowed = true;
   private Button save;
   private Button pathButton;
   private Button styleButton;
   private Button delete;
   private Button previewButton;
   private Button failureButton;
   private String serverFailure = "";
   private List<RoadGeometry.Mesh> failureRoads = List.of();
   private boolean returningFromFailureDetails;
   private static final RoadGeometry.Mode[] MODES = new RoadGeometry.Mode[]{
      RoadGeometry.Mode.AUTO, RoadGeometry.Mode.STRAIGHT, RoadGeometry.Mode.CURVE, RoadGeometry.Mode.ARC
   };
   private static final String[] STYLES = new String[]{"双向两车道", "双向四车道", "单向两车道", "无标线路面", "单车道匝道", "双车道匝道"};
   private Object previewInput;
   private long previewRoadRevision = Long.MIN_VALUE;
   private RoadGeometry.Mesh resolvedPreview;
   private String resolvedStatus = "";
   private Component resolvedPathLabel;
   private String furnitureFailure = "";
   private String beforeFurnitureFailure = "";
   private long observedSavedRevision = Long.MIN_VALUE;

   private boolean laneRoad() {
      return this.payload.getString("Kind").equals("laneRampRoad");
   }

   private boolean sideRoad() {
      return this.payload.getString("Kind").equals("sideRoad");
   }

   private boolean sectionRoad() {
      return this.sideRoad() || this.payload.getString("Kind").equals("armRoad");
   }

   private boolean optionsOnly() {
      return !this.node && this.width < 512 && this.extraPage;
   }

   public RoadScreen(CompoundTag payload) {
      super(Component.literal("Spline Roads · 智能铺路"));
      this.payload = RoadDraft.baseline(payload);
      this.node = payload.getString("Kind").equals("node");
      this.advanced = payload.getBoolean("Advanced");
      this.extraPage = payload.getBoolean("ExtraPage");
      this.liftPage = payload.getBoolean("LiftPage");
      this.mapPage = payload.getBoolean("MapPage");
      this.controlIndex = payload.getInt("ControlIndex");
      this.optionsPage = Math.max(0, Math.min(this.advanced ? 8 : 7, payload.getInt("OptionsPage")));
      if (!this.node) {
         RoadGeometry.Settings s = RoadProfile.editable(RoadRecord.readSettings(payload.getCompound("Settings")));
         this.mode = s.mode();
         this.options = s.options();
         this.style = s.style();
         if (RoadProfile.catalog(this.style).type() == RoadProfile.Type.HIGHWAY) {
            this.payload.putBoolean("ForceJunction", false);
         }

         this.structure = s.structure();
         this.roadWidth = s.width();
         this.thickness = s.thickness();
         this.tension = s.tension();
         this.arc = s.arcDegrees();
      }
   }

   protected void init() {
      this.fields.clear();
      this.pathButton = null;
      this.left = 12;
      this.top = Math.max(2, (this.height - 238) / 2);
      int x = this.left + 12;
      int y = this.top + 40;
      if (this.node) {
         RoadGeometry.Node n = RoadRecord.readNode(this.payload.getCompound("Node"));
         BlockPos p = BlockPos.of(this.payload.getLong("Pos"));
         this.field("X 偏移", "x", n.position().x() - (double)p.getX() - 0.5, x, y);
         this.field("Y 偏移", "y", n.position().y() - (double)p.getY(), x + 136, y);
         this.field("Z 偏移", "z", n.position().z() - (double)p.getZ() - 0.5, x, y + 42);
         this.field("朝向 / 度", "yaw", n.yaw(), x + 136, y + 42);
         this.field("坡度 / %", "grade", n.grade() * 100.0, x, y + 84);
         this.addRenderableWidget(Button.builder(Component.literal("一键贴地"), b -> {
            this.fields.get("y").setValue("0");
            this.changed();
         }).bounds(x + 136, y + 96, 128, 18).build());
      } else if (!this.optionsOnly()) {
         if (ManualPlannerClient.hasMap()) {
            this.addRenderableWidget(Button.builder(Component.literal("规划地图"), b -> {
               if (this.pending || this.refresh()) {
                  ManualPlannerClient.mapPreview(this);
               }
            }).bounds(this.left + 283, this.top + 10, 56, 18).build());
         }

         this.option(this.mapPage ? "设置" : "俯视图", this.left + 145, this.top + 10, 52, () -> {
            this.mapPage = !this.mapPage;
            if (this.width < 512) {
               this.extraPage = this.mapPage;
            }
         });
         this.addRenderableWidget(Button.builder(Component.literal(this.advanced ? "收起微调" : "展开微调"), b -> {
            if (this.capture()) {
               this.advanced = !this.advanced;
               this.optionsPage = Math.min(this.optionsPage, this.advanced ? 8 : 7);
               this.payload.putBoolean("Advanced", this.advanced);
               this.payload.putBoolean("ExtraPage", this.extraPage);
               this.payload.putBoolean("LiftPage", this.liftPage);
               this.rebuildWidgets();
            }
         }).bounds(this.left + 204, this.top + 10, 72, 18).build());
         this.addRenderableWidget(
            Button.builder(
                  Component.literal(this.typeName()),
                  b -> {
                     if (this.capture()) {
                        if (this.laneRoad()) {
                           this.selectStyle(RoadProfile.highway(this.style) ? RoadGeometry.Style.C1_RAMP : RoadGeometry.Style.C1_HIGHWAY_RAMP, true);
                        } else {
                           this.applyLaneConfiguration(
                              RoadProfile.highway(this.style) ? RoadProfile.Type.ORDINARY : RoadProfile.Type.HIGHWAY,
                              RoadLanes.counts(this.style, this.options),
                              true
                           );
                        }
                     }
                  }
               )
               .bounds(x, y, this.advanced ? 98 : 64, 20)
               .build()
         );
         this.styleButton = (Button)this.addRenderableWidget(Button.builder(Component.literal(this.styleName()), b -> {
            if (this.capture()) {
               this.openLaneConfiguration();
            }
         }).bounds(x + (this.advanced ? 102 : 172), y, this.advanced ? 162 : 92, 20).build());
         this.pathButton = (Button)this.addRenderableWidget(Button.builder(Component.literal("智能选线"), b -> {
            if (this.capture()) {
               if (this.options.routing().controls().isEmpty()) {
                  int i = Arrays.asList(MODES).indexOf(this.mode);
                  this.mode = MODES[(i + 1) % MODES.length];
                  this.changed();
                  this.refresh();
               }
            }
         }).bounds(x + (this.advanced ? 0 : 68), y + (this.advanced ? 24 : 0), this.advanced ? 264 : 100, 20).build());
         if (this.advanced) {
            this.field("单车道宽 / 格", "width", this.laneWidth(), x, y + 49);
            this.field("厚度 / 格", "thickness", this.thickness, x + 136, y + 49);
            this.field("曲线强度", "tension", this.tension, x, y + 81);
            this.field("圆弧角度 / 度", "angle", this.arc, x + 136, y + 81);
         } else {
            this.addRenderableWidget(Button.builder(Component.literal(this.payload.getBoolean("LevelEnds") ? "两端贴地：开" : "两端贴地"), b -> {
               this.payload.putBoolean("LevelEnds", !this.payload.getBoolean("LevelEnds"));
               this.rebuildWidgets();
            }).bounds(x, this.top + 134, 128, 18).build());
            this.addRenderableWidget(Button.builder(Component.literal("−"), b -> {
               this.setLaneWidth(Math.max(2.0, this.laneWidth() - 0.5));
               this.rebuildWidgets();
            }).bounds(x, y + 24, 30, 20).build());
            ((Button)this.addRenderableWidget(
                  Button.builder(
                        Component.literal(
                           (RoadProfile.modern(this.style) ? "车道 " + this.format(this.laneWidth()) + " · 总宽 " : "路宽 ") + this.format(this.roadWidth) + " 格"
                        ),
                        b -> {
                        }
                     )
                     .bounds(x + 34, y + 24, 196, 20)
                     .build()
               ))
               .active = false;
            this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
               this.setLaneWidth(Math.min(RoadProfile.modern(this.style) ? 8.0 : 64.0, this.laneWidth() + 0.5));
               this.rebuildWidgets();
            }).bounds(x + 234, y + 24, 30, 20).build());
            this.medianWidthRow(x, y + 48, 264, true);
            this.medianWidthRow(x, y + 72, 264, false);
         }
      }

      if (!this.node && !this.optionsOnly()) {
         this.addRenderableWidget(Button.builder(Component.literal(this.structureName()), b -> {
            this.structure = switch (this.structure) {
               case AUTO, GROUND -> RoadGeometry.Structure.BRIDGE;
               case BRIDGE -> RoadGeometry.Structure.TUNNEL;
               case TUNNEL -> RoadGeometry.Structure.AUTO;
            };
            b.setMessage(Component.literal(this.structureName()));
            this.changed();
            this.refresh();
         }).bounds(x, this.top + 156, 264, 18).build());
      }

      if (!this.node) {
         if (this.width >= 512 || this.optionsOnly()) {
            this.buildOptions(this.width >= 512 ? this.left + 294 : this.left + 12, this.top + 40, this.width >= 512 ? 194 : 264);
         }

         if (this.width < 512 && !this.optionsOnly()) {
            this.option("▸", x + 240, this.top + 134, 24, () -> {
               this.extraPage = true;
               this.liftPage = false;
            });
         }
      }

      if (!this.node && !this.optionsOnly() && (this.payload.getInt("DegreeA") > 0 || this.payload.getInt("DegreeB") > 0)) {
         for (int endpoint = 0; endpoint < 2; endpoint++) {
            String side = endpoint == 0 ? "A" : "B";
            boolean required = this.payload.getBoolean("Center" + side) || this.payload.getInt("Degree" + side) >= 2;
            boolean incompatible = false;

            try {
               incompatible = !required
                  && this.payload.getInt("Degree" + side) > 0
                  && this.payload.contains("JoinSection" + side)
                  && !RoadTransitions.compatible(
                     new RoadGeometry.Settings(this.mode, this.style, this.roadWidth, this.thickness, this.tension, this.arc).options(this.options),
                     RoadRecord.readSettings(this.payload.getCompound("JoinSection" + side))
                  );
            } catch (IllegalArgumentException var10) {
               incompatible = true;
               this.status = "连接数据无效，请关闭后重新右键道路：" + var10.getMessage();
            }

            required |= incompatible;
            boolean force = AutoJunctions.forced(this.payload, side);
            String label = side + " 端：" + (required ? "已有/必要路口" : (force ? "生成路口" : "连续道路"));
            Button button = (Button)this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
               this.payload.putBoolean("ForceJunction" + side, !AutoJunctions.forced(this.payload, side));
               this.rebuildWidgets();
            }).bounds(x + endpoint * 134, this.top + 177, 130, 18).build());
            button.active = !required && this.payload.getInt("Degree" + side) > 0 && RoadProfile.catalog(this.style).type() == RoadProfile.Type.ORDINARY;
         }
      }

      boolean edit = this.payload.hasUUID("Id") && !this.sectionRoad();
      this.save = (Button)this.addRenderableWidget(
         Button.builder(Component.literal(this.node ? "保存端点" : "建造 / 保存"), b -> this.submit()).bounds(x, this.top + 206, edit ? 64 : 70, 20).build()
      );
      this.previewButton = (Button)this.addRenderableWidget(Button.builder(Component.literal("实景预览"), b -> {
         this.clearFailure();
         if (this.refresh()) {
            ManualPlannerClient.worldPreview();
            this.minecraft.setScreen(null);
         }
      }).bounds(x + (edit ? 68 : 74), this.top + 206, edit ? 56 : 62, 20).build());
      this.failureButton = (Button)this.addRenderableWidget(
         Button.builder(Component.literal("失败详情"), b -> this.minecraft.setScreen(new RoadScreen.FailureDetails(this)))
            .bounds(x + (edit ? 128 : 140), this.top + 206, edit ? 56 : 62, 20)
            .build()
      );
      this.failureButton.visible = !this.serverFailure.isEmpty();
      this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> {
         ClientRoads.discardDraft();
         this.clearFailure();
         ClientRoads.preview = null;
         this.minecraft.setScreen(null);
      }).bounds(x + (edit ? 188 : 206), this.top + 206, edit ? 38 : 58, 20).build());
      if (edit) {
         this.delete = (Button)this.addRenderableWidget(Button.builder(Component.literal("删除"), b -> {
            if (!this.pending) {
               CompoundTag t = new CompoundTag();
               t.putString("Action", "deletePreview");
               t.putUUID("Id", this.payload.getUUID("Id"));
               this.pending = true;
               b.active = false;
               ClientRoads.sendAction(t);
            }
         }).bounds(x + 230, this.top + 206, 34, 20).build());
      }

      if (this.sectionRoad()) {
         if (this.pathButton != null) {
            this.pathButton.setMessage(Component.literal("接入线形随路口衔接"));
            this.pathButton.active = false;
         }

         for (GuiEventListener child : this.children()) {
            if (child instanceof Button) {
               Button b = (Button)child;
               String label = b.getMessage().getString();
               if ((this.optionsPage == 2 || this.optionsPage == 3)
                  && b.getX() >= (this.optionsOnly() ? this.left + 12 : this.left + 294)
                  && b.getY() >= this.top + 40
                  && b.getY() < this.top + 180) {
                  b.active = false;
               }

               if (label.equals(this.typeName())
                  || label.equals(this.structureName())
                  || label.startsWith("两端贴地")
                  || label.contains("靠左行驶")
                  || label.startsWith("中间控制点")
                  || label.contains("直路边缘")
                  || label.startsWith("启用桥梁")
                  || label.startsWith("启用隧道")
                  || label.startsWith("龙门架")
                  || label.startsWith("配跨")
                  || label.startsWith("端点：")
                  || label.startsWith("下潜：")
                  || label.startsWith("最大坡度")) {
                  b.active = false;
               }
            }
         }

         for (String key : List.of("thickness", "tension", "angle", "bridgeSpan", "bridgeRise", "tunnelHeight", "tunnelDepth", "gantrySpacing")) {
            if (this.fields.containsKey(key)) {
               this.fields.get(key).setEditable(false);
            }
         }
      }

      if (this.laneRoad()) {
         if (this.styleButton != null) {
            this.styleButton.active = false;
         }

         if (this.pathButton != null) {
            this.pathButton.active = false;
            this.pathButton.setMessage(Component.literal("线路与扩出：用匝道连接器编辑"));
         }

         for (GuiEventListener childx : this.children()) {
            if (childx instanceof Button) {
               Button bx = (Button)childx;
               if (bx.getMessage().getString().startsWith("两端贴地")) {
                  bx.active = false;
               }
            }
         }

         for (String keyx : List.of("tension", "angle")) {
            if (this.fields.containsKey(keyx)) {
               this.fields.get(keyx).setEditable(false);
            }
         }
      }

      String retainedFailure = this.serverFailure;
      List<RoadGeometry.Mesh> retainedRoads = this.failureRoads;
      boolean restoreFailure = this.returningFromFailureDetails;
      this.returningFromFailureDetails = false;
      this.refresh();
      if (restoreFailure && !retainedFailure.isEmpty()) {
         this.showFailure(retainedFailure, retainedRoads);
      }
   }

   private String structureName() {
      return switch (this.structure) {
         case AUTO, GROUND -> "自动识别";
         case BRIDGE -> "桥梁：" + this.options.infrastructure().bridge().label;
         case TUNNEL -> "地下：" + this.options.infrastructure().tunnel().label;
      };
   }

   private void field(String label, String key, double value, int x, int y) {
      EditBox f = new EditBox(this.font, x, y + 12, 128, 18, Component.literal(label));
      f.setMaxLength(32);
      f.setValue(this.format(value));
      f.setResponder(s -> this.changed());
      this.fields.put(key, f);
      this.addRenderableWidget(f);
   }

   private String format(double n) {
      return Double.toString(n);
   }

   private double number(String key) {
      double n = Double.parseDouble(this.fields.get(key).getValue());
      if (!Double.isFinite(n)) {
         throw new IllegalArgumentException("请输入有限数值");
      } else {
         return n;
      }
   }

   private boolean capture() {
      try {
         if (!this.node && this.fields.containsKey("width")) {
            this.setLaneWidth(this.number("width"));
            this.thickness = this.number("thickness");
            this.tension = this.number("tension");
            this.arc = this.number("angle");
         }

         if (!this.node && this.fields.containsKey("lift")) {
            this.options = this.options.lift(this.number("liftPosition") / 100.0, this.number("lift"));
         }

         if (this.fields.containsKey("controlX")) {
            ArrayList<RoadProfile.Control> cs = new ArrayList<>(this.options.routing().controls());
            RoadProfile.Control c = cs.get(this.controlIndex);
            cs.set(
               this.controlIndex,
               new RoadProfile.Control(c.id(), new RoadGeometry.V(this.number("controlX"), this.number("controlY"), this.number("controlZ")))
            );
            this.options = this.options.route(this.options.routing().controls(cs));
         }

         if (!this.node && !this.options.routing().controls().isEmpty()) {
            this.mode = RoadGeometry.Mode.CURVE;
         }

         this.payload.putBoolean("MapPage", this.mapPage);
         this.payload.putInt("ControlIndex", this.controlIndex);
         if (!this.node) {
            this.options = this.options.sidewalk(this.options.sidewalk().smooth(true));
         }

         if (this.fields.containsKey("bridgeRise")) {
            this.options = this.options.infrastructure(this.options.infrastructure().rise(this.number("bridgeRise")));
         }

         if (this.fields.containsKey("bridgeSpan")) {
            this.options = this.options.infrastructure(this.options.infrastructure().span(this.number("bridgeSpan")));
         }

         if (this.fields.containsKey("tunnelHeight")) {
            this.options = this.options.infrastructure(this.options.infrastructure().headroom(this.number("tunnelHeight")));
         }

         if (this.fields.containsKey("tunnelDepth")) {
            this.options = this.options.infrastructure(this.options.infrastructure().depth(this.number("tunnelDepth")));
         }

         if (this.fields.containsKey("gantrySpacing")) {
            this.options = this.options.infrastructure(this.options.infrastructure().spacing(this.number("gantrySpacing")));
         }

         if (this.fields.containsKey("lampSpacing")) {
            this.options = this.options.streetscape(this.options.streetscape().lampSpacing(this.number("lampSpacing")));
         }

         if (this.fields.containsKey("walkLampSpacing")) {
            this.options = this.options.streetscape(this.options.streetscape().walkLampSpacing(this.number("walkLampSpacing")));
         }

         if (this.fields.containsKey("medianTreeSpacing")) {
            this.options = this.options
               .streetscape(this.options.streetscape().plantings(this.options.streetscape().plantings().medianSpacing(this.number("medianTreeSpacing"))));
         }

         if (this.fields.containsKey("separatorTreeSpacing")) {
            this.options = this.options
               .streetscape(this.options.streetscape().plantings(this.options.streetscape().plantings().separatorSpacing(this.number("separatorTreeSpacing"))));
         }

         if (this.fields.containsKey("plantingSpacing")) {
            this.options = this.options.streetscape(this.options.streetscape().plantingSpacing(this.number("plantingSpacing")));
         }

         this.payload.putInt("OptionsPage", this.optionsPage);
         return true;
      } catch (IllegalArgumentException var3) {
         this.status = var3 instanceof NumberFormatException ? "请先填写完整数值" : var3.getMessage();
         return false;
      }
   }

   private boolean junctionMode() {
      return !this.sectionRoad()
         && (
            AutoJunctions.requiresJunction(
                  this.payload,
                  new RoadGeometry.Settings(this.mode, this.style, this.roadWidth, this.thickness, this.tension, this.arc)
                     .structure(this.structure)
                     .options(this.options)
               )
               || RoadProfile.catalog(this.style).type() == RoadProfile.Type.HIGHWAY
                  && this.payload.getBoolean("AutoJunction")
                  && !RoadTunnelFit.enabled(
                     new RoadGeometry.Settings(this.mode, this.style, this.roadWidth, this.thickness, this.tension, this.arc)
                        .structure(this.structure)
                        .options(this.options)
                  )
         );
   }

   /** Panel geometry for add-ons that extend the screen (material bill). */
   public int panelLeft() {
      return this.left;
   }

   public int panelTop() {
      return this.top;
   }

   public int panelWidth() {
      return 288;
   }

   /** Real right edge (advanced layout shows a second column at left + 294). */
   public int panelRight() {
      return this.width >= 512 ? this.left + 494 : this.left + 288;
   }

   public int panelHeight() {
      return 238;
   }

   /** Settings for add-ons (material bills etc.); {@code null} while the form is incomplete. */
   public RoadGeometry.Settings currentSettingsOrNull() {
      try {
         return this.settings();
      } catch (RuntimeException error) {
         return null;
      }
   }

   /** The raw draft payload (contains {@code A} / {@code B} endpoint positions). */
   public CompoundTag draftTag() {
      return this.payload;
   }

   private RoadGeometry.Settings settings() {
      if (!this.capture()) {
         throw new IllegalArgumentException("请填写完整数值");
      } else if (this.laneRoad() || this.sectionRoad()) {
         return new RoadGeometry.Settings(this.mode, this.style, this.roadWidth, this.thickness, this.tension, this.arc)
            .structure(this.structure)
            .options(this.options);
      } else {
         return this.junctionMode()
            ? new RoadGeometry.Settings(this.mode, this.style, this.roadWidth, this.thickness, this.tension, this.arc)
               .structure(this.structure)
               .options(this.options)
            : RoadData.joinSections(
               RoadData.joinWidths(
                  new RoadGeometry.Settings(this.mode, this.style, this.roadWidth, this.thickness, this.tension, this.arc)
                     .structure(this.structure)
                     .options(this.options),
                  this.payload.getDouble("JoinWidthA"),
                  this.payload.getDouble("JoinWidthB")
               ),
               this.payload
            );
      }
   }

   private RoadGeometry.Node editedNode() {
      BlockPos p = BlockPos.of(this.payload.getLong("Pos"));
      return new RoadGeometry.Node(
         new RoadGeometry.V((double)p.getX() + 0.5 + this.number("x"), (double)p.getY() + this.number("y"), (double)p.getZ() + 0.5 + this.number("z")),
         this.number("yaw"),
         this.number("grade") / 100.0
      );
   }

   private void changed() {
      this.savedPreviewAllowed = false;
      ClientRoads.editorChanged(this);
      this.clearFailure();
      this.debounce = 4;
      this.status = "";
      ClientRoads.error = "";
   }

   private boolean refresh() {
      this.debounce = 0;

      Object input;
      try {
         input = this.node
            ? this.editedNode()
            : new RoadScreen.RoadPreviewInput(
               this.settings(),
               this.payload.getBoolean("LevelEnds"),
               this.payload.getBoolean("ForceJunction"),
               AutoJunctions.forced(this.payload, "A"),
               AutoJunctions.forced(this.payload, "B")
            );
      } catch (IllegalArgumentException var5) {
         return this.refreshPlan();
      }

      long revision = ClientRoads.INDEX.revision();
      if (input.equals(this.previewInput)
         && this.previewRoadRevision == revision
         && ClientRoads.preview == this.resolvedPreview
         && this.resolvedPreview != null
         && !ClientRoads.conflictPreview) {
         this.clearFailure();
         this.status = this.resolvedStatus;
         ClientRoads.error = "";
         if (this.pathButton != null && this.resolvedPathLabel != null) {
            this.pathButton.setMessage(this.resolvedPathLabel);
         }

         if (this.save != null) {
            this.save.active = !this.pending;
         }

         this.payload.putBoolean("Advanced", this.advanced);
         this.payload.putBoolean("ExtraPage", this.extraPage);
         this.payload.putBoolean("LiftPage", this.liftPage);
         ClientRoads.storeDraft(this.payload);
         RoadRenderer.previewFurnitureStatus(ClientRoads.preview);
         return true;
      } else {
         boolean valid = this.refreshPlan();
         if (valid) {
            this.previewInput = input;
            this.previewRoadRevision = revision;
            this.resolvedPreview = ClientRoads.preview;
            this.resolvedStatus = this.status;
            this.resolvedPathLabel = this.pathButton == null ? null : this.pathButton.getMessage();
         } else {
            this.previewInput = null;
         }

         return valid;
      }
   }

   private boolean refreshPlan() {
      this.clearFailure();
      ClientRoads.conflictPreview = false;

      try {
         ArrayList<RoadRecord> candidates = new ArrayList<>();
         ClientRoads.nodePreviews = List.of();
         if (this.node) {
            RoadGeometry.Node n = this.editedNode();
            if (Math.abs(this.number("x")) > 8.0
               || Math.abs(this.number("z")) > 8.0
               || this.number("y") < -8.0
               || this.number("y") > 16.0
               || Math.abs(n.grade()) > 0.5) {
               throw new IllegalArgumentException("偏移或坡度超出范围");
            }

            this.payload.put("Node", RoadRecord.writeNode(n));
            ClientRoads.preview = null;
            List<RoadGeometry.Mesh> connected = new ArrayList<>();

            for (Tag tag : this.payload.getList("Attached", 10)) {
               CompoundTag entry = (CompoundTag)tag;
               RoadRecord record = RoadRecord.load(entry.getCompound("Road"));
               RoadRecord candidate = RoadData.planNodeEdit(
                  record,
                  BlockPos.of(this.payload.getLong("Pos")),
                  n,
                  RoadRecord.readNode(this.payload.getCompound("OriginalNode")),
                  RoadData.readHint(entry.getCompound("AutoA")),
                  RoadData.readHint(entry.getCompound("AutoB"))
               );
               candidates.add(candidate);
               connected.add(candidate.mesh());
            }

            ClientRoads.nodePreviews = List.copyOf(connected);
            if (!connected.isEmpty()) {
               ClientRoads.preview = connected.get(0);
            }

            this.status = "保存时同步调整相连道路，并自动清障";
         } else {
            RoadGeometry.Settings s = this.settings();
            s.validate();
            this.payload.put("Settings", RoadRecord.writeSettings(s));
            if (this.pathButton != null) {
               this.pathButton.setMessage(Component.literal(this.mode == RoadGeometry.Mode.AUTO ? "智能选线 ▸" : "手动：" + RoadPlanner.name(this.mode) + " ▸"));
            }

            RoadIndex.Built saved = this.savedPreview();
            if (saved != null) {
               ClientRoads.preview = saved.mesh;
               ClientRoads.nodePreviews = List.of();
               RoadRenderer.previewCandidates(List.of(saved.record));
               this.status = "已保存道路；修改参数后更新预览，保存时完整校验";
               this.furnitureStatus("");
               ClientRoads.error = "";
               ClientRoads.storeDraft(this.payload);
               if (this.save != null) {
                  this.save.active = !this.pending;
               }

               return true;
            }

            if (this.laneRoad()) {
               RoadIndex.Built cached = ClientRoads.INDEX.roads.get(this.payload.getUUID("Id"));
               RoadRecord old = cached == null ? RoadRecord.load(this.payload.getCompound("Road")) : cached.record;
               RoadRecord candidate = old.settings(s);
               ClientRoads.preview = candidate.mesh();
               ClientRoads.nodePreviews = List.of();
               RoadRenderer.previewCandidates(List.of(candidate));
               this.status = "单向 1 车道匝道；路宽、附属设置可编辑，路径使用匝道连接器调整";
               if (this.pathButton != null) {
                  this.pathButton.setMessage(Component.literal("线路与扩出：用匝道连接器编辑"));
               }

               ClientRoads.storeDraft(this.payload);
               if (this.save != null) {
                  this.save.active = !this.pending;
               }

               return true;
            }

            if (this.sideRoad()) {
               ArrayList<RoadGeometry.Mesh> meshes = new ArrayList<>();
               ArrayList<RoadSurface.Face> faces = new ArrayList<>();

               for (String key : this.payload.getBoolean("Paired") ? List.of("Spec", "PairSpec") : List.of("Spec")) {
                  JunctionSpec spec = JunctionCodec.read(this.payload.getCompound(key));
                  spec = SideRoads.preview(spec, s, key.equals("PairSpec") || !this.payload.getBoolean("MainFirst"));
                  JunctionPlanner.Plan plan = JunctionPlanner.plan(spec);
                  meshes.add(plan.pieces().get(2).mesh());

                  for (JunctionPlanner.Piece piece : plan.pieces()) {
                     faces.addAll(piece.paint());
                     piece.structures().forEach(p -> faces.addAll(p.faces()));
                  }
               }

               ClientRoads.preview = meshes.get(0);
               ClientRoads.nodePreviews = List.copyOf(meshes);
               JunctionScreen.previewMesh = ClientRoads.preview;
               RoadRenderer.previewCandidates(List.of());
               JunctionScreen.previewFaces = List.copyOf(faces);
               this.status = "附属点连接道路 · 总宽 " + this.format(s.width()) + " 格；路口设置使用路口编辑器";
               ClientRoads.storeDraft(this.payload);
               if (this.save != null) {
                  this.save.active = !this.pending;
               }

               return true;
            }

            if (this.payload.getString("Kind").equals("armRoad")) {
               if (this.pathButton != null) {
                  this.pathButton.setMessage(Component.literal("接入线形随路口衔接"));
               }

               JunctionSpec spec = JunctionCodec.read(this.payload.getCompound("Spec"));
               int index = this.payload.getInt("Arm");
               ArrayList<JunctionSpec.Arm> arms = new ArrayList<>(spec.arms());
               arms.set(index, JunctionRoads.change(arms.get(index), s));
               JunctionPlanner.Plan plan = JunctionPlanner.plan(spec.arms(arms));
               JunctionPlanner.Piece piece = plan.pieces().get(index);
               ClientRoads.preview = piece.mesh();
               ClientRoads.nodePreviews = List.of();
               JunctionScreen.previewMesh = piece.mesh();
               RoadRenderer.previewCandidates(List.of());
               ArrayList<RoadSurface.Face> faces = new ArrayList<>(piece.paint());
               piece.structures().forEach(p -> faces.addAll(p.faces()));
               JunctionScreen.previewFaces = List.copyOf(faces);
               this.status = "仅更新接入路段 " + (index + 1) + " · 总宽 " + this.format(arms.get(index).width()) + " 格";
               ClientRoads.storeDraft(this.payload);
               if (this.save != null) {
                  this.save.active = !this.pending;
               }

               return true;
            }

            if (this.junctionMode()) {
               AutoJunctions.Draft draft = AutoJunctions.preview(this.payload);
               List<RoadRecord> selected = AutoJunctions.selected(
                  draft, this.payload.hasUUID("Id") ? this.payload.getUUID("Id") : AutoJunctions.proposed(this.payload).id()
               );
               ClientRoads.nodePreviews = selected.stream().map(RoadRecord::mesh).toList();
               RoadRenderer.previewCandidates(selected);
               ClientRoads.preview = ClientRoads.nodePreviews.get(0);
               JunctionScreen.previewMesh = ClientRoads.preview;
               ArrayList<RoadSurface.Face> faces = new ArrayList<>();
               new HashSet();

               for (RoadRecord r : selected) {
                  if (r.junction() != null) {
                     JunctionPlanner.Piece piece = r.junction().get();
                     faces.addAll(RoadSurface.custom(new RoadSurface.Geometry(List.of(), List.of()), piece.mesh(), piece.paint()).markings());
                     piece.structures().forEach(part -> faces.addAll(part.faces()));
                  }
               }

               JunctionScreen.previewFaces = List.copyOf(faces);
               this.status = "自动路口预览：" + draft.centers().size() + " 处；各方向保留自己的车道和宽度";
               ClientRoads.storeDraft(this.payload);
               if (this.save != null) {
                  this.save.active = !this.pending;
               }

               return true;
            }

            RoadPlanner.Hint a = this.mode == RoadGeometry.Mode.AUTO && this.payload.contains("AutoA")
               ? RoadData.readHint(this.payload.getCompound("AutoA"))
               : RoadPlanner.Hint.free(RoadRecord.readNode(this.payload.getCompound("StartNode")));
            RoadPlanner.Hint b = this.mode == RoadGeometry.Mode.AUTO && this.payload.contains("AutoB")
               ? RoadData.readHint(this.payload.getCompound("AutoB"))
               : RoadPlanner.Hint.free(RoadRecord.readNode(this.payload.getCompound("EndNode")));
            if (this.payload.getBoolean("LevelEnds")) {
               a = new RoadPlanner.Hint(RoadData.atGround(a.node(), BlockPos.of(this.payload.getLong("A"))), a.headingLocked(), true, a.linked());
               b = new RoadPlanner.Hint(RoadData.atGround(b.node(), BlockPos.of(this.payload.getLong("B"))), b.headingLocked(), true, b.linked());
            }

            if (this.payload.getBoolean("LiveSectionA") && this.payload.contains("AutoA")) {
               RoadGeometry.Node nx = RoadData.readHint(this.payload.getCompound("AutoA")).node();
               a = new RoadPlanner.Hint(new RoadGeometry.Node(nx.position(), a.node().yaw(), a.node().grade()), a.headingLocked(), a.gradeLocked(), a.linked());
            }

            if (this.payload.getBoolean("LiveSectionB") && this.payload.contains("AutoB")) {
               RoadGeometry.Node nx = RoadData.readHint(this.payload.getCompound("AutoB")).node();
               b = new RoadPlanner.Hint(new RoadGeometry.Node(nx.position(), b.node().yaw(), b.node().grade()), b.headingLocked(), b.gradeLocked(), b.linked());
            }

            RoadRecord previous = this.payload.contains("Road") ? RoadRecord.load(this.payload.getCompound("Road")) : null;
            RoadPlanner.Plan plan = previous == null
               ? null
               : previous.retainedPlan(
                  s, a, b, RoadRecord.readNode(this.payload.getCompound("StartNode")), RoadRecord.readNode(this.payload.getCompound("EndNode"))
               );
            if (plan == null) {
               plan = RoadTunnelFit.plan(a, b, s);
            }

            RoadPlanner.Hint seamA = this.payload.contains("AutoA") ? RoadData.readHint(this.payload.getCompound("AutoA")) : null;
            RoadPlanner.Hint seamB = this.payload.contains("AutoB") ? RoadData.readHint(this.payload.getCompound("AutoB")) : null;
            if (this.payload.getBoolean("LevelEnds")) {
               seamA = RoadConnectionChecks.atLevel(seamA, (double)BlockPos.of(this.payload.getLong("A")).getY());
               seamB = RoadConnectionChecks.atLevel(seamB, (double)BlockPos.of(this.payload.getLong("B")).getY());
            }

            RoadConnectionChecks.require(plan, seamA, seamB);
            int caps = (this.payload.getCompound("AutoA").getBoolean("Linked") ? 0 : 1) | (this.payload.getCompound("AutoB").getBoolean("Linked") ? 0 : 2);
            RoadRecord previewRecord = new RoadRecord(
                  previous == null ? new UUID(0L, 0L) : previous.id(),
                  previous == null ? new UUID(0L, 0L) : previous.owner(),
                  BlockPos.of(this.payload.getLong("A")),
                  BlockPos.of(this.payload.getLong("B")),
                  plan.start(),
                  plan.end(),
                  plan.settings(),
                  this.mode == RoadGeometry.Mode.AUTO,
                  4
               )
               .caps(caps);
            if (previous != null) {
               previewRecord = previous.retainAlignment(
                  previewRecord, s, RoadRecord.readNode(this.payload.getCompound("StartNode")), RoadRecord.readNode(this.payload.getCompound("EndNode"))
               );
            }

            previewRecord = RoadCrossingTransitions.preview(previewRecord, this.payload);
            ClientRoads.preview = previewRecord.mesh();
            candidates.add(previewRecord);
            this.payload.put("Settings", RoadRecord.writeSettings(s));
            if (this.pathButton != null && !this.optionsOnly()) {
               this.pathButton
                  .setMessage(
                     Component.literal(
                        this.mode == RoadGeometry.Mode.AUTO
                           ? "智能识别：" + RoadPlanner.name(plan.settings().mode()) + "  ▸"
                           : "手动：" + RoadPlanner.name(this.mode) + "  ▸"
                     )
                  );
            }

            if (this.pathButton != null && !this.options.routing().controls().isEmpty()) {
               this.pathButton
                  .setMessage(Component.literal("平滑曲线 · " + this.options.routing().controls().size() + (this.options.routing().through() ? " 个经过点" : " 个控制点")));
               this.pathButton.active = false;
            }

            this.status = String.format(Locale.ROOT, "%s · %.1f 格", plan.reason(), plan.mesh().length());
            if (RoadVertical.automatic(s)) {
               double da = plan.start().position().distance(a.node().position());
               double db = plan.end().position().distance(b.node().position());
               boolean rising = RoadVertical.rising(s);
               this.status = String.format(
                  Locale.ROOT,
                  (rising ? "最高" : "最低") + " Y %.2f；两端延长 %.1f / %.1f 格",
                  rising ? RoadVertical.maximum(plan.start(), plan.end(), s) : RoadTunnel.minimum(plan.start(), plan.end(), s),
                  da,
                  db
               );
            }
         }

         this.payload.putBoolean("Advanced", this.advanced);
         this.payload.putBoolean("ExtraPage", this.extraPage);
         this.payload.putBoolean("LiftPage", this.liftPage);
         RoadRenderer.previewCandidates(candidates);
         ClientRoads.storeDraft(this.payload);
         ClientRoads.error = "";
         if (this.save != null) {
            this.save.active = !this.pending;
         }

         return true;
      } catch (IllegalArgumentException var17) {
         this.payload.putBoolean("Advanced", this.advanced);
         this.payload.putBoolean("ExtraPage", this.extraPage);
         this.payload.putBoolean("LiftPage", this.liftPage);
         ClientRoads.storeDraft(this.payload);
         ClientRoads.preview = null;
         RoadRenderer.previewCandidates(List.of());
         this.status = var17 instanceof NumberFormatException ? "请输入完整数值" : var17.getMessage();
         ClientRoads.error = this.status;
         if (this.save != null) {
            this.save.active = false;
         }

         if (this.pathButton != null && this.pathButton != null && !this.optionsOnly()) {
            this.pathButton.setMessage(Component.literal(this.mode == RoadGeometry.Mode.AUTO ? "智能识别：需调整  ▸" : "手动：" + RoadPlanner.name(this.mode) + "  ▸"));
         }

         return false;
      }
   }

   private RoadIndex.Built savedPreview() {
      if (this.savedPreviewAllowed
         && this.payload.getString("Kind").equals("road")
         && this.payload.hasUUID("Id")
         && !this.payload.getBoolean("LevelEnds")
         && !this.payload.getBoolean("ForceJunction")
         && !this.payload.getBoolean("ForceJunctionA")
         && !this.payload.getBoolean("ForceJunctionB")
         && !this.payload.contains("DraftConflict")
         && !this.junctionMode()) {
         RoadIndex.Built built = ClientRoads.INDEX.roads.get(this.payload.getUUID("Id"));
         if (built != null
            && built.record.junction() == null
            && built.record.toll() == null
            && !built.record.railway()
            && built.record.header().equals(this.payload.getCompound("Road"))) {
            RoadRecord r = built.record;
            if (r.start().equals(RoadRecord.readNode(this.payload.getCompound("StartNode")))
               && r.end().equals(RoadRecord.readNode(this.payload.getCompound("EndNode")))) {
               RoadGeometry.Settings expected = RoadProfile.editable(r.settings());
               if (r.automatic()) {
                  expected = RoadPlanner.mode(expected, RoadGeometry.Mode.AUTO, expected.arcDegrees());
               }

               RoadGeometry.Settings actual = new RoadGeometry.Settings(this.mode, this.style, this.roadWidth, this.thickness, this.tension, this.arc)
                  .structure(this.structure)
                  .options(this.options);
               return expected.equals(actual) ? built : null;
            } else {
               return null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private void submit() {
      if (!this.pending && this.refresh()) {
         CompoundTag t = RoadDraft.action(this.payload, this.node);
         if (this.payload.getString("Kind").equals("armRoad")) {
            t = new CompoundTag();
            t.putString("Action", "junctionRoad");
            t.putUUID("Id", this.payload.getUUID("Id"));
            t.putInt("Arm", this.payload.getInt("Arm"));
            t.putInt("Signature", this.payload.getInt("Signature"));
            t.put("Settings", this.payload.getCompound("Settings").copy());
         }

         if (this.sideRoad()) {
            t = SideRoads.action(this.payload);
         }

         if (this.laneRoad()) {
            t = new CompoundTag();
            t.putString("Action", "laneRampRoad");
            t.putUUID("Id", this.payload.getUUID("Id"));
            t.putInt("Signature", this.payload.getInt("Signature"));
            t.put("Settings", this.payload.getCompound("Settings").copy());
         }

         this.pending = true;
         this.save.active = false;
         this.status = "正在建造并清理障碍…";
         ClientRoads.sendAction(t);
      }
   }

   void completed() {
      this.pending = false;
   }

   void requestSuperseded() {
      this.pending = false;
      if (this.save != null) {
         this.save.active = true;
      }

      if (this.delete != null) {
         this.delete.active = true;
      }
   }

   public void previewFailed(String reason) {
      this.pending = false;
      this.status = reason;
      if (this.save != null) {
         this.save.active = false;
      }
   }

   void furnitureStatus(String reason) {
      if (!this.pending && this.serverFailure.isEmpty() && !ClientRoads.conflictPreview) {
         if (!reason.isEmpty()) {
            if (this.furnitureFailure.isEmpty() || !this.status.equals(this.furnitureFailure)) {
               this.beforeFurnitureFailure = this.status;
            }

            this.furnitureFailure = reason;
            this.status = reason;
            ClientRoads.error = reason;
            if (this.save != null) {
               this.save.active = false;
            }
         } else if (!this.furnitureFailure.isEmpty()) {
            if (this.status.equals(this.furnitureFailure)) {
               this.status = this.beforeFurnitureFailure;
            }

            if (ClientRoads.error.equals(this.furnitureFailure)) {
               ClientRoads.error = "";
            }

            this.furnitureFailure = "";
            if (this.save != null && ClientRoads.preview != null && !ClientRoads.conflictPreview) {
               this.save.active = true;
            }
         }
      }
   }

   public void failed(String reason) {
      this.failed(reason, new CompoundTag());
   }

   public void failed(String reason, CompoundTag reply) {
      this.showFailure(reason, ClientRoads.conflictRoads(reply));
   }

   void failed(String reason, List<RoadGeometry.Mesh> roads) {
      this.showFailure(reason, roads);
   }

   void failureGeometry(List<RoadGeometry.Mesh> roads) {
      this.failureRoads = roads;
   }

   private void showFailure(String reason, List<RoadGeometry.Mesh> roads) {
      this.pending = false;
      this.serverFailure = reason;
      this.failureRoads = roads;
      this.debounce = 0;
      this.status = RoadConflictIds.display(reason);
      ClientRoads.error = this.status;
      ClientRoads.conflictPreview = !roads.isEmpty();
      if (!roads.isEmpty()) {
         ClientRoads.preview = roads.get(0);
         ClientRoads.nodePreviews = roads;
      }

      if (this.save != null) {
         this.save.active = true;
      }

      if (this.delete != null) {
         this.delete.active = true;
      }

      if (this.previewButton != null) {
         this.previewButton.active = true;
      }

      if (this.failureButton != null) {
         this.failureButton.visible = true;
      }
   }

   private void clearFailure() {
      if (!this.serverFailure.isEmpty()) {
         this.serverFailure = "";
         this.failureRoads = List.of();
         ClientRoads.conflictPreview = false;
         ClientRoads.preview = null;
         ClientRoads.nodePreviews = List.of();
         if (this.previewButton != null) {
            this.previewButton.setMessage(Component.literal("实景预览"));
         }

         if (this.failureButton != null) {
            this.failureButton.visible = false;
         }
      }
   }

   public void tick() {
      super.tick();
      if (this.debounce > 0 && --this.debounce == 0) {
         this.refresh();
      }

      long revision = ClientRoads.INDEX.revision();
      if (this.savedPreviewAllowed
         && !this.pending
         && this.debounce == 0
         && this.serverFailure.isEmpty()
         && !ClientRoads.conflictPreview
         && revision != this.observedSavedRevision) {
         this.observedSavedRevision = revision;
         RoadIndex.Built saved = this.savedPreview();
         if (saved != null && ClientRoads.preview != saved.mesh) {
            this.refresh();
         }
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void onClose() {
      if (this.serverFailure.isEmpty()) {
         this.refresh();
      }

      super.onClose();
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.left, this.top, this.left + 288, this.top + 238, -334356180);
      g.fill(this.left, this.top, this.left + 4, this.top + 238, -11477293);
      g.drawString(this.font, this.node ? "端点微调" : "SR · 智能铺路", this.left + 12, this.top + 12, 16777215, false);
      g.drawString(this.font, this.node ? "普通铺路无需调整这些参数" : (this.optionsOnly() ? "" : "选两点 → 自动预览 → 建造"), this.left + 12, this.top + 27, 11060171, false);
      if (this.node) {
         this.label(g, "X 偏移", "x");
         this.label(g, "Y 偏移", "y");
         this.label(g, "Z 偏移", "z");
         this.label(g, "朝向 / 度", "yaw");
         this.label(g, "坡度 / %", "grade");
      } else if (!this.optionsOnly() && this.advanced) {
         this.label(g, "单车道宽 / 格", "width");
         this.label(g, "厚度 / 格", "thickness");
         this.label(g, "曲线强度", "tension");
         this.label(g, "圆弧角度 / 度", "angle");
      } else if (!this.optionsOnly() && ClientRoads.preview != null) {
         g.drawString(
            this.font,
            "Y " + this.format(ClientRoads.preview.first().center().y()) + " → " + this.format(ClientRoads.preview.last().center().y()),
            this.left + 148,
            this.top + 139,
            12309468,
            false
         );
      }

      if (!this.optionsOnly() && !this.node && (this.payload.getInt("DegreeA") > 0 || this.payload.getInt("DegreeB") > 0)) {
         g.drawString(
            this.font, this.font.plainSubstrByWidth(this.status, 264), this.left + 12, this.top + 196, ClientRoads.error.isEmpty() ? 12178653 : 16751497, false
         );
      } else if (!this.optionsOnly()) {
         g.drawWordWrap(this.font, Component.literal(this.status), this.left + 12, this.top + 180, 264, ClientRoads.error.isEmpty() ? 12178653 : 16751497);
      }

      if (!this.node && (this.width >= 512 || this.optionsOnly())) {
         int sx = this.width >= 512 ? this.left + 294 : this.left + 12;
         if (this.width >= 512) {
            g.fill(sx - 6, this.top, sx + 200, this.top + 238, -334356180);
         }

         if (this.mapPage) {
            this.renderMap(g, sx, this.top + 27, this.width >= 512 ? 194 : 264);
         } else {
            g.drawString(
               this.font,
               this.liftPage
                  ? "高度控制点与自动避让"
                  : new String[]{"基本选项", "人行道", "桥梁 / 跨线桥", "地下通道", "龙门架 / 隔音板", "道路 / 人行道路灯", "绿化 / 包边", "隔离带植树", "中央隔离宽度"}[this.optionsPage]
                     + " · "
                     + (this.optionsPage + 1)
                     + (this.advanced ? "/9" : "/8"),
               sx,
               this.top + 27,
               16777215,
               false
            );
            if (this.optionsPage >= 5 && !this.liftPage) {
               this.label(g, "道路路灯间距 / 8–96 格", "lampSpacing");
               this.label(g, "人行道灯距 / 8–96 格", "walkLampSpacing");
               this.label(g, "绿化间距 / 8–64 格", "plantingSpacing");
               this.label(g, "植树间距 / 8–64 格", "medianTreeSpacing");
               this.label(g, "植树间距 / 8–64 格", "separatorTreeSpacing");
               if (this.optionsPage < 7) {
                  g.drawWordWrap(
                     this.font,
                     Component.literal(this.optionsPage == 5 ? "灯型按道路断面自动选择；高速不设路灯，隧道使用洞内照明。" : "绿化仅地面段生成；与灯位冲突时让位。空间不足自动拓宽人行道，盲道保留在外侧。"),
                     sx,
                     this.top + 171,
                     this.width >= 512 ? 194 : 264,
                     12309468
                  );
               }
            } else if (this.optionsPage >= 2 && !this.liftPage) {
               if (this.optionsPage == 2) {
                  this.label(g, "手动桥跨 / 格", "bridgeSpan");
                  this.label(g, "抬升高度 / 格", "bridgeRise");
               } else if (this.optionsPage == 3) {
                  this.label(g, "边缘净高 / 格", "tunnelHeight");
                  this.label(g, "下潜深度 / 格", "tunnelDepth");
               } else {
                  this.label(g, "布设间距 / 格", "gantrySpacing");
               }

               String help = this.optionsPage == 2
                  ? "小河用梁桥，跨道路用跨线桥；大河可用拱桥或斜拉桥，跨海可用悬索桥或连续梁。斜拉 / 悬索可自动按全长配跨。抬升从较高端向上量。"
                  : (this.optionsPage == 3 ? "两端为洞口；深度从较低洞口向下量，0 沿端点。" : "隔音板用于高架，左右按 A→B。龙门架不用于匝道、有顶隧道或开放隧道（含自动识别）。");
               if (this.optionsPage == 3 && this.options.infrastructure().tunnelDepth() > 0.0) {
                  double low = Math.min(this.payload.getCompound("StartNode").getDouble("Y"), this.payload.getCompound("EndNode").getDouble("Y"))
                     - this.options.infrastructure().tunnelDepth();
                  help = String.format(Locale.ROOT, "最低路面 Y %.2f；深度从较低洞口向下量。", low);
               }

               if ((this.optionsPage == 2 || this.optionsPage == 3) && !this.status.isEmpty()) {
                  help = this.status;
               }

               if ((this.optionsPage == 2 || this.optionsPage == 3) && !ClientRoads.error.isEmpty()) {
                  help = ClientRoads.error;
               }

               g.drawWordWrap(
                  this.font,
                  Component.literal(help),
                  sx,
                  this.top + (this.optionsPage == 2 ? 190 : (this.optionsPage == 3 ? (this.optionsOnly() ? 181 : 166) : 158)),
                  this.width >= 512 ? 194 : 264,
                  this.optionsPage == 3 && !ClientRoads.error.isEmpty() ? 16751497 : 12309468
               );
            } else if (this.optionsPage != 1 || this.liftPage) {
               if (this.liftPage) {
                  this.label(g, "沿路位置 / %", "liftPosition");
                  this.label(g, "相对抬升 / 格", "lift");
                  g.drawWordWrap(this.font, Component.literal("保持两端高度与接入坡度；正值上抬，负值下挖。"), sx, this.top + 128, this.width >= 512 ? 194 : 264, 12309468);
               } else {
                  String note = !this.laneRoad() && !this.style.connectorRamp()
                     ? (RoadProfile.catalog(this.style).type() == RoadProfile.Type.HIGHWAY ? "" : (RoadProfile.modern(this.style) ? "" : "旧断面保留；切换样式升级"))
                     : "一般箭头关闭；额外扩入保留并线导向";
                  g.drawWordWrap(this.font, Component.literal(this.width >= 512 ? note : ""), sx, this.top + 214, this.width >= 512 ? 110 : 154, 12309468);
               }
            }
         }
      }

      super.render(g, mx, my, dt);
   }

   private double laneWidth() {
      return RoadProfile.modern(this.style)
         ? RoadProfile.layout(
               new RoadGeometry.Settings(RoadGeometry.Mode.AUTO, this.style, this.roadWidth, this.thickness, this.tension, this.arc).options(this.options),
               this.roadWidth
            )
            .laneWidth()
         : this.roadWidth;
   }

   private void setLaneWidth(double value) {
      if (!this.laneRoad() && !this.style.connectorRamp()) {
         if (RoadProfile.modern(this.style)) {
            RoadGeometry.Settings sized = RoadLanes.width(
               new RoadGeometry.Settings(RoadGeometry.Mode.AUTO, this.style, this.roadWidth, this.thickness, this.tension, this.arc).options(this.options),
               value
            );
            this.roadWidth = sized.width();
            this.options = sized.options();
         } else {
            this.roadWidth = value;
         }
      } else {
         RoadGeometry.Settings sized = LaneRampAlignment.usableWidth(
            new RoadGeometry.Settings(RoadGeometry.Mode.AUTO, this.style, this.roadWidth, this.thickness, this.tension, this.arc).options(this.options), value
         );
         this.roadWidth = sized.width();
         this.options = sized.options();
      }
   }

   private String typeName() {
      if (!this.laneRoad() && !this.style.connectorRamp()) {
         return RoadProfile.catalog(this.style).type() == RoadProfile.Type.HIGHWAY ? "高速道路" : "普通道路";
      } else {
         return RoadProfile.highway(this.style) ? "自由匝道（高速）" : "自由匝道（普通）";
      }
   }

   private String styleName() {
      if (this.laneRoad() || this.style.connectorRamp()) {
         return "单车道匝道";
      } else if (RoadProfile.modern(this.style) && !this.advanced) {
         RoadLanes.Counts counts = RoadLanes.counts(this.style, this.options);
         return (counts.reverse() > 0 ? counts.forward() + "+" + counts.reverse() : "单向 " + counts.forward()) + " 车道 ▸";
      } else {
         return RoadProfile.modern(this.style) ? RoadLanes.counts(this.style, this.options).label() + " ▸" : STYLES[this.style.ordinal()];
      }
   }

   private void selectStyle(RoadGeometry.Style next, boolean resetWidth) {
      double lane = RoadProfile.modern(this.style) ? this.laneWidth() : 4.0;
      this.style = next;
      if (RoadProfile.catalog(this.style).type() == RoadProfile.Type.HIGHWAY) {
         this.payload.putBoolean("ForceJunction", false);
      }

      if (RoadProfile.catalog(this.style).type() != RoadProfile.Type.ORDINARY) {
         this.options = this.options.sidewalk(this.options.sidewalk().enabled(false));
      }

      if (resetWidth) {
         lane = RoadProfile.catalog(this.style).laneWidth();
      }

      this.setLaneWidth(lane);
      this.rebuildWidgets();
   }

   private void openLaneConfiguration() {
      if (!this.laneRoad() && !this.style.connectorRamp()) {
         this.minecraft
            .setScreen(
               new RoadLaneConfigScreen(
                  this, RoadProfile.highway(this.style) ? RoadProfile.Type.HIGHWAY : RoadProfile.Type.ORDINARY, RoadLanes.counts(this.style, this.options)
               )
            );
      }
   }

   void applyLaneConfiguration(RoadProfile.Type type, RoadLanes.Counts counts, boolean resetWidth) {
      double lane = resetWidth ? (double)(type == RoadProfile.Type.HIGHWAY ? 5 : 4) : this.laneWidth();
      this.style = RoadLanes.carrier(type, counts, RoadProfile.catalog(this.style).median());
      this.options = this.options.lanes(counts).ends(RoadTransitions.Ends.NONE);
      if (type == RoadProfile.Type.HIGHWAY) {
         this.payload.putBoolean("ForceJunction", false);
         this.options = this.options.sidewalk(this.options.sidewalk().enabled(false));
      }

      this.setLaneWidth(lane);
      this.changed();
      this.rebuildWidgets();
   }

   private Button option(String text, int x, int y, int w, Runnable action) {
      int buttonHeight = 20;
      if (this.optionsOnly() && this.optionsPage == 0 && !this.liftPage && y >= this.top + 40) {
         y = this.top + 55 + (y - this.top - 40) * 5 / 6;
         buttonHeight = 18;
      }

      return (Button)this.addRenderableWidget(Button.builder(Component.literal(text), b -> {
         if (this.capture()) {
            action.run();
            this.rebuildWidgets();
         }
      }).bounds(x, y, w, buttonHeight).build());
   }

   private void page(int delta) {
      List<Integer> pages = this.advanced ? List.of(0, 1, 2, 3, 4, 5, 6, 7, 8) : List.of(0, 1, 2, 3, 4, 5, 6, 7);
      this.optionsPage = pages.get(Math.floorMod(pages.indexOf(this.optionsPage) + delta, pages.size()));
      this.liftPage = false;
   }

   private void changeMedianWidth(boolean green, double delta) {
      double lane = this.laneWidth();
      RoadPlantings p = this.options.streetscape().plantings();
      this.options = this.options
         .streetscape(this.options.streetscape().plantings(green ? p.greenWidth(p.greenWidth() + delta) : p.railGap(p.railGap() + delta)));
      this.setLaneWidth(lane);
   }

   private void medianWidthRow(int x, int y, int w, boolean green) {
      RoadPlantings p = this.options.streetscape().plantings();
      double value = green ? p.greenWidth() : p.railGap();
      this.option("−", x, y, 30, () -> this.changeMedianWidth(green, -0.5)).active = value > (green ? 0.5 : 0.0);
      this.option((green ? "绿化宽度：" : "护栏间距：") + this.format(value) + " 格", x + 34, y, w - 68, () -> {
      }).active = false;
      this.option("+", x + w - 30, y, 30, () -> this.changeMedianWidth(green, 0.5)).active = value < (double)(green ? 8 : 7);
   }

   private void buildOptions(int x, int y, int w) {
      if (this.mapPage) {
         List<RoadProfile.Control> cs = this.options.routing().controls();
         this.controlIndex = Math.max(0, Math.min(this.controlIndex, cs.size() - 1));
         if (!cs.isEmpty()) {
            this.option("◀", x, this.top + 150, 24, () -> this.controlIndex = Math.floorMod(this.controlIndex - 1, cs.size()));
            this.option(
               (this.options.routing().through() ? "经过点 " : "控制点 ") + (this.controlIndex + 1) + " / " + cs.size(), x + 28, this.top + 150, w - 56, () -> {
               }
            );
            this.option("▶", x + w - 24, this.top + 150, 24, () -> this.controlIndex = (this.controlIndex + 1) % cs.size());
            RoadGeometry.V p = cs.get(this.controlIndex).position();
            int fw = (w - 8) / 3;
            String[] keys = new String[]{"controlX", "controlY", "controlZ"};
            double[] values = new double[]{p.x(), p.y(), p.z()};

            for (int i = 0; i < 3; i++) {
               EditBox f = new EditBox(
                  this.font,
                  x + i * (fw + 4),
                  this.top + 184,
                  fw,
                  18,
                  Component.literal((this.options.routing().through() ? "经过点 " : "控制点 ") + "XYZ".charAt(i))
               );
               f.setMaxLength(24);
               f.setValue(Double.toString(values[i]));
               f.setResponder(v -> this.changed());
               this.fields.put(keys[i], f);
               this.addRenderableWidget(f);
            }
         }

         this.option("返回", x + w - 58, this.width < 512 ? this.top + 10 : this.top + 206, 58, () -> {
            this.mapPage = false;
            if (this.width < 512) {
               this.extraPage = false;
            }
         });
      } else {
         int navY = this.liftPage ? (this.optionsOnly() ? this.top + 182 : this.top + 206) : (this.optionsOnly() ? this.top + 31 : this.top + 206);
         this.option("◀", x + w - 58, navY, 26, () -> this.page(-1));
         this.option("▶", x + w - 28, navY, 26, () -> this.page(1));
         if (this.optionsOnly() && this.optionsPage >= 1 && !this.liftPage) {
            y = Math.max(y, this.top + 55);
         }

         if (this.optionsPage >= 7 && !this.liftPage) {
            if (this.optionsPage == 8) {
               this.medianWidthRow(x, y, w, true);
               this.medianWidthRow(x, y + 28, w, false);
            } else {
               for (boolean median : new boolean[]{true, false}) {
                  RoadPlantings plants = this.options.streetscape().plantings();
                  RoadProfile.Catalog catalog = RoadProfile.catalog(this.style);
                  RoadStreetscape.Planting type = median ? plants.medianTrees() : plants.separatorTrees();
                  int row = y + (median ? 0 : 78);
                  boolean available = this.structure != RoadGeometry.Structure.TUNNEL
                     && (
                        median
                           ? catalog.twoWay() && catalog.median() == RoadProfile.Median.GREEN && plants.greenWidth() >= 1.5
                           : this.options.cycle()
                              && !this.options.streetscape().parking()
                              && this.options.streetscape().separator() == RoadStreetscape.Separator.GREEN
                     );
                  this.option(
                        (median ? "中央：" : "非机动车隔离：") + type.label + " ▸",
                        x,
                        row,
                        w,
                        () -> {
                           RoadPlantings current = this.options.streetscape().plantings();
                           RoadStreetscape.Planting[] types = RoadStreetscape.Planting.values();
                           RoadStreetscape.Planting next = types[(type.ordinal() + 1) % 5];
                           this.options = this.options
                              .streetscape(this.options.streetscape().plantings(median ? current.medianTrees(next) : current.separatorTrees(next)));
                        }
                     )
                     .active = available;
                  String key = median ? "medianTreeSpacing" : "separatorTreeSpacing";
                  this.field("植树间距 / 8–64 格", key, median ? plants.medianSpacing() : plants.separatorSpacing(), x, row + 27);
                  this.fields.get(key).setWidth(w);
               }
            }

            if (this.optionsOnly()) {
               this.option("返回道路设置", x + 164, this.top + 10, 100, () -> this.extraPage = false);
            }
         } else if (this.optionsPage >= 5 && !this.liftPage) {
            if (!RoadStreetscape.walkLampAllowed(this.options, this.structure, this.style) && this.options.streetscape().walkLamps()) {
               this.options = this.options.streetscape(this.options.streetscape().walkLamps(false));
            }

            RoadStreetscape.Config c = this.options.streetscape();
            boolean eligible = RoadProfile.catalog(this.style).type() == RoadProfile.Type.ORDINARY;
            if (this.optionsPage == 5) {
               this.field("道路路灯间距 / 8–96 格", "lampSpacing", c.lampSpacing(), x, y);
               this.fields.get("lampSpacing").setWidth(w);
               this.option(
                     "人行道路灯：" + (c.walkLamps() ? "开启" : (RoadStreetscape.walkLampAllowed(this.options, this.structure, this.style) ? "关闭" : "关闭（当前断面禁用）")),
                     x,
                     y + 42,
                     w,
                     () -> this.options = this.options.streetscape(this.options.streetscape().walkLamps(!this.options.streetscape().walkLamps()))
                  )
                  .active = eligible && RoadStreetscape.walkLampAllowed(this.options, this.structure, this.style);
               this.field("人行道灯距 / 8–96 格", "walkLampSpacing", c.walkLampSpacing(), x, y + 68);
               this.fields.get("walkLampSpacing").setWidth(w);
            } else {
               this.option(
                     "人行道绿化：" + c.planting().label + " ▸",
                     x,
                     y,
                     w,
                     () -> {
                        RoadStreetscape.Planting[] values = RoadStreetscape.Planting.values();
                        this.options = this.options
                           .streetscape(this.options.streetscape().planting(values[(this.options.streetscape().planting().ordinal() + 1) % values.length]));
                     }
                  )
                  .active = eligible && this.options.sidewalk().enabled() && this.structure != RoadGeometry.Structure.TUNNEL;
               this.field("绿化间距 / 8–64 格", "plantingSpacing", c.plantingSpacing(), x, y + 36);
               this.fields.get("plantingSpacing").setWidth(w);
               this.option(
                  "道路包边：" + c.apron().label + " ▸",
                  x,
                  y + 72,
                  w,
                  () -> {
                     RoadStreetscape.Apron[] values = RoadStreetscape.Apron.values();
                     this.options = this.options
                        .streetscape(this.options.streetscape().apron(values[(this.options.streetscape().apron().ordinal() + 1) % values.length]));
                  }
               );
            }

            if (this.optionsOnly()) {
               this.option("返回道路设置", x + 164, this.top + 10, 100, () -> this.extraPage = false);
            }
         } else if (this.optionsPage >= 2 && !this.liftPage) {
            RoadInfrastructure.Config infra = this.options.infrastructure();
            if (this.optionsPage != 2 && this.optionsPage != 3) {
               this.option(
                  "龙门架：" + (infra.gantry() == RoadInfrastructure.Gantry.OFF ? "关闭" : "开启"),
                  x,
                  y,
                  w,
                  () -> this.options = this.options
                        .infrastructure(
                           infra.gantry(infra.gantry() == RoadInfrastructure.Gantry.OFF ? RoadInfrastructure.Gantry.FRAME : RoadInfrastructure.Gantry.OFF)
                        )
               );
               this.field("布设间距 / 格", "gantrySpacing", infra.spacing(), x, y + 32);
               this.option(
                     "高架外侧：" + this.options.outerRail().label + " ▸",
                     x,
                     y + 70,
                     w,
                     () -> this.options = this.options
                           .outerRail(RoadProfile.OuterRail.values()[(this.options.outerRail().ordinal() + 1) % RoadProfile.OuterRail.values().length])
                  )
                  .active = this.structure != RoadGeometry.Structure.TUNNEL;
            } else {
               boolean bridge = this.optionsPage == 2;
               int half = (w - 8) / 2;
               int right = x + (w + 8) / 2;
               this.option(bridge ? "启用桥梁" : "启用隧道", x, y, half, () -> this.structure = bridge ? RoadGeometry.Structure.BRIDGE : RoadGeometry.Structure.TUNNEL);
               this.option(
                  (bridge ? infra.bridge().label : infra.tunnel().label) + " ▸",
                  right,
                  y,
                  half,
                  () -> {
                     this.options = this.options
                        .infrastructure(
                           bridge
                              ? infra.bridge(RoadInfrastructure.Bridge.values()[(infra.bridge().ordinal() + 1) % RoadInfrastructure.Bridge.values().length])
                              : infra.tunnel(RoadInfrastructure.Tunnel.values()[(infra.tunnel().ordinal() + 1) % 2])
                        );
                     this.structure = bridge ? RoadGeometry.Structure.BRIDGE : RoadGeometry.Structure.TUNNEL;
                  }
               );
               String upper = bridge ? "bridgeSpan" : "tunnelHeight";
               String lower = bridge ? "bridgeRise" : "tunnelDepth";
               this.field(bridge ? "手动桥跨 / 格" : "边缘净高 / 格", upper, bridge ? infra.span() : infra.headroom(), x, y + 32);
               this.fields.get(upper).setWidth(half);
               this.field(bridge ? "抬升高度 / 格" : "下潜深度 / 格", lower, bridge ? infra.bridgeRise() : infra.tunnelDepth(), x, y + 66);
               this.fields.get(lower).setWidth(half);
               if (bridge) {
                  this.option(
                        "配跨：" + (infra.autoSpan() ? "自动" : "手动"),
                        right,
                        y + 44,
                        half,
                        () -> this.options = this.options.infrastructure(this.options.infrastructure().autoSpan(!infra.autoSpan()))
                     )
                     .active = infra.bridge() == RoadInfrastructure.Bridge.CABLE || infra.bridge() == RoadInfrastructure.Bridge.SUSPENSION;
               } else {
                  this.option("下潜：" + (infra.tunnelDepth() > 0.0 ? "开" : "关"), right, y + 44, half, () -> {
                     this.options = this.options.lift(0.5, 0.0).infrastructure(this.options.infrastructure().depth(infra.tunnelDepth() > 0.0 ? 0.0 : 8.0));
                     this.structure = RoadGeometry.Structure.TUNNEL;
                  });
               }

               this.option(
                  "端点：" + infra.adjustment().label,
                  right,
                  y + 78,
                  half,
                  () -> {
                     RoadTunnelFit.Adjustment[] modes = RoadTunnelFit.Adjustment.values();
                     this.options = this.options
                        .infrastructure(this.options.infrastructure().adjustment(modes[(infra.adjustment().ordinal() + 1) % modes.length]));
                  }
               );
               this.option(
                  infra.maxGrade() == 0.0 ? "最大坡度：旧版 ▸" : String.format(Locale.ROOT, "最大坡度：%.0f%% ▸", infra.maxGrade() * 100.0),
                  x,
                  y + 102,
                  w,
                  () -> {
                     double grade = infra.maxGrade();
                     double next = grade == 0.0
                        ? 0.06
                        : (grade < 0.04 ? 0.04 : (grade < 0.06 ? 0.06 : (grade < 0.08 ? 0.08 : (grade < 0.1 ? 0.1 : (grade < 0.12 ? 0.12 : 0.04)))));
                     this.options = this.options.infrastructure(this.options.infrastructure().grade(next));
                  }
               );
               if (bridge) {
                  this.option(
                     "高架中央转护栏：" + infra.medianConversion().label + " ▸",
                     x,
                     y + 126,
                     w,
                     () -> {
                        RoadInfrastructure.MedianConversion[] modes = RoadInfrastructure.MedianConversion.values();
                        this.options = this.options
                           .infrastructure(this.options.infrastructure().medianConversion(modes[(infra.medianConversion().ordinal() + 1) % modes.length]));
                     }
                  );
               }
            }

            if (this.optionsOnly()) {
               this.option("返回道路设置", x + 164, this.top + 10, 100, () -> this.extraPage = false);
            }
         } else if (this.optionsPage == 1 && !this.liftPage) {
            boolean eligiblex = this.structure != RoadGeometry.Structure.TUNNEL && RoadProfile.catalog(this.style).type() == RoadProfile.Type.ORDINARY;
            RoadSidewalks.Config walk = this.options.sidewalk();
            this.option(
                  (walk.enabled() ? "☑" : "☐") + " 智能人行道",
                  x,
                  y,
                  w,
                  () -> this.options = this.options.sidewalk(this.options.sidewalk().enabled(!this.options.sidewalk().enabled()))
               )
               .active = eligiblex;
            this.option(
                  "铺设位置：" + walk.side().label,
                  x,
                  y + 24,
                  w,
                  () -> this.options = this.options
                        .sidewalk(this.options.sidewalk().side(RoadSidewalks.Side.values()[(this.options.sidewalk().side().ordinal() + 1) % 3]))
               )
               .active = eligiblex;
            this.option(
                  "−",
                  x,
                  y + 48,
                  26,
                  () -> this.options = this.options.sidewalk(this.options.sidewalk().width(Math.max(1, this.options.sidewalk().width() - 1)))
               )
               .active = eligiblex;
            this.option("宽度 " + walk.width() + " 格", x + 30, y + 48, w - 60, () -> {
            }).active = false;
            this.option(
                  "+",
                  x + w - 26,
                  y + 48,
                  26,
                  () -> this.options = this.options.sidewalk(this.options.sidewalk().width(Math.min(15, this.options.sidewalk().width() + 1)))
               )
               .active = eligiblex;
            this.option(
                  "材质：" + RoadSidewalks.Finish.of(walk.material()).label + " ▸",
                  x,
                  y + 76,
                  w,
                  () -> {
                     RoadSidewalks.Finish[] values = RoadSidewalks.Finish.values();
                     this.options = this.options
                        .sidewalk(
                           this.options
                              .sidewalk()
                              .material(values[(RoadSidewalks.Finish.of(this.options.sidewalk().material()).ordinal() + 1) % values.length].id)
                        );
                  }
               )
               .active = eligiblex;
            this.option(
                  walk.width() >= 2 ? "盲道：" + (walk.tactile() ? "开" : "关") : "盲道：宽度需至少 2 格",
                  x,
                  y + 104,
                  w,
                  () -> this.options = this.options.sidewalk(this.options.sidewalk().tactile(!this.options.sidewalk().tactile()))
               )
               .active = eligiblex && walk.enabled() && walk.width() >= 2;
            if (this.optionsOnly()) {
               this.option("返回道路设置", x + 164, this.top + 10, 100, () -> this.extraPage = false);
            }
         } else if (this.liftPage) {
            this.field("沿路位置 / %", "liftPosition", this.options.liftPosition() * 100.0, x, y + 4);
            this.fields.get("liftPosition").setWidth((w - 8) / 2);
            this.field("相对抬升 / 格", "lift", this.options.liftHeight(), x + (w + 8) / 2, y + 4);
            this.fields.get("lift").setWidth((w - 8) / 2);
            this.option(
               "降低 1 格",
               x,
               y + 40,
               (w - 8) / 2,
               () -> this.options = this.options.lift(this.options.liftPosition(), Math.max(-64.0, this.options.liftHeight() - 1.0))
            );
            this.option(
               "抬高 1 格",
               x + (w + 8) / 2,
               y + 40,
               (w - 8) / 2,
               () -> this.options = this.options.lift(this.options.liftPosition(), Math.min(64.0, this.options.liftHeight() + 1.0))
            );
            this.option("重置控制点", x, y + 64, w, () -> this.options = this.options.lift(0.5, 0.0));
            this.option("返回额外选项", x, y + 126, w, () -> this.liftPage = false);
            if (this.optionsOnly()) {
               this.option("返回道路设置", x, y + 150, w, () -> this.extraPage = false);
            }
         } else {
            this.option(
               (this.options.leftTraffic() ? "☑" : "☐") + " 靠左行驶（默认靠右）", x, y, w, () -> this.options = this.options.traffic(!this.options.leftTraffic())
            );
            RoadProfile.Catalog cx = RoadProfile.catalog(this.style);
            boolean ordinary = cx.type() == RoadProfile.Type.ORDINARY;
            this.option(
                  RoadProfile.medianName(cx.median()) + " ▸",
                  x,
                  y + 24,
                  w,
                  () -> {
                     if (cx.twoWay() && cx.type() != RoadProfile.Type.LEGACY) {
                        List<RoadProfile.Median> choices = cx.type() == RoadProfile.Type.HIGHWAY
                           ? List.of(RoadProfile.Median.RAIL, RoadProfile.Median.GREEN)
                           : (
                              cx.lanes() == 2
                                 ? List.of(
                                    RoadProfile.Median.DOUBLE_YELLOW, RoadProfile.Median.DASHED_YELLOW, RoadProfile.Median.RAIL, RoadProfile.Median.GREEN
                                 )
                                 : List.of(RoadProfile.Median.DOUBLE_YELLOW, RoadProfile.Median.RAIL, RoadProfile.Median.GREEN)
                           );
                        RoadProfile.Median m = choices.get((choices.indexOf(cx.median()) + 1) % choices.size());
                        double lane = this.laneWidth();
                        this.style = RoadLanes.carrier(cx.type(), RoadLanes.counts(this.style, this.options), m);
                        this.setLaneWidth(lane);
                     }
                  }
               )
               .active = cx.twoWay() && cx.type() != RoadProfile.Type.LEGACY;
            this.option(
                  this.options.cycleFinish().label,
                  x,
                  y + 48,
                  w,
                  () -> {
                     double lane = this.laneWidth();
                     this.options = this.options
                        .cycleFinish(
                           RoadProfile.Options.CycleFinish.values()[(this.options.cycleFinish().ordinal() + 1)
                              % (
                                 this.structure == RoadGeometry.Structure.TUNNEL
                                    ? RoadProfile.Options.CycleFinish.values().length - 1
                                    : RoadProfile.Options.CycleFinish.values().length
                              )]
                        );
                     this.setLaneWidth(lane);
                  }
               )
               .active = ordinary;
            this.option(
                  "非机动车分隔：" + this.options.streetscape().separator().label,
                  x,
                  y + 72,
                  w,
                  () -> {
                     double lane = this.laneWidth();
                     this.options = this.options
                        .streetscape(
                           this.options.streetscape().separator(RoadStreetscape.Separator.values()[(this.options.streetscape().separator().ordinal() + 1) % 3])
                        );
                     this.setLaneWidth(lane);
                  }
               )
               .active = ordinary && this.options.cycle() && !this.options.streetscape().parking() && this.structure != RoadGeometry.Structure.TUNNEL;
            this.option((this.options.curb() ? "☑" : "☐") + " 最外侧马路牙子", x, y + 96, w, () -> {
               double lane = this.laneWidth();
               this.options = this.options.extras(this.options.cycle(), this.options.cycleRail(), !this.options.curb());
               this.setLaneWidth(lane);
            }).active = ordinary;
            this.option("中间控制点：" + this.format(this.options.liftHeight()) + " 格 ▸", x, y + 120, w, () -> this.liftPage = true);
            this.option(
               (this.options.routing().fitEdges() ? "☑" : "☐") + " 直路边缘对齐方块",
               x,
               y + 144,
               w,
               () -> this.options = this.options.route(this.options.routing().fit(!this.options.routing().fitEdges()))
            );
            if (this.optionsOnly()) {
               this.option("返回道路设置", x + 164, this.top + 10, 100, () -> this.extraPage = false);
            }
         }
      }
   }

   private void label(GuiGraphics g, String text, String key) {
      EditBox f = this.fields.get(key);
      if (f != null) {
         g.drawString(this.font, text, f.getX(), f.getY() - 11, 12309468, false);
      }
   }

   private double[] mapTransform(int x, int y, int w) {
      RoadGeometry.Mesh mesh = ClientRoads.preview;
      double minX = Double.POSITIVE_INFINITY;
      double minZ = minX;
      double maxX = -minX;
      double maxZ = -minX;
      ArrayList<RoadGeometry.V> points = new ArrayList<>();
      if (mesh != null) {
         for (RoadGeometry.Sample sample : mesh.samples()) {
            points.add(sample.center());
         }
      }

      for (RoadProfile.Control c : this.options.routing().controls()) {
         points.add(c.position());
      }

      if (points.isEmpty()) {
         return new double[]{0.0, 0.0, 1.0, (double)(x + 8), (double)(y + 20)};
      } else {
         for (RoadGeometry.V p : points) {
            minX = Math.min(minX, p.x());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
         }

         double scale = Math.min((double)(w - 24) / Math.max(1.0, maxX - minX), 90.0 / Math.max(1.0, maxZ - minZ));
         return new double[]{minX, minZ, scale, (double)x + ((double)w - (maxX - minX) * scale) / 2.0, (double)(y + 22) + (90.0 - (maxZ - minZ) * scale) / 2.0};
      }
   }

   private static int mapX(RoadGeometry.V p, double[] t) {
      return (int)Math.round(t[3] + (p.x() - t[0]) * t[2]);
   }

   private static int mapY(RoadGeometry.V p, double[] t) {
      return (int)Math.round(t[4] + (p.z() - t[1]) * t[2]);
   }

   private static void mapLine(GuiGraphics g, RoadGeometry.V a, RoadGeometry.V b, double[] t, int color) {
      int ax = mapX(a, t);
      int ay = mapY(a, t);
      int bx = mapX(b, t);
      int by = mapY(b, t);
      int steps = Math.max(Math.abs(ax - bx), Math.abs(ay - by));

      for (int i = 0; i <= steps; i++) {
         double q = steps == 0 ? 0.0 : (double)i / (double)steps;
         int x = (int)Math.round((double)ax + (double)(bx - ax) * q);
         int y = (int)Math.round((double)ay + (double)(by - ay) * q);
         g.fill(x, y, x + 1, y + 1, color);
      }
   }

   private void renderMap(GuiGraphics g, int x, int y, int w) {
      g.drawString(this.font, "俯视图 · 北 ↑" + (this.options.routing().through() ? " · 经过点" : ""), x, y, 16777215, false);
      g.fill(x, y + 17, x + w, this.top + 144, -16181474);
      double[] t = this.mapTransform(x, y, w);
      RoadGeometry.Mesh mesh = ClientRoads.preview;
      List<RoadProfile.Control> cs = this.options.routing().controls();
      if (mesh != null) {
         RoadGeometry.V previous = mesh.first().center();

         for (RoadProfile.Control c : cs) {
            mapLine(g, previous, c.position(), t, -10326916);
            previous = c.position();
         }

         mapLine(g, previous, mesh.last().center(), t, -10326916);
         previous = mesh.first().center();

         for (RoadGeometry.Sample p : mesh.samples()) {
            mapLine(g, previous, p.center(), t, -10492971);
            previous = p.center();
         }

         for (int i = 0; i < 2; i++) {
            RoadGeometry.V p = i == 0 ? mesh.first().center() : mesh.last().center();
            int px = mapX(p, t);
            int py = mapY(p, t);
            g.fill(px - 2, py - 2, px + 3, py + 3, -1118482);
            g.drawString(this.font, i == 0 ? "A" : "B", px + 4, py - 3, 16777215, false);
         }
      }

      for (int i = 0; i < cs.size(); i++) {
         RoadGeometry.V p = cs.get(i).position();
         int px = mapX(p, t);
         int py = mapY(p, t);
         g.fill(px - 3, py - 3, px + 4, py + 4, i == this.controlIndex ? -11396 : -5069577);
         g.drawString(this.font, i + 1 + "", px + 4, py + 2, -1585262, false);
      }

      if (cs.isEmpty()) {
         g.drawWordWrap(this.font, Component.literal("A/B 为端点；选择起点后，左 Alt＋右键端点添加控制点。"), x, this.top + 157, w, -4467748);
      } else {
         this.label(g, "X", "controlX");
         this.label(g, "Y", "controlY");
         this.label(g, "Z", "controlZ");
      }
   }

   public boolean mouseClicked(double mx, double my, int button) {
      if (this.mapPage && button == 0) {
         int x = this.width >= 512 ? this.left + 294 : this.left + 12;
         double[] t = this.mapTransform(x, this.top + 27, this.width >= 512 ? 194 : 264);
         List<RoadProfile.Control> cs = this.options.routing().controls();

         for (int i = 0; i < cs.size(); i++) {
            if (Math.hypot(mx - (double)mapX(cs.get(i).position(), t), my - (double)mapY(cs.get(i).position(), t)) <= 7.0) {
               if (this.capture()) {
                  this.controlIndex = i;
                  this.rebuildWidgets();
               }

               return true;
            }
         }
      }

      return super.mouseClicked(mx, my, button);
   }

   private void plan(GuiGraphics g, int x, int y, int w, int h) {
      RoadGeometry.Mesh mesh = ClientRoads.preview;
      g.fill(x, y, x + w, y + h, -653189079);
      g.drawString(this.font, "俯视路径", x + 8, y + 8, 16777215, false);
      double dx = Math.max(1.0, mesh.max().x() - mesh.min().x());
      double dz = Math.max(1.0, mesh.max().z() - mesh.min().z());
      double scale = Math.min((double)(w - 24) / dx, (double)(h - 44) / dz);

      for (RoadGeometry.Sample p : mesh.samples()) {
         int px = x + 12 + (int)((p.center().x() - mesh.min().x()) * scale);
         int py = y + 30 + (int)((p.center().z() - mesh.min().z()) * scale);
         g.fill(px, py, px + 2, py + 2, -10492971);
      }

      g.drawString(this.font, "完整路面在世界中同步预览", x + 8, y + h - 12, 12309468, false);
   }

   private static final class FailureDetails extends Screen {
      private final RoadScreen parent;
      private Button world;

      FailureDetails(RoadScreen parent) {
         super(Component.literal("道路保存失败详情"));
         this.parent = parent;
      }

      protected void init() {
         this.addRenderableWidget(
            Button.builder(Component.literal("返回道路设置"), b -> this.onClose()).bounds(this.width / 2 - 134, this.height - 30, 130, 20).build()
         );
         this.world = (Button)this.addRenderableWidget(
            Button.builder(Component.literal("查看标红道路"), b -> this.parent.onClose()).bounds(this.width / 2 + 4, this.height - 30, 130, 20).build()
         );
         this.world.active = !this.parent.failureRoads.isEmpty();
      }

      public void tick() {
         super.tick();
         if (this.world != null) {
            this.world.active = !this.parent.failureRoads.isEmpty();
         }
      }

      public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
      }

      public void render(GuiGraphics g, int mx, int my, float dt) {
         super.renderBackground(g, mx, my, dt);
         g.fill(12, 12, this.width - 12, this.height - 12, -267050450);
         g.drawString(this.font, "道路未保存：完整诊断", 24, 24, 16764812, false);
         String message = RoadConflictIds.display(this.parent.serverFailure);
         if (!this.parent.failureRoads.isEmpty()) {
            message = message + "；失败道路已标红，透过上方道路也可见。按提示坐标查找；它可能是此次编辑联动的邻路。";
         }

         g.drawWordWrap(this.font, Component.literal(message), 24, 44, Math.max(100, this.width - 48), 16777215);
         super.render(g, mx, my, dt);
      }

      public void onClose() {
         this.parent.returningFromFailureDetails = true;
         this.minecraft.setScreen(this.parent);
      }

      public boolean isPauseScreen() {
         return false;
      }
   }

   private static record RoadPreviewInput(RoadGeometry.Settings settings, boolean levelEnds, boolean forceJunction, boolean forceA, boolean forceB) {
   }
}
