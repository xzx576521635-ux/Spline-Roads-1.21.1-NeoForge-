package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class JunctionRetainingWalls {
   public static final String MODEL = "sr:junction_retaining_wall";

   public static List<RoadStructures.Part> plan(
      JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<RoadGeometry.V> boundary, RoadStructures.Ground ground
   ) {
      if (spec.kind() == JunctionSpec.Kind.INTERSECTION
         && boundary.size() >= 3
         && !spec.arms()
            .stream()
            .noneMatch(a -> a.external().structure() == RoadGeometry.Structure.AUTO || a.external().structure() == RoadGeometry.Structure.TUNNEL)) {
         ArrayList<List<RoadStructures.Part>> shells = new ArrayList<>();

         for (int i = 0; i < approaches.size(); i++) {
            RoadGeometry.Mesh raw = approaches.get(i);
            RoadGeometry.Settings settings = spec.arms().get(i).external();
            RoadGeometry.Mesh proxy = new RoadGeometry.Mesh(raw.samples(), settings, raw.min(), raw.max(), raw.length(), raw.closed());
            shells.add(
               settings.structure() == RoadGeometry.Structure.TUNNEL
                  ? RoadInfrastructure.tunnelShell(proxy)
                  : RoadAutoTunnels.enclose(proxy, ground, List.of())
            );
         }

         int n = boundary.size();
         int start = -1;
         boolean[] open = new boolean[n];
         double sign = JunctionPaint.area(boundary) >= 0.0 ? -1.0 : 1.0;

         for (int i = 0; i < n; i++) {
            RoadGeometry.V a = boundary.get(i);
            RoadGeometry.V b = boundary.get((i + 1) % n);
            RoadGeometry.V mid = a.add(b).mul(0.5);
            open[i] = a.distance(b) > 1.0E-8 && approaches.stream().noneMatch(m -> RoadQueries.contains(m, mid, 0.02, 0.1));
            if (!open[i]) {
               start = i;
            }
         }

         if (start < 0) {
            return List.of();
         } else {
            ArrayList<RoadStructures.Part> out = new ArrayList<>();
            ArrayList<RoadGeometry.V> run = new ArrayList<>();

            for (int k = 1; k <= n; k++) {
               int ix = (start + k) % n;
               if (open[ix]) {
                  if (run.isEmpty()) {
                     run.add(boundary.get(ix));
                  }

                  run.add(boundary.get((ix + 1) % n));
               } else if (!run.isEmpty()) {
                  corner(out, spec, approaches, shells, run, sign, ground);
                  run.clear();
               }
            }

            if (!run.isEmpty()) {
               corner(out, spec, approaches, shells, run, sign, ground);
            }

            return List.copyOf(out);
         }
      } else {
         return List.of();
      }
   }

   private static JunctionRetainingWalls.End end(
      JunctionSpec spec, List<RoadGeometry.Mesh> approaches, List<List<RoadStructures.Part>> shells, RoadGeometry.V point
   ) {
      int arm = 0;
      int side = 1;
      double best = Double.POSITIVE_INFINITY;

      for (int i = 0; i < approaches.size(); i++) {
         for (int s : new int[]{-1, 1}) {
            RoadGeometry.Sample at = approaches.get(i).last();
            double d = at.at((double)s * at.halfWidth(), 0.0).distance(point);
            if (d < best) {
               best = d;
               arm = i;
               side = s;
            }
         }
      }

      RoadGeometry.Mesh mesh = approaches.get(arm);
      RoadGeometry.Sample mouth = mesh.last();
      double cap = point.y();

      for (RoadStructures.Part p : shells.get(arm)) {
         if (p.material() == RoadStructures.Material.TUNNEL) {
            RoadQueries.Projection a = RoadQueries.horizontal(mesh, p.a());
            RoadQueries.Projection b = RoadQueries.horizontal(mesh, p.b());
            if (!(Math.max(a.sample().distance(), b.sample().distance()) < mesh.length() - 0.05)
               && !((a.lateral() + b.lateral()) * 0.5 * (double)side < mouth.halfWidth() - 0.2)) {
               for (RoadGeometry.V v : p.top()) {
                  if (RoadQueries.horizontal(mesh, v).sample().distance() > mesh.length() - 0.05) {
                     cap = Math.max(cap, v.y());
                  }
               }
            }
         }
      }

      RoadSidewalks.Config walk = spec.arms().get(arm).external().options().sidewalk();
      double width = !walk.enabled()
            || walk.side() != RoadSidewalks.Side.BOTH
               && (walk.side() != RoadSidewalks.Side.LEFT || side >= 0)
               && (walk.side() != RoadSidewalks.Side.RIGHT || side <= 0)
         ? 0.0
         : (double)walk.width();
      return new JunctionRetainingWalls.End(arm, side, mouth.left().mul((double)side), width, cap);
   }

   private static void corner(
      List<RoadStructures.Part> out,
      JunctionSpec spec,
      List<RoadGeometry.Mesh> approaches,
      List<List<RoadStructures.Part>> shells,
      List<RoadGeometry.V> raw,
      double sign,
      RoadStructures.Ground ground
   ) {
      List<RoadGeometry.V> edge = RoadSidewalks.closeCorner(spec, approaches, raw);
      if (edge.size() >= 2) {
         JunctionRetainingWalls.End first = end(spec, approaches, shells, edge.get(0));
         JunctionRetainingWalls.End last = end(spec, approaches, shells, edge.get(edge.size() - 1));
         RoadGeometry.Structure modeA = spec.arms().get(first.arm()).external().structure();
         RoadGeometry.Structure modeB = spec.arms().get(last.arm()).external().structure();
         if (modeA == RoadGeometry.Structure.AUTO
            || modeA == RoadGeometry.Structure.TUNNEL
            || modeB == RoadGeometry.Structure.AUTO
            || modeB == RoadGeometry.Structure.TUNNEL) {
            int n = edge.size();
            double[] d = new double[n];
            double[] tops = new double[n];
            double[] walks = new double[n];
            ArrayList<RoadGeometry.V> normals = new ArrayList<>();

            for (int i = 1; i < n; i++) {
               d[i] = d[i - 1] + edge.get(i).distance(edge.get(i - 1));
            }

            for (int i = 0; i < n; i++) {
               RoadGeometry.V p = edge.get(i);
               RoadGeometry.V normal;
               if (i == 0) {
                  normal = first.normal();
               } else if (i == n - 1) {
                  normal = last.normal();
               } else {
                  RoadGeometry.V a = p.sub(edge.get(i - 1)).horizontalUnit().left().mul(sign);
                  RoadGeometry.V b = edge.get(i + 1).sub(p).horizontalUnit().left().mul(sign);
                  normal = a.add(b).mul(1.0 / Math.max(0.5, 1.0 + a.dot(b)));
               }

               normals.add(normal);
               double t = RoadGeometry.Settings.smooth(d[i] / d[n - 1]);
               walks[i] = first.walk() + (last.walk() - first.walk()) * t;
               double terrain = Double.NEGATIVE_INFINITY;

               for (double offset : new double[]{0.0, 0.75, 1.55}) {
                  RoadGeometry.V q = p.add(normal.mul(walks[i] + offset));
                  double y = ground.surface(q.x(), q.z(), p.y());
                  if (Double.isFinite(y)) {
                     terrain = Math.max(terrain, y);
                  }
               }

               tops[i] = terrain >= p.y() + 2.0 - 1.0E-7 ? Math.min(p.y() + 6.0, terrain + 0.5) : p.y();
            }

            tops[0] = first.cap();
            tops[n - 1] = last.cap();

            for (int i = 1; i < n; i++) {
               RoadGeometry.V a = edge.get(i - 1);
               RoadGeometry.V b = edge.get(i);
               double ha = tops[i - 1] - a.y();
               double hb = tops[i] - b.y();
               if (!(Math.max(ha, hb) < 0.1)) {
                  ha = Math.max(0.15, ha) + spec.thickness();
                  hb = Math.max(0.15, hb) + spec.thickness();
                  RoadGeometry.V na = normals.get(i - 1);
                  RoadGeometry.V nb = normals.get(i);
                  RoadGeometry.V down = new RoadGeometry.V(0.0, -spec.thickness(), 0.0);
                  out.add(
                     new RoadStructures.Part(
                        a.add(na.mul(walks[i - 1] + 0.8)).add(down),
                        b.add(nb.mul(walks[i] + 0.8)).add(down),
                        1.6,
                        Math.max(ha, hb),
                        false,
                        RoadStructures.Material.TUNNEL,
                        na.mul(0.8),
                        nb.mul(0.8),
                        "sr:junction_retaining_wall",
                        new RoadStructures.Heights(ha, ha, hb, hb)
                     )
                  );
               }
            }
         }
      }
   }

   private JunctionRetainingWalls() {
   }

   private static record End(int arm, int side, RoadGeometry.V normal, double walk, double cap) {
   }
}
