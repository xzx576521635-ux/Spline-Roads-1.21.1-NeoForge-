package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;

public final class JunctionPlanner {
   private static final BoundedLoadingCache<JunctionPlanner.PlanKey, JunctionPlanner.Plan> CACHE = new BoundedLoadingCache<>(
      48, 33554432L, JunctionPlanner::planBytes
   );

   private static long planBytes(JunctionPlanner.Plan p) {
      long bytes = 4096L + (long)p.boundary().size() * 64L + (long)p.sidewalks().size() * 128L + (long)p.heads().size() * 256L;

      for (JunctionPlanner.Piece piece : p.pieces()) {
         bytes += (long)piece.mesh().samples().size() * 256L + (long)piece.structures().size() * 512L;

         for (RoadSurface.Face face : piece.paint()) {
            bytes += 128L + (long)face.points().size() * 64L;
         }
      }

      for (JunctionPlanner.Movement movement : p.movements()) {
         bytes += 128L + (long)movement.path().size() * 64L;
      }

      return bytes;
   }

   public static JunctionPlanner.Plan plan(JunctionSpec s) {
      return plan(s, 55);
   }

   private static JunctionPlanner.Plan plan(JunctionSpec s, int version) {
      return CACHE.get(new JunctionPlanner.PlanKey(s, version), k -> build(k.spec(), k.version()));
   }

   public static RoadGeometry.Settings settings(double width, double thick) {
      return new RoadGeometry.Settings(RoadGeometry.Mode.STRAIGHT, RoadGeometry.Style.UNMARKED, width, thick, 0.35, 90.0)
         .structure(RoadGeometry.Structure.GROUND);
   }

   private static RoadGeometry.V radial(JunctionSpec s, int i) {
      return (new JunctionSides(s, true)).outward.get(i);
   }

   public static boolean separatedEntrances(JunctionSpec s) {
      return new JunctionSides(s, true).separated();
   }

   private static double cross(RoadGeometry.V a, RoadGeometry.V b) {
      return a.x() * b.z() - a.z() * b.x();
   }

   private static RoadGeometry.V bezier(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c, RoadGeometry.V d, double t) {
      double u = 1.0 - t;
      return a.mul(u * u * u).add(b.mul(3.0 * u * u * t)).add(c.mul(3.0 * u * t * t)).add(d.mul(t * t * t));
   }

   public static int turn(JunctionSpec s, int from, int to) {
      if (from != to && !new JunctionSides(s, true).paired(from, to)) {
         RoadGeometry.V f = radial(s, from).mul(-1.0);
         RoadGeometry.V d = radial(s, to);
         if (f.dot(d) > 0.707106) {
            return 1;
         } else {
            return f.left().dot(d) > 0.0 ? 4 : 2;
         }
      } else {
         return 8;
      }
   }

   public static int mask(JunctionSpec s, int from, int lane) {
      int result = requestedMask(s, from, lane);
      if (!s.arms().get(from).rightSlip()) {
         return result;
      } else {
         result &= -5;
         if (result == 0) {
            int available = 0;

            for (int j = 0; j < s.arms().size(); j++) {
               if (j != from && s.arms().get(j).outgoing() > 0) {
                  available |= turn(s, from, j);
               }
            }

            result = (available & 1) != 0 ? 1 : available & 2;
         }

         return result;
      }
   }

   private static int requestedMask(JunctionSpec s, int from, int lane) {
      int requested = s.arms().get(from).lanes().get(lane).mask();
      if (requested >= 0) {
         return requested;
      } else {
         int available = 0;

         for (int j = 0; j < s.arms().size(); j++) {
            if (j != from && s.arms().get(j).outgoing() > 0 && turn(s, from, j) != 8) {
               available |= turn(s, from, j);
            }
         }

         if (s.kind() == JunctionSpec.Kind.ROUNDABOUT && available == 0 && s.arms().get(from).outgoing() > 0) {
            available = 8;
         }

         int count = s.arms().get(from).incoming();
         if (count == 1) {
            return available;
         } else {
            int result = available & 1;
            if (result == 0) {
               if ((available & 6) == 6) {
                  return lane < (count + 1) / 2 ? 2 : 4;
               } else {
                  return available;
               }
            } else {
               if (lane == 0) {
                  result |= available & 2;
               }

               if (lane == count - 1) {
                  result |= available & 4;
               }

               return result;
            }
         }
      }
   }

   public static int signal(JunctionSpec s, int arm, long time) {
      return plan(s).signals().vehicle(arm, time);
   }

   public static int pedestrianSignal(JunctionSpec s, int arm, long time) {
      return plan(s).signals().pedestrian(arm, time);
   }

   public static double radius(JunctionSpec s) {
      return new JunctionSides(s, true).radius(s);
   }

   private static JunctionPlanner.Plan build(JunctionSpec source, int geometryVersion) {
      return build(source, geometryVersion, false);
   }

