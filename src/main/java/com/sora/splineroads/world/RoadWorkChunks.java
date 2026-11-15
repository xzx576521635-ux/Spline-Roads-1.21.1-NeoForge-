package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadCoverage;
import com.sora.splineroads.core.RoadGeometry;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

public final class RoadWorkChunks implements AutoCloseable {
   private static final TicketType<UUID> TICKET = TicketType.create("splineroads_work", UUID::compareTo);
   private static final Map<ServerLevel, RoadWorkChunks.State> ACTIVE = new IdentityHashMap<>();
   private final ServerLevel level;
   private final RoadWorkChunks.State state;
   private boolean closed;

   private RoadWorkChunks(ServerLevel level) {
      if (!level.getServer().isSameThread()) {
         throw new IllegalStateException("Road work must run on the server thread");
      } else {
         this.level = level;
         this.state = ACTIVE.computeIfAbsent(level, ignored -> new RoadWorkChunks.State());
         this.state.users++;
      }
   }

   public static RoadWorkChunks open(ServerLevel level) {
      return new RoadWorkChunks(level);
   }

   static Set<Long> held(ServerLevel level) {
      RoadWorkChunks.State state = ACTIVE.get(level);
      return state == null ? Set.of() : Set.copyOf(state.held);
   }

   public static int heldCount(ServerLevel level) {
      RoadWorkChunks.State state = ACTIVE.get(level);
      return state == null ? 0 : state.held.size();
   }

   static boolean terrainAvailable(ServerLevel level, BlockPos pos) {
      if (!level.getWorldBorder().isWithinBounds(pos)) {
         return false;
      } else if (level.hasChunkAt(pos)) {
         return true;
      } else if (!ACTIVE.containsKey(level)) {
         return false;
      } else {
         try (RoadWorkChunks lease = open(level)) {
            lease.load(Set.of(new ChunkPos(pos).toLong()));
         }

         return level.hasChunkAt(pos);
      }
   }

   public void multiInterchange() {
      if (this.closed) {
         throw new IllegalStateException("Road work lease is closed");
      } else {
         this.state.limit = 4096;
      }
   }

   public void node(BlockPos pos) {
      if (!this.level.isOutsideBuildHeight(pos) && this.level.getWorldBorder().isWithinBounds(pos)) {
         this.load(Set.of(new ChunkPos(pos).toLong()));
      } else {
         throw new IllegalArgumentException("端点超出世界高度或边界");
      }
   }

   public void roads(Collection<RoadIndex.Built> roads) {
      Set<Long> needed = new HashSet<>();

      for (RoadIndex.Built road : roads) {
         needed.addAll(RoadCoverage.chunks(road.mesh, road.record.settings().structure() == RoadGeometry.Structure.BRIDGE ? 4.0 : 2.0));
         needed.addAll(road.chunks);
      }

      this.load(needed);
   }

   public void load(Collection<Long> requested) {
      if (this.closed) {
         throw new IllegalStateException("Road work lease is closed");
      } else {
         Set<Long> extra = new LinkedHashSet<>(requested);
         extra.removeAll(this.state.held);
         if (this.state.held.size() + extra.size() > this.state.limit) {
            throw new IllegalArgumentException("一次道路操作涉及区块过多，请缩小范围或分段建造");
         } else {
            for (long key : extra) {
               ChunkPos chunk = new ChunkPos(key);
               if (!this.level.getWorldBorder().isWithinBounds(chunk)) {
                  throw new IllegalArgumentException("道路超出世界边界");
               }
            }

            for (long keyx : extra) {
               ChunkPos chunk = new ChunkPos(keyx);
               this.level.getChunkSource().addRegionTicket(TICKET, chunk, 0, this.state.id);
               this.state.held.add(keyx);
               this.level.getChunk(chunk.x, chunk.z);
            }
         }
      }
   }

   @Override
   public void close() {
      if (!this.closed) {
         this.closed = true;
         if (--this.state.users == 0) {
            try {
               for (long key : this.state.held) {
                  this.level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(key), 0, this.state.id);
               }
            } finally {
               ACTIVE.remove(this.level);
            }
         }
      }
   }

   private static final class State {
      final UUID id = UUID.randomUUID();
      final Set<Long> held = new LinkedHashSet<>();
      int users;
      int limit = 2048;
   }
}
