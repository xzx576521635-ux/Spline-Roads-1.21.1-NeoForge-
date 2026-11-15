package com.sora.splineroads.core;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class StreetPlan {
   public static final int VERSION = 1;
   public static final int MAX_CHUNKS = 1024;
   public static final int MAX_EDGES = 160;
   public static final int MAX_CENTERS = 8;
   public static final int MAX_ENTRIES = 8;

   private static Set<StreetPlan.Cell> ordered(Set<StreetPlan.Cell> cells) {
      return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(cells)));
   }

   private StreetPlan() {
   }

   public static record Cell(int x, int z) implements Comparable<StreetPlan.Cell> {
      public long key() {
         return (long)this.x << 32 ^ (long)this.z & 4294967295L;
      }

      public static StreetPlan.Cell from(long value) {
         return new StreetPlan.Cell((int)(value >> 32), (int)value);
      }

      public static StreetPlan.Cell at(double x, double z) {
         return new StreetPlan.Cell(Math.floorDiv((int)Math.floor(x), 16), Math.floorDiv((int)Math.floor(z), 16));
      }

      public int compareTo(StreetPlan.Cell other) {
         int c = Integer.compare(this.x, other.x);
         return c != 0 ? c : Integer.compare(this.z, other.z);
      }
   }

   public static record Edge(int a, int b, int rank, double bend) {
      public Edge(int a, int b, int rank) {
         this(a, b, rank, 0.0);
      }

      public Edge(int a, int b, int rank, double bend) {
         if (a != b && rank >= 0 && rank <= 2 && Double.isFinite(bend)) {
            this.a = a;
            this.b = b;
            this.rank = rank;
            this.bend = bend;
         } else {
            throw new IllegalArgumentException("道路连接无效");
         }
      }
   }

   public static record Entry(StreetPlan.Point point, double height, double yaw, boolean left, boolean existing, String reference) {
      public Entry(StreetPlan.Point point, double height, double yaw, boolean left, boolean existing, String reference) {
         Objects.requireNonNull(point);
         if (RoadGeometry.finite(height, yaw) && !(height < -2048.0) && !(height > 4096.0) && reference.length() <= 128) {
            this.point = point;
            this.height = height;
            this.yaw = yaw;
            this.left = left;
            this.existing = existing;
            this.reference = reference;
         } else {
            throw new IllegalArgumentException("对外接口数据无效");
         }
      }
   }

   public static enum Ground {
      LAND,
      WATER,
      OBSTACLE,
      UNKNOWN,
      ROAD;
   }

   public static record Input(
      Set<StreetPlan.Cell> selection,
      Set<StreetPlan.Cell> protection,
      List<StreetPlan.Point> centers,
      List<StreetPlan.Entry> entries,
      StreetPlan.Options options,
      Set<StreetPlan.Cell> corridor,
      boolean corridorConfirmed
   ) {
      public Input(
         Set<StreetPlan.Cell> selection,
         Set<StreetPlan.Cell> protection,
         List<StreetPlan.Point> centers,
         List<StreetPlan.Entry> entries,
         StreetPlan.Options options
      ) {
         this(selection, protection, centers, entries, options, Set.of(), false);
      }

      public Input(
         Set<StreetPlan.Cell> selection,
         Set<StreetPlan.Cell> protection,
         List<StreetPlan.Point> centers,
         List<StreetPlan.Entry> entries,
         StreetPlan.Options options,
         Set<StreetPlan.Cell> corridor,
         boolean corridorConfirmed
      ) {
         Objects.requireNonNull(options);
         selection = StreetPlan.ordered(selection);
         protection = StreetPlan.ordered(protection);
         centers = List.copyOf(centers);
         entries = List.copyOf(entries);
         corridor = StreetPlan.ordered(corridor);
         if (!selection.isEmpty() && selection.size() <= 1024 && protection.size() <= 1024 && centers.size() <= 8 && entries.size() <= 8) {
            HashSet<StreetPlan.Cell> all = new HashSet<>(selection);
            all.addAll(corridor);
            if (all.size() > 1024) {
               throw new IllegalArgumentException("选区与连接走廊总面积超限");
            } else {
               for (StreetPlan.Cell c : corridor) {
                  if (Math.abs((long)c.x()) > 1800000L
                     || Math.abs((long)c.z()) > 1800000L
                     || selection.stream().noneMatch(p -> Math.abs((long)c.x() - (long)p.x()) <= 8L && Math.abs((long)c.z() - (long)p.z()) <= 8L)) {
                     throw new IllegalArgumentException("连接走廊须在选区周边8区块以内");
                  }
               }

               if (!selection.containsAll(protection)) {
                  throw new IllegalArgumentException("保护区必须位于本次选区内");
               } else {
                  int minX = selection.stream().mapToInt(StreetPlan.Cell::x).min().orElseThrow();
                  int maxX = selection.stream().mapToInt(StreetPlan.Cell::x).max().orElseThrow();
                  int minZ = selection.stream().mapToInt(StreetPlan.Cell::z).min().orElseThrow();
                  int maxZ = selection.stream().mapToInt(StreetPlan.Cell::z).max().orElseThrow();
                  if ((long)maxX - (long)minX <= 63L
                     && (long)maxZ - (long)minZ <= 63L
                     && Math.abs((long)minX) <= 1800000L
                     && Math.abs((long)minZ) <= 1800000L
                     && Math.abs((long)maxX) <= 1800000L
                     && Math.abs((long)maxZ) <= 1800000L) {
                     HashSet<StreetPlan.Cell> reached = new HashSet<>();
                     ArrayDeque<StreetPlan.Cell> queue = new ArrayDeque<>();
                     queue.add(selection.iterator().next());

                     while (!queue.isEmpty()) {
                        StreetPlan.Cell cx = queue.remove();
                        if (selection.contains(cx) && reached.add(cx)) {
                           queue.add(new StreetPlan.Cell(cx.x + 1, cx.z));
                           queue.add(new StreetPlan.Cell(cx.x - 1, cx.z));
                           queue.add(new StreetPlan.Cell(cx.x, cx.z + 1));
                           queue.add(new StreetPlan.Cell(cx.x, cx.z - 1));
                        }
                     }

                     if (reached.size() != selection.size()) {
                        throw new IllegalArgumentException("一次只能规划一个连续选区；请分开创建任务");
                     } else {
                        for (StreetPlan.Point cx : centers) {
                           if (!selection.contains(StreetPlan.Cell.at(cx.x, cx.z)) || protection.contains(StreetPlan.Cell.at(cx.x, cx.z))) {
                              throw new IllegalArgumentException("规划中心必须位于可建设选区内");
                           }
                        }

                        Boolean traffic = null;

                        for (StreetPlan.Entry e : entries) {
                           if (e.existing) {
                              if (traffic != null && traffic != e.left) {
                                 throw new IllegalArgumentException("已有入口的行驶方向冲突，请先统一后重试");
                              }

                              traffic = e.left;
                           }
                        }

                        if (traffic != null && traffic != options.left) {
                           throw new IllegalArgumentException("规划行驶方向必须与已有入口一致");
                        } else {
                           this.selection = selection;
                           this.protection = protection;
                           this.centers = centers;
                           this.entries = entries;
                           this.options = options;
                           this.corridor = corridor;
                           this.corridorConfirmed = corridorConfirmed;
                        }
                     }
                  } else {
                     throw new IllegalArgumentException("首版规划边长最多64区块，且必须位于世界边界内");
                  }
               }
            }
         } else {
            throw new IllegalArgumentException("规划选区、中心或接口数量超限");
         }
      }

      public Set<StreetPlan.Cell> captureArea() {
         TreeSet<StreetPlan.Cell> area = new TreeSet<>(this.selection);
         area.addAll(this.corridor);
         return Collections.unmodifiableSet(area);
      }

      public boolean allowed(double x, double z) {
         StreetPlan.Cell c = StreetPlan.Cell.at(x, z);
         return (this.selection.contains(c) || this.corridorConfirmed && this.corridor.contains(c)) && !this.protection.contains(c);
      }
   }

   public static record Issue(StreetPlan.Point point, String message, boolean fatal) {
   }

   public static record Options(
      StreetPlan.Style style,
      double density,
      double blockSize,
      double direction,
      long seed,
      double primaryWidth,
      double secondaryWidth,
      double localWidth,
      RoadGeometry.Style primarySection,
      RoadGeometry.Style secondarySection,
      RoadGeometry.Style localSection,
      boolean left,
      boolean sidewalks,
      boolean lighting,
      double earthwork
   ) {
      public Options(
         StreetPlan.Style style,
         double density,
         double blockSize,
         double direction,
         long seed,
         double primaryWidth,
         double secondaryWidth,
         double localWidth,
         RoadGeometry.Style primarySection,
         RoadGeometry.Style secondarySection,
         RoadGeometry.Style localSection,
         boolean left,
         boolean sidewalks,
         boolean lighting,
         double earthwork
      ) {
         Objects.requireNonNull(style);

         for (RoadGeometry.Style section : List.of(primarySection, secondarySection, localSection)) {
            if (RoadProfile.catalog(section).type() != RoadProfile.Type.ORDINARY || !RoadProfile.catalog(section).twoWay()) {
               throw new IllegalArgumentException("自动路网首版仅支持普通双向道路断面");
            }
         }

         if (RoadGeometry.finite(density, blockSize, direction, primaryWidth, secondaryWidth, localWidth, earthwork)
            && !(density < 0.1)
            && !(density > 1.0)
            && !(blockSize < 24.0)
            && !(blockSize > 192.0)
            && !(Math.abs(direction) > 360.0)
            && !(localWidth < 8.0)
            && !(secondaryWidth < 8.0)
            && !(primaryWidth < 8.0)
            && !(primaryWidth > 24.0)
            && !(secondaryWidth > 24.0)
            && !(localWidth > 24.0)
            && !(earthwork < 0.0)
            && !(earthwork > 3.0)) {
            this.style = style;
            this.density = density;
            this.blockSize = blockSize;
            this.direction = direction;
            this.seed = seed;
            this.primaryWidth = primaryWidth;
            this.secondaryWidth = secondaryWidth;
            this.localWidth = localWidth;
            this.primarySection = primarySection;
            this.secondarySection = secondarySection;
            this.localSection = localSection;
            this.left = left;
            this.sidewalks = sidewalks;
            this.lighting = lighting;
            this.earthwork = earthwork;
         } else {
            throw new IllegalArgumentException("路网密度、街坊、断面或地形预算超出首版范围");
         }
      }

      public static StreetPlan.Options defaults() {
         return new StreetPlan.Options(
            StreetPlan.Style.DOWNTOWN,
            0.65,
            48.0,
            0.0,
            1L,
            18.0,
            12.0,
            9.0,
            RoadGeometry.Style.O4_YELLOW,
            RoadGeometry.Style.O2_YELLOW,
            RoadGeometry.Style.O2_YELLOW,
            false,
            true,
            true,
            1.0
         );
      }

      public RoadGeometry.Style section(int rank) {
         return rank == 0 ? this.primarySection : (rank == 1 ? this.secondarySection : this.localSection);
      }

      public double width(int rank) {
         return rank == 0 ? this.primaryWidth : (rank == 1 ? this.secondaryWidth : this.localWidth);
      }

      public double fullWidth(int rank) {
         return this.width(rank) + (double)(this.sidewalks ? 4 : 0) + 4.0;
      }

      public double spacing() {
         return Math.max(2.0 * Math.max(this.primaryWidth, Math.max(this.secondaryWidth, this.localWidth)) + 64.0, this.blockSize + this.fullWidth(0))
            * (1.5 - 0.5 * this.density);
      }
   }

   public static record Plan(
      StreetPlan.Input input, List<StreetPlan.Vertex> vertices, List<StreetPlan.Edge> edges, List<StreetPlan.Point> centers, List<StreetPlan.Issue> issues
   ) {
      public Plan(
         StreetPlan.Input input, List<StreetPlan.Vertex> vertices, List<StreetPlan.Edge> edges, List<StreetPlan.Point> centers, List<StreetPlan.Issue> issues
      ) {
         vertices = List.copyOf(vertices);
         edges = List.copyOf(edges);
         centers = List.copyOf(centers);
         issues = List.copyOf(issues);
         this.input = input;
         this.vertices = vertices;
         this.edges = edges;
         this.centers = centers;
         this.issues = issues;
      }

      public boolean buildable() {
         return !this.edges.isEmpty() && this.issues.stream().noneMatch(StreetPlan.Issue::fatal);
      }

      public int junctions() {
         int[] counts = new int[this.vertices.size()];

         for (StreetPlan.Edge e : this.edges) {
            counts[e.a]++;
            counts[e.b]++;
         }

         return (int)Arrays.stream(counts).filter(n -> n >= 3).count();
      }
   }

   public static record Point(double x, double z) {
      public Point(double x, double z) {
         if (RoadGeometry.finite(x, z) && !(Math.abs(x) > 2.9E7) && !(Math.abs(z) > 2.9E7)) {
            this.x = x;
            this.z = z;
         } else {
            throw new IllegalArgumentException("规划位置超出可用世界范围");
         }
      }

      public double distance(StreetPlan.Point other) {
         return Math.hypot(this.x - other.x, this.z - other.z);
      }

      public StreetPlan.Point mix(StreetPlan.Point other, double t) {
         return new StreetPlan.Point(this.x + (other.x - this.x) * t, this.z + (other.z - this.z) * t);
      }
   }

   public static enum Style {
      DOWNTOWN("中心城区"),
      OLD_TOWN("老城区"),
      NEW_TOWN("新城区"),
      SUBURB("郊区"),
      RURAL("农村"),
      TOWN("小镇");

      public final String label;

      private Style(String label) {
         this.label = label;
      }

      public StreetPlan.Style next() {
         return values()[(this.ordinal() + 1) % values().length];
      }
   }

   public static record Surface(double y, StreetPlan.Ground ground) {
   }

   @FunctionalInterface
   public interface Terrain {
      StreetPlan.Surface at(double var1, double var3);
   }

   public static record Vertex(int id, StreetPlan.Point point, double height) {
   }
}
