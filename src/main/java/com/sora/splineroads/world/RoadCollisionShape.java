package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadRaster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RoadCollisionShape {
   /**
    * The raster decomposes a sloped deck into one tiny AABB per 1/8 block. Vanilla
    * then has to step up at every one of those micro risers while sprinting, which
    * feels like catching on the road (the "卡脚" report). Snap the top surface to
    * horizontal treads relative to the lowest top in the cell: flat cells stay
    * untouched, while a slope collapses several raster tiles into one flat tread.
    *
    * <p>Steep slopes use a coarser quantum (1/4 or 1/2 block) so sprinting triggers
    * one step-up every one or two blocks instead of several per block. The surface is
    * centred around the real raster top (error at most half the quantum) and thin
    * raised boxes (paint, tabletops) are never collapsed into nothing.</p>
    */
   private static List<RoadRaster.Box> smoothTops(List<RoadRaster.Box> boxes) {
      if (boxes.size() < 2) {
         return boxes;
      } else {
         double base = Double.POSITIVE_INFINITY;
         double top = Double.NEGATIVE_INFINITY;

         for (RoadRaster.Box b : boxes) {
            base = Math.min(base, b.y1());
            top = Math.max(top, b.y1());
         }

         if (!(Double.isFinite(base)) || !(Double.isFinite(top))) {
            return boxes;
         } else {
            double span = top - base;
            double quantum = span >= 0.55 ? 0.5 : (span >= 0.2 ? 0.25 : 0.125);
            ArrayList<RoadRaster.Box> snapped = new ArrayList<>(boxes.size());

            for (RoadRaster.Box b : boxes) {
               double boxTop = b.y1();
               double bottom = b.y0();

               if (boxTop > base + 1.0E-9) {
                  double snappedTop = Math.min(1.0, base + Math.round((boxTop - base) / quantum) * quantum);
                  // Never collapse a thin raised box (markings, tabletops) into nothing.
                  if (snappedTop > bottom + 1.0E-8) {
                     boxTop = snappedTop;
                  }
               }

               if (boxTop - bottom > 1.0E-8) {
                  snapped.add(new RoadRaster.Box(b.x0(), bottom, b.z0(), b.x1(), boxTop, b.z1()));
               }
            }

            return snapped.isEmpty() ? boxes : snapped;
         }
      }
   }

   public static VoxelShape build(List<RoadRaster.Box> boxes) {
      boxes = smoothTops(boxes);
      if (boxes.isEmpty()) {
         return Shapes.empty();
      } else if (boxes.size() == 1) {
         RoadRaster.Box b = boxes.get(0);
         return Shapes.box(b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1());
      } else {
         double[] x = axis(boxes, 0);
         double[] y = axis(boxes, 1);
         double[] z = axis(boxes, 2);
         long size = (long)(x.length - 1) * (long)(y.length - 1) * (long)(z.length - 1);
         if (size > 1000000L) {
            return legacy(boxes, 0, boxes.size());
         } else {
            BitSetDiscreteVoxelShape grid = new BitSetDiscreteVoxelShape(x.length - 1, y.length - 1, z.length - 1);

            for (RoadRaster.Box b : boxes) {
               int x0 = Arrays.binarySearch(x, b.x0());
               int x1 = Arrays.binarySearch(x, b.x1());
               int y0 = Arrays.binarySearch(y, b.y0());
               int y1 = Arrays.binarySearch(y, b.y1());
               int z0 = Arrays.binarySearch(z, b.z0());
               int z1 = Arrays.binarySearch(z, b.z1());

               for (int i = x0; i < x1; i++) {
                  for (int j = y0; j < y1; j++) {
                     for (int k = z0; k < z1; k++) {
                        grid.fill(i, j, k);
                     }
                  }
               }
            }

            return new RoadCollisionShape.GridShape(grid, x, y, z);
         }
      }
   }

   private static double[] axis(List<RoadRaster.Box> boxes, int axis) {
      double[] values = new double[boxes.size() * 2];
      int i = 0;

      for (RoadRaster.Box b : boxes) {
         values[i++] = axis == 0 ? b.x0() : (axis == 1 ? b.y0() : b.z0());
         values[i++] = axis == 0 ? b.x1() : (axis == 1 ? b.y1() : b.z1());
      }

      Arrays.sort(values);
      int n = 0;

      for (double v : values) {
         if (n == 0 || v != values[n - 1]) {
            values[n++] = v;
         }
      }

      return Arrays.copyOf(values, n);
   }

   private static VoxelShape legacy(List<RoadRaster.Box> boxes, int from, int to) {
      if (to - from == 1) {
         RoadRaster.Box b = boxes.get(from);
         return Shapes.box(b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1());
      } else {
         int mid = (from + to) / 2;
         return Shapes.joinUnoptimized(legacy(boxes, from, mid), legacy(boxes, mid, to), BooleanOp.OR);
      }
   }

   private RoadCollisionShape() {
   }

   private static final class GridShape extends ArrayVoxelShape {
      GridShape(DiscreteVoxelShape shape, double[] x, double[] y, double[] z) {
         super(shape, x, y, z);
      }
   }
}
