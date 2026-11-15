package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Predicate;

public final class RoadRaster {
   private static final LinkedHashMap<RoadRaster.ShapeKey, RoadRaster.SavedRaster> SAVED = new LinkedHashMap<>(16, 0.75F, true);
   private static long savedWeight;
   private static long savedHits;
   private static long savedMisses;
   private static long savedEpoch;
   private static final Comparator<RoadRaster.Box> ROW_ORDER = (a, b) -> {
      int c = Double.compare(a.z0, b.z0);
      if (c != 0) {
         return c;
      } else {
         c = Double.compare(a.z1, b.z1);
         if (c != 0) {
            return c;
         } else {
            c = Double.compare(a.x0, b.x0);
            return c != 0 ? c : Double.compare(a.x1, b.x1);
         }
      }
   };
   private static final Comparator<RoadRaster.Box> COLUMN_ORDER = (a, b) -> {
      int c = Double.compare(a.x0, b.x0);
      if (c != 0) {
         return c;
      } else {
         c = Double.compare(a.x1, b.x1);
         if (c != 0) {
            return c;
         } else {
            c = Double.compare(a.z0, b.z0);
            return c != 0 ? c : Double.compare(a.z1, b.z1);
         }
      }
   };
   private static final Comparator<RoadRaster.Box> BOX_ORDER = (a, b) -> {
      int c = Double.compare(a.y0, b.y0);
      if (c != 0) {
         return c;
      } else {
         c = Double.compare(a.y1, b.y1);
         return c != 0 ? c : ROW_ORDER.compare(a, b);
      }
   };

   public static synchronized void clearSavedRasters() {
      SAVED.clear();
      savedWeight = 0L;
      savedEpoch++;
   }

   public static synchronized long[] savedRasterStats() {
      return new long[]{(long)SAVED.size(), savedWeight, savedHits, savedMisses};
   }

   public static Map<RoadRaster.Cell, List<RoadRaster.Box>> cachedRaster(RoadGeometry.Mesh mesh) {
      RoadRaster.ShapeKey key = new RoadRaster.ShapeKey(mesh);
      long epoch;
      synchronized (RoadRaster.class) {
         RoadRaster.SavedRaster old = SAVED.get(key);
         if (old != null) {
            savedHits++;
            return old.cells();
         }

         savedMisses++;
         epoch = savedEpoch;
      }

      Map<RoadRaster.Cell, List<RoadRaster.Box>> cells = raster(mesh);
      long weight = (long)mesh.samples().size();

      for (List<RoadRaster.Box> list : cells.values()) {
         weight += (long)(list.size() + 1);
      }

      if (weight > 250000L) {
         return cells;
      } else {
         cells.replaceAll((cell, boxes) -> List.copyOf(boxes));
         Map<RoadRaster.Cell, List<RoadRaster.Box>> result = Map.copyOf(cells);
         synchronized (RoadRaster.class) {
            if (epoch != savedEpoch) {
               return result;
            } else {
               RoadRaster.SavedRaster old = SAVED.get(key);
               if (old != null) {
                  return old.cells();
               } else {
                  SAVED.put(key, new RoadRaster.SavedRaster(result, (int)weight));
                  savedWeight += weight;
                  Iterator<Entry<RoadRaster.ShapeKey, RoadRaster.SavedRaster>> it = SAVED.entrySet().iterator();

                  while (it.hasNext() && (SAVED.size() > 12 || savedWeight > 250000L)) {
                     savedWeight = savedWeight - (long)it.next().getValue().weight();
                     it.remove();
                  }

                  return result;
               }
            }
         }
      }
   }

   public static Map<RoadRaster.Cell, List<RoadRaster.Box>> raster(RoadGeometry.Mesh mesh) {
      return raster(mesh, null);
   }

   public static Map<RoadRaster.Cell, List<RoadRaster.Box>> raster(RoadGeometry.Mesh mesh, RoadRaster.Cell only) {
      int resolution = mesh.max().y() - mesh.min().y() - mesh.settings().thickness() > 1.0E-5 ? 8 : 4;
      if (mesh.length() <= 256.0) {
         return rasterPart(mesh, only, resolution);
      } else {
         Map<RoadRaster.Cell, List<RoadRaster.Box>> result = new HashMap<>();

         for (RoadGeometry.Mesh part : LaneDeck.rasterPieces(mesh, 96.0)) {
            RoadPlanningBudget.check();
            if (only == null
               || !((double)(only.x() + 1) < part.min().x())
                  && !((double)only.x() > part.max().x())
                  && !((double)(only.z() + 1) < part.min().z())
                  && !((double)only.z() > part.max().z())) {
               rasterPart(part, only, resolution).forEach((cell, boxes) -> result.computeIfAbsent(cell, ignored -> new ArrayList<>()).addAll(boxes));
               if (result.size() > 262144) {
                  throw new IllegalArgumentException("道路实体占位过大，请增加中间端点");
               }
            }
         }

         result.replaceAll((cell, boxes) -> compact((List<RoadRaster.Box>)boxes));
         return result;
      }
   }

