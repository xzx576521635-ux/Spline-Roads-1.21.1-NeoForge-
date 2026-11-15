package com.sora.splineroads.world;

import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadBoundsIndex;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.core.RoadTunnelFit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

public final class AutoCrossings {
   static boolean eligible(RoadRecord r) {
      return AutoJunctions.ordinary(r) && !r.mesh().closed() && !RoadTunnelFit.enabled(r.settings());
   }

   private static RoadGeometry.V median(RoadGeometry.Mesh m, RoadGeometry.Sample s) {
      return s.at(RoadProfile.layout(m, s).medianCenter(), 0.0);
   }

   private static double cross(RoadGeometry.V a, RoadGeometry.V b) {
      return a.x() * b.z() - a.z() * b.x();
   }

   private static List<AutoCrossings.Segment> segments(RoadGeometry.Mesh mesh) {
      List<RoadGeometry.V> points = mesh.samples().stream().map(s -> median(mesh, s)).toList();
      ArrayList<AutoCrossings.Segment> result = new ArrayList<>();

      for (int i = 1; i < points.size(); i++) {
         RoadGeometry.V p = points.get(i - 1);
         RoadGeometry.V q = points.get(i);
         RoadGeometry.V delta = q.sub(p);
         double length = delta.horizontalLength();
         double margin = length * 1.0E-7 + 1.0E-7;
         result.add(
            new AutoCrossings.Segment(
               p,
               delta,
               length,
               new RoadBoundsIndex.Bounds(
                  Math.min(p.x(), q.x()) - margin, Math.min(p.z(), q.z()) - margin, Math.max(p.x(), q.x()) + margin, Math.max(p.z(), q.z()) + margin
               )
            )
         );
      }

      return result;
   }

