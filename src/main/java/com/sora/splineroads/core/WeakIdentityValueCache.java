package com.sora.splineroads.core;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.ToIntFunction;

public final class WeakIdentityValueCache<K, V> {
   private final int maxEntries;
   private final long maxWeight;
   private final ToIntFunction<V> weigh;
   private final ReferenceQueue<K> keys = new ReferenceQueue<>();
   private final ReferenceQueue<V> results = new ReferenceQueue<>();
   private final LinkedHashMap<WeakIdentityValueCache.Key<K>, WeakIdentityValueCache.Value<K, V>> entries = new LinkedHashMap<>(64, 0.75F, true);
   private long weight;
   private long hits;
   private long misses;
   private long epoch;

   public WeakIdentityValueCache(int maxEntries, long maxWeight, ToIntFunction<V> weigh) {
      if (maxEntries >= 1 && maxWeight >= 1L) {
         this.maxEntries = maxEntries;
         this.maxWeight = maxWeight;
         this.weigh = Objects.requireNonNull(weigh);
      } else {
         throw new IllegalArgumentException("Invalid cache budget");
      }
   }

   public V get(K key, Function<K, V> factory) {
      WeakIdentityValueCache.Key<K> lookup = new WeakIdentityValueCache.Key<>(key, null);
      long generation;
      synchronized (this) {
         this.reap();
         V prior = this.live(lookup);
         if (prior != null) {
            this.hits++;
            return prior;
         }

         this.misses++;
         generation = this.epoch;
      }

      V created = Objects.requireNonNull(factory.apply(key));
      int size = Math.max(1, this.weigh.applyAsInt(created));
      synchronized (this) {
         if (generation != this.epoch) {
            return created;
         } else {
            this.reap();
            V prior = this.live(lookup);
            if (prior != null) {
               return prior;
            } else if ((long)size > this.maxWeight) {
               return created;
            } else {
               WeakIdentityValueCache.Key<K> stored = new WeakIdentityValueCache.Key<>(key, this.keys);
               this.entries.put(stored, new WeakIdentityValueCache.Value<>(stored, created, size, this.results));
               this.weight += (long)size;
               Iterator<Entry<WeakIdentityValueCache.Key<K>, WeakIdentityValueCache.Value<K, V>>> iterator = this.entries.entrySet().iterator();

               while (iterator.hasNext() && (this.entries.size() > this.maxEntries || this.weight > this.maxWeight)) {
                  this.weight = this.weight - (long)iterator.next().getValue().weight;
                  iterator.remove();
               }

               return created;
            }
         }
      }
   }

   private V live(WeakIdentityValueCache.Key<K> key) {
      WeakIdentityValueCache.Value<K, V> entry = this.entries.get(key);
      if (entry == null) {
         return null;
      } else {
         V value = entry.get();
         if (value == null) {
            this.entries.remove(key);
            this.weight = this.weight - (long)entry.weight;
         }

         return value;
      }
   }

   private void reap() {
      for (Reference<? extends K> dead = this.keys.poll(); dead != null; dead = this.keys.poll()) {
         WeakIdentityValueCache.Value<K, V> entry = this.entries.remove(dead);
         if (entry != null) {
            this.weight = this.weight - (long)entry.weight;
         }
      }

      for (Reference<? extends V> deadx = this.results.poll(); deadx != null; deadx = this.results.poll()) {
         WeakIdentityValueCache.Value<K, V> entry = (WeakIdentityValueCache.Value<K, V>)deadx;
         if (this.entries.remove(entry.key, entry)) {
            this.weight = this.weight - (long)entry.weight;
         }
      }
   }

   public synchronized void clear() {
      this.epoch++;
      this.entries.clear();
      this.weight = 0L;

      while (this.keys.poll() != null) {
      }

      while (this.results.poll() != null) {
      }
   }

   public synchronized WeakIdentityCache.Stats stats() {
      this.reap();
      return new WeakIdentityCache.Stats(this.entries.size(), this.weight, this.hits, this.misses);
   }

   private static final class Key<K> extends WeakReference<K> {
      private final int hash;

      Key(K key, ReferenceQueue<K> queue) {
         super(key, queue);
         this.hash = System.identityHashCode(key);
      }

      @Override
      public int hashCode() {
         return this.hash;
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) {
            return true;
         } else {
            if (other instanceof WeakIdentityValueCache.Key<?> key && this.get() != null && this.get() == key.get()) {
               return true;
            }

            return false;
         }
      }
   }

   private static final class Value<K, V> extends WeakReference<V> {
      final WeakIdentityValueCache.Key<K> key;
      final int weight;

      Value(WeakIdentityValueCache.Key<K> key, V value, int weight, ReferenceQueue<V> queue) {
         super(value, queue);
         this.key = key;
         this.weight = weight;
      }
   }
}
