package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.compat.mtr.MtrRailAccess;
import com.sora.splineroads.core.RailbedFurniture;
import com.sora.splineroads.core.RailbedGeometry;
import com.sora.splineroads.core.RailbedOptions;
import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RailbedStructures;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadCoverage;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPavingSupport;
import com.sora.splineroads.core.RoadPierSpacing;
import com.sora.splineroads.core.RoadRaster;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadSolidOverlap;
import com.sora.splineroads.core.RoadStructureClearance;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadSupports;
import com.sora.splineroads.net.RoadNetwork;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent.BreakEvent;
import net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent;

public final class RailbedBuildQueue {
   private static final int MAX_CELLS = 1000000;
   private static final Deque<RailbedBuildQueue.Job> jobs = new ArrayDeque<>();
   private static final Set<ServerLevel> recovering = Collections.newSetFromMap(new IdentityHashMap<>());
   private static boolean protectionProbe;

   static boolean protectionProbe() {
      return protectionProbe;
   }

   public static CompletableFuture<RailbedStore.Entry> submit(
      ServerLevel level,
      ServerPlayer player,
      UUID id,
      List<RailbedPath.Path> paths,
      double width,
      RoadGeometry.Structure mode,
      List<RailbedPath.Path> surrounding
   ) {
      return submit(level, player, id, paths, width, mode, surrounding, RailbedOptions.DEFAULT);
   }

   public static CompletableFuture<RailbedStore.Entry> submit(
      ServerLevel level,
      ServerPlayer player,
      UUID id,
      List<RailbedPath.Path> paths,
      double width,
      RoadGeometry.Structure mode,
      List<RailbedPath.Path> surrounding,
      RailbedOptions options
   ) {
      return RailbedRoads.submit(level, player, id, paths, width, mode, options, surrounding);
   }

   public static CompletableFuture<RailbedStore.Entry> preview(
      ServerLevel level,
      ServerPlayer player,
      UUID id,
      List<RailbedPath.Path> paths,
      double width,
      RoadGeometry.Structure mode,
      List<RailbedPath.Path> surrounding
   ) {
      return previewTask(level, player, id, paths, width, mode, surrounding).future();
   }

   static RailbedBuildQueue.Preview previewTask(
      ServerLevel level,
      ServerPlayer player,
      UUID id,
      List<RailbedPath.Path> paths,
      double width,
      RoadGeometry.Structure mode,
      List<RailbedPath.Path> surrounding
   ) {
      return previewTask(level, player, id, paths, width, mode, surrounding, RailbedOptions.DEFAULT);
   }

   static RailbedBuildQueue.Preview previewTask(
      ServerLevel level,
      ServerPlayer player,
      UUID id,
      List<RailbedPath.Path> paths,
      double width,
      RoadGeometry.Structure mode,
      List<RailbedPath.Path> surrounding,
      RailbedOptions options
   ) {
      return new RailbedBuildQueue.Preview(RailbedRoads.preview(level, player, id, paths, width, mode, options, surrounding));
   }

   static RailbedBuildQueue.Preview previewTask(
      ServerLevel level,
      ServerPlayer player,
      UUID id,
      List<RailbedPath.Path> paths,
      double width,
      RoadGeometry.Structure mode,
      List<RailbedPath.Path> surrounding,
      RailbedOptions options,
      BooleanSupplier cancelled
   ) {
      return new RailbedBuildQueue.Preview(RailbedRoads.preview(level, player, id, paths, width, mode, options, surrounding, cancelled));
   }

   public static void tick(MinecraftServer server) {
      for (ServerLevel level : server.getAllLevels()) {
         if (recovering.add(level)) {
            for (RailbedStore.Pending pending : RoadData.get(level).railbeds.pending.values()) {
               jobs.add(new RailbedBuildQueue.Job(level, pending));
            }
         }
      }

      long until = System.nanoTime() + 4000000L;
      int operations = 0;

      while (!jobs.isEmpty() && operations++ < 256 && System.nanoTime() < until) {
         RailbedBuildQueue.Job job = jobs.removeFirst();

         try (RoadPlanInputs inputs = job.inputs == null ? RoadPlanInputs.open(job.level, false) : job.inputs.resume()) {
            job.step();
         } catch (RuntimeException var10) {
            job.fail(var10);
         }

         if (!job.done) {
            jobs.addLast(job);
         }
      }

      RailbedRoads.tick();
   }

