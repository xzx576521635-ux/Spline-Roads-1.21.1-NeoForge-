package com.sora.splineroads.client;

import com.sora.splineroads.world.RoadRecord;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;

public final class RoadDeleteScreen extends Screen {
   private final CompoundTag payload;
   private Button remove;
   private String status = "删除所选整段道路、护栏和桥墩；保留端点";

   public RoadDeleteScreen(CompoundTag t) {
      super(Component.literal("删除道路"));
      this.payload = t.copy();
      if (t.getBoolean("ElevatedDelete")) {
         this.status = "删除这处高架连接并恢复主路车道和辅路；保留原高架尽头及出入点";
      }

      if (t.getBoolean("RampDelete")) {
         this.status = "删除所选匝道，并恢复主路车道及附属设施；保留主路";
      }

      ListTag deps = t.getList("Dependencies", 8);
      if (!deps.isEmpty()) {
         this.status = "级联删除以下依赖匝道：" + IntStream.range(0, deps.size()).mapToObj(i -> deps.getString(i).substring(0, 8)).collect(Collectors.joining(", "));
      }

      ClientRoads.preview = RoadRecord.load(t.getCompound("Road")).mesh();
      ClientRoads.nodePreviews = List.of();
   }

   protected void init() {
      int x = this.width / 2 - 150;
      int y = this.height / 2 - 38;
      this.remove = (Button)this.addRenderableWidget(
         Button.builder(
               Component.literal(this.payload.getBoolean("ElevatedDelete") ? "确认删除高架连接" : (this.payload.getBoolean("RampDelete") ? "确认删除所选匝道" : "确认删除所选道路")),
               b -> {
                  CompoundTag t = new CompoundTag();
                  t.putString("Action", this.payload.getBoolean("RampDelete") ? "laneRampDelete" : "delete");
                  t.putUUID("Id", this.payload.getUUID("Id"));
                  if (this.payload.getBoolean("RampDelete")) {
                     t.putInt("Signature", this.payload.getInt("Signature"));
                     t.put("From", this.payload.getCompound("From").copy());
                     t.put("To", this.payload.getCompound("To").copy());
                  }

                  t.put("ConfirmDependencies", this.payload.getList("Dependencies", 8).copy());
                  this.remove.active = false;
                  if (!this.payload.getList("Dependencies", 8).isEmpty()) {
                     this.minecraft.setScreen(new LaneDeleteConfirmScreen(this, this.payload.getList("Dependencies", 8), () -> ClientRoads.sendAction(t)));
                     this.remove.active = true;
                  } else {
                     ClientRoads.sendAction(t);
                  }
               }
            )
            .bounds(x + 12, y + 67, 160, 20)
            .build()
      );
      this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> this.onClose()).bounds(x + 180, y + 67, 108, 20).build());
   }

   public void failed(String message) {
      this.status = message;
      this.remove.active = true;
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void onClose() {
      ClientRoads.preview = null;
      ClientRoads.draft = null;
      super.onClose();
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int x, int y, float dt) {
      super.renderBackground(g, x, y, dt);
      int l = this.width / 2 - 150;
      int t = this.height / 2 - 38;
      g.fill(l, t, l + 300, t + 100, -300801748);
      g.drawString(
         this.font,
         this.payload.getBoolean("ElevatedDelete") ? "删除高架连接" : (this.payload.getBoolean("RampDelete") ? "删除匝道 · 主路保留" : "删除道路 · 红色高亮为所选路段"),
         l + 12,
         t + 12,
         -21351,
         false
      );
      g.drawWordWrap(this.font, Component.literal(this.status), l + 12, t + 32, 276, 16777215);
      super.render(g, x, y, dt);
   }
}
