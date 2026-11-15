package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

public final class RoadTerrainMesh {
   private static final double EPS = 1.0E-8;

   public static RoadTerrainMesh.Source source(RoadSurface.Geometry geometry) {
      Map<RoadTerrainMesh.Chunk, List<RoadSurface.Face>> pavement = new LinkedHashMap<>();
      Map<RoadTerrainMesh.Chunk, List<RoadSurface.Face>> paint = new LinkedHashMap<>();

      for (RoadSurface.Face face : geometry.pavement()) {
         if (asphalt(face) || walkSurface(face)) {
            index(pavement, face);
         }
      }

      for (RoadSurface.Face facex : geometry.markings()) {
         index(paint, facex);
      }

      LinkedHashSet<RoadTerrainMesh.Chunk> keys = new LinkedHashSet<>(pavement.keySet());
      keys.addAll(paint.keySet());
      Map<RoadTerrainMesh.Chunk, RoadSurface.Geometry> result = new LinkedHashMap<>();

      for (RoadTerrainMesh.Chunk chunk : keys) {
         result.put(chunk, new RoadSurface.Geometry(List.copyOf(pavement.getOrDefault(chunk, List.of())), List.copyOf(paint.getOrDefault(chunk, List.of()))));
      }

      return new RoadTerrainMesh.Source(Collections.unmodifiableMap(result));
   }

