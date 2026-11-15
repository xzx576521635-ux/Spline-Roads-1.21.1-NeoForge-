package com.sora.splineroads.compat.dh;

import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockColorOverrideEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockStateWrapperCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockTextureOverrideEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.client.ClientRoads;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.world.RoadBlocks;
import com.sora.splineroads.world.RoadMarker;
import com.sora.splineroads.world.RoadIndex;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Optional Distant Horizons integration.
 *
 * <p>Spline Roads renders its driving surface as geometry on top of invisible
 * {@code road_collision}/{@code road_infill} blocks. Distant Horizons builds its LODs from
 * real block states, so those blocks used to be baked as their filler material
 * (grass/stone/dirt) or skipped as transparent because the colliders do not occlude light.
 * Distant roads consequently looked like piles of grass/stone.</p>
 *
 * <p>DH exposes block wrapper/color/texture override events. We make the road blocks opaque
 * and give them a flat, slightly brightened asphalt color/texture in DH. The actual block
 * model used by map/LOD color readers is handled in
 * {@code RoadTerrainModels.TerrainModel}.</p>
 *
 * <p>No custom DH geometry is created here: Spline Roads' lane paint and street furniture
 * are not blocks, and drawing them through DH's generic box renderer proved too intrusive
 * (large coarse boxes, black undersides, abrupt distance transitions). The block based
 * surface treatment below is stable and compatible with other mods.</p>
 *
 * <p>This class is loaded reflectively only when {@code distanthorizons} is present, so the
 * main mod has no hard dependency on DH.</p>
 */
public final class DhRoadTerrain {
   private static final int ASPHALT_R = 0x88;
   private static final int ASPHALT_G = 0x86;
   private static final int ASPHALT_B = 0x87;
   private static final Map<Long, Integer> PAINT_CELLS = new ConcurrentHashMap<>();
   private static final Map<Long, Integer> ROAD_TOP = new ConcurrentHashMap<>();
   private static final int STONE_R = 0x60;
   private static final int STONE_G = 0x60;
   private static final int STONE_B = 0x60;
   private static boolean installed;
   private static long paintRevision = Long.MIN_VALUE;
   private static long lastPaintRefresh;

   private DhRoadTerrain() {
   }

