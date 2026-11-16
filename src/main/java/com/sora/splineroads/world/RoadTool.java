package com.sora.splineroads.world;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadPreferences;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadTransitions;
import com.sora.splineroads.net.RoadActionContext;
import com.sora.splineroads.net.RoadNetwork;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The road connector tool. Not final: add-on mods (e.g. Spline Roads: Quick Build) subclass it so
 * the shared "is the player holding a road tool" checks in {@code RoadNetwork} keep working.
 */
public class RoadTool extends Item {
   public static Consumer<CompoundTag> openClient = t -> {
   };

   public static boolean showsNodes(ItemStack stack) {
      return stack.getItem() instanceof LanePointTool
         || stack.getItem() instanceof LaneRampTool
         || stack.getItem() instanceof ElevatedPointTool
         || stack.getItem() instanceof AttachedPointTool
         || stack.getItem() instanceof LaneLineTool
         || stack.getItem() instanceof RoadTool
         || stack.getItem() instanceof RoadSplitTool
         || stack.getItem() instanceof JunctionTool
         || stack.getItem() instanceof YJunctionTool
         || stack.getItem() instanceof RoundaboutTool
         || stack.getItem() instanceof TollTool
         || stack.getItem() instanceof InterchangeTool
         || stack.getItem() instanceof RoadRemover
         || stack.is((Item)SplineRoads.NODE_ITEM.get());
   }

   public static BlockPos endpointAtHit(UseOnContext context) {
      return endpointAtHit(context.getLevel(), context.getClickedPos(), context.getClickLocation());
   }

   public static BlockPos endpointAtHit(Level level, BlockPos clicked, Vec3 hit) {
      if (!RoadBlocks.isCollider(level.getBlockState(clicked))) {
         return clicked;
      } else {
         BlockPos best = clicked;
         double nearest = 0.9;

         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               for (int dy = -2; dy <= 0; dy++) {
                  BlockPos p = clicked.offset(dx, dy, dz);
                  BlockEntity v = level.getBlockEntity(p);
                  if (v instanceof NodeEntity) {
                     NodeEntity node = (NodeEntity)v;
                     RoadGeometry.V vx = node.node().position();
                     double distance = Math.hypot(vx.x() - hit.x, vx.z() - hit.z);
                     if (distance < nearest && hit.y - vx.y() >= -0.15 && hit.y - vx.y() <= 2.0) {
                        nearest = distance;
                        best = p;
                     }
                  }
               }
            }
         }

