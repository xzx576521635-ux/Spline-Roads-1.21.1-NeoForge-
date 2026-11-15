package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RailwayCrossingSigns {
   public static final String MODEL = "sr:railway_crossing_sign";

   public static List<RoadStructures.Part> plan(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();

      for (RailwayCrossings.Surface crossing : mesh.settings().options().levelCrossings()) {
         for (int end : new int[]{-1, 1}) {
            int side = end * (mesh.settings().options().leftTraffic() ? -1 : 1);

            for (double setback : new double[]{2.0, 4.0, 6.0}) {
               double station = (end < 0 ? crossing.from() : crossing.to()) + (double)end * setback;
               if (!(station < 1.0) && !(station > mesh.length() - 1.0)) {
                  RoadGeometry.Sample at = RoadStructures.sample(mesh, station);
                  double walk = RoadSidewalks.walkWidth(mesh, mesh.settings().options().sidewalk(), at, side);
                  RoadGeometry.V foot = at.at((double)side * (at.halfWidth() + walk + 1.2), 0.0);
                  double floor = ground.top(foot.x(), foot.z(), foot.y() + 1.0);
                  if (Double.isFinite(floor) && !(Math.abs(floor - foot.y()) > 1.5)) {
                     foot = new RoadGeometry.V(foot.x(), floor, foot.z());
                     RoadStructures.Part sign = new RoadStructures.Part(
                        foot.sub(at.left().mul(0.6)),
                        foot.add(at.left().mul(0.6)),
                        0.12,
                        3.2,
                        false,
                        RoadStructures.Material.STEEL,
                        null,
                        null,
                        "sr:railway_crossing_sign"
                     );
                     if (!ground.blocked(sign) && out.stream().noneMatch(p -> p.a().distance(sign.a()) < 2.0)) {
                        out.add(sign);
                        break;
                     }
                  }
               }
            }
         }
      }

      return List.copyOf(out);
   }

   public static List<RoadSurface.Face> faces(RoadStructures.Part p) {
      ArrayList<RoadSurface.Face> local = new ArrayList<>();

      for (double y = 0.0; y < 2.6; y += 0.4) {
         OriginalModels.box(local, -0.06, y, -0.06, 0.06, Math.min(y + 0.4, 2.6), 0.06, (int)Math.round(y / 0.4) % 2 == 0 ? 15527399 : 12399661);
      }

      for (int side : new int[]{-1, 1}) {
         ArrayList<RoadSurface.Face> bar = new ArrayList<>();

         for (int i = 0; i < 3; i++) {
            OriginalModels.box(
               bar, i == 0 ? -0.6 : (i == 1 ? -0.4 : 0.4), -0.07, -0.035, i == 0 ? -0.4 : (i == 1 ? 0.4 : 0.6), 0.07, 0.035, i == 1 ? 15527399 : 12399661
            );
         }

         double angle = (double)side * Math.toRadians(35.0);
         double c = Math.cos(angle);
         double s = Math.sin(angle);

         for (RoadSurface.Face face : bar) {
            local.add(
               new RoadSurface.Face(
                  face.points().stream().map(v -> new RoadGeometry.V(v.x() * c - v.y() * s, 2.65 + v.x() * s + v.y() * c, v.z())).toList(),
                  face.color(),
                  false,
                  RoadSurface.Texture.PLAIN
               )
            );
         }
      }

      RoadGeometry.V origin = p.a().add(p.b()).mul(0.5);
      RoadGeometry.V right = p.b().sub(p.a()).horizontalUnit();
      RoadGeometry.V front = right.left();
      return local.stream()
         .map(
            f -> new RoadSurface.Face(
                  f.points().stream().map(v -> origin.add(right.mul(v.x())).add(front.mul(v.z())).add(new RoadGeometry.V(0.0, v.y(), 0.0))).toList(),
                  f.color(),
                  false,
                  RoadSurface.Texture.PLAIN
               )
         )
         .toList();
   }

   private RailwayCrossingSigns() {
   }
}
