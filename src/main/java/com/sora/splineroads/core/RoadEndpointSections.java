package com.sora.splineroads.core;

import java.util.List;

public final class RoadEndpointSections {
   public static boolean changed(RoadGeometry.Mesh mesh, boolean first) {
      double station = first ? 0.0 : mesh.length();
      return mesh.settings().options().lanePoints().additions().stream().anyMatch(a -> a.fraction(station) > 1.0E-6)
         || mesh.settings().options().lanePoints().cuts().stream().anyMatch(c -> !c.temporary() && c.removed(station) > 1.0E-6);
   }

   public static RoadGeometry.Settings section(RoadGeometry.Mesh mesh, boolean first, boolean reversed) {
      return sectionAt(mesh, first ? mesh.first() : mesh.last(), reversed);
   }

   public static RoadGeometry.Settings sectionAt(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, boolean reversed) {
      RoadGeometry.Settings base = mesh.settings();
      RoadProfile.Layout l = RoadProfile.layout(mesh, at);
      RoadProfile.Catalog c = l.catalog();
      if (c.type() != RoadProfile.Type.ORDINARY && c.type() != RoadProfile.Type.HIGHWAY) {
         throw new IllegalArgumentException("此接点不是普通道路或高速的通行断面");
      } else {
         int low = c.twoWay() ? l.lanesOnSide(-1) : c.lanes();
         int high = c.twoWay() ? l.lanesOnSide(1) : 0;
         RoadLanes.Counts counts = c.twoWay() ? new RoadLanes.Counts(l.outside() > 0 ? high : low, l.outside() > 0 ? low : high) : new RoadLanes.Counts(low, 0);
         if (reversed) {
            counts = counts.mirrored();
         }

         if (!c.twoWay() || low >= 1 && high >= 1) {
            for (LaneSections.Cut cut : base.options().lanePoints().cuts()) {
               if (!cut.temporary()) {
                  double r = cut.removed(at.distance());
                  if (r > 1.0E-6 && r < 0.999999) {
                     throw new IllegalArgumentException("接点仍在车道合并渐变段中，请将合并过渡留在端点前完成");
                  }
               }
            }

            for (LaneAdditions.Addition added : base.options().lanePoints().additions()) {
               double f = added.fraction(at.distance());
               if (f > 1.0E-6 && f < 0.999999) {
                  throw new IllegalArgumentException("接点仍在新增车道渐变段中，请在完整断面处续接");
               }
            }

            double sign = reversed ? -1.0 : 1.0;
            List<Double> dividers = l.dividers()
               .stream()
               .filter(d -> d > l.motorMin() + 0.12 && d < l.motorMax() - 0.12)
               .map(d -> sign * d)
               .sorted()
               .distinct()
               .toList();
            RoadTransitions.Port port = new RoadTransitions.Port(
               reversed ? -l.motorMax() : l.motorMin(),
               reversed ? -l.motorMin() : l.motorMax(),
               l.median(),
               dividers,
               l.curbWidth(),
               l.curbWidth(),
               sign * l.medianCenter()
            );
            RoadProfile.Options options = base.options()
               .lanePoints(LanePoints.Data.EMPTY)
               .attachments(RoadAttachments.Data.EMPTY)
               .laneLines(List.of())
               .streetscape(base.options().streetscape().raisedSpans(List.of()))
               .lanes(counts)
               .ends(RoadTransitions.Ends.NONE.port(port))
               .route(base.options().routing().fit(false));
            RoadGeometry.Style style = RoadLanes.carrier(c.type(), counts, c.median());
            RoadGeometry.Settings result = new RoadGeometry.Settings(
                  RoadGeometry.Mode.STRAIGHT, style, at.halfWidth() * 2.0, base.thickness(), base.tension(), base.arcDegrees()
               )
               .structure(base.structure())
               .options(options);
            result.validate();
            return result;
         } else {
            throw new IllegalArgumentException("该端点有一个方向已无开放通行车道，不能作为普通双向续接端口");
         }
      }
   }

   public static RoadGeometry.Settings orient(RoadGeometry.Settings s, boolean reverse) {
      if (!reverse) {
         return s;
      } else {
         RoadProfile.Options o = s.options();
         RoadLanes.Counts n = RoadLanes.counts(s);
         RoadTransitions.Port p = o.ends().port();
         if (n.twoWay() && o.lanes().explicit()) {
            o = o.lanes(n.mirrored());
         }
         o = o.outerRail(switch (o.outerRail()) {
            case SOUND_LEFT -> RoadProfile.OuterRail.SOUND_RIGHT;
            case SOUND_RIGHT -> RoadProfile.OuterRail.SOUND_LEFT;
            default -> o.outerRail();
         });
         RoadSidewalks.Config walk = o.sidewalk();

         RoadSidewalks.Side side = switch (walk.side()) {
            case LEFT -> RoadSidewalks.Side.RIGHT;
            case RIGHT -> RoadSidewalks.Side.LEFT;
            default -> walk.side();
         };
         o = o.sidewalk(new RoadSidewalks.Config(walk.enabled(), side, walk.width(), walk.material(), walk.smooth(), walk.tactile()));
         if (p != null) {
            o = o.ends(
               o.ends()
                  .port(
                     new RoadTransitions.Port(
                        -p.motorMax(),
                        -p.motorMin(),
                        p.median(),
                        p.dividers().stream().map(d -> -d).sorted().toList(),
                        p.curbRight(),
                        p.curbLeft(),
                        -p.medianCenter()
                     )
                  )
            );
         }

         return s.options(o);
      }
   }

   public static RoadGeometry.Settings inherit(RoadGeometry.Settings requested, RoadGeometry.Settings port) {
      return new RoadGeometry.Settings(requested.mode(), port.style(), port.width(), requested.thickness(), requested.tension(), requested.arcDegrees())
         .structure(requested.structure())
         .options(
            port.options()
               .infrastructure(requested.options().infrastructure())
               .lanePoints(LanePoints.Data.EMPTY)
               .attachments(RoadAttachments.Data.EMPTY)
               .lift(0.5, 0.0)
         );
   }

   private RoadEndpointSections() {
   }
}
