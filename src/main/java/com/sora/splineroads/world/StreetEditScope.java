package com.sora.splineroads.world;

import com.sora.splineroads.config.StreetServerConfig;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadTimings;
import com.sora.splineroads.core.StreetPlan;
import com.sora.splineroads.core.StreetTerrain;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

final class StreetEditScope implements AutoCloseable {
   private static final ThreadLocal<StreetEditScope> ACTIVE = new ThreadLocal<>();
   final ServerLevel level;
   final StreetPlan.Input input;
   final StreetTerrain terrain;
   final Map<Long, Long> fingerprints;
   final Set<UUID> centers;
   Set<UUID> ids;
   Set<Long> columns = new LongOpenHashSet();
   StreetJournal.Snapshot before;
   CompoundTag objects;
   int remainingCells = Integer.MAX_VALUE;

   StreetEditScope(ServerLevel level, StreetPlan.Input input, StreetTerrain terrain, Map<Long, Long> fingerprints, Set<UUID> centers) {
      if (ACTIVE.get() == null && level.getServer().isSameThread()) {
         this.level = level;
         this.input = input;
         this.terrain = terrain;
         this.fingerprints = fingerprints;
         this.centers = Set.copyOf(centers);
         ACTIVE.set(this);
      } else {
         throw new IllegalStateException("路网施工上下文无效");
      }
   }

   static boolean active() {
      return ACTIVE.get() != null;
   }

   static void prepare(
      ServerLevel level,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      Set<Long> touched,
      Map<Long, BlockState> writes,
      Map<Long, BlockState> sidewalks,
      Set<Long> aprons,
      Set<Long> restores,
      boolean preview
   ) {
      StreetEditScope scope = ACTIVE.get();
      if (scope != null) {
         if (scope.level != level) {
            throw new IllegalStateException("路网施工维度已改变");
         } else {
            scope.prepare(built, removed, touched, writes, sidewalks, aprons, restores, preview);
         }
      }
   }

