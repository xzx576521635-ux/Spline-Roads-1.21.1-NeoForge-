package com.sora.splineroads.world;

import com.sora.splineroads.core.OneSidedJunction;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

final class AttachedPoints {
   static void reconcile(RoadData data, List<RoadIndex.Built> next, Set<UUID> removed, boolean deleting, Set<UUID> deletedPoints) {
      Map<UUID, RoadAttachments.Point> authored = new LinkedHashMap<>();
      Map<UUID, UUID> owners = new HashMap<>();

      for (RoadIndex.Built b : next) {
         if (b.record.junction() == null || OneSidedJunction.retainedPiece(b.record.junction())) {
            for (RoadAttachments.Point p : b.record.settings().options().attachments().points()) {
               if (authored.putIfAbsent(p.id(), p) != null) {
                  throw new IllegalArgumentException("附属点存在重复归属");
               }

               owners.put(p.id(), b.record.id());
            }
         }
      }

      if (!deleting) {
         for (UUID id : removed) {
            RoadIndex.Built old = data.index.roads.get(id);
            if (old != null && (old.record.junction() == null || OneSidedJunction.retainedPiece(old.record.junction()))) {
               for (RoadAttachments.Point p : old.record.settings().options().attachments().points()) {
                  if (!deletedPoints.contains(p.id())) {
                     authored.putIfAbsent(p.id(), p);
                     owners.putIfAbsent(p.id(), id);
                  }
               }
            }
         }
      }

      HashMap<UUID, List<RoadAttachments.Point>> assigned = new HashMap<>();

      for (RoadAttachments.Point px : authored.values()) {
         RoadIndex.Built curveOwner = next.stream()
            .filter(
               bx -> bx.record.id().equals(owners.get(px.id()))
                     && bx.record.settings().options().routing().controls().stream().anyMatch(c -> c.id().equals(px.id()) && c.position().equals(px.position()))
            )
            .findFirst()
            .orElse(null);
         if (curveOwner == null) {
            RoadIndex.Built target = null;
            double best = Double.POSITIVE_INFINITY;

            for (RoadIndex.Built bx : next) {
               if ((bx.record.junction() == null || OneSidedJunction.retainedPiece(bx.record.junction()))
                  && (!bx.record.settings().style().ramp() || bx.record.id().equals(owners.get(px.id())))) {
                  RoadQueries.Projection q = RoadQueries.horizontal(bx.mesh, px.position());
                  RoadGeometry.V tangent = q.tangent().horizontalUnit();
                  double extra = px.position().sub(q.sample().center()).dot(tangent);
                  if ((!(q.sample().distance() < 1.0E-6) || !(extra < -1.0E-5))
                     && (!(q.sample().distance() > bx.mesh.length() - 1.0E-6) || !(extra > 1.0E-5))
                     && !(q.horizontalDistance() > q.sample().halfWidth() + 1.0E-5)) {
                     double score = q.horizontalDistance() + (double)(bx.record.id().equals(owners.get(px.id())) ? -100 : 0);
                     if (score < best) {
                        best = score;
                        target = bx;
                     }
                  }
               }
            }

            if (target == null) {
               throw new IllegalArgumentException("调整后附属点 " + px.id().toString().substring(0, 8) + " 不再位于道路上，操作已取消");
            }

            RoadQueries.Projection q = RoadQueries.horizontal(target.mesh, px.position());
            RoadGeometry.V position = px.elevated()
               ? q.sample().at(RoadProfile.layout(target.mesh, q.sample()).medianCenter(), 0.0)
               : (px.controlled() ? new RoadGeometry.V(px.position().x(), q.sample().center().y(), px.position().z()) : q.sample().center());
            boolean same = target.record.id().equals(owners.get(px.id()));
            assigned.computeIfAbsent(target.record.id(), k -> new ArrayList<>())
               .add(new RoadAttachments.Point(px.id(), position, same ? px.anchor() : position, same && px.controlled(), px.origin(), px.elevated()));
         }
      }

      ArrayList<RoadAttachments.Span> oldPaint = new ArrayList<>();
      if (!deleting) {
         for (UUID idx : removed) {
            RoadIndex.Built old = data.index.roads.get(idx);
            if (old != null && old.record.junction() == null) {
               TreeSet<Double> cuts = new TreeSet<>();
               cuts.add(0.0);
               cuts.add(old.mesh.length());

               for (RoadAttachments.Span span : old.record.settings().options().attachments().spans()) {
                  cuts.add(RoadAttachments.station(old.mesh, span.start()));
                  cuts.add(RoadAttachments.station(old.mesh, span.end()));
               }

               ArrayList<Double> boundaries = new ArrayList<>(cuts);

               for (int n = 1; n < boundaries.size(); n++) {
                  double a = boundaries.get(n - 1);
                  double bxx = boundaries.get(n);
                  if (bxx - a > 1.0E-6) {
                     oldPaint.add(
                        new RoadAttachments.Span(
                           RoadAttachments.at(old.mesh, a), RoadAttachments.at(old.mesh, bxx), RoadAttachments.paint(old.mesh, (a + bxx) / 2.0)
                        )
                     );
                  }
               }
            }
         }
      }

      for (int i = 0; i < next.size(); i++) {
         RoadIndex.Built bxx = next.get(i);
         if ((bxx.record.junction() == null || !OneSidedJunction.retainedPiece(bxx.record.junction()))
            && (bxx.record.fragment() == null || !bxx.record.settings().options().attachments().equals(bxx.record.fragment().applied()))) {
            RoadAttachments.Data original = bxx.record.settings().options().attachments();
            List<RoadAttachments.Point> points = assigned.getOrDefault(bxx.record.id(), List.of());
            ArrayList<RoadAttachments.Span> spans = new ArrayList<>();
            if (bxx.record.junction() == null
               && (
                  !points.isEmpty()
                     || !original.spans().isEmpty()
                     || removed.stream()
                        .map(data.index.roads::get)
                        .filter(Objects::nonNull)
                        .anyMatch(old -> !old.record.settings().options().attachments().equals(RoadAttachments.Data.EMPTY))
               )) {
               for (RoadAttachments.Span span : oldPaint) {
                  RoadQueries.Projection qa = RoadQueries.horizontal(bxx.mesh, span.start());
                  RoadQueries.Projection qb = RoadQueries.horizontal(bxx.mesh, span.end());
                  if (!(Math.abs(qa.lateral()) > 0.01) && !(Math.abs(qb.lateral()) > 0.01)) {
                     double a = qa.sample().distance();
                     double z = qb.sample().distance();
                     if (Math.abs(z - a) > 1.0E-6) {
                        spans.add(new RoadAttachments.Span(RoadAttachments.at(bxx.mesh, a), RoadAttachments.at(bxx.mesh, z), span.paint()));
                     }
                  }
               }

               if (spans.isEmpty()) {
                  spans.addAll(original.spans());
               }
            }

            spans = new ArrayList<>(new LinkedHashSet<>(spans));
            RoadAttachments.Data value = new RoadAttachments.Data(points, spans);
            if (!value.equals(original)) {
               next.set(i, new RoadIndex.Built(bxx.record.withAttachments(value)));
            }
         }
      }
   }

   private AttachedPoints() {
   }
}
