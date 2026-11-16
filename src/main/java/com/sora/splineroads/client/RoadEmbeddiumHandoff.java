package com.sora.splineroads.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.sora.splineroads.core.RoadTerrainMesh;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;

public final class RoadEmbeddiumHandoff {
   private static final Map<Object, Object> BUILT = Collections.synchronizedMap(new WeakHashMap<>());
   private static final Map<Object, List<Object>> DEFERRED = new WeakHashMap<>();
   private static final Map<Object, Long> WAIT_ORDER = new WeakHashMap<>();
   private static final RoadTerrainUploadBudget BUDGET = new RoadTerrainUploadBudget();
   private static volatile RoadEmbeddiumHandoff.Backend backend;
   private static WeakReference<Object> collecting = new WeakReference<>(null);
   private static long commits;
   private static long rejected;
   private static long deferred;
   private static long waitSerial;

   private RoadEmbeddiumHandoff() {
   }

   static void beginUploadFrame() {
      BUDGET.begin();
   }

   public static void begin(Object task, Object render, Object cancellation) {
      RoadTerrainHandoff.abort(task);
      RoadEmbeddiumHandoff.Backend api = api(render);
      if (!(Boolean)invoke(api.cancelled, cancellation)) {
         RoadTerrainHandoff.beginOptional(task, render, position(api, render), cancellation);
      }
   }

   public static Object built(Object output, Object task) {
      Object token = RoadTerrainHandoff.endOptional(task);
      if (output != null && token != null) {
         BUILT.put(output, token);
      } else {
         RoadTerrainHandoff.finishOptional(token);
      }

      return output;
   }

   public static Object empty(Object output) {
      RoadEmbeddiumHandoff.Backend api = api(output);
      Object render = get(api.render, output);
      RoadTerrainHandoff.beginOptional(output, render, position(api, render));
      BUILT.put(output, RoadTerrainHandoff.endOptional(output));
      return output;
   }

   public static ArrayList collect(ArrayList outputs, Object manager) {
      api(manager);
      List<Object> retained = DEFERRED.remove(manager);
      if (retained != null) {
         outputs.addAll(retained);
      }

      if (!outputs.isEmpty()) {
         collecting = new WeakReference<>(manager);
      }

      return outputs;
   }

   public static Object uploadLock() {
      return RoadTerrainHandoff.STATE;
   }

   public static void releaseRegions(Object manager) {
      RoadEmbeddiumHandoff.Backend api = api(manager);

      for (Object region : (Collection)invoke(api.loadedRegions, manager)) {
         for (int i = 0; i < api.regionSize; i++) {
            Object owner = invoke(api.regionSection, region, i);
            if (owner != null) {
               RoadTerrainHandoff.release(owner);
            }
         }
      }
   }

   public static void beforeUpload(Collection<?> results) {
      Iterator<?> it = results.iterator();

      while (it.hasNext()) {
         Object output = it.next();
         Object token = BUILT.get(output);
         if (token != null && RoadTerrainHandoff.terrainOptional(token) && !RoadTerrainHandoff.validOptional(token)) {
            Object render = get(backend.render, output);
            retireCompletedJob(token, render);
            retry(token, render);
            BUILT.remove(output);
            RoadTerrainHandoff.finishOptional(token);
            it.remove();
            rejected++;
         }
      }
   }

