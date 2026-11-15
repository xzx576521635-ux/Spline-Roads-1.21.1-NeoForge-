package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadLaneLines;
import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class AttachedPointCodec {
   private static CompoundTag v(RoadGeometry.V p) {
      return RoadRecord.writeNode(new RoadGeometry.Node(p, 0.0, 0.0));
   }

   private static RoadGeometry.V v(CompoundTag t) {
      return RoadRecord.readNode(t).position();
   }

   public static CompoundTag write(RoadAttachments.Data data) {
      CompoundTag t = new CompoundTag();
      t.putInt("ControlVersion", 2);
      ListTag points = new ListTag();
      ListTag spans = new ListTag();

      for (RoadAttachments.Point p : data.points()) {
         CompoundTag q = new CompoundTag();
         q.putUUID("Id", p.id());
         q.put("Position", v(p.position()));
         q.put("Anchor", v(p.anchor()));
         q.put("Origin", v(p.origin()));
         q.putBoolean("Controlled", p.controlled());
         if (p.elevated()) {
            q.putBoolean("Elevated", true);
         }

         points.add(q);
      }

      for (RoadAttachments.Span s : data.spans()) {
         CompoundTag q = new CompoundTag();
         q.put("Start", v(s.start()));
         q.put("End", v(s.end()));
         q.putBoolean("HideArrows", s.paint().hideArrows());
         ListTag lines = new ListTag();

         for (RoadLaneLines.Edit e : s.paint().lines()) {
            CompoundTag l = new CompoundTag();
            l.putString("Key", e.key());
            l.putString("Pattern", e.pattern().name());
            l.putDouble("Width", e.width());
            lines.add(l);
         }

         q.put("Lines", lines);
         spans.add(q);
      }

      t.put("Points", points);
      t.put("Spans", spans);
      return t;
   }

   public static RoadAttachments.Data read(CompoundTag t) {
      ArrayList<RoadAttachments.Point> points = new ArrayList<>();
      ArrayList<RoadAttachments.Span> spans = new ArrayList<>();

      for (Tag tag : t.getList("Points", 10)) {
         CompoundTag p = (CompoundTag)tag;
         points.add(
            new RoadAttachments.Point(
               p.getUUID("Id"),
               v(p.getCompound("Position")),
               v(p.getCompound("Anchor")),
               p.getBoolean("Controlled"),
               p.contains("Origin") ? v(p.getCompound("Origin")) : v(p.getCompound("Anchor")),
               p.getBoolean("Elevated")
            )
         );
      }

      for (Tag tag : t.getList("Spans", 10)) {
         CompoundTag s = (CompoundTag)tag;
         ArrayList<RoadLaneLines.Edit> lines = new ArrayList<>();

         for (Tag e : s.getList("Lines", 10)) {
            CompoundTag q = (CompoundTag)e;
            lines.add(new RoadLaneLines.Edit(q.getString("Key"), RoadLaneLines.Pattern.valueOf(q.getString("Pattern")), q.getDouble("Width")));
         }

         spans.add(new RoadAttachments.Span(v(s.getCompound("Start")), v(s.getCompound("End")), new RoadAttachments.Paint(lines, s.getBoolean("HideArrows"))));
      }

      if (t.getInt("ControlVersion") < 2 && points.stream().anyMatch(RoadAttachments.Point::controlled)) {
         points.replaceAll(p -> p.controlled() ? p : new RoadAttachments.Point(p.id(), p.position(), p.anchor(), true, p.origin(), p.elevated()));
      }

      return new RoadAttachments.Data(points, spans);
   }

   private AttachedPointCodec() {
   }
}
