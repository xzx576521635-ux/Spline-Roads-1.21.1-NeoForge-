package com.sora.splineroads.client;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadLaneLines;
import com.sora.splineroads.world.RoadRecord;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;

public final class LaneLineScreen extends Screen {
   private final CompoundTag payload;
   private final ListTag lines;
   private final RoadGeometry.Settings settings;
   private int selected;
   private int x;
   private int y;
   private int w;
   private int h;
   private RoadLaneLines.Pattern pattern;
   private double lineWidth;
   private EditBox widthBox;
   private Button apply;
   private Button arrows;
   private String status = "";
   private boolean pending;
   private boolean playerFacing = true;

   public LaneLineScreen(CompoundTag t) {
      super(Component.literal("车道标线编辑器"));
      this.payload = t.copy();
      this.lines = t.getList("Lines", 10);
      this.settings = RoadRecord.readSettings(t.getCompound("Settings"));
      this.select(Math.min(this.lines.size() - 1, t.getInt("Selected")));
   }

   private void edited() {
      ClientRoads.editorChanged(this);
      this.pending = false;
      if (this.apply != null) {
         this.apply.active = true;
      }

      if (this.arrows != null) {
         this.arrows.active = true;
      }
   }

   private void select(int i) {
      this.edited();
      this.selected = i;
      RoadLaneLines.Edit e = RoadLaneLines.get(this.settings, this.lines.getCompound(i).getString("Key"));
      this.pattern = e.pattern();
      this.lineWidth = e.width();
   }

