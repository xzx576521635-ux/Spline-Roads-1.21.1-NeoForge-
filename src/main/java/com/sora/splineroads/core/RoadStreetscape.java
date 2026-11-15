package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class RoadStreetscape {
   public static final double MAX_BRIDGE_GROUND_GAP = 24.0;

   private static List<RoadStreetscape.Span> union(List<RoadStreetscape.Span> a, List<RoadStreetscape.Span> b) {
      ArrayList<RoadStreetscape.Span> out = new ArrayList<>();

      for (RoadStreetscape.Span s : Stream.concat(a.stream(), b.stream()).sorted(Comparator.comparingDouble(RoadStreetscape.Span::from)).toList()) {
         append(out, s.from(), s.to());
      }

      return List.copyOf(out);
   }

   private static List<RoadStreetscape.Span> outsideRoofs(List<RoadStreetscape.Span> spans, List<RoadAutoTunnels.Section> sections) {
      ArrayList<RoadStreetscape.Span> out = new ArrayList<>();

      for (RoadStreetscape.Span span : spans) {
         for (double d = span.from(); d < span.to() - 1.0E-7; d++) {
            double at = d + 0.5;
            if (sections.stream().noneMatch(s -> s.roof() && at >= s.from() && at < s.to())) {
               append(out, d, Math.min(d + 1.0, span.to()));
            }
         }
      }

      return List.copyOf(out);
   }

   private static void append(List<RoadStreetscape.Span> out, double from, double to) {
      if (!out.isEmpty() && out.get(out.size() - 1).to() >= from - 1.0E-7) {
         RoadStreetscape.Span last = out.remove(out.size() - 1);
         out.add(new RoadStreetscape.Span(last.from(), Math.max(last.to(), to)));
      } else {
         out.add(new RoadStreetscape.Span(from, to));
      }
   }

   public static RoadStreetscape.SideStructures classifySides(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      if (mesh.settings().structure() != RoadGeometry.Structure.AUTO) {
         return new RoadStreetscape.SideStructures(List.of(), List.of(), List.of(), List.of());
      } else {
         List<RoadAutoTunnels.Section> sections = RoadAutoTunnels.structuralSections(ground.terrainReference(mesh), ground);
         ArrayList<RoadStreetscape.Span> negative = new ArrayList<>();
         ArrayList<RoadStreetscape.Span> positive = new ArrayList<>();

         for (RoadAutoTunnels.Section section : sections) {
            if (!section.roof() && !section.surface()) {
               if (section.rightDepth() >= RoadAutoTunnels.minCutDepth(mesh)) {
                  append(negative, section.from(), section.to());
               }

               if (section.leftDepth() >= RoadAutoTunnels.minCutDepth(mesh)) {
                  append(positive, section.from(), section.to());
               }
            }
         }

         return new RoadStreetscape.SideStructures(
            outsideRoofs(classify(mesh, ground, -1), sections), outsideRoofs(classify(mesh, ground, 1), sections), negative, positive
         );
      }
   }

   public static boolean cut(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, int side) {
      return mesh.settings().structure() == RoadGeometry.Structure.AUTO
         && mesh.settings().options().streetscape().sides().cut(side).stream().anyMatch(s -> at.distance() >= s.from() && at.distance() < s.to());
   }

   public static List<RoadStreetscape.Span> classify(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      return union(classify(mesh, ground, -1), classify(mesh, ground, 1));
   }

   private static List<RoadStreetscape.Span> classify(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, int side) {
      if (mesh.settings().structure() != RoadGeometry.Structure.AUTO) {
         return List.of();
      } else {
         mesh = ground.terrainReference(mesh);
         ArrayList<RoadStreetscape.Span> out = new ArrayList<>();
         double begin = -1.0;

         for (double d = 0.0; d < mesh.length() - 1.0E-6; d++) {
            double end = Math.min(mesh.length(), d + 1.0);
            RoadGeometry.Sample at = RoadStructures.sample(mesh, (d + end) / 2.0);
            boolean raised = !underground(mesh, at.distance())
               && !RoadAutoTunnels.below(mesh, at, ground, side)
               && RoadStructures.elevated(mesh, at, ground, side);
            if (raised && begin < 0.0) {
               begin = d;
            }

            if (!raised && begin >= 0.0) {
               out.add(new RoadStreetscape.Span(begin, d));
               begin = -1.0;
            }
         }

         if (begin >= 0.0) {
            out.add(new RoadStreetscape.Span(begin, mesh.length()));
         }

         ArrayList<RoadStreetscape.Span> joined = new ArrayList<>();

         for (RoadStreetscape.Span next : out) {
            if (!joined.isEmpty()) {
               RoadStreetscape.Span previous = joined.get(joined.size() - 1);
               boolean connect = next.from() - previous.to() <= 24.0000001;

               for (double d = previous.to(); connect && d < next.from(); d += 0.5) {
                  RoadGeometry.Sample atx = RoadStructures.sample(mesh, d);
                  boolean levelCrossing = false;

                  for (RailwayCrossings.Surface crossing : mesh.settings().options().levelCrossings()) {
                     if (d >= crossing.from() - 1.0 && d <= crossing.to() + 1.0) {
                        levelCrossing = true;
                        break;
                     }
                  }

                  if (levelCrossing
                     || underground(mesh, d)
                     || RoadAutoTunnels.below(mesh, atx, ground, side)
                     || ground.joined(atx.center())
                     || ground.furnitureClear(atx.center())) {
                     connect = false;
                  }
               }

               if (connect) {
                  joined.set(joined.size() - 1, new RoadStreetscape.Span(previous.from(), next.to()));
                  continue;
               }
            }

            joined.add(next);
         }

         return List.copyOf(joined);
      }
   }

   public static RoadGeometry.Mesh resolve(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      RoadProfile.Options options = mesh.settings().options();
      RoadProfile.Options next = options.streetscape(
         options.streetscape()
            .sideStructures(classifySides(mesh, ground))
            .tunnels(RoadAutoTunnels.detect(mesh, ground).stream().map(s -> new RoadStreetscape.Span(s.from(), s.to())).toList())
      );
      RoadGeometry.Mesh reference = mesh.reference();
      if (reference != null) {
         RoadProfile.Options ro = reference.settings().options();
         RoadGeometry.Settings rs = reference.settings()
            .options(ro.streetscape(ro.streetscape().sideStructures(next.streetscape().sides()).tunnels(next.streetscape().tunnels())));
         reference = new RoadGeometry.Mesh(
            reference.samples(),
            rs,
            reference.min(),
            reference.max(),
            reference.length(),
            reference.closed(),
            reference.controlPoint(),
            reference.controls(),
            reference.reference()
         );
      }

      return new RoadGeometry.Mesh(
         mesh.samples(), mesh.settings().options(next), mesh.min(), mesh.max(), mesh.length(), mesh.closed(), mesh.controlPoint(), mesh.controls(), reference
      );
   }

   public static boolean underground(RoadGeometry.Mesh mesh, double station) {
      return mesh.settings().structure() == RoadGeometry.Structure.TUNNEL
         || mesh.settings().options().streetscape().tunnels().stream().anyMatch(s -> station >= s.from() - 1.0E-7 && station <= s.to() + 1.0E-7);
   }

   public static boolean raised(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample) {
      return mesh.settings().structure() == RoadGeometry.Structure.BRIDGE
         || mesh.settings().structure() == RoadGeometry.Structure.AUTO
            && mesh.settings().options().streetscape().raisedSpans().stream().anyMatch(s -> sample.distance() >= s.from() && sample.distance() < s.to());
   }

   public static boolean raised(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, int side) {
      return !underground(mesh, sample.distance())
         && (
            mesh.settings().structure() == RoadGeometry.Structure.BRIDGE
               || mesh.settings().structure() == RoadGeometry.Structure.AUTO
                  && mesh.settings()
                     .options()
                     .streetscape()
                     .sides()
                     .raised(side)
                     .stream()
                     .anyMatch(s -> sample.distance() >= s.from() && sample.distance() < s.to())
         );
   }

   public static double raisedBlend(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample) {
      if (mesh.settings().structure() == RoadGeometry.Structure.BRIDGE) {
         return 1.0;
      } else if (mesh.settings().structure() != RoadGeometry.Structure.AUTO) {
         return 0.0;
      } else {
         double distance = Double.POSITIVE_INFINITY;

         for (RoadStreetscape.Span span : mesh.settings().options().streetscape().raisedSpans()) {
            if (sample.distance() >= span.from() && sample.distance() <= span.to()) {
               return 1.0;
            }

            distance = Math.min(distance, Math.min(Math.abs(sample.distance() - span.from()), Math.abs(sample.distance() - span.to())));
         }

         return 1.0 - RoadGeometry.Settings.smooth(Math.min(1.0, distance / 24.0));
      }
   }

   public static double medianConversionBlend(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample) {
      RoadProfile.Median median = RoadProfile.catalog(mesh.settings()).median();
      return median != RoadProfile.Median.RAIL && !mesh.settings().options().infrastructure().medianConversion().converts(median)
         ? 0.0
         : raisedBlend(mesh, sample) * (1.0 - RoadTransitions.greenPortWeight(mesh, sample));
   }

   public static double tunnelBlend(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample) {
      if (mesh.settings().structure() == RoadGeometry.Structure.TUNNEL) {
         return 1.0;
      } else if (mesh.settings().structure() != RoadGeometry.Structure.AUTO) {
         return 0.0;
      } else {
         double distance = Double.POSITIVE_INFINITY;

         for (RoadStreetscape.Span span : mesh.settings().options().streetscape().tunnels()) {
            if (sample.distance() >= span.from() && sample.distance() <= span.to()) {
               return 1.0;
            }

            distance = Math.min(distance, Math.min(Math.abs(sample.distance() - span.from()), Math.abs(sample.distance() - span.to())));
         }

         return 1.0 - RoadGeometry.Settings.smooth(Math.min(1.0, distance / 24.0));
      }
   }

   public static boolean walkLampAllowed(RoadProfile.Options o, RoadGeometry.Structure structure, RoadGeometry.Style style) {
      return RoadProfile.catalog(style).type() == RoadProfile.Type.ORDINARY
         && RoadProfile.catalog(style).twoWay()
         && structure != RoadGeometry.Structure.BRIDGE
         && structure != RoadGeometry.Structure.TUNNEL
         && o.sidewalk().enabled()
         && o.cycle()
         && o.streetscape().separator() != RoadStreetscape.Separator.LINE;
   }

   public static double parkingLength() {
      return 8.0;
   }

   public static double separatorWidth(RoadProfile.Options o) {
      return o.cycle() && !o.streetscape().parking() && o.streetscape().separator() == RoadStreetscape.Separator.GREEN ? 1.5 : 0.0;
   }

   public static boolean walkSide(RoadSidewalks.Config c, int side) {
      return c.enabled()
         && (c.side() == RoadSidewalks.Side.BOTH || c.side() == RoadSidewalks.Side.LEFT && side < 0 || c.side() == RoadSidewalks.Side.RIGHT && side > 0);
   }

   public static RoadSidewalks.Config fitWalk(RoadSidewalks.Config walk, RoadStreetscape.Config c) {
      if (!walk.enabled()) {
         return walk;
      } else {
         double inner = c.planting() == RoadStreetscape.Planting.NONE ? 1.2 : (c.planting() == RoadStreetscape.Planting.FLOWERS ? 2.2 : 3.6);
         double minimum = inner + (c.walkLamps() ? 1.2 : 0.0) + (walk.tactile() ? 1.4 : 0.4);
         return walk.width(Math.max(walk.width(), (int)Math.ceil(minimum)));
      }
   }

   public static double tactileOffset(double width) {
      return Math.max(0.5, width - 0.8);
   }

   public static boolean bridge(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, RoadStructures.Ground ground, int side) {
      return raised(mesh, at, side)
         || mesh.settings().structure() != RoadGeometry.Structure.GROUND
            && !underground(mesh, at.distance())
            && !RoadAutoTunnels.below(mesh, at, ground, side)
            && RoadStructures.elevated(mesh, at, ground, side);
   }

   public static boolean bridge(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, RoadStructures.Ground g) {
      return raised(mesh, at) || mesh.settings().structure() != RoadGeometry.Structure.GROUND && RoadStructures.elevated(mesh, at, g);
   }

   public static List<RoadStructures.Part> plan(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, RoadFurniture.Phase phase) {
      if (!mesh.settings().style().ramp()
         && mesh.settings().options().lanePoints().link() == null
         && mesh.settings().structure() != RoadGeometry.Structure.TUNNEL) {
         ArrayList<RoadStructures.Part> out = new ArrayList<>();
         RoadProfile.Options o = mesh.settings().options();
         RoadStreetscape.Config c = o.streetscape();
         RoadSidewalks.Config walk = o.sidewalk();
         if (RoadProfile.catalog(mesh.settings()).type() != RoadProfile.Type.ORDINARY) {
            stripTrees(out, mesh, ground, phase);
            return List.copyOf(out);
         } else {
            if (c.lamps()) {
               for (double d = phase.first(8.0, c.lampSpacing()); d < mesh.length() - 1.0E-6; d += c.lampSpacing()) {
                  if (!(d < 1.0) && !(mesh.length() - d < 1.0) && !RoadInfrastructure.nearGantry(mesh, ground, d)) {
                     RoadGeometry.Sample at = RoadStructures.sample(mesh, d);
                     RoadProfile.Layout l = RoadProfile.layout(mesh, at);
                     boolean raised = bridge(mesh, at, ground);
                     if (!l.catalog().twoWay()) {
                        int side = l.outside();
                        raised = bridge(mesh, at, ground, side);
                        double curb = l.curbWidth() > 0.0 && !raised ? RoadProfile.curbExtent(l, at, side) : 0.0;
                        boolean sound = raised && o.outerRail().sound(side);
                        double offset = sound ? at.halfWidth() + 0.5 : at.halfWidth() - (curb > 0.0 ? curb / 2.0 : 0.28);
                        addLamp(out, mesh, ground, at, (double)side * offset, curb > 0.0 ? 0.2 : 0.0, 3, side, sound);
                     } else {
                        boolean center = raised ? o.cycle() : l.catalog().median() == RoadProfile.Median.GREEN && l.median() >= 1.4;
                        if (center && (raised || greenSeat(mesh, d, 0, 2.0))) {
                           addLamp(out, mesh, ground, at, l.medianCenter(), raised ? 0.0 : 0.3, 1, 1, false);
                        }

                        for (int side : l.outsideSides()) {
                           boolean paved = walkSide(walk, side);
                           boolean cycle = o.cycle() && l.cycleWidth() > 1.0;
                           if (bridge(mesh, at, ground, side)) {
                              int style = cycle ? (paved ? 2 : 4) : (paved ? 1 : 4);
                              addLamp(out, mesh, ground, at, (double)side * (at.halfWidth() + (paved ? 0.65 : 0.45)), paved ? 0.2 : 0.0, style, side, !paved);
                           } else if (cycle) {
                              if (!RoadsideBypass.crossing(mesh, at.distance(), side)) {
                                 if (c.separator() == RoadStreetscape.Separator.GREEN && greenSeat(mesh, d, side, 2.0)) {
                                    addLamp(out, mesh, ground, at, l.outer(side) + (double)side * 0.75, 0.3, 2, side, false);
                                 } else if (c.separator() != RoadStreetscape.Separator.GREEN) {
                                    if (c.separator() == RoadStreetscape.Separator.RAIL) {
                                       addLamp(
                                          out,
                                          mesh,
                                          ground,
                                          at,
                                          l.outer(side),
                                          0.0,
                                          c.walkLamps() && paved && walkLampAllowed(o, mesh.settings().structure(), mesh.settings().style()) ? 3 : 2,
                                          side,
                                          false
                                       );
                                    } else {
                                       addLamp(
                                          out, mesh, ground, at, (double)side * (at.halfWidth() + (paved ? 0.65 : 0.55)), paved ? 0.2 : 0.0, 1, side, false
                                       );
                                    }
                                 }
                              }
                           } else if (!center) {
                              addLamp(
                                 out, mesh, ground, at, (double)side * (at.halfWidth() + (paved ? 0.65 : 0.55)), paved ? 0.2 : 0.0, paved ? 1 : 3, side, false
                              );
                           }
                        }
                     }
                  }
               }
            }

            if (c.lamps() && c.walkLamps() && walkLampAllowed(o, mesh.settings().structure(), mesh.settings().style())) {
               for (double dx = phase.first(8.0, c.walkLampSpacing()); dx < mesh.length() - 1.0; dx += c.walkLampSpacing()) {
                  if (!(dx < 1.0) && !RoadInfrastructure.nearGantry(mesh, ground, dx)) {
                     RoadGeometry.Sample at = RoadStructures.sample(mesh, dx);

                     for (int sidex : new int[]{-1, 1}) {
                        if (walkSide(walk, sidex) && !bridge(mesh, at, ground, sidex) && !cut(mesh, at, sidex)) {
                           double offset = Math.max(1.7, (double)walk.width() / 2.0);
                           addLamp(out, mesh, ground, at, (double)sidex * (at.halfWidth() + offset), 0.2, 1, sidex, false);
                        }
                     }
                  }
               }
            }

            if (c.planting() != RoadStreetscape.Planting.NONE) {
               for (double dxx = phase.first(8.0, c.plantingSpacing()); dxx < mesh.length() - 3.0; dxx += c.plantingSpacing()) {
                  if (!(dxx < 3.0) && !RoadInfrastructure.nearGantry(mesh, ground, dxx)) {
                     RoadGeometry.Sample at = RoadStructures.sample(mesh, dxx);

                     for (int sidexx : new int[]{-1, 1}) {
                        if (walkSide(walk, sidexx) && !bridge(mesh, at, ground, sidexx) && !cut(mesh, at, sidexx)) {
                           double offset = c.planting() == RoadStreetscape.Planting.FLOWERS ? 1.1 : 1.8;
                           RoadGeometry.V foot = at.at((double)sidexx * (at.halfWidth() + offset), -0.2);
                           if (!ground.joined(foot) && !ground.furnitureClear(foot)) {
                              List<RoadStructures.Part> plants = plant(foot, c.planting(), at.left().left().mul(-1.0));
                              if (!out.stream().anyMatch(p -> p.a().y() < foot.y() + 2.0 && p.a().sub(foot).horizontalLength() < 2.3)
                                 && !plants.stream().anyMatch(p -> ground.plantBlocked(p, at))) {
                                 out.addAll(plants);
                              }
                           }
                        }
                     }
                  }
               }
            }

            stripTrees(out, mesh, ground, phase);
            return List.copyOf(out);
         }
      } else {
         return List.of();
      }
   }

   private static void stripTrees(List<RoadStructures.Part> out, RoadGeometry.Mesh mesh, RoadStructures.Ground ground, RoadFurniture.Phase phase) {
      RoadStreetscape.Config c = mesh.settings().options().streetscape();
      RoadPlantings plants = c.plantings();

      for (boolean median : new boolean[]{true, false}) {
         RoadStreetscape.Planting type = median ? plants.medianTrees() : plants.separatorTrees();
         if (type != RoadStreetscape.Planting.NONE) {
            double spacing = median ? plants.medianSpacing() : plants.separatorSpacing();

            for (double d = phase.first(8.0, spacing); d < mesh.length() - 3.0; d += spacing) {
               if (!(d < 3.0) && !underground(mesh, d) && !RoadInfrastructure.nearGantry(mesh, ground, d)) {
                  RoadGeometry.Sample at = RoadStructures.sample(mesh, d);
                  RoadProfile.Layout layout = RoadProfile.layout(mesh, at);
                  if (!median || !(medianConversionBlend(mesh, at) > 0.01)) {
                     double width = median ? layout.median() : separatorWidth(mesh.settings().options());
                     if (!(width < 1.5)
                        && (!median || layout.catalog().twoWay() && !(RoadTransitions.green(mesh, at) < 0.99))
                        && (median || c.separator() == RoadStreetscape.Separator.GREEN && !(layout.cycleWidth() < 2.5))) {
                        for (int side : median ? new int[]{0} : layout.outsideSides()) {
                           if (greenSeat(mesh, d, side, 3.5)
                              && (side == 0 || !RoadsideBypass.crossing(mesh, d, side) && !bridge(mesh, at, ground, side) && !cut(mesh, at, side))) {
                              RoadGeometry.V foot = at.at(median ? layout.medianCenter() : layout.outer(side) + (double)side * width / 2.0, -0.3);
                              if (!ground.joined(foot) && !ground.furnitureClear(foot) && !ground.lampClear(foot)) {
                                 List<RoadStructures.Part> tree = stripPlant(foot, type, at.left().left().mul(-1.0), width);
                                 if (!out.stream().anyMatch(p -> p.a().y() < foot.y() + 3.0 && p.a().sub(foot).horizontalLength() < 3.2)
                                    && !tree.stream().anyMatch(p -> ground.plantBlocked(p, at))) {
                                    out.addAll(tree);
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public static boolean hasPlanting(RoadGeometry.Mesh mesh) {
      RoadStreetscape.Config c = mesh.settings().options().streetscape();
      return c.planting() != RoadStreetscape.Planting.NONE
         || c.plantings().medianTrees() != RoadStreetscape.Planting.NONE
         || c.plantings().separatorTrees() != RoadStreetscape.Planting.NONE;
   }

   public static List<RoadStructures.Part> clearBlockedPlanting(
      List<RoadStructures.Part> parts, RoadGeometry.Mesh mesh, RoadFurniture.Phase phase, Predicate<RoadStructures.Part> blocked
   ) {
      RoadStreetscape.Config c = mesh.settings().options().streetscape();
      RoadSidewalks.Config walk = mesh.settings().options().sidewalk();
      if (!hasPlanting(mesh)) {
         return parts;
      } else {
         HashSet<RoadStructures.Part> present = new HashSet<>(parts);
         HashSet<RoadStructures.Part> remove = new HashSet<>();
         Consumer<List<RoadStructures.Part>> inspect = group -> {
            if (group.stream().anyMatch(p -> present.contains(p) && blocked.test(p))) {
               remove.addAll(group);
            }
         };
         if (c.planting() != RoadStreetscape.Planting.NONE) {
            for (double d = phase.first(8.0, c.plantingSpacing()); d < mesh.length() - 3.0; d += c.plantingSpacing()) {
               if (!(d < 3.0)) {
                  RoadGeometry.Sample at = RoadStructures.sample(mesh, d);

                  for (int side : new int[]{-1, 1}) {
                     if (walkSide(walk, side)) {
                        RoadGeometry.V foot = at.at((double)side * (at.halfWidth() + (c.planting() == RoadStreetscape.Planting.FLOWERS ? 1.1 : 1.8)), -0.2);
                        inspect.accept(plant(foot, c.planting(), at.left().left().mul(-1.0)));
                     }
                  }
               }
            }
         }

         for (boolean median : new boolean[]{true, false}) {
            RoadStreetscape.Planting type = median ? c.plantings().medianTrees() : c.plantings().separatorTrees();
            if (type != RoadStreetscape.Planting.NONE) {
               double spacing = median ? c.plantings().medianSpacing() : c.plantings().separatorSpacing();

               for (double dx = phase.first(8.0, spacing); dx < mesh.length() - 3.0; dx += spacing) {
                  if (!(dx < 3.0)) {
                     RoadGeometry.Sample at = RoadStructures.sample(mesh, dx);
                     RoadProfile.Layout layout = RoadProfile.layout(mesh, at);
                     double width = median ? layout.median() : separatorWidth(mesh.settings().options());
                     if (!(width < 1.5)) {
                        for (int sidex : median ? new int[]{0} : layout.outsideSides()) {
                           inspect.accept(
                              stripPlant(
                                 at.at(median ? layout.medianCenter() : layout.outer(sidex) + (double)sidex * width / 2.0, -0.3),
                                 type,
                                 at.left().left().mul(-1.0),
                                 width
                              )
                           );
                        }
                     }
                  }
               }
            }
         }

         return remove.isEmpty() ? parts : parts.stream().filter(p -> !remove.contains(p)).toList();
      }
   }

   public static boolean greenSeat(RoadGeometry.Mesh mesh, double distance, int side, double setback) {
      if (!(distance < setback) && !(distance > mesh.length() - setback)) {
         for (double d = distance - setback; d <= distance + setback + 1.0E-7; d += 0.5) {
            RoadGeometry.Sample at = RoadStructures.sample(mesh, d);
            RoadProfile.Layout l = RoadProfile.layout(mesh, at);
            if (underground(mesh, d) || (side == 0 ? medianConversionBlend(mesh, at) : raisedBlend(mesh, at)) > 0.01) {
               return false;
            }

            if (side == 0) {
               if (l.median() < 1.4 || RoadTransitions.green(mesh, at) < 0.99) {
                  return false;
               }
            } else if (mesh.settings().options().streetscape().separator() != RoadStreetscape.Separator.GREEN
               || l.cycleWidth() < 2.8
               || separatorWidth(mesh.settings().options()) < 0.45
               || cut(mesh, at, side)
               || RoadsideBypass.crossing(mesh, d, side)) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   public static int treeVariant(RoadGeometry.V foot, RoadStreetscape.Planting type) {
      long x = Math.round(foot.x() * 16.0);
      long z = Math.round(foot.z() * 16.0);
      long h = x * -7046029254386353131L ^ z * -4417276706812531889L ^ (long)type.ordinal() * 1609587929392839161L;
      h = (h ^ h >>> 30) * -4658895280553007687L;
      h = (h ^ h >>> 27) * -7723592293110705685L;
      return Math.floorMod(h ^ h >>> 31, 6);
   }

   public static List<RoadStructures.Part> stripPlant(RoadGeometry.V foot, RoadStreetscape.Planting type, RoadGeometry.V along, double width) {
      return !(width < 1.5) && type != RoadStreetscape.Planting.NONE && type != RoadStreetscape.Planting.FLOWERS
         ? RoadTrees.model(foot, type, along)
         : List.of();
   }

   private static void addLamp(
      List<RoadStructures.Part> out,
      RoadGeometry.Mesh mesh,
      RoadStructures.Ground g,
      RoadGeometry.Sample at,
      double lateral,
      double up,
      int style,
      int side,
      boolean bracket
   ) {
      if (!LaneDeck.outerOpening(mesh, at.distance(), side) || !(Math.abs(lateral) > at.halfWidth() - 1.0)) {
         RoadGeometry.V foot = at.at(lateral, -up);
         if (!g.joined(foot) && !g.furnitureClear(foot) && !g.lampClear(foot)) {
            ArrayList<RoadStructures.Part> parts = new ArrayList<>(lamp(foot, at.left().mul((double)(-side)), style));
            if (bracket) {
               parts.add(
                  new RoadStructures.Part(
                     at.at((double)side * (at.halfWidth() - 0.55), 0.47),
                     foot.add(new RoadGeometry.V(0.0, -0.47, 0.0)),
                     0.7,
                     0.35,
                     false,
                     RoadStructures.Material.CONCRETE
                  )
               );
               parts.add(
                  new RoadStructures.Part(
                     foot.add(new RoadGeometry.V(0.0, -0.12, 0.0)),
                     foot.add(new RoadGeometry.V(0.0, -0.12, 0.0)),
                     0.38,
                     0.12,
                     true,
                     RoadStructures.Material.CONCRETE
                  )
               );
            }

            if (parts.stream().noneMatch(p -> g.lampBlocked(p, at))) {
               out.addAll(parts);
            }
         }
      }
   }

   public static boolean lampOwnRoadBlocked(RoadStructures.Part part, RoadGeometry.Mesh mesh, RoadGeometry.Sample mount) {
      for (RoadClearance.Contact contact : RoadClearance.structureContacts(part, mesh, 4.25)) {
         if (contact.blocked() && (contact.from() < mount.distance() - 1.0 - 1.0E-7 || contact.to() > mount.distance() + 1.0 + 1.0E-7)) {
            return true;
         }
      }

      return false;
   }

   public static List<RoadStructures.Part> lamp(RoadGeometry.V foot, RoadGeometry.V inward, int style) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      double h = style == 4 ? 6.0 : 7.5;
      out.add(new RoadStructures.Part(foot, foot, 0.38, 0.16, true, RoadStructures.Material.CONCRETE));
      out.add(
         new RoadStructures.Part(
            foot.add(new RoadGeometry.V(0.0, 0.16, 0.0)), foot.add(new RoadGeometry.V(0.0, 0.16, 0.0)), 0.22, 1.8, true, RoadStructures.Material.LAMP_BLUE
         )
      );
      out.add(
         new RoadStructures.Part(
            foot.add(new RoadGeometry.V(0.0, 1.96, 0.0)), foot.add(new RoadGeometry.V(0.0, 1.96, 0.0)), 0.14, h - 1.96, true, RoadStructures.Material.STEEL
         )
      );
      arm(out, foot, inward, h, style == 4 ? 1.45 : 2.5, style == 4 ? 0.15 : 0.65);
      if (style == 1 || style == 2) {
         arm(out, foot, inward.mul(-1.0), style == 2 ? h - 1.7 : h, style == 2 ? 1.7 : 2.5, 0.65);
      }

      if (style == 1) {
         out.add(
            new RoadStructures.Part(
               foot.add(new RoadGeometry.V(0.0, h, 0.0)), foot.add(new RoadGeometry.V(0.0, h, 0.0)), 0.09, 0.65, true, RoadStructures.Material.STEEL
            )
         );
      }

      if (style == 3) {
         RoadGeometry.V a = foot.add(new RoadGeometry.V(0.0, h - 1.4, 0.0));
         RoadGeometry.V b = foot.add(new RoadGeometry.V(0.0, h - 0.2, 0.0));
         RoadGeometry.V last = a;

         for (int i = 1; i <= 8; i++) {
            double t = (double)i / 8.0;
            RoadGeometry.V next = a.add(b.sub(a).mul(t)).add(inward.mul(-0.42 * Math.sin(Math.PI * t)));
            out.add(new RoadStructures.Part(last, next, 0.07, 0.07, false, RoadStructures.Material.STEEL));
            last = next;
         }
      }

      String anchor = "sr:lamp/" + Double.toHexString(foot.x()) + "/" + Double.toHexString(foot.y()) + "/" + Double.toHexString(foot.z());
      return out.stream()
         .map(p -> new RoadStructures.Part(p.a(), p.b(), p.width(), p.height(), p.pier(), p.material(), p.frameA(), p.frameB(), anchor, p.heights()))
         .toList();
   }

   public static RoadGeometry.V lampFoot(RoadStructures.Part part) {
      if (!part.model().startsWith("sr:lamp/")) {
         return null;
      } else {
         String[] values = part.model().substring(8).split("/");
         if (values.length != 3) {
            return null;
         } else {
            try {
               return new RoadGeometry.V(Double.parseDouble(values[0]), Double.parseDouble(values[1]), Double.parseDouble(values[2]));
            } catch (NumberFormatException var3) {
               return null;
            }
         }
      }
   }

   private static void arm(List<RoadStructures.Part> out, RoadGeometry.V foot, RoadGeometry.V direction, double h, double length, double rise) {
      RoadGeometry.V root = foot.add(new RoadGeometry.V(0.0, h - 0.2, 0.0));
      RoadGeometry.V last = root;

      for (int i = 1; i <= 8; i++) {
         double t = (double)i / 8.0;
         RoadGeometry.V p = root.add(direction.mul(length * t)).add(new RoadGeometry.V(0.0, rise * (2.0 * t - t * t), 0.0));
         out.add(new RoadStructures.Part(last, p, 0.1, 0.1, false, RoadStructures.Material.STEEL));
         last = p;
      }

      out.add(new RoadStructures.Part(last.sub(direction.mul(0.3)), last.add(direction.mul(0.55)), 0.36, 0.16, false, RoadStructures.Material.LAMP_BLUE));
      out.add(
         new RoadStructures.Part(
            last.sub(direction.mul(0.24)).add(new RoadGeometry.V(0.0, -0.035, 0.0)),
            last.add(direction.mul(0.45)).add(new RoadGeometry.V(0.0, -0.035, 0.0)),
            0.28,
            0.05,
            false,
            RoadStructures.Material.LAMP
         )
      );
   }

   public static List<RoadStructures.Part> plant(RoadGeometry.V foot, RoadStreetscape.Planting type, RoadGeometry.V along) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      if (type == RoadStreetscape.Planting.NONE) {
         return List.of();
      } else {
         along = along.horizontalUnit();
         RoadGeometry.V side = along.left();
         if (type == RoadStreetscape.Planting.FLOWERS) {
            box(out, foot.add(new RoadGeometry.V(0.0, 0.025, 0.0)), along, 3.5, 1.65, 0.16, RoadStructures.Material.SOIL);

            for (int sign : new int[]{-1, 1}) {
               box(out, foot.add(side.mul((double)sign * 0.84)), along, 3.8, 0.16, 0.32, RoadStructures.Material.CONCRETE);
               box(out, foot.add(along.mul((double)sign * 1.84)), side, 1.65, 0.16, 0.32, RoadStructures.Material.CONCRETE);
            }

            for (int row = -1; row <= 1; row++) {
               for (int i = -3; i <= 3; i++) {
                  double h = 0.3 + 0.05 * Math.sin((double)i * 2.3 + (double)row);
                  RoadGeometry.V at = foot.add(along.mul((double)i * 0.48 + (double)row * 0.05))
                     .add(side.mul((double)row * 0.45))
                     .add(new RoadGeometry.V(0.0, 0.16, 0.0));
                  box(out, at, along, 0.43, 0.36, h, RoadStructures.Material.OAK_LEAVES);
                  RoadGeometry.V bloom = at.add(new RoadGeometry.V(0.0, h + 0.02, 0.0));
                  RoadStructures.Material petal = (i - row) % 3 == 0
                     ? RoadStructures.Material.FLOWER_WHITE
                     : ((i + row) % 2 == 0 ? RoadStructures.Material.FLOWER_PINK : RoadStructures.Material.FLOWER_YELLOW);
                  box(out, bloom, along, 0.28, 0.11, 0.055, petal);
                  box(out, bloom, side, 0.28, 0.11, 0.055, petal);
                  box(out, bloom.add(new RoadGeometry.V(0.0, 0.045, 0.0)), along, 0.07, 0.07, 0.04, RoadStructures.Material.FLOWER_YELLOW);
               }
            }

            return List.copyOf(out);
         } else {
            box(out, foot.add(new RoadGeometry.V(0.0, 0.008, 0.0)), along, 2.25, 2.25, 0.035, RoadStructures.Material.SOIL);

            for (int sign : new int[]{-1, 1}) {
               box(out, foot.add(side.mul((double)sign * 1.16)), along, 2.48, 0.16, 0.12, RoadStructures.Material.CONCRETE);
               box(out, foot.add(along.mul((double)sign * 1.16)), side, 2.2, 0.16, 0.12, RoadStructures.Material.CONCRETE);
            }

            for (int i = -5; i <= 5; i++) {
               double offset = (double)i * 0.19;
               double gap = Math.abs(offset) < 0.37 ? 0.42 : 0.0;

               for (int sign : new int[]{-1, 1}) {
                  double length = 1.08 - gap;
                  if (!(length <= 0.0)) {
                     RoadGeometry.V a = foot.add(side.mul(offset)).add(along.mul((double)sign * (gap + length / 2.0))).add(new RoadGeometry.V(0.0, 0.055, 0.0));
                     box(out, a, along, length, 0.035, 0.035, RoadStructures.Material.DARK_STEEL);
                  }
               }
            }

            out.addAll(RoadTrees.model(foot, type, along));
            return List.copyOf(out);
         }
      }
   }

   private static void box(
      List<RoadStructures.Part> out, RoadGeometry.V center, RoadGeometry.V along, double length, double width, double height, RoadStructures.Material material
   ) {
      out.add(new RoadStructures.Part(center.sub(along.mul(length / 2.0)), center.add(along.mul(length / 2.0)), width, height, false, material));
   }

   private RoadStreetscape() {
   }

   public static enum Apron {
      ROAD("道路方块"),
      TERRAIN("相邻地形"),
      NONE("不填充");

      public final String label;

      private Apron(String label) {
         this.label = label;
      }
   }

   public static record Config(
      RoadStreetscape.Separator separator,
      boolean parking,
      double lampSpacing,
      boolean walkLamps,
      double walkLampSpacing,
      RoadStreetscape.Planting planting,
      double plantingSpacing,
      List<RoadStreetscape.Span> raisedSpans,
      List<RoadsideBypass.Span> crossings,
      RoadStreetscape.Apron apron,
      List<RoadStreetscape.Span> tunnels,
      RoadPlantings plantings,
      RoadStreetscape.SideStructures sides,
      boolean lamps
   ) {
      public static final RoadStreetscape.Config DEFAULT = new RoadStreetscape.Config(
         RoadStreetscape.Separator.LINE, false, 24.0, false, 24.0, RoadStreetscape.Planting.NONE, 16.0
      );

      public Config(
         RoadStreetscape.Separator separator,
         boolean parking,
         double lampSpacing,
         boolean walkLamps,
         double walkLampSpacing,
         RoadStreetscape.Planting planting,
         double plantingSpacing,
         List<RoadStreetscape.Span> raisedSpans,
         List<RoadsideBypass.Span> crossings,
         RoadStreetscape.Apron apron,
         List<RoadStreetscape.Span> tunnels,
         RoadPlantings plantings,
         RoadStreetscape.SideStructures sides
      ) {
         this(
            separator,
            parking,
            lampSpacing,
            walkLamps,
            walkLampSpacing,
            planting,
            plantingSpacing,
            raisedSpans,
            crossings,
            apron,
            tunnels,
            plantings,
            sides,
            true
         );
      }

      public Config(
         RoadStreetscape.Separator separator,
         boolean parking,
         double lampSpacing,
         boolean walkLamps,
         double walkLampSpacing,
         RoadStreetscape.Planting planting,
         double plantingSpacing,
         List<RoadStreetscape.Span> raisedSpans,
         List<RoadsideBypass.Span> crossings,
         RoadStreetscape.Apron apron,
         List<RoadStreetscape.Span> tunnels,
         RoadPlantings plantings
      ) {
         this(
            separator,
            parking,
            lampSpacing,
            walkLamps,
            walkLampSpacing,
            planting,
            plantingSpacing,
            raisedSpans,
            crossings,
            apron,
            tunnels,
            plantings,
            new RoadStreetscape.SideStructures(raisedSpans, raisedSpans, List.of(), List.of())
         );
      }

      public Config(
         RoadStreetscape.Separator separator,
         boolean parking,
         double lampSpacing,
         boolean walkLamps,
         double walkLampSpacing,
         RoadStreetscape.Planting planting,
         double plantingSpacing
      ) {
         this(separator, parking, lampSpacing, walkLamps, walkLampSpacing, planting, plantingSpacing, List.of());
      }

      public Config(
         RoadStreetscape.Separator separator,
         boolean parking,
         double lampSpacing,
         boolean walkLamps,
         double walkLampSpacing,
         RoadStreetscape.Planting planting,
         double plantingSpacing,
         List<RoadStreetscape.Span> raisedSpans
      ) {
         this(separator, parking, lampSpacing, walkLamps, walkLampSpacing, planting, plantingSpacing, raisedSpans, List.of());
      }

      public Config(
         RoadStreetscape.Separator separator,
         boolean parking,
         double lampSpacing,
         boolean walkLamps,
         double walkLampSpacing,
         RoadStreetscape.Planting planting,
         double plantingSpacing,
         List<RoadStreetscape.Span> raisedSpans,
         List<RoadsideBypass.Span> crossings
      ) {
         this(
            separator,
            parking,
            lampSpacing,
            walkLamps,
            walkLampSpacing,
            planting,
            plantingSpacing,
            raisedSpans,
            crossings,
            RoadStreetscape.Apron.ROAD,
            List.of()
         );
      }

      public Config(
         RoadStreetscape.Separator separator,
         boolean parking,
         double lampSpacing,
         boolean walkLamps,
         double walkLampSpacing,
         RoadStreetscape.Planting planting,
         double plantingSpacing,
         List<RoadStreetscape.Span> raisedSpans,
         List<RoadsideBypass.Span> crossings,
         RoadStreetscape.Apron apron,
         List<RoadStreetscape.Span> tunnels
      ) {
         this(
            separator,
            parking,
            lampSpacing,
            walkLamps,
            walkLampSpacing,
            planting,
            plantingSpacing,
            raisedSpans,
            crossings,
            apron,
            tunnels,
            RoadPlantings.DEFAULT
         );
      }

      public Config(
         RoadStreetscape.Separator separator,
         boolean parking,
         double lampSpacing,
         boolean walkLamps,
         double walkLampSpacing,
         RoadStreetscape.Planting planting,
         double plantingSpacing,
         List<RoadStreetscape.Span> raisedSpans,
         List<RoadsideBypass.Span> crossings,
         RoadStreetscape.Apron apron,
         List<RoadStreetscape.Span> tunnels,
         RoadPlantings plantings,
         RoadStreetscape.SideStructures sides,
         boolean lamps
      ) {
         Objects.requireNonNull(sides);
         Objects.requireNonNull(plantings);
         Objects.requireNonNull(apron);
         tunnels = List.copyOf(tunnels);
         if (tunnels.size() > 8192) {
            throw new IllegalArgumentException("隧道分段过多");
         } else if (separator != null
            && planting != null
            && RoadGeometry.finite(lampSpacing, walkLampSpacing, plantingSpacing)
            && !(lampSpacing < 8.0)
            && !(lampSpacing > 96.0)
            && !(walkLampSpacing < 8.0)
            && !(walkLampSpacing > 96.0)
            && !(plantingSpacing < 8.0)
            && !(plantingSpacing > 64.0)) {
            if (parking) {
               separator = RoadStreetscape.Separator.LINE;
            }

            crossings = List.copyOf(crossings);
            if (crossings.size() > 8192) {
               throw new IllegalArgumentException("非机动车道立体通行分段过多");
            } else {
               raisedSpans = List.copyOf(raisedSpans);
               if (raisedSpans.size() > 8192) {
                  throw new IllegalArgumentException("高架分段过多");
               } else {
                  this.separator = separator;
                  this.parking = parking;
                  this.lampSpacing = lampSpacing;
                  this.walkLamps = walkLamps;
                  this.walkLampSpacing = walkLampSpacing;
                  this.planting = planting;
                  this.plantingSpacing = plantingSpacing;
                  this.raisedSpans = raisedSpans;
                  this.crossings = crossings;
                  this.apron = apron;
                  this.tunnels = tunnels;
                  this.plantings = plantings;
                  this.sides = sides;
                  this.lamps = lamps;
               }
            }
         } else {
            throw new IllegalArgumentException("路灯间距 8–96 格，绿化间距 8–64 格");
         }
      }

      public RoadStreetscape.Config sideStructures(RoadStreetscape.SideStructures value) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            this.lampSpacing,
            this.walkLamps,
            this.walkLampSpacing,
            this.planting,
            this.plantingSpacing,
            RoadStreetscape.union(value.negativeRaised(), value.positiveRaised()),
            this.crossings,
            this.apron,
            this.tunnels,
            this.plantings,
            value,
            this.lamps
         );
      }

      public RoadStreetscape.Config apron(RoadStreetscape.Apron value) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            this.lampSpacing,
            this.walkLamps,
            this.walkLampSpacing,
            this.planting,
            this.plantingSpacing,
            this.raisedSpans,
            this.crossings,
            value,
            this.tunnels,
            this.plantings,
            this.sides,
            this.lamps
         );
      }

      public RoadStreetscape.Config tunnels(List<RoadStreetscape.Span> value) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            this.lampSpacing,
            this.walkLamps,
            this.walkLampSpacing,
            this.planting,
            this.plantingSpacing,
            this.raisedSpans,
            this.crossings,
            this.apron,
            value,
            this.plantings,
            this.sides,
            this.lamps
         );
      }

      public RoadStreetscape.Config raisedSpans(List<RoadStreetscape.Span> v) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            this.lampSpacing,
            this.walkLamps,
            this.walkLampSpacing,
            this.planting,
            this.plantingSpacing,
            v,
            this.crossings,
            this.apron,
            this.tunnels,
            this.plantings,
            new RoadStreetscape.SideStructures(v, v, List.of(), List.of()),
            this.lamps
         );
      }

      public RoadStreetscape.Config crossings(List<RoadsideBypass.Span> v) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            this.lampSpacing,
            this.walkLamps,
            this.walkLampSpacing,
            this.planting,
            this.plantingSpacing,
            this.raisedSpans,
            v,
            this.apron,
            this.tunnels,
            this.plantings,
            this.sides,
            this.lamps
         );
      }

      public RoadStreetscape.Config plantings(RoadPlantings v) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            this.lampSpacing,
            this.walkLamps,
            this.walkLampSpacing,
            this.planting,
            this.plantingSpacing,
            this.raisedSpans,
            this.crossings,
            this.apron,
            this.tunnels,
            v,
            this.sides,
            this.lamps
         );
      }

      public RoadStreetscape.Config lamps(boolean value) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            this.lampSpacing,
            this.walkLamps,
            this.walkLampSpacing,
            this.planting,
            this.plantingSpacing,
            this.raisedSpans,
            this.crossings,
            this.apron,
            this.tunnels,
            this.plantings,
            this.sides,
            value
         );
      }

      public RoadStreetscape.Config separator(RoadStreetscape.Separator v) {
         return new RoadStreetscape.Config(
            v,
            this.parking,
            this.lampSpacing,
            this.walkLamps,
            this.walkLampSpacing,
            this.planting,
            this.plantingSpacing,
            this.raisedSpans,
            this.crossings,
            this.apron,
            this.tunnels,
            this.plantings,
            this.sides,
            this.lamps
         );
      }

      public RoadStreetscape.Config parking(boolean v) {
         return new RoadStreetscape.Config(
            this.separator,
            v,
            this.lampSpacing,
            this.walkLamps,
            this.walkLampSpacing,
            this.planting,
            this.plantingSpacing,
            this.raisedSpans,
            this.crossings,
            this.apron,
            this.tunnels,
            this.plantings,
            this.sides,
            this.lamps
         );
      }

      public RoadStreetscape.Config lampSpacing(double v) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            v,
            this.walkLamps,
            this.walkLampSpacing,
            this.planting,
            this.plantingSpacing,
            this.raisedSpans,
            this.crossings,
            this.apron,
            this.tunnels,
            this.plantings,
            this.sides,
            this.lamps
         );
      }

      public RoadStreetscape.Config walkLamps(boolean v) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            this.lampSpacing,
            v,
            this.walkLampSpacing,
            this.planting,
            this.plantingSpacing,
            this.raisedSpans,
            this.crossings,
            this.apron,
            this.tunnels,
            this.plantings,
            this.sides,
            this.lamps
         );
      }

      public RoadStreetscape.Config walkLampSpacing(double v) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            this.lampSpacing,
            this.walkLamps,
            v,
            this.planting,
            this.plantingSpacing,
            this.raisedSpans,
            this.crossings,
            this.apron,
            this.tunnels,
            this.plantings,
            this.sides,
            this.lamps
         );
      }

      public RoadStreetscape.Config planting(RoadStreetscape.Planting v) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            this.lampSpacing,
            this.walkLamps,
            this.walkLampSpacing,
            v,
            this.plantingSpacing,
            this.raisedSpans,
            this.crossings,
            this.apron,
            this.tunnels,
            this.plantings,
            this.sides,
            this.lamps
         );
      }

      public RoadStreetscape.Config plantingSpacing(double v) {
         return new RoadStreetscape.Config(
            this.separator,
            this.parking,
            this.lampSpacing,
            this.walkLamps,
            this.walkLampSpacing,
            this.planting,
            v,
            this.raisedSpans,
            this.crossings,
            this.apron,
            this.tunnels,
            this.plantings,
            this.sides,
            this.lamps
         );
      }
   }

   public static enum Planting {
      NONE("关闭"),
      OAK("橡木"),
      BIRCH("白桦"),
      SPRUCE("云杉"),
      CHERRY("樱花树"),
      FLOWERS("花坛");

      public final String label;

      private Planting(String label) {
         this.label = label;
      }
   }

   public static enum Separator {
      LINE("白实线"),
      RAIL("护栏"),
      GREEN("绿化带");

      public final String label;

      private Separator(String label) {
         this.label = label;
      }
   }

   public static record SideStructures(
      List<RoadStreetscape.Span> negativeRaised,
      List<RoadStreetscape.Span> positiveRaised,
      List<RoadStreetscape.Span> negativeCut,
      List<RoadStreetscape.Span> positiveCut
   ) {
      public SideStructures(
         List<RoadStreetscape.Span> negativeRaised,
         List<RoadStreetscape.Span> positiveRaised,
         List<RoadStreetscape.Span> negativeCut,
         List<RoadStreetscape.Span> positiveCut
      ) {
         negativeRaised = List.copyOf(negativeRaised);
         positiveRaised = List.copyOf(positiveRaised);
         negativeCut = List.copyOf(negativeCut);
         positiveCut = List.copyOf(positiveCut);
         if (negativeRaised.size() + positiveRaised.size() + negativeCut.size() + positiveCut.size() > 32768) {
            throw new IllegalArgumentException("左右结构分段过多");
         } else {
            this.negativeRaised = negativeRaised;
            this.positiveRaised = positiveRaised;
            this.negativeCut = negativeCut;
            this.positiveCut = positiveCut;
         }
      }

      public List<RoadStreetscape.Span> raised(int side) {
         return side < 0 ? this.negativeRaised : this.positiveRaised;
      }

      public List<RoadStreetscape.Span> cut(int side) {
         return side < 0 ? this.negativeCut : this.positiveCut;
      }

      public RoadStreetscape.SideStructures slice(double from, double to) {
         return new RoadStreetscape.SideStructures(
            trim(this.negativeRaised, from, to), trim(this.positiveRaised, from, to), trim(this.negativeCut, from, to), trim(this.positiveCut, from, to)
         );
      }

      private static List<RoadStreetscape.Span> trim(List<RoadStreetscape.Span> spans, double from, double to) {
         return spans.stream()
            .filter(s -> s.to() > from && s.from() < to)
            .map(s -> new RoadStreetscape.Span(Math.max(from, s.from()) - from, Math.min(to, s.to()) - from))
            .toList();
      }
   }

   public static record Span(double from, double to) {
      public Span(double from, double to) {
         if (RoadGeometry.finite(from, to) && !(from < 0.0) && !(to <= from)) {
            this.from = from;
            this.to = to;
         } else {
            throw new IllegalArgumentException("无效的高架范围");
         }
      }
   }
}
