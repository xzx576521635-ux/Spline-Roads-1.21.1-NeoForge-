package com.sora.splineroads.world;

import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneReopening;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadStructures;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;

public final class LaneRoadChain {
   private final List<LaneRoadChain.Leg> legs;
   private final LanePoints.Point origin;

   private LaneRoadChain(List<LaneRoadChain.Leg> legs, LanePoints.Point origin) {
      this.legs = List.copyOf(legs);
      this.origin = origin;
   }

   public List<LaneRoadChain.Leg> legs() {
      return this.legs;
   }

   public Set<UUID> ids() {
      LinkedHashSet<UUID> out = new LinkedHashSet<>();

      for (LaneRoadChain.Leg leg : this.legs) {
         out.add(leg.road().id());
      }

      return out;
   }

   public static LaneRoadChain of(Map<UUID, RoadRecord> all, LanePoints.Ref ref) {
      return of(all, ref, 0);
   }

   static LaneRoadChain of(Map<UUID, RoadRecord> all, LanePoints.Ref ref, int offset) {
      RoadRecord root = all.get(ref.road());
      if (root == null) {
         throw new IllegalArgumentException("所选连续车道已不存在");
      } else {
         LanePoints.Point point = LaneTopology.point(root, ref.point());
         LanePoints.Lane lane = LanePoints.lane(root.rawMesh(), point);
         if (offset != 0) {
            LanePoints.Lane next = LanePoints.lane(root.rawMesh(), lane.station(), point.lane() + offset);
            if (next.sign() != lane.sign()) {
               throw new IllegalArgumentException("双车道匝道不能跨越对向车道");
            }

            point = point.at(next, root.rawMesh());
            lane = next;
         }

         LaneRoadChain.Leg first = new LaneRoadChain.Leg(root, point.lane(), lane.sign(), (double)(-lane.sign()) * lane.station());
         ArrayList<LaneRoadChain.Leg> out = new ArrayList<>();
         out.add(first);
         HashSet<UUID> seen = new HashSet<>();
         seen.add(root.id());
         HashMap<BlockPos, List<RoadRecord>> incident = new HashMap<>();

         for (RoadRecord road : all.values()) {
            if (road.assembly() == null && road.junction() == null && LaneTopology.metadata(road).link() == null) {
               incident.computeIfAbsent(road.a(), k -> new ArrayList<>()).add(road);
               if (!road.b().equals(road.a())) {
                  incident.computeIfAbsent(road.b(), k -> new ArrayList<>()).add(road);
               }
            }
         }

         for (int direction : new int[]{-1, 1}) {
            LaneRoadChain.Leg current = first;

            while (true) {
               RoadPlanningBudget.check();
               double d = current.sign() * direction > 0 ? current.road().rawMesh().length() : 0.0;
               double boundary = current.coordinate(d);
               BlockPos node = d == 0.0 ? current.road().a() : current.road().b();
               List<RoadRecord> near = incident.getOrDefault(node, List.of());
               if (near.size() != 2) {
                  break;
               }

               LanePoints.Lane old = LanePoints.lane(current.road().rawMesh(), d, current.slot());
               LaneRoadChain.Leg next = null;

               for (RoadRecord roadx : near) {
                  if (!seen.contains(roadx.id()) && LanePoints.supported(roadx.settings()) && !roadx.rawMesh().closed()) {
                     double at = roadx.a().equals(node) ? 0.0 : roadx.rawMesh().length();
                     if (LanePoints.sameSection(current.road().rawMesh(), d, roadx.rawMesh(), at)) {
                        for (int slot : LaneAdditions.slots(roadx.rawMesh(), at)) {
                           LanePoints.Lane lane2 = LanePoints.lane(roadx.rawMesh(), at, slot);
                           if (!(old.direction().dot(lane2.direction()) < 0.995)
                              && !(old.position().distance(lane2.position()) > 0.25)
                              && !(Math.abs(old.width() - lane2.width()) > 0.25)
                              && (at == 0.0 ? 1 : -1) == lane2.sign() * direction
                              && continuous(roadx, slot, lane2.sign())) {
                              if (next != null) {
                                 throw new IllegalArgumentException("车道接缝存在多个同向匹配，不能自动换道");
                              }

                              next = new LaneRoadChain.Leg(roadx, slot, lane2.sign(), boundary - (double)lane2.sign() * at);
                           }
                        }
                     }
                  }
               }

               if (next == null) {
                  break;
               }

               out.add(next);
               seen.add(next.road().id());
               current = next;
            }
         }

         out.sort(Comparator.comparingDouble(LaneRoadChain.Leg::low));
         return new LaneRoadChain(out, point);
      }
   }

   private static boolean continuous(RoadRecord road, int slot, int sign) {
      RoadGeometry.Mesh raw = road.rawMesh();

      for (RoadGeometry.Sample sample : raw.samples()) {
         if (!LaneAdditions.isAdded(raw, slot) && slot >= RoadProfile.layout(raw, sample).catalog().lanes()) {
            return false;
         }

         if (LanePoints.lane(raw, sample.distance(), slot).sign() != sign) {
            return false;
         }
      }

      return true;
   }

