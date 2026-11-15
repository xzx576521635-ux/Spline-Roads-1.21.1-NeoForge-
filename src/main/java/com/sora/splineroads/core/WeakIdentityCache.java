package com.sora.splineroads.core;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;

public final class WeakIdentityCache<K, V> {
   private final int maxEntries;
   private final long maxWeight;
   private final ToIntFunction<V> weigh;
   private final ReferenceQueue<K> queue = new ReferenceQueue<>();
   private final LinkedHashMap<WeakIdentityCache.Key<K>, WeakIdentityCache.Entry<V>> values = new LinkedHashMap<>(64, 0.75F, true);
   private long weight;
   private long hits;
   private long misses;
   private long epoch;

   public WeakIdentityCache(int maxEntries, long maxWeight, ToIntFunction<V> weigh) {
      if (maxEntries >= 1 && maxWeight >= 1L) {
         this.maxEntries = maxEntries;
         this.maxWeight = maxWeight;
         this.weigh = Objects.requireNonNull(weigh);
      } else {
         throw new IllegalArgumentException("Invalid cache budget");
      }
   }

   public V get(K key, Function<K, V> factory) {
      WeakIdentityCache.Key<K> lookup = new WeakIdentityCache.Key<>(key, null);
      long generation;
      synchronized (this) {
         this.reap();
         WeakIdentityCache.Entry<V> old = this.values.get(lookup);
         if (old != null) {
            this.hits++;
            return old.value();
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
            WeakIdentityCache.Entry<V> old = this.values.get(lookup);
            if (old != null) {
               return old.value();
            } else if ((long)size > this.maxWeight) {
               return created;
            } else {
               this.values.put(new WeakIdentityCache.Key<>(key, this.queue), new WeakIdentityCache.Entry<>(created, size));
               this.weight += (long)size;
               Iterator<Map.Entry<WeakIdentityCache.Key<K>, WeakIdentityCache.Entry<V>>> it = this.values.entrySet().iterator();

               while (it.hasNext() && (this.values.size() > this.maxEntries || this.weight > this.maxWeight)) {
                  this.weight = this.weight - (long)it.next().getValue().weight();
                  it.remove();
               }

               return created;
            }
         }
      }
   }

   private void reap() {
      for (Reference<? extends K> dead = this.queue.poll(); dead != null; dead = this.queue.poll()) {
         WeakIdentityCache.Entry<V> old = this.values.remove(dead);
         if (old != null) {
            this.weight = this.weight - (long)old.weight();
         }
      }
   }

   public synchronized void clear() {
      this.epoch++;
      this.values.clear();
      this.weight = 0L;

      while (this.queue.poll() != null) {
      }
   }

   public synchronized WeakIdentityCache.Stats stats() {
      this.reap();
      return new WeakIdentityCache.Stats(this.values.size(), this.weight, this.hits, this.misses);
   }

   private static record Entry<V>(V value, int weight) {
   }

   private static final class Key<K> extends WeakReference<K> {
      final int hash;

      Key(K value, ReferenceQueue<K> queue) {
         super(value, queue);
         this.hash = System.identityHashCode(value);
      }

      @Override
      public int hashCode() {
         return this.hash;
      }

      @Override
      public boolean equals(Object value) {
         if (this == value) {
            return true;
         } else {
            if (value instanceof WeakIdentityCache.Key<?> key && this.get() != null && this.get() == key.get()) {
               return true;
            }

            return false;
         }
      }
   }

   public static record Stats(int entries, long weight, long hits, long misses) {
   }
}