   private static JunctionPlanner.Plan build(JunctionSpec source, int geometryVersion, boolean singleArrowRow) {
      if (source.oneSide() != null) {
         if (geometryVersion < 51) {
            throw new IllegalArgumentException("单边路口需要几何版本51");
         } else {
            return OneSidedJunction.apply(source, build(OneSidedJunction.prepare(source), geometryVersion, geometryVersion >= 54));
         }
      } else {
         JunctionSpec s = geometryVersion >= 50
            ? source.supportedCrossings()
            : (geometryVersion >= 39 && geometryVersion < 47 ? source.arms(source.arms().stream().map(JunctionPlanner::bridgeArm).toList()) : source);
         int n = s.arms().size();
         JunctionSides sides = new JunctionSides(s, geometryVersion >= 35);
         List<Integer> order = sides.order;
         double radius = sides.radius(s, geometryVersion >= 46);
         if (s.kind() == JunctionSpec.Kind.ROUNDABOUT) {
            for (int k = 0; k < n; k++) {
               int i = order.get(k);
               int j = order.get((k + 1) % n);
               RoadGeometry.V a = sides.outward.get(i);
               RoadGeometry.V b = sides.outward.get(j);
               double angle = Math.atan2(cross(a, b), a.dot(b));
               if (angle <= 0.0) {
                  angle += Math.PI * 2;
               }

               if (angle < Math.toRadians(25.0)) {
                  throw new IllegalArgumentException("相邻接入口夹角至少 25°；请拉开端点");
               }

               double span = Math.asin(Math.min(0.99, s.arms().get(i).width() / radius / 2.0))
                  + Math.asin(Math.min(0.99, s.arms().get(j).width() / radius / 2.0));
               if (angle < span + Math.toRadians(2.0)) {
                  throw new IllegalArgumentException("相邻环岛接入口过近；请拉开方向或增大中央岛半径");
               }
            }
         }

         List<RoadGeometry.Mesh> approaches = new ArrayList<>();
         List<RoadGeometry.V> mouths = new ArrayList<>();

         for (int ix = 0; ix < n; ix++) {
            JunctionSpec.Arm ax = s.arms().get(ix);
            RoadGeometry.V r = sides.outward.get(ix);
            double mouthRadius = radius;
            if (s.kind() == JunctionSpec.Kind.ROUNDABOUT) {
               double square = radius * radius - ax.width() * ax.width() / 4.0;
               if (square <= 0.0 || Math.sqrt(square) - 0.35 < s.islandRadius() + 0.25) {
                  throw new IllegalArgumentException("接入口 " + (ix + 1) + " 过宽；请增加环道车道数或宽度");
               }

               mouthRadius = Math.sqrt(square) - 0.35;
            }

            RoadGeometry.V end = sides.mouth(s, ix, mouthRadius);
            double length = ax.endpoint().position().sub(end).horizontalLength();
            if (ax.endpoint().position().sub(s.center()).dot(r) < radius + 8.0) {
               throw new IllegalArgumentException("接入口 " + (ix + 1) + " 太近；离中心至少 " + (int)Math.ceil(radius + 8.0) + " 格");
            }

            if (length > 512.0) {
               throw new IllegalArgumentException("接入口距路口边缘最多 512 格");
            }

            if (ax.inward().dot(r.mul(-1.0)) < 0.5) {
               throw new IllegalArgumentException("接入口 " + (ix + 1) + " 朝向偏离路口，请调整端点朝向");
            }

            RoadGeometry.V start = ax.endpoint().position();
            RoadGeometry.V c1 = start.add(ax.inward().mul(length * 0.33));
            RoadGeometry.V c2 = end.add(r.mul(length * 0.33));
            int steps = (int)Math.ceil(length * 2.0);
            ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
            RoadGeometry.V prev = null;
            double dist = 0.0;

            for (int k = 0; k <= steps; k++) {
               double t = (double)k / (double)steps;
               RoadGeometry.V p = bezier(start, c1, c2, end, t);
               double slope = ax.endpoint().grade() * ax.endpoint().direction().dot(ax.inward());
               double y = (2.0 * t * t * t - 3.0 * t * t + 1.0) * start.y()
                  + (t * t * t - 2.0 * t * t + t) * length * slope
                  + (-2.0 * t * t * t + 3.0 * t * t) * end.y();
               p = new RoadGeometry.V(p.x(), y, p.z());
               RoadGeometry.V tangent = k == 0
                  ? ax.inward()
                  : (
                     k == steps
                        ? r.mul(-1.0)
                        : bezier(start, c1, c2, end, Math.min(1.0, t + 1.0E-4)).sub(bezier(start, c1, c2, end, Math.max(0.0, t - 1.0E-4))).horizontalUnit()
                  );
               if (prev != null) {
                  dist += p.distance(prev);
               }

               double blend = RoadGeometry.Settings.smooth(Math.min(1.0, t * 2.0));
               samples.add(
                  new RoadGeometry.Sample(
                     p,
                     tangent.left(),
                     dist,
                     (
                           (ax.attached() ? (geometryVersion >= 23 ? ax.external().startWidth() : ax.external().width()) : ax.width()) * (1.0 - blend)
                              + ax.width() * blend
                        )
                        / 2.0
                  )
               );
               prev = p;
            }

            RoadGeometry.Settings approachSettings = settings(ax.width(), s.thickness())
               .options(RoadProfile.Options.DEFAULT.sidewalk(ax.external().options().sidewalk()).streetscape(ax.external().options().streetscape()));
            if (geometryVersion >= 47) {
               approachSettings = approachSettings.structure(ax.external().structure())
                  .options(ax.external().options().traffic(s.leftTraffic()).lanePoints(LanePoints.Data.EMPTY));
            }

            approaches.add(RoadRibbon.mesh(samples, approachSettings));
            mouths.add(end);
         }

         ArrayList<JunctionPlanner.Movement> movement = new ArrayList<>();
         ArrayList<RoadSurface.Face> guides = new ArrayList<>();

         for (int ix = 0; ix < n; ix++) {
            for (int lane = 0; lane < s.arms().get(ix).incoming(); lane++) {
               JunctionSpec.Arm axx = s.arms().get(ix);
               JunctionSpec.Lane config = axx.lanes().get(lane);
               int mask = mask(s, ix, lane);

               for (int ti = 0; ti < 4; ti++) {
                  if ((mask & JunctionSpec.TURNS[ti]) != 0) {
                     int t = JunctionSpec.TURNS[ti];
                     int target = config.targets().get(ti);
                     if (target < 0) {
                        double best = Double.POSITIVE_INFINITY;

                        for (int jx = 0; jx < n; jx++) {
                           if (s.arms().get(jx).outgoing() > 0 && turn(s, ix, jx) == t) {
                              double anglex = Math.acos(Math.max(-1.0, Math.min(1.0, radial(s, ix).mul(-1.0).dot(radial(s, jx)))));
                              double score = Math.abs(anglex - (t == 1 ? 0.0 : (t == 8 ? Math.PI : Math.PI / 2)));
                              if (score < best) {
                                 best = score;
                                 target = jx;
                              }
                           }
                        }
                     }

                     if (target < 0 || target >= n || s.arms().get(target).outgoing() == 0 || turn(s, ix, target) != t) {
                        throw new IllegalArgumentException("接入口 " + (ix + 1) + " 第 " + (lane + 1) + " 车道的" + JunctionSpec.maskName(t) + "没有有效出口");
                     }

                     JunctionSpec.Arm bx = s.arms().get(target);
                     int dest = config.targetLanes().get(ti);
                     if (dest < 0) {
                        dest = Math.min(lane, bx.outgoing() - 1);
                     }

                     if (geometryVersion >= 49 && JunctionSlips.receives(s, target)) {
                        dest = Math.min(dest, bx.outgoing() - 2);
                     }

                     if (dest >= bx.outgoing()) {
                        throw new IllegalArgumentException("目标出口车道不存在");
                     }

                     RoadGeometry.V f = radial(s, ix).mul(-1.0);
                     RoadGeometry.V g = radial(s, target);
                     RoadGeometry.V p = mouths.get(ix).add(f.left().mul(axx.laneCenter(true, lane, s.leftTraffic())));
                     RoadGeometry.V q = mouths.get(target).add(g.mul(-1.0).left().mul(bx.laneCenter(false, dest, s.leftTraffic())));
                     ArrayList<RoadGeometry.V> path = new ArrayList<>();
                     if (s.kind() == JunctionSpec.Kind.ROUNDABOUT) {
                        double rr = s.islandRadius() + ((double)Math.min(lane, s.ringLanes() - 1) + 0.5) * s.ringLaneWidth();
                        double aa = Math.atan2(p.z() - s.center().z(), p.x() - s.center().x());
                        double bb = Math.atan2(q.z() - s.center().z(), q.x() - s.center().x());
                        double sign = s.leftTraffic() ? 1.0 : -1.0;
                        double delta = (bb - aa) * sign;

                        while (delta <= 0.01) {
                           delta += Math.PI * 2;
                        }

                        path.add(p);

                        for (int k = 0; k <= 64; k++) {
                           double th = aa + sign * delta * (double)k / 64.0;
                           path.add(s.center().add(new RoadGeometry.V(Math.cos(th) * rr, 0.0, Math.sin(th) * rr)));
                        }

                        path.add(q);
                     } else {
                        double handle = t == 8 ? Math.max(axx.width(), 6.0) : p.distance(q) * 0.45;

                        for (int k = 0; k <= 32; k++) {
                           path.add(bezier(p, p.add(f.mul(handle)), q.sub(g.mul(handle)), q, (double)k / 32.0));
                        }
                     }

                     movement.add(new JunctionPlanner.Movement(ix, lane, target, dest, t, List.copyOf(path)));
                  }
               }
            }
         }

         ArrayList<JunctionGuides.Guide> guidePaths = new ArrayList<>();
         if (s.guides() && s.kind() == JunctionSpec.Kind.INTERSECTION) {
            for (JunctionPlanner.Movement axx : movement) {
               for (JunctionPlanner.Movement bxx : movement) {
                  if (axx.from() == bxx.from()
                     && axx.to() == bxx.to()
                     && axx.turn() == bxx.turn()
                     && bxx.lane() == axx.lane() + 1
                     && Math.abs(bxx.targetLane() - axx.targetLane()) == 1
                     && axx.turn() != 8) {
                     ArrayList<RoadGeometry.V> edge = new ArrayList<>();

                     for (int k = 0; k < axx.path().size(); k++) {
                        edge.add(axx.path().get(k).add(bxx.path().get(k)).mul(0.5));
                     }

                     if (geometryVersion >= 41) {
                        guidePaths.add(new JunctionGuides.Guide(axx.to(), Math.min(axx.targetLane(), bxx.targetLane()), edge));
                     } else {
                        JunctionPaint.path(guides, edge, 0.14, true, 15593186);
                     }
                  }
               }
            }
         }

         if (geometryVersion >= 41) {
            JunctionGuides.paint(guides, guidePaths);
         }

         ArrayList<JunctionPlanner.Piece> pieces = new ArrayList<>();

         for (int ix = 0; ix < n; ix++) {
            pieces.add(approach(s, ix, approaches.get(ix), approaches, geometryVersion, singleArrowRow));
         }

         List<RoadGeometry.Mesh> cornerApproaches = approaches;
         if (geometryVersion >= 49 && JunctionSlips.enabled(s)) {
            JunctionSlips.layout(s, approaches, geometryVersion >= 52, geometryVersion >= 54);
            JunctionSlipProfiles profiles = new JunctionSlipProfiles(s, approaches, geometryVersion >= 52, geometryVersion >= 54);
            cornerApproaches = new ArrayList<>();

            for (int ix = 0; ix < n; ix++) {
               cornerApproaches.add(profiles.approachMesh(ix));
            }
         }

         ArrayList<RoadGeometry.V> boundary = new ArrayList<>();
         if (s.kind() == JunctionSpec.Kind.INTERSECTION) {
            for (int k = 0; k < n; k++) {
               int ix = order.get(k);
               int jxx = order.get((k + 1) % n);
               RoadGeometry.V rx = sides.outward.get(ix);
               RoadGeometry.V next = sides.outward.get(jxx);
               RoadGeometry.Sample mi = cornerApproaches.get(ix).last();
               RoadGeometry.Sample mj = cornerApproaches.get(jxx).last();
               RoadGeometry.V first = cornerApproaches != approaches
                  ? mi.at(mi.halfWidth(), 0.0)
                  : mouths.get(ix).sub(rx.left().mul(s.arms().get(ix).width() / 2.0));
               RoadGeometry.V axx = cornerApproaches != approaches
                  ? mi.at(-mi.halfWidth(), 0.0)
                  : mouths.get(ix).add(rx.left().mul(s.arms().get(ix).width() / 2.0));
               RoadGeometry.V bxxx = cornerApproaches != approaches
                  ? mj.at(mj.halfWidth(), 0.0)
                  : mouths.get(jxx).sub(next.left().mul(s.arms().get(jxx).width() / 2.0));
               boundary.add(first);
               boundary.add(axx);
               double anglex = Math.atan2(cross(rx, next), rx.dot(next));
               if (anglex <= 0.0) {
                  anglex += Math.PI * 2;
               }

               RoadGeometry.V control = axx.add(bxxx).mul(0.5);
               if (geometryVersion < 22 ? anglex < 3.1315926535897933 : Math.abs(cross(rx, next)) > 0.01) {
                  double d = cross(bxxx.sub(axx), next) / cross(rx, next);
                  control = axx.add(rx.mul(d));
               }

               if (geometryVersion >= 29 ? rx.dot(next) < -0.995 : geometryVersion >= 28 && n == 2 && rx.dot(next) < -0.8660254) {
                  control = axx.add(bxxx).mul(0.5);
               }

               int steps = geometryVersion < 22 ? 5 : Math.max(5, (int)Math.ceil((axx.distance(control) + control.distance(bxxx)) / 0.8));
               if (geometryVersion < 38) {
                  for (int z = 1; z < steps; z++) {
                     double tx = (double)z / (double)steps;
                     boundary.add(axx.mul((1.0 - tx) * (1.0 - tx)).add(control.mul(2.0 * tx * (1.0 - tx))).add(bxxx.mul(tx * tx)));
                  }
               } else {
                  double h0 = -control.sub(axx).dot(rx) * 2.0 / 3.0;
                  double h1 = -control.sub(bxxx).dot(next) * 2.0 / 3.0;
                  if (h0 < 0.05 || h1 < 0.05) {
                     h0 = h1 = Math.min(s.cornerRadius() * 1.25, axx.distance(bxxx) * 0.38);
                  }

                  double limit = axx.distance(bxxx) * 0.8;
                  h0 = Math.min(h0, limit);
                  h1 = Math.min(h1, limit);
                  RoadGeometry.V c1 = axx.sub(rx.mul(h0));
                  RoadGeometry.V c2 = bxxx.sub(next.mul(h1));

                  for (int z = 1; z < steps; z++) {
                     double tx = (double)z / (double)steps;
                     boundary.add(bezier(axx, c1, c2, bxxx, tx));
                  }
               }
            }

            ArrayList<RoadStructures.Part> edges = new ArrayList<>();

            for (int k = 0; k < boundary.size(); k++) {
               RoadGeometry.V axxx = boundary.get(k);
               RoadGeometry.V bxxxx = boundary.get((k + 1) % boundary.size());
               if (!approaches.stream().anyMatch(m -> RoadQueries.contains(m, axxx.add(bxxxx).mul(0.5), 0.02, 0.1))) {
                  if (geometryVersion >= 44 && s.outerRail()) {
                     RoadStructures.barrier(edges, axxx, bxxxx, false, true, (double)k * 0.5);
                  }

                  if (s.arms().stream().anyMatch(arm -> arm.curbWidth() > 0.0)) {
                     if (geometryVersion < 27) {
                        edges.add(new RoadStructures.Part(axxx, bxxxx, 0.3, 0.2, false, RoadStructures.Material.CONCRETE));
                     } else {
                        RoadGeometry.V ia = boundaryInset(s, approaches, boundary, k, geometryVersion >= 32);
                        RoadGeometry.V ib = boundaryInset(s, approaches, boundary, (k + 1) % boundary.size(), geometryVersion >= 32);
                        double width = Math.max(ia.horizontalLength(), ib.horizontalLength());
                        if (width > 0.001) {
                           edges.add(
                              new RoadStructures.Part(axxx.add(ia.mul(0.5)), bxxxx.add(ib.mul(0.5)), width, 0.2, false, RoadStructures.Material.CONCRETE)
                                 .frames(ia.mul(0.5), ib.mul(0.5))
                           );
                        }
                     }
                  }
               }
            }

            boolean firstPiece = true;

            for (List<RoadGeometry.V> triangle : geometryVersion >= 29 ? JunctionPaint.triangulate(boundary) : JunctionPaint.triangulateLegacy(boundary)) {
               pieces.add(new JunctionPlanner.Piece(triangle(triangle, s.thickness()), List.copyOf(guides), firstPiece ? List.copyOf(edges) : List.of(), -1));
               firstPiece = false;
            }
         } else {
            double mid = s.islandRadius() + (double)s.ringLanes() * s.ringLaneWidth() / 2.0;
            double half = (double)s.ringLanes() * s.ringLaneWidth() / 2.0;
            int count = (int)Math.ceil((Math.PI * 2) * mid * 2.0);
            ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
            ArrayList<RoadSurface.Face> paint = new ArrayList<>();

            for (int kx = 0; kx <= count; kx++) {
               double tx = (Math.PI * 2) * (double)kx / (double)count;
               RoadGeometry.V rxx = new RoadGeometry.V(Math.cos(tx), 0.0, Math.sin(tx));
               samples.add(new RoadGeometry.Sample(s.center().add(rxx.mul(mid)), rxx, (double)(kx * 2) * Math.PI * mid / (double)count, half));
            }

            RoadGeometry.Mesh raw = RoadRibbon.mesh(samples, settings(half * 2.0, s.thickness()));
            RoadGeometry.Mesh ring = new RoadGeometry.Mesh(raw.samples(), raw.settings(), raw.min(), raw.max(), raw.length(), true);

            for (int lane = 0; lane < s.ringLanes(); lane++) {
               ArrayList<RoadGeometry.V> line = new ArrayList<>();
               double rr = s.islandRadius() + (double)lane * s.ringLaneWidth();

               for (int kx = 0; kx <= count; kx++) {
                  double tx = (Math.PI * 2) * (double)kx / (double)count;
                  line.add(s.center().add(new RoadGeometry.V(Math.cos(tx) * rr, 0.0, Math.sin(tx) * rr)));
               }

               JunctionPaint.path(paint, line, 0.15, lane > 0, 15593186);
            }

            for (int kx = 1; kx <= count; kx++) {
               double ta = (Math.PI * 2) * (double)(kx - 1) / (double)count;
               double tb = (Math.PI * 2) * (double)kx / (double)count;
               RoadGeometry.V axxx = s.center().add(new RoadGeometry.V(Math.cos(ta) * (mid + half - 0.2), 0.0, Math.sin(ta) * (mid + half - 0.2)));
               RoadGeometry.V bxxxx = s.center().add(new RoadGeometry.V(Math.cos(tb) * (mid + half - 0.2), 0.0, Math.sin(tb) * (mid + half - 0.2)));
               if (approaches.stream().noneMatch(m -> RoadQueries.contains(m, axxx, 0.1, 0.1) || RoadQueries.contains(m, bxxxx, 0.1, 0.1))) {
                  JunctionPaint.line(paint, axxx, bxxxx, 0.15, 15593186);
               }
            }

            ArrayList<RoadStructures.Part> island = new ArrayList<>();
            if (s.greenIsland() && geometryVersion < 43) {
               for (double z = -s.islandRadius() + 0.5; z < s.islandRadius(); z++) {
                  double x = Math.sqrt(Math.max(0.0, s.islandRadius() * s.islandRadius() - Math.pow(Math.abs(z) + 0.5, 2.0))) - 0.2;
                  if (x > 0.1) {
                     RoadGeometry.V axxx = s.center().add(new RoadGeometry.V(-x, 0.0, z));
                     RoadGeometry.V bxxxx = s.center().add(new RoadGeometry.V(x, 0.0, z));
                     island.add(new RoadStructures.Part(axxx, bxxxx, 1.0, 0.35, false, RoadStructures.Material.SOIL));
                     island.add(
                        new RoadStructures.Part(
                           axxx.add(new RoadGeometry.V(0.0, 0.35, 0.0)),
                           bxxxx.add(new RoadGeometry.V(0.0, 0.35, 0.0)),
                           1.0,
                           0.4,
                           false,
                           RoadStructures.Material.GREEN
                        )
                     );
                  }
               }
            }

            for (int kxx = 1; kxx <= count; kxx++) {
               double ta = (Math.PI * 2) * (double)(kxx - 1) / (double)count;
               double tb = (Math.PI * 2) * (double)kxx / (double)count;
               RoadGeometry.V ra = new RoadGeometry.V(Math.cos(ta), 0.0, Math.sin(ta));
               RoadGeometry.V rb = new RoadGeometry.V(Math.cos(tb), 0.0, Math.sin(tb));
               if (s.greenIsland() && geometryVersion >= 43) {
                  RoadGeometry.V axxx = s.center().add(ra.mul(s.islandRadius() - 0.3));
                  RoadGeometry.V bxxxx = s.center().add(rb.mul(s.islandRadius() - 0.3));
                  RoadGeometry.V edge = axxx.add(bxxxx).mul(0.5);
                  RoadGeometry.V frame = axxx.sub(bxxxx).mul(0.5);
                  RoadGeometry.V zero = new RoadGeometry.V(0.0, 0.0, 0.0);
                  island.add(
                     new RoadStructures.Part(
                        edge, s.center(), axxx.distance(bxxxx), 0.35, false, RoadStructures.Material.SOIL, frame, zero, "sr:roundabout_soil"
                     )
                  );
                  island.add(
                     new RoadStructures.Part(
                        edge.add(new RoadGeometry.V(0.0, 0.35, 0.0)),
                        s.center().add(new RoadGeometry.V(0.0, 0.35, 0.0)),
                        axxx.distance(bxxxx),
                        0.4,
                        false,
                        RoadStructures.Material.GREEN,
                        frame,
                        zero,
                        "sr:roundabout_green"
                     )
                  );
               }

               island.add(
                  new RoadStructures.Part(
                        s.center().add(ra.mul(s.islandRadius() - 0.15)),
                        s.center().add(rb.mul(s.islandRadius() - 0.15)),
                        0.3,
                        0.4,
                        false,
                        RoadStructures.Material.CONCRETE
                     )
                     .frames(ra.mul(0.15), rb.mul(0.15))
               );
               RoadGeometry.V axxx = s.center().add(ra.mul(radius - 0.16));
               RoadGeometry.V bxxxx = s.center().add(rb.mul(radius - 0.16));
               if (s.outerRail() && approaches.stream().noneMatch(m -> RoadQueries.contains(m, axxx, 1.0, 0.1) || RoadQueries.contains(m, bxxxx, 1.0, 0.1))) {
                  RoadStructures.barrier(island, axxx, bxxxx, false, false, (double)kxx * 0.5);
               }
            }

            pieces.add(new JunctionPlanner.Piece(ring, List.copyOf(paint), List.copyOf(island), -1));

            for (int kxx = 0; kxx < 64; kxx++) {
               double tx = (Math.PI * 2) * (double)kxx / 64.0;
               boundary.add(s.center().add(new RoadGeometry.V(Math.cos(tx) * radius, 0.0, Math.sin(tx) * radius)));
            }
         }

         if (geometryVersion >= 38 || geometryVersion >= 37 && s.kind() == JunctionSpec.Kind.ROUNDABOUT) {
            boundary = new ArrayList<>(RoadSidewalks.splitBoundary(boundary, approaches));
         }

         List<Integer> pedestrianArms = List.of();
         Map<RoadStructures.Part, JunctionSignals.Head> heads = Map.of();
         boolean lateSignals = geometryVersion >= 55 && JunctionSlips.enabled(s) && s.control() == JunctionSpec.Control.SIGNALS;
         if (s.control() == JunctionSpec.Control.SIGNALS && !lateSignals) {
            JunctionSignals.Layout layout = JunctionSignals.structures(s, approaches, boundary, geometryVersion >= 25, geometryVersion >= 26);
            List<List<RoadStructures.Part>> signals = layout.structures();
            pedestrianArms = layout.pedestrianArms();
            heads = layout.heads();

            for (int ixx = 0; ixx < n; ixx++) {
               JunctionPlanner.Piece old = pieces.get(ixx);
               ArrayList<RoadStructures.Part> parts = new ArrayList<>(old.structures());
               parts.addAll(signals.get(ixx));
               pieces.set(ixx, new JunctionPlanner.Piece(old.mesh(), old.paint(), List.copyOf(parts), ixx));
            }
         }

         for (int ixx = 0; ixx < approaches.size(); ixx++) {
            JunctionPlanner.Piece old = pieces.get(ixx);
            ArrayList<RoadStructures.Part> parts = new ArrayList<>(old.structures());
            parts.addAll(
               geometryVersion >= 29
                  ? RoadSidewalks.parts(old.mesh(), s.arms().get(ixx).external().options().sidewalk(), null, old.structures())
                  : RoadSidewalks.parts(old.mesh(), s.arms().get(ixx).external().options().sidewalk())
            );
            pieces.set(ixx, new JunctionPlanner.Piece(old.mesh(), old.paint(), List.copyOf(parts), old.arm()));
         }

         if (pieces.size() > approaches.size()) {
            int ixx = approaches.size();
            JunctionPlanner.Piece old = pieces.get(ixx);
            ArrayList<RoadStructures.Part> parts = new ArrayList<>(old.structures());
            parts.addAll(
               geometryVersion >= 36
                  ? RoadSidewalks.taperedJunctionParts(s, approaches, boundary)
                  : RoadSidewalks.junctionParts(s, approaches, boundary, geometryVersion >= 32, geometryVersion >= 33, geometryVersion >= 34)
            );
            pieces.set(ixx, new JunctionPlanner.Piece(old.mesh(), old.paint(), List.copyOf(parts), old.arm()));
         }

         if (geometryVersion >= 34) {
            List<TactilePaths.Trim> trims = TactilePaths.trims(s, approaches, boundary);

            for (int ixx = 0; ixx < approaches.size(); ixx++) {
               JunctionPlanner.Piece old = pieces.get(ixx);
               pieces.set(
                  ixx, new JunctionPlanner.Piece(old.mesh(), old.paint(), TactilePaths.trimApproach(old.structures(), old.mesh(), ixx, trims), old.arm())
               );
            }
         }

         if (geometryVersion >= 41) {
            TactileSurface surface = new TactileSurface(pieces.stream().flatMap(px -> px.structures().stream()).toList());

            for (int ixx = 0; ixx < pieces.size(); ixx++) {
               JunctionPlanner.Piece p = pieces.get(ixx);
               pieces.set(ixx, new JunctionPlanner.Piece(p.mesh(), p.paint(), surface.gradePaving(p.structures()), p.arm()));
            }
         }

         if (geometryVersion >= 32) {
            SidewalkJoins sidewalks = new SidewalkJoins(pieces.stream().map(JunctionPlanner.Piece::mesh).toList(), List.of());

            for (int ixx = 0; ixx < pieces.size(); ixx++) {
               JunctionPlanner.Piece old = pieces.get(ixx);
               pieces.set(ixx, new JunctionPlanner.Piece(old.mesh(), old.paint(), sidewalks.clip(old.structures()), old.arm()));
            }
         }

         if (geometryVersion >= 40) {
            TactileSurface surface = new TactileSurface(pieces.stream().flatMap(px -> px.structures().stream()).toList());

            for (int ixx = 0; ixx < pieces.size(); ixx++) {
               JunctionPlanner.Piece p = pieces.get(ixx);
               pieces.set(ixx, new JunctionPlanner.Piece(p.mesh(), p.paint(), surface.conform(p.structures()), p.arm()));
            }
         }

         if (geometryVersion >= 24) {
            for (int ixx = 0; ixx < pieces.size(); ixx++) {
               JunctionPlanner.Piece p = pieces.get(ixx);
               ArrayList<RoadStructures.Part> parts = new ArrayList<>(p.structures());
               RoadStructures.terminalPosts(parts);
               pieces.set(ixx, new JunctionPlanner.Piece(p.mesh(), p.paint(), List.copyOf(parts), p.arm()));
            }
         }

         JunctionPlanner.Plan result = new JunctionPlanner.Plan(
            List.copyOf(pieces),
            List.copyOf(movement),
            List.copyOf(boundary),
            RoadSidewalks.junctionCells(s, approaches, boundary),
            new JunctionSignalCycle(s, pedestrianArms),
            heads
         );
         result = geometryVersion >= 49
            ? JunctionSlips.apply(s, result, geometryVersion >= 50, geometryVersion >= 52, geometryVersion >= 53, geometryVersion >= 54)
            : (geometryVersion == 48 ? JunctionSlips48.apply(s, result) : result);
         return lateSignals ? JunctionSignals.afterSlips(s, result, approaches) : result;
      }
   }

