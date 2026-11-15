package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class RoadSupports {
   public static final double MAX_PORTAL_SPAN = 48.0;
   public static final double MAX_PORTAL_OUTREACH = 12.0;

   private static double foundation(RoadGeometry.V center, double top, double width, RoadStructures.Ground ground) {
      return ground.foundation(center, top, width);
   }

   public static boolean shaft(List<RoadStructures.Part> out, RoadGeometry.V center, double top, double width, RoadStructures.Ground ground) {
      double floor = foundation(center, top, width + 0.4, ground);
      if (!Double.isFinite(floor)) {
         return false;
      } else {
         double height = top - floor;
         if (height < 0.4) {
            return true;
         } else if (height > 96.0) {
            return false;
         } else {
            post(out, center, floor, width, height);
            post(out, center, floor, width + 0.4, Math.min(0.35, height));
            if (height > 2.0) {
               for (int i = 0; i < 3; i++) {
                  post(out, center, top - 0.6 + (double)i * 0.2, width + 0.25 * (double)(i + 1), 0.2);
               }
            }

            return true;
         }
      }
   }

   private static void post(List<RoadStructures.Part> out, RoadGeometry.V c, double y, double width, double height) {
      RoadGeometry.V p = new RoadGeometry.V(c.x(), y, c.z());
      out.add(new RoadStructures.Part(p, p, width, height, true, RoadStructures.Material.CONCRETE));
   }

   public static double medianFoundation(RoadGeometry.V center, double top, double width, List<RoadGeometry.Mesh> roads) {
      RoadGeometry.V position = new RoadGeometry.V(center.x(), top, center.z());
      RoadStructures.Part foot = new RoadStructures.Part(position, position, width, 0.35, true, RoadStructures.Material.CONCRETE);
      double highest = Double.NEGATIVE_INFINITY;

      for (RoadGeometry.Mesh road : roads) {
         if (road.settings().structure() != RoadGeometry.Structure.TUNNEL && RoadStructures.fitsMedian(foot, road, roads)) {
            double floor = Double.POSITIVE_INFINITY;
            double ceiling = Double.NEGATIVE_INFINITY;
            boolean present = true;

            for (double dx : new double[]{-0.5, 0.0, 0.5}) {
               for (double dz : new double[]{-0.5, 0.0, 0.5}) {
                  RoadQueries.Projection q = RoadQueries.horizontal(road, center.add(new RoadGeometry.V(dx * width, 0.0, dz * width)));
                  RoadGeometry.Sample s = q.sample();
                  if (!LaneDeck.present(road, s, q.lateral(), 0.0)
                     || road.settings().options().streetscape().tunnels().stream().anyMatch(span -> s.distance() >= span.from() && s.distance() <= span.to())) {
                     present = false;
                     break;
                  }

                  floor = Math.min(floor, s.center().y());
                  ceiling = Math.max(ceiling, s.center().y());
               }
            }

            if (present && ceiling < top - 0.4 && top - floor <= 96.0) {
               highest = Math.max(highest, floor);
            }
         }
      }

      return Double.isFinite(highest) ? highest : Double.NaN;
   }

   public static List<RoadStructures.Part> standard(RoadGeometry.Sample s, double thickness, RoadStructures.Ground ground) {
      double underside = s.center().y() - thickness;
      double cap = 0.8;
      double reach = Math.min(s.halfWidth(), Math.max(1.4, s.halfWidth() * 0.78));
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      double floor = foundation(s.center(), underside, 1.5, ground);
      if (Double.isFinite(floor) && underside - floor < 2.0) {
         return List.of();
      } else if (shaft(out, s.center(), underside - cap, 1.5, ground) && !out.isEmpty()) {
         out.add(new RoadStructures.Part(s.at(-reach, thickness + cap), s.at(reach, thickness + cap), 2.1, cap, false, RoadStructures.Material.CONCRETE));
         return List.copyOf(out);
      } else {
         return List.of();
      }
   }

   public static List<RoadGeometry.Sample> bearing(RoadGeometry.Mesh mesh, double station, double halfSpan) {
      RoadGeometry.Sample at = RoadStructures.sample(mesh, station);
      ArrayList<LaneDeck.Span> bands = new ArrayList<>(LaneDeck.spans(mesh, at));
      TreeSet<Double> probes = new TreeSet<>();
      probes.add(Math.max(0.0, station - halfSpan));
      probes.add(Math.min(mesh.length(), station + halfSpan));

      for (LaneSections.Cut c : mesh.settings().options().lanePoints().cuts()) {
         for (double edge : new double[]{c.begin(), c.end()}) {
            if (edge > station - halfSpan && edge < station + halfSpan) {
               probes.add(Math.max(0.0, edge - 1.0E-5));
               probes.add(Math.min(mesh.length(), edge + 1.0E-5));
            }
         }
      }

      for (double d : probes) {
         RoadGeometry.Sample s = RoadStructures.sample(mesh, d);
         ArrayList<LaneDeck.Span> next = new ArrayList<>();

         for (LaneDeck.Span x : bands) {
            for (LaneDeck.Span y : LaneDeck.spans(mesh, s)) {
               double lo = Math.max(x.low(), s.at(y.low(), 0.0).sub(at.center()).dot(at.left()));
               double hi = Math.min(x.high(), s.at(y.high(), 0.0).sub(at.center()).dot(at.left()));
               if (hi - lo >= 2.4) {
                  next.add(new LaneDeck.Span(lo, hi, true, true));
               }
            }
         }

         bands = next;
      }

      return bands.stream()
         .filter(b -> b.high() - b.low() >= 2.4)
         .map(b -> new RoadGeometry.Sample(at.at((b.low() + b.high()) / 2.0, 0.0), at.left(), station, (b.high() - b.low()) / 2.0))
         .toList();
   }

   public static List<RoadStructures.Part> clearStandard(RoadGeometry.Mesh mesh, double station, RoadStructures.Ground ground) {
      if (!LaneDeck.hasOpenings(mesh)) {
         return clearStandard(RoadStructures.sample(mesh, station), mesh.settings().thickness(), ground);
      } else {
         ArrayList<RoadStructures.Part> out = new ArrayList<>();

         for (RoadGeometry.Sample sample : bearing(mesh, station, 1.125)) {
            out.addAll(clearStandard(sample, mesh.settings().thickness(), ground));
         }

         return List.copyOf(out);
      }
   }

   public static List<RoadStructures.Part> ramp(RoadGeometry.Sample s, double thickness, RoadStructures.Ground ground) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      double top = s.center().y() - thickness;
      double floor = foundation(s.center(), top, 1.5, ground);
      if (Double.isFinite(floor) && !(top - floor < 2.0) && !(top - floor > 96.0)) {
         RoadGeometry.V foot = new RoadGeometry.V(s.center().x(), floor, s.center().z());
         RoadStructures.Part shaft = new RoadStructures.Part(foot, foot, 1.5, top - floor, true, RoadStructures.Material.CONCRETE);
         if (ground.blocked(shaft)) {
            return List.of();
         } else {
            out.add(shaft);
            return List.copyOf(out);
         }
      } else {
         return List.of();
      }
   }

   public static List<RoadStructures.Part> ramp(RoadGeometry.Mesh mesh, double station, RoadStructures.Ground ground) {
      return ramp(mesh, station, 0.0, ground);
   }

   public static List<RoadStructures.Part> ramp(RoadGeometry.Mesh mesh, double station, double lateral, RoadStructures.Ground ground) {
      RoadGeometry.Sample axis = RoadStructures.sample(mesh, station);
      double radius = 0.75 * (Math.abs(axis.left().x()) + Math.abs(axis.left().z())) + 0.04;
      boolean bearing = false;

      for (RoadGeometry.Sample band : bearing(mesh, station, 1.12)) {
         double center = band.center().sub(axis.center()).dot(axis.left());
         if (lateral - radius >= center - band.halfWidth() && lateral + radius <= center + band.halfWidth()) {
            bearing = true;
            break;
         }
      }

      if (!bearing) {
         return List.of();
      } else {
         RoadGeometry.Sample s = new RoadGeometry.Sample(axis.at(lateral, 0.0), axis.left(), station, axis.halfWidth());
         double thickness = mesh.settings().thickness();
         RoadGeometry.Sample before = RoadStructures.sample(mesh, Math.max(0.0, station - 0.75));
         RoadGeometry.Sample after = RoadStructures.sample(mesh, Math.min(mesh.length(), station + 0.75));
         RoadGeometry.V delta = after.center().sub(before.center());
         double grade = delta.y() / Math.max(1.0E-6, delta.horizontalLength());
         RoadGeometry.V tangent = delta.horizontalUnit();
         double gx = grade * tangent.x();
         double gz = grade * tangent.z();
         double top = s.center().y() - thickness - 0.006;
         double floor = foundation(s.center(), top, 1.5, ground);
         if (Double.isFinite(floor) && !(top - floor < 2.0) && !(top - floor > 96.0)) {
            double height = top - floor + 0.75 * (Math.abs(gx) + Math.abs(gz)) + 0.02;
            RoadGeometry.V a = new RoadGeometry.V(s.center().x(), top - height - 0.75 * gz, s.center().z());
            RoadGeometry.V b = new RoadGeometry.V(s.center().x(), top - height + 0.75 * gz, s.center().z());
            RoadGeometry.V frame = new RoadGeometry.V(0.75, 0.75 * gx, 0.0);
            RoadStructures.Part shaft = new RoadStructures.Part(a, b, 1.5, height, true, RoadStructures.Material.CONCRETE).frames(frame, frame);
            return ground.blocked(shaft) ? List.of() : List.of(shaft);
         } else {
            return List.of();
         }
      }
   }

   public static List<RoadStructures.Part> portal(
      RoadGeometry.Sample s, double thickness, double depth, double offset, double width, RoadStructures.Ground ground
   ) {
      double top = s.center().y() - thickness - depth;
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      double[] feet = new double[2];
      int i = 0;

      for (int side : new int[]{-1, 1}) {
         boolean found = false;

         for (double reach = offset; reach <= Math.min(offset + 12.0, 48.0 - width - 0.75 - offset); reach += 0.5) {
            ArrayList<RoadStructures.Part> shaft = new ArrayList<>();
            if (shaft(shaft, s.at((double)side * reach, 0.0), top, width, ground) && !shaft.isEmpty() && !shaft.stream().anyMatch(ground::blocked)) {
               out.addAll(shaft);
               feet[i++] = (double)side * reach;
               found = true;
               break;
            }
         }

         if (!found) {
            return List.of();
         }
      }

      if (feet[1] - feet[0] + width + 0.75 > 48.0000001) {
         return List.of();
      } else {
         RoadStructures.Part beam = new RoadStructures.Part(
            s.at(feet[0] - (width + 0.75) / 2.0, thickness + depth),
            s.at(feet[1] + (width + 0.75) / 2.0, thickness + depth),
            width + 0.75,
            depth,
            false,
            RoadStructures.Material.CONCRETE
         );
         if (ground.blocked(beam)) {
            return List.of();
         } else {
            out.add(beam);
            return List.copyOf(out);
         }
      }
   }

   public static List<RoadStructures.Part> clearStandard(RoadGeometry.Sample s, double thickness, RoadStructures.Ground ground) {
      List<RoadStructures.Part> simple = standard(s, thickness, ground);
      if (!simple.isEmpty() && simple.stream().noneMatch(ground::blocked)) {
         return simple;
      } else {
         double limit = Math.max(0.0, s.halfWidth() - 1.35);

         for (double reach = 0.5; reach <= limit + 1.0E-7; reach += 0.5) {
            for (int side : new int[]{-1, 1}) {
               double underside = s.center().y() - thickness;
               double cap = 0.8;
               ArrayList<RoadStructures.Part> out = new ArrayList<>();
               RoadGeometry.V axis = s.at((double)side * reach, 0.0);
               double floor = foundation(axis, underside, 1.5, ground);
               if (Double.isFinite(floor) && !(underside - floor < 2.0) && shaft(out, axis, underside - cap, 1.5, ground) && !out.isEmpty()) {
                  double spread = Math.min(s.halfWidth(), Math.max(1.4, s.halfWidth() * 0.78));
                  double low = Math.min(-spread, (double)side * reach - 1.1);
                  double high = Math.max(spread, (double)side * reach + 1.1);
                  if (!(low < -s.halfWidth()) && !(high > s.halfWidth())) {
                     out.add(
                        new RoadStructures.Part(s.at(low, thickness + cap), s.at(high, thickness + cap), 2.1, cap, false, RoadStructures.Material.CONCRETE)
                     );
                     if (out.stream().noneMatch(ground::blocked)) {
                        return List.copyOf(out);
                     }
                  }
               }
            }
         }

         return List.of();
      }
   }

   private RoadSupports() {
   }
}
