package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RoadPoleModel {
   public static boolean support(RoadStructures.Part p) {
      return p.material() == RoadStructures.Material.CB_POST
         || p.material() == RoadStructures.Material.CB_ARM
         || p.material() == RoadStructures.Material.CB_BASE;
   }

   public static List<RoadSurface.Face> faces(RoadStructures.Part p) {
      ArrayList<RoadSurface.Face> out = new ArrayList<>();
      RoadGeometry.V center = p.a().add(p.b()).mul(0.5);
      int metal = 13292247;
      int dark = 5398892;
      if (p.material() == RoadStructures.Material.CB_ARM) {
         RoadGeometry.V delta = p.b().sub(p.a());
         double length = p.a().distance(p.b());
         OriginalModels.cylinder(out, p.a(), p.b(), 0.105, 0.07, 12, metal);

         for (double t : new double[]{0.04, 0.52, 0.94}) {
            RoadGeometry.V a = p.a().add(delta.mul(t));
            OriginalModels.cylinder(out, a, a.add(delta.mul(Math.min(0.12 / length, 0.03))), 0.11 - 0.03 * t, 0.11 - 0.03 * t, 12, dark);
         }
      } else if (p.material() == RoadStructures.Material.CB_POST) {
         OriginalModels.cylinder(out, center, center.add(new RoadGeometry.V(0.0, p.height(), 0.0)), 0.145, 0.085, 12, metal);
         OriginalModels.cylinder(out, center.add(new RoadGeometry.V(0.0, 0.2, 0.0)), center.add(new RoadGeometry.V(0.0, 0.27, 0.0)), 0.18, 0.18, 12, dark);
         OriginalModels.cylinder(
            out, center.add(new RoadGeometry.V(0.0, p.height() - 0.06, 0.0)), center.add(new RoadGeometry.V(0.0, p.height(), 0.0)), 0.095, 0.095, 12, dark
         );
      } else {
         boolean fitted = p.model().equals("sr:signal_base") || p.model().equals("sr:signal_bolt_plate");
         boolean plate = p.model().equals("sr:signal_bolt_plate");
         ArrayList<RoadSurface.Face> local = new ArrayList<>();
         OriginalModels.bevel(local, -0.23, 0.0, -0.23, 0.23, 0.14, 0.23, 0.035, dark);
         if (!plate) {
            OriginalModels.cylinder(local, new RoadGeometry.V(0.0, 0.14, 0.0), new RoadGeometry.V(0.0, 0.34, 0.0), 0.18, 0.15, 12, metal);
         }

         for (int x : new int[]{-1, 1}) {
            for (int z : new int[]{-1, 1}) {
               OriginalModels.cylinder(
                  local,
                  new RoadGeometry.V((double)x * 0.16, plate ? 0.09 : 0.14, (double)z * 0.16),
                  new RoadGeometry.V((double)x * 0.16, plate ? 0.14 : 0.19, (double)z * 0.16),
                  0.025,
                  0.025,
                  6,
                  15528178
               );
            }
         }

         RoadGeometry.V forward = fitted ? p.b().sub(p.a()).horizontalUnit() : new RoadGeometry.V(0.0, 0.0, 1.0);
         RoadGeometry.V side = fitted ? forward.left() : new RoadGeometry.V(1.0, 0.0, 0.0);
         double scale = fitted ? p.width() / 0.46 : 1.0;
         out.addAll(OriginalModels.transform(local, center, side.mul(scale), new RoadGeometry.V(0.0, 1.0, 0.0), forward.mul(scale)));
      }

      return List.copyOf(out);
   }

   private RoadPoleModel() {
   }
}
