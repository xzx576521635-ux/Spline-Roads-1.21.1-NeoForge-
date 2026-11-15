package com.sora.splineroads.world;

import com.sora.splineroads.core.ElevatedAccess;
import com.sora.splineroads.core.ElevatedSigns;
import com.sora.splineroads.core.ForkSignPlacement;
import com.sora.splineroads.core.InterchangePlanner;
import com.sora.splineroads.core.InterchangeSignForks;
import com.sora.splineroads.core.InterchangeSignPaths;
import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.LaneDeck;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadAutoTunnels;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRailJoin;
import com.sora.splineroads.core.RoadSolidOverlap;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.core.RoadsideBypass;
import com.sora.splineroads.core.SmartSignTemplates;
import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.net.RoadNetwork;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

public final class SmartSignRoads {
   public static final int MAX_VISITS = Math.max(16, Math.min(1024, Integer.getInteger("splineroads.signSearchSegments", 256)));
   public static final double MAX_DISTANCE = (double)Math.max(128, Math.min(16384, Integer.getInteger("splineroads.signSearchDistance", 4096)));
   private final RoadData data;
   private final Map<SmartSigns.Key, SmartSigns.State> states = new LinkedHashMap<>();
   private final Map<UUID, Set<SmartSigns.Key>> keysByRoad = new HashMap<>();
   private final Map<Long, Set<SmartSigns.Key>> supportChunks = new HashMap<>();
   private final Map<UUID, Long> versions = new HashMap<>();
   private final Map<SmartSigns.Key, SmartSignRoads.Presence> presentCache = new HashMap<>();
   public long workSteps;
   private final Map<UUID, RoadIndex.Built> known = new HashMap<>();
   private final Map<String, Set<UUID>> ports = new HashMap<>();
   private final Map<UUID, Set<UUID>> groups = new HashMap<>();
   private final Map<UUID, Set<UUID>> hosts = new HashMap<>();
   private final Map<UUID, List<SmartSignRoads.Exit>> exits = new HashMap<>();
   private final Map<UUID, Set<SmartSigns.Key>> dependencies = new HashMap<>();
   private final Map<SmartSigns.Key, Set<UUID>> searched = new HashMap<>();
   private final Set<UUID> dirty = new LinkedHashSet<>();
   private final Set<UUID> publish = new LinkedHashSet<>();
   public long searches;
   public long visited;
   public long layoutChanges;
   public long textEdits;
   private final Set<UUID> sightDirty = new LinkedHashSet<>();
   private RoadSignSightlines sightWork;
   private boolean applyingSight;
   private int maintenanceFamily;
   private long sightWaves;
   private long sightInvalidations;
   private long sightQueries;
   private long sightPartVisits;
   private long sightKeyBuilds;
   private long sightCommits;
   private SmartSignRoads.Work work;

   public SmartSignRoads(RoadData d) {
      this.data = d;
   }

   public void changed(UUID id) {
      this.dirty.add(id);
      if (!this.applyingSight) {
         this.sightDirty.add(id);
         if (this.sightWork != null) {
            this.sightWork.changed(id);
         }
      }

      HashSet<SmartSigns.Key> keys = new HashSet<>(this.keysByRoad.getOrDefault(id, Set.of()));
      keys.addAll(this.dependencies.getOrDefault(id, Set.of()));

      for (SmartSigns.Key key : keys) {
         SmartSigns.State s = this.states.get(key);
         if (s != null && SmartSigns.freestanding(s.mode()) && s.support() != null) {
            this.put(s.layout(s.mode(), s.target(), s.branches(), "标牌关联正在更新", s.entry()).supported(null));
         }
      }
   }

   private boolean compatible(RoadIndex.Built b, SmartSigns.State s) {
      if (b == null) {
         return false;
      } else if (s.mode() == SmartSigns.Mode.JUNCTION_ADVANCE || s.mode() == SmartSigns.Mode.ROAD_NAME) {
         JunctionPlanner.Ref j = b.record.junction();
         return j != null && j.get().arm() >= 0 && j.spec().arms().get(j.get().arm()).incoming() > 0;
      } else if (s.mode() == SmartSigns.Mode.ROADSIDE && this.elevated(b)) {
         return s.key().slot() == 0 && s.key().flow() == 1;
      } else {
         return s.mode() == SmartSigns.Mode.ROADSIDE
            ? s.key().flow() == 1
               && (
                  s.key().slot() == 0 && (LaneTopology.metadata(b.record).link() != null || b.record.assembly() != null && b.record.settings().style().ramp())
                     || s.key().slot() == 1 && b.record.assembly() != null && b.record.settings().style().ramp()
               )
            : this.main(b) && RoadGantry.exists(b.mesh, s.key().slot());
      }
   }

   SmartSignRoads.Snapshot snapshot() {
      return new SmartSignRoads.Snapshot(List.copyOf(this.states.values()), Map.copyOf(this.versions), Set.copyOf(this.sightDirty));
   }

   void restore(SmartSignRoads.Snapshot saved) {
      this.states.clear();
      this.keysByRoad.clear();
      this.supportChunks.clear();
      this.versions.clear();
      this.versions.putAll(saved.versions());
      this.presentCache.clear();
      this.known.clear();
      this.ports.clear();
      this.groups.clear();
      this.hosts.clear();
      this.exits.clear();
      this.dependencies.clear();
      this.searched.clear();
      this.dirty.clear();
      this.publish.clear();
      this.sightDirty.clear();
      this.work = null;
      this.sightDirty.addAll(saved.sightDirty());
      if (this.sightWork != null) {
         this.sightWork.invalidate();
         this.sightDirty.addAll(this.sightWork.requested());
      }

      for (SmartSigns.State state : saved.states()) {
         this.addState(state);
      }

      this.dirty.addAll(this.data.index.roads.keySet());
   }

   void splitKeys(UUID source, Map<Integer, UUID> owners) {
      for (SmartSigns.State state : new ArrayList<>(this.road(source))) {
         UUID target = owners.get(state.key().slot());
         if (target == null) {
            throw new IllegalArgumentException("分割后找不到原标牌的唯一所属龙门架");
         }

         if (!target.equals(source)) {
            this.removeState(state.key());
            this.addState(state.rekey(new SmartSigns.Key(target, state.key().slot(), state.key().flow())));
            this.versions.merge(target, 1L, Long::sum);
            this.publish.add(target);
         }
      }

      this.versions.merge(source, 1L, Long::sum);
      this.publish.add(source);
   }

   private void retire(SmartSigns.Key key) {
      this.removeState(key);
      this.versions.merge(key.road(), 1L, Long::sum);
      this.publish.add(key.road());
      this.data.setDirty();
   }

   private static String port(RoadGeometry.V p) {
      return Math.round(p.x() * 1000.0) + ":" + Math.round(p.y() * 1000.0) + ":" + Math.round(p.z() * 1000.0);
   }

   private boolean elevated(RoadIndex.Built b) {
      return b != null && b.record.id().equals(b.record.assembly()) && ElevatedAccessRoads.saved(this.data, b.record.id());
   }

   private boolean main(RoadIndex.Built b) {
      return b != null
         && !this.elevated(b)
         && b.record.toll() == null
         && b.record.junction() == null
         && !b.record.settings().style().ramp()
         && LaneTopology.metadata(b.record).link() == null;
   }

   private static void remove(Map<String, Set<UUID>> map, String key, UUID id) {
      Set<UUID> ids = map.get(key);
      if (ids != null) {
         ids.remove(id);
         if (ids.isEmpty()) {
            map.remove(key);
         }
      }
   }

   public boolean pending() {
      return this.work != null || !this.dirty.isEmpty();
   }

   public void refresh() {
      while (this.pending()) {
         this.advance(Long.MAX_VALUE, 4096);
      }
   }

   public int advance(long budgetNanos, int maxSteps) {
      long started = System.nanoTime();
      int done = 0;

      while (done < maxSteps && (done == 0 || System.nanoTime() - started < budgetNanos)) {
         if (this.work == null) {
            if (this.dirty.isEmpty()) {
               break;
            }

            this.work = new SmartSignRoads.Work(new LinkedHashSet<>(this.dirty));
            this.dirty.clear();
         }

         if (!this.work.step()) {
            this.work = null;
         }

         done++;
         this.workSteps++;
      }

      return done;
   }

   private static Set<Long> mountChunks(SmartSigns.Support mount) {
      if (mount == null) {
         return Set.of();
      } else {
         HashSet<Long> out = new HashSet<>();

         for (int x = (int)Math.floor(mount.foot().x() - 6.0) >> 4; x <= (int)Math.floor(mount.foot().x() + 6.0) >> 4; x++) {
            for (int z = (int)Math.floor(mount.foot().z() - 6.0) >> 4; z <= (int)Math.floor(mount.foot().z() + 6.0) >> 4; z++) {
               out.add(ChunkPos.asLong(x, z));
            }
         }

         return out;
      }
   }

   private void unindexSupport(SmartSigns.State state) {
      if (state != null) {
         for (long chunk : mountChunks(state.support())) {
            Set<SmartSigns.Key> keys = this.supportChunks.get(chunk);
            if (keys != null) {
               keys.remove(state.key());
               if (keys.isEmpty()) {
                  this.supportChunks.remove(chunk);
               }
            }
         }
      }
   }

   public List<SmartSigns.State> nearSupports(RoadGeometry.V eye) {
      LinkedHashSet<SmartSigns.Key> keys = new LinkedHashSet<>();

      for (int x = (int)Math.floor(eye.x() - 96.0) >> 4; x <= (int)Math.floor(eye.x() + 96.0) >> 4; x++) {
         for (int z = (int)Math.floor(eye.z() - 96.0) >> 4; z <= (int)Math.floor(eye.z() + 96.0) >> 4; z++) {
            keys.addAll(this.supportChunks.getOrDefault(ChunkPos.asLong(x, z), Set.of()));
         }
      }

      return keys.stream().map(this.states::get).filter(Objects::nonNull).toList();
   }

   private void addState(SmartSigns.State state) {
      SmartSigns.State old = this.states.get(state.key());
      if (old == null || !Objects.equals(old.support(), state.support())) {
         this.unindexSupport(old);

         for (long chunk : mountChunks(state.support())) {
            this.supportChunks.computeIfAbsent(chunk, k -> new LinkedHashSet<>()).add(state.key());
         }
      }

      this.states.put(state.key(), state);
      this.keysByRoad.computeIfAbsent(state.key().road(), id -> new LinkedHashSet<>()).add(state.key());
   }

   private void removeState(SmartSigns.Key key) {
      this.unindexSupport(this.states.get(key));
      this.states.remove(key);
      this.forget(key);
      this.presentCache.remove(key);
      Set<SmartSigns.Key> keys = this.keysByRoad.get(key.road());
      if (keys != null) {
         keys.remove(key);
         if (keys.isEmpty()) {
            this.keysByRoad.remove(key.road());
         }
      }
   }

   private void forget(SmartSigns.Key k) {
      Set<UUID> old = this.searched.remove(k);
      if (old != null) {
         for (UUID id : old) {
            Set<SmartSigns.Key> set = this.dependencies.get(id);
            if (set != null) {
               set.remove(k);
               if (set.isEmpty()) {
                  this.dependencies.remove(id);
               }
            }
         }
      }
   }

   private void depend(SmartSigns.Key k, Set<UUID> ids) {
      this.forget(k);
      this.searched.put(k, Set.copyOf(ids));

      for (UUID id : ids) {
         this.dependencies.computeIfAbsent(id, v -> new HashSet<>()).add(k);
      }
   }

   private void upstreamKeys(UUID id, Set<SmartSigns.Key> out) {
      for (int flow : new int[]{-1, 1}) {
         RoadIndex.Built b = this.known.get(id);
         if (this.main(b)) {
            SmartSignRoads.Position p = new SmartSignRoads.Position(id, flow, flow > 0 ? b.mesh.length() : 0.0);
            double distance = 0.0;
            HashSet<UUID> seen = new HashSet<>();

            while (p != null && seen.size() < MAX_VISITS && distance <= MAX_DISTANCE && seen.add(p.road())) {
               RoadIndex.Built at = this.known.get(p.road());

               for (int slot : RoadGantry.slots(at.mesh)) {
                  out.add(new SmartSigns.Key(p.road(), slot, p.flow()));
               }

               distance += at.mesh.length();

               try {
                  p = this.continuation(p, false);
               } catch (IllegalArgumentException var15) {
                  break;
               }
            }
         }
      }
   }

   private SmartSignRoads.Position continuation(SmartSignRoads.Position p, boolean forward) {
      RoadIndex.Built b = this.known.get(p.road());
      if (!this.main(b)) {
         return null;
      } else {
         boolean end = p.flow() > 0 == forward;
         RoadGeometry.Sample edge = end ? b.mesh.last() : b.mesh.first();
         RoadGeometry.V heading = edge.left().left().mul((double)(-p.flow()));
         ArrayList<SmartSignRoads.Position> options = new ArrayList<>();

         for (UUID id : this.ports.getOrDefault(port(edge.center()), Set.of())) {
            RoadIndex.Built other = this.known.get(id);
            if (!id.equals(p.road()) && this.main(other) && other.record.settings().options().leftTraffic() == b.record.settings().options().leftTraffic()) {
               for (boolean first : new boolean[]{true, false}) {
                  RoadGeometry.Sample at = first ? other.mesh.first() : other.mesh.last();
                  if (!(at.center().distance(edge.center()) > 1.0E-4)) {
                     int flow = first == forward ? 1 : -1;
                     if (!(at.left().left().mul((double)(-flow)).dot(heading) < 0.95) && LaneSections.live(other.mesh, at.distance()).count(flow) != 0) {
                        options.add(new SmartSignRoads.Position(id, flow, at.distance()));
                     }
                  }
               }
            }
         }

         if (options.size() > 1) {
            throw new IllegalArgumentException("主线续接存在歧义，请检查连接关系");
         } else {
            return options.isEmpty() ? null : options.get(0);
         }
      }
   }

