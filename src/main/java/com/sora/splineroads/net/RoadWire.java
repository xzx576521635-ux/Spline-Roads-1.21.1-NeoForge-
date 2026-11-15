package com.sora.splineroads.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

public final class RoadWire {
   public static final int PART_BYTES = 32768;
   public static final int MAX_PARTS = 128;
   public static final int MAX_RAW = 16777216;

   public static List<RoadWire.Fragment> split(CompoundTag tag) {
      try {
         ByteArrayOutputStream raw = new ByteArrayOutputStream();
         NbtIo.write(tag, new DataOutputStream(new RoadWire.LimitedOutput(raw, 16777216L, "道路编辑数据过大，请减少同一端点附近的道路数量")));
         ByteArrayOutputStream packed = new ByteArrayOutputStream();
         GZIPOutputStream gzip = new GZIPOutputStream(new RoadWire.LimitedOutput(packed, 4194304L, "道路同步数据过大"));

         try {
            raw.writeTo(gzip);
         } catch (Throwable var9) {
            try {
               gzip.close();
            } catch (Throwable var8) {
               var9.addSuppressed(var8);
            }

            throw var9;
         }

         gzip.close();
         byte[] bytes = packed.toByteArray();
         int count = (bytes.length + 32768 - 1) / 32768;
         if (count > 128) {
            throw new IllegalArgumentException("道路同步数据过大");
         } else {
            UUID id = UUID.randomUUID();
            List<RoadWire.Fragment> parts = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
               parts.add(new RoadWire.Fragment(id, i, count, Arrays.copyOfRange(bytes, i * 32768, Math.min(bytes.length, (i + 1) * 32768))));
            }

            return parts;
         }
      } catch (IOException var10) {
         throw new IllegalArgumentException("道路数据编码失败", var10);
      }
   }

   public static List<RoadWire.Fragment> reidentify(List<RoadWire.Fragment> parts) {
      UUID id = UUID.randomUUID();
      return parts.stream().map(p -> new RoadWire.Fragment(id, p.index(), p.count(), p.bytes())).toList();
   }

   public static CompoundTag decode(byte[] packed) {
      try {
         ByteArrayOutputStream raw = new ByteArrayOutputStream();

         try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            byte[] block = new byte[8192];

            int n;
            while ((n = gzip.read(block)) != -1) {
               if (Thread.currentThread().isInterrupted()) {
                  throw new CancellationException("道路解码已取消");
               }

               if (raw.size() + n > 16777216) {
                  throw new IOException("inflated road data too large");
               }

               raw.write(block, 0, n);
            }
         }

         return NbtIo.read(new DataInputStream(new ByteArrayInputStream(raw.toByteArray())), NbtAccounter.create(33554432L));
      } catch (CancellationException var7) {
         throw var7;
      } catch (RuntimeException | IOException var8) {
         throw new IllegalArgumentException("道路同步数据损坏", var8);
      }
   }

   private RoadWire() {
   }

   public static final class Assembler {
      public static final int MAX_PENDING = 16;
      public static final int MAX_BYTES = 16777216;
      private static final long EXPIRY = 30000000000L;
      private final Map<UUID, RoadWire.Pending> pending = new LinkedHashMap<>();
      private final Map<UUID, Long> cancelled = new LinkedHashMap<>();
      private int bytes;
      private int discarded;

      public synchronized void clear() {
         this.pending.clear();
         this.cancelled.clear();
         this.bytes = this.discarded = 0;
      }

      public synchronized int retainedBytes() {
         return this.bytes;
      }

      public synchronized int pendingCount() {
         return this.pending.size();
      }

      public synchronized Set<UUID> pendingIds() {
         return Set.copyOf(this.pending.keySet());
      }

      public synchronized boolean cancelled(UUID id) {
         return this.cancelled.containsKey(id);
      }

      public synchronized int takeDiscarded() {
         int n = this.discarded;
         this.discarded = 0;
         return n;
      }

      public synchronized void cancel(UUID id, long now) {
         RoadWire.Pending old = this.pending.remove(id);
         if (old != null) {
            this.bytes = this.bytes - old.bytes;
         }

         this.cancelled.put(id, now);

         while (this.cancelled.size() > 1024) {
            this.cancelled.remove(this.cancelled.keySet().iterator().next());
         }
      }

      public synchronized void expire(long now) {
         Iterator<RoadWire.Pending> it = this.pending.values().iterator();

         while (it.hasNext()) {
            RoadWire.Pending old = it.next();
            if (now - old.created > 30000000000L) {
               this.bytes = this.bytes - old.bytes;
               it.remove();
               this.discarded++;
            }
         }

         this.cancelled.values().removeIf(t -> now - t > 30000000000L);
      }

      private void evict(UUID keep) {
         Iterator<Entry<UUID, RoadWire.Pending>> it = this.pending.entrySet().iterator();

         while (it.hasNext()) {
            Entry<UUID, RoadWire.Pending> entry = it.next();
            if (!entry.getKey().equals(keep)) {
               this.bytes = this.bytes - entry.getValue().bytes;
               it.remove();
               this.discarded++;
               return;
            }
         }

         throw new IllegalArgumentException("道路分包超出组装预算");
      }

      public CompoundTag accept(RoadWire.Fragment part, long now) {
         byte[] packed = this.acceptBytes(part, now);
         return packed == null ? null : RoadWire.decode(packed);
      }

      public synchronized byte[] acceptBytes(RoadWire.Fragment part, long now) {
         this.expire(now);
         if (this.cancelled.containsKey(part.id())) {
            return null;
         } else {
            if (!this.pending.containsKey(part.id()) && this.pending.size() >= 16) {
               this.evict(null);
            }

            RoadWire.Pending p = this.pending.computeIfAbsent(part.id(), id -> new RoadWire.Pending(part.count(), now));
            if (p.parts.length != part.count()) {
               throw new IllegalArgumentException("道路分包数量不一致");
            } else {
               if (p.parts[part.index()] != null) {
                  if (!Arrays.equals(p.parts[part.index()], part.bytes())) {
                     throw new IllegalArgumentException("道路重复分包内容不一致");
                  }
               } else {
                  while ((long)this.bytes + (long)part.bytes().length > 16777216L) {
                     this.evict(part.id());
                  }

                  p.parts[part.index()] = part.bytes();
                  p.received++;
                  p.bytes = p.bytes + part.bytes().length;
                  this.bytes = this.bytes + part.bytes().length;
               }

               if (p.received != p.parts.length) {
                  return null;
               } else {
                  this.pending.remove(part.id());
                  this.bytes = this.bytes - p.bytes;
                  byte[] packed = new byte[p.bytes];
                  int offset = 0;

                  for (byte[] block : p.parts) {
                     System.arraycopy(block, 0, packed, offset, block.length);
                     offset += block.length;
                  }

                  return packed;
               }
            }
         }
      }
   }

   public static record Fragment(UUID id, int index, int count, byte[] bytes) {
      public Fragment(UUID id, int index, int count, byte[] bytes) {
         if (id != null && count >= 1 && count <= 128 && index >= 0 && index < count && bytes != null && bytes.length >= 1 && bytes.length <= 32768) {
            this.id = id;
            this.index = index;
            this.count = count;
            this.bytes = bytes;
         } else {
            throw new IllegalArgumentException("无效的道路数据分包");
         }
      }
   }

   private static final class LimitedOutput extends OutputStream {
      private final OutputStream out;
      private final long limit;
      private final String message;
      private long written;
      private long checked;

      LimitedOutput(OutputStream out, long limit, String message) {
         this.out = out;
         this.limit = limit;
         this.message = message;
      }

      private void reserve(int count) {
         if (count >= 0 && this.written + (long)count <= this.limit) {
            if (this.written == 0L || this.written + (long)count - this.checked >= 8192L) {
               if (Thread.currentThread().isInterrupted()) {
                  throw new CancellationException("道路编码已取消");
               }

               this.checked = this.written + (long)count;
            }

            this.written += (long)count;
         } else {
            throw new IllegalArgumentException(this.message);
         }
      }

      @Override
      public void write(int value) throws IOException {
         this.reserve(1);
         this.out.write(value);
      }

      @Override
      public void write(byte[] data, int offset, int length) throws IOException {
         Objects.checkFromIndexSize(offset, length, data.length);
         this.reserve(length);
         this.out.write(data, offset, length);
      }

      @Override
      public void flush() throws IOException {
         this.out.flush();
      }
   }

   private static final class Pending {
      final long created;
      final byte[][] parts;
      int received;
      int bytes;

      Pending(int count, long now) {
         this.parts = new byte[count][];
         this.created = now;
      }
   }
}
