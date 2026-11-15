package com.sora.splineroads.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

public final class RailbedCandidates {
   public static String key(RailbedPath.Path path) {
      return path.segments().stream().map(RailbedPath.Segment::id).sorted().toList().toString();
   }

   public static List<RailbedPath.Path> find(RailbedPath.Path base, List<RailbedPath.Path> nearby) {
      HashMap<RailbedPath.Node, List<RailbedPath.Segment>> graph = new HashMap<>();
      LinkedHashMap<String, RailbedPath.Segment> segments = new LinkedHashMap<>();
      nearby.forEach(p -> p.segments().forEach(sx -> segments.put(sx.id(), sx)));
      segments.values().forEach(sx -> {
         graph.computeIfAbsent(sx.from(), n -> new ArrayList<>()).add(sx);
         graph.computeIfAbsent(sx.to(), n -> new ArrayList<>()).add(sx);
      });
      HashSet<String> excluded = new HashSet<>();
      base.segments().forEach(sx -> excluded.add(sx.id()));
      HashSet<String> visited = new HashSet<>();
      ArrayList<RailbedPath.Path> result = new ArrayList<>();

      for (RailbedPath.Segment seed : segments.values()) {
         if (!visited.contains(seed.id())) {
            LinkedHashSet<RailbedPath.Segment> component = new LinkedHashSet<>();
            ArrayDeque<RailbedPath.Segment> todo = new ArrayDeque<>();
            todo.add(seed);
            boolean branch = false;

            while (!todo.isEmpty()) {
               RailbedPath.Segment s = todo.remove();
               if (component.add(s)) {
                  visited.add(s.id());

                  for (RailbedPath.Node n : List.of(s.from(), s.to())) {
                     List<RailbedPath.Segment> links = graph.get(n);
                     branch |= links.size() > 2;

                     for (RailbedPath.Segment other : links) {
                        if (!component.contains(other)) {
                           todo.add(other);
                        }
                     }
                  }
               }
            }

            if (!branch && !component.stream().anyMatch(sx -> excluded.contains(sx.id()))) {
               RailbedPath.Node start = component.stream()
                  .flatMap(sx -> Stream.of(sx.from(), sx.to()))
                  .filter(n -> graph.get(n).size() == 1)
                  .min(Comparator.naturalOrder())
                  .orElse(null);
               if (start != null) {
                  ArrayList<RailbedPath.Segment> chain = new ArrayList<>();
                  HashSet<String> used = new HashSet<>();
                  RailbedPath.Node at = start;

                  while (true) {
                     RailbedPath.Segment next = null;

                     for (RailbedPath.Segment s : graph.get(at)) {
                        if (!used.contains(s.id())) {
                           next = s;
                           break;
                        }
                     }

                     if (next == null) {
                        try {
                           RailbedPath.Path clipped = clip(base, chain);
                           RailbedGeometry.plan(List.of(base, clipped), 0.0, RoadGeometry.Structure.AUTO);
                           result.add(clipped);
                        } catch (IllegalArgumentException var19) {
                        }
                        break;
                     }

                     used.add(next.id());
                     if (!next.from().equals(at)) {
                        next = reverse(next);
                     }

                     chain.add(next);
                     at = next.to();
                  }
               }
            }
         }
      }

      return List.copyOf(result);
   }

   public static List<RailbedPath.Path> automatic(RailbedPath.Path base, List<RailbedPath.Path> candidates) {
      ArrayList<RailbedPath.Path> selected = new ArrayList<>();
      selected.add(base);
      ArrayList<RailbedPath.Path> remaining = new ArrayList<>(candidates);

      while (selected.size() < 8) {
         List<RailbedPath.Path> next = remaining.stream()
            .filter(px -> selected.stream().anyMatch(s -> endpointDistance(px, s) <= 8.01))
            .sorted(Comparator.<RailbedPath.Path>comparingDouble(px -> endpointDistance(px, base)).thenComparing(RailbedCandidates::key))
            .toList();
         boolean added = false;

         for (RailbedPath.Path p : next) {
            remaining.remove(p);
            ArrayList<RailbedPath.Path> proposed = new ArrayList<>(selected);
            proposed.add(p);

            try {
               RailbedGeometry.plan(proposed, 0.0, RoadGeometry.Structure.AUTO);
               selected.add(p);
               added = true;
               break;
            } catch (IllegalArgumentException var10) {
            }
         }

         if (!added) {
            break;
         }
      }

      return List.copyOf(selected);
   }

