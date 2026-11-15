package com.sora.splineroads.core;

public final class RoadLighting {
   public static RoadGeometry.V normal(RoadSurface.Face f) {
      RoadGeometry.V origin = f.points().get(0);

      for (int i = 1; i + 1 < f.points().size(); i++) {
         RoadGeometry.V a = f.points().get(i).sub(origin);
         RoadGeometry.V b = f.points().get(i + 1).sub(origin);
         RoadGeometry.V n = new RoadGeometry.V(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
         double length = Math.sqrt(n.dot(n));
         if (length > 1.0E-10) {
            return n.mul(1.0 / length);
         }
      }

      return new RoadGeometry.V(0.0, 1.0, 0.0);
   }

   public static int sample(RoadSurface.Face face, RoadGeometry.V vertex, RoadLighting.Access access) {
      return sample(face, vertex, access, false);
   }

   public static int sample(RoadSurface.Face face, RoadGeometry.V vertex, RoadLighting.Access access, boolean forceUp) {
      if (face.emissive()) {
         return 15728880;
      } else {
         RoadGeometry.V n = normal(face);
         RoadGeometry.V center = new RoadGeometry.V(0.0, 0.0, 0.0);
         if (forceUp && n.y() < 0.0) {
            n = n.mul(-1.0);
         }

         boolean walk = face.texture().name().startsWith("WALK_");
         if (face.texture() == RoadSurface.Texture.CONCRETE || walk) {
            int stable = surfaceLight(vertex, n, access, walk ? 1.05 : 0.65);
            if (stable >= 0) {
               return stable;
            }
         }

         for (RoadGeometry.V v : face.points()) {
            center = center.add(v);
         }

         center = center.mul(1.0 / (double)face.points().size());
         RoadGeometry.V inward = center.sub(vertex);
         double length = Math.sqrt(inward.dot(inward));
         RoadGeometry.V direction = length < 1.0E-8 ? new RoadGeometry.V(0.0, 0.0, 0.0) : inward.mul(1.0 / length);
         int nearby = -1;

         for (double inset : new double[]{0.2, 0.7, 1.2}) {
            boolean visible = false;

            for (double outward : new double[]{0.12, 0.65, 1.15}) {
               RoadGeometry.V p = vertex.add(direction.mul(Math.min(inset, length))).add(n.mul(outward));
               int x = (int)Math.floor(p.x());
               int y = (int)Math.floor(p.y());
               int z = (int)Math.floor(p.z());
               if (access.opaque(x, y, z)) {
                  if (visible) {
                     break;
                  }
               } else {
                  visible = true;
                  int light = access.packed(x, y, z);
                  nearby = nearby < 0 ? light : merge(nearby, light);
               }
            }
         }

         if (nearby >= 0) {
            return nearby;
         } else {
            for (double outwardx : new double[]{0.12, 0.65, 1.15}) {
               RoadGeometry.V point = center.add(n.mul(outwardx));
               int x = (int)Math.floor(point.x());
               int y = (int)Math.floor(point.y());
               int z = (int)Math.floor(point.z());
               if (!access.opaque(x, y, z)) {
                  return access.packed(x, y, z);
               }
            }

            if (n.y() > 0.7) {
               for (double reach : new double[]{0.35, 0.75, 1.25}) {
                  int best = -1;

                  for (int dx = -1; dx <= 1; dx++) {
                     for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dz != 0) {
                           RoadGeometry.V q = vertex.add(n.mul(0.2)).add(new RoadGeometry.V((double)dx * reach, 0.0, (double)dz * reach));
                           int x = (int)Math.floor(q.x());
                           int y = (int)Math.floor(q.y());
                           int z = (int)Math.floor(q.z());
                           if (!access.opaque(x, y, z)) {
                              int light = access.packed(x, y, z);
                              best = best < 0 ? light : Math.max(best & 65535, light & 65535) | Math.max(best >>> 16, light >>> 16) << 16;
                           }
                        }
                     }
                  }

                  if (best >= 0) {
                     return best;
                  }
               }
            }

            RoadGeometry.V p = vertex.add(n.mul(0.12));
            return access.packed((int)Math.floor(p.x()), (int)Math.floor(p.y()), (int)Math.floor(p.z()));
         }
      }
   }

   private static int surfaceLight(RoadGeometry.V vertex, RoadGeometry.V n, RoadLighting.Access access, double reach) {
      RoadGeometry.V tangent = Math.abs(n.y()) > 0.9 ? new RoadGeometry.V(1.0, 0.0, 0.0) : new RoadGeometry.V(n.z(), 0.0, -n.x()).horizontalUnit();
      RoadGeometry.V across = new RoadGeometry.V(
         n.y() * tangent.z() - n.z() * tangent.y(), n.z() * tangent.x() - n.x() * tangent.z(), n.x() * tangent.y() - n.y() * tangent.x()
      );
      int best = -1;

      for (double a : new double[]{-reach, 0.0, reach}) {
         for (double b : new double[]{-reach, 0.0, reach}) {
            boolean visible = false;

            for (double outward : new double[]{0.12, 0.65, 1.15}) {
               RoadGeometry.V p = vertex.add(tangent.mul(a)).add(across.mul(b)).add(n.mul(outward));
               int x = (int)Math.floor(p.x());
               int y = (int)Math.floor(p.y());
               int z = (int)Math.floor(p.z());
               if (access.opaque(x, y, z)) {
                  if (visible) {
                     break;
                  }
               } else {
                  visible = true;
                  int light = access.packed(x, y, z);
                  best = best < 0 ? light : merge(best, light);
               }
            }
         }
      }

      return best;
   }

   private static int merge(int a, int b) {
      return Math.max(a & 65535, b & 65535) | Math.max(a >>> 16, b >>> 16) << 16;
   }

   private RoadLighting() {
   }

   public interface Access {
      boolean opaque(int var1, int var2, int var3);

      int packed(int var1, int var2, int var3);
   }
}
