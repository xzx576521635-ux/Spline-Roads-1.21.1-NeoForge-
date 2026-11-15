package com.sora.splineroads.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.world.LaneTopology;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import org.joml.Matrix4f;

public final class ManualPlannerScreen extends Screen {
   final UUID session;
   private CompoundTag state;
   private double cx;
   private double cz;
   private double zoom = 3.0;
   private int tool = 1;
   private int arms = 4;
   private final List<Button> toolbar = new ArrayList<>();
   private Button resumePreview;
   private String status = "选择工具后单击地图；右键点可打开菜单";
   private boolean help;
   private ManualPlannerScreen.Point selected;
   private UUID selectedRoad;
   private final List<ManualPlannerScreen.Point> points = new ArrayList<>();
   private boolean pressed;
   private boolean dragged;
   private boolean moving;
   private boolean openSelected;
   private final Set<UUID> hidden = new HashSet<>();
   private final Map<UUID, RoadIndex.Built> mapRoads = new LinkedHashMap<>();
   private long mapRevision;
   private long preparedRevision = -1L;
   private double preparedCx;
   private double preparedCz;
   private double preparedZoom;
   private int preparedWidth;
   private int preparedHeight;
   private Set<UUID> preparedHidden = Set.of();
   private final List<ManualPlannerScreen.Strip> preparedStrips = new ArrayList<>();
   private final List<ManualPlannerScreen.Point> displayedPoints = new ArrayList<>();
   private UUID snapshot;
   private int nextPage;
   private int pageCount;
   private int expectedRoads;
   private int expectedNodes;
   private long snapshotAt;
   private final Map<UUID, RoadIndex.Built> pendingRoads = new LinkedHashMap<>();
   private final Map<Long, CompoundTag> pendingNodes = new HashMap<>();
   private final Map<Long, ManualPlannerScreen.MapTile> tiles = new HashMap<>();
   private final Map<Long, CompoundTag> mapNodes = new HashMap<>();
   private int terrainStep = 1;
   private int loadedTiles;
   private int totalTiles;
   private double pressX;
   private double pressY;
   private double pressCenterX;
   private double pressCenterZ;
   private double pressAt;
   private RoadGeometry.V movingAt;
   private int axis;
   private long confirmAt;
   private double confirmX;
   private double confirmY;
   private int menuX = -1;
   private int menuY;
   private int terrainX;
   private int terrainZ;
   private int terrainCursor;
   private long terrainUploaded;
   private long terrainFinished;
   private int terrainScanStart;
   private boolean terrainReady;
   private boolean terrainDirty;
   private final int[] heights = new int[1048576];
   private DynamicTexture terrain;
   private ResourceLocation texture;
   private final Set<Integer> keys = new HashSet<>();

   ManualPlannerScreen(CompoundTag t) {
      super(Component.literal("Spline Roads · 手动规划"));
      this.session = t.getUUID("PlannerSession");
      this.state = t.copy();
      LocalPlayer p = Minecraft.getInstance().player;
      this.cx = p.getX();
      this.cz = p.getZ();
      Arrays.fill(this.heights, Integer.MIN_VALUE);
   }

   void state(CompoundTag t) {
      if (t.contains("MapRoads") || t.contains("Nodes")) {
         this.mapRevision++;
      }

      this.state = t.copy();
      this.tool = t.getInt("Tool");
      this.arms = t.getInt("Arms");
      if (t.contains("MapRoads")) {
         HashSet<UUID> ids = new HashSet<>();

         for (Tag value : t.getList("MapRoads", 10)) {
            RoadRecord r = RoadRecord.load((CompoundTag)value);
            ids.add(r.id());
            RoadIndex.Built old = this.mapRoads.get(r.id());
            if (old == null || !old.record.equals(r)) {
               this.mapRoads.put(r.id(), RoadIndex.Built.loading(r));
            }
         }

         this.mapRoads.keySet().retainAll(ids);
         this.hidden.retainAll(ids);
      }

      if (t.contains("Nodes")) {
         this.mapNodes.clear();

         for (Tag valuex : t.getList("Nodes", 10)) {
            CompoundTag n = (CompoundTag)valuex;
            this.mapNodes.put(n.getLong("Pos"), n);
         }
      }

      this.updateToolbar();
   }

   void beginSnapshot(CompoundTag t) {
      this.snapshot = t.getUUID("MapSnapshot");
      this.nextPage = 0;
      this.pageCount = t.getInt("Pages");
      this.expectedRoads = t.getInt("RoadCount");
      this.expectedNodes = t.getInt("NodeCount");
      this.pendingRoads.clear();
      this.pendingNodes.clear();
      this.snapshotAt = Util.getMillis();
      if (this.expectedRoads >= 0
         && this.expectedNodes >= 0
         && this.pageCount >= 1
         && (long)this.pageCount <= Math.max(1L, (long)this.expectedRoads + (long)this.expectedNodes)) {
         this.status = "正在加载规划道路…";
      } else {
         throw new IllegalArgumentException("规划地图批次数量错误，请重新打开");
      }
   }

   boolean loading() {
      return this.snapshot != null;
   }

   boolean snapshotExpired(long now) {
      return this.loading() && now - this.snapshotAt > 30000L;
   }

   void failSnapshot(String message) {
      this.snapshot = null;
      this.pendingRoads.clear();
      this.pendingNodes.clear();
      this.status = message;
   }

   void page(CompoundTag t) {
      if (this.snapshot != null && t.hasUUID("MapSnapshot") && this.snapshot.equals(t.getUUID("MapSnapshot"))) {
         if (t.getInt("Pages") == this.pageCount && t.getInt("Page") == this.nextPage) {
            for (Tag value : t.getList("MapRoads", 10)) {
               RoadRecord r = RoadRecord.load((CompoundTag)value);
               RoadIndex.Built old = this.mapRoads.get(r.id());
               this.pendingRoads.put(r.id(), old != null && old.record.equals(r) ? old : RoadIndex.Built.loading(r));
            }

            for (Tag value : t.getList("Nodes", 10)) {
               CompoundTag node = (CompoundTag)value;
               this.pendingNodes.put(node.getLong("Pos"), node);
            }

            if (this.pendingRoads.size() <= this.expectedRoads && this.pendingNodes.size() <= this.expectedNodes) {
               this.snapshotAt = Util.getMillis();
               if (++this.nextPage == this.pageCount) {
                  if (this.pendingRoads.size() != this.expectedRoads || this.pendingNodes.size() != this.expectedNodes) {
                     throw new IllegalArgumentException("规划地图数据不完整，请重新打开");
                  }

                  this.mapRevision++;
                  this.mapRoads.clear();
                  this.mapRoads.putAll(this.pendingRoads);
                  this.mapNodes.clear();
                  this.mapNodes.putAll(this.pendingNodes);
                  this.hidden.retainAll(this.mapRoads.keySet());
                  this.pendingRoads.clear();
                  this.pendingNodes.clear();
                  this.snapshot = null;
                  this.status = this.hint();
               }
            } else {
               throw new IllegalArgumentException("规划地图数据数量错误，请重新打开");
            }
         } else {
            throw new IllegalArgumentException("规划地图分批顺序错误，请重新打开");
         }
      }
   }

