package com.sora.splineroads.core;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class RoadTimings implements AutoCloseable {
   private static final Logger LOG = LogManager.getLogger("SplineRoads/performance");
   private final long started = System.nanoTime();
   private long previous = this.started;
   private final String operation;
   private final int roads;
   private final int edited;
   private final Map<String, Double> phases = new LinkedHashMap<>();

   private RoadTimings(String operation, int roads, int edited) {
      this.operation = operation;
      this.roads = roads;
      this.edited = edited;
   }

   public static RoadTimings start(String operation, int roads, int edited) {
      return new RoadTimings(operation, roads, edited);
   }

   public void stage(String phase) {
      long now = System.nanoTime();
      double ms = (double)(now - this.previous) / 1000000.0;
      this.phases.merge(phase, ms, Double::sum);
      this.previous = now;
      if (ms >= 1000.0 || Boolean.getBoolean("sr.profile")) {
         LOG.info("SR stage {}/{}: {} ms, elapsed={} ms", this.operation, phase, Math.round(ms), Math.round((double)(now - this.started) / 1000000.0));
      }
   }

   @Override
   public void close() {
      double total = (double)(System.nanoTime() - this.started) / 1000000.0;
      if (total >= 250.0 || Boolean.getBoolean("sr.profile")) {
         LOG.info("SR {}: {} ms, roads={}, requested={}, phases(ms)={}", this.operation, Math.round(total), this.roads, this.edited, this.phases);
      }
   }

   public static void reportLoad(long started, int roads) {
      double total = (double)(System.nanoTime() - started) / 1000000.0;
      if (total >= 100.0 || Boolean.getBoolean("sr.profile")) {
         LOG.info("SR load: {} ms, roads={}", Math.round(total), roads);
      }
   }
}
