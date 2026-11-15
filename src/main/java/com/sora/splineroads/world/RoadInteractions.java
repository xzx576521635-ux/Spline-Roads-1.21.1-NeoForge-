package com.sora.splineroads.world;

import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.MultiLevelBridges;
import com.sora.splineroads.core.RailwayCrossings;
import com.sora.splineroads.core.RoadAutoTunnels;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadShellOpening;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.core.RoadStructureClearance;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadTunnelNose;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.core.BlockPos;

final class RoadInteractions {
   static boolean connected(RoadRecord a, RoadRecord b) {
      return a.a().equals(b.a()) || a.a().equals(b.b()) || a.b().equals(b.a()) || a.b().equals(b.b());
   }

   static boolean deferredLaneContact(RoadRecord host, RoadRecord ramp) {
      LanePoints.Link link = LaneTopology.metadata(ramp).link();
      return link == null
         ? false
         : host.id().equals(link.from().road())
            || host.id().equals(link.to().road())
            || host.assembly() != null && host.assembly().equals(link.to().junction())
            || LaneTopology.metadata(host).cuts().stream().anyMatch(c -> c.connection().equals(ramp.id()))
            || LaneTopology.metadata(host).additions().stream().anyMatch(a -> a.connection().equals(ramp.id()));
   }

   static boolean influences(RoadIndex.Built a, RoadIndex.Built b) {
      if (!a.record.legacyRailway() && !b.record.legacyRailway()) {
         if (connected(a.record, b.record)
            || RailwayCrossings.any(a.mesh, b.mesh)
            || !RailwayCrossings.adjustableContacts(a.record.withLevelCrossings(List.of()).mesh(), b.record.withLevelCrossings(List.of()).mesh()).isEmpty()) {
            return true;
         } else if (!MultiLevelBridges.compatible(a.mesh, b.mesh) && !MultiLevelBridges.compatible(b.mesh, a.mesh)) {
            double walkway = Math.max(walkExtent(a.mesh), walkExtent(b.mesh));
            if (!RoadIndex.overlapXZ(a.mesh, b.mesh, 3.0 + walkway)) {
               return false;
            } else if (LaneTopology.metadata(b.record).link() != null) {
               if (deferredLaneContact(a.record, b.record)) {
                  return true;
               } else {
                  LanePoints.Link link = LaneTopology.metadata(a.record).link();
                  LanePoints.Link oldLink = LaneTopology.metadata(b.record).link();
                  boolean source = link != null && (b.record.id().equals(link.from().road()) || link.from().equals(oldLink.from()));
                  boolean target = link != null && (b.record.id().equals(link.to().road()) || link.to().equals(oldLink.to()));
                  Map<UUID, RoadRecord> host = Map.of(b.record.id(), b.record);
                  Set<UUID> ids = Set.of(b.record.id());
                  double begin = source ? a.mesh.samples().get(LaneRamps.contactEnd(a.mesh, host, ids, true)).distance() : -1.0;
                  double end = target ? a.mesh.samples().get(LaneRamps.contactEnd(a.mesh, host, ids, false)).distance() : a.mesh.length() + 1.0;

                  for (RoadStructures.Part part : b.record.structures()) {
                     for (RoadClearance.Contact contact : RoadClearance.structureContacts(part, a.mesh, 4.25)) {
                        if (contact.blocked() && (!source || !(contact.to() <= begin + 0.01)) && (!target || !(contact.from() >= end - 0.01))) {
                           throw new IllegalArgumentException(
                              String.format(
                                 Locale.ROOT,
                                 "新道路与已有匝道设施冲突；冲突道路 %s，构件 %s，坐标 %.2f %.2f %.2f；已保留原匝道",
                                 b.record.id(),
                                 part.material(),
                                 part.a().x(),
                                 part.a().y(),
                                 part.a().z()
                              )
                           );
                        }
                     }
                  }

                  return source || target;
               }
            } else if (deferredLaneContact(b.record, a.record)) {
               return true;
            } else if (!a.mesh.settings().style().ramp() && LaneTopology.metadata(a.record).link() == null
               || RoadProfile.catalog(b.mesh.settings().style()).type() != RoadProfile.Type.ORDINARY
               || !b.mesh.settings().options().cycle() && !b.mesh.settings().options().sidewalk().enabled()) {
               for (RoadGeometry.Sample s : b.mesh.samples()) {
                  RoadQueries.Projection q = RoadQueries.horizontal(a.mesh, s.center());
                  double dy = s.center().y() - q.sample().center().y();
                  if (dy > 3.0 && dy < 96.0 && s.center().sub(q.sample().center()).horizontalLength() < s.halfWidth() + q.sample().halfWidth() + 1.0 + walkway) {
                     return true;
                  }

                  if (dy < -0.25
                     && dy > -(RoadStreetscape.hasPlanting(b.mesh) ? 96.0 : 10.0)
                     && s.center().sub(q.sample().center()).horizontalLength() < s.halfWidth() + q.sample().halfWidth() + 3.0 + walkway) {
                     return true;
                  }
               }

               return false;
            } else {
               return true;
            }
         } else {
            return true;
         }
      } else {
         return false;
      }
   }

