package com.sora.splineroads.world;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadStreetscape;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

final class GroundTerrainJoin {
   static final double MIN_CAP = 0.25;

   static boolean usable(double cap) {
      return cap >= 0.25 && cap < 1.0;
   }

   static double cap(BlockPos p, BlockState original, List<RoadIndex.Built> nearby) {
      if (TunnelAir.isAirSpace(original) || original.hasBlockEntity() || !original.getFluidState().isEmpty() || !original.canOcclude()) {
         return 1.0;
      } else if (RailbedTerrainEdge.present(p, original, nearby)) {
         return 1.0;
      } else {
         boolean edge = false;
         double cap = 1.0;
         RoadGeometry.V center = new RoadGeometry.V((double)p.getX() + 0.5, (double)p.getY(), (double)p.getZ() + 0.5);

         for (RoadIndex.Built road : nearby) {
            if (road.tunnelAt(p) || road.cycleBodyAt(p)) {
               return 1.0;
            }

            RoadIndex.Built.Column c = road.column(p);
            if (c != null && (double)p.getY() >= c.maxTop() && road.clearanceAt(p)) {
               return 1.0;
            }

            if (c != null && !((double)p.getY() > c.maxTop()) && !((double)(p.getY() + 1) < c.minTop() - 1.0E-7)) {
               cap = Math.min(cap, c.minTop() - (double)p.getY() - 0.002);
               RoadQueries.Projection q = RoadQueries.horizontal(road.mesh, center);
               RoadGeometry.Settings settings = road.record.settings();
               int side = q.lateral() < 0.0 ? -1 : 1;
               if (settings.structure() != RoadGeometry.Structure.BRIDGE
                  && settings.structure() != RoadGeometry.Structure.TUNNEL
                  && !RoadStreetscape.raised(road.mesh, q.sample(), side)
                  && !RoadStreetscape.cut(road.mesh, q.sample(), side)) {
                  RoadSidewalks.Config walk = settings.options().sidewalk();
                  JunctionPlanner.Ref ref = road.record.junction();
                  if (ref != null && ref.get().arm() >= 0) {
                     walk = ref.spec().arms().get(ref.get().arm()).external().options().sidewalk();
                  }

                  if (!walk.enabled()
                     || walk.side() != RoadSidewalks.Side.BOTH
                        && (walk.side() != RoadSidewalks.Side.LEFT || !(q.lateral() < 0.0))
                        && (walk.side() != RoadSidewalks.Side.RIGHT || !(q.lateral() > 0.0))) {
                     boolean end = !road.mesh.closed() && (q.sample().distance() < 1.0 || q.sample().distance() > road.mesh.length() - 1.0);
                     if (end || !(Math.abs(q.lateral()) < q.sample().halfWidth() - 0.72)) {
                        for (int x = 0; x <= 1; x++) {
                           for (int z = 0; z <= 1; z++) {
                              RoadGeometry.V point = new RoadGeometry.V((double)(p.getX() + x), c.minTop(), (double)(p.getZ() + z));
                              RoadQueries.Projection corner = RoadQueries.horizontal(road.mesh, point);
                              double along = point.sub(corner.sample().center()).dot(corner.tangent().horizontalUnit());
                              if (corner.horizontalDistance() > corner.sample().halfWidth() + 1.0E-7
                                 || !road.mesh.closed()
                                    && (
                                       corner.sample().distance() < 1.0E-6 && along < -1.0E-7
                                          || corner.sample().distance() > road.mesh.length() - 1.0E-6 && along > 1.0E-7
                                    )) {
                                 edge = true;
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         if (edge && cap >= 0.9979999 && RoadBlocks.Fill.of(original) != RoadBlocks.Fill.NONE) {
            return 1.0;
         } else {
            return edge && cap < 0.999 ? (cap >= 0.25 ? cap : 0.0) : 1.0;
         }
      }
   }

   private GroundTerrainJoin() {
   }
}
