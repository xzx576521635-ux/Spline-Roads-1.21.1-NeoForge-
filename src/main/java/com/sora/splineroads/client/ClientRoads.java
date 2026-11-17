package com.sora.splineroads.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.RoadEditRequests;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadVisibility;
import com.sora.splineroads.net.RoadActionContext;
import com.sora.splineroads.net.RoadDraft;
import com.sora.splineroads.net.RoadNetwork;
import com.sora.splineroads.net.RoadWire;
import com.sora.splineroads.world.LanePointCodec;
import com.sora.splineroads.world.NodeEntity;
import com.sora.splineroads.world.RoadBlocks;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.world.RoadSplitTool;
import com.sora.splineroads.world.RoadTool;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;

@EventBusSubscriber(
   modid = "splineroads",
   value = {Dist.CLIENT}
)
public final class ClientRoads {
   public static final RoadIndex INDEX = new RoadIndex(true);
   public static CompoundTag draft;
   private static final RoadEditRequests editRequests = new RoadEditRequests();
   private static final Object WORLD_EDITOR = new Object();
   private static final RoadInbox inbox = new RoadInbox(ClientRoads::recoverInbox, () -> ClientRoads.dimension);
   public static RoadGeometry.Mesh preview;
   public static List<RoadGeometry.Mesh> nodePreviews = List.of();
   public static boolean conflictPreview;
   public static String error = "";
   private static String dimension = "";
   private static Object currentLevel;
   private static int ticks;
   private static final ArrayDeque<UUID> prune = new ArrayDeque<>();
   private static final Set<Long> residencyChunks = new LinkedHashSet<>();
   private static final Set<UUID> residencyRoads = new LinkedHashSet<>();
   private static final RoadColdSync coldSync = new RoadColdSync();
   private static final LinkedHashMap<UUID, Long> cold = new LinkedHashMap<>();
   private static long coldBytes;
   private static long coldDerivedBytes;
   private static long coldHits;
   private static long pruned;
   private static long derivedEvictions;
   private static long coldSyncRequests;
   private static long coldSyncAcks;
   private static int joinTicks;
   private static boolean wideSyncSent;
   private static int wideSyncStage;
   private static long lastWideSyncTick;
   private static double wideSyncX;
   private static double wideSyncZ;
   private static Map<UUID, Long> coldWeights = Map.of();
   private static final Set<UUID> coldGpuTrimmed = new HashSet<>();
   private static int coldCursor;
   private static long coldRevision = -1L;
   private static long activityRevision;
   private static long accountingPasses;
   private static long trimVisits;
   private static long trimNanos;
   private static final LinkedHashSet<UUID> receivedBatches = new LinkedHashSet<>();

   @SubscribeEvent
   public static void curveControl(InteractionKeyMappingTriggered e) {
      Minecraft mc = Minecraft.getInstance();
      if (e.isUseItem()
         && e.getHand() == InteractionHand.MAIN_HAND
         && mc.screen == null
         && mc.player != null
         && mc.level != null
         && mc.player.getMainHandItem().getItem() instanceof RoadTool
         && InputConstants.isKeyDown(mc.getWindow().getWindow(), 342)) {
         e.setCanceled(true);
         e.setSwingHand(false);
         if (mc.hitResult instanceof BlockHitResult hit) {
            BlockPos pos = RoadTool.endpointAtHit(mc.level, hit.getBlockPos(), hit.getLocation());
            CompoundTag t = new CompoundTag();
            t.putString("Action", "curveControl");
            t.putLong("Pos", pos.asLong());
            beginWorldEdit();
            sendAction(t);
         }
      }
   }

   static void sendAction(CompoundTag value) {
      CompoundTag command = value.copy();
      ManualPlannerClient.decorate(command);
      String action = command.getString("Action");

      boolean generic = switch (action) {
         case "splitPreview", "splitCancel", "tollPreview", "tollCancelPreview", "interchangePreview", "laneRampPreview", "laneRampCancel", "lanePointReady", "lanePointResync", "roadBatchAck", "roadSyncRetry", "manualOpen", "manualCancelOpen", "manualClose", "manualRefresh" -> false;
         default -> true;
      };
      if (generic) {
         Minecraft mc = Minecraft.getInstance();
         Object owner = mc.screen == null ? WORLD_EDITOR : mc.screen;
         if (owner instanceof ManualPlannerScreen) {
            ManualPlannerClient.abandonMapPreview();
         }

         RoadEditRequests.Request request = editRequests.submit(owner, action, RoadActionContext.target(command));
         RoadActionContext.attach(command, request);
      }

      RoadNetwork.sendToServer(new RoadNetwork.Action(command));
   }

