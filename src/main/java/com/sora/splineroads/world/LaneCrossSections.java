package com.sora.splineroads.world;

import com.sora.splineroads.core.ElevatedAccess;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LaneExpansion;
import com.sora.splineroads.core.LaneMerge;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadStructures;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;

public final class LaneCrossSections {
   public static Map<UUID, RoadRecord> staged(Map<UUID, RoadRecord> records, UUID edited, LanePoints.Link proposal) {
      return staged(records, edited, proposal, null);
   }

   public static Map<UUID, RoadRecord> staged(Map<UUID, RoadRecord> records, UUID edited, LanePoints.Link proposal, RoadGeometry.Mesh candidate) {
      LinkedHashMap<UUID, RoadRecord> result = new LinkedHashMap<>(records);
      Set<UUID> hosts = new HashSet<>();
      hosts.add(proposal.from().road());
      if (proposal.to().road() != null) {
         hosts.add(proposal.to().road());
      }

      RoadRecord old = records.get(edited);
      LanePoints.Link oldLink = old == null ? null : LaneTopology.metadata(old).link();
      if (oldLink != null) {
         hosts.add(oldLink.from().road());
         if (oldLink.to().road() != null) {
            hosts.add(oldLink.to().road());
         }
      }

      derive(result, edited, proposal, hosts, candidate);
      return result;
   }

   public static void reconcile(Map<UUID, RoadRecord> records) {
      derive(records, null, null, null, null);
   }

   static void reconcile(Map<UUID, RoadRecord> records, Set<UUID> hosts) {
      derive(records, null, null, hosts, null);
   }

   private static void derive(Map<UUID, RoadRecord> records, UUID edited, LanePoints.Link proposal, Set<UUID> hosts, RoadGeometry.Mesh candidate) {
      if (hosts != null) {
         HashSet<UUID> expanded = new HashSet<>(hosts);
         HashSet<UUID> removedConnections = new HashSet<>();
         HashSet<UUID> splitSources = new HashSet<>();

         for (UUID host : hosts) {
            RoadRecord r = records.get(host);
            if (r != null && r.fragment() != null) {
               splitSources.add(r.fragment().source());
            }
         }

         for (RoadRecord r : records.values()) {
            if (r.fragment() != null && splitSources.contains(r.fragment().source())) {
               expanded.add(r.id());
            }
         }

         for (UUID hostx : hosts) {
            RoadRecord rx = records.get(hostx);
            if (rx != null) {
               for (LaneSections.Cut cut : LaneTopology.metadata(rx).cuts()) {
                  if (!records.containsKey(cut.connection())) {
                     removedConnections.add(cut.connection());
                  }
               }
            }
         }

         for (RoadRecord rx : records.values()) {
            if (LaneTopology.metadata(rx).cuts().stream().anyMatch(c -> removedConnections.contains(c.connection()))) {
               expanded.add(rx.id());
            }
         }

         for (RoadRecord road : records.values()) {
            LanePoints.Link link = LaneTopology.metadata(road).link();
            if (link != null && (expanded.contains(link.from().road()) || expanded.contains(link.to().road()) || road.id().equals(edited))) {
               expanded.addAll(LaneRoadChain.of(records, link.from()).ids());
               if (link.to().road() != null) {
                  expanded.addAll(LaneRoadChain.of(records, link.to()).ids());
               }
            }

            for (LaneSections.Cut cutx : LaneTopology.metadata(road).cuts()) {
               if (cutx.connection().equals(edited)) {
                  expanded.add(road.id());
               }
            }
         }

         if (proposal != null) {
            expanded.addAll(LaneRoadChain.of(records, proposal.from()).ids());
            if (proposal.to().road() != null) {
               expanded.addAll(LaneRoadChain.of(records, proposal.to()).ids());
            }
         }

         hosts = expanded;
      }

      deriveAdditions(records, edited, proposal, hosts);
      HashMap<UUID, List<LaneSections.Event>> events = new HashMap<>();

      for (RoadRecord road : records.values()) {
         if (!road.id().equals(edited)) {
            LanePoints.Link link = LaneTopology.metadata(road).link();
            if (link != null && (hosts == null || hosts.contains(link.from().road()) || hosts.contains(link.to().road()))) {
               add(
                  events,
                  records,
                  road.id(),
                  link,
                  hosts,
                  link.options().departure() != LanePoints.Departure.TEMPORARY && !link.closesTarget() ? null : road.rawMesh(),
                  road.structures()
               );
            }
         }
      }

      for (RoadRecord roadx : records.values()) {
         if (hosts == null || hosts.contains(roadx.id())) {
            for (LanePoints.Point point : LaneTopology.metadata(roadx).points()) {
               if (point.mergeLength() > 0.0) {
                  LaneSections.Event event = LaneMerge.event(roadx.rawMesh(), point);
                  events.computeIfAbsent(roadx.id(), k -> new ArrayList<>()).add(event);
               }
            }
         }
      }

      if (proposal != null) {
         add(events, records, edited, proposal, hosts, candidate, List.of());
      }

      Map<UUID, List<LaneSections.Cut>> derivedCuts = deriveCuts(records, events, hosts);

      for (RoadRecord roadxx : new ArrayList<>(records.values())) {
         if (hosts == null || hosts.contains(roadxx.id())) {
            LanePoints.Data md = LaneTopology.metadata(roadxx);
            ArrayList<LaneSections.Cut> cuts = new ArrayList<>(derivedCuts.getOrDefault(roadxx.id(), List.of()));
            List<ElevatedAccess.Reservation> access = md.access().stream().filter(rxx -> records.containsKey(rxx.connection())).toList();

            for (ElevatedAccess.Reservation reservation : access) {
               for (LaneSections.Cut cutxx : reservation.cuts()) {
                  for (LaneSections.Cut prior : cuts) {
                     if (prior.lane() == cutxx.lane()
                        && Math.min(Math.max(prior.begin(), prior.end()), Math.max(cutxx.begin(), cutxx.end()))
                              - Math.max(Math.min(prior.begin(), prior.end()), Math.min(cutxx.begin(), cutxx.end()))
                           > 0.01) {
                        throw new IllegalArgumentException("高架出入口与既有车道封闭区间重叠");
                     }
                  }

                  cuts.add(cutxx);
               }
            }

            if (!access.equals(md.access())) {
               md = md.access(access);
               roadxx = roadxx.withLanePoints(md);
               records.put(roadxx.id(), roadxx);
            }

            if (!cuts.equals(md.cuts())) {
               records.put(roadxx.id(), roadxx.withLanePoints(md.cuts(cuts)));
            }
         }
      }

      for (RoadRecord roadxxx : records.values()) {
         if ((hosts == null || hosts.contains(roadxxx.id())) && !LaneTopology.metadata(roadxxx).additions().isEmpty()) {
            LaneAdditions.validate(roadxxx.mesh());
         }
      }

      for (UUID id : events.keySet()) {
         RoadRecord hostxx = records.get(id);
         RoadGeometry.Mesh effective = hostxx.mesh();

         for (LanePoints.Point pointx : LaneTopology.metadata(hostxx).points()) {
            if (pointx.mergeLength() > 0.0) {
               LaneMerge.event(effective, pointx);
            }
         }
      }
   }