   public static void init() {
      if (installed) {
         return;
      }

      installed = true;

      DhApiEventRegister.on(DhApiBlockStateWrapperCreatedEvent.class, new DhApiBlockStateWrapperCreatedEvent() {
         @Override
         public void blockStateWrapperCreated(DhApiEventParam<DhApiBlockStateWrapperCreatedEvent.EventParam> event) {
            BlockState state = stateOf(event.value.getBlockStateWrapper());
            if (isDeck(state)) {
               // The collider blocks are non-occluding in vanilla (light opacity 0), which makes
               // DH skip them and bake the filler blocks underneath instead.
               event.value.setOpacity(16);
            }

            if (isMarker(state)) {
               RoadMarker.Kind kind = state.getValue(RoadMarker.KIND);
               // Paint is delivered by the road collider state now; legacy paint markers
               // stay transparent so they do not create one-block bumps in distant LODs.
               if (kind != RoadMarker.Kind.PAINT_WHITE && kind != RoadMarker.Kind.PAINT_YELLOW) {
                  event.value.setOpacity(16);
               }

               event.value.setAllowApiColorOverride(true);
            }

            if (isRoadSurface(state)) {
               event.value.setAllowApiColorOverride(true);
               event.value.setAllowApiTextureOverride(true);
            }
         }
      });

      DhApiEventRegister.on(DhApiBlockColorOverrideEvent.class, new DhApiBlockColorOverrideEvent() {
         @Override
         public void onBlockColorOverridden(DhApiEventParam<DhApiBlockColorOverrideEvent.EventParam> event) {
            BlockState state = stateOf(event.value.getBlockStateWrapper());
            if (isMarker(state)) {
               int rgb = state.getValue(RoadMarker.KIND).rgb();
               event.value.setColor(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF);
               return;
            }

            if (!isRoadSurface(state)) {
               return;
            }

            int x = event.value.getBlockPosX();
            int y = event.value.getBlockPosY();
            int z = event.value.getBlockPosZ();
            long column = BlockPos.asLong(x, 0, z);

            if (isDeck(state)) {
               Boolean top = topAt(x, y, z);
               if (top == null) {
                  Integer topY = ROAD_TOP.get(column);
                  if (topY != null) {
                     top = y == topY;
                  }
               }

               if (Boolean.FALSE.equals(top)) {
                  // Only the top layer is road surface. Sides and the bridge underside must not
                  // be painted as bright asphalt; making them transparent also avoids the large
                  // pale slabs that were visible when looking up from below.
                  event.value.setColor(0, 0, 0, 0);
                  return;
               }

               if (Boolean.TRUE.equals(top)) {
                  Integer paint = PAINT_CELLS.get(BlockPos.asLong(x, y, z));
                  if (paint != null) {
                     event.value.setColor(paint >> 16 & 0xFF, paint >> 8 & 0xFF, paint & 0xFF);
                  } else {
                     event.value.setColor(ASPHALT_R, ASPHALT_G, ASPHALT_B);
                  }

                  return;
               }

               // No reliable world/LOD data yet: keep the whole column asphalt so a freshly
               // generated LOD is not baked as stone.
               event.value.setColor(ASPHALT_R, ASPHALT_G, ASPHALT_B);
               return;
            }

            Integer paint = PAINT_CELLS.get(BlockPos.asLong(x, y, z));
            if (paint != null) {
               event.value.setColor(paint >> 16 & 0xFF, paint >> 8 & 0xFF, paint & 0xFF);
            } else {
               event.value.setColor(ASPHALT_R, ASPHALT_G, ASPHALT_B);
            }
         }
      });

      DhApiEventRegister.on(DhApiBlockTextureOverrideEvent.class, new DhApiBlockTextureOverrideEvent() {
         @Override
         public void onBlockTextureOverridden(DhApiEventParam<DhApiBlockTextureOverrideEvent.EventParam> event) {
            if (isRoadSurface(stateOf(event.value.getBlockStateWrapper()))) {
               paintAsphalt(event.value);
            }
         }
      });

      NeoForge.EVENT_BUS.addListener(DhRoadTerrain::onClientTick);
   }

   private static void onClientTick(ClientTickEvent.Post event) {
      try {
         long now = System.nanoTime();
         if (now - lastPaintRefresh < 2000000000L) {
            return;
         }

         lastPaintRefresh = now;
         long revision = ClientRoads.INDEX.contentRevision();
         if (revision == paintRevision) {
            return;
         }

         paintRevision = revision;
         rebuildPaintCells();
      } catch (Throwable ignored) {
      }
   }

   /**
    * Lane paint is not made of blocks, so DH cannot see it directly. This builds a coarse
    * 1-block table of road surface cells that contain paint. DH's per-block color override
    * then paints those cells white/yellow in the LOD, which is enough to read as lane
    * markings at distance without adding any custom geometry.
    */
   private static void rebuildPaintCells() {
      PAINT_CELLS.clear();
      ROAD_TOP.clear();

      for (RoadIndex.Built road : ClientRoads.INDEX.roads.values()) {
         RoadSurface.Geometry geometry;
         try {
            geometry = RoadSurface.build(road.renderMesh(), List.of(), List.of());
         } catch (Throwable ignored) {
            continue;
         }

         for (RoadSurface.Face face : geometry.pavement()) {
            recordRoadTop(face);
         }

         for (RoadSurface.Face face : geometry.markings()) {
            List<RoadGeometry.V> points = face.points();
            if (points.size() < 3) {
               continue;
            }

            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;

            for (RoadGeometry.V point : points) {
               minX = Math.min(minX, point.x());
               maxX = Math.max(maxX, point.x());
               minZ = Math.min(minZ, point.z());
               maxZ = Math.max(maxZ, point.z());
               maxY = Math.max(maxY, point.y());
            }

            if (maxX - minX > 96.0 || maxZ - minZ > 96.0) {
               continue;
            }

            int rgb = face.color() & 0xFFFFFF;
            if (rgb == 0) {
               rgb = 0xFFFFFF;
            }

            int topY = (int)Math.floor(maxY) - 1;

            for (int x = (int)Math.floor(minX); x <= (int)Math.floor(maxX); x++) {
               for (int z = (int)Math.floor(minZ); z <= (int)Math.floor(maxZ); z++) {
                  if (inside(points, x + 0.5, z + 0.5)) {
                     PAINT_CELLS.put(BlockPos.asLong(x, topY, z), rgb);
                  }
               }
            }
         }
      }
   }

