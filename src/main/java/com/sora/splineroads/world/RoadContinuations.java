package com.sora.splineroads.world;

import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadEndpointSections;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadMedianAnchor;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadTransitions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;

public final class RoadContinuations {
   private static boolean ordinary(RoadContinuations.View view, RoadRecord r) {
      return r != null
         && r.junction() == null
         && r.assembly() == null
         && LaneTopology.metadata(r).link() == null
         && RoadProfile.modern(r.settings().style())
         && !r.settings().style().ramp()
         && !view.mesh(r).closed();
   }

   private static RoadGeometry.Sample port(RoadContinuations.View view, RoadRecord r, boolean first) {
      RoadGeometry.Mesh m = view.mesh(r);
      return first ? m.first() : m.last();
   }

   private static boolean same(RoadGeometry.Sample a, RoadGeometry.Sample b) {
      return a.center().distance(b.center()) < 1.0E-6 && Math.abs(a.halfWidth() - b.halfWidth()) < 1.0E-6 && Math.abs(a.left().dot(b.left())) > 0.999999;
   }

   public static void reconcile(Map<UUID, RoadRecord> before, Map<UUID, RoadRecord> all, Set<UUID> scope) {
      RoadContinuations.View view = new RoadContinuations.View();
      ArrayDeque<UUID> queue = new ArrayDeque<>();
      HashSet<UUID> queued = new HashSet<>();

      for (UUID id : new TreeSet<>(scope)) {
         RoadRecord old = before.get(id);
         RoadRecord now = all.get(id);
         if (old != null
            && now != null
            && (
               !LaneTopology.metadata(old).cuts().equals(LaneTopology.metadata(now).cuts())
                  || !LaneTopology.metadata(old).additions().equals(LaneTopology.metadata(now).additions())
            )) {
            queue.add(id);
            queued.add(id);
         }
      }

      if (!queue.isEmpty()) {
         HashMap<BlockPos, List<RoadRecord>> byNode = new HashMap<>();

         for (RoadRecord road : before.values()) {
            byNode.computeIfAbsent(road.a(), v -> new ArrayList<>()).add(road);
            if (!road.a().equals(road.b())) {
               byNode.computeIfAbsent(road.b(), v -> new ArrayList<>()).add(road);
            }
         }

         for (List<RoadRecord> roads : byNode.values()) {
            roads.sort(Comparator.comparing(RoadRecord::id));
         }

         int steps = 0;

         while (!queue.isEmpty()) {
            if (++steps > 1024) {
               throw new IllegalArgumentException("续接联动超过 1024 段，请分段修改；本次未写入道路");
            }

            UUID idx = queue.removeFirst();
            queued.remove(idx);
            RoadRecord old = before.get(idx);
            RoadRecord now = all.get(idx);
            if (ordinary(view, old) && ordinary(view, now)) {
               for (boolean first : new boolean[]{true, false}) {
                  RoadGeometry.Sample previous = port(view, old, first);
                  RoadGeometry.Sample target = port(view, now, first);
                  if (!same(previous, target)) {
                     BlockPos node = first ? now.a() : now.b();

                     for (RoadRecord oldNext : byNode.getOrDefault(node, List.of())) {
                        if (!oldNext.id().equals(idx) && ordinary(view, oldNext) && (oldNext.a().equals(node) || oldNext.b().equals(node))) {
                           boolean nextFirst = oldNext.a().equals(node);
                           if (same(previous, port(view, oldNext, nextFirst))) {
                              RoadRecord next = all.get(oldNext.id());
                              if (ordinary(view, next)) {
                                 RoadGeometry.Sample current = port(view, next, nextFirst);
                                 if (!same(current, target)) {
                                    if (!now.owner().equals(next.owner())) {
                                       throw new IllegalArgumentException("合并修改需要联动其他所有者的续接道路 " + next.id() + "，请先处理该接头");
                                    }

                                    if (current.center().distance(previous.center()) > 1.0E-5) {
                                       throw new IllegalArgumentException("相接道路两端同时发生不一致修改：" + next.id() + "；不能覆盖另一个端点编辑");
                                    }

                                    if (RoadEndpointSections.changed(view.mesh(next), nextFirst)) {
                                       throw new IllegalArgumentException("续接两侧均有独立的车道收窄约束，请分开调整合并点：" + next.id());
                                    }

                                    boolean reverse = current.left().dot(target.left()) < 0.0;
                                    RoadGeometry.Settings section = RoadEndpointSections.section(view.mesh(now), first, reverse);

                                    try {
                                       RoadTransitions.requireCompatible(next.settings(), section);
                                    } catch (IllegalArgumentException var36) {
                                       throw new IllegalArgumentException("原合并变化后续接断面需跨多级过渡：" + var36.getMessage(), var36);
                                    }

                                    RoadTransitions.Ends ends = next.settings().options().ends();
                                    RoadGeometry.Settings settings = RoadTransitions.ends(
                                       next.settings(),
                                       nextFirst ? RoadTransitions.Section.of(section) : ends.start(),
                                       nextFirst ? ends.end() : RoadTransitions.Section.of(section)
                                    );
                                    RoadGeometry.V dir = target.left().left().mul(reverse ? 1.0 : -1.0);
                                    double grade = (first ? now.start() : now.end()).grade() * (double)(reverse ? -1 : 1);
                                    RoadGeometry.Node pinned = new RoadGeometry.Node(
                                       RoadMedianAnchor.position(view.mesh(now), first), RoadPlanner.yaw(dir), grade
                                    );
                                    RoadGeometry.Node a = nextFirst ? pinned : next.start();
                                    RoadGeometry.Node b = nextFirst ? next.end() : pinned;
                                    RoadPlanner.Plan plan = RoadPlanner.plan(
                                       new RoadPlanner.Hint(a, true, true, true),
                                       new RoadPlanner.Hint(b, true, true, true),
                                       RoadPlanner.mode(settings, RoadGeometry.Mode.AUTO, settings.arcDegrees())
                                    );
                                    RoadRecord moved = new RoadRecord(
                                       next.id(),
                                       next.owner(),
                                       next.a(),
                                       next.b(),
                                       a,
                                       b,
                                       plan.settings(),
                                       next.automatic(),
                                       next.clearance(),
                                       List.of(),
                                       next.endCaps(),
                                       next.buildVersion(),
                                       null,
                                       List.of(),
                                       next.furniturePhase(),
                                       null
                                    );
                                    RoadGeometry.Sample rebuiltPort = port(view, moved, nextFirst);
                                    if (!same(rebuiltPort, target)) {
                                       throw new IllegalArgumentException("续接联动不能保持精确端口，请调整相邻路段长度：" + next.id());
                                    }

                                    all.put(next.id(), moved);
                                    scope.add(next.id());
                                    if (queued.add(next.id())) {
                                       queue.addLast(next.id());
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

         boolean added;
         do {
            added = false;

            for (RoadRecord r : all.values()) {
               LanePoints.Link l = LaneTopology.metadata(r).link();
               if (l != null && (scope.contains(l.from().road()) || scope.contains(l.to().road()))) {
                  added |= scope.add(r.id());
               }
            }
         } while (added);
      }
   }

   private RoadContinuations() {
   }

   private static final class View {
      final IdentityHashMap<RoadRecord, RoadGeometry.Mesh> meshes = new IdentityHashMap<>();

      RoadGeometry.Mesh mesh(RoadRecord r) {
         return this.meshes.computeIfAbsent(r, v -> v.caps(0).mesh());
      }
   }
}
