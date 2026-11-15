package com.sora.splineroads.world;

import com.sora.splineroads.core.RailbedOptions;
import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RoadGeometry;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

public final class RailbedStore {
   public final Map<UUID, RailbedStore.Entry> entries = new LinkedHashMap<>();
   final Map<UUID, RailbedStore.Pending> pending = new LinkedHashMap<>();

   CompoundTag save() {
      CompoundTag out = new CompoundTag();
      out.putInt("Version", 2);
      ListTag values = new ListTag();
      this.entries.values().forEach(e -> values.add(e.save()));
      out.put("Entries", values);
      ListTag journal = new ListTag();
      this.pending.values().forEach(p -> journal.add(p.save()));
      out.put("Pending", journal);
      return out;
   }

   void load(CompoundTag tag) {
      if (tag.getInt("Version") > 2) {
         throw new IllegalStateException("铁路存档版本过新");
      } else {
         for (Tag raw : tag.getList("Entries", 10)) {
            RailbedStore.Entry e = RailbedStore.Entry.load((CompoundTag)raw);
            this.entries.put(e.road.id(), e);
         }

         for (Tag raw : tag.getList("Pending", 10)) {
            RailbedStore.Pending p = RailbedStore.Pending.load((CompoundTag)raw);
            this.pending.put(p.id, p);
         }
      }
   }

   public static final class Entry {
      public final RoadRecord road;
      public final List<RailbedPath.Path> paths;
      public final double width;
      public final RoadGeometry.Structure mode;
      public final RailbedOptions options;
      public final Long2ObjectOpenHashMap<BlockState> original = new Long2ObjectOpenHashMap();
      public final Long2ObjectOpenHashMap<BlockState> written = new Long2ObjectOpenHashMap();

      public Entry(RoadRecord road, List<RailbedPath.Path> paths, double width, RoadGeometry.Structure mode) {
         this(road, paths, width, mode, RailbedOptions.DEFAULT);
      }

      public Entry(RoadRecord road, List<RailbedPath.Path> paths, double width, RoadGeometry.Structure mode, RailbedOptions options) {
         this.road = road;
         this.paths = List.copyOf(paths);
         this.width = width;
         this.mode = mode;
         this.options = options;
      }

      public String identity() {
         return identity(this.paths);
      }

      public static String identity(List<RailbedPath.Path> paths) {
         return paths.stream().map(p -> p.segments().stream().map(RailbedPath.Segment::id).sorted().toList().toString()).sorted().toList().toString();
      }

      CompoundTag save() {
         CompoundTag t = new CompoundTag();
         t.put("Road", this.road.save());
         ListTag rails = new ListTag();
         this.paths.forEach(p -> rails.add(RailbedCodec.write(p)));
         t.put("Paths", rails);
         t.putDouble("Width", this.width);
         t.putString("Mode", this.mode.name());
         t.put("Options", RailbedCodec.options(this.options));
         t.put("Original", RoadBlockStorage.write(this.original));
         t.put("Written", RoadBlockStorage.write(this.written));
         return t;
      }

      static RailbedStore.Entry load(CompoundTag t) {
         RailbedStore.Entry e = new RailbedStore.Entry(
            RoadRecord.load(t.getCompound("Road")),
            t.getList("Paths", 10).stream().map(v -> RailbedCodec.read((CompoundTag)v)).toList(),
            t.getDouble("Width"),
            RoadGeometry.Structure.valueOf(t.getString("Mode")),
            RailbedCodec.options(t.getCompound("Options"))
         );
         RoadBlockStorage.read(t.getList("Original", 10), e.original);
         RoadBlockStorage.read(t.getList("Written", 10), e.written);
         if (!e.original.keySet().equals(e.written.keySet())) {
            throw new IllegalStateException("铁路恢复清单不完整");
         } else {
            return e;
         }
      }
   }

   public static final class Pending {
      final UUID id;
      final RailbedStore.Entry old;
      final RailbedStore.Entry next;
      final Long2ObjectOpenHashMap<BlockState> before;
      final Long2ObjectOpenHashMap<BlockState> after;

      Pending(UUID id, RailbedStore.Entry old, RailbedStore.Entry next, Long2ObjectOpenHashMap<BlockState> before, Long2ObjectOpenHashMap<BlockState> after) {
         this.id = id;
         this.old = old;
         this.next = next;
         this.before = before;
         this.after = after;
      }

      CompoundTag save() {
         CompoundTag t = new CompoundTag();
         t.putUUID("Id", this.id);
         if (this.old != null) {
            t.put("Old", this.old.save());
         }

         if (this.next != null) {
            t.put("Next", this.next.save());
         }

         t.put("Before", RoadBlockStorage.write(this.before));
         t.put("After", RoadBlockStorage.write(this.after));
         return t;
      }

      static RailbedStore.Pending load(CompoundTag t) {
         Long2ObjectOpenHashMap<BlockState> before = new Long2ObjectOpenHashMap();
         Long2ObjectOpenHashMap<BlockState> after = new Long2ObjectOpenHashMap();
         RoadBlockStorage.read(t.getList("Before", 10), before);
         RoadBlockStorage.read(t.getList("After", 10), after);
         if (!before.keySet().equals(after.keySet())) {
            throw new IllegalStateException("铁路事务日志不完整");
         } else {
            return new RailbedStore.Pending(
               t.getUUID("Id"),
               t.contains("Old") ? RailbedStore.Entry.load(t.getCompound("Old")) : null,
               t.contains("Next") ? RailbedStore.Entry.load(t.getCompound("Next")) : null,
               before,
               after
            );
         }
      }
   }
}
