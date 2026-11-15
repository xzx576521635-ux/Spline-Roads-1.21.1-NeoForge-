package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RoadSignals {
   public static final long PHASE_TICKS = 400L;
   public static final double SOLID_APPROACH = 24.0;

   public static boolean mainGreen(long gameTime) {
      return Math.floorMod(Math.floorDiv(gameTime, 400L), 2) == 0;
   }

   public static boolean signal(RoadStructures.Part p) {
      return p.material() == RoadStructures.Material.SIGNAL_MAIN || p.material() == RoadStructures.Material.SIGNAL_RAMP || RoadJunctionSignalModel.supports(p);
   }

   public static List<RoadSignals.Approach> approaches(RoadGeometry.Mesh road, List<RoadGeometry.Mesh> neighbors) {
      if (!road.closed() && RoadProfile.modern(road.settings().style())) {
         List<RoadSignals.Approach> out = new ArrayList<>();
         boolean ramp = road.settings().style().ramp();

         for (RoadGeometry.Mesh pad : neighbors) {
            if (pad.settings().style() == RoadGeometry.Style.UNMARKED) {
               if (ramp) {
                  if ((!(road.last().center().distance(pad.first().center()) < 0.05) || !(road.last().left().dot(pad.first().left()) > 0.95))
                     && RoadQueries.contains(pad, road.last().center(), 0.1, 0.1)) {
                     for (double d = road.length() - 0.25; d > 1.0; d -= 0.25) {
                        RoadGeometry.Sample at = RoadStructures.sample(road, d);
                        if (!RoadQueries.contains(pad, at.center(), 0.1, 0.1)) {
                           add(out, road, pad, RoadStructures.sample(road, d - 0.6), 1, true, neighbors);
                           break;
                        }
                     }
                  }
               } else if (RoadProfile.catalog(road.settings().style()).twoWay()) {
                  double entry = -1.0;
                  double exit = -1.0;

                  for (double dx = 0.0; dx <= road.length(); dx += 0.25) {
                     RoadGeometry.Sample at = RoadStructures.sample(road, dx);
                     if (RoadQueries.contains(pad, at.center(), 0.1, 0.1)) {
                        if (entry < 0.0) {
                           entry = dx;
                        }

                        exit = dx;
                     }
                  }

                  if (entry > 1.0 && exit < road.length() - 1.0) {
                     add(out, road, pad, RoadStructures.sample(road, entry - 0.85), 1, false, neighbors);
                     add(out, road, pad, RoadStructures.sample(road, exit + 0.85), -1, false, neighbors);
                  }
               }
            }
         }

         return List.copyOf(out);
      } else {
         return List.of();
      }
   }

   private static void add(
      List<RoadSignals.Approach> out,
      RoadGeometry.Mesh road,
      RoadGeometry.Mesh pad,
      RoadGeometry.Sample at,
      int direction,
      boolean ramp,
      List<RoadGeometry.Mesh> neighbors
   ) {
      RoadProfile.Layout l = RoadProfile.layout(road, at);
      int side = l.outside() * direction;
      double inner = ramp ? l.outer(-side) + (double)side * 0.1 : l.medianCenter() + (double)side * (l.median() / 2.0 + 0.1);
      double outer = l.outer(side) - (double)side * 0.1;
      RoadGeometry.V forward = at.left().left().mul((double)(-direction));
      RoadGeometry.V middle = at.at((inner + outer) / 2.0, 0.0);
      double far = 0.0;

      for (double d = 0.5; d <= 160.0; d += 0.5) {
         if (RoadQueries.contains(pad, middle.add(forward.mul(d)), 0.2, 0.1)) {
            far = d;
         } else if (far > 0.0) {
            break;
         }
      }

      if (far != 0.0) {
         RoadGeometry.V post = at.at((double)side * (at.halfWidth() - 0.28), 0.0).add(forward.mul(far + 0.92));
         RoadGeometry.V head = middle.add(forward.mul(far + 0.5)).add(new RoadGeometry.V(0.0, 7.35, 0.0));
         RoadSignals.Approach approach = new RoadSignals.Approach(
            at.at(inner, 0.0), at.at(outer, 0.0), post, head, forward, ramp, at.distance(), direction, far + 2.0
         );
         if (out.stream().noneMatch(a -> a.inner().distance(approach.inner()) < 2.0)) {
            out.add(approach);
         }
      }
   }

   public static boolean solid(List<RoadSignals.Approach> approaches, double station, double lateral) {
      for (RoadSignals.Approach a : approaches) {
         double back = (a.station() - station) * (double)a.direction();
         RoadGeometry.V left = a.forward().left().mul((double)a.direction());
         double lo = a.inner().sub(a.outer()).dot(left);
         if (back >= 0.2 && back <= 24.0 && (a.ramp() || Math.signum(lateral) == -Math.signum(lo))) {
            return true;
         }
      }

      return false;
   }

   public static List<List<RoadGeometry.V>> paintCuts(List<RoadSignals.Approach> approaches) {
      List<List<RoadGeometry.V>> out = new ArrayList<>();

      for (RoadSignals.Approach a : approaches) {
         RoadGeometry.V side = a.outer().sub(a.inner()).horizontalUnit();
         RoadGeometry.V inner = a.inner().sub(side.mul(0.2)).sub(a.forward().mul(0.25));
         RoadGeometry.V outer = a.outer().add(side.mul(0.2)).sub(a.forward().mul(0.25));
         RoadGeometry.V reach = a.forward().mul(a.crossingLength());
         out.add(List.of(inner, outer, outer.add(reach), inner.add(reach)));
      }

      return out;
   }

   public static boolean furnitureClear(RoadGeometry.V p, List<RoadSignals.Approach> approaches) {
      for (RoadSignals.Approach a : approaches) {
         double ahead = p.sub(a.inner()).dot(a.forward());
         double side = Math.abs(p.sub(a.inner()).dot(a.forward().left()));
         double end = a.crossingLength() - 1.5;

         for (RoadSignals.Approach opposite : approaches) {
            if (opposite != a && !opposite.ramp() && !a.ramp() && !(opposite.forward().dot(a.forward()) > -0.99)) {
               double stop = opposite.inner().sub(a.inner()).dot(a.forward());
               if (stop > 0.0 && stop < a.crossingLength() + 2.0) {
                  end = Math.min(end, stop);
               }
            }
         }

         if (Math.abs(p.y() - a.inner().y()) < 0.5 && ahead >= -0.2 && ahead <= end + 0.2 && side < a.inner().distance(a.outer()) + 2.0) {
            return true;
         }

         if (p.sub(a.post()).horizontalLength() < 4.0) {
            return true;
         }
      }

      return false;
   }

   public static List<RoadStructures.Part> structures(List<RoadSignals.Approach> approaches) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();

      for (RoadSignals.Approach a : approaches) {
         out.addAll(assembly(a, a.post(), a.head(), false));
      }

      return List.copyOf(out);
   }

   public static List<RoadStructures.Part> structures(List<RoadSignals.Approach> approaches, List<RoadGeometry.Mesh> roads, final RoadStructures.Ground terrain) {
      if (approaches.isEmpty()) {
         return List.of();
      } else {
         List<RoadGeometry.Mesh> local = roads.stream().filter(m -> approaches.stream().anyMatch(ax -> {
               RoadQueries.Projection q = RoadQueries.horizontal(m, ax.post());
               return q.horizontalDistance() < q.sample().halfWidth() + 4.0 && Math.abs(q.sample().center().y() - ax.post().y()) < 1.0;
            })).toList();
         ArrayList<RoadStructures.Part> fixtures = new ArrayList<>();

         for (RoadGeometry.Mesh mesh : local) {
            final RoadRailJoin join = new RoadRailJoin(mesh, roads.stream().filter(m -> m != mesh).map(m -> new RoadRailJoin.Neighbor(m, false)).toList());
            var ground = new RoadStructures.Ground() {
               @Override
               public double top(double x, double z, double y) {
                  return terrain.top(x, z, y);
               }

               @Override
               public boolean blocked(RoadStructures.Part p) {
                  return terrain.blocked(p);
               }

               @Override
               public boolean joined(RoadGeometry.V p) {
                  return false;
               }

               @Override
               public boolean unionRails() {
                  return true;
               }

               @Override
               public boolean railBlocked(RoadStructures.Part p, RoadGeometry.V a, RoadGeometry.V b) {
                  return terrain.railBlocked(p, a, b);
               }

               @Override
               public List<RoadRailJoin.Span> railSpans(RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V out, double inset) {
                  return join.exposed(a, b, out, inset);
               }

               @Override
               public RoadGeometry.V railJoint(RoadGeometry.V p, RoadGeometry.V d, boolean h, boolean raised) {
                  return join.joint(p, d, h, raised);
               }

               @Override
               public boolean railPost(RoadGeometry.V p, boolean h, boolean raised) {
                  return join.ownsPost(p, h, raised);
               }
            };

            for (RoadStructures.Part part : RoadStructures.roadsideSupports(mesh, ground)) {
               if (approaches.stream().anyMatch(ax -> near(part, ax.post(), 4.0))) {
                  fixtures.add(part);
               }
            }
         }

         List<RoadGeometry.Mesh> motors = roads.stream().filter(m -> m.settings().style() != RoadGeometry.Style.UNMARKED).map(LaneDeck::motorOnly).toList();
         ArrayList<RoadStructures.Part> out = new ArrayList<>();

         for (RoadSignals.Approach a : approaches) {
            RoadGeometry.V across = a.forward().left();
            if (a.post().sub(a.head()).dot(across) < 0.0) {
               across = across.mul(-1.0);
            }

            List<RoadStructures.Part> chosen = null;

            for (double advance : new double[]{0.0, -0.25, 0.25, -0.5, 0.5, -0.75, 0.75}) {
               RoadGeometry.V shift = a.forward().mul(advance);
               RoadGeometry.V wanted = a.post().add(shift);
               RoadGeometry.V head = a.head().add(shift);

               for (int step = 0; step <= 78 && chosen == null; step++) {
                  double lateral = step == 0 ? 0.0 : (double)((step % 2 == 1 ? 1 : -1) * ((step + 1) / 2)) * 0.025;
                  RoadGeometry.V p = wanted.add(across.mul(lateral));
                  if (!(p.sub(a.post()).horizontalLength() >= 1.0)) {
                     List<RoadStructures.Part> parts = assembly(a, p, head, false);
                     if (seated(parts.get(0), local, fixtures, terrain) && clear(parts, roads, motors, fixtures, null)) {
                        chosen = parts;
                     }

                     if (chosen == null) {
                        double bottom = terrain.top(p.x(), p.z(), p.y());
                        double drop = p.y() - bottom;
                        if (Double.isFinite(bottom) && drop > 0.025 && drop <= 0.34) {
                           List<RoadStructures.Part> grounded = assembly(a, new RoadGeometry.V(p.x(), bottom, p.z()), head, false);
                           if (seated(grounded.get(0), local, fixtures, terrain) && clear(grounded, roads, motors, fixtures, null)) {
                              chosen = grounded;
                           }
                        }
                     }
                  }
               }

               if (chosen != null) {
                  break;
               }
            }

            if (chosen == null) {
               for (RoadStructures.Part bearing : fixtures) {
                  if (!bearing.pier() && bearing.material() == RoadStructures.Material.CONCRETE && !(bearing.width() < 0.35) && !(bearing.width() > 0.7)) {
                     RoadGeometry.V delta = bearing.b().sub(bearing.a());
                     double denominator = delta.dot(a.forward());
                     if (!(Math.abs(denominator) < 1.0E-8)) {
                        double t = a.post().sub(bearing.a()).dot(a.forward()) / denominator;
                        if (!(t < 0.0) && !(t > 1.0)) {
                           RoadGeometry.V center = bearing.a().add(delta.mul(t)).add(new RoadGeometry.V(0.0, bearing.height(), 0.0));
                           if (!(center.y() < a.post().y() + 0.3) && !(center.y() > a.post().y() + 1.21) && !(center.sub(a.post()).horizontalLength() >= 1.0)) {
                              for (double offset : new double[]{0.0, 0.02, -0.02, 0.04, -0.04}) {
                                 RoadGeometry.V p = center.add(across.mul(offset));
                                 if (!(p.sub(a.post()).horizontalLength() >= 1.0)) {
                                    List<RoadStructures.Part> partsx = assembly(a, p, a.head(), true);
                                    if (partsx.get(0).base().stream().allMatch(v -> fixtures.stream().anyMatch(support -> onTop(support, v)))
                                       && clear(partsx, roads, motors, fixtures, bearing)) {
                                       chosen = partsx;
                                       break;
                                    }
                                 }
                              }

                              if (chosen != null) {
                                 break;
                              }
                           }
                        }
                     }
                  }
               }
            }

            if (chosen == null) {
               throw new IllegalArgumentException(String.format(Locale.ROOT, "路口远侧信号灯缺少可用承托（%.0f, %.0f, %.0f）", a.post().x(), a.post().y(), a.post().z()));
            }

            out.addAll(chosen);
         }

         return List.copyOf(out);
      }
   }

   private static boolean near(RoadStructures.Part part, RoadGeometry.V point, double margin) {
      List<RoadGeometry.V> base = part.base();
      return point.x() >= base.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow() - margin
         && point.x() <= base.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow() + margin
         && point.z() >= base.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow() - margin
         && point.z() <= base.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow() + margin;
   }

   private static boolean seated(RoadStructures.Part base, List<RoadGeometry.Mesh> roads, List<RoadStructures.Part> fixtures, RoadStructures.Ground terrain) {
      ArrayList<RoadGeometry.V> probes = new ArrayList<>(base.base());
      probes.add(base.a().add(base.b()).mul(0.5));

      for (int i = 0; i < 4; i++) {
         probes.add(base.base().get(i).add(base.base().get((i + 1) % 4)).mul(0.5));
      }

      for (RoadGeometry.V p : probes) {
         if (roads.stream().noneMatch(m -> RoadQueries.contains(m, p, -0.005, 0.06)) && fixtures.stream().noneMatch(s -> onTop(s, p))) {
            double y = terrain.top(p.x(), p.z(), p.y());
            if (!Double.isFinite(y) || Math.abs(y - p.y()) > 0.025) {
               return false;
            }
         }
      }

      return true;
   }

   private static boolean onTop(RoadStructures.Part part, RoadGeometry.V p) {
      if (part.material() == RoadStructures.Material.CONCRETE && !part.pier() && near(part, p, 0.001)) {
         List<RoadGeometry.V> top = part.top();

         for (int i = 1; i < top.size() - 1; i++) {
            RoadGeometry.V a = top.get(0);
            RoadGeometry.V b = top.get(i);
            RoadGeometry.V c = top.get(i + 1);
            RoadGeometry.V u = b.sub(a);
            RoadGeometry.V v = c.sub(a);
            RoadGeometry.V d = p.sub(a);
            double det = u.x() * v.z() - u.z() * v.x();
            if (!(Math.abs(det) < 1.0E-10)) {
               double s = (d.x() * v.z() - d.z() * v.x()) / det;
               double t = (u.x() * d.z() - u.z() * d.x()) / det;
               if (s >= -1.0E-7 && t >= -1.0E-7 && s + t <= 1.0000001 && Math.abs(p.y() - a.y() - s * u.y() - t * v.y()) <= 0.03) {
                  return true;
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private static boolean clear(
      List<RoadStructures.Part> parts,
      List<RoadGeometry.Mesh> roads,
      List<RoadGeometry.Mesh> motors,
      List<RoadStructures.Part> fixtures,
      RoadStructures.Part bearing
   ) {
      for (RoadStructures.Part p : parts) {
         for (RoadGeometry.Mesh m : motors) {
            if (RoadClearance.structureInvades(p, m, 4.25)) {
               return false;
            }
         }

         for (RoadGeometry.Mesh mx : roads) {
            if (RoadClearance.structureInvades(p, mx, 0.0)) {
               return false;
            }
         }

         for (RoadStructures.Part obstacle : fixtures) {
            if (RoadSolidOverlap.intersects(p, obstacle)) {
               boolean railJoint = bearing != null
                  && p.material() == RoadStructures.Material.CB_POST
                  && (obstacle.material() == RoadStructures.Material.STEEL || obstacle.material() == RoadStructures.Material.DARK_STEEL)
                  && Math.max(obstacle.a().y(), obstacle.b().y()) + obstacle.height() <= bearing.a().y() + 1.25
                  && near(bearing, obstacle.a().add(obstacle.b()).mul(0.5), 0.4);
               if (!railJoint) {
                  return false;
               }
            }
         }
      }

      return true;
   }

   private static List<RoadStructures.Part> assembly(RoadSignals.Approach a, RoadGeometry.V p, RoadGeometry.V head, boolean railSeat) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      RoadGeometry.V f = a.forward();
      double base = railSeat ? 0.36 : 0.46;
      double beamBottom = head.y() + 0.2;
      out.add(
         new RoadStructures.Part(
            p.sub(f.mul(base / 2.0)),
            p.add(f.mul(base / 2.0)),
            base,
            railSeat ? 0.14 : 0.34,
            false,
            RoadStructures.Material.CB_BASE,
            null,
            null,
            railSeat ? "sr:signal_bolt_plate" : "sr:signal_base"
         )
      );
      double shaft = railSeat ? 0.36 : 0.38;
      out.add(
         new RoadStructures.Part(p.sub(f.mul(shaft / 2.0)), p.add(f.mul(shaft / 2.0)), shaft, beamBottom - p.y() + 0.25, false, RoadStructures.Material.CB_POST)
      );
      RoadGeometry.V top = new RoadGeometry.V(p.x(), beamBottom, p.z());
      RoadGeometry.V mount = head.add(f.mul(0.42));
      RoadGeometry.V armEnd = new RoadGeometry.V(mount.x(), beamBottom, mount.z());
      armEnd = armEnd.add(armEnd.sub(top).horizontalUnit());
      out.add(new RoadStructures.Part(top, armEnd, 0.25, 0.25, false, RoadStructures.Material.CB_ARM));
      RoadGeometry.V centered = head.add(new RoadGeometry.V(0.0, -0.19690625, 0.0));
      out.add(
         new RoadStructures.Part(
            centered.add(f.mul(0.5)),
            centered.sub(f.mul(0.5)),
            1.9,
            0.85,
            false,
            a.ramp() ? RoadStructures.Material.SIGNAL_RAMP : RoadStructures.Material.SIGNAL_MAIN,
            null,
            null,
            "sr:centered_horizontal"
         )
      );
      return List.copyOf(out);
   }

   public static List<RoadSurface.Face> lights(List<RoadStructures.Part> heads, boolean mainGreen) {
      List<RoadSurface.Face> out = new ArrayList<>();

      for (RoadStructures.Part p : heads) {
         if (signal(p)) {
            boolean green = p.material() == RoadStructures.Material.SIGNAL_MAIN ? mainGreen : !mainGreen;
            out.addAll(RoadSignalModel.faces(p, true, green));
         }
      }

      return List.copyOf(out);
   }

   private RoadSignals() {
   }

   public static record Approach(
      RoadGeometry.V inner,
      RoadGeometry.V outer,
      RoadGeometry.V post,
      RoadGeometry.V head,
      RoadGeometry.V forward,
      boolean ramp,
      double station,
      int direction,
      double crossingLength
   ) {
   }
}