   public double low() {
      return this.legs.get(0).low();
   }

   public double high() {
      return this.legs.get(this.legs.size() - 1).high();
   }

   public boolean contains(double offset) {
      return Double.isFinite(offset) && offset >= this.low() - 1.0E-6 && offset <= this.high() + 1.0E-6;
   }

   public LaneRoadChain.Position at(double offset) {
      LaneRoadChain.Leg selected = null;

      for (LaneRoadChain.Leg leg : this.legs) {
         if (offset >= leg.low() - 1.0E-6 && offset <= leg.high() + 1.0E-6) {
            selected = leg;
            if (offset < leg.high() - 1.0E-6) {
               break;
            }
         }
      }

      if (selected == null) {
         throw new IllegalArgumentException("同车道搜索到达实际断头、分岔或不连续接缝，未跨入相邻车道");
      } else {
         double at = selected.station(offset);
         LanePoints.Point point = LanePoints.point(this.origin.id(), LanePoints.Origin.MANUAL, selected.road().rawMesh(), at, selected.slot());
         return new LaneRoadChain.Position(selected.road(), point, at);
      }
   }

   public RoadGeometry.Mesh sweep() {
      return this.sweep(true);
   }

   private RoadGeometry.Mesh sweep(boolean edges) {
      ArrayList<RoadGeometry.Sample> out = new ArrayList<>();
      double shift = this.legs.get(0).low();
      double thickness = 0.0;
      double girder = 0.0;
      RoadInfrastructure.Config bridge = null;

      for (LaneRoadChain.Leg leg : this.legs) {
         RoadGeometry.Mesh raw = leg.road().rawMesh();
         thickness = Math.max(thickness, raw.settings().thickness());
         double depth = RoadInfrastructure.girderDepth(raw);
         if (depth > girder) {
            girder = depth;
            bridge = raw.settings().options().infrastructure();
         }

         ArrayList<RoadGeometry.Sample> samples = new ArrayList<>(raw.samples());
         if (leg.sign() < 0) {
            Collections.reverse(samples);
         }

         for (RoadGeometry.Sample sample : samples) {
            double station = leg.coordinate(sample.distance()) - shift;
            LanePoints.Lane lane = LanePoints.lane(raw, sample.distance(), leg.slot());
            if (lane.sign() != leg.sign()) {
               throw new IllegalArgumentException("所选车道在断面变化处已终止，不能跨到反向车道");
            }

            if (out.isEmpty() || !(station - out.get(out.size() - 1).distance() < 1.0E-7)) {
               double low = lane.width() / 2.0 + 0.3;
               double high = low;
               if (edges) {
                  RoadProfile.Layout layout = RoadProfile.layout(raw, sample);
                  double center = lane.position().sub(sample.center()).dot(sample.left());
                  double reach = RoadInfrastructure.edgeReach(raw, sample);
                  double lo = 0.0;
                  double hi = 0.0;
                  if (layout.cycleWidth() < 0.01) {
                     if (Math.abs(center - lane.width() / 2.0 - layout.motorMin()) < 0.01) {
                        lo = Math.max(0.0, sample.halfWidth() + layout.motorMin()) + reach;
                     }

                     if (Math.abs(center + lane.width() / 2.0 - layout.motorMax()) < 0.01) {
                        hi = Math.max(0.0, sample.halfWidth() - layout.motorMax()) + reach;
                     }
                  }

                  low += leg.sign() > 0 ? lo : hi;
                  high += leg.sign() > 0 ? hi : lo;
               }

               out.add(
                  new RoadGeometry.Sample(
                     lane.position().add(lane.direction().left().mul((high - low) / 2.0)), lane.direction().left(), station, (high + low) / 2.0
                  )
               );
            }
         }
      }

      RoadGeometry.Settings settings = new RoadGeometry.Settings(RoadGeometry.Mode.CURVE, RoadGeometry.Style.C1_RAMP, 4.0, thickness, 0.35, 90.0);
      if (bridge != null) {
         settings = settings.structure(RoadGeometry.Structure.BRIDGE).options(settings.options().infrastructure(bridge));
      }

      RoadGeometry.Mesh bounds = RoadRibbon.mesh(out, settings);
      return new RoadGeometry.Mesh(List.copyOf(out), settings, bounds.min(), bounds.max(), out.get(out.size() - 1).distance(), false, null);
   }

