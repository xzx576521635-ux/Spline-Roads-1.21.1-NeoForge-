package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

public final class TollInlineGeometry {
   public static TollPlaza.Spec local(TollPlaza.Spec s) {
      if (s.path().isEmpty()) {
         return s;
      } else {
         double length = s.path().get(s.path().size() - 1).distance();
         return new TollPlaza.Spec(
            new RoadGeometry.Node(new RoadGeometry.V(0.0, 0.0, 0.0), 0.0, 0.0),
            new RoadGeometry.Node(new RoadGeometry.V(0.0, 0.0, length), 0.0, 0.0),
            s.config(),
            s.portA(),
            s.portB()
         );
      }
   }

   private static RoadGeometry.Mesh axis(TollPlaza.Spec s) {
      return RoadSlicing.mesh(s.path(), TollPlaza.port(s, true));
   }

   private static double equipmentLift(RoadGeometry.Mesh source) {
      double middle = source.length() / 2.0;
      double top = RoadSlicing.at(source, middle).center().y();

      for (double d = middle - 6.5; d <= middle + 6.5; d += 0.25) {
         top = Math.max(top, RoadSlicing.at(source, d).center().y());
      }

      return top - RoadSlicing.at(source, middle).center().y();
   }

   public static RoadGeometry.V equipmentAt(TollPlaza.Spec s, double station, double lateral, double up) {
      if (s.curvedDeck() && !s.path().isEmpty()) {
         RoadGeometry.Mesh source = axis(s);
         double middle = source.length() / 2.0;
         RoadGeometry.Sample center = RoadSlicing.at(source, middle);
         return center.at(lateral, -up - equipmentLift(source)).add(center.left().left().mul(middle - station));
      } else {
         return at(s, station, lateral, up);
      }
   }

   public static RoadGeometry.V at(TollPlaza.Spec s, double station, double lateral, double up) {
      RoadGeometry.Sample sample = new TollInlineGeometry.Alignment(s).sample(station);
      return sample.at(lateral, -up);
   }

   public static RoadGeometry.V direction(TollPlaza.Spec s, boolean first) {
      return s.path().isEmpty()
         ? s.b().position().sub(s.a().position()).horizontalUnit()
         : (first ? s.path().get(0) : s.path().get(s.path().size() - 1)).left().left().mul(-1.0);
   }

   public static TollPlanner.Plan plan(TollPlaza.Spec s) {
      TollPlanner.Plan base = TollPlanner.centered(local(s));
      TollInlineGeometry.Alignment alignment = new TollInlineGeometry.Alignment(s);
      RoadGeometry.Mesh source = alignment.source;
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

      for (RoadGeometry.Sample sample : base.mesh().samples()) {
         RoadGeometry.Sample at = alignment.sample(sample.distance());
         samples.add(new RoadGeometry.Sample(at.center(), at.left(), sample.distance(), sample.halfWidth()));
      }

      RoadGeometry.Settings bs = base.mesh().settings();
      RoadGeometry.Mesh mesh = RoadRibbon.mesh(
         samples,
         new RoadGeometry.Settings(bs.mode(), bs.style(), bs.width(), source.settings().thickness(), bs.tension(), bs.arcDegrees())
            .structure(bs.structure())
            .options(bs.options())
      );

      for (int i = 1; i < samples.size(); i++) {
         RoadGeometry.Sample a = samples.get(i - 1);
         RoadGeometry.Sample b = samples.get(i);
         RoadGeometry.V delta = b.center().sub(a.center());
         if (Math.abs(delta.y()) / Math.max(1.0E-9, delta.horizontalLength()) > 0.120001) {
            throw new IllegalArgumentException("收费站断面纵坡最多12%");
         }

         for (int side : new int[]{-1, 1}) {
            if (b.at((double)side * b.halfWidth(), 0.0).sub(a.at((double)side * a.halfWidth(), 0.0)).dot(delta) <= 0.0) {
               throw new IllegalArgumentException("收费站扩宽后弯道内侧折叠，请减少通道或选择更平缓路段");
            }
         }
      }

      if (s.rigidCore()) {
         RoadRibbon.checkSelfIntersections(mesh, 4.0);
      }

      UnaryOperator<RoadGeometry.V> warp = p -> {
         RoadGeometry.Sample at = alignment.sample(p.z());
         return at.at(-p.x(), -p.y());
      };
      double middle = source.length() / 2.0;
      RoadGeometry.Sample center = RoadSlicing.at(source, middle);
      double lift = s.curvedDeck() ? equipmentLift(source) : 0.0;
      UnaryOperator<RoadGeometry.V> equipment = p -> center.at(-p.x(), -p.y() - lift).add(center.left().left().mul(middle - p.z()));
      ArrayList<RoadStructures.Part> parts = new ArrayList<>();

      for (RoadStructures.Part p : base.structures()) {
         UnaryOperator<RoadGeometry.V> transform = s.curvedDeck() && !p.model().equals("sr:toll_outer") && !p.model().equals("sr:toll_median")
            ? equipment
            : warp;
         List<RoadGeometry.V> q = p.base().stream().map(transform).toList();
         RoadGeometry.V a = q.get(0).add(q.get(1)).mul(0.5);
         RoadGeometry.V b = q.get(2).add(q.get(3)).mul(0.5);
         double depth = 0.0;
         if (s.curvedDeck() && p.model().equals("sr:toll_island")) {
            for (double d = p.a().z(); d <= p.b().z() + 1.0E-7; d += 0.25) {
               depth = Math.max(depth, center.center().y() + lift - RoadSlicing.at(source, d).center().y() + 0.02);
            }
         }

         parts.add(
            new RoadStructures.Part(
               a.sub(new RoadGeometry.V(0.0, depth, 0.0)),
               b.sub(new RoadGeometry.V(0.0, depth, 0.0)),
               p.width(),
               p.height() + depth,
               p.pier(),
               p.material(),
               q.get(0).sub(q.get(1)).mul(0.5),
               q.get(3).sub(q.get(2)).mul(0.5),
               p.model(),
               p.heights()
            )
         );
      }

      List<RoadSurface.Face> paint = base.paint()
         .stream()
         .map(f -> new RoadSurface.Face(f.points().stream().map(warp).toList(), f.color(), f.emissive(), f.texture(), f.uv()))
         .toList();
      List<TollPlanner.Unit> units = base.units()
         .stream()
         .map(
            u -> new TollPlanner.Unit(u.direction(), u.index(), u.etc(), (s.curvedDeck() ? equipment : warp).apply(u.center()), u.clearWidth(), u.clearHeight())
         )
         .toList();
      return new TollPlanner.Plan(s, base.lengths(), mesh, List.copyOf(parts), paint, units, base.coreStart(), base.coreEnd());
   }

