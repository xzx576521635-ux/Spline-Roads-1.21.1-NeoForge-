package com.sora.splineroads.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexBuffer.Usage;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadSurface;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;

final class RoadSignalBatch {
   private static final int STRIDE = 9;
   private static final int BASE_BYTES = 96 + RoadSurface.Texture.values().length * 4;
   private final RoadGeometry.V origin;
   private static final RoadUploadArena PLATE_ARENA = new RoadUploadArena();
   private VertexBuffer plateBuffer;
   private int plateLight;
   private long plateUploads;
   private final EnumMap<RoadSurface.Texture, int[]> materials = new EnumMap<>(RoadSurface.Texture.class);
   private final EnumMap<RoadSurface.Texture, byte[]> compressed = new EnumMap<>(RoadSurface.Texture.class);
   private final EnumMap<RoadSurface.Texture, Integer> lengths = new EnumMap<>(RoadSurface.Texture.class);
   private int vertexBytes;
   private static long inflations;
   private static long inflatedBytes;

   void close() {
      if (this.plateBuffer != null) {
         this.plateBuffer.close();
         this.plateBuffer = null;
      }
   }

   long plateUploads() {
      return this.plateUploads;
   }

   void drawPlate(PoseStack pose, RoadGeometry.V camera, RenderType type, int light, Matrix4f projection) {
      this.buffer(light);
      type.setupRenderState();

      try {
         Matrix4f matrix = new Matrix4f(pose.last().pose())
            .translate((float)(this.origin.x() - camera.x()), (float)(this.origin.y() - camera.y()), (float)(this.origin.z() - camera.z()));
         this.plateBuffer.bind();
         this.plateBuffer.drawWithShader(matrix, projection, RenderSystem.getShader());
      } finally {
         VertexBuffer.unbind();
         type.clearRenderState();
      }
   }

   boolean currentLight(int light) {
      return this.plateBuffer != null && this.plateLight == light;
   }

   int residentLight() {
      return this.plateLight;
   }

   boolean preparePlate(int light) {
      if (this.currentLight(light)) {
         return true;
      } else if (!RoadUploadBudget.claimSign(this.vertexBytes)) {
         return this.plateBuffer != null;
      } else {
         this.buffer(light);
         return true;
      }
   }

   private VertexBuffer buffer(int light) {
      if (this.plateBuffer != null && this.plateLight == light) {
         return this.plateBuffer;
      } else {
         BufferBuilder builder = PLATE_ARENA.begin(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);

         for (int[] data : this.data().values()) {
            for (int i = 0; i < data.length; i += 9) {
               int color = data[i + 3];
               builder.addVertex(value(data, i), value(data, i + 1), value(data, i + 2))
                  .setColor(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, 255)
                  .setUv(value(data, i + 4), value(data, i + 5))
                  .setUv2(light, light);
            }
         }

         MeshData built = builder.buildOrThrow();
         VertexBuffer next = new VertexBuffer(Usage.STATIC);

         try {
            next.bind();
            next.upload(built);
         } catch (Error | RuntimeException var10) {
            next.close();
            throw var10;
         } finally {
            VertexBuffer.unbind();
         }

         this.close();
         this.plateBuffer = next;
         this.plateLight = light;
         this.plateUploads++;
         return next;
      }
   }

   static void drawPlates(
      List<RoadSignalBatch> batches, List<Integer> lights, PoseStack pose, RoadGeometry.V camera, RenderType type, Matrix4f projection, Matrix4f modelView
   ) {
      if (!batches.isEmpty()) {
         ArrayList<VertexBuffer> buffers = new ArrayList<>(batches.size());

         for (int i = 0; i < batches.size(); i++) {
            buffers.add(batches.get(i).buffer(lights.get(i)));
         }

         type.setupRenderState();
         ShaderInstance shader = RenderSystem.getShader();

         try {
            RoadRenderer.setupShader(shader, pose, projection);
            Matrix4f transform = new Matrix4f();

            for (int i = 0; i < batches.size(); i++) {
               RoadGeometry.V origin = batches.get(i).origin;
               transform.set(modelView).translate((float)(origin.x() - camera.x()), (float)(origin.y() - camera.y()), (float)(origin.z() - camera.z()));
               if (shader.MODEL_VIEW_MATRIX != null) {
                  shader.MODEL_VIEW_MATRIX.set(transform);
                  shader.MODEL_VIEW_MATRIX.upload();
               }

               buffers.get(i).bind();
               buffers.get(i).draw();
            }
         } finally {
            shader.clear();
            VertexBuffer.unbind();
            type.clearRenderState();
         }
      }
   }

   static long inflations() {
      return inflations;
   }

   static long inflatedBytes() {
      return inflatedBytes;
   }

