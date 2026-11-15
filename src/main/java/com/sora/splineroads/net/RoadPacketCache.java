package com.sora.splineroads.net;

import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.world.SmartSignCodec;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

public final class RoadPacketCache {
   public static final int MAX_JOBS = 4;
   public static final int MAX_ENTRIES = 128;
   public static final int MAX_BYTES = 33554432;
   private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), r -> {
      Thread t = new Thread(r, "SR road encoder");
      t.setDaemon(true);
      return t;
   });
   private final LinkedHashMap<RoadPacketCache.Key, RoadPacketCache.Entry> entries = new LinkedHashMap<>(16, 0.75F, true);
   private final Set<RoadPacketCache.EncodeJob> jobs = ConcurrentHashMap.newKeySet();
   private final AtomicInteger outstanding = new AtomicInteger();
   private int retainedBytes;
   private volatile long encodings;
   private volatile long signEncodings;
   private volatile long generation;

   public CompletableFuture<List<RoadWire.Fragment>> request(String dimension, RoadRecord record) {
      return this.request(new RoadPacketCache.Key(dimension, record.id(), false), record, () -> {
         CompoundTag tag = new CompoundTag();
         tag.putString("Type", "road");
         tag.putString("Dimension", dimension);
         tag.put("Road", record.save());
         return tag;
      });
   }

   public CompletableFuture<List<RoadWire.Fragment>> requestSigns(String dimension, UUID id, long version, List<SmartSigns.State> states) {
      List<SmartSigns.State> snapshot = List.copyOf(states);
      return this.request(new RoadPacketCache.Key(dimension, id, true), version, () -> {
         this.signEncodings++;
         CompoundTag tag = new CompoundTag();
         tag.putString("Type", "smartSigns");
         tag.putString("Dimension", dimension);
         tag.putUUID("Id", id);
         ListTag list = new ListTag();

         for (SmartSigns.State state : snapshot) {
            if (state.mode() != SmartSigns.Mode.NORMAL) {
               list.add(SmartSignCodec.write(state));
            }
         }

         tag.put("Signs", list);
         return tag;
      });
   }

   private CompletableFuture<List<RoadWire.Fragment>> request(RoadPacketCache.Key key, Object version, Supplier<CompoundTag> snapshot) {
      this.trim();
      RoadPacketCache.Entry found = this.entries.get(key);
      if (found == null || found.version != version && (!key.signs() || !found.version.equals(version))) {
         CompletableFuture<List<RoadWire.Fragment>> future = this.requestTag(snapshot);
         if (future == null) {
            return null;
         } else {
            if (found != null) {
               this.retainedBytes = this.retainedBytes - found.bytes;
               this.entries.remove(key);
            }

            this.entries.put(key, new RoadPacketCache.Entry(version, future));
            return future;
         }
      } else {
         return found.future;
      }
   }

   public CompletableFuture<List<RoadWire.Fragment>> requestTag(CompoundTag snapshot) {
      return this.requestTag((Supplier<CompoundTag>)(() -> snapshot));
   }

   public CompletableFuture<List<RoadWire.Fragment>> requestTag(Supplier<CompoundTag> snapshot) {
      if (this.outstanding.get() >= 4) {
         return null;
      } else {
         RoadPacketCache.EncodeJob job = new RoadPacketCache.EncodeJob(this.generation, snapshot);
         this.outstanding.incrementAndGet();
         this.jobs.add(job);

         try {
            this.worker.execute(job);
            this.encodings++;
            return job.result;
         } catch (RejectedExecutionException var4) {
            job.cancel(false);
            return null;
         }
      }
   }

   private void trim() {
      for (RoadPacketCache.Entry entry : this.entries.values()) {
         if (entry.bytes == 0 && entry.future.isDone() && !entry.future.isCompletedExceptionally()) {
            entry.bytes = entry.future.join().stream().mapToInt(f -> f.bytes().length).sum();
            this.retainedBytes = this.retainedBytes + entry.bytes;
         }
      }

      Iterator<Map.Entry<RoadPacketCache.Key, RoadPacketCache.Entry>> it = this.entries.entrySet().iterator();

      while (it.hasNext() && (this.retainedBytes > 33554432 || this.entries.size() >= 128)) {
         RoadPacketCache.Entry entryx = it.next().getValue();
         if (entryx.future.isDone()) {
            this.retainedBytes = this.retainedBytes - entryx.bytes;
            it.remove();
         }
      }
   }

   public int retainedBytes() {
      this.trim();
      return this.retainedBytes;
   }

   public int jobs() {
      return this.outstanding.get();
   }

   public long encodings() {
      return this.encodings;
   }

   public long signEncodings() {
      return this.signEncodings;
   }

   public void clear() {
      this.generation++;
      this.entries.clear();
      this.retainedBytes = 0;

      for (RoadPacketCache.EncodeJob job : List.copyOf(this.jobs)) {
         job.cancel(true);
      }

      this.worker.purge();
   }

   private final class EncodeJob extends FutureTask<List<RoadWire.Fragment>> {
      final CompletableFuture<List<RoadWire.Fragment>> result = new CompletableFuture<>();
      final long submittedGeneration;

      EncodeJob(final long generation, final Supplier<CompoundTag> supplier) {
         super(() -> {
            if (generation == RoadPacketCache.this.generation && !Thread.currentThread().isInterrupted()) {
               CompoundTag tag = supplier.get();
               if (generation == RoadPacketCache.this.generation && !Thread.currentThread().isInterrupted()) {
                  return List.copyOf(RoadWire.split(tag));
               } else {
                  throw new CancellationException();
               }
            } else {
               throw new CancellationException();
            }
         });
         this.submittedGeneration = generation;
      }

      @Override
      protected void done() {
         RoadPacketCache.this.jobs.remove(this);
         RoadPacketCache.this.outstanding.decrementAndGet();
         if (this.submittedGeneration != RoadPacketCache.this.generation) {
            this.result.cancel(false);
         } else {
            try {
               List<RoadWire.Fragment> fragments = this.get();
               if (this.submittedGeneration != RoadPacketCache.this.generation) {
                  this.result.cancel(false);
               } else {
                  this.result.complete(fragments);
               }
            } catch (CancellationException var2) {
               this.result.cancel(false);
            } catch (ExecutionException var3) {
               this.result.completeExceptionally(var3.getCause());
            } catch (InterruptedException var4) {
               Thread.currentThread().interrupt();
               this.result.completeExceptionally(var4);
            }
         }
      }
   }

   private static final class Entry {
      final Object version;
      final CompletableFuture<List<RoadWire.Fragment>> future;
      int bytes;

      Entry(Object version, CompletableFuture<List<RoadWire.Fragment>> future) {
         this.version = version;
         this.future = future;
      }
   }

   private static record Key(String dimension, UUID id, boolean signs) {
   }
}
