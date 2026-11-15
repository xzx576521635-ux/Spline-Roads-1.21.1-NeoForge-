package com.sora.splineroads.world;

import com.mojang.serialization.MapCodec;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible marker block used to give Xaero's map and Distant Horizons a block-based
 * representation of Spline Roads' geometry (trees, lamps, rails, medians, paint, ...).
 *
 * <p>The block has no collision, no outline, no light blocking and an empty model. Map/LOD
 * color readers use its MapColor or the per-kind particle texture. The kind is used by the
 * DH color hook as well.</p>
 */
public final class RoadMarker extends Block {
   public static final EnumProperty<RoadMarker.Kind> KIND = EnumProperty.create("kind", RoadMarker.Kind.class);

   public RoadMarker() {
      this(
         Properties.of()
            .mapColor(MapColor.COLOR_GRAY)
            .strength(-1.0F, 3600000.0F)
            .noCollission()
            .noOcclusion()
            .dynamicShape()
            .isViewBlocking((state, level, pos) -> false)
            .isSuffocating((state, level, pos) -> false)
            .pushReaction(PushReaction.BLOCK)
      );
   }

   public RoadMarker(Properties properties) {
      super(properties);
      this.registerDefaultState(this.stateDefinition.any().setValue(KIND, RoadMarker.Kind.OAK_LEAVES));
   }

   protected MapCodec<? extends RoadMarker> codec() {
      return simpleCodec(RoadMarker::new);
   }

   protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
      builder.add(KIND);
   }

   @Override
   public MapColor getMapColor(BlockState state, BlockGetter level, BlockPos pos, MapColor defaultColor) {
      return state.getValue(KIND).mapColor();
   }

   public RenderShape getRenderShape(BlockState state) {
      return RenderShape.MODEL;
   }

   public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      return Shapes.empty();
   }

   public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      // DH computes "isSolid" from the collision shape with EmptyBlockGetter and no entity
      // context. Return a full shape only for that probe; real entities and block placement
      // see an empty shape so markers are walk-through and do not block building.
      if (context instanceof net.minecraft.world.phys.shapes.EntityCollisionContext) {
         return Shapes.empty();
      }

      return level instanceof net.minecraft.world.level.EmptyBlockGetter ? Shapes.block() : Shapes.empty();
   }

   public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
      return Shapes.empty();
   }

   public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
      return true;
   }

   public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
      return 0;
   }

   public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
      return 0.0F;
   }

   public static enum Kind implements StringRepresentable {
      OAK_LEAVES("minecraft:block/lime_concrete", MapColor.PLANT, 0x4C7A34),
      BIRCH_LEAVES("minecraft:block/light_gray_concrete", MapColor.COLOR_LIGHT_GREEN, 0x6A8A4A),
      SPRUCE_LEAVES("minecraft:block/green_concrete", MapColor.COLOR_GREEN, 0x3A5A3A),
      CHERRY_LEAVES("minecraft:block/pink_concrete", MapColor.COLOR_PINK, 0xD98BA4),
      OAK_LOG("minecraft:block/brown_concrete", MapColor.WOOD, 0x6B5232),
      BIRCH_LOG("minecraft:block/light_gray_concrete", MapColor.SAND, 0xC8C09A),
      SPRUCE_LOG("minecraft:block/brown_concrete", MapColor.COLOR_BROWN, 0x4A3826),
      CHERRY_LOG("minecraft:block/brown_concrete", MapColor.COLOR_PINK, 0xA86B6B),
      LAMP("minecraft:block/white_concrete", MapColor.QUARTZ, 0xE8E4D0),
      LAMP_BLUE("minecraft:block/light_blue_concrete", MapColor.COLOR_LIGHT_BLUE, 0x8FC0E0),
      STEEL("minecraft:block/iron_block", MapColor.METAL, 0x6E7278),
      DARK_STEEL("minecraft:block/gray_concrete", MapColor.COLOR_GRAY, 0x4A4E54),
      CONCRETE("minecraft:block/smooth_stone", MapColor.STONE, 0x9E9E9A),
      SOIL("minecraft:block/brown_concrete", MapColor.DIRT, 0x7A5A3A),
      GREEN("minecraft:block/lime_concrete", MapColor.PLANT, 0x5A8A3A),
      FLOWERS("minecraft:block/pink_concrete", MapColor.COLOR_PINK, 0xC87A9A),
      PAINT_WHITE("minecraft:block/white_concrete", MapColor.SNOW, 0xE8E8E4),
      PAINT_YELLOW("minecraft:block/yellow_concrete", MapColor.COLOR_YELLOW, 0xE0C040),
      SIGN_GREEN("minecraft:block/green_concrete", MapColor.COLOR_GREEN, 0x4A8A52),
      SIGN_BLUE("minecraft:block/blue_concrete", MapColor.COLOR_BLUE, 0x4A6AA8),
      SIGN_WHITE("minecraft:block/white_concrete", MapColor.SNOW, 0xE8E8E4),
      PIER("minecraft:block/smooth_stone", MapColor.STONE, 0x8A8A86);

      private final String texture;
      private final MapColor mapColor;
      private final int rgb;

      Kind(String texture, MapColor mapColor, int rgb) {
         this.texture = texture;
         this.mapColor = mapColor;
         this.rgb = rgb;
      }

      public String texture() {
         return this.texture;
      }

      public MapColor mapColor() {
         return this.mapColor;
      }

      public int rgb() {
         return this.rgb;
      }

      public String getSerializedName() {
         return this.name().toLowerCase(Locale.ROOT);
      }
   }
}
