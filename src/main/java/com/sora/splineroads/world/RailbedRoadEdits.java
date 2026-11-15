package com.sora.splineroads.world;

import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RailbedStructures;
import com.sora.splineroads.core.RoadFurniture;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRaster;
import com.sora.splineroads.core.RoadStructures;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class RailbedRoadEdits {
   static boolean support(RoadStructures.Part p) {
      return p.material() == RoadStructures.Material.CONCRETE && p.model().isEmpty();
   }

   static Set<Long> cells(RoadRecord road) {
      HashSet<Long> result = new HashSet<>();

      for (RoadRaster.Cell c : RoadRaster.structures(road.structures().stream().filter(RailbedRoadEdits::support).toList(), null).keySet()) {
         result.add(BlockPos.asLong(c.x(), c.y(), c.z()));
      }

      return result;
   }

   static void enlist(RoadData data, ServerLevel level, List<RoadIndex.Built> built, Set<UUID> removed, List<RoadIndex.Built> requested) {
      ArrayList<RoadIndex.Built> context = new ArrayList<>();

      for (RoadIndex.Built old : data.index.roads.values()) {
         if (!removed.contains(old.record.id())) {
            context.add(old);
         }
      }

      context.addAll(built);

      for (RailbedStore.Entry entry : data.railbeds.entries.values()) {
         if (entry.road.legacyRailway()) {
            RoadGeometry.Mesh mesh = entry.road.mesh();
            if (entry.mode != RoadGeometry.Structure.TUNNEL && !requested.stream().noneMatch(r -> affected(mesh, r.mesh))) {
               if (data.railbeds.pending.containsKey(entry.road.id())) {
                  throw new IllegalArgumentException("相关铁路路基仍在施工，请等待完成");
               }

               List<RoadIndex.Built> others = context.stream()
                  .filter(r -> !r.record.id().equals(entry.road.id()) && RoadIndex.overlapXZ(mesh, r.mesh, 16.0))
                  .toList();
               List<RailbedPath.Path> rails = data.railbeds
                  .entries
                  .values()
                  .stream()
                  .filter(e -> RoadIndex.overlapXZ(mesh, e.road.mesh(), 16.0))
                  .flatMap(e -> e.paths.stream())
                  .toList();
               RailbedStructures.Ground ground = RailbedBuildQueue.ground(level, data, entry.original, others, rails);
               ArrayList<RoadStructures.Part> parts = new ArrayList<>(entry.road.structures().stream().filter(p -> !support(p)).toList());
               RoadGeometry.Mesh supports = new RoadGeometry.Mesh(
                  mesh.samples(), mesh.settings().structure(entry.mode), mesh.min(), mesh.max(), mesh.length(), mesh.closed()
               );
               parts.addAll(
                  RoadStructures.supports(supports, ground, entry.road.furniturePhase() == null ? RoadFurniture.Phase.DEFAULT : entry.road.furniturePhase())
               );
               if (!parts.equals(entry.road.structures())) {
                  RoadIndex.Built next = RoadIndex.Built.loading(entry.road.structures(parts));
                  built.add(next);
                  removed.add(entry.road.id());
                  context.removeIf(r -> r.record.id().equals(entry.road.id()));
                  context.add(next);
               }
            }
         }
      }
   }

   private static boolean affected(RoadGeometry.Mesh rail, RoadGeometry.Mesh road) {
      if (!RoadIndex.overlapXZ(rail, road, 16.0)) {
         return false;
      } else {
         for (RoadGeometry.Sample at : rail.samples()) {
            RoadQueries.Projection q = RoadQueries.horizontal(road, at.center());
            double drop = at.center().y() - q.sample().center().y();
            if (drop > 3.0 && drop < 96.0 && q.horizontalDistance() < at.halfWidth() + q.sample().halfWidth() + 16.0) {
               return true;
            }
         }

         return false;
      }
   }

   static void sync(RoadData data, ServerLevel level, List<RoadIndex.Built> built) {
      for (RoadIndex.Built road : built) {
         if (road.record.legacyRailway()) {
            RailbedStore.Entry old = data.railbeds.entries.get(road.record.id());
            RailbedStore.Entry next = new RailbedStore.Entry(road.record, old.paths, old.width, old.mode, old.options);
            next.original.putAll(old.original);
            next.written.putAll(old.written);
            Set<Long> touched = cells(old.road);
            touched.addAll(cells(road.record));

            for (long key : touched) {
               BlockPos pos = BlockPos.of(key);
               if (!road.boxes(pos).isEmpty()) {
                  next.original.putIfAbsent(key, data.railbedOriginal(key));
                  next.written.put(key, level.getBlockState(pos));
                  if (data.index.at(key).stream().noneMatch(id -> !data.index.roads.get(id).record.legacyRailway())) {
                     data.railbedOwns(key);
                  }
               } else if (!road.clearanceAt(pos)) {
                  next.original.remove(key);
                  next.written.remove(key);
               }
            }

            data.railbeds.entries.put(road.record.id(), next);
         }
      }
   }

   private RailbedRoadEdits() {
   }
}
