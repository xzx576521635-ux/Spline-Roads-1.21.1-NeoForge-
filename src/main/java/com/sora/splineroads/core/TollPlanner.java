package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

public final class TollPlanner {
   public static final double ISLAND = 1.2;
   public static final double CORE = 28.0;
   public static final double MAX_GRADE = 0.12;
   public static final double COLLISION_MARGIN = 0.36;

   private static double pitch(TollPlaza.Config c) {
      return c.passageWidth() + 0.72 + 1.2;
   }

   private static double smooth(double t) {
      t = Math.max(0.0, Math.min(1.0, t));
      return t * t * t * (10.0 + t * (-15.0 + 6.0 * t));
   }

   public static boolean contains(RoadGeometry.Mesh mesh, RoadGeometry.V point, double margin, double height) {
      RoadGeometry.V axis = mesh.last().center().sub(mesh.first().center()).horizontalUnit();
      double d = point.sub(mesh.first().center()).dot(axis);
      double length = mesh.last().center().sub(mesh.first().center()).horizontalLength();
      if (!(d < -margin) && !(d > length + margin)) {
         RoadQueries.Projection q = RoadQueries.horizontal(mesh, point);
         return Math.abs(q.lateral()) <= q.sample().halfWidth() + margin
            && point.y() >= q.sample().center().y() - mesh.settings().thickness() - margin
            && point.y() <= q.sample().center().y() + height + margin;
      } else {
         return false;
      }
   }

   private static double lerp(double a, double b, double t) {
      return a + (b - a) * t;
   }

   public static TollPlanner.Lengths lengths(TollPlaza.Spec s) {
      if (!s.path().isEmpty()) {
         return lengths(TollInlineGeometry.local(s));
      } else {
         RoadGeometry.V delta = s.b().position().sub(s.a().position());
         double length = delta.horizontalLength();
         if (RoadGeometry.finite(length, s.a().position().y(), s.b().position().y(), s.a().grade(), s.b().grade()) && !(length < 0.1) && !(length > 2048.0)) {
            if (!(Math.abs(s.a().grade()) > 0.12) && !(Math.abs(s.b().grade()) > 0.12)) {
               double dy = Math.abs(delta.y()) / 2.0;
               TollPlaza.Config c = s.config();
               double a = 18.0;
               double b = 18.0;

               for (int direction = 0; direction < (c.twoWay() ? 2 : 1); direction++) {
                  TollPlanner.Band core = core(c, direction);

                  for (boolean first : new boolean[]{true, false}) {
                     TollPlanner.Band port = band(s, first, direction);
                     double displacement = Math.max(Math.abs(core.inner() - port.inner()), Math.abs(core.outer() - port.outer()));
                     int changes = Math.abs(core.lanes() - port.lanes());
                     double side = Math.max(18.0, Math.max(1.875 * displacement / 0.22, Math.sqrt(5.78 * displacement * 24.0))) + (double)(changes * 3);
                     if (first) {
                        a = Math.max(a, side);
                     } else {
                        b = Math.max(b, side);
                     }
                  }
               }

               double margin = Math.max(0.035, 0.12 - 0.65 * Math.max(Math.abs(s.a().grade()), Math.abs(s.b().grade())));
               a = Math.max(a, 1.5 * dy / margin + 4.0);
               b = Math.max(b, 1.5 * dy / margin + 4.0);
               return new TollPlanner.Lengths(length, a, 28.0, b);
            } else {
               throw new IllegalArgumentException("收费站接口纵坡最多 12%");
            }
         } else {
            throw new IllegalArgumentException("收费站 A/B 水平距离须为 0.1–2048 格");
         }
      }
   }

