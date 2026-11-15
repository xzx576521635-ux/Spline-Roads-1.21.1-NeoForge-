package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadStreetscape;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class LampSpacing {
   private static List<LampSpacing.Lamp> lamps(RoadIndex.Built road, boolean existing) {
      return road.record
         .structures()
         .stream()
         .map(RoadStreetscape::lampFoot)
         .filter(Objects::nonNull)
         .distinct()
         .map(
            p -> new LampSpacing.Lamp(
                  road.record.id(),
                  p,
                  RoadQueries.horizontal(road.mesh, p).sample().left(),
                  Math.min(12.0, road.mesh.settings().options().streetscape().lampSpacing() * 0.45),
                  existing,
                  road.record.junction() != null
               )
         )
         .toList();
   }

   static void reconcile(RoadData data, List<RoadIndex.Built> built, Set<UUID> removed) {
      HashMap<Long, List<LampSpacing.Lamp>> kept = new HashMap<>();

      for (RoadIndex.Built r : data.index.roads.values()) {
         if (!removed.contains(r.record.id())) {
            for (LampSpacing.Lamp lamp : lamps(r, true)) {
               add(kept, lamp);
            }
         }
      }

      ArrayList<LampSpacing.Lamp> candidates = new ArrayList<>();

      for (RoadIndex.Built rx : built) {
         candidates.addAll(lamps(rx, false));
      }

      candidates.sort(
         Comparator.comparing(LampSpacing.Lamp::junction)
            .thenComparing(LampSpacing.Lamp::road)
            .thenComparingDouble(l -> l.foot().x())
            .thenComparingDouble(l -> l.foot().z())
            .thenComparingDouble(l -> l.foot().y())
      );
      HashMap<UUID, Set<RoadGeometry.V>> hidden = new HashMap<>();

      for (LampSpacing.Lamp lamp : candidates) {
         boolean close = false;
         int x = cell(lamp.foot().x());
         int z = cell(lamp.foot().z());

         for (int dx = -1; dx <= 1 && !close; dx++) {
            for (int dz = -1; dz <= 1 && !close; dz++) {
               for (LampSpacing.Lamp other : kept.getOrDefault(key(x + dx, z + dz), List.of())) {
                  if (close(lamp, other)) {
                     close = true;
                     break;
                  }
               }
            }
         }

         if (close) {
            hidden.computeIfAbsent(lamp.road(), k -> new HashSet<>()).add(lamp.foot());
         } else {
            add(kept, lamp);
         }
      }

      for (int i = 0; i < built.size(); i++) {
         RoadIndex.Built road = built.get(i);
         Set<RoadGeometry.V> feet = hidden.get(road.record.id());
         if (feet != null) {
            built.set(i, road.structures(road.record.structures().stream().filter(p -> !feet.contains(RoadStreetscape.lampFoot(p))).toList()));
         }
      }
   }

   private static int cell(double n) {
      return (int)Math.floor(n / 12.0);
   }

   private static long key(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   private static void add(Map<Long, List<LampSpacing.Lamp>> cells, LampSpacing.Lamp lamp) {
      cells.computeIfAbsent(key(cell(lamp.foot().x()), cell(lamp.foot().z())), k -> new ArrayList<>()).add(lamp);
   }

   private static boolean close(LampSpacing.Lamp a, LampSpacing.Lamp b) {
      RoadGeometry.V delta = a.foot().sub(b.foot());
      double distance = delta.horizontalLength();
      return !(Math.abs(delta.y()) > 2.0) && !(distance >= Math.min(a.gap(), b.gap()))
         ? distance < 1.0 || Math.abs(a.left().dot(b.left())) > 0.94 && Math.abs(delta.dot(a.left())) < 2.0 && Math.abs(delta.dot(b.left())) < 2.0
         : false;
   }

   private LampSpacing() {
   }

   private static record Lamp(UUID road, RoadGeometry.V foot, RoadGeometry.V left, double gap, boolean existing, boolean junction) {
   }
}
