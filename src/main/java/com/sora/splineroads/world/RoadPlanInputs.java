package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadPlanningBudget;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.levelgen.Heightmap.Types;

final class RoadPlanInputs implements AutoCloseable {
   private static final ThreadLocal<RoadPlanInputs> CURRENT = new ThreadLocal<>();
   private RoadPlanInputs previous;
   private final ServerLevel level;
   private final boolean recording;
   private final Map<Long, BlockState> states = new Long2ObjectOpenHashMap();
   private LongOpenHashSet probes = new LongOpenHashSet();
   private final Map<Long, CompoundTag> nodes = new HashMap<>();
   private final Map<Long, Integer> heights = new HashMap<>();

   private RoadPlanInputs(ServerLevel level, boolean recording) {
      this.level = level;
      this.recording = recording;
      this.previous = CURRENT.get();
      CURRENT.set(this);
   }

   static RoadPlanInputs open(ServerLevel level, boolean recording) {
      return new RoadPlanInputs(level, recording);
   }

   RoadPlanInputs resume() {
      this.previous = CURRENT.get();
      CURRENT.set(this);
      this.probes = new LongOpenHashSet();
      return this;
   }

   static BlockState state(ServerLevel level, BlockPos pos) {
      BlockState state = level.getBlockState(pos);
      RoadPlanInputs scope = CURRENT.get();
      if (scope != null && scope.recording && scope.level == level && scope.probes.add(pos.asLong())) {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               for (int dz = -1; dz <= 1; dz++) {
                  BlockPos p = pos.offset(dx, dy, dz);
                  if (!level.isOutsideBuildHeight(p) && !scope.states.containsKey(p.asLong())) {
                     if (scope.states.size() >= 4000000) {
                        throw new IllegalArgumentException("预览地形读取量过大，请分段施工");
                     }

                     scope.states.put(p.asLong(), p.equals(pos) ? state : level.getBlockState(p));
                  }
               }
            }
         }
      }

      return state;
   }

   static BlockEntity node(ServerLevel level, BlockPos pos) {
      BlockEntity entity = level.getBlockEntity(pos);
      RoadPlanInputs scope = CURRENT.get();
      if (scope != null && scope.recording && scope.level == level && !scope.nodes.containsKey(pos.asLong())) {
         scope.nodes.put(pos.asLong(), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
      }

      return entity;
   }

   static int height(ServerLevel level, int x, int z) {
      int height = level.getHeight(Types.WORLD_SURFACE, x, z);
      RoadPlanInputs scope = CURRENT.get();
      if (scope != null && scope.recording && scope.level == level) {
         scope.heights.putIfAbsent(BlockPos.asLong(x, 0, z), height);
      }

      return height;
   }

   void validate(ServerLevel current) {
      if (current != this.level) {
         throw new IllegalArgumentException("预览世界已改变，请重新预览");
      } else {
         HashSet<Long> chunks = new HashSet<>();

         for (Set<Long> keys : List.of(this.states.keySet(), this.heights.keySet(), this.nodes.keySet())) {
            for (long key : keys) {
               chunks.add(new ChunkPos(BlockPos.of(key)).toLong());
            }
         }

         try (RoadWorkChunks lease = RoadWorkChunks.open(this.level)) {
            lease.load(chunks);

            for (Entry<Long, Integer> entry : this.heights.entrySet()) {
               RoadPlanningBudget.check();
               BlockPos p = BlockPos.of(entry.getKey());
               if (this.level.getHeight(Types.WORLD_SURFACE, p.getX(), p.getZ()) != entry.getValue()) {
                  throw new IllegalArgumentException("预览后地形高度已改变，请重新预览");
               }
            }

            for (Entry<Long, BlockState> entryx : this.states.entrySet()) {
               RoadPlanningBudget.check();
               BlockPos pos = BlockPos.of(entryx.getKey());
               BlockState now = this.level.getBlockState(pos);
               BlockState before = entryx.getValue();
               if (!equivalentTerrain(before, now)) {
                  throw new IllegalArgumentException("预览后地形已改变：" + pos.toShortString() + "，" + before + " → " + now + "；请重新预览");
               }
            }

            for (Entry<Long, CompoundTag> entryxx : this.nodes.entrySet()) {
               BlockEntity entity = this.level.getBlockEntity(BlockPos.of(entryxx.getKey()));
               if (!Objects.equals(entryxx.getValue(), entity == null ? null : entity.saveWithFullMetadata(this.level.registryAccess()))) {
                  throw new IllegalArgumentException("预览后端点已改变，请重新预览");
               }
            }
         }
      }
   }

   static boolean equivalentTerrain(BlockState before, BlockState now) {
      if (!before.equals(now) && (!soil(before) || !soil(now))) {
         IntegerProperty distance = BlockStateProperties.DISTANCE;
         return before.getBlock() == now.getBlock()
            && before.getBlock() instanceof LeavesBlock
            && before.hasProperty(distance)
            && now.hasProperty(distance)
            && ((BlockState)before.setValue(distance, (Integer)now.getValue(distance))).equals(now);
      } else {
         return true;
      }
   }

   private static boolean soil(BlockState state) {
      return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT);
   }

   int retainedStates() {
      return this.states.size();
   }

   @Override
   public void close() {
      if (this.previous == null) {
         CURRENT.remove();
      } else {
         CURRENT.set(this.previous);
      }

      this.previous = null;
      this.probes = null;
   }
}
