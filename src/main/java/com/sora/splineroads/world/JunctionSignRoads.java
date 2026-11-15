package com.sora.splineroads.world;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSlips;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.SmartSigns;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;

final class JunctionSignRoads {
   static List<RoadStructures.Part> plantedSeats(RoadIndex.Built owner, List<RoadIndex.Built> roads) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();

      for (RoadIndex.Built road : roads) {
         JunctionPlanner.Ref ref = road.record.junction();
         if (ref != null && ref.get().arm() >= 0) {
            JunctionSpec.Arm arm = ref.spec().arms().get(ref.get().arm());
            if (arm.incoming() != 0 && arm.advanceSign()) {
               JunctionSignRoads.Placement mount = placement(road, 0, p -> roads, new HashSet<>());
               if (mount.location().mesh() == owner.mesh) {
                  RoadGeometry.V foot = mount.support().foot();
                  RoadGeometry.Sample sample = mount.location().sample();
                  if (!(Math.abs(foot.sub(sample.center()).dot(sample.left())) >= sample.halfWidth() - 0.3)) {
                     RoadGeometry.V base = foot.add(mount.support().front().mul(-0.03)).add(new RoadGeometry.V(0.0, -0.4, 0.0));
                     out.add(new RoadStructures.Part(base, base, 0.65, 0.4, true, RoadStructures.Material.CONCRETE, null, null, "sr:junction_sign_pedestal"));
                  }
               }
            }
         }
      }

