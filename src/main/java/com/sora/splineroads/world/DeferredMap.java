package com.sora.splineroads.world;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Supplier;

final class DeferredMap<K, V> extends AbstractMap<K, V> {
   private final Supplier<Map<K, V>> source;

   DeferredMap(Supplier<Map<K, V>> source) {
      this.source = source;
   }

   @Override
   public Set<Entry<K, V>> entrySet() {
      return this.source.get().entrySet();
   }

   @Override
   public V get(Object key) {
      return this.source.get().get(key);
   }

   @Override
   public boolean containsKey(Object key) {
      return this.source.get().containsKey(key);
   }

   @Override
   public int size() {
      return this.source.get().size();
   }
}