   private static JunctionSpec.Arm bridgeArm(JunctionSpec.Arm a) {
      return a.external().structure() == RoadGeometry.Structure.BRIDGE && a.incoming() != 0 && a.outgoing() != 0
         ? new JunctionSpec.Arm(
            a.endpoint(),
            a.inward(),
            a.external(),
            a.incoming(),
            a.outgoing(),
            a.width(),
            1.0,
            RoadProfile.Median.RAIL,
            a.cycleWidth(),
            a.curbWidth(),
            a.crosswalk(),
            a.crossingWidth(),
            a.crossingSetback(),
            a.stopGap(),
            a.phase(),
            a.lanes(),
            a.attached(),
            a.advanceSign(),
            a.nameSign()
         )
         : a;
   }

   private static RoadGeometry.V boundaryInset(JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary, int index, boolean smooth) {
      RoadGeometry.V p = boundary.get(index);
      double best = Double.POSITIVE_INFINITY;
      double width = 0.0;
      double total = 0.0;
      double weighted = 0.0;

      for (int i = 0; i < approaches.size(); i++) {
         RoadGeometry.Sample mouth = approaches.get(i).last();
         double curb = spec.arms().get(i).curbWidth() > 0.0 ? spec.arms().get(i).curbWidth() + 0.5 : 0.0;

         for (int side : new int[]{-1, 1}) {
            double distance = p.distance(mouth.at((double)side * mouth.halfWidth(), 0.0));
            if (distance < 1.0E-5) {
               return mouth.left().mul((double)(-side) * curb);
            }

            double weight = 1.0 / (distance * distance);
            total += weight;
            weighted += curb * weight;
            if (distance < best) {
               best = distance;
               width = curb;
            }
         }
      }

      RoadGeometry.V prev = boundary.get(Math.floorMod(index - 1, boundary.size()));
      RoadGeometry.V next = boundary.get((index + 1) % boundary.size());
      RoadGeometry.V a = p.sub(prev).horizontalUnit();
      RoadGeometry.V b = next.sub(p).horizontalUnit();
      RoadGeometry.V normal = a.left().add(b.left()).mul(1.0 / Math.max(0.1, 1.0 + a.dot(b)));
      if (normal.dot(spec.center().sub(p)) < 0.0) {
         normal = normal.mul(-1.0);
      }

      return normal.mul(smooth && total > 0.0 ? weighted / total : width);
   }