   private static void index(Map<RoadTerrainMesh.Chunk, List<RoadSurface.Face>> result, RoadSurface.Face face) {
      List<RoadGeometry.V> points = face.points();
      if (points.size() >= 3) {
         int x0 = Math.floorDiv(floor(min(points, 0)), 16);
         int x1 = Math.floorDiv(ceil(max(points, 0)) - 1, 16);
         int z0 = Math.floorDiv(floor(min(points, 2)), 16);
         int z1 = Math.floorDiv(ceil(max(points, 2)) - 1, 16);

         for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
               result.computeIfAbsent(new RoadTerrainMesh.Chunk(x, z), key -> new ArrayList<>()).add(face);
            }
         }
      }
   }

   public static RoadTerrainMesh.Result build(RoadSurface.Geometry geometry) {
      return build(geometry, null);
   }

   public static RoadTerrainMesh.Result build(RoadSurface.Geometry geometry, Set<RoadTerrainMesh.Chunk> loaded) {
      Map<RoadTerrainMesh.Cell, List<RoadTerrainMesh.Polygon>> cells = new LinkedHashMap<>();

      for (RoadSurface.Face face : geometry.pavement()) {
         if (asphalt(face) || walkSurface(face)) {
            add(cells, face, false, loaded);
         }
      }

      Map<RoadTerrainMesh.Cell, List<RoadTerrainMesh.Polygon>> paint = new LinkedHashMap<>();

      for (RoadSurface.Face facex : geometry.markings()) {
         add(paint, facex, true, loaded);
      }

      Map<RoadTerrainMesh.Cell, List<RoadTerrainMesh.Polygon>> projected = new LinkedHashMap<>();

      for (Entry<RoadTerrainMesh.Cell, List<RoadTerrainMesh.Polygon>> entry : paint.entrySet()) {
         for (RoadTerrainMesh.Polygon marking : entry.getValue()) {
            List<List<RoadGeometry.V>> remaining = List.of(marking.vertices());
            RoadTerrainMesh.Cell cell = entry.getKey();

            for (int dy = -1; dy <= 1; dy++) {
               RoadTerrainMesh.Cell owner = new RoadTerrainMesh.Cell(cell.x(), cell.y() + dy, cell.z());

               for (RoadTerrainMesh.Polygon deck : cells.getOrDefault(owner, List.of())) {
                  if (deck.texture() == RoadSurface.Texture.PLAIN) {
                     List<RoadGeometry.V> polygon = RoadSurface.intersect(marking.vertices(), deck.vertices());
                     if (!polygon.isEmpty() && !polygon.stream().anyMatch(p -> Math.abs(p.y() - height(deck.vertices(), p)) > 0.35)) {
                        ArrayList<RoadGeometry.V> surface = new ArrayList<>(
                           polygon.stream().map(p -> new RoadGeometry.V(p.x(), height(deck.vertices(), p), p.z())).toList()
                        );
                        if (normalY(surface) < 0.0) {
                           Collections.reverse(surface);
                        }

                        projected.computeIfAbsent(owner, k -> new ArrayList<>()).add(new RoadTerrainMesh.Polygon(surface, marking.color(), true));
                        ArrayList<List<RoadGeometry.V>> next = new ArrayList<>();

                        for (List<RoadGeometry.V> piece : remaining) {
                           next.addAll(RoadSurface.subtract(piece, deck.vertices()));
                        }

                        remaining = next;
                     }
                  }
               }
            }

            for (List<RoadGeometry.V> piece : remaining) {
               ArrayList<RoadGeometry.V> surface = new ArrayList<>(piece);
               if (normalY(surface) < 0.0) {
                  Collections.reverse(surface);
               }

               projected.computeIfAbsent(paintCarrier(cell, surface, cells), k -> new ArrayList<>())
                  .add(new RoadTerrainMesh.Polygon(surface, marking.color(), true));
            }
         }
      }

      projected.forEach((cellx, polygons) -> cells.computeIfAbsent(cellx, k -> new ArrayList<>()).addAll(polygons));
      int count = 0;

      for (Entry<RoadTerrainMesh.Cell, List<RoadTerrainMesh.Polygon>> e : cells.entrySet()) {
         e.setValue(List.copyOf(e.getValue()));
         count += e.getValue().size();
      }

      return new RoadTerrainMesh.Result(Collections.unmodifiableMap(cells), count);
   }

   private static RoadTerrainMesh.Cell paintCarrier(
      RoadTerrainMesh.Cell cell, List<RoadGeometry.V> paint, Map<RoadTerrainMesh.Cell, List<RoadTerrainMesh.Polygon>> pavement
   ) {
      if (pavement.containsKey(cell)) {
         return cell;
      } else {
         RoadGeometry.V center = new RoadGeometry.V(0.0, 0.0, 0.0);

         for (RoadGeometry.V p : paint) {
            center = center.add(p);
         }

         center = center.mul(1.0 / (double)paint.size());
         RoadTerrainMesh.Cell best = cell;
         double score = Double.POSITIVE_INFINITY;

         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               for (int dz = -1; dz <= 1; dz++) {
                  RoadTerrainMesh.Cell next = new RoadTerrainMesh.Cell(cell.x() + dx, cell.y() + dy, cell.z() + dz);
                  if (next.section().equals(cell.section())) {
                     for (RoadTerrainMesh.Polygon deck : pavement.getOrDefault(next, List.of())) {
                        if (!deck.paint() && deck.texture() == RoadSurface.Texture.PLAIN && !(Math.abs(center.y() - height(deck.vertices(), center)) > 0.35)) {
                           double nearest = Double.POSITIVE_INFINITY;

                           for (int i = 0; i < deck.vertices().size(); i++) {
                              RoadGeometry.V a = deck.vertices().get(i);
                              RoadGeometry.V b = deck.vertices().get((i + 1) % deck.vertices().size());
                              RoadGeometry.V edge = b.sub(a);
                              RoadGeometry.V delta = center.sub(a);
                              double run = edge.x() * edge.x() + edge.z() * edge.z();
                              double t = run < 1.0E-12 ? 0.0 : Math.max(0.0, Math.min(1.0, (delta.x() * edge.x() + delta.z() * edge.z()) / run));
                              RoadGeometry.V q = a.add(edge.mul(t));
                              nearest = Math.min(nearest, Math.hypot(center.x() - q.x(), center.z() - q.z()));
                           }

                           if (nearest <= 0.125 && nearest < score) {
                              score = nearest;
                              best = next;
                           }
                        }
                     }
                  }
               }
            }
         }

         return best;
      }
   }

   public static double height(List<RoadGeometry.V> polygon, RoadGeometry.V p) {
      RoadGeometry.V n = RoadLighting.normal(new RoadSurface.Face(polygon, 0));
      RoadGeometry.V a = polygon.get(0);
      return Math.abs(n.y()) < 1.0E-10 ? a.y() : a.y() - (n.x() * (p.x() - a.x()) + n.z() * (p.z() - a.z())) / n.y();
   }

   public static boolean asphalt(RoadSurface.Face face) {
      return face.texture() == RoadSurface.Texture.PLAIN;
   }

   public static boolean walkSurface(RoadSurface.Face face) {
      return face.texture().name().startsWith("WALK_") && RoadLighting.normal(face).y() > 0.05;
   }

   public static RoadSurface.Geometry remainder(RoadSurface.Geometry geometry) {
      return new RoadSurface.Geometry(geometry.pavement().stream().filter(f -> !asphalt(f) && !walkSurface(f)).toList(), List.of());
   }

   private static void add(
      Map<RoadTerrainMesh.Cell, List<RoadTerrainMesh.Polygon>> out, RoadSurface.Face face, boolean paint, Set<RoadTerrainMesh.Chunk> loaded
   ) {
      List<RoadGeometry.V> p = face.points();
      if (p.size() >= 3) {
         for (int t = 1; t + 1 < p.size(); t++) {
            List<RoadGeometry.V> tri = List.of(p.get(0), p.get(t), p.get(t + 1));
            if (!(area(tri) < 1.0E-12)) {
               int x0 = floor(min(tri, 0));
               int x1 = ceil(max(tri, 0));
               int z0 = floor(min(tri, 2));
               int z1 = ceil(max(tri, 2));
               if (loaded == null) {
                  rasterize(out, tri, face.color(), paint, face.texture(), x0, x1, z0, z1);
               } else {
                  for (RoadTerrainMesh.Chunk chunk : loaded) {
                     int cx = chunk.x() * 16;
                     int cz = chunk.z() * 16;
                     rasterize(out, tri, face.color(), paint, face.texture(), Math.max(x0, cx), Math.min(x1, cx + 16), Math.max(z0, cz), Math.min(z1, cz + 16));
                  }
               }
            }
         }
      }
   }

   private static void rasterize(
      Map<RoadTerrainMesh.Cell, List<RoadTerrainMesh.Polygon>> out,
      List<RoadGeometry.V> tri,
      int color,
      boolean paint,
      RoadSurface.Texture texture,
      int x0,
      int x1,
      int z0,
      int z1
   ) {
      if (x0 < x1 && z0 < z1) {
         for (int x = x0; x < x1; x++) {
            List<RoadGeometry.V> strip = clip(clip(tri, 0, (double)x, true), 0, (double)(x + 1), false);
            if (strip.size() >= 3) {
               for (int z = z0; z < z1; z++) {
                  List<RoadGeometry.V> tile = clip(clip(strip, 2, (double)z, true), 2, (double)(z + 1), false);
                  if (tile.size() >= 3 && !(area(tile) < 1.0E-12)) {
                     double low = min(tile, 1);
                     double high = max(tile, 1);
                     int first = ceil(low) - 1;
                     int last = ceil(high) - 1;

                     for (int y = first; y <= last; y++) {
                        List<RoadGeometry.V> part = clip(clip(tile, 1, (double)y + 1.0E-8, true, 0.0), 1, (double)(y + 1) + 1.0E-8, false, 0.0);
                        if (part.size() >= 3 && !(area(part) < 1.0E-12)) {
                           if (normalY(part) < 0.0) {
                              ArrayList<RoadGeometry.V> reversed = new ArrayList<>(part);
                              Collections.reverse(reversed);
                              part = reversed;
                           }

                           out.computeIfAbsent(new RoadTerrainMesh.Cell(x, y, z), k -> new ArrayList<>())
                              .add(new RoadTerrainMesh.Polygon(part, color, paint, texture));
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static List<RoadGeometry.V> clip(List<RoadGeometry.V> in, int axis, double plane, boolean greater) {
      return clip(in, axis, plane, greater, 1.0E-8);
   }

   private static List<RoadGeometry.V> clip(List<RoadGeometry.V> in, int axis, double plane, boolean greater, double tolerance) {
      if (in.isEmpty()) {
         return List.of();
      } else {
         List<RoadGeometry.V> out = new ArrayList<>(in.size() + 2);
         RoadGeometry.V a = in.get(in.size() - 1);
         double da = coord(a, axis) - plane;
         boolean ia = greater ? da >= -tolerance : da <= tolerance;

         for (RoadGeometry.V b : in) {
            double db = coord(b, axis) - plane;
            boolean ib = greater ? db >= -tolerance : db <= tolerance;
            if (ia != ib) {
               double t = da / (da - db);
               RoadGeometry.V v = a.add(b.sub(a).mul(Math.max(0.0, Math.min(1.0, t))));
               out.add(snap(v, axis, plane));
            }

            if (ib) {
               out.add(b);
            }

            a = b;
            da = db;
            ia = ib;
         }

         for (int i = out.size() - 1; i >= 0 && out.size() > 1; i--) {
            if (out.get(i).sub(out.get((i + 1) % out.size())).dot(out.get(i).sub(out.get((i + 1) % out.size()))) < 1.0E-20) {
               out.remove(i);
            }
         }

         return out;
      }
   }

   private static RoadGeometry.V snap(RoadGeometry.V p, int axis, double value) {
      return axis == 0
         ? new RoadGeometry.V(value, p.y(), p.z())
         : (axis == 1 ? new RoadGeometry.V(p.x(), value, p.z()) : new RoadGeometry.V(p.x(), p.y(), value));
   }

   private static int floor(double x) {
      return (int)Math.floor(x + 1.0E-8);
   }

   private static int ceil(double x) {
      return (int)Math.ceil(x - 1.0E-8);
   }

   private static double coord(RoadGeometry.V p, int axis) {
      return axis == 0 ? p.x() : (axis == 1 ? p.y() : p.z());
   }

   private static double min(List<RoadGeometry.V> p, int axis) {
      double r = Double.POSITIVE_INFINITY;

      for (RoadGeometry.V v : p) {
         r = Math.min(r, coord(v, axis));
      }

      return r;
   }

   private static double max(List<RoadGeometry.V> p, int axis) {
      double r = Double.NEGATIVE_INFINITY;

      for (RoadGeometry.V v : p) {
         r = Math.max(r, coord(v, axis));
      }

      return r;
   }

   public static double area(List<RoadGeometry.V> p) {
      return Math.abs(normalY(p)) * 0.5;
   }

   private static double normalY(List<RoadGeometry.V> p) {
      RoadGeometry.V o = p.get(0);
      double sum = 0.0;

      for (int i = 1; i + 1 < p.size(); i++) {
         RoadGeometry.V a = p.get(i).sub(o);
         RoadGeometry.V b = p.get(i + 1).sub(o);
         sum += a.z() * b.x() - a.x() * b.z();
      }

      return sum;
   }

   private RoadTerrainMesh() {
   }

   public static record Cell(int x, int y, int z) {
      public RoadTerrainMesh.Section section() {
         return new RoadTerrainMesh.Section(Math.floorDiv(this.x, 16), Math.floorDiv(this.y, 16), Math.floorDiv(this.z, 16));
      }

      public int localKey() {
         return this.x & 15 | (this.z & 15) << 4 | (this.y & 15) << 8;
      }
   }

   public static record Chunk(int x, int z) {
   }

   public static record Polygon(List<RoadGeometry.V> vertices, int color, boolean paint, RoadSurface.Texture texture) {
      public Polygon(List<RoadGeometry.V> vertices, int color, boolean paint) {
         this(vertices, color, paint, RoadSurface.Texture.PLAIN);
      }

      public Polygon(List<RoadGeometry.V> vertices, int color, boolean paint, RoadSurface.Texture texture) {
         vertices = List.copyOf(vertices);
         this.vertices = vertices;
         this.color = color;
         this.paint = paint;
         this.texture = texture;
      }
   }

   public static record Result(Map<RoadTerrainMesh.Cell, List<RoadTerrainMesh.Polygon>> cells, int polygons) {
   }

   public static record Section(int x, int y, int z) {
   }

   public static record Source(Map<RoadTerrainMesh.Chunk, RoadSurface.Geometry> chunks) {
   }
}
