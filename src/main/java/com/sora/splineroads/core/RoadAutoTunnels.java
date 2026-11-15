package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.function.DoubleUnaryOperator;

public final class RoadAutoTunnels {
   private static final String PREFIX = "sr:auto_tunnel:";
   private static final String OPEN = "sr:auto_cut:";
   private static final String PROFILE = "sr:auto_roof:";
   public static final double HEADROOM = 6.0;
   public static final double MIN_COVER = 7.05;
   public static final double WALL_LIP = 0.5;
   public static final double ROOF_THICKNESS = 1.05;
   public static final double CANOPY = 4.0;
   public static final double LINING = 1.55;
   public static final double WALL_WIDTH = 1.6;
   public static final double MIN_CUT_DEPTH = 2.0;
   public static final double MIN_ROOF_HEIGHT = 4.0;
   public static final double MAX_ROOF_HEIGHT = 96.0;
   public static final double MAX_SHORT_STRUCTURE = 24.0;

   public static double minCutDepth(RoadGeometry.Mesh mesh) {
      return mesh.settings().style() == RoadGeometry.Style.RAILBED ? 1.0 : 2.0;
   }

   private static boolean validRoofHeight(double height) {
      return Double.isFinite(height) && height >= 4.0 && height <= 96.0;
   }

   private static String roofProfile(double from, double to, double first, double last) {
      if (RoadGeometry.finite(from, to) && !(from < 0.0) && !(to <= from) && validRoofHeight(first) && validRoofHeight(last)) {
         return "sr:auto_roof:" + from + ":" + to + ":" + first + ":" + last;
      } else {
         throw new IllegalArgumentException("自动隧道洞口高度超出可支撑范围（4–96 格），请调整道路高程或洞口位置");
      }
   }

   public static List<RoadAutoTunnels.RoofRegion> roofRegions(List<RoadStructures.Part> parts) {
      LinkedHashSet<RoadAutoTunnels.RoofRegion> out = new LinkedHashSet<>();

      for (RoadStructures.Part p : parts) {
         if (p.material() == RoadStructures.Material.TUNNEL && p.model().startsWith("sr:auto_roof:")) {
            double[] v = parse(p.model(), "sr:auto_roof:", 4);
            if (v != null && validRoofHeight(v[2]) && validRoofHeight(v[3])) {
               out.add(new RoadAutoTunnels.RoofRegion(v[0], v[1], v[2], v[3]));
            }
         }
      }

      return List.copyOf(out);
   }

   public static DoubleUnaryOperator wallCeiling(RoadGeometry.Mesh mesh, List<RoadStructures.Part> parts) {
      TreeMap<Double, double[]> spans = new TreeMap<>();

      for (RoadStructures.Part p : parts) {
         if (p.material() == RoadStructures.Material.TUNNEL && p.model().startsWith("sr:auto_cut:")) {
            double[] tag = parse(p.model(), "sr:auto_cut:", 3);
            if (tag != null) {
               RoadGeometry.Sample a = RoadStructures.sample(mesh, tag[0]);
               RoadGeometry.Sample b = RoadStructures.sample(mesh, tag[1]);
               List<RoadGeometry.V> top = p.top();
               double ha = (top.get(0).y() + top.get(1).y()) / 2.0 - a.center().y();
               double hb = (top.get(2).y() + top.get(3).y()) / 2.0 - b.center().y();
               double[] old = spans.get(tag[0]);
               if (old == null) {
                  spans.put(tag[0], new double[]{tag[1], ha, hb});
               } else {
                  old[1] = Math.max(old[1], ha);
                  old[2] = Math.max(old[2], hb);
               }
            }
         }
      }

      return d -> {
         Entry<Double, double[]> e = spans.floorEntry(d + 1.0E-7);
         if (e != null && !(d > e.getValue()[0] + 1.0E-7)) {
            double[] v = e.getValue();
            double t = Math.max(0.0, Math.min(1.0, (d - e.getKey()) / (v[0] - e.getKey())));
            return v[1] * (1.0 - t) + v[2] * t;
         } else {
            return 0.0;
         }
      };
   }

