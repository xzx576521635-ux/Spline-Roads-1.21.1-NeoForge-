package com.sora.splineroads.client;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexBuffer.Usage;
import com.mojang.blaze3d.vertex.VertexFormat.IndexType;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.RoadFurniture;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadLighting;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadRenderMesh;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.core.RoadSurfaceSections;
import com.sora.splineroads.core.RoadTerrainMesh;
import com.sora.splineroads.core.RoadTerrainSourceWindows;
import com.sora.splineroads.core.RoadVisibility;
import com.sora.splineroads.world.FurnitureSpacing;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadRecord;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent.DebugText;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT}
)
public final class RoadRenderer {
   private static final ResourceLocation ASPHALT = ResourceLocation.fromNamespaceAndPath("splineroads", "textures/road/asphalt.png");
   private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath("splineroads", "textures/road/white.png");
   private static final RenderType SURFACE = RoadRenderTypes.surface(ASPHALT, false);
   private static final RenderType MARKING = RoadRenderTypes.surface(WHITE, true);
   private static final RenderType GHOST = RenderType.entityTranslucent(WHITE);
   private static final RenderType CONFLICT = RoadRenderTypes.conflict(WHITE);
   private static final Map<RoadSurface.Texture, RenderType> MATERIALS = new HashMap<>(
      Map.of(
         RoadSurface.Texture.CONCRETE,
         RoadRenderTypes.solid(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/smooth_stone.png")),
         RoadSurface.Texture.METAL,
         RoadRenderTypes.solid(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/iron_block.png")),
         RoadSurface.Texture.SOIL,
         RoadRenderTypes.solid(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/dirt.png")),
         RoadSurface.Texture.LEAVES,
         RoadRenderTypes.solid(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/oak_leaves.png")),
         RoadSurface.Texture.SIGNAL_HOUSING,
         signalType("housing"),
         RoadSurface.Texture.SIGNAL_GREEN,
         signalType("green"),
         RoadSurface.Texture.SIGNAL_RED,
         signalType("red"),
         RoadSurface.Texture.SIGNAL_AMBER,
         signalType("amber"),
         RoadSurface.Texture.SIGNAL_ATLAS,
         signalType("atlas")
      )
   );
   private static final ExecutorService WORKER;
   private static final Map<UUID, Integer> versions;
   private static final Set<UUID> dirty;
   private static final Set<UUID> priorityDirty;
   private static final RoadBuildScope buildScope;
   private static final Map<UUID, Integer> meshVersions;
   private static final Map<UUID, Integer> terrainVersions;
   private static final Map<UUID, RoadRenderer.Job> jobs;
   private static final Map<UUID, Set<RoadRenderMesh.Section>> roadSections;
   private static Map<RoadRenderMesh.Section, RoadRenderer.GpuSection> regions;
   private static Map<RoadRenderMesh.Section, RoadRenderer.GpuSection> dormant;
   private static long uploadCancels;
   private static long uploadFrames;
   private static long uploadZeroFrames;
   private static long packedFaces;
   private static boolean extendedEncoding;
   private static long useClock;
   private static long retainedBytes;
   private static long lastBudget;
   private static final RoadGpuRetention<RoadRenderer.GpuSection> unloadedGpu;
   private static final Set<RoadRenderMesh.Section> residencyDirty;
   private static long residencyChecks;
   private static long residencyEvictions;
   private static long residencyUploadCancels;
   private static long retentionRevision;
   private static final Map<UUID, Long> allocatedRoadBytes;
   private static final AtomicLong workerRetentionRevision;
   private static RoadGeometry.V cameraPosition;
   private static volatile long epoch;
   private static int lastDraws;
   private static int lastUploads;
   private static int lastDistant;
   private static int lastMatrixUploads;
   private static long lastVertices;
   private static double averageCpuMs;
   private static double lastCpuMs;
   private static long lastSurfaceIndices;
   private static final List<RoadRenderer.GpuSection> visible;
   private static final List<RoadRenderer.GpuSection> glassVisible;
   private static final RoadUploadArena uploadArena;
   private static final RoadUploadArena previewArena;
   private static RoadRenderer.Upload pendingUpload;
   private static final AtomicLong pieceSerial;
   private static final Map<UUID, RoadRenderer.SignalCache> signalBatches;
   private static final Set<RenderType> signalTypesUsed;
   private static final Map<UUID, RoadRenderer.Preparation> preparations;
   private static final Map<UUID, RoadRenderer.AtomicUpdate> atomicUpdates;
   private static final Map<UUID, RoadRenderer.AtomicUpdate> displayUpdates;
   private static long drawSelectionBuilds;
   private static long drawSelectionRanges;
   private static long drawOwnershipQueries;
   private static long movementRescans;
   private static long movementCandidates;
   private static long windowSelections;
   private static long windowVisits;
   private static long completedWindowSkips;
   private static long lightRefreshes;
   private static long buildCandidateChecks;
   private static long lightReadChecks;
   private static long lightReadPositions;
   private static long lightReadUnchanged;
   private static long lightHashes;
   private static final int LIGHT_POSITIONS_PER_FRAME = 2048;
   private static final int LIGHT_READ_SLICE = 128;
   private static final boolean LIGHT_DIAGNOSTICS;
   private static int lightPollCursor;
   private static final Map<SectionPos, RoadLightSnapshot> lightSnapshots;
   private static long lightSnapshotTick;
   private static boolean asphaltMipmaps;
   private static RoadGeometry.Mesh ghostMesh;
   private static List<RoadGeometry.Mesh> ghostRoads;
   private static List<RoadSurface.Face> ghostFaces;
   private static VertexBuffer ghostBuffer;
   private static RoadGeometry.V ghostOrigin;
   private static boolean ghostDelete;
   private static boolean ghostJunction;
   private static boolean ghostConflict;
   private static volatile String currentLayerKey;
   private static final int[] setupShaderTextures;
   private static RoadGeometry.Mesh furniturePreview;
   private static final Map<RoadGeometry.Mesh, RoadRecord> previewRecords;
   private static long previewContextRevision;
   private static long furnitureContextRevision;
   private static RoadPreviewGround.Sources furnitureGround;
   private static long furnitureRevision;
   private static List<RoadSurface.Face> furnitureFaces;
   private static String furnitureFailure;

   private static RenderType signalType(String name) {
      return RoadRenderTypes.surface(ResourceLocation.fromNamespaceAndPath("splineroads", "textures/road/sr_white.png"), true);
   }

   static RoadRenderer.ResidencyStats residencyStats() {
      return new RoadRenderer.ResidencyStats(
         unloadedGpu.bytes(), unloadedGpu.size(), residencyDirty.size(), residencyChecks, residencyEvictions, residencyUploadCancels
      );
   }

   static long retentionRevision() {
      return retentionRevision + workerRetentionRevision.get() + RoadTerrainModels.retentionRevision();
   }

   static RoadRenderer.StreamingStats streamingStats() {
      return new RoadRenderer.StreamingStats(lastCpuMs, lastUploads, dirty.size() + jobs.size(), lastSurfaceIndices);
   }

   static RenderType signalMaterial(RoadSurface.Texture texture) {
      return texture == RoadSurface.Texture.PLAIN ? MARKING : MATERIALS.get(texture);
   }

   static Executor terrainExecutor() {
      return WORKER;
   }

   static RoadRenderer.SelectionStats selectionStats() {
      return new RoadRenderer.SelectionStats(drawSelectionBuilds, drawSelectionRanges, drawOwnershipQueries);
   }

   static RoadRenderer.MovementStats movementStats() {
      return new RoadRenderer.MovementStats(movementRescans, movementCandidates, windowSelections, windowVisits, completedWindowSkips, lightRefreshes);
   }

   static boolean hasPendingHandoffs() {
      return !displayUpdates.isEmpty();
   }

   private static void attach(RoadRenderer.GpuSection section, RoadRenderer.AtomicUpdate group) {
      if (section.handoffs.add(group)) {
         if (pendingUpload != null && pendingUpload.section == section) {
            pendingUpload.cancel();
            pendingUpload = null;
         }

         group.pending.add(section);
      }
   }

   private static void superseded(RoadRenderer.GpuSection section, Set<RoadRenderer.AtomicUpdate> obsolete) {
      for (RoadRenderer.AtomicUpdate old : obsolete) {
         if (section.handoffs.remove(old)) {
            old.pending.remove(section);
            retire(old);
         }
      }
   }

   private static void retire(RoadRenderer.AtomicUpdate group) {
      if (group.installed && group.pending.isEmpty()) {
         HashSet<UUID> ended = new HashSet<>();

         for (UUID id : group.all) {
            if (displayUpdates.remove(id, group)) {
               ended.add(id);
            }
         }

         if (!ended.isEmpty()) {
            RoadTerrainModels.endBatch(ended);
         }
      }
   }

   private static void completedHandoff(RoadRenderer.GpuSection section) {
      ArrayList<RoadRenderer.AtomicUpdate> groups = new ArrayList<>(section.handoffs);
      section.handoffs.clear();
      section.retained.clear();
      section.clearDrawSelection();

      for (RoadRenderer.AtomicUpdate group : groups) {
         group.pending.remove(section);
         retire(group);
      }
   }

   private static boolean handoffReady(RoadRenderer.GpuSection section) {
      for (RoadRenderer.AtomicUpdate group : section.handoffs) {
         if (!group.installed || !RoadTerrainModels.transitionReady(section.key, group.all)) {
            return false;
         }
      }

      return true;
   }

   public static void changedBatch(Collection<UUID> changed, Set<UUID> members) {
      LinkedHashSet<UUID> all = new LinkedHashSet<>(changed);
      all.addAll(members);
      invalidate(all, true);
   }

   private static void finishAtomic(RoadRenderer.AtomicUpdate group) {
      for (UUID id : group.members) {
         RoadRenderer.Preparation p = preparations.get(id);
         if (p == null
            || p.version != versions.getOrDefault(id, 0)
            || p.surfaces.size() != p.windows.size()
            || RoadTerrainModels.requested() && p.terrain.size() != p.windows.size()) {
            return;
         }
      }

      for (UUID idx : group.members) {
         RoadRenderer.Preparation p = preparations.get(idx);
         HashSet<RoadRenderMesh.Section> old = new HashSet<>(roadSections.getOrDefault(idx, Set.of()));
         old.removeAll(p.pieces.keySet());
         installPieces(regions, idx, old, p.pieces, false);
         installPieces(dormant, idx, old, p.pieces, true);
         roadSections.put(idx, Set.copyOf(p.pieces.keySet()));
         meshVersions.put(idx, p.version);
         signalBatches.remove(idx);
         RoadTerrainSourceWindows.Publication source = group.terrain.get(idx);
         if (source != null) {
            RoadTerrainModels.installPublication(idx, source, true);
            if (p.terrain.size() == p.windows.size()) {
               terrainVersions.put(idx, p.version);
            }
         }

         atomicUpdates.remove(idx);
      }

      group.terrain.clear();
      group.installed = true;
      RoadTerrainModels.finishBatch(group.all);
      retire(group);
      trimDormant();
   }

   private static <T> List<T> append(List<T> a, List<T> b) {
      ArrayList<T> list = new ArrayList<>(a.size() + b.size());
      list.addAll(a);
      list.addAll(b);
      return List.copyOf(list);
   }

   private static RoadRenderer.Prepared append(RoadRenderer.Prepared a, RoadRenderer.Prepared b) {
      EnumMap<RoadSurface.Texture, List<RoadSurface.Face>> near = new EnumMap<>(RoadSurface.Texture.class);
      near.putAll(a.near);
      b.near.forEach((key, value) -> near.merge(key, (List<RoadSurface.Face>)value, RoadRenderer::append));
      EnumMap<RoadSurface.Texture, List<RoadSurface.Face>> far = new EnumMap<>(RoadSurface.Texture.class);
      far.putAll(a.far);
      b.far.forEach((key, value) -> far.merge(key, (List<RoadSurface.Face>)value, RoadRenderer::append));
      return new RoadRenderer.Prepared(
         pieceSerial.incrementAndGet(),
         a.cpuBytes + b.cpuBytes,
         new RoadRenderMesh.Piece(
            append(a.piece.pavement(), b.piece.pavement()), append(a.piece.detail(), b.piece.detail()), append(a.piece.distant(), b.piece.distant())
         ),
         append(a.paint, b.paint),
         a.bounds.minmax(b.bounds),
         near,
         far
      );
   }

   private static RoadLightSnapshot lightPair(SectionPos pos) {
      ClientLevel level = Minecraft.getInstance().level;
      long tick = level.getGameTime();
      if (tick != lightSnapshotTick) {
         lightSnapshotTick = tick;
         lightSnapshots.clear();
      }

      RoadLightSnapshot cached = lightSnapshots.get(pos);
      if (cached != null) {
         return cached;
      } else {
         lightHashes++;
         LevelLightEngine engine = level.getLightEngine();
         RoadLightSnapshot snapshot = RoadLightSnapshot.capture(
            engine.getLayerListener(LightLayer.SKY).getDataLayerData(pos), engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos)
         );
         lightSnapshots.put(pos, snapshot);
         return snapshot;
      }
   }

   private static boolean sampleChunkLoaded(ClientLevel level, BlockPos pos) {
      return level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) != null;
   }

   private static int pollLight(RoadRenderer.GpuSection s, int allowance) {
      long tick = Minecraft.getInstance().level.getGameTime();
      if (s.lightCheck == null) {
         if (tick - s.checkedLightAt < 20L) {
            return 0;
         }

         s.lightCheck = s.lightReads.cursor();
         lightReadChecks++;
      }

      ClientLevel level = Minecraft.getInstance().level;
      MutableBlockPos pos = new MutableBlockPos();
      int read = s.lightCheck.advance((position, requested) -> {
         lightReadPositions++;
         pos.set(BlockPos.getX(position), BlockPos.getY(position), BlockPos.getZ(position));
         if (!sampleChunkLoaded(level, pos)) {
            return 402653184;
         } else {
            BlockState state = level.getBlockState(pos);
            int value = 0;
            if ((requested & 33554432) != 0 && state.getLightBlock(level, pos) >= 15) {
               value |= 16777216;
            }

            if ((requested & 67108864) != 0) {
               if (level.getLightEngine().lightOnInSection(SectionPos.of(pos))) {
                  value |= LevelRenderer.getLightColor(level, state, pos);
               } else {
                  value |= 268435456;
               }
            }

            return value;
         }
      }, allowance);
      if (!s.lightCheck.complete()) {
         return read;
      } else {
         boolean changed = s.lightCheck.changed();
         s.lightCheck = null;
         s.checkedLightAt = tick;
         if (changed) {
            lightRefreshes++;
            s.dirty = true;
            return read;
         } else {
            lightReadUnchanged++;
            if (LIGHT_DIAGNOSTICS) {
               s.lighting.replaceAll((position, previous) -> lightPair(position));
            }

            return read;
         }
      }
   }

   private static void pollVisibleLight() {
      if (!visible.isEmpty()) {
         int remaining = 2048;
         RoadScanBudget budget = new RoadScanBudget(Math.min(64, visible.size()), System.nanoTime() + 500000L);

         while (remaining > 0 && budget.take()) {
            RoadRenderer.GpuSection section = visible.get(Math.floorMod(lightPollCursor++, visible.size()));
            if (!section.dirty && (pendingUpload == null || pendingUpload.section != section)) {
               remaining -= pollLight(section, Math.min(128, remaining));
            }
         }
      }
   }

   static void asphaltReloaded() {
      asphaltMipmaps = false;
   }

   static void prepareAsphaltTexture() {
      if (!asphaltMipmaps) {
         RenderSystem.setShaderTexture(0, ASPHALT);
         int previous = GL11.glGetInteger(32873);
         RenderSystem.bindTexture(RenderSystem.getShaderTexture(0));

         try {
            int width = GL11.glGetTexLevelParameteri(3553, 0, 4096);
            int height = GL11.glGetTexLevelParameteri(3553, 0, 4097);
            int maximum = 31 - Integer.numberOfLeadingZeros(Math.max(1, Math.min(width, height)));
            int levels = Minecraft.getInstance().options.mipmapLevels().get() > 0 ? maximum : 0;
            GL11.glTexParameteri(3553, 33085, levels);
            GL11.glTexParameterf(3553, 33083, (float)levels);
            if (levels > 0) {
               GL30.glGenerateMipmap(3553);
            }

            asphaltMipmaps = true;
         } finally {
            RenderSystem.bindTexture(previous);
         }
      }
   }

   public static void changed(Collection<UUID> ids) {
      invalidate(ids, false);
   }

   static void sourceEvicted(Collection<UUID> ids) {
      invalidate(ids, false);
   }

   private static void invalidate(Collection<UUID> ids, boolean atomic) {
      LinkedHashSet<UUID> affected = new LinkedHashSet<>(ids);
      HashSet<RoadRenderer.AtomicUpdate> obsolete = new HashSet<>();

      for (UUID id : ids) {
         RoadRenderer.AtomicUpdate group = displayUpdates.get(id);
         if (group != null) {
            obsolete.add(group);
         }
      }

      for (RoadRenderer.AtomicUpdate group : obsolete) {
         for (UUID idx : group.all) {
            atomicUpdates.remove(idx);
            affected.add(idx);
         }
      }

      RoadRenderer.AtomicUpdate update = null;
      if (atomic || !obsolete.isEmpty()) {
         HashSet<UUID> live = new HashSet<>(affected);
         live.removeIf(idx -> !ClientRoads.INDEX.roads.containsKey(idx));
         update = new RoadRenderer.AtomicUpdate(live, affected);

         for (UUID idx : affected) {
            displayUpdates.put(idx, update);
         }

         for (UUID idx : live) {
            atomicUpdates.put(idx, update);
         }

         HashSet<RoadTerrainMesh.Section> previous = new HashSet<>();

         for (Map<RoadRenderMesh.Section, RoadRenderer.GpuSection> map : List.of(regions, dormant)) {
            for (RoadRenderer.GpuSection section : map.values()) {
               boolean touches = !Collections.disjoint(section.pieces.keySet(), affected) || !Collections.disjoint(section.handoffs, obsolete);

               for (List<RoadRenderer.OwnerRange> ranges : section.owners.values()) {
                  for (RoadRenderer.OwnerRange range : ranges) {
                     if (affected.contains(range.owner)) {
                        touches = true;
                        if (map == regions && range.terrain != null) {
                           previous.add(range.terrain);
                        }
                     }
                  }
               }

               if (touches) {
                  if (map == regions && regionUsable(section.key)) {
                     attach(section, update);
                     superseded(section, obsolete);
                  } else {
                     discardAllocation(section);
                  }
               }
            }
         }

         RoadTerrainModels.beginBatch(affected, previous);
      }

      for (UUID idx : affected) {
         versions.merge(idx, 1, Integer::sum);
         if (!ClientRoads.INDEX.roads.containsKey(idx)) {
            RoadRenderer.Job pending = jobs.remove(idx);
            if (pending != null) {
               pending.cancel();
            }

            dirty.remove(idx);
            priorityDirty.remove(idx);
            remove(idx);
            versions.remove(idx);
         } else {
            RoadTerrainModels.remove(idx);
            enqueue(idx);
         }
      }

      if (update != null && update.members.isEmpty()) {
         finishAtomic(update);
      }
   }

   public static void reset() {
      if (pendingUpload != null) {
         pendingUpload.cancel();
         pendingUpload = null;
      }

      clearPreviewBuffer();
      RoadGpuTimer.reset();
      clearPreviewFurniture();
      previewRecords.clear();
      previewContextRevision++;
      epoch++;
      RoadTerrainModels.clear();
      lightSnapshots.clear();
      lightPollCursor = 0;
      dirty.clear();
      priorityDirty.clear();
      buildScope.clear();
      versions.clear();
      jobs.values().forEach(j -> j.cancel());
      jobs.clear();
      roadSections.clear();
      meshVersions.clear();
      terrainVersions.clear();
      preparations.clear();
      atomicUpdates.clear();
      displayUpdates.clear();

      for (RoadRenderer.GpuSection s : regions.values()) {
         s.close();
      }

      regions.clear();

      for (RoadRenderer.GpuSection s : dormant.values()) {
         s.close();
      }

      dormant.clear();
      retainedBytes = 0L;
      unloadedGpu.clear();
      residencyDirty.clear();
      signalBatches.clear();
      signalTypesUsed.clear();
      visible.clear();
      glassVisible.clear();
      lastDistant = 0;
      lastUploads = 0;
      lastDraws = 0;
      lastVertices = 0L;
      lastSurfaceIndices = 0L;
      averageCpuMs = 0.0;
   }

   static void shaderMode(boolean extended) {
      if (extendedEncoding == extended) {
         if (lastBudget != (long)((Integer)RoadClientConfig.INACTIVE_VBO_CACHE_MIB.get()).intValue()) {
            trimDormant();
         }
      } else {
         extendedEncoding = extended;
         if (pendingUpload != null) {
            pendingUpload.cancel();
            pendingUpload = null;
         }

         clearPreviewBuffer();

         for (Entry<RoadRenderMesh.Section, RoadRenderer.GpuSection> e : regions.entrySet()) {
            if (!dormant.containsKey(e.getKey())) {
               RoadRenderer.GpuSection next = new RoadRenderer.GpuSection(e.getValue().key);
               next.pieces.putAll(e.getValue().pieces);
               next.bounds = e.getValue().bounds;
               next.revision = e.getValue().revision;
               dormant.put(e.getKey(), next);
            }
         }

         Map<RoadRenderMesh.Section, RoadRenderer.GpuSection> old = regions;
         regions = dormant;
         dormant = old;
         visible.clear();
         glassVisible.clear();

         for (RoadRenderer.GpuSection section : regions.values()) {
            section.checkedLightAt = -4611686018427387904L;
            section.lightCheck = null;

            for (UUID id : section.pieces.keySet()) {
               RoadRenderer.AtomicUpdate group = displayUpdates.get(id);
               if (group != null && RoadTerrainModels.regionLoaded(section.key)) {
                  attach(section, group);
               }
            }
         }

         for (RoadRenderer.GpuSection section : dormant.values()) {
            if (!section.handoffs.isEmpty()) {
               discardAllocation(section);
            }
         }

         trimDormant();
      }
   }

   private static void discardAllocation(RoadRenderer.GpuSection section) {
      if (dormant.get(section.key) == section) {
         retainedBytes = Math.max(0L, retainedBytes - gpuBytes(section));
      }

      if (pendingUpload != null && pendingUpload.section == section) {
         pendingUpload.cancel();
         pendingUpload = null;
      }

      section.close();
      section.nearVertices = section.farVertices = section.surfaceVertices = 0L;
      section.dirty = true;
      updateBounds(section);
      completedHandoff(section);
   }

   static void chunkUnloaded(RoadTerrainMesh.Chunk chunk) {
      chunkChanged(ChunkPos.asLong(chunk.x(), chunk.z()));
      if (!displayUpdates.isEmpty()) {
         RoadRenderMesh.Section key = new RoadRenderMesh.Section(Math.floorDiv(chunk.x(), 4), 0, Math.floorDiv(chunk.z(), 4));
         if (!regionUsable(key)) {
            for (Map<RoadRenderMesh.Section, RoadRenderer.GpuSection> map : List.of(regions, dormant)) {
               RoadRenderer.GpuSection section = map.get(key);
               if (section != null && !section.handoffs.isEmpty()) {
                  discardAllocation(section);
               }
            }
         }
      }
   }

   private static long gpuBytes(RoadRenderer.GpuSection s) {
      return (s.nearVertices + s.farVertices - s.surfaceVertices) * 64L + s.lightReads.bytes();
   }

   static void chunkChanged(long chunk) {
      residencyDirty.add(new RoadRenderMesh.Section(Math.floorDiv((int)chunk, 4), 0, Math.floorDiv((int)(chunk >> 32), 4)));
   }

   private static void reconcileResidency(RoadRenderMesh.Section key) {
      residencyChecks++;
      boolean loaded = regionUsable(key);
      if (!loaded && pendingUpload != null && pendingUpload.section.key.equals(key)) {
         pendingUpload.cancel();
         pendingUpload = null;
         residencyUploadCancels++;
      }

      for (Map<RoadRenderMesh.Section, RoadRenderer.GpuSection> map : List.of(regions, dormant)) {
         RoadRenderer.GpuSection section = map.get(key);
         if (section != null) {
            if (!loaded && !section.handoffs.isEmpty()) {
               discardAllocation(section);
            }

            unloadedGpu.update(section, gpuBytes(section), loaded);
         }
      }
   }

   private static void trimUnloadedGpu() {
      long deadline = System.nanoTime() + 500000L;
      RoadScanBudget scans = new RoadScanBudget(16, deadline);

      while (!residencyDirty.isEmpty() && scans.take()) {
         Iterator<RoadRenderMesh.Section> iterator = residencyDirty.iterator();
         RoadRenderMesh.Section key = iterator.next();
         iterator.remove();
         reconcileResidency(key);
      }

      long limit = (long)((Integer)RoadClientConfig.UNLOADED_VBO_CACHE_MIB.get()).intValue() * 1048576L;
      RoadScanBudget evictions = new RoadScanBudget(8, deadline);

      while (evictions.take()) {
         RoadRenderer.GpuSection section = unloadedGpu.takeOldest(limit);
         if (section == null) {
            break;
         }

         if (!regionUsable(section.key)) {
            discardAllocation(section);
            residencyEvictions++;
         }
      }
   }

   private static void trimDormant() {
      lastBudget = (long)((Integer)RoadClientConfig.INACTIVE_VBO_CACHE_MIB.get()).intValue();
      long limit = lastBudget * 1048576L;
      retainedBytes = 0L;

      for (RoadRenderer.GpuSection s : dormant.values()) {
         retainedBytes = retainedBytes + gpuBytes(s);
      }

      if (retainedBytes > limit) {
         ArrayList<RoadRenderer.GpuSection> ordered = new ArrayList<>(dormant.values());
         ordered.sort(Comparator.comparingLong(sx -> sx.lastUsed));

         for (RoadRenderer.GpuSection s : ordered) {
            if (retainedBytes <= limit) {
               break;
            }

            discardAllocation(s);
         }
      }
   }

   private static void removePiece(Map<RoadRenderMesh.Section, RoadRenderer.GpuSection> map, UUID id, RoadRenderMesh.Section key) {
      RoadRenderer.GpuSection section = map.get(key);
      if (section != null && section.pieces.remove(id) != null) {
         if (map == regions
            && displayUpdates.containsKey(id)
            && section.owners.values().stream().anyMatch(ranges -> ranges.stream().anyMatch(range -> range.owner.equals(id)))) {
            section.retained.add(id);
         }

         if (pendingUpload != null && pendingUpload.section == section) {
            pendingUpload.cancel();
            pendingUpload = null;
         }

         section.revision++;
         section.dirty = true;
         if (section.pieces.isEmpty() && section.retained.isEmpty()) {
            map.remove(key);
            section.close();
            completedHandoff(section);
         } else {
            updateBounds(section);
            if (section.retained.isEmpty() && section.pieces.keySet().stream().noneMatch(ClientRoads::active)) {
               discardAllocation(section);
            }
         }
      }
   }

   private static void updateBounds(RoadRenderer.GpuSection section) {
      section.bounds = section.uploadedBounds;

      for (RoadRenderer.Prepared p : section.pieces.values()) {
         section.bounds = section.bounds == null ? p.bounds : section.bounds.minmax(p.bounds);
      }
   }

   private static void remove(UUID id) {
      RoadTerrainModels.remove(id);
      signalBatches.remove(id);
      meshVersions.remove(id);
      terrainVersions.remove(id);
      preparations.remove(id);

      for (Map<RoadRenderMesh.Section, RoadRenderer.GpuSection> map : List.of(regions, dormant)) {
         for (RoadRenderMesh.Section key : roadSections.getOrDefault(id, Set.of())) {
            removePiece(map, id, key);
         }
      }

      roadSections.remove(id);
      trimDormant();
   }

   private static void installPieces(
      Map<RoadRenderMesh.Section, RoadRenderer.GpuSection> map,
      UUID id,
      Set<RoadRenderMesh.Section> old,
      Map<RoadRenderMesh.Section, RoadRenderer.Prepared> pieces,
      boolean inactive
   ) {
      retentionRevision++;

      for (RoadRenderMesh.Section key : old) {
         if (!pieces.containsKey(key)) {
            removePiece(map, id, key);
         }
      }

      pieces.forEach((keyx, p) -> {
         RoadRenderer.GpuSection section = map.computeIfAbsent(keyx, RoadRenderer.GpuSection::new);
         RoadRenderer.AtomicUpdate group = displayUpdates.get(id);
         if (group != null) {
            if (map == regions && RoadTerrainModels.regionLoaded(keyx)) {
               attach(section, group);
            } else {
               discardAllocation(section);
            }
         }

         section.pieces.put(id, p);
         updateBounds(section);
         section.dirty = true;
         section.revision++;
      });
   }

   static Map<UUID, Long> retainedRoadBytes(Collection<UUID> ids) {
      Map<UUID, Long> totals = new HashMap<>();

      for (UUID id : ids) {
         totals.put(id, retainedRoadBytes(id));
      }

      return totals;
   }

   static long retainedRoadBytes(UUID id) {
      long total = RoadTerrainModels.retainedBytes(id) + allocatedRoadBytes.getOrDefault(id, 0L);
      RoadRenderer.Preparation preparation = preparations.get(id);
      if (preparation != null) {
         if (preparation.structures != null) {
            total += preparation.structures.bytes();
         }

         if (preparation.context != null) {
            total += preparation.context.bytes();
         }
      }

      for (RoadRenderMesh.Section key : roadSections.getOrDefault(id, Set.of())) {
         RoadRenderer.GpuSection section = regions.get(key);
         if (section != null) {
            RoadRenderer.Prepared p = section.pieces.get(id);
            if (p != null) {
               total += p.cpuBytes;
            }
         }
      }

      return total;
   }

   static void discardColdGpu(UUID id) {
      for (Map<RoadRenderMesh.Section, RoadRenderer.GpuSection> map : List.of(regions, dormant)) {
         for (RoadRenderMesh.Section key : roadSections.getOrDefault(id, Set.of())) {
            RoadRenderer.GpuSection section = map.get(key);
            if (section != null && !section.pieces.keySet().stream().anyMatch(ClientRoads::active)) {
               if (pendingUpload != null && pendingUpload.section == section) {
                  pendingUpload.cancel();
                  pendingUpload = null;
               }

               discardAllocation(section);
            }
         }
      }
   }

   static void discardDerived(UUID id) {
      versions.merge(id, 1, Integer::sum);
      RoadRenderer.Job job = jobs.remove(id);
      if (job != null) {
         job.cancel();
      }

      remove(id);
      enqueue(id);
   }

   static void suspend(UUID id) {
      RoadTerrainModels.suspend(id);
   }

   static void resume(UUID id) {
      RoadTerrainModels.resume(id);
      enqueue(id);
   }

   private static void enqueue(UUID id) {
      dirty.add(id);
      if (buildVisible(id)) {
         priorityDirty.add(id);
      }
   }

   private static boolean buildVisible(UUID id) {
      if (atomicUpdates.containsKey(id)) {
         return ClientRoads.INDEX.roads.containsKey(id);
      } else if (!ClientRoads.active(id)) {
         return false;
      } else {
         RoadIndex.Built road = ClientRoads.INDEX.roads.get(id);
         return road == null
            ? false
            : RoadVisibility.within(
               road.mesh.min().x(),
               road.mesh.min().z(),
               road.mesh.max().x(),
               road.mesh.max().z(),
               cameraPosition.x(),
               cameraPosition.z(),
               viewChunks()
            );
      }
   }

   /**
    * Road VBO view distance in chunks. With Distant Horizons the real road geometry
    * has to cover the band between the vanilla render distance and the DH LOD,
    * otherwise that band only shows the flat LOD color plus block shadows and the
    * road looks like it disappeared. Without DH this is simply the render distance.
    */
   static int viewChunks() {
      int chunks = Minecraft.getInstance().options.getEffectiveRenderDistance() + 2;
      if (ModList.get().isLoaded("distanthorizons")) {
         chunks = Math.max(chunks, (Integer)RoadClientConfig.DISTANT_ROAD_CHUNKS.get());
      }

      return chunks;
   }

   /**
    * A region is usable when its terrain is loaded, or when it still lies inside the
    * extended road view distance (DH band). The latter allows roads to upload before
    * the player walks next to them; light samples for unloaded positions fall back to
    * full sky light and are refreshed by the normal light polling later.
    */
   private static boolean regionUsable(RoadRenderMesh.Section region) {
      if (RoadTerrainModels.regionLoaded(region)) {
         return true;
      }

      double minX = (double)region.x() * 64.0;
      double minZ = (double)region.z() * 64.0;
      return RoadVisibility.within(
         minX, minZ, minX + 64.0, minZ + 64.0, cameraPosition.x(), cameraPosition.z(), viewChunks()
      );
   }

   static boolean terrainSourcesReady() {
      for (UUID id : ClientRoads.INDEX.roads.keySet()) {
         if (buildVisible(id)) {
            RoadRenderer.Preparation p = preparations.get(id);
            if (p == null) {
               if (!Objects.equals(terrainVersions.get(id), versions.getOrDefault(id, 0))) {
                  return false;
               }
            } else {
               for (RoadMeshSurfaces.Window w : p.windows) {
                  if (w.distance(cameraPosition) < Math.pow((double)(viewChunks() * 16), 2.0)
                     && !p.terrain.contains(w)) {
                     return false;
                  }
               }
            }
         }
      }

      return true;
   }

   static boolean terrainTileReady(UUID road, RoadTerrainMesh.Chunk chunk) {
      RoadRenderer.Preparation p = preparations.get(road);
      if (p == null) {
         return true;
      } else {
         double x = (double)chunk.x() * 16.0;
         double z = (double)chunk.z() * 16.0;

         for (RoadMeshSurfaces.Window w : p.windows) {
            if (w.min().x() < x + 16.0 && w.max().x() > x && w.min().z() < z + 16.0 && w.max().z() > z && !p.terrain.contains(w)) {
               return false;
            }
         }

         return true;
      }
   }

   private static void refreshBuildScope() {
      boolean exact = RoadTerrainModels.requested();
      if (buildScope.changed(cameraPosition.x(), cameraPosition.z(), viewChunks(), exact)) {
         movementRescans++;
         LinkedHashSet<UUID> ready = new LinkedHashSet<>();

         for (UUID id : ClientRoads.INDEX.roads.keySet()) {
            movementCandidates++;
            int version = versions.getOrDefault(id, 0);
            if ((!Objects.equals(meshVersions.get(id), version) || exact && !Objects.equals(terrainVersions.get(id), version)) && buildVisible(id)) {
               ready.add(id);
            }
         }

         dirty.addAll(ready);
         priorityDirty.addAll(ready);
      }
   }

   private static void update() {
      refreshBuildScope();
      int completed = 0;
      long deadline = System.nanoTime() + 750000L;
      Iterator<Entry<UUID, RoadRenderer.Job>> it = jobs.entrySet().iterator();

      while (it.hasNext() && completed < 4 && System.nanoTime() < deadline) {
         Entry<UUID, RoadRenderer.Job> entry = it.next();
         RoadRenderer.Job job = entry.getValue();
         if (!job.future.isDone() && !buildVisible(entry.getKey())) {
            dirty.add(entry.getKey());
            job.cancel();
            it.remove();
         } else if (job.future.isDone()) {
            it.remove();
            completed++;
            if (job.epoch == epoch && job.version == versions.getOrDefault(entry.getKey(), 0)) {
               try {
                  RoadRenderer.MeshResult result = job.future.join();
                  RoadRenderer.Preparation p = job.preparation;
                  UUID id = entry.getKey();
                  RoadRenderer.AtomicUpdate atomic = atomicUpdates.get(id);
                  if (atomic != null) {
                     if (result.terrain() != null) {
                        atomic.terrain.put(id, result.terrain());
                        p.terrain.add(job.window);
                     }

                     if (result.regions() != null) {
                        p.replace(job.window, result.regions());
                        p.surfaces.add(job.window);
                     }

                     finishAtomic(atomic);
                     if (atomicUpdates.containsKey(id)
                        && (p.surfaces.size() < p.windows.size() || RoadTerrainModels.requested() && p.terrain.size() < p.windows.size())) {
                        enqueue(id);
                     }
                  } else {
                     if (result.terrain() != null) {
                        boolean first = p.terrain.isEmpty();
                        p.terrain.add(job.window);
                        RoadTerrainModels.installPublication(id, result.terrain(), first);
                        if (p.terrain.size() == p.windows.size()) {
                           terrainVersions.put(id, job.version);
                        }
                     }

                     if (result.regions() != null) {
                        HashSet<RoadRenderMesh.Section> replacedKeys = new HashSet<>(p.windowPieces.getOrDefault(job.window, Map.of()).keySet());
                        Map<RoadRenderMesh.Section, RoadRenderer.Prepared> changed = p.replace(job.window, result.regions());
                        replacedKeys.removeAll(p.pieces.keySet());
                        installPieces(regions, id, replacedKeys, changed, false);
                        installPieces(dormant, id, replacedKeys, changed, true);
                        p.surfaces.add(job.window);
                        HashSet<RoadRenderMesh.Section> keys = new HashSet<>(roadSections.getOrDefault(id, Set.of()));
                        keys.removeAll(replacedKeys);
                        keys.addAll(changed.keySet());
                        roadSections.put(id, Set.copyOf(keys));
                        if (p.surfaces.size() == p.windows.size()) {
                           for (RoadRenderMesh.Section key : p.previous) {
                              if (!p.pieces.containsKey(key)) {
                                 removePiece(regions, id, key);
                                 removePiece(dormant, id, key);
                              }
                           }

                           roadSections.put(id, Set.copyOf(p.pieces.keySet()));
                           meshVersions.put(id, job.version);
                        }

                        signalBatches.remove(id);
                        trimDormant();
                     }

                     if (p.surfaces.size() < p.windows.size() || RoadTerrainModels.requested() && p.terrain.size() < p.windows.size()) {
                        enqueue(id);
                     }
                  }
               } catch (CompletionException var20) {
                  ClientRoads.error = "道路网格构建失败：" + var20.getCause().getMessage();
               }
            }
         }
      }

      RoadScanBudget candidates = new RoadScanBudget(Math.min(64, dirty.size()), deadline);

      while (!dirty.isEmpty() && jobs.size() < 4 && candidates.take()) {
         buildCandidateChecks++;
         Iterator<UUID> itx = (priorityDirty.isEmpty() ? dirty : priorityDirty).iterator();
         UUID id = itx.next();
         itx.remove();
         dirty.remove(id);
         RoadIndex.Built built = ClientRoads.INDEX.roads.get(id);
         if (built == null) {
            remove(id);
         } else if (!buildVisible(id)) {
            dirty.add(id);
         } else if (jobs.containsKey(id)) {
            enqueue(id);
         } else {
            int version = versions.getOrDefault(id, 0);
            RoadRenderer.Preparation preparation = preparations.get(id);
            if (preparation == null || preparation.version != version) {
               preparation = new RoadRenderer.Preparation(id, version, built);
               preparations.put(id, preparation);
            }

            boolean needTerrain = RoadTerrainModels.requested();
            RoadMeshSurfaces.Window window = preparation.next(needTerrain);
            if (window != null) {
               boolean surfaceWanted = !preparation.surfaces.contains(window);
               boolean terrainWanted = needTerrain && !preparation.terrain.contains(window);
               List<RoadIndex.Built> neighbors = ClientRoads.INDEX.surfaceNeighbors(built);
               List<RoadIndex.Built> joined = new ArrayList<>();
               List<RoadIndex.Built> higher = new ArrayList<>();

               for (RoadIndex.Built other : neighbors) {
                  if (!(other.mesh.min().y() > built.mesh.max().y() + 0.2) && !(other.mesh.max().y() < built.mesh.min().y() - 0.2)) {
                     joined.add(other);
                     boolean priority = RoadSurface.higherPriority(other.record.id(), other.mesh, id, built.mesh);
                     if (priority) {
                        higher.add(other);
                     }
                  }
               }

               long generation = epoch;
               RoadRenderer.Preparation windowPreparation = preparation;
               AtomicBoolean cancelled = new AtomicBoolean();
               jobs.put(
                  id,
                  new RoadRenderer.Job(
                     version,
                     generation,
                     CompletableFuture.supplyAsync(
                        () -> {
                           if (generation == epoch && version == versions.getOrDefault(id, 0)) {
                              RoadRenderer.MeshResult var16;
                              try (RoadPlanningBudget scope = RoadPlanningBudget.cancellable(
                                    "客户端道路网格", () -> cancelled.get() || generation != epoch || version != versions.getOrDefault(id, 0)
                                 )) {
                                 if (windowPreparation.context == null) {
                                    windowPreparation.context = new RoadMeshSurfaces.Context(built, joined, higher, windowPreparation.windows);
                                 }

                                 RoadMeshSurfaces.Pair surfaces = windowPreparation.context.prepare(window, surfaceWanted, terrainWanted);
                                 RoadPlanningBudget.check();
                                 RoadTerrainSourceWindows.Publication terrain = terrainWanted
                                    ? windowPreparation.terrainIndex.replace(window, RoadTerrainMesh.source(surfaces.exact()))
                                    : null;
                                 Map<RoadRenderMesh.Section, RoadRenderer.Prepared> result = surfaceWanted ? new LinkedHashMap<>() : null;
                                 if (surfaceWanted) {
                                    if (windowPreparation.structures == null) {
                                       windowPreparation.structures = new RoadMeshSurfaces.Structures(built, windowPreparation.windows);
                                    }

                                    windowPreparation.structures
                                       .layers(surfaces.surface(), window)
                                       .forEach((keyx, piece) -> result.put(keyx, RoadRenderer.Prepared.of(piece)));
                                 }

                                 var16 = new RoadRenderer.MeshResult(result, terrain);
                              } finally {
                                 workerRetentionRevision.incrementAndGet();
                              }

                              return var16;
                           } else {
                              return new RoadRenderer.MeshResult(Map.of(), null);
                           }
                        },
                        WORKER
                     ),
                     preparation,
                     window,
                     cancelled
                  )
               );
            }
         }
      }
   }

   @SubscribeEvent
   public static void render(RenderLevelStageEvent event) {
      ShaderPackState.State pack = ShaderPackState.current();
      if (!pack.shadowPass()) {
         if (event.getStage() == Stage.AFTER_TRANSLUCENT_BLOCKS) {
            if (Minecraft.getInstance().level != null) {
               Vec3 camera = event.getCamera().getPosition();
               cameraPosition = new RoadGeometry.V(camera.x, camera.y, camera.z);
               glassVisible.clear();

               for (RoadRenderer.GpuSection s : visible) {
                  List<VertexBuffer> list = (s.distant ? s.far : s.near).get(RoadSurface.Texture.NOISE_BARRIER_GLASS);
                  if (list != null && !list.isEmpty()) {
                     glassVisible.add(s);
                  }
               }

               if (!glassVisible.isEmpty()) {
                  glassVisible.sort(Comparator.<RoadRenderer.GpuSection>comparingDouble(sx -> sx.bounds.distanceToSqr(camera)).reversed());

                  for (RoadRenderer.GpuSection sx : glassVisible) {
                     RoadGeometry.V origin = sx.key.origin();
                     sx.transform
                        .set(event.getModelViewMatrix())
                        .translate((float)(origin.x() - camera.x), (float)(origin.y() - camera.y), (float)(origin.z() - camera.z));
                  }

                  draw(glassVisible, MATERIALS.get(RoadSurface.Texture.NOISE_BARRIER_GLASS), RoadSurface.Texture.NOISE_BARRIER_GLASS, event);
               }
            }
         } else if (event.getStage() == Stage.AFTER_BLOCK_ENTITIES) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
               RoadSignVisibility.beginFrame();
               long started = System.nanoTime();
               lastMatrixUploads = 0;
               lastDistant = 0;
               lastDraws = 0;
               lastVertices = 0L;
               lastSurfaceIndices = 0L;
               boolean shadowPass = ShaderPackState.current().shadowPass();
               if (!shadowPass) {
                  RoadTerrainModels.beginFrame();
               }

               Vec3 currentCamera = event.getCamera().getPosition();
               cameraPosition = new RoadGeometry.V(currentCamera.x, currentCamera.y, currentCamera.z);
               if (!shadowPass) {
                  update();
                  RoadTerrainModels.publish();
                  trimUnloadedGpu();
               }

               Vec3 camera = event.getCamera().getPosition();
               cameraPosition = new RoadGeometry.V(camera.x, camera.y, camera.z);
               int uploads = 0;
               visible.clear();
               glassVisible.clear();
               int renderChunks = viewChunks();

               for (RoadRenderer.GpuSection sx : regions.values()) {
                  if (sx.bounds != null && (!sx.retained.isEmpty() || !sx.pieces.keySet().stream().noneMatch(ClientRoads::active))) {
                     double d = sx.bounds.distanceToSqr(camera);
                     if (RoadVisibility.within(sx.bounds.minX, sx.bounds.minZ, sx.bounds.maxX, sx.bounds.maxZ, camera.x, camera.z, renderChunks)) {
                        if (d > 12544.0) {
                           sx.distant = true;
                        } else if (d < 7744.0) {
                           sx.distant = false;
                        }

                        RoadGeometry.V origin = sx.key.origin();
                        sx.transform
                           .set(event.getModelViewMatrix())
                           .translate((float)(origin.x() - camera.x), (float)(origin.y() - camera.y), (float)(origin.z() - camera.z));
                        visible.add(sx);
                        sx.lastUsed = ++useClock;
                        if (sx.distant) {
                           lastDistant++;
                        }
                     }
                  }
               }

               if (!shadowPass) {
                  RoadUploadBudget.beginFrame((long)((Integer)RoadClientConfig.VBO_UPLOAD_MICROS.get()).intValue(), SmartSignClient.pendingUploadBytes());
                  uploads = uploadVisible();
               } else {
                  RoadUploadBudget.drawOnly();
               }

               cameraPosition = new RoadGeometry.V(camera.x, camera.y, camera.z);
               PoseStack pose = event.getPoseStack();
               BufferSource buffers = mc.renderBuffers().bufferSource();
               if (!visible.isEmpty()) {
                  buffers.endBatch();
               }

               RoadGpuTimer.begin();
               if (!visible.isEmpty()) {
                  drawPrepared(visible, event.getPoseStack(), event.getProjectionMatrix(), true);
               }

               RoadGpuTimer.end();
               cameraPosition = new RoadGeometry.V(camera.x, camera.y, camera.z);
               pose.pushPose();
               if (mc.level != null) {
                  signalTypesUsed.clear();

                  for (RoadIndex.Built road : ClientRoads.INDEX.signalsNear((int)Math.floor(camera.x) >> 4, (int)Math.floor(camera.z) >> 4)) {
                     if (ClientRoads.active(road.record.id())
                        && road.signalBounds != null
                        && !(road.signalBounds.distanceToSqr(camera) >= 16384.0)
                        && event.getFrustum().isVisible(road.signalBounds)) {
                        List<RoadSurface.Face> faces = road.signalFaces(mc.level.getGameTime());
                        RoadRenderer.SignalCache cached = signalBatches.get(road.record.id());
                        if (cached == null || cached.faces() != faces) {
                           cached = new RoadRenderer.SignalCache(faces, new RoadSignalBatch(faces));
                           signalBatches.put(road.record.id(), cached);
                        }

                        cached.batch().emit(pose, buffers, cameraPosition, RoadRenderer::signalMaterial, signalTypesUsed);
                     }
                  }

                  for (RenderType type : signalTypesUsed) {
                     buffers.endBatch(type);
                  }
               }

               SmartSignClient.render(event);
               TollNameRenderer.render(event);
               TollPreview.render(event);
               drawPreview(event);
               pose.popPose();
               RoadUploadBudget.endFrame();
               lastUploads = uploads;
               double elapsed = (double)(System.nanoTime() - started) / 1000000.0;
               lastCpuMs = elapsed;
               averageCpuMs = averageCpuMs == 0.0 ? elapsed : averageCpuMs * 0.9 + elapsed * 0.1;
            }
         }
      }
   }

