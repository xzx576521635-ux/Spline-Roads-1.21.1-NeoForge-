package com.sora.splineroads.world;

import com.sora.splineroads.core.InterchangePlanner;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadContinuity;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadStructures;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class ContinuousRoads {
   static final String MAIN = "连续主路:";
   static final String DONOR = "共享端点邻路:";

   static List<ContinuousRoads.Source> sources(CompoundTag t) {
      ArrayList<ContinuousRoads.Source> out = new ArrayList<>();

      for (Tag tag : t.getList("ContinuousSources", 10)) {
         CompoundTag q = (CompoundTag)tag;
         out.add(new ContinuousRoads.Source(q.getInt("Axis"), RoadRecord.load(q.getCompound("Road"))));
      }

      return out;
   }

   static void capture(RoadData data, CompoundTag t) {
      if (t.getLongArray("Points").length <= 6) {
         long[] points = t.getLongArray("Points");
         ListTag limits = t.getList("Nodes", 10).copy();
         ListTag list = new ListTag();

         for (int axis = 0; axis * 2 + 1 < points.length; axis++) {
            BlockPos a = BlockPos.of(points[axis * 2]);
            BlockPos b = BlockPos.of(points[axis * 2 + 1]);
            List<RoadRecord> chain = Interchanges.mainChain(data, a, b);
            if (!chain.isEmpty() && !chain.stream().anyMatch(rx -> !RoadContinuity.eligible(rx.mesh()))) {
               if (chain.size() > 1) {
                  for (int i = 1; i < chain.size(); i++) {
                     RoadRecord r = chain.get(i - 1);
                     RoadRecord q = chain.get(i);
                     BlockPos shared = !r.a().equals(q.a()) && !r.a().equals(q.b()) ? r.b() : r.a();
                     if (AutoJunctions.center(data, shared) != null
                        || !RoadContinuity.compatible(r.mesh(), q.mesh(), r.a().equals(shared) ? r.start().position() : r.end().position())) {
                        throw new IllegalArgumentException("连续主路须共线、水平、分方向车道数相同，且中间端点不属于路口");
                     }
                  }
               }

               ArrayList<RoadRecord> extended = new ArrayList<>(chain);
               Set<UUID> seen = new HashSet<>();
               chain.forEach(rx -> seen.add(rx.id()));

               for (boolean first : new boolean[]{true, false}) {
                  BlockPos at = first ? a : b;
                  RoadRecord previous = first ? chain.get(0) : chain.get(chain.size() - 1);

                  while (extended.size() < 256 && AutoJunctions.center(data, at) == null && data.index.atNode(at).size() == 2) {
                     RoadRecord neighbor = null;

                     for (UUID id : data.index.atNode(at)) {
                        if (!seen.contains(id)) {
                           RoadRecord r = data.index.roads.get(id).record;
                           if (r.assembly() == null
                              && r.junction() == null
                              && RoadContinuity.compatible(
                                 previous.mesh(), r.mesh(), previous.a().equals(at) ? previous.start().position() : previous.end().position()
                              )) {
                              neighbor = r;
                           }
                        }
                     }

                     if (neighbor == null) {
                        break;
                     }

                     seen.add(neighbor.id());
                     extended.add(neighbor);
                     at = neighbor.a().equals(at) ? neighbor.b() : neighbor.a();
                     previous = neighbor;
                  }

                  RoadGeometry.Node node = previous.a().equals(at) ? previous.start() : previous.end();
                  limits.set(axis * 2 + (first ? 0 : 1), RoadRecord.writeNode(node));
               }

               for (RoadRecord road : extended) {
                  CompoundTag q = new CompoundTag();
                  q.putInt("Axis", axis);
                  q.put("Road", road.header());
                  list.add(q);
               }
            }
         }

         t.put("ContinuousSources", list);
         t.put("ContinuousLimits", limits);
      }
   }

   public static InterchangePlanner.Plan plan(CompoundTag t, Function<CompoundTag, InterchangePlanner.Plan> base) {
      List<ContinuousRoads.Source> sources = sources(t);
      if (sources.isEmpty()) {
         return base.apply(t);
      } else {
         boolean adjust = Interchanges.read(t.getCompound("Options")).adjustEndpoints();

         InterchangePlanner.Plan p;
         try {
            p = base.apply(t);
         } catch (IllegalArgumentException var21) {
            if (adjust) {
               throw var21;
            }

            ListTag original = t.getList("Nodes", 10);
            ListTag limits = t.getList("ContinuousLimits", 10);
            if (limits.size() != original.size() || limits.equals(original)) {
               throw var21;
            }

            CompoundTag candidate = t.copy();
            candidate.put("Nodes", limits.copy());
            candidate.remove("HostAxes");
            p = base.apply(candidate);
            double lo = 0.0;
            double hi = 1.0;

            for (int iteration = 0; iteration < 10; iteration++) {
               double f = (lo + hi) / 2.0;
               ListTag nodes = new ListTag();

               for (int i = 0; i < original.size(); i++) {
                  RoadGeometry.Node a = RoadRecord.readNode(original.getCompound(i));
                  RoadGeometry.Node b = RoadRecord.readNode(limits.getCompound(i));
                  nodes.add(RoadRecord.writeNode(new RoadGeometry.Node(a.position().mul(1.0 - f).add(b.position().mul(f)), a.yaw(), a.grade())));
               }

               candidate.put("Nodes", nodes);

               try {
                  InterchangePlanner.Plan trial = base.apply(candidate);
                  p = trial;
                  hi = f;
               } catch (IllegalArgumentException var20) {
                  lo = f;
               }
            }
         }

         return segment(t, p, sources);
      }
   }

   private static InterchangePlanner.Plan segment(CompoundTag t, InterchangePlanner.Plan plan, List<ContinuousRoads.Source> sources) {
      ArrayList<InterchangePlanner.Leg> legs = new ArrayList<>();
      long[] points = t.getLongArray("Points");
      boolean adjust = Interchanges.read(t.getCompound("Options")).adjustEndpoints();
      Set<UUID> used = new HashSet<>();

      for (InterchangePlanner.Leg leg : plan.legs()) {
         int axis = leg.name().equals("主路 1") || leg.name().equals("主路 AB")
            ? 0
            : (!leg.name().equals("主路 2") && !leg.name().equals("第二层 CD") ? (leg.name().equals("主路 3") ? 2 : -1) : 1);
         if (axis >= 0 && !sources.stream().noneMatch(s -> s.axis() == axis)) {
            RoadGeometry.Mesh generated = leg.mesh();
            RoadGeometry.V origin = generated.first().center();
            RoadGeometry.V dir = generated.last().center().sub(origin).horizontalUnit();
            double total = generated.last().center().sub(origin).dot(dir);
            List<ContinuousRoads.Source> axisSources = sources.stream()
               .filter(s -> s.axis() == axis && !ContinuousSections.detached(t, s.road().id()))
               .toList();
            HashMap<BlockPos, RoadGeometry.V> terminals = new HashMap<>();
            if (!t.contains("Corridor") && axis * 2 + 1 < points.length) {
               for (boolean first : new boolean[]{true, false}) {
                  double bound = first ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
                  BlockPos outer = null;

                  for (ContinuousRoads.Source item : axisSources) {
                     for (boolean start : new boolean[]{true, false}) {
                        RoadRecord r = item.road();
                        BlockPos end = start ? r.a() : r.b();
                        RoadGeometry.V position = start ? r.start().position() : r.end().position();
                        if (adjust) {
                           for (int i = 2 * axis; i <= 2 * axis + 1; i++) {
                              if (end.asLong() == points[i]) {
                                 position = plan.anchors().get(i).position();
                              }
                           }
                        }

                        double station = position.sub(origin).dot(dir);
                        if (first ? station < bound : station > bound) {
                           bound = station;
                           outer = end;
                        }
                     }
                  }

                  if (outer != null && (first ? bound > 1.0E-5 : bound < total - 1.0E-5)) {
                     terminals.put(outer, first ? origin : generated.last().center());
                  }
               }
            }

            for (ContinuousRoads.Source source : axisSources) {
               RoadRecord rx = source.road();
               RoadGeometry.V a = rx.start().position();
               RoadGeometry.V b = rx.end().position();
               if (adjust) {
                  for (int ix = 0; ix < points.length; ix++) {
                     if (rx.a().asLong() == points[ix]) {
                        a = plan.anchors().get(ix).position();
                     }

                     if (rx.b().asLong() == points[ix]) {
                        b = plan.anchors().get(ix).position();
                     }
                  }
               }

               a = terminals.getOrDefault(rx.a(), a);
               b = terminals.getOrDefault(rx.b(), b);
               double sa = a.sub(origin).dot(dir);
               double sb = b.sub(origin).dot(dir);
               double low = Math.min(sa, sb);
               double high = Math.max(sa, sb);
               boolean occupies = Math.min(high, total) - Math.max(low, 0.0) > 1.0E-5;
               boolean moved = a.distance(rx.start().position()) > 1.0E-6 || b.distance(rx.end().position()) > 1.0E-6;
               boolean previouslyOccupied = t.getList("OccupiedRoads", 10).stream().anyMatch(tag -> ((CompoundTag)tag).getUUID("Road").equals(rx.id()));
               if (occupies || moved || previouslyOccupied) {
                  if (!(a.sub(b).horizontalLength() < 2.0) && Math.signum(rx.end().position().sub(rx.start().position()).dot(dir)) == Math.signum(sb - sa)) {
                     if (!moved || !(Math.abs(a.y() - b.y()) > 0.001) && RoadContinuity.eligible(rx.mesh())) {
                        ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
                        TreeSet<Double> distances = new TreeSet<>();
                        double length = b.sub(a).horizontalLength();
                        int ix = 0;

                        for (int n = (int)Math.ceil(length / 0.5); ix <= n; ix++) {
                           distances.add(length * (double)ix / (double)n);
                        }

                        for (double boundary : new double[]{0.0, total}) {
                           double distance = (boundary - sa) / (sb - sa) * length;
                           if (!(distance <= 1.0E-6) && !(distance >= length - 1.0E-6)) {
                              Double before = distances.floor(distance);
                              Double after = distances.ceiling(distance);
                              if (before != null && distance - before < 1.0E-6) {
                                 distances.remove(before);
                              }

                              if (after != null && after - distance < 1.0E-6) {
                                 distances.remove(after);
                              }

                              distances.add(distance);
                           }
                        }

                        for (double distance : distances) {
                           double f = distance / length;
                           RoadGeometry.V pos = a.mul(1.0 - f).add(b.mul(f));
                           double at = pos.sub(origin).dot(dir);
                           RoadGeometry.Sample old = RoadStructures.sample(
                              rx.mesh(), Math.min(rx.mesh().length(), Math.max(0.0, RoadAttachments.station(rx.mesh(), pos)))
                           );
                           double width = old.halfWidth();
                           if (at >= -1.0E-6 && at <= total + 1.0E-6) {
                              RoadGeometry.Sample q = RoadQueries.horizontal(generated, pos).sample();
                              pos = new RoadGeometry.V(pos.x(), q.center().y(), pos.z());
                              width = q.halfWidth();
                           }

                           samples.add(new RoadGeometry.Sample(pos, b.sub(a).horizontalUnit().left(), 0.0, Math.max(0.25, width)));
                        }

                        RoadGeometry.Settings settings = rx.settings();
                        if (occupies) {
                           RoadGeometry.Settings selected = generated.settings();
                           RoadProfile.Options original = settings.options();
                           RoadProfile.Options chosen = selected.options()
                              .attachments(original.attachments())
                              .lanePoints(original.lanePoints())
                              .ends(original.ends())
                              .infrastructure(original.infrastructure().gantry(selected.options().infrastructure().gantry()));
                           if (sb < sa) {
                              RoadLanes.Counts counts = RoadLanes.counts(selected);
                              chosen = chosen.lanes(new RoadLanes.Counts(counts.reverse(), counts.forward()));
                              RoadProfile.OuterRail rail = chosen.outerRail();
                              if (rail == RoadProfile.OuterRail.SOUND_LEFT) {
                                 chosen = chosen.outerRail(RoadProfile.OuterRail.SOUND_RIGHT);
                              } else if (rail == RoadProfile.OuterRail.SOUND_RIGHT) {
                                 chosen = chosen.outerRail(RoadProfile.OuterRail.SOUND_LEFT);
                              }
                           }

                           settings = new RoadGeometry.Settings(
                              settings.mode(),
                              selected.style(),
                              selected.width(),
                              selected.thickness(),
                              settings.tension(),
                              settings.arcDegrees(),
                              settings.startWidth(),
                              settings.endWidth(),
                              settings.structure(),
                              settings.taperVersion(),
                              settings.rampTurn(),
                              chosen
                           );
                        }

                        RoadAttachments.Data metadata = settings.options().attachments();
                        List<RoadAttachments.Point> fixed = metadata.points()
                           .stream()
                           .map(p -> new RoadAttachments.Point(p.id(), p.position(), p.position(), false, p.origin(), p.elevated()))
                           .toList();
                        RoadGeometry.Settings var68 = settings.options(settings.options().attachments(metadata.points(fixed)));
                        RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, var68);
                        if (occupies) {
                           double first = RoadAttachments.station(mesh, origin);
                           double last = RoadAttachments.station(mesh, generated.last().center());
                           ContinuousSections.append(legs, leg, rx, mesh, Math.min(first, last), Math.max(first, last), moved);
                        } else {
                           legs.add(new InterchangePlanner.Leg("共享端点邻路:" + rx.id(), leg.from(), leg.to(), mesh));
                        }

                        used.add(rx.id());
                        continue;
                     }

                     throw new IllegalArgumentException("共享端点只能沿符合条件的水平直线调整；此次高度变化会使邻路起坡");
                  }

                  throw new IllegalArgumentException("共享端点调整后相邻道路不足两格或发生折返");
               }
            }
         } else {
            legs.add(leg);
         }
      }

      return new InterchangePlanner.Plan(List.copyOf(legs), plan.movements(), plan.minRadius(), plan.highest(), plan.center(), plan.anchors());
   }

   static RoadRecord updatedSource(RoadRecord original, RoadRecord current) {
      RoadGeometry.Settings settings = original.settings().options(original.settings().options().attachments(current.settings().options().attachments()));
      List<RoadGeometry.Sample> alignment = original.alignment();
      if (!original.start().position().equals(current.start().position()) || !original.end().position().equals(current.end().position())) {
         RoadGeometry.Mesh old = original.mesh();
         ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
         RoadGeometry.V start = current.start().position();
         RoadGeometry.V end = current.end().position();
         RoadGeometry.V left = end.sub(start).horizontalUnit().left();

         for (RoadGeometry.Sample sample : old.samples()) {
            double f = sample.distance() / old.length();
            samples.add(new RoadGeometry.Sample(start.mul(1.0 - f).add(end.mul(f)), left, 0.0, sample.halfWidth()));
         }

         alignment = RoadRibbon.mesh(samples, settings).samples();
      }

      return new RoadRecord(
         original.id(),
         original.owner(),
         current.a(),
         current.b(),
         current.start(),
         current.end(),
         settings,
         original.automatic(),
         original.clearance(),
         List.of(),
         original.endCaps(),
         original.buildVersion(),
         null,
         alignment,
         original.furniturePhase(),
         null,
         null,
         original.fragment()
      );
   }

   static UUID sourceId(String name) {
      return ContinuousSections.sourceId(name);
   }

   static boolean donor(String name) {
      return name.startsWith("共享端点邻路:");
   }

   static RoadRecord source(CompoundTag t, UUID id) {
      return sources(t).stream().map(ContinuousRoads.Source::road).filter(r -> r.id().equals(id)).findFirst().orElseThrow();
   }

   static boolean virtual(CompoundTag t, int i, RoadGeometry.Node target) {
      if (Interchanges.read(t.getCompound("Options")).adjustEndpoints()) {
         return false;
      } else {
         RoadGeometry.Node[] nodes = HostAxes.nodes(t);
         RoadGeometry.V real = nodes[i].position();
         long[] points = t.getLongArray("Points");

         for (ContinuousRoads.Source source : sources(t)) {
            RoadRecord r = source.road();
            if (i < points.length && r.a().asLong() == points[i]) {
               real = r.start().position();
               break;
            }

            if (i < points.length && r.b().asLong() == points[i]) {
               real = r.end().position();
               break;
            }
         }

         ListTag limits = t.getList("ContinuousLimits", 10);
         if (i >= limits.size()) {
            return false;
         } else {
            RoadGeometry.V b = RoadRecord.readNode(limits.getCompound(i)).position();
            RoadGeometry.V d = b.sub(real);
            if (d.horizontalLength() < 1.0E-6) {
               return false;
            } else {
               double at = target.position().sub(real).dot(d.horizontalUnit());
               return at >= -1.0E-6 && at <= d.horizontalLength() + 1.0E-6 && Math.abs(target.position().y() - real.y()) < 1.0E-6;
            }
         }
      }
   }

   static void requireMove(RoadData data, BlockPos from, RoadGeometry.Node target, Set<UUID> removed, CompoundTag t) {
      List<UUID> outside = data.index.atNode(from).stream().filter(idx -> !removed.contains(idx)).toList();
      if (!outside.isEmpty()) {
         if (AutoJunctions.center(data, from) == null && data.index.atNode(from).size() == 2) {
            List<ContinuousRoads.Source> sources = sources(t);

            for (UUID id : outside) {
               if (sources.stream().noneMatch(s -> s.road().id().equals(id))) {
                  throw new IllegalArgumentException("共享端点邻路不满足共线水平/车道/结构条件");
               }
            }
         } else {
            throw new IllegalArgumentException("共享端点属于路口或分岔，不能自动移动");
         }
      }
   }

   private ContinuousRoads() {
   }

   static record Source(int axis, RoadRecord road) {
   }
}
