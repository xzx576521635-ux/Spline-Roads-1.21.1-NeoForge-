package com.sora.splineroads.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

final class InterchangeGradeProfile {
   static double[] solve(double[] preferred, double[] lower, double[] upper, List<InterchangeGradeProfile.Edge> source, int[] offsets, double[][] distance) {
      LinkedHashMap<Long, InterchangeGradeProfile.Edge> strongest = new LinkedHashMap<>();

      for (InterchangeGradeProfile.Edge e : source) {
         long key = (long)e.from() << 32 | (long)e.to() & 4294967295L;
         InterchangeGradeProfile.Edge old = strongest.get(key);
         if (old == null || e.rise() > old.rise()) {
            strongest.put(Long.valueOf(key), e);
         }
      }

      ArrayList<InterchangeGradeProfile.Edge> edges = new ArrayList<>(strongest.values());
      ArrayList<InterchangeGradeProfile.Edge> allMonotone = new ArrayList<>();

      for (int r = 0; r < offsets.length; r++) {
         int start = offsets[r];
         int end = start + distance[r].length - 1;
         allMonotone.addAll(shape(start, end, end, preferred[end] >= preferred[start] ? 1 : -1));
      }

      ArrayList<InterchangeGradeProfile.Edge> monotoneEdges = new ArrayList<>(edges);
      monotoneEdges.addAll(allMonotone);

      try {
         return stretch(solve(preferred, (double[])lower.clone(), (double[])upper.clone(), monotoneEdges, true), lower, upper, monotoneEdges, offsets, distance);
      } catch (IllegalArgumentException var31) {
         double[] y = solve(preferred, (double[])lower.clone(), (double[])upper.clone(), edges, false);

         for (int r = 0; r < offsets.length; r++) {
            int start = offsets[r];
            int end = start + distance[r].length - 1;
            int direction = preferred[end] >= preferred[start] ? 1 : -1;
            List<InterchangeGradeProfile.Edge> monotone = shape(start, end, end, direction);
            double[] candidate = attempt(preferred, lower, upper, edges, monotone);
            if (candidate != null) {
               edges.addAll(monotone);
               y = candidate;
            } else {
               int high = start;
               int low = start;

               for (int i = start + 1; i <= end; i++) {
                  if (y[i] > y[high]) {
                     high = i;
                  }

                  if (y[i] < y[low]) {
                     low = i;
                  }
               }

               boolean crest = y[high] - Math.max(y[start], y[end]) >= Math.min(y[start], y[end]) - y[low];
               boolean found = false;

               for (int sign : new int[]{crest ? 1 : -1, crest ? -1 : 1}) {
                  int extreme = sign > 0 ? high : low;
                  LinkedHashSet<Integer> choices = new LinkedHashSet<>();
                  choices.add(Integer.valueOf(extreme));
                  int last = extreme;

                  for (int i = extreme + 1; i < end; i++) {
                     if (Math.abs(y[i] - y[extreme]) < 1.0E-6) {
                        last = i;
                     }
                  }

                  choices.add(Integer.valueOf((extreme + last) / 2));
                  choices.add(Integer.valueOf(last));

                  for (int peak : choices) {
                     if (peak > start && peak < end) {
                        List<InterchangeGradeProfile.Edge> shape = shape(start, end, peak, sign);
                        candidate = attempt(preferred, lower, upper, edges, shape);
                        if (candidate != null) {
                           edges.addAll(shape);
                           y = candidate;
                           found = true;
                           break;
                        }
                     }
                  }

                  if (found) {
                     break;
                  }
               }

               if (!found) {
                  throw new IllegalArgumentException("实际交叉处无法以单调或一次变坡连接，请扩大范围或调整跨越关系");
               }
            }
         }

         return stretch(solve(preferred, (double[])lower.clone(), (double[])upper.clone(), edges, true), lower, upper, edges, offsets, distance);
      }
   }

