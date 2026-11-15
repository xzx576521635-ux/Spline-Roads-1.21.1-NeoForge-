package com.sora.splineroads.core;

import java.util.Locale;
import java.util.UUID;

public final class RoadCrossingFailure extends IllegalArgumentException {
   private final UUID roadId;
   private final String historyReason;
   private final String diagnostic;
   private final RoadGeometry.V failurePosition;
   private final RoadGeometry.V roadStart;
   private final RoadGeometry.V roadEnd;

   public RoadCrossingFailure(UUID roadId, RoadGeometry.Mesh mesh, String historyReason, IllegalArgumentException cause) {
      super(message(roadId, mesh, cause), cause);
      this.roadId = roadId;
      this.historyReason = historyReason;
      this.roadStart = mesh.first().center();
      this.roadEnd = mesh.last().center();
      this.failurePosition = position(mesh, cause);
      RoadGeometry.V start = this.roadStart;
      RoadGeometry.V end = this.roadEnd;
      String detail = "";
      if (cause instanceof RoadsideBypass.ValidationException failure) {
         detail = String.format(
            Locale.ROOT,
            " reason=%s side=%d stationFrom=%.6f stationTo=%.6f slope=%s limit=%.6f",
            failure.reason(),
            failure.side(),
            failure.stationFrom(),
            failure.stationTo(),
            Double.toString(failure.slope()),
            failure.limit()
         );
         if (failure.worldFrom() != null && failure.worldTo() != null) {
            detail = detail + " worldFrom=" + coordinates(failure.worldFrom(), 6) + " worldTo=" + coordinates(failure.worldTo(), 6);
         }
      }

      this.diagnostic = String.format(
         Locale.ROOT,
         "Cycle crossing rejected road=%s history=%s start=(%.3f,%.3f,%.3f) end=(%.3f,%.3f,%.3f) failurePosition=%s%s",
         roadId,
         historyReason,
         start.x(),
         start.y(),
         start.z(),
         end.x(),
         end.y(),
         end.z(),
         coordinates(this.failurePosition, 6),
         detail
      );
   }

   private static String message(UUID roadId, RoadGeometry.Mesh mesh, IllegalArgumentException cause) {
      String location = "非机动车道所在道路 " + roadId;
      if (cause instanceof RoadsideBypass.ValidationException failure) {
         location = location
            + String.format(Locale.ROOT, "，%s（按 A→B），距起点 %.2f–%.2f 格", failure.side() < 0 ? "左侧" : "右侧", failure.stationFrom(), failure.stationTo());
         if (Double.isFinite(failure.slope())) {
            location = location
               + String.format(Locale.ROOT, "，实际坡度 %s%% / 上限 %.0f%%（取引道最大值）", percent(failure.slope(), failure.limit()), failure.limit() * 100.0);
         } else if (Double.isInfinite(failure.slope())) {
            location = location + "，存在垂直高差";
         }
      }

      location = location
         + "，问题坐标 "
         + coordinates(position(mesh, cause), 2)
         + "；道路起点 A"
         + coordinates(mesh.first().center(), 2)
         + " → 终点 B"
         + coordinates(mesh.last().center(), 2);
      return location + "；" + (cause.getMessage() == null ? "非机动车道规划失败" : cause.getMessage());
   }

   private static String percent(double slope, double limit) {
      for (int digits = 2; digits <= 6; digits++) {
         String text = String.format(Locale.ROOT, "%." + digits + "f", slope * 100.0);
         if (slope <= limit || Double.parseDouble(text) > limit * 100.0) {
            return text;
         }
      }

      return Double.toString(slope * 100.0);
   }

   private static RoadGeometry.V position(RoadGeometry.Mesh mesh, IllegalArgumentException cause) {
      if (cause instanceof RoadsideBypass.ValidationException failure) {
         if (failure.worldFrom() != null && failure.worldTo() != null) {
            return failure.worldFrom().add(failure.worldTo()).mul(0.5);
         }

         double station = (failure.stationFrom() + failure.stationTo()) / 2.0;
         if (Double.isFinite(station)) {
            return RoadStructures.sample(mesh, Math.max(0.0, Math.min(mesh.length(), station))).center();
         }
      }

      return mesh.first().center();
   }

   private static String coordinates(RoadGeometry.V point, int digits) {
      return String.format(Locale.ROOT, "(X=%." + digits + "f, Y=%." + digits + "f, Z=%." + digits + "f)", point.x(), point.y(), point.z());
   }

   public UUID roadId() {
      return this.roadId;
   }

   public String historyReason() {
      return this.historyReason;
   }

   public RoadGeometry.V failurePosition() {
      return this.failurePosition;
   }

   public RoadGeometry.V roadStart() {
      return this.roadStart;
   }

   public RoadGeometry.V roadEnd() {
      return this.roadEnd;
   }

   public String diagnostic() {
      return this.diagnostic;
   }

   public boolean candidateDependent() {
      return this.getCause() instanceof RoadsideBypass.ValidationException || this.getCause() instanceof RoadCrossingFailure.GeometryRejection;
   }

   public static RoadCrossingFailure find(Throwable failure) {
      int i = 0;

      while (failure != null && i < 8) {
         if (failure instanceof RoadCrossingFailure) {
            return (RoadCrossingFailure)failure;
         }

         i++;
         failure = failure.getCause();
      }

      return null;
   }

   public static final class GeometryRejection extends IllegalArgumentException {
      public GeometryRejection(String message) {
         super(message);
      }
   }
}