   private static double seamBlend(RoadGeometry.Mesh mesh, RoadGeometry.Sample p) {
      return RoadGeometry.Settings.smooth(Math.min(1.0, 2.0 * p.distance() / Math.max(0.001, mesh.length())));
   }

   private static double seamCurb(JunctionSpec.Arm arm, RoadGeometry.Mesh mesh, RoadGeometry.Sample p, int side, int version) {
      RoadTransitions.Port port = arm.external().options().ends().port();
      double full = arm.curbWidth() > 0.0 ? arm.curbWidth() + 0.5 : 0.0;
      if (version >= 27 && port != null) {
         double start = side > 0 ? port.curbLeft() : port.curbRight();
         return start + (full - start) * seamBlend(mesh, p);
      } else {
         return p.halfWidth() - (arm.width() / 2.0 - arm.curbWidth() - arm.cycleWidth() - 0.5) - arm.cycleWidth();
      }
   }

   private static double seamDivider(JunctionSpec.Arm arm, RoadGeometry.Mesh mesh, RoadGeometry.Sample p, double target, int version) {
      RoadTransitions.Port port = arm.external().options().ends().port();
      if (version >= 27 && port != null && !port.dividers().isEmpty()) {
         double start = port.dividers().stream().min(Comparator.comparingDouble(d -> Math.abs(d - target))).orElse(target);
         return start + (target - start) * seamBlend(mesh, p);
      } else {
         return target;
      }
   }

