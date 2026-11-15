package com.sora.splineroads.core;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;

public final class ImmutableOrderedMap<K extends Comparable<? super K>, V> extends AbstractMap<K, V> {
   private final ImmutableOrderedMap.Node<K, ImmutableOrderedMap.Item<K, V>> lookup;
   private final ImmutableOrderedMap.Node<Long, ImmutableOrderedMap.Item<K, V>> ordered;
   private final long nextOrder;

   private ImmutableOrderedMap(
      ImmutableOrderedMap.Node<K, ImmutableOrderedMap.Item<K, V>> lookup,
      ImmutableOrderedMap.Node<Long, ImmutableOrderedMap.Item<K, V>> ordered,
      long nextOrder
   ) {
      this.lookup = lookup;
      this.ordered = ordered;
      this.nextOrder = nextOrder;
   }

   public static <K extends Comparable<? super K>, V> ImmutableOrderedMap<K, V> empty() {
      return new ImmutableOrderedMap<>(null, null, 0L);
   }

   @Override
   public int size() {
      return size(this.lookup);
   }

   private ImmutableOrderedMap.Item<K, V> item(Object key) {
      if (!(key instanceof Comparable)) {
         return null;
      } else {
         try {
            return find(this.lookup, (K)key);
         } catch (ClassCastException var3) {
            return null;
         }
      }
   }

   @Override
   public V get(Object key) {
      ImmutableOrderedMap.Item<K, V> found = this.item(key);
      return found == null ? null : found.value();
   }

   @Override
   public boolean containsKey(Object key) {
      return this.item(key) != null;
   }

   public ImmutableOrderedMap<K, V> with(K key, V value) {
      Objects.requireNonNull(key);
      Objects.requireNonNull(value);
      ImmutableOrderedMap.Item<K, V> before = find(this.lookup, key);
      if (before != null && before.value() == value) {
         return this;
      } else if (before == null && this.nextOrder == Long.MAX_VALUE) {
         throw new IllegalStateException("snapshot insertion sequence exhausted");
      } else {
         ImmutableOrderedMap.Item<K, V> item = new ImmutableOrderedMap.Item<>(before == null ? this.nextOrder : before.order(), key, value);
         return new ImmutableOrderedMap<>(
            put(this.lookup, key, item), put(this.ordered, item.order(), item), before == null ? this.nextOrder + 1L : this.nextOrder
         );
      }
   }

   public ImmutableOrderedMap<K, V> without(K key) {
      Objects.requireNonNull(key);
      ImmutableOrderedMap.Item<K, V> before = find(this.lookup, key);
      if (before == null) {
         return this;
      } else {
         return this.size() == 1 ? empty() : new ImmutableOrderedMap<>(remove(this.lookup, key), remove(this.ordered, before.order()), this.nextOrder);
      }
   }

   @Override
   public Set<Entry<K, V>> entrySet() {
      return new AbstractSet<Entry<K, V>>() {
         @Override
         public int size() {
            return ImmutableOrderedMap.this.size();
         }

         @Override
         public Iterator<Entry<K, V>> iterator() {
            return new Iterator<Entry<K, V>>() {
               final Deque<ImmutableOrderedMap.Node<Long, ImmutableOrderedMap.Item<K, V>>> stack = new ArrayDeque<>();

               {
                  this.push(ImmutableOrderedMap.this.ordered);
               }

               private void push(ImmutableOrderedMap.Node<Long, ImmutableOrderedMap.Item<K, V>> node) {
                  while (node != null) {
                     this.stack.push(node);
                     node = node.left;
                  }
               }

               @Override
               public boolean hasNext() {
                  return !this.stack.isEmpty();
               }

               public Entry<K, V> next() {
                  if (this.stack.isEmpty()) {
                     throw new NoSuchElementException();
                  } else {
                     ImmutableOrderedMap.Node<Long, ImmutableOrderedMap.Item<K, V>> node = this.stack.pop();
                     this.push(node.right);
                     return node.value;
                  }
               }
            };
         }
      };
   }

   private static int height(ImmutableOrderedMap.Node<?, ?> n) {
      return n == null ? 0 : n.height;
   }

   private static int size(ImmutableOrderedMap.Node<?, ?> n) {
      return n == null ? 0 : n.size;
   }

   private static <K extends Comparable<? super K>, V> V find(ImmutableOrderedMap.Node<K, V> node, K key) {
      while (node != null) {
         int c = key.compareTo(node.key);
         if (c == 0) {
            return node.value;
         }

         node = c < 0 ? node.left : node.right;
      }

      return null;
   }

