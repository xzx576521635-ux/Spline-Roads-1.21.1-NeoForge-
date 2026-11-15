package com.sora.splineroads.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

final class RoadAsphaltModel extends BakedModelWrapper<BakedModel> {
   static final ModelProperty<BlockPos> POSITION = new ModelProperty();

   RoadAsphaltModel(BakedModel original) {
      super(original);
   }

   public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData input) {
      return super.getModelData(level, pos, state, input).derive().with(POSITION, pos.immutable()).build();
   }

   public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType layer) {
      List<BakedQuad> quads = super.getQuads(state, side, random, data, layer);
      BlockPos pos = (BlockPos)data.get(POSITION);
      return pos == null ? quads : align(quads, pos);
   }

   static List<BakedQuad> align(List<BakedQuad> quads, BlockPos pos) {
      if (quads.isEmpty()) {
         return quads;
      } else {
         ArrayList<BakedQuad> result = new ArrayList<>(quads.size());

         for (BakedQuad q : quads) {
            int[] vertices = (int[])q.getVertices().clone();
            TextureAtlasSprite sprite = q.getSprite();

            for (int i = 0; i < 4; i++) {
               int at = i * 8;
               double x = (double)Float.intBitsToFloat(vertices[at]);
               double y = (double)Float.intBitsToFloat(vertices[at + 1]);
               double z = (double)Float.intBitsToFloat(vertices[at + 2]);
               double u;
               double v;
               switch (q.getDirection().getAxis()) {
                  case Y:
                     u = ((double)Math.floorMod(pos.getX(), 4) + x) / 4.0;
                     v = ((double)Math.floorMod(pos.getZ(), 4) + z) / 4.0;
                     break;
                  case X:
                     u = ((double)Math.floorMod(pos.getZ(), 4) + z) / 4.0;
                     v = ((double)Math.floorMod(pos.getY(), 4) + y) / 4.0;
                     break;
                  default:
                     u = ((double)Math.floorMod(pos.getX(), 4) + x) / 4.0;
                     v = ((double)Math.floorMod(pos.getY(), 4) + y) / 4.0;
               }

               vertices[at + 3] = -2302756;
               vertices[at + 4] = Float.floatToRawIntBits(sprite.getU((float)u));
               vertices[at + 5] = Float.floatToRawIntBits(sprite.getV((float)v));
            }

            result.add(new BakedQuad(vertices, q.getTintIndex(), q.getDirection(), sprite, q.isShade()));
         }

         return List.copyOf(result);
      }
   }
}
