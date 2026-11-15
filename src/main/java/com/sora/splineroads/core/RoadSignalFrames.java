package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RoadSignalFrames {
   private final RoadSignalFrames.Head[] heads;
   private final RoadSignalFrames.State state;
   private final int[] values;
   private long second;
   private final int clockStep;
   private List<RoadSurface.Face> frame;

   public RoadSignalFrames(List<RoadStructures.Part> heads, RoadSignalFrames.State state) {
      this(heads, state, 20);
   }

   public RoadSignalFrames(List<RoadStructures.Part> heads, RoadSignalFrames.State state, int clockStep) {
      if (clockStep != 10 && clockStep != 20) {
         throw new IllegalArgumentException("Invalid signal clock step");
      } else {
         this.clockStep = clockStep;
         this.heads = heads.stream().map(RoadSignalFrames.Head::new).toArray(RoadSignalFrames.Head[]::new);
         this.state = state;
         this.values = new int[heads.size()];
         Arrays.fill(this.values, -1);
      }
   }

   public List<RoadSurface.Face> at(long time) {
      long now = Math.floorDiv(time, this.clockStep);
      if (this.frame != null && this.second == now) {
         return this.frame;
      } else {
         boolean changed = this.frame == null;

         for (int i = 0; i < this.heads.length; i++) {
            int next = this.state.at(this.heads[i].part, time);
            if (next < 0 || next > 2) {
               throw new IllegalArgumentException("Invalid signal state: " + next);
            }

            changed |= this.values[i] != next;
            this.values[i] = next;
         }

         if (changed) {
            ArrayList<RoadSurface.Face> result = new ArrayList<>();

            for (int i = 0; i < this.heads.length; i++) {
               result.addAll(this.heads[i].faces(this.values[i]));
            }

            this.frame = List.copyOf(result);
         }

         this.second = now;
         return this.frame;
      }
   }

   private static final class Head {
      final RoadStructures.Part part;
      final List<List<RoadSurface.Face>> states = new ArrayList<>(Arrays.asList(null, null, null));

      Head(RoadStructures.Part part) {
         this.part = part;
      }

      List<RoadSurface.Face> faces(int state) {
         List<RoadSurface.Face> result = this.states.get(state);
         if (result == null) {
            result = RoadSignalModel.faces(this.part, true, state);
            this.states.set(state, result);
         }

         return result;
      }
   }

   @FunctionalInterface
   public interface State {
      int at(RoadStructures.Part var1, long var2);
   }
}
