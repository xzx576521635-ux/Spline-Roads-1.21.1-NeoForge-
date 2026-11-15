package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class RoadThroughCurve {
   private final List<RoadGeometry.V> points;
   private final List<RoadGeometry.V> tangents;
   private final double[] knots;

   RoadThroughCurve(RoadGeometry.Node a, RoadGeometry.Node b, RoadGeometry.Settings settings) {
      ArrayList<RoadGeometry.V> p = new ArrayList<>();
      p.add(a.position());
      settings.options().routing().controls().forEach(c -> p.add(c.position()));
      p.add(b.position());
      this.knots = new double[p.size()];
      double total = 0.0;

      for (int i = 1; i < p.size(); i++) {
         double length = p.get(i).sub(p.get(i - 1)).horizontalLength();
         if (length < 2.0) {
            throw new IllegalArgumentException("相邻经过点至少相距2格");
         }

         if (p.get(i).distance(a.position()) > 2048.0 || p.get(i).distance(b.position()) > 2048.0) {
            throw new IllegalArgumentException("经过点距离端点不能超过2048格");
         }

         total += length;
         this.knots[i] = total;
      }

      if (total > 4096.0) {
         throw new IllegalArgumentException("经过点路线过长，请分段连接");
      } else {
         for (int i = 1; i < this.knots.length; i++) {
            this.knots[i] = this.knots[i] / total;
         }

         ArrayList<RoadGeometry.V> d = new ArrayList<>();
         double strength = Math.min(1.5, settings.tension() * 3.0);
         d.add(a.direction().add(new RoadGeometry.V(0.0, a.grade(), 0.0)).mul(total * strength));

         for (int i = 1; i < p.size() - 1; i++) {
            double left = this.knots[i] - this.knots[i - 1];
            double right = this.knots[i + 1] - this.knots[i];
            RoadGeometry.V incoming = p.get(i).sub(p.get(i - 1)).mul(1.0 / left);
            RoadGeometry.V outgoing = p.get(i + 1).sub(p.get(i)).mul(1.0 / right);
            d.add(incoming.mul(right).add(outgoing.mul(left)).mul(strength / (left + right)));
         }

         d.add(b.direction().add(new RoadGeometry.V(0.0, b.grade(), 0.0)).mul(total * strength));
         this.points = List.copyOf(p);
         this.tangents = List.copyOf(d);
      }
   }

   void knots(Set<Double> output) {
      for (double t : this.knots) {
         output.removeIf(v -> Math.abs(v - t) < 1.0E-10);
         output.add(t);
      }
   }

   private int segment(double t) {
      int i = Arrays.binarySearch(this.knots, t);
      return Math.max(0, Math.min(this.knots.length - 2, i >= 0 ? i : -i - 2));
   }

   RoadGeometry.V point(double t) {
      int i = this.segment(t);
      double h = this.knots[i + 1] - this.knots[i];
      double u = (t - this.knots[i]) / h;
      double u2 = u * u;
      double u3 = u2 * u;
      return this.points
         .get(i)
         .mul(2.0 * u3 - 3.0 * u2 + 1.0)
         .add(this.tangents.get(i).mul(h * (u3 - 2.0 * u2 + u)))
         .add(this.points.get(i + 1).mul(-2.0 * u3 + 3.0 * u2))
         .add(this.tangents.get(i + 1).mul(h * (u3 - u2)));
   }

   RoadGeometry.V tangent(double t) {
      int i = this.segment(t);
      double h = this.knots[i + 1] - this.knots[i];
      double u = (t - this.knots[i]) / h;
      return this.points
         .get(i)
         .mul((6.0 * u * u - 6.0 * u) / h)
         .add(this.tangents.get(i).mul(3.0 * u * u - 4.0 * u + 1.0))
         .add(this.points.get(i + 1).mul((-6.0 * u * u + 6.0 * u) / h))
         .add(this.tangents.get(i + 1).mul(3.0 * u * u - 2.0 * u));
   }
}
