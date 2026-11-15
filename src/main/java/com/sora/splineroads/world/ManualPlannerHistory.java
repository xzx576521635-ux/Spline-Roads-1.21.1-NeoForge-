package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.net.RoadNetwork;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

public final class ManualPlannerHistory {
   private static final ThreadLocal<ManualPlannerHistory.Scope> ACTIVE = new ThreadLocal<>();
   private final ArrayDeque<ManualPlannerHistory.Entry> undo = new ArrayDeque<>();
   private final ArrayDeque<ManualPlannerHistory.Entry> redo = new ArrayDeque<>();
   private static final int MAX_CELLS = 1000000;
   private static final int MAX_RETAINED = 2000000;
   private int limit = 30;
   private long revision;
   private Set<Long> changedChunks = Set.of();

   public Set<Long> changedChunks() {
      return this.changedChunks;
   }

   private static Set<Long> chunks(ManualPlannerHistory.Entry entry) {
      HashSet<Long> result = new HashSet<>();

      for (long cell : entry.before().blocks.keySet()) {
         result.add(new ChunkPos(BlockPos.of(cell)).toLong());
      }

      return Set.copyOf(result);
   }

   public long revision() {
      return this.revision;
   }

   public int limit() {
      return this.limit;
   }

   public void limit(int value) {
      if (value >= 1 && value <= 30) {
         this.limit = value;

         while (this.undo.size() > this.limit) {
            this.undo.removeFirst();
         }

         while (this.redo.size() > this.limit) {
            this.redo.removeFirst();
         }
      } else {
         throw new IllegalArgumentException("撤销最大步应为1–30");
      }
   }

   public int undoCount() {
      return this.undo.size();
   }

   public int redoCount() {
      return this.redo.size();
   }

   public ManualPlannerHistory.Scope begin(ServerPlayer player) {
      return new ManualPlannerHistory.Scope(player);
   }

   static void capture(ServerLevel level, Collection<Long> cells) {
      ManualPlannerHistory.Scope scope = ACTIVE.get();
      if (scope != null) {
         if (scope.level != level) {
            throw new IllegalArgumentException("地图编辑维度已改变");
         }

         scope.capture(cells);
      }
   }

   private static Map<UUID, CompoundTag> tags(Map<UUID, CompoundTag> source) {
      LinkedHashMap<UUID, CompoundTag> result = new LinkedHashMap<>();
      source.forEach((id, t) -> result.put(id, t.copy()));
      return result;
   }

