package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class RoadClearance {
   public static final double REQUIRED = 4.0;
   private static final double EPS = 1.0E-8;
   private static final double AREA_EPS = 1.0E-4;
   private static final ThreadLocal<RoadClearance.NearScratch> NEAR = ThreadLocal.withInitial(RoadClearance.NearScratch::new);
   private static final WeakIdentityCache<RoadGeometry.Mesh, RoadClearance.Grid> GRIDS = new WeakIdentityCache<>(
      256, 500000L, grid -> grid.cells.values().stream().mapToInt(List::size).sum()
   );
   private static final WeakIdentityCache<RoadGeometry.Mesh, RoadClearance.Grid> DRIVING_GRIDS = new WeakIdentityCache<>(
      256, 500000L, grid -> grid.cells.values().stream().mapToInt(List::size).sum()
   );
   private static final ThreadLocal<RoadClearance.ClipScratch> CLIP = ThreadLocal.withInitial(RoadClearance.ClipScratch::new);
   private static final WeakIdentityCache<RoadGeometry.Mesh, List<RoadClearance.Triangle>> TRIANGLES = new WeakIdentityCache<>(256, 500000L, List::size);

   public static String clearanceLabel(double clearance) {
      return clearance < 0.0 ? String.format(Locale.ROOT, "路面实体重叠 %.2f 格（可用净空 0.00）", -clearance) : String.format(Locale.ROOT, "板底净空 %.2f 格", clearance);
   }

   private static RoadClearance.Grid grid(RoadGeometry.Mesh mesh) {
      return GRIDS.get(mesh, RoadClearance.Grid::new);
   }

   private static RoadClearance.Grid drivingGrid(RoadGeometry.Mesh mesh) {
      return LaneDeck.hasOpenings(mesh) ? DRIVING_GRIDS.get(mesh, m -> new RoadClearance.Grid(m, true)) : grid(mesh);
   }

   public static void clearPreparedCache() {
      GRIDS.clear();
      DRIVING_GRIDS.clear();
      TRIANGLES.clear();
   }

   public static WeakIdentityCache.Stats preparedCacheStats() {
      return GRIDS.stats();
   }

   public static RoadClearance.Prepared prepare(RoadGeometry.Mesh mesh) {
      return new RoadClearance.Prepared(mesh);
   }

   public static List<RoadClearance.Contact> contacts(RoadGeometry.Mesh a, RoadClearance.Prepared other) {
      return contacts(a, other.mesh, null, other.grid);
   }

   public static List<RoadClearance.Contact> contacts(RoadClearance.Prepared ours, RoadGeometry.Mesh b) {
      RoadGeometry.Mesh a = ours.mesh;
      if (!(a.max().x() <= b.min().x() + 1.0E-8)
         && !(b.max().x() <= a.min().x() + 1.0E-8)
         && !(a.max().z() <= b.min().z() + 1.0E-8)
         && !(b.max().z() <= a.min().z() + 1.0E-8)) {
         ArrayList<RoadClearance.Contact> out = new ArrayList<>();

         for (RoadClearance.Triangle q : triangles(b)) {
            RoadPlanningBudget.check();

            for (RoadClearance.Triangle t : ours.grid.near(q)) {
               appendContact(out, a, b, t, q);
            }
         }

         return List.copyOf(out);
      } else {
         return List.of();
      }
   }

   public static void check(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      check(a, b, null);
   }

   public static void check(RoadGeometry.Mesh a, RoadGeometry.Mesh b, RoadGeometry.Mesh previous) {
      for (RoadClearance.Contact c : contacts(a, b, previous)) {
         if (c.blocked()) {
            throw new RoadClearance.Conflict(c);
         }
      }
   }

   public static List<RoadClearance.Contact> contacts(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      return contacts(a, b, null);
   }

   public static List<RoadClearance.DetailedContact> detailedContacts(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      if (!(a.max().x() <= b.min().x() + 1.0E-8)
         && !(b.max().x() <= a.min().x() + 1.0E-8)
         && !(a.max().z() <= b.min().z() + 1.0E-8)
         && !(b.max().z() <= a.min().z() + 1.0E-8)) {
         ArrayList<RoadClearance.DetailedContact> out = new ArrayList<>();
         RoadClearance.Grid index = grid(b);

         for (RoadClearance.Triangle t : triangles(a)) {
            RoadPlanningBudget.check();

            for (RoadClearance.Triangle q : index.near(t)) {
               appendContact(null, out, a, b, t, q);
            }
         }

         return List.copyOf(out);
      } else {
         return List.of();
      }
   }

   public static List<RoadClearance.DetailedContact> detailedSelfContacts(RoadGeometry.Mesh mesh, double stationGap) {
      ArrayList<RoadClearance.DetailedContact> out = new ArrayList<>();
      RoadClearance.Grid index = grid(mesh);

      for (RoadClearance.Triangle a : triangles(mesh)) {
         RoadPlanningBudget.check();

         for (RoadClearance.Triangle b : index.nearAfter(a, a.minStation + stationGap)) {
            appendContact(null, out, mesh, mesh, a, b);
         }
      }

      return List.copyOf(out);
   }

   public static List<RoadClearance.Contact> contacts(RoadGeometry.Mesh a, RoadGeometry.Mesh b, RoadGeometry.Mesh previous) {
      return !(a.max().x() <= b.min().x() + 1.0E-8)
            && !(b.max().x() <= a.min().x() + 1.0E-8)
            && !(a.max().z() <= b.min().z() + 1.0E-8)
            && !(b.max().z() <= a.min().z() + 1.0E-8)
         ? contacts(a, b, previous, grid(b))
         : List.of();
   }

   private static List<RoadClearance.Contact> contacts(RoadGeometry.Mesh a, RoadGeometry.Mesh b, RoadGeometry.Mesh previous, RoadClearance.Grid grid) {
      ArrayList<RoadClearance.Contact> out = new ArrayList<>();

      for (RoadClearance.Triangle t : triangles(a)) {
         RoadPlanningBudget.check();
         if (previous == null || !t.polygon().stream().allMatch(v -> RoadQueries.contains(previous, v, 0.015, 0.02))) {
            for (RoadClearance.Triangle q : grid.near(t)) {
               appendContact(out, a, b, t, q);
            }
         }
      }

      return List.copyOf(out);
   }

   private static double solidDepth(RoadGeometry.Mesh mesh) {
      return mesh.settings().thickness() + RoadInfrastructure.girderDepth(mesh);
   }

   private static void appendContact(
      List<RoadClearance.Contact> out, RoadGeometry.Mesh a, RoadGeometry.Mesh b, RoadClearance.Triangle t, RoadClearance.Triangle q
   ) {
      appendContact(out, null, a, b, t, q);
   }

   private static void appendContact(
      List<RoadClearance.Contact> out,
      List<RoadClearance.DetailedContact> detailed,
      RoadGeometry.Mesh a,
      RoadGeometry.Mesh b,
      RoadClearance.Triangle t,
      RoadClearance.Triangle q
   ) {
      RoadClearance.ClipScratch clipped = CLIP.get();
      int n = clipped.intersection(t.polygon(), q.polygon());
      if (!(clipped.area(n) < 1.0E-4)) {
         double[] polygon = clipped.first;
         double from = Double.POSITIVE_INFINITY;
         double to = Double.NEGATIVE_INFINITY;
         double min = Double.POSITIVE_INFINITY;
         double otherFrom = Double.POSITIVE_INFINITY;
         double otherTo = Double.NEGATIVE_INFINITY;
         double raise = Double.NEGATIVE_INFINITY;
         double lower = Double.NEGATIVE_INFINITY;
         double minDiff = Double.POSITIVE_INFINITY;
         double maxDiff = Double.NEGATIVE_INFINITY;
         double required = !(RoadInfrastructure.girderDepth(a) > 0.0) && !(RoadInfrastructure.girderDepth(b) > 0.0) && !fascia(a) && !fascia(b) ? 4.0 : 4.25;
         RoadGeometry.V ours = null;
         RoadGeometry.V other = null;
         int negative = 0;
         int positive = 0;

         for (int i = 0; i < n; i += 3) {
            double x = polygon[i];
            double z = polygon[i + 2];
            double ya = t.height(x, z);
            double yb = q.height(x, z);
            double difference = ya - yb;
            if (difference <= 0.0 && a.settings().style() == RoadGeometry.Style.RAILBED
               || difference >= 0.0 && b.settings().style() == RoadGeometry.Style.RAILBED) {
               required = Math.max(required, 6.0);
            }

            double gap = Math.abs(difference) - (difference >= 0.0 ? solidDepth(a) : solidDepth(b));
            if (gap < min) {
               min = gap;
               ours = new RoadGeometry.V(x, ya, z);
               other = new RoadGeometry.V(x, yb, z);
            }

            double station = t.station(x, z);
            from = Math.min(from, station);
            to = Math.max(to, station);
            if (detailed != null || RailwayCrossings.pair(a, b)) {
               double otherStation = q.station(x, z);
               otherFrom = Math.min(otherFrom, otherStation);
               otherTo = Math.max(otherTo, otherStation);
            }

            if (difference < minDiff) {
               minDiff = difference;
               negative = i;
            }

            if (difference > maxDiff) {
               maxDiff = difference;
               positive = i;
            }

            raise = Math.max(raise, yb + required + solidDepth(a) + 0.1 - ya);
            lower = Math.max(lower, ya + required + solidDepth(b) + 0.1 - yb);
         }

         if (minDiff < 0.0 && maxDiff > 0.0) {
            double alpha = -minDiff / (maxDiff - minDiff);
            double xx = polygon[negative] + (polygon[positive] - polygon[negative]) * alpha;
            double zx = polygon[negative + 2] + (polygon[positive + 2] - polygon[negative + 2]) * alpha;
            ours = new RoadGeometry.V(xx, t.height(xx, zx), zx);
            other = new RoadGeometry.V(xx, q.height(xx, zx), zx);
            min = -Math.min(solidDepth(a), solidDepth(b));
         }

         RoadClearance.Contact contact = new RoadClearance.Contact(
            from, to, ours, other, min, Math.max(0.0, raise), Math.max(0.0, lower), required, raise, lower
         );
         if (out != null && !RailwayCrossings.allows(a, b, from, to, otherFrom, otherTo, minDiff, maxDiff)) {
            out.add(contact);
         }

         if (detailed != null) {
            detailed.add(new RoadClearance.DetailedContact(contact, otherFrom, otherTo, minDiff, maxDiff));
         }
      }
   }

   public static boolean structureInvades(RoadStructures.Part part, RoadGeometry.Mesh road, double headroom) {
      List<RoadGeometry.V> base = part.base();
      List<RoadGeometry.V> top = part.top();
      if (base.size() < 3) {
         return false;
      } else {
         RoadClearance.Grid index = drivingGrid(road);

         for (int i = 1; i < base.size() - 1; i++) {
            RoadClearance.Triangle t = new RoadClearance.Triangle(base.get(0), base.get(i), base.get(i + 1), 0.0, 0.0, 0.0);
            RoadClearance.Triangle roof = new RoadClearance.Triangle(top.get(0), top.get(i), top.get(i + 1), 0.0, 0.0, 0.0);
            if (!(Math.abs(t.det()) < 1.0E-8)) {
               for (RoadClearance.Triangle q : index.near(t)) {
                  List<RoadGeometry.V> polygon = intersection(t.polygon(), q.polygon());
                  if (!(area(polygon) < 1.0E-4)) {
                     double min = Double.POSITIVE_INFINITY;
                     double max = Double.NEGATIVE_INFINITY;

                     for (RoadGeometry.V point : polygon) {
                        double gap = t.height(point) - q.height(point);
                        min = Math.min(min, gap);
                        max = Math.max(max, roof.height(point) - q.height(point));
                     }

                     if (min < headroom - 1.0E-8 && max > -road.settings().thickness() + 0.04 + 1.0E-8) {
                        return true;
                     }
                  }
               }
            }
         }

         return false;
      }
   }

   public static boolean belowSurface(RoadStructures.Part part, RoadGeometry.Mesh road, double tolerance) {
      List<RoadGeometry.V> base = part.base();
      List<RoadGeometry.V> top = part.top();
      RoadClearance.Grid index = grid(road);

      for (int i = 1; i < base.size() - 1; i++) {
         RoadClearance.Triangle t = new RoadClearance.Triangle(base.get(0), base.get(i), base.get(i + 1), 0.0, 0.0, 0.0);
         RoadClearance.Triangle roof = new RoadClearance.Triangle(top.get(0), top.get(i), top.get(i + 1), 0.0, 0.0, 0.0);
         if (!(Math.abs(t.det()) < 1.0E-8)) {
            for (RoadClearance.Triangle q : index.near(t)) {
               List<RoadGeometry.V> polygon = intersection(t.polygon(), q.polygon());
               if (!(area(polygon) < 1.0E-4)) {
                  for (RoadGeometry.V point : polygon) {
                     if (roof.height(point) > q.height(point) + tolerance) {
                        return false;
                     }
                  }
               }
            }
         }
      }

      return true;
   }

   public static boolean joiningFloor(RoadStructures.Part part, RoadGeometry.Mesh owner, RoadGeometry.Mesh joining) {
      return !part.pier()
         && part.material() == RoadStructures.Material.CONCRETE
         && part.height() <= owner.settings().thickness() + 1.0E-7
         && belowSurface(part, joining, 0.025);
   }

   public static List<RoadClearance.Contact> structureContacts(RoadStructures.Part part, RoadGeometry.Mesh road, double headroom) {
      ArrayList<RoadClearance.Contact> out = new ArrayList<>();
      List<RoadGeometry.V> base = part.base();
      List<RoadGeometry.V> top = part.top();
      if (base.size() < 3) {
         return out;
      } else {
         RoadClearance.Grid index = drivingGrid(road);

         for (int i = 1; i < base.size() - 1; i++) {
            RoadClearance.Triangle t = new RoadClearance.Triangle(base.get(0), base.get(i), base.get(i + 1), 0.0, 0.0, 0.0);
            RoadClearance.Triangle roof = new RoadClearance.Triangle(top.get(0), top.get(i), top.get(i + 1), 0.0, 0.0, 0.0);
            if (!(Math.abs(t.det()) < 1.0E-8)) {
               for (RoadClearance.Triangle q : index.near(t)) {
                  List<RoadGeometry.V> polygon = intersection(t.polygon(), q.polygon());
                  if (!(area(polygon) < 1.0E-4)) {
                     double from = Double.POSITIVE_INFINITY;
                     double to = Double.NEGATIVE_INFINITY;
                     double raise = Double.NEGATIVE_INFINITY;
                     double lower = Double.NEGATIVE_INFINITY;
                     double minGap = Double.POSITIVE_INFINITY;
                     double maxGap = Double.NEGATIVE_INFINITY;
                     RoadGeometry.V ours = null;
                     RoadGeometry.V other = null;

                     for (RoadGeometry.V p : polygon) {
                        double deck = q.height(p);
                        double bottom = t.height(p);
                        double gap = bottom - deck;
                        from = Math.min(from, q.station(p));
                        to = Math.max(to, q.station(p));
                        minGap = Math.min(minGap, gap);
                        maxGap = Math.max(maxGap, roof.height(p) - deck);
                        raise = Math.max(raise, roof.height(p) + solidDepth(road) + 0.1 - deck);
                        lower = Math.max(lower, deck + headroom + 0.1 - bottom);
                        ours = new RoadGeometry.V(p.x(), deck, p.z());
                        other = new RoadGeometry.V(p.x(), (bottom + roof.height(p)) / 2.0, p.z());
                     }

                     boolean blocked = minGap < headroom - 1.0E-8 && maxGap > -solidDepth(road) + 0.04 + 1.0E-8;
                     out.add(
                        new RoadClearance.Contact(from, to, ours, other, blocked ? 0.0 : 4.0, Math.max(0.0, raise), Math.max(0.0, lower), 4.0, raise, lower)
                     );
                  }
               }
            }
         }

         return out;
      }
   }

   private static List<RoadClearance.Triangle> triangles(RoadGeometry.Mesh mesh) {
      return TRIANGLES.get(mesh, RoadClearance::makeTriangles);
   }

   private static List<RoadClearance.Triangle> makeTriangles(RoadGeometry.Mesh mesh) {
      return makeTriangles(mesh, false);
   }

   private static boolean fascia(RoadGeometry.Mesh mesh) {
      return mesh.settings().style().connectorRamp()
         && mesh.settings().structure() != RoadGeometry.Structure.GROUND
         && mesh.settings().structure() != RoadGeometry.Structure.TUNNEL;
   }

   private static List<RoadClearance.Triangle> makeTriangles(RoadGeometry.Mesh mesh, boolean driving) {
      ArrayList<RoadClearance.Triangle> out = new ArrayList<>();
      List<RoadGeometry.Sample> samples = mesh.samples();

      for (int i = 1; i < samples.size(); i++) {
         if ((i & 63) == 0) {
            RoadPlanningBudget.check();
         }

         RoadGeometry.Sample a = samples.get(i - 1);
         RoadGeometry.Sample b = samples.get(i);

         for (LaneDeck.Strip strip : driving ? LaneDeck.drivingStrips(mesh, a, b) : LaneDeck.strips(mesh, a, b)) {
            RoadGeometry.V al = strip.al();
            RoadGeometry.V ar = strip.ar();
            RoadGeometry.V bl = strip.bl();
            RoadGeometry.V br = strip.br();

            for (RoadClearance.Triangle t : List.of(
               new RoadClearance.Triangle(al, ar, br, a.distance(), a.distance(), b.distance()),
               new RoadClearance.Triangle(al, br, bl, a.distance(), b.distance(), b.distance())
            )) {
               if (Math.abs(t.det()) > 1.0E-8) {
                  out.add(t);
               }
            }
         }
      }

      return List.copyOf(out);
   }

   private static List<RoadGeometry.V> intersection(List<RoadGeometry.V> subject, List<RoadGeometry.V> clip) {
      List<RoadGeometry.V> polygon = subject;

      for (int i = 0; i < clip.size() && !polygon.isEmpty(); i++) {
         RoadGeometry.V a = clip.get(i);
         RoadGeometry.V b = clip.get((i + 1) % clip.size());
         RoadGeometry.V d = b.sub(a);
         ArrayList<RoadGeometry.V> next = new ArrayList<>();
         RoadGeometry.V prev = polygon.get(polygon.size() - 1);
         double dp = cross(d, prev.sub(a));

         for (RoadGeometry.V cur : polygon) {
            double dc = cross(d, cur.sub(a));
            boolean pin = dp >= -1.0E-8;
            boolean cin = dc >= -1.0E-8;
            if (pin != cin) {
               double den = dp - dc;
               if (Math.abs(den) > 1.0E-8) {
                  next.add(prev.add(cur.sub(prev).mul(dp / den)));
               }
            }

            if (cin) {
               next.add(cur);
            }

            prev = cur;
            dp = dc;
         }

         polygon = next;
      }

      return polygon;
   }

   private static double area(List<RoadGeometry.V> p) {
      if (p.size() < 3) {
         return 0.0;
      } else {
         double sum = 0.0;
         RoadGeometry.V origin = p.get(0);

         for (int i = 1; i < p.size() - 1; i++) {
            sum += cross(p.get(i).sub(origin), p.get(i + 1).sub(origin));
         }

         return Math.abs(sum) / 2.0;
      }
   }

   private static double cross(RoadGeometry.V a, RoadGeometry.V b) {
      return a.x() * b.z() - a.z() * b.x();
   }

   private static int cell(double x) {
      return (int)Math.floor(x / 16.0);
   }

   private static long key(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   private RoadClearance() {
   }

   private static final class ClipScratch {
      double[] first = new double[48];
      double[] second = new double[48];

      int intersection(List<RoadGeometry.V> subject, List<RoadGeometry.V> clip) {
         int n = 0;

         for (RoadGeometry.V v : subject) {
            this.first[n++] = v.x();
            this.first[n++] = v.y();
            this.first[n++] = v.z();
         }

         for (int i = 0; i < clip.size() && n > 0; i++) {
            RoadGeometry.V a = clip.get(i);
            RoadGeometry.V b = clip.get((i + 1) % clip.size());
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            int prev = n - 3;
            int count = 0;
            double dp = dx * (this.first[prev + 2] - a.z()) - dz * (this.first[prev] - a.x());

            for (int cur = 0; cur < n; cur += 3) {
               double dc = dx * (this.first[cur + 2] - a.z()) - dz * (this.first[cur] - a.x());
               boolean pin = dp >= -1.0E-8;
               boolean cin = dc >= -1.0E-8;
               if (pin != cin) {
                  double den = dp - dc;
                  if (Math.abs(den) > 1.0E-8) {
                     double alpha = dp / den;

                     for (int k = 0; k < 3; k++) {
                        this.second[count++] = this.first[prev + k] + (this.first[cur + k] - this.first[prev + k]) * alpha;
                     }
                  }
               }

               if (cin) {
                  this.second[count++] = this.first[cur];
                  this.second[count++] = this.first[cur + 1];
                  this.second[count++] = this.first[cur + 2];
               }

               prev = cur;
               dp = dc;
            }

            double[] swap = this.first;
            this.first = this.second;
            this.second = swap;
            n = count;
         }

         return n;
      }

      double area(int n) {
         if (n < 9) {
            return 0.0;
         } else {
            double sum = 0.0;

            for (int i = 3; i < n - 3; i += 3) {
               sum += (this.first[i] - this.first[0]) * (this.first[i + 5] - this.first[2])
                  - (this.first[i + 2] - this.first[2]) * (this.first[i + 3] - this.first[0]);
            }

            return Math.abs(sum) / 2.0;
         }
      }
   }

   public static final class Conflict extends IllegalArgumentException {
      private final RoadClearance.Contact contact;

      public Conflict(RoadClearance.Contact c) {
         super(
            String.format(
               Locale.ROOT,
               "道路投影交叠于 X=%.2f Z=%.2f；新路 Y=%.2f、既有路 Y=%.2f，%s / 要求净空 %.2f 格",
               c.ours().x(),
               c.ours().z(),
               c.ours().y(),
               c.other().y(),
               RoadClearance.clearanceLabel(c.usableClearance()),
               c.required()
            )
         );
         this.contact = c;
      }

      public RoadClearance.Contact contact() {
         return this.contact;
      }
   }

   public static record Contact(
      double from,
      double to,
      RoadGeometry.V ours,
      RoadGeometry.V other,
      double usableClearance,
      double raise,
      double lower,
      double required,
      double raiseBound,
      double lowerBound
   ) {
      public Contact(double from, double to, RoadGeometry.V ours, RoadGeometry.V other, double usableClearance, double raise, double lower, double required) {
         this(from, to, ours, other, usableClearance, raise, lower, required, raise, lower);
      }

      public Contact(double from, double to, RoadGeometry.V ours, RoadGeometry.V other, double usableClearance, double raise, double lower) {
         this(from, to, ours, other, usableClearance, raise, lower, 4.0);
      }

      public boolean blocked() {
         return this.usableClearance < this.required - 0.025;
      }
   }

   public static record DetailedContact(RoadClearance.Contact contact, double otherFrom, double otherTo, double minimumDifference, double maximumDifference) {
      public boolean coplanar() {
         return Math.abs(this.minimumDifference) <= 1.0E-7 && Math.abs(this.maximumDifference) <= 1.0E-7;
      }
   }

   private static final class Grid {
      final Map<Long, List<RoadClearance.Grid.Indexed>> cells = new HashMap<>();
      final int size;

      Grid(RoadGeometry.Mesh mesh) {
         this(mesh, false);
      }

      Grid(RoadGeometry.Mesh mesh, boolean driving) {
         Map<RoadClearance.Triangle, RoadClearance.Grid.Indexed> unique = new HashMap<>();

         for (RoadClearance.Triangle t : driving ? RoadClearance.makeTriangles(mesh, true) : RoadClearance.triangles(mesh)) {
            RoadClearance.Grid.Indexed indexed = unique.get(t);
            if (indexed == null) {
               indexed = new RoadClearance.Grid.Indexed(t, unique.size());
               unique.put(t, indexed);
            }

            for (int x = RoadClearance.cell(t.minX()); x <= RoadClearance.cell(t.maxX()); x++) {
               for (int z = RoadClearance.cell(t.minZ()); z <= RoadClearance.cell(t.maxZ()); z++) {
                  this.cells.computeIfAbsent(RoadClearance.key(x, z), k -> new ArrayList<>()).add(indexed);
               }
            }
         }

         this.size = unique.size();
      }

      List<RoadClearance.Triangle> near(RoadClearance.Triangle t) {
         return this.nearAfter(t, Double.NEGATIVE_INFINITY);
      }

      List<RoadClearance.Triangle> nearAfter(RoadClearance.Triangle t, double station) {
         RoadClearance.NearScratch scratch = RoadClearance.NEAR.get();
         scratch.begin(this.size);

         for (int x = RoadClearance.cell(t.minX()); x <= RoadClearance.cell(t.maxX()); x++) {
            for (int z = RoadClearance.cell(t.minZ()); z <= RoadClearance.cell(t.maxZ()); z++) {
               List<RoadClearance.Grid.Indexed> bucket = this.cells.getOrDefault(RoadClearance.key(x, z), List.of());
               int first = 0;
               if (Double.isFinite(station)) {
                  int end = bucket.size();

                  while (first < end) {
                     int mid = first + end >>> 1;
                     if (bucket.get(mid).triangle().maxStation <= station) {
                        first = mid + 1;
                     } else {
                        end = mid;
                     }
                  }
               }

               for (int i = first; i < bucket.size(); i++) {
                  RoadClearance.Grid.Indexed indexed = bucket.get(i);
                  RoadClearance.Triangle q = indexed.triangle();
                  if (q.maxX() > t.minX() + 1.0E-8
                     && q.minX() < t.maxX() - 1.0E-8
                     && q.maxZ() > t.minZ() + 1.0E-8
                     && q.minZ() < t.maxZ() - 1.0E-8
                     && scratch.seen[indexed.id()] != scratch.stamp) {
                     scratch.seen[indexed.id()] = scratch.stamp;
                     scratch.result.add(q);
                  }
               }
            }
         }

         return scratch.result;
      }

      private static record Indexed(RoadClearance.Triangle triangle, int id) {
      }
   }

   private static final class NearScratch {
      int[] seen = new int[0];
      int stamp;
      final ArrayList<RoadClearance.Triangle> result = new ArrayList<>();

      void begin(int size) {
         if (this.seen.length < size) {
            this.seen = new int[size];
         }

         if (++this.stamp == 0) {
            Arrays.fill(this.seen, 0);
            this.stamp = 1;
         }

         this.result.clear();
      }
   }

   public static final class Prepared {
      private final RoadGeometry.Mesh mesh;
      private final RoadClearance.Grid grid;

      private Prepared(RoadGeometry.Mesh mesh) {
         this.mesh = mesh;
         this.grid = RoadClearance.grid(mesh);
      }
   }

   private static final class Triangle {
      final RoadGeometry.V a;
      final RoadGeometry.V b;
      final RoadGeometry.V c;
      final double sa;
      final double sb;
      final double sc;
      final double minStation;
      final double maxStation;
      final double dx;
      final double dz;
      final double ex;
      final double ez;
      final double det;
      final double minX;
      final double maxX;
      final double minZ;
      final double maxZ;
      final List<RoadGeometry.V> polygon;
      final int hash;

      Triangle(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c, double sa, double sb, double sc) {
         this.a = a;
         this.b = b;
         this.c = c;
         this.sa = sa;
         this.sb = sb;
         this.sc = sc;
         this.minStation = Math.min(sa, Math.min(sb, sc));
         this.maxStation = Math.max(sa, Math.max(sb, sc));
         this.dx = b.x() - a.x();
         this.dz = b.z() - a.z();
         this.ex = c.x() - a.x();
         this.ez = c.z() - a.z();
         this.det = this.dx * this.ez - this.dz * this.ex;
         this.minX = Math.min(a.x(), Math.min(b.x(), c.x()));
         this.maxX = Math.max(a.x(), Math.max(b.x(), c.x()));
         this.minZ = Math.min(a.z(), Math.min(b.z(), c.z()));
         this.maxZ = Math.max(a.z(), Math.max(b.z(), c.z()));
         this.polygon = this.det >= 0.0 ? List.of(a, b, c) : List.of(a, c, b);
         this.hash = Objects.hash(a, b, c, sa, sb, sc);
      }

      double det() {
         return this.det;
      }

      double value(RoadGeometry.V p, double va, double vb, double vc) {
         return this.value(p.x(), p.z(), va, vb, vc);
      }

      double value(double x, double z, double va, double vb, double vc) {
         double qx = x - this.a.x();
         double qz = z - this.a.z();
         return va + (qx * this.ez - qz * this.ex) / this.det * (vb - va) + (this.dx * qz - this.dz * qx) / this.det * (vc - va);
      }

      double height(RoadGeometry.V p) {
         return this.value(p, this.a.y(), this.b.y(), this.c.y());
      }

      double station(RoadGeometry.V p) {
         return this.value(p, this.sa, this.sb, this.sc);
      }

      double height(double x, double z) {
         return this.value(x, z, this.a.y(), this.b.y(), this.c.y());
      }

      double station(double x, double z) {
         return this.value(x, z, this.sa, this.sb, this.sc);
      }

      List<RoadGeometry.V> polygon() {
         return this.polygon;
      }

      double minX() {
         return this.minX;
      }

      double maxX() {
         return this.maxX;
      }

      double minZ() {
         return this.minZ;
      }

      double maxZ() {
         return this.maxZ;
      }

      @Override
      public int hashCode() {
         return this.hash;
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         } else {
            if (o instanceof RoadClearance.Triangle t
               && this.a.equals(t.a)
               && this.b.equals(t.b)
               && this.c.equals(t.c)
               && Double.compare(this.sa, t.sa) == 0
               && Double.compare(this.sb, t.sb) == 0
               && Double.compare(this.sc, t.sc) == 0) {
               return true;
            }

            return false;
         }
      }
   }
}
