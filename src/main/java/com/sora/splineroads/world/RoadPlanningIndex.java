package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadBoundsIndex;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadsideBypass;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

final class RoadPlanningIndex {
   private final List<RoadIndex.Built> roads;
   private final RoadBoundsIndex bounds;
   private Map<UUID, Set<Integer>> optionalAutomaticAnchors = Map.of();
   private Map<UUID, Map<Integer, RoadGantryRetention.Snapshot>> preservedGantries = Map.of();
   private Map<UUID, RoadPlanningIndex.Previous> dependencies = Map.of();
   private Set<UUID> newRoads = Set.of();
   private Set<UUID> directlyEditedRoads = Set.of();
   private Map<UUID, List<RoadPlanningIndex.CycleNeighbor>> cycleNeighbors = Map.of();
   private final Map<UUID, RoadCycleChain> cycleChains = new HashMap<>();
   private final Map<UUID, List<RoadsideBypass.Span>> cyclePlans = new HashMap<>();

   RoadPlanningIndex(List<RoadIndex.Built> roads) {
      this(roads, 1);
   }

   RoadPlanningIndex(List<RoadIndex.Built> roads, int plannedRoads) {
      this.roads = new ArrayList<>(roads);
      this.bounds = plannedRoads >= 256 && roads.size() > 64 ? new RoadBoundsIndex(roads.stream().map(r -> box(r.mesh)).toList()) : null;
   }

   boolean spatial() {
      return this.bounds != null;
   }

   void resetCyclePlans() {
      this.cycleChains.clear();
      this.cyclePlans.clear();
   }

   RoadCycleChain cycleChain(UUID road) {
      if (this.cycleChains.containsKey(road)) {
         return this.cycleChains.get(road);
      } else {
         LinkedHashMap<UUID, RoadRecord> records = new LinkedHashMap<>();

         for (RoadIndex.Built r : this.roads) {
            records.put(r.record.id(), r.record);
         }

         RoadCycleChain chain = RoadCycleChain.of(records, road);
         if (chain != null) {
            for (RoadCycleChain.Leg leg : chain.legs()) {
               this.cycleChains.put(leg.road().id(), chain);
            }
         }

         return chain;
      }
   }

   List<RoadsideBypass.Span> cyclePlan(RoadCycleChain chain, Supplier<List<RoadsideBypass.Span>> build) {
      return this.cyclePlans.computeIfAbsent(chain.legs().get(0).road().id(), key -> List.copyOf(build.get()));
   }

   void editHistory(List<RoadIndex.Built> candidates, Map<UUID, RoadIndex.Built> saved, Set<UUID> explicit) {
      HashMap<UUID, RoadPlanningIndex.Previous> previous = new HashMap<>();
      HashMap<UUID, List<RoadPlanningIndex.CycleNeighbor>> previousNeighbors = new HashMap<>();
      HashMap<UUID, RoadPlanningIndex.CycleNeighbor> frozenNeighbors = new HashMap<>();
      HashSet<UUID> added = new HashSet<>();
      HashSet<UUID> direct = new HashSet<>();

      for (RoadIndex.Built candidate : candidates) {
         RoadPlanningBudget.check();
         UUID id = candidate.record.id();
         RoadIndex.Built before = saved.get(id);
         if (before == null) {
            added.add(id);
         }

         if (explicit.contains(id)) {
            direct.add(id);
         }

         if (before != null && !explicit.contains(id)) {
            previous.put(id, new RoadPlanningIndex.Previous(before.record, before.mesh));
            if (!before.record.settings().options().streetscape().crossings().isEmpty()) {
               double margin = (double)(
                  3 + (before.record.settings().options().sidewalk().enabled() ? before.record.settings().options().sidewalk().width() : 0)
               );
               ArrayList<RoadPlanningIndex.CycleNeighbor> neighbors = new ArrayList<>();

               for (RoadIndex.Built neighbor : saved.values()) {
                  RoadPlanningBudget.check();
                  RoadGeometry.Mesh mesh = neighbor.mesh;
                  UUID neighborId = neighbor.record.id();
                  if (!neighborId.equals(id)
                     && RoadIndex.overlapXZ(mesh, before.mesh, margin)
                     && (mesh.settings().style().ramp() || mesh.settings().options().lanePoints().link() != null)) {
                     neighbors.add(frozenNeighbors.computeIfAbsent(neighborId, key -> new RoadPlanningIndex.CycleNeighbor(mesh, neighbor.record.structures())));
                  }
               }

               previousNeighbors.put(id, List.copyOf(neighbors));
            }
         }
      }

      this.dependencies = Map.copyOf(previous);
      this.cycleNeighbors = Map.copyOf(previousNeighbors);
      this.newRoads = Set.copyOf(added);
      this.directlyEditedRoads = Set.copyOf(direct);
   }

