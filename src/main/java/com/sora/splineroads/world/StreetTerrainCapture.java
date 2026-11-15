package com.sora.splineroads.world;

import com.sora.splineroads.config.StreetServerConfig;
import com.sora.splineroads.core.StreetPlan;
import com.sora.splineroads.core.StreetTerrain;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;

public final class StreetTerrainCapture {
   private final ServerLevel level;
   private final Iterator<StreetPlan.Cell> remaining;
   private final Map<StreetPlan.Cell, StreetTerrain.Chunk> result = new TreeMap<>();
   private StreetPlan.Cell cell;
   private int column = 256;
   private int[] heights;
   private byte[] ground;
   private long[] fingerprints;
   private int completed;
   private final int count;
   private boolean done;

   public StreetTerrainCapture(ServerLevel level, Set<StreetPlan.Cell> cells) {
      if (cells.size() > (Integer)StreetServerConfig.MAX_CHUNKS.get()) {
         throw new IllegalArgumentException("选区超过服务器路网区块上限");
      } else {
         this.level = level;
         this.count = cells.size();
         this.remaining = new TreeSet<>(cells).iterator();
      }
   }

   public boolean step() {
      if (!this.level.getServer().isSameThread()) {
         throw new IllegalStateException("地形必须在服务端线程读取");
      } else if (this.done) {
         return true;
      } else {
         if (this.column == 256) {
            if (!this.remaining.hasNext()) {
               this.done = true;
               return true;
            }

            this.cell = this.remaining.next();
            this.column = 0;
            this.heights = new int[256];
            this.ground = new byte[256];
            this.fingerprints = new long[256];
         }

         long until = System.nanoTime() + (long)((Integer)StreetServerConfig.WORK_MICROS.get()).intValue() * 1000L;
         int budget = (Integer)StreetServerConfig.CAPTURE_COLUMNS.get();

         try (RoadWorkChunks lease = RoadWorkChunks.open(this.level)) {
            lease.load(Set.of(ChunkPos.asLong(this.cell.x(), this.cell.z())));

            do {
               int x = this.cell.x() * 16 + this.column % 16;
               int z = this.cell.z() * 16 + this.column / 16;
               StreetTerrainCapture.Column value = column(this.level, x, z);
               this.heights[this.column] = value.height;
               this.ground[this.column] = (byte)value.ground.ordinal();
               this.fingerprints[this.column] = value.fingerprint;
               this.column++;
               budget--;
            } while (this.column < 256 && budget > 0 && System.nanoTime() < until);
         }

         if (this.column == 256) {
            this.result.put(this.cell, new StreetTerrain.Chunk(this.heights, this.ground, this.fingerprints));
            this.completed++;
         }

         return false;
      }
   }

   public int completed() {
      return this.completed;
   }

   public int count() {
      return this.count;
   }

   public StreetTerrain snapshot() {
      return new StreetTerrain(this.result);
   }

   static StreetTerrainCapture.Column column(ServerLevel level, int x, int z) {
      int height = level.getHeight(Types.WORLD_SURFACE, x, z);
      StreetPlan.Ground ground = StreetPlan.Ground.LAND;

      while (height > level.getMinBuildHeight() && plant(level.getBlockState(new BlockPos(x, height - 1, z)))) {
         height--;
      }

      if (height <= level.getMinBuildHeight() || height + 10 >= level.getMaxBuildHeight()) {
         ground = StreetPlan.Ground.OBSTACLE;
      }

      long hash = -3750763034362895579L;

      for (int y = Math.max(level.getMinBuildHeight(), height - 5); y < Math.min(level.getMaxBuildHeight(), height + 11); y++) {
         BlockPos p = new BlockPos(x, y, z);
         BlockState state = level.getBlockState(p);
         BlockEntity entity = state.hasBlockEntity() ? level.getBlockEntity(p) : null;
         BlockState fingerprintState = state.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : state;
         hash = (hash ^ (long)Block.getId(fingerprintState)) * 1099511628211L;
         hash = (hash ^ (long)(entity == null ? 0 : entity.saveWithFullMetadata(level.registryAccess()).hashCode())) * 1099511628211L;
         if (!state.getFluidState().isEmpty()) {
            ground = StreetPlan.Ground.WATER;
         } else if (!RoadBlocks.isCollider(state) && !(entity instanceof NodeEntity)) {
            if (entity != null || !natural(state)) {
               ground = StreetPlan.Ground.OBSTACLE;
            }
         } else if (ground == StreetPlan.Ground.LAND) {
            ground = StreetPlan.Ground.ROAD;
         }
      }

      hash = (hash ^ (long)height) * 1099511628211L;
      return new StreetTerrainCapture.Column(height, ground, hash);
   }

   static boolean natural(BlockState state) {
      Block block = state.getBlock();
      return state.isAir()
         || block == Blocks.GRASS_BLOCK
         || state.is(BlockTags.DIRT)
         || state.is(BlockTags.BASE_STONE_OVERWORLD)
         || state.is(BlockTags.SAND)
         || block == Blocks.GRAVEL
         || block == Blocks.CLAY
         || block == Blocks.SNOW
         || block == Blocks.SNOW_BLOCK
         || block == Blocks.BEDROCK
         || block == Blocks.SHORT_GRASS
         || block == Blocks.TALL_GRASS
         || block == Blocks.FERN
         || block == Blocks.LARGE_FERN
         || state.is(BlockTags.FLOWERS);
   }

   private static boolean plant(BlockState state) {
      Block b = state.getBlock();
      return b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.LARGE_FERN || state.is(BlockTags.FLOWERS);
   }

   static record Column(int height, StreetPlan.Ground ground, long fingerprint) {
   }
}
