package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadEndpointSections;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.TollInlineGeometry;
import com.sora.splineroads.core.TollPlanner;
import com.sora.splineroads.core.TollPlaza;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class TollRoads {
   private static final Map<RoadData, LinkedHashMap<UUID, TollRoads.Approval>> APPROVALS = new WeakHashMap<>();

   static void alignApproachRails(RoadIndex.Built road, List<RoadIndex.Built> nearby, List<RoadStructures.Part> parts) {
      if (road.record.toll() == null) {
         for (boolean first : new boolean[]{true, false}) {
            BlockPos node = first ? road.record.a() : road.record.b();
            if (!nearby.stream().noneMatch(bx -> bx.record.toll() != null && (bx.record.a().equals(node) || bx.record.b().equals(node)))) {
               RoadGeometry.Sample end = first ? road.mesh.first() : road.mesh.last();
               RoadGeometry.V along = end.left().left();

               for (int i = 0; i < parts.size(); i++) {
                  RoadStructures.Part p = parts.get(i);
                  if (!p.pier()
                     && p.model().isEmpty()
                     && p.heights() == null
                     && !(p.width() > 0.7)
                     && !(p.height() > 1.2)
                     && Set.of(RoadStructures.Material.CONCRETE, RoadStructures.Material.STEEL, RoadStructures.Material.DARK_STEEL).contains(p.material())
                     && !(p.a().sub(p.b()).horizontalLength() < 0.1)) {
                     if (p.material() == RoadStructures.Material.DARK_STEEL && p.width() <= 0.2 && p.a().sub(p.b()).horizontalLength() <= 0.2) {
                        RoadGeometry.V outward = along.mul(first ? 1.0 : -1.0);
                        double penetration = p.base().stream().mapToDouble(v -> v.sub(end.center()).dot(outward)).max().orElse(0.0);
                        double center = p.a().add(p.b()).mul(0.5).sub(end.center()).dot(outward);
                        if (center <= 0.08 && center > -0.25 && penetration > 0.0 && penetration <= 0.2) {
                           RoadGeometry.V shift = outward.mul(-penetration - 0.001);
                           parts.set(
                              i,
                              new RoadStructures.Part(
                                 p.a().add(shift), p.b().add(shift), p.width(), p.height(), false, p.material(), p.frameA(), p.frameB(), p.model(), p.heights()
                              )
                           );
                           continue;
                        }
                     }

                     boolean a = Math.abs(p.a().sub(end.center()).dot(along)) < 0.015 && p.a().y() >= end.center().y() - 0.05;
                     boolean b = Math.abs(p.b().sub(end.center()).dot(along)) < 0.015 && p.b().y() >= end.center().y() - 0.05;
                     if (a || b) {
                        RoadGeometry.V frame = end.left().mul(p.width() / 2.0);
                        parts.set(i, p.frames(a ? frame : p.frameA(), b ? frame : p.frameB()));
                     }
                  }
               }
            }
         }
      }
   }

   public static RoadRecord at(RoadData data, BlockPos endpoint) {
      return data.index.atNode(endpoint).stream().map(data.index.roads::get).filter(b -> b.record.toll() != null).map(b -> b.record).findFirst().orElse(null);
   }

   private static BlockPos pos(RoadGeometry.Node n) {
      return BlockPos.containing(n.position().x(), n.position().y(), n.position().z());
   }

   public static CompoundTag payload(ServerLevel level, ServerPlayer player, long[] points, UUID id) {
      RoadData data = RoadData.get(level);
      RoadRecord old = null;
      if (id != null) {
         RoadIndex.Built b = data.index.roads.get(id);
         if (b == null || b.record.toll() == null) {
            throw new IllegalArgumentException("收费站已不存在");
         }

         old = b.record;
         if (player != null) {
            RoadData.requireOwner(player, old.owner());
         }

         points = new long[]{old.a().asLong(), old.b().asLong()};
      }

      if (old != null && old.assembly() != null) {
         CompoundTag group = data.interchanges.get(old.assembly());
         if (group != null && group.getBoolean("IntegratedToll")) {
            return Interchanges.payload(level, player, group.getLongArray("Points"), old.assembly());
         }
      }

      if (points.length == 2 && points[0] != points[1]) {
         NodeEntity a = RoadData.requireNode(level, BlockPos.of(points[0]), player);
         NodeEntity bx = RoadData.requireNode(level, BlockPos.of(points[1]), player);
         RoadGeometry.Node na = a.constructionNode();
         RoadGeometry.Node nb = bx.constructionNode();
         RoadGeometry.V axis = nb.position().sub(na.position()).horizontalUnit();
         double yaw = RoadPlanner.yaw(axis);
         na = endpoint(data, BlockPos.of(points[0]), na, axis, id);
         nb = endpoint(data, BlockPos.of(points[1]), nb, axis, id);
         TollPlaza.Spec spec = new TollPlaza.Spec(
            new RoadGeometry.Node(na.position(), yaw, na.grade()),
            new RoadGeometry.Node(nb.position(), yaw, nb.grade()),
            old == null ? TollPlaza.Config.DEFAULT : old.toll().config(),
            port(data, BlockPos.of(points[0]), axis, id, old == null ? null : old.toll().portA()),
            port(data, BlockPos.of(points[1]), axis, id, old == null ? null : old.toll().portB())
         );
         if (old != null && !old.toll().path().isEmpty()) {
            spec = old.toll();
         }

         CompoundTag t = new CompoundTag();
         t.putString("Kind", "toll");
         t.putString("Dimension", level.dimension().location().toString());
         t.putLongArray("Points", points);
         t.put("Spec", TollCodec.write(spec));
         t.putString("Adjust", "NONE");
         ListTag connections = new ListTag();
         Stream.concat(data.index.atNode(BlockPos.of(points[0])).stream(), data.index.atNode(BlockPos.of(points[1])).stream())
            .distinct()
            .filter(v -> !v.equals(id))
            .sorted()
            .forEach(v -> connections.add(data.index.roads.get(v).record.header()));
         t.put("Connections", connections);
         t.put("SnapshotA", a.saveWithoutMetadata(level.registryAccess()));
         t.put("SnapshotB", bx.saveWithoutMetadata(level.registryAccess()));
         t.putLong("Revision", data.index.revision());
         if (old != null) {
            t.putUUID("Id", id);
            t.putUUID("Owner", old.owner());
         }

         t.putString("SelectionStamp", TollInputs.stamp(t));
         return t;
      } else {
         throw new IllegalArgumentException("请选择两个不同的真实道路端点 A、B");
      }
   }

   private static RoadGeometry.Settings port(RoadData data, BlockPos at, RoadGeometry.V axis, UUID station, RoadGeometry.Settings fallback) {
      Optional<RoadIndex.Built> outside = data.index.atNode(at).stream().filter(id -> !id.equals(station)).map(data.index.roads::get).findFirst();
      if (outside.isEmpty()) {
         return fallback;
      } else {
         RoadRecord r = outside.get().record;
         normal(r, InterchangeConnectors.eligible(data, r));
         RoadGeometry.Mesh mesh = r.mesh();
         boolean first = r.a().equals(at);
         RoadGeometry.V forward = (first ? mesh.first() : mesh.last()).left().left().mul(-1.0);
         return RoadEndpointSections.section(mesh, first, forward.dot(axis) < 0.0);
      }
   }

   private static RoadGeometry.Node endpoint(RoadData data, BlockPos at, RoadGeometry.Node node, RoadGeometry.V axis, UUID station) {
      List<RoadIndex.Built> outside = data.index.atNode(at).stream().filter(id -> !id.equals(station)).map(data.index.roads::get).toList();
      if (outside.size() > 1) {
         throw new IllegalArgumentException("收费站每端最多连接一条站外完整道路");
      } else if (outside.isEmpty()) {
         return new RoadGeometry.Node(node.position(), RoadPlanner.yaw(axis), 0.0);
      } else {
         RoadRecord r = outside.get(0).record;
         normal(r, InterchangeConnectors.eligible(data, r));
         boolean first = r.a().equals(at);
         RoadGeometry.Node n = first ? r.start() : r.end();
         return new RoadGeometry.Node(n.position(), RoadPlanner.yaw(axis), n.grade() * (double)(n.direction().dot(axis) < 0.0 ? -1 : 1));
      }
   }

   private static void normal(RoadRecord r, boolean connector) {
      RoadProfile.Type type = RoadProfile.catalog(r.settings()).type();
      if (r.assembly() != null && !connector
         || r.junction() != null
         || r.settings().style().ramp()
         || LaneTopology.metadata(r).link() != null
         || type != RoadProfile.Type.ORDINARY && type != RoadProfile.Type.HIGHWAY) {
         throw new IllegalArgumentException("收费站 A/B 仅连接站外完整普通道路或高速道路；匝道请接到更远的站外路段");
      }
   }

   private static CompoundTag key(CompoundTag t) {
      CompoundTag k = new CompoundTag();

      for (String name : List.of("Points", "Spec", "Adjust", "SnapshotA", "SnapshotB", "Connections", "Revision", "Id", "Dimension")) {
         if (t.contains(name)) {
            k.put(name, t.get(name).copy());
         }
      }

      return k;
   }

   private static CompoundTag fresh(ServerLevel level, ServerPlayer player, CompoundTag t) {
      if (!level.dimension().location().toString().equals(t.getString("Dimension"))) {
         throw new IllegalArgumentException("收费站选点维度已变化");
      } else {
         CompoundTag fresh = payload(level, player, t.getLongArray("Points"), t.hasUUID("Id") ? t.getUUID("Id") : null);
         TollPlaza.Spec source = TollCodec.read(fresh.getCompound("Spec"));
         TollPlaza.Spec requested = TollCodec.read(t.getCompound("Spec"));
         if (source.a().equals(requested.a())
            && source.b().equals(requested.b())
            && fresh.getCompound("SnapshotA").equals(t.getCompound("SnapshotA"))
            && fresh.getCompound("SnapshotB").equals(t.getCompound("SnapshotB"))
            && fresh.getList("Connections", 10).equals(t.getList("Connections", 10))
            && fresh.getLong("Revision") == t.getLong("Revision")) {
            return fresh;
         } else {
            throw new IllegalArgumentException("端点或连接道路已变化，请重新打开收费站并预览");
         }
      }
   }

   public static CompoundTag preview(ServerLevel level, ServerPlayer player, CompoundTag command) {
      if (command.hasUUID("InlineRoad")) {
         return InlineTollRoads.preview(level, player, command);
      } else {
         command = TollInputs.expand(level, player, command);
         TollPlaza.Spec source = TollCodec.read(fresh(level, player, command).getCompound("Spec"));
         RoadData data = RoadData.get(level);
         TollPlaza.Spec requested = TollCodec.read(command.getCompound("Spec"));
         TollPlaza.Spec original = new TollPlaza.Spec(requested.a(), requested.b(), requested.config(), source.portA(), source.portB(), source.path());
         long[] points = command.getLongArray("Points");
         BlockPos oldA = BlockPos.of(points[0]);
         BlockPos oldB = BlockPos.of(points[1]);
         UUID stationId = command.hasUUID("Id") ? command.getUUID("Id") : null;
         TollPlaza.Adjust adjust = TollPlaza.Adjust.valueOf(command.getString("Adjust"));
         TollPlaza.Spec aligned = alignFreePort(data, original, adjust, oldA, oldB, stationId);
         TollPlanner.Fit lengthFit = TollPlanner.fit(aligned, adjust);
         TollPlaza.Spec spec = lengthFit.fitted();
         TollPlanner.Fit fit = new TollPlanner.Fit(
            original,
            spec,
            TollPlanner.lengths(spec),
            original.a().position().distance(spec.a().position()),
            original.b().position().distance(spec.b().position())
         );
         TollPlanner.Plan plan = TollPlanner.plan(spec);
         UUID id = command.hasUUID("Id") ? command.getUUID("Id") : UUID.randomUUID();
         UUID owner = command.hasUUID("Id") ? data.index.roads.get(id).record.owner() : (player == null ? new UUID(0L, 0L) : player.getUUID());
         ArrayList<RoadIndex.Built> built = new ArrayList<>();
         HashSet<UUID> removed = new HashSet<>();
         if (command.hasUUID("Id")) {
            removed.add(id);
         }

         ArrayList<RoadData.NodeMove> moves = new ArrayList<>();
         HashSet<BlockPos> selected = new HashSet<>(List.of(oldA, oldB));

         for (boolean a : new boolean[]{true, false}) {
            BlockPos at = a ? oldA : oldB;
            RoadGeometry.Node target = a ? spec.a() : spec.b();
            List<RoadIndex.Built> outside = data.index.atNode(at).stream().filter(v -> !v.equals(id)).map(data.index.roads::get).toList();
            if (outside.size() > 1) {
               throw new IllegalArgumentException("收费站每端只能外接一条完整道路");
            }

            for (RoadIndex.Built other : outside) {
               checkPort(spec, a, other.record, at, InterchangeConnectors.eligible(data, other.record));
               stage(data, level, player, at, target, other.record, built, removed);
            }

            RoadGeometry.Node old = a ? original.a() : original.b();
            if (old.position().distance(target.position()) > 1.0E-6) {
               NodeEntity marker = RoadData.requireNode(level, at, player);
               BlockPos to = pos(target);
               selected.add(to);
               moves.add(new RoadData.NodeMove(at, to, target, marker.saveWithoutMetadata(level.registryAccess())));
            }
         }

         BlockPos a = fit.movedA() > 1.0E-6 ? pos(spec.a()) : oldA;
         BlockPos b = fit.movedB() > 1.0E-6 ? pos(spec.b()) : oldB;
         RoadRecord record = new RoadRecord(id, owner, a, b, spec.a(), spec.b(), plan.mesh().settings(), false, 4).toll(spec);
         RoadIndex.Built previous = data.index.roads.get(id);
         if (previous != null && InterchangeConnectors.eligible(data, previous.record)) {
            record = InterchangeConnectors.group(record, previous.record.assembly());
         }

         built.add(new RoadIndex.Built(record));
         UUID token = UUID.randomUUID();

         List<RoadIndex.Built> prepared;
         try (RoadWorkChunks chunks = RoadWorkChunks.open(level)) {
            prepared = data.prepareAssembly(level, player, built, removed, selected, moves, token);
         }

         LinkedHashMap<UUID, TollRoads.Approval> approval = APPROVALS.computeIfAbsent(data, d -> new LinkedHashMap<>());
         approval.entrySet()
            .removeIf(
               e -> System.nanoTime() - e.getValue().created() > 600000000000L
                     || Objects.equals(e.getValue().owner(), player == null ? null : player.getUUID())
            );
         approval.put(token, new TollRoads.Approval(key(command), player == null ? null : player.getUUID(), System.nanoTime()));

         while (approval.size() > 4) {
            approval.remove(approval.keySet().iterator().next());
         }

         CompoundTag reply = new CompoundTag();
         reply.putUUID("Token", token);
         reply.put("Spec", TollCodec.write(spec));
         reply.putDouble("MovedA", fit.movedA());
         reply.putDouble("MovedB", fit.movedB());
         reply.putInt("Affected", built.size() - 1);
         ListTag roads = new ListTag();

         for (RoadIndex.Built road : prepared) {
            roads.add(road.record.save());
         }

         reply.put("Roads", roads);
         return reply;
      }
   }

   public static String build(ServerLevel level, ServerPlayer player, CompoundTag t) {
      if (t.hasUUID("InlineRoad")) {
         return InlineTollRoads.build(level, player, t);
      } else {
         t = TollInputs.expand(level, player, t);
         RoadData data = RoadData.get(level);
         if (!t.hasUUID("Token")) {
            throw new IllegalArgumentException("请先完成收费站预览校验");
         } else {
            UUID token = t.getUUID("Token");
            TollRoads.Approval approval = APPROVALS.getOrDefault(data, new LinkedHashMap<>()).get(token);
            if (approval != null && approval.key().equals(key(t)) && Objects.equals(approval.owner(), player == null ? null : player.getUUID())) {
               try {
                  fresh(level, player, t);
                  data.buildPrepared(level, player, token);
               } finally {
                  APPROVALS.get(data).remove(token);
                  data.discardPreviews(player == null ? null : player.getUUID());
               }

               return "收费站已建成；A/B 可外接完整普通道路或高速道路";
            } else {
               throw new IllegalArgumentException("收费站参数已改变，请重新预览");
            }
         }
      }
   }

   private static void stage(
      RoadData data,
      ServerLevel level,
      ServerPlayer player,
      BlockPos at,
      RoadGeometry.Node target,
      RoadRecord r,
      List<RoadIndex.Built> built,
      Set<UUID> removed
   ) {
      boolean first = r.a().equals(at);
      RoadGeometry.Node old = first ? r.start() : r.end();
      if (!(old.position().distance(target.position()) < 1.0E-6)) {
         if (player != null) {
            RoadData.requireOwner(player, r.owner());
         }

         RoadGeometry.V axis = r.end().position().sub(r.start().position()).horizontalUnit();
         RoadGeometry.V delta = target.position().sub(old.position());
         RoadGeometry.Node far = first ? r.end() : r.start();
         if ((r.settings().mode() == RoadGeometry.Mode.STRAIGHT || r.settings().mode() == RoadGeometry.Mode.AUTO)
            && r.alignment().isEmpty()
            && !(Math.abs(delta.dot(axis.left())) > 1.0E-5)
            && !(Math.abs(delta.y()) > 1.0E-5)
            && !(Math.abs(old.grade()) > 1.0E-6)
            && !(Math.abs(far.grade()) > 1.0E-6)
            && !(Math.abs(old.direction().dot(axis)) < 0.99999999)
            && !(Math.abs(far.direction().dot(axis)) < 0.99999999)) {
            if (target.position().sub(far.position()).dot(old.position().sub(far.position())) <= 0.0 || target.position().distance(far.position()) < 4.0) {
               throw new IllegalArgumentException("自动调整将越过站外端点，或使外接道路不足 4 格");
            } else if (r.settings().options().attachments().points().isEmpty()
               && !LaneTopology.metadata(r).points().stream().anyMatch(p -> !p.automatic())
               && LaneTopology.dependents(data, Set.of(r.id())).isEmpty()) {
               RoadGeometry.Node a = first ? new RoadGeometry.Node(target.position(), r.start().yaw(), r.start().grade()) : r.start();
               RoadGeometry.Node b = first ? r.end() : new RoadGeometry.Node(target.position(), r.end().yaw(), r.end().grade());
               built.add(
                  new RoadIndex.Built(
                     new RoadRecord(
                        r.id(),
                        r.owner(),
                        first ? pos(target) : r.a(),
                        first ? r.b() : pos(target),
                        a,
                        b,
                        r.settings(),
                        r.automatic(),
                        r.clearance(),
                        List.of(),
                        r.endCaps(),
                        r.buildVersion(),
                        null,
                        List.of(),
                        r.furniturePhase()
                     )
                  )
               );
               removed.add(r.id());
            } else {
               throw new IllegalArgumentException("外接道路含附属点、手动车道点或匝道，当前调整不能保证原连接；请先处理该路段，原站与两端均保持不变");
            }
         } else {
            throw new IllegalArgumentException("自动移动不能完整保留这条曲线/坡道，请先调整站外道路；两个端点均未修改");
         }
      }
   }

   static TollPlaza.Spec alignFreePort(RoadData data, TollPlaza.Spec spec, TollPlaza.Adjust adjust, BlockPos a, BlockPos b, UUID station) {
      if (adjust != TollPlaza.Adjust.A && adjust != TollPlaza.Adjust.B) {
         return spec;
      } else {
         boolean fixedA = adjust == TollPlaza.Adjust.B;
         BlockPos fixed = fixedA ? a : b;
         BlockPos free = fixedA ? b : a;
         List<RoadIndex.Built> fixedRoads = data.index.atNode(fixed).stream().filter(id -> !id.equals(station)).map(data.index.roads::get).toList();
         if (fixedRoads.size() == 1 && !data.index.atNode(free).stream().anyMatch(id -> !id.equals(station))) {
            RoadIndex.Built road = fixedRoads.get(0);
            normal(road.record, InterchangeConnectors.eligible(data, road.record));
            boolean first = road.record.a().equals(fixed);
            RoadGeometry.V away = (first ? road.mesh.first() : road.mesh.last()).left().left().mul(first ? -1.0 : 1.0);
            return TollPlanner.alignFreePort(spec, adjust, away);
         } else {
            return spec;
         }
      }
   }

   private static void checkPort(TollPlaza.Spec s, boolean a, RoadRecord r, BlockPos at, boolean connector) {
      normal(r, connector);
      boolean first = r.a().equals(at);
      RoadGeometry.V axis = TollInlineGeometry.direction(s, a);
      if (first) {
         r.start();
      } else {
         r.end();
      }

      RoadGeometry.Mesh mesh = r.mesh();
      RoadGeometry.Sample sample = first ? mesh.first() : mesh.last();
      RoadGeometry.V forward = sample.left().left().mul(-1.0);
      RoadGeometry.V away = forward.mul(first ? 1.0 : -1.0);
      RoadGeometry.V outside = axis.mul(a ? -1.0 : 1.0);
      double alignment = away.dot(outside);
      if (alignment <= 0.0) {
         throw new IllegalArgumentException((a ? "A" : "B") + " 端外接道路朝向站内；请把收费站放在道路端点外侧");
      } else if (alignment < 0.99999999) {
         throw new IllegalArgumentException((a ? "A" : "B") + " 端外接道路朝外但未与收费站轴线对齐；可选择仅移动 " + (a ? "B" : "A") + "（该端须未接路），或手动对齐端点");
      } else {
         RoadGeometry.Settings expected = TollPlaza.port(s, a);
         RoadGeometry.Settings nominal = TollPlaza.port(s.config(), a);
         RoadGeometry.Settings actual = RoadEndpointSections.section(mesh, first, forward.dot(axis) < 0.0);
         if (RoadLanes.counts(nominal).equals(RoadLanes.counts(actual))
            && !(Math.abs(RoadProfile.layout(actual, actual.width()).laneWidth() - s.config().laneWidth()) > 1.0E-5)
            && !(Math.abs(expected.width() - actual.width()) > 1.0E-5)
            && !(Math.abs((s.path().isEmpty() ? 1.0 : expected.thickness()) - actual.thickness()) > 1.0E-5)) {
            RoadProfile.Layout e = RoadProfile.layout(expected, expected.width());
            RoadProfile.Layout v = RoadProfile.layout(actual, actual.width());
            if (Math.abs(e.motorMin() - v.motorMin()) > 1.0E-5
               || Math.abs(e.motorMax() - v.motorMax()) > 1.0E-5
               || Math.abs(e.medianCenter() - v.medianCenter()) > 1.0E-5
               || s.config().leftTraffic() != actual.options().leftTraffic()) {
               throw new IllegalArgumentException("收费站接口与外接道路各向车道位置不一致");
            } else if (!s.config().twoWay() && forward.dot(axis) * (double)(s.config().reverse() ? -1 : 1) < 0.99999999) {
               throw new IllegalArgumentException("外接单向道路行驶方向与收费站相反");
            }
         } else {
            throw new IllegalArgumentException((a ? "A" : "B") + " 端外接道路断面不匹配；请先修改外接道路或恢复对应车道数/宽度");
         }
      }
   }

   static void validateConnections(List<RoadIndex.Built> all) {
      for (RoadIndex.Built station : all) {
         if (station.record.toll() != null) {
            TollPlaza.Spec s = station.record.toll();

            for (boolean a : new boolean[]{true, false}) {
               BlockPos at = a ? station.record.a() : station.record.b();
               List<RoadIndex.Built> links = all.stream()
                  .filter(rx -> !rx.record.id().equals(station.record.id()) && (rx.record.a().equals(at) || rx.record.b().equals(at)))
                  .toList();
               if (links.size() > 1) {
                  throw new IllegalArgumentException("收费站 A/B 每端最多连接一条站外完整道路");
               }

               for (RoadIndex.Built r : links) {
                  checkPort(
                     s,
                     a,
                     r.record,
                     at,
                     station.record.assembly() != null
                        && !station.record.assembly().equals(station.record.id())
                        && station.record.assembly().equals(r.record.assembly())
                  );
               }
            }

            RoadClearance.Prepared prepared = RoadClearance.prepare(station.mesh);

            for (RoadIndex.Built other : all) {
               if (!other.record.id().equals(station.record.id()) && RoadIndex.overlapXZ(station.mesh, other.mesh, 2.0)) {
                  for (RoadClearance.Contact contact : RoadClearance.contacts(prepared, other.mesh)) {
                     if (contact.usableClearance() < s.config().clearHeight() + 1.5 - 0.025) {
                        throw new IllegalArgumentException("道路/匝道的实际路面或分合流过渡侵入收费站，请将连接点移远");
                     }
                  }

                  for (RoadStructures.Part part : other.record.structures()) {
                     if (RoadClearance.structureInvades(part, station.mesh, s.config().clearHeight())) {
                        throw new IllegalArgumentException("站外道路设施侵入收费站净空");
                     }
                  }

                  for (RoadStructures.Part partx : station.record.structures()) {
                     if (RoadClearance.structureInvades(partx, other.mesh, Math.max(4.25, RoadInfrastructure.clearance(other.record.settings())))) {
                        throw new IllegalArgumentException("收费站设施与已有道路通行空间冲突");
                     }
                  }
               }
            }
         }
      }
   }

   public static void remove(ServerLevel level, ServerPlayer player, UUID id) {
      RoadData data = RoadData.get(level);
      RoadIndex.Built b = data.index.roads.get(id);
      if (b != null && b.record.toll() != null) {
         if (player != null) {
            RoadData.requireOwner(player, b.record.owner());
         }

         CompoundTag group = b.record.assembly() == null ? null : data.interchanges.get(b.record.assembly());
         if (group != null && group.getBoolean("IntegratedToll")) {
            throw new IllegalArgumentException("请在双喇叭配置中关闭收费站并整体更新，以同步恢复连接段");
         } else {
            List<RoadIndex.Built> restored = b.record.toll().path().isEmpty()
               ? List.of()
               : List.of(new RoadIndex.Built(InterchangeConnectors.restored(data, b.record)));
            data.replaceAssembly(level, player, restored, Set.of(id), Set.of(b.record.a(), b.record.b()));
         }
      } else {
         throw new IllegalArgumentException("收费站已不存在");
      }
   }

   public static boolean reverseConnection(RoadData data, BlockPos a, BlockPos b) {
      Boolean reverse = null;

      for (BlockPos p : List.of(a, b)) {
         RoadRecord r = at(data, p);
         if (r != null && !r.toll().config().twoWay()) {
            boolean stationA = r.a().equals(p);
            boolean shouldStart = stationA == r.toll().config().reverse();
            boolean wrong = p.equals(a) != shouldStart;
            if (reverse != null && reverse != wrong) {
               throw new IllegalArgumentException("两个收费站接口行驶方向不兼容");
            }

            reverse = wrong;
         }
      }

      return Boolean.TRUE.equals(reverse);
   }

   private TollRoads() {
   }

   private static record Approval(CompoundTag key, UUID owner, long created) {
   }
}
