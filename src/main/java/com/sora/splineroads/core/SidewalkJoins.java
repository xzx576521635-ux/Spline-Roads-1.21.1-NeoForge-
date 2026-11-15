package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class SidewalkJoins {
   private final Map<Long, List<SidewalkJoins.Cut>> grid = new HashMap<>();

   public SidewalkJoins(Collection<RoadGeometry.Mesh> decks, Collection<RoadStructures.Part> prior) {
      for (RoadGeometry.Mesh m : decks) {
         for (int i = 1; i < m.samples().size(); i++) {
            RoadGeometry.Sample a = m.samples().get(i - 1);
            RoadGeometry.Sample b = m.samples().get(i);
            this.add(
               new SidewalkJoins.Cut(List.of(a.at(a.halfWidth(), 0.0), a.at(-a.halfWidth(), 0.0), b.at(-b.halfWidth(), 0.0), b.at(b.halfWidth(), 0.0)), -1)
            );
         }
      }

      for (RoadStructures.Part p : prior) {
         if (RoadSidewalks.smoothPart(p)) {
            this.add(new SidewalkJoins.Cut(p.base(), layer(p)));
         }
      }
   }

   private static long key(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   private void add(SidewalkJoins.Cut c) {
      if (!(Math.abs(JunctionPaint.area(c.points)) < 1.0E-9)) {
         for (int x = (int)Math.floor(c.minX / 8.0); x <= (int)Math.floor(c.maxX / 8.0); x++) {
            for (int z = (int)Math.floor(c.minZ / 8.0); z <= (int)Math.floor(c.maxZ / 8.0); z++) {
               this.grid.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(c);
            }
         }
      }
   }

   private static int layer(RoadStructures.Part p) {
      return p.material() != RoadStructures.Material.TACTILE ? 0 : (p.height() < 0.018 ? 1 : 2);
   }

   private static boolean isConvex(List<RoadGeometry.V> points) {
      double sign = 0.0;

      for (int i = 0; i < points.size(); i++) {
         RoadGeometry.V a = points.get((i + 1) % points.size()).sub(points.get(i));
         RoadGeometry.V b = points.get((i + 2) % points.size()).sub(points.get((i + 1) % points.size()));
         double cross = a.x() * b.z() - a.z() * b.x();
         if (!(Math.abs(cross) < 1.0E-9)) {
            if (sign * cross < 0.0) {
               return false;
            }

            sign = cross;
         }
      }

      return true;
   }

   public List<RoadStructures.Part> clip(List<RoadStructures.Part> parts) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      ArrayList<RoadStructures.Part> convex = new ArrayList<>();

      for (RoadStructures.Part part : parts) {
         if (!RoadSidewalks.smoothPart(part)) {
            convex.add(part);
         } else {
            List<RoadGeometry.V> poly = part.base();
            if (isConvex(poly)) {
               convex.add(part);
            } else {
               for (int i = 1; i < poly.size() - 1; i++) {
                  RoadGeometry.V a = poly.get(0);
                  RoadGeometry.V b = poly.get(i);
                  RoadGeometry.V c = poly.get(i + 1);
                  if (Math.abs(JunctionPaint.area(List.of(a, b, c))) > 1.0E-8) {
                     convex.add(
                        new RoadStructures.Part(a.add(b).mul(0.5), c, Math.max(0.001, Math.min(8.0, a.distance(b))), part.height(), false, part.material())
                           .frames(a.sub(b).mul(0.5), new RoadGeometry.V(0.0, 0.0, 0.0))
                     );
                  }
               }
            }
         }
      }

      for (RoadStructures.Part p : convex) {
         if (!RoadSidewalks.smoothPart(p)) {
            out.add(p);
         } else {
            List<RoadGeometry.V> original = p.base();
            SidewalkJoins.Cut box = new SidewalkJoins.Cut(original, layer(p));
            LinkedHashSet<SidewalkJoins.Cut> candidates = new LinkedHashSet<>();

            for (int x = (int)Math.floor(box.minX / 8.0); x <= (int)Math.floor(box.maxX / 8.0); x++) {
               for (int z = (int)Math.floor(box.minZ / 8.0); z <= (int)Math.floor(box.maxZ / 8.0); z++) {
                  candidates.addAll(this.grid.getOrDefault(key(x, z), List.of()));
               }
            }

            List<List<RoadGeometry.V>> polygons = List.of(original);
            boolean changed = false;

            for (SidewalkJoins.Cut cut : candidates) {
               if ((cut.layer < 0 || cut.layer == box.layer)
                  && !(cut.maxX <= box.minX + 1.0E-7)
                  && !(cut.minX >= box.maxX - 1.0E-7)
                  && !(cut.maxZ <= box.minZ + 1.0E-7)
                  && !(cut.minZ >= box.maxZ - 1.0E-7)
                  && !(box.low > cut.high + 0.6)
                  && !(box.high < cut.low - 0.6)) {
                  ArrayList<List<RoadGeometry.V>> next = new ArrayList<>();

                  for (List<RoadGeometry.V> poly : polygons) {
                     next.addAll(RoadSurface.subtract(poly, cut.points));
                  }

                  double before = polygons.stream().mapToDouble(v -> Math.abs(JunctionPaint.area((List<RoadGeometry.V>)v))).sum();
                  double after = next.stream().mapToDouble(v -> Math.abs(JunctionPaint.area((List<RoadGeometry.V>)v))).sum();
                  if (before - after > 1.0E-7) {
                     changed = true;
                     polygons = next;
                  }

                  if (polygons.isEmpty()) {
                     break;
                  }
               }
            }

            if (!changed) {
               out.add(p);
               this.add(box);
            } else {
               for (List<RoadGeometry.V> poly : polygons) {
                  if (Math.abs(JunctionPaint.area(poly)) > 1.0E-7) {
                     this.add(new SidewalkJoins.Cut(poly, box.layer));

                     for (int ix = 1; ix < poly.size() - 1; ix++) {
                        RoadGeometry.V a = poly.get(0);
                        RoadGeometry.V b = poly.get(ix);
                        RoadGeometry.V c = poly.get(ix + 1);
                        if (!(Math.abs(JunctionPaint.area(List.of(a, b, c))) < 1.0E-8)) {
                           RoadGeometry.V frame = a.sub(b).mul(0.5);
                           out.add(
                              new RoadStructures.Part(a.add(b).mul(0.5), c, Math.max(0.001, Math.min(8.0, a.distance(b))), p.height(), false, p.material())
                                 .frames(frame, new RoadGeometry.V(0.0, 0.0, 0.0))
                           );
                        }
                     }
                  }
               }
            }
         }
      }

      return List.copyOf(out);
   }

   private static record Cut(List<RoadGeometry.V> points, int layer, double low, double high, double minX, double maxX, double minZ, double maxZ) {
      Cut(List<RoadGeometry.V> p, int layer) {
         this(
            p,
            layer,
            p.stream().mapToDouble(RoadGeometry.V::y).min().orElseThrow(),
            p.stream().mapToDouble(RoadGeometry.V::y).max().orElseThrow(),
            p.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(),
            p.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(),
            p.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow(),
            p.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()
         );
      }
   }
}
