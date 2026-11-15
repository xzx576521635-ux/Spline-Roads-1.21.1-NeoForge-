package com.sora.splineroads.world;

import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RailwayCrossings;
import com.sora.splineroads.core.RoadAutoTunnels;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadsideBypass;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

final class RoadCycleChain {
   private final List<RoadCycleChain.Leg> legs;
   private final RoadGeometry.Mesh mesh;

   private RoadCycleChain(List<RoadCycleChain.Leg> legs, RoadGeometry.Mesh mesh) {
      this.legs = List.copyOf(legs);
      this.mesh = mesh;
   }

   List<RoadCycleChain.Leg> legs() {
      return this.legs;
   }

   RoadGeometry.Mesh mesh() {
      return this.mesh;
   }

   static void checkRamp(Map<UUID, RoadRecord> all, UUID id, RoadGeometry.Mesh ramp) {
      HashSet<UUID> checked = new HashSet<>();

      for (RoadRecord road : all.values()) {
         if (eligible(road) && !checked.contains(road.id()) && RoadIndex.overlapXZ(road.rawMesh(), ramp, 3.0)) {
            RoadCycleChain chain = of(all, road.id());
            ArrayList<RoadsideBypass.Neighbor> neighbors = new ArrayList<>();
            neighbors.add(new RoadsideBypass.Neighbor(ramp, List.of()));

            for (RoadRecord other : all.values()) {
               if (!other.id().equals(id)
                  && (other.settings().style().ramp() || other.railway() || LaneTopology.metadata(other).link() != null)
                  && RoadIndex.overlapXZ(chain.mesh(), other.mesh(), 3.0)) {
                  neighbors.add(new RoadsideBypass.Neighbor(other.mesh(), RoadAutoTunnels.roofRegions(other.structures())));
               }
            }

            List<RoadsideBypass.Span> spans;
            try {
               spans = RoadsideBypass.plan(chain.mesh(), neighbors);
            } catch (RoadsideBypass.ValidationException var11) {
               if (Boolean.getBoolean("sr.profile")) {
                  System.getLogger("SplineRoads/planner")
                     .log(
                        Level.INFO,
                        "SR cycle rejected road="
                           + road.id()
                           + " length="
                           + chain.mesh().length()
                           + " reason="
                           + var11.reason()
                           + " from="
                           + var11.stationFrom()
                           + " to="
                           + var11.stationTo()
                           + " slope="
                           + var11.slope()
                     );
               }

               throw var11;
            }

            for (RoadCycleChain.Leg leg : chain.legs()) {
               checked.add(leg.road().id());
               RoadsideBypass.validateSection(leg.road().mesh(), chain.local(leg.road().id(), spans));
            }
         }
      }
   }

   static boolean eligible(RoadRecord road) {
      return road.assembly() == null && road.junction() == null && !road.railway() && !road.rawMesh().closed() && RoadsideBypass.eligible(road.rawMesh());
   }

