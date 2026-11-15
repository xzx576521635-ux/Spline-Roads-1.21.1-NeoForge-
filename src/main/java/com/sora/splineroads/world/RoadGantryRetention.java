package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadBoundsIndex;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadStructures;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class RoadGantryRetention {
   static Map<UUID, Map<Integer, RoadGantryRetention.Snapshot>> capture(
      List<RoadIndex.Built> candidates, Map<UUID, RoadIndex.Built> saved, Set<UUID> explicit, Set<UUID> removed
   ) {
      ArrayList<RoadBoundsIndex.Bounds> changed = new ArrayList<>();
      HashSet<UUID> replaced = new HashSet<>();
      HashSet<UUID> localProof = new HashSet<>();

      for (RoadIndex.Built candidate : candidates) {
         UUID id = candidate.record.id();
         replaced.add(id);
         RoadIndex.Built before = saved.get(id);
         if (!explicit.contains(id)) {
            List<RoadBoundsIndex.Bounds> local = RoadEditScope.endpointRails(before, candidate);
            if (local != null) {
               localProof.add(id);
               changed.addAll(local);
               continue;
            }
         }

         changed.add(RoadEditScope.bounds(candidate.mesh));
         if (!candidate.record.structures().isEmpty()) {
            changed.add(RoadEditScope.bounds(candidate.record.structures()));
         }

         if (before != null) {
            changed.add(RoadEditScope.bounds(before.mesh));
            if (!before.record.structures().isEmpty()) {
               changed.add(RoadEditScope.bounds(before.record.structures()));
            }
         }
      }

      for (UUID idx : removed) {
         if (!replaced.contains(idx)) {
            RoadIndex.Built beforex = saved.get(idx);
            if (beforex != null) {
               changed.add(RoadEditScope.bounds(beforex.mesh));
               if (!beforex.record.structures().isEmpty()) {
                  changed.add(RoadEditScope.bounds(beforex.record.structures()));
               }
            }
         }
      }

      HashMap<UUID, Map<Integer, RoadGantryRetention.Snapshot>> result = new HashMap<>();

      for (RoadIndex.Built candidate : candidates) {
         RoadPlanningBudget.check();
         UUID idxx = candidate.record.id();
         RoadIndex.Built beforex = saved.get(idxx);
         if (localProof.contains(idxx) && candidate.record.junction() == null && candidate.record.toll() == null) {
            HashMap<Integer, RoadGantryRetention.Snapshot> frames = new HashMap<>();

            for (RoadGantry.Edit edit : candidate.record.settings().options().infrastructure().gantryEdits()) {
               int slot = edit.slot();
               if (RoadGantry.exists(beforex.mesh, slot) && RoadGantry.exists(candidate.mesh, slot)) {
                  RoadGantry.Station station = RoadGantry.station(candidate.mesh, slot);
                  if (station.kind() != RoadInfrastructure.Gantry.OFF && station.equals(RoadGantry.station(beforex.mesh, slot))) {
                     List<RoadStructures.Part> parts = RoadGantry.members(beforex.mesh, slot, beforex.record.structures());
                     if (!parts.isEmpty()) {
                        RoadBoundsIndex.Bounds footprint = RoadEditScope.bounds(parts);
                        if (!changed.stream().anyMatch(area -> area.overlaps(footprint, 82.0))) {
                           frames.put(slot, new RoadGantryRetention.Snapshot(station, parts));
                        }
                     }
                  }
               }
            }

            if (!frames.isEmpty()) {
               result.put(idxx, Map.copyOf(frames));
            }
         }
      }

      return Map.copyOf(result);
   }

   private RoadGantryRetention() {
   }

   static record Snapshot(RoadGantry.Station station, List<RoadStructures.Part> parts) {
      Snapshot(RoadGantry.Station station, List<RoadStructures.Part> parts) {
         parts = List.copyOf(parts);
         this.station = station;
         this.parts = parts;
      }

      List<RoadStructures.Part> at(RoadGantry.Station current) {
         return this.station.equals(current) ? this.parts : null;
      }
   }
}