   private static double[] stretch(
      double[] feasible, double[] lower, double[] upper, List<InterchangeGradeProfile.Edge> edges, int[] offsets, double[][] distance
   ) {
      double[] y = (double[])feasible.clone();

      for (int pass = 0; pass < 2; pass++) {
         for (int r = 0; r < offsets.length; r++) {
            int start = offsets[r];
            int n = distance[r].length;
            int end = start + n;
            double[] lo = Arrays.copyOfRange(lower, start, end);
            double[] hi = Arrays.copyOfRange(upper, start, end);

            for (InterchangeGradeProfile.Edge e : edges) {
               boolean a = e.from() >= start && e.from() < end;
               boolean b = e.to() >= start && e.to() < end;
               if (!a || !b || Math.abs(e.to() - e.from()) != 1) {
                  if (b) {
                     lo[e.to() - start] = Math.max(lo[e.to() - start], y[e.from()] + e.rise());
                  }

                  if (a) {
                     hi[e.from() - start] = Math.min(hi[e.from() - start], y[e.to()] - e.rise());
                  }
               }
            }

            double[] candidate = Arrays.copyOfRange(y, start, end);
            ArrayDeque<int[]> ranges = new ArrayDeque<>();
            ranges.add(new int[]{0, n - 1});

            while (!ranges.isEmpty()) {
               int[] range = ranges.remove();
               int a = range[0];
               int b = range[1];
               int pin = -1;
               double worst = 1.0E-7;
               double target = 0.0;

               for (int i = a + 1; i < b; i++) {
                  double t = (distance[r][i] - distance[r][a]) / (distance[r][b] - distance[r][a]);
                  double value = candidate[a] * (1.0 - t) + candidate[b] * t;
                  double bound = Math.max(lo[i], Math.min(hi[i], value));
                  double error = Math.abs(bound - value);
                  if (error > worst) {
                     worst = error;
                     pin = i;
                     target = bound;
                  }
               }

               if (pin < 0) {
                  for (int ix = a + 1; ix < b; ix++) {
                     double t = (distance[r][ix] - distance[r][a]) / (distance[r][b] - distance[r][a]);
                     candidate[ix] = candidate[a] * (1.0 - t) + candidate[b] * t;
                  }
               } else {
                  candidate[pin] = target;
                  ranges.add(new int[]{a, pin});
                  ranges.add(new int[]{pin, b});
               }
            }

            boolean valid = true;

            for (int ix = 0; ix < n; ix++) {
               if (candidate[ix] < lo[ix] - 1.0E-7 || candidate[ix] > hi[ix] + 1.0E-7) {
                  valid = false;
                  break;
               }
            }

            for (InterchangeGradeProfile.Edge ex : edges) {
               if (valid) {
                  double a = ex.from() >= start && ex.from() < end ? candidate[ex.from() - start] : y[ex.from()];
                  double b = ex.to() >= start && ex.to() < end ? candidate[ex.to() - start] : y[ex.to()];
                  if (b < a + ex.rise() - 1.0E-7) {
                     valid = false;
                  }
               }
            }

            if (valid) {
               System.arraycopy(candidate, 0, y, start, n);
            }

            RoadPlanningBudget.check();
         }
      }

      return y;
   }

   private static List<InterchangeGradeProfile.Edge> shape(int start, int end, int peak, int direction) {
      ArrayList<InterchangeGradeProfile.Edge> edges = new ArrayList<>(end - start);

      for (int i = start + 1; i <= end; i++) {
         int sign = i <= peak ? direction : -direction;
         edges.add(sign > 0 ? new InterchangeGradeProfile.Edge(i - 1, i, 0.0) : new InterchangeGradeProfile.Edge(i, i - 1, 0.0));
      }

      return edges;
   }

   private static double[] attempt(
      double[] preferred, double[] lower, double[] upper, List<InterchangeGradeProfile.Edge> base, List<InterchangeGradeProfile.Edge> extra
   ) {
      ArrayList<InterchangeGradeProfile.Edge> all = new ArrayList<>(base);
      all.addAll(extra);

      try {
         return solve(preferred, (double[])lower.clone(), (double[])upper.clone(), all, false);
      } catch (IllegalArgumentException var7) {
         return null;
      }
   }

   static double[] solve(double[] preferred, double[] lower, double[] upper, List<InterchangeGradeProfile.Edge> edges) {
      return solve(preferred, lower, upper, edges, true);
   }

