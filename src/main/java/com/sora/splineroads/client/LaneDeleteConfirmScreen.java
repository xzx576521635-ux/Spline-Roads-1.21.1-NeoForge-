package com.sora.splineroads.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;

public final class LaneDeleteConfirmScreen extends Screen {
   private final Screen parent;
   private final ListTag ids;
   private final Runnable confirm;
   private int page;
   private boolean seenLast;

   public LaneDeleteConfirmScreen(Screen parent, ListTag ids, Runnable confirm) {
      super(Component.literal("确认级联删除"));
      this.parent = parent;
      this.ids = ids.copy();
      this.confirm = confirm;
   }

   protected void init() {
      int x = (this.width - 340) / 2;
      int y = (this.height - 242) / 2;
      this.seenLast = this.seenLast | (this.page + 1) * 8 >= this.ids.size();
      this.addRenderableWidget(Button.builder(Component.literal("上一页"), b -> {
         this.page = Math.max(0, this.page - 1);
         this.rebuildWidgets();
      }).bounds(x + 12, y + 176, 95, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("下一页"), b -> {
         this.page = Math.min((this.ids.size() - 1) / 8, this.page + 1);
         this.rebuildWidgets();
      }).bounds(x + 233, y + 176, 95, 20).build());
      Button yes = (Button)this.addRenderableWidget(Button.builder(Component.literal("确认删除所列依赖"), b -> {
         this.minecraft.setScreen(this.parent);
         this.confirm.run();
      }).bounds(x + 12, y + 210, 194, 20).build());
      yes.active = this.seenLast;
      this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> this.onClose()).bounds(x + 218, y + 210, 110, 20).build());
   }

   public void onClose() {
      this.minecraft.setScreen(this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      int x = (this.width - 340) / 2;
      int y = (this.height - 242) / 2;
      g.fill(x, y, x + 340, y + 242, -266853328);
      g.drawString(this.font, "将连同目标删除以下 " + this.ids.size() + " 条依赖匝道", x + 12, y + 12, 16757660, false);
      g.drawString(this.font, "仅删除清单内的有效连接及其后续分支", x + 12, y + 32, 16777215, false);

      for (int i = 0; i < 8 && this.page * 8 + i < this.ids.size(); i++) {
         g.drawString(this.font, this.ids.getString(this.page * 8 + i), x + 12, y + 55 + i * 14, 10144767, false);
      }

      g.drawString(this.font, this.page + 1 + " / " + Math.max(1, (this.ids.size() + 7) / 8), x + 153, y + 182, 16777215, false);
      super.render(g, mx, my, dt);
   }
}
