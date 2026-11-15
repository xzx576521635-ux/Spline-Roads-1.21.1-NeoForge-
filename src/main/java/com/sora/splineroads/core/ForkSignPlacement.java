package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class ForkSignPlacement {
   public static SmartSigns.Support findConnected(
      RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, List<RoadStructures.Part> parts, ForkSignPlacement.Mouth mouth, boolean paired
   ) {
      if (ramp.settings().structure() == RoadGeometry.Structure.TUNNEL) {
         return null;
      } else if ((mouth.departure() == LanePoints.Departure.DETACH || mouth.departure() == LanePoints.Departure.TEMPORARY)
         && host.settings()
            .options()
            .lanePoints()
            .cuts()
            .stream()
            .noneMatch(
               cx -> cx.connection().equals(mouth.connection())
                     && !cx.arrival()
                     && cx.lane() == mouth.lane()
                     && cx.sign() == mouth.flow()
                     && Math.abs(cx.begin() - mouth.station()) < 0.01
            )) {
         return null;
      } else {
         ArrayList<ForkSignPlacement.Candidate> candidates = new ArrayList<>();

         for (RoadStructures.Part part : parts) {
            boolean base = isBase(part);
            boolean beam = isBeam(part);
            if (base || beam) {
               RoadGeometry.V delta = part.b().sub(part.a());
               double length = delta.horizontalLength();
               if (!(length < 0.4)) {
                  for (RoadGeometry.V start : List.of(part.a(), part.b())) {
                     RoadQueries.Projection q = RoadQueries.horizontal(ramp, start);
                     RoadGeometry.Sample at = q.sample();
                     RoadQueries.Projection near = RoadQueries.horizontal(host, start);
                     RoadGeometry.Sample h = near.sample();
                     if (atDeckHeight(start, at, h)) {
                        RoadGeometry.V forward = at.left().left().mul(-1.0);
                        RoadGeometry.V hostForward = h.left().left().mul((double)(-mouth.flow()));
                        if (!(at.distance() > 256.0)
                           && !((double)mouth.flow() * (h.distance() - mouth.station()) < -0.05)
                           && (!(at.distance() < 1.0E-5) || !(start.sub(at.center()).dot(forward) < -0.05))
                           && !(Math.abs(at.center().y() - h.center().y()) > 3.5)
                           && !(forward.dot(hostForward) < 0.25)) {
                           RoadGeometry.V toward = h.center().sub(at.center());
                           double gap = toward.horizontalLength();
                           if (!(gap < 0.15)
                              && !(start.sub(at.center()).dot(new RoadGeometry.V(toward.x(), 0.0, toward.z())) <= 0.01)
                              && !(start.sub(h.center()).dot(new RoadGeometry.V(-toward.x(), 0.0, -toward.z())) <= 0.01)) {
                              int inner = at.left().dot(toward) >= 0.0 ? 1 : -1;
                              if (!(Math.abs(q.lateral() * (double)inner - at.halfWidth()) > 0.85) && !(Math.abs(delta.horizontalUnit().dot(forward)) < 0.65)) {
                                 RoadGeometry.V along = delta.horizontalUnit().mul(delta.dot(forward) >= 0.0 ? 1.0 : -1.0);
                                 RoadGeometry.V nose = part.a().dot(along) < part.b().dot(along) ? part.a() : part.b();
                                 double advance = Math.min(1.25, length * 0.65);
                                 RoadGeometry.V on = nose.add(along.mul(advance));
                                 double fraction = on.sub(part.a()).horizontalLength() / length;
                                 double top = beam
                                    ? RoadQueries.horizontal(ramp, on).sample().center().y()
                                    : part.a().y() + (part.b().y() - part.a().y()) * fraction + part.height();
                                 RoadGeometry.V foot = new RoadGeometry.V(on.x(), top, on.z())
                                    .add(at.left().mul((double)inner * Math.min(0.07, part.width() * 0.2)));
                                 RoadQueries.Projection finalRamp = RoadQueries.horizontal(ramp, foot);
                                 RoadQueries.Projection finalHost = RoadQueries.horizontal(host, foot);
                                 if (!((double)mouth.flow() * (finalHost.sample().distance() - mouth.station()) < -0.05)
                                    && !(finalRamp.sample().distance() + 0.05 < at.distance())) {
                                    SmartSigns.Support support = new SmartSigns.Support(
                                       foot, h.left().mul((double)mouth.flow()), h.left().mul((double)mouth.flow()).left(), paired
                                    );
                                    candidates.add(new ForkSignPlacement.Candidate(support, at.distance(), base, nose.x(), nose.z(), part));
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         candidates.sort(
            Comparator.comparingDouble(ForkSignPlacement.Candidate::station)
               .thenComparing(cx -> !cx.base())
               .thenComparingDouble(ForkSignPlacement.Candidate::x)
               .thenComparingDouble(ForkSignPlacement.Candidate::z)
               .thenComparingDouble(cx -> cx.support().foot().x())
               .thenComparingDouble(cx -> cx.support().foot().y())
               .thenComparingDouble(cx -> cx.support().foot().z())
               .thenComparingDouble(cx -> cx.support().right().x())
               .thenComparingDouble(cx -> cx.support().right().z())
         );
         RoadGeometry.Mesh rampMotor = LaneDeck.motorOnly(ramp);
         RoadGeometry.Mesh hostMotor = LaneDeck.motorOnly(host);

         for (ForkSignPlacement.Candidate c : candidates) {
            RoadGeometry.V foot = c.support().foot();
            RoadStructures.Part post = new RoadStructures.Part(foot, foot, 0.24, 4.6, true, RoadStructures.Material.STEEL);
            if (c.base() && seated(post, c.foundation())
               || !RoadClearance.structureInvades(post, rampMotor, 4.25) && !RoadClearance.structureInvades(post, hostMotor, 4.25)) {
               SmartSigns.Support support = plantedEdgeSeat(seat(c.support(), parts), ramp, host, parts);
               RoadGeometry.V at = support.foot().add(support.front().mul(-0.08));
               RoadStructures.Part mast = new RoadStructures.Part(at, at, 0.3, 7.87, true, RoadStructures.Material.STEEL);
               if (parts.stream().noneMatch(p -> !mountingContact(mast, p, support) && RoadSolidOverlap.intersects(mast, p))) {
                  return support;
               }
            }
         }

         return null;
      }
   }

   private static SmartSigns.Support plantedEdgeSeat(
      SmartSigns.Support support, RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, List<RoadStructures.Part> parts
   ) {
      RoadGeometry.V origin = support.foot().add(support.front().mul(-0.08));
      RoadStructures.Part original = new RoadStructures.Part(origin, origin, 0.3, 7.87, true, RoadStructures.Material.STEEL);
      if (parts.stream()
         .noneMatch(
            p -> (p.material() == RoadStructures.Material.GREEN || p.material() == RoadStructures.Material.SOIL) && RoadSolidOverlap.intersects(original, p)
         )) {
         return support;
      } else {
         RoadGeometry.Mesh rampMotor = LaneDeck.motorOnly(ramp);
         RoadGeometry.Mesh hostMotor = LaneDeck.motorOnly(host);

         for (double offset = 0.15; offset <= 1.51; offset += 0.15) {
            for (int side : new int[]{-1, 1}) {
               RoadGeometry.V foot = support.foot().add(support.right().mul((double)side * offset));
               if (RoadQueries.contains(ramp, foot, 0.05, 0.2) || RoadQueries.contains(host, foot, 0.05, 0.2)) {
                  double y = foot.y();
                  RoadStructures.Part probe = new RoadStructures.Part(foot, foot, 0.3, 0.6, true, RoadStructures.Material.STEEL);

                  for (RoadStructures.Part p : parts) {
                     if (p.material() == RoadStructures.Material.CONCRETE && seated(probe, p)) {
                        double top = p.top().stream().mapToDouble(RoadGeometry.V::y).max().orElse(y);
                        if (top >= y && top <= foot.y() + 0.4) {
                           y = Math.max(y, top);
                        }
                     }
                  }

                  foot = new RoadGeometry.V(foot.x(), y, foot.z());
                  SmartSigns.Support candidate = new SmartSigns.Support(foot, support.right(), support.front(), support.paired(), support.cantileverSide());
                  RoadGeometry.V at = foot.add(support.front().mul(-0.08));
                  RoadStructures.Part mast = new RoadStructures.Part(at, at, 0.3, 7.87, true, RoadStructures.Material.STEEL);
                  if (!RoadClearance.structureInvades(mast, rampMotor, 4.25)
                     && !RoadClearance.structureInvades(mast, hostMotor, 4.25)
                     && parts.stream().noneMatch(px -> !mountingContact(mast, px, candidate) && RoadSolidOverlap.intersects(mast, px))) {
                     return candidate;
                  }
               }
            }
         }

         return support;
      }
   }

   private static boolean seated(RoadStructures.Part post, RoadStructures.Part foundation) {
      List<RoadGeometry.V> polygon = foundation.base();

      for (RoadGeometry.V p : post.base()) {
         boolean positive = false;
         boolean negative = false;

         for (int i = 0; i < polygon.size(); i++) {
            RoadGeometry.V a = polygon.get(i);
            RoadGeometry.V b = polygon.get((i + 1) % polygon.size());
            double cross = (b.x() - a.x()) * (p.z() - a.z()) - (b.z() - a.z()) * (p.x() - a.x());
            positive |= cross > 1.0E-8;
            negative |= cross < -1.0E-8;
         }

         if (positive && negative) {
            return false;
         }
      }

      return true;
   }

   private static boolean atDeckHeight(RoadGeometry.V point, RoadGeometry.Sample ramp, RoadGeometry.Sample host) {
      return Math.min(Math.abs(point.y() - ramp.center().y()), Math.abs(point.y() - host.center().y())) <= 1.3;
   }

   static boolean isBase(RoadStructures.Part p) {
      return p.material() == RoadStructures.Material.TUNNEL && (p.model().equals("sr:tunnel_nose") || p.model().startsWith("sr:auto_cut:"))
         || p.material() == RoadStructures.Material.CONCRETE
            && !p.pier()
            && p.width() >= 0.08
            && (
               p.width() <= 0.62001 && Math.abs(p.height() - 0.8) < 1.0E-5
                  || p.width() <= 0.42001 && Math.abs(p.height() - 0.45) < 1.0E-5
                  || p.width() <= 0.7000099999999999 && Math.abs(p.height() - 0.5) < 1.0E-5
            );
   }

   private static boolean isBeam(RoadStructures.Part p) {
      return p.material() == RoadStructures.Material.STEEL
         && !p.pier()
         && (
            Math.abs(p.width() - 0.3) < 1.0E-5 && Math.abs(p.height() - 0.18) < 1.0E-5
               || Math.abs(p.width() - 0.1) < 1.0E-5 && Math.abs(p.height() - 0.1) < 1.0E-5
               || Math.abs(p.width() - 0.12) < 1.0E-5 && Math.abs(p.height() - 0.12) < 1.0E-5
         );
   }

   public static SmartSigns.Support find(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, List<RoadStructures.Part> parts, int flow, boolean paired) {
      return find(ramp, host, parts, flow, paired, false);
   }

   public static SmartSigns.Support findBetween(RoadGeometry.Mesh ramp, RoadGeometry.Mesh sibling, List<RoadStructures.Part> parts, int flow, boolean paired) {
      return find(ramp, sibling, parts, flow, paired, true);
   }

   private static SmartSigns.Support find(
      RoadGeometry.Mesh ramp, RoadGeometry.Mesh host, List<RoadStructures.Part> parts, int flow, boolean paired, boolean between
   ) {
      if (ramp.settings().structure() == RoadGeometry.Structure.TUNNEL) {
         return null;
      } else {
         RoadGeometry.V best = null;
         double first = Double.POSITIVE_INFINITY;
         RoadGeometry.V heading = null;
         boolean bestBase = false;

         for (RoadStructures.Part p : parts) {
            boolean base = isBase(p);
            boolean beam = isBeam(p);
            if (base || beam) {
               RoadGeometry.V delta = p.b().sub(p.a());
               double length = delta.horizontalLength();
               if (!(length < 0.4)) {
                  for (RoadGeometry.V start : List.of(p.a(), p.b())) {
                     RoadQueries.Projection q = RoadQueries.horizontal(ramp, start);
                     RoadGeometry.Sample at = q.sample();
                     if (!(at.halfWidth() < ramp.settings().width() / 2.0 - 0.15)
                        && !(at.distance() > 256.0)
                        && !(at.distance() > first)
                        && onPath(ramp, start, 0.05)
                        && onPath(host, start, 0.05)) {
                        RoadQueries.Projection near = RoadQueries.horizontal(host, start);
                        RoadGeometry.Sample h = near.sample();
                        if (atDeckHeight(start, at, h)
                           && (!between || !(at.left().dot(h.left().mul((double)flow)) < 0.5) && !(q.lateral() * near.lateral() * (double)flow >= -0.05))
                           && !(Math.abs(at.center().y() - h.center().y()) > 3.5)
                           && (!beam || !bestBase || !(Math.abs(at.distance() - first) < 1.0E-5))) {
                           int inner = at.left().dot(h.center().sub(at.center())) >= 0.0 ? 1 : -1;
                           if (!(Math.abs(q.lateral() * (double)inner - at.halfWidth()) > 0.85)) {
                              RoadGeometry.V forward = at.left().left().mul(-1.0);
                              if (!(at.distance() < 1.0E-5) || !(start.sub(at.center()).dot(forward) < -0.05)) {
                                 RoadGeometry.V toward = h.center().sub(at.center());
                                 if (!(toward.horizontalLength() < 0.15)
                                    && !(start.sub(at.center()).dot(toward) <= 0.01)
                                    && !(start.sub(h.center()).dot(toward.mul(-1.0)) <= 0.01)
                                    && !(Math.abs(delta.horizontalUnit().dot(forward)) < 0.65)) {
                                    toward = delta.horizontalUnit().mul(delta.dot(forward) >= 0.0 ? 1.0 : -1.0);
                                    RoadGeometry.V nose = p.a().dot(toward) < p.b().dot(toward) ? p.a() : p.b();
                                    double advance = Math.min(1.25, length * 0.65);
                                    RoadGeometry.V foot = nose.add(toward.mul(advance)).add(at.left().mul((double)inner * (beam ? 0.18 : 0.07)));
                                    double fraction = Math.max(0.0, Math.min(1.0, foot.sub(p.a()).dot(delta) / (length * length)));
                                    List<RoadGeometry.V> top = p.top();
                                    double firstTop = (top.get(0).y() + top.get(1).y()) / 2.0;
                                    double lastTop = (top.get(2).y() + top.get(3).y()) / 2.0;
                                    foot = new RoadGeometry.V(
                                       foot.x(),
                                       beam ? RoadQueries.horizontal(ramp, foot).sample().center().y() : firstTop + (lastTop - firstTop) * fraction,
                                       foot.z()
                                    );
                                    RoadGeometry.V right = h.left().mul((double)flow);
                                    best = foot;
                                    heading = right;
                                    first = at.distance();
                                    bestBase = base;
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         return best == null ? null : seat(new SmartSigns.Support(best, heading, heading.left(), paired), parts);
      }
   }

   public static boolean mountingContact(RoadStructures.Part mast, RoadStructures.Part rail, SmartSigns.Support support) {
      if (support != null
         && mast.pier()
         && mast.material() == RoadStructures.Material.STEEL
         && !(Math.abs(mast.width() - 0.3) > 1.0E-6)
         && !(mast.a().distance(support.foot().add(support.front().mul(-0.08))) > 1.0E-6)) {
         boolean railing = isBeam(rail)
            || rail.material() == RoadStructures.Material.DARK_STEEL && !rail.pier() && Math.abs(rail.width() - 0.16) < 1.0E-6 && rail.height() <= 1.22;
         if (!railing) {
            return false;
         } else {
            double low = rail.base().stream().mapToDouble(RoadGeometry.V::y).min().orElseThrow();
            double high = rail.top().stream().mapToDouble(RoadGeometry.V::y).max().orElseThrow();
            return low >= support.foot().y() - 0.1 && high <= support.foot().y() + 1.3;
         }
      } else {
         return false;
      }
   }

   private static SmartSigns.Support seat(SmartSigns.Support support, List<RoadStructures.Part> parts) {
      RoadGeometry.V foot = support.foot();
      RoadGeometry.V best = foot;
      double top = foot.y();
      double shift = Double.POSITIVE_INFINITY;

      for (RoadStructures.Part p : parts) {
         if (isBase(p)
            || !p.pier() && p.material() == RoadStructures.Material.CONCRETE && Math.abs(p.width() - 0.36) < 1.0E-5 && Math.abs(p.height() - 0.35) < 1.0E-5) {
            RoadGeometry.V d = p.b().sub(p.a());
            double length = d.horizontalLength();
            if (!(length < 0.31)) {
               RoadGeometry.V center = foot.add(support.front().mul(-0.08));
               double t = Math.max(
                  0.16 / length, Math.min(1.0 - 0.16 / length, center.sub(p.a()).dot(new RoadGeometry.V(d.x(), 0.0, d.z())) / (length * length))
               );
               double y = p.a().y() + t * d.y() + p.height();
               if (!(y < foot.y() - 1.0E-5) && !(y > foot.y() + 1.2)) {
                  RoadGeometry.V on = p.a().add(d.mul(t));
                  RoadGeometry.V candidate = new RoadGeometry.V(on.x(), y, on.z()).sub(support.front().mul(-0.08));
                  double distance = candidate.sub(foot).horizontalLength();
                  if (!(distance > 0.26) && !(distance > shift + 1.0E-7)) {
                     RoadStructures.Part flange = new RoadStructures.Part(
                        candidate.add(support.front().mul(-0.08)), candidate.add(support.front().mul(-0.08)), 0.3, 0.4, true, RoadStructures.Material.STEEL
                     );
                     if (seated(flange, p) && (distance < shift - 1.0E-7 || y > top)) {
                        best = candidate;
                        top = y;
                        shift = distance;
                     }
                  }
               }
            }
         }
      }

      RoadGeometry.V center = best.add(support.front().mul(-0.08));
      double seatedY = best.y();

      for (RoadStructures.Part px : parts) {
         if (isBase(px)
            || !px.pier() && px.material() == RoadStructures.Material.CONCRETE && Math.abs(px.width() - 0.36) < 1.0E-5 && Math.abs(px.height() - 0.35) < 1.0E-5
            )
          {
            List<RoadGeometry.V> cap = px.top();
            cap = clipCap(cap, 0, center.x() - 0.15, true);
            cap = clipCap(cap, 0, center.x() + 0.15, false);
            cap = clipCap(cap, 2, center.z() - 0.15, true);
            cap = clipCap(cap, 2, center.z() + 0.15, false);
            double high = cap.stream().mapToDouble(RoadGeometry.V::y).max().orElse(Double.NEGATIVE_INFINITY);
            if (high >= best.y() && high <= best.y() + 0.1) {
               seatedY = Math.max(seatedY, high);
            }
         }
      }

      if (seatedY > best.y()) {
         best = new RoadGeometry.V(best.x(), seatedY, best.z());
      }

      return best == foot ? support : new SmartSigns.Support(best, support.right(), support.front(), support.paired(), support.cantileverSide());
   }

   private static List<RoadGeometry.V> clipCap(List<RoadGeometry.V> polygon, int axis, double edge, boolean lower) {
      ArrayList<RoadGeometry.V> out = new ArrayList<>();
      if (polygon.isEmpty()) {
         return out;
      } else {
         RoadGeometry.V a = polygon.get(polygon.size() - 1);
         double da = (axis == 0 ? a.x() : a.z()) - edge;
         if (!lower) {
            da = -da;
         }

         for (RoadGeometry.V b : polygon) {
            double db = (axis == 0 ? b.x() : b.z()) - edge;
            if (!lower) {
               db = -db;
            }

            if (da >= 0.0 != db >= 0.0) {
               out.add(a.add(b.sub(a).mul(da / (da - db))));
            }

            if (db >= 0.0) {
               out.add(b);
            }

            a = b;
            da = db;
         }

         return out;
      }
   }

   public static double separator(RoadGeometry.Mesh ramp, RoadGeometry.Mesh host) {
      for (RoadGeometry.Sample at : ramp.samples()) {
         if (!(at.halfWidth() < ramp.settings().width() / 2.0 - 0.15)) {
            RoadQueries.Projection q = RoadQueries.horizontal(host, at.center());
            RoadGeometry.Sample h = q.sample();
            if (!(Math.abs(at.center().y() - h.center().y()) > 3.5)) {
               int inner = at.left().dot(h.center().sub(at.center())) >= 0.0 ? 1 : -1;
               RoadGeometry.V edge = at.at((double)inner * at.halfWidth(), 0.0);
               RoadQueries.Projection near = RoadQueries.horizontal(host, edge);
               if (Math.abs(near.lateral()) >= near.sample().halfWidth() + 0.06) {
                  return at.distance();
               }
            }
         }
      }

      return Double.POSITIVE_INFINITY;
   }

   public static List<SmartSigns.Support> approach(RoadGeometry.Mesh host, List<RoadStructures.Part> parts, double station, int flow, int side, boolean paired) {
      if (!host.closed() && host.settings().structure() != RoadGeometry.Structure.TUNNEL && Math.abs(side) == 1) {
         ArrayList<ForkSignPlacement.Candidate> candidates = new ArrayList<>();
         int edge = side * flow;

         for (RoadStructures.Part part : parts) {
            boolean paved = part.material().name().startsWith("WALK_") && !part.pier() && part.height() <= 0.5;
            boolean base = isBase(part)
               || part.material() == RoadStructures.Material.CONCRETE
                  && !part.pier()
                  && Math.abs(part.width() - 0.36) < 1.0E-5
                  && Math.abs(part.height() - 0.35) < 1.0E-5;
            if (base || isBeam(part) || paved) {
               RoadGeometry.V delta = part.b().sub(part.a());
               double length = delta.horizontalLength();
               if (!(length < 0.35)) {
                  for (double along = 0.16; along <= length - 0.16 + 1.0E-6; along += Math.max(0.5, length / 64.0)) {
                     double fraction = along / length;
                     RoadGeometry.V on = part.a().add(delta.mul(fraction));
                     if (paved) {
                        RoadQueries.Projection projection = RoadQueries.horizontal(host, on);
                        RoadGeometry.Sample sample = projection.sample();
                        double desired = (double)edge * (sample.halfWidth() + 0.4);
                        double shift = desired - projection.lateral();
                        shift = Math.max(-part.width() / 2.0 + 0.2, Math.min(part.width() / 2.0 - 0.2, shift));
                        on = on.add(sample.left().mul(shift));
                     }

                     RoadQueries.Projection q = RoadQueries.horizontal(host, on);
                     RoadGeometry.Sample at = q.sample();
                     double before = (station - at.distance()) * (double)flow;
                     if (atDeckHeight(on, at, at)
                        && !(before < 2.0)
                        && !(before > 64.0)
                        && !(at.distance() < 0.5)
                        && !(at.distance() > host.length() - 0.5)
                        && !(Math.abs(q.lateral() * (double)edge - at.halfWidth()) > (paved ? 1.25 : 0.85))
                        && !(Math.abs(delta.horizontalUnit().dot(at.left().left())) < 0.9)) {
                        List<RoadGeometry.V> top = part.top();
                        double a = (top.get(0).y() + top.get(1).y()) / 2.0;
                        double b = (top.get(2).y() + top.get(3).y()) / 2.0;
                        RoadGeometry.V foot = new RoadGeometry.V(on.x(), !base && !paved ? at.center().y() : a + (b - a) * fraction, on.z());
                        RoadGeometry.V right = at.left().mul((double)flow);
                        if (!paved || seated(new RoadStructures.Part(foot, foot, 0.34, 0.1, true, RoadStructures.Material.STEEL), part)) {
                           candidates.add(
                              new ForkSignPlacement.Candidate(
                                 seat(new SmartSigns.Support(foot, right, right.left(), paired, -side), parts), before, base, on.x(), on.z(), part
                              )
                           );
                        }
                     }
                  }
               }
            }
         }

         candidates.sort(
            Comparator.comparingDouble(ForkSignPlacement.Candidate::station)
               .thenComparing(c -> !c.base())
               .thenComparingDouble(ForkSignPlacement.Candidate::x)
               .thenComparingDouble(ForkSignPlacement.Candidate::z)
         );
         ArrayList<SmartSigns.Support> result = new ArrayList<>(candidates.stream().map(ForkSignPlacement.Candidate::support).distinct().toList());
         RoadGeometry.Mesh motor = LaneDeck.motorOnly(host);

         for (double before = 2.0; before <= 64.0; before++) {
            double d = station - (double)flow * before;
            if (!(d < 0.5) && !(d > host.length() - 0.5)) {
               RoadGeometry.Sample at = RoadStructures.sample(host, d);
               RoadGeometry.V right = at.left().mul((double)flow);
               RoadGeometry.V front = right.left();
               RoadGeometry.V center = at.at((double)edge * (at.halfWidth() - 0.26), 0.0);
               RoadStructures.Part flange = new RoadStructures.Part(center, center, 0.34, 0.1, true, RoadStructures.Material.STEEL);
               boolean supported = true;
               double y = center.y();

               for (RoadGeometry.V corner : flange.base()) {
                  RoadQueries.Projection q = RoadQueries.horizontal(host, corner);
                  RoadGeometry.V surface = new RoadGeometry.V(corner.x(), q.sample().center().y(), corner.z());
                  if (!RoadQueries.contains(host, surface, -0.01, 0.01) || Math.abs(surface.y() - center.y()) > 0.05) {
                     supported = false;
                     break;
                  }

                  y = Math.max(y, surface.y());
               }

               if (supported) {
                  center = new RoadGeometry.V(center.x(), y, center.z());
                  RoadStructures.Part mast = new RoadStructures.Part(center, center, 0.3, 7.87, true, RoadStructures.Material.STEEL);
                  if (!RoadClearance.structureInvades(mast, motor, 4.5)) {
                     result.add(new SmartSigns.Support(center.add(front.mul(0.08)), right, front, paired, -side));
                  }
               }
            }
         }

         return List.copyOf(result);
      } else {
         return List.of();
      }
   }

   public static boolean onPath(RoadGeometry.Mesh mesh, RoadGeometry.V point, double tolerance) {
      if (mesh.closed()) {
         return true;
      } else {
         RoadQueries.Projection q = RoadQueries.horizontal(mesh, point);
         RoadGeometry.Sample s = q.sample();
         double along = point.sub(s.center()).dot(s.left().left().mul(-1.0));
         return (!(s.distance() < 1.0E-5) || !(along < -tolerance)) && (!(s.distance() > mesh.length() - 1.0E-5) || !(along > tolerance));
      }
   }

   private ForkSignPlacement() {
   }

   private static record Candidate(SmartSigns.Support support, double station, boolean base, double x, double z, RoadStructures.Part foundation) {
   }

   public static record Mouth(UUID connection, LanePoints.Departure departure, int lane, int flow, double station) {
   }
}
