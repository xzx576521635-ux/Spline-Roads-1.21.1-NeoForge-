package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.function.Consumer;

public final class RoadSidewalks {
   private static final Map<RoadSidewalks.Key, List<RoadSidewalks.Cell>> CACHE = new LinkedHashMap<RoadSidewalks.Key, List<RoadSidewalks.Cell>>(8, 0.75F, true) {
      @Override
      protected boolean removeEldestEntry(Entry<RoadSidewalks.Key, List<RoadSidewalks.Cell>> e) {
         return this.size() > 12;
      }
   };

   public static boolean smoothPart(RoadStructures.Part p) {
      return p.material().name().startsWith("WALK_") || p.material() == RoadStructures.Material.TACTILE;
   }

   public static List<RoadStructures.Part> parts(RoadGeometry.Mesh mesh, RoadSidewalks.Config config) {
      return parts(mesh, config, null, List.of());
   }

   public static List<RoadStructures.Part> parts(
      RoadGeometry.Mesh mesh, RoadSidewalks.Config config, RoadStructures.Ground ground, List<RoadStructures.Part> obstacles
   ) {
      if (hasWalk(mesh, config)
         && config.smooth()
         && mesh.settings().structure() != RoadGeometry.Structure.TUNNEL
         && !mesh.settings().style().ramp()
         && RoadProfile.catalog(mesh.settings().style()).type() != RoadProfile.Type.HIGHWAY) {
         ArrayList<RoadStructures.Part> out = new ArrayList<>();
         List<RoadGeometry.Sample> samples = mesh.samples();
         HashMap<Long, List<RoadStructures.Part>> grid = new HashMap<>();
         IdentityHashMap<RoadStructures.Part, double[]> bounds = new IdentityHashMap<>();

         for (RoadStructures.Part p : obstacles) {
            if (!smoothPart(p)) {
               List<RoadGeometry.V> base = p.base();
               double minX = base.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow();
               double maxX = base.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow();
               double minZ = base.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow();
               double maxZ = base.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow();
               bounds.put(p, new double[]{minX, maxX, minZ, maxZ});

               for (int x = (int)Math.floor(minX / 16.0); x <= (int)Math.floor(maxX / 16.0); x++) {
                  for (int z = (int)Math.floor(minZ / 16.0); z <= (int)Math.floor(maxZ / 16.0); z++) {
                     grid.computeIfAbsent(gridKey(x, z), k -> new ArrayList<>()).add(p);
                  }
               }
            }
         }

         for (int side : new int[]{-1, 1}) {
            if (sideUsed(mesh, config, side)) {
               double[] offsets = new double[samples.size()];

               for (int i = 0; i < samples.size(); i++) {
                  RoadPlanningBudget.check();
                  RoadGeometry.Sample sample = samples.get(i);
                  double target = RoadStreetscape.tactileOffset((double)config.width());
                  RoadGeometry.V edge = sample.at((double)side * sample.halfWidth(), 0.0);
                  RoadGeometry.V outward = sample.left().mul((double)side);
                  RoadGeometry.V forward = sample.left().left();
                  HashSet<RoadStructures.Part> near = new HashSet<>();
                  RoadGeometry.V outer = edge.add(outward.mul((double)config.width()));

                  for (int x = (int)Math.floor(Math.min(edge.x(), outer.x()) / 16.0) - 1; x <= (int)Math.floor(Math.max(edge.x(), outer.x()) / 16.0) + 1; x++) {
                     for (int z = (int)Math.floor(Math.min(edge.z(), outer.z()) / 16.0) - 1;
                        z <= (int)Math.floor(Math.max(edge.z(), outer.z()) / 16.0) + 1;
                        z++
                     ) {
                        near.addAll(grid.getOrDefault(gridKey(x, z), List.of()));
                     }
                  }

                  for (RoadStructures.Part px : near) {
                     double low = Math.min(px.a().y(), px.b().y()) - px.verticalFrame();
                     double high = Math.max(px.a().y(), px.b().y()) + px.height() + px.verticalFrame();
                     if (!(high <= edge.y() + 0.24) && !(low >= edge.y() + 2.1)) {
                        double[] box = bounds.get(px);
                        double minAlong = ((forward.x() >= 0.0 ? box[0] : box[1]) - edge.x()) * forward.x()
                           + ((forward.z() >= 0.0 ? box[2] : box[3]) - edge.z()) * forward.z();
                        double maxAlong = ((forward.x() >= 0.0 ? box[1] : box[0]) - edge.x()) * forward.x()
                           + ((forward.z() >= 0.0 ? box[3] : box[2]) - edge.z()) * forward.z();
                        if (!(minAlong > 0.80000001) && !(maxAlong < -0.80000001)) {
                           double alongLo = Double.POSITIVE_INFINITY;
                           double alongHi = -alongLo;
                           double lateralLo = alongLo;
                           double lateralHi = -alongLo;

                           for (RoadGeometry.V v : obstacleSlice(px, edge.y() + 0.24, edge.y() + 2.1)) {
                              RoadGeometry.V d = v.sub(edge);
                              double l = d.dot(outward);
                              double a = d.dot(forward);
                              alongLo = Math.min(alongLo, a);
                              alongHi = Math.max(alongHi, a);
                              lateralLo = Math.min(lateralLo, l);
                              lateralHi = Math.max(lateralHi, l);
                           }

                           if (alongLo <= 0.8 && alongHi >= -0.8 && lateralLo <= target + 0.5 && lateralHi >= target - 0.5) {
                              target = Math.max(target, lateralHi + 0.55);
                           }
                        }
                     }
                  }

                  offsets[i] = target;
               }

               double normal = RoadStreetscape.tactileOffset((double)config.width());
               int last = -1;

               for (int i = 0; i < offsets.length; i++) {
                  if (offsets[i] > normal + 0.02) {
                     if (last >= 0 && samples.get(i).distance() - samples.get(last).distance() < 14.0) {
                        double v = Math.max(offsets[last], offsets[i]);

                        for (int j = last; j <= i; j++) {
                           offsets[j] = Math.max(offsets[j], v);
                        }
                     }

                     last = i;
                  }
               }

               for (int ix = 1; ix < offsets.length; ix++) {
                  offsets[ix] = Math.max(offsets[ix], offsets[ix - 1] - 0.3 * (samples.get(ix).distance() - samples.get(ix - 1).distance()));
               }

               for (int ix = offsets.length - 2; ix >= 0; ix--) {
                  offsets[ix] = Math.max(offsets[ix], offsets[ix + 1] - 0.3 * (samples.get(ix + 1).distance() - samples.get(ix).distance()));
               }

               for (int ix = 1; ix < samples.size(); ix++) {
                  RoadGeometry.Sample a = samples.get(ix - 1);
                  RoadGeometry.Sample b = samples.get(ix);
                  RoadGeometry.V na = a.left().mul((double)side);
                  RoadGeometry.V nb = b.left().mul((double)side);
                  double wa = walkWidth(mesh, config, a, side);
                  double wb = walkWidth(mesh, config, b, side);
                  double da = Math.max(0.0, offsets[ix - 1] - normal);
                  double db = Math.max(0.0, offsets[ix] - normal);
                  double ta = tactileScale(mesh, config, a, side);
                  double tb = tactileScale(mesh, config, b, side);
                  wa += da;
                  wb += db;
                  strip(
                     out,
                     a.at((double)side * a.halfWidth(), 0.0),
                     b.at((double)side * b.halfWidth(), 0.0),
                     na,
                     nb,
                     config,
                     RoadStreetscape.tactileOffset(wa),
                     RoadStreetscape.tactileOffset(wb),
                     wa,
                     wb,
                     ta,
                     tb
                  );
                  if (ground != null && mesh.settings().options().outerRail() != RoadProfile.OuterRail.OFF) {
                     RoadGeometry.V aa = a.at((double)side * (a.halfWidth() + wa - 0.12), 0.0);
                     RoadGeometry.V bb = b.at((double)side * (b.halfWidth() + wb - 0.12), 0.0);
                     RoadGeometry.V mid = aa.add(bb).mul(0.5);
                     double floor = ground.top(mid.x(), mid.z(), mid.y() + 0.2);
                     boolean raised = mesh.settings().structure() == RoadGeometry.Structure.BRIDGE
                        || mesh.settings().structure() != RoadGeometry.Structure.GROUND && (!Double.isFinite(floor) || mid.y() + 0.2 - floor > 0.35);
                     if (raised && Math.min(wa, wb) > 0.3 && !ground.joined(mid)) {
                        for (double y : new double[]{0.65, 1.3}) {
                           out.add(
                              new RoadStructures.Part(
                                    aa.add(new RoadGeometry.V(0.0, y, 0.0)),
                                    bb.add(new RoadGeometry.V(0.0, y, 0.0)),
                                    0.12,
                                    0.12,
                                    false,
                                    RoadStructures.Material.STEEL
                                 )
                                 .frames(na.mul(0.06), nb.mul(0.06))
                           );
                        }

                        if ((int)Math.floor(a.distance() / 3.5) != (int)Math.floor(b.distance() / 3.5) || ix == 1) {
                           out.add(
                              new RoadStructures.Part(
                                 aa.add(new RoadGeometry.V(0.0, 0.2, 0.0)),
                                 aa.add(new RoadGeometry.V(0.0, 0.2, 0.0)),
                                 0.14,
                                 1.22,
                                 true,
                                 RoadStructures.Material.STEEL
                              )
                           );
                        }
                     }
                  }
               }
            }
         }

         walkwayTerminals(out);
         return List.copyOf(out);
      } else {
         return List.of();
      }
   }

