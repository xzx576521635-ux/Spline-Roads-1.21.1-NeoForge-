package com.sora.splineroads.world;

import com.sora.splineroads.core.StreetPlan;
import com.sora.splineroads.core.StreetTerrain;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;

public final class StreetTaskStore extends SavedData {
   final Map<UUID, StreetTaskStore.Task> tasks = new LinkedHashMap<>();

   public static StreetTaskStore get(ServerLevel level) {
      return level.getDataStorage().computeIfAbsent(new Factory<>(StreetTaskStore::new, (t, prov) -> load(t), null), "splineroads_street_tasks");
   }

   public static StreetTaskStore load(CompoundTag t) {
      if (t.getInt("Version") != 1) {
         throw new IllegalStateException("路网任务存档版本不受支持");
      } else {
         StreetTaskStore store = new StreetTaskStore();

         for (Tag v : t.getList("Tasks", 10)) {
            StreetTaskStore.Task task = StreetTaskStore.Task.load((CompoundTag)v);
            store.tasks.put(task.id, task);
         }

         store.setDirty();
         return store;
      }
   }

   public CompoundTag save(CompoundTag t, Provider prov) {
      t.putInt("Version", 1);
      ListTag tasksTag = new ListTag();
      this.tasks.values().forEach(task -> tasksTag.add(task.save()));
      t.put("Tasks", tasksTag);
      return t;
   }

   void changed(StreetTaskStore.Task t) {
      t.updated = System.currentTimeMillis();
      this.setDirty();
   }

   static enum State {
      READING,
      READY,
      BUILDING,
      PAUSED,
      CANCELLED,
      COMPLETE,
      UNDOING,
      UNDONE,
      CONFLICT,
      FAILED;
   }

   static final class Task {
      final UUID id;
      final UUID owner;
      StreetPlan.Input input;
      StreetTerrain terrain = new StreetTerrain(Map.of());
      StreetTaskStore.State state = StreetTaskStore.State.READING;
      String message = "正在读取地形";
      int progress;
      int readChunks;
      int undoCursor = -1;
      int conflicts;
      long peakUnitMicros;
      long totalUnitMicros;
      final long created;
      long updated;
      long verifiedRevision = Long.MIN_VALUE;
      boolean undoIntent;
      List<RoadRecord> roads = List.of();
      List<CompoundTag> centers = List.of();
      final List<StreetJournal> journals = new ArrayList<>();
      final Map<Long, Long> fingerprints = new Long2LongOpenHashMap();
      final Map<UUID, CompoundTag> dependencies = new HashMap<>();

      Task(UUID id, UUID owner, StreetPlan.Input input) {
         this(id, owner, input, System.currentTimeMillis());
      }

      Task(UUID id, UUID owner, StreetPlan.Input input, long created) {
         this.id = id;
         this.owner = owner;
         this.input = input;
         this.created = created;
         this.updated = created;
      }

      int cells() {
         return this.journals.stream().mapToInt(j -> j.before.blocks.size()).sum();
      }

      CompoundTag save() {
         CompoundTag t = new CompoundTag();
         t.putUUID("Id", this.id);
         t.putUUID("Owner", this.owner);
         t.put("Input", StreetPlanCodec.write(this.input));
         t.put("Terrain", StreetPlanCodec.write(this.terrain));
         t.putString("State", this.state.name());
         t.putString("Message", this.message);
         t.putInt("Progress", this.progress);
         t.putInt("ReadChunks", this.readChunks);
         t.putInt("UndoCursor", this.undoCursor);
         t.putBoolean("UndoIntent", this.undoIntent);
         t.putInt("Conflicts", this.conflicts);
         t.putLong("Created", this.created);
         t.putLong("Updated", this.updated);
         t.putLong("PeakUnitMicros", this.peakUnitMicros);
         t.putLong("TotalUnitMicros", this.totalUnitMicros);
         ListTag r = new ListTag();
         this.roads.forEach(v -> r.add(v.save()));
         t.put("Roads", r);
         ListTag c = new ListTag();
         this.centers.forEach(v -> c.add(v.copy()));
         t.put("Centers", c);
         ListTag j = new ListTag();
         this.journals.forEach(v -> j.add(v.save()));
         t.put("Journals", j);
         long[] keys = new long[this.fingerprints.size() * 2];
         int i = 0;

         for (Entry<Long, Long> e : this.fingerprints.entrySet()) {
            keys[i++] = e.getKey();
            keys[i++] = e.getValue();
         }

         t.putLongArray("Fingerprints", keys);
         ListTag deps = new ListTag();
         this.dependencies.entrySet().stream().sorted(Entry.comparingByKey()).forEach(ex -> {
            CompoundTag v = new CompoundTag();
            v.putUUID("Id", (UUID)ex.getKey());
            v.put("Road", ((CompoundTag)ex.getValue()).copy());
            deps.add(v);
         });
         t.put("Dependencies", deps);
         return t;
      }

      static StreetTaskStore.Task load(CompoundTag t) {
         StreetTaskStore.Task task = new StreetTaskStore.Task(
            t.getUUID("Id"), t.getUUID("Owner"), StreetPlanCodec.read(t.getCompound("Input")), t.getLong("Created")
         );
         task.terrain = StreetPlanCodec.terrain(t.getCompound("Terrain"));
         task.state = StreetTaskStore.State.valueOf(t.getString("State"));
         task.message = t.getString("Message");
         task.progress = t.getInt("Progress");
         task.readChunks = t.getInt("ReadChunks");
         task.undoCursor = t.getInt("UndoCursor");
         task.undoIntent = t.getBoolean("UndoIntent") || task.state == StreetTaskStore.State.UNDOING;
         task.conflicts = t.getInt("Conflicts");
         task.updated = t.getLong("Updated");
         task.peakUnitMicros = t.getLong("PeakUnitMicros");
         task.totalUnitMicros = t.getLong("TotalUnitMicros");
         task.roads = t.getList("Roads", 10).stream().map(vx -> RoadRecord.load((CompoundTag)vx)).toList();
         task.centers = t.getList("Centers", 10).stream().map(vx -> ((CompoundTag)vx).copy()).toList();

         for (Tag v : t.getList("Journals", 10)) {
            task.journals.add(StreetJournal.load((CompoundTag)v));
         }

         long[] keys = t.getLongArray("Fingerprints");
         if (keys.length % 2 != 0) {
            throw new IllegalStateException("路网地形校验记录损坏");
         } else {
            for (int i = 0; i < keys.length; i += 2) {
               task.fingerprints.put(keys[i], keys[i + 1]);
            }

            for (Tag v : t.getList("Dependencies", 10)) {
               CompoundTag d = (CompoundTag)v;
               task.dependencies.put(d.getUUID("Id"), d.getCompound("Road").copy());
            }

            if (task.roads.size() <= 160 && task.progress >= 0 && task.progress <= task.roads.size() && task.journals.size() <= 160) {
               if (task.state == StreetTaskStore.State.BUILDING || task.state == StreetTaskStore.State.READING || task.state == StreetTaskStore.State.UNDOING) {
                  task.state = StreetTaskStore.State.PAUSED;
                  task.message = "服务器已重载；任务暂停，已完成部分保留，可继续或撤销";
               }

               return task;
            } else {
               throw new IllegalStateException("路网任务进度损坏，已停止恢复");
            }
         }
      }
   }
}
