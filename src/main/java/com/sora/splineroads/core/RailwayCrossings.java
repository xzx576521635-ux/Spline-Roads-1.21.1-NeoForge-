package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class RailwayCrossings {
   public static boolean pair(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      boolean ar = a.settings().style() == RoadGeometry.Style.RAILBED;
      boolean br = b.settings().style() == RoadGeometry.Style.RAILBED;
      return ar != br && RoadProfile.catalog((ar ? b : a).settings().style()).type() == RoadProfile.Type.ORDINARY;
   }

   public static boolean ground(RoadGeometry.Mesh mesh, double from, double to) {
      RoadGeometry.Structure mode = mesh.settings().structure();
      if (mode == RoadGeometry.Structure.GROUND) {
         return true;
      } else if (mode != RoadGeometry.Structure.AUTO) {
         return false;
      } else {
         RoadStreetscape.Config c = mesh.settings().options().streetscape();
         return Stream.of(c.tunnels(), c.sides().negativeRaised(), c.sides().positiveRaised(), c.sides().negativeCut(), c.sides().positiveCut())
            .flatMap(Collection::stream)
            .noneMatch(s -> s.to() > from + 1.0E-7 && s.from() < to - 1.0E-7);
      }
   }

   public static boolean allows(RoadGeometry.Mesh a, RoadGeometry.Mesh b, double from, double to, double otherFrom, double otherTo, double min, double max) {
      if (pair(a, b) && !(Math.max(Math.abs(min), Math.abs(max)) > 0.5000001) && ground(a, from, to) && ground(b, otherFrom, otherTo)) {
         RoadGeometry.Sample x = RoadStructures.sample(a, (from + to) / 2.0);
         RoadGeometry.Sample y = RoadStructures.sample(b, (otherFrom + otherTo) / 2.0);
         return Math.abs(x.left().horizontalUnit().dot(y.left().horizontalUnit())) < 0.9999;
      } else {
         return false;
      }
   }

   public static List<RoadClearance.DetailedContact> contacts(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      return !pair(a, b)
         ? List.of()
         : RoadClearance.detailedContacts(a, b)
            .stream()
            .filter(c -> allows(a, b, c.contact().from(), c.contact().to(), c.otherFrom(), c.otherTo(), c.minimumDifference(), c.maximumDifference()))
            .toList();
   }

   public static List<RoadClearance.DetailedContact> adjustableContacts(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      return !pair(a, b)
         ? List.of()
         : RoadClearance.detailedContacts(a, b)
            .stream()
            .filter(
               c -> Math.max(Math.abs(c.minimumDifference()), Math.abs(c.maximumDifference())) <= 1.0000001
                     && adjustable(a, c.contact().from(), c.contact().to())
                     && adjustable(b, c.otherFrom(), c.otherTo())
                     && Math.abs(
                           RoadStructures.sample(a, (c.contact().from() + c.contact().to()) / 2.0)
                              .left()
                              .horizontalUnit()
                              .dot(RoadStructures.sample(b, (c.otherFrom() + c.otherTo()) / 2.0).left().horizontalUnit())
                        )
                        < 0.9999
            )
            .toList();
   }

   private static boolean adjustable(RoadGeometry.Mesh mesh, double from, double to) {
      return mesh.settings().style() != RoadGeometry.Style.RAILBED && mesh.settings().structure() == RoadGeometry.Structure.AUTO || ground(mesh, from, to);
   }

   public static boolean any(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      return !contacts(a, b).isEmpty();
   }

   public static boolean at(RoadGeometry.Mesh a, RoadGeometry.Mesh b, RoadGeometry.V point, double margin) {
      if (!pair(a, b)) {
         return false;
      } else {
         RoadQueries.Projection x = RoadQueries.horizontal(a, point);
         RoadQueries.Projection y = RoadQueries.horizontal(b, point);
         return within(a, x, point, margin)
            && within(b, y, point, margin)
            && x.horizontalDistance() <= x.sample().halfWidth() + margin
            && y.horizontalDistance() <= y.sample().halfWidth() + margin
            && allows(
               a,
               b,
               x.sample().distance(),
               x.sample().distance() + 0.001,
               y.sample().distance(),
               y.sample().distance() + 0.001,
               x.sample().center().y() - y.sample().center().y(),
               x.sample().center().y() - y.sample().center().y()
            );
      }
   }

   private static boolean within(RoadGeometry.Mesh mesh, RoadQueries.Projection q, RoadGeometry.V point, double margin) {
      double along = point.sub(q.sample().center()).dot(q.tangent().horizontalUnit());
      return mesh.closed()
         || (!(q.sample().distance() < 1.0E-7) || !(along < -margin)) && (!(q.sample().distance() > mesh.length() - 1.0E-7) || !(along > margin));
   }

   public static boolean walkingAt(RoadGeometry.Mesh rail, RoadGeometry.Mesh road, RoadGeometry.V point, double margin) {
      if (rail.settings().style() == RoadGeometry.Style.RAILBED && pair(rail, road)) {
         RoadQueries.Projection q = RoadQueries.horizontal(road, point);
         int side = q.lateral() < 0.0 ? -1 : 1;
         double walk = RoadSidewalks.walkWidth(road, road.settings().options().sidewalk(), q.sample(), side);
         if (!(walk <= 0.0) && !(q.horizontalDistance() > q.sample().halfWidth() + walk + margin) && within(road, q, point, margin)) {
            RoadQueries.Projection r = RoadQueries.horizontal(rail, point);
            double delta = r.sample().center().y() - q.sample().center().y();
            return within(rail, r, point, margin)
               && r.horizontalDistance() <= r.sample().halfWidth() + margin
               && allows(rail, road, r.sample().distance(), r.sample().distance() + 0.001, q.sample().distance(), q.sample().distance() + 0.001, delta, delta);
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public static List<RoadJunction.Paint> stopLines(RoadGeometry.Mesh mesh) {
      ArrayList<RoadJunction.Paint> out = new ArrayList<>();

      for (RailwayCrossings.Surface crossing : mesh.settings().options().levelCrossings()) {
         for (int end : new int[]{-1, 1}) {
            double station = (end < 0 ? crossing.from() : crossing.to()) + (double)end * 1.5;
            if (!(station < 0.3) && !(station > mesh.length() - 0.3)) {
               RoadGeometry.Sample s = RoadStructures.sample(mesh, station);

               for (LanePoints.Lane lane : LaneSections.live(mesh, station).lanes()) {
                  if (lane.sign() == -end) {
                     double lateral = lane.position().sub(s.center()).dot(s.left());
                     RoadGeometry.Sample a = RoadStructures.sample(mesh, station - 0.2);
                     RoadGeometry.Sample b = RoadStructures.sample(mesh, station + 0.2);
                     double low = lateral - lane.width() / 2.0;
                     double high = lateral + lane.width() / 2.0;
                     out.add(new RoadJunction.Paint(List.of(a.at(high, -0.014), a.at(low, -0.014), b.at(low, -0.014), b.at(high, -0.014)), 15855838));
                  }
               }
            }
         }
      }

      return List.copyOf(out);
   }

   public static List<List<RoadGeometry.V>> paintCuts(RoadGeometry.Mesh mesh) {
      ArrayList<List<RoadGeometry.V>> out = new ArrayList<>();

      for (RailwayCrossings.Surface crossing : mesh.settings().options().levelCrossings()) {
         double from = Math.max(0.0, crossing.from() - 1.7);
         double to = Math.min(mesh.length(), crossing.to() + 1.7);

         for (double d = from; d < to - 1.0E-8; d += 0.5) {
            RoadGeometry.Sample a = RoadStructures.sample(mesh, d);
            RoadGeometry.Sample b = RoadStructures.sample(mesh, Math.min(d + 0.5, to));
            out.add(
               List.of(a.at(-a.halfWidth() - 0.25, 0.0), a.at(a.halfWidth() + 0.25, 0.0), b.at(b.halfWidth() + 0.25, 0.0), b.at(-b.halfWidth() - 0.25, 0.0))
            );
         }
      }

      return List.copyOf(out);
   }

   public static List<RailwayCrossings.Surface> surfaces(RoadGeometry.Mesh road, List<RoadGeometry.Mesh> rails) {
      if (road.settings().style() == RoadGeometry.Style.RAILBED) {
         return List.of();
      } else {
         ArrayList<RailwayCrossings.Surface> out = new ArrayList<>();

         for (RoadGeometry.Mesh rail : rails) {
            List<RoadClearance.DetailedContact> hits = adjustableContacts(road, rail)
               .stream()
               .sorted(Comparator.comparingDouble(h -> h.contact().from()))
               .toList();
            int i = 0;

            while (i < hits.size()) {
               RoadClearance.DetailedContact first = hits.get(i);
               double from = first.contact().from();
               double to = first.contact().to();

               int end;
               for (end = i + 1; end < hits.size() && hits.get(end).contact().from() <= to + 0.1; end++) {
                  to = Math.max(to, hits.get(end).contact().to());
               }

               RoadQueries.Projection q = RoadQueries.horizontal(rail, RoadStructures.sample(road, (from + to) / 2.0).center());
               RoadGeometry.V tangent = q.tangent();
               double horizontal = tangent.horizontalLength();
               double grade = horizontal < 1.0E-8 ? 0.0 : tangent.y() / horizontal;
               RoadGeometry.V along = tangent.horizontalUnit();
               double delta = 0.0;

               for (int j = i; j < end; j++) {
                  delta = Math.max(delta, Math.max(Math.abs(hits.get(j).minimumDifference()), Math.abs(hits.get(j).maximumDifference())));
               }

               double cap = road.settings().options().infrastructure().maxGrade();
               double transition = Math.max(4.0, Math.max(40.0 * delta, cap > 0.0 ? 1.6 * delta / cap : 0.0));
               if (transition > 128.0) {
                  throw new IllegalArgumentException("道口缓坡超过可用过渡长度，请提高道路允许坡度或调整高度");
               }

               if (delta > 1.0E-7 && (from - transition < 0.01 || to + transition > road.length() - 0.01)) {
                  throw new IllegalArgumentException("道口前后没有足够缓坡空间，请延长公路");
               }

               out.add(new RailwayCrossings.Surface(from, to, q.sample().center(), grade * along.x(), grade * along.z(), transition));
               i = end;
            }
         }

         out.sort(Comparator.comparingDouble(RailwayCrossings.Surface::from));
         ArrayList<RailwayCrossings.Surface> merged = new ArrayList<>();

         for (RailwayCrossings.Surface next : out) {
            if (!merged.isEmpty()) {
               RailwayCrossings.Surface prev = merged.get(merged.size() - 1);
               if (next.from() <= prev.to() + 0.1
                  && Math.abs(prev.slopeX() - next.slopeX()) < 1.0E-6
                  && Math.abs(prev.slopeZ() - next.slopeZ()) < 1.0E-6
                  && Math.abs(prev.height(next.origin()) - next.origin().y()) < 1.0E-5) {
                  merged.set(
                     merged.size() - 1,
                     new RailwayCrossings.Surface(
                        prev.from(),
                        Math.max(prev.to(), next.to()),
                        prev.origin(),
                        prev.slopeX(),
                        prev.slopeZ(),
                        Math.max(prev.transition(), next.transition())
                     )
                  );
                  continue;
               }
            }

            merged.add(next);
         }

         return List.copyOf(merged);
      }
   }

   public static RoadGeometry.Mesh shape(RoadGeometry.Mesh mesh) {
      List<RailwayCrossings.Surface> surfaces = mesh.settings().options().levelCrossings();
      if (!surfaces.isEmpty() && mesh.settings().style() != RoadGeometry.Style.RAILBED) {
         TreeSet<Double> stations = new TreeSet<>();
         mesh.samples().forEach(s -> stations.add(s.distance()));

         for (RailwayCrossings.Surface surface : surfaces) {
            for (double d = Math.max(0.0, surface.from() - surface.transition()); d < Math.min(mesh.length(), surface.to() + surface.transition()); d += 0.5) {
               Double before = stations.floor(d);
               Double after = stations.ceiling(d);
               if ((before == null || d - before > 1.0E-7) && (after == null || after - d > 1.0E-7)) {
                  stations.add(d);
               }
            }
         }

         ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

         for (double dx : stations) {
            RoadGeometry.Sample s = RoadStructures.sample(mesh, dx);
            double weight = 0.0;
            double target = 0.0;
            double bank = 0.0;

            for (RailwayCrossings.Surface patch : surfaces) {
               double w = patch.weight(dx);
               if (w > 0.999999 && weight > 0.999999 && Math.abs(target - patch.height(s.center())) > 0.02) {
                  throw new IllegalArgumentException("相邻铁路道口要求的高度相互冲突");
               }

               if (w > weight) {
                  weight = w;
                  target = patch.height(s.center());
                  bank = patch.slopeX() * s.left().x() + patch.slopeZ() * s.left().z();
               }
            }

            samples.add(
               weight == 0.0
                  ? s
                  : new RoadGeometry.Sample(
                     new RoadGeometry.V(s.center().x(), s.center().y() * (1.0 - weight) + target * weight, s.center().z()),
                     new RoadGeometry.V(s.left().x(), s.left().y() * (1.0 - weight) + bank * weight, s.left().z()),
                     dx,
                     s.halfWidth()
                  )
            );
         }

         RoadGeometry.V min = new RoadGeometry.V(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
         RoadGeometry.V max = new RoadGeometry.V(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE);

         for (RoadGeometry.Sample sample : samples) {
            for (int side : new int[]{-1, 1}) {
               RoadGeometry.V p = sample.at((double)side * sample.halfWidth(), 0.0);
               min = new RoadGeometry.V(Math.min(min.x(), p.x()), Math.min(min.y(), p.y() - mesh.settings().thickness()), Math.min(min.z(), p.z()));
               max = new RoadGeometry.V(Math.max(max.x(), p.x()), Math.max(max.y(), p.y()), Math.max(max.z(), p.z()));
            }
         }

         return new RoadGeometry.Mesh(
            List.copyOf(samples),
            mesh.settings(),
            min,
            max,
            mesh.length(),
            mesh.closed(),
            mesh.controlPoint(),
            mesh.controls(),
            mesh.reference(),
            mesh.slicing()
         );
      } else {
         return mesh;
      }
   }

   private RailwayCrossings() {
   }

   public static record Surface(double from, double to, RoadGeometry.V origin, double slopeX, double slopeZ, double transition) {
      public Surface(double from, double to, RoadGeometry.V origin, double slopeX, double slopeZ) {
         this(from, to, origin, slopeX, slopeZ, 4.0);
      }

      public Surface(double from, double to, RoadGeometry.V origin, double slopeX, double slopeZ, double transition) {
         if (RoadGeometry.finite(from, to, origin.x(), origin.y(), origin.z(), slopeX, slopeZ)
            && !(from < 0.0)
            && !(to <= from)
            && Double.isFinite(transition)
            && !(transition < 1.0)
            && !(transition > 128.0)
            && !(Math.abs(slopeX) > 2.0)
            && !(Math.abs(slopeZ) > 2.0)) {
            this.from = from;
            this.to = to;
            this.origin = origin;
            this.slopeX = slopeX;
            this.slopeZ = slopeZ;
            this.transition = transition;
         } else {
            throw new IllegalArgumentException("Invalid railway crossing profile");
         }
      }

      public double height(RoadGeometry.V p) {
         return this.origin.y() + (p.x() - this.origin.x()) * this.slopeX + (p.z() - this.origin.z()) * this.slopeZ;
      }

      public double weight(double station) {
         return RoadGeometry.Settings.smooth(
            Math.max(
               0.0, Math.min(1.0, Math.min((station - this.from + this.transition) / this.transition, (this.to + this.transition - station) / this.transition))
            )
         );
      }
   }
}