   void result(boolean success, String message) {
      if (!message.isEmpty()) {
         this.status = message.equals("已选择地图工具") ? this.hint() : message;
      }
   }

   void refresh() {
      if (this.minecraft != null) {
         CompoundTag t = this.command("manualRefresh");
         ClientRoads.sendAction(t);
      }
   }

   private CompoundTag command(String action) {
      CompoundTag t = new CompoundTag();
      t.putString("Action", action);
      t.putUUID("PlannerSession", this.session);
      return t;
   }

   private int left() {
      return 116;
   }

   private int top() {
      return 36;
   }

   private int right() {
      return this.width - 8;
   }

   private int bottom() {
      return this.height - 43;
   }

   private double sx(double x) {
      return (double)(this.left() + this.right()) * 0.5 + (x - this.cx) * this.zoom;
   }

   private double sy(double z) {
      return (double)(this.top() + this.bottom()) * 0.5 + (z - this.cz) * this.zoom;
   }

   private double wx(double x) {
      return this.cx + (x - (double)(this.left() + this.right()) * 0.5) / this.zoom;
   }

   private double wz(double y) {
      return this.cz + (y - (double)(this.top() + this.bottom()) * 0.5) / this.zoom;
   }

   private boolean map(double x, double y) {
      return x >= (double)this.left() && x < (double)this.right() && y >= (double)this.top() && y < (double)this.bottom();
   }

   protected void init() {
      this.toolbar.clear();
      String[] labels = new String[]{"1  指针", "2  端点", "3  道路连接", "4  匝道连接", "5  自动立交", "6  收费站", "7  环岛"};
      int step = Math.min(25, Math.max(10, (this.height - (this.height >= 240 ? 144 : 120)) / 7));

      for (int i = 0; i < 7; i++) {
         int n = i + 1;
         this.toolbar
            .add(
               (Button)this.addRenderableWidget(
                  Button.builder(Component.literal(labels[i]), b -> this.choose(n)).bounds(8, 38 + i * step, 100, step - 3).build()
               )
            );
      }

      this.addRenderableWidget(Button.builder(Component.literal("清除选点"), b -> {
         this.selected = null;
         this.selectedRoad = null;
         this.moving = false;
         this.menuX = -1;
         this.confirmAt = 0L;
         this.send("manualClear", null, null, 0.0, 0.0);
      }).bounds(8, 41 + 7 * step, 100, 20).build());
      if (this.height >= 240) {
         this.addRenderableWidget(Button.builder(Component.literal("回到玩家"), b -> {
            this.cx = this.minecraft.player.getX();
            this.cz = this.minecraft.player.getZ();
            this.resetTerrain();
         }).bounds(8, 65 + 7 * step, 100, 20).build());
      }

      this.addRenderableWidget(
         Button.builder(Component.literal("设置"), b -> this.minecraft.setScreen(new ManualPlannerScreen.PlannerSettings()))
            .bounds(this.width - 158, 8, 50, 20)
            .build()
      );
      this.addRenderableWidget(Button.builder(Component.literal("? 操作说明"), b -> this.help = !this.help).bounds(this.width - 102, 8, 94, 20).build());
      this.resumePreview = (Button)this.addRenderableWidget(
         Button.builder(Component.literal("返回设置"), b -> ManualPlannerClient.resumeMapPreview()).bounds(8, this.height - 54, 100, 20).build()
      );
      this.addRenderableWidget(Button.builder(Component.literal("关闭地图"), b -> this.onClose()).bounds(8, this.height - 28, 100, 20).build());
      this.updateToolbar();
      if (this.terrain == null) {
         this.terrain = new DynamicTexture(new NativeImage(1024, 1024, false));
         this.texture = this.minecraft.getTextureManager().register("sr_manual_map", this.terrain);
         this.resetTerrain();
      }
   }

   private void updateToolbar() {
      for (int i = 0; i < this.toolbar.size(); i++) {
         String text = this.toolbar.get(i).getMessage().getString();
         if (i == 4) {
            text = "5  " + this.arms + "向立交";
         }

         this.toolbar.get(i).setMessage(Component.literal(text).withStyle(i + 1 == this.tool ? ChatFormatting.AQUA : ChatFormatting.WHITE));
      }
   }

   private void choose(int n) {
      if (this.tool != n) {
         this.selected = null;
         this.selectedRoad = null;
         this.openSelected = false;
      }

      this.tool = n;
      this.updateToolbar();
      this.menuX = -1;
      this.moving = false;
      CompoundTag t = this.command("manualTool");
      t.putInt("Tool", this.tool);
      t.putInt("Arms", this.arms);
      ClientRoads.sendAction(t);
      this.status = this.hint();
   }

   private String hint() {
      return switch (this.tool) {
         case 2 -> "单击地表放置端点；Ctrl：附属点；左 Alt＋单击道路：高架出入点";
         case 3 -> "选择端点或附属点 A → B；Ctrl：经过点；左 Alt：控制点";
         case 4 -> "Ctrl：创建车道点；选 A / B；左 Alt：匝道放置点";
         case 5 -> "Ctrl＋滚轮切换 3–6 向，当前 " + this.arms + " 向";
         case 6 -> "单击道路断面，或依次选择端点 A / B";
         case 7 -> "单击空闲端点作为环岛圆心";
         default -> "首次单击选中，再次单击打开；右键菜单；H 恢复隐藏";
      };
   }

   private void resetTerrain() {
      int step = Math.max(
         1, (int)Math.ceil(Math.max((double)(this.right() - this.left()) / this.zoom, (double)(this.bottom() - this.top()) / this.zoom) / 1024.0)
      );
      int nx = (int)Math.floor(this.cx) - 512 * step & -16;
      int nz = (int)Math.floor(this.cz) - 512 * step & -16;
      if (!this.terrainReady || nx != this.terrainX || nz != this.terrainZ || step != this.terrainStep) {
         this.terrainX = nx;
         this.terrainZ = nz;
         this.terrainStep = step;
         this.terrainReady = true;
         this.terrainCursor = 0;
         NativeImage image = this.terrain.getPixels();

         for (int z = 0; z < 1024; z++) {
            for (int x = 0; x < 1024; x++) {
               int bx = this.terrainX + x * step;
               int bz = this.terrainZ + z * step;
               ManualPlannerScreen.MapTile tile = this.tiles.get(ChunkPos.asLong(bx >> 4, bz >> 4));
               int at = (bz & 15) * 16 + (bx & 15);
               image.setPixelRGBA(x, z, tile == null ? -14341328 : tile.colors()[at]);
               this.heights[z * 1024 + x] = tile == null ? Integer.MIN_VALUE : tile.heights()[at];
            }
         }

         this.terrainDirty = true;
      }
   }

