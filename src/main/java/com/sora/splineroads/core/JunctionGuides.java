package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

final class JunctionGuides {
   static void paint(List<RoadSurface.Face> out, List<JunctionGuides.Guide> input) {
      LinkedHashMap<String, List<JunctionGuides.Guide>> groups = new LinkedHashMap<>();

      for (JunctionGuides.Guide g : input) {
         groups.computeIfAbsent(g.exit() + ":" + g.divider(), k -> new ArrayList<>()).add(g);
      }

      for (List<JunctionGuides.Guide> group : groups.values()) {
         JunctionGuides.Guide primary = group.stream().min(Comparator.comparingDouble(gx -> length(gx.path()))).orElseThrow();
         List<RoadGeometry.V> trunk = primary.path();
         JunctionPaint.path(out, trunk, 0.14, true, 15593186);

         for (JunctionGuides.Guide g : group) {
            if (g != primary) {
               List<RoadGeometry.V> path = g.path();
               int join = trunk.size() - 1;

               for (double tail = 0.0; join > 1 && tail < Math.min(16.0, length(trunk) * 0.35); join--) {
                  tail += trunk.get(join).distance(trunk.get(join - 1));
               }

               RoadGeometry.V end = trunk.get(join);
               RoadGeometry.V tangent = trunk.get(join + 1).sub(end).horizontalUnit();
               int cut = 1;
               double nearest = Double.POSITIVE_INFINITY;

               for (int i = 1; i < path.size() - 2; i++) {
                  double d = path.get(i).distance(end);
                  if (d < nearest) {
                     nearest = d;
                     cut = i;
                  }
               }

               cut = Math.max(1, cut - 3);
               ArrayList<RoadGeometry.V> branch = new ArrayList<>(path.subList(0, cut + 1));
               RoadGeometry.V a = path.get(cut);
               RoadGeometry.V forward = a.sub(path.get(cut - 1)).horizontalUnit();
               double handle = Math.min(8.0, a.distance(end) * 0.4);
               RoadGeometry.V b = a.add(forward.mul(handle));
               RoadGeometry.V c = end.sub(tangent.mul(handle));
               int steps = Math.max(8, (int)Math.ceil(a.distance(end) / 0.3));

               for (int ix = 1; ix <= steps; ix++) {
                  double t = (double)ix / (double)steps;
                  double u = 1.0 - t;
                  branch.add(a.mul(u * u * u).add(b.mul(3.0 * u * u * t)).add(c.mul(3.0 * u * t * t)).add(end.mul(t * t * t)));
               }

               double at = length(trunk.subList(0, join + 1));
               JunctionPaint.path(out, branch, 0.14, true, 15593186, at - length(branch));
            }
         }
      }
   }

   private static double length(List<RoadGeometry.V> path) {
      double d = 0.0;

      for (int i = 1; i < path.size(); i++) {
         d += path.get(i).distance(path.get(i - 1));
      }

      return d;
   }

   private JunctionGuides() {
   }

   static record Guide(int exit, int divider, List<RoadGeometry.V> path) {
   }
}
