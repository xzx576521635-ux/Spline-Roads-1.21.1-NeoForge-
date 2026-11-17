package com.sora.splineroads.world;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;

/**
 * Persisted ledger of marker blocks and the roads that need them.
 *
 * <p>Every road stores its own {@code position -> kind} map, plus a reverse index
 * {@code position -> roads}. The reverse index is what prevents deleting a marker that a
 * neighbouring road still needs (nearby roads often share the same marker cells).</p>
 */
public final class RoadMarkerData extends SavedData {
   private static final String FILE = "splineroads_markers";
   private final Map<UUID, Map<Long, RoadMarker.Kind>> markers = new HashMap<>();
   private final Map<Long, Set<UUID>> owners = new HashMap<>();
   private static final long[] EMPTY_PENDING = new long[0];
   private final LongOpenHashSet pending = new LongOpenHashSet();
   private boolean rebuilt;
   private boolean backlogDropped;

   public static RoadMarkerData get(ServerLevel level) {
      return (RoadMarkerData)level.getDataStorage()
         .computeIfAbsent(new Factory(RoadMarkerData::new, (tag, provider) -> load((CompoundTag)tag), null), FILE);
   }

   public void add(UUID road, long pos, RoadMarker.Kind kind) {
      Map<Long, RoadMarker.Kind> map = this.markers.computeIfAbsent(road, key -> new HashMap<>());
      RoadMarker.Kind previous = map.put(pos, kind);
      this.owners.computeIfAbsent(pos, key -> new HashSet<>()).add(road);

      if (previous != kind) {
         this.setDirty();
      }
   }

   public Map<Long, RoadMarker.Kind> positions(UUID road) {
      return this.markers.getOrDefault(road, Map.of());
   }

   public Set<UUID> roadsAt(long pos) {
      return this.owners.getOrDefault(pos, Set.of());
   }

   public boolean owns(long pos) {
      return this.owners.containsKey(pos);
   }

   public Set<UUID> roads() {
      return Set.copyOf(this.markers.keySet());
   }

   public void remove(UUID road) {
      Map<Long, RoadMarker.Kind> map = this.markers.remove(road);

      if (map != null) {
         for (long pos : map.keySet()) {
            Set<UUID> set = this.owners.get(pos);

            if (set != null) {
               set.remove(road);
               if (set.isEmpty()) {
                  this.owners.remove(pos);
               }
            }
         }

         this.setDirty();
      }
   }

   public void addPending(long pos) {
      if (this.pending.add(pos)) {
         this.setDirty();
      }
   }

   public int pendingCount() {
      return this.pending.size();
   }

   /** True once the full marker layout rebuild ran for this save (auto recovery runs once). */
   public boolean rebuilt() {
      return this.rebuilt;
   }

   public void rebuilt(boolean value) {
      if (this.rebuilt != value) {
         this.rebuilt = value;
         this.setDirty();
      }
   }

   public boolean backlogDropped() {
      return this.backlogDropped;
   }

   /**
    * Clears the persisted removal backlog. Older versions queued every marker that was not in
    * their (partial) ledger, so the backlog holds hundreds of thousands of perfectly valid
    * markers. Replaying it deleted the map colours; orphans are now re-detected per chunk.
    */
   public int dropPendingBacklog() {
      int size = this.pending.size();
      this.pending.clear();
      this.backlogDropped = true;
      if (size > 0) {
         this.setDirty();
      }
      return size;
   }

   /**
    * Removes and returns up to {@code limit} positions from the persisted removal backlog.
    *
    * <p>The backlog used to be copied with {@code Set.copyOf}, which rehashes the whole set.
    * A world that accumulated about a million positions therefore froze the server tick for
    * minutes every time the world was opened. Callers now take small slices and feed them
    * into the removal queue over several ticks.</p>
    */
   public long[] takePending(int limit) {
      if (this.pending.isEmpty() || limit <= 0) {
         return EMPTY_PENDING;
      }

      int count = Math.min(limit, this.pending.size());
      long[] out = new long[count];
      LongIterator iterator = this.pending.iterator();

      for (int i = 0; i < count && iterator.hasNext(); i++) {
         out[i] = iterator.nextLong();
      }

      for (int i = 0; i < count; i++) {
         this.pending.remove(out[i]);
      }

      this.setDirty();
      return out;
   }

   public boolean isPending(long pos) {
      return this.pending.contains(pos);
   }

   public void cancelPending(long pos) {
      if (this.pending.remove(pos)) {
         this.setDirty();
      }
   }

   /** Called once the block at {@code pos} was removed (or no longer exists). */
   public void complete(long pos) {
      boolean changed = this.pending.remove(pos);
      Set<UUID> set = this.owners.remove(pos);

      if (set != null) {
         for (UUID road : set) {
            Map<Long, RoadMarker.Kind> map = this.markers.get(road);

            if (map != null) {
               map.remove(pos);
            }
         }

         changed = true;
      }

      if (changed) {
         this.setDirty();
      }
   }

   public CompoundTag save(CompoundTag root, Provider provider) {
      ListTag list = new ListTag();

      for (Map.Entry<UUID, Map<Long, RoadMarker.Kind>> entry : this.markers.entrySet()) {
         if (entry.getValue().isEmpty()) {
            continue;
         }

         CompoundTag tag = new CompoundTag();
         tag.putUUID("Road", entry.getKey());
         long[] positions = new long[entry.getValue().size()];
         byte[] kinds = new byte[entry.getValue().size()];
         int i = 0;

         for (Map.Entry<Long, RoadMarker.Kind> marker : entry.getValue().entrySet()) {
            positions[i] = marker.getKey();
            kinds[i] = (byte)marker.getValue().ordinal();
            i++;
         }

         tag.putLongArray("Positions", positions);
         tag.putByteArray("Kinds", kinds);
         list.add(tag);
      }

      root.put("MarkerOwners", list);
      root.putLongArray("PendingRemovals", this.pending.toLongArray());
      root.putBoolean("Rebuilt", this.rebuilt);
      root.putBoolean("BacklogDropped", this.backlogDropped);
      return root;
   }

   private static RoadMarkerData load(CompoundTag root) {
      RoadMarkerData data = new RoadMarkerData();
      RoadMarker.Kind[] kinds = RoadMarker.Kind.values();
      ListTag list = root.getList("MarkerOwners", 10);

      for (int i = 0; i < list.size(); i++) {
         CompoundTag tag = list.getCompound(i);
         UUID road = tag.getUUID("Road");
         long[] positions = tag.getLongArray("Positions");
         byte[] kindBytes = tag.getByteArray("Kinds");
         HashMap<Long, RoadMarker.Kind> map = new HashMap<>();

         for (int j = 0; j < positions.length; j++) {
            int ordinal = j < kindBytes.length ? kindBytes[j] & 0xFF : -1;
            RoadMarker.Kind kind = ordinal >= 0 && ordinal < kinds.length ? kinds[ordinal] : RoadMarker.Kind.OAK_LEAVES;
            map.put(positions[j], kind);
            data.owners.computeIfAbsent(positions[j], key -> new HashSet<>()).add(road);
         }

         data.markers.put(road, map);
      }

      for (long pos : root.getLongArray("PendingRemovals")) {
         data.pending.add(pos);
      }

      data.rebuilt = root.getBoolean("Rebuilt");
      data.backlogDropped = root.getBoolean("BacklogDropped");

      return data;
   }

   private RoadMarkerData() {
   }
}