         return best;
      }
   }

   public RoadTool() {
      super(new Properties().stacksTo(1));
   }

   public InteractionResult useOn(UseOnContext context) {
      if (context.getLevel().isClientSide) {
         return InteractionResult.SUCCESS;
      } else if (context.getPlayer() instanceof ServerPlayer player) {
         BlockPos var22 = endpointAtHit(context);
         ServerLevel level = player.serverLevel();
         CompoundTag tool = ItemNBT.edit(context.getItemInHand());

         try (RoadWorkChunks workChunks = RoadWorkChunks.open(level)) {
            if (!player.isShiftKeyDown()) {
               AttachedPointTool.Pick marker = AttachedPointTool.pick(player);
               if (marker != null) {
                  OneSidedJunctions.selectedAttached(player, tool, marker.road(), marker.point());
                  return InteractionResult.CONSUME;
               }
            }

            if (!player.isShiftKeyDown() && !(level.getBlockEntity(var22) instanceof NodeEntity)) {
               RoadIndex.Built picked = pick(player);
               Vec3 hit = context.getClickLocation();
               RoadAttachments.Point attached = picked == null ? null : AttachedPointTool.nearest(picked, new RoadGeometry.V(hit.x, hit.y, hit.z));
               if (attached != null && (SideHosts.attachable(picked.record) || attached.elevated() && ElevatedPointTool.supported(picked.record))) {
                  OneSidedJunctions.selectedAttached(player, tool, picked, attached);
                  return InteractionResult.CONSUME;
               }
            }

            if (level.getBlockEntity(var22) instanceof NodeEntity node) {
               RoadData.requireOwner(player, node.owner);
               if (player.isShiftKeyDown() && AutoJunctions.center(RoadData.get(level), var22) != null) {
                  throw new IllegalArgumentException("这是路口中心，请用普通路口编辑器编辑；道路连接器用于接入路段");
               }

               if (!player.isShiftKeyDown()) {
                  if (OneSidedJunctions.selectedEndpoint(player, tool, var22)) {
                     return InteractionResult.CONSUME;
                  }

                  String dimension = level.dimension().location().toString();
                  if (tool.contains("Start") && dimension.equals(tool.getString("Dimension"))) {
                     BlockPos a = BlockPos.of(tool.getLong("Start"));
                     if (a.equals(var22)) {
                        throw new IllegalArgumentException("请选另一个端点；Shift＋右键空气清除选择");
                     }

                     NodeEntity first = RoadData.requireNode(level, a, player);
                     if (TollRoads.reverseConnection(RoadData.get(level), a, var22)) {
                        BlockPos swap = a;
                        a = var22;
                        var22 = swap;
                        NodeEntity swapNode = first;
                        first = node;
                        node = swapNode;
                        tool.putLong("Start", a.asLong());
                     }

                     CompoundTag t = new CompoundTag();
                     t.putString("Kind", "road");
                     t.putLong("A", a.asLong());
                     t.putLong("B", var22.asLong());
                     RoadData data = RoadData.get(level);
                     t.put("StartNode", RoadRecord.writeNode(data.connectionNode(level, a, true, null, node.constructionNode().position())));
                     t.put("EndNode", RoadRecord.writeNode(data.connectionNode(level, var22, false, null, first.constructionNode().position())));
                     RoadGeometry.Settings saved = tool.contains("Settings")
                        ? RoadRecord.readSettings(tool.getCompound("Settings"))
                        : new RoadGeometry.Settings(RoadGeometry.Mode.AUTO, RoadGeometry.Style.O2_YELLOW, 9.0, 1.0, 0.35, 90.0);
                     if (tool.getInt("PreferencesVersion") < 3) {
                        saved = saved.options(saved.options().infrastructure(saved.options().infrastructure().grade(0.06).autoSpan(true)));
                     }

                     saved = RoadPreferences.fresh(saved);
                     double width = tool.getInt("PreferencesVersion") >= 2 ? saved.width() : saved.style().defaultWidth();
                     RoadGeometry.Style choice = saved.style();
                     if (choice.ramp()) {
                        choice = RoadGeometry.Style.TWO_LANE;
                        width = 9.0;
                     }

                     if (!choice.ramp() && !RoadProfile.modern(choice)) {
                        width = data.inheritedWidth(a, var22, width, null);
                     }

                     // The add-on's quick build tool can force a straight road: it writes
                     // ForceStraight into the tool NBT (see splinequickbuild).
                     RoadGeometry.Mode requestedMode = tool.getBoolean("ForceStraight")
                        ? RoadGeometry.Mode.STRAIGHT
                        : (
                           ManualPlanner.inSession() && CurveControlNodes.controls(tool).isEmpty()
                              ? RoadGeometry.Mode.STRAIGHT
                              : RoadGeometry.Mode.AUTO
                        );
                     RoadGeometry.Settings settings = new RoadGeometry.Settings(
                           requestedMode,
                           choice,
                           width,
                           saved.thickness(),
                           saved.tension(),
                           saved.arcDegrees()
                        )
                        .options(
                           saved.options()
                              .lanePoints(LanePoints.Data.EMPTY)
                              .attachments(RoadAttachments.Data.EMPTY)
                              .ends(RoadTransitions.Ends.NONE)
                              .infrastructure(saved.options().infrastructure().clearEdits())
                              .lift(0.5, 0.0)
                              .route(
                                 saved.options().routing().offset(0.0, 0.0).controls(CurveControlNodes.controls(tool)).through(tool.getBoolean("CurveThrough"))
                              )
                              .outsets(0.0, 0.0)
                              .sides(RoadProfile.Side.AUTO, RoadProfile.Side.AUTO)
                        )
                        .structure(saved.structure())
                        .rampTurn(RoadGeometry.RampTurn.LEGACY);
                     t.put("Settings", RoadRecord.writeSettings(RoadData.joinWidths(settings, data.endpointWidth(a, null), data.endpointWidth(var22, null))));
                     t.putDouble("JoinWidthA", data.endpointWidth(a, null));
                     t.putDouble("JoinWidthB", data.endpointWidth(var22, null));
                     data.jointPayload(t, a, var22, null);
                     if (t.contains("JoinSectionA") || t.contains("JoinSectionB")) {
                        RoadGeometry.Settings port = RoadRecord.readSettings(t.getCompound(t.contains("JoinSectionA") ? "JoinSectionA" : "JoinSectionB"));
                        settings = continuation(settings, port);
                        t.put("Settings", RoadRecord.writeSettings(settings));
                     }

                     t.put("AutoA", RoadData.writeHint(data.constructionHint(level, a, true, null, node.constructionNode().position(), false)));
                     t.put("AutoB", RoadData.writeHint(data.constructionHint(level, var22, false, null, first.constructionNode().position(), false)));
                     AutoJunctions.enrich(t, data, a, var22, null);
                     RoadNetwork.open(player, t);
                  } else {
                     tool.remove("CurveControlNodes");
                     tool.putLong("Start", var22.asLong());
                     tool.putString("Dimension", dimension);
                     player.displayClientMessage(Component.literal("已选起点 " + var22.toShortString() + "，请右键另一个端点；Shift＋右键端点可编辑朝向与坡度"), true);
                  }

                  return InteractionResult.CONSUME;
               }

               CompoundTag tx = new CompoundTag();
               tx.putString("Kind", "node");
               tx.putLong("Pos", var22.asLong());
               tx.put("Node", RoadRecord.writeNode(node.node()));
               tx.put("OriginalNode", RoadRecord.writeNode(node.node()));
               ListTag attached = new ListTag();
               RoadData nodeData = RoadData.get(level);

               for (RoadIndex.Built built : nodeData.index.roads.values()) {
                  RoadRecord r = built.record;
                  if (r.a().equals(var22) || r.b().equals(var22)) {
                     if (r.assembly() != null) {
                        throw new IllegalArgumentException("自动立交端点请先用立交连接器删除整座，再调整高差后重建");
                     }

                     CompoundTag entry = new CompoundTag();
                     entry.put("Road", r.header());
                     entry.put("AutoA", RoadData.writeHint(nodeData.hint(level, r.a(), true, r.id())));
                     entry.put("AutoB", RoadData.writeHint(nodeData.hint(level, r.b(), false, r.id())));
                     attached.add(entry);
                  }
               }

               tx.put("Attached", attached);
               RoadNetwork.open(player, tx);
               return InteractionResult.CONSUME;
            }

            RoadIndex index = RoadData.get(level).index;
            Set<UUID> ids = index.at(var22.asLong());
            if (!ids.isEmpty()) {
               Vec3 hit = context.getClickLocation();
               RoadIndex.Built picked = pick(player);
               RoadRecord road = picked != null && ids.contains(picked.record.id())
                  ? picked.record
                  : ids.stream()
                        .map(index.roads::get)
                        .min(
                           Comparator.comparingDouble(
                              r -> r.mesh
                                    .samples()
                                    .stream()
                                    .mapToDouble(s -> Math.abs(s.center().y() - hit.y) + Math.hypot(s.center().x() - hit.x, s.center().z() - hit.z) * 0.01)
                                    .min()
                                    .orElse(1.0E9)
                           )
                        )
                        .orElseThrow()
                     .record;
               requireTool(road);
               openRoad(player, road, false);
               return InteractionResult.CONSUME;
            } else {
               player.displayClientMessage(Component.literal("右键端点进行连接，右键已有路面可编辑或删除"), true);
               return InteractionResult.CONSUME;
            }
         } catch (IllegalArgumentException var21) {
            if (ManualPlanner.inSession()) {
               throw var21;
            } else {
               player.displayClientMessage(Component.literal(var21.getMessage()), false);
               return InteractionResult.CONSUME;
            }
         }
      } else {
         return InteractionResult.PASS;
      }
   }

   public static RoadIndex.Built pick(ServerPlayer player) {
      Vec3 eye = player.getEyePosition();
      Vec3 look = player.getLookAngle();
      return RoadData.get(player.serverLevel()).index.pick(new RoadGeometry.V(eye.x, eye.y, eye.z), new RoadGeometry.V(look.x, look.y, look.z), 256.0);
   }

   public static void openDeletion(ServerPlayer player, UUID id) {
      openDeletion(player, id, new CompoundTag());
   }

   public static void openDeletion(ServerPlayer player, UUID id, CompoundTag command) {
      RoadData data = RoadData.get(player.serverLevel());
      RoadRecord road = data.streets.get(id);
      if (road == null) {
         RoadIndex.Built built = data.index.roads.get(id);
         if (built != null) {
            road = built.record;
         }
      }

      if (road == null) {
         throw new IllegalArgumentException("道路已删除或尚未加载，请重新选择");
      } else {
         RoadData.requireOwner(player, road.owner());
         openRoad(player, road, true, command);
      }
   }

   public static void openRoad(ServerPlayer player, RoadRecord road, boolean deletion) {
      openRoad(player, road, deletion, new CompoundTag());
   }

   private static void openRoad(ServerPlayer player, RoadRecord road, boolean deletion, CompoundTag command) {
      if (!deletion && ElevatedAccessRoads.saved(RoadData.get(player.serverLevel()), road.assembly())) {
         ElevatedAccessRoads.open(player, road.assembly());
      } else if (road.railway()) {
         throw new IllegalArgumentException("铁路路基请使用铁路路基连接器编辑或删除");
      } else {
         Consumer<CompoundTag> reply = tx -> {
            RoadActionContext.echo(command, tx);
            RoadNetwork.open(player, tx);
         };

         try (RoadWorkChunks workChunks = RoadWorkChunks.open(player.serverLevel())) {
            ServerLevel level = player.serverLevel();
            RoadData.requireOwner(player, road.owner());
            if (road.junction() != null && road.junction().spec().oneSide() != null) {
               CompoundTag t = OneSidedJunctions.payload(level, player, road.assembly());
               if (!deletion) {
                  t = SideRoads.payload(t);
               }

               t.putBoolean("DeleteOnly", deletion);
               reply.accept(t);
               return;
            }

            if (road.toll() != null) {
               CompoundTag t = TollRoads.payload(level, player, new long[0], road.id());
               t.putBoolean("DeleteOnly", deletion);
               reply.accept(t);
               return;
            }

            if (road.junction() != null && deletion && road.junction().get().arm() >= 0) {
               CompoundTag saved = RoadData.get(level).junctions.get(road.assembly());
               if (saved != null && saved.getBoolean("Auto")) {
                  String key = saved.getList("ArmRoads", 8).getString(road.junction().get().arm());
                  RoadRecord branch = RoadData.get(level).streets.get(UUID.fromString(key));
                  if (branch == null && RoadData.get(level).index.roads.containsKey(UUID.fromString(key))) {
                     branch = RoadData.get(level).index.roads.get(UUID.fromString(key)).record;
                  }

                  if (branch != null) {
                     CompoundTag t = new CompoundTag();
                     t.putString("Kind", "road");
                     t.putBoolean("DeleteOnly", true);
                     t.putUUID("Id", branch.id());
                     t.put("Road", branch.header());
                     LaneDeletes.payload(RoadData.get(level), branch.id(), t);
                     reply.accept(t);
                     return;
                  }
               }
            }

            if (road.junction() != null && !deletion) {
               RoadRecord logical = JunctionRoads.logical(RoadData.get(level), road);
               if (logical == null) {
                  reply.accept(JunctionRoads.payload(level, player, road));
                  return;
               }

               road = logical;
            }

            if (road.junction() != null) {
               CompoundTag t = Junctions.selectedPayload(level, player, road);
               t.putBoolean("DeleteOnly", true);
               reply.accept(t);
               return;
            }

            if (!deletion && LaneTopology.metadata(road).link() != null) {
               reply.accept(LaneRamps.tunnelChoices(LaneRamps.payload(road, true), LaneTopology.records(RoadData.get(level))));
               return;
            }

            if (!deletion && RoadData.get(level).streets.containsKey(road.id())) {
               road = RoadData.get(level).editableRoad(road.id());
            }

            if (deletion) {
               CompoundTag t = new CompoundTag();
               t.putString("Kind", "road");
               t.putBoolean("DeleteOnly", true);
               t.putUUID("Id", road.id());
               t.put("Road", road.header());
               t.putBoolean("ElevatedDelete", ElevatedAccessRoads.saved(RoadData.get(level), road.assembly()));
               LaneDeletes.payload(RoadData.get(level), road.id(), t);
               reply.accept(t);
               return;
            }

            if (road.assembly() != null && RoadData.get(level).interchanges.getOrDefault(road.assembly(), new CompoundTag()).getBoolean("YJunction")) {
               throw new IllegalArgumentException("请用普通路口编辑器右键此 Y 字路口");
            }

            if (road.assembly() != null) {
               throw new IllegalArgumentException("此路段属于自动立交，请用立交连接器右键整体编辑");
            }

            CompoundTag t = new CompoundTag();
            t.putBoolean("DeleteOnly", deletion);
            t.put("Road", road.header());
            t.putString("Kind", "road");
            t.putUUID("Id", road.id());
            t.putLong("A", road.a().asLong());
            t.putLong("B", road.b().asLong());
            t.put(
               "StartNode",
               RoadRecord.writeNode(
                  new RoadGeometry.Node(RoadData.requireNode(level, road.a(), player).constructionNode().position(), road.start().yaw(), road.start().grade())
               )
            );
            t.put(
               "EndNode",
               RoadRecord.writeNode(
                  new RoadGeometry.Node(RoadData.requireNode(level, road.b(), player).constructionNode().position(), road.end().yaw(), road.end().grade())
               )
            );
            t.put(
               "Settings",
               RoadRecord.writeSettings(
                  road.automatic() ? RoadPlanner.mode(road.settings(), RoadGeometry.Mode.AUTO, road.settings().arcDegrees()) : road.settings()
               )
            );
            RoadData data = RoadData.get(level);
            t.putDouble("JoinWidthA", data.endpointWidth(road.a(), road.id()));
            t.putDouble("JoinWidthB", data.endpointWidth(road.b(), road.id()));
            data.jointPayload(t, road.a(), road.b(), road.id());
            t.put("AutoA", RoadData.writeHint(data.constructionHint(level, road.a(), true, road.id(), road.end().position(), false)));
            t.put("AutoB", RoadData.writeHint(data.constructionHint(level, road.b(), false, road.id(), road.start().position(), false)));
            if (!road.settings().style().ramp()) {
               AutoJunctions.enrich(t, data, road.a(), road.b(), road.id());
            }

            reply.accept(t);
         }
      }
   }

   public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
      ItemStack stack = player.getItemInHand(hand);
      if (!level.isClientSide && !player.isShiftKeyDown()) {
         ServerPlayer server = (ServerPlayer)player;
         AttachedPointTool.Pick marker = AttachedPointTool.pick(server);
         if (marker != null) {
            try {
               OneSidedJunctions.selectedAttached(server, ItemNBT.edit(stack), marker.road(), marker.point());
            } catch (IllegalArgumentException var8) {
               player.displayClientMessage(Component.literal(var8.getMessage()), false);
            }

            return InteractionResultHolder.success(stack);
         }
      }

      if (player.isShiftKeyDown()) {
         if (!level.isClientSide) {
            ItemNBT.edit(stack).remove("ElevatedSelection");
            ItemNBT.edit(stack).remove("SideSelection");
            ItemNBT.edit(stack).remove("SideMain");
            ItemNBT.edit(stack).remove("SidePoint");
            ItemNBT.edit(stack).remove("Start");
            ItemNBT.edit(stack).remove("CurveControlNodes");
            CompoundTag request = kind("reset");
            RoadNetwork.open((ServerPlayer)player, request);
            player.displayClientMessage(Component.literal("已清除道路选择与预览"), true);
         }
      } else if (level.isClientSide) {
         CompoundTag request = kind("resume");
         openClient.accept(request);
      } else if (!ItemNBT.get(stack).contains("Start")) {
         RoadIndex.Built road = pick((ServerPlayer)player);
         if (road != null) {
            try {
               requireTool(road.record);
               openRoad((ServerPlayer)player, road.record, false);
            } catch (IllegalArgumentException var9) {
               player.displayClientMessage(Component.literal(var9.getMessage()), false);
            }
         }
      }

      return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
   }

   private static void requireTool(RoadRecord road) {
      if (road.settings().style().ramp() && (!road.settings().style().connectorRamp() || LaneTopology.metadata(road).link() == null)) {
         throw new IllegalArgumentException("此旧路段可用道路删除器移除");
      }
   }

   static RoadGeometry.Settings continuation(RoadGeometry.Settings requested, RoadGeometry.Settings port) {
      RoadProfile.Options options = port.options()
         .lanePoints(LanePoints.Data.EMPTY)
         .attachments(RoadAttachments.Data.EMPTY)
         .laneLines(List.of())
         .streetscape(port.options().streetscape().raisedSpans(List.of()).tunnels(List.of()).crossings(List.of()))
         .infrastructure(port.options().infrastructure().clearEdits())
         .lift(0.5, 0.0)
         .outsets(0.0, 0.0)
         .route(requested.options().routing())
         .sides(RoadProfile.Side.AUTO, RoadProfile.Side.AUTO);
      return new RoadGeometry.Settings(requested.mode(), port.style(), port.width(), port.thickness(), port.tension(), port.arcDegrees())
         .structure(port.structure())
         .options(options);
   }

   private static CompoundTag kind(String kind) {
      CompoundTag t = new CompoundTag();
      t.putString("Kind", kind);
      return t;
   }

   public void appendHoverText(ItemStack s, TooltipContext l, List<Component> lines, TooltipFlag flag) {
      lines.add(Component.translatable("tooltip.splineroads.connector"));
   }
}
