package com.sora.splineroads.client;

import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadHandoffCohorts;
import com.sora.splineroads.core.RoadHandoffState;
import com.sora.splineroads.core.RoadLighting;
import com.sora.splineroads.core.RoadRaster;
import com.sora.splineroads.core.RoadRenderMesh;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.core.RoadTerrainMesh;
import com.sora.splineroads.core.RoadTerrainSourceWindows;
import com.sora.splineroads.core.TerrainEdgeFaces;
import com.sora.splineroads.core.TerrainEdgeGeometry;
import com.sora.splineroads.world.RoadBlocks;
import com.sora.splineroads.world.RoadFillEntity;
import com.sora.splineroads.world.RoadIndex;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import org.apache.logging.log4j.LogManager;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent.BakingCompleted;
import net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkEvent.Load;
import net.neoforged.neoforge.event.level.ChunkEvent.Unload;

@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT},
   bus = Bus.MOD
)
public final class RoadTerrainModels {
   private static final ModelProperty<List<BakedQuad>> QUADS = new ModelProperty();
   private static final ModelProperty<List<BakedQuad>> COVERS = new ModelProperty();
   private static final ModelProperty<Integer> FILL_FACES = new ModelProperty();
   private static final ChunkRenderTypeSet SOLID = ChunkRenderTypeSet.of(new RenderType[]{RenderType.solid()});
   private static final Map<RoadTerrainMesh.Section, Map<Integer, ModelData>> snapshots = new ConcurrentHashMap<>();
   private static final Map<RoadTerrainMesh.Section, Map<UUID, Map<Integer, List<BakedQuad>>>> parts = new HashMap<>();
   private static final Map<UUID, Set<RoadTerrainMesh.Section>> published = new HashMap<>();
   private static final Set<RoadTerrainMesh.Section> pending = new LinkedHashSet<>();
   private static final Map<UUID, RoadTerrainModels.Source> sources = new HashMap<>();
   private static final Map<RoadTerrainMesh.Chunk, Set<UUID>> chunkRoads = new HashMap<>();
   private static final Map<RoadTerrainModels.Tile, RoadTerrainModels.Prepared> active = new HashMap<>();
   private static final LinkedHashMap<RoadTerrainModels.Tile, RoadTerrainModels.Prepared> cache = new LinkedHashMap<>(16, 0.75F, true);
   private static final Set<RoadTerrainModels.Tile> waiting = new LinkedHashSet<>();
   private static final Set<RoadTerrainModels.Tile> incompleteSources = new LinkedHashSet<>();
   private static final Map<RoadTerrainModels.Tile, RoadTerrainModels.TileJob> jobs = new LinkedHashMap<>();
   private static final Set<RoadTerrainMesh.Chunk> streamDirty = new LinkedHashSet<>();
   private static final RoadHandoffCohorts<RoadTerrainMesh.Chunk> cohorts = new RoadHandoffCohorts<>();
   private static final Set<RoadTerrainMesh.Section> heldSections = new LinkedHashSet<>();
   private static final Map<RoadTerrainMesh.Chunk, Set<UUID>> cohortCoverage = new HashMap<>();
   private static final Map<UUID, Set<RoadTerrainMesh.Section>> transitionSections = new HashMap<>();
   private static final int CACHE_TILES = 2048;
   private static final int MAX_JOBS = 2;
   private static int cachedQuads;
   private static int lastCacheMiB = -1;
   private static final Map<UUID, Long> tileBytes = new HashMap<>();
   private static long retentionRevision;
   private static boolean sortWaiting;
   private static long preparedTiles;
   private static long cacheHits;
   private static long discardedTiles;
   private static long chunkEvents;
   private static long roadChunkEvents;
   private static volatile RoadTerrainModels.Assets loadedAssets;
   private static volatile long resourceRevision;
   private static long observedResource = Long.MIN_VALUE;
   private static RoadTerrainModels.Assets observedAssets;
   private static volatile boolean enabled;
   private static volatile boolean requested;
   private static String shaderStatus = "not queried";

   static long retentionRevision() {
      return retentionRevision;
   }

   private static void account(RoadTerrainModels.Tile tile, long delta) {
      if (delta != 0L) {
         tileBytes.merge(tile.road(), delta, Long::sum);
         if (tileBytes.get(tile.road()) == 0L) {
            tileBytes.remove(tile.road());
         }

         retentionRevision++;
      }
   }

   private static RoadTerrainModels.Prepared removeActive(RoadTerrainModels.Tile tile) {
      RoadTerrainModels.Prepared old = active.remove(tile);
      if (old != null) {
         account(tile, (long)(-old.quads()) * 256L);
      }

      return old;
   }

   private static RoadTerrainModels.Prepared removeCached(RoadTerrainModels.Tile tile) {
      RoadTerrainModels.Prepared old = cache.remove(tile);
      if (old != null) {
         cachedQuads = cachedQuads - old.quads();
         account(tile, (long)(-old.quads()) * 256L);
      }

      return old;
   }

   private static double distance(RoadTerrainMesh.Chunk chunk) {
      Vec3 p = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
      double dx = (double)chunk.x() * 16.0 + 8.0 - p.x;
      double dz = (double)chunk.z() * 16.0 + 8.0 - p.z;
      return dx * dx + dz * dz;
   }

