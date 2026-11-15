package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RoadNoiseModel {
   public static final double HEIGHT = 2.5043584;
   public static final double BASE_HEIGHT = 0.5;

   public static List<RoadStructures.Part> assembly(RoadStructures.Part p) {
      RoadGeometry.V up = new RoadGeometry.V(0.0, 0.5, 0.0);
      return List.of(
         new RoadStructures.Part(p.a(), p.b(), p.width(), 0.5, false, RoadStructures.Material.CONCRETE, p.frameA(), p.frameB()),
         new RoadStructures.Part(p.a().add(up), p.b().add(up), p.width(), p.height(), false, p.material(), p.frameA(), p.frameB(), p.model())
      );
   }

   public static List<RoadSurface.Face> faces(RoadStructures.Part p, boolean distant) {
      double length = p.a().sub(p.b()).horizontalLength();
      ArrayList<RoadSurface.Face> local = new ArrayList<>();
      OriginalModels.box(local, 0.0, 0.0, -0.075, length, 1.35, 0.075, 1870390);
      OriginalModels.box(local, 0.0, 1.34, -0.11, length, 1.43, 0.11, 11978185);
      OriginalModels.box(local, 0.0, 0.02, -0.11, length, 0.11, 0.11, 11978185);

      for (double x : new double[]{0.0, Math.max(0.0, length - 0.075)}) {
         OriginalModels.box(local, x, 0.0, -0.13, x + 0.075, 2.28, 0.13, 13358294);
      }

      for (int front : new int[]{-1, 1}) {
         OriginalModels.face(
            local,
            List.of(
               new RoadGeometry.V(0.075, 1.43, (double)front * 0.025),
               new RoadGeometry.V(length - 0.075, 1.43, (double)front * 0.025),
               new RoadGeometry.V(length - 0.075, 2.2, (double)front * 0.025),
               new RoadGeometry.V(0.075, 2.2, (double)front * 0.025)
            ),
            new RoadGeometry.V(0.0, 0.0, (double)front),
            12506047,
            false,
            RoadSurface.Texture.NOISE_BARRIER_GLASS
         );
         if (!distant) {
            for (double y = 0.23; y < 1.3; y += 0.16) {
               OriginalModels.box(local, 0.075, y, front > 0 ? 0.075 : -0.094, length - 0.075, y + 0.035, front > 0 ? 0.094 : -0.075, 4889946);
            }
         }
      }

      OriginalModels.box(local, 0.0, 2.2, -0.11, length, 2.28, 0.11, 11978185);
      OriginalModels.prism(
         local,
         List.of(
            new RoadGeometry.V(0.0, 0.0, -0.1),
            new RoadGeometry.V(length, 0.0, -0.1),
            new RoadGeometry.V(length, 0.0, -0.4),
            new RoadGeometry.V(0.0, 0.0, -0.4)
         ),
         2.28,
         2.36,
         2389560
      );
      OriginalModels.box(local, 0.0, 2.36, -0.4, length, 2.5043584, -0.33, 11978185);
      RoadGeometry.V along = p.b().sub(p.a());
      RoadGeometry.V normal = along.horizontalUnit().left();
      RoadGeometry.V na = p.frameA() == null ? normal : p.frameA().horizontalUnit();
      RoadGeometry.V nb = p.frameB() == null ? normal : p.frameB().horizontalUnit();
      boolean mirror = OriginalModels.cross(along, new RoadGeometry.V(0.0, 1.0, 0.0)).dot(na) < 0.0;
      ArrayList<RoadSurface.Face> out = new ArrayList<>();

      for (RoadSurface.Face f : local) {
         ArrayList<RoadGeometry.V> q = new ArrayList<>();

         for (RoadGeometry.V v : f.points()) {
            double t = v.x() / length;
            q.add(p.a().add(along.mul(t)).add(na.mul(1.0 - t).add(nb.mul(t)).mul(v.z())).add(new RoadGeometry.V(0.0, v.y(), 0.0)));
         }

         if (mirror) {
            Collections.reverse(q);
         }

         out.add(new RoadSurface.Face(List.copyOf(q), f.color(), f.emissive(), f.texture()));
      }

      return List.copyOf(out);
   }

   private RoadNoiseModel() {
   }
}
