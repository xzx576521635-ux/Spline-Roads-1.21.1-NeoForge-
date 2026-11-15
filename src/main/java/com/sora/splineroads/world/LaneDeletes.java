package com.sora.splineroads.world;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class LaneDeletes {
   static Set<UUID> junctionDependencies(RoadData data, UUID group) {
      HashSet<UUID> seeds = new HashSet<>();
      data.index.roads.values().stream().filter(b -> group.equals(b.record.assembly())).forEach(b -> seeds.add(b.record.id()));
      CompoundTag c = data.junctions.get(group);
      if (c != null && c.getBoolean("Auto") && !c.getBoolean("GeneratedRing")) {
         long pos = c.getLong("CenterPos");
         data.streets.values().stream().filter(r -> r.a().asLong() == pos || r.b().asLong() == pos).forEach(r -> seeds.add(r.id()));
      }

      return LaneTopology.dependents(data, seeds);
   }

   static void junctionPayload(RoadData data, UUID group, CompoundTag t) {
      ListTag list = new ListTag();
      junctionDependencies(data, group).stream().sorted().forEach(v -> list.add(StringTag.valueOf(v.toString())));
      t.put("Dependencies", list);
   }

   public static void removeJunction(ServerLevel level, ServerPlayer player, CompoundTag t) {
      RoadData data = RoadData.get(level);
      UUID group = t.getUUID("Id");
      Set<UUID> deps = junctionDependencies(data, group);
      requireConfirmation(t, deps);
      UUID representative = data.index
         .roads
         .values()
         .stream()
         .filter(b -> group.equals(b.record.assembly()))
         .map(b -> b.record.id())
         .findFirst()
         .orElseThrow(() -> new IllegalArgumentException("路口已不存在"));
      data.removeWithDependents(level, player, representative, deps);
   }

   public static void payload(RoadData data, UUID id, CompoundTag t) {
      ListTag list = new ListTag();
      LaneTopology.dependents(data, Set.of(id)).stream().sorted().forEach(v -> list.add(StringTag.valueOf(v.toString())));
      t.put("Dependencies", list);
   }

   public static void requireConfirmation(CompoundTag t, Set<UUID> dependencies) {
      if (!dependencies.isEmpty()) {
         HashSet<UUID> confirmed = new HashSet<>();

         for (Tag value : t.getList("ConfirmDependencies", 8)) {
            try {
               confirmed.add(UUID.fromString(value.getAsString()));
            } catch (IllegalArgumentException var6) {
               throw new IllegalArgumentException("依赖确认无效");
            }
         }

         if (!confirmed.equals(dependencies)) {
            throw new IllegalArgumentException("依赖匝道已变化，请重新打开删除界面，确认级联删除清单");
         }
      }
   }

   public static void remove(ServerLevel level, ServerPlayer player, CompoundTag t) {
      RoadData data = RoadData.get(level);
      UUID id = t.getUUID("Id");
      Set<UUID> dependent = LaneTopology.dependents(data, Set.of(id));
      requireConfirmation(t, dependent);
      if (dependent.isEmpty()) {
         data.remove(level, player, id);
      } else {
         RoadIndex.Built target = data.index.roads.get(id);
         if (target == null) {
            throw new IllegalArgumentException("道路已不存在");
         } else {
            data.removeWithDependents(level, player, id, dependent);
         }
      }
   }

   private LaneDeletes() {
   }
}
