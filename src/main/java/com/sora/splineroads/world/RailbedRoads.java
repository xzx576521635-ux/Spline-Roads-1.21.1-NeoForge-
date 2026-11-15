package com.sora.splineroads.world;

import com.sora.splineroads.compat.mtr.MtrRailAccess;
import com.sora.splineroads.core.RailbedGeometry;
import com.sora.splineroads.core.RailbedOptions;
import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RailbedProfile;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadProfile;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class RailbedRoads {
   private static final Deque<Runnable> queue = new ArrayDeque<>();
   private static final Set<CompletableFuture<?>> active = new HashSet<>();

   static RailbedRoads.Preview preview(
      ServerLevel level,
      ServerPlayer player,
      UUID id,
      List<RailbedPath.Path> paths,
      double width,
      RoadGeometry.Structure mode,
      RailbedOptions options,
      List<RailbedPath.Path> surrounding
   ) {
      return preview(level, player, id, paths, width, mode, options, surrounding, () -> false);
   }

   static RailbedRoads.Preview preview(
      ServerLevel level,
      ServerPlayer player,
      UUID id,
      List<RailbedPath.Path> paths,
      double width,
      RoadGeometry.Structure mode,
      RailbedOptions options,
      List<RailbedPath.Path> surrounding,
      BooleanSupplier cancelled
   ) {
      RailbedRoads.Preview result = new RailbedRoads.Preview(level);
      enqueue(
         result.future,
         () -> {
            try (RoadPlanningBudget budget = RoadPlanningBudget.cancellable("铁路路基预览", () -> result.future.isCancelled() || cancelled.getAsBoolean())) {
               RoadPlanningBudget.check();
               require(level, player, id, paths);
               RoadData data = result.data;
               data.adoptRailbeds(level);
               RoadRecord record = record(data, player, id, paths, width, mode, options);

               List<RoadIndex.Built> planned;
               try (RoadExternalClearance external = RoadExternalClearance.open(paths, surrounding)) {
                  planned = data.prepareAssembly(
                     level,
                     player,
                     List.of(new RoadIndex.Built(record)),
                     data.index.roads.containsKey(id) ? Set.of(id) : Set.of(),
                     Set.of(),
                     List.of(),
                     result.token
                  );
               }

               if (result.sourceRevision != MtrRailAccess.revision(result.dimension)) {
                  throw new IllegalArgumentException("MTR 轨道已改变，请重新预览");
               }

               RoadRecord road = planned.stream().filter(r -> r.record.id().equals(id)).findFirst().orElseThrow().record;
               RoadPlanningBudget.check();
               result.future.complete(entry(road));
            } finally {
               if (cancelled.getAsBoolean() || result.future.isCancelled()) {
                  result.future.cancel(false);
                  if (player != null) {
                     RoadData.forgetPreview(player.getUUID(), result.token);
                  }
               }
            }
         }
      );
      return result;
   }

   static CompletableFuture<RailbedStore.Entry> submit(
      ServerLevel level,
      ServerPlayer player,
      UUID id,
      List<RailbedPath.Path> paths,
      double width,
      RoadGeometry.Structure mode,
      RailbedOptions options,
      List<RailbedPath.Path> surrounding
   ) {
      return submit(level, player, id, paths, width, mode, options, surrounding, null);
   }

   static CompletableFuture<RailbedStore.Entry> submit(
      ServerLevel level,
      ServerPlayer player,
      UUID id,
      List<RailbedPath.Path> paths,
      double width,
      RoadGeometry.Structure mode,
      RailbedOptions options,
      List<RailbedPath.Path> surrounding,
      CompoundTag planner
   ) {
      CompletableFuture<RailbedStore.Entry> future = new CompletableFuture<>();
      future.whenComplete((value, error) -> {
         if (error != null && !(error instanceof CancellationException) && player != null && !player.isRemoved()) {
            player.displayClientMessage(Component.literal(error.getMessage() == null ? "铁路路基操作失败" : error.getMessage()), true);
         }
      });
      Runnable build = () -> {
         require(level, player, id, paths);
         RoadData data = RoadData.get(level);
         data.adoptRailbeds(level);
         ResourceLocation dim = level.dimension().location();
         String dimension = dim.getNamespace() + "/" + dim.getPath();
         long revision = MtrRailAccess.revision(dimension);
         UUID token = UUID.randomUUID();
         List<RoadIndex.Built> proposed = paths == null ? List.of() : List.of(new RoadIndex.Built(record(data, player, id, paths, width, mode, options)));

         try (RoadExternalClearance external = RoadExternalClearance.open(paths == null ? List.of() : paths, paths == null ? List.of() : surrounding)) {
            data.prepareAssembly(level, player, proposed, data.index.roads.containsKey(id) ? Set.of(id) : Set.of(), Set.of(), List.of(), token);
         }

         if (paths != null && revision != MtrRailAccess.revision(dimension)) {
            throw new IllegalArgumentException("MTR 轨道已改变，请重新预览");
         } else {
            data.buildPrepared(level, player, token);
            if (player != null) {
               player.displayClientMessage(Component.literal(paths == null ? "铁路路基已删除；MTR 轨道保持原样" : "铁路路基建造完成"), true);
            }
         }
      };
      CompoundTag receipt = planner == null ? null : planner.copy();
      enqueue(future, () -> {
         if (receipt == null) {
            build.run();
         } else {
            ManualPlanner.edit(player, receipt, () -> {
               build.run();
               return "";
            });
         }

         future.complete(paths == null ? null : RoadData.get(level).railbeds.entries.get(id));
      });
      return future;
   }

   private static void require(ServerLevel level, ServerPlayer player, UUID id, List<RailbedPath.Path> paths) {
      if (player == null || !player.isRemoved() && player.serverLevel() == level && player.mayBuild()) {
         RoadData data = RoadData.get(level);
         RoadIndex.Built old = data.index.roads.get(id);
         if (old != null) {
            if (!old.record.railway()) {
               throw new IllegalArgumentException("目标不是铁路路基");
            }

            if (player != null) {
               RoadData.requireOwner(player, old.record.owner());
            }
         } else if (paths == null) {
            throw new IllegalArgumentException("铁路路基已不存在");
         }

         if (paths != null) {
            String unsupported = RailbedGeometry.unsupported(paths);
            if (unsupported != null) {
               throw new IllegalArgumentException(unsupported);
            }

            String identity = RailbedStore.Entry.identity(paths);

            for (RailbedStore.Entry e : data.railbeds.entries.values()) {
               if (!e.road.id().equals(id) && e.identity().equals(identity)) {
                  throw new IllegalArgumentException("这段轨道已有 SR 路基，请编辑已有结构");
               }
            }
         }
      } else {
         throw new IllegalArgumentException("建设权限或维度已改变");
      }
   }

   private static RoadRecord record(
      RoadData data, ServerPlayer player, UUID id, List<RailbedPath.Path> paths, double width, RoadGeometry.Structure mode, RailbedOptions options
   ) {
      RailbedGeometry.Plan plan = RailbedGeometry.plan(paths, width, mode);
      RoadGeometry.Mesh mesh = plan.mesh();
      RoadGeometry.Settings settings = mesh.settings()
         .structure(mode)
         .options(
            mesh.settings()
               .options()
               .railbed(new RailbedProfile(paths, width, options))
               .infrastructure(RoadInfrastructure.Config.DEFAULT.bridge(options.bridge()).tunnel(options.tunnel()))
               .outerRail(RoadProfile.OuterRail.ON)
         );
      RoadIndex.Built old = data.index.roads.get(id);
      UUID owner = old != null ? old.record.owner() : (player == null ? new UUID(141L, 1L) : player.getUUID());
      RailbedPath.Node first = paths.get(0).segments().get(0).from();
      RailbedPath.Node last = paths.get(0).segments().get(paths.get(0).segments().size() - 1).to();
      return new RoadRecord(
         id,
         owner,
         RailbedCodec.pos(first),
         RailbedCodec.pos(last),
         new RoadGeometry.Node(mesh.first().center(), 0.0, 0.0),
         new RoadGeometry.Node(mesh.last().center(), 0.0, 0.0),
         settings,
         false,
         5,
         List.of(),
         0,
         143,
         null,
         mesh.samples(),
         old == null ? null : old.record.furniturePhase()
      );
   }

   static RailbedStore.Entry entry(RoadRecord road) {
      RailbedProfile profile = road.settings().options().railbed();
      return new RailbedStore.Entry(road, profile.paths(), profile.requestedWidth(), road.settings().structure(), profile.fittings());
   }

   static void sync(RoadData data, List<RoadIndex.Built> built, Set<UUID> removed) {
      for (UUID id : removed) {
         if (!data.index.roads.containsKey(id)) {
            data.railbeds.entries.remove(id);
         }
      }

      for (RoadIndex.Built road : built) {
         if (road.record.railway() && !road.record.legacyRailway()) {
            data.railbeds.entries.put(road.record.id(), entry(road.record));
         }
      }
   }

   private static void enqueue(CompletableFuture<?> future, Runnable action) {
      if (queue.size() >= 16) {
         throw new IllegalArgumentException("道路施工队列已满，请稍后重试");
      } else {
         active.add(future);
         queue.add(() -> {
            try {
               if (!future.isCancelled()) {
                  action.run();
               }
            } catch (RuntimeException var6) {
               future.completeExceptionally(var6);
            } finally {
               active.remove(future);
            }
         });
      }
   }

   static void tick() {
      if (!queue.isEmpty()) {
         queue.removeFirst().run();
      }
   }

   static void stop() {
      for (CompletableFuture<?> future : active) {
         future.cancel(false);
      }

      active.clear();
      queue.clear();
   }

   private RailbedRoads() {
   }

   static final class Preview {
      final ServerLevel level;
      final RoadData data;
      final UUID token = UUID.randomUUID();
      final String dimension;
      final long sourceRevision;
      final CompletableFuture<RailbedStore.Entry> future = new CompletableFuture<>();

      Preview(ServerLevel level) {
         this.level = level;
         this.data = RoadData.get(level);
         ResourceLocation dim = level.dimension().location();
         this.dimension = dim.getNamespace() + "/" + dim.getPath();
         this.sourceRevision = MtrRailAccess.revision(this.dimension);
      }

      boolean valid() {
         return !this.future.isCancelled()
            && this.future.isDone()
            && !this.future.isCompletedExceptionally()
            && this.sourceRevision == MtrRailAccess.revision(this.dimension)
            && this.data.preparedValid(this.level, this.token);
      }
   }
}
