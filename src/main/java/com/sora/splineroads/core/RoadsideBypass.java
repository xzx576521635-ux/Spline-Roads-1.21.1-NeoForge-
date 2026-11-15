package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

public final class RoadsideBypass {
   public static final double HEADROOM = 4.4;
   public static final double SLAB = 0.25;
   public static final double ROOF = 0.25;
   public static final double WALL = 0.22;
   public static final double MAX_GRADE = 0.16;
   private final RoadGeometry.Mesh road;
   private final List<RoadGeometry.Mesh> neighbors;

   public RoadsideBypass(RoadGeometry.Mesh road, List<RoadGeometry.Mesh> neighbors) {
      this.road = road;
      this.neighbors = neighbors;
   }

   public static boolean eligible(RoadGeometry.Mesh road) {
      RoadProfile.Options options = road.settings().options();
      return options.cycle()
         && !options.streetscape().parking()
         && road.settings().structure() != RoadGeometry.Structure.TUNNEL
         && RoadProfile.catalog(road.settings().style()).type() == RoadProfile.Type.ORDINARY
         && options.lanePoints().link() == null;
   }

   public boolean active() {
      return eligible(this.road) && !this.road.settings().options().streetscape().crossings().isEmpty();
   }

   public double offset(double station, int side) {
      return 0.0;
   }

   public double height(double station, int side) {
      return height(this.road, station, side);
   }

   public static double height(RoadGeometry.Mesh road, double station, int side) {
      if (!eligible(road)) {
         return 0.0;
      } else {
         for (RoadsideBypass.Span span : road.settings().options().streetscape().crossings()) {
            if (span.side() == side && station >= span.start() && station <= span.end()) {
               return span.height(station);
            }
         }

         return 0.0;
      }
   }

   public static boolean crossing(RoadGeometry.Mesh road, double station, int side) {
      return Math.abs(height(road, station, side)) > 1.0E-9;
   }

   public static double inner(RoadGeometry.Mesh road, RoadGeometry.Sample s, int side) {
      return (double)side * RoadProfile.layout(road, s).outer(side);
   }

   public static double outer(RoadGeometry.Mesh road, RoadGeometry.Sample s, int side) {
      return s.halfWidth() - RoadProfile.curbExtent(RoadProfile.layout(road, s), s, side);
   }

   public static RoadGeometry.Mesh with(RoadGeometry.Mesh mesh, List<RoadsideBypass.Span> spans) {
      RoadGeometry.Settings s = mesh.settings().options(mesh.settings().options().streetscape(mesh.settings().options().streetscape().crossings(spans)));
      return sampled(
         new RoadGeometry.Mesh(mesh.samples(), s, mesh.min(), mesh.max(), mesh.length(), mesh.closed(), mesh.controlPoint(), mesh.controls(), mesh.reference())
      );
   }

   public static RoadGeometry.Mesh sampled(RoadGeometry.Mesh mesh) {
      if (eligible(mesh) && !mesh.settings().options().streetscape().crossings().isEmpty()) {
         TreeSet<Double> stations = new TreeSet<>();
         HashMap<Double, RoadGeometry.Sample> original = new HashMap<>();

         for (RoadGeometry.Sample at : mesh.samples()) {
            stations.add(at.distance());
            original.put(at.distance(), at);
         }

         for (RoadsideBypass.Span span : mesh.settings().options().streetscape().crossings()) {
            for (double d = Math.max(0.0, span.start()); d < Math.min(mesh.length(), span.end()); d += 0.5) {
               addStation(stations, d);
            }

            addStation(stations, span.from());
            addStation(stations, span.to());
            addStation(stations, span.end());
         }

         List<RoadGeometry.Sample> samples = stations.stream()
            .filter(dx -> dx >= mesh.first().distance() && dx <= mesh.last().distance())
            .map(dx -> original.containsKey(dx) ? original.get(dx) : RoadStructures.sample(mesh, dx))
            .toList();
         return new RoadGeometry.Mesh(
            samples, mesh.settings(), mesh.min(), mesh.max(), mesh.length(), mesh.closed(), mesh.controlPoint(), mesh.controls(), mesh.reference()
         );
      } else {
         return mesh;
      }
   }

   private static void addStation(NavigableSet<Double> stations, double distance) {
      Double before = stations.floor(distance);
      if (before == null || !(distance - before <= 1.0E-7)) {
         Double after = stations.ceiling(distance);
         if (after == null || !(after - distance <= 1.0E-7)) {
            stations.add(Double.valueOf(distance));
         }
      }
   }

   public List<RoadSurface.Face> markings() {
      ArrayList<RoadSurface.Face> out = new ArrayList<>();
      if (!this.active()) {
         return out;
      } else {
         for (RoadsideBypass.Span span : this.road.settings().options().streetscape().crossings()) {
            for (double d = Math.max(0.0, span.start()); d < Math.min(this.road.length(), span.end()) - 1.0E-8; d += 0.5) {
               double end = Math.min(this.road.length(), Math.min(span.end(), d + 0.5));
               RoadGeometry.Sample a = RoadStructures.sample(this.road, d);
               RoadGeometry.Sample b = RoadStructures.sample(this.road, end);
               int side = span.side();
               double ha = span.height(d);
               double hb = span.height(end);

               for (int edge : new int[]{0, 1}) {
                  double ia = edge == 0 ? inner(this.road, a, side) + 0.22 + 0.14 : outer(this.road, a, side) - 0.22 - 0.14;
                  double ib = edge == 0 ? inner(this.road, b, side) + 0.22 + 0.14 : outer(this.road, b, side) - 0.22 - 0.14;
                  out.add(
                     new RoadSurface.Face(
                        List.of(
                           a.at((double)side * (ia - 0.06), -ha),
                           a.at((double)side * (ia + 0.06), -ha),
                           b.at((double)side * (ib + 0.06), -hb),
                           b.at((double)side * (ib - 0.06), -hb)
                        ),
                        15658734
                     )
                  );
               }
            }
         }

         return List.copyOf(out);
      }
   }

   public static List<RoadsideBypass.Span> plan(RoadGeometry.Mesh road, List<RoadsideBypass.Neighbor> neighbors) {
      return plan(road, neighbors, null, null);
   }

