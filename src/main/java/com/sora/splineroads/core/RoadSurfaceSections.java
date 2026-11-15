package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RoadSurfaceSections {
   private static final double EPS = 1.0E-8;

   private RoadSurfaceSections() {
   }

   public static RoadTerrainMesh.Section owner(RoadSurface.Face face) {
      double x = 0.0;
      double y = 0.0;
      double z = 0.0;

      for (RoadGeometry.V p : face.points()) {
         x += p.x();
         y += p.y();
         z += p.z();
      }

      double n = (double)face.points().size();
      return new RoadTerrainMesh.Section(floor(x / n), floor(y / n - 1.0E-8), floor(z / n));
   }

   private static int floor(double x) {
      return (int)Math.floor(x / 16.0);
   }

   public static List<RoadSurface.Face> split(List<RoadSurface.Face> faces) {
      List<RoadSurface.Face> result = new ArrayList<>();

      for (RoadSurface.Face face : faces) {
         double minX = Double.POSITIVE_INFINITY;
         double minY = minX;
         double minZ = minX;
         double maxX = -minX;
         double maxY = -minX;
         double maxZ = -minX;

         for (RoadGeometry.V p : face.points()) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
         }

         int x0 = floor(minX);
         int x1 = floor(maxX - 1.0E-8);
         int y0 = floor(minY - 1.0E-8);
         int y1 = floor(maxY - 1.0E-8);
         int z0 = floor(minZ);
         int z1 = floor(maxZ - 1.0E-8);
         x1 = Math.max(x0, x1);
         z1 = Math.max(z0, z1);
         if (x0 == x1 && y0 == y1 && z0 == z1) {
            result.add(face);
         } else {
            for (int i = 1; i + 1 < face.points().size(); i++) {
               List<RoadSurfaceSections.Vertex> triangle = List.of(vertex(face, 0), vertex(face, i), vertex(face, i + 1));

               for (int x = x0; x <= x1; x++) {
                  for (int y = y0; y <= y1; y++) {
                     for (int z = z0; z <= z1; z++) {
                        List<RoadSurfaceSections.Vertex> polygon = clip(triangle, 0, (double)x * 16.0, true);
                        polygon = clip(polygon, 0, (double)(x + 1) * 16.0, false);
                        polygon = clip(polygon, 1, (double)y * 16.0 + 1.0E-8, true);
                        polygon = clip(polygon, 1, (double)(y + 1) * 16.0 + 1.0E-8, false);
                        polygon = clip(polygon, 2, (double)z * 16.0, true);
                        polygon = clip(polygon, 2, (double)(z + 1) * 16.0, false);
                        if (area(polygon) > 1.0E-12) {
                           result.add(
                              new RoadSurface.Face(
                                 polygon.stream().map(RoadSurfaceSections.Vertex::p).toList(),
                                 face.color(),
                                 face.emissive(),
                                 face.texture(),
                                 face.uv().isEmpty() ? List.of() : polygon.stream().map(RoadSurfaceSections.Vertex::uv).toList()
                              )
                           );
                        }
                     }
                  }
               }
            }
         }
      }

      result.sort(Comparator.<RoadSurface.Face>comparingInt(f -> owner(f).x()).thenComparingInt(f -> owner(f).y()).thenComparingInt(f -> owner(f).z()));
      return List.copyOf(result);
   }

   private static RoadSurfaceSections.Vertex vertex(RoadSurface.Face face, int i) {
      return new RoadSurfaceSections.Vertex(face.points().get(i), face.uv().isEmpty() ? null : face.uv().get(i));
   }

   private static double coordinate(RoadGeometry.V p, int axis) {
      return axis == 0 ? p.x() : (axis == 1 ? p.y() : p.z());
   }

   private static List<RoadSurfaceSections.Vertex> clip(List<RoadSurfaceSections.Vertex> input, int axis, double plane, boolean above) {
      if (input.isEmpty()) {
         return input;
      } else {
         List<RoadSurfaceSections.Vertex> out = new ArrayList<>();
         RoadSurfaceSections.Vertex a = input.get(input.size() - 1);
         double da = coordinate(a.p, axis) - plane;

         for (RoadSurfaceSections.Vertex b : input) {
            double db = coordinate(b.p, axis) - plane;
            boolean ina = above ? da >= 0.0 : da <= 0.0;
            boolean inb = above ? db >= 0.0 : db <= 0.0;
            if (ina != inb) {
               out.add(a.mix(b, da / (da - db)));
            }

            if (inb) {
               out.add(b);
            }

            a = b;
            da = db;
         }

         return out;
      }
   }

   private static double area(List<RoadSurfaceSections.Vertex> p) {
      double sum = 0.0;
      if (p.size() < 3) {
         return 0.0;
      } else {
         RoadGeometry.V origin = p.get(0).p;

         for (int i = 1; i + 1 < p.size(); i++) {
            RoadGeometry.V a = p.get(i).p.sub(origin);
            RoadGeometry.V b = p.get(i + 1).p.sub(origin);
            double x = a.y() * b.z() - a.z() * b.y();
            double y = a.z() * b.x() - a.x() * b.z();
            double z = a.x() * b.y() - a.y() * b.x();
            sum += Math.sqrt(x * x + y * y + z * z);
         }

         return sum * 0.5;
      }
   }

   private static record Vertex(RoadGeometry.V p, RoadSurface.UV uv) {
      RoadSurfaceSections.Vertex mix(RoadSurfaceSections.Vertex b, double t) {
         return new RoadSurfaceSections.Vertex(
            this.p.mul(1.0 - t).add(b.p.mul(t)),
            this.uv == null ? null : new RoadSurface.UV(this.uv.u() + (b.uv.u() - this.uv.u()) * t, this.uv.v() + (b.uv.v() - this.uv.v()) * t)
         );
      }
   }
}