   RoadRecord unchangedDependency(RoadIndex.Built candidate) {
      RoadPlanningIndex.Previous previous = this.dependencies.get(candidate.record.id());
      if (previous == null) {
         return null;
      } else if (previous.record().structures(List.of()).equals(candidate.record.structures(List.of())) && previous.mesh().equals(candidate.mesh)) {
         return previous.record();
      } else {
         List<RoadsideBypass.Span> spans = previous.record().settings().options().streetscape().crossings();
         return RoadsideBypass.sameSavedCrossingSurface(previous.mesh(), candidate.mesh, spans) ? previous.record() : null;
      }
   }

   String crossingHistoryReason(RoadIndex.Built candidate) {
      UUID id = candidate.record.id();
      if (this.newRoads.contains(id)) {
         return "new-road";
      } else if (this.directlyEditedRoads.contains(id)) {
         return "directly-edited";
      } else if (!this.dependencies.containsKey(id)) {
         return "no-history";
      } else {
         return this.unchangedDependency(candidate) == null ? "surface-changed" : "unchanged-surface";
      }
   }

   List<RoadPlanningIndex.CycleNeighbor> previousCycleNeighbors(RoadIndex.Built candidate) {
      return this.unchangedDependency(candidate) == null ? null : this.cycleNeighbors.get(candidate.record.id());
   }

   private static RoadGeometry.Mesh immutableMesh(RoadGeometry.Mesh mesh) {
      return mesh == null
         ? null
         : new RoadGeometry.Mesh(
            List.copyOf(mesh.samples()),
            mesh.settings(),
            mesh.min(),
            mesh.max(),
            mesh.length(),
            mesh.closed(),
            mesh.controlPoint(),
            List.copyOf(mesh.controls()),
            immutableMesh(mesh.reference()),
            mesh.slicing()
         );
   }

   void optionalAutomaticAnchors(Map<UUID, Set<Integer>> values) {
      HashMap<UUID, Set<Integer>> copy = new HashMap<>();
      values.forEach((id, slots) -> copy.put(id, Set.copyOf(slots)));
      this.optionalAutomaticAnchors = Map.copyOf(copy);
   }

   boolean requiredGantry(UUID road, int slot) {
      return !this.optionalAutomaticAnchors.getOrDefault(road, Set.of()).contains(slot);
   }

   void preservedGantries(Map<UUID, Map<Integer, RoadGantryRetention.Snapshot>> values) {
      this.preservedGantries = values;
   }

   List<RoadStructures.Part> preservedGantry(UUID road, RoadGantry.Station station) {
      RoadGantryRetention.Snapshot saved = this.preservedGantries.getOrDefault(road, Map.of()).get(station.slot());
      return saved == null ? null : saved.at(station);
   }

   private static RoadBoundsIndex.Bounds box(RoadGeometry.Mesh m) {
      return new RoadBoundsIndex.Bounds(m.min().x(), m.min().z(), m.max().x(), m.max().z());
   }

   void replace(int slot, RoadIndex.Built next) {
      if (this.bounds != null) {
         this.bounds.replace(slot, box(next.mesh));
      }

      this.roads.set(slot, next);
   }

   List<RoadIndex.Built> near(RoadGeometry.Mesh mesh, double margin, UUID exclude) {
      if (this.bounds == null) {
         return this.roads.stream().filter(rx -> !rx.record.id().equals(exclude) && RoadIndex.overlapXZ(rx.mesh, mesh, margin)).toList();
      } else {
         ArrayList<RoadIndex.Built> result = new ArrayList<>();

         for (int slot : this.bounds.query(box(mesh), margin)) {
            RoadIndex.Built r = this.roads.get(slot);
            if (!r.record.id().equals(exclude)) {
               result.add(r);
            }
         }

         return result;
      }
   }

   List<RoadIndex.Built> nearAny(List<RoadGeometry.Mesh> meshes, double margin, UUID exclude) {
      if (this.bounds == null) {
         return this.roads
            .stream()
            .filter(rx -> !rx.record.id().equals(exclude) && meshes.stream().anyMatch(m -> RoadIndex.overlapXZ(rx.mesh, m, margin)))
            .toList();
      } else {
         TreeSet<Integer> slots = new TreeSet<>();

         for (RoadGeometry.Mesh mesh : meshes) {
            slots.addAll(this.bounds.query(box(mesh), margin));
         }

         ArrayList<RoadIndex.Built> result = new ArrayList<>();

         for (int slot : slots) {
            RoadIndex.Built r = this.roads.get(slot);
            if (!r.record.id().equals(exclude)) {
               result.add(r);
            }
         }

         return result;
      }
   }

   static record CycleNeighbor(RoadGeometry.Mesh mesh, List<RoadStructures.Part> structures) {
      CycleNeighbor(RoadGeometry.Mesh mesh, List<RoadStructures.Part> structures) {
         mesh = RoadPlanningIndex.immutableMesh(mesh);
         structures = List.copyOf(structures);
         this.mesh = mesh;
         this.structures = structures;
      }
   }

   private static record Previous(RoadRecord record, RoadGeometry.Mesh mesh) {
   }
}
