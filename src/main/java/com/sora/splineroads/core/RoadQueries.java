package com.sora.splineroads.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

public final class RoadQueries {
   private static final ThreadLocal<RoadQueries.Cache> PROJECTIONS = ThreadLocal.withInitial(() -> new RoadQueries.Cache(128, 65536));

   public static RoadQueries.Scope scope() {
      return new RoadQueries.Scope();
   }

   static RoadQueries.CacheStats cacheStats() {
      RoadQueries.Cache c = PROJECTIONS.get();
      return new RoadQueries.CacheStats(c.entries.size(), c.samples, c.builds);
   }

   private static RoadProjection index(RoadGeometry.Mesh mesh) {
      return PROJECTIONS.get().get(mesh);
   }

   public static void clearThreadCache() {
      PROJECTIONS.remove();
   }

   public static RoadQueries.Projection project(RoadGeometry.Mesh mesh, RoadGeometry.V point) {
      return index(mesh).project(point, false);
   }

   public static RoadQueries.Projection horizontal(RoadGeometry.Mesh mesh, RoadGeometry.V point) {
      return index(mesh).project(point, true);
   }

   public static boolean contains(RoadGeometry.Mesh mesh, RoadGeometry.V point, double margin, double heightTolerance) {
      if (!(point.x() < mesh.min().x() - margin)
         && !(point.x() > mesh.max().x() + margin)
         && !(point.z() < mesh.min().z() - margin)
         && !(point.z() > mesh.max().z() + margin)
         && !(point.y() < mesh.min().y() - heightTolerance)
         && !(point.y() > mesh.max().y() + heightTolerance)) {
         RoadQueries.Projection p = project(mesh, point);
         if (!mesh.closed()) {
            RoadGeometry.V forwardA = mesh.first().left().left().mul(-1.0);
            RoadGeometry.V forwardB = mesh.last().left().left().mul(-1.0);
            if (p.sample().distance() < 1.0E-7 && point.sub(mesh.first().center()).dot(forwardA) < -1.0E-7) {
               return false;
            }

            if (p.sample().distance() > mesh.length() - 1.0E-7 && point.sub(mesh.last().center()).dot(forwardB) > 1.0E-7) {
               return false;
            }
         }

         return LaneDeck.present(mesh, p.sample(), p.lateral(), margin)
            && Math.abs(p.lateral()) <= p.sample().halfWidth() + margin
            && Math.abs(point.y() - p.sample().center().y()) <= heightTolerance;
      } else {
         return false;
      }
   }

   public static boolean joins(RoadGeometry.Mesh road, RoadGeometry.Mesh other, RoadGeometry.V point) {
      boolean legacyFork = road.settings().style().ramp() && !road.settings().laneRamp() || other.settings().style().ramp() && !other.settings().laneRamp();
      boolean shared = road.first().center().distance(other.first().center()) < 0.01
         || road.first().center().distance(other.last().center()) < 0.01
         || road.last().center().distance(other.first().center()) < 0.01
         || road.last().center().distance(other.last().center()) < 0.01;
      return contains(other, point, -0.02, legacyFork && shared ? 1.05 : 0.12);
   }

   public static double ray(RoadGeometry.Mesh mesh, RoadGeometry.V origin, RoadGeometry.V direction, double limit) {
      double best = limit;

      for (int i = 1; i < mesh.samples().size(); i++) {
         RoadGeometry.Sample a = mesh.samples().get(i - 1);
         RoadGeometry.Sample b = mesh.samples().get(i);

         for (LaneDeck.Strip strip : LaneDeck.strips(mesh, a, b)) {
            best = Math.min(best, triangle(origin, direction, strip.al(), strip.ar(), strip.br()));
            best = Math.min(best, triangle(origin, direction, strip.al(), strip.br(), strip.bl()));
         }
      }

      return best < limit ? best : Double.POSITIVE_INFINITY;
   }

   private static double triangle(RoadGeometry.V o, RoadGeometry.V d, RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c) {
      RoadGeometry.V e = b.sub(a);
      RoadGeometry.V f = c.sub(a);
      RoadGeometry.V p = cross(d, f);
      double det = e.dot(p);
      if (Math.abs(det) < 1.0E-10) {
         return Double.POSITIVE_INFINITY;
      } else {
         RoadGeometry.V t = o.sub(a);
         double u = t.dot(p) / det;
         if (!(u < 0.0) && !(u > 1.0)) {
            RoadGeometry.V q = cross(t, e);
            double v = d.dot(q) / det;
            if (!(v < 0.0) && !(u + v > 1.0)) {
               double hit = f.dot(q) / det;
               return hit >= 0.0 ? hit : Double.POSITIVE_INFINITY;
            } else {
               return Double.POSITIVE_INFINITY;
            }
         } else {
            return Double.POSITIVE_INFINITY;
         }
      }
   }

   private static RoadGeometry.V cross(RoadGeometry.V a, RoadGeometry.V b) {
      return new RoadGeometry.V(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
   }

   private RoadQueries() {
   }

   private static final class Cache {
      final LinkedHashMap<RoadQueries.Key, RoadProjection> entries = new LinkedHashMap<>(64, 0.75F, true);
      final int capacity;
      final int sampleLimit;
      int samples;
      long builds;

      Cache(int capacity, int sampleLimit) {
         this.capacity = capacity;
         this.sampleLimit = sampleLimit;
      }

      RoadProjection get(RoadGeometry.Mesh mesh) {
         RoadQueries.Key key = new RoadQueries.Key(mesh);
         RoadProjection old = this.entries.get(key);
         if (old != null) {
            return old;
         } else {
            RoadProjection value = new RoadProjection(mesh);
            this.builds++;
            int weight = mesh.samples().size();
            if (weight > this.sampleLimit) {
               return value;
            } else {
               Iterator<Entry<RoadQueries.Key, RoadProjection>> it = this.entries.entrySet().iterator();

               while (it.hasNext() && (this.entries.size() >= this.capacity || this.samples + weight > this.sampleLimit)) {
                  this.samples = this.samples - it.next().getKey().mesh.samples().size();
                  it.remove();
               }

               this.entries.put(key, value);
               this.samples += weight;
               return value;
            }
         }
      }
   }

   static record CacheStats(int entries, int samples, long builds) {
   }

   private static record Key(RoadGeometry.Mesh mesh) {
      @Override
      public int hashCode() {
         return System.identityHashCode(this.mesh);
      }

      @Override
      public boolean equals(Object other) {
         if (other instanceof RoadQueries.Key k && this.mesh == k.mesh) {
            return true;
         }

         return false;
      }
   }

   public static record Projection(RoadGeometry.Sample sample, RoadGeometry.V tangent, double lateral, double horizontalDistance) {
   }

   public static final class Scope implements AutoCloseable {
      private final RoadQueries.Cache previous = RoadQueries.PROJECTIONS.get();
      private final RoadQueries.Cache current = new RoadQueries.Cache(1024, 262144);

      private Scope() {
         RoadQueries.PROJECTIONS.set(this.current);
      }

      @Override
      public void close() {
         if (RoadQueries.PROJECTIONS.get() == this.current) {
            RoadQueries.PROJECTIONS.set(this.previous);
         }
      }
   }
}
