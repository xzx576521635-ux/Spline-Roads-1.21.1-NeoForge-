package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlanner {
   public static String name(RoadGeometry.Mode m) {
      return switch (m) {
         case AUTO -> "智能";
         case STRAIGHT -> "直线";
         case CURVE -> "平滑曲线";
         case ARC -> "圆弧";
         case RING -> "圆环";
      };
   }

   public static double yaw(RoadGeometry.V direction) {
      return Math.toDegrees(Math.atan2(-direction.x(), direction.z()));
   }

   public static RoadGeometry.Settings mode(RoadGeometry.Settings s, RoadGeometry.Mode mode, double angle) {
      return new RoadGeometry.Settings(
         mode,
         s.style(),
         s.width(),
         s.thickness(),
         s.tension(),
         angle,
         s.startWidth(),
         s.endWidth(),
         s.structure(),
         s.taperVersion(),
         s.rampTurn(),
         s.options()
      );
   }

   private static double angle(RoadGeometry.V a, RoadGeometry.V b) {
      return Math.atan2(a.x() * b.z() - a.z() * b.x(), a.dot(b));
   }

   private static RoadGeometry.Node heading(RoadGeometry.Node n, RoadGeometry.V direction, double grade) {
      return new RoadGeometry.Node(n.position(), yaw(direction), grade);
   }

   public static RoadPlanner.Plan plan(RoadPlanner.Hint ah, RoadPlanner.Hint bh, RoadGeometry.Settings request) {
      request.validate();
      RoadGeometry.Node a = ah.node;
      RoadGeometry.Node b = bh.node;
      List<RoadProfile.Control> controls = request.options().routing().controls();
      if (!controls.isEmpty()) {
         if (!ah.headingLocked()) {
            a = heading(a, controls.get(0).position().sub(a.position()), ah.gradeLocked() ? a.grade() : 0.0);
         }

         if (!bh.headingLocked()) {
            b = heading(b, b.position().sub(controls.get(controls.size() - 1).position()), bh.gradeLocked() ? b.grade() : 0.0);
         }

         return make(a, b, mode(request, RoadGeometry.Mode.CURVE, request.arcDegrees()), request.options().routing().through() ? "经过点平滑曲线" : "多控制点贝塞尔曲线");
      } else if (request.mode() != RoadGeometry.Mode.AUTO) {
         return make(a, b, request, "手动选择");
      } else {
         RoadGeometry.V chord = b.position().sub(a.position()).horizontalUnit();
         RoadGeometry.V da = a.direction();
         RoadGeometry.V db = b.direction();
         double ga = ah.gradeLocked ? a.grade() : 0.0;
         double gb = bh.gradeLocked ? b.grade() : 0.0;
         if (!ah.headingLocked && da.dot(chord) < 0.0) {
            da = da.mul(-1.0);
         }

         if (!bh.headingLocked && db.dot(chord) < 0.0) {
            db = db.mul(-1.0);
         }

         RoadGeometry.V delta = b.position().sub(a.position());
         boolean axis = Math.abs(delta.x()) < 1.0E-6 || Math.abs(delta.z()) < 1.0E-6;
         boolean forwardA = !ah.headingLocked || da.dot(chord) > 0.99999999;
         boolean forwardB = !bh.headingLocked || db.dot(chord) > 0.99999999;
         boolean casualAlignment = da.dot(chord) > Math.cos(Math.toRadians(15.0)) && db.dot(chord) > Math.cos(Math.toRadians(15.0));
         if (forwardA && forwardB && (axis || ah.headingLocked || bh.headingLocked || casualAlignment)) {
            RoadPlanner.Plan straight = attempt(heading(a, chord, ga), heading(b, chord, gb), mode(request, RoadGeometry.Mode.STRAIGHT, 90.0), "位置对齐，优先采用直线");
            if (straight != null) {
               return straight;
            }
         }

         double angleA = 2.0 * angle(da, chord);
         double angleB = 2.0 * angle(chord, db);
         List<Double> arcs = new ArrayList<>();
         if (ah.headingLocked && !bh.headingLocked) {
            arcs.add(angleA);
         } else if (!ah.headingLocked && bh.headingLocked) {
            arcs.add(angleB);
         } else if (Math.abs(angleA - angleB) < (ah.headingLocked && bh.headingLocked ? 1.0E-7 : Math.toRadians(16.0))) {
            arcs.add((angleA + angleB) / 2.0);
         }

         for (double theta : arcs) {
            if (!(Math.abs(theta) < Math.toRadians(5.0)) && !(Math.abs(theta) > Math.toRadians(300.0))) {
               RoadPlanner.Plan p = attempt(heading(a, da, ga), heading(b, db, gb), mode(request, RoadGeometry.Mode.ARC, Math.toDegrees(theta)), "根据端点切线拟合圆弧");
               if (p != null && matches(p, ah, bh)) {
                  return p;
               }
            }
         }

         if (ah.headingLocked && !bh.headingLocked) {
            db = chord;
         }

         if (!ah.headingLocked && bh.headingLocked) {
            da = chord;
         }

         for (double tension : new double[]{request.tension(), 0.5, 0.65, 0.25, 0.18, 0.85, 1.1}) {
            RoadGeometry.Settings curve = new RoadGeometry.Settings(
               RoadGeometry.Mode.CURVE,
               request.style(),
               request.width(),
               request.thickness(),
               tension,
               90.0,
               request.startWidth(),
               request.endWidth(),
               request.structure(),
               request.taperVersion(),
               request.rampTurn(),
               request.options()
            );
            RoadPlanner.Plan p = attempt(heading(a, da, ga), heading(b, db, gb), curve, "自动衔接已有方向与坡度");
            if (p != null && matches(p, ah, bh)) {
               return p;
            }
         }

         if (!ah.headingLocked && !bh.headingLocked) {
            double grade = (b.position().y() - a.position().y()) / b.position().sub(a.position()).horizontalLength();
            RoadPlanner.Plan p = attempt(
               heading(a, chord, ah.gradeLocked ? ga : grade),
               heading(b, chord, bh.gradeLocked ? gb : grade),
               mode(request, RoadGeometry.Mode.STRAIGHT, 90.0),
               "空间不足，自动采用直线坡道"
            );
            if (p != null) {
               return p;
            }
         }

         throw new IllegalArgumentException("空间不足以平滑连接，请把终点放远一些，或展开微调");
      }
   }

   private static boolean matches(RoadPlanner.Plan p, RoadPlanner.Hint a, RoadPlanner.Hint b) {
      RoadGeometry.V da = p.mesh.first().left().left().mul(-1.0);
      RoadGeometry.V db = p.mesh.last().left().left().mul(-1.0);
      return (!a.headingLocked || da.dot(a.node.direction()) > 0.99999999) && (!b.headingLocked || db.dot(b.node.direction()) > 0.99999999);
   }

   private static RoadPlanner.Plan attempt(RoadGeometry.Node a, RoadGeometry.Node b, RoadGeometry.Settings s, String reason) {
      try {
         return make(a, b, s, reason);
      } catch (IllegalArgumentException var5) {
         return null;
      }
   }

   private static RoadPlanner.Plan make(RoadGeometry.Node a, RoadGeometry.Node b, RoadGeometry.Settings s, String reason) {
      RoadGeometry.Mesh mesh = RoadGeometry.build(a, b, s);
      RoadGeometry.Node start = heading(a, mesh.first().left().left().mul(-1.0), a.grade());
      RoadGeometry.Node end = heading(
         b,
         s.mode() == RoadGeometry.Mode.RING ? mesh.samples().get((mesh.samples().size() - 1) / 2).left().left().mul(-1.0) : mesh.last().left().left().mul(-1.0),
         b.grade()
      );
      return new RoadPlanner.Plan(start, end, s, mesh, reason);
   }

   private RoadPlanner() {
   }

   public static record Hint(RoadGeometry.Node node, boolean headingLocked, boolean gradeLocked, boolean linked) {
      public static RoadPlanner.Hint free(RoadGeometry.Node n) {
         return new RoadPlanner.Hint(n, false, false, false);
      }
   }

   public static record Plan(RoadGeometry.Node start, RoadGeometry.Node end, RoadGeometry.Settings settings, RoadGeometry.Mesh mesh, String reason) {
   }
}