   public static double approachMotor(JunctionSpec.Arm arm, RoadGeometry.Mesh mesh, RoadGeometry.Sample p, int side) {
      double target = (double)side * (arm.width() / 2.0 - arm.curbWidth() - arm.cycleWidth() - 0.5);
      RoadTransitions.Port port = arm.external().options().ends().port();
      if (arm.attached() && port != null) {
         double start = side > 0 ? port.motorMax() : port.motorMin();
         return start + (target - start) * seamBlend(mesh, p);
      } else {
         return target;
      }
   }

   public static double approachMedian(JunctionSpec.Arm arm, RoadGeometry.Mesh mesh, RoadGeometry.Sample p, boolean center, boolean left) {
      double target = center ? arm.divider(left) : arm.median();
      RoadTransitions.Port port = arm.external().options().ends().port();
      if (arm.attached() && port != null) {
         double start = center ? port.medianCenter() : port.median();
         return start + (target - start) * seamBlend(mesh, p);
      } else {
         return target;
      }
   }

   public static double approachEdge(JunctionSpec.Arm arm, RoadGeometry.Mesh mesh, RoadGeometry.Sample p, int side) {
      double motor = approachMotor(arm, mesh, p, side);
      return arm.cycleWidth() > 0.01 ? (double)side * (p.halfWidth() - Math.max(0.3, seamCurb(arm, mesh, p, side, 42) + 0.16)) : motor;
   }

   private static double cycleFade(JunctionSpec.Arm arm, double d, double end) {
      double fade = Math.min(6.0, end / 2.0);
      double from = arm.attached() ? fade : d;
      return RoadGeometry.Settings.smooth(Math.min(1.0, Math.min(from, end - d) / Math.max(0.001, fade)));
   }

