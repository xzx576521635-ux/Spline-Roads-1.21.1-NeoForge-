package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

public final class RoadInfrastructure {
   public static boolean customBridge(RoadGeometry.Mesh mesh) {
      return mesh.settings().structure() == RoadGeometry.Structure.BRIDGE
         && mesh.settings().options().infrastructure().bridge() != RoadInfrastructure.Bridge.STANDARD;
   }

   public static List<RoadStructures.Part> fixedBridgeParts(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      if (customBridge(mesh) && !ground.sharedDeck()) {
         ArrayList<RoadStructures.Part> parts = new ArrayList<>();
         bridge(mesh, mesh.settings().options().infrastructure(), ground, parts, false);
         validateOwnClearance(mesh, parts);
         return checked(parts);
      } else {
         return List.of();
      }
   }

   public static double tunnelRise(RoadInfrastructure.Config c, double halfWidth) {
      return c.tunnel() == RoadInfrastructure.Tunnel.ROUND
         ? RailbedTunnel.top(c, halfWidth) - c.headroom()
         : (c.tunnel() == RoadInfrastructure.Tunnel.ARCH ? Math.min(4.0, halfWidth * 0.38) : 0.0);
   }

   public static double clearance(RoadGeometry.Settings s) {
      RoadInfrastructure.Config c = s.options().infrastructure();
      return s.structure() == RoadGeometry.Structure.TUNNEL ? c.headroom() + tunnelRise(c, s.width() / 2.0) + 1.25 : 0.0;
   }

   public static List<RoadStructures.Part> plan(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      return plan(mesh, ground, List.of());
   }

