package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RoadRibbon {
   public static RoadGeometry.Mesh mesh(List<RoadGeometry.Sample> points, RoadGeometry.Settings settings) {
      settings.validate();
      if (points.size() >= 2 && points.size() <= 16384) {
         RoadGeometry.V min = new RoadGeometry.V(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
         RoadGeometry.V max = new RoadGeometry.V(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE);
         List<RoadGeometry.Sample> result = new ArrayList<>();
         RoadGeometry.Sample before = null;
         double d = 0.0;

         for (RoadGeometry.Sample p : points) {
            if (!RoadGeometry.finite(p.center().x(), p.center().y(), p.center().z(), p.left().x(), p.left().y(), p.left().z(), p.halfWidth())
               || Math.abs(p.center().x()) > 2.9999984E7
               || Math.abs(p.center().z()) > 2.9999984E7
               || p.center().y() < -2048.0
               || p.center().y() > 2048.0
               || p.halfWidth() < 0.25
               || p.halfWidth() > RoadLimits.maximumWidth(settings.style()) / 2.0
               || Math.abs(p.left().horizontalLength() - 1.0) > 0.001
               || Math.abs(p.left().y()) > 0.001) {
               throw new IllegalArgumentException("道路采样数据超出有效范围");
            }

            if (before != null) {
               RoadGeometry.V delta = p.center().sub(before.center());
               double h = delta.horizontalLength();
               if (h < 1.0E-8 || Math.abs(delta.y()) > h * 0.505) {
                  throw new IllegalArgumentException("立交坡度超过 50%，请扩大范围或降低层高");
               }

               if (before.left().dot(p.left()) < 0.0) {
                  throw new IllegalArgumentException("立交路径折返");
               }

               for (int side : new int[]{-1, 1}) {
                  if (p.at((double)side * p.halfWidth(), 0.0).sub(before.at((double)side * before.halfWidth(), 0.0)).dot(delta) <= 0.0) {
                     throw new IllegalArgumentException("立交内侧半径不足，请扩大范围");
                  }
               }

               d += delta.distance(new RoadGeometry.V(0.0, 0.0, 0.0));
            }

            result.add(new RoadGeometry.Sample(p.center(), p.left(), d, p.halfWidth()));
            before = p;

            for (int sidex : new int[]{-1, 1}) {
               RoadGeometry.V v = p.at((double)sidex * p.halfWidth(), 0.0);
               min = new RoadGeometry.V(Math.min(min.x(), v.x()), Math.min(min.y(), v.y() - settings.thickness()), Math.min(min.z(), v.z()));
               max = new RoadGeometry.V(Math.max(max.x(), v.x()), Math.max(max.y(), v.y()), Math.max(max.z(), v.z()));
            }
         }

         List<LanePoints.Band> bands = settings.options().lanePoints().bands();
         if (!bands.isEmpty()) {
            ArrayList<LanePoints.Band> mapped = new ArrayList<>();
            int index = 0;

            for (RoadGeometry.Sample p : points) {
               double station = p.distance();

               while (index + 1 < bands.size() - 1 && bands.get(index + 1).station() < station) {
                  index++;
               }

               LanePoints.Band a = bands.get(index);
               LanePoints.Band b = bands.get(Math.min(index + 1, bands.size() - 1));
               double t = b.station() == a.station() ? 0.0 : Math.max(0.0, Math.min(1.0, (station - a.station()) / (b.station() - a.station())));
               mapped.add(new LanePoints.Band(result.get(mapped.size()).distance(), a.low() + (b.low() - a.low()) * t, a.high() + (b.high() - a.high()) * t));
            }

            settings = settings.options(settings.options().lanePoints(settings.options().lanePoints().bands(mapped)));
         }

         return new RoadGeometry.Mesh(List.copyOf(result), settings, min, max, d, false, result.get(result.size() / 2).center());
      } else {
         throw new IllegalArgumentException("道路采样数量超限");
      }
   }

   public static List<RoadGeometry.Mesh> split(RoadGeometry.Mesh mesh, double length) {
      List<RoadGeometry.Mesh> out = new ArrayList<>();
      int first = 0;

      for (int i = 1; i < mesh.samples().size(); i++) {
         if (mesh.samples().get(i).distance() - mesh.samples().get(first).distance() >= length || i == mesh.samples().size() - 1) {
            out.add(mesh(mesh.samples().subList(first, i + 1), mesh.settings()));
            first = i;
         }
      }

      return List.copyOf(out);
   }

   public static void checkSelfIntersections(RoadGeometry.Mesh mesh, double clearance) {
      if (mesh.samples().size() < 96) {
         shortSelfIntersections(mesh, clearance);
      } else {
         List<RoadGeometry.Sample> samples = mesh.samples();
         RoadBoundsIndex index = RoadPathIndex.segments(mesh);

         for (int i = 1; i < samples.size(); i++) {
            RoadPlanningBudget.check();
            RoadGeometry.V a = samples.get(i - 1).center();
            RoadGeometry.V b = samples.get(i).center();

            for (int segment : index.query(RoadPathIndex.segment(a, b), 0.0)) {
               int j = segment + 1;
               if (j >= i + 3) {
                  RoadGeometry.V c = samples.get(j - 1).center();
                  RoadGeometry.V d = samples.get(j).center();
                  if (!(Math.max(a.x(), b.x()) < Math.min(c.x(), d.x()))
                     && !(Math.min(a.x(), b.x()) > Math.max(c.x(), d.x()))
                     && !(Math.max(a.z(), b.z()) < Math.min(c.z(), d.z()))
                     && !(Math.min(a.z(), b.z()) > Math.max(c.z(), d.z()))) {
                     double dx = b.x() - a.x();
                     double dz = b.z() - a.z();
                     double ex = d.x() - c.x();
                     double ez = d.z() - c.z();
                     double det = dx * ez - dz * ex;
                     if (!(Math.abs(det) < 1.0E-10)) {
                        double ox = c.x() - a.x();
                        double oz = c.z() - a.z();
                        double t = (ox * ez - oz * ex) / det;
                        double u = (ox * dz - oz * dx) / det;
                        if (!(t < 0.0) && !(t > 1.0) && !(u < 0.0) && !(u > 1.0)) {
                           double first = a.y() + t * (b.y() - a.y());
                           double second = c.y() + u * (d.y() - c.y());
                           if (Math.abs(first - second) < clearance + mesh.settings().thickness() - 0.05) {
                              throw new IllegalArgumentException("匝道路线上存在净空不足的自交，请调整预设或范围");
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public static double minRadius(RoadGeometry.Mesh mesh) {
      double min = Double.POSITIVE_INFINITY;

      for (int i = 1; i < mesh.samples().size(); i++) {
         RoadGeometry.Sample a = mesh.samples().get(i - 1);
         RoadGeometry.Sample b = mesh.samples().get(i);
         double angle = Math.acos(Math.max(-1.0, Math.min(1.0, a.left().dot(b.left()))));
         if (angle > 1.0E-7) {
            min = Math.min(min, b.center().sub(a.center()).horizontalLength() / angle);
         }
      }

      return min;
   }

   public static RoadGeometry.Node start(RoadGeometry.Mesh mesh) {
      return node(mesh, true);
   }

   public static RoadGeometry.Node end(RoadGeometry.Mesh mesh) {
      return node(mesh, false);
   }

   private static RoadGeometry.Node node(RoadGeometry.Mesh mesh, boolean first) {
      int i = first ? 0 : mesh.samples().size() - 1;
      RoadGeometry.Sample a = mesh.samples().get(i);
      RoadGeometry.Sample b = mesh.samples().get(first ? 1 : i - 1);
      RoadGeometry.V delta = first ? b.center().sub(a.center()) : a.center().sub(b.center());
      return new RoadGeometry.Node(a.center(), RoadPlanner.yaw(a.left().left().mul(-1.0)), delta.y() / delta.horizontalLength());
   }

   private static void shortSelfIntersections(RoadGeometry.Mesh mesh, double clearance) {
      List<RoadGeometry.Sample> samples = mesh.samples();

      for (int i = 1; i < samples.size(); i++) {
         RoadPlanningBudget.check();
         RoadGeometry.V a = samples.get(i - 1).center();
         RoadGeometry.V b = samples.get(i).center();

         for (int j = i + 3; j < samples.size(); j++) {
            RoadGeometry.V c = samples.get(j - 1).center();
            RoadGeometry.V d = samples.get(j).center();
            if (!(Math.max(a.x(), b.x()) < Math.min(c.x(), d.x()))
               && !(Math.min(a.x(), b.x()) > Math.max(c.x(), d.x()))
               && !(Math.max(a.z(), b.z()) < Math.min(c.z(), d.z()))
               && !(Math.min(a.z(), b.z()) > Math.max(c.z(), d.z()))) {
               double dx = b.x() - a.x();
               double dz = b.z() - a.z();
               double ex = d.x() - c.x();
               double ez = d.z() - c.z();
               double det = dx * ez - dz * ex;
               if (!(Math.abs(det) < 1.0E-10)) {
                  double ox = c.x() - a.x();
                  double oz = c.z() - a.z();
                  double t = (ox * ez - oz * ex) / det;
                  double u = (ox * dz - oz * dx) / det;
                  if (!(t < 0.0) && !(t > 1.0) && !(u < 0.0) && !(u > 1.0)) {
                     double first = a.y() + t * (b.y() - a.y());
                     double second = c.y() + u * (d.y() - c.y());
                     if (Math.abs(first - second) < clearance + mesh.settings().thickness() - 0.05) {
                        throw new IllegalArgumentException("匝道路线上存在净空不足的自交，请调整预设或范围");
                     }
                  }
               }
            }
         }
      }
   }

   private RoadRibbon() {
   }
}
