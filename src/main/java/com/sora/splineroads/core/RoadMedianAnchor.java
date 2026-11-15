package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RoadMedianAnchor {
   public static RoadGeometry.V position(RoadGeometry.Mesh m, boolean first) {
      RoadGeometry.Sample s = first ? m.first() : m.last();
      RoadProfile.Layout l = RoadProfile.layout(m, s);
      return l.catalog().twoWay() ? s.at(l.medianCenter(), 0.0) : s.center();
   }

   public static RoadGeometry.Mesh apply(RoadGeometry.Mesh raw) {
      RoadProfile.Catalog c = RoadProfile.catalog(raw.settings());
      if (c.twoWay() && (c.type() == RoadProfile.Type.ORDINARY || c.type() == RoadProfile.Type.HIGHWAY)) {
         ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
         boolean moved = false;

         for (RoadGeometry.Sample s : raw.samples()) {
            double offset = RoadProfile.layout(raw, s).medianCenter();
            moved |= Math.abs(offset) > 1.0E-9;
            samples.add(new RoadGeometry.Sample(s.at(-offset, 0.0), s.left(), s.distance(), s.halfWidth()));
         }

         if (!moved) {
            return raw;
         } else {
            RoadGeometry.Mesh bounds = RoadRibbon.mesh(samples, raw.settings());
            return new RoadGeometry.Mesh(
               List.copyOf(samples), raw.settings(), bounds.min(), bounds.max(), raw.length(), raw.closed(), raw.controlPoint(), raw.controls(), null
            );
         }
      } else {
         return raw;
      }
   }

   private RoadMedianAnchor() {
   }
}
