package com.sora.splineroads.core;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public final class LatestPlanningTask<T> {
   private final ThreadPoolExecutor executor;
   private final String phase;
   private LatestPlanningTask.Request current;

   public LatestPlanningTask(String name) {
      this.phase = name;
      this.executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
         Thread thread = new Thread(task, name);
         thread.setDaemon(true);
         return thread;
      });
      this.executor.allowCoreThreadTimeOut(true);
   }

   public synchronized void cancel() {
      LatestPlanningTask.Request previous = this.current;
      this.current = null;
      if (previous != null) {
         previous.cancelled.set(true);
         previous.task.cancel(true);
         this.executor.purge();
      }
   }

   public synchronized void submit(Callable<T> computation, BiConsumer<T, Throwable> completion) {
      this.cancel();
      LatestPlanningTask.Request request = new LatestPlanningTask.Request();
      this.current = request;
      request.task = new FutureTask<>(() -> {
         T result = null;
         Throwable failure = null;

         try (RoadPlanningBudget scope = RoadPlanningBudget.cancellable(this.phase, request.cancelled::get)) {
            RoadPlanningBudget.check();
            result = computation.call();
            RoadPlanningBudget.check();
         } catch (Exception var19) {
            failure = var19;
         } finally {
            RoadQueries.clearThreadCache();
         }

         if (!request.cancelled.get()) {
            completion.accept(result, failure);
         }

         synchronized (this) {
            if (this.current == request) {
               this.current = null;
            }

            return null;
         }
      });
      this.executor.execute(request.task);
   }

   public int queued() {
      return this.executor.getQueue().size();
   }

   private static final class Request {
      final AtomicBoolean cancelled = new AtomicBoolean();
      FutureTask<Void> task;
   }
}
