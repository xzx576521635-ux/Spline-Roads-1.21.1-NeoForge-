package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RoadRailJoin {
   public static final double INSET = 0.34;
   private static final double JOIN_HEIGHT = 0.025;
   private static final int NONE = -1;
   private static final int SOUND = 4;
   private final Map<Long, List<RoadRailJoin.Face>> grid = new HashMap<>();
   private final Map<Long, List<RoadRailJoin.Face>> material = new HashMap<>();
   private final Map<Long, List<RoadRailJoin.Edge>> edges = new HashMap<>();
   private final RoadGeometry.Mesh current;
   private double buildingTolerance = 0.025;

   public static double inset(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, int side) {
      if (mesh.reference() == null) {
         return 0.34;
      } else {
         RoadGeometry.Mesh raw = LaneSections.reference(mesh);
         RoadGeometry.Sample original = RoadStructures.sample(raw, at.distance());
         boolean cutSide = false;

         for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
            if (!cut.temporary() && cut.rectangular() && cut.removed(at.distance()) > 0.999) {
               LanePoints.Lane lane = LanePoints.lane(raw, at.distance(), cut.lane());
               if (Math.signum(lane.position().sub(original.center()).dot(original.left())) == (double)side) {
                  cutSide = true;
                  break;
               }
            }
         }

         if (!cutSide) {
            return 0.34;
         } else {
            RoadProfile.Layout layout = RoadProfile.layout(mesh, at);
            double shoulder = side > 0 ? at.halfWidth() - layout.motorMax() : at.halfWidth() + layout.motorMin();
            double half = RoadProfile.highway(mesh.settings().style()) ? 0.31 : 0.21;
            return Math.max(half + 0.015, Math.min(0.34, shoulder - half - 0.015));
         }
      }
   }

   private static int profile(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, int side) {
      RoadGeometry.Settings settings = mesh.settings();
      RoadProfile.OuterRail outer = settings.options().outerRail();
      if (settings.structure() != RoadGeometry.Structure.TUNNEL && outer != RoadProfile.OuterRail.OFF) {
         boolean highway = RoadProfile.highway(settings.style());
         boolean raised = RoadStreetscape.raised(mesh, sample, side);
         boolean modern = RoadStructures.railModern(settings.style());
         if (!modern) {
            return settings.structure() == RoadGeometry.Structure.GROUND ? -1 : 5;
         } else if (outer != RoadProfile.OuterRail.ON && !highway && !settings.style().ramp() && !raised) {
            return -1;
         } else {
            return raised && !settings.style().ramp() && outer.sound(side) ? 4 : profile(highway, raised);
         }
      } else {
         return -1;
      }
   }

   private static int profile(boolean highway, boolean raised) {
      return (highway ? 2 : 0) + (raised ? 1 : 0);
   }

   private static boolean normalJoin(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host) {
      if (ramp == null) {
         return false;
      } else {
         LanePoints.Link link = ramp.settings().options().lanePoints().link();
         if (link == null) {
            return false;
         } else {
            List<LanePoints.Point> points = host.settings().options().lanePoints().points();
            return link.options().departure() == LanePoints.Departure.BRANCH && points.stream().anyMatch(p -> p.id().equals(link.from().point()))
               || link.options().arrival() == LanePoints.Arrival.FLOW && points.stream().anyMatch(p -> p.id().equals(link.to().point()));
         }
      }
   }

   public RoadRailJoin(List<RoadRailJoin.Neighbor> neighbors) {
      this(null, neighbors, false);
   }

   public RoadRailJoin(RoadGeometry.Mesh current, List<RoadRailJoin.Neighbor> neighbors) {
      this(current, neighbors, false);
   }

   public static RoadRailJoin paint(RoadGeometry.Mesh current, List<RoadRailJoin.Neighbor> neighbors) {
      return new RoadRailJoin(current, neighbors, true);
   }

   public static RoadRailJoin paintBoundary(RoadGeometry.Mesh current, List<RoadRailJoin.Neighbor> neighbors) {
      return new RoadRailJoin(current, neighbors, true, false);
   }

   private static RoadRailJoin.Range auxiliary(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host) {
      if (ramp != null && host != null) {
         LanePoints.Link link = ramp.settings().options().lanePoints().link();
         if (link == null) {
            double[] range = RoadJunction.auxiliaryRange(ramp, host);
            return range == null ? null : new RoadRailJoin.Range(range[0], range[1]);
         } else {
            for (boolean source : new boolean[]{true, false}) {
               if (source ? link.options().sourceExtra() : link.options().targetExtra()) {
                  LanePoints.Ref ref = source ? link.from() : link.to();

                  for (LanePoints.Point point : host.settings().options().lanePoints().points()) {
                     if (point.id().equals(ref.point())) {
                        LanePoints.Lane lane = LanePoints.lane(host, point);
                        double station = lane.station() + (source ? 0.0 : (double)lane.sign() * link.targetOffset());

                        try {
                           List<RoadGeometry.Sample> samples = LaneRampApproach.build(
                              host, point.lane(), station, source, ramp.settings(), link.options().transition()
                           );
                           double length = 0.0;

                           for (int i = 1; i < samples.size(); i++) {
                              length += samples.get(i).center().distance(samples.get(i - 1).center());
                           }

                           return source ? new RoadRailJoin.Range(0.0, length) : new RoadRailJoin.Range(ramp.length() - length, ramp.length());
                        } catch (IllegalArgumentException var17) {
                        }
                     }
                  }
               }
            }

            return null;
         }
      } else {
         return null;
      }
   }

   private static Map<RoadGeometry.Mesh, RoadRailJoin.Range> auxiliary(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> context) {
      IdentityHashMap<RoadGeometry.Mesh, RoadRailJoin.Range> result = new IdentityHashMap<>();
      if (ramp == null) {
         return result;
      } else {
         LanePoints.Link link = ramp.settings().options().lanePoints().link();
         if (link == null) {
            return result;
         } else {
            for (boolean source : new boolean[]{true, false}) {
               if (source ? link.options().sourceExtra() : link.options().targetExtra()) {
                  LanePoints.Ref ref = source ? link.from() : link.to();

                  for (RoadGeometry.Mesh host : context) {
                     for (LanePoints.Point point : host.settings().options().lanePoints().points()) {
                        if (point.id().equals(ref.point())) {
                           try {
                              LanePoints.Lane lane = LanePoints.lane(host, point);
                              double end = lane.station() + (double)lane.sign() * (source ? 0.0 : link.targetOffset());
                              double run = LaneRampApproach.length(host, link.options().transition());
                              Set<RoadGeometry.Mesh> used = Collections.newSetFromMap(new IdentityHashMap<>());
                              List<RoadGeometry.Sample> samples = LaneRampApproach.build(
                                 d -> {
                                    LaneRampApproach.Station at = auxiliaryStation(
                                       context, host, point.lane(), end + (double)lane.sign() * (source ? d : d - run)
                                    );
                                    used.add(at.mesh());
                                    return at;
                                 },
                                 source,
                                 ramp.settings(),
                                 link.options().transition(),
                                 host.settings().structure() == RoadGeometry.Structure.TUNNEL
                              );
                              double length = 0.0;

                              for (int i = 1; i < samples.size(); i++) {
                                 length += samples.get(i).center().distance(samples.get(i - 1).center());
                              }

                              RoadRailJoin.Range range = source
                                 ? new RoadRailJoin.Range(0.0, length)
                                 : new RoadRailJoin.Range(ramp.length() - length, ramp.length());

                              for (RoadGeometry.Mesh connected : used) {
                                 result.put(connected, range);
                              }
                           } catch (IllegalArgumentException var25) {
                           }
                        }
                     }
                  }
               }
            }

            return result;
         }
      }
   }

   public static double[] auxiliaryInterval(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, List<RoadGeometry.Mesh> context) {
      RoadRailJoin.Range range = auxiliary(ramp, context).get(host);
      if (range == null) {
         range = auxiliary(ramp, host);
      }

      return range == null ? null : new double[]{range.from(), range.to()};
   }

   private static LaneRampApproach.Station auxiliaryStation(List<RoadGeometry.Mesh> context, RoadGeometry.Mesh root, int slot, double station) {
      Set<RoadGeometry.Mesh> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      RoadGeometry.Mesh current = root;

      while (station < -1.0E-6 || station > current.length() + 1.0E-6) {
         RoadPlanningBudget.check();
         if (!seen.add(current)) {
            throw new IllegalArgumentException("辅助车道连续道路环回");
         }

         boolean before = station < 0.0;
         double edge = before ? 0.0 : current.length();
         double remaining = Math.abs(station - edge);
         RoadGeometry.Sample at = RoadStructures.sample(current, edge);
         LanePoints.Lane lane = LanePoints.lane(current, edge, slot);
         RoadGeometry.Mesh found = null;
         int nextSlot = -1;
         double nextStation = 0.0;

         for (RoadGeometry.Mesh candidate : context) {
            if (candidate != current
               && !seen.contains(candidate)
               && !candidate.closed()
               && candidate.settings().options().lanePoints().link() == null
               && LanePoints.supported(candidate.settings())) {
               for (boolean start : new boolean[]{true, false}) {
                  RoadGeometry.Sample port = start ? candidate.first() : candidate.last();
                  if (!(at.center().distance(port.center()) > 0.25) && LanePoints.sameSection(current, edge, candidate, port.distance())) {
                     for (int otherSlot : LaneAdditions.slots(candidate, port.distance())) {
                        LanePoints.Lane next = LanePoints.lane(candidate, port.distance(), otherSlot);
                        if (!(lane.direction().dot(next.direction()) < 0.995)
                           && !(lane.position().distance(next.position()) > 0.25)
                           && !(Math.abs(lane.width() - next.width()) > 0.25)) {
                           int travel = (before ? -1 : 1) * lane.sign();
                           if ((start ? 1 : -1) == travel * next.sign()) {
                              if (found != null) {
                                 throw new IllegalArgumentException("辅助车道接缝不唯一");
                              }

                              found = candidate;
                              nextSlot = otherSlot;
                              nextStation = port.distance() + (start ? remaining : -remaining);
                           }
                        }
                     }
                  }
               }
            }
         }

         if (found == null) {
            throw new IllegalArgumentException("辅助车道接缝不连续");
         }

         current = found;
         slot = nextSlot;
         station = nextStation;
      }

      return new LaneRampApproach.Station(current, slot, Math.max(0.0, Math.min(current.length(), station)));
   }

   private static double inset(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, int side, boolean paint) {
      return paint ? Math.abs((double)side * at.halfWidth() - RoadSurface.edgeOffset(mesh, at, side)) : inset(mesh, at, side);
   }

   private RoadRailJoin(RoadGeometry.Mesh current, List<RoadRailJoin.Neighbor> neighbors, boolean paint) {
      this(current, neighbors, paint, paint);
   }

   private RoadRailJoin(RoadGeometry.Mesh current, List<RoadRailJoin.Neighbor> neighbors, boolean paint, boolean paintInset) {
      this.current = current;
      ArrayList<RoadGeometry.Mesh> context = new ArrayList<>();
      if (current != null) {
         context.add(current);
      }

      for (RoadRailJoin.Neighbor n : neighbors) {
         context.add(n.mesh());
      }

      IdentityHashMap<RoadGeometry.Mesh, Map<RoadGeometry.Mesh, RoadRailJoin.Range>> auxiliaryCache = new IdentityHashMap<>();

      for (RoadRailJoin.Neighbor neighbor : neighbors) {
         RoadGeometry.Mesh mesh = neighbor.mesh();
         RoadRailJoin.Range ourAuxiliary = auxiliaryCache.computeIfAbsent(current, m -> auxiliary(m, context)).get(mesh);
         if (ourAuxiliary == null) {
            ourAuxiliary = auxiliary(current, mesh);
         }

         RoadRailJoin.Range theirAuxiliary = auxiliaryCache.computeIfAbsent(mesh, m -> auxiliary(m, context)).get(current);
         if (theirAuxiliary == null) {
            theirAuxiliary = auxiliary(mesh, current);
         }

         boolean legacy = ourAuxiliary != null && current.settings().options().lanePoints().link() == null
            || theirAuxiliary != null && mesh.settings().options().lanePoints().link() == null;
         this.buildingTolerance = !legacy
               && !RoadJunction.sharedRampFork(current, mesh)
               && !normalJoin(current, mesh)
               && (current == null || !normalJoin(mesh, current))
            ? 0.025
            : 0.12;

         for (int i = 1; i < mesh.samples().size(); i++) {
            RoadGeometry.Sample a = mesh.samples().get(i - 1);
            RoadGeometry.Sample b = mesh.samples().get(i);

            for (LaneDeck.Strip strip : LaneDeck.strips(mesh, a, b)) {
               double station = (a.distance() + b.distance()) / 2.0;
               RoadGeometry.V tangent = theirAuxiliary != null && station >= theirAuxiliary.from() && station <= theirAuxiliary.to()
                  ? null
                  : b.center().sub(a.center()).horizontalUnit();
               this.add(this.material, strip.al(), strip.ar(), strip.br(), true, tangent, ourAuxiliary);
               this.add(this.material, strip.al(), strip.br(), strip.bl(), true, tangent, ourAuxiliary);
               double highA = strip.al().distance(a.at(a.halfWidth(), 0.0)) < 1.0E-6 ? inset(mesh, a, 1, paintInset) : 0.34;
               double highB = strip.bl().distance(b.at(b.halfWidth(), 0.0)) < 1.0E-6 ? inset(mesh, b, 1, paintInset) : 0.34;
               double lowA = strip.ar().distance(a.at(-a.halfWidth(), 0.0)) < 1.0E-6 ? inset(mesh, a, -1, paintInset) : 0.34;
               double lowB = strip.br().distance(b.at(-b.halfWidth(), 0.0)) < 1.0E-6 ? inset(mesh, b, -1, paintInset) : 0.34;
               RoadGeometry.V al = strip.al().sub(a.left().mul(strip.highWall() ? highA : 0.0));
               RoadGeometry.V ar = strip.ar().add(a.left().mul(strip.lowWall() ? lowA : 0.0));
               RoadGeometry.V bl = strip.bl().sub(b.left().mul(strip.highWall() ? highB : 0.0));
               RoadGeometry.V br = strip.br().add(b.left().mul(strip.lowWall() ? lowB : 0.0));
               if (!(al.sub(ar).dot(a.left()) < 0.0) && !(bl.sub(br).dot(b.left()) < 0.0)) {
                  RoadGeometry.Sample sample = RoadStructures.sample(mesh, (a.distance() + b.distance()) / 2.0);
                  int high = profile(mesh, sample, 1);
                  int low = profile(mesh, sample, -1);
                  boolean boundaryOwner = neighbor.ownsBoundary() && (paint || high != -1 || low != -1);
                  this.add(al, ar, br, boundaryOwner);
                  this.add(al, br, bl, boundaryOwner);
                  if (strip.highWall() && strip.al().distance(a.at(a.halfWidth(), 0.0)) < 1.0E-6 && strip.bl().distance(b.at(b.halfWidth(), 0.0)) < 1.0E-6) {
                     this.edge(al, bl, neighbor.ownsBoundary(), high);
                  }

                  if (strip.lowWall() && strip.ar().distance(a.at(-a.halfWidth(), 0.0)) < 1.0E-6 && strip.br().distance(b.at(-b.halfWidth(), 0.0)) < 1.0E-6) {
                     this.edge(ar, br, neighbor.ownsBoundary(), low);
                  }
               }
            }
         }
      }
   }

   public static boolean sharedRail(RoadGeometry.Mesh neighbor, RoadGeometry.V a, RoadGeometry.V b) {
      for (RoadGeometry.V point : List.of(a, a.add(b).mul(0.5), b)) {
         RoadQueries.Projection q = RoadQueries.horizontal(neighbor, point);
         RoadGeometry.Sample at = q.sample();
         if (Math.abs(point.y() - at.at(q.lateral(), 0.0).y()) > 0.12) {
            return false;
         }

         int side = q.lateral() < 0.0 ? -1 : 1;
         double edge = at.halfWidth() - inset(neighbor, at, side);
         if (Math.abs(q.lateral()) < edge - 0.015 || LaneDeck.outerOpening(neighbor, at.distance(), side)) {
            return false;
         }
      }

      return true;
   }

   private void add(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c, boolean owner) {
      this.add(this.grid, a, b, c, owner);
   }

   private void add(Map<Long, List<RoadRailJoin.Face>> target, RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c, boolean owner) {
      this.add(target, a, b, c, owner, null, null);
   }

   private void add(
      Map<Long, List<RoadRailJoin.Face>> target,
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V c,
      boolean owner,
      RoadGeometry.V tangent,
      RoadRailJoin.Range auxiliary
   ) {
      double area = (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
      if (!(Math.abs(area) < 1.0E-10)) {
         if (area < 0.0) {
            RoadGeometry.V swap = b;
            b = c;
            c = swap;
         }

         RoadRailJoin.Face face = new RoadRailJoin.Face(a, b, c, owner, tangent, auxiliary, this.buildingTolerance);

         for (int x = cell(Math.min(a.x(), Math.min(b.x(), c.x())) - 1.0E-6); x <= cell(Math.max(a.x(), Math.max(b.x(), c.x())) + 1.0E-6); x++) {
            for (int z = cell(Math.min(a.z(), Math.min(b.z(), c.z())) - 1.0E-6); z <= cell(Math.max(a.z(), Math.max(b.z(), c.z())) + 1.0E-6); z++) {
               target.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(face);
            }
         }
      }
   }

   private void edge(RoadGeometry.V a, RoadGeometry.V b, boolean owner, int profile) {
      if (profile != -1 && !(a.sub(b).horizontalLength() < 1.0E-9)) {
         RoadRailJoin.Edge edge = new RoadRailJoin.Edge(a, b, owner, profile, this.buildingTolerance);

         for (int x = cell(Math.min(a.x(), b.x()) - 1.0E-5); x <= cell(Math.max(a.x(), b.x()) + 1.0E-5); x++) {
            for (int z = cell(Math.min(a.z(), b.z()) - 1.0E-5); z <= cell(Math.max(a.z(), b.z()) + 1.0E-5); z++) {
               this.edges.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(edge);
            }
         }
      }
   }

   private boolean touches(RoadRailJoin.Edge edge, RoadGeometry.V point) {
      RoadGeometry.V d = edge.b().sub(edge.a());
      double length = d.x() * d.x() + d.z() * d.z();
      double t = ((point.x() - edge.a().x()) * d.x() + (point.z() - edge.a().z()) * d.z()) / length;
      if (!(t < -1.0E-6) && !(t > 1.000001)) {
         RoadGeometry.V at = edge.a().add(d.mul(Math.max(0.0, Math.min(1.0, t))));
         return point.sub(at).horizontalLength() < 5.0E-5 && Math.abs(point.y() - at.y()) <= edge.heightTolerance();
      } else {
         return false;
      }
   }

   public RoadGeometry.V joint(RoadGeometry.V point, RoadGeometry.V direction) {
      return this.joint(point, direction, -2);
   }

   public RoadGeometry.V joint(RoadGeometry.V point, RoadGeometry.V direction, boolean highway, boolean raised) {
      return this.joint(point, direction, profile(highway, raised));
   }

   private RoadGeometry.V joint(RoadGeometry.V point, RoadGeometry.V direction, int expected) {
      RoadGeometry.V a = direction.horizontalUnit();
      RoadGeometry.V found = null;
      double smallest = 1.0;

      for (RoadRailJoin.Edge edge : this.edges.getOrDefault(key(cell(point.x()), cell(point.z())), List.of())) {
         if ((expected == -2 || edge.profile() == expected) && this.touches(edge, point)) {
            RoadGeometry.V b = edge.b().sub(edge.a()).horizontalUnit();
            double dot = a.dot(b);
            if (dot < 0.0) {
               b = b.mul(-1.0);
               dot = -dot;
            }

            if (!(dot < 0.25) && !(dot >= smallest - 1.0E-9)) {
               smallest = dot;
               found = a.left().add(b.left()).mul(1.0 / (1.0 + dot));
            }
         }
      }

      return found;
   }

   public boolean ownsPost(RoadGeometry.V point) {
      return this.ownsPost(point, -2);
   }

   public boolean ownsPost(RoadGeometry.V point, boolean highway, boolean raised) {
      return this.ownsPost(point, profile(highway, raised));
   }

   private boolean ownsPost(RoadGeometry.V point, int expected) {
      for (RoadRailJoin.Edge edge : this.edges.getOrDefault(key(cell(point.x()), cell(point.z())), List.of())) {
         if (edge.ownsBoundary() && edge.profile() >= 0 && edge.profile() <= 3 && (expected == -2 || edge.profile() == expected) && this.touches(edge, point)) {
            return false;
         }
      }

      return true;
   }

   public List<RoadRailJoin.Span> exposed(RoadGeometry.V a, RoadGeometry.V b) {
      return this.exposed(this.grid, a, b);
   }

   private List<RoadRailJoin.Span> exposed(Map<Long, List<RoadRailJoin.Face>> surface, RoadGeometry.V a, RoadGeometry.V b) {
      return this.exposed(surface, a, b, false);
   }

   private List<RoadRailJoin.Span> exposed(Map<Long, List<RoadRailJoin.Face>> surface, RoadGeometry.V a, RoadGeometry.V b, boolean parallelOnly) {
      if (a.sub(b).horizontalLength() < 1.0E-9) {
         return List.of();
      } else {
         LinkedHashSet<RoadRailJoin.Face> faces = new LinkedHashSet<>();

         for (int x = cell(Math.min(a.x(), b.x()) - 1.0E-6); x <= cell(Math.max(a.x(), b.x()) + 1.0E-6); x++) {
            for (int z = cell(Math.min(a.z(), b.z()) - 1.0E-6); z <= cell(Math.max(a.z(), b.z()) + 1.0E-6); z++) {
               faces.addAll(surface.getOrDefault(key(x, z), List.of()));
            }
         }

         ArrayList<RoadRailJoin.Range> cuts = new ArrayList<>();
         RoadGeometry.V tangent = b.sub(a).horizontalUnit();
         double station = this.current == null ? Double.NaN : RoadQueries.horizontal(this.current, a.add(b).mul(0.5)).sample().distance();

         for (RoadRailJoin.Face face : faces) {
            boolean auxiliary = face.auxiliary() != null && station >= face.auxiliary().from() && station <= face.auxiliary().to();
            if (!parallelOnly || auxiliary || face.tangent() == null || !(Math.abs(face.tangent().dot(tangent)) < 0.999999)) {
               RoadRailJoin.Range hit = face.intersection(a, b);
               if (hit != null) {
                  cuts.add(hit);
               }
            }
         }

         cuts.sort(Comparator.comparingDouble(RoadRailJoin.Range::from));
         ArrayList<RoadRailJoin.Span> result = new ArrayList<>();
         double at = 0.0;
         RoadGeometry.V d = b.sub(a);
         double epsilon = 1.0E-5 / d.horizontalLength();

         for (RoadRailJoin.Range cut : cuts) {
            if (cut.from() > at + epsilon) {
               result.add(new RoadRailJoin.Span(a.add(d.mul(at)), a.add(d.mul(cut.from()))));
            }

            at = Math.max(at, cut.to());
         }

         if (at < 1.0 - epsilon) {
            result.add(new RoadRailJoin.Span(a.add(d.mul(at)), b));
         }

         return List.copyOf(result);
      }
   }

   public List<RoadRailJoin.Span> exposed(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V outside) {
      return this.exposed(a, b, outside, 0.34);
   }

   public List<RoadRailJoin.Span> exposed(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V outside, double inset) {
      if (inset < 1.0E-9) {
         return this.exposed(this.material, a, b);
      } else {
         RoadGeometry.V mid = a.add(b).mul(0.5);
         RoadGeometry.V direction = outside.sub(mid);
         if (direction.horizontalLength() < 1.0E-8) {
            return this.exposed(a, b);
         } else {
            RoadGeometry.V shift = direction.horizontalUnit().mul(inset + 1.0E-5);
            List<RoadRailJoin.Span> inside = this.exposed(a, b);
            List<RoadRailJoin.Span> boundary = this.exposed(this.material, a.add(shift), b.add(shift), true);
            ArrayList<RoadRailJoin.Span> out = new ArrayList<>();
            RoadGeometry.V d = b.sub(a);
            double length = d.dot(d);

            for (RoadRailJoin.Span first : inside) {
               for (RoadRailJoin.Span second : boundary) {
                  double lo = Math.max(first.a().sub(a).dot(d) / length, second.a().sub(shift).sub(a).dot(d) / length);
                  double hi = Math.min(first.b().sub(a).dot(d) / length, second.b().sub(shift).sub(a).dot(d) / length);
                  if (hi - lo > 1.0E-7) {
                     out.add(new RoadRailJoin.Span(a.add(d.mul(lo)), a.add(d.mul(hi))));
                  }
               }
            }

            return List.copyOf(out);
         }
      }
   }

   public List<RoadRailJoin.Span> exposedMouth(RoadGeometry.V a, RoadGeometry.V b) {
      return this.exposed(this.material, a, b);
   }

   public static RoadRailJoin mouths(List<RoadGeometry.Mesh> linked) {
      ArrayList<RoadRailJoin.Neighbor> extensions = new ArrayList<>();

      for (RoadGeometry.Mesh mesh : linked) {
         for (boolean start : new boolean[]{true, false}) {
            int n = mesh.samples().size();
            if (n >= 2) {
               RoadGeometry.Sample at = start ? mesh.first() : mesh.last();
               RoadGeometry.Sample next = mesh.samples().get(start ? 1 : n - 2);
               RoadGeometry.V delta = at.center().sub(next.center());
               double run = delta.horizontalLength();
               if (!(run < 1.0E-8)) {
                  RoadProfile.Layout layout = RoadProfile.layout(mesh, at);
                  double half = at.halfWidth();
                  RoadGeometry.V center = at.center();
                  RoadGeometry.V extension = delta.mul(0.86 / run);
                  List<RoadGeometry.Sample> samples = List.of(
                     new RoadGeometry.Sample(center.sub(extension), at.left(), 0.0, half),
                     new RoadGeometry.Sample(center.add(extension), at.left(), 1.72, half)
                  );
                  extensions.add(new RoadRailJoin.Neighbor(RoadRibbon.mesh(samples, mesh.settings()), true));
               }
            }
         }
      }

      return new RoadRailJoin(extensions);
   }

   private static boolean clip(double[] t, double a, double b, double min) {
      double d = b - a;
      if (Math.abs(d) < 1.0E-12) {
         return a >= min;
      } else {
         double crossing = (min - a) / d;
         if (d > 0.0) {
            t[0] = Math.max(t[0], crossing);
         } else {
            t[1] = Math.min(t[1], crossing);
         }

         return t[0] <= t[1] && t[1] >= 0.0 && t[0] <= 1.0;
      }
   }

   private static int cell(double v) {
      return (int)Math.floor(v / 8.0);
   }

   private static long key(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   private static record Edge(RoadGeometry.V a, RoadGeometry.V b, boolean ownsBoundary, int profile, double heightTolerance) {
   }

   private static record Face(
      RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c, boolean ownsBoundary, RoadGeometry.V tangent, RoadRailJoin.Range auxiliary, double heightTolerance
   ) {
      double cross(RoadGeometry.V p, RoadGeometry.V q, RoadGeometry.V r) {
         return (q.x() - p.x()) * (r.z() - p.z()) - (q.z() - p.z()) * (r.x() - p.x());
      }

      double height(RoadGeometry.V p) {
         double det = this.cross(this.a, this.b, this.c);
         return this.a.y()
            + ((p.x() - this.a.x()) * (this.c.z() - this.a.z()) - (p.z() - this.a.z()) * (this.c.x() - this.a.x())) / det * (this.b.y() - this.a.y())
            + ((this.b.x() - this.a.x()) * (p.z() - this.a.z()) - (this.b.z() - this.a.z()) * (p.x() - this.a.x())) / det * (this.c.y() - this.a.y());
      }

      RoadRailJoin.Range intersection(RoadGeometry.V from, RoadGeometry.V to) {
         double[] t = new double[]{0.0, 1.0};
         RoadGeometry.V[] p = new RoadGeometry.V[]{this.a, this.b, this.c};

         for (int i = 0; i < 3; i++) {
            RoadGeometry.V x = p[i];
            RoadGeometry.V y = p[(i + 1) % 3];
            double length = y.sub(x).horizontalLength();
            double fa = this.cross(x, y, from) / length;
            double fb = this.cross(x, y, to) / length;
            if (!RoadRailJoin.clip(t, fa, fb, this.ownsBoundary ? -1.0E-7 : 1.0E-7)) {
               return null;
            }
         }

         double da = from.y() - this.height(from);
         double db = to.y() - this.height(to);
         if (RoadRailJoin.clip(t, da, db, -this.heightTolerance) && RoadRailJoin.clip(t, -da, -db, -this.heightTolerance)) {
            return t[1] - t[0] > 1.0E-7 ? new RoadRailJoin.Range(t[0], t[1]) : null;
         } else {
            return null;
         }
      }
   }

   public static record Neighbor(RoadGeometry.Mesh mesh, boolean ownsBoundary) {
   }

   private static record Range(double from, double to) {
   }

   public static record Span(RoadGeometry.V a, RoadGeometry.V b) {
   }
}
