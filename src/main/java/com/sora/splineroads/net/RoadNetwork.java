package com.sora.splineroads.net;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.client.ClientRoads;
import com.sora.splineroads.client.RoadPreviewData;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadConflictIds;
import com.sora.splineroads.core.RoadCrossingFailure;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadPreferences;
import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.world.AttachedPointTool;
import com.sora.splineroads.world.AutoJunctions;
import com.sora.splineroads.world.Corridors;
import com.sora.splineroads.world.CurveControlNodes;
import com.sora.splineroads.world.ElevatedAccessRoads;
import com.sora.splineroads.world.ElevatedPointTool;
import com.sora.splineroads.world.GantryTool;
import com.sora.splineroads.world.InterchangeTool;
import com.sora.splineroads.world.Interchanges;
import com.sora.splineroads.world.JunctionRoads;
import com.sora.splineroads.world.JunctionTool;
import com.sora.splineroads.world.Junctions;
import com.sora.splineroads.world.LaneDeletes;
import com.sora.splineroads.world.LaneLineTool;
import com.sora.splineroads.world.LanePointCodec;
import com.sora.splineroads.world.LanePointTool;
import com.sora.splineroads.world.LaneRampTool;
import com.sora.splineroads.world.LaneRamps;
import com.sora.splineroads.world.LaneTopology;
import com.sora.splineroads.world.ManualPlanner;
import com.sora.splineroads.world.NodeEntity;
import com.sora.splineroads.world.OneSidedJunctions;
import com.sora.splineroads.world.RailbedTool;
import com.sora.splineroads.world.RoadData;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.world.RoadSplitter;
import com.sora.splineroads.world.RoadTool;
import com.sora.splineroads.world.RoundaboutTool;
import com.sora.splineroads.world.SmartSignCodec;
import com.sora.splineroads.world.SmartTextTool;
import com.sora.splineroads.world.TollRoads;
import com.sora.splineroads.world.TollTool;
import com.sora.splineroads.world.YJunctionTool;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.apache.logging.log4j.LogManager;

public final class RoadNetwork {
   private static final String PROTOCOL = "126";
   private static int playerCursor;
   private static int levelCursor;
   private static long sentBytes;
   private static long sentFragments;
   private static long publicationNanos;
   private static long publicationMaxNanos;
   private static long maintenanceNanos;
   private static long maintenanceMaxNanos;
   private static long resyncs;
   private static long hardResyncs;
   private static final RoadPacketCache PACKETS = new RoadPacketCache();
   private static final Map<UUID, RoadNetwork.Watching> WATCHING = new HashMap<>();
   private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();
   private static final Map<UUID, Long> LAST_PREVIEW = new HashMap<>();

   public static void register(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("126");
      registrar.playToClient(RoadNetwork.ServerMessage.TYPE, RoadNetwork.ServerMessage.CODEC, RoadNetwork.ServerMessage::handleClient);
      registrar.playToClient(RoadNetwork.ControlMessage.TYPE, RoadNetwork.ControlMessage.CODEC, RoadNetwork.ControlMessage::handleControl);
      registrar.playToServer(RoadNetwork.Action.TYPE, RoadNetwork.Action.CODEC, RoadNetwork.Action::handleServer);
   }

   public static void sendToServer(RoadNetwork.Action action) {
      PacketDistributor.sendToServer(action, new CustomPacketPayload[0]);
   }

   public static void send(ServerPlayer player, CompoundTag t) {
      if (t.getString("Type").equals("result") && !t.getList("ConflictRoads", 10).isEmpty()) {
         CompoundTag header = t.copy();
         header.remove("ConflictRoads");
         send(player, header);
         CompoundTag preview = t.copy();
         preview.putString("Type", "resultPreview");
         send(player, preview);
      } else {
         boolean control = Set.of("open", "result", "delete", "batchBarrier", "syncReset", "cancelTransfer").contains(t.getString("Type"))
            && !RoadPreviewData.queued(t);

         for (RoadWire.Fragment fragment : RoadWire.split(t)) {
            if (control) {
               PacketDistributor.sendToPlayer(player, new RoadNetwork.ControlMessage(fragment), new CustomPacketPayload[0]);
            } else {
               PacketDistributor.sendToPlayer(player, new RoadNetwork.ServerMessage(fragment), new CustomPacketPayload[0]);
            }
         }
      }
   }

   public static void open(ServerPlayer p, CompoundTag payload) {
      ManualPlanner.decorate(p, payload);
      CompoundTag t = new CompoundTag();
      t.putString("Type", "open");
      t.putString("Dimension", p.serverLevel().dimension().location().toString());
      t.put("Payload", payload);
      send(p, t);
   }

   private static RoadNetwork.Watching watching(ServerPlayer player) {
      String dimension = player.level().dimension().location().toString();
      RoadNetwork.Watching state = WATCHING.get(player.getUUID());
      if (state == null || !state.dimension.equals(dimension)) {
         state = new RoadNetwork.Watching(dimension);
         WATCHING.put(player.getUUID(), state);
      }

      return state;
   }

   public static void dimensionChanged(ServerPlayer player) {
      RoadPlanningJobs.cancel(player.getUUID());
      watching(player);
      LAST_ACTION.remove(player.getUUID());
      LAST_PREVIEW.remove(player.getUUID());
   }

   public static void watch(ServerPlayer player, long chunk, boolean added) {
      RoadNetwork.Watching state = watching(player);
      if (added) {
         state.chunks.add(chunk);
      } else {
         state.chunks.remove(chunk);
         RoadIndex index = RoadData.get(player.serverLevel()).index;

         for (UUID id : index.inChunk(chunk)) {
            RoadIndex.Built built = index.roads.get(id);
            if (built == null || Collections.disjoint(built.chunks, state.chunks)) {
               forgetTransport(player, state, id);
            }
         }
      }
   }

   public static void broadcastSmart(ServerLevel level, UUID id) {
      RoadIndex.Built built = RoadData.get(level).index.roads.get(id);

      for (ServerPlayer p : level.players()) {
         if (built != null && !Collections.disjoint(built.chunks, watching(p).chunks)) {
            watching(p).pendingSigns.add(id);
         }
      }
   }

   public static void road(ServerPlayer p, RoadRecord r) {
      RoadNetwork.Watching state = watching(p);
      if (state.batch != null && state.batch.ids.contains(r.id())) {
         if (state.batch.records.get(r.id()) != r) {
            refreshBatch(p, state, state.batch.ids, state.batch.node, null);
         }
      } else {
         RoadNetwork.PointTransfer points = state.pointTransfers.remove(r.id());
         if (points != null) {
            cancelTransfer(p, points.parts, points.next, r.id());
         }

         if (r != state.sent.get(r.id())) {
            RoadNetwork.Transfer pending = state.pending.get(r.id());
            if (pending == null || pending.record != r) {
               if (pending != null) {
                  cancelTransfer(p, pending.parts, pending.next, r.id());
               }

               state.pending.put(r.id(), new RoadNetwork.Transfer(r));
               state.pendingSigns.add(r.id());
            }
         }
      }
   }

   private static void cancelTransfer(ServerPlayer player, List<RoadWire.Fragment> parts, int next, UUID road) {
      if (parts != null && !parts.isEmpty() && next != 0) {
         CompoundTag tag = new CompoundTag();
         tag.putString("Type", "cancelTransfer");
         tag.putString("Dimension", watching(player).dimension);
         tag.putUUID("Transfer", parts.get(0).id());
         if (road != null) {
            tag.putUUID("Id", road);
         }

         send(player, tag);
      }
   }

   private static int fragment(ServerPlayer player, RoadWire.Fragment fragment) {
      PacketDistributor.sendToPlayer(player, new RoadNetwork.ServerMessage(fragment), new CustomPacketPayload[0]);
      sentBytes = sentBytes + (long)fragment.bytes().length;
      sentFragments++;
      return fragment.bytes().length;
   }

   private static boolean ready(CompletableFuture<?> value) {
      return value != null && value.isDone();
   }

   public static void tick(MinecraftServer server) {
      long maintained = System.nanoTime();
      long maintenanceDeadline = maintained + 2000000L;
      ArrayList<ServerLevel> levels = new ArrayList<>();
      server.getAllLevels().forEach(levels::add);
      if (!levels.isEmpty()) {
         int start = Math.floorMod(levelCursor, levels.size());
         int visited = 0;

         for (int offset = 0; offset < levels.size() && System.nanoTime() < maintenanceDeadline; offset++) {
            int position = (start + offset) % levels.size();
            levelCursor = (position + 1) % levels.size();
            visited++;
            ServerLevel level = levels.get(position);
            RoadData.get(level).smartSigns.tick(level, maintenanceDeadline);
         }

         if (visited == levels.size()) {
            levelCursor = (start + 1) % levels.size();
         }
      }

      long maintenance = System.nanoTime() - maintained;
      maintenanceNanos += maintenance;
      maintenanceMaxNanos = Math.max(maintenanceMaxNanos, maintenance);
      long started = System.nanoTime();

      try {
         publishPlayers(server.getPlayerList().getPlayers(), started + 1500000L);
      } finally {
         long elapsed = System.nanoTime() - started;
         publicationNanos += elapsed;
         publicationMaxNanos = Math.max(publicationMaxNanos, elapsed);
         if (Boolean.getBoolean("sr.profile") && server.getTickCount() % 200 == 0) {
            System.getLogger("SplineRoads/network").log(Level.INFO, metrics() + " " + RoadPlanningJobs.metrics());
         }
      }
   }

