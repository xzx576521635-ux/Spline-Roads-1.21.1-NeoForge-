package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class YJunctionWalks {
   private static boolean inner(RoadSidewalks.Config c, int side) {
      return c.enabled() && c.smooth() && (c.side() == RoadSidewalks.Side.BOTH || c.side() == (side > 0 ? RoadSidewalks.Side.RIGHT : RoadSidewalks.Side.LEFT));
   }

   public static YJunctionWalks.Pocket plan(RoadGeometry.Mesh stem, RoadGeometry.Mesh out, RoadGeometry.Mesh in) {
      int side = -RoadProfile.trafficSign(stem.settings().options().leftTraffic());
      RoadSidewalks.Config a = out.settings().options().sidewalk();
      RoadSidewalks.Config b = in.settings().options().sidewalk();
      if (inner(a, side) && inner(b, side) && RoadProfile.catalog(out.settings().style()).type() == RoadProfile.Type.ORDINARY) {
         double max = Math.min(out.length(), in.length()) * 0.7;

         double cut;
         for (cut = Math.min(24.0, max); cut < max; cut += 0.5) {
            RoadGeometry.V p = edge(out, cut, side);
            RoadGeometry.V q = edge(in, in.length() - cut, side);
            if (p.sub(q).horizontalLength() >= (double)Math.max(8, a.width() + b.width() + 1)) {
               break;
            }
         }

         if (cut >= max) {
            return null;
         } else {
            ArrayList<RoadStructures.Part> fill = new ArrayList<>();
            int count = (int)Math.ceil(cut / 0.5);

            for (int i = 1; i <= count; i++) {
               double d0 = cut * (double)(i - 1) / (double)count;
               double d1 = cut * (double)i / (double)count;
               RoadGeometry.V p = edge(out, d0, side);
               RoadGeometry.V q = edge(in, in.length() - d0, side);
               RoadGeometry.V r = edge(out, d1, side);
               RoadGeometry.V s = edge(in, in.length() - d1, side);
               RoadGeometry.V c0 = p.add(q).mul(0.5);
               RoadGeometry.V c1 = r.add(s).mul(0.5);
               slab(fill, p, c0, r, c1, a);
               slab(fill, c0, q, c1, s, b);
            }

            ArrayList<RoadStructures.Part> tactile = new ArrayList<>();
            if (a.tactile() && b.tactile() && a.width() >= 2 && b.width() >= 2) {
               RoadGeometry.Sample sa = RoadStructures.sample(out, cut);
               RoadGeometry.Sample sb = RoadStructures.sample(in, in.length() - cut);
               RoadGeometry.V p = sa.at((double)side * (sa.halfWidth() + RoadStreetscape.tactileOffset((double)a.width())), 0.0);
               RoadGeometry.V q = sb.at((double)side * (sb.halfWidth() + RoadStreetscape.tactileOffset((double)b.width())), 0.0);
               RoadGeometry.V toward = sa.left().left();
               RoadGeometry.V away = sb.left().left();
               double handle = Math.min(cut * 0.65, p.sub(q).horizontalLength() * 0.65);
               RoadGeometry.V c = p.add(toward.mul(handle));
               RoadGeometry.V d = q.sub(away.mul(handle));
               ArrayList<RoadGeometry.V> route = new ArrayList<>();
               int n = Math.max(32, (int)Math.ceil(p.distance(q) * 4.0));

               for (int i = 0; i <= n; i++) {
                  double t = (double)i / (double)n;
                  double u = 1.0 - t;
                  route.add(p.mul(u * u * u).add(c.mul(3.0 * u * u * t)).add(d.mul(3.0 * u * t * t)).add(q.mul(t * t * t)));
               }

               for (int i = 1; i < route.size(); i++) {
                  RoadGeometry.V pa = route.get(i - 1);
                  RoadGeometry.V pb = route.get(i);
                  RoadGeometry.V na = (i == 1 ? toward : route.get(i).sub(route.get(i - 2)).horizontalUnit()).left();
                  RoadGeometry.V nb = (i == route.size() - 1 ? away : route.get(i + 1).sub(route.get(i - 1)).horizontalUnit()).left();
                  tactile.add(
                     new RoadStructures.Part(
                           pa.add(new RoadGeometry.V(0.0, 0.2, 0.0)),
                           pb.add(new RoadGeometry.V(0.0, 0.2, 0.0)),
                           0.6,
                           0.015,
                           false,
                           RoadStructures.Material.TACTILE
                        )
                        .frames(na.mul(0.3), nb.mul(0.3))
                  );

                  for (double ridge : new double[]{-0.18, 0.0, 0.18}) {
                     tactile.add(
                        new RoadStructures.Part(
                              pa.add(na.mul(ridge)).add(new RoadGeometry.V(0.0, 0.215, 0.0)),
                              pb.add(nb.mul(ridge)).add(new RoadGeometry.V(0.0, 0.215, 0.0)),
                              0.06,
                              0.02,
                              false,
                              RoadStructures.Material.TACTILE
                           )
                           .frames(na.mul(0.03), nb.mul(0.03))
                     );
                  }
               }
            }

            SidewalkJoins clip = new SidewalkJoins(List.of(stem, out, in), List.of());
            return new YJunctionWalks.Pocket(cut, cut, clip.clip(fill), new SidewalkJoins(List.of(stem, out, in), List.of()).clip(tactile));
         }
      } else {
         return null;
      }
   }

   private static RoadGeometry.V edge(RoadGeometry.Mesh m, double d, int side) {
      RoadGeometry.Sample s = RoadStructures.sample(m, d);
      return s.at((double)side * s.halfWidth(), 0.0);
   }

   private static void slab(
      List<RoadStructures.Part> result, RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c, RoadGeometry.V d, RoadSidewalks.Config config
   ) {
      RoadGeometry.V first = a.add(b).mul(0.5).add(new RoadGeometry.V(0.0, -0.3, 0.0));
      RoadGeometry.V last = c.add(d).mul(0.5).add(new RoadGeometry.V(0.0, -0.3, 0.0));
      if (!(first.sub(last).horizontalLength() < 1.0E-7)) {
         double width = Math.max(0.001, Math.min(8.0, Math.max(a.distance(b), c.distance(d))));
         result.add(
            new RoadStructures.Part(first, last, width, 0.5, false, RoadSidewalks.Finish.of(config.material()).material())
               .frames(a.sub(b).mul(0.5), c.sub(d).mul(0.5))
         );
      }
   }

   public static List<RoadStructures.Part> apply(
      RoadGeometry.Mesh own, RoadGeometry.Mesh stem, RoadGeometry.Mesh out, RoadGeometry.Mesh in, List<RoadStructures.Part> original
   ) {
      YJunctionWalks.Pocket pocket = plan(stem, out, in);
      if (pocket != null && own != stem) {
         boolean outbound = own == out;
         int side = -RoadProfile.trafficSign(stem.settings().options().leftTraffic());
         ArrayList<RoadStructures.Part> result = new ArrayList<>();

         for (RoadStructures.Part p : original) {
            if (p.material() != RoadStructures.Material.TACTILE) {
               result.add(p);
            } else {
               RoadGeometry.V mid = p.a().add(p.b()).mul(0.5);
               RoadQueries.Projection query = RoadQueries.horizontal(own, mid);
               if (query.lateral() * (double)side <= 0.0) {
                  result.add(p);
               } else {
                  double a = distance(own, p.a(), outbound);
                  double b = distance(own, p.b(), outbound);
                  double cut = outbound ? pocket.outCut : pocket.inCut;
                  if (!(Math.max(a, b) <= cut + 1.0E-7)) {
                     if (Math.min(a, b) >= cut - 1.0E-7) {
                        result.add(p);
                     } else {
                        double t = (cut - a) / (b - a);
                        RoadGeometry.V at = p.a().add(p.b().sub(p.a()).mul(t));
                        RoadGeometry.V fa = p.frameA();
                        RoadGeometry.V fb = p.frameB();
                        RoadGeometry.V frame = fa != null && fb != null ? fa.mul(1.0 - t).add(fb.mul(t)) : null;
                        result.add(
                           a < cut
                              ? new RoadStructures.Part(at, p.b(), p.width(), p.height(), false, p.material(), frame, fb)
                              : new RoadStructures.Part(p.a(), at, p.width(), p.height(), false, p.material(), fa, frame)
                        );
                     }
                  }
               }
            }
         }

         if (outbound) {
            result.addAll(pocket.fill);
            result.addAll(pocket.tactile);
         }

         return result;
      } else {
         return original;
      }
   }

   private static double distance(RoadGeometry.Mesh m, RoadGeometry.V p, boolean outbound) {
      double d = RoadQueries.horizontal(m, p).sample().distance();
      return outbound ? d : m.length() - d;
   }

   private YJunctionWalks() {
   }

   public static record Pocket(double outCut, double inCut, List<RoadStructures.Part> fill, List<RoadStructures.Part> tactile) {
   }
}
