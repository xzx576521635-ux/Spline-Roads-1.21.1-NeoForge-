package com.sora.splineroads.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class LanePoints {
   public static LanePoints.Lane lane(RoadGeometry.Mesh mesh, double station, int index) {
      if (LaneAdditions.isAdded(mesh, index)) {
         return LaneAdditions.lane(mesh, station, index);
      } else {
         mesh = LaneSections.reference(mesh);
         RoadGeometry.Sample sample = RoadStructures.sample(mesh, Math.max(0.0, Math.min(mesh.length(), station)));
         RoadProfile.Layout l = RoadProfile.layout(mesh, sample);
         RoadProfile.Catalog c = l.catalog();
         if (index >= 0 && index < c.lanes()) {
            double offset;
            if (c.twoWay()) {
               int per = l.lanesOnSide(-1);
               int side = index < per ? -1 : 1;
               offset = l.medianCenter() + (double)side * (l.median() / 2.0 + ((double)(side < 0 ? index : index - per) + 0.5) * l.laneWidth());
            } else {
               offset = l.motorMin() + ((double)index + 0.5) * l.laneWidth();
            }

            int sign = c.twoWay() && (offset < l.medianCenter() ? -1 : 1) != l.outside() ? -1 : 1;
            return new LanePoints.Lane(index, sample.at(offset, 0.0), sample.left().left().mul((double)(-sign)), l.laneWidth(), sample.distance(), sign);
         } else {
            throw new IllegalArgumentException("所选车道已不存在");
         }
      }
   }

   public static LanePoints.Lane lane(RoadGeometry.Mesh mesh, LanePoints.Point point) {
      return lane(
         mesh, RoadQueries.project(LaneSections.reference(mesh), point.anchor() == null ? point.position() : point.anchor()).sample().distance(), point.lane()
      );
   }

   public static LanePoints.Lane group(RoadGeometry.Mesh mesh, LanePoints.Point point, int count) {
      LanePoints.Lane first = lane(mesh, point);
      return group(mesh, first.station(), point.lane(), count);
   }

   public static LanePoints.Lane group(RoadGeometry.Mesh mesh, double station, int slot, int count) {
      LanePoints.Lane first = lane(mesh, station, slot);
      if (count == 1) {
         return first;
      } else {
         LanePoints.Lane last = lane(mesh, station, slot + count - 1);
         if (first.sign() == last.sign() && !LaneAdditions.isAdded(mesh, slot) && !LaneAdditions.isAdded(mesh, slot + count - 1)) {
            return new LanePoints.Lane(
               first.index(), first.position().add(last.position()).mul(0.5), first.direction(), first.width() * (double)count, first.station(), first.sign()
            );
         } else {
            throw new IllegalArgumentException("双车道匝道需要相邻且同向的两条原车道");
         }
      }
   }

   public static LanePoints.Point snap(RoadGeometry.Mesh mesh, LanePoints.Point point) {
      LanePoints.Lane selected = lane(mesh, point);
      RoadGeometry.V anchor = LaneSections.anchor(mesh, selected.station());
      return point.anchor() != null && point.position().distance(selected.position()) < 1.0E-7 && point.anchor().distance(anchor) < 1.0E-7
         ? point
         : point.at(selected, mesh);
   }

   public static LanePoints.Point migrate(RoadGeometry.Mesh previous, RoadGeometry.Mesh target, LanePoints.Point point) {
      LanePoints.Lane old = lane(previous, point);
      target = LaneSections.reference(target);
      RoadQueries.Projection q = RoadQueries.horizontal(target, point.position());
      LanePoints.Lane best = null;
      double distance = Double.POSITIVE_INFINITY;
      int count = RoadProfile.layout(target, q.sample()).catalog().lanes();

      for (int index : LaneAdditions.slots(target, q.sample().distance())) {
         LanePoints.Lane candidate = lane(target, q.sample().distance(), index);
         if (!(candidate.direction().dot(old.direction()) < 0.999)) {
            double d = candidate.position().sub(point.position()).horizontalLength();
            if (d < distance) {
               distance = d;
               best = candidate;
            }
         }
      }

      if (best != null && !(distance > Math.max(0.1, old.width() * 0.25))) {
         return point.at(best, target);
      } else {
         throw new IllegalArgumentException("相邻道路找不到保持位置及通行方向的对应车道，已取消归属迁移");
      }
   }

   public static LanePoints.Point point(UUID id, LanePoints.Origin origin, RoadGeometry.Mesh mesh, double station, int index) {
      LanePoints.Lane selected = lane(mesh, station, index);
      return new LanePoints.Point(id, origin, index, selected.position(), LaneSections.anchor(mesh, selected.station()));
   }

   public static LanePoints.Lane clicked(RoadGeometry.Mesh mesh, RoadGeometry.V hit) {
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);
      RoadQueries.Projection q = RoadQueries.project(raw, hit);
      RoadProfile.Layout l = RoadProfile.layout(raw, q.sample());
      LanePoints.Lane best = null;
      double distance = Double.POSITIVE_INFINITY;

      for (int i : LaneAdditions.slots(mesh, q.sample().distance())) {
         if (LaneSections.active(mesh, q.sample().distance(), i)) {
            LanePoints.Lane lane = lane(mesh, q.sample().distance(), i);
            double d = lane.position().sub(hit).horizontalLength();
            if (d < distance) {
               distance = d;
               best = lane;
            }
         }
      }

      if (best != null && !(distance > best.width() / 2.0 + 0.05) && !(Math.abs(hit.y() - best.position().y()) > 2.0)) {
         return best;
      } else {
         throw new IllegalArgumentException("请点击机动车道内，不能在中央隔离带、人行道或非机动车道放点");
      }
   }

   public static String label(RoadGeometry.Mesh mesh, int index) {
      return label(mesh, mesh.length() / 2.0, index);
   }

   public static boolean sameSection(RoadGeometry.Mesh a, double at, RoadGeometry.Mesh b, double bt) {
      RoadProfile.Layout x = RoadProfile.layout(a, RoadStructures.sample(a, at));
      RoadProfile.Layout y = RoadProfile.layout(b, RoadStructures.sample(b, bt));
      return x.catalog().type() == y.catalog().type()
         && x.catalog().lanes() == y.catalog().lanes()
         && x.catalog().twoWay() == y.catalog().twoWay()
         && Math.abs(x.laneWidth() - y.laneWidth()) < 0.01
         && Math.abs(x.median() - y.median()) < 0.01
         && Math.abs(x.cycleWidth() - y.cycleWidth()) < 0.01
         && Math.abs(x.shoulderWidth() - y.shoulderWidth()) < 0.01
         && a.settings().options().leftTraffic() == b.settings().options().leftTraffic();
   }

   public static String label(RoadGeometry.Mesh mesh, double station, int index) {
      LanePoints.Lane chosen = lane(mesh, station, index);
      LaneSections.Live live = LaneSections.live(mesh, station);
      List<LanePoints.Lane> same = live.lanes().stream().filter(l -> l.sign() == chosen.sign()).toList();
      int ordinal = 1;

      for (LanePoints.Lane l : same) {
         if (l.index() == index) {
            break;
         }

         ordinal++;
      }

      boolean present = same.stream().anyMatch(l -> l.index() == index);
      boolean twoWay = RoadProfile.catalog(mesh.settings().style()).twoWay();
      return (twoWay ? (chosen.sign() > 0 ? "正向" : "反向") + " " : "单向 ")
         + (present ? ordinal + " / " + same.size() + " 车道" + (LaneSections.edge(mesh, station, index) ? "（边缘）" : "") : "预留空位 " + (index + 1));
   }

   public static boolean supported(RoadGeometry.Settings s) {
      RoadProfile.Type type = RoadProfile.catalog(s.style()).type();
      if (type != RoadProfile.Type.ORDINARY && type != RoadProfile.Type.HIGHWAY && !s.style().connectorRamp()) {
         return false;
      } else if (s.structure() != RoadGeometry.Structure.BRIDGE) {
         return true;
      } else {
         return switch (s.options().infrastructure().bridge()) {
            case STANDARD, BEAM, OVERPASS -> true;
            default -> false;
         };
      }
   }

   public static boolean opening(RoadGeometry.Mesh mesh, RoadGeometry.V location) {
      for (LanePoints.Opening opening : mesh.settings().options().lanePoints().openings()) {
         for (int i = 1; i < opening.centerline().size(); i++) {
            RoadGeometry.V a = opening.centerline().get(i - 1);
            RoadGeometry.V b = opening.centerline().get(i);
            RoadGeometry.V d = b.sub(a);
            double length = d.x() * d.x() + d.z() * d.z();
            if (!(length < 1.0E-9)) {
               double t = Math.max(0.0, Math.min(1.0, (location.x() - a.x()) * d.x() / length + (location.z() - a.z()) * d.z() / length));
               RoadGeometry.V p = a.add(d.mul(t));
               if (!(Math.abs(location.y() - p.y()) >= 2.2) && !(location.sub(p).horizontalLength() >= opening.halfWidth() + 0.4)) {
                  if (t != 0.0 && t != 1.0
                     || !mesh.settings().options().lanePoints().access().stream().anyMatch(r -> r.connection().equals(opening.connection()))) {
                     return true;
                  }

                  double past = (location.x() - p.x()) * d.x() + (location.z() - p.z()) * d.z();
                  if ((t != 0.0 || !(past < 0.0)) && (t != 1.0 || !(past > 0.0))) {
                     return true;
                  }
               }
            }
         }
      }

      return false;
   }

   private LanePoints() {
   }

   public static enum Arrival {
      MERGE("并入现有车道"),
      REPLACE("旧版空位恢复"),
      EXTRA("额外扩入"),
      FLOW("普通汇流（主路不断行）"),
      ADD("最外侧补入（增加一车道）");

      public final String label;

      private Arrival(String label) {
         this.label = label;
      }
   }

   public static record Band(double station, double low, double high) {
      public Band(double station, double low, double high) {
         if (RoadGeometry.finite(station, low, high) && !(station < 0.0) && !(low >= high) && !(Math.abs(low) > 32.0) && !(Math.abs(high) > 32.0)) {
            this.station = station;
            this.low = low;
            this.high = high;
         } else {
            throw new IllegalArgumentException("无效的匝道机动车断面");
         }
      }
   }

   public static record Data(
      List<LanePoints.Point> points,
      LanePoints.Link link,
      List<LanePoints.Opening> openings,
      int priorityDepth,
      List<LaneSections.Cut> cuts,
      List<LaneAdditions.Addition> additions,
      List<LanePoints.Band> bands,
      List<ElevatedAccess.Reservation> access
   ) {
      public static final LanePoints.Data EMPTY = new LanePoints.Data(List.of(), null, List.of(), 0, List.of());

      public Data(
         List<LanePoints.Point> points,
         LanePoints.Link link,
         List<LanePoints.Opening> openings,
         int depth,
         List<LaneSections.Cut> cuts,
         List<LaneAdditions.Addition> additions,
         List<LanePoints.Band> bands
      ) {
         this(points, link, openings, depth, cuts, additions, bands, List.of());
      }

      public Data(
         List<LanePoints.Point> points,
         LanePoints.Link link,
         List<LanePoints.Opening> openings,
         int depth,
         List<LaneSections.Cut> cuts,
         List<LaneAdditions.Addition> additions
      ) {
         this(points, link, openings, depth, cuts, additions, List.of());
      }

      public Data(List<LanePoints.Point> points, LanePoints.Link link, List<LanePoints.Opening> openings, int priorityDepth, List<LaneSections.Cut> cuts) {
         this(points, link, openings, priorityDepth, cuts, List.of());
      }

      public Data(List<LanePoints.Point> points, LanePoints.Link link, List<LanePoints.Opening> openings) {
         this(points, link, openings, link == null ? 0 : 1, List.of());
      }

      public Data(List<LanePoints.Point> points, LanePoints.Link link, List<LanePoints.Opening> openings, int priorityDepth) {
         this(points, link, openings, priorityDepth, List.of());
      }

      public Data(
         List<LanePoints.Point> points,
         LanePoints.Link link,
         List<LanePoints.Opening> openings,
         int priorityDepth,
         List<LaneSections.Cut> cuts,
         List<LaneAdditions.Addition> additions,
         List<LanePoints.Band> bands,
         List<ElevatedAccess.Reservation> access
      ) {
         access = List.copyOf(access);
         if (access.stream().map(ElevatedAccess.Reservation::connection).distinct().count() != (long)access.size()) {
            throw new IllegalArgumentException("高架出入口身份重复");
         } else {
            points = List.copyOf(points);
            openings = List.copyOf(openings);
            cuts = List.copyOf(cuts);
            additions = List.copyOf(additions);
            bands = List.copyOf(bands);
            if (bands.size() <= 16384
               && additions.size() <= 24
               && additions.stream().map(LaneAdditions.Addition::slot).distinct().count() == (long)additions.size()
               && cuts.size() <= 128
               && points.size() <= 512
               && openings.size() <= 128
               && priorityDepth >= 0
               && priorityDepth <= 1024
               && points.stream().map(LanePoints.Point::id).distinct().count() == (long)points.size()) {
               double last = -1.0;

               for (LanePoints.Band band : bands) {
                  if (band.station() <= last) {
                     throw new IllegalArgumentException("匝道断面站位未递增");
                  }

                  last = band.station();
               }

               this.points = points;
               this.link = link;
               this.openings = openings;
               this.priorityDepth = priorityDepth;
               this.cuts = cuts;
               this.additions = additions;
               this.bands = bands;
               this.access = access;
            } else {
               throw new IllegalArgumentException("车道点数量超限、身份重复或依赖深度无效");
            }
         }
      }

      public LanePoints.Data points(List<LanePoints.Point> v) {
         return new LanePoints.Data(v, this.link, this.openings, this.priorityDepth, this.cuts, this.additions, this.bands, this.access);
      }

      public LanePoints.Data link(LanePoints.Link v) {
         return new LanePoints.Data(
            this.points, v, this.openings, v == null ? 0 : Math.max(1, this.priorityDepth), this.cuts, this.additions, this.bands, this.access
         );
      }

      public LanePoints.Data openings(List<LanePoints.Opening> v) {
         return new LanePoints.Data(this.points, this.link, v, this.priorityDepth, this.cuts, this.additions, this.bands, this.access);
      }

      public LanePoints.Data priorityDepth(int v) {
         return new LanePoints.Data(this.points, this.link, this.openings, v, this.cuts, this.additions, this.bands, this.access);
      }

      public LanePoints.Data additions(List<LaneAdditions.Addition> v) {
         return new LanePoints.Data(this.points, this.link, this.openings, this.priorityDepth, this.cuts, v, this.bands, this.access);
      }

      public LanePoints.Data cuts(List<LaneSections.Cut> v) {
         return new LanePoints.Data(this.points, this.link, this.openings, this.priorityDepth, v, this.additions, this.bands, this.access);
      }

      public LanePoints.Data access(List<ElevatedAccess.Reservation> v) {
         return new LanePoints.Data(this.points, this.link, this.openings, this.priorityDepth, this.cuts, this.additions, this.bands, v);
      }

      public LanePoints.Data bands(List<LanePoints.Band> v) {
         return new LanePoints.Data(this.points, this.link, this.openings, this.priorityDepth, this.cuts, this.additions, v, this.access);
      }
   }

   public static enum Departure {
      BRANCH("普通分流（原车道直行）"),
      DETACH("整车道分离"),
      EXTRA("额外扩出"),
      TEMPORARY("保留车道分离");

      public final String label;

      private Departure(String label) {
         this.label = label;
      }
   }

   public static enum Elevation {
      AUTO("自动避让"),
      OVER("上跨既有道路"),
      UNDER("下穿既有道路"),
      KEEP("保持原高程");

      public final String label;

      private Elevation(String label) {
         this.label = label;
      }
   }

   public static enum Landing {
      FLEXIBLE("同车道弹性落点"),
      EXACT("精确锁定 B");

      public final String label;

      private Landing(String label) {
         this.label = label;
      }
   }

   public static record Lane(int index, RoadGeometry.V position, RoadGeometry.V direction, double width, double station, int sign) {
   }

   public static record Link(
      LanePoints.Ref from,
      LanePoints.Ref to,
      LanePoints.Options options,
      RoadGeometry.V junctionMouth,
      double targetOffset,
      boolean protectedMerge,
      boolean rectangularClosure
   ) {
      public Link(LanePoints.Ref from, LanePoints.Ref to, LanePoints.Options options, RoadGeometry.V mouth, double offset, boolean protectedMerge) {
         this(from, to, options, mouth, offset, protectedMerge, false);
      }

      public Link(LanePoints.Ref from, LanePoints.Ref to, LanePoints.Options options, RoadGeometry.V mouth, double offset) {
         this(from, to, options, mouth, offset, false);
      }

      public Link(LanePoints.Ref from, LanePoints.Ref to, LanePoints.Options options, RoadGeometry.V junctionMouth) {
         this(from, to, options, junctionMouth, 0.0);
      }

      public Link(
         LanePoints.Ref from,
         LanePoints.Ref to,
         LanePoints.Options options,
         RoadGeometry.V junctionMouth,
         double targetOffset,
         boolean protectedMerge,
         boolean rectangularClosure
      ) {
         Objects.requireNonNull(from);
         Objects.requireNonNull(to);
         Objects.requireNonNull(options);
         if (from.junction() != null || from.equals(to)) {
            throw new IllegalArgumentException("匝道须从车道点汇出，且不能接回同一点");
         } else if (to.junction() != null && junctionMouth == null) {
            throw new IllegalArgumentException("缺少路口实际接入口");
         } else if (Double.isFinite(targetOffset) && (to.junction() == null || targetOffset == 0.0)) {
            this.from = from;
            this.to = to;
            this.options = options;
            this.junctionMouth = junctionMouth;
            this.targetOffset = targetOffset;
            this.protectedMerge = protectedMerge;
            this.rectangularClosure = rectangularClosure;
         } else {
            throw new IllegalArgumentException("汇入偏移超出允许范围");
         }
      }

      public boolean closesTarget() {
         return this.protectedMerge && this.to.road() != null && this.options.arrival() == LanePoints.Arrival.MERGE;
      }

      public LanePoints.Link withProtectedMerge() {
         return new LanePoints.Link(this.from, this.to, this.options, this.junctionMouth, this.targetOffset, true, this.rectangularClosure);
      }

      public LanePoints.Link withRectangularClosure() {
         return new LanePoints.Link(this.from, this.to, this.options, this.junctionMouth, this.targetOffset, this.protectedMerge, true);
      }

      public LanePoints.Link targetOffset(double value) {
         return new LanePoints.Link(this.from, this.to, this.options, this.junctionMouth, value, this.protectedMerge, this.rectangularClosure);
      }
   }

   public static record Opening(UUID connection, List<RoadGeometry.V> centerline, double halfWidth) {
      public Opening(UUID connection, List<RoadGeometry.V> centerline, double halfWidth) {
         centerline = List.copyOf(centerline);
         if (centerline.size() <= 16000 && Double.isFinite(halfWidth) && !(halfWidth <= 0.0) && !(halfWidth > 64.0)) {
            this.connection = connection;
            this.centerline = centerline;
            this.halfWidth = halfWidth;
         } else {
            throw new IllegalArgumentException("匝道接头范围无效");
         }
      }
   }

   public static record Options(
      LanePoints.Path path,
      LanePoints.Departure departure,
      LanePoints.Arrival arrival,
      double radius,
      double transition,
      LanePoints.Elevation elevation,
      LanePoints.Landing landing,
      boolean gradeOverride,
      boolean allowTunnel,
      int lanes
   ) {
      public static final LanePoints.Options DEFAULT = new LanePoints.Options(
         LanePoints.Path.AUTO, LanePoints.Departure.BRANCH, LanePoints.Arrival.MERGE, 24.0, 32.0, LanePoints.Elevation.AUTO, LanePoints.Landing.FLEXIBLE
      );

      public Options(
         LanePoints.Path path,
         LanePoints.Departure departure,
         LanePoints.Arrival arrival,
         double radius,
         double transition,
         LanePoints.Elevation elevation,
         LanePoints.Landing landing,
         boolean gradeOverride,
         boolean allowTunnel
      ) {
         this(path, departure, arrival, radius, transition, elevation, landing, gradeOverride, allowTunnel, 1);
      }

      public Options(
         LanePoints.Path path,
         LanePoints.Departure departure,
         LanePoints.Arrival arrival,
         double radius,
         double transition,
         LanePoints.Elevation elevation,
         LanePoints.Landing landing,
         boolean gradeOverride
      ) {
         this(path, departure, arrival, radius, transition, elevation, landing, gradeOverride, false);
      }

      public Options(
         LanePoints.Path path,
         LanePoints.Departure departure,
         LanePoints.Arrival arrival,
         double radius,
         double transition,
         LanePoints.Elevation elevation,
         LanePoints.Landing landing
      ) {
         this(path, departure, arrival, radius, transition, elevation, landing, false);
      }

      public Options(LanePoints.Path path, boolean sourceExtra, boolean targetExtra, double radius, double transition) {
         this(
            path,
            sourceExtra ? LanePoints.Departure.EXTRA : LanePoints.Departure.BRANCH,
            targetExtra ? LanePoints.Arrival.EXTRA : LanePoints.Arrival.MERGE,
            radius,
            transition,
            LanePoints.Elevation.AUTO,
            LanePoints.Landing.FLEXIBLE
         );
      }

      public Options(
         LanePoints.Path path,
         LanePoints.Departure departure,
         LanePoints.Arrival arrival,
         double radius,
         double transition,
         LanePoints.Elevation elevation,
         LanePoints.Landing landing,
         boolean gradeOverride,
         boolean allowTunnel,
         int lanes
      ) {
         if (path != null
            && departure != null
            && arrival != null
            && elevation != null
            && landing != null
            && RoadGeometry.finite(radius, transition)
            && !(radius < 8.0)
            && !(radius > 256.0)
            && !(transition < 8.0)
            && !(transition > 256.0)
            && lanes >= 1
            && lanes <= 2
            && (lanes <= 1 || arrival != LanePoints.Arrival.ADD && arrival != LanePoints.Arrival.REPLACE)) {
            this.path = path;
            this.departure = departure;
            this.arrival = arrival;
            this.radius = radius;
            this.transition = transition;
            this.elevation = elevation;
            this.landing = landing;
            this.gradeOverride = gradeOverride;
            this.allowTunnel = allowTunnel;
            this.lanes = lanes;
         } else {
            throw new IllegalArgumentException("半径与过渡长度须为 8–256 格，且连接模式有效");
         }
      }

      public LanePoints.Options lanes(int value) {
         return new LanePoints.Options(
            this.path, this.departure, this.arrival, this.radius, this.transition, this.elevation, this.landing, this.gradeOverride, this.allowTunnel, value
         );
      }

      public boolean sourceExtra() {
         return this.departure == LanePoints.Departure.EXTRA;
      }

      public boolean separatesLane() {
         return this.departure == LanePoints.Departure.DETACH || this.departure == LanePoints.Departure.TEMPORARY;
      }

      public boolean targetExtra() {
         return this.arrival == LanePoints.Arrival.EXTRA;
      }

      public LanePoints.Options withoutApproaches() {
         return new LanePoints.Options(
            this.path,
            LanePoints.Departure.BRANCH,
            LanePoints.Arrival.MERGE,
            this.radius,
            this.transition,
            this.elevation,
            this.landing,
            this.gradeOverride,
            this.allowTunnel,
            this.lanes
         );
      }
   }

   public static enum Origin {
      AUTOMATIC_START,
      AUTOMATIC_END,
      MANUAL;
   }

   public static enum Path {
      AUTO("自动"),
      RIGHT("右转"),
      LEFT_LOOP("左转回环"),
      DIRECT("直接连接"),
      LEFT("定向左转");

      public final String label;

      private Path(String label) {
         this.label = label;
      }
   }

   public static record Point(UUID id, LanePoints.Origin origin, int lane, RoadGeometry.V position, RoadGeometry.V anchor, double mergeLength) {
      public Point(UUID id, LanePoints.Origin origin, int lane, RoadGeometry.V position, RoadGeometry.V anchor) {
         this(id, origin, lane, position, anchor, 0.0);
      }

      public Point(UUID id, LanePoints.Origin origin, int lane, RoadGeometry.V position, RoadGeometry.V anchor, double mergeLength) {
         Objects.requireNonNull(id);
         Objects.requireNonNull(origin);
         if (Double.isFinite(mergeLength)
            && (mergeLength == 0.0 || !(Math.abs(mergeLength) < 8.0) && !(Math.abs(mergeLength) > 256.0))
            && (origin == LanePoints.Origin.MANUAL || mergeLength == 0.0)
            && lane >= 0
            && lane <= 31
            && position != null
            && RoadGeometry.finite(position.x(), position.y(), position.z())
            && (anchor == null || RoadGeometry.finite(anchor.x(), anchor.y(), anchor.z()))) {
            this.id = id;
            this.origin = origin;
            this.lane = lane;
            this.position = position;
            this.anchor = anchor;
            this.mergeLength = mergeLength;
         } else {
            throw new IllegalArgumentException("车道点数据无效");
         }
      }

      public Point(UUID id, LanePoints.Origin origin, int lane, RoadGeometry.V position) {
         this(id, origin, lane, position, null);
      }

      public boolean automatic() {
         return this.origin != LanePoints.Origin.MANUAL;
      }

      public LanePoints.Point at(LanePoints.Lane selected, RoadGeometry.Mesh mesh) {
         return new LanePoints.Point(
            this.id, this.origin, selected.index(), selected.position(), LaneSections.anchor(mesh, selected.station()), this.mergeLength
         );
      }

      public LanePoints.Point merge(double length) {
         return new LanePoints.Point(this.id, this.origin, this.lane, this.position, this.anchor, length);
      }
   }

   public static record Ref(UUID road, UUID point, UUID junction) {
      public Ref(UUID road, UUID point, UUID junction) {
         if (junction != null != (road != null || point != null) && (junction != null || road != null && point != null)) {
            this.road = road;
            this.point = point;
            this.junction = junction;
         } else {
            throw new IllegalArgumentException("连接目标必须为具体车道点或路口中心");
         }
      }

      public static LanePoints.Ref lane(UUID road, UUID point) {
         return new LanePoints.Ref(road, point, null);
      }

      public static LanePoints.Ref junction(UUID junction) {
         return new LanePoints.Ref(null, null, junction);
      }
   }
}
