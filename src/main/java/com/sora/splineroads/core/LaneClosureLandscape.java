package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

public final class LaneClosureLandscape {
   public static final double MIN_GREEN_LENGTH = 12.0;

   public static boolean raised(RoadGeometry.Mesh mesh, int slot, double station, RoadStructures.Ground ground) {
      if (mesh.settings().structure() == RoadGeometry.Structure.BRIDGE) {
         return true;
      } else if (mesh.settings().structure() == RoadGeometry.Structure.GROUND) {
         return false;
      } else if (mesh.settings().structure() == RoadGeometry.Structure.TUNNEL) {
         return true;
      } else {
         RoadGeometry.Mesh raw = LaneSections.reference(mesh);
         LanePoints.Lane center = LanePoints.lane(raw, station, slot);
         double threshold = 0.125;
         int unsupported = 0;

         for (double along : new double[]{-0.65, 0.0, 0.65}) {
            RoadGeometry.Sample at = RoadStructures.sample(raw, Math.max(raw.first().distance(), Math.min(raw.last().distance(), station + along)));
            LanePoints.Lane lane = LanePoints.lane(raw, at.distance(), slot);

            for (double across : new double[]{-0.35, 0.0, 0.35}) {
               RoadGeometry.V p = lane.position().add(at.left().mul(across * center.width()));
               double top = ground.top(p.x(), p.z(), p.y());
               if (!Double.isFinite(top) || p.y() - top > threshold) {
                  unsupported++;
               }
            }
         }

         return unsupported >= 5;
      }
   }

   public static boolean paved(RoadStructures.Part part) {
      return RoadsideBypass.paved(part)
         || part.material() == RoadStructures.Material.CONCRETE && (part.model().equals("sr:closed_lane_pavement") || part.model().equals("sr:tunnel_apron"));
   }

   public static List<RoadStructures.Part> plan(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      if (mesh.settings().structure() == RoadGeometry.Structure.TUNNEL) {
         return List.of();
      } else {
         RoadGeometry.Mesh raw = LaneSections.reference(mesh);
         ArrayList<RoadStructures.Part> result = new ArrayList<>();
         List<LaneClosureLandscape.Interval> intervals = closedIntervals(mesh, ground);
         ArrayList<LaneClosureLandscape.Interval> eligible = new ArrayList<>();

         for (LaneClosureLandscape.Interval cut : intervals) {
            List<Integer> lane = List.of(cut.lane());
            double start = Double.NaN;

            for (double a = cut.begin(); a < cut.end() - 1.0E-7; a++) {
               double b = Math.min(a + 1.0, cut.end());
               boolean clear = !raised(mesh, cut.lane(), (a + b) / 2.0, ground) && bed(raw, lane, a, b, 1.0, 1.0).stream().noneMatch(ground::blocked);
               if (clear && Double.isNaN(start)) {
                  start = a;
               }

               if ((!clear || b >= cut.end()) && Double.isFinite(start)) {
                  double end = clear ? b : a;
                  if (end - start >= 12.0) {
                     eligible.add(new LaneClosureLandscape.Interval(cut.lane(), start, end));
                  }

                  start = Double.NaN;
               }
            }
         }

         ArrayList<LaneClosureLandscape.Interval> planted = new ArrayList<>();

         for (LaneClosureLandscape.Band band : bands(raw, eligible)) {
            plant(raw, band, ground, result, planted);
         }

         for (LaneClosureLandscape.Band band : bands(raw, intervals)) {
            for (double a = band.from(); a < band.to() - 1.0E-7; a++) {
               double bx = Math.min(a + 1.0, band.to());
               double station = (a + bx) / 2.0;
               List<Integer> onGround = band.slots().stream().filter(slot -> !raised(mesh, slot, station, ground)).toList();

               for (List<Integer> slots : adjacent(raw, onGround, station)) {
                  boolean green = planted.stream().anyMatch(i -> slots.contains(i.lane()) && station >= i.begin() && station < i.end());
                  LaneClosureLandscape.Cross first = cross(raw, slots, a);
                  LaneClosureLandscape.Cross last = cross(raw, slots, bx);
                  double depth = Math.max(0.5, raw.settings().thickness() + 0.125);
                  ArrayList<RoadStructures.Part> pads = new ArrayList<>();
                  RoadStructures.plantingLayer(
                     pads,
                     first.center().add(new RoadGeometry.V(0.0, -depth, 0.0)),
                     last.center().add(new RoadGeometry.V(0.0, -depth, 0.0)),
                     first.left(),
                     last.left(),
                     first.width(),
                     last.width(),
                     depth + (green ? 0.02 : 0.0),
                     RoadStructures.Material.CONCRETE,
                     green ? "" : "sr:closed_lane_pavement"
                  );

                  for (RoadStructures.Part pad : pads) {
                     addUnblocked(result, pad, ground, 0);
                  }
               }
            }
         }

         return List.copyOf(result);
      }
   }

