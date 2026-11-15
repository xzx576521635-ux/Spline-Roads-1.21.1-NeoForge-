package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OriginalModels {
   public static RoadGeometry.V cross(RoadGeometry.V a, RoadGeometry.V b) {
      return new RoadGeometry.V(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
   }

   public static void face(
      List<RoadSurface.Face> out, List<RoadGeometry.V> points, RoadGeometry.V outward, int color, boolean light, RoadSurface.Texture texture
   ) {
      ArrayList<RoadGeometry.V> p = new ArrayList<>(points);
      if (cross(p.get(1).sub(p.get(0)), p.get(2).sub(p.get(0))).dot(outward) < 0.0) {
         Collections.reverse(p);
      }

      out.add(new RoadSurface.Face(List.copyOf(p), color, light, texture));
   }

   public static void box(List<RoadSurface.Face> out, double x0, double y0, double z0, double x1, double y1, double z1, int color) {
      prism(
         out,
         List.of(new RoadGeometry.V(x0, 0.0, z0), new RoadGeometry.V(x1, 0.0, z0), new RoadGeometry.V(x1, 0.0, z1), new RoadGeometry.V(x0, 0.0, z1)),
         y0,
         y1,
         color
      );
   }

   public static void bevel(List<RoadSurface.Face> out, double x0, double y0, double z0, double x1, double y1, double z1, double bevel, int color) {
      double b = Math.min(bevel, Math.min(x1 - x0, z1 - z0) / 2.0);
      prism(
         out,
         List.of(
            new RoadGeometry.V(x0 + b, 0.0, z0),
            new RoadGeometry.V(x1 - b, 0.0, z0),
            new RoadGeometry.V(x1, 0.0, z0 + b),
            new RoadGeometry.V(x1, 0.0, z1 - b),
            new RoadGeometry.V(x1 - b, 0.0, z1),
            new RoadGeometry.V(x0 + b, 0.0, z1),
            new RoadGeometry.V(x0, 0.0, z1 - b),
            new RoadGeometry.V(x0, 0.0, z0 + b)
         ),
         y0,
         y1,
         color
      );
   }

   public static void prism(List<RoadSurface.Face> out, List<RoadGeometry.V> shape, double bottom, double top, int color) {
      RoadGeometry.V center = shape.stream().reduce(new RoadGeometry.V(0.0, 0.0, 0.0), RoadGeometry.V::add).mul(1.0 / (double)shape.size());
      List<RoadGeometry.V> lo = shape.stream().map(v -> new RoadGeometry.V(v.x(), bottom, v.z())).toList();
      List<RoadGeometry.V> hi = shape.stream().map(v -> new RoadGeometry.V(v.x(), top, v.z())).toList();
      face(out, lo, new RoadGeometry.V(0.0, -1.0, 0.0), color, false, RoadSurface.Texture.PLAIN);
      face(out, hi, new RoadGeometry.V(0.0, 1.0, 0.0), color, false, RoadSurface.Texture.PLAIN);

      for (int i = 0; i < shape.size(); i++) {
         int j = (i + 1) % shape.size();
         face(
            out,
            List.of(lo.get(i), lo.get(j), hi.get(j), hi.get(i)),
            shape.get(i).add(shape.get(j)).mul(0.5).sub(center),
            color,
            false,
            RoadSurface.Texture.PLAIN
         );
      }
   }

   public static void cylinder(List<RoadSurface.Face> out, RoadGeometry.V a, RoadGeometry.V b, double ra, double rb, int segments, int color) {
      RoadGeometry.V delta = b.sub(a);
      double length = a.distance(b);
      if (!(length < 1.0E-8)) {
         RoadGeometry.V axis = delta.mul(1.0 / length);
         RoadGeometry.V u = cross(axis, Math.abs(axis.y()) < 0.9 ? new RoadGeometry.V(0.0, 1.0, 0.0) : new RoadGeometry.V(1.0, 0.0, 0.0));
         u = u.mul(1.0 / Math.sqrt(u.dot(u)));
         RoadGeometry.V v = cross(axis, u);
         ArrayList<RoadGeometry.V> lo = new ArrayList<>();
         ArrayList<RoadGeometry.V> hi = new ArrayList<>();

         for (int i = 0; i < segments; i++) {
            double angle = (Math.PI * 2) * (double)i / (double)segments;
            RoadGeometry.V radial = u.mul(Math.cos(angle)).add(v.mul(Math.sin(angle)));
            lo.add(a.add(radial.mul(ra)));
            hi.add(b.add(radial.mul(rb)));
         }

         face(out, lo, axis.mul(-1.0), color, false, RoadSurface.Texture.PLAIN);
         face(out, hi, axis, color, false, RoadSurface.Texture.PLAIN);

         for (int i = 0; i < segments; i++) {
            int j = (i + 1) % segments;
            face(out, List.of(lo.get(i), lo.get(j), hi.get(j), hi.get(i)), lo.get(i).add(lo.get(j)).mul(0.5).sub(a), color, false, RoadSurface.Texture.PLAIN);
         }
      }
   }

   public static List<RoadSurface.Face> transform(List<RoadSurface.Face> source, RoadGeometry.V origin, RoadGeometry.V x, RoadGeometry.V y, RoadGeometry.V z) {
      boolean mirror = cross(x, y).dot(z) < 0.0;
      ArrayList<RoadSurface.Face> out = new ArrayList<>();

      for (RoadSurface.Face f : source) {
         ArrayList<RoadGeometry.V> p = new ArrayList<>();

         for (RoadGeometry.V v : f.points()) {
            p.add(origin.add(x.mul(v.x())).add(y.mul(v.y())).add(z.mul(v.z())));
         }

         if (mirror) {
            Collections.reverse(p);
         }

         ArrayList<RoadSurface.UV> uv = new ArrayList<>(f.uv());
         if (mirror) {
            Collections.reverse(uv);
         }

         out.add(new RoadSurface.Face(List.copyOf(p), f.color(), f.emissive(), f.texture(), List.copyOf(uv)));
      }

      return List.copyOf(out);
   }

   private OriginalModels() {
   }
}
