package com.sora.splineroads.client;

import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadProfile;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RoadLaneConfigScreen extends Screen {
   private final Screen parent;
   private final BiConsumer<RoadProfile.Type, RoadLanes.Counts> apply;
   private final Boolean requiredTwoWay;
   private RoadProfile.Type type;
   private int forward;
   private int reverse;
   private int savedReverse;
   private int x;
   private int y;

   RoadLaneConfigScreen(RoadScreen parent, RoadProfile.Type type, RoadLanes.Counts counts) {
      this(parent, type, counts, (t, c) -> parent.applyLaneConfiguration(t, c, false), null);
   }

   RoadLaneConfigScreen(
      Screen parent, RoadProfile.Type type, RoadLanes.Counts counts, BiConsumer<RoadProfile.Type, RoadLanes.Counts> apply, Boolean requiredTwoWay
   ) {
      super(Component.literal("道路车道配置"));
      this.parent = parent;
      this.type = type;
      this.apply = apply;
      this.requiredTwoWay = requiredTwoWay;
      this.forward = counts.forward();
      this.reverse = counts.reverse();
      this.savedReverse = this.reverse > 0 ? this.reverse : this.forward;
      if (requiredTwoWay != null) {
         this.reverse = requiredTwoWay ? this.savedReverse : 0;
      }
   }

   protected void init() {
      this.x = Math.max(8, (this.width - 280) / 2);
      this.y = Math.max(4, (this.height - 194) / 2);
      this.addRenderableWidget(Button.builder(Component.literal(this.type == RoadProfile.Type.HIGHWAY ? "高速道路" : "普通道路"), b -> {
         this.type = this.type == RoadProfile.Type.HIGHWAY ? RoadProfile.Type.ORDINARY : RoadProfile.Type.HIGHWAY;
         this.rebuildWidgets();
      }).bounds(this.x, this.y + 24, 136, 20).build());
      Button direction = (Button)this.addRenderableWidget(Button.builder(Component.literal(this.reverse > 0 ? "双向" : "单向"), b -> {
         if (this.reverse > 0) {
            this.savedReverse = this.reverse;
            this.reverse = 0;
         } else {
            this.reverse = this.savedReverse;
         }

         this.rebuildWidgets();
      }).bounds(this.x + 144, this.y + 24, 136, 20).build());
      direction.active = this.requiredTwoWay == null;
      this.addRenderableWidget(new RoadLaneConfigScreen.CountSlider(this.x, this.y + 54, this.forward, true));
      if (this.reverse > 0) {
         this.addRenderableWidget(new RoadLaneConfigScreen.CountSlider(this.x, this.y + 82, this.reverse, false));
      }

      this.addRenderableWidget(Button.builder(Component.literal("应用配置"), b -> {
         this.apply.accept(this.type, new RoadLanes.Counts(this.forward, this.reverse));
         this.minecraft.setScreen(this.parent);
      }).bounds(this.x, this.y + 162, 136, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> this.onClose()).bounds(this.x + 144, this.y + 162, 136, 20).build());
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.x - 8, this.y - 6, this.x + 288, this.y + 190, -284024532);
      g.drawString(this.font, this.title, this.x, this.y + 6, 16777215, false);
      g.drawWordWrap(this.font, Component.literal("数量按 A→B / B→A 分别保存。合并后的续接端口以当前位置实际断面为准；未修改的旧路保留原配置。"), this.x, this.y + 114, 280, 12441054);
      super.render(g, mx, my, dt);
   }

   public void onClose() {
      this.minecraft.setScreen(this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }

   private final class CountSlider extends AbstractSliderButton {
      final boolean along;

      CountSlider(int x, int y, int count, boolean along) {
         super(x, y, 280, 20, Component.empty(), (double)(count - 1) / 6.0);
         this.along = along;
         this.updateMessage();
      }

      int count() {
         return Math.max(1, Math.min(7, 1 + (int)Math.round(this.value * 6.0)));
      }

      protected void updateMessage() {
         this.setMessage(Component.literal((this.along ? "A → B" : "B → A") + "：" + this.count() + " 车道（1–7）"));
      }

      protected void applyValue() {
         int snapped = this.count();
         this.value = (double)(snapped - 1) / 6.0;
         if (this.along) {
            RoadLaneConfigScreen.this.forward = snapped;
         } else {
            RoadLaneConfigScreen.this.reverse = snapped;
            RoadLaneConfigScreen.this.savedReverse = RoadLaneConfigScreen.this.reverse;
         }

         this.updateMessage();
      }

      public boolean keyPressed(int key, int scan, int mods) {
         if (key != 263 && key != 262) {
            return super.keyPressed(key, scan, mods);
         } else {
            this.value = (double)(Math.max(1, Math.min(7, this.count() + (key == 262 ? 1 : -1))) - 1) / 6.0;
            this.applyValue();
            return true;
         }
      }
   }
}
