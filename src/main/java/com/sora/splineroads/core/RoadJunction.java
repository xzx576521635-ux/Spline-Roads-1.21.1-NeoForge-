package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

public final class RoadJunction {
   public static boolean sharedRampFork(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      if (a != null
         && b != null
         && a.settings().style().ramp()
         && b.settings().style().ramp()
         && !a.settings().style().connectorRamp()
         && !b.settings().style().connectorRamp()) {
         for (boolean first : new boolean[]{true, false}) {
            if (join(a, b, first) != null || join(b, a, first) != null) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private static RoadJunction.Join join(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, boolean first) {
      return !ramp.settings().style().connectorRamp() && !host.settings().style().connectorRamp() ? measureJoin(ramp, host, first) : null;
   }

   private static RoadJunction.Join measureJoin(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, boolean first) {
      RoadGeometry.Sample endpoint = first ? ramp.first() : ramp.last();
      RoadQueries.Projection projection = RoadQueries.project(host, endpoint.center());
      if (!(projection.horizontalDistance() > projection.sample().halfWidth() + endpoint.halfWidth() - 0.1)
         && !(Math.abs(projection.sample().center().y() - endpoint.center().y()) > 0.12)) {
         RoadGeometry.V direction = endpoint.left().left().mul(first ? -1.0 : 1.0);
         double dot = direction.dot(projection.tangent().horizontalUnit());
         if (Math.abs(dot) < 0.94) {
            return null;
         } else {
            RoadJunction.Route a = new RoadJunction.Route(ramp, first ? 0.0 : ramp.length(), first ? 1 : -1);
            RoadJunction.Route b = new RoadJunction.Route(host, projection.sample().distance(), dot > 0.0 ? 1 : -1);
            double available = Math.min(256.0, Math.min(a.available(), b.available()));
            if (available < 8.0) {
               return null;
            } else {
               double separation = highwayExtra(ramp, host, first) ? 0.05 : -0.1;

               for (double d = 1.0; d <= available; d += 0.5) {
                  RoadGeometry.Sample aa = a.at(d);
                  RoadGeometry.Sample bb = paired(a, b, d);
                  if (aa.center().sub(bb.center()).horizontalLength() >= aa.halfWidth() + bb.halfWidth() + separation
                     || Math.abs(aa.center().y() - bb.center().y()) > 0.12) {
                     return new RoadJunction.Join(a, b, d, available, false);
                  }
               }

               return !host.settings().style().ramp()
                     && endpoint.halfWidth() * 2.0 < ramp.settings().width() * 0.94
                     && a.at(available).halfWidth() * 2.0 >= ramp.settings().width() * 0.94
                  ? new RoadJunction.Join(a, b, available, available, true)
                  : null;
            }
         }
      } else {
         return null;
      }
   }

   private static boolean adjacent(RoadJunction.Join pair, RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, List<RoadGeometry.Mesh> neighbors, boolean first) {
      double d = Math.min(pair.nose, 32.0);
      RoadGeometry.V a = pair.a.at(d).center();
      RoadGeometry.V b = paired(pair.a, pair.b, d).center();
      RoadGeometry.V axis = b.sub(a);
      double length2 = axis.dot(axis);
      if (length2 < 0.01) {
         return true;
      } else {
         for (RoadGeometry.Mesh other : neighbors) {
            if (other != host && other != ramp && other.settings().style().ramp()) {
               RoadJunction.Join candidate = join(ramp, other, first);
               if (candidate != null) {
                  RoadGeometry.V q = paired(candidate.a, candidate.b, d).center().sub(a);
                  double t = q.dot(axis) / length2;
                  if (t > 0.05 && t < 0.95 && q.sub(axis.mul(t)).horizontalLength() < 2.0) {
                     return false;
                  }
               }
            }
         }

         return true;
      }
   }

   public static List<RoadJunction.Zone> dividerZones(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> neighbors) {
      if (MultiFanPaint.applies(ramp)) {
         return MultiFanPaint.zones(ramp);
      } else if (!ramp.settings().style().ramp()) {
         return List.of();
      } else {
         List<RoadJunction.Zone> zones = new ArrayList<>(closureZones(ramp, neighbors));

         for (RoadGeometry.Mesh other : neighbors) {
            if (twoLaneRamps(ramp, other)) {
               for (RoadJunction.Join pair : twoLaneForks(ramp, other)) {
                  RoadJunction.Route route = pair.a.mesh == ramp ? pair.a : pair.b;
                  double last = pair.a.mesh == ramp ? pair.a.at(forkLimit(pair)).distance() : paired(pair.a, pair.b, forkLimit(pair)).distance();
                  double begin = route.at(0.0).distance();
                  zones.add(new RoadJunction.Zone(Math.min(begin, last), Math.max(begin, last)));
               }
            }
         }

         for (boolean first : new boolean[]{true, false}) {
            for (RoadGeometry.Mesh otherx : neighbors) {
               if (otherx.settings().style().ramp() && !twoLaneRamps(ramp, otherx)) {
                  RoadJunction.Join j = join(ramp, otherx, first);
                  if (j != null) {
                     double end = forkLimit(j);
                     zones.add(first ? new RoadJunction.Zone(0.0, end) : new RoadJunction.Zone(ramp.length() - end, ramp.length()));
                  }
               }
            }
         }

         return zones;
      }
   }

   private static boolean highwayExtra(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, boolean first) {
      LanePoints.Link link = ramp.settings().options().lanePoints().link();
      if (link != null && RoadProfile.highway(host.settings().style()) && (first ? link.options().sourceExtra() : link.options().targetExtra())) {
         LanePoints.Ref ref = first ? link.from() : link.to();
         return host.settings().options().lanePoints().points().stream().anyMatch(p -> p.id().equals(ref.point()));
      } else {
         return false;
      }
   }

   private static double closureLength(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, boolean first) {
      if (highwayExtra(ramp, host, first)) {
         return Math.min(ramp.length() / 2.0, LaneRampApproach.taper(host, ramp.settings().options().lanePoints().link().options().transition()));
      } else {
         double reach = Math.min(Math.min(48.0, ramp.length() * 0.6), Math.max(24.0, host.settings().width() * 1.5));
         if (ramp.settings().separatedPort(first)) {
            reach = Math.min(reach, 6.0 + 0.35 * ((first ? ramp.settings().startLead() : ramp.settings().endLead()) - 6.0));
         } else {
            double limit = Math.min(96.0, ramp.length() * 0.6);

            for (double d = 0.0; d <= limit; d += 0.5) {
               RoadGeometry.Sample at = RoadStructures.sample(ramp, first ? d : ramp.length() - d);
               if (at.halfWidth() * 2.0 >= ramp.settings().width() * 0.94) {
                  RoadQueries.Projection q = RoadQueries.horizontal(host, at.center());
                  boolean addedLane = (first ? ramp.first() : ramp.last()).halfWidth() < 0.5
                     && Math.abs(at.center().y() - q.sample().center().y()) < 0.12
                     && q.horizontalDistance() < q.sample().halfWidth() + at.halfWidth() - 0.5;
                  reach = addedLane ? d : Math.max(reach, d);
                  break;
               }
            }
         }

         return Math.max(8.0, reach);
      }
   }

   private static boolean taperClosure(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, boolean first) {
      if (highwayExtra(ramp, host, first)) {
         return ForkSignPlacement.onPath(host, first ? ramp.first().center() : ramp.last().center(), 0.35);
      } else if (!ramp.settings().style().connectorRamp()
         && ramp.settings().style().ramp()
         && !host.settings().style().ramp()
         && host.settings().style() != RoadGeometry.Style.UNMARKED) {
         RoadGeometry.Sample end = first ? ramp.first() : ramp.last();
         if (end.halfWidth() * 2.0 >= ramp.settings().width() * 0.7) {
            return false;
         } else {
            RoadQueries.Projection projection = RoadQueries.horizontal(host, end.center());
            return Math.abs(end.center().y() - projection.sample().center().y()) <= 0.1
               && projection.horizontalDistance() <= end.halfWidth() + projection.sample().halfWidth()
               && Math.abs(end.left().dot(projection.sample().left())) >= 0.94;
         }
      } else {
         return false;
      }
   }

   private static List<RoadJunction.Zone> closureZones(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> neighbors) {
      ArrayList<RoadJunction.Zone> out = new ArrayList<>();

      for (RoadGeometry.Mesh host : neighbors) {
         for (boolean first : new boolean[]{true, false}) {
            if (taperClosure(ramp, host, first)) {
               double reach = closureLength(ramp, host, first);
               out.add(first ? new RoadJunction.Zone(0.0, reach) : new RoadJunction.Zone(ramp.length() - reach, ramp.length()));
            }
         }
      }

      return out;
   }

   public static List<RoadJunction.EdgeZone> edgeZones(RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> neighbors) {
      if (MultiFanPaint.applies(mesh)) {
         return List.of();
      } else {
         List<RoadJunction.EdgeZone> zones = new ArrayList<>();

         for (RoadGeometry.Mesh other : neighbors) {
            for (boolean first : new boolean[]{true, false}) {
               if (mesh.settings().style().ramp()) {
                  RoadJunction.Join j = highwayExtra(mesh, other, first) ? measureJoin(mesh, other, first) : join(mesh, other, first);
                  if (j != null && !j.parallel && goreRequired(j) && adjacent(j, mesh, other, neighbors, first)) {
                     addEdge(zones, j, true);
                  }
               }

               if (other.settings().style().ramp()) {
                  RoadJunction.Join j = highwayExtra(other, mesh, first) ? measureJoin(other, mesh, first) : join(other, mesh, first);
                  if (j != null && !j.parallel && goreRequired(j)) {
                     addEdge(zones, j, false);
                  }
               }
            }
         }

         return zones;
      }
   }

   private static boolean parallelFeeder(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host) {
      if (!ramp.settings().style().connectorRamp()
         && ramp.settings().style().ramp()
         && !host.settings().style().ramp()
         && host.settings().style() != RoadGeometry.Style.UNMARKED) {
         for (double d = 0.0; d <= ramp.length(); d += Math.max(0.5, ramp.length() / 24.0)) {
            RoadGeometry.Sample a = RoadStructures.sample(ramp, d);
            RoadQueries.Projection b = RoadQueries.horizontal(host, a.center());
            if (Math.abs(a.center().y() - b.sample().center().y()) > 0.12
               || Math.abs(a.left().dot(b.sample().left())) < 0.9
               || b.horizontalDistance() > b.sample().halfWidth() + a.halfWidth() + 0.25) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private static void addEdge(List<RoadJunction.EdgeZone> out, RoadJunction.Join pair, boolean ramp) {
      RoadGeometry.Sample aa = ramp ? pair.a.at(pair.nose) : paired(pair.a, pair.b, pair.nose);
      RoadGeometry.Sample bb = ramp ? paired(pair.a, pair.b, pair.nose) : pair.a.at(pair.nose);
      int side = aa.left().dot(bb.center().sub(aa.center())) > 0.0 ? 1 : -1;
      double from = goreStart(pair);
      double to = Math.min(pair.available, pair.nose + 5.0);
      double begin = (ramp ? pair.a.at(from) : paired(pair.a, pair.b, from)).distance();
      double end = (ramp ? pair.a.at(to) : paired(pair.a, pair.b, to)).distance();
      out.add(new RoadJunction.EdgeZone(Math.min(begin, end), Math.max(begin, end), side));
   }

   public static List<RoadGeometry.Mesh> intersections(RoadGeometry.Mesh road, List<RoadGeometry.Mesh> neighbors) {
      List<RoadGeometry.Mesh> result = new ArrayList<>();

      for (RoadGeometry.Mesh other : neighbors) {
         if (other.settings().style() == RoadGeometry.Style.UNMARKED) {
            result.add(other);
         } else if (other.settings().style().ramp() && !other.settings().style().connectorRamp()) {
            for (int i = 0; i < other.samples().size(); i += 6) {
               RoadGeometry.Sample p = other.samples().get(i);
               if (RoadQueries.contains(road, p.center(), 0.0, 0.05)) {
                  RoadQueries.Projection q = RoadQueries.project(road, p.center());
                  if (q.horizontalDistance() < Math.max(2.0, other.settings().width() / 2.0) && Math.abs(q.sample().left().dot(p.left())) < 0.65) {
                     result.add(other);
                     break;
                  }
               }
            }
         }
      }

      return result;
   }

   public static List<RoadJunction.Paint> arrows(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> neighbors) {
      return arrows(ramp, neighbors, intersections(ramp, neighbors));
   }

   public static List<RoadJunction.Paint> arrows(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> neighbors, List<RoadGeometry.Mesh> crossings) {
      if (ramp.settings().style().connectorRamp()) {
         return neighbors.stream().anyMatch(host -> highwayExtra(ramp, host, false)) ? List.of() : LaneRampPaint.arrows(ramp, neighbors);
      } else if (MultiFanPaint.applies(ramp)) {
         return List.of();
      } else if (RoadProfile.modern(ramp.settings().style())) {
         List<RoadJunction.Paint> out = new ArrayList<>();
         List<RoadJunction.Zone> closures = closureZones(ramp, neighbors);

         for (RoadJunction.Paint p : laneArrows(ramp, neighbors)) {
            if (p.points().stream().noneMatch(v -> closures.stream().anyMatch(z -> z.contains(RoadQueries.project(ramp, v).sample().distance())))
               && crossings.stream().noneMatch(m -> p.points().stream().anyMatch(v -> RoadQueries.contains(m, v, 0.5, 0.1)))) {
               out.add(p);
            }
         }

         for (RoadSignals.Approach entry : RoadSignals.approaches(ramp, neighbors)) {
            stroke(out, entry.inner(), entry.outer(), 0.4);
         }

         return out;
      } else if (ramp.settings().style().ramp() && !ramp.settings().options().hideArrows()) {
         List<RoadJunction.Paint> out = new ArrayList<>();

         for (boolean first : new boolean[]{true, false}) {
            double distance = -1.0;

            for (RoadGeometry.Mesh host : neighbors) {
               RoadJunction.Join j = join(ramp, host, first);
               if (j != null) {
                  distance = Math.max(distance, Math.min(j.a.available() - 2.0, j.nose + 5.0));
               }
            }

            if (!(distance < 0.0)) {
               RoadGeometry.Sample s = RoadStructures.sample(ramp, first ? distance : ramp.length() - distance);
               double lateral = RoadProfile.modern(ramp.settings().style()) ? RoadProfile.layout(ramp.settings(), s.halfWidth() * 2.0).motorCenter() : 0.0;
               arrow(out, s.at(lateral, 0.0), s.left().left().mul(-1.0), s.left());
            }
         }

         return out;
      } else {
         return List.of();
      }
   }

   private static List<RoadJunction.Paint> laneArrows(RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> neighbors) {
      List<RoadJunction.Paint> raw = rawLaneArrows(mesh, neighbors);
      if (mesh.settings().style().ramp() && !raw.isEmpty()) {
         LinkedHashMap<RoadGeometry.Mesh, List<RoadJunction.Paint>> others = new LinkedHashMap<>();

         for (RoadGeometry.Mesh other : neighbors) {
            if (other.settings().style().ramp() && !other.settings().style().connectorRamp()) {
               ArrayList<RoadGeometry.Mesh> context = new ArrayList<>(neighbors);
               context.remove(other);
               context.add(mesh);
               others.put(other, rawLaneArrows(other, context));
            }
         }

         ArrayList<RoadJunction.Paint> out = new ArrayList<>();

         for (int i = 0; i + 2 < raw.size(); i += 3) {
            List<RoadJunction.Paint> group = raw.subList(i, i + 3);
            RoadGeometry.V center = arrowCenter(group);
            RoadGeometry.V direction = arrowDirection(group);
            boolean duplicate = false;

            for (Entry<RoadGeometry.Mesh, List<RoadJunction.Paint>> entry : others.entrySet()) {
               for (int j = 0; j + 2 < entry.getValue().size(); j += 3) {
                  List<RoadJunction.Paint> candidate = entry.getValue().subList(j, j + 3);
                  RoadGeometry.V delta = arrowCenter(candidate).sub(center);
                  double along = delta.dot(direction);
                  if (direction.dot(arrowDirection(candidate)) > 0.97
                     && Math.abs(delta.y()) < 0.12
                     && Math.abs(delta.dot(direction.left())) < 0.8
                     && along >= -1.0E-6
                     && along < 32.0
                     && (along > 1.0E-6 || meshOrder(mesh, entry.getKey()) > 0)) {
                     duplicate = true;
                  }
               }
            }

            if (!duplicate) {
               out.addAll(group);
            }
         }

         return List.copyOf(out);
      } else {
         return raw;
      }
   }

   private static RoadGeometry.V arrowCenter(List<RoadJunction.Paint> group) {
      List<RoadGeometry.V> points = group.stream().flatMap(px -> px.points().stream()).toList();
      RoadGeometry.V sum = new RoadGeometry.V(0.0, 0.0, 0.0);

      for (RoadGeometry.V p : points) {
         sum = sum.add(p);
      }

      return sum.mul(1.0 / (double)points.size());
   }

   private static RoadGeometry.V arrowDirection(List<RoadJunction.Paint> group) {
      List<RoadGeometry.V> p = group.get(0).points();
      return p.get(2).add(p.get(3)).sub(p.get(0)).sub(p.get(1)).horizontalUnit();
   }

   private static List<RoadJunction.Paint> rawLaneArrows(RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> neighbors) {
      List<RoadJunction.Paint> out = new ArrayList<>(LaneMerge.guides(mesh));
      RoadJunction.Join merge = null;
      List<RoadJunction.Zone> closures = closureZones(mesh, neighbors);
      if (mesh.settings().style().ramp()) {
         for (RoadGeometry.Mesh host : neighbors) {
            if (!host.settings().style().ramp()
               && host.settings().style() != RoadGeometry.Style.UNMARKED
               && (!parallelFeeder(mesh, host) || !(mesh.last().halfWidth() * 2.0 >= mesh.settings().width() * 0.94))) {
               RoadJunction.Join candidate = join(mesh, host, false);
               if (candidate != null && (merge == null || candidate.nose > merge.nose)) {
                  merge = candidate;
               }
            }
         }
      }

      for (double d = 12.0; !mesh.settings().style().ramp() && d < mesh.length() - 5.0; d += 48.0) {
         if (!RoadAttachments.paint(mesh, d).hideArrows() && (merge == null || !(d > mesh.length() - merge.nose - 8.0))) {
            RoadGeometry.Sample s = RoadStructures.sample(mesh, d);
            if (!Double.isFinite(RoadTransitions.dropBoundary(mesh, s))
               && (!mesh.settings().style().ramp() || !(s.halfWidth() * 2.0 < mesh.settings().width() * 0.95))) {
               RoadProfile.Layout l = RoadProfile.layout(mesh, s);
               RoadProfile.Catalog c = l.catalog();
               if (mesh.reference() == null && mesh.settings().options().lanePoints().additions().isEmpty()) {
                  for (int i = 0; i < c.lanes(); i++) {
                     double lateral;
                     if (c.twoWay()) {
                        int per = l.lanesOnSide(-1);
                        int side = i < per ? -1 : 1;
                        lateral = l.medianCenter() + (double)side * (l.median() / 2.0 + ((double)(side < 0 ? i : i - per) + 0.5) * l.laneWidth());
                     } else {
                        lateral = l.motorMin() + ((double)i + 0.5) * l.laneWidth();
                     }

                     int sign = c.twoWay() && (lateral < l.medianCenter() ? -1 : 1) != l.outside() ? -1 : 1;
                     arrow(out, s.at(lateral, 0.0), s.left().left().mul((double)(-sign)), s.left().mul((double)sign));
                  }
               } else {
                  RoadGeometry.Mesh raw = LaneSections.reference(mesh);

                  for (int slot : LaneAdditions.slots(raw, d)) {
                     if (LaneSections.active(mesh, d, slot) && !LaneMerge.merging(mesh, d, slot) && !LaneClosureWarnings.covers(mesh, d, slot)) {
                        LanePoints.Lane lane = LanePoints.lane(raw, d, slot);
                        arrow(out, lane.position(), lane.direction(), s.left().mul((double)lane.sign()));
                     }
                  }
               }
            }
         }
      }

      if (merge != null) {
         for (int lane = 0; lane < RoadProfile.catalog(mesh.settings()).lanes(); lane++) {
            for (double back = Math.min(merge.nose - 2.0, Math.max(36.0, merge.nose * 0.6));
               back < Math.max(merge.nose + 40.0, 110.0) && back < mesh.length() - 4.0;
               back++
            ) {
               RoadGeometry.Sample at = RoadStructures.sample(mesh, mesh.length() - back);
               if (!(at.halfWidth() * 2.0 < mesh.settings().width() * 0.94)) {
                  RoadGeometry.Sample hostx = RoadQueries.horizontal(merge.b.mesh, at.center()).sample();
                  double sign = Math.signum(at.left().dot(hostx.center().sub(at.center())));
                  if (sign == 0.0) {
                     break;
                  }

                  double inside = guideEdge(merge.b, hostx, at).sub(at.center()).dot(at.left());
                  double outside = -sign * (at.halfWidth() - 0.3);
                  RoadProfile.Layout layout = RoadProfile.layout(mesh, at);
                  double laneCenter = layout.motorMin() + ((double)lane + 0.5) * layout.laneWidth();
                  double width = layout.laneWidth();
                  if (!(laneCenter < Math.min(inside, outside) + 0.7) && !(laneCenter > Math.max(inside, outside) - 0.7) && !(width < 2.0)) {
                     int firstPaint = out.size();
                     out.addAll(RoadMergeArrow.flat(at.center(), at.left().left().mul(-1.0), at.left(), laneCenter, width, sign));
                     boolean fits = true;

                     for (RoadJunction.Paint paint : out.subList(firstPaint, out.size())) {
                        for (RoadGeometry.V point : paint.points()) {
                           RoadQueries.Projection q = RoadQueries.horizontal(mesh, point);
                           RoadProfile.Layout profile = RoadProfile.layout(mesh, q.sample());
                           double lo = profile.motorMin() + (double)lane * profile.laneWidth() + 0.18;
                           double hi = lo + profile.laneWidth() - 0.36;
                           RoadGeometry.Sample h = RoadQueries.horizontal(merge.b.mesh, point).sample();
                           RoadGeometry.V boundary = guideEdge(merge.b, h, q.sample());
                           double towardHost = Math.signum(q.sample().left().dot(h.center().sub(q.sample().center())));
                           if (q.lateral() < lo
                              || q.lateral() > hi
                              || point.sub(boundary).dot(q.sample().left()) * towardHost > -0.18
                              || closures.stream().anyMatch(z -> z.contains(q.sample().distance()))) {
                              fits = false;
                           }
                        }
                     }

                     if (fits) {
                        break;
                     }

                     out.subList(firstPaint, out.size()).clear();
                  }
               }
            }
         }
      }

      return out.stream().map(p -> new RoadJunction.Paint(p.points().stream().map(v -> {
            RoadQueries.Projection qx = RoadQueries.project(mesh, v);
            return new RoadGeometry.V(v.x(), qx.sample().center().y(), v.z());
         }).toList(), p.color())).toList();
   }

   public static List<RoadJunction.Paint> markings(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> hosts) {
      return markings(ramp, hosts, hosts);
   }

   public static List<RoadJunction.Paint> markings(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> hosts, List<RoadGeometry.Mesh> neighbors) {
      return markings(ramp, hosts, neighbors, false);
   }

   public static List<RoadJunction.Paint> markings(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> hosts, List<RoadGeometry.Mesh> neighbors, boolean mainOwned) {
      if (MultiFanPaint.applies(ramp)) {
         return MultiFanPaint.markings(ramp, neighbors);
      } else if (ramp.settings().style().ramp() && !ramp.settings().style().connectorRamp()) {
         List<RoadJunction.Paint> out = new ArrayList<>();

         for (RoadGeometry.Mesh host : hosts) {
            if (twoLaneRamps(ramp, host)) {
               for (RoadJunction.Join pair : twoLaneForks(ramp, host)) {
                  if (adjacent(pair, pair.a.mesh, pair.b.mesh, neighbors, pair.a.sign > 0)) {
                     twoLaneForkGuides(out, pair);
                  }
               }
            }
         }

         for (RoadGeometry.Mesh hostx : hosts) {
            if (parallelFeeder(ramp, hostx)) {
               for (double d = 1.0; !mainOwned && d < ramp.length(); d += 3.0) {
                  RoadGeometry.Sample a = RoadStructures.sample(ramp, d);
                  RoadGeometry.Sample b = RoadStructures.sample(ramp, Math.min(ramp.length(), d + 1.5));
                  RoadGeometry.Sample ah = RoadQueries.horizontal(hostx, a.center()).sample();
                  RoadGeometry.Sample bh = RoadQueries.horizontal(hostx, b.center()).sample();
                  RoadJunction.Route route = new RoadJunction.Route(hostx, 0.0, 1);
                  stroke(out, guideEdge(route, ah, a), guideEdge(route, bh, b), 0.15);
               }

               taperMarkings(out, ramp, hostx);
               return out;
            }
         }

         for (boolean first : new boolean[]{true, false}) {
            for (RoadGeometry.Mesh hostxx : hosts) {
               if (hostxx.settings().style() != RoadGeometry.Style.UNMARKED && !twoLaneRamps(ramp, hostxx)) {
                  RoadJunction.Join pairx = join(ramp, hostxx, first);
                  if (pairx != null && adjacent(pairx, ramp, hostxx, neighbors, first)) {
                     RoadJunction.Route a = pairx.a;
                     RoadJunction.Route b = pairx.b;
                     double nose = pairx.nose;
                     double available = pairx.available;
                     if (parallelFeeder(ramp, hostxx) || pairx.parallel) {
                        for (double d = 1.0; !mainOwned && d < available; d += 3.0) {
                           stroke(
                              out,
                              guideEdge(b, paired(a, b, d), a.at(d)),
                              guideEdge(b, paired(a, b, Math.min(available, d + 1.5)), a.at(Math.min(available, d + 1.5))),
                              0.15
                           );
                        }
                     } else if (hostxx.settings().style().ramp()) {
                        rampGuides(out, pairx);
                     } else if (!goreRequired(pairx)) {
                        if (!mainOwned && !hostxx.settings().style().ramp()) {
                           for (double d = 1.0; d < nose; d += 3.0) {
                              stroke(
                                 out,
                                 guideEdge(b, paired(a, b, d), a.at(d)),
                                 guideEdge(b, paired(a, b, Math.min(nose, d + 1.5)), a.at(Math.min(nose, d + 1.5))),
                                 0.15
                              );
                           }
                        }
                     } else {
                        goreMarkings(out, pairx, mainOwned);
                     }
                  }
               }
            }
         }

         for (RoadGeometry.Mesh hostxxx : hosts) {
            if (!hostxxx.settings().style().ramp()) {
               taperMarkings(out, ramp, hostxxx);
            }
         }

         return out;
      } else {
         List<RoadJunction.Paint> out = terminalMarkings(ramp);
         if (mainOwned) {
            mainGuides(out, ramp, neighbors);
         }

         return out;
      }
   }

   private static void goreMarkings(List<RoadJunction.Paint> out, RoadJunction.Join pair, boolean mainOwned) {
      RoadJunction.Route a = pair.a;
      RoadJunction.Route b = pair.b;
      RoadGeometry.Mesh host = b.mesh;
      double nose = pair.nose;
      double available = pair.available;
      double begin = goreStart(pair);

      for (double d = 2.0; !mainOwned && !host.settings().style().ramp() && d < begin; d += 3.0) {
         double e = Math.min(begin, d + 1.5);
         RoadGeometry.Sample aa = a.at(d);
         RoadGeometry.Sample ab = a.at(e);
         RoadGeometry.Sample ba = paired(a, b, d);
         RoadGeometry.Sample bb = paired(a, b, e);
         if (aa.center().sub(ba.center()).horizontalLength() > Math.min(aa.halfWidth(), ba.halfWidth())) {
            stroke(out, guideEdge(b, ba, aa), guideEdge(b, bb, ab), 0.15);
         }
      }

      if (nose > begin + 2.0) {
         for (double dx = Math.max(0.0, begin - 3.2); dx < begin - 1.0E-7; dx += 0.25) {
            double e = Math.min(begin, dx + 0.25);
            RoadGeometry.V aa = shoulder(b.mesh) ? guideEdge(b, paired(a, b, dx), a.at(dx)) : median(a.at(dx), paired(a, b, dx));
            RoadGeometry.V bb = shoulder(b.mesh) ? guideEdge(b, paired(a, b, e), a.at(e)) : median(a.at(e), paired(a, b, e));
            stroke(out, aa, bb, 0.16);
         }

         RoadGeometry.V prevLeft = null;
         RoadGeometry.V prevRight = null;
         int steps = (int)Math.ceil((nose - begin) / 0.5);

         for (int step = 0; step <= steps; step++) {
            double dx = begin + (nose - begin) * (double)step / (double)steps;
            RoadGeometry.V[] edge = gore(a, b, dx, begin, nose);
            if (prevLeft != null) {
               stroke(out, prevLeft, edge[0], 0.16);
               stroke(out, prevRight, edge[1], 0.16);
            }

            prevLeft = edge[0];
            prevRight = edge[1];
         }

         for (double dx = begin + 2.0; dx < nose - 1.0; dx += 4.0) {
            RoadGeometry.V[] edge = gore(a, b, dx, begin, nose);
            RoadGeometry.V[] tip = gore(a, b, Math.max(begin, dx - 1.6), begin, nose);
            RoadGeometry.V center = tip[0].add(tip[1]).mul(0.5);
            stroke(out, edge[0], center, 0.2);
            stroke(out, center, edge[1], 0.2);
         }
      }

      double tail = Math.min(5.0, available - nose);

      for (double dx = nose; dx < nose + tail - 0.01; dx += 0.5) {
         double e = Math.min(nose + tail, dx + 0.5);
         RoadGeometry.V[] left = goreTail(a, b, dx, nose);
         RoadGeometry.V[] right = goreTail(a, b, e, nose);
         stroke(out, left[0], right[0], 0.18);
         stroke(out, left[1], right[1], 0.18);
      }
   }

   private static void mainGuides(List<RoadJunction.Paint> out, RoadGeometry.Mesh main, List<RoadGeometry.Mesh> neighbors) {
      if (main.settings().style() != RoadGeometry.Style.UNMARKED) {
         ArrayList<List<RoadGeometry.V>> cuts = new ArrayList<>();

         for (RoadGeometry.Mesh neighbor : neighbors) {
            cuts.addAll(closedGuidance(neighbor, main, neighbors));
         }

         RoadRailJoin boundary = new RoadRailJoin(main, neighbors.stream().map(n -> new RoadRailJoin.Neighbor(n, true)).toList());
         IdentityHashMap<RoadGeometry.Mesh, RoadRailJoin> rampBoundaries = new IdentityHashMap<>();
         RoadJunction.Route route = new RoadJunction.Route(main, 0.0, 1);
         List<LaneRampPaint.Auxiliary> auxiliary = LaneRampPaint.auxiliary(main, neighbors);

         for (RoadGeometry.Mesh ramp : neighbors) {
            if (ramp.settings().style().connectorRamp()) {
               for (boolean first : new boolean[]{true, false}) {
                  if (taperClosure(ramp, main, first)) {
                     taperMarkings(out, ramp, main, first);
                     if (!first) {
                        out.addAll(LaneRampPaint.arrows(ramp, List.of(main)));
                     }

                     RoadJunction.Join pair = measureJoin(ramp, main, first);
                     if (pair != null && !pair.parallel) {
                        goreMarkings(out, pair, true);
                     }
                  }
               }
            }
         }

         for (double d = 1.0; d < main.length(); d += 3.0) {
            RoadGeometry.Sample a = RoadStructures.sample(main, d);
            RoadGeometry.Sample b = RoadStructures.sample(main, Math.min(main.length(), d + 1.5));
            RoadGeometry.Sample mid = RoadStructures.sample(main, Math.min(main.length(), d + 0.75));

            for (int side : new int[]{-1, 1}) {
               RoadProfile.Layout layout = RoadProfile.layout(main, mid);
               if (layout.cycleWidth() > 0.01) {
                  if (LaneRampPaint.auxiliaryAt(main, a, b, side, auxiliary)
                     && auxiliary.stream()
                        .anyMatch(
                           item -> item.side() == side
                                 && LaneRampPaint.guideFits(main, a, b, side, item.ramp())
                                 && openGuide(main, a, b, side, item.ramp(), rampBoundaries, neighbors)
                        )) {
                     ArrayList<RoadJunction.Paint> strokes = new ArrayList<>();
                     stroke(strokes, a.at(RoadProfile.layout(main, a).outer(side), 0.0), b.at(RoadProfile.layout(main, b).outer(side), 0.0), 0.15);

                     for (RoadJunction.Paint paint : strokes) {
                        if (cuts.stream().noneMatch(cut -> RoadSurface.area(RoadSurface.intersect(paint.points(), cut)) > 1.0E-8)) {
                           out.add(paint);
                        }
                     }
                  }
               } else {
                  boolean highway = layout.catalog().type() == RoadProfile.Type.HIGHWAY;
                  RoadGeometry.V edge = mid.at((double)side * mid.halfWidth(), 0.0);

                  for (RoadGeometry.Mesh rampx : neighbors) {
                     if (rampx.settings().style().ramp() || rampx.settings().options().lanePoints().link() != null) {
                        RoadQueries.Projection q = RoadQueries.horizontal(rampx, edge);
                        LanePoints.Link link = rampx.settings().options().lanePoints().link();
                        if ((
                              link == null
                                 || !auxiliary.stream()
                                    .noneMatch(
                                       item -> item.ramp() == rampx && item.side() == side && mid.distance() >= item.from() && mid.distance() <= item.to()
                                    )
                           )
                           && !(Math.abs(q.sample().center().y() - mid.center().y()) > 0.12)
                           && !(q.horizontalDistance() > q.sample().halfWidth() + 0.12)
                           && !(Math.abs(q.sample().left().dot(mid.left())) < 0.9)
                           && !(q.sample().center().sub(mid.center()).dot(mid.left()) * (double)side < 0.0)) {
                           RoadGeometry.V other = mid.at((double)side * (mid.halfWidth() + 2.0), 0.0);
                           RoadGeometry.Sample target = new RoadGeometry.Sample(other, mid.left(), 0.0, 1.0);
                           RoadGeometry.V firstx = a.at((double)side * (a.halfWidth() - 0.34), 0.0);
                           RoadGeometry.V last = b.at((double)side * (b.halfWidth() - 0.34), 0.0);
                           if ((highway || boundary.exposed(firstx, last, firstx.add(last).mul(0.5).add(mid.left().mul((double)side))).isEmpty())
                              && LaneRampPaint.guideFits(main, a, b, side, rampx)
                              && openGuide(main, a, b, side, rampx, rampBoundaries, neighbors)) {
                              ArrayList<RoadJunction.Paint> strokes = new ArrayList<>();
                              stroke(strokes, guideEdge(route, a, target), guideEdge(route, b, target), 0.15);

                              for (RoadJunction.Paint paintx : strokes) {
                                 if (cuts.stream().noneMatch(cut -> RoadSurface.area(RoadSurface.intersect(paintx.points(), cut)) > 1.0E-8)) {
                                    out.add(paintx);
                                 }
                              }
                              break;
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean openGuide(
      RoadGeometry.Mesh main,
      RoadGeometry.Sample a,
      RoadGeometry.Sample b,
      int side,
      RoadGeometry.Mesh ramp,
      Map<RoadGeometry.Mesh, RoadRailJoin> boundaries,
      List<RoadGeometry.Mesh> neighbors
   ) {
      RoadProfile.Layout la = RoadProfile.layout(main, a);
      RoadProfile.Layout lb = RoadProfile.layout(main, b);
      boolean motor = la.cycleWidth() > 0.01;
      RoadQueries.Projection qa = RoadQueries.horizontal(ramp, a.at(motor ? la.outer(side) : (double)side * a.halfWidth(), 0.0));
      RoadQueries.Projection qb = RoadQueries.horizontal(ramp, b.at(motor ? lb.outer(side) : (double)side * b.halfWidth(), 0.0));
      RoadGeometry.Sample ra = qa.sample();
      RoadGeometry.Sample rb = qb.sample();
      int inside = ra.left().dot(a.center().sub(ra.center())) < 0.0 ? -1 : 1;
      double ia = RoadRailJoin.inset(ramp, ra, inside);
      double ib = RoadRailJoin.inset(ramp, rb, inside);
      RoadGeometry.V first = ra.at((double)inside * (ra.halfWidth() - ia), 0.0);
      RoadGeometry.V last = rb.at((double)inside * (rb.halfWidth() - ib), 0.0);
      RoadGeometry.V outside = first.add(last).mul(0.5).add(ra.left().add(rb.left()).horizontalUnit().mul((double)inside * 0.4));
      RoadRailJoin boundary = boundaries.computeIfAbsent(
         ramp,
         r -> new RoadRailJoin(
               r, Stream.concat(Stream.of(main), neighbors.stream().filter(n -> n != r)).distinct().map(n -> new RoadRailJoin.Neighbor(n, true)).toList()
            )
      );
      return boundary.exposed(first, last, outside, (ia + ib) / 2.0).isEmpty();
   }

   private static void rampGuides(List<RoadJunction.Paint> out, RoadJunction.Join pair) {
      if (twoLaneFork(pair)) {
         twoLaneForkGuides(out, pair);
      } else {
         double limit = forkLimit(pair);

         for (double d = 0.0; d < limit; d += 0.5) {
            double e = Math.min(limit, d + 0.5);
            RoadGeometry.Sample aa = pair.a.at(d);
            RoadGeometry.Sample bb = paired(pair.a, pair.b, d);
            RoadGeometry.Sample ab = pair.a.at(e);
            RoadGeometry.Sample bc = paired(pair.a, pair.b, e);
            boolean dash = (d + e) / 2.0 % 6.0 < 3.0;
            int lanesA = RoadProfile.catalog(pair.a.mesh.settings()).lanes();
            int lanesB = RoadProfile.catalog(pair.b.mesh.settings()).lanes();
            double separation = aa.center().sub(bb.center()).horizontalLength();
            double lane = Math.max(
               2.8, Math.min(pair.a.mesh.settings().width() / (double)Math.max(1, lanesA), pair.b.mesh.settings().width() / (double)Math.max(1, lanesB)) * 0.8
            );
            if (d < pair.nose && dash && (lanesA > 1 || lanesB > 1 || separation > lane)) {
               forkStroke(out, median(aa, bb), median(ab, bc), 0.15, pair);
            }

            if (dash && (separation >= lane * 2.0 || d >= pair.nose)) {
               if (lanesA > 1) {
                  forkStroke(out, aa.center(), ab.center(), 0.12, pair);
               }

               if (lanesB > 1) {
                  forkStroke(out, bb.center(), bc.center(), 0.12, pair);
               }
            }
         }
      }
   }

   private static boolean twoLaneRamps(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      return a.settings().style().ramp()
         && b.settings().style().ramp()
         && RoadProfile.catalog(a.settings()).lanes() == 2
         && RoadProfile.catalog(b.settings()).lanes() == 2;
   }

   private static int meshOrder(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      for (double[] values : new double[][]{
         {a.min().x(), b.min().x()}, {a.min().z(), b.min().z()}, {a.max().x(), b.max().x()}, {a.max().z(), b.max().z()}, {a.length(), b.length()}
      }) {
         int compare = Double.compare(values[0], values[1]);
         if (compare != 0) {
            return compare;
         }
      }

      return 0;
   }

   private static List<RoadJunction.Join> twoLaneForks(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      if (meshOrder(a, b) > 0) {
         RoadGeometry.Mesh swap = a;
         a = b;
         b = swap;
      }

      List<RoadJunction.Join> result = new ArrayList<>();

      for (RoadGeometry.Mesh ramp : List.of(a, b)) {
         for (boolean first : new boolean[]{true, false}) {
            RoadJunction.Join candidate = join(ramp, ramp == a ? b : a, first);
            if (candidate != null && !candidate.parallel) {
               RoadGeometry.V origin = candidate.a.at(0.0).center();
               if (!result.stream()
                  .anyMatch(
                     p -> p.a.at(0.0).center().distance(origin) < 0.5
                           && p.a.at(1.0).center().sub(p.a.at(0.0).center()).dot(candidate.a.at(1.0).center().sub(origin)) > 0.0
                  )) {
                  result.add(candidate);
               }
            }
         }
      }

      return result;
   }

   private static boolean twoLaneFork(RoadJunction.Join pair) {
      return RoadProfile.catalog(pair.a.mesh.settings()).lanes() == 2 && RoadProfile.catalog(pair.b.mesh.settings()).lanes() == 2;
   }

   private static double forkLimit(RoadJunction.Join pair) {
      if (!twoLaneFork(pair)) {
         return Math.min(pair.available, pair.nose + 7.0);
      } else {
         double lane = Math.max(
            RoadProfile.layout(pair.a.mesh, pair.a.at(pair.nose)).laneWidth(), RoadProfile.layout(pair.b.mesh, paired(pair.a, pair.b, pair.nose)).laneWidth()
         );
         return Math.min(pair.available, pair.nose + Math.max(24.0, lane * 8.0));
      }
   }

   private static void twoLaneForkGuides(List<RoadJunction.Paint> out, RoadJunction.Join pair) {
      double limit = forkLimit(pair);
      double begin = Math.max(0.0, pair.nose - 28.0);

      for (double d = 0.0; d < Math.max(0.0, begin - 3.2) - 1.0E-7; d += 0.25) {
         double e = Math.min(begin - 3.2, d + 0.25);
         if (!((d + e) / 2.0 % 6.0 >= 3.0)) {
            forkStroke(out, median(pair.a.at(d), paired(pair.a, pair.b, d)), median(pair.a.at(e), paired(pair.a, pair.b, e)), 0.12, pair);
         }
      }

      for (double dx = Math.max(0.0, begin - 3.2); dx < begin - 1.0E-7; dx += 0.25) {
         double e = Math.min(begin, dx + 0.25);
         RoadGeometry.V a = median(pair.a.at(dx), paired(pair.a, pair.b, dx));
         RoadGeometry.V b = median(pair.a.at(e), paired(pair.a, pair.b, e));
         forkStroke(out, a, b, 0.16, pair);
      }

      for (double dx = begin; dx < limit - 1.0E-6; dx += 0.5) {
         double e = Math.min(limit, dx + 0.5);
         RoadGeometry.V[][] a = forkEdges(pair, dx);
         RoadGeometry.V[][] b = forkEdges(pair, e);
         double width = a[0][0].sub(a[0][1]).horizontalLength() + a[1][0].sub(a[1][1]).horizontalLength();
         if (dx < pair.nose && width < 0.16) {
            forkStroke(out, a[0][0].add(a[1][0]).mul(0.5), b[0][0].add(b[1][0]).mul(0.5), 0.15, pair);
         } else {
            for (int side = 0; side < 2; side++) {
               forkStroke(out, a[side][0], b[side][0], 0.16, pair);
            }
         }

         if (dx >= pair.nose) {
            for (int side = 0; side < 2; side++) {
               RoadJunction.Route route = side == 0 ? pair.a : pair.b;
               RoadGeometry.Sample sa = side == 0 ? pair.a.at(dx) : paired(pair.a, pair.b, dx);
               RoadGeometry.Sample sb = side == 0 ? pair.a.at(e) : paired(pair.a, pair.b, e);
               RoadGeometry.V ca = sa.at(RoadProfile.layout(route.mesh, sa).motorCenter(), 0.0);
               RoadGeometry.V cb = sb.at(RoadProfile.layout(route.mesh, sb).motorCenter(), 0.0);
               if (ca.sub(a[side][0]).horizontalLength() > 0.3
                  && cb.sub(b[side][0]).horizontalLength() > 0.3
                  && (sa.distance() + sb.distance()) / 2.0 % 6.0 < 3.0) {
                  forkStroke(out, ca, cb, 0.12, pair);
               }
            }
         }
      }

      for (int sidex = 0; sidex < 2; sidex++) {
         for (double dx = begin + 2.0; dx < limit - 1.0; dx += 4.0) {
            RoadGeometry.V[] edge = forkEdges(pair, dx)[sidex];
            double run = Math.min(limit - dx, edge[0].sub(edge[1]).horizontalLength());
            if (!(run < 0.35)) {
               forkHatch(out, pair, sidex, dx, dx + run);
            }
         }
      }
   }

   private static RoadGeometry.V[][] forkEdges(RoadJunction.Join pair, double d) {
      RoadGeometry.Sample a = pair.a.at(d);
      RoadGeometry.Sample b = paired(pair.a, pair.b, d);
      RoadGeometry.V middle = median(a, b);
      RoadGeometry.V[][] edges = new RoadGeometry.V[2][];
      double open = RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, (d - pair.nose) / Math.max(0.5, forkLimit(pair) - pair.nose))));

      for (int i = 0; i < 2; i++) {
         RoadJunction.Route route = i == 0 ? pair.a : pair.b;
         RoadGeometry.Sample at = i == 0 ? a : b;
         RoadGeometry.Sample other = i == 0 ? b : a;
         int toward = at.left().dot(other.center().sub(at.center())) >= 0.0 ? 1 : -1;
         double center = RoadProfile.layout(route.mesh, at).motorCenter();
         double inner = (double)toward * (at.halfWidth() - 0.3);
         double common = middle.sub(at.center()).dot(at.left());
         double boundary = center + (double)toward * Math.max(0.0, Math.min((inner - center) * (double)toward, (common - center) * (double)toward));
         RoadGeometry.V closed = at.at(boundary, 0.0);
         if (d < pair.nose
            && (common - inner) * (double)toward <= 0.0
            && RoadQueries.contains(pair.a.mesh, middle, 0.03, 0.1)
            && RoadQueries.contains(pair.b.mesh, middle, 0.03, 0.1)) {
            closed = middle;
         }

         RoadGeometry.V flow = at.at(center + (boundary - center) * open, 0.0);
         if (d < pair.nose) {
            double begin = Math.max(0.0, pair.nose - 28.0);
            double spread = RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, (d - begin) / Math.min(6.0, Math.max(0.5, pair.nose - begin)))));
            flow = middle.add(flow.sub(middle).mul(spread));
         }

         edges[i] = new RoadGeometry.V[]{flow, closed};
      }

      return edges;
   }

   private static void forkStroke(List<RoadJunction.Paint> out, RoadGeometry.V a, RoadGeometry.V b, double width, RoadJunction.Join pair) {
      stroke(out, a, b, width);
   }

   private static void forkHatch(List<RoadJunction.Paint> out, RoadJunction.Join pair, int side, double from, double to) {
      ArrayList<RoadJunction.Paint> raw = new ArrayList<>();
      stroke(raw, forkEdges(pair, from)[side][1], forkEdges(pair, to)[side][0], 0.18);

      for (double d = from; d < to - 1.0E-7; d += 0.25) {
         RoadGeometry.V[] a = forkEdges(pair, d)[side];
         RoadGeometry.V[] b = forkEdges(pair, Math.min(to, d + 0.25))[side];
         List<RoadGeometry.V> band = List.of(a[0], a[1], b[1], b[0]);

         for (RoadJunction.Paint paint : raw) {
            List<RoadGeometry.V> clipped = RoadSurface.intersect(paint.points(), band);
            if (RoadSurface.area(clipped) > 1.0E-9) {
               out.add(new RoadJunction.Paint(clipped, paint.color()));
            }
         }
      }
   }

   private static RoadGeometry.V taperEdge(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, boolean first, double d, boolean outer) {
      RoadGeometry.Sample at = RoadStructures.sample(ramp, first ? d : ramp.length() - d);
      RoadGeometry.Sample hs = RoadQueries.horizontal(host, at.center()).sample();
      if (!outer) {
         return guideEdge(new RoadJunction.Route(host, 0.0, 1), hs, at);
      } else {
         int toward = at.left().dot(hs.center().sub(at.center())) > 0.0 ? 1 : -1;
         int outside = hs.left().dot(at.center().sub(hs.center())) >= 0.0 ? 1 : -1;
         RoadGeometry.V rampEdge = at.at(
            ramp.settings().style().connectorRamp() ? RoadSurface.edgeOffset(ramp, at, -toward) : (double)(-toward) * (at.halfWidth() - 0.3), 0.0
         );
         RoadGeometry.V mainEdge = hs.at((double)outside * (hs.halfWidth() - 0.3), 0.0);
         return rampEdge.sub(mainEdge).dot(hs.left()) * (double)outside > 0.0 ? rampEdge : mainEdge;
      }
   }

   private static double taperDiagonal(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, boolean first, double inner, double outer) {
      RoadGeometry.V start = taperEdge(ramp, host, first, inner, false);
      RoadGeometry.V delta = taperEdge(ramp, host, first, outer, true).sub(start);
      RoadGeometry.V axis = RoadQueries.horizontal(host, start).tangent().horizontalUnit();
      RoadGeometry.V away = (first ? ramp.first() : ramp.last()).left().left().mul(first ? -1.0 : 1.0);
      if (axis.dot(away) < 0.0) {
         axis = axis.mul(-1.0);
      }

      return delta.dot(axis) - Math.abs(delta.dot(axis.left()));
   }

   private static double goreStart(RoadJunction.Join pair) {
      RoadJunction.Route a = pair.a;
      RoadJunction.Route b = pair.b;
      double nose = pair.nose;
      RoadGeometry.Mesh ramp = a.mesh;
      boolean shoulder = shoulder(b.mesh);
      double begin = nose;
      double search = shoulder ? 1.0 : Math.max(1.0, nose - 28.0);
      if (shoulder && a.at(0.0).halfWidth() * 2.0 < ramp.settings().width() * 0.94) {
         for (double d = 0.0; d < nose; d += 0.5) {
            RoadGeometry.Sample aa = a.at(d);
            RoadGeometry.Sample bb = paired(a, b, d);
            double overlap = aa.halfWidth() + bb.halfWidth() - aa.center().sub(bb.center()).horizontalLength();
            if (aa.halfWidth() * 2.0 >= ramp.settings().width() * 0.999 && overlap > 0.6 && Math.abs(aa.left().dot(bb.left())) > 0.9999) {
               search = Math.max(search, d);
            }
         }
      }

      if (a.at(0.0).halfWidth() * 2.0 >= ramp.settings().width() * 0.94) {
         search = Math.min(search, nose - 18.0);
      }

      for (double dx = search; dx < nose; dx += 0.5) {
         RoadGeometry.Sample aa = a.at(dx);
         RoadGeometry.Sample bb = paired(a, b, dx);
         double overlap = aa.halfWidth() + bb.halfWidth() - aa.center().sub(bb.center()).horizontalLength();
         RoadGeometry.V hostEdge = guideEdge(b, bb, aa);
         RoadGeometry.V rampEdge = innerEdge(a, aa, bb);
         double opening = hostEdge.sub(rampEdge).dot(separation(aa, bb));
         if (shoulder ? opening > 0.08 : overlap < 3.5) {
            begin = dx;
            break;
         }
      }

      if (highwayExtra(ramp, b.mesh, a.sign > 0)) {
         LanePoints.Link link = ramp.settings().options().lanePoints().link();
         begin = Math.min(nose, Math.max(begin, LaneRampApproach.length(b.mesh, link.options().transition())));
      }

      return begin;
   }

   public static List<List<RoadGeometry.V>> closedGuidance(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host) {
      return closedGuidance(ramp, host, List.of());
   }

   public static List<List<RoadGeometry.V>> closedGuidance(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, List<RoadGeometry.Mesh> neighbors) {
      ArrayList<List<RoadGeometry.V>> out = new ArrayList<>();
      if (ramp.settings().style().ramp() && !host.settings().style().ramp()) {
         if (ramp.settings().style().connectorRamp()) {
            out.addAll(connectorClosedGuidance(ramp, host, neighbors));
         }

         for (boolean first : new boolean[]{true, false}) {
            if (taperClosure(ramp, host, first)) {
               double reach = closureLength(ramp, host, first);
               double lo = 0.0;
               double hi = reach;

               for (int i = 0; i < 24; i++) {
                  double mid = (lo + hi) / 2.0;
                  if (taperDiagonal(ramp, host, first, mid, reach) > 0.0) {
                     lo = mid;
                  } else {
                     hi = mid;
                  }
               }

               double inner = (lo + hi) / 2.0;

               for (double d = 0.0; d < inner - 1.0E-6; d += 0.5) {
                  double e = Math.min(inner, d + 0.5);
                  out.add(
                     List.of(
                        taperEdge(ramp, host, first, d, false),
                        taperEdge(ramp, host, first, d, true),
                        taperEdge(ramp, host, first, e, true),
                        taperEdge(ramp, host, first, e, false)
                     )
                  );
               }

               RoadGeometry.V tip = taperEdge(ramp, host, first, inner, false);

               for (double d = inner; d < reach - 1.0E-6; d += 0.5) {
                  out.add(List.of(tip, taperEdge(ramp, host, first, d, true), taperEdge(ramp, host, first, Math.min(reach, d + 0.5), true)));
               }
            }

            RoadJunction.Join pair = highwayExtra(ramp, host, first) ? measureJoin(ramp, host, first) : join(ramp, host, first);
            if (pair != null && !pair.parallel && !parallelFeeder(ramp, host) && goreRequired(pair)) {
               double begin = goreStart(pair);

               for (double d = begin; d < pair.nose - 1.0E-6; d += 0.5) {
                  RoadGeometry.V[] a = gore(pair.a, pair.b, d, begin, pair.nose);
                  RoadGeometry.V[] b = gore(pair.a, pair.b, Math.min(pair.nose, d + 0.5), begin, pair.nose);
                  out.add(List.of(a[0], a[1], b[1], b[0]));
               }
            }
         }

         ArrayList<List<RoadGeometry.V>> expanded = new ArrayList<>(out);

         for (List<RoadGeometry.V> polygon : out) {
            for (int ix = 0; ix < polygon.size(); ix++) {
               ArrayList<RoadJunction.Paint> strokes = new ArrayList<>();
               stroke(strokes, polygon.get(ix), polygon.get((ix + 1) % polygon.size()), 0.24);

               for (RoadJunction.Paint stroke : strokes) {
                  expanded.add(stroke.points());
               }
            }
         }

         return List.copyOf(expanded);
      } else {
         return out;
      }
   }

   private static List<List<RoadGeometry.V>> connectorClosedGuidance(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, List<RoadGeometry.Mesh> neighbors) {
      if (!LaneMerge.linkedTo(host, ramp)) {
         return List.of();
      } else {
         List<RoadGeometry.Mesh> context = Stream.concat(Stream.of(ramp, host), neighbors.stream()).distinct().toList();
         RoadRailJoin ours = RoadRailJoin.paint(ramp, context.stream().filter(m -> m != ramp).map(m -> new RoadRailJoin.Neighbor(m, true)).toList());
         RoadRailJoin theirs = RoadRailJoin.paint(host, context.stream().filter(m -> m != host).map(m -> new RoadRailJoin.Neighbor(m, true)).toList());
         ArrayList<List<RoadGeometry.V>> out = new ArrayList<>();

         for (double d = 0.0; d < ramp.length() - 1.0E-7; d += 0.5) {
            RoadGeometry.Sample a = RoadStructures.sample(ramp, d);
            RoadGeometry.Sample b = RoadStructures.sample(ramp, Math.min(ramp.length(), d + 0.5));
            RoadQueries.Projection qa = RoadQueries.horizontal(host, a.center());
            RoadQueries.Projection qb = RoadQueries.horizontal(host, b.center());
            RoadGeometry.Sample ha = qa.sample();
            RoadGeometry.Sample hb = qb.sample();
            if (!(Math.abs(a.center().y() - ha.center().y()) > 0.12)
               && !(Math.abs(b.center().y() - hb.center().y()) > 0.12)
               && !(qa.horizontalDistance() > a.halfWidth() + ha.halfWidth() + 2.0)
               && !(Math.abs(a.left().dot(ha.left())) < 0.9)) {
               int side = a.left().dot(ha.center().sub(a.center())) > 0.0 ? 1 : -1;
               int hostSide = qa.lateral() > 0.0 ? 1 : -1;
               RoadGeometry.V ra = a.at(RoadSurface.edgeOffset(ramp, a, side), 0.0);
               RoadGeometry.V rb = b.at(RoadSurface.edgeOffset(ramp, b, side), 0.0);
               RoadGeometry.V pa = ha.at(RoadSurface.edgeOffset(host, ha, hostSide), 0.0);
               RoadGeometry.V pb = hb.at(RoadSurface.edgeOffset(host, hb, hostSide), 0.0);
               if (!(ra.sub(pa).horizontalLength() > 2.5) && !(rb.sub(pb).horizontalLength() > 2.5)) {
                  double ri = (
                        a.halfWidth() - Math.abs(RoadSurface.edgeOffset(ramp, a, side)) + b.halfWidth() - Math.abs(RoadSurface.edgeOffset(ramp, b, side))
                     )
                     / 2.0;
                  double hi = (
                        ha.halfWidth()
                           - Math.abs(RoadSurface.edgeOffset(host, ha, hostSide))
                           + hb.halfWidth()
                           - Math.abs(RoadSurface.edgeOffset(host, hb, hostSide))
                     )
                     / 2.0;
                  List<RoadRailJoin.Span> rs = ours.exposed(ra, rb, ra.add(rb).mul(0.5).add(a.left().mul((double)side)), ri);
                  List<RoadRailJoin.Span> hs = theirs.exposed(pa, pb, pa.add(pb).mul(0.5).add(ha.left().mul((double)hostSide)), hi);
                  RoadGeometry.V r = rb.sub(ra);
                  RoadGeometry.V h = pb.sub(pa);
                  double rl = r.dot(r);
                  double hl = h.dot(h);
                  if (!(rl < 1.0E-10) && !(hl < 1.0E-10)) {
                     for (RoadRailJoin.Span one : rs) {
                        for (RoadRailJoin.Span two : hs) {
                           double lo = Math.max(one.a().sub(ra).dot(r) / rl, two.a().sub(pa).dot(h) / hl);
                           double end = Math.min(one.b().sub(ra).dot(r) / rl, two.b().sub(pa).dot(h) / hl);
                           if (end > lo + 1.0E-7) {
                              out.add(List.of(pa.add(h.mul(lo)), ra.add(r.mul(lo)), ra.add(r.mul(end)), pa.add(h.mul(end))));
                           }
                        }
                     }
                  }
               }
            }
         }

         return List.copyOf(out);
      }
   }

   public static double[] auxiliaryRange(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host) {
      if (ramp != null && host != null && ramp.settings().style().ramp() && !host.settings().style().ramp()) {
         double from = Double.POSITIVE_INFINITY;
         double to = Double.NEGATIVE_INFINITY;

         for (boolean first : new boolean[]{true, false}) {
            RoadJunction.Join pair = join(ramp, host, first);
            if (pair != null) {
               double end = pair.parallel ? pair.available : Math.max(0.0, pair.nose - 0.68);
               from = Math.min(from, first ? 0.0 : ramp.length() - end);
               to = Math.max(to, first ? end : ramp.length());
            }
         }

         return Double.isFinite(from) ? new double[]{from, to} : null;
      } else {
         return null;
      }
   }

   private static void taperMarkings(List<RoadJunction.Paint> out, RoadGeometry.Mesh ramp, RoadGeometry.Mesh host) {
      for (boolean first : new boolean[]{true, false}) {
         if (taperClosure(ramp, host, first)) {
            taperMarkings(out, ramp, host, first);
         }
      }
   }

   private static void taperMarkings(List<RoadJunction.Paint> out, RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, boolean first) {
      double reach = closureLength(ramp, host, first);
      double lo = 0.0;
      double hi = reach;

      for (int step = 0; step < 24; step++) {
         double mid = (lo + hi) / 2.0;
         if (taperDiagonal(ramp, host, first, mid, reach) > 0.0) {
            lo = mid;
         } else {
            hi = mid;
         }
      }

      double innerEnd = (lo + hi) / 2.0;

      for (boolean outer : new boolean[]{false, true}) {
         double limit = outer ? reach : innerEnd;

         for (double d = 0.0; d < limit - 1.0E-6; d += 0.5) {
            stroke(out, taperEdge(ramp, host, first, d, outer), taperEdge(ramp, host, first, Math.min(limit, d + 0.5), outer), 0.16);
         }
      }

      double spacing = (first ? ramp.first() : ramp.last()).halfWidth() < 0.5 ? 2.5 : 4.0;

      for (double d = 1.0; d < innerEnd - 2.0; d += spacing) {
         double from = d;
         double to = reach;

         for (int stepx = 0; stepx < 24; stepx++) {
            double mid = (from + to) / 2.0;
            if (taperDiagonal(ramp, host, first, d, mid) < 0.0) {
               from = mid;
            } else {
               to = mid;
            }
         }

         double outer = (from + to) / 2.0;
         if (outer - d > 0.4) {
            stroke(out, taperEdge(ramp, host, first, d, false), taperEdge(ramp, host, first, outer, true), 0.18);
         }
      }

      stroke(out, taperEdge(ramp, host, first, innerEnd, false), taperEdge(ramp, host, first, reach, true), 0.18);
   }

   private static RoadGeometry.V[] goreTail(RoadJunction.Route a, RoadJunction.Route b, double d, double nose) {
      RoadGeometry.V[] edge = borders(a, b, d);
      RoadGeometry.Sample aa = a.at(d);
      RoadGeometry.Sample bb = paired(a, b, d);
      if (shoulder(b.mesh)) {
         return new RoadGeometry.V[]{edge[0], guideEdge(b, bb, aa)};
      } else {
         RoadGeometry.V side = separation(aa, bb);
         double half = Math.min(1.1, Math.min(aa.halfWidth(), bb.halfWidth()) * 0.3);
         double extra = Math.max(0.0, half - 0.3) * Math.max(0.0, 1.0 - (d - nose) / 5.0);
         return new RoadGeometry.V[]{edge[0].sub(side.mul(extra)), edge[1].add(side.mul(extra))};
      }
   }

   private static List<RoadJunction.Paint> terminalMarkings(RoadGeometry.Mesh mesh) {
      List<RoadJunction.Paint> out = new ArrayList<>();

      for (double d = 0.5; d <= mesh.length(); d += 0.5) {
         RoadGeometry.Sample a = RoadStructures.sample(mesh, d - 0.5);
         RoadGeometry.Sample b = RoadStructures.sample(mesh, d);
         double ia = RoadTransitions.dropBoundary(mesh, a);
         double ib = RoadTransitions.dropBoundary(mesh, b);
         if (Double.isFinite(ia) && Double.isFinite(ib)) {
            for (int side : new int[]{-1, 1}) {
               double oa = Math.abs(RoadProfile.layout(mesh, a).outer(side)) - 0.2;
               double ob = Math.abs(RoadProfile.layout(mesh, b).outer(side)) - 0.2;
               if (!(oa - ia < 0.1) && !(ob - ib < 0.1)) {
                  stroke(out, a.at((double)side * ia, 0.0), b.at((double)side * ib, 0.0), 0.15);
                  if ((int)Math.round(d * 2.0) % 8 == 0) {
                     RoadGeometry.Sample tip = RoadStructures.sample(mesh, Math.min(mesh.length(), d + 1.3));
                     double inner = RoadTransitions.dropBoundary(mesh, tip);
                     if (Double.isFinite(inner)) {
                        stroke(out, b.at((double)side * ob, 0.0), tip.at((double)side * inner, 0.0), 0.18);
                     }
                  }
               }
            }
         }
      }

      return out;
   }

   public static List<List<RoadGeometry.V>> terminalCuts(RoadGeometry.Mesh mesh) {
      List<List<RoadGeometry.V>> out = new ArrayList<>();
      if (!mesh.settings().options().ends().persistent()) {
         return out;
      } else {
         for (double d = 0.25; d <= mesh.length() + 0.249; d += 0.25) {
            RoadGeometry.Sample a = RoadStructures.sample(mesh, Math.max(0.0, d - 0.25));
            RoadGeometry.Sample b = RoadStructures.sample(mesh, Math.min(d, mesh.length()));
            double ia = RoadTransitions.dropBoundary(mesh, a);
            double ib = RoadTransitions.dropBoundary(mesh, b);
            if (Double.isFinite(ib)) {
               if (!Double.isFinite(ia)) {
                  ia = Math.abs(RoadProfile.layout(mesh, a).outer(1));
               }

               for (int side : new int[]{-1, 1}) {
                  out.add(
                     List.of(
                        a.at((double)side * (ia - 0.15), 0.0),
                        a.at((double)side * (a.halfWidth() + 1.0), 0.0),
                        b.at((double)side * (b.halfWidth() + 1.0), 0.0),
                        b.at((double)side * (ib - 0.15), 0.0)
                     )
                  );
               }
            }
         }

         return out;
      }
   }

   private static RoadGeometry.V separation(RoadGeometry.Sample a, RoadGeometry.Sample b) {
      RoadGeometry.V d = b.center().sub(a.center());
      return d.horizontalLength() < 1.0E-5 ? a.left() : d.horizontalUnit();
   }

   private static RoadGeometry.V median(RoadGeometry.Sample a, RoadGeometry.Sample b) {
      return a.center().add(b.center()).mul(0.5).add(separation(a, b).mul((a.halfWidth() - b.halfWidth()) / 2.0));
   }

   private static RoadGeometry.V guideEdge(RoadJunction.Route route, RoadGeometry.Sample at, RoadGeometry.Sample other) {
      RoadProfile.Layout layout = RoadProfile.layout(route.mesh, at);
      int side = at.left().dot(other.center().sub(at.center())) >= 0.0 ? 1 : -1;
      return layout.catalog().type() == RoadProfile.Type.HIGHWAY ? at.at(layout.outer(side), 0.0) : innerEdge(route, at, other);
   }

   private static RoadGeometry.V innerEdge(RoadJunction.Route route, RoadGeometry.Sample at, RoadGeometry.Sample other) {
      int side = at.left().dot(other.center().sub(at.center())) >= 0.0 ? 1 : -1;
      double offset = route.mesh.settings().style().connectorRamp() ? RoadSurface.edgeOffset(route.mesh, at, side) : (double)side * (at.halfWidth() - 0.3);
      return at.at(offset, 0.0);
   }

   private static RoadGeometry.Sample paired(RoadJunction.Route a, RoadJunction.Route b, double distance) {
      RoadGeometry.V point = a.at(distance).center();
      RoadGeometry.Sample nearest = RoadQueries.horizontal(b.mesh, point).sample();
      if (!twoLaneRamps(a.mesh, b.mesh)) {
         return nearest;
      } else {
         double station = nearest.distance();

         for (int i = 0; i < 8; i++) {
            RoadGeometry.Sample at = pairedSample(b.mesh, station);
            double f = point.sub(at.center()).dot(at.left().left().mul(-1.0));
            if (Math.abs(f) < 1.0E-7) {
               break;
            }

            double lo = b.mesh.closed() ? station - 0.1 : Math.max(0.0, station - 0.1);
            double hi = b.mesh.closed() ? station + 0.1 : Math.min(b.mesh.length(), station + 0.1);
            RoadGeometry.Sample al = pairedSample(b.mesh, lo);
            RoadGeometry.Sample ah = pairedSample(b.mesh, hi);
            double derivative = (point.sub(ah.center()).dot(ah.left().left().mul(-1.0)) - point.sub(al.center()).dot(al.left().left().mul(-1.0))) / (hi - lo);
            if (Math.abs(derivative) < 0.15) {
               break;
            }

            double lower = b.mesh.closed() ? nearest.distance() - 4.0 : Math.max(0.0, nearest.distance() - 4.0);
            double upper = b.mesh.closed() ? nearest.distance() + 4.0 : Math.min(b.mesh.length(), nearest.distance() + 4.0);
            station = Math.max(lower, Math.min(upper, station - Math.max(-1.0, Math.min(1.0, f / derivative))));
         }

         return pairedSample(b.mesh, station);
      }
   }

   private static RoadGeometry.Sample pairedSample(RoadGeometry.Mesh mesh, double station) {
      if (mesh.closed()) {
         station = (station % mesh.length() + mesh.length()) % mesh.length();
      }

      return RoadStructures.sample(mesh, station);
   }

   private static RoadGeometry.V[] borders(RoadJunction.Route a, RoadJunction.Route b, double d) {
      RoadGeometry.Sample aa = a.at(d);
      RoadGeometry.Sample bb = paired(a, b, d);
      return new RoadGeometry.V[]{innerEdge(a, aa, bb), innerEdge(b, bb, aa)};
   }

   private static boolean shoulder(RoadGeometry.Mesh mesh) {
      return RoadProfile.layout(mesh, mesh.first()).shoulderWidth() > 0.1;
   }

   private static boolean goreRequired(RoadJunction.Join join) {
      if (join.b.mesh.settings().style().ramp()) {
         return false;
      } else if (shoulder(join.b.mesh)) {
         return true;
      } else {
         for (double d = 0.0; d <= join.nose; d += 2.0) {
            if (Double.isFinite(RoadTransitions.dropBoundary(join.b.mesh, paired(join.a, join.b, d)))) {
               return true;
            }
         }

         return false;
      }
   }

   private static RoadGeometry.V[] gore(RoadJunction.Route a, RoadJunction.Route b, double d, double start, double end) {
      RoadGeometry.Sample aa = a.at(d);
      RoadGeometry.Sample bb = paired(a, b, d);
      if (shoulder(b.mesh)) {
         RoadGeometry.V host = guideEdge(b, bb, aa);
         RoadGeometry.V ramp = innerEdge(a, aa, bb);
         double opening = host.sub(ramp).dot(separation(aa, bb));
         if (opening < 0.0) {
            ramp = host;
         }

         double blend = RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, (d - start) / Math.min(6.0, end - start))));
         return new RoadGeometry.V[]{host.add(ramp.sub(host).mul(blend)), host};
      } else {
         RoadGeometry.V center = median(aa, bb);
         RoadGeometry.V side = separation(aa, bb);
         double half = Math.min(1.1, Math.min(aa.halfWidth(), bb.halfWidth()) * 0.3);
         half *= RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, (d - start) / (end - start))));
         return new RoadGeometry.V[]{center.sub(side.mul(half)), center.add(side.mul(half))};
      }
   }

   private static void arrow(List<RoadJunction.Paint> out, RoadGeometry.V p, RoadGeometry.V f, RoadGeometry.V left) {
      stroke(out, p.sub(f.mul(1.4)), p.add(f.mul(0.2)), 0.22);
      out.add(new RoadJunction.Paint(List.of(p.add(f.mul(1.5)), p.add(f.mul(0.2)).add(left.mul(0.65)), p.add(f.mul(0.2)).sub(left.mul(0.65))), 15593186));
   }

   private static void stroke(List<RoadJunction.Paint> out, RoadGeometry.V a, RoadGeometry.V b, double width) {
      RoadGeometry.V d = b.sub(a);
      if (!(d.horizontalLength() < 1.0E-6)) {
         RoadGeometry.V side = d.horizontalUnit().left().mul(width / 2.0);
         out.add(new RoadJunction.Paint(List.of(a.add(side), a.sub(side), b.sub(side), b.add(side)), 15593186));
      }
   }

   private RoadJunction() {
   }

   public static record EdgeZone(double from, double to, int side) {
      public boolean contains(double d, int edge) {
         return edge == this.side && d >= this.from && d <= this.to;
      }
   }

   private static record Join(RoadJunction.Route a, RoadJunction.Route b, double nose, double available, boolean parallel) {
   }

   public static record Paint(List<RoadGeometry.V> points, int color) {
   }

   private static record Route(RoadGeometry.Mesh mesh, double origin, int sign) {
      RoadGeometry.Sample at(double d) {
         double distance = this.origin + (double)this.sign * d;
         if (this.mesh.closed()) {
            distance = (distance % this.mesh.length() + this.mesh.length()) % this.mesh.length();
         }

         if (!this.mesh.closed() && (distance < 0.0 || distance > this.mesh.length())) {
            RoadGeometry.Sample end = distance < 0.0 ? this.mesh.first() : this.mesh.last();
            double along = distance < 0.0 ? distance : distance - this.mesh.length();
            RoadGeometry.V forward = end.left().left().mul(-1.0);
            return new RoadGeometry.Sample(end.center().add(forward.mul(along)), end.left(), distance, end.halfWidth());
         } else {
            return RoadStructures.sample(this.mesh, distance);
         }
      }

      double available() {
         return this.mesh.closed() ? this.mesh.length() / 2.0 : (this.sign > 0 ? this.mesh.length() - this.origin : this.origin);
      }
   }

   public static record Zone(double from, double to) {
      public boolean contains(double d) {
         return d >= this.from && d <= this.to;
      }
   }
}
