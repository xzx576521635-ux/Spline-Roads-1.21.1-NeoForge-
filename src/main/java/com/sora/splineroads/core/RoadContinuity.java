package com.sora.splineroads.core;

public final class RoadContinuity {
   public static final double POSITION_EPS = 0.001;

   public static boolean eligible(RoadGeometry.Mesh m) {
      RoadGeometry.Settings s = m.settings();
      RoadProfile.Type kind = RoadProfile.catalog(s.style()).type();
      if (kind != RoadProfile.Type.ORDINARY && kind != RoadProfile.Type.HIGHWAY) {
         return false;
      } else if (m.closed() || s.structure() == RoadGeometry.Structure.TUNNEL) {
         return false;
      } else if (s.structure() == RoadGeometry.Structure.BRIDGE
         && s.options().infrastructure().bridge() != RoadInfrastructure.Bridge.STANDARD
         && s.options().infrastructure().bridge() != RoadInfrastructure.Bridge.BEAM
         && s.options().infrastructure().bridge() != RoadInfrastructure.Bridge.OVERPASS) {
         return false;
      } else {
         RoadGeometry.V a = m.first().center();
         RoadGeometry.V v = m.last().center().sub(a);
         if (v.horizontalLength() < 2.0) {
            return false;
         } else {
            RoadGeometry.V dir = v.horizontalUnit();
            return m.samples().stream().allMatch(p -> Math.abs(p.center().y() - a.y()) <= 0.001 && Math.abs(p.center().sub(a).dot(dir.left())) <= 0.001);
         }
      }
   }

   public static boolean compatible(RoadGeometry.Mesh a, RoadGeometry.Mesh b, RoadGeometry.V shared) {
      if (eligible(a) && eligible(b)) {
         RoadProfile.Catalog ac = RoadProfile.catalog(a.settings());
         RoadProfile.Catalog bc = RoadProfile.catalog(b.settings());
         if (ac.lanes() == bc.lanes() && ac.twoWay() == bc.twoWay()) {
            RoadGeometry.V ad = a.last().center().sub(a.first().center()).horizontalUnit();
            RoadGeometry.V bd = b.last().center().sub(b.first().center()).horizontalUnit();
            if (!RoadLanes.counts(a.settings()).equals(ad.dot(bd) < 0.0 ? RoadLanes.counts(b.settings()).mirrored() : RoadLanes.counts(b.settings()))) {
               return false;
            } else if (Math.abs(ad.dot(bd)) < 0.99999999 || Math.abs(a.first().center().y() - b.first().center().y()) > 0.001) {
               return false;
            } else if (!ac.twoWay() && ad.dot(bd) < 0.0) {
               return false;
            } else {
               boolean af = a.first().center().distance(shared) < 0.001;
               boolean bf = b.first().center().distance(shared) < 0.001;
               return (af || !(a.last().center().distance(shared) >= 0.001)) && (bf || !(b.last().center().distance(shared) >= 0.001))
                  ? ad.mul(af ? 1.0 : -1.0).dot(bd.mul(bf ? 1.0 : -1.0)) < -0.99999999
                  : false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private RoadContinuity() {
   }
}
