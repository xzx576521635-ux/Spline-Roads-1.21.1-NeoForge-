package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.DoublePredicate;
import java.util.function.Predicate;

public final class RoadStructures {
   public static final double PIER_SPACING = 24.0;
   public static final double MAX_DROP = 96.0;
   public static final double GROUND_CONTACT = 0.125;

   public static List<RoadStructures.Part> plan(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      return plan(mesh, ground, RoadFurniture.Phase.DEFAULT);
   }

   public static boolean railModern(RoadGeometry.Style style) {
      return RoadProfile.modern(style) || style == RoadGeometry.Style.UNMARKED;
   }

   public static List<RoadStructures.Part> plan(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, RoadFurniture.Phase phase) {
      if (mesh.settings().structure() == RoadGeometry.Structure.TUNNEL) {
         ArrayList<RoadStructures.Part> out = new ArrayList<>(RoadInfrastructure.plan(mesh, ground));
         return List.copyOf(out);
      } else {
         mesh = RoadStreetscape.resolve(mesh, ground);
         boolean modern = railModern(mesh.settings().style());
         boolean highway = RoadProfile.highway(mesh.settings().style());
         if (!modern && mesh.settings().style() != RoadGeometry.Style.RAILBED && mesh.settings().structure() == RoadGeometry.Structure.GROUND) {
            return RoadGantry.plan(mesh, ground);
         } else {
            List<RoadStructures.Part> out = new ArrayList<>();
            boundaryRails(out, mesh, ground, modern, highway);
            if (RoadProfile.modern(mesh.settings().style())) {
               furniture(mesh, ground, out, phase);
            }

            if (modern) {
               terminalPosts(out, ground);
            }

            out.addAll(RoadInfrastructure.plan(mesh, ground, out));
            out.addAll(RailbedBridges.plan(mesh, ground));
            out.addAll(RailwayCrossingSigns.plan(mesh, ground));
            out.addAll(LaneClosureLandscape.plan(mesh, ground));
            if (RoadInfrastructure.customBridge(mesh) && !ground.sharedDeck()) {
               out.addAll(RoadSidewalks.parts(mesh, mesh.settings().options().sidewalk(), ground, out).stream().filter(p -> !ground.blocked(p)).toList());
               return List.copyOf(out);
            } else {
               out.addAll(edgeSlabs(mesh, ground));
               out.addAll(groundSkirts(mesh, ground));
               out.addAll(supports(mesh, ground, phase, out));
               out.addAll(RoadSidewalks.parts(mesh, mesh.settings().options().sidewalk(), ground, out).stream().filter(p -> !ground.blocked(p)).toList());
               return List.copyOf(out);
            }
         }
      }
   }

   public static List<RoadStructures.Part> roadsideSupports(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      if (mesh.settings().structure() == RoadGeometry.Structure.TUNNEL) {
         return List.of();
      } else {
         mesh = RoadStreetscape.resolve(mesh, ground);
         ArrayList<RoadStructures.Part> out = new ArrayList<>();
         boundaryRails(out, mesh, ground, railModern(mesh.settings().style()), RoadProfile.highway(mesh.settings().style()));
         if (railModern(mesh.settings().style())) {
            terminalPosts(out, ground);
         }

         if (!RoadInfrastructure.customBridge(mesh)) {
            out.addAll(edgeSlabs(mesh, ground));
         }

         return List.copyOf(out);
      }
   }

   private static void boundaryRails(List<RoadStructures.Part> out, RoadGeometry.Mesh mesh, RoadStructures.Ground ground, boolean modern, boolean highway) {
      for (int side : new int[]{-1, 1}) {
         if (mesh.settings().style() != RoadGeometry.Style.RAILBED || mesh.settings().options().railbed().fittings().rail(side)) {
            ArrayList<RoadStructures.RailSpan> run = new ArrayList<>();
            double trim = mesh.settings().style() == RoadGeometry.Style.UNMARKED ? Math.min(0.34, mesh.length() / 4.0) : 0.0;
            TreeSet<Double> stations = new TreeSet<>();

            for (double d = trim; d < mesh.length() - trim - 1.0E-6; d += 0.5) {
               stations.add(d);
            }

            stations.add(mesh.length() - trim);

            for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
               if (cut.rectangular() && !cut.temporary()) {
                  for (double edge : new double[]{cut.begin(), cut.end()}) {
                     for (double d : new double[]{edge - 1.0E-5, edge, edge + 1.0E-5}) {
                        if (d > trim && d < mesh.length() - trim) {
                           stations.add(d);
                        }
                     }
                  }
               }
            }

            ArrayList<Double> ordered = new ArrayList<>(stations);

            for (int i = 1; i < ordered.size(); i++) {
               double dx = ordered.get(i - 1);
               RoadGeometry.Sample a = sample(mesh, dx);
               RoadGeometry.Sample b = sample(mesh, ordered.get(i));
               double insetA = RoadRailJoin.inset(mesh, a, side);
               double insetB = RoadRailJoin.inset(mesh, b, side);
               RoadGeometry.V aa = a.at((double)side * (a.halfWidth() - insetA), 0.0);
               RoadGeometry.V bb = b.at((double)side * (b.halfWidth() - insetB), 0.0);
               RoadGeometry.V mid = aa.add(bb).mul(0.5);
               boolean raised = RoadStreetscape.raised(mesh, sample(mesh, (a.distance() + b.distance()) / 2.0), side);
               if (!raised) {
                  for (RoadGeometry.V point : List.of(aa, mid, bb)) {
                     if (elevated(mesh, point, ground)) {
                        raised = true;
                        break;
                     }
                  }
               }

               RoadGeometry.V sum = a.left().add(b.left());
               RoadGeometry.V normal = sum.horizontalLength() < 1.0E-7 ? a.left() : sum.horizontalUnit();
               RoadGeometry.V outside = mid.add(normal.mul((double)side * (modern ? 0.4 : 0.14)));
               boolean visible = bb.sub(aa).horizontalLength() > 1.0E-7
                  && !LaneDeck.outerOpening(mesh, (a.distance() + b.distance()) / 2.0, side)
                  && mesh.settings().options().outerRail() != RoadProfile.OuterRail.OFF
                  && (
                     mesh.settings().style() == RoadGeometry.Style.RAILBED
                        || mesh.settings().options().outerRail() == RoadProfile.OuterRail.ON
                        || highway
                        || modern && mesh.settings().style().ramp()
                        || mesh.settings().structure() == RoadGeometry.Structure.BRIDGE
                        || mesh.settings().structure() != RoadGeometry.Structure.GROUND && raised
                  );
               boolean transverse = b.distance() - a.distance() < 3.0E-5 && bb.sub(aa).horizontalLength() > 0.01;
               if (visible && transverse) {
                  emitOuterRailRun(out, run, mesh, ground, modern, highway, side);
                  run.clear();

                  for (RoadRailJoin.Span span : ground.capRailSpans(aa, bb, outside)) {
                     emitCapRail(out, ground, span, highway, raised);
                  }
               } else {
                  List<RoadRailJoin.Span> spans = visible ? ground.railSpans(aa, bb, outside, (insetA + insetB) / 2.0) : List.of();
                  if (spans.isEmpty()) {
                     emitOuterRailRun(out, run, mesh, ground, modern, highway, side);
                     run.clear();
                  }

                  for (RoadRailJoin.Span span : spans) {
                     if (!run.isEmpty() && run.get(run.size() - 1).b().distance(span.a()) > 1.0E-5) {
                        emitOuterRailRun(out, run, mesh, ground, modern, highway, side);
                        run.clear();
                     }

                     double fraction = aa.sub(bb).horizontalLength() < 1.0E-9 ? 0.0 : aa.sub(span.a()).horizontalLength() / aa.sub(bb).horizontalLength();
                     double endFraction = aa.sub(bb).horizontalLength() < 1.0E-9 ? 1.0 : aa.sub(span.b()).horizontalLength() / aa.sub(bb).horizontalLength();
                     run.add(
                        new RoadStructures.RailSpan(
                           span.a(), span.b(), raised, dx + fraction * (b.distance() - a.distance()), dx + endFraction * (b.distance() - a.distance())
                        )
                     );
                  }

                  if (!spans.isEmpty() && spans.get(spans.size() - 1).b().distance(bb) > 1.0E-5) {
                     emitOuterRailRun(out, run, mesh, ground, modern, highway, side);
                     run.clear();
                  }
               }
            }

            emitOuterRailRun(out, run, mesh, ground, modern, highway, side);
         }
      }

