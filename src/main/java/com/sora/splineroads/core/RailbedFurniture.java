package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

public final class RailbedFurniture {
   public static final String WIRE = "sr:catenary_wire";

   public static boolean visualOnly(RoadStructures.Part p) {
      return p.model().equals("sr:catenary_wire");
   }

   public static boolean continues(RoadGeometry.Mesh own, RoadGeometry.Mesh other, List<RoadStructures.Part> shell, boolean first) {
      if (other.settings().options().railbed().active()
         && other.settings().options().railbed().fittings().catenary()
         && other.settings().structure() != RoadGeometry.Structure.TUNNEL) {
         RoadGeometry.Sample at = first ? own.first() : own.last();
         if (Math.abs(own.settings().options().railbed().fittings().wireHeight() - other.settings().options().railbed().fittings().wireHeight()) > 1.0E-7) {
            return false;
         } else {
            for (RoadGeometry.Sample end : List.of(other.first(), other.last())) {
               if (at.center().distance(end.center()) < 0.01
                  && Math.abs(at.left().dot(end.left())) > 0.94
                  && RoadAutoTunnels.regions(shell).stream().noneMatch(r -> end.distance() >= r.from() - 0.5 && end.distance() <= r.to() + 0.5)) {
                  return true;
               }
            }

            return false;
         }
      } else {
         return false;
      }
   }

   public static List<RoadStructures.Part> plan(
      RoadGeometry.Mesh mesh,
      RoadGeometry.Structure mode,
      List<RoadStructures.Part> shell,
      List<RailbedPath.Path> paths,
      RailbedOptions options,
      RoadStructures.Ground ground
   ) {
      return plan(mesh, mode, shell, paths, options, ground, RoadFurniture.Phase.DEFAULT);
   }

   public static List<RoadStructures.Part> plan(
      RoadGeometry.Mesh mesh,
      RoadGeometry.Structure mode,
      List<RoadStructures.Part> shell,
      List<RailbedPath.Path> paths,
      RailbedOptions options,
      RoadStructures.Ground ground,
      RoadFurniture.Phase phase
   ) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      List<RoadAutoTunnels.Region> roofs = mode == RoadGeometry.Structure.TUNNEL
         ? List.of(new RoadAutoTunnels.Region(0.0, mesh.length()))
         : RoadAutoTunnels.regions(shell);

      for (RoadAutoTunnels.Region roof : roofs) {
         for (double d = roof.from() + 6.0; d < roof.to() - 1.0; d += 16.0) {
            RoadGeometry.Sample a = RoadStructures.sample(mesh, d);
            RoadGeometry.Sample b = RoadStructures.sample(mesh, Math.min(d + 1.2, roof.to() - 0.1));

            for (int side : new int[]{-1, 1}) {
               double lateralA = (double)side * a.halfWidth() * 0.65;
               double lateralB = (double)side * b.halfWidth() * 0.65;
               double topA = mode == RoadGeometry.Structure.TUNNEL
                  ? RoadInfrastructure.ceiling(mesh.settings().options().infrastructure(), a.halfWidth(), lateralA)
                  : 6.0;
               double topB = mode == RoadGeometry.Structure.TUNNEL
                  ? RoadInfrastructure.ceiling(mesh.settings().options().infrastructure(), b.halfWidth(), lateralB)
                  : 6.0;
               if (options.tunnel() == RoadInfrastructure.Tunnel.ROUND) {
                  topA = RailbedTunnel.ceiling(mesh.settings().options().infrastructure(), a.halfWidth(), lateralA);
                  topB = RailbedTunnel.ceiling(mesh.settings().options().infrastructure(), b.halfWidth(), lateralB);
               }

               add(
                  out,
                  new RoadStructures.Part(
                     a.at(lateralA, -topA + 0.01),
                     b.at(lateralB, -topB + 0.01),
                     0.22,
                     0.2,
                     false,
                     RoadStructures.Material.LAMP,
                     a.left().mul(0.11),
                     b.left().mul(0.11),
                     "sr:railbed_light"
                  ),
                  ground
               );
            }
         }
      }

