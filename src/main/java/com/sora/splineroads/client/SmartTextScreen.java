package com.sora.splineroads.client;

import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.world.SmartSignCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class SmartTextScreen extends Screen {
   private static SmartTextScreen draft;
   private final SmartSigns.State original;
   private SmartSigns.State editing;
   private final RoadRecord road;
   private final List<String> fields;
   private int selected;
   private int left;
   private int top;
   private EditBox value;
   private boolean initializing;
   private boolean pending;
   private boolean invalid;
   private String rawValue;
   private String status = "所有文字均手填；输入 \\n 指定换行，空白区域不显示文字";

   public SmartTextScreen(CompoundTag t) {
      super(Component.literal("Spline Roads · 文字编辑器"));
      this.original = SmartSignCodec.read(t.getCompound("Sign"));
      this.editing = this.original;
      this.road = RoadRecord.load(t.getCompound("Road"));
      this.fields = this.indicator()
         ? new ArrayList<>(
            SmartSigns.layout(this.road.mesh(), this.original)
               .boards()
               .stream()
               .filter(b -> b.branch().startsWith("lane:"))
               .sorted(Comparator.comparingDouble(SmartSigns.Board::x))
               .map(b -> b.branch() + "/status")
               .toList()
         )
         : new ArrayList<>(SmartSigns.fieldIds(this.original.mode(), this.original.branches()));
      if (this.fields.isEmpty()) {
         throw new IllegalArgumentException("当前标牌没有可编辑区域");
      } else {
         this.selected = Math.max(0, this.fields.indexOf(t.getString("Field")));
         if (this.indicator()) {
            this.status = "设置当前牌对应车道；确认前不会保存";
         }
      }
   }

   public static void open(CompoundTag t) {
      String kind = t.getString("Kind");
      if (kind.equals("smartTextReset")) {
         clear();
      } else if (kind.equals("smartTextResume")) {
         if (draft != null) {
            Minecraft.getInstance().setScreen(draft);
         }
      } else {
         SmartSigns.State incoming = SmartSignCodec.read(t.getCompound("Sign"));
         if (draft == null
            || !draft.original.key().equals(incoming.key())
            || draft.original.revision() != incoming.revision()
            || draft.indicator() && !draft.field().equals(t.getString("Field")) && !draft.dirty()) {
            draft = new SmartTextScreen(t);
            Minecraft.getInstance().setScreen(draft);
         } else {
            Minecraft.getInstance().setScreen(draft);
         }
      }
   }

   public static void clear() {
      draft = null;
      SmartSignClient.preview(null, null);
   }

   private void edited() {
      ClientRoads.editorChanged(this);
      this.pending = false;
   }

   private boolean indicator() {
      return this.original.mode() == SmartSigns.Mode.LANE_INDICATOR;
   }

   private boolean dirty() {
      return this.invalid
         || !this.editing.text().equals(this.original.text())
         || !this.editing.hiddenHeaders().equals(this.original.hiddenHeaders())
         || !this.editing.hiddenAccessories().equals(this.original.hiddenAccessories())
         || !this.editing.closedLanes().equals(this.original.closedLanes());
   }

   private void initIndicator() {
      this.addRenderableWidget(Button.builder(Component.literal("上一车道指示牌"), b -> this.select(-1)).bounds(this.left + 10, this.top + 30, 165, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("下一车道指示牌"), b -> this.select(1)).bounds(this.left + 185, this.top + 30, 165, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal(this.editing.closedLanes().contains(this.branch()) ? "状态：禁行（红叉）" : "状态：通行（绿箭头）"), b -> {
         this.edited();
         this.editing = this.editing.indicator(this.field(), !this.editing.closedLanes().contains(this.branch()));
         this.status = "预览中；确认只保存当前牌对应车道";
         this.rebuildWidgets();
      }).bounds(this.left + 10, this.top + 83, 340, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("进入场景预览"), b -> {
         draft = this;
         Minecraft.getInstance().setScreen(null);
      }).bounds(this.left + 10, this.top + 139, 340, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("确认当前指示牌"), b -> this.submit(false)).bounds(this.left + 10, this.top + 261, 165, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> this.onClose()).bounds(this.left + 185, this.top + 261, 165, 20).build());
      this.initializing = false;
      SmartSignClient.preview(this.editing, this.road);
   }

   private String field() {
      return this.fields.get(this.selected);
   }

   private String branch() {
      return this.field().substring(0, this.field().lastIndexOf(47));
   }

   private SmartSigns.Text current() {
      return this.editing.text().getOrDefault(this.field(), SmartSigns.Text.EMPTY);
   }

   protected void init() {
      this.left = (this.width - 360) / 2;
      this.top = Math.max(4, (this.height - 302) / 2);
      this.initializing = true;
      if (this.indicator()) {
         this.initIndicator();
      } else {
         SmartSigns.Text current = this.current();
         this.addRenderableWidget(Button.builder(Component.literal("上一文字区域"), b -> this.select(-1)).bounds(this.left + 10, this.top + 30, 165, 20).build());
         this.addRenderableWidget(Button.builder(Component.literal("下一文字区域"), b -> this.select(1)).bounds(this.left + 185, this.top + 30, 165, 20).build());
         this.value = new EditBox(this.font, this.left + 10, this.top + 83, 340, 20, Component.literal(SmartSigns.label(this.field())));
         this.value.setMaxLength(160);
         this.value.setValue(this.rawValue == null ? current.value().replace("\n", "\\n") : this.rawValue);
         this.value.setResponder(v -> {
            this.rawValue = v;
            if (!this.initializing) {
               this.change(v, this.current().size(), this.current().wrap());
            }
         });
         this.addRenderableWidget(this.value);
         this.addRenderableWidget(Button.builder(Component.literal("字号：" + new String[]{"小", "中", "大"}[current.size()]), b -> {
            if (this.change(this.value.getValue(), (this.current().size() + 1) % 3, this.current().wrap())) {
               this.rebuildWidgets();
            }
         }).bounds(this.left + 10, this.top + 110, 110, 20).build());
         this.addRenderableWidget(Button.builder(Component.literal(current.wrap() ? "允许换行" : "单行"), b -> {
            if (this.change(this.value.getValue(), this.current().size(), !this.current().wrap())) {
               this.rebuildWidgets();
            }
         }).bounds(this.left + 125, this.top + 110, 110, 20).build());
         this.addRenderableWidget(Button.builder(Component.literal("清空当前字段"), b -> {
            this.value.setValue("");
            this.change("", this.current().size(), this.current().wrap());
         }).bounds(this.left + 240, this.top + 110, 110, 20).build());
         this.addRenderableWidget(Button.builder(Component.literal("进入场景预览"), b -> {
            if (!this.invalid) {
               draft = this;
               Minecraft.getInstance().setScreen(null);
            }
         }).bounds(this.left + 10, this.top + 139, 165, 20).build());
         this.addRenderableWidget(Button.builder(Component.literal("恢复此目标草稿"), b -> this.submit(true)).bounds(this.left + 185, this.top + 139, 165, 20).build());
         if (SmartSigns.headerMode(this.editing.mode())) {
            this.addRenderableWidget(Button.builder(Component.literal("本分支道路编号及名称区：" + (this.editing.headerVisible(this.branch()) ? "显示" : "隐藏")), b -> {
               try {
                  SmartSigns.State next = this.editing.header(this.branch(), !this.editing.headerVisible(this.branch()));
                  SmartSignClient.validate(this.road.mesh(), next);
                  this.edited();
                  this.editing = next;
                  SmartSignClient.preview(this.editing, this.road);
                  this.status = "隐藏保留原文字，再次显示即可恢复";
                  this.rebuildWidgets();
               } catch (IllegalArgumentException var3x) {
                  this.status = var3x.getMessage();
               }
            }).bounds(this.left + 10, this.top + 165, 340, 20).build());
         }

         if (SmartSigns.headerMode(this.editing.mode())) {
            for (int i = 0; i < 2; i++) {
               String part = i == 0 ? "exit" : "distance";
               String label = i == 0 ? "显示出口编号" : "显示距离";
               this.addRenderableWidget(
                  Button.builder(Component.literal(label + "：" + (this.editing.accessoryVisible(this.branch(), part) ? "开" : "关")), b -> {
                     try {
                        SmartSigns.State next = this.editing.accessory(this.branch(), part, !this.editing.accessoryVisible(this.branch(), part));
                        SmartSignClient.validate(this.road.mesh(), next);
                        this.edited();
                        this.editing = next;
                        SmartSignClient.preview(this.editing, this.road);
                        this.status = "仅改变附属牌显示，原文字与草稿保留";
                        this.rebuildWidgets();
                     } catch (IllegalArgumentException var4x) {
                        this.status = var4x.getMessage();
                     }
                  }).bounds(this.left + 10 + i * 175, this.top + 191, 165, 20).build()
               );
            }
         }

         this.addRenderableWidget(Button.builder(Component.literal("确认当前字段"), b -> this.submit(false)).bounds(this.left + 10, this.top + 261, 165, 20).build());
         this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> this.onClose()).bounds(this.left + 185, this.top + 261, 165, 20).build());
         this.initializing = false;
         SmartSignClient.preview(this.editing, this.road);
      }
   }

   private void select(int delta) {
      if (this.dirty()) {
         this.status = "当前字段尚未确认；请先确认或取消，避免丢失修改";
      } else {
         this.edited();
         this.selected = Math.floorMod(this.selected + delta, this.fields.size());
         this.rawValue = null;
         this.rebuildWidgets();
      }
   }

   private boolean change(String v, int size, boolean wrap) {
      this.edited();

      try {
         SmartSigns.State next = this.editing.edit(this.field(), new SmartSigns.Text(v.replace("\\n", "\n"), size, wrap));
         SmartSignClient.validate(this.road.mesh(), next);
         this.editing = next;
         this.status = "预览中；确认前不会保存";
         this.invalid = false;
         SmartSignClient.preview(this.editing, this.road);
         return true;
      } catch (IllegalArgumentException var5) {
         this.invalid = true;
         this.status = var5.getMessage();
         return false;
      }
   }

   private CompoundTag command(boolean restore) {
      if (!restore && !this.indicator()) {
         SmartSigns.State next = this.editing
            .edit(this.field(), new SmartSigns.Text(this.value.getValue().replace("\\n", "\n"), this.current().size(), this.current().wrap()));
         SmartSignClient.validate(this.road.mesh(), next);
         this.editing = next;
      }

      CompoundTag t = SmartSignCodec.key(this.original.key());
      t.putString("Action", "smartText");
      t.putLong("Revision", this.original.revision());
      if (this.original.target() != null) {
         t.putUUID("Target", this.original.target());
      }

      t.putBoolean("Restore", restore);
      t.putString("Field", this.field());
      if (this.indicator()) {
         t.putBoolean("Closed", this.editing.closedLanes().contains(this.branch()));
      } else {
         t.put("Value", SmartSignCodec.text(this.current()));
      }

      if (SmartSigns.headerMode(this.editing.mode())) {
         t.putBoolean("HeaderVisible", this.editing.headerVisible(this.branch()));
         t.putBoolean("ExitVisible", this.editing.accessoryVisible(this.branch(), "exit"));
         t.putBoolean("DistanceVisible", this.editing.accessoryVisible(this.branch(), "distance"));
      }

      return t;
   }

   private void submit(boolean restore) {
      if (!this.pending) {
         try {
            CompoundTag t = this.command(restore);
            this.pending = true;
            ClientRoads.sendAction(t);
         } catch (IllegalArgumentException var3) {
            this.status = var3.getMessage();
         }
      }
   }

   public static void result(SmartTextScreen owner, boolean success, String message) {
      if (draft == owner && owner.pending) {
         draft.pending = false;
         if (success) {
            clear();
            Minecraft.getInstance().setScreen(null);
         } else {
            draft.status = message;
            Minecraft.getInstance().setScreen(draft);
         }
      }
   }

   public void onClose() {
      clear();
      Minecraft.getInstance().setScreen(null);
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.left, this.top, this.left + 360, this.top + 297, -267247316);
      g.drawString(this.font, this.title, this.left + 10, this.top + 10, 16777215);
      g.drawString(
         this.font,
         this.indicator()
            ? "车道指示牌 " + (this.selected + 1) + " / " + this.fields.size()
            : "区域 " + (this.selected + 1) + " / " + this.fields.size() + " · " + SmartSigns.label(this.field()),
         this.left + 10,
         this.top + 61,
         13821144
      );
      super.render(g, mx, my, dt);
      g.drawWordWrap(this.font, Component.literal(this.status), this.left + 10, this.top + 221, 340, 16767397);
   }
}
