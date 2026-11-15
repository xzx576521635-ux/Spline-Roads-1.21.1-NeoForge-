package com.sora.splineroads.world;

import com.sora.splineroads.core.ImmutableOrderedMap;
import com.sora.splineroads.core.RoadGeometry;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Map.Entry;
import java.util.function.Function;

final class RoadPlanningSnapshot {
   private final ImmutableOrderedMap<UUID, RoadPlanningSnapshot.Source> sources;
   private final ImmutableOrderedMap<UUID, RoadRecord> tolls;
   private final RoadPlanningSnapshot.Records records;
   private final Map<RoadRecord, RoadGeometry.Mesh> seeds;

   private RoadPlanningSnapshot(final ImmutableOrderedMap<UUID, RoadPlanningSnapshot.Source> sources, ImmutableOrderedMap<UUID, RoadRecord> tolls) {
      this.sources = sources;
      this.tolls = tolls;
      this.records = new RoadPlanningSnapshot.Records();
      this.seeds = Collections.unmodifiableMap(new AbstractMap<RoadRecord, RoadGeometry.Mesh>() {
         public RoadGeometry.Mesh get(Object key) {
            if (!(key instanceof RoadRecord r)) {
               return null;
            } else {
               RoadPlanningSnapshot.Source source = sources.get(r.id());
               return source != null && source.record() == r ? source.mesh() : null;
            }
         }

         @Override
         public boolean containsKey(Object key) {
            return this.get(key) != null;
         }

         @Override
         public int size() {
            return sources.size();
         }

         @Override
         public Set<Entry<RoadRecord, RoadGeometry.Mesh>> entrySet() {
            return RoadPlanningSnapshot.this.mappedEntries(s -> new SimpleImmutableEntry<>(s.record(), s.mesh()));
         }
      });
   }

   static RoadPlanningSnapshot empty() {
      return new RoadPlanningSnapshot(ImmutableOrderedMap.empty(), ImmutableOrderedMap.empty());
   }

   RoadPlanningSnapshot with(RoadIndex.Built built) {
      UUID id = built.record.id();
      RoadPlanningSnapshot.Source old = this.sources.get(id);
      return old != null && old.record() == built.record && old.mesh() == built.mesh
         ? this
         : new RoadPlanningSnapshot(
            this.sources.with(id, new RoadPlanningSnapshot.Source(built.record, built.mesh)),
            built.record.toll() == null ? this.tolls.without(id) : this.tolls.with(id, built.record)
         );
   }

   RoadPlanningSnapshot without(UUID id) {
      ImmutableOrderedMap<UUID, RoadPlanningSnapshot.Source> next = this.sources.without(id);
      return next == this.sources ? this : new RoadPlanningSnapshot(next, this.tolls.without(id));
   }

   RoadPlanningSnapshot changed(Set<UUID> removed, Collection<RoadIndex.Built> built) {
      RoadPlanningSnapshot next = this;

      for (UUID id : removed) {
         next = next.without(id);
      }

      for (RoadIndex.Built road : built) {
         next = next.with(road);
      }

      return next;
   }

   Map<UUID, RoadRecord> records() {
      return this.records;
   }

   Map<RoadRecord, RoadGeometry.Mesh> seeds() {
      return this.seeds;
   }

   int size() {
      return this.sources.size();
   }

   private <K, V> Set<Entry<K, V>> mappedEntries(final Function<RoadPlanningSnapshot.Source, Entry<K, V>> map) {
      return Collections.unmodifiableSet(new AbstractSet<Entry<K, V>>() {
         @Override
         public int size() {
            return RoadPlanningSnapshot.this.sources.size();
         }

         @Override
         public Iterator<Entry<K, V>> iterator() {
            final Iterator<RoadPlanningSnapshot.Source> iterator = RoadPlanningSnapshot.this.sources.values().iterator();
            return new Iterator<Entry<K, V>>() {
               @Override
               public boolean hasNext() {
                  return iterator.hasNext();
               }

               public Entry<K, V> next() {
                  return map.apply(iterator.next());
               }
            };
         }
      });
   }

   final class Records extends AbstractMap<UUID, RoadRecord> {
      Collection<RoadRecord> tolls() {
         return RoadPlanningSnapshot.this.tolls.values();
      }

      public RoadRecord get(Object id) {
         RoadPlanningSnapshot.Source source = RoadPlanningSnapshot.this.sources.get(id);
         return source == null ? null : source.record();
      }

      @Override
      public boolean containsKey(Object id) {
         return RoadPlanningSnapshot.this.sources.containsKey(id);
      }

      @Override
      public int size() {
         return RoadPlanningSnapshot.this.sources.size();
      }

      @Override
      public Set<Entry<UUID, RoadRecord>> entrySet() {
         return RoadPlanningSnapshot.this.mappedEntries(s -> new SimpleImmutableEntry<>(s.record().id(), s.record()));
      }
   }

   private static record Source(RoadRecord record, RoadGeometry.Mesh mesh) {
   }
}