      if (options.catenary() && !paths.isEmpty()) {
         List<RoadGeometry.Mesh> trackMeshes = paths.stream().map(p -> RailbedGeometry.plan(List.of(p), 0.0, RoadGeometry.Structure.AUTO).mesh()).toList();
         double firstPole = phase.first(0.5, options.poleSpacing());
         if (phase.direction() < 0 && firstPole < 1.0E-7) {
            firstPole += options.poleSpacing();
         }

         TreeSet<Double> stations = new TreeSet<>();

         for (double d = firstPole; phase.direction() > 0 ? d < mesh.length() - 1.0E-7 : d <= mesh.length() + 1.0E-7; d += options.poleSpacing()) {
            stations.add(Math.min(d, mesh.length()));
         }

         TreeSet<Double> supported = new TreeSet<>();

         for (double d : stations) {
            if (!roofs.stream().anyMatch(r -> d >= r.from() - 0.5 && d <= r.to() + 0.5)) {
               RoadGeometry.Sample at = RoadStructures.sample(mesh, d);
               if (!ground.furnitureClear(at.center())) {
                  supported.add(d);
                  List<RoadGeometry.Mesh> orderedMeshes = trackMeshes.stream()
                     .sorted(Comparator.comparingDouble(m -> RoadQueries.horizontal(m, at.center()).sample().center().sub(at.center()).dot(at.left())))
                     .toList();
                  List<RoadGeometry.V> tracks = orderedMeshes.stream().map(m -> RoadQueries.horizontal(m, at.center()).sample().center()).toList();
                  List<RoadGeometry.V> wires = tracks.stream().map(p -> p.add(new RoadGeometry.V(0.0, options.wireHeight(), 0.0))).toList();
                  ArrayList<RoadGeometry.V> poles = new ArrayList<>();
                  if (tracks.size() == 1) {
                     poles.add(at.at((double)options.singleSide() * (at.halfWidth() - 0.16), 0.0));
                  } else {
                     for (int i = 0; i + 1 < tracks.size(); i += 2) {
                        poles.add(tracks.get(i).add(tracks.get(i + 1)).mul(0.5));
                     }
                  }

                  for (int i = 0; i < poles.size(); i++) {
                     RoadGeometry.V foot = poles.get(i);
                     RoadGeometry.V top = foot.add(new RoadGeometry.V(0.0, options.wireHeight() + 0.25, 0.0));
                     add(
                        out,
                        new RoadStructures.Part(
                           foot, foot, 0.18, options.wireHeight() + 0.55, true, RoadStructures.Material.STEEL, null, null, "sr:catenary_pole"
                        ),
                        ground
                     );
                     int from = tracks.size() == 1 ? 0 : i * 2;
                     int to = tracks.size() == 1 ? 0 : Math.min(tracks.size() - 1, i == poles.size() - 1 ? tracks.size() - 1 : from + 1);

                     for (int k = from; k <= to; k++) {
                        RoadGeometry.V tip = wires.get(k).add(new RoadGeometry.V(0.0, 0.25, 0.0));
                        add(out, new RoadStructures.Part(top, tip, 0.12, 0.12, false, RoadStructures.Material.STEEL, null, null, "sr:catenary_arm"), ground);
                        add(
                           out,
                           new RoadStructures.Part(
                              wires.get(k), wires.get(k), 0.1, 0.25, true, RoadStructures.Material.DARK_STEEL, null, null, "sr:catenary_drop"
                           ),
                           ground
                        );
                     }
                  }
               }
            }
         }

         ArrayList<RoadAutoTunnels.Region> boundaries = new ArrayList<>();
         double begin = 0.0;

         for (RoadAutoTunnels.Region roof : roofs) {
            if (roof.from() > begin) {
               boundaries.add(new RoadAutoTunnels.Region(begin, roof.from()));
            }

            begin = Math.max(begin, roof.to());
         }

         if (begin < mesh.length()) {
            boundaries.add(new RoadAutoTunnels.Region(begin, mesh.length()));
         }

         for (RoadAutoTunnels.Region run : boundaries) {
            NavigableSet<Double> poles = supported.subSet(run.from(), true, run.to(), true);
            boolean first = run.from() < 1.0E-7 && ground.wireContinues(true);
            boolean last = run.to() > mesh.length() - 1.0E-7 && ground.wireContinues(false);
            if (!poles.isEmpty() || first && last) {
               double from = first ? 0.0 : poles.first();
               double to = last ? mesh.length() : poles.last();

               for (RoadGeometry.Mesh track : trackMeshes) {
                  for (double dx = from; dx < to - 1.0E-7; dx += 2.0) {
                     double end = Math.min(to, dx + 2.0);
                     RoadGeometry.V a = RoadQueries.horizontal(track, RoadStructures.sample(mesh, dx).center())
                        .sample()
                        .center()
                        .add(new RoadGeometry.V(0.0, options.wireHeight(), 0.0));
                     RoadGeometry.V b = RoadQueries.horizontal(track, RoadStructures.sample(mesh, end).center())
                        .sample()
                        .center()
                        .add(new RoadGeometry.V(0.0, options.wireHeight(), 0.0));
                     if (a.distance(b) > 1.0E-7) {
                        out.add(new RoadStructures.Part(a, b, 0.035, 0.035, false, RoadStructures.Material.DARK_STEEL, null, null, "sr:catenary_wire"));
                     }
                  }
               }
            }
         }

         return List.copyOf(out);
      } else {
         return List.copyOf(out);
      }
   }

   private static void add(List<RoadStructures.Part> parts, RoadStructures.Part p, RoadStructures.Ground ground) {
      if (ground.blocked(p)) {
         throw new IllegalArgumentException(p.model().startsWith("sr:catenary") ? "接触网支柱或悬臂与通行空间冲突；请增大轨间距、提高接触网或关闭接触网" : "铁路照明与通行空间冲突");
      } else {
         parts.add(p);
      }
   }

   private RailbedFurniture() {
   }
}