   public static TollPlaza.Spec alignFreePort(TollPlaza.Spec s, TollPlaza.Adjust mode, RoadGeometry.V fixedAway) {
      if (mode != TollPlaza.Adjust.A && mode != TollPlaza.Adjust.B) {
         return s;
      } else {
         boolean fixedA = mode == TollPlaza.Adjust.B;
         RoadGeometry.V requested = s.b().position().sub(s.a().position()).horizontalUnit();
         RoadGeometry.V axis = fixedAway.horizontalUnit().mul(fixedA ? -1.0 : 1.0);
         if (axis.dot(requested) <= 0.0) {
            throw new IllegalArgumentException((fixedA ? "A" : "B") + " 端外接道路朝向站内，不能通过自动移动翻转整座收费站");
         } else if (axis.dot(requested) > 0.99999999) {
            return s;
         } else {
            double length = s.b().position().sub(s.a().position()).horizontalLength();
            double yaw = RoadPlanner.yaw(axis);
            RoadGeometry.V point = fixedA ? s.a().position().add(axis.mul(length)) : s.b().position().sub(axis.mul(length));
            RoadGeometry.Node a = fixedA ? s.a() : new RoadGeometry.Node(new RoadGeometry.V(point.x(), s.a().position().y(), point.z()), yaw, s.a().grade());
            RoadGeometry.Node b = fixedA ? new RoadGeometry.Node(new RoadGeometry.V(point.x(), s.b().position().y(), point.z()), yaw, s.b().grade()) : s.b();
            return new TollPlaza.Spec(a, b, s.config(), s.portA(), s.portB());
         }
      }
   }

   public static TollPlanner.Fit fit(TollPlaza.Spec s, TollPlaza.Adjust mode) {
      if (!s.path().isEmpty()) {
         if (mode != TollPlaza.Adjust.NONE) {
            throw new IllegalArgumentException("道路断面收费站固定原路接口，请调整收费通道数量或选址");
         } else {
            return new TollPlanner.Fit(s, s, lengths(s), 0.0, 0.0);
         }
      } else if (mode == null) {
         throw new IllegalArgumentException("自动端点调整模式无效");
      } else {
         TollPlanner.Lengths lengths = lengths(s);
         double deficit = lengths.deficit();
         if (!(deficit < 1.0E-6) && mode != TollPlaza.Adjust.NONE) {
            double da = mode == TollPlaza.Adjust.B ? 0.0 : (mode == TollPlaza.Adjust.A ? deficit : deficit / 2.0);
            double db = deficit - da;
            RoadGeometry.V axis = s.b().position().sub(s.a().position()).horizontalUnit();
            RoadGeometry.Node a = new RoadGeometry.Node(s.a().position().sub(axis.mul(da)), s.a().yaw(), s.a().grade());
            RoadGeometry.Node b = new RoadGeometry.Node(s.b().position().add(axis.mul(db)), s.b().yaw(), s.b().grade());
            TollPlaza.Spec fitted = new TollPlaza.Spec(a, b, s.config(), s.portA(), s.portB());
            return new TollPlanner.Fit(s, fitted, lengths(fitted), da, db);
         } else {
            return new TollPlanner.Fit(s, s, lengths, 0.0, 0.0);
         }
      }
   }

   public static TollPlanner.Band band(TollPlaza.Config c, boolean a, int direction) {
      return band(c, TollPlaza.port(c, a), a, direction);
   }

   public static TollPlanner.Band band(TollPlaza.Spec s, boolean a, int direction) {
      return band(s.config(), TollPlaza.port(s, a), a, direction);
   }

   private static TollPlanner.Band band(TollPlaza.Config c, RoadGeometry.Settings settings, boolean a, int direction) {
      RoadProfile.Layout layout = RoadProfile.layout(settings, settings.width());
      int sign = RoadProfile.trafficSign(c.leftTraffic()) * (direction == 0 ? 1 : -1);
      int n = (direction == 0 ? c.forward() : c.backward()).lanes(a);
      return !c.twoWay()
         ? new TollPlanner.Band(layout.motorMin(), layout.motorMax(), n, 1)
         : new TollPlanner.Band(layout.medianCenter() + (double)sign * layout.median() / 2.0, layout.outer(sign), n, sign);
   }

   public static TollPlanner.Band core(TollPlaza.Config c, int direction) {
      double pitch = pitch(c);
      if (!c.twoWay()) {
         double w = (double)c.forward().units() * pitch;
         return new TollPlanner.Band(-w / 2.0, w / 2.0, c.forward().units(), 1);
      } else {
         int sign = RoadProfile.trafficSign(c.leftTraffic());
         double median = (double)sign * (directionWidth(c, 1) - directionWidth(c, 0)) / 2.0;
         sign *= direction == 0 ? 1 : -1;
         int n = (direction == 0 ? c.forward() : c.backward()).units();
         double inner = median + (double)sign * 1.2 / 2.0;
         return new TollPlanner.Band(inner, inner + (double)sign * directionWidth(c, direction), n, sign);
      }
   }

