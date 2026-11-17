package com.sora.splineroads.world;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Coarse, always-on timing buckets for the chunk-load work that dominates world entry and
 * exploration. Prints at most one summary line every few seconds, so a normal session adds a
 * handful of log lines and we can see where the time goes without a profiler.
 */
public final class RoadPerf {
   private static final Logger LOG = LogManager.getLogger("SplineRoads/performance");
   private static long window = System.nanoTime();
   private static long repairs;
   private static long repairNanos;
   private static long repairMax;
   private static long markers;
   private static long markerNanos;
   private static long markerMax;
   private static long heightmaps;
   private static long heightmapNanos;
   private static long heightmapMax;

   static void repair(long nanos) {
      repairs++;
      repairNanos += nanos;
      repairMax = Math.max(repairMax, nanos);
   }

   static void markers(long nanos) {
      markers++;
      markerNanos += nanos;
      markerMax = Math.max(markerMax, nanos);
   }

   static void heightmaps(long nanos) {
      heightmaps++;
      heightmapNanos += nanos;
      heightmapMax = Math.max(heightmapMax, nanos);
   }

   /** Prints one summary line when there was activity in the last few seconds. */
   static void maybeLog() {
      long now = System.nanoTime();
      long elapsed = now - window;

      if (elapsed < 5_000_000_000L || repairs + markers + heightmaps == 0L) {
         return;
      }

      LOG.info(
         "SR chunk perf/{}s: repair {} calls {} ms (max {}), markers {} calls {} ms (max {}), heightmaps {} calls {} ms (max {})",
         elapsed / 1_000_000_000L,
         repairs,
         repairNanos / 1_000_000L,
         repairMax / 1_000_000L,
         markers,
         markerNanos / 1_000_000L,
         markerMax / 1_000_000L,
         heightmaps,
         heightmapNanos / 1_000_000L,
         heightmapMax / 1_000_000L
      );
      window = now;
      repairs = repairNanos = repairMax = 0L;
      markers = markerNanos = markerMax = 0L;
      heightmaps = heightmapNanos = heightmapMax = 0L;
   }

   private RoadPerf() {
   }
}
