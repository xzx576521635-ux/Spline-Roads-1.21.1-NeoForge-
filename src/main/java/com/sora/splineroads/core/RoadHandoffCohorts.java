package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

public final class RoadHandoffCohorts<K> {
   private final Map<UUID, RoadHandoffCohorts.Group<K>> groups = new HashMap<>();

   public Set<UUID> begin(Collection<UUID> members, Collection<K> previousKeys) {
      RoadHandoffCohorts.Group<K> next = new RoadHandoffCohorts.Group<>();
      next.members.addAll(members);
      next.keys.addAll(previousKeys);
      Set<RoadHandoffCohorts.Group<K>> previous = Collections.newSetFromMap(new IdentityHashMap<>());

      for (UUID member : members) {
         RoadHandoffCohorts.Group<K> group = this.groups.get(member);
         if (group != null) {
            previous.add(group);
         }
      }

      for (RoadHandoffCohorts.Group<K> group : previous) {
         next.members.addAll(group.members);
         next.keys.addAll(group.keys);
      }

      for (UUID memberx : next.members) {
         this.groups.put(memberx, next);
      }

      return Set.copyOf(next.members);
   }

   public void include(UUID member, K key) {
      RoadHandoffCohorts.Group<K> group = this.groups.get(member);
      if (group != null) {
         group.keys.add(key);
      }
   }

   public void installed(Collection<UUID> members) {
      Set<RoadHandoffCohorts.Group<K>> changed = Collections.newSetFromMap(new IdentityHashMap<>());

      for (UUID member : members) {
         RoadHandoffCohorts.Group<K> group = this.groups.get(member);
         if (group != null) {
            changed.add(group);
         }
      }

      for (RoadHandoffCohorts.Group<K> group : changed) {
         group.installed = true;
         this.retire(group);
      }
   }

   public boolean ready(K key, BiPredicate<UUID, K> memberReady) {
      ArrayList<RoadHandoffCohorts.Group<K>> touching = new ArrayList<>();

      for (RoadHandoffCohorts.Group<K> group : new HashSet<>(this.groups.values())) {
         if (group.keys.contains(key)) {
            if (!group.installed) {
               return false;
            }

            for (UUID member : group.members) {
               if (!memberReady.test(member, key)) {
                  return false;
               }
            }

            touching.add(group);
         }
      }

      for (RoadHandoffCohorts.Group<K> groupx : touching) {
         groupx.keys.remove(key);
         this.retire(groupx);
      }

      return true;
   }

   public Set<K> keys() {
      Set<K> keys = new LinkedHashSet<>();

      for (RoadHandoffCohorts.Group<K> group : this.groups.values()) {
         keys.addAll(group.keys);
      }

      return Set.copyOf(keys);
   }

   public Set<UUID> members() {
      return Set.copyOf(this.groups.keySet());
   }

   public Set<UUID> members(K key) {
      Set<UUID> ids = new HashSet<>();

      for (RoadHandoffCohorts.Group<K> group : this.groups.values()) {
         if (group.keys.contains(key)) {
            ids.addAll(group.members);
         }
      }

      return Set.copyOf(ids);
   }

   public boolean contains(UUID member) {
      return this.groups.containsKey(member);
   }

   public int size() {
      return new HashSet<>(this.groups.values()).size();
   }

   public void clear() {
      this.groups.clear();
   }

   private void retire(RoadHandoffCohorts.Group<K> group) {
      if (group.installed && group.keys.isEmpty()) {
         for (UUID member : group.members) {
            this.groups.remove(member, group);
         }
      }
   }

   private static final class Group<K> {
      final Set<UUID> members = new LinkedHashSet<>();
      final Set<K> keys = new LinkedHashSet<>();
      boolean installed;
   }
}