   public static void stop() {
      RailbedRoads.stop();

      for (RailbedBuildQueue.Job j : jobs) {
         j.result.completeExceptionally(new IllegalStateException("服务器停止，未完成施工将在重启时回退"));
      }

      jobs.clear();
      recovering.clear();
   }

   static RailbedStructures.Ground ground(
      final ServerLevel level, RoadData data, Map<Long, BlockState> original, List<RoadIndex.Built> roads, List<RailbedPath.Path> paths
   ) {
      final RoadStructures.Ground terrain = data.railbedTerrain(original);
      final RoadSolidOverlap.Index railAir = RailbedStructures.clearance(paths);
      final List<RoadGeometry.Mesh> meshes = roads.stream().filter(r -> !r.record.railway()).map(r -> r.mesh).toList();
      List<RoadStructures.Part> fixtures = roads.stream().flatMap(r -> r.record.structures().stream()).toList();
      final RoadPavingSupport paving = new RoadPavingSupport(fixtures);
      final RoadSolidOverlap.Index solids = new RoadSolidOverlap.Index(
         fixtures.stream()
            .filter(p -> !RoadSidewalks.smoothPart(p) && p.material() != RoadStructures.Material.GREEN && p.material() != RoadStructures.Material.SOIL)
            .toList()
      );
      final IdentityHashMap<RoadGeometry.Mesh, RoadSolidOverlap.Index> medianFills = roads.stream()
         .collect(
            Collectors.toMap(
               r -> r.mesh,
               r -> new RoadSolidOverlap.Index(
                     r.record
                        .structures()
                        .stream()
                        .filter(p -> p.material() == RoadStructures.Material.GREEN || p.material() == RoadStructures.Material.SOIL)
                        .toList()
                  ),
               (a, b) -> a,
               IdentityHashMap::new
            )
         );
      final RoadSolidOverlap.Index existingRails = RailbedStructures.clearance(roads.stream().filter(r -> r.record.railway()).flatMap(r -> {
         RailbedStore.Entry entry = data.railbeds.entries.get(r.record.id());
         return entry == null ? Stream.empty() : entry.paths.stream();
      }).toList());
      final RoadStructureClearance clearance = new RoadStructureClearance();
      final RoadPierSpacing spacing = new RoadPierSpacing(fixtures);
      return new RailbedStructures.Ground() {
         @Override
         public double floor(RoadGeometry.V p) {
            double var3;
            try (RoadWorkChunks lease = RoadWorkChunks.open(level)) {
               lease.node(BlockPos.containing(p.x(), p.y(), p.z()));
               var3 = terrain.top(p.x(), p.z(), p.y());
            }

            return var3;
         }

         @Override
         public double foundation(RoadGeometry.V center, double top, double width) {
            double median = RoadSupports.medianFoundation(center, top, width, meshes);
            return paving.foundation(center, top, width, median, () -> RailbedStructures.Ground.super.foundation(center, top, width));
         }

         @Override
         public double surface(double x, double z, double y) {
            double var8;
            try (RoadWorkChunks lease = RoadWorkChunks.open(level)) {
               lease.node(BlockPos.containing(x, y, z));
               var8 = terrain.surface(x, z, y);
            }

            return var8;
         }

         @Override
         public boolean covered(RoadGeometry.V p) {
            return this.surface(p.x(), p.z(), p.y()) > p.y() + 1.0;
         }

         @Override
         public boolean blocked(RoadStructures.Part part) {
            if (!railAir.intersects(part) && !existingRails.intersects(part) && !solids.intersects(part) && !paving.blocks(part) && !spacing.tooClose(part)) {
               for (Entry<RoadGeometry.Mesh, RoadSolidOverlap.Index> entry : medianFills.entrySet()) {
                  if (entry.getValue().intersects(part)
                     && (part.material() != RoadStructures.Material.CONCRETE || !RoadStructures.fitsMedian(part, entry.getKey(), meshes))) {
                     return true;
                  }
               }

               for (RoadGeometry.Mesh mesh : meshes) {
                  if (clearance.cycleBlocked(part, mesh) || RoadStructureClearance.pierBlocked(part, mesh, meshes)) {
                     return true;
                  }
               }

               return false;
            } else {
               return true;
            }
         }
      };
   }

