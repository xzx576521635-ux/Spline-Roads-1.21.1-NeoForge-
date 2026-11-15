package com.sora.splineroads.core;

import java.util.List;

public final class JunctionSignalSupports {
   public static void add(
      List<RoadStructures.Part> parts, List<RoadGeometry.Mesh> decks, List<RoadStructures.Part> sidewalks, RoadStructures.Ground ground, double thickness
   ) {
      for (RoadStructures.Part base : List.copyOf(parts)) {
         if (base.material() == RoadStructures.Material.CB_BASE) {
            RoadGeometry.V foot = base.a().add(base.b()).mul(0.5);
            List<RoadGeometry.V> bottom = RoadPoleModel.faces(base)
               .stream()
               .flatMap(f -> f.points().stream())
               .filter(px -> Math.abs(px.y() - foot.y()) < 1.0E-7)
               .toList();
            if (!bottom.stream()
               .allMatch(
                  px -> decks.stream().anyMatch(m -> RoadQueries.contains(m, px, -0.005, 0.03))
                        || sidewalks.stream().anyMatch(sx -> onTop(sx, px))
                        || Math.abs(ground.top(px.x(), px.z(), px.y()) - px.y()) < 0.025
               )) {
               RoadGeometry.V anchor = null;
               double best = Double.POSITIVE_INFINITY;

               for (RoadGeometry.Mesh mesh : decks) {
                  RoadQueries.Projection q = RoadQueries.horizontal(mesh, foot);
                  RoadGeometry.Sample s = q.sample();
                  if (!(Math.abs(s.center().y() - foot.y()) > 0.25)) {
                     double lateral = Math.max(-s.halfWidth() + 0.25, Math.min(s.halfWidth() - 0.25, q.lateral()));
                     RoadGeometry.V p = s.at(lateral, 0.0);
                     double distance = p.sub(foot).horizontalLength();
                     if (distance < best && RoadQueries.contains(mesh, p, 0.0, 0.3)) {
                        anchor = p;
                        best = distance;
                     }
                  }
               }

               if (anchor == null || best > 8.0) {
                  throw new IllegalArgumentException("路口信号灯缺少邻近路面承托，请调整路口参数");
               }

               double depth = Math.max(0.4, thickness);
               double y = Math.min(anchor.y(), foot.y()) - depth;
               RoadGeometry.V direction = foot.sub(anchor).horizontalLength() > 1.0E-7 ? foot.sub(anchor).horizontalUnit() : new RoadGeometry.V(0.0, 0.0, 1.0);
               RoadStructures.Part seat = new RoadStructures.Part(
                  new RoadGeometry.V(anchor.x(), y, anchor.z()).sub(direction.mul(0.5)),
                  new RoadGeometry.V(foot.x(), y, foot.z()).add(direction.mul(0.5)),
                  0.9,
                  foot.y() - y,
                  false,
                  RoadStructures.Material.CONCRETE,
                  null,
                  null,
                  "sr:junction_signal_seat"
               );
               if (ground.blocked(seat)) {
                  throw new IllegalArgumentException("路口信号灯承托与其他道路冲突，请调整路口参数");
               }

               parts.add(seat);
            }
         }
      }
   }

   private static boolean onTop(RoadStructures.Part part, RoadGeometry.V p) {
      if (part.material() != RoadStructures.Material.CONCRETE && !part.material().name().startsWith("WALK_")) {
         return false;
      } else {
         List<RoadGeometry.V> top = part.top();
         return top.stream().anyMatch(v -> Math.abs(v.y() - p.y()) > 0.03) ? false : JunctionPaint.inside(top, p);
      }
   }

   private JunctionSignalSupports() {
   }
}
