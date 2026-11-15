package com.sora.splineroads.world;

import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadGeometry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

final class RoadPointIndex {
   private final Map<Long, Set<RoadPointIndex.Marker>> chunks = new HashMap<>();
   private final Map<UUID, List<RoadPointIndex.Marker>> markers = new HashMap<>();
   private final Map<UUID, LanePoints.Link> links = new HashMap<>();
   private final Map<LanePoints.Ref, Set<UUID>> references = new HashMap<>();
   private final Map<UUID, Long> order = new HashMap<>();
   private long nextOrder;

   static AABB bounds(LanePoints.Point point) {
      RoadGeometry.V p = point.position();
      return new AABB(p.x() - 0.26, p.y() + 0.03, p.z() - 0.26, p.x() + 0.26, p.y() + 0.55, p.z() + 0.26);
   }

   private static Set<Long> chunks(LanePoints.Point point) {
      AABB box = bounds(point);
      HashSet<Long> out = new HashSet<>();
      int x0 = (int)Math.floor(box.minX / 16.0);
      int x1 = (int)Math.floor(box.maxX / 16.0);
      int z0 = (int)Math.floor(box.minZ / 16.0);
      int z1 = (int)Math.floor(box.maxZ / 16.0);
      out.add(ChunkPos.asLong(x0, z0));
      out.add(ChunkPos.asLong(x0, z1));
      out.add(ChunkPos.asLong(x1, z0));
      out.add(ChunkPos.asLong(x1, z1));
      return out;
   }

   void put(RoadRecord road) {
      this.removeContents(road.id());
      this.order.computeIfAbsent(road.id(), ignored -> this.nextOrder++);
      if (!road.railway()) {
         ArrayList<RoadPointIndex.Marker> points = new ArrayList<>();
         int ordinal = 0;

         for (LanePoints.Point point : LaneTopology.visiblePoints(road)) {
            RoadPointIndex.Marker marker = new RoadPointIndex.Marker(road.id(), point, ordinal++);
            points.add(marker);

            for (long chunk : chunks(point)) {
               this.chunks.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(marker);
            }
         }

         if (!points.isEmpty()) {
            this.markers.put(road.id(), List.copyOf(points));
         }

         LanePoints.Link link = LaneTopology.metadata(road).link();
         if (link != null) {
            this.links.put(road.id(), link);
            this.references.computeIfAbsent(link.from(), ignored -> new HashSet<>()).add(road.id());
            this.references.computeIfAbsent(link.to(), ignored -> new HashSet<>()).add(road.id());
         }
      }
   }

   void remove(UUID road) {
      this.removeContents(road);
      this.order.remove(road);
   }

   private void removeContents(UUID road) {
      for (RoadPointIndex.Marker marker : this.markers.getOrDefault(road, List.of())) {
         for (long chunk : chunks(marker.point())) {
            Set<RoadPointIndex.Marker> values = this.chunks.get(chunk);
            if (values != null) {
               values.remove(marker);
               if (values.isEmpty()) {
                  this.chunks.remove(chunk);
               }
            }
         }
      }

      this.markers.remove(road);
      LanePoints.Link link = this.links.remove(road);
      if (link != null) {
         for (LanePoints.Ref ref : List.of(link.from(), link.to())) {
            Set<UUID> values = this.references.get(ref);
            if (values != null) {
               values.remove(road);
               if (values.isEmpty()) {
                  this.references.remove(ref);
               }
            }
         }
      }
   }

   List<RoadPointIndex.Marker> candidates(RoadGeometry.V origin, RoadGeometry.V direction, double range) {
      HashSet<RoadPointIndex.Marker> found = new HashSet<>();

      for (long chunk : RoadIndex.rayChunks(origin, direction, range)) {
         found.addAll(this.chunks.getOrDefault(chunk, Set.of()));
      }

      ArrayList<RoadPointIndex.Marker> result = new ArrayList<>(found);
      result.sort(Comparator.<RoadPointIndex.Marker>comparingLong(p -> this.order.get(p.road())).thenComparingInt(RoadPointIndex.Marker::ordinal));
      return result;
   }

   List<UUID> references(LanePoints.Ref ref) {
      return this.references.getOrDefault(ref, Set.of()).stream().sorted().toList();
   }

   boolean referenced(LanePoints.Ref ref) {
      return !this.references.getOrDefault(ref, Set.of()).isEmpty();
   }

   void restore(Collection<RoadIndex.Built> roads) {
      this.clear();

      for (RoadIndex.Built road : roads) {
         this.put(road.record);
      }
   }

   void clear() {
      this.chunks.clear();
      this.markers.clear();
      this.links.clear();
      this.references.clear();
      this.order.clear();
      this.nextOrder = 0L;
   }

   static record Marker(UUID road, LanePoints.Point point, int ordinal) {
   }
}
