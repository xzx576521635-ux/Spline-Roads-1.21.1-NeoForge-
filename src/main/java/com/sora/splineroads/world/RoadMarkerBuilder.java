package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadSurface;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Builds invisible {@link RoadMarker} blocks for Spline Roads geometry so that Xaero's map
 * and Distant Horizons (both block based) can show trees, lamps, rails, medians and piers.
 *
 * <p>Every marker that is actually placed is recorded in {@link RoadMarkerData} together
 * with the roads that own it. Deleting or editing a road then removes exactly those
 * recorded blocks instead of recomputing (and missing) older layouts. Markers found in the
 * world that were placed by older versions are adopted into the records when their chunk
 * is loaded; adoption only reads blocks and never deletes anything.</p>
 */
public final class RoadMarkerBuilder {
   private static final int PLACE_BUDGET_PER_TICK = 1500;
   private static final int REMOVE_BUDGET_PER_TICK = 512;
   private static final int LOAD_INTERVAL_TICKS = 10;
   private static final int MAX_MARKERS = 200000;
   private static final int KEEP_RADIUS = 3;

   private static ServerLevel level;
   private static RoadMarkerData records;
   private static long revision = Long.MIN_VALUE;
   private static final ArrayDeque<Placement> QUEUE = new ArrayDeque<>();
   private static final ArrayDeque<Removal> REMOVALS = new ArrayDeque<>();
   private static final Set<Long> queuedRemovals = new HashSet<>();
   private static final Set<Long> adoptedChunks = new HashSet<>();
   private static final ArrayDeque<Long> RECONCILE = new ArrayDeque<>();
   private static final Set<Long> reconcileQueued = new HashSet<>();
   private static final Map<UUID, RoadMarkerBuilder.ChunkLayout> CHUNK_LAYOUTS = new LinkedHashMap<>(64, 0.75F, true) {
   @Override
   protected boolean removeEldestEntry(Map.Entry<UUID, RoadMarkerBuilder.ChunkLayout> eldest) {
      return this.size() > 128;
   }
};
   private static boolean pendingRestored;
   private static int loadTick;
   private static int reconcileLoadTick;
   private static final Set<Long> cancelledRemovals = new HashSet<>();
   private static final Set<Long> nudgedChunks = new HashSet<>();
   private static final ArrayDeque<Nudge> NUDGES = new ArrayDeque<>();
   private static int reconcileCooldown;
   private static final ArrayDeque<UUID> REBUILD = new ArrayDeque<>();
   private static boolean coverageChecked;
   private static final org.apache.logging.log4j.Logger PERF = org.apache.logging.log4j.LogManager.getLogger("SplineRoads/performance");

   private RoadMarkerBuilder() {
   }

   public static void tick(ServerTickEvent.Post event) {
      MinecraftServer server = event.getServer();
      ServerLevel overworld = server.overworld();
      if (overworld != level) {
         level = overworld;
         records = RoadMarkerData.get(level);
         revision = Long.MIN_VALUE;
         QUEUE.clear();
         REMOVALS.clear();
         queuedRemovals.clear();
         adoptedChunks.clear();
         RECONCILE.clear();
         reconcileQueued.clear();
         cancelledRemovals.clear();
         nudgedChunks.clear();
         NUDGES.clear();
         reconcileCooldown = 0;
         CHUNK_LAYOUTS.clear();
         pendingRestored = false;
         REBUILD.clear();
         coverageChecked = false;
      }

      if (records == null && level != null) {
         records = RoadMarkerData.get(level);
      }

      RoadData data = RoadData.get(level);
      if (data == null) {
         return;
      }

      long current = data.index.contentRevision();
      if (current != revision && QUEUE.isEmpty()) {
         revision = current;
         CHUNK_LAYOUTS.clear();
         reconcileCooldown = 60;
         queueAll(data);
         reconcileRemovals(data);

         // Newly built roads often extend beyond the render distance: their chunks were
         // not loaded when the marker queue ran. Re-check the chunks that are loaded now
         // and let later chunk loads reconcile the rest.
         adoptedChunks.clear();

         for (RoadIndex.Built road : data.index.roads.values()) {
            if (!records.positions(road.record.id()).isEmpty()) {
               continue;
            }

            for (long chunk : road.chunks) {
               ChunkPos pos = new ChunkPos((int)chunk, (int)(chunk >> 32));
               if (level.getChunkSource().getChunk(pos.x, pos.z, ChunkStatus.FULL, false) != null) {
                  enqueueReconcile(chunk);
               }
            }
         }
      }

      if (!pendingRestored) {
         pendingRestored = true;
         loadTick = 0;
         PERF.info("SR markers: ledger roads={}, pendingRemovals={}", records.roads().size(), records.pendingCount());

         if (!records.backlogDropped()) {
            int dropped = records.dropPendingBacklog();
            REMOVALS.clear();
            queuedRemovals.clear();
            PERF.info(
               "SR markers: dropped legacy removal backlog ({} entries); orphan markers are now detected per chunk",
               dropped
            );
         }
      }

      // Feed the persisted removal backlog in bounded slices. It used to be copied with
      // Set.copyOf and turned into Removal objects in one tick, which froze world entry for
      // minutes when the backlog reached about a million positions.
      if (REMOVALS.size() < 2048) {
         for (long backlogPos : records.takePending(2048)) {
            BlockPos backlogBlock = BlockPos.of(backlogPos);

            if (!level.isLoaded(backlogBlock)) {
               // Chunk is not loaded: don't force synchronous chunk generation for backlog
               // cleanup. adoptChunk() removes the marker (if any) the next time the chunk
               // loads, so dropping the ledger entry here is safe.
               continue;
            }

            if (queuedRemovals.add(backlogPos)) {
               REMOVALS.add(new Removal(backlogBlock, null));
            }
         }
      }

      // One-time recovery: if the ledger only covers a minority of the roads (the old
      // removal backlog stripped most entries), schedule a background rebuild of all marker
      // layouts so the map gets its colours back.
      if (!coverageChecked && !data.index.roads.isEmpty()) {
         coverageChecked = true;
         int covered = 0;

         for (UUID id : data.index.roads.keySet()) {
            if (!records.positions(id).isEmpty()) {
               covered++;
            }
         }

         if (covered > 0 && covered * 2 < data.index.roads.size() && !records.rebuilt()) {
            PERF.info("SR markers: ledger covers {}/{} roads; scheduling rebuild", covered, data.index.roads.size());
            records.rebuilt(true);
            scheduleRebuild(data, "low ledger coverage");
         }
      }

      rebuildBudget(data);
      removeBudget();

      if (!QUEUE.isEmpty()) {
         placeBudget();
      }

      if (reconcileCooldown > 0) {
         reconcileCooldown--;
      } else {
         reconcileBudget();
      }

      nudgeBudget();
   }

