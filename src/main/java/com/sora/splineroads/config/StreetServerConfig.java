package com.sora.splineroads.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;

public final class StreetServerConfig {
   public static final ModConfigSpec SPEC;
   public static final IntValue MAX_CHUNKS;
   public static final IntValue MAX_ROADS;
   public static final IntValue MAX_JUNCTIONS;
   public static final IntValue CAPTURE_COLUMNS;
   public static final IntValue WORK_MICROS;
   public static final IntValue MAX_UNIT_CELLS;
   public static final IntValue MAX_TASK_CELLS;
   public static final IntValue MAX_TASKS;
   public static final IntValue PERMISSION;
   public static final IntValue PREPARE_MILLIS;
   public static final IntValue UNIT_INTERVAL;
   public static final BooleanValue DEFAULT_LEFT;

   private StreetServerConfig() {
   }

   static {
      Builder b = new Builder();
      b.push("streetPlanner");
      MAX_CHUNKS = b.comment("Selected chunks per plan, hard maximum 1024").defineInRange("maxChunks", 1024, 16, 1024);
      MAX_ROADS = b.defineInRange("maxRoads", 160, 4, 160);
      MAX_JUNCTIONS = b.defineInRange("maxJunctions", 80, 1, 128);
      CAPTURE_COLUMNS = b.defineInRange("captureColumnsPerTick", 512, 16, 2048);
      WORK_MICROS = b.defineInRange("captureMicrosPerTick", 3000, 250, 10000);
      MAX_UNIT_CELLS = b.comment("Maximum changed world/ownership cells in one atomic connected construction unit")
         .defineInRange("maxUnitCells", 120000, 4096, 200000);
      MAX_TASK_CELLS = b.defineInRange("maxTaskCells", 1000000, 10000, 2000000);
      MAX_TASKS = b.defineInRange("retainedTasksPerDimension", 8, 1, 32);
      PREPARE_MILLIS = b.comment("Pre-write validation deadline; completed atomic writes are never interrupted")
         .defineInRange("prepareMillis", 5000, 250, 10000);
      UNIT_INTERVAL = b.comment("Minimum server ticks between native atomic construction units").defineInRange("unitIntervalTicks", 10, 1, 100);
      PERMISSION = b.comment("Required operator permission level; 0 allows players with build permission").defineInRange("permissionLevel", 0, 0, 4);
      DEFAULT_LEFT = b.define("defaultLeftTraffic", false);
      b.pop();
      SPEC = b.build();
   }
}
