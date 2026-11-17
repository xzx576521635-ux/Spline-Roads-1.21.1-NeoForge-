package com.sora.splineroads.world;

import com.mojang.brigadier.CommandDispatcher;
import com.sora.splineroads.SplineRoads;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent.BreakEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;

@EventBusSubscriber(
   modid = "splineroads"
)
public final class RoadCommands {
   private static final Map<String, RoadCommands.Species> SPECIES = Map.of(
      "oak",
      new RoadCommands.Species(BlockTags.OAK_LOGS, Blocks.OAK_LEAVES),
      "spruce",
      new RoadCommands.Species(BlockTags.SPRUCE_LOGS, Blocks.SPRUCE_LEAVES),
      "birch",
      new RoadCommands.Species(BlockTags.BIRCH_LOGS, Blocks.BIRCH_LEAVES),
      "jungle",
      new RoadCommands.Species(BlockTags.JUNGLE_LOGS, Blocks.JUNGLE_LEAVES),
      "acacia",
      new RoadCommands.Species(BlockTags.ACACIA_LOGS, Blocks.ACACIA_LEAVES),
      "dark_oak",
      new RoadCommands.Species(BlockTags.DARK_OAK_LOGS, Blocks.DARK_OAK_LEAVES),
      "mangrove",
      new RoadCommands.Species(BlockTags.MANGROVE_LOGS, Blocks.MANGROVE_LEAVES),
      "cherry",
      new RoadCommands.Species(BlockTags.CHERRY_LOGS, Blocks.CHERRY_LEAVES)
   );
   private static final Map<MinecraftServer, ArrayDeque<RoadCommands.ClearJob>> JOBS = new IdentityHashMap<>();
   private static final long MAX_CELLS = 64000000L;

   @SubscribeEvent
   public static void register(RegisterCommandsEvent event) {
      register(event.getDispatcher());
   }

   public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
      LiteralArgumentBuilder<CommandSourceStack> root = (LiteralArgumentBuilder<CommandSourceStack>)Commands.literal("sr")
         .requires(source -> source.hasPermission(2));
      String[][] aliases = new String[][]{
         {"day", "time set day"}, {"night", "time set midnight"}, {"rain", "weather rain"}, {"sun", "weather clear"}, {"kd", "kill @e[type=item]"}
      };

      for (String[] pair : aliases) {
         root.then(Commands.literal(pair[0]).executes(c -> {
            ((CommandSourceStack)c.getSource()).getServer().getCommands().performPrefixedCommand((CommandSourceStack)c.getSource(), pair[1]);
            return 1;
         }));
      }

      RequiredArgumentBuilder<CommandSourceStack, Integer> treeRadius = (RequiredArgumentBuilder<CommandSourceStack, Integer>)Commands.argument(
            "r", IntegerArgumentType.integer(1, 1024)
         )
         .executes(c -> start(c, true, false, ""));
      RequiredArgumentBuilder<CommandSourceStack, Integer> treeHeight = (RequiredArgumentBuilder<CommandSourceStack, Integer>)Commands.argument(
            "h", IntegerArgumentType.integer(1, 1024)
         )
         .executes(c -> start(c, true, true, ""));

      for (String species : new TreeSet<>(SPECIES.keySet())) {
         treeRadius.then(Commands.literal(species).executes(c -> start(c, true, false, species)));
         treeHeight.then(Commands.literal(species).executes(c -> start(c, true, true, species)));
      }