   private static Map<RoadRaster.Cell, List<RoadRaster.Box>> rasterPart(RoadGeometry.Mesh mesh, RoadRaster.Cell only, int resolution) {
      if (!Boolean.getBoolean("sr.raster.legacyRectangles")) {
         Map<RoadRaster.Cell, List<RoadRaster.Box>> rectangular = flatRectangles(mesh, only, resolution);
         if (rectangular != null) {
            return rectangular;
         }
      }

      Map<RoadRaster.Tile, List<RoadRaster.Interval>> tiles = new HashMap<>();
      List<RoadGeometry.Sample> points = mesh.samples();
      double step = 1.0 / (double)resolution;

      for (int i = 1; i < points.size(); i++) {
         RoadPlanningBudget.check();
         RoadGeometry.Sample a = points.get(i - 1);
         RoadGeometry.Sample b = points.get(i);

         for (LaneDeck.Strip strip : LaneDeck.strips(mesh, a, b)) {
            triangle(tiles, strip.al(), strip.ar(), strip.br(), mesh.settings().thickness(), resolution, only);
            triangle(tiles, strip.al(), strip.br(), strip.bl(), mesh.settings().thickness(), resolution, only);
         }

         if (tiles.size() > 1500000) {
            throw new IllegalArgumentException("碰撞面积过大，请将道路分为多段");
         }
      }

      return collect(tiles, resolution, only);
   }

   private static Map<RoadRaster.Cell, List<RoadRaster.Box>> flatRectangles(RoadGeometry.Mesh mesh, RoadRaster.Cell only, int resolution) {
      List<RoadGeometry.Sample> points = mesh.samples();
      if (points.isEmpty()) {
         return null;
      } else {
         double y = points.get(0).center().y();

         for (RoadGeometry.Sample at : points) {
            if (at.center().y() != y || at.left().y() != 0.0) {
               return null;
            }
         }

         ArrayList<double[]> quads = new ArrayList<>();

         for (int i = 1; i < points.size(); i++) {
            for (LaneDeck.Strip q : LaneDeck.strips(mesh, points.get(i - 1), points.get(i))) {
               double[] rect = rectangle(q.al(), q.ar(), q.br(), q.bl(), resolution);
               if (rect == null) {
                  return null;
               }

               quads.add(rect);
            }
         }

         Map<RoadRaster.Tile, Long> masks = new HashMap<>();

         for (double[] r : quads) {
            RoadPlanningBudget.check();
            int tx0 = (int)Math.floor(r[0] * (double)resolution);
            int tx1 = (int)Math.ceil(r[2] * (double)resolution) - 1;
            int tz0 = (int)Math.floor(r[1] * (double)resolution);
            int tz1 = (int)Math.ceil(r[3] * (double)resolution) - 1;
            int x0 = Math.floorDiv(tx0, resolution);
            int x1 = Math.floorDiv(tx1, resolution);
            int z0 = Math.floorDiv(tz0, resolution);
            int z1 = Math.floorDiv(tz1, resolution);
            if (only != null) {
               x0 = Math.max(x0, only.x);
               x1 = Math.min(x1, only.x);
               z0 = Math.max(z0, only.z);
               z1 = Math.min(z1, only.z);
            }

            for (int x = x0; x <= x1; x++) {
               for (int z = z0; z <= z1; z++) {
                  int loX = Math.max(0, tx0 - x * resolution);
                  int hiX = Math.min(resolution, tx1 - x * resolution + 1);
                  int loZ = Math.max(0, tz0 - z * resolution);
                  int hiZ = Math.min(resolution, tz1 - z * resolution + 1);
                  long row = (1L << hiX - loX) - 1L << loX;
                  long bits = 0L;

                  for (int iz = loZ; iz < hiZ; iz++) {
                     bits |= row << iz * resolution;
                  }

                  RoadRaster.Tile key = new RoadRaster.Tile(x, z);
                  masks.merge(key, bits, (a, b) -> a | b);
               }
            }
         }

         Map<RoadRaster.Cell, List<RoadRaster.Box>> result = new HashMap<>();
         double low = y - mesh.settings().thickness();
         int y0 = (int)Math.floor(low);
         int y1 = (int)Math.ceil(y - 1.0E-8) - 1;

         for (Entry<RoadRaster.Tile, Long> e : masks.entrySet()) {
            for (int iy = y0; iy <= y1; iy++) {
               if (only == null || only.y == iy) {
                  double b0 = Math.max(0.0, low - (double)iy);
                  double b1 = Math.min(1.0, y - (double)iy);
                  if (!(b1 - b0 <= 1.0E-8)) {
                     result.put(new RoadRaster.Cell(e.getKey().x, iy, e.getKey().z), maskBoxes(e.getValue(), resolution, b0, b1));
                  }
               }
            }
         }

         if (result.size() > 262144) {
            throw new IllegalArgumentException("道路实体占位过大，请增加中间端点");
         } else {
            return result;
         }
      }
   }

