package com.sora.splineroads.core;

import java.util.HashSet;
import java.util.Set;

public final class RoadCoverage {
   public static Set<Long> chunks(RoadGeometry.Mesh mesh, double margin) {
      if (Double.isFinite(margin) && !(margin < 0.0) && !(margin > 16.0)) {
         Set<Long> result = new HashSet<>();
         RoadGeometry.Sample previous = mesh.first();

         for (RoadGeometry.Sample sample : mesh.samples()) {
            RoadGeometry.V a = previous.at(-previous.halfWidth(), 0.0);
            RoadGeometry.V b = previous.at(previous.halfWidth(), 0.0);
            RoadGeometry.V c = sample.at(-sample.halfWidth(), 0.0);
            RoadGeometry.V d = sample.at(sample.halfWidth(), 0.0);
            double minX = Math.min(Math.min(a.x(), b.x()), Math.min(c.x(), d.x())) - margin;
            double maxX = Math.max(Math.max(a.x(), b.x()), Math.max(c.x(), d.x())) + margin;
            double minZ = Math.min(Math.min(a.z(), b.z()), Math.min(c.z(), d.z())) - margin;
            double maxZ = Math.max(Math.max(a.z(), b.z()), Math.max(c.z(), d.z())) + margin;

            for (int x = (int)Math.floor(minX) >> 4; x <= (int)Math.floor(maxX) >> 4; x++) {
               for (int z = (int)Math.floor(minZ) >> 4; z <= (int)Math.floor(maxZ) >> 4; z++) {
                  result.add((long)x & 4294967295L | (long)z << 32);
               }
            }

            previous = sample;
         }

         return result;
      } else {
         throw new IllegalArgumentException("无效的道路区块边距");
      }
   }

   private RoadCoverage() {
   }
}
