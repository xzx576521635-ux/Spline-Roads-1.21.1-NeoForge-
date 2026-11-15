package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class RoadSurface {
   public static RoadSurface.Geometry closurePavement(RoadSurface.Geometry geometry, List<RoadStructures.Part> parts, List<RoadGeometry.Mesh> decks) {
      return closurePavement(geometry, parts, decks, null);
   }

   public static RoadSurface.Geometry closurePavement(
      RoadSurface.Geometry geometry, List<RoadStructures.Part> parts, List<RoadGeometry.Mesh> decks, RoadSurface.SlabSink sink
   ) {
      if (parts.stream().noneMatch(LaneClosureLandscape::paved)) {
         return geometry;
      } else {
         ArrayList<RoadSurface.Face> pavement = new ArrayList<>(geometry.pavement());
         exportClosureSlabs(parts, decks, (poly, depth, color) -> {
            pavement.add(new RoadSurface.Face(poly, color));
            if (sink != null) {
               sink.accept(poly, depth, color);
            }
         });
         return new RoadSurface.Geometry(List.copyOf(pavement), geometry.markings());
      }
   }

   public static void exportClosureSlabs(List<RoadStructures.Part> parts, List<RoadGeometry.Mesh> decks, RoadSurface.SlabSink sink) {
      exportClosureSlabs(parts, decks, sink, Long.MAX_VALUE);
   }

   public static void exportClosureSlabs(List<RoadStructures.Part> parts, List<RoadGeometry.Mesh> decks, RoadSurface.SlabSink sink, long workspace) {
      if (!parts.stream().noneMatch(LaneClosureLandscape::paved)) {
         RoadSurface.Grid occupied = new RoadSurface.Grid(decks, workspace);

         for (RoadStructures.Part part : RoadRenderMesh.compact(parts.stream().filter(LaneClosureLandscape::paved).toList(), false)) {
            List<RoadGeometry.V> top = part.base().stream().map(v -> v.add(new RoadGeometry.V(0.0, part.height(), 0.0))).toList();

            for (int i = 1; i < top.size() - 1; i++) {
               for (List<RoadGeometry.V> poly : visible(List.of(top.get(0), top.get(i), top.get(i + 1)), occupied, 0.025)) {
                  sink.accept(poly, part.height(), 14474460);
               }
            }
         }
      }
   }

   public static boolean higherPriority(UUID candidate, RoadGeometry.Mesh a, UUID current, RoadGeometry.Mesh b) {
      if (candidate.equals(current)) {
         return false;
      } else if (a.settings().style() == RoadGeometry.Style.RAILBED != (b.settings().style() == RoadGeometry.Style.RAILBED)) {
         return a.settings().style() != RoadGeometry.Style.RAILBED;
      } else {
         LanePoints.Data am = a.settings().options().lanePoints();
         LanePoints.Data bm = b.settings().options().lanePoints();
         boolean ar = am.link() != null || a.settings().style().ramp();
         boolean br = bm.link() != null || b.settings().style().ramp();
         if (ar != br) {
            return !ar;
         } else {
            int ad = am.link() == null ? 0 : Math.max(1, am.priorityDepth());
            int bd = bm.link() == null ? 0 : Math.max(1, bm.priorityDepth());
            return ad != bd ? ad < bd : candidate.compareTo(current) < 0;
         }
      }
   }

   public static RoadSurface.Geometry custom(RoadSurface.Geometry base, RoadGeometry.Mesh mesh, List<RoadSurface.Face> paint) {
      List<RoadSurface.Face> clipped = new ArrayList<>();
      RoadSurface.Grid grid = new RoadSurface.Grid(List.of(mesh));

      for (RoadSurface.Face f : paint) {
         paintOnRoad(clipped, new RoadJunction.Paint(f.points(), f.color()), grid);
      }

      if (!mesh.settings().options().levelCrossings().isEmpty()) {
         clipped = clipCrossingPaint(clipped, new RoadSurface.PaintMask(RailwayCrossings.paintCuts(mesh)));

         for (RoadJunction.Paint line : RailwayCrossings.stopLines(mesh)) {
            paintOnRoad(clipped, line, grid);
         }
      }

      return new RoadSurface.Geometry(base.pavement(), List.copyOf(clipped));
   }

   public static boolean painted(RoadGeometry.Mesh mesh, double station) {
      double d = station + mesh.settings().options().ends().paintPhase() + mesh.settings().options().ends().trimmedStart();
      return (d % 6.0 + 6.0) % 6.0 < 3.0;
   }

   private static List<RoadGeometry.Sample> markingSamples(RoadGeometry.Mesh mesh) {
      double phase = mesh.settings().options().ends().paintPhase() + mesh.settings().options().ends().trimmedStart();
      if (Math.abs(phase % 3.0) < 1.0E-8) {
         return RoadAttachments.splitPaint(mesh, mesh.samples());
      } else {
         ArrayList<RoadGeometry.Sample> out = new ArrayList<>();
         List<RoadGeometry.Sample> samples = mesh.samples();
         out.add(samples.get(0));

         for (int i = 1; i < samples.size(); i++) {
            double a = samples.get(i - 1).distance();
            double b = samples.get(i).distance();

            for (double at = (Math.floor((a + phase) / 3.0) + 1.0) * 3.0 - phase; at < b - 1.0E-7; at += 3.0) {
               if (at > a + 1.0E-7) {
                  out.add(RoadStructures.sample(mesh, at));
               }
            }

            out.add(samples.get(i));
         }

         return RoadAttachments.splitPaint(mesh, out);
      }
   }

   private static boolean overrideLine(
      List<RoadSurface.Face> out, RoadGeometry.Mesh mesh, RoadGeometry.Sample a, RoadGeometry.Sample b, String key, double x, double y, RoadSurface.Grid cuts
   ) {
      RoadLaneLines.Edit e = RoadAttachments.line(mesh, (a.distance() + b.distance()) / 2.0, key);
      RoadLaneLines.Pattern p = e.pattern();
      if (key.equals("center:-1") || key.equals("center:1")) {
         RoadLaneLines.Edit other = RoadAttachments.line(mesh, (a.distance() + b.distance()) / 2.0, key.equals("center:-1") ? "center:1" : "center:-1");
         if (p == RoadLaneLines.Pattern.DEFAULT && other.pattern().mixed()) {
            return true;
         }

         if (p.mixed() && other.pattern() == RoadLaneLines.Pattern.DEFAULT) {
            x = RoadProfile.layout(mesh, a).medianCenter();
            y = RoadProfile.layout(mesh, b).medianCenter();
         }
      }

      if (p == RoadLaneLines.Pattern.DEFAULT) {
         return false;
      } else if (p != RoadLaneLines.Pattern.NONE && (!p.dashed() || painted(mesh, (a.distance() + b.distance()) / 2.0))) {
         if (p.doubled()) {
            for (int side : new int[]{-1, 1}) {
               if (!p.mixed() || !p.dashedSide(side) || painted(mesh, (a.distance() + b.distance()) / 2.0)) {
                  stripe(out, a, b, x + (double)side * (e.width() + 0.06), y + (double)side * (e.width() + 0.06), e.width(), p.yellow(), cuts);
               }
            }
         } else {
            stripe(out, a, b, x, y, e.width(), p.yellow(), cuts);
         }

         return true;
      } else {
         return true;
      }
   }

   public static RoadSurface.Geometry build(RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> higherPriority, List<RoadGeometry.Mesh> neighbors) {
      return build(mesh, higherPriority, neighbors, new RoadsideBypass(mesh, neighbors));
   }

   public static RoadSurface.Geometry build(
      RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> higherPriority, List<RoadGeometry.Mesh> neighbors, RoadsideBypass bypass
   ) {
      return buildWindow(mesh, higherPriority, neighbors, bypass, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
   }

   public static RoadSurface.Geometry build(
      RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> higherPriority, List<RoadGeometry.Mesh> neighbors, RoadsideBypass bypass, RoadSurface.SlabSink sink
   ) {
      return new RoadSurface.WindowContext(mesh, higherPriority, neighbors, bypass).build(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, sink);
   }

   public static void exportSlabs(RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> higherPriority, RoadSurface.SlabSink sink) {
      exportSlabs(mesh, higherPriority, sink, Long.MAX_VALUE);
   }

   public static void exportSlabs(RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> higherPriority, RoadSurface.SlabSink sink, long workspace) {
      RoadSurface.Grid owners = new RoadSurface.Grid(higherPriority, workspace);
      List<RoadGeometry.Sample> deck = RoadRenderMesh.pavement(mesh).samples();

      for (int i = 1; i < deck.size(); i++) {
         RoadPlanningBudget.check();

         for (LaneDeck.Strip strip : LaneDeck.strips(mesh, deck.get(i - 1), deck.get(i))) {
            slab(strip, owners, mesh.settings().thickness(), sink);
         }
      }
   }

   private static void slab(LaneDeck.Strip strip, RoadSurface.Grid owners, double thickness, RoadSurface.SlabSink sink) {
      RoadGeometry.V l = strip.al();
      RoadGeometry.V r = strip.ar();
      RoadGeometry.V ll = strip.bl();
      RoadGeometry.V rr = strip.br();

      for (List<RoadGeometry.V> triangle : List.of(List.of(l, r, rr), List.of(l, rr, ll))) {
         if (area(triangle) > 1.0E-9) {
            for (List<RoadGeometry.V> poly : visible(triangle, owners, 0.025)) {
               sink.accept(poly, thickness, 14474460);
            }
         }
      }
   }

   public static RoadSurface.Geometry buildWindow(
      RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> higherPriority, List<RoadGeometry.Mesh> neighbors, RoadsideBypass bypass, double from, double to
   ) {
      return new RoadSurface.WindowContext(mesh, higherPriority, neighbors, bypass).build(from, to);
   }

   private static List<RoadSurface.Face> clipCrossingPaint(List<RoadSurface.Face> paint, RoadSurface.PaintMask mask) {
      ArrayList<RoadSurface.Face> clipped = new ArrayList<>();

      for (RoadSurface.Face face : paint) {
         for (List<RoadGeometry.V> poly : mask.clipXZ(face.points())) {
            clipped.add(new RoadSurface.Face(poly, face.color(), face.emissive(), face.texture()));
         }
      }

      return clipped;
   }

   public static RoadSurface.Geometry buildCustom(
      RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> higher, List<RoadGeometry.Mesh> joined, List<RoadSurface.Face> paint
   ) {
      ArrayList<RoadSurface.Face> pavement = new ArrayList<>();
      pavement(
         mesh,
         RoadRenderMesh.pavement(mesh).samples(),
         new RoadSurface.Grid(higher),
         new RoadSurface.Grid(joined),
         Double.NEGATIVE_INFINITY,
         Double.POSITIVE_INFINITY,
         null,
         pavement
      );
      RoadSurface.Geometry base = new RoadSurface.Geometry(List.copyOf(pavement), List.of());
      return paint.isEmpty() && mesh.settings().options().levelCrossings().isEmpty() ? base : custom(base, mesh, paint);
   }

   private static void pavement(
      RoadGeometry.Mesh mesh,
      List<RoadGeometry.Sample> deck,
      RoadSurface.Grid owners,
      RoadSurface.Grid joined,
      double from,
      double to,
      RoadSurface.SlabSink sink,
      List<RoadSurface.Face> pavement
   ) {
      double thickness = mesh.settings().thickness();

      for (int i = 1; i < deck.size(); i++) {
         RoadGeometry.Sample a = deck.get(i - 1);
         RoadGeometry.Sample b = deck.get(i);
         if (inWindow((a.distance() + b.distance()) / 2.0, from, to)) {
            for (LaneDeck.Strip strip : LaneDeck.strips(mesh, a, b)) {
               RoadGeometry.V l = strip.al();
               RoadGeometry.V r = strip.ar();
               RoadGeometry.V ll = strip.bl();
               RoadGeometry.V rr = strip.br();
               slab(
                  strip,
                  owners,
                  thickness,
                  (poly, depth, color) -> {
                     pavement.add(new RoadSurface.Face(poly, color));
                     pavement.add(
                        new RoadSurface.Face(
                           poly.stream().map(v -> v.add(new RoadGeometry.V(0.0, -depth, 0.0))).toList(), 12171693, false, RoadSurface.Texture.CONCRETE
                        )
                     );
                     if (sink != null) {
                        sink.accept(poly, depth, color);
                     }
                  }
               );
               if (strip.highWall()) {
                  wall(pavement, ll, l, thickness, joined);
               }

               if (strip.lowWall()) {
                  wall(pavement, r, rr, thickness, joined);
               }
            }
         }
      }

      if (!mesh.closed()) {
         for (boolean start : new boolean[]{true, false}) {
            RoadGeometry.Sample a = start ? mesh.first() : mesh.last();
            if (inWindow(a.distance(), from, to)) {
               for (LaneDeck.Span span : LaneDeck.spans(mesh, a)) {
                  if (span.high() - span.low() > 1.0E-7) {
                     wall(pavement, a.at(start ? span.high() : span.low(), 0.0), a.at(start ? span.low() : span.high(), 0.0), thickness, joined);
                  }
               }
            }
         }
      }

      for (LaneDeck.Cap cap : LaneDeck.caps(mesh)) {
         if (inWindow(RoadQueries.horizontal(mesh, cap.a().add(cap.b()).mul(0.5)).sample().distance(), from, to)) {
            wall(pavement, cap.a(), cap.b(), thickness, joined);
         }
      }
   }

   public static boolean inWindow(double distance, double from, double to) {
      return distance >= from && distance < to;
   }

   private static boolean inWindow(RoadGeometry.Mesh mesh, RoadSurface.Face face, double from, double to) {
      if (!Double.isFinite(from) && !Double.isFinite(to)) {
         return true;
      } else {
         RoadGeometry.V center = new RoadGeometry.V(0.0, 0.0, 0.0);

         for (RoadGeometry.V point : face.points()) {
            center = center.add(point);
         }

         center = center.mul(1.0 / (double)face.points().size());
         return inWindow(RoadQueries.horizontal(mesh, center).sample().distance(), from, to);
      }
   }

   private static void paintOnRoad(List<RoadSurface.Face> output, RoadJunction.Paint paint, RoadSurface.Grid paving) {
      List<RoadSurface.Face> pieces = new ArrayList<>();
      RoadSurface.Cut plane = null;
      List<List<RoadGeometry.V>> remaining = new ArrayList<>();
      remaining.add(ccw(paint.points()));

      for (RoadSurface.Cut cut : paving.candidates(paint.points())) {
         if (!paint.points().stream().anyMatch(p -> Math.abs(p.y() - cut.height(p)) > 0.35)) {
            if (plane == null) {
               plane = cut;
            }

            List<List<RoadGeometry.V>> next = new ArrayList<>();

            for (List<RoadGeometry.V> subject : remaining) {
               List<RoadGeometry.V> intersection = subject;

               for (int i = 0; i < cut.points.size(); i++) {
                  intersection = halfPlane(intersection, cut.points.get(i), cut.points.get((i + 1) % cut.points.size()), true);
               }

               if (area(intersection) > 1.0E-9) {
                  pieces.add(new RoadSurface.Face(intersection.stream().map(p -> new RoadGeometry.V(p.x(), cut.height(p), p.z())).toList(), paint.color()));
               }

               next.addAll(subtract(subject, cut.points));
            }

            remaining = next;
            if (next.isEmpty()) {
               break;
            }
         }
      }

      if (remaining.isEmpty() && plane != null && !pieces.isEmpty()) {
         RoadSurface.Cut flat = plane;
         boolean coplanar = pieces.stream().flatMap(f -> f.points().stream()).allMatch(p -> Math.abs(p.y() - flat.height(p)) < 1.0E-7);
         if (coplanar) {
            output.add(new RoadSurface.Face(ccw(paint.points()).stream().map(p -> new RoadGeometry.V(p.x(), flat.height(p), p.z())).toList(), paint.color()));
            return;
         }
      }

      output.addAll(pieces);
   }

   private static void stripe(
      List<RoadSurface.Face> output, RoadGeometry.Sample a, RoadGeometry.Sample b, double oa, double ob, double width, boolean yellow, RoadSurface.Grid joined
   ) {
      stripe(output, a, b, oa, ob, width, yellow, joined, 0.06);
   }

   private static void stripe(
      List<RoadSurface.Face> output,
      RoadGeometry.Sample a,
      RoadGeometry.Sample b,
      double oa,
      double ob,
      double width,
      boolean yellow,
      RoadSurface.Grid joined,
      double tolerance
   ) {
      List<RoadGeometry.V> quad = List.of(a.at(oa - width / 2.0, 0.0), a.at(oa + width / 2.0, 0.0), b.at(ob + width / 2.0, 0.0), b.at(ob - width / 2.0, 0.0));

      for (List<RoadGeometry.V> triangle : List.of(List.of(quad.get(0), quad.get(1), quad.get(2)), List.of(quad.get(0), quad.get(2), quad.get(3)))) {
         for (List<RoadGeometry.V> part : visible(triangle, joined, tolerance)) {
            output.add(new RoadSurface.Face(part, yellow ? 16433462 : 15593186));
         }
      }
   }

   private static void shoulderStripe(
      List<RoadSurface.Face> out,
      RoadGeometry.Sample a,
      RoadGeometry.Sample b,
      int side,
      double oa,
      double ob,
      RoadSurface.Grid joined,
      List<RoadJunction.EdgeZone> zones
   ) {
      TreeSet<Double> cuts = new TreeSet<>(List.of(0.0, 1.0));
      double span = b.distance() - a.distance();

      for (RoadJunction.EdgeZone zone : zones) {
         if (zone.side() == side) {
            for (double d : new double[]{zone.from(), zone.to()}) {
               if (d > a.distance() && d < b.distance()) {
                  cuts.add((d - a.distance()) / span);
               }
            }
         }
      }

      double previous = 0.0;

      for (double t : cuts) {
         double mid = a.distance() + (previous + t) * span / 2.0;
         if (t > previous && zones.stream().noneMatch(z -> z.contains(mid, side))) {
            RoadGeometry.Sample p = blend(a, b, previous);
            RoadGeometry.Sample q = blend(a, b, t);
            stripe(out, p, q, oa + (ob - oa) * previous, oa + (ob - oa) * t, 0.15, false, joined);
         }

         previous = t;
      }
   }

   private static RoadGeometry.Sample blend(RoadGeometry.Sample a, RoadGeometry.Sample b, double t) {
      return new RoadGeometry.Sample(
         a.center().add(b.center().sub(a.center()).mul(t)),
         a.left().add(b.left().sub(a.left()).mul(t)).horizontalUnit(),
         a.distance() + (b.distance() - a.distance()) * t,
         a.halfWidth() + (b.halfWidth() - a.halfWidth()) * t
      );
   }

   private static List<List<RoadGeometry.V>> visible(List<RoadGeometry.V> polygon, RoadSurface.Grid grid, double tolerance) {
      List<List<RoadGeometry.V>> pieces = new ArrayList<>();
      pieces.add(ccw(polygon));
      Set<RoadSurface.Cut> candidates = grid.candidates(polygon);
      long retained = (long)candidates.size() * 128L;

      for (RoadSurface.Cut cut : candidates) {
         RoadPlanningBudget.check();
         if (!polygon.stream().anyMatch(px -> Math.abs(px.y() - cut.height(px)) > tolerance)) {
            List<List<RoadGeometry.V>> next = new ArrayList<>();
            if (grid.limit == Long.MAX_VALUE) {
               for (List<RoadGeometry.V> p : pieces) {
                  next.addAll(subtract(p, cut.points));
               }
            } else {
               long oldBytes = pieces.stream().mapToLong(px -> 128L + (long)px.size() * 64L).sum();
               long nextBytes = 0L;

               for (List<RoadGeometry.V> p : pieces) {
                  for (List<RoadGeometry.V> part : subtract(p, cut.points)) {
                     nextBytes += 128L + (long)part.size() * 64L;
                     grid.temporary(retained + oldBytes + nextBytes);
                     next.add(part);
                  }
               }
            }

            pieces = next;
            if (next.isEmpty()) {
               break;
            }
         }
      }

      pieces.removeIf(px -> !usable(px));
      return pieces;
   }

   private static boolean usable(List<RoadGeometry.V> p) {
      double edge = 0.0;

      for (int i = 0; i < p.size(); i++) {
         edge = Math.max(edge, p.get(i).sub(p.get((i + 1) % p.size())).horizontalLength());
      }

      return area(p) * 2.0 > edge * 1.0E-7;
   }

   public static List<List<RoadGeometry.V>> subtract(List<RoadGeometry.V> subject, List<RoadGeometry.V> clip) {
      List<List<RoadGeometry.V>> result = new ArrayList<>();
      List<RoadGeometry.V> remaining = ccw(subject);
      List<RoadGeometry.V> boundary = ccw(clip);

      for (int i = 0; i < boundary.size() && !remaining.isEmpty(); i++) {
         RoadGeometry.V a = boundary.get(i);
         RoadGeometry.V b = boundary.get((i + 1) % boundary.size());
         if (!(a.sub(b).horizontalLength() < 1.0E-9)) {
            List<RoadGeometry.V> outside = halfPlane(remaining, a, b, false);
            if (area(outside) > 1.0E-10) {
               result.add(outside);
            }

            remaining = halfPlane(remaining, a, b, true);
         }
      }

      return result;
   }

   public static List<RoadGeometry.V> intersect(List<RoadGeometry.V> subject, List<RoadGeometry.V> clip) {
      return intersect(subject, clip, 1.0E-10);
   }

   static List<RoadGeometry.V> intersectVolume(List<RoadGeometry.V> subject, List<RoadGeometry.V> clip) {
      return intersect(subject, clip, 0.0);
   }

   private static List<RoadGeometry.V> intersect(List<RoadGeometry.V> subject, List<RoadGeometry.V> clip, double minimumArea) {
      List<RoadGeometry.V> remaining = ccw(subject);
      List<RoadGeometry.V> boundary = ccw(clip);

      for (int i = 0; i < boundary.size() && !remaining.isEmpty(); i++) {
         RoadGeometry.V a = boundary.get(i);
         RoadGeometry.V b = boundary.get((i + 1) % boundary.size());
         if (a.sub(b).horizontalLength() > 1.0E-9) {
            remaining = halfPlane(remaining, a, b, true, minimumArea == 0.0 ? 0.0 : 1.0E-9);
         }
      }

      return area(remaining) > minimumArea ? remaining : List.of();
   }

   private static List<RoadGeometry.V> halfPlane(List<RoadGeometry.V> input, RoadGeometry.V a, RoadGeometry.V b, boolean inside) {
      return halfPlane(input, a, b, inside, 1.0E-9);
   }

   private static List<RoadGeometry.V> halfPlane(List<RoadGeometry.V> input, RoadGeometry.V a, RoadGeometry.V b, boolean inside, double tolerance) {
      if (input.isEmpty()) {
         return input;
      } else {
         List<RoadGeometry.V> out = new ArrayList<>();
         RoadGeometry.V last = input.get(input.size() - 1);
         double prior = cross(a, b, last) * (double)(inside ? 1 : -1);
         boolean was = prior >= 0.0;

         for (RoadGeometry.V p : input) {
            double value = cross(a, b, p) * (double)(inside ? 1 : -1);
            boolean now = value >= 0.0;
            if (was != now) {
               double t = prior / (prior - value);
               out.add(last.add(p.sub(last).mul(t)));
            }

            if (now) {
               out.add(p);
            }

            last = p;
            prior = value;
            was = now;
         }

         List<RoadGeometry.V> clean = new ArrayList<>();

         for (RoadGeometry.V p : out) {
            if (clean.isEmpty() || p.distance(clean.get(clean.size() - 1)) > tolerance) {
               clean.add(p);
            }
         }

         if (clean.size() > 1 && clean.get(0).distance(clean.get(clean.size() - 1)) <= tolerance) {
            clean.remove(clean.size() - 1);
         }

         return clean;
      }
   }

   private static void wall(List<RoadSurface.Face> output, RoadGeometry.V a, RoadGeometry.V b, double thickness, RoadSurface.Grid neighbors) {
      for (double[] range : exposed(a, b, neighbors)) {
         if (range[1] > range[0] + 1.0E-9) {
            RoadGeometry.V p = a.add(b.sub(a).mul(range[0]));
            RoadGeometry.V q = a.add(b.sub(a).mul(range[1]));
            output.add(
               new RoadSurface.Face(
                  List.of(p, q, q.add(new RoadGeometry.V(0.0, -thickness, 0.0)), p.add(new RoadGeometry.V(0.0, -thickness, 0.0))),
                  12171693,
                  false,
                  RoadSurface.Texture.CONCRETE
               )
            );
         }
      }
   }

   private static List<double[]> exposed(RoadGeometry.V a, RoadGeometry.V b, RoadSurface.Grid neighbors) {
      List<double[]> intervals = new ArrayList<>();
      intervals.add(new double[]{0.0, 1.0});

      for (RoadSurface.Cut cut : neighbors.candidates(List.of(a, b))) {
         if (!(Math.abs(a.y() - cut.height(a)) > 0.025) && !(Math.abs(b.y() - cut.height(b)) > 0.025)) {
            double lo = 0.0;
            double hi = 1.0;
            boolean empty = false;

            for (int i = 0; i < cut.points.size(); i++) {
               RoadGeometry.V p = cut.points.get(i);
               RoadGeometry.V q = cut.points.get((i + 1) % cut.points.size());
               double va = cross(p, q, a);
               double vb = cross(p, q, b);
               double epsilon = 1.0E-7 * Math.max(1.0, q.sub(p).horizontalLength());
               if (Math.abs(va) < epsilon) {
                  va = 0.0;
               }

               if (Math.abs(vb) < epsilon) {
                  vb = 0.0;
               }

               double d = vb - va;
               if (Math.abs(d) < 1.0E-12) {
                  if (va < -1.0E-9) {
                     empty = true;
                     break;
                  }
               } else if (d > 0.0) {
                  lo = Math.max(lo, -va / d);
               } else {
                  hi = Math.min(hi, -va / d);
               }
            }

            if (!empty && !(hi <= lo + 1.0E-9)) {
               List<double[]> next = new ArrayList<>();

               for (double[] range : intervals) {
                  if (lo > range[0] + 1.0E-9) {
                     next.add(new double[]{range[0], Math.min(lo, range[1])});
                  }

                  if (hi < range[1] - 1.0E-9) {
                     next.add(new double[]{Math.max(hi, range[0]), range[1]});
                  }
               }

               intervals = next;
            }
         }
      }

      return intervals;
   }

   public static boolean closedSlotBoundary(RoadGeometry.Mesh mesh, double station, double lateral) {
      RoadGeometry.Sample sample = RoadStructures.sample(mesh, station);
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);

      for (LaneAdditions.Addition added : LaneAdditions.ordered(raw)) {
         if (!Double.isFinite(added.end()) && (double)added.sign() * (station - added.station()) < 0.0 && added.fraction(station) > 1.0E-7) {
            LanePoints.Lane lane = LaneAdditions.lane(raw, station, added.slot());
            RoadProfile.Layout layout = RoadProfile.layout(raw, RoadStructures.sample(raw, station));
            int side = LaneAdditions.side(layout, added.sign());
            double edge = lane.position().sub(sample.center()).dot(sample.left()) - (double)side * lane.width() * added.fraction(station) / 2.0;
            if (Math.abs(lateral - edge) < 0.2) {
               return true;
            }
         }
      }

      if (!LaneDeck.hasOpenings(mesh)) {
         return false;
      } else {
         for (int slot : LaneAdditions.slots(raw, station)) {
            if (mesh.settings().options().lanePoints().cuts().stream().anyMatch(c -> c.temporary() && c.lane() == slot && c.removed(station) > 0.999)
               || LaneClosureWarnings.covers(mesh, station, slot)) {
               LanePoints.Lane lane = LanePoints.lane(raw, station, slot);
               double center = lane.position().sub(sample.center()).dot(sample.left());
               if (Math.abs(Math.abs(lateral - center) - lane.width() / 2.0) < 0.2) {
                  return true;
               }
            }
         }

         return LaneDeck.present(mesh, sample, lateral - 0.14, 0.0) != LaneDeck.present(mesh, sample, lateral + 0.14, 0.0);
      }
   }

   public static double edgeOffset(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, int side) {
      double normal = (double)side * Math.max(0.08, sample.halfWidth() - 0.3);
      RoadProfile.Layout layout = RoadProfile.layout(mesh, sample);
      if (layout.cycleWidth() > 0.01 && (layout.catalog().twoWay() || layout.outside() == side)) {
         normal = (double)side * Math.max(0.08, sample.halfWidth() - Math.max(0.3, RoadProfile.curbExtent(layout, sample, side) + 0.16));
      }

      LanePoints.Link link = mesh.settings().options().lanePoints().link();
      if (link == null) {
         return layout.catalog().type() == RoadProfile.Type.ORDINARY && layout.cycleWidth() < 0.01 && layout.curbWidth() < 0.01 ? layout.outer(side) : normal;
      } else if (mesh.settings().style().connectorRamp()) {
         return layout.outer(side);
      } else if (layout.catalog().type() == RoadProfile.Type.ORDINARY && !(layout.cycleWidth() > 0.01) && !(layout.curbWidth() > 0.01)) {
         double span = Math.min(mesh.length() / 2.0, Math.max(16.0, link.options().transition()));
         double distance = Math.min(sample.distance(), mesh.length() - sample.distance());
         double weight = 1.0 - RoadGeometry.Settings.smooth(Math.min(1.0, distance / Math.max(0.01, span)));
         return normal + (layout.outer(side) - normal) * weight;
      } else {
         return normal;
      }
   }

   private static double edgeAForCycle(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, int side, RoadsideBypass bypass) {
      return edgeOffset(mesh, sample, side) + (double)side * (bypass.offset(sample.distance(), side) - 0.075);
   }

   private static void boundaryStripe(
      List<RoadSurface.Face> out,
      RoadGeometry.Sample a,
      RoadGeometry.Sample b,
      int side,
      RoadRailJoin neighbors,
      RoadRailJoin paintNeighbors,
      List<RoadJunction.EdgeZone> zones,
      double oa,
      double ob
   ) {
      RoadGeometry.V a0 = a.at(oa - 0.06, 0.0);
      RoadGeometry.V a1 = a.at(oa + 0.06, 0.0);
      RoadGeometry.V b0 = b.at(ob - 0.06, 0.0);
      RoadGeometry.V b1 = b.at(ob + 0.06, 0.0);
      RoadGeometry.V first = a.at((double)side * (a.halfWidth() - 0.34), 0.0);
      RoadGeometry.V last = b.at((double)side * (b.halfWidth() - 0.34), 0.0);
      RoadGeometry.V delta = last.sub(first);
      double inset = 0.34;
      List<double[]> ranges = new ArrayList<>();
      double length = delta.dot(delta);
      if (!(length < 1.0E-12)) {
         RoadGeometry.V outside = first.add(last).mul(0.5).add(a.left().add(b.left()).horizontalUnit().mul((double)side * 0.4));

         for (RoadRailJoin.Span span : neighbors.exposed(first, last, outside, inset)) {
            ranges.add(new double[]{span.a().sub(first).dot(delta) / length, span.b().sub(first).dot(delta) / length});
         }

         RoadGeometry.V pa = a.at(oa, 0.0);
         RoadGeometry.V pb = b.at(ob, 0.0);
         RoadGeometry.V pd = pb.sub(pa);
         double pl = pd.dot(pd);
         if (!(pl < 1.0E-12)) {
            List<RoadRailJoin.Span> painted = paintNeighbors.exposed(
               pa,
               pb,
               pa.add(pb).mul(0.5).add(a.left().add(b.left()).horizontalUnit().mul((double)side * 0.4)),
               (a.halfWidth() - Math.abs(oa) + b.halfWidth() - Math.abs(ob)) / 2.0
            );
            ArrayList<double[]> common = new ArrayList<>();

            for (double[] r : ranges) {
               for (RoadRailJoin.Span span : painted) {
                  double lo = Math.max(r[0], span.a().sub(pa).dot(pd) / pl);
                  double hi = Math.min(r[1], span.b().sub(pa).dot(pd) / pl);
                  if (hi > lo + 1.0E-8) {
                     common.add(new double[]{lo, hi});
                  }
               }
            }

            ranges = common;

            for (RoadJunction.EdgeZone zone : zones) {
               if (zone.side() == side) {
                  double lo = Math.max(0.0, (zone.from() - a.distance()) / (b.distance() - a.distance()));
                  double hi = Math.min(1.0, (zone.to() - a.distance()) / (b.distance() - a.distance()));
                  if (!(hi <= lo)) {
                     List<double[]> next = new ArrayList<>();

                     for (double[] r : ranges) {
                        if (lo > r[0] + 1.0E-9) {
                           next.add(new double[]{r[0], Math.min(lo, r[1])});
                        }

                        if (hi < r[1] - 1.0E-9) {
                           next.add(new double[]{Math.max(hi, r[0]), r[1]});
                        }
                     }

                     ranges = next;
                  }
               }
            }

            for (double[] r : ranges) {
               if (!(r[1] <= r[0] + 1.0E-9)) {
                  List<RoadGeometry.V> polygon = List.of(
                     a0.add(b0.sub(a0).mul(r[0])), a1.add(b1.sub(a1).mul(r[0])), a1.add(b1.sub(a1).mul(r[1])), a0.add(b0.sub(a0).mul(r[1]))
                  );
                  out.add(new RoadSurface.Face(polygon, 15593186));
               }
            }
         }
      }
   }

   private static double cross(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V p) {
      return (b.x() - a.x()) * (p.z() - a.z()) - (b.z() - a.z()) * (p.x() - a.x());
   }

   private static double signedArea(List<RoadGeometry.V> p) {
      if (p.size() < 3) {
         return 0.0;
      } else {
         double a = 0.0;

         for (int i = 1; i < p.size() - 1; i++) {
            a += cross(p.get(0), p.get(i), p.get(i + 1));
         }

         return a / 2.0;
      }
   }

   public static double area(List<RoadGeometry.V> p) {
      return Math.abs(signedArea(p));
   }

   private static List<RoadGeometry.V> ccw(List<RoadGeometry.V> p) {
      if (signedArea(p) >= 0.0) {
         return p;
      } else {
         List<RoadGeometry.V> r = new ArrayList<>(p);
         Collections.reverse(r);
         return r;
      }
   }

   private RoadSurface() {
   }

   private static record Cut(List<RoadGeometry.V> points, double minX, double minZ, double maxX, double maxZ) {
      Cut(List<RoadGeometry.V> p) {
         this(
            p,
            p.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(),
            p.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow(),
            p.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(),
            p.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()
         );
      }

      double height(RoadGeometry.V p) {
         RoadGeometry.V a = this.points.get(0);
         RoadGeometry.V b = this.points.get(1).sub(a);
         RoadGeometry.V c = this.points.get(2).sub(a);
         RoadGeometry.V q = p.sub(a);
         double det = b.x() * c.z() - b.z() * c.x();
         return a.y() + ((q.x() * c.z() - q.z() * c.x()) * b.y() + (b.x() * q.z() - b.z() * q.x()) * c.y()) / det;
      }

      boolean overlaps(RoadSurface.Cut b) {
         return this.maxX >= b.minX - 1.0E-8 && b.maxX >= this.minX - 1.0E-8 && this.maxZ >= b.minZ - 1.0E-8 && b.maxZ >= this.minZ - 1.0E-8;
      }
   }

   public static record Face(List<RoadGeometry.V> points, int color, boolean emissive, RoadSurface.Texture texture, List<RoadSurface.UV> uv) {
      public Face(List<RoadGeometry.V> points, int color, boolean emissive, RoadSurface.Texture texture) {
         this(points, color, emissive, texture, List.of());
      }

      public Face(List<RoadGeometry.V> points, int color) {
         this(points, color, false, RoadSurface.Texture.PLAIN);
      }

      public Face(List<RoadGeometry.V> points, int color, boolean emissive) {
         this(points, color, emissive, RoadSurface.Texture.PLAIN);
      }
   }

   public static record Geometry(List<RoadSurface.Face> pavement, List<RoadSurface.Face> markings) {
   }

   private static final class Grid {
      final Map<Long, List<RoadSurface.Cut>> cells = new HashMap<>();
      final long limit;
      long bytes = 4096L;

      Grid(List<RoadGeometry.Mesh> meshes) {
         this(meshes, Long.MAX_VALUE);
      }

      Grid(List<RoadGeometry.Mesh> meshes, long limit) {
         this.limit = limit;

         for (RoadGeometry.Mesh original : meshes) {
            RoadGeometry.Mesh mesh = RoadRenderMesh.pavement(original);

            for (int i = 1; i < mesh.samples().size(); i++) {
               RoadGeometry.Sample a = mesh.samples().get(i - 1);
               RoadGeometry.Sample b = mesh.samples().get(i);

               for (LaneDeck.Strip strip : LaneDeck.strips(mesh, a, b)) {
                  this.add(List.of(strip.al(), strip.ar(), strip.br()));
                  this.add(List.of(strip.al(), strip.br(), strip.bl()));
               }
            }
         }
      }

      void add(List<RoadGeometry.V> points) {
         if (!(Math.abs(JunctionPaint.area(points)) < 1.0E-9)) {
            if (this.limit != Long.MAX_VALUE) {
               this.charge(1024L);
            }

            RoadSurface.Cut c = new RoadSurface.Cut(RoadSurface.ccw(points));

            for (int x = cell(c.minX); x <= cell(c.maxX); x++) {
               for (int z = cell(c.minZ); z <= cell(c.maxZ); z++) {
                  if (this.limit != Long.MAX_VALUE) {
                     RoadPlanningBudget.check();
                     this.charge((long)(32 + (this.cells.containsKey(key(x, z)) ? 0 : 256)));
                  }

                  this.cells.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(c);
               }
            }
         }
      }

      void charge(long amount) {
         this.temporary(amount);
         this.bytes += amount;
      }

      void temporary(long amount) {
         if (amount < 0L || amount > this.limit - this.bytes) {
            throw new IllegalArgumentException("LOD surface workspace budget exceeded");
         }
      }

      Set<RoadSurface.Cut> candidates(List<RoadGeometry.V> points) {
         RoadSurface.Cut box = new RoadSurface.Cut(points);
         Set<RoadSurface.Cut> found = new LinkedHashSet<>();

         for (int x = cell(box.minX); x <= cell(box.maxX); x++) {
            for (int z = cell(box.minZ); z <= cell(box.maxZ); z++) {
               for (RoadSurface.Cut c : this.cells.getOrDefault(key(x, z), List.of())) {
                  if (c.overlaps(box)) {
                     if (this.limit != Long.MAX_VALUE) {
                        this.temporary((long)(found.size() + 1) * 128L);
                     }

                     found.add(c);
                  }
               }
            }
         }

         return found;
      }

      static int cell(double v) {
         return (int)Math.floor(v / 8.0);
      }

      static long key(int x, int z) {
         return (long)x << 32 ^ (long)z & 4294967295L;
      }
   }

   public static final class PaintMask {
      private final RoadSurface.Grid grid = new RoadSurface.Grid(List.of());

      public PaintMask(List<List<RoadGeometry.V>> polygons) {
         polygons.forEach(this.grid::add);
      }

      public List<List<RoadGeometry.V>> clip(List<RoadGeometry.V> face) {
         return RoadSurface.visible(face, this.grid, 0.35);
      }

      public List<List<RoadGeometry.V>> clipXZ(List<RoadGeometry.V> face) {
         return RoadSurface.visible(face, this.grid, Double.POSITIVE_INFINITY);
      }
   }

   @FunctionalInterface
   public interface SlabSink {
      void accept(List<RoadGeometry.V> var1, double var2, int var4);
   }

   public static enum Texture {
      PLAIN,
      WALK_TACTILE,
      CONCRETE,
      METAL,
      SOIL,
      LEAVES,
      SIGNAL_HOUSING,
      SIGNAL_GREEN,
      SIGNAL_RED,
      SIGNAL_AMBER,
      SIGNAL_ATLAS,
      WALK_STONE_BRICKS,
      WALK_BRICKS,
      WALK_ANDESITE,
      WALK_DIORITE,
      WALK_GRANITE,
      WALK_SMOOTH_STONE,
      NOISE_BARRIER,
      NOISE_BARRIER_GLASS,
      OAK_LOG,
      BIRCH_LOG,
      SPRUCE_LOG,
      CHERRY_LOG,
      OAK_LEAVES,
      BIRCH_LEAVES,
      SPRUCE_LEAVES,
      CHERRY_LEAVES,
      FLOWERS;
   }

   public static record UV(double u, double v) {
   }

   public static final class WindowContext {
      private final RoadGeometry.Mesh mesh;
      private final List<RoadGeometry.Mesh> higherPriority;
      private final List<RoadGeometry.Mesh> neighbors;
      private final RoadsideBypass bypass;
      private final RoadSurface.Grid owners;
      private final RoadSurface.Grid joined;
      private final RoadSurface.Grid dividers;
      private final RoadSurface.Grid defaultDividers;
      private RoadSurface.Grid holes;
      private final RoadRailJoin edgeJoin;
      private final RoadRailJoin paintEdgeJoin;
      private final List<RoadSignals.Approach> approaches;
      private final List<RoadGeometry.Sample> samples;
      private final List<RoadGeometry.Sample> deck;
      private final List<RoadJunction.Zone> junctionZones;
      private final List<RoadJunction.EdgeZone> edgeZones;
      private final List<LaneRampPaint.Auxiliary> auxiliary;
      private final List<RoadSurface.Face> globalPaint;
      private final List<RoadSurface.Face> bypassPaint;
      private final List<RoadSurface.Face> stopBars;
      private final RoadSurface.PaintMask railwayStops;

      public WindowContext(RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> higherPriority, List<RoadGeometry.Mesh> neighbors, RoadsideBypass bypass) {
         this.mesh = mesh;
         this.higherPriority = List.copyOf(higherPriority);
         this.neighbors = List.copyOf(neighbors);
         this.bypass = bypass;
         this.owners = new RoadSurface.Grid(higherPriority);
         this.joined = new RoadSurface.Grid(neighbors);
         this.edgeJoin = RoadRailJoin.paintBoundary(mesh, neighbors.stream().map(n -> new RoadRailJoin.Neighbor(n, higherPriority.contains(n))).toList());
         this.paintEdgeJoin = RoadRailJoin.paint(mesh, neighbors.stream().map(n -> new RoadRailJoin.Neighbor(n, higherPriority.contains(n))).toList());
         List<RoadGeometry.Mesh> dividerCuts = new ArrayList<>(
            higherPriority.stream().filter(other -> mesh.settings().style().ramp() || !other.settings().style().ramp()).toList()
         );
         List<RoadGeometry.Mesh> crossings = RoadJunction.intersections(mesh, neighbors);
         dividerCuts.addAll(crossings);

         for (RoadGeometry.Mesh neighbor : neighbors) {
            if (RailwayCrossings.any(mesh, neighbor)) {
               dividerCuts.add(neighbor);
            }
         }

         this.dividers = new RoadSurface.Grid(dividerCuts);
         this.defaultDividers = new RoadSurface.Grid(dividerCuts);

         for (RoadGeometry.Mesh neighborx : neighbors) {
            for (List<RoadGeometry.V> cut : RoadJunction.closedGuidance(neighborx, mesh, neighbors)) {
               this.defaultDividers.add(cut);
            }

            for (List<RoadGeometry.V> cut : RoadJunction.closedGuidance(mesh, neighborx, neighbors)) {
               this.defaultDividers.add(cut);
            }
         }

         for (RoadGeometry.Mesh neighborx : neighbors) {
            if (LaneMerge.linkedTo(neighborx, mesh) || LaneMerge.linkedTo(mesh, neighborx)) {
               for (int i = 1; i < neighborx.samples().size(); i++) {
                  RoadGeometry.Sample a = neighborx.samples().get(i - 1);
                  RoadGeometry.Sample b = neighborx.samples().get(i);
                  double da = a.center().y() - RoadQueries.horizontal(mesh, a.center()).sample().center().y();
                  double db = b.center().y() - RoadQueries.horizontal(mesh, b.center()).sample().center().y();
                  if (!(Math.abs(da) < 0.06) || !(Math.abs(db) < 0.06)) {
                     for (LaneDeck.Strip strip : LaneDeck.strips(neighborx, a, b)) {
                        this.defaultDividers.add(List.of(strip.al(), strip.ar(), strip.br()));
                        this.defaultDividers.add(List.of(strip.al(), strip.br(), strip.bl()));
                     }
                  }
               }
            }
         }

         for (List<RoadGeometry.V> cut : RoadJunction.terminalCuts(mesh)) {
            this.dividers.add(cut);
            this.defaultDividers.add(cut);
         }

         this.approaches = RoadSignals.approaches(mesh, neighbors);

         for (List<RoadGeometry.V> cut : RoadSignals.paintCuts(this.approaches)) {
            this.dividers.add(cut);
            this.defaultDividers.add(cut);
         }

         this.samples = RoadSurface.markingSamples(mesh);
         this.junctionZones = RoadJunction.dividerZones(mesh, neighbors);
         this.edgeZones = RoadJunction.edgeZones(mesh, neighbors);
         this.auxiliary = LaneRampPaint.auxiliary(mesh, neighbors);
         this.railwayStops = new RoadSurface.PaintMask(RailwayCrossings.paintCuts(mesh));
         this.globalPaint = new ArrayList<>();
         List<RoadGeometry.Mesh> union = new ArrayList<>(neighbors);
         union.add(mesh);
         RoadSurface.Grid paving = new RoadSurface.Grid(union);

         for (RoadJunction.Paint paint : RoadJunction.markings(mesh, higherPriority, neighbors, true)) {
            if (mesh.settings().options().lanePoints().link() != null) {
               for (List<RoadGeometry.V> poly : RoadSurface.visible(paint.points(), this.owners, 0.025)) {
                  RoadSurface.paintOnRoad(this.globalPaint, new RoadJunction.Paint(poly, paint.color()), paving);
               }
            } else {
               RoadSurface.paintOnRoad(this.globalPaint, paint, paving);
            }
         }

         for (RoadJunction.Paint paintx : RoadJunction.arrows(mesh, neighbors, crossings)) {
            if (mesh.settings().options().lanePoints().link() == null) {
               RoadSurface.paintOnRoad(this.globalPaint, paintx, paving);
            } else {
               for (List<RoadGeometry.V> poly : RoadSurface.visible(paintx.points(), this.owners, 0.025)) {
                  RoadSurface.paintOnRoad(this.globalPaint, new RoadJunction.Paint(poly, paintx.color()), paving);
               }
            }
         }

         for (RoadJunction.Paint warning : LaneClosureWarnings.paint(mesh, neighbors)) {
            RoadSurface.paintOnRoad(this.globalPaint, warning, paving);
         }

         this.stopBars = new ArrayList<>();

         for (RoadJunction.Paint line : RailwayCrossings.stopLines(mesh)) {
            RoadSurface.paintOnRoad(this.stopBars, line, paving);
         }

         if (LaneDeck.hasOpenings(mesh)) {
            this.holes = new RoadSurface.Grid(List.of());
            List<RoadGeometry.Sample> points = mesh.samples();

            for (int ix = 1; ix < points.size(); ix++) {
               for (List<RoadGeometry.V> q : LaneDeck.holeQuads(mesh, points.get(ix - 1), points.get(ix))) {
                  this.holes.add(List.of(q.get(0), q.get(1), q.get(2)));
                  this.holes.add(List.of(q.get(0), q.get(2), q.get(3)));
               }
            }
         }

         this.deck = RoadRenderMesh.pavement(mesh).samples();
         this.bypassPaint = bypass.markings();
      }

      public long bytes() {
         return 4096L
            + (long)(this.samples.size() + this.deck.size()) * 64L
            + (long)(this.globalPaint.size() + this.bypassPaint.size()) * 512L
            + this.neighbors.stream().mapToLong(m -> (long)m.samples().size() * 256L).sum();
      }

      public RoadSurface.Geometry build(double from, double to) {
         return this.build(from, to, null);
      }

      public RoadSurface.Geometry build(double from, double to, RoadSurface.SlabSink sink) {
         List<RoadSurface.Face> pavement = new ArrayList<>();
         List<RoadSurface.Face> markings = new ArrayList<>();

         for (int i = 1; i < this.samples.size(); i++) {
            RoadPlanningBudget.check();
            RoadGeometry.Sample a = this.samples.get(i - 1);
            RoadGeometry.Sample b = this.samples.get(i);
            if (RoadSurface.inWindow((a.distance() + b.distance()) / 2.0, from, to)) {
               RoadGeometry.V l = a.at(a.halfWidth(), 0.0);
               RoadGeometry.V r = a.at(-a.halfWidth(), 0.0);
               RoadGeometry.V ll = b.at(b.halfWidth(), 0.0);
               RoadGeometry.V rr = b.at(-b.halfWidth(), 0.0);
               if (this.mesh.settings().style() != RoadGeometry.Style.UNMARKED
                  && this.mesh.settings().style() != RoadGeometry.Style.TOLL
                  && this.mesh.settings().style() != RoadGeometry.Style.RAILBED) {
                  for (int side : new int[]{-1, 1}) {
                     RoadProfile.Layout edgeLayout = RoadProfile.layout(this.mesh, a);
                     boolean curb = RoadProfile.modern(this.mesh.settings().style())
                        && edgeLayout.curbWidth() > 0.0
                        && (edgeLayout.catalog().twoWay() || edgeLayout.outside() == side);
                     double edgeA = RoadSurface.edgeOffset(this.mesh, a, side) + (double)side * this.bypass.offset(a.distance(), side);
                     double edgeB = RoadSurface.edgeOffset(this.mesh, b, side) + (double)side * this.bypass.offset(b.distance(), side);
                     if ((!curb || edgeLayout.cycleWidth() > 0.01)
                        && !LaneDeck.outerOpening(this.mesh, (a.distance() + b.distance()) / 2.0, side)
                        && !RoadSurface.overrideLine(markings, this.mesh, a, b, "edge:" + side, edgeA, edgeB, this.joined)) {
                        RoadSurface.boundaryStripe(markings, a, b, side, this.edgeJoin, this.paintEdgeJoin, this.edgeZones, edgeA, edgeB);
                     }
                  }

                  RoadGeometry.Style style = this.mesh.settings().style();
                  if (RoadProfile.modern(style)) {
                     RoadProfile.Layout la = RoadProfile.layout(this.mesh, a);
                     RoadProfile.Layout lb = RoadProfile.layout(this.mesh, b);
                     boolean dash = RoadSurface.painted(this.mesh, (a.distance() + b.distance()) / 2.0);
                     RoadProfile.Layout middle = RoadProfile.layout(this.mesh, RoadStructures.sample(this.mesh, (a.distance() + b.distance()) / 2.0));
                     RoadProfile.Median median = middle.catalog().median();
                     if (median == RoadProfile.Median.DOUBLE_YELLOW && middle.median() < 0.12) {
                        if (!RoadSurface.overrideLine(
                           markings,
                           this.mesh,
                           a,
                           b,
                           "center:-1",
                           la.medianCenter() - (0.14 + la.median() / 2.0),
                           lb.medianCenter() - (0.14 + lb.median() / 2.0),
                           this.dividers
                        )) {
                           RoadSurface.stripe(
                              markings,
                              a,
                              b,
                              la.medianCenter() - (0.14 + la.median() / 2.0),
                              lb.medianCenter() - (0.14 + lb.median() / 2.0),
                              0.1,
                              true,
                              this.dividers
                           );
                        }

                        if (!RoadSurface.overrideLine(
                           markings,
                           this.mesh,
                           a,
                           b,
                           "center:1",
                           la.medianCenter() + 0.14 + la.median() / 2.0,
                           lb.medianCenter() + 0.14 + lb.median() / 2.0,
                           this.dividers
                        )) {
                           RoadSurface.stripe(
                              markings,
                              a,
                              b,
                              la.medianCenter() + 0.14 + la.median() / 2.0,
                              lb.medianCenter() + 0.14 + lb.median() / 2.0,
                              0.1,
                              true,
                              this.dividers
                           );
                        }
                     } else if (median == RoadProfile.Median.DASHED_YELLOW) {
                        if (!RoadSurface.overrideLine(markings, this.mesh, a, b, "center:0", la.medianCenter(), lb.medianCenter(), this.dividers) && dash) {
                           RoadSurface.stripe(markings, a, b, la.medianCenter(), lb.medianCenter(), 0.12, true, this.dividers);
                        }
                     } else if (middle.median() >= 0.12 || median == RoadProfile.Median.RAIL || median == RoadProfile.Median.GREEN) {
                        for (int sidex : new int[]{-1, 1}) {
                           if (!RoadSurface.overrideLine(
                              markings,
                              this.mesh,
                              a,
                              b,
                              "median:" + sidex,
                              la.medianCenter() + (double)sidex * (la.median() / 2.0 + 0.12),
                              lb.medianCenter() + (double)sidex * (lb.median() / 2.0 + 0.12),
                              this.dividers
                           )) {
                              RoadSurface.stripe(
                                 markings,
                                 a,
                                 b,
                                 la.medianCenter() + (double)sidex * (la.median() / 2.0 + 0.12),
                                 lb.medianCenter() + (double)sidex * (lb.median() / 2.0 + 0.12),
                                 0.12,
                                 false,
                                 this.dividers
                              );
                           }
                        }
                     }

                     for (int sidexx : la.outsideSides()) {
                        if (la.cycleWidth() > 0.01 && Math.max(this.bypass.offset(a.distance(), sidexx), this.bypass.offset(b.distance(), sidexx)) > 1.0E-7) {
                           RoadSurface.stripe(
                              markings,
                              a,
                              b,
                              la.outer(sidexx) + (double)sidexx * this.bypass.offset(a.distance(), sidexx),
                              lb.outer(sidexx) + (double)sidexx * this.bypass.offset(b.distance(), sidexx),
                              0.15,
                              false,
                              this.joined
                           );
                        }

                        boolean extra = LaneRampPaint.auxiliaryAt(this.mesh, a, b, sidexx, this.auxiliary);
                        if (!extra
                           && (la.shoulderWidth() > 0.0 || la.cycleWidth() > 0.0)
                           && !RoadSurface.overrideLine(markings, this.mesh, a, b, "shoulder:" + sidexx, la.outer(sidexx), lb.outer(sidexx), this.joined)) {
                           RoadSurface.boundaryStripe(
                              markings, a, b, sidexx, this.edgeJoin, this.paintEdgeJoin, this.edgeZones, la.outer(sidexx), lb.outer(sidexx)
                           );
                        }

                        if (la.cycleWidth() > 0.0 && RoadTransitions.greenCycle(this.mesh, a)) {
                           double separator = RoadStreetscape.separatorWidth(this.mesh.settings().options());
                           double ia = la.outer(sidexx) + (double)sidexx * (separator + 0.075 + this.bypass.offset(a.distance(), sidexx));
                           double ib = lb.outer(sidexx) + (double)sidexx * (separator + 0.075 + this.bypass.offset(b.distance(), sidexx));
                           double ea = RoadSurface.edgeAForCycle(this.mesh, a, sidexx, this.bypass);
                           double eb = RoadSurface.edgeAForCycle(this.mesh, b, sidexx, this.bypass);
                           if ((ea - ia) * (double)sidexx > 0.0 && (eb - ib) * (double)sidexx > 0.0) {
                              List<RoadGeometry.V> quad = List.of(a.at(ia, 0.0), a.at(ea, 0.0), b.at(eb, 0.0), b.at(ib, 0.0));

                              for (List<RoadGeometry.V> triangle : List.of(
                                 List.of(quad.get(0), quad.get(1), quad.get(2)), List.of(quad.get(0), quad.get(2), quad.get(3))
                              )) {
                                 for (List<RoadGeometry.V> part : RoadSurface.visible(triangle, this.joined, 0.06)) {
                                    markings.add(new RoadSurface.Face(part, 5468001));
                                 }
                              }
                           }
                        }
                     }

                     if (this.mesh.settings().options().streetscape().parking() && !RoadStreetscape.underground(this.mesh, (a.distance() + b.distance()) / 2.0)
                        )
                      {
                        for (int sidexx : la.outsideSides()) {
                           if (!RoadStreetscape.raised(this.mesh, a, sidexx)
                              && !RoadStreetscape.cut(this.mesh, a, sidexx)
                              && !(la.cycleWidth() < 1.0)
                              && !(lb.cycleWidth() < 1.0)) {
                              double phase = this.mesh.settings().options().ends().paintPhase();
                              double station = Math.ceil((a.distance() + phase) / RoadStreetscape.parkingLength()) * RoadStreetscape.parkingLength() - phase;
                              if (station >= a.distance() - 1.0E-7 && station < b.distance() - 1.0E-7) {
                                 RoadGeometry.Sample p = RoadStructures.sample(this.mesh, station);
                                 RoadProfile.Layout parkingLayout = RoadProfile.layout(this.mesh, p);
                                 RoadGeometry.V x = p.at(parkingLayout.outer(sidexx), -0.012);
                                 RoadGeometry.V y = p.at(
                                    (double)sidexx
                                       * (p.halfWidth() - (parkingLayout.curbWidth() > 0.0 ? RoadProfile.curbExtent(parkingLayout, p, sidexx) : 0.3)),
                                    -0.012
                                 );
                                 ArrayList<RoadSurface.Face> faces = new ArrayList<>();
                                 JunctionPaint.line(faces, x, y, 0.12, 15658718);

                                 for (RoadSurface.Face face : faces) {
                                    List<List<RoadGeometry.V>> remaining = List.of(face.points());

                                    for (RoadSurface.Cut cut : this.joined.candidates(face.points())) {
                                       ArrayList<List<RoadGeometry.V>> next = new ArrayList<>();

                                       for (List<RoadGeometry.V> poly : remaining) {
                                          next.addAll(RoadSurface.subtract(poly, cut.points()));
                                       }

                                       remaining = next;
                                    }

                                    for (List<RoadGeometry.V> poly : remaining) {
                                       markings.add(new RoadSurface.Face(poly, face.color()));
                                    }
                                 }
                              }
                           }
                        }
                     }

                     if ((!style.ramp() || a.halfWidth() + b.halfWidth() >= this.mesh.settings().width() * 0.85)
                        && this.junctionZones.stream().noneMatch(zone -> zone.contains((a.distance() + b.distance()) / 2.0))) {
                        List<Double> aa = la.dividers();
                        List<Double> bb = lb.dividers();

                        for (int j = 0; j < Math.min(aa.size(), bb.size()); j++) {
                           if ((
                                 aa.get(j) > la.motorMin() + 0.12 && aa.get(j) < la.motorMax() - 0.12
                                    || bb.get(j) > lb.motorMin() + 0.12 && bb.get(j) < lb.motorMax() - 0.12
                                    || RoadSurface.closedSlotBoundary(this.mesh, (a.distance() + b.distance()) / 2.0, (aa.get(j) + bb.get(j)) / 2.0)
                              )
                              && !RoadSurface.overrideLine(markings, this.mesh, a, b, "divider:" + j, aa.get(j), bb.get(j), this.dividers)
                              && (
                                 dash
                                    || RoadSurface.closedSlotBoundary(this.mesh, (a.distance() + b.distance()) / 2.0, (aa.get(j) + bb.get(j)) / 2.0)
                                    || RoadSignals.solid(
                                       this.approaches,
                                       (a.distance() + b.distance()) / 2.0,
                                       (aa.get(j) + bb.get(j) - la.medianCenter() - lb.medianCenter()) / 2.0
                                    )
                              )) {
                              RoadSurface.stripe(markings, a, b, aa.get(j), bb.get(j), 0.12, false, this.defaultDividers, 0.35);
                           }
                        }
                     }
                  } else {
                     if (style == RoadGeometry.Style.TWO_LANE || style == RoadGeometry.Style.FOUR_LANE) {
                        RoadSurface.stripe(markings, a, b, -0.14, -0.14, 0.1, true, this.dividers);
                        RoadSurface.stripe(markings, a, b, 0.14, 0.14, 0.1, true, this.dividers);
                     }

                     if (RoadSurface.painted(this.mesh, (a.distance() + b.distance()) / 2.0)
                        && this.junctionZones.stream().noneMatch(zone -> zone.contains((a.distance() + b.distance()) / 2.0))) {
                        if (style == RoadGeometry.Style.FOUR_LANE) {
                           RoadSurface.stripe(markings, a, b, a.halfWidth() / 2.0, b.halfWidth() / 2.0, 0.12, false, this.dividers);
                           RoadSurface.stripe(markings, a, b, -a.halfWidth() / 2.0, -b.halfWidth() / 2.0, 0.12, false, this.dividers);
                        } else if (style == RoadGeometry.Style.ONE_WAY
                           || style == RoadGeometry.Style.RAMP_TWO && a.halfWidth() + b.halfWidth() >= this.mesh.settings().width() * 0.85) {
                           RoadSurface.stripe(markings, a, b, 0.0, 0.0, 0.12, false, this.dividers);
                        }
                     }
                  }
               }
            }
         }

         RoadSurface.pavement(this.mesh, this.deck, this.owners, this.joined, from, to, sink, pavement);
         if (this.mesh.settings().options().lanePoints().link() != null && !this.higherPriority.isEmpty()) {
            ArrayList<RoadSurface.Face> clipped = new ArrayList<>();

            for (RoadSurface.Face face : markings) {
               for (List<RoadGeometry.V> poly : RoadSurface.visible(face.points(), this.owners, 0.025)) {
                  clipped.add(new RoadSurface.Face(poly, face.color(), face.emissive(), face.texture()));
               }
            }

            markings = clipped;
         }

         for (RoadSurface.Face face : this.globalPaint) {
            if (RoadSurface.inWindow(this.mesh, face, from, to)) {
               markings.add(face);
            }
         }

         if (this.holes != null) {
            ArrayList<RoadSurface.Face> clipped = new ArrayList<>();

            for (RoadSurface.Face facex : markings) {
               for (List<RoadGeometry.V> poly : RoadSurface.visible(facex.points(), this.holes, 0.03)) {
                  clipped.add(new RoadSurface.Face(poly, facex.color(), facex.emissive(), facex.texture()));
               }
            }

            markings = clipped;
         }

         for (RoadSurface.Face facex : this.bypassPaint) {
            if (RoadSurface.inWindow(this.mesh, facex, from, to)) {
               markings.add(facex);
            }
         }

         if (!this.mesh.settings().options().levelCrossings().isEmpty()) {
            markings = RoadSurface.clipCrossingPaint(markings, this.railwayStops);

            for (RoadSurface.Face facexx : this.stopBars) {
               if (RoadSurface.inWindow(this.mesh, facexx, from, to)) {
                  markings.add(facexx);
               }
            }
         }

         return new RoadSurface.Geometry(List.copyOf(pavement), List.copyOf(markings));
      }
   }
}
