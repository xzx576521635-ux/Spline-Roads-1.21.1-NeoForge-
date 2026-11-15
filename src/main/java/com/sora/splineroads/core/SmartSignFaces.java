package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SmartSignFaces {
   public static boolean front(RoadSurface.Face f, SmartSigns.Layout l) {
      double lo = Double.POSITIVE_INFINITY;
      double hi = Double.NEGATIVE_INFINITY;

      for (RoadGeometry.V p : f.points()) {
         double d = p.sub(l.origin()).dot(l.front());
         lo = Math.min(lo, d);
         hi = Math.max(hi, d);
      }

      return lo >= 0.32999900000000004 && hi - lo < 1.0E-6;
   }

   public static List<RoadSurface.Face> compose(SmartSigns.Layout l) {
      ArrayList<RoadSurface.Face> out = new ArrayList<>();
      ArrayList<SmartSignFaces.Paint> paints = new ArrayList<>();

      for (RoadSurface.Face f : l.faces()) {
         if (!front(f, l)) {
            out.add(f);
         } else {
            List<RoadGeometry.V> points = f.points().stream().map(px -> {
               RoadGeometry.V d = px.sub(l.origin());
               return new RoadGeometry.V(d.dot(l.right()), 0.0, d.y());
            }).toList();
            paints.add(new SmartSignFaces.Paint(points, f.color(), f.points().get(0).sub(l.origin()).dot(l.front())));
         }
      }

      paints.sort(Comparator.comparingDouble(SmartSignFaces.Paint::depth));

      for (int i = 0; i < paints.size(); i++) {
         SmartSignFaces.Paint p = paints.get(i);
         List<List<RoadGeometry.V>> pieces = List.of(p.points());

         for (int j = i + 1; j < paints.size() && !pieces.isEmpty(); j++) {
            SmartSignFaces.Paint cover = paints.get(j);
            if (p.overlaps(cover)) {
               ArrayList<List<RoadGeometry.V>> next = new ArrayList<>();

               for (List<RoadGeometry.V> piece : pieces) {
                  if (RoadSurface.intersect(piece, cover.points()).isEmpty()) {
                     next.add(piece);
                  } else {
                     next.addAll(RoadSurface.subtract(piece, cover.points()));
                  }
               }

               pieces = next;
            }
         }

         for (List<RoadGeometry.V> piecex : pieces) {
            out.add(new RoadSurface.Face(piecex.stream().map(v -> l.at(v.x(), v.z(), 0.33)).toList(), p.color()));
         }
      }

      return List.copyOf(out);
   }

   private SmartSignFaces() {
   }

   private static record Paint(List<RoadGeometry.V> points, int color, double depth, double x0, double x1, double y0, double y1) {
      Paint(List<RoadGeometry.V> p, int color, double depth) {
         this(
            p,
            color,
            depth,
            p.stream().mapToDouble(RoadGeometry.V::x).min().orElse(0.0),
            p.stream().mapToDouble(RoadGeometry.V::x).max().orElse(0.0),
            p.stream().mapToDouble(RoadGeometry.V::z).min().orElse(0.0),
            p.stream().mapToDouble(RoadGeometry.V::z).max().orElse(0.0)
         );
      }

      boolean overlaps(SmartSignFaces.Paint p) {
         return this.x1 > p.x0 + 1.0E-9 && p.x1 > this.x0 + 1.0E-9 && this.y1 > p.y0 + 1.0E-9 && p.y1 > this.y0 + 1.0E-9;
      }
   }
}