   private static double[] rectangle(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V c, RoadGeometry.V d, int resolution) {
      if (a.y() == b.y() && a.y() == c.y() && a.y() == d.y()) {
         if (a.x() == b.x() && b.z() == c.z() && c.x() == d.x() && d.z() == a.z() || a.z() == b.z() && b.x() == c.x() && c.z() == d.z() && d.x() == a.x()) {
            double x0 = Math.min(a.x(), c.x());
            double x1 = Math.max(a.x(), c.x());
            double z0 = Math.min(a.z(), c.z());
            double z1 = Math.max(a.z(), c.z());
            if (!(x1 - x0 < 1.0E-4) && !(z1 - z0 < 1.0E-4)) {
               for (double v : new double[]{x0, x1, z0, z1}) {
                  double f = v * (double)resolution - Math.floor(v * (double)resolution);
                  if (f != 0.0 && Math.min(f, 1.0 - f) < 1.0E-4) {
                     return null;
                  }
               }

               return new double[]{x0, z0, x1, z1};
            } else {
               return null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static List<RoadRaster.Box> maskBoxes(long mask, int resolution, double low, double high) {
      long full = resolution == 8 ? -1L : (1L << resolution * resolution) - 1L;
      if (mask == full) {
         return List.of(new RoadRaster.Box(0.0, low, 0.0, 1.0, high, 1.0));
      } else {
         ArrayList<RoadRaster.Box> rows = new ArrayList<>();
         double step = 1.0 / (double)resolution;

         for (int z = 0; z < resolution; z++) {
            int x = 0;

            while (x < resolution) {
               if ((mask & 1L << z * resolution + x) == 0L) {
                  x++;
               } else {
                  int begin = x++;

                  while (x < resolution && (mask & 1L << z * resolution + x) != 0L) {
                     x++;
                  }

                  rows.add(new RoadRaster.Box((double)begin * step, low, (double)z * step, (double)x * step, high, (double)(z + 1) * step));
               }
            }
         }

         return compact(rows);
      }
   }

   private static Map<RoadRaster.Cell, List<RoadRaster.Box>> collect(
      Map<RoadRaster.Tile, List<RoadRaster.Interval>> tiles, int resolution, RoadRaster.Cell only
   ) {
      double step = 1.0 / (double)resolution;
      Map<RoadRaster.Cell, List<RoadRaster.Box>> result = new HashMap<>();

      for (Entry<RoadRaster.Tile, List<RoadRaster.Interval>> entry : tiles.entrySet()) {
         RoadPlanningBudget.check();
         RoadRaster.Tile tile = entry.getKey();
         double x0 = (double)tile.x * step;
         double z0 = (double)tile.z * step;
         int x = Math.floorDiv(tile.x, resolution);
         int z = Math.floorDiv(tile.z, resolution);

         for (RoadRaster.Interval v : entry.getValue()) {
            int minY = (int)Math.floor(v.low);
            int maxY = (int)Math.ceil(v.high - 1.0E-8) - 1;

            for (int y = minY; y <= maxY; y++) {
               if (only == null || y == only.y()) {
                  RoadRaster.Box box = new RoadRaster.Box(
                     x0 - (double)x,
                     Math.max(0.0, v.low - (double)y),
                     z0 - (double)z,
                     x0 + step - (double)x,
                     Math.min(1.0, v.high - (double)y),
                     z0 + step - (double)z
                  );
                  if (box.y1 - box.y0 > 1.0E-8) {
                     result.computeIfAbsent(new RoadRaster.Cell(x, y, z), k -> new ArrayList<>()).add(box);
                  }
               }
            }
         }
      }

      if (result.size() > 262144) {
         throw new IllegalArgumentException("道路实体占位过大，请增加中间端点");
      } else {
         result.replaceAll((cell, boxes) -> compact((List<RoadRaster.Box>)boxes));
         return result;
      }
   }

   private static void triangle(
      Map<RoadRaster.Tile, List<RoadRaster.Interval>> tiles,
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V c,
      double thickness,
      int resolution,
      RoadRaster.Cell only
   ) {
      triangle(tiles, a, b, c, thickness, null, resolution, only);
   }

   private static void triangle(
      Map<RoadRaster.Tile, List<RoadRaster.Interval>> tiles,
      RoadGeometry.V a,
      RoadGeometry.V b,
      RoadGeometry.V c,
      double thickness,
      List<RoadGeometry.V> bottom,
      int resolution,
      RoadRaster.Cell only
   ) {
      double twiceArea = Math.abs((b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x()));
      double edge = Math.max(a.sub(b).horizontalLength(), Math.max(a.sub(c).horizontalLength(), b.sub(c).horizontalLength()));
      if (!(twiceArea <= edge * 1.0E-7)) {
         double step = 1.0 / (double)resolution;
         int x0 = (int)Math.floor(Math.min(a.x(), Math.min(b.x(), c.x())) * (double)resolution);
         int x1 = (int)Math.ceil(Math.max(a.x(), Math.max(b.x(), c.x())) * (double)resolution) - 1;
         int z0 = (int)Math.floor(Math.min(a.z(), Math.min(b.z(), c.z())) * (double)resolution);
         int z1 = (int)Math.ceil(Math.max(a.z(), Math.max(b.z(), c.z())) * (double)resolution) - 1;
         if (only != null) {
            x0 = Math.max(x0, only.x() * resolution);
            x1 = Math.min(x1, (only.x() + 1) * resolution - 1);
            z0 = Math.max(z0, only.z() * resolution);
            z1 = Math.min(z1, (only.z() + 1) * resolution - 1);
         }

         if (x0 <= x1 && z0 <= z1) {
            double[] poly = new double[36];
            double[] scratch = new double[36];

            for (int x = x0; x <= x1; x++) {
               for (int z = z0; z <= z1; z++) {
                  poly[0] = a.x();
                  poly[1] = a.y();
                  poly[2] = a.z();
                  poly[3] = b.x();
                  poly[4] = b.y();
                  poly[5] = b.z();
                  poly[6] = c.x();
                  poly[7] = c.y();
                  poly[8] = c.z();
                  int n = clip(poly, 9, scratch, 0, (double)x * step, true);
                  n = clip(scratch, n, poly, 0, (double)(x + 1) * step, false);
                  n = clip(poly, n, scratch, 2, (double)z * step, true);
                  n = clip(scratch, n, poly, 2, (double)(z + 1) * step, false);
                  if (n >= 9 && !(area(poly, n) < 1.0E-11)) {
                     double min = Double.MAX_VALUE;
                     double max = -Double.MAX_VALUE;

                     for (int i = 1; i < n; i += 3) {
                        min = Math.min(
                           min, bottom == null ? poly[i] - thickness : RoadTerrainMesh.height(bottom, new RoadGeometry.V(poly[i - 1], poly[i], poly[i + 1]))
                        );
                        max = Math.max(max, poly[i]);
                     }

                     RoadRaster.Interval next = new RoadRaster.Interval(min, max);
                     List<RoadRaster.Interval> list = tiles.computeIfAbsent(new RoadRaster.Tile(x, z), k -> new ArrayList<>());
                     Iterator<RoadRaster.Interval> it = list.iterator();

                     while (it.hasNext()) {
                        RoadRaster.Interval old = it.next();
                        if (next.low <= old.high + 1.0E-7 && next.high >= old.low - 1.0E-7) {
                           next.low = Math.min(next.low, old.low);
                           next.high = Math.max(next.high, old.high);
                           it.remove();
                        }
                     }

                     list.add(next);
                  }
               }
            }
         }
      }
   }

   public static List<RoadRaster.Box> compact(List<RoadRaster.Box> input) {
      if (input.size() < 2) {
         return new ArrayList<>(input);
      } else {
         List<RoadRaster.Box> fast = Boolean.getBoolean("sr.raster.legacyCompact") ? null : compactFlat(input);
         if (fast != null) {
            return fast;
         } else {
            List<RoadRaster.Box> out = new ArrayList<>(input);
            Comparator<RoadRaster.Box> order = BOX_ORDER;
            out.sort(order);

            for (int axis = 0; axis < 2; axis++) {
               for (int i = 0; i < out.size(); i++) {
                  int j = i + 1;

                  while (j < out.size()) {
                     RoadRaster.Box a = out.get(i);
                     RoadRaster.Box b = out.get(j);
                     if (b.y0 - a.y0 >= 1.0E-9) {
                        break;
                     }

                     boolean height = Math.abs(a.y0 - b.y0) < 1.0E-9 && Math.abs(a.y1 - b.y1) < 1.0E-9;
                     boolean merge = height
                        && (
                           axis == 0
                              ? a.z0 == b.z0 && a.z1 == b.z1 && (a.x1 == b.x0 || b.x1 == a.x0)
                              : a.x0 == b.x0 && a.x1 == b.x1 && (a.z1 == b.z0 || b.z1 == a.z0)
                        );
                     if (merge) {
                        out.set(i, new RoadRaster.Box(Math.min(a.x0, b.x0), a.y0, Math.min(a.z0, b.z0), Math.max(a.x1, b.x1), a.y1, Math.max(a.z1, b.z1)));
                        out.remove(j);
                        j = i + 1;
                     } else {
                        j++;
                     }
                  }
               }
            }

            out.sort(order);
            return out;
         }
      }
   }

   private static List<RoadRaster.Box> compactFlat(List<RoadRaster.Box> input) {
      if (input.size() < 2) {
         return new ArrayList<>(input);
      } else {
         RoadRaster.Box first = input.get(0);

         for (RoadRaster.Box b : input) {
            if (b.y0() != first.y0() || b.y1() != first.y1()) {
               return null;
            }
         }

         Comparator<RoadRaster.Box> rows = ROW_ORDER;
         ArrayList<RoadRaster.Box> sorted = new ArrayList<>(input);
         sorted.sort(rows);
         ArrayList<RoadRaster.Box> horizontal = new ArrayList<>();
         RoadRaster.Box current = sorted.get(0);

         for (int i = 1; i < sorted.size(); i++) {
            RoadRaster.Box bx = sorted.get(i);
            if (current.z0() == bx.z0() && current.z1() == bx.z1()) {
               if (bx.x0() < current.x1()) {
                  return null;
               }

               if (bx.x0() == current.x1()) {
                  current = new RoadRaster.Box(current.x0(), current.y0(), current.z0(), bx.x1(), current.y1(), current.z1());
                  continue;
               }
            }

            horizontal.add(current);
            current = bx;
         }

         horizontal.add(current);
         horizontal.sort(COLUMN_ORDER);
         ArrayList<RoadRaster.Box> out = new ArrayList<>();
         current = horizontal.get(0);

         for (int i = 1; i < horizontal.size(); i++) {
            RoadRaster.Box bx = horizontal.get(i);
            if (current.x0() == bx.x0() && current.x1() == bx.x1()) {
               if (bx.z0() < current.z1()) {
                  return null;
               }

               if (bx.z0() == current.z1()) {
                  current = new RoadRaster.Box(current.x0(), current.y0(), current.z0(), current.x1(), current.y1(), bx.z1());
                  continue;
               }
            }

            out.add(current);
            current = bx;
         }

         out.add(current);
         out.sort(rows);
         return out;
      }
   }

   public static Map<RoadRaster.Cell, List<RoadRaster.Box>> structures(List<RoadStructures.Part> parts, RoadRaster.Cell only) {
      Map<RoadRaster.Cell, List<RoadRaster.Box>> out = new HashMap<>();

      for (RoadStructures.Part part : parts) {
         RoadPlanningBudget.check();
         if (!RailbedFurniture.visualOnly(part)) {
            if (only != null) {
               double w = part.halfExtent();
               if ((double)(only.x() + 1) <= Math.min(part.a().x(), part.b().x()) - w
                  || (double)only.x() >= Math.max(part.a().x(), part.b().x()) + w
                  || (double)(only.z() + 1) <= Math.min(part.a().z(), part.b().z()) - w
                  || (double)only.z() >= Math.max(part.a().z(), part.b().z()) + w
                  || (double)(only.y() + 1) <= Math.min(part.a().y(), part.b().y()) - part.verticalFrame()
                  || (double)only.y() >= Math.max(part.a().y(), part.b().y()) + part.height() + part.verticalFrame()) {
                  continue;
               }
            }

            if (part.pier() && part.frameA() == null && part.frameB() == null && Math.abs(part.a().y() - part.b().y()) < 1.0E-9) {
               RoadGeometry.V a = part.a();
               double w = part.halfExtent();
               addBox(out, a.x() - w, a.y(), a.z() - w, a.x() + w, a.y() + part.height(), part.b().z() + w, only);
            } else if (!RoadSignals.signal(part) && !RoadPoleModel.support(part)) {
               List<RoadGeometry.V> base = part.base();
               double[] rect = part.heights() == null && !Boolean.getBoolean("sr.raster.legacyRectangles")
                  ? rectangle(base.get(0), base.get(1), base.get(2), base.get(3), 4)
                  : null;
               if (rect != null) {
                  double topY = base.get(0).y() + part.height();
                  flatStructure(out, rect, topY - part.height(), topY, only);
               } else {
                  List<RoadGeometry.V> top = part.top();
                  Map<RoadRaster.Tile, List<RoadRaster.Interval>> tiles = new HashMap<>();
                  triangle(
                     tiles,
                     top.get(0),
                     top.get(1),
                     top.get(2),
                     part.height(),
                     part.heights() == null ? null : List.of(base.get(0), base.get(1), base.get(2)),
                     4,
                     only
                  );
                  triangle(
                     tiles,
                     top.get(0),
                     top.get(2),
                     top.get(3),
                     part.height(),
                     part.heights() == null ? null : List.of(base.get(0), base.get(2), base.get(3)),
                     4,
                     only
                  );
                  collect(tiles, 4, only).forEach((cell, boxes) -> out.computeIfAbsent(cell, k -> new ArrayList<>()).addAll(boxes));
               }
            } else {
               List<RoadGeometry.V> vertices = part.faces().stream().flatMap(f -> f.points().stream()).toList();
               addBox(
                  out,
                  vertices.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(),
                  vertices.stream().mapToDouble(RoadGeometry.V::y).min().orElseThrow(),
                  vertices.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow(),
                  vertices.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(),
                  vertices.stream().mapToDouble(RoadGeometry.V::y).max().orElseThrow(),
                  vertices.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow(),
                  only
               );
            }
         }
      }

      return out;
   }

   private static void flatStructure(Map<RoadRaster.Cell, List<RoadRaster.Box>> out, double[] r, double low, double high, RoadRaster.Cell only) {
      double x0 = Math.floor(r[0] * 4.0) / 4.0;
      double x1 = Math.ceil(r[2] * 4.0) / 4.0;
      double z0 = Math.floor(r[1] * 4.0) / 4.0;
      double z1 = Math.ceil(r[3] * 4.0) / 4.0;
      int ax = (int)Math.floor(x0);
      int bx = (int)Math.ceil(x1) - 1;
      int az = (int)Math.floor(z0);
      int bz = (int)Math.ceil(z1) - 1;
      int ay = (int)Math.floor(low);
      int by = (int)Math.ceil(high - 1.0E-8) - 1;
      if (only != null) {
         ax = Math.max(ax, only.x);
         bx = Math.min(bx, only.x);
         az = Math.max(az, only.z);
         bz = Math.min(bz, only.z);
         ay = Math.max(ay, only.y);
         by = Math.min(by, only.y);
      }

      for (int x = ax; x <= bx; x++) {
         for (int y = ay; y <= by; y++) {
            for (int z = az; z <= bz; z++) {
               double bottom = Math.max(0.0, low - (double)y);
               double top = Math.min(1.0, high - (double)y);
               if (!(top - bottom <= 1.0E-8)) {
                  out.computeIfAbsent(new RoadRaster.Cell(x, y, z), c -> new ArrayList<>())
                     .add(
                        new RoadRaster.Box(
                           Math.max(0.0, x0 - (double)x),
                           bottom,
                           Math.max(0.0, z0 - (double)z),
                           Math.min(1.0, x1 - (double)x),
                           top,
                           Math.min(1.0, z1 - (double)z)
                        )
                     );
               }
            }
         }
      }
   }

   private static void addBox(
      Map<RoadRaster.Cell, List<RoadRaster.Box>> out, double x0, double y0, double z0, double x1, double y1, double z1, RoadRaster.Cell only
   ) {
      int minX = (int)Math.floor(x0);
      int minY = (int)Math.floor(y0);
      int minZ = (int)Math.floor(z0);
      int maxX = (int)Math.ceil(x1) - 1;
      int maxY = (int)Math.ceil(y1) - 1;
      int maxZ = (int)Math.ceil(z1) - 1;
      if (only != null) {
         minX = Math.max(minX, only.x);
         maxX = Math.min(maxX, only.x);
         minY = Math.max(minY, only.y);
         maxY = Math.min(maxY, only.y);
         minZ = Math.max(minZ, only.z);
         maxZ = Math.min(maxZ, only.z);
      }

      for (int x = minX; x <= maxX; x++) {
         for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
               out.computeIfAbsent(new RoadRaster.Cell(x, y, z), k -> new ArrayList<>())
                  .add(
                     new RoadRaster.Box(
                        Math.max(0.0, x0 - (double)x),
                        Math.max(0.0, y0 - (double)y),
                        Math.max(0.0, z0 - (double)z),
                        Math.min(1.0, x1 - (double)x),
                        Math.min(1.0, y1 - (double)y),
                        Math.min(1.0, z1 - (double)z)
                     )
                  );
            }
         }
      }
   }

   private static int clip(double[] in, int length, double[] out, int axis, double edge, boolean above) {
      if (length == 0) {
         return 0;
      } else {
         int previous = length - 3;
         int count = 0;
         boolean pi = above ? in[previous + axis] >= edge : in[previous + axis] <= edge;

         for (int p = 0; p < length; p += 3) {
            boolean inside = above ? in[p + axis] >= edge : in[p + axis] <= edge;
            if (inside != pi) {
               double t = (edge - in[previous + axis]) / (in[p + axis] - in[previous + axis]);

               for (int k = 0; k < 3; k++) {
                  out[count++] = in[previous + k] + (in[p + k] - in[previous + k]) * t;
               }
            }

            if (inside) {
               out[count++] = in[p];
               out[count++] = in[p + 1];
               out[count++] = in[p + 2];
            }

            previous = p;
            pi = inside;
         }

         return count;
      }
   }

   private static double area(double[] p, int length) {
      double sum = 0.0;

      for (int i = 3; i < length - 3; i += 3) {
         sum += (p[i] - p[0]) * (p[i + 5] - p[2]) - (p[i + 2] - p[2]) * (p[i + 3] - p[0]);
      }

      return Math.abs(sum) * 0.5;
   }

   private RoadRaster() {
   }

   public static record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
   }

   public static record Cell(int x, int y, int z) {
   }

   private static final class Interval {
      double low;
      double high;

      Interval(double a, double b) {
         this.low = a;
         this.high = b;
      }
   }

   public static final class Local {
      private final Map<Long, List<RoadRaster.Local.Segment>> decks = new HashMap<>();
      private final Map<Long, List<RoadStructures.Part>> furniture = new HashMap<>();
      private final int resolution;
      public final int segmentCount;

      public Local(RoadGeometry.Mesh mesh, List<RoadStructures.Part> parts) {
         this.resolution = mesh.max().y() - mesh.min().y() - mesh.settings().thickness() > 1.0E-5 ? 8 : 4;
         int count = 0;

         for (RoadGeometry.Mesh piece : LaneDeck.rasterPieces(mesh, 96.0)) {
            List<RoadGeometry.Sample> points = piece.samples();

            for (int i = 1; i < points.size(); i++) {
               RoadPlanningBudget.check();
               RoadGeometry.Sample a = points.get(i - 1);
               RoadGeometry.Sample b = points.get(i);

               for (LaneDeck.Strip strip : LaneDeck.strips(piece, a, b)) {
                  RoadRaster.Local.Segment segment = new RoadRaster.Local.Segment(strip.al(), strip.ar(), strip.bl(), strip.br(), mesh.settings().thickness());
                  List<RoadGeometry.V> vertices = List.of(segment.l, segment.r, segment.ll, segment.rr);
                  add(
                     this.decks,
                     segment,
                     vertices.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(),
                     vertices.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow(),
                     vertices.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(),
                     vertices.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()
                  );
                  count++;
               }
            }
         }

         this.segmentCount = count;

         for (RoadStructures.Part p : parts) {
            if (!RailbedFurniture.visualOnly(p)) {
               double w = p.halfExtent();
               add(
                  this.furniture,
                  p,
                  Math.min(p.a().x(), p.b().x()) - w,
                  Math.min(p.a().z(), p.b().z()) - w,
                  Math.max(p.a().x(), p.b().x()) + w,
                  Math.max(p.a().z(), p.b().z()) + w
               );
            }
         }
      }

      private static long key(int x, int z) {
         return (long)x << 32 ^ (long)z & 4294967295L;
      }

      private static <T> void add(Map<Long, List<T>> bins, T value, double minX, double minZ, double maxX, double maxZ) {
         for (int x = (int)Math.floor(minX / 4.0); x <= (int)Math.floor(maxX / 4.0); x++) {
            for (int z = (int)Math.floor(minZ / 4.0); z <= (int)Math.floor(maxZ / 4.0); z++) {
               bins.computeIfAbsent(key(x, z), ignored -> new ArrayList<>()).add(value);
            }
         }
      }

      public int candidates(RoadRaster.Cell cell) {
         return this.decks.getOrDefault(key(Math.floorDiv(cell.x(), 4), Math.floorDiv(cell.z(), 4)), List.of()).size();
      }

      public Set<RoadRaster.Cell> cellsInChunk(int chunkX, int chunkZ) {
         Set<RoadRaster.Cell> result = new HashSet<>();

         for (int x = chunkX * 16; x < chunkX * 16 + 16; x++) {
            for (int z = chunkZ * 16; z < chunkZ * 16 + 16; z++) {
               long bin = key(Math.floorDiv(x, 4), Math.floorDiv(z, 4));
               result.addAll(this.deckColumn(x, z).keySet());

               for (RoadStructures.Part part : this.furniture.getOrDefault(bin, List.of())) {
                  double w = part.halfExtent();
                  if (!((double)(x + 1) <= Math.min(part.a().x(), part.b().x()) - w)
                     && !((double)x >= Math.max(part.a().x(), part.b().x()) + w)
                     && !((double)(z + 1) <= Math.min(part.a().z(), part.b().z()) - w)
                     && !((double)z >= Math.max(part.a().z(), part.b().z()) + w)) {
                     int low = (int)Math.floor(Math.min(part.a().y(), part.b().y()) - part.verticalFrame());
                     int high = (int)Math.ceil(Math.max(part.a().y(), part.b().y()) + part.height() + part.verticalFrame());

                     for (int y = low; y < high; y++) {
                        RoadRaster.Cell cell = new RoadRaster.Cell(x, y, z);
                        if (!RoadRaster.structures(List.of(part), cell).isEmpty()) {
                           result.add(cell);
                        }
                     }
                  }
               }
            }
         }

         return result;
      }

      public Map<RoadRaster.Cell, List<RoadRaster.Box>> deckColumn(int x, int z) {
         Map<RoadRaster.Tile, List<RoadRaster.Interval>> tiles = new HashMap<>();
         RoadRaster.Cell column = new RoadRaster.Cell(x, 0, z);

         for (RoadRaster.Local.Segment s : this.decks.getOrDefault(key(Math.floorDiv(x, 4), Math.floorDiv(z, 4)), List.of())) {
            RoadRaster.triangle(tiles, s.l, s.r, s.rr, s.thickness, this.resolution, column);
            RoadRaster.triangle(tiles, s.l, s.rr, s.ll, s.thickness, this.resolution, column);
         }

         return RoadRaster.collect(tiles, this.resolution, null);
      }

      public Map<RoadRaster.Cell, List<RoadRaster.Box>> structureColumn(int x, int z, Predicate<RoadStructures.Part> match) {
         Map<RoadRaster.Cell, List<RoadRaster.Box>> result = new HashMap<>();

         for (RoadStructures.Part part : this.furniture.getOrDefault(key(Math.floorDiv(x, 4), Math.floorDiv(z, 4)), List.of())) {
            if (match.test(part)) {
               double extent = part.halfExtent();
               if (!((double)(x + 1) <= Math.min(part.a().x(), part.b().x()) - extent)
                  && !((double)x >= Math.max(part.a().x(), part.b().x()) + extent)
                  && !((double)(z + 1) <= Math.min(part.a().z(), part.b().z()) - extent)
                  && !((double)z >= Math.max(part.a().z(), part.b().z()) + extent)) {
                  int low = (int)Math.floor(Math.min(part.a().y(), part.b().y()) - part.verticalFrame());
                  int high = (int)Math.ceil(Math.max(part.a().y(), part.b().y()) + part.height() + part.verticalFrame());

                  for (int y = low; y < high; y++) {
                     RoadRaster.Cell cell = new RoadRaster.Cell(x, y, z);
                     RoadRaster.structures(List.of(part), cell).forEach((c, boxes) -> result.computeIfAbsent(c, k -> new ArrayList<>()).addAll(boxes));
                  }
               }
            }
         }

         return result;
      }

      public boolean structureAt(RoadRaster.Cell cell, Predicate<RoadStructures.Part> match) {
         for (RoadStructures.Part part : this.furniture.getOrDefault(key(Math.floorDiv(cell.x(), 4), Math.floorDiv(cell.z(), 4)), List.of())) {
            if (match.test(part) && !RoadRaster.structures(List.of(part), cell).isEmpty()) {
               return true;
            }
         }

         return false;
      }

      public List<RoadRaster.Box> boxes(RoadRaster.Cell cell) {
         long key = key(Math.floorDiv(cell.x(), 4), Math.floorDiv(cell.z(), 4));
         Map<RoadRaster.Tile, List<RoadRaster.Interval>> tiles = new HashMap<>();

         for (RoadRaster.Local.Segment s : this.decks.getOrDefault(key, List.of())) {
            double top = Math.max(Math.max(s.l.y(), s.r.y()), Math.max(s.ll.y(), s.rr.y()));
            double bottom = Math.min(Math.min(s.l.y(), s.r.y()), Math.min(s.ll.y(), s.rr.y())) - s.thickness;
            if (!((double)cell.y() >= top) && !((double)(cell.y() + 1) <= bottom)) {
               RoadRaster.triangle(tiles, s.l, s.r, s.rr, s.thickness, this.resolution, cell);
               RoadRaster.triangle(tiles, s.l, s.rr, s.ll, s.thickness, this.resolution, cell);
            }
         }

         List<RoadRaster.Box> boxes = new ArrayList<>(RoadRaster.collect(tiles, this.resolution, cell).getOrDefault(cell, List.of()));
         boxes.addAll(RoadRaster.structures(this.furniture.getOrDefault(key, List.of()), cell).getOrDefault(cell, List.of()));
         return List.copyOf(boxes);
      }

      private static record Segment(RoadGeometry.V l, RoadGeometry.V r, RoadGeometry.V ll, RoadGeometry.V rr, double thickness) {
      }
   }

   private static record SavedRaster(Map<RoadRaster.Cell, List<RoadRaster.Box>> cells, int weight) {
   }

   private static final class ShapeKey {
      final RoadGeometry.Mesh mesh;
      final int hash;

      ShapeKey(RoadGeometry.Mesh mesh) {
         this.mesh = mesh;
         this.hash = mesh.hashCode();
      }

      @Override
      public int hashCode() {
         return this.hash;
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         } else {
            if (o instanceof RoadRaster.ShapeKey k && this.mesh.equals(k.mesh)) {
               return true;
            }

            return false;
         }
      }
   }

   private static record Tile(int x, int z) {
   }
}
