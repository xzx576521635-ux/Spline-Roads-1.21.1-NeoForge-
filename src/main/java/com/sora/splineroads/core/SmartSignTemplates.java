package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class SmartSignTemplates {
   public static final int VERSION = 3;

   public static List<SmartSigns.Branch> compile(String preset, List<SmartSigns.Branch> confirmed) {
      return compile(preset, confirmed, false);
   }

   public static List<SmartSigns.Branch> compile(String preset, List<SmartSigns.Branch> confirmed, boolean leftTraffic) {
      int direct = leftTraffic ? -1 : 1;
      boolean both = confirmed.stream().anyMatch(b -> b.side() == direct) && confirmed.stream().anyMatch(b -> b.side() == -direct);
      ArrayList<SmartSigns.Branch> out = new ArrayList<>();

      for (SmartSigns.Branch branch : confirmed) {
         List<RoadGeometry.V> motif;
         if (!branch.id().equals("through") && branch.side() != 0) {
            if (branch.side() == direct) {
               double low = both ? 0.12 : 0.28;
               double high = both ? 0.27 : 0.46;
               ArrayList<RoadGeometry.V> p = new ArrayList<>();

               for (int i = 0; i <= 32; i++) {
                  double t = (double)i / 32.0;
                  double x = 0.88 * t;
                  double y = low + (high - low) * (1.0 - Math.pow(1.0 - t, 3.0));
                  p.add(new RoadGeometry.V((double)direct * x, 0.0, y));
               }

               p.add(new RoadGeometry.V((double)direct * 0.96, 0.0, high));
               motif = List.copyOf(p);
            } else {
               ArrayList<RoadGeometry.V> p = new ArrayList<>();
               p.add(new RoadGeometry.V(0.0, 0.0, 0.03));
               p.add(new RoadGeometry.V(0.0, 0.0, 0.46));

               for (int i = 0; i <= 32; i++) {
                  double a = Math.PI - (double)i * Math.PI * 1.5 / 32.0;
                  p.add(new RoadGeometry.V((double)direct * (0.14 + 0.14 * Math.cos(a)), 0.0, 0.46 + 0.08 * Math.sin(a)));
               }

               p.add(new RoadGeometry.V((double)(-direct) * 0.88, 0.0, 0.38));
               p.add(new RoadGeometry.V((double)(-direct) * 0.96, 0.0, 0.38));
               motif = List.copyOf(p);
            }
         } else {
            motif = List.of(new RoadGeometry.V(0.0, 0.0, 0.03), new RoadGeometry.V(0.0, 0.0, 1.0));
         }

         out.add(new SmartSigns.Branch(branch.id(), branch.lanes(), branch.side(), branch.dedicated(), motif));
      }

      return List.copyOf(out);
   }

   private SmartSignTemplates() {
   }
}