   private static JunctionPlanner.Piece approach(
      JunctionSpec s, int i, RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> approaches, int version, boolean singleArrowRow
   ) {
      JunctionSpec.Arm arm = s.arms().get(i);
      ArrayList<RoadSurface.Face> paint = new ArrayList<>();
      ArrayList<RoadStructures.Part> parts = new ArrayList<>();
      boolean raised = version >= 38 && arm.external().structure() == RoadGeometry.Structure.BRIDGE;
      double ringOuter = s.islandRadius() + (double)s.ringLanes() * s.ringLaneWidth();
      double ringOverlap = s.kind() == JunctionSpec.Kind.ROUNDABOUT ? ringOuter - mesh.last().center().sub(s.center()).horizontalLength() : 0.0;
      double crossing = mesh.length() - ringOverlap - arm.crossingSetback() - arm.crossingWidth() / 2.0;
      double stop = arm.crosswalk() ? crossing - arm.crossingWidth() / 2.0 - arm.stopGap() : mesh.length() - ringOverlap - arm.crossingSetback();
      if (version >= 24 && stop < 5.0) {
         throw new IllegalArgumentException("接入口 " + (i + 1) + " 的停止线退距过大；请延长接路或减小斑马线/停止线退距");
      } else {
         double medianEnd = arm.crosswalk() ? crossing - arm.crossingWidth() / 2.0 - 0.5 : mesh.length() - ringOverlap - 1.0;
         double sign = s.leftTraffic() ? -1.0 : 1.0;
         double divider = arm.divider(s.leftTraffic());

         for (int k = 1; k < mesh.samples().size(); k++) {
            RoadGeometry.Sample a = mesh.samples().get(k - 1);
            RoadGeometry.Sample b = mesh.samples().get(k);

            for (int side : new int[]{-1, 1}) {
               RoadGeometry.V ea = a.at(version >= 42 ? approachEdge(arm, mesh, a, side) : (double)side * (a.halfWidth() - 0.3), -0.012);
               RoadGeometry.V eb = b.at(version >= 42 ? approachEdge(arm, mesh, b, side) : (double)side * (b.halfWidth() - 0.3), -0.012);
               if (s.kind() != JunctionSpec.Kind.ROUNDABOUT
                  || ea.sub(s.center()).horizontalLength() > ringOuter + 0.2 && eb.sub(s.center()).horizontalLength() > ringOuter + 0.2) {
                  if (version >= 54 && arm.crosswalk()) {
                     double low = crossing - arm.crossingWidth() / 2.0 - 0.12;
                     double high = crossing + arm.crossingWidth() / 2.0 + 0.12;

                     for (double[] span : List.of(
                        new double[]{a.distance(), Math.min(b.distance(), low)}, new double[]{Math.max(a.distance(), high), b.distance()}
                     )) {
                        if (span[1] > span[0] + 1.0E-8) {
                           RoadGeometry.Sample x = RoadSlicing.at(mesh, span[0]);
                           RoadGeometry.Sample y = RoadSlicing.at(mesh, span[1]);
                           JunctionPaint.line(
                              paint, x.at(approachEdge(arm, mesh, x, side), -0.012), y.at(approachEdge(arm, mesh, y, side), -0.012), 0.15, 15593186
                           );
                        }
                     }
                  } else {
                     JunctionPaint.line(paint, ea, eb, 0.15, 15593186);
                  }
               }

               if ((
                     arm.curbWidth() > 0.0
                        || version >= 27
                           && arm.external().options().ends().port() != null
                           && (arm.external().options().ends().port().curbLeft() > 0.0 || arm.external().options().ends().port().curbRight() > 0.0)
                  )
                  && a.distance() < mesh.length() - ringOverlap - 0.2) {
                  double ca = seamCurb(arm, mesh, a, side, version);
                  double cb = seamCurb(arm, mesh, b, side, version);
                  if (Math.max(ca, cb) > 0.001) {
                     RoadStructures.Part curb = new RoadStructures.Part(
                        a.at((double)side * (a.halfWidth() - ca / 2.0), 0.0),
                        b.at((double)side * (b.halfWidth() - cb / 2.0), 0.0),
                        Math.max(0.001, Math.max(ca, cb)),
                        0.2,
                        false,
                        RoadStructures.Material.CONCRETE
                     );
                     if (version >= 27) {
                        curb = curb.frames(a.left().mul(ca / 2.0), b.left().mul(cb / 2.0));
                     }

                     parts.add(curb);
                  }
               }

               if (arm.cycleWidth() > 0.0 && a.distance() < stop - 0.225) {
                  double offset = (double)side * (arm.width() / 2.0 - arm.curbWidth() - arm.cycleWidth() - 0.5);
                  RoadGeometry.Sample end = RoadStructures.sample(mesh, Math.min(b.distance(), stop - 0.225));
                  JunctionPaint.line(
                     paint,
                     a.at(version >= 42 ? approachMotor(arm, mesh, a, side) : offset, -0.014),
                     end.at(version >= 42 ? approachMotor(arm, mesh, end, side) : offset, -0.014),
                     0.13,
                     15593186
                  );
               }

               if (a.distance() < medianEnd) {
                  RoadGeometry.Sample end = RoadStructures.sample(mesh, Math.min(b.distance(), medianEnd));
                  if (arm.cycleWidth() > 0.0) {
                     double inner = version >= 42
                        ? approachMotor(arm, mesh, a, side)
                        : (double)side * (arm.width() / 2.0 - arm.curbWidth() - arm.cycleWidth() - 0.5);
                     double innerEnd = version >= 42 ? approachMotor(arm, mesh, end, side) : inner;
                     double outer = inner + (double)side * arm.cycleWidth();
                     double separation = RoadStreetscape.separatorWidth(arm.external().options());
                     if (!arm.external().options().cycleAsphalt()) {
                        paint.add(
                           new RoadSurface.Face(
                              List.of(
                                 a.at(inner + (double)side * (separation + 0.08), -0.009),
                                 a.at(outer - (double)side * 0.08, -0.009),
                                 end.at(innerEnd + (double)side * arm.cycleWidth() - (double)side * 0.08, -0.009),
                                 end.at(innerEnd + (double)side * (separation + 0.08), -0.009)
                              ),
                              5468001
                           )
                        );
                     }

                     if (version < 47 && version >= 42 && separation > 0.0 && !raised) {
                        double fa = cycleFade(arm, a.distance(), medianEnd);
                        double fb = cycleFade(arm, end.distance(), medianEnd);
                        RoadStructures.cyclePlanter(parts, a, end, side, inner, innerEnd, separation, fa, fb);
                     } else if (version < 47 && separation > 0.0) {
                        RoadGeometry.V first = a.at(inner + (double)side * separation / 2.0, 0.0);
                        RoadGeometry.V last = end.at(inner + (double)side * separation / 2.0, 0.0);
                        if (raised) {
                           RoadStructures.barrier(parts, first, last, false, true, a.distance());
                        } else {
                           parts.add(
                              new RoadStructures.Part(first, last, separation, 0.3, false, RoadStructures.Material.CONCRETE)
                                 .frames(a.left().mul(separation / 2.0), end.left().mul(separation / 2.0))
                           );
                           parts.add(
                              new RoadStructures.Part(
                                    first.add(new RoadGeometry.V(0.0, 0.3, 0.0)),
                                    last.add(new RoadGeometry.V(0.0, 0.3, 0.0)),
                                    separation - 0.3,
                                    0.45,
                                    false,
                                    RoadStructures.Material.GREEN
                                 )
                                 .frames(a.left().mul((separation - 0.3) / 2.0), end.left().mul((separation - 0.3) / 2.0))
                           );
                        }
                     }

                     if (arm.external().options().streetscape().parking()
                        && arm.external().structure() != RoadGeometry.Structure.BRIDGE
                        && a.distance() < stop - 3.0) {
                        double station = Math.ceil((a.distance() + arm.external().options().ends().paintPhase()) / RoadStreetscape.parkingLength())
                              * RoadStreetscape.parkingLength()
                           - arm.external().options().ends().paintPhase();
                        if (station >= a.distance() - 1.0E-7 && station < end.distance() - 1.0E-7) {
                           RoadGeometry.Sample p = RoadStructures.sample(mesh, station);
                           JunctionPaint.line(paint, p.at(inner, -0.012), p.at((double)side * (p.halfWidth() - 0.3), -0.012), 0.12, 15593186);
                        }
                     }

                     if (version < 47 && arm.external().options().cycleRail()) {
                        RoadStructures.barrier(parts, a.at(inner, 0.0), end.at(innerEnd, 0.0), false, false, a.distance());
                     }
                  }

                  if (version < 44 && arm.external().options().outerRail() == RoadProfile.OuterRail.ON) {
                     RoadStructures.barrier(
                        parts,
                        a.at((double)side * (a.halfWidth() - 0.16), 0.0),
                        end.at((double)side * (end.halfWidth() - 0.16), 0.0),
                        false,
                        arm.external().structure() == RoadGeometry.Structure.BRIDGE,
                        a.distance()
                     );
                  }
               }

               if (version >= 44 && version < 47 && (s.outerRail() || arm.external().options().outerRail() == RoadProfile.OuterRail.ON)) {
                  double lo = arm.crosswalk() ? crossing - arm.crossingWidth() / 2.0 : Double.POSITIVE_INFINITY;
                  double hi = arm.crosswalk() ? crossing + arm.crossingWidth() / 2.0 : Double.POSITIVE_INFINITY;

                  for (double[] spanx : List.of(new double[]{a.distance(), Math.min(b.distance(), lo)}, new double[]{Math.max(a.distance(), hi), b.distance()})) {
                     if (spanx[1] > spanx[0] + 1.0E-7) {
                        RoadGeometry.Sample x = RoadStructures.sample(mesh, spanx[0]);
                        RoadGeometry.Sample y = RoadStructures.sample(mesh, spanx[1]);
                        RoadStructures.barrier(
                           parts,
                           x.at((double)side * (x.halfWidth() - 0.16), 0.0),
                           y.at((double)side * (y.halfWidth() - 0.16), 0.0),
                           false,
                           s.outerRail() || raised,
                           spanx[0]
                        );
                     }
                  }
               }
            }

            if (version < 47 && (arm.median() > 0.2 || raised && arm.incoming() > 0 && arm.outgoing() > 0) && a.distance() < medianEnd) {
               RoadGeometry.Sample endx = RoadStructures.sample(mesh, Math.min(b.distance(), medianEnd));
               double da = version >= 42 ? approachMedian(arm, mesh, a, true, s.leftTraffic()) : divider;
               double db = version >= 42 ? approachMedian(arm, mesh, endx, true, s.leftTraffic()) : divider;
               RoadGeometry.V aa = a.at(da, 0.0);
               RoadGeometry.V bb = endx.at(db, 0.0);
               if (aa.distance(bb) > 0.01) {
                  if (version >= 42 && arm.medianKind() == RoadProfile.Median.GREEN && !raised) {
                     parts.addAll(
                        RoadStructures.planting(
                           aa,
                           bb,
                           a.left(),
                           endx.left(),
                           approachMedian(arm, mesh, a, false, s.leftTraffic()),
                           approachMedian(arm, mesh, endx, false, s.leftTraffic()),
                           1.0,
                           0.0
                        )
                     );
                  } else if (arm.medianKind() == RoadProfile.Median.GREEN && !raised) {
                     parts.add(new RoadStructures.Part(aa, bb, Math.max(0.1, arm.median() - 0.3), 0.3, false, RoadStructures.Material.SOIL));
                     parts.add(
                        new RoadStructures.Part(
                           aa.add(new RoadGeometry.V(0.0, 0.3, 0.0)),
                           bb.add(new RoadGeometry.V(0.0, 0.3, 0.0)),
                           Math.max(0.1, arm.median() - 0.5),
                           0.55,
                           false,
                           RoadStructures.Material.GREEN
                        )
                     );
                     parts.add(
                        new RoadStructures.Part(
                           aa.add(new RoadGeometry.V(0.0, 0.82, 0.0)),
                           bb.add(new RoadGeometry.V(0.0, 0.82, 0.0)),
                           Math.max(0.1, arm.median() - 0.85),
                           0.25,
                           false,
                           RoadStructures.Material.GREEN
                        )
                     );

                     for (int side : new int[]{-1, 1}) {
                        parts.add(
                           new RoadStructures.Part(
                              a.at(divider + (double)side * (arm.median() / 2.0 - 0.05), 0.0),
                              RoadStructures.sample(mesh, Math.min(b.distance(), medianEnd)).at(divider + (double)side * (arm.median() / 2.0 - 0.05), 0.0),
                              0.2,
                              0.35,
                              false,
                              RoadStructures.Material.CONCRETE
                           )
                        );
                     }
                  } else {
                     RoadStructures.barrier(parts, aa, bb, false, arm.external().structure() == RoadGeometry.Structure.BRIDGE, a.distance());
                  }
               }
            }

            if (!(a.distance() >= stop - 0.225)) {
               b = RoadStructures.sample(mesh, Math.min(b.distance(), stop - 0.225));

               for (boolean in : new boolean[]{true, false}) {
                  int count = in ? arm.incoming() : arm.outgoing();

                  for (int lane = 1; lane < count; lane++) {
                     double offset = divider + (double)(in ? 1 : -1) * sign * (arm.median() / 2.0 + (double)lane * arm.laneWidth());
                     if (a.distance() > stop - 20.0 || ((int)(a.distance() / 3.0) & 1) == 0) {
                        JunctionPaint.line(
                           paint,
                           a.at(seamDivider(arm, mesh, a, offset, version), -0.013),
                           b.at(seamDivider(arm, mesh, b, offset, version), -0.013),
                           0.15,
                           15593186
                        );
                     }
                  }
               }

               if (!raised && arm.incoming() > 0 && arm.outgoing() > 0 && arm.median() < 0.2) {
                  for (double side : new double[]{-0.14, 0.14}) {
                     JunctionPaint.line(paint, a.at(divider + side, -0.013), b.at(divider + side, -0.013), 0.13, 16433462);
                  }
               }
            }
         }

         if (version < 47 && !raised && arm.median() > 0.2 && arm.medianKind() == RoadProfile.Median.GREEN) {
            RoadGeometry.Sample cap = RoadStructures.sample(mesh, medianEnd);
            parts.add(
               new RoadStructures.Part(
                  cap.at(divider - arm.median() / 2.0 + 0.05, 0.0),
                  cap.at(divider + arm.median() / 2.0 - 0.05, 0.0),
                  0.2,
                  0.35,
                  false,
                  RoadStructures.Material.CONCRETE
               )
            );
         }

         if (arm.crosswalk()) {
            RoadGeometry.Sample at = RoadStructures.sample(mesh, crossing);
            RoadGeometry.V f = at.left().left().mul(-1.0);
            double min = Math.min(arm.motorEdge(true, s.leftTraffic()), arm.motorEdge(false, s.leftTraffic()));
            double max = Math.max(arm.motorEdge(true, s.leftTraffic()), arm.motorEdge(false, s.leftTraffic()));
            if (version >= 41 && arm.cycleWidth() > 0.0) {
               min = -at.halfWidth() + 0.25;
               max = at.halfWidth() - 0.25;
            }

            for (double lateral = min + 0.35; lateral < max - 0.25; lateral++) {
               JunctionPaint.line(
                  paint,
                  at.at(lateral, -0.016).sub(f.mul(arm.crossingWidth() / 2.0)),
                  at.at(lateral, -0.016).add(f.mul(arm.crossingWidth() / 2.0)),
                  0.7,
                  15593186
               );
            }
         }

         RoadGeometry.Sample at = RoadStructures.sample(mesh, stop);
         if (arm.incoming() > 0) {
            if (s.control() == JunctionSpec.Control.YIELD) {
               double innerx = divider + sign * arm.median() / 2.0;
               double outerx = arm.motorEdge(true, s.leftTraffic());
               RoadGeometry.V backwards = at.left().left();

               for (double offset = Math.min(innerx, outerx) + 0.65; offset < Math.max(innerx, outerx) - 0.5; offset++) {
                  paint.add(
                     new RoadSurface.Face(
                        List.of(at.at(offset - 0.42, -0.017), at.at(offset + 0.42, -0.017), at.at(offset, -0.017).add(backwards.mul(1.2))), 15593186
                     )
                  );
               }
            } else {
               JunctionPaint.line(
                  paint, at.at(divider + sign * arm.median() / 2.0, -0.017), at.at(arm.motorEdge(true, s.leftTraffic()), -0.017), 0.45, 15593186
               );
            }

            if (version < 24) {
               for (int lanex = 0; lanex < arm.incoming(); lanex++) {
                  for (double back : new double[]{Math.min(7.0, Math.max(2.0, stop - 3.0)), 19.0}) {
                     if (stop >= back + 2.5) {
                        RoadGeometry.Sample arrow = RoadStructures.sample(mesh, stop - back);
                        JunctionPaint.arrow(
                           paint,
                           arrow.at(arm.laneCenter(true, lanex, s.leftTraffic()), -0.02),
                           arrow.left().left().mul(-1.0),
                           mask(s, i, lanex),
                           Math.min(1.0, arm.laneWidth() / 3.6)
                        );
                     }
                  }
               }
            } else {
               for (int lanex = 0; lanex < arm.incoming(); lanex++) {
                  double scale = Math.min(1.0, (arm.laneWidth() - 0.5) / 3.8);
                  double front = 2.65 * scale + 0.6;
                  double backx = Math.max(front, Math.min(7.0, stop - 2.1 * scale - 0.4));

                  for (double setback : !singleArrowRow && (version < 49 || !JunctionSlips.enabled(s)) && (version < 45 || s.arms().size() <= 4)
                     ? new double[]{backx, 19.0}
                     : new double[]{backx}) {
                     double station = stop - setback;
                     if (!(station < 2.1 * scale + 0.35) && !(setback < front)) {
                        RoadGeometry.Sample sample = RoadStructures.sample(mesh, station);
                        ArrayList<RoadSurface.Face> arrow = new ArrayList<>();
                        JunctionPaint.arrow(
                           arrow, sample.at(arm.laneCenter(true, lanex, s.leftTraffic()), -0.02), sample.left().left().mul(-1.0), mask(s, i, lanex), scale
                        );
                        boolean fits = arrow.stream().flatMap(f -> f.points().stream()).allMatch(px -> {
                           RoadQueries.Projection q = RoadQueries.horizontal(mesh, px);
                           double w = q.sample().halfWidth();
                           return q.sample().distance() <= stop - 0.35 && Math.abs(q.lateral()) < w - arm.curbWidth() - arm.cycleWidth() - 0.2;
                        });
                        if (fits) {
                           paint.addAll(arrow);
                        }
                     }
                  }
               }
            }
         }

         return new JunctionPlanner.Piece(mesh, List.copyOf(paint), List.copyOf(parts), i);
      }
   }