   private static double directionWidth(TollPlaza.Config c, int dir) {
      return (double)(dir == 0 ? c.forward() : c.backward()).units() * (c.charges(dir) ? pitch(c) : c.laneWidth());
   }

   private static double coreWidth(TollPlaza.Config c) {
      return directionWidth(c, 0) + (c.twoWay() ? directionWidth(c, 1) + 1.2 : 0.0) + 1.2 + 1.0;
   }

   private static double elevation(TollPlaza.Spec s, double d, double from, double to, double length) {
      double y = (s.a().position().y() + s.b().position().y()) / 2.0;
      if (d < from) {
         double t = d / from;
         return lerp(s.a().position().y(), y, t * t * (3.0 - 2.0 * t)) + s.a().grade() * from * t * (t - 1.0) * (t - 1.0);
      } else if (d > to) {
         double n = length - to;
         double t = (d - to) / n;
         return lerp(y, s.b().position().y(), t * t * (3.0 - 2.0 * t)) + s.b().grade() * n * t * t * (t - 1.0);
      } else {
         return y;
      }
   }

   public static TollPlanner.Plan plan(TollPlaza.Spec s) {
      return !s.path().isEmpty() ? TollInlineGeometry.plan(s) : station(s, false);
   }

   static TollPlanner.Plan centered(TollPlaza.Spec s) {
      return station(s, true);
   }

