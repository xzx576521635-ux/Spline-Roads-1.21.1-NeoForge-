package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class JunctionSlipWalks {
   static boolean affected(JunctionSpec s, List<RoadGeometry.Mesh> approaches, List<JunctionSlips.Slip> slips, RoadStructures.Part p) {
      RoadGeometry.V at = p.a().add(p.b()).mul(0.5);

      for (JunctionSlips.Slip slip : slips) {
         RoadQueries.Projection a = RoadQueries.horizontal(approaches.get(slip.from()), at);
         RoadQueries.Projection b = RoadQueries.horizontal(approaches.get(slip.to()), at);
         if (a.lateral() > s.arms().get(slip.from()).motorEdge(true, false)
            && b.lateral() < s.arms().get(slip.to()).motorEdge(false, false) + s.arms().get(slip.to()).laneWidth() + 1.0) {
            return true;
         }
      }

      return false;
   }

   static void add(
      JunctionSpec s,
      List<RoadGeometry.Mesh> approaches,
      JunctionSlipProfiles profiles,
      JunctionSlips.Slip slip,
      RoadSidewalks.Config walk,
      RoadGeometry.V inner,
      RoadGeometry.V outer,
      List<RoadStructures.Part> parts,
      List<RoadStructures.Part> island,
      boolean exact,
      boolean smoothEdges
   ) {
      double offset = RoadStreetscape.tactileOffset((double)walk.width());
      ArrayList<RoadGeometry.V> route = new ArrayList<>();
      RoadGeometry.Mesh a = approaches.get(slip.from());
      RoadGeometry.Mesh b = approaches.get(slip.to());
      double startWidth = exact ? JunctionSlips.walkSpan(s.arms().get(slip.from())) : (double)walk.width();
      double endWidth = exact ? JunctionSlips.walkSpan(s.arms().get(slip.to())) : (double)walk.width();
      if (!exact || startWidth >= 2.0) {
         for (RoadGeometry.Sample p : a.samples()) {
            if (p.distance() < slip.departure()) {
               route.add(profiles.map(slip.from(), p.at(p.halfWidth() + RoadStreetscape.tactileOffset(startWidth), 0.0)));
            }
         }
      }

      for (RoadGeometry.Sample px : slip.mesh().samples()) {
         double width = exact ? JunctionSlips.walkWidth(s, slip, px.distance(), smoothEdges) : (double)walk.width();
         if (!exact || width >= 2.0) {
            route.add(px.at(px.halfWidth() + RoadStreetscape.tactileOffset(width), 0.0));
         }
      }

      ArrayList<RoadGeometry.V> tail = new ArrayList<>();
      if (!exact || endWidth >= 2.0) {
         for (RoadGeometry.Sample pxx : b.samples()) {
            if (pxx.distance() < slip.arrival()) {
               tail.add(profiles.map(slip.to(), pxx.at(-pxx.halfWidth() - RoadStreetscape.tactileOffset(endWidth), 0.0)));
            }
         }
      }

      Collections.reverse(tail);
      route.addAll(tail);
      parts.addAll(TactilePaths.parts(route));
      RoadGeometry.Sample mid = RoadStructures.sample(slip.mesh(), exact ? slip.turnEnd() * 0.5 : slip.mesh().length() * 0.5);
      double midOffset = exact ? RoadStreetscape.tactileOffset(JunctionSlips.walkWidth(s, slip, mid.distance(), smoothEdges)) : offset;
      parts.addAll(TactilePaths.parts(List.of(outer, mid.at(mid.halfWidth() + midOffset, 0.0))));
      ArrayList<RoadGeometry.V> ends = new ArrayList<>();
      ends.add(inner);

      for (int arm : new int[]{slip.from(), slip.to()}) {
         JunctionSpec.Arm config = s.arms().get(arm);
         if (config.crosswalk()) {
            RoadGeometry.Mesh mesh = approaches.get(arm);
            RoadGeometry.Sample pxxx = RoadStructures.sample(mesh, mesh.length() - config.crossingSetback() - config.crossingWidth() / 2.0);
            int side = arm == slip.from() ? 1 : -1;
            double edge = side > 0 ? config.motorEdge(true, false) : config.motorEdge(false, false) + profiles.reservation(arm, pxxx.distance());
            RoadGeometry.V end = pxxx.at(edge + (double)side * 0.45, 0.0);
            ends.add(end);
            warning(island, end, pxxx.left());
         }
      }

      List<List<RoadGeometry.V>> paving = island.stream().filter(pxxx -> pxxx.material().name().startsWith("WALK_")).map(RoadStructures.Part::base).toList();
      JunctionSlipWalks.Coverage coverage = new JunctionSlipWalks.Coverage(paving);
      RoadGeometry.V fork = null;
      double best = Double.POSITIVE_INFINITY;

      for (List<RoadGeometry.V> poly : paving) {
         RoadGeometry.V c = new RoadGeometry.V(0.0, 0.0, 0.0);

         for (RoadGeometry.V v : poly) {
            c = c.add(v);
         }

         c = c.mul(1.0 / (double)poly.size());
         double score = 0.0;
         boolean safe = true;

         for (RoadGeometry.V e : ends) {
            score += e.distance(c);
            if (!covered(coverage, e, c)) {
               safe = false;
               break;
            }
         }

         if (safe && score < best) {
            best = score;
            fork = c;
         }
      }

      if (fork != null) {
         for (RoadGeometry.V ex : ends) {
            island.addAll(TactilePaths.parts(List.of(fork, ex)));
         }
      } else {
         for (int i = 1; i < ends.size(); i++) {
            RoadGeometry.V start = ends.get(i);
            RoadGeometry.V finish = ends.get(0);
            List<RoadGeometry.V> routeInIsland = null;
            double length = Double.POSITIVE_INFINITY;
            if (covered(coverage, start, finish)) {
               routeInIsland = List.of(start, finish);
            } else {
               for (List<RoadGeometry.V> poly : paving) {
                  RoadGeometry.V c = new RoadGeometry.V(0.0, 0.0, 0.0);

                  for (RoadGeometry.V v : poly) {
                     c = c.add(v);
                  }

                  c = c.mul(1.0 / (double)poly.size());
                  double d = start.distance(c) + c.distance(finish);
                  if (d < length && covered(coverage, start, c) && covered(coverage, c, finish)) {
                     length = d;
                     routeInIsland = List.of(start, c, finish);
                  }
               }
            }

            if (routeInIsland == null) {
               throw new IllegalArgumentException("独立右转行人岛无法容纳连续盲道，请增大转角半径");
            }

            island.addAll(TactilePaths.parts(routeInIsland));
         }
      }
   }

   private static boolean covered(JunctionSlipWalks.Coverage paving, RoadGeometry.V a, RoadGeometry.V b) {
      int steps = Math.max(1, (int)Math.ceil(a.distance(b) * 4.0));
      RoadGeometry.V normal = b.sub(a).horizontalUnit().left();

      for (int k = 0; k <= steps; k++) {
         for (double side : new double[]{-0.31, 0.0, 0.31}) {
            RoadGeometry.V p = a.mul(1.0 - (double)k / (double)steps).add(b.mul((double)k / (double)steps)).add(normal.mul(side));
            if (!paving.contains(p)) {
               return false;
            }
         }
      }

      return true;
   }

   private static void warning(List<RoadStructures.Part> out, RoadGeometry.V p, RoadGeometry.V axis) {
      out.add(
         new RoadStructures.Part(
            p.sub(axis.mul(0.4)).add(new RoadGeometry.V(0.0, 0.2, 0.0)),
            p.add(axis.mul(0.4)).add(new RoadGeometry.V(0.0, 0.2, 0.0)),
            0.8,
            0.02,
            false,
            RoadStructures.Material.TACTILE
         )
      );
   }

   private static final class Coverage {
      private final Map<Long, List<List<RoadGeometry.V>>> cells = new HashMap<>();

      private static long key(int x, int z) {
         return (long)x << 32 ^ (long)z & 4294967295L;
      }

      Coverage(List<List<RoadGeometry.V>> polygons) {
         for (List<RoadGeometry.V> p : polygons) {
            double minX = Double.POSITIVE_INFINITY;
            double minZ = minX;
            double maxX = -minX;
            double maxZ = -minX;

            for (RoadGeometry.V v : p) {
               minX = Math.min(minX, v.x());
               maxX = Math.max(maxX, v.x());
               minZ = Math.min(minZ, v.z());
               maxZ = Math.max(maxZ, v.z());
            }

            for (int x = (int)Math.floor(minX / 4.0); x <= (int)Math.floor(maxX / 4.0); x++) {
               for (int z = (int)Math.floor(minZ / 4.0); z <= (int)Math.floor(maxZ / 4.0); z++) {
                  this.cells.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(p);
               }
            }
         }
      }

      boolean contains(RoadGeometry.V point) {
         for (List<RoadGeometry.V> p : this.cells.getOrDefault(key((int)Math.floor(point.x() / 4.0), (int)Math.floor(point.z() / 4.0)), List.of())) {
            if (JunctionPaint.inside(p, point)) {
               return true;
            }
         }

         return false;
      }
   }
}
