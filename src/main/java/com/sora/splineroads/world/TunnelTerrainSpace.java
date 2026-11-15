package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadRaster;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadsideBypass;
import java.util.List;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;

final class TunnelTerrainSpace {
   static boolean safe(BlockPos p, List<RoadIndex.Built> nearby) {
      boolean shell = false;

      for (RoadIndex.Built road : nearby) {
         shell |= road.shellAt(p);
         if (road.hasTunnel()) {
            if (road.tunnelAt(p) || road.clearanceAt(p)) {
               return false;
            }
         } else if (road.clearanceAt(p)) {
            return false;
         }
      }

      return shell;
   }

   static boolean exteriorInfill(BlockPos p, List<RoadIndex.Built> nearby) {
      RoadStructures.Part cube = new RoadStructures.Part(
         new RoadGeometry.V((double)p.getX() + 0.5, (double)p.getY(), (double)p.getZ()),
         new RoadGeometry.V((double)p.getX() + 0.5, (double)p.getY(), (double)(p.getZ() + 1)),
         1.0,
         1.0,
         false,
         RoadStructures.Material.CONCRETE
      );
      boolean supported = false;

      for (RoadIndex.Built road : nearby) {
         if (road.hasTunnel() && road.tunnelAt(p)) {
            return false;
         }

         double air = Math.max(4.25, RoadInfrastructure.clearance(road.record.settings()));
         if (RoadClearance.structureInvades(cube, road.mesh, air) && !RoadClearance.belowSurface(cube, road.mesh, 1.0E-7)) {
            return false;
         }

         RoadRaster.Cell cell = new RoadRaster.Cell(p.getX(), p.getY(), p.getZ());

         for (Entry<RoadRaster.Cell, List<RoadRaster.Box>> e : road.prepareCollision().structureColumn(p.getX(), p.getZ(), RoadsideBypass::walkway).entrySet()) {
            for (RoadRaster.Box box : e.getValue()) {
               double top = (double)e.getKey().y() + box.y1();
               if ((double)(p.getY() + 1) > top + 1.0E-7 && (double)p.getY() < top + 2.1) {
                  return false;
               }
            }
         }

         if (road.prepareCollision()
            .structureAt(
               cell,
               q -> q.material() == RoadStructures.Material.TUNNEL
                     || q.material() == RoadStructures.Material.CONCRETE
                     || q.material() == RoadStructures.Material.DEFAULT
            )) {
            supported = true;
         }

         if (road.column(p) != null && (double)(p.getY() + 1) <= road.column(p).minTop() + 1.0E-7) {
            supported = true;
         }
      }

      return supported;
   }

   private TunnelTerrainSpace() {
   }
}
