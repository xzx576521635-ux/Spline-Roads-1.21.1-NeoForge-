package com.sora.splineroads.core;

import java.util.function.BooleanSupplier;

public final class RoadPlanningBudget implements AutoCloseable {
   private static final ThreadLocal<RoadPlanningBudget> CURRENT = new ThreadLocal<>();
   private final RoadPlanningBudget previous = CURRENT.get();
   private final long deadline;
   private final BooleanSupplier cancelled;
   private String phase;
   private boolean disabled;

   private RoadPlanningBudget(String phase, double seconds, BooleanSupplier cancelled) {
      this.phase = phase;
      this.cancelled = cancelled;
      long own = Double.isInfinite(seconds) ? Long.MAX_VALUE : System.nanoTime() + (long)(seconds * 1.0E9);
      this.deadline = this.previous == null ? own : Math.min(own, this.previous.deadline);
      CURRENT.set(this);
   }

   public static RoadPlanningBudget open(String phase, double seconds) {
      return open(phase, seconds, () -> false);
   }

   public static RoadPlanningBudget open(String phase, double seconds, BooleanSupplier cancelled) {
      return new RoadPlanningBudget(phase, seconds, cancelled);
   }

   public static RoadPlanningBudget cancellable(String phase) {
      return cancellable(phase, () -> false);
   }

   public static RoadPlanningBudget cancellable(String phase, BooleanSupplier cancelled) {
      return open(phase, Double.POSITIVE_INFINITY, cancelled);
   }

   public static void phase(String name) {
      RoadPlanningBudget b = CURRENT.get();
      if (b != null) {
         b.phase = name;
      }

      check();
   }

   public static void check() {
      RoadPlanningBudget b = CURRENT.get();
      if (b != null && !b.disabled) {
         if (Thread.currentThread().isInterrupted()) {
            throw new RoadPlanningBudget.Aborted("匝道计算已取消，未写入道路");
         } else {
            for (RoadPlanningBudget scope = b; scope != null; scope = scope.previous) {
               if (scope.cancelled.getAsBoolean()) {
                  throw new RoadPlanningBudget.Aborted("匝道计算已取消，未写入道路");
               }
            }

            if (b.deadline != Long.MAX_VALUE && System.nanoTime() - b.deadline >= 0L) {
               throw new RoadPlanningBudget.Aborted("道路计算达到本次时间预算，已停止而非继续卡住；未写入道路。阶段：" + b.phase + "。这不代表几何无解，可调整落点/路线后重试。");
            }
         }
      }
   }

   public static void committing() {
      for (RoadPlanningBudget b = CURRENT.get(); b != null; b = b.previous) {
         b.disabled = true;
      }
   }

   @Override
   public void close() {
      if (CURRENT.get() == this) {
         if (this.previous == null) {
            CURRENT.remove();
         } else {
            CURRENT.set(this.previous);
         }
      }
   }

   public static final class Aborted extends RuntimeException {
      public Aborted(String s) {
         super(s);
      }
   }
}
