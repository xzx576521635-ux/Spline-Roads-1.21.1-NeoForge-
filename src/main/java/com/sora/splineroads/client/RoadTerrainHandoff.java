package com.sora.splineroads.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexBuffer.Usage;
import com.sora.splineroads.core.RoadHandoffState;
import com.sora.splineroads.core.RoadTerrainMesh;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.CompiledSection;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent.Pre;
import net.neoforged.neoforge.client.model.data.ModelData;

@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT}
)
public final class RoadTerrainHandoff {
   static final RoadHandoffState<Map<Integer, ModelData>> STATE = new RoadHandoffState<>(Map::isEmpty);
   private static final Map<Object, RoadTerrainHandoff.Build> BUILDS = Collections.synchronizedMap(new WeakHashMap<>());
   private static final ThreadLocal<RoadTerrainHandoff.Build> BUILD = new ThreadLocal<>();
   private static final Map<SectionRenderDispatcher, ArrayDeque<Runnable>> DEFERRED = new WeakHashMap<>();
   private static final RoadTerrainUploadBudget BUDGET = new RoadTerrainUploadBudget();
   private static final LinkedHashMap<RoadTerrainMesh.Section, Long> REFRESH = new LinkedHashMap<>();
   private static Object level;
   private static volatile boolean vanillaSeen;

   private RoadTerrainHandoff() {
   }

   @SubscribeEvent
   public static void renderFrame(Pre event) {
      beginUploadFrame();
      refreshStale();
   }

   static void beginUploadFrame() {
      BUDGET.begin();
      RoadEmbeddiumHandoff.beginUploadFrame();
   }

   private static void checkLevel() {
      ClientLevel current = Minecraft.getInstance().level;
      if (level != current) {
         level = current;
         STATE.reset();
         REFRESH.clear();
      }
   }

   static void frame(boolean terrain) {
      checkLevel();
      STATE.mode(terrain);
   }

   static void publish(RoadTerrainMesh.Section section, Map<Integer, ModelData> cells, Set<UUID> owners) {
      publish(section, cells, owners, Set.of());
   }

   static void publish(RoadTerrainMesh.Section section, Map<Integer, ModelData> cells, Set<UUID> owners, Set<UUID> replaces) {
      checkLevel();
      STATE.publish(section, cells, owners, replaces);
      if (STATE.resident(section) != null) {
         REFRESH.put(section, Long.valueOf(System.nanoTime() + 250000000L));
      }
   }

   private static void refreshStale() {
      checkLevel();
      Minecraft mc = Minecraft.getInstance();
      if (mc.level != null && !REFRESH.isEmpty()) {
         long now = System.nanoTime();
         int count = Math.min(8, REFRESH.size());

         while (count-- > 0) {
            Iterator<Entry<RoadTerrainMesh.Section, Long>> it = REFRESH.entrySet().iterator();
            Entry<RoadTerrainMesh.Section, Long> entry = it.next();
            RoadTerrainMesh.Section section = entry.getKey();
            long due = entry.getValue();
            it.remove();
            RoadHandoffState.Resident resident = STATE.resident(section);
            if (resident != null && resident.source() != STATE.source(section)) {
               if (!visible(section, resident)) {
                  STATE.invalidAllocation(section, resident);
               } else {
                  if (now >= due) {
                     Object owner = resident.owner().get();
                     boolean requested;
                     if (owner instanceof RenderSection chunk) {
                        chunk.setDirty(false);
                        requested = true;
                     } else {
                        requested = RoadEmbeddiumHandoff.refresh(owner, section);
                     }

                     if (requested) {
                        due = now + 500000000L;
                     }
                  }

                  REFRESH.put(section, Long.valueOf(due));
               }
            }
         }
      }
   }

   static void clearSources() {
      STATE.clearSources();
   }

   static void invalidateSources() {
      STATE.clearSources();
   }

   public static void release(Object owner) {
      STATE.release(owner);
   }

   public static long ownershipRevision() {
      return STATE.ownershipRevision();
   }

