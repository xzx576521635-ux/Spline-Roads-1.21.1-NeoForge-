package com.sora.splineroads.world;

import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.net.RoadNetwork;
import com.sora.splineroads.net.RoadWire;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class RoadSplitter {
   private static final Map<UUID, RoadSplitter.Pending> PENDING = new LinkedHashMap<>();

   public static RoadSplitter.Hit hit(Collection<RoadIndex.Built> roads, RoadGeometry.V eye, RoadGeometry.V direction, double limit) {
      RoadSplitter.Hit best = null;

      for (RoadIndex.Built road : roads) {
         RoadGeometry.Mesh m = road.mesh;
         if (!(eye.x() + limit < m.min().x())
            && !(eye.x() - limit > m.max().x())
            && !(eye.z() + limit < m.min().z())
            && !(eye.z() - limit > m.max().z())
            && !(eye.y() + limit < m.min().y())
            && !(eye.y() - limit > m.max().y())) {
            double ray = RoadQueries.ray(road.mesh, eye, direction, limit);
            if (Double.isFinite(ray) && (best == null || ray < best.distance())) {
               RoadGeometry.V point = eye.add(direction.mul(ray));
               best = new RoadSplitter.Hit(road, RoadSplitGeometry.station(road.mesh, point), point, ray);
            }
         }
      }

      return best;
   }

   private static RoadSplitter.Hit hit(ServerPlayer player) {
      Vec3 eye = player.getEyePosition();
      Vec3 look = player.getLookAngle();
      RoadGeometry.V origin = new RoadGeometry.V(eye.x, eye.y, eye.z);
      RoadGeometry.V direction = new RoadGeometry.V(look.x, look.y, look.z);
      return hit(RoadData.get(player.serverLevel()).index.rayCandidates(origin, direction, 64.0), origin, direction, 64.0);
   }

   public static void cancel(UUID owner) {
      PENDING.entrySet().removeIf(e -> e.getValue().owner().equals(owner));
   }

   static void expire(long now) {
      PENDING.entrySet().removeIf(e -> now - e.getValue().created() > 120000000000L);
   }

   public static void clear() {
      PENDING.clear();
   }

   private static void requireTool(ServerPlayer player) {
      if (!player.mayBuild()
         || player.isSpectator()
         || !(player.getMainHandItem().getItem() instanceof RoadSplitTool) && !(player.getOffhandItem().getItem() instanceof RoadSplitTool)) {
         throw new IllegalArgumentException("请手持道路分割器并确保拥有建设权限");
      }
   }

   public static CompoundTag preview(ServerPlayer player, CompoundTag command) {
      requireTool(player);
      cancel(player.getUUID());
      CompoundTag reply = new CompoundTag();
      reply.putString("Kind", "splitCheck");
      reply.putLong("Request", command.getLong("Request"));
      reply.putString("Dimension", player.level().dimension().location().toString());
      RoadIndex.Built built = RoadData.get(player.serverLevel()).index.roads.get(command.getUUID("Id"));
      if (built != null && built.record.header().hashCode() == command.getInt("Signature")) {
         RoadRecord r = built.record;
         requireReach(player, r, command.getDouble("Station"));
         RoadSlicing.Connection mode = RoadSlicing.Connection.valueOf(command.getString("Mode"));
         RoadSplitter.Plan plan = plan(player.serverLevel(), player, r, command.getDouble("Station"), mode);
         UUID token = UUID.randomUUID();
         expire(System.nanoTime());
         PENDING.put(token, new RoadSplitter.Pending(reply.getString("Dimension"), player.getUUID(), System.nanoTime(), plan));

         while (PENDING.size() > 1 && (PENDING.size() > 4 || PENDING.values().stream().mapToLong(p -> (long)p.plan().prepared().cells()).sum() > 4000000L)) {
            PENDING.remove(PENDING.keySet().iterator().next());
         }

         reply.putUUID("Token", token);
         reply.putUUID("Id", r.id());
         reply.putDouble("Station", plan.pair().station());
         reply.putDouble("Length", r.mesh().length());
         reply.put("Node", RoadRecord.writeNode(plan.pair().node()));
         reply.putString("Mode", mode.name());
         ListTag paths = new ListTag();
         if (mode == RoadSlicing.Connection.JUNCTION) {
            for (RoadRecord road : plan.roads()) {
               paths.add(road.header());
            }
         }

         reply.put("PreviewRoads", paths);
         return reply;
      } else {
         throw new IllegalArgumentException("道路已改变，请重新选择");
      }
   }

   public static String commit(ServerPlayer player, CompoundTag command) {
      requireTool(player);
      RoadSplitter.Pending pending = PENDING.remove(command.getUUID("Token"));
      if (pending != null
         && pending.owner().equals(player.getUUID())
         && pending.dimension().equals(player.level().dimension().location().toString())
         && System.nanoTime() - pending.created() <= 120000000000L) {
         RoadSplitter.Plan plan = pending.plan();
         RoadIndex.Built current = RoadData.get(player.serverLevel()).index.roads.get(plan.original().id());
         if (current != null && current.record == plan.original()) {
            requireReach(player, plan.original(), plan.pair().station());
            commit(player.serverLevel(), player, plan);
            return "道路已分割为两段，共享端点与原有设施已保存";
         } else {
            throw new IllegalArgumentException("道路已改变，请重新选择");
         }
      } else {
         throw new IllegalArgumentException("分割预览已过期，请重新选择");
      }
   }

   private static void requireReach(ServerPlayer player, RoadRecord road, double station) {
      if (Double.isFinite(station) && !(station <= 0.0) && !(station >= road.mesh().length())) {
         Vec3 eye = player.getEyePosition();
         RoadGeometry.Sample sample = RoadSlicing.at(road.mesh(), station);
         RoadGeometry.V point = new RoadGeometry.V(eye.x, eye.y, eye.z);
         double lateral = Math.max(-sample.halfWidth(), Math.min(sample.halfWidth(), point.sub(sample.center()).dot(sample.left())));
         if (sample.at(lateral, 0.0).distance(point) > 64.0) {
            throw new IllegalArgumentException("已离开分割点的操作范围，请重新选择");
         }
      } else {
         throw new IllegalArgumentException("分割位置无效");
      }
   }

   static RoadSplitter.Plan plan(ServerLevel level, ServerPlayer player, RoadRecord road, double station, RoadSlicing.Connection mode) {
      RoadData data = RoadData.get(level);
      if (player != null) {
         RoadData.requireOwner(player, road.owner());
      }

      RoadSplitGeometry.Pair pair = RoadSplitGeometry.split(road, station, mode, UUID.randomUUID());
      ArrayList<RoadSplitGeometry.Range> occupied = new ArrayList<>();
      boolean managedSource = false;

      for (CompoundTag descriptor : data.interchanges.values()) {
         if (ContinuousRoads.sources(descriptor).stream().anyMatch(s -> s.road().id().equals(road.id()))) {
            managedSource = true;
         }

         for (Tag entry : descriptor.getList("OccupiedRoads", 10)) {
            CompoundTag v = (CompoundTag)entry;
            if (v.getUUID("Road").equals(road.id())) {
               occupied.add(new RoadSplitGeometry.Range(v.getDouble("From"), v.getDouble("To"), "这里属于立交生成器实际占用区间"));
            }
         }
      }

      if (road.assembly() != null && !managedSource) {
         throw new IllegalArgumentException("受管立交或组合内部道路不能直接分割");
      } else {
         RoadSplitGeometry.requireClear(occupied, station, station);
         if (level.getBlockEntity(pair.pos()) != null && !RoadBlocks.isCollider(level.getBlockState(pair.pos()))) {
            throw new IllegalArgumentException("新端点位置被方块实体占用");
         } else {
            RoadRecord first = pair.first();
            RoadRecord second = pair.second();
            if (managedSource) {
               if (occupied.stream().noneMatch(v -> v.intersects(0.0, station))) {
                  first = assembly(first, null);
               }

               if (occupied.stream().noneMatch(v -> v.intersects(station, road.mesh().length()))) {
                  second = assembly(second, null);
               }
            }

            LinkedHashMap<UUID, RoadRecord> replacement = new LinkedHashMap<>();
            replacement.put(first.id(), first);
            replacement.put(second.id(), second);
            LinkedHashMap<UUID, RoadRecord> streets = new LinkedHashMap<>(data.streets);
            RoadRecord logical = AutoJunctions.logical(data).get(road.id());
            RoadSplitGeometry.Pair full = !streets.containsKey(road.id())
               ? null
               : RoadSplitGeometry.split(logical, mappedStation(logical, road, pair.station()), mode, second.id());
            if (full != null) {
               streets.put(first.id(), full.first());
               streets.put(second.id(), full.second());
            }

            LinkedHashMap<UUID, CompoundTag> junctions = new LinkedHashMap<>();
            data.junctions.forEach((id, v) -> junctions.put(id, remapJunction(v, road, pair.second().id())));
            LinkedHashMap<UUID, CompoundTag> interchanges = new LinkedHashMap<>();

            for (Entry<UUID, CompoundTag> entryx : data.interchanges.entrySet()) {
               interchanges.put(entryx.getKey(), remapAssembly(entryx.getValue(), road, pair));
            }

            Set<UUID> secondPoints = new HashSet<>();

            for (LanePoints.Point p : second.settings().options().lanePoints().points()) {
               secondPoints.add(p.id());
            }

            for (RoadIndex.Built built : data.index.roads.values()) {
               if (!built.record.id().equals(road.id())) {
                  RoadRecord r = remapLinks(built.record, road.id(), second.id(), secondPoints);
                  if (r != built.record) {
                     replacement.put(r.id(), r);
                  }
               }
            }

            streets.replaceAll((id, r) -> remapLinks(r, road.id(), pair.second().id(), secondPoints));
            if (mode == RoadSlicing.Connection.JUNCTION) {
               if (RoadProfile.catalog(road.settings()).type() != RoadProfile.Type.ORDINARY) {
                  throw new IllegalArgumentException("当前道路不支持平交路口，可使用连续模式");
               }

               CompoundTag config = AutoJunctions.config(pair.pos(), road.owner());
               AutoJunctions.Draft draft = AutoJunctions.plan(List.of(first, second), List.of(config));
               double left = station;
               double right = station;

               for (RoadRecord r : draft.roads()) {
                  if (r.junction() == null) {
                     if (r.id().equals(first.id())) {
                        left = RoadSplitGeometry.station(road.mesh(), r.mesh().last().center());
                     } else if (r.id().equals(second.id())) {
                        right = RoadSplitGeometry.station(road.mesh(), r.mesh().first().center());
                     }
                  }
               }

               RoadSplitGeometry.requireClear(pair.protection(), left, right);
               RoadSplitGeometry.requireClear(occupied, left, right);

               for (RoadRecord rx : draft.roads()) {
                  replacement.put(rx.id(), rx);
               }

               streets.put(first.id(), first);
               streets.put(second.id(), second);

               for (CompoundTag c : draft.centers()) {
                  junctions.put(c.getUUID("Id"), c);
               }

               for (RoadRecord rx : draft.roads()) {
                  for (RoadIndex.Built old : data.index.roads.values()) {
                     if (!old.record.id().equals(road.id()) && !replacement.containsKey(old.record.id()) && RoadIndex.overlapXZ(rx.mesh(), old.mesh, 1.0)) {
                        Interchanges.checkExternal(rx.mesh(), old.mesh, null);
                     }
                  }
               }
            }

            HashMap<Integer, UUID> owners = new HashMap<>();

            for (RoadRecord rx : List.of(first, second)) {
               for (int slot : RoadGantry.slots(rx.mesh())) {
                  owners.put(slot, rx.id());
               }
            }

            for (SmartSigns.State state : data.smartSigns.road(road.id())) {
               if (!owners.containsKey(state.key().slot())) {
                  throw new IllegalArgumentException("原标牌缺少可迁移的龙门架站位");
               }
            }

            CompoundTag nodeTag = new CompoundTag();
            nodeTag.putUUID("Owner", road.owner());
            nodeTag.put("Node", RoadRecord.writeNode(pair.node()));
            nodeTag.putBoolean("HeightExplicit", true);
            nodeTag.putBoolean("HeadingLocked", true);
            nodeTag.putBoolean("GradeLocked", true);
            RoadData.NodeMove moves = new RoadData.NodeMove(null, pair.pos(), pair.node(), nodeTag);
            HashSet<UUID> removed = new HashSet<>(replacement.keySet());
            removed.retainAll(data.index.roads.keySet());
            List<RoadRecord> roads = mode == RoadSlicing.Connection.JUNCTION
               ? data.splitJunctionStructures(level, List.copyOf(replacement.values()), road.id())
               : List.copyOf(replacement.values());
            List<RoadIndex.Built> builtx = roads.stream().map(RoadIndex.Built::new).toList();
            CompoundTag packet = RoadNetwork.batchPacket(level, roads, removed, pair.pos());
            RoadWire.split(packet);
            RoadData.SplitPrepared prepared = data.prepareSplit(level, player, builtx, removed, moves, mode == RoadSlicing.Connection.JUNCTION);
            return new RoadSplitter.Plan(
               road,
               pair,
               roads,
               RoadMetadataPatch.between(data.streets, streets, rx -> rx),
               RoadMetadataPatch.between(data.junctions, junctions, CompoundTag::copy),
               RoadMetadataPatch.between(data.interchanges, interchanges, CompoundTag::copy),
               Map.copyOf(owners),
               prepared
            );
         }
      }
   }

   static void commit(ServerLevel level, ServerPlayer player, RoadSplitter.Plan plan) {
      RoadData data = RoadData.get(level);
      plan.streets().validate(data.streets);
      plan.junctions().validate(data.junctions);
      plan.interchanges().validate(data.interchanges);
      data.commitSplit(level, player, plan.prepared(), () -> {
         plan.streets().apply(data.streets);
         plan.junctions().apply(data.junctions);
         plan.interchanges().apply(data.interchanges);
         data.smartSigns.splitKeys(plan.original().id(), plan.signOwners());
      });
   }

   private static RoadRecord assembly(RoadRecord r, UUID group) {
      return new RoadRecord(
         r.id(),
         r.owner(),
         r.a(),
         r.b(),
         r.start(),
         r.end(),
         r.settings(),
         r.automatic(),
         r.clearance(),
         r.structures(),
         r.endCaps(),
         r.buildVersion(),
         group,
         r.alignment(),
         r.furniturePhase(),
         r.junction(),
         r.toll(),
         r.fragment()
      );
   }

   private static LanePoints.Ref ref(LanePoints.Ref ref, UUID source, UUID second, Set<UUID> points) {
      return source.equals(ref.road()) && points.contains(ref.point()) ? LanePoints.Ref.lane(second, ref.point()) : ref;
   }

   private static double mappedStation(RoadRecord whole, RoadRecord visible, double station) {
      double mapped = station + visible.settings().options().ends().trimmedStart() - whole.settings().options().ends().trimmedStart();
      RoadGeometry.V point = RoadSlicing.at(visible.mesh(), station).center();
      return mapped >= 0.0 && mapped <= whole.mesh().length() && RoadSlicing.at(whole.mesh(), mapped).center().distance(point) < 1.0E-6
         ? mapped
         : RoadSplitGeometry.station(whole.mesh(), point);
   }

   static RoadRecord remapLinks(RoadRecord road, UUID source, UUID second, Set<UUID> points) {
      LanePoints.Data data = road.settings().options().lanePoints();
      LanePoints.Link old = data.link();
      if (old == null) {
         return road;
      } else {
         LanePoints.Ref from = ref(old.from(), source, second, points);
         LanePoints.Ref to = ref(old.to(), source, second, points);
         return from == old.from() && to == old.to()
            ? road
            : road.withLanePoints(
               data.link(new LanePoints.Link(from, to, old.options(), old.junctionMouth(), old.targetOffset(), old.protectedMerge(), old.rectangularClosure()))
            );
      }
   }

   static CompoundTag remapJunction(CompoundTag before, RoadRecord old, UUID second) {
      CompoundTag tag = before.copy();
      if (tag.getLong("CenterPos") == old.b().asLong() || Arrays.stream(tag.getLongArray("Ports")).anyMatch(v -> v == old.b().asLong())) {
         ListTag arms = tag.getList("ArmRoads", 8);

         for (int i = 0; i < arms.size(); i++) {
            if (arms.getString(i).equals(old.id().toString())) {
               arms.set(i, StringTag.valueOf(second.toString()));
            }
         }
      }

      return tag;
   }

   static CompoundTag remapAssembly(CompoundTag before, RoadRecord old, RoadSplitGeometry.Pair pair) {
      boolean hasSource = before.getList("ContinuousSources", 10).stream().anyMatch(vx -> {
         CompoundTag tx = ((CompoundTag)vx).getCompound("Road");
         return tx.hasUUID("Id") && tx.getUUID("Id").equals(old.id());
      });
      boolean occupiedSource = before.getList("OccupiedRoads", 10).stream().anyMatch(vx -> {
         CompoundTag tx = (CompoundTag)vx;
         return tx.hasUUID("Road") && tx.getUUID("Road").equals(old.id());
      });
      if (!hasSource && !occupiedSource) {
         return before;
      } else {
         CompoundTag result = before.copy();
         ListTag sources = new ListTag();

         for (Tag entry : before.getList("ContinuousSources", 10)) {
            CompoundTag source = (CompoundTag)entry;
            RoadRecord r = RoadRecord.load(source.getCompound("Road"));
            if (!r.id().equals(old.id())) {
               sources.add(source.copy());
            } else {
               RoadSplitGeometry.Pair split = RoadSplitGeometry.split(
                  r, mappedStation(r, old, pair.station()), pair.first().fragment().connection(), pair.second().id()
               );

               for (RoadRecord half : List.of(split.first(), split.second())) {
                  CompoundTag next = source.copy();
                  next.put("Road", half.header());
                  sources.add(next);
               }
            }
         }

         result.put("ContinuousSources", sources);
         ListTag occupied = new ListTag();
         HashSet<UUID> owners = new HashSet<>();

         for (Tag entryx : before.getList("OccupiedRoads", 10)) {
            CompoundTag t = ((CompoundTag)entryx).copy();
            if (t.getUUID("Road").equals(old.id())) {
               if (Math.min(t.getDouble("From"), t.getDouble("To")) >= pair.station()) {
                  t.putUUID("Road", pair.second().id());
                  t.putDouble("From", t.getDouble("From") - pair.station());
                  t.putDouble("To", t.getDouble("To") - pair.station());
               }

               owners.add(t.getUUID("Road"));
            }

            occupied.add(t);
         }

         result.put("OccupiedRoads", occupied);

         for (String key : List.of("SignTopology", "CorridorRoles")) {
            ListTag list = new ListTag();

            for (Tag entryx : before.getList(key, 10)) {
               CompoundTag t = (CompoundTag)entryx;
               if (!t.getUUID("Road").equals(old.id())) {
                  list.add(t.copy());
               } else {
                  for (UUID owner : owners) {
                     CompoundTag next = t.copy();
                     next.putUUID("Road", owner);
                     list.add(next);
                  }
               }
            }

            if (before.contains(key)) {
               result.put(key, list);
            }
         }

         if (sources.stream()
            .anyMatch(
               tx -> ((CompoundTag)tx).getCompound("Road").hasUUID("Id") && ((CompoundTag)tx).getCompound("Road").getUUID("Id").equals(pair.second().id())
            )) {
            LinkedHashSet<Long> nodes = new LinkedHashSet<>();

            for (long v : before.getLongArray("PreservedNodes")) {
               nodes.add(Long.valueOf(v));
            }

            nodes.add(Long.valueOf(pair.pos().asLong()));
            result.putLongArray("PreservedNodes", nodes.stream().mapToLong(Long::longValue).toArray());
         }

         return result;
      }
   }

   private RoadSplitter() {
   }

   public static record Hit(RoadIndex.Built road, double station, RoadGeometry.V point, double distance) {
   }

   private static record Pending(String dimension, UUID owner, long created, RoadSplitter.Plan plan) {
   }

   static record Plan(
      RoadRecord original,
      RoadSplitGeometry.Pair pair,
      List<RoadRecord> roads,
      RoadMetadataPatch<RoadRecord> streets,
      RoadMetadataPatch<CompoundTag> junctions,
      RoadMetadataPatch<CompoundTag> interchanges,
      Map<Integer, UUID> signOwners,
      RoadData.SplitPrepared prepared
   ) {
   }
}