   private static boolean loaded(RoadTerrainMesh.Chunk chunk) {
      ClientLevel level = Minecraft.getInstance().level;
      return level != null && level.getChunkSource().getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL, false) != null;
   }

   static boolean regionLoaded(RoadRenderMesh.Section region) {
      for (int x = 0; x < 4; x++) {
         for (int z = 0; z < 4; z++) {
            if (loaded(new RoadTerrainMesh.Chunk(region.x() * 4 + x, region.z() * 4 + z))) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean terrainModelsNeeded() {
      RoadClientConfig.SurfaceBackend choice = (RoadClientConfig.SurfaceBackend)RoadClientConfig.SURFACE_BACKEND.get();
      if (choice == RoadClientConfig.SurfaceBackend.TERRAIN) {
         return true;
      }

      // AUTO selects the terrain backend only when a shader pack is active and DH is absent.
      // Shader state can change after models are baked, so keep the wrappers whenever AUTO
      // could reach that state at all. With DH installed AUTO stays on VBO.
      return choice == RoadClientConfig.SurfaceBackend.AUTO && !ModList.get().isLoaded("distanthorizons");
   }

   @SubscribeEvent
   public static void wrap(ModifyBakingResult event) {
      // Wrapping every baked model costs tens of seconds on large packs (ModernFix reported
      // ~29 s for splineroads). The wrappers are only useful for the "terrain" surface
      // backend: it draws the road quads through the terrain block model. With Distant
      // Horizons installed AUTO always uses the VBO backend, so skip them entirely there.
      boolean terrainModels = terrainModelsNeeded();
      LogManager.getLogger("SplineRoads")
         .info("Terrain model wrapping {} (surface backend {})", terrainModels ? "enabled" : "skipped", RoadClientConfig.SURFACE_BACKEND.get());
      event.getModels()
         .replaceAll(
            (id, original) -> {
               if (!(id instanceof ModelResourceLocation) || id.getVariant().equals("inventory")) {
                  return (BakedModel)original;
               } else if (!id.id().getNamespace().equals("splineroads")) {
                  return terrainModels ? new RoadTerrainModels.TerrainModel(original, true) : (BakedModel)original;
               } else {
                  String name = id.id().getPath();
                  if (name.equals("road_block") || name.equals("road_slab")) {
                     return new RoadTerrainModels.TerrainModel(new RoadAsphaltModel(original), true);
                  } else if (name.equals("road_collision") || name.equals("road_infill")) {
                     // Without model data these blocks must report an asphalt surface to map/LOD color
                     // readers (Xaero, Distant Horizons), otherwise the grass/stone filler model is
                     // sampled and roads blend into the terrain on the map.
                     return new RoadTerrainModels.TerrainModel(original, name.equals("road_collision"), true);
                  } else if (name.equals("road_node")) {
                     // Upstream hides the node block model in the world (only NodeRenderer draws the
                     // endpoint marker, and only while the player holds a road tool). Keep the
                     // asphalt colour sampling enabled for map/LOD readers.
                     return new RoadTerrainModels.TerrainModel(original, false, true);
                  } else {
                     return (BakedModel)original;
                  }
               }
            }
         );
   }

   @SubscribeEvent
   public static void baked(BakingCompleted event) {
      SmartSignClient.resourceReloaded();
      RoadRenderer.asphaltReloaded();
      RoadShaders.texturesReloaded();
      TextureAtlas atlas = event.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
      EnumMap<RoadSurface.Texture, TextureAtlasSprite> walks = new EnumMap<>(RoadSurface.Texture.class);

      for (RoadSidewalks.Finish f : RoadSidewalks.Finish.values()) {
         walks.put(
            RoadSurface.Texture.valueOf("WALK_" + f.name()), atlas.getSprite(ResourceLocation.fromNamespaceAndPath("minecraft", "block/" + f.id.substring(10)))
         );
      }

      walks.put(RoadSurface.Texture.WALK_TACTILE, atlas.getSprite(ResourceLocation.fromNamespaceAndPath("splineroads", "block/terrain_paint")));
      RoadTerrainModels.Assets next = new RoadTerrainModels.Assets(
         atlas.getSprite(ResourceLocation.fromNamespaceAndPath("splineroads", "block/terrain_asphalt")),
         atlas.getSprite(ResourceLocation.fromNamespaceAndPath("splineroads", "block/terrain_paint")),
         Map.copyOf(walks)
      );
      RoadTerrainModels.Assets old = loadedAssets;
      loadedAssets = next;
      if (old == null || old.asphalt() != next.asphalt() || old.paint() != next.paint()) {
         resourceRevision++;
      }
   }

   public static void beginFrame() {
      ShaderPackState.State shader = ShaderPackState.current();
      if (!shader.shadowPass()) {
         shaderStatus = shader.active() ? "shader active" : "shader off";
         RoadClientConfig.SurfaceBackend choice = (RoadClientConfig.SurfaceBackend)RoadClientConfig.SURFACE_BACKEND.get();
         // Terrain mode and DH's LOD transition overlap at the vanilla render distance and
         // produce a black-noise band when shaders are enabled. When DH is installed AUTO
         // stays on the VBO backend (manual TERRAIN is still honoured).
         boolean distantHorizons = ModList.get().isLoaded("distanthorizons");
         boolean wanted = loadedAssets != null
            && (
               choice == RoadClientConfig.SurfaceBackend.TERRAIN
                  || choice == RoadClientConfig.SurfaceBackend.AUTO && shader.active() && !distantHorizons
            );
         requested = wanted;
         boolean initialized = observedResource != Long.MIN_VALUE;
         boolean changed = observedResource != resourceRevision;
         boolean toggle = enabled != wanted;
         observedResource = resourceRevision;
         RoadRenderer.shaderMode(false);
         if (lastCacheMiB != (Integer)RoadClientConfig.TERRAIN_CACHE_MIB.get()) {
            trimCache();
         }

         Set<RoadTerrainMesh.Section> dirty = new HashSet<>();
         if (!initialized || changed || toggle) {
            dirty.addAll(snapshots.keySet());
         }

         RoadTerrainHandoff.frame(wanted);
         if (changed) {
            RoadTerrainHandoff.invalidateSources();
            reloadAtlas();
            observedAssets = loadedAssets;
         }

         enabled = wanted;
         if (toggle && !wanted) {
            suspend();
         }

         if (!wanted) {
            dirty.addAll(snapshots.keySet());
            if (!snapshots.isEmpty() || !pending.isEmpty() || !parts.isEmpty() || !published.isEmpty() || !heldSections.isEmpty()) {
               snapshots.clear();
               pending.clear();
               published.clear();
               parts.clear();
               heldSections.clear();
               RoadTerrainHandoff.invalidateOwnership();
            }
         }

         if (wanted && (toggle || changed)) {
            for (Entry<RoadTerrainMesh.Chunk, Set<UUID>> e : chunkRoads.entrySet()) {
               if (loaded(e.getKey())) {
                  for (UUID road : e.getValue()) {
                     request(new RoadTerrainModels.Tile(road, e.getKey()));
                  }
               }
            }
         }

         if (Minecraft.getInstance().level != null) {
            for (RoadTerrainMesh.Section section : dirty) {
               Minecraft.getInstance().levelRenderer.setSectionDirty(section.x(), section.y(), section.z());
            }
         }
      }
   }

   public static boolean enabled() {
      return enabled;
   }

   static boolean requested() {
      return requested;
   }

   public static RoadTerrainModels.Assets assets() {
      return enabled ? loadedAssets : null;
   }

   private static void reloadAtlas() {
      jobs.values().forEach(j -> j.future().cancel(false));
      jobs.clear();
      waiting.clear();
      if (observedAssets != null) {
         active.replaceAll((tile, data) -> rebind(data, observedAssets, loadedAssets));
         cache.replaceAll((tile, data) -> rebind(data, observedAssets, loadedAssets));
      }

      pending.clear();
      published.clear();
      parts.clear();
      snapshots.clear();
      sources.replaceAll((road, source) -> new RoadTerrainModels.Source(source.mesh(), loadedAssets, source.bytes()));
      active.forEach((tile, data) -> data.sections().forEach((sectionx, quads) -> putPart(tile.road(), sectionx, (Map<Integer, List<BakedQuad>>)quads)));
      cache.forEach((tile, data) -> {
         if (ClientRoads.active(tile.road()) && loaded(tile.chunk())) {
            data.sections().forEach((sectionx, quads) -> putPart(tile.road(), sectionx, (Map<Integer, List<BakedQuad>>)quads));
         }
      });
      pending.addAll(heldSections);

      for (RoadTerrainMesh.Section section : new ArrayList<>(pending)) {
         if (publishSection(section)) {
            pending.remove(section);
         }
      }
   }

   static RoadTerrainModels.Prepared rebind(RoadTerrainModels.Prepared data, RoadTerrainModels.Assets old, RoadTerrainModels.Assets next) {
      IdentityHashMap<TextureAtlasSprite, TextureAtlasSprite> sprites = new IdentityHashMap<>();
      sprites.put(old.asphalt(), next.asphalt());
      sprites.put(old.paint(), next.paint());
      old.walks().forEach((texture, sprite) -> sprites.put(sprite, next.walks().getOrDefault(texture, next.paint())));
      Map<RoadTerrainMesh.Section, Map<Integer, List<BakedQuad>>> sections = new LinkedHashMap<>();
      data.sections()
         .forEach(
            (section, cells) -> {
               Map<Integer, List<BakedQuad>> replaced = new LinkedHashMap<>();
               cells.forEach(
                  (cell, quads) -> replaced.put(
                        cell,
                        quads.stream()
                           .map(
                              q -> {
                                 TextureAtlasSprite before = q.getSprite();
                                 TextureAtlasSprite after = sprites.getOrDefault(before, next.asphalt());
                                 boolean sameUV = before.getU(0.0F) == after.getU(0.0F)
                                    && before.getU(1.0F) == after.getU(1.0F)
                                    && before.getV(0.0F) == after.getV(0.0F)
                                    && before.getV(1.0F) == after.getV(1.0F);
                                 int[] v = sameUV ? q.getVertices() : (int[])q.getVertices().clone();
                                 if (!sameUV) {
                                    for (int i = 0; i < 4; i++) {
                                       double u = (double)((Float.intBitsToFloat(v[i * 8 + 4]) - before.getU(0.0F)) / (before.getU(1.0F) - before.getU(0.0F)));
                                       double t = (double)((Float.intBitsToFloat(v[i * 8 + 5]) - before.getV(0.0F)) / (before.getV(1.0F) - before.getV(0.0F)));
                                       v[i * 8 + 4] = Float.floatToRawIntBits(after.getU((float)u));
                                       v[i * 8 + 5] = Float.floatToRawIntBits(after.getV((float)t));
                                    }
                                 }

                                 return new BakedQuad(v, q.getTintIndex(), q.getDirection(), after, q.isShade());
                              }
                           )
                           .toList()
                     )
               );
               sections.put(section, Collections.unmodifiableMap(replaced));
            }
         );
      return new RoadTerrainModels.Prepared(Collections.unmodifiableMap(sections), data.quads());
   }

   private static void suspend() {
      waiting.clear();
      sortWaiting = false;
      ArrayList<RoadTerrainModels.Tile> order = new ArrayList<>(active.keySet());
      order.sort(Comparator.<RoadTerrainModels.Tile>comparingDouble(tx -> distance(tx.chunk())).reversed());

      for (RoadTerrainModels.Tile t : order) {
         remember(t, removeActive(t));
      }
   }

   public static void clear() {
      ArrayList<RoadTerrainMesh.Section> previousSections = new ArrayList<>(snapshots.keySet());
      jobs.values().forEach(job -> job.future().cancel(false));
      jobs.clear();
      waiting.clear();
      sources.clear();
      chunkRoads.clear();
      active.clear();
      cache.clear();
      cachedQuads = 0;
      tileBytes.clear();
      retentionRevision++;
      requested = false;
      enabled = false;
      observedResource = Long.MIN_VALUE;
      observedAssets = null;
      pending.clear();
      published.clear();
      parts.clear();
      snapshots.clear();
      streamDirty.clear();
      incompleteSources.clear();
      cohorts.clear();
      heldSections.clear();
      cohortCoverage.clear();
      transitionSections.clear();
      sortWaiting = false;
      RoadTerrainHandoff.clearSources();
      discardedTiles = 0L;
      cacheHits = 0L;
      preparedTiles = 0L;
      Minecraft mc = Minecraft.getInstance();
      if (mc.level != null) {
         for (RoadTerrainMesh.Section section : previousSections) {
            if (loaded(new RoadTerrainMesh.Chunk(section.x(), section.z()))) {
               mc.levelRenderer.setSectionDirty(section.x(), section.y(), section.z());
            }
         }
      }
   }

   public static void install(UUID road, RoadTerrainMesh.Source mesh) {
      remove(road);
      if (mesh != null) {
         sources.put(road, new RoadTerrainModels.Source(mesh, loadedAssets));
         retentionRevision++;

         for (RoadTerrainMesh.Chunk chunk : mesh.chunks().keySet()) {
            chunkRoads.computeIfAbsent(chunk, key -> new LinkedHashSet<>()).add(road);
            if (enabled && loadedAssets != null && loaded(chunk)) {
               request(new RoadTerrainModels.Tile(road, chunk));
            }
         }
      }
   }

   static void beginBatch(Collection<UUID> members, Collection<RoadTerrainMesh.Section> oldVboSections) {
      Set<RoadTerrainMesh.Chunk> chunks = new LinkedHashSet<>();
      HashSet<RoadTerrainMesh.Section> previous = new HashSet<>(oldVboSections);

      for (UUID member : members) {
         for (RoadTerrainMesh.Section section : published.getOrDefault(member, Set.of())) {
            previous.add(section);
         }
      }

      for (RoadTerrainMesh.Section section : previous) {
         if (loaded(new RoadTerrainMesh.Chunk(section.x(), section.z()))) {
            chunks.add(new RoadTerrainMesh.Chunk(section.x(), section.z()));
            heldSections.add(section);
         }
      }

      for (UUID member : cohorts.begin(members, chunks)) {
         transitionSections.computeIfAbsent(member, key -> new HashSet<>()).addAll(previous);
      }

      rememberCoverage();
   }

   static void finishBatch(Collection<UUID> members) {
      for (UUID member : cohorts.members()) {
         RoadTerrainModels.Source source = sources.get(member);
         if (source != null) {
            for (RoadTerrainMesh.Chunk chunk : source.mesh().chunks().keySet()) {
               if (loaded(chunk)) {
                  cohorts.include(member, chunk);
               }
            }
         }
      }

      rememberCoverage();
      cohorts.installed(members);
      pending.addAll(heldSections);
      if (!requested) {
         for (RoadTerrainMesh.Chunk key : cohorts.keys()) {
            cohorts.ready(key, (road, chunkx) -> true);
         }
      }
   }

   private static void rememberCoverage() {
      for (RoadTerrainMesh.Chunk key : cohorts.keys()) {
         cohortCoverage.merge(key, cohorts.members(key), (a, b) -> {
            HashSet<UUID> ids = new HashSet<>(a);
            ids.addAll(b);
            return Set.copyOf(ids);
         });
      }

      transitionSections.forEach((road, sections) -> {
         for (RoadTerrainMesh.Section section : sections) {
            RoadTerrainMesh.Chunk keyx = new RoadTerrainMesh.Chunk(section.x(), section.z());
            HashSet<UUID> ids = new HashSet<>(cohortCoverage.getOrDefault(keyx, Set.of()));
            ids.add(road);
            cohortCoverage.put(keyx, Set.copyOf(ids));
         }
      });
   }

   static boolean transitionReady(RoadRenderMesh.Section region, Set<UUID> members) {
      HashSet<RoadTerrainMesh.Section> sections = new HashSet<>();

      for (UUID member : members) {
         sections.addAll(transitionSections.getOrDefault(member, Set.of()));
      }

      for (RoadTerrainMesh.Chunk key : cohorts.keys()) {
         if (Math.floorDiv(key.x(), 4) == region.x()
            && Math.floorDiv(key.z(), 4) == region.z()
            && loaded(key)
            && !Collections.disjoint(cohorts.members(key), members)) {
            return false;
         }
      }

      for (RoadTerrainMesh.Section section : sections) {
         if (Math.floorDiv(section.x(), 4) == region.x()
            && Math.floorDiv(section.z(), 4) == region.z()
            && loaded(new RoadTerrainMesh.Chunk(section.x(), section.z()))) {
            if (pending.contains(section) || heldSections.contains(section)) {
               return false;
            }

            RoadHandoffState.Resident resident = RoadTerrainHandoff.STATE.resident(section);
            if (resident != null && !Collections.disjoint(resident.vboOwners(), members) && !RoadTerrainHandoff.currentUpload(section, members, enabled)) {
               return false;
            }
         }
      }

      return true;
   }

   static void endBatch(Collection<UUID> members) {
      for (UUID member : members) {
         transitionSections.remove(member);
      }

      cohortCoverage.clear();
      rememberCoverage();
   }

   private static boolean coherent(RoadTerrainMesh.Section section) {
      RoadTerrainMesh.Chunk chunk = new RoadTerrainMesh.Chunk(section.x(), section.z());
      if (requested && loaded(chunk)) {
         return cohorts.ready(
            chunk,
            (road, key) -> {
               RoadTerrainModels.Source source = sources.get(road);
               return source == null
                  || !ClientRoads.active(road)
                  || !source.mesh().chunks().containsKey(key)
                  || active.containsKey(new RoadTerrainModels.Tile(road, key));
            }
         );
      } else {
         cohorts.ready(chunk, (road, key) -> true);
         return true;
      }
   }

   static void installWindow(UUID road, RoadTerrainMesh.Source mesh, boolean first) {
      if (first) {
         remove(road);
      }

      RoadTerrainModels.Source previous = sources.get(road);
      LinkedHashMap<RoadTerrainMesh.Chunk, RoadSurface.Geometry> chunks = new LinkedHashMap<>();
      if (previous != null) {
         chunks.putAll(previous.mesh().chunks());
      }

      mesh.chunks().forEach((chunk, geometry) -> chunks.merge(chunk, geometry, (a, b) -> {
            ArrayList<RoadSurface.Face> pavement = new ArrayList<>(a.pavement());
            pavement.addAll(b.pavement());
            ArrayList<RoadSurface.Face> paint = new ArrayList<>(a.markings());
            paint.addAll(b.markings());
            return new RoadSurface.Geometry(List.copyOf(pavement), List.copyOf(paint));
         }));

      for (RoadTerrainMesh.Chunk chunk : mesh.chunks().keySet()) {
         RoadTerrainModels.Tile tile = new RoadTerrainModels.Tile(road, chunk);
         unload(tile);
         removeCached(tile);
      }

      sources.put(road, new RoadTerrainModels.Source(new RoadTerrainMesh.Source(Collections.unmodifiableMap(chunks)), loadedAssets));
      retentionRevision++;

      for (RoadTerrainMesh.Chunk chunk : mesh.chunks().keySet()) {
         chunkRoads.computeIfAbsent(chunk, k -> new LinkedHashSet<>()).add(road);
         if (enabled && loadedAssets != null && loaded(chunk)) {
            request(new RoadTerrainModels.Tile(road, chunk));
         }
      }

      if (enabled) {
         for (RoadTerrainModels.Tile tile : new ArrayList<>(incompleteSources)) {
            if (tile.road().equals(road) && loaded(tile.chunk())) {
               request(tile);
            }
         }
      }
   }

   static void installPublication(UUID road, RoadTerrainSourceWindows.Publication publication, boolean first) {
      if (first) {
         remove(road);
      }

      RoadTerrainMesh.Source mesh = publication.source();
      Set<RoadTerrainMesh.Chunk> changed = first ? mesh.chunks().keySet() : publication.changed();

      for (RoadTerrainMesh.Chunk chunk : changed) {
         RoadTerrainModels.Tile tile = new RoadTerrainModels.Tile(road, chunk);
         unload(tile);
         removeCached(tile);
         if (!mesh.chunks().containsKey(chunk)) {
            Set<UUID> ids = chunkRoads.get(chunk);
            if (ids != null) {
               ids.remove(road);
               if (ids.isEmpty()) {
                  chunkRoads.remove(chunk);
               }
            }
         }
      }

      sources.put(road, new RoadTerrainModels.Source(mesh, loadedAssets, publication.bytes()));
      retentionRevision++;

      for (RoadTerrainMesh.Chunk chunkx : changed) {
         if (mesh.chunks().containsKey(chunkx)) {
            chunkRoads.computeIfAbsent(chunkx, key -> new LinkedHashSet<>()).add(road);
            if (enabled && loadedAssets != null && loaded(chunkx)) {
               request(new RoadTerrainModels.Tile(road, chunkx));
            }
         }
      }

      if (enabled) {
         for (RoadTerrainModels.Tile tile : new ArrayList<>(incompleteSources)) {
            if (tile.road().equals(road) && loaded(tile.chunk())) {
               request(tile);
            }
         }
      }
   }

   static long retainedBytes(UUID road) {
      RoadTerrainModels.Source source = sources.get(road);
      return (source == null ? 0L : source.bytes()) + tileBytes.getOrDefault(road, 0L);
   }

   private static void request(RoadTerrainModels.Tile tile) {
      if (ClientRoads.active(tile.road())) {
         if (!RoadRenderer.terrainTileReady(tile.road(), tile.chunk())) {
            incompleteSources.add(tile);
         } else {
            incompleteSources.remove(tile);
            if (!active.containsKey(tile) && !jobs.containsKey(tile) && !waiting.contains(tile)) {
               RoadTerrainModels.Prepared retained = removeCached(tile);
               if (retained != null) {
                  cacheHits++;
                  activate(tile, retained);
               } else {
                  waiting.add(tile);
                  sortWaiting = true;
               }
            }
         }
      }
   }

   public static void submit(UUID road, RoadTerrainModels.Prepared geometry) {
      remove(road);
      geometry.sections().forEach((section, data) -> putPart(road, section, (Map<Integer, List<BakedQuad>>)data));
   }

   private static void putPart(UUID road, RoadTerrainMesh.Section section, Map<Integer, List<BakedQuad>> data) {
      if (parts.computeIfAbsent(section, key -> new HashMap<>()).put(road, data) != data) {
         if (loaded(new RoadTerrainMesh.Chunk(section.x(), section.z()))) {
            RoadTerrainMesh.Chunk chunk = new RoadTerrainMesh.Chunk(section.x(), section.z());
            cohorts.include(road, chunk);
            Set<UUID> ids = cohorts.members(chunk);
            if (!ids.isEmpty()) {
               cohortCoverage.merge(chunk, ids, (a, b) -> {
                  HashSet<UUID> all = new HashSet<>(a);
                  all.addAll(b);
                  return Set.copyOf(all);
               });

               for (UUID member : ids) {
                  Set<RoadTerrainMesh.Section> changed = transitionSections.computeIfAbsent(member, key -> new HashSet<>());
                  changed.add(section);
                  changed.add(below(section));
               }
            }
         }

         published.computeIfAbsent(road, key -> new HashSet<>()).add(section);
         pending.add(section);
         pending.add(below(section));
      }
   }

   private static void dropPart(UUID road, RoadTerrainMesh.Section section) {
      Map<UUID, Map<Integer, List<BakedQuad>>> bucket = parts.get(section);
      if (bucket != null) {
         bucket.remove(road);
         if (bucket.isEmpty()) {
            parts.remove(section);
         }
      }

      Set<RoadTerrainMesh.Section> keys = published.get(road);
      if (keys != null) {
         keys.remove(section);
         if (keys.isEmpty()) {
            published.remove(road);
         }
      }

      if (requested) {
         if (publishSection(section)) {
            pending.remove(section);
         } else {
            pending.add(section);
         }

         pending.add(below(section));
      }
   }

   public static void suspend(UUID road) {
      RoadTerrainModels.Source source = sources.get(road);
      if (source != null) {
         for (RoadTerrainMesh.Chunk chunk : source.mesh().chunks().keySet()) {
            unload(new RoadTerrainModels.Tile(road, chunk));
         }
      }
   }

   public static void resume(UUID road) {
      RoadTerrainModels.Source source = sources.get(road);
      if (source != null && enabled && loadedAssets != null) {
         for (RoadTerrainMesh.Chunk chunk : source.mesh().chunks().keySet()) {
            if (loaded(chunk)) {
               request(new RoadTerrainModels.Tile(road, chunk));
            }
         }
      }
   }

   public static void remove(UUID road) {
      RoadTerrainModels.Source source = sources.remove(road);
      retentionRevision++;
      if (source != null) {
         for (RoadTerrainMesh.Chunk chunk : source.mesh().chunks().keySet()) {
            Set<UUID> ids = chunkRoads.get(chunk);
            if (ids != null) {
               ids.remove(road);
               if (ids.isEmpty()) {
                  chunkRoads.remove(chunk);
               }
            }
         }
      }

      waiting.removeIf(tile -> tile.road().equals(road));
      incompleteSources.removeIf(tile -> tile.road().equals(road));
      Iterator<Entry<RoadTerrainModels.Tile, RoadTerrainModels.TileJob>> it = jobs.entrySet().iterator();

      while (it.hasNext()) {
         Entry<RoadTerrainModels.Tile, RoadTerrainModels.TileJob> e = it.next();
         if (e.getKey().road().equals(road)) {
            e.getValue().future().cancel(false);
            it.remove();
         }
      }

      Iterator<Entry<RoadTerrainModels.Tile, RoadTerrainModels.Prepared>> cachedIt = cache.entrySet().iterator();

      while (cachedIt.hasNext()) {
         Entry<RoadTerrainModels.Tile, RoadTerrainModels.Prepared> e = cachedIt.next();
         if (e.getKey().road().equals(road)) {
            cachedQuads = cachedQuads - e.getValue().quads();
            account(e.getKey(), (long)(-e.getValue().quads()) * 256L);
            cachedIt.remove();
         }
      }

      for (RoadTerrainModels.Tile tile : new ArrayList<>(active.keySet())) {
         if (tile.road().equals(road)) {
            removeActive(tile);
         }
      }

      for (RoadTerrainMesh.Section section : new ArrayList<>(published.getOrDefault(road, Set.of()))) {
         dropPart(road, section);
      }
   }

   private static void activate(RoadTerrainModels.Tile tile, RoadTerrainModels.Prepared data) {
      RoadTerrainModels.Prepared old = active.put(tile, data);
      account(tile, (long)(data.quads() - (old == null ? 0 : old.quads())) * 256L);
      data.sections().forEach((section, quads) -> putPart(tile.road(), section, (Map<Integer, List<BakedQuad>>)quads));
   }

   private static void unload(RoadTerrainModels.Tile tile) {
      waiting.remove(tile);
      RoadTerrainModels.TileJob job = jobs.remove(tile);
      if (job != null) {
         job.future().cancel(false);
      }

      RoadTerrainModels.Prepared data = removeActive(tile);
      if (data == null) {
         RoadTerrainModels.Prepared retained = cache.get(tile);
         if (retained != null) {
            for (RoadTerrainMesh.Section section : retained.sections().keySet()) {
               dropPart(tile.road(), section);
            }
         }
      } else {
         for (RoadTerrainMesh.Section section : data.sections().keySet()) {
            dropPart(tile.road(), section);
         }

         remember(tile, data);
      }
   }

   private static void remember(RoadTerrainModels.Tile tile, RoadTerrainModels.Prepared data) {
      RoadTerrainModels.Prepared old = cache.put(tile, data);
      if (old != null) {
         cachedQuads = cachedQuads - old.quads();
      }

      cachedQuads = cachedQuads + data.quads();
      account(tile, (long)(data.quads() - (old == null ? 0 : old.quads())) * 256L);
      trimCache();
   }

   private static void trimCache() {
      lastCacheMiB = (Integer)RoadClientConfig.TERRAIN_CACHE_MIB.get();
      long limit = (long)lastCacheMiB * 1048576L / 256L;
      Iterator<Entry<RoadTerrainModels.Tile, RoadTerrainModels.Prepared>> it = cache.entrySet().iterator();

      while (it.hasNext() && (cache.size() > 2048 || (long)cachedQuads > limit)) {
         Entry<RoadTerrainModels.Tile, RoadTerrainModels.Prepared> e = it.next();
         cachedQuads = cachedQuads - e.getValue().quads();
         account(e.getKey(), (long)(-e.getValue().quads()) * 256L);
         it.remove();
         if (!active.containsKey(e.getKey())) {
            for (RoadTerrainMesh.Section section : e.getValue().sections().keySet()) {
               dropPart(e.getKey().road(), section);
            }
         }
      }
   }

   static void chunkChanged(RoadTerrainMesh.Chunk chunk) {
      chunkEvents++;
      boolean road = chunkRoads.containsKey(chunk);
      if (road) {
         roadChunkEvents++;
      }

      if (road || RoadRenderer.hasPendingHandoffs()) {
         streamDirty.add(chunk);
      }
   }

   private static void stream() {
      if (!streamDirty.isEmpty() || !jobs.isEmpty() || !waiting.isEmpty()) {
         long deadline = System.nanoTime() + 500000L;

         for (int n = 0; n < 16 && !streamDirty.isEmpty() && (n == 0 || System.nanoTime() < deadline); n++) {
            Iterator<RoadTerrainMesh.Chunk> it = streamDirty.iterator();
            RoadTerrainMesh.Chunk chunk = it.next();
            it.remove();
            boolean present = loaded(chunk);
            if (!present) {
               RoadRenderer.chunkUnloaded(chunk);
            }

            for (UUID road : chunkRoads.getOrDefault(chunk, Set.of())) {
               RoadTerrainModels.Tile tile = new RoadTerrainModels.Tile(road, chunk);
               if (present && enabled) {
                  request(tile);
               } else {
                  unload(tile);
               }
            }
         }

         int consumed = 0;
         Iterator<Entry<RoadTerrainModels.Tile, RoadTerrainModels.TileJob>> it = jobs.entrySet().iterator();

         while (it.hasNext() && consumed < 2) {
            Entry<RoadTerrainModels.Tile, RoadTerrainModels.TileJob> entry = it.next();
            RoadTerrainModels.TileJob job = entry.getValue();
            if (job.future().isDone()) {
               it.remove();
               consumed++;
               RoadTerrainModels.Tile tile = entry.getKey();
               RoadTerrainModels.Source current = sources.get(tile.road());
               if (current != null
                  && current.assets() == job.source().assets()
                  && current.mesh().chunks().get(tile.chunk()) == job.source().mesh().chunks().get(tile.chunk())) {
                  try {
                     RoadTerrainModels.Prepared data = job.future().join();
                     if (enabled && loaded(tile.chunk())) {
                        activate(tile, data);
                     } else {
                        remember(tile, data);
                     }

                     preparedTiles++;
                  } catch (CancellationException var9) {
                     discardedTiles++;
                  } catch (CompletionException var10) {
                     ClientRoads.error = "道路 terrain 分块构建失败：" + var10.getCause();
                  }
               } else {
                  discardedTiles++;
               }
            }
         }

         if (sortWaiting && !waiting.isEmpty()) {
            ArrayList<RoadTerrainModels.Tile> order = new ArrayList<>(waiting);
            order.sort(Comparator.comparingDouble(tilex -> distance(tilex.chunk())));
            waiting.clear();
            waiting.addAll(order);
            sortWaiting = false;
         }

         Iterator<RoadTerrainModels.Tile> waitIt = waiting.iterator();

         while (waitIt.hasNext() && jobs.size() < 2) {
            RoadTerrainModels.Tile tile = waitIt.next();
            waitIt.remove();
            RoadTerrainModels.Source source = sources.get(tile.road());
            if (source != null && source.assets() != null && enabled && loaded(tile.chunk())) {
               RoadSurface.Geometry geometry = source.mesh().chunks().get(tile.chunk());
               if (geometry != null) {
                  jobs.put(
                     tile,
                     new RoadTerrainModels.TileJob(
                        source, CompletableFuture.supplyAsync(() -> prepare(geometry, source.assets(), Set.of(tile.chunk())), RoadRenderer.terrainExecutor())
                     )
                  );
               }
            }
         }
      }
   }

   public static void publish() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.level != null) {
         stream();
         if (!enabled) {
            return;
         }

         if (!pending.isEmpty()) {
            int allowance = (Integer)RoadClientConfig.TERRAIN_SECTIONS_PER_FRAME.get();
            long deadline = System.nanoTime() + 900000L;
            int scans = pending.size();

            while (allowance > 0 && scans-- > 0 && !pending.isEmpty() && System.nanoTime() < deadline) {
               Iterator<RoadTerrainMesh.Section> it = pending.iterator();
               RoadTerrainMesh.Section section = it.next();
               it.remove();
               if (publishSection(section)) {
                  allowance--;
               } else {
                  pending.add(section);
               }
            }
         }
      }
   }

   private static boolean publishSection(RoadTerrainMesh.Section section) {
      if (!requested) {
         snapshots.remove(section);
         heldSections.remove(section);
         return true;
      }

      if (!coherent(section)) {
         heldSections.add(section);
         return false;
      } else {
         heldSections.remove(section);
         Minecraft mc = Minecraft.getInstance();
         Map<UUID, Map<Integer, List<BakedQuad>>> bucket = parts.get(section);
         boolean present = loaded(new RoadTerrainMesh.Chunk(section.x(), section.z()));
         Map<Integer, List<BakedQuad>> own = present ? combined(section) : Map.of();
         Map<Integer, List<BakedQuad>> above = present ? combined(new RoadTerrainMesh.Section(section.x(), section.y() + 1, section.z())) : Map.of();
         Map<Integer, ModelData> data = present ? publication(own, above) : Map.of();
         if (data.isEmpty()) {
            snapshots.remove(section);
         } else {
            snapshots.put(section, data);
         }

         RoadTerrainHandoff.publish(
            section,
            data,
            bucket == null ? Set.of() : bucket.keySet(),
            cohortCoverage.getOrDefault(new RoadTerrainMesh.Chunk(section.x(), section.z()), Set.of())
         );
         if (present) {
            mc.levelRenderer.setSectionDirty(section.x(), section.y(), section.z());
         }

         return true;
      }
   }

   private static RoadTerrainMesh.Section below(RoadTerrainMesh.Section section) {
      return new RoadTerrainMesh.Section(section.x(), section.y() - 1, section.z());
   }

   private static Map<Integer, List<BakedQuad>> combined(RoadTerrainMesh.Section section) {
      HashMap<Integer, List<BakedQuad>> result = new HashMap<>();
      Map<UUID, Map<Integer, List<BakedQuad>>> bucket = parts.get(section);
      if (bucket != null) {
         bucket.entrySet()
            .stream()
            .sorted(Entry.comparingByKey())
            .forEach(road -> road.getValue().forEach((cell, quads) -> result.computeIfAbsent(cell, key -> new ArrayList<>()).addAll(quads)));
      }

      return result;
   }

   static Map<Integer, ModelData> publication(Map<Integer, List<BakedQuad>> own, Map<Integer, List<BakedQuad>> above) {
      HashMap<Integer, List<BakedQuad>> covers = new HashMap<>();
      own.forEach((cellx, quads) -> {
         if (cellx >> 8 > 0) {
            coverBelow(covers, cellx - 256, (List<BakedQuad>)quads);
         }
      });
      above.forEach((cellx, quads) -> {
         if (cellx >> 8 == 0) {
            coverBelow(covers, cellx + 3840, (List<BakedQuad>)quads);
         }
      });
      HashSet<Integer> keys = new HashSet<>(own.keySet());
      keys.addAll(covers.keySet());
      HashMap<Integer, ModelData> result = new HashMap<>();

      for (int cell : keys) {
         result.put(
            cell, ModelData.builder().with(QUADS, own.getOrDefault(cell, List.of())).with(COVERS, List.copyOf(covers.getOrDefault(cell, List.of()))).build()
         );
      }

      return Collections.unmodifiableMap(result);
   }

   private static void coverBelow(Map<Integer, List<BakedQuad>> covers, int cell, List<BakedQuad> quads) {
      for (BakedQuad q : quads) {
         int[] v = (int[])q.getVertices().clone();
         boolean close = true;

         for (int i = 0; i < 4; i++) {
            float y = Float.intBitsToFloat(v[i * 8 + 1]);
            if ((double)y < -0.025 || (double)y > 0.025) {
               close = false;
               break;
            }

            v[i * 8 + 1] = Float.floatToRawIntBits(y + 1.0F);
         }

         if (close) {
            covers.computeIfAbsent(cell, key -> new ArrayList<>())
               .add(new BakedQuad(v, q.getTintIndex(), q.getDirection(), q.getSprite(), q.isShade(), q.hasAmbientOcclusion()));
         }
      }
   }

   static boolean coverOnly(ModelData data) {
      List<BakedQuad> cover = (List<BakedQuad>)data.get(COVERS);
      List<BakedQuad> quads = (List<BakedQuad>)data.get(QUADS);
      return cover != null && !cover.isEmpty() && (quads == null || quads.isEmpty());
   }

   static List<BakedQuad> exposedFill(List<BakedQuad> base, ModelData data) {
      return exposedFill(exposedFill(base, (List<BakedQuad>)data.get(COVERS)), (List<BakedQuad>)data.get(QUADS));
   }

   public static String status() {
      return (enabled ? "terrain" : "VBO")
         + " ("
         + shaderStatus
         + "), "
         + snapshots.size()
         + " sections, "
         + (waiting.size() + jobs.size())
         + " terrain tiles queued, "
         + pending.size()
         + " publish, "
         + cache.size()
         + " cached, "
         + preparedTiles
         + " baked / "
         + cacheHits
         + " reused; "
         + RoadTerrainHandoff.status();
   }

   public static boolean active(RoadTerrainMesh.Section section) {
      return enabled && snapshots.containsKey(section);
   }

   static RoadTerrainModels.Stats stats() {
      return new RoadTerrainModels.Stats(waiting.size() + jobs.size(), active.size(), cache.size(), cachedQuads, preparedTiles, cacheHits, discardedTiles);
   }

   public static RoadTerrainModels.Prepared prepare(RoadSurface.Geometry geometry, RoadTerrainModels.Assets assets, Set<RoadTerrainMesh.Chunk> loaded) {
      RoadTerrainMesh.Result mesh = RoadTerrainMesh.build(geometry, loaded);
      Map<RoadTerrainMesh.Section, Map<Integer, List<BakedQuad>>> sections = new LinkedHashMap<>();
      int count = 0;

      for (Entry<RoadTerrainMesh.Cell, List<RoadTerrainMesh.Polygon>> entry : mesh.cells().entrySet()) {
         RoadTerrainMesh.Cell cell = entry.getKey();
         List<BakedQuad> quads = new ArrayList<>();

         for (RoadTerrainMesh.Polygon polygon : entry.getValue()) {
            List<RoadGeometry.V> v = cleanPolygon(polygon.vertices(), cell);
            if (v.size() == 4) {
               quads.add(quad(cell, polygon, List.of(v.get(0), v.get(1), v.get(2), v.get(3)), assets));
            } else {
               for (int i = 1; i + 1 < v.size(); i++) {
                  if (RoadTerrainMesh.area(List.of(v.get(0), v.get(i), v.get(i + 1))) > 1.0E-12) {
                     quads.add(quad(cell, polygon, List.of(v.get(0), v.get(i), v.get(i + 1), v.get(i + 1)), assets));
                  }
               }
            }
         }

         count += quads.size();
         sections.computeIfAbsent(cell.section(), k -> new LinkedHashMap<>()).put(cell.localKey(), List.copyOf(quads));
      }

      sections.replaceAll((key, value) -> Collections.unmodifiableMap((Map<? extends Integer, ? extends List<BakedQuad>>)value));
      return new RoadTerrainModels.Prepared(Collections.unmodifiableMap(sections), count);
   }

   private static List<RoadGeometry.V> cleanPolygon(List<RoadGeometry.V> input, RoadTerrainMesh.Cell cell) {
      ArrayList<RoadGeometry.V> points = new ArrayList<>(input);
      boolean changed = true;

      while (changed && points.size() >= 3) {
         changed = false;

         for (int i = 0; i < points.size(); i++) {
            RoadGeometry.V a = points.get((i + points.size() - 1) % points.size());
            RoadGeometry.V b = points.get(i);
            RoadGeometry.V c = points.get((i + 1) % points.size());
            double ax = (double)((float)(a.x() - (double)cell.x()));
            double az = (double)((float)(a.z() - (double)cell.z()));
            double bx = (double)((float)(b.x() - (double)cell.x()));
            double bz = (double)((float)(b.z() - (double)cell.z()));
            double cx = (double)((float)(c.x() - (double)cell.x()));
            double cz = (double)((float)(c.z() - (double)cell.z()));
            if (Math.abs((bx - ax) * (cz - az) - (bz - az) * (cx - ax)) <= 1.0E-12) {
               points.remove(i);
               changed = true;
               break;
            }
         }
      }

      return points;
   }

   private static BakedQuad quad(RoadTerrainMesh.Cell cell, RoadTerrainMesh.Polygon polygon, List<RoadGeometry.V> points, RoadTerrainModels.Assets assets) {
      boolean walk = polygon.texture() != RoadSurface.Texture.PLAIN;
      TextureAtlasSprite sprite = polygon.paint() ? assets.paint() : (walk ? assets.walks().getOrDefault(polygon.texture(), assets.paint()) : assets.asphalt());
      int[] data = new int[32];
      RoadSurface.Face face = new RoadSurface.Face(points, polygon.color());
      RoadGeometry.V n = RoadLighting.normal(face);
      int normal = (byte)((int)Math.round(n.x() * 127.0)) & 255
         | ((byte)((int)Math.round(n.y() * 127.0)) & 255) << 8
         | ((byte)((int)Math.round(n.z() * 127.0)) & 255) << 16;
      int rgb = polygon.color();
      int abgr = 0xFF000000 | (rgb & 0xFF) << 16 | rgb & 0xFF00 | rgb >> 16 & 0xFF;
      double scale = walk ? 1.0 : 4.0;
      double baseU = Math.floor((double)cell.x() / scale);
      double baseV = Math.floor((double)cell.z() / scale);

      for (int i = 0; i < 4; i++) {
         RoadGeometry.V p = points.get(i);
         int j = i * 8;
         data[j] = Float.floatToRawIntBits((float)(p.x() - (double)cell.x()));
         data[j + 1] = Float.floatToRawIntBits((float)(p.y() - (double)cell.y() + (polygon.paint() ? 0.003 : 0.001)));
         data[j + 2] = Float.floatToRawIntBits((float)(p.z() - (double)cell.z()));
         data[j + 3] = abgr;
         boolean solid = polygon.paint() || polygon.texture() == RoadSurface.Texture.WALK_TACTILE;
         double u = solid ? 0.25 + 0.5 * (p.x() - (double)cell.x()) : p.x() / scale - baseU;
         double v = solid ? 0.25 + 0.5 * (p.z() - (double)cell.z()) : p.z() / scale - baseV;
         data[j + 4] = Float.floatToRawIntBits(sprite.getU((float)Math.max(0.0, Math.min(1.0, u))));
         data[j + 5] = Float.floatToRawIntBits(sprite.getV((float)Math.max(0.0, Math.min(1.0, v))));
         data[j + 6] = 0;
         data[j + 7] = normal;
      }

      return new BakedQuad(data, -1, Direction.UP, sprite, true);
   }

   private static List<RoadGeometry.V> vertices(BakedQuad q) {
      int[] v = q.getVertices();
      ArrayList<RoadGeometry.V> out = new ArrayList<>(4);

      for (int i = 0; i < 4; i++) {
         out.add(
            new RoadGeometry.V((double)Float.intBitsToFloat(v[i * 8]), (double)Float.intBitsToFloat(v[i * 8 + 1]), (double)Float.intBitsToFloat(v[i * 8 + 2]))
         );
      }

      return out;
   }

   static List<BakedQuad> exposedFill(List<BakedQuad> base, List<BakedQuad> roads) {
      if (!base.isEmpty() && roads != null && !roads.isEmpty()) {
         ArrayList<BakedQuad> out = new ArrayList<>();

         for (BakedQuad q : base) {
            if (q.getDirection() != Direction.UP) {
               out.add(q);
            } else {
               List<RoadGeometry.V> original = vertices(q);
               List<List<RoadGeometry.V>> pieces = List.of(original);

               for (BakedQuad road : roads) {
                  List<RoadGeometry.V> boundary = vertices(road);
                  if (!boundary.stream().anyMatch(p -> Math.abs(p.y() - RoadTerrainMesh.height(original, p)) > 0.025)) {
                     ArrayList<List<RoadGeometry.V>> next = new ArrayList<>();

                     for (List<RoadGeometry.V> piece : pieces) {
                        next.addAll(RoadSurface.subtract(piece, boundary));
                     }

                     pieces = next;
                     if (next.isEmpty()) {
                        break;
                     }
                  }
               }

               if (pieces.size() == 1 && pieces.get(0).equals(original)) {
                  out.add(q);
               } else {
                  for (List<RoadGeometry.V> clipped : pieces) {
                     ArrayList<RoadGeometry.V> piece = new ArrayList<>(clipped);
                     if (RoadLighting.normal(new RoadSurface.Face(piece, 0)).dot(RoadLighting.normal(new RoadSurface.Face(original, 0))) < 0.0) {
                        Collections.reverse(piece);
                     }

                     for (int i = 1; i + 1 < piece.size(); i++) {
                        int[] packed = (int[])q.getVertices().clone();
                        List<RoadGeometry.V> triangle = List.of(piece.get(0), piece.get(i), piece.get(i + 1), piece.get(i + 1));

                        for (int j = 0; j < 4; j++) {
                           RoadGeometry.V v = triangle.get(j);
                           int k = j * 8;
                           packed[k] = Float.floatToRawIntBits((float)v.x());
                           packed[k + 1] = Float.floatToRawIntBits((float)v.y());
                           packed[k + 2] = Float.floatToRawIntBits((float)v.z());
                           RoadGeometry.V a = original.get(0);
                           RoadGeometry.V b = original.get(1).sub(a);
                           RoadGeometry.V c = original.get(2).sub(a);
                           RoadGeometry.V d = v.sub(a);
                           double det = b.x() * c.z() - b.z() * c.x();
                           if (Math.abs(det) > 1.0E-12) {
                              double u = (d.x() * c.z() - d.z() * c.x()) / det;
                              double t = (b.x() * d.z() - b.z() * d.x()) / det;

                              for (int component = 4; component <= 5; component++) {
                                 int[] data = q.getVertices();
                                 double first = (double)Float.intBitsToFloat(data[component]);
                                 packed[k + component] = Float.floatToRawIntBits(
                                    (float)(
                                       first
                                          + u * ((double)Float.intBitsToFloat(data[8 + component]) - first)
                                          + t * ((double)Float.intBitsToFloat(data[16 + component]) - first)
                                    )
                                 );
                              }
                           }
                        }

                        out.add(new BakedQuad(packed, q.getTintIndex(), q.getDirection(), q.getSprite(), q.isShade()));
                     }
                  }
               }
            }
         }

         return List.copyOf(out);
      } else {
         return base;
      }
   }

   public static Set<RoadTerrainMesh.Chunk> loadedChunks(RoadIndex.Built road) {
      ClientLevel level = Minecraft.getInstance().level;
      Set<RoadTerrainMesh.Chunk> result = new HashSet<>();
      if (level != null) {
         for (long packed : road.chunks) {
            int x = (int)packed;
            int z = (int)(packed >> 32);
            RoadTerrainMesh.Chunk chunk = new RoadTerrainMesh.Chunk(x, z);
            if (loaded(chunk)) {
               result.add(chunk);
            }
         }
      }

      return Set.copyOf(result);
   }

   public static BakedModel markerModel(BakedModel model) {
      return model instanceof RoadTerrainModels.TerrainModel terrain ? terrain.baseModel : model;
   }

   static List<BakedQuad> capQuads(List<BakedQuad> quads, double cap) {
      return quads.stream().map(q -> {
         int[] packed = (int[])q.getVertices().clone();

         for (int i = 0; i < 4; i++) {
            int at = i * 8 + 1;
            packed[at] = Float.floatToRawIntBits((float)((double)Float.intBitsToFloat(packed[at]) * cap - (cap >= 1.0 ? 0.002 : 0.0)));
         }

         return new BakedQuad(packed, q.getTintIndex(), q.getDirection(), q.getSprite(), q.isShade(), q.hasAmbientOcclusion());
      }).toList();
   }

   static List<BakedQuad> outlineQuads(List<BakedQuad> faces, List<List<RoadGeometry.V>> polygons, double cap, int visible) {
      ArrayList<BakedQuad> out = new ArrayList<>();

      for (TerrainEdgeGeometry.Face tile : TerrainEdgeGeometry.faces(polygons)) {
         for (BakedQuad face : faces) {
            Direction direction = face.getDirection();
            int axis = direction.getAxis() == Axis.X ? 0 : (direction.getAxis() == Axis.Y ? 1 : 2);
            if (axis == tile.axis()
               && direction.getAxisDirection() == AxisDirection.POSITIVE == tile.positive()
               && (
                  (visible & 1 << direction.ordinal()) != 0
                     || !tile.points()
                        .stream()
                        .allMatch(px -> Math.abs((axis == 0 ? px.x() : (axis == 1 ? px.y() : px.z())) - (double)(tile.positive() ? 1 : 0)) < 1.0E-8)
               )) {
               List<RoadGeometry.V> original = vertices(face);
               RoadGeometry.V a = original.get(0);
               RoadGeometry.V b = original.get(1).sub(a);
               RoadGeometry.V c = original.get(2).sub(a);
               double bb = b.dot(b);
               double cc = c.dot(c);
               double bc = b.dot(c);
               double det = bb * cc - bc * bc;
               if (!(Math.abs(det) < 1.0E-12)) {
                  List<RoadGeometry.V> points = tile.points();
                  ArrayList<List<RoadGeometry.V>> triangles = new ArrayList<>();
                  if (points.size() == 4) {
                     triangles.add(points);
                  } else {
                     for (int i = 1; i + 1 < points.size(); i++) {
                        triangles.add(List.of(points.get(0), points.get(i), points.get(i + 1), points.get(i + 1)));
                     }
                  }

                  for (List<RoadGeometry.V> quad : triangles) {
                     int[] source = face.getVertices();
                     int[] packed = (int[])source.clone();
                     RoadGeometry.V normal = RoadLighting.normal(new RoadSurface.Face(quad, 0));
                     int packedNormal = (byte)((int)Math.round(normal.x() * 127.0)) & 255
                        | ((byte)((int)Math.round(normal.y() * 127.0)) & 255) << 8
                        | ((byte)((int)Math.round(normal.z() * 127.0)) & 255) << 16;

                     for (int i = 0; i < 4; i++) {
                        RoadGeometry.V p = quad.get(i);
                        RoadGeometry.V next = new RoadGeometry.V(p.x(), p.y() * cap, p.z());
                        int at = i * 8;
                        packed[at] = Float.floatToRawIntBits((float)next.x());
                        packed[at + 1] = Float.floatToRawIntBits((float)next.y());
                        packed[at + 2] = Float.floatToRawIntBits((float)next.z());
                        packed[at + 7] = packedNormal;
                        RoadGeometry.V d = next.sub(a);
                        double u = (d.dot(b) * cc - d.dot(c) * bc) / det;
                        double t = (d.dot(c) * bb - d.dot(b) * bc) / det;

                        for (int component = 4; component <= 5; component++) {
                           double first = (double)Float.intBitsToFloat(source[component]);
                           packed[at + component] = Float.floatToRawIntBits(
                              (float)(
                                 first
                                    + u * ((double)Float.intBitsToFloat(source[8 + component]) - first)
                                    + t * ((double)Float.intBitsToFloat(source[16 + component]) - first)
                              )
                           );
                        }
                     }

                     out.add(new BakedQuad(packed, face.getTintIndex(), face.getDirection(), face.getSprite(), face.isShade(), false));
                  }
               }
            }
         }
      }

      return List.copyOf(out);
   }

   static List<BakedQuad> edgeQuads(List<BakedQuad> faces, List<RoadRaster.Box> boxes, double cap) {
      ArrayList<BakedQuad> out = new ArrayList<>();

      for (TerrainEdgeFaces.Face tile : TerrainEdgeFaces.surface(boxes)) {
         for (BakedQuad face : faces) {
            Direction direction = face.getDirection();
            int axis = direction.getAxis() == Axis.X ? 0 : (direction.getAxis() == Axis.Y ? 1 : 2);
            if (axis == tile.axis() && direction.getAxisDirection() == AxisDirection.POSITIVE == tile.positive()) {
               RoadRaster.Box box = tile.box();
               List<RoadGeometry.V> vertices = vertices(face);
               RoadGeometry.V a = vertices.get(0);
               RoadGeometry.V b = vertices.get(1).sub(a);
               RoadGeometry.V c = vertices.get(2).sub(a);
               double bb = b.dot(b);
               double cc = c.dot(c);
               double bc = b.dot(c);
               double det = bb * cc - bc * bc;
               if (!(Math.abs(det) < 1.0E-12)) {
                  int[] source = face.getVertices();
                  int[] packed = (int[])source.clone();

                  for (int i = 0; i < 4; i++) {
                     RoadGeometry.V v = vertices.get(i);
                     RoadGeometry.V next = new RoadGeometry.V(
                        box.x0() + v.x() * (box.x1() - box.x0()), (box.y0() + v.y() * (box.y1() - box.y0())) * cap, box.z0() + v.z() * (box.z1() - box.z0())
                     );
                     int at = i * 8;
                     packed[at] = Float.floatToRawIntBits((float)next.x());
                     packed[at + 1] = Float.floatToRawIntBits((float)next.y());
                     packed[at + 2] = Float.floatToRawIntBits((float)next.z());
                     RoadGeometry.V d = next.sub(a);
                     double u = (d.dot(b) * cc - d.dot(c) * bc) / det;
                     double t = (d.dot(c) * bb - d.dot(b) * bc) / det;

                     for (int component = 4; component <= 5; component++) {
                        double first = (double)Float.intBitsToFloat(source[component]);
                        packed[at + component] = Float.floatToRawIntBits(
                           (float)(
                              first
                                 + u * ((double)Float.intBitsToFloat(source[8 + component]) - first)
                                 + t * ((double)Float.intBitsToFloat(source[16 + component]) - first)
                           )
                        );
                     }
                  }

                  out.add(new BakedQuad(packed, face.getTintIndex(), face.getDirection(), face.getSprite(), face.isShade(), face.hasAmbientOcclusion()));
               }
            }
         }
      }

      return List.copyOf(out);
   }

   private RoadTerrainModels() {
   }

   public static record Assets(TextureAtlasSprite asphalt, TextureAtlasSprite paint, Map<RoadSurface.Texture, TextureAtlasSprite> walks) {
      public Assets(TextureAtlasSprite asphalt, TextureAtlasSprite paint) {
         this(asphalt, paint, Map.of());
      }
   }

   public static record Prepared(Map<RoadTerrainMesh.Section, Map<Integer, List<BakedQuad>>> sections, int quads) {
      public static RoadTerrainModels.Prepared empty() {
         return new RoadTerrainModels.Prepared(Map.of(), 0);
      }
   }

   private static record Source(RoadTerrainMesh.Source mesh, RoadTerrainModels.Assets assets, long bytes) {
      Source(RoadTerrainMesh.Source mesh, RoadTerrainModels.Assets assets) {
         this(mesh, assets, RoadTerrainSourceWindows.bytes(mesh));
      }
   }

   static record Stats(int queued, int active, int cached, int cachedQuads, long baked, long hits, long discarded) {
   }

   @EventBusSubscriber(
      modid = "splineroads",
      value = {Dist.CLIENT}
   )
   public static final class Streaming {
      @SubscribeEvent
      public static void loaded(Load event) {
         changed(event);
      }

      @SubscribeEvent
      public static void unloaded(Unload event) {
         changed(event);
      }

      private static void changed(ChunkEvent event) {
         if (event.getLevel() instanceof ClientLevel level) {
            long var5 = event.getChunk().getPos().toLong();
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
               if (mc.level == level) {
                  ClientRoads.chunkChanged(var5);
                  RoadTerrainModels.chunkChanged(new RoadTerrainMesh.Chunk((int)var5, (int)(var5 >> 32)));
               }
            });
         }
      }
   }

   private static final class TerrainModel extends BakedModelWrapper<BakedModel> {
      private static volatile BakedQuad asphaltQuad;
      private static volatile BakedQuad paintWhiteQuad;
      private static volatile BakedQuad paintYellowQuad;
      private final boolean originalVisible;
      private final boolean asphalt;
      private final BakedModel baseModel;

      TerrainModel(BakedModel original, boolean originalVisible) {
         this(original, originalVisible, false);
      }

      TerrainModel(BakedModel original, boolean originalVisible, boolean asphalt) {
         super(original);
         this.baseModel = original;
         this.originalVisible = originalVisible;
         this.asphalt = asphalt;
      }

      public ModelData getModelData(final BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData input) {
         input = super.getModelData(level, pos, state, input);
         BlockState fill = (BlockState)input.get(RoadFillEntity.MATERIAL);
         if (fill != null) {
            int visible = 0;

            for (Direction direction : Direction.values()) {
               BlockPos adjacent = pos.relative(direction);
               if (!level.getBlockState(adjacent).isSolidRender(level, adjacent)) {
                  visible |= 1 << direction.ordinal();
               }
            }

            input = input.derive().with(RoadAsphaltModel.POSITION, pos.immutable()).with(RoadTerrainModels.FILL_FACES, visible).build();
         }

         RoadTerrainMesh.Section section = new RoadTerrainMesh.Section(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
         int cell = pos.getX() & 15 | (pos.getZ() & 15) << 4 | (pos.getY() & 15) << 8;
         Map<Integer, ModelData> map = RoadTerrainHandoff.model(section, cell);
         if (map == null) {
            return withoutRoads(input);
         } else {
            ModelData data = map.get(cell);
            if (data == null) {
               return withoutRoads(input);
            } else {
               final HashMap<BlockPos, Integer> samples = new HashMap<>();
               var lighting = new RoadLighting.Access() {
                  @Override
                  public boolean opaque(int x, int y, int z) {
                     BlockPos p = new BlockPos(x, y, z);
                     return level.getBlockState(p).getLightBlock(level, p) >= 15;
                  }

                  @Override
                  public int packed(int x, int y, int z) {
                     return samples.computeIfAbsent(new BlockPos(x, y, z), p -> LevelRenderer.getLightColor(level, p));
                  }
               };
               ArrayList<BakedQuad> lit = new ArrayList<>();

               for (BakedQuad quad : (List<BakedQuad>)(List<?>)data.get(RoadTerrainModels.QUADS)) {
                  int[] vertices = (int[])quad.getVertices().clone();
                  List<RoadGeometry.V> points = RoadTerrainModels.vertices(quad)
                     .stream()
                     .map(p -> p.add(new RoadGeometry.V((double)pos.getX(), (double)pos.getY(), (double)pos.getZ())))
                     .toList();
                  RoadSurface.Face face = new RoadSurface.Face(points, 0);

                  for (int vertex = 0; vertex < 4; vertex++) {
                     vertices[vertex * 8 + 6] = RoadLighting.sample(face, points.get(vertex), lighting, true);
                  }

                  lit.add(new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), false));
               }

               return input.derive()
                  .with(RoadTerrainModels.QUADS, List.copyOf(lit))
                  .with(RoadTerrainModels.COVERS, (List)data.get(RoadTerrainModels.COVERS))
                  .build();
            }
         }
      }

      private static ModelData withoutRoads(ModelData input) {
         return input.get(RoadTerrainModels.QUADS) == null && input.get(RoadTerrainModels.COVERS) == null
            ? input
            : input.derive().with(RoadTerrainModels.QUADS, List.of()).with(RoadTerrainModels.COVERS, List.of()).build();
      }

      public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
         if (modelColorSampling()) {
            List<BakedQuad> paint = paintQuads(state);
            if (paint != null) {
               return paint;
            }

            if (this.asphalt) {
               return asphaltQuads();
            }
         }

         return this.originalVisible ? super.getQuads(state, side, random) : List.of();
      }

      /**
       * The asphalt replacement is only meant for map/LOD colour readers (Xaero/DH). Some
       * chunk renderers also call the no-ModelData overload, and returning asphalt there
       * painted the whole road volume as asphalt slabs in the world, visible when looking
       * up from below. Only answer with asphalt when the call comes from Xaero/DH.
       */
      private static boolean modelColorSampling() {
         return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> {
            String name = frame.getClassName();
            return name.startsWith("xaero.") || name.startsWith("com.seibel.distanthorizons.");
         }));
      }

      private static List<BakedQuad> paintQuads(BlockState state) {
         if (state == null || !state.hasProperty(RoadBlocks.Road.PAINT)) {
            return null;
         }

         RoadBlocks.Paint paint = state.getValue(RoadBlocks.Road.PAINT);
         if (paint == RoadBlocks.Paint.NONE) {
            return null;
         }

         BakedQuad quad = paintQuad(paint);
         return quad == null ? List.of() : List.of(quad);
      }

      private static BakedQuad paintQuad(RoadBlocks.Paint paint) {
         boolean white = paint == RoadBlocks.Paint.WHITE;
         BakedQuad cached = white ? paintWhiteQuad : paintYellowQuad;
         if (cached != null) {
            return cached;
         }

         TextureAtlasSprite sprite = sprite(white ? "minecraft:block/white_concrete" : "minecraft:block/yellow_concrete");
         if (sprite == null) {
            return null;
         }

         BakedQuad quad = flatQuad(sprite);
         if (white) {
            paintWhiteQuad = quad;
         } else {
            paintYellowQuad = quad;
         }

         return quad;
      }

      private static TextureAtlasSprite sprite(String name) {
         return Minecraft.getInstance()
            .getModelManager()
            .getAtlas(InventoryMenu.BLOCK_ATLAS)
            .getSprite(ResourceLocation.parse(name));
      }

      private static List<BakedQuad> asphaltQuads() {
         BakedQuad quad = asphaltQuad;
         if (quad == null) {
            TextureAtlasSprite sprite = sprite("splineroads:block/terrain_asphalt");
            if (sprite == null) {
               return List.of();
            }

            quad = flatQuad(sprite);
            asphaltQuad = quad;
         }

         return List.of(quad);
      }

      private static BakedQuad flatQuad(TextureAtlasSprite sprite) {
         float[] xs = new float[]{0.0F, 1.0F, 1.0F, 0.0F};
         float[] zs = new float[]{0.0F, 0.0F, 1.0F, 1.0F};
         int[] vertices = new int[32];

         for (int i = 0; i < 4; i++) {
            int at = i * 8;
            vertices[at] = Float.floatToRawIntBits(xs[i]);
            vertices[at + 1] = Float.floatToRawIntBits(1.0F);
            vertices[at + 2] = Float.floatToRawIntBits(zs[i]);
            vertices[at + 3] = -1;
            vertices[at + 4] = Float.floatToRawIntBits(sprite.getU(xs[i]));
            vertices[at + 5] = Float.floatToRawIntBits(sprite.getV(zs[i]));
            vertices[at + 6] = 0;
            vertices[at + 7] = 0x7F00;
         }

         return new BakedQuad(vertices, -1, Direction.UP, sprite, true);
      }

      public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType layer) {
         if (data.get(RoadTerrainModels.QUADS) == null
            && data.get(RoadFillEntity.MATERIAL) == null
            && modelColorSampling()) {
            List<BakedQuad> paint = paintQuads(state);
            if (paint != null) {
               return paint;
            }

            if (this.asphalt) {
               return asphaltQuads();
            }
         }

         List<BakedQuad> base = this.originalVisible ? super.getQuads(state, side, random, data, layer) : List.of();
         BlockState material = (BlockState)data.get(RoadFillEntity.MATERIAL);
         Double cap = (Double)data.get(RoadFillEntity.CAP);
         if (material != null && cap != null) {
            BakedModel model = RoadTerrainModels.markerModel(Minecraft.getInstance().getBlockRenderer().getBlockModel(material));
            BlockPos pos = (BlockPos)data.get(RoadAsphaltModel.POSITION);
            RandomSource layerRandom = RandomSource.create(material.getSeed(pos == null ? BlockPos.ZERO : pos));
            base = layer != null && !model.getRenderTypes(material, layerRandom, data).contains(layer)
               ? List.of()
               : RoadTerrainModels.capQuads(model.getQuads(material, side, random, data, layer), cap);
            List<RoadRaster.Box> edge = (List<RoadRaster.Box>)data.get(RoadFillEntity.EDGE);
            List<List<RoadGeometry.V>> outline = (List<List<RoadGeometry.V>>)data.get(RoadFillEntity.OUTLINE);
            if (edge != null && !edge.isEmpty() || outline != null && !outline.isEmpty()) {
               base = List.of();
               if (side == null
                  && (
                     layer == null
                        || model.getRenderTypes(material, RandomSource.create(material.getSeed(pos == null ? BlockPos.ZERO : pos)), data).contains(layer)
                  )) {
                  ArrayList<BakedQuad> faces = new ArrayList<>(model.getQuads(material, null, random, data, layer));

                  for (Direction direction : Direction.values()) {
                     faces.addAll(model.getQuads(material, direction, random, data, layer));
                  }

                  base = outline != null && !outline.isEmpty()
                     ? RoadTerrainModels.outlineQuads(
                        faces, outline, cap, data.get(RoadTerrainModels.FILL_FACES) == null ? 63 : (Integer)data.get(RoadTerrainModels.FILL_FACES)
                     )
                     : RoadTerrainModels.edgeQuads(faces, edge, cap);
               }
            }
         }

         final List<BakedQuad> roads = (List<BakedQuad>)data.get(RoadTerrainModels.QUADS);
         base = RoadTerrainModels.exposedFill(base, data);
         if (side != null || layer != null && layer != RenderType.solid() || roads == null || roads.isEmpty()) {
            return base;
         } else if (base.isEmpty()) {
            return roads;
         } else {
            final List<BakedQuad> fill = base;
            return new AbstractList<BakedQuad>() {
               @Override
               public int size() {
                  return fill.size() + roads.size();
               }

               public BakedQuad get(int i) {
                  return i < fill.size() ? fill.get(i) : roads.get(i - fill.size());
               }
            };
         }
      }

      public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
         BlockState fill = (BlockState)data.get(RoadFillEntity.MATERIAL);
         if (fill != null) {
            return ChunkRenderTypeSet.union(
               new ChunkRenderTypeSet[]{
                  RoadTerrainModels.markerModel(Minecraft.getInstance().getBlockRenderer().getBlockModel(fill)).getRenderTypes(fill, random, ModelData.EMPTY),
                  RoadTerrainModels.SOLID
               }
            );
         } else if (!this.originalVisible) {
            return RoadTerrainModels.SOLID;
         } else {
            ChunkRenderTypeSet base = super.getRenderTypes(state, random, data);
            List<BakedQuad> roads = (List<BakedQuad>)data.get(RoadTerrainModels.QUADS);
            return roads != null && !roads.isEmpty() && !base.contains(RenderType.solid())
               ? ChunkRenderTypeSet.union(new ChunkRenderTypeSet[]{base, RoadTerrainModels.SOLID})
               : base;
         }
      }

      public boolean isCustomRenderer() {
         return this.originalVisible && super.isCustomRenderer();
      }

      public boolean useAmbientOcclusion() {
         return !this.originalVisible || super.useAmbientOcclusion();
      }
   }

   private static record Tile(UUID road, RoadTerrainMesh.Chunk chunk) {
   }

   private static record TileJob(RoadTerrainModels.Source source, CompletableFuture<RoadTerrainModels.Prepared> future) {
   }
}
