package com.sora.splineroads.world;

import com.sora.splineroads.core.CorridorPlanner;
import com.sora.splineroads.core.CurvedRoadPlans;
import com.sora.splineroads.core.InterchangePlanner;
import com.sora.splineroads.core.RoadAxis;
import com.sora.splineroads.core.RoadGeometry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class HostAxes {
   public static final String RETIRED_MESSAGE = "请选择两条直线主路";

   public static RoadGeometry.Node[] nodes(CompoundTag t) {
      return t.getList("Nodes", 10).stream().map(v -> RoadRecord.readNode((CompoundTag)v)).toArray(RoadGeometry.Node[]::new);
   }

   public static List<RoadAxis> read(CompoundTag t) {
      ArrayList<RoadAxis> out = new ArrayList<>();

      for (Tag value : t.getList("HostAxes", 10)) {
         ArrayList<RoadGeometry.V> points = new ArrayList<>();

         for (Tag p : ((CompoundTag)value).getList("Points", 10)) {
            points.add(RoadRecord.readNode((CompoundTag)p).position());
         }

         out.add(new RoadAxis(points));
      }

      if (!out.isEmpty()) {
         return List.copyOf(out);
      } else {
         RoadGeometry.Node[] n = nodes(t);

         for (int i = 0; i + 1 < n.length; i += 2) {
            out.add(RoadAxis.straight(n[i].position(), n[i + 1].position()));
         }

         if (n.length == 3) {
            RoadGeometry.V a = n[0].position();
            RoadGeometry.V u = n[1].position().sub(a).horizontalUnit();
            RoadGeometry.V c = n[2].position();
            RoadGeometry.V foot = a.add(u.mul(c.sub(a).dot(u)));
            out.add(RoadAxis.straight(c, new RoadGeometry.V(foot.x(), c.y(), foot.z())));
         }

         return List.copyOf(out);
      }
   }

   public static void write(CompoundTag t, List<RoadAxis> axes) {
      ListTag list = new ListTag();

      for (RoadAxis axis : axes) {
         CompoundTag a = new CompoundTag();
         ListTag points = new ListTag();

         for (RoadGeometry.V p : axis.points()) {
            CompoundTag q = new CompoundTag();
            q.putDouble("X", p.x());
            q.putDouble("Y", p.y());
            q.putDouble("Z", p.z());
            points.add(q);
         }

         a.put("Points", points);
         list.add(a);
      }

      t.put("HostAxes", list);
   }

   static RoadAxis chain(List<RoadRecord> chain, BlockPos start) {
      ArrayList<RoadGeometry.V> points = new ArrayList<>();
      BlockPos at = start;

      for (RoadRecord road : chain) {
         boolean forward = road.a().equals(at);
         List<RoadGeometry.Sample> samples = road.mesh().samples();

         for (int j = 0; j < samples.size(); j++) {
            RoadGeometry.V p = samples.get(forward ? j : samples.size() - 1 - j).center();
            if (points.isEmpty() || p.distance(points.get(points.size() - 1)) > 1.0E-5) {
               points.add(p);
            }
         }

         at = forward ? road.b() : road.a();
      }

      return new RoadAxis(points);
   }

   public static int signature(CompoundTag t) {
      return t.getList("HostAxes", 10).hashCode();
   }

   public static boolean curved(CompoundTag t) {
      return read(t).stream().anyMatch(RoadAxis::curved);
   }

   public static boolean retiredInterchange(CompoundTag t) {
      return !t.contains("Corridor") && curved(t);
   }

   public static List<InterchangePlanner.Preset> presets(CompoundTag t) {
      return ParallelSpui.present(t) ? List.of(InterchangePlanner.Preset.SPUI) : CurvedRoadPlans.presets(t.getLongArray("Points").length, curved(t));
   }

   public static boolean fixedFrontage(CompoundTag t) {
      return t.contains("Corridor") && CurvedRoadPlans.fixedFrontageHeight(nodes(t), Corridors.read(t.getCompound("Corridor")), read(t).get(0));
   }

   public static void normalizeCorridor(CompoundTag t) {
      if (fixedFrontage(t)) {
         CorridorPlanner.Config c = Corridors.read(t.getCompound("Corridor"));
         t.put(
            "Corridor",
            Corridors.write(new CorridorPlanner.Config(c.kind(), c.sides(), CorridorPlanner.Access.NONE, c.gap(), nodes(t)[0].position().y(), c.adjustment()))
         );
      }
   }

   static void fit(CompoundTag t, List<RoadGeometry.Node> target) {
      if (target.size() <= 4) {
         List<RoadAxis> axes = read(t);
         ArrayList<RoadAxis> fitted = new ArrayList<>();

         for (int i = 0; i < axes.size(); i++) {
            RoadAxis axis = axes.get(i);
            double start = axis.project(target.get(i * 2).position());
            double end = i * 2 + 1 < target.size() ? axis.project(target.get(i * 2 + 1).position()) : axis.length();
            fitted.add(axis.slice(start, end, target.get(i * 2).position().y() - axis.at(start).y()));
         }

         write(t, fitted);
      }
   }

   private HostAxes() {
   }
}
