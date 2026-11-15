package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadFurniture;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadQueries;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;

public final class FurnitureSpacing {
   public static RoadFurniture.Phase resolve(RoadRecord road, RoadGeometry.Mesh mesh, Collection<RoadIndex.Built> references) {
      if (road.furniturePhase() != null) {
         return road.furniturePhase();
      } else {
         Optional<RoadIndex.Built> old = references.stream().filter(r -> r.record.id().equals(road.id())).findFirst();
         if (old.isPresent() && old.get().record.furniturePhase() != null) {
            return old.get().record.furniturePhase();
         } else {
            double start = RoadQueries.horizontal(mesh, road.start().position()).sample().distance();

            for (RoadIndex.Built other : references.stream().sorted(Comparator.comparing(r -> r.record.id())).toList()) {
               if (!other.record.id().equals(road.id())
                  && other.record.railway() == road.railway()
                  && !other.mesh.closed()
                  && other.record.settings().style().ramp() == road.settings().style().ramp()) {
                  for (boolean first : new boolean[]{true, false}) {
                     BlockPos node = first ? road.a() : road.b();
                     boolean ofirst = other.record.a().equals(node);
                     if (ofirst || other.record.b().equals(node)) {
                        RoadGeometry.Sample at = RoadQueries.horizontal(mesh, (first ? road.start() : road.end()).position()).sample();
                        RoadGeometry.Sample ot = RoadQueries.horizontal(other.mesh, (ofirst ? other.record.start() : other.record.end()).position()).sample();
                        if (!(at.center().distance(ot.center()) > 1.05) && !(Math.abs(at.left().dot(ot.left())) < 0.94)) {
                           RoadFurniture.Phase phase = other.record.furniturePhase() == null ? RoadFurniture.Phase.DEFAULT : other.record.furniturePhase();
                           double os = RoadQueries.horizontal(other.mesh, other.record.start().position()).sample().distance();
                           int direction = phase.direction() * (at.left().dot(ot.left()) > 0.0 ? 1 : -1);
                           return new RoadFurniture.Phase(
                              phase.origin() + (double)phase.direction() * (ot.distance() - os) - (double)direction * (at.distance() - start), direction
                           );
                        }
                     }
                  }
               }
            }

            return RoadFurniture.Phase.DEFAULT;
         }
      }
   }

   public static RoadFurniture.Phase onMesh(RoadRecord road, RoadGeometry.Mesh mesh) {
      RoadFurniture.Phase phase = road.furniturePhase() == null ? RoadFurniture.Phase.DEFAULT : road.furniturePhase();
      double start = RoadQueries.horizontal(mesh, road.start().position()).sample().distance();
      return phase.shift(-start);
   }

   private FurnitureSpacing() {
   }
}
