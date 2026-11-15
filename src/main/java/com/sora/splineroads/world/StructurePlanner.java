package com.sora.splineroads.world;

import com.sora.splineroads.core.ElevatedSigns;
import com.sora.splineroads.core.JunctionApproachFurniture;
import com.sora.splineroads.core.JunctionPaint;
import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionRetainingWalls;
import com.sora.splineroads.core.JunctionSignalSupports;
import com.sora.splineroads.core.JunctionSlips;
import com.sora.splineroads.core.JunctionSlips48;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.LaneClosureLandscape;
import com.sora.splineroads.core.LaneDeck;
import com.sora.splineroads.core.LaneMerge;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneRampTerrain;
import com.sora.splineroads.core.MultiLevelBridges;
import com.sora.splineroads.core.OneSidedJunction;
import com.sora.splineroads.core.RailbedFurniture;
import com.sora.splineroads.core.RailbedProfile;
import com.sora.splineroads.core.RailbedStructures;
import com.sora.splineroads.core.RailwayCrossings;
import com.sora.splineroads.core.RoadAutoTunnels;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadCrashCushion;
import com.sora.splineroads.core.RoadCrossingFailure;
import com.sora.splineroads.core.RoadFurniture;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGantryMounts;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadJunctionSignalModel;
import com.sora.splineroads.core.RoadPavingSupport;
import com.sora.splineroads.core.RoadPierSpacing;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadPlantings;
import com.sora.splineroads.core.RoadPoleModel;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRailJoin;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadSignals;
import com.sora.splineroads.core.RoadSolidOverlap;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.core.RoadStructureClearance;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadSupports;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.core.RoadsideBypass;
import com.sora.splineroads.core.SidewalkJoins;
import com.sora.splineroads.core.TactileSurface;
import com.sora.splineroads.core.YJunctionWalks;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

final class StructurePlanner {
   static RoadRecord plan(
      ServerLevel level, RoadIndex.Built built, List<RoadIndex.Built> all, Map<Long, BlockState> originals, Map<BlockPos, List<AABB>> terrainCache
   ) {
      return plan(level, built, all, originals, terrainCache, new RoadPlanningIndex(all));
   }

   static RoadRecord plan(
      ServerLevel level,
      RoadIndex.Built built,
      List<RoadIndex.Built> all,
      Map<Long, BlockState> originals,
      Map<BlockPos, List<AABB>> terrainCache,
      RoadPlanningIndex lookup
   ) {
      return plan(level, built, all, originals, Map.of(), terrainCache, lookup);
   }

   static RoadRecord plan(
      ServerLevel level,
      RoadIndex.Built built,
      List<RoadIndex.Built> all,
      Map<Long, BlockState> retained,
      Map<Long, BlockState> originals,
      Map<BlockPos, List<AABB>> terrainCache,
      RoadPlanningIndex lookup
   ) {
      if (passiveFill(built)) {
         return built.record.structures(List.of());
      } else {
         built = prepareCrossings(level, built, retained, originals, terrainCache, lookup);
         List<RoadIndex.Built> nearby = lookup.near(
            built.mesh,
            (double)(3 + (built.record.settings().options().sidewalk().enabled() ? built.record.settings().options().sidewalk().width() : 0)),
            built.record.id()
         );
         return planGeometry(level, built, all, retained, originals, terrainCache, lookup, nearby, null, StructurePlanner.Stage.FULL);
      }
   }

   static RoadRecord reserve(
      ServerLevel level,
      RoadIndex.Built built,
      List<RoadIndex.Built> all,
      Map<Long, BlockState> retained,
      Map<Long, BlockState> originals,
      Map<BlockPos, List<AABB>> terrainCache,
      RoadPlanningIndex lookup,
      StructurePlanner.Stage stage
   ) {
      if (passiveFill(built)) {
         return built.record.structures(List.of());
      } else if (stage != StructurePlanner.Stage.FIXED
         || built.mesh.settings().structure() != RoadGeometry.Structure.TUNNEL
            && (
               built.record.fragment() != null || built.record.junction() != null || built.record.toll() != null || RoadInfrastructure.customBridge(built.mesh)
            )) {
         if (stage != StructurePlanner.Stage.GANTRIES
            || built.mesh.settings().structure() != RoadGeometry.Structure.TUNNEL
               && built.record.junction() == null
               && built.record.toll() == null
               && !built.mesh.settings().options().infrastructure().gantryEdits().isEmpty()) {
            List<RoadIndex.Built> nearby = lookup.near(
               built.mesh,
               (double)(3 + (built.record.settings().options().sidewalk().enabled() ? built.record.settings().options().sidewalk().width() : 0)),
               built.record.id()
            );
            return planGeometry(level, built, all, retained, originals, terrainCache, lookup, nearby, null, stage);
         } else {
            return built.record;
         }
      } else {
         return built.record.structures(List.of());
      }
   }

   static RoadIndex.Built prepareCrossings(
      ServerLevel level,
      RoadIndex.Built built,
      Map<Long, BlockState> retained,
      Map<Long, BlockState> originals,
      Map<BlockPos, List<AABB>> terrainCache,
      RoadPlanningIndex lookup
   ) {
      try {
         return prepareCrossingGeometry(level, built, retained, originals, terrainCache, lookup);
      } catch (IllegalArgumentException var7) {
         if (var7 instanceof RoadCrossingFailure) {
            throw var7;
         } else {
            throw new RoadCrossingFailure(built.record.id(), built.mesh, lookup.crossingHistoryReason(built), var7);
         }
      }
   }

   private static boolean passiveFill(RoadIndex.Built built) {
      JunctionPlanner.Ref ref = built.record.junction();
      if (ref != null
         && ref.spec().kind() == JunctionSpec.Kind.INTERSECTION
         && ref.piece() > ref.spec().arms().size()
         && ref.get().arm() < 0
         && ref.get().structures().isEmpty()
         && built.mesh.settings().structure() == RoadGeometry.Structure.GROUND) {
         RoadStreetscape.Config decor = built.mesh.settings().options().streetscape();
         RoadStreetscape.SideStructures sides = decor.sides();
         return decor.tunnels().isEmpty()
            && decor.crossings().isEmpty()
            && sides.negativeRaised().isEmpty()
            && sides.positiveRaised().isEmpty()
            && sides.negativeCut().isEmpty()
            && sides.positiveCut().isEmpty();
      } else {
         return false;
      }
   }

