package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

public final class RoadAttachments {
   private static final ThreadLocal<IdentityHashMap<RoadGeometry.Mesh, Map<RoadGeometry.V, Double>>> STATIONS = ThreadLocal.withInitial(IdentityHashMap::new);

   private static void finite(RoadGeometry.V p) {
      if (p == null || !RoadGeometry.finite(p.x(), p.y(), p.z())) {
         throw new IllegalArgumentException("附属点坐标无效");
      }
   }

   public static double station(RoadGeometry.Mesh m, RoadGeometry.V p) {
      IdentityHashMap<RoadGeometry.Mesh, Map<RoadGeometry.V, Double>> meshes = STATIONS.get();
      if (meshes.size() > 32) {
         meshes.clear();
      }

      return meshes.computeIfAbsent(m, k -> new HashMap<>()).computeIfAbsent(p, k -> RoadQueries.horizontal(m, p).sample().distance());
   }

   public static List<RoadAttachments.Point> sorted(RoadGeometry.Mesh m) {
      return m.settings()
         .options()
         .attachments()
         .points()
         .stream()
         .filter(p -> !p.elevated())
         .sorted(Comparator.comparingDouble(p -> station(m, p.position())))
         .toList();
   }

   public static RoadAttachments.Range range(RoadGeometry.Mesh m, double d) {
      double lo = 0.0;
      double hi = m.length();

      for (RoadAttachments.Point p : sorted(m)) {
         double at = station(m, p.position());
         if (!(at <= d + 1.0E-6)) {
            hi = at;
            break;
         }

         lo = Math.max(lo, at);
      }

      return new RoadAttachments.Range(lo, hi);
   }

   public static RoadAttachments.Paint paint(RoadGeometry.Mesh m, double d) {
      RoadProfile.Options o = m.settings().options();
      RoadAttachments.Paint result = new RoadAttachments.Paint(o.laneLines(), o.hideArrows());

      for (RoadAttachments.Span span : o.attachments().spans()) {
         double a = station(m, span.start());
         double b = station(m, span.end());
         if (d >= Math.min(a, b) - 1.0E-7 && d < Math.max(a, b) - 1.0E-7) {
            result = a <= b ? span.paint() : reverse(m, span.paint());
         }
      }

      return result;
   }

   private static RoadAttachments.Paint reverse(RoadGeometry.Mesh mesh, RoadAttachments.Paint paint) {
      int dividers = (int)RoadLaneLines.lines(mesh).stream().filter(l -> l.key().startsWith("divider:")).count();
      ArrayList<RoadLaneLines.Edit> lines = new ArrayList<>();

      for (RoadLaneLines.Edit e : paint.lines()) {
         String[] parts = e.key().split(":");
         int n = Integer.parseInt(parts[1]);
         String key = parts[0] + ":" + (parts[0].equals("divider") ? dividers - 1 - n : -n);

         RoadLaneLines.Pattern pattern = switch (e.pattern()) {
            case LEFT_SOLID_RIGHT_DASHED -> RoadLaneLines.Pattern.LEFT_DASHED_RIGHT_SOLID;
            case LEFT_DASHED_RIGHT_SOLID -> RoadLaneLines.Pattern.LEFT_SOLID_RIGHT_DASHED;
            case YELLOW_LEFT_SOLID_RIGHT_DASHED -> RoadLaneLines.Pattern.YELLOW_LEFT_DASHED_RIGHT_SOLID;
            case YELLOW_LEFT_DASHED_RIGHT_SOLID -> RoadLaneLines.Pattern.YELLOW_LEFT_SOLID_RIGHT_DASHED;
            default -> e.pattern();
         };
         lines.add(new RoadLaneLines.Edit(key, pattern, e.width()));
      }

      return new RoadAttachments.Paint(lines, paint.hideArrows());
   }

   public static RoadLaneLines.Edit line(RoadGeometry.Mesh m, double d, String key) {
      return paint(m, d).lines().stream().filter(e -> e.key().equals(key)).findFirst().orElse(new RoadLaneLines.Edit(key, RoadLaneLines.Pattern.DEFAULT, 0.12));
   }

