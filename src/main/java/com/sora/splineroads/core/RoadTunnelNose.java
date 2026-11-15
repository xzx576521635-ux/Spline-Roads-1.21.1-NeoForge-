package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.DoubleUnaryOperator;

public final class RoadTunnelNose {
   public static RoadTunnelNose.Join build(
      RoadGeometry.Mesh host, RoadGeometry.Mesh ramp, double from, double to, DoubleUnaryOperator hostCeiling, DoubleUnaryOperator rampCeiling
   ) {
      ArrayList<RoadStructures.Part> volume = new ArrayList<>();
      ArrayList<RoadStructures.Part> solid = new ArrayList<>();
      ArrayList<RoadStructures.Part> pavement = new ArrayList<>();
      TreeSet<Double> stations = new TreeSet<>();
      from = Math.max(0.0, from);
      to = Math.min(ramp.length(), to);
      if (to <= from) {
         return new RoadTunnelNose.Join(List.of(), List.of(), List.of());
      } else {
         stations.add(from);
         stations.add(to);

         for (double d = from; d < to; d += 0.5) {
            stations.add(d);
         }

         for (RoadGeometry.Sample at : ramp.samples()) {
            if (at.distance() > from && at.distance() < to) {
               stations.add(at.distance());
            }
         }

         RoadTunnelNose.Edge previous = null;

         for (double d : stations) {
            RoadGeometry.Sample atx = RoadStructures.sample(ramp, d);
            RoadQueries.Projection query = RoadQueries.horizontal(host, atx.center());
            RoadGeometry.Sample main = query.sample();
            int side = query.lateral() < 0.0 ? -1 : 1;
            double inner = Math.signum(main.center().sub(atx.center()).dot(atx.left()));
            RoadGeometry.V a = main.at((double)side * (main.halfWidth() + 0.08), 0.0);
            RoadGeometry.V b = atx.at(inner * (atx.halfWidth() + 0.08), 0.0);
            double gap = b.sub(a).dot(main.left().mul((double)side));
            double hc = hostCeiling.applyAsDouble(main.distance());
            double rc = rampCeiling.applyAsDouble(d);
            RoadTunnelNose.Edge current = null;
            if (hc >= 1.0 && rc >= 1.0 && Math.abs(atx.left().dot(main.left())) > 0.45) {
               double floor = Math.min(a.y() - host.settings().thickness(), b.y() - ramp.settings().thickness());
               double top = Math.min(a.y() + hc, b.y() + rc);
               if (top - floor > 1.0) {
                  current = new RoadTunnelNose.Edge(
                     a, b, main.left().mul((double)side), atx.left().mul(inner), gap, floor, top, Math.max(a.y() + hc, b.y() + rc)
                  );
               }
            }

            if (previous != null && current != null) {
               add(volume, previous, current, -0.28, 3.1, 0.15);
               add(solid, previous, current, 0.35, 3.1, 0.0);
               apron(pavement, previous, current);
            }

            previous = current;
         }

         return new RoadTunnelNose.Join(List.copyOf(volume), List.copyOf(solid), List.copyOf(pavement));
      }
   }

   private static void add(List<RoadStructures.Part> out, RoadTunnelNose.Edge a, RoadTunnelNose.Edge b, double minimum, double maximum, double padding) {
      double lo = 0.0;
      double hi = 1.0;
      double change = b.gap() - a.gap();
      if (Math.abs(change) < 1.0E-10) {
         if (a.gap() < minimum || a.gap() > maximum) {
            return;
         }
      } else {
         double first = (minimum - a.gap()) / change;
         double last = (maximum - a.gap()) / change;
         lo = Math.max(0.0, Math.min(first, last));
         hi = Math.min(1.0, Math.max(first, last));
         if (hi <= lo + 1.0E-8) {
            return;
         }
      }

      RoadTunnelNose.Edge x = a.mix(b, lo);
      RoadTunnelNose.Edge y = a.mix(b, hi);
      x = new RoadTunnelNose.Edge(
         x.host().sub(x.hostNormal().mul(padding)),
         x.ramp().sub(x.rampNormal().mul(padding)),
         x.hostNormal(),
         x.rampNormal(),
         x.gap(),
         x.floor(),
         x.top(),
         x.envelope()
      );
      y = new RoadTunnelNose.Edge(
         y.host().sub(y.hostNormal().mul(padding)),
         y.ramp().sub(y.rampNormal().mul(padding)),
         y.hostNormal(),
         y.rampNormal(),
         y.gap(),
         y.floor(),
         y.top(),
         y.envelope()
      );
      RoadGeometry.V h0 = new RoadGeometry.V(x.host().x(), x.floor(), x.host().z());
      RoadGeometry.V r0 = new RoadGeometry.V(x.ramp().x(), x.floor(), x.ramp().z());
      RoadGeometry.V h1 = new RoadGeometry.V(y.host().x(), y.floor(), y.host().z());
      RoadGeometry.V r1 = new RoadGeometry.V(y.ramp().x(), y.floor(), y.ramp().z());
      if (!(Math.abs(JunctionPaint.area(List.of(h0, r0, r1, h1))) < 1.0E-9)) {
         RoadGeometry.V c0 = h0.add(r0).mul(0.5);
         RoadGeometry.V c1 = h1.add(r1).mul(0.5);
         double ha = x.top() - x.floor();
         double hb = y.top() - y.floor();
         out.add(
            new RoadStructures.Part(
               c0,
               c1,
               Math.max(h0.distance(r0), h1.distance(r1)),
               Math.max(ha, hb),
               false,
               RoadStructures.Material.TUNNEL,
               h0.sub(c0),
               h1.sub(c1),
               "sr:tunnel_nose",
               new RoadStructures.Heights(ha, ha, hb, hb)
            )
         );
      }
   }