   private static Map<UUID, List<LaneSections.Cut>> deriveCuts(Map<UUID, RoadRecord> records, Map<UUID, List<LaneSections.Event>> events, Set<UUID> hosts) {
      HashMap<UUID, List<LaneSections.Event>> routed = new HashMap<>();
      events.forEach((id, list) -> routed.put(id, new ArrayList<>(list)));
      LinkedHashMap<UUID, List<RoadRecord>> groups = new LinkedHashMap<>();

      for (RoadRecord road : records.values()) {
         if (road.fragment() != null && (hosts == null || hosts.contains(road.id()))) {
            groups.computeIfAbsent(road.fragment().source(), key -> new ArrayList<>()).add(road);
         }
      }

      for (List<RoadRecord> siblings : groups.values()) {
         ArrayList<LaneCrossSections.SplitEvent> departures = new ArrayList<>();
         ArrayList<LaneCrossSections.SplitEvent> replacements = new ArrayList<>();

         for (RoadRecord roadx : siblings) {
            List<LaneSections.Event> list = routed.computeIfAbsent(roadx.id(), key -> new ArrayList<>());

            for (LaneSections.Event event : list) {
               if (event.kind() == LaneSections.Kind.DEPART) {
                  departures.add(new LaneCrossSections.SplitEvent(roadx, event));
               }

               if (event.kind() == LaneSections.Kind.REPLACE) {
                  replacements.add(new LaneCrossSections.SplitEvent(roadx, event));
               }
            }

            list.removeIf(event -> event.kind() == LaneSections.Kind.REPLACE);
         }

         HashSet<UUID> used = new HashSet<>();

         for (LaneCrossSections.SplitEvent departure : departures) {
            LaneSections.Event e = departure.event();
            LaneCrossSections.SplitEvent replacement = null;
            double distance = Double.POSITIVE_INFINITY;

            for (LaneCrossSections.SplitEvent candidate : replacements) {
               LaneSections.Event r = candidate.event();
               double next = (double)e.sign() * (candidate.station() - departure.station());
               if (r.lane() == e.lane() && r.sign() == e.sign() && next > 1.0E-4 && next < distance) {
                  replacement = candidate;
                  distance = next;
               }
            }

            if (replacement != null) {
               LaneSections.Event r = replacement.event();
               if (!used.add(r.connection())) {
                  throw new IllegalArgumentException("补入车道同时匹配多个分离接头");
               }

               double station = replacement.station() - departure.road().fragment().origin();
               routed.get(departure.road().id())
                  .add(
                     new LaneSections.Event(
                        r.connection(), r.kind(), r.lane(), r.sign(), station, r.transition(), r.returnStation(), r.rectangular(), r.underpass()
                     )
                  );
            }
         }

         for (LaneCrossSections.SplitEvent replacement : replacements) {
            if (!used.contains(replacement.event().connection())) {
               throw new IllegalArgumentException("补入模式需要上游已有整车道分离或车道点合流缩减形成空位；普通汇入请选并入现有车道");
            }
         }
      }

      LinkedHashMap<UUID, List<LaneSections.Cut>> owned = new LinkedHashMap<>();

      for (RoadRecord roadx : records.values()) {
         if (hosts == null || hosts.contains(roadx.id())) {
            List<LaneSections.Event> values = routed.getOrDefault(roadx.id(), List.of());
            List<LaneSections.Cut> cuts = values.isEmpty() ? List.of() : LaneSections.derive(roadx.rawMesh(), values);
            if (roadx.fragment() != null) {
               cuts = cuts.stream()
                  .map(
                     c -> {
                        if (!c.temporary() && c.replacement() == null) {
                           double end = (c.sign() > 0 ? roadx.fragment().total() + 2.0 * c.transition() : -2.0 * c.transition()) - roadx.fragment().origin();
                           return new LaneSections.Cut(
                              c.connection(), c.lane(), c.sign(), c.begin(), end, c.transition(), null, false, c.arrival(), c.rectangular(), c.underpass()
                           );
                        } else {
                           return (LaneSections.Cut)c;
                        }
                     }
                  )
                  .toList();
            }

            owned.put(roadx.id(), cuts);
         }
      }

      HashMap<UUID, List<LaneSections.Cut>> result = new HashMap<>();
      owned.forEach((id, list) -> result.put(id, new ArrayList<>(list)));

      for (List<RoadRecord> siblings : groups.values()) {
         ArrayList<LaneSections.Cut> global = new ArrayList<>();

         for (RoadRecord source : siblings) {
            for (LaneSections.Cut cut : owned.getOrDefault(source.id(), List.of())) {
               if (!cut.temporary()) {
                  LaneSections.Cut translated = shift(cut, source.fragment().origin());

                  for (LaneSections.Cut previous : global) {
                     if (previous.lane() == cut.lane()
                        && previous.sign() == cut.sign()
                        && !previous.connection().equals(cut.connection())
                        && Math.min(Math.max(previous.begin(), previous.end()), Math.max(translated.begin(), translated.end()))
                              - Math.max(Math.min(previous.begin(), previous.end()), Math.min(translated.begin(), translated.end()))
                           > 0.01) {
                        throw new IllegalArgumentException("同一车道空位内重复整车道分离；Y 分叉请使用普通分流（原车道直行）");
                     }
                  }

                  global.add(translated);
               }
            }
         }

         for (RoadRecord target : siblings) {
            List<LaneSections.Cut> list = result.get(target.id());
            list.removeIf(c -> !c.temporary());

            for (LaneSections.Cut cutx : global) {
               LaneSections.Cut translated = shift(cutx, -target.fragment().origin());
               if (Math.max(translated.begin(), translated.end()) > 0.0 && Math.min(translated.begin(), translated.end()) < target.rawMesh().length()) {
                  list.add(translated);
               }
            }
         }
      }

      result.replaceAll(
         (id, list) -> {
            if (records.get(id).fragment() == null) {
               return List.copyOf(list);
            } else {
               List<LaneSections.Cut> old = LaneTopology.metadata(records.get(id)).cuts();
               List<LaneSections.Cut> stable = list.stream()
                  .map(
                     c -> old.stream()
                           .filter(p -> sameCut(p, c) && Math.abs(p.begin() - c.begin()) < 1.0E-7 && Math.abs(p.end() - c.end()) < 1.0E-7)
                           .findFirst()
                           .orElse(c)
                  )
                  .toList();
               return stable.size() == old.size() && new HashSet<>(stable).equals(new HashSet<>(old)) ? old : stable;
            }
         }
      );
      return result;
   }