   public static List filter(List selected, ArrayList originals) {
      RenderSystem.assertOnRenderThread();
      if (selected.isEmpty() && originals.isEmpty()) {
         return selected;
      } else {
         ArrayList accepted = null;
         ArrayList<Object> postponed = null;
         if (originals.size() != selected.size()) {
            Set<Object> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
            chosen.addAll(selected);

            for (Object output : originals) {
               if (!chosen.contains(output)) {
                  Object token = BUILT.remove(output);
                  retireCompletedJob(token, get(backend.render, output));
                  if (token != null
                     && RoadTerrainHandoff.validOptional(token)
                     && RoadTerrainHandoff.stagedOptional(token)
                     && (backend.indexOnly == null || !(Boolean)invoke(backend.indexOnly, output))) {
                     retry(token, get(backend.render, output));
                  }

                  RoadTerrainHandoff.finishOptional(token);
               }
            }
         }

         List ordered = selected;
         if (!WAIT_ORDER.isEmpty()) {
            ordered = new ArrayList(selected);
            ordered.sort(Comparator.comparingLong(outputx -> WAIT_ORDER.getOrDefault(get(backend.render, outputx), Long.MAX_VALUE)));
         }

         for (int i = 0; i < ordered.size(); i++) {
            Object outputx = ordered.get(i);
            RoadEmbeddiumHandoff.Backend api = backend;
            Object token = BUILT.get(outputx);
            Object render = get(api.render, outputx);
            if (token != null && RoadTerrainHandoff.terrainOptional(token) && !RoadTerrainHandoff.validOptional(token)) {
               rejected++;
               retireCompletedJob(token, render);
               retry(token, render);
               BUILT.remove(outputx);
               RoadTerrainHandoff.finishOptional(token);
               if (accepted == null) {
                  accepted = new ArrayList(ordered.subList(0, i));
               }
            } else if (token != null && RoadTerrainHandoff.stagedOptional(token) && !BUDGET.claim(bytes(api, outputx), false)) {
               WAIT_ORDER.computeIfAbsent(render, key -> ++waitSerial);
               if (postponed == null) {
                  postponed = new ArrayList<>();
               }

               postponed.add(outputx);
               deferred++;
               if (accepted == null) {
                  accepted = new ArrayList(ordered.subList(0, i));
               }
            } else {
               if (token != null) {
                  WAIT_ORDER.remove(render);
               }

               if (accepted != null) {
                  accepted.add(outputx);
               }
            }
         }

         if (postponed != null) {
            Set<Object> retained = Collections.newSetFromMap(new IdentityHashMap<>());
            retained.addAll(postponed);
            originals.removeIf(retained::contains);
            DEFERRED.computeIfAbsent(Objects.requireNonNull(collecting.get()), k -> new ArrayList<>()).addAll(postponed);
         }

         return (List)(accepted == null ? ordered : accepted);
      }
   }

   public static void uploaded(Collection<?> results) {
      RenderSystem.assertOnRenderThread();
      RoadEmbeddiumHandoff.Backend api = backend;

      for (Object output : results) {
         if (api.indexOnly == null || !(Boolean)invoke(api.indexOnly, output)) {
            Object render = get(api.render, output);
            Object token = BUILT.remove(output);
            if (token == null) {
               RoadTerrainHandoff.release(render);
            } else if (!RoadTerrainHandoff.ownersOptional(token)) {
               RoadTerrainHandoff.commitOptional(token, null);
               RoadTerrainHandoff.finishOptional(token);
               commits++;
            } else {
               Object region = invoke(api.region, render);
               Object storage = invoke(api.storage, region, api.solid);
               int index = (Integer)invoke(api.index, render);
               Object allocation = storage instanceof RoadEmbeddiumStorage access ? access.splineroads$allocation(index) : null;
               Object info = get(api.info, output);
               if (!RoadTerrainHandoff.ownersOptional(token)
                  || allocation != null && info != null && ((Integer)get(api.infoFlags, info) & api.geometryMask) != 0) {
                  RoadTerrainHandoff.commitOptional(token, new RoadEmbeddiumHandoff.Proof(storage, allocation, index));
                  RoadTerrainHandoff.finishOptional(token);
                  commits++;
               } else {
                  RoadTerrainHandoff.release(render);
                  retry(token, render);
                  RoadTerrainHandoff.finishOptional(token);
                  rejected++;
               }
            }
         }
      }
   }

