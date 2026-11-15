package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

public final class ElevatedSigns {
   private static final String SEAT = "sr:elevated_access_sign_seat";

   public static List<SmartSigns.Branch> destinations(RoadGeometry.Mesh host, ElevatedAccess.Reservation access, int flow) {
      int outer = host.settings().options().leftTraffic() ? -1 : 1;
      boolean paired = access.additions().stream().anyMatch(a -> a.sign() == flow);
      SmartSigns.Branch elevated = new SmartSigns.Branch("elevated", List.of(), paired ? -outer : 0, true, List.of());
      return paired ? List.of(elevated, new SmartSigns.Branch("surface", List.of(), outer, true, List.of())) : List.of(elevated);
   }

   private static SmartSigns.Support placement(RoadGeometry.Mesh host, LaneSections.Cut cut, double lead, boolean paired) {
      int flow = cut.sign();
      double station = cut.begin() - (double)flow * lead;
      if (!(station < 1.0) && !(station > host.length() - 1.0)) {
         RoadGeometry.Sample at = RoadStructures.sample(host, station);
         RoadProfile.Layout layout = RoadProfile.layout(host, at);
         int side = LaneAdditions.side(layout, flow);
         double offset = layout.cycleWidth() > 0.1 && host.settings().options().streetscape().separator() == RoadStreetscape.Separator.GREEN
            ? layout.outer(side) + (double)side * RoadStreetscape.separatorWidth(host.settings().options()) * 0.5
            : (double)side * (at.halfWidth() + (host.settings().options().sidewalk().enabled() ? 0.6 : -0.22));
         RoadGeometry.V right = at.left().mul((double)flow);
         double inner = layout.catalog().twoWay() ? layout.medianCenter() : layout.outer(-side) - (double)side * 0.55;
         return new SmartSigns.Support(at.at(offset, -0.42), right, right.left(), paired, -side * flow, (inner - offset) * (double)flow);
      } else {
         return null;
      }
   }

   public static SmartSigns.Support saved(RoadGeometry.Mesh host, ElevatedAccess.Reservation access, List<RoadStructures.Part> structures) {
      LaneSections.Cut exit = access.cuts().stream().filter(c -> !c.arrival()).findFirst().orElse(null);
      if (exit == null) {
         return null;
      } else {
         List<RoadStructures.Part> seats = structures.stream().filter(p -> p.model().equals("sr:elevated_access_sign_seat")).toList();

         for (int lead = 18; lead <= 54; lead += 3) {
            SmartSigns.Support mount = placement(host, exit, (double)lead, destinations(host, access, exit.sign()).size() == 2);
            if (mount != null) {
               if (hasSeat(seats, seat(mount, mount.gantrySpan())) && hasSeat(seats, seat(mount, 0.0))) {
                  return mount;
               }

               SmartSigns.Support oldGantry = new SmartSigns.Support(
                  mount.foot(),
                  mount.right(),
                  mount.front(),
                  mount.paired(),
                  mount.cantileverSide(),
                  mount.gantrySpan() - (double)mount.cantileverSide() * 0.58
               );
               if (hasSeat(seats, seat(oldGantry, oldGantry.gantrySpan())) && hasSeat(seats, seat(oldGantry, 0.0))) {
                  return oldGantry;
               }

               SmartSigns.Support legacy = new SmartSigns.Support(mount.foot(), mount.right(), mount.front(), mount.paired(), mount.cantileverSide());
               if (hasSeat(seats, seat(legacy, 0.0))) {
                  return legacy;
               }
            }
         }

         return null;
      }
   }

   private static boolean hasSeat(List<RoadStructures.Part> seats, RoadStructures.Part target) {
      return seats.stream()
         .anyMatch(p -> p.a().add(new RoadGeometry.V(0.0, p.height(), 0.0)).distance(target.a().add(new RoadGeometry.V(0.0, target.height(), 0.0))) < 0.01);
   }

   private static RoadStructures.Part seat(SmartSigns.Support mount, double offset) {
      RoadGeometry.V foot = mount.foot()
         .add(mount.right().mul(offset))
         .add(mount.front().mul(mount.gantrySpan() == 0.0 ? -0.08 : -0.22))
         .add(new RoadGeometry.V(0.0, -0.42, 0.0));
      return new RoadStructures.Part(foot, foot, 0.62, 0.42, true, RoadStructures.Material.CONCRETE, null, null, "sr:elevated_access_sign_seat");
   }

