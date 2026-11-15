package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.StreetCurves;
import com.sora.splineroads.core.StreetPlan;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

public final class StreetNetworkGeometry {
   public static List<RoadRecord> streets(StreetPlan.Plan plan, UUID task, UUID owner) {
      if (!plan.buildable()) {
         throw new IllegalArgumentException("请先解决路网预览中的冲突");
      } else {
         ArrayList<RoadRecord> roads = new ArrayList<>();
         StreetPlan.Options options = plan.input().options();

         for (StreetPlan.Edge edge : plan.edges()) {
            StreetPlan.Vertex a = plan.vertices().get(edge.a());
            StreetPlan.Vertex b = plan.vertices().get(edge.b());
            RoadGeometry.V av = new RoadGeometry.V(a.point().x(), a.height(), a.point().z());
            RoadGeometry.V bv = new RoadGeometry.V(b.point().x(), b.height(), b.point().z());
            double yaw = RoadPlanner.yaw(bv.sub(av));
            RoadGeometry.Settings settings = new RoadGeometry.Settings(
                  edge.bend() == 0.0 ? RoadGeometry.Mode.STRAIGHT : RoadGeometry.Mode.CURVE,
                  options.section(edge.rank()),
                  options.width(edge.rank()),
                  1.0,
                  0.4,
                  90.0
               )
               .structure(RoadGeometry.Structure.GROUND);
            settings = settings.options(
               settings.options()
                  .traffic(options.left())
                  .streetscape(settings.options().streetscape().lamps(options.lighting()))
                  .sidewalk(RoadSidewalks.Config.DEFAULT.width(2).enabled(options.sidewalks()))
            );
            UUID id = UUID.nameUUIDFromBytes((task + ":street:" + edge.a() + ":" + edge.b()).getBytes(StandardCharsets.UTF_8));
            RoadRecord road = new RoadRecord(
               id,
               owner,
               BlockPos.containing(av.x(), av.y(), av.z()),
               BlockPos.containing(bv.x(), bv.y(), bv.z()),
               new RoadGeometry.Node(av, yaw, 0.0),
               new RoadGeometry.Node(bv, yaw, 0.0),
               settings,
               false,
               4
            );
            if (edge.bend() != 0.0) {
               ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
               int steps = Math.max(2, (int)Math.ceil(a.point().distance(b.point()) / 0.5));

               for (int i = 0; i <= steps; i++) {
                  double t = (double)i / (double)steps;
                  StreetPlan.Point p = StreetCurves.point(plan, edge, t);
                  double y = a.height() + (b.height() - a.height()) * t * t * (3.0 - 2.0 * t);
                  samples.add(
                     new RoadGeometry.Sample(
                        new RoadGeometry.V(p.x(), y, p.z()), StreetCurves.direction(a.point(), b.point(), edge.bend(), t).left(), 0.0, settings.width() / 2.0
                     )
                  );
               }

               road = road.alignment(null, RoadRibbon.mesh(samples, settings));
            }

            roads.add(road);
         }

         return List.copyOf(roads);
      }
   }

   public static AutoJunctions.Draft plan(StreetPlan.Plan plan, UUID task, UUID owner) {
      List<RoadRecord> streets = streets(plan, task, owner);
      LinkedHashMap<BlockPos, List<RoadRecord>> ends = new LinkedHashMap<>();

      for (RoadRecord r : streets) {
         for (BlockPos p : List.of(r.a(), r.b())) {
            ends.computeIfAbsent(p, k -> new ArrayList<>()).add(r);
         }
      }

      ArrayList<CompoundTag> configs = new ArrayList<>();

      for (Entry<BlockPos, List<RoadRecord>> entry : ends.entrySet()) {
         List<RoadRecord> linked = entry.getValue();
         if (linked.size() > 4) {
            throw new IllegalArgumentException("首版只支持三向或四向平交");
         }

         boolean junction = linked.size() >= 3;
         if (linked.size() == 2) {
            RoadGeometry.V a = away(linked.get(0), entry.getKey());
            RoadGeometry.V b = away(linked.get(1), entry.getKey());
            junction = a.dot(b) > -0.999;
         }

         if (junction) {
            CompoundTag config = AutoJunctions.config(entry.getKey(), owner);
            config.putBoolean("LeftTraffic", plan.input().options().left());
            configs.add(config);
         }
      }

      AutoJunctions.Draft draft = AutoJunctions.plan(streets, configs);
      checkGrades(draft.roads());
      return draft;
   }

