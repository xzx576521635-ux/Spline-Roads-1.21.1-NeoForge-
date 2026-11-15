package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class StreetJournal {
   final Set<UUID> roads;
   final Set<UUID> centers;
   final StreetJournal.Snapshot before;
   final StreetJournal.Snapshot after;
   final CompoundTag oldObjects;
   final CompoundTag newObjects;

   StreetJournal(
      Set<UUID> roads, Set<UUID> centers, StreetJournal.Snapshot before, StreetJournal.Snapshot after, CompoundTag oldObjects, CompoundTag newObjects
   ) {
      this.roads = Set.copyOf(roads);
      this.centers = Set.copyOf(centers);
      this.before = before;
      this.after = after;
      this.oldObjects = oldObjects.copy();
      this.newObjects = newObjects.copy();
   }

   CompoundTag save() {
      CompoundTag t = new CompoundTag();
      t.put("RoadIds", ids(this.roads));
      t.put("CenterIds", ids(this.centers));
      t.put("Before", this.before.save());
      t.put("After", this.after.save());
      t.put("OldObjects", this.oldObjects);
      t.put("NewObjects", this.newObjects);
      return t;
   }

   static StreetJournal load(CompoundTag t) {
      return new StreetJournal(
         ids(t.getList("RoadIds", 8)),
         ids(t.getList("CenterIds", 8)),
         StreetJournal.Snapshot.load(t.getCompound("Before")),
         StreetJournal.Snapshot.load(t.getCompound("After")),
         t.getCompound("OldObjects"),
         t.getCompound("NewObjects")
      );
   }

   static ListTag ids(Set<UUID> values) {
      ListTag t = new ListTag();
      values.stream().sorted().forEach(id -> t.add(StringTag.valueOf(id.toString())));
      return t;
   }

   static Set<UUID> ids(ListTag values) {
      HashSet<UUID> result = new HashSet<>();

      for (int i = 0; i < values.size(); i++) {
         result.add(UUID.fromString(values.getString(i)));
      }

      return result;
   }

   StreetJournal.Undo undo(ServerLevel level, ServerPlayer player) {
      RoadData data = RoadData.get(level);
      if (!data.streetObjects(this.roads, this.centers).equals(this.newObjects)) {
         return new StreetJournal.Undo(false, 1);
      } else {
         HashSet<BlockPos> nodes = new HashSet<>();

         for (Tag value : this.newObjects.getList("Centers", 10)) {
            nodes.add(BlockPos.of(((CompoundTag)value).getLong("CenterPos")));
         }

         for (long key : this.before.blocks.keySet()) {
            if (this.after.blocks.get(key).is((Block)SplineRoads.NODE.get()) && !this.before.blocks.get(key).is((Block)SplineRoads.NODE.get())) {
               nodes.add(BlockPos.of(key));
            }
         }

         if (data.index
            .roads
            .values()
            .stream()
            .anyMatch(r -> !this.roads.contains(r.record.id()) && (nodes.contains(r.record.a()) || nodes.contains(r.record.b())))) {
            return new StreetJournal.Undo(false, 1);
         } else if (data.index
            .roads
            .values()
            .stream()
            .anyMatch(
               r -> !this.roads.contains(r.record.id())
                     && this.before.blocks.keySet().stream().anyMatch(k -> !this.before.cellEquals(this.after, k) && r.cells.containsKey(k))
            )) {
            return new StreetJournal.Undo(false, 1);
         } else {
            StreetJournal.Undo var29;
            try (RoadWorkChunks lease = RoadWorkChunks.open(level)) {
               HashSet<Long> chunks = new HashSet<>();

               for (long keyx : this.before.blocks.keySet()) {
                  chunks.add(new ChunkPos(BlockPos.of(keyx)).toLong());
               }

               lease.load(chunks);
               StreetJournal.Snapshot current = new StreetJournal.Snapshot(level, this.before.blocks.keySet());
               LinkedHashMap<Long, BlockState> restores = new LinkedHashMap<>();
               HashMap<Long, CompoundTag> entities = new HashMap<>();
               int conflicts = 0;

               for (long keyx : this.before.blocks.keySet()) {
                  BlockPos p = BlockPos.of(keyx);
                  if (player != null && !level.mayInteract(player, p)) {
                     return new StreetJournal.Undo(false, 1);
                  }

                  if (this.after.cellEquals(current, keyx)) {
                     restores.put(Long.valueOf(keyx), this.before.blocks.get(keyx));
                     if (this.before.entities.containsKey(keyx)) {
                        entities.put(keyx, this.before.entities.get(keyx));
                     }
                  } else {
                     conflicts++;
                     BlockState state = current.blocks.get(keyx);
                     if (RoadBlocks.isCollider(state) || state.is((Block)SplineRoads.NODE.get())) {
                        return new StreetJournal.Undo(false, conflicts);
                     }
                  }
               }

               for (Entry<Long, BlockState> e : restores.entrySet()) {
                  if (player != null) {
                     RoadEditProtection.check(level, player, BlockPos.of(e.getKey()), current.blocks.get(e.getKey()), e.getValue());
                  }
               }

               try {
                  RoadData.restoreStreetBlocks(level, restores, entities);

                  for (long keyx : this.before.blocks.keySet()) {
                     data.streetLedger(keyx, this.before.ledger.get(keyx));
                  }

                  data.restoreStreetObjects(level, this.roads, this.centers, this.oldObjects);
               } catch (RuntimeException var19) {
                  try {
                     RoadData.restoreStreetBlocks(level, current.blocks, current.entities);

                     for (long keyx : current.blocks.keySet()) {
                        data.streetLedger(keyx, current.ledger.get(keyx));
                     }

                     data.restoreStreetObjects(level, this.roads, this.centers, this.newObjects);
                  } catch (RuntimeException var18) {
                     var19.addSuppressed(var18);
                  }

                  throw var19;
               }

               var29 = new StreetJournal.Undo(true, conflicts);
            }

            return var29;
         }
      }
   }

   static final class Snapshot {
      final Map<Long, BlockState> blocks = new LinkedHashMap<>();
      final Map<Long, CompoundTag> entities = new HashMap<>();
      final Map<Long, RoadData.StreetLedger> ledger = new HashMap<>();

      Snapshot() {
      }

      Snapshot(ServerLevel level, Collection<Long> keys) {
         RoadData data = RoadData.get(level);

         for (long key : keys) {
            BlockPos p = BlockPos.of(key);
            BlockState state = level.getBlockState(p);
            this.blocks.put(key, state);
            BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(p) : null;
            if (be != null) {
               this.entities.put(key, be.saveWithFullMetadata(level.registryAccess()));
            }

            this.ledger.put(key, data.streetLedger(key));
         }
      }

      void keep(Set<Long> keys) {
         this.blocks.keySet().retainAll(keys);
         this.entities.keySet().retainAll(keys);
         this.ledger.keySet().retainAll(keys);
      }

      boolean cellEquals(StreetJournal.Snapshot other, long key) {
         return Objects.equals(this.blocks.get(key), other.blocks.get(key)) && Objects.equals(this.entities.get(key), other.entities.get(key));
      }

      CompoundTag save() {
         CompoundTag t = new CompoundTag();
         t.put("Blocks", RoadBlockStorage.write(new Long2ObjectOpenHashMap(this.blocks)));
         ListTag bes = new ListTag();
         this.entities.entrySet().stream().sorted(Entry.comparingByKey()).forEach(ex -> {
            CompoundTag v = new CompoundTag();
            v.putLong("Pos", (Long)ex.getKey());
            v.put("Data", ((CompoundTag)ex.getValue()).copy());
            bes.add(v);
         });
         t.put("Entities", bes);

         for (String kind : List.of("Fill", "Original", "Sidewalk")) {
            Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap();

            for (Entry<Long, RoadData.StreetLedger> e : this.ledger.entrySet()) {
               BlockState s = switch (kind) {
                  case "Fill" -> e.getValue().fill();
                  case "Original" -> e.getValue().original();
                  default -> e.getValue().sidewalk();
               };
               if (s != null) {
                  states.put(e.getKey(), s);
               }
            }

            t.put(kind, RoadBlockStorage.write(states));
         }

         t.putLongArray(
            "Aprons", this.ledger.entrySet().stream().filter(ex -> ((RoadData.StreetLedger)ex.getValue()).apron()).mapToLong(Entry::getKey).toArray()
         );
         return t;
      }

      static StreetJournal.Snapshot load(CompoundTag t) {
         StreetJournal.Snapshot s = new StreetJournal.Snapshot();
         Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap();
         RoadBlockStorage.read(t.getList("Blocks", 10), blocks);
         s.blocks.putAll(blocks);

         for (Tag v : t.getList("Entities", 10)) {
            CompoundTag e = (CompoundTag)v;
            s.entities.put(e.getLong("Pos"), e.getCompound("Data").copy());
         }

         Long2ObjectOpenHashMap<BlockState> fill = new Long2ObjectOpenHashMap();
         Long2ObjectOpenHashMap<BlockState> original = new Long2ObjectOpenHashMap();
         Long2ObjectOpenHashMap<BlockState> sidewalk = new Long2ObjectOpenHashMap();
         RoadBlockStorage.read(t.getList("Fill", 10), fill);
         RoadBlockStorage.read(t.getList("Original", 10), original);
         RoadBlockStorage.read(t.getList("Sidewalk", 10), sidewalk);
         HashSet<Long> aprons = new HashSet<>();

         for (long p : t.getLongArray("Aprons")) {
            aprons.add(p);
         }

         for (long key : s.blocks.keySet()) {
            s.ledger
               .put(
                  key, new RoadData.StreetLedger((BlockState)fill.get(key), (BlockState)original.get(key), (BlockState)sidewalk.get(key), aprons.contains(key))
               );
         }

         return s;
      }
   }

   static record Undo(boolean restored, int conflicts) {
   }
}
