package com.sora.splineroads.world;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSlips;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadEndpointSections;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadTransitions;
import com.sora.splineroads.core.RoadTunnelFit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class AutoJunctions {
   static AutoJunctions.StreetChange streetAddition(ServerLevel level, ServerPlayer player, RoadRecord next, List<CompoundTag> plannedCenters) {
      RoadData data = RoadData.get(level);
      if (data.index.roads.containsKey(next.id())) {
         throw new IllegalArgumentException("此规划道路已经存在");
      } else {
         Set<BlockPos> active = Set.of(next.a(), next.b());
         CompoundTag before = context(data, active);
         List<RoadRecord> roads = streets(before);
         roads.add(next);
         List<CompoundTag> configs = centers(before);

         for (CompoundTag planned : plannedCenters) {
            if (active.contains(BlockPos.of(planned.getLong("CenterPos")))
               && configs.stream().noneMatch(c -> c.getLong("CenterPos") == planned.getLong("CenterPos"))) {
               CompoundTag config = config(BlockPos.of(planned.getLong("CenterPos")), next.owner());
               config.putBoolean("LeftTraffic", next.settings().options().leftTraffic());
               configs.add(config);
            }
         }

         AutoJunctions.Draft draft = plan(roads, configs, before);
         ArrayList<RoadData.NodeMove> moves = new ArrayList<>();

         for (boolean first : new boolean[]{true, false}) {
            BlockPos p = first ? next.a() : next.b();
            RoadGeometry.Node target = first ? next.start() : next.end();
            BlockEntity tag = level.getBlockEntity(p);
            if (tag instanceof NodeEntity) {
               NodeEntity node = (NodeEntity)tag;
               if (player != null) {
                  RoadData.requireOwner(player, node.owner);
               }

               if (node.constructionNode().position().distance(target.position()) > 0.01) {
                  throw new IllegalArgumentException("接口端点在预览后移动，请重新规划");
               }
            } else {
               CompoundTag tagx = new CompoundTag();
               tagx.put("Node", RoadRecord.writeNode(target));
               tagx.putBoolean("HeightExplicit", true);
               tagx.putUUID("Owner", next.owner());
               moves.add(new RoadData.NodeMove(null, p, target, tagx));
            }
         }

         HashSet<UUID> groups = new HashSet<>();

         for (CompoundTag c : centers(before)) {
            groups.add(c.getUUID("Id"));
         }

         for (CompoundTag c : draft.centers()) {
            groups.add(c.getUUID("Id"));
         }

         StreetNetworkGeometry.checkGrades(draft.roads());
         return new AutoJunctions.StreetChange(before, draft, List.copyOf(moves), Set.copyOf(groups), next.id());
      }
   }

   static void commitStreet(ServerLevel level, ServerPlayer player, AutoJunctions.StreetChange change) {
      commit(level, player, change.before(), change.draft(), change.moves(), Set.of(change.added()), Set.of());
   }

   public static List<RoadRecord> selected(AutoJunctions.Draft draft, UUID id) {
      HashMap<UUID, CompoundTag> centers = new HashMap<>();
      draft.centers().forEach(t -> centers.put(t.getUUID("Id"), t));
      HashSet<String> ids = new HashSet<>();
      ids.add(id.toString());

      for (RoadRecord piece : draft.newSplits().getOrDefault(id, List.of())) {
         ids.add(piece.id().toString());
      }

      return draft.roads().stream().filter(r -> {
         if (ids.contains(r.id().toString())) {
            return true;
         } else if (r.junction() == null) {
            return false;
         } else {
            CompoundTag t = centers.get(r.assembly());
            if (t == null) {
               return false;
            } else {
               ListTag arms = t.getList("ArmRoads", 8);
               int arm = r.junction().get().arm();
               return arm >= 0 && arm < arms.size() && ids.contains(arms.getString(arm));
            }
         }
      }).toList();
   }

   public static boolean ordinary(RoadRecord r) {
      return r.assembly() == null
         && RoadProfile.catalog(r.settings().style()).type() == RoadProfile.Type.ORDINARY
         && r.settings().mode() != RoadGeometry.Mode.RING;
   }

   public static Map<UUID, RoadRecord> logical(RoadData data) {
      LinkedHashMap<UUID, RoadRecord> result = new LinkedHashMap<>();
      data.index
         .roads
         .values()
         .stream()
         .map(b -> b.record)
         .filter(r -> ordinary(r) || corridorStreet(data, r))
         .sorted(Comparator.comparing(RoadRecord::id))
         .forEach(r -> result.put(r.id(), r));
      data.streets.keySet().forEach(id -> result.put(id, data.editableRoad(id)));

      for (Entry<UUID, RoadRecord> entry : data.streets.entrySet()) {
         RoadIndex.Built live = data.index.roads.get(entry.getKey());
         RoadRecord saved = entry.getValue();
         if (live != null
            && live.record.fragment() != null
            && saved.fragment() != null
            && ordinary(live.record)
            && live.record.fragment().source().equals(saved.fragment().source())
            && center(data, saved.a()) == null
            && center(data, saved.b()) == null
            && ringAt(data, saved.a()) == null
            && ringAt(data, saved.b()) == null) {
            result.put(entry.getKey(), live.record);
         }
      }

      return result;
   }

   private static boolean corridorStreet(RoadData d, RoadRecord r) {
      CompoundTag group = d.interchanges.get(r.assembly());
      return r.junction() == null
         && group != null
         && group.contains("Corridor")
         && RoadProfile.catalog(r.settings().style()).type() == RoadProfile.Type.ORDINARY;
   }

   public static boolean incompatible(RoadData d, BlockPos a, BlockPos b, UUID id, RoadGeometry.Settings s) {
      Map<UUID, RoadRecord> roads = logical(d);
      RoadRecord previous = roads.get(id);

      for (RoadRecord r : roads.values()) {
         if (!r.id().equals(id)) {
            for (boolean start : new boolean[]{true, false}) {
               BlockPos p = start ? a : b;
               if (touches(r, p)) {
                  boolean first = r.a().equals(p);
                  RoadGeometry.Settings target = r.fragment() == null
                        && r.assembly() == null
                        && !r.settings().options().ends().persistent()
                        && !RoadEndpointSections.changed(r.mesh(), first)
                     ? RoadData.authoredSection(r.settings())
                     : RoadData.endpointSection(new RoadIndex.Built(r), p);
                  target = RoadEndpointSections.orient(target, first == start);
                  if ((previous == null || !previous.sameDerivedLaneEnd(s, start, target)) && !RoadTransitions.compatible(s, target)) {
                     return true;
                  }
               }
            }
         }
      }

      return false;
   }

   public static boolean forced(CompoundTag t, String side) {
      return t.contains("ForceJunction" + side) ? t.getBoolean("ForceJunction" + side) : t.getBoolean("ForceJunction");
   }

   public static boolean requiresJunction(CompoundTag t, RoadGeometry.Settings s) {
      if (RoadProfile.catalog(s.style()).type() != RoadProfile.Type.ORDINARY) {
         return false;
      } else if (RoadTunnelFit.enabled(s)
         && !t.getBoolean("CenterA")
         && !t.getBoolean("CenterB")
         && t.getInt("DegreeA") < 2
         && t.getInt("DegreeB") < 2
         && !forced(t, "A")
         && !forced(t, "B")) {
         return false;
      } else if (!t.getBoolean("AutoJunction") && !forced(t, "A") && !forced(t, "B")) {
         for (String side : List.of("A", "B")) {
            if (incompatibleSection(t, side, s)) {
               return true;
            }
         }

         if (t.contains("CrossingCandidates")) {
            CompoundTag command = t.copy();
            command.put("Settings", RoadRecord.writeSettings(s));
            if (!AutoCrossings.hits(proposed(command), AutoCrossings.candidates(t)).isEmpty()) {
               return true;
            }
         }

         return false;
      } else {
         return true;
      }
   }

   private static boolean incompatibleSection(CompoundTag command, String side, RoadGeometry.Settings proposed) {
      if (command.getInt("Degree" + side) != 0 && command.contains("JoinSection" + side)) {
         RoadGeometry.Settings candidate = RoadRecord.readSettings(command.getCompound("JoinSection" + side));
         return command.contains("Road") && RoadRecord.load(command.getCompound("Road")).sameDerivedLaneEnd(proposed, side.equals("A"), candidate)
            ? false
            : !RoadTransitions.compatible(proposed, candidate);
      } else {
         return false;
      }
   }

   private static boolean touches(RoadRecord r, BlockPos p) {
      return r.a().equals(p) || r.b().equals(p);
   }

   public static CompoundTag center(RoadData d, BlockPos p) {
      return d.junctions.values().stream().filter(t -> t.getBoolean("Auto") && t.getLong("CenterPos") == p.asLong()).findFirst().orElse(null);
   }

   public static CompoundTag ringAt(RoadData d, BlockPos p) {
      return d.junctions
         .values()
         .stream()
         .filter(t -> t.getBoolean("GeneratedRing") && Arrays.stream(t.getLongArray("Ports")).anyMatch(v -> v == p.asLong()))
         .findFirst()
         .orElse(null);
   }

   public static int degree(RoadData d, BlockPos p, UUID exclude) {
      return (int)logical(d).values().stream().filter(r -> !r.id().equals(exclude) && touches(r, p)).count();
   }

   public static boolean handles(RoadData d, BlockPos a, BlockPos b, UUID id, boolean force) {
      return d.streets.containsKey(id)
         || d.streets.values().stream().anyMatch(r -> touches(r, a) || touches(r, b))
         || center(d, a) != null
         || center(d, b) != null
         || ringAt(d, a) != null
         || ringAt(d, b) != null
         || degree(d, a, id) >= 2
         || degree(d, b, id) >= 2
         || force && (degree(d, a, id) > 0 || degree(d, b, id) > 0);
   }

   public static void enrich(CompoundTag t, RoadData d, BlockPos a, BlockPos b, UUID id) {
      CompoundTag graph = context(d, Set.of(a, b), false);
      t.put("JunctionGraph", graph);
      t.putInt("DegreeA", degree(d, a, id));
      t.putInt("DegreeB", degree(d, b, id));
      t.putBoolean("AutoJunction", handles(d, a, b, id, false));
      t.putBoolean("CenterA", center(d, a) != null || ringAt(d, a) != null);
      t.putBoolean("CenterB", center(d, b) != null || ringAt(d, b) != null);
      if (id == null && !t.contains("ForceJunction") && corner(d, a, b)) {
         t.putBoolean("ForceJunction", true);
      }

      AutoCrossings.enrich(t, d, a, b, id, false);
   }

   private static boolean corner(RoadData d, BlockPos a, BlockPos b) {
      RoadGeometry.V chord = new RoadGeometry.V((double)(b.getX() - a.getX()), 0.0, (double)(b.getZ() - a.getZ())).horizontalUnit();

      for (BlockPos p : List.of(a, b)) {
         List<RoadRecord> links = logical(d).values().stream().filter(rx -> touches(rx, p)).toList();
         if (links.size() == 1) {
            RoadRecord r = links.get(0);
            RoadGeometry.Mesh m = r.mesh();
            boolean first = r.a().equals(p);
            RoadGeometry.V away = (first ? m.first() : m.last()).left().left().mul(first ? -1.0 : 1.0);
            RoadGeometry.V next = p.equals(a) ? chord : chord.mul(-1.0);
            if (away.dot(next) > -0.8660254) {
               return true;
            }
         }
      }

      return false;
   }

   private static Set<UUID> borrowed(CompoundTag center) {
      LinkedHashSet<UUID> ids = new LinkedHashSet<>();

      for (Tag t : center.getList("SlipRoads", 8)) {
         ids.add(UUID.fromString(t.getAsString()));
      }

      return ids;
   }

   private static boolean plainApproach(RoadRecord road) {
      if (!ordinary(road)) {
         return false;
      } else {
         RoadProfile.Options o = road.settings().options();
         LanePoints.Data lanes = o.lanePoints();
         return o.attachments().points().isEmpty()
            && lanes.points().stream().allMatch(p -> p.automatic() && p.mergeLength() == 0.0)
            && lanes.points(List.of()).equals(LanePoints.Data.EMPTY)
            && o.infrastructure().gantryEdits().stream().allMatch(RoadGantry.Edit::automatic)
            && o.levelCrossings().isEmpty()
            && o.streetscape().crossings().isEmpty()
            && o.laneLines().isEmpty();
      }
   }

   private static boolean continuous(AutoJunctions.End from, AutoJunctions.End next) {
      if (plainApproach(from.road()) && plainApproach(next.road()) && from.road().owner().equals(next.road().owner())) {
         RoadGeometry.Settings a = from.road().settings();
         RoadGeometry.Settings b = next.road().settings();
         if (a.structure() == b.structure() && a.thickness() == b.thickness() && a.options().leftTraffic() == b.options().leftTraffic()) {
            RoadGeometry.Settings aligned = RoadEndpointSections.orient(RoadData.authoredSection(b), from.first() != next.first());
            if (!RoadTransitions.Section.of(RoadData.authoredSection(a)).equals(RoadTransitions.Section.of(aligned))) {
               return false;
            } else {
               RoadGeometry.V axis = from.away(0.0);
               RoadGeometry.V origin = from.origin();

               for (AutoJunctions.End end : List.of(from, next)) {
                  for (RoadGeometry.Sample sample : end.mesh().samples()) {
                     if (Math.abs(sample.center().sub(origin).dot(axis.left())) > 0.05
                        || Math.abs(sample.center().y() - origin.y()) > 0.05
                        || Math.abs(sample.left().dot(axis.left())) < 0.9999) {
                        return false;
                     }
                  }
               }

               return next.away(0.0).dot(axis) > 0.9999 && next.origin().distance(from.at(from.mesh().length()).center()) < 0.05;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static ListTag slipCandidates(RoadData data, Map<UUID, RoadRecord> all, Collection<CompoundTag> centers, boolean structures) {
      ListTag result = new ListTag();
      if (centers.isEmpty()) {
         return result;
      } else {
         HashMap<BlockPos, List<AutoJunctions.End>> links = new HashMap<>();

         for (RoadRecord r : all.values()) {
            for (boolean first : List.of(true, false)) {
               BlockPos node = first ? r.a() : r.b();
               links.computeIfAbsent(node, k -> new ArrayList<>()).add(new AutoJunctions.End(r, node, first));
            }
         }

         for (CompoundTag center : centers) {
            if (!center.getBoolean("GeneratedRing")) {
               BlockPos origin = BlockPos.of(center.getLong("CenterPos"));

               for (AutoJunctions.End start : links.getOrDefault(origin, List.of())) {
                  AutoJunctions.End current = start;
                  HashSet<UUID> seen = new HashSet<>();
                  double length = 0.0;

                  while (seen.add(current.road().id()) && seen.size() <= 32 && (length += current.mesh().length()) < 256.0) {
                     BlockPos at = current.first() ? current.road().b() : current.road().a();
                     List<AutoJunctions.End> incident = links.getOrDefault(at, List.of());
                     if (incident.size() != 2 || center(data, at) != null || ringAt(data, at) != null) {
                        break;
                     }

                     AutoJunctions.End next = incident.get(incident.get(0).road().id().equals(current.road().id()) ? 1 : 0);
                     if (!continuous(current, next)) {
                        break;
                     }

                     CompoundTag edge = new CompoundTag();
                     edge.putUUID("From", current.road().id());
                     edge.putLong("At", at.asLong());
                     edge.put("Road", structures ? next.road().save() : next.road().header());
                     result.add(edge);
                     BlockPos far = next.first() ? next.road().b() : next.road().a();
                     if (center(data, far) != null || ringAt(data, far) != null) {
                        result.remove(result.size() - 1);
                        break;
                     }

                     current = next;
                  }
               }
            }
         }

         return result;
      }
   }

   public static CompoundTag context(RoadData d, Set<BlockPos> seeds) {
      return context(d, seeds, true);
   }

   static CompoundTag context(RoadData d, Set<BlockPos> seeds, boolean structures) {
      Map<UUID, RoadRecord> all = logical(d);
      Set<BlockPos> nodes = new HashSet<>(seeds);
      LinkedHashMap<UUID, CompoundTag> centers = new LinkedHashMap<>();

      for (CompoundTag t : d.junctions.values()) {
         if (t.getBoolean("Auto")
            && (
               borrowed(t).stream().map(all::get).filter(Objects::nonNull).anyMatch(rx -> seeds.contains(rx.a()) || seeds.contains(rx.b()))
                  || seeds.contains(BlockPos.of(t.getLong("CenterPos")))
                  || Arrays.stream(t.getLongArray("Ports")).anyMatch(vx -> seeds.contains(BlockPos.of(vx)))
            )) {
            centers.put(t.getUUID("Id"), t.copy());
            nodes.add(BlockPos.of(t.getLong("CenterPos")));

            for (long v : t.getLongArray("Ports")) {
               nodes.add(BlockPos.of(v));
            }
         }
      }

      CompoundTag tx = new CompoundTag();
      ListTag roads = new ListTag();
      ListTag cs = new ListTag();
      ListTag trims = new ListTag();
      HashSet<UUID> restore = new HashSet<>();
      centers.values().forEach(c -> restore.addAll(borrowed(c)));
      tx.put("SlipNext", slipCandidates(d, all, centers.values(), structures));

      for (RoadRecord r : all.values()) {
         if (restore.contains(r.id()) || nodes.contains(r.a()) || nodes.contains(r.b())) {
            roads.add(structures ? r.save() : r.header());
            RoadIndex.Built live = d.index.roads.get(r.id());
            if (live != null) {
               CompoundTag trim = new CompoundTag();
               trim.putUUID("Id", r.id());
               RoadGeometry.Mesh full = r.mesh();
               if (!nodes.contains(r.a()) && (center(d, r.a()) != null || ringAt(d, r.a()) != null)) {
                  trim.putDouble("Start", RoadQueries.horizontal(full, live.mesh.first().center()).sample().distance());
               }

               if (!nodes.contains(r.b()) && (center(d, r.b()) != null || ringAt(d, r.b()) != null)) {
                  trim.putDouble("End", full.length() - RoadQueries.horizontal(full, live.mesh.last().center()).sample().distance());
               }

               trims.add(trim);
            }
         }
      }

      centers.values().forEach(cs::add);
      tx.put("Streets", roads);
      tx.put("Centers", cs);
      tx.put("RemoteTrims", trims);
      tx.putLongArray("ActiveNodes", nodes.stream().mapToLong(BlockPos::asLong).toArray());
      return tx;
   }

   public static RoadRecord proposed(CompoundTag command) {
      RoadGeometry.Settings s = RoadRecord.readSettings(command.getCompound("Settings"));
      RoadGeometry.Node a = RoadRecord.readNode(command.getCompound("StartNode"));
      RoadGeometry.Node b = RoadRecord.readNode(command.getCompound("EndNode"));
      if (command.getBoolean("LiveSectionA") && command.contains("AutoA")) {
         RoadGeometry.Node n = RoadData.readHint(command.getCompound("AutoA")).node();
         a = new RoadGeometry.Node(n.position(), a.yaw(), a.grade());
      }

      if (command.getBoolean("LiveSectionB") && command.contains("AutoB")) {
         RoadGeometry.Node n = RoadData.readHint(command.getCompound("AutoB")).node();
         b = new RoadGeometry.Node(n.position(), b.yaw(), b.grade());
      }

      if (command.getBoolean("LevelEnds")) {
         a = RoadData.atGround(a, BlockPos.of(command.getLong("A")));
         b = RoadData.atGround(b, BlockPos.of(command.getLong("B")));
      }

      RoadGeometry.V chord = b.position().sub(a.position()).horizontalUnit();
      boolean freeA = free(command, "A");
      boolean freeB = free(command, "B");
      CompoundTag joins = command.copy();
      if (freeA) {
         joins.remove("JoinSectionA");
      }

      if (freeB) {
         joins.remove("JoinSectionB");
      }

      s = RoadData.joinSections(s, joins);
      RoadPlanner.Hint ah = endpointHint(command, "A", a, chord, freeA);
      RoadPlanner.Hint bh = endpointHint(command, "B", b, chord, freeB);
      RoadRecord previous = command.contains("Road") ? RoadRecord.load(command.getCompound("Road")) : null;
      RoadPlanner.Plan plan = previous == null
         ? null
         : previous.retainedPlan(s, ah, bh, RoadRecord.readNode(command.getCompound("StartNode")), RoadRecord.readNode(command.getCompound("EndNode")));
      if (plan == null) {
         plan = RoadPlanner.plan(ah, bh, s);
      }

      RoadRecord next = new RoadRecord(
         command.hasUUID("Id")
            ? command.getUUID("Id")
            : UUID.nameUUIDFromBytes((command.getLong("A") + ":" + command.getLong("B")).getBytes(StandardCharsets.UTF_8)),
         new UUID(0L, 0L),
         BlockPos.of(command.getLong("A")),
         BlockPos.of(command.getLong("B")),
         s.mode() == RoadGeometry.Mode.CURVE ? a : plan.start(),
         s.mode() == RoadGeometry.Mode.CURVE ? b : plan.end(),
         plan.settings(),
         s.mode() == RoadGeometry.Mode.AUTO,
         4
      );
      if (previous != null) {
         next = previous.retainAlignment(next, s, RoadRecord.readNode(command.getCompound("StartNode")), RoadRecord.readNode(command.getCompound("EndNode")));
      }

      return RoadCrossingTransitions.preview(next, command);
   }

   private static boolean free(CompoundTag command, String side) {
      return command.getBoolean("Center" + side)
         || command.getInt("Degree" + side) >= 2
         || forced(command, side) && command.getInt("Degree" + side) > 0
         || incompatibleSection(command, side, RoadRecord.readSettings(command.getCompound("Settings")));
   }

   private static RoadPlanner.Hint endpointHint(CompoundTag command, String side, RoadGeometry.Node node, RoadGeometry.V chord, boolean center) {
      RoadPlanner.Hint hint = command.contains("Auto" + side) ? RoadData.readHint(command.getCompound("Auto" + side)) : RoadPlanner.Hint.free(node);
      if (!command.getCompound("Settings").getString("Mode").equals("AUTO")) {
         return RoadPlanner.Hint.free(node);
      } else {
         return !center && (hint.headingLocked() || hint.linked())
            ? new RoadPlanner.Hint(
               new RoadGeometry.Node(node.position(), hint.node().yaw(), command.getBoolean("LevelEnds") ? 0.0 : hint.node().grade()),
               hint.headingLocked(),
               hint.gradeLocked(),
               hint.linked()
            )
            : RoadPlanner.Hint.free(new RoadGeometry.Node(node.position(), RoadPlanner.yaw(chord), node.grade()));
      }
   }

   public static AutoJunctions.Draft preview(CompoundTag command) {
      CompoundTag graph = command.getCompound("JunctionGraph").copy();
      List<RoadRecord> streets = new ArrayList<>();

      for (Tag entry : graph.getList("Streets", 10)) {
         streets.add(RoadRecord.load((CompoundTag)entry));
      }

      RoadRecord proposal = proposed(command);
      RoadRecord add = streets.stream()
         .filter(r -> r.id().equals(proposal.id()))
         .findFirst()
         .map(
            r -> r.retainAlignment(
                  proposal,
                  RoadRecord.readSettings(command.getCompound("Settings")),
                  RoadRecord.readNode(command.getCompound("StartNode")),
                  RoadRecord.readNode(command.getCompound("EndNode"))
               )
         )
         .orElse(proposal);
      List<AutoCrossings.Hit> hits = AutoCrossings.hits(add, AutoCrossings.candidates(command));
      graph = AutoCrossings.graph(command, hits);
      streets = streets(graph);
      streets.removeIf(r -> r.id().equals(add.id()));
      streets.add(add);
      List<CompoundTag> centers = new ArrayList<>();

      for (Tag entry : graph.getList("Centers", 10)) {
         centers.add(((CompoundTag)entry).copy());
      }

      for (BlockPos pos : List.of(add.a(), add.b())) {
         if (free(command, pos.equals(add.a()) ? "A" : "B")
            && streets.stream().filter(r -> touches(r, pos)).count() >= 2L
            && centers.stream().noneMatch(t -> t.getLong("CenterPos") == pos.asLong())) {
            centers.add(config(pos, add.owner()));
         }
      }

      AutoCrossings.Split split = AutoCrossings.split(streets, add, hits, centers);
      AutoJunctions.Draft draft = plan(split.roads(), centers, graph);
      return new AutoJunctions.Draft(draft.streets(), draft.roads(), draft.centers(), split.pieces());
   }

   public static AutoJunctions.Draft previewEdit(CompoundTag command) {
      CompoundTag graph = command.getCompound("JunctionGraph");
      List<CompoundTag> configs = centers(graph);

      for (CompoundTag config : configs) {
         if (config.getUUID("Id").equals(command.getUUID("Id"))) {
            config.put("Spec", command.getCompound("Spec").copy());
         }
      }

      return plan(streets(graph), configs, graph);
   }

   static CompoundTag config(BlockPos p, UUID owner) {
      CompoundTag t = new CompoundTag();
      t.putBoolean("Auto", true);
      t.putBoolean("Forced", true);
      t.putLong("CenterPos", p.asLong());
      t.putUUID("Id", UUID.nameUUIDFromBytes(("node-junction:" + p.asLong()).getBytes(StandardCharsets.UTF_8)));
      t.putUUID("Owner", owner);
      return t;
   }

   private static JunctionSpec spec(RoadGeometry.V center, CompoundTag config, List<JunctionSpec.Arm> arms) {
      if (!config.contains("Spec")) {
         return new JunctionSpec(
            center,
            JunctionSpec.Kind.INTERSECTION,
            config.getBoolean("LeftTraffic"),
            4.0,
            12.0,
            1,
            4.0,
            1.0,
            JunctionSpec.Control.NONE,
            20,
            3,
            1,
            0,
            true,
            true,
            arms
         );
      } else {
         JunctionSpec old = JunctionCodec.read(config.getCompound("Spec"));
         List<JunctionSpec.Arm> var7 = new ArrayList<>(arms);

         for (Tag v : config.getList("ConnectorArms", 10)) {
            CompoundTag t = (CompoundTag)v;
            var7.add(RampJunctions.arm(old, LanePointCodec.position(t.getCompound("Mouth")), RoadRecord.readSettings(t.getCompound("Settings")), var7.size()));
         }

         return new JunctionSpec(
            center,
            old.kind(),
            old.leftTraffic(),
            old.cornerRadius(),
            old.islandRadius(),
            old.ringLanes(),
            old.ringLaneWidth(),
            old.thickness(),
            old.control(),
            old.greenSeconds(),
            old.yellowSeconds(),
            old.allRedSeconds(),
            old.timeOffset(),
            old.guides(),
            old.greenIsland(),
            old.outerRail(),
            var7,
            old.parallelApproaches()
         );
      }
   }

   private static JunctionSpec.Arm arm(AutoJunctions.End end, double distance, int i) {
      RoadGeometry.Sample at = end.at(distance);
      RoadGeometry.V inward = end.away(distance).mul(-1.0);
      RoadGeometry.Node node = new RoadGeometry.Node(
         at.center(), RoadPlanner.yaw(inward), (double)(end.first() ? -1 : 1) * (end.first() ? end.road().start() : end.road().end()).grade()
      );
      RoadGeometry.Settings external = RoadEndpointSections.orient(end.road().settings().taper(at.halfWidth() * 2.0, at.halfWidth() * 2.0), end.first());
      if (end.first() && external.options().sidewalk().side() != RoadSidewalks.Side.BOTH) {
         external = external.options(
            external.options()
               .sidewalk(
                  external.options()
                     .sidewalk()
                     .side(external.options().sidewalk().side() == RoadSidewalks.Side.LEFT ? RoadSidewalks.Side.RIGHT : RoadSidewalks.Side.LEFT)
               )
         );
      }

      RoadProfile.Layout l = RoadProfile.layout(end.mesh(), at);
      double orientation = end.first() ? -1.0 : 1.0;
      List<Double> dividers = l.dividers().stream().map(d -> d * orientation).sorted().toList();
      double left = RoadProfile.curbExtent(l, at, orientation < 0.0 ? -1 : 1);
      double right = RoadProfile.curbExtent(l, at, orientation < 0.0 ? 1 : -1);
      RoadTransitions.Port port = new RoadTransitions.Port(
         orientation < 0.0 ? -l.motorMax() : l.motorMin(),
         orientation < 0.0 ? -l.motorMin() : l.motorMax(),
         l.median(),
         dividers,
         Math.max(0.0, left),
         Math.max(0.0, right),
         orientation * l.medianCenter()
      );
      external = external.options(external.options().ends(external.options().ends().port(port)));
      return JunctionSpec.arm(node, inward, external, !end.first(), i % 8).attached(true);
   }

   public static AutoJunctions.Draft plan(List<RoadRecord> streets, List<CompoundTag> saved) {
      return plan(streets, saved, new CompoundTag());
   }

   private static RoadTransitions.Section streetSection(RoadRecord own, BlockPos p, Map<BlockPos, List<RoadRecord>> links, Set<BlockPos> centers) {
      List<RoadRecord> peers = links.getOrDefault(p, List.of());
      if (peers.size() == 2 && !centers.contains(p)) {
         boolean first = own.a().equals(p);
         RoadGeometry.Mesh ownMesh = own.caps(0).mesh();
         if (RoadEndpointSections.changed(ownMesh, first)) {
            return first ? own.settings().options().ends().start() : own.settings().options().ends().end();
         } else {
            RoadRecord other = peers.get(0).id().equals(own.id()) ? peers.get(1) : peers.get(0);
            boolean otherFirst = other.a().equals(p);
            return other.fragment() == null && other.assembly() == null && !RoadEndpointSections.changed(other.mesh(), otherFirst)
               ? RoadTransitions.common(
                  RoadData.authoredSection(own.settings()), RoadEndpointSections.orient(RoadData.authoredSection(other.settings()), first == otherFirst)
               )
               : RoadTransitions.Section.of(RoadEndpointSections.orient(RoadData.endpointSection(new RoadIndex.Built(other), p), first == otherFirst));
         }
      } else {
         return null;
      }
   }

   private static List<RoadRecord> normalizeStreetJoints(List<RoadRecord> streets, List<CompoundTag> saved, CompoundTag context) {
      HashMap<BlockPos, List<RoadRecord>> links = new HashMap<>();
      HashSet<BlockPos> centers = new HashSet<>();
      HashSet<BlockPos> active = new HashSet<>();

      for (long p : context.getLongArray("ActiveNodes")) {
         active.add(BlockPos.of(p));
      }

      for (CompoundTag t : saved) {
         centers.add(BlockPos.of(t.getLong("CenterPos")));

         for (long p : t.getLongArray("Ports")) {
            centers.add(BlockPos.of(p));
         }
      }

      for (RoadRecord r : streets) {
         for (BlockPos p : List.of(r.a(), r.b())) {
            links.computeIfAbsent(p, k -> new ArrayList<>()).add(r);
         }
      }

      ArrayList<RoadRecord> result = new ArrayList<>();

      for (RoadRecord r : streets) {
         if (r.fragment() != null) {
            result.add(r);
         } else {
            RoadTransitions.Ends ends = r.settings().options().ends();
            RoadTransitions.Section a = !active.isEmpty() && !active.contains(r.a()) ? ends.start() : streetSection(r, r.a(), links, centers);
            RoadTransitions.Section b = !active.isEmpty() && !active.contains(r.b()) ? ends.end() : streetSection(r, r.b(), links, centers);
            RoadGeometry.Settings settings = r.assembly() != null ? r.settings() : RoadTransitions.ends(r.settings(), a, b);
            int caps = r.endCaps();
            if (links.get(r.a()).size() > 1) {
               caps &= -2;
            }

            if (links.get(r.b()).size() > 1) {
               caps &= -3;
            }

            RoadRecord next = settings.equals(r.settings()) ? r : r.settings(settings);
            result.add(caps == next.endCaps() ? next : next.caps(caps));
         }
      }

      return result;
   }

   private static AutoJunctions.Draft plan(List<RoadRecord> streets, List<CompoundTag> saved, CompoundTag context) {
      streets = normalizeStreetJoints(streets, saved, context);
      Map<BlockPos, List<AutoJunctions.End>> links = new LinkedHashMap<>();

      for (RoadRecord r : streets) {
         links.computeIfAbsent(r.a(), p -> new ArrayList<>()).add(new AutoJunctions.End(r, r.a(), true));
         links.computeIfAbsent(r.b(), p -> new ArrayList<>()).add(new AutoJunctions.End(r, r.b(), false));
      }

      Map<Long, CompoundTag> configs = new LinkedHashMap<>();

      for (CompoundTag t : saved) {
         configs.put(t.getLong("CenterPos"), t.copy());
      }

      Set<Long> active = new HashSet<>();

      for (long point : context.getLongArray("ActiveNodes")) {
         active.add(point);
      }

      for (Entry<BlockPos, List<AutoJunctions.End>> entry : links.entrySet()) {
         if ((active.isEmpty() || active.contains(entry.getKey().asLong())) && entry.getValue().size() >= 3) {
            if (entry.getValue().size() > 6) {
               throw new IllegalArgumentException("同一端点最多连接六个方向");
            }

            configs.computeIfAbsent(entry.getKey().asLong(), v -> config(entry.getKey(), entry.getValue().get(0).road().owner()));
         }
      }

      Map<UUID, double[]> trim = new HashMap<>();

      for (Tag t : context.getList("RemoteTrims", 10)) {
         CompoundTag value = (CompoundTag)t;
         trim.put(value.getUUID("Id"), new double[]{value.getDouble("Start"), value.getDouble("End")});
      }

      ArrayList<RoadRecord> output = new ArrayList<>();
      ArrayList<CompoundTag> result = new ArrayList<>();
      HashSet<UUID> consumed = new HashSet<>();
      HashSet<UUID> originalIds = new HashSet<>();

      for (Tag t : context.getList("Streets", 10)) {
         originalIds.add(((CompoundTag)t).getUUID("Id"));
      }

      LinkedHashMap<UUID, RoadRecord> extended = new LinkedHashMap<>();

      for (RoadRecord road : streets) {
         extended.put(road.id(), road);
      }

      for (CompoundTag config : configs.values()) {
         BlockPos centerPos = BlockPos.of(config.getLong("CenterPos"));
         boolean ring = config.getBoolean("GeneratedRing");
         ArrayList<AutoJunctions.End> ends = new ArrayList<>();
         if (ring) {
            for (long port : config.getLongArray("Ports")) {
               List<AutoJunctions.End> at = links.getOrDefault(BlockPos.of(port), List.of());
               if (at.size() > 1) {
                  throw new IllegalArgumentException("环岛每个接路点只能连接一条道路");
               }

               ends.addAll(at);
            }
         } else {
            ends.addAll(links.getOrDefault(centerPos, List.of()));
         }

         if (ring || ends.size() + config.getList("ConnectorArms", 10).size() >= 2) {
            if (!ring && ends.isEmpty()) {
               throw new IllegalArgumentException("请先删除依赖匝道，再删除路口最后的普通支路");
            }

            if (ends.stream().anyMatch(e -> RoadProfile.catalog(e.road().settings().style()).type() != RoadProfile.Type.ORDINARY)) {
               throw new IllegalArgumentException("高速可渐变续接普通道路；多向平交请先接一段普通道路或使用立交");
            }

            ends.sort(Comparator.comparing(e -> e.road().id()));
            RoadGeometry.V center = ring ? RoadRecord.readNode(config.getCompound("CenterNode")).position() : ends.get(0).origin();

            for (AutoJunctions.End end : ends) {
               if (Math.abs(end.origin().y() - center.y()) > 0.01) {
                  throw new IllegalArgumentException("路口中心高程不一致；请把相接道路设为同高");
               }
            }

            ArrayList<JunctionSpec.Arm> trial = new ArrayList<>();

            for (int i = 0; i < ends.size(); i++) {
               AutoJunctions.End endx = ends.get(i);
               RoadGeometry.V outward = endx.away(0.0);
               trial.add(
                  arm(endx, 0.0, i)
                     .node(
                        new RoadGeometry.Node(center.add(outward.mul(200.0)), RoadPlanner.yaw(outward.mul(-1.0)), 0.0),
                        outward.mul(-1.0),
                        endx.road().settings()
                     )
               );
            }

            double radius = JunctionPlanner.radius(spec(center, config, trial));
            ArrayList<JunctionSpec.Arm> arms = new ArrayList<>();
            ListTag armRoads = new ListTag();
            ListTag previousIds = config.getList("ArmRoads", 8);
            JunctionSpec previous = config.contains("Spec") ? JunctionCodec.read(config.getCompound("Spec")) : null;
            if (previous != null) {
               for (int i = 0; i < ends.size(); i++) {
                  for (int k = 0; k < previousIds.size() && k < previous.arms().size(); k++) {
                     if (previousIds.getString(k).equals(ends.get(i).road().id().toString())) {
                        trial.set(i, trial.get(i).slip(previous.arms().get(k).rightSlip()));
                     }
                  }
               }
            }

            if (previousIds.size() != ends.size()) {
               JunctionSpec changed = spec(center, config, trial);

               for (int i = 0; i < trial.size(); i++) {
                  if (trial.get(i).rightSlip() && !JunctionSlips.unavailable(changed, i).isEmpty()) {
                     trial.set(i, trial.get(i).slip(false));
                  }
               }
            }

            JunctionSpec slipLayout = spec(center, config, trial);
            Set<Integer> usedPhases = new HashSet<>();
            if (previous != null) {
               for (int kx = 0; kx < previousIds.size(); kx++) {
                  String key = previousIds.getString(kx);
                  if (ends.stream().anyMatch(e -> e.road().id().toString().equals(key))) {
                     usedPhases.add(previous.arms().get(kx).phase());
                  }
               }
            }

            LinkedHashSet<UUID> slipRoads = new LinkedHashSet<>();

            for (int ix = 0; ix < ends.size(); ix++) {
               AutoJunctions.End endx = ends.get(ix);
               double approach = Math.max(16.0, JunctionSlips.approachLength(slipLayout, ix));
               if (previous != null) {
                  for (int kxx = 0; kxx < previousIds.size() && kxx < previous.arms().size(); kxx++) {
                     if (previousIds.getString(kxx).equals(endx.road().id().toString())) {
                        JunctionSpec.Arm before = previous.arms().get(kxx);
                        approach = Math.max(approach, before.crossingSetback() + (before.crosswalk() ? before.crossingWidth() + before.stopGap() : 0.0) + 6.0);
                     }
                  }
               }

               double offset = ring ? endx.origin().sub(center).horizontalLength() : 0.0;
               double cut = Math.max(12.0, radius + approach - offset);
               double available = endx.mesh().length();
               double remaining = cut;
               AutoJunctions.End port = endx;

               while (available < remaining + 2.0) {
                  BlockPos at = port.first() ? port.road().b() : port.road().a();
                  UUID fromId = port.road().id();
                  Optional<CompoundTag> edge = context.getList("SlipNext", 10)
                     .stream()
                     .map(v -> (CompoundTag)v)
                     .filter(v -> v.getLong("At") == at.asLong() && fromId.equals(v.getUUID("From")))
                     .findFirst();
                  if (JunctionSlips.approachLength(slipLayout, ix) == 0.0
                     || edge.isEmpty()
                     || links.getOrDefault(at, List.of()).size() > 2
                     || trim.containsKey(fromId) && Arrays.stream(trim.get(fromId)).anyMatch(v -> v > 0.0)
                     || !consumed.add(fromId)) {
                     throw new IllegalArgumentException("接入口 " + (ix + 1) + " 的连续可用道路不足，请沿相同断面延长到至少 " + (int)Math.ceil(cut + 2.0) + " 格");
                  }

                  RoadRecord candidate = RoadRecord.load(edge.get().getCompound("Road"));
                  if (originalIds.contains(candidate.id()) && !extended.containsKey(candidate.id())) {
                     throw new IllegalArgumentException("相邻路段仍用于独立右转，请先关闭右转匝道");
                  }

                  RoadRecord road = extended.getOrDefault(candidate.id(), candidate);
                  AutoJunctions.End next = new AutoJunctions.End(road, at, road.a().equals(at));
                  if (!continuous(port, next)) {
                     throw new IllegalArgumentException("右转接路的相邻断面或线形已改变");
                  }

                  extended.put(road.id(), road);
                  slipRoads.add(road.id());
                  remaining = Math.max(0.0, remaining - available);
                  port = next;
                  available = next.mesh().length();
               }

               if (consumed.contains(port.road().id())) {
                  throw new IllegalArgumentException("相邻路口之间的右转接路范围发生重叠");
               }

               JunctionSpec.Arm a = arm(port, remaining, ix);
               int old = -1;

               for (int kxxx = 0; kxxx < previousIds.size(); kxxx++) {
                  if (previousIds.getString(kxxx).equals(endx.road().id().toString())) {
                     old = kxxx;
                  }
               }

               if (previous != null && old >= 0 && old < previous.arms().size()) {
                  JunctionSpec.Arm before = previous.arms().get(old);
                  ArrayList<JunctionSpec.Lane> lanes = new ArrayList<>();

                  for (int l = 0; l < a.incoming(); l++) {
                     JunctionSpec.Lane savedLane = l < before.lanes().size() ? before.lanes().get(l) : JunctionSpec.Lane.AUTO;
                     ArrayList<Integer> targets = new ArrayList<>();

                     for (int target : savedLane.targets()) {
                        int next = -1;
                        if (target >= 0 && target < previousIds.size()) {
                           for (int kxxxx = 0; kxxxx < ends.size(); kxxxx++) {
                              if (previousIds.getString(target).equals(ends.get(kxxxx).road().id().toString())) {
                                 next = kxxxx;
                              }
                           }
                        }

                        targets.add(next);
                     }

                     lanes.add(
                        new JunctionSpec.Lane(
                           savedLane.mask(), targets, savedLane.targetLanes(), savedLane.splitLeft(), savedLane.leftPhase(), savedLane.controlRight()
                        )
                     );
                  }

                  a = new JunctionSpec.Arm(
                     a.endpoint(),
                     a.inward(),
                     a.external(),
                     a.incoming(),
                     a.outgoing(),
                     a.width(),
                     a.median(),
                     a.medianKind(),
                     a.cycleWidth(),
                     a.curbWidth(),
                     before.crosswalk(),
                     before.crossingWidth(),
                     before.crossingSetback(),
                     before.stopGap(),
                     before.phase(),
                     lanes,
                     true,
                     before.advanceSign(),
                     before.nameSign(),
                     trial.get(ix).rightSlip()
                  );
               }

               if (old < 0) {
                  int phase = 0;

                  while (phase < 7 && usedPhases.contains(phase)) {
                     phase++;
                  }

                  usedPhases.add(phase);
                  a = new JunctionSpec.Arm(
                     a.endpoint(),
                     a.inward(),
                     a.external(),
                     a.incoming(),
                     a.outgoing(),
                     a.width(),
                     a.median(),
                     a.medianKind(),
                     a.cycleWidth(),
                     a.curbWidth(),
                     a.crosswalk(),
                     a.crossingWidth(),
                     a.crossingSetback(),
                     a.stopGap(),
                     phase,
                     a.lanes(),
                     true,
                     a.advanceSign(),
                     a.nameSign(),
                     a.rightSlip()
                  );
               }

               arms.add(a);
               armRoads.add(StringTag.valueOf(endx.road().id().toString()));
               trim.computeIfAbsent(port.road().id(), v -> new double[2])[port.first() ? 0 : 1] = remaining;
            }

            ListTag borrowedRoads = new ListTag();
            slipRoads.forEach(idx -> borrowedRoads.add(StringTag.valueOf(idx.toString())));
            config.put("SlipRoads", borrowedRoads);
            JunctionSpec current = spec(center, config, arms);
            if (previousIds.size() != ends.size()) {
               ArrayList<JunctionSpec.Arm> adjusted = new ArrayList<>();

               for (int ix = 0; ix < arms.size(); ix++) {
                  int availableMask = 0;

                  for (int j = 0; j < arms.size(); j++) {
                     if (arms.get(j).outgoing() > 0) {
                        availableMask |= JunctionPlanner.turn(current, ix, j);
                     }
                  }

                  ArrayList<JunctionSpec.Lane> lanes = new ArrayList<>();

                  for (JunctionSpec.Lane lane : arms.get(ix).lanes()) {
                     int mask = lane.mask() < 0 ? -1 : lane.mask() & availableMask;
                     lanes.add(
                        new JunctionSpec.Lane(
                           lane.mask() > 0 && mask == 0 ? -1 : mask,
                           lane.targets(),
                           lane.targetLanes(),
                           lane.splitLeft(),
                           lane.leftPhase(),
                           lane.controlRight()
                        )
                     );
                  }

                  adjusted.add(arms.get(ix).lanes(lanes));
               }

               adjusted.addAll(current.arms().subList(arms.size(), current.arms().size()));
               current = current.arms(adjusted);
            }

            JunctionPlanner.Plan plan = JunctionPlanner.plan(current);
            UUID group = config.getUUID("Id");
            UUID owner = config.getUUID("Owner");

            for (int ix = 0; ix < plan.pieces().size(); ix++) {
               JunctionPlanner.Piece piece = plan.pieces().get(ix);
               RoadGeometry.Mesh m = piece.mesh();
               BlockPos armNode = piece.arm() >= 0 && piece.arm() < ends.size()
                  ? ends.get(piece.arm()).node()
                  : (piece.arm() >= ends.size() && piece.arm() >= 0 ? RampJunctions.at(current.arms().get(piece.arm()).endpoint().position()) : centerPos);
               UUID id = UUID.nameUUIDFromBytes((group + ":auto:" + ix).getBytes(StandardCharsets.UTF_8));
               output.add(
                  new RoadRecord(
                        id,
                        owner,
                        armNode,
                        centerPos,
                        new RoadGeometry.Node(m.first().center(), 0.0, 0.0),
                        new RoadGeometry.Node(m.last().center(), 0.0, 0.0),
                        m.settings(),
                        false,
                        4
                     )
                     .junction(group, new JunctionPlanner.Ref(current, ix))
               );
            }

            for (Tag v : config.getList("ConnectorArms", 10)) {
               armRoads.add(StringTag.valueOf(((CompoundTag)v).getUUID("Road").toString()));
            }

            config.put("Spec", JunctionCodec.write(current));
            config.put("ArmRoads", armRoads);
            config.putString("Kind", "junction");
            long[] points = new long[current.arms().size() + 1];
            points[0] = centerPos.asLong();

            for (int ix = 0; ix < current.arms().size(); ix++) {
               points[ix + 1] = ix < ends.size() ? ends.get(ix).node().asLong() : RampJunctions.at(current.arms().get(ix).endpoint().position()).asLong();
            }

            config.putLongArray("Points", points);
            result.add(config);
         }
      }

      List<RoadRecord> var53 = new ArrayList<>(extended.values());

      for (RoadRecord r : var53) {
         if (!consumed.contains(r.id())) {
            double[] cut = trim.getOrDefault(r.id(), new double[2]);
            if (cut[0] == 0.0 && cut[1] == 0.0) {
               output.add(r);
            } else {
               RoadGeometry.Mesh mesh = r.mesh();
               double from = cut[0];
               double to = mesh.length() - cut[1];
               if (to - from < 2.0) {
                  throw new IllegalArgumentException("相邻路口之间的道路太短，路口范围发生重叠");
               }

               if (r.fragment() != null) {
                  RoadGeometry.Node first = RoadSplitGeometry.node(mesh, from);
                  RoadGeometry.Node last = RoadSplitGeometry.node(mesh, to);
                  RoadRecord source = r.structures(
                     r.structures().stream().filter(p -> !p.model().equals("sr:junction_sign_pedestal") && !p.model().equals("sr:junction_sign_seat")).toList()
                  );
                  output.add(RoadSplitGeometry.junctionRange(source, from, to, r.id(), r.a(), r.b(), first, last, r.fragment().connection()));
               } else {
                  ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
                  RoadGeometry.Sample first = RoadStructures.sample(mesh, from);
                  RoadGeometry.Sample last = RoadStructures.sample(mesh, to);
                  samples.add(new RoadGeometry.Sample(first.center(), first.left(), 0.0, first.halfWidth()));

                  for (RoadGeometry.Sample s : mesh.samples()) {
                     if (s.distance() > from + 1.0E-7 && s.distance() < to - 1.0E-7) {
                        samples.add(new RoadGeometry.Sample(s.center(), s.left(), s.distance() - from, s.halfWidth()));
                     }
                  }

                  samples.add(new RoadGeometry.Sample(last.center(), last.left(), to - from, last.halfWidth()));
                  RoadGeometry.Settings clippedSettings = r.settings()
                     .options(r.settings().options().ends(r.settings().options().ends().trim(from, mesh.length() - to)));
                  RoadGeometry.Mesh clipped = RoadRibbon.mesh(samples, clippedSettings);
                  output.add(
                     new RoadRecord(
                           r.id(),
                           r.owner(),
                           r.a(),
                           r.b(),
                           new RoadGeometry.Node(first.center(), RoadPlanner.yaw(first.left().left().mul(-1.0)), r.start().grade()),
                           new RoadGeometry.Node(last.center(), RoadPlanner.yaw(last.left().left().mul(-1.0)), r.end().grade()),
                           clippedSettings,
                           r.automatic(),
                           r.clearance()
                        )
                        .alignment(r.assembly(), clipped)
                  );
               }
            }
         }
      }

      return new AutoJunctions.Draft(List.copyOf(var53), List.copyOf(output), List.copyOf(result));
   }

   private static List<RoadRecord> streets(CompoundTag graph) {
      ArrayList<RoadRecord> list = new ArrayList<>();

      for (Tag t : graph.getList("Streets", 10)) {
         list.add(RoadRecord.load((CompoundTag)t));
      }

      return list;
   }

   private static List<CompoundTag> centers(CompoundTag graph) {
      ArrayList<CompoundTag> list = new ArrayList<>();

      for (Tag t : graph.getList("Centers", 10)) {
         list.add(((CompoundTag)t).copy());
      }

      return list;
   }

   static Set<UUID> directlyEditedRoads(AutoJunctions.Draft draft, Set<UUID> streets, Set<UUID> junctions) {
      HashSet<UUID> ids = new HashSet<>();

      for (UUID id : streets) {
         for (RoadRecord road : selected(draft, id)) {
            ids.add(road.id());
         }
      }

      for (RoadRecord road : draft.roads()) {
         if (road.assembly() != null && junctions.contains(road.assembly())) {
            ids.add(road.id());
         }
      }

      return Set.copyOf(ids);
   }

   private static void commit(
      ServerLevel level,
      ServerPlayer player,
      CompoundTag before,
      AutoJunctions.Draft draft,
      List<RoadData.NodeMove> moves,
      Set<UUID> editedStreets,
      Set<UUID> editedJunctions
   ) {
      commit(level, player, before, draft, moves, editedStreets, editedJunctions, Map.of());
   }

   private static void commit(
      ServerLevel level,
      ServerPlayer player,
      CompoundTag before,
      AutoJunctions.Draft draft,
      List<RoadData.NodeMove> moves,
      Set<UUID> editedStreets,
      Set<UUID> editedJunctions,
      Map<UUID, List<RoadRecord>> pieces
   ) {
      AutoJunctions.CommitInput prepared = commitInput(level, player, before, draft, moves, pieces);
      ArrayList<RoadIndex.Built> built = new ArrayList<>(prepared.built());
      HashSet<UUID> removed = new HashSet<>(prepared.removed());
      if (editedStreets.isEmpty() && !editedJunctions.isEmpty()) {
         for (int i = 0; i < built.size(); i++) {
            RoadIndex.Built next = built.get(i);
            RoadIndex.Built old = RoadData.get(level).index.roads.get(next.record.id());
            if (old != null && old.record.junction() == null && next.record.junction() == null && old.record.furniturePhase() != null) {
               RoadGeometry.Sample at = RoadQueries.horizontal(old.mesh, next.mesh.first().center()).sample();
               double delta = at.distance() + next.mesh.first().center().sub(at.center()).dot(at.left().left().mul(-1.0));
               built.set(i, new RoadIndex.Built(next.record.furniturePhase(old.record.furniturePhase().shift(delta))));
            }
         }

         built.removeIf(nextx -> {
            RoadIndex.Built oldx = RoadData.get(level).index.roads.get(nextx.record.id());
            if (oldx != null && oldx.record.junction() == null && nextx.record.junction() == null && sameDeck(oldx.mesh, nextx.mesh)) {
               removed.remove(nextx.record.id());
               return true;
            } else {
               return false;
            }
         });
      }

      RoadData.get(level)
         .replaceAssembly(
            level, player, built, removed, prepared.selected(), moves, prepared.editing(directlyEditedRoads(draft, editedStreets, editedJunctions))
         );
      commitMetadata(RoadData.get(level), before, draft, pieces);
      RoadData.get(level).junctions.putAll(prepared.sideReceipts());
   }

   static boolean sameDeck(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      if (a.samples().size() != b.samples().size()) {
         return false;
      } else {
         for (int i = 0; i < a.samples().size(); i++) {
            RoadGeometry.Sample x = a.samples().get(i);
            RoadGeometry.Sample y = b.samples().get(i);
            if (x.center().distance(y.center()) > 1.0E-7 || x.left().distance(y.left()) > 1.0E-7 || Math.abs(x.halfWidth() - y.halfWidth()) > 1.0E-7) {
               return false;
            }
         }

         return true;
      }
   }

   private static AutoJunctions.CommitInput commitInput(
      ServerLevel level, ServerPlayer player, CompoundTag before, AutoJunctions.Draft draft, List<RoadData.NodeMove> moves, Map<UUID, List<RoadRecord>> pieces
   ) {
      RoadData data = RoadData.get(level);
      Set<UUID> removed = new HashSet<>();

      for (RoadRecord r : streets(before)) {
         removed.add(r.id());
      }

      Set<UUID> oldGroups = new HashSet<>();

      for (CompoundTag t : centers(before)) {
         oldGroups.add(t.getUUID("Id"));
      }

      for (RoadIndex.Built r : data.index.roads.values()) {
         if (oldGroups.contains(r.record.assembly())) {
            removed.add(r.record.id());
         }
      }

      for (CompoundTag c : draft.centers()) {
         removed.addAll(borrowed(c));
      }

      Set<BlockPos> selected = new HashSet<>();

      for (RoadRecord rx : draft.streets()) {
         selected.add(rx.a());
         selected.add(rx.b());
         if (player != null) {
            RoadData.requireOwner(player, rx.owner());
         }
      }

      for (CompoundTag t : draft.centers()) {
         selected.add(BlockPos.of(t.getLong("CenterPos")));

         for (long port : t.getLongArray("Ports")) {
            selected.add(BlockPos.of(port));
         }
      }

      for (RoadData.NodeMove move : moves) {
         if (move.from() != null) {
            selected.add(move.from());
         }
      }

      ArrayList<RoadIndex.Built> built = new ArrayList<>();

      for (RoadRecord rxx : draft.roads()) {
         built.add(new RoadIndex.Built(AutoCrossings.remap(rxx, pieces)));
      }

      for (RoadIndex.Built old : data.index.roads.values()) {
         if (!removed.contains(old.record.id())) {
            RoadRecord next = AutoCrossings.remap(old.record, pieces);
            if (next != old.record) {
               removed.add(next.id());
               built.add(new RoadIndex.Built(next));
            }
         }
      }

      HashSet<UUID> preserved = new HashSet<>();
      Map<UUID, CompoundTag> sideReceipts = ConnectedSideJunctions.rebase(level, built, removed, preserved);

      for (RoadIndex.Built rxx : built) {
         for (RoadIndex.Built oldx : data.index.roads.values()) {
            if (!removed.contains(oldx.record.id())
               && !RoadInteractions.deferredLaneContact(rxx.record, oldx.record)
               && RoadIndex.overlapXZ(rxx.mesh, oldx.mesh, 1.0)) {
               Interchanges.checkExternal(
                  rxx.mesh, oldx.mesh, data.index.roads.containsKey(rxx.record.id()) ? data.index.roads.get(rxx.record.id()).mesh : null
               );
            }
         }
      }

      return new AutoJunctions.CommitInput(built, removed, selected, sideReceipts, Set.copyOf(preserved));
   }

   private static void commitMetadata(RoadData data, CompoundTag before, AutoJunctions.Draft draft, Map<UUID, List<RoadRecord>> pieces) {
      for (RoadRecord r : streets(before)) {
         data.streets.remove(r.id());
      }

      for (RoadRecord r : draft.streets()) {
         if (r.assembly() == null || draft.centers().stream().anyMatch(c -> touches(r, BlockPos.of(c.getLong("CenterPos"))))) {
            data.streets.put(r.id(), r);
         }
      }

      for (CompoundTag old : centers(before)) {
         data.junctions.remove(old.getUUID("Id"));
      }

      draft.centers().forEach(t -> data.junctions.put(t.getUUID("Id"), t.copy()));
      RampJunctions.sync(data);

      for (RoadRecord rx : draft.streets()) {
         RoadIndex.Built actual = data.index.roads.get(rx.id());
         if (actual != null && data.streets.containsKey(rx.id())) {
            data.streets.put(rx.id(), rx.withLanePoints(ElevatedAccessRoads.logicalLanes(rx, actual.record)));
         }
      }

      data.setDirty();
      data.streets.replaceAll((id, rxx) -> AutoCrossings.remap(rxx, pieces));

      for (Entry<UUID, List<RoadRecord>> entry : pieces.entrySet()) {
         HashMap<Integer, UUID> owners = new HashMap<>();

         for (RoadRecord piece : entry.getValue()) {
            for (int slot : RoadGantry.slots(piece.mesh())) {
               owners.put(slot, piece.id());
            }
         }

         data.smartSigns.splitKeys(entry.getKey(), owners);
      }
   }

   static void prepareStreet(ServerLevel level, ServerPlayer player, AutoJunctions.StreetChange change, UUID token) {
      AutoJunctions.CommitInput input = commitInput(level, player, change.before(), change.draft(), change.moves(), Map.of());
      RoadData data = RoadData.get(level);
      List<RoadIndex.Built> prepared = data.prepareAssembly(
         level,
         player,
         input.built(),
         input.removed(),
         input.selected(),
         change.moves(),
         token,
         input.editing(directlyEditedRoads(change.draft(), Set.of(change.added()), Set.of())),
         false
      );
      HashSet<UUID> oldIds = new HashSet<>(input.removed());

      for (RoadIndex.Built road : prepared) {
         RoadPlanningBudget.check();
         road.cells.size();
         road.clearanceCells.size();
         oldIds.add(road.record.id());
      }

      for (UUID id : oldIds) {
         RoadIndex.Built old = data.index.roads.get(id);
         if (old != null) {
            RoadPlanningBudget.check();
            old.cells.size();
            old.clearanceCells.size();
         }
      }
   }

   static void commitPreparedStreet(ServerLevel level, ServerPlayer player, AutoJunctions.StreetChange change, UUID token) {
      AutoJunctions.CommitInput input = commitInput(level, player, change.before(), change.draft(), change.moves(), Map.of());
      RoadData.get(level).buildPrepared(level, player, token);
      commitMetadata(RoadData.get(level), change.before(), change.draft(), Map.of());
      RoadData.get(level).junctions.putAll(input.sideReceipts());
   }

   public static RoadRecord connect(
      ServerLevel level, ServerPlayer player, BlockPos a, BlockPos b, RoadGeometry.Settings settings, UUID id, boolean force, boolean levelEnds
   ) {
      return connect(level, player, a, b, settings, id, force, force, levelEnds);
   }

   public static RoadRecord connect(
      ServerLevel level,
      ServerPlayer player,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Settings settings,
      UUID id,
      boolean forceA,
      boolean forceB,
      boolean levelEnds
   ) {
      return connect(level, player, a, b, settings, id, forceA, forceB, levelEnds, List.of());
   }

   public static RoadRecord connect(
      ServerLevel level,
      ServerPlayer player,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Settings settings,
      UUID id,
      boolean forceA,
      boolean forceB,
      boolean levelEnds,
      List<BlockPos> controlNodes
   ) {
      if (a.equals(b)) {
         throw new IllegalArgumentException("请选择两个不同的端点");
      } else if (Math.hypot((double)a.getX() - (double)b.getX(), (double)a.getZ() - (double)b.getZ()) > 2048.0) {
         throw new IllegalArgumentException("端点距离超过 2048 格");
      } else {
         RoadData data = RoadData.get(level);
         NodeEntity na = RoadData.requireNode(level, a, player);
         NodeEntity nb = RoadData.requireNode(level, b, player);
         CompoundTag graph = context(data, Set.of(a, b));
         List<RoadRecord> roads = streets(graph);
         List<CompoundTag> configs = centers(graph);

         for (RoadRecord r : roads) {
            if (!r.id().equals(id) && (r.a().equals(a) && r.b().equals(b) || r.a().equals(b) && r.b().equals(a))) {
               throw new IllegalArgumentException("这两个端点已经有道路");
            }
         }

         if (id != null && roads.stream().anyMatch(rx -> rx.id().equals(id) && rx.assembly() != null)) {
            throw new IllegalArgumentException("组合道路请使用对应生成器整体编辑");
         } else if (id != null && roads.stream().noneMatch(rx -> rx.id().equals(id))) {
            throw new IllegalArgumentException("道路已不存在");
         } else {
            CompoundTag command = new CompoundTag();
            command.putLong("A", a.asLong());
            command.putLong("B", b.asLong());
            command.put("StartNode", RoadRecord.writeNode(data.connectionNode(level, a, true, id, nb.constructionNode().position())));
            command.put("EndNode", RoadRecord.writeNode(data.connectionNode(level, b, false, id, na.constructionNode().position())));
            command.put("Settings", RoadRecord.writeSettings(settings));
            command.putBoolean("LevelEnds", levelEnds);
            command.putUUID("Id", id == null ? UUID.randomUUID() : id);
            if (id != null) {
               command.put("Road", roads.stream().filter(rx -> rx.id().equals(id)).findFirst().orElseThrow().header());
            }

            command.putBoolean("ForceJunctionA", forceA);
            command.putBoolean("ForceJunctionB", forceB);
            command.putInt("DegreeA", degree(data, a, id));
            command.putInt("DegreeB", degree(data, b, id));
            command.putBoolean("CenterA", center(data, a) != null || ringAt(data, a) != null);
            command.putBoolean("CenterB", center(data, b) != null || ringAt(data, b) != null);
            command.put("AutoA", RoadData.writeHint(data.constructionHint(level, a, true, id, nb.constructionNode().position(), levelEnds)));
            command.put("AutoB", RoadData.writeHint(data.constructionHint(level, b, false, id, na.constructionNode().position(), levelEnds)));
            data.jointPayload(command, a, b, id);
            RoadRecord next = proposed(command);
            UUID owner = player == null ? new UUID(0L, 0L) : player.getUUID();
            if (id != null) {
               owner = roads.stream().filter(rx -> rx.id().equals(id)).findFirst().orElseThrow().owner();
            }

            next = new RoadRecord(next.id(), owner, a, b, next.start(), next.end(), next.settings(), next.automatic(), 4);
            if (id != null) {
               for (RoadRecord old : roads) {
                  if (old.id().equals(id)) {
                     next = old.retainAlignment(next, settings, na.constructionNode(), nb.constructionNode());
                  }
               }
            }

            return connectRecord(level, player, next, command, controlNodes);
         }
      }
   }

   static RoadRecord connectRecord(ServerLevel level, ServerPlayer player, RoadRecord next, CompoundTag command, List<BlockPos> controlNodes) {
      RoadData data = RoadData.get(level);
      command = command.copy();
      command.put("JunctionGraph", context(data, Set.of(next.a(), next.b())));
      AutoCrossings.enrich(command, data, next.a(), next.b(), next.id());
      next = RoadCrossingTransitions.fit(next, RoadCrossingTransitions.candidates(data));
      final RoadRecord current = next;
      List<AutoCrossings.Hit> hits = AutoCrossings.hits(next, AutoCrossings.candidates(command));
      CompoundTag graph = AutoCrossings.graph(command, hits);
      List<RoadRecord> roads = streets(graph);
      List<CompoundTag> configs = centers(graph);
      roads.removeIf(r -> r.id().equals(current.id()));
      roads.add(next);

      for (BlockPos pos : List.of(next.a(), next.b())) {
         if (command.contains("Settings")
            && free(command, pos.equals(next.a()) ? "A" : "B")
            && roads.stream().filter(r -> touches(r, pos)).count() >= 2L
            && configs.stream().noneMatch(t -> t.getLong("CenterPos") == pos.asLong())) {
            configs.add(config(pos, next.owner()));
         }
      }

      AutoCrossings.Split split = AutoCrossings.split(roads, next, hits, configs);
      ArrayList<RoadData.NodeMove> moves = new ArrayList<>(split.nodes());
      controlNodes.forEach(p -> moves.add(RoadData.NodeMove.retire(p)));
      HashSet<UUID> edited = new HashSet<>();
      edited.add(next.id());

      for (List<RoadRecord> pieces : split.pieces().values()) {
         pieces.forEach(r -> edited.add(r.id()));
      }

      AutoJunctions.Draft draft = plan(split.roads(), configs, graph);
      commit(level, player, graph, draft, moves, edited, Set.of(), split.pieces());
      RoadIndex.Built live = data.index.roads.get(next.id());
      return live == null ? data.streets.get(next.id()) : live.record;
   }

   public static void removeStreet(ServerLevel level, ServerPlayer player, UUID id) {
      RoadData data = RoadData.get(level);
      RoadRecord old = data.streets.get(id);
      if (old == null) {
         throw new IllegalArgumentException("道路已不存在");
      } else {
         if (player != null) {
            RoadData.requireOwner(player, old.owner());
         }

         CompoundTag graph = context(data, Set.of(old.a(), old.b()));
         List<RoadRecord> roads = streets(graph);
         roads.removeIf(r -> r.id().equals(id));
         commit(level, player, graph, plan(roads, centers(graph), graph), List.of(), Set.of(id), Set.of());
      }
   }

   public static CompoundTag payload(ServerLevel level, ServerPlayer player, UUID id) {
      RoadData data = RoadData.get(level);
      CompoundTag saved = data.junctions.get(id);
      if (saved == null) {
         throw new IllegalArgumentException("路口已不存在");
      } else {
         if (player != null) {
            RoadData.requireOwner(player, saved.getUUID("Owner"));
         }

         CompoundTag t = saved.copy();
         t.put("JunctionGraph", context(data, Set.of(BlockPos.of(t.getLong("CenterPos")))));
         LaneDeletes.junctionPayload(data, id, t);
         return t;
      }
   }

   public static String edit(ServerLevel level, ServerPlayer player, CompoundTag command) {
      RoadData data = RoadData.get(level);
      CompoundTag current = payload(level, player, command.getUUID("Id"));
      CompoundTag graph = current.getCompound("JunctionGraph");
      List<CompoundTag> configs = centers(graph);
      JunctionSpec requested = JunctionCodec.read(command.getCompound("Spec"));
      JunctionSpec original = JunctionCodec.read(current.getCompound("Spec"));
      if (requested.kind() == original.kind() && requested.center().equals(original.center()) && requested.arms().size() == original.arms().size()) {
         if (!current.getBoolean("GeneratedRing")
            || requested.islandRadius() == original.islandRadius()
               && requested.ringLanes() == original.ringLanes()
               && requested.ringLaneWidth() == original.ringLaneWidth()) {
            for (CompoundTag c : configs) {
               if (c.getUUID("Id").equals(command.getUUID("Id"))) {
                  c.put("Spec", command.getCompound("Spec").copy());
               }
            }

            commit(level, player, graph, plan(streets(graph), configs, graph), List.of(), Set.of(), Set.of(command.getUUID("Id")));
            return "路口设置已保存";
         } else {
            throw new IllegalArgumentException("环岛尺寸与八个接路点绑定；请删除环岛后用生成器重设尺寸");
         }
      } else {
         throw new IllegalArgumentException("连接道路已变化，请重新打开路口");
      }
   }

   public static void removeCenter(ServerLevel level, ServerPlayer player, UUID id) {
      try (RoadWorkChunks work = RoadWorkChunks.open(level)) {
         CompoundTag current = payload(level, player, id);
         CompoundTag graph = current.getCompound("JunctionGraph");
         List<RoadRecord> roads = streets(graph);
         BlockPos center = BlockPos.of(current.getLong("CenterPos"));
         Set<Long> ports = new HashSet<>();

         for (long p : current.getLongArray("Ports")) {
            ports.add(p);
         }

         if (current.getBoolean("GeneratedRing") && roads.stream().anyMatch(r -> ports.contains(r.a().asLong()) || ports.contains(r.b().asLong()))) {
            throw new IllegalArgumentException("请先删除环岛接出的道路，再删除环岛");
         }

         if (current.getBoolean("GeneratedRing")) {
            for (long point : ports) {
               BlockPos pos = BlockPos.of(point);
               work.node(pos);
               if (level.getBlockEntity(pos) instanceof NodeEntity) {
                  RoadData.requireNode(level, pos, player);
               }
            }
         }

         if (roads.stream().anyMatch(r -> r.assembly() != null && touches(r, center))) {
            throw new IllegalArgumentException("此路口连接了道路组合；请用拆路器删除外接支路，组合道路会自动恢复");
         }

         roads.removeIf(r -> touches(r, center));
         List<CompoundTag> configs = centers(graph);
         configs.removeIf(t -> t.getUUID("Id").equals(id));
         commit(level, player, graph, plan(roads, configs, graph), List.of(), Set.of(), Set.of(id));
         if (current.getBoolean("GeneratedRing")) {
            for (long pointx : ports) {
               BlockPos pos = BlockPos.of(pointx);
               if (!RoadData.get(level).linked(pos) && level.getBlockEntity(pos) instanceof NodeEntity) {
                  level.removeBlock(pos, false);
               }
            }
         }
      }
   }

   public static void createRing(ServerLevel level, ServerPlayer player, BlockPos pos, JunctionSpec spec) {
      UUID owner = player == null ? new UUID(0L, 0L) : player.getUUID();
      CompoundTag c = config(pos, owner);
      c.putBoolean("GeneratedRing", true);
      c.put("Spec", JunctionCodec.write(spec));
      c.put("CenterNode", RoadRecord.writeNode(new RoadGeometry.Node(spec.center(), 0.0, 0.0)));
      List<RoadGeometry.Node> ports = RoundaboutTool.ports(spec);
      long[] positions = new long[ports.size()];
      ArrayList<RoadData.NodeMove> moves = new ArrayList<>();

      for (int i = 0; i < ports.size(); i++) {
         RoadGeometry.Node n = ports.get(i);
         BlockPos at = BlockPos.containing(n.position().x(), n.position().y(), n.position().z());
         positions[i] = at.asLong();
         CompoundTag snapshot = new CompoundTag();
         snapshot.putUUID("Owner", owner);
         snapshot.put("Node", RoadRecord.writeNode(n));
         snapshot.putBoolean("HeightExplicit", true);
         moves.add(new RoadData.NodeMove(null, at, n, snapshot));
      }

      c.putLongArray("Ports", positions);
      commit(level, player, new CompoundTag(), plan(List.of(), List.of(c)), moves, Set.of(), Set.of(c.getUUID("Id")));
   }

   private AutoJunctions() {
   }

   private static record CommitInput(
      List<RoadIndex.Built> built, Set<UUID> removed, Set<BlockPos> selected, Map<UUID, CompoundTag> sideReceipts, Set<UUID> preservedSideHosts
   ) {
      Set<UUID> editing(Set<UUID> selected) {
         HashSet<UUID> direct = new HashSet<>(selected);
         direct.removeAll(this.preservedSideHosts);
         return direct;
      }
   }

   public static record Draft(List<RoadRecord> streets, List<RoadRecord> roads, List<CompoundTag> centers, Map<UUID, List<RoadRecord>> newSplits) {
      public Draft(List<RoadRecord> streets, List<RoadRecord> roads, List<CompoundTag> centers) {
         this(streets, roads, centers, Map.of());
      }
   }

   private static record End(RoadRecord road, BlockPos node, boolean first) {
      RoadGeometry.Mesh mesh() {
         return this.road.mesh();
      }

      RoadGeometry.V origin() {
         return (this.first ? this.road.start() : this.road.end()).position();
      }

      RoadGeometry.Sample at(double distance) {
         RoadGeometry.Mesh m = this.mesh();
         return RoadStructures.sample(m, this.first ? distance : m.length() - distance);
      }

      RoadGeometry.V away(double distance) {
         return this.at(distance).left().left().mul(this.first ? -1.0 : 1.0);
      }
   }

   static record StreetChange(CompoundTag before, AutoJunctions.Draft draft, List<RoadData.NodeMove> moves, Set<UUID> centers, UUID added) {
   }
}
