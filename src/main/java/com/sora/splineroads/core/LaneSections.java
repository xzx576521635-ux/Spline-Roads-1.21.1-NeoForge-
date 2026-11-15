package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class LaneSections {
   public static List<LaneSections.Cut> derive(RoadGeometry.Mesh mesh, List<LaneSections.Event> events) {
      RoadGeometry.Mesh raw = reference(mesh);
      ArrayList<LaneSections.Cut> cuts = new ArrayList<>();
      HashSet<UUID> used = new HashSet<>();
      Set<UUID> provisional = new HashSet<>();

      for (LaneSections.Event e : events) {
         if (e.kind() == LaneSections.Kind.ARRIVE && !Double.isFinite(e.returnStation())) {
            provisional.add(e.connection());
         }
      }

      ArrayList<LaneSections.Event> ordered = new ArrayList<>(events);
      ordered.sort(Comparator.comparingDouble(ex -> ex.station() * (double)ex.sign()));

      for (LaneSections.Event event : ordered) {
         if (event.kind() != LaneSections.Kind.REPLACE && event.kind() != LaneSections.Kind.ARRIVE) {
            LanePoints.Lane lane = LanePoints.lane(raw, event.station(), event.lane());
            RoadProfile.Layout layout = RoadProfile.layout(raw, RoadStructures.sample(raw, event.station()));
            RoadProfile.Catalog c = layout.catalog();
            if (event.sign() != lane.sign()) {
               throw new IllegalArgumentException("分离方向与所选车道的实际行驶方向不一致");
            }

            boolean outer = edge(raw, event.station(), event.lane(), cuts, event.connection());
            if (!outer && event.kind() != LaneSections.Kind.TEMPORARY) {
               throw new IllegalArgumentException(c.twoWay() ? "双向整车道分离请选择该方向最外侧车道，不能挖走内部车道或中央隔离" : "整车道分离须选择单向主线的一侧边缘车道");
            }

            double remainder = event.sign() > 0 ? raw.length() - event.station() : event.station();
            if (!(remainder < 0.02)) {
               if ((long)c.lanes() + raw.settings().options().lanePoints().additions().stream().filter(a -> a.fraction(event.station()) > 0.999999).count()
                     <= 1L
                  && event.kind() != LaneSections.Kind.TEMPORARY) {
                  throw new IllegalArgumentException("单车道内部一分二请选择普通分流（原车道直行）；整车道分离会清空主路");
               }

               LaneSections.Event replacement = null;

               for (LaneSections.Event candidate : ordered) {
                  if (event.kind() == LaneSections.Kind.DEPART
                     && candidate.kind() == LaneSections.Kind.REPLACE
                     && candidate.lane() == event.lane()
                     && candidate.sign() == event.sign()
                     && (double)candidate.sign() * (candidate.station() - event.station()) > 1.0E-4) {
                     replacement = candidate;
                     break;
                  }
               }

               double end = event.kind() == LaneSections.Kind.TEMPORARY && Double.isFinite(event.returnStation())
                  ? event.returnStation()
                  : (replacement == null ? (event.sign() > 0 ? raw.length() + 2.0 * event.transition() : -2.0 * event.transition()) : replacement.station());
               if (event.kind() == LaneSections.Kind.TEMPORARY
                  && Double.isFinite(event.returnStation())
                  && (
                     end < 0.0
                        || end > raw.length()
                        || (double)event.sign() * (end - event.station()) < (event.rectangular() ? 0.02 : 2.0 * event.transition())
                  )) {
                  throw new IllegalArgumentException("保留车道分离没有足够空间完成封闭及安全恢复");
               }

               for (LaneSections.Cut old : cuts) {
                  if (old.lane() == event.lane()
                     && old.sign() == event.sign()
                     && (double)event.sign() * (event.station() - old.begin()) >= 0.0
                     && (double)event.sign() * (event.station() - old.end()) < -0.01) {
                     throw new IllegalArgumentException("同一车道空位内重复整车道分离；Y 分叉请使用普通分流（原车道直行）");
                  }
               }

               if (replacement != null && !used.add(replacement.connection())) {
                  throw new IllegalArgumentException("补入车道同时匹配多个分离接头");
               }

               cuts.add(
                  new LaneSections.Cut(
                     event.connection(),
                     event.lane(),
                     event.sign(),
                     event.station(),
                     end,
                     event.transition(),
                     replacement == null ? null : replacement.connection(),
                     event.kind() == LaneSections.Kind.TEMPORARY,
                     false,
                     event.rectangular(),
                     event.underpass()
                  )
               );
            }
         }
      }

      for (LaneSections.Event eventx : ordered) {
         if (eventx.kind() == LaneSections.Kind.ARRIVE) {
            LanePoints.Lane lanex = LanePoints.lane(raw, eventx.station(), eventx.lane());
            if (lanex.sign() != eventx.sign()) {
               throw new IllegalArgumentException("汇入车道封闭方向与行驶方向不一致");
            }

            double begin = Double.isFinite(eventx.returnStation()) ? eventx.returnStation() : (eventx.sign() > 0 ? 0.0 : raw.length());
            if (!((double)eventx.sign() * (eventx.station() - begin) < 0.02)) {
               if (begin < -eventx.transition() - 0.001
                  || begin > raw.length() + eventx.transition() + 0.001
                  || eventx.station() < 0.0
                  || eventx.station() > raw.length()) {
                  throw new IllegalArgumentException("汇入封闭范围超出实际宿主路段");
               }

               LaneSections.Cut cut = new LaneSections.Cut(
                  eventx.connection(),
                  eventx.lane(),
                  eventx.sign(),
                  begin,
                  eventx.station(),
                  eventx.transition(),
                  null,
                  true,
                  true,
                  eventx.rectangular(),
                  eventx.underpass()
               );

               for (LaneSections.Cut oldx : cuts) {
                  if (!provisional.contains(cut.connection())
                     && !provisional.contains(oldx.connection())
                     && oldx.lane() == cut.lane()
                     && Math.min(Math.max(oldx.begin(), oldx.end()), Math.max(cut.begin(), cut.end()))
                           - Math.max(Math.min(oldx.begin(), oldx.end()), Math.min(cut.begin(), cut.end()))
                        > 0.01) {
                     throw new IllegalArgumentException("目标车道的汇入封闭与既有分离/汇入区间冲突，不能覆盖其他连接");
                  }
               }

               cuts.add(cut);
            }
         }
      }

      for (LaneSections.Event eventxx : ordered) {
         if (eventxx.kind() == LaneSections.Kind.REPLACE && !used.contains(eventxx.connection())) {
            throw new IllegalArgumentException("补入模式需要上游已有整车道分离或车道点合流缩减形成空位；普通汇入请选并入现有车道");
         }
      }

      return List.copyOf(cuts);
   }

   public static RoadGeometry.Mesh reference(RoadGeometry.Mesh mesh) {
      return mesh.reference() == null ? mesh : mesh.reference();
   }

   public static double removed(RoadGeometry.Mesh mesh, double station, int slot) {
      double removed = 0.0;

      for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
         if (cut.lane() == slot) {
            removed = Math.max(removed, cut.removed(station));
         }
      }

      return removed;
   }

   public static boolean active(RoadGeometry.Mesh mesh, double station, int slot) {
      return (!LaneAdditions.isAdded(mesh, slot) || LaneAdditions.find(mesh, slot).fraction(station) > 0.999999) && removed(mesh, station, slot) < 0.5;
   }

   public static LaneSections.Live live(RoadGeometry.Mesh mesh, double station) {
      RoadGeometry.Mesh raw = reference(mesh);
      RoadGeometry.Sample sample = RoadStructures.sample(raw, station);
      int count = RoadProfile.layout(raw, sample).catalog().lanes();
      int forward = 0;
      int reverse = 0;
      ArrayList<LanePoints.Lane> result = new ArrayList<>();

      for (int i : LaneAdditions.slots(mesh, station)) {
         if (active(mesh, station, i)) {
            LanePoints.Lane lane = LanePoints.lane(raw, station, i);
            result.add(lane);
            if (lane.sign() > 0) {
               forward++;
            } else {
               reverse++;
            }
         }
      }

      return new LaneSections.Live(result, forward, reverse);
   }

   public static boolean edge(RoadGeometry.Mesh mesh, double station, int slot) {
      return edge(reference(mesh), station, slot, mesh.settings().options().lanePoints().cuts(), null);
   }

   static boolean edge(RoadGeometry.Mesh raw, double station, int slot, List<LaneSections.Cut> cuts, UUID excluded) {
      LanePoints.Lane chosen = LanePoints.lane(raw, station, slot);
      RoadProfile.Layout layout = RoadProfile.layout(raw, RoadStructures.sample(raw, station));
      int count = layout.catalog().lanes();
      if ((!LaneAdditions.isAdded(raw, slot) || !(LaneAdditions.find(raw, slot).fraction(station) < 0.999999))
         && !(removed(cuts, station, slot, excluded) >= 0.5)) {
         double offset = chosen.position().sub(RoadStructures.sample(raw, station).center()).dot(RoadStructures.sample(raw, station).left());
         boolean low = true;
         boolean high = true;

         for (int i : LaneAdditions.slots(raw, station)) {
            if (i != slot
               && (!LaneAdditions.isAdded(raw, i) || LaneAdditions.find(raw, i).fraction(station) > 0.999999)
               && removed(cuts, station, i, excluded) < 0.5) {
               LanePoints.Lane other = LanePoints.lane(raw, station, i);
               if (other.sign() == chosen.sign()) {
                  double delta = other.position().sub(chosen.position()).dot(RoadStructures.sample(raw, station).left());
                  if (delta < -0.001) {
                     low = false;
                  }

                  if (delta > 0.001) {
                     high = false;
                  }
               }
            }
         }

         return layout.catalog().twoWay() ? (offset < layout.medianCenter() ? low : high) : low || high;
      } else {
         return false;
      }
   }

   static double removed(List<LaneSections.Cut> cuts, double station, int slot, UUID excluded) {
      double removed = 0.0;

      for (LaneSections.Cut c : cuts) {
         if (c.lane() == slot && !c.connection().equals(excluded)) {
            removed = Math.max(removed, c.removed(station));
         }
      }

      return removed;
   }

   public static int receiver(RoadGeometry.Mesh mesh, double station, int slot, UUID excluded) {
      RoadGeometry.Mesh raw = reference(mesh);
      LanePoints.Lane chosen = LanePoints.lane(raw, station, slot);
      int count = RoadProfile.layout(raw, RoadStructures.sample(raw, station)).catalog().lanes();
      int best = -1;
      double distance = Double.POSITIVE_INFINITY;

      for (int i : LaneAdditions.slots(mesh, station)) {
         if (i != slot
            && (!LaneAdditions.isAdded(raw, i) || LaneAdditions.find(raw, i).fraction(station) > 0.999999)
            && removed(mesh.settings().options().lanePoints().cuts(), station, i, excluded) < 0.5) {
            LanePoints.Lane candidate = LanePoints.lane(raw, station, i);
            if (candidate.sign() == chosen.sign()) {
               double d = candidate.position().distance(chosen.position());
               if (d < distance) {
                  distance = d;
                  best = i;
               }
            }
         }
      }

      if (best < 0) {
         throw new IllegalArgumentException("当前位置该方向至少保留两条通行车道才能合流或整车道分离");
      } else if (distance > chosen.width() * 1.01) {
         throw new IllegalArgumentException("合流接收车道被封闭，不能跨越中间空位并入更远车道");
      } else {
         return best;
      }
   }

   private static LaneSections.Section section(RoadGeometry.Mesh raw, RoadGeometry.Sample s) {
      RoadProfile.Layout layout = RoadProfile.layout(raw, s);
      RoadProfile.Catalog c = layout.catalog();
      int count = c.lanes();
      double[] removal = new double[count];

      for (LaneSections.Cut cut : raw.settings().options().lanePoints().cuts()) {
         if (!cut.temporary() && !LaneAdditions.isAdded(raw, cut.lane())) {
            if (cut.lane() >= count) {
               if (cut.removed(s.distance()) > 0.001) {
                  throw new IllegalArgumentException("分离车道穿过了车道数变化接缝，请在同一断面路段内设置接头");
               }
            } else {
               removal[cut.lane()] = Math.max(removal[cut.lane()], cut.removed(s.distance()));
            }
         }
      }

      double trimLow = 0.0;
      double trimHigh = 0.0;
      if (!c.twoWay()) {
         int low = 0;

         int high;
         for (high = count - 1; low < count && removal[low] > 1.0E-6; low++) {
            trimLow += layout.laneWidth() * removal[low];
         }

         while (high >= low && removal[high] > 1.0E-6) {
            trimHigh += layout.laneWidth() * removal[high];
            high--;
         }

         for (int i = low; i <= high; i++) {
            if (removal[i] > 1.0E-6) {
               throw new IllegalArgumentException("不能在保留两侧车道时挖走内部车道；请先通过普通分流接头将它引至外侧");
            }
         }
      } else {
         int per = layout.lanesOnSide(-1);

         for (int ix = 0; ix < count; ix++) {
            if (removal[ix] > 1.0E-6) {
               int outside = ix < per ? per - 1 : count - 1;

               for (int j = ix + 1; j <= outside; j++) {
                  if (removal[j] < 0.999999) {
                     throw new IllegalArgumentException("双向整车道分离须从当前位置的最外侧依次进行，不能挖走仍有外侧通行车道的内部车道");
                  }
               }

               if (count != RoadProfile.catalog(raw.settings()).lanes()) {
                  throw new IllegalArgumentException("分离区间不能跨越车道数变化接缝，请在同一稳定断面内设置接头");
               }

               if (ix < per) {
                  trimLow += layout.laneWidth() * removal[ix];
               } else {
                  trimHigh += layout.laneWidth() * removal[ix];
               }
            }
         }

         if (layout.medianEdge(-1) - layout.motorMin() - trimLow < -0.001 || layout.motorMax() - layout.medianEdge(1) - trimHigh < -0.001) {
            throw new IllegalArgumentException("分离范围超出相应半幅机动车道");
         }
      }

      double motorWidth = layout.motorMax() - layout.motorMin() - layout.median() - trimLow - trimHigh;
      if (motorWidth < layout.laneWidth() * 0.99) {
         throw new IllegalArgumentException("整车道分离后至少保留一条主路通行车道，不能挖空整条道路");
      } else {
         double extraLow = 0.0;
         double extraHigh = 0.0;
         int extraCount = 0;
         ArrayList<Double> addedDividers = new ArrayList<>();

         for (LaneAdditions.Addition added : LaneAdditions.ordered(raw)) {
            double width = layout.laneWidth() * added.fraction(s.distance()) * (1.0 - LaneAdditions.permanentRemoval(raw, s.distance(), added.slot()));
            int side = LaneAdditions.side(layout, added.sign());
            addedDividers.add(side < 0 ? layout.motorMin() + trimLow - extraLow : layout.motorMax() - trimHigh + extraHigh);
            if (side < 0) {
               extraLow += width;
            } else {
               extraHigh += width;
            }

            if (width > layout.laneWidth() * 0.999999) {
               extraCount++;
            }
         }

         double shift = (trimLow - trimHigh - extraLow + extraHigh) / 2.0;
         double half = s.halfWidth() - (trimLow + trimHigh) / 2.0 + (extraLow + extraHigh) / 2.0;
         double min = layout.motorMin() + trimLow - extraLow - shift;
         double max = layout.motorMax() - trimHigh + extraHigh - shift;
         int retained = count;

         for (double value : removal) {
            if (value >= 0.5) {
               retained--;
            }
         }

         ArrayList<Double> dividers = new ArrayList<>();

         for (double d : layout.dividers()) {
            dividers.add(d - shift);
         }

         for (double d : addedDividers) {
            dividers.add(d - shift);
         }

         dividers.sort(Double::compare);
         RoadProfile.Catalog catalog = new RoadProfile.Catalog(
            c.type(), c.twoWay() ? c.lanes() + extraCount : Math.max(1, retained + extraCount), c.twoWay(), c.median(), c.shoulder()
         );
         return new LaneSections.Section(
            shift,
            half,
            new RoadProfile.Layout(
               catalog,
               layout.laneWidth(),
               layout.median(),
               min,
               max,
               layout.cycleWidth(),
               layout.curbWidth(),
               layout.shoulderWidth(),
               layout.outside(),
               List.copyOf(dividers),
               layout.medianCenter() - shift
            )
         );
      }
   }

   public static RoadGeometry.Mesh apply(RoadGeometry.Mesh mesh) {
      if ((!mesh.settings().options().lanePoints().cuts().isEmpty() || !mesh.settings().options().lanePoints().additions().isEmpty())
         && mesh.reference() == null) {
         TreeSet<Double> stations = new TreeSet<>();

         for (RoadGeometry.Sample s : mesh.samples()) {
            stations.add(s.distance());
         }

         for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
            for (double center : new double[]{cut.begin(), cut.end()}) {
               for (double d = center - cut.transition(); d <= center + cut.transition() + 0.001; d += 0.5) {
                  if (d > 0.0 && d < mesh.length()) {
                     stations.add(d);
                  }
               }
            }
         }

         for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
            if (cut.rectangular() && !cut.temporary()) {
               for (double center : new double[]{cut.begin(), cut.end()}) {
                  for (double dx : new double[]{center - 1.0E-5, center, center + 1.0E-5}) {
                     if (dx > 0.0 && dx < mesh.length()) {
                        stations.add(dx);
                     }
                  }
               }
            }
         }

         for (LaneAdditions.Addition added : mesh.settings().options().lanePoints().additions()) {
            double begin = added.station() - (double)added.sign() * added.transition();

            for (double dxx = Math.min(begin, added.station()); dxx <= Math.max(begin, added.station()) + 0.001; dxx += 0.5) {
               if (dxx > 0.0 && dxx < mesh.length()) {
                  stations.add(dxx);
               }
            }

            if (added.station() > 0.0 && added.station() < mesh.length()) {
               stations.add(added.station());
            }

            if (Double.isFinite(added.end())) {
               double finish = added.end() + (double)added.sign() * added.transition();

               for (double dxxx = Math.min(added.end(), finish); dxxx <= Math.max(added.end(), finish) + 0.001; dxxx += 0.5) {
                  if (dxxx > 0.0 && dxxx < mesh.length()) {
                     stations.add(dxxx);
                  }
               }

               if (added.end() > 0.0 && added.end() < mesh.length()) {
                  stations.add(added.end());
               }
            }
         }

         if (stations.size() > 16384) {
            throw new IllegalArgumentException("车道过渡采样数量超限，请缩短路段");
         } else {
            ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

            for (double station : stations) {
               if (samples.isEmpty() || !(station - samples.get(samples.size() - 1).distance() < 1.0E-6)) {
                  RoadGeometry.Sample s = RoadStructures.sample(mesh, station);
                  LaneSections.Section section = section(mesh, s);
                  samples.add(new RoadGeometry.Sample(s.center().add(s.left().mul(section.shift())), s.left(), s.distance(), section.half()));
               }
            }

            RoadGeometry.Mesh physical = RoadRibbon.mesh(samples, mesh.settings());
            return new RoadGeometry.Mesh(
               List.copyOf(samples), mesh.settings(), physical.min(), physical.max(), mesh.length(), mesh.closed(), mesh.controlPoint(), mesh.controls(), mesh
            );
         }
      } else {
         return mesh;
      }
   }

   public static RoadProfile.Layout layout(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample) {
      return mesh.reference() == null ? null : section(mesh.reference(), RoadStructures.sample(mesh.reference(), sample.distance())).layout();
   }

   public static RoadGeometry.V anchor(RoadGeometry.Mesh mesh, double station) {
      return RoadStructures.sample(reference(mesh), station).center();
   }

   private LaneSections() {
   }

   public static record Cut(
      UUID connection,
      int lane,
      int sign,
      double begin,
      double end,
      double transition,
      UUID replacement,
      boolean temporary,
      boolean arrival,
      boolean rectangular,
      boolean underpass
   ) {
      public Cut(
         UUID connection,
         int lane,
         int sign,
         double begin,
         double end,
         double transition,
         UUID replacement,
         boolean temporary,
         boolean arrival,
         boolean rectangular
      ) {
         this(connection, lane, sign, begin, end, transition, replacement, temporary, arrival, rectangular, false);
      }

      public Cut(UUID connection, int lane, int sign, double begin, double end, double transition, UUID replacement, boolean temporary, boolean arrival) {
         this(connection, lane, sign, begin, end, transition, replacement, temporary, arrival, false);
      }

      public Cut(UUID connection, int lane, int sign, double begin, double end, double transition, UUID replacement, boolean temporary) {
         this(connection, lane, sign, begin, end, transition, replacement, temporary, false);
      }

      public Cut(UUID connection, int lane, int sign, double begin, double end, double transition, UUID replacement) {
         this(connection, lane, sign, begin, end, transition, replacement, false);
      }

      public Cut(UUID connection, int lane, int sign, double begin, double end, double transition) {
         this(connection, lane, sign, begin, end, transition, null);
      }

      public Cut(
         UUID connection,
         int lane,
         int sign,
         double begin,
         double end,
         double transition,
         UUID replacement,
         boolean temporary,
         boolean arrival,
         boolean rectangular,
         boolean underpass
      ) {
         if ((!arrival || temporary)
            && connection != null
            && lane >= 0
            && lane <= 31
            && (sign == 1 || sign == -1)
            && RoadGeometry.finite(begin, end, transition)
            && !(transition < 2.0)
            && !(transition > 256.0)
            && !((double)sign * (end - begin) < -1.0E-4)) {
            this.connection = connection;
            this.lane = lane;
            this.sign = sign;
            this.begin = begin;
            this.end = end;
            this.transition = transition;
            this.replacement = replacement;
            this.temporary = temporary;
            this.arrival = arrival;
            this.rectangular = rectangular;
            this.underpass = underpass;
         } else {
            throw new IllegalArgumentException("车道分离区间无效");
         }
      }

      public double removed(double station) {
         double distance = (double)this.sign * (this.end - this.begin);
         if (distance < 1.0E-6) {
            return 0.0;
         } else if (!this.rectangular) {
            double span = Math.min(this.transition, distance / 2.0);
            if (this.arrival) {
               return (double)this.sign * (station - this.end) >= -1.0E-8 ? 0.0 : smooth((double)this.sign * (station - this.begin) / span);
            } else {
               return Math.min(smooth((double)this.sign * (station - this.begin) / span), smooth((double)this.sign * (this.end - station) / span));
            }
         } else {
            return (double)this.sign * (station - this.begin) > 1.0E-8 && (double)this.sign * (this.end - station) > 1.0E-8 ? 1.0 : 0.0;
         }
      }

      private static double smooth(double t) {
         return RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, t)));
      }
   }

   public static record Event(
      UUID connection,
      LaneSections.Kind kind,
      int lane,
      int sign,
      double station,
      double transition,
      double returnStation,
      boolean rectangular,
      boolean underpass
   ) {
      public Event(UUID connection, LaneSections.Kind kind, int lane, int sign, double station, double transition, double returnStation, boolean rectangular) {
         this(connection, kind, lane, sign, station, transition, returnStation, rectangular, false);
      }

      public Event(UUID connection, LaneSections.Kind kind, int lane, int sign, double station, double transition, double returnStation) {
         this(connection, kind, lane, sign, station, transition, returnStation, false);
      }

      public Event(UUID connection, LaneSections.Kind kind, int lane, int sign, double station, double transition) {
         this(connection, kind, lane, sign, station, transition, Double.NaN);
      }
   }

   public static enum Kind {
      DEPART,
      REPLACE,
      TEMPORARY,
      ARRIVE;
   }

   public static record Live(List<LanePoints.Lane> lanes, int forward, int reverse) {
      public Live(List<LanePoints.Lane> lanes, int forward, int reverse) {
         lanes = List.copyOf(lanes);
         this.lanes = lanes;
         this.forward = forward;
         this.reverse = reverse;
      }

      public int count(int sign) {
         return sign > 0 ? this.forward : this.reverse;
      }

      public String description() {
         return this.reverse == 0 ? "单向 " + this.forward + " 车道" : "双向 " + this.forward + "+" + this.reverse + " 车道";
      }
   }

   private static record Section(double shift, double half, RoadProfile.Layout layout) {
   }
}