   static void validateDeckClearance(List<RailbedPath.Path> paths, List<RoadIndex.Built> roads) {
      RoadSolidOverlap.Index air = RailbedStructures.clearance(paths);

      for (RoadIndex.Built road : roads) {
         for (RoadStructures.Part part : physicalParts(road)) {
            if (air.intersects(part)) {
               throw new IllegalArgumentException("已有 SR 结构侵入铁路通行空间，请调整高度或位置");
            }
         }
      }
   }

   private static List<RoadStructures.Part> physicalParts(RoadIndex.Built road) {
      List<RoadStructures.Part> result = RoadTravelValidation.deckParts(road.mesh);
      road.record.structures().stream().filter(p -> !RailbedFurniture.visualOnly(p)).forEach(result::add);
      return result;
   }

   private static void validateDeckSolids(RoadGeometry.Mesh mesh, List<RailbedPath.Path> rails, List<RoadIndex.Built> roads, RoadData data) {
      List<RoadStructures.Part> deck = RoadTravelValidation.deckParts(mesh);
      ArrayList<RailbedPath.Path> allRails = new ArrayList<>(rails);

      for (RoadIndex.Built road : roads) {
         RailbedStore.Entry entry = data.railbeds.entries.get(road.record.id());
         if (entry != null) {
            allRails.addAll(entry.paths);
         }
      }

      RoadSolidOverlap.Index air = RailbedStructures.clearance(allRails);

      for (RoadStructures.Part part : deck) {
         if (air.intersects(part)) {
            throw new IllegalArgumentException("铁路路面侵入既有轨道通行空间");
         }
      }

      for (RoadIndex.Built roadx : roads) {
         RoadSolidOverlap.Index solids = new RoadSolidOverlap.Index(physicalParts(roadx));

         for (RoadStructures.Part partx : deck) {
            if (solids.intersects(partx)) {
               throw new IllegalArgumentException("铁路路面与已有 SR 实体相交");
            }

            if (!roadx.record.railway()
               && RoadClearance.structureInvades(partx, roadx.mesh, RoadStructureClearance.headroom(roadx.mesh))
               && !RoadClearance.belowSurface(partx, roadx.mesh, 0.025)) {
               throw new IllegalArgumentException("铁路路面侵入已有道路通行空间");
            }
         }
      }
   }

   private static boolean isMtr(BlockState state) {
      return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("mtr");
   }

   private RailbedBuildQueue() {
   }

   static final class Job {
      final ServerLevel level;
      final RoadData data;
      final ServerPlayer player;
      final UUID id;
      final RailbedStore.Entry old;
      final CompletableFuture<RailbedStore.Entry> result = new CompletableFuture<>();
      final Long2ObjectOpenHashMap<BlockState> before = new Long2ObjectOpenHashMap();
      final Long2ObjectOpenHashMap<BlockState> after = new Long2ObjectOpenHashMap();
      final List<RailbedPath.Path> paths;
      final double width;
      final RoadGeometry.Structure mode;
      final RailbedOptions options;
      final RailbedGeometry.Plan plan;
      RailbedStructures.Planner planner;
      RoadIndex.Built built;
      RailbedStore.Entry next;
      Iterator<Long> chunks;
      long chunk;
      int column = 256;
      Iterator<Long> write;
      Iterator<Long> oldCells;
      Iterator<Long> notify;
      RailbedStore.Pending journal;
      RuntimeException failure;
      boolean preview;
      boolean published;
      boolean rollback;
      boolean done;
      long revision;
      long sourceRevision;
      final String dimension;
      RoadPlanInputs inputs;
      Set<Long> protectedChunks = Set.of();
      Map<UUID, RoadRecord> obstacles = Map.of();

      Job(
         ServerLevel level,
         ServerPlayer player,
         UUID id,
         RailbedStore.Entry old,
         List<RailbedPath.Path> paths,
         double width,
         RoadGeometry.Structure mode,
         List<RailbedPath.Path> surrounding
      ) {
         this(level, player, id, old, paths, width, mode, surrounding, RailbedOptions.DEFAULT);
      }