   static int[] normalize(int[] original) {
      int[] kinds = (int[])original.clone();

      for (int pass = 0; pass < original.length; pass++) {
         ArrayList<RoadAutoTunnels.Run> runs = new ArrayList<>();
         int i = 0;

         while (i < kinds.length) {
            int j = i + 1;

            while (j < kinds.length && kinds[j] == kinds[i]) {
               j++;
            }

            runs.add(new RoadAutoTunnels.Run(i, j, kinds[i]));
            i = j;
         }

         RoadAutoTunnels.Run best = null;
         int target = 0;

         for (int ix = 1; ix < runs.size() - 1; ix++) {
            RoadAutoTunnels.Run a = runs.get(ix - 1);
            RoadAutoTunnels.Run b = runs.get(ix);
            RoadAutoTunnels.Run c = runs.get(ix + 1);
            int n = b.to() - b.from();
            if (a.kind() == c.kind() && (double)n <= 24.0 && a.to() - a.from() > n && c.to() - c.from() > n && (best == null || n < best.to() - best.from())) {
               best = b;
               target = a.kind();
            }
         }

         if (best == null) {
            break;
         }

         Arrays.fill(kinds, best.from(), best.to(), target);
      }

      return kinds;
   }

   public static boolean below(RoadGeometry.Sample at, RoadStructures.Ground ground) {
      return below(at, ground, 0, 2.0);
   }

   public static boolean below(RoadGeometry.Sample at, RoadStructures.Ground ground, int side) {
      return below(at, ground, side, 2.0);
   }

   public static boolean below(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, RoadStructures.Ground ground) {
      return below(mesh, at, ground, 0);
   }

   public static boolean below(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, RoadStructures.Ground ground, int side) {
      if (below(at, ground, side, minCutDepth(mesh))) {
         return true;
      } else {
         if (mesh.settings().style() == RoadGeometry.Style.RAILBED) {
            for (int s : side == 0 ? new int[]{-1, 1} : new int[]{side}) {
               for (double offset : new double[]{0.25, 0.75, 1.25}) {
                  RoadGeometry.V p = at.at((double)s * (at.halfWidth() + offset), 0.0);
                  double h = ground.surface(p.x(), p.z(), p.y());
                  if (Double.isFinite(h) && h >= p.y() + minCutDepth(mesh) - 1.0E-7) {
                     return true;
                  }
               }
            }
         }

         return false;
      }
   }

   private static boolean below(RoadGeometry.Sample at, RoadStructures.Ground ground, int side, double depth) {
      for (double u : side == 0 ? new double[]{-0.8, 0.0, 0.8} : new double[]{0.35 * (double)side, 0.65 * (double)side, 0.9 * (double)side}) {
         RoadGeometry.V p = at.at(u * at.halfWidth(), 0.0);
         double h = ground.surface(p.x(), p.z(), p.y());
         if (Double.isFinite(h) && h >= p.y() + depth - 1.0E-7) {
            return true;
         }
      }

      return false;
   }

   public static List<RoadAutoTunnels.Section> sections(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      if (mesh.settings().structure() != RoadGeometry.Structure.AUTO) {
         return List.of();
      } else {
         ArrayList<RoadAutoTunnels.Section> out = new ArrayList<>();

         for (double d = 0.0; d < mesh.length() - 1.0E-7; d++) {
            RoadPlanningBudget.check();
            double end = Math.min(mesh.length(), d + 1.0);
            double mid = (d + end) / 2.0;
            RoadGeometry.Sample at = RoadStructures.sample(mesh, mid);
            if (below(mesh, at, ground)) {
               boolean roof = true;
               double left = 0.0;
               double right = 0.0;
               double clearance = 6.0;

               for (double station : new double[]{d + 1.0E-6, mid, end - 1.0E-6}) {
                  RoadGeometry.Sample s = RoadStructures.sample(mesh, station);
                  int across = (int)Math.ceil((s.halfWidth() * 2.0 + 3.1) / 0.5);

                  for (int i = 0; i <= across; i++) {
                     double u = -1.0 + 2.0 * (double)i / (double)across;
                     RoadGeometry.V p = s.at(u * (s.halfWidth() + 1.55), 0.0);
                     double h = ground.surface(p.x(), p.z(), p.y());
                     double depth = Double.isFinite(h) ? Math.max(0.0, h - p.y()) : 0.0;
                     roof &= depth >= 7.0499998999999995;
                     double lateral = u * (s.halfWidth() + 1.55);
                     double median = Math.min(1.0, s.halfWidth() * 0.35);
                     if (lateral < -median) {
                        right = Math.max(right, depth);
                     }

                     if (lateral > median) {
                        left = Math.max(left, depth);
                     }

                     clearance = Math.max(clearance, depth + 0.5);
                  }
               }

               roof |= Math.max(left, right) + 0.5 > 6.0000001;
               out.add(new RoadAutoTunnels.Section(d, end, roof, left, right, Math.min(96.0, clearance)));
            }
         }

         int count = (int)Math.ceil(mesh.length());
         int[] raw = new int[count];
         RoadAutoTunnels.Section[] original = new RoadAutoTunnels.Section[count];

         for (RoadAutoTunnels.Section section : out) {
            int i = (int)Math.round(section.from());
            raw[i] = section.roof() ? 2 : 1;
            original[i] = section;
         }

         int[] kinds = normalize(raw);
         ArrayList<RoadAutoTunnels.Section> continuous = new ArrayList<>();

         for (int i = 0; i < count; i++) {
            if (kinds[i] != 0 || original[i] != null) {
               RoadAutoTunnels.Section old = original[i];
               double left = old == null ? 0.0 : old.leftDepth();
               double right = old == null ? 0.0 : old.rightDepth();
               double clear = old == null ? 6.0 : old.clearance();
               continuous.add(
                  new RoadAutoTunnels.Section((double)i, Math.min(mesh.length(), (double)(i + 1)), kinds[i] == 2, left, right, clear, kinds[i] == 0)
               );
            }
         }

         return List.copyOf(continuous);
      }
   }

