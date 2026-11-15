package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map.Entry;

public final class RoadPartCompaction {
   private static boolean eligible(RoadStructures.Part p) {
      return !p.pier()
         && p.heights() == null
         && p.model().isEmpty()
         && (p.material() == RoadStructures.Material.TACTILE || p.material().name().startsWith("WALK_"))
         && p.a().distance(p.b()) > 1.0E-6;
   }

   private static RoadPartCompaction.Seam seam(RoadStructures.Part p, boolean start) {
      List<RoadGeometry.V> q = p.base();
      return new RoadPartCompaction.Seam(
         p.material(),
         p.model(),
         Math.round(p.height() * 1.0E9),
         RoadPartCompaction.Point.of(q.get(start ? 0 : 3)),
         RoadPartCompaction.Point.of(q.get(start ? 1 : 2))
      );
   }

   private static boolean straight(RoadGeometry.V a, RoadGeometry.V middle, RoadGeometry.V b) {
      RoadGeometry.V span = b.sub(a);
      double length = span.dot(span);
      if (length < 1.0E-12) {
         return false;
      } else {
         double t = middle.sub(a).dot(span) / length;
         return t > 0.0 && t < 1.0 && a.add(span.mul(t)).distance(middle) < 1.0E-8;
      }
   }

   private static RoadStructures.Part join(RoadStructures.Part a, RoadStructures.Part b) {
      if (!(a.a().distance(b.b()) > 16.0) && !(Math.abs(a.height() - b.height()) > 1.0E-10)) {
         List<RoadGeometry.V> x = a.base();
         List<RoadGeometry.V> y = b.base();
         return !(x.get(3).distance(y.get(0)) > 1.0E-8)
               && !(x.get(2).distance(y.get(1)) > 1.0E-8)
               && straight(x.get(0), x.get(3), y.get(3))
               && straight(x.get(1), x.get(2), y.get(2))
            ? new RoadStructures.Part(
               a.a(), b.b(), Math.max(a.width(), b.width()), a.height(), false, a.material(), x.get(0).sub(a.a()), y.get(3).sub(b.b()), a.model()
            )
            : null;
      } else {
         return null;
      }
   }

   public static List<RoadStructures.Part> compact(List<RoadStructures.Part> parts) {
      if (parts.size() < 2) {
         return parts;
      } else {
         HashMap<RoadPartCompaction.Seam, List<Integer>> starts = new HashMap<>();

         for (int i = 0; i < parts.size(); i++) {
            if (eligible(parts.get(i))) {
               starts.computeIfAbsent(seam(parts.get(i), true), k -> new ArrayList<>()).add(i);
            }
         }

         boolean[] used = new boolean[parts.size()];
         ArrayList<RoadStructures.Part> out = new ArrayList<>();
         boolean changed = false;

         for (int ix = 0; ix < parts.size(); ix++) {
            if (!used[ix]) {
               RoadStructures.Part current = parts.get(ix);
               used[ix] = true;
               if (eligible(current)) {
                  while (true) {
                     RoadStructures.Part next = null;
                     int selected = -1;

                     for (int candidate : starts.getOrDefault(seam(current, false), List.of())) {
                        if (!used[candidate]) {
                           next = join(current, parts.get(candidate));
                           if (next != null) {
                              selected = candidate;
                              break;
                           }
                        }
                     }

                     if (selected < 0) {
                        break;
                     }

                     used[selected] = true;
                     current = next;
                     changed = true;
                  }
               }

               out.add(current);
            }
         }

         return changed ? List.copyOf(out) : parts;
      }
   }

   public static String budgetMessage(UUID road, List<RoadStructures.Part> parts, int limit) {
      HashMap<String, Integer> counts = new HashMap<>();

      for (RoadStructures.Part p : parts) {
         String group = p.material() == RoadStructures.Material.TACTILE
            ? "盲道"
            : (
               p.material().name().startsWith("WALK_")
                  ? "铺装"
                  : (!p.material().name().contains("LEAVES") && !p.material().name().endsWith("_LOG") ? p.material().name() : "树木")
            );
         counts.merge(group, 1, Integer::sum);
      }

      String main = counts.entrySet()
         .stream()
         .sorted(Entry.<String, Integer>comparingByValue().reversed().thenComparing(Entry.comparingByKey()))
         .limit(3L)
         .map(e -> e.getKey() + " " + e.getValue())
         .reduce((a, b) -> a + "、" + b)
         .orElse("无");
      return "道路 " + road + "：设施 " + parts.size() + "/" + limit + "（" + main + "）；请缩短本段或调整对应设施";
   }

   private RoadPartCompaction() {
   }

   private static record Point(long x, long y, long z) {
      static RoadPartCompaction.Point of(RoadGeometry.V v) {
         return new RoadPartCompaction.Point(Math.round(v.x() * 1000000.0), Math.round(v.y() * 1000000.0), Math.round(v.z() * 1000000.0));
      }
   }

   private static record Seam(RoadStructures.Material material, String model, long height, RoadPartCompaction.Point left, RoadPartCompaction.Point right) {
   }
}