   private static LaneSections.Cut shift(LaneSections.Cut c, double offset) {
      return new LaneSections.Cut(
         c.connection(),
         c.lane(),
         c.sign(),
         c.begin() + offset,
         c.end() + offset,
         c.transition(),
         c.replacement(),
         c.temporary(),
         c.arrival(),
         c.rectangular(),
         c.underpass()
      );
   }

   private static boolean sameCut(LaneSections.Cut a, LaneSections.Cut b) {
      return a.connection().equals(b.connection())
         && a.lane() == b.lane()
         && a.sign() == b.sign()
         && a.transition() == b.transition()
         && Objects.equals(a.replacement(), b.replacement())
         && a.temporary() == b.temporary()
         && a.arrival() == b.arrival()
         && a.rectangular() == b.rectangular()
         && a.underpass() == b.underpass();
   }

   private static void deriveAdditions(Map<UUID, RoadRecord> records, UUID edited, LanePoints.Link proposal, Set<UUID> hosts) {
      LinkedHashMap<UUID, LanePoints.Link> requests = new LinkedHashMap<>();

      for (RoadRecord road : records.values()) {
         if (!road.id().equals(edited)) {
            LanePoints.Link l = LaneTopology.metadata(road).link();
            if (l != null && l.options().arrival() == LanePoints.Arrival.ADD) {
               requests.put(road.id(), l);
            }
         }
      }

      if (proposal != null && proposal.options().arrival() == LanePoints.Arrival.ADD) {
         requests.put(edited, proposal);
      }

      HashMap<UUID, List<LaneAdditions.Addition>> additions = new HashMap<>();

      for (Entry<UUID, LanePoints.Link> entry : requests.entrySet()) {
         LanePoints.Link link = entry.getValue();
         if (link.to().road() == null) {
            throw new IllegalArgumentException("新增外侧车道需要选择道路车道点，不能使用路口中心");
         }

         if (hosts == null || hosts.contains(link.to().road())) {
            LaneRoadChain.Position resolved = LaneRoadChain.of(records, link.to()).at(link.targetOffset());
            RoadRecord roadx = resolved.road();
            LanePoints.Point point = resolved.point();
            RoadGeometry.Mesh raw = roadx.rawMesh();
            LanePoints.Lane lane = LanePoints.lane(raw, point);
            double station = resolved.station();
            if (station < -0.001 || station > raw.length() + 0.001) {
               throw new IllegalArgumentException("新增车道汇入口超出所选道路");
            }

            List<LaneAdditions.Addition> list = additions.computeIfAbsent(roadx.id(), k -> new ArrayList<>());
            LaneAdditions.Addition previous = LaneTopology.metadata(roadx)
               .additions()
               .stream()
               .filter(ax -> ax.connection().equals(entry.getKey()))
               .findFirst()
               .orElse(null);
            int slot = previous == null ? 16 : previous.slot();
            if (previous == null) {
               HashSet<Integer> reserved = new HashSet<>();

               for (LaneAdditions.Addition a : LaneTopology.metadata(roadx).additions()) {
                  reserved.add(a.slot());
               }

               for (LaneAdditions.Addition a : list) {
                  reserved.add(a.slot());
               }

               while (reserved.contains(slot)) {
                  slot++;
               }
            }

            if (slot > 31) {
               throw new IllegalArgumentException("此路段新增车道身份数量达到上限，请分段建设");
            }

            list.add(new LaneAdditions.Addition(entry.getKey(), slot, lane.sign(), station, link.options().transition()));
         }
      }

      for (RoadRecord roadxx : records.values()) {
         if (hosts == null || hosts.contains(roadxx.id())) {
            for (LanePoints.Point pointx : LaneTopology.metadata(roadxx).points()) {
               if (pointx.mergeLength() < 0.0) {
                  List<LaneAdditions.Addition> listx = additions.computeIfAbsent(roadxx.id(), k -> new ArrayList<>());
                  LaneAdditions.Addition previousx = LaneTopology.metadata(roadxx)
                     .additions()
                     .stream()
                     .filter(ax -> ax.connection().equals(pointx.id()))
                     .findFirst()
                     .orElse(null);
                  int slotx = previousx == null ? 16 : previousx.slot();
                  if (previousx == null) {
                     HashSet<Integer> used = new HashSet<>();

                     for (LaneAdditions.Addition a : LaneTopology.metadata(roadxx).additions()) {
                        used.add(a.slot());
                     }

                     for (LaneAdditions.Addition a : listx) {
                        used.add(a.slot());
                     }

                     while (used.contains(slotx)) {
                        slotx++;
                     }
                  }

                  if (slotx > 31) {
                     throw new IllegalArgumentException("新增车道身份数量达到上限");
                  }

                  listx.add(LaneExpansion.addition(roadxx.rawMesh(), pointx, slotx));
               }
            }
         }
      }

      HashMap<UUID, List<LaneAdditions.Addition>> derived = new HashMap<>();
      additions.forEach((id, listxx) -> derived.put(id, List.copyOf(listxx)));

      for (Entry<UUID, List<LaneAdditions.Addition>> entry : derived.entrySet()) {
         RoadRecord source = records.get(entry.getKey());
         if (source.fragment() != null) {
            for (RoadRecord target : records.values()) {
               if (!target.id().equals(source.id())
                  && target.fragment() != null
                  && source.fragment().source().equals(target.fragment().source())
                  && (hosts == null || hosts.contains(target.id()))) {
                  for (LaneAdditions.Addition value : entry.getValue()) {
                     LaneAdditions.Addition next = new LaneAdditions.Addition(
                        value.connection(),
                        value.slot(),
                        value.sign(),
                        value.station() + source.fragment().origin() - target.fragment().origin(),
                        value.transition(),
                        value.end() + source.fragment().origin() - target.fragment().origin()
                     );
                     if (next.fraction(0.0) > 0.0 || next.fraction(target.rawMesh().length()) > 0.0) {
                        List<LaneAdditions.Addition> listxx = additions.computeIfAbsent(target.id(), k -> new ArrayList<>());
                        if (listxx.stream().noneMatch(ax -> ax.connection().equals(next.connection()))) {
                           listxx.add(next);
                        }
                     }
                  }
               }
            }
         }
      }

      for (RoadRecord roadxxx : new ArrayList<>(records.values())) {
         if (hosts == null || hosts.contains(roadxxx.id())) {
            List<LaneAdditions.Addition> listxx = new ArrayList<>(additions.getOrDefault(roadxxx.id(), List.of()));
            LanePoints.Data md = LaneTopology.metadata(roadxxx);

            for (ElevatedAccess.Reservation access : md.access()) {
               if (records.containsKey(access.connection())) {
                  listxx.addAll(access.additions());
               }
            }

            if (roadxxx.fragment() != null) {
               listxx = listxx.stream()
                  .map(
                     valuex -> md.additions()
                           .stream()
                           .filter(
                              old -> old.connection().equals(valuex.connection())
                                    && old.slot() == valuex.slot()
                                    && old.sign() == valuex.sign()
                                    && old.transition() == valuex.transition()
                                    && Math.abs(old.station() - valuex.station()) < 1.0E-7
                           )
                           .findFirst()
                           .orElse(valuex)
                  )
                  .toList();
            }

            if (!listxx.equals(md.additions())) {
               records.put(roadxxx.id(), roadxxx.withLanePoints(md.additions(listxx)));
            }
         }
      }
   }

