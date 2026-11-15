package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MultiFanPaint {
   public static boolean applies(RoadGeometry.Mesh m) {
      return m.settings().style().ramp() && m.settings().options().routing().fanArms() >= 5;
   }

   private static MultiFanPaint.Branch branch(RoadGeometry.Mesh m, boolean first) {
      RoadGeometry.Sample root = first ? m.first() : m.last();
      RoadProfile.Routing r = m.settings().options().routing();
      return new MultiFanPaint.Branch(m, first, root.center(), root.left().left().mul(first ? -1.0 : 1.0), first ? r.fanStart() : r.fanEnd());
   }

   private static List<MultiFanPaint.Branch> group(RoadGeometry.Mesh m, List<RoadGeometry.Mesh> neighbors, boolean first) {
      MultiFanPaint.Branch own = branch(m, first);
      ArrayList<MultiFanPaint.Branch> out = new ArrayList<>();
      out.add(own);

      for (RoadGeometry.Mesh peer : neighbors) {
         if (peer != m && applies(peer) && peer.settings().options().routing().fanArms() == m.settings().options().routing().fanArms()) {
            MultiFanPaint.Branch b = branch(peer, first);
            RoadGeometry.V delta = b.origin.sub(own.origin);
            if (Math.abs(delta.y()) < 0.05 && Math.abs(delta.dot(own.axis)) < 0.15 && delta.horizontalLength() < 16.0 && b.axis.dot(own.axis) > 0.99999) {
               out.add(b);
            }
         }
      }

      RoadGeometry.V side = own.axis.left();
      out.sort(
         Comparator.<MultiFanPaint.Branch>comparingDouble(bx -> bx.at(bx.reach).center().dot(side))
            .thenComparingDouble(bx -> bx.mesh.first().center().x())
            .thenComparingDouble(bx -> bx.mesh.last().center().z())
      );
      return out;
   }

   public static List<RoadJunction.Zone> zones(RoadGeometry.Mesh m) {
      MultiFanPaint.Branch a = branch(m, true);
      MultiFanPaint.Branch b = branch(m, false);
      return List.of(new RoadJunction.Zone(0.0, a.at(a.reach).distance()), new RoadJunction.Zone(b.at(b.reach).distance(), m.length()));
   }

   private static void line(List<RoadJunction.Paint> out, RoadGeometry.V a, RoadGeometry.V b, double width) {
      RoadGeometry.V side = b.sub(a).horizontalUnit().left().mul(width / 2.0);
      if (!(a.distance(b) < 1.0E-5)) {
         out.add(new RoadJunction.Paint(List.of(a.add(side), a.sub(side), b.sub(side), b.add(side)), 15593186));
      }
   }

   private static MultiFanPaint.Slice slice(List<MultiFanPaint.Branch> group, double d) {
      List<RoadGeometry.Sample> samples = group.stream().map(b -> b.at(d)).toList();
      RoadGeometry.V side = group.get(0).axis.left();
      double[] lateral = new double[samples.size()];
      double[] half = new double[samples.size()];

      for (int i = 0; i < samples.size(); i++) {
         RoadGeometry.Sample p = samples.get(i);
         lateral[i] = p.center().dot(side);
         half[i] = p.halfWidth() / Math.max(0.8, Math.abs(p.left().dot(side)));
      }

      return new MultiFanPaint.Slice(samples, lateral, half, side);
   }

   private static RoadGeometry.V point(MultiFanPaint.Slice s, int i, double lateral) {
      RoadGeometry.Sample p = s.points.get(i);
      return p.center().add(s.side.mul(lateral - s.lateral[i]));
   }

   private static double low(MultiFanPaint.Slice s, int i) {
      return i == 0 ? s.lateral[i] - s.half[i] : Math.max(s.lateral[i] - s.half[i], (s.lateral[i - 1] + s.lateral[i]) / 2.0);
   }

   private static double high(MultiFanPaint.Slice s, int i) {
      return i == s.points.size() - 1 ? s.lateral[i] + s.half[i] : Math.min(s.lateral[i] + s.half[i], (s.lateral[i] + s.lateral[i + 1]) / 2.0);
   }

   public static List<RoadJunction.Paint> markings(RoadGeometry.Mesh m, List<RoadGeometry.Mesh> neighbors) {
      ArrayList<RoadJunction.Paint> out = new ArrayList<>();

      for (boolean first : new boolean[]{true, false}) {
         List<MultiFanPaint.Branch> g = group(m, neighbors, first);
         if (g.get(0).mesh == m) {
            double reach = g.stream().mapToDouble(MultiFanPaint.Branch::reach).min().orElse(0.0);
            MultiFanPaint.Slice entry = slice(g, 16.0);
            double low = low(entry, 0);
            double high = high(entry, g.size() - 1);
            int lanes = high - low >= 6.4 ? 2 : 1;
            ArrayList<RoadSurface.Face> arrows = new ArrayList<>();

            for (int lane = 0; lane < lanes; lane++) {
               JunctionPaint.arrow(
                  arrows, point(entry, 0, low + ((double)lane + 0.5) * (high - low) / (double)lanes), g.get(0).axis.mul(first ? 1.0 : -1.0), 1, 0.7
               );
            }

            if (!m.settings().options().hideArrows()) {
               for (RoadSurface.Face arrow : arrows) {
                  out.add(new RoadJunction.Paint(arrow.points(), arrow.color()));
               }
            }

            for (double d = 0.0; d < reach; d++) {
               double end = Math.min(reach, d + 1.5);
               MultiFanPaint.Slice a = slice(g, d);
               MultiFanPaint.Slice b = slice(g, end);
               boolean dash = ((int)(d / 3.0) & 1) == 0;
               if (dash && RoadProfile.catalog(m.settings().style()).lanes() == 2) {
                  for (int i = 0; i < g.size(); i++) {
                     if (!(high(a, i) - low(a, i) < 6.6) && !(high(b, i) - low(b, i) < 6.6)) {
                        line(out, point(a, i, (low(a, i) + high(a, i)) / 2.0), point(b, i, (low(b, i) + high(b, i)) / 2.0), 0.15);
                     }
                  }
               }

               for (int ix = 0; ix + 1 < g.size(); ix++) {
                  double sep = a.lateral[ix + 1] - a.lateral[ix];
                  double sepB = b.lateral[ix + 1] - b.lateral[ix];
                  double overlap = a.half[ix] + a.half[ix + 1] - sep;
                  double overlapB = b.half[ix] + b.half[ix + 1] - sepB;
                  double mid = (a.lateral[ix] + a.lateral[ix + 1]) / 2.0;
                  double midB = (b.lateral[ix] + b.lateral[ix + 1]) / 2.0;
                  if (!(overlap < 0.0) && !(overlapB < 0.0)) {
                     boolean central = ix == (g.size() - 1) / 2 && RoadProfile.catalog(m.settings().style()).lanes() == 2;
                     if (dash && overlap > 1.6 && overlapB > 1.6 && (central || sep >= 3.2 && sepB >= 3.2)) {
                        line(out, point(a, ix, mid), point(b, ix, midB), 0.15);
                     }

                     if (overlap <= 1.6 && overlapB <= 1.6) {
                        double wa = Math.min(0.65, Math.max(0.02, (1.6 - overlap) * 0.4));
                        double wb = Math.min(0.65, Math.max(0.02, (1.6 - overlapB) * 0.4));

                        for (int side : new int[]{-1, 1}) {
                           line(out, point(a, ix, mid + (double)side * wa), point(b, ix, midB + (double)side * wb), 0.15);
                        }

                        if ((int)(d / 1.5) % 3 == 0) {
                           RoadGeometry.V tip = point(b, ix, midB);
                           line(out, point(a, ix, mid - wa), tip, 0.18);
                           line(out, tip, point(a, ix, mid + wa), 0.18);
                        }
                     }
                  }
               }
            }
         }
      }

      return List.copyOf(out);
   }

   private MultiFanPaint() {
   }

   private static record Branch(RoadGeometry.Mesh mesh, boolean first, RoadGeometry.V origin, RoadGeometry.V axis, double reach) {
      RoadGeometry.Sample root() {
         return this.first ? this.mesh.first() : this.mesh.last();
      }

      RoadGeometry.Sample at(double station) {
         if (station <= 0.0) {
            return this.root();
         } else {
            List<RoadGeometry.Sample> samples = this.mesh.samples();
            int size = samples.size();
            double max = Math.min(this.mesh.length(), this.reach * 1.5 + 16.0);
            int lo = 0;
            int hi = size - 1;

            while (hi - lo > 1) {
               int mid = (lo + hi) / 2;
               RoadGeometry.Sample p = samples.get(this.first ? mid : size - 1 - mid);
               double d = this.first ? p.distance() : this.mesh.length() - p.distance();
               if (d < max) {
                  lo = mid;
               } else {
                  hi = mid;
               }
            }

            lo = 0;

            while (hi - lo > 1) {
               int mid = (lo + hi) / 2;
               RoadGeometry.Sample p = samples.get(this.first ? mid : size - 1 - mid);
               if (p.center().sub(this.origin).dot(this.axis) < station) {
                  lo = mid;
               } else {
                  hi = mid;
               }
            }

            RoadGeometry.Sample a = samples.get(this.first ? lo : size - 1 - lo);
            RoadGeometry.Sample b = samples.get(this.first ? hi : size - 1 - hi);
            double x = a.center().sub(this.origin).dot(this.axis);
            double y = b.center().sub(this.origin).dot(this.axis);
            double t = Math.max(0.0, Math.min(1.0, (station - x) / Math.max(1.0E-9, y - x)));
            return new RoadGeometry.Sample(
               a.center().add(b.center().sub(a.center()).mul(t)),
               a.left().add(b.left().sub(a.left()).mul(t)).horizontalUnit(),
               a.distance() + (b.distance() - a.distance()) * t,
               a.halfWidth() + (b.halfWidth() - a.halfWidth()) * t
            );
         }
      }
   }

   private static record Slice(List<RoadGeometry.Sample> points, double[] lateral, double[] half, RoadGeometry.V side) {
   }
}
