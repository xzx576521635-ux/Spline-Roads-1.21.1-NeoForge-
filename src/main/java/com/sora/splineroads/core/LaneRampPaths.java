package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class LaneRampPaths {
   public static List<LaneRampPaths.Candidate> smoothTurns(
      LaneRampPaths.Port a, LaneRampPaths.Port b, RoadGeometry.Settings settings, LanePoints.Options options, double maxGrade
   ) {
      return smoothTurns(a, b, settings, options, maxGrade, Math.abs(a.position().y() - b.position().y()) > 0.5);
   }

   public static List<LaneRampPaths.Candidate> smoothTurns(
      LaneRampPaths.Port a, LaneRampPaths.Port b, RoadGeometry.Settings settings, LanePoints.Options options, double maxGrade, boolean layerChange
   ) {
      LaneRampGrade.checked(maxGrade);
      if (LaneRampGrade.exceeds(a.grade(), 1.0, maxGrade) || LaneRampGrade.exceeds(b.grade(), 1.0, maxGrade)) {
         return List.of();
      } else if (!options.sourceExtra() && !options.targetExtra()) {
         RoadGeometry.V delta = b.position().sub(a.position());
         double distance = delta.horizontalLength();
         if (!(distance < 4.0) && !(distance > 2048.0)) {
            ArrayList<LaneRampPaths.Candidate> out = new ArrayList<>();
            ArrayList<LaneRampPaths.Candidate> continuousReturns = new ArrayList<>();
            ArrayList<LaneRampPaths.Candidate> simpleReturns = new ArrayList<>();
            boolean returning = delta.dot(a.direction()) < 0.0 || delta.dot(b.direction()) < 0.0;
            if (!returning && layerChange && a.direction().dot(b.direction()) > 0.5 && a.outside().dot(b.outside()) > 0.5) {
               for (double side : new double[]{1.0, -1.0}) {
                  for (double offset : new double[]{3.0, 2.0, 1.0, 5.0}) {
                     for (double fraction : new double[]{0.55, 0.4, 0.7}) {
                        try {
                           RoadPlanningBudget.check();
                           ArrayList<LaneRampPaths.Frame> frames = new ArrayList<>();
                           double handle = distance * fraction;
                           RoadGeometry.V[] controls = new RoadGeometry.V[]{
                              a.position(),
                              a.position().add(a.direction().mul(handle / 2.0)),
                              a.position().add(a.direction().mul(handle)).add(a.outside().mul(side * offset * settings.width())),
                              b.position().sub(b.direction().mul(handle)).add(b.outside().mul(side * offset * settings.width())),
                              b.position().sub(b.direction().mul(handle / 2.0)),
                              b.position()
                           };
                           quintic(frames, controls);
                           RoadGeometry.Mesh mesh = finish(a, b, settings, frames, 0.0, maxGrade);
                           if (RoadRibbon.minRadius(mesh) + 0.001 >= options.radius()) {
                              out.add(new LaneRampPaths.Candidate(LanePoints.Path.AUTO, mesh));
                           }
                        } catch (IllegalArgumentException var41) {
                        }
                     }
                  }
               }

               for (double offset : new double[]{settings.width(), settings.width() * 2.0, settings.width() * 3.0, settings.width() * 5.0}) {
                  for (double fraction : new double[]{0.22, 0.32, 0.42, 0.48, 0.12}) {
                     try {
                        RoadPlanningBudget.check();
                        ArrayList<LaneRampPaths.Frame> frames = new ArrayList<>();
                        RoadGeometry.V first = a.position().add(a.direction().mul(distance * fraction)).add(a.outside().mul(offset));
                        RoadGeometry.V last = b.position().sub(b.direction().mul(distance * fraction)).add(b.outside().mul(offset));
                        quintic(frames, a.position(), a.direction(), first, a.direction(), distance * fraction * 0.55);
                        quintic(frames, first, a.direction(), last, b.direction(), first.sub(last).horizontalLength() * 0.55);
                        quintic(frames, last, b.direction(), b.position(), b.direction(), distance * fraction * 0.55);
                        RoadGeometry.Mesh mesh = finish(a, b, settings, frames, 0.0, maxGrade);
                        if (RoadRibbon.minRadius(mesh) + 0.001 >= options.radius()) {
                           out.add(new LaneRampPaths.Candidate(LanePoints.Path.AUTO, mesh));
                        }
                     } catch (IllegalArgumentException var40) {
                     }
                  }
               }
            }

            if (!returning) {
               for (double fraction : new double[]{0.6, 0.85, 1.1}) {
                  try {
                     ArrayList<LaneRampPaths.Frame> frames = new ArrayList<>();
                     quintic(frames, a.position(), a.direction(), b.position(), b.direction(), distance * fraction);
                     RoadGeometry.Mesh mesh = finish(a, b, settings, frames, 0.0, maxGrade);
                     if (RoadRibbon.minRadius(mesh) + 0.001 >= options.radius()) {
                        out.add(new LaneRampPaths.Candidate(LanePoints.Path.AUTO, mesh));
                     }
                  } catch (IllegalArgumentException var39) {
                  }
               }
            }

            if (returning) {
               for (double scale : new double[]{0.16, 0.22, 0.3, 0.4}) {
                  for (int side : new int[]{1, -1}) {
                     try {
                        RoadPlanningBudget.check();
                        double radius = Math.max(options.radius() * 1.25, distance * scale);
                        List<LaneRampPaths.Frame> rounded = fairTurns(tangentReturn(a, b, radius, side), Math.min(16.0, radius * 0.5), true);
                        RoadGeometry.Mesh mesh = finish(a, b, settings, rounded, 0.0, maxGrade);
                        if (RoadRibbon.minRadius(mesh) + 0.001 >= options.radius()) {
                           simpleReturns.add(new LaneRampPaths.Candidate(LanePoints.Path.AUTO, mesh));
                        }
                     } catch (IllegalArgumentException var38) {
                     }
                  }
               }

               for (double scale : new double[]{0.16, 0.22, 0.3, 0.4}) {
                  for (int side : new int[]{1, -1}) {
                     for (int bend : new int[]{1, -1}) {
                        try {
                           double radius = Math.max(options.radius(), distance * scale);

                           for (double generous : new double[]{1.25, 1.4, 1.6}) {
                              try {
                                 List<LaneRampPaths.Frame> rounded = fairTurns(roundReturn(a, b, radius * generous, side, bend), Math.min(16.0, radius * 0.5));
                                 RoadGeometry.Mesh continuous = finish(a, b, settings, rounded, 0.0, maxGrade);
                                 if (RoadRibbon.minRadius(continuous) + 0.001 >= options.radius()) {
                                    continuousReturns.add(new LaneRampPaths.Candidate(LanePoints.Path.AUTO, continuous));
                                 }
                              } catch (IllegalArgumentException var37) {
                              }
                           }

                           RoadGeometry.Mesh mesh = finish(a, b, settings, roundReturn(a, b, radius, side, bend), 0.0, maxGrade);
                           if (RoadRibbon.minRadius(mesh) + 0.001 >= options.radius()) {
                              out.add(new LaneRampPaths.Candidate(LanePoints.Path.AUTO, mesh));
                           }
                        } catch (IllegalArgumentException var43) {
                        }
                     }
                  }
               }

               out.sort(Comparator.comparingDouble(c -> c.mesh().length()));
            }

            for (double fraction : returning ? new double[]{0.7, 1.1, 1.6} : new double[]{0.55, 0.4, 0.7}) {
               for (double ratio : new double[]{1.0, 0.55, 1.8}) {
                  try {
                     RoadPlanningBudget.check();
                     ArrayList<LaneRampPaths.Frame> frames = new ArrayList<>();
                     bezier(
                        frames,
                        a.position(),
                        a.direction(),
                        b.position(),
                        b.direction(),
                        Math.max(options.radius(), distance * fraction),
                        Math.max(options.radius(), distance * fraction * ratio)
                     );
                     RoadGeometry.Mesh mesh = finish(a, b, settings, frames, 0.0, maxGrade);
                     if (!(RoadRibbon.minRadius(mesh) + 0.001 < options.radius())) {
                        out.add(new LaneRampPaths.Candidate(LanePoints.Path.AUTO, mesh));
                     }
                  } catch (IllegalArgumentException var36) {
                  }
               }
            }

            if (returning) {
               out.sort(Comparator.comparingDouble(c -> c.mesh().length()));
               double shortest = out.isEmpty() ? Double.POSITIVE_INFINITY : out.get(0).mesh().length();
               continuousReturns.sort(Comparator.comparingDouble(c -> c.mesh().length()));
               out.addAll(0, continuousReturns.stream().filter(c -> c.mesh().length() <= shortest * 1.6).toList());
               simpleReturns.sort(Comparator.comparingDouble(c -> c.mesh().length()));
               out.addAll(0, simpleReturns.stream().filter(c -> c.mesh().length() <= shortest * 1.6).toList());
            }

            return out;
         } else {
            return List.of();
         }
      } else {
         List<RoadGeometry.Sample> prefix = options.sourceExtra() ? approach(a, true, settings.width(), options.transition()) : List.of();
         List<RoadGeometry.Sample> suffix = options.targetExtra() ? approach(b, false, settings.width(), options.transition()) : List.of();
         LaneRampPaths.Port start = prefix.isEmpty() ? a : approachPort(prefix, false, a);
         LaneRampPaths.Port end = suffix.isEmpty() ? b : approachPort(suffix, true, b);
         ArrayList<LaneRampPaths.Candidate> outx = new ArrayList<>();

         for (LaneRampPaths.Candidate c : smoothTurns(start, end, settings, options.withoutApproaches(), maxGrade, layerChange)) {
            try {
               ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
               append(samples, prefix);
               append(samples, c.mesh().samples());
               append(samples, suffix);
               RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, settings);
               LaneRampGrade.validate(mesh, maxGrade);
               RoadRibbon.checkSelfIntersections(mesh, 4.0);
               checkVolume(mesh);
               outx.add(new LaneRampPaths.Candidate(c.path(), mesh));
            } catch (IllegalArgumentException var42) {
            }
         }

         return outx;
      }
   }

   private static void quintic(List<LaneRampPaths.Frame> out, RoadGeometry.V a, RoadGeometry.V da, RoadGeometry.V b, RoadGeometry.V db, double handle) {
      RoadGeometry.V[] p = new RoadGeometry.V[]{a, a.add(da.mul(handle / 2.0)), a.add(da.mul(handle)), b.sub(db.mul(handle)), b.sub(db.mul(handle / 2.0)), b};
      quintic(out, p);
   }

   private static void quintic(List<LaneRampPaths.Frame> out, RoadGeometry.V[] p) {
      polynomial(out, p);
   }

   private static void polynomial(List<LaneRampPaths.Frame> out, RoadGeometry.V[] p) {
      double length = 0.0;

      for (int i = 1; i < p.length; i++) {
         length += p[i].sub(p[i - 1]).horizontalLength();
      }

      int count = Math.max(16, (int)Math.ceil(length / 0.5));

      for (int i = 0; i <= count; i++) {
         double t = (double)i / (double)count;
         double u = 1.0 - t;
         RoadGeometry.V[] q = (RoadGeometry.V[])p.clone();

         for (int n = p.length - 1; n > 1; n--) {
            for (int j = 0; j < n; j++) {
               q[j] = q[j].mul(u).add(q[j + 1].mul(t));
            }
         }

         RoadGeometry.V direction = q[1].sub(q[0]);
         if (direction.horizontalLength() < 1.0E-8) {
            throw new IllegalArgumentException("平滑曲线折返");
         }

         add(out, q[0].mul(u).add(q[1].mul(t)), direction.horizontalUnit());
      }
   }

   private static List<LaneRampPaths.Frame> roundReturn(LaneRampPaths.Port a, LaneRampPaths.Port b, double radius, int side, int bend) {
      RoadGeometry.V ca = a.position().add(a.direction().left().mul((double)side * radius));
      RoadGeometry.V cb = b.position().add(b.direction().left().mul((double)side * radius));
      RoadGeometry.V delta = cb.sub(ca);
      delta = new RoadGeometry.V(delta.x(), 0.0, delta.z());
      double distance = delta.horizontalLength();
      if (!(distance < 1.0E-7) && !(distance >= 4.0 * radius - 1.0E-7)) {
         RoadGeometry.V middle = ca.add(delta.mul(0.5))
            .add(delta.horizontalUnit().left().mul((double)bend * Math.sqrt(4.0 * radius * radius - distance * distance / 4.0)));
         RoadGeometry.V j1 = ca.add(middle).mul(0.5);
         RoadGeometry.V j2 = cb.add(middle).mul(0.5);
         RoadGeometry.V d1 = j1.sub(ca).left().mul((double)side).horizontalUnit();
         RoadGeometry.V d2 = j2.sub(middle).left().mul((double)(-side)).horizontalUnit();
         ArrayList<LaneRampPaths.Frame> frames = new ArrayList<>();
         circular(frames, ca, radius, a.direction(), d1, side);
         circular(frames, middle, radius, d1, d2, -side);
         circular(frames, cb, radius, d2, b.direction(), side);
         return frames;
      } else {
         throw new IllegalArgumentException("回环圆弧空间不足");
      }
   }

   private static List<LaneRampPaths.Frame> tangentReturn(LaneRampPaths.Port a, LaneRampPaths.Port b, double radius, int side) {
      RoadGeometry.V ca = a.position().add(a.direction().left().mul((double)side * radius));
      RoadGeometry.V cb = b.position().add(b.direction().left().mul((double)side * radius));
      RoadGeometry.V delta = cb.sub(ca);
      ArrayList<LaneRampPaths.Frame> frames = new ArrayList<>();
      if (delta.horizontalLength() < 1.0E-7) {
         circular(frames, ca, radius, a.direction(), b.direction(), side);
         return frames;
      } else {
         RoadGeometry.V tangent = delta.horizontalUnit();
         circular(frames, ca, radius, a.direction(), tangent, side);
         line(frames, ca.sub(tangent.left().mul((double)side * radius)), cb.sub(tangent.left().mul((double)side * radius)), tangent);
         circular(frames, cb, radius, tangent, b.direction(), side);
         return frames;
      }
   }

   private static List<LaneRampPaths.Frame> fairTurns(List<LaneRampPaths.Frame> frames, double reach) {
      return fairTurns(frames, reach, false);
   }

   private static List<LaneRampPaths.Frame> fairTurns(List<LaneRampPaths.Frame> frames, double reach, boolean sameTurn) {
      int n = frames.size();
      double[] distance = new double[n];
      double[] curvature = new double[n];

      for (int i = 1; i < n; i++) {
         LaneRampPaths.Frame a = frames.get(i - 1);
         LaneRampPaths.Frame b = frames.get(i);
         double length = b.p().sub(a.p()).horizontalLength();
         distance[i] = distance[i - 1] + length;
         curvature[i] = Math.atan2(a.d().x() * b.d().z() - a.d().z() * b.d().x(), a.d().dot(b.d())) / length;
      }

      ArrayList<int[]> windows = new ArrayList<>();

      for (int i = 0; i < n; i++) {
         if (i == 0 || i == n - 1 || Math.abs(curvature[i] - curvature[i + 1]) > 0.005) {
            int a = i;
            int b = i;

            while (a > 0 && distance[i] - distance[a] < reach) {
               a--;
            }

            while (b < n - 1 && distance[b] - distance[i] < reach) {
               b++;
            }

            if (!windows.isEmpty() && a <= windows.get(windows.size() - 1)[1]) {
               windows.get(windows.size() - 1)[1] = b;
            } else {
               windows.add(new int[]{a, b});
            }
         }
      }

      ArrayList<LaneRampPaths.Frame> out = new ArrayList<>();
      int cursor = 0;

      for (int[] window : windows) {
         int a = window[0];
         int b = window[1];

         while (cursor < a) {
            LaneRampPaths.Frame f = frames.get(cursor++);
            add(out, f.p(), f.d());
         }

         LaneRampPaths.Frame first = frames.get(a);
         LaneRampPaths.Frame last = frames.get(b);
         double length = distance[b] - distance[a];
         RoadGeometry.V p = first.p();
         RoadGeometry.V q = last.p();
         RoadGeometry.V u = first.d();
         RoadGeometry.V v = last.d();
         double ka = a == 0 ? 0.0 : curvature[a];
         double kb = b == n - 1 ? 0.0 : curvature[b + 1];
         if (sameTurn) {
            convexBlend(out, p, u, q, v, ka, kb);
            cursor = b + 1;
         } else {
            RoadGeometry.V p1 = p.add(u.mul(length / 5.0));
            RoadGeometry.V p2 = p.add(u.mul(2.0 * length / 5.0)).add(u.left().mul(ka * length * length / 20.0));
            RoadGeometry.V p4 = q.sub(v.mul(length / 5.0));
            RoadGeometry.V p3 = q.sub(v.mul(2.0 * length / 5.0)).add(v.left().mul(kb * length * length / 20.0));
            quintic(out, new RoadGeometry.V[]{p, p1, p2, p3, p4, q});
            cursor = b + 1;
         }
      }

      while (cursor < n) {
         LaneRampPaths.Frame f = frames.get(cursor++);
         add(out, f.p(), f.d());
      }

      return out;
   }

   private static void convexBlend(List<LaneRampPaths.Frame> out, RoadGeometry.V p, RoadGeometry.V u, RoadGeometry.V q, RoadGeometry.V v, double ka, double kb) {
      double cross = u.x() * v.z() - u.z() * v.x();
      RoadGeometry.V delta = q.sub(p);
      if (Math.abs(cross) < 1.0E-8) {
         line(out, p, q, u);
      } else {
         double alpha = (delta.x() * v.z() - delta.z() * v.x()) / cross;
         double beta = (u.x() * delta.z() - u.z() * delta.x()) / cross;
         if (!(alpha <= 0.0) && !(beta <= 0.0)) {
            double ha;
            double hb;
            if (Math.abs(ka) < 1.0E-8) {
               hb = beta;
               ha = alpha - 1.5 * kb * beta * beta / cross;
            } else if (Math.abs(kb) < 1.0E-8) {
               ha = alpha;
               hb = beta - 1.5 * ka * alpha * alpha / cross;
            } else {
               ha = alpha * 0.75;
               hb = beta * 0.75;
            }

            if (!(ha <= 0.0) && !(hb <= 0.0) && !(ha > alpha + 1.0E-6) && !(hb > beta + 1.0E-6)) {
               bezier(out, p, u, q, v, ha, hb);
            } else {
               throw new IllegalArgumentException("同向回环曲率接头不足");
            }
         } else {
            throw new IllegalArgumentException("同向回环平滑空间不足");
         }
      }
   }

   private static void circular(List<LaneRampPaths.Frame> frames, RoadGeometry.V center, double radius, RoadGeometry.V from, RoadGeometry.V to, int side) {
      double turn = positive((double)side * (angle(to) - angle(from)));
      if (side > 0) {
         arc(frames, center, radius, angle(from), turn);
      } else {
         arcLeft(frames, center, radius, angle(from), turn);
      }
   }

   public static List<LaneRampPaths.Candidate> automaticTurns(
      LaneRampPaths.Port a, LaneRampPaths.Port b, RoadGeometry.Settings settings, LanePoints.Options options, double maxGrade
   ) {
      LaneRampGrade.checked(maxGrade);
      if (a.position().sub(b.position()).horizontalLength() > 2048.0) {
         throw new IllegalArgumentException("匝道端点距离不能超过 2048 格");
      } else if (!LaneRampGrade.exceeds(a.grade(), 1.0, maxGrade) && !LaneRampGrade.exceeds(b.grade(), 1.0, maxGrade)) {
         if (!options.sourceExtra() && !options.targetExtra()) {
            ArrayList<LaneRampPaths.Candidate> out = new ArrayList<>();
            double r = options.radius();
            RoadGeometry.V pa = a.position().add(a.direction().mul(options.transition()));
            RoadGeometry.V pb = b.position().sub(b.direction().mul(options.transition()));

            for (int first : new int[]{1, -1}) {
               for (int last : new int[]{1, -1}) {
                  try {
                     RoadPlanningBudget.check();
                     ArrayList<LaneRampPaths.Frame> frames = new ArrayList<>();
                     RoadGeometry.V ca = pa.add(a.direction().left().mul((double)first * r));
                     RoadGeometry.V cb = pb.add(b.direction().left().mul((double)last * r));
                     RoadGeometry.V delta = cb.sub(ca);
                     double length = delta.horizontalLength();
                     double offset = (double)(first - last) * r;
                     if (!(length < 1.0E-7) && !(length <= Math.abs(offset) + 1.0E-7)) {
                        double heading = angle(delta) + Math.asin(offset / length);
                        RoadGeometry.V tangent = new RoadGeometry.V(Math.cos(heading), 0.0, Math.sin(heading));
                        double turnA = positive((double)first * (heading - angle(a.direction())));
                        double turnB = positive((double)last * (angle(b.direction()) - heading));
                        line(frames, a.position(), pa, a.direction());
                        if (first > 0) {
                           arc(frames, ca, r, angle(a.direction()), turnA);
                        } else {
                           arcLeft(frames, ca, r, angle(a.direction()), turnA);
                        }

                        RoadGeometry.V ta = ca.sub(tangent.left().mul((double)first * r));
                        RoadGeometry.V tb = cb.sub(tangent.left().mul((double)last * r));
                        line(frames, ta, tb, tangent);
                        if (last > 0) {
                           arc(frames, cb, r, heading, turnB);
                        } else {
                           arcLeft(frames, cb, r, heading, turnB);
                        }

                        line(frames, pb, b.position(), b.direction());
                        out.add(new LaneRampPaths.Candidate(LanePoints.Path.AUTO, finish(a, b, settings, frames, 0.0, maxGrade)));
                     }
                  } catch (IllegalArgumentException var36) {
                  }
               }
            }

            out.sort(Comparator.comparingDouble(c -> c.mesh().length()));
            return List.copyOf(out);
         } else {
            List<RoadGeometry.Sample> prefix = options.sourceExtra() ? approach(a, true, settings.width(), options.transition()) : List.of();
            List<RoadGeometry.Sample> suffix = options.targetExtra() ? approach(b, false, settings.width(), options.transition()) : List.of();
            LaneRampPaths.Port start = prefix.isEmpty() ? a : approachPort(prefix, false, a);
            LaneRampPaths.Port end = suffix.isEmpty() ? b : approachPort(suffix, true, b);
            ArrayList<LaneRampPaths.Candidate> out = new ArrayList<>();

            for (LaneRampPaths.Candidate candidate : automaticTurns(start, end, settings, options.withoutApproaches(), maxGrade)) {
               try {
                  ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
                  append(samples, prefix);
                  append(samples, candidate.mesh().samples());
                  append(samples, suffix);
                  RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, settings);
                  RoadRibbon.checkSelfIntersections(mesh, 4.0);
                  checkVolume(mesh);
                  out.add(new LaneRampPaths.Candidate(candidate.path(), mesh));
               } catch (IllegalArgumentException var37) {
               }
            }

            return out;
         }
      } else {
         throw new IllegalArgumentException("端口坡度超过 " + LaneRampGrade.label(maxGrade) + "，不能破坏接缝来强行连接");
      }
   }

   public static List<LaneRampPaths.Candidate> candidates(
      LaneRampPaths.Port a, LaneRampPaths.Port b, RoadGeometry.Settings settings, LanePoints.Options options
   ) {
      return candidates(a, b, settings, options, 0.15);
   }

   public static List<LaneRampPaths.Candidate> candidates(
      LaneRampPaths.Port a, LaneRampPaths.Port b, RoadGeometry.Settings settings, LanePoints.Options options, double maxGrade
   ) {
      LaneRampGrade.checked(maxGrade);
      if (!options.sourceExtra() && !options.targetExtra()) {
         List<LaneRampPaths.Candidate> out = new ArrayList<>();
         EnumMap<LanePoints.Path, String> errors = new EnumMap<>(LanePoints.Path.class);

         for (LanePoints.Path kind : options.path() == LanePoints.Path.AUTO
            ? List.of(LanePoints.Path.DIRECT, LanePoints.Path.RIGHT, LanePoints.Path.LEFT, LanePoints.Path.LEFT_LOOP)
            : List.of(options.path())) {
            for (double lift : loopLifts(kind, options.elevation())) {
               try {
                  out.add(new LaneRampPaths.Candidate(kind, generate(a, b, settings, options, kind, lift, maxGrade)));
               } catch (IllegalArgumentException var16) {
                  errors.put(kind, var16.getMessage());
               }
            }
         }

         if (out.isEmpty()) {
            throw new IllegalArgumentException(failure(a, b, options, errors));
         } else {
            out.sort(Comparator.comparingDouble(c -> c.mesh().length()));
            return List.copyOf(out);
         }
      } else {
         return expanded(a, b, settings, options, maxGrade);
      }
   }

   private static double[] loopLifts(LanePoints.Path path, LanePoints.Elevation elevation) {
      if (path != LanePoints.Path.LEFT_LOOP || elevation == LanePoints.Elevation.KEEP) {
         return new double[]{0.0};
      } else if (elevation == LanePoints.Elevation.OVER) {
         return new double[]{0.0, 6.0, 8.0, 10.0, 12.0, 16.0, 24.0};
      } else {
         return elevation == LanePoints.Elevation.UNDER
            ? new double[]{0.0, -6.0, -8.0, -10.0, -12.0, -16.0, -24.0}
            : new double[]{0.0, 6.0, -6.0, 8.0, -8.0, 10.0, -10.0, 12.0, -12.0, 16.0, -16.0, 24.0, -24.0};
      }
   }

   private static List<LaneRampPaths.Candidate> expanded(
      LaneRampPaths.Port a, LaneRampPaths.Port b, RoadGeometry.Settings settings, LanePoints.Options o, double maxGrade
   ) {
      List<RoadGeometry.Sample> prefix = o.sourceExtra() ? approach(a, true, settings.width(), o.transition()) : List.of();
      List<RoadGeometry.Sample> suffix = o.targetExtra() ? approach(b, false, settings.width(), o.transition()) : List.of();
      LaneRampPaths.Port start = prefix.isEmpty() ? a : approachPort(prefix, false, a);
      LaneRampPaths.Port end = suffix.isEmpty() ? b : approachPort(suffix, true, b);
      LanePoints.Options plain = o.withoutApproaches();
      ArrayList<LaneRampPaths.Candidate> result = new ArrayList<>();
      String error = "扩出后的路径净空不足";

      for (double lead : o.targetExtra() ? new double[]{0.0, 64.0, 96.0, 128.0} : new double[]{0.0}) {
         try {
            RoadGeometry.V extension = end.direction().mul(lead).add(new RoadGeometry.V(0.0, end.grade() * lead, 0.0));
            LaneRampPaths.Port early = lead == 0.0
               ? end
               : new LaneRampPaths.Port(end.position().sub(extension), end.direction(), end.outside(), end.extraWidth(), end.grade());

            for (LaneRampPaths.Candidate c : candidates(start, early, settings, plain, maxGrade)) {
               try {
                  ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
                  append(samples, prefix);
                  append(samples, c.mesh().samples());
                  if (lead > 0.0) {
                     int count = (int)Math.ceil(lead / 0.5);
                     ArrayList<RoadGeometry.Sample> run = new ArrayList<>();

                     for (int i = 1; i <= count; i++) {
                        run.add(
                           new RoadGeometry.Sample(
                              early.position().add(extension.mul((double)i / (double)count)),
                              end.direction().left(),
                              lead * (double)i / (double)count,
                              settings.width() / 2.0
                           )
                        );
                     }

                     append(samples, run);
                  }

                  append(samples, suffix);
                  RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, settings);
                  LaneRampGrade.validate(mesh, maxGrade);
                  RoadRibbon.checkSelfIntersections(mesh, 4.0);
                  checkVolume(mesh);
                  result.add(new LaneRampPaths.Candidate(c.path(), mesh));
               } catch (IllegalArgumentException var29) {
                  error = var29.getMessage();
               }
            }
         } catch (IllegalArgumentException var30) {
            error = var30.getMessage();
         }
      }

      if (o.sourceExtra() && (o.path() == LanePoints.Path.DIRECT || o.path() == LanePoints.Path.AUTO)) {
         for (double lead : new double[]{16.0, 32.0, 48.0}) {
            try {
               if (!(end.position().sub(start.position()).dot(start.direction()) < lead + 8.0)) {
                  RoadGeometry.V later = start.position().add(start.direction().mul(lead));
                  LaneRampPaths.Port late = new LaneRampPaths.Port(later, start.direction(), start.outside(), start.extraWidth(), 0.0);
                  LaneRampPaths.Port flatEnd = new LaneRampPaths.Port(
                     new RoadGeometry.V(end.position().x(), later.y(), end.position().z()), end.direction(), end.outside(), end.extraWidth(), 0.0
                  );
                  LanePoints.Options direct = new LanePoints.Options(
                     LanePoints.Path.DIRECT,
                     LanePoints.Departure.BRANCH,
                     LanePoints.Arrival.MERGE,
                     o.radius(),
                     o.transition(),
                     o.elevation(),
                     o.landing(),
                     o.gradeOverride(),
                     o.allowTunnel()
                  );

                  for (LaneRampPaths.Candidate c : candidates(late, flatEnd, settings, direct, maxGrade)) {
                     ArrayList<LaneRampPaths.Frame> frames = new ArrayList<>();
                     line(frames, start.position(), later, start.direction());

                     for (RoadGeometry.Sample s : c.mesh().samples()) {
                        add(frames, s.center(), s.left().left().mul(-1.0));
                     }

                     RoadGeometry.Mesh free = finish(start, end, settings, frames, 0.0, maxGrade);
                     ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
                     append(samples, prefix);
                     append(samples, free.samples());
                     append(samples, suffix);
                     RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, settings);
                     LaneRampGrade.validate(mesh, maxGrade);
                     RoadRibbon.checkSelfIntersections(mesh, 4.0);
                     checkVolume(mesh);
                     result.add(new LaneRampPaths.Candidate(LanePoints.Path.DIRECT, mesh));
                  }
               }
            } catch (IllegalArgumentException var28) {
            }
         }
      }

      if (result.isEmpty()) {
         throw new IllegalArgumentException(error);
      } else {
         return List.copyOf(result);
      }
   }

   private static List<RoadGeometry.Sample> approach(LaneRampPaths.Port p, boolean source, double width, double transition) {
      if (!p.approach().isEmpty()) {
         return p.approach();
      } else {
         double total = LaneRampApproach.length(transition);
         int n = (int)Math.ceil(total / 0.5);
         ArrayList<RoadGeometry.Sample> out = new ArrayList<>();

         for (int i = 0; i <= n; i++) {
            double d = total * (double)i / (double)n;
            double q = source ? d : total - d;
            double grow = RoadGeometry.Settings.smooth(Math.min(1.0, q / LaneRampApproach.taper(transition)));
            RoadGeometry.V position = p.position().add(p.direction().mul(source ? d : d - total)).add(p.outside().mul(p.extraWidth() * grow));
            out.add(new RoadGeometry.Sample(position, p.direction().left(), d, width / 2.0));
         }

         return out;
      }
   }

   private static LaneRampPaths.Port approachPort(List<RoadGeometry.Sample> samples, boolean first, LaneRampPaths.Port p) {
      RoadGeometry.Sample s = samples.get(first ? 0 : samples.size() - 1);
      RoadGeometry.Sample a = samples.get(first ? 0 : samples.size() - 2);
      RoadGeometry.Sample b = samples.get(first ? 1 : samples.size() - 1);
      RoadGeometry.V d = b.center().sub(a.center());
      return new LaneRampPaths.Port(s.center(), s.left().left().mul(-1.0), p.outside(), p.extraWidth(), d.y() / Math.max(0.001, d.horizontalLength()));
   }

   private static void append(List<RoadGeometry.Sample> out, List<RoadGeometry.Sample> added) {
      for (RoadGeometry.Sample s : added) {
         double d = out.isEmpty() ? 0.0 : s.center().sub(out.get(out.size() - 1).center()).horizontalLength();
         if (out.isEmpty() || !(d < 1.0E-7)) {
            out.add(new RoadGeometry.Sample(s.center(), s.left(), out.isEmpty() ? 0.0 : out.get(out.size() - 1).distance() + d, s.halfWidth()));
         }
      }
   }

   public static String failure(LaneRampPaths.Port a, LaneRampPaths.Port b, LanePoints.Options options, Map<LanePoints.Path, String> errors) {
      LanePoints.Path preferred = options.path();
      if (preferred == LanePoints.Path.AUTO && errors.containsKey(LanePoints.Path.AUTO)) {
         return "自动回转方案：" + errors.get(LanePoints.Path.AUTO);
      } else {
         if (preferred == LanePoints.Path.AUTO) {
            double turn = positive(angle(b.direction()) - angle(a.direction()));
            preferred = a.direction().dot(b.direction()) > 0.75 ? LanePoints.Path.DIRECT : (turn <= Math.PI ? LanePoints.Path.RIGHT : LanePoints.Path.LEFT);
         }

         String error = errors.get(preferred);
         if (error == null) {
            return "自动未找到可用路径：" + String.join("；", errors.values());
         } else {
            String prefix = preferred.label + "：";

            while (error.startsWith(prefix)) {
               error = error.substring(prefix.length());
            }

            return (options.path() == LanePoints.Path.AUTO ? "自动 / " : "") + prefix + error;
         }
      }
   }

   private static RoadGeometry.Mesh generate(
      LaneRampPaths.Port a, LaneRampPaths.Port b, RoadGeometry.Settings settings, LanePoints.Options o, LanePoints.Path kind, double lift, double maxGrade
   ) {
      if (kind == LanePoints.Path.LEFT) {
         RoadGeometry.Mesh mirrored = generateOriented(reflect(a), reflect(b), settings, o, LanePoints.Path.RIGHT, lift, maxGrade, LanePoints.Path.LEFT);
         List<RoadGeometry.Sample> samples = mirrored.samples()
            .stream()
            .map(s -> new RoadGeometry.Sample(reflect(s.center()), reflect(s.left()).mul(-1.0), s.distance(), s.halfWidth()))
            .toList();
         return RoadRibbon.mesh(samples, settings);
      } else {
         return generateOriented(a, b, settings, o, kind, lift, maxGrade, kind);
      }
   }

   private static RoadGeometry.Mesh generateOriented(
      LaneRampPaths.Port a,
      LaneRampPaths.Port b,
      RoadGeometry.Settings settings,
      LanePoints.Options o,
      LanePoints.Path kind,
      double lift,
      double maxGrade,
      LanePoints.Path requested
   ) {
      RoadPlanningBudget.check();
      if (LaneRampGrade.exceeds(a.grade(), 1.0, maxGrade) || LaneRampGrade.exceeds(b.grade(), 1.0, maxGrade)) {
         throw new IllegalArgumentException("端口坡度超过 " + LaneRampGrade.label(maxGrade) + "，不能破坏接缝来强行连接");
      } else if (a.position().sub(b.position()).horizontalLength() > 2048.0) {
         throw new IllegalArgumentException("匝道端点距离不能超过 2048 格");
      } else {
         double transition = o.transition();
         RoadGeometry.V pa = a.position();
         RoadGeometry.V pb = b.position();
         List<LaneRampPaths.Frame> frames = new ArrayList<>();
         double leadA = transition;
         double leadB = transition;
         if (kind == LanePoints.Path.RIGHT) {
            double turn = positive(angle(b.direction()) - angle(a.direction()));
            if (turn > 3.141602653589793) {
               throw new IllegalArgumentException("所选车道方向不符合" + requested.label + "；不能反转车道方向");
            }

            double cross = a.direction().x() * b.direction().z() - a.direction().z() * b.direction().x();
            if (cross > 1.0E-6) {
               RoadGeometry.V delta = pb.sub(pa);
               double tangent = o.radius() * Math.tan(turn / 2.0);
               double availableA = (delta.x() * b.direction().z() - delta.z() * b.direction().x()) / cross - tangent;
               double availableB = (a.direction().x() * delta.z() - a.direction().z() * delta.x()) / cross - tangent;
               leadA = Math.min(transition, Math.max(0.0, availableA));
               leadB = Math.min(transition, Math.max(0.0, availableB));
            }
         }

         if (kind != LanePoints.Path.DIRECT) {
            RoadGeometry.V lead = pa.add(a.direction().mul(leadA));
            if (leadA > 1.0E-6) {
               line(frames, pa, lead, a.direction());
            }

            pa = lead;
            pb = pb.sub(b.direction().mul(leadB));
         }

         if (kind == LanePoints.Path.DIRECT) {
            RoadGeometry.V delta = pb.sub(pa);
            if (delta.dot(a.direction()) < 2.0 || delta.dot(b.direction()) < 2.0 || a.direction().dot(b.direction()) < 0.75) {
               throw new IllegalArgumentException("直接连接需要两端大致同向且前方有过渡空间；请尝试自动或回环");
            }

            bezier(frames, pa, a.direction(), pb, b.direction(), Math.max(4.0, Math.min(delta.horizontalLength() * 0.42, Math.max(o.radius(), transition))));
         } else {
            double r = o.radius();
            RoadGeometry.V ca = pa.add(a.direction().left().mul(r));
            RoadGeometry.V cb = pb.add(b.direction().left().mul(kind == LanePoints.Path.LEFT_LOOP ? -r : r));
            RoadGeometry.V delta = cb.sub(ca);
            if (kind == LanePoints.Path.RIGHT && delta.horizontalLength() < 1.0E-6) {
               arc(frames, ca, r, angle(a.direction()), positive(angle(b.direction()) - angle(a.direction())));
            } else {
               if (delta.horizontalLength() < 1.0E-6) {
                  throw new IllegalArgumentException("回环圆心重合，请调整半径或点位");
               }

               RoadGeometry.V tangent = delta.horizontalUnit();
               if (kind == LanePoints.Path.LEFT_LOOP) {
                  double length = delta.horizontalLength();
                  if (length <= 2.0 * r) {
                     throw new IllegalArgumentException("回环内公切线空间不足，请扩大间距");
                  }

                  double theta = angle(tangent) + Math.asin(2.0 * r / length);
                  tangent = new RoadGeometry.V(Math.cos(theta), 0.0, Math.sin(theta));
               }

               double aa = angle(a.direction());
               double bb = angle(b.direction());
               double tt = angle(tangent);
               double turnA = positive(tt - aa);
               double turnB = positive(kind == LanePoints.Path.LEFT_LOOP ? tt - bb : bb - tt);
               double turnx = kind == LanePoints.Path.LEFT_LOOP ? turnA - turnB : turnA + turnB;
               if (kind == LanePoints.Path.RIGHT && turnx > 3.141602653589793) {
                  throw new IllegalArgumentException("当前半径或扩出过渡放不进" + requested.label + "空间，请减小半径、关闭扩出或调整选点");
               }

               if (kind == LanePoints.Path.LEFT_LOOP && turnx < 3.141602653589793) {
                  throw new IllegalArgumentException("当前布局没有左转回环空间，请移动点位或改用其他样式");
               }

               arc(frames, ca, r, aa, turnA);
               RoadGeometry.V ta = ca.sub(tangent.left().mul(r));
               RoadGeometry.V tb = cb.sub(tangent.left().mul(kind == LanePoints.Path.LEFT_LOOP ? -r : r));
               line(frames, ta, tb, tangent);
               if (kind == LanePoints.Path.LEFT_LOOP) {
                  arcLeft(frames, cb, r, tt, turnB);
               } else {
                  arc(frames, cb, r, tt, turnB);
               }
            }
         }

         if (kind != LanePoints.Path.DIRECT) {
            line(frames, pb, b.position(), b.direction());
         }

         return finish(a, b, settings, frames, lift, maxGrade);
      }
   }

   private static RoadGeometry.Mesh finish(
      LaneRampPaths.Port a, LaneRampPaths.Port b, RoadGeometry.Settings settings, List<LaneRampPaths.Frame> frames, double lift, double maxGrade
   ) {
      if (frames.size() < 2) {
         throw new IllegalArgumentException("连接距离过短");
      } else {
         frames.set(0, new LaneRampPaths.Frame(a.position(), a.direction()));
         frames.set(frames.size() - 1, new LaneRampPaths.Frame(b.position(), b.direction()));
         double[] d = new double[frames.size()];

         for (int i = 1; i < d.length; i++) {
            d[i] = d[i - 1] + frames.get(i).p().sub(frames.get(i - 1).p()).horizontalLength();
         }

         double length = d[d.length - 1];
         if (length < 4.0) {
            throw new IllegalArgumentException("连接距离须至少 4 格");
         } else {
            List<RoadGeometry.Sample> samples = new ArrayList<>();
            double lastY = 0.0;

            for (int i = 0; i < frames.size(); i++) {
               double t = d[i] / length;
               double u = 1.0 - t;
               double y = (2.0 * t * t * t - 3.0 * t * t + 1.0) * a.position().y()
                  + (t * t * t - 2.0 * t * t + t) * length * a.grade()
                  + (-2.0 * t * t * t + 3.0 * t * t) * b.position().y()
                  + (t * t * t - t * t) * length * b.grade()
                  + lift * Math.pow(Math.sin(Math.PI * t), 2.0);
               if (settings.style().connectorRamp()) {
                  y = LaneRampProfile.height(a.position().y(), b.position().y(), a.grade(), b.grade(), length, d[i], maxGrade)
                     + lift * Math.pow(Math.sin(Math.PI * t), 2.0);
               }

               if (i > 0 && LaneRampGrade.exceeds(y - lastY, d[i] - d[i - 1], maxGrade)) {
                  throw new IllegalArgumentException("匝道坡度超过 " + LaneRampGrade.label(maxGrade) + "，请扩大间距或降低高差");
               }

               lastY = y;
               LaneRampPaths.Frame f = frames.get(i);
               samples.add(new RoadGeometry.Sample(new RoadGeometry.V(f.p().x(), y, f.p().z()), f.d().left(), d[i], settings.width() / 2.0));
            }

            RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, settings);
            if (RoadRibbon.minRadius(mesh) < Math.max(settings.width() / 2.0 + 0.5, 3.0)) {
               throw new IllegalArgumentException("接头内侧半径不足，请增大过渡长度");
            } else {
               RoadRibbon.checkSelfIntersections(mesh, 4.0);
               checkVolume(mesh);
               return mesh;
            }
         }
      }
   }

   public static void checkVolume(RoadGeometry.Mesh mesh) {
      if (mesh.samples().size() < 96) {
         shortVolume(mesh);
      } else {
         List<RoadGeometry.Sample> p = mesh.samples();
         double half = p.stream().mapToDouble(RoadGeometry.Sample::halfWidth).max().orElse(mesh.settings().width() / 2.0);
         double width = half * 2.0;
         double clearance = 4.0 + mesh.settings().thickness();
         RoadBoundsIndex index = RoadPathIndex.points(mesh);

         for (int i = 0; i < p.size(); i += 3) {
            RoadPlanningBudget.check();
            RoadGeometry.Sample a = p.get(i);

            for (int point : index.query(RoadPathIndex.segment(a.center(), a.center()), a.halfWidth() + half + 0.35)) {
               int j = point * 3;
               if (j >= i + 3) {
                  RoadGeometry.Sample b = p.get(j);
                  if (!(b.distance() - a.distance() < width * 3.0)) {
                     double horizontal = a.center().sub(b.center()).horizontalLength();
                     if (horizontal < a.halfWidth() + b.halfWidth() + 0.35 && Math.abs(a.center().y() - b.center().y()) < clearance - 0.05) {
                        throw new IllegalArgumentException("回环路面体积净空不足，请增大半径、间距或过渡长度");
                     }
                  }
               }
            }
         }
      }
   }

   private static RoadGeometry.V reflect(RoadGeometry.V p) {
      return new RoadGeometry.V(p.x(), p.y(), -p.z());
   }

   private static LaneRampPaths.Port reflect(LaneRampPaths.Port p) {
      return new LaneRampPaths.Port(reflect(p.position()), reflect(p.direction()), reflect(p.outside()), p.extraWidth(), p.grade());
   }

   private static double angle(RoadGeometry.V d) {
      return Math.atan2(d.z(), d.x());
   }

   private static double positive(double a) {
      double v = a % (Math.PI * 2);
      if (v < 0.0) {
         v += Math.PI * 2;
      }

      return !(v < 1.0E-10) && !((Math.PI * 2) - v < 1.0E-10) ? v : 0.0;
   }

   private static void add(List<LaneRampPaths.Frame> f, RoadGeometry.V p, RoadGeometry.V d) {
      if (f.isEmpty() || !(f.get(f.size() - 1).p().sub(p).horizontalLength() < 1.0E-6)) {
         if (f.size() >= 16384) {
            throw new IllegalArgumentException("匝道过长");
         } else {
            f.add(new LaneRampPaths.Frame(p, d));
         }
      }
   }

   private static void arc(List<LaneRampPaths.Frame> f, RoadGeometry.V c, double r, double start, double turn) {
      int n = Math.max(1, (int)Math.ceil(r * turn / 0.65));

      for (int i = 0; i <= n; i++) {
         double t = start + turn * (double)i / (double)n;
         RoadGeometry.V d = new RoadGeometry.V(Math.cos(t), 0.0, Math.sin(t));
         add(f, c.sub(d.left().mul(r)), d);
      }
   }

   private static void arcLeft(List<LaneRampPaths.Frame> f, RoadGeometry.V c, double r, double start, double turn) {
      int n = Math.max(1, (int)Math.ceil(r * turn / 0.65));

      for (int i = 0; i <= n; i++) {
         double t = start - turn * (double)i / (double)n;
         RoadGeometry.V d = new RoadGeometry.V(Math.cos(t), 0.0, Math.sin(t));
         add(f, c.add(d.left().mul(r)), d);
      }
   }

   private static void line(List<LaneRampPaths.Frame> f, RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V dir) {
      int n = Math.max(1, (int)Math.ceil(a.sub(b).horizontalLength() / 0.65));

      for (int i = 0; i <= n; i++) {
         add(f, a.add(b.sub(a).mul((double)i / (double)n)), dir);
      }
   }

   private static void bezier(List<LaneRampPaths.Frame> f, RoadGeometry.V a, RoadGeometry.V da, RoadGeometry.V b, RoadGeometry.V db, double k) {
      bezier(f, a, da, b, db, k, k);
   }

   private static void bezier(List<LaneRampPaths.Frame> f, RoadGeometry.V a, RoadGeometry.V da, RoadGeometry.V b, RoadGeometry.V db, double ka, double kb) {
      RoadGeometry.V p = a.add(da.mul(ka));
      RoadGeometry.V q = b.sub(db.mul(kb));
      int n = Math.max(8, (int)Math.ceil((a.sub(p).horizontalLength() + p.sub(q).horizontalLength() + q.sub(b).horizontalLength()) / 0.55));

      for (int i = 0; i <= n; i++) {
         double t = (double)i / (double)n;
         double u = 1.0 - t;
         RoadGeometry.V v = a.mul(u * u * u).add(p.mul(3.0 * u * u * t)).add(q.mul(3.0 * u * t * t)).add(b.mul(t * t * t));
         RoadGeometry.V d = p.sub(a).mul(u * u).add(q.sub(p).mul(2.0 * u * t)).add(b.sub(q).mul(t * t));
         if (d.horizontalLength() < 1.0E-7) {
            throw new IllegalArgumentException("匝道过渡折返");
         }

         add(f, v, d.horizontalUnit());
      }
   }

   private static void shortVolume(RoadGeometry.Mesh mesh) {
      List<RoadGeometry.Sample> p = mesh.samples();
      double width = mesh.samples().stream().mapToDouble(s -> s.halfWidth() * 2.0).max().orElse(mesh.settings().width());
      double clearance = 4.0 + mesh.settings().thickness();

      for (int i = 0; i < p.size(); i += 3) {
         RoadPlanningBudget.check();

         for (int j = i + 3; j < p.size(); j += 3) {
            RoadGeometry.Sample a = p.get(i);
            RoadGeometry.Sample b = p.get(j);
            if (!(b.distance() - a.distance() < width * 3.0)) {
               double horizontal = a.center().sub(b.center()).horizontalLength();
               if (horizontal < a.halfWidth() + b.halfWidth() + 0.35 && Math.abs(a.center().y() - b.center().y()) < clearance - 0.05) {
                  throw new IllegalArgumentException("回环路面体积净空不足，请增大半径、间距或过渡长度");
               }
            }
         }
      }
   }

   private LaneRampPaths() {
   }

   public static record Candidate(LanePoints.Path path, RoadGeometry.Mesh mesh) {
   }

   private static record Frame(RoadGeometry.V p, RoadGeometry.V d) {
   }

   public static record Port(
      RoadGeometry.V position, RoadGeometry.V direction, RoadGeometry.V outside, double extraWidth, double grade, List<RoadGeometry.Sample> approach
   ) {
      public Port(RoadGeometry.V position, RoadGeometry.V direction, RoadGeometry.V outside, double extraWidth, double grade) {
         this(position, direction, outside, extraWidth, grade, List.of());
      }

      public Port(
         RoadGeometry.V position, RoadGeometry.V direction, RoadGeometry.V outside, double extraWidth, double grade, List<RoadGeometry.Sample> approach
      ) {
         approach = List.copyOf(approach);
         this.position = position;
         this.direction = direction;
         this.outside = outside;
         this.extraWidth = extraWidth;
         this.grade = grade;
         this.approach = approach;
      }
   }
}
