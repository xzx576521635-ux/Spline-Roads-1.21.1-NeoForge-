package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DoubleTrumpetPlanner {
   public static final String CONNECTOR = "双喇叭中央连接段";

   private static double cross(RoadGeometry.V a, RoadGeometry.V b) {
      return a.x() * b.z() - a.z() * b.x();
   }

   public static InterchangePlanner.Plan plan(RoadGeometry.Node[] nodes, RoadGeometry.Settings a, RoadGeometry.Settings b, InterchangePlanner.Options o) {
      if (nodes.length != 4) {
         throw new IllegalArgumentException("双喇叭需要四个主路端点");
      } else {
         for (RoadGeometry.Settings s : List.of(a, b)) {
            s.validate();
            if (s.style().ramp() || !RoadProfile.catalog(s).twoWay()) {
               throw new IllegalArgumentException("双喇叭两条主路都需要完整双向道路");
            }
         }

         double ab = nodes[1].position().sub(nodes[0].position()).horizontalLength();
         double cd = nodes[3].position().sub(nodes[2].position()).horizontalLength();
         if (!(ab < 100.0) && !(cd < 100.0) && !(ab > 4096.000001) && !(cd > 4096.000001)) {
            InterchangePlanner.Levels levels = InterchangePlanner.levels(nodes, a, b, o);
            if (!o.flatConnector()
               || !(o.connectorHeight() < Math.min(levels.first(), levels.second()) - 1.0E-7)
                  && !(o.connectorHeight() > Math.max(levels.first(), levels.second()) + 1.0E-7)) {
               RoadGeometry.V u = nodes[1].position().sub(nodes[0].position()).horizontalUnit();
               RoadGeometry.V v = nodes[3].position().sub(nodes[2].position()).horizontalUnit();
               double det = cross(u, v);
               if (Math.abs(det) < 0.5) {
                  throw new IllegalArgumentException("双喇叭要求主路夹角至少 30 度");
               } else {
                  RoadGeometry.V center = nodes[0].position().add(u.mul(cross(nodes[2].position().sub(nodes[0].position()), v) / det));
                  double alongA = center.sub(nodes[0].position()).dot(u);
                  double alongB = center.sub(nodes[2].position()).dot(v);
                  if (!(Math.min(alongA, alongB) < 45.0) && !(Math.min(ab - alongA, cd - alongB) <= 0.0)) {
                     double shortest = Math.min(nodes[0].position().sub(center).horizontalLength(), nodes[2].position().sub(center).horizontalLength());
                     double offset = shortest * 0.6;
                     double reach = offset * 0.75;
                     RoadGeometry.V na = u.left().mul(u.left().dot(v) < 0.0 ? 1.0 : -1.0);
                     RoadGeometry.V nb = v.left().mul(v.left().dot(u) < 0.0 ? 1.0 : -1.0);
                     double ya = levels.first();
                     double yb = levels.second();
                     RoadGeometry.V pa = center.sub(u.mul(offset)).add(na.mul(reach));
                     RoadGeometry.V pb = center.sub(v.mul(offset)).add(nb.mul(reach));
                     pa = new RoadGeometry.V(pa.x(), ya, pa.z());
                     pb = new RoadGeometry.V(pb.x(), yb, pb.z());
                     RoadLanes.Counts ac = RoadLanes.counts(a);
                     RoadLanes.Counts bc = RoadLanes.counts(b);
                     int lanes = Math.min(Math.min(ac.forward(), ac.reverse()), Math.min(bc.forward(), bc.reverse()));
                     boolean lamps = RoadProfile.catalog(a).type() != RoadProfile.Type.HIGHWAY || RoadProfile.catalog(b).type() != RoadProfile.Type.HIGHWAY;
                     double laneWidth = Math.min(RoadProfile.layout(a, a.width()).laneWidth(), RoadProfile.layout(b, b.width()).laneWidth());
                     RoadGeometry.Settings connector = RoadLanes.configure(
                        new RoadGeometry.Settings(RoadGeometry.Mode.STRAIGHT, RoadGeometry.Style.O2_RAIL, 10.0, 1.0, 0.4, 90.0)
                           .structure(RoadGeometry.Structure.AUTO)
                           .options(RoadProfile.Options.DEFAULT.traffic(o.leftTraffic()).streetscape(RoadStreetscape.Config.DEFAULT.lamps(lamps))),
                        RoadProfile.Type.ORDINARY,
                        new RoadLanes.Counts(lanes, lanes),
                        laneWidth
                     );
                     InterchangePlanner.Options child = new InterchangePlanner.Options(
                        InterchangePlanner.Preset.TRUMPET,
                        o.leftTraffic(),
                        o.lanes(),
                        o.transition(),
                        o.radius(),
                        o.clearance(),
                        o.upper(),
                        o.rampWidth(),
                        false
                     );
                     RoadGeometry.V ca = center.sub(u.mul(offset));
                     RoadGeometry.V cb = center.sub(v.mul(offset));
                     InterchangePlanner.Plan first = InterchangePlanner.plan(
                        new RoadGeometry.Node[]{near(nodes[0], ca, u), near(nodes[1], ca, u), new RoadGeometry.Node(pa, RoadPlanner.yaw(na.mul(-1.0)), 0.0)},
                        a,
                        connector,
                        child
                     );
                     InterchangePlanner.Plan second = InterchangePlanner.plan(
                        new RoadGeometry.Node[]{near(nodes[2], cb, v), near(nodes[3], cb, v), new RoadGeometry.Node(pb, RoadPlanner.yaw(nb.mul(-1.0)), 0.0)},
                        b,
                        connector,
                        child
                     );
                     RoadGeometry.V outerA = landing(pa, na, o);
                     RoadGeometry.V outerB = landing(pb, nb, o);
                     ArrayList<InterchangePlanner.Leg> legs = new ArrayList<>();
                     legs.add(new InterchangePlanner.Leg("主路 1", 0, 2, extend(first.legs().get(0).mesh(), nodes[0], nodes[1])));
                     legs.add(new InterchangePlanner.Leg("主路 2", 1, 3, extend(second.legs().get(0).mesh(), nodes[2], nodes[3])));

                     for (int i = 1; i < first.legs().size(); i++) {
                        InterchangePlanner.Leg l = first.legs().get(i);
                        legs.add(
                           new InterchangePlanner.Leg("AB 侧 " + l.name(), arm(l.from(), 0), arm(l.to(), 0), i == 1 ? approach(l.mesh(), outerA) : l.mesh())
                        );
                     }

                     int secondStart = legs.size();

                     for (int i = 1; i < second.legs().size(); i++) {
                        InterchangePlanner.Leg l = second.legs().get(i);
                        legs.add(
                           new InterchangePlanner.Leg("CD 侧 " + l.name(), arm(l.from(), 1), arm(l.to(), 1), i == 1 ? approach(l.mesh(), outerB) : l.mesh())
                        );
                     }

                     RoadGeometry.Mesh approachA = legs.get(2).mesh();
                     RoadGeometry.Mesh approachB = legs.get(secondStart).mesh();
                     double cutA = connectorStart(approachA, first);
                     double cutB = connectorStart(approachB, second);
                     double extraA = 0.0;
                     double extraB = 0.0;
                     if (o.toll().enabled()) {
                        double required = DoubleTrumpetToll.length(connector, o) + 16.0;
                        if (o.toll().near() == DoubleTrumpetToll.Near.AB) {
                           extraA = Math.max(0.0, required - cutA);
                        } else {
                           extraB = Math.max(0.0, required - cutB);
                        }
                     }

                     RoadGeometry.V curveA = outerA.add(na.mul(extraA));
                     RoadGeometry.V curveB = outerB.add(nb.mul(extraB));
                     RoadGeometry.Mesh middle = connector(curveA, curveB, na, nb.mul(-1.0), connector, o);
                     ArrayList<RoadGeometry.Sample> central = new ArrayList<>();
                     if (cutA > 0.0) {
                        ArrayList<RoadGeometry.Sample> prefix = new ArrayList<>(RoadSlicing.slice(approachA, 0.0, cutA, connector).mesh().samples());
                        Collections.reverse(prefix);

                        for (RoadGeometry.Sample p : prefix) {
                           central.add(new RoadGeometry.Sample(p.center(), new RoadGeometry.V(-p.left().x(), 0.0, -p.left().z()), 0.0, p.halfWidth()));
                        }
                     }

                     line(central, outerA, curveA, na.left(), connector.width() / 2.0);
                     central.addAll(middle.samples().subList(central.isEmpty() ? 0 : 1, middle.samples().size()));
                     line(central, curveB, outerB, nb.mul(-1.0).left(), connector.width() / 2.0);
                     if (cutB > 0.0) {
                        List<RoadGeometry.Sample> suffix = RoadSlicing.slice(approachB, 0.0, cutB, connector).mesh().samples();
                        central.addAll(suffix.subList(1, suffix.size()));
                     }

                     middle = RoadRibbon.mesh(central, connector);
                     if (!o.flatConnector()) {
                        ArrayList<RoadGeometry.Sample> graded = new ArrayList<>();

                        for (RoadGeometry.Sample p : middle.samples()) {
                           double t = p.distance() / middle.length();
                           double blend = t * t * t * (10.0 + t * (-15.0 + 6.0 * t));
                           graded.add(
                              new RoadGeometry.Sample(new RoadGeometry.V(p.center().x(), ya + (yb - ya) * blend, p.center().z()), p.left(), 0.0, p.halfWidth())
                           );
                        }

                        middle = RoadRibbon.mesh(graded, connector);
                     }

                     if (RoadGrades.maximum(middle) > 0.120001) {
                        throw new IllegalArgumentException("双喇叭特殊段纵坡超过收费站 12% 上限，请扩大范围或启用水平段");
                     } else {
                        InterchangePlanner.Leg la = legs.get(2);
                        InterchangePlanner.Leg lb = legs.get(secondStart);
                        legs.set(2, new InterchangePlanner.Leg(la.name(), la.from(), la.to(), throat(approachA, cutA)));
                        legs.set(secondStart, new InterchangePlanner.Leg(lb.name(), lb.from(), lb.to(), throat(approachB, cutB)));
                        if (o.toll().enabled()) {
                           legs.addAll(DoubleTrumpetToll.split(middle, o));
                        } else {
                           legs.add(new InterchangePlanner.Leg("双喇叭中央连接段", -1, -1, middle));
                        }

                        for (int i = 0; i < legs.size(); i++) {
                           for (int j = 0; j < i; j++) {
                              boolean firstI = i == 0 || i >= 2 && i < secondStart;
                              boolean firstJ = j == 0 || j >= 2 && j < secondStart;
                              if (DoubleTrumpetToll.connector(legs.get(i).name()) || DoubleTrumpetToll.connector(legs.get(j).name()) || firstI != firstJ) {
                                 RoadGeometry.Mesh x = legs.get(i).mesh();
                                 RoadGeometry.Mesh y = legs.get(j).mesh();

                                 for (RoadClearance.DetailedContact contact : RoadClearance.detailedContacts(x, y)) {
                                    if (!sharedEnd(x, y, contact)
                                       && contact.contact().usableClearance() + 1.0E-6 < Math.max(o.clearance(), contact.contact().required())) {
                                       throw new IllegalArgumentException(legs.get(i).name() + "与" + legs.get(j).name() + "净空不足，请扩大双喇叭范围");
                                    }
                                 }
                              }
                           }
                        }

                        return new InterchangePlanner.Plan(
                           List.copyOf(legs),
                           8,
                           legs.stream()
                              .filter(l -> l.mesh().settings().style().ramp())
                              .mapToDouble(l -> RoadRibbon.minRadius(l.mesh()))
                              .min()
                              .orElse(o.radius()),
                           legs.stream().mapToDouble(l -> l.mesh().max().y()).max().orElse(0.0),
                           center,
                           List.of(nodes)
                        );
                     }
                  } else {
                     throw new IllegalArgumentException("双喇叭两轴需在端点之间相交，喇叭所在的 A、C 端距交点至少 45 格");
                  }
               }
            } else {
               throw new IllegalArgumentException("双喇叭水平段高度必须在两条主路高度之间（含边界），且为整数");
            }
         } else {
            throw new IllegalArgumentException("双喇叭两条主路总长各需 100–4096 格");
         }
      }
   }

   private static RoadGeometry.Mesh throat(RoadGeometry.Mesh source, double from) {
      RoadGeometry.Settings s = source.settings();
      RoadTransitions.Ends ends = s.options().ends();
      RoadGeometry.Settings local = s.options(s.options().ends(ends.trim(from, 0.0)));
      return RoadSlicing.slice(source, from, source.length(), local).mesh();
   }

   private static int arm(int child, int axis) {
      return child == 0 ? axis : (child == 2 ? axis + 2 : -1);
   }

   private static double connectorStart(RoadGeometry.Mesh approach, InterchangePlanner.Plan trumpet) {
      double cut = approach.length() - 2.0;
      double half = approach.first().halfWidth();

      for (RoadGeometry.Sample p : approach.samples()) {
         if (Math.abs(p.center().y() - approach.first().center().y()) > 1.0E-8) {
            return 0.0;
         }
      }

      for (RoadGeometry.Sample px : approach.samples()) {
         if (Math.abs(px.halfWidth() - half) > 1.0E-6) {
            cut = Math.min(cut, px.distance() - 1.0);
            break;
         }
      }

      for (InterchangePlanner.Leg leg : trumpet.legs()) {
         if (leg.mesh().settings().style().ramp()) {
            for (RoadClearance.DetailedContact c : RoadClearance.detailedContacts(approach, leg.mesh())) {
               cut = Math.min(cut, c.contact().from() - 1.0);
            }
         }
      }

      if (cut < 1.0) {
         throw new IllegalArgumentException("双喇叭收束后没有完整中央断面，请扩大范围");
      } else {
         return cut;
      }
   }

   private static RoadGeometry.V landing(RoadGeometry.V base, RoadGeometry.V outward, InterchangePlanner.Options o) {
      if (o.flatConnector() && !(Math.abs(base.y() - o.connectorHeight()) < 1.0E-8)) {
         double run = 1.875 * Math.abs(base.y() - o.connectorHeight()) / 0.15 + 12.0;
         RoadGeometry.V p = base.add(outward.mul(run));
         return new RoadGeometry.V(p.x(), o.connectorHeight(), p.z());
      } else {
         return base;
      }
   }

   private static RoadGeometry.Mesh approach(RoadGeometry.Mesh mesh, RoadGeometry.V outer) {
      RoadGeometry.V end = mesh.first().center();
      if (end.distance(outer) < 1.0E-7) {
         return mesh;
      } else {
         int count = (int)Math.ceil(end.distance(outer) / 0.5);
         ArrayList<RoadGeometry.Sample> points = new ArrayList<>();

         for (int i = 0; i < count; i++) {
            double t = (double)i / (double)count;
            double blend = t * t * t * (10.0 + t * (-15.0 + 6.0 * t));
            RoadGeometry.V p = outer.add(end.sub(outer).mul(t));
            points.add(
               new RoadGeometry.Sample(
                  new RoadGeometry.V(p.x(), outer.y() + (end.y() - outer.y()) * blend, p.z()), mesh.first().left(), 0.0, mesh.first().halfWidth()
               )
            );
         }

         points.addAll(mesh.samples());
         RoadGeometry.Mesh result = RoadRibbon.mesh(points, mesh.settings());
         if (RoadGrades.maximum(result) > 0.150001) {
            throw new IllegalArgumentException("双喇叭水平段引道坡度超过 15%，请扩大范围");
         } else {
            return result;
         }
      }
   }

   private static RoadGeometry.Node near(RoadGeometry.Node n, RoadGeometry.V center, RoadGeometry.V axis) {
      double d = n.position().sub(center).dot(axis);
      RoadGeometry.V at = center.add(axis.mul(Math.max(-900.0, Math.min(900.0, d))));
      return new RoadGeometry.Node(new RoadGeometry.V(at.x(), n.position().y(), at.z()), RoadPlanner.yaw(axis), 0.0);
   }

   private static RoadGeometry.Mesh extend(RoadGeometry.Mesh mesh, RoadGeometry.Node a, RoadGeometry.Node b) {
      ArrayList<RoadGeometry.Sample> points = new ArrayList<>();
      line(points, a.position(), mesh.first().center(), mesh.first().left(), mesh.first().halfWidth());
      if (!points.isEmpty()) {
         points.remove(points.size() - 1);
      }

      points.addAll(mesh.samples());
      line(points, mesh.last().center(), b.position(), mesh.last().left(), mesh.last().halfWidth());
      return RoadRibbon.mesh(points, mesh.settings());
   }

   private static void line(List<RoadGeometry.Sample> out, RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V left, double width) {
      if (!(a.distance(b) < 1.0E-7)) {
         int n = (int)Math.ceil(a.distance(b) / 0.5);

         for (int i = 0; i <= n; i++) {
            if (i != 0 || out.isEmpty()) {
               out.add(new RoadGeometry.Sample(a.add(b.sub(a).mul((double)i / (double)n)), left, 0.0, width));
            }
         }
      }
   }

   private static boolean sharedEnd(RoadGeometry.Mesh a, RoadGeometry.Mesh b, RoadClearance.DetailedContact c) {
      if (Math.max(Math.abs(c.minimumDifference()), Math.abs(c.maximumDifference())) > 1.0E-5) {
         return false;
      } else {
         for (RoadGeometry.Sample x : List.of(a.first(), a.last())) {
            for (RoadGeometry.Sample y : List.of(b.first(), b.last())) {
               if (x.center().distance(y.center()) < 1.0E-5
                  && Math.abs(x.left().dot(y.left())) > 0.99999
                  && Math.min(c.contact().from(), a.length() - c.contact().to()) < 0.01
                  && Math.min(c.otherFrom(), b.length() - c.otherTo()) < 0.01) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   private static RoadGeometry.Mesh connector(
      RoadGeometry.V a, RoadGeometry.V b, RoadGeometry.V from, RoadGeometry.V to, RoadGeometry.Settings settings, InterchangePlanner.Options o
   ) {
      double handle = a.sub(b).horizontalLength() * 0.55;
      RoadGeometry.V p = a.add(from.mul(handle));
      RoadGeometry.V q = b.sub(to.mul(handle));
      int n = Math.max(16, (int)Math.ceil((a.distance(p) + p.distance(q) + q.distance(b)) / 0.5));
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

      for (int i = 0; i <= n; i++) {
         double t = (double)i / (double)n;
         double w = 1.0 - t;
         RoadGeometry.V at = a.mul(w * w * w).add(p.mul(3.0 * w * w * t)).add(q.mul(3.0 * w * t * t)).add(b.mul(t * t * t));
         double blend = t * t * t * (10.0 + t * (-15.0 + 6.0 * t));
         at = new RoadGeometry.V(at.x(), a.y() + (b.y() - a.y()) * blend, at.z());
         RoadGeometry.V d = p.sub(a).mul(3.0 * w * w).add(q.sub(p).mul(6.0 * w * t)).add(b.sub(q).mul(3.0 * t * t));
         samples.add(new RoadGeometry.Sample(at, d.horizontalUnit().left(), 0.0, settings.width() / 2.0));
      }

      RoadGeometry.Mesh mesh = RoadRibbon.mesh(samples, settings);
      RoadRibbon.checkSelfIntersections(mesh, o.clearance());
      if (RoadRibbon.minRadius(mesh) + 0.1 < o.radius()) {
         throw new IllegalArgumentException("双喇叭中央连接段半径不足，请扩大范围");
      } else {
         return mesh;
      }
   }

   private DoubleTrumpetPlanner() {
   }
}
