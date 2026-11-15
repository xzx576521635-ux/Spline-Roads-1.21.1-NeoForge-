package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public final class LanePointPreview {
   public static LanePointPreview.Image render(RoadGeometry.Mesh mesh, LanePoints.Point point, int width, int height, int selected) {
      double station = LanePoints.lane(mesh, point).station();
      RoadGeometry.Mesh raw = LaneSections.reference(mesh);
      RoadGeometry.Sample sample = RoadStructures.sample(raw, station);
      RoadGeometry.V origin = sample.center();
      RoadGeometry.V forward = sample.left().left().mul(-1.0);
      RoadGeometry.V right = forward.left();
      RoadProfile.Layout layout = RoadProfile.layout(raw, sample);
      double actualHalfWidth = sample.halfWidth();

      for (int slot : LaneAdditions.slots(mesh, station)) {
         LanePoints.Lane lane = LanePoints.lane(mesh, station, slot);
         actualHalfWidth = Math.max(actualHalfWidth, Math.abs(lane.position().sub(origin).dot(sample.left())) + lane.width() / 2.0);
      }

      RoadGeometry.Sample effective = RoadStructures.sample(mesh, station);
      actualHalfWidth = Math.max(actualHalfWidth, Math.abs(effective.center().sub(origin).dot(sample.left())) + effective.halfWidth());
      double halfWidth = actualHalfWidth + (double)(mesh.settings().options().sidewalk().enabled() ? mesh.settings().options().sidewalk().width() : 0) + 3.0;
      double halfLength = Math.max(5.0, halfWidth * (double)(height - 20) / (double)Math.max(1, width - 20));
      JunctionPreview.Painter painter = new JunctionPreview.Painter(
         new RoadGeometry.V(-halfWidth, 0.0, -halfLength), new RoadGeometry.V(halfWidth, 0.0, halfLength), width, height
      );
      JunctionPreview.Painter hit = new JunctionPreview.Painter(
         new RoadGeometry.V(-halfWidth, 0.0, -halfLength), new RoadGeometry.V(halfWidth, 0.0, halfLength), width, height
      );
      Arrays.fill(hit.pixels, -1);
      Arrays.fill(painter.pixels, -14141394);
      Function<RoadGeometry.V, RoadGeometry.V> project = v -> {
         RoadGeometry.V d = v.sub(origin);
         return new RoadGeometry.V(d.dot(right), v.y(), -d.dot(forward));
      };
      double window = Math.hypot(halfWidth, halfLength) + 6.0;
      double lo = Math.max(0.0, station - window);
      double hi = Math.min(mesh.length(), station + window);
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
      samples.add(RoadStructures.sample(mesh, lo));

      for (RoadGeometry.Sample s : mesh.samples()) {
         if (s.distance() > lo && s.distance() < hi) {
            samples.add(s);
         }
      }

      samples.add(RoadStructures.sample(mesh, hi));
      RoadGeometry.Mesh visible = new RoadGeometry.Mesh(
         samples, mesh.settings(), mesh.min(), mesh.max(), mesh.length(), false, mesh.controlPoint(), mesh.controls(), mesh.reference()
      );

      for (RoadStructures.Part part : RoadSidewalks.parts(visible, mesh.settings().options().sidewalk())) {
         painter.polygon(part.base().stream().map(project).toList(), part.material() == RoadStructures.Material.TACTILE ? -2374583 : -7169383);
      }

      RoadSurface.Geometry surface = RoadSurface.build(visible, List.of(), List.of());

      for (RoadSurface.Face face : surface.pavement()) {
         painter.polygon(face.points().stream().map(project).toList(), 0xFF000000 | face.color());
      }

      for (int lane : LaneAdditions.slots(mesh, station)) {
         for (int i = 1; i < samples.size(); i++) {
            LanePoints.Lane a = LanePoints.lane(mesh, samples.get(i - 1).distance(), lane);
            LanePoints.Lane b = LanePoints.lane(mesh, samples.get(i).distance(), lane);
            double fa = LaneAdditions.isAdded(mesh, lane) ? LaneAdditions.find(mesh, lane).fraction(a.station()) : 1.0;
            double fb = LaneAdditions.isAdded(mesh, lane) ? LaneAdditions.find(mesh, lane).fraction(b.station()) : 1.0;
            if (!(Math.max(fa, fb) < 1.0E-6)) {
               RoadGeometry.V al = samples.get(i - 1).left().mul(a.width() * fa / 2.0);
               RoadGeometry.V bl = samples.get(i).left().mul(b.width() * fb / 2.0);
               List<RoadGeometry.V> quad = List.of(a.position().sub(al), a.position().add(al), b.position().add(bl), b.position().sub(bl))
                  .stream()
                  .map(project)
                  .toList();
               if (!LaneAdditions.isAdded(mesh, lane) || LaneAdditions.find(mesh, lane).fraction(station) > 0.999999 && Math.min(fa, fb) > 0.999999) {
                  hit.polygon(quad, lane);
               }

               if (lane == selected) {
                  painter.polygon(quad, -13216149);
               } else if (!LaneSections.active(mesh, (samples.get(i - 1).distance() + samples.get(i).distance()) / 2.0, lane)) {
                  painter.polygon(quad, -12236735);
               }
            }
         }
      }

      for (RoadSurface.Face face : surface.markings()) {
         painter.marking(face.points().stream().map(project).toList(), 0xFF000000 | face.color());
      }

      ArrayList<LanePointPreview.Marker> markers = new ArrayList<>();

      for (int lane : LaneAdditions.slots(mesh, station)) {
         if (!LaneAdditions.isAdded(mesh, lane) || !(LaneAdditions.find(mesh, lane).fraction(station) < 0.999999)) {
            LanePoints.Lane lanePoint = LanePoints.lane(mesh, station, lane);
            RoadGeometry.V center = project.apply(lanePoint.position());
            RoadGeometry.V direction = lanePoint.direction();
            RoadGeometry.V arrow = lanePoint.position().add(direction.mul(3.0));
            RoadGeometry.V tip = arrow.add(direction.mul(1.4));
            painter.line(project.apply(arrow.sub(direction)), project.apply(tip), -724513);
            painter.line(project.apply(tip), project.apply(tip.sub(direction).add(direction.left().mul(0.6))), -724513);
            painter.line(project.apply(tip), project.apply(tip.sub(direction).sub(direction.left().mul(0.6))), -724513);
            RoadGeometry.V end = project.apply(lanePoint.position().add(direction));
            markers.add(
               new LanePointPreview.Marker(lane, painter.x(center), painter.y(center), painter.x(end) - painter.x(center), painter.y(end) - painter.y(center))
            );
         }
      }

      return new LanePointPreview.Image(width, height, painter.pixels, hit.pixels, List.copyOf(markers));
   }

   private LanePointPreview() {
   }

   public static record Image(int width, int height, int[] pixels, int[] lanes, List<LanePointPreview.Marker> markers) {
      public int laneAt(int x, int y) {
         return x >= 0 && y >= 0 && x < this.width && y < this.height ? this.lanes[y * this.width + x] : -1;
      }
   }

   public static record Marker(int lane, int x, int y, int dx, int dy) {
   }
}
