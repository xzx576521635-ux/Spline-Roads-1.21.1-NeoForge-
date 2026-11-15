package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadContinuity;
import com.sora.splineroads.core.RoadGeometry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class SharedRoadEndpoints {
   static void validate(RoadData data, BlockPos at, RoadGeometry.Node target) {
      validate(data, at, target, Set.of(), null);
   }

   private static void validate(RoadData data, BlockPos at, RoadGeometry.Node target, Set<UUID> replaced, RoadGeometry.V original) {
      Set<UUID> candidates = data.index.atNode(at);
      Optional<RoadRecord> source = candidates.stream()
         .filter(replaced::contains)
         .map(id -> data.index.roads.get(id).record)
         .filter(rx -> original == null || (rx.a().equals(at) ? rx.start() : rx.end()).position().distance(original) < 1.0E-6)
         .findFirst();
      RoadGeometry.V exact = original != null
         ? original
         : source.<RoadGeometry.V>map(rx -> (rx.a().equals(at) ? rx.start() : rx.end()).position()).orElse(null);
      List<UUID> ids = candidates.stream().filter(id -> !replaced.contains(id) || source.isPresent() && id.equals(source.get().id())).filter(id -> {
         RoadRecord rx = data.index.roads.get(id).record;
         return exact == null || (rx.a().equals(at) ? rx.start() : rx.end()).position().distance(exact) < 1.0E-6;
      }).toList();
      if (!ids.isEmpty() && (ids.size() != 1 || original != null)) {
         if (ids.size() <= 2 && AutoJunctions.center(data, at) == null) {
            List<RoadRecord> roads = ids.stream().map(id -> data.index.roads.get(id).record).toList();
            RoadRecord a = roads.get(0);
            RoadRecord b = roads.get(roads.size() - 1);
            RoadGeometry.V shared = a.a().equals(at) ? a.start().position() : a.end().position();
            if ((a.assembly() == null || replaced.contains(a.id()))
               && (b.assembly() == null || replaced.contains(b.id()))
               && a.junction() == null
               && b.junction() == null
               && (roads.size() == 1 ? RoadContinuity.eligible(a.mesh()) : RoadContinuity.compatible(a.mesh(), b.mesh(), shared))) {
               RoadGeometry.V axis = a.end().position().sub(a.start().position()).horizontalUnit();
               if (!(Math.abs(target.position().y() - shared.y()) > 0.001) && !(Math.abs(target.position().sub(shared).dot(axis.left())) > 0.001)) {
                  for (RoadRecord r : roads) {
                     RoadGeometry.V far = r.a().equals(at) ? r.end().position() : r.start().position();
                     if (target.position().sub(far).dot(shared.sub(far)) <= 0.0 || target.position().sub(far).horizontalLength() < 2.0) {
                        throw new IllegalArgumentException("共享端点不能越过外端点或使相邻道路不足两格");
                     }
                  }
               } else {
                  throw new IllegalArgumentException("共享端点只能沿原水平道路移动，不能引入折角或坡度");
               }
            } else {
               throw new IllegalArgumentException("共享端点邻路须为适用范围内共线、水平且分方向车道数相同的道路");
            }
         } else {
            throw new IllegalArgumentException("共享端点属于路口或分岔，不能自动移动");
         }
      }
   }

   static void stage(
      RoadData data, ServerLevel level, ServerPlayer player, BlockPos at, RoadGeometry.Node target, List<RoadIndex.Built> built, Set<UUID> removed
   ) {
      stage(data, level, player, at, null, target, built, removed);
   }

   static void stage(
      RoadData data,
      ServerLevel level,
      ServerPlayer player,
      BlockPos at,
      RoadGeometry.Node original,
      RoadGeometry.Node target,
      List<RoadIndex.Built> built,
      Set<UUID> removed
   ) {
      List<RoadGeometry.V> sources = data.index
         .atNode(at)
         .stream()
         .filter(removed::contains)
         .map(idx -> data.index.roads.get(idx).record)
         .map(rx -> (rx.a().equals(at) ? rx.start() : rx.end()).position())
         .toList();
      List<UUID> outside = data.index.atNode(at).stream().filter(idx -> !removed.contains(idx)).filter(idx -> {
         RoadRecord rx = data.index.roads.get(idx).record;
         RoadGeometry.V p = (rx.a().equals(at) ? rx.start() : rx.end()).position();
         return original != null ? p.distance(original.position()) < 1.0E-6 : !sources.isEmpty() && sources.stream().anyMatch(s -> s.distance(p) < 1.0E-6);
      }).toList();
      if (!outside.isEmpty()) {
         boolean moved = outside.stream()
            .map(idx -> data.index.roads.get(idx).record)
            .anyMatch(rx -> (rx.a().equals(at) ? rx.start() : rx.end()).position().distance(target.position()) > 1.0E-6);
         if (moved) {
            validate(data, at, target, removed, original == null ? null : original.position());
            BlockPos to = BlockPos.containing(target.position().x(), target.position().y(), target.position().z());

            for (UUID id : outside) {
               RoadRecord r = data.index.roads.get(id).record;
               if (player != null) {
                  RoadData.requireOwner(player, r.owner());
               }

               boolean first = r.a().equals(at);
               RoadGeometry.Node a = first ? new RoadGeometry.Node(target.position(), r.start().yaw(), 0.0) : r.start();
               RoadGeometry.Node b = first ? r.end() : new RoadGeometry.Node(target.position(), r.end().yaw(), 0.0);
               RoadRecord next = new RoadRecord(
                  r.id(),
                  r.owner(),
                  first ? to : r.a(),
                  first ? r.b() : to,
                  a,
                  b,
                  r.settings(),
                  r.automatic(),
                  r.clearance(),
                  List.of(),
                  r.endCaps(),
                  r.buildVersion(),
                  null,
                  List.of(),
                  r.furniturePhase()
               );
               built.add(new RoadIndex.Built(next));
               removed.add(id);
            }
         } else {
            for (UUID id : outside) {
               RoadRecord r = data.index.roads.get(id).record;
               if (r.fragment() != null) {
                  RoadGeometry.Node current = r.a().equals(at) ? r.start() : r.end();
                  double yaw = Math.abs(Math.IEEEremainder(target.yaw() - current.yaw(), 360.0));
                  if (yaw > 1.0E-4 || Math.abs(target.grade() - current.grade()) > 1.0E-6) {
                     throw new IllegalArgumentException("分割共享端点的朝向和纵坡须同时调整两段；当前曲坡联动暂不支持，请保持原接头条件");
                  }
               }
            }
         }
      }
   }

   private SharedRoadEndpoints() {
   }
}
