package com.sora.splineroads.client;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.world.JunctionCodec;
import com.sora.splineroads.world.JunctionRoads;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class JunctionRoadScreen extends Screen {
   private final CompoundTag payload;
   private final CompoundTag values;
   private final JunctionSpec spec;
   private final int arm;
   private final Map<String, EditBox> fields = new LinkedHashMap<>();
   private int x;
   private int y;
   private int page;
   private int debounce;
   private String status = "";
   private boolean pending;
   private Button apply;

   public JunctionRoadScreen(CompoundTag t) {
      super(Component.literal("道路设置 · 路口接入路段"));
      this.payload = t.copy();
      this.spec = JunctionCodec.read(t.getCompound("Spec"));
      this.arm = t.getInt("Arm");
      this.values = JunctionRoads.fields(this.spec.arms().get(this.arm));
   }

   protected void init() {
      this.x = 12;
      this.y = Math.max(0, (this.height - 238) / 2);
      this.fields.clear();
      this.button(this.page == 0 ? "道路断面 → 人行道设置" : "人行道设置 → 道路断面", this.x + 12, this.y + 29, 264, () -> {
         if (this.capture()) {
            this.page = 1 - this.page;
            this.rebuildWidgets();
         }
      });
      if (this.page == 0) {
         this.field("入向车道数", "Incoming", this.x + 12, this.y + 65);
         this.field("出向车道数", "Outgoing", this.x + 146, this.y + 65);
         this.field("道路总宽 / 格", "Width", this.x + 12, this.y + 106);
         this.field("中央分隔宽 / 格", "Median", this.x + 146, this.y + 106);
         this.field("非机动车道宽 / 单侧", "CycleWidth", this.x + 12, this.y + 147);
         this.field("路缘宽 / 单侧", "CurbWidth", this.x + 146, this.y + 147);
         this.button("中央：" + this.medianLabel(), this.x + 12, this.y + 174, 128, () -> {
            if (this.capture()) {
               RoadProfile.Median[] a = new RoadProfile.Median[]{RoadProfile.Median.DOUBLE_YELLOW, RoadProfile.Median.RAIL, RoadProfile.Median.GREEN};
               int i = Arrays.asList(a).indexOf(RoadProfile.Median.valueOf(this.values.getString("MedianKind")));
               this.values.putString("MedianKind", a[(i + 1) % a.length].name());
               this.edited();
               this.rebuildWidgets();
            }
         });
         this.button(this.values.getBoolean("CycleAsphalt") ? "非机动车：沥青" : "非机动车：绿色", this.x + 146, this.y + 174, 130, () -> {
            if (this.capture()) {
               this.values.putBoolean("CycleAsphalt", !this.values.getBoolean("CycleAsphalt"));
               this.edited();
               this.rebuildWidgets();
            }
         });
      } else {
         this.button(this.values.getBoolean("Walk") ? "人行道：显示" : "人行道：隐藏", this.x + 12, this.y + 60, 128, () -> this.toggle("Walk"));
         this.button("侧别：" + RoadSidewalks.Side.valueOf(this.values.getString("WalkSide")).label, this.x + 146, this.y + 60, 130, () -> {
            if (this.capture()) {
               RoadSidewalks.Side v = RoadSidewalks.Side.valueOf(this.values.getString("WalkSide"));
               this.values.putString("WalkSide", RoadSidewalks.Side.values()[(v.ordinal() + 1) % 3].name());
               this.edited();
               this.rebuildWidgets();
            }
         });
         this.field("人行道宽 / 格", "WalkWidth", this.x + 12, this.y + 107);
         this.button("材质：" + RoadSidewalks.Finish.of(this.values.getString("WalkMaterial")).label, this.x + 146, this.y + 107, 130, () -> {
            if (this.capture()) {
               RoadSidewalks.Finish v = RoadSidewalks.Finish.of(this.values.getString("WalkMaterial"));
               this.values.putString("WalkMaterial", RoadSidewalks.Finish.values()[(v.ordinal() + 1) % RoadSidewalks.Finish.values().length].id);
               this.edited();
               this.rebuildWidgets();
            }
         });
         this.button(this.values.getBoolean("Tactile") ? "盲道：显示" : "盲道：隐藏", this.x + 12, this.y + 144, 264, () -> this.toggle("Tactile"));
      }

      this.apply = this.button("更新此路段", this.x + 12, this.y + 207, 128, this::send);
      this.apply.active = !this.pending;
      this.button("取消", this.x + 146, this.y + 207, 130, this::onClose);
      this.preview();
   }

   private void edited() {
      ClientRoads.editorChanged(this);
      this.pending = false;
      if (this.apply != null) {
         this.apply.active = true;
      }
   }

   private String medianLabel() {
      return switch (RoadProfile.Median.valueOf(this.values.getString("MedianKind"))) {
         case RAIL -> "护栏";
         case GREEN -> "绿化带";
         case DOUBLE_YELLOW -> "双黄线";
         case DASHED_YELLOW -> "黄虚线";
         case NONE -> "无";
      };
   }

   private void toggle(String k) {
      if (this.capture()) {
         this.values.putBoolean(k, !this.values.getBoolean(k));
         this.edited();
         this.rebuildWidgets();
      }
   }

   private Button button(String title, int a, int b, int w, Runnable action) {
      return (Button)this.addRenderableWidget(Button.builder(Component.literal(title), v -> action.run()).bounds(a, b, w, 20).build());
   }

   private void field(String label, String key, int a, int b) {
      EditBox f = new EditBox(this.font, a, b, 128, 18, Component.literal(label));
      f.setMaxLength(8);
      f.setValue(Double.toString(this.values.getDouble(key)));
      f.setResponder(v -> {
         this.edited();
         this.debounce = 8;
         if (this.apply != null) {
            this.apply.active = !this.pending;
         }
      });
      this.fields.put(key, f);
      this.addRenderableWidget(f);
   }

   private boolean capture() {
      try {
         for (Entry<String, EditBox> entry : this.fields.entrySet()) {
            double n = Double.parseDouble(entry.getValue().getValue());
            if (!Double.isFinite(n)) {
               throw new NumberFormatException();
            }

            if (Set.of("Incoming", "Outgoing", "WalkWidth").contains(entry.getKey())) {
               if (n != Math.rint(n)) {
                  throw new NumberFormatException();
               }

               this.values.putInt(entry.getKey(), (int)n);
            } else {
               this.values.putDouble(entry.getKey(), n);
            }
         }

         return true;
      } catch (IllegalArgumentException var5) {
         this.status = "请输入有效数值；车道数和人行道宽须为整数";
         return false;
      }
   }

   private boolean preview() {
      if (!this.capture()) {
         return false;
      } else {
         try {
            ArrayList<JunctionSpec.Arm> arms = new ArrayList<>(this.spec.arms());
            arms.set(this.arm, JunctionRoads.change(arms.get(this.arm), this.values));
            JunctionPlanner.Plan plan = JunctionPlanner.plan(this.spec.arms(arms));
            ClientRoads.nodePreviews = plan.pieces().stream().map(JunctionPlanner.Piece::mesh).toList();
            ClientRoads.preview = plan.pieces().get(this.arm).mesh();
            JunctionScreen.previewMesh = ClientRoads.preview;
            ArrayList<RoadSurface.Face> faces = new ArrayList<>();

            for (JunctionPlanner.Piece p : plan.pieces()) {
               faces.addAll(p.paint());

               for (RoadStructures.Part part : p.structures()) {
                  faces.addAll(part.faces());
               }
            }

            JunctionScreen.previewFaces = List.copyOf(faces);
            this.status = "仅编辑接入路段 " + (this.arm + 1) + "；路口配时、转向请用普通路口编辑器。";
            return true;
         } catch (IllegalArgumentException var8) {
            this.status = var8.getMessage();
            return false;
         }
      }
   }

   public void tick() {
      super.tick();
      if (this.debounce > 0 && --this.debounce == 0 && !this.pending) {
         this.preview();
      }
   }

   private void send() {
      if (!this.pending && this.preview()) {
         CompoundTag t = new CompoundTag();
         t.putString("Action", "junctionRoad");
         t.putUUID("Id", this.payload.getUUID("Id"));
         t.putInt("Arm", this.arm);
         t.putInt("Signature", this.payload.getInt("Signature"));
         t.put("Fields", this.values.copy());
         this.pending = true;
         this.apply.active = false;
         ClientRoads.sendAction(t);
      }
   }

   public void failed(String text) {
      this.pending = false;
      this.status = text;
      this.apply.active = true;
   }

   public void onClose() {
      JunctionScreen.clear();
      ClientRoads.preview = null;
      ClientRoads.nodePreviews = List.of();
      super.onClose();
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.x, this.y, this.x + 288, this.y + 238, -317382356);
      g.drawString(this.font, this.title, this.x + 12, this.y + 12, 16777215, false);

      for (EditBox f : this.fields.values()) {
         g.drawString(this.font, f.getMessage(), f.getX(), f.getY() - 11, 12309468, false);
      }

      super.render(g, mx, my, dt);
      if (this.width >= 480) {
         g.drawWordWrap(this.font, Component.literal(this.status), this.x + 302, this.y + 20, Math.max(130, this.width - this.x - 312), 16777215);
      } else if (my >= this.y + 195 && my < this.y + 207) {
         g.renderTooltip(this.font, Component.literal(this.status), mx, my);
      } else {
         g.drawString(this.font, this.font.plainSubstrByWidth(this.status, 264), this.x + 12, this.y + 195, 12309468, false);
      }
   }
}
