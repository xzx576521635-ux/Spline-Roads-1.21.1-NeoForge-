package com.sora.splineroads.client;

import com.sora.splineroads.core.LaneExpansion;
import com.sora.splineroads.core.LaneMerge;
import com.sora.splineroads.core.LanePointPreview;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.world.LaneTopology;
import com.sora.splineroads.world.RoadRecord;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class LanePointScreen extends Screen {
   private static final ExecutorService PREVIEW = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "SR lane point view");
      t.setDaemon(true);
      return t;
   });
   private final CompoundTag payload;
   private final RoadRecord road;
   private final RoadGeometry.Mesh mesh;
   private final LanePoints.Point point;
   private int lane;
   private int x;
   private int y;
   private int w;
   private int h;
   private int viewX;
   private int viewY;
   private int viewW;
   private int viewH;
   private int requestedLane;
   private String status = "";
   private Button apply;
   private Button remove;
   private Button merge;
   private Button expand;
   private boolean confirm;
   private boolean pending;
   private LanePointPreview.Image image;
   private CompletableFuture<LanePointPreview.Image> drawing;

   public LanePointScreen(CompoundTag t) {
      super(Component.literal("蓝色车道点"));
      this.payload = t.copy();
      this.road = RoadRecord.load(t.getCompound("Road"));
      this.point = LaneTopology.point(this.road, t.getUUID("Point"));
      this.lane = this.point.lane();
      this.mesh = this.road.mesh();
   }

   protected void init() {
      this.w = Math.min(460, this.width - 16);
      this.h = Math.min(310, this.height - 16);
      this.x = (this.width - this.w) / 2;
      this.y = (this.height - this.h) / 2;
      this.viewX = this.x + 12;
      this.viewY = this.y + 36;
      this.viewW = this.w - 24;
      this.viewH = this.h - 110;
      if (this.image != null && (this.image.width() != this.viewW || this.image.height() != this.viewH)) {
         this.image = null;
      }

      if (this.drawing != null) {
         this.drawing.cancel(false);
         this.drawing = null;
      }

      this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> this.onClose()).bounds(this.x + this.w - 56, this.y + 8, 44, 20).build());
      this.apply = (Button)this.addRenderableWidget(
         Button.builder(Component.literal("应用"), b -> this.send(false)).bounds(this.x + 12, this.y + this.h - 30, 88, 20).build()
      );
      this.remove = (Button)this.addRenderableWidget(Button.builder(Component.literal("删除"), b -> {
         if (!this.payload.getList("Dependencies", 8).isEmpty() && !this.confirm) {
            this.minecraft.setScreen(new LaneDeleteConfirmScreen(this, this.payload.getList("Dependencies", 8), () -> {
               this.confirm = true;
               this.send(true);
            }));
         } else {
            this.send(true);
         }
      }).bounds(this.x + this.w - 100, this.y + this.h - 30, 88, 20).build());
      this.merge = (Button)this.addRenderableWidget(
         Button.builder(Component.literal(this.point.mergeLength() > 0.0 ? "取消合流缩减" : "外侧合流缩减"), b -> this.mergeLane())
            .bounds(this.x + 104, this.y + this.h - 30, 108, 20)
            .build()
      );
      this.expand = (Button)this.addRenderableWidget(
         Button.builder(Component.literal(this.point.mergeLength() < 0.0 ? "取消外侧扩流" : "外侧扩流加道"), b -> this.expandLane())
            .bounds(this.x + 216, this.y + this.h - 30, 108, 20)
            .build()
      );
      this.buttons();
      this.requestView();
   }

   private void buttons() {
      if (this.merge != null) {
         this.merge.active = this.point.mergeLength() >= 0.0
            && !this.point.automatic()
            && !this.pending
            && this.lane == this.point.lane()
            && this.payload.getBoolean("Supported")
            && (this.point.mergeLength() > 0.0 || LaneMerge.allowed(this.mesh, this.point));
      }

      if (this.expand != null) {
         this.expand.active = this.point.mergeLength() <= 0.0
            && !this.point.automatic()
            && !this.pending
            && this.lane == this.point.lane()
            && this.payload.getBoolean("Supported")
            && (this.point.mergeLength() < 0.0 || LaneExpansion.allowed(this.mesh, this.point));
      }

      this.apply.active = !this.point.automatic() && !this.pending && this.lane != this.point.lane();
      this.remove.active = !this.point.automatic() && !this.pending;
   }

   private void requestView() {
      if (this.drawing == null) {
         this.requestedLane = this.lane;
         int chosen = this.lane;
         int vw = this.viewW;
         int vh = this.viewH;
         this.drawing = CompletableFuture.supplyAsync(() -> LanePointPreview.render(this.mesh, this.point, vw, vh, chosen), PREVIEW);
      }
   }

   public void tick() {
      super.tick();
      if (this.payload.getBoolean("PlannerDelete") && this.remove != null) {
         this.payload.remove("PlannerDelete");
         if (this.remove.active) {
            this.remove.onPress();
         }
      }

      if (this.drawing != null && this.drawing.isDone()) {
         try {
            this.image = this.drawing.join();
         } catch (CompletionException var2) {
            this.status = "道路视图加载失败：" + var2.getCause().getMessage();
         }

         this.drawing = null;
         if (this.requestedLane != this.lane) {
            this.requestView();
         }
      }
   }

   public boolean mouseClicked(double mx, double my, int button) {
      if (button == 0
         && !this.point.automatic()
         && !this.pending
         && this.image != null
         && mx >= (double)this.viewX
         && my >= (double)this.viewY
         && mx < (double)(this.viewX + this.viewW)
         && my < (double)(this.viewY + this.viewH)) {
         int selected = this.image.laneAt((int)(mx - (double)this.viewX), (int)(my - (double)this.viewY));
         if (selected >= 0) {
            this.lane = selected;
            this.status = "";
            this.buttons();
            this.requestView();
         } else {
            this.status = "请点击机动车道；隔离带和人行道不能选作车道。";
         }

         return true;
      } else {
         return super.mouseClicked(mx, my, button);
      }
   }

   private void mergeLane() {
      if (!this.pending && !this.point.automatic()) {
         CompoundTag t = new CompoundTag();
         t.putUUID("Id", this.payload.getUUID("Id"));
         t.putUUID("Point", this.point.id());
         t.putInt("Signature", this.payload.getInt("Signature"));
         t.putString("Action", "lanePoint");
         t.putDouble("MergeLength", this.point.mergeLength() > 0.0 ? 0.0 : 32.0);
         this.pending = true;
         this.status = "正在提交合流缩减；不创建汇出匝道…";
         this.buttons();
         ClientRoads.sendAction(t);
      }
   }

   private void expandLane() {
      if (!this.pending && !this.point.automatic()) {
         CompoundTag t = new CompoundTag();
         t.putUUID("Id", this.payload.getUUID("Id"));
         t.putUUID("Point", this.point.id());
         t.putInt("Signature", this.payload.getInt("Signature"));
         t.putString("Action", "lanePoint");
         t.putDouble("MergeLength", this.point.mergeLength() < 0.0 ? 0.0 : -32.0);
         this.pending = true;
         this.status = "正在提交外侧扩流…";
         this.buttons();
         ClientRoads.sendAction(t);
      }
   }

   private void send(boolean delete) {
      if (!this.pending && !this.point.automatic() && (delete || this.lane != this.point.lane())) {
         CompoundTag t = new CompoundTag();
         t.putUUID("Id", this.payload.getUUID("Id"));
         t.putUUID("Point", this.payload.getUUID("Point"));
         t.putInt("Signature", this.payload.getInt("Signature"));
         t.putString("Action", "lanePoint");
         t.putBoolean("Delete", delete);
         t.putInt("Lane", this.lane);
         if (this.confirm) {
            t.put("ConfirmDependencies", this.payload.getList("Dependencies", 8).copy());
         }

         this.pending = true;
         this.status = "正在提交…";
         this.buttons();
         ClientRoads.sendAction(t);
      }
   }

   public void failed(String s) {
      this.status = s;
      this.pending = false;
      this.buttons();
   }

   public void onClose() {
      if (this.drawing != null) {
         this.drawing.cancel(false);
      }

      super.onClose();
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(this.x, this.y, this.x + this.w, this.y + this.h, -267050450);
      g.drawString(this.font, "蓝色车道点 · " + (this.point.automatic() ? "自动尽头" : "手动创建"), this.x + 12, this.y + 14, 6731263, false);
      g.fill(this.viewX - 1, this.viewY - 1, this.viewX + this.viewW + 1, this.viewY + this.viewH + 1, -10192249);
      g.fill(this.viewX, this.viewY, this.viewX + this.viewW, this.viewY + this.viewH, -14141394);
      if (this.image != null) {
         for (int row = 0; row < this.viewH; row++) {
            int col = 0;

            while (col < this.viewW) {
               int start = col;
               int color = this.image.pixels()[row * this.viewW + col];

               while (col < this.viewW && this.image.pixels()[row * this.viewW + col] == color) {
                  col++;
               }

               g.fill(this.viewX + start, this.viewY + row, this.viewX + col, this.viewY + row + 1, color);
            }
         }

         for (LanePointPreview.Marker marker : this.image.markers()) {
            if (marker.lane() == this.lane) {
               int px = this.viewX + marker.x();
               int py = this.viewY + marker.y();
               g.fill(px - 5, py - 5, px + 6, py + 6, -4397057);
               g.fill(px - 3, py - 3, px + 4, py + 4, -14449409);
            }
         }

         int hover = this.image.laneAt(mx - this.viewX, my - this.viewY);
         String label = hover >= 0 ? LanePoints.label(this.mesh, LanePoints.lane(this.mesh, this.point).station(), hover) : "道路实景俯视 · 沿路方向 ↑";
         g.fill(this.viewX + 3, this.viewY + 3, this.viewX + 9 + this.font.width(label), this.viewY + 16, -585817554);
         g.drawString(this.font, label, this.viewX + 6, this.viewY + 6, 15201017, false);
      } else {
         g.drawCenteredString(this.font, "正在加载道路视图…", this.viewX + this.viewW / 2, this.viewY + this.viewH / 2, 13359589);
      }

      int infoY = this.viewY + this.viewH + 7;
      String selected = "所属："
         + LanePoints.label(this.mesh, LanePoints.lane(this.mesh, this.point).station(), this.lane)
         + (LaneSections.active(this.mesh, LanePoints.lane(this.mesh, this.point).station(), this.lane) ? "" : "（分离空位）")
         + "   匝道引用："
         + this.payload.getList("Dependencies", 8).size()
         + " 条";
      g.drawString(this.font, selected, this.x + 12, infoY, 10079487, false);
      String hint = !this.status.isEmpty()
         ? this.status
         : (this.point.automatic() ? "自动尽头点只能查看，不能换车道或删除。" : (this.payload.getBoolean("Supported") ? "点击车道选择；灰色预留槽可用作补入目标，不可从空位汇出。" : "可选择车道；此类道路不支持匝道连接器。"));
      g.drawWordWrap(this.font, Component.literal(hint), this.x + 12, infoY + 14, this.w - 24, 16764812);
      super.render(g, mx, my, dt);
   }
}