   private SmartSignRoads.Found forward(SmartSigns.Key k, boolean assemblyOnly) {
      return this.work != null && this.work.phase >= 4
         ? (assemblyOnly ? this.work.assemblyQueries : this.work.anyQueries).computeIfAbsent(k, key -> this.findForward(key, assemblyOnly))
         : this.findForward(k, assemblyOnly);
   }

   private SmartSignRoads.Found findForward(SmartSigns.Key k, boolean assemblyOnly) {
      this.searches++;
      RoadIndex.Built b = this.known.get(k.road());
      LinkedHashSet<UUID> seen = new LinkedHashSet<>();
      if (this.main(b) && RoadGantry.exists(b.mesh, k.slot())) {
         SmartSignRoads.Position p = new SmartSignRoads.Position(k.road(), k.flow(), RoadGantry.station(b.mesh, k.slot()).distance());
         double distance = 0.0;

         try {
            while (p != null && seen.size() < MAX_VISITS && distance <= MAX_DISTANCE && seen.add(p.road())) {
               this.visited++;
               RoadIndex.Built at = this.known.get(p.road());
               SmartSignRoads.Position current = p;
               List<SmartSignRoads.Exit> found = this.exits
                  .getOrDefault(p.road(), List.of())
                  .stream()
                  .filter(
                     ex -> ex.flow() == current.flow()
                           && (ex.station() - current.station()) * (double)current.flow() > 1.0E-5
                           && (!assemblyOnly || this.generated(ex.assembly()))
                  )
                  .sorted(
                     Comparator.<SmartSignRoads.Exit>comparingDouble(ex -> (ex.station() - current.station()) * (double)current.flow())
                        .thenComparing(SmartSignRoads.Exit::id)
                  )
                  .toList();
               if (!found.isEmpty()) {
                  SmartSignRoads.Exit e = found.get(0);
                  if (found.size() <= 1
                     || !(Math.abs(found.get(1).station() - e.station()) < 0.01)
                     || e.assembly() != null && Objects.equals(found.get(1).assembly(), e.assembly())) {
                     return new SmartSignRoads.Found(e, distance + (e.station() - p.station()) * (double)p.flow(), "", seen);
                  }

                  return new SmartSignRoads.Found(null, distance, "同站位有多个出口，无法唯一确定目标", seen);
               }

               distance += p.flow() > 0 ? at.mesh.length() - p.station() : p.station();
               p = this.continuation(p, true);
            }
         } catch (IllegalArgumentException var12) {
            return new SmartSignRoads.Found(null, distance, var12.getMessage(), seen);
         }

         return new SmartSignRoads.Found(
            null,
            distance,
            p != null ? "搜索达到 " + (int)MAX_DISTANCE + " 格／" + MAX_VISITS + " 段上限" : (assemblyOnly ? "未找到前方连通立交；自由匝道缺少完整立交关联" : "未找到前方分流出口"),
            seen
         );
      } else {
         return new SmartSignRoads.Found(null, 0.0, "龙门架锚点失效", seen);
      }
   }

   private SmartSigns.Key nearest(SmartSignRoads.Exit exit, Set<UUID> deps) {
      UUID id = this.target(exit);
      if (this.work != null && this.work.owners.containsKey(id)) {
         SmartSignRoads.Closest found = this.work.owners.get(id);
         deps.addAll(found.dependencies());
         return found.key();
      } else {
         HashSet<UUID> read = new HashSet<>();
         SmartSigns.Key key = this.findNearest(exit, read);
         deps.addAll(read);
         if (this.work != null) {
            this.work.owners.put(id, new SmartSignRoads.Closest(key, Set.copyOf(read)));
         }

         return key;
      }
   }

   private SmartSigns.Key findNearest(SmartSignRoads.Exit exit, Set<UUID> deps) {
      SmartSignRoads.Position p = new SmartSignRoads.Position(exit.host(), exit.flow(), exit.station());
      double distance = 0.0;
      HashSet<UUID> seen = new HashSet<>();

      while (p != null && distance <= MAX_DISTANCE && seen.size() < MAX_VISITS && seen.add(p.road())) {
         deps.add(p.road());
         RoadIndex.Built b = this.known.get(p.road());
         SmartSignRoads.Position pos = p;
         ArrayList<SmartSigns.Key> candidates = new ArrayList<>();

         for (int slot : RoadGantry.slots(b.mesh)) {
            SmartSigns.Key key = new SmartSigns.Key(p.road(), slot, p.flow());
            RoadGantry.Station st = RoadGantry.station(b.mesh, slot);
            if ((p.station() - st.distance()) * (double)p.flow() > 1.0E-5
               && this.isPresent(key, b, st)
               && (
                  this.states.getOrDefault(key, SmartSigns.State.empty(key)).mode() == SmartSigns.Mode.NORMAL
                     || this.states.getOrDefault(key, SmartSigns.State.empty(key)).mode() == SmartSigns.Mode.EXIT_AUTO
                     || this.states.getOrDefault(key, SmartSigns.State.empty(key)).mode() == SmartSigns.Mode.EXIT_SIGN
               )) {
               candidates.add(key);
            }
         }

         candidates.sort(Comparator.comparingDouble(kx -> (pos.station() - RoadGantry.station(b.mesh, kx.slot()).distance()) * (double)pos.flow()));

         for (SmartSigns.Key k : candidates) {
            SmartSignRoads.Found first = this.forward(k, false);
            deps.addAll(first.visited());
            if (first.exit() != null && this.target(first.exit()).equals(this.target(exit))) {
               return k;
            }
         }

         distance += p.flow() > 0 ? p.station() : b.mesh.length() - p.station();
         p = this.continuation(p, false);
      }

      return null;
   }

   public static boolean present(RoadIndex.Built b, RoadGantry.Station st) {
      if (st.kind() == RoadInfrastructure.Gantry.OFF) {
         return false;
      } else {
         RoadGeometry.V center = st.sample().center();
         double beam = RoadGantry.lowerBeamY(st);
         return b.record
            .structures()
            .stream()
            .anyMatch(
               p -> !p.pier()
                     && (p.material() == RoadStructures.Material.STEEL || p.material() == RoadStructures.Material.GANTRY_FRAME)
                     && p.a().sub(p.b()).horizontalLength() > st.sample().halfWidth() * 1.8
                     && Math.abs(p.a().y() - beam) < 0.2
                     && Math.abs(p.a().add(p.b()).mul(0.5).sub(center).dot(st.sample().left().left())) < 0.2
            );
      }
   }

   private boolean isPresent(SmartSigns.Key k, RoadIndex.Built b, RoadGantry.Station st) {
      if (st.kind() == RoadInfrastructure.Gantry.OFF) {
         return false;
      } else {
         SmartSignRoads.Presence cached = this.presentCache.get(k);
         if (cached != null && cached.road() == b) {
            return cached.value();
         } else {
            boolean result = present(b, st);
            this.presentCache.put(k, new SmartSignRoads.Presence(b, result));
            return result;
         }
      }
   }

   private void reconcile(SmartSigns.Key k) {
      SmartSigns.State old = this.states.get(k);
      RoadIndex.Built b = this.known.get(k.road());
      if (this.main(b) && RoadGantry.exists(b.mesh, k.slot())) {
         SmartSigns.Mode inactive = old.mode() == SmartSigns.Mode.EXIT_AUTO ? SmartSigns.Mode.NORMAL : old.mode();
         HashSet<UUID> deps = new HashSet<>();
         deps.add(k.road());

         SmartSigns.State next;
         try {
            RoadGantry.Station station = RoadGantry.station(b.mesh, k.slot());
            if (old.mode() == SmartSigns.Mode.EMPTY) {
               next = old.layout(SmartSigns.Mode.EMPTY, null, List.of(), "");
            } else if (!this.isPresent(k, b, station)) {
               next = old.layout(inactive, null, List.of(), "此架位未建成或安装空间受阻");
            } else if (old.mode() != SmartSigns.Mode.SPEED_LIMIT && old.mode() != SmartSigns.Mode.LANE_INDICATOR) {
               SmartSignRoads.Found lookup = this.forward(k, old.mode() == SmartSigns.Mode.IC_JCT);
               deps.addAll(lookup.visited());
               if (lookup.exit() == null) {
                  next = old.layout(inactive, null, List.of(), lookup.reason());
               } else if (old.mode() == SmartSigns.Mode.IC_JCT) {
                  SmartSignRoads.Exit e = lookup.exit();
                  List<SmartSigns.Branch> branches = this.diagram(e, k, deps);
                  next = old.layout(
                     SmartSigns.Mode.IC_JCT, e.assembly(), branches, branches.size() < 2 ? "缺少完整且可达的立交关联" : "", this.source(e.host()) + ":" + e.flow()
                  );
               } else if (old.mode() == SmartSigns.Mode.EXIT_SIGN) {
                  SmartSignRoads.Exit e = lookup.exit();
                  next = old.layout(SmartSigns.Mode.EXIT_SIGN, this.target(e), this.exitBranches(e, k), "");
               } else {
                  SmartSignRoads.Exit e = lookup.exit();
                  SmartSigns.Key closest = this.nearest(e, deps);
                  next = k.equals(closest)
                     ? old.layout(SmartSigns.Mode.EXIT_AUTO, this.target(e), this.exitBranches(e, k), "")
                     : old.layout(inactive, null, List.of(), closest == null ? "未找到可用的上游龙门架" : "由更近的上游龙门架预告");
               }
            } else {
               List<SmartSigns.Branch> lanes = LaneSections.live(b.mesh, station.distance())
                  .lanes()
                  .stream()
                  .filter(l -> l.sign() == k.flow())
                  .map(l -> new SmartSigns.Branch("lane:" + l.index(), List.of(l.index()), 0, true, List.of()))
                  .toList();
               next = old.layout(old.mode(), new SmartSigns.Key(this.source(k.road()), k.slot(), k.flow()).id(), lanes, lanes.isEmpty() ? "该方向没有可服务的车道" : "");
            }
         } catch (IllegalArgumentException var11) {
            next = old.layout(inactive, null, List.of(), var11.getMessage());
         }

         if (next.target() != null && next.diagnostic().isEmpty() && this.blocked(b, next, deps)) {
            next = old.layout(inactive, null, List.of(), "标牌安装空间与其他道路净空冲突");
         }

         this.depend(k, deps);
         this.put(next);
      }
   }

   private boolean blocked(RoadIndex.Built built, SmartSigns.State next, Set<UUID> deps) {
      SmartSigns.Layout layout = SmartSigns.layout(built.mesh, next);
      boolean blocked = false;
      HashSet<UUID> nearby = new HashSet<>();
      List<RoadStructures.Part> mounts = SmartSigns.mountParts(built.mesh, next);

      for (RoadStructures.Part part : mounts) {
         List<RoadGeometry.V> base = part.base();

         for (int x = (int)Math.floor(base.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow() - 0.1) >> 4;
            x <= (int)Math.floor(base.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow() + 0.1) >> 4;
            x++
         ) {
            for (int z = (int)Math.floor(base.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow() - 0.1) >> 4;
               z <= (int)Math.floor(base.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow() + 0.1) >> 4;
               z++
            ) {
               nearby.addAll(this.data.index.inChunk(ChunkPos.asLong(x, z)));
            }
         }
      }

      for (SmartSigns.Board board : layout.boards()) {
         RoadGeometry.V a = layout.at(board.x() - board.width() / 2.0, 0.0, 0.1);
         RoadGeometry.V z = layout.at(board.x() + board.width() / 2.0, 0.0, 0.45);

         for (int x = (int)Math.floor(Math.min(a.x(), z.x()) - 0.5) >> 4; x <= (int)Math.floor(Math.max(a.x(), z.x()) + 0.5) >> 4; x++) {
            for (int y = (int)Math.floor(Math.min(a.z(), z.z()) - 0.5) >> 4; y <= (int)Math.floor(Math.max(a.z(), z.z()) + 0.5) >> 4; y++) {
               nearby.addAll(this.data.index.inChunk(ChunkPos.asLong(x, y)));
            }
         }
      }

      for (UUID id : nearby) {
         RoadIndex.Built other = this.data.index.roads.get(id);
         if (other != null) {
            deps.add(id);
            if (!mounts.isEmpty()) {
               RoadSolidOverlap.Index solids = new RoadSolidOverlap.Index(other.record.structures());

               for (RoadStructures.Part part : mounts) {
                  if (solids.intersects(part)
                     && other.record
                        .structures()
                        .stream()
                        .anyMatch(p -> !ForkSignPlacement.mountingContact(part, p, next.support()) && RoadSolidOverlap.intersects(part, p))) {
                     return true;
                  }
               }
            }

            if (!id.equals(built.record.id())) {
               for (SmartSigns.Board board : layout.boards()) {
                  for (double x = board.x() - board.width() / 2.0; x <= board.x() + board.width() / 2.0 + 0.01 && !blocked; x += 0.7) {
                     for (double depth : new double[]{0.1, 0.45}) {
                        RoadGeometry.V point = layout.at(x, board.bottom(), depth);
                        RoadQueries.Projection q = RoadQueries.horizontal(other.mesh, point);
                        double deck = q.sample().center().y();
                        if (!(point.y() > deck + 4.5)
                           && !(point.y() + board.height() < deck - other.mesh.settings().thickness())
                           && RoadQueries.contains(other.mesh, new RoadGeometry.V(point.x(), deck, point.z()), 0.05, 0.01)) {
                           blocked = true;
                        }
                     }
                  }
               }

               if (blocked) {
                  break;
               }
            }
         }
      }

      return blocked;
   }

