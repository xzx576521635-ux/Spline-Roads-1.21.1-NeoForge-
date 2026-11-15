package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;

public interface RoadChunkBuffers {
   Map<RenderType, VertexBuffer> splineroads$buffers();
}