      treeRadius.then(treeHeight);
      root.then(Commands.literal("tree").then(treeRadius));
      root.then(
         Commands.literal("plant")
            .then(
               ((RequiredArgumentBuilder)Commands.argument("r", IntegerArgumentType.integer(1, 1024)).executes(c -> start(c, false, false, "")))
                  .then(Commands.argument("h", IntegerArgumentType.integer(1, 1024)).executes(c -> start(c, false, true, "")))
            )
      );
      root.then(
         Commands.literal("markers")
            .then(Commands.literal("prune").executes(c -> pruneMarkers(c.getSource())))
            .then(
               Commands.literal("orphans")
                  .executes(c -> orphanMarkers(c.getSource(), 64, false))
                  .then(
                     Commands.literal("remove")
                        .executes(c -> orphanMarkers(c.getSource(), 64, true))
                        .then(
                           Commands.argument("radius", IntegerArgumentType.integer(8, 160))
                              .executes(c -> orphanMarkers(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), true))
                        )
                  )
                  .then(
                     Commands.argument("radius", IntegerArgumentType.integer(8, 160))
                        .executes(c -> orphanMarkers(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), false))
                  )
            )
            .then(Commands.literal("rebuild").executes(c -> rebuildMarkers(c.getSource())))
      );
      root.then(
         Commands.literal("colliders")
            .then(
               Commands.literal("audit")
                  .executes(c -> colliders(c.getSource(), 64, false))
                  .then(
                     Commands.argument("radius", IntegerArgumentType.integer(8, 160))
                        .executes(c -> colliders(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), false))
                  )
            )
            .then(
               Commands.literal("prune")
                  .executes(c -> colliders(c.getSource(), 64, true))
                  .then(
                     Commands.argument("radius", IntegerArgumentType.integer(8, 160))
                        .executes(c -> colliders(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), true))
                  )
            )
      );
      dispatcher.register(root);
   }

   private static CommandSyntaxException error(String text) {
      return new SimpleCommandExceptionType(Component.literal(text)).create();
   }

   private static int start(CommandContext<CommandSourceStack> context, boolean trees, boolean hasHeight, String except) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)context.getSource();
      ServerPlayer player = source.getPlayerOrException();
      int radius = IntegerArgumentType.getInteger(context, "r");
      int height = hasHeight ? IntegerArgumentType.getInteger(context, "h") : radius;
      ArrayDeque<RoadCommands.ClearJob> queue = JOBS.computeIfAbsent(source.getServer(), s -> new ArrayDeque<>());
      if (queue.stream().anyMatch(j -> j.player.getUUID().equals(player.getUUID()))) {
         throw error("上一次 SR 清理尚未完成，请稍后再执行");
      } else {
         RoadCommands.ClearJob job = new RoadCommands.ClearJob(source, player, radius, height, trees, except);
         if ((long)(2 * radius + 1) * (long)(2 * radius + 1) * (long)(job.high - job.low) > 64000000L) {
            throw error("清理范围超过 6400 万格，请减小半径 r 或高度 h");
         } else {
            queue.add(job);
            source.sendSuccess(
               () -> Component.literal(
                     "开始清理" + (trees ? "原木和树叶" : "杂草和花卉") + "：半径 " + radius + "，高度 " + height + "（从玩家脚下向上）" + (except.isEmpty() ? "" : "，保留 " + except)
                  ),
               false
            );
            return 1;
         }
      }
   }

   static boolean matches(BlockState state, boolean trees, String except) {
      if (trees) {
         if (!state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
            return false;
         } else {
            RoadCommands.Species keep = SPECIES.get(except);
            return keep == null || !state.is(keep.logs()) && !state.is(keep.leaves());
         }
      } else {
         return state.is(BlockTags.FLOWERS) && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.SAPLINGS)
            || state.is(Blocks.SHORT_GRASS)
            || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.FERN)
            || state.is(Blocks.LARGE_FERN)
            || state.is(Blocks.DEAD_BUSH);
      }
   }

   @SubscribeEvent
   public static void tick(Post event) {
      tick(event.getServer());
   }

   static void tick(MinecraftServer server) {
      ArrayDeque<RoadCommands.ClearJob> queue = JOBS.get(server);
      if (queue != null && !queue.isEmpty()) {
         long deadline = System.nanoTime() + 2000000L;
         int budget = 8192;

         while (!queue.isEmpty() && budget > 0 && System.nanoTime() < deadline) {
            RoadCommands.ClearJob job = queue.removeFirst();
            int used = job.step(Math.min(512, budget), deadline);
            budget -= used;
            if (job.done()) {
               job.finish();
            } else {
               queue.addLast(job);
            }
         }

         if (queue.isEmpty()) {
            JOBS.remove(server);
         }
      }
   }

   @SubscribeEvent
   public static void stopped(ServerStoppedEvent event) {
      JOBS.remove(event.getServer());
   }

   static boolean pending(MinecraftServer server) {
      ArrayDeque<RoadCommands.ClearJob> q = JOBS.get(server);
      return q != null && !q.isEmpty();
   }

   private RoadCommands() {
   }

   private static final class ClearJob {
      final CommandSourceStack source;
      final ServerPlayer player;
      final ServerLevel level;
      final int radius;
      final int low;
      final int high;
      final int cx;
      final int cz;
      final boolean trees;
      final String except;
      final MutableBlockPos pos = new MutableBlockPos();
      int x;
      int z;
      int y;
      int removed;
      int skipped;
      boolean columnChecked;

      ClearJob(CommandSourceStack source, ServerPlayer player, int radius, int height, boolean trees, String except) {
         this.source = source;
         this.player = player;
         this.level = player.serverLevel();
         this.radius = radius;
         this.trees = trees;
         this.except = except;
         BlockPos center = player.blockPosition();
         this.cx = center.getX();
         this.cz = center.getZ();
         this.low = Math.max(this.level.getMinBuildHeight(), Math.min(this.level.getMaxBuildHeight(), center.getY()));
         this.high = Math.max(this.low, Math.min(this.level.getMaxBuildHeight(), center.getY() + height));
         this.x = this.z = -radius;
         this.y = this.low;
      }

      boolean done() {
         return this.x > this.radius || this.high <= this.low;
      }

      void column() {
         this.y = this.low;
         this.columnChecked = false;
         if (++this.z > this.radius) {
            this.z = -this.radius;
            this.x++;
         }
      }

      int step(int budget, long deadline) {
         int used = 0;

         while (!this.done() && used < budget && (used % 32 != 0 || System.nanoTime() < deadline)) {
            used++;
            if (!this.columnChecked) {
               if ((long)this.x * (long)this.x + (long)this.z * (long)this.z > (long)this.radius * (long)this.radius) {
                  this.column();
                  continue;
               }

               this.pos.set(this.cx + this.x, this.low, this.cz + this.z);
               if (!this.level.hasChunkAt(this.pos) || !this.level.getWorldBorder().isWithinBounds(this.pos)) {
                  this.skipped++;
                  this.column();
                  continue;
               }

               this.columnChecked = true;
            }

            this.pos.set(this.cx + this.x, this.y, this.cz + this.z);
            if (!this.level.hasChunkAt(this.pos)) {
               this.skipped++;
               this.column();
            } else {
               BlockState state = this.level.getBlockState(this.pos);
               if (RoadCommands.matches(state, this.trees, this.except)) {
                  BreakEvent event = new BreakEvent(this.level, this.pos.immutable(), state, this.player);
                  if (this.level.mayInteract(this.player, this.pos)
                     && !((BreakEvent)NeoForge.EVENT_BUS.post(event)).isCanceled()
                     && this.level.setBlock(this.pos, state.getFluidState().createLegacyBlock(), 18)) {
                     this.removed++;
                  }
               }

               if (++this.y >= this.high) {
                  this.column();
               }
            }
         }

         return used;
      }

      void finish() {
         this.source
            .sendSuccess(
               () -> Component.literal("SR 清理完成：移除 " + this.removed + " 个方块" + (this.skipped == 0 ? "" : "；跳过 " + this.skipped + " 个未加载或边界外的方块列")), true
            );
      }
   }

   private static record Species(TagKey<Block> logs, Block leaves) {
   }

   /**
    * Removes marker blocks whose owning road no longer exists and drops their ledger entries.
    * Chunks that are not loaded are skipped (run the command again after visiting them).
    */
   private static int pruneMarkers(CommandSourceStack source) {
      ServerLevel level = source.getLevel();
      RoadData data = RoadData.get(level);
      RoadMarkerData markers = RoadMarkerData.get(level);
      int removed = 0;
      int skipped = 0;
      int roads = 0;

      for (java.util.UUID id : new java.util.ArrayList<>(markers.roads())) {
         if (data.index.roads.containsKey(id) || data.streets.containsKey(id)) {
            continue;
         }

         Map<Long, RoadMarker.Kind> positions = markers.positions(id);
         boolean complete = true;

         for (long key : new java.util.ArrayList<>(positions.keySet())) {
            BlockPos pos = BlockPos.of(key);
            if (!level.isLoaded(pos)) {
               complete = false;
               skipped++;
               continue;
            }

            if (level.getBlockState(pos).is((Block)SplineRoads.MARKER.get()) && markers.roadsAt(key).size() <= 1) {
               level.removeBlock(pos, false);
               removed++;
            }
         }

         if (complete) {
            markers.remove(id);
            roads++;
         }
      }

      markers.setDirty();
      int removedFinal = removed;
      int skippedFinal = skipped;
      int roadsFinal = roads;
      source.sendSuccess(
         () -> Component.literal(
            "SR 标记清理：移除 " + removedFinal + " 个标记方块，清理 " + roadsFinal + " 条已删除道路的账本"
               + (skippedFinal > 0 ? "；另有 " + skippedFinal + " 个在未加载区块，请再运行一次" : "")
         ),
         true
      );
      return removed;
   }

   /**
    * Audits (or removes) marker blocks that have no ledger owner and are not next to any
    * existing road. Default is a dry run so a large radius cannot surprise the player.
    */
   private static int orphanMarkers(CommandSourceStack source, int radius, boolean apply) {
      ServerLevel level = source.getLevel();
      java.util.HashMap<RoadMarker.Kind, Integer> histogram = new java.util.HashMap<>();
      int found;

      try {
         found = RoadMarkerBuilder.pruneUntracked(
            level, BlockPos.containing(source.getPosition()), radius, apply, histogram
         );
      } catch (Throwable failure) {
         org.apache.logging.log4j.LogManager.getLogger("SplineRoads").error("SR orphan marker scan failed", failure);
         String detail = failure.getClass().getSimpleName()
            + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
         source.sendFailure(Component.literal("SR 无主标记扫描失败：" + detail));
         return 0;
      }
      String kinds = histogram.entrySet()
         .stream()
         .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
         .limit(6)
         .map(e -> e.getKey().getSerializedName() + "=" + e.getValue())
         .reduce((a, b) -> a + ", " + b)
         .orElse("-");
      String message = (apply
            ? "SR 无主标记清理：半径 " + radius + " 内移除 " + found + " 个（仅已加载区块）"
            : "SR 无主标记检查：半径 " + radius + " 内发现 " + found
               + " 个（未修改；确认后执行 /sr markers orphans remove " + radius + "）")
         + "；种类：" + kinds;
      source.sendSuccess(() -> Component.literal(message), true);
      return found;
   }

   /** Recomputes and re-places the marker layout of every road (map colours). */
   private static int rebuildMarkers(CommandSourceStack source) {
      RoadData data = RoadData.get(source.getLevel());
      int roads = RoadMarkerBuilder.scheduleRebuild(data, "command");
      source.sendSuccess(
         () -> Component.literal("SR 标记重建：已排队 " + roads + " 条道路，后台分批计算并补放（不会删除现有标记）"),
         true
      );
      return roads;
   }

   /**
    * Reports (audit) or releases (prune) collider blocks that no road owns any more. These are
    * the invisible, walkable, shadow-casting leftovers of deleted roads.
    */
   private static int colliders(CommandSourceStack source, int radius, boolean apply) {
      ServerLevel level = source.getLevel();
      RoadData data = RoadData.get(level);
      BlockPos center = BlockPos.containing(source.getPosition());
      RoadData.ColliderAudit audit = data.auditOrphanColliders(level, center, radius, apply);
      String where = audit.min() == null
         ? "范围内没有残留碰撞方块"
         : "范围 " + audit.min().toShortString() + " ~ " + audit.max().toShortString();
      String samples = audit.samples().isEmpty()
         ? ""
         : "；示例 " + audit.samples().stream().map(BlockPos::toShortString).reduce((a, b) -> a + " " + b).orElse("");
      String message = (apply ? "SR 碰撞方块清理：" : "SR 碰撞方块检查：")
         + "扫描 " + audit.chunks() + " 个区块、"
         + audit.colliders() + " 个碰撞方块，无主 "
         + audit.orphans() + " 个（其中有原始记录 "
         + audit.withTerrain() + " 个）"
         + (apply ? "，已恢复 " + audit.restored() + " 个" : "")
         + "；" + where + samples;
      source.sendSuccess(() -> Component.literal(message), true);
      return audit.orphans();
   }
}
