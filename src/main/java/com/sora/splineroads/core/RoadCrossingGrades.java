package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class RoadCrossingGrades {
   private static double cross(RoadGeometry.V a, RoadGeometry.V b) {
      return a.x() * b.z() - a.z() * b.x();
   }

   private static RoadGeometry.V median(RoadGeometry.Mesh m, RoadGeometry.Sample s) {
      return s.at(RoadProfile.layout(m, s).medianCenter(), 0.0);
   }

   public static RoadGeometry.Mesh fit(RoadGeometry.Mesh proposed, List<RoadGeometry.Mesh> existing) {
      if (RoadProfile.catalog(proposed.settings().style()).type() == RoadProfile.Type.ORDINARY
         && !proposed.closed()
         && !RoadTunnelFit.enabled(proposed.settings())) {
         ArrayList<RoadCrossingGrades.Patch> patches = new ArrayList<>();

         for (RoadGeometry.Mesh other : existing) {
            if (!other.closed()
               && (other.settings().style() == RoadGeometry.Style.RAILBED || RoadProfile.catalog(other.settings().style()).type() == RoadProfile.Type.ORDINARY)
               && !(proposed.max().x() < other.min().x())
               && !(proposed.min().x() > other.max().x())
               && !(proposed.max().z() < other.min().z())
               && !(proposed.min().z() > other.max().z())) {
               ArrayList<Double> seen = new ArrayList<>();
               RoadBoundsIndex index = RoadPathIndex.segments(other);
               List<RoadClearance.DetailedContact> contacts = null;

               for (int i = 1; i < proposed.samples().size(); i++) {
                  RoadGeometry.Sample a = proposed.samples().get(i - 1);
                  RoadGeometry.Sample b = proposed.samples().get(i);
                  RoadGeometry.V p = median(proposed, a);
                  RoadGeometry.V u = median(proposed, b).sub(p);

                  for (int segment : index.query(RoadPathIndex.segment(p, p.add(u)), 2.0)) {
                     RoadGeometry.Sample c = other.samples().get(segment);
                     RoadGeometry.Sample d = other.samples().get(segment + 1);
                     RoadGeometry.V q = median(other, c);
                     RoadGeometry.V v = median(other, d).sub(q);
                     double det = cross(u, v);
                     if (!(Math.abs(det) < 1.0E-7 * u.horizontalLength() * v.horizontalLength())) {
                        double t = cross(q.sub(p), v) / det;
                        double s = cross(q.sub(p), u) / det;
                        if (!(t < -1.0E-7) && !(t > 1.0000001) && !(s < -1.0E-7) && !(s > 1.0000001)) {
                           t = Math.max(0.0, Math.min(1.0, t));
                           s = Math.max(0.0, Math.min(1.0, s));
                           double station = a.distance() + (b.distance() - a.distance()) * t;
                           double otherStation = c.distance() + (d.distance() - c.distance()) * s;
                           if (!(station < 0.01)
                              && !(station > proposed.length() - 0.01)
                              && !(otherStation < 0.01)
                              && !(otherStation > other.length() - 0.01)
                              && !seen.stream().anyMatch(x -> Math.abs(x - station) < 0.1)) {
                              seen.add(station);
                              RoadGeometry.V ours = p.add(u.mul(t));
                              RoadGeometry.V theirs = q.add(v.mul(s));
                              double difference = ours.y() - theirs.y();
                              if (Math.abs(difference) > 1.0000001) {
                                 if (contacts == null) {
                                    contacts = RoadClearance.detailedContacts(proposed, other);
                                 }

                                 for (RoadClearance.DetailedContact contact : contacts) {
                                    if (contact.contact().from() <= station + other.settings().width() + 1.0
                                       && contact.contact().to() >= station - other.settings().width() - 1.0
                                       && contact.contact().blocked()) {
                                       throw new IllegalArgumentException("交汇高差超过1格且下方净高不足：" + new RoadClearance.Conflict(contact.contact()).getMessage());
                                    }
                                 }
                              } else if (!(Math.abs(difference) < 1.0E-7)) {
                                 double angle = Math.abs(cross(u.horizontalUnit(), v.horizontalUnit()));
                                 double half = Math.max(18.0, (a.halfWidth() + c.halfWidth()) * 2.0 / Math.max(0.1, angle));
                                 double transition = Math.max(24.0, Math.abs(difference) * 40.0);
                                 if (station - half - transition < 0.5 || station + half + transition > proposed.length() - 0.5) {
                                    throw new IllegalArgumentException("交汇高差在1格以内，但端点附近没有足够的平齐及缓坡过渡空间，请延长新道路");
                                 }

                                 double grade = other.settings().style() == RoadGeometry.Style.RAILBED ? v.y() / Math.max(1.0E-8, v.horizontalLength()) : 0.0;
                                 RoadGeometry.V direction = v.horizontalUnit();
                                 patches.add(
                                    new RoadCrossingGrades.Patch(station, half, transition, theirs.y(), grade * direction.x(), grade * direction.z(), theirs)
                                 );
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         if (patches.isEmpty()) {
            return proposed;
         } else {
            TreeSet<Double> stations = new TreeSet<>();
            proposed.samples().forEach(sx -> stations.add(sx.distance()));

            for (RoadCrossingGrades.Patch patch : patches) {
               for (double d = patch.station() - patch.half() - patch.transition(); d <= patch.station() + patch.half() + patch.transition(); d += 0.5) {
                  Double before = stations.floor(d);
                  Double after = stations.ceiling(d);
                  if ((before == null || d - before > 1.0E-5) && (after == null || after - d > 1.0E-5)) {
                     stations.add(d);
                  }
               }
            }

            ArrayList<RoadGeometry.Sample> samples = new ArrayList<>();

            for (double dx : stations) {
               RoadGeometry.Sample sample = RoadStructures.sample(proposed, dx);
               double weight = 0.0;
               double target = sample.center().y();

               for (RoadCrossingGrades.Patch patch : patches) {
                  double w = patch.weight(dx);
                  if (w > 0.999999 && weight > 0.999999 && Math.abs(target - patch.height(sample.center())) > 0.02) {
                     throw new IllegalArgumentException("相邻交汇需要不同高度且平齐区域重叠，请调整道路间距或端点高度");
                  }

                  if (w > weight) {
                     weight = w;
                     target = patch.height(sample.center());
                  }
               }

               RoadGeometry.V p = sample.center();
               samples.add(
                  new RoadGeometry.Sample(new RoadGeometry.V(p.x(), p.y() * (1.0 - weight) + target * weight, p.z()), sample.left(), dx, sample.halfWidth())
               );
            }

            double configured = proposed.settings().options().infrastructure().maxGrade();
            double cap = configured > 0.0 ? configured : 0.2;

            for (int i = 1; i < samples.size(); i++) {
               RoadGeometry.V delta = samples.get(i).center().sub(samples.get(i - 1).center());
               if (Math.abs(delta.y()) > delta.horizontalLength() * cap + 1.0E-7) {
                  throw new IllegalArgumentException("交汇缓坡超过道路设置的最大坡度，请延长过渡距离或调整端点高度");
               }
            }

            return RoadRibbon.mesh(samples, proposed.settings());
         }
      } else {
         return proposed;
      }
   }

   private RoadCrossingGrades() {
   }

   private static record Patch(double station, double half, double transition, double height, double gradeX, double gradeZ, RoadGeometry.V origin) {
      double weight(double d) {
         return RoadGeometry.Settings.smooth(Math.max(0.0, Math.min(1.0, (this.half + this.transition - Math.abs(d - this.station)) / this.transition)));
      }

      double height(RoadGeometry.V at) {
         return this.height + (at.x() - this.origin.x()) * this.gradeX + (at.z() - this.origin.z()) * this.gradeZ;
      }
   }
}
