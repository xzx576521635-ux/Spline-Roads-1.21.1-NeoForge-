package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.TollPlanner;
import com.sora.splineroads.core.TollPlaza;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class TollPreview {
   private static List<TollPreview.Mark> marks = List.of();
   static TollPlanner.Plan namedPreview;

   public static void clear() {
      marks = List.of();
      namedPreview = null;
   }

   public static void show(TollPlaza.Spec original, TollPlanner.Plan plan) {
      namedPreview = plan;
      ArrayList<TollPreview.Mark> out = new ArrayList<>();
      TollPlaza.Spec proposed = plan.spec();

      for (boolean a : new boolean[]{true, false}) {
         String name = a ? "A" : "B";
         RoadGeometry.V old = (a ? original.a() : original.b()).position();
         RoadGeometry.V now = (a ? proposed.a() : proposed.b()).position();
         double distance = old.distance(now);
         if (distance > 0.001) {
            out.add(new TollPreview.Mark(old.add(new RoadGeometry.V(0.0, 3.0, 0.0)), name + " 原端点 " + coordinates(old), 16765358));
         }

         out.add(
            new TollPreview.Mark(
               now.add(new RoadGeometry.V(0.0, 3.0, 0.0)),
               name + (distance > 0.001 ? " 新端点 " : " ") + coordinates(now) + String.format(Locale.ROOT, " · 移动 %.1f 格", distance),
               11075538
            )
         );
      }

      for (TollPlanner.Unit unit : plan.units()) {
         out.add(
            new TollPreview.Mark(
               unit.center().add(new RoadGeometry.V(0.0, plan.spec().config().clearHeight() + 2.2, 0.0)),
               (unit.direction() > 0 ? "A→B" : "B→A") + " " + (unit.etc() ? "ETC" : "人工") + " " + (unit.index() + 1),
               unit.etc() ? 11075538 : 11721983
            )
         );
      }

      marks = List.copyOf(out);
   }

   private static String coordinates(RoadGeometry.V p) {
      return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", p.x(), p.y(), p.z());
   }

   public static void render(RenderLevelStageEvent event) {
      if (ClientRoads.preview != null && ClientRoads.preview.settings().style() == RoadGeometry.Style.TOLL) {
         Minecraft mc = Minecraft.getInstance();
         Vec3 camera = event.getCamera().getPosition();
         PoseStack pose = event.getPoseStack();
         BufferSource buffers = mc.renderBuffers().bufferSource();

         for (TollPreview.Mark mark : marks) {
            RoadGeometry.V p = mark.at();
            if (!(p.distance(new RoadGeometry.V(camera.x, camera.y, camera.z)) > 192.0)) {
               pose.pushPose();
               pose.translate(p.x() - camera.x, p.y() - camera.y, p.z() - camera.z);
               pose.mulPose(event.getCamera().rotation());
               pose.scale(-0.04F, -0.04F, 0.04F);
               mc.font
                  .drawInBatch(
                     mark.text(),
                     (float)(-mc.font.width(mark.text())) / 2.0F,
                     0.0F,
                     mark.color(),
                     false,
                     pose.last().pose(),
                     buffers,
                     DisplayMode.SEE_THROUGH,
                     1610612736,
                     15728880
                  );
               pose.popPose();
            }
         }
      }
   }

   private TollPreview() {
   }

   private static record Mark(RoadGeometry.V at, String text, int color) {
   }
}