   static void publishPlayers(List<ServerPlayer> players, long deadline) {
      int count = players.size();
      if (count != 0) {
         int start = Math.floorMod(playerCursor, count);
         int served = 0;

         for (int offset = 0; offset < count && System.nanoTime() < deadline; offset++) {
            int position = (start + offset) % count;
            playerCursor = (position + 1) % count;
            served++;
            ServerPlayer player = players.get(position);
            RoadNetwork.Watching state = watching(player);
            long tick = player.serverLevel().getGameTime();
            if (state.lastServed != 0L) {
               state.maxWaitTicks = Math.max(state.maxWaitTicks, tick - state.lastServed);
            }

            state.lastServed = tick;
            publish(player, state, deadline, tick);
         }

         if (served == count) {
            playerCursor = (start + 1) % count;
         }
      }
   }

   private static void publish(ServerPlayer player, RoadNetwork.Watching state, long deadline, long tick) {
      RoadData data = RoadData.get(player.serverLevel());
      state.signPreparations = 0;
      state.publication.publish(deadline, (family, remaining) -> {
         return switch (family) {
            case 0 -> publishBatch(player, state, data, deadline, tick, remaining);
            case 1 -> publishPoints(player, state, data, deadline, remaining);
            case 2 -> publishSign(player, state, data, deadline, remaining);
            default -> publishRoad(player, state, data, deadline, remaining);
         };
      });
   }

   private static <T> Entry<UUID, T> next(Map<UUID, T> queued, UUID active) {
      if (active != null && queued.containsKey(active)) {
         return Map.entry(active, queued.get(active));
      } else if (queued.isEmpty()) {
         return null;
      } else {
         Iterator<Entry<UUID, T>> it = queued.entrySet().iterator();
         Entry<UUID, T> entry = it.next();
         UUID id = entry.getKey();
         T value = entry.getValue();
         it.remove();
         queued.put(id, value);
         return Map.entry(id, value);
      }
   }

   private static boolean canSend(List<RoadWire.Fragment> parts, int next, int remaining, long deadline) {
      return parts != null && next < parts.size() && parts.get(next).bytes().length <= remaining && System.nanoTime() < deadline;
   }

   private static int publishBatch(ServerPlayer player, RoadNetwork.Watching state, RoadData data, long deadline, long tick, int remaining) {
      RoadNetwork.BatchTransfer batch = state.batch;
      if (batch == null) {
         return 0;
      } else {
         long revision = data.index.contentRevision();
         boolean stale = false;
         if (batch.checkedContentRevision != revision) {
            for (UUID id : batch.ids) {
               RoadIndex.Built current = data.index.roads.get(id);
               if (batch.records.get(id) != (current == null ? null : current.record)) {
                  stale = true;
                  break;
               }
            }

            if (!stale) {
               batch.checkedContentRevision = revision;
            }
         }

         if (stale || batch.sentTick != 0L && tick - batch.sentTick > 100L) {
            refreshBatch(player, state, batch.ids, batch.node, null);
            batch = state.batch;
            if (batch == null) {
               return 0;
            }
         }

         if (batch.parts == null) {
            if (batch.encoded == null) {
               batch.encoded = PACKETS.requestTag(batch.packet);
            }

            if (!ready(batch.encoded)) {
               return 0;
            }

            try {
               batch.parts = RoadWire.reidentify(batch.encoded.join());
               batch.historyBytes = Math.max(batch.historyBytes, packedBytes(batch.parts));
            } catch (CancellationException | CompletionException var15) {
               resync(player, state, true);
               return 0;
            }
         }

         if (!canSend(batch.parts, batch.next, remaining, deadline)) {
            return 0;
         } else {
            int bytes = fragment(player, batch.parts.get(batch.next++));
            if (batch.next == batch.parts.size() && batch.sentTick == 0L) {
               batch.sentTick = tick;
            }

            return bytes;
         }
      }
   }

   static CompoundTag pointPacket(String dimension, RoadRecord before, RoadRecord after) {
      CompoundTag tag = new CompoundTag();
      tag.putString("Type", "lanePoints");
      tag.putString("Dimension", dimension);
      tag.putUUID("Id", before.id());
      tag.putInt("Signature", before.header().hashCode());
      tag.put("Points", LanePointCodec.write(LaneTopology.metadata(after)));
      return tag;
   }

   private static int publishPoints(ServerPlayer player, RoadNetwork.Watching state, RoadData data, long deadline, int remaining) {
      Entry<UUID, RoadNetwork.PointTransfer> entry = next(state.pointTransfers, state.activePoints);
      if (entry == null) {
         state.activePoints = null;
         return 0;
      } else {
         RoadNetwork.PointTransfer transfer = entry.getValue();
         RoadIndex.Built built = data.index.roads.get(entry.getKey());
         if (built == null || built.record != transfer.after || Collections.disjoint(built.chunks, state.chunks)) {
            cancelTransfer(player, transfer.parts, transfer.next, entry.getKey());
            state.pointTransfers.remove(entry.getKey());
            state.activePoints = null;
            return 0;
         } else {
            if (transfer.parts == null) {
               if (transfer.encoded == null) {
                  String dimension = state.dimension;
                  RoadRecord before = transfer.before;
                  RoadRecord after = transfer.after;
                  transfer.encoded = PACKETS.requestTag((Supplier<CompoundTag>)(() -> pointPacket(dimension, before, after)));
               }

               if (!ready(transfer.encoded)) {
                  return 0;
               }

               try {
                  transfer.parts = RoadWire.reidentify(transfer.encoded.join());
               } catch (CancellationException | CompletionException var12) {
                  state.pointTransfers.remove(entry.getKey());
                  state.pending.put(entry.getKey(), new RoadNetwork.Transfer(transfer.after));
                  state.activePoints = null;
                  return 0;
               }
            }

            if (!canSend(transfer.parts, transfer.next, remaining, deadline)) {
               return 0;
            } else {
               int bytes = fragment(player, transfer.parts.get(transfer.next++));
               state.activePoints = entry.getKey();
               if (transfer.next == transfer.parts.size()) {
                  state.sent.put(entry.getKey(), transfer.after);
                  state.pointTransfers.remove(entry.getKey());
                  state.activePoints = null;
               }

               return bytes;
            }
         }
      }
   }

   private static void prepareSign(ServerPlayer player, RoadNetwork.Watching state, RoadData data) {
      if (!state.pendingSigns.isEmpty() && state.signPreparations++ < 8) {
         Iterator<UUID> it = state.pendingSigns.iterator();
         UUID id = it.next();
         it.remove();
         if ((state.batch == null || !state.batch.ids.contains(id)) && (state.signTransfers.size() < 64 || state.signTransfers.containsKey(id))) {
            RoadIndex.Built built = data.index.roads.get(id);
            if (built != null && !Collections.disjoint(built.chunks, state.chunks)) {
               long version = data.smartSigns.version(id);
               if (state.forceSigns.contains(id) || !Objects.equals(state.sentSigns.get(id), version)) {
                  RoadNetwork.SignTransfer old = state.signTransfers.get(id);
                  if (old == null || old.version != version) {
                     if (old != null) {
                        cancelTransfer(player, old.parts, old.next, id);
                     }

                     RoadNetwork.SignTransfer transfer = new RoadNetwork.SignTransfer(id, version, data.smartSigns.road(id));
                     if (!transfer.active && !state.activeSigns.contains(id) && !state.forceSigns.contains(id) && state.sentSigns.containsKey(id)) {
                        state.sentSigns.put(id, version);
                     } else {
                        state.signTransfers.put(id, transfer);
                     }
                  }
               }
            } else {
               state.sentSigns.remove(id);
               state.activeSigns.remove(id);
            }
         } else {
            state.pendingSigns.add(id);
         }
      }
   }

