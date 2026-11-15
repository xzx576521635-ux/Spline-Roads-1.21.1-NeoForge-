package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

public final class RoadPavingSupport {
   private final Map<Long, List<RoadPavingSupport.Patch>> grid = new HashMap<>();

   public RoadPavingSupport(Collection<RoadStructures.Part> parts) {
      for (RoadStructures.Part part : parts) {
         if (part.material().name().startsWith("WALK_")) {
            List<RoadGeometry.V> top = part.top();

            for (int i = 1; i < top.size() - 1; i++) {
               List<RoadGeometry.V> triangle = List.of(top.get(0), top.get(i), top.get(i + 1));
               if (!(RoadSurface.area(triangle) < 1.0E-10)) {
                  RoadPavingSupport.Patch patch = new RoadPavingSupport.Patch(
                     part, triangle, triangle.stream().mapToDouble(RoadGeometry.V::y).max().orElseThrow()
                  );

                  for (int x = lo(triangle, true); x <= hi(triangle, true); x++) {
                     for (int z = lo(triangle, false); z <= hi(triangle, false); z++) {
                        this.grid.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(patch);
                     }
                  }
               }
            }
         }
      }
   }

   private static int lo(List<RoadGeometry.V> p, boolean x) {
      return (int)Math.floor(p.stream().mapToDouble(v -> x ? v.x() : v.z()).min().orElseThrow() / 16.0);
   }

   private static int hi(List<RoadGeometry.V> p, boolean x) {
      return (int)Math.floor(p.stream().mapToDouble(v -> x ? v.x() : v.z()).max().orElseThrow() / 16.0);
   }

   private static long key(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   private List<RoadPavingSupport.Patch> near(List<RoadGeometry.V> base) {
      LinkedHashSet<RoadPavingSupport.Patch> found = new LinkedHashSet<>();

      for (int x = lo(base, true); x <= hi(base, true); x++) {
         for (int z = lo(base, false); z <= hi(base, false); z++) {
            found.addAll(this.grid.getOrDefault(key(x, z), List.of()));
         }
      }

      return found.stream().sorted(Comparator.comparingDouble(RoadPavingSupport.Patch::high).reversed()).toList();
   }

   public double foundation(RoadGeometry.V center, double top, double width) {
      RoadGeometry.V position = new RoadGeometry.V(center.x(), top, center.z());
      List<RoadGeometry.V> base = new RoadStructures.Part(position, position, width, 0.35, true, RoadStructures.Material.CONCRETE).base();
      ArrayList<List<RoadGeometry.V>> uncovered = new ArrayList<>();
      uncovered.add(base);
      double floor = Double.POSITIVE_INFINITY;
      double ceiling = Double.NEGATIVE_INFINITY;

      for (RoadPavingSupport.Patch patch : this.near(base)) {
         ArrayList<List<RoadGeometry.V>> next = new ArrayList<>();

         for (List<RoadGeometry.V> polygon : uncovered) {
            List<RoadGeometry.V> clip = RoadSurface.intersect(polygon, patch.top());
            if (clip.size() >= 3 && !(RoadSurface.area(clip) < 1.0E-9)) {
               double low = clip.stream().mapToDouble(patch::height).min().orElseThrow();
               double high = clip.stream().mapToDouble(patch::height).max().orElseThrow();
               if (!(high >= top - 0.4) && !(top - low > 96.0)) {
                  floor = Math.min(floor, low);
                  ceiling = Math.max(ceiling, high);
                  next.addAll(RoadSurface.subtract(polygon, patch.top()));
               } else {
                  next.add(polygon);
               }
            } else {
               next.add(polygon);
            }
         }

         uncovered = next;
         if (next.isEmpty()) {
            break;
         }
      }

      return uncovered.stream().mapToDouble(RoadSurface::area).sum() < 1.0E-7 && Double.isFinite(floor) && ceiling - floor <= width * 0.5 + 0.02
         ? floor
         : Double.NaN;
   }

   public double foundation(RoadGeometry.V center, double top, double width, double median, DoubleSupplier terrain) {
      double paving = this.foundation(center, top, width);
      if (!Double.isFinite(median) && !Double.isFinite(paving)) {
         RoadGeometry.V position = new RoadGeometry.V(center.x(), top, center.z());
         List<RoadGeometry.V> base = new RoadStructures.Part(position, position, width, 0.35, true, RoadStructures.Material.CONCRETE).base();

         for (RoadPavingSupport.Patch patch : this.near(base)) {
            List<RoadGeometry.V> clip = RoadSurface.intersect(base, patch.top());
            if (clip.size() >= 3 && !(RoadSurface.area(clip) < 1.0E-9)) {
               double low = clip.stream().mapToDouble(patch::height).min().orElseThrow();
               double high = clip.stream().mapToDouble(patch::height).max().orElseThrow();
               if (high < top - 0.4 && top - low <= 96.0) {
                  return Double.NaN;
               }
            }
         }

         return terrain.getAsDouble();
      } else {
         return Double.isFinite(median) && Double.isFinite(paving) ? Math.max(median, paving) : (Double.isFinite(median) ? median : paving);
      }
   }

   public boolean blocks(RoadStructures.Part part) {
      HashSet<RoadStructures.Part> seen = new HashSet<>();

      for (RoadPavingSupport.Patch patch : this.near(part.base())) {
         if (seen.add(patch.part()) && RoadSolidOverlap.intersects(part, patch.part())) {
            if (part.pier() && part.material() == RoadStructures.Material.CONCRETE) {
               List<RoadGeometry.V> floor = part.base();
               double bottom = floor.stream().mapToDouble(RoadGeometry.V::y).min().orElseThrow();
               double bearing = this.foundation(part.a(), bottom + 1.0, part.width());
               if (Double.isFinite(bearing) && !(bottom > bearing + 1.0E-7) && !(bearing - bottom > 0.3500001)) {
                  for (RoadPavingSupport.Patch face : this.near(floor)) {
                     if (face.part() == patch.part()) {
                        List<RoadGeometry.V> overlap = RoadSurface.intersect(floor, face.top());
                        if (overlap.size() >= 3 && !(RoadSurface.area(overlap) < 1.0E-9)) {
                           for (RoadGeometry.V p : overlap) {
                              if (face.height(p) - bottom > 0.3500001) {
                                 return true;
                              }
                           }
                        }
                     }
                  }
                  continue;
               }

               return true;
            }

            return true;
         }
      }

      return false;
   }

   private static record Patch(RoadStructures.Part part, List<RoadGeometry.V> top, double high) {
      double height(RoadGeometry.V p) {
         RoadGeometry.V a = this.top.get(0);
         RoadGeometry.V b = this.top.get(1).sub(a);
         RoadGeometry.V c = this.top.get(2).sub(a);
         RoadGeometry.V q = p.sub(a);
         double det = b.x() * c.z() - b.z() * c.x();
         return a.y() + ((q.x() * c.z() - q.z() * c.x()) * b.y() + (b.x() * q.z() - b.z() * q.x()) * c.y()) / det;
      }
   }
}
