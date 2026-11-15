package com.sora.splineroads.world;

import com.sora.splineroads.core.InterchangePlanner;
import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadAutoTunnels;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.core.RoadStructureClearance;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadTransitions;
import com.sora.splineroads.core.RoadsideBypass;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ParallelSpui {
   private static final String KEY = "ParallelJunction";
   private static final Map<CompoundTag, ParallelSpui.Draft> DRAFTS = new LinkedHashMap<CompoundTag, ParallelSpui.Draft>(4, 0.75F, true) {
      @Override
      protected boolean removeEldestEntry(Entry<CompoundTag, ParallelSpui.Draft> e) {
         return this.size() > 2;
      }
   };

   public static boolean present(CompoundTag t) {
      return t.contains("ParallelJunction");
   }

   public static boolean active(CompoundTag t) {
      return present(t) && Interchanges.read(t.getCompound("Options")).preset() == InterchangePlanner.Preset.SPUI;
   }

   public static int ordinal(int lanes, int rampLanes) {
      if (lanes < (rampLanes == 2 ? 3 : 2)) {
         throw new IllegalArgumentException("下层每方向至少需要 " + (rampLanes == 2 ? 3 : 2) + " 条车道才能生成" + (rampLanes == 2 ? "双" : "单") + "车道匝道");
      } else {
         return rampLanes == 2 ? lanes - 2 : (lanes == 2 ? 2 : lanes - 1);
      }
   }

   static boolean capture(RoadData data, CompoundTag t, ServerPlayer player) {
      if (t.getLongArray("Points").length == 4 && !t.contains("Corridor")) {
         if (!present(t)) {
            long[] points = t.getLongArray("Points");
            Map<UUID, RoadRecord> logical = AutoJunctions.logical(data);
            CompoundTag found = null;

            for (int axis = 0; axis < 2; axis++) {
               BlockPos a = BlockPos.of(points[axis * 2]);
               BlockPos b = BlockPos.of(points[axis * 2 + 1]);
               RoadRecord upper = data.index
                  .roads
                  .values()
                  .stream()
                  .map(v -> v.record)
                  .filter(
                     rx -> rx.assembly() == null
                           && rx.junction() == null
                           && !rx.settings().style().ramp()
                           && (rx.a().equals(a) && rx.b().equals(b) || rx.a().equals(b) && rx.b().equals(a))
                  )
                  .findFirst()
                  .orElse(null);
               if (upper != null) {
                  for (CompoundTag center : data.junctions.values()) {
                     if (center.getBoolean("Auto") && center.contains("Spec")) {
                        RoadGeometry.V at = JunctionCodec.read(center.getCompound("Spec")).center();
                        RoadQueries.Projection q = RoadQueries.horizontal(upper.mesh(), at);
                        if (!(q.horizontalDistance() > 128.0) && !(q.sample().center().y() - at.y() < 5.0)) {
                           BlockPos node = BlockPos.of(center.getLong("CenterPos"));
                           List<RoadRecord> arms = logical.values().stream().filter(rx -> rx.a().equals(node) || rx.b().equals(node)).toList();
                           BlockPos c = BlockPos.of(points[(1 - axis) * 2]);
                           BlockPos d = BlockPos.of(points[(1 - axis) * 2 + 1]);
                           boolean selected = arms.stream().anyMatch(rx -> rx.a().equals(c) && rx.b().equals(d) || rx.a().equals(d) && rx.b().equals(c));
                           if (!selected) {
                              selected = arms.stream().anyMatch(rx -> rx.a().equals(c) || rx.b().equals(c))
                                 && arms.stream().anyMatch(rx -> rx.a().equals(d) || rx.b().equals(d));
                           }

                           if (selected) {
                              RoadGeometry.V along = q.sample().left().left().mul(-1.0);
                              List<RoadRecord> lower = arms.stream().filter(rx -> Math.abs(away(rx, node).dot(along)) > 0.9).toList();
                              if (lower.size() == 2 && !(away(lower.get(0), node).dot(away(lower.get(1), node)) > -0.9)) {
                                 CompoundTag candidate = new CompoundTag();
                                 candidate.putUUID("Center", center.getUUID("Id"));
                                 candidate.putUUID("Upper", upper.id());
                                 ListTag ids = new ListTag();
                                 lower.forEach(rx -> ids.add(StringTag.valueOf(rx.id().toString())));
                                 candidate.put("Lower", ids);
                                 if (found != null && !found.equals(candidate)) {
                                    throw new IllegalArgumentException("选区对应多个下层路口，请选择更靠近目标路口的道路端点");
                                 }

                                 found = candidate;
                              }
                           }
                        }
                     }
                  }
               }
            }

            if (found == null) {
               return false;
            }

            t.put("ParallelJunction", found);
            InterchangePlanner.Options o = Interchanges.read(t.getCompound("Options"));
            boolean left = data.index.roads.get(found.getUUID("Upper")).record.settings().options().leftTraffic();
            t.put(
               "Options",
               Interchanges.write(
                  new InterchangePlanner.Options(
                     InterchangePlanner.Preset.SPUI,
                     left,
                     o.lanes(),
                     32.0,
                     o.radius(),
                     o.clearance(),
                     o.upper(),
                     o.rampWidth(),
                     o.adjustEndpoints(),
                     false,
                     0.0
                  )
               )
            );
         }

         CompoundTag tag = t.getCompound("ParallelJunction");
         CompoundTag centerx = data.junctions.get(tag.getUUID("Center"));
         if (centerx == null) {
            throw new IllegalArgumentException("下层路口已改变，请重新选择道路");
         } else {
            tag.put("Junction", centerx.copy());
            tag.putInt("MinimumBuildHeight", data.minimumBuildHeight());
            RoadIndex.Built upper = data.index.roads.get(tag.getUUID("Upper"));
            if (upper == null) {
               throw new IllegalArgumentException("上层道路已不存在");
            } else {
               LinkedHashSet<UUID> hosts = new LinkedHashSet<>();
               hosts.add(upper.record.id());

               for (Tag v : tag.getList("Lower", 8)) {
                  hosts.add(UUID.fromString(v.getAsString()));
               }

               for (UUID id : hosts) {
                  RoadIndex.Built r = data.index.roads.get(id);
                  if (r == null) {
                     throw new IllegalArgumentException("下层道路已分割，请重新选择");
                  }

                  if (player != null) {
                     RoadData.requireOwner(player, r.record.owner());
                  }
               }

               if (player != null) {
                  RoadData.requireOwner(player, centerx.getUUID("Owner"));
               }

               double minX = Double.POSITIVE_INFINITY;
               double minZ = minX;
               double maxX = -minX;
               double maxZ = -minX;

               for (UUID id : hosts) {
                  RoadGeometry.Mesh mesh = data.index.roads.get(id).mesh;
                  minX = Math.min(minX, mesh.min().x());
                  minZ = Math.min(minZ, mesh.min().z());
                  maxX = Math.max(maxX, mesh.max().x());
                  maxZ = Math.max(maxZ, mesh.max().z());
               }

               InterchangePlanner.Options options = Interchanges.read(t.getCompound("Options"));
               RoadGeometry.V location = JunctionCodec.read(centerx.getCompound("Spec")).center();
               double padding = options.adjustEndpoints()
                  ? Math.max(
                     512.0,
                     Math.abs(RoadQueries.horizontal(upper.mesh, location).sample().center().y() - location.y()) / 0.055
                        + 4.0 * options.transition()
                        + JunctionPlanner.radius(JunctionCodec.read(centerx.getCompound("Spec")))
                        + 240.0
                  )
                  : 256.0;
               ListTag roads = new ListTag();

               for (RoadIndex.Built rx : data.index.roads.values()) {
                  if (rx.mesh.max().x() >= minX - padding
                     && rx.mesh.min().x() <= maxX + padding
                     && rx.mesh.max().z() >= minZ - padding
                     && rx.mesh.min().z() <= maxZ + padding) {
                     roads.add(rx.record.header());
                  }
               }

               tag.put("Roads", roads);
               t.put("Main1", RoadRecord.writeSettings(upper.record.settings()));
               t.put("Main2", RoadRecord.writeSettings(data.index.roads.get(UUID.fromString(tag.getList("Lower", 8).getString(0))).record.settings()));
               t.putString("Kind", "interchange");
               return true;
            }
         }
      } else {
         return false;
      }
   }

   private static RoadGeometry.V away(RoadRecord r, BlockPos center) {
      return (r.a().equals(center) ? r.end().position().sub(r.start().position()) : r.start().position().sub(r.end().position())).horizontalUnit();
   }

   private static UUID identity(UUID group, String name) {
      return UUID.nameUUIDFromBytes((group + ":parallel-spui:" + name).getBytes(StandardCharsets.UTF_8));
   }

   private static UUID group(CompoundTag t) {
      return t.hasUUID("Id") ? t.getUUID("Id") : UUID.nameUUIDFromBytes(Arrays.toString(t.getLongArray("Points")).getBytes(StandardCharsets.UTF_8));
   }

   private static Map<UUID, RoadRecord> context(CompoundTag t) {
      UUID group = group(t);
      HashSet<UUID> removed = new HashSet<>();

      for (int i = 0; i < 4; i++) {
         removed.add(identity(group, "ramp:" + i));
      }

      HashSet<UUID> pointIds = new HashSet<>();

      for (int i = 0; i < 8; i++) {
         pointIds.add(identity(group, "point:" + i));
      }

      LinkedHashMap<UUID, RoadRecord> roads = new LinkedHashMap<>();

      for (Tag v : t.getCompound("ParallelJunction").getList("Roads", 10)) {
         RoadRecord r = RoadRecord.load((CompoundTag)v);
         if (!removed.contains(r.id())) {
            LanePoints.Data md = LaneTopology.metadata(r);
            LanePoints.Data clean = md.points(md.points().stream().filter(p -> !pointIds.contains(p.id())).toList())
               .cuts(md.cuts().stream().filter(c -> !removed.contains(c.connection())).toList())
               .openings(md.openings().stream().filter(o -> !removed.contains(o.connection())).toList());
            roads.put(r.id(), clean.equals(md) ? r : r.withLanePoints(clean));
         }
      }

      return roads;
   }

   private static int slot(RoadRecord road, double station, RoadGeometry.V direction, int ordinal, int lanes) {
      RoadGeometry.Mesh mesh = road.rawMesh();
      RoadGeometry.Sample at = RoadStructures.sample(mesh, station);
      RoadProfile.Layout layout = RoadProfile.layout(mesh, at);
      ArrayList<LanePoints.Lane> same = new ArrayList<>();

      for (int i = 0; i < RoadProfile.catalog(road.settings()).lanes(); i++) {
         LanePoints.Lane lane = LanePoints.lane(mesh, station, i);
         if (lane.direction().dot(direction) > 0.85) {
            same.add(lane);
         }
      }

      same.sort(Comparator.comparingDouble(l -> Math.abs(l.position().sub(at.center()).dot(at.left()) - layout.medianCenter())));
      int index = ordinal < 0 ? same.size() - 1 : ordinal - 1;
      if (index >= 0 && index + lanes <= same.size()) {
         int chosen = same.get(index).index();
         LanePoints.group(mesh, station, chosen, lanes);
         return chosen;
      } else {
         throw new IllegalArgumentException("所选方向的连续车道数量不足");
      }
   }

   private static double station(RoadRecord road, RoadGeometry.V desired) {
      RoadQueries.Projection q = RoadQueries.horizontal(road.rawMesh(), desired);
      if (!(q.sample().distance() < 2.0) && !(q.sample().distance() > road.rawMesh().length() - 2.0) && !(q.horizontalDistance() > 128.0)) {
         return q.sample().distance();
      } else {
         throw new IllegalArgumentException("主路长度不足，请将上、下层道路向路口外延长后重新选择");
      }
   }

   static void requireOutsideDeck(RoadRecord lower, double station, int slot, int lanes, RoadRecord upper) {
      LanePoints.Lane lane = LanePoints.lane(lower.rawMesh(), station, slot);
      RoadQueries.Projection q = RoadQueries.horizontal(upper.rawMesh(), lane.position());
      double extent = Math.abs(lane.direction().left().dot(q.sample().left())) * lane.width() / 2.0;
      if (q.horizontalDistance() - extent <= q.sample().halfWidth() + 1.0E-6) {
         throw new IllegalArgumentException("下方道路从外向内第" + (lanes == 2 ? "三" : "二") + "车道须完整位于高架投影外，请加宽下方道路或收窄／移动高架");
      }
   }

   private static void requireLandingOutsideDeck(Map<UUID, RoadRecord> all, RoadRecord ramp, RoadRecord upper) {
      LanePoints.Link link = LaneTopology.metadata(ramp).link();
      boolean down = link.options().sourceExtra();
      LaneRoadChain.Position at = LaneRoadChain.of(all, down ? link.to() : link.from()).at(down ? link.targetOffset() : 0.0);
      RoadGeometry.Mesh mesh = at.road().rawMesh();
      LanePoints.Lane selected = LanePoints.lane(mesh, at.point());
      RoadProfile.Layout layout = RoadProfile.layout(mesh, RoadStructures.sample(mesh, selected.station()));
      int side = selected.position().sub(RoadStructures.sample(mesh, selected.station()).center()).dot(RoadStructures.sample(mesh, selected.station()).left())
            < layout.medianCenter()
         ? -1
         : 1;
      int boundary = at.point().lane() - (link.options().lanes() == 1 && layout.lanesOnSide(side) == 2 ? 1 : 0);
      requireOutsideDeck(at.road(), selected.station(), boundary, link.options().lanes(), upper);
   }

   private static void fit(Map<UUID, RoadRecord> all, UUID id, RoadGeometry.V desired, double margin, BlockPos fixed) {
      RoadRecord road = all.get(id);
      RoadGeometry.Mesh mesh = road.rawMesh();
      RoadQueries.Projection q = RoadQueries.horizontal(mesh, desired);
      double d = q.sample().distance() + desired.sub(q.sample().center()).dot(q.sample().left().left().mul(-1.0));
      double before = Math.max(0.0, margin - d);
      double after = Math.max(0.0, d + margin - mesh.length());
      if (road.a().equals(fixed) && d >= 2.0) {
         before = 0.0;
      }

      if (road.b().equals(fixed) && d <= mesh.length() - 2.0) {
         after = 0.0;
      }

      if (!(before < 1.0E-5) || !(after < 1.0E-5)) {
         for (boolean first : new boolean[]{true, false}) {
            if ((first ? before : after) > 1.0E-5) {
               BlockPos pos = first ? road.a() : road.b();
               RoadGeometry.Node point = first ? road.start() : road.end();
               if (pos.equals(fixed) || Math.abs(point.grade()) > 1.0E-5) {
                  throw new IllegalArgumentException("只可向路口外延长水平主路端点");
               }

               for (RoadRecord other : all.values()) {
                  if (!other.id().equals(id)
                     && (
                        other.a().equals(pos) && other.start().position().distance(point.position()) < 0.01
                           || other.b().equals(pos) && other.end().position().distance(point.position()) < 0.01
                     )) {
                     throw new IllegalArgumentException("主路外端点已连接其他道路，请延长相连道路后重新选择");
                  }
               }
            }
         }

         all.put(id, extend(road, before, after));
         if (road.fragment() != null) {
            RoadSlicing.Fragment f = road.fragment();
            double shift = Math.max(0.0, before - f.origin());
            double total = Math.max(f.total() + shift, f.origin() + shift + mesh.length() + after);

            for (RoadRecord otherx : new ArrayList<>(all.values())) {
               if (!otherx.id().equals(id) && otherx.fragment() != null && otherx.fragment().source().equals(f.source())) {
                  RoadSlicing.Fragment g = otherx.fragment();
                  all.put(
                     otherx.id(),
                     otherx.fragment(new RoadSlicing.Fragment(g.source(), g.origin() + shift, total, g.connection(), g.applied(), g.geometry(), g.supports()))
                  );
               }
            }
         }
      }
   }

   private static RoadRecord extend(RoadRecord road, double before, double after) {
      RoadGeometry.Mesh mesh = road.rawMesh();
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
      RoadGeometry.Sample a = mesh.first();
      RoadGeometry.Sample b = mesh.last();

      for (double d = before; d > 1.0E-7; d = Math.max(0.0, d - 1.0)) {
         samples.add(new RoadGeometry.Sample(a.center().add(a.left().left().mul(d)), a.left(), 0.0, a.halfWidth()));
      }

      samples.addAll(mesh.samples());

      for (double d = Math.min(1.0, after); d > 0.0; d = Math.min(after, d + 1.0)) {
         samples.add(new RoadGeometry.Sample(b.center().add(b.left().left().mul(-d)), b.left(), 0.0, b.halfWidth()));
         if (d >= after) {
            break;
         }
      }

      RoadGeometry.Settings settings = road.settings();
      RoadTransitions.Ends ends = settings.options().ends();
      RoadStreetscape.Config decor = settings.options().streetscape();
      RoadStreetscape.SideStructures sides = decor.sides();
      RoadStreetscape.SideStructures grown = new RoadStreetscape.SideStructures(
         grow(sides.negativeRaised(), mesh.length(), before, after),
         grow(sides.positiveRaised(), mesh.length(), before, after),
         grow(sides.negativeCut(), mesh.length(), before, after),
         grow(sides.positiveCut(), mesh.length(), before, after)
      );
      List<RoadsideBypass.Span> crossings = decor.crossings()
         .stream()
         .map(c -> new RoadsideBypass.Span(c.side(), c.start() + before, c.from() + before, c.to() + before, c.end() + before, c.delta()))
         .toList();
      decor = decor.sideStructures(grown).tunnels(grow(decor.tunnels(), mesh.length(), before, after)).crossings(crossings);
      settings = settings.options(settings.options().streetscape(decor).ends(ends.paintPhase(ends.paintPhase() - before)));
      if (before > 0.0) {
         LanePoints.Data md = settings.options().lanePoints();
         List<LaneSections.Cut> cuts = md.cuts()
            .stream()
            .map(
               c -> new LaneSections.Cut(
                     c.connection(),
                     c.lane(),
                     c.sign(),
                     c.begin() + before,
                     c.end() + before,
                     c.transition(),
                     c.replacement(),
                     c.temporary(),
                     c.arrival(),
                     c.rectangular(),
                     c.underpass()
                  )
            )
            .toList();
         List<LaneAdditions.Addition> added = md.additions()
            .stream()
            .map(a0 -> new LaneAdditions.Addition(a0.connection(), a0.slot(), a0.sign(), a0.station() + before, a0.transition()))
            .toList();
         settings = settings.options(settings.options().lanePoints(md.cuts(cuts).additions(added)));
      }

      RoadGeometry.Mesh shape = RoadRibbon.mesh(samples, settings);
      RoadGeometry.Node start = before > 0.0 ? RoadRibbon.start(shape) : road.start();
      RoadGeometry.Node end = after > 0.0 ? RoadRibbon.end(shape) : road.end();
      RoadSlicing.Fragment fragment = road.fragment();
      if (fragment != null) {
         double shift = Math.max(0.0, before - fragment.origin());
         List<RoadSlicing.Patch> patches = fragment.geometry()
            .patches()
            .stream()
            .map(p -> new RoadSlicing.Patch(p.from() + before, p.to() + before, p.strips()))
            .toList();
         fragment = new RoadSlicing.Fragment(
            fragment.source(),
            fragment.origin() + shift - before,
            Math.max(fragment.total() + shift, fragment.origin() + shift + mesh.length() + after),
            fragment.connection(),
            fragment.applied(),
            new RoadSlicing.Geometry(patches),
            fragment.supports()
         );
      }

      return new RoadRecord(
         road.id(),
         road.owner(),
         before > 0.0 ? RampJunctions.at(start.position()) : road.a(),
         after > 0.0 ? RampJunctions.at(end.position()) : road.b(),
         start,
         end,
         settings,
         road.automatic(),
         road.clearance(),
         List.of(),
         road.endCaps(),
         road.buildVersion(),
         road.assembly(),
         shape.samples(),
         road.furniturePhase(),
         road.junction(),
         road.toll(),
         fragment
      );
   }

   private static List<RoadStreetscape.Span> grow(List<RoadStreetscape.Span> spans, double length, double before, double after) {
      return spans.stream()
         .map(
            s -> new RoadStreetscape.Span(s.from() <= 1.0E-6 ? 0.0 : s.from() + before, s.to() >= length - 1.0E-6 ? length + before + after : s.to() + before)
         )
         .toList();
   }

   private static LanePoints.Point point(Map<UUID, RoadRecord> all, UUID road, UUID id, double station, int slot) {
      RoadRecord r = all.get(road);
      LanePoints.Point p = LanePoints.point(id, LanePoints.Origin.MANUAL, r.rawMesh(), station, slot);
      ArrayList<LanePoints.Point> points = new ArrayList<>(LaneTopology.metadata(r).points());
      points.add(p);
      all.put(road, r.withLanePoints(LaneTopology.metadata(r).points(points)));
      return p;
   }

   private static double setback(JunctionSpec spec, RoadRecord lower) {
      double cycle = RoadsideBypass.eligible(lower.rawMesh()) ? 57.421875 : 0.0;
      return Math.max(64.0, JunctionPlanner.radius(spec) + 48.0 + cycle);
   }

   private static double continuation(RoadRecord lower, InterchangePlanner.Options options) {
      double cycle = RoadsideBypass.eligible(lower.rawMesh()) ? 65.421875 : 0.0;
      return Math.max(64.0, options.transition()) + cycle;
   }

   private static ParallelSpui.Draft draft(CompoundTag t) {
      synchronized (DRAFTS) {
         ParallelSpui.Draft cached = DRAFTS.get(t);
         if (cached != null) {
            return cached;
         }
      }

      ParallelSpui.Draft result = draftUncached(t, null);
      synchronized (DRAFTS) {
         DRAFTS.put(t.copy(), result);
         return result;
      }
   }

   private static ParallelSpui.Draft draftUncached(CompoundTag t, RoadData data) {
      InterchangePlanner.Options o = Interchanges.read(t.getCompound("Options"));
      if (o.preset() != InterchangePlanner.Preset.SPUI) {
         throw new IllegalArgumentException("已有下层平交路口的三道路布局请选择单点式菱形");
      } else {
         Map<UUID, RoadRecord> all = context(t);
         CompoundTag tag = t.getCompound("ParallelJunction");
         UUID group = group(t);
         RoadRecord upper = all.get(tag.getUUID("Upper"));
         JunctionSpec spec = JunctionCodec.read(tag.getCompound("Junction").getCompound("Spec"));
         RoadGeometry.V center = spec.center();
         BlockPos node = BlockPos.of(tag.getCompound("Junction").getLong("CenterPos"));
         if (o.leftTraffic() != upper.settings().options().leftTraffic()) {
            throw new IllegalArgumentException("立交行驶方向须与已有上层道路一致");
         } else {
            for (Tag v : tag.getList("Lower", 8)) {
               RoadGeometry.Mesh mesh = all.get(UUID.fromString(v.getAsString())).rawMesh();
               RoadProfile.Layout layout = RoadProfile.layout(mesh, mesh.first());
               ordinal(layout.lanesOnSide(-1), o.lanes());
               ordinal(layout.lanesOnSide(1), o.lanes());
            }

            if (o.adjustEndpoints()) {
               double height = RoadQueries.horizontal(upper.mesh(), center).sample().center().y() - center.y();
               double run = Math.max(160.0, height / 0.055 + 2.0 * o.transition() + 96.0);
               double margin = Math.max(64.0, 2.0 * o.transition());

               for (Tag v : tag.getList("Lower", 8)) {
                  UUID id = UUID.fromString(v.getAsString());
                  RoadGeometry.V outward = away(all.get(id), node);
                  double setback = setback(spec, all.get(id));
                  fit(all, id, center.add(outward.mul(setback)), Math.max(margin, continuation(all.get(id), o)), node);
                  fit(all, upper.id(), center.add(outward.mul(setback + run)), margin, null);
               }

               upper = all.get(upper.id());
            }

            LinkedHashSet<UUID> hosts = new LinkedHashSet<>();
            hosts.add(upper.id());
            ArrayList<LanePoints.Link> links = new ArrayList<>();
            ArrayList<UUID> pointIds = new ArrayList<>();
            int movement = 0;

            for (Tag v : tag.getList("Lower", 8)) {
               RoadRecord lower = all.get(UUID.fromString(v.getAsString()));
               hosts.add(lower.id());
               RoadGeometry.V away = away(lower, node);
               if (lower.settings().options().leftTraffic() != o.leftTraffic()) {
                  throw new IllegalArgumentException("上、下层道路的左右行驶设置必须一致");
               }

               double setback = setback(spec, lower);
               double local = station(lower, center.add(away.mul(setback)));

               for (boolean down : new boolean[]{true, false}) {
                  RoadGeometry.V direction = away.mul(down ? -1.0 : 1.0);
                  int count = 0;

                  for (int i = 0; i < RoadProfile.catalog(lower.settings()).lanes(); i++) {
                     if (LanePoints.lane(lower.rawMesh(), local, i).direction().dot(direction) > 0.85) {
                        count++;
                     }
                  }

                  int chosen = slot(lower, local, direction, ordinal(count, o.lanes()), o.lanes());
                  double height = RoadQueries.horizontal(upper.mesh(), center).sample().center().y() - center.y();
                  double run = Math.max(160.0, height / 0.055 + 2.0 * o.transition() + 96.0);
                  double high = station(upper, center.add(away.mul(setback + run)));
                  int outer = slot(upper, high, direction, -1, 1);
                  int boundary = slot(lower, local, direction, count - (o.lanes() == 2 ? 2 : 1), 1);
                  requireOutsideDeck(lower, local, boundary, o.lanes(), upper);
                  UUID loId = identity(group, "point:" + movement * 2);
                  UUID hiId = identity(group, "point:" + (movement * 2 + 1));
                  pointIds.add(loId);
                  pointIds.add(hiId);
                  point(all, lower.id(), loId, local, chosen);
                  point(all, upper.id(), hiId, high, outer);
                  LanePoints.Ref lowRef = LanePoints.Ref.lane(lower.id(), loId);
                  LanePoints.Ref highRef = LanePoints.Ref.lane(upper.id(), hiId);
                  LanePoints.Options options = new LanePoints.Options(
                     LanePoints.Path.AUTO,
                     down ? LanePoints.Departure.EXTRA : LanePoints.Departure.TEMPORARY,
                     down ? LanePoints.Arrival.MERGE : LanePoints.Arrival.EXTRA,
                     o.radius(),
                     o.transition(),
                     LanePoints.Elevation.AUTO,
                     LanePoints.Landing.FLEXIBLE,
                     false,
                     false,
                     o.lanes()
                  );
                  links.add(new LanePoints.Link(down ? highRef : lowRef, down ? lowRef : highRef, options, null).withProtectedMerge().withRectangularClosure());
                  movement++;
               }
            }

            ArrayList<RoadRecord> ramps = new ArrayList<>();

            for (int ix = 0; ix < links.size(); ix++) {
               RoadRecord r = LaneRamps.aboveFloor(data, all, identity(group, "ramp:" + ix), upper.owner(), links.get(ix), center.y());
               r = r.alignment(group, r.mesh());
               ramps.add(r);
               all.put(r.id(), r);
               links.set(ix, LaneTopology.metadata(r).link());
            }

            boolean hostsExtended = false;
            if (o.adjustEndpoints()) {
               for (Tag v : tag.getList("Lower", 8)) {
                  UUID id = UUID.fromString(v.getAsString());
                  RoadRecord lower = all.get(id);
                  RoadGeometry.V outward = away(lower, node);
                  double end = 0.0;

                  for (RoadRecord ramp : ramps) {
                     RoadGeometry.Mesh mesh = ramp.mesh();
                     double depth = mesh.settings().thickness() + RoadInfrastructure.girderDepth(mesh);

                     for (RoadGeometry.Sample sample : mesh.samples()) {
                        RoadGeometry.V delta = sample.center().sub(center);
                        double along = delta.dot(outward);
                        double across = Math.abs(delta.dot(outward.left()));
                        double top = RoadQueries.horizontal(lower.rawMesh(), sample.center()).sample().center().y();
                        if (along > 0.0
                           && across < lower.rawMesh().first().halfWidth() + sample.halfWidth()
                           && sample.center().y() - depth - top < RoadStructureClearance.headroom(lower.rawMesh()) + 0.25) {
                           end = Math.max(end, along);
                        }
                     }
                  }

                  fit(all, id, center.add(outward.mul(end)), continuation(lower, o), node);
                  hostsExtended |= all.get(id).rawMesh().length() > lower.rawMesh().length() + 1.0E-5;
               }
            }

            LaneCrossSections.reconcile(all);
            if (o.adjustEndpoints()) {
               for (int pass = 0; pass < 8; pass++) {
                  boolean extended = hostsExtended;
                  hostsExtended = false;

                  for (Tag v : tag.getList("Lower", 8)) {
                     UUID id = UUID.fromString(v.getAsString());
                     RoadRecord lower = all.get(id);
                     RoadGeometry.V outward = away(lower, node);
                     double end = 0.0;

                     for (LaneSections.Cut cut : LaneTopology.metadata(lower).cuts()) {
                        for (double d : new double[]{cut.begin(), cut.end()}) {
                           end = Math.max(end, RoadStructures.sample(lower.rawMesh(), d).center().sub(center).dot(outward));
                        }
                     }

                     fit(all, id, center.add(outward.mul(end)), continuation(lower, o), node);
                     extended |= all.get(id).rawMesh().length() > lower.rawMesh().length() + 1.0E-5;
                  }

                  if (!extended) {
                     break;
                  }

                  LaneCrossSections.reconcile(all);

                  for (int ix = 0; ix < ramps.size(); ix++) {
                     RoadRecord old = ramps.get(ix);
                     RoadRecord r = LaneRamps.aboveFloor(data, all, old.id(), old.owner(), links.get(ix), center.y());
                     r = r.alignment(group, r.mesh());
                     ramps.set(ix, r);
                     all.put(r.id(), r);
                     links.set(ix, LaneTopology.metadata(r).link());
                  }

                  LaneCrossSections.reconcile(all);
                  if (pass == 7) {
                     throw new IllegalArgumentException("下层道路延长与匝道落点未能稳定，请增加过渡长度");
                  }
               }
            }

            double minimum = data != null
               ? (double)data.minimumBuildHeight()
               : (tag.contains("MinimumBuildHeight") ? (double)tag.getInt("MinimumBuildHeight") : -2.1474836E9F);
            HashSet<UUID> checked = new HashSet<>();

            for (RoadRecord road : all.values()) {
               if (RoadCycleChain.eligible(road)
                  && !checked.contains(road.id())
                  && ramps.stream().anyMatch(rx -> RoadIndex.overlapXZ(road.mesh(), rx.mesh(), 3.0))) {
                  RoadCycleChain chain = RoadCycleChain.of(all, road.id());
                  ArrayList<RoadsideBypass.Neighbor> neighbors = new ArrayList<>();

                  for (RoadRecord other : all.values()) {
                     if ((other.settings().style().ramp() || other.railway() || LaneTopology.metadata(other).link() != null)
                        && RoadIndex.overlapXZ(chain.mesh(), other.mesh(), 3.0)) {
                        neighbors.add(new RoadsideBypass.Neighbor(other.mesh(), RoadAutoTunnels.roofRegions(other.structures())));
                     }
                  }

                  List<RoadsideBypass.Span> spans = RoadsideBypass.plan(chain.mesh(), neighbors);
                  RoadsideBypass.validateFloor(chain.mesh(), spans, minimum);

                  for (RoadCycleChain.Leg leg : chain.legs()) {
                     checked.add(leg.road().id());
                  }
               }
            }

            for (RoadRecord ramp : ramps) {
               requireLandingOutsideDeck(all, ramp, all.get(upper.id()));
            }

            Map<UUID, RoadRecord> original = context(t);

            for (RoadRecord r : all.values()) {
               if (original.containsKey(r.id()) && !Objects.equals(r.fragment(), original.get(r.id()).fragment())) {
                  hosts.add(r.id());
               }
            }

            return new ParallelSpui.Draft(all, hosts.stream().map(all::get).toList(), List.copyOf(ramps), List.copyOf(pointIds), center);
         }
      }
   }

   public static InterchangePlanner.Plan plan(CompoundTag t) {
      ParallelSpui.Draft d = draft(t);
      ArrayList<InterchangePlanner.Leg> legs = new ArrayList<>();

      for (RoadRecord r : d.hosts()) {
         legs.add(new InterchangePlanner.Leg("既有主路", 0, 0, r.mesh()));
      }

      for (int i = 0; i < d.ramps().size(); i++) {
         legs.add(new InterchangePlanner.Leg(i % 2 == 0 ? "上层汇出并入下层" : "下层保留车道分离汇入上层", i, i + 1, d.ramps().get(i).mesh()));
      }

      CompoundTag junction = t.getCompound("ParallelJunction").getCompound("Junction");
      BlockPos node = BlockPos.of(junction.getLong("CenterPos"));
      Set<UUID> hosts = d.hosts().stream().map(RoadRecord::id).collect(Collectors.toSet());

      for (RoadRecord r : d.context().values()) {
         if (r.junction() != null && t.getCompound("ParallelJunction").getUUID("Center").equals(r.assembly())) {
            legs.add(new InterchangePlanner.Leg("既有下层路口", -1, -1, r.mesh(), r.junction()));
         } else if (r.junction() == null && !hosts.contains(r.id()) && (r.a().equals(node) || r.b().equals(node))) {
            legs.add(new InterchangePlanner.Leg("既有横向道路", -1, -1, r.mesh()));
         }
      }

      Map<UUID, RoadRecord> original = context(t);
      ArrayList<RoadGeometry.Node> anchors = new ArrayList<>();

      for (Tag v : t.getList("Nodes", 10)) {
         RoadGeometry.Node n = RoadRecord.readNode((CompoundTag)v);

         for (RoadRecord host : d.hosts()) {
            RoadRecord old = original.get(host.id());
            if (n.position().distance(old.start().position()) < 0.01) {
               n = host.start();
               break;
            }

            if (n.position().distance(old.end().position()) < 0.01) {
               n = host.end();
               break;
            }
         }

         anchors.add(n);
      }

      return new InterchangePlanner.Plan(
         legs,
         4,
         d.ramps().stream().mapToDouble(rx -> RoadRibbon.minRadius(rx.mesh())).min().orElse(0.0),
         d.ramps().stream().mapToDouble(rx -> rx.mesh().max().y()).max().orElse(d.center().y()),
         d.center(),
         anchors
      );
   }

   static String build(ServerLevel level, ServerPlayer player, CompoundTag t) {
      RoadData data = RoadData.get(level);
      ParallelSpui.Draft d = draftUncached(t, data);
      UUID group = group(t);
      ArrayList<RoadIndex.Built> built = new ArrayList<>();
      HashSet<UUID> removed = new HashSet<>();
      HashSet<BlockPos> selected = new HashSet<>();

      for (RoadRecord r : d.hosts()) {
         built.add(new RoadIndex.Built(r));
         removed.add(r.id());
         selected.add(r.a());
         selected.add(r.b());
      }

      for (RoadRecord r : d.ramps()) {
         built.add(new RoadIndex.Built(r));
         if (data.index.roads.containsKey(r.id())) {
            removed.add(r.id());
         }

         selected.add(r.a());
         selected.add(r.b());
      }

      ArrayList<RoadData.NodeMove> moves = new ArrayList<>();
      LinkedHashMap<UUID, RoadRecord> logical = new LinkedHashMap<>();
      HashMap<Long, Long> positions = new HashMap<>();

      for (RoadRecord host : d.hosts()) {
         RoadRecord old = data.index.roads.get(host.id()).record;
         double before = 0.0;
         double after = 0.0;

         for (boolean first : new boolean[]{true, false}) {
            BlockPos from = first ? old.a() : old.b();
            BlockPos to = first ? host.a() : host.b();
            if (!from.equals(to)) {
               RoadGeometry.Node source = first ? old.start() : old.end();
               RoadGeometry.Node target = first ? host.start() : host.end();
               NodeEntity marker = RoadData.requireNode(level, from, player);
               moves.add(new RoadData.NodeMove(from, to, target, marker.saveWithoutMetadata(level.registryAccess())));
               selected.add(from);
               positions.put(from.asLong(), to.asLong());
               if (first) {
                  before = source.position().distance(target.position());
               } else {
                  after = source.position().distance(target.position());
               }
            }
         }

         if (data.streets.containsKey(host.id())) {
            RoadRecord full = data.streets.get(host.id());
            if (before > 0.0 || after > 0.0) {
               full = extend(full, before, after);
            }

            if (full.fragment() != null && host.fragment() != null) {
               RoadSlicing.Fragment f = full.fragment();
               RoadSlicing.Fragment live = host.fragment();
               double trim = RoadQueries.horizontal(full.rawMesh(), host.rawMesh().first().center()).sample().distance();
               full = full.fragment(
                  new RoadSlicing.Fragment(
                     f.source(), Math.max(0.0, live.origin() - trim), live.total(), f.connection(), f.applied(), f.geometry(), f.supports()
                  )
               );
            }

            logical.put(host.id(), full);
         }
      }

      data.replaceAssembly(level, player, built, removed, selected, moves);

      for (Entry<UUID, RoadRecord> entry : logical.entrySet()) {
         data.streets.put(entry.getKey(), entry.getValue().withLanePoints(LaneTopology.metadata(data.index.roads.get(entry.getKey()).record)));
      }

      t = t.copy();
      Interchanges.clearRemovedDesign(t);
      long[] points = t.getLongArray("Points");

      for (int i = 0; i < points.length; i++) {
         points[i] = positions.getOrDefault(points[i], points[i]);
      }

      t.putLongArray("Points", points);
      ListTag nodes = new ListTag();

      for (Tag v : t.getList("Nodes", 10)) {
         RoadGeometry.Node n = RoadRecord.readNode((CompoundTag)v);
         Optional<RoadData.NodeMove> move = moves.stream().filter(m -> m.from().equals(RampJunctions.at(n.position()))).findFirst();
         nodes.add(RoadRecord.writeNode(move.isPresent() ? move.get().target() : n));
      }

      t.put("Nodes", nodes);
      t.putUUID("Id", group);
      t.putUUID("Owner", d.hosts().get(0).owner());
      t.getCompound("ParallelJunction").remove("Roads");
      data.interchanges.put(group, t);
      data.setDirty();
      return "单点菱形已连接已有下层路口，四条匝道按指定车道生成";
   }

   static void remove(ServerLevel level, ServerPlayer player, CompoundTag saved) {
      RoadData data = RoadData.get(level);
      capture(data, saved, player);
      UUID group = saved.getUUID("Id");
      Map<UUID, RoadRecord> cleaned = context(saved);
      ArrayList<RoadIndex.Built> built = new ArrayList<>();
      HashSet<UUID> removed = new HashSet<>();
      HashSet<BlockPos> selected = new HashSet<>();

      for (RoadIndex.Built old : data.index.roads.values()) {
         RoadRecord r = cleaned.get(old.record.id());
         if (r == null) {
            if (group.equals(old.record.assembly())) {
               removed.add(old.record.id());
            }
         } else if (!r.settings().options().lanePoints().equals(LaneTopology.metadata(old.record))) {
            built.add(new RoadIndex.Built(r));
            removed.add(r.id());
            selected.add(r.a());
            selected.add(r.b());
         }
      }

      data.replaceAssembly(level, player, built, removed, selected);
      data.interchanges.remove(group);
      data.setDirty();
   }

   private ParallelSpui() {
   }

   private static record Draft(Map<UUID, RoadRecord> context, List<RoadRecord> hosts, List<RoadRecord> ramps, List<UUID> points, RoadGeometry.V center) {
   }
}