   public static List<RoadStructures.Part> plan(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, Collection<RoadStructures.Part> occupied) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      RoadInfrastructure.Config c = mesh.settings().options().infrastructure();
      if (mesh.settings().structure() == RoadGeometry.Structure.TUNNEL) {
         tunnel(mesh, c, out);
         out.addAll(RailbedTunnel.portals(mesh, List.of(new RoadAutoTunnels.Region(0.0, mesh.length())), ground));
         RoadStructures.medianFurniture(mesh, ground, out);
         RoadStructures.cycleFurniture(mesh, ground, out);
         return checked(out);
      } else {
         if (customBridge(mesh) && !ground.sharedDeck()) {
            bridge(mesh, c, ground, out);
            validateOwnClearance(mesh, out);

            for (RoadStructures.Part part : out) {
               if (ground.blocked(part)) {
                  throw new IllegalArgumentException("桥梁构件安装空间冲突：" + ground.blockedReason(part) + "；请调整桥面高度或桥位");
               }
            }
         }

         ArrayList<RoadStructures.Part> fixtures = new ArrayList<>(occupied);
         fixtures.addAll(out);
         out.addAll(RoadGantry.plan(mesh, ground, fixtures));
         return checked(out);
      }
   }

   private static List<RoadStructures.Part> checked(List<RoadStructures.Part> parts) {
      if (parts.size() > 48000) {
         throw new IllegalArgumentException("本段结构过多，请用中间端点拆分桥梁或隧道");
      } else {
         return List.copyOf(parts);
      }
   }

   private static RoadGeometry.V at(RoadGeometry.Sample s, double lateral, double up) {
      return s.at(lateral, -up);
   }

   private static RoadGeometry.Sample sample(RoadGeometry.Mesh m, double d) {
      return RoadStructures.sample(m, Math.max(0.0, Math.min(m.length(), d)));
   }

   private static void beam(List<RoadStructures.Part> out, RoadGeometry.V a, RoadGeometry.V b, double width, double height, RoadStructures.Material mat) {
      if (a.sub(b).horizontalLength() < 1.0E-6) {
         post(out, a, width, Math.max(height, b.y() - a.y() + height), mat);
      } else {
         out.add(new RoadStructures.Part(a, b, width, height, false, mat));
      }
   }

   private static void post(List<RoadStructures.Part> out, RoadGeometry.V a, double width, double height, RoadStructures.Material mat) {
      for (double offset = 0.0; offset < height - 0.001; offset += 96.0) {
         RoadGeometry.V base = a.add(new RoadGeometry.V(0.0, offset, 0.0));
         out.add(new RoadStructures.Part(base, base, width, Math.min(96.0, height - offset), true, mat));
      }
   }

   private static void longitudinal(
      List<RoadStructures.Part> out,
      RoadGeometry.Sample a,
      RoadGeometry.Sample b,
      double offsetA,
      double offsetB,
      double y,
      double width,
      double height,
      RoadStructures.Material mat
   ) {
      out.add(
         new RoadStructures.Part(at(a, offsetA, y), at(b, offsetB, y), width, height, false, mat).frames(a.left().mul(width / 2.0), b.left().mul(width / 2.0))
      );
   }

   public static List<RoadStructures.Part> tunnelShell(RoadGeometry.Mesh mesh) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      tunnel(mesh, mesh.settings().options().infrastructure(), out);
      return List.copyOf(out);
   }

   private static void tunnel(RoadGeometry.Mesh mesh, RoadInfrastructure.Config c, List<RoadStructures.Part> out) {
      if (mesh.settings().style() != RoadGeometry.Style.RAILBED && c.tunnel() != RoadInfrastructure.Tunnel.ROUND) {
         for (double d = 0.0; d < mesh.length() - 1.0E-6; d += 2.0) {
            RoadGeometry.Sample a = sample(mesh, d);
            RoadGeometry.Sample b = sample(mesh, d + 2.0);

            for (int side : new int[]{-1, 1}) {
               longitudinal(
                  out,
                  a,
                  b,
                  (double)side * (a.halfWidth() + 0.75),
                  (double)side * (b.halfWidth() + 0.75),
                  -mesh.settings().thickness(),
                  1.6,
                  Math.max(wallTop(c, a.halfWidth()), wallTop(c, b.halfWidth())) + mesh.settings().thickness(),
                  RoadStructures.Material.TUNNEL
               );
               longitudinal(
                  out, a, b, (double)side * (a.halfWidth() - 0.15), (double)side * (b.halfWidth() - 0.15), 0.8, 0.12, 0.18, RoadStructures.Material.SIGN_WHITE
               );
            }

            int bands = roofBands(a.halfWidth(), b.halfWidth());

            for (int i = 0; i < bands; i++) {
               double u = -1.0 + 2.0 * (double)i / (double)bands;
               double v = -1.0 + 2.0 * (double)(i + 1) / (double)bands;
               double wa = a.halfWidth() + 1.55;
               double wb = b.halfWidth() + 1.55;
               double ya = roof(c, a.halfWidth(), u);
               double za = roof(c, a.halfWidth(), v);
               double yb = roof(c, b.halfWidth(), u);
               double zb = roof(c, b.halfWidth(), v);
               out.add(
                  new RoadStructures.Part(
                        at(a, wa * (u + v) / 2.0, (ya + za) / 2.0),
                        at(b, wb * (u + v) / 2.0, (yb + zb) / 2.0),
                        Math.max(wa, wb) * (v - u),
                        0.85,
                        false,
                        RoadStructures.Material.TUNNEL
                     )
                     .frames(
                        a.left().mul(wa * (v - u) / 2.0).add(new RoadGeometry.V(0.0, (za - ya) / 2.0, 0.0)),
                        b.left().mul(wb * (v - u) / 2.0).add(new RoadGeometry.V(0.0, (zb - yb) / 2.0, 0.0))
                     )
               );
            }

            int rows = Math.max(1, (int)Math.ceil(mesh.settings().width() / 4.0));

            for (int row = 0; row < rows; row++) {
               double u = -1.0 + ((double)row + 0.5) * 2.0 / (double)rows;
               double oa = a.halfWidth() * u;
               double ob = b.halfWidth() * u;
               double ya = ceiling(c, a.halfWidth(), oa);
               double yb = ceiling(c, b.halfWidth(), ob);
               double tiltA = (ceiling(c, a.halfWidth(), oa + 0.11) - ceiling(c, a.halfWidth(), oa - 0.11)) / 2.0;
               double tiltB = (ceiling(c, b.halfWidth(), ob + 0.11) - ceiling(c, b.halfWidth(), ob - 0.11)) / 2.0;
               out.add(
                  new RoadStructures.Part(
                     at(a, oa, ya - 0.22),
                     at(b, ob, yb - 0.22),
                     0.22,
                     0.2,
                     false,
                     RoadStructures.Material.LAMP,
                     a.left().mul(0.11).add(new RoadGeometry.V(0.0, tiltA, 0.0)),
                     b.left().mul(0.11).add(new RoadGeometry.V(0.0, tiltB, 0.0)),
                     "sr:tunnel_ceiling_light"
                  )
               );
            }
         }

         for (double d : new double[]{0.6, Math.max(0.6, mesh.length() - 0.6)}) {
            RoadGeometry.Sample s = sample(mesh, d);
            double w = s.halfWidth() + 0.65;

            for (int side : new int[]{-1, 1}) {
               post(
                  out,
                  at(s, (double)side * w, -mesh.settings().thickness()),
                  1.3,
                  wallTop(c, s.halfWidth()) + mesh.settings().thickness(),
                  RoadStructures.Material.TUNNEL
               );
            }

            int bands = 16;

            for (int i = 0; i < bands; i++) {
               double u = -1.0 + 2.0 * (double)i / (double)bands;
               double v = -1.0 + 2.0 * (double)(i + 1) / (double)bands;
               beam(out, at(s, w * u, roof(c, s.halfWidth(), u)), at(s, w * v, roof(c, s.halfWidth(), v)), 1.3, 1.1, RoadStructures.Material.TUNNEL);
            }
         }

         for (double d = 3.0; d < mesh.length() - 1.0; d += 8.0) {
            RoadGeometry.Sample a = sample(mesh, d);
            RoadGeometry.Sample b = sample(mesh, d + 1.2);

            for (int side : new int[]{-1, 1}) {
               longitudinal(
                  out,
                  a,
                  b,
                  (double)side * (a.halfWidth() - 0.2),
                  (double)side * (b.halfWidth() - 0.2),
                  c.headroom() - 0.5,
                  0.3,
                  0.25,
                  RoadStructures.Material.LAMP
               );
            }
         }
      } else {
         out.addAll(RailbedTunnel.shell(mesh));
      }
   }

   private static double wallTop(RoadInfrastructure.Config c, double half) {
      return ceiling(c, half, Math.max(0.0, half - 0.05)) + 0.03;
   }

   public static double excavationTop(RoadGeometry.Mesh mesh, int x, int z, double deckTop) {
      double top = RoadTunnelSpace.ceiling(mesh, x, z);
      return Double.isFinite(top) ? top : deckTop;
   }

   static int roofBands(double halfA, double halfB) {
      return Math.max(16, (int)Math.ceil(Math.max(halfA, halfB) / 3.0));
   }

   static double roofHeight(RoadInfrastructure.Config c, double half, double u) {
      return roof(c, half, u);
   }

   public static double ceiling(RoadInfrastructure.Config c, double half, double lateral) {
      if (c.tunnel() == RoadInfrastructure.Tunnel.ROUND) {
         return RailbedTunnel.ceiling(c, half, lateral);
      } else {
         int bands = Math.max(16, (int)Math.ceil(half / 3.0));
         double t = Math.max(0.0, Math.min((double)bands - 1.0E-9, (lateral / (half + 1.55) + 1.0) * (double)bands / 2.0));
         int i = (int)t;
         double u = -1.0 + 2.0 * (double)i / (double)bands;
         double v = -1.0 + 2.0 * (double)(i + 1) / (double)bands;
         return roof(c, half, u) * (1.0 - (t - (double)i)) + roof(c, half, v) * (t - (double)i);
      }
   }

   private static double roof(RoadInfrastructure.Config c, double half, double u) {
      return c.tunnel() == RoadInfrastructure.Tunnel.ROUND
         ? RailbedTunnel.ceiling(c, half, u * (half + 1.55))
         : c.headroom() + tunnelRise(c, half) * Math.sqrt(Math.max(0.0, 1.0 - u * u));
   }

   public static double endTaper(RoadGeometry.Mesh mesh, RoadGeometry.Sample s) {
      double t = Math.max(0.0, Math.min(1.0, Math.min(s.distance(), mesh.length() - s.distance()) / Math.min(12.0, mesh.length() * 0.12)));
      return t * t * (3.0 - 2.0 * t);
   }

   public static double girderDepth(RoadGeometry.Mesh mesh) {
      if (!customBridge(mesh)) {
         return 0.0;
      } else {
         RoadInfrastructure.Bridge bridge = mesh.settings().options().infrastructure().bridge();
         return bridge == RoadInfrastructure.Bridge.BEAM
            ? 0.7
            : (
               bridge == RoadInfrastructure.Bridge.OVERPASS
                  ? 0.9
                  : (
                     bridge != RoadInfrastructure.Bridge.CABLE && bridge != RoadInfrastructure.Bridge.SUSPENSION
                        ? 1.2
                        : Math.max(1.2, towerWidth(towerHeight(cableSpan(mesh), bridge)) * 0.45)
                  )
            );
      }
   }

   public static double edgeReach(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample) {
      if (!customBridge(mesh)) {
         return 0.0;
      } else {
         RoadInfrastructure.Bridge bridge = mesh.settings().options().infrastructure().bridge();
         return (
               bridge != RoadInfrastructure.Bridge.CABLE && bridge != RoadInfrastructure.Bridge.SUSPENSION
                  ? (bridge == RoadInfrastructure.Bridge.ARCH ? 1.25 : 0.35)
                  : Math.max(2.1, 2.0 * towerRadius(sample, towerWidth(towerHeight(cableSpan(mesh), bridge))) + 0.95)
            )
            * endTaper(mesh, sample);
      }
   }

   private static void bridge(RoadGeometry.Mesh mesh, RoadInfrastructure.Config c, RoadStructures.Ground ground, List<RoadStructures.Part> out) {
      bridge(mesh, c, ground, out, true);
   }

   private static void bridge(
      RoadGeometry.Mesh mesh, RoadInfrastructure.Config c, RoadStructures.Ground ground, List<RoadStructures.Part> out, boolean includeSupports
   ) {
      double depth = girderDepth(mesh);
      TreeSet<Double> stations = new TreeSet<>();

      for (double d = 0.0; d < mesh.length(); d += 2.0) {
         stations.add(d);
      }

      stations.add(mesh.length());

      for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
         for (double d : new double[]{cut.begin(), cut.end()}) {
            for (double e : new double[]{-1.0E-5, 0.0, 1.0E-5}) {
               if (d + e > 0.0 && d + e < mesh.length()) {
                  stations.add(d + e);
               }
            }
         }
      }

      ArrayList<Double> distances = new ArrayList<>(stations);

      for (int index = 1; index < distances.size(); index++) {
         RoadGeometry.Sample a = sample(mesh, distances.get(index - 1));
         RoadGeometry.Sample b = sample(mesh, distances.get(index));
         double middle = (a.distance() + b.distance()) / 2.0;
         RoadGeometry.Mesh bearing = mesh;

         for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
            if (cut.temporary() && cut.removed(middle) > 0.999) {
               bearing = LaneDeck.excludingSlot(bearing, cut.lane());
            }
         }

         for (int side : new int[]{-1, 1}) {
            girder(
               out,
               bearing,
               a,
               b,
               (double)side * a.halfWidth() * 0.62,
               (double)side * b.halfWidth() * 0.62,
               -mesh.settings().thickness() - depth,
               Math.min(2.4, mesh.settings().width() / 5.0),
               depth
            );
            if (c.bridge() != RoadInfrastructure.Bridge.BEAM && c.bridge() != RoadInfrastructure.Bridge.OVERPASS && !LaneDeck.outerOpening(mesh, middle, side)) {
               double ra = edgeReach(mesh, a);
               double rb = edgeReach(mesh, b);
               int strips = Math.max(1, (int)Math.ceil((Math.max(ra, rb) + 0.05) / 7.5));

               for (int strip = 0; strip < strips; strip++) {
                  double wa = (ra + 0.05) / (double)strips;
                  double wb = (rb + 0.05) / (double)strips;
                  out.add(
                     new RoadStructures.Part(
                           at(a, (double)side * (a.halfWidth() - 0.05 + wa * ((double)strip + 0.5)), -mesh.settings().thickness()),
                           at(b, (double)side * (b.halfWidth() - 0.05 + wb * ((double)strip + 0.5)), -mesh.settings().thickness()),
                           Math.max(wa, wb),
                           mesh.settings().thickness(),
                           false,
                           RoadStructures.Material.CONCRETE
                        )
                        .frames(a.left().mul(wa / 2.0), b.left().mul(wb / 2.0))
                  );
               }
            }
         }
      }

      if (c.bridge() == RoadInfrastructure.Bridge.BEAM || c.bridge() == RoadInfrastructure.Bridge.OVERPASS) {
         out.addAll(RoadStructures.edgeSlabs(mesh, ground));
      }

      int spans = !c.autoSpan() || c.bridge() != RoadInfrastructure.Bridge.CABLE && c.bridge() != RoadInfrastructure.Bridge.SUSPENSION
         ? Math.max(1, (int)Math.ceil(mesh.length() / c.span() - 0.001))
         : 1;
      double span = mesh.length() / (double)spans;
      if (includeSupports) {
         for (int i = 0; i <= spans; i++) {
            support(mesh, Math.min(mesh.length() - 1.0, Math.max(1.0, (double)i * span)), depth, c.bridge(), ground, out);
         }
      }

      for (int i = 0; i < spans; i++) {
         double start = (double)i * span;
         if (c.bridge() == RoadInfrastructure.Bridge.ARCH) {
            arch(mesh, start, span, out);
         }

         if (c.bridge() == RoadInfrastructure.Bridge.CABLE || c.bridge() == RoadInfrastructure.Bridge.SUSPENSION) {
            cables(mesh, start, span, depth, c.bridge(), ground, out, includeSupports);
         }
      }
   }

   private static void girder(
      List<RoadStructures.Part> out,
      RoadGeometry.Mesh bearing,
      RoadGeometry.Sample a,
      RoadGeometry.Sample b,
      double offsetA,
      double offsetB,
      double y,
      double width,
      double height
   ) {
      for (LaneDeck.Strip strip : LaneDeck.strips(bearing, a, b)) {
         double loA = Math.max(offsetA - width / 2.0, strip.ar().sub(a.center()).dot(a.left()));
         double hiA = Math.min(offsetA + width / 2.0, strip.al().sub(a.center()).dot(a.left()));
         double loB = Math.max(offsetB - width / 2.0, strip.br().sub(b.center()).dot(b.left()));
         double hiB = Math.min(offsetB + width / 2.0, strip.bl().sub(b.center()).dot(b.left()));
         if (!(hiA - loA < 1.0E-6) && !(hiB - loB < 1.0E-6)) {
            out.add(
               new RoadStructures.Part(
                     at(a, (loA + hiA) / 2.0, y), at(b, (loB + hiB) / 2.0, y), Math.max(hiA - loA, hiB - loB), height, false, RoadStructures.Material.CONCRETE
                  )
                  .frames(a.left().mul((hiA - loA) / 2.0), b.left().mul((hiB - loB) / 2.0))
            );
         }
      }
   }

   private static void support(
      RoadGeometry.Mesh mesh, double station, double depth, RoadInfrastructure.Bridge style, RoadStructures.Ground ground, List<RoadStructures.Part> out
   ) {
      boolean portal = style == RoadInfrastructure.Bridge.OVERPASS;
      double shaftWidth = portal
         ? 1.8
         : (
            style != RoadInfrastructure.Bridge.CABLE && style != RoadInfrastructure.Bridge.SUSPENSION
               ? 2.2
               : Math.max(2.2, towerWidth(towerHeight(cableSpan(mesh), style)))
         );
      RoadGeometry.Sample at = sample(mesh, station);
      List<RoadStructures.Part> shared = ground.sharedSupports(mesh, at, depth, shaftWidth, portal ? at.halfWidth() + 1.4 : at.halfWidth() * 0.62);
      if (shared != null) {
         out.addAll(shared);
      } else {
         for (double shift : new double[]{0.0, -4.0, 4.0, -8.0, 8.0, -12.0, 12.0}) {
            if (!(station + shift < 1.0) && !(station + shift > mesh.length() - 1.0)) {
               RoadGeometry.Sample s = sample(mesh, station + shift);
               ArrayList<RoadStructures.Part> parts = new ArrayList<>();
               List<RoadGeometry.Sample> bands = RoadSupports.bearing(mesh, station + shift, 1.5);
               boolean cut = bands.size() != 1
                  || bands.get(0).center().distance(s.center()) > 1.0E-6
                  || Math.abs(bands.get(0).halfWidth() - s.halfWidth()) > 1.0E-6;
               if (LaneDeck.hasOpenings(mesh) && cut) {
                  for (RoadGeometry.Sample band : bands) {
                     List<RoadStructures.Part> strip = RoadSupports.standard(band, mesh.settings().thickness() + Math.max(0.0, depth - 0.8), ground);
                     if (strip.stream().noneMatch(ground::blocked)) {
                        parts.addAll(strip);
                     }
                  }

                  if (!parts.isEmpty()) {
                     out.addAll(parts);
                     return;
                  }
               } else {
                  double offset = portal ? s.halfWidth() + 1.4 : s.halfWidth() * 0.62;
                  double top = s.center().y() - mesh.settings().thickness() - depth;
                  boolean valid = true;

                  for (int side : new int[]{-1, 1}) {
                     RoadGeometry.V foot = at(s, (double)side * offset, 0.0);
                     if (!RoadSupports.shaft(parts, foot, top, shaftWidth, ground)) {
                        valid = false;
                        break;
                     }
                  }

                  if (valid && !parts.stream().anyMatch(ground::blocked)) {
                     if (!parts.isEmpty()) {
                        beam(
                           parts,
                           at(s, -offset - (shaftWidth + 0.75) / 2.0, -mesh.settings().thickness() - depth),
                           at(s, offset + (shaftWidth + 0.75) / 2.0, -mesh.settings().thickness() - depth),
                           Math.max(2.4, shaftWidth + 0.2),
                           depth,
                           RoadStructures.Material.CONCRETE
                        );
                     }

                     if (!parts.stream().anyMatch(ground::blocked)) {
                        out.addAll(parts);
                        return;
                     }
                  }
               }
            }
         }

         if (!mesh.settings().style().ramp()) {
            RoadGeometry.Sample portalSample = sample(mesh, station);
            List<RoadGeometry.Sample> bearing = RoadSupports.bearing(mesh, station, 1.5);
            boolean gap = LaneDeck.hasOpenings(mesh)
               && (
                  bearing.size() != 1
                     || bearing.get(0).center().distance(portalSample.center()) > 1.0E-6
                     || Math.abs(bearing.get(0).halfWidth() - portalSample.halfWidth()) > 1.0E-6
               );
            List<RoadStructures.Part> relocated = gap
               ? List.of()
               : RoadSupports.portal(
                  portalSample,
                  mesh.settings().thickness(),
                  depth,
                  portal ? portalSample.halfWidth() + 1.4 : portalSample.halfWidth() * 0.62,
                  shaftWidth,
                  ground
               );
            if (!relocated.isEmpty()) {
               out.addAll(relocated);
            } else {
               RoadGeometry.Sample s = sample(mesh, station);
               double floor = ground.top(s.center().x(), s.center().z(), s.center().y());
               if (!Double.isFinite(floor) || s.center().y() - floor > mesh.settings().thickness() + depth + 1.0) {
                  throw new IllegalArgumentException("桥墩无可用基础或与下方道路冲突；请调整桥跨、桥位或桥面高度");
               }
            }
         }
      }
   }

   private static void arch(RoadGeometry.Mesh mesh, double start, double length, List<RoadStructures.Part> out) {
      double trim = Math.min(6.0, length * 0.12);
      double trimA = start < 1.0E-6 ? trim : 0.0;
      double trimB = start + length > mesh.length() - 1.0E-6 ? trim : 0.0;
      start += trimA;
      length -= trimA + trimB;
      double rise = Math.max(7.0, Math.min(28.0, length * 0.22));
      int steps = Math.max(12, (int)Math.ceil(length / 3.0));

      for (int side : new int[]{-1, 1}) {
         RoadGeometry.V previous = null;

         for (int i = 0; i <= steps; i++) {
            double t = (double)i / (double)steps;
            RoadGeometry.Sample s = sample(mesh, start + length * t);
            double y = 1.0 + rise * 4.0 * t * (1.0 - t);
            RoadGeometry.V p = at(s, (double)side * (s.halfWidth() + 0.8 * endTaper(mesh, s)), y);
            if (previous != null) {
               beam(out, previous, p, 0.8, 0.8, RoadStructures.Material.ARCH_STEEL);
            }

            if (i == 0 || i == steps) {
               post(
                  out,
                  at(s, (double)side * (s.halfWidth() + 0.8 * endTaper(mesh, s)), -mesh.settings().thickness()),
                  0.8,
                  y + mesh.settings().thickness(),
                  RoadStructures.Material.CONCRETE
               );
            }

            if (i > 0 && i < steps && i % 2 == 0) {
               post(out, at(s, (double)side * (s.halfWidth() + 0.8 * endTaper(mesh, s)), -0.4), 0.18, y + 0.4, RoadStructures.Material.STEEL);
            }

            previous = p;
         }
      }

      for (double tx : new double[]{0.3, 0.5, 0.7}) {
         RoadGeometry.Sample sx = sample(mesh, start + length * tx);
         double yx = 1.0 + rise * 4.0 * tx * (1.0 - tx);
         beam(out, at(sx, -sx.halfWidth() - 0.8, yx), at(sx, sx.halfWidth() + 0.8, yx), 0.45, 0.5, RoadStructures.Material.ARCH_STEEL);
      }
   }

   private static double cableSpan(RoadGeometry.Mesh mesh) {
      RoadInfrastructure.Config c = mesh.settings().options().infrastructure();
      return mesh.length() / (double)(c.autoSpan() ? 1 : Math.max(1, (int)Math.ceil(mesh.length() / c.span() - 0.001)));
   }

   public static double towerWidth(double height) {
      return Math.min(7.0, 1.5 + Math.max(0.0, height - 24.0) * 0.035);
   }

   public static double towerHeight(double span, RoadInfrastructure.Bridge style) {
      return Math.max(12.0, span * (style == RoadInfrastructure.Bridge.CABLE ? 0.22 : 0.18));
   }

   private static double towerRadius(RoadGeometry.Sample s, double width) {
      return width * 0.5 * (Math.abs(s.left().x()) + Math.abs(s.left().z()));
   }

   private static double towerOffset(RoadGeometry.Sample s, double width) {
      return towerRadius(s, width) + 0.55;
   }

   private static void cables(
      RoadGeometry.Mesh mesh,
      double start,
      double length,
      double depth,
      RoadInfrastructure.Bridge style,
      RoadStructures.Ground ground,
      List<RoadStructures.Part> out,
      boolean includeSupports
   ) {
      double trim = Math.min(6.0, length * 0.12);
      double trimA = start < 1.0E-6 ? trim : 0.0;
      double trimB = start + length > mesh.length() - 1.0E-6 ? trim : 0.0;
      start += trimA;
      length -= trimA + trimB;
      double tower = towerHeight(length, style);
      double width = towerWidth(tower);
      double brace = Math.max(1.1, width * 0.7);
      double stay = Math.min(0.8, 0.14 + Math.max(0.0, tower - 24.0) * 0.003);
      double mainCable = Math.min(1.5, 0.32 + Math.max(0.0, tower - 24.0) * 0.006);
      double hanger = Math.min(0.7, 0.12 + Math.max(0.0, tower - 24.0) * 0.0025);

      for (double fraction : new double[]{0.2, 0.8}) {
         RoadGeometry.Sample s = sample(mesh, start + length * fraction);
         double offset = towerOffset(s, width);
         if (includeSupports) {
            support(mesh, start + length * fraction, depth, style, ground, out);
         }

         for (int side : new int[]{-1, 1}) {
            post(
               out,
               at(s, (double)side * (s.halfWidth() + offset), -mesh.settings().thickness()),
               width,
               tower + mesh.settings().thickness(),
               RoadStructures.Material.CONCRETE
            );
         }

         beam(out, at(s, -s.halfWidth() - offset, tower * 0.8), at(s, s.halfWidth() + offset, tower * 0.8), brace, brace, RoadStructures.Material.CONCRETE);
         beam(
            out,
            at(s, -s.halfWidth() - offset, -mesh.settings().thickness() - depth),
            at(s, s.halfWidth() + offset, -mesh.settings().thickness() - depth),
            Math.max(2.4, width + 0.4),
            depth,
            RoadStructures.Material.CONCRETE
         );
         if (style == RoadInfrastructure.Bridge.CABLE) {
            for (int side : new int[]{-1, 1}) {
               for (int j = 0; j <= 10; j++) {
                  double t = (fraction < 0.5 ? 0.0 : 0.5) + (double)j * 0.05;
                  RoadGeometry.Sample anchor = sample(mesh, start + length * t);
                  if (!(Math.abs(t - fraction) < 0.03)) {
                     double anchorOffset = (double)side * (anchor.halfWidth() + towerOffset(anchor, width) * endTaper(mesh, anchor));
                     post(out, at(anchor, anchorOffset, -0.15), Math.max(0.45, stay * 2.0), Math.max(0.5, stay), RoadStructures.Material.DARK_STEEL);
                     beam(
                        out,
                        at(anchor, anchorOffset, 0.12),
                        at(s, (double)side * (s.halfWidth() + offset), tower - 0.8),
                        stay,
                        stay + 0.02,
                        RoadStructures.Material.STEEL
                     );
                  }
               }
            }
         }
      }

      if (style == RoadInfrastructure.Bridge.SUSPENSION) {
         int steps = Math.max(24, (int)Math.ceil(length / 3.0));

         for (int side : new int[]{-1, 1}) {
            RoadGeometry.V prev = null;

            for (int i = 0; i <= steps; i++) {
               double t = (double)i / (double)steps;
               RoadGeometry.Sample sx = sample(mesh, start + length * t);
               double offsetx = towerOffset(sx, width);
               double h = t < 0.2
                  ? 1.0 + (tower - 1.0) * t / 0.2
                  : (t > 0.8 ? 1.0 + (tower - 1.0) * (1.0 - t) / 0.2 : tower * 0.35 + tower * 0.65 * Math.pow((t - 0.5) / 0.3, 2.0));
               RoadGeometry.V p = at(sx, (double)side * (sx.halfWidth() + offsetx * endTaper(mesh, sx)), h);
               if (prev != null) {
                  beam(out, prev, p, mainCable, mainCable + 0.03, RoadStructures.Material.DARK_STEEL);
               }

               if (i == 0 || i == steps) {
                  post(
                     out,
                     at(sx, (double)side * (sx.halfWidth() + offsetx * endTaper(mesh, sx)), -mesh.settings().thickness()),
                     Math.max(0.6, mainCable * 1.6),
                     h + mesh.settings().thickness(),
                     RoadStructures.Material.CONCRETE
                  );
               }

               if (i > 0 && i < steps && i % 2 == 0) {
                  post(out, at(sx, (double)side * (sx.halfWidth() + offsetx * endTaper(mesh, sx)), 0.1), hanger, h - 0.1, RoadStructures.Material.STEEL);
               }

               prev = p;
            }
         }
      }
   }

   private static void validateOwnClearance(RoadGeometry.Mesh mesh, List<RoadStructures.Part> parts) {
      for (RoadStructures.Part part : parts) {
         int steps = Math.max(1, (int)Math.ceil(part.b().sub(part.a()).horizontalLength() / 2.0));

         for (int k = 0; k <= steps; k++) {
            RoadGeometry.V p = part.a().add(part.b().sub(part.a()).mul((double)k / (double)steps));
            RoadQueries.Projection q = RoadQueries.horizontal(mesh, p);
            RoadGeometry.Sample s = q.sample();
            if (Math.abs(q.lateral()) < s.halfWidth() - 0.5 + part.width() / 2.0
               && p.y() + part.height() > s.center().y() + 0.5
               && p.y() < s.center().y() + 5.0) {
               throw new IllegalArgumentException("桥拱或拉索侵入本桥行车净空；请缩短桥跨、减小弯曲或改用梁桥");
            }
         }
      }
   }

   public static boolean nearGantry(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, double distance) {
      return RoadGantry.near(mesh, ground, distance);
   }

   private RoadInfrastructure() {
   }

   public static enum Bridge {
      STANDARD("普通高架", 24.0),
      BEAM("小河梁桥", 32.0),
      OVERPASS("跨线桥", 64.0),
      ARCH("大河拱桥", 96.0),
      CABLE("斜拉桥", 192.0),
      SUSPENSION("悬索桥", 256.0),
      VIADUCT("跨海连续梁", 64.0);

      public final String label;
      public final double span;

      private Bridge(String label, double span) {
         this.label = label;
         this.span = span;
      }
   }

   public static record Config(
      RoadInfrastructure.Bridge bridge,
      double span,
      RoadInfrastructure.Tunnel tunnel,
      double headroom,
      RoadInfrastructure.Gantry gantry,
      double spacing,
      double tunnelDepth,
      List<RoadGantry.Edit> gantryEdits,
      RoadTunnelFit.Adjustment adjustment,
      boolean efficientDip,
      double bridgeRise,
      double maxGrade,
      boolean autoSpan,
      boolean fixedStations,
      RoadInfrastructure.MedianConversion medianConversion
   ) {
      public static final RoadInfrastructure.Config DEFAULT = new RoadInfrastructure.Config(
         RoadInfrastructure.Bridge.STANDARD,
         24.0,
         RoadInfrastructure.Tunnel.BOX,
         6.0,
         RoadInfrastructure.Gantry.OFF,
         128.0,
         0.0,
         List.of(),
         RoadTunnelFit.Adjustment.OFF,
         true,
         0.0,
         0.06,
         true
      );

      public Config(
         RoadInfrastructure.Bridge bridge,
         double span,
         RoadInfrastructure.Tunnel tunnel,
         double headroom,
         RoadInfrastructure.Gantry gantry,
         double spacing,
         double depth,
         List<RoadGantry.Edit> edits,
         RoadTunnelFit.Adjustment adjustment,
         boolean efficientDip,
         double rise,
         double maxGrade,
         boolean autoSpan
      ) {
         this(
            bridge,
            span,
            tunnel,
            headroom,
            gantry,
            spacing,
            depth,
            edits,
            adjustment,
            efficientDip,
            rise,
            maxGrade,
            autoSpan,
            false,
            RoadInfrastructure.MedianConversion.OFF
         );
      }

      public Config(
         RoadInfrastructure.Bridge bridge, double span, RoadInfrastructure.Tunnel tunnel, double headroom, RoadInfrastructure.Gantry gantry, double spacing
      ) {
         this(bridge, span, tunnel, headroom, gantry, spacing, 0.0, List.of());
      }

      public Config(
         RoadInfrastructure.Bridge bridge,
         double span,
         RoadInfrastructure.Tunnel tunnel,
         double headroom,
         RoadInfrastructure.Gantry gantry,
         double spacing,
         double depth,
         List<RoadGantry.Edit> edits
      ) {
         this(bridge, span, tunnel, headroom, gantry, spacing, depth, edits, RoadTunnelFit.Adjustment.OFF, true);
      }

      public Config(
         RoadInfrastructure.Bridge bridge,
         double span,
         RoadInfrastructure.Tunnel tunnel,
         double headroom,
         RoadInfrastructure.Gantry gantry,
         double spacing,
         double depth,
         List<RoadGantry.Edit> edits,
         RoadTunnelFit.Adjustment adjustment,
         boolean efficientDip
      ) {
         this(bridge, span, tunnel, headroom, gantry, spacing, depth, edits, adjustment, efficientDip, 0.0, 0.0, false);
      }

      public Config(
         RoadInfrastructure.Bridge bridge,
         double span,
         RoadInfrastructure.Tunnel tunnel,
         double headroom,
         RoadInfrastructure.Gantry gantry,
         double spacing,
         double tunnelDepth,
         List<RoadGantry.Edit> gantryEdits,
         RoadTunnelFit.Adjustment adjustment,
         boolean efficientDip,
         double bridgeRise,
         double maxGrade,
         boolean autoSpan,
         boolean fixedStations,
         RoadInfrastructure.MedianConversion medianConversion
      ) {
         if (medianConversion != null
            && bridge != null
            && tunnel != null
            && gantry != null
            && adjustment != null
            && RoadGeometry.finite(span, headroom, spacing, tunnelDepth, bridgeRise, maxGrade)
            && !(span < 12.0)
            && !(span > 2048.0)
            && !(headroom < 4.0)
            && !(headroom > 12.0)
            && !(spacing < 32.0)
            && !(spacing > 512.0)
            && !(tunnelDepth < 0.0)
            && !(tunnelDepth > 96.0)
            && !(bridgeRise < 0.0)
            && !(bridgeRise > 96.0)
            && !(maxGrade < 0.0)
            && !(maxGrade > 0.2)) {
            gantryEdits = List.copyOf(gantryEdits);
            if (gantryEdits.size() <= 512 && gantryEdits.stream().map(RoadGantry.Edit::slot).distinct().count() == (long)gantryEdits.size()) {
               this.bridge = bridge;
               this.span = span;
               this.tunnel = tunnel;
               this.headroom = headroom;
               this.gantry = gantry;
               this.spacing = spacing;
               this.tunnelDepth = tunnelDepth;
               this.gantryEdits = gantryEdits;
               this.adjustment = adjustment;
               this.efficientDip = efficientDip;
               this.bridgeRise = bridgeRise;
               this.maxGrade = maxGrade;
               this.autoSpan = autoSpan;
               this.fixedStations = fixedStations;
               this.medianConversion = medianConversion;
            } else {
               throw new IllegalArgumentException("龙门架编辑记录重复或过多");
            }
         } else {
            throw new IllegalArgumentException("桥跨 12–2048；净高 4–12；间距 32–512；下潜 / 抬升 0–96 格；坡度不超过 20%");
         }
      }

      public RoadInfrastructure.Config bridge(RoadInfrastructure.Bridge v) {
         return new RoadInfrastructure.Config(
            v,
            v.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            this.gantryEdits,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config span(double v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            v,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            this.gantryEdits,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config tunnel(RoadInfrastructure.Tunnel v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            v,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            this.gantryEdits,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config headroom(double v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            v,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            this.gantryEdits,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config gantry(RoadInfrastructure.Gantry v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            v,
            this.spacing,
            this.tunnelDepth,
            this.gantryEdits,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config spacing(double v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            v,
            this.tunnelDepth,
            this.gantryEdits,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config gantryEdits(List<RoadGantry.Edit> values) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            values,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config depth(double v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            v,
            this.gantryEdits,
            this.adjustment,
            true,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config adjustment(RoadTunnelFit.Adjustment v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            this.gantryEdits,
            v,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config profile(boolean v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            this.gantryEdits,
            this.adjustment,
            v,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config clearEdits() {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            List.of(),
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            false,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config edit(RoadGantry.Edit value) {
         ArrayList<RoadGantry.Edit> edits = new ArrayList<>(this.gantryEdits);
         edits.removeIf(e -> e.slot() == value.slot());
         edits.add(value);
         edits.sort(Comparator.comparingInt(RoadGantry.Edit::slot));
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            edits,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config reset(int slot) {
         RoadGantry.Edit prior = this.gantryEdits.stream().filter(e -> e.slot() == slot).findFirst().orElse(null);
         return this.fixedStations && prior != null && !prior.independent()
            ? this.edit(prior.parameters(0.0, 5.6, RoadInfrastructure.Gantry.AUTO, false))
            : new RoadInfrastructure.Config(
               this.bridge,
               this.span,
               this.tunnel,
               this.headroom,
               this.gantry,
               this.spacing,
               this.tunnelDepth,
               this.gantryEdits.stream().filter(e -> e.slot() != slot).toList(),
               this.adjustment,
               this.efficientDip,
               this.bridgeRise,
               this.maxGrade,
               this.autoSpan,
               this.fixedStations,
               this.medianConversion
            );
      }

      public RoadInfrastructure.Config fixedStations(List<RoadGantry.Edit> values) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            values,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            true,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config rise(double v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            this.gantryEdits,
            this.adjustment,
            this.efficientDip,
            v,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config grade(double v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            this.gantryEdits,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            v,
            this.autoSpan,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config autoSpan(boolean v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            this.gantryEdits,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            v,
            this.fixedStations,
            this.medianConversion
         );
      }

      public RoadInfrastructure.Config medianConversion(RoadInfrastructure.MedianConversion v) {
         return new RoadInfrastructure.Config(
            this.bridge,
            this.span,
            this.tunnel,
            this.headroom,
            this.gantry,
            this.spacing,
            this.tunnelDepth,
            this.gantryEdits,
            this.adjustment,
            this.efficientDip,
            this.bridgeRise,
            this.maxGrade,
            this.autoSpan,
            this.fixedStations,
            v
         );
      }
   }

   public static enum Gantry {
      AUTO("自动龙门架"),
      OFF("关闭"),
      FRAME("设备横梁"),
      SIGNS("普通龙门架");

      public final String label;

      private Gantry(String label) {
         this.label = label;
      }
   }

   public static enum MedianConversion {
      OFF("关"),
      YELLOW("仅双黄线"),
      GREEN("仅绿化带"),
      BOTH("开（两者都）");

      public final String label;

      private MedianConversion(String label) {
         this.label = label;
      }

      public boolean converts(RoadProfile.Median median) {
         return median == RoadProfile.Median.DOUBLE_YELLOW && (this == YELLOW || this == BOTH)
            || median == RoadProfile.Median.GREEN && (this == GREEN || this == BOTH);
      }
   }

   public static enum Tunnel {
      BOX("箱形地下通道"),
      ARCH("拱顶隧道"),
      ROUND("圆形铁路隧道");

      public final String label;

      private Tunnel(String label) {
         this.label = label;
      }
   }
}
