package com.sora.splineroads.world;

import com.sora.splineroads.core.CurvedRoadPlans;
import com.sora.splineroads.core.DoubleTrumpetToll;
import com.sora.splineroads.core.InterchangePlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.MultiInterchange;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadAxis;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.SmartSigns;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class Interchanges {
   private static final Map<CompoundTag, InterchangePlanner.Plan> PLANS = new LinkedHashMap<CompoundTag, InterchangePlanner.Plan>(16, 0.75F, true) {
      @Override
      protected boolean removeEldestEntry(Entry<CompoundTag, InterchangePlanner.Plan> e) {
         return this.size() > 3;
      }
   };

   public static CompoundTag descriptor(RoadData data, UUID id) {
      CompoundTag saved = data.interchanges.get(InterchangeJunctions.parent(data, id));
      if (saved == null) {
         throw new IllegalArgumentException("道路组合已不存在");
      } else {
         return saved.copy();
      }
   }

   public static CompoundTag write(InterchangePlanner.Options o) {
      CompoundTag t = new CompoundTag();
      t.putString("Preset", o.preset().name());
      t.putBoolean("Left", o.leftTraffic());
      t.putInt("Lanes", o.lanes());
      t.putDouble("Transition", o.transition());
      t.putDouble("Radius", o.radius());
      t.putDouble("Clearance", o.clearance());
      t.putInt("Upper", o.upper());
      t.putInt("JunctionAxis", o.junctionAxis());
      t.putInt("LoopMask", o.loopMask());
      t.putBoolean("AllowAbove", o.allowAbove());
      t.putBoolean("FlatConnector", o.flatConnector());
      t.putDouble("ConnectorHeight", o.connectorHeight());
      t.putDouble("RampWidth", o.rampWidth());
      t.putBoolean("AdjustEndpoints", o.adjustEndpoints());
      t.putBoolean("AllowShrink", o.allowShrink());
      t.putDouble("MaxLowering", o.maxLowering());
      DoubleTrumpetToll.Config config = o.toll();
      CompoundTag toll = new CompoundTag();
      toll.putBoolean("Enabled", config.enabled());
      toll.putBoolean("TwoWay", config.twoWay());
      toll.putBoolean("Reverse", config.reverse());
      toll.putString("Near", config.near().name());
      toll.putInt("ForwardETC", config.forwardEtc());
      toll.putInt("ReverseETC", config.reverseEtc());
      toll.putString("Name", config.name());
      t.put("Toll", toll);
      return t;
   }

   public static InterchangePlanner.Options read(CompoundTag t) {
      return new InterchangePlanner.Options(
         InterchangePlanner.Preset.valueOf(t.getString("Preset")),
         t.getBoolean("Left"),
         t.getInt("Lanes"),
         t.getDouble("Transition"),
         t.getDouble("Radius"),
         t.getDouble("Clearance"),
         t.getInt("Upper"),
         t.getDouble("RampWidth"),
         t.getBoolean("AdjustEndpoints"),
         t.getBoolean("AllowShrink"),
         t.contains("MaxLowering") ? t.getDouble("MaxLowering") : 8.0,
         t.contains("JunctionAxis") ? t.getInt("JunctionAxis") : 1,
         t.contains("LoopMask") ? t.getInt("LoopMask") : 5,
         t.getBoolean("AllowAbove"),
         t.getBoolean("FlatConnector"),
         t.getDouble("ConnectorHeight"),
         readToll(t)
      );
   }

   private static DoubleTrumpetToll.Config readToll(CompoundTag options) {
      if (!options.contains("Toll")) {
         return DoubleTrumpetToll.Config.OFF;
      } else {
         CompoundTag t = options.getCompound("Toll");
         return new DoubleTrumpetToll.Config(
            t.getBoolean("Enabled"),
            t.getBoolean("TwoWay"),
            t.getBoolean("Reverse"),
            DoubleTrumpetToll.Near.valueOf(t.getString("Near")),
            t.getInt("ForwardETC"),
            t.getInt("ReverseETC"),
            t.getString("Name")
         );
      }
   }

   static void clearRemovedDesign(CompoundTag t) {
      t.put("Options", write(read(t.getCompound("Options"))));

      for (String key : List.of(
         "DesignInputNodes", "DesignInputAxes", "DesignFittedNodes", "DesignShape", "DesignFixed", "ParallelDesignScale", "ParallelDesignOptions"
      )) {
         t.remove(key);
      }
   }

   public static InterchangePlanner.Plan plan(CompoundTag t) {
      if (ParallelSpui.present(t)) {
         return ParallelSpui.plan(t);
      } else {
         validateMainStyles(t);
         return InterchangeJunctions.applyEdits(InterchangeJunctions.elevationDefaults(ContinuousRoads.plan(t, Interchanges::planBase), t), t);
      }
   }

   private static void validateMainStyles(CompoundTag t) {
      for (String key : List.of("Main1", "Main2", "Main3")) {
         if (t.contains(key)) {
            RoadInfrastructure.Bridge bridge = RoadRecord.readSettings(t.getCompound(key)).options().infrastructure().bridge();
            if (bridge == RoadInfrastructure.Bridge.ARCH || bridge == RoadInfrastructure.Bridge.CABLE || bridge == RoadInfrastructure.Bridge.SUSPENSION) {
               throw new IllegalArgumentException("立交主路不支持大河拱桥、斜拉桥或悬索桥，请选择普通高架、梁桥、跨线桥或连续梁");
            }
         }
      }
   }

   private static InterchangePlanner.Plan planBase(CompoundTag t) {
      if (HostAxes.retiredInterchange(t)) {
         throw new IllegalArgumentException("请选择两条直线主路");
      } else {
         CompoundTag key = new CompoundTag();

         for (String field : List.of("Nodes", "Main1", "Main2", "Main3", "Options", "MultiBaseY", "Corridor", "ResolvedFit", "HostAxes")) {
            if (t.contains(field)) {
               key.put(field, t.get(field).copy());
            }
         }

         synchronized (PLANS) {
            InterchangePlanner.Plan cached = PLANS.get(key);
            if (cached != null) {
               return cached;
            }
         }

         ListTag tags = t.getList("Nodes", 10);
         if (tags.size() >= 2 && tags.size() <= 6) {
            RoadGeometry.Node[] nodes = new RoadGeometry.Node[tags.size()];

            for (int i = 0; i < nodes.length; i++) {
               nodes[i] = RoadRecord.readNode(tags.getCompound(i));
            }

            InterchangePlanner.Plan result = t.contains("Corridor")
               ? CurvedRoadPlans.corridor(
                  nodes,
                  RoadRecord.readSettings(t.getCompound("Main1")),
                  RoadRecord.readSettings(t.getCompound("Main2")),
                  read(t.getCompound("Options")),
                  Corridors.effective(t),
                  HostAxes.read(t)
               )
               : (
                  nodes.length >= 5
                     ? MultiInterchange.plan(
                        nodes,
                        new RoadGeometry.Settings[]{
                           RoadRecord.readSettings(t.getCompound("Main1")),
                           RoadRecord.readSettings(t.getCompound("Main2")),
                           RoadRecord.readSettings(t.getCompound("Main3"))
                        },
                        read(t.getCompound("Options")),
                        t.getDouble("MultiBaseY")
                     )
                     : CurvedRoadPlans.interchange(
                        nodes,
                        RoadRecord.readSettings(t.getCompound("Main1")),
                        RoadRecord.readSettings(t.getCompound("Main2")),
                        read(t.getCompound("Options")),
                        HostAxes.read(t)
                     )
               );
            synchronized (PLANS) {
               PLANS.put(key, result);
               return result;
            }
         } else {
            throw new IllegalArgumentException("道路组合端点数据不完整");
         }
      }
   }

   public static CompoundTag payload(ServerLevel level, ServerPlayer player, long[] points, UUID id) {
      CompoundTag var32;
      try (RoadWorkChunks workChunks = RoadWorkChunks.open(level)) {
         if (points.length < 2 || points.length > 6 || Arrays.stream(points).distinct().count() != (long)points.length) {
            throw new IllegalArgumentException("请选择对应数量的不同端点");
         }

         RoadData data = RoadData.get(level);
         CompoundTag t = new CompoundTag();
         if (id != null) {
            CompoundTag saved = data.interchanges.get(id);
            if (saved == null) {
               throw new IllegalArgumentException("立交已不存在");
            }

            if (!Arrays.equals(points, saved.getLongArray("Points"))) {
               throw new IllegalArgumentException("立交端点已变化，请重新打开立交");
            }

            if (player != null) {
               RoadData.requireOwner(player, saved.getUUID("Owner"));
            }

            t = saved.copy();
         } else {
            for (Entry<UUID, CompoundTag> entry : data.interchanges.entrySet()) {
               if (Arrays.equals(entry.getValue().getLongArray("Points"), points)) {
                  return payload(level, player, points, entry.getKey());
               }
            }
         }

         t.putString("Kind", t.contains("Corridor") ? "corridor" : "interchange");
         t.putLongArray("Points", points);
         ListTag nodes = new ListTag();

         for (int i = 0; i < points.length; i++) {
            nodes.add(
               RoadRecord.writeNode(
                  id != null && t.getBoolean("VirtualRange")
                     ? RoadRecord.readNode(t.getList("PlanningNodes", 10).getCompound(i))
                     : InterchangeEndpoints.read(level, player, t, i).node()
               )
            );
         }

         t.put("Nodes", nodes);
         if (points.length >= 5 && !t.contains("MultiBaseY")) {
            double base = Double.POSITIVE_INFINITY;

            for (int i = 0; i < nodes.size(); i++) {
               base = Math.min(base, RoadRecord.readNode(nodes.getCompound(i)).position().y());
            }

            t.putDouble("MultiBaseY", base);
         }

         if (!t.contains("Options")) {
            InterchangePlanner.Options d = InterchangePlanner.Options.DEFAULT;
            t.put(
               "Options",
               write(
                  points.length == 3
                     ? new InterchangePlanner.Options(
                        InterchangePlanner.Preset.TRUMPET, d.leftTraffic(), d.lanes(), d.transition(), d.radius(), d.clearance(), d.upper()
                     )
                     : d
               )
            );
         }

         if (points.length >= 5 && !t.getCompound("Options").getString("Preset").startsWith("DIRECTIONAL_")) {
            t.put(
               "Options",
               write(
                  new InterchangePlanner.Options(
                     points.length == 5 ? InterchangePlanner.Preset.DIRECTIONAL_FIVE : InterchangePlanner.Preset.DIRECTIONAL_SIX,
                     false,
                     1,
                     96.0,
                     20.0,
                     5.0,
                     2,
                     0.0,
                     true,
                     true,
                     0.0
                  )
               )
            );
         }

         if (ParallelSpui.capture(data, t, player)) {
            return t;
         }

         ArrayList<RoadAxis> hostAxes = new ArrayList<>();

         for (int axis = 0; axis < (points.length >= 5 ? 3 : 2); axis++) {
            String key = "Main" + (axis + 1);
            if (id == null || !t.contains(key)) {
               RoadGeometry.Settings settings = new RoadGeometry.Settings(
                  RoadGeometry.Mode.STRAIGHT, RoadGeometry.Style.H4_RAIL, RoadGeometry.Style.H4_RAIL.defaultWidth(), 1.0, 0.4, 90.0
               );
               List<RoadRecord> chain = axis * 2 + 1 < points.length
                  ? mainChain(data, BlockPos.of(points[axis * 2]), BlockPos.of(points[axis * 2 + 1]))
                  : (
                     points.length == 2
                        ? List.of()
                        : (points.length == 3 ? thirdArmChain(data, t, points) : branchProfile(data, BlockPos.of(points[axis * 2])))
                  );
               List<RoadRecord> profiles = chain;
               if (chain.isEmpty() && axis == 1 && points.length == 3) {
                  profiles = branchProfile(data, BlockPos.of(points[2]));
               }

               if (!profiles.isEmpty()) {
                  RoadRecord r = profiles.get(0);
                  if (player != null) {
                     RoadData.requireOwner(player, r.owner());
                  }

                  settings = r.settings();
               }

               if (!t.contains(key)) {
                  t.put(key, RoadRecord.writeSettings(settings));
               }

               if (points.length >= 5 && !chain.isEmpty()) {
                  RoadAxis path = HostAxes.chain(chain, BlockPos.of(points[axis * 2]));
                  if (path.curved() || !path.flat()) {
                     throw new IllegalArgumentException("五、六向立交仍需要平直且各自等高的主路");
                  }
               }

               if (axis * 2 < points.length && points.length < 5) {
                  List<RoadAxis> defaults = HostAxes.read(t);
                  hostAxes.add(chain.isEmpty() ? defaults.get(axis) : HostAxes.chain(chain, BlockPos.of(points[axis * 2])));
               }
            }
         }

         if (!hostAxes.isEmpty()) {
            HostAxes.write(t, hostAxes);
         }

         RoadStructures.Ground terrain = data.terrainGround();
         if (terrain != null) {
            for (int axisx = 0; axisx < 2 && axisx * 2 + 1 < nodes.size(); axisx++) {
               RoadGeometry.V a = RoadRecord.readNode(nodes.getCompound(axisx * 2)).position();
               RoadGeometry.V b = RoadRecord.readNode(nodes.getCompound(axisx * 2 + 1)).position();
               RoadGeometry.V center = a.add(b).mul(0.5);
               double floor = terrain.top(center.x(), center.z(), center.y());
               RoadGeometry.Settings settingsx = RoadRecord.readSettings(t.getCompound("Main" + (axisx + 1)));
               RoadGeometry.Mesh reference = RoadGeometry.build(
                  new RoadGeometry.Node(a, RoadPlanner.yaw(b.sub(a)), 0.0), new RoadGeometry.Node(b, RoadPlanner.yaw(b.sub(a)), 0.0), settingsx
               );
               t.putBoolean(
                  "ServiceElevated" + axisx,
                  settingsx.structure() == RoadGeometry.Structure.BRIDGE
                     || Double.isFinite(floor) && center.y() - floor > 2.0
                     || RoadStructures.elevated(reference, RoadStructures.sample(reference, reference.length() / 2.0), terrain)
               );
            }
         }

         if (points.length >= 5 && HostAxes.read(t).stream().anyMatch(ax -> ax.curved() || !ax.flat())) {
            throw new IllegalArgumentException("五、六向立交仍需要平直且各自等高的主路");
         }

         if (id == null && HostAxes.retiredInterchange(t)) {
            throw new IllegalArgumentException("请选择两条直线主路");
         }

         if (id == null) {
            ContinuousRoads.capture(data, t);
         } else if (t.contains("ContinuousSources")) {
            ListTag refreshed = new ListTag();

            for (Tag value : t.getList("ContinuousSources", 10)) {
               CompoundTag q = ((CompoundTag)value).copy();
               RoadRecord original = RoadRecord.load(q.getCompound("Road"));
               RoadIndex.Built current = data.index.roads.get(original.id());
               if (!q.getBoolean("Detached") || current != null) {
                  if (current != null) {
                     q.put(
                        "Road",
                        q.getBoolean("Detached") && current.record.assembly() == null
                           ? current.record.header()
                           : ContinuousRoads.updatedSource(original, current.record).header()
                     );
                  }

                  refreshed.add(q);
               }
            }

            t.put("ContinuousSources", refreshed);
         }

         if (id != null) {
            t.putUUID("Id", id);
            InterchangeJunctions.captureEdits(data, id, t);
         }

         var32 = t;
      }

      return var32;
   }

   private static List<RoadRecord> thirdArmChain(RoadData data, CompoundTag inputs, long[] points) {
      if (points.length != 3) {
         return List.of();
      } else {
         ListTag tags = inputs.getList("Nodes", 10);
         RoadGeometry.V a = RoadRecord.readNode(tags.getCompound(0)).position();
         RoadGeometry.V b = RoadRecord.readNode(tags.getCompound(1)).position();
         RoadGeometry.V c = RoadRecord.readNode(tags.getCompound(2)).position();
         RoadGeometry.V along = b.sub(a).horizontalUnit();
         RoadGeometry.V foot = a.add(along.mul(c.sub(a).dot(along)));
         foot = new RoadGeometry.V(foot.x(), c.y(), foot.z());
         RoadGeometry.V direction = foot.sub(c).horizontalUnit();
         double limit = foot.sub(c).horizontalLength() + RoadRecord.readSettings(inputs.getCompound("Main1")).width() / 2.0 + 1.0;
         BlockPos start = BlockPos.of(points[2]);
         BlockPos last = start;
         double farthest = 0.0;
         Set<BlockPos> visited = new HashSet<>();
         ArrayDeque<BlockPos> queue = new ArrayDeque<>();
         queue.add(start);
         visited.add(start);

         while (!queue.isEmpty() && visited.size() < 512) {
            BlockPos at = queue.remove();

            for (UUID id : data.index.atNode(at)) {
               RoadRecord r = data.index.roads.get(id).record;
               if (r.assembly() == null && !r.settings().style().ramp()) {
                  boolean forward = r.a().equals(at);
                  BlockPos next = forward ? r.b() : r.a();
                  RoadGeometry.V point = (forward ? r.end() : r.start()).position();
                  double distance = point.sub(c).dot(direction);
                  if (!(distance <= 0.01)
                     && !(distance > limit)
                     && !(Math.abs(point.sub(c).dot(direction.left())) > Math.max(24.0, limit * 0.6))
                     && !visited.contains(next)) {
                     visited.add(next);
                     queue.add(next);
                     if (distance > farthest) {
                        farthest = distance;
                        last = next;
                     }
                  }
               }
            }
         }

         return last.equals(start) ? List.of() : mainChain(data, start, last);
      }
   }

   private static List<RoadRecord> branchProfile(RoadData data, BlockPos at) {
      return data.index
         .atNode(at)
         .stream()
         .map(data.index.roads::get)
         .filter(Objects::nonNull)
         .map(b -> b.record)
         .filter(r -> r.assembly() == null && !r.settings().style().ramp())
         .sorted(Comparator.comparing(RoadRecord::id))
         .limit(1L)
         .toList();
   }

   static List<RoadRecord> mainChain(RoadData data, BlockPos a, BlockPos b) {
      Map<BlockPos, RoadRecord> via = new HashMap<>();
      Map<BlockPos, BlockPos> previous = new HashMap<>();
      ArrayDeque<BlockPos> queue = new ArrayDeque<>();
      queue.add(a);
      previous.put(a, a);

      while (!queue.isEmpty() && previous.size() < 512) {
         BlockPos at = queue.remove();
         if (at.equals(b)) {
            break;
         }

         for (UUID id : data.index.atNode(at)) {
            RoadRecord r = data.index.roads.get(id).record;
            if (r.junction() == null && !r.settings().style().ramp() && r.assembly() == null) {
               BlockPos next = r.a().equals(at) ? r.b() : r.a();
               if (!previous.containsKey(next)) {
                  previous.put(next, at);
                  via.put(next, r);
                  queue.add(next);
               }
            }
         }
      }

      if (!previous.containsKey(b)) {
         return List.of();
      } else {
         List<RoadRecord> chain = new ArrayList<>();

         for (BlockPos at = b; !at.equals(a); at = previous.get(at)) {
            chain.add(via.get(at));
         }

         Collections.reverse(chain);
         HostAxes.chain(chain, a);
         Set<UUID> ids = new HashSet<>();
         chain.forEach(rx -> ids.add(rx.id()));

         for (RoadRecord r : chain) {
            for (BlockPos p : List.of(r.a(), r.b())) {
               if (!p.equals(a) && !p.equals(b)) {
                  for (UUID idx : data.index.atNode(p)) {
                     if (!ids.contains(idx)) {
                        throw new IllegalArgumentException("选中的主路中间已有其他支路，请缩小选区或先处理该支路");
                     }
                  }
               }
            }
         }

         return chain;
      }
   }

   public static String build(ServerLevel level, ServerPlayer player, CompoundTag command) {
      String var134;
      try (RoadWorkChunks workChunks = RoadWorkChunks.open(level)) {
         RoadData data = RoadData.get(level);
         long[] points = command.getLongArray("Points");
         UUID existing = command.hasUUID("Id") ? command.getUUID("Id") : null;
         requireNoEndpointJunction(data, existing);
         CompoundTag t = payload(level, player, points, existing);
         t.put("Options", write(read(command.getCompound("Options"))));
         clearRemovedDesign(t);
         if (ParallelSpui.present(t)) {
            ParallelSpui.capture(data, t, player);
            if (player != null && read(t.getCompound("Options")).adjustEndpoints()) {
               ListTag fitted = new ListTag();
               ParallelSpui.plan(t).anchors().forEach(node -> fitted.add(RoadRecord.writeNode(node)));
               if (!command.getList("SourceNodes", 10).equals(t.getList("Nodes", 10)) || !command.getList("FittedNodes", 10).equals(fitted)) {
                  throw new IllegalArgumentException("端点或方案已变化，请重新打开立交并查看端点调整预览");
               }
            }

            return ParallelSpui.build(level, player, t);
         }

         if (command.contains("Corridor")) {
            t.put("Corridor", command.getCompound("Corridor").copy());
         }

         if (!t.contains("Corridor") && !HostAxes.presets(t).contains(read(t.getCompound("Options")).preset())) {
            throw new IllegalArgumentException("此布局不适用于当前向数，请重新选择立交预设");
         }

         for (String key : List.of("Main1", "Main2", "Main3")) {
            if (command.contains(key)) {
               t.put(key, command.getCompound(key).copy());
            }
         }

         validateMainStyles(t);
         if (t.contains("Corridor")) {
            HostAxes.normalizeCorridor(t);
         }

         if (player != null && command.contains("SourceAxesHash") && command.getInt("SourceAxesHash") != HostAxes.signature(t)) {
            throw new IllegalArgumentException("主路曲线已改变，请重新打开生成器查看预览");
         }

         InterchangePlanner.Plan plan = t.contains("Corridor") ? Corridors.resolve(level, player, t) : plan(t);
         UUID group = t.hasUUID("Id") ? t.getUUID("Id") : UUID.randomUUID();
         UUID owner = t.hasUUID("Owner") ? t.getUUID("Owner") : (player == null ? new UUID(0L, 0L) : player.getUUID());
         Set<UUID> removed = new HashSet<>();
         List<RoadRecord> retainedConnector = InterchangeConnectors.preserve(data, t, plan);
         if (!t.hasUUID("Id")) {
            for (int axis = 0; axis * 2 + 1 < points.length; axis++) {
               for (RoadRecord r : mainChain(data, BlockPos.of(points[axis * 2]), BlockPos.of(points[axis * 2 + 1]))) {
                  if (player != null) {
                     RoadData.requireOwner(player, r.owner());
                  }

                  removed.add(r.id());
               }
            }
         }

         if (!t.hasUUID("Id") && points.length == 3) {
            for (RoadRecord r : thirdArmChain(data, t, points)) {
               if (player != null) {
                  RoadData.requireOwner(player, r.owner());
               }

               removed.add(r.id());
            }
         }

         for (RoadIndex.Built built : data.index.roads.values()) {
            RoadRecord r = built.record;
            boolean main = false;

            for (int axis = 0; axis * 2 + 1 < points.length; axis++) {
               main |= r.a().asLong() == points[axis * 2] && r.b().asLong() == points[axis * 2 + 1]
                  || r.b().asLong() == points[axis * 2] && r.a().asLong() == points[axis * 2 + 1];
            }

            if (InterchangeJunctions.belongs(data, group, r.assembly()) || main && r.assembly() == null) {
               if (player != null) {
                  RoadData.requireOwner(player, r.owner());
               }

               removed.add(r.id());
            }
         }

         for (InterchangePlanner.Leg leg : plan.legs()) {
            UUID source = ContinuousRoads.sourceId(leg.name());
            if (source != null) {
               RoadIndex.Built old = data.index.roads.get(source);
               if (old != null && player != null) {
                  RoadData.requireOwner(player, old.record.owner());
               }

               removed.add(source);
            }
         }

         for (ContinuousRoads.Source source : ContinuousRoads.sources(t)) {
            if (ContinuousSections.detached(t, source.road().id())) {
               RoadIndex.Built old = data.index.roads.get(source.road().id());
               if (old != null) {
                  if (player != null) {
                     RoadData.requireOwner(player, old.record.owner());
                  }

                  removed.add(old.record.id());
               }
            }
         }

         ListTag proposedNodes = new ListTag();
         plan.anchors().forEach(node -> proposedNodes.add(RoadRecord.writeNode(node)));
         boolean adjust = read(t.getCompound("Options")).adjustEndpoints();
         if (player != null
            && adjust
            && (!command.getList("SourceNodes", 10).equals(t.getList("Nodes", 10)) || !command.getList("FittedNodes", 10).equals(proposedNodes))) {
            throw new IllegalArgumentException("端点或方案已变化，请重新打开立交并查看端点调整预览");
         }

         List<RoadData.NodeMove> moves = new ArrayList<>();
         List<RoadIndex.Built> sharedChanges = new ArrayList<>();
         long[] sourcePoints = (long[])points.clone();
         points = (long[])points.clone();

         for (int i = 0; i < points.length; i++) {
            BlockPos from = BlockPos.of(sourcePoints[i]);
            InterchangeEndpoints.Endpoint endpoint = InterchangeEndpoints.read(level, player, t, i);
            RoadGeometry.Node target = plan.anchors().get(i);
            boolean changed = !endpoint.node().equals(target);
            if (changed || endpoint.marker() == null) {
               if (changed && ContinuousRoads.virtual(t, i, target)) {
                  t.putBoolean("VirtualRange", true);
               } else {
                  if (changed && !adjust) {
                     throw new IllegalArgumentException("未允许调整端点");
                  }

                  if (changed) {
                     SharedRoadEndpoints.stage(data, level, player, from, endpoint.node(), target, sharedChanges, removed);
                  }

                  RoadGeometry.V p = target.position();
                  BlockPos to = changed ? BlockPos.containing(p.x(), p.y(), p.z()) : from;
                  moves.add(new RoadData.NodeMove(endpoint.marker() == null ? null : from, to, target, endpoint.snapshot()));
                  points[i] = to.asLong();
               }
            }
         }

         if (Arrays.stream(points).distinct().count() != (long)points.length) {
            throw new IllegalArgumentException("调整后的端点方块重叠");
         }

         HostAxes.fit(t, plan.anchors());
         t.put("Nodes", proposedNodes);
         t.put("PlanningNodes", proposedNodes.copy());
         t.putLongArray("Points", points);
         if (points.length >= 5) {
            workChunks.multiInterchange();
         }

         List<RoadIndex.Built> records = new ArrayList<>(sharedChanges);
         ListTag roles = new ListTag();
         ListTag signTopology = new ListTag();
         int part = 0;
         if (read(t.getCompound("Options")).toll().enabled()) {
            t.put("TollConnectorRoads", new ListTag());
            t.remove("TollConnectorShape");
         }

         t.putBoolean("IntegratedToll", read(t.getCompound("Options")).toll().enabled());
         ArrayList<JunctionSpec> junctionSpecs = new ArrayList<>();
         ArrayList<CompoundTag> junctionTags = new ArrayList<>();

         for (InterchangePlanner.Leg legx : plan.legs()) {
            for (RoadGeometry.Mesh mesh : List.of(legx.mesh())) {
               RoadGeometry.Node a = RoadRibbon.start(mesh);
               RoadGeometry.Node b = RoadRibbon.end(mesh);
               BlockPos pa = anchor(points, t, a.position());
               BlockPos pb = anchor(points, t, b.position());
               UUID sourceId = ContinuousRoads.sourceId(legx.name());
               UUID roadId = sourceId != null
                  ? ContinuousSections.id(legx.name())
                  : UUID.nameUUIDFromBytes((group + ":" + part++).getBytes(StandardCharsets.UTF_8));
               RoadRecord record = new RoadRecord(roadId, owner, pa, pb, a, b, mesh.settings(), false, 4).alignment(group, mesh);
               RoadRecord var121;
               if (sourceId != null) {
                  RoadRecord sourcex = ContinuousSections.source(t, legx.name());
                  if (a.position().distance(sourcex.start().position()) < 1.0E-6) {
                     pa = sourcex.a();
                  }

                  if (b.position().distance(sourcex.end().position()) < 1.0E-6) {
                     pb = sourcex.b();
                  }

                  for (RoadData.NodeMove move : moves) {
                     if (pa.equals(move.from())) {
                        pa = move.to();
                     }

                     if (pb.equals(move.from())) {
                        pb = move.to();
                     }
                  }

                  var121 = new RoadRecord(
                     roadId,
                     sourcex.owner(),
                     pa,
                     pb,
                     a,
                     b,
                     mesh.settings(),
                     sourcex.automatic(),
                     sourcex.clearance(),
                     List.of(),
                     sourcex.endCaps(),
                     sourcex.buildVersion(),
                     ContinuousRoads.donor(legx.name()) ? null : group,
                     mesh.samples(),
                     sourcex.furniturePhase()
                  );
               } else {
                  var121 = record.withAttachments(RoadAttachments.Data.EMPTY);
               }

               if (legx.name().equals("双喇叭中央连接段")) {
                  if (!retainedConnector.isEmpty()) {
                     for (RoadRecord keep : retainedConnector) {
                        records.add(new RoadIndex.Built(keep));
                        CompoundTag signLeg = new CompoundTag();
                        signLeg.putUUID("Road", keep.id());
                        signLeg.putInt("From", -1);
                        signLeg.putInt("To", -1);
                        signLeg.putBoolean("Ramp", false);
                        signTopology.add(signLeg);
                     }
                     continue;
                  }

                  InterchangeConnectors.capture(t, var121);
               }

               if (legx.toll() != null) {
                  var121 = InterchangeConnectors.group(var121.toll(legx.toll()), group);
               }

               if (DoubleTrumpetToll.connector(legx.name()) && !legx.name().equals("双喇叭中央连接段")) {
                  InterchangeConnectors.capturePart(t, var121);
               }

               if (legx.junction() != null) {
                  JunctionSpec spec = legx.junction().spec();
                  int slot = junctionSpecs.indexOf(spec);
                  if (slot < 0) {
                     slot = junctionSpecs.size();
                     junctionSpecs.add(spec);
                     junctionTags.add(InterchangeJunctions.descriptor(group, owner, slot, spec));
                  }

                  var121 = InterchangeJunctions.piece(group, owner, slot, legx.junction());
                  roadId = var121.id();
               }

               records.add(new RoadIndex.Built(var121));
               CompoundTag signLeg = new CompoundTag();
               signLeg.putUUID("Road", roadId);
               signLeg.putInt("From", legx.from());
               signLeg.putInt("To", legx.to());
               signLeg.putBoolean("Ramp", mesh.settings().style().ramp());
               signTopology.add(signLeg);
               if (t.contains("Corridor")) {
                  CompoundTag role = new CompoundTag();
                  role.putUUID("Road", roadId);
                  role.putString("Role", legx.name());
                  roles.add(role);
               }
            }
         }

         Map<UUID, Map<Integer, UUID>> partitionOwners = ContinuousSections.signOwners(plan, records);

         for (Entry<UUID, Map<Integer, UUID>> entry : partitionOwners.entrySet()) {
            for (SmartSigns.State state : data.smartSigns.road(entry.getKey())) {
               entry.getValue().putIfAbsent(state.key().slot(), entry.getKey());
            }
         }

         HashSet<UUID> logicalSources = new HashSet<>();

         for (InterchangePlanner.Leg legx : plan.legs()) {
            if (legx.name().contains("@") && data.streets.containsKey(ContinuousRoads.sourceId(legx.name()))) {
               logicalSources.add(ContinuousRoads.sourceId(legx.name()));
            }
         }

         ContinuousSections.updateSources(t, plan);
         HashMap<UUID, UUID> pointOwners = new HashMap<>();

         for (RoadIndex.Built r : records) {
            for (LanePoints.Point p : r.record.settings().options().lanePoints().points()) {
               pointOwners.put(p.id(), r.record.id());
            }
         }

         ArrayList<RoadIndex.Built> remapped = new ArrayList<>();

         for (RoadIndex.Built old : data.index.roads.values()) {
            if (!removed.contains(old.record.id())) {
               RoadRecord r = ContinuousSections.remap(old.record, pointOwners);
               if (r != old.record) {
                  removed.add(r.id());
                  remapped.add(new RoadIndex.Built(r));
               }
            }
         }

         records.addAll(remapped);

         for (int ix = 0; ix < records.size(); ix++) {
            RoadRecord r = ContinuousSections.remap(records.get(ix).record, pointOwners);
            if (r != records.get(ix).record) {
               records.set(ix, new RoadIndex.Built(r));
            }
         }

         long junctionPieces = records.stream().filter(rx -> rx.record.junction() != null).count();
         if ((long)records.size() - junctionPieces > 128L || junctionSpecs.size() > 2 || junctionPieces > 512L) {
            throw new IllegalArgumentException("立交道路或路口分段过多，请缩小范围");
         }

         for (RoadIndex.Built proposed : records) {
            for (RoadIndex.Built oldx : data.index.roads.values()) {
               if (!removed.contains(oldx.record.id()) && RoadIndex.overlapXZ(proposed.mesh, oldx.mesh, 1.0)) {
                  checkExternal(proposed.mesh, oldx.mesh);
               }
            }
         }

         List<RoadIndex.Built> existingRoads = new ArrayList<>();

         for (UUID oldId : removed) {
            if (data.index.roads.containsKey(oldId)) {
               existingRoads.add(data.index.roads.get(oldId));
            }
         }

         workChunks.roads(existingRoads);

         for (long point : t.getLongArray("PreservedNodes")) {
            workChunks.node(BlockPos.of(point));
         }

         Set<BlockPos> preserved = new HashSet<>();

         for (long point : points) {
            preserved.add(BlockPos.of(point));
         }

         for (long point : t.getLongArray("PreservedNodes")) {
            if (level.getBlockEntity(BlockPos.of(point)) instanceof NodeEntity) {
               preserved.add(BlockPos.of(point));
            }
         }

         for (UUID oldIdx : removed) {
            RoadIndex.Built oldxx = data.index.roads.get(oldIdx);
            if (t.contains("Corridor") && oldxx != null) {
               for (BlockPos end : List.of(oldxx.record.a(), oldxx.record.b())) {
                  boolean external = data.index.atNode(end).stream().anyMatch(rx -> !removed.contains(rx));
                  boolean retained = records.stream().anyMatch(rx -> rx.record.a().equals(end) || rx.record.b().equals(end));
                  if (external && !retained) {
                     throw new IllegalArgumentException("辅路端点已连接其他道路，不能改变该接点位置；请先处理外接道路");
                  }
               }
            }

            if (oldxx != null) {
               for (BlockPos p : List.of(oldxx.record.a(), oldxx.record.b())) {
                  if (level.getBlockEntity(p) instanceof NodeEntity) {
                     preserved.add(p);
                  }
               }
            }
         }

         if (t.contains("Corridor")) {
            for (BlockPos oldxxx : new ArrayList<>(preserved)) {
               if (!records.stream().anyMatch(rx -> rx.record.a().equals(oldxxx) || rx.record.b().equals(oldxxx))
                  && !data.index.atNode(oldxxx).stream().anyMatch(id -> !removed.contains(id))) {
                  if (moves.stream().noneMatch(m -> oldxxx.equals(m.from()))) {
                     moves.add(RoadData.NodeMove.retire(oldxxx));
                  }

                  preserved.remove(oldxxx);
               }
            }
         }

         Set<BlockPos> destinations = new HashSet<>();

         for (BlockPos pos : InterchangeConnectors.obsoleteNodes(data, t, records, removed)) {
            if (level.getBlockEntity(pos) instanceof NodeEntity) {
               RoadData.requireNode(level, pos, player);
               moves.add(RoadData.NodeMove.retire(pos));
            }

            preserved.remove(pos);
         }

         moves.forEach(movex -> {
            if (movex.to() != null) {
               destinations.add(movex.to());
            }
         });

         for (BlockPos px : preserved) {
            if (!destinations.contains(px)) {
               RoadData.requireNode(level, px, player);
            }
         }

         if (t.contains("Corridor") || plan.legs().stream().anyMatch(l -> l.name().contains("@"))) {
            if (t.contains("Corridor")) {
               t.put("CorridorRoles", roles);
            }

            Set<BlockPos> added = new HashSet<>();

            for (RoadIndex.Built recordx : records) {
               if (!recordx.mesh.settings().style().ramp()) {
                  for (boolean first : new boolean[]{true, false}) {
                     BlockPos pos = first ? recordx.record.a() : recordx.record.b();
                     if (destinations.contains(pos)) {
                        preserved.add(pos);
                     } else if (level.getBlockEntity(pos) instanceof NodeEntity) {
                        RoadData.requireNode(level, pos, player);
                        preserved.add(pos);
                     } else if (added.add(pos)) {
                        RoadGeometry.Node node = first ? recordx.record.start() : recordx.record.end();
                        CompoundTag snapshot = new CompoundTag();
                        snapshot.putUUID("Owner", recordx.record.owner());
                        snapshot.put("Node", RoadRecord.writeNode(node));
                        snapshot.putBoolean("HeightExplicit", true);
                        moves.add(new RoadData.NodeMove(null, pos, node, snapshot));
                        preserved.add(pos);
                     }
                  }
               }
            }
         }

         data.replaceAssembly(level, player, records, removed, preserved, moves, editLimit(t));
         partitionOwners.forEach(data.smartSigns::splitKeys);

         for (InterchangePlanner.Leg legxx : plan.legs()) {
            if (logicalSources.contains(ContinuousRoads.sourceId(legxx.name()))) {
               data.streets.put(ContinuousSections.id(legxx.name()), data.index.roads.get(ContinuousSections.id(legxx.name())).record);
            }
         }

         InterchangeJunctions.forget(data, group);

         for (CompoundTag junction : junctionTags) {
            data.junctions.put(junction.getUUID("Id"), junction);
         }

         t.putBoolean("StandardServiceJunctions", !junctionTags.isEmpty());

         for (RoadData.NodeMove move : moves) {
            if (!destinations.contains(move.from())) {
               preserved.remove(move.from());
            }
         }

         t.putLongArray("PreservedNodes", preserved.stream().mapToLong(BlockPos::asLong).sorted().toArray());
         t.putUUID("Id", group);
         t.putUUID("Owner", owner);
         t.remove("Partial");
         ListTag occupied = new ListTag();

         for (RoadIndex.Built recordxx : records) {
            if (group.equals(recordxx.record.assembly())
               && ContinuousRoads.sources(t).stream().anyMatch(sourcexx -> sourcexx.road().id().equals(recordxx.record.id()))) {
               CompoundTag range = new CompoundTag();
               range.putUUID("Road", recordxx.record.id());
               ContinuousRoads.Source sourcexx = ContinuousRoads.sources(t)
                  .stream()
                  .filter(entry -> entry.road().id().equals(recordxx.record.id()))
                  .findFirst()
                  .orElseThrow();
               int firstx = sourcexx.axis() * 2;
               double start = RoadAttachments.station(recordxx.mesh, plan.anchors().get(firstx).position());
               double endx = RoadAttachments.station(recordxx.mesh, plan.anchors().get(firstx + 1).position());
               range.putDouble("From", Math.min(start, endx));
               range.putDouble("To", Math.max(start, endx));
               range.put("Start", RoadRecord.writeNode(new RoadGeometry.Node(RoadAttachments.at(recordxx.mesh, Math.min(start, endx)), 0.0, 0.0)));
               range.put("End", RoadRecord.writeNode(new RoadGeometry.Node(RoadAttachments.at(recordxx.mesh, Math.max(start, endx)), 0.0, 0.0)));
               occupied.add(range);
            }
         }

         t.put("OccupiedRoads", occupied);

         for (Tag value : t.getList("ContinuousSources", 10)) {
            CompoundTag sourcexx = (CompoundTag)value;
            RoadRecord original = RoadRecord.load(sourcexx.getCompound("Road"));
            RoadIndex.Built current = data.index.roads.get(original.id());
            if (current != null) {
               RoadRecord r = current.record;
               sourcexx.put("Road", ContinuousRoads.updatedSource(original, r).header());
            }
         }

         t.put("SignTopology", signTopology);
         data.interchanges.put(group, t.copy());
         data.smartSigns.assemblyChanged(group);
         data.setDirty();
         var134 = (t.contains("Corridor") ? "道路组合已整体建成：" : "立交已整体建成：")
            + plan.movements()
            + (t.contains("Corridor") ? " 条同侧连接匝道，" : " 条转向匝道，")
            + records.size()
            + " 个可分区加载的路段；对应生成器右键路面可整体编辑";
      }

      return var134;
   }

   private static BlockPos anchor(long[] points, CompoundTag t, RoadGeometry.V p) {
      ListTag nodes = t.getList("Nodes", 10);

      for (int i = 0; i < points.length; i++) {
         if (RoadRecord.readNode(nodes.getCompound(i)).position().distance(p) < 1.0E-5) {
            return BlockPos.of(points[i]);
         }
      }

      return BlockPos.containing(p.x(), p.y(), p.z());
   }

   static void checkExternal(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      checkExternal(a, b, null);
   }

   static void checkExternal(RoadGeometry.Mesh a, RoadGeometry.Mesh b, RoadGeometry.Mesh previous) {
      RoadClearance.check(a, b, previous);
   }

   public static long[] points(RoadData data, UUID id) {
      CompoundTag saved = data.interchanges.get(id);
      if (saved == null) {
         throw new IllegalArgumentException("立交已不存在");
      } else {
         return (long[])saved.getLongArray("Points").clone();
      }
   }

   private static Set<BlockPos> selected(CompoundTag saved) {
      Set<BlockPos> nodes = new HashSet<>();
      if (saved != null) {
         for (String key : List.of("Points", "PreservedNodes")) {
            for (long point : saved.getLongArray(key)) {
               nodes.add(BlockPos.of(point));
            }
         }
      }

      return nodes;
   }

   public static void removePart(ServerLevel level, ServerPlayer player, UUID roadId) {
      RoadData data = RoadData.get(level);
      RoadIndex.Built road = data.index.roads.get(roadId);
      if (road == null) {
         throw new IllegalArgumentException("道路已经不存在");
      } else {
         if (player != null) {
            RoadData.requireOwner(player, road.record.owner());
         }

         UUID group = InterchangeJunctions.parent(data, road.record.assembly());
         requireNoEndpointJunction(data, group);
         CompoundTag saved = data.interchanges.get(group);
         if (saved != null && player != null) {
            RoadData.requireOwner(player, saved.getUUID("Owner"));
         }

         data.replaceAssembly(level, player, List.of(), Set.of(roadId), selected(saved), List.of(), editLimit(saved));
         if (saved != null) {
            if (data.index.roads.values().stream().noneMatch(r -> group.equals(r.record.assembly()))) {
               data.interchanges.remove(group);
            } else {
               saved.putBoolean("Partial", true);
            }

            data.setDirty();
         }
      }
   }

   public static void removeRamps(ServerLevel level, ServerPlayer player, UUID group) {
      RoadData data = RoadData.get(level);
      if (data.interchanges.containsKey(group) && ParallelSpui.present(data.interchanges.get(group))) {
         ParallelSpui.remove(level, player, data.interchanges.get(group).copy());
      } else {
         requireNoEndpointJunction(data, group);
         CompoundTag saved = data.interchanges.get(group);
         if (saved == null) {
            throw new IllegalArgumentException("立交不存在");
         } else {
            if (player != null) {
               RoadData.requireOwner(player, saved.getUUID("Owner"));
            }

            Set<UUID> removed = new HashSet<>();
            List<RoadIndex.Built> mains = new ArrayList<>();
            Set<BlockPos> selected = selected(saved);
            List<RoadData.NodeMove> markers = new ArrayList<>();
            Set<BlockPos> added = new HashSet<>();
            Set<UUID> restoredServiceMain = saved.contains("Options") ? InterchangeJunctions.restoreMain(data, saved, mains) : Set.of();

            try (RoadWorkChunks chunks = RoadWorkChunks.open(level)) {
               for (RoadIndex.Built built : data.index.roads.values()) {
                  RoadRecord r = built.record;
                  if (InterchangeJunctions.belongs(data, group, r.assembly())) {
                     if (player != null) {
                        RoadData.requireOwner(player, r.owner());
                     }

                     removed.add(r.id());
                     if (!restoredServiceMain.contains(r.id())
                        && r.junction() == null
                        && !r.settings().style().ramp()
                        && r.settings().style() != RoadGeometry.Style.UNMARKED
                        && !built.mesh.closed()) {
                        boolean continuousMain = ContinuousRoads.sources(saved).stream().anyMatch(source -> source.road().id().equals(r.id()));
                        if (continuousMain || saved.contains("Corridor") || RoadProfile.catalog(r.settings().style()).twoWay()) {
                           int arms = saved.getLongArray("Points").length;
                           if (continuousMain
                              || arms != 3 && arms != 5
                              || r.a().asLong() != saved.getLongArray("Points")[arms - 1] && r.b().asLong() != saved.getLongArray("Points")[arms - 1]) {
                              mains.add(new RoadIndex.Built(r.structures(List.of()).alignment(null, built.mesh)));

                              for (boolean first : new boolean[]{true, false}) {
                                 BlockPos pos = first ? r.a() : r.b();
                                 selected.add(pos);
                                 chunks.node(pos);
                                 if (level.getBlockEntity(pos) instanceof NodeEntity) {
                                    RoadData.requireNode(level, pos, player);
                                 } else if (added.add(pos)) {
                                    RoadGeometry.Node node = first ? r.start() : r.end();
                                    CompoundTag snapshot = new CompoundTag();
                                    snapshot.putUUID("Owner", r.owner());
                                    snapshot.put("Node", RoadRecord.writeNode(node));
                                    snapshot.putBoolean("HeightExplicit", true);
                                    markers.add(new RoadData.NodeMove(null, pos, node, snapshot));
                                 }
                              }
                           }
                        }
                     }
                  }
               }

               data.replaceAssembly(level, player, mains, removed, selected, markers, editLimit(saved));
               InterchangeJunctions.forget(data, group);
               data.interchanges.remove(group);
               data.setDirty();
            }
         }
      }
   }

   public static void remove(ServerLevel level, ServerPlayer player, UUID id) {
      CompoundTag current = RoadData.get(level).interchanges.get(id);
      if (current != null && ParallelSpui.present(current)) {
         ParallelSpui.remove(level, player, current.copy());
      } else {
         RoadData data = RoadData.get(level);
         requireNoEndpointJunction(data, id);
         CompoundTag saved = data.interchanges.get(id);
         if (saved == null) {
            throw new IllegalArgumentException("立交不存在");
         } else {
            if (player != null) {
               RoadData.requireOwner(player, saved.getUUID("Owner"));
            }

            Set<UUID> ids = new HashSet<>();

            for (RoadIndex.Built r : data.index.roads.values()) {
               if (InterchangeJunctions.belongs(data, id, r.record.assembly())) {
                  ids.add(r.record.id());
               }
            }

            data.replaceAssembly(
               level,
               player,
               List.of(),
               ids,
               Arrays.stream(saved.getLongArray("Points")).<BlockPos>mapToObj(BlockPos::of).collect(Collectors.toSet()),
               InterchangeConnectors.obsoleteNodes(data, saved.copy(), List.of(), ids)
                  .stream()
                  .filter(p -> level.getBlockEntity(p) instanceof NodeEntity)
                  .map(RoadData.NodeMove::retire)
                  .toList(),
               editLimit(saved)
            );
            InterchangeJunctions.forget(data, id);
            data.interchanges.remove(id);
            data.setDirty();
         }
      }
   }

   static void requireNoEndpointJunction(RoadData data, UUID group) {
      if (group != null && data.streets.values().stream().anyMatch(r -> group.equals(r.assembly()))) {
         throw new IllegalArgumentException("组合端点已形成路口；请先删除该路口的外接支路，再调整或删除组合");
      }
   }

   private static int editLimit(CompoundTag t) {
      return t != null && t.getLongArray("Points").length >= 3 ? 8000000 : 4000000;
   }

   private Interchanges() {
   }
}