   static void checkGrades(List<RoadRecord> roads) {
      for (RoadRecord road : roads) {
         List<RoadGeometry.Sample> samples = road.mesh().samples();

         for (int i = 1; i < samples.size(); i++) {
            RoadGeometry.V delta = samples.get(i).center().sub(samples.get(i - 1).center());
            if (Math.abs(delta.y()) > Math.max(1.0E-4, delta.horizontalLength() * 0.08001)) {
               throw new IllegalArgumentException("原生路口引道或转弯纵坡超过缓坡上限，请降低高差或扩大街坊");
            }
         }
      }
   }

   private static RoadGeometry.V away(RoadRecord r, BlockPos p) {
      RoadGeometry.V d = r.end().position().sub(r.start().position());
      return d.mul((double)(r.a().equals(p) ? 1 : -1) / d.horizontalLength());
   }

   static List<RoadRecord> connectedOrder(List<RoadRecord> roads) {
      return connectedOrder(roads, List.of());
   }

   static List<RoadRecord> prunePendingSpurs(List<RoadRecord> roads, int completed) {
      ArrayList<RoadRecord> result = new ArrayList<>(roads);
      HashSet<UUID> protectedIds = new HashSet<>();

      for (int i = 0; i < completed; i++) {
         protectedIds.add(roads.get(i).id());
      }

      boolean changed;
      label79:
      do {
         changed = false;
         HashMap<BlockPos, Integer> degree = new HashMap<>();

         for (RoadRecord r : result) {
            degree.merge(r.a(), 1, Integer::sum);
            degree.merge(r.b(), 1, Integer::sum);
         }

         for (RoadRecord r : result) {
            if (!protectedIds.contains(r.id())) {
               for (boolean first : new boolean[]{true, false}) {
                  BlockPos pos = first ? r.a() : r.b();
                  if (degree.get(pos) == 1) {
                     RoadGeometry.V end = (first ? r.start() : r.end()).position();

                     for (RoadRecord other : result) {
                        if (!r.id().equals(other.id())
                           && !r.a().equals(other.a())
                           && !r.a().equals(other.b())
                           && !r.b().equals(other.a())
                           && !r.b().equals(other.b())) {
                           RoadQueries.Projection near = RoadQueries.horizontal(other.mesh(), end);
                           double gap = (r.settings().width() + other.settings().width()) / 2.0 + 1.0;
                           if (end.sub(near.sample().center()).horizontalLength() < gap && Math.abs(end.y() - near.sample().center().y()) < 1.0) {
                              result.remove(r);
                              changed = true;
                              continue label79;
                           }
                        }
                     }
                  }
               }
            }
         }
      } while (changed);

      return List.copyOf(result);
   }

   static List<RoadRecord> connectedOrder(List<RoadRecord> roads, List<BlockPos> anchors) {
      ArrayList<RoadRecord> remaining = new ArrayList<>(roads);
      remaining.sort(
         Comparator.<RoadRecord>comparingDouble(rx -> Math.min(rx.start().position().x(), rx.end().position().x()))
            .thenComparingDouble(rx -> Math.min(rx.start().position().z(), rx.end().position().z()))
            .thenComparingDouble(rx -> Math.max(rx.start().position().x(), rx.end().position().x()))
            .thenComparingDouble(rx -> Math.max(rx.start().position().z(), rx.end().position().z()))
      );
      ArrayList<RoadRecord> output = new ArrayList<>();
      HashSet<BlockPos> reached = new HashSet<>();

      for (BlockPos anchor : anchors) {
         if (remaining.stream().anyMatch(rx -> rx.a().equals(anchor) || rx.b().equals(anchor))) {
            reached.add(anchor);
            break;
         }
      }

      while (!remaining.isEmpty()) {
         int at = 0;
         if (!reached.isEmpty()) {
            at = -1;

            for (int i = 0; i < remaining.size(); i++) {
               if (reached.contains(remaining.get(i).a()) || reached.contains(remaining.get(i).b())) {
                  at = i;
                  break;
               }
            }

            if (at < 0) {
               throw new IllegalArgumentException("规划含有未连接片区，请重新规划");
            }
         }

         RoadRecord r = remaining.remove(at);
         output.add(r);
         reached.add(r.a());
         reached.add(r.b());
      }

      return List.copyOf(output);
   }

   private StreetNetworkGeometry() {
   }
}