   private static RoadIndex.Built prepareCrossingGeometry(
      ServerLevel level,
      RoadIndex.Built built,
      Map<Long, BlockState> retained,
      Map<Long, BlockState> originals,
      Map<BlockPos, List<AABB>> terrainCache,
      RoadPlanningIndex lookup
   ) {
      List<RoadIndex.Built> nearby = lookup.near(
         built.mesh,
         (double)(3 + (built.record.settings().options().sidewalk().enabled() ? built.record.settings().options().sidewalk().width() : 0)),
         built.record.id()
      );
      RoadStructures.Ground terrain = RoadTerrain.read(level, retained, originals, terrainCache);
      if (RoadProfile.catalog(built.mesh.settings().style()).type() == RoadProfile.Type.ORDINARY && built.record.junction() == null) {
         RoadRecord raw = built.record.withLevelCrossings(List.of());
         RoadGeometry.Mesh authored = RoadStreetscape.resolve(raw.mesh(), terrain);
         List<RoadGeometry.Mesh> railMeshes = nearby.stream().filter(r -> r.record.railway()).map(r -> RoadStreetscape.resolve(r.mesh, terrain)).toList();
         built = built.planned(raw.withLevelCrossings(RailwayCrossings.surfaces(authored, railMeshes)));
      }

      Map<RoadGeometry.Mesh, List<RoadAutoTunnels.RoofRegion>> roofCache = (Map<RoadGeometry.Mesh, List<RoadAutoTunnels.RoofRegion>>)(terrainCache instanceof RoadTerrain.Cache cache
         ? cache.cycleRoofs
         : new IdentityHashMap<>());
      boolean cycle = RoadsideBypass.eligible(built.mesh);
      RoadCycleChain chain = cycle ? lookup.cycleChain(built.record.id()) : null;
      boolean continuous = chain != null && chain.legs().size() > 1;
      ArrayList<RoadsideBypass.Neighbor> neighbors = new ArrayList<>();
      List<RoadIndex.Built> cycleNearby = continuous ? lookup.near(chain.mesh(), 3.0, built.record.id()) : nearby;
      if (cycle) {
         for (RoadIndex.Built r : cycleNearby) {
            neighbors.add(cycleNeighbor(r.mesh, r.record.structures(), terrain, roofCache));
         }
      }

      RoadRecord unchanged = lookup.unchangedDependency(built);
      List<RoadsideBypass.Neighbor> previousNeighbors = null;
      if (unchanged != null) {
         List<RoadPlanningIndex.CycleNeighbor> previous = lookup.previousCycleNeighbors(built);
         if (previous != null) {
            previousNeighbors = new ArrayList<>();

            for (RoadPlanningIndex.CycleNeighbor r : previous) {
               previousNeighbors.add(cycleNeighbor(r.mesh(), r.structures(), terrain, roofCache));
            }
         }
      }

      List<RoadsideBypass.Span> crossings = continuous
         ? chain.local(built.record.id(), lookup.cyclePlan(chain, () -> RoadsideBypass.plan(chain.mesh(), neighbors)))
         : RoadsideBypass.plan(built.mesh, neighbors, unchanged == null ? null : unchanged.settings().options().streetscape().crossings(), previousNeighbors);
      if (continuous) {
         RoadsideBypass.validateSection(built.mesh, crossings);
      }

      ArrayList<RoadStreetscape.Span> tunnelSpans = new ArrayList<>();

      for (RoadAutoTunnels.Section section : RoadAutoTunnels.structuralSections(built.mesh, terrain)) {
         if (section.roof()) {
            if (!tunnelSpans.isEmpty() && Math.abs(tunnelSpans.get(tunnelSpans.size() - 1).to() - section.from()) < 1.0E-7) {
               RoadStreetscape.Span previous = tunnelSpans.remove(tunnelSpans.size() - 1);
               tunnelSpans.add(new RoadStreetscape.Span(previous.from(), section.to()));
            } else {
               tunnelSpans.add(new RoadStreetscape.Span(section.from(), section.to()));
            }
         }
      }

      RoadRecord derived = built.record.derivedStreetscape(built.record.settings().options().streetscape().crossings(crossings).tunnels(tunnelSpans));
      if (derived != built.record) {
         built = built.planned(derived);
      }

      RoadsideBypass.validateFloor(built.mesh, crossings, (double)level.getMinBuildHeight());
      double missingDepth = 0.0;
      BlockPos obstruction = null;

      for (RoadsideBypass.Span span : crossings) {
         if (span.delta() < 0.0) {
            for (double d = Math.max(0.0, span.start()); d <= Math.min(built.mesh.length(), span.end()); d++) {
               RoadGeometry.Sample at = RoadStructures.sample(built.mesh, d);

               for (double fraction : new double[]{0.05, 0.5, 0.95}) {
                  double lateral = RoadsideBypass.inner(built.mesh, at, span.side()) * (1.0 - fraction)
                     + RoadsideBypass.outer(built.mesh, at, span.side()) * fraction;
                  RoadGeometry.V bottom = at.at((double)span.side() * lateral, 0.25 - span.height(d));
                  BlockPos pos = BlockPos.containing(bottom.x(), bottom.y() + 1.0E-7, bottom.z());
                  if (level.hasChunkAt(pos)) {
                     BlockState state = RoadPlanInputs.state(level, pos);
                     if (!RoadBlocks.isCollider(state) && state.getDestroySpeed(level, pos) < 0.0F && (double)(pos.getY() + 1) - bottom.y() > missingDepth) {
                        missingDepth = (double)(pos.getY() + 1) - bottom.y();
                        obstruction = pos;
                     }
                  }
               }
            }
         }
      }

      if (obstruction != null) {
         throw new RoadCrossingFailure.GeometryRejection(
            "非机动车下穿遇到基岩等不可破坏方块，位置 " + obstruction.toShortString() + "；本段相交道路需提高至少 " + String.format(Locale.ROOT, "%.2f", missingDepth) + " 格以保留地下空间"
         );
      } else {
         return built;
      }
   }

   private static RoadsideBypass.Neighbor cycleNeighbor(
      RoadGeometry.Mesh mesh,
      List<RoadStructures.Part> structures,
      RoadStructures.Ground terrain,
      Map<RoadGeometry.Mesh, List<RoadAutoTunnels.RoofRegion>> roofCache
   ) {
      List<RoadAutoTunnels.RoofRegion> roofs = RoadAutoTunnels.roofRegions(structures);
      if (roofs.isEmpty() && mesh.settings().structure() == RoadGeometry.Structure.AUTO) {
         roofs = roofCache.computeIfAbsent(mesh, m -> RoadAutoTunnels.roofRegions(RoadAutoTunnels.enclose(m, terrain, List.of())));
      }

      return new RoadsideBypass.Neighbor(mesh, roofs);
   }

