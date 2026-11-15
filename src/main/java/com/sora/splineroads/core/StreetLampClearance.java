package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class StreetLampClearance {
   private static long rounded(double n) {
      return Math.round(n * 1000000.0);
   }

   private static String point(RoadGeometry.V v) {
      return rounded(v.x()) + "," + rounded(v.y()) + "," + rounded(v.z());
   }

   private static String key(RoadStructures.Part p) {
      return p.material() + ":" + p.pier() + ":" + point(p.a()) + ":" + point(p.b()) + ":" + rounded(p.width()) + ":" + rounded(p.height());
   }

   public static List<RoadStructures.Part> removed(List<RoadStructures.Part> parts, Predicate<RoadGeometry.V> clear) {
      StreetLampClearance.Removal scan = new StreetLampClearance.Removal(parts, List.of(clear));

      while (!scan.done()) {
         scan.advance(1024);
      }

      return scan.removed();
   }

   private StreetLampClearance() {
   }

   public static final class Removal {
      private final List<RoadStructures.Part> parts;
      private final List<Predicate<RoadGeometry.V>> clear;
      private final Set<String> keys = new HashSet<>();
      private final Set<RoadStructures.Part> brackets = new HashSet<>();
      private final List<RoadStructures.Part> removed = new ArrayList<>();
      private int phase;
      private int baseIndex;
      private int signIndex;
      private int memberIndex;
      private int resultIndex;
      private RoadGeometry.V foot;
      private long visits;
      private long signTests;
      private long keyBuilds;

      public Removal(List<RoadStructures.Part> parts, List<Predicate<RoadGeometry.V>> clear) {
         this.parts = parts;
         this.clear = List.copyOf(clear);
      }

      public boolean done() {
         return this.phase == 4;
      }

      public long partVisits() {
         return this.visits;
      }

      public long signTests() {
         return this.signTests;
      }

      public long keyBuilds() {
         return this.keyBuilds;
      }

      public List<RoadStructures.Part> removed() {
         if (!this.done()) {
            throw new IllegalStateException("lamp scan not complete");
         } else {
            return List.copyOf(this.removed);
         }
      }

      public int advance(int limit) {
         int used = 0;

         while (used < limit && !this.done()) {
            if (this.phase == 0) {
               if (this.baseIndex != this.parts.size()) {
                  RoadStructures.Part base = this.parts.get(this.baseIndex++);
                  used++;
                  this.visits++;
                  if (base.pier()
                     && base.material() == RoadStructures.Material.LAMP_BLUE
                     && Math.abs(base.width() - 0.22) < 1.0E-7
                     && Math.abs(base.height() - 1.8) < 1.0E-7) {
                     this.foot = base.a().sub(new RoadGeometry.V(0.0, 0.16, 0.0));
                     this.signIndex = 0;
                     this.phase = 1;
                  }
               } else {
                  this.phase = this.keys.isEmpty() && this.brackets.isEmpty() ? 4 : 3;
               }
            } else if (this.phase == 1) {
               if (this.signIndex == this.clear.size()) {
                  this.phase = 0;
               } else {
                  used++;
                  this.signTests++;
                  if (this.clear.get(this.signIndex++).test(this.foot)) {
                     this.memberIndex = 0;
                     this.phase = 2;
                  }
               }
            } else if (this.phase == 2) {
               if (this.memberIndex == this.parts.size()) {
                  this.phase = 0;
               } else {
                  RoadStructures.Part p = this.parts.get(this.memberIndex++);
                  used++;
                  this.visits++;
                  if (!p.pier()
                     && p.material() == RoadStructures.Material.STEEL
                     && Math.abs(p.width() - 0.1) < 1.0E-7
                     && Math.abs(p.height() - 0.1) < 1.0E-7
                     && p.a().sub(this.foot).horizontalLength() < 1.0E-6
                     && (Math.abs(p.a().y() - this.foot.y() - 7.3) < 1.0E-6 || Math.abs(p.a().y() - this.foot.y() - 5.8) < 1.0E-6)) {
                     RoadGeometry.V direction = p.b().sub(p.a()).horizontalUnit();

                     for (int style = 1; style <= 4; style++) {
                        for (RoadStructures.Part lamp : RoadStreetscape.lamp(this.foot, direction, style)) {
                           this.keys.add(StreetLampClearance.key(lamp));
                           this.keyBuilds++;
                        }
                     }
                  }

                  if (p.material() == RoadStructures.Material.CONCRETE
                     && (
                        p.pier()
                              && p.a().distance(this.foot.sub(new RoadGeometry.V(0.0, 0.12, 0.0))) < 1.0E-6
                              && Math.abs(p.width() - 0.38) < 1.0E-7
                              && Math.abs(p.height() - 0.12) < 1.0E-7
                           || !p.pier()
                              && p.b().distance(this.foot.sub(new RoadGeometry.V(0.0, 0.47, 0.0))) < 1.0E-6
                              && Math.abs(p.width() - 0.7) < 1.0E-7
                              && Math.abs(p.height() - 0.35) < 1.0E-7
                     )) {
                     this.brackets.add(p);
                  }
               }
            } else if (this.resultIndex == this.parts.size()) {
               this.phase = 4;
            } else {
               RoadStructures.Part px = this.parts.get(this.resultIndex++);
               used++;
               this.visits++;
               boolean match = false;
               if (!this.keys.isEmpty()) {
                  this.keyBuilds++;
                  match = this.keys.contains(StreetLampClearance.key(px));
               }

               if (match || this.brackets.contains(px)) {
                  this.removed.add(px);
               }
            }
         }

         return used;
      }
   }
}