   static List<RoadStructures.Part> variableParts(RoadGeometry.Mesh mesh, RoadSidewalks.Config config, List<Double> widths) {
      if (widths.size() != mesh.samples().size()) {
         throw new IllegalArgumentException("footway sample count");
      } else {
         ArrayList<RoadStructures.Part> out = new ArrayList<>();

         for (int i = 1; i < mesh.samples().size(); i++) {
            RoadGeometry.Sample a = mesh.samples().get(i - 1);
            RoadGeometry.Sample b = mesh.samples().get(i);
            double wa = widths.get(i - 1);
            double wb = widths.get(i);
            strip(out, a.at(a.halfWidth(), 0.0), b.at(b.halfWidth(), 0.0), a.left(), b.left(), config, 0.0, 0.0, wa, wb, 0.0, 0.0);
         }

         return List.copyOf(out);
      }
   }

   private static RoadSidewalks.Config endWalk(RoadTransitions.Section s, RoadSidewalks.Config fallback) {
      return s != null && s.sidewalk() != null ? s.sidewalk() : fallback;
   }

   private static boolean hasWalk(RoadGeometry.Mesh m, RoadSidewalks.Config c) {
      RoadTransitions.Ends e = m.settings().options().ends();
      return c.enabled() || endWalk(e.start(), c).enabled() || endWalk(e.end(), c).enabled();
   }

