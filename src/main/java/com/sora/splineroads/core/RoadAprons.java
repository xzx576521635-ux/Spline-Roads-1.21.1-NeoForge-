package com.sora.splineroads.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class RoadAprons {
   public static List<RoadAprons.JunctionCell> junctionCells(JunctionPlanner.Ref ref, Predicate<RoadGeometry.Sample> grounded) {
      List<RoadGeometry.V> edge = ref.boundary();
      LinkedHashMap<Long, RoadAprons.JunctionCell> out = new LinkedHashMap<>();
      double area = 0.0;

      for (int i = 0; i < edge.size(); i++) {
         RoadGeometry.V a = edge.get(i);
         RoadGeometry.V b = edge.get((i + 1) % edge.size());
         area += a.x() * b.z() - b.x() * a.z();
      }

      double sign = area >= 0.0 ? -1.0 : 1.0;

      for (int i = 0; i < edge.size(); i++) {
         RoadGeometry.V a = edge.get(i);
         RoadGeometry.V b = edge.get((i + 1) % edge.size());
         RoadGeometry.V mid = a.add(b).mul(0.5);
         if (!(a.distance(b) < 1.0E-7)) {
            int chosen = -1;
            double best = Double.POSITIVE_INFINITY;
            boolean mouth = false;

            for (int j = 0; j < ref.spec().arms().size(); j++) {
               RoadGeometry.Mesh mesh = ref.approach(j);
               if (RoadQueries.contains(mesh, mid, 0.02, 0.1)) {
                  mouth = true;
                  break;
               }

               RoadGeometry.Sample at = mesh.last();
               double d = Math.min(mid.distance(at.at(at.halfWidth(), 0.0)), mid.distance(at.at(-at.halfWidth(), 0.0)));
               if (d < best) {
                  best = d;
                  chosen = j;
               }
            }

            if (!mouth && chosen >= 0) {
               RoadGeometry.Settings external = ref.spec().arms().get(chosen).external();
               if (eligible(new RoadGeometry.Mesh(List.of(), external, a, b, 0.0, false))) {
                  RoadGeometry.V normal = b.sub(a).horizontalUnit().left().mul(sign);
                  RoadGeometry.V inside = normal.mul(-1.0);
                  if (grounded.test(new RoadGeometry.Sample(a, inside, 0.0, 1.0)) && grounded.test(new RoadGeometry.Sample(b, inside, a.distance(b), 1.0))) {
                     int arm = chosen;
                     RoadSidewalks.raster(List.of(a.sub(normal.mul(0.72)), a.add(normal), b.add(normal), b.sub(normal.mul(0.72))), a, b, c -> {
                        RoadGeometry.V p = new RoadGeometry.V((double)c.x() + 0.5, a.y(), (double)c.z() + 0.5);
                        if (!(p.sub(mid).dot(normal) > 1.0000001)) {
                           long key = (long)c.x() << 32 ^ (long)c.z() & 4294967295L;
                           out.putIfAbsent(Long.valueOf(key), new RoadAprons.JunctionCell(new RoadAprons.Cell(c.x(), c.z(), mid.y()), arm));
                        }
                     });
                  }
               }
            }
         }
      }

      return List.copyOf(out.values());
   }

   public static boolean eligible(RoadGeometry.Mesh mesh) {
      return mesh.settings().options().streetscape().apron() != RoadStreetscape.Apron.NONE
         && mesh.settings().structure() != RoadGeometry.Structure.BRIDGE
         && mesh.settings().structure() != RoadGeometry.Structure.TUNNEL
         && !mesh.settings().options().sidewalk().enabled();
   }

   public static List<RoadAprons.Cell> cells(RoadGeometry.Mesh mesh) {
      return cells(mesh, s -> true);
   }

   public static List<RoadAprons.Cell> cells(RoadGeometry.Mesh mesh, Predicate<RoadGeometry.Sample> grounded) {
      return cells(mesh, (sample, side) -> grounded.test(sample));
   }

   public static List<RoadAprons.Cell> cells(RoadGeometry.Mesh mesh, BiPredicate<RoadGeometry.Sample, Integer> grounded) {
      if (!eligible(mesh)) {
         return List.of();
      } else {
         LinkedHashMap<Long, RoadAprons.Cell> result = new LinkedHashMap<>();
         List<RoadGeometry.Sample> samples = mesh.samples();

         for (int i = 1; i < samples.size(); i++) {
            RoadGeometry.Sample a = samples.get(i - 1);
            RoadGeometry.Sample b = samples.get(i);
            if (!RoadStreetscape.underground(mesh, (a.distance() + b.distance()) / 2.0)) {
               for (int side : new int[]{-1, 1}) {
                  if (!RoadStreetscape.raised(mesh, a, side)
                     && !RoadStreetscape.raised(mesh, b, side)
                     && !RoadStreetscape.cut(mesh, a, side)
                     && !RoadStreetscape.cut(mesh, b, side)
                     && grounded.test(a, side)
                     && grounded.test(b, side)) {
                     List<RoadGeometry.V> band = List.of(
                        a.at((double)side * Math.max(0.0, a.halfWidth() - 0.72), 0.0),
                        a.at((double)side * (a.halfWidth() + 1.0), 0.0),
                        b.at((double)side * (b.halfWidth() + 1.0), 0.0),
                        b.at((double)side * Math.max(0.0, b.halfWidth() - 0.72), 0.0)
                     );
                     RoadSidewalks.raster(
                        band,
                        a.center(),
                        b.center(),
                        cell -> {
                           RoadGeometry.V p = new RoadGeometry.V((double)cell.x() + 0.5, 0.0, (double)cell.z() + 0.5);
                           RoadGeometry.Sample first = mesh.first();
                           RoadGeometry.Sample last = mesh.last();
                           if (mesh.closed()
                              || (!(a.distance() < 1.5) || !(p.sub(first.center()).dot(first.left().left().mul(-1.0)) < 0.0))
                                 && (!(b.distance() > mesh.length() - 1.5) || !(p.sub(last.center()).dot(last.left().left().mul(-1.0)) > 0.0))) {
                              RoadGeometry.V d = b.center().sub(a.center());
                              double t = Math.max(
                                 0.0,
                                 Math.min(1.0, p.sub(a.center()).dot(new RoadGeometry.V(d.x(), 0.0, d.z())) / Math.max(1.0E-9, d.x() * d.x() + d.z() * d.z()))
                              );
                              RoadGeometry.V center = a.center().mul(1.0 - t).add(b.center().mul(t));
                              RoadGeometry.V left = a.left().mul(1.0 - t).add(b.left().mul(t)).horizontalUnit();
                              if (!(Math.abs(p.sub(center).dot(left)) > a.halfWidth() * (1.0 - t) + b.halfWidth() * t + 1.0 + 1.0E-7)) {
                                 double top = a.center().y() + d.y() * t;
                                 long key = (long)cell.x() << 32 ^ (long)cell.z() & 4294967295L;
                                 RoadAprons.Cell old = result.get(key);
                                 if (old == null || top < old.top()) {
                                    result.put(Long.valueOf(key), new RoadAprons.Cell(cell.x(), cell.z(), top));
                                 }
                              }
                           }
                        }
                     );
                  }
               }
            }
         }

         return List.copyOf(result.values());
      }
   }

   private RoadAprons() {
   }

   public static record Cell(int x, int z, double top) {
   }

   public static record JunctionCell(RoadAprons.Cell cell, int arm) {
   }
}