   private static double endpointDistance(RailbedPath.Path a, RailbedPath.Path b) {
      List<RoadGeometry.V> points = a.positions();
      RoadGeometry.V origin = b.positions().get(0);
      return Math.min(points.get(0).distance(origin), points.get(points.size() - 1).distance(origin));
   }

   private static RailbedPath.Segment reverse(RailbedPath.Segment s) {
      ArrayList<RailbedPath.Point> points = new ArrayList<>();

      for (int i = s.points().size() - 1; i >= 0; i--) {
         RailbedPath.Point p = s.points().get(i);
         points.add(new RailbedPath.Point(s.length() - p.parameter(), p.position()));
      }

      return new RailbedPath.Segment(s.id(), s.to(), s.from(), !s.reverse(), s.length(), s.styles(), points);
   }

   private static RailbedCandidates.Located crossing(List<RailbedPath.Segment> chain, RoadGeometry.V origin, RoadGeometry.V tangent) {
      RailbedCandidates.Located hit = null;
      double order = 0.0;

      for (int s = 0; s < chain.size(); s++) {
         List<RailbedPath.Point> points = chain.get(s).points();

         for (int i = 1; i < points.size(); i++) {
            RailbedPath.Point a = points.get(i - 1);
            RailbedPath.Point b = points.get(i);
            double da = a.position().sub(origin).dot(tangent);
            double db = b.position().sub(origin).dot(tangent);
            double distance = a.position().distance(b.position());
            if (s == 0 && i == 1 && Math.abs(da) <= 0.006) {
               da = 0.0;
            }

            if (s == chain.size() - 1 && i == points.size() - 1 && Math.abs(db) <= 0.006) {
               db = 0.0;
            }

            if (da * db <= 1.0E-8 && Math.abs(da - db) > 1.0E-10) {
               double t = Math.max(0.0, Math.min(1.0, da / (da - db)));
               RoadGeometry.V p = a.position().mul(1.0 - t).add(b.position().mul(t));
               if (p.sub(origin).horizontalLength() < 64.0) {
                  RailbedCandidates.Located next = new RailbedCandidates.Located(
                     s, a.parameter() + (b.parameter() - a.parameter()) * t, p, order + distance * t
                  );
                  if (hit != null && Math.abs(hit.order - next.order) > 1.0E-5) {
                     throw new IllegalArgumentException("邻轨与端部断面多次相交");
                  }

                  hit = next;
               }
            }

            order += distance;
         }
      }

      if (hit == null) {
         throw new IllegalArgumentException("邻轨没有覆盖 A–B 区间");
      } else {
         return hit;
      }
   }

   public static RailbedPath.Path clip(RailbedPath.Path base, List<RailbedPath.Segment> chain) {
      List<RoadGeometry.V> points = base.positions();
      RailbedCandidates.Located a = crossing(chain, points.get(0), RailbedPath.tangent(points, 0));
      int end = points.size() - 1;
      RailbedCandidates.Located b = crossing(chain, points.get(end), RailbedPath.tangent(points, end));
      if (a.order > b.order) {
         RailbedCandidates.Located swap = a;
         a = b;
         b = swap;
      }

      if (b.order - a.order < 0.01) {
         throw new IllegalArgumentException("邻轨截取范围无效");
      } else {
         ArrayList<RailbedPath.Segment> output = new ArrayList<>();

         for (int i = a.segment; i <= b.segment; i++) {
            RailbedPath.Segment source = chain.get(i);
            ArrayList<RailbedPath.Point> sample = new ArrayList<>();
            double lo = i == a.segment ? a.parameter : source.points().get(0).parameter();
            double hi = i == b.segment ? b.parameter : source.points().get(source.points().size() - 1).parameter();
            if (!(hi - lo < 1.0E-8)) {
               if (i == a.segment) {
                  sample.add(new RailbedPath.Point(lo, a.position));
               }

               for (RailbedPath.Point p : source.points()) {
                  if (p.parameter() > lo + 1.0E-8 && p.parameter() < hi - 1.0E-8) {
                     sample.add(p);
                  }
               }

               if (i != a.segment) {
                  sample.add(0, source.points().get(0));
               }

               if (i == b.segment) {
                  sample.add(new RailbedPath.Point(hi, b.position));
               } else {
                  sample.add(source.points().get(source.points().size() - 1));
               }

               output.add(new RailbedPath.Segment(source.id(), source.from(), source.to(), source.reverse(), source.length(), source.styles(), sample));
            }
         }

         return new RailbedPath.Path(output);
      }
   }

   private RailbedCandidates() {
   }

   private static record Located(int segment, double parameter, RoadGeometry.V position, double order) {
   }
}
