package com.sora.splineroads.world;

import com.mojang.serialization.MapCodec;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadRaster;
import com.sora.splineroads.core.RoadStructures;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RoadBlocks {
   public static Function<Level, RoadIndex> clientIndex = l -> null;

   public static boolean isCollider(BlockState state) {
      return state.getBlock() instanceof RoadBlocks.Road;
   }

   public static boolean canInfill(Level level, BlockPos p, BlockState fill) {
      BlockState existing = level.getBlockState(p);
      RoadIndex i = index(level);
      if (!isCollider(existing)
         || existing.is((Block)SplineRoads.FILLED_COLLIDER.get())
         || existing.getValue(RoadBlocks.Road.FILL) != RoadBlocks.Fill.NONE
         || i == null
         || fill.hasBlockEntity()
         || TunnelAir.isAirSpace(fill)
         || !Block.isShapeFullBlock(fill.getCollisionShape(level, p))) {
         return false;
      } else if (gantryCell(i, p)) {
         return Shapes.joinIsNotEmpty(Shapes.block(), i.shape(p), BooleanOp.ONLY_FIRST);
      } else {
         List<RoadIndex.Built> nearby = i.inChunk(new ChunkPos(p).toLong()).stream().map(i.roads::get).filter(Objects::nonNull).toList();
         if (GroundTerrainJoin.usable(GroundTerrainJoin.cap(p, fill, nearby))) {
            return true;
         } else if (!TunnelTerrainSpace.safe(p, nearby) && !TunnelTerrainSpace.exteriorInfill(p, nearby)) {
            boolean deck = false;

            for (UUID id : i.at(p.asLong())) {
               RoadIndex.Built road = i.roads.get(id);
               RoadIndex.Built.Column c = road.column(p);
               if (c != null) {
                  deck = true;
                  if ((double)(p.getY() + 1) > c.minTop() + 1.0E-7) {
                     return false;
                  }
               } else if (level.isClientSide) {
                  for (RoadRaster.Box box : new RoadRaster.Local(road.mesh, List.of()).boxes(new RoadRaster.Cell(p.getX(), p.getY(), p.getZ()))) {
                     deck = true;
                     if (1.0 > box.y0() + road.record.settings().thickness() + 1.0E-7) {
                        return false;
                     }
                  }
               }
            }

            boolean walkway = false;
            if (!deck) {
               for (UUID idx : i.at(p.asLong())) {
                  RoadIndex.Built road = i.roads.get(idx);

                  for (RoadStructures.Part part : road.record.structures()) {
                     if (part.material().name().startsWith("WALK_")) {
                        Map<RoadRaster.Cell, List<RoadRaster.Box>> boxes = RoadRaster.structures(
                           List.of(part), new RoadRaster.Cell(p.getX(), p.getY(), p.getZ())
                        );
                        if (!boxes.isEmpty() && boxes.values().stream().flatMap(Collection::stream).anyMatch(b -> b.y1() >= 0.9999999)) {
                           walkway = true;
                        }
                     }
                  }
               }
            }

            return (deck || walkway) && Shapes.joinIsNotEmpty(Shapes.block(), i.shape(p), BooleanOp.ONLY_FIRST);
         } else {
            return Shapes.joinIsNotEmpty(Shapes.block(), i.shape(p), BooleanOp.ONLY_FIRST);
         }
      }
   }

   static boolean gantryCell(RoadIndex index, BlockPos p) {
      RoadRaster.Cell cell = new RoadRaster.Cell(p.getX(), p.getY(), p.getZ());

      for (UUID id : index.at(p.asLong())) {
         if (gantryCell(index.roads.get(id), cell)) {
            return true;
         }
      }

      return false;
   }

   static boolean gantryCell(RoadIndex.Built road, RoadRaster.Cell cell) {
      List<RoadStructures.Part> parts = road.record.structures().stream().filter(part -> part.material() == RoadStructures.Material.GANTRY_FRAME).toList();
      return !parts.isEmpty() && !RoadRaster.structures(parts, cell).isEmpty();
   }

   public static RoadIndex index(BlockGetter world) {
      if (world instanceof ServerLevel server) {
         return RoadData.get(server).index;
      } else {
         return world instanceof Level level ? clientIndex.apply(level) : null;
      }
   }

   private RoadBlocks() {
   }

   public static enum Fill implements StringRepresentable {
      NONE,
      GRASS_BLOCK,
      DIRT,
      STONE,
      SAND,
      GRAVEL,
      DEEPSLATE,
      COARSE_DIRT,
      PODZOL,
      ANDESITE,
      DIORITE,
      GRANITE,
      STONE_SLAB,
      SMOOTH_STONE_SLAB,
      STONE_BRICK_SLAB,
      COBBLESTONE_SLAB,
      BRICK_SLAB,
      QUARTZ_SLAB,
      OAK_SLAB,
      SPRUCE_SLAB,
      STONE_BRICKS,
      BRICKS;

      public double top() {
         return this.name().endsWith("_SLAB") ? 0.5 : 1.0;
      }

      public String getSerializedName() {
         return this.name().toLowerCase(Locale.ROOT);
      }

      public BlockState state() {
         return this == NONE
            ? Blocks.AIR.defaultBlockState()
            : ((Block)BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("minecraft", this.getSerializedName()))).defaultBlockState();
      }

      public static RoadBlocks.Fill of(BlockState state) {
         for (RoadBlocks.Fill f : values()) {
            if (f != NONE && state.equals(f.state())) {
               return f;
            }
         }

         return NONE;
      }
   }

   /**
    * Lane paint carried by the top road collider cell. Xaero's map samples the block state's
    * model instead of separate marker blocks, so paint no longer needs invisible blocks.
    */
   public static enum Paint implements StringRepresentable {
      NONE,
      WHITE,
      YELLOW;

      public String getSerializedName() {
         return this.name().toLowerCase(Locale.ROOT);
      }
   }

   public static class FilledRoad extends RoadBlocks.Road implements EntityBlock {
      public BlockEntity newBlockEntity(BlockPos p, BlockState s) {
         return new RoadFillEntity(p, s);
      }

      @Override
      public RenderShape getRenderShape(BlockState s) {
         return RenderShape.MODEL;
      }

      @Override
      public VoxelShape getCollisionShape(BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
         VoxelShape road = super.getCollisionShape(s, w, p, c);
         return w.getBlockEntity(p) instanceof RoadFillEntity entity ? entity.collision(w, p, c, road) : road;
      }

      @Override
      public float getDestroyProgress(BlockState s, Player player, BlockGetter w, BlockPos p) {
         return w.getBlockEntity(p) instanceof RoadFillEntity fill ? fill.fill().getDestroyProgress(player, w, p) : 0.0F;
      }
   }

   public static class Node extends BaseEntityBlock {
      private static final VoxelShape MARKER = Block.box(3.0, 0.0, 3.0, 13.0, 2.0, 13.0);

      protected MapCodec<? extends RoadBlocks.Node> codec() {
         return simpleCodec(RoadBlocks.Node::new);
      }

      public Node() {
         this(Properties.of().strength(1.5F).dynamicShape().noOcclusion().isSuffocating((s, w, p) -> false).pushReaction(PushReaction.BLOCK));
      }

      public Node(Properties props) {
         super(props);
      }

      public BlockEntity newBlockEntity(BlockPos p, BlockState s) {
         return new NodeEntity(p, s);
      }

      public RenderShape getRenderShape(BlockState s) {
         return RenderShape.MODEL;
      }

      public VoxelShape getShape(BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
         boolean var10000;
         label28: {
            if (c instanceof EntityCollisionContext entity
               && entity.getEntity() instanceof LivingEntity who
               && (RoadTool.showsNodes(who.getMainHandItem()) || RoadTool.showsNodes(who.getOffhandItem()))) {
               var10000 = true;
               break label28;
            }

            var10000 = false;
         }

         boolean holding = var10000;
         VoxelShape shape = holding ? Shapes.or(MARKER, this.getCollisionShape(s, w, p, c)) : this.getCollisionShape(s, w, p, c);
         return shape.isEmpty() ? MARKER : shape;
      }

      public VoxelShape getCollisionShape(BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
         RoadIndex i = RoadBlocks.index(w);
         return i == null ? Shapes.empty() : i.shape(p);
      }

      public void setPlacedBy(Level w, BlockPos p, BlockState s, LivingEntity who, ItemStack stack) {
         if (w.getBlockEntity(p) instanceof NodeEntity n) {
            n.owner = who == null ? null : who.getUUID();
            n.yaw = who == null ? 0.0 : (double)who.getYRot();
            n.setChanged();
            w.sendBlockUpdated(p, s, s, 3);
         }
      }

      public float getDestroyProgress(BlockState s, Player player, BlockGetter w, BlockPos p) {
         RoadIndex i = RoadBlocks.index(w);
         return i != null && i.roads.values().stream().anyMatch(r -> r.record.a().equals(p) || r.record.b().equals(p))
            ? 0.0F
            : super.getDestroyProgress(s, player, w, p);
      }
   }

   public static class Road extends Block implements LiquidBlockContainer {
      public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
      public static final BooleanProperty PERMEABLE = BooleanProperty.create("water_permeable");
      public static final BooleanProperty SEALED = BooleanProperty.create("sealed");
      public static final BooleanProperty LIT = BooleanProperty.create("lit");
      public static final EnumProperty<RoadBlocks.Fill> FILL = EnumProperty.create("fill", RoadBlocks.Fill.class);
      public static final EnumProperty<RoadBlocks.Paint> PAINT = EnumProperty.create("paint", RoadBlocks.Paint.class);

      public boolean canPlaceLiquid(Player pl, BlockGetter w, BlockPos p, BlockState s, Fluid f) {
         return (Boolean)s.getValue(PERMEABLE) && !(Boolean)s.getValue(WATERLOGGED) && f == Fluids.WATER;
      }

      public boolean placeLiquid(LevelAccessor w, BlockPos p, BlockState s, FluidState f) {
         if (!this.canPlaceLiquid(null, w, p, s, f.getType())) {
            return false;
         } else {
            if (!w.isClientSide()) {
               w.setBlock(p, (BlockState)s.setValue(WATERLOGGED, true), 3);
               w.scheduleTick(p, Fluids.WATER, Fluids.WATER.getTickDelay(w));
            }

            return true;
         }
      }

      public FluidState getFluidState(BlockState s) {
         return s.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(s);
      }

      public BlockState updateShape(BlockState s, Direction direction, BlockState neighbour, LevelAccessor w, BlockPos p, BlockPos other) {
         if ((Boolean)s.getValue(WATERLOGGED)) {
            w.scheduleTick(p, Fluids.WATER, Fluids.WATER.getTickDelay(w));
         }

         return super.updateShape(s, direction, neighbour, w, p, other);
      }

      public Road() {
         super(
            Properties.of()
               .mapColor(MapColor.COLOR_GRAY)
               .strength(-1.0F, 3600000.0F)
               .dynamicShape()
               .forceSolidOn()
               .noOcclusion()
               .lightLevel(state -> state.getValue(LIT) ? 15 : 0)
               .isViewBlocking((s, w, p) -> false)
               .isSuffocating((s, w, p) -> false)
               .pushReaction(PushReaction.BLOCK)
         );
         this.registerDefaultState(
            (BlockState)((BlockState)((BlockState)((BlockState)((BlockState)((BlockState)((BlockState)this.stateDefinition.any()).setValue(FILL, RoadBlocks.Fill.NONE))
                        .setValue(PAINT, RoadBlocks.Paint.NONE))
                        .setValue(LIT, false))
                     .setValue(SEALED, false))
                  .setValue(WATERLOGGED, false))
               .setValue(PERMEABLE, false)
         );
      }

      protected void createBlockStateDefinition(Builder<Block, BlockState> b) {
         b.add(new Property[]{FILL, PAINT, LIT, SEALED, WATERLOGGED, PERMEABLE});
      }

      public float getDestroyProgress(BlockState s, Player player, BlockGetter w, BlockPos p) {
         return s.getValue(FILL) == RoadBlocks.Fill.NONE ? 0.0F : ((RoadBlocks.Fill)s.getValue(FILL)).state().getDestroyProgress(player, w, p);
      }

      public RenderShape getRenderShape(BlockState s) {
         return RenderShape.MODEL;
      }

      public VoxelShape getCollisionShape(BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
         // The road deck shape must take precedence over the fill block. A full fill cube
         // would turn every slope cell into a 1-block vertical step, which blocks sprinting
         // uphill (downhill still works because the player simply drops).
         RoadIndex index = RoadBlocks.index(w);
         if (index != null) {
            VoxelShape road = index.shape(p);
            if (!road.isEmpty()) {
               return road;
            }

            if (s.getValue(FILL) == RoadBlocks.Fill.NONE) {
               // Safety fallback: a road collider must never become walk-through
               // just because its index/raster has no boxes for this cell. Without
               // this, a missing deck raster leaves the visible VBO road floating
               // while only vertical structures keep collision.
               return Shapes.block();
            }
         }

         if (s.getValue(FILL) != RoadBlocks.Fill.NONE) {
            VoxelShape fill = ((RoadBlocks.Fill)s.getValue(FILL)).state().getCollisionShape(w, p, c);
            return Block.isShapeFullBlock(fill) ? Shapes.block() : fill;
         }

         return index == null ? Shapes.block() : Shapes.empty();
      }

      public VoxelShape getShape(BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
         return this.getCollisionShape(s, w, p, c);
      }

      public VoxelShape getOcclusionShape(BlockState s, BlockGetter w, BlockPos p) {
         return Shapes.empty();
      }

      public VoxelShape getVisualShape(BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
         return Shapes.empty();
      }

      public boolean propagatesSkylightDown(BlockState s, BlockGetter w, BlockPos p) {
         return !(Boolean)s.getValue(SEALED);
      }

      public int getLightBlock(BlockState s, BlockGetter w, BlockPos p) {
         return s.getValue(SEALED) ? 15 : 0;
      }
   }
}
