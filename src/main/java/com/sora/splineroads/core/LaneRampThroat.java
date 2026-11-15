package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class LaneRampThroat {
   public static int end(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> hosts, boolean first) {
      int n = ramp.samples().size();
      int i = first ? 0 : n - 1;

      int last;
      for (last = i; i >= 0 && i < n && height(ramp.samples().get(i), hosts) != null; i += first ? 1 : -1) {
         last = i;
      }

      return last;
   }

   private static Double height(RoadGeometry.Sample at, List<RoadGeometry.Mesh> hosts) {
      Double height = null;

      for (RoadGeometry.Mesh host : hosts) {
         RoadQueries.Projection q = RoadQueries.horizontal(host, at.center());
         RoadGeometry.Sample h = q.sample();
         RoadGeometry.V forward = h.left().left().mul(-1.0);
         double along = at.center().sub(h.center()).dot(forward);
         if ((!(q.sample().distance() < 1.0E-5) || !(along < -0.02))
            && (!(q.sample().distance() > host.length() - 1.0E-5) || !(along > 0.02))
            && !(Math.abs(h.left().dot(at.left())) < 0.55)
            && !(q.horizontalDistance() >= h.halfWidth() + at.halfWidth() - 0.02)
            && LaneDeck.present(host, h, q.lateral(), at.halfWidth())) {
            if (height != null && Math.abs(height - h.center().y()) > 0.025) {
               throw new IllegalArgumentException("普通分流的共用路面高程不一致，请先更新原分流接头");
            }

            height = h.center().y();
         }
      }

      return height;
   }

   public static RoadGeometry.Mesh fit(RoadGeometry.Mesh ramp, List<RoadGeometry.Mesh> hosts, boolean first) {
      if (hosts.isEmpty()) {
         return ramp;
      } else {
         int end = end(ramp, hosts, first);
         RoadGeometry.Sample boundary = ramp.samples().get(end);
         double remaining = first ? ramp.length() - boundary.distance() : boundary.distance();
         if (remaining < 0.01) {
            for (RoadGeometry.Sample at : ramp.samples()) {
               Double target = height(at, hosts);
               if (target != null && Math.abs(target - at.center().y()) > 0.025) {
                  throw new IllegalArgumentException("普通分流尚未离开共用路面，不能改变高程");
               }
            }

            return ramp;
         } else {
            Double y = height(boundary, hosts);
            if (y == null) {
               return ramp;
            } else {
               double delta = y - boundary.center().y();
               double ease = Math.min(remaining, Math.max(16.0, Math.min(96.0, remaining * 0.5)));
               ArrayList<RoadGeometry.Sample> out = new ArrayList<>();

               for (int i = 0; i < ramp.samples().size(); i++) {
                  RoadGeometry.Sample atx = ramp.samples().get(i);
                  double dy;
                  if (first ? i <= end : i >= end) {
                     Double target = height(atx, hosts);
                     dy = target == null ? 0.0 : target - atx.center().y();
                  } else {
                     double distance = first ? atx.distance() - boundary.distance() : boundary.distance() - atx.distance();
                     dy = delta * (1.0 - RoadGeometry.Settings.smooth(Math.min(1.0, distance / ease)));
                  }

                  out.add(new RoadGeometry.Sample(atx.center().add(new RoadGeometry.V(0.0, dy, 0.0)), atx.left(), atx.distance(), atx.halfWidth()));
               }

               return RoadRibbon.mesh(out, ramp.settings());
            }
         }
      }
   }

   private LaneRampThroat() {
   }
}
