package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RailbedFence {
   public static final String MODEL = "sr:railway_fence";

   public static List<RoadSurface.Face> faces(RoadStructures.Part p) {
      double length = p.a().sub(p.b()).horizontalLength();
      if (length < 0.001) {
         return List.of();
      } else {
         ArrayList<RoadSurface.Face> local = new ArrayList<>();
         int green = 2652504;

         for (double x : new double[]{0.0, Math.max(0.0, length - 0.06)}) {
            OriginalModels.box(local, x, 0.0, -0.05, Math.min(length, x + 0.06), 1.65, 0.05, green);
         }

         for (double y : new double[]{0.08, 1.55}) {
            OriginalModels.box(local, 0.0, y, -0.025, length, y + 0.045, 0.025, green);
         }

         for (double x = 0.2; x < length - 0.06; x += 0.2) {
            wire(local, x, 0.12, x + 0.016, 1.55, green);
         }

         for (double y = 0.3; y < 1.5; y += 0.2) {
            wire(local, 0.06, y, Math.max(0.06, length - 0.06), y + 0.016, green);
         }

         RoadGeometry.V along = p.b().sub(p.a());
         RoadGeometry.V normal = along.horizontalUnit().left();
         ArrayList<RoadSurface.Face> out = new ArrayList<>();

         for (RoadSurface.Face f : local) {
            List<RoadGeometry.V> q = f.points()
               .stream()
               .map(v -> p.a().add(along.mul(v.x() / length)).add(normal.mul(v.z())).add(new RoadGeometry.V(0.0, v.y(), 0.0)))
               .toList();
            out.add(new RoadSurface.Face(q, f.color(), false, f.texture()));
         }

         return List.copyOf(out);
      }
   }

   private static void wire(List<RoadSurface.Face> out, double x0, double y0, double x1, double y1, int color) {
      for (int side : new int[]{-1, 1}) {
         OriginalModels.face(
            out,
            List.of(new RoadGeometry.V(x0, y0, 0.0), new RoadGeometry.V(x1, y0, 0.0), new RoadGeometry.V(x1, y1, 0.0), new RoadGeometry.V(x0, y1, 0.0)),
            new RoadGeometry.V(0.0, 0.0, (double)side),
            color,
            false,
            RoadSurface.Texture.PLAIN
         );
      }
   }

   private RailbedFence() {
   }
}
