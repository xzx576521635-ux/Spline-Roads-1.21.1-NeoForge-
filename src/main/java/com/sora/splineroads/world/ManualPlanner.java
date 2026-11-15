package com.sora.splineroads.world;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.net.ManualPlannerPackets;
import com.sora.splineroads.net.RoadNetwork;
import com.sora.splineroads.net.RoadPlanningJobs;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map.Entry;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class ManualPlanner {
   public static final int RADIUS = 2048;
   private static final Map<ServerPlayer, ManualPlanner.Session> SESSIONS = new WeakHashMap<>();
   private static final ThreadLocal<ManualPlanner.Session> CURRENT = new ThreadLocal<>();

   private static Item generator(int arms) {
      return switch (arms) {
         case 3 -> (Item)SplineRoads.INTERCHANGE_3.get();
         default -> (Item)SplineRoads.INTERCHANGE.get();
         case 5 -> (Item)SplineRoads.INTERCHANGE_5.get();
         case 6 -> (Item)SplineRoads.INTERCHANGE_6.get();
      };
   }

   private static void permission(ServerPlayer p) {
      if (!p.mayBuild() || p.isSpectator()) {
         throw new IllegalArgumentException("没有建设权限");
      }
   }

   private static ManualPlanner.Session require(ServerPlayer p, CompoundTag t) {
      permission(p);
      ManualPlanner.Session s = SESSIONS.get(p);
      if (s != null && s.active && s.level == p.serverLevel() && t.hasUUID("PlannerSession") && s.token.equals(t.getUUID("PlannerSession"))) {
         return s;
      } else {
         throw new IllegalArgumentException("地图会话已失效，请按 P 重新打开");
      }
   }

   static boolean active(ServerPlayer p, UUID token) {
      ManualPlanner.Session s = SESSIONS.get(p);
      return s != null && s.active && s.level == p.serverLevel() && s.token.equals(token);
   }

   static boolean inSession() {
      return CURRENT.get() != null;
   }

   public static ItemStack tool(ServerPlayer p, CompoundTag t) {
      return require(p, t).editor;
   }

   public static boolean ownsTool(ServerPlayer p, ItemStack tool) {
      ManualPlanner.Session s = SESSIONS.get(p);
      return s != null && s.active && s.level == p.serverLevel() && s.tools.containsValue(tool);
   }

   public static String edit(ServerPlayer p, CompoundTag t, Supplier<String> operation) {
      ManualPlanner.Session s = require(p, t);
      long revision = s.history.revision();
      CURRENT.set(s);

      String var9;
      try (
         RoadWorkChunks work = RoadWorkChunks.open(p.serverLevel());
         ManualPlannerHistory.Scope history = s.history.begin(p);
      ) {
         String result = operation.get();
         history.finish();
         var9 = result;
      } finally {
         if (s.terrain != null && s.history.revision() != revision) {
            s.terrain.changed(s.history.changedChunks());
         }

         CURRENT.remove();
         state(p, s, "manualState");
      }

      return var9;
   }

   public static void decorate(ServerPlayer p, CompoundTag payload) {
      ManualPlanner.Session s = CURRENT.get();
      if (s != null) {
         payload.putUUID("PlannerSession", s.token);
         String var4 = payload.getString("Kind");

         Item item = switch (var4) {
            case "railbed" -> (Item)SplineRoads.RAILBED_CONNECTOR.get();
            case "node", "road", "armRoad", "junctionRoad", "elevatedAccess" -> (Item)SplineRoads.CONNECTOR.get();
            case "lanePoint" -> (Item)SplineRoads.LANE_POINT.get();
            case "attachedPoint" -> (Item)SplineRoads.ATTACHED_POINT.get();
            case "toll" -> (Item)SplineRoads.TOLL.get();
            case "laneRamp" -> (Item)SplineRoads.RAMP_CONNECTOR.get();
            case "junction" -> payload.getBoolean("OneSided") ? (Item)SplineRoads.CONNECTOR.get() : (Item)SplineRoads.JUNCTION.get();
            case "roundabout" -> (Item)SplineRoads.ROUNDABOUT.get();
            case "yJunction" -> (Item)SplineRoads.Y_JUNCTION.get();
            case "corridor" -> payload.getLongArray("Points").length == 2 ? (Item)SplineRoads.FRONTAGE.get() : (Item)SplineRoads.LAYERED.get();
            case "interchange" -> generator(payload.contains("GeneratorArms") ? payload.getInt("GeneratorArms") : payload.getLongArray("Points").length);
            default -> null;
         };
         if (item != null) {
            s.editor = s.tool(item);
         }
      }
   }

   public static String action(ServerPlayer p, CompoundTag t) {
      permission(p);
      String action = t.getString("Action");
      if (!action.equals("manualOpen")) {
         if (action.equals("manualCancelOpen")) {
            ManualPlanner.Session pending = SESSIONS.get(p);
            if (pending != null && t.hasUUID("OpenRequest") && t.getUUID("OpenRequest").equals(pending.openingRequest)) {
               pending.active = false;
               if (pending.terrain != null) {
                  pending.terrain.close();
                  pending.terrain = null;
               }
            }

            return "";
         } else {
            ManualPlanner.Session s = require(p, t);
            if (action.equals("manualClose")) {
               s.active = false;
               if (s.terrain != null) {
                  s.terrain.close();
                  s.terrain = null;
               }

               RoadPlanningJobs.cancel(p.getUUID());
               return "";
            } else if (action.equals("manualSettings")) {
               int radius = t.getInt("Radius");
               int steps = t.getInt("MaxUndo");
               if (radius >= 16 && radius <= 2048 && steps >= 1 && steps <= 30) {
                  s.terrain.configure(radius);
                  s.history.limit(steps);
                  p.getPersistentData().putInt("SRPlannerRadius", radius);
                  p.getPersistentData().putInt("SRPlannerUndo", steps);
                  state(p, s, "manualState");
                  return "规划设置已保存";
               } else {
                  throw new IllegalArgumentException("规划设置超出范围");
               }
            } else if (action.equals("manualRefresh")) {
               state(p, s, "manualState");
               return "";
            } else if (action.equals("manualTool")) {
               int tool = t.getInt("Tool");
               int arms = t.getInt("Arms");
               if (tool >= 1 && tool <= 7 && arms >= 3 && arms <= 6) {
                  RoadPlanningJobs.cancel(p.getUUID());
                  s.selected = tool;
                  s.arms = arms;
                  s.editor = s.selected();
                  state(p, s, "manualState");
                  return "已选择地图工具";
               } else {
                  throw new IllegalArgumentException("地图工具无效");
               }
            } else if (action.equals("manualClear")) {
               CompoundTag selection = ItemNBT.edit(s.selected());
               selection.remove("Start");
               selection.remove("SideMain");
               selection.remove("SidePoint");
               selection.remove("ElevatedSelection");
               selection.remove("SideSelection");
               selection.remove("CurveControlNodes");
               selection.remove("CurveThrough");
               selection.remove("Points");
               LaneRampTool.clearSelection(p, s.selected());
               selection.remove("InlineSelection");
               RoadPlanningJobs.cancel(p.getUUID());
               state(p, s, "manualState");
               return "已清除当前选点";
            } else {
               long now = p.serverLevel().getGameTime();
               if (now == s.last) {
                  throw new IllegalArgumentException("请稍候再操作");
               } else {
                  s.last = now;
                  if (!action.equals("manualUndo") && !action.equals("manualRedo")) {
                     return edit(
                        p,
                        t,
                        () -> {
                           if (!action.equals("manualAccessPreview") && !action.equals("manualAccessBuild")) {
                              RoadGeometry.V hit = new RoadGeometry.V(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z"));
                              check(p, hit);
                              BlockPos pos = t.contains("Pos") ? BlockPos.of(t.getLong("Pos")) : BlockPos.containing(hit.x(), hit.y(), hit.z());
                              check(p, new RoadGeometry.V((double)pos.getX(), (double)pos.getY(), (double)pos.getZ()));
                              if (t.hasUUID("Road") && road(p, t).record.railway()) {
                                 if (!action.equals("manualDetails") && !action.equals("manualDelete") && (!action.equals("manualClick") || s.selected != 1)) {
                                    throw new IllegalArgumentException("铁路路基不提供公路车道点；请用指针再次单击打开编辑");
                                 } else {
                                    s.editor = s.tool((Item)SplineRoads.RAILBED_CONNECTOR.get());
                                    RailbedTool.openExisting(p, t.getUUID("Road"), s.token);
                                    return "";
                                 }
                              } else if (action.equals("manualMove")) {
                                 return move(p, t, hit);
                              } else if (action.equals("manualDelete")) {
                                 if (t.hasUUID("Point")) {
                                    openPoint(p, s, t, true);
                                    return "";
                                 } else if (t.hasUUID("Road")) {
                                    s.editor = s.tool((Item)SplineRoads.CONNECTOR.get());
                                    RoadTool.openDeletion(p, t.getUUID("Road"));
                                    return "";
                                 } else {
                                    deleteNode(p, pos);
                                    return "端点已删除";
                                 }
                              } else if (action.equals("manualDetails")) {
                                 if (t.hasUUID("Point")) {
                                    openPoint(p, s, t, false);
                                    return "";
                                 } else if (t.hasUUID("Road")) {
                                    RoadTool.openRoad(p, road(p, t).record, false);
                                    return "";
                                 } else {
                                    openNode(p, pos);
                                    return "";
                                 }
                              } else if (!action.equals("manualClick")) {
                                 throw new IllegalArgumentException("未知地图操作");
                              } else {
                                 s.editor = s.selected();
                                 if (s.selected == 1) {
                                    if (t.hasUUID("Point")) {
                                       openPoint(p, s, t, false);
                                    } else if (t.contains("Pos")) {
                                       openNode(p, pos);
                                    } else if (t.hasUUID("Road")) {
                                       RoadTool.openRoad(p, road(p, t).record, false);
                                    }

                                    return "";
                                 } else if (s.selected == 2) {
                                    if (t.getBoolean("Alt")) {
                                       ElevatedPointTool.create(p.serverLevel(), p, road(p, t), hit);
                                       return "已创建高架出入点";
                                    } else if (t.getBoolean("Ctrl")) {
                                       RoadIndex.Built road = SideHosts.hostAt(RoadData.get(p.serverLevel()), road(p, t), hit);
                                       if (!SideHosts.attachable(road.record)) {
                                          throw new IllegalArgumentException("请选择普通道路路面");
                                       } else {
                                          AttachedPointTool.create(p.serverLevel(), p, road, hit);
                                          return "已在对应断面添加附属点";
                                       }
                                    } else {
                                       place(p, hit);
                                       return "已放置端点";
                                    }
                                 } else if (s.selected == 3
                                    && (t.getString("PointKind").equals("attached") || t.getString("PointKind").equals("elevated"))
                                    && !t.getBoolean("Ctrl")
                                    && !t.getBoolean("Alt")) {
                                    RoadIndex.Built road = road(p, t);
                                    RoadAttachments.Point point = road.record
                                       .settings()
                                       .options()
                                       .attachments()
                                       .points()
                                       .stream()
                                       .filter(v -> v.id().equals(t.getUUID("Point")))
                                       .findFirst()
                                       .orElseThrow(() -> new IllegalArgumentException("附属点已不存在"));
                                    OneSidedJunctions.selectedAttached(p, ItemNBT.edit(s.editor), road, point);
                                    return "已选择道路附属点";
                                 } else if (s.selected != 3 || !t.getBoolean("Ctrl") && !t.getBoolean("Alt")) {
                                    if (s.selected == 4) {
                                       if (t.getBoolean("Ctrl")) {
                                          RoadIndex.Built road = road(p, t);
                                          RoadQueries.Projection q = RoadQueries.horizontal(road.mesh, hit);
                                          if (q.horizontalDistance() > q.sample().halfWidth() + 1.0) {
                                             throw new IllegalArgumentException("请选择道路断面");
                                          } else {
                                             LanePoints.Lane lane = LaneAdditions.slots(road.mesh, q.sample().distance())
                                                .stream()
                                                .filter(slot -> LaneSections.active(road.mesh, q.sample().distance(), slot))
                                                .map(slot -> LanePoints.lane(road.mesh, q.sample().distance(), slot))
                                                .min(Comparator.comparingDouble(v -> v.position().sub(hit).horizontalLength()))
                                                .orElseThrow(() -> new IllegalArgumentException("此断面没有可用车道"));
                                             LanePoints.Point point = LanePointTool.create(p.serverLevel(), p, road.record.id(), lane.position());
                                             return "已创建车道点 " + point.lane();
                                          }
                                       } else if (t.getBoolean("Alt")) {
                                          return placement(p, s, hit);
                                       } else {
                                          selectRamp(p, s, t);
                                          return "已选择匝道连接点";
                                       }
                                    } else if (s.selected == 5) {
                                       selectInterchange(p, s, pos);
                                       return "已选择立交端点";
                                    } else if (s.selected == 6) {
                                       if (t.hasUUID("Road") && !t.contains("Pos")) {
                                          RoadIndex.Built road = road(p, t);
                                          CompoundTag command = InlineTollRoads.payload(
                                             p.serverLevel(), p, road.record.id(), RoadQueries.horizontal(road.mesh, hit).sample().distance()
                                          );
                                          TollTool.inlineSelection(s.editor, command);
                                          RoadNetwork.open(p, command);
                                          return "";
                                       } else {
                                          selectToll(p, s, pos);
                                          return "已选择收费站端点";
                                       }
                                    } else if (s.selected != 3 && s.selected != 7) {
                                       return "";
                                    } else {
                                       RoadData.requireNode(p.serverLevel(), pos, p);
                                       var context = new UseOnContext(
                                          p, InteractionHand.MAIN_HAND, new BlockHitResult(new Vec3(hit.x(), hit.y(), hit.z()), Direction.UP, pos, false)
                                       ) {
                                          public ItemStack getItemInHand() {
                                             return s.editor;
                                          }
                                       };
                                       s.editor.useOn(context);
                                       return "";
                                    }
                                 } else {
                                    CompoundTag tag = ItemNBT.edit(s.editor);
                                    boolean through = t.getBoolean("Ctrl");
                                    if (t.getBoolean("Ctrl") && t.getBoolean("Alt")) {
                                       throw new IllegalArgumentException("经过点和控制点不能同时选择");
                                    } else if (!tag.contains("Start")) {
                                       throw new IllegalArgumentException("先选择起点 A，再添加经过点或控制点");
                                    } else if (!tag.getList("CurveControlNodes", 10).isEmpty() && tag.getBoolean("CurveThrough") != through) {
                                       throw new IllegalArgumentException("同一道路不能混用经过点和控制点；请清除选点后重选");
                                    } else {
                                       if (!t.contains("Pos")) {
                                          pos = place(p, hit);
                                       } else {
                                          RoadData.requireNode(p.serverLevel(), pos, p);
                                       }

                                       tag.putBoolean("CurveThrough", through);
                                       return CurveControlNodes.add(p.serverLevel(), p, s.editor, pos, true);
                                    }
                                 }
                              }
                           } else {
                              check(p, new RoadGeometry.V(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z")));
                              if (action.equals("manualAccessPreview")) {
                                 RoadNetwork.open(p, ManualAccessRoads.preview(p, t));
                                 return "";
                              } else {
                                 return ManualAccessRoads.build(p, t);
                              }
                           }
                        }
                     );
                  } else {
                     RoadPlanningJobs.cancel(p.getUUID());
                     String message = s.history.apply(p, action.equals("manualUndo"));
                     if (s.terrain != null) {
                        s.terrain.changed(s.history.changedChunks());
                     }

                     state(p, s, "manualState");
                     return message;
                  }
               }
            }
         }
      } else {
         ManualPlanner.Session s = SESSIONS.get(p);
         if (s == null || s.level != p.serverLevel()) {
            if (s != null && s.terrain != null) {
               s.terrain.close();
            }

            s = new ManualPlanner.Session(p);
            SESSIONS.put(p, s);
         }

         s.active = true;
         if (s.terrain == null) {
            s.terrain = new ManualPlannerTerrain(p, s.token);
            if (p.getPersistentData().contains("SRPlannerRadius")) {
               s.terrain.configure(Math.max(16, Math.min(2048, p.getPersistentData().getInt("SRPlannerRadius"))));
            }
         }

         s.openingRequest = t.hasUUID("OpenRequest") ? t.getUUID("OpenRequest") : null;
         state(p, s, "manualPlanner");
         return "";
      }
   }

   private static void check(ServerPlayer p, RoadGeometry.V point) {
      if (RoadGeometry.finite(point.x(), point.y(), point.z())
         && !(Math.abs(point.x()) > 2.9999984E7)
         && !(Math.abs(point.z()) > 2.9999984E7)
         && !(point.y() < (double)p.level().getMinBuildHeight())
         && !(point.y() >= (double)p.level().getMaxBuildHeight())
         && !(Math.hypot(point.x() - p.getX(), point.z() - p.getZ()) > 2048.0)) {
         BlockPos pos = BlockPos.containing(point.x(), point.y(), point.z());
         if (!p.serverLevel().hasChunkAt(pos)) {
            if (!inSession()) {
               throw new IllegalArgumentException("地图会话已关闭");
            }

            try (RoadWorkChunks work = RoadWorkChunks.open(p.serverLevel())) {
               work.node(pos);
            }
         }

         if (!p.serverLevel().mayInteract(p, pos)) {
            throw new IllegalArgumentException("没有修改此位置的权限");
         }
      } else {
         throw new IllegalArgumentException("请选择玩家附近2048格内、世界高度内的位置");
      }
   }

   private static RoadIndex.Built road(ServerPlayer p, CompoundTag t) {
      RoadIndex.Built b = RoadData.get(p.serverLevel()).index.roads.get(t.getUUID("Road"));
      if (b == null) {
         throw new IllegalArgumentException("道路已不存在");
      } else {
         RoadData.requireOwner(p, b.record.owner());
         return b;
      }
   }

   static BlockPos place(ServerPlayer p, RoadGeometry.V hit) {
      ServerLevel level = p.serverLevel();
      BlockPos pos = BlockPos.containing(hit.x(), hit.y(), hit.z());
      check(p, hit);
      if (!level.getBlockState(pos).canBeReplaced()) {
         throw new IllegalArgumentException("端点位置已被占用");
      } else {
         CompoundTag tag = new CompoundTag();
         tag.putUUID("Owner", p.getUUID());
         tag.putBoolean("HeightExplicit", true);
         tag.putBoolean("PlannerHeading", true);
         RoadGeometry.Node node = new RoadGeometry.Node(hit, 0.0, 0.0);
         tag.put("Node", RoadRecord.writeNode(node));
         RoadData.get(level).replaceAssembly(level, p, List.of(), Set.of(), Set.of(pos), List.of(new RoadData.NodeMove(null, pos, node, tag)));
         ManualPlanner.Session session = CURRENT.get();
         if (session != null && session.terrain != null && level.getBlockEntity(pos) instanceof NodeEntity n) {
            session.terrain.nodes.put(pos.asLong(), ManualPlannerTerrain.nodeTag(n));
         }

         return pos;
      }
   }

   private static void deleteNode(ServerPlayer p, BlockPos pos) {
      ServerLevel level = p.serverLevel();
      RoadData.requireNode(level, pos, p);
      RoadData data = RoadData.get(level);
      if (!data.linked(pos) && AutoJunctions.center(data, pos) == null) {
         data.replaceAssembly(level, p, List.of(), Set.of(), Set.of(pos), List.of(RoadData.NodeMove.retire(pos)));
      } else {
         throw new IllegalArgumentException("端点仍被道路使用，请先删除相关道路");
      }
   }

   private static void openNode(ServerPlayer p, BlockPos pos) {
      RoadData data = RoadData.get(p.serverLevel());
      NodeEntity node = RoadData.requireNode(p.serverLevel(), pos, p);
      CompoundTag junction = AutoJunctions.center(data, pos);
      if (junction != null) {
         RoadNetwork.open(p, Junctions.payload(p.serverLevel(), p, new long[0], junction.getUUID("Id")));
      } else {
         CompoundTag t = new CompoundTag();
         t.putString("Kind", "node");
         t.putLong("Pos", pos.asLong());
         t.put("Node", RoadRecord.writeNode(node.node()));
         t.put("OriginalNode", RoadRecord.writeNode(node.node()));
         ListTag attached = new ListTag();

         for (UUID id : data.index.atNode(pos)) {
            RoadRecord r = data.index.roads.get(id).record;
            if (r.assembly() != null) {
               throw new IllegalArgumentException("请使用相应道路组合的整体编辑界面");
            }

            CompoundTag e = new CompoundTag();
            e.put("Road", r.header());
            e.put("AutoA", RoadData.writeHint(data.hint(p.serverLevel(), r.a(), true, r.id())));
            e.put("AutoB", RoadData.writeHint(data.hint(p.serverLevel(), r.b(), false, r.id())));
            attached.add(e);
         }

         t.put("Attached", attached);
         RoadNetwork.open(p, t);
      }
   }

   private static void openPoint(ServerPlayer p, ManualPlanner.Session s, CompoundTag t, boolean delete) {
      RoadIndex.Built b = road(p, t);
      CompoundTag payload;
      if (t.getString("PointKind").equals("lane")) {
         payload = LanePointTool.payload(RoadData.get(p.serverLevel()), b.record, LaneTopology.point(b.record, t.getUUID("Point")));
      } else {
         if (t.getString("PointKind").equals("control")) {
            if (delete) {
               ArrayList<RoadProfile.Control> controls = new ArrayList<>(b.record.settings().options().routing().controls());
               if (!controls.removeIf(v -> v.id().equals(t.getUUID("Point")))) {
                  throw new IllegalArgumentException("曲线点已不存在");
               }

               RoadProfile.Options options = b.record.settings().options();
               RoadProfile.Routing routing = options.routing().controls(controls);
               RoadData.get(p.serverLevel())
                  .connect(
                     p.serverLevel(),
                     p,
                     b.record.a(),
                     b.record.b(),
                     b.record.settings().options(options.route(controls.isEmpty() ? routing.through(false) : routing)),
                     b.record.id()
                  );
            } else {
               RoadTool.openRoad(p, b.record, false);
            }

            return;
         }

         RoadAttachments.Point point = b.record
            .settings()
            .options()
            .attachments()
            .points()
            .stream()
            .filter(v -> v.id().equals(t.getUUID("Point")))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("附属点已不存在"));
         payload = AttachedPointTool.payload(b.record, point);
      }

      payload.putBoolean("PlannerDelete", delete);
      RoadNetwork.open(p, payload);
   }

   private static void selectToll(ServerPlayer p, ManualPlanner.Session s, BlockPos pos) {
      RoadData.requireNode(p.serverLevel(), pos, p);
      CompoundTag tag = ItemNBT.edit(s.editor);
      long[] old = tag.getLongArray("Points");
      if (old.length >= 2) {
         old = new long[0];
      }

      for (long a : old) {
         if (a == pos.asLong()) {
            throw new IllegalArgumentException("请选择另一个端点");
         }
      }

      long[] next = Arrays.copyOf(old, old.length + 1);
      next[old.length] = pos.asLong();
      tag.putLongArray("Points", next);
      tag.putString("Dimension", p.level().dimension().location().toString());
      tag.remove("InlineSelection");
      if (next.length == 2) {
         RoadNetwork.open(p, TollRoads.payload(p.serverLevel(), p, next, null));
      }
   }

   private static void selectInterchange(ServerPlayer p, ManualPlanner.Session s, BlockPos pos) {
      RoadData.requireNode(p.serverLevel(), pos, p);
      CompoundTag tag = ItemNBT.edit(s.editor);
      long[] old = tag.getLongArray("Points");
      if (old.length >= s.arms) {
         old = new long[0];
      }

      for (long a : old) {
         if (a == pos.asLong()) {
            throw new IllegalArgumentException("端点重复");
         }
      }

      long[] next = Arrays.copyOf(old, old.length + 1);
      next[old.length] = pos.asLong();
      tag.putLongArray("Points", next);
      tag.putString("Dimension", p.level().dimension().location().toString());
      if (next.length == s.arms) {
         CompoundTag payload = Interchanges.payload(p.serverLevel(), p, next, null);
         payload.putInt("GeneratorArms", s.arms);
         RoadNetwork.open(p, payload);
      }
   }

   private static void selectRamp(ServerPlayer p, ManualPlanner.Session s, CompoundTag t) {
      RoadData data = RoadData.get(p.serverLevel());
      LanePoints.Ref ref;
      if (t.hasUUID("Point") && t.getString("PointKind").equals("lane")) {
         RoadIndex.Built b = road(p, t);
         LaneTopology.point(b.record, t.getUUID("Point"));
         ref = LanePoints.Ref.lane(b.record.id(), t.getUUID("Point"));
      } else {
         if (!t.contains("Pos") || AutoJunctions.center(data, BlockPos.of(t.getLong("Pos"))) == null) {
            throw new IllegalArgumentException("选择车道点，或先 Ctrl＋单击道路放置车道点");
         }

         ref = LanePoints.Ref.junction(AutoJunctions.center(data, BlockPos.of(t.getLong("Pos"))).getUUID("Id"));
      }

      if (ref.junction() == null) {
         LaneRamps.host(LaneTopology.records(data), ref);
      } else {
         RampJunctions.spec(data, ref.junction());
         RoadData.requireOwner(p, data.junctions.get(ref.junction()).getUUID("Owner"));
      }

      CompoundTag state = ItemNBT.edit(s.editor);
      resetMissingRampStart(p, s.editor, data);
      state.putString("LaneDimension", p.level().dimension().location().toString());
      if (!state.contains("LaneFrom")) {
         if (ref.junction() != null) {
            throw new IllegalArgumentException("先选择汇出车道点 A");
         } else {
            LaneRampTool.clearSelection(p, s.editor);
            state.putString("LaneDimension", p.level().dimension().location().toString());
            state.put("LaneFrom", LanePointCodec.ref(ref));
         }
      } else if (state.getCompound("LaneFrom").equals(LanePointCodec.ref(ref))) {
         throw new IllegalArgumentException("请选择不同的汇入点 B");
      } else {
         LaneRampTool.cancelPlanning(p, s.editor);
         state.put("LaneTo", LanePointCodec.ref(ref));
         CompoundTag payload = new CompoundTag();
         payload.putString("Kind", "laneRamp");
         payload.put("From", state.getCompound("LaneFrom").copy());
         payload.put("To", state.getCompound("LaneTo").copy());
         payload.put(
            "Options",
            LanePointCodec.options(
               new LanePoints.Options(
                  LanePoints.Path.AUTO,
                  LanePoints.Departure.TEMPORARY,
                  LanePoints.Arrival.MERGE,
                  24.0,
                  32.0,
                  LanePoints.Elevation.AUTO,
                  LanePoints.Landing.FLEXIBLE
               )
            )
         );
         RoadNetwork.open(p, LaneRamps.tunnelChoices(payload, LaneTopology.records(data)));
      }
   }

   private static void resetMissingRampStart(ServerPlayer p, ItemStack tool, RoadData data) {
      CompoundTag state = ItemNBT.edit(tool);
      if (state.contains("LaneFrom")) {
         CompoundTag from = state.getCompound("LaneFrom");
         RoadIndex.Built road = from.hasUUID("Road") ? data.index.roads.get(from.getUUID("Road")) : null;
         if (road == null
            || !from.hasUUID("Point")
            || LaneTopology.metadata(road.record).points().stream().noneMatch(v -> v.id().equals(from.getUUID("Point")))) {
            LaneRampTool.clearSelection(p, tool);
         }
      }
   }

   private static String placement(ServerPlayer p, ManualPlanner.Session s, RoadGeometry.V hit) {
      RoadNetwork.open(p, ManualAccessRoads.payload(p, hit, ItemNBT.edit(s.editor)));
      return "";
   }

   static String move(ServerPlayer p, CompoundTag t, RoadGeometry.V hit) {
      check(p, hit);
      ServerLevel level = p.serverLevel();
      RoadData data = RoadData.get(level);
      if (t.hasUUID("Point")) {
         RoadIndex.Built b = road(p, t);
         RoadRecord r = b.record;
         UUID id = t.getUUID("Point");
         String kind = t.getString("PointKind");
         if (kind.equals("lane")) {
            LanePoints.Point point = LaneTopology.point(r, id);
            if (point.automatic()) {
               throw new IllegalArgumentException("自动车道点随端点移动；可用 Ctrl 新建手动车道点");
            } else {
               RoadQueries.Projection q = RoadQueries.horizontal(b.mesh, hit);
               LanePoints.Lane lane = LanePoints.lane(b.mesh, q.sample().distance(), point.lane());
               ArrayList<LanePoints.Point> points = new ArrayList<>(LaneTopology.metadata(r).points());
               points.set(points.indexOf(point), point.at(lane, b.mesh));
               LanePointTool.update(level, p, r, LaneTopology.metadata(r).points(points), Set.of());
               return "车道点已沿原车道移动，相关匝道已更新";
            }
         } else if (kind.equals("control")) {
            RoadProfile.Routing routing = r.settings().options().routing();
            ArrayList<RoadProfile.Control> controls = new ArrayList<>(routing.controls());
            int at = -1;

            for (int i = 0; i < controls.size(); i++) {
               if (controls.get(i).id().equals(id)) {
                  at = i;
               }
            }

            if (at < 0) {
               throw new IllegalArgumentException("曲线点已不存在");
            } else {
               controls.set(at, new RoadProfile.Control(id, hit));
               RoadGeometry.Settings settings = r.settings().options(r.settings().options().route(routing.controls(controls)));
               data.connect(level, p, r.a(), r.b(), settings, r.id());
               return "曲线点及道路已更新";
            }
         } else {
            RoadAttachments.Data metadata = r.settings().options().attachments();
            ArrayList<RoadAttachments.Point> points = new ArrayList<>(metadata.points());
            RoadAttachments.Point point = points.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("附属点已不存在"));
            if (point.controlled()) {
               throw new IllegalArgumentException("此旧附属点控制原道路形状，请通过道路设置调整，以保留原曲线");
            } else {
               RoadQueries.Projection q = RoadQueries.horizontal(b.mesh, hit);
               RoadGeometry.V at = point.elevated() ? q.sample().at(RoadProfile.layout(b.mesh, q.sample()).medianCenter(), 0.0) : q.sample().center();
               double before = RoadAttachments.station(b.mesh, point.position());
               double after = q.sample().distance();
               if (!(after < 1.0) && !(after > b.mesh.length() - 1.0)) {
                  for (RoadAttachments.Point other : points) {
                     if (!other.id().equals(id)) {
                        double station = RoadAttachments.station(b.mesh, other.position());
                        if (Math.abs(station - after) < 0.25 || (station - before) * (station - after) < 0.0) {
                           throw new IllegalArgumentException("附属点不能越过相邻标线分界点");
                        }
                     }
                  }

                  points.set(points.indexOf(point), new RoadAttachments.Point(id, at, at, false, point.origin(), point.elevated()));
                  List<RoadAttachments.Span> spans = metadata.spans()
                     .stream()
                     .map(
                        span -> new RoadAttachments.Span(
                              span.start().distance(point.position()) < 1.0E-5 ? at : span.start(),
                              span.end().distance(point.position()) < 1.0E-5 ? at : span.end(),
                              span.paint()
                           )
                     )
                     .toList();
                  data.updatePointMetadata(level, p, r, new RoadAttachments.Data(points, spans));
                  return "附属点已沿道路断面移动";
               } else {
                  throw new IllegalArgumentException("附属点须保留在道路内部");
               }
            }
         }
      } else {
         BlockPos from = BlockPos.of(t.getLong("Pos"));
         BlockPos to = BlockPos.containing(hit.x(), hit.y(), hit.z());
         check(p, new RoadGeometry.V((double)from.getX(), (double)from.getY(), (double)from.getZ()));
         NodeEntity node = RoadData.requireNode(level, from, p);
         RoadGeometry.Node target = new RoadGeometry.Node(hit, node.yaw, node.grade);
         if (node.node().position().distance(hit) < 1.0E-7) {
            return "端点位置未改变";
         } else if (!to.equals(from) && !level.getBlockState(to).canBeReplaced()) {
            throw new IllegalArgumentException("目标端点位置已被占用");
         } else {
            ArrayList<RoadIndex.Built> replacements = new ArrayList<>();
            HashSet<UUID> ids = new HashSet<>(data.index.atNode(from));

            for (UUID id : ids) {
               RoadRecord r = data.index.roads.get(id).record;
               RoadData.requireOwner(p, r.owner());
               if (r.assembly() != null || r.toll() != null || data.streets.containsKey(id) || LaneTopology.metadata(r).link() != null) {
                  throw new IllegalArgumentException("此端点属于路口、收费站或匝道，请在对应整体设置中调整");
               }

               RoadRecord planned = RoadData.planNodeEdit(r, from, target, node.node(), data.hint(level, r.a(), true, id), data.hint(level, r.b(), false, id));
               RoadRecord moved = new RoadRecord(
                  id,
                  r.owner(),
                  r.a().equals(from) ? to : r.a(),
                  r.b().equals(from) ? to : r.b(),
                  planned.start(),
                  planned.end(),
                  planned.settings(),
                  planned.automatic(),
                  r.clearance(),
                  List.of(),
                  r.endCaps(),
                  r.buildVersion(),
                  null,
                  List.of(),
                  r.furniturePhase()
               );
               replacements.add(new RoadIndex.Built(moved));
            }

            CompoundTag saved = node.saveWithoutMetadata(level.registryAccess());
            saved.putBoolean("HeightExplicit", true);
            data.replaceAssembly(level, p, replacements, ids, new HashSet<>(List.of(from, to)), List.of(new RoadData.NodeMove(from, to, target, saved)));
            ManualPlanner.Session session = CURRENT.get();
            if (session != null && session.terrain != null) {
               session.terrain.nodes.remove(from.asLong());
               NodeEntity moved = RoadData.requireNode(level, to, p);
               session.terrain.nodes.put(to.asLong(), ManualPlannerTerrain.nodeTag(moved));
            }

            return "端点及所有相连道路已移动";
         }
      }
   }

   private static void state(ServerPlayer p, ManualPlanner.Session s, String kind) {
      CompoundTag t = new CompoundTag();
      t.putString("Kind", kind);
      t.putUUID("PlannerSession", s.token);
      if (kind.equals("manualPlanner") && s.openingRequest != null) {
         t.putUUID("OpenRequest", s.openingRequest);
      }

      t.putString("Dimension", s.level.dimension().location().toString());
      t.putInt("Tool", s.selected);
      t.putInt("Arms", s.arms);
      t.putInt("Radius", s.terrain == null ? 256 : s.terrain.radius());
      t.putInt("MaxUndo", s.history.limit());
      t.putInt("Undo", s.history.undoCount());
      t.putInt("Redo", s.history.redoCount());
      t.put("Selection", ItemNBT.edit(s.selected()).copy());
      ListTag nodes = new ListTag();
      int cx = p.blockPosition().getX() >> 4;
      int cz = p.blockPosition().getZ() >> 4;

      for (int x = cx - 32; x <= cx + 32; x++) {
         for (int z = cz - 32; z <= cz + 32; z++) {
            ChunkAccess chunk = s.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk instanceof LevelChunk) {
               LevelChunk loaded = (LevelChunk)chunk;

               for (BlockEntity be : loaded.getBlockEntities().values()) {
                  if (be instanceof NodeEntity node) {
                     CompoundTag e = new CompoundTag();
                     e.putLong("Pos", node.getBlockPos().asLong());
                     e.put("Node", RoadRecord.writeNode(node.node()));
                     nodes.add(e);
                  }
               }
            }
         }
      }

      if (s.terrain != null) {
         for (Tag entry : nodes) {
            CompoundTag n = (CompoundTag)entry;
            s.terrain.nodes.put(n.getLong("Pos"), n);
         }

         nodes = new ListTag();
         s.terrain.nodes.values().forEach(nodes::add);
      }

      t.put("Nodes", nodes);
      ListTag roads = new ListTag();

      for (RoadIndex.Built built : RoadData.get(s.level).index.roads.values()) {
         RoadGeometry.Mesh m = built.mesh;
         double dx = Math.max(0.0, Math.max(m.min().x() - p.getX(), p.getX() - m.max().x()));
         double dz = Math.max(0.0, Math.max(m.min().z() - p.getZ(), p.getZ() - m.max().z()));
         if (dx * dx + dz * dz <= 4194304.0) {
            roads.add(built.record.header());
         }
      }

      UUID snapshot = UUID.randomUUID();
      List<CompoundTag> pages = ManualPlannerPackets.pages(s.token, snapshot, roads, nodes);
      t.remove("Nodes");
      t.putUUID("MapSnapshot", snapshot);
      t.putInt("Pages", pages.size());
      t.putInt("RoadCount", roads.size());
      t.putInt("NodeCount", nodes.size());
      RoadNetwork.open(p, t);

      for (CompoundTag page : pages) {
         RoadNetwork.open(p, page);
      }
   }

   public static void tick() {
      for (Entry<ServerPlayer, ManualPlanner.Session> entry : new ArrayList<>(SESSIONS.entrySet())) {
         ServerPlayer p = entry.getKey();
         ManualPlanner.Session s = entry.getValue();
         if (p.isRemoved() || p.serverLevel() != s.level) {
            forget(p);
         } else if (s.active && s.terrain != null) {
            s.terrain.tick();
         }
      }
   }

   public static void forget(ServerPlayer p) {
      ManualPlanner.Session s = SESSIONS.remove(p);
      if (s != null && s.terrain != null) {
         s.terrain.close();
      }
   }

   public static void stop() {
      for (ServerPlayer p : new ArrayList<>(SESSIONS.keySet())) {
         forget(p);
      }
   }

   private ManualPlanner() {
   }

   static final class Session {
      final UUID token = UUID.randomUUID();
      final ServerLevel level;
      final ManualPlannerHistory history = new ManualPlannerHistory();
      final Map<Item, ItemStack> tools = new HashMap<>();
      ItemStack editor;
      UUID openingRequest;
      int selected = 1;
      int arms = 4;
      boolean active = true;
      long last = -100L;
      ManualPlannerTerrain terrain;

      Session(ServerPlayer player) {
         this.level = player.serverLevel();
         this.editor = this.tool((Item)SplineRoads.CONNECTOR.get());
         this.history
            .limit(Math.max(1, Math.min(30, player.getPersistentData().contains("SRPlannerUndo") ? player.getPersistentData().getInt("SRPlannerUndo") : 30)));
      }

      ItemStack tool(Item item) {
         return this.tools.computeIfAbsent(item, ItemStack::new);
      }

      ItemStack selected() {
         return this.tool(switch (this.selected) {
            case 4 -> (Item)SplineRoads.RAMP_CONNECTOR.get();
            case 5 -> ManualPlanner.generator(this.arms);
            case 6 -> (Item)SplineRoads.TOLL.get();
            case 7 -> (Item)SplineRoads.ROUNDABOUT.get();
            default -> (Item)SplineRoads.CONNECTOR.get();
         });
      }
   }
}