   public void reserve(
      Map<UUID, List<LaneSections.Event>> events,
      UUID connection,
      RoadGeometry.Mesh ramp,
      List<RoadStructures.Part> parts,
      boolean arrival,
      double endOffset,
      double transition,
      boolean rectangular
   ) {
      double low = this.legs.get(0).low();
      double high = this.legs.get(this.legs.size() - 1).high();
      double begin = arrival ? low : 0.0;
      double end = arrival ? endOffset : high;
      if (ramp != null) {
         RoadGeometry.Mesh sweep = this.sweep();
         RoadClearance.Prepared prepared = RoadClearance.prepare(sweep);
         List<RoadClearance.Contact> deckContacts = RoadClearance.contacts(prepared, ramp);
         ArrayList<RoadClearance.Contact> contacts = new ArrayList<>(deckContacts);

         for (RoadStructures.Part part : parts) {
            RoadPlanningBudget.check();
            contacts.addAll(LaneReopening.structureContacts(prepared, sweep, part, ramp.settings()));
         }

         if (arrival) {
            begin = endOffset;

            for (RoadClearance.Contact c : contacts) {
               if (c.blocked()) {
                  double from = c.from() + low;
                  double to = c.to() + low;
                  if (from <= endOffset + 0.01) {
                     begin = Math.min(begin, from);
                  }
               }
            }

            for (RoadClearance.Contact cx : deckContacts) {
               if (cx.blocked() && cx.to() + low > endOffset + 0.5) {
                  throw new IllegalArgumentException("汇入点下游仍有实际路面横穿，不能恢复车道");
               }
            }

            if (endOffset + 0.12 < high) {
               RoadGeometry.Mesh driveSweep = this.sweep(false);
               ArrayList<RoadGeometry.Sample> after = new ArrayList<>();
               double from = endOffset + 0.12 - low;
               RoadGeometry.Sample at = RoadStructures.sample(driveSweep, from);
               after.add(new RoadGeometry.Sample(at.center(), at.left(), 0.0, Math.max(0.05, at.halfWidth() - 0.52)));

               for (RoadGeometry.Sample a : driveSweep.samples()) {
                  if (a.distance() > from) {
                     after.add(new RoadGeometry.Sample(a.center(), a.left(), a.distance() - from, Math.max(0.05, a.halfWidth() - 0.52)));
                  }
               }

               RoadGeometry.Mesh downstream = RoadRibbon.mesh(after, sweep.settings());

               for (RoadStructures.Part part : parts) {
                  if (RoadClearance.structureInvades(part, downstream, 4.0)) {
                     throw new IllegalArgumentException("汇入后实际通行车道被结构占用，不能恢复");
                  }
               }
            }

            double required = begin - 1.0;
            begin -= rectangular ? 9.0 : 1.0 + transition;
            if (rectangular) {
               for (LaneRoadChain.Leg leg : this.legs) {
                  for (LaneSections.Cut cut : LaneTopology.metadata(leg.road()).cuts()) {
                     if (!cut.connection().equals(connection) && cut.lane() == leg.slot()) {
                        double boundary = Math.max(leg.coordinate(cut.begin()), leg.coordinate(cut.end()));
                        if (boundary <= required && boundary > begin) {
                           begin = boundary;
                        }
                     }
                  }
               }
            }
         } else {
            end = 0.0;

            for (RoadClearance.Contact cxx : contacts) {
               if (cxx.blocked() && cxx.to() + low >= -0.01) {
                  end = Math.max(end, cxx.to() + low);
               }
            }

            end += 1.0 + (rectangular ? 0.0 : transition);
         }
      }

      begin = Math.max(low, begin);
      end = Math.min(high, end);
      if (rectangular) {
         if (begin - low <= 0.50001) {
            begin = low;
         }

         if (high - end <= 0.50001) {
            end = high;
         }
      }

      for (LaneRoadChain.Leg leg : this.legs) {
         double ax = Math.max(begin, leg.low());
         double b = Math.min(end, leg.high());
         if (!(b - ax < 0.02)) {
            double start = leg.station(ax);
            double finish = leg.station(b);
            boolean under = false;
            if (ramp != null) {
               for (double d = ax; d <= b + 0.001; d += 0.5) {
                  LanePoints.Lane lane = LanePoints.lane(leg.road().rawMesh(), leg.station(d), leg.slot());
                  RoadQueries.Projection q = RoadQueries.horizontal(ramp, lane.position());
                  if (!(q.horizontalDistance() > q.sample().halfWidth() + lane.width() / 2.0 + 0.3) && q.sample().center().y() < lane.position().y() - 0.1) {
                     under = true;
                     break;
                  }
               }
            }

            LaneSections.Event event = new LaneSections.Event(
               connection,
               arrival ? LaneSections.Kind.ARRIVE : LaneSections.Kind.TEMPORARY,
               leg.slot(),
               leg.sign(),
               arrival ? finish : start,
               transition,
               arrival ? start : finish,
               rectangular,
               under
            );
            events.computeIfAbsent(leg.road().id(), k -> new ArrayList<>()).add(event);
         }
      }
   }

   public static record Leg(RoadRecord road, int slot, int sign, double zero) {
      public double coordinate(double station) {
         return this.zero + (double)this.sign * station;
      }

      public double low() {
         return this.coordinate(this.sign > 0 ? 0.0 : this.road.rawMesh().length());
      }

      public double high() {
         return this.coordinate(this.sign > 0 ? this.road.rawMesh().length() : 0.0);
      }

      public double station(double coordinate) {
         return Math.max(0.0, Math.min(this.road.rawMesh().length(), (double)this.sign * (coordinate - this.zero)));
      }
   }

   public static record Position(RoadRecord road, LanePoints.Point point, double station) {
   }
}
