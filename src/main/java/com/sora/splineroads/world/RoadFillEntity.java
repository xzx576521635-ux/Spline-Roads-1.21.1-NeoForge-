package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadRaster;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

public final class RoadFillEntity extends BlockEntity {
   public static final ModelProperty<BlockState> MATERIAL = new ModelProperty();
   public static final ModelProperty<Double> CAP = new ModelProperty();
   public static final ModelProperty<List<RoadRaster.Box>> EDGE = new ModelProperty();
   public static final ModelProperty<List<List<RoadGeometry.V>>> OUTLINE = new ModelProperty();
   private volatile RoadFillEntity.Fill contents = new RoadFillEntity.Fill(Blocks.AIR.defaultBlockState(), 1.0, List.of(), List.of());
   private volatile RoadFillEntity.Collision cached;

   public List<List<RoadGeometry.V>> outline() {
      return this.contents.outline();
   }

   public double cap() {
      return this.contents.cap();
   }

   public List<RoadRaster.Box> edge() {
      return this.contents.edge();
   }

   public ModelData getModelData() {
      RoadFillEntity.Fill fill = this.contents;
      return ModelData.builder().with(MATERIAL, fill.material()).with(CAP, fill.cap()).with(EDGE, fill.edge()).with(OUTLINE, fill.outline()).build();
   }

   public RoadFillEntity(BlockPos pos, BlockState state) {
      super((BlockEntityType)SplineRoads.FILL_ENTITY.get(), pos, state);
   }

   public BlockState fill() {
      return this.contents.material();
   }

   public VoxelShape collision(BlockGetter world, BlockPos pos, CollisionContext context, VoxelShape road) {
      RoadFillEntity.Fill fill = this.contents;
      RoadFillEntity.Collision previous = this.cached;
      VoxelShape raw = fill.material().getCollisionShape(world, pos, context);
      if (previous != null && previous.fill() == fill && previous.raw() == raw && previous.road() == road) {
         return previous.shape();
      } else {
         VoxelShape clipped = !(fill.cap() >= 1.0) && !raw.isEmpty()
            ? Shapes.joinUnoptimized(raw, Shapes.box(0.0, 0.0, 0.0, 1.0, fill.cap(), 1.0), BooleanOp.AND)
            : raw;
         if (!fill.edge().isEmpty() || !fill.outline().isEmpty()) {
            VoxelShape mask = Shapes.empty();

            for (RoadRaster.Box box : fill.edge()) {
               mask = Shapes.or(mask, Shapes.box(box.x0(), box.y0(), box.z0(), box.x1(), box.y1(), box.z1()));
            }

            clipped = Shapes.joinUnoptimized(clipped, mask, BooleanOp.AND);
         }

         VoxelShape shape = Block.isShapeFullBlock(clipped)
            ? Shapes.block()
            : (clipped.isEmpty() ? road : (road.isEmpty() ? clipped : Shapes.joinUnoptimized(road, clipped, BooleanOp.OR)));
         if (this.contents == fill) {
            this.cached = new RoadFillEntity.Collision(fill, raw, road, shape);
         }

         return shape;
      }
   }

   private void refresh() {
      this.cached = null;
      this.requestModelDataUpdate();
      if (this.level != null) {
         this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
      }
   }

   public void fill(BlockState state) {
      this.fill(state, 1.0);
   }

   public void fill(BlockState state, double height) {
      RoadIndex index = this.level == null ? null : RoadBlocks.index(this.level);
      List<RoadIndex.Built> nearby = index == null
         ? List.of()
         : index.inChunk(new ChunkPos(this.worldPosition).toLong()).stream().map(index.roads::get).filter(Objects::nonNull).toList();
      RailbedTerrainEdge.Data edge = RailbedTerrainEdge.data(this.worldPosition, state, nearby);
      this.contents = new RoadFillEntity.Fill(state, Math.max(0.01, Math.min(1.0, height)), edge.boxes(), edge.polygons());
      this.refresh();
      this.setChanged();
   }

