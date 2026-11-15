package com.sora.splineroads.world;

import com.sora.splineroads.core.ElevatedAccess;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneRampAlignment;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;

public final class LaneTopology {
   public static LanePoints.Data metadata(RoadRecord r) {
      return r.settings().options().lanePoints();
   }

   public static LanePoints.Point point(RoadRecord r, UUID id) {
      return metadata(r).points().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("车道点已不存在，请重新选择"));
   }

   static boolean independent(RoadRecord r) {
      return !r.railway() && r.junction() == null && r.toll() == null;
   }

   static boolean normal(RoadRecord r) {
      return independent(r) && metadata(r).link() == null;
   }

   private static Map<BlockPos, Set<UUID>> endpointOwners(Collection<RoadRecord> roads) {
      HashMap<BlockPos, Set<UUID>> owners = new HashMap<>();

      for (RoadRecord r : roads) {
         if (!r.railway() && metadata(r).link() == null) {
            owners.computeIfAbsent(r.a(), k -> new HashSet<>()).add(r.id());
            owners.computeIfAbsent(r.b(), k -> new HashSet<>()).add(r.id());
         }
      }

      return owners;
   }

   public static boolean generatedRamp(RoadRecord r) {
      return r.assembly() != null && r.settings().style().ramp();
   }

   public static List<LanePoints.Point> visiblePoints(RoadRecord r) {
      return metadata(r).points().stream().filter(p -> !p.automatic() || !generatedRamp(r)).toList();
   }

   private static boolean free(RoadRecord r, RoadGeometry.Mesh mesh, boolean first, Map<BlockPos, Set<UUID>> owners) {
      if (!generatedRamp(r) && !mesh.closed() && r.junction() == null && r.toll() == null && metadata(r).link() == null) {
         Set<UUID> ids = owners.getOrDefault(first ? r.a() : r.b(), Set.of());
         return ids.isEmpty() || ids.size() == 1 && ids.contains(r.id());
      } else {
         return false;
      }
   }

   public static List<UUID> references(Collection<RoadRecord> roads, LanePoints.Ref ref) {
      return roads.stream().filter(r -> {
         LanePoints.Link link = metadata(r).link();
         return link != null && (link.from().equals(ref) || link.to().equals(ref));
      }).map(RoadRecord::id).sorted().toList();
   }

   static List<UUID> references(RoadData data, LanePoints.Ref ref) {
      return data.index.laneReferences(ref);
   }

   public static Set<UUID> dependents(RoadData data, Set<UUID> initial) {
      Set<UUID> result = new LinkedHashSet<>(initial);

      boolean changed;
      do {
         changed = false;

         for (RoadIndex.Built b : data.index.roads.values()) {
            if (result.contains(b.record.id())) {
               for (ElevatedAccess.Reservation access : metadata(b.record).access()) {
                  changed |= result.add(access.connection());
               }
            }

            for (LaneSections.Cut cut : metadata(b.record).cuts()) {
               if (result.contains(cut.connection()) && cut.replacement() != null) {
                  changed |= result.add(cut.replacement());
               }
            }

            LanePoints.Link l = metadata(b.record).link();
            if (l != null && !result.contains(b.record.id())) {
               boolean hit = result.contains(l.from().road())
                  || l.to().road() != null && result.contains(l.to().road())
                  || l.to().junction() != null
                     && result.stream().map(data.index.roads::get).filter(Objects::nonNull).anyMatch(v -> l.to().junction().equals(v.record.assembly()));
               if (hit) {
                  changed |= result.add(b.record.id());
               }
            }
         }
      } while (changed);

      result.removeAll(initial);
      return result;
   }

   static Map<UUID, RoadRecord> records(RoadData data) {
      Map<UUID, RoadRecord> all = new LinkedHashMap<>();
      data.index.roads.forEach((id, b) -> all.put(id, b.record));
      return all;
   }

   public static void initialize(RoadData data) {
      Map<UUID, RoadRecord> all = records(data);
      LaneCrossSections.reconcile(all);
      assignPriorities(all);
      Map<BlockPos, Set<UUID>> ends = endpointOwners(all.values());

      for (RoadRecord r : new ArrayList<>(all.values())) {
         if (independent(r) && !generatedRamp(r)) {
            RoadRecord next = automatic(r, ends);
            if (!next.equals(data.index.roads.get(r.id()).record)) {
               data.index.put(RoadIndex.Built.loading(next));
               if (data.streets.containsKey(r.id())) {
                  data.streets.put(r.id(), data.streets.get(r.id()).withLanePoints(metadata(next)));
               }

               data.setDirty();
            }
         }
      }
   }

   private static RoadRecord automatic(RoadRecord r, Map<BlockPos, Set<UUID>> ends) {
      LanePoints.Data md = metadata(r);
      List<LanePoints.Point> points = new ArrayList<>();

      for (LanePoints.Point p : md.points()) {
         if (!p.automatic()) {
            points.add(LanePoints.snap(r.mesh(), p));
         }
      }

      RoadGeometry.Mesh mesh = r.mesh();
      int lanes = RoadProfile.catalog(r.settings()).lanes();

      for (boolean first : new boolean[]{true, false}) {
         if (free(r, mesh, first, ends)) {
            for (int i : LaneAdditions.slots(mesh, first ? 0.0 : mesh.length())) {
               double station = first ? 0.0 : mesh.length();
               if (LaneSections.active(mesh, station, i)) {
                  LanePoints.Origin origin = first ? LanePoints.Origin.AUTOMATIC_START : LanePoints.Origin.AUTOMATIC_END;
                  UUID id = md.points()
                     .stream()
                     .filter(px -> px.origin() == origin && px.lane() == i)
                     .map(LanePoints.Point::id)
                     .findFirst()
                     .orElse(UUID.nameUUIDFromBytes((r.id() + ":lane:" + origin + ":" + i).getBytes(StandardCharsets.UTF_8)));
                  points.add(LanePoints.point(id, origin, mesh, station, i));
               }
            }
         }
      }

      return r.withLanePoints(md.points(points));
   }

   static void snapPlannedPoints(List<RoadIndex.Built> built, List<RoadIndex.Built> planning) {
      for (int i = 0; i < built.size(); i++) {
         RoadIndex.Built old = built.get(i);
         if (independent(old.record)) {
            LanePoints.Data md = metadata(old.record);
            ArrayList<LanePoints.Point> points = new ArrayList<>();

            for (LanePoints.Point p : md.points()) {
               points.add(
                  p.mergeLength() != 0.0
                     ? p
                     : (
                        p.automatic()
                           ? LanePoints.point(p.id(), p.origin(), old.mesh, p.origin() == LanePoints.Origin.AUTOMATIC_START ? 0.0 : old.mesh.length(), p.lane())
                           : LanePoints.snap(old.mesh, p)
                     )
               );
            }

            if (!points.equals(md.points())) {
               RoadIndex.Built next = old.lanePoints(md.points(points));
               built.set(i, next);
               int slot = planning.indexOf(old);
               if (slot >= 0) {
                  planning.set(slot, next);
               }
            }
         }
      }
   }

   static boolean needsRefresh(Collection<RoadIndex.Built> planning) {
      return needsRefresh(planning, null);
   }

   static boolean needsRefresh(Collection<RoadIndex.Built> planning, Collection<UUID> edited) {
      return needsRefresh(null, planning, edited);
   }

   static boolean needsRefresh(RoadData data, Collection<RoadIndex.Built> planning, Collection<UUID> edited) {
      LinkedHashMap<UUID, RoadRecord> all = new LinkedHashMap<>();

      for (RoadIndex.Built b : planning) {
         all.put(b.record.id(), b.record);
      }

      for (UUID id : edited == null ? all.keySet() : new HashSet<>(edited)) {
         RoadRecord r = all.get(id);
         if (r != null && independent(r)) {
            for (LanePoints.Point p : metadata(r).points()) {
               if (LanePoints.lane(r.mesh(), p).position().distance(p.position()) > 1.0E-5) {
                  return true;
               }
            }

            LanePoints.Link l = metadata(r).link();
            if (l != null && !portsMatch(r, all)) {
               RoadIndex.Built old = data == null ? null : data.index.roads.get(id);
               if (old == null
                  || !sameDeck(r, old.record)
                  || !Objects.equals(l, metadata(old.record).link())
                  || !samePort(data, all, l.from())
                  || !samePort(data, all, l.to())) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   static Set<UUID> editScope(RoadData data, Map<UUID, RoadRecord> all, Collection<RoadIndex.Built> built, Set<UUID> removed) {
      LinkedHashSet<UUID> scope = new LinkedHashSet<>(removed);

      for (RoadIndex.Built b : built) {
         RoadIndex.Built old = data.index.roads.get(b.record.id());
         if (old != null && !topologyChanged(old.record, b.record)) {
            scope.remove(b.record.id());
         } else {
            scope.add(b.record.id());
         }
      }

      HashSet<BlockPos> nodes = new HashSet<>();
      HashSet<UUID> groups = new HashSet<>();

      for (UUID id : new ArrayList<>(scope)) {
         RoadIndex.Built old = data.index.roads.get(id);
         addScopeRecord(old == null ? null : old.record, scope, nodes, groups);
         addScopeRecord(all.get(id), scope, nodes, groups);
      }

      for (RoadRecord r : all.values()) {
         if (nodes.contains(r.a()) || nodes.contains(r.b()) || r.assembly() != null && groups.contains(r.assembly())) {
            scope.add(r.id());
         }
      }

      boolean added;
      do {
         added = false;

         for (RoadRecord rx : all.values()) {
            LanePoints.Link l = metadata(rx).link();
            if (l != null
               && (
                  scope.contains(l.from().road())
                     || l.to().road() != null && scope.contains(l.to().road())
                     || l.to().junction() != null && groups.contains(l.to().junction())
               )) {
               added |= scope.add(rx.id());
            }
         }
      } while (added);

      for (UUID id : new ArrayList<>(scope)) {
         RoadIndex.Built old = data.index.roads.get(id);
         addLinkHosts(old == null ? null : old.record, scope);
         addLinkHosts(all.get(id), scope);
      }

      for (RoadIndex.Built bx : built) {
         scope.add(bx.record.id());
      }

      return scope;
   }

   private static void addLinkHosts(RoadRecord r, Set<UUID> scope) {
      if (r != null) {
         LanePoints.Link l = metadata(r).link();
         if (l != null) {
            if (l.from().road() != null) {
               scope.add(l.from().road());
            }

            if (l.to().road() != null) {
               scope.add(l.to().road());
            }
         }
      }
   }

   private static void addScopeRecord(RoadRecord r, Set<UUID> scope, Set<BlockPos> nodes, Set<UUID> groups) {
      if (r != null) {
         LanePoints.Link l = metadata(r).link();
         if (l == null) {
            nodes.add(r.a());
            nodes.add(r.b());
            if (r.assembly() != null) {
               groups.add(r.assembly());
            }
         }
      }
   }

   private static boolean topologyChanged(RoadRecord a, RoadRecord b) {
      return !a.a().equals(b.a())
         || !a.b().equals(b.b())
         || !a.start().equals(b.start())
         || !a.end().equals(b.end())
         || !authoredSettings(a).equals(authoredSettings(b))
         || !a.alignment().equals(b.alignment())
         || !Objects.equals(a.assembly(), b.assembly())
         || !Objects.equals(a.junction(), b.junction());
   }

   private static RoadGeometry.Settings authoredSettings(RoadRecord r) {
      RoadProfile.Options o = r.settings().options();
      LanePoints.Data md = o.lanePoints();
      List<LanePoints.Point> points = md.points()
         .stream()
         .filter(p -> !p.automatic())
         .map(p -> new LanePoints.Point(p.id(), p.origin(), p.lane(), p.anchor() == null ? p.position() : p.anchor(), p.anchor(), p.mergeLength()))
         .toList();
      return r.settings()
         .options(o.lanePoints(new LanePoints.Data(points, md.link(), List.of(), 0, List.of())).streetscape(o.streetscape().raisedSpans(List.of())));
   }

   private static boolean sameDeck(RoadRecord a, RoadRecord b) {
      return a.settings().thickness() == b.settings().thickness()
         && a.settings().style() == b.settings().style()
         && a.settings().options().leftTraffic() == b.settings().options().leftTraffic()
         && a.mesh().samples().equals(b.mesh().samples());
   }

   private static boolean portsMatch(RoadRecord r, Map<UUID, RoadRecord> all) {
      LanePoints.Link l = metadata(r).link();

      try {
         RoadRecord a = LaneRamps.host(all, l.from());
         RoadGeometry.V first = LaneRamps.port(a, point(a, l.from().point()), l.options().sourceExtra() ? 1 : l.options().lanes()).position();
         RoadGeometry.V last = l.junctionMouth();
         if (l.to().road() != null) {
            last = LaneRamps.resolvedArrival(all, l, r.id()).position();
         }

         return LaneRampAlignment.axis(r.mesh(), true).distance(first) < 1.0E-5 && LaneRampAlignment.axis(r.mesh(), false).distance(last) < 1.0E-5;
      } catch (IllegalArgumentException var6) {
         return false;
      }
   }

   static void reconcile(RoadData data, List<RoadIndex.Built> built, Set<UUID> removed) {
      RoadPlanningBudget.check();
      Map<UUID, RoadRecord> all = records(data);
      removed.forEach(all::remove);

      for (RoadIndex.Built b : built) {
         all.put(b.record.id(), b.record);
      }

      Set<UUID> scope = editScope(data, all, built, removed);
      Map<LanePoints.Ref, LanePoints.Ref> migrations = new HashMap<>();

      for (RoadRecord r : new ArrayList<>(all.values())) {
         if (independent(r) && scope.contains(r.id())) {
            LanePoints.Data md = metadata(r);
            List<LanePoints.Point> points = new ArrayList<>();
            RoadGeometry.Mesh mesh = r.rawMesh();

            for (LanePoints.Point p : md.points()) {
               if (p.automatic()) {
                  points.add(p);
               } else {
                  RoadGeometry.V anchor = p.anchor() == null ? p.position() : p.anchor();
                  RoadQueries.Projection q = RoadQueries.project(mesh, anchor);
                  RoadGeometry.V tangent = q.tangent().horizontalUnit();
                  double beyond = anchor.sub(q.sample().center()).dot(tangent);
                  RoadRecord target = r;
                  if ((q.sample().distance() < 1.0E-6 && beyond < -0.05 || q.sample().distance() > mesh.length() - 1.0E-6 && beyond > 0.05)
                     && data.index.roads.containsKey(r.id())) {
                     RoadRecord old = data.index.roads.get(r.id()).record;

                     for (RoadRecord candidate : all.values()) {
                        if (!candidate.id().equals(r.id())
                           && normal(candidate)
                           && adjacentBefore(data, candidate, old)
                           && p.lane() < RoadProfile.catalog(candidate.settings()).lanes()) {
                           RoadQueries.Projection v = RoadQueries.horizontal(candidate.rawMesh(), p.position());
                           double along = p.position().sub(v.sample().center()).dot(v.tangent().horizontalUnit());
                           if (v.horizontalDistance() < v.sample().halfWidth()
                              && (!(v.sample().distance() < 1.0E-6) || !(along < -0.05))
                              && (!(v.sample().distance() > candidate.mesh().length() - 1.0E-6) || !(along > 0.05))) {
                              target = candidate;
                              break;
                           }
                        }
                     }
                  }

                  RoadIndex.Built oldRecord = data.index.roads.get(r.id());
                  LanePoints.Point moved = target.id().equals(r.id())
                     ? LanePoints.snap(target.mesh(), p)
                     : LanePoints.migrate(oldRecord == null ? r.mesh() : oldRecord.mesh, target.mesh(), p);
                  if (target.id().equals(r.id())) {
                     points.add(moved);
                  } else {
                     LanePoints.Data targetData = metadata(all.get(target.id()));
                     ArrayList<LanePoints.Point> list = new ArrayList<>(targetData.points());
                     list.add(moved);
                     all.put(target.id(), all.get(target.id()).withLanePoints(targetData.points(list)));
                     scope.add(target.id());
                     migrations.put(LanePoints.Ref.lane(r.id(), p.id()), LanePoints.Ref.lane(target.id(), p.id()));
                  }
               }
            }

            for (LanePoints.Point px : metadata(all.get(r.id())).points()) {
               if (md.points().stream().noneMatch(x -> x.id().equals(px.id()))) {
                  points.add(px);
               }
            }

            all.put(r.id(), r.withLanePoints(md.points(points)));
         }
      }

      for (RoadRecord rx : new ArrayList<>(all.values())) {
         LanePoints.Link l = metadata(rx).link();
         if (l != null) {
            LanePoints.Ref from = migrations.getOrDefault(l.from(), l.from());
            LanePoints.Ref to = migrations.getOrDefault(l.to(), l.to());
            if (!from.equals(l.from()) || !to.equals(l.to())) {
               all.put(
                  rx.id(),
                  rx.withLanePoints(
                     metadata(rx)
                        .link(new LanePoints.Link(from, to, l.options(), l.junctionMouth(), l.targetOffset(), l.protectedMerge(), l.rectangularClosure()))
                  )
               );
            }
         }
      }

      LaneCrossSections.reconcile(all, scope);
      RoadContinuations.reconcile(records(data), all, scope);
      Map<BlockPos, Set<UUID>> ends = endpointOwners(all.values());

      for (RoadRecord rxx : new ArrayList<>(all.values())) {
         if (independent(rxx) && scope.contains(rxx.id())) {
            all.put(rxx.id(), automatic(rxx, ends));
         }
      }

      for (RoadRecord rxxx : all.values()) {
         LanePoints.Link link = metadata(rxxx).link();
         if (link != null && scope.contains(rxxx.id())) {
            for (LanePoints.Ref ref : List.of(link.from(), link.to())) {
               if (ref.road() != null) {
                  RoadRecord host = all.get(ref.road());
                  if (host == null) {
                     throw new IllegalArgumentException("道路仍被匝道引用，请确认级联删除依赖匝道");
                  }

                  if (metadata(host).points().stream().noneMatch(pxx -> pxx.id().equals(ref.point()))) {
                     throw new IllegalArgumentException("该自动尽头车道点正在使用中，不能正常续接此端；或手动点仍有依赖匝道");
                  }
               }
            }
         }
      }

      LaneCrossSections.reconcile(all, scope);
      Set<UUID> done = new HashSet<>();
      Set<UUID> visiting = new HashSet<>();

      for (UUID id : new ArrayList<>(all.keySet())) {
         rebuild(data, id, all, done, visiting, scope);
      }

      LaneCrossSections.reconcile(all, scope);

      for (RoadRecord rxxxx : new ArrayList<>(all.values())) {
         if (independent(rxxxx) && scope.contains(rxxxx.id())) {
            all.put(rxxxx.id(), automatic(rxxxx, ends));
         }
      }

      assignPriorities(all);
      HashSet<UUID> connectors = new HashSet<>();

      for (UUID id : scope) {
         RoadRecord now = all.get(id);
         RoadIndex.Built old = data.index.roads.get(id);
         if (now != null && metadata(now).link() != null || old != null && metadata(old.record).link() != null) {
            connectors.add(id);
         }
      }

      Map<UUID, RoadRecord> before = records(data);
      HashMap<UUID, Set<UUID>> reverseHosts = new HashMap<>();

      for (UUID idx : connectors) {
         for (Map<UUID, RoadRecord> context : List.of(before, all)) {
            RoadRecord road = context.get(idx);
            if (road != null && metadata(road).link() != null) {
               LanePoints.Link link = metadata(road).link();
               Set<UUID> hosts = reverseHosts.computeIfAbsent(idx, key -> new HashSet<>());
               hosts.addAll(LaneRamps.contactRoads(context, link.from()));
               hosts.addAll(LaneRamps.contactRoads(context, link.to()));
            }
         }
      }

      Map<UUID, List<LanePoints.Opening>> openings = new HashMap<>();

      for (RoadRecord road : all.values()) {
         Set<UUID> oldHosts = reverseHosts.getOrDefault(road.id(), Set.of());
         ArrayList<LanePoints.Opening> kept = new ArrayList<>(
            metadata(road).openings().stream().filter(o -> !connectors.contains(o.connection()) && !oldHosts.contains(o.connection())).toList()
         );
         openings.put(road.id(), kept);
      }

      for (RoadRecord road : all.values()) {
         if (connectors.contains(road.id())) {
            LanePoints.Link link = metadata(road).link();
            if (link != null) {
               LinkedHashSet<UUID> hosts = new LinkedHashSet<>();
               hosts.addAll(LaneRamps.contactRoads(all, link.from()));
               hosts.addAll(LaneRamps.contactRoads(all, link.to()));

               for (UUID idx : hosts) {
                  RoadRecord hostx = all.get(idx);
                  if (hostx != null) {
                     openings.computeIfAbsent(idx, k -> new ArrayList<>()).addAll(contactOpenings(road, hostx));
                     openings.get(road.id()).addAll(contactOpenings(hostx, road));
                  }
               }
            }
         }
      }

      for (RoadRecord roadx : new ArrayList<>(all.values())) {
         if (independent(roadx)) {
            all.put(roadx.id(), roadx.withLanePoints(metadata(roadx).openings(openings.get(roadx.id()))));
         }
      }

      RampJunctions.reconcile(data, all, scope);
      HashSet<UUID> changedDecks = new HashSet<>();

      for (RoadRecord rxxxxx : all.values()) {
         RoadIndex.Built old = data.index.roads.get(rxxxxx.id());
         if (old == null || !sameDeck(rxxxxx, old.record)) {
            changedDecks.add(rxxxxx.id());
         }
      }

      if (!changedDecks.isEmpty()) {
         for (RoadRecord rxxxxxx : all.values()) {
            if (metadata(rxxxxxx).link() != null && !changedDecks.contains(rxxxxxx.id())) {
               boolean near = false;

               for (UUID changedId : changedDecks) {
                  if (!changedId.equals(rxxxxxx.id()) && RoadIndex.overlapXZ(rxxxxxx.mesh(), all.get(changedId).mesh(), 0.0)) {
                     near = true;
                     break;
                  }
               }

               if (near) {
                  try {
                     LaneRamps.validateChanges(rxxxxxx.mesh(), all, rxxxxxx.id(), metadata(rxxxxxx).link(), changedDecks);
                  } catch (IllegalArgumentException var25) {
                     throw new IllegalArgumentException("本次道路修改影响既有匝道 " + rxxxxxx.id() + "：" + var25.getMessage(), var25);
                  }
               }
            }
         }
      }

      for (RoadRecord rxxxxxxx : all.values()) {
         RoadIndex.Built old = data.index.roads.get(rxxxxxxx.id());
         boolean authored = built.stream().anyMatch(b -> b.record.id().equals(rxxxxxxx.id()));
         if (authored || old == null || !old.record.equals(rxxxxxxx)) {
            if (old != null) {
               removed.add(rxxxxxxx.id());
            }

            built.removeIf(b -> b.record.id().equals(rxxxxxxx.id()));
            built.add(new RoadIndex.Built(rxxxxxxx.structures(List.of())));
         }
      }

      Set<UUID> finalIds = all.keySet();
      built.removeIf(b -> !finalIds.contains(b.record.id()));

      for (RoadIndex.Built old : data.index.roads.values()) {
         if (!all.containsKey(old.record.id())) {
            removed.add(old.record.id());
         }
      }
   }

   static void reconcileDeletion(RoadData data, List<RoadIndex.Built> built, Set<UUID> removed) {
      Map<UUID, RoadRecord> before = records(data);
      LinkedHashMap<UUID, RoadRecord> all = new LinkedHashMap<>(before);
      removed.forEach(all::remove);

      for (RoadIndex.Built b : built) {
         all.put(b.record.id(), b.record);
      }

      HashSet<UUID> deleted = new HashSet<>(removed);

      for (RoadIndex.Built b : built) {
         deleted.remove(b.record.id());
      }

      HashSet<UUID> deletedEvents = new HashSet<>(deleted);

      for (UUID id : deleted) {
         RoadRecord old = before.get(id);
         if (old != null) {
            for (LanePoints.Point point : metadata(old).points()) {
               if (!point.automatic() && point.mergeLength() != 0.0) {
                  deletedEvents.add(point.id());
               }
            }
         }
      }

      Map<BlockPos, Set<UUID>> ends = endpointOwners(all.values());
      HashSet<UUID> scope = new HashSet<>();

      for (RoadRecord r : new ArrayList<>(all.values())) {
         LanePoints.Data md = metadata(r);
         List<LaneAdditions.Addition> additions = md.additions().stream().filter(a -> !deletedEvents.contains(a.connection())).toList();
         ArrayList<LaneSections.Cut> cuts = new ArrayList<>();

         for (LaneSections.Cut c : md.cuts()) {
            if (!deletedEvents.contains(c.connection())) {
               if (c.replacement() != null && deleted.contains(c.replacement())) {
                  c = new LaneSections.Cut(
                     c.connection(),
                     c.lane(),
                     c.sign(),
                     c.begin(),
                     c.sign() > 0 ? r.rawMesh().length() + 2.0 * c.transition() : -2.0 * c.transition(),
                     c.transition(),
                     null,
                     c.temporary(),
                     c.arrival(),
                     c.rectangular(),
                     c.underpass()
                  );
               }

               cuts.add(c);
            }
         }

         List<LanePoints.Point> points = md.points()
            .stream()
            .filter(p -> md.additions().stream().noneMatch(a -> a.slot() == p.lane()) || additions.stream().anyMatch(a -> a.slot() == p.lane()))
            .toList();
         RoadRecord next = r.withLanePoints(
            md.access(md.access().stream().filter(a -> !deleted.contains(a.connection())).toList())
               .cuts(cuts)
               .additions(additions)
               .points(points)
               .openings(md.openings().stream().filter(o -> !deleted.contains(o.connection())).toList())
         );
         boolean authored = built.stream().anyMatch(b -> b.record.id().equals(r.id()));
         if (!next.equals(r) || authored) {
            if (normal(next)) {
               next = automatic(next, ends);
            }

            all.put(r.id(), next);
            scope.add(r.id());
         }
      }

      RoadContinuations.reconcile(before, all, scope);

      for (RoadRecord next : all.values()) {
         if (!next.equals(before.get(next.id())) || built.stream().anyMatch(b -> b.record.id().equals(next.id()))) {
            if (data.index.roads.containsKey(next.id())) {
               removed.add(next.id());
            }

            built.removeIf(b -> b.record.id().equals(next.id()));
            built.add(new RoadIndex.Built(next.structures(List.of())));
         }
      }

      refreshDeletionPoints(data, built, removed);
   }

   static void refreshDeletionPoints(RoadData data, List<RoadIndex.Built> built, Set<UUID> removed) {
      Map<UUID, RoadRecord> all = records(data);
      removed.forEach(all::remove);

      for (RoadIndex.Built b : built) {
         all.put(b.record.id(), b.record);
      }

      Map<BlockPos, Set<UUID>> ends = endpointOwners(all.values());

      for (int i = 0; i < built.size(); i++) {
         RoadRecord record = built.get(i).record;
         if (normal(record)) {
            RoadRecord next = automatic(record, ends);
            if (!next.equals(record)) {
               built.set(i, new RoadIndex.Built(next));
            }
         }
      }
   }

   private static List<LanePoints.Opening> contactOpenings(RoadRecord path, RoadRecord surface) {
      RoadGeometry.Mesh road = path.mesh();
      RoadGeometry.Mesh host = surface.mesh();
      List<RoadGeometry.Sample> samples = road.samples();
      boolean[] near = new boolean[samples.size()];
      double half = samples.stream().mapToDouble(RoadGeometry.Sample::halfWidth).max().orElse(path.settings().width() / 2.0);

      for (int i = 0; i < samples.size(); i++) {
         RoadGeometry.Sample s = samples.get(i);
         if (!(s.center().x() < host.min().x() - half - 1.0)
            && !(s.center().x() > host.max().x() + half + 1.0)
            && !(s.center().z() < host.min().z() - half - 1.0)
            && !(s.center().z() > host.max().z() + half + 1.0)) {
            RoadQueries.Projection q = RoadQueries.horizontal(host, s.center());
            near[i] = q.horizontalDistance() <= q.sample().halfWidth() + s.halfWidth() + 0.6 && Math.abs(q.sample().center().y() - s.center().y()) < 2.2;
         }
      }

      ArrayList<LanePoints.Opening> result = new ArrayList<>();
      ArrayList<RoadGeometry.V> run = new ArrayList<>();

      for (int ix = 0; ix < samples.size(); ix++) {
         boolean included = near[ix] || ix > 0 && near[ix - 1] || ix + 1 < near.length && near[ix + 1];
         if (included) {
            run.add(samples.get(ix).center());
         }

         if (!included || ix == samples.size() - 1) {
            if (run.size() > 1) {
               result.add(new LanePoints.Opening(path.id(), run, Math.min(16.0, half)));
            }

            run = new ArrayList<>();
         }
      }

      return result;
   }

   private static boolean adjacentBefore(RoadData data, RoadRecord candidate, RoadRecord old) {
      RoadIndex.Built b = data.index.roads.get(candidate.id());
      RoadRecord r = b == null ? candidate : b.record;
      return r.a().equals(old.a()) || r.a().equals(old.b()) || r.b().equals(old.a()) || r.b().equals(old.b());
   }

   private static boolean sameGeometry(RoadData data, Map<UUID, RoadRecord> all, LanePoints.Ref ref) {
      if (ref.road() == null) {
         return true;
      } else {
         RoadIndex.Built old = data.index.roads.get(ref.road());
         RoadRecord now = all.get(ref.road());
         return old != null && now != null && old.record.rawMesh().samples().equals(now.rawMesh().samples());
      }
   }

   private static boolean samePort(RoadData data, Map<UUID, RoadRecord> all, LanePoints.Ref ref) {
      if (ref.road() == null) {
         return true;
      } else {
         RoadIndex.Built old = data.index.roads.get(ref.road());
         RoadRecord now = all.get(ref.road());
         if (old != null && now != null && old.record.settings().style() == now.settings().style()) {
            try {
               return LaneRamps.port(old.record, point(old.record, ref.point())).equals(LaneRamps.port(now, point(now, ref.point())));
            } catch (IllegalArgumentException var6) {
               return false;
            }
         } else {
            return false;
         }
      }
   }

   private static void rebuild(RoadData data, UUID id, Map<UUID, RoadRecord> all, Set<UUID> done, Set<UUID> visiting, Set<UUID> scope) {
      if (!done.contains(id) && scope.contains(id)) {
         RoadRecord r = all.get(id);
         if (r == null) {
            throw new IllegalArgumentException("依赖道路已不存在：" + id);
         } else {
            LanePoints.Link l = metadata(r).link();
            if (l == null) {
               done.add(id);
            } else if (!visiting.add(id)) {
               throw new IllegalArgumentException("匝道引用出现循环依赖");
            } else {
               rebuild(data, l.from().road(), all, done, visiting, scope);
               if (l.to().road() != null) {
                  rebuild(data, l.to().road(), all, done, visiting, scope);
               }

               RoadIndex.Built existing = data.index.roads.get(id);
               if (existing == null
                  || !sameDeck(r, existing.record)
                  || !Objects.equals(l, metadata(existing.record).link())
                  || !samePort(data, all, l.from())
                  || !samePort(data, all, l.to())
                  || l.options().sourceExtra() && !sameGeometry(data, all, l.from())
                  || (l.options().targetExtra() || l.targetOffset() != 0.0) && !sameGeometry(data, all, l.to())) {
                  if (existing == null && !r.alignment().isEmpty() && portsMatch(r, all)) {
                     LaneRamps.validate(r.mesh(), all, id, l);
                     visiting.remove(id);
                     done.add(id);
                  } else if (existing == null
                     || !portsMatch(r, all)
                     || !Objects.equals(l, metadata(existing.record).link())
                     || !samePort(data, all, l.from())
                     || !samePort(data, all, l.to())
                     || l.options().sourceExtra() && !sameGeometry(data, all, l.from())
                     || (l.options().targetExtra() || l.targetOffset() != 0.0) && !sameGeometry(data, all, l.to())) {
                     RoadRecord next;
                     try {
                        next = LaneRamps.generate(data, all, r.id(), r.owner(), l);
                     } catch (IllegalArgumentException var13) {
                        throw new IllegalArgumentException("本次修改需要重建关联匝道 " + id + "，但该连接无法成立：" + var13.getMessage(), var13);
                     }

                     ArrayList<LanePoints.Point> moved = new ArrayList<>();

                     for (LanePoints.Point p : metadata(r).points()) {
                        if (!p.automatic()) {
                           moved.add(LanePoints.snap(next.mesh(), p));
                        }
                     }

                     next = next.withLanePoints(metadata(next).points(moved));
                     if (r.assembly() != null) {
                        next = next.alignment(r.assembly(), next.mesh());
                     }

                     next = next.withAttachments(r.settings().options().attachments()).furniturePhase(r.furniturePhase());
                     RoadProfile.Options options = next.settings()
                        .options()
                        .infrastructure(r.settings().options().infrastructure())
                        .laneLines(r.settings().options().laneLines())
                        .hideArrows(r.settings().options().hideArrows());
                     next = next.settings(next.settings().options(options));
                     LaneRamps.validate(next.mesh(), all, id, metadata(next).link());
                     all.put(id, next);
                     visiting.remove(id);
                     done.add(id);
                  } else {
                     if (!sameDeck(r, existing.record)) {
                        LaneRamps.validate(r.mesh(), all, id, l);
                     }

                     visiting.remove(id);
                     done.add(id);
                  }
               } else {
                  visiting.remove(id);
                  done.add(id);
               }
            }
         }
      }
   }

   static void assignPriorities(Map<UUID, RoadRecord> all) {
      HashMap<UUID, Integer> depths = new HashMap<>();
      HashSet<UUID> active = new HashSet<>();

      for (UUID id : all.keySet()) {
         priorityDepth(id, all, depths, active);
      }

      for (RoadRecord r : new ArrayList<>(all.values())) {
         all.put(r.id(), r.withLanePoints(metadata(r).priorityDepth(depths.get(r.id()))));
      }
   }

   private static int priorityDepth(UUID id, Map<UUID, RoadRecord> all, Map<UUID, Integer> depths, Set<UUID> active) {
      if (depths.containsKey(id)) {
         return depths.get(id);
      } else {
         RoadRecord r = all.get(id);
         if (r == null) {
            return 0;
         } else if (active.add(id) && active.size() <= 1024) {
            LanePoints.Link link = metadata(r).link();
            int depth = 0;
            if (link != null) {
               depth = 1
                  + Math.max(
                     priorityDepth(link.from().road(), all, depths, active),
                     link.to().road() == null ? 0 : priorityDepth(link.to().road(), all, depths, active)
                  );
            }

            active.remove(id);
            depths.put(id, depth);
            return depth;
         } else {
            throw new IllegalArgumentException("匝道依赖成环或超过 1024 层");
         }
      }
   }

   private LaneTopology() {
   }
}
