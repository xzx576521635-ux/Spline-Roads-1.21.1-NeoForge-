package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadAutoTunnels;
import com.sora.splineroads.core.RoadFoundation;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadTerrainSurface;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

final class RoadTerrain {
   static RoadStructures.Ground read(
      final ServerLevel level, final Map<Long, BlockState> retained, final Map<Long, BlockState> originals, final Map<BlockPos, List<AABB>> terrainCache
   ) {
      RoadIndex index = RoadData.get(level).index;
      final RoadTerrainCover cover = new RoadTerrainCover(chunk -> index.inChunk(chunk).stream().map(index.roads::get).filter(Objects::nonNull).toList());
      return new RoadStructures.Ground() {
         @Override
         public double top(double x, double z, double deckY) {
            return Math.min(this.solidTop(x, z, deckY), deckY);
         }

         @Override
         public double surface(double x, double z, double roadY) {
            double surface = cover.surface(x, z, roadY, probex -> this.solidTop(x, z, probex));
            double probe = roadY + 7.05 + 0.05;
            double overhead = this.solidTop(x, z, probe);
            if (Double.isFinite(overhead) && overhead > probe + 1.0E-7) {
               double below = probe - 1.05;
               double under = this.solidTop(x, z, below);
               if (Double.isFinite(under) && under > below + 1.0E-7) {
                  double top = RoadTerrainSurface.connected(probe, p -> this.solidTop(x, z, p));
                  if (Double.isFinite(top)) {
                     surface = Double.isFinite(surface) ? Math.max(surface, top) : top;
                  }
               }
            }

            return surface;
         }

         private double solidTop(double x, double z, double deckY) {
            BlockPos key = BlockPos.containing(x, Math.min((double)(level.getMaxBuildHeight() - 1), deckY), z);
            if (!RoadWorkChunks.terrainAvailable(level, key)) {
               return Double.NaN;
            } else {
               for (AABB box : terrainCache.computeIfAbsent(
                  key,
                  k -> {
                     ArrayList<AABB> out = new ArrayList<>();
                     int bottom = Math.max(level.getMinBuildHeight(), key.getY() - 96);
                     int first = key.getY();
                     if (terrainCache instanceof RoadTerrain.Cache indexed) {
                        int top = RoadPlanInputs.height(level, key.getX(), key.getZ()) - 1;
                        top = Math.max(top, indexed.originalTop.get(BlockPos.asLong(key.getX(), 0, key.getZ())));
                        first = Math.min(first, top);
                     }

                     for (int y = first; y >= bottom; y--) {
                        RoadPlanningBudget.check();
                        BlockPos p = new BlockPos(key.getX(), y, key.getZ());
                        if (!level.hasChunkAt(p)) {
                           break;
                        }

                        BlockState state = RoadPlanInputs.state(level, p);
                        state = RoadFoundation.source(
                           p.asLong(),
                           state,
                           RoadBlocks.isCollider(state) || state.is((Block)SplineRoads.TUNNEL_AIR.get()),
                           TunnelAir.isAirSpace(state),
                           originals,
                           retained,
                           Blocks.AIR.defaultBlockState()
                        );
                        if (!state.is(BlockTags.LEAVES)
                           && !state.is(BlockTags.LOGS)
                           && !state.is((Block)SplineRoads.NODE.get())
                           && state.getFluidState().isEmpty()) {
                           VoxelShape shape = state.getCollisionShape(level, p);

                           for (AABB boxx : shape.toAabbs()) {
                              out.add(boxx.move(p));
                           }

                           if (y < key.getY() && Block.isShapeFullBlock(shape)) {
                              break;
                           }
                        }
                     }

                     out.sort(Comparator.<AABB>comparingDouble(boxxx -> boxxx.maxY).reversed());
                     return out;
                  }
               )) {
                  if (x >= box.minX - 1.0E-7 && x <= box.maxX + 1.0E-7 && z >= box.minZ - 1.0E-7 && z <= box.maxZ + 1.0E-7 && box.minY < deckY - 1.0E-7) {
                     return box.maxY;
                  }
               }

               return Double.NaN;
            }
         }

         @Override
         public boolean joined(RoadGeometry.V p) {
            return false;
         }

         @Override
         public boolean blocked(RoadStructures.Part p) {
            return false;
         }
      };
   }

   private RoadTerrain() {
   }

   static final class Cache extends HashMap<BlockPos, List<AABB>> {
      final Map<RoadGeometry.Mesh, List<RoadAutoTunnels.RoofRegion>> cycleRoofs = new IdentityHashMap<>();
      final Long2IntOpenHashMap originalTop = new Long2IntOpenHashMap();

      Cache(Map<Long, BlockState> retained, Map<Long, BlockState> originals) {
         this.originalTop.defaultReturnValue(Integer.MIN_VALUE);

         for (Map<Long, BlockState> map : List.of(retained, originals)) {
            for (Entry<Long, BlockState> entry : map.entrySet()) {
               if (!TunnelAir.isAirSpace(entry.getValue())) {
                  RoadPlanningBudget.check();
                  BlockPos p = BlockPos.of(entry.getKey());
                  long column = BlockPos.asLong(p.getX(), 0, p.getZ());
                  this.originalTop.put(column, Math.max(this.originalTop.get(column), p.getY()));
               }
            }
         }
      }
   }
}
