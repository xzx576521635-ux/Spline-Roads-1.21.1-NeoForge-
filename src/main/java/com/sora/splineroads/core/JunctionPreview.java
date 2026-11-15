package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

public final class JunctionPreview {
   public static JunctionPreview.Image render(JunctionPlanner.Plan plan, int width, int height, int arm, int lane, boolean movement) {
      JunctionPreview.Painter result = new JunctionPreview.Painter(plan, width, height);
      LinkedHashSet<RoadSidewalks.Cell> walks = new LinkedHashSet<>(plan.sidewalks().keySet());

      for (JunctionPlanner.Piece piece : plan.pieces()) {
         if (piece.arm() >= 0) {
            walks.addAll(RoadSidewalks.cells(piece.mesh(), piece.mesh().settings().options().sidewalk()));
         }
      }

      for (RoadSurface.Face face : RoadSidewalks.preview(walks)) {
         result.polygon(face.points(), -7364197);
      }

      for (JunctionPlanner.Piece piecex : plan.pieces()) {
         List<RoadGeometry.Sample> points = piecex.mesh().samples();
         int color = piecex.arm() == arm ? -11507340 : -11512227;

         for (int i = 1; i < points.size(); i++) {
            RoadGeometry.Sample a = points.get(i - 1);
            RoadGeometry.Sample b = points.get(i);

            for (LaneDeck.Strip strip : LaneDeck.strips(piecex.mesh(), a, b)) {
               result.polygon(List.of(strip.al(), strip.ar(), strip.br(), strip.bl()), color);
            }
         }
      }

      HashSet<RoadSurface.Face> seen = new HashSet<>();

      for (JunctionPlanner.Piece piecex : plan.pieces()) {
         for (RoadSurface.Face face : piecex.paint()) {
            if (seen.add(face)) {
               result.marking(face.points(), 0xFF000000 | face.color());
            }
         }
      }

      for (JunctionPlanner.Piece piecex : plan.pieces()) {
         for (RoadStructures.Part part : piecex.structures()) {
            if (!RoadSignals.signal(part) && !RoadPoleModel.support(part)) {
               int color = switch (part.material()) {
                  case GREEN -> -9332389;
                  case SOIL -> -8952761;
                  case CONCRETE -> -5723483;
                  default -> -5194299;
               };
               result.polygon(part.base(), color);
            }
         }
      }

      if (movement) {
         for (JunctionPlanner.Movement move : plan.movements()) {
            if (move.from() == arm && move.lane() == lane) {
               for (int k = 1; k < move.path().size(); k++) {
                  result.line(move.path().get(k - 1), move.path().get(k), -734345);
               }

               int at = move.path().size() * 3 / 4;
               RoadGeometry.V center = move.path().get(at);
               RoadGeometry.V f = move.path().get(at + 1).sub(move.path().get(at - 1)).horizontalUnit();
               result.line(center, center.sub(f.mul(2.0)).add(f.left()), -734345);
               result.line(center, center.sub(f.mul(2.0)).sub(f.left()), -734345);
            }
         }
      }

      ArrayList<JunctionPreview.Label> labels = new ArrayList<>();

      for (JunctionPlanner.Piece piecex : plan.pieces()) {
         if (piecex.arm() >= 0) {
            RoadGeometry.V p = piecex.mesh().first().center();
            labels.add(new JunctionPreview.Label(piecex.arm(), result.x(p), result.y(p)));
         }
      }

      return new JunctionPreview.Image(width, height, result.pixels, List.copyOf(labels));
   }

   private JunctionPreview() {
   }

   public static record Image(int width, int height, int[] pixels, List<JunctionPreview.Label> labels) {
   }

   public static record Label(int arm, int x, int y) {
   }

   static final class Painter {
      final int w;
      final int h;
      final int[] pixels;
      final double scale;
      final double ox;
      final double oz;

      Painter(JunctionPlanner.Plan plan, int w, int h) {
         this.w = w;
         this.h = h;
         this.pixels = new int[w * h];
         Arrays.fill(this.pixels, -14668752);
         double minX = Double.POSITIVE_INFINITY;
         double minZ = minX;
         double maxX = -minX;
         double maxZ = -minX;

         for (JunctionPlanner.Piece piece : plan.pieces()) {
            minX = Math.min(minX, piece.mesh().min().x());
            minZ = Math.min(minZ, piece.mesh().min().z());
            maxX = Math.max(maxX, piece.mesh().max().x());
            maxZ = Math.max(maxZ, piece.mesh().max().z());
         }

         this.scale = Math.min((double)(w - 30) / Math.max(1.0, maxX - minX), (double)(h - 30) / Math.max(1.0, maxZ - minZ));
         this.ox = (double)w / 2.0 - (minX + maxX) * this.scale / 2.0;
         this.oz = (double)h / 2.0 - (minZ + maxZ) * this.scale / 2.0;
      }