   private static void clearPreviewBuffer() {
      if (ghostBuffer != null) {
         ghostBuffer.close();
      }

      ghostBuffer = null;
      ghostMesh = null;
      ghostRoads = null;
      ghostFaces = null;
   }

   private static void drawPreview(RenderLevelStageEvent event) {
      if (ClientRoads.preview == null) {
         clearPreviewBuffer();
         clearPreviewFurniture();
         if (!previewRecords.isEmpty()) {
            previewCandidates(List.of());
         }
      } else {
         if (furniturePreview != ClientRoads.preview
            || furnitureRevision != ClientRoads.INDEX.revision()
            || furnitureContextRevision != previewContextRevision
            || furnitureGround != null && !furnitureGround.sameLevel(Minecraft.getInstance().level)) {
            clearPreviewFurniture();
         }

         boolean deleting = Minecraft.getInstance().screen instanceof RoadDeleteScreen || ClientRoads.conflictPreview;
         boolean junction = ClientRoads.preview == JunctionScreen.previewMesh;
         boolean unchanged = savedPreview(ClientRoads.preview);
         boolean furniture = !unchanged
            && !deleting
            && ClientRoads.nodePreviews.size() < 3
            && ClientRoads.preview.settings().style() != RoadGeometry.Style.TOLL
            && RoadProfile.modern(ClientRoads.preview.settings().style());
         boolean furnitureChanged = furniture
            && (
               furniturePreview != ClientRoads.preview
                  || furnitureRevision != ClientRoads.INDEX.revision()
                  || furnitureContextRevision != previewContextRevision
                  || furnitureGround == null
                  || !furnitureGround.current(Minecraft.getInstance().level)
            );
         if (ghostMesh != ClientRoads.preview
            || ghostRoads != ClientRoads.nodePreviews
            || ghostFaces != JunctionScreen.previewFaces
            || ghostDelete != deleting
            || ghostJunction != junction
            || ghostConflict != ClientRoads.conflictPreview
            || furnitureChanged) {
            clearPreviewBuffer();
            ghostOrigin = ClientRoads.preview.first().center();
            RoadGeometry.V camera = cameraPosition;
            BufferBuilder builder = previewArena.begin();
            cameraPosition = ghostOrigin;

            try {
               emitPreview(new PoseStack(), builder);
            } finally {
               cameraPosition = camera;
            }

            VertexBuffer transform = new VertexBuffer(Usage.STATIC);

            try {
               transform.bind();
               transform.upload(builder.buildOrThrow());
               ghostBuffer = transform;
            } catch (RuntimeException var17) {
               transform.close();
               throw var17;
            } finally {
               VertexBuffer.unbind();
            }

            ghostMesh = ClientRoads.preview;
            ghostRoads = ClientRoads.nodePreviews;
            ghostFaces = JunctionScreen.previewFaces;
            ghostDelete = deleting;
            ghostJunction = junction;
            ghostConflict = ClientRoads.conflictPreview;
         }

         RenderType type = ClientRoads.conflictPreview ? CONFLICT : GHOST;
         type.setupRenderState();
         GL11.glColorMask(true, true, true, true);
         ShaderInstance shader = RenderSystem.getShader();
         Matrix4f transform = new Matrix4f(event.getModelViewMatrix())
            .translate(
               (float)(ghostOrigin.x() - cameraPosition.x()), (float)(ghostOrigin.y() - cameraPosition.y()), (float)(ghostOrigin.z() - cameraPosition.z())
            );
         ghostBuffer.bind();
         setupShader(shader, event);
         if (ClientRoads.conflictPreview) {
            if (shader.FOG_START != null) {
               shader.FOG_START.set(1000000.0F);
               shader.FOG_START.upload();
            }

            if (shader.FOG_END != null) {
               shader.FOG_END.set(1000001.0F);
               shader.FOG_END.upload();
            }
         }

         if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(transform);
            shader.MODEL_VIEW_MATRIX.upload();
         }

         ghostBuffer.draw();
         lastDraws++;
         shader.clear();
         VertexBuffer.unbind();
         type.clearRenderState();
      }
   }

   private static void emitPreview(PoseStack pose, VertexConsumer c) {
      Minecraft mc = Minecraft.getInstance();
      boolean conflict = ClientRoads.conflictPreview;
      double lift = conflict ? 0.06 : -0.025;
      int walkColor = conflict ? 16735557 : 10348186;
      List<RoadGeometry.Mesh> previews = ClientRoads.nodePreviews.isEmpty() ? List.of(ClientRoads.preview) : ClientRoads.nodePreviews;

      for (RoadGeometry.Mesh mesh : previews) {
         for (int i = 1; i < mesh.samples().size(); i++) {
            RoadGeometry.Sample a = mesh.samples().get(i - 1);
            RoadGeometry.Sample b = mesh.samples().get(i);
            RoadGeometry.V l = a.at(a.halfWidth(), lift);
            RoadGeometry.V r = a.at(-a.halfWidth(), lift);
            RoadGeometry.V ll = b.at(b.halfWidth(), lift);
            RoadGeometry.V rr = b.at(-b.halfWidth(), lift);
            triangle(
               pose, c, l, r, rr, !(mc.screen instanceof RoadDeleteScreen) && !ClientRoads.conflictPreview ? 4185309 : 16735557, 105, 15728880, false, true
            );
            triangle(
               pose, c, l, rr, ll, !(mc.screen instanceof RoadDeleteScreen) && !ClientRoads.conflictPreview ? 4185309 : 16735557, 105, 15728880, false, true
            );
         }
      }

      if (ClientRoads.preview.settings().style() == RoadGeometry.Style.RAILBED) {
         for (RoadStructures.Part part : RailbedScreen.structures) {
            for (RoadSurface.Face face : part.faces()) {
               List<RoadGeometry.V> v = face.points();

               for (int i = 1; i + 1 < v.size(); i++) {
                  triangle(pose, c, v.get(0), v.get(i), v.get(i + 1), 4185309, 105, 15728880, false, true);
               }
            }
         }
      }

      for (RoadGeometry.Mesh mesh : previews) {
         if (!savedPreview(mesh) && mesh.settings().options().sidewalk().enabled()) {
            RoadSidewalks.Config walk = mesh.settings().options().sidewalk();
            if (walk.smooth()) {
               for (RoadStructures.Part part : RoadSidewalks.parts(mesh, walk)) {
                  if (part.material() != RoadStructures.Material.TACTILE) {
                     List<RoadGeometry.V> v = part.base().stream().map(p -> p.add(new RoadGeometry.V(0.0, part.height() + 0.015, 0.0))).toList();
                     triangle(pose, c, v.get(0), v.get(1), v.get(2), walkColor, 105, 15728880, false, true);
                     triangle(pose, c, v.get(0), v.get(2), v.get(3), walkColor, 105, 15728880, false, true);
                  }
               }
            } else {
               for (RoadSidewalks.Cell block : RoadSidewalks.cells(mesh, walk)) {
                  RoadGeometry.V a = new RoadGeometry.V((double)block.x(), (double)block.y() + 1.03, (double)block.z());
                  RoadGeometry.V b = a.add(new RoadGeometry.V(1.0, 0.0, 0.0));
                  RoadGeometry.V d = a.add(new RoadGeometry.V(0.0, 0.0, 1.0));
                  RoadGeometry.V e = a.add(new RoadGeometry.V(1.0, 0.0, 1.0));
                  triangle(pose, c, a, d, e, walkColor, 105, 15728880, false, true);
                  triangle(pose, c, a, e, b, walkColor, 105, 15728880, false, true);
               }
            }
         }
      }

      if (ClientRoads.preview == JunctionScreen.previewMesh) {
         for (RoadSurface.Face face : JunctionScreen.previewFaces) {
            List<RoadGeometry.V> vertices = face.points();

            for (int i = 1; i < vertices.size() - 1; i++) {
               triangle(
                  pose,
                  c,
                  vertices.get(0).add(new RoadGeometry.V(0.0, 0.035, 0.0)),
                  vertices.get(i).add(new RoadGeometry.V(0.0, 0.035, 0.0)),
                  vertices.get(i + 1).add(new RoadGeometry.V(0.0, 0.035, 0.0)),
                  face.color(),
                  205,
                  15728880,
                  false,
                  true
               );
            }
         }
      }

      if (!savedPreview(ClientRoads.preview)
         && !ClientRoads.conflictPreview
         && !(mc.screen instanceof RoadDeleteScreen)
         && ClientRoads.nodePreviews.size() < 3
         && ClientRoads.preview.settings().style() != RoadGeometry.Style.TOLL
         && ClientRoads.preview.settings().style() != RoadGeometry.Style.RAILBED) {
         for (RoadSurface.Face face : previewFurniture(ClientRoads.preview)) {
            List<RoadGeometry.V> vertices = face.points();

            for (int i = 1; i < vertices.size() - 1; i++) {
               triangle(pose, c, vertices.get(0), vertices.get(i), vertices.get(i + 1), face.color(), 155, 15728880, false, true);
            }
         }
      }

      if (!conflict && !(mc.screen instanceof RoadDeleteScreen) && !ClientRoads.preview.settings().laneRamp()) {
         for (RoadGeometry.V control : ClientRoads.preview.controls()) {
            RoadGeometry.V point = control.add(new RoadGeometry.V(0.0, 0.12, 0.0));
            int color = 13466623;
            RoadStructures.Part marker = new RoadStructures.Part(point, point, 0.45, 0.45, true);

            for (RoadSurface.Face face : marker.faces()) {
               List<RoadGeometry.V> vertices = face.points();
               triangle(pose, c, vertices.get(0), vertices.get(1), vertices.get(2), color, 230, 15728880, false, true);
               triangle(pose, c, vertices.get(0), vertices.get(2), vertices.get(3), color, 230, 15728880, false, true);
            }
         }
      }
   }

   @SubscribeEvent
   public static void debug(DebugText event) {
      if (Minecraft.getInstance().level != null && (Boolean)RoadClientConfig.PERF_OVERLAY.get()) {
         event.getLeft().add(SmartSignClient.metrics());
         event.getLeft().add(uploadMetrics());
         event.getLeft().add(ClientRoads.cacheMetrics());
         event.getLeft()
            .add(String.format(Locale.ROOT, "SR: %d regions (%d far), %d draws, %,d vertices", visible.size(), lastDistant, lastDraws, lastVertices));
         event.getLeft()
            .add(String.format(Locale.ROOT, "SR CPU: %.2f ms, %d uploads, %d road meshes queued", averageCpuMs, lastUploads, dirty.size() + jobs.size()));
         event.getLeft().add(RoadGpuTimer.metrics());
         event.getLeft().add("SR surface: " + RoadTerrainModels.status());
         event.getLeft().add("SR inactive VBO cache: " + retainedBytes / 1048576L + " MiB estimated");
      }
   }

   private static void draw(List<RoadRenderer.GpuSection> visible, RenderType type, RoadSurface.Texture texture, RenderLevelStageEvent event) {
      drawLayer(visible, type, texture, event, false);
   }

   private static void drawLayer(
      List<RoadRenderer.GpuSection> visible, RenderType type, RoadSurface.Texture texture, RenderLevelStageEvent event, boolean paint
   ) {
      drawLayer(visible, type, texture, event.getPoseStack(), event.getProjectionMatrix(), paint);
   }

   private static void drawLayer(
      List<RoadRenderer.GpuSection> visible, RenderType type, RoadSurface.Texture texture, PoseStack pose, Matrix4f projection, boolean paint
   ) {
      if (!visible.isEmpty()) {
         boolean present = false;

         for (RoadRenderer.GpuSection s : visible) {
            List<VertexBuffer> list = paint ? s.paint : (texture == null ? s.pavement : (s.distant ? s.far : s.near).get(texture));
            if (drawable(s, list)) {
               present = true;
               break;
            }
         }

         if (present) {
            type.setupRenderState();
            GL11.glColorMask(true, true, true, true);
            ShaderInstance shader = RenderSystem.getShader();
            boolean applied = false;

            for (RoadRenderer.GpuSection sx : visible) {
               List<VertexBuffer> buffers = paint ? sx.paint : (texture == null ? sx.pavement : (sx.distant ? sx.far : sx.near).get(texture));
               if (drawable(sx, buffers)) {
                  if (!applied) {
                     setupShader(shader, pose, projection);

                     try {
                        RenderSystem.setupShaderLights(shader);
                     } catch (Throwable var14) {
                     }

                     applied = true;
                     currentLayerKey = type + "/" + (paint ? "paint" : texture);
                  }

                  if (shader.MODEL_VIEW_MATRIX != null) {
                     shader.MODEL_VIEW_MATRIX.set(sx.transform);
                     shader.MODEL_VIEW_MATRIX.upload();
                     lastMatrixUploads++;
                  }

                  for (VertexBuffer buffer : buffers) {
                     drawOwned(sx, buffer);
                  }
               }
            }

            if (applied) {
               shader.clear();
            }

            VertexBuffer.unbind();
            type.clearRenderState();
         }
      }
   }

   private static boolean drawable(RoadRenderer.GpuSection section, List<VertexBuffer> buffers) {
      if (buffers == null) {
         return false;
      } else {
         for (VertexBuffer buffer : buffers) {
            if (drawSelection(section, buffer).indices != 0L) {
               return true;
            }
         }

         return false;
      }
   }

   private static RoadDrawSelection drawSelection(RoadRenderer.GpuSection section, VertexBuffer buffer) {
      long activity = ClientRoads.visibilityRevision();
      long terrain = RoadTerrainHandoff.ownershipRevision();
      boolean changed = section.drawRevision != section.revision;
      if (!changed && section.drawActivityRevision != activity) {
         for (Entry<UUID, Boolean> entry : section.drawActiveOwners.entrySet()) {
            if (entry.getValue() != ClientRoads.active(entry.getKey())) {
               changed = true;
               break;
            }
         }
      }

      if (!changed && section.drawTerrainRevision != terrain) {
         for (Entry<RoadTerrainMesh.Section, Boolean> entryx : section.terrainActive.entrySet()) {
            drawOwnershipQueries++;
            if (entryx.getValue() != RoadTerrainModels.active(entryx.getKey())) {
               changed = true;
               break;
            }
         }
      }

      if (changed) {
         section.clearDrawSelection();
         section.drawRevision = section.revision;
      }

      section.drawActivityRevision = activity;
      section.drawTerrainRevision = terrain;
      RoadDrawSelection saved = section.drawSelections.get(buffer);
      if (saved != null) {
         return saved;
      } else {
         List<RoadRenderer.OwnerRange> ranges = section.owners.get(buffer);
         if (ranges != null && !ranges.isEmpty()) {
            RoadDrawSelection.Builder selection = new RoadDrawSelection.Builder();
            drawSelectionBuilds++;

            for (RoadRenderer.OwnerRange range : ranges) {
               drawSelectionRanges++;
               boolean live = section.retained.contains(range.owner)
                  || section.pieces.containsKey(range.owner) && section.drawActiveOwners.computeIfAbsent(range.owner, ClientRoads::active);
               if (live && range.terrain != null) {
                  boolean active = RoadTerrainModels.active(range.terrain);
                  section.terrainActive.put(range.terrain, active);
                  if (active) {
                     live = false;
                  }
               }

               selection.add(range.firstIndex, range.indexCount, live, range.terrain != null);
            }

            saved = selection.build();
            section.drawSelections.put(buffer, saved);
            return saved;
         } else {
            return RoadDrawSelection.EMPTY;
         }
      }
   }

   static void drawPrepared(List<?> sections, PoseStack pose, Matrix4f projection, boolean surface) {
      List<RoadRenderer.GpuSection> list = (List<RoadRenderer.GpuSection>)sections;
      if (surface) {
         prepareAsphaltTexture();
         drawLayer((List<RoadRenderer.GpuSection>)sections, SURFACE, null, pose, projection, false);
         drawLayer((List<RoadRenderer.GpuSection>)sections, MARKING, null, pose, projection, true);
      }

      drawLayer((List<RoadRenderer.GpuSection>)sections, MARKING, RoadSurface.Texture.PLAIN, pose, projection, false);

      for (Entry<RoadSurface.Texture, RenderType> e : MATERIALS.entrySet()) {
         if (e.getKey() != RoadSurface.Texture.NOISE_BARRIER_GLASS) {
            drawLayer(list, e.getValue(), e.getKey(), pose, projection, false);
         }
      }
   }

   private static void drawOwned(RoadRenderer.GpuSection section, VertexBuffer buffer) {
      RoadDrawSelection selection = drawSelection(section, buffer);
      if (selection.indices != 0L) {
         lastVertices = lastVertices + selection.indices * 2L / 3L;
         lastSurfaceIndices = lastSurfaceIndices + selection.surfaceIndices;
         buffer.bind();
         int before = RenderSystem.getShaderTexture(0);

         for (int i = 0; i < 12; i++) {
            RenderSystem.setShaderTexture(i, setupShaderTextures[i]);
         }

         GL11.glColorMask(true, true, true, true);
         GL11.glDepthMask(true);
         if (currentLayerKey == null || !currentLayerKey.contains("markings") && !currentLayerKey.endsWith("/paint")) {
            GL11.glPolygonOffset(-1.0F, -8.0F);
         } else {
            GL11.glPolygonOffset(-2.0F, -16.0F);
         }

         GL11.glEnable(32823);
         IndexType indexType = RenderSystem.getSequentialBuffer(Mode.QUADS).type();
         int indexBytes = indexType == IndexType.SHORT ? 2 : 4;
         if (selection.all) {
            GL11.glDrawElements(4, (int)selection.indices, indexType.asGLType, 0L);
            lastDraws++;
         } else {
            for (int i = 0; i < selection.spans.length; i += 2) {
               GL11.glDrawElements(4, selection.spans[i + 1], indexType.asGLType, (long)selection.spans[i] * (long)indexBytes);
               lastDraws++;
            }
         }
      }
   }

   private static boolean drawOwner(RoadRenderer.GpuSection section, RoadRenderer.OwnerRange range) {
      return (section.retained.contains(range.owner) || section.pieces.containsKey(range.owner) && ClientRoads.active(range.owner))
         && (range.terrain == null || !RoadTerrainHandoff.owns(range.terrain, range.owner));
   }

   private static void setupShader(ShaderInstance shader, RenderLevelStageEvent event) {
      setupShader(shader, event.getPoseStack(), event.getProjectionMatrix());
   }

   static void setupShader(ShaderInstance shader, PoseStack pose, Matrix4f projection) {
      for (int i = 0; i < 12; i++) {
         shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
      }

      if (shader.MODEL_VIEW_MATRIX != null) {
         shader.MODEL_VIEW_MATRIX.set(RenderSystem.getModelViewMatrix());
      }

      if (shader.PROJECTION_MATRIX != null) {
         shader.PROJECTION_MATRIX.set(projection);
      }

      if (shader.COLOR_MODULATOR != null) {
         shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
      }

      if (shader.GLINT_ALPHA != null) {
         shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
      }

      if (shader.FOG_START != null) {
         shader.FOG_START.set(RenderSystem.getShaderFogStart());
      }

      if (shader.FOG_END != null) {
         shader.FOG_END.set(RenderSystem.getShaderFogEnd());
      }

      if (shader.FOG_COLOR != null) {
         shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
      }

      if (shader.FOG_SHAPE != null) {
         shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
      }

      if (shader.TEXTURE_MATRIX != null) {
         shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
      }

      if (shader.GAME_TIME != null) {
         shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
      }

      if (shader.SCREEN_SIZE != null) {
         Window window = Minecraft.getInstance().getWindow();
         shader.SCREEN_SIZE.set((float)window.getWidth(), (float)window.getHeight());
      }

      RenderSystem.setupShaderLights(shader);

      for (int i = 0; i < 12; i++) {
         setupShaderTextures[i] = RenderSystem.getShaderTexture(i);
      }

      shader.apply();
   }

   private static AABB bounds(RoadRenderMesh.Piece piece) {
      double x0 = Double.POSITIVE_INFINITY;
      double y0 = x0;
      double z0 = x0;
      double x1 = -x0;
      double y1 = -x0;
      double z1 = -x0;

      for (List<RoadSurface.Face> list : List.of(piece.pavement(), piece.detail(), piece.distant())) {
         for (RoadSurface.Face face : list) {
            for (RoadGeometry.V p : face.points()) {
               x0 = Math.min(x0, p.x());
               y0 = Math.min(y0, p.y());
               z0 = Math.min(z0, p.z());
               x1 = Math.max(x1, p.x());
               y1 = Math.max(y1, p.y());
               z1 = Math.max(z1, p.z());
            }
         }
      }

      return new AABB(x0, y0, z0, x1, y1, z1).inflate(0.1);
   }

   private static int uploadVisible() {
      int uploads = 0;
      pollVisibleLight();
      if (pendingUpload == null) {
         RoadRenderer.GpuSection candidate = null;
         double closest = Double.POSITIVE_INFINITY;

         for (RoadRenderer.GpuSection s : visible) {
            if (s.dirty && handoffReady(s) && regionUsable(s.key)) {
               double d = s.bounds.distanceToSqr(new Vec3(cameraPosition.x(), cameraPosition.y(), cameraPosition.z()));
               if (d < closest) {
                  closest = d;
                  candidate = s;
               }
            }
         }

         if (candidate != null) {
            pendingUpload = new RoadRenderer.Upload(candidate);
         }
      }

      if (pendingUpload != null) {
         try {
            long before = packedFaces;
            uploadFrames++;
            uploads = pendingUpload.step(System.nanoTime() + (long)((Integer)RoadClientConfig.VBO_UPLOAD_MICROS.get()).intValue() * 1000L);
            if (before == packedFaces && uploads == 0 && !pendingUpload.complete) {
               uploadZeroFrames++;
            }

            if (pendingUpload.complete) {
               pendingUpload = null;
            }
         } catch (RuntimeException var8) {
            pendingUpload.cancel();
            pendingUpload = null;
            ClientRoads.error = "道路网格上传失败：" + var8.getMessage();
         }
      }

      return uploads;
   }

   static String uploadMetrics() {
      RoadRenderer.Upload p = pendingUpload;
      long lightBytes = 0L;

      for (Map<RoadRenderMesh.Section, RoadRenderer.GpuSection> map : List.of(regions, dormant)) {
         for (RoadRenderer.GpuSection section : map.values()) {
            lightBytes += section.lightReads.bytes();
         }
      }

      return "upload frames="
         + uploadFrames
         + " zero="
         + uploadZeroFrames
         + " cancel="
         + uploadCancels
         + " packed="
         + packedFaces
         + " lightChecks="
         + lightReadChecks
         + " lightPositions="
         + lightReadPositions
         + " lightUnchanged="
         + lightReadUnchanged
         + " lightHashes="
         + lightHashes
         + " lightBytes="
         + lightBytes
         + " candidateChecks="
         + buildCandidateChecks
         + " "
         + RoadUploadBudget.metrics()
         + (p == null ? " idle" : " pendingMs=" + (System.nanoTime() - p.started) / 1000000L + " batch=" + p.batchIndex + " face=" + p.faceIndex);
   }

   static void previewCandidates(Collection<RoadRecord> candidates) {
      IdentityHashMap<RoadGeometry.Mesh, RoadRecord> next = new IdentityHashMap<>();

      for (RoadRecord record : candidates) {
         next.put(record.mesh(), record);
      }

      boolean same = next.size() == previewRecords.size();
      if (same) {
         for (Entry<RoadGeometry.Mesh, RoadRecord> entry : next.entrySet()) {
            if (!Objects.equals(previewRecords.get(entry.getKey()), entry.getValue())) {
               same = false;
               break;
            }
         }
      }

      if (!same) {
         previewRecords.clear();
         previewRecords.putAll(next);
         previewContextRevision++;
      }
   }

   private static boolean savedPreview(RoadGeometry.Mesh mesh) {
      RoadRecord candidate = previewRecords.get(mesh);
      RoadIndex.Built built = candidate == null ? null : ClientRoads.INDEX.roads.get(candidate.id());
      return built != null && built.record == candidate && built.mesh == mesh;
   }

   static void previewFurnitureStatus(RoadGeometry.Mesh mesh) {
      if (mesh == furniturePreview && furnitureRevision == ClientRoads.INDEX.revision() && furnitureContextRevision == previewContextRevision) {
         if (Minecraft.getInstance().screen instanceof RoadScreen screen) {
            screen.furnitureStatus(furnitureFailure);
         }
      }
   }

   private static void clearPreviewFurniture() {
      furniturePreview = null;
      furnitureGround = null;
      furnitureContextRevision = Long.MIN_VALUE;
      furnitureRevision = Long.MIN_VALUE;
      furnitureFaces = List.of();
      furnitureFailure = "";
   }

   private static List<RoadSurface.Face> previewFurniture(RoadGeometry.Mesh mesh) {
      ClientLevel level = Minecraft.getInstance().level;
      long revision = ClientRoads.INDEX.revision();
      boolean same = furniturePreview == mesh
         && furnitureRevision == revision
         && furnitureContextRevision == previewContextRevision
         && furnitureGround != null
         && furnitureGround.sameLevel(level);
      if (same && furnitureGround.current(level)) {
         previewFurnitureStatus(mesh);
         return furnitureFaces;
      } else {
         RoadPreviewGround.Sources retained = same ? furnitureGround : null;
         furniturePreview = mesh;
         furnitureRevision = revision;
         furnitureContextRevision = previewContextRevision;
         furnitureGround = null;
         furnitureFailure = "";
         if (!RoadProfile.modern(mesh.settings().style())) {
            return furnitureFaces = List.of();
         } else {
            UUID edited = ClientRoads.draft != null && ClientRoads.draft.hasUUID("Id") ? ClientRoads.draft.getUUID("Id") : null;
            if (edited == null && ClientRoads.draft != null && ClientRoads.draft.getString("Kind").equals("node")) {
               ListTag attached = ClientRoads.draft.getList("Attached", 10);
               int selected = -1;

               for (int i = 0; i < ClientRoads.nodePreviews.size(); i++) {
                  if (ClientRoads.nodePreviews.get(i) == mesh) {
                     selected = i;
                     break;
                  }
               }

               if (selected >= 0 && selected < attached.size()) {
                  CompoundTag road = attached.getCompound(selected).getCompound("Road");
                  if (road.hasUUID("Id")) {
                     edited = road.getUUID("Id");
                  }
               }
            }

            RoadRecord candidate = previewRecords.get(mesh);
            if (candidate != null) {
               edited = candidate.id();
            }

            List<RoadStructures.Part> fixed = candidate != null && candidate.fragment() != null ? candidate.fragment().supports() : null;
            RoadGeometry.Mesh reference = candidate == null ? null : candidate.terrainClassificationMesh();
            RoadPreviewGround ground = RoadPreviewGround.create(level, mesh, edited, ClientRoads.INDEX.roads.values(), retained, fixed, reference);

            List draft;
            try {
               RoadFurniture.Phase phase = RoadFurniture.Phase.DEFAULT;
               CompoundTag draftx = ClientRoads.draft;
               if (candidate != null) {
                  RoadRecord resolved = candidate.furniturePhase(FurnitureSpacing.resolve(candidate, mesh, ClientRoads.INDEX.roads.values()));
                  phase = FurnitureSpacing.onMesh(resolved, mesh);
               } else if (draftx != null && draftx.contains("A") && draftx.contains("B")) {
                  RoadGeometry.Node start = new RoadGeometry.Node(mesh.first().center(), RoadPlanner.yaw(mesh.first().left().left().mul(-1.0)), 0.0);
                  RoadGeometry.Node end = new RoadGeometry.Node(mesh.last().center(), RoadPlanner.yaw(mesh.last().left().left().mul(-1.0)), 0.0);
                  if (draftx.contains("StartNode")) {
                     start = RoadRecord.readNode(draftx.getCompound("StartNode"));
                  }

                  if (draftx.contains("EndNode")) {
                     end = RoadRecord.readNode(draftx.getCompound("EndNode"));
                  }

                  RoadRecord record = new RoadRecord(
                     draftx.hasUUID("Id") ? draftx.getUUID("Id") : new UUID(0L, 0L),
                     new UUID(0L, 0L),
                     BlockPos.of(draftx.getLong("A")),
                     BlockPos.of(draftx.getLong("B")),
                     start,
                     end,
                     mesh.settings()
                  );
                  record = record.furniturePhase(FurnitureSpacing.resolve(record, mesh, ClientRoads.INDEX.roads.values()));
                  phase = FurnitureSpacing.onMesh(record, mesh);
               }

               List<RoadStructures.Part> parts = RoadStructures.plan(mesh, ground, phase);
               List<RoadSurface.Face> faces = new ArrayList<>();

               for (RoadStructures.Part part : RoadSignVisibility.parts(mesh, parts)) {
                  faces.addAll(part.faces());
               }

               return furnitureFaces = List.copyOf(faces);
            } catch (IllegalArgumentException var20) {
               furnitureFaces = List.of();
               furnitureFailure = ground.incomplete() ? "正在等待桥墩基础附近地形加载…" : "设施预览无效：" + var20.getMessage();
               draft = furnitureFaces;
            } finally {
               furnitureGround = ground.sources();
               previewFurnitureStatus(mesh);
            }

            return draft;
         }
      }
   }

   private static void emitPacked(
      RoadSurface.Face face, boolean detail, RoadRenderer.GpuSection section, Map<BlockPos, Integer> lights, BufferBuilder builder, PoseStack pose
   ) {
      if (face.points().size() >= 3) {
         int[] vertexLights = new int[face.points().size()];

         for (int i = 0; i < vertexLights.length; i++) {
            vertexLights[i] = vertexLight(face, face.points().get(i), section, lights, false);
         }

         emitLitFace(face, detail, vertexLights, builder, pose);
      }
   }

   static void emitLitFace(RoadSurface.Face face, boolean detail, int[] lights, BufferBuilder builder, PoseStack pose) {
      List<RoadGeometry.V> p = face.points();
      if (p.size() >= 3) {
         if (!face.uv().isEmpty()) {
            texturedFace(pose, builder, face, lights);
         } else if (p.size() == 4 && face.texture() != RoadSurface.Texture.PLAIN) {
            furnitureFace(pose, builder, face, lights);
         } else {
            RoadGeometry.V n = RoadLighting.normal(face);
            if (p.size() == 4) {
               for (int i = 0; i < 4; i++) {
                  vertex(pose, builder, p.get(i), face.color(), 255, lights[i], detail, (float)n.x(), (float)n.y(), (float)n.z(), face.texture());
               }
            } else {
               for (int i = 1; i < p.size() - 1; i++) {
                  for (int k : new int[]{0, i, i + 1, i + 1}) {
                     vertex(pose, builder, p.get(k), face.color(), 255, lights[k], detail, (float)n.x(), (float)n.y(), (float)n.z(), face.texture());
                  }
               }
            }
         }
      }
   }

   private static void triangle(
      PoseStack pose,
      VertexConsumer consumer,
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V c,
      int color,
      int alpha,
      int light,
      boolean marking,
      boolean up
   ) {
      quad(pose, consumer, a, b, c, c, color, alpha, light, marking, up, RoadSurface.Texture.PLAIN);
   }

   private static void triangle(
      PoseStack pose,
      VertexConsumer consumer,
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V c,
      int color,
      int alpha,
      int light,
      boolean marking,
      boolean up,
      RoadSurface.Texture texture
   ) {
      quad(pose, consumer, a, b, c, c, color, alpha, light, marking, up, texture);
   }

   private static void quad(
      PoseStack pose,
      VertexConsumer consumer,
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V c,
      RoadGeometry.V d,
      int color,
      int alpha,
      int light,
      boolean marking,
      boolean up,
      RoadSurface.Texture texture
   ) {
      RoadGeometry.V ab = b.sub(a);
      RoadGeometry.V ac = c.sub(a);
      double nx = ab.y() * ac.z() - ab.z() * ac.y();
      double ny = ab.z() * ac.x() - ab.x() * ac.z();
      double nz = ab.x() * ac.y() - ab.y() * ac.x();
      double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
      if (length < 1.0E-10) {
         if (!c.equals(d)) {
            triangle(pose, consumer, a, c, d, color, alpha, light, marking, up, texture);
         }
      } else {
         double sign = up && ny < 0.0 ? -1.0 : 1.0;
         if (sign < 0.0) {
            RoadGeometry.V swap = b;
            b = d;
            d = swap;
         }

         float x = (float)(nx * sign / length);
         float y = (float)(ny * sign / length);
         float z = (float)(nz * sign / length);
         vertex(pose, consumer, a, color, alpha, light, marking, x, y, z, texture);
         vertex(pose, consumer, b, color, alpha, light, marking, x, y, z, texture);
         vertex(pose, consumer, c, color, alpha, light, marking, x, y, z, texture);
         vertex(pose, consumer, d, color, alpha, light, marking, x, y, z, texture);
      }
   }

   private static int vertexLight(
      RoadSurface.Face face, RoadGeometry.V p, final RoadRenderer.GpuSection section, final Map<BlockPos, Integer> cache, boolean up
   ) {
      final ClientLevel level = Minecraft.getInstance().level;
      return RoadLighting.sample(face, p, new RoadLighting.Access() {
         @Override
         public boolean opaque(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (RoadRenderer.LIGHT_DIAGNOSTICS) {
               section.lighting.computeIfAbsent(SectionPos.of(pos), RoadRenderer::lightPair);
            }

            boolean available = RoadRenderer.sampleChunkLoaded(level, pos);
            boolean opaque = available && level.getBlockState(pos).getLightBlock(level, pos) >= 15;
            return section.lightBuilder.opaque(pos.asLong(), opaque, available);
         }

         @Override
         public int packed(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (RoadRenderer.LIGHT_DIAGNOSTICS) {
               section.lighting.computeIfAbsent(SectionPos.of(pos), RoadRenderer::lightPair);
            }

            return cache.computeIfAbsent(pos, k -> {
               boolean available = RoadRenderer.sampleChunkLoaded(level, k) && level.getLightEngine().lightOnInSection(SectionPos.of(k));
               int packed = LevelRenderer.getLightColor(level, k);
               if (!available) {
                  // Roads in the DH band may be uploaded before their chunk (and light)
                  // arrives. Full sky light keeps them visible instead of black; the
                  // normal light polling re-uploads them when the chunk loads.
                  packed = 0x00F000F0;
               }

               return section.lightBuilder.packed(k.asLong(), packed, available);
            });
         }
      }, up);
   }

   private static RoadGeometry.V normal(RoadSurface.Face face) {
      List<RoadGeometry.V> p = face.points();
      RoadGeometry.V a = p.get(1).sub(p.get(0));
      RoadGeometry.V b = p.get(2).sub(p.get(0));
      RoadGeometry.V n = new RoadGeometry.V(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
      double length = Math.sqrt(n.dot(n));
      return length < 1.0E-10 ? new RoadGeometry.V(0.0, 1.0, 0.0) : n.mul(1.0 / length);
   }

   private static void furnitureFace(PoseStack pose, VertexConsumer builder, RoadSurface.Face face, int[] lights) {
      RoadGeometry.V n = normal(face);

      for (int i = 0; i < 4; i++) {
         vertex(pose, builder, face.points().get(i), face.color(), 255, lights[i], true, (float)n.x(), (float)n.y(), (float)n.z(), face.texture());
      }
   }

   private static void texturedFace(PoseStack pose, VertexConsumer builder, RoadSurface.Face face, int[] lights) {
      RoadGeometry.V n = normal(face);
      int count = face.points().size();
      if (count == 4) {
         for (int i = 0; i < 4; i++) {
            texturedVertex(pose, builder, face, lights, n, i);
         }
      } else {
         for (int i = 1; i < count - 1; i++) {
            for (int j : new int[]{0, i, i + 1, i + 1}) {
               texturedVertex(pose, builder, face, lights, n, j);
            }
         }
      }
   }

   private static void texturedVertex(PoseStack pose, VertexConsumer builder, RoadSurface.Face face, int[] lights, RoadGeometry.V n, int i) {
      RoadGeometry.V p = face.points().get(i);
      RoadSurface.UV uv = face.uv().get(i);
      builder.addVertex(pose.last().pose(), (float)(p.x() - cameraPosition.x()), (float)(p.y() - cameraPosition.y()), (float)(p.z() - cameraPosition.z()))
         .setColor(face.color() >> 16 & 0xFF, face.color() >> 8 & 0xFF, face.color() & 0xFF, 255)
         .setUv((float)uv.u(), (float)uv.v())
         .setOverlay(OverlayTexture.NO_OVERLAY)
         .setUv2(lights[i] & 65535, lights[i] >> 16)
         .setNormal(pose.last(), (float)n.x(), (float)n.y(), (float)n.z());
   }

   private static void vertex(
      PoseStack pose,
      VertexConsumer c,
      RoadGeometry.V p,
      int color,
      int alpha,
      int light,
      boolean marking,
      float nx,
      float ny,
      float nz,
      RoadSurface.Texture texture
   ) {
      float u;
      float v;
      if (texture != RoadSurface.Texture.PLAIN) {
         double px = p.x() - Math.floor(cameraPosition.x());
         double py = p.y() - Math.floor(cameraPosition.y());
         double pz = p.z() - Math.floor(cameraPosition.z());
         if (Math.abs(ny) >= Math.max(Math.abs(nx), Math.abs(nz))) {
            u = (float)px;
            v = (float)pz;
         } else if (Math.abs(nx) > Math.abs(nz)) {
            u = (float)pz;
            v = (float)py;
         } else {
            u = (float)px;
            v = (float)py;
         }
      } else {
         u = (float)(p.x() / 4.0 - Math.floor(cameraPosition.x() / 4.0));
         v = (float)(p.z() / 4.0 - Math.floor(cameraPosition.z() / 4.0));
      }

      c.addVertex(pose.last().pose(), (float)(p.x() - cameraPosition.x()), (float)(p.y() - cameraPosition.y()), (float)(p.z() - cameraPosition.z()))
         .setColor(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, alpha)
         .setUv(u, v)
         .setOverlay(OverlayTexture.NO_OVERLAY)
         .setUv2(light & 65535, light >> 16)
         .setNormal(pose.last(), nx, ny, nz);
   }

   private RoadRenderer() {
   }

   static {
      for (String name : List.of("oak_log", "birch_log", "spruce_log", "cherry_log", "oak_leaves", "birch_leaves", "spruce_leaves", "cherry_leaves")) {
         MATERIALS.put(
            RoadSurface.Texture.valueOf(name.toUpperCase(Locale.ROOT)),
            RoadRenderTypes.surface(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/" + name + ".png"), true)
         );
      }

      MATERIALS.put(
         RoadSurface.Texture.FLOWERS,
         RoadRenderTypes.surface(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/flowering_azalea_top.png"), true)
      );
      MATERIALS.put(
         RoadSurface.Texture.NOISE_BARRIER_GLASS, RoadRenderTypes.glass(ResourceLocation.fromNamespaceAndPath("splineroads", "textures/road/sr_glass.png"))
      );
      MATERIALS.put(
         RoadSurface.Texture.NOISE_BARRIER, RoadRenderTypes.surface(ResourceLocation.fromNamespaceAndPath("splineroads", "textures/road/sr_white.png"), true)
      );

      for (RoadSidewalks.Finish finish : RoadSidewalks.Finish.values()) {
         MATERIALS.put(
            RoadSurface.Texture.valueOf("WALK_" + finish.name()),
            RoadRenderTypes.solid(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/" + finish.id.substring(10) + ".png"))
         );
      }

      MATERIALS.put(RoadSurface.Texture.WALK_TACTILE, MARKING);
      WORKER = Executors.newSingleThreadExecutor(r -> {
         Thread t = new Thread(r, "SplineRoads mesh builder");
         t.setDaemon(true);
         t.setPriority(4);
         return t;
      });
      versions = new ConcurrentHashMap<>();
      dirty = new LinkedHashSet<>();
      priorityDirty = new LinkedHashSet<>();
      buildScope = new RoadBuildScope();
      meshVersions = new HashMap<>();
      terrainVersions = new HashMap<>();
      jobs = new HashMap<>();
      roadSections = new HashMap<>();
      regions = new LinkedHashMap<>();
      dormant = new LinkedHashMap<>();
      lastBudget = -1L;
      unloadedGpu = new RoadGpuRetention<>();
      residencyDirty = new LinkedHashSet<>();
      allocatedRoadBytes = new HashMap<>();
      workerRetentionRevision = new AtomicLong();
      cameraPosition = new RoadGeometry.V(0.0, 0.0, 0.0);
      visible = new ArrayList<>();
      glassVisible = new ArrayList<>();
      uploadArena = new RoadUploadArena();
      previewArena = new RoadUploadArena();
      pieceSerial = new AtomicLong();
      signalBatches = new HashMap<>();
      signalTypesUsed = new LinkedHashSet<>();
      preparations = new HashMap<>();
      atomicUpdates = new HashMap<>();
      displayUpdates = new HashMap<>();
      LIGHT_DIAGNOSTICS = Boolean.getBoolean("sr.lightDiagnostics");
      lightSnapshots = new HashMap<>();
      lightSnapshotTick = Long.MIN_VALUE;
      currentLayerKey = "?";
      setupShaderTextures = new int[12];
      previewRecords = new IdentityHashMap<>();
      furnitureContextRevision = Long.MIN_VALUE;
      furnitureRevision = Long.MIN_VALUE;
      furnitureFaces = List.of();
      furnitureFailure = "";
   }

   private static final class AtomicUpdate {
      final Set<UUID> members;
      final Set<UUID> all;
      final Map<UUID, RoadTerrainSourceWindows.Publication> terrain = new HashMap<>();
      final Set<RoadRenderer.GpuSection> pending = Collections.newSetFromMap(new IdentityHashMap<>());
      boolean installed;

      AtomicUpdate(Set<UUID> ids, Set<UUID> all) {
         this.members = Set.copyOf(ids);
         this.all = Set.copyOf(all);
      }
   }

   private static final class GpuSection {
      final RoadRenderMesh.Section key;
      final Map<UUID, RoadRenderer.Prepared> pieces = new HashMap<>();
      final List<VertexBuffer> pavement = new ArrayList<>();
      final List<VertexBuffer> paint = new ArrayList<>();
      final Map<RoadSurface.Texture, List<VertexBuffer>> near = new EnumMap<>(RoadSurface.Texture.class);
      final Map<RoadSurface.Texture, List<VertexBuffer>> far = new EnumMap<>(RoadSurface.Texture.class);
      final Map<VertexBuffer, List<RoadRenderer.OwnerRange>> owners = new IdentityHashMap<>();
      final Map<UUID, Long> allocationBytes = new HashMap<>();
      final Map<UUID, Long> uploadedSources = new HashMap<>();
      final Map<VertexBuffer, RoadDrawSelection> drawSelections = new IdentityHashMap<>();
      final Map<RoadTerrainMesh.Section, Set<UUID>> terrainOwners = new HashMap<>();
      final Map<RoadTerrainMesh.Section, Boolean> terrainActive = new HashMap<>();
      final Map<UUID, Boolean> drawActiveOwners = new HashMap<>();
      long drawRevision = Long.MIN_VALUE;
      long drawActivityRevision = Long.MIN_VALUE;
      long drawTerrainRevision = Long.MIN_VALUE;
      final Set<UUID> retained = new HashSet<>();
      final Set<RoadRenderer.AtomicUpdate> handoffs = Collections.newSetFromMap(new IdentityHashMap<>());
      boolean distant;
      long nearVertices;
      long farVertices;
      long surfaceVertices;
      long lastUsed;
      final Matrix4f transform = new Matrix4f();
      AABB bounds;
      AABB uploadedBounds;
      boolean dirty = true;
      long revision;
      long checkedLightAt;
      final Map<SectionPos, RoadLightSnapshot> lighting = new HashMap<>();
      RoadLightReadSet lightReads = RoadLightReadSet.EMPTY;
      RoadLightReadSet.Cursor lightCheck;
      RoadLightReadSet.Builder lightBuilder;

      GpuSection(RoadRenderMesh.Section key) {
         this.key = key;
      }

      void clearDrawSelection() {
         this.drawSelections.clear();
         this.terrainOwners.clear();
         this.terrainActive.clear();
         this.drawActiveOwners.clear();
         this.drawRevision = Long.MIN_VALUE;
      }

      void accountUpload() {
         if (!this.allocationBytes.isEmpty()) {
            throw new IllegalStateException("VBO ownership already accounted");
         } else {
            for (List<RoadRenderer.OwnerRange> ranges : this.owners.values()) {
               for (RoadRenderer.OwnerRange range : ranges) {
                  this.allocationBytes.merge(range.owner, (long)range.indexCount * 4L / 6L * 64L, Long::sum);
               }
            }

            if (!this.allocationBytes.isEmpty()) {
               long share = (this.lightReads.bytes() + (long)this.allocationBytes.size() - 1L) / (long)this.allocationBytes.size();
               this.allocationBytes.replaceAll((id, bytes) -> bytes + share);
               this.allocationBytes.forEach((id, bytes) -> RoadRenderer.allocatedRoadBytes.merge(id, bytes, Long::sum));
            }
         }
      }

      void close() {
         RoadRenderer.retentionRevision++;
         this.allocationBytes
            .forEach((id, bytes) -> RoadRenderer.allocatedRoadBytes.computeIfPresent(id, (key, total) -> total == bytes ? null : total - bytes));
         this.allocationBytes.clear();
         RoadRenderer.unloadedGpu.remove(this);
         this.pavement.forEach(VertexBuffer::close);
         this.pavement.clear();
         this.paint.forEach(VertexBuffer::close);
         this.paint.clear();
         this.near.values().forEach(list -> list.forEach(VertexBuffer::close));
         this.far.values().forEach(list -> list.forEach(VertexBuffer::close));
         this.near.clear();
         this.far.clear();
         this.owners.clear();
         this.uploadedSources.clear();
         this.lighting.clear();
         this.lightReads = RoadLightReadSet.EMPTY;
         this.lightCheck = null;
         this.lightBuilder = null;
         this.clearDrawSelection();
         this.uploadedBounds = null;
      }
   }

   private static record Job(
      int version,
      long epoch,
      CompletableFuture<RoadRenderer.MeshResult> future,
      RoadRenderer.Preparation preparation,
      RoadMeshSurfaces.Window window,
      AtomicBoolean cancelled
   ) {
      void cancel() {
         this.cancelled.set(true);
         this.future.cancel(false);
      }
   }

   private static record MeshResult(Map<RoadRenderMesh.Section, RoadRenderer.Prepared> regions, RoadTerrainSourceWindows.Publication terrain) {
   }

   static record MovementStats(long rescans, long candidates, long selections, long visits, long completedSkips, long lightRefreshes) {
   }

   private static record OwnerRange(UUID owner, long source, int firstIndex, int indexCount, RoadTerrainMesh.Section terrain) {
   }

   private static final class Preparation {
      final int version;
      final List<RoadMeshSurfaces.Window> windows;
      final Set<RoadMeshSurfaces.Window> surfaces = new HashSet<>();
      final Set<RoadMeshSurfaces.Window> terrain = new HashSet<>();
      final Map<RoadRenderMesh.Section, RoadRenderer.Prepared> pieces = new HashMap<>();
      final Set<RoadRenderMesh.Section> previous;
      final RoadIndex.Built source;
      RoadMeshSurfaces.Structures structures;
      RoadMeshSurfaces.Context context;
      final Map<RoadMeshSurfaces.Window, Map<RoadRenderMesh.Section, RoadRenderer.Prepared>> windowPieces = new LinkedHashMap<>();
      final RoadTerrainSourceWindows<RoadMeshSurfaces.Window> terrainIndex = new RoadTerrainSourceWindows<>();

      Preparation(UUID id, int version, RoadIndex.Built road) {
         this.version = version;
         this.source = road;
         this.windows = RoadMeshSurfaces.windows(road);
         this.previous = Set.copyOf(RoadRenderer.roadSections.getOrDefault(id, Set.of()));
         RoadRenderer.Preparation old = RoadRenderer.preparations.get(id);
         if (old != null && old.source == road) {
            this.windowPieces.putAll(old.windowPieces);
            this.pieces.putAll(old.pieces);
            this.structures = old.structures;
         }
      }

      Map<RoadRenderMesh.Section, RoadRenderer.Prepared> replace(RoadMeshSurfaces.Window window, Map<RoadRenderMesh.Section, RoadRenderer.Prepared> result) {
         Map<RoadRenderMesh.Section, RoadRenderer.Prepared> old = this.windowPieces.put(window, result);
         Set<RoadRenderMesh.Section> affected = new HashSet<>(result.keySet());
         if (old != null) {
            affected.addAll(old.keySet());
         }

         LinkedHashMap<RoadRenderMesh.Section, RoadRenderer.Prepared> changed = new LinkedHashMap<>();

         for (RoadRenderMesh.Section key : affected) {
            RoadRenderer.Prepared combined = null;

            for (Map<RoadRenderMesh.Section, RoadRenderer.Prepared> part : this.windowPieces.values()) {
               RoadRenderer.Prepared contribution = part.get(key);
               if (contribution != null) {
                  combined = combined == null ? contribution : RoadRenderer.append(combined, contribution);
               }
            }

            if (combined == null) {
               this.pieces.remove(key);
            } else {
               this.pieces.put(key, combined);
               changed.put(key, combined);
            }
         }

         return changed;
      }

      RoadMeshSurfaces.Window next(boolean exact) {
         RoadRenderer.windowSelections++;
         if (this.surfaces.size() != this.windows.size() || exact && this.terrain.size() != this.windows.size()) {
            RoadRenderer.windowVisits = RoadRenderer.windowVisits + (long)this.windows.size();
            RoadMeshSurfaces.Window best = null;
            double nearest = Double.POSITIVE_INFINITY;

            for (RoadMeshSurfaces.Window window : this.windows) {
               if ((!this.surfaces.contains(window) || exact && !this.terrain.contains(window))
                  && (
                     RoadRenderer.atomicUpdates.containsKey(this.source.record.id())
                        || RoadVisibility.within(
                           window.min().x(),
                           window.min().z(),
                           window.max().x(),
                           window.max().z(),
                           RoadRenderer.cameraPosition.x(),
                           RoadRenderer.cameraPosition.z(),
                           viewChunks()
                        )
                  )) {
                  double distance = window.distance(RoadRenderer.cameraPosition);
                  if (distance < nearest) {
                     nearest = distance;
                     best = window;
                  }
               }
            }

            return best;
         } else {
            RoadRenderer.completedWindowSkips++;
            return null;
         }
      }
   }

   private static record Prepared(
      long identity,
      long cpuBytes,
      RoadRenderMesh.Piece piece,
      List<RoadSurface.Face> paint,
      AABB bounds,
      Map<RoadSurface.Texture, List<RoadSurface.Face>> near,
      Map<RoadSurface.Texture, List<RoadSurface.Face>> far
   ) {
      private static List<RoadSurface.Face> splitWalks(List<RoadSurface.Face> faces) {
         ArrayList<RoadSurface.Face> result = new ArrayList<>();

         for (RoadSurface.Face f : faces) {
            result.addAll(RoadTerrainMesh.walkSurface(f) ? RoadSurfaceSections.split(List.of(f)) : List.of(f));
         }

         return result;
      }

      static RoadRenderer.Prepared of(RoadRenderMesh.Layers layers) {
         RoadRenderMesh.Piece piece = new RoadRenderMesh.Piece(
            RoadUploadBudget.faces(RoadSurfaceSections.split(layers.surface().pavement())),
            RoadUploadBudget.faces(splitWalks(layers.shared().detail())),
            RoadUploadBudget.faces(splitWalks(layers.shared().distant()))
         );
         List<RoadSurface.Face> paint = RoadUploadBudget.faces(RoadSurfaceSections.split(layers.surface().detail()));
         EnumMap<RoadSurface.Texture, List<RoadSurface.Face>> near = new EnumMap<>(RoadSurface.Texture.class);
         EnumMap<RoadSurface.Texture, List<RoadSurface.Face>> far = new EnumMap<>(RoadSurface.Texture.class);

         for (RoadSurface.Face f : piece.detail()) {
            near.computeIfAbsent(f.texture(), k -> new ArrayList<>()).add(f);
         }

         for (RoadSurface.Face f : piece.distant()) {
            far.computeIfAbsent(f.texture(), k -> new ArrayList<>()).add(f);
         }

         long cpuBytes = 0L;

         for (List<RoadSurface.Face> faces : List.of(piece.pavement(), piece.detail(), piece.distant(), paint)) {
            for (RoadSurface.Face face : faces) {
               cpuBytes += 96L + (long)face.points().size() * 64L;
            }
         }

         ArrayList<RoadSurface.Face> bound = new ArrayList<>(piece.detail());
         bound.addAll(paint);
         return new RoadRenderer.Prepared(
            RoadRenderer.pieceSerial.incrementAndGet(),
            cpuBytes,
            piece,
            paint,
            RoadRenderer.bounds(new RoadRenderMesh.Piece(piece.pavement(), bound, piece.distant())),
            near,
            far
         );
      }
   }

   static record ResidencyStats(long bytes, int regions, int queued, long checks, long evictions, long cancelledUploads) {
   }

   static record SelectionStats(long builds, long ranges, long ownershipQueries) {
   }

   private static record SignalCache(List<RoadSurface.Face> faces, RoadSignalBatch batch) {
   }

   static record StreamingStats(double cpuMillis, int uploads, int meshJobs, long surfaceIndices) {
   }

   private static final class Upload {
      final RoadRenderer.GpuSection section;
      final long revision;
      final long started = System.nanoTime();
      final List<RoadRenderer.Prepared> pieces;
      final List<UUID> owners;
      final List<RoadRenderer.OwnerRange> ranges = new ArrayList<>();
      final Map<BlockPos, Integer> lights = new HashMap<>();
      final RoadRenderer.GpuSection staged;
      final PoseStack pose = new PoseStack();
      final List<RoadRenderer.Upload.Batch> batches = new ArrayList<>();
      BufferBuilder builder;
      MeshData ready;
      int batchIndex;
      int pieceIndex;
      int faceIndex;
      long vertices;
      boolean complete;

      Upload(RoadRenderer.GpuSection s) {
         this.section = s;
         this.revision = s.revision;
         this.owners = s.pieces.keySet().stream().sorted().toList();
         this.pieces = this.owners.stream().map(s.pieces::get).toList();
         this.staged = new RoadRenderer.GpuSection(s.key);
         this.staged.lightBuilder = new RoadLightReadSet.Builder(s.lightReads);

         for (RoadRenderer.Prepared piece : this.pieces) {
            this.staged.bounds = this.staged.bounds == null ? piece.bounds : this.staged.bounds.minmax(piece.bounds);
         }

         this.batches.add(new RoadRenderer.Upload.Batch(0, null));
         this.batches.add(new RoadRenderer.Upload.Batch(3, RoadSurface.Texture.PLAIN));

         for (int kind : new int[]{1, 2}) {
            for (RoadSurface.Texture texture : RoadSurface.Texture.values()) {
               boolean present = false;

               for (RoadRenderer.Prepared p : this.pieces) {
                  if ((kind == 1 ? p.near : p.far).containsKey(texture)) {
                     present = true;
                     break;
                  }
               }

               if (present) {
                  this.batches.add(new RoadRenderer.Upload.Batch(kind, texture));
               }
            }
         }
      }

      List<RoadSurface.Face> faces(RoadRenderer.Prepared p, RoadRenderer.Upload.Batch b) {
         return b.kind == 0 ? p.piece.pavement() : (b.kind == 3 ? p.paint : (b.kind == 1 ? p.near : p.far).getOrDefault(b.texture, List.of()));
      }

      int step(long deadline) {
         RoadRenderer.cameraPosition = this.section.key.origin();
         int uploads = 0;
         int packed = 0;

         while (this.batchIndex < this.batches.size()) {
            if (packed > 0 && System.nanoTime() >= deadline) {
               return uploads;
            }

            RoadRenderer.Upload.Batch batch = this.batches.get(this.batchIndex);
            if (this.builder == null && this.ready == null) {
               this.builder = RoadRenderer.uploadArena.begin();
            }

            if (this.ready == null) {
               label230:
               while (this.pieceIndex < this.pieces.size()) {
                  List<RoadSurface.Face> faces = this.faces(this.pieces.get(this.pieceIndex), batch);

                  while (this.faceIndex < faces.size()) {
                     RoadSurface.Face f = faces.get(this.faceIndex);
                     if (this.vertices > 0L && this.vertices + (long)RoadRenderMesh.vertexCount(f) > 8192L) {
                        break label230;
                     }

                     this.faceIndex++;
                     RoadRenderer.packedFaces++;
                     RoadRenderer.emitPacked(f, batch.kind != 0, this.staged, this.lights, this.builder, this.pose);
                     int first = (int)(this.vertices / 4L * 6L);
                     int count = RoadRenderMesh.vertexCount(f) / 4 * 6;
                     UUID owner = this.owners.get(this.pieceIndex);
                     long source = this.pieces.get(this.pieceIndex).identity;
                     RoadTerrainMesh.Section terrain = batch.kind != 0 && batch.kind != 3 && !RoadTerrainMesh.walkSurface(f)
                        ? null
                        : RoadSurfaceSections.owner(f);
                     if (!this.ranges.isEmpty()
                        && this.ranges.get(this.ranges.size() - 1).owner.equals(owner)
                        && Objects.equals(this.ranges.get(this.ranges.size() - 1).terrain, terrain)) {
                        RoadRenderer.OwnerRange previous = this.ranges.remove(this.ranges.size() - 1);
                        this.ranges.add(new RoadRenderer.OwnerRange(owner, source, previous.firstIndex, previous.indexCount + count, terrain));
                     } else {
                        this.ranges.add(new RoadRenderer.OwnerRange(owner, source, first, count, terrain));
                     }

                     this.vertices = this.vertices + (long)RoadRenderMesh.vertexCount(f);
                     if ((++packed & 15) == 0 && System.nanoTime() >= deadline) {
                        return uploads;
                     }
                  }

                  this.faceIndex = 0;
                  this.pieceIndex++;
               }

               this.ready = this.builder.buildOrThrow();
               this.builder = null;
            }

            VertexBuffer buffer = null;
            if (this.vertices > 0L) {
               if (!RoadUploadBudget.claimRoad(
                  this.ready.drawState().vertexCount() * this.ready.drawState().format().getVertexSize()
                     + this.ready.drawState().indexCount() * this.ready.drawState().indexType().bytes
               )) {
                  return uploads;
               }

               buffer = new VertexBuffer(Usage.STATIC);

               try {
                  buffer.bind();
                  MeshData uploading = this.ready;
                  this.ready = null;
                  buffer.upload(uploading);
               } catch (Error | RuntimeException var18) {
                  buffer.close();
                  throw var18;
               } finally {
                  VertexBuffer.unbind();
               }

               uploads++;
            } else {
               this.ready.close();
               this.ready = null;
            }

            if (buffer != null) {
               this.staged.owners.put(buffer, List.copyOf(this.ranges));
               if (batch.kind == 0) {
                  this.staged.pavement.add(buffer);
                  this.staged.nearVertices = this.staged.nearVertices + this.vertices;
                  this.staged.farVertices = this.staged.farVertices + this.vertices;
                  this.staged.surfaceVertices = this.staged.surfaceVertices + this.vertices;
               } else if (batch.kind == 3) {
                  this.staged.paint.add(buffer);
                  this.staged.nearVertices = this.staged.nearVertices + this.vertices;
                  this.staged.farVertices = this.staged.farVertices + this.vertices;
                  this.staged.surfaceVertices = this.staged.surfaceVertices + this.vertices;
               } else if (batch.kind == 1) {
                  this.staged.near.computeIfAbsent(batch.texture, k -> new ArrayList<>()).add(buffer);
                  this.staged.nearVertices = this.staged.nearVertices + this.vertices;
               } else {
                  this.staged.far.computeIfAbsent(batch.texture, k -> new ArrayList<>()).add(buffer);
                  this.staged.farVertices = this.staged.farVertices + this.vertices;
               }
            }

            this.vertices = 0L;
            this.ranges.clear();
            if (this.pieceIndex == this.pieces.size()) {
               this.pieceIndex = this.faceIndex = 0;
               this.batchIndex++;
            }

            if (uploads >= 4 && this.batchIndex < this.batches.size()) {
               return uploads;
            }
         }

         if (!RoadRenderer.handoffReady(this.section)) {
            return uploads;
         } else {
            this.section.close();
            this.section.pavement.addAll(this.staged.pavement);
            this.section.paint.addAll(this.staged.paint);
            this.section.near.putAll(this.staged.near);
            this.section.far.putAll(this.staged.far);
            this.section.owners.putAll(this.staged.owners);

            for (int i = 0; i < this.owners.size(); i++) {
               this.section.uploadedSources.put(this.owners.get(i), this.pieces.get(i).identity);
            }

            this.section.nearVertices = this.staged.nearVertices;
            this.section.farVertices = this.staged.farVertices;
            this.section.surfaceVertices = this.staged.surfaceVertices;
            this.section.lighting.clear();
            this.section.lighting.putAll(this.staged.lighting);
            this.section.lightReads = this.staged.lightBuilder.build();
            this.staged.lightBuilder = null;
            this.section.accountUpload();
            this.section.uploadedBounds = this.staged.bounds;
            RoadRenderer.updateBounds(this.section);
            this.section.dirty = this.section.revision != this.revision;
            this.section.checkedLightAt = Minecraft.getInstance().level.getGameTime() - (long)Math.floorMod(this.section.key.hashCode(), 20);
            RoadRenderer.completedHandoff(this.section);
            if (this.section.pieces.isEmpty()) {
               RoadRenderer.regions.remove(this.section.key, this.section);
               RoadRenderer.dormant.remove(this.section.key, this.section);
            } else {
               RoadRenderer.residencyDirty.add(this.section.key);
            }

            this.complete = true;
            return uploads;
         }
      }

      void cancel() {
         RoadRenderer.uploadCancels++;
         if (this.builder != null) {
            this.builder = null;
         }

         if (this.ready != null) {
            this.ready.close();
            this.ready = null;
         }

         this.builder = null;
         this.staged.close();
      }

      static record Batch(int kind, RoadSurface.Texture texture) {
      }
   }
}
