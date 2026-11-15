package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.RoadAprons;
import com.sora.splineroads.core.RoadCoverage;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.core.RoadStructures;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

final class SmartRoadAprons {
   static boolean material(BlockState state) {
      return state.is((Block)SplineRoads.ROAD_BLOCK.get()) || state.is((Block)SplineRoads.ROAD_SLAB.get());
   }

   static Set<Long> plan(
      Map<Long, BlockState> paving,
      Collection<RoadIndex.Built> roads,
      ServerLevel level,
      Map<Long, BlockState> originals,
      Map<Long, BlockState> previous,
      Set<Long> previousAprons,
      Collection<RoadIndex.Built> dirty
   ) {
      LongOpenHashSet aprons = new LongOpenHashSet();
      HashSet<Long> affected = new HashSet<>();

      for (RoadIndex.Built r : dirty) {
         affected.addAll(RoadCoverage.chunks(r.mesh, 3.0));
      }

      for (Entry<Long, BlockState> e : previous.entrySet()) {
         if (previousAprons.contains(e.getKey()) && !affected.contains(new ChunkPos(BlockPos.of(e.getKey())).toLong())) {
            put(paving, aprons, e.getKey(), e.getValue());
         }
      }

      ArrayList<RoadIndex.Built> local = new ArrayList<>();
      HashMap<Long, List<RoadIndex.Built>> byChunk = new HashMap<>();

      record Candidate(double top, RoadIndex.Built road) {
      }

      for (RoadIndex.Built r : roads) {
         if (!Collections.disjoint(RoadCoverage.chunks(r.mesh, 3.0), affected)) {
            local.add(r);
         }

         for (long c : r.chunks) {
            if (affected.contains(c)) {
               byChunk.computeIfAbsent(c, k -> new ArrayList<>()).add(r);
            }
         }
      }

      TreeMap<Long, Candidate> candidates = new TreeMap<>();
      RoadStructures.Ground ground = RoadTerrain.read(level, Map.of(), originals, new RoadTerrain.Cache(Map.of(), originals));

      for (RoadIndex.Built r : local) {
         if (r.record.junction() != null && r.record.junction().get().arm() < 0) {
            JunctionPlanner.Ref ref = r.record.junction();
            if (ref.piece() == ref.spec().arms().size()) {
               for (RoadAprons.JunctionCell jc : RoadAprons.junctionCells(ref, s -> grounded(s, ground, 1))) {
                  RoadAprons.Cell cx = jc.cell();
                  long chunk = ChunkPos.asLong(cx.x() >> 4, cx.z() >> 4);
                  if (affected.contains(chunk)) {
                     RoadIndex.Built owner = local.stream()
                        .filter(
                           v -> Objects.equals(v.record.assembly(), r.record.assembly())
                                 && v.record.junction() != null
                                 && v.record.junction().get().arm() == jc.arm()
                        )
                        .findFirst()
                        .orElse(r);
                     candidates.merge(BlockPos.asLong(cx.x(), 0, cx.z()), new Candidate(cx.top(), owner), (a, b) -> (Candidate)(a.top() <= b.top() ? a : b));
                  }
               }
            }
         } else {
            for (RoadAprons.Cell cx : RoadAprons.cells(r.mesh, (sample, side) -> grounded(sample, ground, side))) {
               long chunk = ChunkPos.asLong(cx.x() >> 4, cx.z() >> 4);
               if (affected.contains(chunk)) {
                  candidates.merge(BlockPos.asLong(cx.x(), 0, cx.z()), new Candidate(cx.top(), r), (a, b) -> (Candidate)(a.top() <= b.top() ? a : b));
               }
            }
         }
      }

      for (Entry<Long, Candidate> entry : candidates.entrySet()) {
         RoadPlanningBudget.check();
         BlockPos column = BlockPos.of(entry.getKey());
         double roadTop = entry.getValue().top();
         RoadIndex.Built owner = entry.getValue().road();
         boolean terrainMode = owner.record.railway() || owner.record.settings().options().streetscape().apron() == RoadStreetscape.Apron.TERRAIN;
         double top = Math.floor(roadTop * 2.0 + 1.0E-6) / 2.0;
         int y = (int)Math.ceil(top) - 1;
         BlockPos p = new BlockPos(column.getX(), y, column.getZ());
         if (!level.isOutsideBuildHeight(p) && level.getWorldBorder().isWithinBounds(p) && RoadWorkChunks.terrainAvailable(level, p)) {
            BlockState desired = top - (double)y < 0.75
               ? (BlockState)((Block)SplineRoads.ROAD_SLAB.get()).defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
               : ((Block)SplineRoads.ROAD_BLOCK.get()).defaultBlockState();
            List<RoadIndex.Built> near = byChunk.getOrDefault(new ChunkPos(p).toLong(), List.of());
            boolean blocked = false;

            for (RoadIndex.Built rx : near) {
               if (rx.tunnelAt(p) || rx.cycleBodyAt(p)) {
                  blocked = true;
                  break;
               }

               RoadIndex.Built.Column cxx = rx.column(p);
               if (cxx != null && (double)(y + 1) >= cxx.minTop() - 1.0E-7 && (double)y < cxx.maxTop() + 4.25) {
                  if (!GroundTerrainJoin.usable(GroundTerrainJoin.cap(p, ((Block)SplineRoads.ROAD_BLOCK.get()).defaultBlockState(), near))) {
                     blocked = true;
                     break;
                  }

                  desired = ((Block)SplineRoads.ROAD_BLOCK.get()).defaultBlockState();
               } else if (rx.clearanceAt(p)) {
                  blocked = true;
                  break;
               }
            }

            if (!blocked) {
               BlockState chosen = terrainMode
                  ? adjacent(level, owner.mesh, p, originals, previous)
                  : ((Block)SplineRoads.ROAD_BLOCK.get()).defaultBlockState();
               if (chosen != null) {
                  if (terrainMode) {
                     desired = chosen;
                  }

                  BlockState current = RoadPlanInputs.state(level, p);
                  BlockState base = terrain(current, p, originals, previous);
                  if ((!previous.containsKey(p.asLong()) || RoadBlocks.isCollider(current) || current.equals(previous.get(p.asLong()))) && replaceable(base)) {
                     BlockPos below = p.below();
                     if (RoadWorkChunks.terrainAvailable(level, below)) {
                        BlockState lower = RoadPlanInputs.state(level, below);
                        BlockState support = terrain(lower, below, originals, previous);
                        if (!base.canOcclude() && !support.canOcclude()) {
                           if (!replaceable(support) || !support.getFluidState().isEmpty() || support.hasBlockEntity()) {
                              continue;
                           }

                           BlockPos beneath = below.below();
                           if (!RoadWorkChunks.terrainAvailable(level, beneath)) {
                              continue;
                           }

                           BlockState foundation = RoadPlanInputs.state(level, beneath);
                           if (!foundation.canOcclude()
                              || foundation.hasBlockEntity()
                              || !foundation.getFluidState().isEmpty()
                              || near.stream()
                                 .anyMatch(rx -> rx.clearanceAt(below) && rx.boxes(below).isEmpty() || rx.tunnelAt(below) || rx.cycleBodyAt(below))) {
                              continue;
                           }

                           put(paving, aprons, below.asLong(), chosen);
                        }

                        put(paving, aprons, p.asLong(), desired);
                        int edgeY = (int)Math.floor(roadTop);
                        BlockPos edge = new BlockPos(p.getX(), edgeY, p.getZ());
                        if (edgeY != y
                           && GroundTerrainJoin.usable(GroundTerrainJoin.cap(edge, ((Block)SplineRoads.ROAD_BLOCK.get()).defaultBlockState(), near))) {
                           BlockState at = RoadPlanInputs.state(level, edge);
                           if (replaceable(terrain(at, edge, originals, previous)) && !at.hasBlockEntity()) {
                              put(paving, aprons, edge.asLong(), chosen);
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return aprons;
   }

   private static BlockState adjacent(ServerLevel level, RoadGeometry.Mesh mesh, BlockPos p, Map<Long, BlockState> originals, Map<Long, BlockState> previous) {
      RoadQueries.Projection q = RoadQueries.horizontal(mesh, new RoadGeometry.V((double)p.getX() + 0.5, (double)p.getY(), (double)p.getZ() + 0.5));
      int side = q.lateral() < 0.0 ? -1 : 1;

      for (double reach : new double[]{1.5, 2.5, 3.5}) {
         for (int down = 0; down <= 2; down++) {
            RoadGeometry.V edge = q.sample().at((double)side * (q.sample().halfWidth() + reach), 0.0);
            BlockPos at = BlockPos.containing(edge.x(), (double)(p.getY() - down), edge.z());
            if (RoadWorkChunks.terrainAvailable(level, at)) {
               BlockState state = terrain(RoadPlanInputs.state(level, at), at, originals, previous);
               if (state.canOcclude() && !state.hasBlockEntity() && state.getFluidState().isEmpty() && !RoadBlocks.isCollider(state) && !material(state)) {
                  return state;
               }
            }
         }
      }

      return null;
   }

   private static void put(Map<Long, BlockState> paving, Set<Long> aprons, long key, BlockState state) {
      if (paving.putIfAbsent(key, state) == null) {
         aprons.add(key);
      }
   }

   private static boolean grounded(RoadGeometry.Sample sample, RoadStructures.Ground ground, int side) {
      int contact = 0;

      for (double f : new double[]{0.35, 0.65, 0.9}) {
         RoadGeometry.V p = sample.at((double)side * sample.halfWidth() * f, 0.0);
         double y = ground.top(p.x(), p.z(), p.y());
         if (Double.isFinite(y) && p.y() - y <= 0.125) {
            contact++;
         }
      }

      return contact >= 2;
   }

   private static BlockState terrain(BlockState current, BlockPos p, Map<Long, BlockState> originals, Map<Long, BlockState> previous) {
      return !RoadBlocks.isCollider(current) && !previous.containsKey(p.asLong()) ? current : originals.getOrDefault(p.asLong(), current);
   }

   private static boolean replaceable(BlockState s) {
      return !s.hasBlockEntity()
         && s.getFluidState().isEmpty()
         && (
            s.isAir()
               || s.canBeReplaced()
               || s.is(BlockTags.DIRT)
               || s.is(BlockTags.SAND)
               || s.is(BlockTags.BASE_STONE_OVERWORLD)
               || s.is(Blocks.GRAVEL)
               || s.is(Blocks.SNOW_BLOCK)
               || s.is(Blocks.SNOW)
               || material(s)
         );
   }

   private SmartRoadAprons() {
   }
}
