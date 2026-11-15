package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;

final class RoadUploadArena {
   private ByteBufferBuilder bytes;
   private final int initialCapacity;

   RoadUploadArena() {
      this(4096);
   }

   RoadUploadArena(int initialCapacity) {
      this.initialCapacity = initialCapacity;
   }

   boolean allocated() {
      return this.bytes != null;
   }

   BufferBuilder begin() {
      return this.begin(DefaultVertexFormat.NEW_ENTITY);
   }

   BufferBuilder begin(VertexFormat format) {
      if (this.bytes == null) {
         this.bytes = new ByteBufferBuilder(this.initialCapacity);
      }

      this.bytes.clear();
      return new BufferBuilder(this.bytes, Mode.QUADS, format);
   }
}
