package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.LaneClosureLandscape;
import com.sora.splineroads.core.LaneDeck;
import com.sora.splineroads.core.LaneMerge;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.MultiLevelBridges;
import com.sora.splineroads.core.RailbedFurniture;
import com.sora.splineroads.core.RailwayCrossings;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGantryMounts;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPavingSupport;
import com.sora.splineroads.core.RoadPierSpacing;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRailJoin;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadSolidOverlap;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.core.RoadStructureClearance;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadSupports;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.core.RoadTerrainSurface;
import com.sora.splineroads.core.RoadsideBypass;
import com.sora.splineroads.world.RoadBlocks;
import com.sora.splineroads.world.RoadFillEntity;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadTerrainCover;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RoadPreviewGround implements RoadStructures.Ground {
   private final Level level;
   private final RoadGeometry.Mesh mesh;
   private final List<RoadGeometry.Mesh> nearby;
   private final List<RoadGeometry.Mesh> obstacles;
   private final RoadStructureClearance clearance = new RoadStructureClearance();
   private final RoadSolidOverlap.Index cycleExcavation;
   private final RoadSolidOverlap.Index gantrySolids;
   private final RoadPavingSupport sidewalkSolids;
   private final RoadPierSpacing pierSpacing;
   private final RoadRailJoin railJoin;
   private final RoadRailJoin capMouths;
   private final Map<RoadGeometry.Mesh, RoadGeometry.Mesh> motorDecks = new IdentityHashMap<>();
   private final Map<BlockPos, List<AABB>> columns = new HashMap<>();
   private final Set<BlockPos> queries = new LinkedHashSet<>();
   private final Map<Long, LevelChunk> chunks = new HashMap<>();
   private final RoadPreviewGround.Sources retained;
   private final List<RoadStructures.Part> fixedSupports;
   private final RoadGeometry.Mesh terrainReference;
   private RoadTerrainCover cover;
   private final Map<RoadGeometry.Mesh, List<RoadStructures.Part>> railShells = new IdentityHashMap<>();
   private RoadGeometry.Mesh stackMesh;
   private List<RoadGeometry.Mesh> stackNeighbors;

   public RoadPreviewGround(Level level, RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> nearby) {
      this(level, mesh, nearby, nearby, List.of(), null, RoadRailJoin.mouths(List.of()), null, null, null);
   }

   public static RoadPreviewGround create(Level level, RoadGeometry.Mesh mesh, UUID edited, Collection<RoadIndex.Built> roads) {
      return create(level, mesh, edited, roads, null);
   }

   static RoadPreviewGround create(Level level, RoadGeometry.Mesh mesh, UUID edited, Collection<RoadIndex.Built> roads, RoadPreviewGround.Sources retained) {
      return create(level, mesh, edited, roads, retained, null, null);
   }

   static RoadPreviewGround create(
      Level level,
      RoadGeometry.Mesh mesh,
      UUID edited,
      Collection<RoadIndex.Built> roads,
      RoadPreviewGround.Sources retained,
      List<RoadStructures.Part> fixedSupports,
      RoadGeometry.Mesh terrainReference
   ) {
      List<RoadIndex.Built> candidates = roads.stream().filter(r -> !r.record.id().equals(edited)).toList();
      double margin = (double)(3 + (mesh.settings().options().sidewalk().enabled() ? mesh.settings().options().sidewalk().width() : 0));
      List<RoadIndex.Built> nearby = candidates.stream().filter(r -> RoadIndex.overlapXZ(r.mesh, mesh, margin)).toList();
      List<RoadIndex.Built> obstacles = candidates.stream().filter(r -> RoadIndex.overlapXZ(r.mesh, mesh, 82.0)).toList();
      boolean laneEdges = mesh.settings().style().ramp()
         || mesh.settings().options().lanePoints().link() != null
         || nearby.stream().anyMatch(r -> r.mesh.settings().style().ramp() || r.mesh.settings().options().lanePoints().link() != null);
      RoadRailJoin join = laneEdges
         ? new RoadRailJoin(
            mesh,
            nearby.stream()
               .map(r -> new RoadRailJoin.Neighbor(r.mesh, RoadSurface.higherPriority(r.record.id(), r.mesh, edited == null ? new UUID(0L, 0L) : edited, mesh)))
               .toList()
         )
         : null;
      RoadRailJoin mouths = RoadRailJoin.mouths(
         nearby.stream()
            .filter(
               r -> LaneMerge.linkedTo(mesh, r.mesh)
                     || LaneMerge.linkedTo(r.mesh, mesh)
                     || mesh.settings().options().lanePoints().cuts().stream().anyMatch(c -> c.connection().equals(r.record.id()))
            )
            .map(r -> r.mesh)
            .toList()
      );
      RoadPreviewGround result = new RoadPreviewGround(
         level,
         mesh,
         nearby.stream().map(r -> r.mesh).toList(),
         obstacles.stream().map(r -> r.mesh).toList(),
         obstacles.stream().flatMap(r -> r.record.structures().stream()).toList(),
         join,
         mouths,
         retained,
         fixedSupports,
         terrainReference
      );
      List<RoadIndex.Built> tunnels = roads.stream().filter(RoadIndex.Built::hasTunnel).toList();
      result.cover = new RoadTerrainCover(chunk -> tunnels.stream().filter(r -> r.chunks.contains(chunk)).toList());
      nearby.forEach(r -> result.railShells.put(r.mesh, r.record.structures()));
      return result;
   }

   private RoadPreviewGround(
      Level level,
      RoadGeometry.Mesh mesh,
      List<RoadGeometry.Mesh> nearby,
      List<RoadGeometry.Mesh> obstacles,
      List<RoadStructures.Part> structures,
      RoadRailJoin railJoin,
      RoadRailJoin capMouths,
      RoadPreviewGround.Sources retained,
      List<RoadStructures.Part> fixedSupports,
      RoadGeometry.Mesh terrainReference
   ) {
      this.level = level;
      this.mesh = mesh;
      this.nearby = List.copyOf(nearby);
      this.obstacles = List.copyOf(obstacles);
      this.retained = retained != null && retained.sameLevel(level) ? retained : null;
      this.fixedSupports = fixedSupports == null ? null : List.copyOf(fixedSupports);
      this.terrainReference = terrainReference;
      this.railJoin = railJoin;
      this.capMouths = capMouths;
      this.cycleExcavation = new RoadSolidOverlap.Index(obstacles.stream().flatMap(r -> RoadsideBypass.supportVoids(r).stream()).toList());
      this.sidewalkSolids = new RoadPavingSupport(structures);
      this.gantrySolids = new RoadSolidOverlap.Index(structures.stream().filter(RoadGantry::member).toList());
      this.pierSpacing = new RoadPierSpacing(structures);
   }

   @Override
   public List<RoadStructures.Part> fixedSupports() {
      return this.fixedSupports;
   }

   @Override
   public double foundation(RoadGeometry.V center, double top, double width) {
      double median = RoadSupports.medianFoundation(center, top, width, this.obstacles);
      return this.sidewalkSolids.foundation(center, top, width, median, () -> RoadStructures.Ground.super.foundation(center, top, width));
   }

   @Override
   public RoadGeometry.Mesh terrainReference(RoadGeometry.Mesh candidate) {
      return this.terrainReference == null ? candidate : this.terrainReference;
   }

   public RoadPreviewGround.Sources sources() {
      return new RoadPreviewGround.Sources(
         this.level, new RoadPreviewGround.ColumnSources(this.queries, this.columns, this.chunks, this.retained == null ? null : this.retained.columns)
      );
   }

   boolean incomplete() {
      return this.level != null && this.queries.stream().anyMatch(key -> !this.columns.containsKey(key));
   }

   @Override
   public double top(double x, double z, double deckY) {
      return Math.min(this.solidTop(x, z, deckY, true), deckY);
   }

   @Override
   public double surface(double x, double z, double roadY) {
      return this.cover == null
         ? RoadTerrainSurface.connected(roadY, probe -> this.solidTop(x, z, probe, false))
         : this.cover.surface(x, z, roadY, probe -> this.solidTop(x, z, probe, false));
   }

   private double solidTop(double x, double z, double deckY, boolean schematicFoundation) {
      if (this.level == null) {
         return schematicFoundation ? deckY : Double.NaN;
      } else {
         BlockPos key = BlockPos.containing(x, Math.min((double)(this.level.getMaxBuildHeight() - 1), deckY), z);
         this.queries.add(key);
         long position = ChunkPos.asLong(key.getX() >> 4, key.getZ() >> 4);
         LevelChunk chunk = loadedChunk(this.level, position);
         this.chunks.put(position, chunk);
         List<AABB> values = this.columns.get(key);
         if (values == null) {
            values = chunk == null ? (this.retained == null ? null : this.retained.columns.known(key)) : column(this.level, key);
            if (values != null) {
               this.columns.put(key, values);
            }
         }

         if (values == null) {
            return schematicFoundation ? deckY : Double.NaN;
         } else {
            for (AABB box : values) {
               if (x >= box.minX - 1.0E-7 && x <= box.maxX + 1.0E-7 && z >= box.minZ - 1.0E-7 && z <= box.maxZ + 1.0E-7 && box.minY < deckY - 1.0E-7) {
                  return box.maxY;
               }
            }

            return Double.NaN;
         }
      }
   }

   private static LevelChunk loadedChunk(Level level, long position) {
      ChunkPos at = new ChunkPos(position);
      if (level.getChunkSource().getChunk(at.x, at.z, ChunkStatus.FULL, false) instanceof LevelChunk loaded && !(loaded instanceof EmptyLevelChunk)) {
         return loaded;
      }

      return null;
   }

   private static List<AABB> column(Level level, BlockPos key) {
      ArrayList<AABB> result = new ArrayList<>();
      int first = Math.min(key.getY(), level.getHeight(Types.WORLD_SURFACE, key.getX(), key.getZ()) - 1);
      int bottom = Math.max(level.getMinBuildHeight(), key.getY() - 96);

      for (int y = first; y >= bottom; y--) {
         BlockPos pos = new BlockPos(key.getX(), y, key.getZ());
         BlockState state = level.getBlockState(pos);
         double cap = 1.0;
         if (RoadBlocks.isCollider(state)) {
            if (level.getBlockEntity(pos) instanceof RoadFillEntity fill) {
               state = fill.fill();
               cap = fill.cap();
            } else {
               state = ((RoadBlocks.Fill)state.getValue(RoadBlocks.Road.FILL)).state();
            }
         }

         if (!state.isAir()
            && !state.is(BlockTags.LEAVES)
            && !state.is(BlockTags.LOGS)
            && !state.is((Block)SplineRoads.NODE.get())
            && !state.is((Block)SplineRoads.TUNNEL_AIR.get())
            && state.getFluidState().isEmpty()) {
            VoxelShape shape = state.getCollisionShape(level, pos);

            for (AABB box : shape.toAabbs()) {
               if (box.minY < cap) {
                  result.add(new AABB(box.minX, box.minY, box.minZ, box.maxX, Math.min(box.maxY, cap), box.maxZ).move(pos));
               }
            }

            if (y < key.getY() && cap == 1.0 && Block.isShapeFullBlock(shape)) {
               break;
            }
         }
      }

      result.sort(Comparator.<AABB>comparingDouble(boxx -> boxx.maxY).reversed());
      return List.copyOf(result);
   }

   @Override
   public boolean joined(RoadGeometry.V point) {
      return LanePoints.opening(this.mesh, point)
         || this.nearby
            .stream()
            .anyMatch(
               road -> RoadQueries.joins(this.mesh, road, point)
                     || RailwayCrossings.at(this.mesh, road, point, 0.3)
                     || RailwayCrossings.walkingAt(this.mesh, road, point, 0.3)
            );
   }

   @Override
   public boolean furnitureClear(RoadGeometry.V point) {
      return LanePoints.opening(this.mesh, point)
         || this.nearby.stream().anyMatch(road -> RailwayCrossings.at(this.mesh, road, point, 1.0) || RailwayCrossings.walkingAt(this.mesh, road, point, 1.0));
   }

   @Override
   public boolean wireContinues(boolean first) {
      return this.nearby.stream().anyMatch(road -> RailbedFurniture.continues(this.mesh, road, this.railShells.getOrDefault(road, List.of()), first));
   }

   private void prepareStack() {
      if (this.stackNeighbors == null) {
         this.stackMesh = RoadStreetscape.resolve(this.mesh, this);
         this.stackNeighbors = this.nearby.stream().map(road -> RoadStreetscape.resolve(road, this)).toList();
      }
   }

   @Override
   public boolean sharedDeck() {
      this.prepareStack();
      return this.stackNeighbors.stream().anyMatch(road -> MultiLevelBridges.compatible(this.stackMesh, road));
   }

   @Override
   public List<RoadStructures.Part> sharedSupports(RoadGeometry.Mesh upper, RoadGeometry.Sample at, double depth, double width, double offset) {
      this.prepareStack();
      List<RoadGeometry.Mesh> lowers = this.stackNeighbors.stream().filter(road -> MultiLevelBridges.compatible(road, this.stackMesh)).toList();
      return MultiLevelBridges.supports(upper, lowers, at, upper.settings().thickness(), depth, width, offset, this);
   }

   @Override
   public boolean blocked(RoadStructures.Part part) {
      return this.blocked(part, null, null, null);
   }

   @Override
   public String blockedReason(RoadStructures.Part part) {
      return (RoadStructureClearance.selfSupportBlocked(part, this.mesh) ? "构件侵入本桥通行空间，" : "") + RoadStructures.Ground.super.blockedReason(part);
   }

   @Override
   public boolean lampBlocked(RoadStructures.Part part, RoadGeometry.Sample mount) {
      return this.blocked(part, null, null, mount);
   }

   @Override
   public boolean railBlocked(RoadStructures.Part part, RoadGeometry.V a, RoadGeometry.V b) {
      return this.blocked(part, a, b, null);
   }

   private boolean blocked(RoadStructures.Part part, RoadGeometry.V railA, RoadGeometry.V railB, RoadGeometry.Sample lampMount) {
      if (!RailbedFurniture.visualOnly(part)
         && this.nearby
            .stream()
            .anyMatch(
               road -> Stream.concat(part.base().stream(), Stream.of(part.a().add(part.b()).mul(0.5)))
                     .anyMatch(p -> RailwayCrossings.walkingAt(this.mesh, road, p, 0.3))
            )) {
         return true;
      } else if (!this.pierSpacing.tooClose(part) && (!part.pier() || RoadGantry.member(part) || !this.gantrySolids.intersects(part))) {
         if (part.pier() && part.material() == RoadStructures.Material.CONCRETE && this.cycleExcavation.intersects(part)) {
            return true;
         } else if (!RoadSidewalks.smoothPart(part) && this.sidewalkSolids.blocks(part)) {
            return true;
         } else if (lampMount == null
            ? (
               RoadGantryMounts.foot(part)
                  ? !RoadGantryMounts.blockedByRoad(part, this.mesh, 4.25)
                  : !RoadStructureClearance.selfSupportBlocked(part, this.mesh)
            )
            : !RoadStreetscape.lampOwnRoadBlocked(part, this.mesh, lampMount)) {
            if (this.railJoin == null && !part.pier() && part.material() == RoadStructures.Material.DEFAULT) {
               return false;
            } else {
               double w = part.halfExtent() + (part.pier() ? 0.5 : 0.05);
               double minX = Math.min(part.a().x(), part.b().x()) - w;
               double maxX = Math.max(part.a().x(), part.b().x()) + w;
               double minZ = Math.min(part.a().z(), part.b().z()) - w;
               double maxZ = Math.max(part.a().z(), part.b().z()) + w;
               double minY = Math.min(part.a().y(), part.b().y()) - part.verticalFrame();
               double maxY = Math.max(part.a().y(), part.b().y()) + part.height() + part.verticalFrame();

               for (RoadGeometry.Mesh other : this.obstacles) {
                  if ((railA == null || !RoadRailJoin.sharedRail(other, railA, railB))
                     && !(maxX < other.min().x() - 2.0)
                     && !(minX > other.max().x() + 2.0)
                     && !(maxZ < other.min().z() - 2.0)
                     && !(minZ > other.max().z() + 2.0)) {
                     if (this.clearance.cycleBlocked(part, other)) {
                        return true;
                     }

                     if (!(maxY < other.min().y() - 0.05)
                        && !(minY > other.max().y() + RoadStructureClearance.headroom(other))
                        && !RoadSidewalks.smoothPart(part)) {
                        if (part.pier()) {
                           if (RoadStructureClearance.pierBlocked(part, other, this.nearby)) {
                              return true;
                           }
                        } else if (!LaneClosureLandscape.paved(part) || !RoadClearance.belowSurface(part, other, 0.025)) {
                           boolean joining = LaneMerge.linkedTo(this.mesh, other)
                              || LaneMerge.linkedTo(other, this.mesh)
                              || RoadQueries.joins(this.mesh, other, part.a())
                              || RoadQueries.joins(this.mesh, other, part.b());
                           if (!joining || !RoadClearance.joiningFloor(part, this.mesh, other)) {
                              RoadGeometry.Mesh protectedMesh = railA == null ? other : this.motorDecks.computeIfAbsent(other, LaneDeck::motorOnly);
                              if (RoadClearance.structureInvades(part, protectedMesh, RoadStructureClearance.headroom(other))) {
                                 return true;
                              }
                           }
                        }
                     }
                  }
               }

               return false;
            }
         } else {
            return true;
         }
      } else {
         return true;
      }
   }

   @Override
   public boolean unionRails() {
      return this.railJoin != null;
   }

   @Override
   public RoadGeometry.V railJoint(RoadGeometry.V p, RoadGeometry.V direction) {
      return this.railJoin == null ? null : this.railJoin.joint(p, direction);
   }

   @Override
   public RoadGeometry.V railJoint(RoadGeometry.V p, RoadGeometry.V direction, boolean highway, boolean raised) {
      return this.railJoin == null ? null : this.railJoin.joint(p, direction, highway, raised);
   }

   @Override
   public boolean railPost(RoadGeometry.V p) {
      return this.railJoin == null || this.railJoin.ownsPost(p);
   }

   @Override
   public boolean railPost(RoadGeometry.V p, boolean highway, boolean raised) {
      return this.railJoin == null || this.railJoin.ownsPost(p, highway, raised);
   }

   @Override
   public List<RoadRailJoin.Span> railSpans(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V outside) {
      return this.railJoin == null ? RoadStructures.Ground.super.railSpans(a, b, outside) : this.railJoin.exposed(a, b, outside);
   }

   @Override
   public List<RoadRailJoin.Span> railSpans(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V outside, double inset) {
      return this.railJoin == null ? RoadStructures.Ground.super.railSpans(a, b, outside, inset) : this.railJoin.exposed(a, b, outside, inset);
   }

   @Override
   public List<RoadRailJoin.Span> capRailSpans(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V outside) {
      ArrayList<RoadRailJoin.Span> result = new ArrayList<>();

      for (RoadRailJoin.Span span : this.railSpans(a, b, outside)) {
         result.addAll(this.capMouths.exposedMouth(span.a(), span.b()));
      }

      return result;
   }

   static final class ColumnSources {
      static final int POLL_COLUMNS = 32;
      static final int POLL_INTERVAL = 20;
      private final Map<BlockPos, List<AABB>> known;
      private final List<BlockPos> keys;
      private final Map<Long, RoadPreviewGround.ColumnSources.ChunkColumns> chunks = new LinkedHashMap<>();
      private final ArrayDeque<RoadPreviewGround.ColumnSources.ChunkColumns> reloads = new ArrayDeque<>();
      private int cursor;
      private long budgetTick = Long.MIN_VALUE;
      private int readThisTick;
      private long polledAt = Long.MIN_VALUE;
      private long nextPollAt = Long.MIN_VALUE;
      private boolean invalid;

      ColumnSources(Collection<BlockPos> queries, Map<BlockPos, List<AABB>> known, Map<Long, ?> observed, RoadPreviewGround.ColumnSources retained) {
         this.known = Map.copyOf(known);
         this.keys = List.copyOf(queries);

         for (BlockPos key : this.keys) {
            long position = ChunkPos.asLong(key.getX() >> 4, key.getZ() >> 4);
            RoadPreviewGround.ColumnSources.ChunkColumns group = this.chunks.computeIfAbsent(position, p -> {
               Object identity = observed.get(p);
               if (identity == null && retained != null) {
                  RoadPreviewGround.ColumnSources.ChunkColumns old = retained.chunks.get(p);
                  if (old != null) {
                     identity = old.identity.get();
                  }
               }

               return new RoadPreviewGround.ColumnSources.ChunkColumns(p, identity);
            });
            group.keys.add(key);
            group.unknown = group.unknown | !known.containsKey(key);
         }
      }

      List<AABB> known(BlockPos key) {
         return this.known.get(key);
      }

      boolean current(RoadPreviewGround.Terrain terrain) {
         if (this.invalid) {
            return false;
         } else {
            long tick = terrain.tick();
            if (tick != this.budgetTick) {
               this.budgetTick = tick;
               this.readThisTick = 0;
            }

            for (RoadPreviewGround.ColumnSources.ChunkColumns group : this.chunks.values()) {
               Object present = terrain.chunk(group.position);
               if (present != null && present != group.identity.get()) {
                  if (group.unknown) {
                     return !(this.invalid = true);
                  }

                  if (present != group.checking.get()) {
                     group.checking = new WeakReference<>(present);
                     group.reloadCursor = 0;
                  }

                  if (!group.queued) {
                     group.queued = true;
                     this.reloads.addLast(group);
                  }
               }
            }

            int visits = 0;

            while (!this.reloads.isEmpty() && this.readThisTick < 32 && visits++ < 32) {
               RoadPreviewGround.ColumnSources.ChunkColumns groupx = this.reloads.removeFirst();
               groupx.queued = false;
               Object present = terrain.chunk(groupx.position);
               if (present != null) {
                  if (present == groupx.identity.get()) {
                     groupx.checking = new WeakReference<>(null);
                     groupx.reloadCursor = 0;
                  } else {
                     if (present != groupx.checking.get()) {
                        groupx.checking = new WeakReference<>(present);
                        groupx.reloadCursor = 0;
                     }

                     BlockPos key = groupx.keys.get(groupx.reloadCursor++);
                     this.readThisTick++;
                     if (!this.known.get(key).equals(terrain.column(key))) {
                        return !(this.invalid = true);
                     }

                     if (groupx.reloadCursor == groupx.keys.size()) {
                        groupx.identity = new WeakReference<>(present);
                        groupx.checking = new WeakReference<>(null);
                        groupx.reloadCursor = 0;
                        groupx.checkedAt = tick;
                     } else {
                        groupx.queued = true;
                        this.reloads.addLast(groupx);
                     }
                  }
               }
            }

            if (tick != this.polledAt && tick >= this.nextPollAt && this.readThisTick != 32) {
               this.polledAt = tick;

               for (int count = 0; count < 32 && this.readThisTick < 32 && this.cursor < this.keys.size(); this.cursor++) {
                  BlockPos keyx = this.keys.get(this.cursor);
                  RoadPreviewGround.ColumnSources.ChunkColumns groupx = this.chunks.get(ChunkPos.asLong(keyx.getX() >> 4, keyx.getZ() >> 4));
                  if (groupx.checkedAt != tick && !groupx.queued && terrain.chunk(groupx.position) != null) {
                     List<AABB> before = this.known.get(keyx);
                     this.readThisTick++;
                     if (before == null || !before.equals(terrain.column(keyx))) {
                        return !(this.invalid = true);
                     }
                  }

                  count++;
               }

               if (this.cursor == this.keys.size()) {
                  this.cursor = 0;
                  this.nextPollAt = tick + 20L;
               }

               return true;
            } else {
               return true;
            }
         }
      }

      private static final class ChunkColumns {
         final long position;
         final List<BlockPos> keys = new ArrayList<>();
         WeakReference<Object> identity;
         WeakReference<Object> checking = new WeakReference<>(null);
         int reloadCursor;
         boolean queued;
         boolean unknown;
         long checkedAt = Long.MIN_VALUE;

         ChunkColumns(long position, Object identity) {
            this.position = position;
            this.identity = new WeakReference<>(identity);
         }
      }
   }

   public static final class Sources {
      private final Level level;
      private final RoadPreviewGround.ColumnSources columns;
      private final RoadPreviewGround.Terrain terrain;

      private Sources(final Level level, RoadPreviewGround.ColumnSources columns) {
         this.level = level;
         this.columns = columns;
         this.terrain = new RoadPreviewGround.Terrain() {
            @Override
            public Object chunk(long position) {
               return RoadPreviewGround.loadedChunk(level, position);
            }

            @Override
            public List<AABB> column(BlockPos key) {
               return RoadPreviewGround.column(level, key);
            }

            @Override
            public long tick() {
               return level.getGameTime();
            }
         };
      }

      boolean sameLevel(Level candidate) {
         return candidate == this.level;
      }

      public boolean current(Level candidate) {
         return candidate != this.level ? false : this.level == null || this.columns.current(this.terrain);
      }
   }

   interface Terrain {
      Object chunk(long var1);

      List<AABB> column(BlockPos var1);

      long tick();
   }
}