   private static boolean sideUsed(RoadGeometry.Mesh m, RoadSidewalks.Config c, int side) {
      RoadTransitions.Ends e = m.settings().options().ends();
      return enabled(c, side) || enabled(endWalk(e.start(), c), side) || enabled(endWalk(e.end(), c), side);
   }

   private static double walkValue(RoadSidewalks.Config c, int side, boolean tactile) {
      return enabled(c, side) ? (tactile ? (double)(c.tactile() && c.width() >= 2 ? 1 : 0) : (double)c.width()) : 0.0;
   }

   private static double walkBlend(RoadGeometry.Mesh m, RoadSidewalks.Config c, RoadGeometry.Sample s, int side, boolean tactile) {
      RoadTransitions.Ends e = m.settings().options().ends();
      double span = Math.min(24.0, m.length() * 0.45);
      double base = walkValue(c, side, tactile);
      double a = RoadTransitions.endWeight(
         m.settings(), s.distance() + e.trimmedStart(), m.length() + e.trimmedStart() + e.trimmedEnd(), true, Math.max(1.0, span)
      );
      double b = RoadTransitions.endWeight(
         m.settings(), m.length() - s.distance() + e.trimmedEnd(), m.length() + e.trimmedStart() + e.trimmedEnd(), false, Math.max(1.0, span)
      );
      return base + (walkValue(endWalk(e.start(), c), side, tactile) - base) * a + (walkValue(endWalk(e.end(), c), side, tactile) - base) * b;
   }

   public static double walkWidth(RoadGeometry.Mesh m, RoadSidewalks.Config c, RoadGeometry.Sample s, int side) {
      return walkBlend(m, c, s, side, false);
   }

   public static double tactileScale(RoadGeometry.Mesh m, RoadSidewalks.Config c, RoadGeometry.Sample s, int side) {
      return walkBlend(m, c, s, side, true);
   }

   private static void walkwayTerminals(List<RoadStructures.Part> out) {
      LinkedHashMap<RoadGeometry.V, Integer> endpoints = new LinkedHashMap<>();

      for (RoadStructures.Part p : out) {
         if (p.material() == RoadStructures.Material.STEEL && !p.pier() && p.width() == 0.12 && p.height() == 0.12) {
            endpoints.merge(p.a(), Integer.valueOf(1), Integer::sum);
            endpoints.merge(p.b(), Integer.valueOf(1), Integer::sum);
         }
      }

      for (Entry<RoadGeometry.V, Integer> e : endpoints.entrySet()) {
         if (e.getValue() == 1) {
            RoadGeometry.V high = e.getKey();
            boolean lower = endpoints.keySet().stream().anyMatch(v -> v.distance(high.add(new RoadGeometry.V(0.0, -0.65, 0.0))) < 1.0E-6);
            if (lower) {
               RoadGeometry.V base = high.add(new RoadGeometry.V(0.0, -1.1, 0.0));
               if (out.stream().noneMatch(px -> px.pier() && px.a().distance(base) < 0.1)) {
                  out.add(new RoadStructures.Part(base, base, 0.14, 1.22, true, RoadStructures.Material.STEEL));
               }
            }
         }
      }
   }

   private static List<RoadGeometry.V> obstacleSlice(RoadStructures.Part p, double low, double high) {
      List<RoadGeometry.V> base = p.base();
      ArrayList<RoadGeometry.V> out = new ArrayList<>();

      for (int i = 0; i < 4; i++) {
         RoadGeometry.V a = base.get(i);
         RoadGeometry.V b = base.get((i + 1) % 4);
         sliceEdge(out, a, a.add(new RoadGeometry.V(0.0, p.height(), 0.0)), low, high);
         sliceEdge(out, a, b, low, high);
         sliceEdge(out, a.add(new RoadGeometry.V(0.0, p.height(), 0.0)), b.add(new RoadGeometry.V(0.0, p.height(), 0.0)), low, high);
      }

      return out;
   }

   private static void sliceEdge(List<RoadGeometry.V> out, RoadGeometry.V a, RoadGeometry.V b, double low, double high) {
      if (a.y() >= low && a.y() <= high) {
         out.add(a);
      }

      if (b.y() >= low && b.y() <= high) {
         out.add(b);
      }

      if (!(Math.abs(b.y() - a.y()) < 1.0E-9)) {
         for (double y : new double[]{low, high}) {
            double t = (y - a.y()) / (b.y() - a.y());
            if (t > 0.0 && t < 1.0) {
               out.add(a.add(b.sub(a).mul(t)));
            }
         }
      }
   }

   private static RoadGeometry.V cornerNormal(List<RoadGeometry.V> boundary, int index, double sign) {
      int n = boundary.size();
      RoadGeometry.V p = boundary.get((index + n - 1) % n);
      RoadGeometry.V q = boundary.get(index);
      RoadGeometry.V r = boundary.get((index + 1) % n);
      RoadGeometry.V a = q.sub(p).horizontalUnit().left().mul(sign);
      RoadGeometry.V b = r.sub(q).horizontalUnit().left().mul(sign);
      double den = 1.0 + a.dot(b);
      return den < 0.5 ? b : a.add(b).mul(1.0 / den);
   }

   private static long gridKey(int x, int z) {
      return (long)x << 32 ^ (long)z & 4294967295L;
   }