   public static boolean alive(Object render, RoadTerrainMesh.Section section, Object receipt) {
      RoadEmbeddiumHandoff.Backend api = backend;
      if (api != null && api.renderClass.isInstance(render) && receipt instanceof RoadEmbeddiumHandoff.Proof proof) {
         if ((Boolean)invoke(api.disposed, render) || !position(api, render).equals(section)) {
            return false;
         } else if ((Boolean)invoke(api.isBuilt, render) && ((Integer)invoke(api.flags, render) & api.geometryMask) != 0) {
            Object storage = invoke(api.storage, invoke(api.region, render), api.solid);
            if (storage == proof.storage && storage instanceof RoadEmbeddiumStorage access && access.splineroads$allocation(proof.index) == proof.allocation) {
               return true;
            }

            return false;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static void retry(Object token, Object render) {
      RoadTerrainMesh.Section section = RoadTerrainHandoff.optionalSection(token);
      if (section != null && !(Boolean)invoke(backend.disposed, render) && Minecraft.getInstance().level != null) {
         Minecraft.getInstance().levelRenderer.setSectionDirty(section.x(), section.y(), section.z());
      }
   }

   static boolean refresh(Object render, RoadTerrainMesh.Section section) {
      RoadEmbeddiumHandoff.Backend api = backend;
      if (api == null || render == null || !api.renderClass.isInstance(render) || (Boolean)invoke(api.disposed, render)) {
         return false;
      } else if (invoke(api.job, render) != null) {
         return false;
      } else {
         Minecraft mc = Minecraft.getInstance();
         if (mc.level == null) {
            return false;
         } else {
            mc.levelRenderer.setSectionDirty(section.x(), section.y(), section.z());
            return true;
         }
      }
   }

   private static void retireCompletedJob(Object token, Object render) {
      Object job = RoadTerrainHandoff.optionalJob(token);
      if (job != null && invoke(backend.job, render) == job) {
         // NOTE: the (Object) cast matters. Without it javac treats the trailing `null` as the
         // whole varargs array, so invoke() calls setJob with zero arguments and the JVM throws
         // "wrong number of arguments: 0 expected: 1" while Sodium uploads chunk builds.
         invoke(backend.setJob, render, (Object)null);
      }
   }

   private static int bytes(RoadEmbeddiumHandoff.Backend api, Object output) {
      long size = 0L;

      for (Object mesh : ((Map)get(api.meshes, output)).values()) {
         Object vertices = invoke(api.vertices, mesh);
         if (vertices != null) {
            size += (long)((Integer)invoke(api.length, vertices)).intValue();
         }

         if (api.indices != null) {
            Object indices = invoke(api.indices, mesh);
            if (indices != null) {
               size += (long)((Integer)invoke(api.length, indices)).intValue();
            }
         }
      }

      return (int)Math.min(2147483647L, size);
   }

   private static RoadTerrainMesh.Section position(RoadEmbeddiumHandoff.Backend api, Object render) {
      return new RoadTerrainMesh.Section((Integer)invoke(api.x, render), (Integer)invoke(api.y, render), (Integer)invoke(api.z, render));
   }

   static String status() {
      int pending = DEFERRED.values().stream().mapToInt(List::size).sum();
      return backend == null
         ? ""
         : ", Embeddium " + commits + " commits / " + rejected + " rejected, " + pending + " deferred, " + BUDGET.bytes() + " bytes/batch";
   }

   static boolean active() {
      return backend != null;
   }

   static int pending() {
      return DEFERRED.values().stream().mapToInt(List::size).sum();
   }

   static int uploadedBytes() {
      return BUDGET.bytes();
   }

   static int uploadedCount() {
      return BUDGET.uploads();
   }

   private static RoadEmbeddiumHandoff.Backend api(Object render) {
      RoadEmbeddiumHandoff.Backend value = backend;
      if (value != null) {
         return value;
      } else {
         synchronized (RoadEmbeddiumHandoff.class) {
            if (backend == null) {
               backend = new RoadEmbeddiumHandoff.Backend(render.getClass().getClassLoader());
            }

            return backend;
         }
      }
   }

   private static Object invoke(Method method, Object object, Object... args) {
      try {
         return method.invoke(object, args);
      } catch (ReflectiveOperationException var4) {
         throw new IllegalStateException("SplineRoads Embeddium upload adapter: " + method, var4);
      }
   }

   private static Object get(Field field, Object object) {
      try {
         return field.get(object);
      } catch (ReflectiveOperationException var3) {
         throw new IllegalStateException("SplineRoads Embeddium output field: " + field, var3);
      }
   }

   private static final class Backend {
      final Class<?> renderClass;
      final Method x;
      final Method y;
      final Method z;
      final Method disposed;
      final Method region;
      final Method index;
      final Method storage;
      final Method indexOnly;
      final Method cancelled;
      final Method vertices;
      final Method indices;
      final Method length;
      final Method isBuilt;
      final Method flags;
      final Method loadedRegions;
      final Method regionSection;
      final Method job;
      final Method setJob;
      final Field render;
      final Field meshes;
      final Field info;
      final Field infoFlags;
      final int geometryMask;
      final int regionSize;
      final Object solid;

      Backend(ClassLoader loader) {
         try {
            String root = "net.caffeinemc.mods.sodium.client.";
            this.renderClass = Class.forName(root + "render.chunk.RenderSection", false, loader);
            this.x = this.renderClass.getMethod("getChunkX");
            this.y = this.renderClass.getMethod("getChunkY");
            this.z = this.renderClass.getMethod("getChunkZ");
            this.disposed = this.renderClass.getMethod("isDisposed");
            this.region = this.renderClass.getMethod("getRegion");
            this.index = this.renderClass.getMethod("getSectionIndex");
            this.isBuilt = this.renderClass.getMethod("isBuilt");
            this.flags = this.renderClass.getMethod("getFlags");
            Class<?> cancellation = Class.forName(root + "util.task.CancellationToken", false, loader);
            this.job = this.renderClass.getMethod("getRunningJob");
            Class<?> chunkJob = Class.forName(root + "render.chunk.compile.executor.ChunkJob", false, loader);
            this.setJob = this.renderClass.getMethod("setRunningJob", chunkJob);
            this.geometryMask = 1 << Class.forName(root + "render.chunk.RenderSectionFlags", false, loader).getField("HAS_BLOCK_GEOMETRY").getInt(null);
            Class<?> pass = Class.forName(root + "render.chunk.terrain.TerrainRenderPass", false, loader);
            this.solid = Class.forName(root + "render.chunk.terrain.DefaultTerrainRenderPasses", true, loader).getField("SOLID").get(null);
            Class<?> regionClass = Class.forName(root + "render.chunk.region.RenderRegion", false, loader);
            this.storage = regionClass.getMethod("getStorage", pass);
            this.regionSection = regionClass.getMethod("getSection", int.class);
            this.regionSize = regionClass.getField("REGION_SIZE").getInt(null);
            this.loadedRegions = Class.forName(root + "render.chunk.region.RenderRegionManager", false, loader).getMethod("getLoadedRegions");
            Class<?> output = Class.forName(root + "render.chunk.compile.ChunkBuildOutput", false, loader);
            this.render = output.getField("render");
            this.meshes = output.getField("meshes");
            this.info = output.getField("info");

            Method idx;
            try {
               idx = output.getMethod("isIndexOnlyUpload");
            } catch (NoSuchMethodException var13) {
               idx = null;
            }

            this.indexOnly = idx;
            this.infoFlags = Class.forName(root + "render.chunk.data.BuiltSectionInfo", false, loader).getField("flags");
            this.cancelled = cancellation.getMethod("isCancelled");
            Class<?> mesh = Class.forName(root + "render.chunk.data.BuiltSectionMeshParts", false, loader);
            this.vertices = mesh.getMethod("getVertexData");

            Method idxParts;
            try {
               idxParts = mesh.getMethod("getIndexData");
            } catch (NoSuchMethodException var12) {
               idxParts = null;
            }

            this.indices = idxParts;
            this.length = Class.forName(root + "util.NativeBuffer", false, loader).getMethod("getLength");
         } catch (ReflectiveOperationException var14) {
            throw new IllegalStateException("Unsupported Embeddium terrain upload API", var14);
         }
      }
   }

   private static record Proof(Object storage, Object allocation, int index) {
   }
}
