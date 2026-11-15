package com.sora.splineroads.world;

import com.sora.splineroads.compat.mtr.MtrRailAccess;
import com.sora.splineroads.core.RailbedCandidates;
import com.sora.splineroads.core.RailbedGeometry;
import com.sora.splineroads.core.RailbedOptions;
import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.net.RoadNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(
   modid = "splineroads"
)
public final class RailbedTool extends Item {
   private static final Map<UUID, RailbedTool.Draft> drafts = new ConcurrentHashMap<>();

   public static void cancelSignal(UUID owner, UUID token) {
      RailbedTool.Draft d = drafts.get(owner);
      if (d != null && d.token.equals(token)) {
         d.cancel();
      }
   }

   public RailbedTool() {
      super(new Properties().stacksTo(1));
   }

   private static String dimension(ServerPlayer player) {
      ResourceLocation id = player.level().dimension().location();
      return id.getNamespace() + "/" + id.getPath();
   }

   private static boolean held(ServerPlayer player) {
      return player.getMainHandItem().getItem() instanceof RailbedTool || player.getOffhandItem().getItem() instanceof RailbedTool;
   }

   static RailbedTool.Draft draft(ServerPlayer player) {
      RailbedTool.Draft d = drafts.get(player.getUUID());
      if (d != null && d.dimension.equals(dimension(player))) {
         return d;
      } else {
         throw new IllegalArgumentException("铁路选择已失效，请重新选点");
      }
   }

   private static void require(ServerPlayer player, CompoundTag tag) {
      if (tag.hasUUID("PlannerSession")) {
         if (!(ManualPlanner.tool(player, tag).getItem() instanceof RailbedTool)) {
            throw new IllegalArgumentException("请从规划地图重新打开铁路路基");
         }
      } else {
         require(player);
      }
   }

   private static void publish(ServerPlayer player, RailbedTool.Draft d, CompoundTag tag) {
      if (d.plannerSession != null) {
         tag.putUUID("PlannerSession", d.plannerSession);
      }

      RoadNetwork.open(player, tag);
   }

   private static void require(ServerPlayer player) {
      if (!held(player) || !player.mayBuild() || player.isSpectator()) {
         throw new IllegalArgumentException("请手持铁路路基连接器并确保拥有建设权限");
      }
   }

   public InteractionResult useOn(UseOnContext context) {
      if (context.getLevel().isClientSide) {
         return InteractionResult.SUCCESS;
      } else if (context.getPlayer() instanceof ServerPlayer player) {
         try {
            require(player);
            if (player.isShiftKeyDown()) {
               clear(player);
               return InteractionResult.CONSUME;
            }

            String problem = MtrRailAccess.unavailable();
            if (problem != null) {
               throw new IllegalArgumentException(problem);
            }

            BlockPos pos = context.getClickedPos();
            if (!player.serverLevel().mayInteract(player, pos)) {
               throw new IllegalArgumentException("铁路节点位于受保护区域");
            }

            if (!MtrRailAccess.trainNode(context.getLevel().getBlockState(pos).getBlock())) {
               if (!drafts.containsKey(player.getUUID()) && edit(player)) {
                  return InteractionResult.CONSUME;
               }

               RailbedTool.Draft existing = draft(player);
               if (!toggle(player, existing)) {
                  resume(player);
               }

               return InteractionResult.CONSUME;
            }

            RailbedTool.Draft d = drafts.get(player.getUUID());
            if (d == null || !d.dimension.equals(dimension(player))) {
               d = new RailbedTool.Draft(player, pos);
               drafts.put(player.getUUID(), d);
               message(player, "已选铁路节点 A，请右键同一路径的节点 B");
            } else if (d.paths.isEmpty()) {
               if (d.pending != null && !d.pending.isDone()) {
                  throw new IllegalArgumentException("正在读取轨道，请稍候");
               }

               d.b = pos.immutable();
               read(player, d);
            } else if (!toggle(player, d)) {
               resume(player);
            }
         } catch (RuntimeException var6) {
            message(player, var6.getMessage());
         }

         return InteractionResult.CONSUME;
      } else {
         return InteractionResult.PASS;
      }
   }