      Job(
         ServerLevel level,
         ServerPlayer player,
         UUID id,
         RailbedStore.Entry old,
         List<RailbedPath.Path> paths,
         double width,
         RoadGeometry.Structure mode,
         List<RailbedPath.Path> surrounding,
         RailbedOptions options
      ) {
         this.options = options;
         this.level = level;
         this.data = RoadData.get(level);
         this.player = player;
         this.id = id;
         this.old = old;
         this.paths = paths == null ? null : List.copyOf(paths);
         this.width = width;
         this.mode = mode;
         ResourceLocation location = level.dimension().location();
         this.dimension = location.getNamespace() + "/" + location.getPath();
         this.sourceRevision = MtrRailAccess.revision(this.dimension);
         this.revision = this.data.index.contentRevision();
         this.plan = paths == null ? null : RailbedGeometry.plan(paths, width, mode);
         this.protectedChunks = new HashSet<>(RoadCoverage.chunks(this.plan == null ? old.road.mesh() : this.plan.mesh(), 16.0));
         if (old != null) {
            this.protectedChunks.addAll(RoadCoverage.chunks(old.road.mesh(), 16.0));
         }

         this.obstacles = this.localState();
         if (this.plan != null) {
            Set<String> selected = paths.stream().flatMap(px -> px.segments().stream()).map(RailbedPath.Segment::id).collect(Collectors.toSet());
            ArrayList<RailbedPath.Path> all = new ArrayList<>();

            for (RailbedPath.Path p : surrounding) {
               if (p.segments().stream().noneMatch(s -> selected.contains(s.id()))) {
                  all.add(p);
               }
            }

            all.addAll(paths);
            List<RoadIndex.Built> roads = this.obstacles.keySet().stream().map(this.data.index.roads::get).filter(Objects::nonNull).toList();
            RailbedBuildQueue.validateDeckClearance(paths, roads);
            RailbedBuildQueue.validateDeckSolids(this.plan.mesh(), all, roads, this.data);
            this.planner = new RailbedStructures.Planner(
               this.plan.mesh(),
               mode,
               RailbedBuildQueue.ground(level, this.data, (Map<Long, BlockState>)(old == null ? Map.of() : old.original), roads, all),
               options,
               paths
            );
         } else {
            this.initializeWrites();
         }
      }

      Job(ServerLevel level, RailbedStore.Pending pending) {
         this.level = level;
         this.dimension = "";
         this.data = RoadData.get(level);
         this.player = null;
         this.id = pending.id;
         this.old = pending.old;
         this.paths = null;
         this.width = 0.0;
         this.mode = RoadGeometry.Structure.AUTO;
         this.options = RailbedOptions.DEFAULT;
         this.plan = null;
         this.journal = pending;
         this.before.putAll(pending.before);
         this.after.putAll(pending.after);
         this.rollback = true;
         this.published = true;
         this.failure = new IllegalStateException("已回退中断的铁路施工");
         this.write = this.before.keySet().iterator();
         this.restoreIndex();
      }

