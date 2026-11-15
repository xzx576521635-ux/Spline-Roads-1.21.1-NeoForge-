package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

public final class JunctionSignalCycle {
   private final int[] phaseSlots;
   private final int[] pedestrianArms;
   private final int[][] leftSlots;
   private final boolean[] crossings;
   private final boolean[] splitArms;
   private final int duration;
   private final int green;
   private final int yellow;
   private final int offset;
   private final int period;
   private final boolean enabled;

   public JunctionSignalCycle(JunctionSpec s, List<Integer> pedestrianArms) {
      int n = s.arms().size();
      this.phaseSlots = new int[n];
      this.leftSlots = new int[n][];
      this.crossings = new boolean[n];
      this.splitArms = new boolean[n];
      this.pedestrianArms = new int[n];
      TreeSet<Integer> phases = new TreeSet<>();

      for (int i = 0; i < n; i++) {
         JunctionSpec.Arm a = s.arms().get(i);
         if (a.incoming() > 0) {
            phases.add(a.phase());
         }
      }

      ArrayList<Integer> groups = new ArrayList<>(phases);

      for (int ix = 0; ix < n; ix++) {
         JunctionSpec.Arm a = s.arms().get(ix);
         this.phaseSlots[ix] = normalActive(s, ix) ? groups.indexOf(a.phase()) : -1;
         this.leftSlots[ix] = new int[a.incoming()];
         Arrays.fill(this.leftSlots[ix], -1);

         for (int j = 0; j < a.incoming(); j++) {
            JunctionSpec.Lane lane = a.lanes().get(j);
            if (lane.splitLeft() && (JunctionPlanner.mask(s, ix, j) & 10) != 0) {
               this.leftSlots[ix][j] = groups.indexOf(a.phase());
               this.splitArms[ix] = true;
            }
         }

         this.crossings[ix] = a.crosswalk();
         this.pedestrianArms[ix] = pedestrianArms.isEmpty() ? ix : pedestrianArms.get(ix);
      }

      this.green = s.greenSeconds();
      this.yellow = s.yellowSeconds();
      this.duration = this.green + this.yellow + s.allRedSeconds();
      this.period = this.duration * groups.size();
      this.offset = s.timeOffset();
      this.enabled = s.control() == JunctionSpec.Control.SIGNALS;
   }

   private static boolean normalActive(JunctionSpec s, int arm) {
      return JunctionSignals.normalMask(s, arm, -1) != 0 || JunctionSignals.rightControlled(s, arm, -1);
   }

   private long tick(int slot, long time) {
      if (this.enabled && this.period != 0 && slot >= 0) {
         long at = Math.floorMod(Math.floorMod(time, (long)this.period * 20L) + (long)this.offset * 20L, (long)this.period * 20L);
         return at / ((long)this.duration * 20L) == (long)slot ? at % ((long)this.duration * 20L) : -1L;
      } else {
         return -1L;
      }
   }

   private int state(int slot, long time) {
      long t = this.tick(slot, time);
      return t < 0L ? 0 : (t < (long)this.green * 20L ? 1 : (t < (long)(this.green + this.yellow) * 20L ? 2 : 0));
   }

   public int vehicle(int arm, long time) {
      if (arm < 0 || arm >= this.phaseSlots.length) {
         return 0;
      } else if (!this.splitArms[arm]) {
         return this.state(this.phaseSlots[arm], time);
      } else {
         long t = this.tick(this.phaseSlots[arm], time);
         return t >= 0L && t < (long)this.green * 10L ? 1 : 0;
      }
   }

   public int vehicle(int arm, int lane, boolean left, long time) {
      if (!left) {
         return this.vehicle(arm, time);
      } else if (arm >= 0 && arm < this.leftSlots.length && lane >= 0 && lane < this.leftSlots[arm].length) {
         long t = this.tick(this.leftSlots[arm][lane], time);
         return t < (long)this.green * 10L ? 0 : (t < (long)this.green * 20L ? 1 : (t < (long)(this.green + this.yellow) * 20L ? 2 : 0));
      } else {
         return 0;
      }
   }

   public int pedestrian(int arm, long time) {
      if (this.enabled && arm >= 0 && arm < this.crossings.length && this.crossings[arm]) {
         int controller = this.pedestrianArms[arm];
         if (this.vehicle(controller, time) != 0) {
            return 0;
         } else {
            for (int lane = 0; lane < this.leftSlots[controller].length; lane++) {
               if (this.vehicle(controller, lane, true, time) != 0) {
                  return 0;
               }
            }

            return 1;
         }
      } else {
         return 0;
      }
   }

   public int pedestrianVehicleArm(int arm) {
      return this.pedestrianArms[arm];
   }

   public int periodSeconds() {
      return this.period;
   }

   public int resolutionTicks() {
      if ((this.green & 1) != 0) {
         for (boolean split : this.splitArms) {
            if (split) {
               return 10;
            }
         }
      }

      return 20;
   }
}