   static void beginWorldEdit() {
      editRequests.reset();
      editRequests.open(WORLD_EDITOR);
   }

   static void beginEditor(Object owner) {
      editRequests.open(owner);
   }

   static boolean ownsPreview(Object owner) {
      return editRequests.owns(owner);
   }

   static boolean endEditor(Object owner) {
      if (!editRequests.owns(owner)) {
         return false;
      } else {
         editRequests.reset();
         return true;
      }
   }

   static void editorChanged(Object owner) {
      if (editRequests.changed(owner) && owner instanceof RoadScreen screen) {
         screen.requestSuperseded();
      }
   }

   static boolean toolScreen(Object screen) {
      return screen instanceof ManualPlannerScreen.PointDetails
         || screen instanceof ManualAccessScreen
         || screen instanceof ManualPlannerScreen
         || screen instanceof RoadScreen
         || screen instanceof RoadDeleteScreen
         || screen instanceof SmartTextScreen
         || screen instanceof TollScreen
         || screen instanceof RoadSplitScreen
         || screen instanceof InterchangeScreen
         || screen instanceof CorridorScreen
         || screen instanceof LaneRampScreen
         || screen instanceof JunctionScreen
         || screen instanceof JunctionRoadScreen
         || screen instanceof RoundaboutScreen
         || screen instanceof ElevatedAccessScreen
         || screen instanceof LanePointScreen
         || screen instanceof LaneLineScreen
         || screen instanceof AttachedPointScreen
         || screen instanceof YJunctionScreen
         || screen instanceof GantryScreen;
   }

   private static void recoverInbox() {
      TollScreen.deliveryRecovery();
      LaneRampScreen.deliveryRecovery();
      CorridorScreen.deliveryRecovery();
      RoadNetwork.requestRoadSync();
   }

   public static void fragment(RoadWire.Fragment fragment) {
      ensureWorld();
      inbox.submit(fragment);
   }

   public static void control(RoadWire.Fragment fragment) {
      ensureWorld();
      inbox.control(fragment, event -> {
         if (event.error() != null) {
            error = event.error();
            ManualPlannerClient.deliveryFailure(error);
         } else {
            receive(event.tag());
         }
      });
   }

   public static void storeDraft(CompoundTag value) {
      if (!value.getBoolean("DeleteOnly")) {
         if (draft != null && !draft.equals(value)) {
            editorChanged(Minecraft.getInstance().screen);
         }

         draft = value.copy();
      }
   }

   public static void discardDraft() {
      draft = null;
   }

   private static void coldChanged() {
      coldRevision = -1L;
      activityRevision++;
   }

   private static void resetCold() {
      cold.clear();
      coldWeights = Map.of();
      coldGpuTrimmed.clear();
      coldCursor = 0;
      coldDerivedBytes = 0L;
      coldBytes = 0L;
      residencyChunks.clear();
      residencyRoads.clear();
      coldSync.clear();
      joinTicks = 0;
      wideSyncSent = false;
      wideSyncStage = 0;
      lastWideSyncTick = 0L;
      wideSyncX = 0.0;
      wideSyncZ = 0.0;
      coldChanged();
   }

   static void chunkChanged(long chunk) {
      residencyChunks.add(chunk);
      RoadRenderer.chunkChanged(chunk);
   }

   private static boolean chunkLoaded(ClientLevel level, long chunk) {
      return level.getChunkSource().getChunk((int)chunk, (int)(chunk >> 32), ChunkStatus.FULL, false) != null;
   }

   private static void refreshResidency(ClientLevel level, UUID id) {
      RoadIndex.Built road = INDEX.roads.get(id);
      if (road != null) {
         if (road.chunks.stream().anyMatch(chunk -> chunkLoaded(level, chunk)) || distantRenderable(road)) {
            awaken(id);
         } else {
            cool(id, road);
         }
      }
   }

   /**
    * With Distant Horizons the road VBO is drawn past the vanilla chunk radius, so
    * roads in that band must stay resident (and therefore buildable) even though
    * none of their own chunks are loaded. Without this they are cooled/suspended as
    * soon as the player walks away and only the flat DH LOD color remains.
    */
   private static boolean distantRenderable(RoadIndex.Built road) {
      if (!ModList.get().isLoaded("distanthorizons")) {
         return false;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null) {
         return false;
      }

      return RoadVisibility.within(
         road.mesh.min().x(),
         road.mesh.min().z(),
         road.mesh.max().x(),
         road.mesh.max().z(),
         mc.player.getX(),
         mc.player.getZ(),
         RoadRenderer.viewChunks()
      );
   }

