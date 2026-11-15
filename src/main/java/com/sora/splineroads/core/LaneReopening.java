package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LaneReopening {
   private static final double SIDE_MARGIN = 0.3;
   private static final double END_MARGIN = 1.0;
   private static final ConcurrentHashMap<Integer, WeakIdentityCache<RoadGeometry.Mesh, RoadGeometry.Mesh>> SWEEPS = new ConcurrentHashMap<>();

   public static double restoreStation(
      RoadGeometry.Mesh host, int slot, double begin, RoadGeometry.Mesh ramp, List<RoadStructures.Part> parts, double transition
   ) {
      return restoreStation(host, slot, begin, ramp, parts, transition, false);
   }

   public static double restoreStation(
      RoadGeometry.Mesh host, int slot, double begin, RoadGeometry.Mesh ramp, List<RoadStructures.Part> parts, double transition, boolean rectangular
   ) {
      RoadGeometry.Mesh raw = LaneSections.reference(host);
      int sign = LanePoints.lane(raw, begin, slot).sign();
      RoadGeometry.Mesh sweep = laneSweep(raw, slot);
      RoadClearance.Prepared prepared = RoadClearance.prepare(sweep);
      double last = (double)sign * begin;
      last = lastBlocked(prepared, ramp, begin, sign, last);

      for (RoadStructures.Part part : parts) {
         RoadPlanningBudget.check();

         for (RoadClearance.Contact contact : structureContacts(prepared, sweep, part, ramp.settings())) {
            if (contact.blocked()) {
               double d = sign > 0 ? contact.to() : -contact.from();
               if (d >= (double)sign * begin - 0.01) {
                  last = Math.max(last, d);
               }
            }
         }
      }

      double reopen = rectangular ? Math.max((double)sign * begin + 0.25, last + 1.0) : Math.max((double)sign * begin + 2.0 * transition, last + 1.0);
      double end = (reopen + (rectangular ? 0.0 : transition)) * (double)sign;
      if (!(end < 0.001) && !(end > raw.length() - 0.001)) {
         return end;
      } else {
         throw new IllegalArgumentException("匝道/结构未在本路段让出通行空间，或不足以渐变恢复车道；请延长主路或调整汇入位置/高程");
      }
   }

   public static double closeBeforeStation(
      RoadGeometry.Mesh host, int slot, double end, RoadGeometry.Mesh ramp, List<RoadStructures.Part> parts, double transition
   ) {
      return closeBeforeStation(host, slot, end, ramp, parts, transition, false);
   }

   public static double closeBeforeStation(
      RoadGeometry.Mesh host, int slot, double end, RoadGeometry.Mesh ramp, List<RoadStructures.Part> parts, double transition, boolean rectangular
   ) {
      RoadGeometry.Mesh raw = LaneSections.reference(host);
      int sign = LanePoints.lane(raw, end, slot).sign();
      RoadGeometry.Mesh sweep = laneSweep(raw, slot);
      RoadClearance.Prepared prepared = RoadClearance.prepare(sweep);
      double first = (double)sign * end;

      for (RoadClearance.Contact contact : RoadClearance.contacts(prepared, ramp)) {
         if (contact.blocked()) {
            double lo = sign > 0 ? contact.from() : -contact.to();
            if (lo <= (double)sign * end + 0.01) {
               first = Math.min(first, lo);
            }

            double hi = sign > 0 ? contact.to() : -contact.from();
            if (hi > (double)sign * end + 0.1 && !terminalSeam(raw, slot, end, ramp, contact)) {
               throw new IllegalArgumentException("汇入点之后仍有低净空横穿，不能开放目标车道（同高同向的正常接缝允许重合）");
            }
         }
      }

      RoadGeometry.Mesh downstream = downstreamLane(raw, slot, end, sign);

      for (RoadStructures.Part part : parts) {
         for (RoadClearance.Contact contactx : structureContacts(prepared, sweep, part, ramp.settings())) {
            if (contactx.blocked()) {
               double lox = sign > 0 ? contactx.from() : -contactx.to();
               if (lox <= (double)sign * end + 0.01) {
                  first = Math.min(first, lox);
               }
            }
         }

         if (downstream != null && RoadClearance.structureInvades(part, downstream, 4.0)) {
            throw new IllegalArgumentException("汇入点之后的目标车道被实际结构构件挡住（不是正常路面接缝）");
         }
      }

      double begin = (
            rectangular ? Math.min((double)sign * end - 0.25, first - 1.0) : Math.min((double)sign * end - 2.0 * transition, first - transition - 1.0)
         )
         * (double)sign;
      begin = Math.max(0.0, Math.min(raw.length(), begin));
      double available = (double)sign * (end - begin);
      double span = Math.min(transition, available / 2.0);
      if (!rectangular && available > 0.02 && first - (double)sign * begin < span - 0.01) {
         begin = sign > 0 ? -transition : raw.length() + transition;
      }

      return begin;
   }

   private static boolean terminalSeam(RoadGeometry.Mesh host, int slot, double end, RoadGeometry.Mesh ramp, RoadClearance.Contact contact) {
      LanePoints.Lane lane = LanePoints.lane(host, end, slot);
      RoadQueries.Projection q = RoadQueries.horizontal(ramp, contact.other());
      double beyond = (double)lane.sign() * ((lane.sign() > 0 ? contact.to() : contact.from()) - end);
      return beyond <= 0.5
         && q.sample().distance() >= ramp.length() - 1.0
         && Math.abs(contact.ours().y() - contact.other().y()) < 0.035
         && q.sample().left().left().mul(-1.0).dot(lane.direction()) > 0.995;
   }

   private static RoadGeometry.Mesh downstreamLane(RoadGeometry.Mesh raw, int slot, double end, int sign) {
      double lo = sign > 0 ? end + 0.12 : raw.first().distance();
      double hi = sign > 0 ? raw.length() : end - 0.12;
      if (hi - lo < 1.0E-5) {
         return null;
      } else {
         TreeSet<Double> stations = new TreeSet<>();
         stations.add(lo);
         stations.add(hi);

         for (RoadGeometry.Sample s : raw.samples()) {
            if (s.distance() > lo && s.distance() < hi) {
               stations.add(s.distance());
            }
         }

         ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

         for (double d : stations) {
            LanePoints.Lane lane = LanePoints.lane(raw, d, slot);
            RoadGeometry.Sample sx = RoadStructures.sample(raw, d);
            samples.add(new RoadGeometry.Sample(lane.position(), sx.left(), d, Math.max(0.05, lane.width() / 2.0 - 0.22)));
         }

         RoadGeometry.Settings settings = raw.settings().options(raw.settings().options().lanePoints(LanePoints.Data.EMPTY));
         RoadGeometry.Mesh m = RoadRibbon.mesh(samples, settings);
         return new RoadGeometry.Mesh(samples, settings, m.min(), m.max(), raw.length(), false, null);
      }
   }

   private static double lastBlocked(RoadGeometry.Mesh sweep, RoadGeometry.Mesh obstacle, double begin, int sign, double last) {
      return lastBlocked(RoadClearance.prepare(sweep), obstacle, begin, sign, last);
   }

   private static double lastBlocked(RoadClearance.Prepared sweep, RoadGeometry.Mesh obstacle, double begin, int sign, double last) {
      for (RoadClearance.Contact c : RoadClearance.contacts(sweep, obstacle)) {
         if (c.blocked()) {
            double d = sign > 0 ? c.to() : -c.from();
            if (d >= (double)sign * begin - 0.01) {
               last = Math.max(last, d);
            }
         }
      }

      return last;
   }

   public static RoadGeometry.Mesh laneSweep(RoadGeometry.Mesh host, int slot) {
      RoadGeometry.Mesh raw = LaneSections.reference(host);
      return slot >= 0 && slot <= 31
         ? SWEEPS.computeIfAbsent(slot, k -> new WeakIdentityCache<>(8, 8000L, v -> v.samples().size())).get(raw, k -> makeLaneSweep(k, slot))
         : makeLaneSweep(raw, slot);
   }

   private static RoadGeometry.Mesh makeLaneSweep(RoadGeometry.Mesh raw, int slot) {
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
      int count = RoadProfile.catalog(raw.settings()).lanes();

      for (RoadGeometry.Sample s : raw.samples()) {
         LanePoints.Lane lane = LanePoints.lane(raw, s.distance(), slot);
         samples.add(new RoadGeometry.Sample(lane.position(), s.left(), s.distance(), lane.width() / 2.0 + 0.3));
      }

      RoadGeometry.Settings clean = raw.settings().options(raw.settings().options().lanePoints(LanePoints.Data.EMPTY));
      RoadGeometry.Mesh bounds = RoadRibbon.mesh(samples, clean);
      return new RoadGeometry.Mesh(List.copyOf(samples), clean, bounds.min(), bounds.max(), raw.length(), raw.closed(), null);
   }

   public static List<RoadClearance.Contact> structureContacts(
      RoadClearance.Prepared prepared, RoadGeometry.Mesh sweep, RoadStructures.Part part, RoadGeometry.Settings settings
   ) {
      return part.heights() == null ? RoadClearance.contacts(prepared, envelope(part, settings)) : RoadClearance.structureContacts(part, sweep, 4.0);
   }

   public static RoadGeometry.Mesh envelope(RoadStructures.Part part, RoadGeometry.Settings settings) {
      List<RoadGeometry.V> base = part.base();
      RoadGeometry.V first = base.get(0).add(base.get(1)).mul(0.5);
      RoadGeometry.V last = base.get(2).add(base.get(3)).mul(0.5);
      RoadGeometry.V fa = base.get(0).sub(base.get(1)).mul(0.5);
      RoadGeometry.V fb = base.get(3).sub(base.get(2)).mul(0.5);
      double wa = Math.sqrt(fa.dot(fa));
      double wb = Math.sqrt(fb.dot(fb));
      double length = first.sub(last).horizontalLength();
      RoadGeometry.V up = new RoadGeometry.V(0.0, part.height(), 0.0);
      List<RoadGeometry.Sample> points = List.of(
         new RoadGeometry.Sample(first.add(up), wa > 1.0E-12 ? fa.mul(1.0 / wa) : new RoadGeometry.V(1.0, 0.0, 0.0), 0.0, wa),
         new RoadGeometry.Sample(last.add(up), wb > 1.0E-12 ? fb.mul(1.0 / wb) : new RoadGeometry.V(1.0, 0.0, 0.0), Math.max(1.0E-8, length), wb)
      );
      RoadGeometry.Settings volume = new RoadGeometry.Settings(
         settings.mode(), settings.style(), settings.width(), part.height(), settings.tension(), settings.arcDegrees()
      );
      double x0 = base.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow();
      double x1 = base.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow();
      double z0 = base.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow();
      double z1 = base.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow();
      double y0 = base.stream().mapToDouble(RoadGeometry.V::y).min().orElseThrow();
      double y1 = base.stream().mapToDouble(RoadGeometry.V::y).max().orElseThrow() + part.height();
      return new RoadGeometry.Mesh(points, volume, new RoadGeometry.V(x0, y0, z0), new RoadGeometry.V(x1, y1, z1), Math.max(1.0E-8, length), false, null);
   }

   public static void validateRestored(RoadGeometry.Mesh host, int slot, RoadGeometry.Mesh ramp, UUID connection) {
      Optional<LaneSections.Cut> cut = host.settings()
         .options()
         .lanePoints()
         .cuts()
         .stream()
         .filter(cx -> cx.connection().equals(connection) && cx.lane() == slot)
         .findFirst();
      if (!cut.isEmpty()) {
         LaneSections.Cut c = cut.get();
         if ((c.sign() <= 0 || !(c.end() >= host.length() - 1.0E-7)) && (c.sign() >= 0 || !(c.end() <= 1.0E-7))) {
            double reopen = (double)c.sign() * c.end() - (c.rectangular() ? 0.0 : c.transition());
            if (lastBlocked(laneSweep(host, slot), ramp, c.begin(), c.sign(), (double)c.sign() * c.begin()) >= reopen - 0.01) {
               throw new IllegalArgumentException("车道恢复范围仍有匝道净空冲突，已取消建造");
            }
         }
      }
   }

   private LaneReopening() {
   }
}
