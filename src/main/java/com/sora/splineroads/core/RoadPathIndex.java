package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RoadPathIndex {
   private static final WeakIdentityCache<RoadGeometry.Mesh, RoadBoundsIndex> SEGMENTS = new WeakIdentityCache<>(64, 200000L, RoadBoundsIndex::bucketCount);
   private static final WeakIdentityCache<RoadGeometry.Mesh, RoadBoundsIndex> POINTS = new WeakIdentityCache<>(64, 200000L, RoadBoundsIndex::bucketCount);

   public static RoadBoundsIndex segments(RoadGeometry.Mesh mesh) {
      return SEGMENTS.get(mesh, m -> {
         ArrayList<RoadBoundsIndex.Bounds> bounds = new ArrayList<>();
         List<RoadGeometry.Sample> points = m.samples();

         for (int i = 1; i < points.size(); i++) {
            bounds.add(segment(points.get(i - 1).center(), points.get(i).center()));
         }

         return new RoadBoundsIndex(bounds, 16);
      });
   }

   public static RoadBoundsIndex.Bounds segment(RoadGeometry.V a, RoadGeometry.V b) {
      return new RoadBoundsIndex.Bounds(Math.min(a.x(), b.x()), Math.min(a.z(), b.z()), Math.max(a.x(), b.x()), Math.max(a.z(), b.z()));
   }

   public static RoadBoundsIndex points(RoadGeometry.Mesh mesh) {
      return POINTS.get(mesh, RoadPathIndex::pointIndex);
   }

   private static RoadBoundsIndex pointIndex(RoadGeometry.Mesh mesh) {
      ArrayList<RoadBoundsIndex.Bounds> bounds = new ArrayList<>();

      for (int i = 0; i < mesh.samples().size(); i += 3) {
         RoadGeometry.V p = mesh.samples().get(i).center();
         bounds.add(segment(p, p));
      }

      return new RoadBoundsIndex(bounds, 16);
   }

   private RoadPathIndex() {
   }
}