   private void put(SmartSigns.State next) {
      SmartSigns.State old = this.states.get(next.key());
      this.addState(next);
      if (!Objects.equals(old, next)) {
         this.layoutChanges++;
         this.versions.merge(next.key().road(), 1L, Long::sum);
         this.data.setDirty();
         this.publish.add(next.key().road());
      }
   }

   private UUID source(UUID id) {
      RoadIndex.Built r = this.known.get(id);
      return r != null && r.record.fragment() != null ? r.record.fragment().source() : id;
   }

   private UUID target(SmartSignRoads.Exit e) {
      RoadIndex.Built host = this.known.get(e.host());
      double origin = host != null && host.record.fragment() != null ? host.record.fragment().origin() : 0.0;
      return e.assembly() == null
         ? e.id()
         : UUID.nameUUIDFromBytes(
            (e.assembly() + ":" + this.source(e.host()) + ":" + e.flow() + ":" + Math.round((origin + e.station()) * 10.0)).getBytes(StandardCharsets.UTF_8)
         );
   }

   private List<SmartSigns.Branch> exitBranches(SmartSignRoads.Exit e, SmartSigns.Key k) {
      List<SmartSignRoads.Exit> bundle = this.exits.getOrDefault(e.host(), List.of()).stream().filter(v -> this.target(v).equals(this.target(e))).toList();
      if (bundle.stream().map(SmartSignRoads.Exit::side).distinct().count() > 1L) {
         throw new IllegalArgumentException("同位分流涉及两侧，请使用 IC/JCT 结构预告");
      } else {
         HashSet<Integer> branchLanes = new HashSet<>();

         for (SmartSignRoads.Exit exit : bundle) {
            branchLanes.addAll(exit.lanes());
         }

         RoadIndex.Built b = this.known.get(k.road());
         List<Integer> live = LaneSections.live(b.mesh, RoadGantry.station(b.mesh, k.slot()).distance())
            .lanes()
            .stream()
            .filter(l -> l.sign() == k.flow())
            .map(LanePoints.Lane::index)
            .toList();
         RoadIndex.Built targetRoad = this.known.get(e.host());
         List<LanePoints.Lane> targetLanes = LaneSections.live(targetRoad.mesh, e.station())
            .lanes()
            .stream()
            .filter(l -> l.sign() == e.flow())
            .sorted(
               Comparator.comparingDouble(
                  l -> l.position()
                           .sub(RoadStructures.sample(targetRoad.mesh, e.station()).center())
                           .dot(RoadStructures.sample(targetRoad.mesh, e.station()).left())
                        * (double)e.flow()
               )
            )
            .toList();
         List<LanePoints.Lane> sourceLanes = LaneSections.live(b.mesh, RoadGantry.station(b.mesh, k.slot()).distance())
            .lanes()
            .stream()
            .filter(l -> l.sign() == k.flow())
            .sorted(
               Comparator.comparingDouble(
                  l -> l.position().sub(RoadGantry.station(b.mesh, k.slot()).sample().center()).dot(RoadGantry.station(b.mesh, k.slot()).sample().left())
                        * (double)k.flow()
               )
            )
            .toList();
         List<Integer> lane = SmartSigns.exitLaneMapping(
            sourceLanes.stream().map(LanePoints.Lane::index).toList(), targetLanes.stream().map(LanePoints.Lane::index).toList(), branchLanes, e.side()
         );
         if (lane.isEmpty()) {
            throw new IllegalArgumentException("出口车道与本架服务车道无法关联");
         } else {
            ArrayList<Integer> through = new ArrayList<>(live);
            through.removeAll(lane);
            ArrayList<SmartSigns.Branch> out = new ArrayList<>();
            if (!through.isEmpty()) {
               out.add(new SmartSigns.Branch("through", through, 0, true, List.of()));
            }

            out.add(new SmartSigns.Branch(this.target(e).toString(), lane, e.side(), e.dedicated(), List.of()));
            return out;
         }
      }
   }

   private List<SmartSignRoads.Exit> derive(RoadIndex.Built ramp) {
      RoadRecord r = ramp.record;
      LanePoints.Link link = LaneTopology.metadata(r).link();
      if (link != null) {
         RoadIndex.Built host = this.known.get(link.from().road());
         if (host != null && host.record.junction() == null && host.record.toll() == null) {
            try {
               LanePoints.Point point = LaneTopology.point(host.record, link.from().point());
               LanePoints.Lane lane = LanePoints.lane(host.mesh, point);
               double station = lane.station();

               for (LaneSections.Cut cut : LaneTopology.metadata(host.record).cuts()) {
                  if (cut.connection().equals(r.id()) && !cut.arrival()) {
                     station = cut.begin();
                  }
               }

               int side = departureSide(host.mesh, ramp.mesh, station, lane.sign(), lane.index());
               return List.of(
                  new SmartSignRoads.Exit(
                     r.id(),
                     host.record.id(),
                     lane.sign(),
                     station,
                     List.of(lane.index()),
                     side,
                     link.options().departure() == LanePoints.Departure.DETACH || link.options().departure() == LanePoints.Departure.TEMPORARY,
                     r.assembly(),
                     path(ramp.mesh)
                  )
               );
            } catch (IllegalArgumentException var23) {
               return List.of();
            }
         } else {
            return List.of();
         }
      } else if (r.assembly() != null && r.settings().style().ramp()) {
         CompoundTag descriptor = this.data.interchanges.get(r.assembly());
         HashMap<UUID, CompoundTag> topology = new HashMap<>();
         if (descriptor != null) {
            for (Tag value : descriptor.getList("SignTopology", 10)) {
               CompoundTag leg = (CompoundTag)value;
               if (leg.hasUUID("Road")) {
                  topology.put(leg.getUUID("Road"), leg);
               }
            }
         }

         CompoundTag source = topology.get(r.id());
         if (isWeavingCollector(descriptor, source)) {
            return List.of();
         } else if (source == null
            || source.getInt("From") >= 0
            || descriptor != null && descriptor.contains("Corridor")
            || source.getInt("To") >= 0 && descriptor != null && descriptor.getCompound("Options").getString("Preset").equals("DOUBLE_TRUMPET")) {
            if (source != null && (descriptor == null || !descriptor.contains("Corridor"))) {
               for (Entry<UUID, CompoundTag> item : topology.entrySet()) {
                  CompoundTag parent = item.getValue();
                  RoadIndex.Built built = this.known.get(item.getKey());
                  if (built != null
                     && !item.getKey().equals(r.id())
                     && parent.getInt("To") == -1
                     && parent.getInt("From") == source.getInt("From")
                     && built.mesh.last().center().distance(ramp.mesh.first().center()) < 0.35
                     && built.mesh.last().left().dot(ramp.mesh.first().left()) > 0.9) {
                     return List.of();
                  }
               }
            }

            ArrayList<SmartSignRoads.Exit> matches = new ArrayList<>();

            for (UUID id : this.groups.getOrDefault(r.assembly(), Set.of())) {
               RoadIndex.Built host = this.known.get(id);
               if (this.main(host)) {
                  RoadQueries.Projection q = RoadQueries.horizontal(host.mesh, ramp.mesh.first().center());
                  double d = q.sample().distance();
                  if (ForkSignPlacement.onPath(host.mesh, ramp.mesh.first().center(), 0.35)) {
                     CompoundTag mainLeg = topology.get(id);
                     boolean sameApproach = descriptor != null && descriptor.contains("Corridor")
                        || source != null
                           && mainLeg != null
                           && (source.getInt("From") == mainLeg.getInt("From") || source.getInt("From") == mainLeg.getInt("To"));
                     double reach = q.sample().halfWidth() + 0.1 + (sameApproach ? ramp.mesh.first().halfWidth() : 0.0);
                     if (!(Math.abs(q.lateral()) > reach) && !(Math.abs(q.sample().center().y() - ramp.mesh.first().center().y()) > 0.3)) {
                        RoadQueries.Projection end = RoadQueries.horizontal(host.mesh, ramp.mesh.last().center());
                        boolean parallel = Math.abs(end.lateral() - q.lateral()) < 0.01
                           && Math.abs(end.sample().center().y() - ramp.mesh.last().center().y()) < 0.3
                           && ramp.mesh.first().left().dot(ramp.mesh.last().left()) > 0.9999
                           && ramp.mesh.last().center().sub(ramp.mesh.first().center()).horizontalUnit().dot(ramp.mesh.first().left().left().mul(-1.0))
                              > 0.9999;
                        if (!parallel) {
                           List<LanePoints.Lane> lanes = LaneSections.live(host.mesh, d).lanes();
                           LanePoints.Lane lane = lanes.stream()
                              .filter(l -> l.direction().dot(ramp.mesh.first().left().left().mul(-1.0)) > 0.9)
                              .min(Comparator.comparingDouble(l -> l.position().distance(ramp.mesh.first().center())))
                              .orElse(null);
                           if (lane != null) {
                              List<Integer> connected = lanes.stream()
                                 .filter(
                                    l -> l.sign() == lane.sign()
                                          && l.position().sub(ramp.mesh.first().center()).horizontalLength()
                                             <= ramp.mesh.first().halfWidth() + l.width() * 0.45
                                 )
                                 .map(LanePoints.Lane::index)
                                 .toList();
                              if (connected.isEmpty() && sameApproach) {
                                 connected = List.of(lane.index());
                              }

                              matches.add(
                                 new SmartSignRoads.Exit(
                                    r.id(),
                                    id,
                                    lane.sign(),
                                    d,
                                    connected,
                                    departureSide(host.mesh, ramp.mesh, d, lane.sign(), lane.index()),
                                    false,
                                    r.assembly(),
                                    path(ramp.mesh)
                                 )
                              );
                           }
                        }
                     }
                  }
               }
            }

            return (List<SmartSignRoads.Exit>)(matches.size() == 1 ? matches : List.of());
         } else if (source.getInt("To") >= 0 && descriptor != null && descriptor.getCompound("Options").getString("Preset").equals("ROUNDABOUT")) {
            ArrayList<SmartSignRoads.Exit> matches = new ArrayList<>();

            for (UUID idx : this.groups.getOrDefault(r.assembly(), Set.of())) {
               RoadIndex.Built ring = this.known.get(idx);
               if (ring != null && ring.mesh.closed()) {
                  RoadQueries.Projection q = RoadQueries.horizontal(ring.mesh, ramp.mesh.first().center());
                  if (!(q.sample().center().distance(ramp.mesh.first().center()) > 0.4)) {
                     List<LanePoints.Lane> lanes = LaneSections.live(ring.mesh, q.sample().distance()).lanes();
                     LanePoints.Lane lane = lanes.stream()
                        .filter(l -> l.direction().dot(ramp.mesh.first().left().left().mul(-1.0)) > 0.9)
                        .findFirst()
                        .orElse(null);
                     if (lane != null) {
                        matches.add(
                           new SmartSignRoads.Exit(
                              r.id(),
                              idx,
                              lane.sign(),
                              q.sample().distance(),
                              List.of(lane.index()),
                              departureSide(ring.mesh, ramp.mesh, q.sample().distance(), lane.sign(), lane.index()),
                              false,
                              r.assembly(),
                              path(ramp.mesh)
                           )
                        );
                     }
                  }
               }
            }

            return (List<SmartSignRoads.Exit>)(matches.size() == 1 ? matches : List.of());
         } else {
            return List.of();
         }
      } else {
         return List.of();
      }
   }

   private static boolean isWeavingCollector(CompoundTag descriptor, CompoundTag leg) {
      if (descriptor != null && leg != null && leg.getBoolean("Ramp")) {
         String preset = descriptor.getCompound("Options").getString("Preset");
         int from = leg.getInt("From");
         int to = leg.getInt("To");
         return (preset.equals("HYBRID") || preset.equals("CLOVERLEAF")) && from >= 0 && from < 4 && to == (from + 2) % 4;
      } else {
         return false;
      }
   }

   private boolean generated(UUID id) {
      if (id == null) {
         return false;
      } else {
         CompoundTag descriptor = this.data.interchanges.get(id);
         if (descriptor != null && !descriptor.contains("Corridor")) {
            int n = descriptor.getList("Nodes", 10).size();
            return n >= 3 && n <= 6;
         } else {
            return false;
         }
      }
   }

