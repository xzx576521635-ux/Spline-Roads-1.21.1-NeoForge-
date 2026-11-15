package com.sora.splineroads.world;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class Junctions {
   public static CompoundTag selectedPayload(ServerLevel level, ServerPlayer player, RoadRecord road) {
      CompoundTag t = payload(level, player, new long[0], road.assembly());
      int arm = road.junction().get().arm();
      if (arm >= 0) {
         t.putInt("SelectedArm", arm);
      }

      return t;
   }

   public static CompoundTag payload(ServerLevel level, ServerPlayer player, long[] points, UUID id) {
      RoadData data = RoadData.get(level);
      if (OneSidedJunctions.saved(data, id)) {
         return OneSidedJunctions.payload(level, player, id);
      } else if (id != null && data.junctions.containsKey(id) && data.junctions.get(id).getBoolean("Auto")) {
         return AutoJunctions.payload(level, player, id);
      } else {
         CompoundTag t = new CompoundTag();
         if (id != null) {
            CompoundTag saved = data.junctions.get(id);
            if (saved == null) {
               throw new IllegalArgumentException("路口已不存在");
            }

            t = saved.copy();
            points = t.getLongArray("Points");
            if (player != null) {
               RoadData.requireOwner(player, t.getUUID("Owner"));
            }
         }

         if (t.hasUUID("Interchange")) {
            LaneDeletes.junctionPayload(data, id, t);
            return t;
         } else if (points.length >= 4 && points.length <= 7 && Arrays.stream(points).distinct().count() == (long)points.length) {
            ArrayList<RoadGeometry.Node> nodes = new ArrayList<>();

            for (long point : points) {
               nodes.add(RoadData.requireNode(level, BlockPos.of(point), player).constructionNode());
            }

            ArrayList<JunctionSpec.Arm> arms = new ArrayList<>();
            JunctionSpec old = id == null ? null : JunctionCodec.read(t.getCompound("Spec"));

            for (int i = 1; i < points.length; i++) {
               BlockPos p = BlockPos.of(points[i]);
               BlockPos center = BlockPos.of(points[0]);
               List<RoadIndex.Built> links = data.index
                  .atNode(p)
                  .stream()
                  .map(data.index.roads::get)
                  .filter(r -> !Objects.equals(id, r.record.assembly()) || id == null)
                  .filter(r -> !r.record.a().equals(center) && !r.record.b().equals(center))
                  .toList();
               if (links.size() > 1) {
                  throw new IllegalArgumentException("接入口 " + i + " 连接多条外部道路，请选普通道路的端点");
               }

               RoadGeometry.Node node = nodes.get(i);
               RoadGeometry.V inward = nodes.get(0).position().sub(node.position()).horizontalUnit();
               RoadGeometry.Settings profile = old == null ? RoadGeometry.Settings.defaults() : old.arms().get(i - 1).external();
               boolean oneIn = true;
               if (!links.isEmpty()) {
                  RoadIndex.Built link = links.get(0);
                  if (link.record.junction() != null
                     || RoadProfile.catalog(link.record.settings().style()).type() != RoadProfile.Type.ORDINARY
                        && LaneTopology.metadata(link.record).link() == null) {
                     throw new IllegalArgumentException("路口仅连接普通道路；高速道路请使用立交");
                  }

                  boolean first = link.record.a().equals(p);
                  RoadGeometry.Sample sample = first ? link.mesh.first() : link.mesh.last();
                  RoadGeometry.V forward = sample.left().left().mul(-1.0);
                  inward = forward.mul(first ? -1.0 : 1.0);
                  profile = link.record.settings().taper(sample.halfWidth() * 2.0, sample.halfWidth() * 2.0);
                  profile = new RoadGeometry.Settings(
                        RoadGeometry.Mode.STRAIGHT,
                        LaneTopology.metadata(link.record).link() != null ? RoadGeometry.Style.O1_ONE : profile.style(),
                        sample.halfWidth() * 2.0,
                        profile.thickness(),
                        0.35,
                        90.0
                     )
                     .options(profile.options());
                  node = new RoadGeometry.Node(
                     (first ? link.record.start() : link.record.end()).position(),
                     Math.toDegrees(Math.atan2(-inward.x(), inward.z())),
                     (double)(first ? -1 : 1) * (first ? link.record.start().grade() : link.record.end().grade())
                  );
                  oneIn = !first;
               } else {
                  node = new RoadGeometry.Node(node.position(), Math.toDegrees(Math.atan2(-inward.x(), inward.z())), 0.0);
               }

               if (old != null) {
                  RoadProfile.Options previous = old.arms().get(i - 1).external().options();
                  profile = profile.options(previous);
               }

               arms.add(
                  (old == null ? JunctionSpec.arm(node, inward, profile, oneIn, i - 1) : old.arms().get(i - 1).node(node, inward, profile))
                     .attached(!links.isEmpty())
               );
            }

            JunctionSpec spec = old == null
               ? new JunctionSpec(
                  nodes.get(0).position(),
                  JunctionSpec.Kind.INTERSECTION,
                  false,
                  6.0,
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
               )
               : new JunctionSpec(
                  nodes.get(0).position(),
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
                  arms,
                  old.parallelApproaches()
               );
            t.putString("Kind", "junction");
            t.putLongArray("Points", points);
            t.put("Spec", JunctionCodec.write(spec));
            if (id != null) {
               t.putUUID("Id", id);
               LaneDeletes.junctionPayload(data, id, t);
            }

            return t;
         } else {
            throw new IllegalArgumentException("先选中心，再选 3–6 个不同接入口");
         }
      }
   }

   public static String build(ServerLevel level, ServerPlayer player, CompoundTag command) {
      return buildRoad(level, player, command, -1);
   }

   static String buildRoad(ServerLevel level, ServerPlayer player, CompoundTag command, int roadArm) {
      if (!command.getBoolean("OneSided") && (!command.hasUUID("Id") || !OneSidedJunctions.saved(RoadData.get(level), command.getUUID("Id")))) {
         if (command.hasUUID("Id") && RoadData.get(level).junctions.getOrDefault(command.getUUID("Id"), new CompoundTag()).getBoolean("Auto")) {
            return AutoJunctions.edit(level, player, command);
         } else {
            String var38;
            try (RoadWorkChunks work = RoadWorkChunks.open(level)) {
               RoadData data = RoadData.get(level);
               UUID id = command.hasUUID("Id") ? command.getUUID("Id") : null;
               CompoundTag fresh = payload(level, player, command.getLongArray("Points"), id);
               long[] points = fresh.getLongArray("Points");
               JunctionSpec source = JunctionCodec.read(fresh.getCompound("Spec"));
               JunctionSpec requested = JunctionCodec.read(command.getCompound("Spec"));
               if (!source.center().equals(requested.center()) || source.arms().size() != requested.arms().size()) {
                  throw new IllegalArgumentException("端点选择已变化，请重新打开路口");
               }

               for (int i = 0; i < source.arms().size(); i++) {
                  JunctionSpec.Arm a = source.arms().get(i);
                  JunctionSpec.Arm b = requested.arms().get(i);
                  if (a.attached() != b.attached()
                     || !a.endpoint().equals(b.endpoint())
                     || !a.inward().equals(b.inward())
                     || i != roadArm && !a.external().equals(b.external())) {
                     throw new IllegalArgumentException("接入口道路已变化，请重新打开路口确认预览");
                  }
               }

               JunctionPlanner.Plan plan = JunctionPlanner.plan(requested);
               UUID group = id == null ? UUID.randomUUID() : id;
               UUID owner = id == null ? (player == null ? new UUID(0L, 0L) : player.getUUID()) : fresh.getUUID("Owner");
               Set<UUID> removed = new HashSet<>();
               Set<BlockPos> selected = new HashSet<>();

               for (long point : points) {
                  selected.add(BlockPos.of(point));
               }

               BlockPos center = BlockPos.of(points[0]);

               for (RoadIndex.Built r : data.index.roads.values()) {
                  boolean spoke = !r.record.settings().laneRamp()
                     && r.record.assembly() == null
                     && (r.record.a().equals(center) && selected.contains(r.record.b()) || r.record.b().equals(center) && selected.contains(r.record.a()));
                  if (!group.equals(r.record.assembly()) && !spoke) {
                     if (r.record.a().equals(center) || r.record.b().equals(center)) {
                        throw new IllegalArgumentException("中心仍连接未选中的道路，请将其接入口加入选区");
                     }
                  } else {
                     if (player != null) {
                        RoadData.requireOwner(player, r.record.owner());
                     }

                     removed.add(r.record.id());
                  }
               }

               ArrayList<RoadIndex.Built> built = new ArrayList<>();

               for (int ix = 0; ix < plan.pieces().size(); ix++) {
                  JunctionPlanner.Piece piece = plan.pieces().get(ix);
                  RoadGeometry.Mesh mesh = piece.mesh();
                  int arm = piece.arm();
                  RoadGeometry.Node a = arm >= 0 ? requested.arms().get(arm).endpoint() : new RoadGeometry.Node(mesh.first().center(), 0.0, 0.0);
                  RoadGeometry.Node b = new RoadGeometry.Node(
                     mesh.last().center(), Math.toDegrees(Math.atan2(mesh.last().left().z(), mesh.last().left().x())), 0.0
                  );
                  BlockPos start = arm >= 0 && arm + 1 < points.length ? BlockPos.of(points[arm + 1]) : center;
                  UUID road = UUID.nameUUIDFromBytes((group + ":junction:" + ix).getBytes(StandardCharsets.UTF_8));
                  RoadRecord record = new RoadRecord(road, owner, start, center, a, b, mesh.settings(), false, 4)
                     .junction(group, new JunctionPlanner.Ref(requested, ix));
                  built.add(new RoadIndex.Built(record));
               }

               for (RoadIndex.Built proposed : built) {
                  for (RoadIndex.Built old : data.index.roads.values()) {
                     if (!removed.contains(old.record.id()) && RoadIndex.overlapXZ(proposed.mesh, old.mesh, 1.0)) {
                        Interchanges.checkExternal(
                           proposed.mesh, old.mesh, data.index.roads.containsKey(proposed.record.id()) ? data.index.roads.get(proposed.record.id()).mesh : null
                        );
                     }
                  }
               }

               data.replaceAssembly(level, player, built, removed, selected);
               fresh.putUUID("Id", group);
               fresh.putUUID("Owner", owner);
               fresh.put("Spec", JunctionCodec.write(requested));
               data.junctions.put(group, fresh.copy());
               RampJunctions.sync(data);
               data.setDirty();
               var38 = (requested.kind() == JunctionSpec.Kind.ROUNDABOUT ? "环岛" : "路口")
                  + "已建成："
                  + requested.arms().size()
                  + " 个方向，"
                  + plan.movements().size()
                  + " 条车道通行关系";
            }

            return var38;
         }
      } else {
         return OneSidedJunctions.build(level, player, command);
      }
   }

   public static void remove(ServerLevel level, ServerPlayer player, UUID group) {
      RoadData data = RoadData.get(level);
      if (OneSidedJunctions.saved(data, group)) {
         OneSidedJunctions.remove(level, player, group);
      } else if (data.junctions.getOrDefault(group, new CompoundTag()).getBoolean("Auto")) {
         AutoJunctions.removeCenter(level, player, group);
      } else {
         CompoundTag saved = data.junctions.get(group);
         if (saved == null) {
            throw new IllegalArgumentException("路口已经不存在");
         } else {
            if (player != null) {
               RoadData.requireOwner(player, saved.getUUID("Owner"));
            }

            Set<UUID> removed = new HashSet<>();
            Set<BlockPos> selected = new HashSet<>();
            data.index.roads.values().stream().filter(r -> group.equals(r.record.assembly())).forEach(r -> removed.add(r.record.id()));

            for (long p : saved.getLongArray("Points")) {
               selected.add(BlockPos.of(p));
            }

            data.replaceAssembly(level, player, new ArrayList<>(), removed, selected);
            data.junctions.remove(group);
            if (saved.hasUUID("Interchange")) {
               CompoundTag parent = data.interchanges.get(saved.getUUID("Interchange"));
               if (parent != null) {
                  parent.putBoolean("Partial", true);
               }
            }

            data.setDirty();
         }
      }
   }

   private Junctions() {
   }
}
