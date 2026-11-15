package com.sora.splineroads.world;

import com.mojang.logging.LogUtils;
import com.sora.splineroads.net.RoadNetwork;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap.Types;

final class ManualPlannerTerrain implements AutoCloseable {
   private static final TicketType<UUID> TICKET = TicketType.create("sr_planner_map", UUID::compareTo);
   private final ServerPlayer player;
   private final ServerLevel level;
   private final UUID session;
   private final ArrayDeque<ChunkPos> waiting = new ArrayDeque<>();
   private final Set<Long> sampled = new HashSet<>();
   private final Map<ChunkPos, Long> pending = new LinkedHashMap<>();
   private int radius = 256;
   private int total;
   private double centerX;
   private double centerZ;
   private boolean closed;
   final Map<Long, CompoundTag> nodes = new HashMap<>();

   ManualPlannerTerrain(ServerPlayer p, UUID id) {
      this.player = p;
      this.level = p.serverLevel();
      this.session = id;
      this.configure(256);
   }

   int radius() {
      return this.radius;
   }

   int completed() {
      return this.sampled.size();
   }

   int total() {
      return this.total;
   }

   void configure(int value) {
      if (value >= 16 && value <= 2048) {
         this.radius = value;
         this.centerX = this.player.getX();
         this.centerZ = this.player.getZ();
         this.waiting.clear();
         ArrayList<ChunkPos> next = new ArrayList<>();
         this.sampled.removeIf(key -> {
            ChunkPos c = new ChunkPos(key);
            return Math.hypot((double)(c.x * 16 + 8) - this.centerX, (double)(c.z * 16 + 8) - this.centerZ) > 2080.0;
         });
         this.nodes.keySet().removeIf(key -> {
            BlockPos p = BlockPos.of(key);
            return Math.hypot((double)p.getX() - this.centerX, (double)p.getZ() - this.centerZ) > 2080.0;
         });

         for (int x = (int)Math.floor(this.centerX - (double)this.radius) >> 4; x <= (int)Math.floor(this.centerX + (double)this.radius) >> 4; x++) {
            for (int z = (int)Math.floor(this.centerZ - (double)this.radius) >> 4; z <= (int)Math.floor(this.centerZ + (double)this.radius) >> 4; z++) {
               ChunkPos pos = new ChunkPos(x, z);
               double dx = Math.max(0.0, Math.max((double)(x * 16) - this.centerX, this.centerX - (double)(x * 16 + 16)));
               double dz = Math.max(0.0, Math.max((double)(z * 16) - this.centerZ, this.centerZ - (double)(z * 16 + 16)));
               if (dx * dx + dz * dz <= (double)this.radius * (double)this.radius
                  && this.level.getWorldBorder().isWithinBounds(pos)
                  && !this.sampled.contains(pos.toLong())) {
                  next.add(pos);
               }
            }
         }

         next.sort(Comparator.comparingDouble(p -> Math.pow((double)(p.x * 16 + 8) - this.centerX, 2.0) + Math.pow((double)(p.z * 16 + 8) - this.centerZ, 2.0)));
         this.waiting.addAll(next);
         this.total = this.sampled.size() + next.size();
      } else {
         throw new IllegalArgumentException("地表加载半径应为16–2048格");
      }
   }

   void changed(Set<Long> chunks) {
      for (Entry<Long, CompoundTag> e : this.nodes.entrySet()) {
         BlockPos pos = BlockPos.of(e.getKey());
         if (chunks.contains(new ChunkPos(pos).toLong()) && this.level.hasChunkAt(pos)) {
            if (this.level.getBlockEntity(pos) instanceof NodeEntity n) {
               e.setValue(nodeTag(n));
            } else {
               e.setValue(new CompoundTag());
            }
         }
      }

      this.nodes.values().removeIf(CompoundTag::isEmpty);

      for (long key : chunks) {
         ChunkPos pos = new ChunkPos(key);
         if (!(Math.hypot((double)(pos.x * 16 + 8) - this.centerX, (double)(pos.z * 16 + 8) - this.centerZ) > 2080.0)) {
            this.sampled.remove(key);
            this.waiting.remove(pos);
            if (!this.pending.containsKey(pos)) {
               this.waiting.addFirst(pos);
            }
         }
      }

      this.total = this.sampled.size() + this.waiting.size() + this.pending.size();
   }

   static CompoundTag nodeTag(NodeEntity n) {
      CompoundTag item = new CompoundTag();
      item.putLong("Pos", n.getBlockPos().asLong());
      item.put("Node", RoadRecord.writeNode(n.node()));
      return item;
   }

