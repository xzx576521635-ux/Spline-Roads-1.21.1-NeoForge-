package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JunctionSignals {
   static JunctionPlanner.Plan afterSlips(JunctionSpec s, JunctionPlanner.Plan plan, List<RoadGeometry.Mesh> original) {
      List<RoadGeometry.Mesh> approaches = plan.pieces().subList(0, s.arms().size()).stream().map(JunctionPlanner.Piece::mesh).toList();
      ArrayList<RoadGeometry.Mesh> obstacles = new ArrayList<>(approaches);
      obstacles.addAll(JunctionSlips.meshes(plan));
      List<RoadStructures.Part> paving = plan.pieces()
         .stream()
         .flatMap(px -> px.structures().stream())
         .filter(px -> px.material().name().startsWith("WALK_"))
         .toList();
      JunctionSignals.Layout layout = structures(s, original, plan.boundary(), true, true, new JunctionSignals.Placement(approaches, obstacles, paving));
      ArrayList<JunctionPlanner.Piece> pieces = new ArrayList<>(plan.pieces());

      for (int i = 0; i < s.arms().size(); i++) {
         JunctionPlanner.Piece p = pieces.get(i);
         ArrayList<RoadStructures.Part> parts = new ArrayList<>(p.structures());
         parts.addAll(layout.structures().get(i));
         pieces.set(i, new JunctionPlanner.Piece(p.mesh(), p.paint(), List.copyOf(parts), p.arm()));
      }

      return new JunctionPlanner.Plan(
         List.copyOf(pieces), plan.movements(), plan.boundary(), plan.sidewalks(), new JunctionSignalCycle(s, layout.pedestrianArms()), layout.heads()
      );
   }

   public static JunctionSignals.Layout structures(JunctionSpec s, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary) {
      return structures(s, approaches, boundary, true, true);
   }

   public static JunctionSignals.Layout structures(JunctionSpec s, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary, boolean grouped) {
      return structures(s, approaches, boundary, grouped, false);
   }

   public static JunctionSignals.Layout structures(
      JunctionSpec s, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary, boolean grouped, boolean sharedPhase
   ) {
      return structures(s, approaches, boundary, grouped, sharedPhase, new JunctionSignals.Placement(approaches, approaches, List.of()));
   }

   private static JunctionSignals.Layout structures(
      JunctionSpec s,
      List<RoadGeometry.Mesh> approaches,
      List<RoadGeometry.V> boundary,
      boolean grouped,
      boolean sharedPhase,
      JunctionSignals.Placement placement
   ) {
      ArrayList<List<RoadStructures.Part>> result = new ArrayList<>();
      ArrayList<JunctionSignals.Mast> masts = new ArrayList<>();
      LinkedHashMap<RoadStructures.Part, JunctionSignals.Head> heads = new LinkedHashMap<>();

      for (int i = 0; i < approaches.size(); i++) {
         result.add(vehicle(s, i, approaches, boundary, masts, heads, grouped, sharedPhase, placement));
      }

      ArrayList<Integer> controllers = new ArrayList<>();

      for (int i = 0; i < approaches.size(); i++) {
         controllers.add(pedestrians(s, i, approaches, boundary, masts, result.get(i), placement));
      }

      return new JunctionSignals.Layout(result.stream().map(List::copyOf).toList(), List.copyOf(controllers), Map.copyOf(heads));
   }

   public static int normalMask(JunctionSpec spec, int arm, int lane) {
      int mask = 0;
      List<JunctionSpec.Lane> lanes = spec.arms().get(arm).lanes();
      boolean protectedLeft = false;

      for (int i = 0; i < lanes.size(); i++) {
         protectedLeft |= lanes.get(i).splitLeft() && (JunctionPlanner.mask(spec, arm, i) & 10) != 0;
      }

      for (int i = 0; i < lanes.size(); i++) {
         if (lane < 0 || lane == i) {
            int next = JunctionPlanner.mask(spec, arm, i) & -5;
            if (protectedLeft) {
               next &= -11;
            }

            mask |= next;
         }
      }

      return mask;
   }

   public static boolean rightControlled(JunctionSpec spec, int arm, int lane) {
      List<JunctionSpec.Lane> lanes = spec.arms().get(arm).lanes();
      boolean enabled = lanes.stream().anyMatch(JunctionSpec.Lane::controlRight);
      if (!enabled) {
         return false;
      } else {
         for (int i = 0; i < lanes.size(); i++) {
            if ((lane < 0 || lane == i) && (JunctionPlanner.mask(spec, arm, i) & 4) != 0) {
               return true;
            }
         }

         return false;
      }
   }

   public static List<RoadStructures.Part> refreshHeads(List<RoadStructures.Part> saved, List<RoadStructures.Part> current) {
      if (saved.isEmpty()) {
         return saved;
      } else {
         ArrayList<RoadStructures.Part> result = new ArrayList<>();

         for (RoadStructures.Part p : saved) {
            if (!RoadJunctionSignalModel.supports(p)) {
               result.add(p);
            }
         }

         for (RoadStructures.Part px : current) {
            if (RoadJunctionSignalModel.supports(px)) {
               result.add(px);
            }
         }

         return List.copyOf(result);
      }
   }

   private static List<RoadStructures.Part> vehicle(
      JunctionSpec s,
      int index,
      List<RoadGeometry.Mesh> approaches,
      List<RoadGeometry.V> boundary,
      List<JunctionSignals.Mast> masts,
      Map<RoadStructures.Part, JunctionSignals.Head> heads,
      boolean grouped,
      boolean sharedPhase,
      JunctionSignals.Placement placement
   ) {
      JunctionSpec.Arm arm = s.arms().get(index);
      RoadGeometry.Mesh mesh = approaches.get(index);
      ArrayList<RoadStructures.Part> parts = new ArrayList<>();
      RoadGeometry.V forward = mesh.last().left().left().mul(-1.0);
      double sign = s.leftTraffic() ? -1.0 : 1.0;
      boolean leftControlled = false;

      for (int lane = 0; lane < arm.incoming(); lane++) {
         leftControlled |= arm.lanes().get(lane).splitLeft() && (JunctionPlanner.mask(s, index, lane) & 10) != 0;
      }

      if (arm.incoming() > 0 && (normalMask(s, index, -1) != 0 || leftControlled || rightControlled(s, index, -1))) {
         double middle = (arm.divider(s.leftTraffic()) + sign * arm.median() / 2.0 + arm.motorEdge(true, s.leftTraffic())) / 2.0;
         int opposite = -1;
         double best = 0.85;

         for (int j = 0; j < approaches.size(); j++) {
            if (j != index) {
               double dot = approaches.get(j).last().center().sub(s.center()).horizontalUnit().dot(forward);
               if (dot > best) {
                  best = dot;
                  opposite = j;
               }
            }
         }

         RoadGeometry.V head;
         RoadGeometry.V foot;
         if (s.kind() == JunctionSpec.Kind.ROUNDABOUT) {
            RoadGeometry.Sample far = RoadStructures.sample(mesh, Math.max(0.0, mesh.length() - 0.7));
            head = far.at(middle, -6.8);
            foot = foot(far, arm, sign, s, approaches, boundary, placement);
         } else if (opposite >= 0) {
            JunctionSpec.Arm other = s.arms().get(opposite);
            RoadGeometry.Mesh farMesh = approaches.get(opposite);
            double back = other.crossingSetback() + (other.crosswalk() ? other.crossingWidth() : 0.0) + 1.0;
            RoadGeometry.Sample far = RoadStructures.sample(farMesh, Math.max(0.0, farMesh.length() - back));
            head = far.center().add(forward.left().mul(middle)).add(new RoadGeometry.V(0.0, 6.8, 0.0));
            foot = foot(far, other, -sign, s, approaches, boundary, placement);
         } else {
            RoadGeometry.V origin = s.center().add(forward.left().mul(middle));
            double far = 0.0;

            for (double d = 0.0; d <= JunctionPlanner.radius(s) * 2.0 + arm.width(); d += 0.25) {
               if (JunctionPaint.inside(boundary, origin.add(forward.mul(d)))) {
                  far = d;
               }
            }

            RoadGeometry.V base = origin.add(forward.mul(far + 0.8));
            head = base.add(new RoadGeometry.V(0.0, 6.8, 0.0));
            foot = outside(
               base.add(forward.left().mul(sign * (arm.width() / 2.0 + 0.6) - middle)), forward.left().mul(sign), s, approaches, boundary, placement
            );
         }

         double beamY = head.y() + 0.796875;
         pole(parts, foot, forward, beamY - foot.y() + 0.25);
         masts.add(new JunctionSignals.Mast(foot, index));
         RoadGeometry.V top = new RoadGeometry.V(foot.x(), beamY, foot.z());
         ArrayList<RoadGeometry.V> positions = new ArrayList<>();
         boolean individual = arm.lanes().stream().anyMatch(JunctionSpec.Lane::splitLeft);
         if (!grouped) {
            if (!individual && normalMask(s, index, -1) != 0) {
               RoadStructures.Part p = new RoadStructures.Part(head, head.sub(forward.mul(0.939)), 0.657, 1.594, false, RoadStructures.Material.SIGNAL_VEHICLE);
               parts.add(p);
               heads.put(p, new JunctionSignals.Head(index, -1, false));
               positions.add(head);
            } else {
               for (int lane = 0; lane < arm.incoming(); lane++) {
                  JunctionSpec.Lane config = arm.lanes().get(lane);
                  int mask = JunctionPlanner.mask(s, index, lane);
                  RoadGeometry.V center = head.add(forward.left().mul(arm.laneCenter(true, lane, s.leftTraffic()) - middle));
                  boolean separate = config.splitLeft() && (mask & 10) != 0;
                  boolean normal = normalMask(s, index, lane) != 0;
                  if (normal) {
                     RoadGeometry.V p = center.add(forward.left().mul(separate ? 0.48 : 0.0));
                     RoadStructures.Part part = new RoadStructures.Part(
                        p, p.sub(forward.mul(0.939)), 0.657, 1.594, false, RoadStructures.Material.SIGNAL_VEHICLE
                     );
                     parts.add(part);
                     heads.put(part, new JunctionSignals.Head(index, lane, false));
                     positions.add(p);
                  }

                  if (separate) {
                     RoadGeometry.V p = center.sub(forward.left().mul(normal ? 0.48 : 0.0));
                     RoadStructures.Part part = new RoadStructures.Part(p, p.sub(forward.mul(0.939)), 0.657, 1.594, false, RoadStructures.Material.SIGNAL_LEFT);
                     parts.add(part);
                     heads.put(part, new JunctionSignals.Head(index, lane, true));
                     positions.add(p);
                  }
               }
            }
         } else {
            boolean normalx = normalMask(s, index, -1) != 0;
            LinkedHashMap<Integer, Integer> leftGroups = new LinkedHashMap<>();

            for (int lane = 0; lane < arm.incoming(); lane++) {
               JunctionSpec.Lane configx = arm.lanes().get(lane);
               int maskx = JunctionPlanner.mask(s, index, lane);
               boolean split = configx.splitLeft() && (maskx & 10) != 0;
               if (split) {
                  leftGroups.putIfAbsent(Integer.valueOf(sharedPhase ? arm.phase() : configx.effectiveLeftPhase(index)), Integer.valueOf(lane));
               }
            }

            if (normalx) {
               RoadStructures.Part p = new RoadStructures.Part(head, head.sub(forward.mul(0.939)), 0.657, 1.594, false, RoadStructures.Material.SIGNAL_VEHICLE);
               parts.add(p);
               heads.put(p, new JunctionSignals.Head(index, -1, false));
               positions.add(head);
            }

            int at = 0;

            for (int lanex : leftGroups.values()) {
               RoadGeometry.V p = head.sub(forward.left().mul((double)((!normalx && !rightControlled(s, index, -1) ? 0 : 1) + at++)));
               RoadStructures.Part part = new RoadStructures.Part(p, p.sub(forward.mul(0.939)), 0.657, 1.594, false, RoadStructures.Material.SIGNAL_LEFT);
               parts.add(part);
               heads.put(part, new JunctionSignals.Head(index, lanex, true));
               positions.add(p);
            }

            if (rightControlled(s, index, -1)) {
               RoadGeometry.V p = head.add(forward.left().mul(normalx ? 1.0 : 0.0));
               RoadStructures.Part part = new RoadStructures.Part(
                  p, p.sub(forward.mul(0.939)), 0.657, 1.594, false, RoadStructures.Material.SIGNAL_VEHICLE, null, null, "sr:signal_right"
               );
               parts.add(part);
               heads.put(part, new JunctionSignals.Head(index, -1, false));
               positions.add(p);
            }
         }

         if (!grouped && rightControlled(s, index, -1)) {
            double offset = positions.stream().mapToDouble(px -> px.sub(head).dot(forward.left())).max().orElse(-1.0) + 1.0;
            RoadGeometry.V p = head.add(forward.left().mul(offset));
            RoadStructures.Part part = new RoadStructures.Part(
               p, p.sub(forward.mul(0.939)), 0.657, 1.594, false, RoadStructures.Material.SIGNAL_VEHICLE, null, null, "sr:signal_right"
            );
            parts.add(part);
            heads.put(part, new JunctionSignals.Head(index, -1, false));
            positions.add(p);
         }

         RoadGeometry.V far = positions.stream().max(Comparator.comparingDouble(px -> px.sub(top).horizontalLength())).orElse(head);
         RoadGeometry.V mount = new RoadGeometry.V(far.x(), beamY, far.z());
         RoadGeometry.V span = mount.sub(top).horizontalUnit();
         parts.add(new RoadStructures.Part(top, mount.add(span.mul(0.5)), 0.22, 0.22, false, RoadStructures.Material.CB_ARM));
      }

      return parts;
   }

   private static int pedestrians(
      JunctionSpec s,
      int index,
      List<RoadGeometry.Mesh> approaches,
      List<RoadGeometry.V> boundary,
      List<JunctionSignals.Mast> masts,
      List<RoadStructures.Part> parts,
      JunctionSignals.Placement placement
   ) {
      JunctionSpec.Arm arm = s.arms().get(index);
      RoadGeometry.Mesh mesh = approaches.get(index);
      if (!arm.crosswalk()) {
         return index;
      } else {
         double overlap = s.kind() == JunctionSpec.Kind.ROUNDABOUT
            ? s.islandRadius() + (double)s.ringLanes() * s.ringLaneWidth() - mesh.last().center().sub(s.center()).horizontalLength()
            : 0.0;
         RoadGeometry.Sample crossing = RoadStructures.sample(mesh, Math.max(0.0, mesh.length() - overlap - arm.crossingSetback() + 0.65));
         RoadGeometry.V forward = crossing.left().left().mul(-1.0);
         int controller = index;
         double nearest = Double.POSITIVE_INFINITY;

         for (int side : new int[]{-1, 1}) {
            RoadGeometry.V base = foot(crossing, arm, (double)side, s, approaches, boundary, placement);
            RoadGeometry.V front = crossing.left().mul((double)(-side));
            RoadGeometry.V shared = null;
            double best = arm.crossingWidth() + 2.0;

            for (JunctionSignals.Mast candidate : masts) {
               RoadGeometry.V mast = candidate.base();
               RoadGeometry.V delta = mast.sub(base);
               double distance = delta.horizontalLength();
               if (distance <= best
                  && Math.abs(delta.dot(crossing.left())) <= 1.25
                  && Math.abs(delta.y()) <= 0.3
                  && mast.sub(crossing.center()).dot(crossing.left()) * (double)side > crossing.halfWidth()) {
                  shared = mast;
                  best = distance;
                  if (distance < nearest) {
                     nearest = distance;
                     controller = candidate.arm();
                  }
               }
            }

            if (shared == null) {
               pole(parts, base, forward, 3.45);
            } else {
               base = shared;
            }

            RoadGeometry.V mount = base.add(front.mul(0.19)).add(new RoadGeometry.V(0.0, 2.3, 0.0));
            parts.add(new RoadStructures.Part(mount, mount.add(front.mul(0.688)), 0.563, 1.038, false, RoadStructures.Material.SIGNAL_PEDESTRIAN));
         }

         return controller;
      }
   }

   private static RoadGeometry.V foot(
      RoadGeometry.Sample sample,
      JunctionSpec.Arm arm,
      double side,
      JunctionSpec spec,
      List<RoadGeometry.Mesh> approaches,
      List<RoadGeometry.V> boundary,
      JunctionSignals.Placement placement
   ) {
      RoadGeometry.Sample actual = RoadStructures.sample(placement.approaches().get(spec.arms().indexOf(arm)), sample.distance());
      return outside(actual.at(side * (actual.halfWidth() + 0.7), 0.0), actual.left().mul(side), spec, approaches, boundary, placement);
   }

   private static RoadGeometry.V outside(
      RoadGeometry.V base,
      RoadGeometry.V outward,
      JunctionSpec spec,
      List<RoadGeometry.Mesh> approaches,
      List<RoadGeometry.V> boundary,
      JunctionSignals.Placement placement
   ) {
      for (int step = 0; step <= 32; step++) {
         RoadGeometry.V p = base.add(outward.mul((double)step * 0.25));
         if (clearFoot(p, placement.obstacles(), boundary)) {
            return onWalk(p, spec, placement);
         }
      }

      RoadGeometry.V nearest = null;
      double best = Double.POSITIVE_INFINITY;
      double sign = JunctionPaint.area(boundary) >= 0.0 ? -1.0 : 1.0;

      for (int k = 0; k < boundary.size(); k++) {
         RoadGeometry.V a = boundary.get(k);
         RoadGeometry.V b = boundary.get((k + 1) % boundary.size());
         RoadGeometry.V normal = b.sub(a).horizontalUnit().left().mul(sign);

         for (double offset : new double[]{0.7, 1.0, 1.5, 2.0, 3.0}) {
            RoadGeometry.V p = a.add(b).mul(0.5).add(normal.mul(offset));
            double distance = p.sub(base).horizontalLength();
            if (distance < best && clearFoot(p, placement.obstacles(), boundary)) {
               best = distance;
               nearest = p;
            }
         }
      }

      if (nearest != null) {
         return onWalk(nearest, spec, placement);
      } else {
         throw new IllegalArgumentException("路口外侧空间不足，无法放置信号灯柱");
      }
   }

   private static boolean clearFoot(RoadGeometry.V base, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary) {
      if (JunctionPaint.inside(boundary, base)) {
         return false;
      } else {
         for (int k = 0; k < boundary.size(); k++) {
            RoadGeometry.V a = boundary.get(k);
            RoadGeometry.V b = boundary.get((k + 1) % boundary.size());
            RoadGeometry.V d = b.sub(a);
            double t = Math.max(0.0, Math.min(1.0, base.sub(a).dot(d) / Math.max(1.0E-9, d.dot(d))));
            if (base.distance(a.add(d.mul(t))) < 0.55) {
               return false;
            }
         }

         for (RoadGeometry.Mesh approach : approaches) {
            if (RoadSidewalks.overlapsDeck(new RoadStructures.Part(base, base, 1.12, 1.0, true, RoadStructures.Material.CB_BASE), approach)) {
               return false;
            }
         }

         for (RoadGeometry.Mesh approachx : approaches) {
            for (double dx : new double[]{-0.5, 0.0, 0.5}) {
               for (double dz : new double[]{-0.5, 0.0, 0.5}) {
                  if (RoadQueries.contains(approachx, base.add(new RoadGeometry.V(dx, 0.0, dz)), 0.03, 0.6)) {
                     return false;
                  }
               }
            }
         }

         return true;
      }
   }

   private static RoadGeometry.V onWalk(RoadGeometry.V base, JunctionSpec spec, JunctionSignals.Placement placement) {
      for (RoadStructures.Part p : placement.paving()) {
         if (JunctionPaint.inside(p.base(), base)) {
            return new RoadGeometry.V(base.x(), Math.max(p.a().y(), p.b().y()) + p.height(), base.z());
         }
      }

      List<RoadGeometry.Mesh> approaches = placement.approaches();

      for (int i = 0; i < approaches.size(); i++) {
         RoadQueries.Projection q = RoadQueries.horizontal(approaches.get(i), base);
         RoadSidewalks.Config c = spec.arms().get(i).external().options().sidewalk();
         int side = q.lateral() < 0.0 ? -1 : 1;
         if (c.enabled()
            && (c.side() == RoadSidewalks.Side.BOTH || c.side() == RoadSidewalks.Side.LEFT && side < 0 || c.side() == RoadSidewalks.Side.RIGHT && side > 0)
            && Math.abs(q.lateral()) < q.sample().halfWidth() + (double)c.width()
            && q.horizontalDistance() < q.sample().halfWidth() + (double)c.width()) {
            return base.add(new RoadGeometry.V(0.0, 0.2, 0.0));
         }
      }

      return base;
   }

   private static void pole(List<RoadStructures.Part> out, RoadGeometry.V foot, RoadGeometry.V forward, double height) {
      out.add(new RoadStructures.Part(foot.sub(forward.mul(0.35)), foot.add(forward.mul(0.35)), 0.7, 1.0, false, RoadStructures.Material.CB_BASE));
      out.add(new RoadStructures.Part(foot.sub(forward.mul(0.19)), foot.add(forward.mul(0.19)), 0.38, height, false, RoadStructures.Material.CB_POST));
   }

   private JunctionSignals() {
   }

   public static record Head(int arm, int lane, boolean left) {
   }

   public static record Layout(List<List<RoadStructures.Part>> structures, List<Integer> pedestrianArms, Map<RoadStructures.Part, JunctionSignals.Head> heads) {
   }

   private static record Mast(RoadGeometry.V base, int arm) {
   }

   private static record Placement(List<RoadGeometry.Mesh> approaches, List<RoadGeometry.Mesh> obstacles, List<RoadStructures.Part> paving) {
   }
}
