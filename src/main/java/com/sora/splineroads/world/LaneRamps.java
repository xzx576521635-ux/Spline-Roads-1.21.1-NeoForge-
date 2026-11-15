package com.sora.splineroads.world;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LaneDeck;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneRampAlignment;
import com.sora.splineroads.core.LaneRampApproach;
import com.sora.splineroads.core.LaneRampCorridor;
import com.sora.splineroads.core.LaneRampGrade;
import com.sora.splineroads.core.LaneRampPaths;
import com.sora.splineroads.core.LaneRampProfile;
import com.sora.splineroads.core.LaneRampTerrain;
import com.sora.splineroads.core.LaneRampThroat;
import com.sora.splineroads.core.LaneReopening;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadAutoTunnels;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadConflictIds;
import com.sora.splineroads.core.RoadCrossingFailure;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadShellOpening;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.TollPlanner;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class LaneRamps {
   private static final ThreadLocal<LaneRamps.Planning> CURRENT = new ThreadLocal<>();
   private static final Map<RoadIndex, LaneRamps.PreviewSnapshot> PREVIEW_SNAPSHOTS = new WeakHashMap<>();
   private static long snapshotBuilds;
   private static long snapshotHits;
   private static long snapshotNanos;
   private static long terrainFallbackNanos;
   private static long commitNanos;

   private static RoadGeometry.Mesh mesh(RoadRecord road) {
      LaneRamps.Planning plan = CURRENT.get();
      if (plan == null) {
         return road.mesh();
      } else {
         RoadGeometry.Mesh seeded = plan.seedMeshes.get(road);
         if (seeded != null) {
            return seeded;
         } else {
            if (plan.meshes.size() > 512) {
               plan.meshes.clear();
            }

            return plan.meshes.computeIfAbsent(road, RoadRecord::mesh);
         }
      }
   }

   static RoadRecord aboveFloor(RoadData data, Map<UUID, RoadRecord> all, UUID id, UUID owner, LanePoints.Link link, final double floor) {
      RoadRecord var11;
      try (LaneRamps.Planning scope = new LaneRamps.Planning(data)) {
         LaneRamps.Planning active = CURRENT.get();
         final RoadStructures.Ground previous = active.terrain;
         boolean retain = active.retainProfile;
         active.retainProfile = all.containsKey(id);
         active.terrain = new RoadStructures.Ground() {
            @Override
            public double top(double x, double z, double y) {
               return Math.min(y, floor);
            }

            @Override
            public double surface(double x, double z, double y) {
               double actual = previous == null ? floor : previous.surface(x, z, y);
               return Double.isFinite(actual) ? Math.max(floor, actual) : floor;
            }

            @Override
            public boolean blocked(RoadStructures.Part p) {
               return previous != null && previous.blocked(p);
            }

            @Override
            public boolean joined(RoadGeometry.V p) {
               return previous != null && previous.joined(p);
            }
         };

         try {
            var11 = generate(data, all, id, owner, link);
         } finally {
            active.terrain = previous;
            active.retainProfile = retain;
         }
      }

      return var11;
   }

   private static List<RoadClearance.Contact> contacts(RoadGeometry.Mesh proposed, RoadGeometry.Mesh existing) {
      LaneRamps.Planning plan = CURRENT.get();
      if (plan == null) {
         return RoadClearance.contacts(proposed, existing);
      } else {
         if (plan.decks.size() > 32) {
            plan.decks.clear();
         }

         long start = System.nanoTime();
         plan.contactQueries++;

         List var5;
         try {
            var5 = RoadClearance.contacts(proposed, plan.decks.computeIfAbsent(existing, RoadClearance::prepare));
         } finally {
            plan.clearanceNanos = plan.clearanceNanos + (System.nanoTime() - start);
         }

         return var5;
      }
   }

   public static RoadRecord host(Map<UUID, RoadRecord> all, LanePoints.Ref ref) {
      RoadRecord r = all.get(ref.road());
      if (r == null) {
         throw new IllegalArgumentException("所选道路已不存在");
      } else if (r.toll() != null) {
         throw new IllegalArgumentException("收费站内部和 A/B 接口不支持匝道，请连接站外道路");
      } else if (r.assembly() == null && r.junction() == null && LanePoints.supported(r.settings())) {
         LanePoints.Point selected = LaneTopology.point(r, ref.point());

         Collection<RoadRecord> candidates = all instanceof RoadPlanningSnapshot.Records records
            ? records.tolls()
            : all.values();
         for (RoadRecord other : candidates) {
            if (other.toll() != null && TollPlanner.contains(other.mesh(), selected.position(), 0.03, other.toll().config().clearHeight())) {
               throw new IllegalArgumentException("该车道点位于收费站内部或 A/B 接口，请把匝道接点移到站外");
            }
         }

         return r;
      } else {
         throw new IllegalArgumentException("连接器仅支持独立普通道路／高速／自由匝道的地面、隧道、自动高架、标准小河桥、梁式高架与跨线桥；不支持立交内部道路");
      }
   }

   public static LaneRampPaths.Port port(RoadRecord road, LanePoints.Point p) {
      return port(road, p, 1);
   }

   public static LaneRampPaths.Port port(RoadRecord road, LanePoints.Point p, int lanes) {
      RoadGeometry.Mesh mesh = mesh(road);
      LanePoints.Lane l = LanePoints.group(mesh, p, lanes);
      RoadGeometry.Sample sample = RoadStructures.sample(mesh, l.station());
      RoadProfile.Layout layout = RoadProfile.layout(mesh, sample);
      double lateral = l.position().sub(sample.center()).dot(sample.left());
      int side = layout.catalog().twoWay() ? (lateral < layout.medianCenter() ? -1 : 1) : layout.outside();
      double edge = side < 0 ? -layout.motorMin() : layout.motorMax();
      double distance = Math.max(l.width(), edge - (double)side * lateral + l.width() / 2.0);
      double step = Math.min(0.5, mesh.length() / 10.0);
      RoadGeometry.Sample before = RoadStructures.sample(mesh, Math.max(0.0, l.station() - step));
      RoadGeometry.Sample after = RoadStructures.sample(mesh, Math.min(mesh.length(), l.station() + step));
      RoadGeometry.V delta = after.center().sub(before.center());
      double grade = delta.y() / Math.max(0.001, delta.horizontalLength()) * (double)l.sign();
      return new LaneRampPaths.Port(l.position(), l.direction(), sample.left().mul((double)side), distance, grade);
   }

   public static LaneRampPaths.Port targetPort(RoadRecord road, LanePoints.Point point, double offset) {
      return targetPort(road, point, offset, 1);
   }

   private static LaneRampPaths.Port targetPort(RoadRecord road, LanePoints.Point point, double offset, int lanes) {
      LanePoints.Lane lane = LanePoints.lane(mesh(road), point);
      double station = lane.station() + (double)lane.sign() * offset;
      if (!(station < -1.0E-5) && !(station > mesh(road).length() + 1.0E-5)) {
         return port(road, LanePoints.point(point.id(), point.origin(), mesh(road), station, point.lane()), lanes);
      } else {
         throw new IllegalArgumentException("汇入口超出所选道路，不能换路或换车道");
      }
   }

   public static LaneRampPaths.Port arrivalPort(RoadRecord road, LanePoints.Point point, double offset, LanePoints.Link link, UUID connection) {
      if (link.options().targetExtra()) {
         return extraPort(road, point, offset);
      } else if (link.options().arrival() != LanePoints.Arrival.ADD) {
         return targetPort(road, point, offset, link.options().lanes());
      } else {
         LanePoints.Lane at = LanePoints.lane(mesh(road), point);
         LaneAdditions.Addition added = LaneAdditions.owned(mesh(road), connection);
         return port(road, LanePoints.point(point.id(), point.origin(), mesh(road), at.station() + (double)at.sign() * offset, added.slot()));
      }
   }

   private static LaneRampPaths.Port extraPort(RoadRecord road, LanePoints.Point point, double offset) {
      RoadGeometry.Mesh host = mesh(road);
      LanePoints.Lane lane = LanePoints.lane(host, point);
      double station = lane.station() + (double)lane.sign() * offset;
      if (!(station < 0.0) && !(station > host.length())) {
         LanePoints.Lane outside = LaneRampApproach.outerLane(host, station, point.lane());
         return port(road, LanePoints.point(point.id(), point.origin(), host, station, outside.index()));
      } else {
         throw new IllegalArgumentException("额外车道接头超出主路范围");
      }
   }

   private static boolean sameRoute(LanePoints.Link a, LanePoints.Link b) {
      if (Objects.equals(a, b)) {
         return true;
      } else if (a != null && b != null && a.options().separatesLane() && b.options().separatesLane()) {
         LanePoints.Options o = a.options();
         LanePoints.Options n = b.options();
         return a.equals(
            new LanePoints.Link(
               b.from(),
               b.to(),
               new LanePoints.Options(
                  n.path(), o.departure(), n.arrival(), n.radius(), n.transition(), n.elevation(), n.landing(), n.gradeOverride(), n.allowTunnel(), n.lanes()
               ),
               b.junctionMouth(),
               b.targetOffset(),
               b.protectedMerge(),
               b.rectangularClosure()
            )
         );
      } else {
         return false;
      }
   }

   private static LaneRampAlignment.Mouth[] contactMouths(Map<UUID, RoadRecord> all, LanePoints.Link link, UUID id) {
      RoadRecord source = host(all, link.from());
      LanePoints.Point point = LaneTopology.point(source, link.from().point());
      LanePoints.Lane lane = LanePoints.group(source.mesh(), point, link.options().sourceExtra() ? 1 : link.options().lanes());
      LaneRampAlignment.Mouth from = link.options().sourceExtra()
         ? LaneRampAlignment.Mouth.extra(lane.width(), RoadProfile.trafficSign(source.settings().options().leftTraffic()))
         : LaneRampAlignment.mouth(source.mesh(), lane);
      LaneRampAlignment.Mouth to = LaneRampAlignment.Mouth.extra(lane.width());
      if (link.to().road() != null) {
         LaneRoadChain.Position position = chain(all, link.to()).at(link.targetOffset());
         RoadGeometry.Mesh target = position.road().mesh();
         LanePoints.Lane targetLane = LanePoints.group(target, position.point(), link.options().targetExtra() ? 1 : link.options().lanes());
         if (link.options().arrival() == LanePoints.Arrival.ADD) {
            LaneAdditions.Addition added = LaneAdditions.owned(target, id);
            targetLane = LanePoints.lane(target, added.station(), added.slot());
         }

         to = link.options().targetExtra()
            ? LaneRampAlignment.Mouth.extra(targetLane.width(), RoadProfile.trafficSign(target.settings().options().leftTraffic()))
            : LaneRampAlignment.mouth(target, targetLane);
      }

      return new LaneRampAlignment.Mouth[]{from, to};
   }

   static RoadRecord generate(RoadData data, Map<UUID, RoadRecord> all, UUID id, UUID owner, LanePoints.Link link) {
      return generateChoice(data, all, id, owner, link, null).road();
   }

   public static RoadRecord reconfigure(RoadRecord old, RoadGeometry.Settings requested, Map<UUID, RoadRecord> all) {
      return reconfigure(null, old, requested, all);
   }

   private static RoadRecord reconfigure(RoadData data, RoadRecord old, RoadGeometry.Settings requested, Map<UUID, RoadRecord> all) {
      if (RoadProfile.catalog(requested).lanes() == LaneTopology.metadata(old).link().options().lanes()
         && (LanePoints.supported(requested) || requested.structure() == RoadGeometry.Structure.TUNNEL)) {
         return generateChoice(data, all, old.id(), old.owner(), LaneTopology.metadata(old).link(), requested).road();
      } else {
         throw new IllegalArgumentException("连接器匝道须保持独立匝道类型、单车道及支持的道路结构");
      }
   }

   private static LaneRamps.Generated generateChoice(
      RoadData data, Map<UUID, RoadRecord> all, UUID id, UUID owner, LanePoints.Link link, RoadGeometry.Settings edited
   ) {
      LaneRamps.Generated var8;
      try (
         RoadPlanningBudget budget = RoadPlanningBudget.cancellable("匝道路线/净空搜索");
         LaneRamps.Planning ignored = new LaneRamps.Planning(data);
      ) {
         var8 = generatePlanned(data, all, id, owner, link, edited);
      }

      return var8;
   }

   private static LaneRamps.Generated generatePlanned(
      RoadData data, Map<UUID, RoadRecord> all, UUID id, UUID owner, LanePoints.Link link, RoadGeometry.Settings edited
   ) {
      link = link.withProtectedMerge().withRectangularClosure();
      RoadRecord existing = all.get(id);
      LanePoints.Link existingLink = existing == null ? null : LaneTopology.metadata(existing).link();
      if (existingLink == null || !existingLink.options().equals(link.options())) {
         validateTunnelEnds(all, link.from(), link.to(), link.options());
      }

      RoadRecord source = host(all, link.from());
      LanePoints.Point p = LaneTopology.point(source, link.from().point());
      LanePoints.Lane lane = LanePoints.group(mesh(source), p, link.options().sourceExtra() ? 1 : link.options().lanes());
      double maxGrade = gradeLimit(all, link);
      RoadRecord previous = all.get(id);
      RoadRecord old = previous != null && LaneTopology.metadata(previous).link() != null ? previous : null;
      RoadGeometry.Style style = RoadProfile.highway(source.settings().style()) ? RoadGeometry.Style.C1_HIGHWAY_RAMP : RoadGeometry.Style.C1_RAMP;
      RoadGeometry.Settings base = edited != null
         ? edited
         : (
            old != null
               ? old.settings()
               : new RoadGeometry.Settings(RoadGeometry.Mode.CURVE, style, Math.max(4.0, lane.width()), source.settings().thickness(), 0.35, 90.0)
                  .structure(RoadGeometry.Structure.AUTO)
                  .options(RoadProfile.Options.DEFAULT.traffic(source.settings().options().leftTraffic()).lanePoints(LanePoints.Data.EMPTY.link(link)))
         );
      boolean migrating = old != null && !old.settings().style().connectorRamp();
      RoadGeometry.Style kind = (
            edited != null ? !RoadProfile.highway(edited.style()) : (old != null ? !RoadProfile.highway(old.settings().style()) : !RoadProfile.highway(style))
         )
         ? RoadGeometry.Style.C1_RAMP
         : RoadGeometry.Style.C1_HIGHWAY_RAMP;
      RoadProfile.Options options = base.options()
         .lanePoints(base.options().lanePoints().link(link))
         .lanes(link.options().lanes() > 1 ? new RoadLanes.Counts(link.options().lanes(), 0) : RoadLanes.Counts.AUTO)
         .hideArrows(true);
      if (old == null && edited == null || migrating && options.infrastructure().gantry() == RoadInfrastructure.Gantry.AUTO) {
         options = options.infrastructure(options.infrastructure().gantry(RoadInfrastructure.Gantry.OFF));
      }

      base = new RoadGeometry.Settings(
         base.mode(),
         kind,
         base.width(),
         base.thickness(),
         base.tension(),
         base.arcDegrees(),
         base.startWidth(),
         base.endWidth(),
         base.structure(),
         base.taperVersion(),
         base.rampTurn(),
         options
      );
      if (base.structure() == RoadGeometry.Structure.TUNNEL) {
         base = base.structure(RoadGeometry.Structure.AUTO);
      }

      base = LaneRampAlignment.usableWidth(base, link.options().sourceExtra() ? lane.width() * (double)link.options().lanes() : lane.width());
      base.validate();
      if (old != null
         && (edited == null || old.settings().options().ends().start() != null && old.settings().options().ends().end() != null)
         && sameRoute(LaneTopology.metadata(old).link(), link)
         && base.width() == old.settings().width()
         && base.thickness() == old.settings().thickness()
         && base.style() == old.settings().style()
         && (
            CURRENT.get() != null && CURRENT.get().retainProfile
               || link.options().elevation() == LanePoints.Elevation.KEEP
               || !LaneTopology.metadata(old).link().options().equals(link.options())
               || !verticalKink(old.mesh())
         )
         && (CURRENT.get() != null && CURRENT.get().retainProfile || link.options().elevation() != LanePoints.Elevation.AUTO || monotone(old.mesh()))) {
         try {
            RoadRecord kept = old.settings(base);
            RoadGeometry.Mesh candidate = kept.mesh();
            if (!currentApproaches(candidate, all, link)) {
               throw new IllegalArgumentException("额外汇入/汇出渐变长度已更新");
            }

            Map<UUID, RoadRecord> context = LaneCrossSections.staged(all, id, link, candidate);
            LaneRampAlignment.Mouth[] mouths = contactMouths(context, link, id);
            if (!LaneRampAlignment.matches(candidate, mouths[0], mouths[1])) {
               candidate = LaneRampAlignment.refit(candidate, mouths[0], mouths[1]);
               RoadGeometry.Node start = RoadRibbon.start(candidate);
               RoadGeometry.Node end = RoadRibbon.end(candidate);
               kept = new RoadRecord(
                     id, owner, RampJunctions.at(start.position()), RampJunctions.at(end.position()), start, end, candidate.settings(), false, 4
                  )
                  .alignment(null, candidate)
                  .furniturePhase(old.furniturePhase());
               context = LaneCrossSections.staged(all, id, link, candidate);
            }

            RoadGeometry.Mesh fitted = fitHostContacts(candidate, context, link);
            if (!fitted.samples().equals(candidate.samples())) {
               candidate = fitted;
               RoadGeometry.Node start = RoadRibbon.start(fitted);
               RoadGeometry.Node end = RoadRibbon.end(fitted);
               kept = new RoadRecord(id, owner, RampJunctions.at(start.position()), RampJunctions.at(end.position()), start, end, fitted.settings(), false, 4)
                  .alignment(null, fitted)
                  .furniturePhase(old.furniturePhase());
               context = LaneCrossSections.staged(all, id, link, fitted);
            }

            RoadRecord sourceHost = host(context, link.from());
            LanePoints.Point sourcePoint = LaneTopology.point(sourceHost, link.from().point());
            LaneRampPaths.Port from = link.options().sourceExtra()
               ? extraPort(sourceHost, sourcePoint, 0.0)
               : port(sourceHost, sourcePoint, link.options().lanes());
            LaneRampPaths.Port to = link.to().road() == null ? null : resolvedArrival(context, link, id);
            if (LaneRampAlignment.axis(candidate, true).distance(from.position()) < 1.0E-5
               && LaneRampAlignment.axis(candidate, false).distance(to == null ? link.junctionMouth() : to.position()) < 1.0E-5) {
               if (CURRENT.get() != null && CURRENT.get().retainProfile) {
                  EnumMap<LanePoints.Path, String> errors = new EnumMap<>(LanePoints.Path.class);

                  for (RoadGeometry.Mesh fittedHeight : heightSearch(candidate, context, id, link, errors, link.options().path())) {
                     try {
                        if (data != null && !data.withinHeight(fittedHeight)) {
                           throw new IllegalArgumentException("上跨／下穿超出世界高度范围");
                        }

                        Map<UUID, RoadRecord> finalContext = LaneCrossSections.staged(all, id, link, fittedHeight);
                        validate(fittedHeight, finalContext, id, link);
                        RoadCycleChain.checkRamp(finalContext, id, fittedHeight);

                        for (RoadRecord road : finalContext.values()) {
                           for (LaneSections.Cut cut : LaneTopology.metadata(road).cuts()) {
                              if (cut.connection().equals(id) && !cut.arrival() && cut.temporary()) {
                                 LaneReopening.validateRestored(road.mesh(), cut.lane(), fittedHeight, id);
                              }
                           }
                        }

                        return new LaneRamps.Generated(
                           new RoadRecord(
                                 id,
                                 owner,
                                 RampJunctions.at(RoadRibbon.start(fittedHeight).position()),
                                 RampJunctions.at(RoadRibbon.end(fittedHeight).position()),
                                 RoadRibbon.start(fittedHeight),
                                 RoadRibbon.end(fittedHeight),
                                 fittedHeight.settings(),
                                 false,
                                 4
                              )
                              .alignment(null, fittedHeight)
                              .furniturePhase(old.furniturePhase()),
                           link.options().path()
                        );
                     } catch (IllegalArgumentException var64) {
                        errors.put(link.options().path(), var64.getMessage());
                     }
                  }

                  throw new IllegalArgumentException(errors.toString());
               }

               validate(candidate, context, id, link);
               RoadCycleChain.checkRamp(context, id, candidate);

               for (RoadRecord road : context.values()) {
                  for (LaneSections.Cut cutx : LaneTopology.metadata(road).cuts()) {
                     if (cutx.connection().equals(id) && !cutx.arrival() && cutx.temporary()) {
                        LaneReopening.validateRestored(road.mesh(), cutx.lane(), candidate, id);
                     }
                  }
               }

               return new LaneRamps.Generated(kept, link.options().path());
            }
         } catch (IllegalArgumentException var65) {
            if (Boolean.getBoolean("sr.profile")) {
               System.getLogger("SplineRoads/planner").log(Level.INFO, "SR retained route rejected: " + var65.getMessage());
            }
         }
      }

      LaneRampPaths.Port a = port(source, p, link.options().sourceExtra() ? 1 : link.options().lanes());
      if (link.options().sourceExtra()) {
         a = approach(all, link.from(), 0.0, true, base, link.options().transition());
      }

      LinkedHashSet<Double> offsets = new LinkedHashSet<>();
      LaneRoadChain landingChain = link.to().road() == null ? null : chain(all, link.to());
      if (link.options().landing() != LanePoints.Landing.EXACT && landingChain != null) {
         if (landingChain.contains(link.targetOffset())) {
            offsets.add(Double.valueOf(link.targetOffset()));
         }

         offsets.add(Double.valueOf(0.0));
         double reach = Math.max(-landingChain.low(), landingChain.high());

         for (double step = 8.0; step < reach; step += 8.0) {
            RoadPlanningBudget.check();
            if (landingChain.contains(step)) {
               offsets.add(Double.valueOf(step));
            }

            if (landingChain.contains(-step)) {
               offsets.add(Double.valueOf(-step));
            }
         }

         for (LaneRoadChain.Leg leg : landingChain.legs()) {
            offsets.add(Double.valueOf(leg.low()));
            offsets.add(Double.valueOf(leg.high()));
         }
      } else {
         offsets.add(Double.valueOf(0.0));
      }

      HashMap<Double, Map<UUID, RoadRecord>> stagedOffsets = new HashMap<>();
      LaneRamps.CandidateMemo candidateMemo = new LaneRamps.CandidateMemo();
      LaneRamps.Generated bestProfile = null;
      double bestBend = Double.POSITIVE_INFINITY;
      int compared = 0;
      int compareAttempts = 0;
      EnumMap<LanePoints.Path, String> errors = new EnumMap<>(LanePoints.Path.class);
      String error = "所选范围内没有可用汇入口";
      LaneRampPaths.Port target = null;

      for (int stage = 0; stage < 3; stage++) {
         for (double offset : offsets) {
            try {
               RoadPlanningBudget.check();
               LanePoints.Link actual = link.targetOffset(offset);
               Map<UUID, RoadRecord> contextx = stagedOffsets.get(offset);
               if (contextx == null) {
                  contextx = LaneCrossSections.staged(all, id, actual);
                  stagedOffsets.put(offset, contextx);
               }

               RoadRecord currentSource = host(contextx, actual.from());
               if (!LaneSections.active(mesh(currentSource), lane.station(), p.lane())) {
                  throw new IllegalArgumentException("汇出车道已在此位置分离，不能从车道空位再次汇出");
               }

               double targetLaneWidth = lane.width();
               LaneRoadChain.Position targetPosition = null;
               LaneRampPaths.Port b;
               if (link.to().road() != null) {
                  targetPosition = chain(contextx, link.to()).at(offset);
                  RoadRecord road = targetPosition.road();
                  LanePoints.Point point = targetPosition.point();
                  LanePoints.Lane selectedLane = LanePoints.group(mesh(road), point, link.options().targetExtra() ? 1 : link.options().lanes());
                  if (!rampHost(road)
                     && !link.options().targetExtra()
                     && tunnelAt(road, selectedLane.station())
                     && (existingLink == null || !existingLink.options().equals(link.options()))) {
                     throw new IllegalArgumentException("实际汇入点位于隧道，只允许额外汇入");
                  }

                  if ((link.options().arrival() == LanePoints.Arrival.MERGE || link.options().arrival() == LanePoints.Arrival.FLOW)
                     && !LaneSections.active(mesh(road), selectedLane.station(), point.lane())) {
                     throw new IllegalArgumentException("B 位于已分离的车道空位，不能当作开放车道汇入");
                  }

                  targetLaneWidth = selectedLane.width();
                  b = link.options().targetExtra()
                     ? approach(contextx, link.to(), offset, false, base, link.options().transition())
                     : arrivalPort(road, point, 0.0, actual, id);
               } else {
                  if (link.options().targetExtra()) {
                     throw new IllegalArgumentException("路口中心接入口不设置沿主路的额外汇入车道");
                  }

                  RoadGeometry.V direction = data != null
                     ? RampJunctions.spec(data, link.to().junction()).center().sub(link.junctionMouth()).horizontalUnit()
                     : (
                        CURRENT.get() != null && CURRENT.get().junctionDirection != null
                           ? CURRENT.get().junctionDirection
                           : (old != null ? old.end().direction() : null)
                     );
                  if (direction == null) {
                     throw new IllegalArgumentException("缺少路口接入方向");
                  }

                  b = new LaneRampPaths.Port(link.junctionMouth(), direction, direction.left(), lane.width(), 0.0);
               }

               target = b;
               LanePoints.Data md = (old == null ? LanePoints.Data.EMPTY : LaneTopology.metadata(old)).link(actual).openings(List.of()).bands(List.of());
               RoadGeometry.Settings settings = base.options(base.options().lanePoints(md));

               for (LaneRampPaths.Candidate candidatex : routeCandidates(
                  a,
                  b,
                  settings,
                  link.options(),
                  source,
                  p,
                  targetPosition == null ? null : targetPosition.road(),
                  targetPosition == null ? null : targetPosition.point(),
                  0.0,
                  maxGrade,
                  errors,
                  stage,
                  candidateMemo,
                  offset,
                  chain(contextx, actual.from()),
                  actual.to().road() == null ? null : chain(contextx, actual.to())
               )) {
                  try {
                     if (bestProfile != null) {
                        if (++compareAttempts > 32) {
                           return bestProfile;
                        }
                     }

                     RoadPlanningBudget.check();
                     if (CURRENT.get() != null) {
                        CURRENT.get().routeCount++;
                     }

                     LaneRampAlignment.Mouth sourceMouth = actual.options().sourceExtra()
                        ? LaneRampAlignment.Mouth.extra(lane.width(), RoadProfile.trafficSign(settings.options().leftTraffic()))
                        : LaneRampAlignment.mouth(currentSource.mesh(), LanePoints.group(currentSource.mesh(), p, actual.options().lanes()));
                     LaneRampAlignment.Mouth targetMouth = targetPosition == null
                        ? LaneRampAlignment.Mouth.extra(targetLaneWidth)
                        : (
                           actual.options().targetExtra()
                              ? LaneRampAlignment.Mouth.extra(targetLaneWidth, RoadProfile.trafficSign(settings.options().leftTraffic()))
                              : LaneRampAlignment.mouth(
                                 targetPosition.road().mesh(), LanePoints.group(targetPosition.road().mesh(), targetPosition.point(), actual.options().lanes())
                              )
                        );
                     if (actual.options().arrival() == LanePoints.Arrival.ADD) {
                        RoadGeometry.Mesh host = targetPosition.road().mesh();
                        LaneAdditions.Addition added = LaneAdditions.owned(host, id);
                        targetMouth = LaneRampAlignment.mouth(host, LanePoints.lane(host, added.station(), added.slot()));
                     }

                     RoadGeometry.Mesh baseMesh = fitHostContacts(
                        LaneRampAlignment.fit(
                           candidatex.mesh(), sourceMouth, targetMouth, normalHosts(contextx, actual, true), normalHosts(contextx, actual, false)
                        ),
                        contextx,
                        actual
                     );

                     for (RoadGeometry.Mesh mesh : heightSearch(baseMesh, contextx, id, actual, errors, candidatex.path())) {
                        try {
                           if (data != null && !data.withinHeight(mesh)
                              || data == null
                                 && CURRENT.get() != null
                                 && (
                                    mesh.min().y() - mesh.settings().thickness() < CURRENT.get().minimumHeight
                                       || mesh.max().y() + 4.0 >= CURRENT.get().maximumHeight
                                 )) {
                              throw new IllegalArgumentException("上跨／下穿超出世界高度范围");
                           }

                           Map<UUID, RoadRecord> finalContext = actual.options().departure() != LanePoints.Departure.TEMPORARY && !actual.closesTarget()
                              ? contextx
                              : LaneCrossSections.staged(all, id, actual, mesh);
                           validate(mesh, finalContext, id, actual);
                           RoadCycleChain.checkRamp(finalContext, id, mesh);
                           if (actual.options().departure() == LanePoints.Departure.TEMPORARY) {
                              for (RoadRecord roadx : finalContext.values()) {
                                 for (LaneSections.Cut cutxx : LaneTopology.metadata(roadx).cuts()) {
                                    if (cutxx.connection().equals(id) && !cutxx.arrival()) {
                                       LaneReopening.validateRestored(roadx.mesh(), cutxx.lane(), mesh, id);
                                    }
                                 }
                              }
                           }

                           RoadGeometry.Node start = RoadRibbon.start(mesh);
                           RoadGeometry.Node end = RoadRibbon.end(mesh);
                           RoadRecord record = new RoadRecord(
                                 id, owner, RampJunctions.at(start.position()), RampJunctions.at(end.position()), start, end, mesh.settings(), false, 4
                              )
                              .alignment(null, mesh);
                           if (old != null) {
                              record = record.furniturePhase(old.furniturePhase());
                           }

                           LaneRamps.Generated generated = new LaneRamps.Generated(record, candidatex.path());
                           if (link.options().elevation() != LanePoints.Elevation.AUTO || !link.options().sourceExtra() && !link.options().targetExtra()) {
                              boolean compare = link.options().path() == LanePoints.Path.AUTO
                                 && link.options().elevation() == LanePoints.Elevation.AUTO
                                 && Math.abs(a.position().y() - b.position().y()) > 0.5
                                 && a.direction().dot(b.direction()) > 0.5
                                 && (link.options().separatesLane() && !LaneSections.edge(source.rawMesh(), lane.station(), p.lane()) || actual.closesTarget());
                              if (!compare) {
                                 return generated;
                              }

                              double bend = gradeBends(mesh);
                              if (bend < bestBend) {
                                 bestProfile = generated;
                                 bestBend = bend;
                              }

                              if (!(bestBend < 0.02)) {
                                 if (++compared < 24) {
                                    continue;
                                 }
                              }

                              return bestProfile;
                           } else {
                              if (monotone(mesh)) {
                                 return generated;
                              }

                              double reversal = verticalEffort(mesh) - Math.abs(end.position().y() - start.position().y());
                              if (bestProfile == null || reversal < bestBend) {
                                 bestProfile = generated;
                                 bestBend = reversal;
                              }

                              if (++compared >= 24) {
                                 return bestProfile;
                              }
                           }
                        } catch (IllegalArgumentException var61) {
                           errors.put(candidatex.path(), var61.getMessage());
                           error = var61.getMessage();
                        }
                     }
                  } catch (IllegalArgumentException var62) {
                     errors.put(candidatex.path(), var62.getMessage());
                     error = var62.getMessage();
                  }
               }
            } catch (IllegalArgumentException var63) {
               if (offset == link.targetOffset()) {
                  error = var63.getMessage();
               }
            }
         }
      }

      if (bestProfile != null) {
         return bestProfile;
      } else {
         if (!errors.isEmpty() && target != null) {
            error = LaneRampPaths.failure(a, target, link.options(), errors);
         }

         throw new IllegalArgumentException(
            error + (link.to().road() != null ? (link.options().landing() == LanePoints.Landing.EXACT ? "；汇入点锁定 B" : "；整条连续同车道内未找到满足方向、坡度和净空的汇入口") : "")
         );
      }
   }

   private static double gradeBends(RoadGeometry.Mesh mesh) {
      double previous = 0.0;
      double total = 0.0;
      double peak = 0.0;

      for (int i = 1; i < mesh.samples().size(); i++) {
         RoadGeometry.V delta = mesh.samples().get(i).center().sub(mesh.samples().get(i - 1).center());
         double grade = delta.y() / Math.max(1.0E-8, delta.horizontalLength());
         total += Math.abs(grade - previous);
         peak = Math.max(peak, Math.abs(grade));
         previous = grade;
      }

      return Math.max(0.0, total + Math.abs(previous) - 2.0 * peak);
   }

   private static Iterable<LaneRampPaths.Candidate> routeCandidates(
      LaneRampPaths.Port a,
      LaneRampPaths.Port b,
      RoadGeometry.Settings settings,
      LanePoints.Options options,
      RoadRecord source,
      LanePoints.Point point,
      RoadRecord target,
      LanePoints.Point targetPoint,
      double targetOffset,
      double maxGrade,
      Map<LanePoints.Path, String> errors,
      int stage,
      LaneRamps.CandidateMemo memo,
      double landingOffset,
      LaneRoadChain sourceChain,
      LaneRoadChain targetChain
   ) {
      if (stage == 2 && options.path() != LanePoints.Path.AUTO) {
         return List.of();
      } else {
         boolean innerSource = options.separatesLane()
            && !LaneSections.edge(source.rawMesh(), LanePoints.lane(source.rawMesh(), point).station(), point.lane());
         double[] leads = options.separatesLane()
            ? (innerSource ? new double[]{64.0, 48.0, 32.0, 96.0, 128.0, 0.0} : new double[]{0.0, 32.0, 64.0, 96.0, 128.0})
            : new double[]{0.0};
         boolean tail = target != null
            && (options.arrival() == LanePoints.Arrival.MERGE || options.arrival() == LanePoints.Arrival.FLOW || options.arrival() == LanePoints.Arrival.ADD);
         boolean innerTarget = tail && !LaneSections.edge(target.rawMesh(), LanePoints.lane(target.rawMesh(), targetPoint).station(), targetPoint.lane());
         boolean lateralFirst = (innerSource || innerTarget)
            && Math.abs(a.position().y() - b.position().y()) > 0.5
            && a.direction().dot(b.direction()) > 0.5
            && a.outside().dot(b.outside()) > 0.5;
         double[] tails = tail
            ? (
               lateralFirst
                  ? new double[]{64.0, 96.0, 128.0, 48.0, 32.0, 0.0, 16.0}
                  : (innerTarget ? new double[]{64.0, 32.0, 96.0, 128.0, 0.0, 16.0} : new double[]{0.0, 16.0, 32.0, 48.0, 64.0, 96.0, 128.0})
            )
            : new double[]{0.0};
         return () -> new Iterator<LaneRampPaths.Candidate>() {
               int li;
               int ti;
               int ki;
               final List<LanePoints.Path> kinds = options.path() == LanePoints.Path.AUTO
                  ? (
                     stage == 2
                        ? List.of(LanePoints.Path.LEFT_LOOP)
                        : (
                           lateralFirst
                              ? List.of(LanePoints.Path.RIGHT, LanePoints.Path.DIRECT, LanePoints.Path.LEFT)
                              : List.of(LanePoints.Path.DIRECT, LanePoints.Path.RIGHT, LanePoints.Path.LEFT)
                        )
                  )
                  : List.of(options.path());
               Iterator<LaneRampPaths.Candidate> ready = Collections.emptyIterator();

               @Override
               public boolean hasNext() {
                  while (!this.ready.hasNext() && this.ti < tails.length) {
                     RoadPlanningBudget.check();
                     double lead = leads[this.li];
                     double endLength = tails[this.ti];
                     LanePoints.Path kind = this.kinds.get(this.ki++);
                     if (this.ki == this.kinds.size()) {
                        this.ki = 0;
                        if (++this.li == leads.length) {
                           this.li = 0;
                           this.ti++;
                        }
                     }

                     if ((stage != 0 || lead == leads[0] && endLength == tails[0]) && (stage != 1 || lead != leads[0] || endLength != tails[0])) {
                        LanePoints.Options specific = new LanePoints.Options(
                           kind,
                           options.departure(),
                           options.arrival(),
                           options.radius(),
                           options.transition(),
                           options.elevation(),
                           options.landing(),
                           options.gradeOverride(),
                           options.allowTunnel(),
                           options.lanes()
                        );

                        try {
                           LaneRamps.CandidateKey key = new LaneRamps.CandidateKey(
                              landingOffset,
                              lead,
                              endLength,
                              kind,
                              options.path() == LanePoints.Path.AUTO && kind == LanePoints.Path.LEFT_LOOP,
                              kind == LanePoints.Path.RIGHT || options.path() == LanePoints.Path.LEFT,
                              options.path() != LanePoints.Path.AUTO
                           );
                           this.ready = memo.get(
                                 key,
                                 () -> LaneRamps.routeGroup(
                                       a,
                                       b,
                                       settings,
                                       specific,
                                       source,
                                       point,
                                       target,
                                       targetPoint,
                                       targetOffset,
                                       maxGrade,
                                       lead,
                                       endLength,
                                       key.automatic(),
                                       key.smooth(),
                                       key.constrained(),
                                       sourceChain,
                                       targetChain,
                                       landingOffset
                                    )
                              )
                              .iterator();
                        } catch (IllegalArgumentException var8) {
                           errors.putIfAbsent(kind, var8.getMessage());
                           this.ready = Collections.emptyIterator();
                        }
                     }
                  }

                  return this.ready.hasNext();
               }

               public LaneRampPaths.Candidate next() {
                  if (!this.hasNext()) {
                     throw new NoSuchElementException();
                  } else {
                     return this.ready.next();
                  }
               }
            };
      }
   }

   private static List<LaneRampPaths.Candidate> routeGroup(
      LaneRampPaths.Port a,
      LaneRampPaths.Port b,
      RoadGeometry.Settings settings,
      LanePoints.Options options,
      RoadRecord source,
      LanePoints.Point point,
      RoadRecord target,
      LanePoints.Point targetPoint,
      double targetOffset,
      double maxGrade,
      double leadLength,
      double tailLength,
      boolean automaticTurns,
      boolean smooth,
      boolean constrainSmooth,
      LaneRoadChain sourceChain,
      LaneRoadChain targetChain,
      double landingOffset
   ) {
      ArrayList<RoadGeometry.Sample> before = new ArrayList<>();
      ArrayList<RoadGeometry.Sample> after = new ArrayList<>();
      if (leadLength > 0.0) {
         for (double d = 0.0; d <= leadLength; d += 0.5) {
            LaneRoadChain.Position pos = sourceChain.at(d);
            LanePoints.Lane q = LanePoints.group(pos.road().rawMesh(), pos.point(), options.lanes());
            before.add(new RoadGeometry.Sample(q.position(), q.direction().left(), d, settings.width() / 2.0));
         }

         RoadGeometry.Sample last = before.get(before.size() - 1);
         RoadGeometry.V delta = last.center().sub(before.get(before.size() - 2).center());
         RoadGeometry.V direction = last.left().left().mul(-1.0);
         a = new LaneRampPaths.Port(
            last.center(),
            direction,
            direction.left().mul(Math.signum(a.outside().dot(a.direction().left()))),
            a.extraWidth(),
            delta.y() / Math.max(0.001, delta.horizontalLength())
         );
      }

      if (tailLength > 0.0 && options.arrival() != LanePoints.Arrival.ADD) {
         LaneRoadChain.Position pos = targetChain.at(landingOffset - tailLength);
         LanePoints.Lane q = LanePoints.group(pos.road().rawMesh(), pos.point(), options.lanes());
         LaneRoadChain.Position next = targetChain.at(landingOffset - tailLength + 0.5);
         LanePoints.Lane following = LanePoints.group(next.road().rawMesh(), next.point(), options.lanes());
         RoadGeometry.V delta = following.position().sub(q.position());
         b = new LaneRampPaths.Port(
            q.position(),
            q.direction(),
            q.direction().left().mul(Math.signum(b.outside().dot(b.direction().left()))),
            b.extraWidth(),
            delta.y() / Math.max(0.001, delta.horizontalLength())
         );

         for (double d = 0.5; d <= tailLength + 0.001; d += 0.5) {
            LaneRoadChain.Position at = targetChain.at(landingOffset - tailLength + d);
            LanePoints.Lane lane = LanePoints.group(at.road().rawMesh(), at.point(), options.lanes());
            after.add(new RoadGeometry.Sample(lane.position(), lane.direction().left(), d, settings.width() / 2.0));
         }
      } else if (tailLength > 0.0) {
         RoadGeometry.Mesh raw = target.rawMesh();
         LanePoints.Lane lane = LanePoints.lane(raw, targetPoint);
         double end = lane.station() + (double)lane.sign() * targetOffset;
         double start = end - (double)lane.sign() * tailLength;
         if (start <= 0.5 || start >= raw.length() - 0.5) {
            return List.of();
         }

         int slot = targetPoint.lane();
         if (options.arrival() == LanePoints.Arrival.ADD) {
            Optional<LaneAdditions.Addition> best = raw.settings()
               .options()
               .lanePoints()
               .additions()
               .stream()
               .filter(v -> v.sign() == lane.sign() && Math.abs(v.station() - end) < 1.0E-5)
               .reduce((x, yx) -> yx);
            if (best.isEmpty()) {
               return List.of();
            }

            slot = best.get().slot();
            if (best.get().fraction(start) < 0.999999) {
               return List.of();
            }
         }

         LanePoints.Lane q = LanePoints.lane(raw, start, slot);
         LanePoints.Lane prev = LanePoints.lane(raw, start - (double)lane.sign() * 0.5, slot);
         RoadGeometry.V delta = q.position().sub(prev.position());
         b = new LaneRampPaths.Port(q.position(), q.direction(), b.outside(), b.extraWidth(), delta.y() / Math.max(0.001, delta.horizontalLength()));

         for (double d = 0.5; d <= tailLength + 0.001; d += 0.5) {
            LanePoints.Lane at = LanePoints.lane(raw, start + (double)lane.sign() * d, slot);
            after.add(new RoadGeometry.Sample(at.position(), at.direction().left(), d, settings.width() / 2.0));
         }
      }

      boolean wholeProfile = options.separatesLane()
         && options.arrival() == LanePoints.Arrival.MERGE
         && options.elevation() != LanePoints.Elevation.KEEP
         && (!before.isEmpty() || !after.isEmpty());
      double endHeight = b.position().y();
      double endGrade = b.grade();
      double startGrade = a.grade();
      if (wholeProfile) {
         b = new LaneRampPaths.Port(
            new RoadGeometry.V(b.position().x(), a.position().y(), b.position().z()), b.direction(), b.outside(), b.extraWidth(), a.grade()
         );
      }

      List<LaneRampPaths.Candidate> smoothCandidates = smooth
         ? LaneRampPaths.smoothTurns(a, b, settings, options, maxGrade, Math.abs(endHeight - a.position().y()) > 0.5)
            .stream()
            .filter(cx -> !constrainSmooth || matchesTurn(cx.mesh(), options.path()))
            .map(cx -> constrainSmooth ? new LaneRampPaths.Candidate(options.path(), cx.mesh()) : cx)
            .toList()
         : List.of();
      ArrayList<LaneRampPaths.Candidate> candidates = new ArrayList<>();
      if (automaticTurns) {
         candidates.addAll(LaneRampPaths.automaticTurns(a, b, settings, options, maxGrade));
      }

      try {
         candidates.addAll(LaneRampPaths.candidates(a, b, settings, options, maxGrade));
      } catch (IllegalArgumentException var53) {
         if (candidates.isEmpty() && smoothCandidates.isEmpty()) {
            throw var53;
         }
      }

      candidates.sort(Comparator.comparingDouble(cx -> cx.mesh().length()));
      double shortest = candidates.stream().mapToDouble(cx -> cx.mesh().length()).min().orElse(Double.POSITIVE_INFINITY);
      candidates.addAll(0, smoothCandidates.stream().filter(cx -> cx.mesh().length() <= shortest * 1.35).toList());
      if (before.isEmpty() && after.isEmpty()) {
         return candidates;
      } else {
         ArrayList<LaneRampPaths.Candidate> out = new ArrayList<>();

         for (LaneRampPaths.Candidate c : candidates) {
            try {
               ArrayList<RoadGeometry.Sample> samples = new ArrayList<>(before);
               samples.addAll(before.isEmpty() ? c.mesh().samples() : c.mesh().samples().subList(1, c.mesh().samples().size()));
               samples.addAll(after);
               if (wholeProfile) {
                  double length = 0.0;

                  for (int i = 1; i < samples.size(); i++) {
                     length += samples.get(i).center().sub(samples.get(i - 1).center()).horizontalLength();
                  }

                  double fromY = samples.get(0).center().y();
                  double toY = after.isEmpty() ? endHeight : after.get(after.size() - 1).center().y();
                  double station = 0.0;

                  for (int i = 0; i < samples.size(); i++) {
                     RoadGeometry.Sample s = samples.get(i);
                     if (i > 0) {
                        station += s.center().sub(samples.get(i - 1).center()).horizontalLength();
                     }

                     double y = LaneRampProfile.height(fromY, toY, startGrade, endGrade, length, station, maxGrade);
                     samples.set(i, new RoadGeometry.Sample(new RoadGeometry.V(s.center().x(), y, s.center().z()), s.left(), station, s.halfWidth()));
                  }
               }

               RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, settings);
               RoadRibbon.checkSelfIntersections(mesh, 4.0);
               out.add(new LaneRampPaths.Candidate(c.path(), mesh));
            } catch (IllegalArgumentException var52) {
            }
         }

         return out;
      }
   }

   static boolean matchesTurn(RoadGeometry.Mesh mesh, LanePoints.Path path) {
      double turn = 0.0;
      List<RoadGeometry.Sample> samples = mesh.samples();

      for (int i = 1; i < samples.size(); i++) {
         RoadGeometry.V a = samples.get(i - 1).left();
         RoadGeometry.V b = samples.get(i).left();
         turn += Math.atan2(a.x() * b.z() - a.z() * b.x(), a.dot(b));
      }

      return path == LanePoints.Path.RIGHT
         ? turn >= -0.001 && turn <= 3.142592653589793
         : (path == LanePoints.Path.LEFT ? turn <= 0.001 && turn >= -3.142592653589793 : true);
   }

   private static LaneRampPaths.Port approach(
      Map<UUID, RoadRecord> all, LanePoints.Ref ref, double offset, boolean source, RoadGeometry.Settings settings, double transition
   ) {
      LaneRoadChain route = chain(all, ref);
      LaneRamps.Planning plan = CURRENT.get();
      if (plan == null) {
         return buildApproach(route, offset, source, settings, transition);
      } else {
         if (plan.approaches.size() > 128) {
            plan.approaches.clear();
         }

         Map<LaneRamps.ApproachKey, LaneRampPaths.Port> cache = plan.approaches.computeIfAbsent(route, k -> new HashMap<>());
         LaneRamps.ApproachKey key = new LaneRamps.ApproachKey(
            offset, source, settings.width(), RoadProfile.layout(settings, settings.width()).motorCenter(), transition
         );
         return cache.computeIfAbsent(key, k -> buildApproach(route, offset, source, settings, transition));
      }
   }

   private static LaneRampPaths.Port buildApproach(LaneRoadChain route, double offset, boolean source, RoadGeometry.Settings settings, double transition) {
      LaneRoadChain.Position location = route.at(offset);
      RoadRecord road = location.road();
      LanePoints.Point point = location.point();
      boolean tunnel = tunnelAt(road, location.station());
      double length = (tunnel ? Math.max(64.0, transition * 2.0) : LaneRampApproach.taper(transition)) + LaneRampApproach.parallel(transition);
      LaneRampPaths.Port port = extraPort(road, point, 0.0);
      List<RoadGeometry.Sample> samples = LaneRampApproach.build(d -> {
         LaneRoadChain.Position next = route.at(offset + (source ? d : d - length));
         return new LaneRampApproach.Station(mesh(next.road()), next.point().lane(), next.station());
      }, source, settings, transition, tunnel);
      return new LaneRampPaths.Port(port.position(), port.direction(), port.outside(), port.extraWidth(), port.grade(), samples);
   }

   static Set<UUID> contactRoads(Map<UUID, RoadRecord> all, LanePoints.Ref ref) {
      LaneRamps.Planning plan = CURRENT.get();
      if (plan == null) {
         return findContactRoads(all, ref);
      } else {
         if (plan.connected.size() > 64) {
            plan.connected.clear();
         }

         return plan.connected.computeIfAbsent(all, k -> new HashMap<>()).computeIfAbsent(ref, k -> findContactRoads(all, ref));
      }
   }

   private static LaneRoadChain chain(Map<UUID, RoadRecord> all, LanePoints.Ref ref) {
      LaneRamps.Planning p = CURRENT.get();
      if (p == null) {
         return LaneRoadChain.of(all, ref);
      } else {
         if (p.chains.size() > 64) {
            p.chains.clear();
         }

         return p.chains.computeIfAbsent(all, k -> new HashMap<>()).computeIfAbsent(ref, k -> LaneRoadChain.of(all, k));
      }
   }

   private static Set<UUID> findContactRoads(Map<UUID, RoadRecord> all, LanePoints.Ref ref) {
      return ref.road() == null ? Set.of() : chain(all, ref).ids();
   }

   static int contactEnd(RoadGeometry.Mesh mesh, Map<UUID, RoadRecord> all, Set<UUID> hosts, boolean first) {
      int n = mesh.samples().size();
      int i = first ? 0 : n - 1;

      for (List<RoadGeometry.Mesh> roads = hosts.stream().map(all::get).filter(Objects::nonNull).map(LaneRamps::mesh).toList();
         i >= 0 && i < n;
         i += first ? 1 : -1
      ) {
         RoadGeometry.Sample sample = mesh.samples().get(i);
         boolean touching = false;

         for (RoadGeometry.Mesh host : roads) {
            RoadQueries.Projection q = RoadQueries.horizontal(host, sample.center());
            if (q.horizontalDistance() < q.sample().halfWidth() + sample.halfWidth() + 0.15) {
               touching = true;
               break;
            }
         }

         if (!touching) {
            break;
         }
      }

      return Math.max(0, Math.min(n - 1, i + (first ? 2 : -2)));
   }

   private static RoadGeometry.Mesh fitHostContacts(RoadGeometry.Mesh mesh, Map<UUID, RoadRecord> all, LanePoints.Link link) {
      if (link.protectedMerge()) {
         if (link.options().departure() == LanePoints.Departure.BRANCH || link.options().sourceExtra() && highwayHost(all, link.from())) {
            mesh = LaneRampThroat.fit(mesh, normalHosts(all, link, true), true);
         }

         if (link.options().arrival() == LanePoints.Arrival.FLOW || link.options().targetExtra() && highwayHost(all, link.to())) {
            mesh = LaneRampThroat.fit(mesh, normalHosts(all, link, false), false);
         }

         return mesh;
      } else {
         Set<UUID> sourceIds = contactRoads(all, link.from());
         Set<UUID> targetIds = contactRoads(all, link.to());
         List<RoadGeometry.Mesh> source = sourceIds.stream().map(all::get).filter(Objects::nonNull).map(LaneRamps::mesh).toList();
         List<RoadGeometry.Mesh> target = targetIds.stream().map(all::get).filter(Objects::nonNull).map(LaneRamps::mesh).toList();
         double from = link.options().separatesLane() ? 0.0 : mesh.samples().get(contactEnd(mesh, all, sourceIds, true)).distance();
         double to = link.closesTarget() ? mesh.length() : mesh.samples().get(contactEnd(mesh, all, targetIds, false)).distance();
         if (from >= to) {
            return mesh;
         } else {
            double ease = Math.min(200.0, Math.max(2.0, (to - from) / 3.0));
            ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

            for (RoadGeometry.Sample sample : mesh.samples()) {
               double ws = !source.isEmpty() && !link.options().separatesLane()
                  ? 1.0 - RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, (sample.distance() - from) / ease)))
                  : 0.0;
               double wt = !target.isEmpty() && !link.closesTarget()
                  ? 1.0 - RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, (to - sample.distance()) / ease)))
                  : 0.0;
               double dy = ((ws > 0.0 ? ws * hostHeightDelta(sample, source) : 0.0) + (wt > 0.0 ? wt * hostHeightDelta(sample, target) : 0.0))
                  / Math.max(1.0, ws + wt);
               samples.add(new RoadGeometry.Sample(sample.center().add(new RoadGeometry.V(0.0, dy, 0.0)), sample.left(), sample.distance(), sample.halfWidth()));
            }

            return RoadRibbon.mesh(samples, mesh.settings());
         }
      }
   }

   private static boolean highwayHost(Map<UUID, RoadRecord> all, LanePoints.Ref ref) {
      if (ref.road() == null) {
         return false;
      } else {
         RoadRecord r = all.get(ref.road());
         return r != null && RoadProfile.highway(r.settings().style());
      }
   }

   private static List<RoadGeometry.Mesh> normalHosts(Map<UUID, RoadRecord> all, LanePoints.Link link, boolean first) {
      LanePoints.Ref ref = first ? link.from() : link.to();
      if (ref.road() == null) {
         return List.of();
      } else {
         ArrayList<RoadGeometry.Mesh> out = new ArrayList<>();

         for (LaneRoadChain.Leg leg : chain(all, ref).legs()) {
            RoadGeometry.Mesh host = mesh(leg.road());
            if (RoadProfile.catalog(host.settings()).lanes() == 1) {
               out.add(host);
            } else {
               ArrayList<RoadGeometry.Sample> points = new ArrayList<>();

               for (RoadGeometry.Sample at : host.samples()) {
                  LanePoints.Lane lane = (first ? !link.options().sourceExtra() : !link.options().targetExtra())
                     ? LanePoints.group(host, at.distance(), leg.slot(), link.options().lanes())
                     : LaneRampApproach.outerLane(host, at.distance(), leg.slot());
                  double center = lane.position().sub(at.center()).dot(at.left());
                  double low = center - lane.width() / 2.0;
                  double high = center + lane.width() / 2.0;
                  if (RoadProfile.highway(host.settings().style())) {
                     RoadProfile.Layout layout = RoadProfile.layout(host, at);
                     if (Math.abs(low - layout.motorMin()) < 0.01) {
                        low = -at.halfWidth();
                     }

                     if (Math.abs(high - layout.motorMax()) < 0.01) {
                        high = at.halfWidth();
                     }
                  }

                  points.add(new RoadGeometry.Sample(at.at((low + high) / 2.0, 0.0), at.left(), at.distance(), (high - low) / 2.0));
               }

               out.add(
                  RoadRibbon.mesh(
                     points, new RoadGeometry.Settings(RoadGeometry.Mode.CURVE, RoadGeometry.Style.C1_RAMP, 4.0, host.settings().thickness(), 0.35, 90.0)
                  )
               );
            }
         }

         for (RoadRecord road : all.values()) {
            LanePoints.Link other = LaneTopology.metadata(road).link();
            if (other != null) {
               boolean sibling = first
                  ? other.from().equals(link.from()) && !other.to().equals(link.to()) && other.options().departure() == LanePoints.Departure.BRANCH
                  : other.to().equals(link.to()) && !other.from().equals(link.from()) && other.options().arrival() == LanePoints.Arrival.FLOW;
               if (sibling) {
                  out.add(mesh(road));
               }
            }
         }

         return List.copyOf(out);
      }
   }

   private static double hostHeightDelta(RoadGeometry.Sample sample, List<RoadGeometry.Mesh> hosts) {
      double distance = Double.POSITIVE_INFINITY;
      double delta = 0.0;

      for (RoadGeometry.Mesh host : hosts) {
         RoadQueries.Projection q = RoadQueries.horizontal(host, sample.center());
         if (q.horizontalDistance() < distance) {
            distance = q.horizontalDistance();
            delta = q.sample().center().y() - sample.center().y();
         }
      }

      return delta;
   }

   private static List<LaneRamps.Obstacle> crossings(RoadGeometry.Mesh mesh, Map<UUID, RoadRecord> all, UUID id, LanePoints.Link link) {
      return crossings(mesh, all, id, link, null);
   }

   private static List<LaneRamps.Obstacle> crossings(RoadGeometry.Mesh mesh, Map<UUID, RoadRecord> all, UUID id, LanePoints.Link link, Set<UUID> changed) {
      return crossings(mesh, all, id, link, changed, false);
   }

   private static List<LaneRamps.Obstacle> crossings(
      RoadGeometry.Mesh mesh, Map<UUID, RoadRecord> all, UUID id, LanePoints.Link link, Set<UUID> changed, boolean firstConflict
   ) {
      ArrayList<LaneRamps.Obstacle> out = new ArrayList<>();
      Set<UUID> sourceHosts = contactRoads(all, link.from());
      Set<UUID> targetHosts = contactRoads(all, link.to());
      double sourceLimit = mesh.samples().get(contactEnd(mesh, all, sourceHosts, true)).distance();
      double targetLimit = mesh.samples().get(contactEnd(mesh, all, targetHosts, false)).distance();

      for (RoadRecord road : all.values()) {
         RoadPlanningBudget.check();
         if ((changed == null || changed.contains(road.id()))
            && !road.id().equals(id)
            && (road.junction() == null || !Objects.equals(road.assembly(), link.to().junction()))) {
            LanePoints.Link other = LaneTopology.metadata(road).link();
            if (other == null || !id.equals(other.from().road()) && !id.equals(other.to().road())) {
               RoadGeometry.Mesh old = mesh(road);
               double sharedStart = -1.0;
               double sharedEnd = mesh.length() + 1.0;
               if (other != null && link.from().equals(other.from())) {
                  sharedStart = mesh.samples().get(contactEnd(mesh, Map.of(road.id(), road), Set.of(road.id()), true)).distance();
               }

               if (other != null && link.to().equals(other.to())) {
                  sharedEnd = mesh.samples().get(contactEnd(mesh, Map.of(road.id(), road), Set.of(road.id()), false)).distance();
               }

               if (sharedStart > mesh.length() * 0.9 || sharedEnd < mesh.length() * 0.1) {
                  throw new IllegalArgumentException("同一车道点的两条匝道几乎全程重合，请使用不同汇入方向");
               }

               if (RoadIndex.overlapXZ(mesh, old, 8.0)) {
                  for (RoadStructures.Part part : routingStructures(mesh, road, all, sourceHosts, targetHosts)) {
                     for (RoadClearance.Contact c : RoadClearance.structureContacts(part, mesh, 4.25)) {
                        if ((!sourceHosts.contains(road.id()) || !(c.to() <= sourceLimit + 0.01))
                           && (!targetHosts.contains(road.id()) || !(c.from() >= targetLimit - 0.01))
                           && !(c.to() <= sharedStart + 0.01)
                           && !(c.from() >= sharedEnd - 0.01)
                           && addObstacle(out, new LaneRamps.Obstacle(road.id(), c, true), all, firstConflict)) {
                           return out;
                        }
                     }
                  }
               }

               if (RoadIndex.overlapXZ(mesh, old, 0.0)) {
                  LinkedHashSet<Integer> exactSlots = new LinkedHashSet<>();
                  if (link.protectedMerge()) {
                     for (LaneRoadChain.Leg leg : chain(all, link.from()).legs()) {
                        if (leg.road().id().equals(road.id())) {
                           if (link.options().sourceExtra()) {
                              exactSlots.add(Integer.valueOf(LaneRampApproach.outerLane(old, leg.station(0.0), leg.slot()).index()));
                           } else {
                              for (int n = 0; n < link.options().lanes(); n++) {
                                 exactSlots.add(Integer.valueOf(leg.slot() + n));
                              }
                           }
                        }
                     }

                     if (link.to().road() != null) {
                        for (LaneRoadChain.Leg legx : chain(all, link.to()).legs()) {
                           if (legx.road().id().equals(road.id())) {
                              if (link.options().arrival() == LanePoints.Arrival.ADD) {
                                 for (LaneAdditions.Addition added : LaneTopology.metadata(road).additions()) {
                                    if (added.connection().equals(id)) {
                                       exactSlots.add(Integer.valueOf(added.slot()));
                                    }
                                 }
                              } else if (link.options().targetExtra()) {
                                 exactSlots.add(Integer.valueOf(LaneRampApproach.outerLane(old, legx.station(link.targetOffset()), legx.slot()).index()));
                              } else {
                                 for (int n = 0; n < link.options().lanes(); n++) {
                                    exactSlots.add(Integer.valueOf(legx.slot() + n));
                                 }
                              }
                           }
                        }
                     }
                  }

                  if (!exactSlots.isEmpty()) {
                     RoadGeometry.Mesh protectedDeck = protectedDeck(old, exactSlots);

                     for (RoadClearance.Contact cx : contacts(mesh, protectedDeck)) {
                        if (addObstacle(out, new LaneRamps.Obstacle(road.id(), cx), all, firstConflict)) {
                           return out;
                        }
                     }
                  }

                  boolean extraSource = link.options().sourceExtra() && sourceHosts.contains(road.id());
                  boolean extraTarget = link.options().targetExtra() && targetHosts.contains(road.id());
                  if (extraSource || extraTarget) {
                     double begin = extraSource ? fixedApproach(mesh, all, link, true) : -1.0;
                     double end = extraTarget ? mesh.length() - fixedApproach(mesh, all, link, false) : mesh.length() + 1.0;

                     for (RoadClearance.Contact cxx : contacts(mesh, protectedDeck(old, Set.of()))) {
                        if ((!extraSource || !(cxx.to() <= begin + 0.01))
                           && (!extraTarget || !(cxx.from() >= end - 0.01))
                           && addObstacle(out, new LaneRamps.Obstacle(road.id(), cxx), all, firstConflict)) {
                           return out;
                        }
                     }
                  }

                  for (RoadClearance.Contact cxxx : contacts(mesh, old)) {
                     boolean sourceJoin = sourceHosts.contains(road.id()) && cxxx.to() <= sourceLimit + 0.01;
                     if (sourceJoin && link.options().separatesLane()) {
                        sourceJoin = separationThroat(cxxx, road, link, all);
                     }

                     if (!sourceJoin
                        && (!targetHosts.contains(road.id()) || !(cxxx.from() >= targetLimit - 0.01))
                        && !(cxxx.to() <= sharedStart + 0.01)
                        && !(cxxx.from() >= sharedEnd - 0.01)
                        && addObstacle(out, new LaneRamps.Obstacle(road.id(), cxxx), all, firstConflict)) {
                        return out;
                     }
                  }
               }
            }
         }
      }

      return out;
   }

   private static boolean addObstacle(List<LaneRamps.Obstacle> out, LaneRamps.Obstacle obstacle, Map<UUID, RoadRecord> all, boolean firstConflict) {
      out.add(obstacle);
      return firstConflict && obstacle.contact().blocked();
   }

   private static RoadGeometry.Mesh protectedDeck(RoadGeometry.Mesh host, Set<Integer> slots) {
      List<Integer> key = List.copyOf(slots);
      LaneRamps.Planning plan = CURRENT.get();
      if (plan == null) {
         return makeProtectedDeck(host, key);
      } else {
         if (plan.protectedDecks.size() > 128) {
            plan.protectedDecks.clear();
         }

         return plan.protectedDecks.computeIfAbsent(host, k -> new HashMap<>()).computeIfAbsent(key, k -> makeProtectedDeck(host, (List<Integer>)k));
      }
   }

   private static RoadGeometry.Mesh makeProtectedDeck(RoadGeometry.Mesh host, List<Integer> slots) {
      RoadGeometry.Mesh result = LaneDeck.motorOnly(host);

      for (int slot : slots) {
         result = LaneDeck.excludingSlot(result, slot);
      }

      return result;
   }

   private static boolean separationThroat(RoadClearance.Contact contact, RoadRecord road, LanePoints.Link link, Map<UUID, RoadRecord> all) {
      for (LaneRoadChain.Leg leg : chain(all, link.from()).legs()) {
         if (leg.road().id().equals(road.id())) {
            RoadGeometry.Mesh raw = road.rawMesh();
            RoadQueries.Projection q = RoadQueries.horizontal(raw, contact.ours());
            LanePoints.Lane lane = LanePoints.group(raw, q.sample().distance(), leg.slot(), link.options().lanes());
            RoadGeometry.Sample at = q.sample();
            RoadProfile.Layout layout = RoadProfile.layout(raw, at);
            double center = lane.position().sub(at.center()).dot(at.left());
            double low = center - lane.width() / 2.0;
            double high = center + lane.width() / 2.0;
            if (Math.abs(low - layout.motorMin()) < 1.0E-5) {
               low = -at.halfWidth();
            }

            if (Math.abs(high - layout.motorMax()) < 1.0E-5) {
               high = at.halfWidth();
            }

            double offset = contact.ours().sub(at.center()).dot(at.left());
            if (link.protectedMerge()) {
               low = Math.min(low, layout.motorMin());
               high = Math.max(high, layout.motorMax());
            }

            return leg.coordinate(lane.station()) >= -0.25 && offset >= low - 1.0E-5 && offset <= high + 1.0E-5;
         }
      }

      return false;
   }

   static LaneRampPaths.Port resolvedArrival(Map<UUID, RoadRecord> all, LanePoints.Link link, UUID id) {
      LaneRoadChain.Position position = chain(all, link.to()).at(link.targetOffset());
      return arrivalPort(position.road(), position.point(), 0.0, link, id);
   }

   private static double[] terrainFloors(RoadGeometry.Mesh mesh, LanePoints.Link link) {
      return LaneRampTerrain.floors(mesh, link.options(), CURRENT.get() == null ? null : CURRENT.get().terrain);
   }

   private static Iterable<RoadGeometry.Mesh> heightSearch(
      RoadGeometry.Mesh base, Map<UUID, RoadRecord> all, UUID id, LanePoints.Link link, Map<LanePoints.Path, String> errors, LanePoints.Path path
   ) {
      return () -> new Iterator<RoadGeometry.Mesh>() {
            Iterator<RoadGeometry.Mesh> current = LaneRamps.heightCandidates(base, all, id, link, errors, path, false, false).iterator();
            boolean fallback;

            @Override
            public boolean hasNext() {
               if (this.current.hasNext()) {
                  return true;
               } else if (this.fallback) {
                  return false;
               } else {
                  this.fallback = true;
                  this.current = LaneRamps.heightCandidates(base, all, id, link, errors, path, false, true).iterator();
                  return this.current.hasNext();
               }
            }

            public RoadGeometry.Mesh next() {
               if (!this.hasNext()) {
                  throw new NoSuchElementException();
               } else {
                  return this.current.next();
               }
            }
         };
   }

   private static List<RoadGeometry.Mesh> heightCandidates(
      RoadGeometry.Mesh base,
      Map<UUID, RoadRecord> all,
      UUID id,
      LanePoints.Link link,
      Map<LanePoints.Path, String> errors,
      LanePoints.Path path,
      boolean monotoneOnly
   ) {
      ArrayList<RoadGeometry.Mesh> result = new ArrayList<>(heightCandidates(base, all, id, link, errors, path, monotoneOnly, false));
      result.addAll(heightCandidates(base, all, id, link, errors, path, monotoneOnly, true));
      if (link.options().elevation() == LanePoints.Elevation.AUTO) {
         result.sort(heightOrder(base));
      }

      return result;
   }

   private static List<RoadGeometry.Mesh> heightCandidates(
      RoadGeometry.Mesh base,
      Map<UUID, RoadRecord> all,
      UUID id,
      LanePoints.Link link,
      Map<LanePoints.Path, String> errors,
      LanePoints.Path path,
      boolean monotoneOnly,
      boolean fallbackOnly
   ) {
      LanePoints.Elevation mode = link.options().elevation();
      ArrayList<RoadGeometry.Mesh> out = new ArrayList<>();
      double flatY = base.first().center().y();
      boolean fixedFlat = monotoneOnly && base.last().center().y() == flatY && base.samples().stream().allMatch(s -> s.center().y() == flatY);
      List<LaneRamps.Obstacle> contacts = crossings(base, all, id, link, null, fixedFlat);
      double[] floors = terrainFloors(base, link);
      boolean earthClear = LaneRampTerrain.clear(base, floors);
      boolean clear = contacts.stream().noneMatch(c -> c.contact().blocked());
      if (!fixedFlat || clear && earthClear) {
         if (mode == LanePoints.Elevation.KEEP) {
            return List.of(base);
         } else if (mode != LanePoints.Elevation.AUTO || !clear || !earthClear || monotoneOnly && !monotone(base)) {
            if (!contacts.isEmpty() || !earthClear || monotoneOnly && !monotone(base)) {
               double from = link.protectedMerge()
                  ? fixedApproach(base, all, link, true)
                  : (link.options().separatesLane() ? 0.0 : base.samples().get(contactEnd(base, all, contactRoads(all, link.from()), true)).distance());
               double to = link.protectedMerge()
                  ? base.length() - fixedApproach(base, all, link, false)
                  : (link.closesTarget() ? base.length() : base.samples().get(contactEnd(base, all, contactRoads(all, link.to()), false)).distance());
               if (fallbackOnly) {
                  return contacts.isEmpty() ? List.of() : mixedCandidates(base, from, to, contacts, all, gradeLimit(all, link), monotoneOnly, floors, mode);
               } else {
                  for (boolean over : mode == LanePoints.Elevation.UNDER
                     ? new boolean[]{false}
                     : (mode == LanePoints.Elevation.OVER ? new boolean[]{true} : new boolean[]{true, false})) {
                     ArrayList<LaneRampCorridor.Bound> constraints = new ArrayList<>();

                     for (LaneRamps.Obstacle obstacle : contacts) {
                        RoadClearance.Contact c = obstacle.contact();
                        constraints.add(new LaneRampCorridor.Bound(c.from(), c.to(), over ? c.raiseBound() : c.lowerBound(), over));
                     }

                     try {
                        out.add(LaneRampCorridor.solveMixed(base, from, to, constraints, gradeLimit(all, link), monotoneOnly, floors));
                     } catch (IllegalArgumentException var39) {
                        errors.put(path, var39.getMessage());
                     }
                  }

                  if (mode == LanePoints.Elevation.AUTO) {
                     ArrayList<LaneRampCorridor.Bound> mixed = new ArrayList<>();
                     double grade = gradeLimit(all, link);

                     for (LaneRamps.Obstacle obstacle : contacts) {
                        RoadClearance.Contact c = obstacle.contact();
                        RoadRecord other = all.get(obstacle.road());
                        LanePoints.Link saved = other != null && !obstacle.structure() ? LaneTopology.metadata(other).link() : null;
                        double d = Math.max(0.0, Math.min(base.length(), (c.from() + c.to()) / 2.0));
                        RoadGeometry.Sample at = RoadStructures.sample(base, d);
                        double reachLow = Math.max(base.first().center().y() - grade * d, base.last().center().y() - grade * (base.length() - d));
                        double reachHigh = Math.min(base.first().center().y() + grade * d, base.last().center().y() + grade * (base.length() - d));
                        boolean up = at.center().y() + c.raiseBound() <= reachHigh + 1.0E-6;
                        boolean down = at.center().y() - c.lowerBound() >= reachLow - 1.0E-6;
                        boolean over = saved != null && saved.options().elevation() == LanePoints.Elevation.UNDER
                           || (saved == null || saved.options().elevation() != LanePoints.Elevation.OVER)
                              && (up && !down || up == down && (c.blocked() ? c.raiseBound() <= c.lowerBound() : c.ours().y() >= c.other().y()));
                        mixed.add(new LaneRampCorridor.Bound(c.from(), c.to(), over ? c.raiseBound() : c.lowerBound(), over));
                     }

                     try {
                        out.add(LaneRampCorridor.solveMixed(base, from, to, mixed, grade, monotoneOnly, floors));
                     } catch (IllegalArgumentException var41) {
                        if (out.isEmpty()) {
                           errors.put(path, var41.getMessage() + obstacleSummary(base, contacts));
                        }
                     }
                  }

                  if (out.isEmpty() && !contacts.isEmpty()) {
                     errors.put(path, errors.getOrDefault(path, "没有满足净空的纵坡方案") + obstacleSummary(base, contacts));
                  }

                  if (out.isEmpty() && !errors.containsKey(path)) {
                     errors.put(path, "没有满足端点、坡度及净空的自动跨越方案");
                  }

                  if (mode == LanePoints.Elevation.AUTO) {
                     out.sort(heightOrder(base));
                  }

                  return out;
               }
            } else {
               return List.of(base);
            }
         } else {
            if (!monotone(base)) {
               try {
                  double start = link.protectedMerge()
                     ? fixedApproach(base, all, link, true)
                     : (link.options().separatesLane() ? 0.0 : base.samples().get(contactEnd(base, all, contactRoads(all, link.from()), true)).distance());
                  double end = link.protectedMerge()
                     ? base.length() - fixedApproach(base, all, link, false)
                     : (link.closesTarget() ? base.length() : base.samples().get(contactEnd(base, all, contactRoads(all, link.to()), false)).distance());
                  RoadGeometry.Mesh relaxed = LaneRampCorridor.solveMixed(base, start, end, List.of(), gradeLimit(all, link), true, floors);
                  return List.of(relaxed, base);
               } catch (IllegalArgumentException var40) {
               }
            }

            return List.of(base);
         }
      } else {
         errors.put(path, "等高端口的单调候选与实际障碍冲突" + obstacleSummary(base, contacts));
         return List.of();
      }
   }

   private static List<RoadStructures.Part> routingStructures(
      RoadGeometry.Mesh mesh, RoadRecord road, Map<UUID, RoadRecord> all, Set<UUID> source, Set<UUID> target
   ) {
      boolean start = source.contains(road.id());
      boolean end = target.contains(road.id());
      List<RoadStructures.Part> structures = !start && !end
         ? road.structures()
         : road.structures().stream().filter(p -> !RoadClearance.joiningFloor(p, road.mesh(), mesh)).toList();
      if (road.settings().structure() == RoadGeometry.Structure.TUNNEL && (start || end)) {
         RoadGeometry.Mesh lining = RoadRibbon.mesh(
            mesh.samples().stream().map(s -> new RoadGeometry.Sample(s.center(), s.left(), s.distance(), s.halfWidth() + 1.55)).toList(), mesh.settings()
         );
         ArrayList<RoadShellOpening> openings = new ArrayList<>();
         if (start) {
            openings.add(new RoadShellOpening(mesh, 0.0, lining.samples().get(contactEnd(lining, all, source, true)).distance(), d -> 6.0));
         }

         if (end) {
            openings.add(new RoadShellOpening(mesh, lining.samples().get(contactEnd(lining, all, target, false)).distance(), mesh.length(), d -> 6.0));
         }

         ArrayList<RoadStructures.Part> kept = new ArrayList<>();

         for (RoadStructures.Part part : structures) {
            if ((
                  part.material() == RoadStructures.Material.TUNNEL
                     || part.material() == RoadStructures.Material.SIGN_WHITE
                     || part.material() == RoadStructures.Material.LAMP
               )
               && !part.model().equals("sr:tunnel_ceiling_light")
               && !part.model().equals("sr:auto_light")) {
               List<RoadStructures.Part> pieces = List.of(part);

               for (RoadShellOpening opening : openings) {
                  ArrayList<RoadStructures.Part> next = new ArrayList<>();

                  for (RoadStructures.Part piece : pieces) {
                     next.addAll(opening.cut(piece));
                  }

                  pieces = next;
               }

               kept.addAll(pieces);
            } else {
               kept.add(part);
            }
         }

         return kept;
      } else {
         return structures;
      }
   }

   private static String obstacleSummary(RoadGeometry.Mesh base, List<LaneRamps.Obstacle> contacts) {
      if (contacts.isEmpty()) {
         return "";
      } else {
         LaneRamps.Obstacle c = contacts.stream()
            .filter(vx -> vx.contact().blocked())
            .min(Comparator.comparingDouble(vx -> Math.min(vx.contact().from(), base.length() - vx.contact().to())))
            .orElse(contacts.get(0));
         RoadClearance.Contact v = c.contact();
         return String.format(
            Locale.ROOT,
            "；实际冲突道路 %s，交叠区在候选路线距 A 沿线 %.1f–%.1f 格（路线全长 %.1f 格，AB 水平直距 %.1f 格），%s；要求净空 %.2f 格，上跨需抬升 %.2f 格，下穿需降低 %.2f 格",
            c.road(),
            v.from(),
            v.to(),
            base.length(),
            base.last().center().sub(base.first().center()).horizontalLength(),
            RoadClearance.clearanceLabel(v.usableClearance()),
            v.required(),
            v.raise(),
            v.lower()
         );
      }
   }

   static boolean monotone(RoadGeometry.Mesh m) {
      return verticalEffort(m) <= Math.abs(m.last().center().y() - m.first().center().y()) + 1.0E-5;
   }

   static boolean verticalKink(RoadGeometry.Mesh mesh) {
      List<RoadGeometry.Sample> points = mesh.samples();
      double previous = 0.0;
      double previousRun = 0.0;

      for (int i = 1; i < points.size(); i++) {
         RoadGeometry.V delta = points.get(i).center().sub(points.get(i - 1).center());
         double run = delta.horizontalLength();
         if (!(run < 1.0E-6)) {
            double grade = delta.y() / run;
            double change = Math.abs(grade - previous);
            if (previousRun > 0.0 && change > 0.02 && change / ((run + previousRun) / 2.0) > 0.03) {
               return true;
            }

            previous = grade;
            previousRun = run;
         }
      }

      return false;
   }

   private static List<List<LaneRamps.Obstacle>> groupedContacts(List<LaneRamps.Obstacle> contacts) {
      ArrayList<List<LaneRamps.Obstacle>> groups = new ArrayList<>();
      double groupEnd = 0.0;

      for (LaneRamps.Obstacle obstacle : contacts.stream()
         .sorted(Comparator.<LaneRamps.Obstacle, String>comparing(o -> o.road().toString()).thenComparingDouble(o -> o.contact().from()))
         .toList()) {
         List<LaneRamps.Obstacle> group = groups.isEmpty() ? null : groups.get(groups.size() - 1);
         if (group == null
            || !group.get(0).road().equals(obstacle.road())
            || group.get(0).structure() != obstacle.structure()
            || obstacle.contact().from() > groupEnd + 2.0) {
            group = new ArrayList<>();
            groups.add(group);
            groupEnd = Double.NEGATIVE_INFINITY;
         }

         group.add(obstacle);
         groupEnd = Math.max(groupEnd, obstacle.contact().to());
      }

      groups.sort(Comparator.comparingDouble(g -> g.get(0).contact().from()));
      return groups;
   }

   private static List<RoadGeometry.Mesh> mixedCandidates(
      RoadGeometry.Mesh base,
      double from,
      double to,
      List<LaneRamps.Obstacle> contacts,
      Map<UUID, RoadRecord> all,
      double grade,
      boolean strict,
      double[] floors,
      LanePoints.Elevation preference
   ) {
      List<List<LaneRamps.Obstacle>> groups = groupedContacts(contacts);

      record Choice(List<LaneRampCorridor.Bound> bounds, RoadGeometry.Mesh mesh, int secondary) {
      }

      ArrayList<Choice> beam = new ArrayList<>();
      beam.add(new Choice(List.of(), base, 0));

      for (List<LaneRamps.Obstacle> group : groups) {
         ArrayList<Choice> next = new ArrayList<>();

         for (Choice choice : beam) {
            for (boolean over : preference == LanePoints.Elevation.UNDER ? new boolean[]{false, true} : new boolean[]{true, false}) {
               RoadPlanningBudget.check();
               ArrayList<LaneRampCorridor.Bound> bounds = new ArrayList<>(choice.bounds());

               for (LaneRamps.Obstacle o : group) {
                  RoadClearance.Contact c = o.contact();
                  bounds.add(new LaneRampCorridor.Bound(c.from(), c.to(), over ? c.raiseBound() : c.lowerBound(), over));
               }

               try {
                  next.add(
                     new Choice(
                        List.copyOf(bounds),
                        LaneRampCorridor.solveMixed(base, from, to, bounds, grade, strict, floors),
                        choice.secondary() + ((preference != LanePoints.Elevation.OVER || over) && (preference != LanePoints.Elevation.UNDER || !over) ? 0 : 1)
                     )
                  );
               } catch (IllegalArgumentException var27) {
               }
            }
         }

         next.sort(Comparator.comparingInt(Choice::secondary).thenComparing(Choice::mesh, heightOrder(base)));
         beam = new ArrayList<>(next.subList(0, Math.min(16, next.size())));
         if (beam.isEmpty()) {
            break;
         }
      }

      return beam.stream().map(Choice::mesh).toList();
   }

   private static double verticalDisplacement(RoadGeometry.Mesh base, RoadGeometry.Mesh candidate) {
      double sum = 0.0;

      for (int i = 0; i < candidate.samples().size(); i++) {
         sum += Math.abs(candidate.samples().get(i).center().y() - base.samples().get(i).center().y());
      }

      return sum;
   }

   private static Comparator<RoadGeometry.Mesh> heightOrder(RoadGeometry.Mesh base) {
      return Comparator.<RoadGeometry.Mesh>comparingDouble(m -> verticalDisplacement(base, m)).thenComparingDouble(LaneRamps::verticalEffort);
   }

   private static double verticalEffort(RoadGeometry.Mesh m) {
      double total = 0.0;

      for (int i = 1; i < m.samples().size(); i++) {
         total += Math.abs(m.samples().get(i).center().y() - m.samples().get(i - 1).center().y());
      }

      return total;
   }

   private static double fixedApproach(RoadGeometry.Mesh ramp, Map<UUID, RoadRecord> all, LanePoints.Link link, boolean source) {
      if ((!source || link.options().departure() != LanePoints.Departure.BRANCH) && (source || link.options().arrival() != LanePoints.Arrival.FLOW)) {
         if (source ? link.options().sourceExtra() : link.options().targetExtra()) {
            LanePoints.Ref ref = source ? link.from() : link.to();
            if (ref.road() == null) {
               return 0.0;
            } else {
               List<RoadGeometry.Sample> samples = approach(all, ref, source ? 0.0 : link.targetOffset(), source, ramp.settings(), link.options().transition())
                  .approach();
               double span = 0.0;

               for (int i = 1; i < samples.size(); i++) {
                  span += samples.get(i).center().distance(samples.get(i - 1).center());
               }

               return Math.min(ramp.length(), span);
            }
         } else {
            return 0.0;
         }
      } else {
         int end = LaneRampThroat.end(ramp, normalHosts(all, link, source), source);
         return source ? ramp.samples().get(end).distance() : ramp.length() - ramp.samples().get(end).distance();
      }
   }

   static void validateChanges(RoadGeometry.Mesh mesh, Map<UUID, RoadRecord> all, UUID id, LanePoints.Link link, Set<UUID> changed) {
      try (LaneRamps.Planning ignored = new LaneRamps.Planning((RoadData)null)) {
         for (LaneRamps.Obstacle obstacle : crossings(mesh, all, id, link, changed)) {
            if (obstacle.contact().blocked()) {
               throw new IllegalArgumentException("与本次修改道路 " + obstacle.road() + " 的净空或上下关系冲突");
            }
         }
      }
   }

   static void validate(RoadGeometry.Mesh mesh, Map<UUID, RoadRecord> all, UUID id, LanePoints.Link link) {
      LaneRampTerrain.validate(mesh, link.options(), CURRENT.get() == null ? null : CURRENT.get().terrain);
      RoadRibbon.checkSelfIntersections(mesh, 4.0);
      LaneRampPaths.checkVolume(mesh);
      LaneRampGrade.validate(mesh, gradeLimit(all, link));

      for (RoadRecord other : all.values()) {
         LanePoints.Link saved = LaneTopology.metadata(other).link();
         if (saved != null
            && !other.id().equals(id)
            && !other.id().equals(link.from().road())
            && !other.id().equals(link.to().road())
            && !id.equals(saved.from().road())
            && !id.equals(saved.to().road())
            && !saved.from().equals(link.from())
            && !saved.to().equals(link.to())
            && RoadIndex.overlapXZ(mesh, mesh(other), 2.0)) {
            for (RoadStructures.Part part : other.structures()) {
               if (RoadClearance.structureInvades(part, mesh, 4.25)) {
                  throw new IllegalArgumentException("候选路线侵入已建匝道 " + other.id() + " 的设施，不能通过重建旧匝道腾出空间");
               }
            }
         }
      }

      for (LaneRamps.Obstacle obstacle : crossings(mesh, all, id, link)) {
         RoadClearance.Contact c = obstacle.contact();
         LanePoints.Elevation mode = link.options().elevation();
         if (c.blocked()) {
            throw new IllegalArgumentException("与道路 " + obstacle.road() + " 冲突：" + new RoadClearance.Conflict(c).getMessage());
         }
      }
   }

   static double gradeLimit(Map<UUID, RoadRecord> all, LanePoints.Link link) {
      ArrayDeque<UUID> roads = new ArrayDeque<>();
      LinkedHashSet<UUID> junctions = new LinkedHashSet<>();

      for (LanePoints.Ref ref : List.of(link.from(), link.to())) {
         if (ref.road() != null) {
            roads.add(ref.road());
         } else {
            junctions.add(ref.junction());
         }
      }

      HashSet<UUID> seen = new HashSet<>();
      boolean highway = false;

      while (!roads.isEmpty()) {
         UUID id = roads.removeFirst();
         if (seen.add(id)) {
            RoadRecord road = all.get(id);
            if (road == null) {
               throw new IllegalArgumentException("坡比判定的关联道路已不存在，请重新选择");
            }

            highway |= RoadProfile.highway(road.settings().style());
            LanePoints.Link parent = LaneTopology.metadata(road).link();
            if (parent != null) {
               for (LanePoints.Ref refx : List.of(parent.from(), parent.to())) {
                  if (refx.road() != null) {
                     roads.add(refx.road());
                  } else {
                     junctions.add(refx.junction());
                  }
               }
            }
         }
      }

      for (UUID junction : junctions) {
         boolean found = false;

         for (RoadRecord roadx : all.values()) {
            if (junction.equals(roadx.assembly()) && roadx.junction() != null) {
               found = true;

               for (JunctionSpec.Arm arm : roadx.junction().spec().arms()) {
                  highway |= RoadProfile.highway(arm.external().style());
               }
            }
         }

         if (!found) {
            highway = true;
         }
      }

      return LaneRampGrade.limit(highway, link.options().gradeOverride());
   }

   private static boolean contact(RoadGeometry.Sample s, RoadGeometry.Mesh host, double limit, RoadGeometry.V end) {
      RoadQueries.Projection q = RoadQueries.horizontal(host, s.center());
      return q.horizontalDistance() < q.sample().halfWidth() + s.halfWidth() + 1.0 && Math.abs(q.sample().center().y() - s.center().y()) < 0.18;
   }

   private static int signature(RoadData data, LanePoints.Ref ref) {
      if (ref.junction() != null) {
         return data.junctions.getOrDefault(ref.junction(), new CompoundTag()).hashCode();
      } else {
         RoadIndex.Built b = data.index.roads.get(ref.road());
         if (b == null) {
            throw new IllegalArgumentException("道路已不存在");
         } else {
            return b.record.header().hashCode();
         }
      }
   }

   static void selection(ServerPlayer p, ItemStack tool, CompoundTag t) {
      if (t.hasUUID("Id")) {
         RoadIndex.Built built = RoadData.get(p.serverLevel()).index.roads.get(t.getUUID("Id"));
         if (built != null && LaneTopology.metadata(built.record).link() != null && built.record.assembly() == null) {
            RoadData.requireOwner(p, built.record.owner());
            if (t.getInt("Signature") != built.record.header().hashCode()) {
               throw new IllegalArgumentException("匝道已改变，请重新打开");
            } else {
               LanePoints.Link link = LaneTopology.metadata(built.record).link();
               if (!LanePointCodec.ref(link.from()).equals(t.getCompound("From")) || !LanePointCodec.ref(link.to()).equals(t.getCompound("To"))) {
                  throw new IllegalArgumentException("不能在编辑时替换车道点引用");
               }
            }
         } else {
            throw new IllegalArgumentException("所选独立匝道已不存在");
         }
      } else {
         CompoundTag state = ItemNBT.edit(tool);
         if (!state.contains("LaneFrom")
            || !state.contains("LaneTo")
            || !state.getCompound("LaneFrom").equals(t.getCompound("From"))
            || !state.getCompound("LaneTo").equals(t.getCompound("To"))
            || !p.level().dimension().location().toString().equals(state.getString("LaneDimension"))) {
            throw new IllegalArgumentException("匝道选择已失效，请先选汇出点，再选汇入点");
         }
      }
   }

   public static boolean tunnelEnd(Map<UUID, RoadRecord> all, LanePoints.Ref ref) {
      if (ref.road() == null) {
         return false;
      } else {
         RoadRecord road = host(all, ref);
         double station = LanePoints.lane(mesh(road), LaneTopology.point(road, ref.point())).station();
         return tunnelAt(road, station);
      }
   }

   private static boolean tunnelAt(RoadRecord road, double station) {
      return road.settings().structure() == RoadGeometry.Structure.TUNNEL
         ? true
         : RoadAutoTunnels.regions(road.structures()).stream().anyMatch(r -> station >= r.from() - 1.0E-6 && station <= r.to() + 1.0E-6);
   }

   private static boolean currentApproaches(RoadGeometry.Mesh candidate, Map<UUID, RoadRecord> all, LanePoints.Link link) {
      for (boolean source : new boolean[]{true, false}) {
         if (source ? link.options().sourceExtra() : link.options().targetExtra()) {
            LaneRoadChain.Position position = chain(all, source ? link.from() : link.to()).at(source ? 0.0 : link.targetOffset());
            LanePoints.Lane lane = LanePoints.lane(mesh(position.road()), position.point());
            List<RoadGeometry.Sample> expected = approach(
                  all, source ? link.from() : link.to(), source ? 0.0 : link.targetOffset(), source, candidate.settings(), link.options().transition()
               )
               .approach();

            for (int i = 0; i < expected.size(); i += 8) {
               RoadGeometry.Sample authored = expected.get(i);
               LaneRampAlignment.Mouth mouth = LaneRampAlignment.Mouth.extra(
                  lane.width(), RoadProfile.trafficSign(candidate.settings().options().leftTraffic())
               );
               RoadGeometry.V center = authored.center()
                  .add(authored.left().mul(LaneRampAlignment.auxiliaryShift(authored.halfWidth() * 2.0, mouth, candidate.settings().width())));
               RoadQueries.Projection actual = RoadQueries.horizontal(candidate, center);
               if (actual.horizontalDistance() > 0.05 || Math.abs(actual.sample().halfWidth() - authored.halfWidth()) > 0.01) {
                  return false;
               }
            }
         }
      }

      return true;
   }

   public static boolean rampHost(RoadRecord road) {
      return road != null && (road.settings().style().ramp() || LaneTopology.metadata(road).link() != null);
   }

   private static boolean rampEnd(Map<UUID, RoadRecord> all, LanePoints.Ref ref) {
      return ref.road() != null && rampHost(all.get(ref.road()));
   }

   private static boolean highwayEnd(Map<UUID, RoadRecord> all, LanePoints.Ref ref) {
      if (ref.road() == null) {
         return false;
      } else {
         RoadRecord road = host(all, ref);
         return !rampHost(road) && RoadProfile.catalog(road.settings()).type() == RoadProfile.Type.HIGHWAY;
      }
   }

   static void validateTunnelEnds(Map<UUID, RoadRecord> all, LanePoints.Ref from, LanePoints.Ref to, LanePoints.Options options) {
      boolean sourceRamp = rampEnd(all, from);
      boolean targetRamp = rampEnd(all, to);
      if (highwayEnd(all, from) && !options.sourceExtra()) {
         throw new IllegalArgumentException("高速车道点只允许额外扩出");
      } else if (highwayEnd(all, to) && !options.targetExtra()) {
         throw new IllegalArgumentException("高速车道点只允许额外扩入");
      } else if (sourceRamp && options.departure() != LanePoints.Departure.BRANCH) {
         throw new IllegalArgumentException("匝道车道点仅支持普通分流");
      } else if (targetRamp && options.arrival() != LanePoints.Arrival.FLOW) {
         throw new IllegalArgumentException("匝道车道点仅支持普通合流");
      } else if (!sourceRamp && tunnelEnd(all, from) && !options.sourceExtra()) {
         throw new IllegalArgumentException("隧道汇出端只允许额外汇出，请重新选择或编辑匝道");
      } else if (!targetRamp && tunnelEnd(all, to) && !options.targetExtra()) {
         throw new IllegalArgumentException("隧道汇入端只允许额外汇入，请重新选择或编辑匝道");
      }
   }

   public static CompoundTag tunnelChoices(CompoundTag t, Map<UUID, RoadRecord> all) {
      if (t.contains("From") && t.contains("To")) {
         LanePoints.Ref from = LanePointCodec.ref(t.getCompound("From"));
         LanePoints.Ref to = LanePointCodec.ref(t.getCompound("To"));
         boolean sourceRamp = rampEnd(all, from);
         boolean targetRamp = rampEnd(all, to);
         boolean source = !sourceRamp && tunnelEnd(all, from);
         boolean target = !targetRamp && tunnelEnd(all, to);
         t.putBoolean("SourceRamp", sourceRamp);
         t.putBoolean("TargetRamp", targetRamp);
         t.putBoolean("SourceTunnel", source);
         t.putBoolean("TargetTunnel", target);
         boolean sourceHighway = highwayEnd(all, from);
         boolean targetHighway = highwayEnd(all, to);
         t.putBoolean("SourceHighway", sourceHighway);
         t.putBoolean("TargetHighway", targetHighway);
         ListTag context = new ListTag();
         LinkedHashSet<UUID> ids = new LinkedHashSet<>();
         if (from.road() != null) {
            ids.add(from.road());
         }

         if (to.road() != null) {
            ids.add(to.road());
         }

         for (UUID id : ids) {
            RoadRecord road = all.get(id);
            if (road != null) {
               context.add(road.header());
            }
         }

         t.put("ContextRoads", context);
         LanePoints.Options o = LanePointCodec.options(t.getCompound("Options"));
         t.put(
            "Options",
            LanePointCodec.options(
               new LanePoints.Options(
                  o.path(),
                  sourceRamp ? LanePoints.Departure.BRANCH : (!source && !sourceHighway ? o.departure() : LanePoints.Departure.EXTRA),
                  targetRamp ? LanePoints.Arrival.FLOW : (!target && !targetHighway ? o.arrival() : LanePoints.Arrival.EXTRA),
                  o.radius(),
                  o.transition(),
                  o.elevation(),
                  o.landing(),
                  o.gradeOverride(),
                  o.allowTunnel(),
                  o.lanes()
               )
            )
         );
         return t;
      } else {
         return t;
      }
   }

   public static CompoundTag payload(RoadRecord road, boolean roadEditor) {
      LanePoints.Link link = LaneTopology.metadata(road).link();
      if (link == null) {
         throw new IllegalArgumentException("请选择连接器生成的独立匝道");
      } else {
         CompoundTag t = new CompoundTag();
         t.putString("Kind", roadEditor ? "laneRampRoad" : "laneRamp");
         t.putUUID("Id", road.id());
         t.putInt("Signature", road.header().hashCode());
         t.put("From", LanePointCodec.ref(link.from()));
         t.put("To", LanePointCodec.ref(link.to()));
         t.put("Options", LanePointCodec.options(link.options()));
         t.put("Road", road.header());
         t.put("Settings", RoadRecord.writeSettings(road.settings()));
         t.putLong("A", road.a().asLong());
         t.putLong("B", road.b().asLong());
         t.put("StartNode", RoadRecord.writeNode(road.start()));
         t.put("EndNode", RoadRecord.writeNode(road.end()));
         return t;
      }
   }

   private static synchronized LaneRamps.PreviewSnapshot previewSnapshot(RoadData data) {
      LaneRamps.PreviewSnapshot found = PREVIEW_SNAPSHOTS.get(data.index);
      long revision = data.index.contentRevision();
      if (found != null && found.revision() == revision) {
         snapshotHits++;
         return found;
      } else {
         long started = System.nanoTime();
         LaneRamps.PreviewSnapshot snapshot = new LaneRamps.PreviewSnapshot(revision, data.index.planningSnapshot());
         if (PREVIEW_SNAPSHOTS.size() >= 4) {
            PREVIEW_SNAPSHOTS.clear();
         }

         PREVIEW_SNAPSHOTS.put(data.index, snapshot);
         snapshotBuilds++;
         snapshotNanos = snapshotNanos + (System.nanoTime() - started);
         return snapshot;
      }
   }

   public static synchronized void clearPreviewSnapshots() {
      PREVIEW_SNAPSHOTS.clear();
   }

   public static String previewMetrics() {
      return "snapshotBuilds="
         + snapshotBuilds
         + " snapshotHits="
         + snapshotHits
         + " snapshotMs="
         + snapshotNanos / 1000000L
         + " terrainFallbackMs="
         + terrainFallbackNanos / 1000000L
         + " commitMs="
         + commitNanos / 1000000L;
   }

   public static LaneRamps.CandidateRejected candidateRejection(IllegalArgumentException failure) {
      if (failure instanceof LaneRamps.CandidateRejected) {
         return (LaneRamps.CandidateRejected)failure;
      } else {
         if (failure instanceof RoadCrossingFailure crossing && crossing.candidateDependent()) {
            return new LaneRamps.CandidateRejected(failure.getMessage(), failure);
         }

         throw failure;
      }
   }

   public static void conflictRoads(CompoundTag reply, ServerPlayer player, String message) {
      ListTag records = new ListTag();
      RoadData data = RoadData.get(player.serverLevel());

      for (UUID id : RoadConflictIds.read(message)) {
         RoadIndex.Built road = data.index.roads.get(id);
         if (road != null) {
            records.add(road.record.header());
         }
      }

      reply.put("ConflictRoads", records);
   }

   public static LaneRamps.PreviewWork preparePreview(ServerPlayer player, ItemStack tool, CompoundTag t) {
      selection(player, tool, t);
      RoadData data = RoadData.get(player.serverLevel());
      LanePoints.Ref from = LanePointCodec.ref(t.getCompound("From"));
      LanePoints.Ref to = LanePointCodec.ref(t.getCompound("To"));
      LanePoints.Options options = LanePointCodec.options(t.getCompound("Options"));
      LaneRamps.PreviewSnapshot snapshot = previewSnapshot(data);
      Map<UUID, RoadRecord> all = snapshot.records();
      RoadRecord source = host(all, from);
      RoadData.requireOwner(player, source.owner());
      if (to.road() != null) {
         RoadData.requireOwner(player, host(all, to).owner());
      } else {
         RoadData.requireOwner(player, data.junctions.getOrDefault(to.junction(), new CompoundTag()).getUUID("Owner"));
      }

      validateTunnelEnds(all, from, to, options);
      RoadGeometry.V mouth = to.junction() == null
         ? null
         : RampJunctions.mouth(
            data,
            to.junction(),
            LaneTopology.point(source, from.point()).position(),
            new RoadGeometry.Settings(
               RoadGeometry.Mode.STRAIGHT,
               RoadGeometry.Style.O1_ONE,
               Math.max(4.0, LanePoints.lane(mesh(source), LaneTopology.point(source, from.point())).width() + 1.0),
               1.0,
               0.35,
               90.0
            )
         );
      RoadGeometry.V direction = to.junction() == null ? null : RampJunctions.spec(data, to.junction()).center().sub(mouth).horizontalUnit();
      UUID id = t.hasUUID("Id") ? t.getUUID("Id") : UUID.randomUUID();
      double previousOffset = t.hasUUID("Id") ? LaneTopology.metadata(all.get(id)).link().targetOffset() : 0.0;
      return new LaneRamps.PreviewWork(
         snapshot.state(),
         id,
         t.hasUUID("Id") ? all.get(id).owner() : player.getUUID(),
         new LanePoints.Link(from, to, options, mouth, previousOffset),
         direction,
         snapshot.revision(),
         (double)player.serverLevel().getMinBuildHeight(),
         (double)player.serverLevel().getMaxBuildHeight()
      );
   }

   public static CompoundTag preview(ServerPlayer player, ItemStack tool, CompoundTag t) {
      LaneRamps.PreviewWork work = preparePreview(player, tool, t);
      return work.resolve(route -> finishPreview(player, tool, t, work, route));
   }

   public static CompoundTag finishPreview(ServerPlayer player, ItemStack tool, CompoundTag t, LaneRamps.PreviewWork work, LaneRamps.PreviewRoute generated) {
      CompoundTag var6;
      try (RoadWorkChunks chunks = RoadWorkChunks.open(player.serverLevel())) {
         var6 = finishPreviewLoaded(player, tool, t, work, generated);
      }

      return var6;
   }

   private static CompoundTag finishPreviewLoaded(
      ServerPlayer player, ItemStack tool, CompoundTag t, LaneRamps.PreviewWork work, LaneRamps.PreviewRoute generated
   ) {
      selection(player, tool, t);
      RoadData data = RoadData.get(player.serverLevel());
      if (data.index.contentRevision() != work.revision) {
         throw new IllegalArgumentException("计算期间道路已改变，旧预览已丢弃，请重新预览");
      } else {
         Map<UUID, RoadRecord> all = work.all;
         UUID id = work.id;
         LanePoints.Ref from = work.link.from();
         LanePoints.Ref to = work.link.to();
         LanePoints.Options options = work.link.options();
         RoadRecord r = generated.road();
         RoadStructures.Ground terrain = data.terrainGround();
         if (!LaneRampTerrain.clear(r.mesh(), LaneRampTerrain.floors(r.mesh(), LaneTopology.metadata(r).link().options(), terrain))) {
            long started = System.nanoTime();

            try {
               LanePoints.Link selected = LaneTopology.metadata(r).link();
               LanePoints.Options o = selected.options();
               LanePoints.Options trialOptions = new LanePoints.Options(
                  o.path(),
                  o.departure(),
                  o.arrival(),
                  o.radius(),
                  o.transition(),
                  generated.trialElevation(),
                  o.landing(),
                  o.gradeOverride(),
                  o.allowTunnel(),
                  o.lanes()
               );
               LanePoints.Link trial = new LanePoints.Link(
                  selected.from(),
                  selected.to(),
                  trialOptions,
                  selected.junctionMouth(),
                  selected.targetOffset(),
                  selected.protectedMerge(),
                  selected.rectangularClosure()
               );
               r = generateChoice(data, all, r.id(), r.owner(), trial, r.settings()).road();
               LanePoints.Data fitted = LaneTopology.metadata(r);
               LanePoints.Link finalLink = fitted.link();
               r = r.withLanePoints(
                  fitted.link(
                     new LanePoints.Link(
                        finalLink.from(),
                        finalLink.to(),
                        o,
                        finalLink.junctionMouth(),
                        finalLink.targetOffset(),
                        finalLink.protectedMerge(),
                        finalLink.rectangularClosure()
                     )
                  )
               );
            } catch (IllegalArgumentException var30) {
               throw new LaneRamps.CandidateRejected(var30.getMessage(), var30);
            } finally {
               terrainFallbackNanos = terrainFallbackNanos + (System.nanoTime() - started);
            }
         }

         if (!data.withinHeight(r.mesh())) {
            throw new LaneRamps.CandidateRejected("上跨／下穿超出世界高度范围");
         } else {
            ArrayList<RoadIndex.Built> planned = new ArrayList<>();
            planned.add(new RoadIndex.Built(r));
            HashSet<UUID> removed = new HashSet<>();
            if (all.containsKey(id)) {
               removed.add(id);
            }

            LaneRamps.AssemblyRequest request = assemblyRequest(data, r, all);
            UUID token = UUID.randomUUID();
            planned = new ArrayList<>(data.prepareAssembly(player.serverLevel(), player, planned, removed, request.endpoints(), request.moves(), token));
            Map<UUID, RoadRecord> staging = work.snapshot.changed(removed, planned).records();
            r = staging.get(id);
            CompoundTag checked = new CompoundTag();
            checked.put("Road", r.header());
            checked.putLong("WorldRevision", data.index.revision());
            checked.putInt("FromSignature", signature(data, from));
            checked.putInt("ToSignature", signature(data, to));
            checked.putUUID("Token", token);
            checked.putLong("Request", t.getLong("Request"));
            if (t.hasUUID("Id")) {
               checked.putUUID("Id", id);
               checked.putInt("Signature", t.getInt("Signature"));
            }

            RoadPlanningBudget.check();
            ItemNBT.get(tool).put("LanePreview", checked);
            CompoundTag reply = new CompoundTag();
            reply.putString("Kind", "laneRampCheck");
            reply.putString("ResolvedPath", generated.path().name());
            reply.putLong("Request", t.getLong("Request"));
            reply.put("Road", r.header());
            reply.putUUID("Token", checked.getUUID("Token"));
            reply.putDouble("TargetOffset", LaneTopology.metadata(r).link().targetOffset());
            reply.putDouble("GradeLimit", gradeLimit(staging, LaneTopology.metadata(r).link()));
            LanePoints.Link finalLink = LaneTopology.metadata(r).link();
            RoadGeometry.Mesh finalMesh = r.mesh();
            LaneRampGrade.Report metrics = LaneRampGrade.report(
               finalMesh,
               fixedApproach(finalMesh, staging, finalLink, true),
               finalMesh.length() - fixedApproach(finalMesh, staging, finalLink, false),
               gradeLimit(staging, finalLink)
            );
            reply.putDouble("GradeHorizontal", metrics.horizontal());
            reply.putDouble("GradeAvailable", metrics.available());
            reply.putDouble("GradeMinimum", metrics.minimum());
            reply.putDouble("ActualGrade", metrics.maximum());
            ListTag changed = new ListTag();

            for (RoadIndex.Built entry : planned) {
               RoadRecord next = entry.record;
               if (next.id().equals(id) || all.containsKey(next.id()) && !next.equals(all.get(next.id()))) {
                  changed.add(next.header());
               }
            }

            reply.put("ChangedRoads", changed);
            if (options.departure() == LanePoints.Departure.TEMPORARY) {
               for (LaneSections.Cut cut : LaneTopology.metadata(staging.get(from.road())).cuts()) {
                  if (cut.connection().equals(id)) {
                     double length = (double)cut.sign() * (cut.end() - cut.begin());
                     reply.putBoolean("TemporaryClosure", true);
                     reply.putBoolean("RectangularClosure", cut.rectangular());
                     reply.putDouble("ReopenAfter", cut.rectangular() ? length : length - cut.transition());
                     reply.putDouble("RestoredAfter", length);
                     break;
                  }
               }
            }

            if (LaneTopology.metadata(r).link().closesTarget()) {
               for (LaneSections.Cut cutx : LaneTopology.metadata(staging.get(to.road())).cuts()) {
                  if (cutx.connection().equals(id) && cutx.arrival()) {
                     reply.putBoolean("TargetClosure", true);
                     reply.putDouble(
                        "TargetClosedBefore",
                        (double)cutx.sign() * (cutx.end() - Math.max(0.0, Math.min(staging.get(to.road()).rawMesh().length(), cutx.begin())))
                     );
                     break;
                  }
               }
            }

            return reply;
         }
      }
   }

   public static String build(ServerPlayer player, ItemStack tool, CompoundTag t) {
      selection(player, tool, t);
      CompoundTag checked = ItemNBT.get(tool).getCompound("LanePreview");
      if (checked.hasUUID("Token")
         && t.hasUUID("Token")
         && checked.getUUID("Token").equals(t.getUUID("Token"))
         && checked.hasUUID("Id") == t.hasUUID("Id")
         && (!t.hasUUID("Id") || checked.getUUID("Id").equals(t.getUUID("Id")))) {
         RoadRecord r = RoadRecord.load(checked.getCompound("Road"));
         LanePoints.Link link = LaneTopology.metadata(r).link();
         RoadData data = RoadData.get(player.serverLevel());
         if (checked.getLong("WorldRevision") != data.index.revision()) {
            throw new IllegalArgumentException("道路几何已在预览后改变，请重新预览完整接头");
         } else if ((!t.hasUUID("Id") || checked.getInt("Signature") == t.getInt("Signature"))
            && LanePointCodec.options(link.options()).equals(t.getCompound("Options"))
            && checked.getInt("FromSignature") == signature(data, link.from())
            && checked.getInt("ToSignature") == signature(data, link.to())) {
            long started = System.nanoTime();

            try {
               data.buildPrepared(player.serverLevel(), player, checked.getUUID("Token"));
            } finally {
               commitNanos = commitNanos + (System.nanoTime() - started);
            }

            LaneRampTool.clearSelection(tool);
            return t.hasUUID("Id") ? "匝道已更新，身份与依赖引用保留" : "匝道已建成，车道点引用生效";
         } else {
            throw new IllegalArgumentException("道路或选项已变化，请重新预览");
         }
      } else {
         throw new IllegalArgumentException("请先检查当前设置的预览");
      }
   }

   public static String editRoad(ServerLevel level, ServerPlayer player, CompoundTag t) {
      RoadData data = RoadData.get(level);
      RoadIndex.Built b = data.index.roads.get(t.getUUID("Id"));
      if (b != null && LaneTopology.metadata(b.record).link() != null) {
         if (player != null) {
            RoadData.requireOwner(player, b.record.owner());
         }

         if (t.getInt("Signature") != b.record.header().hashCode()) {
            throw new IllegalArgumentException("道路已改变，请重新打开编辑器");
         } else {
            RoadRecord next = reconfigure(data, b.record, RoadRecord.readSettings(t.getCompound("Settings")), LaneTopology.records(data));
            build(data, level, player, next);
            return "匝道路面与附属设置已保存，车道点引用保留";
         }
      } else {
         throw new IllegalArgumentException("所选匝道已不存在");
      }
   }

   private static LaneRamps.AssemblyRequest assemblyRequest(RoadData data, RoadRecord r) {
      return assemblyRequest(data, r, data.index.planningSnapshot().records());
   }

   private static LaneRamps.AssemblyRequest assemblyRequest(RoadData data, RoadRecord r, Map<UUID, RoadRecord> all) {
      LanePoints.Link link = LaneTopology.metadata(r).link();
      if (link == null) {
         throw new IllegalArgumentException("缺少真实车道点引用");
      } else {
         ArrayList<RoadData.NodeMove> moves = new ArrayList<>();
         if (link.to().junction() != null) {
            RoadGeometry.Node n = new RoadGeometry.Node(
               link.junctionMouth(), RoadPlanner.yaw(RampJunctions.spec(data, link.to().junction()).center().sub(link.junctionMouth()).horizontalUnit()), 0.0
            );
            CompoundTag snapshot = new CompoundTag();
            snapshot.putUUID("Owner", r.owner());
            snapshot.put("Node", RoadRecord.writeNode(n));
            snapshot.putBoolean("HeightExplicit", true);
            moves.add(new RoadData.NodeMove(null, RampJunctions.at(n.position()), n, snapshot));
         }

         return new LaneRamps.AssemblyRequest(contactEndpoints(all, r), List.copyOf(moves));
      }
   }

   static Set<BlockPos> contactEndpoints(Map<UUID, RoadRecord> all, RoadRecord r) {
      HashSet<BlockPos> endpoints = new HashSet<>();
      endpoints.add(r.a());
      endpoints.add(r.b());
      ArrayDeque<LanePoints.Ref> pending = new ArrayDeque<>();
      HashSet<UUID> seen = new HashSet<>();
      seen.add(r.id());
      LanePoints.Link link = LaneTopology.metadata(r).link();
      if (link != null) {
         pending.add(link.from());
         pending.add(link.to());
      }

      while (!pending.isEmpty()) {
         for (UUID id : contactRoads(all, pending.removeFirst())) {
            if (seen.add(id)) {
               RoadRecord host = all.get(id);
               endpoints.add(host.a());
               endpoints.add(host.b());
               LanePoints.Link parent = LaneTopology.metadata(host).link();
               if (parent != null) {
                  pending.add(parent.from());
                  pending.add(parent.to());
               }
            }
         }
      }

      return Set.copyOf(endpoints);
   }

   public static void build(RoadData data, ServerLevel level, ServerPlayer player, RoadRecord r) {
      LaneRamps.AssemblyRequest request = assemblyRequest(data, r);
      data.replaceAssembly(
         level, player, List.of(new RoadIndex.Built(r)), data.index.roads.containsKey(r.id()) ? Set.of(r.id()) : Set.of(), request.endpoints(), request.moves()
      );
      RampJunctions.sync(data);
      data.setDirty();
   }

   private LaneRamps() {
   }

   private static record ApproachKey(double offset, boolean source, double width, double motorCenter, double transition) {
   }

   private static record AssemblyRequest(Set<BlockPos> endpoints, List<RoadData.NodeMove> moves) {
   }

   private static record CandidateKey(double offset, double lead, double tail, LanePoints.Path kind, boolean automatic, boolean smooth, boolean constrained) {
   }

   private static final class CandidateMemo {
      private final LinkedHashMap<LaneRamps.CandidateKey, LaneRamps.CandidateMemo.Entry> entries = new LinkedHashMap<>(64, 0.75F, true);
      private int weight;

      List<LaneRampPaths.Candidate> get(LaneRamps.CandidateKey key, Supplier<List<LaneRampPaths.Candidate>> build) {
         LaneRamps.CandidateMemo.Entry value = this.entries.get(key);
         if (value == null) {
            try {
               List<LaneRampPaths.Candidate> made = List.copyOf(build.get());
               value = new LaneRamps.CandidateMemo.Entry(made, null, Math.max(1, made.stream().mapToInt(c -> c.mesh().samples().size()).sum()));
            } catch (IllegalArgumentException var5) {
               value = new LaneRamps.CandidateMemo.Entry(List.of(), var5.getMessage(), 1);
            }

            if (value.weight() <= 300000) {
               this.entries.put(key, value);
               this.weight = this.weight + value.weight();
               Iterator<LaneRamps.CandidateMemo.Entry> it = this.entries.values().iterator();

               while (this.weight > 300000 || this.entries.size() > 256) {
                  this.weight = this.weight - it.next().weight();
                  it.remove();
               }
            }
         }

         if (value.error() != null) {
            throw new IllegalArgumentException(value.error());
         } else {
            return value.candidates();
         }
      }

      private static record Entry(List<LaneRampPaths.Candidate> candidates, String error, int weight) {
      }
   }

   public static final class CandidateRejected extends IllegalArgumentException {
      public CandidateRejected(String message) {
         super(message);
      }

      public CandidateRejected(String message, Throwable cause) {
         super(message, cause);
      }
   }

   private static record Generated(RoadRecord road, LanePoints.Path path) {
   }

   private static record Obstacle(UUID road, RoadClearance.Contact contact, boolean structure) {
      Obstacle(UUID road, RoadClearance.Contact contact) {
         this(road, contact, false);
      }
   }

   private static final class Planning implements AutoCloseable {
      final IdentityHashMap<RoadRecord, RoadGeometry.Mesh> meshes = new IdentityHashMap<>();
      Map<RoadRecord, RoadGeometry.Mesh> seedMeshes = Map.of();
      final IdentityHashMap<RoadGeometry.Mesh, RoadClearance.Prepared> decks = new IdentityHashMap<>();
      final IdentityHashMap<RoadGeometry.Mesh, Map<List<Integer>, RoadGeometry.Mesh>> protectedDecks = new IdentityHashMap<>();
      final IdentityHashMap<Map<UUID, RoadRecord>, Map<LanePoints.Ref, Set<UUID>>> connected = new IdentityHashMap<>();
      final IdentityHashMap<Map<UUID, RoadRecord>, Map<LanePoints.Ref, LaneRoadChain>> chains = new IdentityHashMap<>();
      final IdentityHashMap<LaneRoadChain, Map<LaneRamps.ApproachKey, LaneRampPaths.Port>> approaches = new IdentityHashMap<>();
      RoadStructures.Ground terrain;
      RoadGeometry.V junctionDirection;
      double minimumHeight = Double.NEGATIVE_INFINITY;
      double maximumHeight = Double.POSITIVE_INFINITY;
      boolean retainProfile;
      final boolean root;
      final long started = System.nanoTime();
      long clearanceNanos;
      int routeCount;
      int contactQueries;

      Planning(Map<RoadRecord, RoadGeometry.Mesh> seeds, RoadGeometry.V direction, double minHeight, double maxHeight) {
         this.root = LaneRamps.CURRENT.get() == null;
         if (this.root) {
            LaneRamps.CURRENT.set(this);
            this.seedMeshes = seeds;
            this.junctionDirection = direction;
            this.minimumHeight = minHeight;
            this.maximumHeight = maxHeight;
         }
      }

      Planning(RoadData data) {
         this.root = LaneRamps.CURRENT.get() == null;
         if (this.root) {
            LaneRamps.CURRENT.set(this);
            if (data != null) {
               this.terrain = data.terrainGround();
               this.seedMeshes = data.index.planningSnapshot().seeds();
            }
         }
      }

      @Override
      public void close() {
         if (this.root) {
            long elapsed = System.nanoTime() - this.started;
            if (elapsed > 1000000000L || Boolean.getBoolean("sr.profile")) {
               System.getLogger("SplineRoads/planner")
                  .log(
                     Level.INFO,
                     String.format(
                        Locale.ROOT,
                        "SR route plan total_ms=%.3f clearance_ms=%.3f candidates=%d contact_queries=%d cached_meshes=%d",
                        (double)elapsed / 1000000.0,
                        (double)this.clearanceNanos / 1000000.0,
                        this.routeCount,
                        this.contactQueries,
                        this.meshes.size()
                     )
                  );
            }

            LaneRamps.CURRENT.remove();
         }
      }
   }

   public static record PreviewRoute(RoadRecord road, LanePoints.Path path, LanePoints.Elevation trialElevation) {
      public PreviewRoute(RoadRecord road, LanePoints.Path path) {
         this(road, path, LaneTopology.metadata(road).link().options().elevation());
      }
   }

   private static record PreviewSnapshot(long revision, RoadPlanningSnapshot state) {
      Map<UUID, RoadRecord> records() {
         return this.state.records();
      }

      Map<RoadRecord, RoadGeometry.Mesh> seeds() {
         return this.state.seeds();
      }
   }

   public static final class PreviewWork {
      private final RoadPlanningSnapshot snapshot;
      private final Map<UUID, RoadRecord> all;
      private final Map<RoadRecord, RoadGeometry.Mesh> seeds;
      private final UUID id;
      private final UUID owner;
      private final LanePoints.Link link;
      private final RoadGeometry.V junctionDirection;
      public final long revision;
      private final double minHeight;
      private final double maxHeight;

      private PreviewWork(
         RoadPlanningSnapshot snapshot, UUID id, UUID owner, LanePoints.Link link, RoadGeometry.V direction, long revision, double minHeight, double maxHeight
      ) {
         this.snapshot = snapshot;
         this.all = snapshot.records();
         this.seeds = snapshot.seeds();
         this.id = id;
         this.owner = owner;
         this.link = link;
         this.junctionDirection = direction;
         this.revision = revision;
         this.minHeight = minHeight;
         this.maxHeight = maxHeight;
      }

      public boolean alternative(int attempt) {
         int count = switch (this.link.options().elevation()) {
            case AUTO -> 3;
            case KEEP -> 1;
            case OVER, UNDER -> 2;
         };
         return attempt >= 0 && attempt < count;
      }

      public LaneRamps.PreviewRoute compute() {
         return this.compute(0);
      }

      public LaneRamps.PreviewRoute compute(int attempt) {
         LaneRamps.PreviewRoute var13;
         try (LaneRamps.Planning scope = new LaneRamps.Planning(this.seeds, this.junctionDirection, this.minHeight, this.maxHeight)) {
            if (!this.alternative(attempt)) {
               throw new IllegalArgumentException("没有更多高程候选");
            }

            LanePoints.Link trial = this.link;
            if (attempt > 0) {
               LanePoints.Options o = this.link.options();
               LanePoints.Elevation elevation = o.elevation() == LanePoints.Elevation.OVER
                  ? LanePoints.Elevation.UNDER
                  : (
                     o.elevation() == LanePoints.Elevation.UNDER
                        ? LanePoints.Elevation.OVER
                        : (attempt == 1 ? LanePoints.Elevation.OVER : LanePoints.Elevation.UNDER)
                  );
               LanePoints.Options options = new LanePoints.Options(
                  o.path(), o.departure(), o.arrival(), o.radius(), o.transition(), elevation, o.landing(), o.gradeOverride(), o.allowTunnel(), o.lanes()
               );
               trial = new LanePoints.Link(
                  this.link.from(),
                  this.link.to(),
                  options,
                  this.link.junctionMouth(),
                  this.link.targetOffset(),
                  this.link.protectedMerge(),
                  this.link.rectangularClosure()
               );
            }

            LaneRamps.Generated generated = LaneRamps.generateChoice(null, this.all, this.id, this.owner, trial, null);
            RoadRecord road = generated.road();
            if (attempt > 0) {
               LanePoints.Data data = LaneTopology.metadata(road);
               LanePoints.Link selected = data.link();
               road = road.withLanePoints(
                  data.link(
                     new LanePoints.Link(
                        selected.from(),
                        selected.to(),
                        this.link.options(),
                        selected.junctionMouth(),
                        selected.targetOffset(),
                        selected.protectedMerge(),
                        selected.rectangularClosure()
                     )
                  )
               );
            }

            var13 = new LaneRamps.PreviewRoute(road, generated.path(), trial.options().elevation());
         }

         return var13;
      }

      public <T> T resolve(Function<LaneRamps.PreviewRoute, T> validate) {
         LaneRamps.CandidateRejected rejection = null;

         for (int attempt = 0; this.alternative(attempt); attempt++) {
            LaneRamps.PreviewRoute route;
            try {
               route = this.compute(attempt);
            } catch (IllegalArgumentException var7) {
               if (rejection == null) {
                  throw var7;
               }
               continue;
            }

            try {
               return validate.apply(route);
            } catch (IllegalArgumentException var6) {
               rejection = LaneRamps.candidateRejection(var6);
            }
         }

         throw rejection;
      }
   }
}