   private static boolean inside(List<RoadGeometry.V> polygon, double x, double z) {
      boolean inside = false;

      for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
         RoadGeometry.V a = polygon.get(i);
         RoadGeometry.V b = polygon.get(j);
         if ((a.z() > z) != (b.z() > z) && x < (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) {
            inside = !inside;
         }
      }

      return inside;
   }

   private static void recordRoadTop(RoadSurface.Face face) {
      List<RoadGeometry.V> points = face.points();
      if (points.size() < 3) {
         return;
      }

      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minZ = Double.MAX_VALUE;
      double maxZ = -Double.MAX_VALUE;
      double maxY = -Double.MAX_VALUE;

      for (RoadGeometry.V point : points) {
         minX = Math.min(minX, point.x());
         maxX = Math.max(maxX, point.x());
         minZ = Math.min(minZ, point.z());
         maxZ = Math.max(maxZ, point.z());
         maxY = Math.max(maxY, point.y());
      }

      if (maxX - minX > 96.0 || maxZ - minZ > 96.0) {
         return;
      }

      int topY = (int)Math.floor(maxY) - 1;

      for (int x = (int)Math.floor(minX); x <= (int)Math.floor(maxX); x++) {
         for (int z = (int)Math.floor(minZ); z <= (int)Math.floor(maxZ); z++) {
            if (inside(points, x + 0.5, z + 0.5)) {
               ROAD_TOP.put(BlockPos.asLong(x, 0, z), topY);
            }
         }
      }
   }

   private static BlockState stateOf(IDhApiBlockStateWrapper wrapper) {
      if (wrapper == null) {
         return null;
      }

      Object object = wrapper.getWrappedMcObject();
      return object instanceof BlockState state ? state : null;
   }

   private static boolean isDeck(BlockState state) {
      if (state == null) {
         return false;
      }

      Block block = state.getBlock();
      return block == SplineRoads.COLLIDER.get() || block == SplineRoads.FILLED_COLLIDER.get();
   }

   private static boolean isMarker(BlockState state) {
      return state != null && state.is(SplineRoads.MARKER.get());
   }

   /**
    * Returns whether this collider block is the top layer of the road deck, or null when the
    * client chunk is not available (far LOD generation).
    */
   private static Boolean topAt(int x, int y, int z) {
      ClientLevel level = Minecraft.getInstance().level;
      if (level == null) {
         return null;
      }

      try {
         BlockPos above = new BlockPos(x, y + 1, z);
         if (!level.hasChunkAt(above)) {
            return null;
         }

         return !RoadBlocks.isCollider(level.getBlockState(above));
      } catch (Throwable ignored) {
         return null;
      }
   }

   private static boolean isRoadSurface(BlockState state) {
      if (state == null) {
         return false;
      }

      Block block = state.getBlock();
      return isDeck(state) || block == SplineRoads.ROAD_BLOCK.get() || block == SplineRoads.ROAD_SLAB.get();
   }

   /**
    * Flat, slightly brightened asphalt. A noisy texture is deliberately avoided: DH's own
    * noise texture is applied on top of LODs and can turn high-contrast pixels into black
    * speckles at distance.
    */
   private static void paintAsphalt(DhApiBlockTextureOverrideEvent.EventParam param) {
      int width = param.getWidth();
      int height = param.getHeight();

      for (int u = 0; u < width; u++) {
         for (int v = 0; v < height; v++) {
            param.setColor(u, v, 255, ASPHALT_R, ASPHALT_G, ASPHALT_B);
         }
      }
   }
}
