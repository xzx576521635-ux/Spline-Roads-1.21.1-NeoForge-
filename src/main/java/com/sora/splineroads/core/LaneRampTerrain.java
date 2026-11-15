package com.sora.splineroads.core;

public final class LaneRampTerrain {
   public static boolean allowed(RoadGeometry.Mesh mesh, LanePoints.Options options, RoadStructures.Ground ground) {
      return ground == null || options.allowTunnel() || RoadAutoTunnels.below(mesh.first(), ground) || RoadAutoTunnels.below(mesh.last(), ground);
   }

   public static double[] floors(RoadGeometry.Mesh mesh, LanePoints.Options options, RoadStructures.Ground ground) {
      if (allowed(mesh, options, ground)) {
         return null;
      } else {
         double[] sampled = new double[mesh.samples().size()];

         for (int i = 0; i < sampled.length; i++) {
            RoadPlanningBudget.check();
            sampled[i] = floor(mesh.samples().get(i), ground);
         }

         double[] floors = (double[])sampled.clone();

         for (int i = 1; i < floors.length; i++) {
            RoadGeometry.Sample a = mesh.samples().get(i - 1);
            RoadGeometry.Sample b = mesh.samples().get(i);
            double bound = Math.max(Math.max(sampled[i - 1], sampled[i]), floor(RoadStructures.sample(mesh, (a.distance() + b.distance()) / 2.0), ground));
            floors[i - 1] = Math.max(floors[i - 1], bound);
            floors[i] = Math.max(floors[i], bound);
         }

         return floors;
      }
   }

   private static double floor(RoadGeometry.Sample at, RoadStructures.Ground ground) {
      double floor = Double.NEGATIVE_INFINITY;

      for (double u : new double[]{-0.8, 0.0, 0.8}) {
         RoadGeometry.V p = at.at(u * at.halfWidth(), 0.0);
         double top = ground.surface(p.x(), p.z(), p.y());
         if (Double.isFinite(top)) {
            floor = Math.max(floor, top - (p.y() - at.center().y()));
         }
      }

      return floor;
   }

   public static boolean clear(RoadGeometry.Mesh mesh, double[] floors) {
      if (floors == null) {
         return true;
      } else {
         for (int i = 0; i < floors.length; i++) {
            if (mesh.samples().get(i).center().y() < floors[i] - 1.0E-5) {
               return false;
            }
         }

         return true;
      }
   }

   public static void validate(RoadGeometry.Mesh mesh, LanePoints.Options options, RoadStructures.Ground ground) {
      if (!clear(mesh, floors(mesh, options, ground))) {
         throw new IllegalArgumentException("允许隧道已关闭：候选匝道进入地面以下；请开启允许隧道或调整接头/避障关系（地下接头不受此限制）");
      }
   }

   private LaneRampTerrain() {
   }
}