      void step() {
         if (this.rollback) {
            this.restoreOne();
         } else if (!this.published && this.data.railbeds.entries.get(this.id) != this.old) {
            throw new IllegalArgumentException("原路基已改变，请重新预览");
         } else if (this.result.isCancelled()) {
            throw new IllegalArgumentException("铁路施工已取消并回退");
         } else if (this.paths != null && this.sourceRevision != MtrRailAccess.revision(this.dimension)) {
            throw new IllegalArgumentException("MTR 轨道在施工中发生变化，请重新预览");
         } else if (this.player == null || !this.player.isRemoved() && this.player.serverLevel() == this.level && this.player.mayBuild()) {
            if (this.data.index.contentRevision() != this.revision) {
               if (!this.localState().equals(this.obstacles)) {
                  throw new IllegalArgumentException("附近 SR 道路已改变，请重新预览");
               }

               this.revision = this.data.index.contentRevision();
            }

            if (this.oldCells != null) {
               if (this.oldCells.hasNext()) {
                  long key = this.oldCells.next();
                  BlockPos p = BlockPos.of(key);

                  try (RoadWorkChunks lease = RoadWorkChunks.open(this.level)) {
                     lease.node(p);
                     BlockState current = this.level.getBlockState(p);
                     if (current.equals(this.old.written.get(key))) {
                        this.before.put(key, current);
                        this.after.put(key, this.remainingState(key, (BlockState)this.old.original.get(key)));
                     }
                  }

                  return;
               }

               this.oldCells = null;
            }

            if (this.planner != null) {
               if (!this.planner.done()) {
                  this.planner.step();
               } else {
                  RoadGeometry.Mesh mesh = this.plan.mesh();
                  RailbedPath.Node a = this.paths.get(0).segments().get(0).from();
                  RailbedPath.Node last = this.paths.get(0).segments().get(this.paths.get(0).segments().size() - 1).to();
                  RoadRecord record = new RoadRecord(
                     this.id,
                     this.old == null ? (this.player == null ? new UUID(138L, 1L) : this.player.getUUID()) : this.old.road.owner(),
                     RailbedCodec.pos(a),
                     RailbedCodec.pos(last),
                     new RoadGeometry.Node(mesh.first().center(), 0.0, 0.0),
                     new RoadGeometry.Node(mesh.last().center(), 0.0, 0.0),
                     mesh.settings().structure(this.mode == RoadGeometry.Structure.TUNNEL ? RoadGeometry.Structure.TUNNEL : RoadGeometry.Structure.GROUND),
                     false,
                     5,
                     this.planner.result(),
                     0,
                     138,
                     this.id,
                     mesh.samples()
                  );
                  this.next = new RailbedStore.Entry(record, this.paths, this.width, this.mode, this.options);
                  this.built = RoadIndex.Built.loading(record);
                  this.built.prepareCollision();
                  this.chunks = new TreeSet<>(this.built.chunks).iterator();
                  this.planner = null;
                  this.initializeWrites();
               }
            } else if (!this.published && this.built != null) {
               if (this.column >= 256) {
                  if (!this.chunks.hasNext()) {
                     this.publish();
                     return;
                  }

                  this.chunk = this.chunks.next();
                  this.column = 0;
               }

               ChunkPos cp = new ChunkPos(this.chunk);
               int x = cp.getMinBlockX() + this.column % 16;
               int z = cp.getMinBlockZ() + this.column / 16;
               this.column++;
               this.prepareColumn(x, z);
            } else if (!this.published) {
               this.publish();
            } else if (this.write.hasNext()) {
               long key = this.write.next();
               BlockPos pos = BlockPos.of(key);

               try (RoadWorkChunks leasex = RoadWorkChunks.open(this.level)) {
                  leasex.node(pos);
                  BlockState current = this.level.getBlockState(pos);
                  BlockState expected = (BlockState)this.before.get(key);
                  if (!RoadPlanInputs.equivalentTerrain(expected, current)) {
                     throw new IllegalArgumentException(
                        "施工区域已被修改："
                           + pos.toShortString()
                           + "（"
                           + BuiltInRegistries.BLOCK.getKey(expected.getBlock())
                           + " → "
                           + BuiltInRegistries.BLOCK.getKey(current.getBlock())
                           + "）"
                     );
                  }

                  if (!current.equals(expected)) {
                     this.before.put(key, current);
                     if (this.next != null) {
                        this.next.original.put(key, current);
                     }

                     this.data.setDirty();
                  }

                  this.protect(pos, current, (BlockState)this.after.get(key));
                  this.set(pos, (BlockState)this.after.get(key));
                  this.fill(pos, (BlockState)this.after.get(key), this.next);
               }
            } else {
               if (this.notify == null) {
                  this.notify = this.after.keySet().iterator();
               }

               if (this.notify.hasNext()) {
                  this.notifyOne(false);
               } else {
                  if (this.next == null) {
                     this.data.railbeds.entries.remove(this.id);
                  } else {
                     this.data.railbeds.entries.put(this.id, this.next);
                  }

                  this.data.railbeds.pending.remove(this.id);
                  this.data.setDirty();
                  this.done = true;
                  this.result.complete(this.next);
                  this.message(this.next == null ? "铁路路基已删除；MTR 轨道保持原样" : "铁路路基建造完成");
               }
            }
         } else {
            throw new IllegalArgumentException("玩家离开或建设权限失效，施工已回退");
         }
      }