   void warm() {
      this.data();
   }

   boolean compacted() {
      return !this.compressed.isEmpty();
   }

   int gpuBytes() {
      return this.vertexBytes;
   }

   void compact() {
      Iterator<Entry<RoadSurface.Texture, int[]>> it = this.materials.entrySet().iterator();

      while (it.hasNext()) {
         Entry<RoadSurface.Texture, int[]> entry = it.next();
         int[] input = entry.getValue();
         if (input.length >= 1024) {
            ByteBuffer raw = ByteBuffer.allocate(input.length * 4);
            raw.asIntBuffer().put(input);
            Deflater deflater = new Deflater(1);

            try {
               deflater.setInput(raw.array());
               deflater.finish();
               ByteArrayOutputStream output = new ByteArrayOutputStream();
               byte[] block = new byte[8192];

               while (!deflater.finished()) {
                  int n = deflater.deflate(block);
                  output.write(block, 0, n);
               }

               byte[] packed = output.toByteArray();
               if (packed.length < input.length * 3) {
                  this.compressed.put(entry.getKey(), packed);
                  this.lengths.put(entry.getKey(), input.length);
                  it.remove();
               }
            } finally {
               deflater.end();
            }
         }
      }
   }

   private Map<RoadSurface.Texture, int[]> data() {
      if (this.compressed.isEmpty()) {
         return this.materials;
      } else {
         EnumMap<RoadSurface.Texture, int[]> result = new EnumMap<>(this.materials);

         for (Entry<RoadSurface.Texture, byte[]> entry : this.compressed.entrySet()) {
            byte[] raw = new byte[this.lengths.get(entry.getKey()) * 4];
            Inflater inflater = new Inflater();

            try {
               inflater.setInput(entry.getValue());
               int at = 0;

               while (!inflater.finished() && at < raw.length) {
                  int n = inflater.inflate(raw, at, raw.length - at);
                  if (n == 0) {
                     throw new IllegalStateException("Truncated sign geometry");
                  }

                  at += n;
               }

               if (at != raw.length) {
                  throw new IllegalStateException("Invalid sign geometry size");
               }

               int[] expanded = new int[raw.length / 4];
               ByteBuffer.wrap(raw).asIntBuffer().get(expanded);
               result.put(entry.getKey(), expanded);
               inflations++;
               inflatedBytes += (long)raw.length;
            } catch (DataFormatException var11) {
               throw new IllegalStateException("Invalid sign cache", var11);
            } finally {
               inflater.end();
            }
         }

         this.materials.clear();
         this.materials.putAll(result);
         this.compressed.clear();
         this.lengths.clear();
         return this.materials;
      }
   }

   RoadSignalBatch(List<RoadSurface.Face> faces) {
      this.origin = faces.stream().filter(fx -> !fx.points().isEmpty()).map(fx -> fx.points().get(0)).findFirst().orElse(new RoadGeometry.V(0.0, 0.0, 0.0));
      EnumMap<RoadSurface.Texture, Integer> counts = new EnumMap<>(RoadSurface.Texture.class);

      for (RoadSurface.Face f : faces) {
         int n = f.points().size();
         if (n >= 3) {
            if (!f.uv().isEmpty() && f.uv().size() != n) {
               throw new IllegalArgumentException("Signal UV count does not match its polygon");
            }

            counts.merge(f.texture(), Integer.valueOf(n == 4 ? 4 : 4 * (n - 2)), Integer::sum);
         }
      }

      EnumMap<RoadSurface.Texture, RoadSignalBatch.Group> groups = new EnumMap<>(RoadSurface.Texture.class);
      counts.forEach((t, nx) -> groups.put(t, new RoadSignalBatch.Group(nx)));

      for (RoadSurface.Face fx : faces) {
         int n = fx.points().size();
         if (n >= 3) {
            RoadSignalBatch.Group out = groups.get(fx.texture());
            if (n == 4) {
               this.quad(out, fx, 0, 1, 2, 3);
            } else {
               for (int i = 1; i < n - 1; i++) {
                  this.quad(out, fx, 0, i, i + 1, i + 1);
               }
            }
         }
      }

      groups.forEach((t, g) -> this.materials.put(t, g.size == g.data.length ? g.data : Arrays.copyOf(g.data, g.size)));

      for (int[] data : this.materials.values()) {
         this.vertexBytes += data.length / 9 * 28;
      }
   }

