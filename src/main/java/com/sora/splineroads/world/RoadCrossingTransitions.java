package com.sora.splineroads.world;

import com.sora.splineroads.core.RailwayCrossings;
import com.sora.splineroads.core.RoadCrossingGrades;
import com.sora.splineroads.core.RoadGeometry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

public final class RoadCrossingTransitions {
   public static RoadRecord fit(RoadRecord proposed, Collection<RoadRecord> roads) {
      if (!AutoCrossings.eligible(proposed)) {
         return proposed;
      } else {
         RoadGeometry.Mesh mesh = proposed.caps(0).rawMesh();
         List<RoadGeometry.Mesh> others = roads.stream()
            .filter(r -> !r.id().equals(proposed.id()) && (AutoCrossings.eligible(r) || r.railway()))
            .map(RoadRecord::mesh)
            .toList();
         RoadGeometry.Mesh fitted = RoadCrossingGrades.fit(mesh, others.stream().filter(m -> m.settings().style() != RoadGeometry.Style.RAILBED).toList());
         RoadRecord result = fitted == mesh ? proposed : proposed.alignment(proposed.assembly(), fitted);
         List<RoadGeometry.Mesh> rails = others.stream().filter(m -> m.settings().style() == RoadGeometry.Style.RAILBED).toList();
         return result.withLevelCrossings(RailwayCrossings.surfaces(result.withLevelCrossings(List.of()).mesh(), rails));
      }
   }

   public static Collection<RoadRecord> candidates(RoadData data) {
      LinkedHashMap<UUID, RoadRecord> roads = new LinkedHashMap<>(AutoJunctions.logical(data));
      data.index.roads.values().stream().map(b -> b.record).filter(RoadRecord::railway).forEach(r -> roads.put(r.id(), r));
      return roads.values();
   }

   public static RoadRecord preview(RoadRecord proposed, CompoundTag command) {
      return fit(proposed, AutoCrossings.candidates(command));
   }

   private RoadCrossingTransitions() {
   }
}
