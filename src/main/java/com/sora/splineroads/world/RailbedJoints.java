package com.sora.splineroads.world;

import com.sora.splineroads.core.RailbedGeometry;
import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RailbedProfile;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadRibbon;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

final class RailbedJoints {
   static void normalize(RoadIndex index, List<RoadIndex.Built> built, Set<UUID> removed, ServerPlayer player) {
      HashSet<BlockPos> affected = new HashSet<>();
      LinkedHashMap<UUID, RoadIndex.Built> all = new LinkedHashMap<>(index.roads);

      for (UUID id : removed) {
         RoadIndex.Built old = all.remove(id);
         if (old != null && old.record.railway()) {
            affected.add(old.record.a());
            affected.add(old.record.b());
         }
      }

      for (RoadIndex.Built r : built) {
         all.put(r.record.id(), r);
         if (r.record.railway()) {
            affected.add(r.record.a());
            affected.add(r.record.b());
         }
      }

      if (!affected.isEmpty()) {
         HashMap<BlockPos, List<RoadIndex.Built>> joints = new HashMap<>();

         for (RoadIndex.Built rx : all.values()) {
            if (rx.record.settings().options().railbed().active()) {
               for (BlockPos p : List.of(rx.record.a(), rx.record.b())) {
                  joints.computeIfAbsent(p, k -> new ArrayList<>()).add(rx);
               }
            }
         }

         for (RoadIndex.Built rxx : all.values()) {
            RoadRecord record = rxx.record;
            RailbedProfile profile = record.settings().options().railbed();
            if (profile.active() && (affected.contains(record.a()) || affected.contains(record.b()))) {
               RoadGeometry.Mesh base = RailbedGeometry.plan(profile.paths(), profile.requestedWidth(), record.settings().structure()).mesh();
               double a = width(rxx, record.a(), joints);
               double b = width(rxx, record.b(), joints);
               double blend = Math.min(16.0, base.length() / 2.0);
               ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

               for (RoadGeometry.Sample at : base.samples()) {
                  double from = Math.max(0.0, 1.0 - at.distance() / blend);
                  double to = Math.max(0.0, 1.0 - (base.length() - at.distance()) / blend);
                  double half = at.halfWidth() + Math.max((a / 2.0 - at.halfWidth()) * from, (b / 2.0 - at.halfWidth()) * to);
                  samples.add(new RoadGeometry.Sample(at.center(), at.left(), at.distance(), half));
               }

               if (!samples.equals(record.alignment())) {
                  if (player != null) {
                     RoadData.requireOwner(player, record.owner());
                  }

                  RoadIndex.Built next = new RoadIndex.Built(
                     record.alignment(record.assembly(), RoadRibbon.mesh(samples, record.settings())).structures(List.of())
                  );
                  int slot = -1;

                  for (int i = 0; i < built.size(); i++) {
                     if (built.get(i).record.id().equals(record.id())) {
                        slot = i;
                     }
                  }

                  if (slot < 0) {
                     built.add(next);
                  } else {
                     built.set(slot, next);
                  }

                  if (index.roads.containsKey(record.id())) {
                     removed.add(record.id());
                  }
               }
            }
         }
      }
   }

   private static double width(RoadIndex.Built road, BlockPos node, Map<BlockPos, List<RoadIndex.Built>> joints) {
      double width = road.record.settings().width();
      List<RoadIndex.Built> links = joints.getOrDefault(node, List.of());
      if (links.size() != 2) {
         return width;
      } else {
         Set<RailbedPath.Node> own = endpoints(road, node);
         RoadGeometry.Sample at = road.record.a().equals(node) ? road.mesh.first() : road.mesh.last();

         for (RoadIndex.Built other : links) {
            if (other != road && own.equals(endpoints(other, node))) {
               RoadGeometry.Sample end = other.record.a().equals(node) ? other.mesh.first() : other.mesh.last();
               if (at.center().distance(end.center()) < 1.0E-6) {
                  width = Math.max(width, other.record.settings().width());
               }
            }
         }

         return width;
      }
   }

   private static Set<RailbedPath.Node> endpoints(RoadIndex.Built road, BlockPos node) {
      HashSet<RailbedPath.Node> result = new HashSet<>();

      for (RailbedPath.Path path : road.record.settings().options().railbed().paths()) {
         RailbedPath.Node start = path.segments().get(0).from();
         RailbedPath.Node end = path.segments().get(path.segments().size() - 1).to();
         result.add(start.center().distance(RailbedCodec.node(node).center()) <= end.center().distance(RailbedCodec.node(node).center()) ? start : end);
      }

      return result;
   }

   private RailbedJoints() {
   }
}
