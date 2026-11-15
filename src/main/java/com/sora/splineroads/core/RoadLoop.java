package com.sora.splineroads.core;

public final class RoadLoop {
   private final RoadGeometry.V start;
   private final RoadGeometry.V end;
   private final double radius;
   private final double turn;
   private final double length;
   private final RoadLoop.Path path;

   public RoadLoop(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V da, RoadGeometry.V db, double radius, boolean right) {
      this.start = a;
      this.end = b;
      this.radius = radius;
      this.turn = right ? 1.0 : -1.0;
      RoadLoop.Path same = this.candidate(a, b, da, db, this.turn);
      RoadLoop.Path opposite = this.candidate(a, b, da, db, -this.turn);
      this.path = opposite != null && opposite.length(radius) < same.length(radius) - 1.0E-7 ? opposite : same;
      this.length = this.path.length(radius);
      if (this.length < 1.0E-7) {
         throw new IllegalArgumentException("环绕端口重合，请调整半径或端点");
      }
   }

   private RoadLoop.Path candidate(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V da, RoadGeometry.V db, double second) {
      RoadGeometry.V c1 = a.add(da.horizontalUnit().left().mul(this.turn * this.radius));
      RoadGeometry.V c2 = b.add(db.horizontalUnit().left().mul(second * this.radius));
      RoadGeometry.V delta = c2.sub(c1);
      double d = delta.horizontalLength();
      double angle1 = Math.atan2(a.z() - c1.z(), a.x() - c1.x());
      double finish = Math.atan2(b.z() - c2.z(), b.x() - c2.x());
      if (d < 1.0E-7) {
         return second != this.turn ? null : new RoadLoop.Path(c1, c2, b, b, angle1, finish, positive(this.turn * (finish - angle1)), 0.0, 0.0, second);
      } else {
         double offset = (second - this.turn) * this.radius;
         if (Math.abs(offset) > d + 1.0E-7) {
            return null;
         } else {
            double heading = Math.atan2(delta.z(), delta.x()) - Math.asin(Math.max(-1.0, Math.min(1.0, offset / d)));
            RoadGeometry.V forward = new RoadGeometry.V(Math.cos(heading), 0.0, Math.sin(heading));
            RoadGeometry.V r1 = forward.left().mul(-this.turn * this.radius);
            RoadGeometry.V r2 = forward.left().mul(-second * this.radius);
            RoadGeometry.V t1 = c1.add(r1);
            RoadGeometry.V t2 = c2.add(r2);
            double angleT1 = Math.atan2(r1.z(), r1.x());
            double angleT2 = Math.atan2(r2.z(), r2.x());
            return new RoadLoop.Path(
               c1,
               c2,
               t1,
               t2,
               angle1,
               angleT2,
               positive(this.turn * (angleT1 - angle1)),
               positive(second * (finish - angleT2)),
               t2.sub(t1).horizontalLength(),
               second
            );
         }
      }
   }

   private static double positive(double angle) {
      double v = angle % (Math.PI * 2);
      if (v < -1.0E-10) {
         v += Math.PI * 2;
      }

      return Math.max(0.0, v);
   }

   public double length() {
      return this.length;
   }

   public RoadGeometry.V point(double t) {
      if (t <= 0.0) {
         return this.start;
      } else if (t >= 1.0) {
         return this.end;
      } else {
         double d = t * this.length;
         if (d < this.path.arc1() * this.radius) {
            return this.circle(this.path.c1(), this.path.angle1() + this.turn * d / this.radius);
         } else {
            d -= this.path.arc1() * this.radius;
            return d < this.path.straight()
               ? this.path.t1().add(this.path.t2().sub(this.path.t1()).mul(d / this.path.straight()))
               : this.circle(this.path.c2(), this.path.angle2() + this.path.secondTurn() * (d - this.path.straight()) / this.radius);
         }
      }
   }

   private RoadGeometry.V circle(RoadGeometry.V center, double angle) {
      return new RoadGeometry.V(center.x() + this.radius * Math.cos(angle), 0.0, center.z() + this.radius * Math.sin(angle));
   }

   private static record Path(
      RoadGeometry.V c1,
      RoadGeometry.V c2,
      RoadGeometry.V t1,
      RoadGeometry.V t2,
      double angle1,
      double angle2,
      double arc1,
      double arc2,
      double straight,
      double secondTurn
   ) {
      double length(double radius) {
         return radius * (this.arc1 + this.arc2) + this.straight;
      }
   }
}