   private static int publishSign(ServerPlayer player, RoadNetwork.Watching state, RoadData data, long deadline, int remaining) {
      prepareSign(player, state, data);
      Entry<UUID, RoadNetwork.SignTransfer> entry = next(state.signTransfers, state.activeSign);
      if (entry == null) {
         state.activeSign = null;
         return 0;
      } else {
         RoadNetwork.SignTransfer transfer = entry.getValue();
         RoadIndex.Built built = data.index.roads.get(entry.getKey());
         if (built == null || Collections.disjoint(built.chunks, state.chunks) || data.smartSigns.version(entry.getKey()) != transfer.version) {
            cancelTransfer(player, transfer.parts, transfer.next, entry.getKey());
            state.signTransfers.remove(entry.getKey());
            state.activeSign = null;
            if (built != null) {
               state.pendingSigns.add(entry.getKey());
            }

            return 0;
         } else {
            if (transfer.parts == null) {
               if (transfer.encoded == null) {
                  transfer.encoded = PACKETS.requestSigns(state.dimension, transfer.road, transfer.version, transfer.states);
               }

               if (!ready(transfer.encoded)) {
                  return 0;
               }

               try {
                  transfer.parts = RoadWire.reidentify(transfer.encoded.join());
               } catch (CancellationException | CompletionException var10) {
                  state.signTransfers.remove(entry.getKey());
                  state.pendingSigns.add(entry.getKey());
                  state.activeSign = null;
                  return 0;
               }
            }

            if (!canSend(transfer.parts, transfer.next, remaining, deadline)) {
               return 0;
            } else {
               int bytes = fragment(player, transfer.parts.get(transfer.next++));
               state.activeSign = entry.getKey();
               if (transfer.next == transfer.parts.size()) {
                  state.sentSigns.put(entry.getKey(), transfer.version);
                  state.forceSigns.remove(entry.getKey());
                  if (transfer.active) {
                     state.activeSigns.add(entry.getKey());
                  } else {
                     state.activeSigns.remove(entry.getKey());
                  }

                  state.signTransfers.remove(entry.getKey());
                  state.activeSign = null;
               }

               return bytes;
            }
         }
      }
   }

   private static int publishRoad(ServerPlayer player, RoadNetwork.Watching state, RoadData data, long deadline, int remaining) {
      Entry<UUID, RoadNetwork.Transfer> entry = next(state.pending, state.activeRoad);
      if (entry == null) {
         state.activeRoad = null;
         return 0;
      } else {
         RoadNetwork.Transfer transfer = entry.getValue();
         RoadIndex.Built built = data.index.roads.get(entry.getKey());
         if (built == null || built.record != transfer.record || Collections.disjoint(built.chunks, state.chunks)) {
            cancelTransfer(player, transfer.parts, transfer.next, entry.getKey());
            state.pending.remove(entry.getKey());
            state.activeRoad = null;
            return 0;
         } else {
            if (transfer.parts == null) {
               CompletableFuture<List<RoadWire.Fragment>> encoded = PACKETS.request(state.dimension, transfer.record);
               if (!ready(encoded)) {
                  return 0;
               }

               try {
                  transfer.parts = RoadWire.reidentify(encoded.join());
               } catch (CancellationException | CompletionException var11) {
                  state.pending.remove(entry.getKey());
                  state.activeRoad = null;
                  player.displayClientMessage(
                     Component.literal("道路同步失败：" + (var11.getCause() == null ? var11.getMessage() : var11.getCause().getMessage())), false
                  );
                  return 0;
               }
            }

            if (!canSend(transfer.parts, transfer.next, remaining, deadline)) {
               return 0;
            } else {
               int bytes = fragment(player, transfer.parts.get(transfer.next++));
               state.activeRoad = entry.getKey();
               if (transfer.next == transfer.parts.size()) {
                  state.sent.put(entry.getKey(), transfer.record);
                  state.pending.remove(entry.getKey());
                  state.activeRoad = null;
               }

               return bytes;
            }
         }
      }
   }

   public static String metrics() {
      return "wireFragments="
         + sentFragments
         + " wireMiB="
         + sentBytes / 1048576L
         + " publishMs="
         + publicationNanos / 1000000L
         + " publishMaxMs="
         + publicationMaxNanos / 1000000L
         + " resync="
         + resyncs
         + " hardResync="
         + hardResyncs
         + " maintenanceMs="
         + maintenanceNanos / 1000000L
         + " maintenanceMaxMs="
         + maintenanceMaxNanos / 1000000L
         + " encodings="
         + PACKETS.encodings()
         + " signEncodings="
         + PACKETS.signEncodings()
         + " maxWaitTicks="
         + WATCHING.values().stream().mapToLong(s -> s.maxWaitTicks).max().orElse(0L);
   }

   public static void stop() {
      RoadRecord.clearMeshCaches();
      RoadClearance.clearPreparedCache();
      RoadPlanningJobs.stop();
      LaneRamps.clearPreviewSnapshots();
      TollTool.clearPreviews();
      WATCHING.clear();
      PACKETS.clear();
      LAST_ACTION.clear();
      LAST_PREVIEW.clear();
      levelCursor = 0;
      playerCursor = 0;
   }

   public static void broadcastRoad(ServerLevel level, RoadRecord r) {
      RoadIndex.Built built = RoadData.get(level).index.roads.get(r.id());
      if (built != null) {
         for (ServerPlayer player : level.players()) {
            changedRoad(player, built);
         }
      }
   }

   private static void changedRoad(ServerPlayer player, RoadIndex.Built built) {
      RoadRecord r = built.record;
      RoadNetwork.Watching state = watching(player);
      if (!Collections.disjoint(built.chunks, state.chunks)) {
         road(player, r);
      } else if (state.sent.containsKey(r.id())
         || state.pending.containsKey(r.id())
         || state.activeSigns.contains(r.id())
         || state.batch != null && state.batch.ids.contains(r.id())) {
         forget(player, state, r.id());
      }
   }

   public static CompoundTag batchPacket(ServerLevel level, List<RoadRecord> roads, Set<UUID> replaced, BlockPos node) {
      CompoundTag packet = new CompoundTag();
      packet.putString("Type", "roadBatch");
      packet.putString("Dimension", level.dimension().location().toString());
      packet.putUUID("Batch", UUID.randomUUID());
      ListTag ids = new ListTag();

      for (UUID id : replaced) {
         CompoundTag t = new CompoundTag();
         t.putUUID("Id", id);
         ids.add(t);
      }

      packet.put("Replaced", ids);
      ListTag list = new ListTag();
      ListTag signs = new ListTag();
      RoadData data = RoadData.get(level);

      for (RoadRecord road : roads) {
         list.add(road.save());
         signs.add(data.smartSigns.packet(level, road.id()));
      }

      packet.put("Roads", list);
      packet.put("SignPackets", signs);
      packet.putLong("NodePos", node.asLong());
      if (level.getBlockEntity(node) instanceof NodeEntity marker) {
         packet.put("Node", marker.getUpdateTag(level.registryAccess()));
      }

      return packet;
   }

   public static RoadNetwork.PreparedBatch prepareBatch(ServerLevel level, List<RoadRecord> roads, Set<UUID> replaced, BlockPos node) {
      CompoundTag packet = batchPacket(level, roads, replaced, node);
      return new RoadNetwork.PreparedBatch(packet, RoadWire.split(packet));
   }

   private static long packedBytes(List<RoadWire.Fragment> parts) {
      return parts.stream().mapToLong(p -> (long)p.bytes().length).sum();
   }

   private static void refreshBatch(ServerPlayer player, RoadNetwork.Watching state, Set<UUID> affected, BlockPos node, RoadNetwork.PreparedBatch prepared) {
      RoadNetwork.BatchTransfer old = state.batch;
      int generations = (old == null ? 0 : old.generations) + (prepared == null ? 0 : 1);
      long historyBytes = (old == null ? 0L : old.historyBytes) + (prepared == null ? 0L : packedBytes(prepared.fragments()));
      if (generations <= 4 && historyBytes <= 4194304L) {
         if (old != null) {
            cancelTransfer(player, old.parts, old.next, null);
         }

         RoadData data = RoadData.get(player.serverLevel());
         LinkedHashSet<UUID> ids = new LinkedHashSet<>(affected);
         if (old != null) {
            ids.addAll(old.ids);
         }

         if (ids.size() > 4096) {
            resync(player, state, true);
         } else {
            LinkedHashMap<UUID, RoadRecord> records = new LinkedHashMap<>();
            HashMap<UUID, Long> signs = new HashMap<>();

            for (UUID id : ids) {
               RoadNetwork.Transfer pending = state.pending.remove(id);
               if (pending != null) {
                  cancelTransfer(player, pending.parts, pending.next, id);
               }

               RoadNetwork.SignTransfer sign = state.signTransfers.remove(id);
               if (sign != null) {
                  cancelTransfer(player, sign.parts, sign.next, id);
               }

               RoadNetwork.PointTransfer points = state.pointTransfers.remove(id);
               if (points != null) {
                  cancelTransfer(player, points.parts, points.next, id);
               }

               state.sent.remove(id);
               state.pendingSigns.remove(id);
               state.sentSigns.remove(id);
               RoadIndex.Built built = data.index.roads.get(id);
               if (built != null) {
                  records.put(id, built.record);
                  signs.put(id, data.smartSigns.version(id));
               }
            }

            CompoundTag packet = prepared != null && old == null
               ? prepared.packet()
               : batchPacket(player.serverLevel(), List.copyOf(records.values()), ids, node);
            if (prepared != null && old == null) {
               records.clear();
               signs.clear();

               for (Tag entry : packet.getList("Roads", 10)) {
                  UUID id = ((CompoundTag)entry).getUUID("Id");
                  RoadIndex.Built built = data.index.roads.get(id);
                  if (built != null) {
                     records.put(id, built.record);
                     signs.put(id, data.smartSigns.version(id));
                  }
               }
            }

            CompoundTag barrier = new CompoundTag();
            barrier.putString("Type", "batchBarrier");
            barrier.putString("Dimension", state.dimension);
            barrier.putUUID("Batch", packet.getUUID("Batch"));
            ListTag list = new ListTag();

            for (UUID id : ids) {
               CompoundTag value = new CompoundTag();
               value.putUUID("Id", id);
               list.add(value);
            }

            barrier.put("Ids", list);
            send(player, barrier);
            state.batch = new RoadNetwork.BatchTransfer(
               ids, records, signs, node, packet, prepared != null && old == null ? RoadWire.reidentify(prepared.fragments()) : null, generations, historyBytes
            );
         }
      } else {
         resync(player, state, true);
      }
   }

