package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class RoadRenderMesh {
   private static final ThreadLocal<IdentityHashMap<RoadGeometry.Mesh, RoadGeometry.Mesh>> PAVEMENTS = ThreadLocal.withInitial(IdentityHashMap::new);

   public static int vertexCount(RoadSurface.Face face) {
      return face.points().size() == 4 ? 4 : 4 * (face.points().size() - 2);
   }

   public static RoadGeometry.Mesh simplify(RoadGeometry.Mesh mesh) {
      return simplify(mesh, 3.0, 0.025);
   }

   private static boolean exactCrossSections(RoadGeometry.Mesh mesh) {
      return LaneDeck.hasOpenings(mesh);
   }

   private static NavigableSet<Double> protectedStations(RoadGeometry.Mesh mesh) {
      TreeSet<Double> stations = new TreeSet<>();

      for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
         if (cut.rectangular()) {
            for (double edge : new double[]{cut.begin(), cut.end()}) {
               for (RoadGeometry.Sample sample : mesh.samples()) {
                  if (Math.abs(sample.distance() - edge) < 3.0E-5) {
                     stations.add(sample.distance());
                  }
               }
            }
         }
      }

      return stations;
   }

   public static RoadGeometry.Mesh pavement(RoadGeometry.Mesh mesh) {
      IdentityHashMap<RoadGeometry.Mesh, RoadGeometry.Mesh> cache = PAVEMENTS.get();
      RoadGeometry.Mesh found = cache.get(mesh);
      if (found != null) {
         return found;
      } else {
         RoadGeometry.Mesh reduced = pavementUncached(mesh);
         if (cache.size() >= 128) {
            cache.clear();
         }

         cache.put(mesh, reduced);
         return reduced;
      }
   }

   private static RoadGeometry.Mesh pavementUncached(RoadGeometry.Mesh mesh) {
      if (mesh.slicing() == null && !exactCrossSections(mesh)) {
         List<RoadGeometry.Sample> samples = mesh.samples();
         NavigableSet<Double> protectedAt = protectedStations(mesh);
         List<RoadGeometry.Sample> out = new ArrayList<>();
         out.add(mesh.first());
         int from = 0;

         while (from < samples.size() - 1) {
            int end = from + 1;
            Double boundary = protectedAt.higher(samples.get(from).distance() + 1.0E-8);

            for (int candidate = end + 1; candidate < samples.size(); end = candidate++) {
               RoadGeometry.Sample a = samples.get(from);
               RoadGeometry.Sample b = samples.get(candidate);
               if (b.distance() - a.distance() > 24.0 || boundary != null && b.distance() > boundary + 1.0E-8) {
                  break;
               }

               boolean exact = true;

               for (int k = from + 1; k < candidate && exact; k++) {
                  RoadGeometry.Sample m = samples.get(k);
                  double t = (m.distance() - a.distance()) / (b.distance() - a.distance());

                  for (int side : new int[]{-1, 1}) {
                     if (a.at(LaneDeck.edge(mesh, a, side), 0.0)
                           .mul(1.0 - t)
                           .add(b.at(LaneDeck.edge(mesh, b, side), 0.0).mul(t))
                           .distance(m.at(LaneDeck.edge(mesh, m, side), 0.0))
                        > 1.0E-7) {
                        exact = false;
                        break;
                     }
                  }
               }

               if (!exact) {
                  break;
               }
            }

            out.add(samples.get(end));
            from = end;
         }

         return new RoadGeometry.Mesh(
            List.copyOf(out), mesh.settings(), mesh.min(), mesh.max(), mesh.length(), mesh.closed(), mesh.controlPoint(), mesh.controls(), mesh.reference()
         );
      } else {
         return mesh;
      }
   }

   private static RoadGeometry.Mesh simplify(RoadGeometry.Mesh mesh, double spacing, double error) {
      if (mesh.slicing() == null && !exactCrossSections(mesh)) {
         List<RoadGeometry.Sample> out = new ArrayList<>();
         out.add(mesh.first());
         NavigableSet<Double> protectedAt = protectedStations(mesh);
         double d = 0.0;

         while (d < mesh.length() - 1.0E-7) {
            RoadPlanningBudget.check();
            double next = Math.min(mesh.length(), (Math.floor((d + 1.0E-6) / spacing) + 1.0) * spacing);
            Double boundary = protectedAt.higher(d + 1.0E-8);
            if (boundary != null) {
               next = Math.min(next, boundary);
            }

            RoadGeometry.Sample a = out.get(out.size() - 1);

            RoadGeometry.Sample b;
            for (b = RoadStructures.sample(mesh, next); next - d > 0.45; b = RoadStructures.sample(mesh, next)) {
               boolean good = true;

               for (double f : new double[]{0.25, 0.5, 0.75}) {
                  RoadGeometry.Sample m = RoadStructures.sample(mesh, d + (next - d) * f);

                  for (int side : new int[]{-1, 1}) {
                     RoadGeometry.V linear = a.at(LaneDeck.edge(mesh, a, side), 0.0).mul(1.0 - f).add(b.at(LaneDeck.edge(mesh, b, side), 0.0).mul(f));
                     if (linear.distance(m.at(LaneDeck.edge(mesh, m, side), 0.0)) > error) {
                        good = false;
                        break;
                     }
                  }
               }

               if (good) {
                  break;
               }

               next = (d + next) / 2.0;
            }

            out.add(b);
            d = next;
         }

         return new RoadGeometry.Mesh(
            List.copyOf(out), mesh.settings(), mesh.min(), mesh.max(), mesh.length(), mesh.closed(), mesh.controlPoint(), mesh.controls(), mesh.reference()
         );
      } else {
         return mesh;
      }
   }

   public static Map<RoadRenderMesh.Section, RoadRenderMesh.Layers> layers(RoadSurface.Geometry geometry, List<RoadStructures.Part> structures) {
      return layersFromFaces(geometry, structureFaces(structures, false), structureFaces(structures, true));
   }

   public static Map<RoadRenderMesh.Section, RoadRenderMesh.Layers> layersFromFaces(
      RoadSurface.Geometry geometry, List<RoadSurface.Face> near, List<RoadSurface.Face> far
   ) {
      Map<RoadRenderMesh.Section, List<RoadSurface.Face>> pavement = new HashMap<>();
      Map<RoadRenderMesh.Section, List<RoadSurface.Face>> paint = new HashMap<>();
      Map<RoadRenderMesh.Section, List<RoadSurface.Face>> detail = new HashMap<>();
      Map<RoadRenderMesh.Section, List<RoadSurface.Face>> distant = new HashMap<>();

      for (RoadSurface.Face f : geometry.pavement()) {
         if (f.texture() == RoadSurface.Texture.PLAIN) {
            add(pavement, f.color() == 14474460 ? upward(f) : f);
         } else {
            add(detail, f);
            add(distant, f);
         }
      }

      for (RoadSurface.Face fx : geometry.markings()) {
         add(paint, upward(fx));
      }

      for (RoadSurface.Face fx : near) {
         add(detail, fx);
      }

      for (RoadSurface.Face fx : far) {
         add(distant, fx);
      }

      Set<RoadRenderMesh.Section> keys = new HashSet<>(pavement.keySet());
      keys.addAll(paint.keySet());
      keys.addAll(detail.keySet());
      keys.addAll(distant.keySet());
      Map<RoadRenderMesh.Section, RoadRenderMesh.Layers> out = new HashMap<>();

      for (RoadRenderMesh.Section k : keys) {
         List<RoadSurface.Face> lines = List.copyOf(paint.getOrDefault(k, List.of()));
         out.put(
            k,
            new RoadRenderMesh.Layers(
               new RoadRenderMesh.Piece(List.of(), List.copyOf(detail.getOrDefault(k, List.of())), List.copyOf(distant.getOrDefault(k, List.of()))),
               new RoadRenderMesh.Piece(List.copyOf(pavement.getOrDefault(k, List.of())), lines, lines)
            )
         );
      }

      return out;
   }

   public static Map<RoadRenderMesh.Section, RoadRenderMesh.Piece> sections(RoadSurface.Geometry geometry, List<RoadStructures.Part> structures) {
      Map<RoadRenderMesh.Section, RoadRenderMesh.Piece> out = new HashMap<>();
      layers(geometry, structures).forEach((k, l) -> {
         ArrayList<RoadSurface.Face> near = new ArrayList<>(l.surface().detail());
         near.addAll(l.shared().detail());
         ArrayList<RoadSurface.Face> far = new ArrayList<>(l.surface().distant());
         far.addAll(l.shared().distant());
         out.put(k, new RoadRenderMesh.Piece(l.surface().pavement(), List.copyOf(near), List.copyOf(far)));
      });
      return out;
   }

   public static RoadSurface.Face upward(RoadSurface.Face f) {
      if (RoadLighting.normal(f).y() >= 0.0) {
         return f;
      } else {
         ArrayList<RoadGeometry.V> points = new ArrayList<>(f.points());
         Collections.reverse(points);
         ArrayList<RoadSurface.UV> uv = new ArrayList<>(f.uv());
         Collections.reverse(uv);
         return new RoadSurface.Face(points, f.color(), f.emissive(), f.texture(), uv);
      }
   }

   public static List<RoadStructures.Part> compact(List<RoadStructures.Part> parts, boolean distant) {
      List<RoadStructures.Part> out = new ArrayList<>();
      Map<RoadRenderMesh.End, Integer> ends = new HashMap<>();
      Map<Integer, List<RoadStructures.Part>> runs = new HashMap<>();

      for (RoadStructures.Part p : parts) {
         RoadPlanningBudget.check();
         if (!p.model().equals("sr:railway_fence")
            && !p.model().equals("sr:railway_crossing_sign")
            && !TollModels.supports(p)
            && p.heights() == null
            && p.material() != RoadStructures.Material.NOISE_BARRIER
            && (!LaneClosureLandscape.paved(p) || RoadsideBypass.paved(p))) {
            double length = p.b().sub(p.a()).horizontalLength();
            if (!distant || p.material() != RoadStructures.Material.TACTILE || !(p.width() < 0.1) || !(p.height() < 0.025)) {
               RoadRenderMesh.End start = new RoadRenderMesh.End(p.a(), p);
               Integer at = ends.get(start);
               if (!p.pier() && length > 1.0E-6 && at != null) {
                  RoadStructures.Part prev = out.get(at);
                  List<RoadStructures.Part> run = runs.get(at);
                  if (canJoin(prev, p, run, distant)) {
                     RoadStructures.Part merged = new RoadStructures.Part(
                        prev.a(), p.b(), p.width(), p.height(), false, p.material(), prev.frameA(), p.frameB(), p.model()
                     );
                     out.set(at, merged);
                     run.add(p);
                     ends.remove(start);
                     ends.put(new RoadRenderMesh.End(p.b(), p), at);
                     continue;
                  }
               }

               if (!p.pier() && length > 1.0E-6) {
                  ends.put(new RoadRenderMesh.End(p.b(), p), out.size());
               }

               if (!p.pier() && length > 1.0E-6) {
                  runs.put(out.size(), new ArrayList<>(List.of(p)));
               }

               out.add(p);
            }
         } else {
            out.add(p);
         }
      }

      return out;
   }

   private static boolean canJoin(RoadStructures.Part first, RoadStructures.Part next, List<RoadStructures.Part> run, boolean distant) {
      if (first.heights() == null
         && next.heights() == null
         && run != null
         && first.model().equals(next.model())
         && !(first.b().distance(next.a()) > 1.0E-6)
         && !(first.a().distance(next.b()) > (double)(distant ? 64 : 8))) {
         RoadGeometry.V before = run.get(run.size() - 1).b().sub(run.get(run.size() - 1).a());
         RoadGeometry.V after = next.b().sub(next.a());
         if (before.horizontalUnit().dot(after.horizontalUnit()) < 0.985) {
            return false;
         } else {
            List<RoadGeometry.V> end = run.get(run.size() - 1).base();
            List<RoadGeometry.V> start = next.base();
            if (!(end.get(3).distance(start.get(0)) > 1.0E-6) && !(end.get(2).distance(start.get(1)) > 1.0E-6)) {
               RoadStructures.Part merged = new RoadStructures.Part(
                  first.a(), next.b(), first.width(), first.height(), false, first.material(), first.frameA(), next.frameB()
               );
               List<RoadGeometry.V> corners = merged.base();
               RoadGeometry.V chord = next.b().sub(first.a());
               double squared = chord.dot(chord);
               double error = first.material() != RoadStructures.Material.TUNNEL
                     && first.material() != RoadStructures.Material.LAMP
                     && first.material() != RoadStructures.Material.SIGN_WHITE
                     && (!first.model().startsWith("sr:cycle_") || first.model().equals("sr:cycle_planter"))
                  ? (distant ? 0.04 : 0.0125)
                  : 1.0E-7;

               for (RoadStructures.Part part : run) {
                  if (!within(part, corners, first.a(), chord, squared, error)) {
                     return false;
                  }
               }

               return within(next, corners, first.a(), chord, squared, error);
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   private static boolean within(
      RoadStructures.Part part, List<RoadGeometry.V> corners, RoadGeometry.V origin, RoadGeometry.V chord, double squared, double error
   ) {
      List<RoadGeometry.V> base = part.base();
      double a = part.a().sub(origin).dot(chord) / squared;
      double b = part.b().sub(origin).dot(chord) / squared;
      return lerp(corners.get(0), corners.get(3), a).distance(base.get(0)) <= error
         && lerp(corners.get(1), corners.get(2), a).distance(base.get(1)) <= error
         && lerp(corners.get(0), corners.get(3), b).distance(base.get(3)) <= error
         && lerp(corners.get(1), corners.get(2), b).distance(base.get(2)) <= error;
   }

   private static RoadGeometry.V lerp(RoadGeometry.V a, RoadGeometry.V b, double t) {
      return a.mul(1.0 - t).add(b.mul(t));
   }

   public static List<RoadSurface.Face> structureFaces(List<RoadStructures.Part> parts, boolean distant) {
      List<RoadStructures.Part> compacted = compact(parts, distant);
      return structureFacesCompacted(compacted, compacted, distant);
   }

   public static List<RoadSurface.Face> structureFacesCompacted(List<RoadStructures.Part> compacted, List<RoadStructures.Part> context, boolean distant) {
      return structureFacesCompacted(compacted, new RoadRenderMesh.StructureContext(context), distant);
   }

   public static List<RoadSurface.Face> structureFacesCompacted(List<RoadStructures.Part> compacted, RoadRenderMesh.StructureContext context, boolean distant) {
      List<RoadSurface.Face> faces = new ArrayList<>();
      Map<RoadRenderMesh.Cap, Integer> caps = new HashMap<>();
      IdentityHashMap<RoadSurface.Face, Double> biasByFace = new IdentityHashMap<>();
      SidewalkFaces walks = context.walks;

      for (RoadStructures.Part part : compacted) {
         RoadPlanningBudget.check();
         List<RoadSurface.Face> solid = part.material() == RoadStructures.Material.NOISE_BARRIER
            ? RoadNoiseModel.faces(part, distant)
            : (distant && RoadSignals.signal(part) ? signalHull(part) : part.faces());
         double bias = part.material() == RoadStructures.Material.TUNNEL ? 0.006 : (part.model().equals("sr:auto_light") ? 0.009 : 0.0);

         for (RoadSurface.Face face : solid) {
            if (bias > 0.0) {
               biasByFace.put(face, bias);
            }
         }

         for (int i = 0; i < solid.size(); i++) {
            if (i != 0 || !LaneClosureLandscape.paved(part)) {
               RoadSurface.Face facex = solid.get(i);
               if (i == 0 && part.material() == RoadStructures.Material.TACTILE) {
                  facex = new RoadSurface.Face(facex.points(), facex.color(), facex.emissive(), RoadSurface.Texture.WALK_TACTILE, facex.uv());
               }

               if (SidewalkFaces.walk(part) && i >= 2) {
                  faces.addAll(walks.exposed(part, facex));
               } else {
                  if (!part.model().equals("sr:railway_fence")
                     && !part.pier()
                     && !TollModels.supports(part)
                     && part.material() != RoadStructures.Material.NOISE_BARRIER
                     && !RoadSignals.signal(part)
                     && !RoadPoleModel.support(part)
                     && facex.texture() != RoadSurface.Texture.LEAVES
                     && Math.abs(RoadLighting.normal(facex).y()) < 0.5) {
                     RoadRenderMesh.Cap key = new RoadRenderMesh.Cap(facex);
                     Integer opposite = caps.remove(key);
                     if (opposite != null) {
                        List<RoadGeometry.V> previous = faces.get(opposite).points();
                        RoadGeometry.V a = previous.get(1).sub(previous.get(0));
                        RoadGeometry.V b = previous.get(2).sub(previous.get(0));
                        RoadGeometry.V c = facex.points().get(1).sub(facex.points().get(0));
                        RoadGeometry.V d = facex.points().get(2).sub(facex.points().get(0));
                        RoadGeometry.V n = new RoadGeometry.V(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
                        RoadGeometry.V m = new RoadGeometry.V(c.y() * d.z() - c.z() * d.y(), c.z() * d.x() - c.x() * d.z(), c.x() * d.y() - c.y() * d.x());
                        if (n.dot(m) < 0.0) {
                           faces.set(opposite, null);
                        } else {
                           caps.put(key, opposite);
                        }
                        continue;
                     }

                     caps.put(key, faces.size());
                  }

                  faces.add(facex);
               }
            }
         }
      }

      faces.removeIf(Objects::isNull);

      for (double bias : new double[]{0.006, 0.009}) {
         List<RoadSurface.Face> group = faces.stream().filter(f -> biasByFace.getOrDefault(f, Double.valueOf(0.0)) == bias).toList();
         List<RoadSurface.Face> biased = closedBias(group, bias);
         IdentityHashMap<RoadSurface.Face, RoadSurface.Face> replacements = new IdentityHashMap<>();

         for (int ix = 0; ix < group.size(); ix++) {
            replacements.put(group.get(ix), biased.get(ix));
         }

         for (int ix = 0; ix < faces.size(); ix++) {
            faces.set(ix, replacements.getOrDefault(faces.get(ix), faces.get(ix)));
         }
      }

      return faces;
   }

   private static List<RoadSurface.Face> closedBias(List<RoadSurface.Face> faces, double amount) {
      HashMap<RoadRenderMesh.Point, List<RoadGeometry.V>> normals = new HashMap<>();

      for (RoadSurface.Face face : faces) {
         RoadGeometry.V normal = RoadLighting.normal(face);

         for (RoadGeometry.V p : face.points()) {
            List<RoadGeometry.V> at = normals.computeIfAbsent(new RoadRenderMesh.Point(p), k -> new ArrayList<>());
            if (at.stream().noneMatch(n -> n.dot(normal) > 0.999999)) {
               at.add(normal);
            }
         }
      }

      HashMap<RoadRenderMesh.Point, RoadGeometry.V> corners = new HashMap<>();
      normals.forEach((px, ns) -> {
         RoadGeometry.V sum = new RoadGeometry.V(0.0, 0.0, 0.0);

         for (RoadGeometry.V n : ns) {
            sum = sum.add(n);
         }

         double limit = Double.POSITIVE_INFINITY;

         for (RoadGeometry.V n : ns) {
            limit = Math.min(limit, sum.dot(n));
         }

         RoadGeometry.V delta = limit > 1.0E-6 ? sum.mul(amount / limit) : new RoadGeometry.V(0.0, 0.0, 0.0);
         double length = delta.distance(new RoadGeometry.V(0.0, 0.0, 0.0));
         if (length > amount * 4.0) {
            delta = delta.mul(amount * 4.0 / length);
         }

         corners.put(px, delta);
      });
      return faces.stream()
         .map(
            f -> new RoadSurface.Face(
                  f.points().stream().map(px -> px.add(corners.get(new RoadRenderMesh.Point(px)))).toList(), f.color(), f.emissive(), f.texture(), f.uv()
               )
         )
         .toList();
   }

   private static List<RoadSurface.Face> signalHull(RoadStructures.Part part) {
      RoadGeometry.V front = part.b().sub(part.a()).horizontalUnit();
      RoadGeometry.V side = front.left();
      List<RoadGeometry.V> points = part.faces().stream().flatMap(f -> f.points().stream()).toList();
      double x0 = Double.POSITIVE_INFINITY;
      double x1 = -x0;
      double y0 = x0;
      double y1 = -x0;
      double z0 = x0;
      double z1 = -x0;

      for (RoadGeometry.V p : points) {
         RoadGeometry.V q = p.sub(part.a());
         double x = q.dot(side);
         double z = q.dot(front);
         x0 = Math.min(x0, x);
         x1 = Math.max(x1, x);
         y0 = Math.min(y0, q.y());
         y1 = Math.max(y1, q.y());
         z0 = Math.min(z0, z);
         z1 = Math.max(z1, z);
      }

      double lens = RoadSignalModel.faces(part, true, 0)
         .stream()
         .flatMap(f -> f.points().stream())
         .mapToDouble(v -> v.sub(part.a()).dot(front))
         .min()
         .orElse(z1);
      z1 = Math.max(z0 + 0.02, Math.min(z1, lens - 0.025));
      RoadGeometry.V a = part.a().add(side.mul((x0 + x1) / 2.0)).add(front.mul(z0)).add(new RoadGeometry.V(0.0, y0, 0.0));
      RoadGeometry.V b = part.a().add(side.mul((x0 + x1) / 2.0)).add(front.mul(z1)).add(new RoadGeometry.V(0.0, y0, 0.0));
      return new RoadStructures.Part(a, b, x1 - x0, y1 - y0, false, RoadStructures.Material.DARK_STEEL).faces();
   }

   private static void add(Map<RoadRenderMesh.Section, List<RoadSurface.Face>> map, RoadSurface.Face f) {
      RoadPlanningBudget.check();
      double minX = f.points().stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow();
      double maxX = f.points().stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow();
      double minZ = f.points().stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow();
      double maxZ = f.points().stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow();

      for (int x = (int)Math.floor(minX / 64.0); x <= (int)Math.floor((maxX - 1.0E-9) / 64.0) || x == (int)Math.floor(minX / 64.0); x++) {
         for (int z = (int)Math.floor(minZ / 64.0); z <= (int)Math.floor((maxZ - 1.0E-9) / 64.0) || z == (int)Math.floor(minZ / 64.0); z++) {
            ArrayList<RoadRenderMesh.CutVertex> v = new ArrayList<>();

            for (int i = 0; i < f.points().size(); i++) {
               v.add(new RoadRenderMesh.CutVertex(f.points().get(i), f.uv().isEmpty() ? null : f.uv().get(i)));
            }

            List<RoadRenderMesh.CutVertex> cut = clip(v, 0, (double)(x * 64), true);
            cut = clip(cut, 0, (double)((x + 1) * 64), false);
            cut = clip(cut, 2, (double)(z * 64), true);
            cut = clip(cut, 2, (double)((z + 1) * 64), false);
            if (cut.size() >= 3) {
               RoadGeometry.V o = cut.get(0).p();
               double area = 0.0;

               for (int i = 1; i + 1 < cut.size(); i++) {
                  RoadGeometry.V a = cut.get(i).p().sub(o);
                  RoadGeometry.V b = cut.get(i + 1).p().sub(o);
                  area += new RoadGeometry.V(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x())
                     .distance(new RoadGeometry.V(0.0, 0.0, 0.0));
               }

               if (!(area < 1.0E-12)) {
                  map.computeIfAbsent(new RoadRenderMesh.Section(x, 0, z), k -> new ArrayList<>())
                     .add(
                        new RoadSurface.Face(
                           cut.stream().map(RoadRenderMesh.CutVertex::p).toList(),
                           f.color(),
                           f.emissive(),
                           f.texture(),
                           f.uv().isEmpty() ? List.of() : cut.stream().map(RoadRenderMesh.CutVertex::uv).toList()
                        )
                     );
               }
            }
         }
      }
   }

   private static List<RoadRenderMesh.CutVertex> clip(List<RoadRenderMesh.CutVertex> in, int axis, double edge, boolean above) {
      ArrayList<RoadRenderMesh.CutVertex> out = new ArrayList<>();
      if (in.isEmpty()) {
         return out;
      } else {
         RoadRenderMesh.CutVertex a = in.get(in.size() - 1);
         double da = (axis == 0 ? a.p().x() : a.p().z()) - edge;

         for (RoadRenderMesh.CutVertex b : in) {
            double db = (axis == 0 ? b.p().x() : b.p().z()) - edge;
            boolean ai = above ? da >= -1.0E-9 : da <= 1.0E-9;
            boolean bi = above ? db >= -1.0E-9 : db <= 1.0E-9;
            if (ai != bi) {
               double t = da / (da - db);
               RoadGeometry.V p = a.p().mul(1.0 - t).add(b.p().mul(t));
               RoadSurface.UV uv = a.uv() == null ? null : new RoadSurface.UV(a.uv().u() * (1.0 - t) + b.uv().u() * t, a.uv().v() * (1.0 - t) + b.uv().v() * t);
               out.add(new RoadRenderMesh.CutVertex(p, uv));
            }

            if (bi) {
               out.add(b);
            }

            a = b;
            da = db;
         }

         return out;
      }
   }

   private RoadRenderMesh() {
   }

   private static record Cap(List<RoadRenderMesh.Point> points, RoadSurface.Texture texture, int color) {
      Cap(RoadSurface.Face face) {
         this(face.points().stream().map(RoadRenderMesh.Point::new).sorted().toList(), face.texture(), face.color());
      }
   }

   private static record CutVertex(RoadGeometry.V p, RoadSurface.UV uv) {
   }

   private static record End(long x, long y, long z, double width, double height, RoadStructures.Material material) {
      End(RoadGeometry.V v, RoadStructures.Part p) {
         this(Math.round(v.x() * 100000.0), Math.round(v.y() * 100000.0), Math.round(v.z() * 100000.0), p.width(), p.height(), p.material());
      }
   }

   public static record Layers(RoadRenderMesh.Piece shared, RoadRenderMesh.Piece surface) {
   }

   static final class PavementScope implements AutoCloseable {
      private final IdentityHashMap<RoadGeometry.Mesh, RoadGeometry.Mesh> previous = RoadRenderMesh.PAVEMENTS.get();

      PavementScope() {
         RoadRenderMesh.PAVEMENTS.set(new IdentityHashMap<>());
      }

      @Override
      public void close() {
         RoadRenderMesh.PAVEMENTS.set(this.previous);
      }
   }

   public static record Piece(List<RoadSurface.Face> pavement, List<RoadSurface.Face> detail, List<RoadSurface.Face> distant) {
   }

   private static record Point(long x, long y, long z) implements Comparable<RoadRenderMesh.Point> {
      Point(RoadGeometry.V p) {
         this(Math.round(p.x() * 100000.0), Math.round(p.y() * 100000.0), Math.round(p.z() * 100000.0));
      }

      public int compareTo(RoadRenderMesh.Point p) {
         int c = Long.compare(this.x, p.x);
         if (c == 0) {
            c = Long.compare(this.y, p.y);
         }

         return c == 0 ? Long.compare(this.z, p.z) : c;
      }
   }

   public static record Section(int x, int y, int z) {
      public RoadGeometry.V origin() {
         return new RoadGeometry.V((double)this.x * 64.0, (double)this.y * 64.0, (double)this.z * 64.0);
      }

      public static RoadRenderMesh.Section of(RoadGeometry.V p) {
         return new RoadRenderMesh.Section((int)Math.floor(p.x() / 64.0), 0, (int)Math.floor(p.z() / 64.0));
      }
   }

   public static final class StructureContext {
      private final SidewalkFaces walks;

      public StructureContext(List<RoadStructures.Part> context) {
         this.walks = new SidewalkFaces(context);
      }

      public long bytes() {
         return this.walks.bytes();
      }
   }
}