   private static double walkExtent(RoadGeometry.Mesh mesh) {
      RoadSidewalks.Config walk = mesh.settings().options().sidewalk();
      return walk.enabled()
            && walk.smooth()
            && mesh.settings().structure() != RoadGeometry.Structure.TUNNEL
            && RoadProfile.catalog(mesh.settings().style()).type() == RoadProfile.Type.ORDINARY
         ? (double)walk.width()
         : 0.0;
   }

   private static boolean near(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      for (RoadGeometry.Sample s : a.samples()) {
         RoadQueries.Projection p = RoadQueries.horizontal(b, s.center());
         double dx = s.center().sub(p.sample().center()).horizontalLength();
         if (dx < s.halfWidth() + p.sample().halfWidth() + 3.0 && Math.abs(s.center().y() - p.sample().center().y()) < 96.0) {
            return true;
         }
      }

      return false;
   }

   static boolean selfSupportBlocked(RoadStructures.Part part, RoadGeometry.Mesh mesh) {
      return RoadStructureClearance.selfSupportBlocked(part, mesh);
   }

   static boolean invades(RoadStructures.Part p, RoadGeometry.Mesh m) {
      return RoadClearance.structureInvades(p, m, RoadStructureClearance.headroom(m));
   }

   static DoubleUnaryOperator tunnelHeadroom(final RoadIndex.Built ramp, final List<RoadIndex.Built> context) {
      return new DoubleUnaryOperator() {
         private DoubleUnaryOperator resolved;

         @Override
         public double applyAsDouble(double d) {
            if (this.resolved == null) {
               this.resolved = RoadInteractions.matchedHeadroom(ramp, context);
            }

            return this.resolved.applyAsDouble(d);
         }
      };
   }

   private static DoubleUnaryOperator matchedHeadroom(RoadIndex.Built ramp, List<RoadIndex.Built> context) {
      LinkedHashMap<UUID, RoadRecord> all = new LinkedHashMap<>();

      record Parent(RoadIndex.Built road, double from, double to, DoubleUnaryOperator roof) {
      }

      context.forEach(r -> all.put(r.record.id(), r.record));
      all.put(ramp.record.id(), ramp.record);
      LanePoints.Link link = LaneTopology.metadata(ramp.record).link();
      Set<UUID> source = link == null ? generatedHosts(ramp, context, true) : LaneRamps.contactRoads(all, link.from());
      Set<UUID> target = link == null ? generatedHosts(ramp, context, false) : LaneRamps.contactRoads(all, link.to());
      ArrayList<Parent> parents = new ArrayList<>();

      for (RoadIndex.Built host : context) {
         if (source.contains(host.record.id()) || target.contains(host.record.id())) {
            double first = source.contains(host.record.id())
               ? 0.0
               : ramp.mesh.samples().get(LaneRamps.contactEnd(ramp.mesh, all, Set.of(host.record.id()), false)).distance();
            double last = target.contains(host.record.id())
               ? ramp.mesh.length()
               : ramp.mesh.samples().get(LaneRamps.contactEnd(ramp.mesh, all, Set.of(host.record.id()), true)).distance();
            parents.add(new Parent(host, first, last, noseCeiling(host)));
         }
      }

      return d -> {
         RoadGeometry.Sample at = RoadStructures.sample(ramp.mesh, d);
         double height = ramp.mesh.settings().structure() == RoadGeometry.Structure.TUNNEL
            ? RoadInfrastructure.ceiling(ramp.mesh.settings().options().infrastructure(), at.halfWidth(), 0.0)
            : Math.max(6.0, ramp.mesh.settings().options().infrastructure().headroom());

         for (Parent p : parents) {
            double outside = Math.max(p.from() - d, d - p.to());
            if (!(outside >= 16.0)) {
               RoadQueries.Projection q = RoadQueries.horizontal(p.road().mesh, at.center());
               double roof = p.roof().applyAsDouble(q.sample().distance());
               if (!(roof < 6.0)) {
                  double difference = q.sample().center().y() + roof - at.center().y() - height;
                  height += Math.max(0.0, difference) * (1.0 - RoadGeometry.Settings.smooth(Math.max(0.0, outside) / 16.0));
               }
            }
         }

         return height;
      };
   }