   private boolean throughBeyond(RoadIndex.Built host, LanePoints.Link link) {
      LanePoints.Point point = LaneTopology.point(host.record, link.from().point());
      LanePoints.Lane lane = LanePoints.lane(host.mesh, point);
      double remaining = lane.sign() > 0 ? host.mesh.length() - lane.station() : lane.station();
      if (remaining > 0.05) {
         double station = lane.station() + (double)lane.sign() * Math.min(0.5, remaining);
         return LaneSections.live(host.mesh, station).lanes().stream().anyMatch(l -> l.index() == lane.index() && l.sign() == lane.sign());
      } else {
         try {
            return this.continuation(new SmartSignRoads.Position(host.record.id(), lane.sign(), lane.station()), true) != null;
         } catch (IllegalArgumentException var9) {
            return false;
         }
      }
   }

   private void elevatedSign(RoadIndex.Built connector) {
      UUID id = connector.record.id();
      CompoundTag receipt = this.data.interchanges.get(id);
      RoadIndex.Built host = this.data.index.roads.get(receipt.getUUID("Main"));
      SmartSigns.Key key = new SmartSigns.Key(id, 0, 1);
      SmartSigns.State old = this.peek(key);
      if (host == null) {
         this.retire(key);
      } else {
         ElevatedAccess.Reservation access = LaneTopology.metadata(host.record)
            .access()
            .stream()
            .filter(a -> a.connection().equals(id))
            .findFirst()
            .orElse(null);
         if (access == null) {
            this.retire(key);
         } else {
            LaneSections.Cut exit = access.cuts().stream().filter(c -> !c.arrival()).findFirst().orElse(null);
            if (exit == null) {
               this.retire(key);
            } else {
               List<SmartSigns.Branch> branches = ElevatedSigns.destinations(host.mesh, access, exit.sign());
               SmartSigns.Support mount = ElevatedSigns.saved(host.mesh, access, host.record.structures());
               HashSet<UUID> deps = new HashSet<>();
               deps.add(id);
               deps.add(host.record.id());

               for (Tag tag : receipt.getList("Hosts", 11)) {
                  UUID member = NbtUtils.loadUUID(tag);
                  if (deps.add(member) && mount == null) {
                     RoadIndex.Built next = this.known.get(member);
                     if (next != null) {
                        ElevatedAccess.Reservation local = LaneTopology.metadata(next.record)
                           .access()
                           .stream()
                           .filter(a -> a.connection().equals(id))
                           .findFirst()
                           .orElse(null);
                        if (local != null) {
                           mount = ElevatedSigns.saved(next.mesh, local, next.record.structures());
                        }
                     }
                  }
               }

               SmartSigns.State state = old.layout(SmartSigns.Mode.ROADSIDE, id, branches, mount == null ? "请编辑并保存出入口以设置路牌基座" : "").supported(mount);
               if (mount != null && this.blocked(connector, state, deps)) {
                  state = state.layout(SmartSigns.Mode.ROADSIDE, id, branches, "高架入口标牌与其他道路空间冲突").supported(null);
               }

               if (old.branches().isEmpty()) {
                  state = state.edit("elevated/main", new SmartSigns.Text("高架", 1, true));
                  if (branches.size() == 2) {
                     state = state.edit("surface/main", new SmartSigns.Text("地面辅路", 1, true));
                  }
               }

               this.put(state);
               this.depend(key, deps);
            }
         }
      }
   }

   private void roadside(RoadIndex.Built ramp, List<SmartSignRoads.Exit> derived) {
      SmartSigns.Key key = new SmartSigns.Key(ramp.record.id(), 0, 1);
      SmartSigns.State old = this.states.getOrDefault(key, SmartSigns.State.empty(key));
      List<RoadIndex.Built> shared = this.sharedForkChildren(ramp);
      if (shared.size() == 2 && derived.size() == 1) {
         this.sharedDeparture(ramp, derived.get(0));
      } else {
         SmartSigns.Key departure = new SmartSigns.Key(ramp.record.id(), 1, 1);
         if (this.states.containsKey(departure) && this.states.get(departure).mode() == SmartSigns.Mode.ROADSIDE) {
            this.retire(departure);
         }
      }

      CompoundTag descriptor = this.data.interchanges.get(ramp.record.assembly());
      if (descriptor != null
         && !descriptor.contains("Corridor")
         && descriptor.getList("Nodes", 10).size() >= 5
         && this.multiForks(ramp.record.assembly()).containsKey(ramp.record.id().toString())) {
         this.multiRoadside(ramp, old);
      } else if (derived.size() != 1) {
         if (old.mode() == SmartSigns.Mode.ROADSIDE) {
            this.put(old.layout(SmartSigns.Mode.ROADSIDE, null, List.of(), "分流关联已失效").supported(null));
         }
      } else {
         SmartSignRoads.Exit e = derived.get(0);
         RoadIndex.Built host = this.known.get(e.host());
         if (host != null) {
            LanePoints.Link link = LaneTopology.metadata(ramp.record).link();
            boolean paired = link != null && link.options().departure() == LanePoints.Departure.BRANCH && this.throughBeyond(host, link);
            List<RoadIndex.Built> terminal = this.terminalFork(ramp);
            if (terminal.size() == 2 && !terminal.get(0).record.id().equals(ramp.record.id())) {
               if (old.mode() == SmartSigns.Mode.ROADSIDE) {
                  this.put(old.layout(SmartSigns.Mode.ROADSIDE, ramp.record.id(), List.of(), "已合并到同一分叉路牌").supported(null));
               }
            } else {
               List<RoadIndex.Built> split = terminal.size() == 2 ? terminal : shared;
               List<RoadStructures.Part> rails = Stream.concat(ramp.record.structures().stream(), host.record.structures().stream()).toList();
               SmartSigns.Support mount = split.size() == 2
                  ? sharedForkMount(ramp, split, terminal.size() == 2 ? ramp.mesh.first() : ramp.mesh.last())
                  : (
                     link != null
                        ? ForkSignPlacement.findConnected(
                           ramp.mesh,
                           host.mesh,
                           rails,
                           new ForkSignPlacement.Mouth(ramp.record.id(), link.options().departure(), e.lanes().get(0), e.flow(), e.station()),
                           paired
                        )
                        : ForkSignPlacement.find(ramp.mesh, host.mesh, rails, e.flow(), paired)
                  );
               if (mount == null && split.size() != 2 && this.generated(e.assembly())) {
                  ArrayDeque<UUID> queue = new ArrayDeque<>();
                  HashSet<UUID> seen = new HashSet<>();
                  queue.add(ramp.record.id());

                  while (mount == null && !queue.isEmpty() && seen.size() < 16) {
                     UUID id = queue.remove();
                     if (seen.add(id)) {
                        RoadIndex.Built current = this.known.get(id);
                        if (current != null) {
                           if (!id.equals(ramp.record.id())) {
                              mount = ForkSignPlacement.find(current.mesh, host.mesh, current.record.structures(), e.flow(), false);
                           }

                           for (UUID next : new TreeSet<>(this.groups.getOrDefault(e.assembly(), Set.of()))) {
                              RoadIndex.Built n = this.known.get(next);
                              if (n != null && !this.main(n) && !seen.contains(next) && current.mesh.last().center().distance(n.mesh.first().center()) < 0.35) {
                                 queue.add(next);
                              }
                           }
                        }
                     }
                  }
               }

               SmartSigns.Support preferred = split.size() == 2 ? null : this.preferredNose(ramp, host, e, link, paired);
               SmartSigns.Support nose = preferred != null ? preferred : mount;
               mount = outsideRoof(mount, ramp, host);
               ArrayList<SmartSigns.Branch> branches = new ArrayList<>();
               if (paired) {
                  branches.add(new SmartSigns.Branch("through", List.of(), 0, true, List.of()));
               }

               List<SmartSigns.Branch> movements = this.generated(e.assembly()) ? this.roadsideMovements(e) : List.of();
               if (terminal.size() == 2) {
                  TreeMap<String, SmartSigns.Branch> combined = new TreeMap<>();

                  for (RoadIndex.Built child : terminal) {
                     for (SmartSignRoads.Exit exit : this.derive(child)) {
                        for (SmartSigns.Branch branch : this.roadsideMovements(exit)) {
                           combined.put(branch.id(), branch);
                        }
                     }
                  }

                  movements = combined.values().stream().sorted(Comparator.comparingInt(SmartSigns.Branch::side).thenComparing(SmartSigns.Branch::id)).toList();
               }

               if (movements.size() > 1) {
                  branches.addAll(movements);
               } else {
                  branches.add(new SmartSigns.Branch("ramp", List.of(), e.side(), true, List.of()));
               }

               if (mount != null && branches.size() > 1) {
                  mount = new SmartSigns.Support(mount.foot(), mount.right(), mount.front(), true);
               }

               SmartSigns.State nextx = old.layout(SmartSigns.Mode.ROADSIDE, ramp.record.id(), branches, mount == null ? "未找到可安装的分流鼻端护栏" : "").supported(mount);
               HashSet<UUID> deps = new HashSet<>();
               deps.add(e.host());
               deps.add(ramp.record.id());

               for (RoadIndex.Built child : split) {
                  deps.add(child.record.id());
               }

               if (mount != null && this.blocked(ramp, nextx, deps)) {
                  nextx = old.layout(SmartSigns.Mode.ROADSIDE, ramp.record.id(), branches, "标牌安装空间与其他道路净空冲突").supported(mount);
               }

               if (this.preferredBlocked(ramp, host, preferred, mount, nextx, deps)) {
                  nextx = nextx.layout(SmartSigns.Mode.ROADSIDE, ramp.record.id(), branches, "原鼻端净空不足，需提前设置告示牌");
               }

               if ((split.size() != 2 || terminal.size() == 2) && !nextx.diagnostic().isEmpty()) {
                  nextx = this.approachFallback(ramp, host, e, nose, nextx, deps);
               }

               if (terminal.size() == 2 && (old.branches().isEmpty() || old.branches().stream().anyMatch(b -> b.id().equals("ramp")))) {
                  nextx = this.terminalText(nextx, terminal);
               }

               this.put(nextx);
               this.depend(key, deps);
            }
         }
      }
   }

   private static SmartSigns.Support outsideRoof(SmartSigns.Support mount, RoadIndex.Built... roads) {
      if (mount == null) {
         return null;
      } else {
         for (RoadIndex.Built road : roads) {
            RoadQueries.Projection q = RoadQueries.horizontal(road.mesh, mount.foot());
            double d = q.sample().distance();
            if (!(q.horizontalDistance() > q.sample().halfWidth() + 1.55 + 1.0)
               && (
                  road.mesh.settings().structure() == RoadGeometry.Structure.TUNNEL
                     || RoadAutoTunnels.regions(road.record.structures()).stream().anyMatch(r -> d >= r.from() - 0.1 && d <= r.to() + 0.1)
               )) {
               return null;
            }
         }

         return mount;
      }
   }

   private SmartSigns.Support preferredNose(RoadIndex.Built ramp, RoadIndex.Built host, SmartSignRoads.Exit exit, LanePoints.Link link, boolean paired) {
      ArrayList<RoadStructures.Part> rails = new ArrayList<>();

      for (RoadIndex.Built owner : List.of(ramp, host)) {
         final RoadRailJoin join = new RoadRailJoin(
            owner.mesh,
            this.known
               .values()
               .stream()
               .filter(r -> !r.record.id().equals(owner.record.id()) && RoadIndex.overlapXZ(owner.mesh, r.mesh, 4.0))
               .map(r -> new RoadRailJoin.Neighbor(r.mesh, RoadSurface.higherPriority(r.record.id(), r.mesh, owner.record.id(), owner.mesh)))
               .toList()
         );
         rails.addAll(RoadStructures.roadsideSupports(owner.mesh, new RoadStructures.Ground() {
            @Override
            public double top(double x, double z, double y) {
               return Double.NaN;
            }

            @Override
            public boolean joined(RoadGeometry.V p) {
               return false;
            }

            @Override
            public boolean blocked(RoadStructures.Part p) {
               return false;
            }

            @Override
            public boolean unionRails() {
               return true;
            }

            @Override
            public List<RoadRailJoin.Span> railSpans(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V offset, double inset) {
               return join.exposed(a, b, offset, inset);
            }
         }));
         rails.addAll(
            owner.record
               .structures()
               .stream()
               .filter(
                  p -> p.material() == RoadStructures.Material.GREEN
                        || p.material() == RoadStructures.Material.SOIL
                        || p.material() == RoadStructures.Material.CONCRETE
               )
               .toList()
         );
      }

      return link == null
         ? ForkSignPlacement.find(ramp.mesh, host.mesh, rails, exit.flow(), paired)
         : ForkSignPlacement.findConnected(
            ramp.mesh,
            host.mesh,
            rails,
            new ForkSignPlacement.Mouth(ramp.record.id(), link.options().departure(), exit.lanes().get(0), exit.flow(), exit.station()),
            paired
         );
   }

   private boolean preferredBlocked(
      RoadIndex.Built ramp, RoadIndex.Built host, SmartSigns.Support preferred, SmartSigns.Support actual, SmartSigns.State state, Set<UUID> deps
   ) {
      if (preferred == null) {
         return false;
      } else if (actual != null && actual.foot().distance(preferred.foot()) < 0.15) {
         return false;
      } else {
         SmartSigns.State probe = state.layout(state.mode(), state.target(), state.branches(), "").supported(preferred);
         return outsideRoof(preferred, ramp, host) == null || this.blocked(ramp, probe, deps);
      }
   }

