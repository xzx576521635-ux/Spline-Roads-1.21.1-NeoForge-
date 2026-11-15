package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;

public final class InterchangeSignPaths {
   public static Map<String, List<RoadGeometry.V>> routes(List<InterchangePlanner.Leg> legs, RoadGeometry.V start, int from, List<RoadGeometry.Node> anchors) {
      HashMap<InterchangeSignPaths.Point, List<InterchangeSignPaths.Edge>> graph = new HashMap<>();
      HashMap<InterchangeSignPaths.Point, RoadGeometry.V> positions = new HashMap<>();
      TreeMap<Integer, Set<InterchangeSignPaths.Point>> goals = new TreeMap<>();

      for (InterchangePlanner.Leg leg : legs) {
         RoadGeometry.Mesh mesh = leg.mesh();
         if (mesh.settings().style().ramp()) {
            for (int i = 1; i < mesh.samples().size(); i++) {
               RoadGeometry.V a = mesh.samples().get(i - 1).center();
               RoadGeometry.V b = mesh.samples().get(i).center();
               InterchangeSignPaths.Point ka = new InterchangeSignPaths.Point(a);
               InterchangeSignPaths.Point kb = new InterchangeSignPaths.Point(b);
               if (!ka.equals(kb)) {
                  positions.put(ka, a);
                  positions.put(kb, b);
                  graph.computeIfAbsent(ka, k -> new ArrayList<>()).add(new InterchangeSignPaths.Edge(kb, a.distance(b)));
               }
            }

            if (leg.to() >= 0 && leg.to() < anchors.size() && leg.to() != from) {
               RoadGeometry.V end = mesh.last().center();
               RoadGeometry.V heading = mesh.last().left().left().mul(-1.0);

               for (InterchangePlanner.Leg main : legs) {
                  if (!main.mesh().settings().style().ramp() && (main.from() == leg.to() || main.to() == leg.to())) {
                     RoadQueries.Projection q = RoadQueries.horizontal(main.mesh(), end);
                     RoadGeometry.Sample at = q.sample();
                     RoadGeometry.V outward = anchors.get(leg.to()).position().sub(at.center());
                     if (Math.abs(end.y() - at.center().y()) < 0.3
                        && RoadQueries.contains(main.mesh(), end, 0.25, 0.31)
                        && outward.horizontalLength() > 1.0
                        && heading.dot(outward.horizontalUnit()) > 0.9) {
                        goals.computeIfAbsent(leg.to(), k -> new LinkedHashSet<>()).add(new InterchangeSignPaths.Point(end));
                     }
                  }
               }
            }
         }
      }

      if (positions.size() > 100000) {
         throw new IllegalArgumentException("立交标牌拓扑超过可处理上限");
      } else {
         InterchangeSignPaths.Point source = new InterchangeSignPaths.Point(start);
         HashMap<InterchangeSignPaths.Point, Double> distance = new HashMap<>();
         HashMap<InterchangeSignPaths.Point, InterchangeSignPaths.Point> previous = new HashMap<>();
         PriorityQueue<InterchangeSignPaths.Visit> queue = new PriorityQueue<>(Comparator.comparingDouble(InterchangeSignPaths.Visit::distance));
         distance.put(source, 0.0);
         queue.add(new InterchangeSignPaths.Visit(source, 0.0));

         while (!queue.isEmpty()) {
            InterchangeSignPaths.Visit next = queue.remove();
            if (!(next.distance() > distance.getOrDefault(next.point(), Double.POSITIVE_INFINITY) + 1.0E-7)) {
               for (InterchangeSignPaths.Edge edge : graph.getOrDefault(next.point(), List.of())) {
                  double d = next.distance() + edge.length();
                  if (d + 1.0E-7 < distance.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)) {
                     distance.put(edge.to(), d);
                     previous.put(edge.to(), next.point());
                     queue.add(new InterchangeSignPaths.Visit(edge.to(), d));
                  }
               }
            }
         }

         LinkedHashMap<String, List<RoadGeometry.V>> result = new LinkedHashMap<>();

         for (Entry<Integer, Set<InterchangeSignPaths.Point>> goal : goals.entrySet()) {
            InterchangeSignPaths.Point end = goal.getValue().stream().filter(distance::containsKey).min(Comparator.comparingDouble(distance::get)).orElse(null);
            if (end != null) {
               ArrayList<RoadGeometry.V> path = new ArrayList<>();

               for (InterchangeSignPaths.Point at = end; at != null; at = previous.get(at)) {
                  path.add(positions.get(at));
                  if (at.equals(source)) {
                     break;
                  }
               }

               Collections.reverse(path);
               path.add(anchors.get(goal.getKey()).position());
               result.put("out:arm:" + goal.getKey(), List.copyOf(path));
            }
         }

         return result;
      }
   }

   private InterchangeSignPaths() {
   }

   private static record Edge(InterchangeSignPaths.Point to, double length) {
   }

   private static record Point(long x, long y, long z) {
      Point(RoadGeometry.V p) {
         this(Math.round(p.x() * 100000.0), Math.round(p.y() * 100000.0), Math.round(p.z() * 100000.0));
      }
   }

   private static record Visit(InterchangeSignPaths.Point point, double distance) {
   }
}
