package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

public final class LaneAdditions {
   public static final int FIRST_NEW_SLOT = 16;

   public static boolean isAdded(RoadGeometry.Mesh mesh, int slot) {
      return slot >= 8 && LaneSections.reference(mesh).settings().options().lanePoints().additions().stream().anyMatch(a -> a.slot() == slot);
   }

   public static LanePoints.Data reserveAuthored(LanePoints.Data data, int count) {
      HashMap<Integer, Integer> remap = new HashMap<>();
      HashSet<Integer> used = new HashSet<>();

      for (LaneAdditions.Addition a : data.additions()) {
         used.add(a.slot());
      }

      for (LaneAdditions.Addition a : data.additions()) {
         if (a.slot() < count) {
            int next = 16;

            while (used.contains(next)) {
               next++;
            }

            if (next > 31) {
               throw new IllegalArgumentException("扩宽道路需要空闲的新增车道编号，请先删除部分扩出连接");
            }

            used.add(next);
            remap.put(a.slot(), next);
         }
      }

      if (remap.isEmpty()) {
         return data;
      } else {
         List<LaneAdditions.Addition> additions = data.additions()
            .stream()
            .map(
               ax -> new LaneAdditions.Addition(ax.connection(), remap.getOrDefault(ax.slot(), ax.slot()), ax.sign(), ax.station(), ax.transition(), ax.end())
            )
            .toList();
         List<LanePoints.Point> points = data.points()
            .stream()
            .map(p -> new LanePoints.Point(p.id(), p.origin(), remap.getOrDefault(p.lane(), p.lane()), p.position(), p.anchor(), p.mergeLength()))
            .toList();
         List<LaneSections.Cut> cuts = data.cuts()
            .stream()
            .map(
               c -> new LaneSections.Cut(
                     c.connection(),
                     remap.getOrDefault(c.lane(), c.lane()),
                     c.sign(),
                     c.begin(),
                     c.end(),
                     c.transition(),
                     c.replacement(),
                     c.temporary(),
                     c.arrival(),
                     c.rectangular(),
                     c.underpass()
                  )
            )
            .toList();
         return data.additions(additions).points(points).cuts(cuts);
      }
   }

   public static List<Integer> slots(RoadGeometry.Mesh mesh) {
      return slots(mesh, mesh.first().distance());
   }

   public static List<Integer> slots(RoadGeometry.Mesh mesh, double station) {
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);
      ArrayList<Integer> out = new ArrayList<>();

      for (int i = 0; i < RoadProfile.layout(raw, RoadStructures.sample(raw, station)).catalog().lanes(); i++) {
         out.add(i);
      }

      for (LaneAdditions.Addition a : raw.settings().options().lanePoints().additions()) {
         out.add(a.slot());
      }

