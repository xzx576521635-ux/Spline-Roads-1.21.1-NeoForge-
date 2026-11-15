package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadStreetscape;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class SmartSidewalks {
   public static Map<Long, BlockState> plan(Collection<RoadIndex.Built> roads) {
      LinkedHashMap<Long, BlockState> result = new LinkedHashMap<>();
      HashMap<String, BlockState> materials = new HashMap<>();
      HashSet<JunctionSpec> junctions = new HashSet<>();
      Iterator byChunk = roads.stream().sorted(Comparator.comparing(b -> b.record.id())).toList().iterator();

      while (true) {
         RoadIndex.Built built;
         RoadSidewalks.Config config;
         while (true) {
            if (!byChunk.hasNext()) {
               HashMap<Long, List<RoadIndex.Built>> byChunkx = new HashMap<>();

               for (RoadIndex.Built r : roads) {
                  for (long chunk : r.chunks) {
                     byChunkx.computeIfAbsent(chunk, k -> new ArrayList<>()).add(r);
                  }
               }

               result.keySet().removeIf(key -> {
                  BlockPos p = BlockPos.of(key);
                  long column = new BlockPos(p.getX(), 0, p.getZ()).asLong();

                  for (RoadIndex.Built rx : byChunkx.getOrDefault(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4), List.of())) {
                     if (rx.clearanceAt(p)) {
                        return true;
                     }

                     if (!rx.boxes(p).isEmpty()) {
                        RoadIndex.Built.Column c = rx.column(p);
                        if (c == null || (double)(p.getY() + 1) > c.minTop() + 1.0E-7) {
                           return true;
                        }
                     }
                  }

                  return false;
               });
               return result;
            }

            built = (RoadIndex.Built)byChunk.next();
            RoadRecord r = built.record;
            config = r.settings().options().sidewalk();
            if (r.junction() == null) {
               if (r.assembly() == null && RoadProfile.catalog(r.settings().style()).type() == RoadProfile.Type.ORDINARY) {
                  break;
               }
            } else {
               JunctionSpec spec = r.junction().spec();
               if (junctions.add(spec)) {
                  for (Entry<RoadSidewalks.Cell, RoadSidewalks.Config> e : r.junction().sidewalks().entrySet()) {
                     put(result, materials, e.getKey(), e.getValue());
                  }
               }

               int arm = r.junction().get().arm();
               if (arm >= 0) {
                  config = spec.arms().get(arm).external().options().sidewalk();
                  break;
               }
            }
         }

         if (config.enabled() && !config.smooth()) {
            for (RoadSidewalks.Cell cell : RoadSidewalks.cells(built.mesh, config)) {
               if (!RoadStreetscape.underground(
                  built.mesh,
                  RoadQueries.horizontal(built.mesh, new RoadGeometry.V((double)cell.x() + 0.5, (double)cell.y(), (double)cell.z() + 0.5)).sample().distance()
               )) {
                  put(result, materials, cell, config);
               }
            }
         }
      }
   }

   public static Map<Long, BlockState> plan(
      Collection<RoadIndex.Built> roads,
      ServerLevel level,
      Map<Long, BlockState> originals,
      Map<Long, BlockState> previous,
      Set<Long> aprons,
      Collection<RoadIndex.Built> edited,
      Collection<RoadIndex.Built> dirty
   ) {
      Map<Long, BlockState> result = plan(roads);
      HashMap<String, BlockState> materials = new HashMap<>();
      HashSet<UUID> editedIds = new HashSet<>();
      dirty.forEach(r -> editedIds.add(r.record.id()));
      HashMap<Long, List<RoadIndex.Built>> unchanged = new HashMap<>();

      for (RoadIndex.Built r : roads) {
         if (!editedIds.contains(r.record.id())) {
            for (long chunk : r.chunks) {
               unchanged.computeIfAbsent(chunk, k -> new ArrayList<>()).add(r);
            }
         }
      }

      for (Entry<Long, BlockState> e : previous.entrySet()) {
         if (!aprons.contains(e.getKey())) {
            BlockPos p = BlockPos.of(e.getKey());
            RoadGeometry.V point = new RoadGeometry.V((double)p.getX() + 0.5, (double)(p.getY() + 1), (double)p.getZ() + 0.5);
            boolean affected = dirty.stream()
               .anyMatch(
                  rx -> {
                     RoadQueries.Projection q = RoadQueries.horizontal(rx.mesh, point);
                     return Math.abs(point.y() - q.sample().center().y()) < 2.0
                        && point.sub(q.sample().center()).horizontalLength()
                           <= q.sample().halfWidth() + (double)rx.record.settings().options().sidewalk().width() + 2.0;
                  }
               );
            boolean retained = unchanged.getOrDefault(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4), List.of())
               .stream()
               .anyMatch(
                  rx -> {
                     RoadSidewalks.Config cx = rx.record.settings().options().sidewalk();
                     if (!cx.enabled()) {
                        return false;
                     } else {
                        RoadQueries.Projection q = RoadQueries.horizontal(rx.mesh, point);
                        int side = q.lateral() < 0.0 ? -1 : 1;
                        if ((cx.side() != RoadSidewalks.Side.LEFT || side <= 0) && (cx.side() != RoadSidewalks.Side.RIGHT || side >= 0)) {
                           double edge = q.sample().halfWidth() + RoadSidewalks.walkWidth(rx.mesh, cx, q.sample(), side);
                           return Math.abs(point.y() - q.sample().center().y()) < 1.1
                              && Math.abs(Math.abs(q.lateral()) - edge) < 1.6
                              && q.horizontalDistance() < edge + 1.6;
                        } else {
                           return false;
                        }
                     }
                  }
               );
            if (retained || !affected && roads.stream().anyMatch(rx -> ownsBorder(rx, p))) {
               result.putIfAbsent(e.getKey(), e.getValue());
            }
         }
      }

      HashMap<Long, List<RoadIndex.Built>> byChunk = new HashMap<>();

      for (RoadIndex.Built rx : roads) {
         for (long chunk : rx.chunks) {
            byChunk.computeIfAbsent(chunk, k -> new ArrayList<>()).add(rx);
         }
      }

      for (RoadIndex.Built rx : edited) {
         RoadSidewalks.Config c = rx.record.settings().options().sidewalk();
         if (c.enabled() && c.smooth() && rx.record.settings().structure() != RoadGeometry.Structure.TUNNEL) {
            RoadSidewalks.PavingAnchors paving = new RoadSidewalks.PavingAnchors(rx.record.structures());
            RoadGeometry.Mesh expanded = new RoadGeometry.Mesh(
               rx.mesh.samples().stream().map(s -> new RoadGeometry.Sample(s.center(), s.left(), s.distance(), s.halfWidth() + (double)c.width())).toList(),
               rx.mesh.settings(),
               rx.mesh.min(),
               rx.mesh.max(),
               rx.mesh.length(),
               rx.mesh.closed()
            );
            RoadSidewalks.Config ring = new RoadSidewalks.Config(true, c.side(), 1, c.material(), false);

            for (RoadSidewalks.Cell cell : RoadSidewalks.cells(expanded, ring)) {
               BlockPos p = new BlockPos(cell.x(), cell.y(), cell.z());
               long key = p.asLong();
               if (level.hasChunkAt(p)) {
                  BlockState current = RoadPlanInputs.state(level, p);
                  BlockState terrain = !RoadBlocks.isCollider(current) && !previous.containsKey(key) ? current : originals.getOrDefault(key, current);
                  if (!terrain.hasBlockEntity()
                     && terrain.getFluidState().isEmpty()
                     && Block.isShapeFullBlock(terrain.getCollisionShape(level, p))
                     && paving.near(new RoadGeometry.V((double)p.getX() + 0.5, (double)(p.getY() + 1), (double)p.getZ() + 0.5), (double)(c.width() + 4))) {
                     boolean blocked = false;

                     for (RoadIndex.Built other : byChunk.getOrDefault(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4), List.of())) {
                        if (other.columns.containsKey(BlockPos.asLong(p.getX(), 0, p.getZ()))) {
                           RoadIndex.Built.Column col = other.columns.get(BlockPos.asLong(p.getX(), 0, p.getZ()));
                           if ((double)(p.getY() + 1) > col.minTop() + 1.0E-6 && (double)p.getY() < col.maxTop() + 4.25) {
                              blocked = true;
                              break;
                           }
                        }
                     }

                     if (!blocked) {
                        put(result, materials, cell, ring);
                     }
                  }
               }
            }
         }
      }

      return result;
   }

   private static boolean ownsBorder(RoadIndex.Built road, BlockPos p) {
      RoadSidewalks.Config c = road.record.settings().options().sidewalk();
      if (!c.enabled()) {
         return false;
      } else {
         RoadGeometry.V point = new RoadGeometry.V((double)p.getX() + 0.5, (double)(p.getY() + 1), (double)p.getZ() + 0.5);
         RoadQueries.Projection q = RoadQueries.horizontal(road.mesh, point);
         int side = q.lateral() < 0.0 ? -1 : 1;
         if ((c.side() != RoadSidewalks.Side.LEFT || side <= 0) && (c.side() != RoadSidewalks.Side.RIGHT || side >= 0)) {
            double edge = q.sample().halfWidth() + RoadSidewalks.walkWidth(road.mesh, c, q.sample(), side);
            return Math.abs(point.y() - q.sample().center().y()) < 1.1 && Math.abs(Math.abs(q.lateral()) - edge) < 1.6 && q.horizontalDistance() < edge + 1.6;
         } else {
            return false;
         }
      }
   }

   private static void put(Map<Long, BlockState> result, Map<String, BlockState> materials, RoadSidewalks.Cell cell, RoadSidewalks.Config config) {
      if (!config.smooth()) {
         BlockState state = materials.computeIfAbsent(config.material(), name -> {
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
               Block block = (Block)BuiltInRegistries.BLOCK.get(id);
               BlockState value = block.defaultBlockState();
               if (!TunnelAir.isAirSpace(value) && !(block instanceof RoadBlocks.Road) && block != SplineRoads.NODE.get() && !value.hasBlockEntity()) {
                  return value;
               } else {
                  throw new IllegalArgumentException("请选择不含方块实体的普通原版或模组方块作为人行道材质");
               }
            } else {
               throw new IllegalArgumentException("人行道材质不存在：" + name);
            }
         });
         result.putIfAbsent(new BlockPos(cell.x(), cell.y(), cell.z()).asLong(), state);
      }
   }

   private SmartSidewalks() {
   }
}