   private static void strip(List<RoadStructures.Part> out, RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V na, RoadGeometry.V nb, RoadSidewalks.Config c) {
      strip(out, a, b, na, nb, c, RoadStreetscape.tactileOffset((double)c.width()), RoadStreetscape.tactileOffset((double)c.width()));
   }

   private static void strip(
      List<RoadStructures.Part> out, RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V na, RoadGeometry.V nb, RoadSidewalks.Config c, double xa, double xb
   ) {
      strip(out, a, b, na, nb, c, xa, xb, (double)c.width(), (double)c.width(), c.tactile() ? 1.0 : 0.0, c.tactile() ? 1.0 : 0.0);
   }

   private static void strip(
      List<RoadStructures.Part> out,
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V na,
      RoadGeometry.V nb,
      RoadSidewalks.Config c,
      double xa,
      double xb,
      double wa,
      double wb,
      double ta,
      double tb
   ) {
      if (!(a.sub(b).horizontalLength() < 1.0E-6) && !(Math.max(wa, wb) < 0.01)) {
         for (double x = 0.0; x < Math.max(wa, wb); x += 5.0) {
            double aw = Math.max(0.0, Math.min(5.0, wa - x));
            double bw = Math.max(0.0, Math.min(5.0, wb - x));
            double w = Math.max(aw, bw);
            out.add(
               new RoadStructures.Part(
                     a.add(na.mul(Math.min(x, wa) + aw / 2.0)).add(new RoadGeometry.V(0.0, -0.3, 0.0)),
                     b.add(nb.mul(Math.min(x, wb) + bw / 2.0)).add(new RoadGeometry.V(0.0, -0.3, 0.0)),
                     w,
                     0.5,
                     false,
                     RoadSidewalks.Finish.of(c.material()).material()
                  )
                  .frames(na.mul(aw / 2.0), nb.mul(bw / 2.0))
            );
         }

         if (Math.min(wa, wb) >= 2.0 && Math.max(ta, tb) > 0.01 && xa <= wa - 0.45 && xb <= wb - 0.45) {
            out.add(
               new RoadStructures.Part(
                     a.add(na.mul(xa)).add(new RoadGeometry.V(0.0, 0.2, 0.0)),
                     b.add(nb.mul(xb)).add(new RoadGeometry.V(0.0, 0.2, 0.0)),
                     0.6,
                     0.015,
                     false,
                     RoadStructures.Material.TACTILE
                  )
                  .frames(na.mul(0.3 * ta), nb.mul(0.3 * tb))
            );

            for (double ridge : new double[]{-0.18, 0.0, 0.18}) {
               out.add(
                  new RoadStructures.Part(
                        a.add(na.mul(xa + ridge * ta)).add(new RoadGeometry.V(0.0, 0.215, 0.0)),
                        b.add(nb.mul(xb + ridge * tb)).add(new RoadGeometry.V(0.0, 0.215, 0.0)),
                        0.06,
                        0.02,
                        false,
                        RoadStructures.Material.TACTILE
                     )
                     .frames(na.mul(0.03 * ta), nb.mul(0.03 * tb))
               );
            }
         }
      }
   }

   public static List<RoadStructures.Part> junctionParts(JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary) {
      return junctionParts(spec, approaches, boundary, true);
   }

   public static List<RoadStructures.Part> junctionParts(
      JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary, boolean continuous
   ) {
      return junctionParts(spec, approaches, boundary, continuous, false);
   }

   public static List<RoadStructures.Part> junctionParts(
      JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary, boolean continuous, boolean rounded
   ) {
      return junctionParts(spec, approaches, boundary, continuous, rounded, false);
   }

   public static List<RoadStructures.Part> junctionParts(
      JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary, boolean continuous, boolean rounded, boolean insetBend
   ) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      double area = 0.0;

      for (int i = 0; i < boundary.size(); i++) {
         RoadGeometry.V a = boundary.get(i);
         RoadGeometry.V b = boundary.get((i + 1) % boundary.size());
         area += a.x() * b.z() - b.x() * a.z();
      }

      double sign = area >= 0.0 ? -1.0 : 1.0;

      for (int i = 0; i < boundary.size(); i++) {
         RoadGeometry.V a = boundary.get(i);
         RoadGeometry.V b = boundary.get((i + 1) % boundary.size());
         RoadGeometry.V mid = a.add(b).mul(0.5);
         if (!(a.distance(b) < 1.0E-8) && !approaches.stream().anyMatch(m -> RoadQueries.contains(m, mid, 0.02, 0.1))) {
            RoadSidewalks.Config c = null;
            double best = Double.POSITIVE_INFINITY;

            for (int arm = 0; arm < approaches.size(); arm++) {
               RoadGeometry.Sample mouth = approaches.get(arm).last();
               int side = mid.sub(mouth.center()).dot(mouth.left()) < 0.0 ? -1 : 1;
               double d = mid.distance(mouth.at((double)side * mouth.halfWidth(), 0.0));
               if (d < best) {
                  best = d;
                  RoadSidewalks.Config candidate = spec.arms().get(arm).external().options().sidewalk();
                  c = enabled(candidate, side) && candidate.smooth() ? candidate : null;
               }
            }

            if (c != null) {
               RoadGeometry.V normal = b.sub(a).horizontalUnit().left().mul(sign);
               strip(
                  out,
                  a,
                  b,
                  continuous ? cornerNormal(boundary, i, sign) : normal,
                  continuous ? cornerNormal(boundary, (i + 1) % boundary.size(), sign) : normal,
                  rounded ? c.tactile(false) : c
               );
            }
         }
      }