   public InteractionResultHolder<ItemStack> use(Level level, Player actor, InteractionHand hand) {
      if (actor instanceof ServerPlayer player) {
         try {
            require(player);
            if (player.isShiftKeyDown()) {
               clear(player);
            } else if (drafts.containsKey(player.getUUID()) || !edit(player)) {
               RailbedTool.Draft d = draft(player);
               if (!toggle(player, d)) {
                  resume(player);
               }
            }
         } catch (RuntimeException var6) {
            message(player, var6.getMessage());
         }
      }

      return InteractionResultHolder.sidedSuccess(actor.getItemInHand(hand), level.isClientSide);
   }

   private static void read(ServerPlayer player, RailbedTool.Draft d) {
      message(player, "正在读取 MTR 实际轨迹…");
      CompletableFuture<RailbedPath.Path> future = MtrRailAccess.read(d.dimension, RailbedCodec.node(d.a), RailbedCodec.node(d.b));
      d.pending = future;
      d.reads(List.of(future));
      future.whenComplete((path, error) -> player.server.execute(() -> {
            if (valid(player, d)) {
               d.pending = null;
               if (error != null) {
                  d.b = null;
                  message(player, reason(error));
               } else {
                  d.paths.clear();
                  d.paths.add(path);
                  neighbors(player, d, true);
               }
            }
         }));
   }

   private static String reason(Throwable e) {
      while (e.getCause() != null) {
         e = e.getCause();
      }

      return e.getMessage() == null ? "铁路读取失败：" + e.getClass().getSimpleName() : e.getMessage();
   }

   private static void resume(ServerPlayer player) {
      open(player, draft(player));
   }

   private static void neighbors(ServerPlayer player, RailbedTool.Draft d, boolean show) {
      CompletableFuture<List<RailbedPath.Path>> future = MtrRailAccess.nearby(d.dimension, d.paths.get(0));
      d.pending = future;
      d.reads(List.of(future));
      future.whenComplete((near, error) -> player.server.execute(() -> {
            if (valid(player, d)) {
               d.pending = null;
               if (error != null) {
                  message(player, reason(error));
               } else {
                  d.surrounding = (List<RailbedPath.Path>)near;
                  d.candidates = RailbedCandidates.find(d.paths.get(0), (List<RailbedPath.Path>)near);
                  if (d.saved == null && d.paths.size() == 1 && d.previewKey.isEmpty()) {
                     List<RailbedPath.Path> selected = RailbedCandidates.automatic(d.paths.get(0), d.candidates);
                     d.paths.clear();
                     d.paths.addAll(selected);
                  }
               }

               open(player, d, show);
            }
         }));
   }

   static void open(ServerPlayer player, RailbedTool.Draft d) {
      open(player, d, true);
   }

   static void open(ServerPlayer player, RailbedTool.Draft d, boolean show) {
      if (d.paths.isEmpty()) {
         message(player, "请继续选择铁路节点 B");
      } else {
         preview(player, d);
         CompoundTag t = new CompoundTag();
         t.putString("Kind", show ? "railbed" : "railbedPreview");
         t.putUUID("Token", d.token);
         t.putString("Dimension", d.dimension);
         t.putString("SourceStatus", d.sourceStatus);
         t.putDouble("Width", d.width);
         t.putString("Mode", d.mode.name());
         t.put("Options", RailbedCodec.options(d.options));
         ListTag paths = new ListTag();
         d.paths.forEach(path -> paths.add(RailbedCodec.write(path)));
         t.put("Paths", paths);
         ListTag candidates = new ListTag();
         d.candidates.forEach(path -> candidates.add(RailbedCodec.write(path)));
         t.put("Candidates", candidates);
         t.putString("Selection", selection(d));
         if (d.previewResponse != null) {
            t.put("CachedPreview", d.previewResponse.copy());
         }

         if (d.saved != null) {
            t.putUUID("Id", d.saved);
         }

         publish(player, d, t);
      }
   }

   private static boolean valid(ServerPlayer player, RailbedTool.Draft d) {
      return !d.cancelled.get()
         && drafts.get(player.getUUID()) == d
         && !player.isRemoved()
         && d.dimension.equals(dimension(player))
         && (d.plannerSession == null || ManualPlanner.active(player, d.plannerSession));
   }

   private static String selection(RailbedTool.Draft d) {
      return d.paths.stream().map(RailbedPath.Path::fingerprint).toList().toString();
   }

