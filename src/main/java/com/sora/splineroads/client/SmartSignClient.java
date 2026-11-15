package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.core.SmartSignFaces;
import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.world.SmartSignCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class SmartSignClient {
   private static int frameCandidates;
   private static int frameDrawn;
   private static int frameCulled;
   private static double frameCpuMs;
   private static final int MAX_RESIDENT = 512;
   private static final int MAX_COLD = 512;
   private static final int MAX_BYTES = 33554432;
   private static final LinkedHashMap<SmartSigns.Key, SmartSigns.State> states = new LinkedHashMap<>();
   private static final LinkedHashMap<SmartSigns.Key, SmartSignClient.Baked> cache = new LinkedHashMap<>(32, 0.75F, true);
   private static final RenderType MATERIAL = RenderType.entitySolid(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/white_concrete.png"));
   private static final RenderType PLATE_MATERIAL = RenderType.text(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/white_concrete.png"));
   private static volatile boolean resourcesDirty;
   private static SmartSigns.State preview;
   private static RoadRecord previewRoad;
   private static SmartSignClient.Baked previewMesh;
   public static long rebuilds;
   public static long textLayouts;
   private static int bytes;
   private static long stateBytes;
   private static final Map<SmartSigns.Key, Integer> stateWeights = new HashMap<>();
   private static Set<SmartSigns.Key> visibleKeys = Set.of();
   private static final int HOT_CPU_BYTES = 67108864;
   private static final int COLD_CPU_BYTES = 16777216;
   private static final int CPU_BYTES = 83886080;
   private static final int GPU_BYTES = 16777216;
   private static final Map<SmartSigns.Key, SmartSignClient.Weight> hotWeights = new HashMap<>();
   private static final Set<SmartSigns.Key> coldCompacted = new HashSet<>();
   private static boolean pendingUploads;
   private static int pendingUploadBytes;
   private static final Map<SmartSigns.Key, SmartSignClient.Candidate> candidatesByKey = new HashMap<>();
   private static final SignCandidateIndex<SmartSigns.Key> candidateIndex = new SignCandidateIndex<>();
   private static final Map<UUID, Set<SmartSigns.Key>> stateKeysByRoad = new HashMap<>();
   private static final Set<SmartSigns.Key> dirtyCandidates = new LinkedHashSet<>();
   private static int frameCandidateVisits;
   private static long anchorBuilds;
   private static int gpuBytes;
   private static int residentPlates;
   private static int streamedPlates;
   private static final Map<SmartSigns.Key, SmartSignClient.Failed> failed = new HashMap<>();
   private static long bakeAttempts;
   private static long bakeFailures;
   private static long bakeNanos;
   private static int frameBakes;
   private static long bakeDeadline;

   public static void resourceReloaded() {
      resourcesDirty = true;
   }

   static boolean pendingUploads() {
      return pendingUploads;
   }

   static int pendingUploadBytes() {
      return pendingUploadBytes;
   }

   private static SmartSignClient.Candidate candidate(SmartSigns.State state, RoadIndex.Built road) {
      SmartSignClient.Candidate old = candidatesByKey.get(state.key());
      if (old != null && old.state() == state && old.road() == road.record) {
         return old;
      } else {
         SmartSignClient.Candidate result = new SmartSignClient.Candidate(state, road.record, null, null, false);
         if (SmartSigns.valid(road.mesh, state)) {
            try {
               RoadGeometry.V anchor = old != null && old.valid() && old.road() == road.record && sameGeometry(old.state(), state)
                  ? old.anchor()
                  : SmartSigns.anchor(road.mesh, state);
               anchorBuilds++;
               result = new SmartSignClient.Candidate(state, road.record, anchor, coarseBounds(road.mesh, state, anchor), true);
            } catch (IllegalArgumentException var5) {
            }
         }

         if (!result.valid()) {
            evict(state.key());
         }

         candidatesByKey.put(state.key(), result);
         return result;
      }
   }

   private static void refreshCandidates() {
      for (SmartSigns.Key key : dirtyCandidates) {
         SmartSigns.State state = states.get(key);
         RoadIndex.Built road = ClientRoads.INDEX.roads.get(key.road());
         if (state != null && road != null && state.target() != null && state.diagnostic().isEmpty()) {
            SmartSignClient.Candidate value = candidate(state, road);
            if (value.valid()) {
               candidateIndex.put(key, value.anchor().x(), value.anchor().z());
            } else {
               candidateIndex.remove(key);
            }
         } else {
            candidatesByKey.remove(key);
            candidateIndex.remove(key);
         }
      }

      dirtyCandidates.clear();
   }

   private static List<SmartSignClient.Candidate> visibleCandidates(RoadGeometry.V eye, Predicate<AABB> visible) {
      refreshCandidates();
      ArrayList<SmartSignClient.Candidate> result = new ArrayList<>();
      frameCandidateVisits = 0;

      for (SmartSigns.Key key : candidateIndex.near(eye.x(), eye.z())) {
         frameCandidateVisits++;
         SmartSignClient.Candidate value = candidatesByKey.get(key);
         if (value != null && ClientRoads.active(key.road()) && !(value.anchor().distance(eye) > 144.0) && visible.test(value.bounds())) {
            result.add(value);
         }
      }

      return result;
   }

   static void candidatesChanged(UUID road) {
      for (SmartSigns.Key key : stateKeysByRoad.getOrDefault(road, Set.of())) {
         candidatesByKey.remove(key);
         candidateIndex.remove(key);
         dirtyCandidates.add(key);
      }
   }

   private static AABB coarseBounds(RoadGeometry.Mesh mesh, SmartSigns.State s) {
      return coarseBounds(mesh, s, SmartSigns.anchor(mesh, s));
   }

   private static AABB coarseBounds(RoadGeometry.Mesh mesh, SmartSigns.State s, RoadGeometry.V at) {
      double radius = SmartSigns.freestanding(s.mode()) ? Math.max(5.0, (double)s.branches().size() * 2.4) : mesh.settings().width() / 2.0 + 4.0;
      if (s.mode() == SmartSigns.Mode.IC_JCT) {
         long columns = Math.max(s.branches().stream().filter(b -> b.side() < 0).count(), s.branches().stream().filter(b -> b.side() > 0).count());
         radius = Math.max(radius, mesh.settings().width() / 2.0 + 6.0 + (double)Math.max(0L, columns - 1L) * 1.95);
      }

      return new AABB(at.x() - radius, at.y() - 1.0, at.z() - radius, at.x() + radius, at.y() + 26.0, at.z() + radius);
   }

   private static void release(SmartSignClient.Baked b) {
      if (b != null) {
         b.batch().close();
         b.plate().close();
      }
   }

   private static void clearCache() {
      cache.values().forEach(SmartSignClient::release);
      cache.clear();
      failed.clear();
      candidatesByKey.clear();
      candidateIndex.clear();
      dirtyCandidates.addAll(states.keySet());
      hotWeights.clear();
      coldCompacted.clear();
      bytes = 0;
   }

   static void visibilityChanged() {
      clearCache();
      release(previewMesh);
      previewMesh = null;
      resourcesDirty = true;
      visibleKeys = Set.of();
      pendingUploadBytes = 0;
      streamedPlates = 0;
      residentPlates = 0;
      gpuBytes = 0;
      frameCandidateVisits = 0;
      frameCulled = 0;
      frameBakes = 0;
      frameDrawn = 0;
      frameCandidates = 0;
      pendingUploads = false;
      frameCpuMs = 0.0;
   }

   public static void clear() {
      clearCache();
      states.clear();
      stateKeysByRoad.clear();
      dirtyCandidates.clear();
      stateWeights.clear();
      cache.clear();
      bytes = 0;
      stateBytes = 0L;
      pendingUploadBytes = 0;
      pendingUploads = false;
      preview(null, null);
      SmartTextScreen.clear();
   }

   public static void roadChanged(UUID road) {
      hotWeights.keySet().removeIf(k -> k.road().equals(road));
      candidatesByKey.keySet().removeIf(k -> k.road().equals(road));
      candidatesChanged(road);
      failed.keySet().removeIf(k -> k.road().equals(road));

      for (SmartSigns.Key key : new ArrayList<>(cache.keySet())) {
         if (key.road().equals(road)) {
            evict(key);
         }
      }

      if (preview != null && preview.key().road().equals(road)) {
         SmartTextScreen.clear();
      }
   }

   public static void remove(UUID road) {
      remove(road, true);
   }

   public static void evictSource(UUID road) {
      remove(road, false);
   }

   private static void remove(UUID road, boolean deleted) {
      for (SmartSigns.Key key : new ArrayList<>(states.keySet())) {
         if (key.road().equals(road) || deleted && Objects.equals(states.get(key).target(), road)) {
            removeState(key);
         }
      }

      for (SmartSigns.Key keyx : new ArrayList<>(cache.keySet())) {
         if (keyx.road().equals(road)) {
            evict(keyx);
         }
      }

      if (preview != null && (preview.key().road().equals(road) || deleted && Objects.equals(preview.target(), road))) {
         SmartTextScreen.clear();
      }
   }

   public static void receive(CompoundTag tag) {
      UUID road = tag.getUUID("Id");
      HashMap<SmartSigns.Key, SmartSigns.State> next = new HashMap<>();

      for (Tag v : tag.getList("Signs", 10)) {
         SmartSigns.State s = SmartSignCodec.read((CompoundTag)v);
         if (s.key().road().equals(road)) {
            next.put(s.key(), s);
         }
      }

      for (SmartSigns.Key k : new ArrayList<>(states.keySet())) {
         if (k.road().equals(road) && !next.containsKey(k)) {
            removeState(k);
         }
      }

      next.forEach((key, sx) -> {
         SmartSigns.State old = states.get(key);
         if (old == null || sx.revision() >= old.revision()) {
            stateBytes = stateBytes - (long)stateWeights.getOrDefault(key, 0).intValue();
            int weight = weight(sx);
            stateWeights.put(key, weight);
            stateBytes += (long)weight;
            states.put(key, sx);
            stateKeysByRoad.computeIfAbsent(road, kx -> new HashSet<>()).add(key);
            dirtyCandidates.add(key);
            if (old != null && !old.equals(sx)) {
               hotWeights.remove(key);
            }

            if (old != null && !sameGeometry(sx, old)) {
               evict(key);
            }
         }
      });
      if (preview != null && preview.key().road().equals(road)) {
         SmartSigns.State n = next.get(preview.key());
         if (n == null || !Objects.equals(n.target(), preview.target()) || n.revision() >= preview.revision()) {
            SmartTextScreen.clear();
         }
      }
   }

   private static int weight(SmartSigns.State s) {
      int n = 256 + s.branches().stream().mapToInt(b -> 128 + b.path().size() * 40 + b.lanes().size() * 16).sum();

      for (Entry<String, SmartSigns.Text> e : s.text().entrySet()) {
         n += 96 + 2 * (e.getKey().length() + e.getValue().value().length());
      }

      for (Map<String, SmartSigns.Text> h : s.drafts().values()) {
         for (Entry<String, SmartSigns.Text> e : h.entrySet()) {
            n += 96 + 2 * (e.getKey().length() + e.getValue().value().length());
         }
      }

      return n;
   }

   private static void removeState(SmartSigns.Key key) {
      hotWeights.remove(key);
      candidatesByKey.remove(key);
      candidateIndex.remove(key);
      dirtyCandidates.remove(key);
      Set<SmartSigns.Key> roadKeys = stateKeysByRoad.get(key.road());
      if (roadKeys != null) {
         roadKeys.remove(key);
         if (roadKeys.isEmpty()) {
            stateKeysByRoad.remove(key.road());
         }
      }

      failed.remove(key);
      states.remove(key);
      stateBytes = stateBytes - (long)stateWeights.getOrDefault(key, 0).intValue();
      stateWeights.remove(key);
      evict(key);
   }

   private static void evict(SmartSigns.Key key) {
      coldCompacted.remove(key);
      SmartSignClient.Baked b = cache.remove(key);
      if (b != null) {
         bytes = bytes - b.bytes();
         release(b);
      }
   }

   public static void preview(SmartSigns.State state, RoadRecord road) {
      SmartSignClient.Baked previous = previewMesh;
      release(previous);
      preview = state;
      previewRoad = road;
      previewMesh = state != null && RoadSignVisibility.enabled() ? bake(state, road, previous == null ? cache.get(state.key()) : previous) : null;
   }

   private static List<String> wrap(String value, int limit) {
      Font font = Minecraft.getInstance().font;
      ArrayList<String> out = new ArrayList<>();
      StringBuilder line = new StringBuilder();

      for (int cp : value.codePoints().toArray()) {
         if (cp == 10) {
            out.add(line.toString());
            line.setLength(0);
         } else {
            String c = new String(Character.toChars(cp));
            if (line.length() > 0 && font.width(line + c) > limit) {
               out.add(line.toString());
               line.setLength(0);
            }

            line.append(c);
         }
      }

      out.add(line.toString());
      return out;
   }

   private static List<SmartSignClient.Line> text(SmartSigns.Layout layout, SmartSigns.State s) {
      ArrayList<SmartSignClient.Line> out = new ArrayList<>();
      Font font = Minecraft.getInstance().font;

      for (SmartSigns.Field f : layout.fields()) {
         SmartSigns.Text word = f.editable() ? s.text().get(f.id()) : new SmartSigns.Text(f.literal(), 2, false);
         if (word != null && !word.value().isEmpty()) {
            if (!word.wrap() && word.value().contains("\n")) {
               throw new IllegalArgumentException("显式换行需要启用允许换行");
            }

            int allowed = word.wrap() ? f.lines() : 1;
            double scale = f.height() / 10.0 * new double[]{0.66, 0.84, 1.0}[word.size()];

            while (true) {
               int max = (int)Math.floor(f.width() / scale);
               List<String> rows = word.wrap() ? wrap(word.value(), max) : List.of(word.value());
               boolean fit = rows.size() <= allowed
                  && (double)(rows.size() * 10) * scale <= f.height() + 1.0E-7
                  && rows.stream().allMatch(linex -> font.width(linex) <= max);
               if (fit) {
                  for (int i = 0; i < rows.size(); i++) {
                     String line = rows.get(i);
                     out.add(
                        new SmartSignClient.Line(
                           Component.literal(line),
                           font.width(line),
                           f.x(),
                           f.y() + (double)((rows.size() - 1) * 5) * scale - (double)(i * 10) * scale,
                           scale,
                           f.color()
                        )
                     );
                  }
                  break;
               }

               if (scale <= 0.012001) {
                  throw new IllegalArgumentException("当前字体下文字超出区域，请缩短或启用换行");
               }

               scale = Math.max(0.012, scale * 0.92);
            }
         }
      }

      return List.copyOf(out);
   }

   public static void validate(RoadGeometry.Mesh mesh, SmartSigns.State state) {
      text(SmartSigns.layout(mesh, state), state);
   }

   private static boolean sameGeometry(SmartSigns.State a, SmartSigns.State b) {
      return a.key().equals(b.key())
         && a.mode() == b.mode()
         && a.entry().equals(b.entry())
         && Objects.equals(a.target(), b.target())
         && a.diagnostic().equals(b.diagnostic())
         && a.branches().equals(b.branches())
         && a.hiddenHeaders().equals(b.hiddenHeaders())
         && a.hiddenAccessories().equals(b.hiddenAccessories())
         && a.closedLanes().equals(b.closedLanes())
         && Objects.equals(a.support(), b.support());
   }

   private static boolean plateFace(RoadSurface.Face f, SmartSigns.Layout layout) {
      double lo = Double.POSITIVE_INFINITY;
      double hi = Double.NEGATIVE_INFINITY;

      for (RoadGeometry.V v : f.points()) {
         double d = v.sub(layout.origin()).dot(layout.front());
         lo = Math.min(lo, d);
         hi = Math.max(hi, d);
      }

      return lo >= 0.32999900000000004 && hi - lo < 1.0E-6;
   }

   private static SmartSigns.Layout textView(SmartSigns.Layout l) {
      return new SmartSigns.Layout(l.origin(), l.right(), l.front(), l.boards(), l.fields(), List.of());
   }

   private static SmartSignClient.Baked bake(SmartSigns.State s, RoadRecord road, SmartSignClient.Baked old) {
      boolean reuse = old != null && old.road() == road && sameGeometry(old.state(), s);
      SmartSigns.Layout layout = reuse ? old.layout() : SmartSigns.layout(road.mesh(), s);
      List<SmartSignClient.Line> lines = text(layout, s);
      textLayouts++;
      if (!reuse) {
         rebuilds++;
      }

      List<SmartSigns.Layout> views;
      RoadSignalBatch body;
      RoadSignalBatch plate;
      int geometryBytes;
      AABB bounds;
      if (reuse) {
         views = old.views();
         body = old.batch();
         plate = old.plate();
         geometryBytes = old.geometryBytes();
         bounds = old.bounds();
      } else {
         List<SmartSigns.Layout> fullViews = SmartSigns.views(layout, s.mode());
         ArrayList<RoadSurface.Face> bodyFaces = new ArrayList<>();
         ArrayList<RoadSurface.Face> plateFaces = new ArrayList<>();

         for (SmartSigns.Layout view : fullViews) {
            for (RoadSurface.Face face : SmartSignFaces.compose(view)) {
               if (fullViews.stream().anyMatch(v -> plateFace(face, v))) {
                  plateFaces.add(face);
               } else {
                  bodyFaces.add(face);
               }
            }
         }

         body = new RoadSignalBatch(bodyFaces);
         plate = new RoadSignalBatch(plateFaces);
         views = fullViews.stream().map(SmartSignClient::textView).toList();
         layout = textView(layout);
         double radius = layout.boards().stream().mapToDouble(v -> Math.abs(v.x()) + v.width() / 2.0).max().orElse(1.0) + 1.0;
         RoadGeometry.V o = layout.origin();
         bounds = new AABB(o.x() - radius, o.y() - (s.support() == null ? 0.0 : 5.2), o.z() - radius, o.x() + radius, o.y() + layout.top(), o.z() + radius);
         geometryBytes = 384
            + body.retainedBytes()
            + plate.retainedBytes()
            + views.stream().mapToInt(v -> 160 + v.boards().size() * 64 + v.fields().size() * 128).sum();
      }

      int textBytes = lines.stream().mapToInt(l -> 192 + l.text().getString().length() * 2).sum();
      return new SmartSignClient.Baked(s, road, layout, views, body, plate, lines, bounds, geometryBytes, geometryBytes + textBytes);
   }

   private static SmartSignClient.Baked measured(SmartSignClient.Baked b) {
      int geometry = 384
         + b.batch().retainedBytes()
         + b.plate().retainedBytes()
         + b.views().stream().mapToInt(v -> 160 + v.boards().size() * 64 + v.fields().size() * 128).sum();
      return new SmartSignClient.Baked(
         b.state(), b.road(), b.layout(), b.views(), b.batch(), b.plate(), b.lines(), b.bounds(), geometry, geometry + b.bytes() - b.geometryBytes()
      );
   }

   private static SmartSignClient.Baked compact(SmartSignClient.Baked b) {
      b.batch().compact();
      b.plate().compact();
      return measured(b);
   }

   private static SmartSignClient.Baked warm(SmartSignClient.Baked b) {
      if (!b.batch().compacted() && !b.plate().compacted()) {
         return b;
      } else {
         b.batch().warm();
         b.plate().warm();
         return measured(b);
      }
   }

   private static void trimCold() {
      int coldBytes = 0;
      int coldCount = 0;
      int compacted = 0;
      long deadline = System.nanoTime() + 500000L;

      for (Entry<SmartSigns.Key, SmartSignClient.Baked> entry : cache.entrySet()) {
         if (!visibleKeys.contains(entry.getKey())) {
            SmartSignClient.Baked before = entry.getValue();
            SmartSignClient.Baked after = before;
            if (!coldCompacted.contains(entry.getKey()) && compacted < 2 && (compacted == 0 || System.nanoTime() < deadline)) {
               after = compact(before);
               coldCompacted.add(entry.getKey());
               entry.setValue(after);
               bytes = bytes + (after.bytes() - before.bytes());
               compacted++;
            }

            coldBytes += after.bytes();
            coldCount++;
         }
      }

      Iterator<Entry<SmartSigns.Key, SmartSignClient.Baked>> it = cache.entrySet().iterator();

      while (it.hasNext() && (coldCount > 512 || coldBytes > 16777216 || bytes > 83886080)) {
         Entry<SmartSigns.Key, SmartSignClient.Baked> e = it.next();
         if (!visibleKeys.contains(e.getKey())) {
            coldCount--;
            coldBytes -= e.getValue().bytes();
            bytes = bytes - e.getValue().bytes();
            coldCompacted.remove(e.getKey());
            release(e.getValue());
            it.remove();
         }
      }
   }

   private static List<SmartSignClient.Candidate> admitted(List<SmartSignClient.Candidate> sorted) {
      ArrayList<SmartSignClient.Candidate> result = new ArrayList<>();
      int hotBytes = 0;

      for (SmartSignClient.Candidate c : sorted) {
         SmartSignClient.Weight w = hotWeights.get(c.state().key());
         int size = w != null && w.state().equals(c.state()) && w.road() == c.road() ? w.bytes() : 0;
         if (size <= 67108864 - hotBytes) {
            result.add(c);
            hotBytes += size;
         }
      }

      return result;
   }

   private static SmartSignClient.Baked baked(SmartSigns.State s, RoadRecord road) {
      SmartSignClient.Baked old = cache.get(s.key());
      if (old == null || !old.state().equals(s) || old.road() != road) {
         SmartSignClient.Failed failure = failed.get(s.key());
         if (failure != null && failure.state().equals(s) && failure.road() == road) {
            return null;
         } else {
            long start = System.nanoTime();
            bakeAttempts++;

            SmartSignClient.Baked value;
            label122: {
               Object var8;
               try {
                  value = bake(s, road, old);
                  value = warm(value);
                  break label122;
               } catch (IllegalArgumentException var12) {
                  bakeFailures++;
                  failed.put(s.key(), new SmartSignClient.Failed(s, road));
                  var8 = null;
               } finally {
                  bakeNanos = bakeNanos + (System.nanoTime() - start);
               }

               return (SmartSignClient.Baked)var8;
            }

            hotWeights.put(s.key(), new SmartSignClient.Weight(s, road, value.bytes()));
            coldCompacted.remove(s.key());
            if (old != null) {
               cache.remove(s.key());
               bytes = bytes - old.bytes();
               if (old.batch() != value.batch()) {
                  release(old);
               }
            }

            while (!cache.isEmpty() && bytes + value.bytes() > 83886080) {
               SmartSigns.Key victim = null;

               for (SmartSigns.Key key : cache.keySet()) {
                  if (!visibleKeys.contains(key)) {
                     victim = key;
                     break;
                  }
               }

               if (victim == null) {
                  break;
               }

               evict(victim);
            }

            if (value.bytes() <= 67108864 && bytes + value.bytes() <= 83886080) {
               cache.put(s.key(), value);
               bytes = bytes + value.bytes();
               failed.remove(s.key());
               return value;
            } else {
               release(value);
               return null;
            }
         }
      } else if (visibleKeys.contains(s.key())) {
         coldCompacted.remove(s.key());
         SmartSignClient.Baked valuex = warm(old);
         cache.put(s.key(), valuex);
         bytes = bytes + (valuex.bytes() - old.bytes());
         return valuex;
      } else {
         return old;
      }
   }

   public static void render(RenderLevelStageEvent event) {
      if (!RoadSignVisibility.enabled()) {
         pendingUploadBytes = 0;
         streamedPlates = 0;
         residentPlates = 0;
         gpuBytes = 0;
         frameCandidateVisits = 0;
         frameCulled = 0;
         frameBakes = 0;
         frameDrawn = 0;
         frameCandidates = 0;
         pendingUploads = false;
         frameCpuMs = 0.0;
      } else {
         long frameStart = System.nanoTime();
         Minecraft mc = Minecraft.getInstance();
         if (mc.level != null) {
            if (resourcesDirty) {
               resourcesDirty = false;
               clearCache();
               release(previewMesh);
               TollNameRenderer.clear();
               if (preview != null) {
                  try {
                     previewMesh = bake(preview, previewRoad, null);
                  } catch (IllegalArgumentException var44) {
                     previewMesh = null;
                  }
               }
            }

            Vec3 pos = event.getCamera().getPosition();
            RoadGeometry.V eye = new RoadGeometry.V(pos.x, pos.y, pos.z);
            BufferSource buffers = mc.renderBuffers().bufferSource();
            HashSet<RenderType> used = new HashSet<>();
            PoseStack pose = event.getPoseStack();
            boolean editor = mc.player != null
               && (mc.player.getMainHandItem().is((Item)SplineRoads.TEXT_EDITOR.get()) || mc.player.getOffhandItem().is((Item)SplineRoads.TEXT_EDITOR.get()));
            ArrayList<SmartSignClient.Baked> visible = editor ? new ArrayList<>() : null;
            List<SmartSignClient.Candidate> candidates = visibleCandidates(eye, event.getFrustum()::isVisible);
            HashSet<SmartSigns.Key> keys = new HashSet<>();
            frameCandidates = candidates.size();
            frameBakes = 0;
            bakeDeadline = System.nanoTime() + 2000000L;
            ArrayList<SmartSignClient.Baked> drawn = new ArrayList<>();
            ArrayList<Integer> lights = new ArrayList<>();
            candidates.sort(
               Comparator.<SmartSignClient.Candidate>comparingDouble(c -> c.anchor().distance(eye))
                  .thenComparing(c -> c.state().key().road())
                  .thenComparingInt(c -> c.state().key().slot())
                  .thenComparingInt(c -> c.state().key().flow())
            );
            List<SmartSignClient.Candidate> admitted = admitted(candidates);
            keys.clear();

            for (SmartSignClient.Candidate candidate : admitted) {
               keys.add(candidate.state().key());
            }

            visibleKeys = keys;
            frameCulled = frameCandidates - admitted.size();
            int hotBytes = 0;

            for (SmartSignClient.Candidate candidate : admitted) {
               SmartSigns.State s = candidate.state();
               RoadRecord road = candidate.road();
               SmartSignClient.Failed failure = failed.get(s.key());
               if (failure == null || !failure.state().equals(s) || failure.road() != road) {
                  SmartSignClient.Baked cached = cache.get(s.key());
                  boolean needsBake = cached == null || !cached.state().equals(s) || cached.road() != road;
                  if (!needsBake || frameBakes <= 0 || frameBakes < 2 && System.nanoTime() < bakeDeadline) {
                     if (needsBake) {
                        frameBakes++;
                     }

                     SmartSignClient.Baked b = preview != null && preview.key().equals(s.key()) ? previewMesh : baked(s, road);
                     if (b != null && !b.layout().boards().isEmpty()) {
                        if (b.bytes() > 67108864 - hotBytes) {
                           keys.remove(s.key());
                           frameCulled++;
                        } else {
                           hotBytes += b.bytes();
                           RoadGeometry.V o = b.layout().origin();
                           AABB bounds = b.bounds();
                           if (event.getFrustum().isVisible(bounds)) {
                              if (editor) {
                                 visible.add(b);
                              }

                              int light = LevelRenderer.getLightColor(
                                 mc.level, BlockPos.containing(o.x() + b.layout().front().x(), o.y() + 2.0, o.z() + b.layout().front().z())
                              );
                              drawn.add(b);
                              lights.add(light);
                           }
                        }
                     }
                  }
               }
            }

            frameDrawn = drawn.size();
            trimCold();
            pendingUploads = false;
            pendingUploadBytes = 0;
            buffers.endBatch();

            for (int i = 0; i < drawn.size(); i++) {
               drawn.get(i).batch().emit(pose, buffers, eye, t -> MATERIAL, used, lights.get(i));
            }

            buffers.endBatch(MATERIAL);
            used.remove(MATERIAL);
            ArrayList<RoadSignalBatch> resident = new ArrayList<>();
            ArrayList<Integer> residentLight = new ArrayList<>();
            ArrayList<Integer> streaming = new ArrayList<>();
            gpuBytes = 0;
            int admittedGpuBytes = 0;
            int admittedGpuCount = 0;

            for (int i = 0; i < drawn.size(); i++) {
               RoadSignalBatch plate = drawn.get(i).plate();
               if (admittedGpuCount < 512 && admittedGpuBytes + plate.gpuBytes() <= 16777216) {
                  admittedGpuCount++;
                  admittedGpuBytes += plate.gpuBytes();
                  if (plate.preparePlate(lights.get(i))) {
                     gpuBytes = gpuBytes + plate.gpuBytes();
                     resident.add(plate);
                     residentLight.add(plate.residentLight());
                  } else {
                     streaming.add(i);
                  }

                  if (!plate.currentLight(lights.get(i))) {
                     pendingUploads = true;
                     pendingUploadBytes = Math.max(pendingUploadBytes, plate.gpuBytes());
                  }
               } else {
                  plate.close();
                  streaming.add(i);
               }
            }

            for (SmartSignClient.Baked b : cache.values()) {
               if (!visibleKeys.contains(b.state().key())) {
                  b.plate().close();
               }
            }

            RoadSignalBatch.drawPlates(resident, residentLight, pose, eye, PLATE_MATERIAL, event.getProjectionMatrix(), event.getModelViewMatrix());

            for (int ix : streaming) {
               drawn.get(ix).plate().emitPlate(pose, buffers, eye, PLATE_MATERIAL, used, lights.get(ix));
            }

            if (!streaming.isEmpty()) {
               buffers.endBatch(PLATE_MATERIAL);
            }

            residentPlates = resident.size();
            streamedPlates = streaming.size();

            for (int ix = 0; ix < drawn.size(); ix++) {
               SmartSignClient.Baked bx = drawn.get(ix);
               int light = lights.get(ix);
               RoadGeometry.V o = bx.layout().origin();
               if (!(o.distance(eye) > 80.0)) {
                  for (SmartSigns.Layout view : bx.views()) {
                     if (!(eye.sub(view.origin()).dot(view.front()) <= 0.0)) {
                        for (SmartSignClient.Line line : bx.lines()) {
                           RoadGeometry.V at = view.at(line.x(), line.y(), 0.332);
                           pose.pushPose();
                           pose.translate(at.x() - eye.x(), at.y() - eye.y(), at.z() - eye.z());
                           pose.mulPose(Axis.YP.rotation((float)Math.atan2(-view.right().z(), view.right().x())));
                           pose.scale((float)line.scale(), (float)(-line.scale()), (float)line.scale());
                           mc.font
                              .drawInBatch(
                                 line.text(),
                                 (float)(-line.width()) / 2.0F,
                                 -4.5F,
                                 line.color(),
                                 false,
                                 pose.last().pose(),
                                 buffers,
                                 DisplayMode.NORMAL,
                                 0,
                                 light
                              );
                           pose.popPose();
                        }
                     }
                  }
               }
            }

            used.forEach(buffers::endBatch);
            if (editor && mc.player != null) {
               Vec3 look = mc.player.getLookAngle();
               RoadGeometry.V ray = new RoadGeometry.V(look.x, look.y, look.z);
               SmartSignClient.Baked best = null;
               SmartSigns.Layout selected = null;
               SmartSigns.Field field = null;
               double nearest = 96.0;

               for (SmartSignClient.Baked bx : visible) {
                  for (SmartSigns.Layout l : bx.views()) {
                     double denominator = ray.dot(l.front());
                     if (!(denominator >= -1.0E-5)) {
                        double d = l.origin().add(l.front().mul(0.332)).sub(eye).dot(l.front()) / denominator;
                        if (!(d < 0.0) && !(d > nearest)) {
                           RoadGeometry.V hit = eye.add(ray.mul(d)).sub(l.origin());
                           double x = hit.dot(l.right());
                           double y = hit.y();

                           for (SmartSigns.Field f : l.fields()) {
                              if (f.editable() && Math.abs(x - f.x()) <= f.width() / 2.0 && Math.abs(y - f.y()) <= f.height() / 2.0) {
                                 best = bx;
                                 selected = l;
                                 field = f;
                                 nearest = d;
                              }
                           }
                        }
                     }
                  }
               }

               if (best != null) {
                  VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
                  pose.pushPose();
                  pose.translate(-eye.x(), -eye.y(), -eye.z());
                  RoadGeometry.V[] corners = new RoadGeometry.V[]{
                     selected.at(field.x() - field.width() / 2.0, field.y() - field.height() / 2.0, 0.337),
                     selected.at(field.x() + field.width() / 2.0, field.y() - field.height() / 2.0, 0.337),
                     selected.at(field.x() + field.width() / 2.0, field.y() + field.height() / 2.0, 0.337),
                     selected.at(field.x() - field.width() / 2.0, field.y() + field.height() / 2.0, 0.337)
                  };

                  for (int ixx = 0; ixx < 4; ixx++) {
                     RoadGeometry.V a = corners[ixx];
                     RoadGeometry.V bx = corners[(ixx + 1) % 4];
                     RoadGeometry.V n = bx.sub(a).mul(1.0 / a.distance(bx));

                     for (RoadGeometry.V v : List.of(a, bx)) {
                        consumer.addVertex(pose.last().pose(), (float)v.x(), (float)v.y(), (float)v.z())
                           .setColor(255, 219, 85, 255)
                           .setNormal(pose.last(), (float)n.x(), (float)n.y(), (float)n.z());
                     }
                  }

                  pose.popPose();
                  buffers.endBatch(RenderType.lines());
               }
            }

            double elapsed = (double)(System.nanoTime() - frameStart) / 1000000.0;
            frameCpuMs = frameCpuMs == 0.0 ? elapsed : frameCpuMs * 0.9 + elapsed * 0.1;
         }
      }
   }

   public static String metrics() {
      return "sign states="
         + states.size()
         + " cache="
         + cache.size()
         + " bytes="
         + ((long)bytes + stateBytes)
         + " rebuilds="
         + rebuilds
         + " textLayouts="
         + textLayouts
         + " attempts="
         + bakeAttempts
         + " failed="
         + bakeFailures
         + " bakeMs="
         + bakeNanos / 1000000L
         + " anchors="
         + anchorBuilds
         + " gpuBytes="
         + gpuBytes
         + " gpuCount="
         + residentPlates
         + " streamed="
         + streamedPlates
         + " candidates="
         + frameCandidates
         + " drawn="
         + frameDrawn
         + " capacityCulled="
         + frameCulled
         + " candidateVisits="
         + frameCandidateVisits
         + " indexed="
         + candidateIndex.size()
         + " hotLimitMiB=64 coldLimitMiB=16 inflates="
         + RoadSignalBatch.inflations()
         + " enabled="
         + RoadSignVisibility.enabled()
         + " cpuMs="
         + String.format(Locale.ROOT, "%.2f", frameCpuMs);
   }

   private SmartSignClient() {
   }

   private static record Baked(
      SmartSigns.State state,
      RoadRecord road,
      SmartSigns.Layout layout,
      List<SmartSigns.Layout> views,
      RoadSignalBatch batch,
      RoadSignalBatch plate,
      List<SmartSignClient.Line> lines,
      AABB bounds,
      int geometryBytes,
      int bytes
   ) {
   }

   private static record Candidate(SmartSigns.State state, RoadRecord road, RoadGeometry.V anchor, AABB bounds, boolean valid) {
   }

   private static record Failed(SmartSigns.State state, RoadRecord road) {
   }

   private static record Line(Component text, int width, double x, double y, double scale, int color) {
   }

   private static record Weight(SmartSigns.State state, RoadRecord road, int bytes) {
   }
}