   public static List<RoadAutoTunnels.Section> structuralSections(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      List<RoadAutoTunnels.Section> raw = sections(mesh, ground);
      List<RoadAutoTunnels.Section> roofs = raw.stream().filter(RoadAutoTunnels.Section::roof).toList();
      return raw.stream()
         .map(
            s -> new RoadAutoTunnels.Section(
                  s.from(),
                  s.to(),
                  !s.surface() && (s.roof() || roofs.stream().anyMatch(r -> s.to() > r.from() - 4.0 + 1.0E-7 && s.from() < r.to() + 4.0 - 1.0E-7)),
                  s.leftDepth(),
                  s.rightDepth(),
                  s.clearance(),
                  s.surface()
               )
         )
         .toList();
   }

   public static List<RoadAutoTunnels.Region> detect(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      ArrayList<RoadAutoTunnels.Region> out = new ArrayList<>();

      for (RoadAutoTunnels.Section section : structuralSections(mesh, ground)) {
         if (section.roof()) {
            if (!out.isEmpty() && Math.abs(out.get(out.size() - 1).to() - section.from()) < 1.0E-7) {
               RoadAutoTunnels.Region old = out.remove(out.size() - 1);
               out.add(new RoadAutoTunnels.Region(old.from(), section.to()));
            } else {
               out.add(new RoadAutoTunnels.Region(section.from(), section.to()));
            }
         }
      }

      return List.copyOf(out);
   }

   public static RoadGeometry.Mesh tube(RoadGeometry.Mesh mesh, RoadAutoTunnels.Region r) {
      return tube(mesh, r, 6.0);
   }

   public static RoadGeometry.Mesh tube(RoadGeometry.Mesh mesh, RoadAutoTunnels.Region r, double height) {
      ArrayList<RoadGeometry.Sample> points = new ArrayList<>();

      for (double d = r.from(); d < r.to() - 1.0E-7; d++) {
         points.add(RoadStructures.sample(mesh, d));
      }

      points.add(RoadStructures.sample(mesh, r.to()));
      RoadProfile.Options options = mesh.settings()
         .options()
         .infrastructure(RoadInfrastructure.Config.DEFAULT.headroom(Math.max(4.0, Math.min(12.0, height))).gantry(RoadInfrastructure.Gantry.OFF));
      return RoadRibbon.mesh(points, mesh.settings().structure(RoadGeometry.Structure.TUNNEL).options(options));
   }

   public static List<RoadAutoTunnels.Region> regions(List<RoadStructures.Part> parts) {
      LinkedHashSet<RoadAutoTunnels.Region> out = new LinkedHashSet<>(legacyRegions(parts));

      for (RoadAutoTunnels.RoofRegion r : roofRegions(parts)) {
         out.add(new RoadAutoTunnels.Region(r.from(), r.to()));
      }

      ArrayList<RoadAutoTunnels.Region> merged = new ArrayList<>();

      for (RoadAutoTunnels.Region r : out.stream().sorted(Comparator.comparingDouble(RoadAutoTunnels.Region::from)).toList()) {
         if (!merged.isEmpty() && r.from() <= merged.get(merged.size() - 1).to() + 1.0E-7) {
            RoadAutoTunnels.Region old = merged.remove(merged.size() - 1);
            merged.add(new RoadAutoTunnels.Region(old.from(), Math.max(old.to(), r.to())));
         } else {
            merged.add(r);
         }
      }

      return List.copyOf(merged);
   }