   private static RoadGeometry.Mesh triangle(List<RoadGeometry.V> p, double thickness) {
      RoadGeometry.V a = p.get(0);
      RoadGeometry.V b = p.get(1);
      RoadGeometry.V c = p.get(2);
      RoadGeometry.V mid = b.add(c).mul(0.5);
      RoadGeometry.V side = c.sub(b).horizontalUnit();
      double length = a.distance(mid);
      double half = b.distance(c) / 2.0;
      RoadGeometry.V min = new RoadGeometry.V(
         p.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(), a.y() - thickness, p.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow()
      );
      RoadGeometry.V max = new RoadGeometry.V(
         p.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(), a.y(), p.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()
      );
      return new RoadGeometry.Mesh(
         List.of(new RoadGeometry.Sample(a, side, 0.0, 0.0), new RoadGeometry.Sample(mid, side, length, half)),
         settings(Math.min(64.0, Math.max(2.0, half * 2.0)), thickness),
         min,
         max,
         length,
         false
      );
   }

   private JunctionPlanner() {
   }

   public static record Movement(int from, int lane, int to, int targetLane, int turn, List<RoadGeometry.V> path) {
   }

   public static record Piece(RoadGeometry.Mesh mesh, List<RoadSurface.Face> paint, List<RoadStructures.Part> structures, int arm) {
   }

