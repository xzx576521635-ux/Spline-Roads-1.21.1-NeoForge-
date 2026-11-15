package com.sora.splineroads.client;

import com.sora.splineroads.config.RoadClientConfig;
import java.util.Locale;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

final class RoadGpuTimer {
   private static final int[] starts = new int[4];
   private static final int[] ends = new int[4];
   private static final boolean[] pending = new boolean[4];
   private static int cursor;
   private static int active = -1;
   private static double average;
   private static boolean measured;

   static void begin() {
      if (active < 0 && (Boolean)RoadClientConfig.PERF_OVERLAY.get() && GL.getCapabilities().OpenGL33) {
         for (int i = 0; i < starts.length; i++) {
            if (pending[i] && GL15.glGetQueryObjecti(ends[i], 34919) != 0) {
               long end = GL33.glGetQueryObjectui64(ends[i], 34918);
               long start = GL33.glGetQueryObjectui64(starts[i], 34918);
               double ms = (double)(end - start) / 1000000.0;
               average = measured ? average * 0.9 + ms * 0.1 : ms;
               measured = true;
               pending[i] = false;
            }
         }

         for (int n = 0; n < starts.length; n++) {
            int ix = (cursor + n) % starts.length;
            if (!pending[ix]) {
               if (starts[ix] == 0) {
                  starts[ix] = GL15.glGenQueries();
                  ends[ix] = GL15.glGenQueries();
               }

               GL33.glQueryCounter(starts[ix], 36392);
               active = ix;
               cursor = (ix + 1) % starts.length;
               return;
            }
         }
      }
   }

   static void end() {
      if (active >= 0) {
         GL33.glQueryCounter(ends[active], 36392);
         pending[active] = true;
         active = -1;
      }
   }

   static String metrics() {
      return measured
         ? String.format(Locale.ROOT, "SR road GPU: %.2f ms (async, excludes vanilla chunks/sign text)", average)
         : "SR road GPU: waiting / unsupported";
   }

   static void reset() {
      end();

      for (int i = 0; i < starts.length; i++) {
         if (starts[i] != 0) {
            GL15.glDeleteQueries(starts[i]);
            GL15.glDeleteQueries(ends[i]);
         }

         starts[i] = 0;
         ends[i] = 0;
         pending[i] = false;
      }

      measured = false;
      average = 0.0;
      cursor = 0;
   }

   private RoadGpuTimer() {
   }
}