   void tick() {
      if (!this.closed) {
         if (Math.hypot(this.player.getX() - this.centerX, this.player.getZ() - this.centerZ) >= 16.0) {
            this.configure(this.radius);
         }

         long end = System.nanoTime() + 3000000L;
         Iterator<Entry<ChunkPos, Long>> iterator = this.pending.entrySet().iterator();

         while (iterator.hasNext()) {
            Entry<ChunkPos, Long> entry = iterator.next();
            ChunkPos pos = entry.getKey();
            LevelChunk loaded = this.level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (loaded != null || this.level.getGameTime() - entry.getValue() >= 2400L) {
               try {
                  if (loaded != null) {
                     this.send(loaded);
                     this.sampled.add(pos.toLong());
                  } else {
                     CompoundTag t = new CompoundTag();
                     t.putString("Kind", "manualTerrain");
                     t.putUUID("PlannerSession", this.session);
                     t.putString("Error", "地表分块加载超时，可在设置中重新加载");
                     RoadNetwork.open(this.player, t);
                  }
               } catch (RuntimeException var13) {
                  LogUtils.getLogger().warn("Planner terrain tile failed at {}", pos, var13);
               } finally {
                  this.level.getChunkSource().removeRegionTicket(TICKET, pos, 0, this.session);
                  iterator.remove();
               }

               if (System.nanoTime() >= end) {
                  break;
               }
            }
         }

         int submitted = 0;

         while (this.pending.size() < 32 && !this.waiting.isEmpty() && submitted++ < 64 && System.nanoTime() < end) {
            ChunkPos pos = this.waiting.removeFirst();
            if (!this.sampled.contains(pos.toLong()) && !this.pending.containsKey(pos)) {
               LevelChunk loaded = this.level.getChunkSource().getChunkNow(pos.x, pos.z);
               if (loaded != null) {
                  try {
                     this.send(loaded);
                     this.sampled.add(pos.toLong());
                  } catch (RuntimeException var12) {
                     LogUtils.getLogger().warn("Planner terrain tile failed at {}", pos, var12);
                  }
               } else {
                  this.level.getChunkSource().addRegionTicket(TICKET, pos, 0, this.session);
                  this.pending.put(pos, this.level.getGameTime());
               }
            }
         }
      }
   }

   private void send(LevelChunk chunk) {
      int[] colors = new int[256];
      int[] heights = new int[256];
      ChunkPos origin = chunk.getPos();

      for (int z = 0; z < 16; z++) {
         for (int x = 0; x < 16; x++) {
            int y = chunk.getHeight(Types.WORLD_SURFACE, x, z);
            BlockPos pos = new BlockPos(origin.x * 16 + x, y, origin.z * 16 + z);
            int color = chunk.getBlockState(pos).getMapColor(this.level, pos).col;
            if (color == 0) {
               color = 7829367;
            }

            int north = chunk.getHeight(Types.WORLD_SURFACE, x, Math.max(0, z - 1));
            int shade = y > north ? 255 : (y < north ? 175 : 220);
            colors[z * 16 + x] = 0xFF000000 | (color & 0xFF) * shade / 255 << 16 | (color >> 8 & 0xFF) * shade / 255 << 8 | (color >> 16 & 0xFF) * shade / 255;
            heights[z * 16 + x] = y + 1;
         }
      }

      CompoundTag t = new CompoundTag();
      t.putString("Kind", "manualTerrain");
      t.putUUID("PlannerSession", this.session);
      t.putInt("ChunkX", origin.x);
      t.putInt("ChunkZ", origin.z);
      t.putIntArray("Colors", colors);
      t.putIntArray("Heights", heights);
      t.putInt("Loaded", this.sampled.size() + 1);
      t.putInt("Total", this.total);
      this.nodes.entrySet().removeIf(e -> new ChunkPos(BlockPos.of(e.getKey())).equals(origin));
      ListTag found = new ListTag();

      for (BlockEntity be : chunk.getBlockEntities().values()) {
         if (be instanceof NodeEntity n) {
            CompoundTag item = new CompoundTag();
            item.putLong("Pos", n.getBlockPos().asLong());
            item.put("Node", RoadRecord.writeNode(n.node()));
            this.nodes.put(n.getBlockPos().asLong(), item);
            found.add(item);
         }
      }

      t.put("Nodes", found);
      RoadNetwork.open(this.player, t);
   }

   private void releasePending() {
      for (ChunkPos pos : this.pending.keySet()) {
         this.level.getChunkSource().removeRegionTicket(TICKET, pos, 0, this.session);
      }

      this.pending.clear();
   }

   @Override
   public void close() {
      this.closed = true;
      this.releasePending();
      this.waiting.clear();
      this.sampled.clear();
      this.nodes.clear();
   }
}