   private static <K extends Comparable<? super K>, V> ImmutableOrderedMap.Node<K, V> put(ImmutableOrderedMap.Node<K, V> node, K key, V value) {
      if (node == null) {
         return new ImmutableOrderedMap.Node<>(key, value, null, null);
      } else {
         int c = key.compareTo(node.key);
         return c == 0
            ? new ImmutableOrderedMap.Node<>(key, value, node.left, node.right)
            : balance(
               c < 0
                  ? new ImmutableOrderedMap.Node<>(node.key, node.value, put(node.left, key, value), node.right)
                  : new ImmutableOrderedMap.Node<>(node.key, node.value, node.left, put(node.right, key, value))
            );
      }
   }

   private static <K extends Comparable<? super K>, V> ImmutableOrderedMap.Node<K, V> remove(ImmutableOrderedMap.Node<K, V> node, K key) {
      if (node == null) {
         return null;
      } else {
         int c = key.compareTo(node.key);
         if (c < 0) {
            return balance(new ImmutableOrderedMap.Node<>(node.key, node.value, remove(node.left, key), node.right));
         } else if (c > 0) {
            return balance(new ImmutableOrderedMap.Node<>(node.key, node.value, node.left, remove(node.right, key)));
         } else if (node.left == null) {
            return node.right;
         } else if (node.right == null) {
            return node.left;
         } else {
            ImmutableOrderedMap.Node<K, V> successor = node.right;

            while (successor.left != null) {
               successor = successor.left;
            }

            return balance(new ImmutableOrderedMap.Node<>(successor.key, successor.value, node.left, remove(node.right, successor.key)));
         }
      }
   }

   private static <K, V> ImmutableOrderedMap.Node<K, V> balance(ImmutableOrderedMap.Node<K, V> node) {
      int delta = height(node.left) - height(node.right);
      if (delta > 1) {
         ImmutableOrderedMap.Node<K, V> left = node.left;
         if (height(left.left) < height(left.right)) {
            node = new ImmutableOrderedMap.Node<>(node.key, node.value, rotateLeft(left), node.right);
         }

         return rotateRight(node);
      } else if (delta < -1) {
         ImmutableOrderedMap.Node<K, V> right = node.right;
         if (height(right.right) < height(right.left)) {
            node = new ImmutableOrderedMap.Node<>(node.key, node.value, node.left, rotateRight(right));
         }

         return rotateLeft(node);
      } else {
         return node;
      }
   }

   private static <K, V> ImmutableOrderedMap.Node<K, V> rotateLeft(ImmutableOrderedMap.Node<K, V> node) {
      ImmutableOrderedMap.Node<K, V> right = node.right;
      return new ImmutableOrderedMap.Node<>(right.key, right.value, new ImmutableOrderedMap.Node<>(node.key, node.value, node.left, right.left), right.right);
   }

   private static <K, V> ImmutableOrderedMap.Node<K, V> rotateRight(ImmutableOrderedMap.Node<K, V> node) {
      ImmutableOrderedMap.Node<K, V> left = node.left;
      return new ImmutableOrderedMap.Node<>(left.key, left.value, left.left, new ImmutableOrderedMap.Node<>(node.key, node.value, left.right, node.right));
   }

   private static record Item<K, V>(long order, K key, V value) implements Entry<K, V> {
      @Override
      public K getKey() {
         return this.key;
      }

      @Override
      public V getValue() {
         return this.value;
      }

      @Override
      public V setValue(V ignored) {
         throw new UnsupportedOperationException("immutable snapshot");
      }

      @Override
      public boolean equals(Object o) {
         if (o instanceof Entry<?, ?> e && Objects.equals(this.key, e.getKey()) && Objects.equals(this.value, e.getValue())) {
            return true;
         }

         return false;
      }

      @Override
      public int hashCode() {
         return Objects.hashCode(this.key) ^ Objects.hashCode(this.value);
      }
   }

   private static final class Node<K, V> {
      final K key;
      final V value;
      final ImmutableOrderedMap.Node<K, V> left;
      final ImmutableOrderedMap.Node<K, V> right;
      final int height;
      final int size;

      Node(K key, V value, ImmutableOrderedMap.Node<K, V> left, ImmutableOrderedMap.Node<K, V> right) {
         this.key = key;
         this.value = value;
         this.left = left;
         this.right = right;
         this.height = 1 + Math.max(ImmutableOrderedMap.height(left), ImmutableOrderedMap.height(right));
         this.size = 1 + ImmutableOrderedMap.size(left) + ImmutableOrderedMap.size(right);
      }
   }
}
