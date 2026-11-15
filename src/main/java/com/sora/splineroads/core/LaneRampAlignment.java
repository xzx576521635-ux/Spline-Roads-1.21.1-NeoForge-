package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LaneRampAlignment {
   private static double motorWidth(RoadGeometry.Settings settings) {
      RoadProfile.Layout p = RoadProfile.layout(settings, settings.width());
      return p.motorMax() - p.motorMin();
   }

   public static RoadGeometry.Settings usableWidth(RoadGeometry.Settings settings, double drive) {
      drive = Math.max(4.0, drive);
      RoadProfile.Options o = settings.options();
      RoadTransitions.Port port = new RoadTransitions.Port(-drive / 2.0, drive / 2.0, 0.0, List.of(), 0.0, 0.0);
      return new RoadGeometry.Settings(
         settings.mode(),
         settings.style(),
         drive + 1.5,
         settings.thickness(),
         settings.tension(),
         settings.arcDegrees(),
         o.ends().start() == null ? drive + 1.5 : settings.startWidth(),
         o.ends().end() == null ? drive + 1.5 : settings.endWidth(),
         settings.structure(),
         settings.taperVersion(),
         settings.rampTurn(),
         o.ends(o.ends().port(port))
      );
   }

   public static RoadGeometry.Mesh fit(RoadGeometry.Mesh path, double startLane, double endLane, double transition) {
      RoadProfile.Layout profile = RoadProfile.layout(path.settings(), path.settings().width());
      double lane = profile.motorMax() - profile.motorMin();
      double margin = path.settings().width() - lane;
      double span = Math.max(0.01, Math.min(Math.max(16.0, transition), path.length() / 2.0));
      ArrayList<RoadGeometry.Sample> out = new ArrayList<>();

      for (RoadGeometry.Sample s : path.samples()) {
         double a = 1.0 - RoadGeometry.Settings.smooth(Math.min(1.0, s.distance() / span));
         double b = 1.0 - RoadGeometry.Settings.smooth(Math.min(1.0, (path.length() - s.distance()) / span));
         double width = lane + (startLane - lane) * a + (endLane - lane) * b + margin;
         RoadGeometry.V center = s.center().sub(s.left().mul(profile.motorCenter()));
         out.add(new RoadGeometry.Sample(center, s.left(), s.distance(), width / 2.0));
      }

      return RoadRibbon.mesh(out, path.settings());
   }

   public static LaneRampAlignment.Mouth mouth(RoadGeometry.Mesh host, LanePoints.Lane lane) {
      RoadGeometry.Sample at = RoadStructures.sample(host, lane.station());
      RoadProfile.Layout layout = RoadProfile.layout(host, at);
      double center = lane.position().sub(at.center()).dot(at.left());
      double low = Math.abs(center - lane.width() / 2.0 - layout.motorMin()) < 0.01 ? Math.max(0.0, at.halfWidth() + layout.motorMin()) : 0.0;
      double high = Math.abs(center + lane.width() / 2.0 - layout.motorMax()) < 0.01 ? Math.max(0.0, at.halfWidth() - layout.motorMax()) : 0.0;
      low = low > 1.0E-6 ? 0.75 : 0.0;
      high = high > 1.0E-6 ? 0.75 : 0.0;
      return lane.direction().dot(at.left().left().mul(-1.0)) > 0.0
         ? new LaneRampAlignment.Mouth(lane.width(), low, high)
         : new LaneRampAlignment.Mouth(lane.width(), high, low);
   }

   private static RoadTransitions.Section section(RoadGeometry.Settings settings, LaneRampAlignment.Mouth mouth) {
      double shift = (mouth.high() - mouth.low()) / 2.0;
      double width = mouth.lane() + mouth.low() + mouth.high();
      return RoadTransitions.Section.of(
            new RoadGeometry.Settings(settings.mode(), settings.style(), width, settings.thickness(), settings.tension(), settings.arcDegrees())
               .options(settings.options())
         )
         .port(new RoadTransitions.Port(-mouth.lane() / 2.0 - shift, mouth.lane() / 2.0 - shift, 0.0, List.of(), 0.0, 0.0));
   }

   public static RoadGeometry.Mesh fit(RoadGeometry.Mesh path, LaneRampAlignment.Mouth from, LaneRampAlignment.Mouth to) {
      return fit(path, from, to, List.of(), List.of());
   }

   public static RoadGeometry.Mesh fit(
      RoadGeometry.Mesh path,
      LaneRampAlignment.Mouth from,
      LaneRampAlignment.Mouth to,
      List<RoadGeometry.Mesh> sourceGuides,
      List<RoadGeometry.Mesh> targetGuides
   ) {
      RoadGeometry.Settings clean = path.settings()
         .options(
            path.settings()
               .options()
               .lanePoints(path.settings().options().lanePoints().bands(List.of()))
               .ends(RoadTransitions.Ends.NONE.port(path.settings().options().ends().port()))
         );
      RoadTransitions.Section a = section(clean, from);
      RoadTransitions.Section b = section(clean, to);
      RoadGeometry.Settings settings = RoadTransitions.ends(clean, a, b);
      RoadGeometry.Mesh fitted = path;
      double[] sourceFree = outsideProgress(path, from, true, sourceGuides);
      double[] targetFree = outsideProgress(path, to, false, targetGuides);
      double shoulder = Math.max(0.0, (settings.width() - motorWidth(settings)) / 2.0);
      LanePoints.Link link = path.settings().options().lanePoints().link();
      double transition = link == null ? 32.0 : link.options().transition();
      double auxiliary = Math.max(64.0, transition * 2.0) + LaneRampApproach.parallel(transition) + Math.max(8.0, settings.width());

      for (int pass = 0; pass < 12; pass++) {
         double span = RoadTransitions.span(settings, fitted.length());
         ArrayList<RoadGeometry.Sample> out = new ArrayList<>();

         for (int i = 0; i < path.samples().size(); i++) {
            RoadGeometry.Sample p = path.samples().get(i);
            double distance = fitted.samples().get(i).distance();
            double wa = 1.0 - RoadGeometry.Settings.smooth(Math.min(1.0, distance / span));
            double wb = 1.0 - RoadGeometry.Settings.smooth(Math.min(1.0, (fitted.length() - distance) / span));
            double shift = ((from.high() - from.low()) * wa + (to.high() - to.low()) * wb) / 2.0;
            double bias = Math.signum(from.high() - from.low()) * (1.0 - sourceFree[i]) + Math.signum(to.high() - to.low()) * (1.0 - targetFree[i]);
            shift += shoulder * (1.0 - wa) * (1.0 - wb) * Math.max(-1.0, Math.min(1.0, bias));
            double width = settings.width() + (a.width() - settings.width()) * wa + (b.width() - settings.width()) * wb;
            if (from.internal()) {
               width = Math.min(width, from.lane() + 2.0 * shoulder * sourceFree[i]);
            }

            if (to.internal()) {
               width = Math.min(width, to.lane() + 2.0 * shoulder * targetFree[i]);
            }

            if (!from.auxiliary() && !from.internal()) {
               double edge = Math.signum(from.high() - from.low()) * (width - from.lane()) / 2.0;
               shift = edge * (1.0 - sourceFree[i]) + shift * sourceFree[i];
            }

            if (!to.auxiliary() && !to.internal()) {
               double edge = Math.signum(to.high() - to.low()) * (width - to.lane()) / 2.0;
               shift = edge * (1.0 - targetFree[i]) + shift * targetFree[i];
            }

            if (from.auxiliary() && distance <= auxiliary || to.auxiliary() && fitted.length() - distance <= auxiliary) {
               width = p.halfWidth() * 2.0;
               LaneRampAlignment.Mouth mouth = !from.auxiliary() || !(distance <= auxiliary) || to.auxiliary() && !(distance <= fitted.length() / 2.0)
                  ? to
                  : from;
               if (mouth.auxiliary()) {
                  shift = auxiliaryShift(width, mouth, settings.width());
               }
            }

            out.add(new RoadGeometry.Sample(p.center().add(p.left().mul(shift)), p.left(), p.distance(), width / 2.0));
         }

         smoothWidths(out);
         RoadGeometry.Mesh next = RoadRibbon.mesh(out, settings);
         double movement = 0.0;

         for (int i = 0; i < out.size(); i++) {
            movement = Math.max(movement, next.samples().get(i).center().distance(fitted.samples().get(i).center()));
         }

         fitted = next;
         if (movement < 1.0E-9) {
            break;
         }
      }

      return painted(fitted, from, to);
   }

   private static void smoothWidths(List<RoadGeometry.Sample> samples) {
      for (int direction : new int[]{1, -1}) {
         for (int i = direction > 0 ? 1 : samples.size() - 2; i >= 0 && i < samples.size(); i += direction) {
            RoadGeometry.Sample p = samples.get(i - direction);
            RoadGeometry.Sample q = samples.get(i);
            double run = q.center().sub(p.center()).horizontalLength();
            double half = Math.min(q.halfWidth(), p.halfWidth() + 0.025 * run);
            if (half < q.halfWidth()) {
               samples.set(i, new RoadGeometry.Sample(q.center(), q.left(), q.distance(), half));
            }
         }
      }
   }

   public static double auxiliaryShift(double width, LaneRampAlignment.Mouth mouth, double full) {
      double narrow = mouth.lane() + mouth.low() + mouth.high();
      double grow = full > narrow + 1.0E-8 ? Math.max(0.0, Math.min(1.0, (width - narrow) / (full - narrow))) : 1.0;
      return (mouth.high() - mouth.low()) * (1.0 - grow) / 2.0;
   }

   private static LanePoints.Band band(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample, LaneRampAlignment.Mouth from, LaneRampAlignment.Mouth to) {
      boolean first = sample.distance() <= mesh.length() / 2.0;
      LaneRampAlignment.Mouth mouth = first ? from : to;
      LanePoints.Link link = mesh.settings().options().lanePoints().link();
      double transition = link == null ? 32.0 : link.options().transition();
      double auxiliary = Math.max(64.0, transition * 2.0) + LaneRampApproach.parallel(transition) + Math.max(8.0, mesh.settings().width());
      if (!from.auxiliary() || !(sample.distance() <= auxiliary) || to.auxiliary() && !first) {
         if (to.auxiliary() && mesh.length() - sample.distance() <= auxiliary) {
            mouth = to;
            first = false;
         }
      } else {
         mouth = from;
         first = true;
      }

      double distance = first ? sample.distance() : mesh.length() - sample.distance();
      double full = mesh.settings().width();
      double narrow = mouth.lane() + mouth.low() + mouth.high();
      double shoulder = Math.max(0.0, (full - motorWidth(mesh.settings())) / 2.0);
      double grow = full - narrow > 1.0E-6
         ? Math.max(0.0, Math.min(1.0, (sample.halfWidth() * 2.0 - narrow) / (full - narrow)))
         : RoadGeometry.Settings.smooth(Math.min(1.0, distance / RoadTransitions.span(mesh.settings(), mesh.length())));
      double low = mouth.low() + (shoulder - mouth.low()) * grow;
      double high = mouth.high() + (shoulder - mouth.high()) * grow;
      return new LanePoints.Band(sample.distance(), -sample.halfWidth() + low, sample.halfWidth() - high);
   }

   private static RoadGeometry.Mesh painted(RoadGeometry.Mesh mesh, LaneRampAlignment.Mouth from, LaneRampAlignment.Mouth to) {
      List<LanePoints.Band> bands = mesh.samples().stream().map(s -> band(mesh, s, from, to)).toList();
      RoadGeometry.Settings settings = mesh.settings().options(mesh.settings().options().lanePoints(mesh.settings().options().lanePoints().bands(bands)));
      return new RoadGeometry.Mesh(
         mesh.samples(), settings, mesh.min(), mesh.max(), mesh.length(), mesh.closed(), mesh.controlPoint(), mesh.controls(), mesh.reference()
      );
   }

   private static double[] outsideProgress(RoadGeometry.Mesh path, LaneRampAlignment.Mouth mouth, boolean first) {
      return outsideProgress(path, mouth, first, List.of());
   }

   private static double[] outsideProgress(RoadGeometry.Mesh path, LaneRampAlignment.Mouth mouth, boolean first, List<RoadGeometry.Mesh> guides) {
      int n = path.samples().size();
      RoadGeometry.Sample end = first ? path.first() : path.last();
      double side = Math.signum(mouth.high() - mouth.low());
      double progress = 0.0;
      double[] result = new double[n];

      for (int i = first ? 0 : n - 1; i >= 0 && i < n; i += first ? 1 : -1) {
         RoadGeometry.V delta = path.samples().get(i).center().sub(end.center());
         double lateral = delta.dot(end.left());
         if (!guides.isEmpty()) {
            RoadGeometry.V p = path.samples().get(i).center();
            RoadQueries.Projection near = guides.stream()
               .map(g -> RoadQueries.horizontal(g, p))
               .min(Comparator.comparingDouble(RoadQueries.Projection::horizontalDistance))
               .orElseThrow();
            lateral = near.horizontalDistance();
            delta = new RoadGeometry.V(delta.x(), p.y() - near.sample().center().y(), delta.z());
         }

         progress = Math.max(progress, side == 0.0 ? Math.abs(lateral) - 0.5 : Math.abs(lateral));
         double lateralFree = RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, progress / Math.max(1.0, path.settings().width()))));
         double verticalFree = RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, (Math.abs(delta.y()) - 4.25) / 1.25)));
         LanePoints.Link link = path.settings().options().lanePoints().link();
         if (!first && !guides.isEmpty() && link != null && link.closesTarget()) {
            double reserve = Math.max(96.0, link.options().transition() * 4.0);
            double remaining = path.length() - path.samples().get(i).distance();
            verticalFree *= RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, (remaining - reserve) / 32.0)));
         }

         result[i] = mouth.auxiliary() ? 1.0 : Math.max(lateralFree, verticalFree);
      }

      return result;
   }

   public static boolean matches(RoadGeometry.Mesh mesh, LaneRampAlignment.Mouth from, LaneRampAlignment.Mouth to) {
      if (mesh.settings().style().connectorRamp() && mesh.settings().options().lanePoints().bands().isEmpty()) {
         return false;
      } else {
         for (boolean first : new boolean[]{true, false}) {
            RoadGeometry.Sample s = first ? mesh.first() : mesh.last();
            LaneRampAlignment.Mouth mouth = first ? from : to;
            RoadProfile.Layout layout = RoadProfile.layout(mesh, s);
            RoadGeometry.V center = motorAxis(mesh, s).add(s.left().mul((mouth.high() - mouth.low()) / 2.0));
            if (Math.abs(layout.motorMax() - layout.motorMin() - mouth.lane()) > 1.0E-6
               || center.distance(s.center()) > 1.0E-6
               || Math.abs(s.halfWidth() * 2.0 - mouth.lane() - mouth.low() - mouth.high()) > 1.0E-6) {
               return false;
            }
         }

         for (RoadGeometry.Sample sample : mesh.samples()) {
            LanePoints.Band expected = band(mesh, sample, from, to);
            RoadProfile.Layout actual = RoadProfile.layout(mesh, sample);
            if (Math.abs(actual.motorMin() - expected.low()) > 0.003 || Math.abs(actual.motorMax() - expected.high()) > 0.003) {
               return false;
            }
         }

         return true;
      }
   }

   public static RoadGeometry.Mesh refit(RoadGeometry.Mesh mesh, LaneRampAlignment.Mouth from, LaneRampAlignment.Mouth to) {
      if (!mesh.settings().options().lanePoints().bands().isEmpty()
         && Math.abs(mesh.first().halfWidth() * 2.0 - from.lane() - from.low() - from.high()) < 1.0E-6
         && Math.abs(mesh.last().halfWidth() * 2.0 - to.lane() - to.low() - to.high()) < 1.0E-6) {
         return painted(mesh, from, to);
      } else {
         ArrayList<RoadGeometry.Sample> axis = new ArrayList<>();

         for (RoadGeometry.Sample s : mesh.samples()) {
            axis.add(new RoadGeometry.Sample(motorAxis(mesh, s), s.left(), s.distance(), s.halfWidth()));
         }

         if (mesh.settings().options().lanePoints().bands().isEmpty()) {
            double shoulder = Math.max(0.0, (mesh.settings().width() - motorWidth(mesh.settings())) / 2.0);
            double span = RoadTransitions.span(mesh.settings(), mesh.length());

            for (int pass = 0; pass < 12; pass++) {
               RoadGeometry.Mesh path = RoadRibbon.mesh(axis, mesh.settings());
               double[] source = outsideProgress(path, from, true);
               double[] target = outsideProgress(path, to, false);
               ArrayList<RoadGeometry.Sample> next = new ArrayList<>();

               for (int i = 0; i < mesh.samples().size(); i++) {
                  RoadGeometry.Sample s = mesh.samples().get(i);
                  double wa = 1.0 - RoadGeometry.Settings.smooth(Math.min(1.0, s.distance() / span));
                  double wb = 1.0 - RoadGeometry.Settings.smooth(Math.min(1.0, (mesh.length() - s.distance()) / span));
                  double bias = Math.signum(from.high() - from.low()) * (1.0 - source[i]) + Math.signum(to.high() - to.low()) * (1.0 - target[i]);
                  RoadGeometry.V center = motorAxis(mesh, s).sub(s.left().mul(shoulder * (1.0 - wa) * (1.0 - wb) * Math.max(-1.0, Math.min(1.0, bias))));
                  next.add(new RoadGeometry.Sample(center, s.left(), s.distance(), s.halfWidth()));
               }

               axis = next;
            }
         }

         return fit(RoadRibbon.mesh(axis, mesh.settings()), from, to);
      }
   }

   private static RoadGeometry.V motorAxis(RoadGeometry.Mesh mesh, RoadGeometry.Sample sample) {
      return sample.at(RoadProfile.layout(mesh, sample).motorCenter(), 0.0);
   }

   public static RoadGeometry.V axis(RoadGeometry.Mesh mesh, boolean first) {
      return motorAxis(mesh, first ? mesh.first() : mesh.last());
   }

   private LaneRampAlignment() {
   }

   public static record Mouth(double lane, double low, double high, boolean auxiliary) {
      public Mouth(double lane, double low, double high) {
         this(lane, low, high, false);
      }

      public static LaneRampAlignment.Mouth extra(double lane) {
         return new LaneRampAlignment.Mouth(lane, 0.0, 0.0, true);
      }

      public static LaneRampAlignment.Mouth extra(double lane, int outside) {
         return new LaneRampAlignment.Mouth(lane, outside < 0 ? 0.75 : 0.0, outside > 0 ? 0.75 : 0.0, true);
      }

      boolean internal() {
         return !this.auxiliary && this.low < 1.0E-6 && this.high < 1.0E-6;
      }
   }
}
