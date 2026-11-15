package com.sora.splineroads.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.function.DoubleFunction;

public final class RailbedPath {
   public static final double MAX_LENGTH = 2048.0;
   public static final double ERROR = 0.003;
   public static final int MAX_SEGMENTS = 64;
   public static final int MAX_POINTS = 8192;

   public static RoadGeometry.V tangent(List<RoadGeometry.V> points, int index) {
      if (points.size() >= 3 && (index <= 0 || index + 1 >= points.size())) {
         int direction = index == 0 ? 1 : -1;
         RoadGeometry.V a = points.get(index);
         RoadGeometry.V b = points.get(index + direction);
         RoadGeometry.V c = points.get(index + 2 * direction);
         double h1 = b.sub(a).horizontalLength();
         double h2 = c.sub(b).horizontalLength();
         return b.sub(a).mul((2.0 * h1 + h2) / (h1 * (h1 + h2))).sub(c.sub(b).mul(h1 / (h2 * (h1 + h2)))).mul((double)direction).horizontalUnit();
      } else {
         return points.get(Math.min(points.size() - 1, index + 1)).sub(points.get(Math.max(0, index - 1))).horizontalUnit();
      }
   }

   private RailbedPath() {
   }

   public static record Node(int x, int y, int z) implements Comparable<RailbedPath.Node> {
      public Node(int x, int y, int z) {
         if (Math.abs((long)x) <= 29999984L && Math.abs((long)z) <= 29999984L && Math.abs((long)y) <= 2048L) {
            this.x = x;
            this.y = y;
            this.z = z;
         } else {
            throw new IllegalArgumentException("铁路节点坐标无效");
         }
      }

      public int compareTo(RailbedPath.Node b) {
         int c = Integer.compare(this.x, b.x);
         if (c == 0) {
            c = Integer.compare(this.y, b.y);
         }

         return c == 0 ? Integer.compare(this.z, b.z) : c;
      }

      public RoadGeometry.V center() {
         return new RoadGeometry.V((double)this.x + 0.5, (double)this.y, (double)this.z + 0.5);
      }
   }

   public static record Path(List<RailbedPath.Segment> segments) {
      public Path(List<RailbedPath.Segment> segments) {
         segments = List.copyOf(segments);
         if (!segments.isEmpty()
            && segments.size() <= 64
            && segments.stream().mapToInt(sx -> sx.points().size()).sum() <= 8192
            && !(segments.stream().mapToDouble(sx -> sx.points().get(sx.points().size() - 1).parameter() - sx.points().get(0).parameter()).sum() > 2048.001)) {
            HashSet<String> ids = new HashSet<>();

            for (int i = 0; i < segments.size(); i++) {
               RailbedPath.Segment s = segments.get(i);
               if (!ids.add(s.id())) {
                  throw new IllegalArgumentException("铁路区间重复经过同一轨段");
               }

               if (i > 0) {
                  RailbedPath.Segment before = segments.get(i - 1);
                  if (!before.to().equals(s.from())
                     || before.points().get(before.points().size() - 1).position().distance(s.points().get(0).position()) > 0.001) {
                     throw new IllegalArgumentException("铁路轨迹不连续");
                  }
               }
            }

            this.segments = segments;
         } else {
            throw new IllegalArgumentException("铁路区间超过预算，请分段选择");
         }
      }

      public double length() {
         return this.segments.stream().mapToDouble(s -> s.points().get(s.points().size() - 1).parameter() - s.points().get(0).parameter()).sum();
      }

      public List<RoadGeometry.V> positions() {
         ArrayList<RoadGeometry.V> out = new ArrayList<>();

         for (RailbedPath.Segment s : this.segments) {
            for (RailbedPath.Point p : s.points()) {
               if (out.isEmpty() || out.get(out.size() - 1).distance(p.position()) > 1.0E-8) {
                  out.add(p.position());
               }
            }
         }

         return List.copyOf(out);
      }