      if (mesh.settings().style() != RoadGeometry.Style.RAILBED) {
         padEndRails(out, mesh, ground);
         closureRails(out, mesh, ground, modern, highway);
      }
   }

   public static List<RoadStructures.Part> edgeSlabs(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      double depth = mesh.settings().thickness();

      for (int i = 1; i < mesh.samples().size(); i++) {
         RoadGeometry.Sample a = mesh.samples().get(i - 1);
         RoadGeometry.Sample b = mesh.samples().get(i);
         RoadGeometry.Sample mid = sample(mesh, (a.distance() + b.distance()) / 2.0);

         for (int side : new int[]{-1, 1}) {
            if (RoadStreetscape.raised(mesh, mid, side) && !LaneDeck.outerOpening(mesh, mid.distance(), side)) {
               double ra = 0.35 * RoadInfrastructure.endTaper(mesh, a);
               double rb = 0.35 * RoadInfrastructure.endTaper(mesh, b);
               RoadGeometry.V first = a.at((double)side * (a.halfWidth() + (ra - 0.05) / 2.0), depth);
               RoadGeometry.V last = b.at((double)side * (b.halfWidth() + (rb - 0.05) / 2.0), depth);
               RoadStructures.Part part = new RoadStructures.Part(first, last, Math.max(ra, rb) + 0.05, depth, false, RoadStructures.Material.CONCRETE)
                  .frames(a.left().mul((ra + 0.05) / 2.0), b.left().mul((rb + 0.05) / 2.0));

               for (RoadRailJoin.Span span : ground.railSpans(
                  a.at((double)side * a.halfWidth(), 0.0), b.at((double)side * b.halfWidth(), 0.0), mid.at((double)side * (mid.halfWidth() + 0.2), 0.0), 0.0
               )) {
                  double length = a.center().sub(b.center()).horizontalLength();
                  double ta = length < 1.0E-7
                     ? 0.0
                     : span.a().sub(a.at((double)side * a.halfWidth(), 0.0)).horizontalLength()
                        / Math.max(1.0E-7, a.at((double)side * a.halfWidth(), 0.0).sub(b.at((double)side * b.halfWidth(), 0.0)).horizontalLength());
                  double tb = length < 1.0E-7
                     ? 1.0
                     : span.b().sub(a.at((double)side * a.halfWidth(), 0.0)).horizontalLength()
                        / Math.max(1.0E-7, a.at((double)side * a.halfWidth(), 0.0).sub(b.at((double)side * b.halfWidth(), 0.0)).horizontalLength());
                  RoadStructures.Part clipped = new RoadStructures.Part(
                        first.add(last.sub(first).mul(ta)), first.add(last.sub(first).mul(tb)), part.width(), depth, false, RoadStructures.Material.CONCRETE
                     )
                     .frames(part.frameA().add(part.frameB().sub(part.frameA()).mul(ta)), part.frameA().add(part.frameB().sub(part.frameA()).mul(tb)));
                  if (!ground.blocked(clipped)) {
                     out.add(clipped);
                  }
               }
            }
         }
      }

      return List.copyOf(out);
   }

   public static List<RoadStructures.Part> groundSkirts(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      if (mesh.settings().structure() != RoadGeometry.Structure.BRIDGE && mesh.settings().structure() != RoadGeometry.Structure.TUNNEL) {
         ArrayList<RoadStructures.Part> out = new ArrayList<>();
         double depth = mesh.settings().thickness();

         for (double d = mesh.first().distance(); d < mesh.last().distance() - 1.0E-7; d += 0.5) {
            RoadGeometry.Sample a = sample(mesh, d);
            RoadGeometry.Sample b = sample(mesh, Math.min(mesh.last().distance(), d + 0.5));

            for (int side : new int[]{-1, 1}) {
               RoadGeometry.V aa = a.at((double)side * (a.halfWidth() - 0.035), depth);
               RoadGeometry.V bb = b.at((double)side * (b.halfWidth() - 0.035), depth);
               double ga = ground.top(aa.x(), aa.z(), aa.y() + depth);
               double gb = ground.top(bb.x(), bb.z(), bb.y() + depth);
               double gapA = aa.y() - ga;
               double gapB = bb.y() - gb;
               if (RoadGeometry.finite(ga, gb) && !(Math.max(gapA, gapB) <= 0.005) && !(Math.min(gapA, gapB) < -0.025) && !(Math.max(gapA, gapB) > 0.6)) {
                  double height = Math.max(gapA, gapB) + 0.02;
                  RoadStructures.Part part = new RoadStructures.Part(
                        new RoadGeometry.V(aa.x(), ga - 0.01, aa.z()),
                        new RoadGeometry.V(bb.x(), gb - 0.01, bb.z()),
                        0.1,
                        height,
                        false,
                        RoadStructures.Material.CONCRETE
                     )
                     .frames(a.left().mul(0.05), b.left().mul(0.05));
                  if (!ground.blocked(part)) {
                     out.add(part);
                  }
               }
            }
         }

         return List.copyOf(out);
      } else {
         return List.of();
      }
   }

   public static List<RoadStructures.Part> supports(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, RoadFurniture.Phase phase) {
      return supports(mesh, ground, phase, List.of());
   }

   private static List<RoadStructures.Part> supports(
      RoadGeometry.Mesh mesh, RoadStructures.Ground ground, RoadFurniture.Phase phase, Collection<RoadStructures.Part> fixtures
   ) {
      if (ground.sharedDeck()) {
         return List.of();
      } else {
         RoadSolidOverlap.Index gantries = new RoadSolidOverlap.Index(fixtures.stream().filter(RoadGantry::member).toList());
         Predicate<RoadStructures.Part> blocked = p -> ground.blocked(p) || gantries.intersects(p);
         List<RoadStructures.Part> fixed = ground.fixedSupports();
         if (fixed != null) {
            for (RoadStructures.Part part : fixed) {
               if (blocked.test(part)) {
                  throw new IllegalArgumentException("原桥墩与新的通行区冲突，请先调整相关道路");
               }
            }

            return fixed;
         } else {
            ArrayList<RoadStructures.Part> out = new ArrayList<>();
            double previous = -100.0;
            ArrayList<Double> missing = new ArrayList<>();
            ArrayList<Double> occupied = new ArrayList<>();

            for (double target = phase.first(12.0, 24.0); target < mesh.length() - 1.0E-6; target += 24.0) {
               if (!RailbedBridges.customAt(mesh, target)) {
                  List<RoadStructures.Part> shared = ground.sharedSupports(mesh, sample(mesh, target), 0.9, 1.8, sample(mesh, target).halfWidth() * 0.62);
                  if (shared != null) {
                     out.addAll(shared);
                     occupied.add(target);
                     previous = target;
                  } else {
                     boolean placed = false;

                     for (double shift : new double[]{0.0, -4.0, 4.0, -8.0, 8.0}) {
                        double d = target + shift;
                        if (!(d < 0.0) && !(d >= mesh.length()) && !(d - previous < 10.0)) {
                           RoadGeometry.Sample s = sample(mesh, d);
                           boolean negative = RoadStreetscape.raised(mesh, s, -1);
                           boolean positive = RoadStreetscape.raised(mesh, s, 1);
                           boolean halfBridge = mesh.settings().structure() == RoadGeometry.Structure.AUTO
                              && negative != positive
                              && !LaneDeck.hasOpenings(mesh);
                           RoadGeometry.Sample bearing = halfBridge
                              ? new RoadGeometry.Sample(
                                 s.at((double)(positive ? 1 : -1) * s.halfWidth() / 2.0, 0.0), s.left(), s.distance(), s.halfWidth() / 2.0
                              )
                              : s;
                           List<RoadStructures.Part> support = mesh.settings().style().connectorRamp()
                              ? RoadSupports.ramp(mesh, d, ground)
                              : (
                                 mesh.settings().style().ramp()
                                    ? RoadSupports.ramp(bearing, mesh.settings().thickness(), ground)
                                    : (
                                       halfBridge
                                          ? RoadSupports.clearStandard(bearing, mesh.settings().thickness(), ground)
                                          : RoadSupports.clearStandard(mesh, d, ground)
                                    )
                              );
                           if (!support.isEmpty() && !support.stream().anyMatch(blocked)) {
                              out.addAll(support);
                              previous = d;
                              occupied.add(d);
                              placed = true;
                              break;
                           }
                        }
                     }

                     if (!placed && mesh.settings().style().ramp()) {
                        missing.add(target);
                     }

                     boolean fallback = RoadStreetscape.raised(mesh, sample(mesh, target), -1) == RoadStreetscape.raised(mesh, sample(mesh, target), 1)
                        && !mesh.settings().style().ramp()
                        && mesh.settings().options().lanePoints().link() == null
                        && (
                           mesh.settings().structure() == RoadGeometry.Structure.AUTO
                              || mesh.settings().structure() == RoadGeometry.Structure.BRIDGE
                                 && mesh.settings().options().infrastructure().bridge() == RoadInfrastructure.Bridge.STANDARD
                        );
                     if (!placed && fallback && target - previous >= 10.0) {
                        RoadGeometry.Sample s = sample(mesh, target);
                        double floor = ground.top(s.center().x(), s.center().z(), s.center().y() - mesh.settings().thickness());
                        if (!Double.isFinite(floor) || !(s.center().y() - mesh.settings().thickness() - floor < 2.0)) {
                           List<RoadGeometry.Sample> bands = RoadSupports.bearing(mesh, target, 1.5);
                           if (!LaneDeck.hasOpenings(mesh)
                              || bands.size() == 1
                                 && !(bands.get(0).center().distance(s.center()) > 1.0E-6)
                                 && !(Math.abs(bands.get(0).halfWidth() - s.halfWidth()) > 1.0E-6)) {
                              List<RoadStructures.Part> portal = RoadSupports.portal(s, mesh.settings().thickness(), 0.9, s.halfWidth() + 1.4, 1.8, ground);
                              if (!portal.isEmpty() && portal.stream().noneMatch(blocked)) {
                                 out.addAll(portal);
                                 previous = target;
                              }
                           }
                        }
                     }
                  }
               }
            }

            for (double targetx : missing) {
               boolean placed = false;

               for (double shiftx : new double[]{0.0, -2.0, 2.0, -4.0, 4.0, -6.0, 6.0, -8.0, 8.0, -10.0, 10.0}) {
                  double d = targetx + shiftx;
                  if (!(d < 1.12) && !(d > mesh.length() - 1.12) && !occupied.stream().anyMatch(x -> Math.abs(x - d) < 10.0)) {
                     RoadGeometry.Sample at = sample(mesh, d);

                     for (double reach = 0.5; reach <= Math.min(3.0, at.halfWidth() - 1.12) + 1.0E-7; reach += 0.5) {
                        for (int side : new int[]{-1, 1}) {
                           List<RoadStructures.Part> support = RoadSupports.ramp(mesh, d, (double)side * reach, ground);
                           if (!support.isEmpty() && !support.stream().anyMatch(blocked)) {
                              out.addAll(support);
                              occupied.add(d);
                              placed = true;
                              break;
                           }
                        }

                        if (placed) {
                           break;
                        }
                     }

                     if (placed) {
                        break;
                     }
                  }
               }

               if (!placed) {
                  RoadGeometry.Sample at = sample(mesh, targetx);
                  double top = at.center().y() - mesh.settings().thickness();
                  double floor = ground.top(at.center().x(), at.center().z(), top);
                  if (!Double.isFinite(floor) || top - floor > 96.0) {
                     ground.missingSupport(targetx, "附近候选缺少可达地基");
                  } else if (top - floor < 2.0) {
                     ground.missingSupport(targetx, "路面贴近地面，无需桥墩");
                  } else if (RoadSupports.bearing(mesh, targetx, 1.12).isEmpty()) {
                     ground.missingSupport(targetx, "可承载桥面不足以容纳完整单柱");
                  } else {
                     ground.missingSupport(targetx, "附近单柱候选受机动车、慢行净空或既有支撑间距阻挡");
                  }
               }
            }

            return List.copyOf(out);
         }
      }
   }

   private static void padEndRails(List<RoadStructures.Part> out, RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      if (mesh.settings().style() == RoadGeometry.Style.UNMARKED
         && mesh.settings().structure() != RoadGeometry.Structure.GROUND
         && mesh.settings().options().outerRail() != RoadProfile.OuterRail.OFF) {
         double trim = Math.min(0.34, mesh.length() / 4.0);

         for (boolean first : new boolean[]{true, false}) {
            RoadGeometry.Sample at = sample(mesh, first ? trim : mesh.length() - trim);
            boolean raised = mesh.settings().structure() == RoadGeometry.Structure.BRIDGE || elevated(mesh, at, ground);
            if (raised) {
               RoadGeometry.V a = at.at(-at.halfWidth() + 0.34, 0.0);
               RoadGeometry.V b = at.at(at.halfWidth() - 0.34, 0.0);
               RoadGeometry.V outside = a.add(b).mul(0.5).add(at.left().left().mul(first ? 0.4 : -0.4));

               for (RoadRailJoin.Span span : ground.railSpans(a, b, outside, 0.34)) {
                  int n = Math.max(1, (int)Math.ceil(span.a().distance(span.b()) / 0.5));
                  ArrayList<RoadStructures.RailSpan> run = new ArrayList<>();

                  for (int i = 0; i < n; i++) {
                     run.add(
                        new RoadStructures.RailSpan(
                           span.a().mul(1.0 - (double)i / (double)n).add(span.b().mul((double)i / (double)n)),
                           span.a().mul(1.0 - (double)(i + 1) / (double)n).add(span.b().mul((double)(i + 1) / (double)n)),
                           true,
                           (double)i * 0.5,
                           (double)(i + 1) * 0.5
                        )
                     );
                  }

                  emitRailRun(out, run, mesh, ground, true, false, 1);
               }
            }
         }
      }
   }

   private static void closureRails(List<RoadStructures.Part> out, RoadGeometry.Mesh mesh, RoadStructures.Ground ground, boolean modern, boolean highway) {
      if (LaneDeck.hasOpenings(mesh)
         && mesh.settings().structure() != RoadGeometry.Structure.GROUND
         && mesh.settings().structure() != RoadGeometry.Structure.TUNNEL
         && mesh.settings().options().outerRail() != RoadProfile.OuterRail.OFF) {
         List<RoadGeometry.Sample> samples = mesh.samples();
         List<LaneDeck.Cap> caps = LaneDeck.caps(mesh);
         ArrayList<RoadStructures.ClosureRailRun> runs = new ArrayList<>();

         for (int i = 1; i < samples.size(); i++) {
            RoadGeometry.Sample a = samples.get(i - 1);
            RoadGeometry.Sample b = samples.get(i);
            double station = (a.distance() + b.distance()) / 2.0;

            for (LaneDeck.Strip strip : LaneDeck.strips(mesh, a, b)) {
               for (int side : new int[]{-1, 1}) {
                  boolean wall = side > 0 ? strip.highWall() : strip.lowWall();
                  if (wall) {
                     RoadGeometry.V first = side > 0 ? strip.al() : strip.ar();
                     RoadGeometry.V last = side > 0 ? strip.bl() : strip.br();
                     if (!(first.distance(a.at((double)side * a.halfWidth(), 0.0)) < 1.0E-6)
                        || !(last.distance(b.at((double)side * b.halfWidth(), 0.0)) < 1.0E-6)) {
                        double widthA = strip.al().distance(strip.ar());
                        double widthB = strip.bl().distance(strip.br());
                        if (!(Math.min(widthA, widthB) < 0.5)) {
                           RoadGeometry.V var50 = first.sub(a.left().mul((double)side * 0.34));
                           RoadGeometry.V var53 = last.sub(b.left().mul((double)side * 0.34));
                           RoadGeometry.V tangent = var53.sub(var50).horizontalUnit();
                           first = closureCorner(var50, first, tangent, caps);
                           last = closureCorner(var53, last, tangent, caps);
                           if (!(first.distance(last) < 1.0E-7)) {
                              RoadGeometry.V mid = first.add(last).mul(0.5);
                              boolean raised = RoadStreetscape.raised(mesh, sample(mesh, station), side)
                                 || elevated(mesh, mid, ground.top(mid.x(), mid.z(), mid.y()), ground);
                              if (raised) {
                                 RoadGeometry.V outside = mid.add(a.left().add(b.left()).horizontalUnit().mul((double)side * 0.4));

                                 for (RoadRailJoin.Span span : ground.capRailSpans(first, last, outside)) {
                                    RoadStructures.ClosureRailRun target = null;

                                    for (RoadStructures.ClosureRailRun candidate : runs) {
                                       if (candidate.side() == side
                                          && !candidate.spans().isEmpty()
                                          && candidate.spans().get(candidate.spans().size() - 1).b().distance(span.a()) < 1.0E-5) {
                                          target = candidate;
                                          break;
                                       }
                                    }

                                    if (target == null) {
                                       target = new RoadStructures.ClosureRailRun(side, new ArrayList<>());
                                       runs.add(target);
                                    }

                                    target.spans().add(new RoadStructures.RailSpan(span.a(), span.b(), true, a.distance(), b.distance()));
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         for (RoadStructures.ClosureRailRun run : runs) {
            emitRailRun(out, seatClosureRun(run.spans(), mesh, ground, run.side(), highway), mesh, ground, modern, highway, run.side());
         }

         for (LaneDeck.Cap cap : caps) {
            RoadGeometry.V axis = cap.b().sub(cap.a()).horizontalUnit();
            RoadGeometry.V mid = cap.a().add(cap.b()).mul(0.5);
            RoadQueries.Projection query = RoadQueries.horizontal(mesh, mid);
            boolean raised = RoadStreetscape.raised(mesh, query.sample(), query.lateral() < 0.0 ? -1 : 1)
               || elevated(mesh, mid, ground.top(mid.x(), mid.z(), mid.y()), ground);
            if (raised && !(cap.a().distance(cap.b()) < 0.7300000000000001)) {
               RoadGeometry.V inward = axis.left().mul(0.34);
               RoadGeometry.Sample at = query.sample();
               boolean outerA = Math.abs(cap.a().sub(at.center()).dot(at.left())) >= at.halfWidth() - 1.0E-5;
               boolean outerB = Math.abs(cap.b().sub(at.center()).dot(at.left())) >= at.halfWidth() - 1.0E-5;
               RoadGeometry.V first = cap.a().add(axis.mul((double)(outerA ? 1 : -1) * 0.34)).add(inward);
               RoadGeometry.V last = cap.b().add(axis.mul((double)(outerB ? -1 : 1) * 0.34)).add(inward);
               RoadGeometry.V outside = mid.sub(axis.left().mul(0.4));

               for (RoadRailJoin.Span span : ground.capRailSpans(first, last, outside)) {
                  emitCapRail(out, ground, span, highway, true);
               }
            }
         }
      }
   }

   private static RoadGeometry.V closureCorner(RoadGeometry.V point, RoadGeometry.V raw, RoadGeometry.V tangent, List<LaneDeck.Cap> caps) {
      for (LaneDeck.Cap cap : caps) {
         if (!(Math.min(raw.distance(cap.a()), raw.distance(cap.b())) > 1.0E-5)) {
            RoadGeometry.V inward = cap.b().sub(cap.a()).horizontalUnit().left();
            double den = tangent.dot(inward);
            if (!(Math.abs(den) < 0.1)) {
               return point.add(tangent.mul((0.34 - point.sub(cap.a()).dot(inward)) / den));
            }
         }
      }

      return point;
   }

   private static List<RoadStructures.RailSpan> seatClosureRun(
      List<RoadStructures.RailSpan> run, RoadGeometry.Mesh mesh, RoadStructures.Ground ground, int side, boolean highway
   ) {
      if (run.size() >= 2 && !highway) {
         int n = run.size();
         double[] offset = new double[n + 1];
         double[] distance = new double[n + 1];
         ArrayList<RoadGeometry.V> points = new ArrayList<>();
         ArrayList<RoadGeometry.V> normals = new ArrayList<>();

         for (int i = 0; i <= n; i++) {
            RoadStructures.RailSpan r = run.get(Math.min(i, n - 1));
            RoadGeometry.V point = i == n ? r.b() : r.a();
            points.add(point);
            RoadGeometry.V normal = sample(mesh, i == n ? r.endDistance() : r.distance()).left().mul((double)(-side));
            normals.add(normal);
            if (i > 0) {
               distance[i] = distance[i - 1] + point.distance(points.get(i - 1));
            }

            if (!closureSeatClear(point, normal, ground)) {
               for (double shift = 0.02; shift <= 0.160001; shift += 0.02) {
                  if (closureSeatClear(point.add(normal.mul(shift)), normal, ground)) {
                     offset[i] = shift;
                     break;
                  }
               }
            }
         }

         if (Arrays.stream(offset).allMatch(v -> v == 0.0)) {
            return run;
         } else {
            for (int i = 1; i <= n; i++) {
               offset[i] = Math.max(offset[i], offset[i - 1] - 0.025 * (distance[i] - distance[i - 1]));
            }

            for (int i = n - 1; i >= 0; i--) {
               offset[i] = Math.max(offset[i], offset[i + 1] - 0.025 * (distance[i + 1] - distance[i]));
            }

            for (int i = 0; i <= n; i++) {
               double shiftx = Math.min(offset[i], 0.025 * Math.min(distance[i], distance[n] - distance[i]));
               RoadGeometry.V moved = points.get(i).add(normals.get(i).mul(shiftx));
               if (shiftx > 0.0 && closureSeatClear(moved, normals.get(i), ground)) {
                  points.set(i, moved);
               }
            }

            ArrayList<RoadStructures.RailSpan> seated = new ArrayList<>();

            for (int ix = 0; ix < n; ix++) {
               RoadStructures.RailSpan rx = run.get(ix);
               seated.add(new RoadStructures.RailSpan(points.get(ix), points.get(ix + 1), rx.raised(), rx.distance(), rx.endDistance()));
            }

            return seated;
         }
      } else {
         return run;
      }
   }

   private static boolean closureSeatClear(RoadGeometry.V p, RoadGeometry.V inward, RoadStructures.Ground ground) {
      RoadGeometry.V step = inward.left().mul(0.005);
      RoadGeometry.V a = p.sub(step);
      RoadGeometry.V b = p.add(step);
      if (ground.railBlocked(new RoadStructures.Part(a, b, 0.18, 0.45, false, RoadStructures.Material.CONCRETE), a, b)) {
         return false;
      } else {
         for (double height : new double[]{0.7, 1.05}) {
            if (ground.railBlocked(
               new RoadStructures.Part(
                  a.add(new RoadGeometry.V(0.0, height, 0.0)), b.add(new RoadGeometry.V(0.0, height, 0.0)), 0.12, 0.12, false, RoadStructures.Material.STEEL
               ),
               a,
               b
            )) {
               return false;
            }
         }

         return true;
      }
   }

   private static void emitCapRail(List<RoadStructures.Part> out, RoadStructures.Ground ground, RoadRailJoin.Span span, boolean highway, boolean raised) {
      int steps = Math.max(1, (int)Math.ceil(span.a().distance(span.b()) / 0.5));

      for (int j = 0; j < steps; j++) {
         RoadGeometry.V a = span.a().add(span.b().sub(span.a()).mul((double)j / (double)steps));
         RoadGeometry.V b = span.a().add(span.b().sub(span.a()).mul((double)(j + 1) / (double)steps));
         ArrayList<RoadStructures.Part> pieces = new ArrayList<>();
         barrier(pieces, a, b, highway, raised, (double)j * 0.5);
         out.addAll(clearBarrier(pieces, ground, a, b));
      }
   }

   private static void emitOuterRailRun(
      List<RoadStructures.Part> out,
      List<RoadStructures.RailSpan> run,
      RoadGeometry.Mesh mesh,
      RoadStructures.Ground ground,
      boolean modern,
      boolean highway,
      int side
   ) {
      if (mesh.settings().style() == RoadGeometry.Style.UNMARKED
         || run.isEmpty()
         || !(run.get(0).distance() > 1.0)
         || !(run.get(run.size() - 1).endDistance() < mesh.length() - 1.0)
         || !(run.stream().mapToDouble(r -> r.a().distance(r.b())).sum() < 0.75)) {
         emitRailRun(out, run, mesh, ground, modern, highway, side);
      }
   }

   private static void emitRailRun(
      List<RoadStructures.Part> out,
      List<RoadStructures.RailSpan> run,
      RoadGeometry.Mesh mesh,
      RoadStructures.Ground ground,
      boolean modern,
      boolean highway,
      int side
   ) {
      if (!run.isEmpty()) {
         double length = run.stream().mapToDouble(rx -> rx.a.distance(rx.b)).sum();
         if (!modern
            || mesh.settings().style() == RoadGeometry.Style.UNMARKED
            || LaneDeck.hasOpenings(mesh)
            || mesh.settings().options().lanePoints().link() != null
            || !mesh.settings().options().lanePoints().openings().isEmpty()
            || !(mesh.length() > 8.0)
            || !(length < 3.0)
            || mesh.settings().options().outerRail() != RoadProfile.OuterRail.AUTO) {
            if (modern && mesh.settings().options().outerRail().sound(side)) {
               ArrayList<RoadStructures.RailSpan> ordinary = new ArrayList<>();
               int i = 0;

               while (i < run.size()) {
                  RoadStructures.RailSpan first = run.get(i);
                  if (!first.raised() && mesh.settings().structure() != RoadGeometry.Structure.BRIDGE) {
                     ordinary.add(first);
                     i++;
                  } else {
                     int end = i;
                     double panelLength = first.a().distance(first.b());

                     while (
                        end + 1 < run.size()
                           && panelLength < 1.999
                           && run.get(end).b().distance(run.get(end + 1).a()) < 1.0E-6
                           && (run.get(end + 1).raised() || mesh.settings().structure() == RoadGeometry.Structure.BRIDGE)
                     ) {
                        panelLength += run.get(++end).a().distance(run.get(end).b());
                     }

                     double tail = 0.0;
                     int last = end;

                     while (
                        last + 1 < run.size()
                           && tail <= 0.25
                           && run.get(last).b().distance(run.get(last + 1).a()) < 1.0E-6
                           && (run.get(last + 1).raised() || mesh.settings().structure() == RoadGeometry.Structure.BRIDGE)
                     ) {
                        tail += run.get(++last).a().distance(run.get(last).b());
                     }

                     if (last == run.size() - 1 && tail <= 0.25) {
                        end = last;
                     }

                     RoadStructures.Part part = new RoadStructures.Part(
                           first.a(), run.get(end).b(), 0.7, 2.5043584, false, RoadStructures.Material.NOISE_BARRIER
                        )
                        .frames(
                           sample(mesh, first.distance()).left().mul((double)side * 0.35),
                           sample(mesh, run.get(end).endDistance()).left().mul((double)side * 0.35)
                        );
                     List<RoadStructures.Part> assembly = RoadNoiseModel.assembly(part);
                     if (assembly.stream().noneMatch(ground::blocked)) {
                        out.addAll(assembly);
                     } else {
                        for (int j = i; j <= end; j++) {
                           ordinary.add(run.get(j));
                        }
                     }

                     i = end + 1;
                  }
               }

               run = ordinary;
            }

            if (mesh.settings().style() == RoadGeometry.Style.RAILBED && mesh.settings().options().railbed().fittings().fence() == RailbedOptions.Fence.MESH) {
               ArrayList<RoadStructures.RailSpan> solid = new ArrayList<>();
               int i = 0;

               while (i < run.size()) {
                  RoadStructures.RailSpan first = run.get(i);
                  if (fenceRaised(mesh, run, i)) {
                     solid.add(first);
                     i++;
                  } else {
                     int end = i;
                     double lengthPanel = first.a().distance(first.b());

                     while (
                        end + 1 < run.size()
                           && !fenceRaised(mesh, run, end + 1)
                           && lengthPanel < 2.49
                           && run.get(end).b().distance(run.get(end + 1).a()) < 1.0E-6
                           && first.b().sub(first.a()).horizontalUnit().dot(run.get(end + 1).b().sub(run.get(end + 1).a()).horizontalUnit()) > 0.998
                     ) {
                        lengthPanel += run.get(++end).a().distance(run.get(end).b());
                     }

                     RoadStructures.Part panel = new RoadStructures.Part(
                        first.a(), run.get(end).b(), 0.1, 1.65, false, RoadStructures.Material.SIGN_GREEN, null, null, "sr:railway_fence"
                     );
                     if (!ground.blocked(panel)) {
                        out.add(panel);
                     }

                     i = end + 1;
                  }
               }

               run = solid;
            }

            for (RoadStructures.RailSpan r : run) {
               if (modern) {
                  ArrayList<RoadStructures.Part> pieces = new ArrayList<>();
                  barrier(pieces, r.a, r.b, highway, r.raised, r.distance);
                  RoadGeometry.V direction = r.b.sub(r.a);
                  RoadGeometry.V first = ground.railJoint(r.a, direction, highway, r.raised);
                  RoadGeometry.V last = ground.railJoint(r.b, direction, highway, r.raised);
                  ArrayList<RoadStructures.Part> assembly = new ArrayList<>();

                  for (RoadStructures.Part piece : pieces) {
                     if (piece.material() != RoadStructures.Material.DARK_STEEL || ground.railPost(r.a, highway, r.raised)) {
                        if (piece.a().sub(r.a).horizontalLength() < 1.0E-6 && piece.b().sub(r.b).horizontalLength() < 1.0E-6) {
                           piece = piece.frames(
                              first == null ? piece.frameA() : first.mul(piece.width() / 2.0), last == null ? piece.frameB() : last.mul(piece.width() / 2.0)
                           );
                        }

                        assembly.add(piece);
                     }
                  }

                  boolean check = ground.unionRails()
                     || LaneDeck.hasOpenings(mesh)
                     || mesh.settings().options().lanePoints().link() != null
                     || !mesh.settings().options().lanePoints().openings().isEmpty();

                  for (RoadStructures.Part piecex : check ? clearBarrier(assembly, ground, r.a, r.b) : assembly) {
                     add(out, piecex);
                  }
               } else {
                  RoadStructures.Part part = new RoadStructures.Part(r.a, r.b, 0.24, 1.05, false);
                  if (!ground.blocked(part)) {
                     add(out, part);
                  }
               }
            }
         }
      }
   }

   private static boolean fenceRaised(RoadGeometry.Mesh mesh, List<RoadStructures.RailSpan> run, int index) {
      if (mesh.settings().structure() == RoadGeometry.Structure.BRIDGE) {
         return true;
      } else if (!run.get(index).raised()) {
         return false;
      } else {
         int from = index;
         int to = index;
         double length = run.get(index).a().distance(run.get(index).b());

         while (from > 0 && run.get(from - 1).raised() && length <= 0.51) {
            length += run.get(--from).a().distance(run.get(from).b());
         }

         while (to + 1 < run.size() && run.get(to + 1).raised() && length <= 0.51) {
            length += run.get(++to).a().distance(run.get(to).b());
         }

         return !(length <= 0.51) || from != 0 && to != run.size() - 1 || from <= 0 && to + 1 >= run.size();
      }
   }

   private static List<RoadStructures.Part> clearBarrier(List<RoadStructures.Part> pieces, RoadStructures.Ground ground, RoadGeometry.V a, RoadGeometry.V b) {
      return clearBarrier(pieces, ground, a, b, 0);
   }

   private static List<RoadStructures.Part> clearBarrier(
      List<RoadStructures.Part> pieces, RoadStructures.Ground ground, RoadGeometry.V a, RoadGeometry.V b, int depth
   ) {
      List<RoadStructures.Part> fitted = fitBarrier(pieces, ground, a, b);
      if (fitted.isEmpty() && !pieces.isEmpty()) {
         double length = a.distance(b);
         if (depth < 8 && !(length < 0.02)) {
            boolean usable = false;

            for (double t : new double[]{0.0, 0.5, 1.0}) {
               double from = Math.max(0.0, t - 0.001 / length);
               double to = Math.min(1.0, t + 0.001 / length);
               RoadGeometry.V first = a.add(b.sub(a).mul(from));
               RoadGeometry.V last = a.add(b.sub(a).mul(to));
               if (!fitBarrier(barrierSlice(pieces, a, b, from, to), ground, first, last).isEmpty()) {
                  usable = true;
                  break;
               }
            }

            if (!usable) {
               return List.of();
            } else {
               RoadGeometry.V mid = a.add(b).mul(0.5);
               ArrayList<RoadStructures.Part> out = new ArrayList<>();
               out.addAll(clearBarrier(barrierSlice(pieces, a, b, 0.0, 0.5), ground, a, mid, depth + 1));
               out.addAll(clearBarrier(barrierSlice(pieces, a, b, 0.5, 1.0), ground, mid, b, depth + 1));
               return out;
            }
         } else {
            return List.of();
         }
      } else {
         return fitted;
      }
   }

   private static List<RoadStructures.Part> barrierSlice(List<RoadStructures.Part> pieces, RoadGeometry.V a, RoadGeometry.V b, double from, double to) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      RoadGeometry.V axis = b.sub(a);
      double length2 = axis.dot(axis);

      for (RoadStructures.Part p : pieces) {
         if (p.material() == RoadStructures.Material.DARK_STEEL) {
            double d = p.a().sub(a).dot(axis) / length2;
            if (d >= from - 1.0E-8 && d < to - 1.0E-8) {
               out.add(p);
            }
         } else {
            List<RoadGeometry.V> base = p.base();
            RoadGeometry.V fa = base.get(0).sub(p.a());
            RoadGeometry.V fb = base.get(3).sub(p.b());
            out.add(
               new RoadStructures.Part(
                  p.a().add(p.b().sub(p.a()).mul(from)),
                  p.a().add(p.b().sub(p.a()).mul(to)),
                  p.width(),
                  p.height(),
                  p.pier(),
                  p.material(),
                  fa.mul(1.0 - from).add(fb.mul(from)),
                  fa.mul(1.0 - to).add(fb.mul(to)),
                  p.model()
               )
            );
         }
      }

      return out;
   }

   private static List<RoadStructures.Part> fitBarrier(List<RoadStructures.Part> pieces, RoadStructures.Ground ground, RoadGeometry.V a, RoadGeometry.V b) {
      if (pieces.stream().noneMatch(px -> ground.railBlocked(px, a, b))) {
         return pieces;
      } else {
         ArrayList<RoadStructures.Part> fitted = new ArrayList<>();

         for (RoadStructures.Part p : pieces) {
            if (!ground.railBlocked(p, a, b)) {
               fitted.add(p);
            } else if (p.material() == RoadStructures.Material.DARK_STEEL) {
               RoadGeometry.V delta = b.sub(a).horizontalUnit().mul(Math.max(0.0, b.sub(a).horizontalLength() - 0.16));
               RoadStructures.Part moved = new RoadStructures.Part(
                  p.a().add(delta), p.b().add(delta), p.width(), p.height(), p.pier(), p.material(), p.frameA(), p.frameB(), p.model()
               );
               if (!ground.railBlocked(moved, a, b)) {
                  fitted.add(moved);
               }
            } else {
               boolean urban = p.material() == RoadStructures.Material.CONCRETE && Math.abs(p.width() - 0.42) < 1.0E-7 && Math.abs(p.height() - 0.45) < 1.0E-7;
               boolean highway = p.material() == RoadStructures.Material.CONCRETE && Math.abs(p.width() - 0.62) < 1.0E-7 && Math.abs(p.height() - 0.8) < 1.0E-7;
               if (!urban && !highway) {
                  return List.of();
               }

               RoadStructures.Part clear = null;
               double minimum = urban ? 0.18 : 0.4;
               double width = p.width() - 0.02;

               while (true) {
                  if (width >= minimum - 1.0E-7) {
                     double scale = width / p.width();
                     RoadStructures.Part candidate = new RoadStructures.Part(
                        p.a(),
                        p.b(),
                        width,
                        p.height(),
                        false,
                        p.material(),
                        p.frameA() == null ? null : p.frameA().mul(scale),
                        p.frameB() == null ? null : p.frameB().mul(scale),
                        p.model()
                     );
                     if (ground.railBlocked(candidate, a, b)) {
                        width -= 0.02;
                        continue;
                     }

                     clear = candidate;
                  }

                  if (clear == null) {
                     return List.of();
                  }

                  fitted.add(clear);
                  break;
               }
            }
         }

         return fitted;
      }
   }

   public static boolean elevated(RoadGeometry.Mesh mesh, RoadGeometry.V point, double top, RoadStructures.Ground ground) {
      return elevated(mesh, point, ground);
   }

   private static boolean elevated(RoadGeometry.Mesh mesh, RoadGeometry.V point, RoadStructures.Ground ground) {
      double threshold = 0.125;
      int unsupported = 0;

      for (int x = -1; x <= 1; x++) {
         for (int z = -1; z <= 1; z++) {
            if (x != 0 || z != 0) {
               double near = ground.top(point.x() + (double)x * 0.65, point.z() + (double)z * 0.65, point.y());
               if (Double.isNaN(near) || point.y() - near > threshold) {
                  unsupported++;
               }
            }
         }
      }

      return unsupported >= 5;
   }

   public static boolean elevated(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, RoadStructures.Ground ground) {
      return elevated(mesh, at, ground, -1) || elevated(mesh, at, ground, 1);
   }

   public static boolean elevated(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, RoadStructures.Ground ground, int side) {
      if (mesh.settings().structure() == RoadGeometry.Structure.BRIDGE) {
         return true;
      } else if (mesh.settings().structure() != RoadGeometry.Structure.GROUND && mesh.settings().structure() != RoadGeometry.Structure.TUNNEL) {
         double threshold = 0.125;
         int raisedRows = 0;

         for (double step : new double[]{-0.8, 0.0, 0.8}) {
            RoadGeometry.Sample sample = sample(mesh, Math.max(0.0, Math.min(mesh.length(), at.distance() + step)));
            boolean raised = false;
            int missing = 0;

            for (double fraction : new double[]{0.35, 0.65, 0.9}) {
               RoadGeometry.V p = sample.at((double)side * sample.halfWidth() * fraction, 0.0);
               double top = ground.top(p.x(), p.z(), p.y());
               if (!Double.isFinite(top) || p.y() - top > threshold) {
                  missing++;
               }
            }

            if (missing >= 2) {
               raised = true;
            }

            if (raised) {
               raisedRows++;
            }
         }

         return raisedRows >= 2;
      } else {
         return false;
      }
   }

   public static boolean fitsMedian(RoadStructures.Part pier, RoadGeometry.Mesh lower, List<RoadGeometry.Mesh> neighbors) {
      if (pier.pier() && RoadProfile.modern(lower.settings().style()) && RoadProfile.catalog(lower.settings().style()).twoWay()) {
         double margin = pier.height() <= 0.4 ? 0.04 : 0.1;

         for (RoadGeometry.V p : pier.base()) {
            RoadQueries.Projection q = RoadQueries.horizontal(lower, p);
            RoadGeometry.Sample s = q.sample();
            RoadProfile.Layout layout = RoadProfile.layout(lower, s);
            double usable = layout.median();
            if (RoadTransitions.green(lower, s) * (1.0 - RoadStreetscape.medianConversionBlend(lower, s)) < 0.12) {
               boolean raised = RoadStreetscape.raised(lower, s);
               double band = RoadProfile.highway(lower.settings().style()) ? (raised ? 0.62 : 0.3) : (raised ? 0.42 : 0.1);
               double separation = Math.min(RoadTransitions.railSeparation(lower, s, band), Math.max(0.0, layout.median() - band));
               usable = Math.max(0.0, separation - band);
            }

            if (s.distance() < 2.0 || s.distance() > lower.length() - 2.0 || Math.abs(q.lateral() - layout.medianCenter()) + margin > usable / 2.0) {
               return false;
            }

            for (RoadGeometry.Mesh road : neighbors) {
               if (road != lower
                  && road.settings().style() == RoadGeometry.Style.UNMARKED
                  && RoadQueries.contains(road, s.at(RoadProfile.layout(lower, s).medianCenter(), 0.0), 2.0, 0.2)) {
                  return false;
               }
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private static void furniture(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, List<RoadStructures.Part> out, RoadFurniture.Phase phase) {
      RoadProfile.Catalog profile = RoadProfile.catalog(mesh.settings().style());
      boolean highway = RoadProfile.highway(mesh.settings().style());

      for (double d = 0.0; d < mesh.length() - 1.0E-6; d += 2.0) {
         RoadGeometry.Sample a = sample(mesh, d);
         RoadGeometry.Sample b = sample(mesh, Math.min(mesh.length(), d + 2.0));
         RoadProfile.Layout la = RoadProfile.layout(mesh, a);
         RoadProfile.Layout lb = RoadProfile.layout(mesh, b);
         if (profile.twoWay()) {
            for (double[] span : medianSpans(mesh, ground, d, Math.min(mesh.length(), d + 2.0))) {
               median(out, mesh, ground, sample(mesh, span[0]), sample(mesh, span[1]), highway, span[0]);
            }
         }

         for (int side : la.outsideSides()) {
            cycleBarrier(mesh, ground, out, a, b, side);
            if (la.curbWidth() > 0.0 || lb.curbWidth() > 0.0) {
               for (double[] span : openSpans(d, Math.min(mesh.length(), d + 2.0), station -> {
                  RoadGeometry.Sample at = sample(mesh, station);
                  return !bridgeAt(mesh, at, ground, side) && !ground.joined(at.at((double)side * (at.halfWidth() + 0.02), 0.0));
               })) {
                  RoadGeometry.Sample first = sample(mesh, span[0]);
                  RoadGeometry.Sample last = sample(mesh, span[1]);
                  RoadProfile.Layout fl = RoadProfile.layout(mesh, first);
                  RoadProfile.Layout ll = RoadProfile.layout(mesh, last);
                  double ca = RoadProfile.curbExtent(fl, first, side);
                  double cb = RoadProfile.curbExtent(ll, last, side);
                  if (!(Math.max(ca, cb) < 0.001)) {
                     add(
                        out,
                        new RoadStructures.Part(
                              first.at((double)side * (first.halfWidth() - ca / 2.0), 0.0),
                              last.at((double)side * (last.halfWidth() - cb / 2.0), 0.0),
                              Math.max(ca, cb),
                              0.2,
                              false,
                              RoadStructures.Material.CONCRETE
                           )
                           .frames(first.left().mul(ca / 2.0), last.left().mul(cb / 2.0))
                     );
                  }
               }
            }
         }
      }

      cyclePlanting(mesh, ground, out);
      closePlantingEnds(out, mesh);
      List<RoadStructures.Part> amenities = RoadStreetscape.plan(mesh, ground, phase);
      List<RoadStructures.Part> lampBases = amenities.stream()
         .filter(p -> p.material() == RoadStructures.Material.CONCRETE && p.width() == 0.38 && p.height() == 0.16)
         .toList();
      trimHedges(out, lampBases);
      out.addAll(amenities);
   }

   public static void cycleFurniture(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, List<RoadStructures.Part> out) {
      for (double d = 0.0; d < mesh.length() - 1.0E-6; d += 2.0) {
         RoadGeometry.Sample a = sample(mesh, d);
         RoadGeometry.Sample b = sample(mesh, Math.min(mesh.length(), d + 2.0));

         for (int side : RoadProfile.layout(mesh, a).outsideSides()) {
            cycleBarrier(mesh, ground, out, a, b, side);
         }
      }
   }

   private static void cycleBarrier(
      RoadGeometry.Mesh mesh, RoadStructures.Ground ground, List<RoadStructures.Part> out, RoadGeometry.Sample a, RoadGeometry.Sample b, int side
   ) {
      RoadProfile.Layout la = RoadProfile.layout(mesh, a);
      RoadProfile.Layout lb = RoadProfile.layout(mesh, b);
      double mid = (a.distance() + b.distance()) / 2.0;
      double separation = RoadStreetscape.separatorWidth(mesh.settings().options());
      boolean raised = mesh.settings().structure() != RoadGeometry.Structure.TUNNEL && bridgeAt(mesh, sample(mesh, mid), ground, side);
      if (!RoadsideBypass.crossing(mesh, mid, side)
         && la.cycleWidth() > 0.0
         && lb.cycleWidth() > 0.0
         && (mesh.settings().options().cycleRail() || raised && separation > 0.0)) {
         RoadGeometry.V aa = a.at(la.outer(side) + (double)side * separation / 2.0, 0.0);
         RoadGeometry.V bb = b.at(lb.outer(side) + (double)side * separation / 2.0, 0.0);
         if (!ground.joined(aa.add(bb).mul(0.5))) {
            barrier(out, aa, bb, false, false, a.distance());
         }
      }
   }

   private static boolean plantingContinues(RoadGeometry.Mesh mesh, boolean start, boolean cycle, int side) {
      RoadTransitions.Ends ends = mesh.settings().options().ends();
      RoadTransitions.Section section = start ? ends.start() : ends.end();
      if (section == null) {
         return (start ? ends.trimmedStart() : ends.trimmedEnd()) > 0.0;
      } else {
         RoadProfile.Layout layout = section.layout(mesh.settings().options().leftTraffic());
         return cycle
            ? layout.cycleWidth() > 0.0
               && section.streetscape() != null
               && section.streetscape().separator() == RoadStreetscape.Separator.GREEN
               && !section.streetscape().parking()
               && Arrays.stream(layout.outsideSides()).anyMatch(v -> v == side)
            : layout.catalog().median() == RoadProfile.Median.GREEN && layout.median() > 0.5;
      }
   }

   private static void cyclePlanting(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, List<RoadStructures.Part> out) {
      double separation = RoadStreetscape.separatorWidth(mesh.settings().options());
      if (!(separation <= 0.0)) {
         for (int side : new int[]{-1, 1}) {
            ArrayList<double[]> runs = new ArrayList<>();

            for (double d = 0.0; d < mesh.length() - 1.0E-7; d += 0.5) {
               double end = Math.min(mesh.length(), d + 0.5);
               double mid = (d + end) / 2.0;
               RoadGeometry.Sample at = sample(mesh, mid);
               RoadProfile.Layout l = RoadProfile.layout(mesh, at);
               RoadGeometry.V center = at.at(l.outer(side) + (double)side * separation / 2.0, 0.0);
               if (!(l.cycleWidth() <= 0.0)
                  && !Arrays.stream(l.outsideSides()).noneMatch(v -> v == side)
                  && !RoadsideBypass.crossing(mesh, mid, side)
                  && !bridgeAt(mesh, at, ground, side)
                  && !RoadStreetscape.cut(mesh, at, side)
                  && !ground.joined(center)
                  && !ground.furnitureClear(center)) {
                  if (!runs.isEmpty() && Math.abs(runs.get(runs.size() - 1)[1] - d) < 1.0E-7) {
                     runs.get(runs.size() - 1)[1] = end;
                  } else {
                     runs.add(new double[]{d, end});
                  }
               }
            }

            for (double[] run : runs) {
               double fade = Math.min(6.0, (run[1] - run[0]) / 2.0);
               double dx = run[0];

               while (dx < run[1] - 1.0E-7) {
                  double step = dx - run[0] >= fade && run[1] - dx > fade + 2.0 ? 2.0 : 0.5;
                  double end = Math.min(run[1], dx + step);
                  RoadGeometry.Sample a = sample(mesh, dx);
                  RoadGeometry.Sample b = sample(mesh, end);
                  boolean openStart = run[0] < 1.0E-7 && plantingContinues(mesh, true, true, side);
                  boolean openEnd = Math.abs(run[1] - mesh.length()) < 1.0E-7 && plantingContinues(mesh, false, true, side);
                  double fa = RoadGeometry.Settings.smooth(Math.min(1.0, Math.min(openStart ? fade : dx - run[0], openEnd ? fade : run[1] - dx) / fade));
                  double fb = RoadGeometry.Settings.smooth(Math.min(1.0, Math.min(openStart ? fade : end - run[0], openEnd ? fade : run[1] - end) / fade));
                  cyclePlanter(out, a, b, side, RoadProfile.layout(mesh, a).outer(side), RoadProfile.layout(mesh, b).outer(side), separation, fa, fb);
                  dx = end;
               }
            }
         }
      }
   }

   public static void cyclePlanter(
      List<RoadStructures.Part> out,
      RoadGeometry.Sample a,
      RoadGeometry.Sample b,
      int side,
      double innerA,
      double innerB,
      double separation,
      double fa,
      double fb
   ) {
      double wa0 = 0.6 + (separation - 0.6) * fa;
      double wb0 = 0.6 + (separation - 0.6) * fb;
      double ca = innerA + (double)side * wa0 / 2.0;
      double cb = innerB + (double)side * wb0 / 2.0;
      double[] insets = new double[]{0.0, 0.24, 0.3};
      double[] ys = new double[]{0.0, 0.3, 0.45};
      double[] hs = new double[]{0.3, 0.15, 0.4};
      RoadStructures.Material[] mats = new RoadStructures.Material[]{
         RoadStructures.Material.CONCRETE, RoadStructures.Material.SOIL, RoadStructures.Material.GREEN
      };

      for (int k = 0; k < 3; k++) {
         double wa = wa0 - insets[k];
         double wb = wb0 - insets[k];
         out.add(
            new RoadStructures.Part(
               a.at(ca, -ys[k]), b.at(cb, -ys[k]), Math.max(wa, wb), hs[k], false, mats[k], a.left().mul(wa / 2.0), b.left().mul(wb / 2.0), "sr:cycle_planter"
            )
         );
      }
   }

   public static List<RoadStructures.Part> repairJoinedPlanting(RoadGeometry.Mesh mesh, List<RoadStructures.Part> saved) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>(saved);

      for (RoadStructures.Part cap : saved.stream()
         .filter(p -> p.model().equals("sr:planting_end") && (joinedPlantingEnd(mesh, p.a()) || joinedPlantingEnd(mesh, p.b())))
         .toList()) {
         boolean first = joinedPlantingEnd(mesh, cap.a());
         RoadGeometry.V outer = first ? cap.a() : cap.b();
         RoadGeometry.V inner = first ? cap.b() : cap.a();
         RoadGeometry.V frame = first ? cap.frameA() : cap.frameB();
         frame = frame.sub(frame.horizontalUnit().mul(0.2));

         for (int i = 0; i < out.size(); i++) {
            RoadStructures.Part soil = out.get(i);
            if (soil.model().equals("sr:planting_soil")) {
               if (soil.a().distance(inner) < 1.0E-5) {
                  out.set(
                     i,
                     new RoadStructures.Part(
                        outer,
                        soil.b(),
                        Math.max(soil.width(), frame.horizontalLength() * 2.0),
                        soil.height(),
                        false,
                        soil.material(),
                        frame,
                        soil.frameB(),
                        soil.model()
                     )
                  );
               } else if (soil.b().distance(inner) < 1.0E-5) {
                  out.set(
                     i,
                     new RoadStructures.Part(
                        soil.a(),
                        outer,
                        Math.max(soil.width(), frame.horizontalLength() * 2.0),
                        soil.height(),
                        false,
                        soil.material(),
                        soil.frameA(),
                        frame,
                        soil.model()
                     )
                  );
               }
            }
         }

         out.remove(cap);
      }

      double separation = RoadStreetscape.separatorWidth(mesh.settings().options());
      if (separation <= 0.0) {
         return List.copyOf(out);
      } else {
         for (int side : new int[]{-1, 1}) {
            boolean start = plantingContinues(mesh, true, true, side);
            boolean end = plantingContinues(mesh, false, true, side);
            if (start || end) {
               ArrayList<double[]> runs = new ArrayList<>();

               for (RoadStructures.Part p : saved.stream()
                  .filter(px -> px.model().equals("sr:cycle_planter") && px.material() == RoadStructures.Material.CONCRETE)
                  .filter(px -> Math.signum(RoadQueries.horizontal(mesh, px.a().add(px.b()).mul(0.5)).lateral()) == (double)side)
                  .sorted(Comparator.comparingDouble(px -> RoadQueries.horizontal(mesh, px.a()).sample().distance()))
                  .toList()) {
                  double a = RoadQueries.horizontal(mesh, p.a()).sample().distance();
                  double b = RoadQueries.horizontal(mesh, p.b()).sample().distance();
                  if (!runs.isEmpty() && Math.abs(runs.get(runs.size() - 1)[1] - a) < 1.0E-5) {
                     runs.get(runs.size() - 1)[1] = b;
                  } else {
                     runs.add(new double[]{a, b});
                  }
               }

               for (double[] run : runs) {
                  boolean openStart = start && run[0] < 1.0E-5;
                  boolean openEnd = end && Math.abs(run[1] - mesh.length()) < 1.0E-5;
                  if (openStart || openEnd) {
                     out.removeIf(
                        px -> px.model().equals("sr:cycle_planter")
                              && Math.signum(RoadQueries.horizontal(mesh, px.a().add(px.b()).mul(0.5)).lateral()) == (double)side
                              && RoadQueries.horizontal(mesh, px.a()).sample().distance() >= run[0] - 1.0E-5
                              && RoadQueries.horizontal(mesh, px.b()).sample().distance() <= run[1] + 1.0E-5
                     );
                     double fade = Math.min(6.0, (run[1] - run[0]) / 2.0);
                     if (!(fade < 1.0E-6)) {
                        double d = run[0];

                        while (d < run[1] - 1.0E-7) {
                           double step = d - run[0] >= fade && run[1] - d > fade + 2.0 ? 2.0 : 0.5;
                           double stop = Math.min(run[1], d + step);
                           RoadGeometry.Sample a = sample(mesh, d);
                           RoadGeometry.Sample b = sample(mesh, stop);
                           double fa = RoadGeometry.Settings.smooth(Math.min(1.0, Math.min(openStart ? fade : d - run[0], openEnd ? fade : run[1] - d) / fade));
                           double fb = RoadGeometry.Settings.smooth(
                              Math.min(1.0, Math.min(openStart ? fade : stop - run[0], openEnd ? fade : run[1] - stop) / fade)
                           );
                           cyclePlanter(out, a, b, side, RoadProfile.layout(mesh, a).outer(side), RoadProfile.layout(mesh, b).outer(side), separation, fa, fb);
                           d = stop;
                        }
                     }
                  }
               }
            }
         }

         return List.copyOf(out);
      }
   }

   public static void trimHedges(List<RoadStructures.Part> out, List<RoadStructures.Part> bases) {
      trimPlanting(out, bases, false);
   }

   public static void trimPlanting(List<RoadStructures.Part> out, List<RoadStructures.Part> bases, boolean soil) {
      ArrayList<RoadStructures.Part> result = new ArrayList<>();

      for (RoadStructures.Part p : out) {
         if (p.material() == RoadStructures.Material.GREEN || soil && p.material() == RoadStructures.Material.SOIL) {
            subtractFootprint(result, p, bases);
         } else {
            result.add(p);
         }
      }

      out.clear();
      out.addAll(result);
   }

   static void subtractFootprint(List<RoadStructures.Part> result, RoadStructures.Part p, List<RoadStructures.Part> bases) {
      List<List<RoadGeometry.V>> polygons = List.of(p.base());
      boolean changed = false;

      for (RoadStructures.Part b : bases) {
         double before = polygons.stream().mapToDouble(RoadSurface::area).sum();
         ArrayList<List<RoadGeometry.V>> next = new ArrayList<>();

         for (List<RoadGeometry.V> poly : polygons) {
            next.addAll(RoadSurface.subtract(poly, b.base()));
         }

         if (before - next.stream().mapToDouble(RoadSurface::area).sum() > 1.0E-8) {
            changed = true;
            polygons = next;
         }
      }

      if (!changed) {
         result.add(p);
      } else {
         for (List<RoadGeometry.V> poly : polygons) {
            for (int i = 1; i + 1 < poly.size(); i++) {
               RoadGeometry.V a = poly.get(0);
               RoadGeometry.V b = poly.get(i);
               RoadGeometry.V c = poly.get(i + 1);
               if (!(RoadSurface.area(List.of(a, b, c)) < 1.0E-8)) {
                  result.add(
                     new RoadStructures.Part(
                           a.add(b).mul(0.5), c, Math.max(0.001, Math.min(8.0, a.distance(b))), p.height(), false, p.material(), null, null, p.model()
                        )
                        .frames(a.sub(b).mul(0.5), new RoadGeometry.V(0.0, 0.0, 0.0))
                  );
               }
            }
         }
      }
   }

   private static boolean bridgeAt(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, RoadStructures.Ground ground, int side) {
      return RoadStreetscape.raised(mesh, at, side)
         || mesh.settings().structure() != RoadGeometry.Structure.GROUND
            && !RoadStreetscape.underground(mesh, at.distance())
            && !RoadAutoTunnels.below(mesh, at, ground, side)
            && elevated(mesh, at, ground, side);
   }

   private static boolean bridgeAt(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, RoadStructures.Ground ground) {
      return RoadStreetscape.raised(mesh, at) || mesh.settings().structure() != RoadGeometry.Structure.GROUND && elevated(mesh, at, ground);
   }

   private static boolean medianOpen(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, double distance) {
      RoadGeometry.Sample at = sample(mesh, distance);

      for (ElevatedAccess.Reservation access : mesh.settings().options().lanePoints().access()) {
         for (ElevatedAccess.Gap gap : access.gaps()) {
            if (gap.at(distance)) {
               return false;
            }
         }
      }

      RoadGeometry.V center = at.at(RoadProfile.layout(mesh, at).medianCenter(), 0.0);
      return !ground.joined(center) && !ground.furnitureClear(center);
   }

   private static List<double[]> medianSpans(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, double from, double to) {
      return openSpans(from, to, d -> medianOpen(mesh, ground, d));
   }

   private static List<double[]> openSpans(double from, double to, DoublePredicate visible) {
      List<double[]> spans = new ArrayList<>();
      double previous = from;
      double begin = from;
      boolean open = visible.test(from);
      int steps = (int)Math.ceil((to - from) / 0.5);

      for (int i = 1; i <= steps; i++) {
         double next = from + (to - from) * (double)i / (double)steps;
         boolean after = visible.test(next);
         if (after != open) {
            double lo = previous;
            double hi = next;

            for (int j = 0; j < 16; j++) {
               double mid = (lo + hi) / 2.0;
               if (visible.test(mid) == open) {
                  lo = mid;
               } else {
                  hi = mid;
               }
            }

            double boundary = (lo + hi) / 2.0;
            if (open && boundary - begin > 1.0E-5) {
               spans.add(new double[]{begin, boundary});
            } else {
               begin = boundary;
            }

            open = after;
         }

         previous = next;
      }

      if (open && to - begin > 1.0E-5) {
         spans.add(new double[]{begin, to});
      }

      return spans;
   }

   public static void medianFurniture(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, List<RoadStructures.Part> out) {
      RoadProfile.Catalog catalog = RoadProfile.catalog(mesh.settings().style());
      if (catalog.twoWay()) {
         for (double d = 0.0; d < mesh.length() - 1.0E-6; d += 2.0) {
            for (double[] span : medianSpans(mesh, ground, d, Math.min(mesh.length(), d + 2.0))) {
               median(out, mesh, ground, sample(mesh, span[0]), sample(mesh, span[1]), catalog.type() == RoadProfile.Type.HIGHWAY, span[0]);
            }
         }

         closePlantingEnds(out, mesh);
      }
   }

   private static void median(
      List<RoadStructures.Part> out,
      RoadGeometry.Mesh mesh,
      RoadStructures.Ground ground,
      RoadGeometry.Sample a,
      RoadGeometry.Sample b,
      boolean highway,
      double d
   ) {
      RoadProfile.Layout la = RoadProfile.layout(mesh, a);
      RoadProfile.Layout lb = RoadProfile.layout(mesh, b);
      boolean raised = mesh.settings().structure() != RoadGeometry.Structure.TUNNEL
         && bridgeAt(mesh, sample(mesh, (a.distance() + b.distance()) / 2.0), ground);
      double greenA = RoadTransitions.green(mesh, a) * (1.0 - RoadStreetscape.medianConversionBlend(mesh, a));
      double greenB = RoadTransitions.green(mesh, b) * (1.0 - RoadStreetscape.medianConversionBlend(mesh, b));
      double green = Math.min(greenA, greenB);
      double terrainBlend = mesh.settings().structure() == RoadGeometry.Structure.TUNNEL ? 0.0 : 1.0;
      green *= terrainBlend;
      double median = Math.min(la.median(), lb.median());
      if (median >= 0.35 && (green < 0.12 || median <= 0.5)) {
         medianBarrier(out, mesh, a, b, highway, raised, d);
      }

      if (green >= 0.12 && median > 0.5) {
         out.addAll(planting(a.at(la.medianCenter(), 0.0), b.at(lb.medianCenter(), 0.0), a.left(), b.left(), la.median(), lb.median(), greenA, greenB, 0.0));
      }
   }

   public static void medianBarrier(
      List<RoadStructures.Part> out, RoadGeometry.Mesh mesh, RoadGeometry.Sample a, RoadGeometry.Sample b, boolean highway, boolean raised, double d
   ) {
      RoadProfile.Layout la = RoadProfile.layout(mesh, a);
      RoadProfile.Layout lb = RoadProfile.layout(mesh, b);
      double band = highway ? (raised ? 0.62 : 0.3) : (raised ? 0.42 : 0.1);
      double ga = Math.min(RoadTransitions.railSeparation(mesh, a, band), Math.max(0.0, la.median() - band));
      double gb = Math.min(RoadTransitions.railSeparation(mesh, b, band), Math.max(0.0, lb.median() - band));
      if (Math.max(ga, gb) > 1.0E-7) {
         for (int side : new int[]{-1, 1}) {
            barrier(out, a.at(la.medianCenter() + (double)side * ga / 2.0, 0.0), b.at(lb.medianCenter() + (double)side * gb / 2.0, 0.0), highway, raised, d);
         }
      } else {
         barrier(out, a.at(la.medianCenter(), 0.0), b.at(lb.medianCenter(), 0.0), highway, raised, d);
      }
   }

   public static List<RoadStructures.Part> planting(
      RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V leftA, RoadGeometry.V leftB, double widthA, double widthB, double green, double foundationDepth
   ) {
      return planting(a, b, leftA, leftB, widthA, widthB, green, green, foundationDepth);
   }

   private static List<RoadStructures.Part> planting(
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V leftA,
      RoadGeometry.V leftB,
      double widthA,
      double widthB,
      double greenA,
      double greenB,
      double foundationDepth
   ) {
      double green = Math.min(greenA, greenB);
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      double wa = Math.max(0.08, (widthA - 0.3) * greenA);
      double wb = Math.max(0.08, (widthB - 0.3) * greenB);
      double depth = Math.max(0.0, foundationDepth);
      if (Math.max(widthA, widthB) <= 8.0) {
         out.add(
            new RoadStructures.Part(
               a.add(new RoadGeometry.V(0.0, -depth, 0.0)),
               b.add(new RoadGeometry.V(0.0, -depth, 0.0)),
               Math.max(wa, wb),
               depth + 0.3,
               false,
               RoadStructures.Material.SOIL,
               leftA.mul(wa / 2.0),
               leftB.mul(wb / 2.0),
               "sr:planting_soil"
            )
         );
         out.add(
            new RoadStructures.Part(
                  a.add(new RoadGeometry.V(0.0, 0.3, 0.0)),
                  b.add(new RoadGeometry.V(0.0, 0.3, 0.0)),
                  Math.max(0.06, (Math.min(widthA, widthB) - 0.5) * green),
                  0.55 * green,
                  false,
                  RoadStructures.Material.GREEN
               )
               .frames(leftA.mul(Math.max(0.03, (widthA - 0.5) * greenA / 2.0)), leftB.mul(Math.max(0.03, (widthB - 0.5) * greenB / 2.0)))
         );
         out.add(
            new RoadStructures.Part(
                  a.add(new RoadGeometry.V(0.0, 0.3 + 0.52 * green, 0.0)),
                  b.add(new RoadGeometry.V(0.0, 0.3 + 0.52 * green, 0.0)),
                  Math.max(0.04, (Math.min(widthA, widthB) - 0.85) * green),
                  0.25 * green,
                  false,
                  RoadStructures.Material.GREEN
               )
               .frames(leftA.mul(Math.max(0.02, (widthA - 0.85) * greenA / 2.0)), leftB.mul(Math.max(0.02, (widthB - 0.85) * greenB / 2.0)))
         );
      } else {
         plantingLayer(
            out,
            a.add(new RoadGeometry.V(0.0, -depth, 0.0)),
            b.add(new RoadGeometry.V(0.0, -depth, 0.0)),
            leftA,
            leftB,
            wa,
            wb,
            depth + 0.3,
            RoadStructures.Material.SOIL,
            "sr:planting_soil"
         );
         plantingLayer(
            out,
            a.add(new RoadGeometry.V(0.0, 0.3, 0.0)),
            b.add(new RoadGeometry.V(0.0, 0.3, 0.0)),
            leftA,
            leftB,
            Math.max(0.06, (widthA - 0.5) * greenA),
            Math.max(0.06, (widthB - 0.5) * greenB),
            0.55 * green,
            RoadStructures.Material.GREEN,
            ""
         );
         plantingLayer(
            out,
            a.add(new RoadGeometry.V(0.0, 0.3 + 0.52 * green, 0.0)),
            b.add(new RoadGeometry.V(0.0, 0.3 + 0.52 * green, 0.0)),
            leftA,
            leftB,
            Math.max(0.04, (widthA - 0.85) * greenA),
            Math.max(0.04, (widthB - 0.85) * greenB),
            0.25 * green,
            RoadStructures.Material.GREEN,
            ""
         );
      }

      for (int side : new int[]{-1, 1}) {
         out.add(
            new RoadStructures.Part(
                  a.add(leftA.mul((double)side * (wa / 2.0 + 0.1))).add(new RoadGeometry.V(0.0, -depth, 0.0)),
                  b.add(leftB.mul((double)side * (wb / 2.0 + 0.1))).add(new RoadGeometry.V(0.0, -depth, 0.0)),
                  0.2,
                  depth + 0.35,
                  false,
                  RoadStructures.Material.CONCRETE
               )
               .frames(leftA.mul(0.1), leftB.mul(0.1))
         );
      }

      return List.copyOf(out);
   }

   static void plantingLayer(
      List<RoadStructures.Part> out,
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V leftA,
      RoadGeometry.V leftB,
      double widthA,
      double widthB,
      double height,
      RoadStructures.Material material,
      String model
   ) {
      int count = Math.max(1, (int)Math.ceil(Math.max(widthA, widthB) / 8.0));

      for (int i = 0; i < count; i++) {
         double offset = ((double)i + 0.5) / (double)count - 0.5;
         out.add(
            new RoadStructures.Part(
               a.add(leftA.mul(offset * widthA)),
               b.add(leftB.mul(offset * widthB)),
               Math.max(widthA, widthB) / (double)count,
               height,
               false,
               material,
               leftA.mul(widthA / (double)(2 * count)),
               leftB.mul(widthB / (double)(2 * count)),
               model
            )
         );
      }
   }

   private static boolean joinedPlantingEnd(RoadGeometry.Mesh mesh, RoadGeometry.V p) {
      for (boolean start : new boolean[]{true, false}) {
         RoadGeometry.Sample at = start ? mesh.first() : mesh.last();
         RoadProfile.Layout l = RoadProfile.layout(mesh, at);
         RoadGeometry.V delta = p.sub(at.center());
         if (Math.abs(delta.dot(at.left().left())) < 1.0E-5
            && Math.abs(delta.dot(at.left()) - l.medianCenter()) < 0.01
            && plantingContinues(mesh, start, false, 0)) {
            return true;
         }
      }

      return false;
   }

   public static void closePlantingEnds(List<RoadStructures.Part> out, RoadGeometry.Mesh mesh) {
      record End(long x, long y, long z) {
         End(RoadGeometry.V p) {
            this(Math.round(p.x() * 1000000.0), Math.round(p.y() * 1000000.0), Math.round(p.z() * 1000000.0));
         }
      }

      HashMap<End, Integer> counts = new HashMap<>();

      for (RoadStructures.Part p : out) {
         if (p.material() == RoadStructures.Material.SOIL && p.model().equals("sr:planting_soil")) {
            counts.merge(new End(p.a()), 1, Integer::sum);
            counts.merge(new End(p.b()), 1, Integer::sum);
         }
      }

      ArrayList<RoadStructures.Part> caps = new ArrayList<>();

      for (int i = 0; i < out.size(); i++) {
         RoadStructures.Part px = out.get(i);
         if (px.material() == RoadStructures.Material.SOIL && px.model().equals("sr:planting_soil")) {
            boolean first = counts.get(new End(px.a())) == 1 && !joinedPlantingEnd(mesh, px.a());
            boolean last = counts.get(new End(px.b())) == 1 && !joinedPlantingEnd(mesh, px.b());
            if (first || last) {
               double length = px.a().sub(px.b()).horizontalLength();
               if (!(length < 1.0E-7)) {
                  double t = Math.min(0.25 / length, first && last ? 0.3 : 0.6);
                  RoadGeometry.V delta = px.b().sub(px.a());
                  RoadGeometry.V df = px.frameB().sub(px.frameA());
                  RoadGeometry.V a = first ? px.a().add(delta.mul(t)) : px.a();
                  RoadGeometry.V b = last ? px.b().sub(delta.mul(t)) : px.b();
                  RoadGeometry.V fa = first ? px.frameA().add(df.mul(t)) : px.frameA();
                  RoadGeometry.V fb = last ? px.frameB().sub(df.mul(t)) : px.frameB();
                  out.set(i, new RoadStructures.Part(a, b, px.width(), px.height(), false, px.material(), fa, fb, px.model()));

                  for (boolean head : new boolean[]{true, false}) {
                     if (head ? first : last) {
                        RoadGeometry.V outer = head ? px.a() : px.b();
                        RoadGeometry.V inner = head ? a : b;
                        RoadGeometry.V fo = head ? px.frameA() : px.frameB();
                        RoadGeometry.V fi = head ? fa : fb;
                        fo = fo.add(fo.horizontalUnit().mul(0.2));
                        fi = fi.add(fi.horizontalUnit().mul(0.2));
                        caps.add(
                           new RoadStructures.Part(
                              outer,
                              inner,
                              Math.min(8.0, Math.max(fo.horizontalLength(), fi.horizontalLength()) * 2.0),
                              px.height() + 0.05,
                              false,
                              RoadStructures.Material.CONCRETE,
                              fo,
                              fi,
                              "sr:planting_end"
                           )
                        );
                     }
                  }
               }
            }
         }
      }

      out.addAll(caps);
   }

   private static List<RoadStructures.Part> lamp(RoadGeometry.V foot, RoadGeometry.V along, RoadGeometry.V inward, boolean bridge) {
      List<RoadStructures.Part> parts = new ArrayList<>();
      double height = bridge ? 5.9 : 7.0;
      double lower = bridge ? 2.6 : 3.4;
      double armLength = bridge ? 1.35 : 2.2;
      parts.add(postPart(foot, along, 0.4, 0.18, RoadStructures.Material.CONCRETE));
      parts.add(postPart(foot.add(new RoadGeometry.V(0.0, 0.16, 0.0)), along, 0.29, 0.12, RoadStructures.Material.DARK_STEEL));
      parts.add(postPart(foot.add(new RoadGeometry.V(0.0, 0.26, 0.0)), along, 0.19, lower, RoadStructures.Material.DARK_STEEL));
      parts.add(postPart(foot.add(new RoadGeometry.V(0.0, lower + 0.24, 0.0)), along, 0.13, height - lower - 0.24, RoadStructures.Material.STEEL));
      parts.add(postPart(foot.add(new RoadGeometry.V(0.0, lower + 0.21, 0.0)), along, 0.25, 0.13, RoadStructures.Material.DARK_STEEL));
      RoadGeometry.V root = foot.add(new RoadGeometry.V(0.0, height - 0.3, 0.0));
      RoadGeometry.V bend = root.add(inward.mul(armLength * 0.55)).add(new RoadGeometry.V(0.0, bridge ? 0.15 : 0.35, 0.0));
      RoadGeometry.V head = root.add(inward.mul(armLength)).add(new RoadGeometry.V(0.0, bridge ? 0.15 : 0.35, 0.0));
      parts.add(new RoadStructures.Part(root, bend, 0.12, 0.1, false, RoadStructures.Material.STEEL));
      parts.add(new RoadStructures.Part(bend, head, 0.12, 0.1, false, RoadStructures.Material.STEEL));
      parts.add(new RoadStructures.Part(head.sub(inward.mul(0.36)), head.add(inward.mul(0.55)), 0.46, 0.16, false, RoadStructures.Material.DARK_STEEL));
      parts.add(
         new RoadStructures.Part(
            head.sub(inward.mul(0.26)).add(new RoadGeometry.V(0.0, -0.04, 0.0)),
            head.add(inward.mul(0.45)).add(new RoadGeometry.V(0.0, -0.04, 0.0)),
            0.34,
            0.06,
            false,
            RoadStructures.Material.LAMP
         )
      );
      return parts;
   }

   private static RoadStructures.Part postPart(RoadGeometry.V center, RoadGeometry.V along, double width, double height, RoadStructures.Material material) {
      return new RoadStructures.Part(center.sub(along.mul(width / 2.0)), center.add(along.mul(width / 2.0)), width, height, false, material);
   }

   public static void barrier(List<RoadStructures.Part> out, RoadGeometry.V a, RoadGeometry.V b, boolean highway, boolean raised, double distance) {
      if (highway && raised) {
         add(out, new RoadStructures.Part(a, b, 0.62, 0.8, false, RoadStructures.Material.CONCRETE));
         add(
            out,
            new RoadStructures.Part(
               a.add(new RoadGeometry.V(0.0, 0.8, 0.0)), b.add(new RoadGeometry.V(0.0, 0.8, 0.0)), 0.36, 0.35, false, RoadStructures.Material.CONCRETE
            )
         );
      } else if (raised) {
         add(out, new RoadStructures.Part(a, b, 0.42, 0.45, false, RoadStructures.Material.CONCRETE));

         for (double y : new double[]{0.65, 1.05}) {
            add(
               out,
               new RoadStructures.Part(
                  a.add(new RoadGeometry.V(0.0, y, 0.0)), b.add(new RoadGeometry.V(0.0, y, 0.0)), 0.12, 0.12, false, RoadStructures.Material.STEEL
               )
            );
         }

         if ((int)Math.floor(distance * 2.0) % 4 == 0) {
            post(out, a, b, 0.45, 1.15);
         }
      } else if (highway) {
         for (double y : new double[]{0.58, 0.8}) {
            add(
               out,
               new RoadStructures.Part(
                  a.add(new RoadGeometry.V(0.0, y, 0.0)), b.add(new RoadGeometry.V(0.0, y, 0.0)), 0.3, 0.18, false, RoadStructures.Material.STEEL
               )
            );
         }

         if ((int)Math.floor(distance * 2.0) % 4 == 0) {
            post(out, a, b, 0.0, 0.94);
         }
      } else {
         for (double y : new double[]{0.38, 0.95}) {
            add(
               out,
               new RoadStructures.Part(
                  a.add(new RoadGeometry.V(0.0, y, 0.0)), b.add(new RoadGeometry.V(0.0, y, 0.0)), 0.1, 0.1, false, RoadStructures.Material.STEEL
               )
            );
         }

         if ((int)Math.floor(distance * 2.0) % 2 == 0) {
            post(out, a, b, 0.0, 1.05);
         }
      }
   }

   public static void terminalPosts(List<RoadStructures.Part> parts) {
      terminalPosts(parts, null);
   }

   public static void terminalPosts(List<RoadStructures.Part> parts, RoadStructures.Ground ground) {
      LinkedHashMap<RoadStructures.RailEndKey, List<RoadStructures.RailEnd>> ends = new LinkedHashMap<>();
      HashSet<RoadStructures.RailEndKey> lower = new HashSet<>();

      for (RoadStructures.Part p : parts) {
         if (p.material() == RoadStructures.Material.STEEL
            && (p.width() == 0.1 && p.height() == 0.1 || p.width() == 0.12 && p.height() == 0.12 || p.width() == 0.3 && p.height() == 0.18)) {
            RoadGeometry.V dir = p.b().sub(p.a()).horizontalUnit();
            ends.computeIfAbsent(new RoadStructures.RailEndKey(p.a(), p), k -> new ArrayList<>()).add(new RoadStructures.RailEnd(p.a(), dir, p));
            ends.computeIfAbsent(new RoadStructures.RailEndKey(p.b(), p), k -> new ArrayList<>()).add(new RoadStructures.RailEnd(p.b(), dir.mul(-1.0), p));
            lower.add(new RoadStructures.RailEndKey(p.a(), p));
            lower.add(new RoadStructures.RailEndKey(p.b(), p));
         }
      }

      HashSet<String> posts = new HashSet<>();

      for (RoadStructures.Part px : parts) {
         if (px.material() == RoadStructures.Material.DARK_STEEL && px.width() >= 0.1 && px.width() <= 0.16 && px.height() < 1.3) {
            posts.add(postKey(px.a()));
         }
      }

      for (List<RoadStructures.RailEnd> group : ends.values()) {
         if (group.size() == 1) {
            RoadStructures.RailEnd e = group.get(0);
            double separation = e.part().width() == 0.1 ? 0.57 : (e.part().width() == 0.12 ? 0.4 : 0.22);
            if (lower.contains(new RoadStructures.RailEndKey(e.p().add(new RoadGeometry.V(0.0, -separation, 0.0)), e.part()))) {
               double top = e.part().width() == 0.1 ? 1.05 : (e.part().width() == 0.12 ? 1.15 : 0.94);
               double railY = e.part().width() == 0.1 ? 0.95 : (e.part().width() == 0.12 ? 1.05 : 0.8);
               double bottom = e.part().width() == 0.12 ? 0.45 : 0.0;
               RoadGeometry.V base = e.p().add(new RoadGeometry.V(0.0, -railY, 0.0));
               if ((ground == null || ground.railPost(base, e.part().width() == 0.3, e.part().width() == 0.12))
                  && posts.add(postKey(base.add(new RoadGeometry.V(0.0, bottom > 0.0 ? bottom - 0.02 : bottom, 0.0))))) {
                  ArrayList<RoadStructures.Part> terminal = new ArrayList<>();
                  post(terminal, base, base.add(e.into()), bottom, top);
                  if (ground != null && ground.unionRails()) {
                     RoadStructures.Part pxx = terminal.get(0);
                     RoadStructures.Part fitted = null;

                     for (double width : new double[]{0.16, 0.14, 0.12, 0.1}) {
                        if (fitted != null) {
                           break;
                        }

                        for (double length : new double[]{0.16, 0.08, 0.04}) {
                           RoadStructures.Part candidate = new RoadStructures.Part(
                              pxx.a(),
                              pxx.a().add(e.into().mul(length)),
                              width,
                              railY + e.part().height() - (bottom > 0.0 ? bottom - 0.02 : bottom),
                              false,
                              RoadStructures.Material.DARK_STEEL
                           );
                           if (ground == null || !ground.railBlocked(candidate, base, base.add(e.into().mul(length)))) {
                              fitted = candidate;
                              break;
                           }
                        }
                     }

                     if (fitted != null) {
                        parts.add(fitted);
                     }
                  } else {
                     parts.addAll(terminal);
                  }
               }
            }
         }
      }
   }

   private static String postKey(RoadGeometry.V p) {
      return Math.round(p.x() * 100000.0) + ":" + Math.round(p.y() * 100000.0) + ":" + Math.round(p.z() * 100000.0);
   }

   private static void post(List<RoadStructures.Part> out, RoadGeometry.V a, RoadGeometry.V b, double bottom, double top) {
      RoadGeometry.V dir = b.sub(a).horizontalUnit();
      RoadGeometry.V p = a.add(new RoadGeometry.V(0.0, bottom > 0.0 ? bottom - 0.02 : bottom, 0.0));
      add(
         out,
         new RoadStructures.Part(p, p.add(dir.mul(0.16)), 0.16, top - bottom + 0.03 + (bottom > 0.0 ? 0.02 : 0.0), false, RoadStructures.Material.DARK_STEEL)
      );
   }

   private static void add(List<RoadStructures.Part> out, RoadStructures.Part part) {
      for (int i = out.size() - 1; i >= Math.max(0, out.size() - 12); i--) {
         RoadStructures.Part old = out.get(i);
         if (!old.pier()
            && old.material() == part.material()
            && old.width() == part.width()
            && old.height() == part.height()
            && !(old.b().distance(part.a()) > 1.0E-6)) {
            RoadGeometry.V before = old.b().sub(old.a());
            RoadGeometry.V after = part.b().sub(part.a());
            if (!(before.horizontalLength() < 1.0E-8) && !(after.horizontalLength() < 1.0E-8)) {
               RoadGeometry.V da = before.horizontalUnit();
               RoadGeometry.V db = after.horizontalUnit();
               double dot = da.dot(db);
               if (dot > 0.99999999
                  && old.a().distance(part.b()) <= 8.0
                  && Math.abs(before.y() / before.horizontalLength() - after.y() / after.horizontalLength()) < 1.0E-6) {
                  out.set(i, new RoadStructures.Part(old.a(), part.b(), old.width(), old.height(), false, old.material(), old.frameA(), part.frameB()));
                  return;
               }

               if (dot > -0.9 && old.frameB() == null && part.frameA() == null) {
                  RoadGeometry.V joint = da.left().add(db.left()).mul(old.width() / 2.0 / (1.0 + dot));
                  out.set(i, old.frames(old.frameA(), joint));
                  part = part.frames(joint, part.frameB());
               }
               break;
            }
         }
      }

      out.add(part);
   }

   public static RoadGeometry.Sample sample(RoadGeometry.Mesh mesh, double distance) {
      List<RoadGeometry.Sample> s = mesh.samples();
      int lo = 0;
      int hi = s.size() - 1;
      distance = Math.max(0.0, Math.min(mesh.length(), distance));

      while (hi - lo > 1) {
         int mid = (lo + hi) / 2;
         if (s.get(mid).distance() < distance) {
            lo = mid;
         } else {
            hi = mid;
         }
      }

      RoadGeometry.Sample a = s.get(lo);
      RoadGeometry.Sample b = s.get(hi);
      double span = b.distance() - a.distance();
      double t = span < 1.0E-9 ? 0.0 : (distance - a.distance()) / span;
      RoadGeometry.V direction = a.left().add(b.left().sub(a.left()).mul(t));
      if (direction.horizontalLength() < 1.0E-7) {
         direction = a.left();
      }

      return new RoadGeometry.Sample(
         a.center().add(b.center().sub(a.center()).mul(t)), direction.horizontalUnit(), distance, a.halfWidth() + (b.halfWidth() - a.halfWidth()) * t
      );
   }

   private RoadStructures() {
   }

   private static record ClosureRailRun(int side, List<RoadStructures.RailSpan> spans) {
   }

   public interface Ground {
      default List<RoadStructures.Part> fixedSupports() {
         return null;
      }

      default boolean sharedDeck() {
         return false;
      }

      default List<RoadStructures.Part> sharedSupports(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, double depth, double width, double offset) {
         return null;
      }

      default boolean requiredGantry(RoadGantry.Station station) {
         return true;
      }

      default List<RoadStructures.Part> preservedGantry(RoadGantry.Station station) {
         return null;
      }

      default void missingSupport(double station, String reason) {
      }

      default boolean closedLanePlanting(UUID connection) {
         return true;
      }

      default RoadGeometry.Mesh terrainReference(RoadGeometry.Mesh mesh) {
         return mesh;
      }

      double top(double var1, double var3, double var5);

      default double foundation(RoadGeometry.V center, double top, double width) {
         double floor = Double.POSITIVE_INFINITY;

         for (double dx : new double[]{-0.5, 0.0, 0.5}) {
            for (double dz : new double[]{-0.5, 0.0, 0.5}) {
               double y = this.top(center.x() + dx * width, center.z() + dz * width, top);
               if (!Double.isFinite(y)) {
                  return Double.NaN;
               }

               floor = Math.min(floor, y);
            }
         }

         return floor;
      }

      default double surface(double x, double z, double roadY) {
         double probe = roadY + 1.0E-4;

         for (int i = 0; (double)i <= 96.0; probe++) {
            RoadPlanningBudget.check();
            double surface = this.top(x, z, probe);
            if (!Double.isFinite(surface) || Math.abs(surface - probe) > 1.0E-7) {
               return surface;
            }

            i++;
         }

         return roadY + 96.0;
      }

      boolean blocked(RoadStructures.Part var1);

      default boolean plantBlocked(RoadStructures.Part part, RoadGeometry.Sample mount) {
         return this.blocked(part);
      }

      default boolean lampBlocked(RoadStructures.Part part, RoadGeometry.Sample mount) {
         return this.blocked(part);
      }

      default boolean gantryMountConflict(RoadStructures.Part part, RoadGantry.Station station) {
         return false;
      }

      default String blockedReason(RoadStructures.Part part) {
         return String.format(Locale.ROOT, "%s构件位于 X=%.1f Y=%.1f Z=%.1f", part.pier() ? "桥墩" : "桥梁", part.a().x(), part.a().y(), part.a().z());
      }

      boolean joined(RoadGeometry.V var1);

      default RoadGeometry.V railJoint(RoadGeometry.V position, RoadGeometry.V direction) {
         return null;
      }

      default boolean railPost(RoadGeometry.V position) {
         return true;
      }

      default RoadGeometry.V railJoint(RoadGeometry.V position, RoadGeometry.V direction, boolean highway, boolean raised) {
         return this.railJoint(position, direction);
      }

      default boolean railPost(RoadGeometry.V position, boolean highway, boolean raised) {
         return this.railPost(position);
      }

      default List<RoadRailJoin.Span> railSpans(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V outside) {
         return this.joined(outside) ? List.of() : List.of(new RoadRailJoin.Span(a, b));
      }

      default List<RoadRailJoin.Span> railSpans(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V outside, double inset) {
         return this.railSpans(a, b, outside);
      }

      default List<RoadRailJoin.Span> capRailSpans(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V outside) {
         return this.railSpans(a, b, outside);
      }

      default boolean railBlocked(RoadStructures.Part part, RoadGeometry.V a, RoadGeometry.V b) {
         return this.blocked(part);
      }

      default boolean unionRails() {
         return false;
      }

      default boolean marker(RoadGeometry.V point) {
         return false;
      }

      default boolean furnitureClear(RoadGeometry.V point) {
         return false;
      }

      default boolean wireContinues(boolean first) {
         return false;
      }

      default boolean lampClear(RoadGeometry.V point) {
         return false;
      }
   }

   public static record Heights(double a, double b, double c, double d) {
      public Heights(double a, double b, double c, double d) {
         if (RoadGeometry.finite(a, b, c, d) && !(Math.min(Math.min(a, b), Math.min(c, d)) < 0.0)) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
         } else {
            throw new IllegalArgumentException("Invalid corner heights");
         }
      }

      public double get(int i) {
         return switch (i) {
            case 0 -> this.a;
            case 1 -> this.b;
            case 2 -> this.c;
            default -> this.d;
         };
      }
   }

   public static enum Material {
      DEFAULT,
      STEEL,
      DARK_STEEL,
      CONCRETE,
      SOIL,
      GREEN,
      LAMP,
      SIGNAL_MAIN,
      SIGNAL_RAMP,
      CB_POST,
      CB_ARM,
      CB_BASE,
      SIGNAL_VEHICLE,
      SIGNAL_PEDESTRIAN,
      SIGNAL_LEFT,
      TUNNEL,
      SIGN_GREEN,
      SIGN_BLUE,
      SIGN_WHITE,
      ARCH_STEEL,
      GANTRY_FRAME,
      WALK_STONE_BRICKS,
      WALK_BRICKS,
      WALK_ANDESITE,
      WALK_DIORITE,
      WALK_GRANITE,
      WALK_SMOOTH_STONE,
      TACTILE,
      NOISE_BARRIER,
      OAK_LOG,
      BIRCH_LOG,
      SPRUCE_LOG,
      CHERRY_LOG,
      OAK_LEAVES,
      BIRCH_LEAVES,
      SPRUCE_LEAVES,
      CHERRY_LEAVES,
      FLOWERS,
      LAMP_BLUE,
      FLOWER_PINK,
      FLOWER_YELLOW,
      FLOWER_WHITE;
   }

   public static record Part(
      RoadGeometry.V a,
      RoadGeometry.V b,
      double width,
      double height,
      boolean pier,
      RoadStructures.Material material,
      RoadGeometry.V frameA,
      RoadGeometry.V frameB,
      String model,
      RoadStructures.Heights heights
   ) {
      public Part(
         RoadGeometry.V a,
         RoadGeometry.V b,
         double width,
         double height,
         boolean pier,
         RoadStructures.Material material,
         RoadGeometry.V frameA,
         RoadGeometry.V frameB,
         String model
      ) {
         this(a, b, width, height, pier, material, frameA, frameB, model, null);
      }

      public Part(
         RoadGeometry.V a,
         RoadGeometry.V b,
         double width,
         double height,
         boolean pier,
         RoadStructures.Material material,
         RoadGeometry.V frameA,
         RoadGeometry.V frameB
      ) {
         this(a, b, width, height, pier, material, frameA, frameB, "");
      }

      public Part(RoadGeometry.V a, RoadGeometry.V b, double width, double height, boolean pier, RoadStructures.Material material) {
         this(a, b, width, height, pier, material, null, null);
      }

      public Part(RoadGeometry.V a, RoadGeometry.V b, double width, double height, boolean pier) {
         this(a, b, width, height, pier, RoadStructures.Material.DEFAULT);
      }

      public Part(
         RoadGeometry.V a,
         RoadGeometry.V b,
         double width,
         double height,
         boolean pier,
         RoadStructures.Material material,
         RoadGeometry.V frameA,
         RoadGeometry.V frameB,
         String model,
         RoadStructures.Heights heights
      ) {
         if (model != null && model.length() <= 128) {
            if (heights != null) {
               for (int i = 0; i < 4; i++) {
                  if (heights.get(i) > height + 1.0E-7) {
                     throw new IllegalArgumentException("Corner height exceeds bounds");
                  }
               }
            }

            if (RoadGeometry.finite(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), width, height)
               && !(width <= 0.0)
               && !(width > 8.0)
               && !(height <= 0.0)
               && !(height > 100.0)) {
               this.a = a;
               this.b = b;
               this.width = width;
               this.height = height;
               this.pier = pier;
               this.material = material;
               this.frameA = frameA;
               this.frameB = frameB;
               this.model = model;
               this.heights = heights;
            } else {
               throw new IllegalArgumentException("无效的高架结构尺寸（" + material + "，宽=" + width + "，高=" + height + "）");
            }
         } else {
            throw new IllegalArgumentException("无效的设施模型");
         }
      }

      public RoadStructures.Part frames(RoadGeometry.V first, RoadGeometry.V last) {
         return new RoadStructures.Part(this.a, this.b, this.width, this.height, this.pier, this.material, first, last, this.model, this.heights);
      }

      public double verticalFrame() {
         return Math.max(this.frameA == null ? 0.0 : Math.abs(this.frameA.y()), this.frameB == null ? 0.0 : Math.abs(this.frameB.y()));
      }

      public double halfExtent() {
         return Math.max(
            this.width / 2.0, Math.max(this.frameA == null ? 0.0 : this.frameA.horizontalLength(), this.frameB == null ? 0.0 : this.frameB.horizontalLength())
         );
      }

      public boolean luminous() {
         return this.material == RoadStructures.Material.LAMP;
      }

      public List<RoadGeometry.V> base() {
         RoadGeometry.V side = !this.pier && !(this.b.sub(this.a).horizontalLength() < 1.0E-8)
            ? this.b.sub(this.a).horizontalUnit().left().mul(this.width / 2.0)
            : new RoadGeometry.V(this.width / 2.0, 0.0, 0.0);
         RoadGeometry.V aa = this.a;
         RoadGeometry.V bb = this.b;
         if (this.pier) {
            aa = this.a.add(new RoadGeometry.V(0.0, 0.0, -this.width / 2.0));
            bb = this.b.add(new RoadGeometry.V(0.0, 0.0, this.width / 2.0));
         }

         RoadGeometry.V first = this.frameA == null ? side : this.frameA;
         RoadGeometry.V last = this.frameB == null ? side : this.frameB;
         return List.of(aa.add(first), aa.sub(first), bb.sub(last), bb.add(last));
      }

      public List<RoadGeometry.V> top() {
         List<RoadGeometry.V> base = this.base();
         ArrayList<RoadGeometry.V> out = new ArrayList<>(4);

         for (int i = 0; i < 4; i++) {
            out.add(base.get(i).add(new RoadGeometry.V(0.0, this.heights == null ? this.height : this.heights.get(i), 0.0)));
         }

         return List.copyOf(out);
      }

      public List<RoadSurface.Face> faces() {
         if (this.model.equals("sr:railway_crossing_sign")) {
            return RailwayCrossingSigns.faces(this);
         } else if (this.model.equals("sr:railway_fence")) {
            return RailbedFence.faces(this);
         } else if (this.model.equals("sr:crash_cushion")) {
            return RoadCrashCushion.faces(this);
         } else if (TollModels.supports(this)) {
            return TollModels.faces(this);
         } else if (this.material == RoadStructures.Material.NOISE_BARRIER) {
            return RoadNoiseModel.faces(this, false);
         } else if (RoadSignals.signal(this)) {
            return RoadSignalModel.faces(this, false, false);
         } else if (RoadPoleModel.support(this)) {
            return RoadPoleModel.faces(this);
         } else {
            List<RoadGeometry.V> base = this.base();
            List<RoadGeometry.V> top = this.top();
            List<RoadSurface.Face> out = new ArrayList<>();

            int color = switch (this.material) {
               case DEFAULT -> this.pier ? 10857388 : 13818073;
               case STEEL, GANTRY_FRAME -> 12832207;
               case DARK_STEEL -> 6913154;
               case CONCRETE -> 12171693;
               case SOIL -> 6706238;
               case GREEN -> 7580241;
               case LAMP -> 16773575;
               case SIGNAL_MAIN, SIGNAL_RAMP, SIGNAL_VEHICLE, SIGNAL_PEDESTRIAN, SIGNAL_LEFT -> 1580320;
               case CB_POST, CB_ARM, CB_BASE -> 15198183;
               case TUNNEL -> 13094087;
               case SIGN_GREEN -> 1340234;
               case SIGN_BLUE, LAMP_BLUE -> 2316692;
               case SIGN_WHITE -> 16053212;
               case ARCH_STEEL -> 11292216;
               case WALK_STONE_BRICKS, WALK_BRICKS, WALK_ANDESITE, WALK_DIORITE, WALK_GRANITE, WALK_SMOOTH_STONE -> 16777215;
               case TACTILE -> 15318594;
               case NOISE_BARRIER -> 16777215;
               case OAK_LOG, BIRCH_LOG, SPRUCE_LOG, CHERRY_LOG, FLOWERS -> 16777215;
               case OAK_LEAVES -> 6987078;
               case BIRCH_LEAVES -> 8431445;
               case SPRUCE_LEAVES -> 5010517;
               case CHERRY_LEAVES -> 16777215;
               case FLOWER_PINK -> 15172530;
               case FLOWER_YELLOW -> 15779645;
               case FLOWER_WHITE -> 16775403;
            };

            RoadSurface.Texture texture = switch (this.material) {
               case DEFAULT, CONCRETE, TUNNEL -> RoadSurface.Texture.CONCRETE;
               case STEEL, DARK_STEEL, ARCH_STEEL, GANTRY_FRAME -> RoadSurface.Texture.METAL;
               case SOIL -> RoadSurface.Texture.SOIL;
               case GREEN -> RoadSurface.Texture.LEAVES;
               case LAMP, SIGNAL_MAIN, SIGNAL_RAMP, SIGNAL_VEHICLE, SIGNAL_PEDESTRIAN, SIGNAL_LEFT, SIGN_GREEN, SIGN_BLUE, SIGN_WHITE, LAMP_BLUE, FLOWER_PINK, FLOWER_YELLOW, FLOWER_WHITE -> RoadSurface.Texture.PLAIN;
               case CB_POST, CB_ARM, CB_BASE -> RoadSurface.Texture.METAL;
               case WALK_STONE_BRICKS, WALK_BRICKS, WALK_ANDESITE, WALK_DIORITE, WALK_GRANITE, WALK_SMOOTH_STONE -> RoadSurface.Texture.valueOf(
               this.material.name()
            );
               case TACTILE -> RoadSurface.Texture.PLAIN;
               case NOISE_BARRIER -> RoadSurface.Texture.NOISE_BARRIER;
               case OAK_LOG, BIRCH_LOG, SPRUCE_LOG, CHERRY_LOG, OAK_LEAVES, BIRCH_LEAVES, SPRUCE_LEAVES, CHERRY_LEAVES, FLOWERS -> RoadSurface.Texture.valueOf(
               this.material.name()
            );
            };
            RoadGeometry.V center = new RoadGeometry.V(0.0, 0.0, 0.0);

            for (int i = 0; i < 4; i++) {
               center = center.add(base.get(i)).add(top.get(i));
            }

            center = center.mul(0.125);
            out.add(face(top, color, this.luminous(), texture, center));
            if (this.material != RoadStructures.Material.GREEN) {
               out.add(face(base, color, this.luminous(), texture, center));
            }

            for (int i = 0; i < 4; i++) {
               int j = (i + 1) % 4;
               out.add(face(List.of(base.get(i), base.get(j), top.get(j), top.get(i)), color, this.luminous(), texture, center));
            }

            out.removeIf(f -> surfaceArea(f.points()) < 1.0E-10);
            return out;
         }
      }

      private static double surfaceArea(List<RoadGeometry.V> points) {
         double sum = 0.0;

         for (int i = 1; i + 1 < points.size(); i++) {
            RoadGeometry.V a = points.get(i).sub(points.get(0));
            RoadGeometry.V b = points.get(i + 1).sub(points.get(0));
            RoadGeometry.V n = new RoadGeometry.V(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
            sum += Math.sqrt(n.dot(n)) / 2.0;
         }

         return sum;
      }

      private static RoadSurface.Face face(List<RoadGeometry.V> points, int color, boolean light, RoadSurface.Texture texture, RoadGeometry.V center) {
         ArrayList<RoadGeometry.V> unique = new ArrayList<>();

         for (RoadGeometry.V p : points) {
            if (unique.isEmpty() || p.distance(unique.get(unique.size() - 1)) > 1.0E-9) {
               unique.add(p);
            }
         }

         if (unique.size() > 1 && unique.get(0).distance(unique.get(unique.size() - 1)) < 1.0E-9) {
            unique.remove(unique.size() - 1);
         }

         if (unique.size() >= 3) {
            points = unique;
         }

         RoadGeometry.V a = points.get(1).sub(points.get(0));
         RoadGeometry.V b = points.get(2).sub(points.get(0));
         RoadGeometry.V n = new RoadGeometry.V(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
         if (n.dot(points.get(0).sub(center)) < 0.0) {
            points = new ArrayList<>(points);
            Collections.reverse(points);
         }

         return new RoadSurface.Face(points, color, light, texture);
      }
   }

   private static record RailEnd(RoadGeometry.V p, RoadGeometry.V into, RoadStructures.Part part) {
   }

   private static record RailEndKey(long x, long y, long z, double width, double height) {
      RailEndKey(RoadGeometry.V p, RoadStructures.Part part) {
         this(Math.round(p.x() * 100000.0), Math.round(p.y() * 100000.0), Math.round(p.z() * 100000.0), part.width(), part.height());
      }
   }

   private static record RailSpan(RoadGeometry.V a, RoadGeometry.V b, boolean raised, double distance, double endDistance) {
   }
}