   static boolean active(UUID id) {
      return INDEX.roads.containsKey(id) && !cold.containsKey(id);
   }

   static long visibilityRevision() {
      return INDEX.contentRevision() + activityRevision;
   }

   static String cacheMetrics() {
      return "cold roads="
         + cold.size()
         + " sourceMiB="
         + coldBytes / 1048576L
         + " renderMiB="
         + coldDerivedBytes / 1048576L
         + " renderEvicted="
         + derivedEvictions
         + " hits="
         + coldHits
         + " evicted="
         + pruned
         + " accounting="
         + accountingPasses
         + " trimVisits="
         + trimVisits
         + " trimMs="
         + trimNanos / 1000000L
         + " coldSync="
         + coldSync.pending()
         + " "
         + inbox.metrics();
   }

   private static void awaken(UUID id) {
      Long weight = cold.remove(id);
      if (weight != null) {
         coldChanged();
         coldGpuTrimmed.remove(id);
         coldBytes = coldBytes - weight;
         coldHits++;
         TollNameRenderer.clear();
         RoadRenderer.resume(id);
      }
   }

   private static void cool(UUID id, RoadIndex.Built road) {
      if (!cold.containsKey(id)) {
         long weight = 4096L + (long)road.mesh.samples().size() * 384L + (long)road.record.structures().size() * 512L;
         cold.put(id, Long.valueOf(weight));
         coldChanged();
         coldBytes += weight;
         TollNameRenderer.clear();
         RoadRenderer.suspend(id);
      }
   }

   private static void trimCold() {
      if (cold.isEmpty()) {
         coldDerivedBytes = 0L;
         coldWeights = Map.of();
         coldRevision = RoadRenderer.retentionRevision();
      } else {
         long started = System.nanoTime();
         long limit = (long)((Integer)RoadClientConfig.COLD_ROAD_CACHE_MIB.get()).intValue() * 1048576L;
         long observed = RoadRenderer.retentionRevision();
         if (coldRevision != observed) {
            coldWeights = RoadRenderer.retainedRoadBytes(cold.keySet());
            coldDerivedBytes = 0L;

            for (long weight : coldWeights.values()) {
               coldDerivedBytes += weight;
            }

            coldRevision = observed;
            accountingPasses++;
         }

         if (coldBytes + coldDerivedBytes <= limit && cold.size() <= 512) {
            trimNanos = trimNanos + (System.nanoTime() - started);
         } else {
            int work = 0;
            long deadline = System.nanoTime() + 500000L;
            ArrayList<UUID> owners = new ArrayList<>(cold.keySet());

            for (int checked = 0;
               checked < owners.size()
                  && checked < 16
                  && (coldBytes + coldDerivedBytes > limit || cold.size() > 512)
                  && work < 4
                  && (checked <= 0 || System.nanoTime() < deadline);
               checked++
            ) {
               UUID id = owners.get(Math.floorMod(coldCursor++, owners.size()));
               RoadIndex.Built present = INDEX.roads.get(id);
               ClientLevel level = Minecraft.getInstance().level;
               if (present != null && level != null && present.chunks.stream().anyMatch(chunk -> chunkLoaded(level, chunk))) {
                  awaken(id);
               } else {
                  trimVisits++;
                  long before = coldWeights.getOrDefault(id, 0L);
                  if (coldBytes > limit || cold.size() > 512) {
                     RoadIndex.Built road = INDEX.roads.get(id);
                     if (road != null) {
                        road.chunks.forEach(coldSync::evicted);
                     }

                     coldDerivedBytes -= before;
                     pruned++;
                     evictSource(id);
                     work++;
                  } else if (before != 0L) {
                     if (coldGpuTrimmed.add(id)) {
                        RoadRenderer.discardColdGpu(id);
                     } else {
                        RoadRenderer.discardDerived(id);
                        derivedEvictions++;
                     }

                     long after = RoadRenderer.retainedRoadBytes(id);
                     coldDerivedBytes = coldDerivedBytes - Math.max(0L, before - after);
                     work++;
                  }
               }
            }

            trimNanos = trimNanos + (System.nanoTime() - started);
         }
      }
   }

   public static void receive(CompoundTag t) {
      receive(t, null);
   }

   private static void receive(CompoundTag t, RoadIndex.Built decoded) {
      receive(t, decoded, List.of());
   }

   private static void receive(CompoundTag t, RoadIndex.Built decoded, List<RoadIndex.Built> batch) {
      receive(t, decoded, batch, null);
   }