   public String apply(ServerPlayer player, boolean reverse) {
      ArrayDeque<ManualPlannerHistory.Entry> from = reverse ? this.undo : this.redo;
      ArrayDeque<ManualPlannerHistory.Entry> to = reverse ? this.redo : this.undo;
      if (from.isEmpty()) {
         throw new IllegalArgumentException(reverse ? "没有可撤销的地图操作" : "没有可重做的地图操作");
      } else {
         ManualPlannerHistory.Entry entry = from.peekLast();
         ServerLevel level = player.serverLevel();
         RoadData data = RoadData.get(level);
         ManualPlannerHistory.State current = ManualPlannerHistory.State.of(data);
         entry.roads().validate(current.roads(), reverse);
         entry.streets().validate(current.streets(), reverse);
         entry.junctions().validate(current.junctions(), reverse);
         entry.interchanges().validate(current.interchanges(), reverse);
         entry.signs().validate(current.signs(), reverse);
         StreetJournal.Snapshot expected = reverse ? entry.after() : entry.before();
         StreetJournal.Snapshot target = reverse ? entry.before() : entry.after();
         HashSet<BlockPos> changedNodes = new HashSet<>();

         for (long key : target.blocks.keySet()) {
            if (target.blocks.get(key).is((Block)SplineRoads.NODE.get()) || expected.blocks.get(key).is((Block)SplineRoads.NODE.get())) {
               changedNodes.add(BlockPos.of(key));
            }
         }

         for (RoadIndex.Built road : data.index.roads.values()) {
            if (!entry.roads().keys().contains(road.record.id())) {
               if (changedNodes.contains(road.record.a()) || changedNodes.contains(road.record.b())) {
                  throw new IllegalArgumentException("后续道路使用了这个端点，请先撤销后续连接");
               }

               for (long keyx : target.blocks.keySet()) {
                  if (road.cells.containsKey(keyx) && !Objects.equals(target.blocks.get(keyx), expected.blocks.get(keyx))) {
                     throw new IllegalArgumentException("后续道路使用了原施工范围");
                  }
               }
            }
         }

         try (RoadWorkChunks chunks = RoadWorkChunks.open(level)) {
            HashSet<Long> needed = new HashSet<>();

            for (long keyxx : target.blocks.keySet()) {
               needed.add(new ChunkPos(BlockPos.of(keyxx)).toLong());
            }

            chunks.load(needed);
            StreetJournal.Snapshot world = new StreetJournal.Snapshot(level, target.blocks.keySet());
            Iterator var39 = target.blocks.keySet().iterator();

            RoadNetwork.PreparedBatch publication;
            List<RoadRecord> records;
            Consumer<UUID> callback;
            while (true) {
               if (!var39.hasNext()) {
                  records = List.copyOf((reverse ? entry.roads().before() : entry.roads().after()).values());
                  BlockPos point = changedNodes.stream().findFirst().orElse(records.isEmpty() ? BlockPos.ZERO : records.get(0).a());
                  callback = data.index.changed;
                  data.index.changed = id -> {
                  };

                  try {
                     RoadData.restorePlannerBlocks(level, target.blocks, target.entities);
                     target.ledger.forEach(data::streetLedger);
                     restore(data, entry, reverse);
                     publication = entry.roads().keys().isEmpty() ? null : RoadNetwork.prepareBatch(level, records, entry.roads().keys(), point);
                     break;
                  } catch (RuntimeException var29) {
                     try {
                        RoadData.restorePlannerBlocks(level, world.blocks, world.entities);
                        world.ledger.forEach(data::streetLedger);
                        restore(data, entry, !reverse);
                     } catch (RuntimeException var28) {
                        var29.addSuppressed(var28);
                     }

                     throw var29;
                  } finally {
                     data.index.changed = callback;
                  }
               }

               long keyxx = (Long)var39.next();
               BlockPos p = BlockPos.of(keyxx);
               if (!expected.cellEquals(world, keyxx) || !Objects.equals(expected.ledger.get(keyxx), world.ledger.get(keyxx))) {
                  throw new IllegalArgumentException("原位置已被其他操作改变，撤销/重做未写入世界：" + p.toShortString());
               }

               if (!level.mayInteract(player, p)) {
                  throw new IllegalArgumentException("没有修改该位置的权限");
               }

               RoadEditProtection.check(level, player, p, world.blocks.get(keyxx), target.blocks.get(keyxx));
            }

            entry.roads().keys().forEach(callback);
            data.setDirty();
            if (publication != null) {
               RoadNetwork.broadcastBatch(level, records, entry.roads().keys(), publication);
            }
         }

         from.removeLast();
         to.addLast(entry);
         this.revision++;
         this.changedChunks = chunks(entry);
         return reverse ? "已撤销地图操作" : "已重做地图操作";
      }
   }

   private static void restore(RoadData data, ManualPlannerHistory.Entry entry, boolean reverse) {
      for (UUID id : entry.roads().keys()) {
         data.index.remove(id);
      }

      for (RoadRecord r : (reverse ? entry.roads().before() : entry.roads().after()).values()) {
         data.index.put(RoadIndex.Built.loading(r));
      }

      entry.streets().apply(data.streets, reverse);
      entry.junctions().apply(data.junctions, reverse, CompoundTag::copy);
      entry.interchanges().apply(data.interchanges, reverse, CompoundTag::copy);
      RailbedRoads.sync(data, entry.roads().keys().stream().map(data.index.roads::get).filter(Objects::nonNull).toList(), entry.roads().keys());
      SmartSignRoads.Snapshot signs = data.smartSigns.snapshot();
      LinkedHashMap<SmartSigns.Key, SmartSigns.State> states = new LinkedHashMap<>();
      signs.states().forEach(s -> states.put(s.key(), s));
      entry.signs().apply(states, reverse);
      data.smartSigns.restore(new SmartSignRoads.Snapshot(List.copyOf(states.values()), signs.versions(), signs.sightDirty()));
   }