   private static Set<UUID> generatedHosts(RoadIndex.Built ramp, List<RoadIndex.Built> context, boolean first) {
      if (ramp.record.assembly() != null && ramp.mesh.settings().style().ramp()) {
         HashSet<UUID> result = new HashSet<>();
         RoadGeometry.Sample end = first ? ramp.mesh.first() : ramp.mesh.last();

         for (RoadIndex.Built host : context) {
            if (!host.record.id().equals(ramp.record.id()) && (!host.mesh.settings().style().ramp() || ramp.record.assembly().equals(host.record.assembly()))) {
               RoadQueries.Projection q = RoadQueries.horizontal(host.mesh, end.center());
               if (Math.abs(end.center().y() - q.sample().center().y()) < 0.025
                  && Math.abs(end.left().dot(q.sample().left())) > 0.94
                  && q.horizontalDistance() < end.halfWidth() + q.sample().halfWidth() + 3.1) {
                  result.add(host.record.id());
               }
            }
         }

         return Set.copyOf(result);
      } else {
         return Set.of();
      }
   }

   private static DoubleUnaryOperator noseCeiling(RoadIndex.Built road) {
      return noseCeiling(road, road.record.structures());
   }

   private static DoubleUnaryOperator noseCeiling(RoadIndex.Built road, List<RoadStructures.Part> parts) {
      if (road.mesh.settings().structure() == RoadGeometry.Structure.TUNNEL) {
         return d -> RoadInfrastructure.ceiling(road.mesh.settings().options().infrastructure(), RoadStructures.sample(road.mesh, d).halfWidth(), 0.0);
      } else {
         List<RoadAutoTunnels.RoofRegion> roofs = RoadAutoTunnels.roofRegions(parts);
         DoubleUnaryOperator walls = RoadAutoTunnels.wallCeiling(road.mesh, parts);
         return d -> {
            for (RoadAutoTunnels.RoofRegion r : roofs) {
               if (d >= r.from() && d <= r.to()) {
                  return r.first() + (r.last() - r.first()) * (d - r.from()) / (r.to() - r.from());
               }
            }

            return walls.applyAsDouble(d);
         };
      }
   }

   static List<RoadStructures.Part> openPortal(RoadIndex.Built tube, List<RoadStructures.Part> parts, List<RoadIndex.Built> roads) {
      return openPortal(tube, parts, roads, roads);
   }

