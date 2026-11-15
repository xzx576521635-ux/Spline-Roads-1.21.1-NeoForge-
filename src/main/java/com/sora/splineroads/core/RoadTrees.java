package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

final class RoadTrees {
   static List<RoadStructures.Part> model(RoadGeometry.V foot, RoadStreetscape.Planting type, RoadGeometry.V along) {
      if (type != RoadStreetscape.Planting.NONE && type != RoadStreetscape.Planting.FLOWERS) {
         int variant = RoadStreetscape.treeVariant(foot, type);
         along = along.horizontalUnit();
         RoadGeometry.V side = along.left();

         RoadStructures.Material bark = switch (type) {
            case BIRCH -> RoadStructures.Material.BIRCH_LOG;
            case SPRUCE -> RoadStructures.Material.SPRUCE_LOG;
            case CHERRY -> RoadStructures.Material.CHERRY_LOG;
            default -> RoadStructures.Material.OAK_LOG;
         };

         RoadStructures.Material leaf = switch (type) {
            case BIRCH -> RoadStructures.Material.BIRCH_LEAVES;
            case SPRUCE -> RoadStructures.Material.SPRUCE_LEAVES;
            case CHERRY -> RoadStructures.Material.CHERRY_LEAVES;
            default -> RoadStructures.Material.OAK_LEAVES;
         };
         ArrayList<RoadStructures.Part> out = new ArrayList<>();
         double fork = 4.7;
         double stem = type == RoadStreetscape.Planting.BIRCH ? 0.34 : (type == RoadStreetscape.Planting.SPRUCE ? 0.44 : 0.52);
         out.add(new RoadStructures.Part(foot, foot, stem, fork + 0.45, true, bark));
         RoadGeometry.V root = foot.add(new RoadGeometry.V(0.0, fork, 0.0));
         if (type == RoadStreetscape.Planting.SPRUCE) {
            int tiers = new int[]{5, 4, 6, 4, 5, 3}[variant];
            double step = new double[]{0.82, 1.15, 0.72, 1.02, 0.9, 1.3}[variant];
            out.add(new RoadStructures.Part(root, root, 0.28, (double)tiers * step + 0.65, true, bark));

            for (int tier = 0; tier < tiers; tier++) {
               double radius = (variant == 5 ? 2.35 : 1.95) * (1.0 - (double)tier / ((double)tiers + 0.3));
               RoadGeometry.V center = root.add(along.mul(variant == 2 ? (double)tier * 0.13 : 0.0)).add(new RoadGeometry.V(0.0, (double)tier * step, 0.0));
               int spokes = (variant == 1 ? 4 : 5) + (tier + variant) % 2;

               for (int k = 0; k < spokes; k++) {
                  double angle = (double)(k * 2) * Math.PI / (double)spokes + (double)tier * 0.63 + (double)variant * 0.7;
                  RoadGeometry.V dir = along.mul(Math.cos(angle)).add(side.mul(Math.sin(angle)));
                  double reach = radius * (variant == 4 && k % 2 == 0 ? 0.66 : 1.0);
                  RoadGeometry.V tip = center.add(dir.mul(reach));
                  branch(out, center, tip.add(new RoadGeometry.V(0.0, 0.22, 0.0)), 0.13, bark);
                  crown(out, center.add(dir.mul(reach * 0.65)), along, reach * 0.72, 0.64, 0.62, leaf, variant + k);
               }

               crown(out, center, along, radius * 0.66, radius * 0.66, 0.9, leaf, tier);
            }

            crown(out, root.add(new RoadGeometry.V(0.0, (double)tiers * step - 0.15, 0.0)), along, 0.36, 0.36, 0.95, leaf, variant);
            if (variant == 3) {
               RoadGeometry.V split = root.add(side.mul(0.9)).add(new RoadGeometry.V(0.0, 1.4, 0.0));
               branch(out, root, split, 0.19, bark);
               crown(out, split, along, 0.68, 0.68, 2.2, leaf, 4);
            }
         } else {
            int arms = new int[]{5, 4, 2, 6, 3, 5}[variant];
            double reach = type == RoadStreetscape.Planting.CHERRY ? 2.05 : (type == RoadStreetscape.Planting.BIRCH ? 1.05 : 1.65);
            double rise = type == RoadStreetscape.Planting.BIRCH ? 2.0 : (type == RoadStreetscape.Planting.CHERRY ? 0.65 : 1.15);

            for (int arm = 0; arm < arms; arm++) {
               double angle = (double)(arm * 2) * Math.PI / (double)arms + (double)variant * 0.67;
               RoadGeometry.V dir = along.mul(Math.cos(angle)).add(side.mul(Math.sin(angle)));
               double spread = reach * (variant == 1 ? 1.15 : (variant == 2 ? 0.82 : (variant == 5 ? 1.08 : 1.0)));
               double uneven = variant == 3 ? (double)(arm % 3 - 1) * 0.45 : (variant == 4 ? (double)arm * 0.52 : 0.0);
               RoadGeometry.V elbow = root.add(dir.mul(spread * 0.48)).add(new RoadGeometry.V(0.0, 0.4 + Math.max(0.0, uneven), 0.0));
               RoadGeometry.V tip = root.add(dir.mul(spread)).add(new RoadGeometry.V(0.0, rise + uneven, 0.0));
               branch(out, root, elbow, stem * 0.65, bark);
               branch(out, elbow, tip, 0.19, bark);
               double rx = type == RoadStreetscape.Planting.BIRCH ? 0.85 : (type == RoadStreetscape.Planting.CHERRY ? 1.45 : 1.2);
               double ry = type == RoadStreetscape.Planting.BIRCH ? 2.3 : (type == RoadStreetscape.Planting.CHERRY ? 1.15 : 1.65);
               crown(out, tip, along, rx, rx * (variant == 1 ? 1.1 : 0.85), ry, leaf, variant + arm);
               if (variant == 2 || variant == 3 || variant == 5) {
                  RoadGeometry.V twig = tip.add(side.mul((double)(arm % 2 == 0 ? 1 : -1) * 0.65)).add(new RoadGeometry.V(0.0, 0.65, 0.0));
                  branch(out, elbow, twig, 0.12, bark);
                  crown(out, twig, along, rx * 0.75, rx * 0.7, ry * 0.78, leaf, arm + 2);
               }
            }

            if (variant != 5) {
               double height = type == RoadStreetscape.Planting.BIRCH ? 2.65 : (type == RoadStreetscape.Planting.CHERRY ? 1.1 : 1.8);
               RoadGeometry.V top = root.add(new RoadGeometry.V(0.0, height, 0.0));
               branch(out, root, top, stem * 0.6, bark);
               crown(
                  out,
                  top,
                  along,
                  type == RoadStreetscape.Planting.BIRCH ? 0.9 : 1.6,
                  type == RoadStreetscape.Planting.BIRCH ? 0.85 : 1.35,
                  type == RoadStreetscape.Planting.CHERRY ? 1.25 : 1.65,
                  leaf,
                  variant + 1
               );
            }
         }

         return List.copyOf(out);
      } else {
         return List.of();
      }
   }

