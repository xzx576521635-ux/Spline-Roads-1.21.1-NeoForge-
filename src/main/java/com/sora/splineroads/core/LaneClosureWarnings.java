package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class LaneClosureWarnings {
   public static List<RoadJunction.Paint> paint(RoadGeometry.Mesh mesh) {
      return paint(mesh, List.of());
   }

   public static List<RoadJunction.Paint> paint(RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> neighbors) {
      ArrayList<RoadJunction.Paint> out = new ArrayList<>();
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);
      HashSet<String> used = new HashSet<>();

      for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
         if (cut.rectangular() && cut.arrival()) {
            double start = Math.min(cut.begin(), cut.end());
            double end = Math.max(cut.begin(), cut.end());
            LanePoints.Lane lane = LanePoints.lane(raw, Math.max(0.0, Math.min(raw.length(), start)), cut.lane());
            double boundary = lane.sign() > 0 ? start : end;
            double before = boundary - (double)lane.sign() * 0.25;
            double after = boundary + (double)lane.sign() * 0.25;
            if (!(before < 0.0)
               && !(before > raw.length())
               && !(after < 0.0)
               && !(after > raw.length())
               && LaneSections.active(mesh, before, cut.lane())
               && !LaneSections.active(mesh, after, cut.lane())
               && used.add(cut.lane() + ":" + Math.round(boundary * 10000.0))) {
               for (double lead : new double[]{8.0, 16.0, 24.0}) {
                  double station = boundary - (double)lane.sign() * lead;
                  if (!(station < 3.0) && !(station > raw.length() - 3.0)) {
                     double half = Math.min(1.4, lane.width() * 0.32);

                     for (int slope : new int[]{-1, 1}) {
                        ArrayList<RoadGeometry.V> vertices = new ArrayList<>();
                        boolean fits = true;

                        for (double[] q : new double[][]{{-2.0, -0.1}, {-2.0, 0.1}, {2.0, 0.1}, {2.0, -0.1}}) {
                           double d = station + (double)lane.sign() * q[0];
                           LanePoints.Lane at = LanePoints.lane(raw, d, cut.lane());
                           RoadGeometry.V v = at.position().add(at.direction().left().mul((double)slope * q[0] * half / 2.0 + q[1]));
                           if (!LaneSections.active(mesh, d, cut.lane()) || !RoadQueries.contains(mesh, v, -0.08, 0.1)) {
                              fits = false;
                           }

                           vertices.add(v);
                        }

                        if (fits) {
                           out.add(new RoadJunction.Paint(List.copyOf(vertices), 15593186));
                        }
                     }
                  }
               }
            }
         }
      }

      boolean elevated = !mesh.settings().options().lanePoints().access().isEmpty();
      RoadRailJoin mouths = RoadRailJoin.mouths(
         neighbors.stream()
            .filter(
               n -> LaneMerge.linkedTo(mesh, n)
                     || LaneMerge.linkedTo(n, mesh)
                     || elevated && (n.settings().options().ends().startHold() > 0.0 || n.settings().options().ends().endHold() > 0.0)
            )
            .toList()
      );

      for (LaneDeck.Cap cap : LaneDeck.caps(mesh)) {
         RoadGeometry.V axis = cap.b().sub(cap.a()).horizontalUnit();
         RoadGeometry.V inward = axis.left().mul(0.1);
         RoadGeometry.Sample at = RoadQueries.horizontal(mesh, cap.a().add(cap.b()).mul(0.5)).sample();
         RoadGeometry.V a = paintEnd(mesh, at, cap.a()).sub(axis.mul(0.06)).add(inward);
         RoadGeometry.V b = paintEnd(mesh, at, cap.b()).add(axis.mul(0.06)).add(inward);

         for (RoadRailJoin.Span span : mouths.exposedMouth(a, b)) {
            out.add(
               new RoadJunction.Paint(
                  List.of(span.a().sub(inward.mul(0.8)), span.b().sub(inward.mul(0.8)), span.b().add(inward.mul(0.8)), span.a().add(inward.mul(0.8))), 15593186
               )
            );
         }
      }

      return List.copyOf(out);
   }

   private static RoadGeometry.V paintEnd(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, RoadGeometry.V point) {
      double offset = point.sub(at.center()).dot(at.left());
      if (Math.abs(offset) >= at.halfWidth() - 1.0E-5) {
         return at.at(RoadSurface.edgeOffset(mesh, at, offset > 0.0 ? 1 : -1), 0.0);
      } else {
         double nearest = offset;
         double error = 0.8;

         for (double boundary : RoadProfile.layout(mesh, at).dividers()) {
            if (Math.abs(boundary - offset) < error) {
               nearest = boundary;
               error = Math.abs(boundary - offset);
            }
         }

         return at.at(nearest, 0.0);
      }
   }

   public static boolean covers(RoadGeometry.Mesh mesh, double station, int slot) {
      for (LaneSections.Cut cut : mesh.settings().options().lanePoints().cuts()) {
         if (cut.rectangular() && cut.arrival() && cut.lane() == slot) {
            LanePoints.Lane lane = LanePoints.lane(LaneSections.reference(mesh), station, slot);
            double boundary = lane.sign() > 0 ? Math.min(cut.begin(), cut.end()) : Math.max(cut.begin(), cut.end());
            double lead = (double)lane.sign() * (boundary - station);
            double before = boundary - (double)lane.sign() * 0.25;
            double after = boundary + (double)lane.sign() * 0.25;
            if (lead >= 0.0
               && lead <= 29.0
               && before >= 0.0
               && after >= 0.0
               && before <= mesh.length()
               && after <= mesh.length()
               && LaneSections.active(mesh, before, slot)
               && !LaneSections.active(mesh, after, slot)) {
               return true;
            }
         }
      }

      return false;
   }

   private LaneClosureWarnings() {
   }
}