   public static List<RoadAutoTunnels.Region> legacyRegions(List<RoadStructures.Part> parts) {
      LinkedHashSet<RoadAutoTunnels.Region> out = new LinkedHashSet<>();

      for (RoadStructures.Part p : parts) {
         if (p.material() == RoadStructures.Material.TUNNEL && p.model().startsWith("sr:auto_tunnel:")) {
            double[] values = parse(p.model(), "sr:auto_tunnel:", 2);
            if (values != null) {
               out.add(new RoadAutoTunnels.Region(values[0], values[1]));
            }
         }
      }

      ArrayList<RoadAutoTunnels.Region> merged = new ArrayList<>();

      for (RoadAutoTunnels.Region r : out.stream().sorted(Comparator.comparingDouble(RoadAutoTunnels.Region::from)).toList()) {
         if (!merged.isEmpty() && r.from() <= merged.get(merged.size() - 1).to() + 1.0E-7) {
            RoadAutoTunnels.Region old = merged.remove(merged.size() - 1);
            merged.add(new RoadAutoTunnels.Region(old.from(), Math.max(old.to(), r.to())));
         } else {
            merged.add(r);
         }
      }

      return List.copyOf(merged);
   }

   public static List<RoadAutoTunnels.OpenRegion> openRegions(List<RoadStructures.Part> parts) {
      LinkedHashSet<RoadAutoTunnels.OpenRegion> out = new LinkedHashSet<>();

      for (RoadStructures.Part p : parts) {
         if (p.material() == RoadStructures.Material.TUNNEL && p.model().startsWith("sr:auto_cut:")) {
            double[] values = parse(p.model(), "sr:auto_cut:", 3);
            if (values != null && values[2] >= 4.0 && values[2] <= 96.0) {
               out.add(new RoadAutoTunnels.OpenRegion(values[0], values[1], values[2]));
            }
         }
      }

      ArrayList<RoadAutoTunnels.OpenRegion> merged = new ArrayList<>();

      for (RoadAutoTunnels.OpenRegion r : out.stream().sorted(Comparator.comparingDouble(RoadAutoTunnels.OpenRegion::from)).toList()) {
         if (!merged.isEmpty()) {
            RoadAutoTunnels.OpenRegion last = merged.get(merged.size() - 1);
            if (Math.abs(last.to() - r.from()) < 1.0E-7 && Math.abs(last.clearance() - r.clearance()) < 1.0E-7) {
               merged.set(merged.size() - 1, new RoadAutoTunnels.OpenRegion(last.from(), r.to(), last.clearance()));
               continue;
            }
         }

         merged.add(r);
      }

      return List.copyOf(merged);
   }

   private static double[] parse(String tag, String prefix, int count) {
      String[] pieces = tag.substring(prefix.length()).split(":");
      if (pieces.length != count) {
         return null;
      } else {
         try {
            double[] values = new double[count];

            for (int i = 0; i < count; i++) {
               values[i] = Double.parseDouble(pieces[i]);
               if (!Double.isFinite(values[i])) {
                  return null;
               }
            }

            return values[0] >= 0.0 && values[1] > values[0] ? values : null;
         } catch (NumberFormatException var6) {
            return null;
         }
      }
   }

   public static List<RoadStructures.Part> enclose(RoadGeometry.Mesh mesh, RoadStructures.Ground ground, List<RoadStructures.Part> original) {
      return enclose(mesh, ground, original, d -> 6.0);
   }

   public static List<RoadStructures.Part> enclose(
      RoadGeometry.Mesh mesh, RoadStructures.Ground ground, List<RoadStructures.Part> original, DoubleUnaryOperator ceiling
   ) {
      List<RoadAutoTunnels.Section> sections = structuralSections(mesh, ground);
      if (sections.isEmpty()) {
         return original;
      } else {
         ArrayList<RoadStructures.Part> out = new ArrayList<>();
         HashSet<Double> roofStarts = new HashSet<>();
         HashSet<Double> roofEnds = new HashSet<>();

         for (RoadAutoTunnels.Section s : sections) {
            if (!s.surface() && s.roof()) {
               roofStarts.add(s.from());
               roofEnds.add(s.to());
            }
         }

         HashMap<Double, Double> portals = new HashMap<>();

         for (double d : roofStarts) {
            if (d > mesh.first().distance() + 1.0E-7 && !roofEnds.contains(d)) {
               portals.put(d, portalTop(mesh, d, ground, ceiling));
            }
         }

         for (double dx : roofEnds) {
            if (dx < mesh.last().distance() - 1.0E-7 && !roofStarts.contains(dx)) {
               portals.put(dx, portalTop(mesh, dx, ground, ceiling));
            }
         }

         DoubleUnaryOperator lining = dxx -> {
            RoadGeometry.Sample at = RoadStructures.sample(mesh, dxx);
            double head = Math.max(6.0, ceiling.applyAsDouble(dxx));
            double normal = at.center().y() + head + 1.05;
            double top = normal;

            for (Entry<Double, Double> portal : portals.entrySet()) {
               double distance = Math.abs(dxx - portal.getKey());
               double weight = 1.0 - RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, (distance - 4.0) / 16.0)));
               top = Math.max(top, normal + Math.max(0.0, portal.getValue() - normal) * weight);
            }

