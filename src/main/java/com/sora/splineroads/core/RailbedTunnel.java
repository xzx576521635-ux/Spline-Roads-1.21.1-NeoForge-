package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class RailbedTunnel {
   public static final double BOX_ROOF_THICKNESS = 0.85;
   public static final String PORTAL = "sr:railbed_portal";

   private static double radius(RoadInfrastructure.Config c, double half) {
      double x = half + 1.55;
      return Math.max(c.headroom() / 2.0 + 1.0, (c.headroom() / 2.0 + 1.0) / Math.sqrt(1.0 - half * half / (x * x)));
   }

   public static double top(RoadInfrastructure.Config c, double half) {
      return c.tunnel() == RoadInfrastructure.Tunnel.ROUND ? c.headroom() / 2.0 + radius(c, half) : c.headroom();
   }

   public static double ceiling(RoadInfrastructure.Config c, double half, double lateral) {
      double x = half + 1.55;
      double at = Math.max(-x, Math.min(x, lateral));
      double r = radius(c, half);
      double center = c.headroom() / 2.0;

      for (int i = 0; i < 16; i++) {
         double a = (-Math.PI / 2) + Math.PI * (double)i / 16.0;
         double b = (-Math.PI / 2) + Math.PI * (double)(i + 1) / 16.0;
         double xa = x * Math.sin(a);
         double xb = x * Math.sin(b);
         if (at <= xb + 1.0E-8) {
            return center + r * (Math.cos(a) + (Math.cos(b) - Math.cos(a)) * (at - xa) / (xb - xa));
         }
      }

      return center;
   }

   public static List<RoadStructures.Part> shell(RoadGeometry.Mesh mesh) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();

      for (double d = 0.0; d < mesh.length() - 1.0E-7; d += 2.0) {
         out.addAll(section(mesh, RoadStructures.sample(mesh, d), RoadStructures.sample(mesh, Math.min(mesh.length(), d + 2.0)), "sr:railbed_lining"));
      }

      return List.copyOf(out);
   }

   public static List<RoadStructures.Part> section(RoadGeometry.Mesh mesh, RoadGeometry.Sample a, RoadGeometry.Sample b, String tag) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      RoadInfrastructure.Config c = mesh.settings().options().infrastructure();
      RailbedOptions options = mesh.settings().options().railbed().fittings();
      boolean railway = mesh.settings().style() == RoadGeometry.Style.RAILBED;
      if (c.tunnel() != RoadInfrastructure.Tunnel.ROUND) {
         for (int side : new int[]{-1, 1}) {
            if (!railway || options.rail(side)) {
               out.add(
                  new RoadStructures.Part(
                     a.at((double)side * (a.halfWidth() + 0.75), mesh.settings().thickness()),
                     b.at((double)side * (b.halfWidth() + 0.75), mesh.settings().thickness()),
                     1.6,
                     c.headroom() + mesh.settings().thickness(),
                     false,
                     RoadStructures.Material.TUNNEL,
                     a.left().mul(0.8),
                     b.left().mul(0.8),
                     tag
                  )
               );
            }
         }

         int bands = Math.max(1, (int)Math.ceil((Math.max(a.halfWidth(), b.halfWidth()) * 2.0 + 3.1) / 6.0));

         for (int i = 0; i < bands; i++) {
            double u = -1.0 + 2.0 * (double)i / (double)bands;
            double v = -1.0 + 2.0 * (double)(i + 1) / (double)bands;
            out.add(
               new RoadStructures.Part(
                  a.at((a.halfWidth() + 1.55) * (u + v) / 2.0, -c.headroom()),
                  b.at((b.halfWidth() + 1.55) * (u + v) / 2.0, -c.headroom()),
                  1.0,
                  0.85,
                  false,
                  RoadStructures.Material.TUNNEL,
                  a.left().mul((a.halfWidth() + 1.55) * (v - u) / 2.0),
                  b.left().mul((b.halfWidth() + 1.55) * (v - u) / 2.0),
                  tag
               )
            );
         }

         return List.copyOf(out);
      } else {
         double ra = radius(c, a.halfWidth());
         double rb = radius(c, b.halfWidth());
         double center = c.headroom() / 2.0;

         for (int sidex : new int[]{-1, 1}) {
            if (!railway || options.rail(sidex)) {
               out.add(
                  new RoadStructures.Part(
                     a.at((double)sidex * (a.halfWidth() + 0.75), mesh.settings().thickness()),
                     b.at((double)sidex * (b.halfWidth() + 0.75), mesh.settings().thickness()),
                     1.6,
                     center + 0.65 + mesh.settings().thickness(),
                     false,
                     RoadStructures.Material.TUNNEL,
                     a.left().mul(0.8),
                     b.left().mul(0.8),
                     tag
                  )
               );
            }
         }

         double bottomA = Math.PI / 2;
         double bottomB = Math.PI / 2;
         TreeSet<Double> angles = new TreeSet<>();

         for (int i = -8; i <= 8; i++) {
            angles.add((double)i * Math.PI / 16.0);
         }

         angles.add(Math.acos((c.headroom() - center) / ra));
         angles.add(-Math.acos((c.headroom() - center) / ra));
         angles.add(Math.acos((c.headroom() - center) / rb));
         angles.add(-Math.acos((c.headroom() - center) / rb));
         ArrayList<Double> ordered = new ArrayList<>(angles);

         for (int i = 1; i < ordered.size(); i++) {
            double lo = ordered.get(i - 1);
            double hi = ordered.get(i);
            int sidexx = lo + hi > 0.0 ? 1 : -1;
            double aa = Math.max(-bottomA, Math.min(bottomA, lo));
            double ab = Math.max(-bottomA, Math.min(bottomA, hi));
            double ba = Math.max(-bottomB, Math.min(bottomB, lo));
            double bb = Math.max(-bottomB, Math.min(bottomB, hi));
            if (!(ab - aa < 1.0E-8) || !(bb - ba < 1.0E-8)) {
               double ya = center + ra * Math.cos((aa + ab) / 2.0);
               double yb = center + rb * Math.cos((ba + bb) / 2.0);
               if (!railway || options.rail(sidexx) || !(Math.min(ya, yb) < c.headroom() - 1.0E-7)) {
                  RoadGeometry.V pa = a.at((a.halfWidth() + 1.55) * (Math.sin(aa) + Math.sin(ab)) / 2.0, -center - ra * (Math.cos(aa) + Math.cos(ab)) / 2.0);
                  RoadGeometry.V pb = b.at((b.halfWidth() + 1.55) * (Math.sin(ba) + Math.sin(bb)) / 2.0, -center - rb * (Math.cos(ba) + Math.cos(bb)) / 2.0);
                  RoadGeometry.V fa = a.left()
                     .mul((a.halfWidth() + 1.55) * (Math.sin(ab) - Math.sin(aa)) / 2.0)
                     .add(new RoadGeometry.V(0.0, ra * (Math.cos(ab) - Math.cos(aa)) / 2.0, 0.0));
                  RoadGeometry.V fb = b.left()
                     .mul((b.halfWidth() + 1.55) * (Math.sin(bb) - Math.sin(ba)) / 2.0)
                     .add(new RoadGeometry.V(0.0, rb * (Math.cos(bb) - Math.cos(ba)) / 2.0, 0.0));
                  out.add(new RoadStructures.Part(pa, pb, 0.1, 0.65, false, RoadStructures.Material.TUNNEL, fa, fb, tag));
               }
            }
         }

         for (int sidexx : new int[]{-1, 1}) {
            if (!railway || options.rail(sidexx)) {
               TreeSet<Double> bands = new TreeSet<>();
               bands.add(0.0);
               bands.add(1.0);

               for (int ix = 0; ix <= 8; ix++) {
                  double f = Math.sin((double)ix * Math.PI / 16.0);

                  for (double half : new double[]{a.halfWidth(), b.halfWidth()}) {
                     double u = ((half + 1.55) * f - (half - 0.05)) / 1.6;
                     if (u > 0.0 && u < 1.0) {
                        bands.add(u);
                     }
                  }
               }

               ArrayList<Double> cuts = new ArrayList<>(bands);

               for (int ix = 1; ix < cuts.size(); ix++) {
                  double u = cuts.get(ix - 1);
                  double v = cuts.get(ix);
                  double ax = a.halfWidth() - 0.05 + 1.6 * u;
                  double ay = a.halfWidth() - 0.05 + 1.6 * v;
                  double bx = b.halfWidth() - 0.05 + 1.6 * u;
                  double by = b.halfWidth() - 0.05 + 1.6 * v;
                  double h0 = Math.max(0.0, ceiling(c, a.halfWidth(), ay) - center);
                  double h1 = Math.max(0.0, ceiling(c, a.halfWidth(), ax) - center);
                  double h2 = Math.max(0.0, ceiling(c, b.halfWidth(), bx) - center);
                  double h3 = Math.max(0.0, ceiling(c, b.halfWidth(), by) - center);
                  double height = Math.max(Math.max(h0, h1), Math.max(h2, h3));
                  if (!(height < 1.0E-8)) {
                     out.add(
                        new RoadStructures.Part(
                           a.at((double)sidexx * (ax + ay) / 2.0, -center),
                           b.at((double)sidexx * (bx + by) / 2.0, -center),
                           ay - ax,
                           height,
                           false,
                           RoadStructures.Material.TUNNEL,
                           a.left().mul((double)sidexx * (ay - ax) / 2.0),
                           b.left().mul((double)sidexx * (by - bx) / 2.0),
                           tag,
                           new RoadStructures.Heights(h0, h1, h2, h3)
                        )
                     );
                  }
               }
            }
         }

         return List.copyOf(out);
      }
   }

   public static List<RoadStructures.Part> portals(RoadGeometry.Mesh mesh, List<RoadAutoTunnels.Region> roofs, RoadStructures.Ground ground) {
      RoadInfrastructure.Config config = mesh.settings().options().infrastructure();
      if (mesh.settings().options().railbed().active() && config.tunnel() == RoadInfrastructure.Tunnel.ROUND) {
         ArrayList<RoadStructures.Part> out = new ArrayList<>();
         RailbedOptions options = mesh.settings().options().railbed().fittings();

         for (RoadAutoTunnels.Region roof : roofs) {
            for (boolean first : new boolean[]{true, false}) {
               double station = first ? roof.from() : roof.to();
               double reach = 0.8;
               boolean buried = false;
               double limit = Math.min(5.0, (roof.to() - roof.from()) / 2.0);

               for (double offset = Math.min(0.4, limit); offset <= limit + 1.0E-7; offset = Math.min(limit, offset + 0.5)) {
                  RoadGeometry.Sample probe = RoadStructures.sample(mesh, station + (double)(first ? 1 : -1) * offset);

                  for (double side : new double[]{-1.0, 0.0, 1.0}) {
                     RoadGeometry.V p = probe.at(side * (probe.halfWidth() + 1.55), 0.0);
                     double surface = ground.surface(p.x(), p.z(), p.y());
                     buried |= Double.isFinite(surface) && surface >= p.y() + config.headroom();
                  }

                  if (buried) {
                     reach = Math.max(0.8, offset + 0.5);
                     break;
                  }

                  if (offset >= limit) {
                     break;
                  }
               }

               if (buried) {
                  RoadGeometry.Sample a = RoadStructures.sample(mesh, Math.max(0.0, station - (first ? 1.02 : reach)));
                  RoadGeometry.Sample b = RoadStructures.sample(mesh, Math.min(mesh.length(), station + (first ? reach : 1.02)));
                  double topA = top(config, a.halfWidth()) + 1.5;
                  double topB = top(config, b.halfWidth()) + 1.5;

                  for (int i = 0; i < 16; i++) {
                     double u = Math.sin((-Math.PI / 2) + Math.PI * (double)i / 16.0);
                     double v = Math.sin((-Math.PI / 2) + Math.PI * (double)(i + 1) / 16.0);
                     int side = u + v < 0.0 ? -1 : 1;
                     double al = (a.halfWidth() + 1.55) * u;
                     double ah = (a.halfWidth() + 1.55) * v;
                     double bl = (b.halfWidth() + 1.55) * u;
                     double bh = (b.halfWidth() + 1.55) * v;
                     double h0 = ceiling(config, a.halfWidth(), ah);
                     double h1 = ceiling(config, a.halfWidth(), al);
                     double h2 = ceiling(config, b.halfWidth(), bl);
                     double h3 = ceiling(config, b.halfWidth(), bh);
                     if (!options.rail(side)) {
                        h0 = Math.max(h0, config.headroom());
                        h1 = Math.max(h1, config.headroom());
                        h2 = Math.max(h2, config.headroom());
                        h3 = Math.max(h3, config.headroom());
                     }

                     RoadStructures.Heights heights = new RoadStructures.Heights(topA - h0, topA - h1, topB - h2, topB - h3);
                     double height = 0.0;

                     for (int j = 0; j < 4; j++) {
                        height = Math.max(height, heights.get(j));
                     }

                     out.add(
                        new RoadStructures.Part(
                           a.at((al + ah) / 2.0, -(h0 + h1) / 2.0),
                           b.at((bl + bh) / 2.0, -(h2 + h3) / 2.0),
                           1.0,
                           height,
                           false,
                           RoadStructures.Material.TUNNEL,
                           a.left().mul((ah - al) / 2.0).add(new RoadGeometry.V(0.0, (h0 - h1) / 2.0, 0.0)),
                           b.left().mul((bh - bl) / 2.0).add(new RoadGeometry.V(0.0, (h3 - h2) / 2.0, 0.0)),
                           "sr:railbed_portal",
                           heights
                        )
                     );
                  }

                  for (int side : new int[]{-1, 1}) {
                     double base = options.rail(side) ? -mesh.settings().thickness() : config.headroom();
                     double height = Math.max(topA, topB) - base;
                     RoadStructures.Heights heights = new RoadStructures.Heights(topA - base, topA - base, topB - base, topB - base);
                     out.add(
                        new RoadStructures.Part(
                           a.at((double)side * (a.halfWidth() + 2.3), -base),
                           b.at((double)side * (b.halfWidth() + 2.3), -base),
                           1.5,
                           height,
                           false,
                           RoadStructures.Material.TUNNEL,
                           a.left().mul(0.75),
                           b.left().mul(0.75),
                           "sr:railbed_portal",
                           heights
                        )
                     );
                  }
               }
            }
         }

         return List.copyOf(out);
      } else {
         return List.of();
      }
   }

   private RailbedTunnel() {
   }
}