   private static LaneClosureLandscape.Cross cross(RoadGeometry.Mesh raw, List<Integer> slots, double station) {
      RoadGeometry.Sample at = RoadStructures.sample(raw, station);
      double lo = Double.POSITIVE_INFINITY;
      double hi = Double.NEGATIVE_INFINITY;

      for (int slot : slots) {
         LanePoints.Lane lane = LanePoints.lane(raw, station, slot);
         double x = lane.position().sub(at.center()).dot(at.left());
         lo = Math.min(lo, x - lane.width() / 2.0);
         hi = Math.max(hi, x + lane.width() / 2.0);
      }

      return new LaneClosureLandscape.Cross(at.at((lo + hi) / 2.0, 0.0), at.left(), hi - lo);
   }

   private static List<List<Integer>> adjacent(RoadGeometry.Mesh raw, List<Integer> slots, double d) {
      RoadGeometry.Sample at = RoadStructures.sample(raw, d);
      RoadProfile.Layout layout = RoadProfile.layout(raw, at);
      ArrayList<Integer> sorted = new ArrayList<>(slots);
      sorted.sort(Comparator.comparingDouble(slotx -> LanePoints.lane(raw, d, slotx).position().sub(at.center()).dot(at.left())));
      ArrayList<List<Integer>> groups = new ArrayList<>();
      List<Integer> group = null;
      double previous = Double.NEGATIVE_INFINITY;

      for (int slot : sorted) {
         LanePoints.Lane lane = LanePoints.lane(raw, d, slot);
         double x = lane.position().sub(at.center()).dot(at.left());
         double low = x - lane.width() / 2.0;
         boolean median = Math.abs(previous - layout.medianEdge(-1)) < 0.05 && Math.abs(low - layout.medianEdge(1)) < 0.05;
         if (group == null || low > previous + 0.05 && !median) {
            group = new ArrayList<>();
            groups.add(group);
         }

         group.add(slot);
         previous = x + lane.width() / 2.0;
      }

      return groups;
   }

   private static List<LaneClosureLandscape.Band> bands(RoadGeometry.Mesh raw, List<LaneClosureLandscape.Interval> intervals) {
      TreeSet<Double> edges = new TreeSet<>();

      for (LaneClosureLandscape.Interval i : intervals) {
         edges.add(i.begin());
         edges.add(i.end());
      }

      ArrayList<Double> ordered = new ArrayList<>(edges);
      ArrayList<LaneClosureLandscape.Band> result = new ArrayList<>();
      HashMap<List<Integer>, Integer> lastIndex = new HashMap<>();

      for (int k = 1; k < ordered.size(); k++) {
         double a = ordered.get(k - 1);
         double b = ordered.get(k);
         double mid = (a + b) / 2.0;
         List<Integer> active = intervals.stream().filter(i -> mid >= i.begin() && mid < i.end()).map(LaneClosureLandscape.Interval::lane).distinct().toList();

         for (List<Integer> slots : adjacent(raw, active, mid)) {
            Integer n = lastIndex.get(slots);
            LaneClosureLandscape.Band last = n == null ? null : result.get(n);
            if (last != null && last.to() == a) {
               result.set(n, new LaneClosureLandscape.Band(slots, last.from(), b));
            } else {
               lastIndex.put(slots, result.size());
               result.add(new LaneClosureLandscape.Band(slots, a, b));
            }
         }
      }

      return result;
   }

   private static void plant(
      RoadGeometry.Mesh raw,
      LaneClosureLandscape.Band band,
      RoadStructures.Ground ground,
      List<RoadStructures.Part> out,
      List<LaneClosureLandscape.Interval> planted
   ) {
      double from = band.from();
      double to = band.to();
      if (!(to - from <= 0.4)) {
         ArrayList<RoadStructures.Part> parts = new ArrayList<>();

         for (double a = from + 0.2; a < to - 0.2 - 1.0E-7; a++) {
            double b = Math.min(to - 0.2, a + 1.0);
            parts.addAll(bed(raw, band.slots(), a, b, taper(a, from, to), taper(b, from, to)));
         }

         for (double d : new double[]{from + 0.1, to - 0.1}) {
            LaneClosureLandscape.Cross section = cross(raw, band.slots(), d);
            double depth = Math.max(0.5, raw.settings().thickness() + 0.125);
            RoadGeometry.V center = section.center().add(new RoadGeometry.V(0.0, -depth, 0.0));
            RoadGeometry.V edge = section.left().mul(Math.max(0.08, (section.width() - 0.12) * taper(d < from + 1.0 ? from + 0.2 : to - 0.2, from, to) / 2.0));
            parts.add(new RoadStructures.Part(center.sub(edge), center.add(edge), 0.2, depth + 0.35, false, RoadStructures.Material.CONCRETE));
         }

         if (parts.stream().noneMatch(ground::blocked)) {
            out.addAll(parts);

            for (int slot : band.slots()) {
               planted.add(new LaneClosureLandscape.Interval(slot, from, to));
            }
         } else if (band.slots().size() > 1) {
            for (int slot : band.slots()) {
               plant(raw, new LaneClosureLandscape.Band(List.of(slot), from, to), ground, out, planted);
            }
         }
      }
   }

