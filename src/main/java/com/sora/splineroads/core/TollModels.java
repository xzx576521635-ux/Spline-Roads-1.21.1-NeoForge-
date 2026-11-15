package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TollModels {
   public static boolean supports(RoadStructures.Part p) {
      return p.model() != null && p.model().startsWith("sr:toll_model_");
   }

   public static List<RoadSurface.Face> faces(RoadStructures.Part p) {
      ArrayList<RoadSurface.Face> out = new ArrayList<>();
      if (p.model().startsWith("sr:toll_model_label_")) {
         String text = p.model().substring("sr:toll_model_label_".length());
         double total = (double)text.length() * 0.4 - 0.12;

         for (int i = 0; i < text.length(); i++) {
            letter(out, text.charAt(i), ((double)i * 0.4 - total / 2.0) / p.width(), 0.28 / p.width());
         }
      } else if (p.model().startsWith("sr:toll_model_name_")) {
         int color = p.model().endsWith("panel") ? 14936294 : (p.model().endsWith("sill") ? 3753804 : 10201259);
         double width = p.frameA() == null ? p.width() : p.frameA().horizontalLength() * 2.0;
         OriginalModels.box(out, -width / (2.0 * p.width()), 0.0, 0.0, width / (2.0 * p.width()), p.height(), 1.0, color);
      } else if (p.model().equals("sr:toll_model_gate")) {
         OriginalModels.bevel(out, -0.34, 0.0, 0.12, 0.34, 0.1, 0.88, 0.07, 3622221);
         OriginalModels.bevel(out, -0.29, 0.1, 0.18, 0.29, 1.15, 0.82, 0.055, 15002087);
         OriginalModels.bevel(out, -0.3, 1.15, 0.17, 0.3, 1.24, 0.83, 0.06, 2707297);
         OriginalModels.box(out, -0.23, 0.24, 0.165, 0.23, 0.98, 0.18, 12109772);
         OriginalModels.box(out, -0.17, 0.83, 0.15, 0.12, 0.9, 0.165, 3361879);

         for (double y = 0.35; y < 0.69; y += 0.08) {
            OriginalModels.box(out, -0.16, y, 0.15, 0.16, y + 0.018, 0.165, 5400179);
         }

         OriginalModels.cylinder(out, new RoadGeometry.V(-0.35, 1.04, 0.5), new RoadGeometry.V(0.35, 1.04, 0.5), 0.13, 0.13, 12, 6847880);
         OriginalModels.bevel(out, -0.085, 1.05, 0.45, 0.085, 4.42, 0.57, 0.025, 15856362);

         for (double y = 1.35; y < 4.4; y += 0.48) {
            OriginalModels.box(out, -0.09, y, 0.444, 0.09, y + 0.22, 0.576, 13911862);
         }

         OriginalModels.bevel(out, -0.1, 4.42, 0.44, 0.1, 4.5, 0.58, 0.025, 5398892);
         OriginalModels.box(out, 0.3, 0.84, 0.34, 0.315, 1.0, 0.65, 3258761);
      } else {
         if (!p.model().equals("sr:toll_model_island_nose")) {
            throw new IllegalArgumentException("Unknown toll equipment: " + p.model());
         }

         List<RoadGeometry.V> outline = List.of(
            new RoadGeometry.V(-0.34, 0.0, 0.05),
            new RoadGeometry.V(0.34, 0.0, 0.05),
            new RoadGeometry.V(0.48, 0.0, 0.26),
            new RoadGeometry.V(0.48, 0.0, 0.94),
            new RoadGeometry.V(-0.48, 0.0, 0.94),
            new RoadGeometry.V(-0.48, 0.0, 0.26)
         );
         OriginalModels.prism(out, outline, 0.0, 0.18, 3950672);
         OriginalModels.prism(out, outline, 0.18, 0.95, 15120432);
         OriginalModels.bevel(out, -0.48, 0.95, 0.07, 0.48, 1.07, 0.94, 0.13, 3161411);

         for (int side : new int[]{-1, 1}) {
            for (double z = 0.28; z < 0.9; z += 0.26) {
               OriginalModels.box(out, side < 0 ? -0.484 : 0.478, 0.27, z, side < 0 ? -0.478 : 0.484, 0.82, z + 0.12, 3357243);
            }
         }

         OriginalModels.box(out, -0.25, 0.3, 0.042, 0.25, 0.76, 0.052, 2567730);

         for (int side : new int[]{-1, 1}) {
            OriginalModels.face(
               out,
               List.of(
                  new RoadGeometry.V((double)side * 0.22, 0.36, 0.038),
                  new RoadGeometry.V((double)side * 0.04, 0.53, 0.038),
                  new RoadGeometry.V((double)side * 0.22, 0.7, 0.038)
               ),
               new RoadGeometry.V(0.0, 0.0, -1.0),
               16175179,
               false,
               RoadSurface.Texture.PLAIN
            );
         }

         for (int side : new int[]{-1, 1}) {
            OriginalModels.cylinder(
               out, new RoadGeometry.V((double)side * 0.33, 1.07, 0.73), new RoadGeometry.V((double)side * 0.33, 1.18, 0.73), 0.035, 0.035, 8, 9149343
            );
            OriginalModels.cylinder(
               out, new RoadGeometry.V((double)side * 0.33, 1.18, 0.73), new RoadGeometry.V((double)side * 0.33, 1.25, 0.73), 0.06, 0.06, 8, 15527129
            );
         }
      }

      RoadGeometry.V along = p.b().sub(p.a());
      return OriginalModels.transform(out, p.a(), along.horizontalUnit().left().mul(p.width()), new RoadGeometry.V(0.0, 1.0, 0.0), along);
   }

   private static void letter(List<RoadSurface.Face> out, char ch, double x, double width) {
      double[][] shape = switch (ch) {
         case 'C' -> new double[][]{
         {0.18, 0.0},
         {1.0, 0.0},
         {1.0, 0.18},
         {0.29, 0.18},
         {0.22, 0.25},
         {0.22, 0.75},
         {0.29, 0.82},
         {1.0, 0.82},
         {1.0, 1.0},
         {0.18, 1.0},
         {0.0, 0.82},
         {0.0, 0.18}
      };
         case 'E' -> new double[][]{
         {0.0, 0.0},
         {1.0, 0.0},
         {1.0, 0.17},
         {0.22, 0.17},
         {0.22, 0.42},
         {0.82, 0.42},
         {0.82, 0.59},
         {0.22, 0.59},
         {0.22, 0.83},
         {1.0, 0.83},
         {1.0, 1.0},
         {0.0, 1.0}
      };
         case 'M' -> new double[][]{
         {0.0, 0.0}, {0.2, 0.0}, {0.2, 0.68}, {0.5, 0.36}, {0.8, 0.68}, {0.8, 0.0}, {1.0, 0.0}, {1.0, 1.0}, {0.79, 1.0}, {0.5, 0.65}, {0.21, 1.0}, {0.0, 1.0}
      };
         case 'T' -> new double[][]{{0.39, 0.0}, {0.61, 0.0}, {0.61, 0.82}, {1.0, 0.82}, {1.0, 1.0}, {0.0, 1.0}, {0.0, 0.82}, {0.39, 0.82}};
         default -> throw new IllegalArgumentException("Unknown toll letter " + ch);
      };
      List<RoadGeometry.V> outline = Arrays.stream(shape).map(v -> new RoadGeometry.V(x + v[0] * width, 0.0, v[1] * 0.48)).toList();

      for (List<RoadGeometry.V> triangle : JunctionPaint.triangulate(outline)) {
         for (int side : new int[]{0, 1}) {
            OriginalModels.face(
               out,
               triangle.stream().map(v -> new RoadGeometry.V(v.x(), v.z(), (double)side)).toList(),
               new RoadGeometry.V(0.0, 0.0, side == 0 ? -1.0 : 1.0),
               16448238,
               false,
               RoadSurface.Texture.PLAIN
            );
         }
      }

      for (int i = 0; i < outline.size(); i++) {
         RoadGeometry.V a = outline.get(i);
         RoadGeometry.V b = outline.get((i + 1) % outline.size());
         OriginalModels.face(
            out,
            List.of(
               new RoadGeometry.V(a.x(), a.z(), 0.0),
               new RoadGeometry.V(b.x(), b.z(), 0.0),
               new RoadGeometry.V(b.x(), b.z(), 1.0),
               new RoadGeometry.V(a.x(), a.z(), 1.0)
            ),
            new RoadGeometry.V(b.z() - a.z(), a.x() - b.x(), 0.0),
            13424339,
            false,
            RoadSurface.Texture.PLAIN
         );
      }
   }

   private TollModels() {
   }
}
