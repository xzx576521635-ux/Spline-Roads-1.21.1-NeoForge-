package com.sora.splineroads.core;

final class RoadProjection {
   private final RoadGeometry.Mesh mesh;
   private final RoadProjection.Branch root;

   RoadProjection(RoadGeometry.Mesh mesh) {
      this.mesh = mesh;
      this.root = new RoadProjection.Branch(1, mesh.samples().size());
   }

   RoadQueries.Projection project(RoadGeometry.V point, boolean horizontal) {
      RoadProjection.Hit hit = new RoadProjection.Hit();
      this.visit(this.root, point, horizontal, hit);
      RoadGeometry.Sample a = this.mesh.samples().get(hit.segment - 1);
      RoadGeometry.Sample b = this.mesh.samples().get(hit.segment);
      RoadGeometry.V d = b.center().sub(a.center());
      RoadGeometry.V center = a.center().add(d.mul(hit.t));
      RoadGeometry.V left = a.left().add(b.left().sub(a.left()).mul(hit.t)).horizontalUnit();
      return new RoadQueries.Projection(
         new RoadGeometry.Sample(center, left, a.distance() + (b.distance() - a.distance()) * hit.t, a.halfWidth() + (b.halfWidth() - a.halfWidth()) * hit.t),
         d.mul(1.0 / d.horizontalLength()),
         point.sub(center).dot(left),
         point.sub(center).horizontalLength()
      );
   }

   private void visit(RoadProjection.Branch branch, RoadGeometry.V p, boolean horizontal, RoadProjection.Hit hit) {
      if (!(branch.bound(p, horizontal) > hit.score + 1.0E-10)) {
         if (branch.a != null) {
            boolean first = branch.a.bound(p, horizontal) <= branch.b.bound(p, horizontal);
            this.visit(first ? branch.a : branch.b, p, horizontal, hit);
            this.visit(first ? branch.b : branch.a, p, horizontal, hit);
         } else {
            for (int i = branch.from; i < branch.to; i++) {
               RoadGeometry.V a = this.mesh.samples().get(i - 1).center();
               RoadGeometry.V b = this.mesh.samples().get(i).center();
               double dx = b.x() - a.x();
               double dz = b.z() - a.z();
               double t = Math.max(0.0, Math.min(1.0, ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / (dx * dx + dz * dz)));
               double x = p.x() - (a.x() + dx * t);
               double z = p.z() - (a.z() + dz * t);
               double y = horizontal ? 0.0 : p.y() - (a.y() + (b.y() - a.y()) * t);
               double score = x * x + z * z + y * y;
               if (score < hit.score || score == hit.score && i < hit.segment) {
                  hit.score = score;
                  hit.t = t;
                  hit.segment = i;
               }
            }
         }
      }
   }

   private final class Branch {
      final int from;
      final int to;
      final double minX;
      final double minY;
      final double minZ;
      final double maxX;
      final double maxY;
      final double maxZ;
      final RoadProjection.Branch a;
      final RoadProjection.Branch b;

      Branch(int from, int to) {
         this.from = from;
         this.to = to;
         double x0 = Double.POSITIVE_INFINITY;
         double y0 = x0;
         double z0 = x0;
         double x1 = -x0;
         double y1 = x1;
         double z1 = x1;

         for (int i = from - 1; i < to; i++) {
            RoadGeometry.V p = RoadProjection.this.mesh.samples().get(i).center();
            x0 = Math.min(x0, p.x());
            y0 = Math.min(y0, p.y());
            z0 = Math.min(z0, p.z());
            x1 = Math.max(x1, p.x());
            y1 = Math.max(y1, p.y());
            z1 = Math.max(z1, p.z());
         }

         this.minX = x0;
         this.minY = y0;
         this.minZ = z0;
         this.maxX = x1;
         this.maxY = y1;
         this.maxZ = z1;
         int mid = (from + to) / 2;
         this.a = to - from > 8 ? RoadProjection.this.new Branch(from, mid) : null;
         this.b = this.a == null ? null : RoadProjection.this.new Branch(mid, to);
      }

      double bound(RoadGeometry.V p, boolean horizontal) {
         double x = Math.max(0.0, Math.max(this.minX - p.x(), p.x() - this.maxX));
         double z = Math.max(0.0, Math.max(this.minZ - p.z(), p.z() - this.maxZ));
         double y = horizontal ? 0.0 : Math.max(0.0, Math.max(this.minY - p.y(), p.y() - this.maxY));
         return x * x + y * y + z * z;
      }
   }

   private static final class Hit {
      double score = Double.POSITIVE_INFINITY;
      double t;
      int segment = Integer.MAX_VALUE;
   }
}
