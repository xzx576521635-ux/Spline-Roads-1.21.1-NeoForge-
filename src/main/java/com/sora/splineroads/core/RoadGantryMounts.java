package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class RoadGantryMounts {
   private static final double VERGE = 0.75;
   private static final WeakIdentityValueCache<RoadGeometry.Mesh, RoadGeometry.Mesh> INTERIOR = new WeakIdentityValueCache<>(
      256, 500000L, m -> m.samples().size()
   );
   private static final WeakIdentityValueCache<RoadGeometry.Mesh, RoadGeometry.Mesh> MOTOR = new WeakIdentityValueCache<>(256, 500000L, m -> m.samples().size());
   private static final WeakIdentityValueCache<RoadGeometry.Mesh, RoadGeometry.Mesh> WALL = new WeakIdentityValueCache<>(256, 500000L, m -> m.samples().size());
   private static final WeakIdentityCache<RoadGeometry.Mesh, RoadSolidOverlap.Index> FOOTINGS = new WeakIdentityCache<>(128, 128L, i -> 1);
   private static final RoadStructures.Ground EDGE_GROUND = new RoadStructures.Ground() {
      @Override
      public double top(double x, double z, double y) {
         return Double.NaN;
      }

      @Override
      public boolean joined(RoadGeometry.V point) {
         return false;
      }

      @Override
      public boolean blocked(RoadStructures.Part part) {
         return false;
      }
   };

   public static boolean foot(RoadStructures.Part p) {
      return RoadGantry.member(p)
         && p.pier()
         && p.a().distance(p.b()) < 1.0E-8
         && (
            p.material() == RoadStructures.Material.STEEL && Math.abs(p.width() - 0.65) < 1.0E-8
               || p.material() == RoadStructures.Material.CONCRETE && p.width() >= 0.9499999899999999 && p.width() <= 1.20000001 && p.height() <= 0.45000001
               || p.material() == RoadStructures.Material.DARK_STEEL && Math.abs(p.width() - 0.85) < 1.0E-8 && p.height() <= 0.12000000999999999
         );
   }

   public static RoadGeometry.Mesh clearanceMesh(RoadGeometry.Mesh mesh) {
      RoadProfile.OuterRail rail = mesh.settings().options().outerRail();
      return !rail.sound(-1) && !rail.sound(1) ? mesh : WALL.get(mesh, m -> {
         ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

         for (RoadGeometry.Sample s : m.samples()) {
            double lo = RoadStreetscape.raised(m, s, -1) && rail.sound(-1) ? Math.max(0.0, 0.35 - RoadRailJoin.inset(m, s, -1)) : 0.0;
            double hi = RoadStreetscape.raised(m, s, 1) && rail.sound(1) ? Math.max(0.0, 0.35 - RoadRailJoin.inset(m, s, 1)) : 0.0;
            samples.add(new RoadGeometry.Sample(s.at((hi - lo) / 2.0, 0.0), s.left(), s.distance(), s.halfWidth() + (lo + hi) / 2.0));
         }

         return ribbon(m, samples);
      });
   }

   private static RoadGeometry.Mesh ribbon(RoadGeometry.Mesh mesh, List<RoadGeometry.Sample> samples) {
      return new RoadGeometry.Mesh(
         List.copyOf(samples),
         mesh.settings(),
         mesh.min(),
         mesh.max(),
         mesh.length(),
         mesh.closed(),
         mesh.controlPoint(),
         mesh.controls(),
         LaneSections.reference(mesh)
      );
   }

   private static boolean footingContact(RoadStructures.Part part, RoadGeometry.Mesh mesh) {
      return mesh.settings().structure() != RoadGeometry.Structure.GROUND
            && mesh.settings().structure() != RoadGeometry.Structure.TUNNEL
            && !RoadInfrastructure.customBridge(mesh)
         ? FOOTINGS.get(mesh, m -> new RoadSolidOverlap.Index(RoadStructures.edgeSlabs(m, EDGE_GROUND))).intersects(part)
         : false;
   }

   public static boolean blockedByRoad(RoadStructures.Part part, RoadGeometry.Mesh mesh, double headroom) {
      return RoadClearance.structureInvades(part, clearanceMesh(mesh), headroom) || footingContact(part, mesh);
   }

   public static boolean vergeContact(RoadStructures.Part part, RoadGeometry.Mesh mesh, RoadGantry.Station station, double headroom) {
      if (!foot(part)) {
         return false;
      } else {
         RoadGeometry.Mesh envelope = clearanceMesh(mesh);
         if (!blockedByRoad(part, mesh, headroom)) {
            return false;
         } else {
            RoadGeometry.Mesh inner = INTERIOR.get(
               mesh,
               m -> ribbon(
                     m,
                     m.samples().stream().map(s -> new RoadGeometry.Sample(s.center(), s.left(), s.distance(), Math.max(0.01, s.halfWidth() - 0.75))).toList()
                  )
            );
            if (!RoadClearance.structureInvades(part, inner, headroom) && !RoadClearance.structureInvades(part, MOTOR.get(mesh, LaneDeck::motorOnly), headroom)
               )
             {
               if (Math.abs(RoadQueries.horizontal(mesh, part.a()).sample().center().y() - station.sample().center().y()) > 0.75) {
                  return false;
               } else {
                  for (RoadClearance.Contact contact : RoadClearance.structureContacts(part, envelope, headroom)) {
                     if (contact.blocked() && Math.abs(contact.ours().y() - station.sample().center().y()) > 0.75) {
                        return false;
                     }
                  }

                  return true;
               }
            } else {
               return false;
            }
         }
      }
   }

   public static double tightOffset(RoadGeometry.Mesh mesh, RoadGantry.Station station, int side, double pedestal, RoadGeometry.V originalFoot) {
      RoadGeometry.Sample mount = station.sample();
      RoadGeometry.V normal = mount.left().mul((double)side);
      double extent = 0.5 * pedestal * (Math.abs(normal.x()) + Math.abs(normal.z()));
      double edge = mount.halfWidth();

      for (double d = Math.max(0.0, station.distance() - 1.1); d <= Math.min(mesh.length(), station.distance() + 1.1) + 1.0E-7; d += 0.2) {
         RoadGeometry.Sample at = RoadStructures.sample(mesh, d);
         double fascia = RoadStreetscape.raised(mesh, at, side) && !RoadInfrastructure.customBridge(mesh) ? 0.35 * RoadInfrastructure.endTaper(mesh, at) : 0.0;
         edge = Math.max(edge, at.at((double)side * (at.halfWidth() + fascia), 0.0).sub(mount.center()).dot(normal));
      }

      double shift = edge + extent + 1.0E-4 - originalFoot.sub(mount.center()).dot(normal);
      return Math.max(-0.6, Math.min(3.0, shift));
   }

   public static boolean boundaryPart(RoadGeometry.Mesh mesh, RoadStructures.Part p) {
      if (!p.pier() && !RoadGantry.member(p) && p.heights() == null) {
         boolean shape = p.material() == RoadStructures.Material.NOISE_BARRIER && Math.abs(p.height() - 2.5043584) < 1.0E-7
            || p.material() == RoadStructures.Material.CONCRETE && p.width() <= 0.71 && p.height() <= 0.81
            || p.material() == RoadStructures.Material.STEEL && p.width() <= 0.31 && p.height() <= 0.19
            || p.material() == RoadStructures.Material.DARK_STEEL && Math.abs(p.width() - 0.16) < 1.0E-7 && p.height() <= 1.25;
         if (!shape) {
            return false;
         } else {
            int side = 0;

            for (RoadGeometry.V v : List.of(p.a(), p.b())) {
               RoadQueries.Projection q = RoadQueries.horizontal(mesh, v);
               RoadGeometry.Sample s = q.sample();
               int here = q.lateral() < 0.0 ? -1 : 1;
               if (side != 0 && side != here) {
                  return false;
               }

               side = here;
               double rail = s.halfWidth() - RoadRailJoin.inset(mesh, s, here);
               if (Math.abs(Math.abs(q.lateral()) - rail) > 0.42 || v.y() < s.center().y() - 0.04 || v.y() > s.center().y() + 3.1) {
                  return false;
               }
            }

            return true;
         }
      } else {
         return false;
      }
   }

   private RoadGantryMounts() {
   }
}