   private static void add(
      Map<UUID, List<LaneSections.Event>> events,
      Map<UUID, RoadRecord> all,
      UUID connection,
      LanePoints.Link link,
      Set<UUID> hosts,
      RoadGeometry.Mesh candidate,
      List<RoadStructures.Part> parts
   ) {
      if (link != null) {
         if (link.options().separatesLane() && (hosts == null || hosts.contains(link.from().road()))) {
            if (link.options().departure() == LanePoints.Departure.DETACH) {
               add(events, all, connection, link.from(), LaneSections.Kind.DEPART, 0.0, link.options().transition(), link.rectangularClosure());
            } else if (candidate != null) {
               for (int lane = 0; lane < link.options().lanes(); lane++) {
                  LaneRoadChain.of(all, link.from(), lane)
                     .reserve(events, connection, candidate, parts, false, 0.0, link.options().transition(), link.rectangularClosure());
               }
            }
         }

         if (candidate != null && link.closesTarget() && (hosts == null || hosts.contains(link.to().road()))) {
            for (int lane = 0; lane < link.options().lanes(); lane++) {
               LaneRoadChain.of(all, link.to(), lane)
                  .reserve(events, connection, candidate, parts, true, link.targetOffset(), link.options().transition(), link.rectangularClosure());
            }
         }

         if (link.options().arrival() == LanePoints.Arrival.ADD && (hosts == null || hosts.contains(link.to().road()))) {
            LaneRoadChain.Position location = LaneRoadChain.of(all, link.to()).at(link.targetOffset());
            RoadRecord road = location.road();
            LaneAdditions.Addition addition = LaneAdditions.owned(road.rawMesh(), connection);
            double start = Math.max(0.0, Math.min(road.rawMesh().length(), addition.station() - (double)addition.sign() * addition.transition()));
            events.computeIfAbsent(road.id(), k -> new ArrayList<>())
               .add(
                  new LaneSections.Event(
                     connection, LaneSections.Kind.ARRIVE, addition.slot(), addition.sign(), addition.station(), addition.transition(), start, true
                  )
               );
         }

         if (link.options().arrival() == LanePoints.Arrival.REPLACE && (hosts == null || hosts.contains(link.to().road()))) {
            if (link.to().road() == null) {
               throw new IllegalArgumentException("路口中心不能作为车道空位补入目标");
            }

            add(events, all, connection, link.to(), LaneSections.Kind.REPLACE, link.targetOffset(), link.options().transition());
         }
      }
   }