   /** Queues a full marker layout rebuild for every road (background, budgeted). */
   public static int scheduleRebuild(RoadData data, String reason) {
      REBUILD.clear();

      for (UUID id : data.index.roads.keySet()) {
         REBUILD.add(id);
      }

      if (records != null) {
         records.rebuilt(true);
      }

      PERF.info("SR markers: rebuild scheduled ({}) roads={}", reason, REBUILD.size());
      return REBUILD.size();
   }

   private static void rebuildBudget(RoadData data) {
      if (REBUILD.isEmpty()) {
         return;
      }

      long deadline = System.nanoTime() + 5_000_000L;
      int roads = 0;

      while (!REBUILD.isEmpty() && roads < 16 && System.nanoTime() < deadline && QUEUE.size() < MAX_MARKERS) {
         UUID id = REBUILD.removeFirst();
         RoadIndex.Built road = data.index.roads.get(id);
         if (road == null) {
            continue;
         }

         roads++;

         // Only structures (trees, lamps, rails, medians, piers). Lane paint lives on the top
         // collider block state, so it needs no marker blocks; skipping the full road surface
         // build keeps the rebuild cheap (no multi-second layout per road).
         LinkedHashMap<Long, Placement> markers = new LinkedHashMap<>();

         try {
            queueRoad(markers, road);
         } catch (Throwable ignored) {
         }

         for (Placement placement : markers.values()) {
            if (QUEUE.size() >= MAX_MARKERS) {
               break;
            }

            QUEUE.addLast(placement);
         }
      }

      if (REBUILD.isEmpty()) {
         PERF.info("SR markers: rebuild finished queueing, placements pending={}", QUEUE.size());
      }
   }

   /**
    * Returns the road that owns a position when the marker sits next to its surface: the
    * carriageway, a sidewalk, or the furniture above it (trees and lamps up to ~16 blocks
    * high, ~8 blocks to the side). Used to keep markers that a partial ledger no longer lists.
    */
   private static UUID nearestRoad(BlockPos pos, Collection<RoadIndex.Built> roads) {
      double x = (double)pos.getX() + 0.5;
      double y = (double)pos.getY() + 0.5;
      double z = (double)pos.getZ() + 0.5;
      RoadGeometry.V point = new RoadGeometry.V(x, y, z);
      UUID best = null;
      double bestLateral = Double.MAX_VALUE;

      for (RoadIndex.Built road : roads) {
         if (x < road.mesh.min().x() - 10.0
            || x > road.mesh.max().x() + 10.0
            || z < road.mesh.min().z() - 10.0
            || z > road.mesh.max().z() + 10.0
            || y < road.mesh.min().y() - 16.0
            || y > road.mesh.max().y() + 16.0) {
            continue;
         }

         RoadQueries.Projection projection = RoadQueries.horizontal(road.mesh, point);
         if (projection == null) {
            continue;
         }

         double lateral = Math.abs(projection.lateral());
         if (lateral <= projection.sample().halfWidth() + 8.0
            && Math.abs(projection.sample().center().y() - y) <= 16.0
            && lateral < bestLateral) {
            bestLateral = lateral;
            best = road.record.id();
         }
      }

      return best;
   }

   private static void queueAll(RoadData data) {
      LinkedHashMap<Long, Placement> markers = new LinkedHashMap<>();

      for (RoadIndex.Built road : data.index.roads.values()) {
         // Roads with ledger positions already have their markers placed; adoptChunk()
         // re-creates any missing block per chunk. Recomputing the full layout for every
         // road at load built up to 200k Placement objects and dominated world entry.
         if (records != null && !records.positions(road.record.id()).isEmpty()) {
            continue;
         }

         try {
            queueRoad(markers, road);
         } catch (Throwable ignored) {
         }

         if (markers.size() >= MAX_MARKERS) {
            break;
         }
      }

      QUEUE.addAll(markers.values());
   }