   public static void broadcastBatch(ServerLevel level, List<RoadRecord> roads, Set<UUID> replaced, RoadNetwork.PreparedBatch prepared) {
      RoadData data = RoadData.get(level);
      LinkedHashSet<UUID> ids = new LinkedHashSet<>(replaced);

      for (RoadRecord road : roads) {
         ids.add(road.id());
      }

      for (ServerPlayer player : level.players()) {
         RoadNetwork.Watching state = watching(player);
         boolean relevant = ids.stream()
               .anyMatch(id -> state.sent.containsKey(id) || state.pending.containsKey(id) || state.batch != null && state.batch.ids.contains(id))
            || roads.stream().anyMatch(r -> {
               RoadIndex.Built built = data.index.roads.get(r.id());
               return built != null && !Collections.disjoint(built.chunks, state.chunks);
            });
         if (relevant) {
            refreshBatch(player, state, ids, BlockPos.of(prepared.packet().getLong("NodePos")), prepared);
         }
      }
   }

   private static void acknowledgeBatch(ServerPlayer player, CompoundTag command) {
      RoadNetwork.Watching state = watching(player);
      RoadNetwork.BatchTransfer batch = state.batch;
      if (batch != null
         && state.dimension.equals(command.getString("Dimension"))
         && command.hasUUID("Batch")
         && batch.packet.getUUID("Batch").equals(command.getUUID("Batch"))
         && batch.parts != null
         && batch.next == batch.parts.size()) {
         RoadData data = RoadData.get(player.serverLevel());

         for (Entry<UUID, RoadRecord> entry : batch.records.entrySet()) {
            RoadIndex.Built current = data.index.roads.get(entry.getKey());
            if (current == null || current.record != entry.getValue()) {
               refreshBatch(player, state, batch.ids, batch.node, null);
               return;
            }
         }

         state.sent.putAll(batch.records);
         state.sentSigns.putAll(batch.signVersions);
         state.activeSigns.addAll(batch.records.keySet());
         state.forceSigns.removeAll(batch.records.keySet());

         for (UUID id : batch.ids) {
            if (!batch.records.containsKey(id)) {
               state.activeSigns.remove(id);
            }
         }

         for (UUID idx : batch.records.keySet()) {
            if (!Objects.equals(state.sentSigns.get(idx), data.smartSigns.version(idx))) {
               state.pendingSigns.add(idx);
            }
         }

         for (UUID idxx : batch.ids) {
            RoadIndex.Built current = data.index.roads.get(idxx);
            if (current == null || Collections.disjoint(current.chunks, state.chunks)) {
               forgetTransport(player, state, idxx);
            }
         }

         state.batch = null;
      }
   }

   public static void broadcastLanePoints(ServerLevel level, RoadRecord before, RoadRecord after) {
      RoadIndex.Built built = RoadData.get(level).index.roads.get(after.id());
      if (built != null && built.record == after) {
         for (ServerPlayer player : level.players()) {
            lanePoints(player, before, built);
         }
      }
   }

   static void lanePoints(ServerPlayer player, RoadRecord before, RoadIndex.Built built) {
      RoadRecord after = built.record;
      RoadNetwork.Watching state = watching(player);
      if (!Collections.disjoint(built.chunks, state.chunks)) {
         if (state.batch != null && state.batch.ids.contains(before.id())) {
            refreshBatch(player, state, state.batch.ids, state.batch.node, null);
         } else {
            RoadNetwork.PointTransfer queued = state.pointTransfers.get(before.id());
            if ((state.sent.get(before.id()) == before || queued != null && queued.after == before) && !state.pending.containsKey(before.id())) {
               RoadRecord base = queued == null ? before : queued.before;
               if (queued != null) {
                  cancelTransfer(player, queued.parts, queued.next, before.id());
               }

               state.pointTransfers.put(after.id(), new RoadNetwork.PointTransfer(base, after));
            } else {
               road(player, after);
            }
         }
      }
   }

   private static void forgetTransport(ServerPlayer player, RoadNetwork.Watching state, UUID id) {
      RoadNetwork.Transfer pending = state.pending.remove(id);
      if (pending != null) {
         cancelTransfer(player, pending.parts, pending.next, id);
      }

      RoadNetwork.SignTransfer sign = state.signTransfers.remove(id);
      if (sign != null) {
         cancelTransfer(player, sign.parts, sign.next, id);
      }

      RoadNetwork.PointTransfer points = state.pointTransfers.remove(id);
      if (points != null) {
         cancelTransfer(player, points.parts, points.next, id);
      }

      state.sent.remove(id);
      state.pendingSigns.remove(id);
      state.forceSigns.remove(id);
      state.sentSigns.remove(id);
      state.activeSigns.remove(id);
   }

   private static void forget(ServerPlayer player, RoadNetwork.Watching state, UUID id) {
      if (state.batch != null && state.batch.ids.contains(id)) {
         refreshBatch(player, state, state.batch.ids, state.batch.node, null);
      } else {
         forgetTransport(player, state, id);
         CompoundTag packet = new CompoundTag();
         packet.putString("Type", "delete");
         packet.putUUID("Id", id);
         packet.putString("Dimension", state.dimension);
         send(player, packet);
      }
   }

   public static void broadcastDelete(ServerLevel level, UUID id) {
      for (ServerPlayer player : level.players()) {
         forget(player, watching(player), id);
      }
   }

   public static void result(ServerPlayer p, boolean success, String message) {
      result(p, success, message, new CompoundTag());
   }

   private static void result(ServerPlayer p, boolean success, String message, CompoundTag command) {
      CompoundTag t = new CompoundTag();
      t.putString("Type", "result");
      t.putBoolean("Success", success);
      t.putString("Message", message);
      t.putString("Dimension", p.level().dimension().location().toString());
      RoadActionContext.echo(command, t);
      if (command.getString("Action").equals("manualOpen") && command.hasUUID("OpenRequest")) {
         t.putUUID("OpenRequest", command.getUUID("OpenRequest"));
      }

      if (!success) {
         LaneRamps.conflictRoads(t, p, message);
      }

      send(p, t);
      p.displayClientMessage(Component.literal(RoadConflictIds.display(message)), false);
   }

   public static void forget(UUID id) {
      RoadPlanningJobs.cancel(id);
      RoadSplitter.cancel(id);
      LAST_ACTION.remove(id);
      LAST_PREVIEW.remove(id);
      WATCHING.remove(id);
   }

