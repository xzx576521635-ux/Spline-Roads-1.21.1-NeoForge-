package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;

public final class InterchangeSignForks {
   public static Map<String, InterchangeSignForks.Fork> find(List<InterchangePlanner.Leg> legs) {
      LinkedHashMap<InterchangeSignForks.Point, Map<InterchangeSignForks.Point, InterchangeSignForks.Out>> out = new LinkedHashMap<>();
      HashMap<InterchangeSignForks.Point, RoadGeometry.V> positions = new HashMap<>();
      HashMap<String, InterchangeSignForks.Point> starts = new HashMap<>();

      for (InterchangePlanner.Leg leg : legs) {
         if (leg.mesh().settings().style().ramp()) {
            starts.put(leg.name(), new InterchangeSignForks.Point(leg.mesh().first().center()));
            List<RoadGeometry.Sample> samples = leg.mesh().samples();

            for (int i = 1; i < samples.size(); i++) {
               RoadGeometry.V a = samples.get(i - 1).center();
               RoadGeometry.V b = samples.get(i).center();
               InterchangeSignForks.Point p = new InterchangeSignForks.Point(a);
               InterchangeSignForks.Point q = new InterchangeSignForks.Point(b);
               if (!p.equals(q)) {
                  positions.put(p, a);
                  out.computeIfAbsent(
                        p,
                        k -> new TreeMap<>(
                              Comparator.comparingLong(InterchangeSignForks.Point::x)
                                 .thenComparingLong(InterchangeSignForks.Point::y)
                                 .thenComparingLong(InterchangeSignForks.Point::z)
                           )
                     )
                     .putIfAbsent(q, new InterchangeSignForks.Out(leg.name(), q, b.sub(a).horizontalUnit()));
               }
            }
         }
      }

      LinkedHashMap<String, InterchangeSignForks.Fork> result = new LinkedHashMap<>();

      for (Entry<InterchangeSignForks.Point, Map<InterchangeSignForks.Point, InterchangeSignForks.Out>> entry : out.entrySet()) {
         if (entry.getValue().size() == 2) {
            ArrayList<InterchangeSignForks.Out> edges = new ArrayList<>(entry.getValue().values());
            InterchangeSignForks.Out a = edges.get(0);
            InterchangeSignForks.Out b = edges.get(1);
            if (!a.road().equals(b.road()) && !(a.heading().dot(b.heading()) < 0.9)) {
               InterchangeSignForks.Out owner = entry.getKey().equals(starts.get(a.road())) ? a : b;
               if (entry.getKey().equals(starts.get(owner.road()))) {
                  InterchangeSignForks.Out sibling = owner == a ? b : a;
                  result.put(
                     owner.road(),
                     new InterchangeSignForks.Fork(
                        owner.road(), sibling.road(), positions.get(entry.getKey()), a.heading().add(b.heading()).horizontalUnit().left()
                     )
                  );
               }
            }
         }
      }

      return Collections.unmodifiableMap(result);
   }

   private InterchangeSignForks() {
   }

   public static record Fork(String owner, String sibling, RoadGeometry.V origin, RoadGeometry.V right) {
   }

   private static record Out(String road, InterchangeSignForks.Point next, RoadGeometry.V heading) {
   }

   private static record Point(long x, long y, long z) {
      Point(RoadGeometry.V p) {
         this(Math.round(p.x() * 100000.0), Math.round(p.y() * 100000.0), Math.round(p.z() * 100000.0));
      }
   }
}
