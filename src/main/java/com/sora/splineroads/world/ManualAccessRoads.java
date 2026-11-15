package com.sora.splineroads.world;

import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.net.RoadActionContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ManualAccessRoads {
   private static final Map<RoadData, Map<UUID, ManualAccessRoads.Approval>> APPROVALS = new WeakHashMap<>();

   private static boolean supported(RoadRecord r) {
      return r.assembly() == null
         && r.toll() == null
         && r.junction() == null
         && LaneTopology.metadata(r).link() == null
         && !r.mesh().closed()
         && LanePoints.supported(r.settings())
         && !r.settings().style().ramp();
   }

   static CompoundTag payload(ServerPlayer player, RoadGeometry.V hit, CompoundTag selection) {
      RoadData data = RoadData.get(player.serverLevel());
      RoadRecord host = null;
      if (selection.contains("LaneFrom")) {
         LanePoints.Ref ref = LanePointCodec.ref(selection.getCompound("LaneFrom"));
         RoadIndex.Built built = data.index.roads.get(ref.road());
         if (built != null) {
            host = built.record;
         }
      }

      if (host == null) {
         host = data.index
            .roads
            .values()
            .stream()
            .map(b -> b.record)
            .filter(ManualAccessRoads::supported)
            .min(Comparator.comparingDouble(r -> RoadQueries.horizontal(r.mesh(), hit).horizontalDistance()))
            .orElseThrow(() -> new IllegalArgumentException("附近没有可接入的独立普通道路或高速道路"));
      }

      if (!supported(host)) {
         throw new IllegalArgumentException("请选择独立普通道路或高速道路作为主路");
      } else {
         RoadData.requireOwner(player, host.owner());
         CompoundTag t = new CompoundTag();
         t.putString("Kind", "manualAccess");
         t.putString("Dimension", player.level().dimension().location().toString());
         t.putUUID("Road", host.id());
         t.putInt("Signature", host.header().hashCode());
         t.putDouble("X", hit.x());
         t.putDouble("Y", hit.y());
         t.putDouble("Z", hit.z());
         t.putDouble("SpurLength", 80.0);
         return t;
      }
   }

   private static CompoundTag key(CompoundTag t) {
      CompoundTag out = new CompoundTag();

      for (String name : List.of("Road", "Signature", "X", "Y", "Z", "SpurLength", "Dimension")) {
         if (t.contains(name)) {
            out.put(name, t.get(name).copy());
         }
      }

      return out;
   }

   static CompoundTag preview(ServerPlayer player, CompoundTag command) {
      return preview(player.serverLevel(), player, command);
   }

   static CompoundTag preview(ServerLevel level, ServerPlayer player, CompoundTag command) {
      CompoundTag var49;
      try (RoadWorkChunks chunks = RoadWorkChunks.open(level)) {
         RoadData data = RoadData.get(level);
         RoadIndex.Built built = data.index.roads.get(command.getUUID("Road"));
         if (built == null || !supported(built.record) || built.record.header().hashCode() != command.getInt("Signature")) {
            throw new IllegalArgumentException("主路已变化，请重新放置接入点");
         }

         RoadRecord host = built.record;
         if (player != null) {
            RoadData.requireOwner(player, host.owner());
         }

         if (!level.dimension().location().toString().equals(command.getString("Dimension"))) {
            throw new IllegalArgumentException("地图维度已变化");
         }

         RoadGeometry.V target = new RoadGeometry.V(command.getDouble("X"), command.getDouble("Y"), command.getDouble("Z"));
         double length = command.getDouble("SpurLength");
         if (!RoadGeometry.finite(target.x(), target.y(), target.z(), length) || length < 64.0 || length > 160.0) {
            throw new IllegalArgumentException("接出道路长度应为64–160格");
         }

         RoadQueries.Projection q = RoadQueries.horizontal(built.mesh, target);
         double distance = q.horizontalDistance();
         if (distance < length + 48.0 || distance > 384.0) {
            throw new IllegalArgumentException("接入点与主路需保留至少道路长度加48格、最多384格的横向空间");
         }

         RoadGeometry.V outward = target.sub(q.sample().center()).horizontalUnit();
         double station = q.sample().distance();
         double spread = Math.max(64.0, Math.min(120.0, distance * 0.6));
         if (station - spread < 40.0 || station + spread > built.mesh.length() - 40.0) {
            throw new IllegalArgumentException("主路两侧需有足够分合流长度，请在更长路段中部放置");
         }

         List<LanePoints.Lane> lanes = LaneAdditions.slots(built.mesh, station)
            .stream()
            .filter(i -> LaneSections.active(built.mesh, station, i))
            .map(i -> LanePoints.lane(built.mesh, station, i))
            .toList();
         LanePoints.Lane outgoing = lanes.stream()
            .min(Comparator.comparingDouble(l -> l.position().sub(target).horizontalLength()))
            .orElseThrow(() -> new IllegalArgumentException("所选主路没有可用车道"));
         LanePoints.Lane incoming = lanes.stream()
            .filter(l -> l.sign() == outgoing.sign())
            .max(Comparator.comparingDouble(l -> l.position().sub(q.sample().center()).dot(outward)))
            .orElseThrow();
         LanePoints.Point departure = LanePoints.point(
            UUID.randomUUID(), LanePoints.Origin.MANUAL, built.mesh, station - spread * (double)outgoing.sign(), outgoing.index()
         );
         LanePoints.Point arrival = LanePoints.point(
            UUID.randomUUID(), LanePoints.Origin.MANUAL, built.mesh, station + spread * (double)outgoing.sign(), incoming.index()
         );
         ArrayList<LanePoints.Point> points = new ArrayList<>(LaneTopology.metadata(host).points());
         points.add(departure);
         points.add(arrival);
         RoadRecord changedHost = host.withLanePoints(LaneTopology.metadata(host).points(points));
         RoadGeometry.V start = target.sub(outward.mul(length));
         RoadGeometry.Node na = new RoadGeometry.Node(start, RoadPlanner.yaw(outward), 0.0);
         RoadGeometry.Node nb = new RoadGeometry.Node(target, RoadPlanner.yaw(outward), 0.0);
         BlockPos a = BlockPos.containing(start.x(), start.y(), start.z());
         BlockPos b = BlockPos.containing(target.x(), target.y(), target.z());
         RoadGeometry.Settings settings = RoadLanes.configure(
               RoadGeometry.Settings.defaults(), RoadProfile.Type.ORDINARY, new RoadLanes.Counts(1, 1), RoadProfile.layout(built.mesh, q.sample()).laneWidth()
            )
            .structure(RoadGeometry.Structure.AUTO);
         settings = RoadPlanner.mode(settings, RoadGeometry.Mode.STRAIGHT, 90.0)
            .options(
               settings.options()
                  .traffic(host.settings().options().leftTraffic())
                  .route(RoadProfile.Routing.DEFAULT.fit(false))
                  .infrastructure(settings.options().infrastructure().gantry(RoadInfrastructure.Gantry.OFF))
            );
         RoadRecord spur = new RoadRecord(UUID.randomUUID(), host.owner(), a, b, na, nb, settings, false, 4);
         RoadGeometry.Mesh spurMesh = spur.mesh();
         List<LanePoints.Lane> spurLanes = LaneAdditions.slots(spurMesh, length / 2.0).stream().map(i -> LanePoints.lane(spurMesh, length / 2.0, i)).toList();
         LanePoints.Point entry = LanePoints.point(
            UUID.randomUUID(), LanePoints.Origin.MANUAL, spurMesh, length / 2.0, spurLanes.stream().filter(l -> l.sign() > 0).findFirst().orElseThrow().index()
         );
         LanePoints.Point exit = LanePoints.point(
            UUID.randomUUID(), LanePoints.Origin.MANUAL, spurMesh, length / 2.0, spurLanes.stream().filter(l -> l.sign() < 0).findFirst().orElseThrow().index()
         );
         spur = spur.withLanePoints(LanePoints.Data.EMPTY.points(List.of(entry, exit)));
         LinkedHashMap<UUID, RoadRecord> all = new LinkedHashMap<>(LaneTopology.records(data));
         all.put(host.id(), changedHost);
         all.put(spur.id(), spur);
         LanePoints.Options options = new LanePoints.Options(
            LanePoints.Path.AUTO, LanePoints.Departure.BRANCH, LanePoints.Arrival.FLOW, 24.0, 24.0, LanePoints.Elevation.AUTO, LanePoints.Landing.EXACT
         );
         LanePoints.Link firstLink = new LanePoints.Link(
            LanePoints.Ref.lane(host.id(), departure.id()), LanePoints.Ref.lane(spur.id(), entry.id()), options, null
         );
         RoadRecord first = LaneRamps.generate(data, all, UUID.randomUUID(), host.owner(), firstLink);
         all.put(first.id(), first);
         LanePoints.Link secondLink = new LanePoints.Link(
            LanePoints.Ref.lane(spur.id(), exit.id()), LanePoints.Ref.lane(host.id(), arrival.id()), options, null
         );
         RoadRecord second = LaneRamps.generate(data, all, UUID.randomUUID(), host.owner(), secondLink);
         all.put(second.id(), second);
         HashSet<BlockPos> selected = new HashSet<>();
         selected.addAll(LaneRamps.contactEndpoints(all, first));
         selected.addAll(LaneRamps.contactEndpoints(all, second));
         selected.add(a);
         selected.add(b);
         UUID token = UUID.randomUUID();
         List<RoadRecord> requested = List.of(changedHost, spur, first, second);
         List<RoadIndex.Built> prepared = data.prepareAssembly(
            level,
            player,
            requested.stream().map(RoadIndex.Built::new).toList(),
            Set.of(host.id()),
            selected,
            List.of(node(a, na, host.owner()), node(b, nb, host.owner())),
            token,
            Set.of(spur.id(), first.id(), second.id()),
            true
         );
         Map<UUID, ManualAccessRoads.Approval> table = APPROVALS.computeIfAbsent(data, d -> new LinkedHashMap<>());
         table.entrySet()
            .removeIf(
               e -> System.nanoTime() - e.getValue().created() > 600000000000L
                     || Objects.equals(e.getValue().owner(), player == null ? null : player.getUUID())
            );
         table.put(token, new ManualAccessRoads.Approval(player == null ? null : player.getUUID(), host, key(command), System.nanoTime()));

         while (table.size() > 4) {
            table.remove(table.keySet().iterator().next());
         }

         CompoundTag reply = command.copy();
         reply.remove("Action");
         reply.putString("Kind", "manualAccess");
         reply.putUUID("Token", token);
         ListTag roads = new ListTag();
         prepared.forEach(r -> roads.add(r.record.save()));
         reply.put("PlannerPreview", roads);
         RoadActionContext.echo(command, reply);
         var49 = reply;
      }

      return var49;
   }

   private static RoadData.NodeMove node(BlockPos p, RoadGeometry.Node n, UUID owner) {
      CompoundTag t = new CompoundTag();
      t.putUUID("Owner", owner);
      t.put("Node", RoadRecord.writeNode(n));
      t.putBoolean("HeadingLocked", true);
      t.putBoolean("HeightExplicit", true);
      return new RoadData.NodeMove(null, p, n, t);
   }

   static String build(ServerPlayer player, CompoundTag t) {
      return build(player.serverLevel(), player, t);
   }

   static String build(ServerLevel level, ServerPlayer player, CompoundTag t) {
      RoadData data = RoadData.get(level);
      Map<UUID, ManualAccessRoads.Approval> table = APPROVALS.get(data);
      ManualAccessRoads.Approval approval = table != null && t.hasUUID("Token") ? table.remove(t.getUUID("Token")) : null;
      if (approval != null
         && System.nanoTime() - approval.created() <= 600000000000L
         && Objects.equals(approval.owner(), player == null ? null : player.getUUID())
         && approval.key().equals(key(t))
         && data.index.roads.get(approval.source().id()) != null
         && data.index.roads.get(approval.source().id()).record == approval.source()) {
         data.buildPrepared(level, player, t.getUUID("Token"));
         return "双向进出匝道、接出道路及分合流点已统一建造";
      } else {
         throw new IllegalArgumentException("接入匝道预览已失效，请重新预览");
      }
   }

   private ManualAccessRoads() {
   }

   private static record Approval(UUID owner, RoadRecord source, CompoundTag key, long created) {
   }
}
