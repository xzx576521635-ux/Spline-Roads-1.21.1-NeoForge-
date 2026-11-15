package com.sora.splineroads.core;

import java.util.List;

public final class RoadMergeArrow {
   public static List<RoadJunction.Paint> flat(RoadGeometry.V origin, RoadGeometry.V forward, RoadGeometry.V left, double lateral, double width, double side) {
      RoadGeometry.V toward = left.mul(side);
      double shift = Math.min(0.95, width * 0.22);
      RoadGeometry.V center = origin.add(left.mul(lateral - side * shift * 0.5));
      RoadGeometry.V tail = center.sub(forward.mul(2.2));
      RoadGeometry.V bend = center.add(forward.mul(0.2));
      RoadGeometry.V base = bend.add(forward.mul(1.55)).add(toward.mul(shift));
      RoadGeometry.V aim = base.sub(bend).horizontalUnit();
      RoadGeometry.V cross = aim.left();
      RoadGeometry.V normal = forward.left().add(cross).horizontalUnit();
      RoadGeometry.V miter = normal.mul(0.11 / Math.max(0.5, normal.dot(forward.left())));
      RoadGeometry.V a = tail.add(forward.left().mul(0.11));
      RoadGeometry.V b = tail.sub(forward.left().mul(0.11));
      RoadGeometry.V c = bend.add(miter);
      RoadGeometry.V d = bend.sub(miter);
      RoadGeometry.V e = base.add(cross.mul(0.11));
      RoadGeometry.V g = base.sub(cross.mul(0.11));
      List<RoadJunction.Paint> group = List.of(
         new RoadJunction.Paint(List.of(a, b, d, c), 15593186),
         new RoadJunction.Paint(List.of(c, d, g, e), 15593186),
         new RoadJunction.Paint(List.of(base.add(aim.mul(1.1)), base.sub(cross.mul(0.52)), base.add(cross.mul(0.52))), 15593186)
      );
      double min = group.stream().flatMap(p -> p.points().stream()).mapToDouble(p -> p.sub(origin).dot(left)).min().orElse(0.0);
      double max = group.stream().flatMap(p -> p.points().stream()).mapToDouble(p -> p.sub(origin).dot(left)).max().orElse(0.0);
      RoadGeometry.V correction = left.mul(lateral - (min + max) / 2.0);
      return group.stream().map(p -> new RoadJunction.Paint(p.points().stream().map(v -> v.add(correction)).toList(), p.color())).toList();
   }

   public static List<RoadJunction.Paint> local(double width, double side) {
      return flat(new RoadGeometry.V(0.0, 0.0, 0.0), new RoadGeometry.V(1.0, 0.0, 0.0), new RoadGeometry.V(0.0, 0.0, 1.0), 0.0, width, side);
   }

   private RoadMergeArrow() {
   }
}