      public String fingerprint() {
         try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            for (RailbedPath.Segment s : this.segments) {
               digest.update((s.id() + ":" + s.reverse() + ":" + s.styles()).getBytes(StandardCharsets.UTF_8));
               ByteBuffer buffer = ByteBuffer.allocate(32);

               for (RailbedPath.Point p : s.points()) {
                  buffer.clear();
                  buffer.putDouble(p.parameter()).putDouble(p.position().x()).putDouble(p.position().y()).putDouble(p.position().z());
                  digest.update(buffer.array());
               }
            }

            return HexFormat.of().formatHex(digest.digest());
         } catch (NoSuchAlgorithmException var7) {
            throw new AssertionError(var7);
         }
      }
   }

   public static record Point(double parameter, RoadGeometry.V position) {
      public Point(double parameter, RoadGeometry.V position) {
         if (position != null
            && RoadGeometry.finite(parameter, position.x(), position.y(), position.z())
            && !(parameter < 0.0)
            && !(Math.abs(position.x()) > 2.9999984E7)
            && !(Math.abs(position.z()) > 2.9999984E7)
            && !(Math.abs(position.y()) > 2048.0)) {
            this.parameter = parameter;
            this.position = position;
         } else {
            throw new IllegalArgumentException("铁路采样坐标无效");
         }
      }
   }

   public static final class Sampler {
      private final DoubleFunction<RoadGeometry.V> curve;
      private final Deque<RailbedPath.Sampler.Span> pending = new ArrayDeque<>();
      private final List<RailbedPath.Point> points = new ArrayList<>();

      public Sampler(double length, DoubleFunction<RoadGeometry.V> curve) {
         if (Double.isFinite(length) && !(length <= 0.01) && !(length > 2048.0)) {
            this.curve = curve;
            RoadGeometry.V a = this.read(0.0);
            RoadGeometry.V b = this.read(length);
            this.points.add(new RailbedPath.Point(0.0, a));
            this.pending.push(new RailbedPath.Sampler.Span(0.0, a, length, b, 0));
         } else {
            throw new IllegalArgumentException("铁路长度无效或超过 2048 格，请分段选择");
         }
      }

      private RoadGeometry.V read(double s) {
         RoadGeometry.V p = this.curve.apply(s);
         if (RoadGeometry.finite(p.x(), p.y(), p.z()) && !(Math.abs(p.x()) > 2.9999984E7) && !(Math.abs(p.z()) > 2.9999984E7) && !(Math.abs(p.y()) > 2048.0)) {
            return p;
         } else {
            throw new IllegalArgumentException("MTR 轨道坐标超出有效范围");
         }
      }

      public boolean done() {
         return this.pending.isEmpty();
      }

      public void step() {
         RailbedPath.Sampler.Span span = this.pending.pop();
         double length = span.b - span.a;
         boolean split = length > 2.0;
         RoadGeometry.V middle = null;

         for (double f : new double[]{0.25, 0.5, 0.75}) {
            RoadGeometry.V p = this.read(span.a + length * f);
            if (f == 0.5) {
               middle = p;
            }

            if (p.distance(span.p.mul(1.0 - f).add(span.q.mul(f))) > 0.003) {
               split = true;
            }
         }

         if (split) {
            if (span.depth >= 22 || this.points.size() + this.pending.size() + 2 > 8192) {
               throw new IllegalArgumentException("铁路曲线细分超过预算，请缩短区间");
            }

            double mid = (span.a + span.b) / 2.0;
            this.pending.push(new RailbedPath.Sampler.Span(mid, middle, span.b, span.q, span.depth + 1));
            this.pending.push(new RailbedPath.Sampler.Span(span.a, span.p, mid, middle, span.depth + 1));
         } else {
            this.points.add(new RailbedPath.Point(span.b, span.q));
         }
      }

      public List<RailbedPath.Point> result() {
         if (!this.done()) {
            throw new IllegalStateException("轨道读取尚未完成");
         } else {
            return List.copyOf(this.points);
         }
      }

      private static record Span(double a, RoadGeometry.V p, double b, RoadGeometry.V q, int depth) {
      }
   }

   public static record Segment(
      String id, RailbedPath.Node from, RailbedPath.Node to, boolean reverse, double length, List<String> styles, List<RailbedPath.Point> points
   ) {
      public Segment(String id, RailbedPath.Node from, RailbedPath.Node to, boolean reverse, double length, List<String> styles, List<RailbedPath.Point> points) {
         styles = List.copyOf(styles);
         points = List.copyOf(points);
         if (points.size() < 2 || points.size() > 8192) {
            throw new IllegalArgumentException("铁路采样数量超限");
         } else if (id != null
            && !id.isBlank()
            && id.length() <= 128
            && from != null
            && to != null
            && !from.equals(to)
            && Double.isFinite(length)
            && !(length <= 0.01)
            && !(length > 2048.0)
            && styles.size() <= 64
            && !styles.stream().anyMatch(s -> s.length() > 256)) {
            double last = -1.0;

            for (RailbedPath.Point p : points) {
               if (p.parameter() <= last || p.parameter() > length + 1.0E-6) {
                  throw new IllegalArgumentException("铁路采样参数无效");
               }

               last = p.parameter();
            }

            this.id = id;
            this.from = from;
            this.to = to;
            this.reverse = reverse;
            this.length = length;
            this.styles = styles;
            this.points = points;
         } else {
            throw new IllegalArgumentException("铁路轨段信息无效");
         }
      }
   }
}