   private static double[] solve(double[] preferred, double[] lower, double[] upper, List<InterchangeGradeProfile.Edge> edges, boolean fair) {
      int n = preferred.length;
      ArrayList<List<InterchangeGradeProfile.Edge>> incoming = new ArrayList<>(n);
      ArrayList<List<InterchangeGradeProfile.Edge>> outgoing = new ArrayList<>(n);

      for (int i = 0; i < n; i++) {
         incoming.add(new ArrayList<>());
         outgoing.add(new ArrayList<>());
      }

      for (InterchangeGradeProfile.Edge e : edges) {
         outgoing.get(e.from()).add(e);
         incoming.get(e.to()).add(e);
      }

      ArrayDeque<Integer> queue = new ArrayDeque<>();
      boolean[] queued = new boolean[n];
      int[] visits = new int[n];

      for (int i = 0; i < n; i++) {
         if (Double.isFinite(upper[i])) {
            queue.add(i);
            queued[i] = true;
         }
      }

      while (!queue.isEmpty()) {
         int ix = queue.remove();
         queued[ix] = false;
         if (++visits[ix] > n) {
            throw new IllegalArgumentException("交叉高程约束成环，请扩大范围");
         }

         for (InterchangeGradeProfile.Edge e : incoming.get(ix)) {
            if (upper[e.from()] > upper[ix] - e.rise() + 1.0E-8) {
               upper[e.from()] = upper[ix] - e.rise();
               if (upper[e.from()] < lower[e.from()] - 1.0E-7) {
                  throw new IllegalArgumentException("实际交叉处的净高与 15% 纵坡无法同时满足，请扩大范围");
               }

               if (!queued[e.from()]) {
                  queue.add(e.from());
                  queued[e.from()] = true;
               }
            }
         }

         if ((ix & 0xFF) == 0) {
            RoadPlanningBudget.check();
         }
      }

      double[] y = new double[n];

      for (int ixx = 0; ixx < n; ixx++) {
         if (lower[ixx] > upper[ixx] + 1.0E-7) {
            throw new IllegalArgumentException("交叉处净高不足，请扩大范围");
         }

         y[ixx] = Math.max(lower[ixx], Math.min(upper[ixx], preferred[ixx]));
         queue.add(ixx);
         queued[ixx] = true;
      }

      Arrays.fill(visits, 0);

      while (!queue.isEmpty()) {
         int ixx = queue.remove();
         queued[ixx] = false;
         if (++visits[ixx] > n) {
            throw new IllegalArgumentException("交叉高程约束成环，请扩大范围");
         }

         for (InterchangeGradeProfile.Edge ex : outgoing.get(ixx)) {
            if (y[ex.to()] < y[ixx] + ex.rise() - 1.0E-8) {
               y[ex.to()] = y[ixx] + ex.rise();
               if (y[ex.to()] > upper[ex.to()] + 1.0E-7) {
                  throw new IllegalArgumentException("实际交叉处的净高与 15% 纵坡无法同时满足，请扩大范围");
               }

               if (!queued[ex.to()]) {
                  queue.add(ex.to());
                  queued[ex.to()] = true;
               }
            }
         }

         if ((ixx & 0xFF) == 0) {
            RoadPlanningBudget.check();
         }
      }

      for (int pass = 0; pass < (fair ? 32 : 0); pass++) {
         for (int k = 1; k < n - 1; k++) {
            if ((k & 0xFF) == 0) {
               RoadPlanningBudget.check();
            }

            int ixxx = (pass & 1) == 0 ? k : n - 1 - k;
            if (lower[ixxx] != upper[ixxx]) {
               double lo = lower[ixxx];
               double hi = upper[ixxx];

               for (InterchangeGradeProfile.Edge exx : incoming.get(ixxx)) {
                  lo = Math.max(lo, y[exx.from()] + exx.rise());
               }

               for (InterchangeGradeProfile.Edge exx : outgoing.get(ixxx)) {
                  hi = Math.min(hi, y[exx.to()] - exx.rise());
               }

               y[ixxx] = Math.max(lo, Math.min(hi, (y[ixxx - 1] + y[ixxx + 1]) * 0.5));
            }
         }
      }

      return y;
   }

   static int floor(double[] distance, double value) {
      int i = Arrays.binarySearch(distance, value);
      return i >= 0 ? i : Math.max(0, -i - 2);
   }

   static int ceil(double[] distance, double value) {
      int i = Arrays.binarySearch(distance, value);
      return i >= 0 ? i : Math.min(distance.length - 1, -i - 1);
   }

   private InterchangeGradeProfile() {
   }

   static record Edge(int from, int to, double rise) {
   }
}