   static void invalidateOwnership() {
      STATE.invalidateOwnership();
   }

   public static Set<UUID> visibleOwners(RoadTerrainMesh.Section section) {
      RoadHandoffState.Resident resident = STATE.resident(section);
      if (resident == null) {
         return Set.of();
      } else if (visible(section, resident)) {
         return resident.vboOwners();
      } else {
         STATE.invalidAllocation(section, resident);
         return Set.of();
      }
   }

   public static boolean owns(RoadTerrainMesh.Section section, UUID road) {
      RoadHandoffState.Resident resident = STATE.resident(section);
      return resident != null && resident.vboOwners().contains(road) && visible(section, resident);
   }

   private static boolean visible(RoadTerrainMesh.Section section, RoadHandoffState.Resident resident) {
      Object owner = resident.owner().get();
      if (owner instanceof RenderSection chunk) {
         if (!section.equals(section(chunk.getOrigin()))) {
            return false;
         } else if (resident.upload() instanceof Map<?, ?> buffers) {
            if (chunk.getCompiled().isEmpty(RenderType.solid())) {
               return false;
            } else {
               Map<RenderType, VertexBuffer> installed = ((RoadChunkBuffers)chunk).splineroads$buffers();

               for (Entry<?, ?> entry : buffers.entrySet()) {
                  if (!(entry.getValue() instanceof VertexBuffer v)) {
                     return false;
                  }

                  if (v.isInvalid()) {
                     return false;
                  }

                  if (installed.get(entry.getKey()) != v) {
                     return false;
                  }
               }

               return true;
            }
         } else {
            return false;
         }
      } else {
         return owner != null && RoadEmbeddiumHandoff.alive(owner, section, resident.upload());
      }
   }

   static boolean currentUpload(RoadTerrainMesh.Section section, Set<UUID> members, boolean terrain) {
      RoadHandoffState.Resident resident = STATE.resident(section);
      RoadHandoffState.Source<Map<Integer, ModelData>> source = STATE.source(section);
      if (!terrain) {
         return resident == null || Collections.disjoint(resident.vboOwners(), members);
      } else if (source != null && !Collections.disjoint(source.owners(), members)) {
         if (resident != null && resident.source() == source) {
            for (UUID owner : source.owners()) {
               if (members.contains(owner) && !owns(section, owner)) {
                  return false;
               }
            }

            return true;
         } else {
            return false;
         }
      } else {
         return resident == null || Collections.disjoint(resident.vboOwners(), members) || resident.source() == source;
      }
   }