   private static void queueRoad(Map<Long, Placement> markers, RoadIndex.Built road) {
      UUID owner = road.record.id();

      for (RoadStructures.Part part : road.record.structures()) {
         RoadMarker.Kind kind = kindOf(part);
         if (kind == null) {
            continue;
         }

         queuePart(markers, part, kind, owner);
         if (markers.size() >= MAX_MARKERS) {
            return;
         }
      }
   }

   /**
    * Positions a road would own according to the current placement algorithm. Only used as
    * a fallback when no recorded positions exist yet (roads built before recording).
    */
   static Map<Long, RoadMarker.Kind> layout(RoadIndex.Built road) {
      LinkedHashMap<Long, Placement> markers = new LinkedHashMap<>();
      UUID owner = road.record.id();

      try {
         queueRoad(markers, road);
      } catch (Throwable ignored) {
      }

      try {
         RoadSurface.Geometry geometry = RoadSurface.build(road.renderMesh(), List.of(), List.of());

         for (RoadSurface.Face face : geometry.markings()) {
            queuePaint(markers, face, owner);
            if (markers.size() >= MAX_MARKERS) {
               break;
            }
         }
      } catch (Throwable ignored) {
      }

      LinkedHashMap<Long, RoadMarker.Kind> out = new LinkedHashMap<>();

      for (Map.Entry<Long, Placement> entry : markers.entrySet()) {
         out.put(entry.getKey(), entry.getValue().kind);
      }

      return out;
   }

   /**
    * Queues the marker blocks of removed/edited roads. Recorded positions are used when
    * available, otherwise the computed layout is used as a fallback. Positions still owned
    * by a remaining road are never deleted.
    */
   static void queueRemoval(Collection<RoadIndex.Built> oldRoads, Collection<RoadIndex.Built> finalRoads) {
      if (records == null && level != null) {
         records = RoadMarkerData.get(level);
      }

      if (oldRoads.isEmpty()) {
         return;
      }

      HashMap<Long, RoadMarker.Kind> old = new HashMap<>();

      for (RoadIndex.Built road : oldRoads) {
         Map<Long, RoadMarker.Kind> recorded = records == null ? Map.of() : records.positions(road.record.id());

         if (recorded.isEmpty()) {
            old.putAll(layout(road));
         } else {
            old.putAll(recorded);
         }
      }

      if (old.isEmpty()) {
         return;
      }

      HashSet<Long> chunks = new HashSet<>();

      for (long key : old.keySet()) {
         chunks.add(ChunkPos.asLong(BlockPos.getX(key) >> 4, BlockPos.getZ(key) >> 4));
      }

      for (RoadIndex.Built road : oldRoads) {
         for (long chunk : road.chunks) {
            enqueueReconcile(chunk);
         }
      }

      HashMap<Long, RoadMarker.Kind> keep = new HashMap<>();

      for (RoadIndex.Built road : finalRoads) {
         if (records != null) {
            keep.putAll(records.positions(road.record.id()));
         }

         if (road.chunks.stream().anyMatch(chunks::contains)) {
            for (Map.Entry<Long, RoadMarker.Kind> entry : layout(road).entrySet()) {
               keep.putIfAbsent(entry.getKey(), entry.getValue());
            }
         }
      }

      for (Map.Entry<Long, RoadMarker.Kind> entry : old.entrySet()) {
         long key = entry.getKey();
         boolean kept = keep.get(key) == entry.getValue()
            || nearKind(keep, BlockPos.of(key), entry.getValue(), KEEP_RADIUS) != 0L;

         if (!kept && queuedRemovals.add(key)) {
            REMOVALS.add(new Removal(BlockPos.of(key), null));

            if (records != null) {
               records.addPending(key);
            }
         }
      }
   }

   /**
    * Removes marker blocks inside {@code chunk} that no existing road covers any more.
    * Markers still owned by an existing road, or still next to a road surface (trees, lamps,
    * piers), are kept. Returns the number of removed blocks.
    */
   public static int cleanChunk(ServerLevel world, ChunkPos chunk, boolean apply, Map<RoadMarker.Kind, Integer> histogram) {
      if (records == null) {
         records = RoadMarkerData.get(world);
      }

      if (records == null) {
         return 0;
      }

      RoadData data = RoadData.get(world);
      if (data == null) {
         return 0;
      }

      ChunkAccess access = world.getChunkSource().getChunk(chunk.x, chunk.z, ChunkStatus.FULL, false);
      if (access == null) {
         return 0;
      }

      ArrayList<BlockPos> found = new ArrayList<>();
      access.findBlocks(
         state -> state.is((Block)SplineRoads.MARKER.get()),
         (state, pos) -> true,
         (pos, state) -> found.add(pos.immutable())
      );

      if (found.isEmpty()) {
         return 0;
      }

      HashSet<UUID> ids = new HashSet<>();

      for (int dx = -2; dx <= 2; dx++) {
         for (int dz = -2; dz <= 2; dz++) {
            ids.addAll(data.index.inChunk(ChunkPos.asLong(chunk.x + dx, chunk.z + dz)));
         }
      }

      List<RoadIndex.Built> nearby = ids.stream().map(data.index.roads::get).filter(Objects::nonNull).toList();
      int removed = 0;

      for (BlockPos pos : found) {
         // The chunk scan can report cells whose current block is no longer a marker (the
         // chunk may have changed since the palette walk). Never read marker properties or
         // place air unless the live block really is a road marker.
         BlockState current = world.getBlockState(pos);
         if (!current.is((Block)SplineRoads.MARKER.get())) {
            continue;
         }

         long key = pos.asLong();
         Set<UUID> owners = records.roadsAt(key);
         boolean currentOwner = false;

         for (UUID id : owners) {
            if (data.index.roads.containsKey(id)) {
               currentOwner = true;
               break;
            }
         }

         if (!currentOwner && nearCurrentRoad(pos, nearby)) {
            // Untracked, but still part of a road's furniture (canopy, lamp head, pier).
            continue;
         }

         if (currentOwner) {
            continue;
         }

         removed++;

         if (histogram != null) {
            histogram.merge(current.getValue(RoadMarker.KIND), 1, Integer::sum);
         }

         if (!apply) {
            continue;
         }

         world.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);

         if (!owners.isEmpty() || records.isPending(key)) {
            records.complete(key);
         }
      }

