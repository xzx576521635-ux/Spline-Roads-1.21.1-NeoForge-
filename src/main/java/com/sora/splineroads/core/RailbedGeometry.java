package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

public final class RailbedGeometry {
   public static final int MAX_TRACKS = 8;
   public static final double MAX_WIDTH = 64.0;
   public static final double HEIGHT_TOLERANCE = 0.01;
   public static final double WALL = 0.35;
   public static final double HEADROOM = 6.0;

   private static boolean official(RailbedPath.Path path) {
      return path.segments()
         .stream()
         .allMatch(
            s -> !s.styles().isEmpty()
                  && s.styles()
                     .stream()
                     .allMatch(
                        id -> id.equals("default")
                              || id.replaceFirst("_[12]$", "").equals("default_3d")
                              || id.replaceFirst("_[12]$", "").equals("default_3d_siding")
                     )
         );
   }

   public static String unsupported(List<RailbedPath.Path> paths) {
      for (RailbedPath.Path path : paths) {
         if (!official(path)) {
            return "轨道模型尚未标定：" + path.segments().stream().flatMap(s -> s.styles().stream()).distinct().toList();
         }
      }

      return null;
   }

   public static RailbedGeometry.Plan plan(List<RailbedPath.Path> paths, double requestedWidth, RoadGeometry.Structure mode) {
      if (!paths.isEmpty() && paths.size() <= 8) {
         if (paths.stream().map(p -> p.segments().stream().map(RailbedPath.Segment::id).sorted().toList()).distinct().count() != (long)paths.size()) {
            throw new IllegalArgumentException("不能重复加入同一轨道");
         } else {
            List<RailbedGeometry.IndexedPath> rails = paths.stream().map(p -> new RailbedGeometry.IndexedPath(p.positions())).toList();
            RailbedGeometry.Stations stations = stations(rails);
            List<RoadGeometry.V> base = stations.points();
            if (base.size() > 16384) {
               throw new IllegalArgumentException("铁路采样过多，请分段选择");
            } else {
               double lo = Double.POSITIVE_INFINITY;
               double hi = -lo;
               ArrayList<RoadGeometry.Sample> sections = new ArrayList<>();
               double swept = rails.stream().mapToDouble(r -> sweep(r.points())).max().orElse(0.0);
               double[][] offsets = new double[paths.size()][base.size()];
               double[] progress = new double[paths.size()];
               Arrays.fill(progress, Double.NaN);
               int[] directions = new int[paths.size()];

               for (int i = 0; i < base.size(); i++) {
                  RoadPlanningBudget.check();
                  RoadGeometry.V p = base.get(i);
                  RoadGeometry.V forward = stations.tangents().get(i);
                  RoadGeometry.V left = forward.left();
                  double floor = p.y();
                  double margin = 2.85 + swept;

                  for (int track = 0; track < rails.size(); track++) {
                     RailbedGeometry.Hit hit = track == 0
                        ? new RailbedGeometry.Hit(p, forward, (double)i)
                        : intersection(rails.get(track), p, forward, i == 0 || i == base.size() - 1);
                     if (Math.abs(hit.tangent.dot(forward)) < Math.cos(Math.toRadians(15.0))) {
                        throw new IllegalArgumentException("邻轨不平行，请缩短区间或分开生成");
                     }

                     if (Math.abs(hit.point.y() - p.y()) > 0.01) {
                        throw new IllegalArgumentException("轨道支承高度不一致：" + Math.round(p.x()) + ", " + Math.round(p.y()) + ", " + Math.round(p.z()));
                     }

                     floor = Math.min(floor, hit.point.y());
                     double lateral = hit.point.sub(p).dot(left);
                     offsets[track][i] = lateral;
                     lo = Math.min(lo, lateral - margin);
                     hi = Math.max(hi, lateral + margin);
                     if (!Double.isNaN(progress[track])) {
                        double delta = hit.parameter - progress[track];
                        int sign = delta > 1.0E-6 ? 1 : (delta < -1.0E-6 ? -1 : 0);
                        if (sign != 0 && directions[track] != 0 && sign != directions[track]) {
                           throw new IllegalArgumentException("邻轨存在折返或重复横断面，请分开生成：断面 " + i + "，轨道 " + track + "，变化 " + delta);
                        }

                        if (sign != 0) {
                           directions[track] = sign;
                        }
                     }

                     progress[track] = hit.parameter;
                  }

                  sections.add(new RoadGeometry.Sample(new RoadGeometry.V(p.x(), floor, p.z()), left, (double)i, 1.0));
               }

               for (int a = 0; a < rails.size(); a++) {
                  for (int b = a + 1; b < rails.size(); b++) {
                     double first = offsets[a][0] - offsets[b][0];

                     for (int i = 0; i < base.size(); i++) {
                        if (Math.abs(offsets[a][i] - offsets[b][i]) < 2.5 || first * (offsets[a][i] - offsets[b][i]) <= 0.0) {
                           throw new IllegalArgumentException("轨道间距不足或横向顺序交换，请分开生成");
                        }
                     }
                  }
               }

               double minimum = Math.ceil((hi - lo) * 2.0 - 1.0E-9) / 2.0;
               double width = requestedWidth == 0.0 ? minimum : requestedWidth;
               double offset = (hi + lo) / 2.0;
               if (Double.isFinite(width) && !(width < minimum - 1.0E-8) && !(width > 64.0)) {
                  RoadGeometry.Settings settings = new RoadGeometry.Settings(RoadGeometry.Mode.STRAIGHT, RoadGeometry.Style.RAILBED, width, 0.5, 0.35, 90.0)
                     .structure(RoadGeometry.Structure.GROUND);
                  List<RoadGeometry.Sample> samples = sections.stream()
                     .map(s -> new RoadGeometry.Sample(s.center().add(s.left().mul(offset)), s.left(), s.distance(), width / 2.0))
                     .toList();
                  return new RailbedGeometry.Plan(RoadRibbon.mesh(samples, settings), minimum, offset, paths.size(), paths);
               } else {
                  throw new IllegalArgumentException("路基总宽需为 " + minimum + "–64 格；超限请分组生成");
               }
            }
         }
      } else {
         throw new IllegalArgumentException("请选择 1–8 股轨道");
      }
   }