   void terrain(CompoundTag t) {
      if (t.contains("Error")) {
         this.status = t.getString("Error");
      } else {
         int[] colors = t.getIntArray("Colors");
         int[] values = t.getIntArray("Heights");
         if (colors.length == 256 && values.length == 256) {
            this.mapRevision++;
            int x = t.getInt("ChunkX");
            int z = t.getInt("ChunkZ");
            this.tiles.put(ChunkPos.asLong(x, z), new ManualPlannerScreen.MapTile(colors, values));
            if (this.tiles.size() > 60000) {
               LocalPlayer player = Minecraft.getInstance().player;
               this.tiles.keySet().removeIf(key -> {
                  ChunkPos c = new ChunkPos(key);
                  return Math.hypot((double)(c.x * 16 + 8) - player.getX(), (double)(c.z * 16 + 8) - player.getZ()) > 2080.0;
               });
            }

            this.mapNodes.entrySet().removeIf(e -> {
               BlockPos pos = BlockPos.of(e.getKey());
               return pos.getX() >> 4 == x && pos.getZ() >> 4 == z;
            });

            for (Tag item : t.getList("Nodes", 10)) {
               CompoundTag n = (CompoundTag)item;
               this.mapNodes.put(n.getLong("Pos"), n);
            }

            this.loadedTiles = t.getInt("Loaded");
            this.totalTiles = t.getInt("Total");
            this.paintTile(x, z);
         }
      }
   }

   private void paintTile(int cx, int cz) {
      if (this.terrain != null) {
         ManualPlannerScreen.MapTile tile = this.tiles.get(ChunkPos.asLong(cx, cz));
         NativeImage image = this.terrain.getPixels();
         boolean changed = false;

         for (int z = Math.max(0, Math.floorDiv(cz * 16 - this.terrainZ + this.terrainStep - 1, this.terrainStep));
            z < 1024 && this.terrainZ + z * this.terrainStep < cz * 16 + 16;
            z++
         ) {
            for (int x = Math.max(0, Math.floorDiv(cx * 16 - this.terrainX + this.terrainStep - 1, this.terrainStep));
               x < 1024 && this.terrainX + x * this.terrainStep < cx * 16 + 16;
               x++
            ) {
               int i = (this.terrainZ + z * this.terrainStep & 15) * 16 + (this.terrainX + x * this.terrainStep & 15);
               image.setPixelRGBA(x, z, tile.colors()[i]);
               this.heights[z * 1024 + x] = tile.heights()[i];
               changed = true;
            }
         }

         if (changed) {
            this.terrainDirty = true;
         }
      }
   }

   void release() {
      if (this.texture != null) {
         Minecraft.getInstance().getTextureManager().release(this.texture);
      }

      this.terrain = null;
      this.texture = null;
      this.tiles.clear();
      this.mapNodes.clear();
      this.mapRoads.clear();
      this.pendingRoads.clear();
      this.pendingNodes.clear();
      this.snapshot = null;
      this.hidden.clear();
      this.points.clear();
      this.displayedPoints.clear();
      this.preparedStrips.clear();
      this.preparedHidden = Set.of();
      this.mapRevision++;
      this.preparedRevision = -1L;
   }

   public void tick() {
      if (this.minecraft.level != null && this.terrain != null) {
         double step = (double)(this.keys.contains(340) ? 12 : 5) / this.zoom;
         if (this.keys.contains(87)) {
            this.cz -= step;
         }

         if (this.keys.contains(83)) {
            this.cz += step;
         }

         if (this.keys.contains(65)) {
            this.cx -= step;
         }

         if (this.keys.contains(68)) {
            this.cx += step;
         }

         double dx = this.cx - this.minecraft.player.getX();
         double dz = this.cz - this.minecraft.player.getZ();
         double distance = Math.hypot(dx, dz);
         if (distance > 2048.0) {
            this.cx = this.minecraft.player.getX() + dx * 2048.0 / distance;
            this.cz = this.minecraft.player.getZ() + dz * 2048.0 / distance;
         }

         int wanted = Math.max(
            1, (int)Math.ceil(Math.max((double)(this.right() - this.left()) / this.zoom, (double)(this.bottom() - this.top()) / this.zoom) / 1024.0)
         );
         if (Math.abs(this.cx - (double)(this.terrainX + 512 * this.terrainStep)) > (double)(192 * this.terrainStep)
            || Math.abs(this.cz - (double)(this.terrainZ + 512 * this.terrainStep)) > (double)(192 * this.terrainStep)
            || wanted != this.terrainStep) {
            this.resetTerrain();
         }

         long end = System.nanoTime() + 3000000L;
         int read = 0;

         while (read++ < 4096 && System.nanoTime() < end) {
            int at = this.terrainCursor++ & 4095;
            int x = (this.terrainX >> 4) + (at & 63);
            int z = (this.terrainZ >> 4) + (at >> 6);
            long key = ChunkPos.asLong(x, z);
            if (!this.tiles.containsKey(key)) {
               LevelChunk chunk = this.minecraft.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
               if (chunk != null) {
                  int[] colors = new int[256];
                  int[] height = new int[256];

                  for (int j = 0; j < 16; j++) {
                     for (int i = 0; i < 16; i++) {
                        int y = chunk.getHeight(Types.WORLD_SURFACE, i, j);
                        BlockPos pos = new BlockPos(x * 16 + i, y, z * 16 + j);
                        int color = chunk.getBlockState(pos).getMapColor(this.minecraft.level, pos).col;
                        if (color == 0) {
                           color = 7829367;
                        }

                        int north = chunk.getHeight(Types.WORLD_SURFACE, i, Math.max(0, j - 1));
                        int shade = y > north ? 255 : (y < north ? 175 : 220);
                        colors[j * 16 + i] = 0xFF000000
                           | (color & 0xFF) * shade / 255 << 16
                           | (color >> 8 & 0xFF) * shade / 255 << 8
                           | (color >> 16 & 0xFF) * shade / 255;
                        height[j * 16 + i] = y + 1;
                     }
                  }

                  this.tiles.put(key, new ManualPlannerScreen.MapTile(colors, height));
                  this.paintTile(x, z);
               }
            }
         }
      }
   }

   private RoadGeometry.V terrain(double x, double y) {
      int bx = (int)Math.floor(this.wx(x));
      int bz = (int)Math.floor(this.wz(y));
      ManualPlannerScreen.MapTile tile = this.tiles.get(ChunkPos.asLong(bx >> 4, bz >> 4));
      return tile == null ? null : new RoadGeometry.V(this.wx(x), (double)tile.heights()[(bz & 15) * 16 + (bx & 15)], this.wz(y));
   }

   private boolean nodeVisible(BlockPos pos) {
      boolean linked = false;

      for (RoadIndex.Built b : this.mapRoads.values()) {
         if (b.record.a().equals(pos) || b.record.b().equals(pos)) {
            linked = true;
            if (!this.hidden.contains(b.record.id())) {
               return true;
            }
         }
      }

      return !linked;
   }