   private static void preview(ServerPlayer player, RailbedTool.Draft d) {
      if (!d.cancelled.get() && !d.sourceStatus.startsWith("正在核验")) {
         String key = selection(d)
            + ":"
            + d.width
            + ":"
            + d.mode
            + ":"
            + d.options
            + ":"
            + d.saved
            + ":"
            + d.surrounding.stream().map(RailbedPath.Path::fingerprint).sorted().toList();
         if (d.previewTask == null || !key.equals(d.previewKey) || d.previewTask.future().isDone() && !d.previewTask.valid()) {
            if (d.preview != null) {
               d.preview.cancel(false);
            }

            double width = d.width;
            RoadGeometry.Structure mode = d.mode;
            RailbedOptions options = d.options;
            String selection = selection(d);
            d.previewResponse = null;
            d.previewKey = key;

            try {
               d.previewTask = RailbedBuildQueue.previewTask(
                  player.serverLevel(),
                  player,
                  d.saved == null ? d.token : d.saved,
                  List.copyOf(d.paths),
                  width,
                  mode,
                  d.surrounding,
                  options,
                  d.cancelled::get
               );
               CompletableFuture<RailbedStore.Entry> future = d.previewTask.future();
               d.preview = future;
               d.previewComputations++;
               future.whenComplete((entry, error) -> player.server.execute(() -> {
                     if (valid(player, d) && d.preview == future && !future.isCancelled()) {
                        CompoundTag tagx = new CompoundTag();
                        tagx.putString("Kind", "railbedStructurePreview");
                        tagx.putUUID("Token", d.token);
                        tagx.putDouble("Width", width);
                        tagx.putString("Mode", mode.name());
                        tagx.put("Options", RailbedCodec.options(options));
                        tagx.putString("Selection", selection);
                        if (error == null) {
                           tagx.put("Road", entry.road.save());
                        } else {
                           tagx.putString("Problem", reason(error));
                        }

                        d.previewResponse = tagx;
                        publish(player, d, tagx);
                     }
                  }));
            } catch (RuntimeException var10) {
               d.previewTask = null;
               CompoundTag tag = new CompoundTag();
               tag.putString("Kind", "railbedStructurePreview");
               tag.putUUID("Token", d.token);
               tag.putDouble("Width", width);
               tag.putString("Mode", mode.name());
               tag.put("Options", RailbedCodec.options(options));
               tag.putString("Selection", selection);
               tag.putString("Problem", reason(var10));
               d.previewResponse = tag;
               publish(player, d, tag);
            }
         }
      }
   }