      Painter(RoadGeometry.V min, RoadGeometry.V max, int w, int h) {
         this.w = w;
         this.h = h;
         this.pixels = new int[w * h];
         Arrays.fill(this.pixels, -14668752);
         this.scale = Math.min((double)(w - 30) / Math.max(1.0, max.x() - min.x()), (double)(h - 30) / Math.max(1.0, max.z() - min.z()));
         this.ox = (double)w / 2.0 - (min.x() + max.x()) * this.scale / 2.0;
         this.oz = (double)h / 2.0 - (min.z() + max.z()) * this.scale / 2.0;
      }

      int x(RoadGeometry.V p) {
         return (int)Math.round(this.ox + p.x() * this.scale);
      }

      int y(RoadGeometry.V p) {
         return (int)Math.round(this.oz + p.z() * this.scale);
      }

      void pixel(int x, int y, int color) {
         if (x >= 0 && x < this.w && y >= 0 && y < this.h) {
            this.pixels[y * this.w + x] = color;
         }
      }

      void line(RoadGeometry.V a, RoadGeometry.V b, int color) {
         int x = this.x(a);
         int y = this.y(a);
         int dx = this.x(b) - x;
         int dy = this.y(b) - y;
         int n = Math.max(1, Math.max(Math.abs(dx), Math.abs(dy)));

         for (int k = 0; k <= n; k++) {
            this.pixel(x + dx * k / n, y + dy * k / n, color);
         }
      }

      void marking(List<RoadGeometry.V> poly, int color) {
         this.polygon(poly, color);
         double minX = Double.POSITIVE_INFINITY;
         double minZ = minX;
         double maxX = -minX;
         double maxZ = -minX;

         for (RoadGeometry.V p : poly) {
            minX = Math.min(minX, p.x());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
         }

         if (Math.min(maxX - minX, maxZ - minZ) * this.scale < 1.0) {
            RoadGeometry.V a = poly.get(0);
            RoadGeometry.V b = a;
            double longest = 0.0;

            for (RoadGeometry.V p : poly) {
               for (RoadGeometry.V q : poly) {
                  if (p.distance(q) > longest) {
                     longest = p.distance(q);
                     a = p;
                     b = q;
                  }
               }
            }

            this.line(a, b, color);
         }
      }

      void polygon(List<RoadGeometry.V> poly, int color) {
         int minY = this.h;
         int maxY = -1;

         for (RoadGeometry.V p : poly) {
            minY = Math.min(minY, (int)Math.floor(this.oz + p.z() * this.scale - 0.5));
            maxY = Math.max(maxY, (int)Math.ceil(this.oz + p.z() * this.scale));
         }

         for (int row = Math.max(0, minY); row <= Math.min(this.h - 1, maxY); row++) {
            ArrayList<Double> intersections = new ArrayList<>();

            for (int i = 0; i < poly.size(); i++) {
               RoadGeometry.V a = poly.get(i);
               RoadGeometry.V b = poly.get((i + 1) % poly.size());
               double ax = this.ox + a.x() * this.scale;
               double ay = this.oz + a.z() * this.scale;
               double bx = this.ox + b.x() * this.scale;
               double by = this.oz + b.z() * this.scale;
               if (ay <= (double)row + 0.5 && by > (double)row + 0.5 || by <= (double)row + 0.5 && ay > (double)row + 0.5) {
                  intersections.add(ax + ((double)row + 0.5 - ay) * (bx - ax) / (by - ay));
               }
            }

            Collections.sort(intersections);

            for (int ix = 1; ix < intersections.size(); ix += 2) {
               int lo = Math.max(0, (int)Math.floor(intersections.get(ix - 1)));
               int hi = Math.min(this.w - 1, (int)Math.ceil(intersections.get(ix)));

               for (int col = lo; col <= hi; col++) {
                  this.pixels[row * this.w + col] = color;
               }
            }
         }
      }
   }
}
