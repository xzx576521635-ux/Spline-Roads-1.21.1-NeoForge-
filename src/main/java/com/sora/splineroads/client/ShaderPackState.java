package com.sora.splineroads.client;

import java.lang.System.Logger.Level;
import java.lang.reflect.Method;

final class ShaderPackState {
   private static final String[] APIS = new String[]{"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"};

   static ShaderPackState.Probe discover(ClassLoader loader, String... names) {
      for (String name : names) {
         try {
            Class<?> api = Class.forName(name, false, loader);
            Object instance = api.getMethod("getInstance").invoke(null);
            Method active = api.getMethod("isShaderPackInUse");
            Method shadow = api.getMethod("isRenderingShadowPass");
            return new ShaderPackState.Probe(instance, active, shadow, name);
         } catch (RuntimeException | LinkageError | ReflectiveOperationException var10) {
         }
      }

      return new ShaderPackState.Probe(null, null, null, "no Iris/Oculus API");
   }

   static ShaderPackState.EncodingProbe discoverEncoding(ClassLoader loader, String... names) {
      for (String name : names) {
         try {
            Class<?> type = Class.forName(name, false, loader);
            return new ShaderPackState.EncodingProbe(type.getField("INSTANCE").get(null), type.getMethod("shouldUseExtendedVertexFormat"));
         } catch (RuntimeException | LinkageError | ReflectiveOperationException var7) {
         }
      }

      return new ShaderPackState.EncodingProbe(null, null);
   }

   static boolean extendedVertices(ShaderPackState.State state) {
      return ShaderPackState.EncodingHolder.PROBE.read(state.active());
   }

   static ShaderPackState.State current() {
      return ShaderPackState.Holder.PROBE.read();
   }

   private ShaderPackState() {
   }

   private static final class EncodingHolder {
      static final ShaderPackState.EncodingProbe PROBE = ShaderPackState.discoverEncoding(
         ShaderPackState.class.getClassLoader(),
         "net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings",
         "net.coderbot.iris.block_rendering.WorldRenderingSettings"
      );
   }

   static final class EncodingProbe {
      final Object instance;
      final Method method;

      EncodingProbe(Object instance, Method method) {
         this.instance = instance;
         this.method = method;
      }

      boolean read(boolean fallback) {
         if (this.method == null) {
            return fallback;
         } else {
            try {
               return Boolean.TRUE.equals(this.method.invoke(this.instance));
            } catch (RuntimeException | LinkageError | ReflectiveOperationException var3) {
               return fallback;
            }
         }
      }
   }

   private static final class Holder {
      static final ShaderPackState.Probe PROBE = ShaderPackState.discover(ShaderPackState.class.getClassLoader(), ShaderPackState.APIS);
   }

   static final class Probe {
      private final Object instance;
      private final Method active;
      private final Method shadow;
      private final String provider;
      private boolean warned;

      Probe(Object instance, Method active, Method shadow, String provider) {
         this.instance = instance;
         this.active = active;
         this.shadow = shadow;
         this.provider = provider;
      }

      ShaderPackState.State read() {
         if (this.active == null) {
            return new ShaderPackState.State(false, false, this.provider);
         } else {
            try {
               boolean inUse = Boolean.TRUE.equals(this.active.invoke(this.instance));
               return new ShaderPackState.State(inUse, inUse && this.shadow != null && Boolean.TRUE.equals(this.shadow.invoke(this.instance)), this.provider);
            } catch (RuntimeException | LinkageError | ReflectiveOperationException var2) {
               if (!this.warned) {
                  this.warned = true;
                  System.getLogger("SplineRoads").log(Level.WARNING, "Cannot query shader render state; AUTO safely falls back to VBO", var2);
               }

               return new ShaderPackState.State(false, false, this.provider + " unavailable");
            }
         }
      }
   }

   static record State(boolean active, boolean shadowPass, String provider) {
   }
}
