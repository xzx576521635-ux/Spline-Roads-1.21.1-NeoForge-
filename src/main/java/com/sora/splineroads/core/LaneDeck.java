package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

public final class LaneDeck {
   private static final WeakIdentityCache<RoadGeometry.Mesh, List<Integer>> SLOT_ORDER = new WeakIdentityCache<>(256, 8192L, List::size);

   public static double edge(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, int side) {
      return (double)side * sample.halfWidth();
   }

   public static boolean hasOpenings(RoadGeometry.Mesh mesh) {
      return !mesh.settings().options().streetscape().crossings().isEmpty()
         || mesh.settings().options().lanePoints().cuts().stream().anyMatch(LaneSections.Cut::temporary);
   }

   private static List<Integer> slots(RoadGeometry.Mesh mesh) {
      return SLOT_ORDER.get(mesh, LaneDeck::slotOrder);
   }

   private static List<Integer> slotOrder(RoadGeometry.Mesh mesh) {
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);
      double d = (raw.first().distance() + raw.last().distance()) / 2.0;
      ArrayList<Integer> out = new ArrayList<>();

      for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
         if (cut.temporary() && !out.contains(cut.lane())) {
            out.add(cut.lane());
         }
      }

      RoadGeometry.Sample at = RoadStructures.sample(raw, d);
      out.sort(Comparator.comparingDouble(slot -> LanePoints.lane(raw, d, slot).position().sub(at.center()).dot(at.left())));
      return List.copyOf(out);
   }

   private static List<LaneDeck.Span> holes(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample) {
      double probe = sample.distance();
      if (Math.abs(probe - mesh.first().distance()) < 1.0E-7) {
         probe += 1.0E-5;
      }

      if (Math.abs(probe - mesh.last().distance()) < 1.0E-7) {
         probe -= 1.0E-5;
      }

      return holes(mesh, sample, probe);
   }

   private static List<LaneDeck.Span> holes(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, double interval) {
      return holes(mesh, sample, interval, true);
   }

   private static List<LaneDeck.Span> holes(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, double interval, boolean footings) {
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);
      ArrayList<LaneDeck.Span> holes = new ArrayList<>();

      for (int slot : slots(mesh)) {
         double removed = 0.0;

         for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
            if (cut.temporary() && cut.lane() == slot) {
               removed = Math.max(
                  removed,
                  cut.removed(!cut.rectangular() && (!cut.arrival() || !(Math.abs(sample.distance() - cut.end()) < 1.0E-7)) ? sample.distance() : interval)
               );
            }
         }

         LanePoints.Lane lane = LanePoints.lane(raw, sample.distance(), slot);
         double center = lane.position().sub(sample.center()).dot(sample.left());
         double half = lane.width() * removed * (LaneAdditions.isAdded(raw, slot) ? LaneAdditions.find(raw, slot).fraction(sample.distance()) : 1.0) / 2.0;
         double lo = Math.max(-sample.halfWidth(), Math.min(sample.halfWidth(), center - half));
         double hi = Math.max(lo, Math.min(sample.halfWidth(), center + half));
         boolean rectangular = mesh.settings()
            .options()
            .lanePoints()
            .cuts()
            .stream()
            .anyMatch(c -> c.lane() == slot && c.rectangular() && c.removed(interval) > 0.999);
         if (rectangular && half > 1.0E-7) {
            boolean lowOuter = true;
            boolean highOuter = true;

            for (int other : LaneAdditions.slots(raw, sample.distance())) {
               if (other != slot
                  && (!LaneAdditions.isAdded(raw, other) || !(LaneAdditions.find(raw, other).fraction(sample.distance()) < 1.0E-7))
                  && !(LaneAdditions.permanentRemoval(raw, sample.distance(), other) > 0.999999)) {
                  double x = LanePoints.lane(raw, sample.distance(), other).position().sub(sample.center()).dot(sample.left());
                  if (x < center - 1.0E-6) {
                     lowOuter = false;
                  }

                  if (x > center + 1.0E-6) {
                     highOuter = false;
                  }
               }
            }

            RoadProfile.Layout layout = RoadProfile.layout(raw, sample);
            boolean access = mesh.settings()
               .options()
               .lanePoints()
               .access()
               .stream()
               .anyMatch(r -> r.cuts().stream().anyMatch(c -> c.lane() == slot && c.removed(interval) > 0.999));
            double shoulder = footings && !access ? Math.min(0.68, lane.width() * 0.25) : 0.0;
            if (footings && LaneAdditions.isAdded(raw, slot)) {
               RoadGeometry.Sample original = RoadStructures.sample(raw, sample.distance());
               RoadProfile.Layout originalLayout = RoadProfile.layout(raw, original);
               boolean rail = mesh.settings().structure() == RoadGeometry.Structure.BRIDGE
                  || RoadStreetscape.raised(mesh, sample, center < 0.0 ? -1 : 1)
                  || mesh.settings().options().outerRail() == RoadProfile.OuterRail.ON
                  || RoadProfile.highway(mesh.settings().style());
               shoulder = rail ? shoulder : 0.0;
               if (highOuter) {
                  shoulder = Math.max(shoulder, edge(raw, original, 1) - originalLayout.motorMax());
               }

               if (lowOuter) {
                  shoulder = Math.max(shoulder, originalLayout.motorMin() - edge(raw, original, -1));
               }
            }

            boolean openLow = lowOuter && (layout.cycleWidth() < 0.01 || !layout.catalog().twoWay() && layout.outside() > 0);
            boolean openHigh = highOuter && (layout.cycleWidth() < 0.01 || !layout.catalog().twoWay() && layout.outside() < 0);
            if (openLow) {
               lo = -sample.halfWidth();
            }

            if (openHigh) {
               hi = sample.halfWidth();
            }

            if (!openLow) {
               lo = Math.min(hi, lo + shoulder);
            }

            if (!openHigh) {
               hi = Math.max(lo, hi - shoulder);
            }
         }

         holes.add(new LaneDeck.Span(lo, hi, false, false));
      }

      for (ElevatedAccess.Reservation accessx : mesh.settings().options().lanePoints().access()) {
         for (ElevatedAccess.Gap gap : accessx.gaps()) {
            holes.add(gap.at(interval) ? new LaneDeck.Span(gap.low(), gap.high(), false, false) : new LaneDeck.Span(gap.low(), gap.low(), false, false));
         }
      }

      for (int side : new int[]{-1, 1}) {
         if (RoadsideBypass.crossing(mesh, interval, side)) {
            double inner = RoadsideBypass.inner(mesh, sample, side);
            double outer = RoadsideBypass.outer(mesh, sample, side);
            holes.add(
               new LaneDeck.Span(Math.min((double)side * inner, (double)side * outer), Math.max((double)side * inner, (double)side * outer), false, false)
            );
         }
      }

      holes.sort(Comparator.comparingDouble(LaneDeck.Span::low));
      return holes;
   }

   public static boolean outerOpening(RoadGeometry.Mesh mesh, double station, int side) {
      RoadGeometry.Sample sample = RoadStructures.sample(mesh, station);
      return !present(mesh, sample, edge(mesh, sample, side) - (double)side * 0.025, 0.0);
   }

   public static List<LaneDeck.Span> spans(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample) {
      double probe = sample.distance();
      if (Math.abs(probe - mesh.first().distance()) < 1.0E-7) {
         probe += 1.0E-5;
      }

      if (Math.abs(probe - mesh.last().distance()) < 1.0E-7) {
         probe -= 1.0E-5;
      }

      return spans(mesh, sample, probe);
   }

   private static List<LaneDeck.Span> spans(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, double interval) {
      return spans(mesh, sample, interval, true);
   }

   private static List<LaneDeck.Span> spans(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, double interval, boolean footings) {
      double left = edge(mesh, sample, -1);
      double right = edge(mesh, sample, 1);
      if (!hasOpenings(mesh)) {
         return List.of(new LaneDeck.Span(left, right, true, true));
      } else {
         ArrayList<LaneDeck.Span> out = new ArrayList<>();
         double low = left;
         boolean wall = true;

         for (LaneDeck.Span hole : holes(mesh, sample, interval, footings)) {
            double high = Math.min(right, Math.max(low, hole.low()));
            boolean open = hole.high() - hole.low() > 1.0E-7;
            out.add(new LaneDeck.Span(low, high, wall, open));
            low = Math.min(right, Math.max(low, hole.high()));
            wall = open;
         }

         out.add(new LaneDeck.Span(low, right, wall, true));
         return out;
      }
   }

   public static List<LaneDeck.Strip> strips(RoadGeometry.Mesh mesh, RoadGeometry.Sample a, RoadGeometry.Sample b) {
      return strips(mesh, a, b, true);
   }

   public static List<LaneDeck.Strip> drivingStrips(RoadGeometry.Mesh mesh, RoadGeometry.Sample a, RoadGeometry.Sample b) {
      return strips(mesh, a, b, false);
   }

   private static List<LaneDeck.Strip> strips(RoadGeometry.Mesh mesh, RoadGeometry.Sample a, RoadGeometry.Sample b, boolean footings) {
      List<LaneDeck.Strip> patch = RoadSlicing.patch(mesh, a, b);
      if (patch != null) {
         return patch;
      } else {
         double probe = (a.distance() + b.distance()) / 2.0;
         List<LaneDeck.Span> aa = spans(mesh, a, probe, footings);
         List<LaneDeck.Span> bb = spans(mesh, b, probe, footings);
         ArrayList<LaneDeck.Strip> result = new ArrayList<>();

         for (int i = 0; i < aa.size(); i++) {
            LaneDeck.Span x = aa.get(i);
            LaneDeck.Span y = bb.get(i);
            if (!(Math.max(x.high() - x.low(), y.high() - y.low()) < 1.0E-8)) {
               double lowA = x.low();
               double lowB = y.low();
               boolean lowWall = x.lowWall() || y.lowWall();
               if (!result.isEmpty() && !lowWall) {
                  LaneDeck.Strip previous = result.get(result.size() - 1);
                  if (!previous.highWall() && previous.al().distance(a.at(lowA, 0.0)) < 1.0E-8 && previous.bl().distance(b.at(lowB, 0.0)) < 1.0E-8) {
                     result.set(
                        result.size() - 1,
                        new LaneDeck.Strip(
                           a.at(x.high(), 0.0), previous.ar(), b.at(y.high(), 0.0), previous.br(), previous.lowWall(), x.highWall() || y.highWall()
                        )
                     );
                     continue;
                  }
               }

               result.add(new LaneDeck.Strip(a.at(x.high(), 0.0), a.at(lowA, 0.0), b.at(y.high(), 0.0), b.at(lowB, 0.0), lowWall, x.highWall() || y.highWall()));
            }
         }

         return result;
      }
   }

   public static List<List<RoadGeometry.V>> holeQuads(RoadGeometry.Mesh mesh, RoadGeometry.Sample a, RoadGeometry.Sample b) {
      double probe = (a.distance() + b.distance()) / 2.0;
      List<LaneDeck.Span> aa = holes(mesh, a, probe);
      List<LaneDeck.Span> bb = holes(mesh, b, probe);
      ArrayList<List<RoadGeometry.V>> out = new ArrayList<>();

      for (int i = 0; i < aa.size(); i++) {
         LaneDeck.Span x = aa.get(i);
         LaneDeck.Span y = bb.get(i);
         if (Math.max(x.high() - x.low(), y.high() - y.low()) > 1.0E-8) {
            out.add(List.of(a.at(x.high(), 0.0), a.at(x.low(), 0.0), b.at(y.low(), 0.0), b.at(y.high(), 0.0)));
         }
      }

      return out;
   }

   public static List<LaneDeck.Cap> caps(RoadGeometry.Mesh mesh) {
      TreeSet<Double> stations = new TreeSet<>();

      for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
         if (cut.rectangular()) {
            stations.add(cut.begin());
            stations.add(cut.end());
         }
      }

      ArrayList<LaneDeck.Cap> result = new ArrayList<>();

      for (double d : stations) {
         if (!(d <= mesh.first().distance() + 1.0E-7) && !(d >= mesh.last().distance() - 1.0E-7)) {
            RoadGeometry.Sample sample = RoadStructures.sample(mesh, d);
            List<LaneDeck.Span> before = holes(mesh, sample, d - 1.0E-5);
            List<LaneDeck.Span> after = holes(mesh, sample, d + 1.0E-5);
            TreeSet<Double> edges = new TreeSet<>();

            for (LaneDeck.Span h : before) {
               edges.add(h.low());
               edges.add(h.high());
            }

            for (LaneDeck.Span h : after) {
               edges.add(h.low());
               edges.add(h.high());
            }

            ArrayList<Double> boundaries = new ArrayList<>(edges);
            Double from = null;
            boolean opening = false;

            for (int i = 1; i < boundaries.size(); i++) {
               double lo = boundaries.get(i - 1);
               double hi = boundaries.get(i);
               double middle = (lo + hi) / 2.0;
               boolean was = before.stream().anyMatch(h -> middle > h.low() && middle < h.high());
               boolean now = after.stream().anyMatch(h -> middle > h.low() && middle < h.high());
               if (from != null && (was == now || now != opening)) {
                  RoadGeometry.V low = sample.at(from, 0.0);
                  RoadGeometry.V high = sample.at(lo, 0.0);
                  result.add(new LaneDeck.Cap(opening ? low : high, opening ? high : low));
                  from = null;
               }

               if (was != now && from == null) {
                  from = lo;
                  opening = now;
               }
            }

            if (from != null) {
               RoadGeometry.V low = sample.at(from, 0.0);
               RoadGeometry.V high = sample.at(boundaries.get(boundaries.size() - 1), 0.0);
               result.add(new LaneDeck.Cap(opening ? low : high, opening ? high : low));
            }
         }
      }

      return List.copyOf(result);
   }

   public static boolean present(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, double lateral, double margin) {
      if (!(lateral < edge(mesh, sample, -1) - margin - 1.0E-8) && !(lateral > edge(mesh, sample, 1) + margin + 1.0E-8)) {
         if (!hasOpenings(mesh)) {
            return true;
         } else {
            for (LaneDeck.Span hole : holes(mesh, sample)) {
               if (hole.high() - hole.low() > 1.0E-7
                  && lateral > hole.low() + Math.max(0.0, margin) + 1.0E-8
                  && lateral < hole.high() - Math.max(0.0, margin) - 1.0E-8) {
                  return false;
               }
            }

            return true;
         }
      } else {
         return false;
      }
   }

   public static List<RoadGeometry.Mesh> rasterPieces(RoadGeometry.Mesh mesh, double length) {
      if (mesh.slicing() != null && mesh.samples().size() == 2) {
         return List.of(mesh);
      } else if (RoadProfile.catalog(mesh.settings()).type() != RoadProfile.Type.ORDINARY
         && mesh.slicing() == null
         && !hasOpenings(mesh)
         && mesh.reference() == null
         && mesh.settings().options().lanePoints().additions().isEmpty()) {
         return mesh.length() <= 256.0 ? List.of(mesh) : RoadRibbon.split(mesh, length);
      } else {
         ArrayList<RoadGeometry.Mesh> out = new ArrayList<>();
         List<RoadGeometry.Sample> points = mesh.samples();
         int start = 0;

         for (int i = 1; i < points.size(); i++) {
            if (points.get(i).distance() - points.get(start).distance() >= length || i == points.size() - 1) {
               List<RoadGeometry.Sample> subset = List.copyOf(points.subList(start, i + 1));
               RoadGeometry.Mesh bounds = RoadRibbon.mesh(subset, mesh.settings());
               out.add(
                  new RoadGeometry.Mesh(
                     subset,
                     mesh.settings(),
                     bounds.min(),
                     bounds.max(),
                     mesh.length(),
                     false,
                     null,
                     mesh.controls(),
                     LaneSections.reference(mesh),
                     mesh.slicing()
                  )
               );
               start = i;
            }
         }

         return out;
      }
   }

   public static RoadGeometry.Mesh motorOnly(RoadGeometry.Mesh host) {
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

      for (RoadGeometry.Sample sample : host.samples()) {
         RoadProfile.Layout l = RoadProfile.layout(host, sample);
         samples.add(new RoadGeometry.Sample(sample.at(l.motorCenter(), 0.0), sample.left(), sample.distance(), (l.motorMax() - l.motorMin()) / 2.0));
      }

      return new RoadGeometry.Mesh(
         List.copyOf(samples),
         host.settings(),
         host.min(),
         host.max(),
         host.length(),
         host.closed(),
         host.controlPoint(),
         host.controls(),
         LaneSections.reference(host)
      );
   }

   public static RoadGeometry.Mesh excludingSlot(RoadGeometry.Mesh host, int slot) {
      ArrayList<LaneSections.Cut> cuts = new ArrayList<>(host.settings().options().lanePoints().cuts());
      cuts.removeIf(c -> c.temporary() && c.lane() == slot);
      cuts.add(new LaneSections.Cut(new UUID(0L, (long)(slot + 1)), slot, 1, -1024.0, host.length() + 1024.0, 2.0, null, true));
      RoadGeometry.Settings settings = host.settings().options(host.settings().options().lanePoints(host.settings().options().lanePoints().cuts(cuts)));
      return new RoadGeometry.Mesh(
         host.samples(), settings, host.min(), host.max(), host.length(), host.closed(), host.controlPoint(), host.controls(), LaneSections.reference(host)
      );
   }

   private LaneDeck() {
   }

   public static record Cap(RoadGeometry.V a, RoadGeometry.V b) {
   }

   public static record Span(double low, double high, boolean lowWall, boolean highWall) {
   }

   public static record Strip(RoadGeometry.V al, RoadGeometry.V ar, RoadGeometry.V bl, RoadGeometry.V br, boolean lowWall, boolean highWall) {
   }
}
