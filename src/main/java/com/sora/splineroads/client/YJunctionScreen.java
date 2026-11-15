package com.sora.splineroads.client;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.YJunctionPlanner;
import com.sora.splineroads.world.RoadRecord;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;

public final class YJunctionScreen extends Screen {
   private final CompoundTag payload;
   private EditBox tension;
   private String status = "";
   private int x;
   private int y;
   private int w;
   private int h;
   private boolean pending;
   private Button apply;
   private YJunctionPlanner.Plan plan;

   private void edited() {
      ClientRoads.editorChanged(this);
      this.pending = false;
   }

   public YJunctionScreen(CompoundTag t) {
      super(Component.literal("Y 字路口编辑器"));
      this.payload = t.copy();
   }

   protected void init() {
      this.w = Math.min(430, this.width - 12);
      this.h = Math.min(330, this.height - 8);
      this.x = (this.width - this.w) / 2;
      this.y = (this.height - this.h) / 2;

      for (int i = 0; i < 3; i++) {
         int slot = i;
         RoadGeometry.Settings settings = RoadRecord.readSettings(this.payload.getCompound("Profile" + i));
         Button button = (Button)this.addRenderableWidget(Button.builder(Component.literal((char)(65 + i) + " " + RoadLanes.counts(settings).label()), b -> {
            this.payload.putDouble("Tension", this.parseTension());
            this.minecraft.setScreen(new RoadLaneConfigScreen(this, RoadProfile.catalog(settings).type(), RoadLanes.counts(settings), (type, counts) -> {
               RoadGeometry.Settings changed = RoadLanes.configure(settings, type, counts, RoadProfile.layout(settings, settings.width()).laneWidth());
               this.payload.put("Profile" + slot, RoadRecord.writeSettings(changed));
               this.edited();
            }, slot == 0));
         }).bounds(this.x + 12 + i * (this.w - 24) / 3, this.y + 31, (this.w - 30) / 3, 20).build());
         button.active = !this.payload.getBoolean("LockedProfile" + i) && !this.pending;
      }

      this.tension = new EditBox(this.font, this.x + this.w - 100, this.y + this.h - 89, 86, 20, Component.literal("曲线强度"));
      this.tension.setMaxLength(5);
      this.tension.setValue(Double.toString(this.payload.getDouble("Tension")));
      this.addRenderableWidget(this.tension);
      this.tension.setResponder(v -> {
         this.edited();
         this.preview();
      });
      this.apply = (Button)this.addRenderableWidget(
         Button.builder(Component.literal(this.payload.hasUUID("Id") ? "保存修改" : "建造 Y 字路口"), b -> this.send(false))
            .bounds(this.x + 12, this.y + this.h - 62, (this.w - 32) / 2, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.literal("取消"), b -> this.onClose()).bounds(this.x + this.w / 2 + 4, this.y + this.h - 62, (this.w - 32) / 2, 20).build()
      );
      if (this.payload.hasUUID("Id")) {
         this.addRenderableWidget(
            Button.builder(Component.literal("删除此 Y 字路口"), b -> this.send(true)).bounds(this.x + 12, this.y + this.h - 37, this.w - 24, 20).build()
         );
      }

      this.preview();
   }

   private double parseTension() {
      try {
         return Double.parseDouble(this.tension.getValue());
      } catch (RuntimeException var2) {
         return this.payload.getDouble("Tension");
      }
   }

   private void preview() {
      try {
         ListTag n = this.payload.getList("Nodes", 10);
         this.plan = YJunctionPlanner.plan(
            RoadRecord.readNode(n.getCompound(0)),
            RoadRecord.readNode(n.getCompound(1)),
            RoadRecord.readNode(n.getCompound(2)),
            RoadRecord.readSettings(this.payload.getCompound("Profile0")),
            RoadRecord.readSettings(this.payload.getCompound("Profile1")),
            RoadRecord.readSettings(this.payload.getCompound("Profile2")),
            Double.parseDouble(this.tension.getValue())
         );
         this.status = "";
      } catch (IllegalArgumentException var2) {
         this.plan = null;
         this.status = var2 instanceof NumberFormatException ? "请输入曲线强度" : var2.getMessage();
      }

      if (this.apply != null) {
         this.apply.active = this.plan != null && !this.pending;
      }
   }

   private void send(boolean delete) {
      if (!this.pending && (delete || this.plan != null)) {
         CompoundTag t = this.payload.copy();
         t.putString("Action", "yJunction");
         if (!delete) {
            t.putDouble("Tension", Double.parseDouble(this.tension.getValue()));
         }

         t.putBoolean("Delete", delete);
         this.pending = true;
         this.apply.active = false;
         ClientRoads.sendAction(t);
      }
   }

   void completed() {
      this.pending = false;
   }

   public void failed(String s) {
      this.pending = false;
      this.status = s;
      this.apply.active = this.plan != null;
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.x, this.y, this.x + this.w, this.y + this.h, -267050450);
      g.drawString(this.font, "Y 字路口 · A 双向 / B 出向 / C 入向", this.x + 12, this.y + 12, 16777215, false);
      g.fill(this.x + 12, this.y + 55, this.x + this.w - 12, this.y + this.h - 123, -14340298);
      if (this.plan != null) {
         List<RoadGeometry.Mesh> ms = List.of(this.plan.stem(), this.plan.outbound(), this.plan.inbound());
         double minX = ms.stream().mapToDouble(m -> m.min().x()).min().orElse(0.0);
         double maxX = ms.stream().mapToDouble(m -> m.max().x()).max().orElse(1.0);
         double minZ = ms.stream().mapToDouble(m -> m.min().z()).min().orElse(0.0);
         double maxZ = ms.stream().mapToDouble(m -> m.max().z()).max().orElse(1.0);
         double scale = Math.min((double)(this.w - 70) / Math.max(1.0, maxX - minX), (double)(this.h - 198) / Math.max(1.0, maxZ - minZ));
         double cx = (minX + maxX) / 2.0;
         double cz = (minZ + maxZ) / 2.0;

         for (int i = 0; i < ms.size(); i++) {
            for (RoadGeometry.Sample s : ms.get(i).samples()) {
               int px = this.x + this.w / 2 + (int)((s.center().x() - cx) * scale);
               int py = this.y + 65 + (this.h - 188) / 2 + (int)((s.center().z() - cz) * scale);
               int r = Math.max(1, (int)(s.halfWidth() * scale));
               g.fill(px - r, py - r, px + r + 1, py + r + 1, i == 0 ? -6378574 : (i == 1 ? -11806811 : -1133976));
            }
         }

         ListTag n = this.payload.getList("Nodes", 10);

         for (int i = 0; i < 3; i++) {
            RoadGeometry.V p = RoadRecord.readNode(n.getCompound(i)).position();
            g.drawCenteredString(
               this.font,
               (char)(65 + i) + "",
               this.x + this.w / 2 + (int)((p.x() - cx) * scale),
               this.y + 65 + (this.h - 188) / 2 + (int)((p.z() - cz) * scale),
               16777215
            );
         }
      }

      g.drawWordWrap(
         this.font,
         Component.literal(this.status.isEmpty() ? "绿色 A→B 按 B 车道过渡，橙色 C→A 按 A 接收方向车道过渡；B、C 须为单向道路，可使用不同车道数。" : this.status),
         this.x + 14,
         this.y + this.h - 116,
         this.w - 28,
         this.status.isEmpty() ? 12442069 : 16756625
      );
      g.drawString(this.font, "曲线强度（0.2–0.8）", this.x + 14, this.y + this.h - 82, 14149871, false);
      super.render(g, mx, my, dt);
   }
}