   private static void add(
      Map<UUID, List<LaneSections.Event>> events,
      Map<UUID, RoadRecord> all,
      UUID connection,
      LanePoints.Ref ref,
      LaneSections.Kind kind,
      double offset,
      double transition
   ) {
      add(events, all, connection, ref, kind, offset, transition, false);
   }

   private static void add(
      Map<UUID, List<LaneSections.Event>> events,
      Map<UUID, RoadRecord> all,
      UUID connection,
      LanePoints.Ref ref,
      LaneSections.Kind kind,
      double offset,
      double transition,
      boolean rectangular
   ) {
      RoadRecord road = all.get(ref.road());
      if (road == null) {
         throw new IllegalArgumentException("车道接头引用的道路已不存在");
      } else {
         LanePoints.Point point = LaneTopology.point(road, ref.point());
         LanePoints.Lane lane = LanePoints.lane(road.rawMesh(), point);
         double station = lane.station() + offset * (double)lane.sign();
         if (!(station < -0.001) && !(station > road.rawMesh().length() + 0.001)) {
            events.computeIfAbsent(road.id(), key -> new ArrayList<>())
               .add(new LaneSections.Event(connection, kind, point.lane(), lane.sign(), station, transition, Double.NaN, rectangular));
         } else {
            throw new IllegalArgumentException("车道补入位置超出所属路段");
         }
      }
   }

