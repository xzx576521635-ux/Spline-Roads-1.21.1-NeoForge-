package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadAutoTunnels;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadTerrainSurface;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import java.util.function.LongFunction;
import net.minecraft.world.level.ChunkPos;

public final class RoadTerrainCover {
   private final LongFunction<List<RoadIndex.Built>> lookup;
   private final Map<Long, List<RoadIndex.Built>> chunks = new HashMap<>();
   private final Map<RoadIndex.Built, List<RoadAutoTunnels.RoofRegion>> profiles = new IdentityHashMap<>();

   public RoadTerrainCover(LongFunction<List<RoadIndex.Built>> lookup) {
      this.lookup = lookup;
   }

   public double surface(double x, double z, double roadY, DoubleUnaryOperator original) {
      double result = RoadTerrainSurface.connected(roadY, original);
      long chunk = ChunkPos.asLong((int)Math.floor(x) >> 4, (int)Math.floor(z) >> 4);

      for (RoadIndex.Built road : this.chunks
         .computeIfAbsent(
            chunk,
            c -> this.lookup
                  .apply(c)
                  .stream()
                  .filter(
                     rx -> rx.mesh.settings().structure() == RoadGeometry.Structure.TUNNEL
                           || !this.profiles.computeIfAbsent(rx, v -> RoadAutoTunnels.roofRegions(v.record.structures())).isEmpty()
                  )
                  .toList()
         )) {
         RoadQueries.Projection q = RoadQueries.horizontal(road.mesh, new RoadGeometry.V(x, roadY, z));
         RoadGeometry.Sample at = q.sample();
         if (!(q.horizontalDistance() > at.halfWidth() + 1.55) && !(roadY < at.center().y() - 0.25)) {
            double ceiling = Double.NaN;
            if (road.mesh.settings().structure() == RoadGeometry.Structure.TUNNEL) {
               ceiling = RoadInfrastructure.ceiling(road.mesh.settings().options().infrastructure(), at.halfWidth(), 0.0);
            } else {
               for (RoadAutoTunnels.RoofRegion r : this.profiles.computeIfAbsent(road, v -> RoadAutoTunnels.roofRegions(v.record.structures()))) {
                  if (at.distance() >= r.from() - 1.0E-7 && at.distance() <= r.to() + 1.0E-7) {
                     ceiling = r.first() + (r.last() - r.first()) * (at.distance() - r.from()) / (r.to() - r.from());
                     break;
                  }
               }
            }

            if (Double.isFinite(ceiling) && !(roadY > at.center().y() + ceiling)) {
               double probe = Math.ceil(at.center().y() + ceiling + 1.05) + 1.0E-4;
               double solid = original.applyAsDouble(probe);
               if (Double.isFinite(solid) && !(solid <= probe)) {
                  double top = RoadTerrainSurface.connected(probe, original);
                  if (Double.isFinite(top)) {
                     result = Double.isFinite(result) ? Math.max(result, top) : top;
                  }
               }
            }
         }
      }

      return result;
   }
}
