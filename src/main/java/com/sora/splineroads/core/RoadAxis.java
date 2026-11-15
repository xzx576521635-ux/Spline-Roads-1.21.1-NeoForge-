package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RoadAxis {
   private final List<RoadGeometry.V> points;
   private final double[] stations;
   private final RoadGeometry.V[] tangents;

   public RoadAxis(List<RoadGeometry.V> source) {
      ArrayList<RoadGeometry.V> clean = new ArrayList<>();

      for (RoadGeometry.V p : source) {
         if (!RoadGeometry.finite(p.x(), p.y(), p.z())) {
            throw new IllegalArgumentException("主路路径数值无效");
         }

         if (clean.isEmpty() || p.sub(clean.get(clean.size() - 1)).horizontalLength() > 1.0E-6) {
            clean.add(p);
         }
      }

      if (clean.size() >= 2 && clean.size() <= 16384) {
         this.points = List.copyOf(clean);
         this.stations = new double[this.points.size()];
         this.tangents = new RoadGeometry.V[this.points.size()];

         for (int i = 1; i < this.points.size(); i++) {
            this.stations[i] = this.stations[i - 1] + this.points.get(i).sub(this.points.get(i - 1)).horizontalLength();
         }

         for (int i = 0; i < this.points.size(); i++) {
            RoadGeometry.V before = this.points.get(Math.max(1, i)).sub(this.points.get(Math.max(0, i - 1))).horizontalUnit();
            RoadGeometry.V after = this.points
               .get(Math.min(i + 1, this.points.size() - 1))
               .sub(this.points.get(Math.min(i, this.points.size() - 2)))
               .horizontalUnit();
            if (i > 0 && i < this.points.size() - 1 && before.dot(after) < 0.985) {
               throw new IllegalArgumentException("选中的主路含有折角；请先改为平滑曲线");
            }

            this.tangents[i] = i == 0 ? after : (i == this.points.size() - 1 ? before : before.add(after).horizontalUnit());
         }
      } else {
         throw new IllegalArgumentException("主路路径采样无效");
      }
   }

   public static RoadAxis straight(RoadGeometry.V a, RoadGeometry.V b) {
      return new RoadAxis(List.of(a, b));
   }

   public static RoadAxis of(RoadGeometry.Mesh mesh) {
      return new RoadAxis(mesh.samples().stream().map(RoadGeometry.Sample::center).toList());
   }

   public List<RoadGeometry.V> points() {
      return this.points;
   }

   public double length() {
      return this.stations[this.stations.length - 1];
   }

   public boolean curved() {
      RoadGeometry.V a = this.points.get(0);
      RoadGeometry.V u = this.points.get(this.points.size() - 1).sub(a).horizontalUnit();
      return this.points.stream().anyMatch(p -> Math.abs(p.sub(a).dot(u.left())) > 0.1);
   }

   public boolean flat() {
      double y = this.points.get(0).y();
      return this.points.stream().allMatch(p -> Math.abs(p.y() - y) < 0.001);
   }

   private int segment(double d) {
      int i = Arrays.binarySearch(this.stations, d);
      return Math.max(0, Math.min(this.stations.length - 2, i >= 0 ? i : -i - 2));
   }

   public RoadGeometry.V at(double d) {
      if (d < 0.0) {
         return this.points.get(0).add(this.tangents[0].mul(d));
      } else if (d > this.length()) {
         return this.points.get(this.points.size() - 1).add(this.tangents[this.tangents.length - 1].mul(d - this.length()));
      } else {
         int i = this.segment(d);
         double t = (d - this.stations[i]) / (this.stations[i + 1] - this.stations[i]);
         return this.points.get(i).add(this.points.get(i + 1).sub(this.points.get(i)).mul(t));
      }
   }

   public RoadGeometry.V tangent(double d) {
      int i = this.segment(d);
      double t = Math.max(0.0, Math.min(1.0, (d - this.stations[i]) / (this.stations[i + 1] - this.stations[i])));
      return this.tangents[i].mul(1.0 - t).add(this.tangents[i + 1].mul(t)).horizontalUnit();
   }

   public double project(RoadGeometry.V point) {
      double best = Double.POSITIVE_INFINITY;
      double result = 0.0;

      for (int i = 0; i < this.points.size() - 1; i++) {
         RoadGeometry.V a = this.points.get(i);
         RoadGeometry.V delta = this.points.get(i + 1).sub(a);
         double span = this.stations[i + 1] - this.stations[i];
         double t = point.sub(a).dot(delta.horizontalUnit());
         t = Math.max(i == 0 ? -4096.0 : 0.0, Math.min(i == this.points.size() - 2 ? span + 4096.0 : span, t));
         double error = point.sub(a.add(delta.horizontalUnit().mul(t))).horizontalLength();
         if (error < best) {
            best = error;
            result = this.stations[i] + t;
         }
      }

      return result;
   }

   public RoadAxis slice(double start, double end, double yShift) {
      if (end - start < 1.0) {
         throw new IllegalArgumentException("主路剩余长度不足");
      } else {
         ArrayList<RoadGeometry.V> p = new ArrayList<>();
         p.add(this.at(start).add(new RoadGeometry.V(0.0, yShift, 0.0)));

         for (int i = 0; i < this.points.size(); i++) {
            if (this.stations[i] > start + 1.0E-6 && this.stations[i] < end - 1.0E-6) {
               p.add(this.points.get(i).add(new RoadGeometry.V(0.0, yShift, 0.0)));
            }
         }

         p.add(this.at(end).add(new RoadGeometry.V(0.0, yShift, 0.0)));
         return new RoadAxis(p);
      }
   }

   public static RoadAxis.Crossing crossing(RoadAxis a, RoadAxis b, boolean terminating) {
      ArrayList<RoadAxis.Crossing> matches = new ArrayList<>();

      for (int i = 0; i < a.points.size() - 1; i++) {
         for (int j = 0; j < b.points.size() - (terminating ? 0 : 1); j++) {
            RoadGeometry.V p = a.points.get(i);
            RoadGeometry.V q = b.at(j == b.points.size() - 1 ? b.length() : b.stations[j]);
            RoadGeometry.V da = a.points.get(i + 1).sub(p);
            RoadGeometry.V db = j == b.points.size() - 1 ? b.tangent(b.length()).mul(2048.0) : b.points.get(j + 1).sub(q);
            double det = cross(da, db);
            if (!(Math.abs(det) < 1.0E-8)) {
               double x = cross(q.sub(p), db) / det;
               double z = cross(q.sub(p), da) / det;
               if (!(x < -1.0E-7) && !(x > 1.0000001) && !(z < -1.0E-7) && !(z > 1.0000001)) {
                  double sa = a.stations[i] + x * da.horizontalLength();
                  double sb = b.stations[j] + z * db.horizontalLength();
                  if (matches.stream().noneMatch(c -> Math.abs(c.first - sa) < 0.05 && Math.abs(c.second - sb) < 0.05)) {
                     matches.add(new RoadAxis.Crossing(sa, sb, a.at(sa)));
                  }
               }
            }
         }
      }

      if (matches.size() != 1) {
         throw new IllegalArgumentException(matches.isEmpty() ? "两条主路曲线未相交；三向 C 支路应朝向 AB" : "两条主路多次相交，请缩小选区至一个交点");
      } else {
         return matches.get(0);
      }
   }

   public static double cross(RoadGeometry.V a, RoadGeometry.V b) {
      return a.x() * b.z() - a.z() * b.x();
   }

   public static record Crossing(double first, double second, RoadGeometry.V point) {
   }
}