   public static RoadAttachments.Data edit(RoadGeometry.Mesh m, RoadAttachments.Range range, RoadAttachments.Paint value) {
      ArrayList<RoadAttachments.Span> spans = new ArrayList<>();

      for (RoadAttachments.Span span : m.settings().options().attachments().spans()) {
         double a = station(m, span.start());
         double b = station(m, span.end());
         double lo = Math.min(a, b);
         double hi = Math.max(a, b);
         if (!(hi <= range.start() + 1.0E-7) && !(lo >= range.end() - 1.0E-7)) {
            RoadAttachments.Paint previous = a <= b ? span.paint() : reverse(m, span.paint());
            if (lo < range.start() - 1.0E-7) {
               spans.add(new RoadAttachments.Span(at(m, lo), at(m, range.start()), previous));
            }

            if (hi > range.end() + 1.0E-7) {
               spans.add(new RoadAttachments.Span(at(m, range.end()), at(m, hi), previous));
            }
         } else {
            spans.add(span);
         }
      }

      spans.add(new RoadAttachments.Span(at(m, range.start()), at(m, range.end()), value));
      return new RoadAttachments.Data(m.settings().options().attachments().points(), spans);
   }

   public static RoadGeometry.V at(RoadGeometry.Mesh m, double d) {
      return RoadStructures.sample(m, d).center();
   }

   public static List<RoadGeometry.Sample> splitPaint(RoadGeometry.Mesh m, List<RoadGeometry.Sample> source) {
      TreeSet<Double> cuts = new TreeSet<>();

      for (RoadGeometry.Sample s : source) {
         cuts.add(s.distance());
      }

      for (RoadAttachments.Point p : m.settings().options().attachments().points()) {
         cuts.add(station(m, p.position()));
      }

      for (RoadAttachments.Span s : m.settings().options().attachments().spans()) {
         cuts.add(station(m, s.start()));
         cuts.add(station(m, s.end()));
      }

      return cuts.stream().map(d -> RoadStructures.sample(m, d)).toList();
   }

   public static RoadGeometry.Mesh deform(RoadGeometry.Mesh base, RoadGeometry.Settings settings) {
      RoadAttachments.Data data = settings.options().attachments();
      if (data.points().stream().noneMatch(RoadAttachments.Point::controlled)) {
         return new RoadGeometry.Mesh(base.samples(), settings, base.min(), base.max(), base.length(), base.closed(), base.controlPoint(), base.controls());
      } else {
         TreeMap<Double, RoadGeometry.V> offsets = new TreeMap<>();
         offsets.put(0.0, new RoadGeometry.V(0.0, 0.0, 0.0));
         offsets.put(base.length(), new RoadGeometry.V(0.0, 0.0, 0.0));

         for (RoadAttachments.Point p : data.points()) {
            if (p.controlled()) {
               double d = station(base, p.anchor());
               RoadGeometry.V center = at(base, d);
               RoadGeometry.V delta = p.position().sub(center);
               if (settings.mode() != RoadGeometry.Mode.CURVE) {
                  delta = new RoadGeometry.V(0.0, delta.y(), 0.0);
               }

               if ((d < 1.0E-5 || d > base.length() - 1.0E-5) && delta.distance(new RoadGeometry.V(0.0, 0.0, 0.0)) > 1.0E-5) {
                  throw new IllegalArgumentException("与真实端点重合的附属点不能单独改变端点位置");
               }

               if (offsets.containsKey(d) && offsets.get(d).distance(delta) > 1.0E-5) {
                  throw new IllegalArgumentException("同一纵向位置的附属点控制目标不一致，请错开控制点位置");
               }

               offsets.put(d, delta);
            }
         }

         TreeSet<Double> stations = new TreeSet<>(offsets.keySet());

         for (RoadGeometry.Sample s : base.samples()) {
            stations.add(s.distance());
         }

         ArrayList<Double> knots = new ArrayList<>(offsets.keySet());
         ArrayList<RoadGeometry.V> positions = new ArrayList<>();
         ArrayList<Double> widths = new ArrayList<>();

         for (double dx : stations) {
            int i = 0;

            while (i + 1 < knots.size() - 1 && dx > knots.get(i + 1)) {
               i++;
            }

            double a = knots.get(i);
            double b = knots.get(i + 1);
            double t = (dx - a) / (b - a);
            double u = t * t * (3.0 - 2.0 * t);
            positions.add(at(base, dx).add(offsets.get(a).mul(1.0 - u).add(offsets.get(b).mul(u))));
            widths.add(RoadStructures.sample(base, dx).halfWidth());
         }

         ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

         for (int i = 0; i < positions.size(); i++) {
            RoadGeometry.V tangent = positions.get(Math.min(i + 1, positions.size() - 1)).sub(positions.get(Math.max(0, i - 1))).horizontalUnit();
            samples.add(new RoadGeometry.Sample(positions.get(i), tangent.left(), 0.0, widths.get(i)));
         }

         RoadGeometry.Mesh result = RoadRibbon.mesh(samples, settings);
         RoadRibbon.checkSelfIntersections(result, 4.0);
         return result;
      }
   }