      void initializeWrites() {
         if (this.old != null) {
            this.oldCells = this.old.written.keySet().iterator();
         }
      }

      Map<UUID, RoadRecord> localState() {
         HashMap<UUID, RoadRecord> result = new HashMap<>();

         for (long chunk : this.protectedChunks) {
            for (UUID other : this.data.index.inChunk(chunk)) {
               if (!other.equals(this.id)) {
                  RoadIndex.Built road = this.data.index.roads.get(other);
                  if (road != null) {
                     result.put(other, road.record);
                  }
               }
            }
         }

         return Map.copyOf(result);
      }

      void prepareColumn(int x, int z) {
         try (RoadWorkChunks lease = RoadWorkChunks.open(this.level)) {
            lease.load(Set.of(ChunkPos.asLong(x >> 4, z >> 4)));
            RoadRaster.Local local = this.built.prepareCollision();
            Map<RoadRaster.Cell, List<RoadRaster.Box>> deck = local.deckColumn(x, z);
            HashMap<RoadRaster.Cell, List<RoadRaster.Box>> body = new HashMap<>(deck);
            local.structureColumn(x, z, px -> true).forEach((cellx, boxes) -> body.merge(cellx, (List<RoadRaster.Box>)boxes, (a, b) -> {
                  ArrayList<RoadRaster.Box> both = new ArrayList<>(a);
                  both.addAll(b);
                  return both;
               }));
            HashSet<RoadRaster.Cell> cells = new HashSet<>(body.keySet());
            RoadIndex.Built.Column c = this.built.column(new BlockPos(x, 0, z));
            if (c != null) {
               for (int y = (int)Math.floor(c.minTop()); y < (int)Math.ceil(c.maxTop() + 6.0); y++) {
                  cells.add(new RoadRaster.Cell(x, y, z));
               }
            }

            for (RoadRaster.Cell cell : cells) {
               BlockPos p = new BlockPos(x, cell.y(), z);
               long key = p.asLong();
               if (this.level.isOutsideBuildHeight(p) || !this.level.getWorldBorder().isWithinBounds(p)) {
                  throw new IllegalArgumentException("铁路结构超出世界边界");
               }

               BlockState current = RoadPlanInputs.state(this.level, p);
               BlockState original = this.source(key, current);
               boolean solid = body.containsKey(cell);
               if (RailbedBuildQueue.isMtr(current)) {
                  if (solid && (!MtrRailAccess.trainNode(current.getBlock()) || local.structureColumn(x, z, part -> true).containsKey(cell))) {
                     throw new IllegalArgumentException("铁路结构碰到 MTR 设施：" + p.toShortString());
                  }
               } else {
                  ArrayList<RoadIndex.Built> nearby = new ArrayList<>();
                  nearby.add(this.built);

                  for (UUID other : this.data.index.inChunk(ChunkPos.asLong(x >> 4, z >> 4))) {
                     if (!other.equals(this.id)) {
                        RoadIndex.Built road = this.data.index.roads.get(other);
                        if (road != null) {
                           nearby.add(road);
                        }
                     }
                  }

                  boolean otherBody = nearby.stream().skip(1L).anyMatch(r -> !r.boxes(p).isEmpty());
                  if (!solid && otherBody) {
                     this.next.original.put(key, original);
                     this.next.written.put(key, current);
                     this.before.remove(key);
                     this.after.remove(key);
                  } else {
                     if (this.old != null && this.old.written.containsKey(key) && !current.equals(this.old.written.get(key))) {
                        throw new IllegalArgumentException("原路基区域已有玩家修改：" + p.toShortString());
                     }

                     RoadClearing.validate(this.level, p, original, false);
                     BlockState target = solid ? this.data.railbedCollision(p, original, nearby) : ((Block)SplineRoads.TUNNEL_AIR.get()).defaultBlockState();
                     if (this.preview) {
                        if (this.player != null && !this.level.mayInteract(this.player, p)) {
                           throw new IllegalArgumentException("铁路预览范围受保护：" + p.toShortString());
                        }
                     } else {
                        this.protect(p, current, target);
                     }

                     this.next.original.put(key, original);
                     this.next.written.put(key, target);
                     if (!current.equals(target)) {
                        this.before.put(key, current);
                        this.after.put(key, target);
                     } else if (target.is((Block)SplineRoads.FILLED_COLLIDER.get())) {
                        this.before.put(key, current);
                        this.after.put(key, target);
                     } else {
                        this.before.remove(key);
                        this.after.remove(key);
                     }

                     if (this.next.written.size() > 1000000 || this.before.size() > 1000000) {
                        throw new IllegalArgumentException("铁路施工范围超过预算，请分段建造");
                     }
                  }
               }
            }
         }
      }