   public static List<AutoCrossings.Hit> hits(RoadRecord proposed, Collection<RoadRecord> roads) {
      if (!eligible(proposed)) {
         return List.of();
      } else {
         ArrayList<AutoCrossings.Hit> result = new ArrayList<>();
         RoadGeometry.Mesh a = proposed.mesh();
         List<AutoCrossings.Segment> as = segments(a);

         for (RoadRecord road : roads) {
            if (!road.id().equals(proposed.id()) && eligible(road)) {
               RoadGeometry.Mesh b = road.mesh();
               if (RoadIndex.overlapXZ(a, b, 0.0)) {
                  List<AutoCrossings.Segment> bs = segments(b);
                  RoadBoundsIndex index = new RoadBoundsIndex(bs.stream().map(AutoCrossings.Segment::bounds).toList(), 16);

                  for (int i = 1; i < a.samples().size(); i++) {
                     RoadPlanningBudget.check();
                     RoadGeometry.Sample a0 = a.samples().get(i - 1);
                     RoadGeometry.Sample a1 = a.samples().get(i);
                     AutoCrossings.Segment sa = as.get(i - 1);
                     RoadGeometry.V p = sa.start();
                     RoadGeometry.V u = sa.delta();

                     for (int slot : index.query(sa.bounds(), 0.0)) {
                        int j = slot + 1;
                        RoadGeometry.Sample b0 = b.samples().get(j - 1);
                        RoadGeometry.Sample b1 = b.samples().get(j);
                        AutoCrossings.Segment sb = bs.get(slot);
                        RoadGeometry.V q = sb.start();
                        RoadGeometry.V v = sb.delta();
                        double det = cross(u, v);
                        if (!(Math.abs(det) < 1.0E-8 * sa.length() * sb.length())) {
                           double t = cross(q.sub(p), v) / det;
                           double s = cross(q.sub(p), u) / det;
                           if (!(t < -1.0E-7) && !(t > 1.0000001) && !(s < -1.0E-7) && !(s > 1.0000001)) {
                              t = Math.max(0.0, Math.min(1.0, t));
                              s = Math.max(0.0, Math.min(1.0, s));
                              RoadGeometry.V x = p.add(u.mul(t));
                              RoadGeometry.V y = q.add(v.mul(s));
                              if (!(Math.abs(x.y() - y.y()) > 0.02)) {
                                 double ad = a0.distance() + (a1.distance() - a0.distance()) * t;
                                 double bd = b0.distance() + (b1.distance() - b0.distance()) * s;
                                 if (!(ad < 0.01) && !(ad > a.length() - 0.01) || !(bd < 0.01) && !(bd > b.length() - 0.01)) {
                                    RoadGeometry.V center = x.add(y).mul(0.5);
                                    if (!sharedEnd(proposed, road, center)
                                       && result.stream().noneMatch(h -> h.road().equals(road.id()) && h.center().distance(center) < 0.1)) {
                                       result.add(new AutoCrossings.Hit(road.id(), ad, bd, center));
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         return List.copyOf(result);
      }
   }

   private static boolean sharedEnd(RoadRecord a, RoadRecord b, RoadGeometry.V p) {
      for (boolean first : new boolean[]{true, false}) {
         BlockPos pos = first ? a.a() : a.b();
         if ((pos.equals(b.a()) || pos.equals(b.b())) && p.distance((first ? a.start() : a.end()).position()) < 2.0) {
            return true;
         }
      }

      return false;
   }

   static List<RoadRecord> candidates(CompoundTag t) {
      ArrayList<RoadRecord> out = new ArrayList<>();

      for (Tag entry : t.getList("CrossingCandidates", 10)) {
         out.add(RoadRecord.load(((CompoundTag)entry).getCompound("Road")));
      }

      return out;
   }

   static void enrich(CompoundTag command, RoadData data, BlockPos a, BlockPos b, UUID id) {
      enrich(command, data, a, b, id, true);
   }

   static void enrich(CompoundTag command, RoadData data, BlockPos a, BlockPos b, UUID id, boolean structures) {
      ListTag list = new ListTag();
      double reach = 2048.0;
      LinkedHashMap<UUID, RoadRecord> available = new LinkedHashMap<>(AutoJunctions.logical(data));
      data.index.roads.values().stream().map(built -> built.record).filter(RoadRecord::railway).forEach(rx -> available.put(rx.id(), rx));

      for (RoadRecord r : available.values()) {
         if (!r.id().equals(id) && (eligible(r) || r.railway())) {
            RoadGeometry.Mesh m = r.mesh();
            if (!(m.max().x() < (double)Math.min(a.getX(), b.getX()) - reach)
               && !(m.min().x() > (double)Math.max(a.getX(), b.getX()) + reach)
               && !(m.max().z() < (double)Math.min(a.getZ(), b.getZ()) - reach)
               && !(m.min().z() > (double)Math.max(a.getZ(), b.getZ()) + reach)) {
               CompoundTag entry = new CompoundTag();
               entry.put("Road", r.header());
               entry.put("Graph", r.railway() ? new CompoundTag() : AutoJunctions.context(data, Set.of(r.a(), r.b()), structures));
               list.add(entry);
            }
         }
      }

      command.put("CrossingCandidates", list);
   }

   static CompoundTag graph(CompoundTag command, List<AutoCrossings.Hit> hits) {
      CompoundTag result = command.getCompound("JunctionGraph").copy();

      for (Tag entry : command.getList("CrossingCandidates", 10)) {
         CompoundTag t = (CompoundTag)entry;
         if (hits.stream().anyMatch(hx -> hx.road().equals(t.getCompound("Road").getUUID("Id")))) {
            merge(result, t.getCompound("Graph"));
         }
      }

      LinkedHashSet<Long> active = new LinkedHashSet<>();

      for (long p : result.getLongArray("ActiveNodes")) {
         active.add(Long.valueOf(p));
      }

      for (AutoCrossings.Hit h : hits) {
         active.add(Long.valueOf(at(h.center()).asLong()));
      }

      result.putLongArray("ActiveNodes", active.stream().mapToLong(Long::longValue).toArray());
      return result;
   }

   private static void merge(CompoundTag into, CompoundTag from) {
      for (String key : List.of("Streets", "Centers", "RemoteTrims")) {
         LinkedHashMap<UUID, CompoundTag> entries = new LinkedHashMap<>();

         for (Tag v : into.getList(key, 10)) {
            CompoundTag t = (CompoundTag)v;
            entries.put(t.getUUID("Id"), t);
         }

         for (Tag v : from.getList(key, 10)) {
            CompoundTag t = (CompoundTag)v;
            entries.put(t.getUUID("Id"), t.copy());
         }

         ListTag list = new ListTag();
         entries.values().forEach(list::add);
         into.put(key, list);
      }

      ListTag continuations = into.getList("SlipNext", 10);

      for (Tag edge : from.getList("SlipNext", 10)) {
         if (!continuations.contains(edge)) {
            continuations.add(edge.copy());
         }
      }

      into.put("SlipNext", continuations);
      LinkedHashSet<Long> active = new LinkedHashSet<>();

      for (long p : into.getLongArray("ActiveNodes")) {
         active.add(Long.valueOf(p));
      }

      for (long p : from.getLongArray("ActiveNodes")) {
         active.add(Long.valueOf(p));
      }

      into.putLongArray("ActiveNodes", active.stream().mapToLong(Long::longValue).toArray());

      for (Tag v : into.getList("RemoteTrims", 10)) {
         CompoundTag trim = (CompoundTag)v;

         for (Tag q : into.getList("Streets", 10)) {
            CompoundTag r = (CompoundTag)q;
            if (r.getUUID("Id").equals(trim.getUUID("Id"))) {
               if (active.contains(Long.valueOf(r.getLong("A")))) {
                  trim.remove("Start");
               }

               if (active.contains(Long.valueOf(r.getLong("B")))) {
                  trim.remove("End");
               }
            }
         }
      }
   }

   static AutoCrossings.Split split(List<RoadRecord> roads, RoadRecord proposal, List<AutoCrossings.Hit> hits, List<CompoundTag> centers) {
      if (hits.isEmpty()) {
         return new AutoCrossings.Split(roads, List.of(), Map.of());
      } else {
         LinkedHashMap<UUID, List<AutoCrossings.Cut>> cuts = new LinkedHashMap<>();
         LinkedHashMap<BlockPos, RoadData.NodeMove> nodes = new LinkedHashMap<>();

         for (AutoCrossings.Hit hit : hits) {
            cuts.computeIfAbsent(proposal.id(), k -> new ArrayList<>()).add(new AutoCrossings.Cut(hit.proposed(), hit.center()));
            cuts.computeIfAbsent(hit.road(), k -> new ArrayList<>()).add(new AutoCrossings.Cut(hit.other(), hit.center()));
            BlockPos pos = at(hit.center());
            RoadGeometry.Node node = new RoadGeometry.Node(hit.center(), 0.0, 0.0);
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Owner", proposal.owner());
            tag.put("Node", RoadRecord.writeNode(node));
            tag.putBoolean("HeightExplicit", true);
            nodes.put(pos, new RoadData.NodeMove(null, pos, node, tag));
            if (centers.stream().noneMatch(t -> t.getLong("CenterPos") == pos.asLong())) {
               centers.add(AutoJunctions.config(pos, proposal.owner()));
            }
         }

         ArrayList<RoadRecord> output = new ArrayList<>();
         LinkedHashMap<UUID, List<RoadRecord>> pieces = new LinkedHashMap<>();

         for (RoadRecord road : roads) {
            List<AutoCrossings.Cut> points = cuts.get(road.id());
            if (points == null) {
               output.add(road);
            } else {
               TreeMap<Double, RoadGeometry.V> unique = new TreeMap<>();

               for (AutoCrossings.Cut c : points) {
                  if (c.station() > 0.01
                     && c.station() < road.mesh().length() - 0.01
                     && unique.keySet().stream().noneMatch(d -> Math.abs(d - c.station()) < 0.1)) {
                     unique.put(c.station(), c.center());
                  }
               }

               if (unique.isEmpty()) {
                  output.add(road);
               } else {
                  ArrayList<RoadRecord> segments = new ArrayList<>();
                  double from = 0.0;
                  BlockPos a = road.a();
                  RoadGeometry.Node start = road.start();
                  unique.put(road.mesh().length(), road.end().position());
                  int part = 0;

                  for (Entry<Double, RoadGeometry.V> entry : unique.entrySet()) {
                     double to = entry.getKey();
                     boolean last = to == road.mesh().length();
                     RoadGeometry.V p = entry.getValue();
                     RoadGeometry.Node end = last ? road.end() : new RoadGeometry.Node(p, RoadSplitGeometry.node(road.mesh(), to).yaw(), 0.0);
                     BlockPos b = last ? road.b() : at(p);
                     UUID id = part++ == 0 ? road.id() : UUID.nameUUIDFromBytes((road.id() + ":cross:" + a.asLong()).getBytes(StandardCharsets.UTF_8));
                     RoadRecord piece = RoadSplitGeometry.junctionRange(road, from, to, id, a, b, start, end, RoadSlicing.Connection.JUNCTION);
                     segments.add(piece);
                     from = to;
                     a = b;
                     start = end;
                  }

                  output.addAll(segments);
                  pieces.put(road.id(), List.copyOf(segments));

                  for (CompoundTag cx : centers) {
                     if (cx.getLong("CenterPos") == road.b().asLong()) {
                        ListTag arms = cx.getList("ArmRoads", 8);

                        for (int i = 0; i < arms.size(); i++) {
                           if (arms.getString(i).equals(road.id().toString())) {
                              arms.set(i, StringTag.valueOf(segments.get(segments.size() - 1).id().toString()));
                           }
                        }
                     }
                  }
               }
            }
         }

         return new AutoCrossings.Split(List.copyOf(output), List.copyOf(nodes.values()), Map.copyOf(pieces));
      }
   }

   static RoadRecord remap(RoadRecord road, Map<UUID, List<RoadRecord>> pieces) {
      RoadRecord next = road;

      for (Entry<UUID, List<RoadRecord>> entry : pieces.entrySet()) {
         for (RoadRecord piece : entry.getValue()) {
            if (!piece.id().equals(entry.getKey())) {
               HashSet<UUID> points = new HashSet<>();

               for (LanePoints.Point p : piece.settings().options().lanePoints().points()) {
                  points.add(p.id());
               }

               next = RoadSplitter.remapLinks(next, entry.getKey(), piece.id(), points);
            }
         }
      }

      return next;
   }

   private static BlockPos at(RoadGeometry.V p) {
      return BlockPos.containing(p.x(), p.y(), p.z());
   }

   private AutoCrossings() {
   }

   static record Cut(double station, RoadGeometry.V center) {
   }

   public static record Hit(UUID road, double proposed, double other, RoadGeometry.V center) {
   }

   private static record Segment(RoadGeometry.V start, RoadGeometry.V delta, double length, RoadBoundsIndex.Bounds bounds) {
   }

   static record Split(List<RoadRecord> roads, List<RoadData.NodeMove> nodes, Map<UUID, List<RoadRecord>> pieces) {
   }
}