   public static void reserve(List<RoadStructures.Part> parts, RoadGeometry.Mesh host) {
      for (ElevatedAccess.Reservation access : host.settings().options().lanePoints().access()) {
         LaneSections.Cut exit = access.cuts().stream().filter(c -> !c.arrival()).findFirst().orElse(null);
         if (exit != null && (!(exit.begin() < 0.0) && !(exit.begin() > host.length()) || placement(host, exit, 18.0, false) != null)) {
            List<SmartSigns.Branch> branches = destinations(host, access, exit.sign());
            SmartSigns.Key key = new SmartSigns.Key(access.connection(), 0, 1);

            for (int lead = 18; lead <= 54; lead += 3) {
               SmartSigns.Support support = placement(host, exit, (double)lead, branches.size() == 2);
               if (support != null) {
                  SmartSigns.State board = SmartSigns.State.empty(key).layout(SmartSigns.Mode.ROADSIDE, access.connection(), branches, "").supported(support);
                  RoadStructures.Part outer = seat(support, 0.0);
                  double top = outer.a().y();

                  for (RoadStructures.Part part : parts) {
                     if (part.material() == RoadStructures.Material.CONCRETE
                        && part.model().equals("sr:cycle_planter")
                        && RoadSolidOverlap.intersects(outer, part)) {
                        top = Math.max(top, part.top().stream().mapToDouble(RoadGeometry.V::y).max().orElse(top));
                     }
                  }

                  double height = support.foot().y() - top;
                  if (!(height <= 0.0)) {
                     RoadGeometry.V base = new RoadGeometry.V(outer.a().x(), top, outer.a().z());
                     List<RoadStructures.Part> bases = List.of(
                        new RoadStructures.Part(base, base, outer.width(), height, true, outer.material(), null, null, "sr:elevated_access_sign_seat"),
                        seat(support, support.gantrySpan())
                     );
                     ArrayList<RoadStructures.Part> members = new ArrayList<>(SmartSigns.mountParts(host, board));
                     members.addAll(bases);
                     RoadStructures.Part inner = bases.get(1);
                     RoadStructures.Part insert = new RoadStructures.Part(inner.a(), inner.a(), inner.width(), 1.6, true, RoadStructures.Material.CONCRETE);
                     List<RoadStructures.Part> medianRails = parts.stream().filter(p -> medianRail(p, insert, host)).toList();
                     RoadSolidOverlap.Index solids = new RoadSolidOverlap.Index(
                        parts.stream()
                           .filter(
                              p -> p.material() != RoadStructures.Material.GREEN && p.material() != RoadStructures.Material.SOIL && !medianRails.contains(p)
                           )
                           .toList()
                     );
                     if (!members.stream().anyMatch(solids::intersects)) {
                        RoadStructures.Part mast = members.get(1);

                        for (RoadStructures.Part rail : medianRails) {
                           parts.remove(rail);
                           RoadStructures.subtractFootprint(parts, rail, List.of(rail.a().y() >= inner.a().y() + inner.height() ? mast : inner));
                        }

                        RoadStructures.trimPlanting(parts, bases, true);
                        parts.addAll(bases);
                        break;
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean medianRail(RoadStructures.Part p, RoadStructures.Part insert, RoadGeometry.Mesh host) {
      if (!p.pier() && p.model().isEmpty() && p.heights() == null && RoadSolidOverlap.intersects(p, insert)) {
         if (ForkSignPlacement.isBase(p)
            || p.material() == RoadStructures.Material.CONCRETE && p.width() == 0.36 && p.height() == 0.35
            || p.material() == RoadStructures.Material.STEEL && (p.width() == 0.1 || p.width() == 0.12 || p.width() == 0.3) && p.height() <= 0.18
            || p.material() == RoadStructures.Material.DARK_STEEL && p.width() == 0.16 && p.height() <= 1.22) {
            RoadQueries.Projection q = RoadQueries.horizontal(host, p.a().add(p.b()).mul(0.5));
            RoadProfile.Layout l = RoadProfile.layout(host, q.sample());
            return l.catalog().twoWay() && Math.abs(q.lateral() - l.medianCenter()) <= l.median() / 2.0 + 0.01;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private ElevatedSigns() {
   }
}