   private static void apron(List<RoadStructures.Part> out, RoadTunnelNose.Edge a, RoadTunnelNose.Edge b) {
      double lo = 0.0;
      double hi = 1.0;
      double change = b.gap() - a.gap();
      if (Math.abs(change) < 1.0E-10) {
         if (a.gap() < 0.0 || a.gap() > 3.1) {
            return;
         }
      } else {
         double x = -a.gap() / change;
         double y = (3.1 - a.gap()) / change;
         lo = Math.max(0.0, Math.min(x, y));
         hi = Math.min(1.0, Math.max(x, y));
         if (hi <= lo + 1.0E-8) {
            return;
         }
      }

      double rise = a.host().y() - a.ramp().y();
      double slope = b.host().y() - b.ramp().y() - rise;
      if (Math.abs(slope) < 1.0E-10) {
         if (Math.abs(rise) > 0.25) {
            return;
         }
      } else {
         double u = (-0.25 - rise) / slope;
         double v = (0.25 - rise) / slope;
         lo = Math.max(lo, Math.min(u, v));
         hi = Math.min(hi, Math.max(u, v));
         if (hi <= lo + 1.0E-8) {
            return;
         }
      }

      RoadTunnelNose.Edge xx = a.mix(b, lo);
      RoadTunnelNose.Edge yx = a.mix(b, hi);
      RoadGeometry.V h0 = xx.host().sub(xx.hostNormal().mul(0.15));
      RoadGeometry.V r0 = xx.ramp().sub(xx.rampNormal().mul(0.15));
      RoadGeometry.V h1 = yx.host().sub(yx.hostNormal().mul(0.15));
      RoadGeometry.V r1 = yx.ramp().sub(yx.rampNormal().mul(0.15));
      if (!(Math.abs(JunctionPaint.area(List.of(h0, r0, r1, h1))) < 1.0E-9)) {
         RoadGeometry.V c0 = h0.add(r0).mul(0.5);
         RoadGeometry.V c1 = h1.add(r1).mul(0.5);
         out.add(
            new RoadStructures.Part(
               c0.add(new RoadGeometry.V(0.0, -0.25, 0.0)),
               c1.add(new RoadGeometry.V(0.0, -0.25, 0.0)),
               Math.max(h0.distance(r0), h1.distance(r1)),
               0.25,
               false,
               RoadStructures.Material.CONCRETE,
               h0.sub(c0),
               h1.sub(c1),
               "sr:tunnel_apron"
            )
         );
      }
   }

   public static List<RoadStructures.Part> apply(List<RoadStructures.Part> parts, RoadTunnelNose.Join join) {
      if (join.volume().isEmpty()) {
         return parts;
      } else {
         RoadShellOpening mask = RoadShellOpening.solids(join.volume());
         ArrayList<RoadStructures.Part> out = new ArrayList<>();

         for (RoadStructures.Part p : parts) {
            if (p.material() == RoadStructures.Material.TUNNEL) {
               out.addAll(mask.cut(p));
            } else {
               out.add(p);
            }
         }

         out.addAll(join.solid());
         out.addAll(join.pavement());
         return List.copyOf(out);
      }
   }

   private RoadTunnelNose() {
   }

   private static record Edge(
      RoadGeometry.V host, RoadGeometry.V ramp, RoadGeometry.V hostNormal, RoadGeometry.V rampNormal, double gap, double floor, double top, double envelope
   ) {
      RoadTunnelNose.Edge mix(RoadTunnelNose.Edge b, double t) {
         return new RoadTunnelNose.Edge(
            this.host.mul(1.0 - t).add(b.host.mul(t)),
            this.ramp.mul(1.0 - t).add(b.ramp.mul(t)),
            this.hostNormal.mul(1.0 - t).add(b.hostNormal.mul(t)).horizontalUnit(),
            this.rampNormal.mul(1.0 - t).add(b.rampNormal.mul(t)).horizontalUnit(),
            this.gap * (1.0 - t) + b.gap * t,
            this.floor * (1.0 - t) + b.floor * t,
            this.top * (1.0 - t) + b.top * t,
            this.envelope * (1.0 - t) + b.envelope * t
         );
      }
   }

   public static record Join(List<RoadStructures.Part> volume, List<RoadStructures.Part> solid, List<RoadStructures.Part> pavement) {
   }
}