   private TollInlineGeometry() {
   }

   private static final class Alignment {
      final RoadGeometry.Mesh source;
      final boolean rigid;
      final double middle;
      final double from;
      final double to;
      final double length;
      final RoadGeometry.Sample center;
      final RoadGeometry.V forward;

      Alignment(TollPlaza.Spec s) {
         this.source = TollInlineGeometry.axis(s);
         this.rigid = s.rigidCore() && !s.curvedDeck();
         this.length = this.source.length();
         this.middle = this.length / 2.0;
         this.from = this.middle - 14.0;
         this.to = this.middle + 14.0;
         this.center = RoadSlicing.at(this.source, this.middle);
         this.forward = this.center.left().left().mul(-1.0);
      }

      RoadGeometry.V position(double d) {
         RoadGeometry.V original = RoadSlicing.at(this.source, d).center();
         if (!this.rigid) {
            return original;
         } else {
            RoadGeometry.V p = this.center.center().add(this.forward.mul(d - this.middle));
            if (d < this.from) {
               p = curve(
                  this.source.first().center(),
                  this.center.center().sub(this.forward.mul(14.0)),
                  this.source.first().left().left().mul(-1.0),
                  this.forward,
                  d / this.from
               );
            }

            if (d > this.to) {
               p = curve(
                  this.center.center().add(this.forward.mul(14.0)),
                  this.source.last().center(),
                  this.forward,
                  this.source.last().left().left().mul(-1.0),
                  (d - this.to) / (this.length - this.to)
               );
            }

            double y = this.center.center().y();
            if (d < this.from) {
               double u = d / this.from;
               double g = RoadSlicing.at(this.source, Math.min(0.1, this.length)).center().sub(this.source.first().center()).y() / Math.min(0.1, this.length);
               y = this.source.first().center().y()
                  + (y - this.source.first().center().y()) * u * u * (3.0 - 2.0 * u)
                  + g * this.from * u * (u - 1.0) * (u - 1.0);
            }

            if (d > this.to) {
               double run = this.length - this.to;
               double u = (d - this.to) / run;
               double g = this.source.last().center().sub(RoadSlicing.at(this.source, this.length - Math.min(0.1, this.length)).center()).y()
                  / Math.min(0.1, this.length);
               y += (this.source.last().center().y() - y) * u * u * (3.0 - 2.0 * u) + g * run * u * u * (u - 1.0);
            }

            return new RoadGeometry.V(p.x(), y, p.z());
         }
      }

      private static RoadGeometry.V curve(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V start, RoadGeometry.V end, double t) {
         double h = a.sub(b).horizontalLength() / 3.0;
         double u = 1.0 - t;
         return a.mul(u * u * u).add(a.add(start.mul(h)).mul(3.0 * u * u * t)).add(b.sub(end.mul(h)).mul(3.0 * u * t * t)).add(b.mul(t * t * t));
      }

      RoadGeometry.Sample sample(double d) {
         d = Math.max(0.0, Math.min(this.length, d));
         RoadGeometry.Sample old = RoadSlicing.at(this.source, d);
         if (this.rigid && !(d < 1.0E-8) && !(d > this.length - 1.0E-8)) {
            RoadGeometry.V left = d >= this.from && d <= this.to
               ? this.center.left()
               : this.position(Math.min(this.length, d + 0.01)).sub(this.position(Math.max(0.0, d - 0.01))).horizontalUnit().left();
            return new RoadGeometry.Sample(this.position(d), left, d, old.halfWidth());
         } else {
            return old;
         }
      }
   }
}
