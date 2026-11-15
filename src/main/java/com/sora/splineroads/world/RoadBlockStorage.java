package com.sora.splineroads.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

final class RoadBlockStorage {
   static ListTag write(Long2ObjectOpenHashMap<BlockState> blocks) {
      Map<BlockState, RoadBlockStorage.Group> groups = new IdentityHashMap<>();
      ObjectIterator<Entry<BlockState>> entries = blocks.long2ObjectEntrySet().fastIterator();

      while (entries.hasNext()) {
         groups.computeIfAbsent((BlockState)((Entry)entries.next()).getValue(), s -> new RoadBlockStorage.Group()).count++;
      }

      for (RoadBlockStorage.Group group : groups.values()) {
         group.positions = new long[group.count];
      }

      entries = blocks.long2ObjectEntrySet().fastIterator();

      while (entries.hasNext()) {
         Entry<BlockState> entry = (Entry<BlockState>)entries.next();
         RoadBlockStorage.Group group = groups.get(entry.getValue());
         group.positions[group.cursor++] = entry.getLongKey();
      }

      ListTag result = new ListTag();
      groups.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().toString())).forEach(e -> {
         CompoundTag tag = new CompoundTag();
         tag.put("State", NbtUtils.writeBlockState(e.getKey()));
         tag.putLongArray("Positions", e.getValue().positions);
         result.add(tag);
      });
      return result;
   }

   static void read(ListTag palette, Long2ObjectOpenHashMap<BlockState> blocks) {
      long count = 0L;

      for (Tag value : palette) {
         count += (long)((CompoundTag)value).getLongArray("Positions").length;
      }

      if (count > 2147483639L) {
         throw new IllegalStateException("Too many road restoration blocks");
      } else {
         for (Tag value : palette) {
            CompoundTag tag = (CompoundTag)value;
            if (!tag.contains("State", 10) || !tag.contains("Positions", 12)) {
               throw new IllegalStateException("Incomplete road restoration palette");
            }

            BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("State"));

            for (long pos : tag.getLongArray("Positions")) {
               if (blocks.put(pos, state) != null) {
                  throw new IllegalStateException("Duplicate road restoration position");
               }
            }
         }
      }
   }

   private RoadBlockStorage() {
   }

   private static final class Group {
      int count;
      int cursor;
      long[] positions;
   }
}