   private static void sourceStatus(ServerPlayer player, RailbedTool.Draft d) {
      String unavailable = MtrRailAccess.unavailable();
      if (unavailable != null) {
         d.sourceStatus = "源不可用；仍可删除旧路基";
         open(player, d);
      } else {
         List<CompletableFuture<RailbedPath.Path>> reads = d.paths
            .stream()
            .map(p -> MtrRailAccess.read(d.dimension, p.segments().get(0).from(), p.segments().get(p.segments().size() - 1).to()))
            .toList();
         CompletableFuture<Void> all = CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new));
         d.pending = all;
         d.reads(reads);
         all.whenComplete((v, error) -> player.server.execute(() -> {
               if (valid(player, d)) {
                  d.pending = null;
                  if (error != null) {
                     d.sourceStatus = "源失效：" + reason(error) + "；旧路基已保留";
                     open(player, d);
                  } else {
                     try {
                        ArrayList<RailbedPath.Path> fresh = new ArrayList<>();
                        fresh.add(reads.get(0).join());

                        for (int i = 1; i < reads.size(); i++) {
                           fresh.add(RailbedCandidates.clip(fresh.get(0), reads.get(i).join().segments()));
                        }

                        boolean changed = false;

                        for (int i = 0; i < fresh.size(); i++) {
                           changed |= !fresh.get(i).fingerprint().equals(d.paths.get(i).fingerprint());
                        }

                        d.sourceStatus = changed ? "待更新：轨道已改变，保存时需确认新预览" : "源轨道有效";
                        neighbors(player, d, true);
                     } catch (RuntimeException var7) {
                        d.sourceStatus = "源失效：" + reason(var7);
                        open(player, d);
                     }
                  }
               }
            }));
      }
   }

   private static boolean edit(ServerPlayer player) {
      RoadIndex.Built picked = RoadTool.pick(player);
      if (picked != null && picked.record.railway()) {
         openExisting(player, picked.record.id(), null);
         return true;
      } else {
         return false;
      }
   }

   static void openExisting(ServerPlayer player, UUID id, UUID session) {
      RailbedStore.Entry entry = RoadData.get(player.serverLevel()).railbeds.entries.get(id);
      if (entry == null) {
         throw new IllegalArgumentException("铁路结构正在恢复，请稍候");
      } else {
         RoadData.requireOwner(player, entry.road.owner());
         RailbedTool.Draft d = new RailbedTool.Draft(player, entry.road.a());
         d.b = entry.road.b();
         d.paths.addAll(entry.paths);
         d.saved = entry.road.id();
         d.width = entry.width;
         d.mode = entry.mode;
         d.options = entry.options;
         d.plannerSession = session;
         RailbedTool.Draft previous = drafts.put(player.getUUID(), d);
         if (previous != null) {
            previous.cancel();
            if (previous.preview != null) {
               previous.preview.cancel(false);
            }
         }

         d.sourceStatus = "正在核验绑定轨道…";
         open(player, d);
         sourceStatus(player, d);
      }
   }

   private static boolean toggle(ServerPlayer player, RailbedTool.Draft d) {
      if (d.paths.isEmpty()) {
         return false;
      } else {
         Vec3 eye = player.getEyePosition();
         Vec3 look = player.getLookAngle();
         RoadGeometry.V origin = new RoadGeometry.V(eye.x, eye.y, eye.z);
         RoadGeometry.V direction = new RoadGeometry.V(look.x, look.y, look.z);
         RailbedPath.Path selected = null;
         double nearest = 97.0;

         for (RailbedPath.Path candidate : d.candidates) {
            List<RoadGeometry.V> points = candidate.positions();
            double hit = Double.POSITIVE_INFINITY;

            for (int i = 1; i < points.size(); i++) {
               RoadGeometry.V a = points.get(i - 1).add(new RoadGeometry.V(0.0, 0.2, 0.0));
               RoadGeometry.V delta = points.get(i).sub(points.get(i - 1));
               RoadGeometry.V w = a.sub(origin);
               double dd = delta.dot(delta);
               double vd = direction.dot(delta);
               double den = dd - vd * vd;
               double t = den < 1.0E-9 ? 0.0 : Math.max(0.0, Math.min(1.0, (vd * w.dot(direction) - w.dot(delta)) / den));
               RoadGeometry.V p = a.add(delta.mul(t));
               double distance = p.sub(origin).dot(direction);
               if (distance > 0.0 && distance < 96.0 && p.distance(origin.add(direction.mul(distance))) < 0.65) {
                  hit = Math.min(hit, distance);
               }
            }

            if (hit < nearest) {
               if (selected != null && Math.abs(hit - nearest) < 0.75) {
                  throw new IllegalArgumentException("候选轨道重叠，请靠近后选择");
               }

               selected = candidate;
               nearest = hit;
            }
         }

         if (selected == null) {
            return false;
         } else {
            String key = RailbedCandidates.key(selected);
            boolean removed = d.paths.removeIf(px -> px != d.paths.get(0) && RailbedCandidates.key(px).equals(key));
            if (!removed) {
               if (d.paths.size() >= 8) {
                  throw new IllegalArgumentException("最多选择 8 股轨道");
               }

               ArrayList<RailbedPath.Path> proposed = new ArrayList<>(d.paths);
               proposed.add(selected);
               RailbedGeometry.plan(proposed, 0.0, d.mode);
               d.paths.add(selected);
            }

            open(player, d, false);
            message(player, "已选 " + d.paths.size() + " 股轨道；右键空处返回设置");
            return true;
         }
      }
   }

   public static void action(ServerPlayer player, CompoundTag tag) {
      try {
         RailbedTool.Draft d = draft(player);
         if (tag.getString("Action").equals("railbedCancel") && tag.hasUUID("Token") && d.token.equals(tag.getUUID("Token"))) {
            clear(player);
            return;
         }

         require(player, tag);
         if (d.cancelled.get()) {
            return;
         }

         if (!Objects.equals(d.plannerSession, tag.hasUUID("PlannerSession") ? tag.getUUID("PlannerSession") : null)) {
            throw new IllegalArgumentException("铁路规划会话已变化，请重新打开");
         }

         if (!d.token.equals(tag.getUUID("Token"))) {
            throw new IllegalArgumentException("铁路草稿已变化，请重新打开");
         }

         String action = tag.getString("Action");
         if (action.equals("railbedCancel")) {
            clear(player);
            return;
         }

         if (d.pending != null && !d.pending.isDone()) {
            throw new IllegalArgumentException("正在读取或施工，请稍候");
         }

         if (action.equals("railbedDelete")) {
            if (d.saved == null) {
               throw new IllegalArgumentException("当前尚未建造");
            }

            d.pending = RailbedRoads.submit(player.serverLevel(), player, d.saved, null, 0.0, d.mode, d.options, List.of(), planner(d));
            d.pending.whenComplete((v, e) -> player.server.execute(() -> {
                  if (e == null && drafts.get(player.getUUID()) == d) {
                     clear(player);
                  }
               }));
            return;
         }

         double width = tag.getDouble("Width");
         if (!Double.isFinite(width) || width < 0.0 || width > 64.0) {
            throw new IllegalArgumentException("路基总宽无效");
         }

         RoadGeometry.Structure mode = RoadGeometry.Structure.valueOf(tag.getString("Mode"));
         if (mode == RoadGeometry.Structure.GROUND) {
            throw new IllegalArgumentException("铁路模式无效");
         }

         RailbedGeometry.plan(d.paths, width, mode);
         d.width = width;
         d.mode = mode;
         d.options = RailbedCodec.options(tag.getCompound("Options"));
         if (action.equals("railbedSettings")) {
            preview(player, d);
            if (d.previewResponse != null) {
               publish(player, d, d.previewResponse.copy());
            }

            return;
         }

         if (!action.equals("railbedBuild")) {
            throw new IllegalArgumentException("未知铁路操作");
         }

         build(player, d);
      } catch (RuntimeException var7) {
         message(player, var7.getMessage());
      }
   }

   private static CompoundTag planner(RailbedTool.Draft d) {
      if (d.plannerSession == null) {
         return null;
      } else {
         CompoundTag t = new CompoundTag();
         t.putUUID("PlannerSession", d.plannerSession);
         return t;
      }
   }

   private static void build(ServerPlayer player, RailbedTool.Draft d) {
      String unsupported = RailbedGeometry.unsupported(d.paths);
      if (unsupported != null) {
         throw new IllegalArgumentException(unsupported);
      } else {
         message(player, "正在重新校验 MTR 源轨道…");
         long sourceRevision = MtrRailAccess.revision(d.dimension);
         List<CompletableFuture<RailbedPath.Path>> reads = d.paths.stream().map(p -> {
            List<RailbedPath.Segment> s = p.segments();
            return MtrRailAccess.read(d.dimension, s.get(0).from(), s.get(s.size() - 1).to());
         }).toList();
         CompletableFuture<Void> all = CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new));
         d.pending = all;
         d.reads(reads);
         all.whenComplete(
            (ignored, error) -> player.server
                  .execute(
                     () -> {
                        if (valid(player, d)) {
                           if (error != null) {
                              message(player, reason(error));
                           } else {
                              try {
                                 ArrayList<RailbedPath.Path> fresh = new ArrayList<>();
                                 fresh.add(reads.get(0).join());

                                 for (int i = 1; i < reads.size(); i++) {
                                    fresh.add(RailbedCandidates.clip(fresh.get(0), reads.get(i).join().segments()));
                                 }

                                 for (int i = 0; i < fresh.size(); i++) {
                                    if (!fresh.get(i).fingerprint().equals(d.paths.get(i).fingerprint())) {
                                       d.paths.clear();
                                       d.paths.addAll(fresh);
                                       message(player, "轨道已变化，请重新确认建造");
                                       neighbors(player, d, true);
                                       return;
                                    }
                                 }

                                 CompletableFuture<List<RailbedPath.Path>> nearby = MtrRailAccess.nearby(d.dimension, fresh.get(0));
                                 d.pending = nearby;
                                 d.reads(List.of(nearby));
                                 nearby.whenComplete(
                                    (surrounding, failure) -> player.server
                                          .execute(
                                             () -> {
                                                if (valid(player, d)) {
                                                   if (failure != null) {
                                                      message(player, reason(failure));
                                                   } else {
                                                      try {
                                                         if (sourceRevision != MtrRailAccess.revision(d.dimension)) {
                                                            throw new IllegalArgumentException("MTR 轨道在校验期间发生变化，请重新预览");
                                                         }

                                                         UUID id = d.saved == null ? UUID.randomUUID() : d.saved;
                                                         CompletableFuture<RailbedStore.Entry> future = RailbedRoads.submit(
                                                            player.serverLevel(), player, id, fresh, d.width, d.mode, d.options, surrounding, planner(d)
                                                         );
                                                         d.pending = future;
                                                         message(player, "已加入铁路施工队列");
                                                         future.whenComplete((entry, ex) -> player.server.execute(() -> {
                                                               if (ex == null && drafts.get(player.getUUID()) == d) {
                                                                  clear(player);
                                                               }
                                                            }));
                                                      } catch (RuntimeException var9x) {
                                                         message(player, var9x.getMessage());
                                                      }
                                                   }
                                                }
                                             }
                                          )
                                 );
                              } catch (RuntimeException var8) {
                                 message(player, reason(var8));
                              }
                           }
                        }
                     }
                  )
         );
      }
   }

   private static void clear(ServerPlayer player) {
      RailbedTool.Draft d = drafts.remove(player.getUUID());
      if (d != null) {
         d.cancel();
         if (d.pending != null) {
            d.pending.cancel(false);
         }

         if (d.preview != null) {
            d.preview.cancel(false);
         }
      }

      CompoundTag tag = new CompoundTag();
      tag.putString("Kind", "railbedReset");
      if (d != null) {
         tag.putUUID("Token", d.token);
      }

      if (d != null) {
         publish(player, d, tag);
      } else {
         RoadNetwork.open(player, tag);
      }

      message(player, "铁路草稿已清除");
   }

   private static void message(ServerPlayer player, String text) {
      player.displayClientMessage(Component.literal(text == null ? "铁路操作失败" : text), true);
   }

   @SubscribeEvent
   public static void logout(PlayerLoggedOutEvent event) {
      RailbedTool.Draft d = drafts.remove(event.getEntity().getUUID());
      if (d != null) {
         d.cancel();
         if (d.pending != null) {
            d.pending.cancel(false);
         }

         if (d.preview != null) {
            d.preview.cancel(false);
         }
      }
   }

   @SubscribeEvent
   public static void dimensionChanged(PlayerChangedDimensionEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         clear(player);
      }
   }

   @SubscribeEvent
   public static void stopped(ServerStoppedEvent event) {
      for (RailbedTool.Draft d : drafts.values()) {
         d.cancel();
         if (d.pending != null) {
            d.pending.cancel(false);
         }

         if (d.preview != null) {
            d.preview.cancel(false);
         }
      }

      drafts.clear();
   }

   static final class Draft {
      final UUID token = UUID.randomUUID();
      final String dimension;
      final BlockPos a;
      BlockPos b;
      final AtomicBoolean cancelled = new AtomicBoolean();
      volatile List<? extends CompletableFuture<?>> reads = List.of();
      RailbedBuildQueue.Preview previewTask;
      String previewKey = "";
      CompoundTag previewResponse;
      long previewComputations;
      final List<RailbedPath.Path> paths = new ArrayList<>();
      List<RailbedPath.Path> candidates = List.of();
      CompletableFuture<?> pending;
      CompletableFuture<?> preview;
      String sourceStatus = "";
      List<RailbedPath.Path> surrounding = List.of();
      double width;
      RoadGeometry.Structure mode = RoadGeometry.Structure.AUTO;
      RailbedOptions options = RailbedOptions.DEFAULT;
      UUID saved;
      UUID plannerSession;

      void cancel() {
         this.cancelled.set(true);
         this.reads.forEach(f -> f.cancel(false));
      }

      void reads(List<? extends CompletableFuture<?>> tasks) {
         this.reads = List.copyOf(tasks);
         if (this.cancelled.get()) {
            this.reads.forEach(f -> f.cancel(false));
         }
      }

      Draft(ServerPlayer player, BlockPos a) {
         this.dimension = RailbedTool.dimension(player);
         this.a = a.immutable();
      }
   }
}
