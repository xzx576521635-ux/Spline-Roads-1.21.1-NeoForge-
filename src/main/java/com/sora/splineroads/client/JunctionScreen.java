package com.sora.splineroads.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionPreview;
import com.sora.splineroads.core.JunctionSlips;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.OneSidedJunction;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.world.AutoJunctions;
import com.sora.splineroads.world.JunctionCodec;
import com.sora.splineroads.world.RoadRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class JunctionScreen extends Screen {
   private static CompoundTag draft;
   public static RoadGeometry.Mesh previewMesh;
   public static List<RoadSurface.Face> previewFaces = List.of();
   private final CompoundTag payload;
   private CompoundTag spec;
   private int page;
   private int arm;
   private int lane;
   private int turn;
   private int top;
   private boolean pairSide;
   private String status = "";
   private boolean pending;
   private boolean confirmDelete;
   private boolean signalLanes;
   private JunctionPlanner.Plan plan;
   private final CompoundTag sourceSpec;
   private CompoundTag plannedSpec;
   private AutoJunctions.Draft previewNetwork;
   private ResourceLocation mapTexture;
   private int planningPasses;
   private int worldFacePasses;
   private JunctionPreview.Image map;
   private Button build;
   private final List<Runnable> captures = new ArrayList<>();
   private final List<JunctionScreen.Label> labels = new ArrayList<>();

   public JunctionScreen(CompoundTag t) {
      super(Component.literal("Spline Roads · 路口"));
      this.payload = t.copy();
      this.spec = JunctionCodec.write(JunctionCodec.read(this.payload.getCompound("Spec")));
      this.sourceSpec = JunctionCodec.write(JunctionCodec.read(this.payload.getCompound(this.payload.contains("FreshSpec") ? "FreshSpec" : "Spec")));
      if (this.payload.contains("SelectedArm")) {
         this.arm = Math.max(0, Math.min(this.spec.getList("Arms", 10).size() - 1, this.payload.getInt("SelectedArm")));
         this.page = 1;
      }
   }

   public static void clear() {
      draft = null;
      previewMesh = null;
      previewFaces = List.of();
      ClientRoads.preview = null;
      ClientRoads.nodePreviews = List.of();
   }

   public static void open(CompoundTag fresh) {
      if (fresh.getString("Kind").equals("junctionReset")) {
         clear();
      } else {
         CompoundTag t = fresh.copy();
         t.put("FreshSpec", fresh.getCompound("Spec").copy());
         if (draft != null
            && !t.getBoolean("Paired")
            && !t.getBoolean("DeleteOnly")
            && Arrays.equals(t.getLongArray("Points"), draft.getLongArray("Points"))
            && Objects.equals(t.hasUUID("Id") ? t.getUUID("Id") : null, draft.hasUUID("Id") ? draft.getUUID("Id") : null)) {
            CompoundTag edited = draft.getCompound("Spec").copy();
            ListTag incoming = t.getCompound("Spec").getList("Arms", 10);
            ListTag saved = edited.getList("Arms", 10);
            if (incoming.size() == saved.size()) {
               edited.put("Center", t.getCompound("Spec").getCompound("Center").copy());

               for (int i = 0; i < saved.size(); i++) {
                  for (String key : List.of("Node", "DX", "DZ", "External", "Attached")) {
                     saved.getCompound(i).put(key, incoming.getCompound(i).get(key).copy());
                  }
               }

               t.put("Spec", edited);
            }
         }

         Minecraft.getInstance().setScreen(new JunctionScreen(t));
      }
   }

   private boolean oneSide() {
      return this.spec.contains("OneSide");
   }

   private boolean protectedArm() {
      return this.oneSide() && this.arm == JunctionCodec.read(this.spec).oneSide().outgoing();
   }

   private int allowedTurns() {
      return this.oneSide() ? OneSidedJunction.allowed(JunctionCodec.read(this.spec), this.arm) : 15;
   }

   private CompoundTag arm() {
      return this.spec.getList("Arms", 10).getCompound(this.arm);
   }

   private void remember() {
      this.payload.put(this.pairSide ? "PairSpec" : "Spec", this.spec);
      if (!this.payload.getBoolean("DeleteOnly")) {
         draft = this.payload.copy();
      }
   }

   private boolean capture() {
      try {
         this.captures.forEach(Runnable::run);
         this.resizeLanes();
         this.remember();
         return true;
      } catch (IllegalArgumentException var2) {
         this.status = var2.getMessage();
         return false;
      }
   }

   private void resizeLanes() {
      for (Tag entry : this.spec.getList("Arms", 10)) {
         CompoundTag a = (CompoundTag)entry;
         int count = a.getInt("In");
         if (count < 0 || count > 7) {
            throw new IllegalArgumentException("进出车道数范围为 0–7");
         }

         ListTag list = a.getList("Lanes", 10);

         while (list.size() > count) {
            list.remove(list.size() - 1);
         }

         while (list.size() < count) {
            CompoundTag l = new CompoundTag();
            l.putInt("Mask", -1);
            l.putIntArray("Targets", new int[]{-1, -1, -1, -1});
            l.putIntArray("TargetLanes", new int[]{-1, -1, -1, -1});
            list.add(l);
         }

         a.put("Lanes", list);
      }

      if (!this.spec.getList("Arms", 10).isEmpty()) {
         this.lane = Math.max(0, Math.min(this.lane, this.arm().getInt("In") - 1));
      }
   }

   private void edited() {
      ClientRoads.editorChanged(this);
      this.pending = false;
      if (this.build != null) {
         this.build.active = !this.payload.getBoolean("DeleteOnly");
      }
   }

   private void change(Runnable r) {
      if (this.capture()) {
         CompoundTag before = this.spec.copy();
         r.run();
         if (!before.equals(this.spec)) {
            this.edited();
         }

         this.remember();
         this.rebuildWidgets();
      }
   }

   private Button button(String text, int x, int y, int w, Runnable r) {
      return (Button)this.addRenderableWidget(Button.builder(Component.literal(text), b -> r.run()).bounds(x, y, w, 20).build());
   }

   private void field(String label, CompoundTag tag, String key, int col, double row, boolean integer) {
      int x = 16 + col * 146;
      int y = this.top + (int)(row * 28.0);
      this.labels.add(new JunctionScreen.Label(label, x, y));
      EditBox box = new EditBox(this.font, x, y + 10, 136, 17, Component.literal(label));
      box.setMaxLength(32);
      box.setValue(integer ? Integer.toString(tag.getInt(key)) : Double.toString(tag.getDouble(key)));
      box.setResponder(v -> this.edited());
      box.setEditable(!this.oneSide() || !key.equals("Thickness") && (this.page != 3 || this.arm >= 2) && !this.protectedArm());
      this.addRenderableWidget(box);
      this.captures.add(() -> {
         try {
            if (integer) {
               tag.putInt(key, Integer.parseInt(box.getValue()));
            } else {
               double value = Double.parseDouble(box.getValue());
               if (!Double.isFinite(value)) {
                  throw new NumberFormatException();
               }

               tag.putDouble(key, value);
            }
         } catch (NumberFormatException var7x) {
            throw new IllegalArgumentException(label + "：请输入有效数字");
         }
      });
   }

   private void toggle(String title, CompoundTag tag, String key, int x, int y, int w) {
      this.button(title + "：" + (tag.getBoolean(key) ? "开" : "关"), x, y, w, () -> this.change(() -> tag.putBoolean(key, !tag.getBoolean(key)))).active = !this.oneSide()
         || tag == this.spec
         || this.arm >= 2;
   }

   protected void init() {
      this.captures.clear();
      this.labels.clear();
      this.top = 57;
      if (this.payload.getBoolean("Paired")) {
         this.button(this.pairSide ? "编辑 B 端 →" : "编辑 A 端 →", this.width - 116, 5, 108, () -> {
            if (this.capture()) {
               this.pairSide = !this.pairSide;
               this.spec = this.payload.getCompound(this.pairSide ? "PairSpec" : "Spec").copy();
               this.plannedSpec = null;
               this.arm = 2;
               this.lane = 0;
               this.rebuildWidgets();
            }
         });
      }

      String[] tabs = this.spec.getList("Arms", 10).isEmpty() ? new String[]{"环岛"} : new String[]{"路口", "接入口", "车道", "斑马线", "信号", "右转"};

      for (int i = 0; i < tabs.length; i++) {
         final int index = i;
         this.button((this.page == i ? "● " : "") + tabs[i], 12 + i * 49, 29, 47, () -> this.change(() -> this.page = index));
      }

      if (this.page > 0) {
         this.button("接入口 " + (this.arm + 1) + " / " + this.spec.getList("Arms", 10).size() + "  →", 16, this.top, 282, () -> this.change(() -> {
               this.arm = (this.arm + 1) % this.spec.getList("Arms", 10).size();
               this.lane = 0;
            }));
      }

      if (this.page == 0) {
         this.button("类型：" + (this.spec.getString("Kind").equals("ROUNDABOUT") ? "环岛" : "普通路口"), 16, this.top, 136, () -> {
         }).active = false;
         if (this.oneSide()) {
            this.button("右侧通行（继承主路）", 162, this.top, 136, () -> {
            }).active = false;
         } else {
            this.toggle("左侧通行", this.spec, "LeftTraffic", 162, this.top, 136);
         }

         this.field("转角半径 / 格", this.spec, "Corner", 0, 1.0, false);
         this.field("路面厚度 / 格", this.spec, "Thickness", 1, 1.0, false);
         if (!this.payload.getBoolean("Auto") && this.spec.getString("Kind").equals("ROUNDABOUT")) {
            this.field("中央岛半径 / 格", this.spec, "Island", 0, 2.0, false);
            this.field("环道车道数 1–3", this.spec, "RingLanes", 1, 2.0, true);
            this.field("环道车道宽 / 格", this.spec, "RingWidth", 0, 3.0, false);
         } else if (this.payload.getBoolean("Auto")) {
            if (this.payload.getBoolean("GeneratedRing")) {
               this.labels.add(new JunctionScreen.Label("环岛尺寸在生成时设置，八向接点固定", 16, this.top + 72));
            } else {
               this.labels.add(new JunctionScreen.Label("增删相连道路时自动更新路口方向", 16, this.top + 72));
            }
         }

         if (this.spec.getString("Kind").equals("ROUNDABOUT")) {
            this.toggle("中央岛绿化", this.spec, "GreenIsland", 162, this.top + 95, 136);
         }

         this.toggle("路口导向虚线", this.spec, "Guides", 16, this.top + 121, 136);
         if (this.spec.getString("Kind").equals("ROUNDABOUT")) {
            this.toggle("环岛外侧护栏", this.spec, "OuterRail", 162, this.top + 121, 136);
         }
      } else if (this.page == 5) {
         JunctionSpec current = JunctionCodec.read(this.spec);
         String reason = JunctionSlips.unavailable(current, this.arm);
         Button toggle = this.button(
            "独立右转匝道：" + (this.arm().getBoolean("RightSlip") ? "开" : "关"),
            16,
            this.top + 34,
            282,
            () -> this.change(() -> this.arm().putBoolean("RightSlip", !this.arm().getBoolean("RightSlip")))
         );
         toggle.active = this.arm().getBoolean("RightSlip") || reason.isEmpty();
         this.labels.add(new JunctionScreen.Label("三角区：" + JunctionSlips.islandLabel(current.arms().get(this.arm)), 16, this.top + 65));
         this.labels.add(new JunctionScreen.Label(reason.isEmpty() ? "来向额外加一车道，转入道路保留外侧车道接入" : reason, 16, this.top + 88));
         this.labels.add(new JunctionScreen.Label("每个驶入方向独立设置；行人过街优先", 16, this.top + 110));
      } else if (this.page == 1) {
         if (!this.payload.getBoolean("Auto") && !this.oneSide()) {
            this.field("进入路口车道数", this.arm(), "In", 0, 1.0, true);
            this.field("驶出路口车道数", this.arm(), "Out", 1, 1.0, true);
            this.field("接入口总宽 / 格", this.arm(), "Width", 0, 2.0, false);
            this.field("中央分隔带宽 / 格", this.arm(), "Median", 1, 2.0, false);
            this.field("两侧非机动车道宽", this.arm(), "Cycle", 0, 3.0, false);
            this.field("两侧路缘宽", this.arm(), "Curb", 1, 3.0, false);
            this.button(
               "分隔带：" + (this.arm().getString("MedianKind").equals("GREEN") ? "绿化" : "护栏"),
               16,
               this.top + 121,
               136,
               () -> this.change(() -> this.arm().putString("MedianKind", this.arm().getString("MedianKind").equals("GREEN") ? "RAIL" : "GREEN"))
            );
         } else {
            this.labels.add(new JunctionScreen.Label("驶入 " + this.arm().getInt("In") + " 车道 · 驶出 " + this.arm().getInt("Out") + " 车道", 16, this.top + 34));
            this.labels.add(new JunctionScreen.Label("道路总宽：" + String.format(Locale.ROOT, "%.1f", this.arm().getDouble("Width")) + " 格", 16, this.top + 57));
            this.labels.add(new JunctionScreen.Label("继承各方向道路的宽度与分隔带", 16, this.top + 82));
            this.labels.add(new JunctionScreen.Label(this.oneSide() ? "中央隔离带与对向主路保持原状" : "右键路口外侧路面可修改该道路", 16, this.top + 105));
         }

         if (!this.oneSide()) {
            this.toggle("路口预告牌", this.arm(), "AdvanceSign", 16, this.top + 147, 136);
         } else {
            boolean choice = this.arm == JunctionCodec.read(this.spec).oneSide().incoming() && (this.allowedTurns() & 4) != 0;
            this.button(
                  "路口预告牌：" + (choice && this.arm().getBoolean("AdvanceSign") ? "开" : "关"),
                  16,
                  this.top + 147,
                  136,
                  () -> this.change(() -> this.arm().putBoolean("AdvanceSign", !this.arm().getBoolean("AdvanceSign")))
               )
               .active = choice;
         }

         this.toggle("道路名称牌", this.arm(), "NameSign", 162, this.top + 147, 136);
      } else if (this.page == 2) {
         ListTag lanes = this.arm().getList("Lanes", 10);
         if (this.protectedArm()) {
            this.labels.add(new JunctionScreen.Label("对向主路保持原状，无需配置", 16, this.top + 30));
         } else if (lanes.isEmpty()) {
            this.labels.add(new JunctionScreen.Label("此方向没有驶入车道", 16, this.top + 30));
         } else {
            CompoundTag l = lanes.getCompound(this.lane);
            int mask = l.getInt("Mask");
            this.button("驶入第 " + (this.lane + 1) + " 车道（从左到右）→", 16, this.top + 21, 282, () -> this.change(() -> this.lane = (this.lane + 1) % lanes.size()));
            String[] names = new String[]{"直行", "左转", "右转", "掉头"};

            for (int i = 0; i < 4; i++) {
               int bit = JunctionSpec.TURNS[i];
               this.button(
                     ((mask & bit) != 0 && mask >= 0 ? "☑ " : "□ ") + names[i],
                     16 + i * 72,
                     this.top + 42,
                     66,
                     () -> this.change(() -> l.putInt("Mask", (mask < 0 ? 0 : mask) ^ bit))
                  )
                  .active = (this.allowedTurns() & bit) != 0;
            }

            this.button("自动分配", 16, this.top + 62, 136, () -> this.change(() -> l.putInt("Mask", -1)));
            this.button("禁行", 162, this.top + 62, 136, () -> this.change(() -> l.putInt("Mask", 0)));
            this.button("出口映射：" + names[this.turn] + " →", 16, this.top + 83, 282, () -> this.change(() -> {
                  do {
                     this.turn = (this.turn + 1) % 4;
                  } while (this.oneSide() && (this.allowedTurns() & JunctionSpec.TURNS[this.turn]) == 0);
               }));
            int[] targets = l.getIntArray("Targets");
            int[] targetLanes = l.getIntArray("TargetLanes");
            if (!this.oneSide()) {
               this.button("出口：" + (targets[this.turn] < 0 ? "自动" : targets[this.turn] + 1), 16, this.top + 104, 136, () -> this.change(() -> {
                     targets[this.turn]++;
                     if (targets[this.turn] >= this.spec.getList("Arms", 10).size()) {
                        targets[this.turn] = -1;
                     }

                     l.putIntArray("Targets", targets);
                  }));
            } else {
               JunctionSpec current = JunctionCodec.read(this.spec);
               int fixed = JunctionSpec.TURNS[this.turn] != 1 && this.arm != 2 ? 2 : current.oneSide().outgoing();
               this.button(
                     (this.allowedTurns() & JunctionSpec.TURNS[this.turn]) != 0 ? "出口：" + (fixed + 1) + "（固定）" : "此转向不可用", 16, this.top + 104, 136, () -> {
                     }
                  )
                  .active = false;
            }

            this.button("出口车道：" + (targetLanes[this.turn] < 0 ? "自动" : targetLanes[this.turn] + 1), 162, this.top + 104, 136, () -> this.change(() -> {
                  targetLanes[this.turn]++;
                  if (targetLanes[this.turn] >= 7) {
                     targetLanes[this.turn] = -1;
                  }

                  l.putIntArray("TargetLanes", targetLanes);
               })).active = (this.allowedTurns() & JunctionSpec.TURNS[this.turn]) != 0;
            this.labels.add(new JunctionScreen.Label("当前：" + JunctionSpec.maskName(mask), 16, this.top + 127));
         }
      } else if (this.page == 3) {
         if (this.oneSide() && this.arm < 2) {
            this.button("主路保持连续，不生成横跨斑马线", 16, this.top + 25, 282, () -> {
            }).active = false;
         } else if (JunctionCodec.read(this.spec).arms().get(this.arm).hasSlowTraffic()) {
            this.toggle("斑马线", this.arm(), "Crosswalk", 16, this.top + 25, 282);
         } else {
            this.button("无慢行设施，不生成斑马线", 16, this.top + 25, 282, () -> {
            }).active = false;
         }

         this.field("斑马线纵向宽度", this.arm(), "CrossWidth", 0, 2.0, false);
         this.field("距路口边缘 / 格", this.arm(), "Setback", 1, 2.0, false);
         this.field("停止线退后 / 格", this.arm(), "StopGap", 0, 3.0, false);
         this.labels.add(new JunctionScreen.Label(this.oneSide() ? "仅支路可设置过街，主路中央分隔带连续" : "斑马线前自动收束中央分隔带", 16, this.top + 121));
      } else {
         this.button(this.signalLanes ? "◂ 信号周期（1 / 2）" : "车道信号（2 / 2）▸", 16, this.top + 22, 282, () -> this.change(() -> this.signalLanes = !this.signalLanes));
         if (!this.signalLanes) {
            String c = this.spec.getString("Control");
            this.button(
               "控制：" + (c.equals("SIGNALS") ? "红绿灯" : (c.equals("YIELD") ? "让行" : "无信号灯")),
               16,
               this.top + 43,
               282,
               () -> this.change(() -> this.spec.putString("Control", c.equals("NONE") ? "YIELD" : (c.equals("YIELD") ? "SIGNALS" : "NONE")))
            );
            this.field("绿灯 / 秒", this.spec, "Green", 0, 2.5, true);
            this.field("黄灯 / 秒", this.spec, "Yellow", 1, 2.5, true);
            this.field("全红间隔 / 秒", this.spec, "AllRed", 0, 3.5, true);
            this.field("入口相位组 0–7", this.arm(), "Phase", 1, 3.5, true);
         } else {
            ListTag lanes = this.arm().getList("Lanes", 10);
            if (this.protectedArm()) {
               this.labels.add(new JunctionScreen.Label("对向主路保持原状，无需配置", 16, this.top + 55));
            } else if (lanes.isEmpty()) {
               this.labels.add(new JunctionScreen.Label("此方向没有驶入车道", 16, this.top + 55));
            } else {
               CompoundTag l = lanes.getCompound(this.lane);
               if (!l.contains("LeftPhase")) {
                  l.putInt("LeftPhase", -1);
               }

               this.button("驶入第 " + (this.lane + 1) + " 车道（从左到右）→", 16, this.top + 44, 282, () -> this.change(() -> this.lane = (this.lane + 1) % lanes.size()));
               this.button(
                     l.getBoolean("SplitLeft") ? "左转 / 掉头：独立箭头灯" : "左转 / 直行：普通圆灯",
                     16,
                     this.top + 65,
                     282,
                     () -> this.change(() -> l.putBoolean("SplitLeft", !l.getBoolean("SplitLeft")))
                  )
                  .active = !this.oneSide();
               boolean right = lanes.stream().anyMatch(v -> ((CompoundTag)v).getBoolean("ControlRight"));
               this.button(right ? "入口右转控制灯：开（单独箭头灯）" : "入口右转控制灯：关（默认不受控）", 16, this.top + 86, 282, () -> this.change(() -> {
                     for (Tag value : lanes) {
                        ((CompoundTag)value).putBoolean("ControlRight", !right);
                     }
                  }));
               this.labels.add(new JunctionScreen.Label("只有左转且已设专用灯：不生成普通灯", 16, this.top + 121));
            }
         }
      }

      int bottom = Math.max(this.top + 141, this.height - 49);
      this.build = this.button(this.payload.hasUUID("Id") ? "保存路口" : "建造路口", 16, bottom, 91, () -> this.submit(false));
      this.button("预览 / 返回世界", 111, bottom, 112, () -> {
         if (this.refresh()) {
            this.publishWorldPreview();
            this.remember();
            ManualPlannerClient.worldPreview();
            this.minecraft.setScreen(null);
         }
      });
      if (this.payload.hasUUID("Id")) {
         this.button(this.confirmDelete ? "确认删除" : "删除整体", 227, bottom, 71, () -> {
            if (this.confirmDelete) {
               this.submit(true);
            } else {
               this.confirmDelete = true;
               this.rebuildWidgets();
            }
         });
      } else {
         this.button("关闭", 227, bottom, 71, this::onClose);
      }

      this.build.active = !this.pending && !this.payload.getBoolean("DeleteOnly");
      this.refresh();
   }

   private boolean refresh() {
      if (!this.capture()) {
         return false;
      } else {
         this.map = null;
         if (this.plan != null && this.plannedSpec != null && this.plannedSpec.equals(this.spec)) {
            return true;
         } else {
            long began = System.nanoTime();

            boolean var4;
            try {
               JunctionSpec parsed = JunctionCodec.read(this.spec).supportedCrossings();
               this.previewNetwork = null;
               if (this.payload.getBoolean("Auto") && !this.sourceSpec.equals(this.spec)) {
                  this.previewNetwork = AutoJunctions.previewEdit(this.payload);

                  for (CompoundTag center : this.previewNetwork.centers()) {
                     if (center.getUUID("Id").equals(this.payload.getUUID("Id"))) {
                        parsed = JunctionCodec.read(center.getCompound("Spec")).supportedCrossings();
                     }
                  }
               }

               this.plan = JunctionPlanner.plan(parsed);
               this.plannedSpec = this.spec.copy();
               this.planningPasses++;
               this.status = JunctionPlanner.separatedEntrances(parsed) ? "分隔式路口预览有效：同侧 B/C 保持独立，A 双向接入" : "几何预览有效；建造时校验地形、权限和已有道路";
               return true;
            } catch (IllegalArgumentException var9) {
               this.plan = null;
               this.plannedSpec = null;
               this.status = var9.getMessage();
               var4 = false;
            } finally {
               if (Boolean.getBoolean("road.junction.timing")) {
                  System.out.println("SR JUNCTION UI plan=" + this.planningPasses + " ms=" + (double)(System.nanoTime() - began) / 1000000.0);
               }
            }

            return var4;
         }
      }
   }

   private void publishWorldPreview() {
      long began = System.nanoTime();
      ArrayList<RoadSurface.Face> faces = new ArrayList<>();
      faces.addAll(RoadSidewalks.preview(this.plan.sidewalks().keySet()));

      for (JunctionPlanner.Piece piece : this.plan.pieces()) {
         faces.addAll(RoadSurface.custom(new RoadSurface.Geometry(List.of(), List.of()), piece.mesh(), piece.paint()).markings());
         piece.structures().forEach(part -> faces.addAll(part.faces()));
      }

      previewMesh = this.plan.pieces().get(0).mesh();
      previewFaces = List.copyOf(faces);
      ClientRoads.preview = previewMesh;
      ClientRoads.nodePreviews = this.previewNetwork == null
         ? this.plan.pieces().stream().map(JunctionPlanner.Piece::mesh).toList()
         : this.previewNetwork.roads().stream().map(RoadRecord::mesh).toList();
      this.worldFacePasses++;
      if (Boolean.getBoolean("road.junction.timing")) {
         System.out.println("SR JUNCTION UI world=" + this.worldFacePasses + " ms=" + (double)(System.nanoTime() - began) / 1000000.0);
      }
   }

   private void submit(boolean delete) {
      if (!this.pending) {
         if (delete && !this.payload.getList("Dependencies", 8).isEmpty() && !this.payload.contains("ConfirmDependencies")) {
            this.minecraft.setScreen(new LaneDeleteConfirmScreen(this, this.payload.getList("Dependencies", 8), () -> {
               this.payload.put("ConfirmDependencies", this.payload.getList("Dependencies", 8).copy());
               this.submit(true);
            }));
         } else if (delete || this.refresh()) {
            CompoundTag command = this.payload.copy();
            command.putString("Action", delete ? "junctionDelete" : "junction");
            command.put(this.pairSide ? "PairSpec" : "Spec", this.spec.copy());
            this.pending = true;
            this.build.active = false;
            this.status = "正在校验并建造…";
            ClientRoads.sendAction(JunctionCodec.editCommand(command));
         }
      }
   }

   public void failed(String message) {
      this.pending = false;
      this.status = message;
      if (this.build != null) {
         this.build.active = !this.payload.getBoolean("DeleteOnly");
      }
   }

   public void onClose() {
      this.capture();
      previewMesh = null;
      previewFaces = List.of();
      ClientRoads.preview = null;
      ClientRoads.nodePreviews = List.of();
      this.minecraft.setScreen(null);
   }

   public void removed() {
      this.releaseMap();
      super.removed();
   }

   private void releaseMap() {
      if (this.mapTexture != null) {
         Minecraft.getInstance().getTextureManager().release(this.mapTexture);
         this.mapTexture = null;
      }
   }

   private void uploadMap() {
      this.releaseMap();
      NativeImage pixels = new NativeImage(this.map.width(), this.map.height(), false);

      for (int y = 0; y < this.map.height(); y++) {
         for (int x = 0; x < this.map.width(); x++) {
            int c = this.map.pixels()[y * this.map.width() + x];
            pixels.setPixelRGBA(x, y, c & -16711936 | c >>> 16 & 0xFF | (c & 0xFF) << 16);
         }
      }

      this.mapTexture = this.minecraft.getTextureManager().register("sr_junction_map", new DynamicTexture(pixels));
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(6, 5, Math.min(this.width - 6, 306), this.height - 5, -451205840);
      g.drawString(this.font, this.title, 16, 13, 16777215);
      super.render(g, mx, my, dt);

      for (JunctionScreen.Label l : this.labels) {
         g.drawString(this.font, l.text(), l.x(), l.y(), 14082797, false);
      }

      g.drawString(
         this.font,
         this.font.plainSubstrByWidth(this.status, Math.max(280, this.width - 30)),
         16,
         this.height - 19,
         this.plan == null ? 16754585 : 12113611,
         false
      );
      if (this.plan != null && this.width > 510) {
         this.drawPlan(g, 318, 38, this.width - 330, this.height - 76);
      }
   }

   private void drawPlan(GuiGraphics g, int x, int y, int w, int h) {
      int imageHeight = Math.max(40, h - 28);
      if (this.map == null || this.map.width() != w || this.map.height() != imageHeight) {
         this.map = JunctionPreview.render(this.plan, w, imageHeight, this.arm, this.lane, this.page == 2);
         this.uploadMap();
      } else if (this.mapTexture == null) {
         this.uploadMap();
      }

      g.fill(x, y, x + w, y + h, -14668752);
      g.blit(this.mapTexture, x, y, 0.0F, 0.0F, this.map.width(), this.map.height(), this.map.width(), this.map.height());

      for (JunctionPreview.Label label : this.map.labels()) {
         int xx = x + label.x();
         int yy = y + label.y();
         g.fill(xx - 6, yy - 5, xx + 7, yy + 6, label.arm() == this.arm ? -14447208 : -14272962);
         g.drawCenteredString(this.font, Integer.toString(label.arm() + 1), xx, yy - 4, 16777215);
      }

      g.drawString(this.font, "北 ↑", x + 5, y + 4, 12243163, false);
      g.drawString(this.font, "蓝色：当前接入口", x + 5, y + imageHeight + 3, 9361116, false);
      if (this.page == 2) {
         g.drawString(this.font, "黄色：选中车道的通行方向", x + 5, y + imageHeight + 15, 16042871, false);
      }
   }

   private static record Label(String text, int x, int y) {
   }
}