   static List<RoadStructures.Part> openPortal(
      RoadIndex.Built tube, List<RoadStructures.Part> parts, List<RoadIndex.Built> roads, List<RoadIndex.Built> context
   ) {
      boolean explicit = tube.record.settings().structure() == RoadGeometry.Structure.TUNNEL;

      record Mouth(RoadIndex.Built ramp, boolean source, boolean target, double from, double to) {
      }

      if (!explicit && parts.stream().noneMatch(px -> px.material() == RoadStructures.Material.TUNNEL && !px.model().startsWith("sr:cycle_"))) {
         return parts;
      } else {
         LanePoints.Link link = LaneTopology.metadata(tube.record).link();
         LinkedHashMap<UUID, RoadRecord> all = new LinkedHashMap<>();
         context.forEach(r -> all.put(r.record.id(), r.record));
         all.put(tube.record.id(), tube.record);
         Set<UUID> source = link == null ? generatedHosts(tube, roads, true) : LaneRamps.contactRoads(all, link.from());
         Set<UUID> target = link == null ? generatedHosts(tube, roads, false) : LaneRamps.contactRoads(all, link.to());
         boolean linked = link != null || !source.isEmpty() || !target.isEmpty();
         double liningWidth = 1.55;
         double parentApron = context.stream()
            .filter(r -> source.contains(r.record.id()) || target.contains(r.record.id()))
            .mapToDouble(
               r -> r.mesh.settings().structure() == RoadGeometry.Structure.TUNNEL
                     ? 1.55
                     : (RoadAutoTunnels.regions(r.record.structures()).isEmpty() ? 0.0 : 1.55)
            )
            .max()
            .orElse(0.0);
         RoadGeometry.Mesh lining = !linked
            ? tube.mesh
            : RoadRibbon.mesh(
               tube.mesh
                  .samples()
                  .stream()
                  .map(s -> new RoadGeometry.Sample(s.center(), s.left(), s.distance(), s.halfWidth() + liningWidth + parentApron))
                  .toList(),
               tube.mesh.settings()
            );
         double begin = !linked ? 0.0 : lining.samples().get(LaneRamps.contactEnd(lining, all, source, true)).distance();
         double finish = !linked ? tube.mesh.length() : lining.samples().get(LaneRamps.contactEnd(lining, all, target, false)).distance();
         ArrayList<Mouth> mouths = new ArrayList<>();

         for (RoadIndex.Built r : roads) {
            LanePoints.Link branch = LaneTopology.metadata(r.record).link();
            Set<UUID> sources = branch == null ? generatedHosts(r, context, true) : LaneRamps.contactRoads(all, branch.from());
            Set<UUID> targets = branch == null ? generatedHosts(r, context, false) : LaneRamps.contactRoads(all, branch.to());
            boolean atStart = sources.contains(tube.record.id());
            boolean atEnd = targets.contains(tube.record.id());
            if (atStart || atEnd) {
               RoadGeometry.Mesh rampLining = RoadRibbon.mesh(
                  r.mesh.samples().stream().map(s -> new RoadGeometry.Sample(s.center(), s.left(), s.distance(), s.halfWidth() + liningWidth)).toList(),
                  r.mesh.settings()
               );
               double from = atStart ? rampLining.samples().get(LaneRamps.contactEnd(rampLining, all, sources, true)).distance() : -1.0;
               double to = atEnd ? rampLining.samples().get(LaneRamps.contactEnd(rampLining, all, targets, false)).distance() : r.mesh.length() + 1.0;
               mouths.add(new Mouth(r, atStart, atEnd, from, to));
            }
         }

         for (Mouth mouth : mouths) {
            if (mouth.source()) {
               parts = RoadTunnelNose.apply(
                  parts, RoadTunnelNose.build(tube.mesh, mouth.ramp().mesh, 0.0, mouth.from(), noseCeiling(tube, parts), tunnelHeadroom(mouth.ramp(), context))
               );
            }

            if (mouth.target()) {
               parts = RoadTunnelNose.apply(
                  parts,
                  RoadTunnelNose.build(
                     tube.mesh, mouth.ramp().mesh, mouth.to(), mouth.ramp().mesh.length(), noseCeiling(tube, parts), tunnelHeadroom(mouth.ramp(), context)
                  )
               );
            }
         }

         ArrayList<RoadShellOpening> openings = new ArrayList<>();

         for (Mouth mouth : mouths) {
            DoubleUnaryOperator rampHeight = tunnelHeadroom(mouth.ramp(), context);
            DoubleUnaryOperator hostHeight = noseCeiling(tube, parts);
            DoubleUnaryOperator height = d -> {
               RoadGeometry.Sample at = RoadStructures.sample(mouth.ramp().mesh, d);
               RoadQueries.Projection qx = RoadQueries.horizontal(tube.mesh, at.center());
               return Math.max(rampHeight.applyAsDouble(d), qx.sample().center().y() + hostHeight.applyAsDouble(qx.sample().distance()) - at.center().y());
            };
            if (mouth.source()) {
               openings.add(new RoadShellOpening(mouth.ramp().mesh, 0.0, mouth.from(), height));
            }

            if (mouth.target()) {
               openings.add(new RoadShellOpening(mouth.ramp().mesh, mouth.to(), mouth.ramp().mesh.length(), height));
            }
         }

         HashMap<UUID, List<RoadShellOpening>> parents = new HashMap<>();
         if (linked) {
            for (RoadIndex.Built rx : roads) {
               if (source.contains(rx.record.id()) || target.contains(rx.record.id())) {
                  boolean tunnel = rx.mesh.settings().structure() == RoadGeometry.Structure.TUNNEL;
                  List<RoadAutoTunnels.RoofRegion> profiles = RoadAutoTunnels.roofRegions(rx.record.structures());
                  DoubleUnaryOperator childHeight = tunnelHeadroom(tube, context);
                  RoadShellOpening air = new RoadShellOpening(rx.mesh, 0.0, rx.mesh.length(), d -> {
                     RoadGeometry.Sample at = RoadStructures.sample(rx.mesh, d);
                     double heightx = tunnel ? RoadInfrastructure.ceiling(rx.mesh.settings().options().infrastructure(), at.halfWidth(), 0.0) : 4.25;
                     if (!tunnel) {
                        for (RoadAutoTunnels.RoofRegion profile : profiles) {
                           if (d >= profile.from() && d <= profile.to()) {
                              heightx = profile.first() + (profile.last() - profile.first()) * (d - profile.from()) / (profile.to() - profile.from());
                              break;
                           }
                        }
                     }

                     RoadGeometry.Sample child = RoadQueries.horizontal(tube.mesh, at.center()).sample();
                     return Math.max(heightx, child.center().y() + childHeight.applyAsDouble(child.distance()) - at.center().y());
                  });
                  List<RoadStructures.Part> shell = (tunnel ? RoadInfrastructure.tunnelShell(rx.mesh) : rx.record.structures())
                     .stream()
                     .filter(px -> px.material() == RoadStructures.Material.TUNNEL)
                     .toList();
                  ArrayList<RoadShellOpening> portals = new ArrayList<>();
                  ArrayList<RoadShellOpening> noseCuts = new ArrayList<>();
                  RoadGeometry.Mesh mouthLining = RoadRibbon.mesh(
                     tube.mesh.samples().stream().map(s -> new RoadGeometry.Sample(s.center(), s.left(), s.distance(), s.halfWidth() + liningWidth)).toList(),
                     tube.mesh.settings()
                  );
                  if (source.contains(rx.record.id())) {
                     double end = mouthLining.samples().get(LaneRamps.contactEnd(mouthLining, all, source, true)).distance();
                     portals.add(new RoadShellOpening(tube.mesh, 0.0, end, tunnelHeadroom(tube, context)));
                     RoadTunnelNose.Join join = RoadTunnelNose.build(rx.mesh, tube.mesh, 0.0, end, noseCeiling(rx), tunnelHeadroom(tube, context));
                     shell = RoadTunnelNose.apply(shell, join);
                     noseCuts.add(RoadShellOpening.solids(join.volume()));
                  }

                  if (target.contains(rx.record.id())) {
                     double start = mouthLining.samples().get(LaneRamps.contactEnd(mouthLining, all, target, false)).distance();
                     portals.add(new RoadShellOpening(tube.mesh, start, tube.mesh.length(), tunnelHeadroom(tube, context)));
                     RoadTunnelNose.Join join = RoadTunnelNose.build(
                        rx.mesh, tube.mesh, start, tube.mesh.length(), noseCeiling(rx), tunnelHeadroom(tube, context)
                     );
                     shell = RoadTunnelNose.apply(shell, join);
                     noseCuts.add(RoadShellOpening.solids(join.volume()));
                  }

                  for (RoadShellOpening aperture : portals) {
                     ArrayList<RoadStructures.Part> clipped = new ArrayList<>();

                     for (RoadStructures.Part p : shell) {
                        clipped.addAll(aperture.cut(p));
                     }

                     shell = clipped;
                  }

                  ArrayList<RoadShellOpening> cuts = new ArrayList<>();
                  cuts.add(air);
                  cuts.add(RoadShellOpening.solids(shell.stream().filter(px -> px.material() == RoadStructures.Material.TUNNEL).toList()));
                  cuts.addAll(noseCuts);
                  parents.put(rx.record.id(), List.copyOf(cuts));
               }
            }
         }

         ArrayList<RoadStructures.Part> out = new ArrayList<>();

         for (RoadStructures.Part p : parts) {
            if (p.material() != RoadStructures.Material.TUNNEL
               && p.material() != RoadStructures.Material.SIGN_WHITE
               && p.material() != RoadStructures.Material.LAMP) {
               out.add(p);
            } else {
               List<RoadStructures.Part> pieces = List.of(p);
               boolean ceilingLight = p.model().equals("sr:tunnel_ceiling_light") || p.model().equals("sr:auto_light");
               if (!ceilingLight) {
                  for (RoadShellOpening opening : openings) {
                     ArrayList<RoadStructures.Part> next = new ArrayList<>();

                     for (RoadStructures.Part piece : pieces) {
                        next.addAll(opening.cut(piece));
                     }

                     pieces = next;
                  }
               }

               if (linked) {
                  double low = p.base().stream().mapToDouble(v -> RoadQueries.horizontal(tube.mesh, v).sample().distance()).min().orElseThrow();
                  double high = p.base().stream().mapToDouble(v -> RoadQueries.horizontal(tube.mesh, v).sample().distance()).max().orElseThrow();

                  for (RoadIndex.Built rxx : roads) {
                     if (source.contains(rxx.record.id()) && low <= begin + 0.01 || target.contains(rxx.record.id()) && high >= finish - 0.01) {
                        for (RoadShellOpening opening : parents.get(rxx.record.id())) {
                           ArrayList<RoadStructures.Part> next = new ArrayList<>();

                           for (RoadStructures.Part piece : pieces) {
                              next.addAll(opening.cut(piece));
                           }

                           pieces = next;
                        }
                     }
                  }
               }

               boolean endPort = false;

               for (RoadIndex.Built rxxx : roads) {
                  if (!rxxx.record.id().equals(tube.record.id()) && connected(tube.record, rxxx.record)) {
                     RoadGeometry.V mid = p.a().add(p.b()).mul(0.5);

                     for (BlockPos pos : List.of(tube.record.a(), tube.record.b())) {
                        if (pos.equals(rxxx.record.a()) || pos.equals(rxxx.record.b())) {
                           RoadGeometry.V end = pos.equals(tube.record.a()) ? tube.mesh.first().center() : tube.mesh.last().center();
                           if (mid.sub(end).horizontalLength() < Math.max(8.0, (tube.record.settings().width() + rxxx.record.settings().width()) * 0.6 + 3.0)
                              && invades(p, rxxx.mesh)) {
                              endPort = true;
                           }
                        }
                     }
                  }
               }

               if (!endPort) {
                  out.addAll(pieces);
               }
            }
         }

         RoadShellOpening attached = RoadShellOpening.solids(out.stream().filter(px -> px.material() == RoadStructures.Material.TUNNEL).toList());
         ArrayList<RoadStructures.Part> result = new ArrayList<>();

         for (RoadStructures.Part px : out) {
            if (px.material() != RoadStructures.Material.LAMP && px.material() != RoadStructures.Material.SIGN_WHITE) {
               result.add(px);
            } else {
               boolean ceiling = px.model().equals("sr:tunnel_ceiling_light") || px.model().equals("sr:auto_light");
               RoadQueries.Projection q = RoadQueries.horizontal(tube.mesh, px.a().add(px.b()).mul(0.5));
               RoadGeometry.V shift = ceiling ? new RoadGeometry.V(0.0, 0.24, 0.0) : q.sample().left().mul((double)(q.lateral() < 0.0 ? -1 : 1) * 0.36);

               for (RoadStructures.Part kept : attached.retain(translated(px, shift))) {
                  result.add(translated(kept, shift.mul(-1.0)));
               }
            }
         }

         return List.copyOf(result);
      }
   }

   private static RoadStructures.Part translated(RoadStructures.Part p, RoadGeometry.V shift) {
      return new RoadStructures.Part(
         p.a().add(shift), p.b().add(shift), p.width(), p.height(), p.pier(), p.material(), p.frameA(), p.frameB(), p.model(), p.heights()
      );
   }
}
