package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class JunctionSides {
   final List<RoadGeometry.V> outward = new ArrayList<>();
   final List<Integer> order = new ArrayList<>();
   final int[] partner;
   final double[] offset;
   final double[] envelope;

   JunctionSides(JunctionSpec s, boolean separated) {
      int n = s.arms().size();
      this.partner = new int[n];
      Arrays.fill(this.partner, -1);
      this.offset = new double[n];
      this.envelope = new double[n];

      for (int i = 0; i < n; i++) {
         JunctionSpec.Arm arm = s.arms().get(i);
         this.outward.add(arm.endpoint().position().sub(s.center()).horizontalUnit());
         this.order.add(i);
         this.envelope[i] = arm.width();
      }

      if (separated && s.kind() == JunctionSpec.Kind.INTERSECTION) {
         for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
               JunctionSpec.Arm a = s.arms().get(i);
               JunctionSpec.Arm b = s.arms().get(j);
               boolean complementary = a.incoming() > 0 && a.outgoing() == 0 && b.incoming() == 0 && b.outgoing() > 0
                  || b.incoming() > 0 && b.outgoing() == 0 && a.incoming() == 0 && a.outgoing() > 0;
               if (this.partner[i] < 0
                  && this.partner[j] < 0
                  && complementary
                  && (s.parallelApproaches() || !(this.outward.get(i).dot(this.outward.get(j)) <= Math.cos(Math.toRadians(25.0))))
                  && !(a.inward().dot(b.inward()) < 0.9)) {
                  RoadGeometry.V axis = a.inward().add(b.inward()).mul(-1.0).horizontalUnit();
                  RoadGeometry.V side = axis.left();
                  double ai = a.endpoint().position().sub(s.center()).dot(side);
                  double bi = b.endpoint().position().sub(s.center()).dot(side);
                  double gap = Math.abs(ai - bi) - (a.width() + b.width()) / 2.0;
                  if (gap < 0.5) {
                     throw new IllegalArgumentException("同侧单行入口边缘至少留 0.5 格间隔，请拉开 B/C 端点");
                  }

                  if (gap > 64.0) {
                     throw new IllegalArgumentException("同侧单行入口间隔最多 64 格，请靠近 B/C 端点");
                  }

                  this.partner[i] = j;
                  this.partner[j] = i;
                  this.outward.set(i, axis);
                  this.outward.set(j, axis);
                  this.offset[i] = ai;
                  this.offset[j] = bi;
                  this.envelope[i] = this.envelope[j] = 2.0 * Math.max(Math.abs(ai) + a.width() / 2.0, Math.abs(bi) + b.width() / 2.0);
               }
            }
         }
      }

      this.order
         .sort(
            Comparator.<Integer>comparingDouble(ix -> Math.atan2(this.outward.get(ix).z(), this.outward.get(ix).x()))
               .thenComparingDouble(ix -> this.offset[ix])
         );
   }

   boolean paired(int i, int j) {
      return this.partner[i] == j;
   }

   boolean separated() {
      return Arrays.stream(this.partner).anyMatch(i -> i >= 0);
   }

   RoadGeometry.V mouth(JunctionSpec s, int i, double radius) {
      return s.center().add(this.outward.get(i).mul(radius)).add(this.outward.get(i).left().mul(this.offset[i]));
   }

   double radius(JunctionSpec s) {
      return this.radius(s, true);
   }

   double radius(JunctionSpec s, boolean parallelClearance) {
      if (s.kind() == JunctionSpec.Kind.ROUNDABOUT) {
         return s.islandRadius() + (double)s.ringLanes() * s.ringLaneWidth();
      } else {
         double result = s.cornerRadius();

         for (int k = 0; k < this.order.size(); k++) {
            int i = this.order.get(k);
            int j = this.order.get((k + 1) % this.order.size());
            if (!this.paired(i, j)) {
               RoadGeometry.V a = this.outward.get(i);
               RoadGeometry.V b = this.outward.get(j);
               double angle = Math.atan2(a.x() * b.z() - a.z() * b.x(), a.dot(b));
               if (angle <= 0.0) {
                  angle += Math.PI * 2;
               }

               if (angle < Math.toRadians(25.0)) {
                  throw new IllegalArgumentException("相邻接入口夹角至少 25°；同侧入口须为一入一出的单行道");
               }

               double tangent = Math.tan(Math.min(angle, 3.1315926535897933) / 2.0);
               if (this.separated()) {
                  tangent = Math.min(1.0, tangent);
               }

               result = Math.max(result, (this.envelope[i] + this.envelope[j]) / 4.0 / tangent + s.cornerRadius());
            }
         }

         if (parallelClearance && s.parallelApproaches()) {
            for (int i = 0; i < this.partner.length; i++) {
               if (this.partner[i] >= 0) {
                  result = Math.max(result, this.envelope[i] / 2.0 + s.cornerRadius());
               }
            }
         }

         return result;
      }
   }
}
