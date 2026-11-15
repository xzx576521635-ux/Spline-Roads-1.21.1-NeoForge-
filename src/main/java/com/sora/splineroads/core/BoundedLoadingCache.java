package com.sora.splineroads.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.ToLongFunction;

final class BoundedLoadingCache<K, V> {
   private final Map<K, BoundedLoadingCache.Entry<V>> values = new LinkedHashMap<>(16, 0.75F, true);
   private final Map<K, CompletableFuture<V>> pending = new HashMap<>();
   private final int capacity;
   private final long budget;
   private final ToLongFunction<V> weight;
   private long bytes;

   BoundedLoadingCache(int capacity, long budget, ToLongFunction<V> weight) {
      this.capacity = capacity;
      this.budget = budget;
      this.weight = weight;
   }

   V get(K key, Function<K, V> factory) {
      CompletableFuture<V> future;
      boolean owner;
      synchronized (this) {
         BoundedLoadingCache.Entry<V> hit = this.values.get(key);
         if (hit != null) {
            return hit.value();
         }

         future = this.pending.get(key);
         owner = future == null;
         if (owner) {
            future = new CompletableFuture<>();
            this.pending.put(key, future);
         }
      }

      if (!owner) {
         try {
            return future.join();
         } catch (CompletionException var15) {
            if (var15.getCause() instanceof RuntimeException runtime) {
               throw runtime;
            } else if (var15.getCause() instanceof Error error) {
               throw error;
            } else {
               throw var15;
            }
         }
      } else {
         try {
            V result = Objects.requireNonNull(factory.apply(key));
            long size = Math.max(1L, this.weight.applyAsLong(result));
            synchronized (this) {
               if (size <= this.budget) {
                  this.values.put(key, new BoundedLoadingCache.Entry<>(result, size));
                  this.bytes += size;
                  Iterator<Map.Entry<K, BoundedLoadingCache.Entry<V>>> iterator = this.values.entrySet().iterator();

                  while (this.values.size() > this.capacity || this.bytes > this.budget) {
                     this.bytes = this.bytes - iterator.next().getValue().bytes();
                     iterator.remove();
                  }
               }
            }

            future.complete(result);
            synchronized (this) {
               this.pending.remove(key);
            }

            return result;
         } catch (Error | RuntimeException var17) {
            future.completeExceptionally(var17);
            synchronized (this) {
               this.pending.remove(key);
            }

            throw var17;
         }
      }
   }

   private static record Entry<V>(V value, long bytes) {
   }
}
