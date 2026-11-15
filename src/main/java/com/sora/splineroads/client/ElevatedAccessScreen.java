package com.sora.splineroads.client;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.world.RoadRecord;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class ElevatedAccessScreen extends Screen {
   private final CompoundTag payload;
   private boolean pending;
   private String status = "逐方向分离内侧车道；等车道数时增加外侧直行辅路";
   private final List<RoadRecord> records;
   private final List<RoadGeometry.Mesh> meshes;
   private Button build;
   private int x;
   private int y;

   public ElevatedAccessScreen(CompoundTag payload) {
      super(Component.literal("高架出入点 · 道路连接"));
      this.payload = payload.copy();
      this.records = this.payload.getList("PlannerPreview", 10).stream().map(t -> RoadRecord.load((CompoundTag)t)).toList();
      this.meshes = this.records.stream().map(RoadRecord::mesh).toList();
      this.payload.remove("PlannerPreview");
   }

   private RoadGeometry.Settings settings() {
      return RoadRecord.readSettings(this.payload.getCompound("Settings"));
   }

   protected void init() {
      this.x = (this.width - 340) / 2;
      this.y = Math.max(6, (this.height - 220) / 2);
      RoadLanes.Counts n = RoadLanes.counts(this.settings());
      this.label(n.twoWay() ? "双向高架" : "单向高架 · A → B", this.x + 12, this.y + 38, 316);
      this.label("A → B：" + n.forward() + " 车道", this.x + 12, this.y + 64, 152);
      this.label("B → A：" + n.reverse() + " 车道", this.x + 176, this.y + 64, 152);
      boolean existing = this.payload.hasUUID("Id");
      boolean approved = this.payload.hasUUID("Token");
      this.addRenderableWidget(
         Button.builder(Component.literal(existing ? "重新计算连接" : "计算完整预览"), b -> this.send("elevatedAccessPreview"))
            .bounds(this.x + 12, this.y + 136, 152, 20)
            .build()
      );
      this.build = (Button)this.addRenderableWidget(Button.builder(Component.literal(existing ? (approved ? "保存连接" : "完成") : "建造连接"), b -> {
         if (existing && !approved) {
            this.onClose();
         } else {
            this.send("elevatedAccessBuild");
         }
      }).bounds(this.x + 176, this.y + 136, 152, 20).build());
      this.build.active = !this.pending && (existing || approved);
      Button view = (Button)this.addRenderableWidget(Button.builder(Component.literal("实景预览 · P 返回"), b -> {
         this.show();
         ManualPlannerClient.worldPreview();
         this.minecraft.setScreen(null);
      }).bounds(this.x + 12, this.y + 162, existing ? 136 : 204, 20).build());
      view.active = !this.meshes.isEmpty() && !this.pending;
      if (existing) {
         this.addRenderableWidget(
            Button.builder(Component.literal("删除连接…"), b -> this.send("deletePreview")).bounds(this.x + 152, this.y + 162, 90, 20).build()
         );
      }

      this.addRenderableWidget(
         Button.builder(Component.literal("关闭"), b -> this.onClose()).bounds(this.x + (existing ? 246 : 228), this.y + 162, existing ? 82 : 100, 20).build()
      );
      if (approved) {
         this.status = "预览已完成，显示高架连接本体；保存时同步更新主路车道";
      } else if (existing) {
         this.status = "正在查看已建连接。可直接预览或完成；要更新设施，可重新计算后保存";
      }

      if (!this.meshes.isEmpty()) {
         this.show();
      }
   }

   private void label(String text, int x, int y, int width) {
      ((Button)this.addRenderableWidget(Button.builder(Component.literal(text), ignored -> {
      }).bounds(x, y, width, 20).build())).active = false;
   }

   private void show() {
      ClientRoads.nodePreviews = this.meshes;
      ClientRoads.preview = this.meshes.isEmpty() ? null : this.meshes.get(0);
      ClientRoads.conflictPreview = false;
      RoadRenderer.previewCandidates(this.records);
   }

   private void clearPreview() {
      ClientRoads.preview = null;
      ClientRoads.nodePreviews = List.of();
      RoadRenderer.previewCandidates(List.of());
   }

   public void onClose() {
      this.clearPreview();
      ClientRoads.endEditor(this);
      ManualPlannerClient.completed(this);
      super.onClose();
   }

   private void send(String action) {
      if (!this.pending) {
         CompoundTag t = this.payload.copy();
         t.putString("Action", action);
         this.pending = true;
         this.build.active = false;
         this.status = "正在检查高架与地面车道、净空和占地…";
         ClientRoads.sendAction(t);
      }
   }

   public void failed(String message) {
      this.pending = false;
      this.status = message;
      if (this.build != null) {
         this.build.active = this.payload.hasUUID("Token") || this.payload.hasUUID("Id");
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.x, this.y, this.x + 340, this.y + 210, -267050450);
      g.drawString(this.font, this.title, this.x + 12, this.y + 14, 16777215, false);
      g.drawWordWrap(this.font, Component.literal(this.status), this.x + 12, this.y + 96, 316, 12967399);
      if (this.payload.getBoolean("Locked")) {
         g.drawString(this.font, "继承已有高架尽头的方向、车道数和宽度", this.x + 12, this.y + 191, 9685204, false);
      }

      super.render(g, mx, my, dt);
   }
}