   public static void requestRoadSync() {
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null) {
         CompoundTag tag = new CompoundTag();
         tag.putString("Action", "roadSyncRetry");
         tag.putString("Dimension", level.dimension().location().toString());
         PacketDistributor.sendToServer(new RoadNetwork.Action(tag), new CustomPacketPayload[0]);
      }
   }

   /** Ask for roads in a wider chunk radius than normal chunk watching (DH draw band). */
   public static void requestWideSync(int radius) {
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null) {
         CompoundTag tag = new CompoundTag();
         tag.putString("Action", "roadWideSync");
         tag.putString("Dimension", level.dimension().location().toString());
         tag.putInt("Radius", Math.max(0, Math.min(64, radius)));
         PacketDistributor.sendToServer(new RoadNetwork.Action(tag), new CustomPacketPayload[0]);
      }
   }

   public static void ackBatch(UUID id) {
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null) {
         CompoundTag tag = new CompoundTag();
         tag.putString("Action", "roadBatchAck");
         tag.putString("Dimension", level.dimension().location().toString());
         tag.putUUID("Batch", id);
         PacketDistributor.sendToServer(new RoadNetwork.Action(tag), new CustomPacketPayload[0]);
      }
   }

   private static void resync(ServerPlayer player, RoadNetwork.Watching state) {
      resync(player, state, false);
   }

   private static void resync(ServerPlayer player, RoadNetwork.Watching state, boolean resetRoads) {
      resyncs++;
      if (resetRoads) {
         hardResyncs++;
      }

      RoadIndex index = RoadData.get(player.serverLevel()).index;
      RoadNetwork.BatchTransfer oldBatch = state.batch;
      CompoundTag reset = new CompoundTag();
      reset.putString("Type", "syncReset");
      reset.putString("Dimension", state.dimension);
      reset.putBoolean("ResetRoads", resetRoads);
      send(player, reset);
      state.sent.clear();
      state.pending.clear();
      state.pendingSigns.clear();
      state.forceSigns.clear();
      state.signTransfers.clear();
      state.pointTransfers.clear();
      state.sentSigns.clear();
      state.activeSigns.clear();
      state.batch = null;
      if (oldBatch != null && !resetRoads) {
         state.batch = oldBatch;
         refreshBatch(player, state, oldBatch.ids, oldBatch.node, null);
      }

      LinkedHashSet<UUID> ids = new LinkedHashSet<>();

      for (long chunk : state.chunks) {
         ids.addAll(index.inChunk(chunk));
      }

      state.forceSigns.addAll(ids);

      for (UUID id : ids) {
         RoadIndex.Built built = index.roads.get(id);
         if (built != null) {
            road(player, built.record);
         }
      }
   }

   /**
    * Stream roads in a square chunk radius around the player, independent of the
    * chunks the server currently tracks for that player. Used with Distant Horizons
    * so the client can draw the real road VBO inside the DH LOD band. road(...) is
    * deduplicated by state.sent, so repeated calls only send newly discovered roads.
    */
   private static void wideSync(ServerPlayer player, RoadNetwork.Watching state, int radius) {
      radius = Math.max(0, Math.min(64, radius));
      if (radius == 0) {
         return;
      }

      RoadIndex index = RoadData.get(player.serverLevel()).index;
      ChunkPos center = new ChunkPos(player.blockPosition());
      int queued = 0;

      for (int dx = -radius; dx <= radius && queued < 4096; dx++) {
         for (int dz = -radius; dz <= radius && queued < 4096; dz++) {
            long chunk = ChunkPos.asLong(center.x + dx, center.z + dz);

            for (UUID id : index.inChunk(chunk)) {
               RoadIndex.Built built = index.roads.get(id);
               if (built != null && (state.batch == null || !state.batch.ids.contains(id))) {
                  road(player, built.record);
                  queued++;
               }
            }
         }
      }
   }

   private static void act(ServerPlayer player, CompoundTag t) {
      if (t.getString("Action").startsWith("manual")) {
         try {
            String message = ManualPlanner.action(player, t);
            if (!message.isEmpty()) {
               result(player, true, message, t);
            }
         } catch (RuntimeException var5) {
            if (!(var5 instanceof IllegalArgumentException)) {
               LogManager.getLogger("SplineRoads/planner").error("Map action failed", var5);
            }

            result(player, false, var5.getMessage() == null ? "地图操作失败" : var5.getMessage(), t);
         }
      } else if (!t.getString("Action").startsWith("street")) {
         if (t.getString("Action").startsWith("railbed")) {
            RailbedTool.action(player, t);
         } else if (t.getString("Action").equals("roadBatchAck")) {
            acknowledgeBatch(player, t);
         } else if (t.getString("Action").equals("roadSyncRetry")) {
            RoadNetwork.Watching state = watching(player);
            long now = player.serverLevel().getGameTime();
            if (state.dimension.equals(t.getString("Dimension")) && now - state.lastResync >= 40L) {
               state.lastResync = now;
               resync(player, state);
            }
         } else if (t.getString("Action").equals("roadWideSync")) {
            RoadNetwork.Watching state = watching(player);
            long now = player.serverLevel().getGameTime();
            if (state.dimension.equals(t.getString("Dimension")) && now - state.lastWideSync >= 100L) {
               state.lastWideSync = now;
               wideSync(player, state, t.getInt("Radius"));
            }
         } else if (t.getString("Action").startsWith("split")) {
            if (t.getString("Action").equals("splitCancel")) {
               RoadSplitter.cancel(player.getUUID());
            } else {
               try {
                  if (t.getString("Action").equals("splitPreview")) {
                     Long last = LAST_PREVIEW.get(player.getUUID());
                     long now = player.serverLevel().getGameTime();
                     if (last != null && now - last < 5L) {
                        throw new IllegalArgumentException("操作过快，请稍候重试");
                     }

                     LAST_PREVIEW.put(player.getUUID(), now);
                     open(player, RoadSplitter.preview(player, t));
                  } else if (t.getString("Action").equals("splitCommit")) {
                     result(player, true, RoadSplitter.commit(player, t), t);
                  }
               } catch (RuntimeException var6) {
                  if (!(var6 instanceof IllegalArgumentException)) {
                     System.getLogger("SplineRoads/splitter").log(Level.ERROR, "Split action failed", var6);
                  }

                  if (t.getString("Action").equals("splitPreview")) {
                     CompoundTag reply = new CompoundTag();
                     reply.putString("Kind", "splitCheck");
                     reply.putLong("Request", t.getLong("Request"));
                     reply.putString("Dimension", player.level().dimension().location().toString());
                     reply.putString("Error", var6.getMessage() == null ? "分割规划失败" : var6.getMessage());
                     open(player, reply);
                  } else {
                     result(player, false, var6.getMessage() == null ? "分割失败，已恢复原道路" : var6.getMessage(), t);
                  }
               }
            }
         } else if (t.getString("Action").equals("tollCancelPreview")) {
            TollTool.cancelPreview(player, t);
         } else if (t.getString("Action").equals("laneRampCancel")) {
            RoadPlanningJobs.cancel(player.getUUID(), t.getLong("Request"));
         } else if (t.getString("Action").equals("lanePointReady")) {
            if (t.hasUUID("Interaction")) {
               LanePointTool.acknowledge(player, t.getUUID("Interaction"));
            }
         } else if (t.getString("Action").equals("lanePointResync")) {
            RoadIndex.Built b = RoadData.get(player.serverLevel()).index.roads.get(t.getUUID("Id"));
            RoadNetwork.Watching state = watching(player);
            if (b != null && !Collections.disjoint(b.chunks, state.chunks)) {
               state.sent.remove(b.record.id());
               road(player, b.record);
            }
         } else if (t.getString("Action").equals("deletePreview")
            || t.getString("Action").equals("laneRampDeletePreview")
            || t.getString("Action").equals("elevatedAccessPreview")) {
            try {
               perform(player, t);
            } catch (RuntimeException var9) {
               result(player, false, var9.getMessage() == null ? "预览读取失败" : var9.getMessage(), t);
            }
         } else if (!t.getString("Action").equals("tollPreview")
            && !t.getString("Action").equals("interchangePreview")
            && !t.getString("Action").equals("laneRampPreview")) {
            try {
               result(player, true, perform(player, t), t);
            } catch (RuntimeException var7) {
               logCrossingFailure(t.getString("Action"), var7);
               if (!(var7 instanceof IllegalArgumentException)) {
                  LogManager.getLogger("SplineRoads/build").error("Road action failed: " + t.getString("Action"), var7);
               }

               result(
                  player,
                  false,
                  !(var7 instanceof IllegalArgumentException) && !(var7 instanceof RoadPlanningBudget.Aborted)
                     ? "建造异常已结束等待："
                        + var7.getClass().getSimpleName()
                        + (var7.getMessage() == null ? "" : "：" + var7.getMessage())
                        + "；完整原因见 latest.log（SplineRoads/build）"
                     : (var7.getMessage() == null ? "道路参数无效" : var7.getMessage()),
                  t
               );
            }
         } else {
            try {
               perform(player, t);
            } catch (RuntimeException var8) {
               logCrossingFailure(t.getString("Action"), var8);
               if (!(var8 instanceof IllegalArgumentException)) {
                  LogManager.getLogger("SplineRoads/build").error("Road preview failed", var8);
               }

               CompoundTag reply = new CompoundTag();
               reply.putString(
                  "Kind",
                  t.getString("Action").equals("tollPreview")
                     ? "tollCheck"
                     : (t.getString("Action").equals("laneRampPreview") ? "laneRampCheck" : "corridorCheck")
               );
               reply.putLong("Request", t.getLong("Request"));
               reply.putString("Dimension", player.level().dimension().location().toString());
               reply.putString("Error", var8.getMessage() == null ? "预览参数无效" : var8.getMessage());
               LaneRamps.conflictRoads(reply, player, reply.getString("Error"));
               open(player, reply);
            }
         }
      }
   }

   private static void logCrossingFailure(String action, RuntimeException failure) {
      RoadCrossingFailure crossing = RoadCrossingFailure.find(failure);
      if (crossing != null) {
         System.getLogger("SplineRoads/cycle").log(Level.WARNING, "action=" + action + " " + crossing.diagnostic(), crossing);
      }
   }

   public static String perform(ServerPlayer player, CompoundTag t) {
      return t.hasUUID("PlannerSession") ? ManualPlanner.edit(player, t, () -> performTool(player, t)) : performTool(player, t);
   }

   private static String performTool(ServerPlayer player, CompoundTag t) {
      if (t.getString("Action").equals("tollCancelPreview")) {
         TollTool.cancelPreview(player, t);
         return "";
      } else {
         long now = player.serverLevel().getGameTime();
         boolean preview = t.getString("Action").equals("elevatedAccessPreview")
            || t.getString("Action").equals("deletePreview")
            || t.getString("Action").equals("laneRampDeletePreview")
            || t.getString("Action").equals("tollPreview")
            || t.getString("Action").equals("interchangePreview")
            || t.getString("Action").equals("laneRampPreview");
         Map<UUID, Long> throttle = preview ? LAST_PREVIEW : LAST_ACTION;
         Long last = throttle.get(player.getUUID());
         if (last != null && now - last < 5L) {
            throw new IllegalArgumentException("操作过快，请稍候重试");
         } else {
            throttle.put(player.getUUID(), now);
            String action = t.getString("Action");
            ItemStack tool = t.hasUUID("PlannerSession")
               ? ManualPlanner.tool(player, t)
               : (usable(player.getMainHandItem(), action) ? player.getMainHandItem() : player.getOffhandItem());
            if ((
                  tool.getItem() instanceof RoadTool
                     || action.startsWith("toll") && usable(tool, action)
                     || (action.equals("gantry") || action.equals("smartMode")) && tool.getItem() instanceof GantryTool
                     || action.equals("smartText") && tool.getItem() instanceof SmartTextTool
                     || action.equals("laneLines") && tool.getItem() instanceof LaneLineTool
                     || action.equals("attachedPoint") && (tool.getItem() instanceof AttachedPointTool || tool.getItem() instanceof ElevatedPointTool)
                     || action.equals("lanePoint") && tool.getItem() instanceof LanePointTool
                     || action.startsWith("laneRamp") && tool.getItem() instanceof LaneRampTool
                     || action.equals("yJunction") && (tool.getItem() instanceof YJunctionTool || tool.getItem() instanceof JunctionTool)
                     || action.startsWith("junction") && usable(tool, action)
                     || action.startsWith("roundabout") && tool.getItem() instanceof RoundaboutTool
                     || action.startsWith("interchange") && tool.getItem() instanceof InterchangeTool generator && generator.available()
                     || (action.equals("delete") || action.equals("deletePreview")) && tool.is((Item)SplineRoads.REMOVER.get())
               )
               && player.mayBuild()
               && !player.isSpectator()) {
               ServerLevel level = player.serverLevel();
               RoadData data = RoadData.get(level);
               switch (action) {
                  case "deletePreview":
                     if (!tool.is((Item)SplineRoads.CONNECTOR.get()) && !tool.is((Item)SplineRoads.REMOVER.get())) {
                        throw new IllegalArgumentException("请手持道路连接器或删除器");
                     }

                     RoadTool.openDeletion(player, t.getUUID("Id"), t);
                     return "";
                  case "smartText":
                     if (!(tool.getItem() instanceof SmartTextTool)) {
                        throw new IllegalArgumentException("请手持文字编辑器");
                     }

                     data.smartSigns.edit(player, t);
                     return "标牌内容已保存";
                  case "smartMode": {
                     if (!(tool.getItem() instanceof GantryTool)) {
                        throw new IllegalArgumentException("请手持龙门架编辑器");
                     } else {
                        SmartSigns.Key key = SmartSignCodec.key(t);
                        RoadIndex.Built b = data.index.roads.get(key.road());
                        if (b == null) {
                           throw new IllegalArgumentException("龙门架已删除");
                        } else {
                           RoadData.requireOwner(player, b.record.owner());
                           if (player.distanceToSqr(b.mesh.first().center().x(), b.mesh.first().center().y(), b.mesh.first().center().z()) > 1.6777216E7) {
                              throw new IllegalArgumentException("离道路太远");
                           }

                           data.smartSigns.mode(key, SmartSigns.Mode.valueOf(t.getString("Mode")), t.getLong("Revision"));
                           return "标牌模式已更新；文字保持手动填写";
                        }
                     }
                  }
                  case "tollPreview":
                     TollTool.selection(player, tool, t);
                     CompoundTag inputs = TollRoads.preview(level, player, t);
                     RoadPlanningJobs.cancel(player.getUUID());
                     TollTool.rememberPreview(player, tool, t, inputs);
                     CompoundTag reply = new CompoundTag();
                     reply.putString("Kind", "tollCheck");
                     reply.putString("Dimension", level.dimension().location().toString());
                     reply.putLong("Request", t.getLong("Request"));
                     reply.put("Inputs", inputs);
                     open(player, reply);
                     return "收费站预览已检查";
                  case "tollBuild":
                     TollTool.selection(player, tool, t);
                     return TollRoads.build(level, player, t);
                  case "tollDelete":
                     TollRoads.remove(level, player, t.getUUID("Id"));
                     return "收费站已删除，原端点与外接道路保留";
                  case "lanePoint":
                     if (!(tool.getItem() instanceof LanePointTool)) {
                        throw new IllegalArgumentException("请手持车道点工具");
                     }

                     return LanePointTool.edit(level, player, t);
                  case "laneRampRoad":
                     if (!(tool.getItem() instanceof RoadTool)) {
                        throw new IllegalArgumentException("请手持道路连接器");
                     }

                     return LaneRamps.editRoad(level, player, t);
                  case "laneRampPreview":
                     if (!(tool.getItem() instanceof LaneRampTool)) {
                        throw new IllegalArgumentException("请手持匝道连接器");
                     }

                     RoadPlanningJobs.begin(player, tool, t);
                     return "匝道正在后台规划";
                  case "laneRamp":
                     if (!(tool.getItem() instanceof LaneRampTool)) {
                        throw new IllegalArgumentException("请手持匝道连接器");
                     }

                     return LaneRamps.build(player, tool, t);
                  case "laneRampDeletePreview":
                  case "laneRampDelete":
                     if (!(tool.getItem() instanceof LaneRampTool)) {
                        throw new IllegalArgumentException("请手持匝道连接器");
                     }

                     return LaneRampTool.deletion(player, tool, t, action.equals("laneRampDelete"));
                  case "attachedPoint":
                     if (!(tool.getItem() instanceof AttachedPointTool) && !(tool.getItem() instanceof ElevatedPointTool)) {
                        throw new IllegalArgumentException("请手持附属点创建器");
                     }

                     return AttachedPointTool.edit(level, player, t);
                  case "laneLines":
                     if (!(tool.getItem() instanceof LaneLineTool)) {
                        throw new IllegalArgumentException("请手持车道线编辑器");
                     }

                     data.editLaneLine(level, player, t);
                     return t.contains("HideArrows") ? (t.getBoolean("HideArrows") ? "所选路段的方向箭头已隐藏" : "所选路段的方向箭头已显示") : "所选路段的单根车道线已更新";
                  case "yJunction":
                     return YJunctionTool.build(level, player, t, tool);
                  case "gantry":
                     if (!(tool.getItem() instanceof GantryTool)) {
                        throw new IllegalArgumentException("请手持龙门架编辑器");
                     }

                     data.editGantry(level, player, t);
                     return "所选龙门架已更新";
                  case "interchangePreview":
                     requireGenerator(tool, t, data);
                     if (!t.contains("Corridor")) {
                        throw new IllegalArgumentException("预览仅用于主辅路与双层同侧组合");
                     } else {
                        if (t.hasUUID("Id")
                           || Arrays.equals(t.getLongArray("Points"), ItemNBT.get(tool).getLongArray("Points"))
                              && level.dimension().location().toString().equals(ItemNBT.get(tool).getString("Dimension"))) {
                           CompoundTag inputsx = Corridors.preview(level, player, t);
                           RoadPlanningJobs.cancel(player.getUUID());
                           CompoundTag replyx = new CompoundTag();
                           replyx.putString("Kind", "corridorCheck");
                           replyx.putLong("Request", t.getLong("Request"));
                           replyx.putString("Dimension", level.dimension().location().toString());
                           replyx.put("Inputs", inputsx);
                           open(player, replyx);
                           return "组合预览已检查";
                        }

                        throw new IllegalArgumentException("组合选点已失效，请重新选点");
                     }
                  case "roundabout":
                     return RoundaboutTool.build(level, player, t, tool);
                  case "junctionRoad":
                     if (!tool.is((Item)SplineRoads.CONNECTOR.get())) {
                        throw new IllegalArgumentException("请手持道路连接器");
                     }

                     return JunctionRoads.build(level, player, t);
                  case "elevatedAccessPreview":
                     if (!(tool.getItem() instanceof RoadTool)) {
                        throw new IllegalArgumentException("请手持道路连接器");
                     }

                     ElevatedAccessRoads.requireSelection(player, tool, t);
                     open(player, ElevatedAccessRoads.preview(level, player, t));
                     return "";
                  case "elevatedAccessBuild": {
                     if (!(tool.getItem() instanceof RoadTool)) {
                        throw new IllegalArgumentException("请手持道路连接器");
                     }

                     ElevatedAccessRoads.requireSelection(player, tool, t);
                     String result = ElevatedAccessRoads.build(level, player, t);

                     for (String key : List.of("ElevatedSelection", "SideMain", "SidePoint", "SideSelection", "Start")) {
                        ItemNBT.edit(tool).remove(key);
                     }

                     return result;
                  }
                  case "junctionSideRoad":
                     if (!tool.is((Item)SplineRoads.CONNECTOR.get())) {
                        throw new IllegalArgumentException("请手持道路连接器");
                     }

                     return OneSidedJunctions.buildRoadSelected(level, player, t, tool);
                  case "junction":
                     if (!(tool.getItem() instanceof RoundaboutTool) && !(tool.getItem() instanceof JunctionTool)) {
                        throw new IllegalArgumentException("请手持普通路口编辑器");
                     } else if (!t.hasUUID("Id") && !(tool.getItem() instanceof JunctionTool)) {
                        throw new IllegalArgumentException("新建独立路口请使用普通路口编辑器");
                     } else {
                        if (t.hasUUID("Id")
                           || Arrays.equals(t.getLongArray("Points"), ItemNBT.get(tool).getLongArray("Points"))
                              && level.dimension().location().toString().equals(ItemNBT.get(tool).getString("Dimension"))) {
                           String message = Junctions.build(level, player, t);
                           ItemNBT.edit(tool).remove("Points");
                           return message;
                        }

                        throw new IllegalArgumentException("路口选点已失效，请重新选点");
                     }
                  case "junctionDelete":
                     LaneDeletes.removeJunction(level, player, t);
                     return "路口已整体删除，外部道路和端点保留";
                  case "interchange":
                     requireGenerator(tool, t, data);
                     if (t.hasUUID("Id")
                        || Arrays.equals(t.getLongArray("Points"), ItemNBT.get(tool).getLongArray("Points"))
                           && level.dimension().location().toString().equals(ItemNBT.get(tool).getString("Dimension"))) {
                        String message = Interchanges.build(level, player, t);
                        ItemNBT.edit(tool).remove("Points");
                        return message;
                     }

                     throw new IllegalArgumentException("立交端点选择失效，请重新选择");
                  case "interchangeDelete":
                  case "interchangeDeleteRamps":
                     requireGenerator(tool, t, data);
                     boolean corridor = Interchanges.descriptor(data, t.getUUID("Id")).contains("Corridor");
                     if (action.equals("interchangeDeleteRamps")) {
                        Interchanges.removeRamps(level, player, t.getUUID("Id"));
                        return corridor ? "连接匝道已删除；主路、辅路或第二层道路保留，可单独编辑" : "匝道及路口设施已删除；主路保留，可用道路连接器单独编辑";
                     }

                     Interchanges.remove(level, player, t.getUUID("Id"));
                     return "整座立交已删除，原端点保留";
                  case "curveControl":
                     if (!(tool.getItem() instanceof RoadTool)) {
                        throw new IllegalArgumentException("请手持道路连接器");
                     }

                     return CurveControlNodes.add(level, player, tool, BlockPos.of(t.getLong("Pos")));
                  case "connect": {
                     BlockPos a = BlockPos.of(t.getLong("A"));
                     BlockPos b = BlockPos.of(t.getLong("B"));
                     UUID id = t.hasUUID("Id") ? t.getUUID("Id") : null;
                     CompoundTag settingsTag = t.getCompound("Settings");
                     if (id != null && t.contains("BaseSettings")) {
                        RoadRecord current = data.editableRoad(id);
                        if (current == null) {
                           throw new IllegalArgumentException("道路已删除，请重新选择");
                        }

                        RoadGeometry.Settings latest = current.automatic()
                           ? RoadPlanner.mode(current.settings(), RoadGeometry.Mode.AUTO, current.settings().arcDegrees())
                           : current.settings();
                        settingsTag = RoadDraft.reconcileSettings(t.getCompound("BaseSettings"), settingsTag, RoadRecord.writeSettings(latest));
                     }

                     RoadGeometry.Settings settings = RoadRecord.readSettings(settingsTag);
                     if (id != null
                        || ItemNBT.get(tool).contains("Start")
                           && ItemNBT.get(tool).getLong("Start") == a.asLong()
                           && level.dimension().location().toString().equals(ItemNBT.get(tool).getString("Dimension"))) {
                        settings = settings.options(settings.options().sidewalk(settings.options().sidewalk().smooth(true)));
                        if (settings.style().ramp()) {
                           throw new IllegalArgumentException("道路连接器仅用于普通道路和高速道路");
                        }

                        List<BlockPos> controlNodes = id == null ? CurveControlNodes.validate(level, player, ItemNBT.edit(tool), settings, a, b) : List.of();
                        RoadRecord r = data.connect(
                           level,
                           player,
                           a,
                           b,
                           settings,
                           id,
                           t.getBoolean("LevelEnds"),
                           AutoJunctions.forced(t, "A"),
                           AutoJunctions.forced(t, "B"),
                           controlNodes
                        );
                        ItemNBT.edit(tool).remove("CurveControlNodes");
                        ItemNBT.get(tool).put("Settings", RoadRecord.writeSettings(RoadPreferences.fresh(settings)));
                        ItemNBT.edit(tool).putInt("PreferencesVersion", 3);
                        if (ManualPlanner.ownsTool(player, tool)) {
                           ItemNBT.edit(tool).remove("Start");
                           ItemNBT.edit(tool).remove("CurveThrough");
                           return "道路已建成；请重新选择 A 和 B";
                        }

                        BlockPos next = AutoJunctions.center(data, r.a()) != null ? r.a() : r.b();
                        ItemNBT.edit(tool).putLong("Start", next.asLong());
                        ItemNBT.edit(tool).putString("Dimension", level.dimension().location().toString());
                        if (AutoJunctions.center(data, next) != null) {
                           return "道路和路口已更新；保留中心端点，可直接选择下一方向的外侧端点";
                        }

                        return "道路已建成并自动清障；继续右键下一个端点即可续接";
                     }

                     throw new IllegalArgumentException("起点选择已失效，请重新选择两个端点");
                  }
                  case "node":
                     BlockPos p = BlockPos.of(t.getLong("Pos"));
                     data.editNode(level, player, p, RoadRecord.readNode(t.getCompound("Node")));
                     return "端点及相连道路已更新";
                  case "delete": {
                     UUID id = t.getUUID("Id");
                     LaneDeletes.remove(level, player, t);
                     return "道路已删除";
                  }
                  default:
                     throw new IllegalArgumentException("未知操作");
               }
            } else {
               throw new IllegalArgumentException("请手持道路连接器并确保拥有建设权限");
            }
         }
      }
   }

   private static void requireGenerator(ItemStack stack, CompoundTag command, RoadData data) {
      if (stack.getItem() instanceof InterchangeTool tool && tool.available()) {
         tool.requireDescriptor(command.hasUUID("Id") ? Interchanges.descriptor(data, command.getUUID("Id")) : command);
         if (command.getString("Action").equals("interchange") || command.getString("Action").equals("interchangePreview")) {
            tool.requireDescriptor(command);
         }

         return;
      }

      throw new IllegalArgumentException("请手持对应的立交、主辅路或双层出入口生成器");
   }

   private static boolean usable(ItemStack stack, String action) {
      if (action.startsWith("toll")) {
         return stack.getItem() instanceof TollTool
            || stack.getItem() instanceof RoadTool
            || action.equals("tollDelete") && stack.is((Item)SplineRoads.REMOVER.get());
      } else if (action.equals("lanePoint")) {
         return stack.getItem() instanceof LanePointTool;
      } else if (action.equals("laneRampRoad")) {
         return stack.getItem() instanceof RoadTool;
      } else if (action.startsWith("laneRamp")) {
         return stack.getItem() instanceof LaneRampTool;
      } else if (action.equals("attachedPoint")) {
         return stack.getItem() instanceof AttachedPointTool || stack.getItem() instanceof ElevatedPointTool;
      } else if (action.equals("laneLines")) {
         return stack.getItem() instanceof LaneLineTool;
      } else if (action.equals("yJunction")) {
         return stack.getItem() instanceof YJunctionTool || stack.getItem() instanceof JunctionTool;
      } else if (action.equals("smartText")) {
         return stack.getItem() instanceof SmartTextTool;
      } else if (action.equals("smartMode")) {
         return stack.getItem() instanceof GantryTool;
      } else if (action.equals("gantry")) {
         return stack.getItem() instanceof GantryTool;
      } else if (action.startsWith("roundabout")) {
         return stack.getItem() instanceof RoundaboutTool;
      } else if (action.startsWith("elevatedAccess") || action.equals("junctionRoad") || action.equals("junctionSideRoad")) {
         return stack.is((Item)SplineRoads.CONNECTOR.get());
      } else if (action.startsWith("junction")) {
         return stack.is((Item)SplineRoads.CONNECTOR.get())
            || stack.getItem() instanceof JunctionTool
            || stack.getItem() instanceof RoundaboutTool
            || action.equals("junctionDelete") && stack.is((Item)SplineRoads.REMOVER.get());
      } else if (action.startsWith("interchange")) {
         if (stack.getItem() instanceof InterchangeTool generator && generator.available()) {
            return true;
         }

         return false;
      } else {
         return stack.getItem() instanceof RoadTool || (action.equals("delete") || action.equals("deletePreview")) && stack.is((Item)SplineRoads.REMOVER.get());
      }
   }

   private RoadNetwork() {
   }

   public static record Action(CompoundTag tag) implements CustomPacketPayload {
      public static final Type<RoadNetwork.Action> TYPE = new Type(ResourceLocation.fromNamespaceAndPath("splineroads", "action"));
      public static final StreamCodec<FriendlyByteBuf, RoadNetwork.Action> CODEC = new StreamCodec<FriendlyByteBuf, RoadNetwork.Action>() {
         public RoadNetwork.Action decode(FriendlyByteBuf buf) {
            return RoadNetwork.Action.decode(buf);
         }

         public void encode(FriendlyByteBuf buf, RoadNetwork.Action value) {
            value.encode(buf);
         }
      };

      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }

      public void encode(FriendlyByteBuf buf) {
         CompoundTag command = this.tag.copy();
         command.remove("JunctionGraph");
         buf.writeNbt(command);
      }

      public static RoadNetwork.Action decode(FriendlyByteBuf buf) {
         if (buf.readableBytes() > 65536) {
            throw new IllegalArgumentException("道路操作消息过大");
         } else {
            CompoundTag t = buf.readNbt();
            return new RoadNetwork.Action(t == null ? new CompoundTag() : t);
         }
      }

      static void handleServer(RoadNetwork.Action msg, IPayloadContext ctx) {
         if (msg.tag.getString("Action").equals("laneRampCancel")) {
            ServerPlayer sender = ctx.player() instanceof ServerPlayer sp ? sp : null;
            if (sender != null) {
               UUID owner = sender.getUUID();
               long request = msg.tag.getLong("Request");
               RoadPlanningJobs.cancel(owner, request);
               ctx.enqueueWork(() -> RoadPlanningJobs.cancel(owner, request));
            }
         } else if (msg.tag.getString("Action").equals("railbedCancel") && msg.tag.hasUUID("Token")) {
            ServerPlayer sender;
            ServerPlayer var11 = sender = ctx.player() instanceof ServerPlayer sp ? sp : null;
            if (sender != null) {
               RailbedTool.cancelSignal(sender.getUUID(), msg.tag.getUUID("Token"));
            }

            ctx.enqueueWork(() -> {
               if (sender != null) {
                  RoadNetwork.act(sender, msg.tag);
               }
            });
         } else {
            ctx.enqueueWork(() -> {
               ServerPlayer senderx = ctx.player() instanceof ServerPlayer spx ? spx : null;
               if (senderx != null) {
                  RoadNetwork.act(senderx, msg.tag);
               }
            });
         }
      }
   }

   private static final class BatchTransfer {
      final Set<UUID> ids;
      final Map<UUID, RoadRecord> records;
      final Map<UUID, Long> signVersions;
      final BlockPos node;
      final CompoundTag packet;
      CompletableFuture<List<RoadWire.Fragment>> encoded;
      List<RoadWire.Fragment> parts;
      int next;
      long sentTick;
      long historyBytes;
      long checkedContentRevision = -1L;
      final int generations;

      BatchTransfer(
         Set<UUID> ids,
         Map<UUID, RoadRecord> records,
         Map<UUID, Long> signs,
         BlockPos node,
         CompoundTag packet,
         List<RoadWire.Fragment> parts,
         int generations,
         long historyBytes
      ) {
         this.ids = Set.copyOf(ids);
         this.records = Map.copyOf(records);
         this.signVersions = Map.copyOf(signs);
         this.node = node;
         this.packet = packet;
         this.parts = parts;
         this.generations = generations;
         this.historyBytes = historyBytes;
      }
   }

   public static record ControlMessage(RoadWire.Fragment fragment) implements CustomPacketPayload {
      public static final Type<RoadNetwork.ControlMessage> TYPE = new Type(ResourceLocation.fromNamespaceAndPath("splineroads", "roads_control"));
      public static final StreamCodec<FriendlyByteBuf, RoadNetwork.ControlMessage> CODEC = new StreamCodec<FriendlyByteBuf, RoadNetwork.ControlMessage>() {
         public RoadNetwork.ControlMessage decode(FriendlyByteBuf buf) {
            return RoadNetwork.ControlMessage.decode(buf);
         }

         public void encode(FriendlyByteBuf buf, RoadNetwork.ControlMessage value) {
            value.encode(buf);
         }
      };

      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }

      public void encode(FriendlyByteBuf buf) {
         new RoadNetwork.ServerMessage(this.fragment).encode(buf);
      }

      public static RoadNetwork.ControlMessage decode(FriendlyByteBuf buf) {
         return new RoadNetwork.ControlMessage(RoadNetwork.ServerMessage.decode(buf).fragment());
      }

      static void handleControl(RoadNetwork.ControlMessage msg, IPayloadContext ctx) {
         ctx.enqueueWork(() -> ClientRoads.control(msg.fragment()));
      }
   }

   private static final class PointTransfer {
      final RoadRecord before;
      final RoadRecord after;
      CompletableFuture<List<RoadWire.Fragment>> encoded;
      List<RoadWire.Fragment> parts;
      int next;

      PointTransfer(RoadRecord before, RoadRecord after) {
         this.before = before;
         this.after = after;
      }
   }

   public static record PreparedBatch(CompoundTag packet, List<RoadWire.Fragment> fragments) {
   }

   public static record ServerMessage(RoadWire.Fragment fragment) implements CustomPacketPayload {
      public static final Type<RoadNetwork.ServerMessage> TYPE = new Type(ResourceLocation.fromNamespaceAndPath("splineroads", "roads"));
      public static final StreamCodec<FriendlyByteBuf, RoadNetwork.ServerMessage> CODEC = new StreamCodec<FriendlyByteBuf, RoadNetwork.ServerMessage>() {
         public RoadNetwork.ServerMessage decode(FriendlyByteBuf buf) {
            return RoadNetwork.ServerMessage.decode(buf);
         }

         public void encode(FriendlyByteBuf buf, RoadNetwork.ServerMessage value) {
            value.encode(buf);
         }
      };

      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }

      public void encode(FriendlyByteBuf buf) {
         buf.writeUUID(this.fragment.id());
         buf.writeVarInt(this.fragment.index());
         buf.writeVarInt(this.fragment.count());
         buf.writeByteArray(this.fragment.bytes());
      }

      public static RoadNetwork.ServerMessage decode(FriendlyByteBuf buf) {
         return new RoadNetwork.ServerMessage(new RoadWire.Fragment(buf.readUUID(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(32768)));
      }

      static void handleClient(RoadNetwork.ServerMessage msg, IPayloadContext ctx) {
         ctx.enqueueWork(() -> ClientRoads.fragment(msg.fragment()));
      }
   }

   private static final class SignTransfer {
      final UUID road;
      final long version;
      final boolean active;
      final List<SmartSigns.State> states;
      CompletableFuture<List<RoadWire.Fragment>> encoded;
      List<RoadWire.Fragment> parts;
      int next;

      SignTransfer(UUID road, long version, List<SmartSigns.State> states) {
         this.road = road;
         this.version = version;
         this.states = List.copyOf(states);
         this.active = states.stream().anyMatch(s -> s.mode() != SmartSigns.Mode.NORMAL);
      }
   }

   private static final class Transfer {
      final RoadRecord record;
      List<RoadWire.Fragment> parts;
      int next;

      Transfer(RoadRecord record) {
         this.record = record;
      }
   }

   private static final class Watching {
      final String dimension;
      final Set<Long> chunks = new HashSet<>();
      final Map<UUID, RoadRecord> sent = new HashMap<>();
      final Map<UUID, RoadNetwork.Transfer> pending = new LinkedHashMap<>();
      final Set<UUID> pendingSigns = new LinkedHashSet<>();
      final Set<UUID> forceSigns = new HashSet<>();
      final Set<UUID> activeSigns = new HashSet<>();
      final Map<UUID, Long> sentSigns = new HashMap<>();
      final Map<UUID, RoadNetwork.SignTransfer> signTransfers = new LinkedHashMap<>();
      final Map<UUID, RoadNetwork.PointTransfer> pointTransfers = new LinkedHashMap<>();
      RoadNetwork.BatchTransfer batch;
      long lastResync = -4611686018427387904L;
      long lastWideSync = -4611686018427387904L;
      long lastServed;
      long maxWaitTicks;
      final RoadPublicationBudget publication = new RoadPublicationBudget();
      UUID activeRoad;
      UUID activeSign;
      UUID activePoints;
      int signPreparations;

      Watching(String dimension) {
         this.dimension = dimension;
      }
   }
}