   private void collect() {
      if (this.preparedRevision != this.mapRevision
         || this.preparedCx != this.cx
         || this.preparedCz != this.cz
         || this.preparedZoom != this.zoom
         || this.preparedWidth != this.width
         || this.preparedHeight != this.height
         || !this.preparedHidden.equals(this.hidden)) {
         this.preparedRevision = -1L;
         this.collectPoints();
         this.preparedStrips.clear();
         this.displayedPoints.clear();

         for (RoadIndex.Built b : this.mapRoads.values()) {
            if (!this.hidden.contains(b.record.id()) && this.visible(b.mesh)) {
               List<RoadGeometry.Sample> samples = b.mesh.samples();

               for (int i = 1; i < samples.size(); i++) {
                  this.preparedStrips.add(new ManualPlannerScreen.Strip(b, samples.get(i - 1), samples.get(i)));
               }
            }
         }

         this.preparedStrips.sort(Comparator.comparingDouble(ManualPlannerScreen.Strip::height).thenComparing(qx -> qx.road().record.id()));

         for (ManualPlannerScreen.Point p : this.points) {
            RoadIndex.Built cover = this.road(this.sx(p.at().x()), this.sy(p.at().z()));
            if (cover != null) {
               RoadQueries.Projection q = RoadQueries.horizontal(cover.mesh, p.at());
               if (q.horizontalDistance() < q.sample().halfWidth() && q.sample().center().y() > p.at().y() + 0.1) {
                  continue;
               }
            }

            this.displayedPoints.add(p);
         }

         this.preparedCx = this.cx;
         this.preparedCz = this.cz;
         this.preparedZoom = this.zoom;
         this.preparedWidth = this.width;
         this.preparedHeight = this.height;
         this.preparedHidden = Set.copyOf(this.hidden);
         this.preparedRevision = this.mapRevision;
      }
   }

   private void collectPoints() {
      this.points.clear();
      HashSet<BlockPos> nodes = new HashSet<>();

      for (Tag value : this.mapNodes.values()) {
         CompoundTag t = (CompoundTag)value;
         BlockPos pos = BlockPos.of(t.getLong("Pos"));
         if (this.nodeVisible(pos)) {
            nodes.add(pos);
            this.points.add(new ManualPlannerScreen.Point("node", null, null, pos, RoadRecord.readNode(t.getCompound("Node")).position()));
         }
      }

      for (RoadIndex.Built b : this.mapRoads.values()) {
         RoadRecord r = b.record;
         if (!this.hidden.contains(b.record.id()) && this.visible(b.mesh)) {
            if (r.railway()) {
               for (boolean first : new boolean[]{true, false}) {
                  this.points.add(new ManualPlannerScreen.Point("rail", r.id(), null, null, (first ? r.start() : r.end()).position()));
               }
            } else {
               if (!r.settings().style().ramp() && LaneTopology.metadata(r).link() == null) {
                  for (boolean first : new boolean[]{true, false}) {
                     BlockPos pos = first ? r.a() : r.b();
                     if (nodes.add(pos)) {
                        this.points.add(new ManualPlannerScreen.Point("node", null, null, pos, (first ? r.start() : r.end()).position()));
                     }
                  }
               }

               for (LanePoints.Point p : LaneTopology.visiblePoints(r)) {
                  this.points.add(new ManualPlannerScreen.Point("lane", r.id(), p.id(), null, p.position()));
               }

               HashSet<UUID> controls = new HashSet<>();

               for (RoadProfile.Control p : r.settings().options().routing().controls()) {
                  controls.add(p.id());
                  this.points.add(new ManualPlannerScreen.Point("control", r.id(), p.id(), null, p.position()));
               }

               for (RoadAttachments.Point p : r.settings().options().attachments().points()) {
                  if (!controls.contains(p.id())) {
                     this.points.add(new ManualPlannerScreen.Point(p.elevated() ? "elevated" : "attached", r.id(), p.id(), null, p.position()));
                  }
               }
            }
         }
      }
   }

   private boolean visible(RoadGeometry.Mesh mesh) {
      return this.sx(mesh.max().x()) >= (double)this.left()
         && this.sx(mesh.min().x()) <= (double)this.right()
         && this.sy(mesh.max().z()) >= (double)this.top()
         && this.sy(mesh.min().z()) <= (double)this.bottom();
   }

   private ManualPlannerScreen.Point pick(double x, double y) {
      ManualPlannerScreen.Point best = null;
      double distance = 8.0;

      for (ManualPlannerScreen.Point p : this.points) {
         double d = Math.hypot(this.sx(p.at().x()) - x, this.sy(p.at().z()) - y);
         if (d < 8.0 && (best == null || p.at().y() > best.at().y() + 0.01 || Math.abs(p.at().y() - best.at().y()) <= 0.01 && d < distance)) {
            RoadIndex.Built cover = this.road(x, y);
            if (cover != null) {
               RoadQueries.Projection q = RoadQueries.horizontal(cover.mesh, p.at());
               if (q.horizontalDistance() < q.sample().halfWidth() && q.sample().center().y() > p.at().y() + 0.1) {
                  continue;
               }
            }

            distance = d;
            best = p;
         }
      }

      return best;
   }

   private RoadIndex.Built road(double x, double y) {
      RoadGeometry.V hit = new RoadGeometry.V(this.wx(x), 0.0, this.wz(y));
      RoadIndex.Built best = null;
      double height = -Double.MAX_VALUE;
      double distance = Double.MAX_VALUE;

      for (RoadIndex.Built b : this.mapRoads.values()) {
         if (!this.hidden.contains(b.record.id()) && this.visible(b.mesh)) {
            RoadQueries.Projection q = RoadQueries.horizontal(b.mesh, hit);
            double h = q.sample().center().y();
            if (q.horizontalDistance() <= Math.max(q.sample().halfWidth(), 5.0 / this.zoom)
               && (
                  h > height + 0.01
                     || Math.abs(h - height) <= 0.01
                        && (
                           q.horizontalDistance() < distance
                              || q.horizontalDistance() == distance && (best == null || b.record.id().compareTo(best.record.id()) < 0)
                        )
               )) {
               distance = q.horizontalDistance();
               height = h;
               best = b;
            }
         }
      }

      return best;
   }

   private void target(CompoundTag t, ManualPlannerScreen.Point point, UUID road, RoadGeometry.V at) {
      t.putDouble("X", at.x());
      t.putDouble("Y", at.y());
      t.putDouble("Z", at.z());
      if (point != null) {
         t.putString("PointKind", point.kind());
         if (point.pos() != null) {
            t.putLong("Pos", point.pos().asLong());
         }

         if (point.id() != null) {
            t.putUUID("Point", point.id());
            t.putString("PointKind", point.kind());
         }

         if (point.road() != null) {
            t.putUUID("Road", point.road());
         }
      } else if (road != null) {
         t.putUUID("Road", road);
      }
   }

