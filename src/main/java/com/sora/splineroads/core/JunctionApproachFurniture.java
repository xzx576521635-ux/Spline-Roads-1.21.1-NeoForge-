package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class JunctionApproachFurniture {
   public static double medianEnd(JunctionSpec spec, int arm, RoadGeometry.Mesh mesh) {
      JunctionSpec.Arm a = spec.arms().get(arm);
      double ring = spec.kind() == JunctionSpec.Kind.ROUNDABOUT
         ? spec.islandRadius() + (double)spec.ringLanes() * spec.ringLaneWidth() - mesh.last().center().sub(spec.center()).horizontalLength()
         : 0.0;
      return a.crosswalk() ? mesh.length() - ring - a.crossingSetback() - a.crossingWidth() - 0.5 : mesh.length() - ring - 1.0;
   }

   public static List<RoadStructures.Part> plan(JunctionSpec spec, int index, RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      JunctionSpec.Arm arm = spec.arms().get(index);
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      double end = medianEnd(spec, index, mesh);
      double crossLo = end + 0.5;
      double crossHi = crossLo + arm.crossingWidth();
      TreeSet<Double> stations = new TreeSet<>();

      for (double d = 0.0; d < mesh.length(); d += 0.5) {
         stations.add(d);
      }

      for (double d : new double[]{end, crossLo, crossHi, mesh.length()}) {
         if (d > 0.0 && d <= mesh.length()) {
            stations.add(d);
         }
      }

      ArrayList<Double> list = new ArrayList<>(stations);

      for (int i = 1; i < list.size(); i++) {
         if (!(list.get(i) - list.get(i - 1) < 1.0E-6)) {
            RoadGeometry.Sample a = RoadStructures.sample(mesh, list.get(i - 1));
            RoadGeometry.Sample b = RoadStructures.sample(mesh, list.get(i));
            RoadGeometry.Sample mid = RoadStructures.sample(mesh, (a.distance() + b.distance()) / 2.0);
            boolean raised = RoadStreetscape.bridge(mesh, mid, ground);
            boolean tunnel = RoadStreetscape.underground(mesh, mid.distance());
            if (a.distance() < end - 1.0E-7 && arm.median() > 0.2) {
               double ca = JunctionPlanner.approachMedian(arm, mesh, a, true, spec.leftTraffic());
               double cb = JunctionPlanner.approachMedian(arm, mesh, b, true, spec.leftTraffic());
               double wa = JunctionPlanner.approachMedian(arm, mesh, a, false, spec.leftTraffic());
               double wb = JunctionPlanner.approachMedian(arm, mesh, b, false, spec.leftTraffic());
               double green = arm.medianKind() == RoadProfile.Median.GREEN && !tunnel ? 1.0 - RoadStreetscape.medianConversionBlend(mesh, mid) : 0.0;
               if (green > 0.12) {
                  out.addAll(RoadStructures.planting(a.at(ca, 0.0), b.at(cb, 0.0), a.left(), b.left(), wa, wb, green, 0.0));
               } else {
                  double band = raised ? 0.42 : 0.1;
                  double gap = mesh.settings().options().streetscape().plantings().railGap();
                  double separation = gap == 0.0 ? 0.0 : gap + band;
                  double ga = Math.min(separation, Math.max(0.0, wa - band));
                  double gb = Math.min(separation, Math.max(0.0, wb - band));

                  for (int side : Math.max(ga, gb) > 1.0E-7 ? new int[]{-1, 1} : new int[]{0}) {
                     RoadStructures.barrier(out, a.at(ca + (double)side * ga / 2.0, 0.0), b.at(cb + (double)side * gb / 2.0, 0.0), false, raised, a.distance());
                  }
               }
            }

            for (int side : new int[]{-1, 1}) {
               boolean sideRaised = RoadStreetscape.bridge(mesh, mid, ground, side);
               if (arm.cycleWidth() > 0.0 && a.distance() < end - 1.0E-7) {
                  double innerA = JunctionPlanner.approachMotor(arm, mesh, a, side);
                  double innerB = JunctionPlanner.approachMotor(arm, mesh, b, side);
                  double separation = RoadStreetscape.separatorWidth(arm.external().options());
                  if (separation > 0.0) {
                     if (!sideRaised && !tunnel) {
                        double fade = Math.min(6.0, end / 2.0);
                        double fa = RoadGeometry.Settings.smooth(Math.min(1.0, Math.min(arm.attached() ? fade : a.distance(), end - a.distance()) / fade));
                        double fb = RoadGeometry.Settings.smooth(Math.min(1.0, Math.min(arm.attached() ? fade : b.distance(), end - b.distance()) / fade));
                        RoadStructures.cyclePlanter(out, a, b, side, innerA, innerB, separation, fa, fb);
                     } else {
                        RoadStructures.barrier(
                           out,
                           a.at(innerA + (double)side * separation / 2.0, 0.0),
                           b.at(innerB + (double)side * separation / 2.0, 0.0),
                           false,
                           sideRaised,
                           a.distance()
                        );
                     }
                  }

                  if (arm.external().options().cycleRail()) {
                     RoadStructures.barrier(out, a.at(innerA, 0.0), b.at(innerB, 0.0), false, false, a.distance());
                  }
               }

               boolean opening = arm.crosswalk() && mid.distance() > crossLo && mid.distance() < crossHi;
               RoadProfile.OuterRail rail = arm.external().options().outerRail();
               if (!opening && !tunnel && (spec.outerRail() || rail == RoadProfile.OuterRail.ON || rail != RoadProfile.OuterRail.OFF && sideRaised)) {
                  RoadStructures.barrier(
                     out,
                     a.at((double)side * (a.halfWidth() - 0.16), 0.0),
                     b.at((double)side * (b.halfWidth() - 0.16), 0.0),
                     false,
                     sideRaised || spec.outerRail(),
                     a.distance()
                  );
               }
            }
         }
      }

      RoadStructures.closePlantingEnds(out, mesh);
      return List.copyOf(out);
   }

   private JunctionApproachFurniture() {
   }
}
