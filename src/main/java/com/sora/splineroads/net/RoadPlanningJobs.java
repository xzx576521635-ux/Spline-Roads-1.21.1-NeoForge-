package com.sora.splineroads.net;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.world.LaneRamps;
import com.sora.splineroads.world.ManualPlanner;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class RoadPlanningJobs {
   private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), r -> {
      Thread t = new Thread(r, "SR-route-planner");
      t.setDaemon(true);
      t.setPriority(4);
      return t;
   }, new AbortPolicy());
   private static final Map<UUID, RoadPlanningJobs.Job> ACTIVE = new ConcurrentHashMap<>();
   private static final LongAdder prepareNanos = new LongAdder();
   private static final LongAdder workerNanos = new LongAdder();
   private static final LongAdder finishNanos = new LongAdder();

   public static void cancel(UUID player) {
      RoadPlanningJobs.Job old = ACTIVE.remove(player);
      if (old != null) {
         old.stop();
      }
   }

   public static void cancel(UUID player, long request) {
      RoadPlanningJobs.Job old = ACTIVE.get(player);
      if (old != null && old.request == request && ACTIVE.remove(player, old)) {
         old.stop();
      }
   }

   public static int activeCount() {
      return ACTIVE.size();
   }

   public static void stop() {
      for (UUID id : List.copyOf(ACTIVE.keySet())) {
         cancel(id);
      }

      WORKER.getQueue().clear();
   }

   public static String metrics() {
      return "prepareMs="
         + prepareNanos.sum() / 1000000L
         + " workerMs="
         + workerNanos.sum() / 1000000L
         + " finishMs="
         + finishNanos.sum() / 1000000L
         + " "
         + LaneRamps.previewMetrics();
   }

   private static void recordPhase(String phase, long started, LongAdder total) {
      long elapsed = System.nanoTime() - started;
      total.add(elapsed);
      if (elapsed >= 100000000L || Boolean.getBoolean("sr.profile")) {
         System.getLogger("SplineRoads/planner")
            .log(Level.INFO, String.format(Locale.ROOT, "SR preview %s_ms=%.3f active=%d", phase, (double)elapsed / 1000000.0, ACTIVE.size()));
      }
   }

   public static void begin(ServerPlayer player, ItemStack tool, CompoundTag command) {
      UUID owner = player.getUUID();
      cancel(owner);
      RoadPlanningJobs.Job job = new RoadPlanningJobs.Job(command.getLong("Request"));
      ACTIVE.put(owner, job);
      long prepared = System.nanoTime();

      LaneRamps.PreviewWork work;
      label87: {
         try (RoadPlanningBudget budget = RoadPlanningBudget.cancellable("预览快照准备", job.cancelled::get)) {
            work = LaneRamps.preparePreview(player, tool, command);
            RoadPlanningBudget.check();
            break label87;
         } catch (RuntimeException var18) {
            ACTIVE.remove(owner, job);
            if (!job.cancelled.get()) {
               throw var18;
            }
         } finally {
            recordPhase("prepare", prepared, prepareNanos);
         }

         return;
      }

      MinecraftServer server = player.getServer();
      if (server == null) {
         cancel(owner);
         throw new IllegalArgumentException("服务器已关闭");
      } else if (!job.cancelled.get() && ACTIVE.get(owner) == job) {
         String dimension = player.level().dimension().location().toString();
         CompoundTag t = command.copy();
         submit(player, tool, t, work, job, dimension, 0, null);
      }
   }

   private static void submit(
      ServerPlayer player,
      ItemStack tool,
      CompoundTag t,
      LaneRamps.PreviewWork work,
      RoadPlanningJobs.Job job,
      String dimension,
      int attempt,
      LaneRamps.CandidateRejected rejection
   ) {
      MinecraftServer server = player.getServer();
      UUID owner = player.getUUID();
      job.task = new FutureTask<>(
         () -> {
            LaneRamps.PreviewRoute route = null;
            RuntimeException error = null;
            long computed = System.nanoTime();

            try (RoadPlanningBudget budget = RoadPlanningBudget.cancellable("匝道路线/净空搜索", job.cancelled::get)) {
               route = work.compute(attempt);
            } catch (RuntimeException var24) {
               error = var24;
            } finally {
               recordPhase("worker", computed, workerNanos);
               RoadQueries.clearThreadCache();
            }

            LaneRamps.PreviewRoute result = route;
            RuntimeException failure = error;
            server.execute(
               () -> {
                  if (ACTIVE.get(owner) == job && !job.cancelled.get()) {
                     if (server.getPlayerList().getPlayer(owner) != player) {
                        cancel(owner);
                     } else {
                        try {
                           if (!player.level().dimension().location().toString().equals(dimension)
                              || player.getMainHandItem() != tool && player.getOffhandItem() != tool && !ManualPlanner.ownsTool(player, tool)) {
                              throw new IllegalArgumentException("维度或所持工具已改变，旧计算已丢弃");
                           }

                           if (failure == null) {
                              long finished = System.nanoTime();

                              try (RoadPlanningBudget budget = RoadPlanningBudget.cancellable("预览事务校验", job.cancelled::get)) {
                                 CompoundTag reply;
                                 try {
                                    reply = LaneRamps.finishPreview(player, tool, t, work, result);
                                 } catch (IllegalArgumentException var33) {
                                    LaneRamps.CandidateRejected rejected = LaneRamps.candidateRejection(var33);
                                    if (!work.alternative(attempt + 1)) {
                                       throw rejected;
                                    }

                                    submit(player, tool, t, work, job, dimension, attempt + 1, rejected);
                                    return;
                                 }

                                 RoadPlanningBudget.check();
                                 ACTIVE.remove(owner, job);
                                 if (!job.cancelled.get()) {
                                    RoadNetwork.open(player, reply);
                                 }

                                 return;
                              } finally {
                                 recordPhase("finish", finished, finishNanos);
                              }
                           }

                           if (rejection == null || !(failure instanceof IllegalArgumentException)) {
                              throw failure;
                           }

                           if (!work.alternative(attempt + 1)) {
                              throw rejection;
                           }

                           submit(player, tool, t, work, job, dimension, attempt + 1, rejection);
                        } catch (RuntimeException var36) {
                           ACTIVE.remove(owner, job);
                           if (!job.cancelled.get()) {
                              failed(player, t, var36);
                           }

                           return;
                        } finally {
                           if (job.cancelled.get() && ItemNBT.get(tool).getCompound("LanePreview").getLong("Request") == job.request) {
                              ItemNBT.edit(tool).remove("LanePreview");
                           }
                        }
                     }
                  }
               }
            );
            return null;
         }
      );

      try {
         if (!job.cancelled.get() && ACTIVE.get(owner) == job) {
            WORKER.execute(job.task);
         } else {
            job.task.cancel(true);
         }
      } catch (RejectedExecutionException var11) {
         ACTIVE.remove(owner);
         job.stop();
         throw new IllegalArgumentException("匝道规划队列已满，请稍后重试（未写入道路）");
      }
   }

   private static void failed(ServerPlayer p, CompoundTag t, RuntimeException e) {
      System.getLogger("SplineRoads/planner").log(e instanceof RoadPlanningBudget.Aborted ? Level.WARNING : Level.INFO, "Ramp preview: " + e.getMessage());
      if (!(e instanceof IllegalArgumentException) && !(e instanceof RoadPlanningBudget.Aborted)) {
         System.getLogger("SplineRoads/planner").log(Level.ERROR, "Preview error", e);
      }

      CompoundTag reply = new CompoundTag();
      reply.putString("Kind", "laneRampCheck");
      reply.putLong("Request", t.getLong("Request"));
      reply.putString("Dimension", p.level().dimension().location().toString());
      reply.putString("Error", e.getMessage() == null ? "匝道预览失败，详见 latest.log" : e.getMessage());
      LaneRamps.conflictRoads(reply, p, reply.getString("Error"));
      RoadNetwork.open(p, reply);
   }

   private RoadPlanningJobs() {
   }

   static {
      WORKER.allowCoreThreadTimeOut(true);
   }

   private static final class Job {
      final long request;
      final AtomicBoolean cancelled = new AtomicBoolean();
      volatile FutureTask<Void> task;

      Job(long request) {
         this.request = request;
      }

      void stop() {
         this.cancelled.set(true);
         if (this.task != null) {
            this.task.cancel(true);
         }

         RoadPlanningJobs.WORKER.purge();
      }
   }
}
