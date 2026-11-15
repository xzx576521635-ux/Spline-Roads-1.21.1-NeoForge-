package com.sora.splineroads.config;

import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.core.StreetPlan;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.EnumValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;

public final class RoadClientConfig {
   public static final ModConfigSpec SPEC;
   public static final EnumValue<RoadSlicing.Connection> SPLIT_CONNECTION;
   public static final EnumValue<StreetPlan.Style> STREET_STYLE;
   public static final BooleanValue PERF_OVERLAY;
   public static final BooleanValue SHOW_SIGNS;
   public static final EnumValue<RoadClientConfig.SurfaceBackend> SURFACE_BACKEND;
   public static final IntValue TERRAIN_SECTIONS_PER_FRAME;
   public static final IntValue VBO_UPLOAD_MICROS;
   public static final IntValue INACTIVE_VBO_CACHE_MIB;
   public static final IntValue UNLOADED_VBO_CACHE_MIB;
   public static final IntValue TERRAIN_CACHE_MIB;
   public static final IntValue COLD_ROAD_CACHE_MIB;
   public static final IntValue DISTANT_ROAD_CHUNKS;

   private RoadClientConfig() {
   }

   static {
      Builder b = new Builder();
      b.push("rendering");
      SHOW_SIGNS = b.comment(
            "Show road signs by default. /sr signs off skips sign baking and drawing without changing saved signs; /sr signs on restores display."
         )
         .define("showSigns", true);
      PERF_OVERLAY = b.comment("Show SR performance overlay; toggle with /sr perf.").define("perfOverlay", false);
      SURFACE_BACKEND = b.comment(
            new String[]{
               "AUTO: terrain ONLY while an Iris/Oculus shader pack is actually rendering; otherwise VBO.",
               "TERRAIN submits asphalt/paint through chunk block rendering, enabling terrain shader materials.",
               "Puddle appearance still depends on the shader pack. VBO is the legacy compatibility fallback.",
               "AUTO follows shader toggles at runtime. Manual TERRAIN/VBO overrides are retained; no world migration."
            }
         )
         .defineEnum("surfaceBackend", RoadClientConfig.SurfaceBackend.AUTO);
      TERRAIN_SECTIONS_PER_FRAME = b.comment(
            new String[]{
               "Maximum 16x16x16 model sections published/rebuilt per frame.", "A time budget also applies; geometry is computed off the render thread."
            }
         )
         .defineInRange("terrainSectionsPerFrame", 4, 1, 32);
      VBO_UPLOAD_MICROS = b.comment("Soft render-thread budget for infrastructure VBO upload (microseconds).").defineInRange("vboUploadMicros", 900, 200, 5000);
      INACTIVE_VBO_CACHE_MIB = b.comment("Estimated MiB budget for dormant vertex-encoding VBOs. 0 disables retention; shared CPU geometry is not duplicated.")
         .defineInRange("inactiveVboCacheMiB", 64, 0, 512);
      UNLOADED_VBO_CACHE_MIB = b.comment(
            new String[]{
               "Estimated MiB budget for VBO regions with no loaded chunks, across both vertex encodings, including distant parts of an otherwise active road.",
               "0 disables retention. Reclamation is spread across frames; prepared CPU geometry remains available for local re-upload on return."
            }
         )
         .defineInRange("unloadedVboCacheMiB", 64, 0, 512);
      TERRAIN_CACHE_MIB = b.comment("Estimated MiB budget for unloaded/inactive terrain tiles (256 bytes/quad). 0 disables retention.")
         .defineInRange("terrainCacheMiB", 64, 0, 512);
      COLD_ROAD_CACHE_MIB = b.comment(
            "Estimated MiB retained for roads outside loaded chunks, including CPU geometry and both VBO encodings. Real delete/edit invalidates immediately."
         )
         .defineInRange("coldRoadCacheMiB", 128, 0, 512);
      DISTANT_ROAD_CHUNKS = b.comment(
            new String[]{
               "When Distant Horizons is installed, keep the real road VBO visible for at least this many chunks.",
               "This is what fills the gap between the vanilla render distance and DH LOD, so roads do not",
               "turn into a shadow-only silhouette in the distance. Only applies while DH is loaded."
            }
         )
         .defineInRange("distantRoadChunks", 24, 0, 64);
      b.pop();
      b.push("tools");
      SPLIT_CONNECTION = b.defineEnum("splitConnection", RoadSlicing.Connection.CONTINUOUS);
      STREET_STYLE = b.defineEnum("streetStyle", StreetPlan.Style.DOWNTOWN);
      b.pop();
      SPEC = b.build();
   }

   public static enum SurfaceBackend {
      AUTO,
      TERRAIN,
      VBO;
   }
}
