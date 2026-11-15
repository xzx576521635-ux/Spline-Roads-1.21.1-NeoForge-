package com.sora.splineroads.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RoadSignalModel {
   private static final List<RoadSignalModel.Polygon> HORIZONTAL = load("horizontal");
   private static final List<RoadSignalModel.Polygon> VERTICAL = load("vertical");

   private static List<RoadSignalModel.Polygon> load(String name) {
      try {
         List var14;
         try (InputStream stream = RoadSignalModel.class.getResourceAsStream("/assets/splineroads/models/road/sr_signal_" + name + ".mesh")) {
            if (stream == null) {
               throw new IOException("Missing SR signal: " + name);
            }

            DataInputStream in = new DataInputStream(stream);
            int count = in.readInt();
            if (count < 1 || count > 10000) {
               throw new IOException("Invalid SR signal");
            }

            ArrayList<RoadSignalModel.Polygon> out = new ArrayList<>();

            for (int i = 0; i < count; i++) {
               int lamp = in.readInt();
               int color = in.readInt();
               int n = in.readInt();
               if (n < 3 || n > 8) {
                  throw new IOException("Invalid signal polygon");
               }

               ArrayList<RoadGeometry.V> p = new ArrayList<>();

               for (int k = 0; k < n; k++) {
                  p.add(new RoadGeometry.V((double)in.readFloat(), (double)in.readFloat(), (double)in.readFloat()));
               }

               out.add(new RoadSignalModel.Polygon(lamp, color, List.copyOf(p)));
            }

            var14 = List.copyOf(out);
         }

         return var14;
      } catch (IOException var13) {
         throw new IllegalStateException(var13);
      }
   }

   public static List<RoadSurface.Face> faces(RoadStructures.Part part, boolean lenses, boolean green) {
      return faces(part, lenses, green ? 1 : 0);
   }

   public static List<RoadSurface.Face> faces(RoadStructures.Part part, boolean lenses, int state) {
      return RoadJunctionSignalModel.supports(part) ? RoadJunctionSignalModel.faces(part, lenses, state) : head(part, lenses, state, false, false);
   }

   static List<RoadSurface.Face> head(RoadStructures.Part part, boolean lamps, int state, boolean vertical, boolean arrow) {
      boolean right = part.model().equals("sr:signal_right");
      arrow |= right;
      ArrayList<RoadSurface.Face> local = new ArrayList<>();

      for (RoadSignalModel.Polygon q : vertical ? VERTICAL : HORIZONTAL) {
         if (q.lamp() >= 0 == lamps) {
            boolean on = q.lamp() == state;
            int color = lamps ? (on ? lampColor(state) : 2306350) : q.color();
            local.add(
               new RoadSurface.Face(
                  !vertical && !part.model().equals("sr:centered_horizontal")
                     ? q.points().stream().map(v -> v.add(new RoadGeometry.V(0.0, -0.19690625, 0.0))).toList()
                     : q.points(),
                  arrow && lamps ? 1516063 : color,
                  lamps && on && !arrow
               )
            );
         }
      }

      if (!lamps) {
         if (vertical) {
            OriginalModels.bevel(local, -0.075, 0.17, -0.065, 0.075, 1.23, 0.12, 0.025, 9082525);
         } else {
            double dy = part.model().equals("sr:centered_horizontal") ? 0.0 : -0.19690625;
            OriginalModels.bevel(local, -0.075, 0.15 + dy, -0.48, 0.075, 0.65 + dy, -0.15, 0.025, 9082525);
         }
      }

      if (arrow && lamps) {
         for (int lamp = 0; lamp < 3; lamp++) {
            double y = (lamp == 0 ? 0.11265375 : (lamp == 2 ? 0.07159375 : 0.03053375)) * 9.78;
            int c = lamp == state ? lampColor(state) : 3359035;
            if (!right) {
               arrow(local, new RoadGeometry.V(0.0, y, 0.715), 0.125, c, lamp == state);
            } else {
               ArrayList<RoadSurface.Face> glyph = new ArrayList<>();
               arrow(glyph, new RoadGeometry.V(0.0, y, 0.715), 0.125, c, lamp == state);

               for (RoadSurface.Face f : glyph) {
                  ArrayList<RoadGeometry.V> points = new ArrayList<>(f.points().stream().map(v -> new RoadGeometry.V(-v.x(), v.y(), v.z())).toList());
                  Collections.reverse(points);
                  local.add(new RoadSurface.Face(points, f.color(), f.emissive(), f.texture()));
               }
            }
         }
      }

      RoadGeometry.V front = part.b().sub(part.a()).horizontalUnit();
      RoadGeometry.V side = front.left().mul(-1.0);
      RoadGeometry.V origin = vertical ? part.a() : part.a().add(part.b()).mul(0.5);
      return OriginalModels.transform(local, origin, side, new RoadGeometry.V(0.0, 1.0, 0.0), front);
   }

   static int lampColor(int state) {
      return state == 1 ? 4849538 : (state == 2 ? 16762954 : 16730428);
   }

   static void arrow(List<RoadSurface.Face> out, RoadGeometry.V c, double size, int color, boolean on) {
      OriginalModels.face(
         out,
         List.of(
            c.add(new RoadGeometry.V(-size * 0.1, -size * 0.23, 0.0)),
            c.add(new RoadGeometry.V(size, -size * 0.23, 0.0)),
            c.add(new RoadGeometry.V(size, size * 0.23, 0.0)),
            c.add(new RoadGeometry.V(-size * 0.1, size * 0.23, 0.0))
         ),
         new RoadGeometry.V(0.0, 0.0, 1.0),
         color,
         on,
         RoadSurface.Texture.PLAIN
      );
      OriginalModels.face(
         out,
         List.of(
            c.add(new RoadGeometry.V(-size, 0.0, 0.0)),
            c.add(new RoadGeometry.V(-size * 0.05, -size * 0.85, 0.0)),
            c.add(new RoadGeometry.V(-size * 0.05, size * 0.85, 0.0))
         ),
         new RoadGeometry.V(0.0, 0.0, 1.0),
         color,
         on,
         RoadSurface.Texture.PLAIN
      );
   }

   private RoadSignalModel() {
   }

   private static record Polygon(int lamp, int color, List<RoadGeometry.V> points) {
   }
}