   static RoadTerrainMesh.Section section(BlockPos p) {
      return new RoadTerrainMesh.Section(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
   }

   static Map<Integer, ModelData> model(RoadTerrainMesh.Section section, int cell) {
      RoadTerrainHandoff.Build build = BUILD.get();
      if (build != null && build.ticket.section.equals(section) && build.ticket.terrain && build.ticket.source != null) {
         Map<Integer, ModelData> map = build.ticket.source.data();
         if (build.cells != null && map.containsKey(cell) && !RoadTerrainModels.coverOnly(map.get(cell))) {
            build.cells.set(cell);
         }

         return map;
      } else {
         return null;
      }
   }

   static boolean inBuild() {
      return BUILD.get() != null;
   }

   public static void beginVanilla(Object task, RenderSection chunk, AtomicBoolean cancelled, SectionRenderDispatcher dispatcher) {
      BUILD.remove();
      if (!cancelled.get()) {
         vanillaSeen = true;
         RoadTerrainHandoff.Build build = new RoadTerrainHandoff.Build(
            STATE.begin(chunk, section(chunk.getOrigin())), cancelled, ((RoadChunkUploads)dispatcher).splineroads$uploads(), null
         );
         BUILDS.put(task, build);
         BUILD.set(build);
      }
   }

   static Object beginOptional(Object task, Object owner, RoadTerrainMesh.Section section) {
      return beginOptional(task, owner, section, null);
   }

   static Object beginOptional(Object task, Object owner, RoadTerrainMesh.Section section, Object nativeJob) {
      BUILD.remove();
      RoadTerrainHandoff.Build build = new RoadTerrainHandoff.Build(STATE.begin(owner, section), new AtomicBoolean(), null, nativeJob);
      BUILDS.put(task, build);
      BUILD.set(build);
      return build;
   }

   static Object endOptional(Object task) {
      BUILD.remove();
      return BUILDS.remove(task);
   }

   static boolean validOptional(Object token) {
      if (token instanceof RoadTerrainHandoff.Build build && valid(build) && completeCells(build)) {
         return true;
      }

      return false;
   }

   static boolean terrainOptional(Object token) {
      if (token instanceof RoadTerrainHandoff.Build build && build.ticket.terrain) {
         return true;
      }

      return false;
   }

   static boolean stagedOptional(Object token) {
      if (token instanceof RoadTerrainHandoff.Build build && build.ticket.staged) {
         return true;
      }

      return false;
   }

   static boolean ownersOptional(Object token) {
      if (token instanceof RoadTerrainHandoff.Build build && !build.ticket.owners().isEmpty()) {
         return true;
      }

      return false;
   }

   static Object optionalJob(Object token) {
      if (token instanceof RoadTerrainHandoff.Build build && build.nativeJob != null) {
         return build.nativeJob.get();
      }

      return null;
   }

   static void finishOptional(Object token) {
      if (token instanceof RoadTerrainHandoff.Build build) {
         build.closed = true;
         STATE.finish(build.ticket);
      }
   }

   static void commitOptional(Object token, Object allocation) {
      if (token instanceof RoadTerrainHandoff.Build build && validOptional(build)) {
         STATE.commit(build.ticket, allocation);
      }
   }

   static RoadTerrainMesh.Section optionalSection(Object token) {
      return token instanceof RoadTerrainHandoff.Build b ? b.ticket.section : null;
   }

   public static CompletableFuture<?> endVanilla(CompletableFuture<?> future, Object task) {
      BUILD.remove();
      RoadTerrainHandoff.Build build = BUILDS.get(task);
      if (build != null) {
         future.whenComplete((result, failure) -> {
            BUILDS.remove(task);
            dispose(build);
         });
      }

      return future;
   }

   public static void abort(Object task) {
      BUILD.remove();
      RoadTerrainHandoff.Build build = BUILDS.remove(task);
      if (build != null) {
         build.cancelled.set(true);
         dispose(build);
      }
   }

   private static boolean valid(RoadTerrainHandoff.Build build) {
      if (!build.closed && !build.cancelled.get() && STATE.current(build.ticket)) {
         if (build.ticket.owner() instanceof RenderSection chunk && !build.ticket.section.equals(section(chunk.getOrigin()))) {
            return false;
         }

         return true;
      } else {
         return false;
      }
   }

   private static boolean completeCells(RoadTerrainHandoff.Build build) {
      return !build.ticket.terrain || build.ticket.source == null || (build.cells == null ? 0 : build.cells.cardinality()) == build.requiredCells;
   }

   private static void retry(RoadTerrainHandoff.Build build, String reason) {
      Object owner;
      boolean var10000;
      label16: {
         build.cancelled.set(true);
         owner = build.ticket.owner();
         if (owner instanceof RenderSection chunk && build.ticket.section.equals(section(chunk.getOrigin()))) {
            var10000 = true;
            break label16;
         }

         var10000 = false;
      }

      boolean dirty = var10000;
      if (dirty) {
         ((RenderSection)owner).setDirty(false);
      }
   }

   public static CompletableFuture<Void> uploadVanilla(
      final SectionRenderDispatcher dispatcher, final MeshData data, final VertexBuffer live, Object task, final RenderType layer
   ) {
      final RoadTerrainHandoff.Build build = BUILDS.get(task);
      if (build == null) {
         return dispatcher.uploadSectionLayer(data, live);
      } else {
         build.requested++;
         final CompletableFuture<Void> completion = new CompletableFuture<>();
         Runnable upload = new Runnable() {
            @Override
            public void run() {
               if (RoadTerrainHandoff.valid(build) && RoadTerrainHandoff.completeCells(build) && !live.isInvalid()) {
                  if (build.ticket.staged
                     && !RoadTerrainHandoff.BUDGET
                        .claim(
                           data.drawState().vertexCount() * data.drawState().format().getVertexSize()
                              + data.drawState().indexCount() * data.drawState().indexType().bytes,
                           true
                        )) {
                     RoadTerrainHandoff.DEFERRED.computeIfAbsent(dispatcher, k -> new ArrayDeque<>()).add(this);
                  } else {
                     VertexBuffer target = build.ticket.staged ? new VertexBuffer(Usage.STATIC) : live;
                     boolean uploaded = false;

                     try {
                        target.bind();
                        target.upload(data);
                        uploaded = true;
                        if (build.ticket.staged) {
                           build.staged.put(layer, target);
                        }

                        build.uploaded++;
                     } catch (Error | RuntimeException var7) {
                        RoadTerrainHandoff.retry(build, "upload-failure:" + var7);
                        completion.completeExceptionally(var7);
                        throw var7;
                     } finally {
                        VertexBuffer.unbind();
                        if (!uploaded && target != live) {
                           target.close();
                        }
                     }

                     completion.complete(null);
                  }
               } else {
                  data.close();
                  RoadTerrainHandoff.retry(
                     build, !RoadTerrainHandoff.valid(build) ? "upload-valid" : (!RoadTerrainHandoff.completeCells(build) ? "upload-cells" : "upload-invalid")
                  );
                  completion.complete(null);
               }
            }
         };
         build.uploads.add(upload);
         return completion;
      }
   }

   public static void beginUploads(SectionRenderDispatcher dispatcher) {
      ArrayDeque<Runnable> waiting = DEFERRED.remove(dispatcher);
      if (waiting != null) {
         ((RoadChunkUploads)dispatcher).splineroads$uploads().addAll(waiting);
      }
   }

   public static void discardUploads(SectionRenderDispatcher dispatcher) {
      ArrayDeque<Runnable> waiting = DEFERRED.remove(dispatcher);
      if (waiting != null) {
         Queue<Runnable> queue = ((RoadChunkUploads)dispatcher).splineroads$uploads();
         synchronized (BUILDS) {
            for (RoadTerrainHandoff.Build build : BUILDS.values()) {
               if (build.uploads == queue) {
                  build.cancelled.set(true);
                  Object owner = build.ticket.owner();
                  if (owner != null) {
                     STATE.release(owner);
                  }
               }
            }
         }

         while (!waiting.isEmpty()) {
            waiting.removeFirst().run();
         }
      }
   }

   public static CompletableFuture completeVanilla(CompletableFuture future, BiFunction handler, Object task) {
      RoadTerrainHandoff.Build build = BUILDS.get(task);
      return build != null && build.ticket.staged ? future.handleAsync(handler, action -> {
         if (RenderSystem.isOnRenderThread()) {
            action.run();
         } else {
            build.uploads.add(action);
         }
      }) : future.handle(handler);
   }

   public static void beforeCommit(Object task, Throwable failure) {
      RoadTerrainHandoff.Build build = BUILDS.get(task);
      if (build != null && (failure != null || !valid(build) || !completeCells(build) || build.requested != build.uploaded)) {
         retry(
            build,
            failure != null ? "build-failure:" + failure : (!valid(build) ? "commit-valid" : (!completeCells(build) ? "commit-cells" : "commit-uploads"))
         );
      }
   }

   public static boolean commitVanilla(AtomicReference reference, Object compiled, Object task) {
      RoadTerrainHandoff.Build build = BUILDS.get(task);
      if (build == null) {
         reference.set(compiled);
         return true;
      } else {
         if (build.ticket.staged) {
            RenderSystem.assertOnRenderThread();
         }

         synchronized (STATE) {
            if (valid(build)
               && completeCells(build)
               && build.requested == build.uploaded
               && (
                  build.ticket.owners().isEmpty()
                     || build.staged.containsKey(RenderType.solid()) && compiled instanceof CompiledSection c && !c.isEmpty(RenderType.solid())
               )) {
               RenderSection chunk = (RenderSection)build.ticket.owner();
               if (!build.ticket.staged) {
                  reference.set(compiled);
                  STATE.commit(build.ticket, Map.of());
                  build.committed = true;
                  return true;
               } else {
                  Map<RenderType, VertexBuffer> buffers = ((RoadChunkBuffers)chunk).splineroads$buffers();
                  List<VertexBuffer> previous = new ArrayList<>();
                  if (build.ticket.staged) {
                     build.staged.forEach((layer, buffer) -> {
                        VertexBuffer old = buffers.put(layer, buffer);
                        if (old != null && old != buffer) {
                           previous.add(old);
                        }
                     });
                  }

                  reference.set(compiled);
                  STATE.commit(build.ticket, Map.copyOf(build.staged));
                  build.committed = true;
                  build.staged.clear();
                  previous.forEach(VertexBuffer::close);
                  return true;
               }
            } else {
               retry(build, "commit-gate");
               return false;
            }
         }
      }
   }

   private static void dispose(RoadTerrainHandoff.Build build) {
      if (build.ticket.staged && build.uploads != null) {
         Runnable close = () -> {
            build.closed = true;
            build.staged.values().forEach(VertexBuffer::close);
            build.staged.clear();
            STATE.finish(build.ticket);
         };
         if (RenderSystem.isOnRenderThread()) {
            close.run();
         } else if (build.uploads != null) {
            build.uploads.add(close);
         }
      } else {
         build.closed = true;
         STATE.finish(build.ticket);
      }
   }

   static String status() {
      return STATE.residentCount()
         + " uploaded sections, "
         + STATE.accepted()
         + " commits / "
         + STATE.rejected()
         + " rejected"
         + (vanillaSeen ? ", vanilla " + BUDGET.bytes() + " bytes/batch" : "")
         + RoadEmbeddiumHandoff.status();
   }

   static RoadTerrainHandoff.UploadStats stats() {
      boolean optional = RoadEmbeddiumHandoff.active();
      return new RoadTerrainHandoff.UploadStats(
         optional ? RoadEmbeddiumHandoff.uploadedBytes() : BUDGET.bytes(),
         optional ? RoadEmbeddiumHandoff.uploadedCount() : BUDGET.uploads(),
         optional ? RoadEmbeddiumHandoff.pending() : DEFERRED.values().stream().mapToInt(ArrayDeque::size).sum(),
         STATE.residentCount()
      );
   }

   private static final class Build {
      final RoadHandoffState.Ticket<Map<Integer, ModelData>> ticket;
      final AtomicBoolean cancelled;
      final Queue<Runnable> uploads;
      final Map<RenderType, VertexBuffer> staged;
      final BitSet cells;
      final WeakReference<Object> nativeJob;
      volatile boolean closed;
      volatile boolean committed;
      int requested;
      int uploaded;
      final int requiredCells;

      Build(RoadHandoffState.Ticket<Map<Integer, ModelData>> ticket, AtomicBoolean cancelled, Queue<Runnable> uploads, Object nativeJob) {
         this.ticket = ticket;
         this.cancelled = cancelled;
         this.uploads = uploads;
         this.nativeJob = nativeJob == null ? null : new WeakReference<>(nativeJob);
         this.staged = ticket.staged && uploads != null
            ? new HashMap<RenderType, VertexBuffer>()
            : Collections.<RenderType, VertexBuffer>emptyMap();
         this.requiredCells = ticket.source == null
            ? 0
            : (int)ticket.source.data().values().stream().filter(data -> !RoadTerrainModels.coverOnly(data)).count();
         this.cells = ticket.terrain && ticket.source != null && !ticket.source.data().isEmpty() ? new BitSet(4096) : null;
      }
   }

   static record UploadStats(int bytes, int uploads, int deferred, int resident) {
   }
}