   static boolean needsRestoreRefresh(List<RoadIndex.Built> planning, Collection<UUID> changed) {
      Set<UUID> changes = new HashSet<>(changed);
      Set<UUID> hosts = new HashSet<>();
      LinkedHashMap<UUID, RoadRecord> all = new LinkedHashMap<>();

      for (RoadIndex.Built b : planning) {
         all.put(b.record.id(), b.record);
      }

      for (RoadRecord r : all.values()) {
         LanePoints.Link l = LaneTopology.metadata(r).link();
         if (l != null) {
            if (l.options().departure() == LanePoints.Departure.TEMPORARY && (changes.contains(r.id()) || changes.contains(l.from().road()))) {
               hosts.add(l.from().road());
            }

            if (l.closesTarget() && (changes.contains(r.id()) || changes.contains(l.to().road()))) {
               hosts.add(l.to().road());
            }
         }
      }

      if (hosts.isEmpty()) {
         return false;
      } else {
         LinkedHashMap<UUID, RoadRecord> staged = new LinkedHashMap<>(all);
         derive(staged, null, null, hosts, null);

         for (UUID host : all.keySet()) {
            if (!LaneTopology.metadata(all.get(host)).cuts().equals(LaneTopology.metadata(staged.get(host)).cuts())) {
               return true;
            }
         }

         return false;
      }
   }

   private LaneCrossSections() {
   }

   private static record SplitEvent(RoadRecord road, LaneSections.Event event) {
      double station() {
         return this.event.station() + this.road.fragment().origin();
      }
   }
}
