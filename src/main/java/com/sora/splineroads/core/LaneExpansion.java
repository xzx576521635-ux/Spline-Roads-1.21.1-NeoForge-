package com.sora.splineroads.core;

public final class LaneExpansion {
   public static LaneAdditions.Addition addition(RoadGeometry.Mesh mesh, LanePoints.Point point, int slot) {
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);
      LanePoints.Lane lane = LanePoints.lane(raw, point);
      double length = -point.mergeLength();
      if (!point.automatic() && !(length < 8.0) && !(length > 256.0) && LanePoints.supported(raw.settings())) {
         LanePoints.Data md = raw.settings().options().lanePoints();
         RoadGeometry.Settings settings = raw.settings()
            .options(raw.settings().options().lanePoints(md.additions(md.additions().stream().filter(a -> !a.connection().equals(point.id())).toList())));
         RoadGeometry.Mesh clean = new RoadGeometry.Mesh(raw.samples(), settings, raw.min(), raw.max(), raw.length(), raw.closed());
         if (!LaneSections.edge(clean, lane.station(), point.lane(), md.cuts(), point.id())) {
            throw new IllegalArgumentException("扩流须选择行驶方向最外侧车道");
         } else {
            RoadGeometry.Sample at = RoadStructures.sample(clean, lane.station());
            RoadProfile.Layout layout = RoadProfile.layout(clean, at);
            int side = LaneAdditions.side(layout, lane.sign());

            for (LanePoints.Lane other : LaneSections.live(clean, lane.station()).lanes()) {
               if (other.sign() == lane.sign() && other.index() != point.lane() && other.position().sub(lane.position()).dot(at.left()) * (double)side > 1.0E-6
                  )
                {
                  throw new IllegalArgumentException("扩流请选择行驶方向最外侧车道");
               }
            }

            double end = lane.station() + (double)lane.sign() * length;
            if (end < 0.0 || end > raw.length()) {
               throw new IllegalArgumentException("下游长度不足以完成扩流渐变");
            } else if (LaneSections.live(clean, end).count(lane.sign()) >= 4) {
               throw new IllegalArgumentException("该方向已达四车道，不能再扩流");
            } else {
               return new LaneAdditions.Addition(point.id(), slot, lane.sign(), end, length);
            }
         }
      } else {
         throw new IllegalArgumentException("请选择手动外侧车道点设置扩流");
      }
   }

   public static boolean allowed(RoadGeometry.Mesh mesh, LanePoints.Point point) {
      try {
         addition(mesh, point.merge(-32.0), 8);
         return true;
      } catch (IllegalArgumentException var3) {
         return false;
      }
   }

   private LaneExpansion() {
   }
}
