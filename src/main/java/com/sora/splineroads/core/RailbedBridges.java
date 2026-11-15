package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RailbedBridges {
   public static List<RoadStreetscape.Span> runs(RoadGeometry.Mesh mesh) {
      if (mesh.settings().style() == RoadGeometry.Style.RAILBED
         && mesh.settings().structure() == RoadGeometry.Structure.AUTO
         && mesh.settings().options().infrastructure().bridge() != RoadInfrastructure.Bridge.STANDARD) {
         RoadStreetscape.SideStructures sides = mesh.settings().options().streetscape().sides();
         ArrayList<RoadStreetscape.Span> out = new ArrayList<>();

         for (RoadStreetscape.Span a : sides.negativeRaised()) {
            for (RoadStreetscape.Span b : sides.positiveRaised()) {
               double from = Math.max(a.from(), b.from());
               double to = Math.min(a.to(), b.to());
               if (to - from >= 8.0) {
                  out.add(new RoadStreetscape.Span(from, to));
               }
            }
         }

         return List.copyOf(out);
      } else {
         return List.of();
      }
   }

   public static boolean customAt(RoadGeometry.Mesh mesh, double station) {
      return runs(mesh).stream().anyMatch(s -> station >= s.from() && station <= s.to());
   }

   public static List<RoadStructures.Part> plan(RoadGeometry.Mesh mesh, RoadStructures.Ground ground) {
      if (ground.sharedDeck()) {
         return List.of();
      } else {
         ArrayList<RoadStructures.Part> out = new ArrayList<>();

         for (RoadStreetscape.Span span : runs(mesh)) {
            ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
            samples.add(RoadStructures.sample(mesh, span.from()));
            mesh.samples().stream().filter(s -> s.distance() > span.from() && s.distance() < span.to()).forEach(samples::add);
            samples.add(RoadStructures.sample(mesh, span.to()));
            RoadGeometry.Mesh segment = RoadRibbon.mesh(samples, mesh.settings().structure(RoadGeometry.Structure.BRIDGE));
            out.addAll(RoadInfrastructure.plan(segment, ground));
         }

         return List.copyOf(out);
      }
   }

   private RailbedBridges() {
   }
}
