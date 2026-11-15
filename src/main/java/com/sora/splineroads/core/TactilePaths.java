package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class TactilePaths {
   private static TactilePaths.End nearest(JunctionSpec s, List<RoadGeometry.Mesh> approaches, RoadGeometry.V p) {
      TactilePaths.End best = null;
      double d = Double.POSITIVE_INFINITY;

      for (int i = 0; i < approaches.size(); i++) {
         for (int side : new int[]{-1, 1}) {
            TactilePaths.End end = new TactilePaths.End(i, approaches.get(i).last(), side, s.arms().get(i).external().options().sidewalk());
            double distance = end.edge().distance(p);
            if (distance < d) {
               d = distance;
               best = end;
            }
         }
      }

      return best;
   }

   public static List<RoadStructures.Part> junction(JunctionSpec s, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary) {
      return junction(s, approaches, boundary, false);
   }

   public static List<RoadStructures.Part> junction(JunctionSpec s, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary, boolean insetBend) {
      return junction(s, approaches, boundary, insetBend, new ArrayList<>());
   }

   public static List<TactilePaths.Trim> trims(JunctionSpec s, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary) {
      ArrayList<TactilePaths.Trim> trims = new ArrayList<>();
      junction(s, approaches, boundary, true, trims);
      return List.copyOf(trims);
   }

   private static List<RoadStructures.Part> junction(
      JunctionSpec s, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary, boolean insetBend, List<TactilePaths.Trim> trims
   ) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      int n = boundary.size();
      if (n < 3) {
         return out;
      } else {
         boolean[] open = new boolean[n];
         double area = JunctionPaint.area(boundary);
         double sign = area >= 0.0 ? -1.0 : 1.0;
         int start = -1;

         for (int i = 0; i < n; i++) {
            RoadGeometry.V a = boundary.get(i);
            RoadGeometry.V b = boundary.get((i + 1) % n);
            RoadGeometry.V mid = a.add(b).mul(0.5);
            open[i] = a.distance(b) > 1.0E-8 && approaches.stream().noneMatch(m -> RoadQueries.contains(m, mid, 0.02, 0.1));
            if (!open[i]) {
               start = i;
            }
         }

         if (start < 0) {
            return out;
         } else {
            ArrayList<RoadGeometry.V> run = new ArrayList<>();

            for (int k = 1; k <= n; k++) {
               int ix = (start + k) % n;
               if (open[ix]) {
                  if (run.isEmpty()) {
                     run.add(boundary.get(ix));
                  }

                  run.add(boundary.get((ix + 1) % n));
               } else if (!run.isEmpty()) {
                  corner(out, s, approaches, run, sign, insetBend, trims, boundary);
                  run.clear();
               }
            }

            if (!run.isEmpty()) {
               corner(out, s, approaches, run, sign, insetBend, trims, boundary);
            }

            crossingBranches(out, s, approaches, trims);
            return List.copyOf(out);
         }
      }
   }

   private static void crossingBranches(List<RoadStructures.Part> out, JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<TactilePaths.Trim> trims) {
      List<RoadStructures.Part> trunk = List.copyOf(out);

      for (int arm = 0; arm < approaches.size(); arm++) {
         for (int side : new int[]{-1, 1}) {
            RoadGeometry.Mesh mesh = approaches.get(arm);
            TactilePaths.End end = new TactilePaths.End(arm, mesh.last(), side, spec.arms().get(arm).external().options().sidewalk());
            JunctionSpec.Arm road = spec.arms().get(arm);
            if (end.enabled() && road.crosswalk()) {
               double ring = spec.kind() == JunctionSpec.Kind.ROUNDABOUT
                  ? spec.islandRadius() + (double)spec.ringLanes() * spec.ringLaneWidth() - mesh.last().center().sub(spec.center()).horizontalLength()
                  : 0.0;
               RoadGeometry.Sample at = RoadStructures.sample(mesh, Math.max(1.0, mesh.length() - ring - road.crossingSetback() - road.crossingWidth() / 2.0));
               RoadGeometry.V inner = at.at((double)side * (at.halfWidth() + 0.45), 0.0);
               RoadGeometry.V outer = at.at((double)side * (at.halfWidth() + end.offset()), 0.0);
               if (!trunk.stream().anyMatch(p -> p.material() == RoadStructures.Material.TACTILE && p.pier() && p.a().sub(inner).horizontalLength() < 0.3)) {
                  RoadGeometry.V join = outer;
                  boolean cut = false;

                  for (TactilePaths.Trim trim : trims) {
                     if (trim.arm() == arm && trim.side() == side && outer.sub(trim.point()).dot(trim.forward()) > 0.0) {
                        cut = true;
                        break;
                     }
                  }

                  if (cut) {
                     double nearest = Double.POSITIVE_INFINITY;

                     for (RoadStructures.Part p : trunk) {
                        if (p.material() == RoadStructures.Material.TACTILE && p.height() < 0.018) {
                           RoadGeometry.V delta = p.b().sub(p.a());
                           double t = Math.max(0.0, Math.min(1.0, inner.sub(p.a()).dot(delta) / Math.max(1.0E-12, delta.dot(delta))));
                           RoadGeometry.V candidate = p.a().add(delta.mul(t)).sub(new RoadGeometry.V(0.0, 0.2, 0.0));
                           double distance = candidate.sub(inner).horizontalLength();
                           if (distance < nearest) {
                              nearest = distance;
                              join = candidate;
                           }
                        }
                     }
                  }

                  RoadGeometry.V toward = join.sub(inner);
                  RoadGeometry.V axis = Math.abs(toward.dot(at.left())) >= Math.abs(toward.dot(at.left().left())) ? at.left() : at.left().left();
                  axis = axis.mul(toward.dot(axis) < 0.0 ? -1.0 : 1.0);
                  RoadGeometry.V padEdge = inner.add(axis.mul(0.3));
                  RoadGeometry.V normal = toward.horizontalUnit().left().mul(-1.0);
                  RoadGeometry.V radialEdge = inner.add(toward.horizontalUnit().mul(0.3));
                  out.addAll(parts(List.of(join, radialEdge)));
                  out.addAll(parts(List.of(radialEdge, padEdge), normal, axis.left().mul(-1.0)));
                  warning(out, inner, at.left());
               }
            }
         }
      }
   }

   private static void corner(
      List<RoadStructures.Part> out,
      JunctionSpec spec,
      List<RoadGeometry.Mesh> approaches,
      List<RoadGeometry.V> edge,
      double sign,
      boolean insetBend,
      List<TactilePaths.Trim> trims,
      List<RoadGeometry.V> roadBoundary
   ) {
      edge = RoadSidewalks.closeCorner(spec, approaches, edge);
      if (edge.size() >= 2) {
         TactilePaths.End first = nearest(spec, approaches, edge.get(0));
         TactilePaths.End last = nearest(spec, approaches, edge.get(edge.size() - 1));
         if (first.enabled() || last.enabled()) {
            if (first.enabled() != last.enabled()) {
               crossingTerminal(out, spec, approaches, first.enabled() ? first : last, trims);
            } else if (spec.kind() == JunctionSpec.Kind.ROUNDABOUT) {
               roundCorner(out, spec, approaches, first, last, trims);
            } else if (!tangentCorner(out, spec, approaches, edge, sign, first, last, trims, roadBoundary)) {
               ArrayList<RoadGeometry.V> offset = new ArrayList<>();
               double length = 0.0;
               double station = 0.0;

               for (int i = 1; i < edge.size(); i++) {
                  length += edge.get(i).distance(edge.get(i - 1));
               }

               for (int i = 0; i < edge.size(); i++) {
                  RoadGeometry.V normal;
                  if (i == 0) {
                     normal = first.normal();
                  } else if (i == edge.size() - 1) {
                     normal = last.normal();
                  } else {
                     RoadGeometry.V a = edge.get(i).sub(edge.get(i - 1)).horizontalUnit().left().mul(sign);
                     RoadGeometry.V b = edge.get(i + 1).sub(edge.get(i)).horizontalUnit().left().mul(sign);
                     double den = 1.0 + a.dot(b);
                     normal = den > 0.15 ? a.add(b).mul(1.0 / den) : b;
                  }

                  if (i > 0) {
                     station += edge.get(i).distance(edge.get(i - 1));
                  }

                  double t = RoadGeometry.Settings.smooth(station / Math.max(1.0E-9, length));
                  double distance = first.offset() * (1.0 - t) + last.offset() * t;
                  offset.add(edge.get(i).add(normal.mul(distance)));
               }

               RoadGeometry.V incoming = first.mouth().left().left().mul(-1.0);
               RoadGeometry.V outgoing = last.mouth().left().left().mul(-1.0);
               double lead = (double)(Math.max(first.config().width(), last.config().width()) * 4 + 8);
               ArrayList<RoadGeometry.V> complete = new ArrayList<>();
               complete.add(offset.get(0).sub(incoming.mul(lead)));
               complete.addAll(offset);
               complete.add(offset.get(offset.size() - 1).sub(outgoing.mul(lead)));
               List<RoadGeometry.V> joined = rounded(removeLoops(complete), 0.7);
               if (joined.size() >= 4) {
                  RoadGeometry.V begin = joined.get(1);
                  RoadGeometry.V finish = joined.get(joined.size() - 2);
                  trims.add(new TactilePaths.Trim(first.arm(), first.side(), begin, incoming));
                  trims.add(new TactilePaths.Trim(last.arm(), last.side(), finish, outgoing));
                  List<RoadGeometry.V> route = joined.subList(1, joined.size() - 1);
                  if (route.size() >= 2) {
                     if (first.enabled() != last.enabled()) {
                        int mid = Math.max(1, route.size() / 2);
                        route = first.enabled() ? route.subList(0, mid + 1) : route.subList(mid, route.size());
                        RoadGeometry.V end = first.enabled() ? route.get(route.size() - 1) : route.get(0);
                        warning(out, end);
                     }

                     out.addAll(parts(route));
                  }
               }
            }
         }
      }
   }

   private static void crossingTerminal(
      List<RoadStructures.Part> out, JunctionSpec spec, List<RoadGeometry.Mesh> approaches, TactilePaths.End end, List<TactilePaths.Trim> trims
   ) {
      JunctionSpec.Arm arm = spec.arms().get(end.arm());
      RoadGeometry.Mesh mesh = approaches.get(end.arm());
      double ring = spec.kind() == JunctionSpec.Kind.ROUNDABOUT
         ? spec.islandRadius() + (double)spec.ringLanes() * spec.ringLaneWidth() - mesh.last().center().sub(spec.center()).horizontalLength()
         : 0.0;
      double d = mesh.length() - ring - arm.crossingSetback() - (arm.crosswalk() ? arm.crossingWidth() / 2.0 : 1.0);
      RoadGeometry.Sample at = RoadStructures.sample(mesh, Math.max(1.0, d));
      RoadGeometry.V outer = at.at((double)end.side() * (at.halfWidth() + end.offset()), 0.0);
      RoadGeometry.V inner = at.at((double)end.side() * (at.halfWidth() + 0.45), 0.0);
      RoadGeometry.V forward = at.left().left().mul(-1.0);
      RoadGeometry.V padEdge = inner.add(outer.sub(inner).horizontalUnit().mul(0.3));
      List<RoadGeometry.V> turn = rounded(List.of(outer.sub(forward.mul(2.0)), outer, padEdge), 0.75);
      RoadGeometry.V begin = turn.get(1);
      trims.add(new TactilePaths.Trim(end.arm(), end.side(), begin, forward));
      out.addAll(parts(turn.subList(1, turn.size()), at.left(), at.left().left().mul((double)(-end.side()))));
      warning(out, inner, at.left());
   }

   private static TactilePaths.Anchor anchor(RoadGeometry.Mesh mesh, TactilePaths.End end, RoadGeometry.V desired) {
      RoadGeometry.V forward = end.mouth().left().left().mul(-1.0);
      RoadGeometry.V outer = end.mouth().at((double)end.side() * (end.mouth().halfWidth() + end.offset()), 0.0);
      double station = Math.max(0.5, Math.min(mesh.length(), mesh.length() + desired.sub(outer).dot(forward)));
      RoadGeometry.Sample at = RoadStructures.sample(mesh, station);
      return new TactilePaths.Anchor(at.at((double)end.side() * (at.halfWidth() + end.offset()), 0.0), at);
   }

   private static boolean tangentCorner(
      List<RoadStructures.Part> out,
      JunctionSpec spec,
      List<RoadGeometry.Mesh> approaches,
      List<RoadGeometry.V> edge,
      double sign,
      TactilePaths.End first,
      TactilePaths.End last,
      List<TactilePaths.Trim> trims,
      List<RoadGeometry.V> roadBoundary
   ) {
      RoadGeometry.V u = first.mouth().left().left().mul(-1.0);
      RoadGeometry.V v = last.mouth().left().left();
      double den = cross(u, v);
      double dot = Math.max(-1.0, Math.min(1.0, u.dot(v)));
      double angle = Math.atan2(den, dot);
      double turn = Math.abs(angle);
      if (turn < Math.toRadians(3.0) || turn > Math.toRadians(175.0) || Math.abs(den) < 1.0E-8) {
         return false;
      } else if (angle * first.normal().dot(u.left()) < 0.0) {
         return false;
      } else {
         ArrayList<RoadStructures.Part> paving = new ArrayList<>(RoadSidewalks.cornerPaving(spec, approaches, edge, sign));

         for (TactilePaths.End e : List.of(first, last)) {
            paving.addAll(
               RoadSidewalks.parts(approaches.get(e.arm()), e.config()).stream().filter(partx -> partx.material().name().startsWith("WALK_")).toList()
            );
         }

         double common = Math.min(first.offset(), last.offset());
         double[] var23 = new double[]{0.0};
         int var24 = var23.length;
         int var25 = 0;

         TactilePaths.Anchor a;
         TactilePaths.Anchor b;
         List<RoadStructures.Part> pieces;
         label98:
         while (true) {
            if (var25 >= var24) {
               return false;
            }

            double inset = var23[var25];
            double offset = common - inset;
            if (!(offset < 0.65)) {
               RoadGeometry.V p = first.mouth().at((double)first.side() * (first.mouth().halfWidth() + first.offset()), 0.0);
               RoadGeometry.V q = last.mouth().at((double)last.side() * (last.mouth().halfWidth() + last.offset()), 0.0);
               RoadGeometry.V hit = p.add(u.mul(cross(q.sub(p), v) / den));

               for (double radius = Math.max(0.85, spec.cornerRadius() - offset); radius >= 0.29; radius /= 1.5) {
                  double distance = radius * Math.tan(turn / 2.0);
                  RoadGeometry.V begin = hit.sub(u.mul(distance));
                  RoadGeometry.V end = hit.add(v.mul(distance));
                  double leadA = 0.0;
                  double leadB = 0.0;
                  a = anchor(approaches.get(first.arm()), first, begin.sub(u.mul(leadA)));
                  b = anchor(approaches.get(last.arm()), last, end.add(v.mul(leadB)));
                  if (!(begin.sub(a.point()).dot(u) < -0.05) && !(b.point().sub(end).dot(v) < -0.05)) {
                     RoadGeometry.V center = begin.add(u.left().mul(Math.signum(angle) * radius));
                     double start = Math.atan2(begin.z() - center.z(), begin.x() - center.x());
                     ArrayList<RoadGeometry.V> route = new ArrayList<>();
                     route.add(a.point());
                     blend(route, a.point(), begin, u);
                     int count = Math.max(12, (int)Math.ceil(radius * turn / 0.12));

                     for (int k = 1; k < count; k++) {
                        double fraction = (double)k / (double)count;
                        double theta = start + angle * fraction;
                        route.add(
                           new RoadGeometry.V(
                              center.x() + radius * Math.cos(theta), begin.y() + (end.y() - begin.y()) * fraction, center.z() + radius * Math.sin(theta)
                           )
                        );
                     }

                     route.add(end);
                     blend(route, end, b.point(), v);
                     pieces = parts(route, a.sample().left(), b.sample().left().mul(-1.0));
                     if (supported(pieces, paving)) {
                        break label98;
                     }

                     boolean safe = true;

                     for (RoadStructures.Part part : pieces) {
                        if (part.height() < 0.018) {
                           for (RoadGeometry.V vertex : part.base()) {
                              if (JunctionPaint.inside(roadBoundary, vertex) || approaches.stream().anyMatch(m -> RoadQueries.contains(m, vertex, 0.02, 0.5))) {
                                 safe = false;
                                 break;
                              }
                           }
                        }
                     }

                     if (safe) {
                        Iterator var59 = pieces.iterator();

                        while (true) {
                           if (!var59.hasNext()) {
                              break label98;
                           }

                           RoadStructures.Part partx = (RoadStructures.Part)var59.next();
                           if (partx.height() < 0.018) {
                              RoadSidewalks.Config config = first.config();
                              RoadGeometry.V down = new RoadGeometry.V(0.0, -0.5, 0.0);
                              out.add(
                                 new RoadStructures.Part(
                                    partx.a().add(down),
                                    partx.b().add(down),
                                    partx.width() + 0.6,
                                    0.5,
                                    false,
                                    RoadSidewalks.Finish.of(config.material()).material(),
                                    partx.frameA().mul(2.0),
                                    partx.frameB().mul(2.0)
                                 )
                              );
                           }
                        }
                     }
                  }
               }
            }

            var25++;
         }

         trims.add(new TactilePaths.Trim(first.arm(), first.side(), a.point(), a.sample().left().left().mul(-1.0)));
         trims.add(new TactilePaths.Trim(last.arm(), last.side(), b.point(), b.sample().left().left().mul(-1.0)));
         out.addAll(pieces);
         return true;
      }
   }

   private static void blend(List<RoadGeometry.V> route, RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V direction) {
      if (!(a.distance(b) < 0.005)) {
         double handle = Math.max(0.0, b.sub(a).dot(direction)) / 3.0;
         RoadGeometry.V c = a.add(direction.mul(handle));
         RoadGeometry.V d = b.sub(direction.mul(handle));
         int steps = Math.max(1, (int)Math.ceil(a.distance(b) / 0.3));

         for (int k = 1; k <= steps; k++) {
            double t = (double)k / (double)steps;
            double w = 1.0 - t;
            route.add(a.mul(w * w * w).add(c.mul(3.0 * w * w * t)).add(d.mul(3.0 * w * t * t)).add(b.mul(t * t * t)));
         }
      }
   }

   private static boolean supported(List<RoadStructures.Part> pieces, List<RoadStructures.Part> paving) {
      for (RoadStructures.Part p : pieces) {
         if (p.height() < 0.018) {
            List<RoadGeometry.V> base = p.base();

            for (int i = 0; i < base.size(); i++) {
               RoadGeometry.V a = base.get(i);
               RoadGeometry.V b = base.get((i + 1) % base.size());

               for (double t : new double[]{0.0, 0.5}) {
                  RoadGeometry.V at = a.mul(1.0 - t).add(b.mul(t));
                  if (paving.stream().noneMatch(w -> JunctionPaint.inside(w.base(), at))) {
                     return false;
                  }
               }
            }
         }
      }

      return true;
   }

   private static RoadGeometry.V circleJoin(JunctionSpec spec, RoadGeometry.Mesh mesh, TactilePaths.End end) {
      double radius = spec.islandRadius() + (double)spec.ringLanes() * spec.ringLaneWidth() + end.offset();

      for (int i = 1; i < mesh.samples().size(); i++) {
         RoadGeometry.Sample a = mesh.samples().get(i - 1);
         RoadGeometry.Sample b = mesh.samples().get(i);
         RoadGeometry.V p = a.at((double)end.side() * (a.halfWidth() + end.offset()), 0.0);
         RoadGeometry.V q = b.at((double)end.side() * (b.halfWidth() + end.offset()), 0.0);
         if (p.sub(spec.center()).horizontalLength() >= radius && q.sub(spec.center()).horizontalLength() <= radius) {
            double lo = 0.0;
            double hi = 1.0;

            for (int k = 0; k < 40; k++) {
               double t = (lo + hi) / 2.0;
               RoadGeometry.V at = p.add(q.sub(p).mul(t));
               if (at.sub(spec.center()).horizontalLength() > radius) {
                  lo = t;
               } else {
                  hi = t;
               }
            }

            return p.add(q.sub(p).mul((lo + hi) / 2.0));
         }
      }

      return end.mouth().at((double)end.side() * (end.mouth().halfWidth() + end.offset()), 0.0);
   }

   private static void roundCorner(
      List<RoadStructures.Part> out,
      JunctionSpec spec,
      List<RoadGeometry.Mesh> approaches,
      TactilePaths.End first,
      TactilePaths.End last,
      List<TactilePaths.Trim> trims
   ) {
      RoadGeometry.V p = circleJoin(spec, approaches.get(first.arm()), first);
      RoadGeometry.V q = circleJoin(spec, approaches.get(last.arm()), last);
      double a = Math.atan2(p.z() - spec.center().z(), p.x() - spec.center().x());
      double b = Math.atan2(q.z() - spec.center().z(), q.x() - spec.center().x());
      double turn = (b - a + (Math.PI * 2)) % (Math.PI * 2);
      if (!(turn > Math.PI * 9.0 / 5.0)) {
         double r0 = p.sub(spec.center()).horizontalLength();
         double r1 = q.sub(spec.center()).horizontalLength();
         int count = Math.max(2, (int)Math.ceil(Math.max(r0, r1) * turn / 0.3));
         ArrayList<RoadGeometry.V> route = new ArrayList<>();
         route.add(p);

         for (int k = 1; k < count; k++) {
            double t = (double)k / (double)count;
            double r = r0 + (r1 - r0) * RoadGeometry.Settings.smooth(t);
            double angle = a + turn * t;
            route.add(new RoadGeometry.V(spec.center().x() + r * Math.cos(angle), p.y() + (q.y() - p.y()) * t, spec.center().z() + r * Math.sin(angle)));
         }

         route.add(q);
         if (first.enabled()) {
            trims.add(new TactilePaths.Trim(first.arm(), first.side(), p, first.mouth().left().left().mul(-1.0)));
         }

         if (last.enabled()) {
            trims.add(new TactilePaths.Trim(last.arm(), last.side(), q, last.mouth().left().left().mul(-1.0)));
         }

         if (first.enabled() != last.enabled()) {
            int middle = Math.max(1, route.size() / 2);
            route = new ArrayList<>(first.enabled() ? route.subList(0, middle + 1) : route.subList(middle, route.size()));
            warning(out, first.enabled() ? route.get(route.size() - 1) : route.get(0));
         }

         out.addAll(parts(route));
      }
   }

   public static List<RoadGeometry.V> insetBend(List<RoadGeometry.V> fallback, RoadGeometry.V incoming, RoadGeometry.V outgoing, double radius) {
      if (fallback.size() < 2) {
         return fallback;
      } else {
         RoadGeometry.V p = fallback.get(0);
         RoadGeometry.V q = fallback.get(fallback.size() - 1);
         RoadGeometry.V u = incoming.horizontalUnit();
         RoadGeometry.V v = outgoing.horizontalUnit();
         double dot = Math.max(-1.0, Math.min(1.0, u.dot(v)));
         double turn = Math.acos(dot);
         double den = cross(u, v);
         if (!(turn < Math.toRadians(65.0)) && !(turn > Math.toRadians(165.0)) && !(Math.abs(den) < 1.0E-8)) {
            double before = cross(q.sub(p), v) / den;
            double after = -cross(q.sub(p), u) / den;
            if (!(before < 1.0) && !(after < 1.0)) {
               double tangent = Math.min(radius * Math.tan(turn / 2.0), Math.min(before, after) * 0.8);
               double actual = tangent / Math.tan(turn / 2.0);
               double sign = Math.signum(den);
               RoadGeometry.V tip = p.add(u.mul(before));
               RoadGeometry.V begin = tip.sub(u.mul(tangent));
               RoadGeometry.V end = tip.add(v.mul(tangent));
               RoadGeometry.V center = begin.add(u.left().mul(sign * actual));
               double angle = Math.atan2(begin.z() - center.z(), begin.x() - center.x());
               ArrayList<RoadGeometry.V> out = new ArrayList<>();
               out.add(p);
               out.add(begin);
               int count = Math.max(12, (int)Math.ceil(actual * turn / 0.15));

               for (int k = 1; k <= count; k++) {
                  double t = (double)k / (double)count;
                  double a = angle + sign * turn * t;
                  out.add(new RoadGeometry.V(center.x() + actual * Math.cos(a), begin.y() + (end.y() - begin.y()) * t, center.z() + actual * Math.sin(a)));
               }

               out.add(q);
               return List.copyOf(out);
            } else {
               return fallback;
            }
         } else {
            return fallback;
         }
      }
   }

   public static List<RoadStructures.Part> trimApproach(List<RoadStructures.Part> parts, RoadGeometry.Mesh mesh, int arm, List<TactilePaths.Trim> trims) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();

      for (RoadStructures.Part original : parts) {
         RoadStructures.Part p = original;
         if (original.material() == RoadStructures.Material.TACTILE) {
            for (TactilePaths.Trim trim : trims) {
               if (trim.arm() == arm) {
                  RoadQueries.Projection projection = RoadQueries.horizontal(mesh, p.a().add(p.b()).mul(0.5));
                  if (!(projection.lateral() * (double)trim.side() <= 0.0)) {
                     double a = p.a().sub(trim.point()).dot(trim.forward());
                     double b = p.b().sub(trim.point()).dot(trim.forward());
                     if (a >= -1.0E-7 && b >= -1.0E-7) {
                        p = null;
                        break;
                     }

                     if (a > 0.0 || b > 0.0) {
                        double t = a / (a - b);
                        RoadGeometry.V cut = p.a().add(p.b().sub(p.a()).mul(t));
                        RoadGeometry.V fa = p.frameA();
                        RoadGeometry.V fb = p.frameB();
                        RoadGeometry.V frame = fa != null && fb != null ? fa.add(fb.sub(fa).mul(t)) : null;
                        p = a > 0.0
                           ? new RoadStructures.Part(cut, p.b(), p.width(), p.height(), p.pier(), p.material(), frame, fb, p.model())
                           : new RoadStructures.Part(p.a(), cut, p.width(), p.height(), p.pier(), p.material(), fa, frame, p.model());
                     }
                  }
               }
            }
         }

         if (p != null) {
            out.add(p);
         }
      }

      return List.copyOf(out);
   }

   public static List<RoadGeometry.V> removeLoops(List<RoadGeometry.V> source) {
      ArrayList<RoadGeometry.V> p = new ArrayList<>(source);
      int budget = p.size() * 2;
      boolean changed = true;

      label65:
      while (changed && budget-- > 0) {
         changed = false;

         for (int i = 0; i < p.size() - 3; i++) {
            for (int j = i + 2; j < p.size() - 1; j++) {
               RoadGeometry.V a = p.get(i);
               RoadGeometry.V u = p.get(i + 1).sub(a);
               RoadGeometry.V b = p.get(j);
               RoadGeometry.V v = p.get(j + 1).sub(b);
               double den = cross(u, v);
               if (!(Math.abs(den) < 1.0E-9)) {
                  double t = cross(b.sub(a), v) / den;
                  double q = cross(b.sub(a), u) / den;
                  if (t >= 0.0 && t <= 1.0 && q >= 0.0 && q <= 1.0) {
                     RoadGeometry.V hit = a.add(u.mul(t));

                     for (int k = j; k > i; k--) {
                        p.remove(k);
                     }

                     p.add(i + 1, hit);
                     changed = true;
                     continue label65;
                  }
               }
            }
         }
      }

      ArrayList<RoadGeometry.V> clean = new ArrayList<>();

      for (RoadGeometry.V v : p) {
         if (clean.isEmpty() || v.distance(clean.get(clean.size() - 1)) > 0.005) {
            clean.add(v);
         }
      }

      return List.copyOf(clean);
   }

   private static double cross(RoadGeometry.V a, RoadGeometry.V b) {
      return a.x() * b.z() - a.z() * b.x();
   }

   public static List<RoadGeometry.V> rounded(List<RoadGeometry.V> p, double radius) {
      if (p.size() < 3) {
         return p;
      } else {
         ArrayList<RoadGeometry.V> out = new ArrayList<>();
         out.add(p.get(0));

         for (int i = 1; i < p.size() - 1; i++) {
            RoadGeometry.V a = p.get(i - 1);
            RoadGeometry.V b = p.get(i);
            RoadGeometry.V c = p.get(i + 1);
            RoadGeometry.V incoming = b.sub(a).horizontalUnit();
            RoadGeometry.V outgoing = c.sub(b).horizontalUnit();
            if (incoming.dot(outgoing) > 0.8) {
               out.add(b);
            } else {
               int before = i - 1;
               int after = i + 1;
               double back = 0.0;

               double forward;
               for (forward = 0.0; before > 0 && back + p.get(before).distance(p.get(before + 1)) < radius; before--) {
                  back += p.get(before).distance(p.get(before + 1));
               }

               while (after < p.size() - 1 && forward + p.get(after).distance(p.get(after - 1)) < radius) {
                  forward += p.get(after).distance(p.get(after - 1));
                  after++;
               }

               RoadGeometry.V ba = p.get(before);
               RoadGeometry.V bb = p.get(before + 1);
               RoadGeometry.V fa = p.get(after - 1);
               RoadGeometry.V fb = p.get(after);
               double db = Math.min(radius - back, ba.distance(bb));
               double df = Math.min(radius - forward, fa.distance(fb));
               RoadGeometry.V begin = bb.add(ba.sub(bb).horizontalUnit().mul(db));
               RoadGeometry.V end = fa.add(fb.sub(fa).horizontalUnit().mul(df));

               while (out.size() > 1 && out.get(out.size() - 1).distance(b) < radius + 0.01) {
                  out.remove(out.size() - 1);
               }

               out.add(begin);
               int steps = Math.max(12, (int)Math.ceil((begin.distance(b) + b.distance(end)) / 0.15));

               for (int k = 1; k <= steps; k++) {
                  double t = (double)k / (double)steps;
                  out.add(begin.mul((1.0 - t) * (1.0 - t)).add(b.mul(2.0 * t * (1.0 - t))).add(end.mul(t * t)));
               }

               i = after - 1;
            }
         }

         out.add(p.get(p.size() - 1));
         return List.copyOf(out);
      }
   }

   public static List<RoadStructures.Part> parts(List<RoadGeometry.V> route) {
      return parts(route, null, null);
   }

   private static List<RoadStructures.Part> parts(List<RoadGeometry.V> route, RoadGeometry.V firstNormal, RoadGeometry.V lastNormal) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      route = removeLoops(route);
      if (route.size() < 2) {
         return out;
      } else {
         ArrayList<RoadGeometry.V> normals = new ArrayList<>();

         for (int i = 0; i < route.size(); i++) {
            RoadGeometry.V a = route.get(i == 0 ? 0 : i - 1);
            RoadGeometry.V b = route.get(i == route.size() - 1 ? i : i + 1);
            RoadGeometry.V tangent = b.sub(a);
            if (tangent.horizontalLength() < 1.0E-8) {
               tangent = i > 0 ? route.get(i).sub(route.get(i - 1)) : route.get(1).sub(route.get(0));
            }

            RoadGeometry.V normal = tangent.horizontalUnit().left();
            if (i > 0 && i < route.size() - 1) {
               RoadGeometry.V incoming = route.get(i).sub(a).horizontalUnit().left();
               RoadGeometry.V outgoing = b.sub(route.get(i)).horizontalUnit().left();
               double den = 1.0 + incoming.dot(outgoing);
               if (den > 0.2) {
                  normal = incoming.add(outgoing).mul(1.0 / den);
               }
            }

            if (i == 0 && firstNormal != null) {
               normal = firstNormal;
            }

            if (i == route.size() - 1 && lastNormal != null) {
               normal = lastNormal;
            }

            normals.add(normal);
         }

         for (int i = 1; i < route.size(); i++) {
            RoadGeometry.V ax = route.get(i - 1);
            RoadGeometry.V bx = route.get(i);
            RoadGeometry.V na = normals.get(i - 1);
            RoadGeometry.V nb = normals.get(i);
            if (!(ax.distance(bx) < 0.005)) {
               out.add(
                  new RoadStructures.Part(
                        ax.add(new RoadGeometry.V(0.0, 0.2, 0.0)),
                        bx.add(new RoadGeometry.V(0.0, 0.2, 0.0)),
                        0.6,
                        0.015,
                        false,
                        RoadStructures.Material.TACTILE
                     )
                     .frames(na.mul(0.3), nb.mul(0.3))
               );

               for (double r : new double[]{-0.18, 0.0, 0.18}) {
                  out.add(
                     new RoadStructures.Part(
                           ax.add(na.mul(r)).add(new RoadGeometry.V(0.0, 0.215, 0.0)),
                           bx.add(nb.mul(r)).add(new RoadGeometry.V(0.0, 0.215, 0.0)),
                           0.06,
                           0.02,
                           false,
                           RoadStructures.Material.TACTILE
                        )
                        .frames(na.mul(0.03), nb.mul(0.03))
                  );
               }
            }
         }

         return List.copyOf(out);
      }
   }

   private static void warning(List<RoadStructures.Part> out, RoadGeometry.V p) {
      warning(out, p, new RoadGeometry.V(1.0, 0.0, 0.0));
   }

   private static void warning(List<RoadStructures.Part> out, RoadGeometry.V p, RoadGeometry.V axis) {
      RoadGeometry.V at = p.add(new RoadGeometry.V(0.0, 0.2, 0.0));
      RoadGeometry.V normal = axis.left();
      out.add(
         new RoadStructures.Part(at.sub(axis.mul(0.3)), at.add(axis.mul(0.3)), 0.6, 0.015, false, RoadStructures.Material.TACTILE)
            .frames(normal.mul(0.3), normal.mul(0.3))
      );

      for (double x : new double[]{-0.18, 0.0, 0.18}) {
         for (double z : new double[]{-0.18, 0.0, 0.18}) {
            RoadGeometry.V q = p.add(axis.mul(x)).add(normal.mul(z)).add(new RoadGeometry.V(0.0, 0.215, 0.0));
            out.add(new RoadStructures.Part(q, q, 0.08, 0.02, true, RoadStructures.Material.TACTILE));
         }
      }
   }

   private TactilePaths() {
   }

   private static record Anchor(RoadGeometry.V point, RoadGeometry.Sample sample) {
   }

   private static record End(int arm, RoadGeometry.Sample mouth, int side, RoadSidewalks.Config config) {
      RoadGeometry.V edge() {
         return this.mouth.at((double)this.side * this.mouth.halfWidth(), 0.0);
      }

      RoadGeometry.V normal() {
         return this.mouth.left().mul((double)this.side);
      }

      double offset() {
         return RoadStreetscape.tactileOffset((double)this.config.width());
      }

      boolean enabled() {
         return this.config.enabled()
            && this.config.smooth()
            && this.config.tactile()
            && this.config.width() >= 2
            && (
               this.config.side() == RoadSidewalks.Side.BOTH
                  || this.config.side() == RoadSidewalks.Side.LEFT && this.side < 0
                  || this.config.side() == RoadSidewalks.Side.RIGHT && this.side > 0
            );
      }
   }

   public static record Trim(int arm, int side, RoadGeometry.V point, RoadGeometry.V forward) {
   }
}
