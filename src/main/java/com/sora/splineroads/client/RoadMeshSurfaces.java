package com.sora.splineroads.client;

import com.sora.splineroads.core.JunctionSlips;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadRenderMesh;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.core.RoadsideBypass;
import com.sora.splineroads.world.RoadIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RoadMeshSurfaces {
   static RoadMeshSurfaces.Pair prepare(RoadIndex.Built road, List<RoadIndex.Built> joined, List<RoadIndex.Built> higher) {
      return prepare(road, joined, higher, true);
   }

   static RoadMeshSurfaces.Pair prepare(RoadIndex.Built road, List<RoadIndex.Built> joined, List<RoadIndex.Built> higher, boolean needTerrain) {
      RoadGeometry.Mesh reduced = road.renderMesh();
      List<RoadGeometry.Mesh> joinedRender = joined.stream().map(RoadIndex.Built::renderMesh).toList();
      List<RoadGeometry.Mesh> highRender = higher.stream().map(RoadIndex.Built::renderMesh).toList();
      List<RoadGeometry.Mesh> joinedExact = joined.stream().map(r -> r.mesh).toList();
      RoadSurface.Geometry surface = build(road, reduced, joinedRender, highRender, joinedExact);
      boolean same = reduced == road.mesh && joined.stream().allMatch(r -> r.renderMesh() == r.mesh) && higher.stream().allMatch(r -> r.renderMesh() == r.mesh);
      RoadSurface.Geometry exact = !needTerrain
         ? null
         : (same ? surface : build(road, road.mesh, joinedExact, higher.stream().map(r -> r.mesh).toList(), joinedExact));
      return new RoadMeshSurfaces.Pair(surface, walks(exact, road.record.structures()), same);
   }

   static RoadSurface.Geometry build(
      RoadIndex.Built road, RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> joined, List<RoadGeometry.Mesh> higher, List<RoadGeometry.Mesh> exactJoined
   ) {
      RoadSurface.Geometry surface;
      if (road.record.junction() != null) {
         surface = RoadSurface.buildCustom(mesh, higher, joined, road.record.junction().get().paint());
      } else if (road.record.toll() != null) {
         surface = RoadSurface.buildCustom(mesh, higher, joined, road.record.tollPlan().paint());
      } else {
         surface = RoadSurface.build(mesh, higher, joined, new RoadsideBypass(road.mesh, exactJoined));
      }

      ArrayList<RoadGeometry.Mesh> decks = new ArrayList<>();
      decks.add(mesh);
      decks.addAll(joined);
      surface = RoadSurface.closurePavement(surface, road.record.structures(), decks);
      return road.record.junction() != null && road.record.junction().geometryVersion() >= 48 && JunctionSlips.enabled(road.record.junction().spec())
         ? JunctionSlips.exposedSurface(surface, road.record.structures())
         : surface;
   }

   static List<RoadMeshSurfaces.Window> windows(RoadIndex.Built road) {
      RoadGeometry.Mesh mesh = road.mesh;
      return road.record.junction() == null && road.record.toll() == null
         ? windows(mesh)
         : List.of(new RoadMeshSurfaces.Window(0.0, mesh.length() + 1.0, mesh.min(), mesh.max()));
   }

   static List<RoadMeshSurfaces.Window> windows(RoadGeometry.Mesh mesh) {
      if (mesh.length() <= 128.0) {
         return List.of(new RoadMeshSurfaces.Window(0.0, mesh.length() + 1.0, mesh.min(), mesh.max()));
      } else {
         ArrayList<RoadMeshSurfaces.Window> windows = new ArrayList<>();
         List<RoadGeometry.Sample> source = mesh.samples();
         int cursor = 0;

         for (double from = 0.0; from < mesh.length(); from += 64.0) {
            double to = Math.min(mesh.length(), from + 64.0);
            double x0 = Double.POSITIVE_INFINITY;
            double z0 = x0;
            double x1 = -x0;
            double z1 = -x0;
            ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
            samples.add(RoadStructures.sample(mesh, from));
            samples.add(RoadStructures.sample(mesh, to));

            while (cursor < source.size() && source.get(cursor).distance() <= from) {
               cursor++;
            }

            while (cursor < source.size() && source.get(cursor).distance() < to) {
               samples.add(source.get(cursor++));
            }

            for (RoadGeometry.Sample p : samples) {
               double margin = p.halfWidth() + 32.0;
               x0 = Math.min(x0, p.center().x() - margin);
               z0 = Math.min(z0, p.center().z() - margin);
               x1 = Math.max(x1, p.center().x() + margin);
               z1 = Math.max(z1, p.center().z() + margin);
            }

            windows.add(
               new RoadMeshSurfaces.Window(
                  from, to == mesh.length() ? to + 1.0 : to, new RoadGeometry.V(x0, mesh.min().y(), z0), new RoadGeometry.V(x1, mesh.max().y(), z1)
               )
            );
         }

         return List.copyOf(windows);
      }
   }

   static List<RoadStructures.Part> parts(RoadIndex.Built road, RoadMeshSurfaces.Window window) {
      return window.from() == 0.0 && window.to() > road.mesh.length()
         ? RoadSignVisibility.parts(road.mesh, road.record.structures())
         : RoadSignVisibility.parts(road.mesh, road.record.structures())
            .stream()
            .filter(p -> window.includes(RoadQueries.horizontal(road.mesh, p.a().add(p.b()).mul(0.5)).sample().distance()))
            .toList();
   }

   static RoadMeshSurfaces.Pair prepareWindow(
      RoadIndex.Built road,
      List<RoadIndex.Built> joined,
      List<RoadIndex.Built> higher,
      RoadMeshSurfaces.Window window,
      boolean surfaceWanted,
      boolean terrainWanted
   ) {
      return new RoadMeshSurfaces.Context(road, joined, higher, List.of(window)).prepare(window, surfaceWanted, terrainWanted);
   }

   static RoadSurface.Geometry walks(RoadSurface.Geometry geometry, List<RoadStructures.Part> parts) {
      if (geometry == null) {
         return null;
      } else {
         ArrayList<RoadSurface.Face> tops = new ArrayList<>();

         for (RoadStructures.Part p : parts) {
            if (p.material().name().startsWith("WALK_") || p.material() == RoadStructures.Material.TACTILE) {
               RoadSurface.Face f = p.faces().get(0);
               tops.add(
                  p.material() == RoadStructures.Material.TACTILE
                     ? new RoadSurface.Face(f.points(), f.color(), f.emissive(), RoadSurface.Texture.WALK_TACTILE)
                     : f
               );
            }
         }

         if (tops.isEmpty()) {
            return geometry;
         } else {
            ArrayList<RoadSurface.Face> pavement = new ArrayList<>(geometry.pavement());
            pavement.addAll(tops);
            return new RoadSurface.Geometry(List.copyOf(pavement), geometry.markings());
         }
      }
   }

   private RoadMeshSurfaces() {
   }

   static final class Context {
      final RoadIndex.Built road;
      final List<RoadIndex.Built> joined;
      final List<RoadIndex.Built> higher;
      final List<RoadGeometry.Mesh> exactJoined;
      final Map<RoadMeshSurfaces.Window, List<RoadStructures.Part>> parts;
      RoadSurface.WindowContext surface;
      RoadSurface.WindowContext exact;

      Context(RoadIndex.Built road, List<RoadIndex.Built> joined, List<RoadIndex.Built> higher, List<RoadMeshSurfaces.Window> windows) {
         this.road = road;
         this.joined = List.copyOf(joined);
         this.higher = List.copyOf(higher);
         this.exactJoined = joined.stream().map(r -> r.mesh).toList();
         this.parts = RoadMeshSurfaces.Structures.partition(road, windows, road.record.structures());
      }

      RoadMeshSurfaces.Pair prepare(RoadMeshSurfaces.Window w, boolean surfaceWanted, boolean terrainWanted) {
         RoadGeometry.Mesh reduced = this.road.renderMesh();
         boolean same = reduced == this.road.mesh
            && this.joined.stream().allMatch(r -> r.renderMesh() == r.mesh)
            && this.higher.stream().allMatch(r -> r.renderMesh() == r.mesh);
         if (w.from() == 0.0 && w.to() > this.road.mesh.length()) {
            return this.prepareWhole(surfaceWanted, terrainWanted, same);
         } else {
            RoadSurface.Geometry a = null;
            RoadSurface.Geometry b = null;
            if (surfaceWanted) {
               if (this.surface == null) {
                  this.surface = new RoadSurface.WindowContext(
                     reduced,
                     this.higher.stream().map(RoadIndex.Built::renderMesh).toList(),
                     this.joined.stream().map(RoadIndex.Built::renderMesh).toList(),
                     new RoadsideBypass(this.road.mesh, this.exactJoined)
                  );
               }

               a = this.finish(this.surface.build(w.from(), w.to()), reduced, this.joined.stream().map(RoadIndex.Built::renderMesh).toList(), w);
            }

            if (terrainWanted) {
               if (same && a != null) {
                  b = a;
               } else {
                  if (this.exact == null) {
                     this.exact = same && this.surface != null
                        ? this.surface
                        : new RoadSurface.WindowContext(
                           this.road.mesh,
                           this.higher.stream().map(r -> r.mesh).toList(),
                           this.exactJoined,
                           new RoadsideBypass(this.road.mesh, this.exactJoined)
                        );
                  }

                  b = this.finish(this.exact.build(w.from(), w.to()), this.road.mesh, this.exactJoined, w);
               }
            }

            return new RoadMeshSurfaces.Pair(a, RoadMeshSurfaces.walks(b, this.parts.getOrDefault(w, List.of())), same && a != null && b != null);
         }
      }

      private RoadMeshSurfaces.Pair prepareWhole(boolean surfaceWanted, boolean terrainWanted, boolean same) {
         RoadSurface.Geometry a = surfaceWanted
            ? RoadMeshSurfaces.build(
               this.road,
               this.road.renderMesh(),
               this.joined.stream().map(RoadIndex.Built::renderMesh).toList(),
               this.higher.stream().map(RoadIndex.Built::renderMesh).toList(),
               this.exactJoined
            )
            : null;
         RoadSurface.Geometry b = !terrainWanted
            ? null
            : (
               same && a != null
                  ? a
                  : RoadMeshSurfaces.build(this.road, this.road.mesh, this.exactJoined, this.higher.stream().map(r -> r.mesh).toList(), this.exactJoined)
            );
         return new RoadMeshSurfaces.Pair(a, RoadMeshSurfaces.walks(b, this.road.record.structures()), a != null && a == b);
      }

      private RoadSurface.Geometry finish(RoadSurface.Geometry geometry, RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> joined, RoadMeshSurfaces.Window w) {
         ArrayList<RoadGeometry.Mesh> decks = new ArrayList<>();
         decks.add(mesh);
         decks.addAll(joined);
         return RoadSurface.closurePavement(geometry, this.parts.getOrDefault(w, List.of()), decks);
      }

      long bytes() {
         return (long)this.road.record.structures().size() * 8L
            + (this.surface == null ? 0L : this.surface.bytes())
            + (this.exact != null && this.exact != this.surface ? this.exact.bytes() : 0L);
      }
   }

   static record Pair(RoadSurface.Geometry surface, RoadSurface.Geometry exact, boolean reused) {
   }

   static final class Structures {
      final RoadRenderMesh.StructureContext nearContext;
      final RoadRenderMesh.StructureContext farContext;
      final List<RoadStructures.Part> near;
      final List<RoadStructures.Part> far;
      final Map<RoadMeshSurfaces.Window, List<RoadStructures.Part>> nearWindows;
      final Map<RoadMeshSurfaces.Window, List<RoadStructures.Part>> farWindows;

      Structures(RoadIndex.Built road, List<RoadMeshSurfaces.Window> windows) {
         List<RoadStructures.Part> visible = RoadSignVisibility.parts(road.mesh, road.record.structures());
         this.near = RoadRenderMesh.compact(visible, false);
         this.far = RoadRenderMesh.compact(visible, true);
         this.nearContext = new RoadRenderMesh.StructureContext(this.near);
         this.farContext = new RoadRenderMesh.StructureContext(this.far);
         this.nearWindows = partition(road, windows, this.near);
         this.farWindows = partition(road, windows, this.far);
      }

      private static Map<RoadMeshSurfaces.Window, List<RoadStructures.Part>> partition(
         RoadIndex.Built road, List<RoadMeshSurfaces.Window> windows, List<RoadStructures.Part> parts
      ) {
         HashMap<RoadMeshSurfaces.Window, List<RoadStructures.Part>> result = new HashMap<>();

         for (RoadStructures.Part p : parts) {
            RoadPlanningBudget.check();
            double mid = RoadQueries.horizontal(road.mesh, p.a().add(p.b()).mul(0.5)).sample().distance();

            for (RoadMeshSurfaces.Window w : windows) {
               if (w.includes(mid)) {
                  result.computeIfAbsent(w, k -> new ArrayList<>()).add(p);
                  break;
               }
            }
         }

         return result;
      }

      Map<RoadRenderMesh.Section, RoadRenderMesh.Layers> layers(RoadSurface.Geometry surface, RoadMeshSurfaces.Window w) {
         return RoadRenderMesh.layersFromFaces(
            surface,
            RoadRenderMesh.structureFacesCompacted(this.nearWindows.getOrDefault(w, List.of()), this.nearContext, false),
            RoadRenderMesh.structureFacesCompacted(this.farWindows.getOrDefault(w, List.of()), this.farContext, true)
         );
      }

      long bytes() {
         return (long)(this.near.size() + this.far.size()) * 320L + this.nearContext.bytes() + this.farContext.bytes();
      }
   }

   static record Window(double from, double to, RoadGeometry.V min, RoadGeometry.V max) {
      boolean includes(double station) {
         return RoadSurface.inWindow(station, this.from, this.to);
      }

      double distance(RoadGeometry.V eye) {
         double x = Math.max(this.min.x(), Math.min(this.max.x(), eye.x())) - eye.x();
         double z = Math.max(this.min.z(), Math.min(this.max.z(), eye.z())) - eye.z();
         return x * x + z * z;
      }
   }
}