   private static double taper(double d, double from, double to) {
      return 0.2 + 0.8 * RoadGeometry.Settings.smooth(Math.min(1.0, Math.min(d - from, to - d) / 2.0));
   }

   private static List<RoadStructures.Part> bed(RoadGeometry.Mesh raw, List<Integer> slots, double a, double b, double wa, double wb) {
      LaneClosureLandscape.Cross first = cross(raw, slots, a);
      LaneClosureLandscape.Cross last = cross(raw, slots, b);
      return RoadStructures.planting(
         first.center(),
         last.center(),
         first.left(),
         last.left(),
         (first.width() - 0.12) * wa,
         (last.width() - 0.12) * wb,
         1.0,
         Math.max(0.5, raw.settings().thickness() + 0.125)
      );
   }

   private static boolean connectorAddition(RoadGeometry.Mesh mesh, LaneSections.Cut cut) {
      return cut.lane() >= 8
         && mesh.settings().options().lanePoints().additions().stream().anyMatch(a -> a.slot() == cut.lane() && a.connection().equals(cut.connection()));
   }

   private static List<LaneClosureLandscape.Interval> closedIntervals(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);
      ArrayList<LaneClosureLandscape.Interval> intervals = new ArrayList<>();

      for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
         if (cut.temporary() && cut.rectangular() && !cut.underpass() && !connectorAddition(mesh, cut) && ground.closedLanePlanting(cut.connection())) {
            double a = Math.max(raw.first().distance(), Math.min(cut.begin(), cut.end()));
            double b = Math.min(raw.last().distance(), Math.max(cut.begin(), cut.end()));
            if (b > a + 1.0E-7) {
               intervals.add(new LaneClosureLandscape.Interval(cut.lane(), a, b));
            }
         }
      }

      intervals.sort(Comparator.comparingInt(LaneClosureLandscape.Interval::lane).thenComparingDouble(LaneClosureLandscape.Interval::begin));
      ArrayList<LaneClosureLandscape.Interval> merged = new ArrayList<>();

      for (LaneClosureLandscape.Interval next : intervals) {
         if (!merged.isEmpty()) {
            LaneClosureLandscape.Interval last = merged.get(merged.size() - 1);
            if (last.lane() == next.lane() && next.begin() <= last.end() + 1.0E-7) {
               merged.set(merged.size() - 1, new LaneClosureLandscape.Interval(last.lane(), last.begin(), Math.max(last.end(), next.end())));
               continue;
            }
         }

         merged.add(next);
      }

      return merged;
   }

   private static void addUnblocked(List<RoadStructures.Part> out, RoadStructures.Part part, RoadStructures.Ground ground, int depth) {
      if (!ground.blocked(part)) {
         out.add(part);
      } else if (depth < 4 && !(part.a().sub(part.b()).horizontalLength() < 0.125)) {
         RoadGeometry.V side = !part.pier() && !(part.b().sub(part.a()).horizontalLength() < 1.0E-8)
            ? part.b().sub(part.a()).horizontalUnit().left().mul(part.width() / 2.0)
            : new RoadGeometry.V(part.width() / 2.0, 0.0, 0.0);
         RoadGeometry.V first = part.frameA() == null ? side : part.frameA();
         RoadGeometry.V last = part.frameB() == null ? side : part.frameB();
         RoadGeometry.V mid = part.a().add(part.b()).mul(0.5);
         RoadGeometry.V frame = first.add(last).mul(0.5);
         addUnblocked(
            out,
            new RoadStructures.Part(part.a(), mid, part.width(), part.height(), part.pier(), part.material(), first, frame, part.model()),
            ground,
            depth + 1
         );
         addUnblocked(
            out,
            new RoadStructures.Part(mid, part.b(), part.width(), part.height(), part.pier(), part.material(), frame, last, part.model()),
            ground,
            depth + 1
         );
      }
   }

   private LaneClosureLandscape() {
   }

   private static record Band(List<Integer> slots, double from, double to) {
   }

   private static record Cross(RoadGeometry.V center, RoadGeometry.V left, double width) {
   }

   private static record Interval(int lane, double begin, double end) {
   }
}
