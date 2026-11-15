package com.sora.splineroads.world;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.OneSidedJunction;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadEndpointSections;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.core.RoadTransitions;
import com.sora.splineroads.net.RoadNetwork;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class OneSidedJunctions {
   static RoadGeometry.Settings preference(CompoundTag tool) {
      RoadGeometry.Settings s = tool.contains("Settings")
         ? RoadRecord.readSettings(tool.getCompound("Settings"))
         : new RoadGeometry.Settings(RoadGeometry.Mode.STRAIGHT, RoadGeometry.Style.O2_YELLOW, 9.0, 1.0, 0.35, 90.0);
      return s.options(s.options().lanePoints(LanePoints.Data.EMPTY).attachments(RoadAttachments.Data.EMPTY).ends(RoadTransitions.Ends.NONE));
   }

   static boolean selectedEndpoint(ServerPlayer player, CompoundTag tool, BlockPos endpoint) {
      if (!tool.hasUUID("SideMain")) {
         return false;
      } else if (!tool.getString("Dimension").equals(player.serverLevel().dimension().location().toString())) {
         tool.remove("ElevatedSelection");
         tool.remove("SideSelection");
         tool.remove("SideMain");
         tool.remove("SidePoint");
         return false;
      } else {
         RoadIndex.Built host = RoadData.get(player.serverLevel()).index.roads.get(tool.getUUID("SideMain"));
         if (host != null
            && host.record.settings().options().attachments().points().stream().anyMatch(p -> p.id().equals(tool.getUUID("SidePoint")) && p.elevated())) {
            ElevatedAccessRoads.open(
               player, tool, ElevatedAccessRoads.payload(player.serverLevel(), player, host.record.id(), tool.getUUID("SidePoint"), endpoint, true, null)
            );
            return true;
         } else {
            openSelection(
               player, tool, create(player.serverLevel(), player, tool.getUUID("SideMain"), tool.getUUID("SidePoint"), endpoint, true, preference(tool))
            );
            return true;
         }
      }
   }

   static void selectedAttached(ServerPlayer player, CompoundTag tool, RoadIndex.Built road, RoadAttachments.Point point) {
      if (point.elevated()) {
         ElevatedAccessRoads.select(player, tool, road, point);
      } else {
         RoadData.requireOwner(player, road.record.owner());
         String dimension = player.serverLevel().dimension().location().toString();
         if (tool.hasUUID("SideMain")) {
            RoadIndex.Built selected = RoadData.get(player.serverLevel()).index.roads.get(tool.getUUID("SideMain"));
            if (selected != null
               && selected.record.settings().options().attachments().points().stream().anyMatch(p -> p.id().equals(tool.getUUID("SidePoint")) && p.elevated())) {
               throw new IllegalArgumentException("高架出入点须连接已有高架的尽头端点");
            }
         }

         if (tool.hasUUID("SideMain") && dimension.equals(tool.getString("Dimension"))) {
            openSelection(
               player,
               tool,
               PairedSideJunctions.create(
                  player.serverLevel(), player, tool.getUUID("SideMain"), tool.getUUID("SidePoint"), road.record.id(), point.id(), preference(tool)
               )
            );
         } else if (tool.contains("Start") && dimension.equals(tool.getString("Dimension"))) {
            openSelection(
               player, tool, create(player.serverLevel(), player, road.record.id(), point.id(), BlockPos.of(tool.getLong("Start")), false, preference(tool))
            );
         } else {
            tool.remove("ElevatedSelection");
            tool.remove("SideSelection");
            tool.remove("Start");
            tool.remove("CurveControlNodes");
            tool.putUUID("SideMain", road.record.id());
            tool.putUUID("SidePoint", point.id());
            tool.putString("Dimension", dimension);
            player.displayClientMessage(Component.literal("已选附属点 A，请右键端点或另一主路附属点 B；单行连接按 A→B"), true);
         }
      }
   }

   private static CompoundTag selection(CompoundTag command) {
      CompoundTag t = new CompoundTag();

      for (String key : List.of("Main", "AttachedPoint", "Branch", "MainFirst", "Paired", "MainB", "AttachedPointB")) {
         if (command.contains(key)) {
            t.put(key, command.get(key).copy());
         }
      }

      return t;
   }

   private static void openSelection(ServerPlayer player, CompoundTag tool, CompoundTag command) {
      tool.put("SideSelection", selection(command));
      RoadNetwork.open(player, SideRoads.payload(command));
   }

   public static String buildRoadSelected(ServerLevel level, ServerPlayer player, CompoundTag command, ItemStack stack) {
      CompoundTag tool = ItemNBT.edit(stack);
      if (command.hasUUID("Id")
         || level.dimension().location().toString().equals(tool.getString("Dimension"))
            && tool.contains("SideSelection")
            && tool.getCompound("SideSelection").equals(selection(command))) {
         String result = build(level, player, command, RoadRecord.readSettings(command.getCompound("Settings")));

         for (String key : List.of("SideSelection", "SideMain", "SidePoint", "Start")) {
            tool.remove(key);
         }

         return result;
      } else {
         throw new IllegalArgumentException("连接选点已失效，请重新选择附属点和道路端点");
      }
   }

   public static String buildSelected(ServerLevel level, ServerPlayer player, CompoundTag command, ItemStack stack) {
      CompoundTag tool = ItemNBT.edit(stack);
      if (command.hasUUID("Id")
         || level.dimension().location().toString().equals(tool.getString("Dimension"))
            && tool.contains("SideSelection")
            && tool.getCompound("SideSelection").equals(selection(command))) {
         String result = build(level, player, command);

         for (String key : List.of("SideSelection", "SideMain", "SidePoint", "Start")) {
            tool.remove(key);
         }

         return result;
      } else {
         throw new IllegalArgumentException("单边路口选点已失效，请重新选择附属点和支路端点");
      }
   }

   static boolean saved(RoadData data, UUID group) {
      return group != null && data.junctions.getOrDefault(group, new CompoundTag()).getBoolean("OneSided");
   }

   public static CompoundTag create(
      ServerLevel level, ServerPlayer player, UUID mainId, UUID pointId, BlockPos branch, boolean mainFirst, RoadGeometry.Settings fallback
   ) {
      RoadIndex.Built main = RoadData.get(level).index.roads.get(mainId);
      if (main == null) {
         throw new IllegalArgumentException("主路已变化，请重新选择附属点");
      } else if (!SideHosts.attachable(main.record)) {
         throw new IllegalArgumentException("请选择普通主路路段的附属点；不能直接占用路口铺装或立交组成段");
      } else {
         CompoundTag t = new CompoundTag();
         t.putBoolean("OneSided", true);
         t.putUUID("Main", mainId);
         t.putUUID("AttachedPoint", pointId);
         t.putLong("Branch", branch.asLong());
         t.putBoolean("MainFirst", mainFirst);
         t.put("Fallback", RoadRecord.writeSettings(fallback));
         RoadRecord original = SideHosts.original(RoadData.get(level), main.record);
         t.put("OriginalMain", original.save());
         return fresh(level, player, t, original);
      }
   }

   public static CompoundTag payload(ServerLevel level, ServerPlayer player, UUID group) {
      if (PairedSideJunctions.saved(RoadData.get(level), group)) {
         return PairedSideJunctions.payload(level, player, group);
      } else {
         CompoundTag saved = RoadData.get(level).junctions.get(group);
         if (saved != null && saved.getBoolean("OneSided")) {
            if (player != null) {
               RoadData.requireOwner(player, saved.getUUID("Owner"));
            }

            return fresh(level, player, saved.copy(), RoadRecord.load(saved.getCompound("OriginalMain")));
         } else {
            throw new IllegalArgumentException("单边路口已经不存在");
         }
      }
   }

   static CompoundTag fresh(ServerLevel level, ServerPlayer player, CompoundTag t, RoadRecord original) {
      return fresh(level, player, t, original, null);
   }

   static CompoundTag fresh(ServerLevel level, ServerPlayer player, CompoundTag t, RoadRecord original, RoadGeometry.Node virtual) {
      if (player != null) {
         RoadData.requireOwner(player, original.owner());
      }

      RoadGeometry.Mesh mesh = original.mesh();
      RoadProfile.Options options = original.settings().options();
      if (RoadProfile.catalog(original.settings().style()).twoWay() && !options.leftTraffic()) {
         if (original.settings().mode() != RoadGeometry.Mode.RING
            && options.lanePoints().points(List.of()).equals(LanePoints.Data.EMPTY)
            && !options.lanePoints()
               .points()
               .stream()
               .anyMatch(p -> !p.automatic() || !LaneTopology.references(RoadData.get(level), LanePoints.Ref.lane(original.id(), p.id())).isEmpty())
            && options.routing().controls().isEmpty()
            && !options.attachments().points().stream().anyMatch(RoadAttachments.Point::controlled)) {
            RoadAttachments.Point point = options.attachments()
               .points()
               .stream()
               .filter(p -> p.id().equals(t.getUUID("AttachedPoint")))
               .findFirst()
               .orElseThrow(() -> new IllegalArgumentException("主路附属点已改变，请重新选择"));
            RoadGeometry.Sample at = RoadQueries.horizontal(mesh, point.position()).sample();
            BlockPos branch = BlockPos.of(t.getLong("Branch"));
            if (!branch.equals(original.a()) && !branch.equals(original.b())) {
               RoadGeometry.Node node = virtual == null ? RoadData.requireNode(level, branch, player).constructionNode() : virtual;
               RoadGeometry.V inward = at.center().sub(node.position()).horizontalUnit();
               int side = node.position().sub(at.center()).dot(at.left()) > 0.0 ? 1 : -1;
               RoadData data = RoadData.get(level);
               UUID group = t.hasUUID("Id") ? t.getUUID("Id") : null;
               List<RoadIndex.Built> links = (virtual == null ? data.index.atNode(branch) : Set.of())
                  .stream()
                  .map(data.index.roads::get)
                  .filter(r -> !Objects.equals(group, r.record.assembly()) || group == null)
                  .toList();
               if (links.size() > 1) {
                  throw new IllegalArgumentException("支路端点连接多条道路，请选只有一条外接路的端点");
               } else {
                  RoadGeometry.Settings profile = RoadRecord.readSettings(t.getCompound("Fallback"));
                  boolean incoming = !t.getBoolean("MainFirst");
                  if (!links.isEmpty()) {
                     RoadIndex.Built link = links.get(0);
                     if (!AutoJunctions.ordinary(link.record)) {
                        throw new IllegalArgumentException("支路须为普通道路端点");
                     }

                     if (player != null) {
                        RoadData.requireOwner(player, link.record.owner());
                     }

                     boolean first = branch.equals(link.record.a());
                     RoadGeometry.Sample sample = first ? link.mesh.first() : link.mesh.last();
                     inward = sample.left().left().mul(first ? 1.0 : -1.0);
                     profile = RoadEndpointSections.orient(link.record.settings().taper(sample.halfWidth() * 2.0, sample.halfWidth() * 2.0), first);
                     if (!RoadProfile.catalog(profile.style()).twoWay() && incoming == first) {
                        throw new IllegalArgumentException("所选 A→B 与现有单行支路方向冲突，请按行驶方向重新选择");
                     }

                     incoming = !first;
                     node = new RoadGeometry.Node(
                        sample.center(), RoadPlanner.yaw(inward), (double)(first ? -1 : 1) * (first ? link.record.start() : link.record.end()).grade()
                     );
                     t.putUUID("BranchRoad", link.record.id());
                     t.putInt("BranchSignature", link.record.header().hashCode());
                  } else {
                     node = new RoadGeometry.Node(node.position(), RoadPlanner.yaw(inward), node.grade());
                  }

                  if (RoadProfile.catalog(profile.style()).type() == RoadProfile.Type.ORDINARY && !profile.options().leftTraffic()) {
                     profile = section(profile);
                     if (t.contains("ConnectionSettings")) {
                        profile = SideRoads.settings(RoadRecord.readSettings(t.getCompound("ConnectionSettings")), profile, !links.isEmpty());
                        incoming = !t.getBoolean("MainFirst");
                     }

                     JunctionSpec previous = t.contains("Spec") ? JunctionCodec.read(t.getCompound("Spec")) : null;
                     double corner = previous == null ? 6.0 : previous.cornerRadius();
                     double span = Math.max(48.0, mesh.settings().width() + profile.width() + corner + 16.0);
                     double from = previous == null ? at.distance() - span : previous.oneSide().from();
                     double to = previous == null ? at.distance() + span : previous.oneSide().to();
                     if (!(from < 2.0) && !(to > mesh.length() - 2.0)) {
                        List<RoadSplitGeometry.Range> protectedRanges = RoadSplitGeometry.protection(original);
                        RoadSplitGeometry.requireClear(protectedRanges, from, from);
                        RoadSplitGeometry.requireClear(protectedRanges, to, to);
                        ArrayList<JunctionSpec.Arm> arms = new ArrayList<>();

                        for (int i = 0; i < 2; i++) {
                           RoadGeometry.Sample port = RoadSlicing.at(mesh, i == 0 ? from : to);
                           RoadGeometry.V f = port.left().left().mul(i == 0 ? -1.0 : 1.0);
                           RoadGeometry.Settings section = section(
                              RoadEndpointSections.orient(original.settings().taper(port.halfWidth() * 2.0, port.halfWidth() * 2.0), i == 1)
                           );
                           double d = 0.1;
                           RoadGeometry.Sample before = RoadSlicing.at(mesh, Math.max(0.0, port.distance() - d));
                           RoadGeometry.Sample after = RoadSlicing.at(mesh, Math.min(mesh.length(), port.distance() + d));
                           double grade = (after.center().y() - before.center().y())
                              / Math.max(1.0E-7, after.center().sub(before.center()).horizontalLength())
                              * (double)(i == 0 ? 1 : -1);
                           arms.add(
                              JunctionSpec.arm(new RoadGeometry.Node(port.center(), RoadPlanner.yaw(f), grade), f, section, true, i)
                                 .attached(true)
                                 .crossing(false)
                                 .signs(i == (side > 0 ? 0 : 1), false)
                           );
                        }

                        arms.add(JunctionSpec.arm(node, inward, profile, incoming, 2).attached(!links.isEmpty()).signs(false, true));
                        if (previous != null) {
                           for (int i = 0; i < 3; i++) {
                              JunctionSpec.Arm old = previous.arms().get(i);
                              JunctionSpec.Arm a = arms.get(i);
                              if ((i != 2 || !t.contains("ConnectionSettings"))
                                 && (a.incoming() != old.incoming() || a.outgoing() != old.outgoing() || a.width() != old.width())) {
                                 throw new IllegalArgumentException("接入道路断面已变化，请重新建立单边路口");
                              }

                              ArrayList<JunctionSpec.Lane> lanes = new ArrayList<>();

                              for (int lane = 0; lane < a.incoming(); lane++) {
                                 lanes.add(lane < old.lanes().size() ? old.lanes().get(lane) : JunctionSpec.Lane.AUTO);
                              }

                              arms.set(
                                 i,
                                 new JunctionSpec.Arm(
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
                                    i == 2 && old.crosswalk(),
                                    old.crossingWidth(),
                                    old.crossingSetback(),
                                    old.stopGap(),
                                    old.phase(),
                                    lanes,
                                    a.attached(),
                                    old.advanceSign(),
                                    old.nameSign(),
                                    false
                                 )
                              );
                           }
                        }

                        OneSidedJunction.Source source = new OneSidedJunction.Source(mesh, original.structures(), from, to, side);
                        JunctionSpec spec = previous == null
                           ? new JunctionSpec(
                              at.center(),
                              JunctionSpec.Kind.INTERSECTION,
                              false,
                              corner,
                              12.0,
                              1,
                              4.0,
                              original.settings().thickness(),
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
                              at.center(),
                              JunctionSpec.Kind.INTERSECTION,
                              false,
                              corner,
                              12.0,
                              1,
                              4.0,
                              original.settings().thickness(),
                              previous.control(),
                              previous.greenSeconds(),
                              previous.yellowSeconds(),
                              previous.allRedSeconds(),
                              previous.timeOffset(),
                              previous.guides(),
                              true,
                              arms
                           );
                        spec = spec.oneSide(source).supportedCrossings();
                        OneSidedJunction.prepare(spec);
                        SideHosts.available(data, original, point.id(), spec, group);
                        t.putString("Kind", "junction");
                        t.put("Spec", JunctionCodec.write(spec));
                        t.putInt("MainSignature", original.save().hashCode());
                        t.putLongArray("Points", new long[]{original.a().asLong(), original.b().asLong(), branch.asLong()});
                        t.putInt("SelectedArm", 2);
                        return t;
                     } else {
                        throw new IllegalArgumentException("附属点两侧主路至少各预留 " + (int)Math.ceil(span + 2.0) + " 格，请移动附属点或延长主路");
                     }
                  } else {
                     throw new IllegalArgumentException("支路须为右侧通行的普通道路");
                  }
               }
            } else {
               throw new IllegalArgumentException("支路端点不能是主路自身端点");
            }
         } else {
            throw new IllegalArgumentException("主路含匝道、车道改造或曲线控制点，请在独立普通路段建立单边路口");
         }
      } else {
         throw new IllegalArgumentException("单边路口主路须为右侧通行的双向普通道路");
      }
   }

   public static String build(ServerLevel level, ServerPlayer player, CompoundTag command) {
      return build(level, player, command, null);
   }

   private static String build(ServerLevel level, ServerPlayer player, CompoundTag command, RoadGeometry.Settings roadSettings) {
      if (!command.getBoolean("Paired") && (!command.hasUUID("Id") || !PairedSideJunctions.saved(RoadData.get(level), command.getUUID("Id")))) {
         String var31;
         try (RoadWorkChunks work = RoadWorkChunks.open(level)) {
            RoadData data = RoadData.get(level);
            UUID group = command.hasUUID("Id") ? command.getUUID("Id") : UUID.randomUUID();
            boolean editing = command.hasUUID("Id");
            CompoundTag fresh = editing
               ? payload(level, player, group)
               : create(
                  level,
                  player,
                  command.getUUID("Main"),
                  command.getUUID("AttachedPoint"),
                  BlockPos.of(command.getLong("Branch")),
                  command.getBoolean("MainFirst"),
                  RoadRecord.readSettings(command.getCompound("Fallback"))
               );
            if (fresh.getInt("MainSignature") != command.getInt("MainSignature") || fresh.getInt("BranchSignature") != command.getInt("BranchSignature")) {
               throw new IllegalArgumentException("道路在预览后发生变化，请重新打开单边路口");
            }

            if (roadSettings != null) {
               if (SideRoads.signature(fresh) != command.getInt("Signature")) {
                  throw new IllegalArgumentException("连接道路或路口已变化，请重新打开道路编辑器");
               }

               fresh.put("ConnectionSettings", RoadRecord.writeSettings(roadSettings));
               fresh = fresh(level, player, fresh, RoadRecord.load(fresh.getCompound("OriginalMain")));
            }

            JunctionSpec requested = roadSettings == null ? validated(fresh, command) : JunctionCodec.read(fresh.getCompound("Spec"));
            fresh.put("Spec", JunctionCodec.write(requested));
            RoadRecord original = RoadRecord.load(fresh.getCompound("OriginalMain"));
            fresh = fresh(level, player, fresh, original);
            requested = JunctionCodec.read(fresh.getCompound("Spec"));
            JunctionPlanner.Plan plan = JunctionPlanner.plan(requested);
            HashSet<UUID> removed = new HashSet<>();
            removed.add(original.id());
            if (editing) {
               data.index.roads.values().stream().filter(r -> group.equals(r.record.assembly())).forEach(r -> removed.add(r.record.id()));
            }

            ArrayList<RoadIndex.Built> built = new ArrayList<>();
            BlockPos center = BlockPos.containing(requested.center().x(), requested.center().y(), requested.center().z());

            for (int i = 0; i < plan.pieces().size(); i++) {
               JunctionPlanner.Piece p = plan.pieces().get(i);
               boolean retained = p.arm() == -3;
               RoadGeometry.Mesh mesh = p.mesh();
               UUID id = retained ? original.id() : UUID.nameUUIDFromBytes((group + ":one-side:" + i).getBytes(StandardCharsets.UTF_8));
               if (retained) {
                  built.add(new RoadIndex.Built(SideHosts.retained(data, original, group, requested)));
               } else {
                  RoadGeometry.Node a = retained
                     ? original.start()
                     : new RoadGeometry.Node(mesh.first().center(), RoadPlanner.yaw(mesh.first().left().left().mul(-1.0)), 0.0);
                  RoadGeometry.Node b = retained
                     ? original.end()
                     : new RoadGeometry.Node(mesh.last().center(), RoadPlanner.yaw(mesh.last().left().left().mul(-1.0)), 0.0);
                  BlockPos posA = retained ? original.a() : (p.arm() == 2 ? BlockPos.of(fresh.getLong("Branch")) : center);
                  BlockPos posB = retained ? original.b() : center;
                  built.add(
                     new RoadIndex.Built(
                        new RoadRecord(id, original.owner(), posA, posB, a, b, retained ? original.settings() : mesh.settings(), false, 4)
                           .junction(group, new JunctionPlanner.Ref(requested, i))
                     )
                  );
               }
            }

            for (RoadIndex.Built proposed : built) {
               for (RoadIndex.Built old : data.index.roads.values()) {
                  if (!removed.contains(old.record.id()) && RoadIndex.overlapXZ(proposed.mesh, old.mesh, 1.0)) {
                     Interchanges.checkExternal(proposed.mesh, old.mesh, null);
                  }
               }
            }

            HashSet<BlockPos> nodes = new HashSet<>(List.of(original.a(), original.b(), BlockPos.of(fresh.getLong("Branch"))));
            data.replaceAssembly(level, player, built, removed, nodes);
            fresh.putUUID("Id", group);
            fresh.putUUID("Owner", original.owner());
            data.junctions.put(group, fresh);
            data.setDirty();
            var31 = "单边路口已保存；中央隔离带和对向道路保持原状";
         }

         return var31;
      } else {
         return PairedSideJunctions.build(level, player, command, roadSettings);
      }
   }

   public static void remove(ServerLevel level, ServerPlayer player, UUID group) {
      SideHosts.remove(level, player, group);
   }

   static JunctionSpec validated(CompoundTag fresh, CompoundTag command) {
      JunctionSpec source = JunctionCodec.read(fresh.getCompound("Spec"));
      CompoundTag request = command.getCompound("Spec").copy();
      if (!request.contains("OneSide")) {
         request.put("OneSide", fresh.getCompound("Spec").getCompound("OneSide").copy());
      }

      JunctionSpec requested = JunctionCodec.read(request);
      if (source.center().equals(requested.center())
         && requested.arms().size() == 3
         && source.oneSide().equals(requested.oneSide())
         && !requested.leftTraffic()
         && requested.thickness() == source.thickness()) {
         for (int i = 0; i < 3; i++) {
            JunctionSpec.Arm a = source.arms().get(i);
            JunctionSpec.Arm b = requested.arms().get(i);
            if (!a.endpoint().equals(b.endpoint())
               || !a.inward().equals(b.inward())
               || !a.external().equals(b.external())
               || a.width() != b.width()
               || a.incoming() != b.incoming()
               || a.outgoing() != b.outgoing()
               || a.median() != b.median()
               || a.medianKind() != b.medianKind()
               || a.cycleWidth() != b.cycleWidth()
               || a.curbWidth() != b.curbWidth()
               || a.attached() != b.attached()) {
               throw new IllegalArgumentException("单边路口继承接入道路断面，不能修改主路或支路宽度");
            }
         }

         return requested;
      } else {
         throw new IllegalArgumentException("主路及保护区参数已变化，请重新预览");
      }
   }

   private static RoadGeometry.Settings section(RoadGeometry.Settings s) {
      return s.options(s.options().lanePoints(LanePoints.Data.EMPTY).attachments(RoadAttachments.Data.EMPTY));
   }

   private OneSidedJunctions() {
   }
}
