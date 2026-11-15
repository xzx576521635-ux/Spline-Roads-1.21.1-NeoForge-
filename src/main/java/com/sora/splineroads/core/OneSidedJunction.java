package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OneSidedJunction {
   private static final WeakIdentityCache<OneSidedJunction.Source, OneSidedJunction.Masks> MASKS = new WeakIdentityCache<>(16, 16L, m -> 1);

   public static int allowed(JunctionSpec s, int arm) {
      OneSidedJunction.Source source = s.oneSide();
      if (source == null) {
         return 15;
      } else if (arm == source.outgoing()) {
         return 0;
      } else {
         return arm == 2 ? 4 : 1 | (s.arms().get(2).outgoing() > 0 ? 4 : 0);
      }
   }

   public static JunctionSpec prepare(JunctionSpec s) {
      OneSidedJunction.Source source = s.oneSide();
      if (source == null) {
         return s;
      } else if (s.kind() == JunctionSpec.Kind.INTERSECTION
         && s.arms().size() == 3
         && !s.leftTraffic()
         && !s.parallelApproaches()
         && !s.arms().stream().anyMatch(JunctionSpec.Arm::rightSlip)) {
         ArrayList<JunctionSpec.Arm> arms = new ArrayList<>();

         for (int i = 0; i < 3; i++) {
            JunctionSpec.Arm a = s.arms().get(i);
            ArrayList<JunctionSpec.Lane> lanes = new ArrayList<>();
            int available = allowed(s, i);

            for (int k = 0; k < a.incoming(); k++) {
               JunctionSpec.Lane l = a.lanes().get(k);
               int mask = l.mask();
               if (mask < 0) {
                  mask = i == source.incoming() ? 1 | (k == a.incoming() - 1 ? available & 4 : 0) : available;
               }

               if ((mask & ~available) != 0) {
                  throw new IllegalArgumentException("单边路口只允许主路直行/右转、支路右转");
               }

               for (int ti = 0; ti < 4; ti++) {
                  if ((mask & JunctionSpec.TURNS[ti]) != 0) {
                     int target = JunctionSpec.TURNS[ti] == 1 ? source.outgoing() : (i == 2 ? source.outgoing() : 2);
                     if (l.targets().get(ti) >= 0 && l.targets().get(ti) != target) {
                        throw new IllegalArgumentException("单边路口不能映射到对向车道");
                     }

                     l = l.target(ti, target, l.targetLanes().get(ti));
                  }
               }

               lanes.add(l.mask(mask));
            }

            arms.add((i < 2 ? a.crossing(false) : a).lanes(lanes));
         }

         return s.arms(arms).oneSide(null);
      } else {
         throw new IllegalArgumentException("单边路口使用右侧通行的三向连接，不支持环岛或额外独立右转道");
      }
   }

   private static OneSidedJunction.Masks masks(OneSidedJunction.Source source) {
      return MASKS.get(source, OneSidedJunction::makeMasks);
   }

   private static double border(OneSidedJunction.Source source, RoadGeometry.Sample at) {
      RoadProfile.Layout layout = RoadProfile.layout(source.road(), at);
      return layout.medianCenter() + (double)source.side() * (layout.median() / 2.0 + 0.16);
   }

   private static OneSidedJunction.Masks makeMasks(OneSidedJunction.Source source) {
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>(source.road().samples());
      samples.add(RoadSlicing.at(source.road(), source.from()));
      samples.add(RoadSlicing.at(source.road(), source.to()));
      samples.sort(Comparator.comparingDouble(RoadGeometry.Sample::distance));
      ArrayList<List<RoadGeometry.V>> edited = new ArrayList<>();
      ArrayList<List<RoadGeometry.V>> retained = new ArrayList<>();

      for (int i = 1; i < samples.size(); i++) {
         RoadGeometry.Sample a = samples.get(i - 1);
         RoadGeometry.Sample b = samples.get(i);
         if (!(b.distance() - a.distance() < 1.0E-8)) {
            boolean inside = (a.distance() + b.distance()) / 2.0 > source.from() && (a.distance() + b.distance()) / 2.0 < source.to();
            double edgeA = border(source, a);
            double edgeB = border(source, b);
            double extent = 256.0;
            if (inside) {
               edited.add(quad(a, b, edgeA, (double)source.side() * extent, edgeB, (double)source.side() * extent));
               retained.add(quad(a, b, (double)(-source.side()) * extent, edgeA, (double)(-source.side()) * extent, edgeB));
            } else {
               retained.add(quad(a, b, -extent, extent, -extent, extent));
            }
         }
      }

      return new OneSidedJunction.Masks(new RoadSurface.PaintMask(edited), new RoadSurface.PaintMask(retained));
   }

   private static List<RoadGeometry.V> quad(RoadGeometry.Sample a, RoadGeometry.Sample b, double loA, double hiA, double loB, double hiB) {
      return List.of(a.at(loA, 0.0), a.at(hiA, 0.0), b.at(hiB, 0.0), b.at(loB, 0.0));
   }

   private static List<RoadSurface.Face> paint(List<RoadSurface.Face> faces, RoadSurface.PaintMask mask) {
      ArrayList<RoadSurface.Face> out = new ArrayList<>();

      for (RoadSurface.Face face : faces) {
         for (List<RoadGeometry.V> p : mask.clipXZ(face.points())) {
            out.add(new RoadSurface.Face(p, face.color(), face.emissive(), face.texture()));
         }
      }

      return List.copyOf(out);
   }

   private static RoadGeometry.Mesh deck(RoadGeometry.Mesh source, RoadSurface.PaintMask mask) {
      ArrayList<RoadSlicing.Patch> patches = new ArrayList<>();
      List<RoadGeometry.Sample> samples = source.samples();

      for (int i = 1; i < samples.size(); i++) {
         RoadGeometry.Sample a = samples.get(i - 1);
         RoadGeometry.Sample b = samples.get(i);
         ArrayList<LaneDeck.Strip> strips = new ArrayList<>();

         for (LaneDeck.Strip strip : LaneDeck.strips(source, a, b)) {
            for (List<RoadGeometry.V> tri : List.of(List.of(strip.al(), strip.ar(), strip.br()), List.of(strip.al(), strip.br(), strip.bl()))) {
               for (List<RoadGeometry.V> polygon : mask.clipXZ(tri)) {
                  for (int k = 1; k < polygon.size() - 1; k++) {
                     RoadGeometry.V x = polygon.get(0);
                     RoadGeometry.V y = polygon.get(k);
                     RoadGeometry.V z = polygon.get(k + 1);
                     if (RoadSurface.area(List.of(x, y, z)) > 1.0E-9) {
                        strips.add(new LaneDeck.Strip(x, y, z, z, false, false));
                     }
                  }
               }
            }
         }

         patches.add(new RoadSlicing.Patch(a.distance(), b.distance(), strips, true));
      }

      return RoadSlicing.attach(source, new RoadSlicing.Geometry(patches, true));
   }

   private static List<RoadStructures.Part> parts(List<RoadStructures.Part> parts, RoadSurface.PaintMask mask) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      HashMap<RoadGeometry.V, Boolean> lamps = new HashMap<>();

      for (RoadStructures.Part p : parts) {
         RoadGeometry.V foot = RoadStreetscape.lampFoot(p);
         if (foot == null) {
            List<List<RoadGeometry.V>> polygons = mask.clipXZ(p.base());
            double area = polygons.stream().mapToDouble(RoadSurface::area).sum();
            if (Math.abs(area - RoadSurface.area(p.base())) < 1.0E-7) {
               out.add(p);
            } else if (!(area < 1.0E-9)) {
               boolean longitudinal = Set.of("sr:cycle_planter", "sr:planting_soil", "sr:cycle_wall", "sr:cycle_bypass").contains(p.model());
               if (!p.model().isEmpty() && !longitudinal || p.pier() || RoadSignals.signal(p) || RoadPoleModel.support(p)) {
                  throw new IllegalArgumentException("单边路口边界穿过不可拆构件（" + p.material() + ", " + p.model() + "），请调整接入位置");
               }

               for (List<RoadGeometry.V> polygon : polygons) {
                  for (int k = 1; k < polygon.size() - 1; k++) {
                     RoadGeometry.V a = polygon.get(0);
                     RoadGeometry.V b = polygon.get(k);
                     RoadGeometry.V c = polygon.get(k + 1);
                     if (!(RoadSurface.area(List.of(a, b, c)) < 1.0E-9)) {
                        out.add(
                           new RoadStructures.Part(
                              a.add(b).mul(0.5),
                              c,
                              Math.max(0.001, Math.min(8.0, a.distance(b))),
                              p.height(),
                              false,
                              p.material(),
                              a.sub(b).mul(0.5),
                              new RoadGeometry.V(0.0, 0.0, 0.0),
                              p.model()
                           )
                        );
                     }
                  }
               }
            }
         } else {
            boolean keep = lamps.computeIfAbsent(
               foot,
               v -> !mask.clipXZ(
                        List.of(
                           v.add(new RoadGeometry.V(-0.001, 0.0, -0.001)),
                           v.add(new RoadGeometry.V(0.001, 0.0, -0.001)),
                           v.add(new RoadGeometry.V(0.001, 0.0, 0.001)),
                           v.add(new RoadGeometry.V(-0.001, 0.0, 0.001))
                        )
                     )
                     .isEmpty()
            );
            if (keep) {
               out.add(p);
            }
         }
      }

      return List.copyOf(out);
   }

   public static List<RoadStructures.Part> generatedParts(OneSidedJunction.Source source, List<RoadStructures.Part> parts) {
      return parts(parts, masks(source).retained());
   }

   public static boolean retainedPiece(JunctionPlanner.Ref ref) {
      return ref.spec().oneSide() != null && ref.get().arm() == -3;
   }

   public static JunctionPlanner.Piece retained(OneSidedJunction.Source original, List<OneSidedJunction.Cut> cuts) {
      RoadGeometry.Mesh mesh = original.road();
      List<RoadSurface.Face> paint = RoadSurface.build(mesh, List.of(), List.of()).markings();
      List<RoadStructures.Part> structures = original.structures();

      for (OneSidedJunction.Cut cut : cuts) {
         OneSidedJunction.Source source = new OneSidedJunction.Source(original.road(), original.structures(), cut.from(), cut.to(), cut.side());
         RoadSurface.PaintMask mask = masks(source).edited();
         mesh = deck(mesh, mask);
         paint = paint(paint, mask);
         structures = parts(structures, mask);
      }

      return new JunctionPlanner.Piece(mesh, paint, structures, -3);
   }

   public static JunctionPlanner.Plan apply(JunctionSpec spec, JunctionPlanner.Plan base) {
      OneSidedJunction.Source source = spec.oneSide();
      OneSidedJunction.Masks masks = masks(source);
      ArrayList<JunctionPlanner.Piece> pieces = new ArrayList<>();

      for (JunctionPlanner.Piece piece : base.pieces()) {
         pieces.add(
            new JunctionPlanner.Piece(
               deck(piece.mesh(), masks.retained()), paint(piece.paint(), masks.retained()), parts(piece.structures(), masks.retained()), piece.arm()
            )
         );
      }

      RoadGeometry.Mesh original = source.road();
      List<RoadSurface.Face> markings = RoadSurface.build(original, List.of(), List.of()).markings();
      pieces.add(new JunctionPlanner.Piece(deck(original, masks.edited()), paint(markings, masks.edited()), parts(source.structures(), masks.edited()), -3));
      LinkedHashMap<RoadSidewalks.Cell, RoadSidewalks.Config> walks = new LinkedHashMap<>();
      base.sidewalks()
         .forEach(
            (cell, config) -> {
               if (!masks.retained()
                  .clipXZ(
                     List.of(
                        new RoadGeometry.V((double)cell.x(), (double)cell.y(), (double)cell.z()),
                        new RoadGeometry.V((double)(cell.x() + 1), (double)cell.y(), (double)cell.z()),
                        new RoadGeometry.V((double)(cell.x() + 1), (double)cell.y(), (double)(cell.z() + 1)),
                        new RoadGeometry.V((double)cell.x(), (double)cell.y(), (double)(cell.z() + 1))
                     )
                  )
                  .isEmpty()) {
                  walks.put(cell, config);
               }
            }
         );
      return new JunctionPlanner.Plan(List.copyOf(pieces), base.movements(), base.boundary(), Map.copyOf(walks), base.signals(), base.heads());
   }

   private OneSidedJunction() {
   }

   public static record Cut(double from, double to, int side) {
   }

   private static record Masks(RoadSurface.PaintMask edited, RoadSurface.PaintMask retained) {
   }

   public static record Source(RoadGeometry.Mesh road, List<RoadStructures.Part> structures, double from, double to, int side) {
      public Source(RoadGeometry.Mesh road, List<RoadStructures.Part> structures, double from, double to, int side) {
         structures = List.copyOf(structures);
         road = RoadSlicing.attach(
            RoadSlicing.mesh(road.samples(), road.settings()), road.slicing() == null ? new RoadSlicing.Geometry(List.of()) : road.slicing()
         );
         if (!road.closed()
            && RoadProfile.catalog(road.settings().style()).twoWay()
            && RoadGeometry.finite(from, to)
            && !(from < 0.0)
            && !(to > road.length())
            && !(to - from < 16.0)
            && Math.abs(side) == 1
            && structures.size() <= 50000) {
            this.road = road;
            this.structures = structures;
            this.from = from;
            this.to = to;
            this.side = side;
         } else {
            throw new IllegalArgumentException("单边路口主路范围无效");
         }
      }

      public int incoming() {
         return this.side > 0 ? 0 : 1;
      }

      public int outgoing() {
         return 1 - this.incoming();
      }
   }
}
