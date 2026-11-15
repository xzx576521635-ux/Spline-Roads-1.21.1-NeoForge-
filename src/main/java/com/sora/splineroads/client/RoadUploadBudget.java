package com.sora.splineroads.client;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadRenderMesh;
import com.sora.splineroads.core.RoadSurface;
import java.util.ArrayList;
import java.util.List;

public final class RoadUploadBudget {
   public static final int MAX_VERTICES = 8192;
   private static final int UNIT_BYTES = 294912;
   private static final int FRAME_BYTES = 1179648;
   private static final int MAX_SIGN_BYTES = 16777216;
   private static boolean active;
   private static boolean uploadsAllowed;
   private static int signReserve;
   private static long deadline;
   private static int roadBytes;
   private static int signBytes;
   private static int roadUploads;
   private static int signUploads;

   static void beginFrame(long micros, boolean signsWaiting) {
      beginFrame(micros, signsWaiting ? 294912 : 0);
   }

   static void beginFrame(long micros, int signBytesWaiting) {
      uploadsAllowed = true;
      active = true;
      signReserve = Math.min(16777216, Math.max(0, signBytesWaiting));
      deadline = System.nanoTime() + micros * 1000L;
      signUploads = 0;
      roadUploads = 0;
      signBytes = 0;
      roadBytes = 0;
   }

   static void drawOnly() {
      active = true;
      uploadsAllowed = false;
   }

   static void endFrame() {
      active = false;
   }

   static boolean claimRoad(int bytes) {
      return claim(bytes, false);
   }

   static boolean claimSign(int bytes) {
      return claim(bytes, true);
   }

   private static boolean claim(int bytes, boolean sign) {
      if (!active) {
         return true;
      } else if (!uploadsAllowed) {
         return false;
      } else {
         int used = roadBytes + signBytes;
         int own = sign ? signUploads : roadUploads;
         if (sign && signReserve > 1179648 && signUploads == 0 && bytes < signReserve) {
            return false;
         } else {
            int limit = sign ? 1179648 : Math.max(0, 1179648 - signReserve);
            boolean largePlate = sign && bytes > 1179648 && bytes <= 16777216 && used == 0;
            if (!largePlate && used + bytes > limit) {
               return false;
            } else if (own > 0 && System.nanoTime() >= deadline) {
               return false;
            } else {
               if (sign) {
                  signBytes += bytes;
                  signUploads++;
               } else {
                  roadBytes += bytes;
                  roadUploads++;
               }

               return true;
            }
         }
      }
   }

   static String metrics() {
      return "upload roadBytes=" + roadBytes + " signBytes=" + signBytes + " roadBuffers=" + roadUploads + " signBuffers=" + signUploads;
   }

   public static List<RoadSurface.Face> faces(List<RoadSurface.Face> input) {
      if (input.stream().noneMatch(fx -> RoadRenderMesh.vertexCount(fx) > 8192)) {
         return input;
      } else {
         ArrayList<RoadSurface.Face> out = new ArrayList<>();

         for (RoadSurface.Face f : input) {
            if (RoadRenderMesh.vertexCount(f) <= 8192) {
               out.add(f);
            } else {
               int first = 1;

               while (first < f.points().size() - 1) {
                  int last = Math.min(f.points().size() - 1, first + 2048);
                  ArrayList<RoadGeometry.V> points = new ArrayList<>();
                  points.add(f.points().get(0));
                  points.addAll(f.points().subList(first, last + 1));
                  ArrayList<RoadSurface.UV> uv = new ArrayList<>();
                  if (!f.uv().isEmpty()) {
                     uv.add(f.uv().get(0));
                     uv.addAll(f.uv().subList(first, last + 1));
                  }

                  out.add(new RoadSurface.Face(List.copyOf(points), f.color(), f.emissive(), f.texture(), List.copyOf(uv)));
                  first = last;
               }
            }
         }

         return List.copyOf(out);
      }
   }

   private RoadUploadBudget() {
   }
}
