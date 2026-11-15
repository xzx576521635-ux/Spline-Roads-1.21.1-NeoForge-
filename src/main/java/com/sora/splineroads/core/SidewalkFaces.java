package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class SidewalkFaces {
   private final Map<Long, List<RoadStructures.Part>> grid = new HashMap<>();
   private final long retainedBytes;
   private final IdentityHashMap<RoadStructures.Part, SidewalkFaces.Footprint> footprints = new IdentityHashMap<>();

   SidewalkFaces(List<RoadStructures.Part> parts) {
      for (RoadStructures.Part p : parts) {
         if (walk(p)) {
            List<RoadGeometry.V> polygon = p.base();
            double y = polygon.stream().mapToDouble(RoadGeometry.V::y).average().orElseThrow();
            this.footprints
               .put(p, new SidewalkFaces.Footprint(polygon, y, JunctionPaint.area(polygon), polygon.stream().noneMatch(v -> Math.abs(v.y() - y) > 1.0E-6)));

            for (long key : keys(polygon)) {
               this.grid.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            }
         }
      }

      this.retainedBytes = (long)this.footprints.size() * 384L
         + (long)this.grid.size() * 80L
         + this.grid.values().stream().mapToLong(v -> (long)v.size() * 8L).sum();
   }

   long bytes() {
      return this.retainedBytes;
   }

   static boolean walk(RoadStructures.Part p) {
      return p.material().name().startsWith("WALK_");
   }

   private static List<Long> keys(List<RoadGeometry.V> poly) {
      int x0 = (int)Math.floor(poly.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow() / 8.0);
      int x1 = (int)Math.floor(poly.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow() / 8.0);
      int z0 = (int)Math.floor(poly.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow() / 8.0);
      int z1 = (int)Math.floor(poly.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow() / 8.0);
      ArrayList<Long> out = new ArrayList<>();

      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            out.add((long)x << 32 ^ (long)z & 4294967295L);
         }
      }

      return out;
   }

   List<RoadSurface.Face> exposed(RoadStructures.Part owner, RoadSurface.Face face) {
      List<RoadGeometry.V> f = face.points();
      List<RoadGeometry.V> low = f.stream().sorted(Comparator.comparingDouble(RoadGeometry.V::y)).limit(2L).toList();
      RoadGeometry.V a = low.get(0);
      RoadGeometry.V b = low.get(1);
      if (a.sub(b).horizontalLength() < 1.0E-8) {
         // The two lowest points are the ends of one vertical edge. That happens on
         // sloped sidewalks, where the whole exposed outer side face was dropped and
         // left a see-through gap in the slope/bridge side. Fall back to the longest
         // horizontal edge (preferring the lower one) so those faces get tested and
         // kept.
         double longest = -1.0;
         double longestY = Double.POSITIVE_INFINITY;

         for (int i = 0; i < f.size(); i++) {
            RoadGeometry.V p = f.get(i);
            RoadGeometry.V q = f.get((i + 1) % f.size());
            double length = p.sub(q).horizontalLength();
            double midY = (p.y() + q.y()) * 0.5;
            if (length > longest + 1.0E-6 || Math.abs(length - longest) <= 1.0E-6 && midY < longestY) {
               longest = length;
               longestY = midY;
               a = p;
               b = q;
            }
         }
      }

      if (a.sub(b).horizontalLength() < 1.0E-8) {
         return List.of();
      } else {
         HashSet<RoadStructures.Part> candidates = new HashSet<>();

         for (long key : keys(List.of(a, b))) {
            candidates.addAll(this.grid.getOrDefault(key, List.of()));
         }

         List<double[]> visible = new ArrayList<>();
         visible.add(new double[]{0.0, 1.0});

         for (RoadStructures.Part other : candidates) {
            if (other != owner && !(Math.abs(other.height() - owner.height()) > 1.0E-6)) {
               SidewalkFaces.Footprint shape = this.footprints.get(other);
               List<RoadGeometry.V> poly = shape.polygon();
               double y = shape.y();
               if (shape.flat() && !(Math.abs(a.y() - y) > 1.0E-6) && !(Math.abs(b.y() - y) > 1.0E-6)) {
                  double area = shape.area();
                  double sign = Math.signum(area);
                  if (!(Math.abs(area) < 1.0E-9)) {
                     double lo = 0.0;
                     double hi = 1.0;
                     boolean miss = false;

                     for (int i = 0; i < poly.size(); i++) {
                        RoadGeometry.V p = poly.get(i);
                        RoadGeometry.V q = poly.get((i + 1) % poly.size());
                        double aa = sign * RoadAxis.cross(q.sub(p), a.sub(p));
                        double bb = sign * RoadAxis.cross(q.sub(p), b.sub(p));
                        double eps = 1.0E-7 * Math.max(1.0, q.sub(p).horizontalLength());
                        if (Math.abs(aa) < eps) {
                           aa = 0.0;
                        }

                        if (Math.abs(bb) < eps) {
                           bb = 0.0;
                        }

                        double d = bb - aa;
                        if (Math.abs(d) < 1.0E-12) {
                           if (aa < 0.0) {
                              miss = true;
                              break;
                           }
                        } else if (d > 0.0) {
                           lo = Math.max(lo, -aa / d);
                        } else {
                           hi = Math.min(hi, -aa / d);
                        }
                     }

                     if (!miss && !(hi <= lo + 1.0E-8)) {
                        ArrayList<double[]> next = new ArrayList<>();

                        for (double[] r : visible) {
                           if (lo > r[0] + 1.0E-8) {
                              next.add(new double[]{r[0], Math.min(lo, r[1])});
                           }

                           if (hi < r[1] - 1.0E-8) {
                              next.add(new double[]{Math.max(hi, r[0]), r[1]});
                           }
                        }

                        visible = next;
                     }
                  }
               }
            }
         }

         if (visible.size() == 1 && visible.get(0)[0] == 0.0 && visible.get(0)[1] == 1.0) {
            return List.of(face);
         } else {
            ArrayList<RoadSurface.Face> out = new ArrayList<>();

            for (double[] r : visible) {
               RoadGeometry.V px = a.add(b.sub(a).mul(r[0]));
               RoadGeometry.V qx = a.add(b.sub(a).mul(r[1]));
               ArrayList<RoadGeometry.V> points = new ArrayList<>(
                  List.of(px, qx, qx.add(new RoadGeometry.V(0.0, owner.height(), 0.0)), px.add(new RoadGeometry.V(0.0, owner.height(), 0.0)))
               );
               RoadSurface.Face candidate = new RoadSurface.Face(points, face.color(), face.emissive(), face.texture());
               if (RoadLighting.normal(candidate).dot(RoadLighting.normal(face)) < 0.0) {
                  Collections.reverse(points);
                  candidate = new RoadSurface.Face(points, face.color(), face.emissive(), face.texture());
               }

               out.add(candidate);
            }

            return out;
         }
      }
   }

   private static record Footprint(List<RoadGeometry.V> polygon, double y, double area, boolean flat) {
   }
}