   private void prepare(
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      Set<Long> touched,
      Map<Long, BlockState> writes,
      Map<Long, BlockState> sidewalks,
      Set<Long> aprons,
      Set<Long> restores,
      boolean preview
   ) {
      try (RoadTimings timing = RoadTimings.start("street_guard", built.size(), touched.size())) {
         RoadPlanningBudget.phase("路网完整占地与地形复核");
         RoadData data = RoadData.get(this.level);
         LongOpenHashSet journalKeys = new LongOpenHashSet(writes.keySet());
         journalKeys.addAll(restores);

         for (long key : restores) {
            this.checkPosition(key);
         }

         LongOpenHashSet affected = new LongOpenHashSet(journalKeys);

         for (RoadIndex.Built road : built) {
            affected.addAll(road.cells.keySet());
            affected.addAll(road.clearanceCells);
         }

         for (UUID id : removed) {
            RoadIndex.Built old = data.index.roads.get(id);
            if (old != null) {
               affected.addAll(old.cells.keySet());
               affected.addAll(old.clearanceCells);
            }
         }

         for (long key : touched) {
            RoadData.StreetLedger prior = data.streetLedger(key);
            if (!Objects.equals(prior.sidewalk(), sidewalks.get(key)) || prior.apron() != aprons.contains(key)) {
               affected.add(key);
               journalKeys.add(key);
               this.checkPosition(key);
            }
         }

         if (journalKeys.size() > (Integer)StreetServerConfig.MAX_UNIT_CELLS.get()) {
            throw new IllegalArgumentException("单批路口施工范围超过服务器上限，请降低密度或断面宽度");
         }

         if (journalKeys.size() > this.remainingCells) {
            throw new IllegalArgumentException("本任务恢复日志达到服务器上限，已停止后续施工；可撤销或分开规划");
         }

         timing.stage("journal_candidates");

         for (RoadIndex.Built road : built) {
            RoadIndex.Built old = data.index.roads.get(road.record.id());

            for (long keyx : road.cells.keySet()) {
               if (old == null || !old.cells.containsKey(keyx)) {
                  this.checkPosition(keyx);
               }
            }

            for (long keyxx : road.clearanceCells) {
               if (old == null || !old.clearanceCells.contains(keyxx)) {
                  this.checkPosition(keyxx);
               }
            }
         }

         for (long keyxxx : writes.keySet()) {
            this.checkPosition(keyxxx);
         }

         LongOpenHashSet examined = new LongOpenHashSet();
         LongIterator var36 = affected.iterator();

         while (var36.hasNext()) {
            long keyxxx = (Long)var36.next();
            BlockPos p = BlockPos.of(keyxxx);
            examined.add(columnKey(p.getX(), p.getZ()));
         }

         var36 = examined.iterator();

         while (var36.hasNext()) {
            long keyxxx = (Long)var36.next();
            if (!this.columns.contains(keyxxx) && this.input.allowed((double)((int)(keyxxx >> 32)) + 0.5, (double)((int)keyxxx) + 0.5)) {
               this.columns.add(keyxxx);
            }
         }

         timing.stage("complete_footprint");
         RoadPlanningBudget.phase("路网过期地形复核");
         int checked = 0;

         for (long keyxxx : this.columns) {
            if ((checked++ & 511) == 0) {
               RoadPlanningBudget.check();
            }

            int x = (int)(keyxxx >> 32);
            int z = (int)keyxxx;
            StreetTerrain.Chunk chunk = this.terrain.chunks().get(StreetPlan.Cell.at((double)x, (double)z));
            if (chunk == null) {
               throw new IllegalArgumentException("施工地形尚未读取");
            }

            long expected = this.fingerprints.getOrDefault(keyxxx, chunk.fingerprint(StreetTerrain.column((double)x, (double)z)));
            if (StreetTerrainCapture.column(this.level, x, z).fingerprint() != expected) {
               throw new IllegalArgumentException("规划范围的地形或建筑已变化，请重新读取预览：" + x + ", " + z);
            }
         }

         timing.stage("fingerprints");
         RoadPlanningBudget.phase("路网写入保护");

         for (Entry<Long, BlockState> write : writes.entrySet()) {
            BlockPos p = BlockPos.of(write.getKey());
            BlockState state = this.level.getBlockState(p);
            if (!state.isAir()
               && !RoadBlocks.isCollider(state)
               && !(this.level.getBlockEntity(p) instanceof NodeEntity)
               && !Objects.equals(data.streetLedger(write.getKey()).sidewalk(), state)
               && !StreetTerrainCapture.natural(state)) {
               throw new IllegalArgumentException("施工遇到保留建筑或设施：" + p.toShortString());
            }

            double surface = this.terrain.at((double)p.getX() + 0.5, (double)p.getZ() + 0.5).y();
            if ((double)p.getY() < surface - this.input.options().earthwork() - 1.01) {
               throw new IllegalArgumentException("施工开挖超过已确认预算：" + p.toShortString());
            }
         }

         timing.stage("write_protection");
         RoadPlanningBudget.phase("路网恢复日志快照");
         if (!preview) {
            this.ids = new HashSet<>(removed);

            for (RoadIndex.Built r : built) {
               this.ids.add(r.record.id());
            }

            this.before = new StreetJournal.Snapshot(this.level, journalKeys);
            this.objects = data.streetObjects(this.ids, this.centers);
         }

         timing.stage("snapshot");
      }
   }

   private void checkPosition(long key) {
      BlockPos p = BlockPos.of(key);
      if (this.columns.add(columnKey(p.getX(), p.getZ()))) {
         if (!this.input.allowed((double)p.getX() + 0.5, (double)p.getZ() + 0.5)) {
            throw new IllegalArgumentException("完整施工占地进入选区外或保护区：" + p.toShortString());
         }
      }
   }

   StreetJournal finish() {
      if (this.before == null) {
         throw new IllegalStateException("路网施工没有事务记录");
      } else {
         RoadData data = RoadData.get(this.level);
         StreetJournal.Snapshot after = new StreetJournal.Snapshot(this.level, this.before.blocks.keySet());
         LongOpenHashSet changed = new LongOpenHashSet();

         for (long key : this.before.blocks.keySet()) {
            if (!this.before.cellEquals(after, key) || !Objects.equals(this.before.ledger.get(key), after.ledger.get(key))) {
               changed.add(key);
            }
         }

         this.before.keep(changed);
         after.keep(changed);
         LongOpenHashSet changedColumns = new LongOpenHashSet();
         LongIterator var10 = changed.iterator();

         while (var10.hasNext()) {
            long keyx = (Long)var10.next();
            BlockPos p = BlockPos.of(keyx);
            changedColumns.add(columnKey(p.getX(), p.getZ()));
         }

         var10 = changedColumns.iterator();

         while (var10.hasNext()) {
            long keyx = (Long)var10.next();
            this.fingerprints.put(keyx, StreetTerrainCapture.column(this.level, (int)(keyx >> 32), (int)keyx).fingerprint());
         }

         return new StreetJournal(this.ids, this.centers, this.before, after, this.objects, data.streetObjects(this.ids, this.centers));
      }
   }

   static long columnKey(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   @Override
   public void close() {
      if (ACTIVE.get() == this) {
         ACTIVE.remove();
      }
   }
}