   private void quad(RoadSignalBatch.Group out, RoadSurface.Face face, int a, int b, int c, int d) {
      RoadGeometry.V ab = face.points().get(b).sub(face.points().get(a));
      RoadGeometry.V ac = face.points().get(c).sub(face.points().get(a));
      RoadGeometry.V n = new RoadGeometry.V(ab.y() * ac.z() - ab.z() * ac.y(), ab.z() * ac.x() - ab.x() * ac.z(), ab.x() * ac.y() - ab.y() * ac.x());
      double length = Math.sqrt(n.dot(n));
      if (length < 1.0E-10) {
         if (c != d) {
            this.quad(out, face, a, c, d, d);
         }
      } else {
         n = n.mul(1.0 / length);
         this.vertex(out, face, a, n, 0);
         this.vertex(out, face, b, n, 1);
         this.vertex(out, face, c, n, 2);
         this.vertex(out, face, d, n, c == d ? 2 : 3);
      }
   }

   private void vertex(RoadSignalBatch.Group out, RoadSurface.Face face, int index, RoadGeometry.V n, int corner) {
      RoadGeometry.V p = face.points().get(index).sub(this.origin);
      RoadSurface.UV uv = face.uv().isEmpty() ? null : face.uv().get(index);
      int[] data = out.data;
      int i = out.size;
      data[i] = bits(p.x());
      data[i + 1] = bits(p.y());
      data[i + 2] = bits(p.z());
      data[i + 3] = face.color();
      data[i + 4] = bits(uv == null ? (corner != 0 && corner != 3 ? 0.0546875 : 0.0078125) : uv.u());
      data[i + 5] = bits(uv == null ? (corner < 2 ? 0.0078125 : 0.0546875) : uv.v());
      data[i + 6] = bits(n.x());
      data[i + 7] = bits(n.y());
      data[i + 8] = bits(n.z());
      out.size = i + 9;
   }

   private static int bits(double v) {
      return Float.floatToRawIntBits((float)v);
   }

   private static float value(int[] data, int i) {
      return Float.intBitsToFloat(data[i]);
   }

   int retainedBytes() {
      int size = BASE_BYTES;

      for (int[] v : this.materials.values()) {
         size += 16 + v.length * 4;
      }

      for (byte[] v : this.compressed.values()) {
         size += 48 + v.length;
      }

      return size;
   }

   int retainedPlateBytes() {
      return this.retainedBytes() + this.vertexBytes;
   }

   void emit(PoseStack pose, MultiBufferSource buffers, RoadGeometry.V camera, Function<RoadSurface.Texture, RenderType> types, Set<RenderType> used) {
      this.emit(pose, buffers, camera, types, used, 15728880);
   }

   void emitPlate(PoseStack pose, MultiBufferSource buffers, RoadGeometry.V camera, RenderType type, Set<RenderType> used, int light) {
      pose.pushPose();
      pose.translate(this.origin.x() - camera.x(), this.origin.y() - camera.y(), this.origin.z() - camera.z());

      try {
         Matrix4f transform = pose.last().pose();
         VertexConsumer target = buffers.getBuffer(type);
         used.add(type);

         for (int[] data : this.data().values()) {
            for (int i = 0; i < data.length; i += 9) {
               int color = data[i + 3];
               target.addVertex(transform, value(data, i), value(data, i + 1), value(data, i + 2))
                  .setColor(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, 255)
                  .setUv(value(data, i + 4), value(data, i + 5))
                  .setUv2(light, light);
            }
         }
      } finally {
         pose.popPose();
      }
   }

   void emit(PoseStack pose, MultiBufferSource buffers, RoadGeometry.V camera, Function<RoadSurface.Texture, RenderType> types, Set<RenderType> used, int light) {
      pose.pushPose();
      pose.translate(this.origin.x() - camera.x(), this.origin.y() - camera.y(), this.origin.z() - camera.z());
      Matrix4f transform = pose.last().pose();
      Pose normals = pose.last();

      try {
         for (Entry<RoadSurface.Texture, int[]> entry : this.data().entrySet()) {
            RenderType type = Objects.requireNonNull(types.apply(entry.getKey()), "Missing SR signal material: " + entry.getKey());
            used.add(type);
            VertexConsumer target = buffers.getBuffer(type);
            int[] data = entry.getValue();

            for (int i = 0; i < data.length; i += 9) {
               int color = data[i + 3];
               target.addVertex(transform, value(data, i), value(data, i + 1), value(data, i + 2))
                  .setColor(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, 255)
                  .setUv(value(data, i + 4), value(data, i + 5))
                  .setOverlay(OverlayTexture.NO_OVERLAY)
                  .setUv2(light, light)
                  .setNormal(normals, value(data, i + 6), value(data, i + 7), value(data, i + 8));
            }
         }
      } finally {
         pose.popPose();
      }
   }

   private static final class Group {
      final int[] data;
      int size;

      Group(int vertices) {
         this.data = new int[vertices * 9];
      }
   }
}