   private void sharedDeparture(RoadIndex.Built ramp, SmartSignRoads.Exit exit) {
      SmartSigns.Key key = new SmartSigns.Key(ramp.record.id(), 1, 1);
      SmartSigns.State old = this.states.getOrDefault(key, SmartSigns.State.empty(key));
      RoadIndex.Built host = this.known.get(exit.host());
      if (host != null) {
         List<RoadStructures.Part> rails = Stream.concat(ramp.record.structures().stream(), host.record.structures().stream()).toList();
         SmartSigns.Support mount = ForkSignPlacement.find(ramp.mesh, host.mesh, rails, exit.flow(), false);
         SmartSigns.Support preferred = this.preferredNose(ramp, host, exit, null, false);
         SmartSigns.Support nose = preferred != null ? preferred : mount;
         mount = outsideRoof(mount, ramp, host);
         List<SmartSigns.Branch> branches = List.of(new SmartSigns.Branch("ramp", List.of(), exit.side(), true, List.of()));
         SmartSigns.State next = old.layout(SmartSigns.Mode.ROADSIDE, ramp.record.id(), branches, mount == null ? "未找到主线出口鼻端护栏" : "").supported(mount);
         HashSet<UUID> deps = new HashSet<>();
         deps.add(exit.host());
         deps.add(ramp.record.id());
         if (mount != null && this.blocked(ramp, next, deps)) {
            next = next.layout(SmartSigns.Mode.ROADSIDE, ramp.record.id(), branches, "标牌安装空间与其他道路净空冲突");
         }

         if (this.preferredBlocked(ramp, host, preferred, mount, next, deps)) {
            next = next.layout(SmartSigns.Mode.ROADSIDE, ramp.record.id(), branches, "原鼻端净空不足，需提前设置告示牌");
         }

         if (!next.diagnostic().isEmpty()) {
            next = this.approachFallback(ramp, host, exit, nose, next, deps);
         }

         this.put(next);
         this.depend(key, deps);
      }
   }

   private SmartSigns.State approachFallback(
      RoadIndex.Built ramp, RoadIndex.Built host, SmartSignRoads.Exit exit, SmartSigns.Support nose, SmartSigns.State state, Set<UUID> deps
   ) {
      if (!this.main(host)) {
         return state;
      } else {
         double split = nose == null ? ForkSignPlacement.separator(ramp.mesh, host.mesh) : RoadQueries.horizontal(ramp.mesh, nose.foot()).sample().distance();
         if (Double.isFinite(split) && split >= 2.0 && split <= 256.0) {
            for (SmartSigns.Support mount : ForkSignPlacement.approach(ramp.mesh, ramp.record.structures(), split, 1, exit.side(), state.branches().size() > 1)) {
               RoadQueries.Projection at = RoadQueries.horizontal(host.mesh, mount.foot());
               if (!((at.sample().distance() - exit.station()) * (double)exit.flow() < -0.1)
                  && !(at.sample().left().mul((double)exit.flow()).dot(mount.right()) < 0.8)
                  && outsideRoof(mount, ramp, host) != null) {
                  SmartSigns.State candidate = state.layout(state.mode(), state.target(), state.branches(), "").supported(mount);
                  if (this.approachClear(ramp, candidate, deps)) {
                     return candidate;
                  }
               }
            }
         }

         for (SmartSigns.Support mountx : ForkSignPlacement.approach(
            host.mesh, host.record.structures(), exit.station(), exit.flow(), exit.side(), state.branches().size() > 1
         )) {
            if (outsideRoof(mountx, host) != null) {
               SmartSigns.State candidate = state.layout(state.mode(), state.target(), state.branches(), "").supported(mountx);
               if (this.approachClear(ramp, candidate, deps)) {
                  return candidate;
               }
            }
         }

         return state;
      }
   }

   private boolean approachClear(RoadIndex.Built ramp, SmartSigns.State state, Set<UUID> deps) {
      SmartSigns.Layout layout = SmartSigns.layout(ramp.mesh, state);
      ArrayList<RoadStructures.Part> parts = new ArrayList<>();
      RoadStructures.Material steel = RoadStructures.Material.STEEL;
      RoadGeometry.V foot = state.support().foot().add(layout.front().mul(-0.08));
      parts.add(new RoadStructures.Part(foot, foot, 0.3, 7.87, true, steel));

      for (SmartSigns.Board board : layout.boards()) {
         parts.add(
            new RoadStructures.Part(
               layout.at(board.x() - board.width() / 2.0, board.bottom(), 0.05),
               layout.at(board.x() + board.width() / 2.0, board.bottom(), 0.05),
               0.8,
               board.height(),
               false,
               steel
            )
         );
      }

      double lo = Math.min(0.0, layout.boards().stream().mapToDouble(b -> b.x() - b.width() / 2.0 + 0.25).min().orElse(0.0));
      double hi = Math.max(0.0, layout.boards().stream().mapToDouble(b -> b.x() + b.width() / 2.0 - 0.25).max().orElse(0.0));

      for (double y : new double[]{1.1, 2.1}) {
         parts.add(new RoadStructures.Part(layout.at(lo, y - 0.085, -0.22), layout.at(hi, y - 0.085, -0.22), 0.17, 0.17, false, steel));
      }

      TreeSet<UUID> nearby = new TreeSet<>();

      for (RoadStructures.Part part : parts) {
         List<RoadGeometry.V> bounds = part.base();

         for (int x = (int)Math.floor(bounds.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow() - 0.5) >> 4;
            x <= (int)Math.floor(bounds.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow() + 0.5) >> 4;
            x++
         ) {
            for (int z = (int)Math.floor(bounds.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow() - 0.5) >> 4;
               z <= (int)Math.floor(bounds.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow() + 0.5) >> 4;
               z++
            ) {
               nearby.addAll(this.data.index.inChunk(ChunkPos.asLong(x, z)));
            }
         }
      }

      for (UUID id : nearby) {
         RoadIndex.Built road = this.data.index.roads.get(id);
         if (road != null) {
            deps.add(id);
            RoadSolidOverlap.Index solids = new RoadSolidOverlap.Index(road.record.structures());
            RoadGeometry.Mesh motor = LaneDeck.motorOnly(road.mesh);

            for (RoadStructures.Part part : parts) {
               if (RoadClearance.structureInvades(part, motor, 4.5)
                  || RoadClearance.structureInvades(part, road.mesh, 0.0)
                  || solids.intersects(part)
                     && road.record
                        .structures()
                        .stream()
                        .anyMatch(p -> !ForkSignPlacement.mountingContact(part, p, state.support()) && RoadSolidOverlap.intersects(part, p))) {
                  return false;
               }
            }

            for (RoadGeometry.Mesh cycle : RoadsideBypass.travel(road.mesh)) {
               for (RoadStructures.Part partx : parts) {
                  if (RoadClearance.structureInvades(partx, cycle, 4.4)) {
                     return false;
                  }
               }
            }
         }
      }

      return true;
   }

   private List<RoadIndex.Built> terminalFork(RoadIndex.Built root) {
      CompoundTag descriptor = this.data.interchanges.get(root.record.assembly());
      if (descriptor != null && !descriptor.contains("Corridor")) {
         boolean doubleTrumpet = descriptor.getCompound("Options").getString("Preset").equals("DOUBLE_TRUMPET");
         if (!doubleTrumpet && descriptor.getList("Nodes", 10).size() != 3) {
            return List.of();
         } else {
            ListTag topology = descriptor.getList("SignTopology", 10);
            CompoundTag source = topology.stream()
               .map(t -> (CompoundTag)t)
               .filter(t -> t.hasUUID("Road") && t.getUUID("Road").equals(root.record.id()))
               .findFirst()
               .orElse(null);
            return source != null && source.getInt("From") == (doubleTrumpet ? -1 : 1)
               ? new TreeSet<>(this.groups.getOrDefault(root.record.assembly(), Set.of()))
                  .stream()
                  .map(this.known::get)
                  .filter(
                     r -> r != null
                           && !this.main(r)
                           && r.mesh.first().center().distance(root.mesh.first().center()) < 0.35
                           && r.mesh.first().left().dot(root.mesh.first().left()) > 0.9
                  )
                  .toList()
               : List.of();
         }
      } else {
         return List.of();
      }
   }

   private SmartSigns.State terminalText(SmartSigns.State next, List<RoadIndex.Built> children) {
      Set<String> valid = SmartSigns.fieldIds(next.branches());

      for (RoadIndex.Built child : children) {
         SmartSigns.State prior = this.states.get(new SmartSigns.Key(child.record.id(), 0, 1));
         if (prior != null) {
            List<SmartSignRoads.Exit> exits = this.derive(child);
            if (exits.size() == 1) {
               List<SmartSigns.Branch> movements = this.roadsideMovements(exits.get(0));
               if (movements.size() == 1) {
                  String destination = movements.get(0).id();
                  HashMap<String, SmartSigns.Text> words = new HashMap<>(prior.drafts().getOrDefault(prior.targetKey(), Map.of()));
                  words.putAll(prior.text());

                  for (Entry<String, SmartSigns.Text> entry : words.entrySet()) {
                     String key = entry.getKey();
                     if (key.startsWith("ramp/")) {
                        key = destination + key.substring(4);
                     }

                     if (valid.contains(key) && !next.text().containsKey(key)) {
                        next = next.edit(key, entry.getValue());
                     }
                  }
               }
            }
         }
      }

      return next;
   }

   private Map<String, InterchangeSignForks.Fork> multiForks(UUID assembly) {
      if (this.work != null && this.work.forks.containsKey(assembly)) {
         return this.work.forks.get(assembly);
      } else {
         ArrayList<InterchangePlanner.Leg> legs = new ArrayList<>();

         for (UUID id : new TreeSet<>(this.groups.getOrDefault(assembly, Set.of()))) {
            RoadIndex.Built r = this.known.get(id);
            if (r != null && !this.main(r)) {
               legs.add(new InterchangePlanner.Leg(id.toString(), 0, 0, r.mesh));
            }
         }

         Map<String, InterchangeSignForks.Fork> forks = InterchangeSignForks.find(legs);
         if (this.work != null) {
            this.work.forks.put(assembly, forks);
         }

         return forks;
      }
   }

   private void multiRoadside(RoadIndex.Built ramp, SmartSigns.State old) {
      InterchangeSignForks.Fork fork = this.multiForks(ramp.record.assembly()).get(ramp.record.id().toString());
      if (fork == null) {
         if (old.mode() == SmartSigns.Mode.ROADSIDE) {
            this.put(old.layout(SmartSigns.Mode.ROADSIDE, ramp.record.id(), List.of(), "此位置不是集散道分叉").supported(null));
         }
      } else {
         RoadIndex.Built sibling = this.known.get(UUID.fromString(fork.sibling()));
         if (sibling != null) {
            SmartSigns.Support mount = ForkSignPlacement.findBetween(ramp.mesh, sibling.mesh, ramp.record.structures(), 1, true);
            if (mount == null) {
               mount = ForkSignPlacement.findBetween(sibling.mesh, ramp.mesh, sibling.record.structures(), 1, true);
            }

            mount = outsideRoof(mount, ramp, sibling);
            if (mount != null) {
               mount = new SmartSigns.Support(mount.foot(), fork.right(), fork.right().left(), true);
            }

            List<SmartSigns.Branch> branches = List.of(
               new SmartSigns.Branch("fork:left", List.of(), -1, true, List.of()), new SmartSigns.Branch("fork:right", List.of(), 1, true, List.of())
            );
            SmartSigns.State next = old.layout(SmartSigns.Mode.ROADSIDE, ramp.record.id(), branches, mount == null ? "未找到分叉鼻端护栏" : "").supported(mount);
            HashSet<UUID> deps = new HashSet<>();
            deps.add(ramp.record.id());
            deps.add(sibling.record.id());
            if (mount != null && this.blocked(ramp, next, deps)) {
               next = next.layout(SmartSigns.Mode.ROADSIDE, ramp.record.id(), branches, "标牌安装空间与其他道路净空冲突");
            }

            this.put(next);
            this.depend(old.key(), deps);
         }
      }
   }

   private List<RoadIndex.Built> sharedForkChildren(RoadIndex.Built root) {
      CompoundTag descriptor = this.data.interchanges.get(root.record.assembly());
      return descriptor != null && Set.of("TURBINE", "STACK", "HYBRID").contains(descriptor.getCompound("Options").getString("Preset"))
         ? new TreeSet<>(this.groups.getOrDefault(root.record.assembly(), Set.of()))
            .stream()
            .map(this.known::get)
            .filter(
               r -> r != null
                     && r != root
                     && !this.main(r)
                     && root.mesh.last().center().distance(r.mesh.first().center()) < 0.35
                     && root.mesh.last().left().dot(r.mesh.first().left()) > 0.9
            )
            .toList()
         : List.of();
   }

   private static SmartSigns.Support sharedForkMount(RoadIndex.Built root, List<RoadIndex.Built> children, RoadGeometry.Sample fork) {
      SmartSigns.Support best = null;
      double nearest = Double.POSITIVE_INFINITY;

      for (int i = 0; i < 2; i++) {
         RoadIndex.Built child = children.get(i);
         RoadIndex.Built sibling = children.get(1 - i);
         SmartSigns.Support support = ForkSignPlacement.findBetween(child.mesh, sibling.mesh, child.record.structures(), 1, true);
         if (support != null) {
            double d = support.foot().sub(fork.center()).horizontalLength();
            if (d < nearest) {
               RoadGeometry.V right = fork.left();
               best = new SmartSigns.Support(support.foot(), right, right.left(), true);
               nearest = d;
            }
         }
      }

      return best;
   }

