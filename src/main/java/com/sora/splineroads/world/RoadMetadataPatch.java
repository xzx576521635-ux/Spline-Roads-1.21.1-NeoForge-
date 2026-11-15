package com.sora.splineroads.world;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

record RoadMetadataPatch<T>(Map<UUID, T> expected, Map<UUID, T> updates, Set<UUID> removed) {
   static <T> RoadMetadataPatch<T> between(Map<UUID, T> before, Map<UUID, T> after, UnaryOperator<T> snapshot) {
      LinkedHashMap<UUID, T> expected = new LinkedHashMap<>();
      LinkedHashMap<UUID, T> updates = new LinkedHashMap<>();
      LinkedHashSet<UUID> removed = new LinkedHashSet<>();
      LinkedHashSet<UUID> keys = new LinkedHashSet<>(before.keySet());
      keys.addAll(after.keySet());

      for (UUID id : keys) {
         if (before.containsKey(id) != after.containsKey(id) || !Objects.equals(before.get(id), after.get(id))) {
            if (before.containsKey(id)) {
               expected.put(id, snapshot.apply(before.get(id)));
            }

            if (after.containsKey(id)) {
               updates.put(id, snapshot.apply(after.get(id)));
            } else {
               removed.add(id);
            }
         }
      }

      return new RoadMetadataPatch<>(Map.copyOf(expected), Map.copyOf(updates), Set.copyOf(removed));
   }

   void validate(Map<UUID, T> live) {
      HashSet<UUID> keys = new HashSet<>(this.updates.keySet());
      keys.addAll(this.removed);

      for (UUID id : keys) {
         if (live.containsKey(id) != this.expected.containsKey(id) || !Objects.equals(live.get(id), this.expected.get(id))) {
            throw new IllegalArgumentException("相关道路元数据已改变，请重新预览");
         }
      }
   }

   void apply(Map<UUID, T> live) {
      this.removed.forEach(live::remove);
      live.putAll(this.updates);
   }
}