   private void send(String action, ManualPlannerScreen.Point point, UUID id, double mx, double my) {
      CompoundTag t = this.command(action);
      if (point != null || id != null || action.equals("manualClick")) {
         RoadGeometry.V at = point != null ? point.at() : this.terrain(mx, my);
         if (id != null && point == null) {
            RoadIndex.Built b = this.mapRoads.get(id);
            if (b != null) {
               RoadQueries.Projection q = RoadQueries.horizontal(b.mesh, new RoadGeometry.V(this.wx(mx), 0.0, this.wz(my)));
               at = new RoadGeometry.V(this.wx(mx), q.sample().center().y(), this.wz(my));
            }
         }

         if (at == null) {
            this.status = "该位置尚未加载，请靠近后重试";
            return;
         }

         this.target(t, point, id, at);
      }

      t.putBoolean("Ctrl", hasControlDown());
      t.putBoolean("Alt", InputConstants.isKeyDown(this.minecraft.getWindow().getWindow(), 342));
      ClientRoads.sendAction(t);
   }

   public boolean mouseClicked(double x, double y, int button) {
      if (this.menuX >= 0) {
         if (button == 0
            && x >= (double)this.menuX
            && x < (double)(this.menuX + 122)
            && y >= (double)this.menuY
            && y < (double)(this.menuY + this.menuRows() * 22)) {
            int item = (int)(y - (double)this.menuY) / 22;
            this.menuX = -1;
            if (item == 0) {
               if (this.selected != null && !this.selected.kind().equals("rail")) {
                  this.minecraft.setScreen(new ManualPlannerScreen.PointDetails(this.selected));
               } else {
                  this.send("manualDetails", this.selected, this.selectedRoad, x, y);
               }
            }

            if (item == 1) {
               this.remove();
            }

            if (item == 2 && this.selectedRoad != null) {
               this.hidden.add(this.selectedRoad);
               this.selectedRoad = null;
               this.selected = null;
               this.status = "已暂时隐藏；按 H 恢复全部";
            }

            if (item == 2 && this.selectedRoad == null && this.selected != null) {
               this.moving = true;
               this.movingAt = this.selected.at();
               this.axis = 0;
               this.confirmAt = 0L;
               this.status = "拖动 X / Z 箭头；双击左键确认，右键取消";
            }

            return true;
         }

         this.menuX = -1;
      }

      if (this.moving && this.map(x, y)) {
         if (button == 1) {
            this.moving = false;
            this.confirmAt = 0L;
            return true;
         } else if (button != 0) {
            return true;
         } else {
            double px = this.sx(this.movingAt.x());
            double py = this.sy(this.movingAt.z());
            if (Math.abs(y - py) < 7.0 && x > px + 8.0 && x < px + 55.0) {
               this.axis = 1;
               this.confirmAt = 0L;
               return true;
            } else if (Math.abs(x - px) < 7.0 && y > py + 8.0 && y < py + 55.0) {
               this.axis = 2;
               this.confirmAt = 0L;
               return true;
            } else {
               long now = Util.getMillis();
               if (this.confirmAt != 0L && now - this.confirmAt <= 300L && Math.hypot(x - this.confirmX, y - this.confirmY) <= 5.0) {
                  this.move(this.movingAt);
                  this.moving = false;
                  this.confirmAt = 0L;
               } else {
                  this.confirmAt = now;
                  this.confirmX = x;
                  this.confirmY = y;
               }

               return true;
            }
         }
      } else if (super.mouseClicked(x, y, button)) {
         return true;
      } else if (!this.map(x, y)) {
         return false;
      } else {
         this.collect();
         ManualPlannerScreen.Point previousPoint = this.selected;
         UUID previousRoad = this.selectedRoad;
         this.selected = this.pick(x, y);
         RoadIndex.Built road = this.selected == null ? this.road(x, y) : null;
         this.selectedRoad = this.selected != null && this.selected.kind().equals("rail") ? this.selected.road() : (road == null ? null : road.record.id());
         this.openSelected = this.selected != null && previousPoint != null && same(this.selected, previousPoint)
            || this.selected == null && this.selectedRoad != null && this.selectedRoad.equals(previousRoad);
         if (button == 0 && this.selected != null && this.selected.kind().equals("rail") && this.tool != 1) {
            this.status = "铁路端点 · 使用指针再次单击打开编辑";
            return true;
         } else if (button != 1) {
            if (button == 0) {
               this.pressed = true;
               this.dragged = false;
               this.pressX = x;
               this.pressY = y;
               this.pressCenterX = this.cx;
               this.pressCenterZ = this.cz;
               this.pressAt = (double)Util.getMillis();
               return true;
            } else {
               return false;
            }
         } else {
            if (this.selected != null || this.selectedRoad != null) {
               this.menuX = Math.min((int)x, this.right() - 122);
               this.menuY = Math.min((int)y, this.bottom() - this.menuRows() * 22);
            }

            return true;
         }
      }
   }

