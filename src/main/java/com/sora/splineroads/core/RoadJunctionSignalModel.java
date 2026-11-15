package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RoadJunctionSignalModel {
   public static boolean supports(RoadStructures.Part p) {
      return p.material() == RoadStructures.Material.SIGNAL_VEHICLE
         || p.material() == RoadStructures.Material.SIGNAL_PEDESTRIAN
         || p.material() == RoadStructures.Material.SIGNAL_LEFT;
   }

   public static List<RoadSurface.Face> faces(RoadStructures.Part p, boolean lamps, int state) {
      if (p.material() != RoadStructures.Material.SIGNAL_PEDESTRIAN) {
         return RoadSignalModel.head(p, lamps, state, true, p.material() == RoadStructures.Material.SIGNAL_LEFT);
      } else {
         ArrayList<RoadSurface.Face> local = new ArrayList<>();
         if (!lamps) {
            OriginalModels.bevel(local, -0.29, 0.0, -0.13, 0.29, 1.05, 0.23, 0.06, 4345173);

            for (double y : new double[]{0.29, 0.76}) {
               OriginalModels.box(local, -0.24, y - 0.2, 0.23, 0.24, y + 0.2, 0.27, 9082525);
               OriginalModels.box(local, -0.2, y - 0.17, 0.27, 0.2, y + 0.17, 0.28, 1582111);
               OriginalModels.box(local, -0.22, y + 0.17, 0.25, 0.22, y + 0.2, 0.46, 2239538);
            }

            OriginalModels.bevel(local, -0.12, 0.38, -0.51, 0.12, 0.66, -0.13, 0.035, 7503748);
         } else {
            for (boolean walking : new boolean[]{false, true}) {
               double cy = walking ? 0.29 : 0.76;
               boolean on = walking ? state == 1 : state != 1;
               int color = on ? RoadSignalModel.lampColor(walking ? 1 : 0) : 2766896;
               String[] glyph = walking
                  ? new String[]{"0011000", "0011000", "0001000", "0111100", "1001010", "0001001", "0010100", "0100010", "1000001"}
                  : new String[]{"0011100", "0011100", "0001000", "0111110", "0101010", "0101010", "0010100", "0010100", "0010100"};

               for (int y = 0; y < glyph.length; y++) {
                  for (int x = 0; x < 7; x++) {
                     if (glyph[y].charAt(x) == '1') {
                        double x0 = ((double)x - 3.5) * 0.036;
                        double y0 = cy + (3.5 - (double)y) * 0.034;
                        OriginalModels.face(
                           local,
                           List.of(
                              new RoadGeometry.V(x0, y0, 0.283),
                              new RoadGeometry.V(x0 + 0.036, y0, 0.283),
                              new RoadGeometry.V(x0 + 0.036, y0 + 0.034, 0.283),
                              new RoadGeometry.V(x0, y0 + 0.034, 0.283)
                           ),
                           new RoadGeometry.V(0.0, 0.0, 1.0),
                           color,
                           on,
                           RoadSurface.Texture.PLAIN
                        );
                     }
                  }
               }
            }
         }

         RoadGeometry.V front = p.b().sub(p.a()).horizontalUnit();
         return OriginalModels.transform(local, p.a().add(front.mul(0.247)), front.left().mul(-0.96), new RoadGeometry.V(0.0, 0.98, 0.0), front.mul(0.95));
      }
   }

   private RoadJunctionSignalModel() {
   }
}
