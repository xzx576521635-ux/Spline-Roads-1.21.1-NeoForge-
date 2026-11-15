package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadRaster;
import com.sora.splineroads.core.TerrainEdgeGeometry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RailbedTerrainEdge {
   private static final RailbedTerrainEdge.Data EMPTY = new RailbedTerrainEdge.Data(List.of(), List.of());
   private static final Map<RailbedTerrainEdge.Key, RailbedTerrainEdge.Data> CACHE = new LinkedHashMap<RailbedTerrainEdge.Key, RailbedTerrainEdge.Data>(
      256, 0.75F, true
   ) {
      @Override
      protected boolean removeEldestEntry(Entry<RailbedTerrainEdge.Key, RailbedTerrainEdge.Data> e) {
         return this.size() > 4096;
      }
   };

   public static boolean present(BlockPos p, BlockState material, List<RoadIndex.Built> nearby) {
      return !data(p, material, nearby).polygons().isEmpty();
   }

   public static List<RoadRaster.Box> boxes(BlockPos p, BlockState material, List<RoadIndex.Built> nearby) {
      return data(p, material, nearby).boxes();
   }

   public static RailbedTerrainEdge.Data data(BlockPos p, BlockState material, List<RoadIndex.Built> nearby) {
      if (material.canOcclude() && !material.hasBlockEntity() && material.getFluidState().isEmpty()) {
         boolean edge = false;
         ArrayList<RoadGeometry.Mesh> relevant = new ArrayList<>();

         for (RoadIndex.Built road : nearby) {
            if (road.tunnelAt(p) || road.cycleBodyAt(p)) {
               return EMPTY;
            }

            RoadIndex.Built.Column c = road.column(p);
            if (c != null
               && !((double)(p.getY() + 1) <= c.minTop() + 1.0E-7)
               && !((double)p.getY() >= c.maxTop() + Math.max(4.25, RoadInfrastructure.clearance(road.record.settings())))) {
               relevant.add(road.mesh);
               edge |= road.record.railway();
            }
         }

         if (!edge) {
            return EMPTY;
         } else {
            RailbedTerrainEdge.Key key = new RailbedTerrainEdge.Key(p.immutable(), List.copyOf(relevant));
            synchronized (CACHE) {
               RailbedTerrainEdge.Data found = CACHE.get(key);
               if (found != null) {
                  return found;
               }
            }

            List<List<RoadGeometry.V>> polygons = TerrainEdgeGeometry.retain(new RoadGeometry.V((double)p.getX(), (double)p.getY(), (double)p.getZ()), relevant);
            RailbedTerrainEdge.Data result = polygons.isEmpty() ? EMPTY : new RailbedTerrainEdge.Data(collision(p, nearby), polygons);
            synchronized (CACHE) {
               CACHE.put(key, result);
               return result;
            }
         }
      } else {
         return EMPTY;
      }
   }

   private static List<RoadRaster.Box> collision(BlockPos p, List<RoadIndex.Built> nearby) {
      VoxelShape shape = Shapes.block();

      for (RoadIndex.Built road : nearby) {
         for (List<RoadRaster.Box> cells : road.prepareCollision().deckColumn(p.getX(), p.getZ()).values()) {
            for (RoadRaster.Box b : cells) {
               shape = Shapes.joinUnoptimized(shape, Shapes.box(b.x0(), 0.0, b.z0(), b.x1(), 1.0, b.z1()), BooleanOp.ONLY_FIRST);
            }
         }
      }

      return shape.optimize().toAabbs().stream().map(bx -> new RoadRaster.Box(bx.minX, bx.minY, bx.minZ, bx.maxX, bx.maxY, bx.maxZ)).toList();
   }

   private RailbedTerrainEdge() {
   }

   public static record Data(List<RoadRaster.Box> boxes, List<List<RoadGeometry.V>> polygons) {
   }

   private static record Key(BlockPos pos, List<RoadGeometry.Mesh> meshes) {
   }
}
