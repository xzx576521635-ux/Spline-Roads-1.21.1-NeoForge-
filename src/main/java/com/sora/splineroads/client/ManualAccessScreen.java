package com.sora.splineroads.client;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.world.RoadRecord;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class ManualAccessScreen extends Screen {
   private final CompoundTag payload;
   private EditBox length;
   private Button build;
   private String status = "先预览两条匝道、接出道路和主路分合流点";
   private boolean pending;

   ManualAccessScreen(CompoundTag t) {
      super(Component.literal("双向进出匝道"));
      this.payload = t.copy();
   }

   protected void init() {
      int x = this.width / 2 - 150;
      int y = this.height / 2 - 85;
      this.length = new EditBox(this.font, x + 120, y + 42, 165, 20, Component.literal("接出道路长度"));
      this.length.setValue(Double.toString(this.payload.getDouble("SpurLength")));
      this.length.setResponder(v -> {
         this.payload.remove("Token");
         if (this.build != null) {
            this.build.active = false;
         }
      });
      this.addRenderableWidget(this.length);
      this.addRenderableWidget(Button.builder(Component.literal("计算完整预览"), b -> this.send("manualAccessPreview")).bounds(x + 12, y + 102, 128, 20).build());
      this.build = (Button)this.addRenderableWidget(
         Button.builder(Component.literal("统一建造"), b -> this.send("manualAccessBuild")).bounds(x + 150, y + 102, 138, 20).build()
      );
      this.build.active = this.payload.hasUUID("Token");
      this.addRenderableWidget(Button.builder(Component.literal("实景预览"), b -> {
         this.show();
         ManualPlannerClient.worldPreview();
         this.minecraft.setScreen(null);
      }).bounds(x + 12, y + 127, 128, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("返回地图"), b -> this.onClose()).bounds(x + 150, y + 127, 138, 20).build());
      if (this.payload.hasUUID("Token")) {
         this.status = "完整预览已校验，可以统一建造；按 P 返回设置";
         this.show();
      }
   }

   private void show() {
      List<RoadGeometry.Mesh> roads = this.payload.getList("PlannerPreview", 10).stream().map(t -> RoadRecord.load((CompoundTag)t).mesh()).toList();
      ClientRoads.nodePreviews = roads;
      ClientRoads.preview = roads.isEmpty() ? null : roads.get(0);
      ClientRoads.conflictPreview = false;
   }

   private void send(String action) {
      if (!this.pending) {
         try {
            double value = Double.parseDouble(this.length.getValue());
            if (!Double.isFinite(value) || value < 64.0 || value > 160.0) {
               throw new IllegalArgumentException();
            }

            this.payload.putDouble("SpurLength", value);
            CompoundTag t = this.payload.copy();
            t.remove("PlannerPreview");
            t.putString("Action", action);
            this.pending = true;
            this.status = "正在校验完整占地与连接…";
            ClientRoads.sendAction(t);
         } catch (IllegalArgumentException var5) {
            this.status = "长度请输入64–160格";
         }
      }
   }

   void failed(String text) {
      this.pending = false;
      this.status = text;
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      int x = this.width / 2 - 150;
      int y = this.height / 2 - 85;
      g.fill(x, y, x + 300, y + 176, -267050450);
      g.drawString(this.font, this.title, x + 12, y + 13, -1, false);
      g.drawString(this.font, "接出道路长度", x + 12, y + 48, -3744793, false);
      g.drawWordWrap(this.font, Component.literal(this.status), x + 12, y + 72, 276, -2956565);
      super.render(g, mx, my, dt);
   }
}