   static RoadCycleChain of(Map<UUID, RoadRecord> all, UUID id) {
      RoadRecord root = all.get(id);
      if (root != null && eligible(root)) {
         HashMap<BlockPos, List<RoadRecord>> incident = new HashMap<>();

         for (RoadRecord road : all.values()) {
            if (road.assembly() == null && road.junction() == null) {
               incident.computeIfAbsent(road.a(), k -> new ArrayList<>()).add(road);
               if (!road.a().equals(road.b())) {
                  incident.computeIfAbsent(road.b(), k -> new ArrayList<>()).add(road);
               }
            }
         }

         LinkedHashMap<UUID, RoadRecord> group = new LinkedHashMap<>();
         ArrayDeque<RoadRecord> queue = new ArrayDeque<>();
         queue.add(root);
         double length = 0.0;
         int samples = 0;

         while (!queue.isEmpty()) {
            RoadPlanningBudget.check();
            RoadRecord roadx = queue.remove();
            if (group.putIfAbsent(roadx.id(), roadx) == null) {
               length += roadx.rawMesh().length();
               samples += roadx.rawMesh().samples().size();
               if (group.size() > 64 || length > 4096.0 || samples > 16384) {
                  return single(root);
               }

               for (BlockPos node : List.of(roadx.a(), roadx.b())) {
                  List<RoadRecord> neighbors = incident.getOrDefault(node, List.of());
                  if (neighbors.size() == 2) {
                     for (RoadRecord next : neighbors) {
                        if (!group.containsKey(next.id()) && compatible(roadx, next, node)) {
                           queue.add(next);
                        }
                     }
                  }
               }
            }
         }

         if (group.size() == 1) {
            return single(root);
         } else {
            RoadRecord seed = group.values().stream().min(Comparator.comparing(RoadRecord::id)).orElseThrow();
            ArrayList<RoadCycleChain.Leg> ordered = new ArrayList<>();
            ordered.add(new RoadCycleChain.Leg(seed, 1, 0.0));
            HashSet<UUID> used = new HashSet<>();
            used.add(seed.id());

            for (int direction : new int[]{-1, 1}) {
               RoadCycleChain.Leg current = new RoadCycleChain.Leg(seed, 1, 0.0);

               for (int count = 0; count < group.size(); count++) {
                  BlockPos nodex = current.sign() * direction > 0 ? current.road().b() : current.road().a();
                  RoadRecord nextx = null;

                  for (RoadRecord r : incident.getOrDefault(nodex, List.of())) {
                     if (group.containsKey(r.id()) && !used.contains(r.id()) && compatible(current.road(), r, nodex)) {
                        nextx = r;
                        break;
                     }
                  }

                  if (nextx == null) {
                     break;
                  }

                  int sign = (nextx.a().equals(nodex) ? 1 : -1) * direction;
                  RoadCycleChain.Leg leg = new RoadCycleChain.Leg(nextx, sign, 0.0);
                  if (direction < 0) {
                     ordered.add(0, leg);
                  } else {
                     ordered.add(leg);
                  }

                  used.add(nextx.id());
                  current = leg;
               }
            }

            if (used.size() != group.size()) {
               return single(root);
            } else {
               ArrayList<RoadGeometry.Sample> points = new ArrayList<>();
               ArrayList<RoadCycleChain.Leg> result = new ArrayList<>();
               double offset = 0.0;

               for (RoadCycleChain.Leg leg : ordered) {
                  RoadGeometry.Mesh raw = clean(leg.road());
                  result.add(new RoadCycleChain.Leg(leg.road(), leg.sign(), offset));
                  ArrayList<RoadGeometry.Sample> list = new ArrayList<>(raw.samples());
                  if (leg.sign() < 0) {
                     Collections.reverse(list);
                  }

                  for (RoadGeometry.Sample sample : list) {
                     double station = offset + (leg.sign() > 0 ? sample.distance() : raw.length() - sample.distance());
                     if (points.isEmpty() || !(station - points.get(points.size() - 1).distance() < 1.0E-7)) {
                        points.add(new RoadGeometry.Sample(sample.center(), sample.left().mul((double)leg.sign()), station, sample.halfWidth()));
                     }
                  }

                  offset += raw.length();
               }

               RoadGeometry.Settings settings = clean(ordered.get(0).road()).settings();
               RoadGeometry.V min = new RoadGeometry.V(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
               RoadGeometry.V max = new RoadGeometry.V(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE);

               for (RoadGeometry.Sample samplex : points) {
                  for (int side : new int[]{-1, 1}) {
                     RoadGeometry.V p = samplex.at((double)side * samplex.halfWidth(), 0.0);
                     min = new RoadGeometry.V(Math.min(min.x(), p.x()), Math.min(min.y(), p.y() - settings.thickness()), Math.min(min.z(), p.z()));
                     max = new RoadGeometry.V(Math.max(max.x(), p.x()), Math.max(max.y(), p.y()), Math.max(max.z(), p.z()));
                  }
               }

               return new RoadCycleChain(result, new RoadGeometry.Mesh(List.copyOf(points), settings, min, max, offset, false, null));
            }
         }
      } else {
         return null;
      }
   }

   private static RoadCycleChain single(RoadRecord root) {
      return new RoadCycleChain(List.of(new RoadCycleChain.Leg(root, 1, 0.0)), clean(root));
   }

   private static RoadGeometry.Mesh clean(RoadRecord road) {
      RoadGeometry.Mesh mesh = RailwayCrossings.shape(road.rawMesh());
      RoadGeometry.Settings settings = mesh.settings()
         .options(
            mesh.settings()
               .options()
               .levelCrossings(List.of())
               .lanePoints(LanePoints.Data.EMPTY)
               .streetscape(mesh.settings().options().streetscape().crossings(List.of()))
         );
      return new RoadGeometry.Mesh(mesh.samples(), settings, mesh.min(), mesh.max(), mesh.length(), mesh.closed(), mesh.controlPoint());
   }

   private static boolean compatible(RoadRecord a, RoadRecord b, BlockPos node) {
      if (!a.id().equals(b.id())
         && eligible(b)
         && a.settings().style() == b.settings().style()
         && a.settings().options().leftTraffic() == b.settings().options().leftTraffic()) {
         RoadGeometry.Mesh am = clean(a);
         RoadGeometry.Mesh bm = clean(b);
         RoadGeometry.Sample x = a.a().equals(node) ? am.first() : am.last();
         RoadGeometry.Sample y = b.a().equals(node) ? bm.first() : bm.last();
         RoadGeometry.V da = x.left().left().mul(a.a().equals(node) ? 1.0 : -1.0);
         RoadGeometry.V db = y.left().left().mul(b.a().equals(node) ? 1.0 : -1.0);
         if (!(x.center().distance(y.center()) > 1.0E-5) && !(da.dot(db) > -0.995)) {
            RoadProfile.Layout layout = RoadProfile.layout(am, x);
            if (!layout.catalog().twoWay()) {
               return false;
            } else {
               for (RoadGeometry.Mesh mesh : List.of(am, bm)) {
                  for (RoadGeometry.Sample s : mesh.samples()) {
                     RoadProfile.Layout l = RoadProfile.layout(mesh, s);
                     if (Math.abs(s.halfWidth() - x.halfWidth()) > 1.0E-6
                        || Math.abs(l.motorMin() - layout.motorMin()) > 1.0E-6
                        || Math.abs(l.motorMax() - layout.motorMax()) > 1.0E-6
                        || Math.abs(l.cycleWidth() - layout.cycleWidth()) > 1.0E-6
                        || Math.abs(l.curbWidth() - layout.curbWidth()) > 1.0E-6) {
                        return false;
                     }
                  }
               }

               return true;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   List<RoadsideBypass.Span> local(UUID id, List<RoadsideBypass.Span> spans) {
      RoadCycleChain.Leg leg = this.legs.stream().filter(l -> l.road().id().equals(id)).findFirst().orElseThrow();
      double length = leg.road().rawMesh().length();
      ArrayList<RoadsideBypass.Span> result = new ArrayList<>();

      for (RoadsideBypass.Span s : spans) {
         if (!(s.end() <= leg.offset()) && !(s.start() >= leg.offset() + length)) {
            result.add(
               leg.sign() > 0
                  ? new RoadsideBypass.Span(
                     s.side(), s.start() - leg.offset(), s.from() - leg.offset(), s.to() - leg.offset(), s.end() - leg.offset(), s.delta()
                  )
                  : new RoadsideBypass.Span(
                     -s.side(),
                     length + leg.offset() - s.end(),
                     length + leg.offset() - s.to(),
                     length + leg.offset() - s.from(),
                     length + leg.offset() - s.start(),
                     s.delta()
                  )
            );
         }
      }

      return List.copyOf(result);
   }

   static record Leg(RoadRecord road, int sign, double offset) {
   }
}
