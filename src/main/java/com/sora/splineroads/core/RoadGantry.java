package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public final class RoadGantry {
   public static final int MANUAL_START = 256;
   public static final int MAX_SLOTS = 512;

   public static int count(RoadGeometry.Mesh m) {
      if (m.settings().options().infrastructure().fixedStations()) {
         return (int)m.settings().options().infrastructure().gantryEdits().stream().filter(e -> !e.independent()).count();
      } else {
         return m.settings().structure() != RoadGeometry.Structure.TUNNEL
               && (!m.settings().style().ramp() || m.settings().style().connectorRamp())
               && !(m.length() < 20.0)
            ? Math.max(1, (int)Math.floor(m.length() / m.settings().options().infrastructure().spacing()))
            : 0;
      }
   }

   public static RoadGantry.Edit edit(RoadInfrastructure.Config c, int slot) {
      return c.gantryEdits().stream().filter(e -> e.slot() == slot).findFirst().orElseGet(() -> RoadGantry.Edit.defaults(slot));
   }

   public static List<Integer> slots(RoadGeometry.Mesh m) {
      if (m.settings().options().infrastructure().fixedStations()) {
         return m.settings().options().infrastructure().gantryEdits().stream().map(RoadGantry.Edit::slot).toList();
      } else {
         ArrayList<Integer> slots = new ArrayList<>();

         for (int i = 0; i < count(m); i++) {
            slots.add(i);
         }

         for (RoadGantry.Edit e : m.settings().options().infrastructure().gantryEdits()) {
            if (e.independent()) {
               slots.add(e.slot());
            }
         }

         return List.copyOf(slots);
      }
   }

   public static boolean exists(RoadGeometry.Mesh m, int slot) {
      return slot >= 0
         && (
            !m.settings().options().infrastructure().fixedStations() && slot < count(m)
               || m.settings().options().infrastructure().gantryEdits().stream().anyMatch(e -> (e.independent() || e.anchored()) && e.slot() == slot)
         );
   }

   public static double maxOffset(RoadGeometry.Mesh m, int slot) {
      RoadGantry.Edit e = edit(m.settings().options().infrastructure(), slot);
      return Math.min(
         256.0,
         !e.independent() && !e.anchored()
            ? Math.max(0.0, m.length() / (double)Math.max(1, count(m)) / 2.0 - 3.0)
            : Math.max(0.0, Math.min(e.position() * m.length() - 2.0, (1.0 - e.position()) * m.length() - 2.0))
      );
   }

   public static int playerFlow(RoadGeometry.Sample sample, RoadGeometry.V look) {
      RoadGeometry.V direction = new RoadGeometry.V(look.x(), 0.0, look.z());
      return direction.dot(sample.left().left().mul(-1.0)) >= 0.0 ? 1 : -1;
   }

   public static RoadGantry.Edit create(RoadGeometry.Mesh m, double station) {
      if (m.settings().structure() != RoadGeometry.Structure.TUNNEL
         && !m.settings().style().ramp()
         && !(m.length() < 6.0)
         && !(station < 2.0)
         && !(station > m.length() - 2.0)) {
         RoadInfrastructure.Config c = m.settings().options().infrastructure();

         for (int slot : slots(m)) {
            RoadGantry.Station old = station(m, slot);
            if (old.kind() != RoadInfrastructure.Gantry.OFF && Math.abs(old.distance() - station) < 4.0) {
               throw new IllegalArgumentException("此处已有龙门架，请与现有架体保持至少4格间距");
            }
         }

         for (int slotx = 256; slotx < 512; slotx++) {
            if (!exists(m, slotx)) {
               return new RoadGantry.Edit(slotx, 0.0, 5.6, RoadInfrastructure.Gantry.SIGNS, false, true, station / m.length());
            }
         }

         throw new IllegalArgumentException("每段道路最多放置256座独立龙门架，请拆分路段");
      } else {
         throw new IllegalArgumentException("请在普通道路或高速道路上、离端点至少2格的位置创建龙门架");
      }
   }

   public static RoadGantry.Station station(RoadGeometry.Mesh m, int slot) {
      int n = count(m);
      if (!exists(m, slot)) {
         throw new IllegalArgumentException("该龙门架位置已失效，请重新选择");
      } else {
         RoadInfrastructure.Config c = m.settings().options().infrastructure();
         RoadGantry.Edit e = edit(c, slot);
         double maximum = maxOffset(m, slot);
         if (Math.abs(e.offset()) > maximum + 1.0E-7) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "该龙门架可前后移动最多 %.1f 格", maximum));
         } else {
            double d = (!e.independent() && !e.anchored() ? ((double)slot + 0.5) * m.length() / (double)n : e.position() * m.length()) + e.offset();
            RoadGeometry.Sample s = RoadStructures.sample(m, d);
            RoadInfrastructure.Gantry kind = e.independent()
               ? (e.kind() == RoadInfrastructure.Gantry.AUTO ? RoadInfrastructure.Gantry.SIGNS : e.kind())
               : (
                  c.gantry() == RoadInfrastructure.Gantry.OFF
                     ? RoadInfrastructure.Gantry.OFF
                     : (e.kind() == RoadInfrastructure.Gantry.AUTO ? c.gantry() : e.kind())
               );
            if (kind == RoadInfrastructure.Gantry.FRAME && !e.anchored()) {
               RoadGeometry.V f = s.left().left().mul(-1.0);
               RoadGeometry.V p = s.center();
               if (Math.abs(f.x()) > 0.999999 || Math.abs(f.z()) > 0.999999) {
                  double coordinate = Math.abs(f.x()) > 0.999999 ? p.x() : p.z();
                  double move = (Math.floor(coordinate) + 0.5 - coordinate) * (Math.abs(f.x()) > 0.999999 ? f.x() : f.z());
                  d += move;
                  s = RoadStructures.sample(m, d);
               }
            }

            return new RoadGantry.Station(slot, d, s, e, kind);
         }
      }
   }

   private static boolean unsupported(RoadGeometry.Mesh mesh, RoadGantry.Station station) {
      for (double delta : new double[]{-0.6, 0.0, 0.6}) {
         for (int side : new int[]{-1, 1}) {
            if (LaneDeck.outerOpening(mesh, Math.max(0.0, Math.min(mesh.length(), station.distance() + delta)), side)) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean enabled(RoadGeometry.Mesh m, RoadStructures.Ground ground, RoadGantry.Station st) {
      if (m.settings().structure() != RoadGeometry.Structure.TUNNEL
         && st.kind() != RoadInfrastructure.Gantry.OFF
         && !unsupported(m, st)
         && !RoadStreetscape.underground(m, st.distance())
         && (m.settings().structure() != RoadGeometry.Structure.AUTO || !RoadAutoTunnels.below(m, st.sample(), ground))) {
         RoadGeometry.Sample s = st.sample();
         return !ground.furnitureClear(s.center()) && !ground.joined(s.center());
      } else {
         return false;
      }
   }

   public static boolean near(RoadGeometry.Mesh m, RoadStructures.Ground ground, double d) {
      for (int i : slots(m)) {
         RoadGantry.Station st = station(m, i);
         if (Math.abs(d - st.distance()) < 5.0 && enabled(m, ground, st)) {
            return true;
         }
      }

      return false;
   }

   public static boolean member(RoadStructures.Part p) {
      return p.model().startsWith("sr:gantry/") || p.material() == RoadStructures.Material.GANTRY_FRAME;
   }

   public static List<RoadStructures.Part> plan(RoadGeometry.Mesh m, RoadStructures.Ground ground) {
      return plan(m, ground, List.of());
   }

   public static List<RoadStructures.Part> plan(RoadGeometry.Mesh m, RoadStructures.Ground ground, Collection<RoadStructures.Part> occupied) {
      return plan(m, ground, occupied, false);
   }

   public static List<RoadStructures.Part> required(RoadGeometry.Mesh m, RoadStructures.Ground ground, Collection<RoadStructures.Part> fixed) {
      return m.settings().structure() == RoadGeometry.Structure.TUNNEL ? List.of() : plan(m, ground, fixed, true);
   }

   private static boolean edited(RoadGeometry.Mesh m, int slot) {
      return m.settings().options().infrastructure().gantryEdits().stream().anyMatch(e -> e.slot() == slot && !e.automatic());
   }

   private static List<RoadStructures.Part> plan(
      RoadGeometry.Mesh m, RoadStructures.Ground ground, Collection<RoadStructures.Part> occupied, boolean requiredOnly
   ) {
      List<Integer> stations = slots(m).stream().filter(ix -> !requiredOnly || edited(m, ix) && ground.requiredGantry(station(m, ix))).toList();
      if (stations.stream().noneMatch(ix -> ground.preservedGantry(station(m, ix)) != null || enabled(m, ground, station(m, ix)))) {
         return List.of();
      } else {
         ArrayList<RoadStructures.Part> fixtures = new ArrayList<>(occupied);
         List<RoadStructures.Part> fixed = ground.fixedSupports();
         if (fixed != null) {
            fixtures.addAll(fixed);
         }

         List<RoadStructures.Part> hard = fixtures.stream().filter(p -> requiredOnly || p.pier() || member(p)).toList();
         RoadSolidOverlap.Index solids = new RoadSolidOverlap.Index(hard);
         RoadSolidOverlap.Index boundaries = new RoadSolidOverlap.Index(
            fixtures.stream().filter(p -> !requiredOnly && !p.pier() && !member(p) && RoadGantryMounts.boundaryPart(m, p)).toList()
         );
         ArrayList<RoadStructures.Part> out = new ArrayList<>();

         for (int i : stations) {
            RoadGantry.Station st = station(m, i);
            if (enabled(m, ground, st)) {
               List<RoadStructures.Part> preserved = ground.preservedGantry(st);
               if (preserved != null) {
                  out.addAll(preserved);
               } else {
                  List<RoadStructures.Part> parts = fittedParts(m, st, ground, solids, boundaries);
                  if (parts == null) {
                     if (edited(m, st.slot()) && ground.requiredGantry(st)) {
                        throw conflict(st);
                     }
                  } else {
                     out.addAll(parts);
                  }
               }
            }
         }

         return List.copyOf(out);
      }
   }

   public static List<RoadStructures.Part> members(RoadGeometry.Mesh mesh, int slot, final List<RoadStructures.Part> saved) {
      String tag = "sr:gantry/" + slot;
      RoadGantry.Station st = station(mesh, slot);
      HashSet<RoadStructures.Part> templates = new HashSet<>();

      for (final boolean elevated : new boolean[]{false, true}) {
         var ground = new RoadStructures.Ground() {
            @Override
            public boolean joined(RoadGeometry.V p) {
               return false;
            }

            @Override
            public boolean blocked(RoadStructures.Part p) {
               return false;
            }

            @Override
            public double top(double x, double z, double y) {
               return elevated
                  ? Double.NaN
                  : saved.stream()
                     .filter(
                        p -> p.pier()
                              && p.material() == RoadStructures.Material.STEEL
                              && Math.abs(p.width() - 0.65) < 1.0E-8
                              && Math.hypot(p.a().x() - x, p.a().z() - z) < 1.0E-7
                     )
                     .mapToDouble(p -> p.a().y())
                     .findFirst()
                     .orElse(Double.NaN);
            }
         };

         for (RoadStructures.Part p : parts(mesh, st, ground)) {
            templates.add(untagged(p));
         }
      }

      return saved.stream().filter(px -> px.model().equals(tag) || px.model().isEmpty() && templates.contains(px)).toList();
   }

   private static RoadStructures.Part untagged(RoadStructures.Part p) {
      return new RoadStructures.Part(p.a(), p.b(), p.width(), p.height(), p.pier(), p.material(), p.frameA(), p.frameB(), "", p.heights());
   }

   private static List<RoadStructures.Part> tagged(List<RoadStructures.Part> parts, int slot) {
      return parts.stream()
         .map(
            p -> new RoadStructures.Part(p.a(), p.b(), p.width(), p.height(), p.pier(), p.material(), p.frameA(), p.frameB(), "sr:gantry/" + slot, p.heights())
         )
         .toList();
   }

   public static List<RoadStructures.Part> planStation(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, int slot) {
      RoadGantry.Station st = station(mesh, slot);
      if (st.kind() == RoadInfrastructure.Gantry.OFF) {
         return List.of();
      } else if (!enabled(mesh, ground, st)) {
         throw new IllegalArgumentException("此位置无法安装龙门架，请调整位移或类型");
      } else {
         List<RoadStructures.Part> parts = fittedParts(mesh, st, ground, new RoadSolidOverlap.Index(List.of()), new RoadSolidOverlap.Index(List.of()));
         if (parts == null) {
            throw conflict(st);
         } else {
            return parts;
         }
      }
   }

   private static List<RoadStructures.Part> fittedParts(
      RoadGeometry.Mesh mesh, RoadGantry.Station st, RoadStructures.Ground ground, RoadSolidOverlap.Index solids, RoadSolidOverlap.Index boundaries
   ) {
      List<RoadStructures.Part> original = parts(mesh, st, ground);
      boolean negative = false;
      boolean positive = false;

      for (RoadStructures.Part p : original) {
         boolean blocked = ground.blocked(p);
         boolean boundary = boundaries.intersects(p);
         if (solids.intersects(p)) {
            return null;
         }

         if (blocked || boundary) {
            if (!RoadGantryMounts.foot(p) || blocked && !ground.gantryMountConflict(p, st)) {
               return null;
            }

            if (p.a().sub(st.sample().center()).dot(st.sample().left()) < 0.0) {
               negative = true;
            } else {
               positive = true;
            }
         }
      }

      if (!negative && !positive) {
         return original;
      } else {
         double[] shifts = new double[]{0.0, 0.2, -0.2, 0.4, -0.4, 0.6, -0.6, 1.0, 1.4, 2.0, 3.0};
         List<RoadStructures.Part> feet = original.stream().filter(p -> p.pier() && p.material() == RoadStructures.Material.STEEL).toList();

         for (double pedestal : new double[]{1.2, 1.0, 0.95}) {
            ArrayList<Double> first = new ArrayList<>();
            first.add(0.0);
            ArrayList<Double> last = new ArrayList<>();
            last.add(0.0);

            for (RoadStructures.Part foot : feet) {
               boolean minus = foot.a().sub(st.sample().center()).dot(st.sample().left()) < 0.0;
               if (minus ? negative : positive) {
                  ArrayList<Double> list = minus ? first : last;

                  for (double shift : shifts) {
                     if (shift != 0.0) {
                        list.add(shift);
                     }
                  }

                  list.add(RoadGantryMounts.tightOffset(mesh, st, minus ? -1 : 1, pedestal, foot.a()));
               }
            }

            ArrayList<double[]> candidates = new ArrayList<>();

            for (double a : first) {
               for (double b : last) {
                  if (pedestal != 1.2 || a != 0.0 || b != 0.0) {
                     candidates.add(new double[]{a, b});
                  }
               }
            }

            candidates.sort(Comparator.comparingDouble(v -> Math.abs(v[0]) + Math.abs(v[1])));

            for (double[] shiftx : candidates) {
               RoadPlanningBudget.check();
               List<RoadStructures.Part> candidate = parts(mesh, st, ground, shiftx[0], shiftx[1], negative ? pedestal : 1.2, positive ? pedestal : 1.2);
               boolean clear = true;

               for (RoadStructures.Part p : candidate) {
                  if (ground.blocked(p)
                     || solids.intersects(p)
                     || boundaries.intersects(p)
                     || RoadGantryMounts.foot(p) && RoadGantryMounts.blockedByRoad(p, mesh, 4.25)) {
                     clear = false;
                     break;
                  }
               }

               if (clear) {
                  return candidate;
               }
            }
         }

         return null;
      }
   }

   private static IllegalArgumentException conflict(RoadGantry.Station station) {
      RoadGeometry.V p = station.sample().center();
      return new IllegalArgumentException(String.format(Locale.ROOT, "龙门架（%.0f, %.0f, %.0f）与道路或设施冲突，请调整架位或净高", p.x(), p.y(), p.z()));
   }

   private static RoadGeometry.V at(RoadGeometry.Sample s, double lateral, double up) {
      return s.at(lateral, -up);
   }

   private static void beam(List<RoadStructures.Part> out, RoadGeometry.V a, RoadGeometry.V b, double width, double height, RoadStructures.Material mat) {
      out.add(new RoadStructures.Part(a, b, width, height, false, mat));
   }

   private static void post(List<RoadStructures.Part> out, RoadGeometry.V a, double width, double height, RoadStructures.Material mat) {
      if (height > 0.001) {
         out.add(new RoadStructures.Part(a, a, width, height, true, mat));
      }
   }

   public static double lowerBeamY(RoadGantry.Station st) {
      double y = st.sample().center().y() + st.edit().clearance() + 1.5;
      return st.kind() == RoadInfrastructure.Gantry.FRAME ? Math.ceil(y) : y;
   }

   public static double upperBeamCenterY(RoadGantry.Station st) {
      return lowerBeamY(st) + (st.kind() == RoadInfrastructure.Gantry.FRAME ? 0.9099999999999999 : 0.85);
   }

   public static List<RoadStructures.Part> parts(RoadGeometry.Mesh mesh, RoadGantry.Station st, RoadStructures.Ground ground) {
      return parts(mesh, st, ground, 0.0, 0.0, 1.2, 1.2);
   }

   private static List<RoadStructures.Part> parts(
      RoadGeometry.Mesh mesh,
      RoadGantry.Station st,
      RoadStructures.Ground ground,
      double negativeShift,
      double positiveShift,
      double negativeBase,
      double positiveBase
   ) {
      if (st.kind() != RoadInfrastructure.Gantry.OFF && !unsupported(mesh, st)) {
         RoadGeometry.Sample s = st.sample();
         RoadProfile.Catalog profile = RoadProfile.catalog(mesh.settings().style());
         ArrayList<RoadStructures.Part> parts = new ArrayList<>();
         boolean equipment = st.kind() == RoadInfrastructure.Gantry.FRAME;
         double edge = s.halfWidth() + 1.3;
         double leftEdge = edge + negativeShift;
         double rightEdge = edge + positiveShift;
         double clear = st.edit().clearance();
         double lower = clear + 1.5;
         if (equipment) {
            lower = Math.ceil(s.center().y() + lower) - s.center().y();
         }

         RoadGeometry.V left = at(s, -leftEdge, 0.0);
         RoadGeometry.V right = at(s, rightEdge, 0.0);
         if (equipment && (Math.abs(s.left().x()) > 0.999999 || Math.abs(s.left().z()) > 0.999999)) {
            RoadGeometry.V originalLeft = at(s, -edge, 0.0);
            RoadGeometry.V originalRight = at(s, edge, 0.0);
            left = new RoadGeometry.V(Math.floor(originalLeft.x()) + 0.5, originalLeft.y(), Math.floor(originalLeft.z()) + 0.5)
               .add(s.left().mul(-negativeShift));
            right = new RoadGeometry.V(Math.floor(originalRight.x()) + 0.5, originalRight.y(), Math.floor(originalRight.z()) + 0.5)
               .add(s.left().mul(positiveShift));
         }

         for (RoadGeometry.V foot : List.of(left, right)) {
            double underside = s.center().y() - mesh.settings().thickness();
            double floor = ground.top(foot.x(), foot.z(), s.center().y() + 2.0);
            boolean grounded = Double.isFinite(floor) && Math.abs(s.center().y() - floor) < 2.0;
            RoadSidewalks.Config walk = mesh.settings().options().sidewalk();
            int walkSide = foot.sub(s.center()).dot(s.left()) < 0.0 ? -1 : 1;
            boolean onWalk = walk.enabled()
               && walk.smooth()
               && (
                  walk.side() == RoadSidewalks.Side.BOTH
                     || walk.side() == RoadSidewalks.Side.LEFT && walkSide < 0
                     || walk.side() == RoadSidewalks.Side.RIGHT && walkSide > 0
               )
               && Math.abs(foot.sub(s.center()).dot(s.left())) + 0.6 <= s.halfWidth() + (double)walk.width();
            double base = onWalk ? s.center().y() + 0.2 : (grounded ? floor : s.center().y());
            double pedestal = foot.sub(s.center()).dot(s.left()) < 0.0 ? negativeBase : positiveBase;
            post(parts, new RoadGeometry.V(foot.x(), base, foot.z()), 0.65, s.center().y() + lower + 0.9 - base, RoadStructures.Material.STEEL);
            post(parts, new RoadGeometry.V(foot.x(), base, foot.z()), pedestal, 0.45, RoadStructures.Material.CONCRETE);
            post(parts, new RoadGeometry.V(foot.x(), base + 0.45, foot.z()), 0.85, 0.12, RoadStructures.Material.DARK_STEEL);
            double side = foot.sub(s.center()).dot(s.left()) < 0.0 ? -1.0 : 1.0;
            if (!grounded && !onWalk) {
               beam(
                  parts,
                  at(s, side * (s.halfWidth() - 0.5), -0.57),
                  new RoadGeometry.V(foot.x(), s.center().y() - 0.57, foot.z()),
                  1.2,
                  0.45,
                  RoadStructures.Material.CONCRETE
               );
               post(parts, new RoadGeometry.V(foot.x(), s.center().y() - 0.12, foot.z()), pedestal, 0.12, RoadStructures.Material.CONCRETE);
            }
         }

         if (equipment) {
            RoadGeometry.V forward = s.left().left().mul(-1.0);
            beam(
               parts,
               left.add(new RoadGeometry.V(0.0, lower, 0.0)),
               right.add(new RoadGeometry.V(0.0, lower, 0.0)),
               1.0,
               0.18,
               RoadStructures.Material.GANTRY_FRAME
            );
            beam(
               parts,
               left.add(new RoadGeometry.V(0.0, lower + 0.82, 0.0)),
               right.add(new RoadGeometry.V(0.0, lower + 0.82, 0.0)),
               1.0,
               0.18,
               RoadStructures.Material.GANTRY_FRAME
            );
            RoadGeometry.V axis = right.sub(left);
            int bays = Math.max(2, (int)Math.ceil(axis.horizontalLength() / 2.8));

            for (int i = 0; i < bays; i++) {
               for (int face : new int[]{-1, 1}) {
                  RoadGeometry.V a = left.add(axis.mul((double)i / (double)bays)).add(forward.mul((double)face * 0.42));
                  RoadGeometry.V b = left.add(axis.mul((double)(i + 1) / (double)bays)).add(forward.mul((double)face * 0.42));
                  beam(
                     parts,
                     a.add(new RoadGeometry.V(0.0, lower + (i % 2 == 0 ? 0.18 : 0.72), 0.0)),
                     b.add(new RoadGeometry.V(0.0, lower + (i % 2 == 0 ? 0.72 : 0.18), 0.0)),
                     0.12,
                     0.1,
                     RoadStructures.Material.GANTRY_FRAME
                  );
               }
            }

            for (RoadGeometry.V footx : List.of(left, right)) {
               double sign = footx.sub(s.center()).dot(s.left()) < 0.0 ? 1.0 : -1.0;
               beam(
                  parts,
                  footx.add(new RoadGeometry.V(0.0, lower - 1.25, 0.0)),
                  footx.add(s.left().mul(sign * 1.6)).add(new RoadGeometry.V(0.0, lower, 0.0)),
                  0.22,
                  0.22,
                  RoadStructures.Material.STEEL
               );
            }

            return tagged(parts, st.slot());
         } else {
            beam(parts, at(s, -leftEdge, lower), at(s, rightEdge, lower), 0.45, 0.45, RoadStructures.Material.STEEL);
            beam(parts, at(s, -leftEdge, lower + 0.7), at(s, rightEdge, lower + 0.7), 0.45, 0.3, RoadStructures.Material.STEEL);

            for (double u = -leftEdge; u < rightEdge; u += 3.0) {
               beam(parts, at(s, u, lower + 0.15), at(s, Math.min(rightEdge, u + 3.0), lower + 0.7), 0.12, 0.12, RoadStructures.Material.DARK_STEEL);
            }

            return tagged(parts, st.slot());
         }
      } else {
         return List.of();
      }
   }

   private RoadGantry() {
   }

   public static record Edit(
      int slot,
      double offset,
      double clearance,
      RoadInfrastructure.Gantry kind,
      boolean reverse,
      boolean independent,
      double position,
      boolean anchored,
      boolean automatic
   ) {
      public Edit(
         int slot, double offset, double clearance, RoadInfrastructure.Gantry kind, boolean reverse, boolean independent, double position, boolean anchored
      ) {
         this(slot, offset, clearance, kind, reverse, independent, position, anchored, false);
      }

      public Edit(int slot, double offset, double clearance, RoadInfrastructure.Gantry kind, boolean reverse, boolean independent, double position) {
         this(slot, offset, clearance, kind, reverse, independent, position, false);
      }

      public Edit(int slot, double offset, double clearance, RoadInfrastructure.Gantry kind, boolean reverse) {
         this(slot, offset, clearance, kind, reverse, false, 0.0);
      }

      public Edit(
         int slot,
         double offset,
         double clearance,
         RoadInfrastructure.Gantry kind,
         boolean reverse,
         boolean independent,
         double position,
         boolean anchored,
         boolean automatic
      ) {
         if (slot >= 0
            && slot < 512
            && slot >= 256 == independent
            && !(position < 0.0)
            && !(position > 1.0)
            && kind != null
            && (!automatic || anchored && !independent)
            && RoadGeometry.finite(offset, clearance, position)
            && !(Math.abs(offset) > 256.0)
            && !(clearance < 5.0)
            && !(clearance > 16.0)) {
            this.slot = slot;
            this.offset = offset;
            this.clearance = clearance;
            this.kind = kind;
            this.reverse = reverse;
            this.independent = independent;
            this.position = position;
            this.anchored = anchored;
            this.automatic = automatic;
         } else {
            throw new IllegalArgumentException("龙门架位移 -256–256 格，净高 5–16 格");
         }
      }

      public RoadGantry.Edit parameters(double shift, double height, RoadInfrastructure.Gantry style, boolean flip) {
         return new RoadGantry.Edit(this.slot, shift, height, style, flip, this.independent, this.position, this.anchored, false);
      }

      public static RoadGantry.Edit defaults(int slot) {
         return new RoadGantry.Edit(slot, 0.0, 5.6, RoadInfrastructure.Gantry.AUTO, false);
      }
   }

   public static record Station(int slot, double distance, RoadGeometry.Sample sample, RoadGantry.Edit edit, RoadInfrastructure.Gantry kind) {
   }
}
