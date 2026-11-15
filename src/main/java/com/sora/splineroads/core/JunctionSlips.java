package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

public final class JunctionSlips {
   public static boolean enabled(JunctionSpec s) {
      return s.arms().stream().anyMatch(JunctionSpec.Arm::rightSlip);
   }

   public static boolean receives(JunctionSpec s, int to) {
      for (int i = 0; i < s.arms().size(); i++) {
         if (s.arms().get(i).rightSlip() && target(s, i) == to) {
            return true;
         }
      }

      return false;
   }

   private static RoadGeometry.V outward(JunctionSpec s, int arm) {
      return s.arms().get(arm).endpoint().position().sub(s.center()).horizontalUnit();
   }

   public static int target(JunctionSpec s, int from) {
      int result = -1;
      double best = 2.0;

      for (int j = 0; j < s.arms().size(); j++) {
         if (j != from && s.arms().get(j).outgoing() > 0 && JunctionPlanner.turn(s, from, j) == 4) {
            double score = Math.abs(outward(s, from).dot(outward(s, j)));
            if (score < best) {
               best = score;
               result = j;
            }
         }
      }

      return result;
   }

   public static String unavailable(JunctionSpec s, int from) {
      if (s.oneSide() != null) {
         return "单边路口保持中央隔离带连续，不使用额外独立右转道";
      } else {
         int n = s.arms().size();
         if (s.kind() == JunctionSpec.Kind.INTERSECTION && (n == 3 || n == 4) && !s.parallelApproaches()) {
            if (s.leftTraffic()) {
               return "靠左通行的右转须穿越对向车道，不能设置外侧独立右转";
            } else if (s.arms().get(from).incoming() == 0) {
               return "此方向没有驶入车道";
            } else {
               int opposite = 0;

               for (int i = 0; i < n; i++) {
                  for (int j = i + 1; j < n; j++) {
                     if (outward(s, i).dot(outward(s, j)) < -0.94) {
                        opposite++;
                     }
                  }
               }

               if (opposite < (n == 3 ? 1 : 2)) {
                  return "需要具有相对主路的十字或丁字布局";
               } else {
                  int to = target(s, from);
                  if (to < 0) {
                     return "此方向没有右转出口";
                  } else if (s.arms().get(to).outgoing() < 2) {
                     return "右转转入方向至少需要 2 条车道";
                  } else {
                     double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, outward(s, from).dot(outward(s, to))))));
                     if (!(angle < 59.999999) && !(angle > 120.000001)) {
                        for (int i : new int[]{from, to}) {
                           JunctionSpec.Arm a = s.arms().get(i);
                           if (a.external().structure() == RoadGeometry.Structure.TUNNEL) {
                              return "隧道路口不能设置外侧独立右转";
                           }

                           if (Math.abs(a.endpoint().position().y() - s.center().y()) > 0.1 || Math.abs(a.endpoint().grade()) > 0.001) {
                              return "独立右转接入口需要与路口齐平";
                           }
                        }

                        return "";
                     } else {
                        return "右转相邻道路夹角需为 60–120°";
                     }
                  }
               }
            }
         } else {
            return "仅适用于十字或丁字路口";
         }
      }
   }

   public static String islandLabel(JunctionSpec.Arm a) {
      return a.external().options().sidewalk().enabled()
         ? RoadSidewalks.Finish.of(a.external().options().sidewalk().material()).label
         : (a.cycleWidth() > 0.01 ? "石砖" : "导流线");
   }

   static double run(JunctionSpec s, int from, int to) {
      JunctionSpec.Arm a = s.arms().get(from);
      JunctionSpec.Arm b = s.arms().get(to);
      return Math.max(
         40.0,
         s.cornerRadius() * 2.0 + Math.max(a.cycleWidth() + a.curbWidth(), b.cycleWidth() + b.curbWidth()) + Math.max(a.laneWidth(), b.laneWidth()) + 20.0
      );
   }

   public static double approachLength(JunctionSpec s, int arm) {
      double length = 0.0;

      for (int i = 0; i < s.arms().size(); i++) {
         if (s.arms().get(i).rightSlip()) {
            int to = target(s, i);
            if (to >= 0 && (i == arm || to == arm)) {
               length = Math.max(length, run(s, i, to) + 40.0);
            }
         }
      }

      return length;
   }

   private static RoadGeometry.V curve(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c, RoadGeometry.V d, double t) {
      double u = 1.0 - t;
      return a.mul(u * u * u).add(b.mul(3.0 * u * u * t)).add(c.mul(3.0 * u * t * t)).add(d.mul(t * t * t));
   }

   private static double smooth(double t) {
      return RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, t)));
   }

   private static double cycleSpan(JunctionSpec.Arm a) {
      return a.cycleWidth() > 0.01 ? a.cycleWidth() + a.curbWidth() + 0.5 : 0.0;
   }

   static double walkSpan(JunctionSpec.Arm a) {
      RoadSidewalks.Config w = a.external().options().sidewalk();
      return w.enabled() ? (double)w.width() : (a.cycleWidth() > 0.01 ? 3.0 : 0.0);
   }

   static double walkWidth(JunctionSpec s, JunctionSlips.Slip slip, double d) {
      return walkWidth(s, slip, d, true);
   }

   static double walkWidth(JunctionSpec s, JunctionSlips.Slip slip, double d, boolean smoothEdges) {
      double a = walkSpan(s.arms().get(slip.from()));
      double b = walkSpan(s.arms().get(slip.to()));
      double from = cycleSpan(s.arms().get(slip.from()));
      double to = cycleSpan(s.arms().get(slip.to()));
      if (smoothEdges && from > 0.0 && to == 0.0 && b >= from - 0.5 + 2.0) {
         double delta = from - 0.5;
         return d <= slip.turnEnd()
            ? a + (b - delta - a) * smooth(d / slip.turnEnd())
            : b - delta * (1.0 - smooth((d - slip.turnEnd() - 4.0) / Math.max(1.0, slip.mesh().length() - slip.turnEnd() - 4.0)));
      } else {
         double t = a > 0.0 && b == 0.0
            ? smooth((d - slip.turnEnd() - 4.0) / Math.max(1.0, slip.mesh().length() - slip.turnEnd() - 4.0))
            : (a == 0.0 ? smooth(d / 24.0) : smooth(d / slip.turnEnd()));
         return a + (b - a) * t;
      }
   }

   public static List<JunctionSlips.Slip> layout(JunctionSpec s, List<RoadGeometry.Mesh> approaches) {
      return layout(s, approaches, true, true);
   }

   static List<JunctionSlips.Slip> layout(JunctionSpec s, List<RoadGeometry.Mesh> approaches, boolean exact) {
      return layout(s, approaches, exact, false);
   }

   static List<JunctionSlips.Slip> layout(JunctionSpec s, List<RoadGeometry.Mesh> approaches, boolean exact, boolean smoothEdges) {
      ArrayList<JunctionSlips.Slip> result = new ArrayList<>();

      for (int i = 0; i < s.arms().size(); i++) {
         if (s.arms().get(i).rightSlip()) {
            String reason = unavailable(s, i);
            if (!reason.isEmpty()) {
               throw new IllegalArgumentException("接入口 " + (i + 1) + "：" + reason);
            }

            int j = target(s, i);
            JunctionSpec.Arm a = s.arms().get(i);
            JunctionSpec.Arm b = s.arms().get(j);
            RoadGeometry.Mesh ma = approaches.get(i);
            RoadGeometry.Mesh mb = approaches.get(j);
            double run = run(s, i, j);
            if (Math.min(ma.length(), mb.length()) < run + 32.0) {
               throw new IllegalArgumentException("独立右转接路过短，请将相邻端点各延长到至少 " + (int)Math.ceil(JunctionPlanner.radius(s) + run + 32.0) + " 格外");
            }

            double da = ma.length() - run;
            double db = mb.length() - run;
            RoadGeometry.Sample sa = RoadStructures.sample(ma, da);
            RoadGeometry.Sample sb = RoadStructures.sample(mb, db);
            RoadGeometry.V p = sa.at(a.motorEdge(true, false) + a.laneWidth() / 2.0, 0.0);
            RoadGeometry.V q = sb.at(b.laneCenter(false, b.outgoing() - 1, false), 0.0);
            RoadGeometry.V f = sa.left().left().mul(-1.0).add(sa.left().mul(smoothEdges ? 0.0 : a.laneWidth() / 16.0)).horizontalUnit();
            RoadGeometry.V g = sb.left().left();
            double handle = p.sub(q).horizontalLength() * 0.35;
            RoadGeometry.V c1 = p.add(f.mul(handle));
            RoadGeometry.V c2 = q.sub(g.mul(handle));
            int steps = Math.max(32, (int)Math.ceil(p.distance(q) * 2.0));
            ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
            double width = Math.max(4.0, Math.min(6.0, Math.max(a.laneWidth(), b.laneWidth()) + 1.0));

            for (int k = 0; k <= steps; k++) {
               double t = (double)k / (double)steps;
               RoadGeometry.V at = curve(p, c1, c2, q, t);
               RoadGeometry.V d = k == 0
                  ? f
                  : (k == steps ? g : curve(p, c1, c2, q, Math.min(1.0, t + 1.0E-4)).sub(curve(p, c1, c2, q, Math.max(0.0, t - 1.0E-4))).horizontalUnit());
               double edge = smoothEdges ? 16.0 * t * t * (1.0 - t) * (1.0 - t) : Math.min(1.0, Math.min(t, 1.0 - t) * 6.0);
               double blend = smoothEdges ? smooth(t) : t;
               double w = (a.laneWidth() * (1.0 - blend) + b.laneWidth() * blend) * (1.0 - edge) + width * edge;
               double joinBlend = RoadGeometry.Settings.smooth(Math.min(1.0, t * p.distance(q) / 12.0));
               RoadGeometry.V frame = smoothEdges ? d.left() : sa.left().mul(1.0 - joinBlend).add(d.left().mul(joinBlend)).horizontalUnit();
               samples.add(new RoadGeometry.Sample(at, frame, 0.0, w / 2.0));
            }

            RoadGeometry.Mesh motor = RoadRibbon.mesh(samples, JunctionPlanner.settings(width, s.thickness()));
            RoadRibbon.checkSelfIntersections(motor, 4.0);
            if (RoadRibbon.minRadius(motor) < Math.max(6.0, width * 1.5)) {
               throw new IllegalArgumentException("独立右转半径不足，请增大路口转角半径或延长接路");
            }

            double fromCycle = cycleSpan(a);
            double toCycle = cycleSpan(b);
            double cycle = exact ? Math.max(fromCycle, toCycle) : Math.min(fromCycle, toCycle);
            int turnSamples = samples.size();
            double turnLength = motor.length();
            double tail = !exact || (!(fromCycle > 0.0) || toCycle != 0.0) && (!(walkSpan(a) > 0.0) || walkSpan(b) != 0.0) ? 0.0 : Math.min(32.0, db - 2.0);
            ArrayList<RoadGeometry.V> island = new ArrayList<>();

            for (int k = 0; k < samples.size(); k += exact ? 1 : 4) {
               RoadGeometry.Sample at = samples.get(k);
               island.add(at.at(-at.halfWidth() - (exact ? 0.0 : 0.2), 0.0));
            }

            RoadGeometry.Sample last = samples.get(samples.size() - 1);
            if (!exact) {
               island.add(last.at(-last.halfWidth() - 0.2, 0.0));
            }

            island.add(s.center());
            if (tail > 0.0) {
               int count = (int)Math.ceil(tail * 2.0);

               for (int k = 1; k <= count; k++) {
                  samples.add(new RoadGeometry.Sample(last.center().add(g.mul(tail * (double)k / (double)count)), last.left(), 0.0, last.halfWidth()));
               }

               motor = RoadRibbon.mesh(samples, JunctionPlanner.settings(width, s.thickness()));
            }

            ArrayList<Double> widths = new ArrayList<>();
            ArrayList<RoadGeometry.Sample> wide = new ArrayList<>();

            for (RoadGeometry.Sample at : motor.samples()) {
               double cw = cycle;
               double blend = 0.0;
               if (exact) {
                  blend = fromCycle > 0.0 && toCycle == 0.0
                     ? smooth((at.distance() - turnLength - 4.0) / Math.max(1.0, tail - 4.0))
                     : (fromCycle == 0.0 ? smooth(at.distance() / 24.0) : smooth(at.distance() / turnLength));
                  cw = fromCycle + (toCycle - fromCycle) * blend;
               }

               double shoulder = smoothEdges ? (fromCycle == 0.0 ? 0.5 : 0.0) * (1.0 - blend) + (toCycle == 0.0 ? 0.5 : 0.0) * blend : 0.0;
               double outer = cw + shoulder;
               widths.add(cw);
               wide.add(new RoadGeometry.Sample(at.at(outer / 2.0, 0.0), at.left(), 0.0, at.halfWidth() + outer / 2.0));
            }

            RoadGeometry.Mesh mesh = cycle == 0.0 && !smoothEdges
               ? motor
               : RoadRibbon.mesh(wide, JunctionPlanner.settings(width + cycle + (smoothEdges ? 0.5 : 0.0), s.thickness()));
            result.add(
               new JunctionSlips.Slip(
                  i, j, mesh, motor, List.copyOf(island), da, db - tail, cycle, List.copyOf(widths), mesh.samples().get(turnSamples - 1).distance()
               )
            );
         }
      }

      return List.copyOf(result);
   }

   public static List<RoadStructures.Part> clear(List<RoadStructures.Part> parts, List<RoadGeometry.Mesh> slips) {
      SidewalkJoins walkClip = new SidewalkJoins(slips, List.of());
      ArrayList<RoadStructures.Part> out = new ArrayList<>();

      for (RoadStructures.Part p : walkClip.clip(parts)) {
         if (RoadSidewalks.smoothPart(p)) {
            out.add(p);
         } else if ((
               p.material() == RoadStructures.Material.GREEN
                  || p.material() == RoadStructures.Material.SOIL
                  || p.material() == RoadStructures.Material.CONCRETE
            )
            && p.height() <= 1.2
            && p.width() <= 3.0
            && !p.pier()) {
            RoadStructures.Part proxy = new RoadStructures.Part(
               p.a(), p.b(), p.width(), p.height(), false, RoadStructures.Material.WALK_STONE_BRICKS, p.frameA(), p.frameB()
            );

            for (RoadStructures.Part bit : new SidewalkJoins(slips, List.of()).clip(List.of(proxy))) {
               out.add(new RoadStructures.Part(bit.a(), bit.b(), bit.width(), p.height(), false, p.material(), bit.frameA(), bit.frameB(), p.model(), null));
            }
         } else {
            boolean blocked = false;
            Iterator var7 = slips.iterator();

            while (true) {
               if (var7.hasNext()) {
                  RoadGeometry.Mesh m = (RoadGeometry.Mesh)var7.next();
                  if (Math.max(p.a().y(), p.b().y()) + p.height() < m.min().y() + m.settings().thickness() - 0.02 || !RoadClearance.structureInvades(p, m, 4.0)
                     )
                   {
                     continue;
                  }

                  blocked = true;
               }

               if (!blocked) {
                  out.add(p);
               }
               break;
            }
         }
      }

      return List.copyOf(out);
   }

   public static List<RoadStructures.Part> clearRefugePlanting(List<RoadStructures.Part> parts, List<RoadGeometry.Mesh> refuges) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      ArrayList<RoadStructures.Part> planting = new ArrayList<>();

      for (RoadStructures.Part p : parts) {
         if (p.model().equals("sr:cycle_planter")) {
            planting.add(p);
         } else {
            out.add(p);
         }
      }

      out.addAll(clear(planting, refuges));
      return List.copyOf(out);
   }

   public static List<RoadGeometry.Mesh> meshes(JunctionPlanner.Plan plan) {
      return plan.pieces().stream().filter(p -> p.arm() <= -2).map(JunctionPlanner.Piece::mesh).toList();
   }

   public static RoadSurface.Geometry exposedSurface(RoadSurface.Geometry surface, List<RoadStructures.Part> parts) {
      List<List<RoadGeometry.V>> paving = parts.stream().filter(p -> p.material().name().startsWith("WALK_")).map(RoadStructures.Part::base).toList();
      if (paving.isEmpty()) {
         return surface;
      } else {
         RoadSurface.PaintMask mask = new RoadSurface.PaintMask(paving);
         ArrayList<RoadSurface.Face> pavement = new ArrayList<>();

         for (RoadSurface.Face face : surface.pavement()) {
            if (face.texture() == RoadSurface.Texture.PLAIN && !(Math.abs(RoadLighting.normal(face).y()) < 0.5)) {
               for (List<RoadGeometry.V> polygon : mask.clip(face.points())) {
                  pavement.add(new RoadSurface.Face(polygon, face.color(), face.emissive(), face.texture()));
               }
            } else {
               pavement.add(face);
            }
         }

         return new RoadSurface.Geometry(List.copyOf(pavement), clipPaint(surface.markings(), mask));
      }
   }

   public static JunctionPlanner.Plan apply(JunctionSpec s, JunctionPlanner.Plan base) {
      return apply(s, base, true);
   }

   static JunctionPlanner.Plan apply(JunctionSpec s, JunctionPlanner.Plan base, boolean current) {
      return apply(s, base, current, true);
   }

   static JunctionPlanner.Plan apply(JunctionSpec s, JunctionPlanner.Plan base, boolean current, boolean exact) {
      return apply(s, base, current, exact, false);
   }

   static JunctionPlanner.Plan apply(JunctionSpec s, JunctionPlanner.Plan base, boolean current, boolean exact, boolean transitionPaint) {
      return apply(s, base, current, exact, transitionPaint, false);
   }

   static JunctionPlanner.Plan apply(JunctionSpec s, JunctionPlanner.Plan base, boolean current, boolean exact, boolean transitionPaint, boolean smoothEdges) {
      if (!enabled(s)) {
         return base;
      } else {
         List<RoadGeometry.Mesh> approaches = base.pieces().subList(0, s.arms().size()).stream().map(JunctionPlanner.Piece::mesh).toList();
         List<JunctionSlips.Slip> layout = layout(s, approaches, exact, smoothEdges);
         JunctionSlipProfiles profiles = new JunctionSlipProfiles(s, approaches, exact, smoothEdges);
         base = profiles.apply(base);
         List<RoadGeometry.Mesh> lanes = layout.stream().map(JunctionSlips.Slip::mesh).toList();
         ArrayList<RoadGeometry.Mesh> replaced = new ArrayList<>(lanes);
         replaced.addAll(profiles.refugeStrips());
         RoadSurface.PaintMask laneMask = new RoadSurface.PaintMask(polygons(replaced));
         List<RoadGeometry.Mesh> islands = layout.stream().map(v -> islandMesh(s, v)).toList();
         ArrayList<JunctionPlanner.Piece> pieces = new ArrayList<>();

         for (JunctionPlanner.Piece p : base.pieces()) {
            List<RoadStructures.Part> retained = clear(p.structures(), replaced)
               .stream()
               .filter(v -> v.material() != RoadStructures.Material.TACTILE || !JunctionSlipWalks.affected(s, approaches, layout, v))
               .toList();
            List<RoadStructures.Part> paving = retained.stream().filter(v -> v.material().name().startsWith("WALK_")).toList();
            ArrayList<RoadStructures.Part> parts = new ArrayList<>(retained.stream().filter(v -> !v.material().name().startsWith("WALK_")).toList());
            parts.addAll(new SidewalkJoins(islands, List.of()).clip(paving));
            pieces.add(new JunctionPlanner.Piece(p.mesh(), clipPaint(p.paint(), laneMask), List.copyOf(parts), p.arm()));
         }

         for (JunctionSlips.Slip slip : layout) {
            int i = slip.from();
            JunctionPlanner.Piece old = pieces.get(i);
            ArrayList<RoadSurface.Face> paint = new ArrayList<>(old.paint());
            JunctionSpec.Arm a = s.arms().get(i);
            RoadGeometry.Mesh m = approaches.get(i);
            ArrayList<RoadGeometry.V> divider = new ArrayList<>();

            for (RoadGeometry.Sample p : m.samples()) {
               if (p.distance() >= slip.departure() - 28.0 && p.distance() <= slip.departure() + 2.0) {
                  divider.add(p.at(a.motorEdge(true, false), -0.014));
               }
            }

            JunctionPaint.path(paint, divider, 0.14, true, 15593186);
            pieces.set(i, new JunctionPlanner.Piece(old.mesh(), List.copyOf(paint), old.structures(), old.arm()));
         }

         ArrayList<JunctionPlanner.Movement> movements = new ArrayList<>(base.movements());
         LinkedHashMap<RoadSidewalks.Cell, RoadSidewalks.Config> walks = new LinkedHashMap<>(base.sidewalks());
         walks.keySet()
            .removeIf(
               c -> lanes.stream()
                     .anyMatch(m -> RoadQueries.contains(m, new RoadGeometry.V((double)c.x() + 0.5, (double)c.y(), (double)c.z() + 0.5), 0.1, 0.7))
            );
         List<RoadGeometry.Mesh> original = base.pieces().stream().map(JunctionPlanner.Piece::mesh).toList();
         RoadSurface.PaintMask oldRoadMask = new RoadSurface.PaintMask(polygons(original));

         for (JunctionSlips.Slip slip : layout) {
            JunctionSpec.Arm arm = s.arms().get(slip.from());
            RoadGeometry.Mesh road = slip.mesh();
            ArrayList<RoadSurface.Face> paint = new ArrayList<>();
            ArrayList<RoadStructures.Part> parts = new ArrayList<>();

            for (int side : new int[]{-1, 1}) {
               List<RoadGeometry.V> path = road.samples().stream().map(v -> v.at((double)side * (v.halfWidth() - 0.12), -0.016)).toList();
               JunctionPaint.path(paint, path, 0.14, false, 15593186);
            }

            paint = new ArrayList<>(clipPaint(paint, oldRoadMask));
            RoadGeometry.Sample arrow = RoadStructures.sample(slip.motor(), slip.motor().length() * 0.18);
            JunctionPaint.arrow(
               paint, arrow.center().add(new RoadGeometry.V(0.0, -0.02, 0.0)), arrow.left().left().mul(-1.0), 4, Math.min(1.0, arrow.halfWidth() / 1.9)
            );
            ArrayList<RoadStructures.Part> pavement = new ArrayList<>();

            for (List<RoadGeometry.V> tri : islandTriangles(slip, exact)) {
               pavement.add(trianglePart(tri, RoadStructures.Material.WALK_STONE_BRICKS, 0.2));
            }

            pavement = new ArrayList<>(new SidewalkJoins(original, List.of()).clip(pavement));
            if (pavement.stream().mapToDouble(px -> Math.abs(JunctionPaint.area(px.base()))).sum() < 12.0) {
               throw new IllegalArgumentException("独立右转岛区空间不足，请增大转角半径或调整相邻道路夹角");
            }

            RoadGeometry.Mesh islandMesh = islandMesh(s, slip);
            boolean pedestrian = arm.external().options().sidewalk().enabled() || arm.cycleWidth() > 0.01 || exact && walkSpan(s.arms().get(slip.to())) > 0.0;
            boolean green = !current && !pedestrian && arm.median() > 0.0 && arm.medianKind() == RoadProfile.Median.GREEN;
            ArrayList<RoadStructures.Part> islandParts = new ArrayList<>();
            ArrayList<RoadSurface.Face> islandPaint = new ArrayList<>();

            for (RoadStructures.Part px : pavement) {
               if (pedestrian) {
                  islandParts.add(
                     material(
                        px,
                        arm.external().options().sidewalk().enabled()
                           ? RoadSidewalks.Finish.of(arm.external().options().sidewalk().material()).material()
                           : RoadStructures.Material.WALK_STONE_BRICKS,
                        0.2
                     )
                  );
               } else if (green) {
                  islandParts.add(material(px, RoadStructures.Material.SOIL, 0.25));
                  RoadStructures.Part soil = material(px, RoadStructures.Material.GREEN, 0.35);
                  islandParts.add(
                     new RoadStructures.Part(
                        soil.a().add(new RoadGeometry.V(0.0, 0.25, 0.0)),
                        soil.b().add(new RoadGeometry.V(0.0, 0.25, 0.0)),
                        soil.width(),
                        soil.height(),
                        false,
                        soil.material(),
                        soil.frameA(),
                        soil.frameB()
                     )
                  );
               } else {
                  hatch(islandPaint, px.base(), exact);
               }
            }

            RoadSidewalks.Config config = arm.external().options().sidewalk();
            if (slip.cycleWidth() > 0.0) {
               JunctionSpec.Arm cycleArm = arm.cycleWidth() > 0.01 ? arm : s.arms().get(slip.to());
               double separation = RoadStreetscape.separatorWidth(cycleArm.external().options());

               for (int k = 1; k < road.samples().size(); k++) {
                  RoadGeometry.Sample a = road.samples().get(k - 1);
                  RoadGeometry.Sample b = road.samples().get(k);
                  double ca = slip.cycle(k - 1);
                  double cb = slip.cycle(k);
                  double ia = a.halfWidth() - ca;
                  double ib = b.halfWidth() - cb;
                  if (!(Math.max(ca, cb) < 0.01)) {
                     double fa = ca / slip.cycleWidth();
                     double fb = cb / slip.cycleWidth();
                     JunctionPaint.line(paint, a.at(ia, -0.016), b.at(ib, -0.016), 0.13, 15593186);
                     if (!cycleArm.external().options().cycleAsphalt()) {
                        paint.add(
                           new RoadSurface.Face(
                              List.of(
                                 a.at(ia + (separation + 0.05) * fa, -0.009),
                                 a.at(a.halfWidth() - 0.08 * fa, -0.009),
                                 b.at(b.halfWidth() - 0.08 * fb, -0.009),
                                 b.at(ib + (separation + 0.05) * fb, -0.009)
                              ),
                              5468001
                           )
                        );
                     }

                     boolean paintOnly = transitionPaint
                        && (
                           arm.cycleWidth() <= 0.01 && b.distance() <= slip.turnEnd() * 0.5 + 1.8
                              || s.arms().get(slip.to()).cycleWidth() <= 0.01 && a.distance() >= slip.turnEnd() * 0.5 - 1.8
                        );
                     if (!(separation > 0.0) || paintOnly || !current && !(Math.abs(a.distance() - road.length() * 0.5) > 2.2)) {
                        if (transitionPaint && !paintOnly && cycleArm.external().options().streetscape().separator() == RoadStreetscape.Separator.RAIL) {
                           RoadStructures.barrier(parts, a.at(ia, 0.0), b.at(ib, 0.0), false, false, a.distance());
                        }
                     } else {
                        RoadStructures.cyclePlanter(parts, a, b, 1, ia, ib, separation, fa, fb);
                     }
                  }
               }
            }

            if (pedestrian) {
               if (current) {
                  RoadGeometry.Mesh opening = crossingOpening(road, exact ? slip.turnEnd() * 0.5 : road.length() * 0.5);
                  paint = new ArrayList<>(clipPaint(paint, new RoadSurface.PaintMask(polygons(List.of(opening)))));
                  parts = new ArrayList<>(clear(parts, List.of(opening)));
               }

               RoadSidewalks.Config walk = config.enabled() ? config : new RoadSidewalks.Config(true, RoadSidewalks.Side.RIGHT, 3, "minecraft:stone_bricks");
               walk = walk.side(RoadSidewalks.Side.RIGHT);
               List<RoadStructures.Part> walking = exact
                  ? RoadSidewalks.variableParts(
                     road, walk.tactile(false), road.samples().stream().map(pxx -> walkWidth(s, slip, pxx.distance(), smoothEdges)).toList()
                  )
                  : RoadSidewalks.parts(road, walk.tactile(false));
               parts.addAll(new SidewalkJoins(original, pieces.stream().flatMap(pxx -> pxx.structures().stream()).toList()).clip(walking));
               double station = exact ? slip.turnEnd() * 0.5 : road.length() * 0.5;
               RoadGeometry.Sample at = RoadStructures.sample(road, station);
               RoadGeometry.V forward = at.left().left().mul(-1.0);

               for (double x = -at.halfWidth() + 0.35; x < at.halfWidth() - 0.2; x++) {
                  JunctionPaint.line(paint, at.at(x, -0.018).sub(forward.mul(1.8)), at.at(x, -0.018).add(forward.mul(1.8)), 0.7, 15593186);
               }

               RoadGeometry.V inner = at.at(-at.halfWidth() - 0.6, 0.0);
               RoadGeometry.V outer = at.at(at.halfWidth() + 0.6, 0.0);
               warning(islandParts, inner, forward);
               warning(parts, outer, forward);
               if (walk.tactile()) {
                  JunctionSlipWalks.add(s, approaches, profiles, slip, walk, inner, outer, parts, islandParts, exact, smoothEdges);
               }
            }

            RoadGeometry.Sample yield = RoadStructures.sample(slip.motor(), slip.motor().length() * 0.72);
            RoadGeometry.V forward = yield.left().left().mul(-1.0);
            List<RoadGeometry.V> triangle = List.of(
               yield.at(-Math.min(1.2, yield.halfWidth() - 0.3), -0.018).sub(forward),
               yield.at(Math.min(1.2, yield.halfWidth() - 0.3), -0.018).sub(forward),
               yield.center().add(forward.mul(1.8)).add(new RoadGeometry.V(0.0, -0.018, 0.0))
            );

            for (int kx = 0; kx < 3; kx++) {
               JunctionPaint.line(paint, triangle.get(kx), triangle.get((kx + 1) % 3), 0.18, 15593186);
            }

            RoadSurface.PaintMask whiteMask = new RoadSurface.PaintMask(
               paint.stream().filter(f -> f.color() == 15593186).map(RoadSurface.Face::points).toList()
            );
            ArrayList<RoadSurface.Face> disjoint = new ArrayList<>();

            for (RoadSurface.Face face : paint) {
               if (face.color() == 15593186) {
                  disjoint.add(face);
               } else {
                  disjoint.addAll(clipPaint(List.of(face), whiteMask));
               }
            }

            pieces.add(new JunctionPlanner.Piece(islandMesh, List.copyOf(islandPaint), List.copyOf(islandParts), -1));
            pieces.add(new JunctionPlanner.Piece(road, List.copyOf(disjoint), List.copyOf(parts), -2 - slip.from()));
            movements.removeIf(m -> m.from() == slip.from() && m.turn() == 4);
            movements.add(
               new JunctionPlanner.Movement(
                  slip.from(),
                  arm.incoming(),
                  slip.to(),
                  s.arms().get(slip.to()).outgoing() - 1,
                  4,
                  slip.motor().samples().stream().map(RoadGeometry.Sample::center).toList()
               )
            );
         }

         return new JunctionPlanner.Plan(
            List.copyOf(pieces), List.copyOf(movements), base.boundary(), Collections.unmodifiableMap(walks), base.signals(), base.heads()
         );
      }
   }

   private static List<List<RoadGeometry.V>> polygons(List<RoadGeometry.Mesh> meshes) {
      ArrayList<List<RoadGeometry.V>> out = new ArrayList<>();

      for (RoadGeometry.Mesh mesh : meshes) {
         for (int i = 1; i < mesh.samples().size(); i++) {
            RoadGeometry.Sample a = mesh.samples().get(i - 1);
            RoadGeometry.Sample b = mesh.samples().get(i);
            out.add(List.of(a.at(-a.halfWidth(), 0.0), a.at(a.halfWidth(), 0.0), b.at(b.halfWidth(), 0.0), b.at(-b.halfWidth(), 0.0)));
         }
      }

      return out;
   }

   private static List<List<RoadGeometry.V>> islandTriangles(JunctionSlips.Slip slip, boolean exact) {
      if (!exact) {
         return JunctionPaint.triangulate(slip.island());
      } else {
         ArrayList<List<RoadGeometry.V>> out = new ArrayList<>();
         List<RoadGeometry.V> p = slip.island();
         RoadGeometry.V center = p.get(p.size() - 1);

         for (int i = 1; i < p.size() - 1; i++) {
            out.add(List.of(center, p.get(i - 1), p.get(i)));
         }

         double sum = out.stream().mapToDouble(q -> Math.abs(JunctionPaint.area((List<RoadGeometry.V>)q))).sum();
         return Math.abs(sum - Math.abs(JunctionPaint.area(p))) > Math.max(1.0E-7, sum * 1.0E-9) ? JunctionPaint.triangulate(p) : List.copyOf(out);
      }
   }

   private static RoadGeometry.Mesh islandMesh(JunctionSpec s, JunctionSlips.Slip slip) {
      ArrayList<RoadGeometry.Sample> fan = new ArrayList<>();
      RoadGeometry.V apex = s.center();

      for (int k = 0; k < slip.island().size() - 1; k++) {
         RoadGeometry.V edge = slip.island().get(k);
         RoadGeometry.V delta = edge.sub(apex);
         fan.add(new RoadGeometry.Sample(edge.add(apex).mul(0.5), delta.horizontalUnit(), 0.0, delta.horizontalLength() / 2.0));
      }

      return fanMesh(fan, s.thickness());
   }

   private static List<RoadSurface.Face> clipPaint(List<RoadSurface.Face> faces, RoadSurface.PaintMask mask) {
      ArrayList<RoadSurface.Face> out = new ArrayList<>();

      for (RoadSurface.Face f : faces) {
         for (List<RoadGeometry.V> p : mask.clip(f.points())) {
            out.add(new RoadSurface.Face(p, f.color()));
         }
      }

      return List.copyOf(out);
   }

   private static RoadStructures.Part trianglePart(List<RoadGeometry.V> p, RoadStructures.Material m, double height) {
      RoadGeometry.V a = p.get(0);
      RoadGeometry.V b = p.get(1);
      RoadGeometry.V c = p.get(2);
      return new RoadStructures.Part(
         a.add(b).mul(0.5), c, Math.max(0.001, Math.min(8.0, a.distance(b))), height, false, m, a.sub(b).mul(0.5), new RoadGeometry.V(0.0, 0.0, 0.0)
      );
   }

   private static RoadGeometry.Mesh fanMesh(List<RoadGeometry.Sample> raw, double thickness) {
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
      ArrayList<RoadGeometry.V> bounds = new ArrayList<>();
      double d = 0.0;
      RoadGeometry.V previous = null;

      for (RoadGeometry.Sample p : raw) {
         if (previous != null) {
            double step = p.center().distance(previous);
            if (step < 1.0E-7) {
               continue;
            }

            d += step;
         }

         samples.add(new RoadGeometry.Sample(p.center(), p.left(), d, p.halfWidth()));
         bounds.add(p.at(p.halfWidth(), 0.0));
         bounds.add(p.at(-p.halfWidth(), 0.0));
         previous = p.center();
      }

      double y = samples.get(0).center().y();
      RoadGeometry.V min = new RoadGeometry.V(
         bounds.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(), y - thickness, bounds.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow()
      );
      RoadGeometry.V max = new RoadGeometry.V(
         bounds.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(), y, bounds.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()
      );
      return new RoadGeometry.Mesh(List.copyOf(samples), JunctionPlanner.settings(4.0, thickness), min, max, d, false);
   }

   private static RoadStructures.Part material(RoadStructures.Part p, RoadStructures.Material m, double height) {
      return new RoadStructures.Part(p.a(), p.b(), p.width(), height, p.pier(), m, p.frameA(), p.frameB());
   }

   private static void warning(List<RoadStructures.Part> out, RoadGeometry.V p, RoadGeometry.V forward) {
      out.add(
         new RoadStructures.Part(
            p.sub(forward.mul(0.4)).add(new RoadGeometry.V(0.0, 0.2, 0.0)),
            p.add(forward.mul(0.4)).add(new RoadGeometry.V(0.0, 0.2, 0.0)),
            0.8,
            0.02,
            false,
            RoadStructures.Material.TACTILE
         )
      );

      for (double x : new double[]{-0.24, 0.0, 0.24}) {
         for (double z : new double[]{-0.24, 0.0, 0.24}) {
            RoadGeometry.V q = p.add(forward.mul(x)).add(forward.left().mul(z)).add(new RoadGeometry.V(0.0, 0.22, 0.0));
            out.add(new RoadStructures.Part(q, q, 0.08, 0.025, true, RoadStructures.Material.TACTILE));
         }
      }
   }

   private static void hatch(List<RoadSurface.Face> out, List<RoadGeometry.V> polygon, boolean exact) {
      if (exact) {
         hatchBands(out, polygon);
      } else {
         double low = polygon.stream().mapToDouble(p -> p.x() + p.z()).min().orElse(0.0);
         double high = polygon.stream().mapToDouble(p -> p.x() + p.z()).max().orElse(0.0);

         for (double v = Math.ceil(low / 3.0) * 3.0; v < high; v += 3.0) {
            ArrayList<RoadGeometry.V> hits = new ArrayList<>();

            for (int i = 0; i < polygon.size(); i++) {
               RoadGeometry.V a = polygon.get(i);
               RoadGeometry.V b = polygon.get((i + 1) % polygon.size());
               double d = b.x() + b.z() - a.x() - a.z();
               if (!(Math.abs(d) < 1.0E-9)) {
                  double t = (v - a.x() - a.z()) / d;
                  if (t >= 0.0 && t < 1.0) {
                     hits.add(a.add(b.sub(a).mul(t)).add(new RoadGeometry.V(0.0, -0.018, 0.0)));
                  }
               }
            }

            if (hits.size() == 2) {
               ArrayList<RoadSurface.Face> stripe = new ArrayList<>();
               JunctionPaint.line(stripe, hits.get(0), hits.get(1), 0.35, 15593186);

               for (RoadSurface.Face face : stripe) {
                  List<RoadGeometry.V> points = exact ? RoadSurface.intersect(face.points(), polygon) : face.points();
                  if (points.size() >= 3) {
                     out.add(new RoadSurface.Face(points, face.color()));
                  }
               }
            }
         }
      }
   }

   private static void hatchBands(List<RoadSurface.Face> out, List<RoadGeometry.V> polygon) {
      RoadGeometry.V normal = new RoadGeometry.V(1.0, 0.0, 1.0).horizontalUnit();
      RoadGeometry.V direction = new RoadGeometry.V(1.0, 0.0, -1.0).horizontalUnit();
      double low = polygon.stream().mapToDouble(p -> p.x() + p.z()).min().orElse(0.0);
      double high = polygon.stream().mapToDouble(p -> p.x() + p.z()).max().orElse(0.0);
      double from = polygon.stream().mapToDouble(p -> p.dot(direction)).min().orElse(0.0) - 1.0;
      double to = polygon.stream().mapToDouble(p -> p.dot(direction)).max().orElse(0.0) + 1.0;
      double half = 0.175;
      double margin = half * Math.sqrt(2.0);
      double y = polygon.get(0).y() - 0.018;

      for (double v = Math.ceil((low - margin) / 3.0) * 3.0; v < high + margin; v += 3.0) {
         RoadGeometry.V axis = normal.mul(v / Math.sqrt(2.0)).add(new RoadGeometry.V(0.0, y, 0.0));
         RoadGeometry.V a = axis.add(direction.mul(from));
         RoadGeometry.V b = axis.add(direction.mul(to));
         RoadGeometry.V n = normal.mul(half);
         List<RoadGeometry.V> points = RoadSurface.intersect(List.of(a.add(n), a.sub(n), b.sub(n), b.add(n)), polygon);
         if (points.size() >= 3) {
            out.add(new RoadSurface.Face(points, 15593186));
         }
      }
   }

   public static double advanceSignStation(JunctionSpec s, int arm, RoadGeometry.Mesh approach) {
      return approach.length() - run(s, arm, target(s, arm)) - 36.0;
   }

   public static RoadGeometry.Mesh crossingOpening(RoadGeometry.Mesh road) {
      return crossingOpening(road, road.length() * 0.5);
   }

   static RoadGeometry.Mesh crossingOpening(RoadGeometry.Mesh road, double station) {
      RoadGeometry.Sample at = RoadStructures.sample(road, station);
      RoadGeometry.V f = at.left().left().mul(-1.0);
      return RoadRibbon.mesh(
         List.of(
            new RoadGeometry.Sample(at.center().sub(f.mul(2.3)), at.left(), 0.0, at.halfWidth() + 1.0),
            new RoadGeometry.Sample(at.center().add(f.mul(2.3)), at.left(), 0.0, at.halfWidth() + 1.0)
         ),
         JunctionPlanner.settings((at.halfWidth() + 1.0) * 2.0, road.settings().thickness())
      );
   }

   private JunctionSlips() {
   }

   public static record Slip(
      int from,
      int to,
      RoadGeometry.Mesh mesh,
      RoadGeometry.Mesh motor,
      List<RoadGeometry.V> island,
      double departure,
      double arrival,
      double cycleWidth,
      List<Double> cycleWidths,
      double turnEnd
   ) {
      public double cycle(int sample) {
         return this.cycleWidths.get(sample);
      }
   }
}