      return List.copyOf(out);
   }

   static void reservePlanting(List<RoadStructures.Part> parts, RoadIndex.Built owner, List<RoadIndex.Built> roads) {
      List<RoadStructures.Part> bases = plantedSeats(owner, roads);
      if (!bases.isEmpty()) {
         RoadStructures.trimPlanting(parts, bases, true);
         parts.addAll(bases);
      }
   }

   static List<RoadStructures.Part> supportParts(RoadIndex.Built owner, List<RoadIndex.Built> roads, RoadStructures.Ground ground) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      JunctionPlanner.Ref ref = owner.record.junction();
      if (ref != null && ref.get().arm() >= 0) {
         JunctionSpec.Arm arm = ref.spec().arms().get(ref.get().arm());

         for (int slot = 0; slot < 2; slot++) {
            if (arm.incoming() != 0 && (slot == 0 ? arm.advanceSign() : arm.nameSign())) {
               JunctionSignRoads.Placement mount = placement(owner, slot, p -> roads, new HashSet<>());
               RoadGeometry.V foot = mount.support().foot();
               RoadGeometry.Mesh mesh = mount.location().mesh();
               RoadGeometry.Sample at = mount.location().sample();
               double lateral = foot.sub(at.center()).dot(at.left());
               if (!(Math.abs(lateral) <= at.halfWidth() - 0.3)
                  && !(ground.top(foot.x(), foot.z(), foot.y()) >= foot.y() - 0.05)
                  && (
                     !mesh.settings().options().sidewalk().enabled()
                        || !(Math.abs(lateral) < at.halfWidth() + (double)mesh.settings().options().sidewalk().width() - 0.3)
                  )) {
                  RoadGeometry.V anchor = at.at(Math.copySign(at.halfWidth() - 0.4, lateral), 0.0);
                  double y = at.center().y() - Math.max(0.5, mesh.settings().thickness());
                  RoadGeometry.V outward = at.left().mul(Math.signum(lateral));
                  RoadStructures.Part seat = new RoadStructures.Part(
                     new RoadGeometry.V(anchor.x(), y, anchor.z()),
                     new RoadGeometry.V(foot.x(), y, foot.z()).add(outward.mul(0.35)),
                     0.8,
                     foot.y() - y,
                     false,
                     RoadStructures.Material.CONCRETE,
                     null,
                     null,
                     "sr:junction_sign_seat"
                  );
                  if (ground.blocked(seat)) {
                     throw new IllegalArgumentException("路口标牌承托与其他道路冲突");
                  }

                  out.add(seat);
               }
            }
         }

         return List.copyOf(out);
      } else {
         return out;
      }
   }

   static SmartSigns.State derive(RoadData data, RoadIndex.Built owner, SmartSigns.State old, int slot, Set<UUID> dependencies) {
      JunctionPlanner.Ref ref = owner.record.junction();
      JunctionSpec spec = ref.spec();
      int index = ref.get().arm();
      JunctionSpec.Arm arm = spec.arms().get(index);
      RoadGeometry.Mesh mesh = owner.mesh;
      SmartSigns.Mode mode = slot == 0 ? SmartSigns.Mode.JUNCTION_ADVANCE : SmartSigns.Mode.ROAD_NAME;
      JunctionSignRoads.Placement placement = placement(owner, slot, p -> {
         int cx = (int)Math.floor(p.x()) >> 4;
         int cz = (int)Math.floor(p.z()) >> 4;
         LinkedHashSet<RoadIndex.Built> found = new LinkedHashSet<>();

         for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
               for (UUID id : data.index.inChunk(ChunkPos.asLong(x, z))) {
                  RoadIndex.Built road = data.index.roads.get(id);
                  if (road != null) {
                     found.add(road);
                  }
               }
            }
         }

         return found;
      }, dependencies);
      SmartSigns.Support support = placement.support();
      ArrayList<SmartSigns.Branch> branches = new ArrayList<>();
      if (slot == 0 && placement.medianFlip()) {
         branches.add(new SmartSigns.Branch("__median__", List.of(), 0, true, List.of()));
      }

      if (slot == 1) {
         branches.add(new SmartSigns.Branch("road", List.of(), 0, true, List.of()));
      } else {
         RoadGeometry.V heading = mesh.last().left().left().mul(-1.0);
         RoadGeometry.V diagramRight = heading.left();

         for (int i = 0; i < spec.arms().size(); i++) {
            if (i != index && spec.arms().get(i).outgoing() != 0) {
               RoadGeometry.Mesh exit = ref.approach(i);
               RoadGeometry.V outward = exit.last().left().left();
               double dx = outward.dot(diagramRight);
               double dz = outward.dot(heading);
               int turn = Math.abs(dx) < 0.22 && dz > 0.0 ? 0 : (dx < 0.0 ? -1 : 1);
               branches.add(new SmartSigns.Branch("arm:" + armIdentity(data, owner, i), List.of(), turn, true, List.of(new RoadGeometry.V(dx, 0.0, dz))));
            }
         }
      }

      String entry = (spec.kind() == JunctionSpec.Kind.ROUNDABOUT ? "roundabout:" : "junction:") + armIdentity(data, owner, index);
      return old.layout(mode, owner.record.assembly() == null ? owner.record.id() : owner.record.assembly(), branches, "", entry).supported(support);
   }

   private static JunctionSignRoads.Placement placement(
      RoadIndex.Built owner, int slot, Function<RoadGeometry.V, Collection<RoadIndex.Built>> candidates, Set<UUID> dependencies
   ) {
      JunctionPlanner.Ref ref = owner.record.junction();
      JunctionSpec spec = ref.spec();
      JunctionSpec.Arm arm = spec.arms().get(ref.get().arm());
      RoadGeometry.Mesh mesh = owner.mesh;
      double ring = spec.kind() == JunctionSpec.Kind.ROUNDABOUT
         ? spec.islandRadius() + (double)spec.ringLanes() * spec.ringLaneWidth() - mesh.last().center().sub(spec.center()).horizontalLength()
         : 0.0;
      double stop = mesh.length() - ring - arm.crossingSetback() - (arm.crosswalk() ? arm.crossingWidth() + arm.stopGap() : 0.0);
      double before = slot == 0 ? 36.0 : 6.0;
      JunctionSignRoads.Location at = locate(candidates, owner, stop - before, dependencies);
      if (slot == 0 && arm.rightSlip() && ref.geometryVersion() >= 50) {
         RoadGeometry.Settings initialSettings = at.approach() ? JunctionRoads.editable(arm) : at.mesh().settings();
         RoadProfile.Layout initial = RoadProfile.layout(initialSettings, at.sample().halfWidth() * 2.0);
         boolean cycleGreen = initialSettings.options().cycle() && initialSettings.options().streetscape().separator() == RoadStreetscape.Separator.GREEN;
         boolean central = !cycleGreen && initial.catalog().median() == RoadProfile.Median.GREEN && initial.median() > 0.4;
         if (!central) {
            at = locate(
               candidates,
               owner,
               Math.min(stop - before, JunctionSlips.advanceSignStation(spec, ref.get().arm(), ref.furnitureApproach(ref.get().arm()))),
               dependencies
            );
         }
      }

      int side = spec.leftTraffic() ? -1 : 1;
      RoadGeometry.Mesh road = at.mesh();
      if (at.approach()) {
         road = new RoadGeometry.Mesh(road.samples(), JunctionRoads.editable(arm), road.min(), road.max(), road.length(), road.closed());
      }

      RoadGeometry.Sample sample = at.sample();
      RoadProfile.Layout profile = RoadProfile.layout(road, sample);
      double median = at.approach() ? JunctionPlanner.approachMedian(arm, at.mesh(), sample, true, spec.leftTraffic()) : profile.medianCenter();
      int physicalSide = side * at.flow();
      double lateral;
      if (slot == 0 && road.settings().options().cycle() && road.settings().options().streetscape().separator() == RoadStreetscape.Separator.GREEN) {
         lateral = (at.approach() ? JunctionPlanner.approachMotor(arm, at.mesh(), sample, physicalSide) : profile.outer(physicalSide))
            + (double)physicalSide * RoadStreetscape.separatorWidth(road.settings().options()) * 0.5;
      } else if (slot == 0 && profile.catalog().median() == RoadProfile.Median.GREEN && profile.median() > 0.4) {
         lateral = median;
      } else {
         lateral = (double)physicalSide * (sample.halfWidth() + (slot == 0 ? 0.8 : 0.45));
      }

      RoadGeometry.V right = sample.left().mul((double)at.flow());
      RoadGeometry.V forward = right.left().mul(-1.0);
      RoadGeometry.V foot = sample.center().add(sample.left().mul(lateral));
      boolean onRoad = Math.abs(lateral) < sample.halfWidth();
      if (onRoad) {
         foot = foot.add(new RoadGeometry.V(0.0, 0.4, 0.0));
      } else if (road.settings().options().sidewalk().enabled()) {
         foot = foot.add(new RoadGeometry.V(0.0, 0.2, 0.0));
      }

      RoadGeometry.V face = slot == 1 ? right.mul((double)(-side)) : forward.mul(-1.0);
      RoadGeometry.V panelRight = slot == 1 ? face.left().mul(-1.0) : right;
      boolean cantileverRight = slot == 0 && onRoad && Math.abs(lateral - median) < 0.1 ? side > 0 : spec.leftTraffic();
      SmartSigns.Support support = new SmartSigns.Support(foot, panelRight, face, spec.leftTraffic());
      return new JunctionSignRoads.Placement(at, support, cantileverRight != spec.leftTraffic());
   }

   static Predicate<RoadGeometry.V> lampClearance(List<RoadIndex.Built> roads) {
      ArrayList<Predicate<RoadGeometry.V>> signs = new ArrayList<>();

      for (RoadIndex.Built road : roads) {
         Predicate<RoadGeometry.V> sign = lampClearance(road, roads);
         if (sign != null) {
            signs.add(sign);
         }
      }

      return point -> {
         for (Predicate<RoadGeometry.V> signx : signs) {
            if (signx.test(point)) {
               return true;
            }
         }

         return false;
      };
   }

   static Predicate<RoadGeometry.V> lampClearance(RoadIndex.Built road, List<RoadIndex.Built> roads) {
      if (road.record.junction() != null
         && road.record.junction().get().arm() >= 0
         && road.record.junction().spec().arms().get(road.record.junction().get().arm()).incoming() > 0
         && road.record.junction().spec().arms().get(road.record.junction().get().arm()).advanceSign()) {
         JunctionSignRoads.Placement sign = placement(road, 0, p -> roads, new HashSet<>());
         return point -> {
            JunctionSignRoads.Location at = sign.location();
            RoadQueries.Projection q = RoadQueries.horizontal(at.mesh(), point);
            double extra = point.sub(q.sample().center()).dot(q.sample().left().left().mul(-1.0));
            double before = (at.sample().distance() - q.sample().distance() - extra) * (double)at.flow();
            double pole = sign.support().foot().sub(at.sample().center()).dot(at.sample().left());
            int side = (sign.support().paired() ? -1 : 1) * at.flow();
            int cantilever = sign.medianFlip() ? side : -side;
            double tip = pole + (double)cantilever * 3.32;
            double lo = Math.min(pole, tip) - 3.1;
            double hi = Math.max(pole, tip) + 3.1;
            return before >= -3.0 && before <= 24.0 && Math.abs(point.y() - q.sample().center().y()) < 3.0 && q.lateral() >= lo && q.lateral() <= hi;
         };
      } else {
         return null;
      }
   }

   private static String armIdentity(RoadData data, RoadIndex.Built owner, int arm) {
      CompoundTag descriptor = data.junctions.get(owner.record.assembly());
      if (descriptor != null) {
         ListTag roads = descriptor.getList("ArmRoads", 8);
         if (arm < roads.size() && !roads.getString(arm).isEmpty()) {
            return roads.getString(arm);
         }
      }

      RoadGeometry.V p = owner.record.junction().spec().arms().get(arm).endpoint().position();
      return UUID.nameUUIDFromBytes(
            (Math.round(p.x() * 1000.0) + ":" + Math.round(p.y() * 1000.0) + ":" + Math.round(p.z() * 1000.0)).getBytes(StandardCharsets.UTF_8)
         )
         .toString();
   }

   private static JunctionSignRoads.Location locate(
      Function<RoadGeometry.V, Collection<RoadIndex.Built>> candidates, RoadIndex.Built owner, double station, Set<UUID> dependencies
   ) {
      RoadGeometry.Mesh mesh = owner.mesh;
      JunctionPlanner.Ref ref = owner.record.junction();
      RoadGeometry.Mesh frame = ref.geometryVersion() >= 50 ? ref.furnitureApproach(ref.get().arm()) : mesh;
      if (station >= 0.0) {
         return new JunctionSignRoads.Location(mesh, RoadStructures.sample(frame, Math.min(station, frame.length() - 2.0)), 1, true);
      } else {
         RoadGeometry.V point = mesh.first().center();
         RoadGeometry.V upstream = mesh.first().left().left();
         double remaining = -station;
         HashSet<UUID> seen = new HashSet<>();
         seen.add(owner.record.id());

         for (int hops = 0; hops < 8; hops++) {
            ArrayList<JunctionSignRoads.Location> matches = new ArrayList<>();
            ArrayList<UUID> ids = new ArrayList<>();

            for (RoadIndex.Built b : candidates.apply(point)) {
               UUID id = b.record.id();
               if (!seen.contains(id) && b.record.junction() == null && !b.record.settings().style().ramp()) {
                  for (boolean first : new boolean[]{true, false}) {
                     RoadGeometry.Sample end = first ? b.mesh.first() : b.mesh.last();
                     RoadGeometry.V away = end.left().left().mul(first ? -1.0 : 1.0);
                     if (!(end.center().distance(point) > 0.08) && !(away.dot(upstream) < 0.95)) {
                        matches.add(
                           new JunctionSignRoads.Location(
                              b.mesh,
                              RoadStructures.sample(b.mesh, first ? Math.min(remaining, b.mesh.length() - 2.0) : Math.max(2.0, b.mesh.length() - remaining)),
                              first ? -1 : 1,
                              false
                           )
                        );
                        ids.add(id);
                     }
                  }
               }
            }

            if (matches.size() != 1) {
               break;
            }

            JunctionSignRoads.Location match = matches.get(0);
            UUID id = ids.get(0);
            dependencies.add(id);
            seen.add(id);
            if (remaining <= match.mesh().length() - 2.0) {
               return match;
            }

            remaining -= match.mesh().length();
            RoadGeometry.Sample end = match.flow() < 0 ? match.mesh().last() : match.mesh().first();
            point = end.center();
            upstream = end.left().left().mul((double)match.flow());
         }

         return new JunctionSignRoads.Location(mesh, RoadStructures.sample(frame, Math.min(2.0, frame.length() / 4.0)), 1, true);
      }
   }

   private JunctionSignRoads() {
   }

   private static record Location(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, int flow, boolean approach) {
   }

   private static record Placement(JunctionSignRoads.Location location, SmartSigns.Support support, boolean medianFlip) {
   }
}
