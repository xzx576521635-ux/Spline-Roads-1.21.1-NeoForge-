package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ElevatedAccess {
   public static ElevatedAccess.Reservation shifted(ElevatedAccess.Reservation r, double offset) {
      List<LaneSections.Cut> cuts = r.cuts()
         .stream()
         .map(
            c -> new LaneSections.Cut(
                  c.connection(),
                  c.lane(),
                  c.sign(),
                  c.begin() + offset,
                  c.end() + offset,
                  c.transition(),
                  c.replacement(),
                  c.temporary(),
                  c.arrival(),
                  c.rectangular(),
                  c.underpass()
               )
         )
         .toList();
      List<LaneAdditions.Addition> adds = r.additions()
         .stream()
         .map(a -> new LaneAdditions.Addition(a.connection(), a.slot(), a.sign(), a.station() + offset, a.transition(), a.end() + offset))
         .toList();
      List<ElevatedAccess.Gap> gaps = r.gaps().stream().map(g -> new ElevatedAccess.Gap(g.from() + offset, g.to() + offset, g.low(), g.high())).toList();
      return new ElevatedAccess.Reservation(r.connection(), r.point(), cuts, adds, gaps);
   }

   public static List<ElevatedAccess.Direction> directions(RoadGeometry.Mesh host, double station, int forward, int reverse) {
      if (forward >= 0 && reverse >= 0 && forward + reverse != 0) {
         RoadProfile.Layout layout = RoadProfile.layout(host, RoadStructures.sample(host, station));
         ArrayList<ElevatedAccess.Direction> out = new ArrayList<>();

         for (int sign : new int[]{1, -1}) {
            int count = sign > 0 ? forward : reverse;
            ArrayList<Integer> slots = new ArrayList<>();

            for (int slot = 0; slot < layout.catalog().lanes(); slot++) {
               if (LanePoints.lane(host, station, slot).sign() == sign) {
                  slots.add(slot);
               }
            }

            if (!layout.catalog().twoWay() && layout.outside() < 0) {
               Collections.reverse(slots);
            }

            if (count > slots.size()) {
               throw new IllegalArgumentException("高架该方向车道数超过下方道路，不能生成");
            }

            if (count > 0) {
               out.add(new ElevatedAccess.Direction(sign, slots.subList(0, count), count == slots.size()));
            }
         }

         return List.copyOf(out);
      } else {
         throw new IllegalArgumentException("高架至少需要一个行驶方向");
      }
   }

   public static ElevatedAccess.Plan plan(
      UUID id, UUID point, RoadGeometry.Mesh host, double station, RoadGeometry.Node endpoint, RoadGeometry.Settings requested, boolean pointFirst
   ) {
      RoadGeometry.Sample at = RoadStructures.sample(host, station);
      RoadProfile.Layout hostLayout = RoadProfile.layout(host, at);
      if (endpoint.position().y() - at.center().y() < 4.0 + requested.thickness() - 0.001) {
         throw new IllegalArgumentException("高架端点高度不足以满足下方道路净空");
      } else {
         RoadGeometry.V towards = endpoint.position().sub(at.center()).horizontalUnit();
         RoadGeometry.V tangent = at.left().left().mul(-1.0);
         int axis = !hostLayout.catalog().twoWay() ? (pointFirst ? 1 : -1) : (towards.dot(tangent) < 0.0 ? -1 : 1);
         RoadLanes.Counts counts = RoadLanes.counts(requested);
         int flowAxis = pointFirst ? axis : -axis;
         int forward = flowAxis > 0 ? counts.forward() : counts.reverse();
         int reverse = flowAxis > 0 ? counts.reverse() : counts.forward();
         List<ElevatedAccess.Direction> selected = directions(host, station, forward, reverse);
         double low = Double.POSITIVE_INFINITY;
         double high = Double.NEGATIVE_INFINITY;

         for (ElevatedAccess.Direction direction : selected) {
            for (int slot : direction.slots()) {
               LanePoints.Lane lane = LanePoints.lane(host, station, slot);
               double offset = lane.position().sub(at.center()).dot(at.left());
               low = Math.min(low, offset - lane.width() / 2.0);
               high = Math.max(high, offset + lane.width() / 2.0);
            }
         }

         double center = (low + high) / 2.0;
         double drive = high - low;
         RoadGeometry.V start = at.at(center, 0.0);
         RoadGeometry.V initial = tangent.mul((double)axis);
         RoadGeometry.V finalDirection = endpoint.direction().horizontalUnit();
         if (finalDirection.dot(towards) < 0.0) {
            finalDirection = finalDirection.mul(-1.0);
         }

         RoadProfile.Options options = requested.options()
            .attachments(RoadAttachments.Data.EMPTY)
            .lanePoints(LanePoints.Data.EMPTY)
            .lanes(counts)
            .ends(RoadTransitions.Ends.NONE)
            .extras(false, false, false)
            .sidewalk(RoadSidewalks.Config.DEFAULT.enabled(false))
            .streetscape(requested.options().streetscape().raisedSpans(List.of()).tunnels(List.of()).crossings(List.of()));
         RoadGeometry.Settings base = new RoadGeometry.Settings(
               RoadGeometry.Mode.CURVE, requested.style(), requested.width(), requested.thickness(), 0.35, 90.0
            )
            .structure(RoadGeometry.Structure.AUTO)
            .options(options);
         double median = selected.size() > 1 ? hostLayout.median() : 0.0;
         double localMedian = (double)axis * (hostLayout.medianCenter() - center);
         ArrayList<Double> dividers = new ArrayList<>();

         for (ElevatedAccess.Direction direction : selected) {
            for (int i = 1; i < direction.slots().size(); i++) {
               LanePoints.Lane lane = LanePoints.lane(host, station, direction.slots().get(i));
               double local = (double)axis * (lane.position().sub(at.center()).dot(at.left()) - center);
               int side = local < localMedian ? -1 : 1;
               dividers.add(local - (double)side * lane.width() / 2.0);
            }
         }

         Collections.sort(dividers);
         RoadGeometry.Style mouthStyle = RoadLanes.carrier(hostLayout.catalog().type(), pointFirst ? counts : counts.mirrored(), hostLayout.catalog().median());
         RoadTransitions.Section section = new RoadTransitions.Section(mouthStyle, drive, false, false, false, RoadProfile.OuterRail.ON)
            .port(new RoadTransitions.Port(-drive / 2.0, drive / 2.0, median, dividers, 0.0, 0.0, selected.size() > 1 ? localMedian : 0.0));
         section = new RoadTransitions.Section(
            section.style(),
            section.width(),
            false,
            false,
            false,
            section.outerRail(),
            section.sidewalk(),
            false,
            section.port(),
            options.streetscape(),
            pointFirst ? counts : counts.mirrored()
         );
         RoadGeometry.Settings pathSettings = pointFirst ? base : base.options(base.options().lanes(counts.mirrored()));
         pathSettings = RoadTransitions.ends(pathSettings, section, RoadTransitions.Section.of(RoadEndpointSections.orient(requested, !pointFirst)));
         double step = Math.min(0.5, host.length() / 20.0);
         RoadGeometry.Sample before = RoadStructures.sample(host, Math.max(0.0, station - step));
         RoadGeometry.Sample after = RoadStructures.sample(host, Math.min(host.length(), station + step));
         double grade = (after.center().y() - before.center().y()) / Math.max(0.001, after.center().sub(before.center()).horizontalLength()) * (double)axis;
         LaneRampPaths.Port a = new LaneRampPaths.Port(start, initial, initial.left(), drive, grade);
         LaneRampPaths.Port b = new LaneRampPaths.Port(
            endpoint.position(),
            finalDirection,
            finalDirection.left(),
            base.width(),
            endpoint.grade() * (double)(endpoint.direction().dot(finalDirection) < 0.0 ? -1 : 1)
         );
         LanePoints.Options routeOptions = new LanePoints.Options(
            LanePoints.Path.AUTO, LanePoints.Departure.TEMPORARY, LanePoints.Arrival.MERGE, 12.0, 24.0, LanePoints.Elevation.OVER, LanePoints.Landing.EXACT
         );
         List<LaneRampPaths.Candidate> candidates = LaneRampPaths.smoothTurns(a, b, pathSettings, routeOptions, 0.2, false);
         List<LaneRampPaths.Candidate> var93 = new ArrayList<>(candidates);
         var93.sort(Comparator.comparingDouble(c -> c.mesh().length()));
         String error = "没有满足坡度、转弯半径和下方通行净空的高架连接";

         for (LaneRampPaths.Candidate candidate : var93) {
            try {
               RoadGeometry.Mesh path = holdMouth(candidate.mesh(), host, center, axis);
               ArrayList<LaneSections.Cut> cuts = new ArrayList<>();
               ArrayList<LaneAdditions.Addition> additions = new ArrayList<>();
               ArrayList<ElevatedAccess.Gap> gaps = new ArrayList<>();
               int slot = 16;
               HashSet<Integer> used = new HashSet<>();

               for (LaneAdditions.Addition addition : host.settings().options().lanePoints().additions()) {
                  used.add(addition.slot());
               }

               double medianFrom = station;
               double medianTo = station;

               for (ElevatedAccess.Direction direction : selected) {
                  double begin = station;
                  double end = station;
                  boolean departure = direction.sign() == axis;

                  for (int lane : direction.slots()) {
                     double other = departure
                        ? LaneReopening.restoreStation(host, lane, station, path, List.of(), 24.0, true)
                        : LaneReopening.closeBeforeStation(
                           host, lane, station, reverse(path, RoadEndpointSections.orient(path.settings(), true)), List.of(), 24.0, true
                        );
                     if (departure) {
                        end = direction.sign() > 0 ? Math.max(end, other) : Math.min(end, other);
                     } else {
                        begin = direction.sign() > 0 ? Math.min(begin, other) : Math.max(begin, other);
                     }
                  }

                  double lo = Math.min(begin, end);
                  double hi = Math.max(begin, end);
                  medianFrom = Math.min(medianFrom, lo);
                  medianTo = Math.max(medianTo, hi);

                  for (int lanex : direction.slots()) {
                     cuts.add(new LaneSections.Cut(id, lanex, direction.sign(), begin, end, 24.0, null, true, !departure, true));
                  }

                  if (direction.auxiliary()) {
                     while (used.contains(slot)) {
                        slot++;
                     }

                     used.add(slot);
                     double passing = 36.0;
                     double taper = Math.max(32.0, 8.0 * hostLayout.laneWidth());
                     double from = lo - passing;
                     double to = hi + passing;
                     if (from < taper || to + taper > host.length()) {
                        throw new IllegalArgumentException("主路长度不足以容纳高架辅路的渐变和换道区");
                     }

                     additions.add(
                        new LaneAdditions.Addition(id, slot++, direction.sign(), direction.sign() > 0 ? from : to, taper, direction.sign() > 0 ? to : from)
                     );
                  }
               }

               if (selected.size() > 1 && median > 0.0 && medianTo > medianFrom) {
                  gaps.add(new ElevatedAccess.Gap(medianFrom, medianTo, hostLayout.medianCenter() - median / 2.0, hostLayout.medianCenter() + median / 2.0));
               }

               ElevatedAccess.Reservation reservation = new ElevatedAccess.Reservation(id, point, cuts, additions, gaps);
               LanePoints.Data md = host.settings().options().lanePoints();
               ArrayList<LaneSections.Cut> allCuts = new ArrayList<>(md.cuts());
               allCuts.addAll(cuts);
               ArrayList<LaneAdditions.Addition> allAdditions = new ArrayList<>(md.additions());
               allAdditions.addAll(additions);
               ArrayList<ElevatedAccess.Reservation> allAccess = new ArrayList<>(md.access());
               allAccess.add(reservation);
               RoadGeometry.Mesh effective = LaneSections.apply(
                  RoadRibbon.mesh(
                     host.samples(), host.settings().options(host.settings().options().lanePoints(md.access(allAccess).cuts(allCuts).additions(allAdditions)))
                  )
               );
               RoadClearance.check(path, effective, null);
               return new ElevatedAccess.Plan(pointFirst ? path : reverse(path, base), reservation, selected);
            } catch (IllegalArgumentException var81) {
               error = var81.getMessage();
            }
         }

         throw new IllegalArgumentException(error);
      }
   }

   private static RoadGeometry.Mesh holdMouth(RoadGeometry.Mesh path, RoadGeometry.Mesh host, double offset, int axis) {
      double hold = 0.0;

      for (RoadGeometry.Sample sample : path.samples()) {
         RoadGeometry.Sample lower = RoadQueries.horizontal(host, sample.center()).sample();
         if (sample.center().y() - path.settings().thickness() - lower.center().y() < 4.25) {
            hold = sample.distance();
         }
      }

      if (hold >= path.length() - 0.5) {
         throw new IllegalArgumentException("高架端点净空不足，无法完成道路断面过渡");
      } else {
         double blend = Math.min(48.0, (path.length() - hold) * 0.75);
         ArrayList<RoadGeometry.V> centers = new ArrayList<>();
         int held = 0;

         for (int i = 0; i < path.samples().size(); i++) {
            RoadGeometry.Sample samplex = path.samples().get(i);
            RoadGeometry.Sample q = RoadQueries.horizontal(host, samplex.center()).sample();
            RoadGeometry.V aligned = q.at(offset, 0.0);
            double t = RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, (samplex.distance() - hold) / blend)));
            RoadGeometry.V p = aligned.mul(1.0 - t).add(samplex.center().mul(t));
            centers.add(new RoadGeometry.V(p.x(), samplex.center().y(), p.z()));
            if (samplex.distance() <= hold) {
               held = i;
            }
         }

         ArrayList<RoadGeometry.Sample> points = new ArrayList<>();

         for (int ix = 0; ix < centers.size(); ix++) {
            RoadGeometry.V left = ix == 0
               ? RoadQueries.horizontal(host, centers.get(ix)).sample().left().mul((double)axis)
               : (ix == centers.size() - 1 ? path.last().left() : centers.get(ix + 1).sub(centers.get(ix - 1)).horizontalUnit().left());
            points.add(new RoadGeometry.Sample(centers.get(ix), left, 0.0, path.samples().get(ix).halfWidth()));
         }

         RoadGeometry.Mesh aligned = RoadRibbon.mesh(points, path.settings());
         hold = aligned.samples().get(held).distance();
         RoadGeometry.Settings settings = path.settings();
         settings = settings.options(settings.options().ends(settings.options().ends().hold(hold, 0.0)));
         points.clear();

         for (RoadGeometry.Sample samplex : aligned.samples()) {
            points.add(
               new RoadGeometry.Sample(
                  samplex.center(), samplex.left(), samplex.distance(), RoadTransitions.width(settings, samplex.distance(), aligned.length()) / 2.0
               )
            );
         }

         RoadGeometry.Mesh result = RoadRibbon.mesh(points, settings);
         LaneRampGrade.validate(result, 0.2);
         RoadRibbon.checkSelfIntersections(result, 4.0);
         return result;
      }
   }

   private static RoadGeometry.Mesh reverse(RoadGeometry.Mesh path, RoadGeometry.Settings base) {
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

      for (int i = path.samples().size() - 1; i >= 0; i--) {
         RoadGeometry.Sample s = path.samples().get(i);
         samples.add(new RoadGeometry.Sample(s.center(), s.left().mul(-1.0), 0.0, s.halfWidth()));
      }

      RoadTransitions.Ends e = path.settings().options().ends();
      RoadTransitions.Section start = reflect(e.end(), base.options().leftTraffic());
      RoadTransitions.Section end = reflect(e.start(), base.options().leftTraffic());
      RoadGeometry.Settings settings = RoadTransitions.ends(base, start, end);
      settings = settings.options(settings.options().ends(settings.options().ends().hold(e.endHold(), e.startHold())));
      return RoadRibbon.mesh(samples, settings);
   }

   private static RoadTransitions.Section reflect(RoadTransitions.Section section, boolean left) {
      return section == null ? null : RoadTransitions.Section.of(RoadEndpointSections.orient(section.settings(left), true));
   }

   private ElevatedAccess() {
   }

   public static record Direction(int sign, List<Integer> slots, boolean auxiliary) {
      public Direction(int sign, List<Integer> slots, boolean auxiliary) {
         slots = List.copyOf(slots);
         this.sign = sign;
         this.slots = slots;
         this.auxiliary = auxiliary;
      }
   }

   public static record Gap(double from, double to, double low, double high) {
      public Gap(double from, double to, double low, double high) {
         if (RoadGeometry.finite(from, to, low, high) && !(from >= to) && !(low >= high)) {
            this.from = from;
            this.to = to;
            this.low = low;
            this.high = high;
         } else {
            throw new IllegalArgumentException("高架出入口铺面开口无效");
         }
      }

      public boolean at(double station) {
         return station > this.from + 1.0E-8 && station < this.to - 1.0E-8;
      }
   }

   public static record Plan(RoadGeometry.Mesh road, ElevatedAccess.Reservation reservation, List<ElevatedAccess.Direction> directions) {
      public Plan(RoadGeometry.Mesh road, ElevatedAccess.Reservation reservation, List<ElevatedAccess.Direction> directions) {
         directions = List.copyOf(directions);
         this.road = road;
         this.reservation = reservation;
         this.directions = directions;
      }
   }

   public static record Reservation(
      UUID connection, UUID point, List<LaneSections.Cut> cuts, List<LaneAdditions.Addition> additions, List<ElevatedAccess.Gap> gaps
   ) {
      public Reservation(UUID connection, UUID point, List<LaneSections.Cut> cuts, List<LaneAdditions.Addition> additions, List<ElevatedAccess.Gap> gaps) {
         Objects.requireNonNull(connection);
         Objects.requireNonNull(point);
         cuts = List.copyOf(cuts);
         additions = List.copyOf(additions);
         gaps = List.copyOf(gaps);
         if (cuts.size() <= 14
            && additions.size() <= 2
            && gaps.size() <= 3
            && !cuts.stream().anyMatch(c -> !c.connection().equals(connection))
            && !additions.stream().anyMatch(a -> !a.connection().equals(connection))) {
            this.connection = connection;
            this.point = point;
            this.cuts = cuts;
            this.additions = additions;
            this.gaps = gaps;
         } else {
            throw new IllegalArgumentException("高架出入口车道预留无效");
         }
      }
   }
}