   private List<SmartSigns.Branch> roadsideMovements(SmartSignRoads.Exit entry) {
      RoadIndex.Built host = this.known.get(entry.host());
      if (host == null) {
         return List.of();
      } else {
         RoadGeometry.Sample at = RoadStructures.sample(host.mesh, entry.station());
         RoadGeometry.V right = at.left().mul((double)entry.flow());
         ArrayDeque<UUID> queue = new ArrayDeque<>();
         HashSet<UUID> seen = new HashSet<>();
         TreeMap<String, SmartSigns.Branch> result = new TreeMap<>();
         TreeSet<UUID> group = new TreeSet<>(this.groups.getOrDefault(entry.assembly(), Set.of()));
         queue.add(entry.id());

         while (!queue.isEmpty() && seen.size() < 128) {
            UUID id = queue.remove();
            if (seen.add(id)) {
               RoadIndex.Built current = this.known.get(id);
               if (current != null) {
                  RoadGeometry.Sample tail = current.mesh.last();
                  RoadGeometry.V heading = tail.left().left().mul(-1.0);
                  boolean reached = false;

                  for (UUID target : group) {
                     RoadIndex.Built road = this.known.get(target);
                     if (this.main(road)) {
                        RoadQueries.Projection q = RoadQueries.horizontal(road.mesh, tail.center());
                        if (this.joinsMainline(current, road)) {
                           double direction = q.sample().left().left().mul(-1.0).dot(heading);
                           if (!(Math.abs(direction) < 0.9)) {
                              int flow = direction > 0.0 ? 1 : -1;
                              if (!target.equals(entry.host()) || flow != entry.flow()) {
                                 RoadGeometry.V destination = flow > 0 ? road.mesh.last().center() : road.mesh.first().center();
                                 int side = (int)Math.signum(destination.sub(at.center()).dot(right));
                                 if (side != 0) {
                                    String key = "out:" + target + ":" + flow;
                                    result.put(key, new SmartSigns.Branch(key, List.of(), side, true, List.of()));
                                    reached = true;
                                 }
                              }
                           }
                        }
                     }
                  }

                  if (!reached) {
                     for (UUID next : group) {
                        RoadIndex.Built road = this.known.get(next);
                        if (road != null
                           && !this.main(road)
                           && !seen.contains(next)
                           && !next.equals(id)
                           && tail.center().distance(road.mesh.first().center()) < 0.35
                           && tail.left().dot(road.mesh.first().left()) > 0.9) {
                           queue.add(next);
                        }
                     }
                  }
               }
            }
         }

         return result.values().stream().sorted(Comparator.comparingInt(SmartSigns.Branch::side).thenComparing(SmartSigns.Branch::id)).toList();
      }
   }

   private boolean joinsMainline(RoadIndex.Built arrival, RoadIndex.Built host) {
      RoadGeometry.Sample tail = arrival.mesh.last();
      RoadQueries.Projection q = RoadQueries.horizontal(host.mesh, tail.center());
      if (!ForkSignPlacement.onPath(host.mesh, tail.center(), 0.35)) {
         return false;
      } else if (Math.abs(q.sample().center().y() - tail.center().y()) > 0.3) {
         return false;
      } else {
         double reach = q.sample().halfWidth() + 0.1;
         if (arrival.record.assembly() != null && Objects.equals(arrival.record.assembly(), host.record.assembly())) {
            CompoundTag descriptor = this.data.interchanges.get(arrival.record.assembly());
            CompoundTag leg = null;
            CompoundTag mainLeg = null;
            if (descriptor != null) {
               for (Tag value : descriptor.getList("SignTopology", 10)) {
                  CompoundTag t = (CompoundTag)value;
                  if (t.hasUUID("Road")) {
                     if (t.getUUID("Road").equals(arrival.record.id())) {
                        leg = t;
                     }

                     if (t.getUUID("Road").equals(host.record.id())) {
                        mainLeg = t;
                     }
                  }
               }
            }

            if (leg != null
               && mainLeg != null
               && leg.getInt("To") >= 0
               && (leg.getInt("To") == mainLeg.getInt("From") || leg.getInt("To") == mainLeg.getInt("To"))) {
               reach += tail.halfWidth();
            }
         }

         return Math.abs(q.lateral()) <= reach;
      }
   }

   private static int departureSide(RoadGeometry.Mesh host, RoadGeometry.Mesh ramp, double station, int flow, int lane) {
      RoadGeometry.Sample s = RoadStructures.sample(host, station);
      RoadGeometry.V right = s.left().mul((double)flow);
      RoadGeometry.V origin = LanePoints.lane(host, station, lane).position();

      for (RoadGeometry.Sample p : ramp.samples()) {
         RoadQueries.Projection q = RoadQueries.horizontal(host, p.center());
         double lateral = p.center().sub(q.sample().center()).dot(q.sample().left()) * (double)flow;
         double initial = origin.sub(s.center()).dot(right);
         if (Math.abs(lateral - initial) > 0.2) {
            return lateral > initial ? 1 : -1;
         }
      }

      return origin.sub(s.center()).dot(right) >= 0.0 ? 1 : -1;
   }

   private static List<RoadGeometry.V> path(RoadGeometry.Mesh m) {
      ArrayList<RoadGeometry.V> out = new ArrayList<>();
      int step = Math.max(1, (m.samples().size() + 62) / 63);

      for (int i = 0; i < m.samples().size(); i += step) {
         out.add(m.samples().get(i).center());
      }

      if (!out.get(out.size() - 1).equals(m.last().center())) {
         out.add(m.last().center());
      }

      return List.copyOf(out);
   }