   protected void saveAdditional(CompoundTag t, Provider prov) {
      super.saveAdditional(t, prov);
      RoadFillEntity.Fill fill = this.contents;
      t.put("Fill", NbtUtils.writeBlockState(fill.material()));
      t.putDouble("TerrainCap", fill.cap());
      ListTag boxes = new ListTag();

      for (RoadRaster.Box box : fill.edge()) {
         ListTag values = new ListTag();

         for (double v : new double[]{box.x0(), box.y0(), box.z0(), box.x1(), box.y1(), box.z1()}) {
            values.add(DoubleTag.valueOf(v));
         }

         boxes.add(values);
      }

      if (!boxes.isEmpty()) {
         t.put("TerrainEdge", boxes);
      }

      ListTag outline = new ListTag();

      for (List<RoadGeometry.V> poly : fill.outline()) {
         ListTag points = new ListTag();

         for (RoadGeometry.V point : poly) {
            points.add(DoubleTag.valueOf(point.x()));
            points.add(DoubleTag.valueOf(point.z()));
         }

         outline.add(points);
      }

      if (!outline.isEmpty()) {
         t.put("TerrainOutline", outline);
      }
   }

   protected void loadAdditional(CompoundTag t, Provider prov) {
      super.loadAdditional(t, prov);
      ArrayList<RoadRaster.Box> boxes = new ArrayList<>();
      ListTag list = t.getList("TerrainEdge", 9);
      if (list.size() <= 256) {
         for (Tag item : list) {
            ListTag v = (ListTag)item;
            if (v.size() == 6) {
               double a = v.getDouble(0);
               double b = v.getDouble(1);
               double c = v.getDouble(2);
               double d = v.getDouble(3);
               double e = v.getDouble(4);
               double f = v.getDouble(5);
               if (a >= 0.0 && b >= 0.0 && c >= 0.0 && d <= 1.0 && e <= 1.0 && f <= 1.0 && a < d && b < e && c < f) {
                  boxes.add(new RoadRaster.Box(a, b, c, d, e, f));
               }
            }
         }
      }

      ArrayList<List<RoadGeometry.V>> outlines = new ArrayList<>();
      ListTag polygons = t.getList("TerrainOutline", 9);
      if (polygons.size() <= 128) {
         for (Tag itemx : polygons) {
            ListTag v = (ListTag)itemx;
            if (v.size() >= 6 && v.size() <= 64 && v.size() % 2 == 0) {
               ArrayList<RoadGeometry.V> points = new ArrayList<>();

               for (int i = 0; i < v.size(); i += 2) {
                  double x = v.getDouble(i);
                  double z = v.getDouble(i + 1);
                  if (!Double.isFinite(x) || !Double.isFinite(z) || x < -1.0E-7 || x > 1.0000001 || z < -1.0E-7 || z > 1.0000001) {
                     points.clear();
                     break;
                  }

                  points.add(new RoadGeometry.V(x, 0.0, z));
               }

               if (points.size() > 2) {
                  outlines.add(List.copyOf(points));
               }
            }
         }
      }

      this.contents = new RoadFillEntity.Fill(
         NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), t.getCompound("Fill")),
         t.contains("TerrainCap") ? Math.max(0.01, Math.min(1.0, t.getDouble("TerrainCap"))) : 1.0,
         List.copyOf(boxes),
         List.copyOf(outlines)
      );
      this.refresh();
   }

   public CompoundTag getUpdateTag(Provider prov) {
      return this.saveWithoutMetadata(prov);
   }

   public ClientboundBlockEntityDataPacket getUpdatePacket() {
      return ClientboundBlockEntityDataPacket.create(this);
   }

   private static record Collision(RoadFillEntity.Fill fill, VoxelShape raw, VoxelShape road, VoxelShape shape) {
   }

   private static record Fill(BlockState material, double cap, List<RoadRaster.Box> edge, List<List<RoadGeometry.V>> outline) {
   }
}
