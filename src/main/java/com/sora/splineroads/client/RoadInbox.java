package com.sora.splineroads.client;

import com.sora.splineroads.net.RoadWire;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadRecord;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public final class RoadInbox {
   private final Map<RoadInbox.RoadKey, Long> deleted = new HashMap<>();
   private final Map<UUID, Long> cancelled = new HashMap<>();
   private long sequence;
   private volatile long completedSequence;
   public static final int MAX_RAW_TASKS = 256;
   public static final long MAX_RAW_BYTES = 8388608L;
   private final AtomicLong rawBytes = new AtomicLong();
   private final AtomicBoolean recoveryNeeded = new AtomicBoolean();
   private final Runnable recover;
   private final Supplier<String> currentDimension;
   private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), task -> {
      Thread t = new Thread(task, "SplineRoads network decoder");
      t.setDaemon(true);
      return t;
   });
   private final ArrayBlockingQueue<RoadInbox.Ready> ready = new ArrayBlockingQueue<>(64);
   private final RoadWire.Assembler controls = new RoadWire.Assembler();
   private volatile long epoch;
   private volatile RoadInbox.Session session = new RoadInbox.Session();
   private volatile long decodeNanos;
   private volatile long duplicateHits;
   private volatile long receivedBytes;
   private volatile long recoveryCount;
   private volatile long rejectedParts;
   private volatile long maxQueueNanos;
   private long lastRecoveryNanos;

   public RoadInbox() {
      this(() -> {
      });
   }

   public RoadInbox(Runnable recover) {
      this(recover, () -> "");
   }

   public RoadInbox(Runnable recover, Supplier<String> dimension) {
      this.recover = Objects.requireNonNull(recover);
      this.currentDimension = Objects.requireNonNull(dimension);
   }

   public String metrics() {
      return "inbox="
         + this.backlog()
         + " rawKiB="
         + this.rawBytes.get() / 1024L
         + " queueMaxMs="
         + this.maxQueueNanos / 1000000L
         + " reused="
         + this.duplicateHits
         + " decodeMs="
         + this.decodeNanos / 1000000L
         + " wireMiB="
         + this.receivedBytes / 1048576L
         + " resync="
         + this.recoveryCount
         + " rejected="
         + this.rejectedParts;
   }

   private static String digest(byte[] data) {
      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
      } catch (NoSuchAlgorithmException var2) {
         throw new IllegalStateException(var2);
      }
   }

   public int backlog() {
      return this.worker.getQueue().size() + this.ready.size() + this.worker.getActiveCount();
   }

   public long rawBacklogBytes() {
      return this.rawBytes.get();
   }

   public int partialCount() {
      return this.session.assembler.pendingCount();
   }

   public void close() {
      this.reset();
      this.worker.shutdownNow();
   }

   public void reset() {
      this.epoch++;
      this.session = new RoadInbox.Session();
      ArrayList<Runnable> abandoned = new ArrayList<>();
      this.worker.getQueue().drainTo(abandoned);

      for (Runnable task : abandoned) {
         if (task instanceof RoadInbox.RawTask raw) {
            raw.release();
         }
      }

      this.ready.clear();
      this.controls.clear();
      this.deleted.clear();
      this.cancelled.clear();
      this.recoveryNeeded.set(false);
      this.lastRecoveryNanos = 0L;
   }

   public void control(RoadWire.Fragment fragment, Consumer<RoadInbox.Event> sink) {
      try {
         CompoundTag tag = this.controls.accept(fragment, System.nanoTime());
         if (tag == null) {
            return;
         }

         String type = tag.getString("Type");
         String dimension = this.currentDimension.get();
         if (tag.contains("Dimension") && dimension != null && !dimension.isEmpty() && !dimension.equals(tag.getString("Dimension"))) {
            return;
         }

         if (type.equals("syncReset")) {
            this.reset();
            sink.accept(new RoadInbox.Event(tag, null, null));
            return;
         }

         if (type.equals("cancelTransfer")) {
            UUID transfer = tag.getUUID("Transfer");
            this.session.assembler.cancel(transfer, System.nanoTime());
            this.cancelled.put(transfer, this.sequence);
            if (this.cancelled.size() > 1024) {
               this.reset();
               this.recoveryNeeded.set(true);
            }

            return;
         }

         if (!type.equals("open") && !type.equals("result") && !type.equals("delete") && !type.equals("batchBarrier")) {
            throw new IllegalArgumentException("无效的工具界面消息");
         }

         if (type.equals("delete")) {
            this.deleted.put(new RoadInbox.RoadKey(tag.getString("Dimension"), tag.getUUID("Id")), this.sequence);
         }

         if (type.equals("batchBarrier")) {
            for (Tag entry : tag.getList("Ids", 10)) {
               this.deleted.put(new RoadInbox.RoadKey(tag.getString("Dimension"), ((CompoundTag)entry).getUUID("Id")), this.sequence);
            }
         }

         sink.accept(new RoadInbox.Event(tag, null, null));
      } catch (IllegalArgumentException var8) {
         this.controls.clear();
         this.recoveryNeeded.set(true);
         sink.accept(new RoadInbox.Event(null, null, "工具消息读取失败：" + var8.getMessage()));
      }
   }

   public void submit(RoadWire.Fragment fragment) {
      long generation = this.epoch;
      long serial = ++this.sequence;

      long held;
      do {
         held = this.rawBytes.get();
         if (held + (long)fragment.bytes().length > 8388608L) {
            this.rejectedParts++;
            this.recoveryNeeded.set(true);
            return;
         }
      } while (!this.rawBytes.compareAndSet(held, held + (long)fragment.bytes().length));

      RoadInbox.RawTask task = new RoadInbox.RawTask(fragment, generation, serial, this.session);

      try {
         this.worker.execute(task);
      } catch (RejectedExecutionException var10) {
         task.release();
         this.rejectedParts++;
         this.recoveryNeeded.set(true);
      }
   }

   private void decode(RoadInbox.RawTask task) {
      long generation = task.generation;
      long serial = task.serial;
      RoadInbox.Session state = task.state;
      RoadWire.Fragment fragment = task.fragment;
      if (generation == this.epoch && state == this.session) {
         this.maxQueueNanos = Math.max(this.maxQueueNanos, System.nanoTime() - task.queued);
         CompoundTag decodedTag = null;
         long first = serial;

         RoadInbox.Event result;
         try {
            this.receivedBytes = this.receivedBytes + (long)fragment.bytes().length;
            state.firstSequences.putIfAbsent(fragment.id(), Long.valueOf(serial));
            byte[] packed = state.assembler.acceptBytes(fragment, System.nanoTime());
            first = state.firstSequences.getOrDefault(fragment.id(), serial);
            state.firstSequences.keySet().retainAll(state.assembler.pendingIds());
            if (state.assembler.takeDiscarded() > 0 && generation == this.epoch) {
               this.recoveryNeeded.set(true);
            }

            if (packed == null) {
               this.completedSequence = serial;
               return;
            }

            String hash = digest(packed);
            RoadInbox.Decoded reused = state.decoded.get(hash);
            if (reused != null) {
               this.duplicateHits++;
               result = reused.event();
            } else {
               long started = System.nanoTime();
               CompoundTag tag = RoadWire.decode(packed);
               decodedTag = tag;
               RoadIndex.Built built = null;
               if (tag.getString("Type").equals("road")) {
                  built = new RoadIndex.Built(RoadRecord.load(tag.getCompound("Road")), true);
                  built.prepareCollision();
               }

               ArrayList<RoadIndex.Built> batch = new ArrayList<>();
               if (tag.getString("Type").equals("roadBatch")) {
                  for (Tag entry : tag.getList("Roads", 10)) {
                     RoadIndex.Built part = new RoadIndex.Built(RoadRecord.load((CompoundTag)entry), true);
                     part.prepareCollision();
                     batch.add(part);
                  }
               }

               RoadPreviewData preview = RoadPreviewData.queued(tag) ? RoadPreviewData.prepare(tag) : null;
               this.decodeNanos = this.decodeNanos + (System.nanoTime() - started);
               result = new RoadInbox.Event(tag, built, null, List.copyOf(batch), preview);
               if (built != null) {
                  long weight = 4096L + (long)packed.length * 8L + (long)built.mesh.samples().size() * 384L + (long)built.record.structures().size() * 512L;
                  if (weight <= 67108864L) {
                     state.decoded.put(hash, new RoadInbox.Decoded(result, weight));
                     state.decodedBytes += weight;

                     while (state.decoded.size() > 256 || state.decodedBytes > 67108864L) {
                        Iterator<Entry<String, RoadInbox.Decoded>> it = state.decoded.entrySet().iterator();
                        Entry<String, RoadInbox.Decoded> oldest = it.next();
                        state.decodedBytes = state.decodedBytes - oldest.getValue().bytes();
                        it.remove();
                     }
                  }
               }
            }
         } catch (RuntimeException var26) {
            state.assembler.clear();
            state.firstSequences.clear();
            if (decodedTag != null && RoadPreviewData.queued(decodedTag)) {
               result = new RoadInbox.Event(
                  RoadPreviewData.failed(decodedTag, "预览读取失败，请重新预览：" + (var26.getMessage() == null ? var26.getClass().getSimpleName() : var26.getMessage())),
                  null,
                  null
               );
            } else {
               if (generation == this.epoch) {
                  this.recoveryNeeded.set(true);
               }

               result = new RoadInbox.Event(
                  null, null, "道路同步失败，将自动重新同步：" + (var26.getMessage() == null ? var26.getClass().getSimpleName() : var26.getMessage())
               );
            }
         }

         try {
            while (generation == this.epoch && !this.ready.offer(new RoadInbox.Ready(generation, first, fragment.id(), result), 25L, TimeUnit.MILLISECONDS)) {
            }
         } catch (InterruptedException var25) {
            Thread.currentThread().interrupt();
         }

         this.completedSequence = serial;
      }
   }

   private boolean stale(RoadInbox.Ready ready) {
      if (!this.session.assembler.cancelled(ready.transfer()) && ready.sequence() > this.cancelled.getOrDefault(ready.transfer(), -1L)) {
         CompoundTag tag = ready.event().tag();
         if (tag == null) {
            return false;
         } else {
            if (ready.event().preview() != null) {
               for (UUID id : ready.event().preview().dependencies()) {
                  if (ready.sequence() <= this.deleted.getOrDefault(new RoadInbox.RoadKey(tag.getString("Dimension"), id), -1L)) {
                     return true;
                  }
               }
            }

            if (tag.getString("Type").equals("smartSigns")) {
               tag.getList("Signs", 10)
                  .removeIf(
                     value -> {
                        CompoundTag sign = (CompoundTag)value;
                        return sign.hasUUID("Target")
                           && ready.sequence() <= this.deleted.getOrDefault(new RoadInbox.RoadKey(tag.getString("Dimension"), sign.getUUID("Target")), -1L);
                     }
                  );
            }

            if (tag.getString("Type").equals("roadBatch")) {
               for (RoadIndex.Built built : ready.event().batch()) {
                  if (ready.sequence() <= this.deleted.getOrDefault(new RoadInbox.RoadKey(tag.getString("Dimension"), built.record.id()), -1L)) {
                     this.recoveryNeeded.set(true);
                     return true;
                  }
               }
            }

            UUID road = ready.event().built() != null ? ready.event().built().record.id() : (tag.hasUUID("Id") ? tag.getUUID("Id") : null);
            return road == null ? false : ready.sequence() <= this.deleted.getOrDefault(new RoadInbox.RoadKey(tag.getString("Dimension"), road), -1L);
         }
      } else {
         return true;
      }
   }

   public int drain(Consumer<RoadInbox.Event> sink) {
      long deadline = System.nanoTime() + 2000000L;
      int count = 0;

      while (count < 8 && System.nanoTime() < deadline) {
         RoadInbox.Ready next = this.ready.poll();
         if (next == null) {
            break;
         }

         if (next.epoch == this.epoch) {
            RoadInbox.Event event = next.event();
            if (!this.stale(next)) {
               sink.accept(event);
               count++;
            } else if (event.preview() != null) {
               sink.accept(new RoadInbox.Event(RoadPreviewData.failed(event.tag(), "预览所用道路已改变，请重新预览"), null, null));
               count++;
            }
         }
      }

      RoadInbox.Session active = this.session;
      active.assembler.expire(System.nanoTime());
      if (active.assembler.takeDiscarded() > 0) {
         this.recoveryNeeded.set(true);
      }

      long now = System.nanoTime();
      if (this.recoveryNeeded.get() && this.backlog() < 16 && now - this.lastRecoveryNanos >= 2000000000L) {
         this.lastRecoveryNanos = now;
         this.recoveryCount++;
         this.recover.run();
      }

      if (this.ready.isEmpty() && this.worker.getActiveCount() == 0 && this.worker.getQueue().isEmpty() && active.assembler.pendingCount() == 0) {
         this.deleted.values().removeIf(cutoff -> this.completedSequence >= cutoff);
         this.cancelled.values().removeIf(cutoff -> this.completedSequence >= cutoff);
      }

      return count;
   }

   private static record Decoded(RoadInbox.Event event, long bytes) {
   }

   public static record Event(CompoundTag tag, RoadIndex.Built built, String error, List<RoadIndex.Built> batch, RoadPreviewData preview) {
      public Event(CompoundTag tag, RoadIndex.Built built, String error) {
         this(tag, built, error, List.of(), null);
      }

      public Event(CompoundTag tag, RoadIndex.Built built, String error, List<RoadIndex.Built> batch) {
         this(tag, built, error, batch, null);
      }
   }

   private final class RawTask implements Runnable {
      final RoadWire.Fragment fragment;
      final long generation;
      final long serial;
      final long queued = System.nanoTime();
      final RoadInbox.Session state;
      private final AtomicBoolean released = new AtomicBoolean();

      RawTask(RoadWire.Fragment fragment, long generation, long serial, RoadInbox.Session state) {
         this.fragment = fragment;
         this.generation = generation;
         this.serial = serial;
         this.state = state;
      }

      void release() {
         if (this.released.compareAndSet(false, true)) {
            RoadInbox.this.rawBytes.addAndGet((long)(-this.fragment.bytes().length));
         }
      }

      @Override
      public void run() {
         try {
            RoadInbox.this.decode(this);
         } finally {
            this.release();
         }
      }
   }

   private static record Ready(long epoch, long sequence, UUID transfer, RoadInbox.Event event) {
   }

   private static record RoadKey(String dimension, UUID road) {
   }

   private static final class Session {
      final RoadWire.Assembler assembler = new RoadWire.Assembler();
      final LinkedHashMap<String, RoadInbox.Decoded> decoded = new LinkedHashMap<>(16, 0.75F, true);
      final LinkedHashMap<UUID, Long> firstSequences = new LinkedHashMap<>();
      long decodedBytes;
   }
}