      return removed;
   }

   /** Prunes unowned/leftover marker blocks in loaded chunks around {@code center}. */
   public static int pruneUntracked(
      ServerLevel world, BlockPos center, int radius, boolean apply, Map<RoadMarker.Kind, Integer> histogram
   ) {
      int chunkRadius = Math.max(1, (radius + 15) / 16);
      int cx = center.getX() >> 4;
      int cz = center.getZ() >> 4;
      int removed = 0;

      for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++) {
         for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
            if (world.hasChunk(x, z)) {
               removed += cleanChunk(world, new ChunkPos(x, z), apply, histogram);
            }
         }
      }

      return removed;
   }

   /**
    * Generous "is this still part of a road" test: carriageway/sidewalk width plus furniture
    * margin laterally, and a very tall vertical window so bridge piers and canopy markers stay.
    */
   private static boolean nearCurrentRoad(BlockPos pos, Collection<RoadIndex.Built> roads) {
      double x = (double)pos.getX() + 0.5;
      double y = (double)pos.getY() + 0.5;
      double z = (double)pos.getZ() + 0.5;
      RoadGeometry.V point = new RoadGeometry.V(x, y, z);

      for (RoadIndex.Built road : roads) {
         try {
            if (road.mesh.samples().isEmpty()
               || x < road.mesh.min().x() - 10.0
               || x > road.mesh.max().x() + 10.0
               || z < road.mesh.min().z() - 10.0
               || z > road.mesh.max().z() + 10.0) {
               continue;
            }

            RoadQueries.Projection projection = RoadQueries.horizontal(road.mesh, point);
            if (projection == null) {
               continue;
            }

            double lateral = Math.abs(projection.lateral());
            double centerY = projection.sample().center().y();

            if (lateral <= projection.sample().halfWidth() + 24.0 && y >= centerY - 512.0 && y <= centerY + 128.0) {
               return true;
            }
         } catch (RuntimeException ignored) {
            // A degenerate mesh must never abort an orphan audit.
         }
      }

      return false;
   }

   private static Map<Long, Map<Long, RoadMarker.Kind>> byChunk(RoadIndex.Built road) {
      UUID id = road.record.id();
      ChunkLayout cached = CHUNK_LAYOUTS.get(id);

      if (cached != null && cached.record == road.record) {
         return cached.byChunk;
      }

      // Prefer the recorded ledger: recomputing layout(r) builds the whole road surface
      // geometry and its furniture placements, which used to cost seconds per chunk load.
      // The cache is keyed by road id and validated against the (immutable) record identity,
      // so road edits recompute and GC cannot silently drop the result.
      Map<Long, RoadMarker.Kind> source = records == null ? Map.of() : records.positions(id);
      if (source.isEmpty()) {
         source = layout(road);
      }

      HashMap<Long, Map<Long, RoadMarker.Kind>> out = new HashMap<>();

      for (Map.Entry<Long, RoadMarker.Kind> entry : source.entrySet()) {
         long chunk = ChunkPos.asLong(BlockPos.getX(entry.getKey()) >> 4, BlockPos.getZ(entry.getKey()) >> 4);
         out.computeIfAbsent(chunk, key -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
      }

      CHUNK_LAYOUTS.put(id, new ChunkLayout(road.record, out));
      return out;
   }

   private static final class ChunkLayout {
      final RoadRecord record;
      final Map<Long, Map<Long, RoadMarker.Kind>> byChunk;

      ChunkLayout(RoadRecord record, Map<Long, Map<Long, RoadMarker.Kind>> byChunk) {
         this.record = record;
         this.byChunk = byChunk;
      }
   }

   /**
    * After every revision change, remove and forget recorded markers whose road no longer
    * exists in the index. This catches deletions that bypass the normal edit commit path
    * (junction splits, node moves, ...) so their marker blocks never get stuck.
    */
   private static void reconcileRemovals(RoadData data) {
      if (records == null) {
         return;
      }

      HashMap<Long, RoadMarker.Kind> keep = new HashMap<>();

      for (RoadIndex.Built road : data.index.roads.values()) {
         keep.putAll(records.positions(road.record.id()));
      }

      ArrayList<UUID> stale = new ArrayList<>();

      for (UUID id : records.roads()) {
         if (!data.index.roads.containsKey(id)) {
            stale.add(id);
         }
      }

      for (UUID id : stale) {
         for (Map.Entry<Long, RoadMarker.Kind> entry : records.positions(id).entrySet()) {
            long pos = entry.getKey();
            enqueueReconcile(ChunkPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4));
            boolean kept = keep.get(pos) == entry.getValue()
               || nearKind(keep, BlockPos.of(pos), entry.getValue(), KEEP_RADIUS) != 0L;

            if (!kept && queuedRemovals.add(pos)) {
               REMOVALS.add(new Removal(BlockPos.of(pos), null));
               records.addPending(pos);
            }
         }

         if (records.positions(id).isEmpty()) {
            records.remove(id);
         }
      }
   }

   /**
    * Called when a chunk finishes loading, before it is sent to clients. Placing the markers
    * here means Xaero's very first map sample already sees the colours.
    */
   static void chunkLoaded(ServerLevel world, ChunkAccess chunk) {
      if (records == null) {
         records = RoadMarkerData.get(world);
      }

      RoadData data = RoadData.get(world);
      if (data == null) {
         return;
      }

      ChunkPos pos = chunk.getPos();
      HashSet<UUID> ids = new HashSet<>();

      for (int dx = -2; dx <= 2; dx++) {
         for (int dz = -2; dz <= 2; dz++) {
            ids.addAll(data.index.inChunk(ChunkPos.asLong(pos.x + dx, pos.z + dz)));
         }
      }

      if (ids.isEmpty()) {
         return;
      }

      adoptedChunks.remove(pos.toLong());
      adoptChunk(world, pos, ids.stream().map(data.index.roads::get).filter(Objects::nonNull).toList());
   }

   /**
    * Read-only adoption of markers placed by older versions, plus orphan detection.
    *
    * <p>Markers close enough to a current road are recorded to that road. Markers that do not
    * match the ledger are kept as they are: road deletion already removes the markers it
    * recorded, so an unknown marker is not proof of an orphan. Explicit cleanup is available
    * through {@code /sr markers prune}.</p>
    */
   static void adoptChunk(ServerLevel world, ChunkPos chunk, List<RoadIndex.Built> nearby) {
      if (records == null) {
         records = RoadMarkerData.get(world);
      }

      if (!adoptedChunks.add(chunk.toLong())) {
         return;
      }

      ChunkAccess access = world.getChunkSource().getChunk(chunk.x, chunk.z, ChunkStatus.FULL, false);
      if (access == null) {
         adoptedChunks.remove(chunk.toLong());
         return;
      }

      HashMap<Long, RoadMarker.Kind> desired = new HashMap<>();
      HashMap<Long, UUID> owners = new HashMap<>();
      // The caller already passes every road in the 5x5 chunk neighbourhood, which covers
      // markers that sit a little outside their road's own chunks. Scanning all roads of the
      // world here forced a full marker layout (road surface + furniture) for roads that had
      // no ledger records yet: one chunk load could take more than ten seconds.
      HashSet<RoadIndex.Built> candidates = new HashSet<>(nearby);

      for (RoadIndex.Built road : candidates) {
         for (Map.Entry<Long, RoadMarker.Kind> entry : byChunk(road).getOrDefault(chunk.toLong(), Map.of()).entrySet()) {
            desired.putIfAbsent(entry.getKey(), entry.getValue());
            owners.putIfAbsent(entry.getKey(), road.record.id());
         }
      }

      List<BlockPos> found = new ArrayList<>();
      access.findBlocks(
         state -> state.is((Block)SplineRoads.MARKER.get()),
         (state, pos) -> true,
         (pos, state) -> found.add(pos.immutable())
      );

      for (BlockPos pos : found) {
         long key = pos.asLong();
         BlockState state = world.getBlockState(pos);
         RoadMarker.Kind actual = state.is((Block)SplineRoads.MARKER.get()) ? state.getValue(RoadMarker.KIND) : null;
         RoadMarker.Kind want = desired.get(key);

         if (want == null) {
            // Older marker layouts are denser and offset by a block or two. Keeping cells
            // near a desired position preserves the richer canopy/greenbelt colours.
            long nearOwner = nearKind(desired, pos, actual, KEEP_RADIUS);

            if (nearOwner != 0L) {
               UUID owner = owners.get(nearOwner);
               if (owner != null) {
                  records.add(owner, key, actual);
               }
               continue;
            }

            // Keep markers that are still next to a current road even when the exact cell is
            // not in the ledger (partial ledger, older denser layouts, offsets). Removing
            // those is what turned a colourful map into a sparse grey one.
            if (actual != null) {
               UUID roadOwner = nearestRoad(pos, candidates);
               if (roadOwner != null) {
                  records.add(roadOwner, key, actual);
                  continue;
               }
            }
         }

         if (want != null) {
            cancelRemoval(key);

            if (want != actual) {
               // Same cell is needed by a remaining road with another kind: convert the
               // marker instead of deleting it, so nearby roads never lose their detail.
               BlockState converted = SplineRoads.MARKER.get().defaultBlockState().setValue(RoadMarker.KIND, want);
               world.setBlock(pos, converted, 2);
            }

            UUID owner = owners.get(key);
            if (owner != null) {
               records.add(owner, key, want);
            }
         }
         // No removal here: deleting a road already removes exactly the markers it recorded,
         // so a marker that is not in the ledger is not necessarily an orphan. Queuing it
         // deleted markers that the rebuild had just placed (the removal scan even looks up
         // to five blocks above the recorded position, so it also hit freshly rebuilt cells).
         // Leftover markers from roads deleted before the ledger existed can be cleaned with
         // /sr markers prune, which is explicit and chunk-scoped.
      }

      for (Map.Entry<Long, RoadMarker.Kind> entry : desired.entrySet()) {
         long key = entry.getKey();
         if (queuedRemovals.contains(key)) {
            continue;
         }

         BlockPos pos = BlockPos.of(key);
         BlockState state = world.getBlockState(pos);
         if (state.is((Block)SplineRoads.MARKER.get()) && state.getValue(RoadMarker.KIND) == entry.getValue()) {
            cancelRemoval(key);
            UUID owner = owners.get(key);
            if (owner != null) {
               records.add(owner, key, entry.getValue());
            }
            continue;
         }

         if (!state.isAir()) {
            continue;
         }

         // Place immediately: the chunk is being loaded/watched right now, and Xaero
         // samples it during this window. Queueing would make the first sample colourless.
         BlockState marker = SplineRoads.MARKER.get().defaultBlockState().setValue(RoadMarker.KIND, entry.getValue());
         world.setBlock(pos, marker, 2);
         cancelRemoval(key);
         UUID owner = owners.get(key);
         if (owner != null) {
            records.add(owner, key, entry.getValue());
         }
      }

      // Xaero does not rebuild a map tile for a chunk that has not changed since it was
      // first mapped. Toggle one marker kind for a tick so the client sees a real change
      // and re-samples the whole chunk tile (the rest of the markers then get coloured).
      if (nudgedChunks.add(chunk.toLong()) && !desired.isEmpty()) {
         for (long key : desired.keySet()) {
            BlockPos pos = BlockPos.of(key);
            BlockState state = world.getBlockState(pos);

            if (state.is((Block)SplineRoads.MARKER.get())) {
               RoadMarker.Kind original = state.getValue(RoadMarker.KIND);
               RoadMarker.Kind temporary = original == RoadMarker.Kind.CONCRETE ? RoadMarker.Kind.STEEL : RoadMarker.Kind.CONCRETE;
               world.setBlock(pos, state.setValue(RoadMarker.KIND, temporary), 2);
               NUDGES.add(new Nudge(pos, original, 1));
               break;
            }
         }
      }
   }

   private static void nudgeBudget() {
      int budget = 32;

      while (budget-- > 0 && !NUDGES.isEmpty()) {
         Nudge nudge = NUDGES.peekFirst();

         if (--nudge.delay > 0) {
            break;
         }

         NUDGES.removeFirst();
         BlockState state = level.getBlockState(nudge.pos);

         if (state.is((Block)SplineRoads.MARKER.get())) {
            level.setBlock(nudge.pos, state.setValue(RoadMarker.KIND, nudge.kind), 2);
         }
      }
   }

   private static final class Nudge {
      final BlockPos pos;
      final RoadMarker.Kind kind;
      int delay;

      Nudge(BlockPos pos, RoadMarker.Kind kind, int delay) {
         this.pos = pos;
         this.kind = kind;
         this.delay = delay;
      }
   }

   private static long nearKind(Map<Long, RoadMarker.Kind> cells, BlockPos pos, RoadMarker.Kind kind, int radius) {
      if (cells.isEmpty() || kind == null) {
         return 0L;
      }

      for (int dx = -radius; dx <= radius; dx++) {
         for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
               long key = BlockPos.asLong(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);

               if (cells.get(key) == kind) {
                  return key;
               }
            }
         }
      }

      return 0L;
   }

   private static void cancelRemoval(long key) {
      if (cancelledRemovals.size() > 10000) {
         cancelledRemovals.clear();
      }

      if (queuedRemovals.remove(key) || records != null && records.isPending(key)) {
         cancelledRemovals.add(key);

         if (records != null) {
            records.cancelPending(key);
         }
      }
   }

   private static void enqueueReconcile(long chunk) {
      if (reconcileQueued.add(chunk)) {
         RECONCILE.add(chunk);
      }
   }

   private static void reconcileBudget() {
      int budget = 8;

      while (budget-- > 0 && !RECONCILE.isEmpty()) {
         long key = RECONCILE.removeFirst();
         reconcileQueued.remove(key);
         ChunkPos chunk = new ChunkPos((int)key, (int)(key >> 32));

         if (level.getChunkSource().getChunk(chunk.x, chunk.z, ChunkStatus.FULL, false) == null) {
            // Roads built beyond the render distance never get markers until their chunks
            // exist on the server; load them slowly so the map is filled automatically.
            if (++reconcileLoadTick >= LOAD_INTERVAL_TICKS) {
               reconcileLoadTick = 0;
               level.getChunk(chunk.x, chunk.z, ChunkStatus.FULL, true);
            }

            enqueueReconcile(key);
            continue;
         }

         RoadData data = RoadData.get(level);
         if (data == null) {
            return;
         }

         HashSet<UUID> ids = new HashSet<>();

         for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
               ids.addAll(data.index.inChunk(ChunkPos.asLong(chunk.x + dx, chunk.z + dz)));
            }
         }

         adoptedChunks.remove(key);
         adoptChunk(level, chunk, ids.stream().map(data.index.roads::get).filter(Objects::nonNull).toList());
      }
   }

   private static void removeBudget() {
      // Backlogs from deleted roads are drained faster; each removal is a handful of block
      // reads, so 4096/tick is still cheap compared to one frozen world load.
      int budget = REMOVALS.size() > 8192 ? 4096 : REMOVE_BUDGET_PER_TICK;

      while (budget-- > 0 && !REMOVALS.isEmpty()) {
         Removal removal = REMOVALS.removeFirst();
         long removalKey = removal.pos.asLong();

         if (cancelledRemovals.remove(removalKey)) {
            continue;
         }

         if (!level.isLoaded(removal.pos)) {
            // Marker blocks only exist in loaded chunks. Slowly load the chunk so a road
            // deleted partly outside the render distance still gets cleaned up.
            if (++loadTick >= LOAD_INTERVAL_TICKS) {
               loadTick = 0;
               ChunkPos chunk = new ChunkPos(removal.pos);
               level.getChunk(chunk.x, chunk.z, ChunkStatus.FULL, true);
            }

            REMOVALS.addLast(removal);
            continue;
         }

         for (int dy = 0; dy <= 5; dy++) {
            BlockPos p = removal.pos.above(dy);
            if (!level.isLoaded(p)) {
               continue;
            }

            BlockState state = level.getBlockState(p);
            if (state.is((Block)SplineRoads.MARKER.get())
               && (removal.kind == null || state.getValue(RoadMarker.KIND) == removal.kind)) {
               if (ownedByCurrentRoad(p)) {
                  // The column scan can reach markers of a road that still exists (bridges,
                  // overhead crossings, rebuilt layouts). Never delete those.
                  continue;
               }

               level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
               break;
            }
         }

         if (records != null) {
            records.complete(removal.pos.asLong());
         }

         queuedRemovals.remove(removalKey);
      }
   }

   /** True when a marker block still belongs to a road that exists in the index. */
   private static boolean ownedByCurrentRoad(BlockPos pos) {
      if (records == null || level == null) {
         return false;
      }

      Set<UUID> owners = records.roadsAt(pos.asLong());
      if (owners.isEmpty()) {
         return false;
      }

      RoadData data = RoadData.get(level);
      if (data == null) {
         return false;
      }

      for (UUID id : owners) {
         if (data.index.roads.containsKey(id)) {
            return true;
         }
      }

      return false;
   }

   private static void queuePart(Map<Long, Placement> markers, RoadStructures.Part part, RoadMarker.Kind kind, UUID owner) {
      List<RoadGeometry.V> base = part.base();
      List<RoadGeometry.V> top = part.top();
      if (base.size() < 4 || top.size() < 4) {
         return;
      }

      double minY = Double.MAX_VALUE;
      double maxY = -Double.MAX_VALUE;
      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minZ = Double.MAX_VALUE;
      double maxZ = -Double.MAX_VALUE;

      for (int i = 0; i < 4; i++) {
         RoadGeometry.V p = base.get(i);
         RoadGeometry.V t = top.get(i);
         minX = Math.min(minX, Math.min(p.x(), t.x()));
         maxX = Math.max(maxX, Math.max(p.x(), t.x()));
         minZ = Math.min(minZ, Math.min(p.z(), t.z()));
         maxZ = Math.max(maxZ, Math.max(p.z(), t.z()));
         minY = Math.min(minY, p.y());
         maxY = Math.max(maxY, t.y());
      }

      if (maxX - minX > 64.0 || maxZ - minZ > 64.0) {
         return;
      }

      double length = part.a().sub(part.b()).horizontalLength();
      int topY = (int)Math.ceil(maxY);
      int bottomY = (int)Math.floor(minY);
      double footprintX = maxX - minX;
      double footprintZ = maxZ - minZ;
      boolean vertical = maxY - minY > 2.0 && footprintX < 2.0 && footprintZ < 2.0;

      if (vertical) {
         int centerX = (int)Math.floor((minX + maxX) * 0.5);
         int centerZ = (int)Math.floor((minZ + maxZ) * 0.5);
         int step = Math.max(1, (int)Math.ceil((maxY - minY) / 8.0));

         for (int y = topY; y >= bottomY; y -= step) {
            addMarker(markers, new BlockPos(centerX, y, centerZ), kind, owner);
         }

         addMarker(markers, new BlockPos(centerX, topY, centerZ), kind, owner);
         return;
      }

      // Fill the top footprint at one marker per block, so canopies, medians, railings
      // and other wide structures keep their shape/colour on the map and in DH.
      int added = 0;
      List<RoadGeometry.V> topCorners = top;

      for (int x = (int)Math.floor(minX); x <= (int)Math.floor(maxX); x++) {
         for (int z = (int)Math.floor(minZ); z <= (int)Math.floor(maxZ); z++) {
            if (inside(topCorners, x + 0.5, z + 0.5)) {
               addMarker(markers, new BlockPos(x, topY, z), kind, owner);
               added++;
            }
         }
      }

      if (added == 0) {
         int steps = length < 1.0E-6 ? 1 : Math.min(16, Math.max(1, (int)Math.ceil(length / 2.0)));

         for (int step = 0; step < steps; step++) {
            double t = steps == 1 ? 0.5 : (step + 0.5) / (double)steps;
            double x = part.a().x() + (part.b().x() - part.a().x()) * t;
            double z = part.a().z() + (part.b().z() - part.a().z()) * t;
            addMarker(markers, BlockPos.containing(x, topY, z), kind, owner);
         }
      }
   }

   private static void queuePaint(Map<Long, Placement> markers, RoadSurface.Face face, UUID owner) {
      List<RoadGeometry.V> points = face.points();
      if (points.size() < 3) {
         return;
      }

      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minZ = Double.MAX_VALUE;
      double maxZ = -Double.MAX_VALUE;
      double maxY = -Double.MAX_VALUE;

      for (RoadGeometry.V point : points) {
         minX = Math.min(minX, point.x());
         maxX = Math.max(maxX, point.x());
         minZ = Math.min(minZ, point.z());
         maxZ = Math.max(maxZ, point.z());
         maxY = Math.max(maxY, point.y());
      }

      if (maxX - minX > 96.0 || maxZ - minZ > 96.0) {
         return;
      }

      int rgb = face.color() & 0xFFFFFF;
      int r = rgb >> 16 & 0xFF;
      int g = rgb >> 8 & 0xFF;
      int b = rgb & 0xFF;
      RoadMarker.Kind kind = r > 180 && g > 140 && b < 150 ? RoadMarker.Kind.PAINT_YELLOW : RoadMarker.Kind.PAINT_WHITE;
      int y = (int)Math.ceil(maxY);

      for (int x = (int)Math.floor(minX); x <= (int)Math.floor(maxX); x++) {
         for (int z = (int)Math.floor(minZ); z <= (int)Math.floor(maxZ); z++) {
            if (inside(points, x + 0.5, z + 0.5)) {
               addMarker(markers, new BlockPos(x, y, z), kind, owner);
            }
         }
      }
   }

   private static void addMarker(Map<Long, Placement> markers, BlockPos pos, RoadMarker.Kind kind, UUID owner) {
      long key = pos.asLong();
      Placement placement = markers.get(key);

      if (placement == null) {
         placement = new Placement(pos.immutable(), kind);
         markers.put(key, placement);
      }

      placement.owners.add(owner);
   }

   private static boolean inside(List<RoadGeometry.V> polygon, double x, double z) {
      boolean inside = false;

      for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
         RoadGeometry.V a = polygon.get(i);
         RoadGeometry.V b = polygon.get(j);
         if ((a.z() > z) != (b.z() > z) && x < (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) {
            inside = !inside;
         }
      }

      return inside;
   }

   private static RoadMarker.Kind kindOf(RoadStructures.Part part) {
      if (part.pier() && part.material() == RoadStructures.Material.CONCRETE) {
         return RoadMarker.Kind.PIER;
      }

      switch (part.material()) {
         case OAK_LEAVES:
            return RoadMarker.Kind.OAK_LEAVES;
         case BIRCH_LEAVES:
            return RoadMarker.Kind.BIRCH_LEAVES;
         case SPRUCE_LEAVES:
            return RoadMarker.Kind.SPRUCE_LEAVES;
         case CHERRY_LEAVES:
            return RoadMarker.Kind.CHERRY_LEAVES;
         case OAK_LOG:
            return RoadMarker.Kind.OAK_LOG;
         case BIRCH_LOG:
            return RoadMarker.Kind.BIRCH_LOG;
         case SPRUCE_LOG:
            return RoadMarker.Kind.SPRUCE_LOG;
         case CHERRY_LOG:
            return RoadMarker.Kind.CHERRY_LOG;
         case LAMP:
            return RoadMarker.Kind.LAMP;
         case LAMP_BLUE:
            return RoadMarker.Kind.LAMP_BLUE;
         case STEEL:
         case ARCH_STEEL:
         case GANTRY_FRAME:
         case CB_POST:
         case CB_ARM:
         case CB_BASE:
            return RoadMarker.Kind.STEEL;
         case DARK_STEEL:
            return RoadMarker.Kind.DARK_STEEL;
         case CONCRETE:
            return RoadMarker.Kind.CONCRETE;
         case SOIL:
            return RoadMarker.Kind.SOIL;
         case GREEN:
            return RoadMarker.Kind.GREEN;
         case FLOWERS:
         case FLOWER_PINK:
         case FLOWER_YELLOW:
         case FLOWER_WHITE:
            return RoadMarker.Kind.FLOWERS;
         case SIGN_GREEN:
            return RoadMarker.Kind.SIGN_GREEN;
         case SIGN_BLUE:
            return RoadMarker.Kind.SIGN_BLUE;
         case SIGN_WHITE:
            return RoadMarker.Kind.SIGN_WHITE;
         default:
            return null;
      }
   }

   private static void placeBudget() {
      int budget = PLACE_BUDGET_PER_TICK;

      while (budget-- > 0 && !QUEUE.isEmpty()) {
         Placement placement = QUEUE.removeFirst();
         if (!level.isLoaded(placement.pos)) {
            enqueueReconcile(ChunkPos.asLong(placement.pos.getX() >> 4, placement.pos.getZ() >> 4));
            continue;
         }

         BlockPos pos = placement.pos;
         BlockState current = level.getBlockState(pos);
         if (!current.isAir() && !current.is(SplineRoads.MARKER.get())) {
            continue;
         }

         BlockState marker = SplineRoads.MARKER.get().defaultBlockState().setValue(RoadMarker.KIND, placement.kind);
         if (current != marker) {
            level.setBlock(pos, marker, 2);
         }

         cancelRemoval(pos.asLong());

         if (records != null) {
            for (UUID owner : placement.owners) {
               records.add(owner, pos.asLong(), placement.kind);
            }
         }
      }
   }

   private static final class Placement {
      final BlockPos pos;
      final RoadMarker.Kind kind;
      final Set<UUID> owners = new HashSet<>();

      Placement(BlockPos pos, RoadMarker.Kind kind) {
         this.pos = pos;
         this.kind = kind;
      }
   }

   private static record Removal(BlockPos pos, RoadMarker.Kind kind) {
   }
}