   private static void branch(List<RoadStructures.Part> out, RoadGeometry.V a, RoadGeometry.V b, double size, RoadStructures.Material material) {
      out.add(new RoadStructures.Part(a, b, size, size, false, material));
   }

   private static void crown(
      List<RoadStructures.Part> out, RoadGeometry.V base, RoadGeometry.V along, double rx, double rz, double height, RoadStructures.Material leaf, int shape
   ) {
      RoadGeometry.V side = along.left();
      double[] profile = shape % 3 == 0
         ? new double[]{0.62, 0.92, 1.0, 0.76, 0.42}
         : (shape % 3 == 1 ? new double[]{0.78, 1.0, 0.88, 0.65, 0.3} : new double[]{0.48, 0.8, 1.0, 0.9, 0.56});

      for (int level = 0; level < profile.length; level++) {
         double f = profile[level];
         double dy = height * (double)level / (double)profile.length;
         RoadGeometry.V center = base.add(along.mul(Math.sin((double)shape + (double)level * 1.2) * 0.09))
            .add(side.mul(Math.cos((double)shape * 1.7 + (double)level) * 0.08))
            .add(new RoadGeometry.V(0.0, dy, 0.0));
         double h = height / (double)profile.length + 0.035;
         box(out, center, along, 2.0 * rx * f, 1.32 * rz * f, h, leaf);
         box(out, center, along, 1.32 * rx * f, 2.0 * rz * f, h, leaf);
      }
   }

   private static void box(
      List<RoadStructures.Part> out, RoadGeometry.V center, RoadGeometry.V along, double length, double width, double height, RoadStructures.Material material
   ) {
      out.add(new RoadStructures.Part(center.sub(along.mul(length / 2.0)), center.add(along.mul(length / 2.0)), width, height, false, material));
   }

   private RoadTrees() {
   }
}