   private static TollPlanner.Plan station(TollPlaza.Spec s, boolean centered) {
      TollPlanner.Lengths ls = lengths(s);
      if (ls.deficit() > 1.0E-5) {
         throw new IllegalArgumentException(
            String.format(Locale.ROOT, "收费站长度 %.1f / 最低 %.1f，尚缺 %.1f 格；请选择 AB、仅 A 或仅 B 自动调整", ls.current(), ls.minimum(), ls.deficit())
         );
      } else {
         TollPlaza.Config c = s.config();
         double extra = Math.max(0.0, ls.current() - ls.minimum());
         double from = centered ? (ls.current() - 28.0) / 2.0 : ls.a() + extra / 2.0;
         double to = from + 28.0;
         if (!(from + 1.0E-5 < ls.a()) && !(ls.current() - to + 1.0E-5 < ls.b())) {
            RoadGeometry.V axis = s.b().position().sub(s.a().position()).horizontalUnit();
            RoadGeometry.V left = axis.left();
            double wa = TollPlaza.port(s, true).width();
            double wb = TollPlaza.port(s, false).width();
            double wc = coreWidth(c);
            TreeSet<Double> stations = new TreeSet<>();
            int count = (int)Math.ceil(ls.current() / 0.5);

            for (int i = 0; i <= count; i++) {
               stations.add(ls.current() * (double)i / (double)count);
            }

            for (double knot : new double[]{from, to}) {
               stations.removeIf(dx -> Math.abs(dx - knot) < 1.0E-7);
               stations.add(knot);
            }

            ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
            double lastY = 0.0;
            double lastD = 0.0;

            for (double d : stations) {
               double w = d < from ? lerp(wa, wc, smooth(d / from)) : (d > to ? lerp(wc, wb, smooth((d - to) / (ls.current() - to))) : wc);
               double y = elevation(s, d, from, to, ls.current());
               if (!samples.isEmpty() && Math.abs(y - lastY) / (d - lastD) > 0.12000999999999999) {
                  throw new IllegalArgumentException("当前高差/接口坡度不能形成平顺收费核心，请增加站区长度");
               }

               RoadGeometry.V p = s.a().position().add(axis.mul(d));
               samples.add(new RoadGeometry.Sample(new RoadGeometry.V(p.x(), y, p.z()), left, d, w / 2.0));
               lastY = y;
               lastD = d;
            }

            RoadGeometry.Settings settings = new RoadGeometry.Settings(
                  RoadGeometry.Mode.STRAIGHT, RoadGeometry.Style.TOLL, Math.max(wc, Math.max(wa, wb)), 1.0, 0.35, 90.0
               )
               .structure(c.structure())
               .options(RoadProfile.Options.DEFAULT.cycleFinish(RoadProfile.Options.CycleFinish.NONE).hideArrows(true));
            RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, settings);
            ArrayList<RoadStructures.Part> parts = new ArrayList<>();
            ArrayList<RoadSurface.Face> paint = new ArrayList<>();
            ArrayList<TollPlanner.Unit> units = new ArrayList<>();
            TollPlanner.Geometry geometry = new TollPlanner.Geometry(s, axis, left, from, to, ls.current());

            for (int i = 1; i < samples.size(); i++) {
               for (int side : new int[]{-1, 1}) {
                  RoadGeometry.Sample a = samples.get(i - 1);
                  RoadGeometry.Sample b = samples.get(i);
                  line(paint, a.at((double)side * (a.halfWidth() - 0.28), -0.015), b.at((double)side * (b.halfWidth() - 0.28), -0.015), 0.12, 16448240);
                  parts.add(
                     new RoadStructures.Part(
                        a.at((double)side * (a.halfWidth() - 0.09), 0.0),
                        b.at((double)side * (b.halfWidth() - 0.09), 0.0),
                        0.16,
                        0.65,
                        false,
                        RoadStructures.Material.CONCRETE,
                        left.mul(0.08),
                        left.mul(0.08),
                        "sr:toll_outer"
                     )
                  );
               }
            }

            if (c.twoWay()) {
               for (int i = 1; i < samples.size(); i++) {
                  double da = geometry.station(samples.get(i - 1).center());
                  double db = geometry.station(samples.get(i).center());
                  double ma = median(s, da, from, to, ls.current());
                  double mb = median(s, db, from, to, ls.current());
                  parts.add(geometry.part(da, db, ma, mb, 0.32, 0.8, 0.0, RoadStructures.Material.CONCRETE, "sr:toll_median"));
               }
            }

            for (int dir = 0; dir < (c.twoWay() ? 2 : 1); dir++) {
               TollPlaza.Direction direction = dir == 0 ? c.forward() : c.backward();
               TollPlanner.Band core = core(c, dir);
               int flow = c.twoWay() ? (dir == 0 ? 1 : -1) : (c.reverse() ? -1 : 1);
               if (!c.charges(dir)) {
                  for (int k = 1; k < core.lanes(); k++) {
                     for (double dash = 0.0; dash < ls.current(); dash += 7.0) {
                        for (double d = dash; d < Math.min(dash + 4.0, ls.current()) - 1.0E-7; d++) {
                           double end = Math.min(d + 1.0, Math.min(dash + 4.0, ls.current()));
                           line(
                              paint,
                              geometry.at(d, laneAt(s, dir, k, d, from, to, ls.current()), 0.022),
                              geometry.at(end, laneAt(s, dir, k, end, from, to, ls.current()), 0.022),
                              0.15,
                              16448240
                           );
                        }
                     }
                  }
               } else {
                  double center = (from + to) / 2.0;
                  double pitch = pitch(c);

                  for (int k = 0; k <= core.lanes(); k++) {
                     double lateral = core.at((double)k / (double)core.lanes());
                     parts.add(geometry.part(center - 6.0, center + 6.0, lateral, lateral, 1.2, 0.35, 0.0, RoadStructures.Material.CONCRETE, "sr:toll_island"));
                     parts.add(
                        geometry.part(
                           center - (double)(flow * 6),
                           center - (double)(flow * 5),
                           lateral,
                           lateral,
                           1.08,
                           1.25,
                           0.35,
                           RoadStructures.Material.STEEL,
                           "sr:toll_model_island_nose"
                        )
                     );
                     if (k < core.lanes()) {
                        parts.add(
                           geometry.part(
                              center + (double)flow * 2.4,
                              center + (double)flow * 3.4,
                              lateral,
                              lateral,
                              0.94,
                              4.5,
                              0.35,
                              RoadStructures.Material.STEEL,
                              "sr:toll_model_gate"
                           )
                        );
                     }

                     for (int stripe = 0; stripe < 12; stripe++) {
                        for (int edge : new int[]{-1, 1}) {
                           if (!c.twoWay() || k != 0 || edge != -core.sign()) {
                              double d = center - 6.0 + (double)stripe;
                              parts.add(
                                 geometry.part(
                                    d,
                                    d + 0.94,
                                    lateral + (double)edge * 0.618,
                                    lateral + (double)edge * 0.618,
                                    0.055,
                                    0.2,
                                    0.08,
                                    stripe % 2 == 0 ? RoadStructures.Material.TACTILE : RoadStructures.Material.DARK_STEEL,
                                    "sr:toll_curb"
                                 )
                              );
                           }
                        }
                     }

                     for (double d : new double[]{center - 4.5, center + 4.5}) {
                        parts.add(
                           geometry.part(
                              d - 0.24, d + 0.24, lateral, lateral, 0.46, c.clearHeight() + 0.8, 0.35, RoadStructures.Material.STEEL, "sr:toll_column"
                           )
                        );
                        parts.add(
                           geometry.part(d - 0.32, d + 0.32, lateral, lateral, 0.62, 0.22, 0.35, RoadStructures.Material.DARK_STEEL, "sr:toll_column_plinth")
                        );
                        parts.add(
                           geometry.part(d - 0.27, d + 0.27, lateral, lateral, 0.52, 0.12, 1.52, RoadStructures.Material.SIGN_BLUE, "sr:toll_column_band")
                        );
                        parts.add(
                           geometry.part(
                              d - 0.3, d + 0.3, lateral, lateral, 0.58, 0.14, c.clearHeight() + 0.7, RoadStructures.Material.SIGN_WHITE, "sr:toll_column_cap"
                           )
                        );
                     }
                  }

                  for (int k = 0; k < core.lanes(); k++) {
                     double lateral = core.at(((double)k + 0.5) / (double)core.lanes());
                     boolean etc = k < direction.etc();
                     units.add(new TollPlanner.Unit(flow, k, etc, geometry.at(center, lateral, 0.0), c.passageWidth(), c.clearHeight()));
                     parts.add(
                        geometry.part(
                           center - 5.7,
                           center + 5.7,
                           lateral,
                           lateral,
                           pitch,
                           0.35,
                           c.clearHeight() + 1.0,
                           RoadStructures.Material.DARK_STEEL,
                           "sr:toll_canopy"
                        )
                     );

                     for (double d : new double[]{center - 5.86, center + 5.68}) {
                        parts.add(
                           geometry.part(
                              d, d + 0.18, lateral, lateral, pitch, 0.44, c.clearHeight() + 0.75, RoadStructures.Material.SIGN_WHITE, "sr:toll_fascia"
                           )
                        );
                        parts.add(
                           geometry.part(
                              d - 0.025,
                              d + 0.205,
                              lateral,
                              lateral,
                              pitch,
                              0.09,
                              c.clearHeight() + 1.19,
                              RoadStructures.Material.SIGN_BLUE,
                              "sr:toll_fascia_trim"
                           )
                        );
                        parts.add(
                           geometry.part(
                              d + 0.02,
                              d + 0.16,
                              lateral,
                              lateral,
                              pitch - 0.25,
                              0.05,
                              c.clearHeight() + 0.72,
                              RoadStructures.Material.LAMP,
                              "sr:toll_edge_light"
                           )
                        );
                     }

                     for (int roof = 0; roof < 8; roof++) {
                        double d = center - 5.6 + (double)roof * 1.4;
                        double up = c.clearHeight() + 1.35 + 0.15 * (1.0 - Math.abs((double)roof - 3.5) / 3.5);
                        parts.add(geometry.part(d, d + 1.38, lateral, lateral, pitch - 0.05, 0.1, up, RoadStructures.Material.STEEL, "sr:toll_roof_panel"));
                        parts.add(
                           geometry.part(d, d + 0.035, lateral, lateral, pitch - 0.05, 0.035, up + 0.1, RoadStructures.Material.SIGN_WHITE, "sr:toll_roof_seam")
                        );
                     }

                     for (int edgex : new int[]{-1, 1}) {
                        double x = lateral + (double)edgex * (pitch / 2.0 - 0.35);
                        parts.add(
                           geometry.part(
                              center - 4.6, center + 4.6, x, x, 0.13, 0.13, c.clearHeight() + 0.53, RoadStructures.Material.SIGN_WHITE, "sr:toll_soffit_rib"
                           )
                        );
                     }

                     parts.add(
                        geometry.part(
                           center - (double)flow * 5.4 - 0.15,
                           center - (double)flow * 5.4 + 0.15,
                           lateral,
                           lateral,
                           c.passageWidth(),
                           0.72,
                           c.clearHeight() + 0.05,
                           etc ? RoadStructures.Material.SIGN_GREEN : RoadStructures.Material.SIGN_BLUE,
                           etc ? "sr:toll_ETC" : "sr:toll_manual"
                        )
                     );
                     glyph(parts, geometry, center - (double)flow * 5.4 - (double)flow * 0.18, lateral, c.clearHeight() + 0.16, etc ? "ETC" : "M", flow);
                     parts.add(
                        geometry.part(
                           center - 2.0, center + 2.0, lateral, lateral, 0.35, 0.08, c.clearHeight() + 0.9, RoadStructures.Material.LAMP, "sr:toll_light"
                        )
                     );
                     if (!etc) {
                        double booth = core.at((double)k / (double)core.lanes());
                        parts.add(geometry.part(center - 1.4, center + 1.4, booth, booth, 0.92, 1.1, 0.35, RoadStructures.Material.STEEL, "sr:toll_booth_base"));
                        parts.add(
                           geometry.part(
                              center - 1.36, center + 1.36, booth, booth, 0.84, 1.08, 1.45, RoadStructures.Material.SIGN_BLUE, "sr:toll_window_glass"
                           )
                        );

                        for (int side : new int[]{-1, 1}) {
                           double x = booth + (double)side * 0.455;

                           for (double d : new double[]{center - 1.35, center - 0.5, center + 0.5, center + 1.28}) {
                              parts.add(geometry.part(d, d + 0.07, x, x, 0.07, 1.08, 1.45, RoadStructures.Material.SIGN_WHITE, "sr:toll_window_frame"));
                           }

                           for (double y : new double[]{1.43, 2.0, 2.53}) {
                              parts.add(
                                 geometry.part(center - 1.4, center + 1.4, x, x, 0.07, 0.065, y, RoadStructures.Material.SIGN_WHITE, "sr:toll_window_frame")
                              );
                           }

                           parts.add(geometry.part(center - 0.62, center + 0.62, x, x, 0.13, 0.08, 1.36, RoadStructures.Material.DARK_STEEL, "sr:toll_counter"));
                           parts.add(
                              geometry.part(
                                 center + 1.04,
                                 center + 1.1,
                                 x + (double)side * 0.025,
                                 x + (double)side * 0.025,
                                 0.06,
                                 0.18,
                                 0.91,
                                 RoadStructures.Material.DARK_STEEL,
                                 "sr:toll_door_handle"
                              )
                           );
                        }

                        parts.add(geometry.part(center + 1.3, center + 1.39, booth, booth, 0.84, 1.08, 0.35, RoadStructures.Material.SIGN_BLUE, "sr:toll_door"));
                        parts.add(
                           geometry.part(center - 1.52, center + 1.52, booth, booth, 1.1, 0.18, 2.6, RoadStructures.Material.DARK_STEEL, "sr:toll_booth_roof")
                        );
                        parts.add(
                           geometry.part(
                              center - 1.48, center + 1.48, booth, booth, 1.02, 0.1, 2.78, RoadStructures.Material.SIGN_BLUE, "sr:toll_booth_roof_trim"
                           )
                        );

                        for (int side : new int[]{-1, 1}) {
                           double x = booth + (double)side * 0.465;
                           parts.add(
                              geometry.part(center - 1.3, center + 1.3, x, x, 0.025, 0.055, 0.56, RoadStructures.Material.SIGN_BLUE, "sr:toll_booth_body_trim")
                           );

                           for (int vent = 0; vent < 5; vent++) {
                              parts.add(
                                 geometry.part(
                                    center + 0.64,
                                    center + 0.98,
                                    x,
                                    x,
                                    0.025,
                                    0.025,
                                    0.72 + (double)vent * 0.08,
                                    RoadStructures.Material.DARK_STEEL,
                                    "sr:toll_booth_vent"
                                 )
                              );
                           }

                           parts.add(
                              geometry.part(
                                 center - 0.5,
                                 center + 0.12,
                                 x + (double)side * 0.025,
                                 x + (double)side * 0.025,
                                 0.07,
                                 0.34,
                                 1.03,
                                 RoadStructures.Material.DARK_STEEL,
                                 "sr:toll_payment_panel"
                              )
                           );
                           parts.add(
                              geometry.part(
                                 center - 0.42,
                                 center + 0.04,
                                 x + (double)side * 0.065,
                                 x + (double)side * 0.065,
                                 0.015,
                                 0.13,
                                 1.18,
                                 RoadStructures.Material.LAMP,
                                 "sr:toll_payment_screen"
                              )
                           );
                        }

                        parts.add(
                           geometry.part(center - 1.45, center - 1.24, booth, booth, 0.18, 0.13, 2.55, RoadStructures.Material.LAMP, "sr:toll_booth_door_light")
                        );
                     }
                  }

                  for (boolean a : new boolean[]{true, false}) {
                     TollPlanner.Band port = band(s, a, dir);
                     double taper = a ? from : ls.current() - to;
                     double endRun = Math.min(10.0, taper * 0.22);
                     double boothRun = Math.min(16.0, taper * 0.28);

                     for (int k = 1; k < port.lanes(); k++) {
                        divider(paint, geometry, port.at((double)k / (double)port.lanes()), a ? 0.0 : ls.current() - endRun, a ? endRun : ls.current());
                     }

                     for (int k = 1; k < core.lanes(); k++) {
                        divider(
                           paint, geometry, core.at((double)k / (double)core.lanes()), a ? from - boothRun : center + 6.0, a ? center - 6.0 : to + boothRun
                        );
                     }
                  }
               }
            }

            if (!c.name().isEmpty()) {
               for (int side : nameSides(c)) {
                  double d = (from + to) / 2.0 + (double)side * 5.7;
                  double w = nameWidth(c, side);
                  double cx = nameCenter(c, side);
                  parts.add(
                     geometry.part(d - 0.09, d + 0.09, cx, cx, w, 0.14, c.clearHeight() + 1.5, RoadStructures.Material.DARK_STEEL, "sr:toll_model_name_sill")
                  );
                  parts.add(
                     geometry.part(
                        d - 0.055, d + 0.055, cx, cx, w, 1.42, c.clearHeight() + 1.64, RoadStructures.Material.SIGN_WHITE, "sr:toll_model_name_panel"
                     )
                  );

                  for (double x : new double[]{cx - w * 0.35, cx + w * 0.35}) {
                     parts.add(
                        geometry.part(d - 0.22, d + 0.22, x, x, 0.12, 0.35, c.clearHeight() + 1.3, RoadStructures.Material.STEEL, "sr:toll_model_name_foot")
                     );
                  }
               }
            }

            return new TollPlanner.Plan(s, ls, mesh, List.copyOf(parts), List.copyOf(paint), List.copyOf(units), from, to);
         } else {
            throw new IllegalArgumentException("收费站中心两侧过渡空间不足");
         }
      }
   }

   private static double laneAt(TollPlaza.Spec s, int dir, int lane, double d, double from, double to, double length) {
      double fraction = (double)lane / (double)core(s.config(), dir).lanes();
      double middle = core(s.config(), dir).at(fraction);
      return d < from
         ? lerp(band(s, true, dir).at(fraction), middle, smooth(d / from))
         : (d > to ? lerp(middle, band(s, false, dir).at(fraction), smooth((d - to) / (length - to))) : middle);
   }

   private static double median(TollPlaza.Spec spec, double d, double from, double to, double length) {
      TollPlaza.Config c = spec.config();
      double m = core(c, 0).inner() - (double)RoadProfile.trafficSign(c.leftTraffic()) * 1.2 / 2.0;
      if (d < from) {
         RoadGeometry.Settings s = TollPlaza.port(spec, true);
         return lerp(RoadProfile.layout(s, s.width()).medianCenter(), m, smooth(d / from));
      } else if (d > to) {
         RoadGeometry.Settings s = TollPlaza.port(spec, false);
         return lerp(m, RoadProfile.layout(s, s.width()).medianCenter(), smooth((d - to) / (length - to)));
      } else {
         return m;
      }
   }

   private static void line(List<RoadSurface.Face> out, RoadGeometry.V a, RoadGeometry.V b, double width, int color) {
      RoadGeometry.V side = b.sub(a).horizontalUnit().left().mul(width / 2.0);
      out.add(new RoadSurface.Face(List.of(a.sub(side), b.sub(side), b.add(side), a.add(side)), color));
   }

   private static void divider(List<RoadSurface.Face> out, TollPlanner.Geometry g, double lateral, double from, double to) {
      for (double dash = from; dash < to - 1.0E-7; dash += 7.0) {
         for (double d = dash; d < Math.min(dash + 4.0, to) - 1.0E-7; d++) {
            double end = Math.min(d + 1.0, Math.min(dash + 4.0, to));
            line(out, g.at(d, lateral, 0.022), g.at(end, lateral, 0.022), 0.15, 16448240);
         }
      }
   }

   public static List<Integer> nameSides(TollPlaza.Config c) {
      return c.twoWay() ? (c.chargedDirections() == 3 ? List.of(-1, 1) : List.of(c.charges(0) ? -1 : 1)) : List.of(c.reverse() ? 1 : -1);
   }

   public static double nameCenter(TollPlaza.Config c, int side) {
      return core(c, c.twoWay() && side > 0 ? 1 : 0).at(0.5);
   }

   public static double nameWidth(TollPlaza.Config c, int side) {
      TollPlanner.Band band = core(c, c.twoWay() && side > 0 ? 1 : 0);
      return Math.max(2.0, Math.min(Math.abs(band.outer() - band.inner()) - 0.5, c.name().codePoints().mapToDouble(v -> v < 128 ? 0.96 : 1.44).sum() + 0.6));
   }

   public static double nameWidth(TollPlaza.Config c) {
      return nameWidth(c, nameSides(c).get(0));
   }

   private static void glyph(List<RoadStructures.Part> parts, TollPlanner.Geometry g, double d, double x, double up, String text, int flow) {
      parts.add(
         g.part(
            d - (double)flow * 0.025,
            d + (double)flow * 0.035,
            x,
            x,
            (double)text.length() * 0.4 - 0.12,
            0.48,
            up,
            RoadStructures.Material.SIGN_WHITE,
            "sr:toll_model_label_" + text
         )
      );
   }

   private TollPlanner() {
   }

   public static record Band(double inner, double outer, int lanes, int sign) {
      public double at(double fraction) {
         return this.inner + (this.outer - this.inner) * fraction;
      }
   }

   public static record Fit(TollPlaza.Spec original, TollPlaza.Spec fitted, TollPlanner.Lengths lengths, double movedA, double movedB) {
   }

   private static record Geometry(TollPlaza.Spec spec, RoadGeometry.V axis, RoadGeometry.V left, double from, double to, double length) {
      double station(RoadGeometry.V v) {
         return v.sub(this.spec.a().position()).dot(this.axis);
      }

      RoadGeometry.V at(double d, double lateral, double up) {
         RoadGeometry.V p = this.spec.a().position().add(this.axis.mul(d)).add(this.left.mul(lateral));
         return new RoadGeometry.V(p.x(), TollPlanner.elevation(this.spec, d, this.from, this.to, this.length) + up, p.z());
      }

      RoadStructures.Part part(double a, double b, double x, double y, double width, double height, double up, RoadStructures.Material material, String model) {
         return new RoadStructures.Part(
            this.at(a, x, up), this.at(b, y, up), Math.min(8.0, width), height, false, material, this.left.mul(width / 2.0), this.left.mul(width / 2.0), model
         );
      }
   }

   public static record Lengths(double current, double a, double core, double b) {
      public double minimum() {
         return this.a + this.core + this.b;
      }

      public double deficit() {
         return Math.max(0.0, this.minimum() - this.current);
      }
   }

   public static record Plan(
      TollPlaza.Spec spec,
      TollPlanner.Lengths lengths,
      RoadGeometry.Mesh mesh,
      List<RoadStructures.Part> structures,
      List<RoadSurface.Face> paint,
      List<TollPlanner.Unit> units,
      double coreStart,
      double coreEnd
   ) {
   }

   public static record Unit(int direction, int index, boolean etc, RoadGeometry.V center, double clearWidth, double clearHeight) {
   }
}
