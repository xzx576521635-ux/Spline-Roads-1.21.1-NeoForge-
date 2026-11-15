package com.sora.splineroads.client;

import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.RoadSlicing;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RoadSplitScreen extends Screen {
   public RoadSplitScreen() {
      super(Component.literal("道路分割器"));
   }

   protected void init() {
      int x = (this.width - 220) / 2;
      int y = (this.height - 94) / 2;

      for (int i = 0; i < 2; i++) {
         RoadSlicing.Connection mode = RoadSlicing.Connection.values()[i];
         this.addRenderableWidget(
            Button.builder(Component.literal((RoadClientConfig.SPLIT_CONNECTION.get() == mode ? "✓ " : "") + (i == 0 ? "连续" : "路口")), button -> {
               RoadClientConfig.SPLIT_CONNECTION.set(mode);
               RoadClientConfig.SPEC.save();
               RoadSplitterClient.clear();
               this.onClose();
            }).bounds(x + 10 + i * 104, y + 40, 96, 20).build()
         );
      }
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      int x = (this.width - 220) / 2;
      int y = (this.height - 94) / 2;
      g.fill(x, y, x + 220, y + 94, -267247316);
      g.drawCenteredString(this.font, this.title, this.width / 2, y + 13, 16777215);
      g.drawCenteredString(this.font, "瞄准道路预览 · 右键分割 · Shift取消", this.width / 2, y + 73, 12243161);
      super.render(g, mx, my, dt);
   }

   public boolean isPauseScreen() {
      return false;
   }
}