      BlockState source(long key, BlockState current) {
         if (this.old != null && current.equals(this.old.written.get(key))) {
            return (BlockState)this.old.original.get(key);
         } else {
            for (RailbedStore.Entry e : this.data.railbeds.entries.values()) {
               if (!e.road.id().equals(this.id) && current.equals(e.written.get(key))) {
                  return (BlockState)e.original.get(key);
               }
            }

            return !RoadBlocks.isCollider(current) && !TunnelAir.isAirSpace(current) ? current : this.data.railbedOriginal(key);
         }
      }

      BlockState remainingState(long key, BlockState original) {
         for (RailbedStore.Entry e : this.data.railbeds.entries.values()) {
            if (!e.road.id().equals(this.id) && e.written.containsKey(key)) {
               return (BlockState)e.written.get(key);
            }
         }

         BlockPos p = BlockPos.of(key);
         List<RoadIndex.Built> nearby = this.data
            .index
            .inChunk(new ChunkPos(p).toLong())
            .stream()
            .filter(v -> !v.equals(this.id))
            .map(this.data.index.roads::get)
            .filter(Objects::nonNull)
            .toList();
         if (nearby.stream().anyMatch(r -> !r.boxes(p).isEmpty())) {
            return this.data.railbedCollision(p, original, nearby);
         } else {
            return nearby.stream().anyMatch(r -> r.clearanceAt(p)) ? ((Block)SplineRoads.TUNNEL_AIR.get()).defaultBlockState() : original;
         }
      }

      void fill(BlockPos p, BlockState state, RailbedStore.Entry entry) {
         if (state.is((Block)SplineRoads.FILLED_COLLIDER.get())) {
            BlockState original = entry != null && entry.original.containsKey(p.asLong())
               ? (BlockState)entry.original.get(p.asLong())
               : this.source(p.asLong(), state);
            List<RoadIndex.Built> nearby = this.data
               .index
               .inChunk(new ChunkPos(p).toLong())
               .stream()
               .map(this.data.index.roads::get)
               .filter(Objects::nonNull)
               .toList();
            if (this.level.getBlockEntity(p) instanceof RoadFillEntity fill) {
               fill.fill(original, GroundTerrainJoin.cap(p, original, nearby));
            }
         }
      }

      void protect(BlockPos pos, BlockState current, final BlockState target) {
         if (!current.equals(target)) {
            if (this.player != null) {
               if (this.level.mayInteract(this.player, pos) && this.player.mayUseItemAt(pos, Direction.UP, this.player.getMainHandItem())) {
                  if (!current.isAir()) {
                     RailbedBuildQueue.protectionProbe = true;

                     boolean rejected;
                     try {
                        rejected = ((BreakEvent)NeoForge.EVENT_BUS.post(new BreakEvent(this.level, pos, current, this.player))).isCanceled();
                     } finally {
                        RailbedBuildQueue.protectionProbe = false;
                     }

                     if (rejected) {
                        throw new IllegalArgumentException("铁路施工被区域保护拒绝：" + pos.toShortString());
                     }
                  }

                  BlockSnapshot snapshot = BlockSnapshot.create(this.level.dimension(), this.level, pos);
                  var event = new EntityPlaceEvent(snapshot, current, this.player) {
                     public BlockState getPlacedBlock() {
                        return target;
                     }

                     public BlockState getState() {
                        return target;
                     }
                  };
                  if (((EntityPlaceEvent)NeoForge.EVENT_BUS.post(event)).isCanceled()) {
                     throw new IllegalArgumentException("铁路施工被区域保护拒绝：" + pos.toShortString());
                  }
               } else {
                  throw new IllegalArgumentException("铁路施工范围受保护：" + pos.toShortString());
               }
            }
         }
      }