   private static record Changes<K, V>(Map<K, V> before, Map<K, V> after, Set<K> keys) {
      static <K, V> ManualPlannerHistory.Changes<K, V> between(Map<K, V> before, Map<K, V> after) {
         HashSet<K> keys = new HashSet<>(before.keySet());
         keys.addAll(after.keySet());
         keys.removeIf(kx -> Objects.equals(before.get(kx), after.get(kx)));
         HashMap<K, V> a = new HashMap<>();
         HashMap<K, V> b = new HashMap<>();

         for (K k : keys) {
            if (before.containsKey(k)) {
               a.put(k, before.get(k));
            }

            if (after.containsKey(k)) {
               b.put(k, after.get(k));
            }
         }

         return new ManualPlannerHistory.Changes<>(Map.copyOf(a), Map.copyOf(b), Set.copyOf(keys));
      }

      void validate(Map<K, V> current, boolean reverse) {
         Map<K, V> expected = reverse ? this.after : this.before;

         for (K k : this.keys) {
            if (!Objects.equals(current.get(k), expected.get(k))) {
               throw new IllegalArgumentException("相关道路、节点或配置已被修改，不能覆盖后续改动");
            }
         }
      }

      void apply(Map<K, V> target, boolean reverse) {
         this.apply(target, reverse, UnaryOperator.identity());
      }

      void apply(Map<K, V> target, boolean reverse, UnaryOperator<V> copy) {
         Map<K, V> next = reverse ? this.before : this.after;

         for (K k : this.keys) {
            if (next.containsKey(k)) {
               target.put(k, copy.apply(next.get(k)));
            } else {
               target.remove(k);
            }
         }
      }
   }

   private static record Entry(
      StreetJournal.Snapshot before,
      StreetJournal.Snapshot after,
      ManualPlannerHistory.Changes<UUID, RoadRecord> roads,
      ManualPlannerHistory.Changes<UUID, RoadRecord> streets,
      ManualPlannerHistory.Changes<UUID, CompoundTag> junctions,
      ManualPlannerHistory.Changes<UUID, CompoundTag> interchanges,
      ManualPlannerHistory.Changes<SmartSigns.Key, SmartSigns.State> signs
   ) {
      int size() {
         return this.before.blocks.size();
      }

      boolean empty() {
         return this.before.blocks.isEmpty()
            && this.roads.keys().isEmpty()
            && this.streets.keys().isEmpty()
            && this.junctions.keys().isEmpty()
            && this.interchanges.keys().isEmpty()
            && this.signs.keys().isEmpty();
      }
   }

   public final class Scope implements AutoCloseable {
      private final ServerLevel level;
      private final ManualPlannerHistory.State state;
      private final StreetJournal.Snapshot before = new StreetJournal.Snapshot();
      private boolean finished;

      private Scope(ServerPlayer player) {
         if (ManualPlannerHistory.ACTIVE.get() != null) {
            throw new IllegalStateException("地图编辑不可嵌套");
         } else {
            this.level = player.serverLevel();
            this.state = ManualPlannerHistory.State.of(RoadData.get(this.level));
            ManualPlannerHistory.ACTIVE.set(this);
         }
      }

      private void capture(Collection<Long> cells) {
         HashSet<Long> missing = new HashSet<>(cells);
         missing.removeAll(this.before.blocks.keySet());
         if (this.before.blocks.size() + missing.size() > 1000000) {
            throw new IllegalArgumentException("本次地图编辑超过撤销记录上限，请分段操作");
         } else {
            StreetJournal.Snapshot values = new StreetJournal.Snapshot(this.level, missing);
            this.before.blocks.putAll(values.blocks);
            this.before.entities.putAll(values.entities);
            this.before.ledger.putAll(values.ledger);
         }
      }