   public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
      if (this.moving && this.axis != 0) {
         this.movingAt = new RoadGeometry.V(this.axis == 1 ? this.wx(x) : this.movingAt.x(), this.movingAt.y(), this.axis == 2 ? this.wz(y) : this.movingAt.z());
         return true;
      } else if (!this.pressed
         || button != 0
         || !this.dragged && (!((double)Util.getMillis() - this.pressAt > 160.0) || !(Math.hypot(x - this.pressX, y - this.pressY) > 3.0))) {
         return super.mouseDragged(x, y, button, dx, dy);
      } else {
         this.dragged = true;
         this.cx = this.pressCenterX - (x - this.pressX) / this.zoom;
         this.cz = this.pressCenterZ - (y - this.pressY) / this.zoom;
         return true;
      }
   }

   public boolean mouseReleased(double x, double y, int button) {
      if (this.moving && this.axis != 0) {
         this.axis = 0;
         return true;
      } else if (this.pressed && button == 0) {
         this.pressed = false;
         if (this.dragged && this.tool == 1) {
            this.selected = null;
            this.selectedRoad = null;
            this.openSelected = false;
         }

         if (!this.dragged && this.map(x, y)) {
            if (this.tool == 1 && !this.openSelected) {
               this.status = "已选中，再次单击打开设置；右键打开菜单";
            } else {
               this.send("manualClick", this.selected, this.selectedRoad, x, y);
            }
         }

         return true;
      } else {
         return super.mouseReleased(x, y, button);
      }
   }

   public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
      if (hasControlDown() && this.tool == 5) {
         this.cycleArms(scrollY);
         return true;
      } else if (!this.map(x, y)) {
         return false;
      } else {
         double beforeX = this.wx(x);
         double beforeZ = this.wz(y);
         this.zoom = Math.max(this.minimumZoom(), Math.min(24.0, this.zoom * Math.pow(1.2, scrollY)));
         this.cx = this.cx + (beforeX - this.wx(x));
         this.cz = this.cz + (beforeZ - this.wz(y));
         return true;
      }
   }

   private double minimumZoom() {
      return Math.max(0.025, (double)Math.min(this.right() - this.left(), this.bottom() - this.top()) / 4505.6);
   }

   void cycleArms(double delta) {
      if (delta != 0.0) {
         this.arms = 3 + Math.floorMod(this.arms - 3 + (delta > 0.0 ? 1 : -1), 4);
         this.choose(this.tool);
      }
   }

   public boolean keyPressed(int key, int scan, int modifiers) {
      if (key >= 49 && key <= 55) {
         this.choose(key - 49 + 1);
         return true;
      } else if (!hasControlDown() || key != 90 && key != 89) {
         if (key == 72) {
            this.hidden.clear();
            this.status = "已恢复全部道路和铁路路基";
            return true;
         } else if (key == 261) {
            this.remove();
            return true;
         } else if (ManualPlannerClient.KEY.matches(key, scan)) {
            this.onClose();
            return true;
         } else if (Set.of(87, 65, 83, 68, 340).contains(key)) {
            this.keys.add(key);
            return true;
         } else {
            return super.keyPressed(key, scan, modifiers);
         }
      } else {
         ClientRoads.sendAction(this.command(key == 90 ? "manualUndo" : "manualRedo"));
         return true;
      }
   }

   public boolean keyReleased(int key, int scan, int modifiers) {
      this.keys.remove(key);
      return super.keyReleased(key, scan, modifiers);
   }

   private void remove() {
      if (this.selected != null) {
         this.send("manualDelete", this.selected, null, this.sx(this.selected.at().x()), this.sy(this.selected.at().z()));
      } else if (this.selectedRoad != null) {
         RoadIndex.Built b = this.mapRoads.get(this.selectedRoad);
         if (b != null) {
            this.send("manualDelete", null, this.selectedRoad, this.sx(b.mesh.first().center().x()), this.sy(b.mesh.first().center().z()));
         }
      }
   }

   private void move(RoadGeometry.V at) {
      CompoundTag t = this.command("manualMove");
      this.target(t, this.selected, null, at);
      ClientRoads.sendAction(t);
   }

   public void onClose() {
      ManualPlannerClient.close();
   }

   public void removed() {
      this.keys.clear();
      this.pressed = false;
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
   }

   public void render(GuiGraphics g, int mx, int my, float dt) {
      super.renderBackground(g, mx, my, dt);
      g.fill(0, 0, this.width, this.height, -15459033);
      g.drawString(this.font, "SR · 手动道路规划", 10, 13, -854790, false);
      g.drawString(this.font, "撤销 " + this.state.getInt("Undo") + "  /  重做 " + this.state.getInt("Redo"), this.left() + 8, 14, -6574913, false);
      g.fill(this.left(), this.top(), this.right(), this.bottom(), -14341328);
      g.enableScissor(this.left(), this.top(), this.right(), this.bottom());
      if (this.terrainDirty && this.terrain != null) {
         this.terrain.upload();
         this.terrainDirty = false;
      }

      if (this.texture != null) {
         int x = (int)Math.floor(this.sx((double)this.terrainX));
         int y = (int)Math.floor(this.sy((double)this.terrainZ));
         int size = (int)Math.ceil((double)(1024 * this.terrainStep) * this.zoom);
         g.blit(this.texture, x, y, size, size, 0.0F, 0.0F, 1024, 1024, 1024, 1024);
      }

      this.drawRoads(g);
      this.drawPreview(g);
      this.collect();

      for (ManualPlannerScreen.Point p : this.displayedPoints) {
         int x = (int)this.sx(p.at().x());
         int y = (int)this.sy(p.at().z());
         String coords = p.kind();

         int color = switch (coords) {
            case "rail" -> -3562497;
            case "lane" -> -11683585;
            case "control" -> -2197761;
            case "attached" -> -35471;
            case "elevated" -> -13382401;
            default -> -11413;
         };
         g.fill(x - 2, y - 2, x + 3, y + 3, color);
         if (p.kind().equals("rail")) {
            g.renderOutline(x - 3, y - 3, 7, 7, -8890205);
         }

         if (this.selected != null && same(this.selected, p)) {
            g.renderOutline(x - 5, y - 5, 11, 11, -1);
         }
      }

      CompoundTag selection = this.state.getCompound("Selection");
      HashSet<Long> selectedNodes = new HashSet<>();
      if (selection.contains("Start")) {
         selectedNodes.add(selection.getLong("Start"));
      }

      for (long pos : selection.getLongArray("Points")) {
         selectedNodes.add(pos);
      }

      for (ManualPlannerScreen.Point p : this.points) {
         if (p.pos() != null && selectedNodes.contains(p.pos().asLong()) || selection.hasUUID("SidePoint") && selection.getUUID("SidePoint").equals(p.id())) {
            g.renderOutline((int)this.sx(p.at().x()) - 6, (int)this.sy(p.at().z()) - 6, 13, 13, -9109586);
         }
      }

      int px = (int)this.sx(this.minecraft.player.getX());
      int py = (int)this.sy(this.minecraft.player.getZ());
      g.fill(px - 3, py - 3, px + 4, py + 4, -1);
      g.drawString(this.font, "你", px + 6, py - 3, -1, false);
      if (this.moving) {
         this.drawMoveAxes(g, mx, my);
      }

      g.disableScissor();
      g.drawString(this.font, this.font.plainSubstrByWidth(this.status, this.width - this.left() - 15), this.left() + 4, this.height - 33, -2759954, false);
      RoadGeometry.V at = this.map((double)mx, (double)my) ? this.terrain((double)mx, (double)my) : null;
      String coords = at == null
         ? "地表加载中 " + this.loadedTiles + " / " + this.totalTiles
         : String.format(Locale.ROOT, "X %.2f  Y %.1f  Z %.2f", at.x(), at.y(), at.z());
      g.drawString(this.font, coords, this.width - this.font.width(coords) - 12, this.height - 17, -6309428, false);
      this.resumePreview.visible = ManualPlannerClient.hasMapPreview();
      this.resumePreview.active = this.resumePreview.visible;
      if (this.height > 330 && !this.resumePreview.visible) {
         g.drawString(this.font, "工具 " + this.tool + (this.tool == 5 ? " · " + this.arms + " 向" : ""), 10, this.height - 54, -9109586, false);
      }

      super.render(g, mx, my, dt);
      if (this.menuX >= 0) {
         g.fill(this.menuX, this.menuY, this.menuX + 122, this.menuY + this.menuRows() * 22, -182374078);
         String[] rows = this.selectedRoad != null ? new String[]{"详细设置", "删除", "暂时隐藏（H 恢复）", "取消"} : new String[]{"详细设置 / XYZ", "删除", "移动", "取消"};

         for (int i = 0; i < rows.length; i++) {
            if (mx >= this.menuX && mx < this.menuX + 122 && my >= this.menuY + i * 22 && my < this.menuY + (i + 1) * 22) {
               g.fill(this.menuX + 1, this.menuY + i * 22, this.menuX + 121, this.menuY + (i + 1) * 22, -13347989);
            }

            g.drawString(this.font, rows[i], this.menuX + 9, this.menuY + i * 22 + 7, -1, false);
         }
      }

      if (this.help) {
         String[] lines = new String[]{
            "W A S D 平移；滚轮缩放；长按左键拖动",
            "主键盘 1–7 切换工具（不含小键盘）",
            "Delete 删除；Ctrl Z 撤销；Ctrl Y 重做",
            "道路：A → 经过点/控制点 → B",
            "Ctrl 经过点在路上；左 Alt 控制点影响弯曲",
            "同一路段只能使用其中一种点",
            "立交：Ctrl＋滚轮选择 3–6 向",
            "右键点：详细设置、删除、XZ 移动",
            "拖动箭头后双击确认；右键取消移动",
            "高架：深蓝/深紫；地下：灰/浅灰",
            "实景预览后按 P 返回设置；Esc 返回地图",
            "设置加载半径；地表保留至本次规划关闭",
            "操作半径2048格；H 恢复全部隐藏道路"
         };
         int x = Math.max(this.left() + 8, this.width - 365);
         int y = 38;
         g.fill(x - 8, y - 8, this.width - 8, y + lines.length * 16 + 8, -183098061);

         for (int i = 0; i < lines.length; i++) {
            g.drawString(this.font, lines[i], x, y + i * 16, -1971472, false);
         }
      }
   }

   private void drawMoveAxes(GuiGraphics g, int mx, int my) {
      int x = (int)this.sx(this.movingAt.x());
      int y = (int)this.sy(this.movingAt.z());
      int red = this.axis != 1 && (Math.abs(my - y) >= 7 || mx <= x + 8 || mx >= x + 55) ? -1083025 : -20313;
      int blue = this.axis != 2 && (Math.abs(mx - x) >= 7 || my <= y + 8 || my >= y + 55) ? -9057041 : -4133377;
      g.fill(x + 6, y - 2, x + 46, y + 3, -1340725965);
      g.fill(x - 2, y + 6, x + 3, y + 46, -1340725965);
      g.fill(x + 6, y, x + 46, y + 1, red);
      g.fill(x, y + 6, x + 1, y + 46, blue);

      for (int i = 0; i < 6; i++) {
         g.fill(x + 46 + i, y - 5 + i, x + 47 + i, y + 6 - i, red);
         g.fill(x - 5 + i, y + 46 + i, x + 6 - i, y + 47 + i, blue);
      }

      g.fill(x - 5, y - 5, x + 6, y + 6, -14666939);
      g.renderOutline(x - 5, y - 5, 11, 11, -2298889);
      g.fill(x - 1, y - 1, x + 2, y + 2, -2298889);
      g.fill(x + 55, y - 7, x + 68, y + 7, -451270088);
      g.drawString(this.font, "X", x + 59, y - 3, red, false);
      g.fill(x - 7, y + 55, x + 7, y + 69, -451270088);
      g.drawString(this.font, "Z", x - 3, y + 59, blue, false);
   }

   private static boolean same(ManualPlannerScreen.Point a, ManualPlannerScreen.Point b) {
      return Objects.equals(a.kind(), b.kind()) && Objects.equals(a.id(), b.id()) && Objects.equals(a.road(), b.road()) && Objects.equals(a.pos(), b.pos());
   }

   private int menuRows() {
      return 4;
   }

   static int roadColor(RoadIndex.Built b, RoadGeometry.Sample at) {
      boolean rail = b.record.railway();
      if (RoadStreetscape.underground(b.mesh, at.distance())) {
         return rail ? -1842197 : -7630697;
      } else if (RoadStreetscape.raised(b.mesh, at)) {
         return rail ? -11456145 : -14463354;
      } else {
         return rail ? -7309907 : (b.record.toll() != null ? -3564440 : (b.record.junction() != null ? -7688767 : -10322799));
      }
   }

   private void drawRoads(GuiGraphics g) {
      this.collect();
      List<ManualPlannerScreen.Strip> strips = this.preparedStrips;
      g.flush();
      RenderSystem.disableCull();
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.setShader(GameRenderer::getPositionColorShader);
      BufferBuilder buffer = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
      Matrix4f pose = g.pose().last().pose();

      for (ManualPlannerScreen.Strip strip : strips) {
         RoadIndex.Built b = strip.road();
         RoadGeometry.Sample a = strip.a();
         RoadGeometry.Sample c = strip.b();
         int color = b.record.id().equals(this.selectedRoad) ? -10389 : roadColor(b, a);
         RoadGeometry.V[] quad = new RoadGeometry.V[]{a.at(a.halfWidth(), 0.0), a.at(-a.halfWidth(), 0.0), c.at(-c.halfWidth(), 0.0), c.at(c.halfWidth(), 0.0)};

         for (RoadGeometry.V p : quad) {
            buffer.addVertex(pose, (float)this.sx(p.x()), (float)this.sy(p.z()), 0.0F).setColor(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, 255);
         }
      }

      BufferUploader.drawWithShader(buffer.buildOrThrow());
      RenderSystem.disableBlend();
      RenderSystem.enableCull();
   }

   private List<RoadGeometry.Mesh> previewMeshes() {
      if (ManualPlannerClient.hasMapPreview() && !ClientRoads.conflictPreview) {
         ArrayList<RoadGeometry.Mesh> meshes = new ArrayList<>(ClientRoads.nodePreviews);
         if (ClientRoads.preview != null && !meshes.contains(ClientRoads.preview)) {
            meshes.add(ClientRoads.preview);
         }

         return meshes;
      } else {
         return List.of();
      }
   }

   private void drawPreview(GuiGraphics g) {
      List<RoadGeometry.Mesh> meshes = this.previewMeshes();
      if (!meshes.isEmpty()) {
         g.flush();
         RenderSystem.disableCull();
         RenderSystem.enableBlend();
         RenderSystem.defaultBlendFunc();
         RenderSystem.setShader(GameRenderer::getPositionColorShader);
         BufferBuilder buffer = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
         Matrix4f pose = g.pose().last().pose();

         for (RoadGeometry.Mesh mesh : meshes) {
            if (this.visible(mesh)) {
               List<RoadGeometry.Sample> samples = mesh.samples();

               for (int i = 1; i < samples.size(); i++) {
                  RoadGeometry.Sample a = samples.get(i - 1);
                  RoadGeometry.Sample b = samples.get(i);

                  for (RoadGeometry.V p : List.of(a.at(a.halfWidth(), 0.0), a.at(-a.halfWidth(), 0.0), b.at(-b.halfWidth(), 0.0), b.at(b.halfWidth(), 0.0))) {
                     buffer.addVertex(pose, (float)this.sx(p.x()), (float)this.sy(p.z()), 0.0F).setColor(67, 221, 136, 235);
                  }
               }
            }
         }

         BufferUploader.drawWithShader(buffer.buildOrThrow());
         RenderSystem.disableBlend();
         RenderSystem.enableCull();
         g.drawString(this.font, "绿色：待建预览", this.left() + 8, this.top() + 8, -9109586, false);
      }
   }

   private static record MapTile(int[] colors, int[] heights) {
   }

   final class PlannerSettings extends Screen {
      private int radius = ManualPlannerScreen.this.state.contains("Radius") ? ManualPlannerScreen.this.state.getInt("Radius") : 256;
      private int steps = ManualPlannerScreen.this.state.contains("MaxUndo") ? ManualPlannerScreen.this.state.getInt("MaxUndo") : 30;

      PlannerSettings() {
         super(Component.literal("道路规划设置"));
      }

      protected void init() {
         int x = this.width / 2 - 140;
         int y = this.height / 2 - 55;
         this.addRenderableWidget(new ManualPlannerScreen.PlannerSettings.SettingSlider(x, y, true, this.radius, 16, 2048));
         this.addRenderableWidget(new ManualPlannerScreen.PlannerSettings.SettingSlider(x, y + 32, false, this.steps, 1, 30));
         this.addRenderableWidget(Button.builder(Component.literal("保存"), b -> {
            CompoundTag t = ManualPlannerScreen.this.command("manualSettings");
            t.putInt("Radius", this.radius);
            t.putInt("MaxUndo", this.steps);
            ClientRoads.sendAction(t);
            this.onClose();
         }).bounds(x, y + 82, 133, 20).build());
         this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.onClose()).bounds(x + 147, y + 82, 133, 20).build());
         ManualPlannerClient.child(this);
         ClientRoads.beginEditor(this);
      }

      public void onClose() {
         this.minecraft.setScreen(ManualPlannerScreen.this);
         ClientRoads.beginEditor(ManualPlannerScreen.this);
      }

      public boolean isPauseScreen() {
         return false;
      }

      public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
      }

      public void render(GuiGraphics g, int x, int y, float dt) {
         super.renderBackground(g, x, y, dt);
         g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 85, -1);
         g.drawCenteredString(this.font, "地表分批加载并保留到关闭；操作范围为半径2048格", this.width / 2, this.height / 2 + 13, -4206368);
         super.render(g, x, y, dt);
      }

      final class SettingSlider extends AbstractSliderButton {
         private final boolean terrainSetting;
         private final int min;
         private final int max;

         SettingSlider(int x, int y, boolean terrainSetting, int current, int min, int max) {
            super(x, y, 280, 22, Component.empty(), (double)(current - min) / (double)(max - min));
            this.terrainSetting = terrainSetting;
            this.min = min;
            this.max = max;
            this.updateMessage();
         }

         protected void updateMessage() {
            this.setMessage(Component.literal(this.terrainSetting ? "地表加载半径：" + PlannerSettings.this.radius + " 格" : "撤销最大步：" + PlannerSettings.this.steps));
         }

         protected void applyValue() {
            int n = this.min + (int)Math.round(this.value * (double)(this.max - this.min));
            if (this.terrainSetting) {
               PlannerSettings.this.radius = n;
            } else {
               PlannerSettings.this.steps = n;
            }

            this.updateMessage();
         }
      }
   }

   private static record Point(String kind, UUID road, UUID id, BlockPos pos, RoadGeometry.V at) {
   }

   final class PointDetails extends Screen {
      private final ManualPlannerScreen.Point point;
      private final EditBox[] xyz = new EditBox[3];
      private String message = "坐标修改与原有道路校验同时执行";

      void result(boolean success, String text) {
         this.message = text;
         if (success) {
            this.onClose();
            ManualPlannerScreen.this.status = text;
         }
      }

      PointDetails(ManualPlannerScreen.Point p) {
         super(Component.literal("点的详细设置"));
         this.point = p;
      }

      protected void init() {
         int x = this.width / 2 - 140;
         int y = this.height / 2 - 70;
         double[] values = new double[]{this.point.at().x(), this.point.at().y(), this.point.at().z()};

         for (int i = 0; i < 3; i++) {
            this.xyz[i] = new EditBox(this.font, x + 30, y + i * 27, 235, 20, Component.literal("XYZ".substring(i, i + 1)));
            this.xyz[i].setMaxLength(24);
            this.xyz[i].setValue(String.format(Locale.ROOT, "%.3f", values[i]));
            this.addRenderableWidget(this.xyz[i]);
         }

         this.addRenderableWidget(
            Button.builder(
                  Component.literal("保存坐标"),
                  b -> {
                     try {
                        RoadGeometry.V at = new RoadGeometry.V(
                           Double.parseDouble(this.xyz[0].getValue()), Double.parseDouble(this.xyz[1].getValue()), Double.parseDouble(this.xyz[2].getValue())
                        );
                        ManualPlannerScreen.this.selected = this.point;
                        CompoundTag t = ManualPlannerScreen.this.command("manualMove");
                        ManualPlannerScreen.this.target(t, this.point, null, at);
                        ClientRoads.sendAction(t);
                     } catch (IllegalArgumentException var4x) {
                        this.message = "请输入有效坐标";
                     }
                  }
               )
               .bounds(x, y + 89, 87, 20)
               .build()
         );
         this.addRenderableWidget(
            Button.builder(
                  Component.literal("原有设置"),
                  b -> {
                     this.minecraft.setScreen(ManualPlannerScreen.this);
                     ManualPlannerScreen.this.send(
                        "manualDetails", this.point, null, ManualPlannerScreen.this.sx(this.point.at().x()), ManualPlannerScreen.this.sy(this.point.at().z())
                     );
                  }
               )
               .bounds(x + 94, y + 89, 87, 20)
               .build()
         );
         this.addRenderableWidget(Button.builder(Component.literal("返回地图"), b -> this.onClose()).bounds(x + 188, y + 89, 87, 20).build());
         ManualPlannerClient.child(this);
         ClientRoads.beginEditor(this);
      }

      public void onClose() {
         this.minecraft.setScreen(ManualPlannerScreen.this);
         ClientRoads.beginEditor(ManualPlannerScreen.this);
      }

      public boolean isPauseScreen() {
         return false;
      }

      public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
      }

      public void render(GuiGraphics g, int x, int y, float dt) {
         super.renderBackground(g, x, y, dt);
         int left = this.width / 2 - 140;
         int top = this.height / 2 - 70;
         g.drawString(this.font, this.title, left, top - 22, -1, false);

         for (int i = 0; i < 3; i++) {
            g.drawString(this.font, "XYZ".substring(i, i + 1), left, top + i * 27 + 6, -1, false);
         }

         g.drawString(this.font, this.font.plainSubstrByWidth(this.message, 290), left, top + 117, -4666151, false);
         super.render(g, x, y, dt);
      }
   }

   private static record Strip(RoadIndex.Built road, RoadGeometry.Sample a, RoadGeometry.Sample b) {
      double height() {
         return (this.a.center().y() + this.b.center().y()) / 2.0;
      }
   }
}
