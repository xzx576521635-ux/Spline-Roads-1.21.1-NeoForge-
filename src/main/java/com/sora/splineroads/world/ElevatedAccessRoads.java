package com.sora.splineroads.world;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.core.ElevatedAccess;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadEndpointSections;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.net.RoadActionContext;
import com.sora.splineroads.net.RoadNetwork;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class ElevatedAccessRoads {
   private static final Map<RoadData, Map<UUID, ElevatedAccessRoads.Approval>> APPROVALS = new WeakHashMap<>();

   public static boolean saved(RoadData data, UUID id) {
      return id != null && data.interchanges.getOrDefault(id, new CompoundTag()).getBoolean("ElevatedAccess");
   }

   static LanePoints.Data logicalLanes(RoadRecord street, RoadRecord live) {
      LanePoints.Data lanes = LaneTopology.metadata(live);
      if (!lanes.access().isEmpty() && street != live) {
         double shift = street.fragment() != null && live.fragment() != null && street.fragment().source().equals(live.fragment().source())
            ? live.fragment().origin() - street.fragment().origin()
            : RoadQueries.horizontal(street.rawMesh(), live.rawMesh().first().center()).sample().distance();
         if (Math.abs(shift) < 1.0E-8) {
            return lanes;
         } else {
            HashSet<UUID> ids = new HashSet<>();
            lanes.access().forEach(a -> ids.add(a.connection()));
            List<ElevatedAccess.Reservation> access = lanes.access().stream().map(a -> ElevatedAccess.shifted(a, shift)).toList();
            ArrayList<LaneSections.Cut> cuts = new ArrayList<>(lanes.cuts().stream().filter(c -> !ids.contains(c.connection())).toList());
            ArrayList<LaneAdditions.Addition> adds = new ArrayList<>(lanes.additions().stream().filter(a -> !ids.contains(a.connection())).toList());

            for (ElevatedAccess.Reservation reservation : access) {
               cuts.addAll(reservation.cuts());
               adds.addAll(reservation.additions());
            }

            return lanes.cuts(cuts).additions(adds).access(access);
         }
      } else {
         return lanes;
      }
   }

   static void protect(RoadData data, List<RoadIndex.Built> proposed, Set<UUID> removed) {
      Map<UUID, RoadRecord> all = LaneTopology.records(data);
      removed.forEach(all::remove);

      for (RoadIndex.Built b : proposed) {
         all.put(b.record.id(), b.record);
      }

      label86:
      for (Entry<UUID, CompoundTag> entry : data.interchanges.entrySet()) {
         if (entry.getValue().getBoolean("ElevatedAccess") && all.containsKey(entry.getKey())) {
            CompoundTag t = entry.getValue();
            RoadIndex.Built old = data.index.roads.get(t.getUUID("Main"));
            RoadRecord next = all.get(t.getUUID("Main"));
            if (old != null && next != null) {
               if (sameGeometry(old.record, next)
                  && !next.settings().options().attachments().points().stream().noneMatch(p -> p.id().equals(t.getUUID("Point")) && p.elevated())) {
                  for (UUID member : members(t)) {
                     if (!member.equals(next.id())) {
                        RoadIndex.Built prior = data.index.roads.get(member);
                        RoadRecord proposedRoad = all.get(member);
                        if (prior == null || proposedRoad == null || !sameGeometry(prior.record, proposedRoad)) {
                           throw new IllegalArgumentException("此段承载相邻高架出入口的辅路，请先删除连接");
                        }
                     }
                  }

                  BlockPos end = BlockPos.of(t.getLong("Endpoint"));
                  List<RoadRecord> neighbors = all.values()
                     .stream()
                     .filter(rx -> !rx.id().equals(entry.getKey()) && (rx.a().equals(end) || rx.b().equals(end)))
                     .toList();
                  if (neighbors.size() <= 1 && (!neighbors.isEmpty() || data.index.atNode(end).stream().allMatch(entry.getKey()::equals))) {
                     Iterator var18 = neighbors.iterator();

                     RoadIndex.Built before;
                     RoadRecord r;
                     do {
                        if (!var18.hasNext()) {
                           continue label86;
                        }

                        r = (RoadRecord)var18.next();
                        before = data.index.roads.get(r.id());
                     } while (before != null && sameGeometry(before.record, r));

                     throw new IllegalArgumentException("该道路尽头连接高架出入口，请先删除连接再改变其线形或断面");
                  }

                  throw new IllegalArgumentException("高架出入口只能连接道路尽头，不能把此端点改成多路交汇");
               }

               throw new IllegalArgumentException("主路已有高架出入口；改变主路线形或断面前，请先删除该连接");
            }

            throw new IllegalArgumentException("主路仍连接高架，请同时删除依赖的高架连接");
         }
      }
   }

   private static boolean sameGeometry(RoadRecord a, RoadRecord b) {
      return a.start().equals(b.start())
         && a.end().equals(b.end())
         && a.rawMesh().samples().equals(b.rawMesh().samples())
         && a.settings().thickness() == b.settings().thickness()
         && a.settings().style() == b.settings().style()
         && RoadLanes.counts(a.settings()).equals(RoadLanes.counts(b.settings()))
         && a.settings().options().leftTraffic() == b.settings().options().leftTraffic()
         && a.settings().options().ends().equals(b.settings().options().ends());
   }

   static void prune(RoadData data) {
      data.interchanges.entrySet().removeIf(e -> e.getValue().getBoolean("ElevatedAccess") && !data.index.roads.containsKey(e.getKey()));
   }

   private static RoadIndex.Built host(RoadData data, UUID id) {
      RoadIndex.Built b = data.index.roads.get(id);
      if (b != null
         && b.record.assembly() == null
         && b.record.junction() == null
         && b.record.toll() == null
         && !b.record.railway()
         && LaneTopology.metadata(b.record).link() == null) {
         return b;
      } else {
         throw new IllegalArgumentException("高架出入点的主路已变化，请重新选择普通道路或高速道路");
      }
   }

   private static RoadAttachments.Point point(RoadRecord road, UUID id) {
      return road.settings()
         .options()
         .attachments()
         .points()
         .stream()
         .filter(p -> p.id().equals(id) && p.elevated())
         .findFirst()
         .orElseThrow(() -> new IllegalArgumentException("高架出入点已不存在"));
   }

   private static List<RoadIndex.Built> endpoint(RoadData data, BlockPos endpoint, UUID edited) {
      List<RoadIndex.Built> roads = data.index.atNode(endpoint).stream().filter(id -> !id.equals(edited)).map(data.index.roads::get).toList();
      if (roads.size() == 1 && ElevatedPointTool.supported(roads.get(0).record)) {
         RoadIndex.Built road = roads.get(0);
         double station = road.record.a().equals(endpoint) ? Math.min(0.25, road.mesh.length() / 2.0) : Math.max(0.0, road.mesh.length() - 0.25);
         if (!RoadStreetscape.raised(road.mesh, RoadStructures.sample(road.mesh, station))) {
            throw new IllegalArgumentException("所选道路尽头须为高架路段");
         } else {
            return roads;
         }
      } else {
         throw new IllegalArgumentException("高架出入口须连接已有高架的尽头端点");
      }
   }

   public static CompoundTag payload(ServerLevel level, ServerPlayer player, UUID main, UUID marker, BlockPos end, boolean first, UUID edited) {
      RoadData data = RoadData.get(level);
      RoadIndex.Built host = host(data, main);
      RoadAttachments.Point point = point(host.record, marker);
      if (edited == null && LaneTopology.metadata(host.record).access().stream().anyMatch(a -> a.point().equals(marker))) {
         throw new IllegalArgumentException("该高架出入点已连接道路，请编辑已有连接");
      } else {
         if (edited != null) {
            CompoundTag receipt = data.interchanges.get(edited);
            if (!saved(data, edited)
               || !data.index.roads.containsKey(edited)
               || !main.equals(receipt.getUUID("Main"))
               || !marker.equals(receipt.getUUID("Point"))
               || end.asLong() != receipt.getLong("Endpoint")
               || first != receipt.getBoolean("MainFirst")) {
               throw new IllegalArgumentException("已建高架连接已改变，请重新选择");
            }
         }

         NodeEntity node = RoadData.requireNode(level, end, player);
         List<RoadIndex.Built> connected = endpoint(data, end, edited);
         if (player != null) {
            RoadData.requireOwner(player, host.record.owner());

            for (RoadIndex.Built b : connected) {
               RoadData.requireOwner(player, b.record.owner());
            }
         }

         RoadGeometry.Sample station = RoadQueries.horizontal(host.mesh, point.position()).sample();
         RoadLanes.Counts counts = RoadLanes.counts(host.record.settings());
         if (!RoadLanes.counts(connected.get(0).record.settings()).twoWay()) {
            first = connected.get(0).record.a().equals(end);
         }

         int axis = !counts.twoWay() ? (first ? 1 : -1) : (node.node().position().sub(station.center()).dot(station.left().left().mul(-1.0)) < 0.0 ? -1 : 1);
         boolean positive = (first ? axis : -axis) > 0;
         int maxForward = positive ? counts.forward() : counts.reverse();
         int maxReverse = positive ? counts.reverse() : counts.forward();
         RoadRecord upper = connected.get(0).record;
         boolean atFirst = upper.a().equals(end);
         RoadGeometry.Settings settings = RoadEndpointSections.section(upper.caps(0).mesh(), atFirst, atFirst != first);
         CompoundTag t = new CompoundTag();
         t.putString("Kind", "elevatedAccess");
         t.putString("Dimension", level.dimension().location().toString());
         t.putUUID("Main", main);
         t.putUUID("Point", marker);
         t.putLong("Endpoint", end.asLong());
         t.putBoolean("MainFirst", first);
         t.putInt("Signature", host.record.header().hashCode());
         t.putInt("TargetSignature", Objects.hash(node.node(), connected.stream().map(b -> b.record.header()).toList()));
         t.putInt("MaxForward", maxForward);
         t.putInt("MaxReverse", maxReverse);
         t.putBoolean("Locked", true);
         t.put("Settings", RoadRecord.writeSettings(settings));
         if (edited != null) {
            t.putUUID("Id", edited);
         }

         return t;
      }
   }

   static void select(ServerPlayer player, CompoundTag tool, RoadIndex.Built road, RoadAttachments.Point marker) {
      if (tool.hasUUID("SideMain")) {
         throw new IllegalArgumentException("高架出入点须连接已有高架的尽头端点");
      } else {
         String dimension = player.level().dimension().location().toString();
         if (tool.contains("Start") && dimension.equals(tool.getString("Dimension"))) {
            open(player, tool, payload(player.serverLevel(), player, road.record.id(), marker.id(), BlockPos.of(tool.getLong("Start")), false, null));
         } else {
            tool.remove("ElevatedSelection");
            tool.remove("SideSelection");
            tool.remove("Start");
            tool.remove("CurveControlNodes");
            tool.putUUID("SideMain", road.record.id());
            tool.putUUID("SidePoint", marker.id());
            tool.putString("Dimension", dimension);
            player.displayClientMessage(Component.literal("已选高架出入点，请右键已有高架的尽头端点"), true);
         }
      }
   }

   static void open(ServerPlayer player, CompoundTag tool, CompoundTag payload) {
      tool.put("ElevatedSelection", selection(payload));
      RoadNetwork.open(player, payload);
   }

   public static void open(ServerPlayer player, UUID id) {
      RoadData data = RoadData.get(player.serverLevel());
      CompoundTag saved = data.interchanges.get(id);
      if (saved == null) {
         throw new IllegalArgumentException("高架连接已删除");
      } else {
         CompoundTag t = payload(
            player.serverLevel(),
            player,
            saved.getUUID("Main"),
            saved.getUUID("Point"),
            BlockPos.of(saved.getLong("Endpoint")),
            saved.getBoolean("MainFirst"),
            id
         );
         previewConnector(t, data.index.roads.get(id).record);
         RoadNetwork.open(player, t);
      }
   }

   private static void previewConnector(CompoundTag t, RoadRecord connector) {
      ListTag records = new ListTag();
      records.add(connector.save());
      t.put("PlannerPreview", records);
   }

   private static CompoundTag selection(CompoundTag t) {
      CompoundTag key = new CompoundTag();

      for (String field : List.of("Main", "Point", "Endpoint", "MainFirst", "Dimension")) {
         if (t.contains(field)) {
            key.put(field, t.get(field).copy());
         }
      }

      return key;
   }

   private static CompoundTag key(CompoundTag t) {
      CompoundTag key = selection(t);

      for (String field : List.of("Id", "Settings", "Signature", "TargetSignature")) {
         if (t.contains(field)) {
            key.put(field, t.get(field).copy());
         }
      }

      return key;
   }

   public static void requireSelection(ServerPlayer player, ItemStack tool, CompoundTag t) {
      if (!t.hasUUID("Id") && !ItemNBT.get(tool).getCompound("ElevatedSelection").equals(selection(t))) {
         throw new IllegalArgumentException("高架选点已改变，请重新选择");
      }
   }

   public static CompoundTag preview(ServerLevel level, ServerPlayer player, CompoundTag command) {
      CompoundTag var37;
      try (RoadWorkChunks chunks = RoadWorkChunks.open(level)) {
         RoadData data = RoadData.get(level);
         UUID edited = command.hasUUID("Id") ? command.getUUID("Id") : null;
         CompoundTag fresh = payload(
            level, player, command.getUUID("Main"), command.getUUID("Point"), BlockPos.of(command.getLong("Endpoint")), command.getBoolean("MainFirst"), edited
         );
         if (!fresh.getString("Dimension").equals(command.getString("Dimension"))
            || fresh.getInt("Signature") != command.getInt("Signature")
            || fresh.getInt("TargetSignature") != command.getInt("TargetSignature")) {
            throw new IllegalArgumentException("道路或端点已改变，请重新打开高架连接设置");
         }

         RoadIndex.Built host = host(data, command.getUUID("Main"));
         RoadAttachments.Point marker = point(host.record, command.getUUID("Point"));
         if (LaneTopology.metadata(host.record).access().stream().anyMatch(ax -> ax.point().equals(marker.id()) && !ax.connection().equals(edited))) {
            throw new IllegalArgumentException("该高架出入点已连接道路，请编辑已有连接");
         }

         RoadGeometry.Settings requested = RoadRecord.readSettings(command.getCompound("Settings"));
         RoadGeometry.Settings inherited = RoadRecord.readSettings(fresh.getCompound("Settings"));
         RoadLanes.Counts selected = RoadLanes.counts(requested);
         if (!selected.equals(RoadLanes.counts(inherited))) {
            throw new IllegalArgumentException("已有高架尽头须保持其实际各方向车道数");
         }

         if (selected.forward() > fresh.getInt("MaxForward") || selected.reverse() > fresh.getInt("MaxReverse")) {
            throw new IllegalArgumentException("高架车道数不能超过下方道路对应方向");
         }

         RoadGeometry.Settings clean = inherited.options(
            inherited.options().attachments(RoadAttachments.Data.EMPTY).lanePoints(LanePoints.Data.EMPTY).route(RoadProfile.Routing.DEFAULT.fit(false))
         );
         BlockPos end = BlockPos.of(command.getLong("Endpoint"));
         RoadGeometry.Node node = RoadData.requireNode(level, end, player).node();
         List<RoadIndex.Built> links = endpoint(data, end, edited);
         RoadIndex.Built road = links.get(0);
         boolean first = road.record.a().equals(end);
         RoadGeometry.Mesh geometry = road.record.caps(0).mesh();
         RoadGeometry.Sample s = first ? geometry.first() : geometry.last();
         RoadGeometry.V direction = s.left().left().mul(first ? -1.0 : 1.0);
         RoadGeometry.Node n = first ? road.record.start() : road.record.end();
         node = new RoadGeometry.Node(s.center(), RoadPlanner.yaw(direction), n.grade() * (double)(first ? 1 : -1));
         UUID id = edited == null ? UUID.randomUUID() : edited;
         RoadRecord original = without(host.record, id);
         ElevatedAccessChain.Result result = ElevatedAccessChain.plan(data, original, marker, id, node, clean, fresh.getBoolean("MainFirst"));
         ElevatedAccess.Plan plan = result.plan();
         LinkedHashSet<UUID> affected = new LinkedHashSet<>(result.reservations().keySet());
         if (edited != null) {
            affected.addAll(members(data.interchanges.get(edited)));
         }

         ArrayList<RoadIndex.Built> changes = new ArrayList<>();
         LanePoints.Opening opening = new LanePoints.Opening(
            id,
            plan.road().samples().stream().map(RoadGeometry.Sample::center).toList(),
            plan.road().samples().stream().mapToDouble(RoadGeometry.Sample::halfWidth).max().orElseThrow()
         );

         for (UUID member : affected) {
            RoadRecord roadx = without(host(data, member).record, id);
            if (player != null) {
               RoadData.requireOwner(player, roadx.owner());
            }

            ElevatedAccess.Reservation reservation = result.reservations().get(member);
            if (reservation != null) {
               LanePoints.Data md = LaneTopology.metadata(roadx);
               ArrayList<ElevatedAccess.Reservation> access = new ArrayList<>(md.access());
               access.add(reservation);
               ArrayList<LaneSections.Cut> cuts = new ArrayList<>(md.cuts());
               cuts.addAll(reservation.cuts());
               ArrayList<LaneAdditions.Addition> additions = new ArrayList<>(md.additions());
               additions.addAll(reservation.additions());
               ArrayList<LanePoints.Opening> openings = new ArrayList<>(md.openings());
               openings.add(opening);
               roadx = roadx.withLanePoints(md.access(access).cuts(cuts).additions(additions).openings(openings));
            }

            changes.add(new RoadIndex.Built(roadx.structures(List.of())));
         }

         RoadGeometry.Mesh path = plan.road();
         RoadGeometry.Node a = RoadRibbon.start(path);
         RoadGeometry.Node b = RoadRibbon.end(path);
         BlockPos pa = BlockPos.containing(a.position().x(), a.position().y(), a.position().z());
         BlockPos pb = BlockPos.containing(b.position().x(), b.position().y(), b.position().z());
         if (fresh.getBoolean("MainFirst")) {
            b = new RoadGeometry.Node(b.position(), b.yaw(), node.grade() * (double)(b.direction().dot(node.direction()) < 0.0 ? -1 : 1));
         } else {
            a = new RoadGeometry.Node(a.position(), a.yaw(), node.grade() * (double)(a.direction().dot(node.direction()) < 0.0 ? -1 : 1));
         }

         RoadRecord connector = new RoadRecord(
               id, host.record.owner(), fresh.getBoolean("MainFirst") ? pa : end, fresh.getBoolean("MainFirst") ? end : pb, a, b, path.settings(), false, 4
            )
            .alignment(id, path);
         HashSet<BlockPos> nodes = new HashSet<>();
         nodes.add(end);

         for (RoadIndex.Built changed : changes) {
            Interchanges.checkExternal(connector.mesh(), changed.mesh, null);
            nodes.add(changed.record.a());
            nodes.add(changed.record.b());
         }

         HashSet<UUID> removed = new HashSet<>(affected);
         if (edited != null) {
            removed.add(edited);
         }

         UUID token = UUID.randomUUID();
         changes.add(new RoadIndex.Built(connector));
         List<RoadIndex.Built> prepared = data.prepareAssembly(level, player, changes, removed, nodes, List.of(), token, Set.of(connector.id()), true);
         CompoundTag receipt = selection(fresh);
         receipt.putUUID("Id", id);
         receipt.putBoolean("ElevatedAccess", true);
         receipt.putUUID("Owner", host.record.owner());
         receipt.put("Settings", RoadRecord.writeSettings(clean));
         ListTag hosts = new ListTag();

         for (UUID member : result.reservations().keySet()) {
            hosts.add(NbtUtils.createUUID(member));
         }

         receipt.put("Hosts", hosts);
         Map<UUID, ElevatedAccessRoads.Approval> table = APPROVALS.computeIfAbsent(data, d -> new LinkedHashMap<>());
         table.entrySet()
            .removeIf(
               e -> System.nanoTime() - e.getValue().created() > 600000000000L
                     || Objects.equals(e.getValue().owner(), player == null ? null : player.getUUID())
            );
         table.put(token, new ElevatedAccessRoads.Approval(player == null ? null : player.getUUID(), key(command), receipt, System.nanoTime()));

         while (table.size() > 4) {
            table.remove(table.keySet().iterator().next());
         }

         CompoundTag reply = command.copy();
         reply.remove("Action");
         reply.putString("Kind", "elevatedAccess");
         reply.putUUID("Token", token);
         previewConnector(reply, prepared.stream().filter(p -> p.record.id().equals(id)).findFirst().orElseThrow().record);
         RoadActionContext.echo(command, reply);
         var37 = reply;
      }

      return var37;
   }

   public static String build(ServerLevel level, ServerPlayer player, CompoundTag t) {
      RoadData data = RoadData.get(level);
      Map<UUID, ElevatedAccessRoads.Approval> table = APPROVALS.get(data);
      ElevatedAccessRoads.Approval approval = table != null && t.hasUUID("Token") ? table.remove(t.getUUID("Token")) : null;
      if (approval != null
         && Objects.equals(approval.owner(), player == null ? null : player.getUUID())
         && System.nanoTime() - approval.created() <= 600000000000L
         && approval.key().equals(key(t))) {
         data.buildPrepared(level, player, t.getUUID("Token"), () -> data.interchanges.put(approval.receipt().getUUID("Id"), approval.receipt().copy()));
         return "高架上下坡连接已建造，地面车道和直行辅路已同步更新";
      } else {
         throw new IllegalArgumentException("高架连接预览已失效，请重新计算预览");
      }
   }

   static void remove(ServerLevel level, ServerPlayer player, UUID id) {
      RoadData data = RoadData.get(level);
      CompoundTag saved = data.interchanges.get(id);
      if (saved != null) {
         ArrayList<RoadIndex.Built> replacements = new ArrayList<>();
         HashSet<UUID> removed = new HashSet<>();
         removed.add(id);
         HashSet<BlockPos> nodes = new HashSet<>();

         for (UUID member : members(saved)) {
            RoadIndex.Built main = data.index.roads.get(member);
            if (main != null) {
               replacements.add(new RoadIndex.Built(without(main.record, id).structures(List.of())));
               removed.add(member);
               nodes.add(main.record.a());
               nodes.add(main.record.b());
            }
         }

         nodes.add(BlockPos.of(saved.getLong("Endpoint")));
         data.replaceAssembly(level, player, replacements, removed, nodes);
         data.interchanges.remove(id);
         data.setDirty();
      }
   }

   private static Set<UUID> members(CompoundTag saved) {
      LinkedHashSet<UUID> ids = new LinkedHashSet<>();
      ids.add(saved.getUUID("Main"));

      for (Tag t : saved.getList("Hosts", 11)) {
         ids.add(NbtUtils.loadUUID(t));
      }

      return ids;
   }

   private static RoadRecord without(RoadRecord road, UUID id) {
      LanePoints.Data md = LaneTopology.metadata(road);
      return road.withLanePoints(
         md.access(md.access().stream().filter(a -> !a.connection().equals(id)).toList())
            .cuts(md.cuts().stream().filter(c -> !c.connection().equals(id)).toList())
            .additions(md.additions().stream().filter(a -> !a.connection().equals(id)).toList())
            .openings(md.openings().stream().filter(o -> !o.connection().equals(id)).toList())
      );
   }

   private ElevatedAccessRoads() {
   }

   private static record Approval(UUID owner, CompoundTag key, CompoundTag receipt, long created) {
   }
}