   private static void receive(CompoundTag t, RoadIndex.Built decoded, List<RoadIndex.Built> batch, RoadPreviewData prepared) {
      Minecraft mc = Minecraft.getInstance();
      String type = t.getString("Type");
      if (type.equals("open")) {
         if (!t.contains("Dimension") || mc.level != null && mc.level.dimension().location().toString().equals(t.getString("Dimension"))) {
            open(t.getCompound("Payload"), prepared);
         }
      } else if (type.equals("resultPreview")) {
         if (!t.contains("Dimension") || mc.level != null && mc.level.dimension().location().toString().equals(t.getString("Dimension"))) {
            if (t.hasUUID("ReplyId") && !t.getBoolean("Success")) {
               RoadEditRequests.Request receipt = editRequests.completed(
                  t.getUUID("ReplyId"), t.getLong("ReplyGeneration"), t.getString("ReplyAction"), t.getString("ReplyTarget")
               );
               if (receipt != null) {
                  Object owner = receipt.owner();
                  if (mc.screen == null || mc.screen == owner || !toolScreen(mc.screen) || ManualPlannerClient.previewing(owner)) {
                     if (!(owner instanceof TollScreen) && !(owner instanceof SmartTextScreen)) {
                        List<RoadGeometry.Mesh> conflicts = prepared == null ? conflictRoads(t) : prepared.conflicts();
                        if (!conflicts.isEmpty()) {
                           preview = conflicts.get(0);
                           nodePreviews = conflicts;
                           conflictPreview = true;
                           RoadRenderer.previewCandidates(List.of());
                           String message = t.getString("Message");
                           if (owner instanceof RoadScreen screen) {
                              screen.failureGeometry(conflicts);
                           }

                           if (owner instanceof LaneRampScreen screen) {
                              screen.failureGeometry(message, conflicts);
                           }
                        }
                     }
                  }
               }
            }
         }
      } else if (type.equals("result")) {
         if (!t.contains("Dimension") || mc.level != null && mc.level.dimension().location().toString().equals(t.getString("Dimension"))) {
            if (!ManualPlannerClient.openResult(t)) {
               if (t.hasUUID("ReplyId")) {
                  RoadEditRequests.Request request = editRequests.take(
                     t.getUUID("ReplyId"), t.getLong("ReplyGeneration"), t.getString("ReplyAction"), t.getString("ReplyTarget")
                  );
                  if (request != null) {
                     Object owner = request.owner();
                     if (mc.screen == null || mc.screen == owner || !toolScreen(mc.screen)) {
                        boolean success = t.getBoolean("Success");
                        String messagex = t.getString("Message");
                        if (owner instanceof ManualPlannerScreen.PointDetails screen) {
                           screen.result(success, messagex);
                        } else if (owner instanceof ManualPlannerScreen screen) {
                           screen.result(success, messagex);
                        } else if (owner instanceof SmartTextScreen screen) {
                           SmartTextScreen.result(screen, success, messagex);
                        } else if (owner instanceof TollScreen screen) {
                           TollScreen.result(screen, success, messagex);
                        } else {
                           if (success) {
                              if (owner instanceof Screen screen) {
                                 ManualPlannerClient.completed(screen);
                              }

                              if (owner instanceof LaneRampScreen screen) {
                                 screen.completed();
                              }

                              if (owner instanceof RoadScreen screen) {
                                 screen.completed();
                              }

                              if (owner instanceof InterchangeScreen screen) {
                                 screen.completed();
                              }

                              if (owner instanceof CorridorScreen screen) {
                                 screen.completed();
                              }

                              if (owner instanceof YJunctionScreen screen) {
                                 screen.completed();
                              }

                              if (owner instanceof InterchangeScreen) {
                                 InterchangeScreen.clear();
                              }

                              if (owner instanceof LaneRampScreen) {
                                 LaneRampScreen.clear();
                              }

                              if (owner instanceof CorridorScreen) {
                                 CorridorScreen.clear();
                              }

                              if (owner instanceof JunctionScreen || owner instanceof JunctionRoadScreen) {
                                 JunctionScreen.clear();
                              }

                              if (owner instanceof RoadScreen || owner instanceof RoadDeleteScreen) {
                                 discardDraft();
                              }

                              preview = null;
                              nodePreviews = List.of();
                              conflictPreview = false;
                              error = "";
                              RoadRenderer.previewCandidates(List.of());
                              if (mc.screen == owner) {
                                 mc.setScreen(null);
                              }
                           } else {
                              error = messagex;
                              List<RoadGeometry.Mesh> conflicts = prepared == null ? conflictRoads(t) : prepared.conflicts();
                              if (!conflicts.isEmpty()) {
                                 preview = conflicts.get(0);
                                 nodePreviews = conflicts;
                                 conflictPreview = true;
                                 RoadRenderer.previewCandidates(List.of());
                              }

                              if (owner instanceof InterchangeScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof CorridorScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof RoadScreen screen) {
                                 screen.failed(error, conflicts);
                              }

                              if (owner instanceof LaneLineScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof ElevatedAccessScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof AttachedPointScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof LanePointScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof LaneRampScreen screen) {
                                 screen.failed(error, conflicts);
                              }

                              if (owner instanceof JunctionRoadScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof YJunctionScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof GantryScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof ManualAccessScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof JunctionScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof RoundaboutScreen screen) {
                                 screen.failed(error);
                              }

                              if (owner instanceof RoadDeleteScreen screen) {
                                 screen.failed(error);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      } else if (mc.level != null && mc.level.dimension().location().toString().equals(t.getString("Dimension"))) {
         ensureWorld();

         try {
            if (type.equals("syncReset")) {
               TollScreen.deliveryRecovery();
               LaneRampScreen.deliveryRecovery();
               CorridorScreen.deliveryRecovery();
               if (!t.getBoolean("ResetRoads")) {
                  if (coldSync.pending()) {
                     coldSyncAcks++;
                  }

                  coldSync.acknowledged();
               }

               if (t.getBoolean("ResetRoads")) {
                  RoadRenderer.reset();
                  INDEX.clear();
                  ClientToolIndex.clear();
                  prune.clear();
                  resetCold();
                  SmartSignClient.clear();
                  receivedBatches.clear();
               }

               return;
            }

            if (type.equals("roadBatch")) {
               receiveBatch(t, batch);
               return;
            }

            if (type.equals("smartSigns")) {
               SmartSignClient.receive(t);
               return;
            }

            if (type.equals("road")) {
               RoadRecord r = decoded == null ? RoadRecord.load(t.getCompound("Road")) : decoded.record;
               RoadIndex.Built previous = INDEX.roads.get(r.id());
               awaken(r.id());
               residencyRoads.add(r.id());
               if (previous == null || !previous.record.equals(r)) {
                  Set<UUID> changed = new HashSet<>();
                  changed.add(r.id());
                  if (previous != null) {
                     INDEX.surfaceNeighbors(previous).forEach(b -> changed.add(b.record.id()));
                  }

                  RoadIndex.Built built = decoded == null ? new RoadIndex.Built(r, true) : decoded;
                  SmartSignClient.roadChanged(r.id());
                  INDEX.put(built);
                  residencyRoads.add(r.id());
                  INDEX.surfaceNeighbors(built).forEach(b -> changed.add(b.record.id()));
                  RoadRenderer.changed(changed);
               }
            } else if (type.equals("lanePoints")) {
               RoadIndex.Built old = INDEX.roads.get(t.getUUID("Id"));
               if (old != null && old.record.header().hashCode() == t.getInt("Signature")) {
                  INDEX.lanePoints(old.record.id(), LanePointCodec.read(t.getCompound("Points")));
               } else {
                  CompoundTag request = new CompoundTag();
                  request.putString("Action", "lanePointResync");
                  request.putUUID("Id", t.getUUID("Id"));
                  RoadNetwork.sendToServer(new RoadNetwork.Action(request));
               }
            } else if (type.equals("delete")) {
               remove(t.getUUID("Id"));
               if (draft != null && draft.hasUUID("Id") && draft.getUUID("Id").equals(t.getUUID("Id"))) {
                  preview = null;
               }
            }
         } catch (IllegalArgumentException var12) {
            error = var12.getMessage();
         }
      }
   }

   static List<RoadGeometry.Mesh> conflictRoads(CompoundTag reply) {
      ArrayList<RoadGeometry.Mesh> roads = new ArrayList<>();

      for (Tag value : reply.getList("ConflictRoads", 10)) {
         try {
            roads.add(RoadRecord.load((CompoundTag)value).mesh());
         } catch (IllegalArgumentException var5) {
         }
      }

      return List.copyOf(roads);
   }

   private static void receiveBatch(CompoundTag packet, List<RoadIndex.Built> decoded) {
      UUID token = packet.getUUID("Batch");
      if (receivedBatches.contains(token)) {
         RoadNetwork.ackBatch(token);
      } else {
         List<RoadIndex.Built> batch = decoded.isEmpty()
            ? packet.getList("Roads", 10).stream().map(t -> new RoadIndex.Built(RoadRecord.load((CompoundTag)t), true)).toList()
            : decoded;
         HashSet<UUID> ids = new HashSet<>();

         for (RoadIndex.Built r : batch) {
            ids.add(r.record.id());
         }

         HashSet<UUID> changed = new HashSet<>(ids);

         for (Tag entry : packet.getList("Replaced", 10)) {
            UUID id = ((CompoundTag)entry).getUUID("Id");
            changed.add(id);
            RoadIndex.Built old = INDEX.roads.get(id);
            if (old != null) {
               INDEX.surfaceNeighbors(old).forEach(r -> changed.add(r.record.id()));
            }

            if (!ids.contains(id)) {
               Long weight = cold.remove(id);
               if (weight != null) {
                  coldBytes = coldBytes - weight;
                  coldChanged();
                  coldGpuTrimmed.remove(id);
               }

               INDEX.remove(id);
               SmartSignClient.remove(id);
            }
         }

         for (RoadIndex.Built r : batch) {
            awaken(r.record.id());
            SmartSignClient.roadChanged(r.record.id());
            INDEX.put(r);
            residencyRoads.add(r.record.id());
         }

         for (RoadIndex.Built r : batch) {
            INDEX.surfaceNeighbors(r).forEach(b -> changed.add(b.record.id()));
         }

         for (Tag entry : packet.getList("SignPackets", 10)) {
            SmartSignClient.receive((CompoundTag)entry);
         }

         ClientLevel level = Minecraft.getInstance().level;
         if (level != null && packet.contains("Node")) {
            BlockPos pos = BlockPos.of(packet.getLong("NodePos"));
            if (level.getBlockEntity(pos) instanceof NodeEntity marker) {
               marker.readSnapshot(packet.getCompound("Node"), level.registryAccess());
            }
         }

         RoadRenderer.changedBatch(changed, ids);
         receivedBatches.add(token);

         while (receivedBatches.size() > 256) {
            receivedBatches.remove(receivedBatches.iterator().next());
         }

         RoadNetwork.ackBatch(token);
      }
   }

   private static void remove(UUID id) {
      remove(id, false);
   }

   private static void evictSource(UUID id) {
      remove(id, true);
   }

   private static void remove(UUID id, boolean localEviction) {
      Long weight = cold.remove(id);
      if (weight != null) {
         coldBytes = coldBytes - weight;
         coldChanged();
         coldGpuTrimmed.remove(id);
      }

      RoadIndex.Built old = INDEX.roads.get(id);
      Set<UUID> changed = new HashSet<>();
      changed.add(id);
      if (old != null) {
         INDEX.surfaceNeighbors(old).forEach(b -> changed.add(b.record.id()));
      }

      INDEX.remove(id);
      if (localEviction) {
         SmartSignClient.evictSource(id);
      } else {
         SmartSignClient.remove(id);
      }

      if (localEviction) {
         RoadRenderer.sourceEvicted(changed);
      } else {
         RoadRenderer.changed(changed);
      }
   }

   private static void ensureWorld() {
      Minecraft mc = Minecraft.getInstance();
      String d = mc.level == null ? "" : mc.level.dimension().location().toString();
      if (currentLevel != mc.level || !d.equals(dimension)) {
         InterchangeScreen.clear();
         TollScreen.clear();
         RailbedScreen.reset();
         LaneRampScreen.clear();
         CorridorScreen.clear();
         JunctionScreen.clear();
         TollScreen.clear();
         receivedBatches.clear();
         RoadRenderer.reset();
         INDEX.clear();
         ClientToolIndex.clear();
         prune.clear();
         resetCold();
         SmartSignClient.clear();
         inbox.reset();
         editRequests.reset();
         discardDraft();
         preview = null;
         nodePreviews = List.of();
         conflictPreview = false;
         error = "";
         dimension = d;
         currentLevel = mc.level;
      }
   }

   public static void open(CompoundTag t) {
      open(t, null);
   }

   private static void open(CompoundTag t, RoadPreviewData prepared) {
      ensureWorld();
      Minecraft mc = Minecraft.getInstance();
      if (!ManualPlannerClient.receive(t)) {
         if (t.hasUUID("ReplyId")) {
            RoadEditRequests.Request request = editRequests.take(
               t.getUUID("ReplyId"), t.getLong("ReplyGeneration"), t.getString("ReplyAction"), t.getString("ReplyTarget")
            );
            if (request == null
               || mc.screen != null && mc.screen != request.owner() && toolScreen(mc.screen) && !ManualPlannerClient.previewing(request.owner())) {
               return;
            }
         }

         String kind = t.getString("Kind");
         if (kind.equals("reset") || kind.endsWith("Reset") && !kind.equals("laneRampReset") && !kind.equals("tollReset")) {
            editRequests.reset();
         }

         switch (kind) {
            case "splitCheck":
               RoadSplitterClient.checked(t);
               return;
            case "tollCheck":
               TollScreen.open(t, prepared);
               return;
            case "corridorCheck":
               CorridorScreen.open(t);
               return;
            case "laneRampCheck":
               LaneRampScreen.checkedReply(t, prepared);
               return;
            default:
               Screen previousScreen = mc.screen;
               RoadGeometry.Mesh previousPreview = preview;
               List<RoadGeometry.Mesh> previousRoads = nodePreviews;
               String previousError = error;
               boolean previousConflict = conflictPreview;
               dispatchOpen(t);
               ManualPlannerClient.opened(t);
               if (mc.screen != null && mc.screen != previousScreen) {
                  beginEditor(mc.screen);
                  if (!(mc.screen instanceof LaneRampScreen)) {
                     conflictPreview = false;
                     if (previousConflict && preview == previousPreview) {
                        preview = null;
                     }

                     if (previousConflict && nodePreviews == previousRoads) {
                        nodePreviews = List.of();
                     }

                     if (previousConflict && error == previousError) {
                        error = "";
                     }
                  }
               }
         }
      }
   }

   private static void dispatchOpen(CompoundTag t) {
      Minecraft mc = Minecraft.getInstance();
      String kind = t.getString("Kind");
      if (kind.equals("elevatedAccess")) {
         mc.setScreen(new ElevatedAccessScreen(t));
      } else if (kind.equals("manualAccess")) {
         mc.setScreen(new ManualAccessScreen(t));
      } else if (kind.startsWith("railbed")) {
         RailbedScreen.open(t);
      } else if (kind.startsWith("toll")) {
         TollScreen.open(t);
      } else if (kind.equals("laneRampReset")) {
         if (LaneRampScreen.clear()) {
            error = "";
         }

         if (mc.screen instanceof LaneRampScreen) {
            mc.setScreen(null);
         }
      } else if (kind.equals("lanePoint")) {
         mc.setScreen(new LanePointScreen(t));
         if (t.hasUUID("Interaction")) {
            CompoundTag ack = new CompoundTag();
            ack.putString("Action", "lanePointReady");
            ack.putUUID("Interaction", t.getUUID("Interaction"));
            RoadNetwork.sendToServer(new RoadNetwork.Action(ack));
         }
      } else if (kind.equals("laneRamp")) {
         LaneRampScreen.open(t);
      } else if (kind.equals("laneRampResume")) {
         LaneRampScreen.resume(t);
      } else if (kind.equals("armRoad") || kind.equals("sideRoad")) {
         Minecraft.getInstance().setScreen(new RoadScreen(t));
      } else if (kind.equals("attachedPoint")) {
         Minecraft.getInstance().setScreen(new AttachedPointScreen(t));
      } else if (kind.equals("laneLines")) {
         Minecraft.getInstance().setScreen(new LaneLineScreen(t));
      } else if (kind.equals("yJunction")) {
         Minecraft.getInstance().setScreen(new YJunctionScreen(t));
      } else if (kind.startsWith("smartText")) {
         SmartTextScreen.open(t);
      } else if (kind.equals("gantry")) {
         Minecraft.getInstance().setScreen(new GantryScreen(t));
      } else if (kind.equals("roundabout")) {
         Minecraft.getInstance().setScreen(new RoundaboutScreen(t));
      } else if (kind.startsWith("corridor")) {
         CorridorScreen.open(t);
      } else if (kind.startsWith("junction")) {
         JunctionScreen.open(t);
      } else if (kind.startsWith("interchange")) {
         InterchangeScreen.open(t);
      } else if (kind.equals("reset")) {
         editRequests.reset();
         discardDraft();
         preview = null;
         nodePreviews = List.of();
         conflictPreview = false;
         error = "";
      } else if (kind.equals("resume")) {
         CompoundTag saved = draft;
         if (saved != null) {
            draft = saved.copy();
            mc.setScreen(new RoadScreen(draft));
         } else if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("先右键选择两个道路端点"), true);
         }
      } else if (t.getBoolean("DeleteOnly")) {
         preview = null;
         nodePreviews = List.of();
         conflictPreview = false;
         error = "";
         mc.setScreen(new RoadDeleteScreen(t));
      } else {
         t = RoadDraft.merge(t, draft);
         storeDraft(t);
         preview = null;
         error = "";
         mc.setScreen(new RoadScreen(draft));
      }
   }

   @SubscribeEvent
   public static void logout(LoggingOut e) {
      InterchangeScreen.clear();
      TollScreen.clear();
      RailbedScreen.reset();
      LaneRampScreen.clear();
      CorridorScreen.clear();
      RoadRenderer.reset();
      INDEX.clear();
      ClientToolIndex.clear();
      prune.clear();
      resetCold();
      SmartSignClient.clear();
      inbox.reset();
      editRequests.reset();
      discardDraft();
      preview = null;
      nodePreviews = List.of();
      conflictPreview = false;
      error = "";
      dimension = "";
      currentLevel = null;
   }

   @SubscribeEvent
   public static void tick(Post e) {
      ensureWorld();
      inbox.drain(event -> {
         if (event.error() != null) {
            error = event.error();
         } else {
            receive(event.tag(), event.built(), event.batch(), event.preview());
         }
      });
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null) {
         long streamDeadline = System.nanoTime() + 500000L;
         RoadScanBudget chunks = new RoadScanBudget(16, streamDeadline);

         while (!residencyChunks.isEmpty() && chunks.take()) {
            Iterator<Long> it = residencyChunks.iterator();
            long chunk = it.next();
            it.remove();
            residencyRoads.addAll(INDEX.inChunk(chunk));
            if (chunkLoaded(level, chunk)) {
               coldSync.arrived(chunk);
            }
         }

         RoadScanBudget roads = new RoadScanBudget(16, streamDeadline);

         while (!residencyRoads.isEmpty() && roads.take()) {
            Iterator<UUID> it = residencyRoads.iterator();
            UUID id = it.next();
            it.remove();
            refreshResidency(level, id);
         }

         if (++ticks % 100 == 0 && prune.isEmpty()) {
            prune.addAll(INDEX.roads.keySet());
         }

         long deadline = System.nanoTime() + 500000L;

         for (int n = 0; n < 4 && !prune.isEmpty() && System.nanoTime() < deadline; n++) {
            refreshResidency(level, prune.removeFirst());
         }

         trimCold();
         if (coldSync.request(level.getGameTime())) {
            coldSyncRequests++;
            RoadNetwork.requestRoadSync();
         }

         joinTicks++;
         requestWideRoadData(level);
      }
   }

   /**
    * Ask the server for road records in the DH band as well. Normal chunk watch only
    * covers the vanilla view distance, so roads the player has never visited would
    * stay unknown to the client and could not be drawn as real geometry out there.
    */
   private static void requestWideRoadData(ClientLevel level) {
      if (!ModList.get().isLoaded("distanthorizons")) {
         return;
      }

      int radius = (Integer)RoadClientConfig.DISTANT_ROAD_CHUNKS.get();
      if (radius <= 0) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null) {
         return;
      }

      double x = mc.player.getX();
      double z = mc.player.getZ();
      long now = level.getGameTime();
      if (joinTicks < 40) {
         return;
      }

      // World entry used to ask for the whole DH band at once, which queued thousands of
      // road meshes while the client was still building the near chunks. Stream it in two
      // steps instead: first a modest ring, then the configured radius once the queue is idle.
      if (wideSyncStage == 0) {
         if (!RoadRenderer.buildQueueIdle() && joinTicks < 400) {
            return;
         }

         wideSyncStage = 1;
         wideSyncSent = true;
         lastWideSyncTick = now;
         wideSyncX = x;
         wideSyncZ = z;
         RoadNetwork.requestWideSync(Math.min(radius, 12));
         return;
      }

      boolean moved = (x - wideSyncX) * (x - wideSyncX) + (z - wideSyncZ) * (z - wideSyncZ) >= 16384.0;
      boolean due = moved && now - lastWideSyncTick >= 200L;
      boolean expand = wideSyncStage == 1 && RoadRenderer.buildQueueIdle() && now - lastWideSyncTick >= 100L;
      if (!due && !expand) {
         return;
      }

      wideSyncStage = 2;
      wideSyncSent = true;
      lastWideSyncTick = now;
      wideSyncX = x;
      wideSyncZ = z;
      RoadNetwork.requestWideSync(radius);
   }

   static {
      INDEX.changed = id -> {
         ClientToolIndex.changed(id);
         SmartSignClient.candidatesChanged(id);
      };
   }

   @EventBusSubscriber(
      modid = "splineroads",
      value = {Dist.CLIENT},
      bus = Bus.MOD
   )
   public static final class Setup {
      @SubscribeEvent
      public static void setup(FMLClientSetupEvent e) {
         e.enqueueWork(() -> {
            RoadBlocks.clientIndex = l -> ClientRoads.INDEX;
            RoadTool.openClient = ClientRoads::open;
            RoadSplitTool.activateClient = RoadSplitterClient::activate;
         });
      }
   }
}