   protected void init() {
      this.w = Math.min(420, this.width - 16);
      this.x = (this.width - this.w) / 2;
      this.h = Math.min(280, this.height - 8);
      this.y = (this.height - this.h) / 2;
      this.addRenderableWidget(Button.builder(Component.literal(this.playerFacing ? "朝向：与玩家一致" : "朝向：起点→终点"), b -> {
         this.playerFacing = !this.playerFacing;
         b.setMessage(Component.literal(this.playerFacing ? "朝向：与玩家一致" : "朝向：起点→终点"));
      }).bounds(this.x + 12, this.y + 25, this.w - 24, 18).build());
      this.addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
         this.select((this.selected + this.lines.size() - 1) % this.lines.size());
         this.rebuildWidgets();
      }).bounds(this.x + 12, this.y + this.h - 132, 28, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal(this.lines.getCompound(this.selected).getString("Label")), b -> {
         this.select((this.selected + 1) % this.lines.size());
         this.rebuildWidgets();
      }).bounds(this.x + 44, this.y + this.h - 132, this.w - 88, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
         this.select((this.selected + 1) % this.lines.size());
         this.rebuildWidgets();
      }).bounds(this.x + this.w - 40, this.y + this.h - 132, 28, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("线型：" + this.pattern.label), b -> {
         this.edited();
         this.pattern = RoadLaneLines.Pattern.values()[(this.pattern.ordinal() + 1) % RoadLaneLines.Pattern.values().length];
         b.setMessage(Component.literal("线型：" + this.pattern.label));
      }).bounds(this.x + 12, this.y + this.h - 105, this.w - 126, 20).build());
      this.widthBox = new EditBox(this.font, this.x + this.w - 106, this.y + this.h - 105, 94, 20, Component.literal("线宽"));
      this.widthBox.setMaxLength(6);
      this.widthBox.setValue(Double.toString(this.lineWidth));
      this.widthBox.setResponder(v -> this.edited());
      this.addRenderableWidget(this.widthBox);
      this.arrows = (Button)this.addRenderableWidget(
         Button.builder(Component.literal(this.settings.options().hideArrows() ? "本路段箭头：隐藏 · 点击显示" : "本路段箭头：显示 · 点击隐藏"), b -> this.sendArrows())
            .bounds(this.x + 12, this.y + this.h - 51, this.w - 24, 20)
            .build()
      );
      this.arrows.active = !this.pending;
      this.apply = (Button)this.addRenderableWidget(
         Button.builder(Component.literal("应用到这根线"), b -> this.send()).bounds(this.x + 12, this.y + this.h - 76, (this.w - 30) / 2, 20).build()
      );
      this.apply.active = !this.pending;
      this.addRenderableWidget(
         Button.builder(Component.literal("取消"), b -> this.onClose()).bounds(this.x + this.w / 2 + 3, this.y + this.h - 76, (this.w - 30) / 2, 20).build()
      );
   }

   public static int facingSign(double roadYaw, double playerYaw) {
      return Math.cos(Math.toRadians(roadYaw - playerYaw)) >= 0.0 ? 1 : -1;
   }

   private int lx(int i) {
      return this.x
         + 26
         + (int)(
            (
                  0.5
                     + (double)(this.playerFacing ? facingSign(this.payload.getDouble("RoadYaw"), this.payload.getDouble("PlayerYaw")) : 1)
                        * this.lines.getCompound(i).getDouble("Offset")
                        / this.payload.getDouble("Width")
               )
               * (double)(this.w - 52)
         );
   }

   public boolean mouseClicked(double mx, double my, int button) {
      if (button == 0 && my >= (double)(this.y + 57) && my <= (double)(this.y + this.h - 143)) {
         int best = this.selected;
         double d = 12.0;

         for (int i = 0; i < this.lines.size(); i++) {
            if (Math.abs(mx - (double)this.lx(i)) < d) {
               d = Math.abs(mx - (double)this.lx(i));
               best = i;
            }
         }

         this.select(best);
         this.rebuildWidgets();
         return true;
      } else {
         return super.mouseClicked(mx, my, button);
      }
   }

   private void send() {
      if (!this.pending) {
         try {
            RoadLaneLines.Edit edit = new RoadLaneLines.Edit(
               this.lines.getCompound(this.selected).getString("Key"), this.pattern, Double.parseDouble(this.widthBox.getValue())
            );
            CompoundTag t = new CompoundTag();
            t.putString("Action", "laneLines");
            t.putUUID("Id", this.payload.getUUID("Id"));
            t.putInt("Signature", this.payload.getInt("Signature"));
            t.putDouble("Station", this.payload.getDouble("Station"));
            t.putString("Key", edit.key());
            t.putString("Pattern", this.pattern.name());
            t.putDouble("Width", edit.width());
            this.pending = true;
            this.apply.active = false;
            this.arrows.active = false;
            ClientRoads.sendAction(t);
         } catch (IllegalArgumentException var3) {
            this.status = var3 instanceof NumberFormatException ? "请输入有效线宽" : var3.getMessage();
         }
      }
   }

   private void sendArrows() {
      if (!this.pending) {
         CompoundTag t = new CompoundTag();
         t.putString("Action", "laneLines");
         t.putUUID("Id", this.payload.getUUID("Id"));
         t.putInt("Signature", this.payload.getInt("Signature"));
         t.putDouble("Station", this.payload.getDouble("Station"));
         t.putBoolean("HideArrows", !this.settings.options().hideArrows());
         this.pending = true;
         this.apply.active = false;
         this.arrows.active = false;
         ClientRoads.sendAction(t);
      }
   }

   public void failed(String s) {
      this.pending = false;
      this.status = s;
      this.apply.active = true;
      this.arrows.active = true;
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.x, this.y, this.x + this.w, this.y + this.h, -267050450);
      g.drawString(
         this.font,
         String.format(Locale.ROOT, "标线区间 %.1f–%.1f 米 · 点击选线", this.payload.getDouble("RangeStart"), this.payload.getDouble("RangeEnd")),
         this.x + 12,
         this.y + 13,
         16777215,
         false
      );
      g.fill(this.x + 20, this.y + 57, this.x + this.w - 20, this.y + this.h - 143, -12368053);

      for (int i = 0; i < this.lines.size(); i++) {
         RoadLaneLines.Pattern p = i == this.selected ? this.pattern : RoadLaneLines.get(this.settings, this.lines.getCompound(i).getString("Key")).pattern();
         int xx = this.lx(i);
         int color = i == this.selected ? -11085569 : (p.yellow() ? -10935 : -1118482);

         for (int yy = this.y + 65; yy < this.y + this.h - 151; yy += 12) {
            g.fill(xx - 1, yy, xx + 2, Math.min(yy + (p.dashed() ? 7 : 12), this.y + this.h - 151), color);
         }

         g.drawCenteredString(this.font, Integer.toString(i + 1), xx, this.y + 47, i == this.selected ? 5691647 : 12303291);
      }

      super.render(g, mx, my, dt);
      g.drawWordWrap(
         this.font,
         Component.literal(this.status.isEmpty() ? "线宽 0.06–0.4 格。箭头开关仅作用于本路段。" : this.status),
         this.x + 12,
         this.y + this.h - 26,
         this.w - 24,
         this.status.isEmpty() ? 12309468 : 16758154
      );
   }
}