   public static double sweep(List<RoadGeometry.V> points) {
      double result = 0.0;

      for (int i = 1; i + 1 < points.size(); i++) {
         RoadGeometry.V before = tangent(points, i - 1);
         RoadGeometry.V after = tangent(points, i + 1);
         double angle = Math.acos(Math.max(-1.0, Math.min(1.0, before.dot(after))));
         double arc = points.get(i).distance(points.get(i - 1)) + points.get(i + 1).distance(points.get(i));
         if (angle > 1.0E-6) {
            double radius = arc / angle;
            if (radius < 16.0) {
               throw new IllegalArgumentException("铁路弯道半径小于首版净空预设范围，请分段或改用独立结构");
            }

            result = Math.max(result, (double)Math.max(324, 352) / (8.0 * radius));
         }
      }

      return result;
   }

   private static RoadGeometry.V tangent(List<RoadGeometry.V> points, int i) {
      return RailbedPath.tangent(points, i);
   }

   private static RailbedGeometry.Hit intersection(RailbedGeometry.IndexedPath indexed, RoadGeometry.V origin, RoadGeometry.V forward, boolean endpoint) {
      List<RoadGeometry.V> path = indexed.points();
      RailbedGeometry.Hit match = null;
      RoadGeometry.V side = forward.left().mul(64.0);

      for (int segment : indexed.index().query(RoadPathIndex.segment(origin.sub(side), origin.add(side)), 0.001)) {
         int i = segment + 1;
         RoadGeometry.V a = path.get(i - 1);
         RoadGeometry.V b = path.get(i);
         double da = a.sub(origin).dot(forward);
         double db = b.sub(origin).dot(forward);
         if (endpoint && i == 1 && Math.abs(da) <= 0.006) {
            da = 0.0;
         }

         if (endpoint && i == path.size() - 1 && Math.abs(db) <= 0.006) {
            db = 0.0;
         }

         if (!(da * db > 1.0E-9) && !(Math.abs(da - db) < 1.0E-10)) {
            double t = da / (da - db);
            if (!(t < -1.0E-7) && !(t > 1.0000001)) {
               t = Math.max(0.0, Math.min(1.0, t));
               RoadGeometry.V point = a.mul(1.0 - t).add(b.mul(t));
               if (!(point.sub(origin).horizontalLength() > 64.0)) {
                  RailbedGeometry.Hit candidate = new RailbedGeometry.Hit(point, b.sub(a).horizontalUnit(), (double)(i - 1) + t);
                  if (match != null && match.point.distance(point) > 1.0E-5) {
                     throw new IllegalArgumentException("邻轨与同一横断面多次相交，请分段选择");
                  }

                  match = candidate;
               }
            }
         }
      }

      if (match == null) {
         throw new IllegalArgumentException("邻轨未覆盖整个 A–B 区间，请缩短区间");
      } else {
         return match;
      }
   }

