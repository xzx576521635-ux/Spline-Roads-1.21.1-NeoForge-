package com.sora.splineroads.client;

import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.world.SmartSignCodec;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class GantryScreen extends Screen {
   private final CompoundTag payload;
   private RoadGantry.Edit edit;
   private EditBox offset;
   private EditBox clearance;
   private int flow = 1;
   private final Map<Integer, SmartSigns.Mode> styles = new HashMap<>();
   private Button apply;
   private Button reset;
   private Button remove;
   private String status = "";
   private boolean pending;
   private boolean confirmRemove;
   private int x;
   private int y;

   private void edited() {
      ClientRoads.editorChanged(this);
      this.pending = false;
      if (this.apply != null && this.reset != null) {
         this.active();
      }
   }

   public GantryScreen(CompoundTag tag) {
      super(Component.literal("Spline Roads · 单架龙门架编辑"));
      this.payload = tag.copy();
      this.edit = RoadGantry.edit(RoadRecord.readSettings(tag.getCompound("Settings")).options().infrastructure(), tag.getInt("Slot"));
   }

   private int effective() {
      return this.flow == 2 ? (this.payload.getInt("PlayerFlow") < 0 ? -1 : 1) : (this.flow == 0 ? 1 : this.flow);
   }

   private SmartSigns.State sign(int direction) {
      return SmartSignCodec.read(this.payload.getCompound(direction > 0 ? "ForwardSign" : "ReverseSign"));
   }

   private SmartSigns.Mode mode() {
      return this.styles.computeIfAbsent(this.effective(), f -> {
         SmartSigns.Mode m = this.sign(f).mode();
         return m == SmartSigns.Mode.NORMAL ? SmartSigns.Mode.EMPTY : (m == SmartSigns.Mode.EXIT_AUTO ? SmartSigns.Mode.EXIT_SIGN : m);
      });
   }

   public static List<Integer> directions(SmartSigns.Mode mode, boolean twoWay) {
      return (mode == SmartSigns.Mode.SPEED_LIMIT || mode == SmartSigns.Mode.LANE_INDICATOR) && twoWay ? List.of(1, -1, 2, 0) : List.of(1, -1, 2);
   }

   private String directionLabel() {
      return this.flow == 0 ? "双向" : (this.flow == 2 ? "当前方向（玩家朝向）" : (this.flow > 0 ? "A → B" : "B → A"));
   }

   protected void init() {
      this.x = (this.width - 284) / 2;
      this.y = Math.max(2, (this.height - 282) / 2);
      this.offset = this.field("沿路位移", Double.toString(this.edit.offset()), this.x + 10, this.y + 45);
      this.clearance = this.field("横梁基准净高", Double.toString(this.edit.clearance()), this.x + 148, this.y + 45);
      SmartSigns.Mode mode = this.mode();
      this.addRenderableWidget(Button.builder(Component.literal("样式：" + SmartSigns.styleLabel(mode)), b -> {
         if (this.capture()) {
            this.edited();
            SmartSigns.Mode next = SmartSigns.STYLES.get((SmartSigns.STYLES.indexOf(mode) + 1) % SmartSigns.STYLES.size());
            this.styles.put(this.effective(), next);
            if (this.flow == 0 && next != SmartSigns.Mode.SPEED_LIMIT && next != SmartSigns.Mode.LANE_INDICATOR) {
               this.flow = 1;
            }

            this.confirmRemove = false;
            this.rebuildWidgets();
         }
      }).bounds(this.x + 10, this.y + 73, 264, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("行驶方向：" + this.directionLabel()), b -> {
         if (this.capture()) {
            this.edited();
            List<Integer> choices = directions(mode, this.payload.getBoolean("TwoWay"));
            this.flow = choices.get((choices.indexOf(this.flow) + 1) % choices.size());
            this.styles.put(this.effective(), mode);
            this.confirmRemove = false;
            this.rebuildWidgets();
         }
      }).bounds(this.x + 10, this.y + 97, 264, 20).build());
      SmartSigns.State sign = this.sign(this.effective());
      this.status = this.payload.getBoolean("SignUpdating") ? "标牌索引正在更新，稍后重新打开查看结果" : (sign.diagnostic().isEmpty() ? "图形自动识别，文字使用文字编辑器填写" : sign.diagnostic());
      this.apply = (Button)this.addRenderableWidget(
         Button.builder(Component.literal("应用到这一架"), b -> this.send(false, false)).bounds(this.x + 10, this.y + 153, 128, 20).build()
      );
      this.reset = (Button)this.addRenderableWidget(
         Button.builder(Component.literal("恢复这一架默认"), b -> this.send(true, false)).bounds(this.x + 146, this.y + 153, 128, 20).build()
      );
      this.remove = (Button)this.addRenderableWidget(Button.builder(Component.literal(this.confirmRemove ? "确认删除这一架" : "删除这一架"), b -> {
         if (this.confirmRemove) {
            this.send(false, true);
         } else {
            this.confirmRemove = true;
            this.rebuildWidgets();
         }
      }).bounds(this.x + 10, this.y + 177, 264, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> this.onClose()).bounds(this.x + 10, this.y + 201, 264, 20).build());
      this.active();
   }

   private void active() {
      this.apply.active = this.reset.active = !this.pending;
      if (this.remove != null) {
         this.remove.active = !this.pending;
      }
   }

   private EditBox field(String name, String value, int px, int py) {
      EditBox box = new EditBox(this.font, px, py, 126, 20, Component.literal(name));
      box.setMaxLength(16);
      box.setValue(value);
      box.setResponder(v -> this.edited());
      this.addRenderableWidget(box);
      return box;
   }

   private boolean capture() {
      try {
         RoadGantry.Edit next = this.edit
            .parameters(Double.parseDouble(this.offset.getValue()), Double.parseDouble(this.clearance.getValue()), this.edit.kind(), this.edit.reverse());
         if (Math.abs(next.offset()) > this.payload.getDouble("MaxOffset")) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "这架最多前后移动 %.1f 格", this.payload.getDouble("MaxOffset")));
         } else {
            this.edit = next;
            this.status = "";
            return true;
         }
      } catch (IllegalArgumentException var2) {
         this.status = var2 instanceof NumberFormatException ? "请输入完整数值" : var2.getMessage();
         return false;
      }
   }

   private void send(boolean restore, boolean deleting) {
      if (!this.pending && (restore || deleting || this.capture())) {
         CompoundTag t = new CompoundTag();
         t.putString("Action", "gantry");
         t.putUUID("Id", this.payload.getUUID("Id"));
         t.putInt("Slot", this.edit.slot());
         t.putInt("Signature", this.payload.getInt("Signature"));
         t.putBoolean("Reset", restore);
         t.putBoolean("Remove", deleting);
         t.putInt("Flow", this.flow);
         t.putString("SignMode", restore ? "NORMAL" : this.mode().name());
         t.putLong("SignRevision", this.sign(this.effective()).revision());
         t.putLong("ReverseSignRevision", this.sign(-1).revision());
         t.putDouble("Offset", this.edit.offset());
         t.putDouble("Clearance", this.edit.clearance());
         t.putString(
            "Style",
            this.edit.kind() != RoadInfrastructure.Gantry.OFF && this.edit.kind() != RoadInfrastructure.Gantry.AUTO
               ? this.edit.kind().name()
               : RoadInfrastructure.Gantry.SIGNS.name()
         );
         t.putBoolean("Reverse", this.edit.reverse());
         this.pending = true;
         this.active();
         this.status = deleting ? "正在删除这一架…" : "正在更新这座龙门架…";
         ClientRoads.sendAction(t);
      }
   }

   public void failed(String message) {
      this.pending = false;
      this.status = message;
      this.active();
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.x, this.y, this.x + 284, this.y + 282, -267247316);
      g.drawString(
         this.font,
         this.edit.independent() ? "独立龙门架 · 不受道路龙门架开关影响" : "龙门架 " + (this.edit.slot() + 1) + " / " + this.payload.getInt("Count") + " · 单独编辑",
         this.x + 10,
         this.y + 12,
         16777215,
         false
      );
      g.drawString(
         this.font,
         this.mode() == SmartSigns.Mode.LANE_INDICATOR ? "用文字编辑器右键指示牌设置通行／禁行" : "文字用文字编辑器填写；限速和车道指示样式支持双向",
         this.x + 10,
         this.y + 128,
         12309468,
         false
      );
      g.drawString(this.font, "沿路位移 / 格", this.x + 10, this.y + 29, 12309468, false);
      g.drawString(this.font, "横梁净高 / 格", this.x + 148, this.y + 29, 12309468, false);
      super.render(g, mx, my, dt);
      g.drawWordWrap(this.font, Component.literal(this.status), this.x + 10, this.y + 231, 264, 16763554);
   }
}
