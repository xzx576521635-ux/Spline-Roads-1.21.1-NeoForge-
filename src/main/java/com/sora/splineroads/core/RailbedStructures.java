package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RailbedStructures {
   public static RoadSolidOverlap.Index clearance(List<RailbedPath.Path> paths) {
      return clearance(paths, 5.0);
   }

   public static RoadSolidOverlap.Index catenaryClearance(List<RailbedPath.Path> paths) {
      return clearance(paths, 3.4);
   }

   private static RoadSolidOverlap.Index clearance(List<RailbedPath.Path> paths, double bodyWidth) {
      ArrayList<RoadStructures.Part> parts = new ArrayList<>();

      for (RailbedPath.Path path : paths) {
         List<RoadGeometry.V> points = path.positions();
         double width = bodyWidth + 2.0 * RailbedGeometry.sweep(points);

         for (int i = 1; i < points.size(); i++) {
            RoadGeometry.V a = points.get(i - 1);
            RoadGeometry.V b = points.get(i);
            if (!(a.sub(b).horizontalLength() < 1.0E-8)) {
               parts.add(
                  new RoadStructures.Part(
                     a.add(new RoadGeometry.V(0.0, 0.015, 0.0)),
                     b.add(new RoadGeometry.V(0.0, 0.015, 0.0)),
                     1.0,
                     5.97,
                     false,
                     RoadStructures.Material.DEFAULT,
                     RailbedPath.tangent(points, i - 1).left().mul(width / 2.0),
                     RailbedPath.tangent(points, i).left().mul(width / 2.0),
                     ""
                  )
               );
            }
         }
      }

      return new RoadSolidOverlap.Index(parts);
   }

   private static String location(RoadGeometry.V p) {
      return Math.round(p.x()) + ", " + Math.round(p.y()) + ", " + Math.round(p.z());
   }

   private RailbedStructures() {
   }

   public interface Ground extends RoadStructures.Ground {
      @Override
      default double top(double x, double z, double y) {
         return this.floor(new RoadGeometry.V(x, y, z));
      }

      @Override
      default boolean joined(RoadGeometry.V p) {
         return false;
      }

      double floor(RoadGeometry.V var1);

      boolean covered(RoadGeometry.V var1);

      @Override
      boolean blocked(RoadStructures.Part var1);
   }

   public static final class Planner {
      private final RoadGeometry.Mesh mesh;
      private final RoadGeometry.Structure mode;
      private final RailbedStructures.Ground ground;
      private final RailbedOptions options;
      private final List<RailbedPath.Path> paths;
      private final List<RoadStructures.Part> parts = new ArrayList<>();
      private int section = 1;
      private boolean supportsDone;

      public Planner(RoadGeometry.Mesh mesh, RoadGeometry.Structure mode, RailbedStructures.Ground ground) {
         this(mesh, mode, ground, RailbedOptions.DEFAULT, List.of());
      }

      public Planner(RoadGeometry.Mesh mesh, RoadGeometry.Structure mode, RailbedStructures.Ground ground, RailbedOptions options, List<RailbedPath.Path> paths) {
         this.mesh = mesh;
         this.mode = mode;
         this.ground = ground;
         this.options = options;
         this.paths = List.copyOf(paths);
      }

      public boolean done() {
         return this.section >= this.mesh.samples().size() && this.supportsDone;
      }

      public void step() {
         if (this.section < this.mesh.samples().size()) {
            RoadGeometry.Sample a = this.mesh.samples().get(this.section - 1);
            RoadGeometry.Sample b = this.mesh.samples().get(this.section++);
            boolean tunnel = this.mode == RoadGeometry.Structure.TUNNEL;

            for (int side : new int[]{-1, 1}) {
               if (tunnel) {
                  this.add(
                     new RoadStructures.Part(
                        a.at((double)side * (a.halfWidth() + 0.175), 0.0),
                        b.at((double)side * (b.halfWidth() + 0.175), 0.0),
                        0.35,
                        6.35,
                        false,
                        RoadStructures.Material.TUNNEL,
                        a.left().mul(0.175),
                        b.left().mul(0.175),
                        "sr:railbed_wall"
                     )
                  );
               }

               if (this.options.rail(side)) {
                  this.add(
                     new RoadStructures.Part(
                        a.at((double)side * (a.halfWidth() - 0.175), 0.0),
                        b.at((double)side * (b.halfWidth() - 0.175), 0.0),
                        0.35,
                        1.2,
                        false,
                        RoadStructures.Material.CONCRETE,
                        a.left().mul(0.175),
                        b.left().mul(0.175),
                        "sr:railbed_guardrail"
                     )
                  );
               }
            }

            if (tunnel) {
               this.add(
                  new RoadStructures.Part(
                     a.at(0.0, -6.0),
                     b.at(0.0, -6.0),
                     1.0,
                     0.35,
                     false,
                     RoadStructures.Material.TUNNEL,
                     a.left().mul(a.halfWidth() + 0.35),
                     b.left().mul(b.halfWidth() + 0.35),
                     "sr:railbed_roof"
                  )
               );
            }
         } else {
            if (this.mode == RoadGeometry.Structure.AUTO) {
               RoadGeometry.Mesh automatic = new RoadGeometry.Mesh(
                  this.mesh.samples(),
                  this.mesh.settings().structure(RoadGeometry.Structure.AUTO),
                  this.mesh.min(),
                  this.mesh.max(),
                  this.mesh.length(),
                  this.mesh.closed()
               );
               List<RoadStructures.Part> shell = RoadAutoTunnels.enclose(automatic, this.ground, this.parts)
                  .stream()
                  .filter(p -> p.material() != RoadStructures.Material.LAMP)
                  .toList();
               this.parts.clear();

               for (RoadStructures.Part part : shell) {
                  this.add(part);
               }
            }

            if (this.mode != RoadGeometry.Structure.TUNNEL) {
               RoadGeometry.Mesh supportMesh = new RoadGeometry.Mesh(
                  this.mesh.samples(), this.mesh.settings().structure(this.mode), this.mesh.min(), this.mesh.max(), this.mesh.length(), this.mesh.closed()
               );
               this.parts.addAll(RoadStructures.supports(supportMesh, this.ground, RoadFurniture.Phase.DEFAULT));
            }

            this.parts.addAll(RailbedFurniture.plan(this.mesh, this.mode, this.parts, this.paths, this.options, this.ground));
            this.supportsDone = true;
         }
      }

      private void add(RoadStructures.Part part) {
         if (this.ground.blocked(part)) {
            throw new IllegalArgumentException("铁路构件侵入既有通行空间：" + RailbedStructures.location(part.a()));
         } else {
            this.parts.add(part);
         }
      }

      public List<RoadStructures.Part> result() {
         if (!this.done()) {
            throw new IllegalStateException("铁路结构仍在规划");
         } else {
            return List.copyOf(this.parts);
         }
      }
   }
}