      private ManualPlannerHistory.Entry changes() {
         StreetJournal.Snapshot after = new StreetJournal.Snapshot(this.level, this.before.blocks.keySet());
         HashSet<Long> changed = new HashSet<>();

         for (long key : this.before.blocks.keySet()) {
            if (!this.before.cellEquals(after, key) || !Objects.equals(this.before.ledger.get(key), after.ledger.get(key))) {
               changed.add(key);
            }
         }

         this.before.keep(changed);
         after.keep(changed);
         ManualPlannerHistory.State current = ManualPlannerHistory.State.of(RoadData.get(this.level));
         return new ManualPlannerHistory.Entry(
            this.before,
            after,
            ManualPlannerHistory.Changes.between(this.state.roads(), current.roads()),
            ManualPlannerHistory.Changes.between(this.state.streets(), current.streets()),
            ManualPlannerHistory.Changes.between(this.state.junctions(), current.junctions()),
            ManualPlannerHistory.Changes.between(this.state.interchanges(), current.interchanges()),
            ManualPlannerHistory.Changes.between(this.state.signs(), current.signs())
         );
      }

      public void finish() {
         if (!this.finished) {
            ManualPlannerHistory.Entry entry = this.changes();
            this.finished = true;
            if (!entry.empty()) {
               ManualPlannerHistory.this.revision++;
               ManualPlannerHistory.this.changedChunks = ManualPlannerHistory.chunks(entry);
               ManualPlannerHistory.this.undo.addLast(entry);
               ManualPlannerHistory.this.redo.clear();

               while (
                  ManualPlannerHistory.this.undo.size() > ManualPlannerHistory.this.limit
                     || ManualPlannerHistory.this.undo.stream().mapToInt(ManualPlannerHistory.Entry::size).sum() > 2000000
               ) {
                  ManualPlannerHistory.this.undo.removeFirst();
               }
            }
         }
      }

      @Override
      public void close() {
         try {
            if (!this.finished) {
               ManualPlannerHistory.Entry entry = this.changes();
               if (!entry.empty()) {
                  RoadData data = RoadData.get(this.level);
                  Consumer<UUID> callback = data.index.changed;
                  data.index.changed = idx -> {
                  };

                  try {
                     RoadData.restorePlannerBlocks(this.level, this.before.blocks, this.before.entities);
                     this.before.ledger.forEach(data::streetLedger);
                     ManualPlannerHistory.restore(data, entry, true);
                  } finally {
                     data.index.changed = callback;
                  }

                  entry.roads().keys().forEach(callback);
                  data.setDirty();

                  for (UUID id : entry.roads().keys()) {
                     RoadIndex.Built b = data.index.roads.get(id);
                     if (b == null) {
                        RoadNetwork.broadcastDelete(this.level, id);
                     } else {
                        RoadNetwork.broadcastRoad(this.level, b.record);
                     }
                  }
               }
            }
         } finally {
            if (ManualPlannerHistory.ACTIVE.get() == this) {
               ManualPlannerHistory.ACTIVE.remove();
            }
         }
      }
   }

   private static record State(
      Map<UUID, RoadRecord> roads,
      Map<UUID, RoadRecord> streets,
      Map<UUID, CompoundTag> junctions,
      Map<UUID, CompoundTag> interchanges,
      Map<SmartSigns.Key, SmartSigns.State> signs
   ) {
      static ManualPlannerHistory.State of(RoadData data) {
         LinkedHashMap<UUID, RoadRecord> roads = new LinkedHashMap<>();
         data.index.roads.forEach((id, b) -> roads.put(id, b.record));
         LinkedHashMap<SmartSigns.Key, SmartSigns.State> signs = new LinkedHashMap<>();
         data.smartSigns.snapshot().states().forEach(s -> signs.put(s.key(), s));
         return new ManualPlannerHistory.State(
            roads, new LinkedHashMap<>(data.streets), ManualPlannerHistory.tags(data.junctions), ManualPlannerHistory.tags(data.interchanges), signs
         );
      }
   }
}
