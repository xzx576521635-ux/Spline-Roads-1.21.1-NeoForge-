package com.sora.splineroads.client;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.world.RoundaboutTool;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class RoundaboutScreen extends Screen {
   private final CompoundTag payload;
   private RoadGeometry.Mesh ownedPreview;
   private List<RoadGeometry.Mesh> ownedPreviews;
   private List<RoadSurface.Face> ownedFaces;
   private EditBox radius;
   private String status = "";
   private boolean pending;
   private Button build;

   private void edited() {
      ClientRoads.editorChanged(this);
      this.pending = false;
   }

   public RoundaboutScreen(CompoundTag t) {
      super(Component.literal("Spline Roads · 环岛生成器"));
      this.payload = t.copy();
   }

   protected void init() {
      int x = this.width / 2 - 140;
      int y = this.height / 2 - 80;
      this.radius = new EditBox(this.font, x + 130, y + 30, 140, 20, Component.literal("中心线半径"));
      this.radius.setValue(Double.toString(this.payload.getDouble("Radius")));
      this.addRenderableWidget(this.radius);
      this.addRenderableWidget(Button.builder(Component.literal(this.payload.getInt("Lanes") == 1 ? "环道：单车道" : "环道：双车道"), b -> {
         this.capture();
         this.payload.putInt("Lanes", this.payload.getInt("Lanes") == 1 ? 2 : 1);
         this.edited();
         this.rebuildWidgets();
      }).bounds(x, y + 60, 130, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal(this.payload.getBoolean("LeftTraffic") ? "左侧通行" : "右侧通行"), b -> {
         this.capture();
         this.payload.putBoolean("LeftTraffic", !this.payload.getBoolean("LeftTraffic"));
         this.edited();
         this.rebuildWidgets();
      }).bounds(x + 140, y + 60, 130, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("外侧护栏：" + (this.payload.getBoolean("OuterRail") ? "开" : "关")), b -> {
         this.capture();
         this.payload.putBoolean("OuterRail", !this.payload.getBoolean("OuterRail"));
         this.edited();
         this.rebuildWidgets();
      }).bounds(x, y + 86, 270, 20).build());
      this.build = (Button)this.addRenderableWidget(Button.builder(Component.literal("生成环岛"), b -> {
         if (this.refresh() && !this.pending) {
            CompoundTag t = this.payload.copy();
            t.putString("Action", "roundabout");
            this.pending = true;
            this.build.active = false;
            ClientRoads.sendAction(t);
         }
      }).bounds(x, y + 115, 130, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> this.onClose()).bounds(x + 140, y + 115, 130, 20).build());
      this.radius.setResponder(v -> {
         this.edited();
         this.refresh();
      });
      this.refresh();
   }

   private boolean capture() {
      try {
         this.payload.putDouble("Radius", Double.parseDouble(this.radius.getValue()));
         return true;
      } catch (NumberFormatException var2) {
         this.status = "请输入半径";
         return false;
      }
   }

   private boolean refresh() {
      if (!this.capture()) {
         if (this.build != null) {
            this.build.active = false;
         }

         this.clearPreview();
         return false;
      } else {
         try {
            JunctionSpec spec = RoundaboutTool.specification(this.payload);
            JunctionPlanner.Plan plan = JunctionPlanner.plan(spec);
            this.ownedPreviews = plan.pieces().stream().map(JunctionPlanner.Piece::mesh).toList();
            ClientRoads.nodePreviews = this.ownedPreviews;
            ClientRoads.preview = ClientRoads.nodePreviews.get(0);
            this.ownedPreview = ClientRoads.preview;
            JunctionScreen.previewMesh = this.ownedPreview;
            ArrayList<RoadSurface.Face> faces = new ArrayList<>();

            for (JunctionPlanner.Piece part : plan.pieces()) {
               faces.addAll(part.paint());
               part.structures().forEach(s -> faces.addAll(s.faces()));
            }

            this.ownedFaces = List.copyOf(faces);
            JunctionScreen.previewFaces = this.ownedFaces;
            this.status = "生成 8 个方向接路点；A 北 B 南 C 西 D 东";
            if (this.build != null) {
               this.build.active = !this.pending;
            }

            return true;
         } catch (IllegalArgumentException var6) {
            this.status = var6.getMessage();
            if (this.build != null) {
               this.build.active = false;
            }

            this.clearPreview();
            return false;
         }
      }
   }

   public void failed(String message) {
      this.pending = false;
      this.status = message;
      this.build.active = true;
   }

   private void clearPreview() {
      if (this.ownedPreview != null && ClientRoads.preview == this.ownedPreview) {
         ClientRoads.preview = null;
      }

      if (this.ownedPreviews != null && ClientRoads.nodePreviews == this.ownedPreviews) {
         ClientRoads.nodePreviews = List.of();
      }

      if (this.ownedPreview != null && JunctionScreen.previewMesh == this.ownedPreview) {
         JunctionScreen.previewMesh = null;
      }

      if (this.ownedFaces != null && JunctionScreen.previewFaces == this.ownedFaces) {
         JunctionScreen.previewFaces = List.of();
      }

      this.ownedPreview = null;
      this.ownedPreviews = null;
      this.ownedFaces = null;
   }

   public void onClose() {
      this.clearPreview();
      super.onClose();
   }

   public void removed() {
      this.clearPreview();
      super.removed();
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      int x = this.width / 2 - 140;
      int y = this.height / 2 - 80;
      g.fill(x - 12, y - 12, x + 282, y + 156, -300210896);
      g.drawString(this.font, this.title, x, y, 16777215);
      g.drawString(this.font, "中心线半径 / 格", x, y + 36, 14082797);
      super.render(g, mx, my, dt);
      g.drawString(this.font, this.font.plainSubstrByWidth(this.status, 280), x, y + 143, 12113611);
   }
}