      return out;
   }

   public static LaneAdditions.Addition find(RoadGeometry.Mesh mesh, int slot) {
      return LaneSections.reference(mesh)
         .settings()
         .options()
         .lanePoints()
         .additions()
         .stream()
         .filter(a -> a.slot() == slot)
         .findFirst()
         .orElseThrow(() -> new IllegalArgumentException("所选新增车道已不存在"));
   }

   public static LaneAdditions.Addition owned(RoadGeometry.Mesh mesh, UUID connection) {
      return LaneSections.reference(mesh)
         .settings()
         .options()
         .lanePoints()
         .additions()
         .stream()
         .filter(a -> a.connection().equals(connection))
         .findFirst()
         .orElseThrow(() -> new IllegalArgumentException("新增车道尚未完成规划"));
   }

   public static int side(RoadProfile.Layout l, int sign) {
      return l.catalog().twoWay() ? l.outside() * sign : l.outside();
   }

   public static List<LaneAdditions.Addition> ordered(RoadGeometry.Mesh raw) {
      return raw.settings()
         .options()
         .lanePoints()
         .additions()
         .stream()
         .sorted(Comparator.<LaneAdditions.Addition>comparingDouble(a -> a.station() * (double)a.sign()).thenComparing(LaneAdditions.Addition::connection))
         .toList();
   }

   public static double cutRemoval(RoadGeometry.Mesh raw, double station, int slot) {
      double value = 0.0;

      for (LaneSections.Cut c : raw.settings().options().lanePoints().cuts()) {
         if (c.lane() == slot) {
            value = Math.max(value, c.removed(station));
         }
      }

      return value;
   }

   public static double permanentRemoval(RoadGeometry.Mesh raw, double station, int slot) {
      double value = 0.0;

      for (LaneSections.Cut c : raw.settings().options().lanePoints().cuts()) {
         if (!c.temporary() && c.lane() == slot) {
            value = Math.max(value, c.removed(station));
         }
      }

      return value;
   }

   public static double edge(RoadGeometry.Mesh raw, RoadGeometry.Sample at, int side) {
      RoadProfile.Layout l = RoadProfile.layout(raw, at);
      double edge = side < 0 ? l.motorMin() : l.motorMax();
      int count = l.catalog().lanes();
      int low = l.catalog().twoWay() ? l.lanesOnSide(-1) : count;

      for (int i = 0; i < count; i++) {
         if (!l.catalog().twoWay() || (i < low ? -1 : 1) == side) {
            if (!l.catalog().twoWay()) {
               boolean contiguous = true;
               if (side < 0) {
                  for (int j = 0; j < i; j++) {
                     contiguous &= permanentRemoval(raw, at.distance(), j) > 0.999999;
                  }
               } else {
                  for (int j = count - 1; j > i; j--) {
                     contiguous &= permanentRemoval(raw, at.distance(), j) > 0.999999;
                  }
               }

               if (!contiguous) {
                  continue;
               }
            }

            double removal = 0.0;

            for (LaneSections.Cut c : raw.settings().options().lanePoints().cuts()) {
               if (!c.temporary() && c.lane() == i) {
                  removal = Math.max(removal, c.removed(at.distance()));
               }
            }

            edge -= (double)side * l.laneWidth() * removal;
         }
      }

      return edge;
   }

   public static LanePoints.Lane lane(RoadGeometry.Mesh mesh, double station, int slot) {
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);
      RoadGeometry.Sample at = RoadStructures.sample(raw, Math.max(0.0, Math.min(raw.length(), station)));
      LaneAdditions.Addition a = find(raw, slot);
      RoadProfile.Layout l = RoadProfile.layout(raw, at);
      int side = side(l, a.sign());
      double offset = edge(raw, at, side);

      for (LaneAdditions.Addition prior : ordered(raw)) {
         if (prior.slot() == slot) {
            break;
         }

         if (side(l, prior.sign()) == side) {
            offset += (double)side * l.laneWidth() * prior.fraction(at.distance()) * (1.0 - permanentRemoval(raw, at.distance(), prior.slot()));
         }
      }

      offset += (double)side * l.laneWidth() * a.fraction(at.distance()) / 2.0;
      return new LanePoints.Lane(slot, at.at(offset, 0.0), at.left().left().mul((double)(-a.sign())), l.laneWidth(), at.distance(), a.sign());
   }

   public static void validate(RoadGeometry.Mesh mesh) {
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);
      TreeSet<Double> checks = new TreeSet<>();
      checks.add(0.0);
      checks.add(raw.length());

      for (LaneAdditions.Addition a : raw.settings().options().lanePoints().additions()) {
         checks.add(a.station());
         checks.add(Math.max(0.0, Math.min(raw.length(), a.station() - (double)a.sign() * a.transition())));
      }

      for (LaneSections.Cut c : raw.settings().options().lanePoints().cuts()) {
         for (double d : new double[]{c.begin(), c.end(), c.begin() + (double)c.sign() * c.transition(), c.end() - (double)c.sign() * c.transition()}) {
            checks.add(Math.max(0.0, Math.min(raw.length(), d)));
         }
      }

      for (double at : checks) {
         LaneSections.Live live = LaneSections.live(mesh, at);
         if (live.forward() > 7 + auxiliary(raw, at, 1) || live.reverse() > 7 + auxiliary(raw, at, -1)) {
            throw new IllegalArgumentException("汇入方向已达 7 条车道，不能补入第 8 条；请检查该点及下游车道数");
         }
      }
   }

   private static int auxiliary(RoadGeometry.Mesh raw, double station, int sign) {
      return (int)raw.settings()
         .options()
         .lanePoints()
         .access()
         .stream()
         .flatMap(r -> r.additions().stream())
         .filter(a -> a.sign() == sign && a.fraction(station) > 0.0)
         .count();
   }

   private LaneAdditions() {
   }

   public static record Addition(UUID connection, int slot, int sign, double station, double transition, double end) {
      public Addition(UUID connection, int slot, int sign, double station, double transition) {
         this(connection, slot, sign, station, transition, Double.NaN);
      }

      public Addition(UUID connection, int slot, int sign, double station, double transition, double end) {
         if (connection != null
            && slot >= 8
            && slot <= 31
            && (sign == 1 || sign == -1)
            && RoadGeometry.finite(station, transition)
            && !(transition < 8.0)
            && !(transition > 256.0)
            && (Double.isNaN(end) || Double.isFinite(end) && !((double)sign * (end - station) < 0.0))) {
            this.connection = connection;
            this.slot = slot;
            this.sign = sign;
            this.station = station;
            this.transition = transition;
            this.end = end;
         } else {
            throw new IllegalArgumentException("新增外侧车道数据无效");
         }
      }

      public double fraction(double at) {
         double growth = RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, 1.0 + (double)this.sign * (at - this.station) / this.transition)));
         return Double.isNaN(this.end)
            ? growth
            : Math.min(growth, RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, 1.0 + (double)this.sign * (this.end - at) / this.transition))));
      }
   }
}
