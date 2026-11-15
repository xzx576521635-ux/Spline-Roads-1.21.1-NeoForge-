package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.compat.mtr.MtrRailAccess;
import com.sora.splineroads.core.ElevatedAccess;
import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RailbedProfile;
import com.sora.splineroads.core.RailwayCrossings;
import com.sora.splineroads.core.RoadAprons;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadBoundsIndex;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadConnectionChecks;
import com.sora.splineroads.core.RoadEndpointSections;
import com.sora.splineroads.core.RoadFurniture;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadLaneLines;
import com.sora.splineroads.core.RoadMedianAnchor;
import com.sora.splineroads.core.RoadNoses;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRaster;
import com.sora.splineroads.core.RoadSolidOverlap;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadTimings;
import com.sora.splineroads.core.RoadTransitions;
import com.sora.splineroads.core.RoadTunnelFit;
import com.sora.splineroads.core.RoadWaterPolicy;
import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.core.StreetLampClearance;
import com.sora.splineroads.core.TollPlanner;
import com.sora.splineroads.core.TollPlaza;
import com.sora.splineroads.net.RoadNetwork;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class RoadData extends SavedData {
   public final RoadIndex index = new RoadIndex();
   public final RailbedStore railbeds = new RailbedStore();
   public final SmartSignRoads smartSigns = new SmartSignRoads(this);
   private ServerLevel owningLevel;
   private Set<UUID> confirmedLaneDeletes = Set.of();
   final Map<UUID, RoadRecord> streets = new LinkedHashMap<>();
   final Map<UUID, CompoundTag> junctions = new HashMap<>();
   final Map<UUID, CompoundTag> interchanges = new HashMap<>();
   private final Map<Long, Set<UUID>> islandRepair = new HashMap<>();
   private final Map<Long, List<Long>> groundJoinRepair = new HashMap<>();
   private final Map<Long, List<RoadStructures.Part>> retiredSignsByChunk = new HashMap<>();
   private Long2ObjectOpenHashMap<BlockState> sidewalkPlaced = new Long2ObjectOpenHashMap();
   private final Map<Long, List<Long>> apronWidthRepair = new HashMap<>();
   private final Map<Long, List<RoadStructures.Part>> structureRepair103 = new HashMap<>();
   private final Set<UUID> supportRepair104 = new LinkedHashSet<>();
   private final Set<UUID> attemptedSupportRepair104 = new HashSet<>();
   private Set<Long> apronPlaced = new LongOpenHashSet();
   private final Long2ObjectOpenHashMap<BlockState> terrainOriginal = new Long2ObjectOpenHashMap();
   private final Long2ObjectOpenHashMap<BlockState> terrainFill = new Long2ObjectOpenHashMap();
   private final Map<Long, Set<UUID>> repairedChunks = new LinkedHashMap<Long, Set<UUID>>(128, 0.75F, true) {
      @Override
      protected boolean removeEldestEntry(Entry<Long, Set<UUID>> e) {
         return this.size() > 4096;
      }
   };
   private boolean exactSplit;
   private final Set<Long> deckRepaired = new HashSet<>();
   static Consumer<String> splitFault = stage -> {
   };
   static Consumer<String> editFault = stage -> {
   };
   private final LinkedHashMap<UUID, RoadData.PreparedEntry> preparedEdits = new LinkedHashMap<>();
   private static final Set<RoadData> PREVIEW_DATA = Collections.newSetFromMap(new WeakHashMap<>());

   BlockState railbedOriginal(long key) {
      return (BlockState)this.terrainOriginal.getOrDefault(key, Blocks.AIR.defaultBlockState());
   }

   RoadData.StreetLedger streetLedger(long key) {
      return new RoadData.StreetLedger(
         (BlockState)this.terrainFill.get(key),
         (BlockState)this.terrainOriginal.get(key),
         (BlockState)this.sidewalkPlaced.get(key),
         this.apronPlaced.contains(key)
      );
   }

   void streetLedger(long key, RoadData.StreetLedger value) {
      if (value.fill() == null) {
         this.terrainFill.remove(key);
      } else {
         this.terrainFill.put(key, value.fill());
      }

      if (value.original() == null) {
         this.terrainOriginal.remove(key);
      } else {
         this.terrainOriginal.put(key, value.original());
      }

      if (value.sidewalk() == null) {
         this.sidewalkPlaced.remove(key);
      } else {
         this.sidewalkPlaced.put(key, value.sidewalk());
      }

      if (value.apron()) {
         this.apronPlaced.add(key);
      } else {
         this.apronPlaced.remove(key);
      }
   }

   CompoundTag streetObjects(Set<UUID> ids, Set<UUID> centers) {
      CompoundTag t = new CompoundTag();
      ListTag roads = new ListTag();
      ListTag logical = new ListTag();
      ListTag junctionsTag = new ListTag();
      ids.stream().sorted().forEach(id -> {
         RoadIndex.Built r = this.index.roads.get(id);
         if (r != null) {
            roads.add(r.record.save());
         }

         if (this.streets.containsKey(id)) {
            logical.add(this.streets.get(id).save());
         }
      });
      centers.stream().sorted().forEach(id -> {
         if (this.junctions.containsKey(id)) {
            junctionsTag.add(this.junctions.get(id).copy());
         }
      });
      t.put("Roads", roads);
      t.put("Streets", logical);
      t.put("Centers", junctionsTag);
      return t;
   }

   void restoreStreetObjects(ServerLevel level, Set<UUID> ids, Set<UUID> centers, CompoundTag snapshot) {
      for (UUID id : ids) {
         this.index.remove(id);
         this.streets.remove(id);
      }

      centers.forEach(this.junctions::remove);

      for (Tag value : snapshot.getList("Roads", 10)) {
         RoadRecord r = RoadRecord.load((CompoundTag)value);
         this.index.put(RoadIndex.Built.loading(r));
      }

      for (Tag value : snapshot.getList("Streets", 10)) {
         RoadRecord r = RoadRecord.load((CompoundTag)value);
         this.streets.put(r.id(), r);
      }

      for (Tag value : snapshot.getList("Centers", 10)) {
         CompoundTag t = (CompoundTag)value;
         this.junctions.put(t.getUUID("Id"), t.copy());
      }

      for (UUID id : ids) {
         RoadIndex.Built r = this.index.roads.get(id);
         if (r == null) {
            RoadNetwork.broadcastDelete(level, id);
         } else {
            RoadNetwork.broadcastRoad(level, r.record);
         }
      }

      this.setDirty();
   }

   static void restoreStreetBlocks(ServerLevel level, Map<Long, BlockState> states, Map<Long, CompoundTag> entities) {
      restoreBlocks(level, states, entities, failure -> {
         throw failure;
      }, true);
   }

   static void restorePlannerBlocks(ServerLevel level, Map<Long, BlockState> states, Map<Long, CompoundTag> entities) {
      restoreBlocks(level, states, entities, failure -> {
         throw failure;
      }, false);
   }

   void railbedOwns(long key) {
      this.terrainOriginal.remove(key);
      this.terrainFill.remove(key);
   }

   public RoadData() {
      this.index.changed = this.smartSigns::changed;
   }

   RoadStructures.Ground terrainGround() {
      return this.owningLevel == null
         ? null
         : RoadTerrain.read(this.owningLevel, this.terrainFill, this.terrainOriginal, new RoadTerrain.Cache(this.terrainFill, this.terrainOriginal));
   }

   RoadStructures.Ground railbedTerrain(Map<Long, BlockState> prior) {
      Long2ObjectOpenHashMap<BlockState> originals = new Long2ObjectOpenHashMap(this.terrainOriginal);

      for (RailbedStore.Entry entry : this.railbeds.entries.values()) {
         entry.original.forEach(originals::putIfAbsent);
      }

      originals.putAll(prior);
      return RoadTerrain.read(this.owningLevel, this.terrainFill, originals, new RoadTerrain.Cache(this.terrainFill, originals));
   }

   boolean withinHeight(RoadGeometry.Mesh mesh) {
      return this.owningLevel == null
         || mesh.min().y() - mesh.settings().thickness() >= (double)this.owningLevel.getMinBuildHeight()
            && mesh.max().y() + 4.0 < (double)this.owningLevel.getMaxBuildHeight();
   }

   int minimumBuildHeight() {
      return this.owningLevel == null ? Integer.MIN_VALUE : this.owningLevel.getMinBuildHeight();
   }

   void removeWithDependents(ServerLevel level, ServerPlayer player, UUID id, Set<UUID> dependents) {
      for (UUID v : dependents) {
         if (player != null) {
            requireOwner(player, this.index.roads.get(v).record.owner());
         }
      }

      this.confirmedLaneDeletes = Set.copyOf(dependents);

      try {
         this.remove(level, player, id);
      } finally {
         this.confirmedLaneDeletes = Set.of();
      }
   }

   public RoadRecord editableRoad(UUID id) {
      RoadRecord street = this.streets.get(id);
      RoadIndex.Built built = this.index.roads.get(id);
      if (street != null && built != null && !LaneTopology.metadata(built.record).access().isEmpty()) {
         return street.withLanePoints(ElevatedAccessRoads.logicalLanes(street, built.record));
      } else {
         return street != null ? street : (built == null ? null : built.record);
      }
   }

   public static RoadData get(ServerLevel level) {
      RoadData data = (RoadData)level.getDataStorage().computeIfAbsent(new Factory(RoadData::new, (t, prov) -> load((CompoundTag)t), null), "splineroads");
      data.owningLevel = level;
      return data;
   }

   public static RoadData load(CompoundTag root) {
      if (root.getInt("Version") > 71) {
         throw new IllegalStateException("Spline Roads save is newer than this mod");
      } else if (root.getInt("Version") < 71) {
         throw new IllegalStateException("Spline Roads 2.0 requires a new save");
      } else {
         long loadStarted = System.nanoTime();
         RoadData data = new RoadData();
         data.railbeds.load(root.getCompound("Railbeds"));
         Map<CompoundTag, BlockState> palette = new HashMap<>();

         for (Tag t : root.getList("Roads", 10)) {
            RoadRecord r = RoadRecord.load((CompoundTag)t);
            if (r.toll() != null && root.getInt("Version") < 48) {
               List<RoadStructures.Part> oldParts = r.structures().stream().filter(p -> p.model() != null && p.model().startsWith("sr:toll_")).toList();
               ArrayList<RoadStructures.Part> parts = new ArrayList<>(r.structures());
               parts.removeAll(oldParts);
               parts.addAll(TollPlanner.plan(r.toll()).structures());

               for (RoadStructures.Part part : oldParts) {
                  double pad = part.halfExtent();

                  for (int x = (int)Math.floor(Math.min(part.a().x(), part.b().x()) - pad) >> 4;
                     x <= (int)Math.floor(Math.max(part.a().x(), part.b().x()) + pad) >> 4;
                     x++
                  ) {
                     for (int z = (int)Math.floor(Math.min(part.a().z(), part.b().z()) - pad) >> 4;
                        z <= (int)Math.floor(Math.max(part.a().z(), part.b().z()) + pad) >> 4;
                        z++
                     ) {
                        data.retiredSignsByChunk.computeIfAbsent(ChunkPos.asLong(x, z), k -> new ArrayList<>()).add(part);
                     }
                  }
               }

               r = r.structures(parts);
               data.setDirty();
            }

            JunctionPlanner.Ref ref = r.junction();
            if (ref != null && ref.geometryVersion() == 42 && ref.spec().kind() == JunctionSpec.Kind.ROUNDABOUT) {
               JunctionPlanner.Ref next = new JunctionPlanner.Ref(ref.spec(), ref.piece(), 43);
               ArrayList<RoadStructures.Part> parts = new ArrayList<>(r.structures());
               if (ref.get().arm() < 0 && ref.spec().greenIsland()) {
                  List<RoadStructures.Part> oldIsland = ref.get()
                     .structures()
                     .stream()
                     .filter(
                        p -> (p.material() == RoadStructures.Material.SOIL || p.material() == RoadStructures.Material.GREEN)
                              && p.a().sub(ref.spec().center()).horizontalLength() < ref.spec().islandRadius()
                     )
                     .toList();
                  parts.removeAll(oldIsland);
                  parts.addAll(next.get().structures().stream().filter(p -> p.model().startsWith("sr:roundabout_")).toList());
               }

               r = r.junction(r.assembly(), next).structures(parts);
               RoadIndex.Built rebuilt = RoadIndex.Built.loading(r);
               if (ref.get().arm() < 0 && ref.spec().greenIsland()) {
                  for (long c : rebuilt.chunks) {
                     data.islandRepair.computeIfAbsent(c, k -> new HashSet<>()).add(r.id());
                  }
               }

               data.index.put(rebuilt);
               data.setDirty();
            } else {
               data.index.put(RoadIndex.Built.loading(r));
            }
         }

         if (root.getInt("Version") < 52) {
            for (RoadIndex.Built built : List.copyOf(data.index.roads.values())) {
               RoadRecord record = built.record;
               List<RoadStructures.Part> old = record.structures();
               ArrayList<RoadStructures.Part> fresh = new ArrayList<>(RoadStructures.repairJoinedPlanting(built.mesh, old));
               if (record.toll() != null && !record.toll().config().name().isEmpty()) {
                  fresh.removeIf(p -> p.model().startsWith("sr:toll_model_name_"));
                  fresh.addAll(TollPlanner.plan(record.toll()).structures().stream().filter(p -> p.model().startsWith("sr:toll_model_name_")).toList());
               }

               if (record.settings().structure() == RoadGeometry.Structure.TUNNEL && record.settings().options().cycleRail()) {
                  final List<RoadIndex.Built> nearby = data.index.surfaceNeighbors(built);
                  var ground = new RoadStructures.Ground() {
                     @Override
                     public double top(double x, double z, double y) {
                        return Double.NaN;
                     }

                     @Override
                     public boolean blocked(RoadStructures.Part part) {
                        return false;
                     }

                     @Override
                     public boolean joined(RoadGeometry.V point) {
                        return nearby.stream().anyMatch(n -> RoadQueries.contains(n.mesh, point, 0.05, 0.2));
                     }
                  };
                  ArrayList<RoadStructures.Part> rails = new ArrayList<>();
                  RoadStructures.cycleFurniture(built.mesh, ground, rails);
                  HashSet<RoadStructures.Part> existing = new HashSet<>(fresh);

                  for (RoadStructures.Part rail : rails) {
                     if (existing.add(rail)) {
                        fresh.add(rail);
                     }
                  }
               }

               HashSet<RoadStructures.Part> oldSet = new HashSet<>(old);
               HashSet<RoadStructures.Part> nextSet = new HashSet<>(fresh);
               ArrayList<RoadStructures.Part> delta = new ArrayList<>();

               for (RoadStructures.Part part : oldSet) {
                  if (!nextSet.contains(part)) {
                     delta.add(part);
                  }
               }

               for (RoadStructures.Part partx : nextSet) {
                  if (!oldSet.contains(partx)) {
                     delta.add(partx);
                  }
               }

               if (!delta.isEmpty()) {
                  data.index.put(RoadIndex.Built.loading(record.structures(fresh)));

                  for (RoadStructures.Part partxx : delta) {
                     double pad = partxx.halfExtent();

                     for (int x = (int)Math.floor(Math.min(partxx.a().x(), partxx.b().x()) - pad) >> 4;
                        x <= (int)Math.floor(Math.max(partxx.a().x(), partxx.b().x()) + pad) >> 4;
                        x++
                     ) {
                        for (int z = (int)Math.floor(Math.min(partxx.a().z(), partxx.b().z()) - pad) >> 4;
                           z <= (int)Math.floor(Math.max(partxx.a().z(), partxx.b().z()) + pad) >> 4;
                           z++
                        ) {
                           data.structureRepair103.computeIfAbsent(ChunkPos.asLong(x, z), k -> new ArrayList<>()).add(partxx);
                        }
                     }
                  }

                  data.setDirty();
               }
            }
         }

         for (Tag value : root.getList("StructureRepair103", 10)) {
            CompoundTag tag = (CompoundTag)value;
            data.structureRepair103
               .computeIfAbsent(tag.getLong("Chunk"), k -> new ArrayList<>())
               .addAll(RoadRecord.unpackStructures(tag.getByteArray("Parts")));
         }

         Predicate<RoadGeometry.V> sight = JunctionSignRoads.lampClearance(List.copyOf(data.index.roads.values()));

         for (RoadIndex.Built built : List.copyOf(data.index.roads.values())) {
            List<RoadStructures.Part> removed = StreetLampClearance.removed(built.record.structures(), sight);
            if (!removed.isEmpty()) {
               for (RoadStructures.Part partxx : removed) {
                  double pad = partxx.halfExtent();

                  for (int x = (int)Math.floor(Math.min(partxx.a().x(), partxx.b().x()) - pad) >> 4;
                     x <= (int)Math.floor(Math.max(partxx.a().x(), partxx.b().x()) + pad) >> 4;
                     x++
                  ) {
                     for (int z = (int)Math.floor(Math.min(partxx.a().z(), partxx.b().z()) - pad) >> 4;
                        z <= (int)Math.floor(Math.max(partxx.a().z(), partxx.b().z()) + pad) >> 4;
                        z++
                     ) {
                        data.retiredSignsByChunk.computeIfAbsent(ChunkPos.asLong(x, z), k -> new ArrayList<>()).add(partxx);
                     }
                  }
               }

               data.index.put(RoadIndex.Built.loading(built.record.structures(built.record.structures().stream().filter(p -> !removed.contains(p)).toList())));
            }
         }

         for (Tag value : root.getList("RetiredSignCleanup", 10)) {
            CompoundTag tag = (CompoundTag)value;
            data.retiredSignsByChunk
               .computeIfAbsent(tag.getLong("Chunk"), k -> new ArrayList<>())
               .addAll(RoadRecord.unpackStructures(tag.getByteArray("Parts")));
         }

         RoadBlockStorage.read(root.getList(root.contains("TerrainFillPalette") ? "TerrainFillPalette" : "OriginalPalette", 10), data.terrainFill);
         RoadBlockStorage.read(root.getList("SidewalkPalette", 10), data.sidewalkPlaced);

         for (long key : root.getLongArray("RoadAprons")) {
            if (data.sidewalkPlaced.containsKey(key)) {
               data.apronPlaced.add(key);
            }
         }

         HashSet<Long> pendingAprons = new HashSet<>();

         for (long c : root.getLongArray("ApronWidthRepair")) {
            pendingAprons.add(c);
         }

         for (long keyx : data.apronPlaced) {
            long c = new ChunkPos(BlockPos.of(keyx)).toLong();
            if (root.getInt("Version") < 51 || pendingAprons.contains(c)) {
               data.apronWidthRepair.computeIfAbsent(c, k -> new ArrayList<>()).add(keyx);
            }
         }

         if (!data.apronWidthRepair.isEmpty()) {
            data.setDirty();
         }

         RoadBlockStorage.read(root.getList("TerrainOriginalPalette", 10), data.terrainOriginal);

         for (Tag entry : root.getList("Interchanges", 10)) {
            CompoundTag t = (CompoundTag)entry;
            data.interchanges.put(t.getUUID("Id"), t.copy());
         }

         for (Tag entry : root.getList("Junctions", 10)) {
            CompoundTag t = (CompoundTag)entry;
            data.junctions.put(t.getUUID("Id"), t.copy());
         }

         for (Tag t : root.getList("LogicalStreets", 10)) {
            RoadRecord rx = RoadRecord.load((CompoundTag)t);
            data.streets.put(rx.id(), rx);
         }

         if (root.getInt("Version") < 18) {
            for (Tag entry : root.getList("SidewalkBlocks", 10)) {
               CompoundTag t = (CompoundTag)entry;
               data.sidewalkPlaced
                  .put(t.getLong("Pos"), palette.computeIfAbsent(t.getCompound("State"), v -> NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), v)));
            }
         }

         if (!data.retiredSignsByChunk.isEmpty()) {
            data.setDirty();
         }

         for (Tag value : root.getList("IslandRepair", 10)) {
            CompoundTag t = (CompoundTag)value;
            if (t.hasUUID("Road")) {
               data.islandRepair.computeIfAbsent(t.getLong("Chunk"), k -> new HashSet<>()).add(t.getUUID("Road"));
            }
         }

         HashSet<Long> pendingGround = new HashSet<>();

         for (long c : root.getLongArray("GroundJoinRepair")) {
            pendingGround.add(c);
         }

         LongIterator var55 = data.terrainOriginal.keySet().iterator();

         while (var55.hasNext()) {
            long keyxx = (Long)var55.next();
            long c = new ChunkPos(BlockPos.of(keyxx)).toLong();
            if (root.getInt("Version") < 49 || pendingGround.contains(c)) {
               data.groundJoinRepair.computeIfAbsent(c, k -> new ArrayList<>()).add(keyxx);
            }
         }

         if (!data.groundJoinRepair.isEmpty()) {
            data.setDirty();
         }

         LaneTopology.initialize(data);

         for (Tag valuex : root.getList("SupportRepair104", 10)) {
            CompoundTag t = (CompoundTag)valuex;
            if (t.hasUUID("Road") && data.index.roads.containsKey(t.getUUID("Road"))) {
               data.supportRepair104.add(t.getUUID("Road"));
            }
         }

         data.smartSigns.load(root.getList("SmartSigns", 10));
         if (data.railbeds.pending.isEmpty()) {
            data.adoptRailbeds(null);
         }

         RoadTimings.reportLoad(loadStarted, data.index.roads.size());
         return data;
      }
   }

   public CompoundTag save(CompoundTag root, Provider prov) {
      root.put("Railbeds", this.railbeds.save());
      root.putInt("Version", 71);
      ListTag supportRepairs = new ListTag();

      for (UUID id : this.supportRepair104) {
         CompoundTag t = new CompoundTag();
         t.putUUID("Road", id);
         supportRepairs.add(t);
      }

      root.put("SupportRepair104", supportRepairs);
      ListTag repair103 = new ListTag();
      this.structureRepair103.forEach((chunk, parts) -> {
         CompoundTag t = new CompoundTag();
         t.putLong("Chunk", chunk);
         t.putByteArray("Parts", RoadRecord.packStructures((List<RoadStructures.Part>)parts));
         repair103.add(t);
      });
      root.put("StructureRepair103", repair103);
      root.putLongArray("ApronWidthRepair", this.apronWidthRepair.keySet().stream().mapToLong(Long::longValue).toArray());
      ListTag islands = new ListTag();
      this.islandRepair.forEach((c, ids) -> ids.forEach(id -> {
            CompoundTag t = new CompoundTag();
            t.putLong("Chunk", c);
            t.putUUID("Road", id);
            islands.add(t);
         }));
      root.put("IslandRepair", islands);
      root.putLongArray("GroundJoinRepair", this.groundJoinRepair.keySet().stream().mapToLong(Long::longValue).toArray());
      root.put("SmartSigns", this.smartSigns.save());
      ListTag cleanup = new ListTag();
      this.retiredSignsByChunk.forEach((chunk, parts) -> {
         CompoundTag t = new CompoundTag();
         t.putLong("Chunk", chunk);
         t.putByteArray("Parts", RoadRecord.packStructures((List<RoadStructures.Part>)parts));
         cleanup.add(t);
      });
      root.put("RetiredSignCleanup", cleanup);
      ListTag logical = new ListTag();
      this.streets.values().forEach(r -> logical.add(r.save()));
      root.put("LogicalStreets", logical);
      ListTag roads = new ListTag();
      this.index.roads.values().forEach(r -> roads.add(r.record.save()));
      ListTag groups = new ListTag();
      this.interchanges.values().forEach(t -> groups.add(t.copy()));
      root.put("Interchanges", groups);
      ListTag junctionTags = new ListTag();
      this.junctions.values().forEach(t -> junctionTags.add(t.copy()));
      root.put("Junctions", junctionTags);
      root.put("TerrainOriginalPalette", RoadBlockStorage.write(this.terrainOriginal));
      root.put("TerrainFillPalette", RoadBlockStorage.write(this.terrainFill));
      root.remove("OriginalPalette");
      root.put("SidewalkPalette", RoadBlockStorage.write(this.sidewalkPlaced));
      root.putLongArray("RoadAprons", this.apronPlaced.stream().mapToLong(Long::longValue).toArray());
      root.remove("OriginalEntities");
      root.put("Roads", roads);
      root.remove("Originals");
      root.remove("SidewalkBlocks");
      return root;
   }

   public boolean linked(BlockPos pos) {
      return !this.index.atNode(pos).isEmpty();
   }

   public static void requireOwner(ServerPlayer player, UUID owner) {
      if (owner != null && !owner.equals(player.getUUID()) && !player.hasPermissions(2)) {
         throw new IllegalArgumentException("只能编辑自己创建的道路和端点");
      }
   }

   public static NodeEntity requireNode(ServerLevel level, BlockPos pos, ServerPlayer player) {
      NodeEntity var8;
      try (RoadWorkChunks work = RoadWorkChunks.open(level)) {
         work.node(pos);
         if (!(level.getBlockEntity(pos) instanceof NodeEntity node)) {
            throw new IllegalArgumentException("端点不存在，可能已被删除，请重新选择");
         }

         if (player != null) {
            requireOwner(player, node.owner);
            if (!player.mayBuild() || !level.mayInteract(player, pos)) {
               throw new IllegalArgumentException("此处没有建设权限");
            }
         }

         var8 = node;
      }

      return var8;
   }

   public RoadRecord connect(ServerLevel level, ServerPlayer player, BlockPos a, BlockPos b, RoadGeometry.Settings settings, UUID replace) {
      return this.connect(level, player, a, b, settings, replace, false);
   }

   public RoadRecord connect(ServerLevel level, ServerPlayer player, BlockPos a, BlockPos b, RoadGeometry.Settings settings, UUID replace, boolean levelEnds) {
      return this.connect(level, player, a, b, settings, replace, levelEnds, false);
   }

   public RoadRecord connect(
      ServerLevel level, ServerPlayer player, BlockPos a, BlockPos b, RoadGeometry.Settings settings, UUID replace, boolean levelEnds, boolean forceJunction
   ) {
      return this.connect(level, player, a, b, settings, replace, levelEnds, forceJunction, forceJunction);
   }

   public RoadRecord connect(
      ServerLevel level,
      ServerPlayer player,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Settings settings,
      UUID replace,
      boolean levelEnds,
      boolean forceA,
      boolean forceB
   ) {
      return this.connect(level, player, a, b, settings, replace, levelEnds, forceA, forceB, List.of());
   }

   public RoadRecord connect(
      ServerLevel level,
      ServerPlayer player,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Settings settings,
      UUID replace,
      boolean levelEnds,
      boolean forceA,
      boolean forceB,
      List<BlockPos> controlNodes
   ) {
      if (settings.style() == RoadGeometry.Style.TOLL) {
         throw new IllegalArgumentException("请使用收费站生成器");
      } else {
         return TollRoads.reverseConnection(this, a, b)
            ? this.connectDirected(level, player, b, a, settings, replace, levelEnds, forceB, forceA, controlNodes)
            : this.connectDirected(level, player, a, b, settings, replace, levelEnds, forceA, forceB, controlNodes);
      }
   }

   private RoadRecord connectDirected(
      ServerLevel level,
      ServerPlayer player,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Settings settings,
      UUID replace,
      boolean levelEnds,
      boolean forceA,
      boolean forceB,
      List<BlockPos> controlNodes
   ) {
      RoadIndex.Built existingLane = replace == null ? null : this.index.roads.get(replace);
      RoadRecord current = replace == null ? null : this.editableRoad(replace);
      if (replace == null || current != null && current.a().equals(a) && current.b().equals(b)) {
         RoadAttachments.Data savedAttachments = current == null ? RoadAttachments.Data.EMPTY : current.settings().options().attachments();
         savedAttachments = CurveControlNodes.attachments(
            savedAttachments, current == null ? List.of() : current.settings().options().routing().controls(), settings.options().routing().controls()
         );
         settings = settings.options(
            settings.options()
               .attachments(savedAttachments)
               .lanePoints(existingLane == null ? LanePoints.Data.EMPTY : ElevatedAccessRoads.logicalLanes(current, existingLane.record))
         );
         settings = settings.options(
            settings.options().lanePoints(LaneAdditions.reserveAuthored(settings.options().lanePoints(), RoadProfile.catalog(settings).lanes()))
         );
         if (current != null) {
            settings = settings.options(
               settings.options().infrastructure(settings.options().infrastructure().gantryEdits(current.settings().options().infrastructure().gantryEdits()))
            );
         }

         try (
            RoadTimings timing = RoadTimings.start("connect", this.index.roads.size(), 1);
            RoadWorkChunks workChunks = RoadWorkChunks.open(level);
         ) {
            RoadProfile.Type roadType = RoadProfile.catalog(settings.style()).type();
            if (roadType == RoadProfile.Type.HIGHWAY) {
               forceA = false;
               forceB = false;
            }

            boolean throughRoad = roadType == RoadProfile.Type.ORDINARY || roadType == RoadProfile.Type.HIGHWAY;
            if (this.streets.containsKey(replace) && !throughRoad) {
               throw new IllegalArgumentException("路口接路不能直接改为匝道，请先删除此支路");
            } else {
               boolean simpleVertical = RoadTunnelFit.enabled(settings)
                  && AutoJunctions.center(this, a) == null
                  && AutoJunctions.center(this, b) == null
                  && AutoJunctions.degree(this, a, replace) < 2
                  && AutoJunctions.degree(this, b, replace) < 2
                  && !forceA
                  && !forceB;
               if (!throughRoad
                  || simpleVertical
                  || !AutoJunctions.handles(this, a, b, replace, forceA || forceB)
                     && (roadType != RoadProfile.Type.ORDINARY || !AutoJunctions.incompatible(this, a, b, replace, settings))) {
                  if (a.equals(b)) {
                     throw new IllegalArgumentException("请选择两个不同的端点");
                  } else if (Math.hypot((double)a.getX() - (double)b.getX(), (double)a.getZ() - (double)b.getZ()) > 2072.0) {
                     throw new IllegalArgumentException("端点距离超过 2048 格，请增加中间端点");
                  } else {
                     NodeEntity na = requireNode(level, a, player);
                     NodeEntity nb = requireNode(level, b, player);
                     ManualPlannerHistory.capture(level, List.of(a.asLong(), b.asLong()));
                     RoadRecord previous = replace == null
                        ? null
                        : Optional.ofNullable(this.index.roads.get(replace)).orElseThrow(() -> new IllegalArgumentException("道路已经被删除")).record;
                     UUID owner = previous == null ? (player == null ? new UUID(0L, 0L) : player.getUUID()) : previous.owner();
                     if (previous != null) {
                        if (previous.assembly() != null) {
                           throw new IllegalArgumentException("自动立交需要使用立交连接器整体编辑");
                        }

                        if (player != null) {
                           requireOwner(player, owner);
                        }

                        if (!previous.a().equals(a) || !previous.b().equals(b)) {
                           throw new IllegalArgumentException("道路端点已改变");
                        }
                     }

                     for (RoadIndex.Built road : this.index.roads.values()) {
                        if (!road.record.id().equals(replace)
                           && (!settings.laneRamp() || !road.record.settings().laneRamp())
                           && (road.record.a().equals(a) && road.record.b().equals(b) || road.record.a().equals(b) && road.record.b().equals(a))) {
                           throw new IllegalArgumentException("这两个端点已经有道路，右键路面即可编辑");
                        }
                     }

                     RoadPlanner.Hint ah = this.constructionHint(level, a, true, replace, nb.constructionNode().position(), levelEnds);
                     RoadPlanner.Hint bh = this.constructionHint(level, b, false, replace, na.constructionNode().position(), levelEnds);
                     boolean linkedA = ah.linked();
                     boolean linkedB = bh.linked();
                     RoadPlanner.Hint seamA = ah;
                     RoadPlanner.Hint seamB = bh;
                     if (settings.mode() != RoadGeometry.Mode.AUTO) {
                        RoadGeometry.Node ma = previous == null
                           ? this.connectionNode(level, a, true, replace, nb.constructionNode().position())
                           : new RoadGeometry.Node(na.constructionNode().position(), previous.start().yaw(), previous.start().grade());
                        RoadGeometry.Node mb = previous == null
                           ? this.connectionNode(level, b, false, replace, na.constructionNode().position())
                           : new RoadGeometry.Node(nb.constructionNode().position(), previous.end().yaw(), previous.end().grade());
                        if (this.liveEndpoint(a, replace)) {
                           ma = new RoadGeometry.Node(ah.node().position(), ma.yaw(), ma.grade());
                        }

                        if (this.liveEndpoint(b, replace)) {
                           mb = new RoadGeometry.Node(bh.node().position(), mb.yaw(), mb.grade());
                        }

                        if (levelEnds) {
                           ma = atGround(ma, a);
                           mb = atGround(mb, b);
                        }

                        ah = RoadPlanner.Hint.free(ma);
                        bh = RoadPlanner.Hint.free(mb);
                     }

                     settings = joinWidths(settings, this.endpointWidth(a, replace), this.endpointWidth(b, replace));
                     CompoundTag joins = new CompoundTag();
                     this.jointPayload(joins, a, b, replace);
                     settings = joinSections(settings, joins, previous);
                     RoadPlanner.Plan plan = previous == null
                        ? null
                        : previous.retainedPlan(
                           settings,
                           ah,
                           bh,
                           new RoadGeometry.Node(na.constructionNode().position(), previous.start().yaw(), previous.start().grade()),
                           new RoadGeometry.Node(nb.constructionNode().position(), previous.end().yaw(), previous.end().grade())
                        );
                     if (plan == null) {
                        plan = RoadTunnelFit.plan(ah, bh, settings);
                     }

                     RoadConnectionChecks.require(plan, seamA, seamB);
                     RoadRecord record = new RoadRecord(
                        replace == null ? UUID.randomUUID() : replace,
                        owner,
                        a,
                        b,
                        plan.start(),
                        plan.end(),
                        plan.settings(),
                        settings.mode() == RoadGeometry.Mode.AUTO,
                        4
                     );
                     if (previous != null) {
                        record = previous.retainAlignment(record, settings, na.constructionNode(), nb.constructionNode());
                     }

                     record = RoadCrossingTransitions.fit(record, RoadCrossingTransitions.candidates(this));
                     if (!AutoCrossings.hits(record, AutoJunctions.logical(this).values()).isEmpty()) {
                        return AutoJunctions.connectRecord(level, player, record, new CompoundTag(), controlNodes);
                     } else {
                        List<RoadIndex.Built> changes = new ArrayList<>();
                        Set<UUID> removed = new HashSet<>();
                        if (replace != null) {
                           removed.add(replace);
                        }

                        RoadGeometry.Node movedA = new RoadGeometry.Node(record.start().position(), na.yaw, levelEnds ? 0.0 : na.grade);
                        RoadGeometry.Node movedB = new RoadGeometry.Node(record.end().position(), nb.yaw, levelEnds ? 0.0 : nb.grade);
                        Map<BlockPos, RoadGeometry.Node> updates = new LinkedHashMap<>();
                        if (!this.liveEndpoint(a, replace) && (movedA.position().distance(na.node().position()) > 1.0E-8 || levelEnds)) {
                           updates.put(a, movedA);
                        }

                        if (!this.liveEndpoint(b, replace) && (movedB.position().distance(nb.node().position()) > 1.0E-8 || levelEnds)) {
                           updates.put(b, movedB);
                        }

                        ArrayDeque<BlockPos> queue = new ArrayDeque<>(updates.keySet());

                        while (!queue.isEmpty()) {
                           BlockPos pos = queue.removeFirst();

                           for (RoadIndex.Built built : this.index.roads.values()) {
                              RoadRecord r = built.record;
                              if (r.a().equals(pos) || r.b().equals(pos)) {
                                 BlockPos other = r.a().equals(pos) ? r.b() : r.a();
                                 if (!updates.containsKey(other)) {
                                    NodeEntity entity = requireNode(level, other, player);
                                    RoadGeometry.Node next = entity.constructionNode();
                                    if (next.position().distance(entity.node().position()) > 1.0E-8) {
                                       updates.put(other, next);
                                       queue.add(other);
                                    }
                                 }
                              }
                           }
                        }

                        for (RoadIndex.Built builtx : this.index.roads.values()) {
                           RoadRecord r = builtx.record;
                           if (!r.id().equals(replace) && (updates.containsKey(r.a()) || updates.containsKey(r.b()))) {
                              boolean changedA = updates.containsKey(r.a()) && !sameNodeIntent(updates.get(r.a()), requireNode(level, r.a(), null).node());
                              boolean changedB = updates.containsKey(r.b()) && !sameNodeIntent(updates.get(r.b()), requireNode(level, r.b(), null).node());
                              if (changedA || changedB) {
                                 if (RoadTunnelFit.enabled(settings) && r.assembly() != null) {
                                    throw new IllegalArgumentException("要移动的桥隧端点属于路口或立交；请固定该端，仅调整另一端");
                                 }

                                 if (player != null) {
                                    requireOwner(player, r.owner());
                                 }

                                 RoadPlanner.Hint ha = this.hint(level, r.a(), true, r.id());
                                 RoadPlanner.Hint hb = this.hint(level, r.b(), false, r.id());
                                 RoadGeometry.Node aa = r.start();
                                 RoadGeometry.Node bb = r.end();
                                 if (updates.containsKey(r.a())) {
                                    RoadGeometry.Node before = requireNode(level, r.a(), null).node();
                                    ha = editedHint(ha, updates.get(r.a()), before);
                                    aa = editedHint(new RoadPlanner.Hint(aa, true, true, true), updates.get(r.a()), before).node();
                                 }

                                 if (updates.containsKey(r.b())) {
                                    RoadGeometry.Node before = requireNode(level, r.b(), null).node();
                                    hb = editedHint(hb, updates.get(r.b()), before);
                                    bb = editedHint(new RoadPlanner.Hint(bb, true, true, true), updates.get(r.b()), before).node();
                                 }

                                 RoadGeometry.Settings oldSettings = r.settings();
                                 if (r.automatic()) {
                                    RoadPlanner.Plan adjusted = RoadPlanner.plan(
                                       ha, hb, RoadPlanner.mode(oldSettings, RoadGeometry.Mode.AUTO, oldSettings.arcDegrees())
                                    );
                                    aa = adjusted.start();
                                    bb = adjusted.end();
                                    oldSettings = adjusted.settings();
                                 }

                                 if (!aa.equals(r.start()) || !bb.equals(r.end()) || !oldSettings.equals(r.settings())) {
                                    changes.add(
                                       new RoadIndex.Built(new RoadRecord(r.id(), r.owner(), r.a(), r.b(), aa, bb, oldSettings, r.automatic(), r.clearance()))
                                    );
                                    removed.add(r.id());
                                 }
                              }
                           }
                        }

                        changes.add(new RoadIndex.Built(record));
                        Map<BlockPos, BlockPos> relocated = new HashMap<>();
                        List<RoadData.NodeMove> moves = new ArrayList<>();

                        for (BlockPos pos : controlNodes) {
                           requireNode(level, pos, player);
                           if (pos.equals(a) || pos.equals(b) || this.linked(pos)) {
                              throw new IllegalArgumentException("控制端点已被使用");
                           }

                           moves.add(RoadData.NodeMove.retire(pos));
                        }

                        if (RoadTunnelFit.enabled(settings)) {
                           for (Entry<BlockPos, RoadGeometry.Node> entry : updates.entrySet()) {
                              BlockPos from = entry.getKey();
                              RoadGeometry.Node target = entry.getValue();
                              NodeEntity marker = requireNode(level, from, player);
                              if (!(target.position().sub(marker.node().position()).horizontalLength() < 1.0E-7)) {
                                 RoadGeometry.V p = target.position();
                                 BlockPos to = BlockPos.containing(p.x(), p.y(), p.z());
                                 relocated.put(from, to);
                                 moves.add(new RoadData.NodeMove(from, to, target, marker.saveWithoutMetadata(level.registryAccess())));
                              }
                           }
                        }

                        if (!moves.isEmpty()) {
                           for (int i = 0; i < changes.size(); i++) {
                              RoadRecord r = changes.get(i).record;
                              changes.set(
                                 i,
                                 new RoadIndex.Built(
                                    new RoadRecord(
                                       r.id(),
                                       r.owner(),
                                       relocated.getOrDefault(r.a(), r.a()),
                                       relocated.getOrDefault(r.b(), r.b()),
                                       r.start(),
                                       r.end(),
                                       r.settings(),
                                       r.automatic(),
                                       r.clearance(),
                                       r.structures(),
                                       r.endCaps(),
                                       r.buildVersion(),
                                       r.assembly(),
                                       r.alignment(),
                                       r.furniturePhase(),
                                       r.junction(),
                                       r.toll(),
                                       r.fragment()
                                    )
                                 )
                              );
                           }

                           HashSet<BlockPos> selected = new HashSet<>(updates.keySet());
                           selected.addAll(controlNodes);
                           this.replaceBatch(level, player, changes, removed, false, selected, moves);
                        } else {
                           this.replaceBatch(level, player, changes, removed);
                        }

                        updates.forEach((pos, n) -> requireNode(level, relocated.getOrDefault(pos, pos), null).apply(n));
                        na = requireNode(level, relocated.getOrDefault(a, a), null);
                        nb = requireNode(level, relocated.getOrDefault(b, b), null);
                        na.apply(!linkedA && !na.headingLocked ? record.start() : movedA);
                        nb.apply(!linkedB && !nb.headingLocked ? record.end() : movedB);
                        if (levelEnds) {
                           na.heightExplicit = false;
                           nb.heightExplicit = false;
                           na.gradeLocked = false;
                           nb.gradeLocked = false;
                           na.setChanged();
                           nb.setChanged();
                        }

                        return this.index.roads.get(record.id()).record;
                     }
                  }
               } else {
                  return AutoJunctions.connect(level, player, a, b, settings, replace, forceA, forceB, levelEnds, controlNodes);
               }
            }
         }
      } else {
         throw new IllegalArgumentException("编辑目标与端点不一致，请重新右键目标道路");
      }
   }

   public static RoadGeometry.Node atGround(RoadGeometry.Node n, BlockPos p) {
      return new RoadGeometry.Node(new RoadGeometry.V(n.position().x(), (double)p.getY(), n.position().z()), n.yaw(), 0.0);
   }

   public RoadPlanner.Hint constructionHint(ServerLevel level, BlockPos pos, boolean start, UUID exclude, RoadGeometry.V towards, boolean levelEnds) {
      RoadPlanner.Hint base = this.hint(level, pos, start, exclude, towards);
      RoadGeometry.Node n = requireNode(level, pos, null).constructionNode();
      RoadGeometry.Node v = new RoadGeometry.Node(
         this.liveEndpoint(pos, exclude) ? base.node().position() : n.position(), base.node().yaw(), base.node().grade()
      );
      if (levelEnds) {
         v = atGround(v, pos);
      }

      return new RoadPlanner.Hint(v, base.headingLocked(), levelEnds || base.gradeLocked(), base.linked());
   }

   public RoadGeometry.Node connectionNode(ServerLevel level, BlockPos pos, boolean start, UUID exclude, RoadGeometry.V towards) {
      NodeEntity entity = requireNode(level, pos, null);
      RoadGeometry.Node node = entity.constructionNode();
      if (entity.plannerHeading && !entity.headingLocked) {
         RoadPlanner.Hint seam = this.constructionHint(level, pos, start, exclude, towards, false);
         RoadGeometry.V chord = start ? towards.sub(node.position()) : node.position().sub(towards);
         return new RoadGeometry.Node(
            this.liveEndpoint(pos, exclude) ? seam.node().position() : node.position(),
            seam.linked() ? seam.node().yaw() : RoadPlanner.yaw(chord),
            node.grade()
         );
      } else {
         return node;
      }
   }

   public RoadPlanner.Hint hint(ServerLevel level, BlockPos pos, boolean start, UUID exclude) {
      RoadIndex.Built current = exclude == null ? null : this.index.roads.get(exclude);
      RoadGeometry.V towards = current == null ? null : (current.record.a().equals(pos) ? current.record.end().position() : current.record.start().position());
      return this.hint(level, pos, start, exclude, towards);
   }

   public RoadPlanner.Hint hint(ServerLevel level, BlockPos pos, boolean start, UUID exclude, RoadGeometry.V towards) {
      NodeEntity entity = requireNode(level, pos, null);
      RoadGeometry.Node n = entity.node();
      RoadIndex.Built best = null;
      double bestScore = -Double.MAX_VALUE;

      for (RoadIndex.Built built : this.index.roads.values()) {
         if (!built.record.id().equals(exclude)
            && (built.record.a().equals(pos) || built.record.b().equals(pos))
            && (transitionEndpoint(built, pos) || built.record.settings().style().ramp())) {
            double score = towards == null
               ? built.record.settings().width()
               : -away(built, pos).horizontalUnit().dot(towards.sub(n.position()).horizontalUnit());
            if (best == null || score > bestScore + 1.0E-8 || Math.abs(score - bestScore) < 1.0E-8 && built.record.id().compareTo(best.record.id()) < 0) {
               best = built;
               bestScore = score;
            }
         }
      }

      if (best != null && best.mesh.closed()) {
         RoadQueries.Projection q = RoadQueries.project(best.mesh, n.position());
         return new RoadPlanner.Hint(new RoadGeometry.Node(q.sample().center(), n.yaw(), 0.0), entity.headingLocked, true, true);
      } else if (best != null) {
         RoadGeometry.V d = away(best, pos).mul(start ? -1.0 : 1.0);
         return new RoadPlanner.Hint(
            new RoadGeometry.Node(
               best.record.fragment() == null && !RoadEndpointSections.changed(best.mesh, best.record.a().equals(pos))
                  ? n.position()
                  : RoadMedianAnchor.position(best.record.caps(0).mesh(), best.record.a().equals(pos)),
               RoadPlanner.yaw(d),
               d.y()
            ),
            true,
            true,
            true
         );
      } else {
         return new RoadPlanner.Hint(n, entity.headingLocked, entity.gradeLocked, false);
      }
   }

   public long connectionsAt(BlockPos p) {
      return (long)this.index.atNode(p).size();
   }

   public double endpointWidth(BlockPos p, UUID exclude) {
      double width = 0.0;

      for (RoadIndex.Built built : this.index.roads.values()) {
         if (!built.record.id().equals(exclude)
            && (transitionEndpoint(built, p) || built.record.settings().style().ramp())
            && (built.record.a().equals(p) || built.record.b().equals(p))) {
            width = Math.max(width, 2.0 * (built.record.a().equals(p) ? built.mesh.first() : built.mesh.last()).halfWidth());
         }
      }

      return width;
   }

   public RoadGeometry.Settings endpointSettings(BlockPos p, UUID exclude) {
      return this.index
         .atNode(p)
         .stream()
         .filter(id -> !id.equals(exclude))
         .map(this.index.roads::get)
         .filter(r -> r != null && transitionEndpoint(r, p) && !r.record.settings().style().ramp())
         .sorted(Comparator.comparing(r -> r.record.id()))
         .map(
            r -> RoadEndpointSections.changed(r.mesh, r.record.a().equals(p))
                  ? endpointSection(r, p)
                  : (
                     r.record.fragment() == null && r.record.assembly() == null && !r.record.settings().options().ends().persistent()
                        ? authoredSection(r.record.settings())
                        : endpointSection(r, p)
                  )
         )
         .findFirst()
         .orElse(null);
   }

   private boolean liveEndpoint(BlockPos p, UUID exclude) {
      return this.index
         .atNode(p)
         .stream()
         .filter(id -> !id.equals(exclude))
         .map(this.index.roads::get)
         .anyMatch(
            r -> transitionEndpoint(r, p)
                  && (r.record.toll() != null || r.record.fragment() != null || RoadEndpointSections.changed(r.mesh, r.record.a().equals(p)))
         );
   }

   private RoadGeometry.Settings endpointSettings(BlockPos p, UUID exclude, boolean start) {
      Optional<RoadIndex.Built> donor = this.index
         .atNode(p)
         .stream()
         .filter(id -> !id.equals(exclude))
         .map(this.index.roads::get)
         .filter(rx -> rx != null && transitionEndpoint(rx, p) && !rx.record.settings().style().ramp())
         .sorted(Comparator.comparing(rx -> rx.record.id()))
         .findFirst();
      if (donor.isEmpty()) {
         return null;
      } else {
         RoadIndex.Built r = donor.get();
         boolean first = r.record.a().equals(p);
         RoadGeometry.Settings section = RoadEndpointSections.changed(r.mesh, first)
            ? endpointSection(r, p)
            : (
               r.record.fragment() == null && r.record.assembly() == null && !r.record.settings().options().ends().persistent()
                  ? authoredSection(r.record.settings())
                  : endpointSection(r, p)
            );
         return RoadEndpointSections.orient(section, first == start);
      }
   }

   static RoadGeometry.Settings authoredSection(RoadGeometry.Settings s) {
      return s.taper(s.width(), s.width()).options(s.options().ends(RoadTransitions.Ends.NONE));
   }

   static boolean transitionEndpoint(RoadIndex.Built road, BlockPos pos) {
      RoadRecord r = road.record;
      if (LaneTopology.metadata(r).link() != null) {
         return false;
      } else if (r.junction() != null) {
         return r.junction().get().arm() >= 0 && r.a().equals(pos) && !r.b().equals(pos);
      } else {
         RoadTransitions.Ends ends = r.settings().options().ends();
         if (r.fragment() != null) {
            boolean first = r.a().equals(pos);
            RoadGeometry.V physical = RoadSplitGeometry.node(road.mesh, first ? 0.0 : road.mesh.length()).position();
            if (BlockPos.containing(physical.x(), physical.y(), physical.z()).equals(pos)) {
               return true;
            } else {
               RoadGeometry.Node node = first ? r.start() : r.end();
               RoadGeometry.V outward = (first ? road.mesh.first() : road.mesh.last()).left().left().mul(first ? 1.0 : -1.0);
               RoadGeometry.V uncapped = node.position().sub(outward.mul(0.5)).add(new RoadGeometry.V(0.0, node.grade() * (first ? 0.5 : -0.5), 0.0));
               if (!BlockPos.containing(node.position().x(), node.position().y(), node.position().z()).equals(pos)
                  && !BlockPos.containing(uncapped.x(), uncapped.y(), uncapped.z()).equals(pos)) {
                  return false;
               } else {
                  RoadGeometry.V delta = physical.sub(node.position());
                  double along = delta.dot(outward);
                  return along >= -1.0E-7
                     && along <= 1.0000001
                     && Math.abs(delta.dot(outward.left())) < 1.0E-6
                     && Math.abs(delta.y() - along * node.grade() * (double)(first ? -1 : 1)) < 1.0E-6;
               }
            }
         } else {
            return (!r.a().equals(pos) || !(ends.trimmedStart() > 1.0E-6)) && (!r.b().equals(pos) || !(ends.trimmedEnd() > 1.0E-6))
               ? r.assembly() == null || r.settings().style() != RoadGeometry.Style.UNMARKED
               : false;
         }
      }
   }

   static RoadGeometry.Settings endpointSection(RoadIndex.Built road, BlockPos pos) {
      boolean first = road.record.a().equals(pos);
      if (road.record.toll() != null) {
         return TollPlaza.port(road.record.toll(), first);
      } else if (road.record.fragment() == null
         && !RoadEndpointSections.changed(road.mesh, first)
         && (road.record.assembly() == null || road.record.junction() != null || road.record.settings().style().ramp())) {
         RoadGeometry.Sample at = first ? road.mesh.first() : road.mesh.last();
         RoadGeometry.Settings base = road.record.junction() != null && road.record.junction().get().arm() >= 0
            ? JunctionRoads.editable(road.record.junction().spec().arms().get(road.record.junction().get().arm()))
            : road.record.settings();
         RoadTransitions.Section end = first ? base.options().ends().start() : base.options().ends().end();
         if (road.record.junction() != null) {
            end = null;
         }

         RoadGeometry.Settings s = end == null ? base : end.settings(base.options().leftTraffic());
         RoadProfile.Options options = s.options()
            .ends(RoadTransitions.Ends.NONE)
            .infrastructure(base.options().infrastructure())
            .cycleFinish(
               s.options().cycle()
                  ? (base.options().cycleAsphalt() ? RoadProfile.Options.CycleFinish.ASPHALT : RoadProfile.Options.CycleFinish.GREEN)
                  : RoadProfile.Options.CycleFinish.NONE
            );
         if (end == null || end.sidewalk() == null) {
            options = options.sidewalk(base.options().sidewalk());
         }

         RoadGeometry.Settings result = new RoadGeometry.Settings(
               RoadGeometry.Mode.STRAIGHT, s.style(), at.halfWidth() * 2.0, base.thickness(), base.tension(), base.arcDegrees()
            )
            .structure(base.structure())
            .options(options);
         result.validate();
         return result;
      } else {
         return RoadEndpointSections.section(road.record.caps(0).mesh(), first, false);
      }
   }

   public void jointPayload(CompoundTag payload, BlockPos a, BlockPos b, UUID exclude) {
      payload.remove("JoinSectionA");
      payload.remove("JoinSectionB");
      RoadGeometry.Settings start = this.endpointSettings(a, exclude, true);
      RoadGeometry.Settings end = this.endpointSettings(b, exclude, false);
      if (start != null) {
         payload.put("JoinSectionA", RoadRecord.writeSettings(start));
      }

      if (end != null) {
         payload.put("JoinSectionB", RoadRecord.writeSettings(end));
      }

      payload.putBoolean("FixedSectionA", this.fixedEndpoint(a, exclude));
      payload.putBoolean("LiveSectionA", this.liveEndpoint(a, exclude));
      payload.putBoolean("FixedSectionB", this.fixedEndpoint(b, exclude));
      payload.putBoolean("LiveSectionB", this.liveEndpoint(b, exclude));
   }

   private boolean fixedEndpoint(BlockPos p, UUID exclude) {
      return this.index
         .atNode(p)
         .stream()
         .filter(id -> !id.equals(exclude))
         .map(this.index.roads::get)
         .anyMatch(
            r -> (r.record.fragment() != null || r.record.assembly() != null || RoadEndpointSections.changed(r.mesh, r.record.a().equals(p)))
                  && transitionEndpoint(r, p)
                  && !r.record.settings().style().ramp()
         );
   }

   public static RoadGeometry.Settings joinSections(RoadGeometry.Settings s, CompoundTag payload) {
      return joinSections(s, payload, payload.contains("Road") ? RoadRecord.load(payload.getCompound("Road")) : null);
   }

   public static RoadGeometry.Settings joinSections(RoadGeometry.Settings s, CompoundTag payload, RoadRecord previous) {
      return RoadRecord.joinSections(
         s,
         payload.contains("JoinSectionA") ? RoadRecord.readSettings(payload.getCompound("JoinSectionA")) : null,
         payload.contains("JoinSectionB") ? RoadRecord.readSettings(payload.getCompound("JoinSectionB")) : null,
         payload.getBoolean("FixedSectionA"),
         payload.getBoolean("FixedSectionB"),
         previous
      );
   }

   public static RoadGeometry.Settings joinWidths(RoadGeometry.Settings s, double a, double b) {
      return s.style().ramp() && !s.laneRamp() ? s.taper(Math.max(s.width(), a), Math.max(s.width(), b)) : s;
   }

   public static CompoundTag writeHint(RoadPlanner.Hint hint) {
      CompoundTag t = RoadRecord.writeNode(hint.node());
      t.putBoolean("HeadingLocked", hint.headingLocked());
      t.putBoolean("GradeLocked", hint.gradeLocked());
      t.putBoolean("Linked", hint.linked());
      return t;
   }

   public static RoadPlanner.Hint readHint(CompoundTag t) {
      return new RoadPlanner.Hint(RoadRecord.readNode(t), t.getBoolean("HeadingLocked"), t.getBoolean("GradeLocked"), t.getBoolean("Linked"));
   }

   public double inheritedWidth(BlockPos a, BlockPos b, double fallback, UUID exclude) {
      for (RoadIndex.Built r : this.index.roads.values()) {
         if (!r.record.id().equals(exclude) && (r.record.a().equals(a) || r.record.b().equals(a) || r.record.a().equals(b) || r.record.b().equals(b))) {
            return r.record.settings().width();
         }
      }

      return fallback;
   }

   public void editNode(ServerLevel level, ServerPlayer player, BlockPos pos, RoadGeometry.Node node) {
      try (
         RoadTimings timing = RoadTimings.start("edit_node", this.index.roads.size(), this.index.atNode(pos).size());
         RoadWorkChunks workChunks = RoadWorkChunks.open(level);
      ) {
         if (this.index.atNode(pos).stream().anyMatch(id -> this.index.roads.get(id).record.assembly() != null)) {
            throw new IllegalArgumentException("路口或立交的端点请使用对应连接器整体编辑，或先删除该整体");
         }

         NodeEntity entity = requireNode(level, pos, player);
         RoadGeometry.V p = node.position();
         if (!RoadGeometry.finite(p.x(), p.y(), p.z(), node.yaw(), node.grade())
            || Math.abs(p.x() - (double)pos.getX() - 0.5) > 8.0
            || Math.abs(p.z() - (double)pos.getZ() - 0.5) > 8.0
            || p.y() - (double)pos.getY() < -8.0
            || p.y() - (double)pos.getY() > 16.0
            || Math.abs(node.grade()) > 0.5) {
            throw new IllegalArgumentException("端点偏移 X/Z：±8，Y：-8–16；坡度：±50%");
         }

         if (sameNodeIntent(node, entity.node())) {
            return;
         }

         ManualPlannerHistory.capture(level, List.of(pos.asLong()));
         List<RoadIndex.Built> replacements = new ArrayList<>();
         Set<UUID> ids = new HashSet<>();

         for (RoadIndex.Built built : this.index.roads.values()) {
            RoadRecord r = built.record;
            if (r.a().equals(pos) || r.b().equals(pos)) {
               if (player != null) {
                  requireOwner(player, r.owner());
               }

               ids.add(r.id());
               replacements.add(
                  new RoadIndex.Built(planNodeEdit(r, pos, node, entity.node(), this.hint(level, r.a(), true, r.id()), this.hint(level, r.b(), false, r.id())))
               );
            }
         }

         this.replaceBatch(level, player, replacements, ids);
         if (Math.abs(node.yaw() - entity.yaw) > 1.0E-7) {
            entity.headingLocked = true;
         }

         if (Math.abs(node.grade() - entity.grade) > 1.0E-7) {
            entity.gradeLocked = true;
         }

         if (Math.abs(node.position().y() - entity.node().position().y()) > 1.0E-8) {
            entity.heightExplicit = true;
         }

         entity.apply(node);
      }
   }

   public static RoadRecord planNodeEdit(
      RoadRecord r, BlockPos pos, RoadGeometry.Node edited, RoadGeometry.Node original, RoadPlanner.Hint ha, RoadPlanner.Hint hb
   ) {
      if (sameNodeIntent(edited, original)) {
         return r;
      } else {
         RoadGeometry.Node aa = r.start();
         RoadGeometry.Node bb = r.end();
         RoadGeometry.Settings settings = r.settings();
         if (r.automatic()) {
            if (r.a().equals(pos)) {
               ha = editedHint(ha, edited, original);
            }

            if (r.b().equals(pos)) {
               hb = editedHint(hb, edited, original);
            }

            RoadPlanner.Plan plan = RoadPlanner.plan(ha, hb, RoadPlanner.mode(settings, RoadGeometry.Mode.AUTO, settings.arcDegrees()));
            aa = plan.start();
            bb = plan.end();
            settings = plan.settings();
         } else {
            if (r.a().equals(pos)) {
               aa = editedHint(new RoadPlanner.Hint(aa, true, true, true), edited, original).node();
            }

            if (r.b().equals(pos)) {
               bb = editedHint(new RoadPlanner.Hint(bb, true, true, true), edited, original).node();
            }
         }

         return new RoadRecord(r.id(), r.owner(), r.a(), r.b(), aa, bb, settings, r.automatic(), r.clearance());
      }
   }

   private static boolean sameNodeIntent(RoadGeometry.Node a, RoadGeometry.Node b) {
      return RoadGeometry.finite(a.position().x(), a.position().y(), a.position().z(), a.yaw(), a.grade())
         && a.position().distance(b.position()) <= 1.0E-8
         && Math.abs(Math.IEEEremainder(a.yaw() - b.yaw(), 360.0)) <= 1.0E-7
         && Math.abs(a.grade() - b.grade()) <= 1.0E-7;
   }

   private static RoadPlanner.Hint editedHint(RoadPlanner.Hint hint, RoadGeometry.Node edited, RoadGeometry.Node original) {
      boolean yawChanged = Math.abs(Math.IEEEremainder(edited.yaw() - original.yaw(), 360.0)) > 1.0E-7;
      boolean gradeChanged = Math.abs(edited.grade() - original.grade()) > 1.0E-7;
      double sign = hint.node().direction().dot(original.direction()) < 0.0 ? -1.0 : 1.0;
      RoadGeometry.V offset = hint.node().position().sub(original.position());
      double angle = Math.toRadians(edited.yaw() - original.yaw());
      RoadGeometry.V rotated = new RoadGeometry.V(
         offset.x() * Math.cos(angle) - offset.z() * Math.sin(angle), offset.y(), offset.x() * Math.sin(angle) + offset.z() * Math.cos(angle)
      );
      RoadGeometry.Node n = new RoadGeometry.Node(
         edited.position().add(rotated),
         hint.node().yaw() + (yawChanged ? edited.yaw() - original.yaw() : 0.0),
         gradeChanged ? sign * edited.grade() : hint.node().grade()
      );
      return new RoadPlanner.Hint(n, hint.headingLocked() || yawChanged, hint.gradeLocked() || gradeChanged, hint.linked());
   }

   public void remove(ServerLevel level, ServerPlayer player, UUID id) {
      RoadIndex.Built road = this.index.roads.get(id);
      if (road == null) {
         throw new IllegalArgumentException("道路已经不存在");
      } else if (road.record.toll() != null) {
         TollRoads.remove(level, player, id);
      } else {
         if (player != null) {
            requireOwner(player, road.record.owner());
         }

         if (this.streets.containsKey(id) && road.record.assembly() == null) {
            AutoJunctions.removeStreet(level, player, id);
         } else if (road.record.junction() != null) {
            Junctions.remove(level, player, road.record.assembly());
         } else if (ElevatedAccessRoads.saved(this, road.record.assembly())) {
            ElevatedAccessRoads.remove(level, player, road.record.assembly());
         } else if (road.record.assembly() != null) {
            if (this.interchanges.getOrDefault(road.record.assembly(), new CompoundTag()).getBoolean("YJunction")) {
               Interchanges.remove(level, player, road.record.assembly());
            } else {
               Interchanges.removePart(level, player, id);
            }
         } else {
            this.replaceBatch(level, player, List.of(), Set.of(id));
         }
      }
   }

   RoadRecord planSideHost(ServerLevel level, RoadIndex.Built host, List<RoadIndex.Built> all) {
      int mask = 0;

      for (boolean first : new boolean[]{true, false}) {
         BlockPos pos = first ? host.record.a() : host.record.b();
         if (all.stream()
            .noneMatch(
               r -> !r.record.id().equals(host.record.id())
                     && LaneTopology.metadata(r.record).link() == null
                     && (r.record.a().equals(pos) || r.record.b().equals(pos))
            )) {
            mask |= first ? 1 : 2;
         }
      }

      RoadIndex.Built capped = host.caps(mask);
      all.set(all.indexOf(host), capped);
      return StructurePlanner.plan(
         level, capped, all, this.terrainFill, this.terrainOriginal, new RoadTerrain.Cache(this.terrainFill, this.terrainOriginal), new RoadPlanningIndex(all)
      );
   }

   public void updatePointMetadata(ServerLevel level, ServerPlayer player, RoadRecord r, RoadAttachments.Data value) {
      if (r.toll() != null) {
         throw new IllegalArgumentException("收费站请使用收费站生成器编辑");
      } else {
         if (player != null) {
            requireOwner(player, r.owner());

            for (RoadAttachments.Point p : value.points()) {
               if (!level.mayInteract(player, BlockPos.containing(p.position().x(), p.position().y(), p.position().z()))) {
                  throw new IllegalArgumentException("附属点位于受保护区域");
               }
            }
         }

         if (SideHosts.host(r)) {
            SideHosts.updatePoints(level, r, value);
         } else {
            for (ElevatedAccess.Reservation access : r.settings().options().lanePoints().access()) {
               RoadAttachments.Point old = r.settings()
                  .options()
                  .attachments()
                  .points()
                  .stream()
                  .filter(px -> px.id().equals(access.point()))
                  .findFirst()
                  .orElse(null);
               if (old != null && value.points().stream().noneMatch(old::equals)) {
                  throw new IllegalArgumentException("请先删除高架连接，再移动或删除其出入点");
               }
            }

            RoadRecord next = r.withAttachments(value);
            this.index.put(RoadIndex.Built.loading(next));
            if (this.streets.containsKey(r.id())) {
               this.streets.put(r.id(), this.streets.get(r.id()).withAttachments(value));
            }

            this.setDirty();
            RoadNetwork.broadcastRoad(level, next);
         }
      }
   }

   void removeAttachedPoint(ServerLevel level, ServerPlayer player, RoadRecord road, RoadAttachments.Data value, UUID point) {
      RoadRecord next = road.withAttachments(value).structures(List.of());
      RoadIndex.Built proposed = new RoadIndex.Built(next);

      for (RoadIndex.Built other : this.index.roads.values()) {
         if (!other.record.id().equals(road.id()) && RoadIndex.overlapXZ(proposed.mesh, other.mesh, 1.0)) {
            Interchanges.checkExternal(proposed.mesh, other.mesh, road.mesh());
         }
      }

      this.replaceBatch(level, player, List.of(proposed), Set.of(road.id()), true, Set.of(road.a(), road.b()), List.of(), 4000000, Set.of(point));
   }

   void updateLanePointMetadata(ServerLevel level, ServerPlayer player, RoadRecord r, LanePoints.Data value) {
      if (r.toll() != null) {
         throw new IllegalArgumentException("收费站内部和接口不支持车道点");
      } else {
         value = value.points(
            Stream.concat(value.points().stream().filter(p -> !p.automatic()), value.points().stream().filter(LanePoints.Point::automatic)).toList()
         );
         if (player != null) {
            requireOwner(player, r.owner());
            if (!player.mayBuild() || player.isSpectator()) {
               throw new IllegalArgumentException("没有建设权限");
            }

            for (LanePoints.Point point : value.points()) {
               if (!level.mayInteract(player, BlockPos.containing(point.position().x(), point.position().y(), point.position().z()))) {
                  throw new IllegalArgumentException("车道点位于受保护区域");
               }
            }
         }

         LanePoints.Data before = LaneTopology.metadata(r);

         for (LanePoints.Point pointx : before.points()) {
            LanePoints.Point next = value.points().stream().filter(p -> p.id().equals(pointx.id())).findFirst().orElse(null);
            if (!pointx.equals(next) && this.index.laneReferenced(LanePoints.Ref.lane(r.id(), pointx.id()))) {
               throw new IllegalArgumentException("已使用的车道点必须同步重建依赖匝道");
            }
         }

         RoadRecord next = this.index.lanePoints(r.id(), value).record;
         if (this.streets.containsKey(r.id())) {
            this.streets.put(r.id(), this.streets.get(r.id()).withLanePoints(value));
         }

         this.setDirty();
         RoadNetwork.broadcastLanePoints(level, r, next);
      }
   }

   public void editLaneLine(ServerLevel level, ServerPlayer player, CompoundTag t) {
      RoadIndex.Built built = this.index.roads.get(t.getUUID("Id"));
      if (built != null && built.record.junction() == null && built.record.toll() == null) {
         RoadRecord r = built.record;
         if (player != null) {
            requireOwner(player, r.owner());
         }

         if (t.getInt("Signature") != GantryTool.signature(r)) {
            throw new IllegalArgumentException("道路已更新，请重新选择");
         } else {
            RoadProfile.Options options = r.settings().options();
            if (t.contains("Station")) {
               double station = t.getDouble("Station");
               if (Double.isFinite(station) && !(station < 0.0) && !(station > built.mesh.length())) {
                  RoadAttachments.Range range = RoadAttachments.range(built.mesh, station);
                  RoadAttachments.Paint paint = RoadAttachments.paint(built.mesh, station);
                  if (t.contains("HideArrows")) {
                     paint = new RoadAttachments.Paint(paint.lines(), t.getBoolean("HideArrows"));
                  } else {
                     String key = t.getString("Key");
                     if (RoadLaneLines.lines(built.mesh).stream().noneMatch(l -> l.key().equals(key))) {
                        throw new IllegalArgumentException("这根车道线已不存在");
                     }

                     paint = new RoadAttachments.Paint(
                        RoadLaneLines.with(
                           paint.lines(), new RoadLaneLines.Edit(key, RoadLaneLines.Pattern.valueOf(t.getString("Pattern")), t.getDouble("Width"))
                        ),
                        paint.hideArrows()
                     );
                  }

                  this.updatePointMetadata(level, player, r, RoadAttachments.edit(built.mesh, range, paint));
               } else {
                  throw new IllegalArgumentException("标线区间无效");
               }
            } else if (!options.attachments().points().isEmpty()) {
               throw new IllegalArgumentException("请重新选择标线编辑区间");
            } else {
               if (t.contains("HideArrows")) {
                  options = options.hideArrows(t.getBoolean("HideArrows"));
               } else {
                  String key = t.getString("Key");
                  if (RoadLaneLines.lines(built.mesh).stream().noneMatch(l -> l.key().equals(key))) {
                     throw new IllegalArgumentException("这根车道线已不存在");
                  }

                  RoadLaneLines.Edit e = new RoadLaneLines.Edit(key, RoadLaneLines.Pattern.valueOf(t.getString("Pattern")), t.getDouble("Width"));
                  options = options.laneLines(RoadLaneLines.with(options.laneLines(), e));
               }

               RoadGeometry.Settings settings = r.settings().options(options);
               RoadRecord next = new RoadRecord(
                  r.id(),
                  r.owner(),
                  r.a(),
                  r.b(),
                  r.start(),
                  r.end(),
                  settings,
                  r.automatic(),
                  r.clearance(),
                  r.structures(),
                  r.endCaps(),
                  r.buildVersion(),
                  r.assembly(),
                  r.alignment(),
                  r.furniturePhase(),
                  r.junction(),
                  r.toll(),
                  r.fragment()
               );
               this.index.put(RoadIndex.Built.loading(next));
               if (this.streets.containsKey(r.id())) {
                  this.streets.put(r.id(), this.streets.get(r.id()).settings(settings));
               }

               this.setDirty();
               RoadNetwork.broadcastRoad(level, next);
            }
         }
      } else {
         throw new IllegalArgumentException("路段已改变，请重新选择");
      }
   }

   public void editGantry(ServerLevel level, ServerPlayer player, CompoundTag command) {
      this.editGantry(level, player, command, null);
   }

   public int createGantry(ServerLevel level, ServerPlayer player, UUID road, RoadGeometry.V point) {
      RoadIndex.Built built = this.index.roads.get(road);
      if (built != null && built.record.junction() == null && built.record.toll() == null && LaneTopology.metadata(built.record).link() == null) {
         RoadQueries.Projection q = RoadQueries.horizontal(built.mesh, point);
         if (!(Math.abs(q.lateral()) > q.sample().halfWidth() + 2.0) && !(Math.abs(point.y() - q.sample().center().y()) > 3.0)) {
            RoadGantry.Edit creation = RoadGantry.create(built.mesh, q.sample().distance());
            CompoundTag command = new CompoundTag();
            command.putUUID("Id", road);
            command.putInt("Signature", GantryTool.signature(built.record));
            command.putInt("Slot", creation.slot());
            this.editGantry(level, player, command, creation);
            return creation.slot();
         } else {
            throw new IllegalArgumentException("请右键道路表面选择放置位置");
         }
      } else {
         throw new IllegalArgumentException("请右键要放置龙门架的普通道路或高速道路");
      }
   }

   private void editGantry(ServerLevel level, ServerPlayer player, CompoundTag command, RoadGantry.Edit creation) {
      RoadIndex.Built built = this.index.roads.get(command.getUUID("Id"));
      if (built != null && built.record.junction() == null && built.record.toll() == null) {
         RoadRecord r = built.record;
         if (player != null) {
            requireOwner(player, r.owner());
            if (!player.mayBuild() || player.isSpectator()) {
               throw new IllegalArgumentException("没有建设权限");
            }
         }

         if (command.getInt("Signature") != GantryTool.signature(r)) {
            throw new IllegalArgumentException("道路已更新，请重新打开龙门架编辑器");
         } else {
            int slot = command.getInt("Slot");
            RoadGantry.Edit oldEdit = creation == null ? RoadGantry.station(built.mesh, slot).edit() : creation;
            boolean remove = command.getBoolean("Remove");
            int flow = command.getInt("Flow");
            if (flow == 2) {
               if (player == null) {
                  throw new IllegalArgumentException("当前方向需要玩家朝向");
               }

               Vec3 look = player.getLookAngle();
               flow = RoadGantry.playerFlow(RoadGantry.station(built.mesh, slot).sample(), new RoadGeometry.V(look.x, look.y, look.z));
            }

            ArrayList<SmartSigns.Key> signKeys = new ArrayList<>();
            SmartSigns.Mode signMode = command.contains("SignMode") ? SmartSigns.Mode.valueOf(command.getString("SignMode")) : null;
            if (signMode != null && !remove) {
               if (flow == 0 && signMode != SmartSigns.Mode.SPEED_LIMIT && signMode != SmartSigns.Mode.LANE_INDICATOR && !command.getBoolean("Reset")) {
                  throw new IllegalArgumentException("限速或车道指示龙门架支持双向设置");
               }

               for (int direction : flow == 0 ? new int[]{1, -1} : new int[]{flow}) {
                  SmartSigns.Key key = new SmartSigns.Key(r.id(), slot, direction);
                  this.smartSigns.validateMode(key, signMode, command.getLong(flow == 0 && direction < 0 ? "ReverseSignRevision" : "SignRevision"));
                  signKeys.add(key);
               }
            }

            RoadInfrastructure.Config c = r.settings().options().infrastructure();
            if (creation != null) {
               c = c.edit(creation);
            } else if (remove) {
               c = oldEdit.independent()
                  ? c.reset(slot)
                  : c.edit(oldEdit.parameters(oldEdit.offset(), oldEdit.clearance(), RoadInfrastructure.Gantry.OFF, oldEdit.reverse()));
            } else if (command.getBoolean("Reset")) {
               c = oldEdit.independent() ? c.edit(oldEdit.parameters(0.0, 5.6, RoadInfrastructure.Gantry.SIGNS, false)) : c.reset(slot);
            } else {
               c = c.edit(
                  oldEdit.parameters(
                     command.getDouble("Offset"),
                     command.getDouble("Clearance"),
                     RoadInfrastructure.Gantry.valueOf(command.getString("Style")),
                     command.getBoolean("Reverse")
                  )
               );
            }

            RoadGeometry.Settings settings = r.settings().options(r.settings().options().infrastructure(c));
            RoadRecord next = r.settings(settings);

            try (
               RoadEditRasters rasters = RoadEditRasters.open();
               RoadPlanningBudget budget = RoadPlanningBudget.cancellable("龙门架局部编辑");
               RoadWorkChunks chunks = RoadWorkChunks.open(level);
            ) {
               RoadIndex.Built candidate = new RoadIndex.Built(next);
               RoadGantry.Station st = RoadGantry.station(remove ? built.mesh : candidate.mesh, slot);
               if (player != null
                  && player.position().distanceToSqr(new Vec3(st.sample().center().x(), st.sample().center().y(), st.sample().center().z())) > 65536.0) {
                  throw new IllegalArgumentException("离所选龙门架太远");
               }

               ArrayList<RoadStructures.Part> members = new ArrayList<>(creation == null ? RoadGantry.members(built.mesh, slot, r.structures()) : List.of());
               ArrayList<RoadStructures.Part> retainedParts = new ArrayList<>(r.structures());
               retainedParts.removeAll(members);
               List<RoadStructures.Part> fresh = remove
                  ? List.of()
                  : StructurePlanner.gantry(
                     level, candidate.structures(retainedParts), List.copyOf(this.index.roads.values()), this.terrainFill, this.terrainOriginal, slot
                  );
               ArrayList<RoadStructures.Part> parts = new ArrayList<>(r.structures());
               parts.removeAll(members);
               parts.addAll(fresh);
               candidate = candidate.structures(parts);
               ArrayList<RoadStructures.Part> changed = new ArrayList<>(members);
               changed.addAll(fresh);
               Set<Long> touched = new HashSet<>();

               for (RoadRaster.Cell cell : RoadRaster.structures(changed, null).keySet()) {
                  touched.add(BlockPos.asLong(cell.x(), cell.y(), cell.z()));
               }

               ArrayList<RoadIndex.Built> finalRoads = new ArrayList<>();

               for (RoadIndex.Built road : this.index.roads.values()) {
                  finalRoads.add(road == built ? candidate : road);
               }

               Map<Long, List<RoadIndex.Built>> byChunk = new HashMap<>();
               Map<Long, List<RoadIndex.Built>> body = new HashMap<>();
               Set<Long> air = new HashSet<>();
               Set<Long> dry = new HashSet<>();
               Set<Long> needed = new HashSet<>();

               for (long key : touched) {
                  needed.add(new ChunkPos(BlockPos.of(key)).toLong());
               }

               chunks.load(needed);

               for (RoadIndex.Built road : finalRoads) {
                  for (long chunk : road.chunks) {
                     if (needed.contains(chunk)) {
                        byChunk.computeIfAbsent(chunk, k -> new ArrayList<>()).add(road);
                     }
                  }
               }

               for (long key : touched) {
                  BlockPos pos = BlockPos.of(key);

                  for (RoadIndex.Built road : byChunk.getOrDefault(new ChunkPos(pos).toLong(), List.of())) {
                     if (!road.boxes(pos).isEmpty()) {
                        body.computeIfAbsent(key, k -> new ArrayList<>()).add(road);
                     }

                     if (road.clearanceAt(pos)) {
                        air.add(key);
                        if (road.tunnelAt(pos)) {
                           dry.add(key);
                        }
                     }
                  }
               }

               this.validateAndCommit(
                  level,
                  player,
                  new RoadData.PreparedEdit(
                     List.of(candidate),
                     Set.of(r.id()),
                     false,
                     touched,
                     Set.of(),
                     Set.of(),
                     new HashMap<>(this.sidewalkPlaced),
                     Set.copyOf(this.apronPlaced),
                     body,
                     air,
                     dry,
                     byChunk,
                     Set.of(r.a(), r.b()),
                     finalRoads,
                     List.of(),
                     4000000,
                     RoadWorkChunks.held(level)
                  ),
                  false
               );
               if (!signKeys.isEmpty()) {
                  this.smartSigns.applyModes(signKeys, signMode);
               }
            }
         }
      } else {
         throw new IllegalArgumentException("道路已改变，请重新选择龙门架");
      }
   }

   private void checkJoints(List<RoadIndex.Built> replacements, Set<UUID> removed) {
      List<RoadIndex.Built> all = new ArrayList<>();

      for (RoadIndex.Built r : this.index.roads.values()) {
         if (!removed.contains(r.record.id())) {
            all.add(r);
         }
      }

      all.addAll(replacements);
      Set<BlockPos> checked = new HashSet<>();

      for (RoadIndex.Built road : replacements) {
         for (BlockPos p : List.of(road.record.a(), road.record.b())) {
            if (checked.add(p)) {
               List<RoadIndex.Built> links = all.stream().filter(rx -> rx.record.a().equals(p) || rx.record.b().equals(p)).toList();
               if (!links.stream().anyMatch(rx -> rx.record.junction() != null)) {
                  links = links.stream().filter(rx -> transitionEndpoint(rx, p)).toList();
                  UUID sameGroup = links.isEmpty() ? null : links.get(0).record.assembly();
                  if (sameGroup == null || !links.stream().allMatch(rx -> sameGroup.equals(rx.record.assembly()))) {
                     long laneRamps = links.stream().filter(rx -> rx.record.settings().laneRamp()).count();
                     if (laneRamps > 16L) {
                        throw new IllegalArgumentException("同一端点最多连接 16 条匝道，请分组增加端点");
                     }

                     links = links.stream().filter(rx -> !rx.record.settings().laneRamp()).toList();
                     if (links.size() > 3) {
                        throw new IllegalArgumentException("一个端点支持主路和两条支路；更多分叉请增加端点");
                     }

                     if (links.size() >= 2) {
                        List<RoadIndex.Built> rings = links.stream().filter(rx -> rx.mesh.closed()).toList();
                        if (!rings.isEmpty()) {
                           for (RoadIndex.Built ring : rings) {
                              for (RoadIndex.Built feeder : links) {
                                 if (feeder != ring) {
                                    RoadGeometry.V point = (feeder.record.a().equals(p) ? feeder.mesh.first() : feeder.mesh.last()).center();
                                    RoadQueries.Projection q = RoadQueries.project(ring.mesh, point);
                                    if (q.horizontalDistance() > 0.05 || Math.abs(point.y() - q.sample().center().y()) > 0.01) {
                                       throw new IllegalArgumentException("接环道路必须与环道路面等高并接在环道上");
                                    }
                                 }
                              }
                           }
                        } else {
                           boolean ramp = links.stream().anyMatch(rx -> rx.record.settings().style().ramp());
                           if (links.size() == 3 && !ramp) {
                              throw new IllegalArgumentException("Y 字分叉请选择单车道或双车道匝道样式");
                           }

                           RoadIndex.Built first = links.get(0);
                           RoadGeometry.V reference = away(first, p);
                           RoadGeometry.Sample origin = first.record.a().equals(p) ? first.mesh.first() : first.mesh.last();
                           boolean opposite = false;
                           int i = 1;

                           while (true) {
                              if (i < links.size()) {
                                 RoadIndex.Built rx = links.get(i);
                                 RoadGeometry.V d = away(rx, p);
                                 double dot = reference.horizontalUnit().dot(d.horizontalUnit());
                                 double sign = dot < 0.0 ? -1.0 : 1.0;
                                 RoadGeometry.Sample sample = rx.record.a().equals(p) ? rx.mesh.first() : rx.mesh.last();
                                 if (first.record.junction() == null && rx.record.junction() == null) {
                                    RoadTransitions.requireCompatible(
                                       endpointSection(first, p), RoadEndpointSections.orient(endpointSection(rx, p), origin.left().dot(sample.left()) < 0.0)
                                    );
                                 }

                                 if (Math.abs(origin.center().y() - sample.center().y()) > 1.0E-6) {
                                    throw new IllegalArgumentException(
                                       String.format(
                                          Locale.ROOT,
                                          "接点高程不一致：两侧实际路面 Y=%.3f / %.3f（差 %.3f 格），请检查接头高程",
                                          origin.center().y(),
                                          sample.center().y(),
                                          Math.abs(origin.center().y() - sample.center().y())
                                       )
                                    );
                                 }

                                 if (origin.center().sub(sample.center()).horizontalLength() > 1.0E-6) {
                                    throw new IllegalArgumentException(
                                       String.format(
                                          Locale.ROOT,
                                          "接点横向断面未对齐（并非高程不同）：两侧中心偏移 %.3f 格；合并后的实际断面需要续接匹配",
                                          origin.center().sub(sample.center()).horizontalLength()
                                       )
                                    );
                                 }

                                 if (Math.abs(origin.halfWidth() - sample.halfWidth()) > 1.0E-6) {
                                    throw new IllegalArgumentException(
                                       String.format(
                                          Locale.ROOT, "接缝过渡宽度不一致：%s，两侧 %.3f / %.3f 格", p.toShortString(), origin.halfWidth() * 2.0, sample.halfWidth() * 2.0
                                       )
                                    );
                                 }

                                 if (!(Math.abs(dot) < 0.99999999) && (ramp || !(dot > 0.0)) && !(Math.abs(d.y() - sign * reference.y()) > 1.0E-5)) {
                                    if (dot < 0.0) {
                                       opposite = true;
                                    }

                                    i++;
                                    continue;
                                 }

                                 throw new IllegalArgumentException("接缝方向或坡度不连续，请切回智能模式或调整端点");
                              }

                              if (links.size() == 3 && !opposite) {
                                 throw new IllegalArgumentException("Y 字节点需要一条主路和两条支路，不能三条都朝同侧");
                              }
                              break;
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static RoadGeometry.V away(RoadIndex.Built b, BlockPos p) {
      boolean first = b.record.a().equals(p);
      RoadGeometry.Sample sample = first ? b.mesh.first() : b.mesh.last();
      RoadGeometry.V forward = sample.left().left().mul(-1.0);
      double grade = first ? b.record.start().grade() : b.record.end().grade();
      return new RoadGeometry.V(forward.x(), grade, forward.z()).mul(first ? 1.0 : -1.0);
   }

   private void replaceBatch(ServerLevel level, ServerPlayer player, List<RoadIndex.Built> built, Set<UUID> removed) {
      this.replaceBatch(level, player, built, removed, false, Set.of(), List.of());
   }

   private void normalizeTransitions(List<RoadIndex.Built> built, Set<UUID> removed, ServerPlayer player, boolean deleting) {
      Map<UUID, RoadIndex.Built> all = new LinkedHashMap<>(this.index.roads);
      Set<BlockPos> affected = new HashSet<>();

      for (UUID id : removed) {
         RoadIndex.Built old = all.remove(id);
         if (old != null) {
            affected.add(old.record.a());
            affected.add(old.record.b());
         }
      }

      for (RoadIndex.Built r : built) {
         all.put(r.record.id(), r);
         affected.add(r.record.a());
         affected.add(r.record.b());
      }

      Map<BlockPos, List<RoadIndex.Built>> joints = new HashMap<>();

      for (RoadIndex.Built r : all.values()) {
         if (!r.record.settings().style().ramp() && !r.mesh.closed()) {
            for (BlockPos pos : List.of(r.record.a(), r.record.b())) {
               if (transitionEndpoint(r, pos)) {
                  joints.computeIfAbsent(pos, p -> new ArrayList<>()).add(r);
               }
            }
         }
      }

      for (RoadIndex.Built rx : all.values()) {
         RoadRecord record = rx.record;
         if (record.fragment() == null
            && record.assembly() == null
            && !record.settings().style().ramp()
            && !rx.mesh.closed()
            && RoadProfile.modern(record.settings().style())
            && (affected.contains(record.a()) || affected.contains(record.b()))) {
            RoadTransitions.Section a = affected.contains(record.a()) && transitionEndpoint(rx, record.a())
               ? jointSection(rx, record.a(), joints.getOrDefault(record.a(), List.of()), deleting)
               : record.settings().options().ends().start();
            RoadTransitions.Section b = affected.contains(record.b()) && transitionEndpoint(rx, record.b())
               ? jointSection(rx, record.b(), joints.getOrDefault(record.b(), List.of()), deleting)
               : record.settings().options().ends().end();
            RoadGeometry.Settings s = RoadTransitions.ends(record.settings(), a, b);
            if (!s.equals(record.settings())) {
               if (player != null) {
                  requireOwner(player, record.owner());
               }

               RoadIndex.Built next = new RoadIndex.Built(record.settings(s));
               int position = -1;

               for (int i = 0; i < built.size(); i++) {
                  if (built.get(i).record.id().equals(record.id())) {
                     position = i;
                  }
               }

               if (position >= 0) {
                  built.set(position, next);
               } else {
                  built.add(next);
               }

               if (this.index.roads.containsKey(record.id())) {
                  removed.add(record.id());
               }
            }
         }
      }
   }

   private static RoadTransitions.Section jointSection(RoadIndex.Built road, BlockPos pos, List<RoadIndex.Built> links, boolean deleting) {
      boolean first = road.record.a().equals(pos);
      if (RoadEndpointSections.changed(road.mesh, first)) {
         return first ? road.record.settings().options().ends().start() : road.record.settings().options().ends().end();
      } else {
         Optional<RoadIndex.Built> fixed = links.stream()
            .filter(
               r -> !r.record.id().equals(road.record.id())
                     && (r.record.fragment() != null || r.record.assembly() != null || RoadEndpointSections.changed(r.mesh, r.record.a().equals(pos)))
            )
            .findFirst();
         if (fixed.isPresent()) {
            RoadGeometry.Settings target = RoadEndpointSections.orient(
               endpointSection(fixed.get(), pos),
               (fixed.get().record.a().equals(pos) ? fixed.get().mesh.first() : fixed.get().mesh.last())
                     .left()
                     .dot((first ? road.mesh.first() : road.mesh.last()).left())
                  < 0.0
            );
            if (deleting && !RoadTransitions.compatible(road.record.settings(), target)) {
               return null;
            } else {
               RoadTransitions.requireCompatible(road.record.settings(), target);
               return RoadTransitions.Section.of(target);
            }
         } else {
            RoadIndex.Built reference = links.stream().min(Comparator.comparing(r -> r.record.id())).orElse(road);
            RoadGeometry.V referenceLeft = (reference.record.a().equals(pos) ? reference.mesh.first() : reference.mesh.last()).left();
            boolean reverse = (first ? road.mesh.first() : road.mesh.last()).left().dot(referenceLeft) < 0.0;
            RoadGeometry.Settings authored = RoadEndpointSections.orient(authoredSection(road.record.settings()), reverse);
            RoadTransitions.Section section = null;

            for (RoadIndex.Built other : links) {
               if (!other.record.id().equals(road.record.id()) && (!deleting || RoadTransitions.compatible(road.record.settings(), other.record.settings()))) {
                  RoadTransitions.Section next = RoadTransitions.common(
                     authored,
                     RoadEndpointSections.orient(
                        authoredSection(other.record.settings()),
                        (other.record.a().equals(pos) ? other.mesh.first() : other.mesh.last()).left().dot(referenceLeft) < 0.0
                     )
                  );
                  section = section == null ? next : RoadTransitions.common(section.settings(false), next.settings(false));
               }
            }

            return section == null
               ? null
               : RoadTransitions.Section.of(RoadEndpointSections.orient(section.settings(road.record.settings().options().leftTraffic()), reverse));
         }
      }
   }

   void replaceAssembly(ServerLevel level, ServerPlayer player, List<RoadIndex.Built> built, Set<UUID> removed, Set<BlockPos> selectedNodes) {
      this.replaceAssembly(level, player, built, removed, selectedNodes, List.of());
   }

   void replaceCurveControl(ServerLevel level, ServerPlayer player, RoadRecord before, RoadRecord next, UUID deleted) {
      this.replaceBatch(
         level,
         player,
         List.of(new RoadIndex.Built(next)),
         Set.of(before.id()),
         true,
         Set.of(before.a(), before.b()),
         List.of(),
         4000000,
         deleted == null ? Set.of() : Set.of(deleted)
      );
   }

   void replaceAssembly(
      ServerLevel level, ServerPlayer player, List<RoadIndex.Built> built, Set<UUID> removed, Set<BlockPos> selectedNodes, List<RoadData.NodeMove> moves
   ) {
      this.replaceBatch(level, player, built, removed, true, selectedNodes, moves);
   }

   void replaceAssembly(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      Set<BlockPos> selectedNodes,
      List<RoadData.NodeMove> moves,
      Set<UUID> directlyEditedRoads
   ) {
      this.replaceBatch(level, player, built, removed, true, selectedNodes, moves, 4000000, Set.of(), null, directlyEditedRoads);
   }

   void replaceAssembly(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      Set<BlockPos> selectedNodes,
      List<RoadData.NodeMove> moves,
      int limit
   ) {
      if (limit >= 4000000 && limit <= 8000000) {
         this.replaceBatch(level, player, built, removed, true, selectedNodes, moves, limit);
      } else {
         throw new IllegalArgumentException("修改范围上限无效");
      }
   }

   private void queueLegacySupportRepairs() {
      for (RoadIndex.Built corridor : this.index.roads.values()) {
         HashSet<UUID> ids = new HashSet<>();

         for (long chunk : corridor.chunks) {
            ids.addAll(this.index.inChunk(chunk));
         }

         for (UUID id : ids) {
            RoadIndex.Built old = this.index.roads.get(id);
            if (old != null && !this.supportRepair104.contains(id) && RoadIndex.overlapXZ(old.mesh, corridor.mesh, 8.0)) {
               for (RoadStructures.Part part : old.record.structures()) {
                  if (part.pier()
                     && part.material() == RoadStructures.Material.CONCRETE
                     && part.height() > 0.8
                     && RoadClearance.structureInvades(part, corridor.mesh, 4.25)) {
                     this.supportRepair104.add(id);
                     break;
                  }
               }
            }
         }
      }
   }

   private void enlistCycleContinuations(List<RoadIndex.Built> changed, Set<UUID> removed) {
      LinkedHashMap<UUID, RoadRecord> records = new LinkedHashMap<>();

      for (RoadIndex.Built r : this.index.roads.values()) {
         if (!removed.contains(r.record.id())) {
            records.put(r.record.id(), r.record);
         }
      }

      for (RoadIndex.Built rx : changed) {
         records.put(rx.record.id(), rx.record);
      }

      HashSet<UUID> selected = new HashSet<>();
      changed.forEach(rx -> selected.add(rx.record.id()));
      HashSet<UUID> visited = new HashSet<>();

      for (RoadIndex.Built rx : List.copyOf(changed)) {
         if (RoadCycleChain.eligible(rx.record) && visited.add(rx.record.id())) {
            RoadCycleChain chain = RoadCycleChain.of(records, rx.record.id());

            for (RoadCycleChain.Leg leg : chain.legs()) {
               visited.add(leg.road().id());
               if (selected.add(leg.road().id())) {
                  changed.add(new RoadIndex.Built(leg.road().structures(List.of())));
                  removed.add(leg.road().id());
               }
            }
         }
      }
   }

   private void enlistSupportDependencies(List<RoadIndex.Built> changed, Set<UUID> removed) {
      List<RoadIndex.Built> corridors = List.copyOf(changed);
      HashSet<UUID> selected = new HashSet<>();
      changed.forEach(r -> selected.add(r.record.id()));
      HashSet<UUID> groups = new HashSet<>();

      for (RoadIndex.Built old : this.index.roads.values()) {
         if (!removed.contains(old.record.id()) && !selected.contains(old.record.id())) {
            boolean conflict = false;

            for (RoadIndex.Built next : corridors) {
               RoadIndex.Built previous = this.index.roads.get(next.record.id());
               if ((previous == null || !previous.mesh.equals(next.mesh)) && RoadIndex.overlapXZ(old.mesh, next.mesh, 8.0)) {
                  for (RoadStructures.Part part : old.record.structures()) {
                     if (part.pier()
                        && RoadClearance.structureInvades(part, next.mesh, 4.25)
                        && (previous == null || !RoadClearance.structureInvades(part, previous.mesh, 4.25))) {
                        conflict = true;
                        break;
                     }
                  }

                  if (conflict) {
                     break;
                  }
               }
            }

            if (conflict) {
               changed.add(old.structures(List.of()));
               removed.add(old.record.id());
               selected.add(old.record.id());
               if (old.record.junction() != null) {
                  groups.add(old.record.assembly());
               }
            }
         }
      }

      for (RoadIndex.Built oldx : this.index.roads.values()) {
         if (oldx.record.junction() != null
            && groups.contains(oldx.record.assembly())
            && !removed.contains(oldx.record.id())
            && !selected.contains(oldx.record.id())) {
            changed.add(oldx.structures(List.of()));
            removed.add(oldx.record.id());
         }
      }
   }

   private void replaceBatch(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      boolean assembly,
      Set<BlockPos> selectedNodes,
      List<RoadData.NodeMove> moves
   ) {
      this.replaceBatch(level, player, built, removed, assembly, selectedNodes, moves, 4000000);
   }

   private void replaceBatch(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      boolean assembly,
      Set<BlockPos> selectedNodes,
      List<RoadData.NodeMove> moves,
      int editCellLimit
   ) {
      this.replaceBatch(level, player, built, removed, assembly, selectedNodes, moves, editCellLimit, Set.of());
   }

   private void replaceBatch(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      boolean assembly,
      Set<BlockPos> selectedNodes,
      List<RoadData.NodeMove> moves,
      int editCellLimit,
      Set<UUID> deletedPoints
   ) {
      this.replaceBatch(level, player, built, removed, assembly, selectedNodes, moves, editCellLimit, deletedPoints, null);
   }

   List<RoadIndex.Built> previewAssembly(
      ServerLevel level, ServerPlayer player, List<RoadIndex.Built> built, Set<UUID> removed, Set<BlockPos> selectedNodes, List<RoadData.NodeMove> moves
   ) {
      return this.prepareAssembly(level, player, built, removed, selectedNodes, moves, null);
   }

   List<RoadRecord> splitJunctionStructures(ServerLevel level, List<RoadRecord> roads, UUID replaced) {
      ArrayList<RoadIndex.Built> all = new ArrayList<>();

      for (RoadIndex.Built r : this.index.roads.values()) {
         if (!r.record.id().equals(replaced) && roads.stream().noneMatch(n -> n.id().equals(r.record.id()))) {
            all.add(r);
         }
      }

      List<RoadIndex.Built> built = roads.stream().map(RoadIndex.Built::new).toList();
      all.addAll(built);
      ArrayList<RoadRecord> out = new ArrayList<>();
      RoadTerrain.Cache cache = new RoadTerrain.Cache(this.terrainFill, this.terrainOriginal);
      RoadPlanningIndex lookup = new RoadPlanningIndex(all);

      try (RoadWorkChunks chunks = RoadWorkChunks.open(level)) {
         chunks.roads(built);

         for (RoadIndex.Built rx : built) {
            out.add(rx.record.junction() == null ? rx.record : StructurePlanner.plan(level, rx, all, this.terrainFill, this.terrainOriginal, cache, lookup));
         }
      }

      return List.copyOf(out);
   }

   RoadData.SplitPrepared prepareSplit(
      ServerLevel level, ServerPlayer player, List<RoadIndex.Built> built, Set<UUID> removed, RoadData.NodeMove node, boolean junction
   ) {
      RoadData.SplitPrepared var61;
      try (
         RoadEditRasters rasters = RoadEditRasters.open();
         RoadWorkChunks chunks = RoadWorkChunks.open(level);
      ) {
         ArrayList<RoadIndex.Built> old = new ArrayList<>();

         for (UUID id : removed) {
            RoadIndex.Built r = this.index.roads.get(id);
            if (r != null) {
               old.add(r);
            }
         }

         ArrayList<RoadIndex.Built> finalRoads = new ArrayList<>();

         for (RoadIndex.Built r : this.index.roads.values()) {
            if (!removed.contains(r.record.id())) {
               finalRoads.add(r);
            }
         }

         finalRoads.addAll(built);
         chunks.roads(built);
         chunks.roads(old);
         LongOpenHashSet touched = new LongOpenHashSet();

         for (RoadIndex.Built rx : old) {
            touched.addAll(rx.cells.keySet());
            touched.addAll(rx.clearanceCells);
         }

         for (RoadIndex.Built rx : built) {
            touched.addAll(rx.cells.keySet());
            touched.addAll(rx.clearanceCells);
         }

         touched.add(node.to().asLong());
         Map<Long, BlockState> sidewalks = new HashMap<>(this.sidewalkPlaced);
         Set<Long> aprons = new HashSet<>(this.apronPlaced);
         if (junction) {
            ArrayList<RoadIndex.Built> terrainRoads = new ArrayList<>(old);
            terrainRoads.addAll(built);
            sidewalks = SmartSidewalks.plan(finalRoads, level, this.terrainFill, this.sidewalkPlaced, this.apronPlaced, built, terrainRoads);
            aprons = SmartRoadAprons.plan(sidewalks, finalRoads, level, this.terrainOriginal, this.sidewalkPlaced, this.apronPlaced, terrainRoads);
            ObjectIterator finalByChunk = this.sidewalkPlaced.entrySet().iterator();

            while (finalByChunk.hasNext()) {
               Entry<Long, BlockState> e = (Entry<Long, BlockState>)finalByChunk.next();
               if (!e.getValue().equals(sidewalks.get(e.getKey()))) {
                  touched.add(e.getKey());
               }
            }

            for (Entry<Long, BlockState> e : sidewalks.entrySet()) {
               if (!e.getValue().equals(this.sidewalkPlaced.get(e.getKey()))) {
                  touched.add(e.getKey());
               }
            }
         }

         if (touched.size() > 4000000) {
            throw new IllegalArgumentException("一次分割涉及的世界范围过大");
         }

         Map<Long, List<RoadIndex.Built>> body = new HashMap<>();
         Map<Long, List<RoadIndex.Built>> finalByChunk = new HashMap<>();
         Set<Long> air = new HashSet<>();
         Set<Long> dry = new HashSet<>();
         Set<Long> touchedChunks = new HashSet<>();
         Map<Long, List<BlockPos>> touchedByChunk = new HashMap<>();
         LongIterator endpoints = touched.iterator();

         while (endpoints.hasNext()) {
            long key = (Long)endpoints.next();
            BlockPos pos = BlockPos.of(key);
            long chunk = new ChunkPos(pos).toLong();
            touchedChunks.add(chunk);
            touchedByChunk.computeIfAbsent(chunk, k -> new ArrayList<>()).add(pos);
         }

         chunks.load(touchedChunks);

         for (RoadIndex.Built rx : finalRoads) {
            for (long chunk : rx.chunks) {
               if (touchedChunks.contains(chunk)) {
                  finalByChunk.computeIfAbsent(chunk, k -> new ArrayList<>()).add(rx);

                  for (BlockPos pos : touchedByChunk.get(chunk)) {
                     long key = pos.asLong();
                     if (!rx.boxes(pos).isEmpty()) {
                        body.computeIfAbsent(key, k -> new ArrayList<>()).add(rx);
                     }

                     if (rx.clearanceAt(pos)) {
                        air.add(key);
                        if (rx.tunnelAt(pos)) {
                           dry.add(key);
                        }
                     }
                  }
               }
            }
         }

         Set<BlockPos> endpointsx = new HashSet<>();

         for (RoadIndex.Built rx : built) {
            endpointsx.add(rx.record.a());
            endpointsx.add(rx.record.b());
         }

         endpointsx.add(node.to());
         LinkedHashMap<UUID, RoadRecord> finalRecords = new LinkedHashMap<>();

         for (RoadIndex.Built rx : finalRoads) {
            finalRecords.put(rx.record.id(), rx.record);
         }

         for (RoadIndex.Built rx : built) {
            if (LaneTopology.metadata(rx.record).link() != null) {
               endpointsx.addAll(LaneRamps.contactEndpoints(finalRecords, rx.record));
            }
         }

         RoadData.PreparedEdit edit = new RoadData.PreparedEdit(
            List.copyOf(built),
            Set.copyOf(removed),
            false,
            touched,
            Set.of(),
            Set.of(node.to().asLong()),
            sidewalks,
            aprons,
            body,
            air,
            dry,
            finalByChunk,
            endpointsx,
            List.copyOf(finalRoads),
            List.of(node),
            4000000,
            Set.copyOf(touchedChunks)
         );
         LinkedHashMap<UUID, RoadRecord> dependencies = new LinkedHashMap<>();

         for (UUID idx : removed) {
            RoadIndex.Built previous = this.index.roads.get(idx);
            if (previous != null) {
               dependencies.put(idx, previous.record);
            }
         }

         this.validateAndCommit(level, player, edit, true);
         var61 = new RoadData.SplitPrepared(edit, this.index.revision(), Map.copyOf(dependencies));
      }

      return var61;
   }

   void commitSplit(ServerLevel level, ServerPlayer player, RoadData.SplitPrepared prepared, Runnable metadata) {
      if (prepared.revision() != this.index.revision()) {
         throw new IllegalArgumentException("道路已被修改，请重新选择");
      } else {
         for (Entry<UUID, RoadRecord> entry : prepared.dependencies().entrySet()) {
            RoadIndex.Built live = this.index.roads.get(entry.getKey());
            if (live == null || live.record != entry.getValue()) {
               throw new IllegalArgumentException("相关道路元数据已改变，请重新预览");
            }
         }

         RoadData.PreparedEdit edit = prepared.edit();

         try (
            RoadEditRasters rasters = RoadEditRasters.open();
            RoadWorkChunks chunks = RoadWorkChunks.open(level);
         ) {
            chunks.load(edit.chunks());
            this.validateAndCommit(level, player, edit, true);
            RoadData.EditSnapshot snapshot = new RoadData.EditSnapshot(level, edit, true);
            Consumer<UUID> callback = this.index.changed;
            this.index.changed = id -> {
            };
            this.exactSplit = true;

            RoadNetwork.PreparedBatch packet;
            try {
               this.validateAndCommit(level, player, edit, false);
               splitFault.accept("world");
               metadata.run();
               splitFault.accept("metadata");
               packet = RoadNetwork.prepareBatch(level, edit.built().stream().map(rx -> rx.record).toList(), edit.removed(), edit.moves().get(0).to());
               this.setDirty();
            } catch (RuntimeException var21) {
               snapshot.restore(level, var21);
               throw var21;
            } finally {
               this.index.changed = callback;
               this.exactSplit = false;
            }

            for (RoadIndex.Built r : edit.built()) {
               this.smartSigns.changed(r.record.id());
            }

            RoadNetwork.broadcastBatch(level, edit.built().stream().map(rx -> rx.record).toList(), edit.removed(), packet);
         }
      }
   }

   private static void checkedSetBlock(ServerLevel level, BlockPos pos, BlockState state, int flags) {
      level.setBlock(pos, state, flags);
      requireBlockState(level, pos, state);
   }

   private static void requireBlockState(ServerLevel level, BlockPos pos, BlockState state) {
      BlockState actual = level.getBlockState(pos);
      if (!accepted(actual, state)) {
         throw new IllegalStateException("世界拒绝写入道路方块：" + pos.toShortString() + " target=" + state + " actual=" + actual);
      }
   }

   /**
    * The world (biome snow, other mods) can normalise some properties immediately after a
    * write. Treating a differing {@code snowy} flag as a hard failure aborted the whole road
    * edit, which made tunnel/bridge deletions in mountains fail with
    * "世界拒绝写入道路方块 ... snowy=true".
    */
   private static boolean accepted(BlockState actual, BlockState target) {
      if (actual.equals(target)) {
         return true;
      }

      if (actual.getBlock() != target.getBlock()) {
         return false;
      }

      for (Property<?> property : target.getProperties()) {
         if (property == BlockStateProperties.SNOWY) {
            continue;
         }

         if (!Objects.equals(actual.getValue(property), target.getValue(property))) {
            return false;
         }
      }

      return true;
   }

   private static void restoreBlocks(ServerLevel level, Map<Long, BlockState> states, Map<Long, CompoundTag> entities, Consumer<RuntimeException> failed) {
      restoreBlocks(level, states, entities, failed, false);
   }

   private static void restoreBlocks(
      ServerLevel level, Map<Long, BlockState> states, Map<Long, CompoundTag> entities, Consumer<RuntimeException> failed, boolean terrainRestore
   ) {
      LinkedHashMap<Long, BlockState> changed = new LinkedHashMap<>();

      for (Entry<Long, BlockState> entry : states.entrySet()) {
         try {
            BlockPos pos = BlockPos.of(entry.getKey());
            BlockState previous = level.getBlockState(pos);
            checkedSetBlock(level, pos, entry.getValue(), 18);
            if (!previous.equals(entry.getValue())) {
               changed.put(entry.getKey(), previous);
            }
         } catch (RuntimeException var14) {
            failed.accept(var14);
         }
      }

      if (terrainRestore) {
         for (int pass = 0; pass < 2; pass++) {
            for (Entry<Long, BlockState> entry : states.entrySet()) {
               BlockPos pos = BlockPos.of(entry.getKey());
               BlockState state = entry.getValue();
               if (state.getBlock() instanceof BushBlock && !state.canSurvive(level, pos)) {
                  BlockState empty = state.getFluidState().is(FluidTags.WATER) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                  entry.setValue(empty);
                  checkedSetBlock(level, pos, empty, 18);
               }
            }
         }
      }

      for (Entry<Long, CompoundTag> entryx : entities.entrySet()) {
         try {
            BlockPos pos = BlockPos.of(entryx.getKey());
            BlockState state = states.get(entryx.getKey());
            requireBlockState(level, pos, state);
            BlockEntity be = BlockEntity.loadStatic(pos, state, entryx.getValue().copy(), level.registryAccess());
            if (be != null && state.hasBlockEntity() && be.getType().isValid(state)) {
               level.removeBlockEntity(pos);
               level.setBlockEntity(be);
               if (level.getBlockEntity(pos) == be && !be.isRemoved() && be.getBlockPos().equals(pos) && be.getBlockState().equals(state)) {
                  be.setChanged();
                  level.sendBlockUpdated(pos, state, state, 3);
                  continue;
               }

               throw new IllegalStateException("无法挂接恢复的端点/填充实体：" + pos.toShortString());
            }

            throw new IllegalStateException("无法恢复端点/填充实体：" + pos.toShortString());
         } catch (RuntimeException var16) {
            failed.accept(var16);
         }
      }

      for (Entry<Long, BlockState> entryx : changed.entrySet()) {
         try {
            BlockPos pos = BlockPos.of(entryx.getKey());
            BlockState state = states.get(entryx.getKey());
            requireBlockState(level, pos, state);
            entryx.getValue().updateIndirectNeighbourShapes(level, pos, 2, 511);
            state.updateNeighbourShapes(level, pos, 2, 511);
            state.updateIndirectNeighbourShapes(level, pos, 2, 511);
         } catch (RuntimeException var13) {
            failed.accept(var13);
         }
      }

      for (Entry<Long, BlockState> entryx : states.entrySet()) {
         try {
            BlockPos pos = BlockPos.of(entryx.getKey());
            BlockState state = entryx.getValue();
            requireBlockState(level, pos, state);
            BlockState stable = Block.updateFromNeighbourShapes(state, level, pos);
            if (!accepted(stable, state)) {
               throw new IllegalStateException("世界拒绝写入道路方块：" + pos.toShortString() + " target=" + state + " supported=" + stable);
            }

            BlockEntity entity = level.getBlockEntity(pos);
            CompoundTag tag = entities.get(entryx.getKey());
            if (tag != null
               && (
                  entity == null
                     || entity.isRemoved()
                     || !entity.getBlockPos().equals(pos)
                     || !entity.getBlockState().equals(state)
                     || !entity.getType().isValid(state)
                     || !entity.saveWithFullMetadata(level.registryAccess()).equals(tag)
               )) {
               throw new IllegalStateException("恢复后端点/填充实体不一致：" + pos.toShortString());
            }

            if (!state.hasBlockEntity() && entity != null) {
               throw new IllegalStateException("恢复后残留方块实体：" + pos.toShortString());
            }
         } catch (RuntimeException var15) {
            failed.accept(var15);
         }
      }
   }

   private static Map<UUID, CompoundTag> copyTags(Map<UUID, CompoundTag> source) {
      LinkedHashMap<UUID, CompoundTag> out = new LinkedHashMap<>();
      source.forEach((key, value) -> out.put(key, value.copy()));
      return out;
   }

   private static Map<UUID, CompoundTag> copyTags(Map<UUID, CompoundTag> source, Set<UUID> ids) {
      LinkedHashMap<UUID, CompoundTag> out = new LinkedHashMap<>();

      for (UUID id : ids) {
         CompoundTag value = source.get(id);
         if (value != null) {
            out.put(id, value.copy());
         }
      }

      return out;
   }

   private Set<UUID> junctionGroups(RoadData.PreparedEdit edit) {
      HashSet<UUID> groups = new HashSet<>();

      for (RoadIndex.Built road : edit.built()) {
         junctionGroups(groups, road.record);
      }

      for (UUID id : edit.removed()) {
         RoadIndex.Built previous = this.index.roads.get(id);
         if (previous != null) {
            junctionGroups(groups, previous.record);
         }
      }

      return groups;
   }

   private static void junctionGroups(Set<UUID> groups, RoadRecord record) {
      if (record.junction() != null && record.assembly() != null) {
         groups.add(record.assembly());
      }

      LanePoints.Link link = LaneTopology.metadata(record).link();
      if (link != null && link.to().junction() != null) {
         groups.add(link.to().junction());
      }
   }

   public static void expirePreviews() {
      long now = System.nanoTime();
      RoadSplitter.expire(now);

      for (RoadData data : new ArrayList<>(PREVIEW_DATA)) {
         data.prunePreviews(now);
      }
   }

   public static void forgetPreviews(UUID owner) {
      for (RoadData data : new ArrayList<>(PREVIEW_DATA)) {
         data.discardPreviews(owner);
      }
   }

   public static void forgetPreview(UUID owner, UUID token) {
      if (owner != null && token != null) {
         for (RoadData data : new ArrayList<>(PREVIEW_DATA)) {
            RoadData.PreparedEntry entry = data.preparedEdits.get(token);
            if (entry != null && owner.equals(entry.owner())) {
               data.preparedEdits.remove(token);
            }

            if (data.preparedEdits.isEmpty()) {
               PREVIEW_DATA.remove(data);
            }
         }
      }
   }

   public static void clearPreviews() {
      for (RoadData data : new ArrayList<>(PREVIEW_DATA)) {
         data.preparedEdits.clear();
      }

      PREVIEW_DATA.clear();
   }

   void prunePreviews(long now) {
      this.preparedEdits.entrySet().removeIf(e -> now - e.getValue().created() > 600000000000L || e.getValue().revision() != this.index.contentRevision());
      if (this.preparedEdits.isEmpty()) {
         PREVIEW_DATA.remove(this);
      }
   }

   public void discardPreviews(UUID owner) {
      this.preparedEdits.entrySet().removeIf(e -> Objects.equals(e.getValue().owner(), owner));
      if (this.preparedEdits.isEmpty()) {
         PREVIEW_DATA.remove(this);
      }
   }

   int preparedPreviewCount() {
      return this.preparedEdits.size();
   }

   List<RoadIndex.Built> prepareAssembly(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      Set<BlockPos> selectedNodes,
      List<RoadData.NodeMove> moves,
      UUID token
   ) {
      return this.prepareAssembly(level, player, built, removed, selectedNodes, moves, token, Set.of(), true);
   }

   List<RoadIndex.Built> prepareAssembly(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      Set<BlockPos> selectedNodes,
      List<RoadData.NodeMove> moves,
      UUID token,
      Set<UUID> directlyEditedRoads,
      boolean validatePreview
   ) {
      ArrayList<RoadData.PreparedEdit> result = new ArrayList<>();

      List var16;
      try (RoadPlanInputs inputs = RoadPlanInputs.open(level, token != null)) {
         this.replaceBatch(level, player, built, removed, true, selectedNodes, moves, 4000000, Set.of(), result, directlyEditedRoads, validatePreview);
         RoadData.PreparedEdit edit = result.get(0);
         if (token != null) {
            UUID owner = player == null ? null : player.getUUID();
            this.preparedEdits.entrySet().removeIf(e -> Objects.equals(e.getValue().owner(), owner) || expired(e.getValue()));
            this.preparedEdits.put(token, new RoadData.PreparedEntry(edit, inputs, owner, this.index.contentRevision(), System.nanoTime()));
            PREVIEW_DATA.add(this);

            while (
               this.preparedEdits.size() > 1
                  && (
                     this.preparedEdits.size() > 2
                        || this.preparedEdits.values().stream().mapToLong(e -> (long)e.edit().touched().size()).sum() > 4000000L
                        || this.preparedEdits.values().stream().mapToLong(e -> (long)e.inputs().retainedStates()).sum() > 4000000L
                  )
            ) {
               this.preparedEdits.remove(this.preparedEdits.keySet().iterator().next());
            }
         }

         var16 = List.copyOf(edit.built());
      }

      return var16;
   }

   private static boolean expired(RoadData.PreparedEntry e) {
      return System.nanoTime() - e.created() > 600000000000L;
   }

   private void reserveBatchFixtures(
      ServerLevel level,
      List<RoadIndex.Built> built,
      List<RoadIndex.Built> planning,
      Map<Long, BlockState> retained,
      Map<Long, BlockState> originals,
      Map<BlockPos, List<AABB>> terrainCache,
      RoadPlanningIndex lookup,
      Set<UUID> explicit,
      Set<UUID> removed
   ) {
      for (int i = 0; i < built.size(); i++) {
         RoadIndex.Built old = built.get(i);
         RoadInfrastructure.Config infra = old.record.settings().options().infrastructure();
         List<RoadGantry.Edit> edits = infra.gantryEdits()
            .stream()
            .map(e -> e.automatic() && this.authoredGantry(old.record.id(), e.slot()) ? e.parameters(e.offset(), e.clearance(), e.kind(), e.reverse()) : e)
            .toList();
         if (!edits.equals(infra.gantryEdits())) {
            RoadIndex.Built next = new RoadIndex.Built(
               old.record.settings(old.record.settings().options(old.record.settings().options().infrastructure(infra.fixedStations(edits))))
            );
            built.set(i, next);
            int slot = planning.indexOf(old);
            planning.set(slot, next);
            lookup.replace(slot, next);
         }
      }

      lookup.editHistory(built, this.index.roads, explicit);
      lookup.optionalAutomaticAnchors(this.optionalAutomaticAnchors(built));

      for (int ix = 0; ix < built.size(); ix++) {
         RoadIndex.Built r = built.get(ix);
         RoadIndex.Built next = r.structures(List.of());
         built.set(ix, next);
         int slot = planning.indexOf(r);
         planning.set(slot, next);
         lookup.replace(slot, next);
      }

      lookup.resetCyclePlans();

      for (int ix = 0; ix < built.size(); ix++) {
         RoadIndex.Built r = built.get(ix);
         RoadIndex.Built next = StructurePlanner.prepareCrossings(level, r, retained, originals, terrainCache, lookup);
         if (next != r) {
            built.set(ix, next);
            int slot = planning.indexOf(r);
            planning.set(slot, next);
            lookup.replace(slot, next);
         }
      }

      lookup.preservedGantries(RoadGantryRetention.capture(built, this.index.roads, explicit, removed));
      boolean authored = built.stream()
         .anyMatch(
            rx -> rx.record.junction() == null
                  && rx.record.toll() == null
                  && rx.mesh.settings().structure() != RoadGeometry.Structure.TUNNEL
                  && !rx.record.settings().options().infrastructure().gantryEdits().isEmpty()
         );

      for (StructurePlanner.Stage stage : authored
         ? List.of(StructurePlanner.Stage.FIXED, StructurePlanner.Stage.GANTRIES, StructurePlanner.Stage.PAVING)
         : List.of(StructurePlanner.Stage.PAVING)) {
         for (int ixx = 0; ixx < built.size(); ixx++) {
            RoadIndex.Built r = built.get(ixx);
            RoadIndex.Built next = r.planned(StructurePlanner.reserve(level, r, planning, retained, originals, terrainCache, lookup, stage));
            built.set(ixx, next);
            int slot = planning.indexOf(r);
            planning.set(slot, next);
            lookup.replace(slot, next);
         }
      }
   }

   private boolean authoredGantry(UUID id, int slot) {
      RoadIndex.Built old = this.index.roads.get(id);
      return old != null && RoadGantry.exists(old.mesh, slot) && !RoadGantry.members(old.mesh, slot, old.record.structures()).isEmpty()
         ? true
         : this.smartSigns
            .road(id)
            .stream()
            .anyMatch(
               s -> s.key().slot() == slot
                     && (
                        s.mode() != SmartSigns.Mode.NORMAL && s.mode() != SmartSigns.Mode.EXIT_AUTO
                           || !s.text().isEmpty()
                           || !s.drafts().isEmpty()
                           || !s.hiddenHeaders().isEmpty()
                           || !s.hiddenAccessories().isEmpty()
                           || !s.closedLanes().isEmpty()
                     )
            );
   }

   private Map<UUID, Set<Integer>> optionalAutomaticAnchors(List<RoadIndex.Built> candidates) {
      HashMap<UUID, Set<Integer>> optional = new HashMap<>();

      for (RoadIndex.Built next : candidates) {
         RoadIndex.Built old = this.index.roads.get(next.record.id());
         if (old != null) {
            RoadInfrastructure.Config before = old.mesh.settings().options().infrastructure();
            RoadInfrastructure.Config after = next.mesh.settings().options().infrastructure();
            if (after.fixedStations()
               && before.gantry() == after.gantry()
               && old.mesh.settings().structure() != RoadGeometry.Structure.TUNNEL
               && next.mesh.settings().structure() != RoadGeometry.Structure.TUNNEL) {
               for (RoadGantry.Edit edit : after.gantryEdits()) {
                  RoadPlanningBudget.check();
                  if (edit.anchored()
                     && !edit.independent()
                     && edit.kind() == RoadInfrastructure.Gantry.AUTO
                     && edit.offset() == 0.0
                     && edit.clearance() == 5.6
                     && !edit.reverse()) {
                     RoadGantry.Edit prior = before.gantryEdits().stream().filter(e -> e.slot() == edit.slot()).findFirst().orElse(null);
                     if (prior == null
                        || prior.anchored()
                           && !prior.independent()
                           && prior.kind() == RoadInfrastructure.Gantry.AUTO
                           && prior.offset() == 0.0
                           && prior.clearance() == 5.6
                           && !prior.reverse()) {
                        int slot = edit.slot();
                        if (!this.smartSigns
                           .road(old.record.id())
                           .stream()
                           .anyMatch(
                              s -> s.key().slot() == slot
                                    && (
                                       s.mode() != SmartSigns.Mode.NORMAL && s.mode() != SmartSigns.Mode.EXIT_AUTO
                                          || !s.text().isEmpty()
                                          || !s.drafts().isEmpty()
                                          || !s.hiddenHeaders().isEmpty()
                                          || !s.hiddenAccessories().isEmpty()
                                          || !s.closedLanes().isEmpty()
                                    )
                           )) {
                           try {
                              if (RoadGantry.exists(old.mesh, slot)
                                 && RoadGantry.exists(next.mesh, slot)
                                 && RoadGantry.station(old.mesh, slot).kind() != RoadInfrastructure.Gantry.OFF
                                 && RoadGantry.members(old.mesh, slot, old.record.structures()).isEmpty()) {
                                 optional.computeIfAbsent(next.record.id(), id -> new HashSet<>()).add(slot);
                              }
                           } catch (IllegalArgumentException var13) {
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return optional;
   }

   boolean preparedValid(ServerLevel level, UUID token) {
      RoadData.PreparedEntry entry = this.preparedEdits.get(token);
      if (entry != null && !expired(entry) && entry.revision() == this.index.contentRevision()) {
         try {
            entry.inputs().validate(level);
            return true;
         } catch (IllegalArgumentException var5) {
            return false;
         }
      } else {
         return false;
      }
   }

   void adoptRailbeds(ServerLevel level) {
      if (!this.railbeds.pending.isEmpty()) {
         throw new IllegalArgumentException("旧铁路事务正在恢复，请稍候");
      } else {
         for (RailbedStore.Entry entry : List.copyOf(this.railbeds.entries.values())) {
            if (entry.road.legacyRailway()) {
               RoadRecord r = entry.road;
               RoadGeometry.Settings settings = r.settings()
                  .structure(entry.mode)
                  .options(r.settings().options().railbed(new RailbedProfile(entry.paths, entry.width, entry.options)).outerRail(RoadProfile.OuterRail.ON));
               RoadRecord next = new RoadRecord(
                  r.id(),
                  r.owner(),
                  r.a(),
                  r.b(),
                  r.start(),
                  r.end(),
                  settings,
                  false,
                  5,
                  r.structures(),
                  r.endCaps(),
                  141,
                  null,
                  r.alignment(),
                  r.furniturePhase()
               );
               entry.original.forEach((key, state) -> this.terrainOriginal.putIfAbsent(key, state));
               this.index.put(RoadIndex.Built.loading(next));
               this.railbeds.entries.put(r.id(), RailbedRoads.entry(next));
               this.setDirty();
            }
         }
      }
   }

   void buildPrepared(ServerLevel level, ServerPlayer player, UUID token) {
      this.buildPrepared(level, player, token, null);
   }

   void buildPrepared(ServerLevel level, ServerPlayer player, UUID token, Runnable metadata) {
      RoadData.PreparedEntry entry = this.preparedEdits.get(token);
      if (entry != null && !expired(entry)) {
         if (!Objects.equals(entry.owner(), player == null ? null : player.getUUID())) {
            throw new IllegalArgumentException("预览不属于当前玩家");
         } else if (entry.revision() != this.index.contentRevision()) {
            this.preparedEdits.remove(token);
            throw new IllegalArgumentException("道路已改变，请重新预览");
         } else {
            try (
               RoadEditRasters rasters = RoadEditRasters.open();
               RoadPlanningBudget budget = RoadPlanningBudget.cancellable("提交已验证预览");
               RoadTimings timing = RoadTimings.start("preview_commit", this.index.roads.size(), entry.edit().built().size());
               RoadWorkChunks chunks = RoadWorkChunks.open(level);
            ) {
               chunks.load(entry.edit().chunks());
               entry.inputs().validate(level);
               timing.stage("cached_world_inputs");
               if (metadata == null) {
                  this.validateAndCommit(level, player, entry.edit(), false);
               } else {
                  RoadData.PreparedEdit edit = entry.edit();
                  RoadData.EditSnapshot snapshot = new RoadData.EditSnapshot(level, edit, true);
                  Consumer<UUID> callback = this.index.changed;
                  this.index.changed = id -> {
                  };
                  this.exactSplit = true;

                  RoadNetwork.PreparedBatch packet;
                  try {
                     this.validateAndCommit(level, player, edit, false);
                     metadata.run();
                     packet = RoadNetwork.prepareBatch(
                        level,
                        edit.built().stream().map(rx -> rx.record).toList(),
                        edit.removed(),
                        edit.moves().isEmpty() ? edit.built().get(0).record.a() : edit.moves().get(0).to()
                     );
                     this.setDirty();
                  } catch (RuntimeException var44) {
                     snapshot.restore(level, var44);
                     throw var44;
                  } finally {
                     this.index.changed = callback;
                     this.exactSplit = false;
                  }

                  LinkedHashSet ids = new LinkedHashSet<>(edit.removed());
                  edit.built().forEach(rx -> ids.add(rx.record.id()));
                  ids.forEach(callback);

                  for (RoadIndex.Built r : edit.built()) {
                     this.smartSigns.changed(r.record.id());
                  }

                  RoadNetwork.broadcastBatch(level, edit.built().stream().map(rx -> rx.record).toList(), edit.removed(), packet);
               }

               timing.stage("validation_commit_sync");
            } finally {
               this.preparedEdits.remove(token);
            }
         }
      } else {
         this.preparedEdits.remove(token);
         throw new IllegalArgumentException("预览缓存已过期，请重新预览");
      }
   }

   private List<RoadIndex.Built> capPlanning(List<RoadIndex.Built> built, Set<UUID> removed) {
      built.removeIf(r -> this.confirmedLaneDeletes.contains(r.record.id()));
      ArrayList<RoadIndex.Built> planning = new ArrayList<>();

      for (RoadIndex.Built old : this.index.roads.values()) {
         if (!removed.contains(old.record.id()) && !this.confirmedLaneDeletes.contains(old.record.id())) {
            planning.add(old);
         }
      }

      planning.addAll(built);

      for (int i = 0; i < built.size(); i++) {
         RoadIndex.Built oldx = built.get(i);
         int mask = 0;
         if (!oldx.mesh.closed() && !oldx.record.settings().style().ramp() && LaneTopology.metadata(oldx.record).link() == null) {
            for (boolean first : new boolean[]{true, false}) {
               BlockPos pos = first ? oldx.record.a() : oldx.record.b();
               boolean shared = planning.stream()
                  .anyMatch(
                     r -> !r.record.id().equals(oldx.record.id())
                           && LaneTopology.metadata(r.record).link() == null
                           && (r.record.a().equals(pos) || r.record.b().equals(pos))
                  );
               if (!shared) {
                  mask |= first ? 1 : 2;
               }
            }
         }

         RoadIndex.Built next = oldx.record.endCaps() == mask && oldx.record.buildVersion() == 17 ? oldx : oldx.caps(mask);
         built.set(i, next);
         planning.set(planning.indexOf(oldx), next);
      }

      return planning;
   }

   private void replaceBatch(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      boolean assembly,
      Set<BlockPos> selectedNodes,
      List<RoadData.NodeMove> moves,
      int editCellLimit,
      Set<UUID> deletedPoints,
      List<RoadData.PreparedEdit> previewResult
   ) {
      this.replaceBatch(
         level,
         player,
         built,
         removed,
         assembly,
         selectedNodes,
         moves,
         editCellLimit,
         deletedPoints,
         previewResult,
         built.stream().map(r -> r.record.id()).collect(Collectors.toUnmodifiableSet())
      );
   }

   private void replaceBatch(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      boolean assembly,
      Set<BlockPos> selectedNodes,
      List<RoadData.NodeMove> moves,
      int editCellLimit,
      Set<UUID> deletedPoints,
      List<RoadData.PreparedEdit> previewResult,
      Set<UUID> directlyEditedRoads
   ) {
      this.replaceBatch(level, player, built, removed, assembly, selectedNodes, moves, editCellLimit, deletedPoints, previewResult, directlyEditedRoads, true);
   }

   private void replaceBatch(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      boolean assembly,
      Set<BlockPos> selectedNodes,
      List<RoadData.NodeMove> moves,
      int editCellLimit,
      Set<UUID> deletedPoints,
      List<RoadData.PreparedEdit> previewResult,
      Set<UUID> directlyEditedRoads,
      boolean validatePreview
   ) {
      if (!built.stream().anyMatch(r -> r.record.legacyRailway())
         && !removed.stream().anyMatch(id -> this.index.roads.containsKey(id) && this.index.roads.get(id).record.legacyRailway())) {
         try (
            RoadQueries.Scope projections = RoadQueries.scope();
            RoadEditRasters rasters = RoadEditRasters.open();
            RoadPlanningBudget budget = RoadPlanningBudget.cancellable("道路事务预检查");
            RoadTimings timing = RoadTimings.start("edit", this.index.roads.size(), built.size());
            RoadWorkChunks workChunks = RoadWorkChunks.open(level);
         ) {
            boolean deleting = built.isEmpty();
            List<RoadIndex.Built> var67 = new ArrayList<>(built);
            Set<UUID> explicit = Set.copyOf(directlyEditedRoads);
            HashSet<UUID> inputIds = new HashSet<>(removed);
            var67.forEach(r -> inputIds.add(r.record.id()));
            if (!inputIds.containsAll(explicit)) {
               throw new IllegalArgumentException("本次道路事务缺少直接编辑目标");
            }

            Set<UUID> var68 = new HashSet<>(removed);
            var68.addAll(this.confirmedLaneDeletes);
            var67.removeIf(b -> this.confirmedLaneDeletes.contains(b.record.id()));
            ElevatedAccessRoads.protect(this, var67, var68);
            RailbedJoints.normalize(this.index, var67, var68, player);
            this.normalizeTransitions(var67, var68, player, deleting);
            timing.stage("normalize_transitions");
            RoadPlanningBudget.phase("normalize_transitions");
            List<RoadIndex.Built> requested = new ArrayList<>(var67);
            HashMap<UUID, List<RoadBoundsIndex.Bounds>> derived = new HashMap<>();

            for (RoadIndex.Built candidate : var67) {
               if (!explicit.contains(candidate.record.id())) {
                  List<RoadBoundsIndex.Bounds> scope = RoadEditScope.endpointRails(this.index.roads.get(candidate.record.id()), candidate);
                  if (scope != null) {
                     derived.put(candidate.record.id(), scope);
                  }
               }
            }

            if (!deleting) {
               for (RoadIndex.Built old : this.index.roads.values()) {
                  if (!var68.contains(old.record.id()) && LaneTopology.metadata(old.record).link() != null) {
                     for (RoadIndex.Built proposed : var67) {
                        if (!derived.containsKey(proposed.record.id()) || RoadEditScope.affects(derived.get(proposed.record.id()), old)) {
                           RoadInteractions.influences(proposed, old);
                        }
                     }
                  }
               }
            }

            for (UUID id : var68) {
               if (this.index.roads.containsKey(id)) {
                  requested.add(this.index.roads.get(id));
               }
            }

            for (RoadIndex.Built oldx : this.index.roads.values()) {
               final RoadIndex.Built currentOld = oldx;
               if (!var68.contains(oldx.record.id())
                  && (!deleting || LaneTopology.metadata(oldx.record).link() == null)
                  && requested.stream()
                     .anyMatch(
                        r -> (!derived.containsKey(r.record.id()) || RoadEditScope.affects(derived.get(r.record.id()), currentOld))
                              && RoadInteractions.influences(r, currentOld)
                     )) {
                  var67.add(oldx.structures(List.of()));
                  var68.add(oldx.record.id());
               }
            }

            HashSet<UUID> supportGroups = new HashSet<>();

            for (RoadIndex.Built r : var67) {
               if (r.record.junction() != null) {
                  supportGroups.add(r.record.assembly());
               }
            }

            for (RoadIndex.Built oldxx : this.index.roads.values()) {
               final RoadIndex.Built currentOldxx = oldxx;
               if (oldxx.record.junction() != null
                  && supportGroups.contains(oldxx.record.assembly())
                  && !var68.contains(oldxx.record.id())
                  && var67.stream().noneMatch(rx -> rx.record.id().equals(currentOldxx.record.id()))) {
                  var67.add(oldxx.structures(List.of()));
                  var68.add(oldxx.record.id());
               }
            }

            timing.stage("affected_structures");
            RoadPlanningBudget.phase("affected_structures");
            if (!deleting) {
               AttachedPoints.reconcile(this, var67, var68, false, deletedPoints);
            }

            timing.stage("attached_points");
            RoadPlanningBudget.phase("attached_points");
            this.capPlanning(var67, var68);
            if (deleting) {
               LaneTopology.reconcileDeletion(this, var67, var68);
               this.normalizeTransitions(var67, var68, player, true);
               LaneTopology.refreshDeletionPoints(this, var67, var68);
            } else {
               LaneTopology.reconcile(this, var67, var68);
            }

            this.enlistSupportDependencies(var67, var68);
            this.enlistCycleContinuations(var67, var68);
            timing.stage("topology");
            RoadPlanningBudget.phase("topology");
            var67.sort(Comparator.comparing(rx -> rx.record.id()));
            List<RoadIndex.Built> planning = this.capPlanning(var67, var68);
            List<RoadIndex.Built> terrainRoads = new ArrayList<>(var67);

            for (UUID idx : var68) {
               if (this.index.roads.containsKey(idx)) {
                  terrainRoads.add(this.index.roads.get(idx));
               }
            }

            boolean largeAssembly = terrainRoads.stream().anyMatch(rx -> {
               CompoundTag descriptor = rx.record.assembly() == null ? null : this.interchanges.get(rx.record.assembly());
               return descriptor != null && descriptor.getLongArray("Points").length >= 3;
            });
            if (largeAssembly) {
               workChunks.multiInterchange();
               editCellLimit = Math.max(editCellLimit, 8000000);
            }

            timing.stage("caps_and_dependencies");
            RoadPlanningBudget.phase("caps_and_dependencies");
            workChunks.roads(terrainRoads);
            timing.stage("terrain_chunk_access");
            RoadPlanningBudget.phase("terrain_chunk_access");
            Map<BlockPos, List<AABB>> terrainCache = new RoadTerrain.Cache(this.terrainFill, this.terrainOriginal);
            List<RoadIndex.Built> spacingReferences = new ArrayList<>(this.index.roads.values());

            for (int i = 0; i < var67.size(); i++) {
               RoadIndex.Built rx = (RoadIndex.Built)var67.get(i);
               RoadIndex.Built next = rx.phase(FurnitureSpacing.resolve(rx.record, rx.mesh, spacingReferences));
               var67.set(i, next);
               planning.set(planning.indexOf(rx), next);
               spacingReferences.removeIf(o -> o.record.id().equals(rx.record.id()));
               spacingReferences.add(next);
            }

            timing.stage("furniture_phase");
            RoadPlanningBudget.phase("furniture_phase");
            RoadPlanningIndex structureLookup = new RoadPlanningIndex(planning, var67.size());
            this.reserveBatchFixtures(level, var67, planning, this.terrainFill, this.terrainOriginal, terrainCache, structureLookup, explicit, var68);
            timing.stage("structure_candidate_index");
            RoadPlanningBudget.phase("structure_candidate_index");

            for (int i = 0; i < var67.size(); i++) {
               RoadIndex.Built rx = (RoadIndex.Built)var67.get(i);
               RoadRecord planned = StructurePlanner.plan(level, rx, planning, this.terrainFill, this.terrainOriginal, terrainCache, structureLookup);
               RoadIndex.Built next = rx.planned(planned);
               var67.set(i, next);
               int slot = planning.indexOf(rx);
               planning.set(slot, next);
               structureLookup.replace(slot, next);
            }

            timing.stage("structure_plan");
            RoadPlanningBudget.phase("structure_plan");
            LaneTopology.snapPlannedPoints(var67, planning);

            for (int pass = 0;
               !deleting
                  && (
                     LaneTopology.needsRefresh(this, planning, var67.stream().map(b -> b.record.id()).toList())
                        || LaneCrossSections.needsRestoreRefresh(planning, var67.stream().map(b -> b.record.id()).toList())
                  );
               pass++
            ) {
               if (pass >= 3) {
                  for (RoadIndex.Built pending : planning) {
                     if (var67.stream().anyMatch(v -> v.record.id().equals(pending.record.id()))) {
                        for (LanePoints.Point point : LaneTopology.metadata(pending.record).points()) {
                           LanePoints.Lane resolved = LanePoints.lane(pending.mesh, point);
                           double drift = resolved.position().distance(point.position());
                           if (drift > 1.0E-5) {
                              System.getLogger("SplineRoads/topology")
                                 .log(
                                    Level.WARNING,
                                    "Unstable lane {0} road={1} delta={2} station={3} point={4} anchor={5} resolved={6}",
                                    point.lane(),
                                    pending.record.id(),
                                    drift,
                                    resolved.station(),
                                    point.position(),
                                    point.anchor(),
                                    resolved.position()
                                 );
                           }
                        }
                     }
                  }

                  throw new IllegalArgumentException("断面与车道点未能稳定，请调整道路样式后重试");
               }

               LaneTopology.reconcile(this, var67, var68);
               this.enlistSupportDependencies(var67, var68);
               workChunks.roads(var67);
               planning = this.capPlanning(var67, var68);
               structureLookup = new RoadPlanningIndex(planning, var67.size());
               this.reserveBatchFixtures(level, var67, planning, this.terrainFill, this.terrainOriginal, terrainCache, structureLookup, explicit, var68);

               for (int i = 0; i < var67.size(); i++) {
                  RoadIndex.Built rx = (RoadIndex.Built)var67.get(i);
                  RoadIndex.Built next = rx.planned(
                     StructurePlanner.plan(level, rx, planning, this.terrainFill, this.terrainOriginal, terrainCache, structureLookup)
                  );
                  var67.set(i, next);
                  int slot = planning.indexOf(rx);
                  planning.set(slot, next);
                  structureLookup.replace(slot, next);
               }

               LaneTopology.snapPlannedPoints(var67, planning);
            }

            timing.stage("dependent_replanning");
            RoadPlanningBudget.phase("dependent_replanning");
            if (var67.stream().anyMatch(rx -> RoadStreetscape.hasPlanting(rx.mesh))) {
               RoadSolidOverlap.Index supports = new RoadSolidOverlap.Index(
                  planning.stream()
                     .flatMap(rx -> rx.record.structures().stream())
                     .filter(p -> p.material() == RoadStructures.Material.CONCRETE && p.height() > 0.5)
                     .toList()
               );

               for (int i = 0; i < var67.size(); i++) {
                  RoadIndex.Built rx = (RoadIndex.Built)var67.get(i);
                  List<RoadStructures.Part> parts = RoadStreetscape.clearBlockedPlanting(
                     rx.record.structures(),
                     rx.mesh,
                     rx.record.furniturePhase() == null ? RoadFurniture.Phase.DEFAULT : rx.record.furniturePhase(),
                     supports::intersects
                  );
                  if (parts != rx.record.structures()) {
                     var67.set(i, rx.structures(parts));
                  }
               }
            }

            Map<Integer, List<RoadStructures.Part>> noseCaps = RoadNoses.connectors(
               var67.stream().map(rx -> rx.mesh).toList(), var67.stream().map(rx -> rx.record.structures()).toList()
            );

            for (Entry<Integer, List<RoadStructures.Part>> entry : noseCaps.entrySet()) {
               RoadIndex.Built oldxxx = (RoadIndex.Built)var67.get(entry.getKey());
               ArrayList<RoadStructures.Part> parts = new ArrayList<>(oldxxx.record.structures());
               parts.addAll(entry.getValue());
               var67.set(entry.getKey(), oldxxx.structures(parts));
            }

            LampSpacing.reconcile(this, var67, var68);
            RailbedRoadEdits.enlist(this, level, var67, var68, requested);
            workChunks.roads(var67);
            ArrayList<RoadIndex.Built> shellFinal = new ArrayList<>();

            for (RoadIndex.Built oldxxx : this.index.roads.values()) {
               if (!var68.contains(oldxxx.record.id())) {
                  shellFinal.add(oldxxx);
               }
            }

            shellFinal.addAll(var67);
            HashSet<UUID> shellChanged = new HashSet<>();

            for (RoadIndex.Built changed : var67) {
               shellChanged.add(changed.record.id());
            }

            TunnelShellValidation.check(shellFinal, shellChanged, this.index.roads);
            RoadTravelValidation.check(shellFinal, shellChanged, this.index.roads);
            if (!deleting) {
               for (RoadIndex.Built road : var67) {
                  RoadExternalClearance.validate(road.record);
               }
            }

            if (!deleting) {
               TollRoads.validateConnections(shellFinal);
               this.checkJoints(var67, var68);
            }

            timing.stage("caps_shell_and_joint_validation");
            RoadPlanningBudget.phase("caps_shell_and_joint_validation");
            Set<Long> touched = new LongOpenHashSet();
            int scanLimit = editCellLimit == 8000000 ? 24000000 : editCellLimit;

            for (UUID idxx : var68) {
               RoadIndex.Built oldxxxx = this.index.roads.get(idxx);
               if (oldxxxx != null) {
                  if (oldxxxx.record.legacyRailway()) {
                     touched.addAll(RailbedRoadEdits.cells(oldxxxx.record));
                  } else {
                     touched.addAll(oldxxxx.cells.keySet());
                     touched.addAll(oldxxxx.clearanceCells);
                  }
               }
            }

            for (RoadIndex.Built rx : var67) {
               if (rx.record.legacyRailway()) {
                  touched.addAll(RailbedRoadEdits.cells(rx.record));
               } else {
                  touched.addAll(rx.cells.keySet());
                  touched.addAll(rx.clearanceCells);
               }
            }

            Set<Long> moveSources = new HashSet<>();
            Set<Long> moveTargets = new HashSet<>();

            for (RoadData.NodeMove move : moves) {
               if (move.from() != null) {
                  moveSources.add(move.from().asLong());
               }

               if (move.from() != null) {
                  touched.add(move.from().asLong());
               }

               if (move.to() != null) {
                  if (!moveTargets.add(move.to().asLong())) {
                     throw new IllegalArgumentException("端点目标重叠");
                  }

                  touched.add(move.to().asLong());
               }
            }

            if (touched.size() > scanLimit) {
               throw new IllegalArgumentException("本次需检查 " + touched.size() + " 格，超过单次范围上限 " + scanLimit + "，请分段建造");
            }

            List<RoadIndex.Built> finalRoads = new ArrayList<>();

            for (RoadIndex.Built rxx : this.index.roads.values()) {
               if (!var68.contains(rxx.record.id())) {
                  finalRoads.add(rxx);
               }
            }

            finalRoads.addAll(var67);
            timing.stage("edit_raster");
            RoadPlanningBudget.phase("edit_raster");
            Map<Long, BlockState> sidewalks = SmartSidewalks.plan(
               finalRoads, level, this.terrainFill, this.sidewalkPlaced, this.apronPlaced, var67, terrainRoads
            );
            Set<Long> aprons = SmartRoadAprons.plan(sidewalks, finalRoads, level, this.terrainOriginal, this.sidewalkPlaced, this.apronPlaced, terrainRoads);
            ObjectIterator body = this.sidewalkPlaced.entrySet().iterator();

            while (body.hasNext()) {
               Entry<Long, BlockState> e = (Entry<Long, BlockState>)body.next();
               if (!e.getValue().equals(sidewalks.get(e.getKey()))) {
                  touched.add(e.getKey());
               }
            }

            for (Entry<Long, BlockState> e : sidewalks.entrySet()) {
               if (!e.getValue().equals(this.sidewalkPlaced.get(e.getKey()))) {
                  touched.add(e.getKey());
               }
            }

            if (touched.size() > scanLimit) {
               throw new IllegalArgumentException("含路侧铺装需检查 " + touched.size() + " 格，超过单次范围上限 " + scanLimit + "，请分段建造");
            }

            timing.stage("sidewalks");
            RoadPlanningBudget.phase("sidewalks");
            Map<Long, List<RoadIndex.Built>> bodyx = new HashMap<>();
            Set<Long> air = new LongOpenHashSet();
            Set<Long> dry = new LongOpenHashSet();
            Set<Long> touchedChunks = new HashSet<>();

            for (long key : touched) {
               touchedChunks.add(new ChunkPos(BlockPos.of(key)).toLong());
            }

            workChunks.load(touchedChunks);
            Map<Long, List<BlockPos>> touchedByChunk = new HashMap<>();
            Map<Long, List<RoadIndex.Built>> finalByChunk = new HashMap<>();

            for (long key : touched) {
               BlockPos pos = BlockPos.of(key);
               touchedByChunk.computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), k -> new ArrayList<>()).add(pos);
            }

            for (RoadIndex.Built rxxx : finalRoads) {
               if (!Collections.disjoint(rxxx.chunks, touchedChunks)) {
                  for (long chunk : rxxx.chunks) {
                     if (touchedChunks.contains(chunk)) {
                        finalByChunk.computeIfAbsent(chunk, k -> new ArrayList<>()).add(rxxx);
                     }
                  }

                  if (rxxx.rasterized()) {
                     for (long key : rxxx.cells.keySet()) {
                        if (touched.contains(key)) {
                           bodyx.computeIfAbsent(key, k -> new ArrayList<>()).add(rxxx);
                        }
                     }

                     for (long keyx : rxxx.clearanceCells) {
                        if (touched.contains(keyx)) {
                           air.add(keyx);
                           if (rxxx.tunnelAt(BlockPos.of(keyx))) {
                              dry.add(keyx);
                           }
                        }
                     }
                  } else {
                     for (long chunkx : rxxx.chunks) {
                        for (BlockPos pos : touchedByChunk.getOrDefault(chunkx, List.of())) {
                           long keyxx = pos.asLong();
                           if (!rxxx.boxes(pos).isEmpty()) {
                              bodyx.computeIfAbsent(keyxx, k -> new ArrayList<>()).add(rxxx);
                           }

                           if (rxxx.clearanceAt(pos)) {
                              air.add(keyxx);
                              if (rxxx.tunnelAt(BlockPos.of(keyxx))) {
                                 dry.add(keyxx);
                              }
                           }
                        }
                     }
                  }
               }
            }

            timing.stage("local_collision");
            RoadPlanningBudget.phase("local_collision");
            Set<BlockPos> endpoints = new HashSet<>(selectedNodes);

            for (long source : moveSources) {
               endpoints.add(BlockPos.of(source));
            }

            for (RoadIndex.Built rxxxx : var67) {
               endpoints.add(rxxxx.record.a());
               endpoints.add(rxxxx.record.b());
            }

            if (assembly) {
               for (UUID idxxx : var68) {
                  RoadIndex.Built oldxxxx = this.index.roads.get(idxxx);
                  if (oldxxxx != null) {
                     endpoints.add(oldxxxx.record.a());
                     endpoints.add(oldxxxx.record.b());
                  }
               }
            }

            for (CompoundTag center : this.junctions.values()) {
               if (center.getBoolean("GeneratedRing") && var67.stream().anyMatch(rxxxx -> center.getUUID("Id").equals(rxxxx.record.assembly()))) {
                  for (long port : center.getLongArray("Ports")) {
                     endpoints.add(BlockPos.of(port));
                  }
               }
            }

            RoadData.PreparedEdit prepared = new RoadData.PreparedEdit(
               List.copyOf(var67),
               Set.copyOf(var68),
               deleting,
               touched,
               moveSources,
               moveTargets,
               sidewalks,
               aprons,
               bodyx,
               air,
               dry,
               finalByChunk,
               Set.copyOf(endpoints),
               List.copyOf(finalRoads),
               List.copyOf(moves),
               editCellLimit,
               RoadWorkChunks.held(level)
            );
            if (previewResult == null || validatePreview) {
               this.validateAndCommit(level, player, prepared, previewResult != null);
            }

            if (previewResult != null) {
               previewResult.add(prepared);
            }

            timing.stage("validation_commit_sync");
         }
      } else {
         throw new IllegalArgumentException("铁路路基请使用铁路路基连接器编辑或删除");
      }
   }

   private void validateAndCommit(ServerLevel level, ServerPlayer player, RoadData.PreparedEdit edit, boolean preview) {
      List<RoadIndex.Built> built = edit.built();
      List<RoadIndex.Built> committed = built;
      Set<UUID> removed = edit.removed();
      Set<UUID> removedIds = removed;
      List<RoadIndex.Built> removedBuilt = new ArrayList<>();

      if (!preview) {
         for (UUID id : removed) {
            RoadIndex.Built old = this.index.roads.get(id);
            if (old != null) {
               removedBuilt.add(old);
            }
         }
      }
      boolean deleting = edit.deleting();
      Set<Long> touched = edit.touched();
      Set<Long> moveSources = edit.moveSources();
      Set<Long> moveTargets = edit.moveTargets();
      Map<Long, BlockState> sidewalks = edit.sidewalks();
      Map<Long, List<RoadIndex.Built>> body = edit.body();
      Set<Long> air = edit.air();
      Set<Long> dry = edit.dry();
      Map<Long, List<RoadIndex.Built>> finalByChunk = edit.finalByChunk();
      Set<BlockPos> endpoints = edit.endpoints();
      List<RoadIndex.Built> finalRoads = edit.finalRoads();
      List<RoadData.NodeMove> moves = edit.moves();
      int editCellLimit = edit.editCellLimit();
      Map<Long, BlockState> writes = new HashMap<>();

      for (long key : touched) {
         RoadPlanningBudget.check();
         BlockPos p = BlockPos.of(key);
         if (level.isOutsideBuildHeight(p) || !level.getWorldBorder().isWithinBounds(p)) {
            throw new IllegalArgumentException("道路超出世界高度或边界");
         }

         if (!level.hasChunkAt(p)) {
            throw new IllegalArgumentException("道路区块加载失败，请稍后重试");
         }

         if (player != null && !level.mayInteract(player, p)) {
            throw new IllegalArgumentException("道路经过受保护区域");
         }

         BlockState state = RoadPlanInputs.state(level, p);
         boolean roadBody = body.containsKey(key);
         boolean headroom = air.contains(key);
         boolean walkway = sidewalks.containsKey(key);
         if (roadBody || headroom || walkway) {
            List<RoadIndex.Built> localRoads = finalByChunk.getOrDefault(new ChunkPos(p).toLong(), List.of());
            boolean ordinaryBody = body.getOrDefault(key, List.of()).stream().anyMatch(rx -> !rx.record.legacyRailway());
            boolean ordinaryAir = localRoads.stream().anyMatch(rx -> !rx.record.legacyRailway() && rx.clearanceAt(p));
            boolean railBody = body.getOrDefault(key, List.of()).stream().anyMatch(rx -> rx.record.legacyRailway());
            if (!deleting) {
               if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("mtr")) {
                  boolean selectedNode = MtrRailAccess.trainNode(state.getBlock())
                     && localRoads.stream()
                        .anyMatch(
                           rx -> rx.record
                                 .settings()
                                 .options()
                                 .railbed()
                                 .paths()
                                 .stream()
                                 .flatMap(path -> path.segments().stream())
                                 .anyMatch(segment -> RailbedCodec.pos(segment.from()).equals(p) || RailbedCodec.pos(segment.to()).equals(p))
                        );
                  boolean motorConflict = localRoads.stream().anyMatch(rx -> !rx.record.railway() && (!rx.boxes(p).isEmpty() || rx.clearanceAt(p)));
                  boolean fixture = localRoads.stream()
                     .filter(rx -> rx.record.railway())
                     .anyMatch(rx -> rx.prepareCollision().structureAt(new RoadRaster.Cell(p.getX(), p.getY(), p.getZ()), part -> true));
                  boolean levelCrossing = localRoads.stream()
                     .filter(rx -> rx.record.railway())
                     .anyMatch(
                        rx -> localRoads.stream()
                              .anyMatch(
                                 other -> RailwayCrossings.at(
                                       rx.mesh, other.mesh, new RoadGeometry.V((double)p.getX() + 0.5, (double)p.getY(), (double)p.getZ() + 0.5), 0.5
                                    )
                              )
                     );
                  if (selectedNode && (!motorConflict || levelCrossing) && !fixture) {
                     continue;
                  }

                  throw new IllegalArgumentException("道路施工碰到 MTR 设施：" + p.toShortString());
               }

               for (RoadIndex.Built candidate : localRoads) {
                  if (candidate.record.legacyRailway()
                     && ((ordinaryBody || ordinaryAir || walkway) && !candidate.boxes(p).isEmpty() || (ordinaryBody || walkway) && candidate.clearanceAt(p))) {
                     throw new IllegalArgumentException("道路施工侵入铁路路基本体或列车通行空间：" + p.toShortString());
                  }
               }
            }

            if (ordinaryBody || ordinaryAir || walkway || !localRoads.stream().anyMatch(rx -> rx.record.legacyRailway() && rx.clearanceAt(p)) || railBody) {
               if (state.is((Block)SplineRoads.NODE.get())) {
                  if (!endpoints.contains(p)
                     && !deleting
                     && (walkway || committed.stream().anyMatch(rx -> rx.cells.containsKey(key) || rx.clearanceCells.contains(key)))) {
                     throw new IllegalArgumentException("道路范围内有其他端点：" + p.toShortString());
                  }
               } else {
                  if (!deleting
                     && headroom
                     && !roadBody
                     && RoadBlocks.isCollider(state)
                     && this.index.at(key).stream().anyMatch(id -> !removedIds.contains(id))) {
                     throw new IllegalArgumentException("通行空间与另一条道路相交");
                  }

                  if ((!deleting || roadBody || walkway)
                     && (!deleting || RoadBlocks.isCollider(state) || !state.hasBlockEntity() && !(state.getDestroySpeed(level, p) < 0.0F))) {
                     boolean railEdge = !roadBody
                        && !walkway
                        && headroom
                        && !dry.contains(key)
                        && RailbedTerrainEdge.present(p, this.retainedSource(key, state, null), localRoads);
                     BlockState target = railEdge
                        ? ((Block)SplineRoads.FILLED_COLLIDER.get()).defaultBlockState()
                        : (
                           railBody && !ordinaryBody && !walkway
                              ? (RoadBlocks.isCollider(state) ? state : ((Block)SplineRoads.COLLIDER.get()).defaultBlockState())
                              : (
                                 roadBody
                                    ? this.collisionState(
                                       key,
                                       state,
                                       body.get(key),
                                       sidewalks.get(key),
                                       dry.contains(key),
                                       finalByChunk.getOrDefault(new ChunkPos(p).toLong(), List.of())
                                    )
                                    : (
                                       walkway
                                          ? sidewalks.get(key)
                                          : (dry.contains(key) ? ((Block)SplineRoads.TUNNEL_AIR.get()).defaultBlockState() : Blocks.AIR.defaultBlockState())
                                    )
                              )
                        );
                     if (!state.equals(target)
                        || target.is((Block)SplineRoads.FILLED_COLLIDER.get())
                           && level.getBlockEntity(p) instanceof RoadFillEntity fill
                           && (
                              !fill.fill().equals(this.retainedSource(key, state, sidewalks.get(key)))
                                 || !fill.edge().equals(RailbedTerrainEdge.boxes(p, this.retainedSource(key, state, sidewalks.get(key)), localRoads))
                                 || Math.abs(fill.cap() - GroundTerrainJoin.cap(p, this.retainedSource(key, state, sidewalks.get(key)), localRoads)) > 1.0E-8
                           )) {
                        RoadClearing.validate(level, p, state, state.equals(this.sidewalkPlaced.get(key)));
                        writes.put(key, target);
                     }
                  }
               }
            }
         }
      }

      for (RoadData.NodeMove move : moves) {
         if (move.from() != null) {
            requireNode(level, move.from(), player);
         }

         if (move.to() == null) {
            if (move.from() == null || finalRoads.stream().anyMatch(rx -> rx.record.a().equals(move.from()) || rx.record.b().equals(move.from()))) {
               throw new IllegalArgumentException("仍在使用的端点不能清理");
            }

            long key = move.from().asLong();
            writes.put(
               key,
               body.containsKey(key)
                  ? this.collisionState(
                     key,
                     Blocks.AIR.defaultBlockState(),
                     body.get(key),
                     sidewalks.get(key),
                     dry.contains(key),
                     finalByChunk.getOrDefault(new ChunkPos(move.from()).toLong(), List.of())
                  )
                  : (dry.contains(key) ? ((Block)SplineRoads.TUNNEL_AIR.get()).defaultBlockState() : Blocks.AIR.defaultBlockState())
            );
         } else {
            BlockState state = level.getBlockState(move.to());
            boolean movableMarker = moveSources.contains(move.to().asLong());
            boolean replacedRoad = RoadBlocks.isCollider(state) && this.index.at(move.to().asLong()).stream().allMatch(removedIds::contains);
            long targetKey = move.to().asLong();
            boolean clearedByConstruction = writes.containsKey(targetKey)
               && !RoadBlocks.isCollider(state)
               && !state.hasBlockEntity()
               && level.getBlockEntity(move.to()) == null
               && committed.stream().anyMatch(rx -> rx.cells.containsKey(targetKey) || rx.clearanceCells.contains(targetKey));
            if (!movableMarker
               && !replacedRoad
               && !clearedByConstruction
               && (!state.canBeReplaced() || state.hasBlockEntity() || level.getBlockEntity(move.to()) != null)) {
               throw new IllegalArgumentException(
                  "端点目标被占用："
                     + move.to().toShortString()
                     + "（"
                     + state.getBlock().getName().getString()
                     + " / "
                     + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                     + "）"
               );
            }

            long source = move.from() == null ? 0L : move.from().asLong();
            if (move.from() != null && !moveTargets.contains(source)) {
               writes.put(
                  source,
                  body.containsKey(source)
                     ? this.collisionState(
                        source,
                        Blocks.AIR.defaultBlockState(),
                        body.get(source),
                        sidewalks.get(source),
                        dry.contains(source),
                        finalByChunk.getOrDefault(new ChunkPos(BlockPos.of(source)).toLong(), List.of())
                     )
                     : (dry.contains(source) ? ((Block)SplineRoads.TUNNEL_AIR.get()).defaultBlockState() : Blocks.AIR.defaultBlockState())
               );
            }

            writes.put(move.to().asLong(), ((Block)SplineRoads.NODE.get()).defaultBlockState());
         }
      }

      // Construction should not leave old terrain/vegetation sticking through the new
      // road surface. The road body itself is replaced above; clear the vertical clearance
      // cells that are not part of any final road, sidewalk or moved node.
      for (Entry<Long, List<RoadIndex.Built>> entry : body.entrySet()) {
         BlockPos base = BlockPos.of(entry.getKey());
         if (entry.getValue().stream().anyMatch(road -> road.hasTunnel() || road.record.settings().structure() == RoadGeometry.Structure.TUNNEL)) {
            continue;
         }

         int clearance = entry.getValue()
            .stream()
            .mapToInt(road -> Math.max(1, Math.min(8, road.record.clearance())))
            .max()
            .orElse(1);

         for (int dy = 1; dy <= clearance; dy++) {
            BlockPos above = base.above(dy);
            long key = above.asLong();
            if (body.containsKey(key)
               || air.contains(key)
               || sidewalks.containsKey(key)
               || moveTargets.contains(key)
               || !level.hasChunkAt(above)) {
               continue;
            }

            BlockState current = level.getBlockState(above);
            if (writes.containsKey(key)) {
               // A road/sidewalk/air target is already planned for this cell; the
               // clearance pass must never overwrite it (that is what erased the
               // road deck and terrain in wide-road builds).
               continue;
            }

            if (current.is((Block)SplineRoads.MARKER.get())) {
               // Orphan map/LOD marker: clear it together with the construction.
               writes.put(key, Blocks.AIR.defaultBlockState());
               continue;
            }

            // Only remove replaceable vegetation/snow and trees here. Solid terrain
            // (grass blocks, dirt, stone, bedrock) must never be cleared by this pass;
            // the old "clear everything above the body" behaviour could remove the
            // whole surface layer or even bedrock on wide roads.
            if (!current.canBeReplaced() && !current.is(BlockTags.LEAVES) && !current.is(BlockTags.LOGS)) {
               continue;
            }

            if (current.isAir()
               || RoadBlocks.isCollider(current)
               || current.is((Block)SplineRoads.TUNNEL_AIR.get())
               || current.is((Block)SplineRoads.NODE.get())
               || current.hasBlockEntity()
               || !current.getFluidState().isEmpty()
               || current.getDestroySpeed(level, above) < 0.0F) {
               continue;
            }

            writes.put(key, Blocks.AIR.defaultBlockState());
         }
      }

      // Roads over void/air (bridges, world edges, holes in the platform) need a real
      // collision deck even when the terrain column is empty. The normal body map is
      // terrain-driven and can skip those cells, which left the visible VBO road with
      // no blocks to stand on. Backfill every raster deck cell that has no planned
      // write and no solid ground under/at it.
      if (!deleting) {
         for (RoadIndex.Built road : built) {
            for (long chunkKey : road.chunks) {
               int cx = (int)chunkKey;
               int cz = (int)(chunkKey >> 32);

               for (int x = cx * 16; x < cx * 16 + 16; x++) {
                  for (int z = cz * 16; z < cz * 16 + 16; z++) {
                     for (Entry<RoadRaster.Cell, List<RoadRaster.Box>> deck : road.prepareCollision().deckColumn(x, z).entrySet()) {
                        RoadRaster.Cell cell = deck.getKey();
                        long cellKey = BlockPos.asLong(cell.x(), cell.y(), cell.z());
                        if (writes.containsKey(cellKey)
                           || body.containsKey(cellKey)
                           || air.contains(cellKey)
                           || sidewalks.containsKey(cellKey)
                           || moveTargets.contains(cellKey)) {
                           continue;
                        }

                        BlockPos cellPos = new BlockPos(cell.x(), cell.y(), cell.z());
                        if (!level.hasChunkAt(cellPos)) {
                           continue;
                        }

                        BlockState current = level.getBlockState(cellPos);
                        if (RoadBlocks.isCollider(current)) {
                           continue;
                        }

                        boolean voidLike = current.isAir() || current.canBeReplaced() || !current.getFluidState().isEmpty();
                        if (!voidLike) {
                           continue;
                        }

                        BlockState target = this.collisionState(
                           cellKey, current, List.of(road), sidewalks.get(cellKey), false, List.of(road)
                        );
                        if (RoadBlocks.isCollider(target)) {
                           writes.put(cellKey, target);
                        }
                     }
                  }
               }
            }
         }
      }

      long changed = (long)writes.size();
      HashSet<Long> streetRestores = StreetEditScope.active() ? new HashSet<>() : null;

      for (long key : touched) {
         if (!body.containsKey(key) && (deleting || !air.contains(key)) && !sidewalks.containsKey(key) && !moveTargets.contains(key)) {
            BlockState current = level.getBlockState(BlockPos.of(key));
            if (RoadBlocks.isCollider(current) || current.is((Block)SplineRoads.TUNNEL_AIR.get()) || current.equals(this.sidewalkPlaced.get(key))) {
               changed++;
            }

            if (streetRestores != null
               && (
                  RoadBlocks.isCollider(current)
                     || TunnelAir.isAirSpace(current)
                     || current.equals(this.sidewalkPlaced.get(key))
                     || this.terrainOriginal.containsKey(key)
                     || this.terrainFill.containsKey(key)
               )) {
               streetRestores.add(key);
            }
         }
      }

      if (changed > (long)editCellLimit) {
         throw new IllegalArgumentException("实际需修改 " + changed + " 个方块，超过本次上限 " + editCellLimit);
      } else {
         StreetEditScope.prepare(level, built, removed, touched, writes, sidewalks, edit.aprons(), streetRestores, preview);
         if (!preview) {
            ManualPlannerHistory.capture(level, touched);
            if (player != null) {
               for (Entry<Long, BlockState> write : writes.entrySet()) {
                  BlockPos px = BlockPos.of(write.getKey());
                  RoadEditProtection.check(level, player, px, level.getBlockState(px), write.getValue());
               }

               for (long keyx : touched) {
                  if (!body.containsKey(keyx) && (deleting || !air.contains(keyx)) && !sidewalks.containsKey(keyx) && !moveTargets.contains(keyx)) {
                     BlockPos px = BlockPos.of(keyx);
                     BlockState currentx = level.getBlockState(px);
                     if (RoadBlocks.isCollider(currentx) || TunnelAir.isAirSpace(currentx) || currentx.equals(this.sidewalkPlaced.get(keyx))) {
                        RoadEditProtection.check(
                           level,
                           player,
                           px,
                           currentx,
                           (BlockState)this.terrainOriginal.getOrDefault(keyx, (BlockState)this.terrainFill.getOrDefault(keyx, Blocks.AIR.defaultBlockState()))
                        );
                     }
                  }
               }
            }

            RoadPlanningBudget.check();
            Set<UUID> groups = this.exactSplit ? Set.of() : this.junctionGroups(edit);
            RoadData.EditSnapshot snapshot = this.exactSplit ? null : new RoadData.EditSnapshot(level, edit, false);
            Consumer<UUID> callback = this.index.changed;
            if (snapshot != null) {
               this.index.changed = id -> {
               };
            }

            try {
               RoadPlanningBudget.committing();

               for (long keyxx : sidewalks.keySet()) {
                  if (!this.sidewalkPlaced.containsKey(keyxx) && !this.terrainOriginal.containsKey(keyxx)) {
                     BlockState statex = level.getBlockState(BlockPos.of(keyxx));
                     if (!RoadBlocks.isCollider(statex)) {
                        this.terrainOriginal.put(keyxx, statex);
                     }
                  }
               }

               for (RoadIndex.Built road : built) {
                  if (road.record.legacyRailway()) {
                     RailbedStore.Entry prior = this.railbeds.entries.get(road.record.id());

                     for (long keyxxx : RailbedRoadEdits.cells(prior.road)) {
                        if (prior.original.containsKey(keyxxx)) {
                           this.terrainOriginal.putIfAbsent(keyxxx, (BlockState)prior.original.get(keyxxx));
                        }
                     }
                  }
               }

               for (UUID id : removed) {
                  this.index.remove(id);
               }

               if (!this.exactSplit) {
                  editFault.accept("remove");
               }

               for (RoadIndex.Built r : built) {
                  this.index.put(r);
                  if (this.exactSplit) {
                     splitFault.accept("index");
                  } else {
                     editFault.accept("index");
                  }

                  RoadRecord logical = this.streets.get(r.record.id());
                  if (!this.exactSplit && logical != null && r.record.junction() == null) {
                     RoadGeometry.Settings settings = r.record.settings();
                     RoadTransitions.Ends ends = settings.options().ends();
                     RoadGeometry.Settings full = settings.options(
                        settings.options().ends(new RoadTransitions.Ends(ends.start(), ends.end(), ends.persistent()).paintPhase(ends.paintPhase()))
                     );
                     this.streets
                        .put(
                           logical.id(),
                           logical.fragment() != null
                              ? logical.settings(settings.options(settings.options().ends(logical.settings().options().ends()))).caps(r.record.endCaps())
                              : (
                                 ends.trimmedStart() == 0.0 && ends.trimmedEnd() == 0.0
                                    ? r.record.settings(full)
                                    : logical.settings(full).caps(r.record.endCaps())
                              )
                        );
                  }
               }

               for (Entry<Long, BlockState> entry : writes.entrySet()) {
                  long keyxxxx = entry.getKey();
                  BlockPos px = BlockPos.of(keyxxxx);
                  BlockState old = level.getBlockState(px);
                  BlockState target = entry.getValue();
                  if (!deleting
                     && target.isAir()
                     && !RoadBlocks.isCollider(old)
                     && !old.is((Block)SplineRoads.MARKER.get())
                     && old.getDestroySpeed(level, px) < 0.0F) {
                     // Unbreakable blocks (bedrock, barriers) must never be removed by
                     // construction, even if a stale clearance/restore entry asks for air.
                     continue;
                  }

                  if (!deleting
                     && target.isAir()
                     && !old.isAir()
                     && !old.canBeReplaced()
                     && !old.is(BlockTags.LEAVES)
                     && !old.is(BlockTags.LOGS)
                     && !RoadBlocks.isCollider(old)
                     && !old.is((Block)SplineRoads.TUNNEL_AIR.get())
                     && !old.is((Block)SplineRoads.NODE.get())
                     && !old.is((Block)SplineRoads.MARKER.get())
                     && !old.equals(this.sidewalkPlaced.get(keyxxxx))) {
                     // Safety net: never punch out solid terrain/bedrock unless the
                     // cell is an explicitly planned clearance/body cell or an old road.
                     continue;
                  }

                  if (!this.terrainOriginal.containsKey(keyxxxx)
                     && !RoadBlocks.isCollider(old)
                     && !old.is((Block)SplineRoads.TUNNEL_AIR.get())
                     && !old.is((Block)SplineRoads.NODE.get())
                     && !old.equals(this.sidewalkPlaced.get(keyxxxx))) {
                     boolean structural = body.containsKey(keyxxxx) || sidewalks.containsKey(keyxxxx) || air.contains(keyxxxx);
                     // Record every non-air block we overwrite, plus air in planned road/
                     // sidewalk/clearance cells. Missing records used to make the restore
                     // path fall back to air and punch void holes under wide roads.
                     if (structural || !old.isAir()) {
                        this.terrainOriginal.put(keyxxxx, old);
                     }
                  }

                  BlockState fill = this.retainedSource(keyxxxx, old, sidewalks.get(keyxxxx));
                  boolean retained = RoadBlocks.isCollider(target)
                     && (target.is((Block)SplineRoads.FILLED_COLLIDER.get()) || target.getValue(RoadBlocks.Road.FILL) != RoadBlocks.Fill.NONE);
                  if (retained && !TunnelAir.isAirSpace(fill) && !RoadBlocks.isCollider(fill)) {
                     this.terrainFill.put(keyxxxx, fill);
                  } else {
                     this.terrainFill.remove(keyxxxx);
                  }

                  if (!this.exactSplit) {
                     editFault.accept("ledger");
                  }

                  checkedSetBlock(level, px, entry.getValue(), 2);
                  if (this.exactSplit) {
                     splitFault.accept("write");
                  } else {
                     editFault.accept("write");
                  }

                  if (level.getBlockEntity(px) instanceof RoadFillEntity filled) {
                     BlockState material = sidewalks.getOrDefault(keyxxxx, (BlockState)this.terrainFill.getOrDefault(keyxxxx, Blocks.AIR.defaultBlockState()));
                     filled.fill(material, GroundTerrainJoin.cap(px, material, finalByChunk.getOrDefault(new ChunkPos(px).toLong(), List.of())));
                  }
               }

               for (RoadData.NodeMove move : moves) {
                  if (move.to() != null) {
                     BlockEntity var99 = level.getBlockEntity(move.to());
                     if (!(var99 instanceof NodeEntity)) {
                        throw new IllegalStateException("道路端点实体创建失败：" + move.to().toShortString());
                     }

                     NodeEntity marker = (NodeEntity)var99;
                     marker.readSnapshot(move.snapshot().copy(), level.registryAccess());
                     marker.heightExplicit = true;
                     marker.apply(move.target());
                     if (!this.exactSplit) {
                        editFault.accept("node");
                     }
                  }
               }

               LinkedHashMap<Long, BlockState> restoredBlocks = new LinkedHashMap<>();

               for (long keyxxxxx : touched) {
                  RoadPlanningBudget.check();
                  if (!writes.containsKey(keyxxxxx) && !body.containsKey(keyxxxxx) && !air.contains(keyxxxxx) && !sidewalks.containsKey(keyxxxxx) && !moveTargets.contains(keyxxxxx)) {
                     boolean stillOwned = false;
                     BlockPos ownerPos = BlockPos.of(keyxxxxx);
                     long ownerChunk = ChunkPos.asLong(ownerPos.getX() >> 4, ownerPos.getZ() >> 4);

                     for (UUID ownerId : this.index.inChunk(ownerChunk)) {
                        RoadIndex.Built owner = this.index.roads.get(ownerId);
                        if (owner != null && owner.cells.containsKey(keyxxxxx)) {
                           stillOwned = true;
                           break;
                        }
                     }

                     if (stillOwned) {
                        // The cell still belongs to a road that remains after this edit.
                        // Restoring it here is what deleted the deck of an overlapping
                        // road when a new road was built next to/over it.
                        continue;
                     }

                     BlockPos pxx = BlockPos.of(keyxxxxx);
                     BlockState currentx = level.getBlockState(pxx);
                     BlockState fillx = (BlockState)this.terrainFill.remove(keyxxxxx);
                     BlockState original = (BlockState)this.terrainOriginal.remove(keyxxxxx);
                     if (RoadBlocks.isCollider(currentx)) {
                        boolean retainedx = currentx.is((Block)SplineRoads.FILLED_COLLIDER.get())
                           || currentx.getValue(RoadBlocks.Road.FILL) != RoadBlocks.Fill.NONE;
                         RoadBlocks.Fill currentFill = currentx.hasProperty(RoadBlocks.Road.FILL)
                            ? currentx.getValue(RoadBlocks.Road.FILL)
                            : RoadBlocks.Fill.NONE;
                         BlockState fillFallback = currentFill == RoadBlocks.Fill.NONE ? null : currentFill.state();

                         if (original != null && !original.isAir()) {
                            restoredBlocks.put(Long.valueOf(keyxxxxx), original);
                         } else if (fillx != null) {
                            restoredBlocks.put(Long.valueOf(keyxxxxx), fillx);
                         } else if (fillFallback != null) {
                            restoredBlocks.put(Long.valueOf(keyxxxxx), fillFallback);
                         } else if (original != null) {
                            restoredBlocks.put(Long.valueOf(keyxxxxx), original);
                         }
                     } else if (TunnelAir.isAirSpace(currentx) || currentx.equals(this.sidewalkPlaced.get(keyxxxxx))) {
                        if (original != null) {
                           restoredBlocks.put(Long.valueOf(keyxxxxx), original);
                        }
                     }
                  }
               }

               restoreBlocks(level, restoredBlocks, Map.of(), failure -> {
                  throw failure;
               }, true);
               RailbedRoadEdits.sync(this, level, built);
               RailbedRoads.sync(this, built, removed);
               this.sidewalkPlaced = new Long2ObjectOpenHashMap(sidewalks);
               this.apronPlaced = new LongOpenHashSet(edit.aprons());
               if (!this.exactSplit) {
                  RampJunctions.sync(this, groups);
               }

               for (RoadIndex.Built r : built) {
                  if (this.streets.containsKey(r.record.id())) {
                     RoadRecord logical = this.streets.get(r.record.id());
                     this.streets.put(logical.id(), logical.withLanePoints(ElevatedAccessRoads.logicalLanes(logical, r.record)));
                  }
               }

               this.setDirty();
               if (!this.exactSplit) {
                  editFault.accept("metadata");
               }
            } catch (RuntimeException var43) {
               if (snapshot != null) {
                  snapshot.restore(level, var43);
               }

               throw var43;
            } finally {
               if (snapshot != null) {
                  this.index.changed = callback;
               }
            }

            if (!this.exactSplit) {
               ElevatedAccessRoads.prune(this);
               LinkedHashSet<UUID> changedIds = new LinkedHashSet<>(removed);

               for (RoadIndex.Built rx : built) {
                  changedIds.add(rx.record.id());
               }

               changedIds.forEach(callback);

               for (UUID id : removed) {
                  if (committed.stream().noneMatch(b -> b.record.id().equals(id))) {
                     RoadNetwork.broadcastDelete(level, id);
                  }
               }

               for (RoadIndex.Built rx : built) {
                  RoadNetwork.broadcastRoad(level, rx.record);
               }
            }

            // Remove the marker blocks of deleted/edited roads, using the road records
            // themselves (deterministic layout) instead of a global chunk scan.
            if (!preview && !removedBuilt.isEmpty()) {
               RoadMarkerBuilder.queueRemoval(removedBuilt, finalRoads);
            }
         }
      }
   }

   BlockState railbedCollision(BlockPos p, BlockState original, List<RoadIndex.Built> nearby) {
      List<RoadIndex.Built> owners = nearby.stream().filter(r -> !r.boxes(p).isEmpty()).toList();
      return this.collisionState(p.asLong(), original, owners, null, nearby.stream().anyMatch(r -> r.tunnelAt(p) && r.clearanceAt(p)), nearby);
   }

   private BlockState collisionState(long key, BlockState previous, List<RoadIndex.Built> roads) {
      return this.collisionState(key, previous, roads, (BlockState)this.sidewalkPlaced.get(key));
   }

   private BlockState collisionState(long key, BlockState previous, List<RoadIndex.Built> roads, BlockState sidewalk) {
      BlockPos pos = BlockPos.of(key);
      List<RoadIndex.Built> nearby = this.index.inChunk(new ChunkPos(pos).toLong()).stream().map(this.index.roads::get).filter(Objects::nonNull).toList();
      boolean dryInterior = nearby.stream().anyMatch(r -> r.tunnelAt(pos) && r.clearanceAt(pos));
      return this.collisionState(key, previous, roads, sidewalk, dryInterior, nearby);
   }

   private BlockState collisionState(
      long key, BlockState previous, List<RoadIndex.Built> roads, BlockState sidewalk, boolean dryInterior, List<RoadIndex.Built> nearby
   ) {
      BlockPos p = BlockPos.of(key);
      BlockState terrain = sidewalk != null
         ? sidewalk
         : (
            this.sidewalkPlaced.containsKey(key)
               ? (BlockState)this.terrainOriginal.getOrDefault(key, Blocks.AIR.defaultBlockState())
               : (
                  !RoadBlocks.isCollider(previous) && !previous.equals(this.sidewalkPlaced.get(key))
                     ? previous
                     : (BlockState)this.terrainFill.getOrDefault(key, Blocks.AIR.defaultBlockState())
               )
         );
      if (TunnelAir.isAirSpace(terrain) && RoadBlocks.isCollider(previous)) {
         terrain = (BlockState)this.terrainOriginal.getOrDefault(key, terrain);
      }

      RoadBlocks.Fill fill = RoadBlocks.Fill.of(terrain);
      boolean generic = fill == RoadBlocks.Fill.NONE
         && !TunnelAir.isAirSpace(terrain)
         && (sidewalk != null || previous.is((Block)SplineRoads.FILLED_COLLIDER.get()));
      boolean deck = false;
      RoadBlocks.Paint paint = RoadBlocks.Paint.NONE;
      boolean gantryFill = previous.is((Block)SplineRoads.FILLED_COLLIDER.get())
         && roads.stream().anyMatch(r -> RoadBlocks.gantryCell(r, new RoadRaster.Cell(p.getX(), p.getY(), p.getZ())));

      for (RoadIndex.Built r : roads) {
         RoadIndex.Built.Column c = r.column(p);
         if (c != null) {
            deck = true;
            if ((double)p.getY() + fill.top() > c.minTop() + 1.0E-7) {
               fill = RoadBlocks.Fill.NONE;
            }

            // Do not keep the terrain material as the visible fill model on the top road
            // layer. Otherwise grass/dirt/stone textures stay rendered on/through the road
            // surface even though the collision block was replaced (snow worked only
            // because it is not part of the Fill enum).
            if ((double)(p.getY() + 1) >= c.maxTop() - 1.0E-7) {
               fill = RoadBlocks.Fill.NONE;
               paint = RoadPaint.at(r, p.getX(), p.getZ());
            }

            if (!gantryFill && (double)(p.getY() + 1) > c.minTop() + 1.0E-7) {
               generic = false;
            }
         }
      }

      if (!deck && fill != RoadBlocks.Fill.NONE) {
         double lowest = roads.stream().flatMap(rx -> rx.boxes(p).stream()).mapToDouble(RoadRaster.Box::y0).min().orElse(0.0);
         if (fill.top() > lowest + 1.0E-7) {
            fill = RoadBlocks.Fill.NONE;
         }
      }

      boolean exteriorShell = roads.stream().anyMatch(rx -> rx.shellAt(p));
      if (exteriorShell && TunnelTerrainSpace.safe(p, nearby)) {
         BlockState original = terrain;
         if (TunnelAir.isAirSpace(terrain) && RoadBlocks.isCollider(previous)) {
            original = (BlockState)this.terrainOriginal.getOrDefault(key, Blocks.AIR.defaultBlockState());
         }

         if (!original.hasBlockEntity() && original.getFluidState().isEmpty()) {
            fill = RoadBlocks.Fill.of(original);
            generic = fill == RoadBlocks.Fill.NONE && !TunnelAir.isAirSpace(original) && original.canOcclude();
         }
      }

      double walkTop = roads.stream().mapToDouble(rx -> rx.walkTopAt(p)).max().orElse(Double.NEGATIVE_INFINITY);
      if (!deck && Double.isFinite(walkTop) && !terrain.hasBlockEntity() && terrain.getFluidState().isEmpty() && !TunnelAir.isAirSpace(terrain)) {
         RoadBlocks.Fill retained = RoadBlocks.Fill.of(terrain);
         double top = retained == RoadBlocks.Fill.NONE ? 1.0 : retained.top();
         if ((double)p.getY() + top <= walkTop + 1.0E-6) {
            fill = retained;
            generic = retained == RoadBlocks.Fill.NONE && terrain.canOcclude();
         }
      }

      if (!deck
         && !TunnelAir.isAirSpace(terrain)
         && terrain.canOcclude()
         && !terrain.hasBlockEntity()
         && terrain.getFluidState().isEmpty()
         && TunnelTerrainSpace.exteriorInfill(p, nearby)) {
         fill = RoadBlocks.Fill.of(terrain);
         generic = fill == RoadBlocks.Fill.NONE;
      }

      double terrainCap = GroundTerrainJoin.cap(p, terrain, nearby);
      if (terrainCap < 1.0) {
         fill = RoadBlocks.Fill.NONE;
         generic = GroundTerrainJoin.usable(terrainCap);
      }

      boolean cycleBody = roads.stream().anyMatch(rx -> rx.cycleBodyAt(p));
      if (dryInterior || cycleBody) {
         fill = RoadBlocks.Fill.NONE;
         generic = false;
      }

      if (!dryInterior && !cycleBody && RailbedTerrainEdge.present(p, terrain, nearby)) {
         fill = RoadBlocks.Fill.NONE;
         generic = true;
      }

      boolean deckHere = roads.stream().anyMatch(rx -> {
         RoadIndex.Built.Column cx = rx.column(p);
         return cx != null && (double)(p.getY() + 1) > cx.minTop() - rx.record.settings().thickness() + 1.0E-7 && (double)p.getY() < cx.maxTop() - 1.0E-7;
      });
      boolean tunnelOwner = roads.stream().anyMatch(RoadIndex.Built::hasTunnel);
      boolean shell = roads.stream().anyMatch(rx -> rx.shellAt(p));
      boolean permeable = RoadWaterPolicy.permeable(deckHere, generic || fill != RoadBlocks.Fill.NONE, tunnelOwner, shell, dryInterior);
      boolean originalWater = ((BlockState)this.terrainOriginal.getOrDefault(key, Blocks.AIR.defaultBlockState())).getFluidState().is(FluidTags.WATER);
      boolean water = RoadWaterPolicy.waterlogged(
         permeable, terrain.getFluidState().is(FluidTags.WATER), previous.getFluidState().is(FluidTags.WATER), originalWater
      );
      return (BlockState)((BlockState)((BlockState)((BlockState)((BlockState)((BlockState)((Block)(generic ? SplineRoads.FILLED_COLLIDER : SplineRoads.COLLIDER).get())
                     .defaultBlockState()
                     .setValue(RoadBlocks.Road.FILL, fill))
                  .setValue(RoadBlocks.Road.PAINT, paint))
                  .setValue(RoadBlocks.Road.LIT, roads.stream().anyMatch(rx -> rx.lightAt(p))))
               .setValue(RoadBlocks.Road.PERMEABLE, permeable))
            .setValue(RoadBlocks.Road.WATERLOGGED, water))
         .setValue(RoadBlocks.Road.SEALED, shell);
   }

   private BlockState retainedSource(long key, BlockState previous, BlockState sidewalk) {
      if (sidewalk != null) {
         return sidewalk;
      } else if (this.sidewalkPlaced.containsKey(key)) {
         return (BlockState)this.terrainOriginal.getOrDefault(key, Blocks.AIR.defaultBlockState());
      } else {
         BlockState fill = (BlockState)this.terrainFill.getOrDefault(key, previous);
         return !RoadBlocks.isCollider(fill) && (!TunnelAir.isAirSpace(fill) || !RoadBlocks.isCollider(previous))
            ? fill
            : (BlockState)this.terrainOriginal.getOrDefault(key, Blocks.AIR.defaultBlockState());
      }
   }

   public boolean infill(ServerLevel level, BlockPos p, BlockState fill, ServerPlayer player) {
      if (!RoadBlocks.canInfill(level, p, fill)) {
         return false;
      } else if (!this.mayEditFill(level, p, player)) {
         return false;
      } else {
         BlockState old = level.getBlockState(p);
         this.terrainFill.put(p.asLong(), fill);
         this.terrainOriginal.put(p.asLong(), fill);
         level.setBlock(
            p,
            (BlockState)((BlockState)((Block)SplineRoads.FILLED_COLLIDER.get())
                  .defaultBlockState()
                  .setValue(RoadBlocks.Road.LIT, (Boolean)old.getValue(RoadBlocks.Road.LIT)))
               .setValue(RoadBlocks.Road.SEALED, (Boolean)old.getValue(RoadBlocks.Road.SEALED)),
            3
         );
         List<RoadIndex.Built> nearby = this.index.inChunk(new ChunkPos(p).toLong()).stream().map(this.index.roads::get).filter(Objects::nonNull).toList();
         ((RoadFillEntity)level.getBlockEntity(p)).fill(fill, GroundTerrainJoin.cap(p, fill, nearby));
         this.setDirty();
         return true;
      }
   }

   private boolean mayEditFill(ServerLevel level, BlockPos p, ServerPlayer player) {
      if (player == null) {
         return true;
      } else if (player.mayBuild() && level.mayInteract(player, p)) {
         if (player.hasPermissions(2)) {
            return true;
         } else {
            for (UUID id : this.index.at(p.asLong())) {
               RoadIndex.Built road = this.index.roads.get(id);
               if (road != null && road.record.owner() != null && !road.record.owner().equals(player.getUUID())) {
                  return false;
               }
            }

            return true;
         }
      } else {
         return false;
      }
   }

   public boolean excavateSupport(ServerLevel level, BlockPos p, ServerPlayer player) {
      BlockState current = level.getBlockState(p);
      if (RoadBlocks.isCollider(current)
         && (current.getValue(RoadBlocks.Road.FILL) != RoadBlocks.Fill.NONE || current.is((Block)SplineRoads.FILLED_COLLIDER.get()))) {
         if (!this.mayEditFill(level, p, player)) {
            return false;
         } else {
            BlockState terrain = (BlockState)this.terrainFill.getOrDefault(p.asLong(), ((RoadBlocks.Fill)current.getValue(RoadBlocks.Road.FILL)).state());
            if (player != null && !player.isCreative() && player.hasCorrectToolForDrops(terrain)) {
               Block.dropResources(terrain, level, p, null, player, player.getMainHandItem());
            }

            this.terrainFill.put(p.asLong(), Blocks.AIR.defaultBlockState());
            this.terrainOriginal.put(p.asLong(), Blocks.AIR.defaultBlockState());
            List<RoadIndex.Built> owners = this.index.at(p.asLong()).stream().map(this.index.roads::get).filter(Objects::nonNull).toList();
            BlockState empty = this.collisionState(p.asLong(), current, owners, null);
            level.setBlock(p, empty, 3);
            if ((Boolean)empty.getValue(RoadBlocks.Road.WATERLOGGED)) {
               level.scheduleTick(p, Fluids.WATER, Fluids.WATER.getTickDelay(level));
            }

            this.setDirty();
            return true;
         }
      } else {
         return false;
      }
   }

   void clearSignSightlines(ServerLevel level, Set<UUID> changed) {
      HashSet<UUID> ids = new HashSet<>();

      for (UUID id : changed) {
         RoadIndex.Built road = this.index.roads.get(id);
         if (road != null) {
            for (long key : road.chunks) {
               ChunkPos c = new ChunkPos(key);

               for (int x = c.x - 6; x <= c.x + 6; x++) {
                  for (int z = c.z - 6; z <= c.z + 6; z++) {
                     ids.addAll(this.index.inChunk(ChunkPos.asLong(x, z)));
                  }
               }
            }
         }
      }

      if (!ids.isEmpty()) {
         List<RoadIndex.Built> roads = ids.stream().map(this.index.roads::get).filter(Objects::nonNull).toList();
         Predicate<RoadGeometry.V> sight = JunctionSignRoads.lampClearance(roads);
         HashSet<Long> chunks = new HashSet<>();

         for (RoadIndex.Built built : roads) {
            List<RoadStructures.Part> removed = StreetLampClearance.removed(built.record.structures(), sight);
            if (!removed.isEmpty()) {
               Set<Long> changedChunks = this.removeSightlineLamps(level, built, removed);
               if (changedChunks != null) {
                  chunks.addAll(changedChunks);
               }
            }
         }

         for (long key : chunks) {
            ChunkPos chunk = new ChunkPos(key);
            if (level.hasChunk(chunk.x, chunk.z)) {
               this.repairChunk(level, chunk);
            }
         }
      }
   }

   Set<Long> removeSightlineLamps(ServerLevel level, RoadIndex.Built expected, List<RoadStructures.Part> removed) {
      if (this.index.roads.get(expected.record.id()) != expected) {
         return null;
      } else if (removed.isEmpty()) {
         return Set.of();
      } else {
         HashSet<RoadStructures.Part> removedSet = new HashSet<>(removed);
         RoadRecord next = expected.record.structures(expected.record.structures().stream().filter(p -> !removedSet.contains(p)).toList());
         this.index.put(RoadIndex.Built.loading(next));
         this.setDirty();
         RoadNetwork.broadcastRoad(level, next);
         HashSet<Long> chunks = new HashSet<>();

         for (RoadStructures.Part part : removed) {
            double pad = part.halfExtent();

            for (int x = (int)Math.floor(Math.min(part.a().x(), part.b().x()) - pad) >> 4;
               x <= (int)Math.floor(Math.max(part.a().x(), part.b().x()) + pad) >> 4;
               x++
            ) {
               for (int z = (int)Math.floor(Math.min(part.a().z(), part.b().z()) - pad) >> 4;
                  z <= (int)Math.floor(Math.max(part.a().z(), part.b().z()) + pad) >> 4;
                  z++
               ) {
                  long key = ChunkPos.asLong(x, z);
                  chunks.add(key);
                  this.retiredSignsByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(part);
               }
            }
         }

         return chunks;
      }
   }

   private void releaseRetiredCell(ServerLevel level, BlockPos pos, BlockState state) {
      long key = pos.asLong();
      BlockState walkway = (BlockState)this.sidewalkPlaced.get(key);
      if (walkway != null) {
         if (RoadBlocks.isCollider(state)) {
            checkedSetBlock(level, pos, walkway, 3);
         }

         this.terrainFill.remove(key);
      } else {
         if (RoadBlocks.isCollider(state)) {
            BlockState restored = (BlockState)this.terrainOriginal
               .getOrDefault(key, (BlockState)this.terrainFill.getOrDefault(key, ((RoadBlocks.Fill)state.getValue(RoadBlocks.Road.FILL)).state()));
            checkedSetBlock(level, pos, restored, 3);
         }

         this.terrainFill.remove(key);
         this.terrainOriginal.remove(key);
      }
   }

   public void repairChunk(ServerLevel level, ChunkPos chunk) {
      if (this.deckRepaired.add(chunk.toLong())) {
         if (this.deckRepaired.size() > 8192) {
            this.deckRepaired.clear();
            this.deckRepaired.add(chunk.toLong());
         }

         List<RoadIndex.Built> deckRoads = this.index.inChunk(chunk.toLong()).stream().map(this.index.roads::get).filter(Objects::nonNull).toList();
         boolean deckChanged = false;

         // Adopt marker blocks placed by older versions (read-only) so later road deletions
         // can remove exactly those blocks. Neighbouring chunks are included because trees
         // and lamps can sit slightly outside the road's own chunk.
         HashSet<UUID> adoptIds = new HashSet<>();

         for (int ax = chunk.x - 2; ax <= chunk.x + 2; ax++) {
            for (int az = chunk.z - 2; az <= chunk.z + 2; az++) {
               adoptIds.addAll(this.index.inChunk(ChunkPos.asLong(ax, az)));
            }
         }

         RoadMarkerBuilder.adoptChunk(
            level, chunk, adoptIds.stream().map(this.index.roads::get).filter(Objects::nonNull).toList()
         );

         for (RoadIndex.Built road : deckRoads) {
            for (int x = chunk.x * 16; x < chunk.x * 16 + 16; x++) {
               for (int z = chunk.z * 16; z < chunk.z * 16 + 16; z++) {
                  for (Entry<RoadRaster.Cell, List<RoadRaster.Box>> deck : road.prepareCollision().deckColumn(x, z).entrySet()) {
                     RoadRaster.Cell cell = deck.getKey();
                     BlockPos p = new BlockPos(cell.x(), cell.y(), cell.z());
                     long key = p.asLong();
                     BlockState current = level.getBlockState(p);
                     if (RoadBlocks.isCollider(current)) {
                        continue;
                     }

                     boolean voidLike = current.isAir() || current.canBeReplaced() || !current.getFluidState().isEmpty();
                     if (!voidLike) {
                        continue;
                     }

                     BlockState target = this.collisionState(key, current, List.of(road), null, false, List.of(road));
                     if (RoadBlocks.isCollider(target)) {
                        level.setBlock(p, target, 3);
                        deckChanged = true;
                     }
                  }
               }
            }
         }

         // Fallback deck generation directly from the stored mesh. This does not
         // depend on the raster/cell maps, so it can repair roads whose deck data
         // was lost (the exact cause of "structures collide, road surface does not").
         int meshPlaced = 0;

         for (RoadIndex.Built road : deckRoads) {
            double sidewalk = road.record.settings().options().sidewalk().width();

            for (RoadGeometry.Sample sample : road.mesh.samples()) {
               if (meshPlaced >= 2048) {
                  break;
               }

               int sx = (int)Math.floor(sample.center().x());
               int sz = (int)Math.floor(sample.center().z());
               if (sx >> 4 != chunk.x || sz >> 4 != chunk.z) {
                  continue;
               }

               int by = (int)Math.floor(sample.center().y() - 1.0E-6);
               double half = sample.halfWidth() + sidewalk;

               for (double off = -half; off <= half && meshPlaced < 2048; off += 1.0) {
                  RoadGeometry.V point = sample.center().add(sample.left().mul(off));
                  BlockPos p = new BlockPos((int)Math.floor(point.x()), by, (int)Math.floor(point.z()));
                  if (p.getX() >> 4 != chunk.x || p.getZ() >> 4 != chunk.z) {
                     continue;
                  }

                  BlockState current = level.getBlockState(p);
                  if (RoadBlocks.isCollider(current)) {
                     continue;
                  }

                  boolean voidLike = current.isAir() || current.canBeReplaced() || !current.getFluidState().isEmpty();
                  if (!voidLike) {
                     continue;
                  }

                  level.setBlock(p, ((Block)SplineRoads.COLLIDER.get()).defaultBlockState(), 3);
                  deckChanged = true;
                  meshPlaced++;
               }
            }
         }

         // Lane paint is part of the top collider state now: apply it to the deck of every
         // road in this chunk (and clear stale paint on cells that no longer carry markings).
         for (RoadIndex.Built road : deckRoads) {
            Map<Long, RoadBlocks.Paint> paint = RoadPaint.cells(road);

            for (int x = chunk.x * 16; x < chunk.x * 16 + 16; x++) {
               for (int z = chunk.z * 16; z < chunk.z * 16 + 16; z++) {
                  RoadBlocks.Paint want = paint.getOrDefault(RoadPaint.key(x, z), RoadBlocks.Paint.NONE);
                  int topY = Integer.MIN_VALUE;

                  for (RoadRaster.Cell cell : road.prepareCollision().deckColumn(x, z).keySet()) {
                     topY = Math.max(topY, cell.y());
                  }

                  if (topY == Integer.MIN_VALUE) {
                     continue;
                  }

                  BlockPos p = new BlockPos(x, topY, z);
                  BlockState state = level.getBlockState(p);
                  if (RoadBlocks.isCollider(state) && state.hasProperty(RoadBlocks.Road.PAINT) && state.getValue(RoadBlocks.Road.PAINT) != want) {
                     level.setBlock(p, state.setValue(RoadBlocks.Road.PAINT, want), 3);
                     deckChanged = true;
                  }
               }
            }
         }

         if (deckChanged) {
            this.setDirty();
         }
      }

      List<RoadStructures.Part> revised = this.structureRepair103.remove(chunk.toLong());
      if (revised != null) {
         List<RoadIndex.Built> nearby = this.index.inChunk(chunk.toLong()).stream().map(this.index.roads::get).filter(Objects::nonNull).toList();

         for (RoadRaster.Cell cell : RoadRaster.structures(revised, null).keySet()) {
            if (cell.x() >> 4 == chunk.x && cell.z() >> 4 == chunk.z) {
               BlockPos p = new BlockPos(cell.x(), cell.y(), cell.z());
               long key = p.asLong();
               BlockState state = level.getBlockState(p);
               List<RoadIndex.Built> owners = nearby.stream().filter(r -> !r.boxes(p).isEmpty()).toList();
               if (owners.isEmpty()) {
                  this.releaseRetiredCell(level, p, state);
               } else if (TunnelAir.isAirSpace(state) || RoadBlocks.isCollider(state)) {
                  level.setBlock(p, this.collisionState(key, state, owners), 3);
                  if (level.getBlockEntity(p) instanceof RoadFillEntity fill) {
                     fill.fill((BlockState)this.terrainFill.getOrDefault(key, Blocks.AIR.defaultBlockState()));
                  }
               }
            }
         }

         this.setDirty();
      }

      List<Long> apronCells = this.apronWidthRepair.remove(chunk.toLong());
      if (apronCells != null) {
         HashSet<UUID> ids = new HashSet<>();

         for (int x = chunk.x - 1; x <= chunk.x + 1; x++) {
            for (int z = chunk.z - 1; z <= chunk.z + 1; z++) {
               ids.addAll(this.index.inChunk(ChunkPos.asLong(x, z)));
            }
         }

         List<RoadIndex.Built> nearby = ids.stream().map(this.index.roads::get).filter(Objects::nonNull).toList();

         for (long key : apronCells) {
            if (this.apronPlaced.contains(key)) {
               BlockPos p = BlockPos.of(key);
               boolean keep = false;

               for (RoadIndex.Built road : nearby) {
                  if (RoadAprons.eligible(road.mesh)) {
                     RoadQueries.Projection q = RoadQueries.horizontal(
                        road.mesh, new RoadGeometry.V((double)p.getX() + 0.5, (double)p.getY(), (double)p.getZ() + 0.5)
                     );
                     if (Math.abs(q.lateral()) <= q.sample().halfWidth() + 1.0 + 1.0E-7 && Math.abs(q.sample().center().y() - (double)(p.getY() + 1)) < 2.01) {
                        keep = true;
                        break;
                     }
                  }
               }

               if (!keep) {
                  BlockState state;
                  boolean var10000;
                  label281: {
                     BlockState expected = (BlockState)this.sidewalkPlaced.get(key);
                     state = level.getBlockState(p);
                     BlockEntity fillEntity = level.getBlockEntity(p);
                     label231:
                     if (!state.equals(expected)) {
                        if (state.is((Block)SplineRoads.FILLED_COLLIDER.get()) && fillEntity instanceof RoadFillEntity f && f.fill().equals(expected)) {
                           break label231;
                        }

                        var10000 = false;
                        break label281;
                     }

                     var10000 = true;
                  }

                  boolean owned = var10000;
                  this.apronPlaced.remove(key);
                  this.sidewalkPlaced.remove(key);
                  List<RoadIndex.Built> owners = nearby.stream().filter(b -> !b.boxes(p).isEmpty()).toList();
                  if (!owned) {
                     if (owners.isEmpty()) {
                        this.terrainFill.remove(key);
                        this.terrainOriginal.remove(key);
                     }
                  } else {
                     BlockState original = (BlockState)this.terrainOriginal.getOrDefault(key, Blocks.AIR.defaultBlockState());
                     if (owners.isEmpty()) {
                        this.terrainFill.remove(key);
                        this.terrainOriginal.remove(key);
                        level.setBlock(p, original, 3);
                     } else {
                        this.terrainFill.put(key, original);
                        level.setBlock(p, this.collisionState(key, state, owners), 3);
                        if (level.getBlockEntity(p) instanceof RoadFillEntity f) {
                           f.fill(original, GroundTerrainJoin.cap(p, original, nearby));
                        }
                     }
                  }
               }
            }
         }

         this.setDirty();
      }

      Set<UUID> islands = this.islandRepair.remove(chunk.toLong());
      if (islands != null) {
         List<RoadIndex.Built> nearby = this.index.inChunk(chunk.toLong()).stream().map(this.index.roads::get).filter(Objects::nonNull).toList();

         for (UUID id : islands) {
            RoadIndex.Built roadx = this.index.roads.get(id);
            if (roadx != null && roadx.record.junction() != null) {
               JunctionSpec spec = roadx.record.junction().spec();

               for (RoadRaster.Cell cellx : roadx.prepareCollision().cellsInChunk(chunk.x, chunk.z)) {
                  if (!(
                        new RoadGeometry.V((double)cellx.x() + 0.5, spec.center().y(), (double)cellx.z() + 0.5).sub(spec.center()).horizontalLength()
                           > spec.islandRadius() + 0.75
                     )
                     && !((double)cellx.y() < Math.floor(spec.center().y()))
                     && !((double)cellx.y() > Math.floor(spec.center().y() + 0.75))) {
                     BlockPos p = new BlockPos(cellx.x(), cellx.y(), cellx.z());
                     BlockState previous = level.getBlockState(p);
                     if (TunnelAir.isAirSpace(previous) || RoadBlocks.isCollider(previous)) {
                        List<RoadIndex.Built> owners = nearby.stream().filter(b -> !b.boxes(p).isEmpty()).toList();
                        if (!owners.isEmpty()) {
                           level.setBlock(p, this.collisionState(p.asLong(), previous, owners), 3);
                        }
                     }
                  }
               }
            }
         }

         this.setDirty();
      }

      List<Long> groundCells = this.groundJoinRepair.remove(chunk.toLong());
      if (groundCells != null) {
         List<RoadIndex.Built> nearby = this.index.inChunk(chunk.toLong()).stream().map(this.index.roads::get).filter(Objects::nonNull).toList();

         for (long keyx : groundCells) {
            BlockPos p = BlockPos.of(keyx);
            BlockState statex = level.getBlockState(p);
            if (RoadBlocks.isCollider(statex)
               && !statex.is((Block)SplineRoads.FILLED_COLLIDER.get())
               && statex.getValue(RoadBlocks.Road.FILL) == RoadBlocks.Fill.NONE) {
               BlockState original = (BlockState)this.terrainOriginal.getOrDefault(keyx, Blocks.AIR.defaultBlockState());
               double cap = GroundTerrainJoin.cap(p, original, nearby);
               if (!(cap >= 1.0)) {
                  List<RoadIndex.Built> owners = nearby.stream().filter(b -> !b.boxes(p).isEmpty()).toList();
                  if (!owners.isEmpty()) {
                     BlockState next = this.collisionState(keyx, statex, owners);
                     if (next.is((Block)SplineRoads.FILLED_COLLIDER.get())) {
                        this.terrainFill.put(keyx, original);
                        level.setBlock(p, next, 3);
                        if (level.getBlockEntity(p) instanceof RoadFillEntity filled) {
                           filled.fill(original, cap);
                        }
                     }
                  }
               }
            }
         }

         this.setDirty();
      }

      List<RoadStructures.Part> retired = this.retiredSignsByChunk.remove(chunk.toLong());
      if (retired != null) {
         for (RoadRaster.Cell cellxx : RoadRaster.structures(retired, null).keySet()) {
            if (cellxx.x() >> 4 == chunk.x && cellxx.z() >> 4 == chunk.z) {
               BlockPos p = new BlockPos(cellxx.x(), cellxx.y(), cellxx.z());
               long keyxx = p.asLong();
               BlockState statex = level.getBlockState(p);
               List<RoadIndex.Built> owners = this.index
                  .inChunk(chunk.toLong())
                  .stream()
                  .map(this.index.roads::get)
                  .filter(r -> !r.boxes(p).isEmpty())
                  .toList();
               if (owners.isEmpty()) {
                  this.releaseRetiredCell(level, p, statex);
               } else if (RoadBlocks.isCollider(statex)) {
                  level.setBlock(p, this.collisionState(keyxx, statex, owners), 3);
                  if (level.getBlockEntity(p) instanceof RoadFillEntity fill) {
                     fill.fill((BlockState)this.terrainFill.getOrDefault(keyxx, Blocks.AIR.defaultBlockState()));
                  }
               }
            }
         }

         this.setDirty();
      }

      for (UUID idx : List.copyOf(this.index.inChunk(chunk.toLong()))) {
         RoadIndex.Built built = this.index.roads.get(idx);
         if (built != null && built.record.buildVersion() < 17) {
            Set<UUID> done = this.repairedChunks.computeIfAbsent(chunk.toLong(), ignored -> new HashSet<>());
            if (!done.contains(idx)) {
               for (RoadRaster.Cell cellxxx : built.prepareCollision().cellsInChunk(chunk.x, chunk.z)) {
                  BlockPos p = new BlockPos(cellxxx.x(), cellxxx.y(), cellxxx.z());
                  long keyxx = p.asLong();
                  BlockState statex = level.getBlockState(p);
                  if (!statex.is((Block)SplineRoads.NODE.get())
                     && !RoadBlocks.isCollider(statex)
                     && !(statex.getDestroySpeed(level, p) < 0.0F)
                     && !statex.hasBlockEntity()) {
                     List<RoadIndex.Built> owners = this.index.at(keyxx).stream().map(this.index.roads::get).toList();
                     level.setBlock(p, this.collisionState(keyxx, statex, owners), 2);
                     this.setDirty();
                  }
               }

               done.add(idx);
            }
         }
      }
   }

   private final class EditSnapshot {
      final Map<UUID, RoadIndex.Built> roadsBefore = new LinkedHashMap<>(RoadData.this.index.roads);
      final Map<UUID, RailbedStore.Entry> railbedsBefore = new LinkedHashMap<>(RoadData.this.railbeds.entries);
      final Map<UUID, RoadRecord> streetsBefore;
      final Map<UUID, CompoundTag> junctionsBefore;
      final Map<UUID, CompoundTag> interchangesBefore;
      final SmartSignRoads.Snapshot signsBefore;
      final boolean dirtyBefore = RoadData.this.isDirty();
      final boolean fullMetadata;
      final Map<Long, BlockState> world = new HashMap<>();
      final Map<Long, BlockState> original = new HashMap<>();
      final Map<Long, BlockState> fill = new HashMap<>();
      final Long2ObjectOpenHashMap<BlockState> walks = RoadData.this.sidewalkPlaced;
      final Map<Long, CompoundTag> entities = new HashMap<>();
      final Set<Long> aprons = RoadData.this.apronPlaced;

      EditSnapshot(ServerLevel level, RoadData.PreparedEdit edit, boolean fullMetadata) {
         this.fullMetadata = fullMetadata;
         this.streetsBefore = new LinkedHashMap<>();
         if (fullMetadata) {
            this.streetsBefore.putAll(RoadData.this.streets);
         } else {
            for (RoadIndex.Built road : edit.built()) {
               RoadRecord saved = RoadData.this.streets.get(road.record.id());
               if (saved != null) {
                  this.streetsBefore.put(saved.id(), saved);
               }
            }
         }

         this.junctionsBefore = fullMetadata
            ? RoadData.copyTags(RoadData.this.junctions)
            : RoadData.copyTags(RoadData.this.junctions, RoadData.this.junctionGroups(edit));
         this.interchangesBefore = fullMetadata ? RoadData.copyTags(RoadData.this.interchanges) : Map.of();
         this.signsBefore = fullMetadata ? RoadData.this.smartSigns.snapshot() : null;
         int captured = 0;

         for (long key : edit.touched()) {
            if ((captured++ & 1023) == 0) {
               RoadPlanningBudget.check();
            }

            BlockPos pos = BlockPos.of(key);
            this.world.put(key, level.getBlockState(pos));
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
               this.entities.put(key, be.saveWithFullMetadata(level.registryAccess()));
            }

            if (RoadData.this.terrainOriginal.containsKey(key)) {
               this.original.put(key, (BlockState)RoadData.this.terrainOriginal.get(key));
            }

            if (RoadData.this.terrainFill.containsKey(key)) {
               this.fill.put(key, (BlockState)RoadData.this.terrainFill.get(key));
            }
         }

         RoadPlanningBudget.check();
      }

      void restore(ServerLevel level, RuntimeException failure) {
         for (UUID id : new ArrayList<>(RoadData.this.index.roads.keySet())) {
            if (RoadData.this.index.roads.get(id) != this.roadsBefore.get(id)) {
               RoadData.this.index.remove(id);
            }
         }

         for (RoadIndex.Built road : this.roadsBefore.values()) {
            if (RoadData.this.index.roads.get(road.record.id()) != road) {
               RoadData.this.index.put(road);
            }
         }

         RoadData.this.index.restoreOrder(this.roadsBefore);
         RoadData.this.railbeds.entries.clear();
         RoadData.this.railbeds.entries.putAll(this.railbedsBefore);
         if (this.fullMetadata) {
            RoadData.this.streets.clear();
            RoadData.this.junctions.clear();
            RoadData.this.interchanges.clear();
            RoadData.this.interchanges.putAll(this.interchangesBefore);
         }

         RoadData.this.streets.putAll(this.streetsBefore);
         RoadData.this.junctions.putAll(this.junctionsBefore);

         for (long key : this.world.keySet()) {
            RoadData.this.terrainOriginal.remove(key);
            RoadData.this.terrainFill.remove(key);
         }

         RoadData.this.terrainOriginal.putAll(this.original);
         RoadData.this.terrainFill.putAll(this.fill);
         RoadData.this.sidewalkPlaced = this.walks;
         RoadData.this.apronPlaced = this.aprons;
         RoadData.restoreBlocks(level, this.world, this.entities, failure::addSuppressed);
         if (this.signsBefore != null) {
            RoadData.this.smartSigns.restore(this.signsBefore);
         }

         RoadData.this.setDirty(this.dirtyBefore);
         if (failure.getSuppressed().length != 0) {
            System.getLogger("SplineRoads/transaction").log(Level.ERROR, "An external world hook prevented complete rollback", failure);
         }
      }
   }

   public static record NodeMove(BlockPos from, BlockPos to, RoadGeometry.Node target, CompoundTag snapshot) {
      static RoadData.NodeMove retire(BlockPos from) {
         return new RoadData.NodeMove(from, null, null, null);
      }
   }

   private static record PreparedEdit(
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      boolean deleting,
      Set<Long> touched,
      Set<Long> moveSources,
      Set<Long> moveTargets,
      Map<Long, BlockState> sidewalks,
      Set<Long> aprons,
      Map<Long, List<RoadIndex.Built>> body,
      Set<Long> air,
      Set<Long> dry,
      Map<Long, List<RoadIndex.Built>> finalByChunk,
      Set<BlockPos> endpoints,
      List<RoadIndex.Built> finalRoads,
      List<RoadData.NodeMove> moves,
      int editCellLimit,
      Set<Long> chunks
   ) {
   }

   private static record PreparedEntry(RoadData.PreparedEdit edit, RoadPlanInputs inputs, UUID owner, long revision, long created) {
   }

   static record SplitPrepared(RoadData.PreparedEdit edit, long revision, Map<UUID, RoadRecord> dependencies) {
      int cells() {
         return this.edit.touched().size();
      }
   }

   static record StreetLedger(BlockState fill, BlockState original, BlockState sidewalk, boolean apron) {
   }
}
