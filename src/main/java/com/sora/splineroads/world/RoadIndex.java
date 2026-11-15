package com.sora.splineroads.world;

import com.sora.splineroads.core.LaneMerge;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RailwayCrossings;
import com.sora.splineroads.core.RoadAutoTunnels;
import com.sora.splineroads.core.RoadCoverage;
import com.sora.splineroads.core.RoadFurniture;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRaster;
import com.sora.splineroads.core.RoadRenderMesh;
import com.sora.splineroads.core.RoadSignalFrames;
import com.sora.splineroads.core.RoadSignals;
import com.sora.splineroads.core.RoadStructureRaster;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.core.RoadTunnelSpace;
import com.sora.splineroads.core.RoadsideBypass;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RoadIndex {
   private long revision;
   private long contentRevision;
   public final Map<UUID, RoadIndex.Built> roads = new LinkedHashMap<>();
   private RoadPlanningSnapshot planningSnapshot = RoadPlanningSnapshot.empty();
   private final Map<Long, Set<UUID>> chunkRoads = new HashMap<>();
   private final Map<Long, Set<UUID>> nodeRoads = new HashMap<>();
   private final Map<Long, Set<UUID>> signalChunks = new HashMap<>();
   private long signalRevision = -1L;
   private long signalChunk;
   private List<RoadIndex.Built> nearbySignals = List.of();
   private final Map<Long, Set<Long>> shapeChunks = new HashMap<>();
   private final Map<Long, VoxelShape> shapes = new LinkedHashMap<Long, VoxelShape>(128, 0.75F, true) {
      @Override
      protected boolean removeEldestEntry(Entry<Long, VoxelShape> e) {
         if (this.size() <= 32768) {
            return false;
         } else {
            RoadIndex.this.untrack(e.getKey());
            return true;
         }
      }
   };
   private final boolean lazy;
   private final RoadPointIndex pointIndex;
   public Consumer<UUID> changed = id -> {
   };

   private static <K, V> Map<K, V> bounded(final int limit) {
      return new LinkedHashMap<K, V>(128, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Entry<K, V> entry) {
            return this.size() > limit;
         }
      };
   }

   public long revision() {
      return this.revision;
   }

   public long contentRevision() {
      return this.contentRevision;
   }

   RoadPlanningSnapshot planningSnapshot() {
      return this.planningSnapshot;
   }

   void restoreOrder(Map<UUID, RoadIndex.Built> ordered) {
      this.roads.clear();
      this.roads.putAll(ordered);
      RoadPlanningSnapshot snapshot = RoadPlanningSnapshot.empty();
      if (!this.lazy) {
         for (RoadIndex.Built built : this.roads.values()) {
            snapshot = snapshot.with(built);
         }
      }

      this.planningSnapshot = snapshot;
      if (this.pointIndex != null) {
         this.pointIndex.restore(this.roads.values());
      }
   }

   public List<RoadIndex.Built> signalsNear(int chunkX, int chunkZ) {
      long chunk = ChunkPos.asLong(chunkX, chunkZ);
      if (this.signalRevision == this.revision && this.signalChunk == chunk) {
         return this.nearbySignals;
      } else {
         Set<UUID> ids = new HashSet<>();

         for (int x = chunkX - 8; x <= chunkX + 8; x++) {
            for (int z = chunkZ - 8; z <= chunkZ + 8; z++) {
               ids.addAll(this.signalChunks.getOrDefault(ChunkPos.asLong(x, z), Set.of()));
            }
         }

         this.nearbySignals = ids.stream().map(this.roads::get).toList();
         this.signalRevision = this.revision;
         this.signalChunk = chunk;
         return this.nearbySignals;
      }
   }

   private static Set<Long> signalLocations(RoadIndex.Built road) {
      Set<Long> chunks = new HashSet<>();

      for (RoadStructures.Part head : road.signalHeads) {
         chunks.add(ChunkPos.asLong((int)Math.floor(head.a().x()) >> 4, (int)Math.floor(head.a().z()) >> 4));
      }

      return chunks;
   }

   private static long chunkAt(long key) {
      BlockPos p = BlockPos.of(key);
      return ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
   }

   private void untrack(long key) {
      Set<Long> set = this.shapeChunks.get(chunkAt(key));
      if (set != null) {
         set.remove(key);
         if (set.isEmpty()) {
            this.shapeChunks.remove(chunkAt(key));
         }
      }
   }

   private synchronized void invalidate(Set<Long> chunks) {
      for (long c : chunks) {
         Set<Long> keys = this.shapeChunks.remove(c);
         if (keys != null) {
            keys.forEach(this.shapes::remove);
         }
      }
   }

   public RoadIndex() {
      this(false);
   }

   public RoadIndex(boolean lazy) {
      this.lazy = lazy;
      this.pointIndex = lazy ? null : new RoadPointIndex();
   }

   public void put(RoadIndex.Built built) {
      this.changed.accept(built.record.id());
      this.remove(built.record.id());
      this.revision++;
      this.contentRevision++;
      this.roads.put(built.record.id(), built);
      if (this.pointIndex != null) {
         this.pointIndex.put(built.record);
      }

      if (!this.lazy) {
         this.planningSnapshot = this.planningSnapshot.with(built);
      }

      signalLocations(built).forEach(c -> this.signalChunks.computeIfAbsent(c, k -> new HashSet<>()).add(built.record.id()));
      this.invalidate(built.chunks);
      built.chunks.forEach(c -> this.chunkRoads.computeIfAbsent(c, k -> new HashSet<>()).add(built.record.id()));

      for (BlockPos p : List.of(built.record.a(), built.record.b())) {
         this.nodeRoads.computeIfAbsent(p.asLong(), k -> new HashSet<>()).add(built.record.id());
      }
   }

   public RoadIndex.Built lanePoints(UUID id, LanePoints.Data value) {
      RoadIndex.Built previous = this.roads.get(id);
      if (previous == null) {
         throw new IllegalArgumentException("道路已不存在");
      } else {
         RoadIndex.Built next = previous.lanePoints(value);
         this.roads.put(id, next);
         if (!this.lazy) {
            this.planningSnapshot = this.planningSnapshot.with(next);
         }

         this.contentRevision++;
         if (this.pointIndex != null) {
            this.pointIndex.put(next.record);
         }

         this.changed.accept(id);
         return next;
      }
   }

   public RoadIndex.Built remove(UUID id) {
      RoadIndex.Built old = this.roads.remove(id);
      if (old != null && this.pointIndex != null) {
         this.pointIndex.remove(id);
      }

      if (old != null && !this.lazy) {
         this.planningSnapshot = this.planningSnapshot.without(id);
      }

      if (old != null) {
         this.changed.accept(id);
      }

      if (old == null) {
         return null;
      } else {
         this.revision++;
         this.contentRevision++;
         this.invalidate(old.chunks);
         signalLocations(old).forEach(c -> removeFrom(this.signalChunks, c, id));
         old.chunks.forEach(p -> removeFrom(this.chunkRoads, p, id));
         removeFrom(this.nodeRoads, old.record.a().asLong(), id);
         removeFrom(this.nodeRoads, old.record.b().asLong(), id);
         return old;
      }
   }

   private static void removeFrom(Map<Long, Set<UUID>> map, long key, UUID id) {
      Set<UUID> ids = map.get(key);
      if (ids != null) {
         ids.remove(id);
         if (ids.isEmpty()) {
            map.remove(key);
         }
      }
   }

   public Set<UUID> at(long pos) {
      BlockPos p = BlockPos.of(pos);
      Set<UUID> result = new HashSet<>();

      for (UUID id : this.inChunk(new ChunkPos(p).toLong())) {
         if (!this.roads.get(id).boxes(p).isEmpty()) {
            result.add(id);
         }
      }

      return result;
   }

   public boolean occupied(long pos) {
      return !this.at(pos).isEmpty();
   }

   public Set<UUID> inChunk(long chunk) {
      return this.chunkRoads.getOrDefault(chunk, Set.of());
   }

   public Set<UUID> atNode(BlockPos p) {
      return this.nodeRoads.getOrDefault(p.asLong(), Set.of());
   }

   public List<RoadIndex.Built> neighbors(RoadIndex.Built built) {
      Set<UUID> ids = new HashSet<>();

      for (long c : built.chunks) {
         ids.addAll(this.inChunk(c));
      }

      ids.remove(built.record.id());
      return ids.stream().map(this.roads::get).filter(b -> overlapXZ(b.mesh, built.mesh, 1.0)).toList();
   }

   public List<RoadIndex.Built> surfaceNeighbors(RoadIndex.Built built) {
      return this.neighbors(built)
         .stream()
         .filter(
            b -> (b.record.railway() == built.record.railway() || RailwayCrossings.any(b.mesh, built.mesh))
                  && b.mesh.min().y() <= built.mesh.max().y() + 0.2
                  && b.mesh.max().y() >= built.mesh.min().y() - 0.2
         )
         .toList();
   }

   public static boolean overlapXZ(RoadGeometry.Mesh a, RoadGeometry.Mesh b, double margin) {
      return a.min().x() <= b.max().x() + margin
         && a.max().x() + margin >= b.min().x()
         && a.min().z() <= b.max().z() + margin
         && a.max().z() + margin >= b.min().z();
   }

   public RoadIndex.Built pick(RoadGeometry.V origin, RoadGeometry.V direction, double range) {
      RoadIndex.Built best = null;
      double distance = range;

      for (RoadIndex.Built b : this.rayCandidates(origin, direction, range)) {
         double hit = RoadQueries.ray(b.mesh, origin, direction, Math.nextUp(distance));
         if (Double.isFinite(hit) && hit >= 0.0 && (hit < distance || best == null && hit <= distance)) {
            distance = hit;
            best = b;
         }
      }

      return best;
   }

   public List<RoadIndex.Built> rayCandidates(RoadGeometry.V origin, RoadGeometry.V direction, double range) {
      LinkedHashSet<UUID> ids = new LinkedHashSet<>();

      for (long chunk : rayChunks(origin, direction, range)) {
         ids.addAll(this.inChunk(chunk));
      }

      return ids.stream().map(this.roads::get).filter(Objects::nonNull).toList();
   }

   List<RoadPointIndex.Marker> lanePointCandidates(RoadGeometry.V origin, RoadGeometry.V direction, double range) {
      if (this.pointIndex != null) {
         return this.pointIndex.candidates(origin, direction, range);
      } else {
         ArrayList<RoadPointIndex.Marker> result = new ArrayList<>();

         for (RoadIndex.Built road : this.roads.values()) {
            int ordinal = 0;

            for (LanePoints.Point point : LaneTopology.metadata(road.record).points()) {
               result.add(new RoadPointIndex.Marker(road.record.id(), point, ordinal++));
            }
         }

         return result;
      }
   }

   List<UUID> laneReferences(LanePoints.Ref ref) {
      return this.pointIndex != null ? this.pointIndex.references(ref) : LaneTopology.references(this.roads.values().stream().map(b -> b.record).toList(), ref);
   }

   boolean laneReferenced(LanePoints.Ref ref) {
      return this.pointIndex != null ? this.pointIndex.referenced(ref) : !this.laneReferences(ref).isEmpty();
   }

   static Set<Long> rayChunks(RoadGeometry.V origin, RoadGeometry.V direction, double range) {
      return !(range < 0.0) && RoadGeometry.finite(origin.x(), origin.y(), origin.z(), direction.x(), direction.y(), direction.z(), range)
         ? RoadChunkTraversal.trace(origin.x(), origin.z(), direction.x(), direction.z(), range)
         : Set.of();
   }

   public VoxelShape shape(BlockPos pos) {
      long key = pos.asLong();
      VoxelShape cached = this.shapes.get(key);
      if (cached != null) {
         return cached;
      } else {
         synchronized (this) {
            cached = this.shapes.get(key);
            if (cached != null) {
               return cached;
            } else {
               List<RoadRaster.Box> boxes = new ArrayList<>();

               for (UUID id : this.inChunk(chunkAt(key))) {
                  boxes.addAll(this.roads.get(id).boxes(pos));
               }

               VoxelShape shape = RoadCollisionShape.build(boxes);
               Set<Long> members = this.shapeChunks.get(chunkAt(key));
               if (members == null) {
                  members = new HashSet<>();
                  this.shapeChunks.put(chunkAt(key), members);
               }

               members.add(key);
               this.shapes.put(key, shape);
               return shape;
            }
         }
      }
   }

   public synchronized void clear() {
      this.revision++;
      this.contentRevision++;
      this.roads.clear();
      if (this.pointIndex != null) {
         this.pointIndex.clear();
      }

      this.planningSnapshot = RoadPlanningSnapshot.empty();
      this.chunkRoads.clear();
      this.nodeRoads.clear();
      this.shapes.clear();
      this.shapeChunks.clear();
      this.signalChunks.clear();
      this.nearbySignals = List.of();
      this.signalRevision = -1L;
   }

   public static final class Built {
      public final RoadRecord record;
      public final RoadGeometry.Mesh mesh;
      public final Map<Long, List<RoadRaster.Box>> cells;
      public final Set<Long> chunks = new HashSet<>();
      public final Set<Long> lightCells = new HashSet<>();
      public final Map<Long, Double> walkTops = new HashMap<>();
      public final Set<Long> shellCells = new HashSet<>();
      public final Map<Long, RoadIndex.Built.Column> columns;
      private Map<Long, RoadIndex.Built.Column> columnData = new HashMap<>();
      private Map<Long, List<RoadRaster.Box>> cellData = new HashMap<>();
      private final Map<Long, List<RoadRaster.Box>> nearbyCells = RoadIndex.bounded(1024);
      private final Map<Long, RoadIndex.Built.Column> nearbyColumns = RoadIndex.bounded(1024);
      private final boolean deferred;
      private volatile boolean rasterized;
      public final Set<Long> clearanceCells = new AbstractSet<Long>() {
         @Override
         public boolean contains(Object key) {
            if (key instanceof Long value && Built.this.effectiveClearance() != 0.0) {
               BlockPos p = BlockPos.of(value);
               RoadIndex.Built.Column c = Built.this.clearanceColumns().get(new BlockPos(p.getX(), 0, p.getZ()).asLong());
               return c != null
                  && p.getY() >= Built.this.clearMin(c, new BlockPos(p.getX(), 0, p.getZ()).asLong())
                  && p.getY() < Built.this.clearEnd(c, new BlockPos(p.getX(), 0, p.getZ()).asLong());
            }

            return false;
         }

         @Override
         public int size() {
            if (Built.this.effectiveClearance() == 0.0) {
               return 0;
            } else {
               int n = 0;

               for (Entry<Long, RoadIndex.Built.Column> e : Built.this.clearanceColumns().entrySet()) {
                  n += Math.max(0, Built.this.clearEnd(e.getValue(), e.getKey()) - Built.this.clearMin(e.getValue(), e.getKey()));
               }

               return n;
            }
         }

         @Override
         public Iterator<Long> iterator() {
            if (Built.this.effectiveClearance() == 0.0) {
               return Collections.emptyIterator();
            } else {
               final Iterator<Entry<Long, RoadIndex.Built.Column>> source = Built.this.clearanceColumns().entrySet().iterator();
               return new Iterator<Long>() {
                  BlockPos p;
                  int y;
                  int end;

                  @Override
                  public boolean hasNext() {
                     while (this.y >= this.end && source.hasNext()) {
                        Entry<Long, RoadIndex.Built.Column> e = source.next();
                        this.p = BlockPos.of(e.getKey());
                        this.y = Built.this.clearMin(e.getValue(), e.getKey());
                        this.end = Built.this.clearEnd(e.getValue(), e.getKey());
                     }

                     return this.y < this.end;
                  }

                  public Long next() {
                     if (!this.hasNext()) {
                        throw new NoSuchElementException();
                     } else {
                        return new BlockPos(this.p.getX(), this.y++, this.p.getZ()).asLong();
                     }
                  }
               };
            }
         }
      };
      private final Map<Long, RoadIndex.Built.Column> nearbyClearance = RoadIndex.bounded(1024);
      private Map<Long, RoadIndex.Built.Column> dryColumns;
      private List<RoadGeometry.Mesh> autoTubes;
      private RoadTunnelSpace.Profiled autoProfile;
      private RoadTunnelSpace.Profiled cutProfile;
      private List<RoadAutoTunnels.RoofRegion> roofRegions;
      private List<RoadAutoTunnels.OpenRegion> openRegions;
      private List<RoadTunnelSpace.Profiled> cycleAir;
      private Boolean tunnel;
      private final Map<Long, Integer> tunnelEnds = RoadIndex.bounded(2048);
      public final List<RoadStructures.Part> signalHeads;
      public final AABB signalBounds;
      private volatile List<RoadSurface.Face> mainSignalFaces;
      private volatile List<RoadSurface.Face> rampSignalFaces;
      private RoadSignalFrames junctionSignalFrames;
      private final boolean lazy;
      private volatile RoadRaster.Local local;
      private volatile RoadGeometry.Mesh rendered;
      private Map<Long, List<RoadRaster.Box>> deckCells = new HashMap<>();

      private int clearMin(RoadIndex.Built.Column c, long key) {
         BlockPos p = BlockPos.of(key);
         double floor = c.minTop();

         for (RoadTunnelSpace.Profiled air : this.cycleAir()) {
            floor = Math.min(floor, air.floor(p.getX(), p.getZ()));
         }

         return (int)Math.floor(floor + 1.0E-7);
      }

      private Map<Long, RoadIndex.Built.Column> clearanceColumns() {
         this.ensureRaster();
         if (this.record.settings().structure() == RoadGeometry.Structure.TUNNEL) {
            return this.columnData;
         } else {
            if (this.dryColumns == null) {
               this.dryColumns = new HashMap<>(this.columnData);

               for (Entry<Long, Double> e : this.walkTops.entrySet()) {
                  BlockPos p = BlockPos.of(e.getKey());
                  long key = BlockPos.asLong(p.getX(), 0, p.getZ());
                  double y = e.getValue();
                  this.dryColumns
                     .merge(
                        key,
                        new RoadIndex.Built.Column(y, y),
                        (a, b) -> new RoadIndex.Built.Column(Math.min(a.minTop(), b.minTop()), Math.max(a.maxTop(), b.maxTop()))
                     );
               }
            }

            return this.dryColumns;
         }
      }

      private double effectiveClearance() {
         return Math.max(
            this.record.toll() == null ? (double)(this.record.clearance() == 0 ? 0 : this.record.clearance() + 1) : this.record.toll().config().clearHeight(),
            RoadInfrastructure.clearance(this.record.settings())
         );
      }

      public List<RoadGeometry.Mesh> automaticTubes() {
         if (this.autoTubes == null) {
            this.autoTubes = RoadAutoTunnels.legacyRegions(this.record.structures())
               .stream()
               .filter(r -> r.to() <= this.mesh.length() + 1.0E-6)
               .map(r -> RoadAutoTunnels.tube(this.mesh, r, 4.0))
               .toList();
         }

         return this.autoTubes;
      }

      private List<RoadAutoTunnels.RoofRegion> roofRegions() {
         if (this.roofRegions == null) {
            this.roofRegions = RoadAutoTunnels.roofRegions(this.record.structures());
         }

         return this.roofRegions;
      }

      private List<RoadAutoTunnels.OpenRegion> openRegions() {
         if (this.openRegions == null) {
            this.openRegions = RoadAutoTunnels.openRegions(this.record.structures());
         }

         return this.openRegions;
      }

      private RoadTunnelSpace.Profiled autoProfile() {
         if (this.autoProfile == null) {
            this.autoProfile = new RoadTunnelSpace.Profiled(this.mesh, this.roofRegions());
         }

         return this.autoProfile;
      }

      private RoadTunnelSpace.Profiled cutProfile() {
         if (this.cutProfile == null) {
            ArrayList<RoadAutoTunnels.RoofRegion> sections = new ArrayList<>();

            for (RoadAutoTunnels.OpenRegion r : this.openRegions()) {
               if (r.to() <= this.mesh.length() + 1.0E-6) {
                  for (double d = r.from(); d < r.to() - 1.0E-7; d++) {
                     sections.add(new RoadAutoTunnels.RoofRegion(d, Math.min(r.to(), d + 1.0), r.clearance(), r.clearance()));
                  }
               }
            }

            this.cutProfile = new RoadTunnelSpace.Profiled(this.mesh, sections);
         }

         return this.cutProfile;
      }

      private List<RoadTunnelSpace.Profiled> cycleAir() {
         if (this.cycleAir == null) {
            this.cycleAir = RoadsideBypass.air(this.mesh);
         }

         return this.cycleAir;
      }

      public boolean hasTunnel() {
         if (this.tunnel == null) {
            this.tunnel = this.record.settings().structure() == RoadGeometry.Structure.TUNNEL
               || !this.automaticTubes().isEmpty()
               || !this.roofRegions().isEmpty()
               || !this.openRegions().isEmpty()
               || !this.cycleAir().isEmpty()
               || this.record.structures().stream().anyMatch(p -> p.model().equals("sr:junction_retaining_wall"));
         }

         return this.tunnel;
      }

      public boolean cycleBodyAt(BlockPos p) {
         return this.mesh.settings().options().streetscape().crossings().isEmpty()
            ? false
            : this.prepareCollision()
               .structureAt(
                  new RoadRaster.Cell(p.getX(), p.getY(), p.getZ()), part -> part.model().equals("sr:cycle_wall") || part.model().equals("sr:cycle_bypass")
               );
      }

      public boolean tunnelAt(BlockPos p) {
         for (RoadTunnelSpace.Profiled air : this.cycleAir()) {
            if (air.intersects(p.getX(), p.getY(), p.getZ(), 1.0)) {
               return true;
            }
         }

         if (this.record.settings().structure() == RoadGeometry.Structure.TUNNEL) {
            return RoadTunnelSpace.intersects(this.mesh, p.getX(), p.getY(), p.getZ(), 1.0);
         } else if (this.autoProfile().intersects(p.getX(), p.getY(), p.getZ(), 1.0)) {
            return true;
         } else {
            for (RoadGeometry.Mesh tube : this.automaticTubes()) {
               if (RoadTunnelSpace.intersects(tube, p.getX(), p.getY(), p.getZ(), 1.0)) {
                  return true;
               }
            }

            return false;
         }
      }

      private synchronized int clearEnd(RoadIndex.Built.Column c, long key) {
         if (this.record.settings().structure() == RoadGeometry.Structure.TUNNEL) {
            Integer previous = this.tunnelEnds.get(key);
            if (previous != null) {
               return previous;
            } else {
               BlockPos p = BlockPos.of(key);
               int result = (int)Math.ceil(RoadInfrastructure.excavationTop(this.mesh, p.getX(), p.getZ(), c.maxTop()) - 1.0E-7);
               this.tunnelEnds.put(key, result);
               return result;
            }
         } else {
            BlockPos p = BlockPos.of(key);
            double cycle = RoadsideBypass.ceiling(this.mesh, (double)p.getX() + 0.5, (double)p.getZ() + 0.5, c.maxTop());
            if (Double.isFinite(cycle)) {
               return (int)Math.ceil(cycle - 1.0E-7);
            } else {
               double roof = this.autoProfile().ceiling(p.getX(), p.getZ());

               for (RoadGeometry.Mesh tube : this.automaticTubes()) {
                  roof = Math.max(roof, RoadTunnelSpace.ceiling(tube, p.getX(), p.getZ()));
               }

               roof = Math.max(roof, this.cutProfile().ceiling(p.getX(), p.getZ()));
               return (int)Math.ceil((Double.isFinite(roof) ? roof : c.maxTop() + this.effectiveClearance()) - 1.0E-7);
            }
         }
      }

      public List<RoadSurface.Face> signalFaces(boolean mainGreen) {
         if (this.signalHeads.isEmpty()) {
            return List.of();
         } else {
            if (this.mainSignalFaces == null) {
               this.rampSignalFaces = RoadSignals.lights(this.signalHeads, false);
               this.mainSignalFaces = RoadSignals.lights(this.signalHeads, true);
            }

            return mainGreen ? this.mainSignalFaces : this.rampSignalFaces;
         }
      }

      public List<RoadSurface.Face> signalFaces(long time) {
         if (this.record.junction() == null) {
            return this.signalFaces(RoadSignals.mainGreen(time));
         } else {
            if (this.junctionSignalFrames == null) {
               this.junctionSignalFrames = new RoadSignalFrames(
                  this.signalHeads.stream().map(this.record.junction()::headModel).toList(),
                  this.record.junction()::headState,
                  this.record.junction().signals().resolutionTicks()
               );
            }

            return this.junctionSignalFrames.at(time);
         }
      }

      public Built(RoadRecord record) {
         this(record, false);
      }

      public Built(RoadRecord record, boolean lazy) {
         this(record, lazy, true, null);
      }

      public static RoadIndex.Built loading(RoadRecord record) {
         return new RoadIndex.Built(record, false, true, null);
      }

      public boolean rasterized() {
         return this.rasterized;
      }

      public RoadIndex.Built lanePoints(LanePoints.Data value) {
         LanePoints.Data before = this.record.settings().options().lanePoints();
         if (Objects.equals(before.link(), value.link())
            && before.openings().equals(value.openings())
            && before.additions().equals(value.additions())
            && before.cuts().equals(value.cuts())
            && LaneMerge.sameDefinitions(before, value)) {
            RoadIndex.Built next = new RoadIndex.Built(this.record.withLanePoints(value), this.lazy, true, this);
            next.local = this.local;
            next.nearbyCells.putAll(this.nearbyCells);
            next.nearbyColumns.putAll(this.nearbyColumns);
            return next;
         } else {
            throw new IllegalArgumentException("几何变更必须通过道路建造流程");
         }
      }

      public RoadIndex.Built caps(int mask) {
         boolean sameGeometry = this.record.endCaps() == mask || !this.record.alignment().isEmpty();
         return new RoadIndex.Built(this.record.caps(mask), this.lazy, this.deferred, sameGeometry ? this : null);
      }

      public RoadIndex.Built structures(List<RoadStructures.Part> parts) {
         return this.record.structures().equals(parts) ? this : new RoadIndex.Built(this.record.structures(parts), this.lazy, true, this);
      }

      public RoadIndex.Built planned(RoadRecord next) {
         return next == this.record ? this : new RoadIndex.Built(next, this.lazy, true, this);
      }

      public RoadIndex.Built phase(RoadFurniture.Phase phase) {
         return Objects.equals(this.record.furniturePhase(), phase)
            ? this
            : new RoadIndex.Built(this.record.furniturePhase(phase), this.lazy, this.deferred, this);
      }

      private Built(RoadRecord record, boolean lazy, boolean deferred, RoadIndex.Built reuse) {
         if (reuse != null && !record.settings().equals(reuse.record.settings())) {
            reuse = null;
         }

         this.record = record;
         this.signalHeads = record.structures().stream().filter(RoadSignals::signal).toList();
         AABB signalBox = null;

         for (RoadStructures.Part head : this.signalHeads) {
            RoadGeometry.V a = head.a();
            RoadGeometry.V b = head.b();
            AABB box = new AABB(
                  Math.min(a.x(), b.x()),
                  Math.min(a.y(), b.y()),
                  Math.min(a.z(), b.z()),
                  Math.max(a.x(), b.x()),
                  Math.max(a.y(), b.y()) + head.height(),
                  Math.max(a.z(), b.z())
               )
               .inflate(Math.max(1.0, head.width()));
            signalBox = signalBox == null ? box : signalBox.minmax(box);
         }

         this.signalBounds = signalBox;
         this.lazy = lazy;
         this.deferred = deferred;
         this.cells = (Map<Long, List<RoadRaster.Box>>)(lazy ? this.nearbyCells : new DeferredMap<>(() -> {
            this.ensureRaster();
            return this.cellData;
         }));
         this.columns = (Map<Long, RoadIndex.Built.Column>)(lazy ? this.columnData : new DeferredMap<>(() -> {
            this.ensureRaster();
            return this.columnData;
         }));
         this.mesh = reuse == null ? record.mesh() : reuse.mesh;
         this.chunks.addAll(RoadCoverage.chunks(this.mesh, 0.0));

         for (RoadStructures.Part part : record.structures()) {
            double pad = part.halfExtent();

            for (int x = (int)Math.floor(Math.min(part.a().x(), part.b().x()) - pad) >> 4;
               x <= (int)Math.floor(Math.max(part.a().x(), part.b().x()) + pad) >> 4;
               x++
            ) {
               for (int z = (int)Math.floor(Math.min(part.a().z(), part.b().z()) - pad) >> 4;
                  z <= (int)Math.floor(Math.max(part.a().z(), part.b().z()) + pad) >> 4;
                  z++
               ) {
                  this.chunks.add(ChunkPos.asLong(x, z));
               }
            }

            if (!lazy && !deferred && part.luminous()) {
               RoadRaster.structures(List.of(part), null).keySet().forEach(c -> this.lightCells.add(new BlockPos(c.x(), c.y(), c.z()).asLong()));
            }
         }

         if (!lazy && !deferred) {
            if (reuse != null && reuse.rasterized) {
               this.cellData.putAll(reuse.deckCells);
               this.columnData.putAll(reuse.columnData);
            }

            this.buildRaster(reuse != null && reuse.rasterized);
         }
      }

      private synchronized void ensureRaster() {
         RoadEditRasters.used(this);
         if (!this.rasterized) {
            this.buildRaster(false);
         }
      }

      public synchronized void releaseEditRaster() {
         if (!this.lazy && (this.rasterized || !this.cellData.isEmpty() || !this.columnData.isEmpty() || !this.deckCells.isEmpty())) {
            this.cellData = new HashMap<>();
            this.deckCells = new HashMap<>();
            this.columnData = new HashMap<>();
            this.nearbyCells.clear();
            this.rasterized = false;
            this.dryColumns = null;
            this.walkTops.clear();
            this.nearbyClearance.clear();
         }
      }

      private void buildRaster(boolean reused) {
         RoadEditRasters.used(this);
         if (!reused) {
            (Boolean.getBoolean("sr.raster.valueCache") ? RoadRaster.cachedRaster(this.mesh) : RoadRaster.raster(this.mesh))
               .forEach(
                  (cell, boxes) -> {
                     BlockPos p = new BlockPos(cell.x(), cell.y(), cell.z());
                     this.cellData.put(p.asLong(), (List<RoadRaster.Box>)boxes);
                     long column = new BlockPos(cell.x(), 0, cell.z()).asLong();

                     for (RoadRaster.Box box : boxes) {
                        double low = (double)cell.y() + box.y0() + this.record.settings().thickness();
                        double high = (double)cell.y() + box.y1();
                        RoadIndex.Built.Column previous = this.columnData.get(column);
                        this.columnData
                           .put(
                              column,
                              previous == null
                                 ? new RoadIndex.Built.Column(low, high)
                                 : new RoadIndex.Built.Column(Math.min(previous.minTop, low), Math.max(previous.maxTop, high))
                           );
                     }
                  }
               );
         }

         this.deckCells.putAll(this.cellData);
         RoadStructureRaster.Result furniture = RoadStructureRaster.build(this.record.structures());
         furniture.cells().forEach((cx, b) -> this.cellData.compute(BlockPos.asLong(cx.x(), cx.y(), cx.z()), (key, existing) -> {
               ArrayList<RoadRaster.Box> combined = new ArrayList<>(existing == null ? List.of() : existing);
               combined.addAll(b);
               return combined;
            }));
         if (this.deferred) {
            for (RoadRaster.Cell c : furniture.lights()) {
               this.lightCells.add(BlockPos.asLong(c.x(), c.y(), c.z()));
            }
         }

         for (RoadRaster.Cell c : furniture.shells()) {
            this.shellCells.add(BlockPos.asLong(c.x(), c.y(), c.z()));
         }

         this.walkTops.clear();
         furniture.walkTops().forEach((cx, top) -> this.walkTops.put(BlockPos.asLong(cx.x(), cx.y(), cx.z()), top));
         this.nearbyCells.clear();
         this.rasterized = true;
      }

      public RoadRaster.Local prepareCollision() {
         RoadRaster.Local value = this.local;
         if (value == null) {
            synchronized (this) {
               if (this.local == null) {
                  this.local = new RoadRaster.Local(this.mesh, this.record.structures());
               }

               value = this.local;
            }
         }

         return value;
      }

      public synchronized RoadIndex.Built.Column column(BlockPos pos) {
         long key = BlockPos.asLong(pos.getX(), 0, pos.getZ());
         if (this.rasterized) {
            return this.columnData.get(key);
         } else if (this.nearbyColumns.containsKey(key)) {
            return this.nearbyColumns.get(key);
         } else {
            RoadIndex.Built.Column result = null;

            for (Entry<RoadRaster.Cell, List<RoadRaster.Box>> entry : this.prepareCollision().deckColumn(pos.getX(), pos.getZ()).entrySet()) {
               for (RoadRaster.Box box : entry.getValue()) {
                  double low = (double)entry.getKey().y() + box.y0() + this.record.settings().thickness();
                  double high = (double)entry.getKey().y() + box.y1();
                  result = result == null
                     ? new RoadIndex.Built.Column(low, high)
                     : new RoadIndex.Built.Column(Math.min(result.minTop(), low), Math.max(result.maxTop(), high));
               }
            }

            this.nearbyColumns.put(key, result);
            return result;
         }
      }

      public synchronized boolean clearanceAt(BlockPos p) {
         if (this.effectiveClearance() == 0.0) {
            return false;
         } else if (this.rasterized) {
            return this.clearanceCells.contains(p.asLong());
         } else {
            long key = BlockPos.asLong(p.getX(), 0, p.getZ());
            RoadIndex.Built.Column c;
            if (this.nearbyClearance.containsKey(key)) {
               c = this.nearbyClearance.get(key);
            } else {
               if (this.record.settings().structure() == RoadGeometry.Structure.TUNNEL) {
                  c = this.column(p);
               } else {
                  c = this.column(p);

                  for (Entry<RoadRaster.Cell, List<RoadRaster.Box>> e : this.prepareCollision()
                     .structureColumn(p.getX(), p.getZ(), a -> RoadsideBypass.walkway(a))
                     .entrySet()) {
                     double top = e.getValue().stream().mapToDouble(b -> (double)e.getKey().y() + b.y1()).max().orElse((double)e.getKey().y());
                     c = c == null ? new RoadIndex.Built.Column(top, top) : new RoadIndex.Built.Column(Math.min(c.minTop(), top), Math.max(c.maxTop(), top));
                  }
               }

               this.nearbyClearance.put(key, c);
            }

            return c != null && p.getY() >= this.clearMin(c, key) && p.getY() < this.clearEnd(c, key);
         }
      }

      public double walkTopAt(BlockPos p) {
         if (this.rasterized) {
            return this.walkTops.getOrDefault(p.asLong(), Double.NEGATIVE_INFINITY);
         } else {
            RoadRaster.Cell cell = new RoadRaster.Cell(p.getX(), p.getY(), p.getZ());
            Map<RoadRaster.Cell, List<RoadRaster.Box>> column = this.prepareCollision().structureColumn(p.getX(), p.getZ(), a -> RoadsideBypass.walkway(a));
            return column.getOrDefault(cell, List.of()).stream().mapToDouble(b -> (double)p.getY() + b.y1()).max().orElse(Double.NEGATIVE_INFINITY);
         }
      }

      public boolean lightAt(BlockPos p) {
         return this.rasterized
            ? this.lightCells.contains(p.asLong())
            : this.prepareCollision().structureAt(new RoadRaster.Cell(p.getX(), p.getY(), p.getZ()), RoadStructures.Part::luminous);
      }

      public boolean shellAt(BlockPos p) {
         return this.rasterized
            ? this.shellCells.contains(p.asLong())
            : this.prepareCollision().structureAt(new RoadRaster.Cell(p.getX(), p.getY(), p.getZ()), part -> part.material() == RoadStructures.Material.TUNNEL);
      }

      public RoadGeometry.Mesh renderMesh() {
         if (this.record.junction() == null && this.record.toll() == null) {
            RoadGeometry.Mesh value = this.rendered;
            if (value == null) {
               synchronized (this) {
                  if (this.rendered == null) {
                     this.rendered = RoadRenderMesh.simplify(this.mesh);
                  }

                  value = this.rendered;
               }
            }

            return value;
         } else {
            return this.mesh;
         }
      }

      public List<RoadRaster.Box> boxes(BlockPos pos) {
         if (this.rasterized) {
            return this.cellData.getOrDefault(pos.asLong(), List.of());
         } else {
            synchronized (this) {
               List<RoadRaster.Box> cached = this.nearbyCells.get(pos.asLong());
               if (cached != null) {
                  return cached;
               } else {
                  RoadRaster.Cell c = new RoadRaster.Cell(pos.getX(), pos.getY(), pos.getZ());
                  List<RoadRaster.Box> boxes = this.prepareCollision().boxes(c);
                  this.nearbyCells.put(pos.asLong(), boxes);
                  return boxes;
               }
            }
         }
      }

      public static record Column(double minTop, double maxTop) {
      }
   }
}