   private RoadAttachments() {
   }

   public static record Data(List<RoadAttachments.Point> points, List<RoadAttachments.Span> spans) {
      public static final RoadAttachments.Data EMPTY = new RoadAttachments.Data(List.of(), List.of());

      public Data(List<RoadAttachments.Point> points, List<RoadAttachments.Span> spans) {
         points = List.copyOf(points);
         spans = List.copyOf(spans);
         if (spans.size() <= 512 && points.stream().map(RoadAttachments.Point::id).distinct().count() == (long)points.size()) {
            this.points = points;
            this.spans = spans;
         } else {
            throw new IllegalArgumentException("附属点重复或标线区间过多");
         }
      }

      public RoadAttachments.Data points(List<RoadAttachments.Point> value) {
         return new RoadAttachments.Data(value, this.spans);
      }
   }

   public static record Paint(List<RoadLaneLines.Edit> lines, boolean hideArrows) {
      public Paint(List<RoadLaneLines.Edit> lines, boolean hideArrows) {
         lines = List.copyOf(lines);
         if (lines.size() > 32) {
            throw new IllegalArgumentException("标线记录过多");
         } else {
            this.lines = lines;
            this.hideArrows = hideArrows;
         }
      }
   }

   public static record Point(UUID id, RoadGeometry.V position, RoadGeometry.V anchor, boolean controlled, RoadGeometry.V origin, boolean elevated) {
      public Point(UUID id, RoadGeometry.V position, RoadGeometry.V anchor, boolean controlled, RoadGeometry.V origin) {
         this(id, position, anchor, controlled, origin, false);
      }

      public Point(UUID id, RoadGeometry.V position, RoadGeometry.V anchor, boolean controlled) {
         this(id, position, anchor, controlled, anchor);
      }

      public Point(UUID id, RoadGeometry.V position, RoadGeometry.V anchor, boolean controlled, RoadGeometry.V origin, boolean elevated) {
         Objects.requireNonNull(id);
         RoadAttachments.finite(position);
         RoadAttachments.finite(anchor);
         RoadAttachments.finite(origin);
         this.id = id;
         this.position = position;
         this.anchor = anchor;
         this.controlled = controlled;
         this.origin = origin;
         this.elevated = elevated;
      }

      public RoadAttachments.Point at(RoadGeometry.V value) {
         return new RoadAttachments.Point(this.id, value, this.anchor, true, this.origin, this.elevated);
      }
   }

   public static record Range(double start, double end) {
   }

   public static record Span(RoadGeometry.V start, RoadGeometry.V end, RoadAttachments.Paint paint) {
      public Span(RoadGeometry.V start, RoadGeometry.V end, RoadAttachments.Paint paint) {
         RoadAttachments.finite(start);
         RoadAttachments.finite(end);
         Objects.requireNonNull(paint);
         this.start = start;
         this.end = end;
         this.paint = paint;
      }
   }
}