      if (rounded) {
         out.addAll(TactilePaths.junction(spec, approaches, boundary, insetBend));
      }

      return List.copyOf(out);
   }

   private static RoadSidewalks.WalkEnd cornerEnd(JunctionSpec spec, List<RoadGeometry.Mesh> approaches, RoadGeometry.V point) {
      RoadSidewalks.WalkEnd result = null;
      double best = Double.POSITIVE_INFINITY;

      for (int i = 0; i < approaches.size(); i++) {
         for (int side : new int[]{-1, 1}) {
            RoadGeometry.Sample m = approaches.get(i).last();
            double d = m.at((double)side * m.halfWidth(), 0.0).distance(point);
            if (d < best) {
               best = d;
               result = new RoadSidewalks.WalkEnd(spec.arms().get(i).external().options().sidewalk(), side, m.left().mul((double)side));
            }
         }
      }

      return result;
   }

   public static List<RoadGeometry.V> splitBoundary(List<RoadGeometry.V> boundary, List<RoadGeometry.Mesh> approaches) {
      ArrayList<RoadGeometry.V> result = new ArrayList<>();

      for (int i = 0; i < boundary.size(); i++) {
         RoadGeometry.V a = boundary.get(i);
         RoadGeometry.V b = boundary.get((i + 1) % boundary.size());
         RoadGeometry.V u = b.sub(a);
         TreeSet<Double> cuts = new TreeSet<>();
         cuts.add(0.0);

         for (RoadGeometry.Mesh mesh : approaches) {
            for (int j = 1; j < mesh.samples().size(); j++) {
               RoadGeometry.Sample p = mesh.samples().get(j - 1);
               RoadGeometry.Sample q = mesh.samples().get(j);

               for (int side : new int[]{-1, 1}) {
                  RoadGeometry.V c = p.at((double)side * p.halfWidth(), 0.0);
                  RoadGeometry.V d = q.at((double)side * q.halfWidth(), 0.0);
                  RoadGeometry.V v = d.sub(c);
                  RoadGeometry.V w = c.sub(a);
                  double den = u.x() * v.z() - u.z() * v.x();
                  if (!(Math.abs(den) < 1.0E-9)) {
                     double t = (w.x() * v.z() - w.z() * v.x()) / den;
                     double r = (w.x() * u.z() - w.z() * u.x()) / den;
                     if (t > 1.0E-8 && t < 0.99999999 && r >= -1.0E-8 && r <= 1.00000001) {
                        cuts.add(t);
                     }
                  }
               }
            }
         }

         for (double t : cuts) {
            result.add(a.add(u.mul(t)));
         }
      }

      return List.copyOf(result);
   }

   public static List<RoadGeometry.V> closeCorner(JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> edge) {
      if (edge.size() < 2) {
         return edge;
      } else {
         ArrayList<RoadGeometry.V> result = new ArrayList<>(edge);

         for (boolean start : new boolean[]{true, false}) {
            int at = start ? 0 : result.size() - 1;
            RoadGeometry.V point = result.get(at);
            RoadGeometry.V closest = null;
            double best = Double.POSITIVE_INFINITY;

            for (RoadGeometry.Mesh m : approaches) {
               for (int side : new int[]{-1, 1}) {
                  RoadGeometry.V p = m.last().at((double)side * m.last().halfWidth(), 0.0);
                  double distance = p.distance(point);
                  if (distance < best) {
                     best = distance;
                     closest = p;
                  }
               }
            }

            if (best > 1.0E-5 && best < 4.0) {
               if (start) {
                  result.add(0, closest);
               } else {
                  result.add(closest);
               }
            }
         }

         return List.copyOf(result);
      }
   }

   public static List<RoadStructures.Part> taperedJunctionParts(JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      int n = boundary.size();
      if (n < 3) {
         return out;
      } else {
         boolean[] open = new boolean[n];
         int start = -1;
         double sign = JunctionPaint.area(boundary) >= 0.0 ? -1.0 : 1.0;

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
            return junctionParts(spec, approaches, boundary, true, true, true);
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
                  taperCorner(out, spec, approaches, run, sign);
                  run.clear();
               }
            }

            if (!run.isEmpty()) {
               taperCorner(out, spec, approaches, run, sign);
            }

            out.addAll(TactilePaths.junction(spec, approaches, boundary, true));
            return List.copyOf(out);
         }
      }
   }

   private static void taperCorner(List<RoadStructures.Part> out, JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> edge, double sign) {
      edge = closeCorner(spec, approaches, edge);
      if (edge.size() >= 2) {
         RoadSidewalks.WalkEnd first = cornerEnd(spec, approaches, edge.get(0));
         RoadSidewalks.WalkEnd last = cornerEnd(spec, approaches, edge.get(edge.size() - 1));
         double a = first.width();
         double b = last.width();
         if (a != 0.0 || b != 0.0) {
            if (a == 0.0) {
               a = b;
            } else if (b == 0.0) {
               b = a;
            }

            double length = 0.0;

            for (int i = 1; i < edge.size(); i++) {
               length += edge.get(i).distance(edge.get(i - 1));
            }

            ArrayList<RoadGeometry.V> normals = new ArrayList<>();
            double[] widths = new double[edge.size()];
            double station = 0.0;

            for (int i = 0; i < edge.size(); i++) {
               if (i > 0) {
                  station += edge.get(i).distance(edge.get(i - 1));
               }

               double t = RoadGeometry.Settings.smooth(station / Math.max(1.0E-9, length));
               widths[i] = a + (b - a) * t;
               normals.add(i == 0 ? first.normal() : (i == edge.size() - 1 ? last.normal() : cornerNormal(edge, i, sign)));
            }

            for (int i = 1; i < edge.size(); i++) {
               RoadSidewalks.Config config = (!(first.width() > 0.0) || last.width() != 0.0 && i * 2 >= edge.size() ? last : first).config().tactile(false);
               strip(out, edge.get(i - 1), edge.get(i), normals.get(i - 1), normals.get(i), config, 0.0, 0.0, widths[i - 1], widths[i], 0.0, 0.0);
            }
         }
      }
   }

   static List<RoadStructures.Part> cornerPaving(JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> edge, double sign) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      taperCorner(out, spec, approaches, edge, sign);
      return out;
   }

   public static boolean blocksTactile(RoadStructures.Part part, RoadGeometry.Mesh mesh) {
      RoadSidewalks.Config c = mesh.settings().options().sidewalk();
      if (c.enabled() && c.tactile() && c.width() >= 2) {
         RoadGeometry.V mid = part.a().add(part.b()).mul(0.5);
         RoadQueries.Projection q = RoadQueries.horizontal(mesh, mid);
         double y = q.sample().center().y();
         if (!(part.a().y() + part.height() < y + 0.19)
            && !(part.a().y() > y + 2.1)
            && !(q.horizontalDistance() > q.sample().halfWidth() + (double)c.width() + part.width())) {
            double lo = Double.POSITIVE_INFINITY;
            double hi = -lo;

            for (RoadGeometry.V v : part.base()) {
               double d = v.sub(q.sample().center()).dot(q.sample().left());
               lo = Math.min(lo, d);
               hi = Math.max(hi, d);
            }

            for (int side : new int[]{-1, 1}) {
               if (enabled(c, side)) {
                  double center = (double)side * (q.sample().halfWidth() + RoadStreetscape.tactileOffset((double)c.width()));
                  if (lo < center + 0.5 && hi > center - 0.5) {
                     return true;
                  }
               }
            }

            return false;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public static boolean overlapsDeck(RoadStructures.Part part, RoadGeometry.Mesh mesh) {
      List<RoadGeometry.V> poly = part.base();
      double area = Math.abs(JunctionPaint.area(poly));
      if (area < 1.0E-10) {
         return false;
      } else {
         double x0 = poly.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow();
         double x1 = poly.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow();
         double z0 = poly.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow();
         double z1 = poly.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow();

         for (int i = 1; i < mesh.samples().size(); i++) {
            RoadGeometry.Sample a = mesh.samples().get(i - 1);
            RoadGeometry.Sample b = mesh.samples().get(i);
            List<RoadGeometry.V> quad = List.of(a.at(a.halfWidth(), 0.0), a.at(-a.halfWidth(), 0.0), b.at(-b.halfWidth(), 0.0), b.at(b.halfWidth(), 0.0));
            if (!(quad.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow() <= x0 + 1.0E-7)
               && !(quad.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow() >= x1 - 1.0E-7)
               && !(quad.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow() <= z0 + 1.0E-7)
               && !(quad.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow() >= z1 - 1.0E-7)
               && area - RoadSurface.subtract(poly, quad).stream().mapToDouble(v -> Math.abs(JunctionPaint.area((List<RoadGeometry.V>)v))).sum() > 1.0E-6) {
               return true;
            }
         }

         return false;
      }
   }

   public static synchronized List<RoadSidewalks.Cell> cells(RoadGeometry.Mesh mesh, RoadSidewalks.Config config) {
      return config.enabled() && !mesh.settings().style().ramp() && RoadProfile.catalog(mesh.settings().style()).type() != RoadProfile.Type.HIGHWAY
         ? CACHE.computeIfAbsent(new RoadSidewalks.Key(mesh, config), k -> build(k.mesh(), k.config()))
         : List.of();
   }

   private static List<RoadSidewalks.Cell> build(RoadGeometry.Mesh mesh, RoadSidewalks.Config config) {
      LinkedHashSet<RoadSidewalks.Cell> result = new LinkedHashSet<>();

      for (int i = 1; i < mesh.samples().size(); i++) {
         RoadGeometry.Sample a = mesh.samples().get(i - 1);
         RoadGeometry.Sample b = mesh.samples().get(i);

         for (int side : new int[]{-1, 1}) {
            if ((config.side() != RoadSidewalks.Side.LEFT || side <= 0) && (config.side() != RoadSidewalks.Side.RIGHT || side >= 0)) {
               List<RoadGeometry.V> p = List.of(
                  a.at((double)side * a.halfWidth(), 0.0),
                  a.at((double)side * (a.halfWidth() + (double)config.width()), 0.0),
                  b.at((double)side * (b.halfWidth() + (double)config.width()), 0.0),
                  b.at((double)side * b.halfWidth(), 0.0)
               );
               raster(p, a.center(), b.center(), cell -> {
                  RoadGeometry.V at = new RoadGeometry.V((double)cell.x() + 0.5, 0.0, (double)cell.z() + 0.5);
                  if (!(a.distance() < 1.5) || !(at.sub(mesh.first().center()).dot(mesh.first().left().left().mul(-1.0)) < -1.0E-7)) {
                     if (!(b.distance() > mesh.length() - 1.5) || !(at.sub(mesh.last().center()).dot(mesh.last().left().left().mul(-1.0)) >= -1.0E-7)) {
                        result.add(cell);
                     }
                  }
               });
            }
         }
      }

      return List.copyOf(result);
   }

   public static Map<RoadSidewalks.Cell, RoadSidewalks.Config> junctionCells(
      JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary
   ) {
      if (spec.arms().stream().noneMatch(ax -> ax.external().options().sidewalk().enabled())) {
         return Map.of();
      } else {
         LinkedHashMap<RoadSidewalks.Cell, RoadSidewalks.Config> result = new LinkedHashMap<>();
         double area = 0.0;

         for (int i = 0; i < boundary.size(); i++) {
            RoadGeometry.V a = boundary.get(i);
            RoadGeometry.V b = boundary.get((i + 1) % boundary.size());
            area += a.x() * b.z() - b.x() * a.z();
         }

         double sign = area >= 0.0 ? -1.0 : 1.0;

         for (int i = 0; i < boundary.size(); i++) {
            RoadGeometry.V a = boundary.get(i);
            RoadGeometry.V b = boundary.get((i + 1) % boundary.size());
            RoadGeometry.V middle = a.add(b).mul(0.5);
            if (!(a.distance(b) < 1.0E-8) && !approaches.stream().anyMatch(m -> RoadQueries.contains(m, middle, 0.02, 0.1))) {
               RoadGeometry.V normal = b.sub(a).horizontalUnit().left().mul(sign);
               RoadSidewalks.Config config = null;
               double best = Double.POSITIVE_INFINITY;

               for (int arm = 0; arm < approaches.size(); arm++) {
                  RoadGeometry.Sample mouth = approaches.get(arm).last();
                  int side = middle.sub(mouth.center()).dot(mouth.left()) < 0.0 ? -1 : 1;
                  double distance = middle.distance(mouth.at((double)side * mouth.halfWidth(), 0.0));
                  if (distance < best) {
                     best = distance;
                     RoadSidewalks.Config c = spec.arms().get(arm).external().options().sidewalk();
                     config = enabled(c, side) ? c : null;
                  }
               }

               if (config != null) {
                  RoadSidewalks.Config chosen = config;
                  RoadGeometry.V along = b.sub(a).horizontalUnit().mul(0.02);
                  List<RoadGeometry.V> polygon = List.of(
                     a.sub(along), b.add(along), b.add(along).add(normal.mul((double)config.width())), a.sub(along).add(normal.mul((double)config.width()))
                  );
                  raster(polygon, a, b, cx -> result.putIfAbsent(cx, chosen));
               }
            }
         }

         return Collections.unmodifiableMap(result);
      }
   }

   private static boolean enabled(RoadSidewalks.Config c, int side) {
      return c.enabled()
         && (c.side() == RoadSidewalks.Side.BOTH || c.side() == RoadSidewalks.Side.LEFT && side < 0 || c.side() == RoadSidewalks.Side.RIGHT && side > 0);
   }

   static void raster(List<RoadGeometry.V> polygon, RoadGeometry.V a, RoadGeometry.V b, Consumer<RoadSidewalks.Cell> out) {
      int x0 = (int)Math.floor(polygon.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow());
      int x1 = (int)Math.ceil(polygon.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow()) - 1;
      int z0 = (int)Math.floor(polygon.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow());
      int z1 = (int)Math.ceil(polygon.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()) - 1;
      RoadGeometry.V d = b.sub(a);
      double square = d.x() * d.x() + d.z() * d.z();

      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            if (overlaps(polygon, x, z)) {
               double t = Math.max(0.0, Math.min(1.0, (((double)x + 0.5 - a.x()) * d.x() + ((double)z + 0.5 - a.z()) * d.z()) / Math.max(1.0E-9, square)));
               out.accept(new RoadSidewalks.Cell(x, (int)Math.floor(a.y() + d.y() * t + 1.0E-6) - 1, z));
            }
         }
      }
   }

   private static boolean overlaps(List<RoadGeometry.V> polygon, int x, int z) {
      List<RoadGeometry.V> p = polygon;

      for (int edge = 0; edge < 4; edge++) {
         ArrayList<RoadGeometry.V> next = new ArrayList<>();
         if (p.isEmpty()) {
            return false;
         }

         double limit = edge == 0 ? (double)x : (edge == 1 ? (double)(x + 1) : (edge == 2 ? (double)z : (double)(z + 1)));
         boolean horizontal = edge < 2;
         boolean lower = edge % 2 == 0;
         RoadGeometry.V prev = p.get(p.size() - 1);
         double pv = (horizontal ? prev.x() : prev.z()) - limit;
         boolean pin = lower ? pv >= 0.0 : pv <= 0.0;

         for (RoadGeometry.V at : p) {
            double v = (horizontal ? at.x() : at.z()) - limit;
            boolean in = lower ? v >= 0.0 : v <= 0.0;
            if (in != pin) {
               double t = pv / (pv - v);
               next.add(prev.add(at.sub(prev).mul(t)));
            }

            if (in) {
               next.add(at);
            }

            prev = at;
            pv = v;
            pin = in;
         }

         p = next;
      }

      double area = 0.0;

      for (int i = 0; i < p.size(); i++) {
         RoadGeometry.V a = p.get(i);
         RoadGeometry.V b = p.get((i + 1) % p.size());
         area += (a.x() - (double)x) * (b.z() - (double)z) - (b.x() - (double)x) * (a.z() - (double)z);
      }

      return Math.abs(area) > 1.0E-7;
   }

   public static List<RoadSurface.Face> preview(Collection<RoadSidewalks.Cell> cells) {
      return cells.stream()
         .map(
            c -> {
               RoadGeometry.V a = new RoadGeometry.V((double)c.x(), (double)c.y() + 1.01, (double)c.z());
               return new RoadSurface.Face(
                  List.of(a, a.add(new RoadGeometry.V(0.0, 0.0, 1.0)), a.add(new RoadGeometry.V(1.0, 0.0, 1.0)), a.add(new RoadGeometry.V(1.0, 0.0, 0.0))),
                  10348186
               );
            }
         )
         .toList();
   }

   private RoadSidewalks() {
   }

   public static record Cell(int x, int y, int z) {
   }

   public static record Config(boolean enabled, RoadSidewalks.Side side, int width, String material, boolean smooth, boolean tactile) {
      public static final RoadSidewalks.Config DEFAULT = new RoadSidewalks.Config(false, RoadSidewalks.Side.BOTH, 5, "minecraft:stone_bricks");

      public Config(boolean enabled, RoadSidewalks.Side side, int width, String material, boolean smooth) {
         this(enabled, side, width, material, smooth, true);
      }

      public Config(boolean enabled, RoadSidewalks.Side side, int width, String material) {
         this(enabled, side, width, material, true);
      }

      public Config(boolean enabled, RoadSidewalks.Side side, int width, String material, boolean smooth, boolean tactile) {
         if (smooth) {
            material = RoadSidewalks.Finish.of(material).id;
         }

         if (side != null && width >= 1 && width <= 15 && material != null && material.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            this.enabled = enabled;
            this.side = side;
            this.width = width;
            this.material = material;
            this.smooth = smooth;
            this.tactile = tactile;
         } else {
            throw new IllegalArgumentException("人行道宽度须为 1–15 格，材质须为有效的命名空间:方块名");
         }
      }

      public RoadSidewalks.Config tactile(boolean v) {
         return new RoadSidewalks.Config(this.enabled, this.side, this.width, this.material, this.smooth, v);
      }

      public RoadSidewalks.Config smooth(boolean v) {
         return new RoadSidewalks.Config(this.enabled, this.side, this.width, this.material, v, this.tactile);
      }

      public RoadSidewalks.Config enabled(boolean v) {
         return new RoadSidewalks.Config(v, this.side, this.width, this.material, true, this.tactile);
      }

      public RoadSidewalks.Config side(RoadSidewalks.Side v) {
         return new RoadSidewalks.Config(this.enabled, v, this.width, this.material, true, this.tactile);
      }

      public RoadSidewalks.Config width(int v) {
         return new RoadSidewalks.Config(this.enabled, this.side, v, this.material, true, this.tactile);
      }

      public RoadSidewalks.Config material(String v) {
         return new RoadSidewalks.Config(this.enabled, this.side, this.width, v, true, this.tactile);
      }
   }

   public static enum Finish {
      STONE_BRICKS("石砖", "stone_bricks"),
      BRICKS("红砖", "bricks"),
      ANDESITE("磨制安山岩", "polished_andesite"),
      DIORITE("磨制闪长岩", "polished_diorite"),
      GRANITE("磨制花岗岩", "polished_granite"),
      SMOOTH_STONE("平滑石头", "smooth_stone");

      public final String label;
      public final String id;

      private Finish(String label, String path) {
         this.label = label;
         this.id = "minecraft:" + path;
      }

      public static RoadSidewalks.Finish of(String id) {
         return Arrays.stream(values()).filter(f -> f.id.equals(id)).findFirst().orElse(STONE_BRICKS);
      }

      public RoadStructures.Material material() {
         return RoadStructures.Material.valueOf("WALK_" + this.name());
      }
   }

   private static record Key(RoadGeometry.Mesh mesh, RoadSidewalks.Config config) {
   }

   public static final class PavingAnchors {
      private final Map<Long, List<RoadGeometry.V>> cells = new HashMap<>();

      public PavingAnchors(Collection<RoadStructures.Part> parts) {
         for (RoadStructures.Part p : parts) {
            if (p.material().name().startsWith("WALK_")) {
               this.cells
                  .computeIfAbsent(RoadSidewalks.gridKey((int)Math.floor(p.a().x() / 16.0), (int)Math.floor(p.a().z() / 16.0)), k -> new ArrayList<>())
                  .add(p.a());
            }
         }
      }

      public boolean near(RoadGeometry.V point, double radius) {
         for (int x = (int)Math.floor((point.x() - radius) / 16.0); x <= (int)Math.floor((point.x() + radius) / 16.0); x++) {
            for (int z = (int)Math.floor((point.z() - radius) / 16.0); z <= (int)Math.floor((point.z() + radius) / 16.0); z++) {
               for (RoadGeometry.V a : this.cells.getOrDefault(RoadSidewalks.gridKey(x, z), List.of())) {
                  if (a.sub(point).horizontalLength() < radius) {
                     return true;
                  }
               }
            }
         }

         return false;
      }
   }

   public static enum Side {
      BOTH("双侧"),
      LEFT("左侧"),
      RIGHT("右侧");

      public final String label;

      private Side(String label) {
         this.label = label;
      }
   }

   private static record WalkEnd(RoadSidewalks.Config config, int side, RoadGeometry.V normal) {
      double width() {
         return RoadSidewalks.enabled(this.config, this.side) && this.config.smooth() ? (double)this.config.width() : 0.0;
      }
   }
}
