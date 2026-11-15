package com.sora.splineroads.world;

import com.sora.splineroads.core.ElevatedAccess;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadContinuity;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadStructures;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;

final class ElevatedAccessChain {
   static ElevatedAccessChain.Result plan(
      RoadData data, RoadRecord host, RoadAttachments.Point marker, UUID id, RoadGeometry.Node end, RoadGeometry.Settings settings, boolean first
   ) {
      RoadGeometry.Mesh root = host.rawMesh();
      double station = RoadQueries.horizontal(root, marker.position()).sample().distance();

      try {
         ElevatedAccess.Plan plan = ElevatedAccess.plan(id, marker.id(), root, station, end, settings, first);
         return new ElevatedAccessChain.Result(plan, Map.of(host.id(), plan.reservation()));
      } catch (IllegalArgumentException var35) {
         ElevatedAccessChain.Section middle = new ElevatedAccessChain.Section(host, host.caps(0).rawMesh(), true);
         if (available(host, id) && RoadContinuity.eligible(middle.raw())) {
            ArrayDeque<ElevatedAccessChain.Section> sections = new ArrayDeque<>();
            sections.add(middle);
            HashSet<UUID> seen = new HashSet<>();
            seen.add(host.id());

            for (boolean before : new boolean[]{true, false}) {
               ElevatedAccessChain.Section previous = middle;
               double reach = 0.0;

               while (sections.size() < 64 && reach < end.position().distance(marker.position()) + 160.0) {
                  boolean atA = before == previous.forward();
                  BlockPos seam = atA ? previous.road().a() : previous.road().b();
                  Set<UUID> touching = data.index.atNode(seam);
                  if (touching.size() != 2 || AutoJunctions.center(data, seam) != null) {
                     break;
                  }

                  UUID candidate = touching.stream().filter(v -> !seen.contains(v)).findFirst().orElse(null);
                  if (candidate == null) {
                     break;
                  }

                  RoadRecord r = data.index.roads.get(candidate).record;
                  if (!r.owner().equals(host.owner())
                     || !ElevatedPointTool.supported(r)
                     || !available(r, id)
                     || r.settings().structure() != host.settings().structure()
                     || r.settings().style() != host.settings().style()) {
                     break;
                  }

                  RoadGeometry.Mesh raw = r.caps(0).rawMesh();
                  boolean nextA = r.a().equals(seam);
                  RoadGeometry.Sample port = atA ? previous.raw().first() : previous.raw().last();
                  if (!RoadContinuity.compatible(previous.raw(), raw, port.center())
                     || !LanePoints.sameSection(previous.raw(), port.distance(), raw, nextA ? 0.0 : raw.length())
                     || Math.abs(raw.first().halfWidth() - middle.raw().first().halfWidth()) > 0.01) {
                     break;
                  }

                  previous = new ElevatedAccessChain.Section(r, raw, before ? !nextA : nextA);
                  seen.add(candidate);
                  reach += raw.length();
                  if (before) {
                     sections.addFirst(previous);
                  } else {
                     sections.addLast(previous);
                  }
               }
            }

            if (sections.size() == 1) {
               throw var35;
            } else {
               ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

               for (ElevatedAccessChain.Section section : sections) {
                  List<RoadGeometry.Sample> part = section.raw().samples();

                  for (int i = samples.isEmpty() ? 0 : 1; i < part.size(); i++) {
                     RoadGeometry.Sample s = part.get(section.forward() ? i : part.size() - 1 - i);
                     samples.add(new RoadGeometry.Sample(s.center(), section.forward() ? s.left() : s.left().mul(-1.0), 0.0, s.halfWidth()));
                  }
               }

               RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, host.settings());
               ElevatedAccess.Plan planx = ElevatedAccess.plan(id, marker.id(), mesh, RoadAttachments.station(mesh, marker.position()), end, settings, first);
               LinkedHashMap<UUID, ElevatedAccess.Reservation> result = new LinkedHashMap<>();
               double start = 0.0;

               for (ElevatedAccessChain.Section part : sections) {
                  double finish = start + part.raw().length();
                  ElevatedAccess.Reservation rx = planx.reservation();
                  double lo = start;
                  boolean occupied = rx.cuts().stream().anyMatch(c -> Math.min(c.begin(), c.end()) < finish && Math.max(c.begin(), c.end()) > lo)
                     || rx.additions()
                        .stream()
                        .anyMatch(a -> Math.min(a.station(), a.end()) - a.transition() < finish && Math.max(a.station(), a.end()) + a.transition() > lo);
                  if (occupied) {
                     RoadGeometry.Mesh localMesh = part.road().rawMesh();
                     RoadGeometry.Sample at = part.forward() ? part.raw().first() : part.raw().last();
                     double zero = RoadAttachments.station(localMesh, at.center());
                     ElevatedAccess.Reservation local = ElevatedAccess.shifted(rx, -start);
                     if (!part.forward()) {
                        local = reverse(local, mesh, finish);
                     }

                     result.put(part.road().id(), ElevatedAccess.shifted(local, zero));
                  }

                  start = finish;
               }

               return new ElevatedAccessChain.Result(planx, result);
            }
         } else {
            throw var35;
         }
      }
   }

   private static boolean available(RoadRecord r, UUID id) {
      LanePoints.Data m = LaneTopology.metadata(r);
      return m.link() == null
         && m.cuts().stream().allMatch(c -> c.connection().equals(id))
         && m.additions().stream().allMatch(a -> a.connection().equals(id))
         && m.openings().stream().allMatch(o -> o.connection().equals(id))
         && m.access().stream().allMatch(a -> a.connection().equals(id))
         && m.bands().isEmpty();
   }

   private static ElevatedAccess.Reservation reverse(ElevatedAccess.Reservation r, RoadGeometry.Mesh chain, double station) {
      RoadProfile.Layout old = RoadProfile.layout(chain, RoadStructures.sample(chain, station));
      int low = old.lanesOnSide(-1);
      int high = old.lanesOnSide(1);
      List<LaneSections.Cut> cuts = r.cuts()
         .stream()
         .map(
            c -> new LaneSections.Cut(
                  c.connection(),
                  c.lane() < low ? high + c.lane() : c.lane() - low,
                  -c.sign(),
                  -c.begin(),
                  -c.end(),
                  c.transition(),
                  c.replacement(),
                  c.temporary(),
                  c.arrival(),
                  c.rectangular(),
                  c.underpass()
               )
         )
         .toList();
      List<LaneAdditions.Addition> adds = r.additions()
         .stream()
         .map(a -> new LaneAdditions.Addition(a.connection(), a.slot(), -a.sign(), -a.station(), a.transition(), -a.end()))
         .toList();
      List<ElevatedAccess.Gap> gaps = r.gaps().stream().map(g -> new ElevatedAccess.Gap(-g.to(), -g.from(), -g.high(), -g.low())).toList();
      return new ElevatedAccess.Reservation(r.connection(), r.point(), cuts, adds, gaps);
   }

   private ElevatedAccessChain() {
   }

   static record Result(ElevatedAccess.Plan plan, Map<UUID, ElevatedAccess.Reservation> reservations) {
   }

   static record Section(RoadRecord road, RoadGeometry.Mesh raw, boolean forward) {
   }
}
