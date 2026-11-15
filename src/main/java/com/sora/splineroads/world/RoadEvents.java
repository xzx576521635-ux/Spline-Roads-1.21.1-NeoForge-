package com.sora.splineroads.world;

import com.sora.splineroads.net.RoadNetwork;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock;
import net.neoforged.neoforge.event.level.BlockEvent.BreakEvent;
import net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent.UnWatch;
import net.neoforged.neoforge.event.level.ChunkWatchEvent.Watch;
import net.neoforged.neoforge.event.level.ExplosionEvent.Detonate;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;

@EventBusSubscriber(
   modid = "splineroads"
)
public final class RoadEvents {
   @SubscribeEvent
   public static void infill(RightClickBlock e) {
      if (e.getItemStack().getItem() instanceof BlockItem item && e.getFace() != null) {
         Level level = e.getLevel();
         BlockState fill = item.getBlock().defaultBlockState();
         BlockPos target = e.getPos().relative(e.getFace());
         Vec3 hit = e.getHitVec().getLocation();

         double coordinate = switch (e.getFace().getAxis()) {
            case X -> hit.x - (double)e.getPos().getX();
            case Y -> hit.y - (double)e.getPos().getY();
            case Z -> hit.z - (double)e.getPos().getZ();
            default -> throw new MatchException(null, null);
         };
         boolean inside = coordinate > 0.001 && coordinate < 0.999;
         RoadIndex index = RoadBlocks.index(level);
         if (inside && index != null && RoadBlocks.gantryCell(index, e.getPos()) && RoadBlocks.canInfill(level, e.getPos(), fill)) {
            target = e.getPos();
         } else if (!RoadBlocks.canInfill(level, target, fill)) {
            if (!inside) {
               return;
            }

            target = e.getPos();
            if (!RoadBlocks.canInfill(level, target, fill)) {
               return;
            }
         }

         BlockPlaceContext context = BlockPlaceContext.at(
            new BlockPlaceContext(e.getEntity(), e.getHand(), e.getItemStack(), e.getHitVec()), target, e.getFace()
         );
         fill = item.getBlock().getStateForPlacement(context);
         if (fill != null && RoadBlocks.canInfill(level, target, fill)) {
            if (e.getEntity().mayBuild() && e.getEntity().mayUseItemAt(target, e.getFace(), e.getItemStack())) {
               if (level instanceof ServerLevel server && e.getEntity() instanceof ServerPlayer player) {
                  BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, target);
                  final BlockState placed = fill;
                  var placeEvent = new EntityPlaceEvent(snapshot, level.getBlockState(target.relative(e.getFace().getOpposite())), player) {
                     public BlockState getPlacedBlock() {
                        return placed;
                     }

                     public BlockState getState() {
                        return placed;
                     }
                  };
                  if (((EntityPlaceEvent)NeoForge.EVENT_BUS.post(placeEvent)).isCanceled()) {
                     return;
                  }

                  if (!RoadData.get(server).infill(server, target, fill, player)) {
                     return;
                  }

                  if (!player.isCreative()) {
                     e.getItemStack().shrink(1);
                  }

                  level.playSound(null, target, fill.getSoundType(level, target, player).getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
               }

               e.setCanceled(true);
               e.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
               return;
            }

            return;
         }

         return;
      }
   }

   @SubscribeEvent
   public static void watch(Watch e) {
      RoadNetwork.watch(e.getPlayer(), e.getPos().toLong(), true);
      RoadData data = RoadData.get(e.getLevel());
      data.repairChunk(e.getLevel(), e.getPos());
      RoadIndex index = data.index;

      for (UUID id : index.inChunk(e.getPos().toLong())) {
         RoadNetwork.road(e.getPlayer(), index.roads.get(id).record);
      }
   }

   @SubscribeEvent
   public static void unwatch(UnWatch e) {
      RoadNetwork.watch(e.getPlayer(), e.getPos().toLong(), false);
   }

   @SubscribeEvent
   public static void dimension(PlayerChangedDimensionEvent e) {
      if (e.getEntity() instanceof ServerPlayer player) {
         RoadSplitter.cancel(player.getUUID());
         RoadData.forgetPreviews(player.getUUID());
         RoadNetwork.dimensionChanged(player);
      }
   }

   @SubscribeEvent
   public static void breakBlock(BreakEvent e) {
      if (!RailbedBuildQueue.protectionProbe() && !RoadEditProtection.probing) {
         if (e.getLevel() instanceof ServerLevel level) {
            RoadData data = RoadData.get(level);
            if (RoadBlocks.isCollider(e.getState()) && e.getPlayer() instanceof ServerPlayer player && data.excavateSupport(level, e.getPos(), player)) {
               e.setCanceled(true);
               return;
            }

            if (!RoadBlocks.isCollider(e.getState()) && !data.linked(e.getPos())) {
               if (level.getBlockEntity(e.getPos()) instanceof NodeEntity n
                  && n.owner != null
                  && !n.owner.equals(e.getPlayer().getUUID())
                  && !e.getPlayer().hasPermissions(2)) {
                  e.setCanceled(true);
               }
            } else {
               e.setCanceled(true);
               e.getPlayer().displayClientMessage(Component.literal("请用道路连接器删除相连道路，再拆除端点"), true);
            }
         }
      }
   }

   @SubscribeEvent
   public static void explosion(Detonate e) {
      if (e.getLevel() instanceof ServerLevel level) {
         RoadData d = RoadData.get(level);
         e.getAffectedBlocks().removeIf(p -> d.linked(p) || RoadBlocks.isCollider(level.getBlockState(p)));
      }
   }

   @SubscribeEvent
   public static void tick(Post e) {
      RoadNetwork.tick(e.getServer());
      ManualPlanner.tick();
      RailbedBuildQueue.tick(e.getServer());
      if (e.getServer().getTickCount() % 100 == 0) {
         RoadData.expirePreviews();
      }
   }

   @SubscribeEvent
   public static void stop(ServerStoppingEvent e) {
      ManualPlanner.stop();
      RailbedBuildQueue.stop();
      RoadSplitter.clear();
      RoadData.clearPreviews();
      RoadNetwork.stop();
   }

   @SubscribeEvent
   public static void logout(PlayerLoggedOutEvent e) {
      if (e.getEntity() instanceof ServerPlayer p) {
         ManualPlanner.forget(p);
      }

      RoadNetwork.forget(e.getEntity().getUUID());
      RoadData.forgetPreviews(e.getEntity().getUUID());
   }
}
