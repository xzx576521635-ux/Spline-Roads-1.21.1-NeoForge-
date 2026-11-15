package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RoadNoses {
   public static Map<Integer, List<RoadStructures.Part>> connectors(List<RoadGeometry.Mesh> meshes, List<List<RoadStructures.Part>> parts) {
      Map<RoadNoses.Key, List<RoadNoses.End>> ends = new HashMap<>();

      for (int i = 0; i < meshes.size(); i++) {
         if (meshes.get(i).settings().style().ramp()) {
            for (RoadStructures.Part p : parts.get(i)) {
               if (!p.pier()
                  && (p.material() == RoadStructures.Material.STEEL || p.material() == RoadStructures.Material.CONCRETE)
                  && !(p.width() > 0.5)
                  && !(p.b().sub(p.a()).horizontalLength() < 0.05)) {
                  RoadGeometry.V dir = p.b().sub(p.a()).horizontalUnit();
                  ends.computeIfAbsent(new RoadNoses.Key(p.a(), p), ignored -> new ArrayList<>()).add(new RoadNoses.End(i, p.a(), dir.mul(-1.0), p));
                  ends.computeIfAbsent(new RoadNoses.Key(p.b(), p), ignored -> new ArrayList<>()).add(new RoadNoses.End(i, p.b(), dir, p));
               }
            }
         }
      }

      List<RoadNoses.End> dangling = ends.values()
         .stream()
         .filter(e -> e.size() == 1)
         .map(e -> e.get(0))
         .sorted(
            Comparator.comparingInt(RoadNoses.End::road)
               .thenComparingDouble(e -> e.at.x())
               .thenComparingDouble(e -> e.at.z())
               .thenComparingDouble(e -> e.at.y())
         )
         .toList();
      Set<Integer> used = new HashSet<>();
      Map<Integer, List<RoadStructures.Part>> result = new HashMap<>();

      for (int ix = 0; ix < dangling.size(); ix++) {
         if (!used.contains(ix)) {
            RoadNoses.End a = dangling.get(ix);
            int best = -1;
            double nearest = 6.0;

            for (int j = ix + 1; j < dangling.size(); j++) {
               RoadNoses.End b = dangling.get(j);
               if (!used.contains(j)
                  && a.road != b.road
                  && a.part.width() == b.part.width()
                  && a.part.height() == b.part.height()
                  && a.part.material() == b.part.material()
                  && !(Math.abs(a.at.y() - b.at.y()) > 0.12)) {
                  double distance = a.at.sub(b.at).horizontalLength();
                  if (!(distance < 0.02) && !(distance >= nearest)) {
                     RoadGeometry.V direction = b.at.sub(a.at).horizontalUnit();
                     boolean facing = a.outward.dot(direction) > 0.92 && b.outward.dot(direction) < -0.92;
                     boolean nose = distance <= 1.25 && a.outward.dot(b.outward) >= 0.6 && shared(meshes.get(a.road), meshes.get(b.road));
                     if (facing || nose) {
                        boolean lane = false;

                        for (double t = 0.1; t < 1.0; t += 0.1) {
                           RoadGeometry.V middle = a.at.add(b.at.sub(a.at).mul(t));

                           for (int index = 0; index < meshes.size(); index++) {
                              RoadQueries.Projection q = RoadQueries.horizontal(meshes.get(index), middle);
                              if (!(Math.abs(q.sample().center().y() - middle.y()) > 1.6) && q.horizontalDistance() < q.sample().halfWidth() - 0.5) {
                                 lane = true;
                              }
                           }
                        }

                        if (!lane) {
                           nearest = distance;
                           best = j;
                        }
                     }
                  }
               }
            }

            if (best >= 0) {
               RoadNoses.End b = dangling.get(best);
               used.add(ix);
               used.add(best);
               result.computeIfAbsent(a.road, ignored -> new ArrayList<>())
                  .add(new RoadStructures.Part(a.at, b.at, a.part.width(), a.part.height(), false, a.part.material()));
            }
         }
      }

      return result;
   }

   private static boolean shared(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      return a.first().center().distance(b.first().center()) < 0.01 || a.last().center().distance(b.last().center()) < 0.01;
   }

   private RoadNoses() {
   }

   private static record End(int road, RoadGeometry.V at, RoadGeometry.V outward, RoadStructures.Part part) {
   }

   private static record Key(long x, long y, long z, double width, double height, RoadStructures.Material material) {
      Key(RoadGeometry.V p, RoadStructures.Part part) {
         this(Math.round(p.x() * 100000.0), Math.round(p.y() * 100000.0), Math.round(p.z() * 100000.0), part.width(), part.height(), part.material());
      }
   }
}