   static List<RoadStructures.Part> gantry(
      ServerLevel level, RoadIndex.Built built, List<RoadIndex.Built> all, Map<Long, BlockState> retained, Map<Long, BlockState> originals, int slot
   ) {
      RoadPlanningIndex lookup = new RoadPlanningIndex(all);
      List<RoadIndex.Built> nearby = lookup.near(
         built.mesh,
         (double)(3 + (built.record.settings().options().sidewalk().enabled() ? built.record.settings().options().sidewalk().width() : 0)),
         built.record.id()
      );
      return planGeometry(level, built, all, retained, originals, new HashMap<>(), lookup, nearby, slot, StructurePlanner.Stage.FULL).structures();
   }

   private static RoadRecord planGeometry(
      final ServerLevel level,
      final RoadIndex.Built built,
      List<RoadIndex.Built> all,
      Map<Long, BlockState> retained,
      Map<Long, BlockState> originals,
      Map<BlockPos, List<AABB>> terrainCache,
      final RoadPlanningIndex lookup,
      final List<RoadIndex.Built> nearby,
      Integer gantrySlot,
      StructurePlanner.Stage stage
   ) {
      final HashMap<UUID, Boolean> planting = new HashMap<>();
      final boolean laneEdges = built.mesh.settings().style().ramp()
         || nearby.stream().anyMatch(r -> r.mesh.settings().style().ramp())
         || LaneTopology.metadata(built.record).link() != null
         || nearby.stream().anyMatch(r -> LaneTopology.metadata(r.record).link() != null);
      final RoadRailJoin railJoin = laneEdges
         ? new RoadRailJoin(
            built.mesh,
            nearby.stream()
               .map(r -> new RoadRailJoin.Neighbor(r.mesh, RoadSurface.higherPriority(r.record.id(), r.mesh, built.record.id(), built.mesh)))
               .toList()
         )
         : null;
      final RoadRailJoin capMouths = RoadRailJoin.mouths(
         nearby.stream()
            .filter(
               r -> LaneMerge.linkedTo(built.mesh, r.mesh)
                     || LaneMerge.linkedTo(r.mesh, built.mesh)
                     || LaneTopology.metadata(built.record).cuts().stream().anyMatch(c -> c.connection().equals(r.record.id()))
            )
            .map(r -> r.mesh)
            .toList()
      );
      final List<RoadSignals.Approach> approaches = built.record.assembly() == null
         ? List.of()
         : RoadSignals.approaches(built.mesh, nearby.stream().filter(r -> built.record.assembly().equals(r.record.assembly())).map(r -> r.mesh).toList());
      List<RoadGeometry.Mesh> supportMeshes = built.record.junction() == null
         ? List.of(built.mesh)
         : all.stream().filter(r -> Objects.equals(r.record.assembly(), built.record.assembly())).map(r -> r.mesh).toList();
      final List<RoadIndex.Built> obstacles = lookup.nearAny(supportMeshes, 82.0, built.record.id());
      final IdentityHashMap<RoadGeometry.Mesh, RoadGeometry.Mesh> motorDecks = new IdentityHashMap<>();
      final RoadStructureClearance roadClearance = new RoadStructureClearance();
      final RoadSolidOverlap.Index cycleExcavation = new RoadSolidOverlap.Index(
         obstacles.stream().flatMap(r -> RoadsideBypass.supportVoids(r.mesh).stream()).toList()
      );
      final RoadPavingSupport sidewalkSolids = new RoadPavingSupport(
         obstacles.stream().flatMap(r -> r.record.structures().stream()).filter(p -> p.material().name().startsWith("WALK_")).toList()
      );
      final RoadPierSpacing pierSpacing = new RoadPierSpacing(obstacles.stream().flatMap(r -> r.record.structures().stream()).toList());
      ArrayList<RoadStructures.Part> fixtures = new ArrayList<>();
      obstacles.forEach(r -> fixtures.addAll(r.record.structures()));
      if (gantrySlot != null) {
         fixtures.addAll(built.record.structures());
      }

      boolean hasGantry = RoadGantry.slots(built.mesh).stream().anyMatch(slot -> RoadGantry.station(built.mesh, slot).kind() != RoadInfrastructure.Gantry.OFF);
      ArrayList<RoadStructures.Part> gantryFixtures = new ArrayList<>();
      if (hasGantry) {
         gantryFixtures.addAll(fixtures.stream().filter(p -> p.pier() || RoadGantry.member(p)).toList());

         for (RoadIndex.Built other : obstacles) {
            if (RoadInfrastructure.customBridge(other.mesh)) {
               gantryFixtures.addAll(other.record.structures());
            }
         }
      }

      RoadPlantings stripPlantings = built.mesh.settings().options().streetscape().plantings();
      final RoadSolidOverlap.Index treeObstacles = new RoadSolidOverlap.Index(
         (Collection<RoadStructures.Part>)(built.mesh.settings().options().streetscape().planting() == RoadStreetscape.Planting.NONE
               && stripPlantings.medianTrees() == RoadStreetscape.Planting.NONE
               && stripPlantings.separatorTrees() == RoadStreetscape.Planting.NONE
            ? List.of()
            : fixtures)
      );
      final RoadSolidOverlap.Index gantryObstacles = new RoadSolidOverlap.Index(gantryFixtures);
      final RoadSolidOverlap.Index gantrySolids = new RoadSolidOverlap.Index(fixtures.stream().filter(RoadGantry::member).toList());
      ArrayList<RoadStructures.Part> boundaryFixtures = new ArrayList<>();
      if (hasGantry) {
         for (RoadIndex.Built otherx : obstacles) {
            for (RoadStructures.Part part : otherx.record.structures()) {
               if (RoadGantryMounts.boundaryPart(otherx.mesh, part)) {
                  boundaryFixtures.add(part);
               }
            }
         }

         if (gantrySlot != null) {
            for (RoadStructures.Part partx : built.record.structures()) {
               if (RoadGantryMounts.boundaryPart(built.mesh, partx)) {
                  boundaryFixtures.add(partx);
               }
            }
         }
      }

      final RoadSolidOverlap.Index gantryBoundaries = new RoadSolidOverlap.Index(boundaryFixtures);
      final RoadGeometry.Mesh terrainReference = built.record.terrainClassificationMesh();
      final RoadStructures.Ground terrain = RoadTerrain.read(level, retained, originals, terrainCache);
      LanePoints.Link link = LaneTopology.metadata(built.record).link();
      RoadGeometry.Mesh stackMesh = RoadStreetscape.resolve(built.mesh, terrain);
      List<RoadGeometry.Mesh> stackNeighbors = nearby.stream()
         .filter(r -> r.record.junction() == null && r.record.toll() == null && !r.record.legacyRailway())
         .map(r -> RoadStreetscape.resolve(r.mesh, terrain))
         .toList();
      final boolean sharedDeck = built.record.junction() == null
         && built.record.toll() == null
         && stackNeighbors.stream().anyMatch(m -> MultiLevelBridges.compatible(stackMesh, m));
      final List<RoadGeometry.Mesh> lowerDecks = sharedDeck
         ? List.of()
         : stackNeighbors.stream().filter(m -> MultiLevelBridges.compatible(m, stackMesh)).toList();
      if (gantrySlot == null && link != null) {
         try {
            LaneRampTerrain.validate(built.mesh, link.options(), terrain);
         } catch (IllegalArgumentException var73) {
            throw new LaneRamps.CandidateRejected(var73.getMessage());
         }
      }

      ArrayList<RoadIndex.Built> signNeighbors = new ArrayList<>(lookup.near(built.mesh, 100.0, built.record.id()));
      signNeighbors.add(built);
      final Predicate<RoadGeometry.V> signSight = gantrySlot == null ? JunctionSignRoads.lampClearance(signNeighbors) : p -> false;
      final RoadRecord builtRecord = built.record;
      final ArrayList<String> missingSupports = new ArrayList<>();
      final RoadSolidOverlap.Index ownBridge = new RoadSolidOverlap.Index(
         RoadInfrastructure.fixedBridgeParts(built.mesh, terrain)
            .stream()
            .filter(p -> p.top().stream().anyMatch(v -> v.y() > RoadQueries.horizontal(built.mesh, v).sample().center().y() + 0.05))
            .toList()
      );
      final RoadSolidOverlap.Index railwayAir = RailbedStructures.catenaryClearance(built.record.settings().options().railbed().paths());
      final var ground = new RoadStructures.Ground() {
         @Override
         public boolean sharedDeck() {
            return sharedDeck;
         }

         @Override
         public List<RoadStructures.Part> sharedSupports(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, double depth, double width, double offset) {
            return MultiLevelBridges.supports(mesh, lowerDecks, at, mesh.settings().thickness(), depth, width, offset, this);
         }

         @Override
         public List<RoadStructures.Part> fixedSupports() {
            return builtRecord.fragment() == null ? null : builtRecord.fragment().supports();
         }

         @Override
         public boolean requiredGantry(RoadGantry.Station station) {
            return lookup.requiredGantry(builtRecord.id(), station.slot());
         }

         @Override
         public List<RoadStructures.Part> preservedGantry(RoadGantry.Station station) {
            return lookup.preservedGantry(builtRecord.id(), station);
         }

         @Override
         public void missingSupport(double station, String reason) {
            missingSupports.add(String.format(Locale.ROOT, "%.2f: %s", station, reason));
         }

         @Override
         public boolean lampClear(RoadGeometry.V p) {
            return signSight.test(p);
         }

         @Override
         public RoadGeometry.Mesh terrainReference(RoadGeometry.Mesh mesh) {
            return terrainReference;
         }

         @Override
         public boolean closedLanePlanting(UUID connection) {
            return planting.computeIfAbsent(
               connection, id -> nearby.stream().filter(r -> r.record.id().equals(id)).noneMatch(r -> StructurePlanner.buried(r.mesh, this))
            );
         }

         @Override
         public boolean furnitureClear(RoadGeometry.V point) {
            return LanePoints.opening(built.mesh, point)
               || RoadSignals.furnitureClear(point, approaches)
               || nearby.stream()
                  .anyMatch(r -> RailwayCrossings.at(built.mesh, r.mesh, point, 1.0) || RailwayCrossings.walkingAt(built.mesh, r.mesh, point, 1.0));
         }

         @Override
         public boolean wireContinues(boolean first) {
            return nearby.stream().anyMatch(r -> RailbedFurniture.continues(built.mesh, r.mesh, r.record.structures(), first));
         }

         @Override
         public boolean marker(RoadGeometry.V point) {
            for (BlockPos p : List.of(built.record.a(), built.record.b())) {
               if (RoadPlanInputs.node(level, p) instanceof NodeEntity node && node.node().position().distance(point) < 1.6) {
                  return true;
               }
            }

            return false;
         }

         @Override
         public double top(double x, double z, double deckY) {
            return terrain.top(x, z, deckY);
         }

         @Override
         public double foundation(RoadGeometry.V center, double top, double width) {
            double median = RoadSupports.medianFoundation(center, top, width, obstacles.stream().map(r -> r.mesh).toList());
            return sidewalkSolids.foundation(center, top, width, median, () -> terrain.foundation(center, top, width));
         }

         @Override
         public double surface(double x, double z, double roadY) {
            return terrain.surface(x, z, roadY);
         }

         @Override
         public RoadGeometry.V railJoint(RoadGeometry.V p, RoadGeometry.V direction) {
            return railJoin == null ? null : railJoin.joint(p, direction);
         }

         @Override
         public boolean railPost(RoadGeometry.V p) {
            return railJoin == null || railJoin.ownsPost(p);
         }

         @Override
         public RoadGeometry.V railJoint(RoadGeometry.V p, RoadGeometry.V direction, boolean highway, boolean raised) {
            return railJoin == null ? null : railJoin.joint(p, direction, highway, raised);
         }

         @Override
         public boolean railPost(RoadGeometry.V p, boolean highway, boolean raised) {
            return railJoin == null || railJoin.ownsPost(p, highway, raised);
         }

         @Override
         public List<RoadRailJoin.Span> railSpans(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V outside) {
            return railJoin == null ? RoadStructures.Ground.super.railSpans(a, b, outside) : railJoin.exposed(a, b, outside);
         }

         @Override
         public List<RoadRailJoin.Span> railSpans(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V outside, double inset) {
            return railJoin == null ? RoadStructures.Ground.super.railSpans(a, b, outside, inset) : railJoin.exposed(a, b, outside, inset);
         }

         @Override
         public List<RoadRailJoin.Span> capRailSpans(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V outside) {
            ArrayList<RoadRailJoin.Span> result = new ArrayList<>();

            for (RoadRailJoin.Span span : this.railSpans(a, b, outside)) {
               result.addAll(capMouths.exposedMouth(span.a(), span.b()));
            }

            return result;
         }

         @Override
         public boolean joined(RoadGeometry.V point) {
            if (LanePoints.opening(built.mesh, point)) {
               return true;
            } else {
               for (RoadIndex.Built other : nearby) {
                  if (RoadQueries.joins(built.mesh, other.mesh, point)
                     || RailwayCrossings.at(built.mesh, other.mesh, point, 0.3)
                     || RailwayCrossings.walkingAt(built.mesh, other.mesh, point, 0.3)) {
                     return true;
                  }
               }

               return false;
            }
         }

         @Override
         public String blockedReason(RoadStructures.Part part) {
            if (RoadStructureClearance.selfSupportBlocked(part, built.mesh)) {
               return "构件侵入本桥通行空间，" + RoadStructures.Ground.super.blockedReason(part);
            } else {
               for (RoadIndex.Built other : obstacles) {
                  if (RoadClearance.structureInvades(part, other.mesh, RoadStructureClearance.headroom(other.mesh))) {
                     return RoadStructures.Ground.super.blockedReason(part) + "，冲突道路 " + other.record.id();
                  }
               }

               return RoadStructures.Ground.super.blockedReason(part);
            }
         }

         @Override
         public boolean blocked(RoadStructures.Part part) {
            return this.blocked(part, null, null, null);
         }

         @Override
         public boolean plantBlocked(RoadStructures.Part part, RoadGeometry.Sample mount) {
            return treeObstacles.intersects(part) || ownBridge.intersects(part) || this.blocked(part, null, null, mount);
         }

         @Override
         public boolean lampBlocked(RoadStructures.Part part, RoadGeometry.Sample mount) {
            return ownBridge.intersects(part) || this.blocked(part, null, null, mount);
         }

         @Override
         public boolean gantryMountConflict(RoadStructures.Part part, RoadGantry.Station station) {
            if (RoadGantryMounts.foot(part)
               && !gantryObstacles.intersects(part)
               && !pierSpacing.tooClose(part)
               && (part.material() != RoadStructures.Material.CONCRETE || !cycleExcavation.intersects(part))
               && !sidewalkSolids.blocks(part)) {
               boolean contact = gantryBoundaries.intersects(part);
               if (RoadGantryMounts.blockedByRoad(part, built.mesh, 4.25)) {
                  if (!RoadGantryMounts.vergeContact(part, built.mesh, station, 4.25)) {
                     return false;
                  }

                  contact = true;
               }

               for (RoadIndex.Built other : obstacles) {
                  if (roadClearance.cycleBlocked(part, other.mesh)) {
                     return false;
                  }

                  double clearance = RoadStructureClearance.headroom(other.mesh);
                  if (RoadGantryMounts.blockedByRoad(part, other.mesh, clearance)) {
                     if (!RoadGantryMounts.vergeContact(part, other.mesh, station, clearance)) {
                        return false;
                     }

                     contact = true;
                  }
               }

               return contact;
            } else {
               return false;
            }
         }

         @Override
         public boolean railBlocked(RoadStructures.Part part, RoadGeometry.V a, RoadGeometry.V b) {
            return this.blocked(part, a, b, null);
         }

         @Override
         public boolean unionRails() {
            return laneEdges;
         }

         private boolean blocked(RoadStructures.Part part, RoadGeometry.V railA, RoadGeometry.V railB, RoadGeometry.Sample lampMount) {
            RoadPlanningBudget.check();
            if (RoadExternalClearance.blocked(part)) {
               return true;
            } else if (!RailbedFurniture.visualOnly(part)
               && nearby.stream()
                  .anyMatch(
                     r -> Stream.concat(part.base().stream(), Stream.of(part.a().add(part.b()).mul(0.5)))
                           .anyMatch(p -> RailwayCrossings.walkingAt(built.mesh, r.mesh, p, 0.3))
                  )) {
               return true;
            } else if (!RailbedFurniture.visualOnly(part)
               && nearby.stream()
                  .anyMatch(r -> RailwayCrossings.at(built.mesh, r.mesh, part.a().add(part.b()).mul(0.5), 0.3) && RoadInteractions.invades(part, r.mesh))) {
               return true;
            } else if ((!RoadGantry.member(part) || !gantryObstacles.intersects(part) && !gantryBoundaries.intersects(part))
               && (!part.pier() || RoadGantry.member(part) || !gantrySolids.intersects(part))) {
               if (pierSpacing.tooClose(part)) {
                  return true;
               } else if (part.pier() && part.material() == RoadStructures.Material.CONCRETE && cycleExcavation.intersects(part)) {
                  return true;
               } else if (!RoadSidewalks.smoothPart(part) && sidewalkSolids.blocks(part)) {
                  return true;
               } else if (builtRecord.settings().options().railbed().active() && part.model().startsWith("sr:catenary")
                  ? railwayAir.intersects(part)
                  : (
                     lampMount == null
                        ? (
                           RoadGantryMounts.foot(part)
                              ? RoadGantryMounts.blockedByRoad(part, built.mesh, 4.25)
                              : RoadInteractions.selfSupportBlocked(part, built.mesh)
                        )
                        : RoadStreetscape.lampOwnRoadBlocked(part, built.mesh, lampMount)
                  )) {
                  return true;
               } else if (!laneEdges && !part.pier() && part.material() == RoadStructures.Material.DEFAULT) {
                  return false;
               } else {
                  double w = part.halfExtent() + (part.pier() ? 0.5 : 0.05);
                  AABB box = new AABB(
                     Math.min(part.a().x(), part.b().x()) - w,
                     Math.min(part.a().y(), part.b().y()) - part.verticalFrame(),
                     Math.min(part.a().z(), part.b().z()) - w,
                     Math.max(part.a().x(), part.b().x()) + w,
                     Math.max(part.a().y(), part.b().y()) + part.height() + part.verticalFrame(),
                     Math.max(part.a().z(), part.b().z()) + w
                  );
                  boolean precise = true;
                  if (precise && !part.pier()) {
                     List<RoadGeometry.V> base = part.base();
                     box = new AABB(
                        base.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(),
                        base.stream().mapToDouble(RoadGeometry.V::y).min().orElseThrow(),
                        base.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow(),
                        base.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(),
                        base.stream().mapToDouble(RoadGeometry.V::y).max().orElseThrow() + part.height(),
                        base.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()
                     );
                  }

                  for (RoadIndex.Built other : obstacles) {
                     if ((railA == null || !RoadRailJoin.sharedRail(other.mesh, railA, railB))
                        && !(box.maxX < other.mesh.min().x() - 2.0)
                        && !(box.minX > other.mesh.max().x() + 2.0)
                        && !(box.maxZ < other.mesh.min().z() - 2.0)
                        && !(box.minZ > other.mesh.max().z() + 2.0)) {
                        if (roadClearance.cycleBlocked(part, other.mesh)) {
                           return true;
                        }

                        if (!(box.maxY < other.mesh.min().y() - 0.05)
                           && !(box.minY > other.mesh.max().y() + RoadStructureClearance.headroom(other.mesh))
                           && !RoadSidewalks.smoothPart(part)) {
                           if (part.pier() && part.material() == RoadStructures.Material.CONCRETE && RoadSidewalks.blocksTactile(part, other.mesh)) {
                              return true;
                           }

                           if (!part.pier()) {
                              if (!LaneClosureLandscape.paved(part) || !RoadClearance.belowSurface(part, other.mesh, 0.025)) {
                                 boolean joining = RoadInteractions.connected(built.record, other.record)
                                    || LaneMerge.linkedTo(built.mesh, other.mesh)
                                    || LaneMerge.linkedTo(other.mesh, built.mesh);
                                 if (!joining || !RoadClearance.joiningFloor(part, built.mesh, other.mesh)) {
                                    RoadGeometry.Mesh protectedMesh = railA == null ? other.mesh : motorDecks.computeIfAbsent(other.mesh, LaneDeck::motorOnly);
                                    if (RoadClearance.structureInvades(part, protectedMesh, RoadStructureClearance.headroom(other.mesh))) {
                                       return true;
                                    }
                                 }
                              }
                           } else if (RoadStructureClearance.pierBlocked(part, other.mesh, nearby.stream().map(r -> r.mesh).toList())) {
                              return true;
                           }
                        }
                     }
                  }

                  return false;
               }
            } else {
               return true;
            }
         }
      };
      if (stage == StructurePlanner.Stage.PAVING) {
         List<RoadStructures.Part> slabs = RoadSidewalks.parts(built.mesh, built.mesh.settings().options().sidewalk(), ground, built.record.structures());
         slabs = new RoadsideBypass(built.mesh, nearby.stream().map(r -> r.mesh).toList()).apply(slabs);
         ArrayList<RoadStructures.Part> grading = new ArrayList<>(slabs);
         nearby.forEach(r -> grading.addAll(r.record.structures()));
         slabs = new TactileSurface(grading).gradePaving(slabs);
         SidewalkJoins joins = new SidewalkJoins(
            nearby.stream().filter(r -> Math.abs(r.mesh.first().center().y() - built.mesh.first().center().y()) < 4.0).map(r -> r.mesh).toList(),
            nearby.stream().flatMap(r -> r.record.structures().stream()).filter(RoadSidewalks::smoothPart).toList()
         );
         ArrayList<RoadStructures.Part> reserved = new ArrayList<>(built.record.structures());
         reserved.addAll(joins.clip(RoadInteractions.openPortal(built, slabs, nearby, all)).stream().filter(RoadSidewalks::smoothPart).toList());
         return built.record.structures(reserved);
      } else if (stage == StructurePlanner.Stage.FIXED) {
         ArrayList<RoadStructures.Part> fixed = new ArrayList<>();
         if (built.record.fragment() != null) {
            fixed.addAll(built.record.fragment().supports());
         }

         if (built.record.junction() != null) {
            fixed.addAll(built.record.junction().get().structures());
         } else if (built.record.toll() != null) {
            fixed.addAll(built.record.tollPlan().structures());
         } else {
            fixed.addAll(RoadInfrastructure.fixedBridgeParts(built.mesh, ground));
         }

         return built.record.structures(fixed);
      } else if (stage == StructurePlanner.Stage.GANTRIES) {
         ArrayList<RoadStructures.Part> reserved = new ArrayList<>(built.record.structures());
         if (built.record.junction() == null && built.record.toll() == null) {
            RoadGeometry.Mesh mesh = RoadStreetscape.resolve(built.mesh, ground);
            reserved.addAll(RoadGantry.required(mesh, ground, reserved));
         }

         return built.record.structures(reserved);
      } else if (gantrySlot != null) {
         return built.record.structures(RoadGantry.planStation(built.mesh, ground, gantrySlot));
      } else if (built.record.toll() != null) {
         for (double d = 0.0; d <= built.mesh.length(); d += 4.0) {
            RoadGeometry.Sample sample = RoadStructures.sample(built.mesh, d);

            for (double lateral : new double[]{-0.7, 0.0, 0.7}) {
               RoadGeometry.V p = sample.at(lateral * sample.halfWidth(), built.mesh.settings().thickness());
               double top = ground.top(p.x(), p.z(), p.y());
               if (!Double.isFinite(top) || p.y() - top > 96.0) {
                  throw new IllegalArgumentException("收费站下方缺少可达基础，请降低站区或先建设地基");
               }

               if (built.record.toll().config().structure() == RoadGeometry.Structure.GROUND && p.y() - top > 1.25) {
                  throw new IllegalArgumentException("地面收费站下方有悬空区，请铺设地基或改用自动/高架结构");
               }
            }
         }

         ArrayList<RoadStructures.Part> parts = new ArrayList<>(built.record.tollPlan().structures());
         parts.addAll(RoadStructures.supports(built.mesh, ground, RoadFurniture.Phase.DEFAULT));
         return built.record.structures(parts);
      } else if (built.record.junction() != null) {
         JunctionPlanner.Ref ref = built.record.junction();
         if (OneSidedJunction.retainedPiece(ref)) {
            return built.record.structures(ref.get().structures());
         } else {
            final ArrayList<RoadStructures.Part> parts = new ArrayList<>(ref.get().structures());
            if (ref.get().arm() >= 0) {
               final JunctionSpec.Arm arm = ref.spec().arms().get(ref.get().arm());
               RoadGeometry.Settings setting = JunctionRoads.editable(arm);
               RoadGeometry.Mesh source = ref.furnitureApproach(ref.get().arm());
               final RoadGeometry.Mesh proxy = RoadStreetscape.resolve(
                  new RoadGeometry.Mesh(source.samples(), setting, source.min(), source.max(), source.length(), source.closed()), ground
               );
               if (ref.geometryVersion() >= 47) {
                  parts.addAll(ref.approachParts(JunctionApproachFurniture.plan(ref.spec(), ref.get().arm(), proxy, ground)));
                  if (proxy.settings().structure() == RoadGeometry.Structure.TUNNEL) {
                     parts.addAll(RoadInfrastructure.plan(proxy, ground));
                  } else {
                     parts.addAll(ref.approachParts(RoadStructures.edgeSlabs(proxy, ground)));
                     parts.addAll(ref.approachParts(RoadStructures.groundSkirts(proxy, ground)));
                  }

                  ArrayList<RoadStructures.Part> enclosed = new ArrayList<>(
                     RoadAutoTunnels.enclose(proxy, ground, parts, RoadInteractions.tunnelHeadroom(built, all))
                  );
                  parts.clear();
                  parts.addAll(enclosed);
               }

               var decoratedGround = new RoadStructures.Ground() {
                  @Override
                  public double top(double x, double z, double y) {
                     return ground.top(x, z, y);
                  }

                  @Override
                  public boolean joined(RoadGeometry.V p) {
                     return ground.joined(p);
                  }

                  @Override
                  public boolean lampClear(RoadGeometry.V p) {
                     return ground.lampClear(p);
                  }

                  @Override
                  public boolean blocked(RoadStructures.Part p) {
                     return ground.blocked(p) || this.signalBlocked(p);
                  }

                  @Override
                  public boolean lampBlocked(RoadStructures.Part p, RoadGeometry.Sample mount) {
                     return ground.lampBlocked(p, mount) || this.signalBlocked(p);
                  }

                  @Override
                  public boolean plantBlocked(RoadStructures.Part p, RoadGeometry.Sample mount) {
                     return ground.plantBlocked(p, mount) || this.signalBlocked(p);
                  }

                  private boolean signalBlocked(RoadStructures.Part p) {
                     for (RoadStructures.Part old : parts) {
                        if ((
                              old.material() == RoadStructures.Material.CB_BASE
                                 || old.material() == RoadStructures.Material.CB_POST
                                 || old.material() == RoadStructures.Material.CB_ARM
                           )
                           && !(p.a().y() + p.height() <= old.a().y())
                           && !(old.a().y() + old.height() <= p.a().y())
                           && RoadSurface.area(p.base()) - RoadSurface.subtract(p.base(), old.base()).stream().mapToDouble(RoadSurface::area).sum() > 1.0E-6) {
                           return true;
                        }
                     }

                     return false;
                  }

                  @Override
                  public boolean furnitureClear(RoadGeometry.V p) {
                     double distance = RoadQueries.horizontal(proxy, p).sample().distance();
                     double setback = arm.crossingSetback() + (arm.crosswalk() ? arm.crossingWidth() : 0.0) + Math.max(arm.stopGap() + 3.0, 6.0);
                     return distance > proxy.length() - setback || ground.furnitureClear(p);
                  }
               };
               List<RoadStructures.Part> amenities = RoadStreetscape.plan(proxy, decoratedGround, FurnitureSpacing.onMesh(built.record, proxy));
               parts.addAll(ref.approachParts(amenities));
            }

            if (ref.get().arm() >= 0 || ref.spec().kind() == JunctionSpec.Kind.ROUNDABOUT) {
               parts.addAll(RoadStructures.supports(built.mesh, ground, FurnitureSpacing.onMesh(built.record, built.mesh)));
            } else if (ref.piece() == ref.spec().arms().size()) {
               if (ref.geometryVersion() >= 47) {
                  parts.addAll(
                     JunctionRetainingWalls.plan(
                        ref.spec(), IntStream.range(0, ref.spec().arms().size()).mapToObj(ref::approach).toList(), ref.boundary(), ground
                     )
                  );
               }

               List<RoadGeometry.V> edge = ref.boundary();
               RoadGeometry.V center = ref.spec().center();
               double minX = edge.stream().mapToDouble(RoadGeometry.V::x).min().orElse(center.x());
               double maxX = edge.stream().mapToDouble(RoadGeometry.V::x).max().orElse(center.x());
               double minZ = edge.stream().mapToDouble(RoadGeometry.V::z).min().orElse(center.z());
               double maxZ = edge.stream().mapToDouble(RoadGeometry.V::z).max().orElse(center.z());

               for (double x = center.x() + Math.ceil((minX - center.x()) / 24.0) * 24.0; x <= maxX; x += 24.0) {
                  for (double z = center.z() + Math.ceil((minZ - center.z()) / 24.0) * 24.0; z <= maxZ; z += 24.0) {
                     RoadGeometry.V at = new RoadGeometry.V(x, center.y(), z);
                     boolean inside = true;

                     for (double dx : new double[]{-2.0, 2.0}) {
                        for (double dz : new double[]{-2.0, 2.0}) {
                           inside &= JunctionPaint.inside(edge, at.add(new RoadGeometry.V(dx, 0.0, dz)));
                        }
                     }

                     if (inside) {
                        parts.addAll(
                           RoadSupports.clearStandard(new RoadGeometry.Sample(at, new RoadGeometry.V(1.0, 0.0, 0.0), 0.0, 2.0), ref.spec().thickness(), ground)
                        );
                     }
                  }
               }
            }

            List<RoadStructures.Part> walks = all.stream()
               .filter(r -> Objects.equals(r.record.assembly(), built.record.assembly()) && r.record.junction() != null)
               .flatMap(r -> r.record.junction().get().structures().stream())
               .toList();
            JunctionSignalSupports.add(parts, supportMeshes, walks, ground, ref.spec().thickness());
            parts.addAll(JunctionSignRoads.supportParts(built, all, ground));
            JunctionSignRoads.reservePlanting(parts, built, all);
            List<RoadStructures.Part> finalSignals = ref.geometryVersion() >= 55
               ? ref.get().structures().stream().filter(px -> RoadPoleModel.support(px) || RoadJunctionSignalModel.supports(px)).toList()
               : List.of();
            if (ref.geometryVersion() >= 48 && JunctionSlips.enabled(ref.spec())) {
               if (ref.get().arm() <= -2) {
                  parts.addAll(RoadStructures.supports(built.mesh, ground, FurnitureSpacing.onMesh(built.record, built.mesh)));
               }

               if (ref.get().arm() >= -1) {
                  List<RoadStructures.Part> cleared = ref.geometryVersion() == 48
                     ? JunctionSlips48.clear(parts, ref.slips())
                     : JunctionSlips.clear(parts, ref.slips());
                  parts.clear();
                  parts.addAll(cleared);
               }

               if (ref.geometryVersion() >= 49 && ref.get().arm() >= 0) {
                  List<RoadStructures.Part> cleared = JunctionSlips.clear(parts, ref.replacedCycleStrips());
                  parts.clear();
                  parts.addAll(cleared);
               }

               if (ref.geometryVersion() >= 55) {
                  List<RoadStructures.Part> cleared = JunctionSlips.clearRefugePlanting(parts, ref.refuges());
                  parts.clear();
                  parts.addAll(cleared);
               }
            }

            for (RoadStructures.Part signal : finalSignals) {
               if (!parts.contains(signal)) {
                  parts.add(signal);
               }
            }

            RoadStructures.terminalPosts(parts, ground);
            if (ref.spec().oneSide() != null) {
               List<RoadStructures.Part> clipped = OneSidedJunction.generatedParts(ref.spec().oneSide(), parts);
               parts.clear();
               parts.addAll(clipped);
            }

            RoadStreetscape.Config config = RoadStreetscape.resolve(built.mesh, ground).settings().options().streetscape();
            return built.record.derivedStreetscape(config).structures(parts);
         }
      } else {
         List<RoadStructures.Part> partsx = RoadStructures.plan(built.mesh, ground, FurnitureSpacing.onMesh(built.record, built.mesh));
         if (built.record.assembly() != null) {
            List<RoadIndex.Built> siblings = all.stream().filter(r -> built.record.assembly().equals(r.record.assembly())).toList();
            if (siblings.size() == 3) {
               RoadIndex.Built stem = siblings.stream()
                  .filter(r -> RoadProfile.catalog(r.record.settings().style()).twoWay() && !r.record.settings().options().ends().persistent())
                  .findFirst()
                  .orElse(null);
               if (stem != null) {
                  RoadIndex.Built out = siblings.stream()
                     .filter(r -> r != stem && r.record.a().equals(stem.record.b()) && r.record.settings().options().ends().persistent())
                     .findFirst()
                     .orElse(null);
                  RoadIndex.Built in = siblings.stream()
                     .filter(r -> r != stem && r.record.b().equals(stem.record.b()) && r.record.settings().options().ends().persistent())
                     .findFirst()
                     .orElse(null);
                  if (out != null && in != null) {
                     partsx = YJunctionWalks.apply(built.mesh, stem.mesh, out.mesh, in.mesh, partsx);
                  }
               }
            }
         }

         ArrayList<RoadStructures.Part> grading = new ArrayList<>(partsx);
         nearby.forEach(r -> grading.addAll(r.record.structures()));
         partsx = RoadAutoTunnels.enclose(built.mesh, ground, partsx, RoadInteractions.tunnelHeadroom(built, all));
         partsx = new RoadsideBypass(built.mesh, nearby.stream().map(r -> r.mesh).toList()).apply(partsx);
         partsx = new TactileSurface(grading).gradePaving(partsx);
         SidewalkJoins joins = new SidewalkJoins(
            nearby.stream().filter(r -> Math.abs(r.mesh.first().center().y() - built.mesh.first().center().y()) < 4.0).map(r -> r.mesh).toList(),
            nearby.stream().flatMap(r -> r.record.structures().stream()).filter(RoadSidewalks::smoothPart).toList()
         );
         ArrayList<RoadStructures.Part> result = new ArrayList<>(joins.clip(RoadInteractions.openPortal(built, partsx, nearby, all)));
         RoadStructures.terminalPosts(result, ground);
         TollRoads.alignApproachRails(built, nearby, result);
         result.addAll(RoadCrashCushion.plan(built.mesh, nearby.stream().map(r -> r.mesh).toList(), result));
         ArrayList<RoadStructures.Part> paving = new ArrayList<>(result);
         nearby.forEach(r -> paving.addAll(r.record.structures()));
         result = new ArrayList<>(new TactileSurface(paving).conform(result));
         JunctionSignRoads.reservePlanting(result, built, all);
         ElevatedSigns.reserve(result, built.mesh);
         if (!approaches.isEmpty()) {
            ArrayList<RoadGeometry.Mesh> signalRoads = new ArrayList<>();
            signalRoads.add(built.mesh);

            for (RoadIndex.Built otherx : all) {
               if (!otherx.record.id().equals(built.record.id()) && Objects.equals(built.record.assembly(), otherx.record.assembly())) {
                  signalRoads.add(otherx.mesh);
               }
            }

            result.addAll(RoadSignals.structures(approaches, signalRoads, ground));
         }

         if (built.record.settings().options().railbed().active()) {
            RailbedProfile spec = built.record.settings().options().railbed();
            result.removeIf(px -> px.material() == RoadStructures.Material.LAMP);
            result.addAll(
               RailbedFurniture.plan(
                  built.mesh,
                  built.record.settings().structure(),
                  result,
                  spec.paths(),
                  spec.fittings(),
                  ground,
                  FurnitureSpacing.onMesh(built.record, built.mesh)
               )
            );
         }

         if (!missingSupports.isEmpty()) {
            System.getLogger("SplineRoads/supports").log(Level.INFO, "道路 " + built.record.id() + " 未布置单柱的站位与原因：" + String.join("；", missingSupports));
         }

         return built.record
            .derivedStreetscape(built.record.settings().options().streetscape().sideStructures(RoadStreetscape.classifySides(built.mesh, ground)))
            .structures(result);
      }
   }

   static boolean buried(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      if (mesh.settings().structure() == RoadGeometry.Structure.TUNNEL) {
         return true;
      } else {
         for (double d = 0.0; d < mesh.length(); d++) {
            RoadGeometry.Sample sample = RoadStructures.sample(mesh, Math.min(mesh.length(), d + 0.5));
            int covered = 0;

            for (double side : new double[]{-0.7, 0.0, 0.7}) {
               RoadGeometry.V p = sample.at(side * sample.halfWidth(), 0.0);
               double top = ground.top(p.x(), p.z(), p.y() + 6.0);
               if (Double.isFinite(top) && top > p.y() + 0.12) {
                  covered++;
               }
            }

            if (covered >= 2) {
               return true;
            }
         }

         return false;
      }
   }

   private StructurePlanner() {
   }

   static enum Stage {
      FULL,
      FIXED,
      GANTRIES,
      PAVING;
   }
}