      void publish() {
         if (this.preview) {
            if (this.inputs != null) {
               this.inputs.validate(this.level);
            }

            this.done = true;
            this.result.complete(this.next);
         } else {
            this.journal = new RailbedStore.Pending(this.id, this.old, this.next, this.before, this.after);
            this.data.railbeds.pending.put(this.id, this.journal);
            this.data.setDirty();
            this.published = true;
            this.write = this.after.keySet().iterator();
            if (this.built != null) {
               this.data.index.put(this.built);
               RoadNetwork.broadcastRoad(this.level, this.built.record);
            } else {
               this.data.index.remove(this.id);
               RoadNetwork.broadcastDelete(this.level, this.id);
            }

            this.revision = this.data.index.contentRevision();
         }
      }

      void fail(RuntimeException e) {
         if (this.rollback) {
            this.done = true;
            this.result.completeExceptionally(e);
            this.message("铁路回退中断，已保留恢复日志：" + e.getMessage());
         } else {
            this.failure = e;
            if (this.published) {
               this.rollback = true;
               this.notify = null;
               this.write = this.before.keySet().iterator();
               this.restoreIndex();
               this.message("铁路施工正在回退：" + e.getMessage());
            } else {
               this.done = true;
               this.result.completeExceptionally(e);
               this.message(e.getMessage());
            }
         }
      }

      void restoreIndex() {
         if (this.old == null) {
            this.data.index.remove(this.id);
            RoadNetwork.broadcastDelete(this.level, this.id);
         } else {
            this.data.index.put(RoadIndex.Built.loading(this.old.road));
            RoadNetwork.broadcastRoad(this.level, this.old.road);
         }

         this.data.setDirty();
      }

      void set(BlockPos p, BlockState state) {
         this.level.setBlock(p, state, 18);
         if (!this.level.getBlockState(p).equals(state)) {
            throw new IllegalStateException("铁路方块写入失败：" + p.toShortString());
         }
      }

      void notifyOne(boolean restoring) {
         long key = this.notify.next();
         BlockPos p = BlockPos.of(key);

         try (RoadWorkChunks lease = RoadWorkChunks.open(this.level)) {
            lease.node(p);
            BlockState state = (BlockState)(restoring ? this.before : this.after).get(key);
            if (!this.level.getBlockState(p).equals(state)) {
               return;
            }

            ((BlockState)(restoring ? this.after : this.before).get(key)).updateIndirectNeighbourShapes(this.level, p, 2, 511);
            state.updateNeighbourShapes(this.level, p, 2, 511);
            state.updateIndirectNeighbourShapes(this.level, p, 2, 511);
            this.level.updateNeighborsAt(p, state.getBlock());
         }
      }

      void restoreOne() {
         if (this.write.hasNext()) {
            long key = this.write.next();
            BlockPos p = BlockPos.of(key);

            try (RoadWorkChunks lease = RoadWorkChunks.open(this.level)) {
               lease.node(p);
               if (this.level.getBlockState(p).equals(this.after.get(key))) {
                  this.set(p, (BlockState)this.before.get(key));
                  this.fill(p, (BlockState)this.before.get(key), this.old);
               }
            }
         } else {
            if (this.notify == null) {
               this.notify = this.before.keySet().iterator();
            }

            if (this.notify.hasNext()) {
               this.notifyOne(true);
            } else {
               this.data.railbeds.pending.remove(this.id);
               this.data.setDirty();
               this.done = true;
               this.result.completeExceptionally(this.failure);
               this.message(this.failure.getMessage());
            }
         }
      }

      void message(String text) {
         if (!this.preview && this.player != null && !this.player.isRemoved()) {
            this.player.displayClientMessage(Component.literal(text), false);
         }
      }
   }

   static final class Preview {
      private final RailbedRoads.Preview common;

      Preview(RailbedRoads.Preview common) {
         this.common = common;
      }

      CompletableFuture<RailbedStore.Entry> future() {
         return this.common.future;
      }

      boolean valid() {
         return this.common.valid();
      }
   }
}