   public static boolean sameSavedCrossingSurface(RoadGeometry.Mesh previous, RoadGeometry.Mesh candidate, List<RoadsideBypass.Span> spans) {
      if (spans != null
         && !spans.isEmpty()
         && eligible(previous)
         && eligible(candidate)
         && previous.closed() == candidate.closed()
         && previous.settings().options().streetscape().crossings().equals(spans)
         && candidate.settings().options().streetscape().crossings().equals(spans)) {
         for (int side : new int[]{-1, 1}) {
            List<RoadGeometry.Sample> before = cycleSamples(previous, side);
            List<RoadGeometry.Sample> after = cycleSamples(candidate, side);

            for (RoadsideBypass.Span span : spans) {
               if (span.side() == side) {
                  RoadPlanningBudget.check();
                  if (!(span.end() > previous.length()) && !(span.end() > candidate.length())) {
                     List<RoadGeometry.Sample> oldWindow = crossingWindow(before, span);
                     List<RoadGeometry.Sample> newWindow = crossingWindow(after, span);
                     if (oldWindow.size() >= 2 && oldWindow.equals(newWindow)) {
                        continue;
                     }

                     return false;
                  }

                  return false;
               }
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private static List<RoadGeometry.Sample> crossingWindow(List<RoadGeometry.Sample> samples, RoadsideBypass.Span span) {
      int lo = 0;
      int hi = samples.size();

      while (lo < hi) {
         int mid = lo + hi >>> 1;
         if (samples.get(mid).distance() < span.start()) {
            lo = mid + 1;
         } else {
            hi = mid;
         }
      }

      int first = Math.max(0, lo - 1);
      lo = 0;
      hi = samples.size();

      while (lo < hi) {
         int mid = lo + hi >>> 1;
         if (samples.get(mid).distance() <= span.end()) {
            lo = mid + 1;
         } else {
            hi = mid;
         }
      }

      return samples.subList(first, Math.min(samples.size(), lo + 1));
   }

   public static List<RoadsideBypass.Span> plan(
      RoadGeometry.Mesh road,
      List<RoadsideBypass.Neighbor> neighbors,
      List<RoadsideBypass.Span> unchangedCrossings,
      List<RoadsideBypass.Neighbor> unchangedNeighbors
   ) {
      if (!eligible(road)) {
         return List.of();
      } else {
         HashMap<Long, List<RoadsideBypass.Obstacle>> grid = new HashMap<>();
         HashMap<Long, List<RoadsideBypass.Obstacle>> rails = new HashMap<>();

         for (RoadsideBypass.Neighbor n : neighbors) {
            RoadGeometry.Mesh ramp = n.mesh();
            boolean railway = ramp.settings().style() == RoadGeometry.Style.RAILBED;
            if (railway || ramp.settings().style().ramp() || ramp.settings().options().lanePoints().link() != null) {
               for (int i = 1; i < ramp.samples().size(); i++) {
                  if ((i & 63) == 0) {
                     RoadPlanningBudget.check();
                  }

                  RoadGeometry.Sample a = ramp.samples().get(i - 1);
                  RoadGeometry.Sample b = ramp.samples().get(i);
                  double station = (a.distance() + b.distance()) / 2.0;
                  RoadAutoTunnels.RoofRegion region = n.roofs().stream().filter(r -> station >= r.from() && station <= r.to()).findFirst().orElse(null);
                  boolean tunnel = ramp.settings().structure() == RoadGeometry.Structure.TUNNEL || region != null;
                  double clearance = region == null ? RoadInfrastructure.clearance(ramp.settings()) : Math.max(region.first(), region.last());
                  double margin = tunnel ? 1.55 : 0.15;
                  List<RoadGeometry.V> polygon = List.of(
                     a.at(-a.halfWidth() - margin, 0.0),
                     a.at(a.halfWidth() + margin, 0.0),
                     b.at(b.halfWidth() + margin, 0.0),
                     b.at(-b.halfWidth() - margin, 0.0)
                  );
                  double depth = ramp.settings().thickness() + RoadInfrastructure.girderDepth(ramp);
                  double low = Math.min(a.center().y(), b.center().y()) - depth;
                  double high = Math.max(a.center().y(), b.center().y()) + (tunnel ? clearance + 1.05 : 1.35);
                  double aboveHeight = tunnel ? clearance + 1.05 : Math.max(4.25, RoadInfrastructure.clearance(ramp.settings()));
                  RoadsideBypass.Obstacle obstacle = new RoadsideBypass.Obstacle(
                     polygon, low, high, tunnel, depth, aboveHeight, Math.min(a.center().y(), b.center().y()) - ramp.settings().thickness(), 0
                  );
                  int x0 = tile(polygon.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow());
                  int x1 = tile(polygon.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow());
                  int z0 = tile(polygon.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow());
                  int z1 = tile(polygon.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow());

                  for (int x = x0; x <= x1; x++) {
                     for (int z = z0; z <= z1; z++) {
                        (railway ? rails : grid).computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(obstacle);
                     }
                  }
               }
            }
         }

         boolean unchanged = rails.isEmpty() && unchangedCrossings != null && sameObstacles(neighbors, unchangedNeighbors);
         if (unchanged
            && !unchangedCrossings.isEmpty()
            && grid.values().stream().flatMap(Collection::stream).anyMatch(o -> o.low() != o.legacyLow())
            && sameCrossingCores(road, crossingSpans(road, grid, true), unchangedCrossings)) {
            return List.copyOf(unchangedCrossings);
         } else {
            List<RoadsideBypass.Span> result = crossingSpans(road, grid, false);
            if (unchanged && sameCrossingCores(road, result, unchangedCrossings)) {
               return List.copyOf(unchangedCrossings);
            } else {
               List<RoadsideBypass.Span> saved = road.settings().options().streetscape().crossings();
               List<RoadsideBypass.Span> fitted = fitApproaches(road, sameFittedCores(road, result, saved) ? saved : result);

               for (HashSet<RoadsideBypass.Obstacle> included = new HashSet<>();
                  !rails.isEmpty();
                  fitted = fitApproaches(road, crossingSpans(road, grid, false))
               ) {
                  RoadPlanningBudget.check();
                  HashSet<RoadsideBypass.Obstacle> added = new HashSet<>();

                  for (RoadsideBypass.Span span : fitted) {
                     for (double d = Math.max(0.0, span.start()); d < Math.min(road.length(), span.end()); d++) {
                        double end = Math.min(d + 1.0, Math.min(road.length(), span.end()));
                        if (!(Math.max(Math.abs(span.height(d)), Math.abs(span.height(end))) < 1.0E-6)) {
                           RoadGeometry.Sample a = RoadStructures.sample(road, d);
                           RoadGeometry.Sample b = RoadStructures.sample(road, end);
                           int side = span.side();
                           List<RoadGeometry.V> strip = List.of(
                              a.at((double)side * inner(road, a, side), 0.0),
                              a.at((double)side * outer(road, a, side), 0.0),
                              b.at((double)side * outer(road, b, side), 0.0),
                              b.at((double)side * inner(road, b, side), 0.0)
                           );
                           HashSet<RoadsideBypass.Obstacle> near = new HashSet<>();

                           for (RoadGeometry.V p : strip) {
                              for (int dx = -1; dx <= 1; dx++) {
                                 for (int dz = -1; dz <= 1; dz++) {
                                    near.addAll(rails.getOrDefault(key(tile(p.x()) + dx, tile(p.z()) + dz), List.of()));
                                 }
                              }
                           }

                           for (RoadsideBypass.Obstacle obstacle : near) {
                              if (RoadSurface.area(RoadSurface.intersect(obstacle.polygon(), strip)) > 1.0E-7) {
                                 RoadsideBypass.Obstacle selected = obstacle.forSide(side);
                                 if (!included.contains(selected)) {
                                    added.add(selected);
                                 }
                              }
                           }
                        }
                     }
                  }

                  if (added.isEmpty()) {
                     break;
                  }

                  for (RoadsideBypass.Obstacle obstaclex : added) {
                     included.add(obstaclex);
                     List<RoadGeometry.V> polygon = obstaclex.polygon();
                     int x0 = tile(polygon.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow());
                     int x1 = tile(polygon.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow());
                     int z0 = tile(polygon.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow());
                     int z1 = tile(polygon.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow());

                     for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                           grid.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(obstaclex);
                        }
                     }
                  }
               }

               validateClearance(road, fitted, grid);
               return fitted;
            }
         }
      }
   }

   private static List<RoadsideBypass.Span> crossingSpans(RoadGeometry.Mesh road, Map<Long, List<RoadsideBypass.Obstacle>> grid, boolean legacy) {
      ArrayList<RoadsideBypass.Span> result = new ArrayList<>();

      for (int side : new int[]{-1, 1}) {
         ArrayList<RoadsideBypass.Span> spans = new ArrayList<>();

         for (double d = 0.0; d < road.length() - 1.0E-7; d++) {
            RoadPlanningBudget.check();
            RoadGeometry.Sample a = RoadStructures.sample(road, d);
            RoadGeometry.Sample b = RoadStructures.sample(road, Math.min(road.length(), d + 1.0));
            RoadProfile.Layout layout = RoadProfile.layout(road, a);
            if (!(layout.cycleWidth() < 0.01) && (layout.catalog().twoWay() || layout.outside() == side)) {
               List<RoadGeometry.V> strip = List.of(
                  a.at((double)side * inner(road, a, side), 0.0),
                  a.at((double)side * outer(road, a, side), 0.0),
                  b.at((double)side * outer(road, b, side), 0.0),
                  b.at((double)side * inner(road, b, side), 0.0)
               );
               HashSet<RoadsideBypass.Obstacle> near = new HashSet<>();
               int x0 = tile(strip.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow());
               int x1 = tile(strip.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow());
               int z0 = tile(strip.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow());
               int z1 = tile(strip.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow());

               for (int x = x0; x <= x1; x++) {
                  for (int z = z0; z <= z1; z++) {
                     near.addAll(grid.getOrDefault(key(x, z), List.of()));
                  }
               }

               double down = 0.0;
               double up = 0.0;

               for (RoadsideBypass.Obstacle obstacle : near) {
                  if (obstacle.side() == 0 || obstacle.side() == side) {
                     double low = legacy ? obstacle.legacyLow() : obstacle.low();
                     if (!(low >= Math.max(a.center().y(), b.center().y()) + 4.4 + 0.1)
                        && !(obstacle.high() + 0.1 <= Math.min(a.center().y(), b.center().y()) - 0.25)) {
                        List<RoadGeometry.V> hit = RoadSurface.intersect(obstacle.polygon(), strip);
                        if (!(Math.abs(JunctionPaint.area(hit)) < 1.0E-7)) {
                           if (obstacle.tunnel()) {
                              up = Math.max(up, obstacle.high() + 0.25 + 0.1 - Math.min(a.center().y(), b.center().y()));
                           } else {
                              down = Math.min(down, low - 4.4 - 0.25 - 0.05 - Math.max(a.center().y(), b.center().y()));
                           }
                        }
                     }
                  }
               }

               if (down < 0.0 && up > 0.0) {
                  throw new RoadCrossingFailure.GeometryRejection("非机动车道上下均被匝道占用，无法保持通行净空");
               }

               double delta = down < 0.0 ? down : up;
               if (!(Math.abs(delta) < 1.0E-7)) {
                  double from = Math.max(0.0, d - 1.0);
                  double to = Math.min(road.length(), d + 2.0);
                  double ramp = Math.max(24.0, Math.abs(delta) * 1.875 / 0.16);
                  RoadsideBypass.Span next = new RoadsideBypass.Span(side, Math.max(0.0, from - ramp), from, to, Math.min(road.length(), to + ramp), delta);
                  if (!spans.isEmpty()) {
                     RoadsideBypass.Span old = spans.get(spans.size() - 1);
                     if (old.end() > next.start() && Math.signum(old.delta()) == Math.signum(next.delta())) {
                        delta = delta < 0.0 ? Math.min(old.delta(), delta) : Math.max(old.delta(), delta);
                        ramp = Math.max(24.0, Math.abs(delta) * 1.875 / 0.16);
                        next = new RoadsideBypass.Span(side, Math.max(0.0, old.from() - ramp), old.from(), to, Math.min(road.length(), to + ramp), delta);
                        spans.remove(spans.size() - 1);
                     } else if (old.to() >= next.from()) {
                        throw new RoadCrossingFailure.GeometryRejection("非机动车道上下穿区间冲突");
                     }
                  }

                  spans.add(next);
               }
            }
         }

         for (int i = 1; i < spans.size(); i++) {
            RoadsideBypass.Span a = spans.get(i - 1);
            RoadsideBypass.Span b = spans.get(i);
            if (!(a.end() <= b.start())) {
               double fraction = Math.abs(a.delta()) / (Math.abs(a.delta()) + Math.abs(b.delta()));
               double join = a.to() + (b.from() - a.to()) * fraction;
               spans.set(i - 1, new RoadsideBypass.Span(side, a.start(), a.from(), a.to(), join, a.delta()));
               spans.set(i, new RoadsideBypass.Span(side, join, b.from(), b.to(), b.end(), b.delta()));
            }
         }

         result.addAll(spans);
      }

      return List.copyOf(result);
   }

   private static boolean sameCrossingCores(RoadGeometry.Mesh road, List<RoadsideBypass.Span> planned, List<RoadsideBypass.Span> saved) {
      if (saved != null && saved.size() == planned.size()) {
         for (int i = 0; i < planned.size(); i++) {
            RoadsideBypass.Span a = planned.get(i);
            RoadsideBypass.Span b = saved.get(i);
            if (a.side() != b.side() || a.from() != b.from() || a.to() != b.to() || a.delta() != b.delta() || b.end() > road.length()) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private static boolean sameFittedCores(RoadGeometry.Mesh road, List<RoadsideBypass.Span> planned, List<RoadsideBypass.Span> saved) {
      if (saved != null && saved.size() == planned.size()) {
         for (int i = 0; i < planned.size(); i++) {
            RoadsideBypass.Span a = planned.get(i);
            RoadsideBypass.Span b = saved.get(i);
            double roundoff = 32.0 * Math.max(Math.ulp(a.delta()), Math.ulp(b.delta()));
            if (a.side() != b.side() || a.from() != b.from() || a.to() != b.to() || Math.abs(a.delta() - b.delta()) > roundoff || b.end() > road.length()) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private static List<RoadsideBypass.Span> fitApproaches(RoadGeometry.Mesh road, List<RoadsideBypass.Span> source) {
      ArrayList<RoadsideBypass.Span> spans = new ArrayList<>(source);

      for (int attempt = 0; attempt < Math.max(1, spans.size() * 64); attempt++) {
         RoadPlanningBudget.check();

         try {
            validate(road, spans);
            return List.copyOf(spans);
         } catch (RoadsideBypass.ValidationException var23) {
            RoadsideBypass.ValidationException failure = var23;
            if (var23.reason() != RoadsideBypass.ValidationException.Reason.GRADE || !Double.isFinite(var23.slope())) {
               throw var23;
            }

            double station = (var23.stationFrom() + var23.stationTo()) / 2.0;
            int index = -1;

            for (int i = 0; i < spans.size(); i++) {
               RoadsideBypass.Span span = spans.get(i);
               if (span.side() == failure.side() && station >= span.start() && station <= span.end()) {
                  index = i;
                  break;
               }
            }

            if (index < 0) {
               throw failure;
            }

            RoadsideBypass.Span old = spans.get(index);
            if (station >= old.from() && station <= old.to()) {
               throw failure;
            }

            double lower = 0.0;
            double upper = road.length();
            int before = -1;
            int after = -1;

            for (int ix = 0; ix < spans.size(); ix++) {
               if (ix != index && spans.get(ix).side() == old.side()) {
                  RoadsideBypass.Span other = spans.get(ix);
                  if (other.to() <= old.from() && other.end() >= lower) {
                     lower = other.end();
                     before = ix;
                  }

                  if (other.from() >= old.to() && other.start() <= upper) {
                     upper = other.start();
                     after = ix;
                  }
               }
            }

            double factor = Math.max(1.01, failure.slope() / 0.16);
            double start = old.start();
            double end = old.end();
            if (station < old.from()) {
               start = Math.max(lower, old.from() - (old.from() - start) * factor);
            } else {
               end = Math.min(upper, old.to() + (end - old.to()) * factor);
            }

            if (start >= old.start() - 1.0E-7 && end <= old.end() + 1.0E-7) {
               int left = station < old.from() ? before : index;
               int right = station < old.from() ? index : after;
               if (left < 0 || right < 0 || !fitSharedApproaches(road, spans, left, right)) {
                  throw failure;
               }
            } else {
               spans.set(index, new RoadsideBypass.Span(old.side(), start, old.from(), old.to(), end, old.delta()));
            }
         }
      }

      validate(road, spans);
      return List.copyOf(spans);
   }

   private static boolean fitSharedApproaches(RoadGeometry.Mesh road, List<RoadsideBypass.Span> spans, int left, int right) {
      RoadsideBypass.Span a = spans.get(left);
      RoadsideBypass.Span b = spans.get(right);
      if (a.side() == b.side() && !(a.to() >= b.from() - 2.0E-7)) {
         double low = a.to() + 1.0E-7;
         double high = b.from() - 1.0E-7;
         double join = Math.max(low, Math.min(high, (a.end() + b.start()) / 2.0));
         ArrayList<RoadsideBypass.Span> trial = new ArrayList<>(spans);

         for (int attempt = 0; attempt < 48; attempt++) {
            RoadPlanningBudget.check();
            RoadsideBypass.Span first = new RoadsideBypass.Span(a.side(), a.start(), a.from(), a.to(), join, a.delta());
            RoadsideBypass.Span second = new RoadsideBypass.Span(b.side(), join, b.from(), b.to(), b.end(), b.delta());
            trial.set(left, first);
            trial.set(right, second);
            double[] grades = sharedGrades(road, trial, first, second);
            if (grades[0] <= 0.16 && grades[1] <= 0.16) {
               if (first.equals(a) && second.equals(b)) {
                  return false;
               }

               spans.set(left, first);
               spans.set(right, second);
               return true;
            }

            if (grades[0] > grades[1]) {
               low = join;
            } else {
               high = join;
            }

            double next = (low + high) / 2.0;
            if (Math.abs(next - join) < 1.0E-8) {
               break;
            }

            join = next;
         }

         return false;
      } else {
         return false;
      }
   }

   private static double[] sharedGrades(RoadGeometry.Mesh road, List<RoadsideBypass.Span> spans, RoadsideBypass.Span a, RoadsideBypass.Span b) {
      RoadGeometry.Mesh planned = with(road, spans);
      List<RoadGeometry.Sample> samples = cycleSamples(planned, a.side());
      double[] grades = new double[2];

      for (int i = 1; i < samples.size(); i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         RoadGeometry.Sample first = samples.get(i - 1);
         RoadGeometry.Sample last = samples.get(i);
         sharedGrade(grades, a, b, (first.distance() + last.distance()) / 2.0, first.center(), last.center());
      }

      List<Double> stations = floorStations(planned);

      for (int i = 1; i < stations.size(); i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         double from = stations.get(i - 1);
         double to = stations.get(i);
         double mid = (from + to) / 2.0;
         if (!(mid < a.to()) && !(mid > b.from())) {
            RoadGeometry.Sample first = RoadStructures.sample(planned, from);
            RoadGeometry.Sample last = RoadStructures.sample(planned, to);
            sharedGrade(grades, a, b, mid, cyclePoint(planned, first, a.side()), cyclePoint(planned, last, a.side()));
         }
      }

      return grades;
   }

   private static RoadGeometry.V cyclePoint(RoadGeometry.Mesh road, RoadGeometry.Sample at, int side) {
      return at.at((double)side * (inner(road, at, side) + outer(road, at, side)) / 2.0, -height(road, at.distance(), side));
   }

   private static void sharedGrade(double[] grades, RoadsideBypass.Span a, RoadsideBypass.Span b, double station, RoadGeometry.V first, RoadGeometry.V last) {
      if (!(station < a.to()) && !(station > b.from())) {
         double run = last.sub(first).horizontalLength();
         double rise = Math.abs(last.y() - first.y());
         double slope = run < 1.0E-9 ? (rise > 1.0E-9 ? Double.POSITIVE_INFINITY : 0.0) : rise / run;
         int side = station <= a.end() ? 0 : 1;
         grades[side] = Math.max(grades[side], slope);
      }
   }

   private static void validateClearance(RoadGeometry.Mesh road, List<RoadsideBypass.Span> spans, Map<Long, List<RoadsideBypass.Obstacle>> grid) {
      if (!spans.isEmpty() && !grid.isEmpty()) {
         RoadGeometry.Mesh planned = with(road, spans);
         RoadsideBypass.ClearanceFailure worst = null;
         ArrayList<RoadsideBypass.ClearanceStrip> strips = new ArrayList<>();

         for (int side : new int[]{-1, 1}) {
            List<RoadGeometry.Sample> samples = cycleSamples(planned, side);

            for (int i = 1; i < samples.size(); i++) {
               RoadPlanningBudget.check();
               RoadGeometry.Sample a = samples.get(i - 1);
               RoadGeometry.Sample b = samples.get(i);
               double middle = (a.distance() + b.distance()) / 2.0;
               if (!spans.stream().noneMatch(s -> s.side() == side && middle > s.start() && middle < s.end())) {
                  RoadGeometry.V al = a.at(a.halfWidth(), 0.0);
                  RoadGeometry.V ar = a.at(-a.halfWidth(), 0.0);
                  RoadGeometry.V bl = b.at(b.halfWidth(), 0.0);
                  RoadGeometry.V br = b.at(-b.halfWidth(), 0.0);
                  strips.add(new RoadsideBypass.ClearanceStrip(side, a.distance(), b.distance(), List.of(al, ar, br, bl)));
               }
            }
         }

         List<Double> ds = floorStations(planned);

         for (int side : new int[]{-1, 1}) {
            for (int ix = 1; ix < ds.size(); ix++) {
               double da = ds.get(ix - 1);
               double db = ds.get(ix);
               double mid = (da + db) / 2.0;
               RoadsideBypass.Span span = spans.stream().filter(s -> s.side() == side && mid > s.start() && mid < s.end()).findFirst().orElse(null);
               if (span != null) {
                  RoadGeometry.Sample a = RoadStructures.sample(planned, da);
                  RoadGeometry.Sample b = RoadStructures.sample(planned, db);
                  ArrayList<RoadStructures.Part> floor = new ArrayList<>(1);
                  band(
                     floor,
                     a,
                     b,
                     side,
                     inner(planned, a, side),
                     outer(planned, a, side),
                     inner(planned, b, side),
                     outer(planned, b, side),
                     span.height(da) - 0.25,
                     span.height(db) - 0.25,
                     0.25,
                     RoadStructures.Material.CONCRETE,
                     "sr:cycle_bypass"
                  );
                  if (!floor.isEmpty()) {
                     strips.add(new RoadsideBypass.ClearanceStrip(side, da, db, floor.get(0).top()));
                  }
               }
            }
         }

         for (RoadsideBypass.ClearanceStrip strip : strips) {
            RoadPlanningBudget.check();
            List<RoadGeometry.V> quad = strip.top();
            HashSet<RoadsideBypass.Obstacle> near = new HashSet<>();
            int x0 = tile(quad.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow());
            int x1 = tile(quad.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow());
            int z0 = tile(quad.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow());
            int z1 = tile(quad.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow());

            for (int x = x0; x <= x1; x++) {
               for (int z = z0; z <= z1; z++) {
                  near.addAll(grid.getOrDefault(key(x, z), List.of()));
               }
            }

            for (RoadsideBypass.Obstacle obstacle : near) {
               if (obstacle.side() == 0 || obstacle.side() == strip.side()) {
                  for (List<RoadGeometry.V> triangle : triangles(quad)) {
                     for (List<RoadGeometry.V> other : triangles(obstacle.polygon())) {
                        List<RoadGeometry.V> overlap = RoadSurface.intersect(triangle, other);
                        if (!(Math.abs(JunctionPaint.area(overlap)) < 1.0E-7)) {
                           double below = Double.POSITIVE_INFINITY;
                           double above = Double.POSITIVE_INFINITY;
                           RoadGeometry.V belowPoint = null;
                           RoadGeometry.V abovePoint = null;

                           for (RoadGeometry.V point : overlap) {
                              double deck = surfaceHeight(other, point);
                              double under = deck - obstacle.depth() - point.y();
                              double over = point.y() - 0.25 - deck;
                              if (under < below) {
                                 below = under;
                                 belowPoint = point;
                              }

                              if (over < above) {
                                 above = over;
                                 abovePoint = point;
                              }
                           }

                           if (!(below >= 4.3999999) && !(above >= obstacle.aboveHeight() - 1.0E-7)) {
                              boolean underx = below - 4.4 > above - obstacle.aboveHeight();
                              double available = underx ? below : above;
                              double required = underx ? 4.4 : obstacle.aboveHeight();
                              if (worst == null || required - available > worst.required() - worst.available()) {
                                 worst = new RoadsideBypass.ClearanceFailure(
                                    strip.side(), strip.from(), strip.to(), underx ? belowPoint : abovePoint, available, required
                                 );
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         if (worst != null) {
            throw new RoadsideBypass.ValidationException(
               String.format(Locale.ROOT, "非机动车道通行净空不足：可用 %.6f 格 / 要求 %.6f 格；请移动相交匝道或调整高度", worst.available(), worst.required()),
               RoadsideBypass.ValidationException.Reason.CLEARANCE,
               worst.side(),
               worst.from(),
               worst.to(),
               Double.NaN,
               worst.point(),
               worst.point()
            );
         }
      }
   }

   private static List<List<RoadGeometry.V>> triangles(List<RoadGeometry.V> quad) {
      return List.of(List.of(quad.get(0), quad.get(1), quad.get(2)), List.of(quad.get(0), quad.get(2), quad.get(3)));
   }

   private static double surfaceHeight(List<RoadGeometry.V> triangle, RoadGeometry.V point) {
      RoadGeometry.V a = triangle.get(0);
      RoadGeometry.V b = triangle.get(1).sub(a);
      RoadGeometry.V c = triangle.get(2).sub(a);
      RoadGeometry.V q = point.sub(a);
      double determinant = b.x() * c.z() - b.z() * c.x();
      return a.y() + b.y() * (q.x() * c.z() - q.z() * c.x()) / determinant + c.y() * (b.x() * q.z() - b.z() * q.x()) / determinant;
   }

   private static boolean sameObstacles(List<RoadsideBypass.Neighbor> current, List<RoadsideBypass.Neighbor> previous) {
      if (previous == null) {
         return false;
      } else {
         HashSet<RoadsideBypass.ObstacleGeometry> before = new HashSet<>();
         HashSet<RoadsideBypass.ObstacleGeometry> after = new HashSet<>();

         for (RoadsideBypass.Neighbor neighbor : previous) {
            if (ramp(neighbor.mesh())) {
               before.add(obstacleGeometry(neighbor));
            }
         }

         for (RoadsideBypass.Neighbor neighborx : current) {
            if (ramp(neighborx.mesh())) {
               after.add(obstacleGeometry(neighborx));
            }
         }

         return before.equals(after);
      }
   }

   private static RoadsideBypass.ObstacleGeometry obstacleGeometry(RoadsideBypass.Neighbor neighbor) {
      RoadPlanningBudget.check();
      RoadGeometry.Mesh mesh = neighbor.mesh();
      return new RoadsideBypass.ObstacleGeometry(
         List.copyOf(mesh.samples()),
         mesh.length(),
         mesh.settings().thickness(),
         RoadInfrastructure.girderDepth(mesh),
         mesh.settings().structure() == RoadGeometry.Structure.TUNNEL,
         RoadInfrastructure.clearance(mesh.settings()),
         List.copyOf(neighbor.roofs())
      );
   }

   private static boolean ramp(RoadGeometry.Mesh mesh) {
      return mesh.settings().style().ramp() || mesh.settings().options().lanePoints().link() != null;
   }

   public static void validate(RoadGeometry.Mesh road, List<RoadsideBypass.Span> spans) {
      validate(road, spans, false);
   }

   public static void validateSection(RoadGeometry.Mesh road, List<RoadsideBypass.Span> spans) {
      validate(road, spans, true);
   }

   public static void validateFloor(RoadGeometry.Mesh road, List<RoadsideBypass.Span> spans, double minimumHeight) {
      for (RoadsideBypass.Span span : spans) {
         if (span.delta() < 0.0) {
            for (double d = Math.max(0.0, span.start()); d <= Math.min(road.length(), span.end()); d++) {
               RoadGeometry.Sample at = RoadStructures.sample(road, d);

               for (double fraction : new double[]{0.05, 0.5, 0.95}) {
                  double lateral = inner(road, at, span.side()) * (1.0 - fraction) + outer(road, at, span.side()) * fraction;
                  RoadGeometry.V bottom = at.at((double)span.side() * lateral, 0.25 - span.height(d));
                  if (bottom.y() < minimumHeight) {
                     throw new RoadCrossingFailure.GeometryRejection("非机动车下穿超出世界最低高度；请先提高相交道路，保留地下通道空间");
                  }
               }
            }
         }
      }
   }

   private static void validate(RoadGeometry.Mesh road, List<RoadsideBypass.Span> spans, boolean continuation) {
      if (eligible(road) && !spans.isEmpty()) {
         RoadGeometry.Mesh planned = with(road, spans);

         for (RoadsideBypass.Span span : spans) {
            if ((continuation || !(span.start() < 0.0) && !(span.end() > road.length() + 1.0E-7))
               && !(span.from() - span.start() < 1.0E-7)
               && !(span.end() - span.to() < 1.0E-7)) {
               if (!(Math.abs(span.height(span.start())) > 1.0E-7) && !(Math.abs(span.height(span.end())) > 1.0E-7)) {
                  continue;
               }

               throw new RoadsideBypass.ValidationException(
                  "非机动车道引道端点高程不连续", RoadsideBypass.ValidationException.Reason.ENDPOINT_HEIGHT, span.side(), span.start(), span.end(), Double.NaN
               );
            }

            throw new RoadsideBypass.ValidationException(
               "非机动车道引道空间不足，无法保留端点高程；请移动连接点或延长道路", RoadsideBypass.ValidationException.Reason.APPROACH_SPACE, span.side(), span.start(), span.end(), Double.NaN
            );
         }

         RoadsideBypass.ValidationException worst = null;

         for (int side : new int[]{-1, 1}) {
            List<RoadGeometry.Sample> samples = cycleSamples(planned, side);

            for (int i = 1; i < samples.size(); i++) {
               if ((i & 63) == 0) {
                  RoadPlanningBudget.check();
               }

               RoadGeometry.Sample a = samples.get(i - 1);
               RoadGeometry.Sample b = samples.get(i);
               double mid = (a.distance() + b.distance()) / 2.0;
               if (!spans.stream().noneMatch(s -> s.side() == side && mid >= s.start() && mid <= s.end())) {
                  double run = Math.hypot(b.center().x() - a.center().x(), b.center().z() - a.center().z());
                  double rise = Math.abs(b.center().y() - a.center().y());
                  if (run < 1.0E-9 ? rise > 1.0E-9 : rise / run > 0.1600001) {
                     double slope = run < 1.0E-9 ? Double.POSITIVE_INFINITY : rise / run;
                     if (worst == null || slope > worst.slope()) {
                        worst = new RoadsideBypass.ValidationException(
                           "非机动车道实际引道坡度超过 16%，剩余空间不足；请移动连接点或延长道路",
                           RoadsideBypass.ValidationException.Reason.GRADE,
                           side,
                           a.distance(),
                           b.distance(),
                           slope,
                           a.center(),
                           b.center()
                        );
                     }
                  }
               }
            }
         }

         if (worst != null) {
            throw worst;
         }
      }
   }

   public List<RoadStructures.Part> apply(List<RoadStructures.Part> original) {
      if (!this.active()) {
         return original;
      } else {
         ArrayList<RoadStructures.Part> out = new ArrayList<>();
         List<RoadShellOpening> openings = this.neighbors
            .stream()
            .map(n -> new RoadShellOpening(n, 0.0, n.length(), dx -> RoadStructureClearance.headroom(n), 1.0, 0.0))
            .toList();
         List<Double> ds = floorStations(this.road);

         for (int side : new int[]{-1, 1}) {
            for (int i = 1; i < ds.size(); i++) {
               double da = ds.get(i - 1);
               double db = ds.get(i);
               double mid = (da + db) / 2.0;
               RoadsideBypass.Span span = this.road
                  .settings()
                  .options()
                  .streetscape()
                  .crossings()
                  .stream()
                  .filter(s -> s.side() == side && mid > s.start() && mid < s.end())
                  .findFirst()
                  .orElse(null);
               if (span != null) {
                  RoadGeometry.Sample a = RoadStructures.sample(this.road, da);
                  RoadGeometry.Sample b = RoadStructures.sample(this.road, db);
                  double ha = span.height(da);
                  double hb = span.height(db);
                  double ia = inner(this.road, a, side);
                  double ib = inner(this.road, b, side);
                  double oa = outer(this.road, a, side);
                  double ob = outer(this.road, b, side);
                  band(out, a, b, side, ia, oa, ib, ob, ha - 0.25, hb - 0.25, 0.25, RoadStructures.Material.CONCRETE, "sr:cycle_bypass");
                  boolean roof = span.delta() < 0.0 && mid >= span.from() && mid <= span.to();
                  double topA = span.delta() < 0.0 ? (roof ? Math.max(0.2, ha + 4.4 + 0.25) : 0.5) : ha + 0.35;
                  double topB = span.delta() < 0.0 ? (roof ? Math.max(0.2, hb + 4.4 + 0.25) : 0.5) : hb + 0.35;

                  for (int edge : new int[]{0, 1}) {
                     double ca = edge == 0 ? ia + 0.11 : oa - 0.11;
                     double cb = edge == 0 ? ib + 0.11 : ob - 0.11;
                     double hA = topA - ha + 0.25;
                     double hB = topB - hb + 0.25;
                     out.add(
                        new RoadStructures.Part(
                           a.at((double)side * ca, 0.25 - ha),
                           b.at((double)side * cb, 0.25 - hb),
                           0.22,
                           Math.max(hA, hB),
                           false,
                           span.delta() < 0.0 ? RoadStructures.Material.TUNNEL : RoadStructures.Material.CONCRETE,
                           a.left().mul(0.11),
                           b.left().mul(0.11),
                           "sr:cycle_wall",
                           Math.abs(hA - hB) < 1.0E-8 ? null : new RoadStructures.Heights(hA, hA, hB, hB)
                        )
                     );
                     if (span.delta() > 0.0 || !roof) {
                        double railA = span.delta() > 0.0 ? ha + 0.35 : topA;
                        double railB = span.delta() > 0.0 ? hb + 0.35 : topB;

                        for (double y : new double[]{0.25, 0.65}) {
                           out.add(
                              new RoadStructures.Part(
                                 a.at((double)side * ca, -railA - y),
                                 b.at((double)side * cb, -railB - y),
                                 0.1,
                                 0.12,
                                 false,
                                 RoadStructures.Material.STEEL,
                                 a.left().mul(0.05),
                                 b.left().mul(0.05),
                                 "sr:cycle_rail"
                              )
                           );
                        }

                        if ((int)Math.floor(da * 2.0) % 4 == 0 || Math.abs(da - span.start()) < 1.0E-7 || Math.abs(da - span.to()) < 1.0E-7) {
                           out.add(
                              new RoadStructures.Part(
                                 a.at((double)side * ca, -railA),
                                 a.at((double)side * ca, -railA),
                                 0.12,
                                 0.77,
                                 true,
                                 RoadStructures.Material.STEEL,
                                 null,
                                 null,
                                 "sr:cycle_rail"
                              )
                           );
                        }

                        if (Math.abs(db - span.from()) < 1.0E-7 || Math.abs(db - span.end()) < 1.0E-7) {
                           out.add(
                              new RoadStructures.Part(
                                 b.at((double)side * cb, -railB),
                                 b.at((double)side * cb, -railB),
                                 0.12,
                                 0.77,
                                 true,
                                 RoadStructures.Material.STEEL,
                                 null,
                                 null,
                                 "sr:cycle_rail"
                              )
                           );
                        }
                     }
                  }

                  if (roof) {
                     band(out, a, b, side, ia, oa, ib, ob, ha + 4.4, hb + 4.4, 0.25, RoadStructures.Material.TUNNEL, "sr:cycle_roof");
                     band(
                        out,
                        a,
                        b,
                        side,
                        (ia + oa) / 2.0 - 0.08,
                        (ia + oa) / 2.0 + 0.08,
                        (ib + ob) / 2.0 - 0.08,
                        (ib + ob) / 2.0 + 0.08,
                        ha + 4.4 - 0.09,
                        hb + 4.4 - 0.09,
                        0.07,
                        RoadStructures.Material.LAMP,
                        "sr:cycle_light"
                     );
                     double bottomA = ha + 4.4 + 0.25;
                     double bottomB = hb + 4.4 + 0.25;
                     double coverA = Math.max(0.0, 0.0 - bottomA);
                     double coverB = Math.max(0.0, 0.0 - bottomB);
                     if (Math.min(coverA, coverB) > 1.0E-8) {
                        out.add(
                           new RoadStructures.Part(
                              a.at((double)side * (ia + oa) / 2.0, -bottomA),
                              b.at((double)side * (ib + ob) / 2.0, -bottomB),
                              Math.max(oa - ia, ob - ib),
                              Math.max(coverA, coverB),
                              false,
                              RoadStructures.Material.TUNNEL,
                              a.left().mul((oa - ia) / 2.0),
                              b.left().mul((ob - ib) / 2.0),
                              "sr:cycle_portal",
                              new RoadStructures.Heights(coverA, coverA, coverB, coverB)
                           )
                        );
                     }

                     RoadSidewalks.Config walk = this.road.settings().options().sidewalk();
                     band(
                        out,
                        a,
                        b,
                        side,
                        ia,
                        a.halfWidth(),
                        ib,
                        b.halfWidth(),
                        0.0,
                        0.0,
                        0.2,
                        RoadSidewalks.Finish.of(walk.material()).material(),
                        "sr:cycle_cover_walk"
                     );
                  }
               }
            }
         }

         for (RoadShellOpening air : openings) {
            ArrayList<RoadStructures.Part> kept = new ArrayList<>();

            for (RoadStructures.Part part : out) {
               kept.addAll(air.cut(part));
            }

            out = kept;
         }

         for (RoadGeometry.Mesh crossing : this.neighbors) {
            this.cutoffRails(out, crossing, openings);
         }

         for (RoadsideBypass.Span span : this.road.settings().options().streetscape().crossings()) {
            if (span.delta() < 0.0) {
               for (double d : new TreeSet<>(List.of(span.from(), span.to(), 0.0, this.road.length()))) {
                  if (!(d < 0.0) && !(d > this.road.length()) && !(span.height(d) > -0.7)) {
                     RoadGeometry.Sample at = RoadStructures.sample(this.road, d);
                     double inner = inner(this.road, at, span.side()) + 0.11;
                     double outer = outer(this.road, at, span.side()) - 0.11;
                     RoadGeometry.V a = at.at((double)span.side() * inner, -0.5);
                     RoadGeometry.V b = at.at((double)span.side() * outer, -0.5);
                     double probe = d == span.from() ? -0.08 : (d == span.to() ? 0.08 : (d == 0.0 ? -0.08 : 0.08));
                     RoadGeometry.V along = at.left().left().mul(-probe);
                     RoadGeometry.V middle = a.add(b).mul(0.5);
                     boolean continuing = (d == 0.0 || d == this.road.length())
                        && this.neighbors
                           .stream()
                           .anyMatch(n -> new RoadsideBypass(n, List.of()).openCutLip(middle.add(along).add(new RoadGeometry.V(0.0, -0.5, 0.0))));
                     if (!continuing) {
                        this.lipRail(out, a, b, openings);

                        for (RoadGeometry.V p : List.of(a, b)) {
                           RoadStructures.Part post = new RoadStructures.Part(
                              p, p, 0.12, 0.77, true, RoadStructures.Material.STEEL, null, null, "sr:cycle_rail"
                           );
                           if (openings.stream().allMatch(c -> c.cut(post).equals(List.of(post)))) {
                              out.add(post);
                           }
                        }
                     }
                  }
               }
            }
         }

         ArrayList<RoadStructures.Part> result = new ArrayList<>(original);
         result.addAll(out);
         return List.copyOf(result);
      }
   }

   private void cutoffRails(List<RoadStructures.Part> out, RoadGeometry.Mesh crossing, List<RoadShellOpening> openings) {
      for (int side : new int[]{-1, 1}) {
         RoadGeometry.V previous = null;
         double run = 0.0;

         for (double d = 0.0; d < crossing.length(); d += 0.25) {
            RoadGeometry.Sample first = RoadStructures.sample(crossing, d);
            RoadGeometry.Sample last = RoadStructures.sample(crossing, Math.min(crossing.length(), d + 0.25));
            RoadGeometry.V a = first.at((double)side * (first.halfWidth() + 0.08), 0.0);
            RoadGeometry.V b = last.at((double)side * (last.halfWidth() + 0.08), 0.0);
            boolean insideA = this.openCutLip(a);
            boolean insideB = this.openCutLip(b);
            if (!insideA && !insideB) {
               previous = null;
               run = 0.0;
            } else {
               if (insideA != insideB) {
                  RoadGeometry.V low = a;
                  RoadGeometry.V high = b;

                  for (int i = 0; i < 18; i++) {
                     RoadGeometry.V mid = low.add(high).mul(0.5);
                     if (this.openCutLip(mid) == insideA) {
                        low = mid;
                     } else {
                        high = mid;
                     }
                  }

                  if (insideA) {
                     b = low;
                  } else {
                     a = high;
                  }
               }

               RoadQueries.Projection qa = RoadQueries.horizontal(this.road, a);
               RoadQueries.Projection qb = RoadQueries.horizontal(this.road, b);
               a = new RoadGeometry.V(a.x(), qa.sample().center().y() + 0.5, a.z());
               b = new RoadGeometry.V(b.x(), qb.sample().center().y() + 0.5, b.z());
               if (!(a.distance(b) < 1.0E-6)) {
                  if (previous == null || previous.distance(a) > 0.001) {
                     this.lipPost(out, a, openings);
                     run = 0.0;
                  }

                  this.lipRail(out, a, b, openings);
                  run += a.distance(b);
                  if (run >= 2.0 || !insideB || d + 0.25 >= crossing.length()) {
                     this.lipPost(out, b, openings);
                     run = 0.0;
                  }

                  previous = b;
               }
            }
         }
      }
   }

   private void lipPost(List<RoadStructures.Part> out, RoadGeometry.V p, List<RoadShellOpening> openings) {
      RoadStructures.Part post = new RoadStructures.Part(p, p, 0.12, 0.77, true, RoadStructures.Material.STEEL, null, null, "sr:cycle_rail");
      if (openings.stream().allMatch(c -> c.cut(post).equals(List.of(post)))) {
         out.add(post);
      }
   }

   private void lipRail(List<RoadStructures.Part> out, RoadGeometry.V a, RoadGeometry.V b, List<RoadShellOpening> openings) {
      ArrayList<RoadStructures.Part> parts = new ArrayList<>();

      for (double y : new double[]{0.25, 0.65}) {
         parts.add(
            new RoadStructures.Part(
               a.add(new RoadGeometry.V(0.0, y, 0.0)),
               b.add(new RoadGeometry.V(0.0, y, 0.0)),
               0.1,
               0.12,
               false,
               RoadStructures.Material.STEEL,
               null,
               null,
               "sr:cycle_rail"
            )
         );
      }

      for (RoadShellOpening cut : openings) {
         ArrayList<RoadStructures.Part> kept = new ArrayList<>();

         for (RoadStructures.Part p : parts) {
            kept.addAll(cut.cut(p));
         }

         parts = kept;
         if (kept.isEmpty()) {
            return;
         }
      }

      out.addAll(parts);
   }

   private boolean openCutLip(RoadGeometry.V p) {
      if (!this.active()) {
         return false;
      } else {
         RoadQueries.Projection q = RoadQueries.horizontal(this.road, p);
         RoadGeometry.Sample at = q.sample();
         int side = q.lateral() < 0.0 ? -1 : 1;
         if (this.road.closed()
            || (!(at.distance() < 1.0E-7) || !(p.sub(this.road.first().center()).dot(this.road.first().left().left().mul(-1.0)) < -1.0E-7))
               && (!(at.distance() > this.road.length() - 1.0E-7) || !(p.sub(this.road.last().center()).dot(this.road.last().left().left().mul(-1.0)) > 1.0E-7))
            )
          {
            return !(Math.abs(p.y() - at.center().y()) > 0.6)
                  && !(q.lateral() * (double)side < inner(this.road, at, side) + 0.11)
                  && !(q.lateral() * (double)side > outer(this.road, at, side) - 0.11)
               ? this.road
                  .settings()
                  .options()
                  .streetscape()
                  .crossings()
                  .stream()
                  .anyMatch(
                     s -> s.side() == side
                           && s.delta() < 0.0
                           && at.distance() > s.start()
                           && at.distance() < s.end()
                           && (at.distance() < s.from() || at.distance() > s.to())
                           && s.height(at.distance()) < -0.7
                  )
               : false;
         } else {
            return false;
         }
      }
   }

   private static List<Double> floorStations(RoadGeometry.Mesh road) {
      TreeSet<Double> stations = new TreeSet<>();

      for (double d = 0.0; d < road.length(); d += 0.5) {
         stations.add(d);
      }

      stations.add(road.length());

      for (RoadsideBypass.Span span : road.settings().options().streetscape().crossings()) {
         for (double d : new double[]{span.start(), span.from(), span.to(), span.end()}) {
            if (d >= 0.0 && d <= road.length()) {
               stations.add(d);
            }
         }
      }

      return new ArrayList<>(stations);
   }

   private static void band(
      List<RoadStructures.Part> out,
      RoadGeometry.Sample a,
      RoadGeometry.Sample b,
      int side,
      double ia,
      double oa,
      double ib,
      double ob,
      double ya,
      double yb,
      double height,
      RoadStructures.Material material,
      String tag
   ) {
      double wa = oa - ia;
      double wb = ob - ib;
      if (!(Math.min(wa, wb) <= 0.0)) {
         out.add(
            new RoadStructures.Part(
               a.at((double)side * (ia + oa) / 2.0, -ya),
               b.at((double)side * (ib + ob) / 2.0, -yb),
               Math.max(wa, wb),
               height,
               false,
               material,
               a.left().mul(wa / 2.0),
               b.left().mul(wb / 2.0),
               tag
            )
         );
      }
   }

   public static double ceiling(RoadGeometry.Mesh mesh, double x, double z, double deckTop) {
      if (eligible(mesh) && !mesh.settings().options().streetscape().crossings().isEmpty()) {
         RoadQueries.Projection at = RoadQueries.horizontal(mesh, new RoadGeometry.V(x, deckTop, z));
         int side = at.lateral() < 0.0 ? -1 : 1;
         double h = height(mesh, at.sample().distance(), side);
         if (!(Math.abs(h) < 1.0E-7)
            && !(Math.abs(at.lateral()) < inner(mesh, at.sample(), side) - 0.6)
            && !(Math.abs(at.lateral()) > outer(mesh, at.sample(), side) + 0.6)
            && !(Math.abs(deckTop - at.sample().center().y() - h) > 0.6)) {
            double ceiling = deckTop + 4.4;

            for (RoadsideBypass.Span span : mesh.settings().options().streetscape().crossings()) {
               if (span.side() == side
                  && span.delta() < 0.0
                  && at.sample().distance() > span.start()
                  && at.sample().distance() < span.end()
                  && (at.sample().distance() < span.from() || at.sample().distance() > span.to())) {
                  ceiling = Math.max(ceiling, at.sample().center().y() + 0.2);
               }
            }

            return ceiling;
         } else {
            return Double.NEGATIVE_INFINITY;
         }
      } else {
         return Double.NEGATIVE_INFINITY;
      }
   }

   public static List<RoadTunnelSpace.Profiled> air(RoadGeometry.Mesh road) {
      if (!eligible(road)) {
         return List.of();
      } else {
         ArrayList<RoadTunnelSpace.Profiled> out = new ArrayList<>();

         for (RoadsideBypass.Span span : road.settings().options().streetscape().crossings()) {
            if (span.delta() < 0.0) {
               ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

               for (RoadGeometry.Sample at : road.samples()) {
                  if (at.distance() >= span.start() && at.distance() <= span.end()) {
                     double inner = inner(road, at, span.side()) + 0.22;
                     double outer = outer(road, at, span.side()) - 0.22;
                     samples.add(
                        new RoadGeometry.Sample(
                           at.at((double)span.side() * (inner + outer) / 2.0, -span.height(at.distance())), at.left(), at.distance(), (outer - inner) / 2.0
                        )
                     );
                  }
               }

               if (samples.size() >= 2) {
                  RoadGeometry.Mesh mesh = new RoadGeometry.Mesh(samples, road.settings(), road.min(), road.max(), road.length(), false, null);
                  ArrayList<RoadAutoTunnels.RoofRegion> profiles = new ArrayList<>();

                  for (int i = 1; i < samples.size(); i++) {
                     RoadGeometry.Sample a = samples.get(i - 1);
                     RoadGeometry.Sample b = samples.get(i);
                     double mid = (a.distance() + b.distance()) / 2.0;
                     double ha = 4.4;
                     double hb = 4.4;
                     if (mid < span.from() || mid > span.to()) {
                        ha = Math.max(ha, -span.height(a.distance()) + 0.2);
                        hb = Math.max(hb, -span.height(b.distance()) + 0.2);
                     }

                     profiles.add(new RoadAutoTunnels.RoofRegion(a.distance(), b.distance(), ha, hb));
                  }

                  out.add(new RoadTunnelSpace.Profiled(mesh, profiles));
               }
            }
         }

         return List.copyOf(out);
      }
   }

   public static List<RoadGeometry.Mesh> travel(RoadGeometry.Mesh road) {
      if (!eligible(road)) {
         return List.of();
      } else {
         ArrayList<RoadGeometry.Mesh> out = new ArrayList<>();

         for (int side : new int[]{-1, 1}) {
            List<RoadGeometry.Sample> samples = cycleSamples(road, side);
            if (samples.size() > 1) {
               RoadGeometry.Settings settings = new RoadGeometry.Settings(
                  RoadGeometry.Mode.CURVE, RoadGeometry.Style.UNMARKED, Math.max(0.5, samples.get(0).halfWidth() * 2.0), 0.25, 0.35, 90.0
               );
               out.add(RoadRibbon.mesh(samples, settings));
            }
         }

         return List.copyOf(out);
      }
   }

   private static List<RoadGeometry.Sample> cycleSamples(RoadGeometry.Mesh road, int side) {
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

      for (RoadGeometry.Sample at : road.samples()) {
         RoadProfile.Layout layout = RoadProfile.layout(road, at);
         if (!(layout.cycleWidth() <= 0.0) && (layout.catalog().twoWay() || layout.outside() == side)) {
            double lo = inner(road, at, side);
            double hi = outer(road, at, side);
            if (!(hi <= lo)) {
               samples.add(
                  new RoadGeometry.Sample(at.at((double)side * (lo + hi) / 2.0, -height(road, at.distance(), side)), at.left(), at.distance(), (hi - lo) / 2.0)
               );
            }
         }
      }

      return samples;
   }

   public static List<RoadStructures.Part> supportVoids(RoadGeometry.Mesh road) {
      if (!eligible(road)) {
         return List.of();
      } else {
         ArrayList<RoadStructures.Part> out = new ArrayList<>();

         for (RoadsideBypass.Span span : road.settings().options().streetscape().crossings()) {
            if (span.delta() < 0.0) {
               TreeSet<Double> stations = new TreeSet<>();
               stations.add(Math.max(0.0, span.start()));
               stations.add(Math.min(road.length(), span.end()));

               for (double d : new double[]{span.from(), span.to()}) {
                  if (d > 0.0 && d < road.length()) {
                     stations.add(d);
                  }
               }

               for (double dx = Math.max(0.0, span.start()); dx < Math.min(road.length(), span.end()); dx += 0.5) {
                  stations.add(dx);
               }

               ArrayList<Double> ds = new ArrayList<>(stations);

               for (int i = 1; i < ds.size(); i++) {
                  double da = ds.get(i - 1);
                  double db = ds.get(i);
                  double mid = (da + db) / 2.0;
                  RoadGeometry.Sample a = RoadStructures.sample(road, da);
                  RoadGeometry.Sample b = RoadStructures.sample(road, db);
                  double ha = span.height(da);
                  double hb = span.height(db);
                  boolean open = mid < span.from() || mid > span.to();
                  double ta = open ? Math.max(0.2, ha + 4.4) : ha + 4.4;
                  double tb = open ? Math.max(0.2, hb + 4.4) : hb + 4.4;
                  double ia = inner(road, a, span.side());
                  double oa = outer(road, a, span.side());
                  double ib = inner(road, b, span.side());
                  double ob = outer(road, b, span.side());
                  double aa = ta - ha + 0.25;
                  double bb = tb - hb + 0.25;
                  out.add(
                     new RoadStructures.Part(
                        a.at((double)span.side() * (ia + oa) / 2.0, 0.25 - ha),
                        b.at((double)span.side() * (ib + ob) / 2.0, 0.25 - hb),
                        Math.max(oa - ia, ob - ib),
                        Math.max(aa, bb),
                        false,
                        RoadStructures.Material.CONCRETE,
                        a.left().mul((oa - ia) / 2.0),
                        b.left().mul((ob - ib) / 2.0),
                        "sr:cycle_support_void",
                        new RoadStructures.Heights(aa, aa, bb, bb)
                     )
                  );
               }
            }
         }

         return List.copyOf(out);
      }
   }

   public static boolean paved(RoadStructures.Part p) {
      return p.material() == RoadStructures.Material.CONCRETE && p.model().equals("sr:cycle_bypass");
   }

   public static boolean walkway(RoadStructures.Part p) {
      return p.material().name().startsWith("WALK_") || paved(p);
   }

   private static int tile(double v) {
      return (int)Math.floor(v / 8.0);
   }

   private static long key(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   private static record ClearanceFailure(int side, double from, double to, RoadGeometry.V point, double available, double required) {
   }

   private static record ClearanceStrip(int side, double from, double to, List<RoadGeometry.V> top) {
   }

   public static record Neighbor(RoadGeometry.Mesh mesh, List<RoadAutoTunnels.RoofRegion> roofs) {
   }

   private static record Obstacle(
      List<RoadGeometry.V> polygon, double low, double high, boolean tunnel, double depth, double aboveHeight, double legacyLow, int side
   ) {
      RoadsideBypass.Obstacle forSide(int value) {
         return new RoadsideBypass.Obstacle(this.polygon, this.low, this.high, this.tunnel, this.depth, this.aboveHeight, this.legacyLow, value);
      }
   }

   private static record ObstacleGeometry(
      List<RoadGeometry.Sample> samples,
      double length,
      double thickness,
      double girderDepth,
      boolean tunnel,
      double clearance,
      List<RoadAutoTunnels.RoofRegion> roofs
   ) {
   }

   public static record Span(int side, double start, double from, double to, double end, double delta) {
      public Span(int side, double start, double from, double to, double end, double delta) {
         if (Math.abs(side) == 1
            && RoadGeometry.finite(start, from, to, end, delta)
            && !(start < -4096.0)
            && !(end > 8192.0)
            && !(from < start)
            && !(to < from)
            && !(end < to)
            && !(Math.abs(delta) < 1.0E-7)) {
            this.side = side;
            this.start = start;
            this.from = from;
            this.to = to;
            this.end = end;
            this.delta = delta;
         } else {
            throw new IllegalArgumentException("无效的非机动车道立体通行范围");
         }
      }

      public double height(double d) {
         if (!(d < this.start) && !(d > this.end)) {
            double t = d < this.from
               ? (d - this.start) / Math.max(1.0E-8, this.from - this.start)
               : (d > this.to ? (this.end - d) / Math.max(1.0E-8, this.end - this.to) : 1.0);
            return this.delta * t * t * t * (10.0 + t * (-15.0 + 6.0 * t));
         } else {
            return 0.0;
         }
      }
   }

   public static final class ValidationException extends IllegalArgumentException {
      private final RoadsideBypass.ValidationException.Reason reason;
      private final int side;
      private final double stationFrom;
      private final double stationTo;
      private final double slope;
      private final RoadGeometry.V worldFrom;
      private final RoadGeometry.V worldTo;

      private ValidationException(String message, RoadsideBypass.ValidationException.Reason reason, int side, double from, double to, double slope) {
         this(message, reason, side, from, to, slope, null, null);
      }

      private ValidationException(
         String message,
         RoadsideBypass.ValidationException.Reason reason,
         int side,
         double from,
         double to,
         double slope,
         RoadGeometry.V worldFrom,
         RoadGeometry.V worldTo
      ) {
         super(message);
         this.reason = reason;
         this.side = side;
         this.stationFrom = from;
         this.stationTo = to;
         this.slope = slope;
         this.worldFrom = worldFrom;
         this.worldTo = worldTo;
      }

      public RoadsideBypass.ValidationException.Reason reason() {
         return this.reason;
      }

      public int side() {
         return this.side;
      }

      public double stationFrom() {
         return this.stationFrom;
      }

      public double stationTo() {
         return this.stationTo;
      }

      public double slope() {
         return this.slope;
      }

      public double limit() {
         return 0.16;
      }

      public RoadGeometry.V worldFrom() {
         return this.worldFrom;
      }

      public RoadGeometry.V worldTo() {
         return this.worldTo;
      }

      public static enum Reason {
         APPROACH_SPACE,
         ENDPOINT_HEIGHT,
         GRADE,
         CLEARANCE;
      }
   }
}