   private List<SmartSigns.Branch> diagram(SmartSignRoads.Exit entry, SmartSigns.Key k, Set<UUID> deps) {
      RoadIndex.Built host = this.known.get(entry.host());
      TreeSet<UUID> group = new TreeSet<>(this.groups.getOrDefault(entry.assembly(), Set.of()));
      deps.addAll(group);
      RoadGeometry.Sample s = RoadStructures.sample(host.mesh, entry.station());
      RoadGeometry.V forward = s.left().left().mul((double)(-entry.flow()));
      RoadGeometry.V right = s.left().mul((double)entry.flow());
      LinkedHashMap<String, List<RoadGeometry.V>> paths = new LinkedHashMap<>();
      RoadGeometry.V mainEnd = entry.flow() > 0 ? host.mesh.last().center() : host.mesh.first().center();
      CompoundTag descriptor = this.data.interchanges.get(entry.assembly());
      boolean through = descriptor == null;
      if (descriptor != null) {
         for (Tag value : descriptor.getList("Nodes", 10)) {
            RoadGeometry.Node node = RoadRecord.readNode((CompoundTag)value);
            if (node.position().distance(mainEnd) < 0.2) {
               through = true;
            }
         }
      }

      if (through) {
         paths.put("through", List.of(s.center(), mainEnd));
      }

      boolean multi = descriptor != null && descriptor.getList("Nodes", 10).size() >= 5;
      if (multi) {
         ArrayList<InterchangePlanner.Leg> legs = new ArrayList<>();
         int from = -1;

         for (Tag valuex : descriptor.getList("SignTopology", 10)) {
            CompoundTag tag = (CompoundTag)valuex;
            if (tag.hasUUID("Road")) {
               RoadIndex.Built part = this.known.get(tag.getUUID("Road"));
               if (part != null) {
                  legs.add(new InterchangePlanner.Leg(tag.getUUID("Road").toString(), tag.getInt("From"), tag.getInt("To"), part.mesh));
                  if (tag.getUUID("Road").equals(entry.id())) {
                     from = tag.getInt("From");
                  }
               }
            }
         }

         ArrayList<RoadGeometry.Node> anchors = new ArrayList<>();

         for (Tag valuexx : descriptor.getList("Nodes", 10)) {
            anchors.add(RoadRecord.readNode((CompoundTag)valuexx));
         }

         if (from >= 0) {
            paths.putAll(InterchangeSignPaths.routes(legs, this.known.get(entry.id()).mesh.first().center(), from, anchors));
         }
      } else {
         for (UUID id : group) {
            RoadIndex.Built ramp = this.known.get(id);
            if (ramp != null && !this.main(ramp)) {
               for (SmartSignRoads.Exit e : this.exits.getOrDefault(entry.host(), List.of())) {
                  if (e.id().equals(id) && e.flow() == entry.flow() && Objects.equals(e.assembly(), entry.assembly())) {
                     ArrayDeque<List<UUID>> queue = new ArrayDeque<>();
                     queue.add(List.of(id));
                     int visits = 0;

                     while (!queue.isEmpty() && visits++ < 128 && paths.size() < 10) {
                        List<UUID> route = queue.removeFirst();
                        RoadIndex.Built tail = this.known.get(route.get(route.size() - 1));
                        boolean reaches = false;
                        RoadGeometry.V destination = null;
                        String destinationKey = null;

                        for (UUID target : group) {
                           RoadIndex.Built road = this.known.get(target);
                           if (this.main(road)) {
                              RoadQueries.Projection q = RoadQueries.horizontal(road.mesh, tail.mesh.last().center());
                              if (this.joinsMainline(tail, road)
                                 && LaneSections.live(road.mesh, q.sample().distance())
                                    .lanes()
                                    .stream()
                                    .anyMatch(l -> l.direction().dot(tail.mesh.last().left().left().mul(-1.0)) > 0.9)) {
                                 reaches = true;
                                 double sign = q.sample().left().dot(tail.mesh.last().left());
                                 destination = sign > 0.0 ? road.mesh.last().center() : road.mesh.first().center();
                                 destinationKey = road.record.id().equals(entry.host()) && (sign > 0.0 ? 1 : -1) == entry.flow()
                                    ? "through"
                                    : "out:" + road.record.id() + ":" + (sign > 0.0 ? 1 : -1);
                              }
                           }
                        }

                        if (reaches && !"through".equals(destinationKey)) {
                           ArrayList<RoadGeometry.V> points = new ArrayList<>();
                           points.add(s.center());

                           for (UUID part : route) {
                              points.addAll(path(this.known.get(part).mesh));
                           }

                           if (destination != null) {
                              points.add(destination);
                           }

                           paths.putIfAbsent(destinationKey, points);
                        } else {
                           for (UUID other : group) {
                              RoadIndex.Built next = this.known.get(other);
                              if (next != null
                                 && !route.contains(other)
                                 && !this.main(next)
                                 && tail.mesh.last().center().distance(next.mesh.first().center()) < 0.35
                                 && tail.mesh.last().left().dot(next.mesh.first().left()) > 0.9) {
                                 ArrayList<UUID> chain = new ArrayList<>(route);
                                 chain.add(other);
                                 queue.add(chain);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         for (UUID ringId : group) {
            RoadIndex.Built ring = this.known.get(ringId);
            if (ring != null && ring.mesh.closed()) {
               for (SmartSignRoads.Exit source : this.exits.getOrDefault(entry.host(), List.of())) {
                  if (source.flow() == entry.flow() && Objects.equals(source.assembly(), entry.assembly())) {
                     RoadIndex.Built incoming = this.known.get(source.id());
                     if (incoming != null) {
                        RoadQueries.Projection join = RoadQueries.horizontal(ring.mesh, incoming.mesh.last().center());
                        if (!(join.sample().center().distance(incoming.mesh.last().center()) > 0.4)) {
                           for (UUID outId : group) {
                              RoadIndex.Built outgoing = this.known.get(outId);
                              if (outgoing != null && !this.main(outgoing) && outgoing != ring && outgoing != incoming) {
                                 RoadQueries.Projection leave = RoadQueries.horizontal(ring.mesh, outgoing.mesh.first().center());
                                 if (!(leave.sample().center().distance(outgoing.mesh.first().center()) > 0.4)) {
                                    RoadIndex.Built targetx = null;
                                    int flow = 0;

                                    for (UUID targetId : group) {
                                       RoadIndex.Built road = this.known.get(targetId);
                                       if (this.main(road) && !targetId.equals(entry.host()) && this.joinsMainline(outgoing, road)) {
                                          RoadQueries.Projection q = RoadQueries.horizontal(road.mesh, outgoing.mesh.last().center());
                                          double dot = q.sample().left().dot(outgoing.mesh.last().left());
                                          if (!(Math.abs(dot) < 0.9)) {
                                             targetx = road;
                                             flow = dot > 0.0 ? 1 : -1;
                                             break;
                                          }
                                       }
                                    }

                                    if (targetx != null) {
                                       ArrayList<RoadGeometry.V> route = new ArrayList<>();
                                       route.add(s.center());
                                       route.addAll(path(incoming.mesh));
                                       double from = join.sample().distance();
                                       double to = leave.sample().distance();
                                       if (to < from) {
                                          to += ring.mesh.length();
                                       }

                                       for (double d = from; d < to; d += Math.max(1.0, (to - from) / 40.0)) {
                                          route.add(RoadStructures.sample(ring.mesh, d % ring.mesh.length()).center());
                                       }

                                       route.addAll(path(outgoing.mesh));
                                       route.add(flow > 0 ? targetx.mesh.last().center() : targetx.mesh.first().center());
                                       paths.put(source.id() + ":" + outId, route);
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      double maxX = 1.0;
      double maxZ = 1.0;
      double minZ = 0.0;

      for (List<RoadGeometry.V> path : paths.values()) {
         for (RoadGeometry.V p : path) {
            RoadGeometry.V d = p.sub(s.center());
            maxX = Math.max(maxX, Math.abs(d.dot(right)));
            maxZ = Math.max(maxZ, d.dot(forward));
            minZ = Math.min(minZ, d.dot(forward));
         }
      }

      ArrayList<SmartSigns.Branch> out = new ArrayList<>();

      for (Entry<String, List<RoadGeometry.V>> item : paths.entrySet()) {
         ArrayList<RoadGeometry.V> normalized = new ArrayList<>();
         List<RoadGeometry.V> p = item.getValue();
         int step = Math.max(1, (p.size() + 94) / 95);

         for (int i = 0; i < p.size(); i += step) {
            RoadGeometry.V d = p.get(i).sub(s.center());
            RoadGeometry.V v = new RoadGeometry.V(d.dot(right) / maxX, d.y(), (d.dot(forward) - minZ) / (maxZ - minZ));
            if (normalized.isEmpty() || normalized.get(normalized.size() - 1).distance(v) > 1.0E-7) {
               normalized.add(v);
            }
         }

         RoadGeometry.V end = p.get(p.size() - 1).sub(s.center());
         RoadGeometry.V finalPoint = new RoadGeometry.V(end.dot(right) / maxX, end.y(), (end.dot(forward) - minZ) / (maxZ - minZ));
         if (normalized.isEmpty() || normalized.get(normalized.size() - 1).distance(finalPoint) > 1.0E-7) {
            normalized.add(finalPoint);
         }

         out.add(new SmartSigns.Branch(item.getKey(), List.of(), (int)Math.signum(end.dot(right)), true, normalized));
      }

      out.sort(Comparator.<SmartSigns.Branch>comparingDouble(b -> b.path().get(b.path().size() - 1).x()).thenComparing(SmartSigns.Branch::id));
      return SmartSignTemplates.compile(
         descriptor == null ? "" : descriptor.getCompound("Options").getString("Preset"), out, host.mesh.settings().options().leftTraffic()
      );
   }

   public SmartSigns.State state(SmartSigns.Key key) {
      this.refresh();
      return this.states.getOrDefault(key, SmartSigns.State.empty(key));
   }

   public SmartSigns.State peek(SmartSigns.Key key) {
      return this.states.getOrDefault(key, SmartSigns.State.empty(key));
   }

   public long version(UUID id) {
      return this.versions.getOrDefault(id, 0L);
   }

   public List<SmartSigns.State> road(UUID id) {
      return this.keysByRoad.getOrDefault(id, Set.of()).stream().map(this.states::get).filter(Objects::nonNull).toList();
   }

   private void requireReady() {
      if (this.pending()) {
         throw new IllegalArgumentException("标牌索引正在更新，请稍后重试；其他道路工具可正常使用");
      }
   }

   public void assemblyChanged(UUID id) {
      for (UUID road : this.groups.getOrDefault(id, Set.of())) {
         this.changed(road);
      }

      CompoundTag tag = this.data.interchanges.get(id);
      if (tag != null) {
         for (Tag value : tag.getList("SignTopology", 10)) {
            CompoundTag leg = (CompoundTag)value;
            if (leg.hasUUID("Road")) {
               this.changed(leg.getUUID("Road"));
            }
         }
      }
   }

   public CompoundTag packet(ServerLevel level, UUID id) {
      CompoundTag t = new CompoundTag();
      t.putString("Type", "smartSigns");
      t.putString("Dimension", level.dimension().location().toString());
      t.putUUID("Id", id);
      ListTag list = new ListTag();

      for (SmartSigns.State s : this.road(id)) {
         if (s.mode() != SmartSigns.Mode.NORMAL) {
            list.add(SmartSignCodec.write(s));
         }
      }

      t.put("Signs", list);
      return t;
   }

   public void tick(ServerLevel level) {
      this.tick(level, System.nanoTime() + 2000000L);
   }

   public void tick(ServerLevel level, long deadline) {
      if (this.pending() || this.sightlinesPending() || !this.publish.isEmpty()) {
         int start = this.maintenanceFamily;
         int visited = 0;

         for (int turn = 0; turn < 3 && System.nanoTime() < deadline; turn++) {
            int family = this.maintenanceFamily;
            this.maintenanceFamily = (family + 1) % 3;
            visited++;
            long now = System.nanoTime();
            long partDeadline = now + Math.max(1L, (deadline - now) / (long)(3 - turn));
            if (family == 0) {
               this.advance(partDeadline - now, 32);
            } else if (family == 1) {
               this.advanceSightlines(level, partDeadline, 1024);
            } else {
               Iterator<UUID> it = this.publish.iterator();

               for (int n = 0; n < 16 && it.hasNext() && System.nanoTime() < partDeadline; n++) {
                  UUID id = it.next();
                  it.remove();
                  RoadNetwork.broadcastSmart(level, id);
               }
            }
         }

         if (visited == 3) {
            this.maintenanceFamily = (start + 1) % 3;
         }
      }
   }

   public boolean sightlinesPending() {
      return this.sightWork != null || !this.sightDirty.isEmpty();
   }

   void advanceSightlines(ServerLevel level, long deadline, int maxSteps) {
      if (this.sightWork == null) {
         if (this.sightDirty.isEmpty()) {
            return;
         }

         this.sightWork = new RoadSignSightlines(this.data.index, this.sightDirty, (road, removed) -> {
            this.applyingSight = true;

            Set var4x;
            try {
               var4x = this.data.removeSightlineLamps(level, road, removed);
            } finally {
               this.applyingSight = false;
            }

            return var4x;
         }, chunk -> {
            ChunkPos at = new ChunkPos(chunk);
            if (level.hasChunk(at.x, at.z)) {
               this.data.repairChunk(level, at);
            }
         });
      }

      this.sightWork.advance(deadline, maxSteps);
      if (this.sightWork.done()) {
         RoadSignSightlines.Cost cost = this.sightWork.cost();
         this.sightWaves++;
         this.sightQueries = this.sightQueries + cost.chunkQueries();
         this.sightPartVisits = this.sightPartVisits + cost.partVisits();
         this.sightKeyBuilds = this.sightKeyBuilds + cost.keyBuilds();
         this.sightCommits = this.sightCommits + cost.commits();
         if (this.sightWork.invalidated()) {
            this.sightInvalidations++;
         } else {
            this.sightDirty.removeAll(this.sightWork.requested());
         }

         this.sightWork = null;
      }
   }

   public String maintenanceMetrics() {
      return "sightWaves="
         + this.sightWaves
         + " sightInvalidations="
         + this.sightInvalidations
         + " sightChunkQueries="
         + this.sightQueries
         + " sightPartVisits="
         + this.sightPartVisits
         + " sightKeyBuilds="
         + this.sightKeyBuilds
         + " sightCommits="
         + this.sightCommits
         + " sightPending="
         + this.sightDirty.size()
         + " sightActive="
         + (this.sightWork != null);
   }

   public void validateMode(SmartSigns.Key key, SmartSigns.Mode mode, long revision) {
      this.requireReady();
      RoadIndex.Built built = this.data.index.roads.get(key.road());
      if (!this.main(built) || LaneSections.live(built.mesh, RoadGantry.station(built.mesh, key.slot()).distance()).count(key.flow()) == 0) {
         throw new IllegalArgumentException("该方向没有可服务的车道");
      } else if (!SmartSigns.STYLES.contains(mode) && mode != SmartSigns.Mode.NORMAL && mode != SmartSigns.Mode.EXIT_AUTO) {
         throw new IllegalArgumentException("无效的龙门架样式");
      } else if (this.peek(key).revision() != revision) {
         throw new IllegalArgumentException("标牌已被更新，请重新打开");
      }
   }

   public void indicators(SmartSigns.Key key, Set<String> closed) {
      this.put(this.peek(key).indicators(closed));
   }

   public void applyMode(SmartSigns.Key key, SmartSigns.Mode mode) {
      this.applyMode(key, mode, true);
   }

   public void applyModes(List<SmartSigns.Key> keys, SmartSigns.Mode mode) {
      for (SmartSigns.Key key : keys) {
         this.applyMode(key, mode, keys.size() == 1);
      }
   }

   private void applyMode(SmartSigns.Key key, SmartSigns.Mode mode, boolean exclusive) {
      SmartSigns.State old = this.peek(key);
      if (old.mode() != mode) {
         boolean sameExit = old.mode() == SmartSigns.Mode.EXIT_AUTO && mode == SmartSigns.Mode.EXIT_SIGN;
         this.put(old.layout(mode, sameExit ? old.target() : null, sameExit ? old.branches() : List.of(), sameExit ? old.diagnostic() : "", old.entry()));
      }

      if (exclusive && mode == SmartSigns.Mode.SPEED_LIMIT) {
         SmartSigns.Key other = new SmartSigns.Key(key.road(), key.slot(), -key.flow());
         SmartSigns.State opposite = this.peek(other);
         if (opposite.mode() == SmartSigns.Mode.SPEED_LIMIT) {
            this.put(opposite.layout(SmartSigns.Mode.EMPTY, null, List.of(), ""));
         }
      }

      this.changed(key.road());
   }

   public void mode(SmartSigns.Key key, SmartSigns.Mode mode, long revision) {
      this.validateMode(key, mode, revision);
      this.applyMode(key, mode);
   }

   public void edit(ServerPlayer player, CompoundTag command) {
      this.requireReady();
      SmartSigns.Key key = SmartSignCodec.key(command);
      RoadIndex.Built b = this.known.get(key.road());
      if (b == null) {
         throw new IllegalArgumentException("龙门架已删除");
      } else {
         RoadData.requireOwner(player, b.record.owner());
         SmartSigns.State old = this.state(key);
         if (command.getLong("Revision") != old.revision() || !Objects.equals(command.hasUUID("Target") ? command.getUUID("Target") : null, old.target())) {
            throw new IllegalArgumentException("标牌目标或内容已改变，请重新打开");
         } else if (old.target() != null && old.diagnostic().isEmpty()) {
            RoadGeometry.V anchor = SmartSigns.anchor(b.mesh, old);
            if (player.position().distanceToSqr(new Vec3(anchor.x(), anchor.y(), anchor.z())) > 16384.0) {
               throw new IllegalArgumentException("离标牌太远");
            } else {
               SmartSigns.State next;
               if (old.mode() == SmartSigns.Mode.LANE_INDICATOR) {
                  if (command.getBoolean("Restore") || !command.contains("Closed", 1)) {
                     throw new IllegalArgumentException("请选择当前车道通行或禁行");
                  }

                  next = old.indicator(command.getString("Field"), command.getBoolean("Closed"));
               } else {
                  next = command.getBoolean("Restore")
                     ? old.restore()
                     : old.edit(command.getString("Field"), SmartSignCodec.text(command.getCompound("Value")));
                  if (!command.getBoolean("Restore") && command.contains("HeaderVisible")) {
                     String field = command.getString("Field");
                     int slash = field.lastIndexOf(47);
                     if (slash < 1) {
                        throw new IllegalArgumentException("无效的标牌分支");
                     }

                     next = next.header(field.substring(0, slash), command.getBoolean("HeaderVisible"));
                  }

                  if (!command.getBoolean("Restore")) {
                     for (String component : List.of("exit", "distance")) {
                        String flag = component.equals("exit") ? "ExitVisible" : "DistanceVisible";
                        if (command.contains(flag)) {
                           String field = command.getString("Field");
                           int slash = field.lastIndexOf(47);
                           if (slash < 1) {
                              throw new IllegalArgumentException("无效的标牌分支");
                           }

                           next = next.accessory(field.substring(0, slash), component, command.getBoolean(flag));
                        }
                     }
                  }
               }

               validateText(b.mesh, next);
               this.put(next);
               this.textEdits++;
            }
         } else {
            throw new IllegalArgumentException("标牌待识别，暂不能提交内容");
         }
      }
   }

   private static void validateText(RoadGeometry.Mesh mesh, SmartSigns.State s) {
      for (SmartSigns.Field f : SmartSigns.layout(mesh, s).fields()) {
         SmartSigns.Text word = s.text().get(f.id());
         if (word != null) {
            int allowed = word.wrap() ? f.lines() : 1;
            int rows = 0;
            double minimum = 0.105;

            for (String line : word.value().split("\n", -1)) {
               double units = line.codePoints().mapToDouble(c -> c < 128 ? 0.6 : 1.0).sum();
               rows += Math.max(1, (int)Math.ceil(units * minimum / f.width()));
            }

            if (rows > allowed || minimum * (double)rows > f.height() || !word.wrap() && word.value().contains("\n")) {
               throw new IllegalArgumentException("该区域文字过长或换行过多，请缩短文字或启用换行");
            }
         }
      }
   }

   public ListTag save() {
      ListTag list = new ListTag();
      this.states
         .values()
         .stream()
         .filter(
            s -> s.mode() != SmartSigns.Mode.NORMAL && s.mode() != SmartSigns.Mode.EXIT_AUTO
                  || !s.text().isEmpty()
                  || !s.drafts().isEmpty()
                  || !s.hiddenHeaders().isEmpty()
                  || !s.hiddenAccessories().isEmpty()
         )
         .forEach(s -> list.add(SmartSignCodec.write(s)));
      return list;
   }

   public void load(ListTag list) {
      if (list.size() > 65536) {
         throw new IllegalArgumentException("智能标牌记录过多");
      } else {
         for (Tag v : list) {
            SmartSigns.State s = SmartSignCodec.read((CompoundTag)v);
            if (!this.data.index.roads.containsKey(s.key().road())) {
               this.data.setDirty();
            } else {
               if (SmartSigns.freestanding(s.mode())) {
                  s = s.layout(s.mode(), s.target(), s.branches(), "标牌关联正在更新", s.entry()).supported(null);
               }

               this.addState(s);
            }
         }

         this.dirty.addAll(this.data.index.roads.keySet());
      }
   }

   private static record Closest(SmartSigns.Key key, Set<UUID> dependencies) {
   }

   private static record Exit(
      UUID id, UUID host, int flow, double station, List<Integer> lanes, int side, boolean dedicated, UUID assembly, List<RoadGeometry.V> route
   ) {
   }

   private static record Found(SmartSignRoads.Exit exit, double distance, String reason, Set<UUID> visited) {
   }

   private static record Position(UUID road, int flow, double station) {
   }

   private static record Presence(RoadIndex.Built road, boolean value) {
   }

   static record Snapshot(List<SmartSigns.State> states, Map<UUID, Long> versions, Set<UUID> sightDirty) {
      Snapshot(List<SmartSigns.State> states, Map<UUID, Long> versions) {
         this(states, versions, Set.of());
      }
   }

   private final class Work {
      final Set<UUID> changed;
      final Set<UUID> rampDirty = new LinkedHashSet<>();
      final Set<UUID> groupDirty = new LinkedHashSet<>();
      final Set<UUID> oldHosts = new LinkedHashSet<>();
      final Set<SmartSigns.Key> affected = new LinkedHashSet<>();
      final Map<SmartSigns.Key, SmartSignRoads.Found> anyQueries = new HashMap<>();
      final Map<SmartSigns.Key, SmartSignRoads.Found> assemblyQueries = new HashMap<>();
      final Map<UUID, SmartSignRoads.Closest> owners = new HashMap<>();
      final Map<UUID, Map<String, InterchangeSignForks.Fork>> forks = new HashMap<>();
      Iterator<UUID> roads;
      Iterator<UUID> ramps;
      Iterator<UUID> upstream;
      Iterator<UUID> cleanup;
      Iterator<Entry<UUID, List<SmartSignRoads.Exit>>> oldExits;
      Iterator<SmartSigns.Key> keys;
      int phase;

      Work(Set<UUID> ids) {
         this.changed = ids;
         this.roads = ids.iterator();
      }

      boolean step() {
         if (this.phase == 0) {
            if (this.roads.hasNext()) {
               this.index(this.roads.next());
               return true;
            } else {
               for (UUID g : this.groupDirty) {
                  this.rampDirty.addAll(SmartSignRoads.this.groups.getOrDefault(g, Set.of()));
               }

               this.oldExits = SmartSignRoads.this.exits.entrySet().iterator();
               this.phase = 1;
               return true;
            }
         } else if (this.phase == 1) {
            if (this.oldExits.hasNext()) {
               Entry<UUID, List<SmartSignRoads.Exit>> e = this.oldExits.next();
               e.getValue().removeIf(v -> {
                  boolean hit = this.rampDirty.contains(v.id()) || this.changed.contains(v.host());
                  if (hit) {
                     this.oldHosts.add(v.host());
                  }

                  return hit;
               });
               if (e.getValue().isEmpty()) {
                  this.oldExits.remove();
               }

               return true;
            } else {
               this.ramps = this.rampDirty.iterator();
               this.phase = 2;
               return true;
            }
         } else if (this.phase == 2) {
            if (this.ramps.hasNext()) {
               RoadIndex.Built b = SmartSignRoads.this.known.get(this.ramps.next());
               if (SmartSignRoads.this.elevated(b)) {
                  SmartSignRoads.this.elevatedSign(b);
                  return true;
               } else {
                  if (b != null && !SmartSignRoads.this.main(b)) {
                     List<SmartSignRoads.Exit> derived = SmartSignRoads.this.derive(b);

                     for (SmartSignRoads.Exit e : derived) {
                        SmartSignRoads.this.exits.computeIfAbsent(e.host(), kx -> new ArrayList<>()).add(e);
                        this.oldHosts.add(e.host());
                     }

                     SmartSignRoads.this.roadside(b, derived);
                  }

                  return true;
               }
            } else {
               LinkedHashSet<UUID> hosts = new LinkedHashSet<>(this.oldHosts);

               for (UUID id : this.changed) {
                  if (SmartSignRoads.this.main(SmartSignRoads.this.known.get(id))) {
                     hosts.add(id);
                  }
               }

               this.upstream = hosts.iterator();
               this.phase = 3;
               return true;
            }
         } else if (this.phase == 3) {
            if (this.upstream.hasNext()) {
               UUID host = this.upstream.next();
               this.affected.addAll(SmartSignRoads.this.dependencies.getOrDefault(host, Set.of()));
               SmartSignRoads.this.upstreamKeys(host, this.affected);
               return true;
            } else {
               this.keys = this.affected.iterator();
               this.phase = 4;
               return true;
            }
         } else if (this.phase == 4) {
            if (!this.keys.hasNext()) {
               this.cleanup = this.changed.iterator();
               this.phase = 5;
               return true;
            } else {
               SmartSigns.Key k = this.keys.next();
               if (SmartSignRoads.this.states.containsKey(k)) {
                  SmartSigns.State old = SmartSignRoads.this.states.get(k);
                  RoadIndex.Built built = SmartSignRoads.this.known.get(k.road());
                  if (old.mode() != SmartSigns.Mode.JUNCTION_ADVANCE && old.mode() != SmartSigns.Mode.ROAD_NAME) {
                     if (!SmartSigns.freestanding(old.mode())) {
                        SmartSignRoads.this.reconcile(k);
                     }
                  } else if (built != null
                     && built.record.junction() != null
                     && built.record.junction().get().arm() >= 0
                     && built.record.junction().spec().arms().get(built.record.junction().get().arm()).signEnabled(k.slot())) {
                     HashSet<UUID> deps = new HashSet<>();
                     deps.add(k.road());
                     SmartSigns.State next = JunctionSignRoads.derive(SmartSignRoads.this.data, built, old, k.slot(), deps);
                     if (SmartSignRoads.this.blocked(built, next, deps)) {
                        next = next.layout(next.mode(), next.target(), next.branches(), "标牌安装空间与其他道路净空冲突", next.entry());
                     }

                     SmartSignRoads.this.depend(k, deps);
                     SmartSignRoads.this.put(next);
                  }
               }

               return true;
            }
         } else if (this.cleanup.hasNext()) {
            UUID idx = this.cleanup.next();
            RoadIndex.Built b = SmartSignRoads.this.known.get(idx);

            for (SmartSigns.Key k : new ArrayList<>(SmartSignRoads.this.keysByRoad.getOrDefault(idx, Set.of()))) {
               if (!SmartSignRoads.this.compatible(b, SmartSignRoads.this.states.get(k))) {
                  SmartSignRoads.this.retire(k);
               }
            }

            return true;
         } else {
            return false;
         }
      }

      void index(UUID id) {
         this.affected.addAll(SmartSignRoads.this.dependencies.getOrDefault(id, Set.of()));

         for (SmartSigns.Key key : SmartSignRoads.this.dependencies.getOrDefault(id, Set.of())) {
            SmartSigns.State s = SmartSignRoads.this.states.get(key);
            if (s != null && s.mode() == SmartSigns.Mode.ROADSIDE) {
               this.rampDirty.add(key.road());
            }
         }

         RoadIndex.Built old = SmartSignRoads.this.known.get(id);
         RoadIndex.Built now = SmartSignRoads.this.data.index.roads.get(id);

         for (RoadIndex.Built b : Arrays.asList(old, now)) {
            if (b != null) {
               for (long chunk : b.chunks) {
                  for (SmartSigns.Key keyx : SmartSignRoads.this.supportChunks.getOrDefault(chunk, Set.of())) {
                     this.rampDirty.add(keyx.road());
                  }
               }

               if (b.record.assembly() != null) {
                  this.groupDirty.add(b.record.assembly());
               }

               this.rampDirty.addAll(SmartSignRoads.this.hosts.getOrDefault(id, Set.of()));
               if (LaneTopology.metadata(b.record).link() != null) {
                  this.rampDirty.add(id);
               }

               for (RoadGeometry.V p : List.of(b.mesh.first().center(), b.mesh.last().center())) {
                  for (UUID adjacent : SmartSignRoads.this.ports.getOrDefault(SmartSignRoads.port(p), Set.of())) {
                     this.affected.addAll(SmartSignRoads.this.dependencies.getOrDefault(adjacent, Set.of()));
                  }
               }
            }
         }

         for (SmartSigns.Key keyx : SmartSignRoads.this.keysByRoad.getOrDefault(id, Set.of())) {
            SmartSignRoads.this.presentCache.remove(keyx);
         }

         if (old != null) {
            SmartSignRoads.remove(SmartSignRoads.this.ports, SmartSignRoads.port(old.mesh.first().center()), id);
            SmartSignRoads.remove(SmartSignRoads.this.ports, SmartSignRoads.port(old.mesh.last().center()), id);
            if (old.record.assembly() != null) {
               SmartSignRoads.this.groups.getOrDefault(old.record.assembly(), new HashSet<>()).remove(id);
            }

            LanePoints.Link link = LaneTopology.metadata(old.record).link();
            if (link != null && link.from().road() != null) {
               SmartSignRoads.this.hosts.getOrDefault(link.from().road(), new HashSet<>()).remove(id);
            }
         }

         if (now == null) {
            SmartSignRoads.this.known.remove(id);

            for (SmartSigns.Key k : new ArrayList<>(SmartSignRoads.this.keysByRoad.getOrDefault(id, Set.of()))) {
               SmartSignRoads.this.removeState(k);
            }

            SmartSignRoads.this.versions.remove(id);
            SmartSignRoads.this.publish.add(id);
         } else {
            for (SmartSigns.Key keyx : new ArrayList<>(SmartSignRoads.this.keysByRoad.getOrDefault(id, Set.of()))) {
               if (!SmartSignRoads.this.compatible(now, SmartSignRoads.this.states.get(keyx))) {
                  SmartSignRoads.this.retire(keyx);
               }
            }

            SmartSignRoads.this.known.put(id, now);

            for (RoadGeometry.V p : List.of(now.mesh.first().center(), now.mesh.last().center())) {
               SmartSignRoads.this.ports.computeIfAbsent(SmartSignRoads.port(p), k -> new HashSet<>()).add(id);
            }

            if (now.record.assembly() != null) {
               SmartSignRoads.this.groups.computeIfAbsent(now.record.assembly(), k -> new HashSet<>()).add(id);
            }

            LanePoints.Link link = LaneTopology.metadata(now.record).link();
            if (link != null && link.from().road() != null) {
               SmartSignRoads.this.hosts.computeIfAbsent(link.from().road(), k -> new HashSet<>()).add(id);
            }

            if (now.record.junction() != null && now.record.junction().get().arm() >= 0) {
               int arm = now.record.junction().get().arm();
               boolean incoming = now.record.junction().spec().arms().get(arm).incoming() > 0;

               for (int slot = 0; slot < 2; slot++) {
                  SmartSigns.Key keyxx = new SmartSigns.Key(id, slot, 1);
                  SmartSigns.State oldState = SmartSignRoads.this.states.getOrDefault(keyxx, SmartSigns.State.empty(keyxx));
                  if (incoming && now.record.junction().spec().arms().get(arm).signEnabled(slot)) {
                     HashSet<UUID> deps = new HashSet<>();
                     deps.add(id);
                     SmartSigns.State next = JunctionSignRoads.derive(SmartSignRoads.this.data, now, oldState, slot, deps);
                     if (SmartSignRoads.this.blocked(now, next, deps)) {
                        next = next.layout(next.mode(), next.target(), next.branches(), "标牌安装空间与其他道路净空冲突", next.entry());
                     }

                     SmartSignRoads.this.depend(keyxx, deps);
                     SmartSignRoads.this.put(next);
                  } else if (SmartSigns.freestanding(oldState.mode())) {
                     SmartSignRoads.this.put(oldState.supported(null));
                  }
               }
            }

            if (SmartSignRoads.this.main(now)) {
               for (int slotx : RoadGantry.slots(now.mesh)) {
                  RoadGantry.Station station = RoadGantry.station(now.mesh, slotx);
                  if (station.kind() != RoadInfrastructure.Gantry.OFF) {
                     LaneSections.Live section = LaneSections.live(now.mesh, station.distance());

                     for (int flow : new int[]{1, -1}) {
                        if (section.count(flow) > 0) {
                           SmartSigns.Key keyxx = new SmartSigns.Key(id, slotx, flow);
                           if (!SmartSignRoads.this.states.containsKey(keyxx)) {
                              SmartSignRoads.this.addState(SmartSigns.State.empty(keyxx));
                           }

                           this.affected.add(keyxx);
                        }
                     }
                  }
               }
            }
         }
      }
   }
}