   public static record Plan(
      List<JunctionPlanner.Piece> pieces,
      List<JunctionPlanner.Movement> movements,
      List<RoadGeometry.V> boundary,
      Map<RoadSidewalks.Cell, RoadSidewalks.Config> sidewalks,
      JunctionSignalCycle signals,
      Map<RoadStructures.Part, JunctionSignals.Head> heads
   ) {
   }

   private static record PlanKey(JunctionSpec spec, int version) {
   }

   public static final class Ref {
      private final JunctionSpec spec;
      private final int piece;
      private final int geometryVersion;
      private final JunctionSlipProfiles slipProfiles;
      private final List<OneSidedJunction.Cut> hostCuts;
      private final List<RoadGeometry.Mesh> approaches;
      private final List<RoadGeometry.Mesh> slips;
      private final List<RoadGeometry.Mesh> refuges;
      private final JunctionPlanner.Piece resolved;
      private final List<RoadGeometry.V> boundary;
      private final JunctionSignalCycle signals;
      private final Map<RoadStructures.Part, JunctionSignals.Head> heads;
      private final Map<RoadSidewalks.Cell, RoadSidewalks.Config> sidewalks;

      public Ref(JunctionSpec spec, int piece) {
         this(spec, piece, 55);
      }

      public Ref(JunctionSpec spec, int piece, int version) {
         this(spec, piece, version, List.of());
      }

      public Ref(JunctionSpec spec, int piece, int version, List<OneSidedJunction.Cut> hostCuts) {
         if (version >= 21 && version <= 55 && piece >= 0) {
            if (version >= 50) {
               spec = spec.supportedCrossings();
            }

            JunctionPlanner.Plan plan = JunctionPlanner.plan(spec, version);
            if (piece >= plan.pieces().size()) {
               throw new IllegalArgumentException("路口分段无效");
            } else {
               this.slipProfiles = version >= 49 && JunctionSlips.enabled(spec)
                  ? new JunctionSlipProfiles(
                     spec,
                     JunctionPlanner.plan(spec, 47).pieces().subList(0, spec.arms().size()).stream().map(JunctionPlanner.Piece::mesh).toList(),
                     version >= 52,
                     version >= 54
                  )
                  : null;
               this.approaches = plan.pieces().subList(0, spec.arms().size()).stream().map(JunctionPlanner.Piece::mesh).toList();
               this.slips = JunctionSlips.meshes(plan);
               this.refuges = plan.pieces().stream().filter(p -> p.arm() == -1).map(JunctionPlanner.Piece::mesh).toList();
               this.hostCuts = List.copyOf(hostCuts);
               if (hostCuts.isEmpty() || version >= 54 && spec.oneSide() != null && plan.pieces().get(piece).arm() == -3) {
                  this.spec = spec;
                  this.piece = piece;
                  this.geometryVersion = version;
                  this.resolved = hostCuts.isEmpty() ? plan.pieces().get(piece) : OneSidedJunction.retained(spec.oneSide(), hostCuts);
                  this.boundary = plan.boundary();
                  this.signals = plan.signals();
                  this.heads = plan.heads();
                  this.sidewalks = plan.sidewalks();
               } else {
                  throw new IllegalArgumentException("共享主路裁剪数据无效");
               }
            }
         } else {
            throw new IllegalArgumentException("路口分段无效");
         }
      }

      public JunctionSpec spec() {
         return this.spec;
      }

      public int piece() {
         return this.piece;
      }

      public int geometryVersion() {
         return this.geometryVersion;
      }

      public List<OneSidedJunction.Cut> hostCuts() {
         return this.hostCuts;
      }

      public RoadGeometry.Mesh approach(int arm) {
         return this.approaches.get(arm);
      }

      public RoadGeometry.Mesh furnitureApproach(int arm) {
         return this.slipProfiles == null ? this.approach(arm) : this.slipProfiles.approaches.get(arm);
      }

      public List<RoadStructures.Part> approachParts(List<RoadStructures.Part> parts) {
         return this.slipProfiles != null && this.resolved.arm() >= 0
            ? parts.stream().map(p -> this.slipProfiles.part(this.resolved.arm(), p)).toList()
            : parts;
      }

      public List<RoadGeometry.Mesh> slips() {
         return this.slips;
      }

      public List<RoadGeometry.Mesh> refuges() {
         return this.refuges;
      }

      public List<RoadGeometry.Mesh> replacedCycleStrips() {
         return this.slipProfiles == null ? List.of() : this.slipProfiles.refugeStrips();
      }

      public JunctionPlanner.Piece get() {
         return this.resolved;
      }

      public List<RoadGeometry.V> boundary() {
         return this.boundary;
      }

      public JunctionSignalCycle signals() {
         return this.signals;
      }

      public Map<RoadSidewalks.Cell, RoadSidewalks.Config> sidewalks() {
         return this.sidewalks;
      }

      public int headState(RoadStructures.Part part, long time) {
         if (part.material() == RoadStructures.Material.SIGNAL_PEDESTRIAN) {
            return this.signals.pedestrian(this.resolved.arm(), time);
         } else {
            JunctionSignals.Head binding = this.headBinding(part);
            return binding == null
               ? this.signals.vehicle(this.resolved.arm(), time)
               : this.signals.vehicle(binding.arm(), binding.lane(), binding.left(), time);
         }
      }

      private JunctionSignals.Head headBinding(RoadStructures.Part part) {
         JunctionSignals.Head exact = this.heads.get(part);
         if (exact != null) {
            return exact;
         } else {
            for (Entry<RoadStructures.Part, JunctionSignals.Head> e : this.heads.entrySet()) {
               RoadStructures.Part p = e.getKey();
               if (p.material() == part.material() && p.a().equals(part.a()) && p.b().equals(part.b())) {
                  return e.getValue();
               }
            }

            return null;
         }
      }

      public RoadStructures.Part headModel(RoadStructures.Part part) {
         return part;
      }

      @Override
      public boolean equals(Object other) {
         if (other instanceof JunctionPlanner.Ref r
            && this.piece == r.piece
            && this.geometryVersion == r.geometryVersion
            && this.spec.equals(r.spec)
            && this.hostCuts.equals(r.hostCuts)) {
            return true;
         }

         return false;
      }

      @Override
      public int hashCode() {
         return Objects.hash(this.spec, this.piece, this.geometryVersion, this.hostCuts);
      }
   }
}
