package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public final class RoadTunnelSpace {
   private static final double EPS = 1.0E-8;
   private static final WeakIdentityCache<RoadGeometry.Mesh, RoadTunnelSpace.Prepared> CACHE = new WeakIdentityCache<>(48, 350000L, p -> p.references);

   private static RoadTunnelSpace.Prepared prepared(RoadGeometry.Mesh mesh) {
      return CACHE.get(mesh, RoadTunnelSpace.Prepared::new);
   }

   public static void clearPreparedCache() {
      CACHE.clear();
   }

   public static double ceiling(RoadGeometry.Mesh mesh, int x, int z) {
      return prepared(mesh).column(x, z).top;
   }

   public static boolean intersects(RoadGeometry.Mesh mesh, int x, int y, int z, double height) {
      if (mesh.settings().structure() == RoadGeometry.Structure.TUNNEL && !(height <= 0.0)) {
         for (List<RoadTunnelSpace.Vertex> polygon : prepared(mesh).column(x, z).polygons) {
            List<RoadTunnelSpace.Vertex> p = clip(polygon, a -> a.roof - (double)y - 1.0E-8);
            p = clip(p, a -> (double)y + height - a.floor - 1.0E-8);
            if (area(p) > 1.0E-8) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private static RoadTunnelSpace.Vertex profile(RoadGeometry.Sample at, double lateral, double height) {
      RoadGeometry.V p = at.at(lateral, 0.0);
      return new RoadTunnelSpace.Vertex(p.x(), p.z(), p.y(), p.y() + height);
   }

   private static RoadTunnelSpace.Vertex vertex(RoadGeometry.Sample sample, RoadInfrastructure.Config c, double u) {
      RoadGeometry.V p = sample.at((sample.halfWidth() + 1.55) * u, 0.0);
      return new RoadTunnelSpace.Vertex(p.x(), p.z(), p.y(), p.y() + RoadInfrastructure.roofHeight(c, sample.halfWidth(), u));
   }

   private static List<RoadTunnelSpace.Vertex> clip(List<RoadTunnelSpace.Vertex> polygon, RoadTunnelSpace.Distance distance) {
      if (polygon.isEmpty()) {
         return polygon;
      } else {
         ArrayList<RoadTunnelSpace.Vertex> out = new ArrayList<>();
         RoadTunnelSpace.Vertex prev = polygon.get(polygon.size() - 1);
         double before = distance.of(prev);

         for (RoadTunnelSpace.Vertex now : polygon) {
            double after = distance.of(now);
            if (before >= 0.0 != after >= 0.0) {
               out.add(prev.mix(now, before / (before - after)));
            }

            if (after >= 0.0) {
               out.add(now);
            }

            prev = now;
            before = after;
         }

         return out;
      }
   }

   private static List<RoadTunnelSpace.Vertex> clipFootprint(List<RoadTunnelSpace.Vertex> p, List<RoadGeometry.V> footprint) {
      double orient = 0.0;
      RoadGeometry.V origin = footprint.get(0);

      for (int i = 1; i + 1 < footprint.size(); i++) {
         orient += cross(
            footprint.get(i).x() - origin.x(), footprint.get(i).z() - origin.z(), footprint.get(i + 1).x() - origin.x(), footprint.get(i + 1).z() - origin.z()
         );
      }

      double sign = Math.signum(orient);

      for (int i = 0; i < footprint.size(); i++) {
         RoadGeometry.V a = footprint.get(i);
         RoadGeometry.V b = footprint.get((i + 1) % footprint.size());
         p = clip(p, v -> sign * cross(b.x() - a.x(), b.z() - a.z(), v.x - a.x(), v.z - a.z()));
      }

      return p;
   }

   private static double area(List<RoadTunnelSpace.Vertex> p) {
      if (p.size() < 3) {
         return 0.0;
      } else {
         RoadTunnelSpace.Vertex a = p.get(0);
         double sum = 0.0;

         for (int i = 1; i + 1 < p.size(); i++) {
            sum += cross(p.get(i).x - a.x, p.get(i).z - a.z, p.get(i + 1).x - a.x, p.get(i + 1).z - a.z);
         }

         return Math.abs(sum) * 0.5;
      }
   }

   private static double cross(double ax, double az, double bx, double bz) {
      return ax * bz - az * bx;
   }

   private static int tile(double x) {
      return (int)Math.floor(x / 8.0);
   }

   private static long key(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   private RoadTunnelSpace() {
   }

   private static record Column(List<List<RoadTunnelSpace.Vertex>> polygons, double top) {
   }

   private interface Distance {
      double of(RoadTunnelSpace.Vertex var1);
   }

   private static record Patch(List<RoadTunnelSpace.Vertex> polygon, double minX, double minZ, double maxX, double maxZ) {
   }

   private static final class Prepared {
      final Map<Long, List<RoadTunnelSpace.Patch>> grid = new HashMap<>();
      final Map<Long, RoadTunnelSpace.Column> columns = new LinkedHashMap<Long, RoadTunnelSpace.Column>(64, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Entry<Long, RoadTunnelSpace.Column> e) {
            return this.size() > 2048;
         }
      };
      int references;

      Prepared(RoadGeometry.Mesh mesh) {
         RoadInfrastructure.Config config = mesh.settings().options().infrastructure();

         for (double d = 0.0; d < mesh.length() - 1.0E-6; d += 2.0) {
            RoadGeometry.Sample a = RoadStructures.sample(mesh, d);
            RoadGeometry.Sample b = RoadStructures.sample(mesh, Math.min(mesh.length(), d + 2.0));
            List<RoadGeometry.V> footprint = List.of(a.at(a.halfWidth(), 0.0), a.at(-a.halfWidth(), 0.0), b.at(-b.halfWidth(), 0.0), b.at(b.halfWidth(), 0.0));
            int bands = RoadInfrastructure.roofBands(a.halfWidth(), b.halfWidth());

            for (int i = 0; i < bands; i++) {
               double u = -1.0 + 2.0 * (double)i / (double)bands;
               double v = -1.0 + 2.0 * (double)(i + 1) / (double)bands;
               RoadTunnelSpace.Vertex ah = RoadTunnelSpace.vertex(a, config, v);
               RoadTunnelSpace.Vertex al = RoadTunnelSpace.vertex(a, config, u);
               RoadTunnelSpace.Vertex bl = RoadTunnelSpace.vertex(b, config, u);
               RoadTunnelSpace.Vertex bh = RoadTunnelSpace.vertex(b, config, v);

               for (List<RoadTunnelSpace.Vertex> tri : List.of(List.of(ah, al, bl), List.of(ah, bl, bh))) {
                  List<RoadTunnelSpace.Vertex> poly = RoadTunnelSpace.clipFootprint(tri, footprint);
                  if (!(RoadTunnelSpace.area(poly) < 1.0E-8)) {
                     this.add(poly);
                  }
               }
            }
         }
      }

      Prepared(RoadGeometry.Mesh mesh, List<RoadAutoTunnels.RoofRegion> regions) {
         for (RoadAutoTunnels.RoofRegion r : regions) {
            if (!(r.to() > mesh.length() + 1.0E-6)) {
               RoadGeometry.Sample a = RoadStructures.sample(mesh, r.from());
               RoadGeometry.Sample b = RoadStructures.sample(mesh, r.to());
               RoadTunnelSpace.Vertex al = RoadTunnelSpace.profile(a, a.halfWidth(), r.first());
               RoadTunnelSpace.Vertex ar = RoadTunnelSpace.profile(a, -a.halfWidth(), r.first());
               RoadTunnelSpace.Vertex bl = RoadTunnelSpace.profile(b, b.halfWidth(), r.last());
               RoadTunnelSpace.Vertex br = RoadTunnelSpace.profile(b, -b.halfWidth(), r.last());
               this.add(List.of(al, ar, br));
               this.add(List.of(al, br, bl));
            }
         }
      }

      private void add(List<RoadTunnelSpace.Vertex> poly) {
         RoadTunnelSpace.Patch patch = new RoadTunnelSpace.Patch(
            List.copyOf(poly),
            poly.stream().mapToDouble(RoadTunnelSpace.Vertex::x).min().orElseThrow(),
            poly.stream().mapToDouble(RoadTunnelSpace.Vertex::z).min().orElseThrow(),
            poly.stream().mapToDouble(RoadTunnelSpace.Vertex::x).max().orElseThrow(),
            poly.stream().mapToDouble(RoadTunnelSpace.Vertex::z).max().orElseThrow()
         );

         for (int x = RoadTunnelSpace.tile(patch.minX); x <= RoadTunnelSpace.tile(patch.maxX); x++) {
            for (int z = RoadTunnelSpace.tile(patch.minZ); z <= RoadTunnelSpace.tile(patch.maxZ); z++) {
               this.grid.computeIfAbsent(RoadTunnelSpace.key(x, z), k -> new ArrayList<>()).add(patch);
               this.references++;
            }
         }
      }

      synchronized RoadTunnelSpace.Column column(int x, int z) {
         long k = RoadTunnelSpace.key(x, z);
         RoadTunnelSpace.Column old = this.columns.get(k);
         if (old != null) {
            return old;
         } else {
            ArrayList<List<RoadTunnelSpace.Vertex>> polygons = new ArrayList<>();
            double top = Double.NEGATIVE_INFINITY;

            for (RoadTunnelSpace.Patch patch : this.grid
               .getOrDefault(RoadTunnelSpace.key(RoadTunnelSpace.tile((double)x), RoadTunnelSpace.tile((double)z)), List.of())) {
               if (!(patch.minX >= (double)(x + 1) - 1.0E-8)
                  && !(patch.maxX <= (double)x + 1.0E-8)
                  && !(patch.minZ >= (double)(z + 1) - 1.0E-8)
                  && !(patch.maxZ <= (double)z + 1.0E-8)) {
                  List<RoadTunnelSpace.Vertex> p = RoadTunnelSpace.clip(patch.polygon, ax -> ax.x - (double)x);
                  p = RoadTunnelSpace.clip(p, ax -> (double)(x + 1) - ax.x);
                  p = RoadTunnelSpace.clip(p, ax -> ax.z - (double)z);
                  p = RoadTunnelSpace.clip(p, ax -> (double)(z + 1) - ax.z);
                  if (!(RoadTunnelSpace.area(p) < 1.0E-8)) {
                     polygons.add(List.copyOf(p));

                     for (RoadTunnelSpace.Vertex a : p) {
                        top = Math.max(top, a.roof);
                     }
                  }
               }
            }

            RoadTunnelSpace.Column result = new RoadTunnelSpace.Column(List.copyOf(polygons), top);
            this.columns.put(k, result);
            return result;
         }
      }
   }

   public static final class Profiled {
      private final RoadTunnelSpace.Prepared prepared;

      public Profiled(RoadGeometry.Mesh mesh, List<RoadAutoTunnels.RoofRegion> regions) {
         this.prepared = new RoadTunnelSpace.Prepared(mesh, regions);
      }

      public double ceiling(int x, int z) {
         return this.prepared.column(x, z).top;
      }

      public double floor(int x, int z) {
         return this.prepared
            .column(x, z)
            .polygons
            .stream()
            .flatMap(Collection::stream)
            .mapToDouble(RoadTunnelSpace.Vertex::floor)
            .min()
            .orElse(Double.POSITIVE_INFINITY);
      }

      public boolean intersects(int x, int y, int z, double height) {
         for (List<RoadTunnelSpace.Vertex> polygon : this.prepared.column(x, z).polygons) {
            List<RoadTunnelSpace.Vertex> p = RoadTunnelSpace.clip(polygon, a -> a.roof - (double)y - 1.0E-8);
            p = RoadTunnelSpace.clip(p, a -> (double)y + height - a.floor - 1.0E-8);
            if (RoadTunnelSpace.area(p) > 1.0E-8) {
               return true;
            }
         }

         return false;
      }
   }

   private static record Vertex(double x, double z, double floor, double roof) {
      RoadTunnelSpace.Vertex mix(RoadTunnelSpace.Vertex b, double t) {
         return new RoadTunnelSpace.Vertex(
            this.x + (b.x - this.x) * t, this.z + (b.z - this.z) * t, this.floor + (b.floor - this.floor) * t, this.roof + (b.roof - this.roof) * t
         );
      }
   }
}
