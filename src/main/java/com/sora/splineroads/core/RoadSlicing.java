package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RoadSlicing {
   public static RoadGeometry.Mesh attach(RoadGeometry.Mesh mesh, RoadSlicing.Geometry geometry) {
      return new RoadGeometry.Mesh(
         mesh.samples(),
         mesh.settings(),
         mesh.min(),
         mesh.max(),
         mesh.length(),
         mesh.closed(),
         mesh.controlPoint(),
         mesh.controls(),
         mesh.reference(),
         geometry
      );
   }

   public static RoadGeometry.Mesh mesh(List<RoadGeometry.Sample> points, RoadGeometry.Settings settings) {
      RoadGeometry.Mesh validated = RoadRibbon.mesh(points, settings);
      double previous = -1.0;

      for (RoadGeometry.Sample point : points) {
         if (!Double.isFinite(point.distance()) || point.distance() <= previous) {
            throw new IllegalArgumentException("分割路径里程未递增");
         }

         previous = point.distance();
      }

      return new RoadGeometry.Mesh(List.copyOf(points), settings, validated.min(), validated.max(), points.get(points.size() - 1).distance(), false);
   }

   public static RoadGeometry.Sample at(RoadGeometry.Mesh mesh, double station) {
      List<RoadGeometry.Sample> points = mesh.samples();
      int hi = 1;

      while (hi < points.size() - 1 && points.get(hi).distance() < station) {
         hi++;
      }

      RoadGeometry.Sample a = points.get(hi - 1);
      RoadGeometry.Sample b = points.get(hi);
      double t = Math.max(0.0, Math.min(1.0, (station - a.distance()) / (b.distance() - a.distance())));
      RoadGeometry.V edge = a.left().mul(a.halfWidth() * (1.0 - t)).add(b.left().mul(b.halfWidth() * t));
      return new RoadGeometry.Sample(a.center().mul(1.0 - t).add(b.center().mul(t)), edge.horizontalUnit(), station, edge.horizontalLength());
   }

   public static RoadSlicing.Slice slice(RoadGeometry.Mesh source, double from, double to, RoadGeometry.Settings settings) {
      if (!source.closed() && !(from < 0.0) && !(to > source.length() + 1.0E-7) && !(to - from < 1.0E-7)) {
         RoadGeometry.Sample first = at(source, from);
         RoadGeometry.Sample last = at(source, to);
         ArrayList<RoadGeometry.Sample> points = new ArrayList<>();
         points.add(local(first, from));

         for (RoadGeometry.Sample p : source.samples()) {
            if (p.distance() > from + 1.0E-8 && p.distance() < to - 1.0E-8) {
               points.add(local(p, from));
            }
         }

         points.add(local(last, from));
         ArrayList<RoadSlicing.Patch> patches = new ArrayList<>();
         List<RoadGeometry.Sample> original = source.samples();

         for (int i = 1; i < original.size(); i++) {
            RoadGeometry.Sample a = original.get(i - 1);
            RoadGeometry.Sample b = original.get(i);
            double lo = Math.max(from, a.distance());
            double hi = Math.min(to, b.distance());
            if (!(hi - lo < 1.0E-8)) {
               boolean partial = lo > a.distance() + 1.0E-8 || hi < b.distance() - 1.0E-8;
               boolean existing = source.slicing() != null
                  && source.slicing()
                     .patches()
                     .stream()
                     .anyMatch(px -> Math.abs(px.from() - a.distance()) < 1.0E-7 && Math.abs(px.to() - b.distance()) < 1.0E-7);
               if (partial || existing) {
                  ArrayList<LaneDeck.Strip> strips = new ArrayList<>();

                  for (LaneDeck.Strip strip : LaneDeck.strips(source, a, b)) {
                     for (List<RoadGeometry.V> triangle : List.of(List.of(strip.al(), strip.ar(), strip.br()), List.of(strip.al(), strip.br(), strip.bl()))) {
                        List<RoadGeometry.V> polygon = triangle;
                        if (lo > a.distance() + 1.0E-8) {
                           polygon = clip(triangle, first, true);
                        }

                        if (hi < b.distance() - 1.0E-8) {
                           polygon = clip(polygon, last, false);
                        }

                        for (int j = 1; j < polygon.size() - 1; j++) {
                           addTriangle(strips, List.of(polygon.get(0), polygon.get(j), polygon.get(j + 1)), strip);
                        }
                     }
                  }

                  patches.add(new RoadSlicing.Patch(lo - from, hi - from, strips));
               }
            }
         }

         RoadSlicing.Geometry geometry = new RoadSlicing.Geometry(patches);
         return new RoadSlicing.Slice(attach(mesh(points, settings), geometry), geometry);
      } else {
         throw new IllegalArgumentException("道路分割范围无效");
      }
   }

   private static RoadGeometry.Sample local(RoadGeometry.Sample p, double origin) {
      return new RoadGeometry.Sample(p.center(), p.left(), p.distance() - origin, p.halfWidth());
   }

   private static List<RoadGeometry.V> clip(List<RoadGeometry.V> polygon, RoadGeometry.Sample cut, boolean after) {
      if (polygon.isEmpty()) {
         return polygon;
      } else {
         RoadGeometry.V forward = cut.left().left().mul(after ? -1.0 : 1.0);
         ArrayList<RoadGeometry.V> out = new ArrayList<>();
         RoadGeometry.V a = polygon.get(polygon.size() - 1);
         double da = a.sub(cut.center()).dot(forward);

         for (RoadGeometry.V b : polygon) {
            double db = b.sub(cut.center()).dot(forward);
            if (da >= 0.0 != db >= 0.0) {
               out.add(a.add(b.sub(a).mul(da / (da - db))));
            }

            if (db >= -1.0E-10) {
               out.add(b);
            }

            a = b;
            da = db;
         }

         return out;
      }
   }

   private static void addTriangle(List<LaneDeck.Strip> out, List<RoadGeometry.V> triangle, LaneDeck.Strip original) {
      RoadGeometry.V a = triangle.get(0);
      RoadGeometry.V b = triangle.get(1);
      RoadGeometry.V c = triangle.get(2);
      if (!(Math.abs((b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x())) < 1.0E-10)) {
         for (int i = 0; i < 3; i++) {
            RoadGeometry.V p = triangle.get(i);
            RoadGeometry.V q = triangle.get((i + 1) % 3);
            if (original.lowWall() && on(p, original.ar(), original.br()) && on(q, original.ar(), original.br())
               || original.highWall() && on(p, original.bl(), original.al()) && on(q, original.bl(), original.al())) {
               RoadGeometry.V zero = triangle.get((i + 2) % 3);
               out.add(new LaneDeck.Strip(zero, p, q, q, true, false));
               return;
            }
         }

         out.add(new LaneDeck.Strip(a, b, c, c, false, false));
      }
   }

   private static boolean on(RoadGeometry.V p, RoadGeometry.V a, RoadGeometry.V b) {
      RoadGeometry.V d = b.sub(a);
      double length = d.dot(d);
      if (length < 1.0E-16) {
         return false;
      } else {
         double t = p.sub(a).dot(d) / length;
         return t >= -1.0E-7 && t <= 1.0000001 && p.distance(a.add(d.mul(t))) < 1.0E-7;
      }
   }

   public static List<LaneDeck.Strip> patch(RoadGeometry.Mesh mesh, RoadGeometry.Sample a, RoadGeometry.Sample b) {
      if (mesh.slicing() == null) {
         return null;
      } else {
         for (RoadSlicing.Patch p : mesh.slicing().patches()) {
            if (Math.abs(p.from() - a.distance()) < 1.0E-7 && Math.abs(p.to() - b.distance()) < 1.0E-7) {
               return p.strips();
            }
         }

         return null;
      }
   }

   public static RoadGeometry.Mesh deform(RoadGeometry.Mesh base, RoadGeometry.Settings settings, RoadSlicing.Fragment fragment) {
      RoadAttachments.Data current = settings.options().attachments();
      if (current.points().equals(fragment.applied().points())) {
         return base;
      } else {
         HashMap<UUID, RoadAttachments.Point> before = new HashMap<>();

         for (RoadAttachments.Point p : fragment.applied().points()) {
            before.put(p.id(), p);
         }

         HashMap<UUID, RoadAttachments.Point> after = new HashMap<>();

         for (RoadAttachments.Point p : current.points()) {
            after.put(p.id(), p);
         }

         LinkedHashSet<UUID> ids = new LinkedHashSet<>(before.keySet());
         ids.addAll(after.keySet());
         ArrayList<RoadAttachments.Point> controls = new ArrayList<>();

         for (UUID id : ids) {
            RoadAttachments.Point a = before.get(id);
            RoadAttachments.Point b = after.get(id);
            RoadAttachments.Point point = b == null ? a : b;
            if (a != null && a.controlled() || b != null && b.controlled()) {
               RoadGeometry.V anchor = at(base, RoadAttachments.station(base, point.anchor())).center();
               RoadGeometry.V old = a != null && a.controlled() ? a.position() : anchor;
               RoadGeometry.V target = b != null && b.controlled() ? b.position() : (a == null ? anchor : a.anchor());
               controls.add(new RoadAttachments.Point(id, anchor.add(target.sub(old)), anchor, true, point.origin()));
            }
         }

         RoadGeometry.Settings temp = settings.options(settings.options().attachments(new RoadAttachments.Data(controls, current.spans())));
         RoadGeometry.Mesh result = RoadAttachments.deform(base, temp);
         return new RoadGeometry.Mesh(result.samples(), settings, result.min(), result.max(), result.length(), false);
      }
   }

   private RoadSlicing() {
   }

   public static enum Connection {
      CONTINUOUS,
      JUNCTION;
   }

   public static record Fragment(
      UUID source,
      double origin,
      double total,
      RoadSlicing.Connection connection,
      RoadAttachments.Data applied,
      RoadSlicing.Geometry geometry,
      List<RoadStructures.Part> supports
   ) {
      public Fragment(UUID source, double origin, double total, RoadSlicing.Connection connection, RoadAttachments.Data applied, RoadSlicing.Geometry geometry) {
         this(source, origin, total, connection, applied, geometry, List.of());
      }

      public Fragment(
         UUID source,
         double origin,
         double total,
         RoadSlicing.Connection connection,
         RoadAttachments.Data applied,
         RoadSlicing.Geometry geometry,
         List<RoadStructures.Part> supports
      ) {
         supports = List.copyOf(supports);
         if (supports.size() > 50000) {
            throw new IllegalArgumentException("分割桥墩数量过多");
         } else {
            Objects.requireNonNull(source);
            Objects.requireNonNull(connection);
            Objects.requireNonNull(applied);
            if (RoadGeometry.finite(origin, total) && !(origin < 0.0) && !(total <= 0.0) && !(origin >= total)) {
               this.source = source;
               this.origin = origin;
               this.total = total;
               this.connection = connection;
               this.applied = applied;
               this.geometry = geometry;
               this.supports = supports;
            } else {
               throw new IllegalArgumentException("分割路径范围无效");
            }
         }
      }

      public RoadSlicing.Fragment geometry(RoadSlicing.Geometry value) {
         return new RoadSlicing.Fragment(this.source, this.origin, this.total, this.connection, this.applied, value, this.supports);
      }
   }

   public static record Geometry(List<RoadSlicing.Patch> patches, boolean clipped) {
      public Geometry(List<RoadSlicing.Patch> patches) {
         this(patches, false);
      }

      public Geometry(List<RoadSlicing.Patch> patches, boolean clipped) {
         patches = List.copyOf(patches);
         if (patches.size() > (clipped ? 8192 : 4)) {
            throw new IllegalArgumentException("分割切面数量无效");
         } else {
            this.patches = patches;
            this.clipped = clipped;
         }
      }
   }

   public static record Patch(double from, double to, List<LaneDeck.Strip> strips, boolean clipped) {
      public Patch(double from, double to, List<LaneDeck.Strip> strips) {
         this(from, to, strips, false);
      }

      public Patch(double from, double to, List<LaneDeck.Strip> strips, boolean clipped) {
         strips = List.copyOf(strips);
         if (RoadGeometry.finite(from, to) && !(from < 0.0) && !(to <= from) && strips.size() <= (clipped ? 4096 : 16)) {
            this.from = from;
            this.to = to;
            this.strips = strips;
            this.clipped = clipped;
         } else {
            throw new IllegalArgumentException("分割切面无效");
         }
      }
   }

   public static record Slice(RoadGeometry.Mesh mesh, RoadSlicing.Geometry geometry) {
   }
}