            return head + Math.max(0.0, top - normal);
         };
         List<RoadAutoTunnels.Section> effective = sections.stream()
            .filter(sx -> !sx.surface())
            .map(sx -> new RoadAutoTunnels.Section(sx.from(), sx.to(), roofStarts.contains(sx.from()), sx.leftDepth(), sx.rightDepth(), sx.clearance()))
            .toList();

         for (RoadStructures.Part part : original) {
            retainOutside(out, part, mesh, ground, effective);
         }

         HashMap<String, Double> wallHeights = new HashMap<>();
         HashMap<Integer, List<Double>> sideEdges = new HashMap<>();

         for (int side : new int[]{-1, 1}) {
            HashSet<Double> cutStarts = new HashSet<>();
            HashSet<Double> cutEnds = new HashSet<>();

            for (RoadAutoTunnels.Section section : sections) {
               if (!section.surface() && (section.roof() || (side < 0 ? section.rightDepth() : section.leftDepth()) >= minCutDepth(mesh))) {
                  cutStarts.add(section.from());
                  cutEnds.add(section.to());
               }
            }

            ArrayList<Double> edges = new ArrayList<>();

            for (double dxx : cutStarts) {
               if (dxx > 1.0E-7 && !cutEnds.contains(dxx)) {
                  edges.add(dxx);
               }
            }

            for (double dxxx : cutEnds) {
               if (dxxx < mesh.length() - 1.0E-7 && !cutStarts.contains(dxxx)) {
                  edges.add(dxxx);
               }
            }

            sideEdges.put(side, edges);
         }

         for (RoadAutoTunnels.Section sx : sections) {
            RoadGeometry.Sample a = RoadStructures.sample(mesh, sx.from());
            RoadGeometry.Sample b = RoadStructures.sample(mesh, sx.to());
            if (sx.surface()) {
               out.add(
                  new RoadStructures.Part(
                     a.at(0.0, mesh.settings().thickness() - 0.1),
                     b.at(0.0, mesh.settings().thickness() - 0.1),
                     0.1,
                     0.05,
                     false,
                     RoadStructures.Material.TUNNEL,
                     a.left().mul(0.05),
                     b.left().mul(0.05),
                     "sr:auto_cut:" + sx.from() + ":" + sx.to() + ":" + sx.clearance()
                  )
               );
            } else {
               RoadProfile.Layout medianA = RoadProfile.layout(mesh, a);
               RoadProfile.Layout medianB = RoadProfile.layout(mesh, b);
               boolean roof = sx.roof();
               if (medianA.catalog().twoWay()
                  && Math.min(medianA.median(), medianB.median()) >= 0.35
                  && (roof || sx.leftDepth() >= minCutDepth(mesh) && sx.rightDepth() >= minCutDepth(mesh))) {
                  RoadStructures.medianBarrier(out, mesh, a, b, medianA.catalog().type() == RoadProfile.Type.HIGHWAY, false, sx.from());
               }

               double ha = lining.applyAsDouble(sx.from());
               double hb = lining.applyAsDouble(sx.to());
               boolean railBox = mesh.settings().options().railbed().active()
                  && mesh.settings().options().railbed().fittings().tunnel() != RoadInfrastructure.Tunnel.ROUND;
               if (railBox) {
                  ha = hb = mesh.settings().options().infrastructure().headroom();
               }

               if (roof
                  && mesh.settings().options().railbed().active()
                  && mesh.settings().options().railbed().fittings().tunnel() == RoadInfrastructure.Tunnel.ROUND) {
                  ha = RailbedTunnel.top(mesh.settings().options().infrastructure(), a.halfWidth());
                  hb = RailbedTunnel.top(mesh.settings().options().infrastructure(), b.halfWidth());
               }

               String tag = roof ? roofProfile(sx.from(), sx.to(), ha, hb) : "sr:auto_cut:" + sx.from() + ":" + sx.to() + ":" + sx.clearance();
               if (roof && mesh.settings().options().railbed().active()) {
                  out.addAll(RailbedTunnel.section(mesh, a, b, tag));
               } else {
                  for (int side : new int[]{-1, 1}) {
                     if ((!mesh.settings().options().railbed().active() || mesh.settings().options().railbed().fittings().rail(side))
                        && (roof || !((side < 0 ? sx.rightDepth() : sx.leftDepth()) < minCutDepth(mesh)))) {
                        RoadGeometry.V pa = a.at((double)side * (a.halfWidth() + 0.75), 0.0);
                        RoadGeometry.V pb = b.at((double)side * (b.halfWidth() + 0.75), 0.0);
                        double ta = roof ? pa.y() + ha : smoothWallTop(mesh, sx.from(), side, ground, wallHeights);
                        double tb = roof ? pb.y() + hb : smoothWallTop(mesh, sx.to(), side, ground, wallHeights);
                        double roofThickness = railBox ? 0.85 : 1.05;
                        if (!roof && roofEnds.contains(sx.from())) {
                           ta = pa.y() + ha + roofThickness;
                        }

                        if (!roof && roofStarts.contains(sx.to())) {
                           tb = pb.y() + hb + roofThickness;
                        }

                        if (!roof) {
                           if (!roofEnds.contains(sx.from())) {
                              ta = Math.min(ta, pa.y() + 6.0);
                           }

                           if (!roofStarts.contains(sx.to())) {
                              tb = Math.min(tb, pb.y() + 6.0);
                           }

                           ta = taperWall(sideEdges.get(side), sx.from(), pa.y(), ta);
                           tb = taperWall(sideEdges.get(side), sx.to(), pb.y(), tb);
                        }

                        double height = Math.min(96.0, Math.max(ta - pa.y(), tb - pb.y()) + mesh.settings().thickness());
                        out.add(
                           new RoadStructures.Part(
                              new RoadGeometry.V(pa.x(), ta - height, pa.z()),
                              new RoadGeometry.V(pb.x(), tb - height, pb.z()),
                              1.6,
                              height,
                              false,
                              RoadStructures.Material.TUNNEL,
                              a.left().mul(0.8),
                              b.left().mul(0.8),
                              tag
                           )
                        );
                        if (roof) {
                           RoadGeometry.V lightA = a.at((double)side * (a.halfWidth() - 0.12), -0.8);
                           RoadGeometry.V lightB = b.at((double)side * (b.halfWidth() - 0.12), -0.8);
                           out.add(
                              new RoadStructures.Part(
                                 lightA, lightB, 0.12, 0.18, false, RoadStructures.Material.LAMP, a.left().mul(0.06), b.left().mul(0.06), "sr:auto_wall_light"
                              )
                           );
                        }

                        if (medianA.cycleWidth() > 0.01 && medianB.cycleWidth() > 0.01) {
                           double inset = RoadStreetscape.separatorWidth(mesh.settings().options()) / 2.0;
                           RoadStructures.barrier(
                              out,
                              a.at(medianA.outer(side) + (double)side * inset, 0.0),
                              b.at(medianB.outer(side) + (double)side * inset, 0.0),
                              false,
                              false,
                              sx.from()
                           );
                        }
                     }
                  }

                  if (roof) {
                     int bands = Math.max(1, (int)Math.ceil((Math.max(a.halfWidth(), b.halfWidth()) * 2.0 + 3.1) / 6.0));

                     for (int i = 0; i < bands; i++) {
                        double u = -1.0 + 2.0 * (double)i / (double)bands;
                        double v = -1.0 + 2.0 * (double)(i + 1) / (double)bands;
                        double wa = a.halfWidth() + 1.55;
                        double wb = b.halfWidth() + 1.55;
                        out.add(
                           new RoadStructures.Part(
                              a.at(wa * (u + v) / 2.0, -ha),
                              b.at(wb * (u + v) / 2.0, -hb),
                              Math.max(wa, wb) * (v - u),
                              1.05,
                              false,
                              RoadStructures.Material.TUNNEL,
                              a.left().mul(wa * (v - u) / 2.0),
                              b.left().mul(wb * (v - u) / 2.0),
                              tag
                           )
                        );
                     }

                     double start = sx.from() + (roofEnds.contains(sx.from()) ? 0.0 : 0.08);
                     double end = sx.to() - (roofStarts.contains(sx.to()) ? 0.0 : 0.08);
                     RoadGeometry.Sample la = RoadStructures.sample(mesh, start);
                     RoadGeometry.Sample lb = RoadStructures.sample(mesh, end);
                     out.add(
                        new RoadStructures.Part(
                           la.at(0.0, -lining.applyAsDouble(start) + 0.14),
                           lb.at(0.0, -lining.applyAsDouble(end) + 0.14),
                           0.22,
                           0.12,
                           false,
                           RoadStructures.Material.LAMP,
                           la.left().mul(0.11),
                           lb.left().mul(0.11),
                           "sr:auto_light"
                        )
                     );
                  }
               }
            }
         }

         out.addAll(RailbedTunnel.portals(mesh, regions(out), ground));
         return List.copyOf(out);
      }
   }

   private static double taperWall(List<Double> edges, double d, double roadY, double top) {
      double fade = 1.0;

      for (double edge : edges) {
         fade = Math.min(fade, Math.abs(d - edge) / 4.0);
      }

      return roadY + Math.max(0.15, (top - roadY) * fade);
   }

   private static double portalTop(RoadGeometry.Mesh mesh, double station, RoadStructures.Ground ground, DoubleUnaryOperator ceiling) {
      RoadGeometry.Sample at = RoadStructures.sample(mesh, station);
      return at.center().y() + Math.max(6.0, ceiling.applyAsDouble(station)) + 1.05;
   }

   private static double wallTop(RoadGeometry.Sample at, int side, RoadStructures.Ground ground) {
      double top = Double.NEGATIVE_INFINITY;

      for (double u : new double[]{0.0, 0.5, 1.0}) {
         RoadGeometry.V p = at.at((double)side * (at.halfWidth() + 1.55) * u, 0.0);
         double h = ground.surface(p.x(), p.z(), p.y());
         if (Double.isFinite(h)) {
            top = Math.max(top, h);
         }
      }

      return Math.max(at.at((double)side * (at.halfWidth() + 0.75), 0.0).y() + 1.2, top + 0.5);
   }

   private static double smoothWallTop(RoadGeometry.Mesh mesh, double station, int side, RoadStructures.Ground ground, Map<String, Double> cache) {
      double first = Math.floor(station / 4.0) * 4.0;
      double last = Math.min(mesh.length(), first + 4.0);
      DoubleUnaryOperator knot = d -> cache.computeIfAbsent(side + ":" + d, key -> {
            double top = Double.NEGATIVE_INFINITY;

            for (double x = Math.max(0.0, d - 4.0); x <= Math.min(mesh.length(), d + 4.0) + 1.0E-7; x += 0.5) {
               top = Math.max(top, wallTop(RoadStructures.sample(mesh, x), side, ground));
            }

            return top;
         });
      double a = knot.applyAsDouble(first);
      double b = knot.applyAsDouble(last);
      return last <= first ? a : a + (b - a) * (station - first) / (last - first);
   }

   private static void retainOutside(
      List<RoadStructures.Part> out, RoadStructures.Part part, RoadGeometry.Mesh mesh, RoadStructures.Ground ground, List<RoadAutoTunnels.Section> allSections
   ) {
      double partSide = RoadQueries.horizontal(mesh, part.a().add(part.b()).mul(0.5)).lateral();
      boolean median = medianFurniture(mesh, part);
      List<RoadAutoTunnels.Section> sections = allSections.stream()
         .filter(
            sx -> sx.roof()
                  || (
                     median
                        ? sx.leftDepth() >= minCutDepth(mesh) && sx.rightDepth() >= minCutDepth(mesh)
                        : (
                           partSide < -0.05
                              ? sx.rightDepth() >= minCutDepth(mesh)
                              : (
                                 partSide > 0.05
                                    ? sx.leftDepth() >= minCutDepth(mesh)
                                    : sx.leftDepth() >= minCutDepth(mesh) && sx.rightDepth() >= minCutDepth(mesh)
                              )
                        )
                  )
         )
         .toList();
      double da = RoadQueries.horizontal(mesh, part.a()).sample().distance();
      double db = RoadQueries.horizontal(mesh, part.b()).sample().distance();
      boolean rail = !part.pier()
         && part.width() <= 0.7
         && part.height() <= 1.2
         && (
            part.material() == RoadStructures.Material.STEEL
               || part.material() == RoadStructures.Material.DARK_STEEL
               || part.material() == RoadStructures.Material.CONCRETE
         );
      if (rail && !(Math.abs(db - da) < 1.0E-8)) {
         ArrayList<double[]> spans = new ArrayList<>();
         spans.add(new double[]{0.0, 1.0});

         for (RoadAutoTunnels.Section s : sections) {
            double x = (s.from() - da) / (db - da);
            double y = (s.to() - da) / (db - da);
            double lo = Math.max(0.0, Math.min(x, y));
            double hi = Math.min(1.0, Math.max(x, y));
            if (!(hi <= lo)) {
               ArrayList<double[]> next = new ArrayList<>();

               for (double[] r : spans) {
                  if (lo > r[0]) {
                     next.add(new double[]{r[0], Math.min(lo, r[1])});
                  }

                  if (hi < r[1]) {
                     next.add(new double[]{Math.max(hi, r[0]), r[1]});
                  }
               }

               spans = next;
            }
         }

         for (double[] r : spans) {
            if (r[1] - r[0] > 1.0E-7) {
               RoadGeometry.V a = part.a().add(part.b().sub(part.a()).mul(r[0]));
               RoadGeometry.V b = part.a().add(part.b().sub(part.a()).mul(r[1]));
               RoadGeometry.V fa = frame(part, r[0]);
               RoadGeometry.V fb = frame(part, r[1]);
               RoadStructures.Part kept = new RoadStructures.Part(a, b, part.width(), part.height(), false, part.material(), fa, fb, part.model());
               out.add(kept);
               if (part.material() != RoadStructures.Material.DARK_STEEL) {
                  for (RoadGeometry.V p : List.of(a, b)) {
                     double d = RoadQueries.horizontal(mesh, p).sample().distance();

                     for (RoadAutoTunnels.Section sx : sections) {
                        double sign = Math.abs(d - sx.from()) < 1.0E-6 ? 1.0 : (Math.abs(d - sx.to()) < 1.0E-6 ? -1.0 : 0.0);
                        if (sign != 0.0) {
                           boolean outer = sections.stream()
                              .noneMatch(other -> sign > 0.0 ? Math.abs(other.to() - d) < 1.0E-7 : Math.abs(other.from() - d) < 1.0E-7);
                           if (outer) {
                              RoadGeometry.Sample start = RoadStructures.sample(mesh, d);
                              double lateral = p.sub(start.center()).dot(start.left());
                              if (!(Math.abs(lateral) < start.halfWidth() - 1.0)) {
                                 double side = Math.signum(lateral);
                                 double height = p.y() - start.at(lateral, 0.0).y();
                                 RoadGeometry.V wall = start.at(side * (start.halfWidth() + 0.75), -height);
                                 RoadStructures.Part join = new RoadStructures.Part(
                                    p, wall, part.width(), part.height(), false, part.material(), null, null, "sr:wall_rail"
                                 );
                                 if (!ground.railBlocked(join, p, wall)) {
                                    out.add(join);
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      } else {
         double d = (da + db) / 2.0;
         if (sections.stream().noneMatch(sxx -> d >= sxx.from() && d <= sxx.to())) {
            out.add(part);
         }
      }
   }

   private static boolean medianFurniture(RoadGeometry.Mesh mesh, RoadStructures.Part part) {
      if (!part.pier() && !(part.height() > 1.2)) {
         for (RoadGeometry.V p : part.base()) {
            RoadQueries.Projection q = RoadQueries.horizontal(mesh, p);
            RoadGeometry.Sample at = q.sample();
            RoadProfile.Layout layout = RoadProfile.layout(mesh, at);
            if (!layout.catalog().twoWay()
               || layout.median() < 0.35
               || Math.abs(q.lateral() - layout.medianCenter()) > layout.median() / 2.0 + 0.05
               || p.y() < at.center().y() - 0.05
               || p.y() + part.height() > at.center().y() + 1.6) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private static RoadGeometry.V frame(RoadStructures.Part part, double t) {
      return part.frameA() != null && part.frameB() != null ? part.frameA().add(part.frameB().sub(part.frameA()).mul(t)) : null;
   }

   private RoadAutoTunnels() {
   }

   public static record OpenRegion(double from, double to, double clearance) {
   }

   public static record Region(double from, double to) {
   }

   public static record RoofRegion(double from, double to, double first, double last) {
   }

   private static record Run(int from, int to, int kind) {
   }

   public static record Section(double from, double to, boolean roof, double leftDepth, double rightDepth, double clearance, boolean surface) {
      public Section(double from, double to, boolean roof, double leftDepth, double rightDepth, double clearance) {
         this(from, to, roof, leftDepth, rightDepth, clearance, false);
      }
   }
}
