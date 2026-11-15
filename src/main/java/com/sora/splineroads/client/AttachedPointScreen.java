package com.sora.splineroads.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class AttachedPointScreen extends Screen {
   private final CompoundTag payload;
   private Button delete;
   private String status = "";
   private int x;
   private int y;
   private int w;

   public AttachedPointScreen(CompoundTag payload) {
      super(Component.literal(payload.getBoolean("Elevated") ? "高架出入点" : "道路附属点"));
      this.payload = payload.copy();
   }

   protected void init() {
      this.w = Math.min(300, this.width - 16);
      this.x = (this.width - this.w) / 2;
      this.y = Math.max(4, (this.height - 122) / 2);
      this.delete = (Button)this.addRenderableWidget(Button.builder(Component.literal(this.payload.getBoolean("Elevated") ? "删除出入点" : "删除附属点"), b -> {
         CompoundTag t = new CompoundTag();
         t.putString("Action", "attachedPoint");
         t.putUUID("Id", this.payload.getUUID("Id"));
         t.putUUID("Point", this.payload.getUUID("Point"));
         t.putInt("Signature", this.payload.getInt("Signature"));
         t.putBoolean("Delete", true);
         ClientRoads.sendAction(t);
         this.delete.active = false;
      }).bounds(this.x + 12, this.y + 70, (this.w - 32) / 2, 20).build());
      this.addRenderableWidget(
         Button.builder(Component.literal("关闭"), b -> this.onClose()).bounds(this.x + 20 + (this.w - 32) / 2, this.y + 70, (this.w - 32) / 2, 20).build()
      );
   }

   public void failed(String text) {
      this.status = text;
      this.delete.active = true;
   }

   public void tick() {
      if (this.payload.getBoolean("PlannerDelete") && this.delete != null) {
         this.payload.remove("PlannerDelete");
         this.delete.onPress();
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.x, this.y, this.x + this.w, this.y + 122, -267050450);
      g.drawString(this.font, this.title, this.x + 12, this.y + 12, 16777215, false);
      g.drawWordWrap(
         this.font,
         Component.literal(this.payload.getBoolean("Elevated") ? "用道路连接器选择此点和高处的尽头端点，设置高架上下坡连接。" : "固定在所选道路断面中央，用于标线分段和道路选择。"),
         this.x + 12,
         this.y + 34,
         this.w - 24,
         12309468
      );
      super.render(g, mx, my, dt);
      g.drawWordWrap(this.font, Component.literal(this.status), this.x + 12, this.y + 98, this.w - 24, 16759688);
   }
}
