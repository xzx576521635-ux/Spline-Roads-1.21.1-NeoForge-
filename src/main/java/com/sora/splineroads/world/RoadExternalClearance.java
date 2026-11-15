package com.sora.splineroads.world;

import com.sora.splineroads.core.RailbedFurniture;
import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RailbedStructures;
import com.sora.splineroads.core.RoadSolidOverlap;
import com.sora.splineroads.core.RoadStructures;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

final class RoadExternalClearance implements AutoCloseable {
   private static final ThreadLocal<RoadExternalClearance> CURRENT = new ThreadLocal<>();
   private final RoadExternalClearance previous = CURRENT.get();
   private final RoadSolidOverlap.Index clearance;
   private final RoadSolidOverlap.Index poles;

   private RoadExternalClearance(List<RailbedPath.Path> selected, List<RailbedPath.Path> surrounding) {
      HashSet<String> ids = new HashSet<>();
      selected.forEach(p -> p.segments().forEach(s -> ids.add(s.id())));
      List<RailbedPath.Path> other = surrounding.stream().filter(p -> p.segments().stream().noneMatch(s -> ids.contains(s.id()))).toList();
      this.clearance = RailbedStructures.clearance(other);
      this.poles = RailbedStructures.catenaryClearance(other);
      CURRENT.set(this);
   }

   static RoadExternalClearance open(List<RailbedPath.Path> selected, List<RailbedPath.Path> surrounding) {
      return new RoadExternalClearance(selected, surrounding);
   }

   static boolean blocked(RoadStructures.Part part) {
      RoadExternalClearance scope = CURRENT.get();
      return scope != null && !RailbedFurniture.visualOnly(part) && (part.model().startsWith("sr:catenary") ? scope.poles : scope.clearance).intersects(part);
   }

   static void validate(RoadRecord road) {
      if (CURRENT.get() != null) {
         ArrayList<RoadStructures.Part> parts = new ArrayList<>(RoadTravelValidation.deckParts(road.mesh()));
         parts.addAll(road.structures());

         for (RoadStructures.Part part : parts) {
            if (blocked(part)) {
               throw new IllegalArgumentException(
                  String.format(Locale.ROOT, "构件与未纳入路基的轨道相交：%.2f, %.2f, %.2f；请把相邻轨道加入同一路基或调整设置", part.a().x(), part.a().y(), part.a().z())
               );
            }
         }
      }
   }

   @Override
   public void close() {
      if (this.previous == null) {
         CURRENT.remove();
      } else {
         CURRENT.set(this.previous);
      }
   }
}