   private static RailbedGeometry.Stations stations(List<RailbedGeometry.IndexedPath> rails) {
      RailbedGeometry.IndexedPath base = rails.get(0);
      List<RoadGeometry.V> points = base.points();
      ArrayList<RoadGeometry.V> frames = new ArrayList<>();

      for (int i = 0; i < points.size(); i++) {
         frames.add(tangent(points, i));
      }

      TreeSet<Double> cuts = new TreeSet<>();

      for (int i = 0; i < points.size(); i++) {
         cuts.add((double)i);
      }

      for (int rail = 1; rail < rails.size(); rail++) {
         for (RoadGeometry.V p : rails.get(rail).points()) {
            RoadPlanningBudget.check();

            for (int segment : base.index().query(RoadPathIndex.segment(p, p), 64.0)) {
               RoadGeometry.V a = points.get(segment);
               RoadGeometry.V b = points.get(segment + 1);
               RoadGeometry.V fa = frames.get(segment);
               RoadGeometry.V fb = frames.get(segment + 1);
               double da = p.sub(a).dot(fa);
               double db = p.sub(b).dot(fb);
               if (!(da * db > 1.0E-9)) {
                  double lo = 0.0;
                  double hi = 1.0;

                  for (int k = 0; k < 24; k++) {
                     double t = (lo + hi) / 2.0;
                     double d = p.sub(a.mul(1.0 - t).add(b.mul(t))).dot(fa.mul(1.0 - t).add(fb.mul(t)));
                     if (d * da > 0.0) {
                        lo = t;
                     } else {
                        hi = t;
                     }
                  }

                  double t = (lo + hi) / 2.0;
                  if (Math.abs(da) < 1.0E-8) {
                     t = 0.0;
                  } else if (Math.abs(db) < 1.0E-8) {
                     t = 1.0;
                  }

                  RoadGeometry.V q = a.mul(1.0 - t).add(b.mul(t));
                  if (!(p.sub(q).horizontalLength() > 64.0)) {
                     cuts.add((double)segment + t);
                     if (cuts.size() > 16384) {
                        throw new IllegalArgumentException("多轨共同断面超过采样预算，请缩短区间");
                     }
                  }
               }
            }
         }
      }

      ArrayList<RoadGeometry.V> merged = new ArrayList<>();
      ArrayList<RoadGeometry.V> tangents = new ArrayList<>();
      double previous = -1.0;

      for (double at : cuts) {
         if (!(at - previous < 1.0E-6)) {
            int i = Math.min(points.size() - 2, (int)Math.floor(at));
            double tx = at - (double)i;
            merged.add(points.get(i).mul(1.0 - tx).add(points.get(i + 1).mul(tx)));
            tangents.add(frames.get(i).mul(1.0 - tx).add(frames.get(i + 1).mul(tx)).horizontalUnit());
            previous = at;
         }
      }

      return new RailbedGeometry.Stations(List.copyOf(merged), List.copyOf(tangents));
   }

   private RailbedGeometry() {
   }

   private static record Hit(RoadGeometry.V point, RoadGeometry.V tangent, double parameter) {
   }

   private static record IndexedPath(List<RoadGeometry.V> points, RoadBoundsIndex index) {
      IndexedPath(List<RoadGeometry.V> points) {
         this(points, index(points));
      }

      private static RoadBoundsIndex index(List<RoadGeometry.V> points) {
         ArrayList<RoadBoundsIndex.Bounds> bounds = new ArrayList<>();

         for (int i = 1; i < points.size(); i++) {
            bounds.add(RoadPathIndex.segment(points.get(i - 1), points.get(i)));
         }

         return new RoadBoundsIndex(bounds, 16);
      }
   }

   public static record Plan(RoadGeometry.Mesh mesh, double minimumWidth, double offset, int tracks, List<RailbedPath.Path> paths) {
      public Plan(RoadGeometry.Mesh mesh, double minimumWidth, double offset, int tracks, List<RailbedPath.Path> paths) {
         paths = List.copyOf(paths);
         this.mesh = mesh;
         this.minimumWidth = minimumWidth;
         this.offset = offset;
         this.tracks = tracks;
         this.paths = paths;
      }
   }

   private static record Stations(List<RoadGeometry.V> points, List<RoadGeometry.V> tangents) {
   }
}
