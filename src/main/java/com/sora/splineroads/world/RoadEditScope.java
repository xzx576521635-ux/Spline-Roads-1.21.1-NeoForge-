package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadBoundsIndex;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadTransitions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class RoadEditScope {
   static RoadBoundsIndex.Bounds bounds(RoadGeometry.Mesh mesh) {
      return new RoadBoundsIndex.Bounds(mesh.min().x(), mesh.min().z(), mesh.max().x(), mesh.max().z());
   }

   static RoadBoundsIndex.Bounds bounds(List<RoadStructures.Part> parts) {
      double minX = Double.POSITIVE_INFINITY;
      double minZ = minX;
      double maxX = Double.NEGATIVE_INFINITY;
      double maxZ = maxX;

      for (RoadStructures.Part part : parts) {
         double pad = part.halfExtent();
         minX = Math.min(minX, Math.min(part.a().x(), part.b().x()) - pad);
         minZ = Math.min(minZ, Math.min(part.a().z(), part.b().z()) - pad);
         maxX = Math.max(maxX, Math.max(part.a().x(), part.b().x()) + pad);
         maxZ = Math.max(maxZ, Math.max(part.a().z(), part.b().z()) + pad);
      }

      return new RoadBoundsIndex.Bounds(minX, minZ, maxX, maxZ);
   }

   static boolean unchanged(RoadIndex.Built before, RoadIndex.Built after) {
      return before != null && before.record.structures(List.of()).equals(after.record.structures(List.of())) && before.mesh.equals(after.mesh);
   }

   private static RoadTransitions.Section withoutRail(RoadTransitions.Section s) {
      return s != null && s.outerRail() != RoadProfile.OuterRail.OFF
         ? new RoadTransitions.Section(
            s.style(),
            s.width(),
            s.cycle(),
            s.cycleRail(),
            s.curb(),
            RoadProfile.OuterRail.OFF,
            s.sidewalk(),
            s.cycleAsphalt(),
            s.port(),
            s.streetscape(),
            s.lanes()
         )
         : s;
   }

   private static RoadGeometry.Settings withoutEndRails(RoadGeometry.Settings s) {
      RoadTransitions.Ends e = s.options().ends();
      return s.options(
         s.options()
            .ends(
               new RoadTransitions.Ends(
                  withoutRail(e.start()),
                  withoutRail(e.end()),
                  e.persistent(),
                  e.trimmedStart(),
                  e.trimmedEnd(),
                  e.port(),
                  e.paintPhase(),
                  e.startHold(),
                  e.endHold()
               )
            )
      );
   }

   private static boolean sameRibbon(RoadGeometry.Mesh a, RoadGeometry.Mesh b) {
      if (a == b) {
         return true;
      } else {
         return a != null && b != null
            ? a.samples().equals(b.samples())
               && a.min().equals(b.min())
               && a.max().equals(b.max())
               && a.length() == b.length()
               && a.closed() == b.closed()
               && Objects.equals(a.controlPoint(), b.controlPoint())
               && a.controls().equals(b.controls())
               && Objects.equals(a.slicing(), b.slicing())
               && withoutEndRails(a.settings()).equals(withoutEndRails(b.settings()))
               && sameRibbon(a.reference(), b.reference())
            : false;
      }
   }

   private static RoadBoundsIndex.Bounds range(RoadGeometry.Mesh mesh, double from, double to) {
      double minX = Double.POSITIVE_INFINITY;
      double minZ = minX;
      double maxX = Double.NEGATIVE_INFINITY;
      double maxZ = maxX;
      ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();
      samples.add(RoadStructures.sample(mesh, from));
      samples.add(RoadStructures.sample(mesh, to));

      for (RoadGeometry.Sample sample : mesh.samples()) {
         if (sample.distance() > from && sample.distance() < to) {
            samples.add(sample);
         }
      }

      double verge = mesh.settings().options().sidewalk().enabled() ? (double)mesh.settings().options().sidewalk().width() : 0.0;

      for (RoadGeometry.Sample samplex : samples) {
         double pad = samplex.halfWidth() + verge + 2.0;
         minX = Math.min(minX, samplex.center().x() - pad);
         minZ = Math.min(minZ, samplex.center().z() - pad);
         maxX = Math.max(maxX, samplex.center().x() + pad);
         maxZ = Math.max(maxZ, samplex.center().z() + pad);
      }

      return new RoadBoundsIndex.Bounds(minX, minZ, maxX, maxZ);
   }

   static List<RoadBoundsIndex.Bounds> endpointRails(RoadIndex.Built before, RoadIndex.Built after) {
      if (before == null || after == null) {
         return null;
      } else if (unchanged(before, after)) {
         return List.of();
      } else if (withoutEndRails(before.record.settings()).equals(withoutEndRails(after.record.settings()))
         && before.record.structures(List.of()).equals(after.record.settings(before.record.settings()).structures(List.of()))
         && sameRibbon(before.mesh, after.mesh)) {
         RoadTransitions.Ends a = before.mesh.settings().options().ends();
         RoadTransitions.Ends b = after.mesh.settings().options().ends();
         double length = before.mesh.length() + a.trimmedStart() + a.trimmedEnd();
         double oldSpan = RoadTransitions.span(before.mesh.settings(), length);
         double newSpan = RoadTransitions.span(after.mesh.settings(), length);
         double span = Math.max(oldSpan, newSpan);
         boolean first = !Objects.equals(a.start(), b.start()) || oldSpan != newSpan && (a.start() != null || b.start() != null);
         boolean last = !Objects.equals(a.end(), b.end()) || oldSpan != newSpan && (a.end() != null || b.end() != null);
         ArrayList<RoadBoundsIndex.Bounds> result = new ArrayList<>();
         if (first) {
            double end = Math.min(before.mesh.length(), Math.max(0.0, span - a.trimmedStart()));
            result.add(range(before.mesh, 0.0, end));
            result.add(range(after.mesh, 0.0, end));
         }

         if (last) {
            double start = Math.max(0.0, Math.min(before.mesh.length(), before.mesh.length() + a.trimmedEnd() - span));
            result.add(range(before.mesh, start, before.mesh.length()));
            result.add(range(after.mesh, start, after.mesh.length()));
         }

         return List.copyOf(result);
      } else {
         return null;
      }
   }

   static boolean affects(List<RoadBoundsIndex.Bounds> local, RoadIndex.Built dependency) {
      if (local.isEmpty()) {
         return false;
      } else {
         RoadBoundsIndex.Bounds deck = bounds(dependency.mesh);
         RoadBoundsIndex.Bounds fixtures = dependency.record.structures().isEmpty() ? null : bounds(dependency.record.structures());
         return local.stream().anyMatch(area -> area.overlaps(deck, 82.0) || fixtures != null && area.overlaps(fixtures, 82.0));
      }
   }

   private RoadEditScope() {
   }
}
