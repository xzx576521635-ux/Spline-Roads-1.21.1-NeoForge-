package com.sora.splineroads.world;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSignals;
import com.sora.splineroads.core.JunctionSlips;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RailwayCrossings;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadEndpointSections;
import com.sora.splineroads.core.RoadFurniture;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadLaneLines;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadMedianAnchor;
import com.sora.splineroads.core.RoadPartCompaction;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadPlantings;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadRaster;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadTransitions;
import com.sora.splineroads.core.RoadTunnelFit;
import com.sora.splineroads.core.RoadTunnelSpace;
import com.sora.splineroads.core.RoadsideBypass;
import com.sora.splineroads.core.TollPlanner;
import com.sora.splineroads.core.TollPlaza;
import com.sora.splineroads.core.WeakIdentityCache;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public record RoadRecord(
   UUID id,
   UUID owner,
   BlockPos a,
   BlockPos b,
   RoadGeometry.Node start,
   RoadGeometry.Node end,
   RoadGeometry.Settings settings,
   boolean automatic,
   int clearance,
   List<RoadStructures.Part> structures,
   int endCaps,
   int buildVersion,
   UUID assembly,
   List<RoadGeometry.Sample> alignment,
   RoadFurniture.Phase furniturePhase,
   JunctionPlanner.Ref junction,
   TollPlaza.Spec toll,
   RoadSlicing.Fragment fragment
) {
   private static final WeakIdentityCache<RoadRecord, TollPlanner.Plan> TOLL_PLANS = new WeakIdentityCache<>(
      24, 150000L, p -> p.mesh().samples().size() + p.structures().size() + p.paint().size()
   );
   private static final int MAX_STRUCTURES = 50000;
   private static final WeakIdentityCache<RoadRecord, RoadGeometry.Mesh> RAW_MESHES = new WeakIdentityCache<>(2048, 750000L, m -> m.samples().size());
   private static final WeakIdentityCache<RoadRecord, RoadGeometry.Mesh> SHAPED_MESHES = new WeakIdentityCache<>(2048, 750000L, m -> m.samples().size());

   public RoadRecord(
      UUID id,
      UUID owner,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Node start,
      RoadGeometry.Node end,
      RoadGeometry.Settings settings,
      boolean automatic,
      int clearance,
      List<RoadStructures.Part> structures,
      int endCaps,
      int buildVersion,
      UUID assembly,
      List<RoadGeometry.Sample> alignment,
      RoadFurniture.Phase phase,
      JunctionPlanner.Ref junction,
      TollPlaza.Spec toll
   ) {
      this(id, owner, a, b, start, end, settings, automatic, clearance, structures, endCaps, buildVersion, assembly, alignment, phase, junction, toll, null);
   }

   public RoadRecord(
      UUID id,
      UUID owner,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Node start,
      RoadGeometry.Node end,
      RoadGeometry.Settings settings,
      boolean automatic,
      int clearance,
      List<RoadStructures.Part> structures,
      int endCaps,
      int buildVersion,
      UUID assembly,
      List<RoadGeometry.Sample> alignment,
      RoadFurniture.Phase phase,
      JunctionPlanner.Ref junction
   ) {
      this(id, owner, a, b, start, end, settings, automatic, clearance, structures, endCaps, buildVersion, assembly, alignment, phase, junction, null);
   }

   public RoadRecord(
      UUID id,
      UUID owner,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Node start,
      RoadGeometry.Node end,
      RoadGeometry.Settings settings,
      boolean automatic,
      int clearance,
      List<RoadStructures.Part> structures,
      int endCaps,
      int buildVersion,
      UUID assembly,
      List<RoadGeometry.Sample> alignment,
      RoadFurniture.Phase phase
   ) {
      this(id, owner, a, b, start, end, settings, automatic, clearance, structures, endCaps, buildVersion, assembly, alignment, phase, null);
   }

   public RoadRecord(
      UUID id,
      UUID owner,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Node start,
      RoadGeometry.Node end,
      RoadGeometry.Settings settings,
      boolean automatic,
      int clearance,
      List<RoadStructures.Part> structures,
      int endCaps,
      int buildVersion,
      UUID assembly,
      List<RoadGeometry.Sample> alignment
   ) {
      this(id, owner, a, b, start, end, settings, automatic, clearance, structures, endCaps, buildVersion, assembly, alignment, null);
   }

   public RoadRecord(
      UUID id,
      UUID owner,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Node start,
      RoadGeometry.Node end,
      RoadGeometry.Settings settings,
      boolean automatic,
      int clearance,
      List<RoadStructures.Part> structures,
      int caps,
      int version
   ) {
      this(id, owner, a, b, start, end, settings, automatic, clearance, structures, caps, version, null, List.of());
   }

   public RoadRecord(
      UUID id,
      UUID owner,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Node start,
      RoadGeometry.Node end,
      RoadGeometry.Settings settings,
      boolean automatic,
      int clearance,
      List<RoadStructures.Part> structures
   ) {
      this(id, owner, a, b, start, end, settings, automatic, clearance, structures, 0, 17);
   }

   public RoadRecord(
      UUID id,
      UUID owner,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Node start,
      RoadGeometry.Node end,
      RoadGeometry.Settings settings,
      boolean automatic,
      int clearance
   ) {
      this(id, owner, a, b, start, end, settings, automatic, clearance, List.of());
   }

   public RoadRecord(
      UUID id,
      UUID owner,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Node start,
      RoadGeometry.Node end,
      RoadGeometry.Settings settings,
      boolean automatic,
      int clearance,
      List<RoadStructures.Part> structures,
      int endCaps,
      int buildVersion,
      UUID assembly,
      List<RoadGeometry.Sample> alignment,
      RoadFurniture.Phase furniturePhase,
      JunctionPlanner.Ref junction,
      TollPlaza.Spec toll,
      RoadSlicing.Fragment fragment
   ) {
      if (structures.size() > 50000) {
         throw new IllegalArgumentException(RoadPartCompaction.budgetMessage(id, structures, 50000));
      } else {
         structures = junction == null ? List.copyOf(structures) : JunctionSignals.refreshHeads(structures, junction.get().structures());
         if (structures.size() > 50000) {
            throw new IllegalArgumentException(RoadPartCompaction.budgetMessage(id, structures, 50000));
         } else {
            alignment = List.copyOf(alignment);
            this.id = id;
            this.owner = owner;
            this.a = a;
            this.b = b;
            this.start = start;
            this.end = end;
            this.settings = settings;
            this.automatic = automatic;
            this.clearance = clearance;
            this.structures = structures;
            this.endCaps = endCaps;
            this.buildVersion = buildVersion;
            this.assembly = assembly;
            this.alignment = alignment;
            this.furniturePhase = furniturePhase;
            this.junction = junction;
            this.toll = toll;
            this.fragment = fragment;
         }
      }
   }

   public RoadRecord(UUID id, UUID owner, BlockPos a, BlockPos b, RoadGeometry.Node start, RoadGeometry.Node end, RoadGeometry.Settings settings) {
      this(id, owner, a, b, start, end, settings, false, 0);
   }

   public RoadRecord fragment(RoadSlicing.Fragment value) {
      return new RoadRecord(
         this.id,
         this.owner,
         this.a,
         this.b,
         this.start,
         this.end,
         this.settings,
         this.automatic,
         this.clearance,
         this.structures,
         this.endCaps,
         this.buildVersion,
         this.assembly,
         this.alignment,
         this.furniturePhase,
         this.junction,
         this.toll,
         value
      );
   }

   public RoadRecord toll(TollPlaza.Spec spec) {
      TollPlanner.Plan plan = TollPlanner.plan(spec);
      return new RoadRecord(
         this.id,
         this.owner,
         this.a,
         this.b,
         spec.a(),
         spec.b(),
         plan.mesh().settings(),
         false,
         4,
         plan.structures(),
         0,
         41,
         this.id,
         List.of(),
         null,
         null,
         spec
      );
   }

   public TollPlanner.Plan tollPlan() {
      return this.toll == null ? null : TOLL_PLANS.get(this, r -> TollPlanner.plan(r.toll()));
   }

   public RoadRecord junction(UUID group, JunctionPlanner.Ref ref) {
      return new RoadRecord(
         this.id,
         this.owner,
         this.a,
         this.b,
         this.start,
         this.end,
         this.settings,
         this.automatic,
         this.clearance,
         ref.get().structures(),
         0,
         18,
         group,
         List.of(),
         this.furniturePhase,
         ref
      );
   }

   public RoadRecord furniturePhase(RoadFurniture.Phase phase) {
      return Objects.equals(this.furniturePhase, phase)
         ? this
         : new RoadRecord(
            this.id,
            this.owner,
            this.a,
            this.b,
            this.start,
            this.end,
            this.settings,
            this.automatic,
            this.clearance,
            this.structures,
            this.endCaps,
            this.buildVersion,
            this.assembly,
            this.alignment,
            phase,
            this.junction,
            this.toll,
            this.fragment
         );
   }

   public RoadRecord alignment(UUID group, RoadGeometry.Mesh mesh) {
      return new RoadRecord(
         this.id,
         this.owner,
         this.a,
         this.b,
         this.start,
         this.end,
         this.settings,
         this.automatic,
         this.clearance,
         this.structures,
         this.endCaps,
         this.buildVersion,
         group,
         mesh.samples(),
         this.furniturePhase,
         this.junction,
         this.toll,
         this.fragment
      );
   }

   public RoadRecord caps(int mask) {
      return new RoadRecord(
         this.id,
         this.owner,
         this.a,
         this.b,
         this.start,
         this.end,
         this.settings,
         this.automatic,
         this.clearance,
         this.structures,
         mask,
         this.junction == null ? 17 : 18,
         this.assembly,
         this.alignment,
         this.furniturePhase,
         this.junction,
         this.toll,
         this.fragment
      );
   }

   public RoadRecord structures(List<RoadStructures.Part> parts) {
      parts = RoadPartCompaction.compact(parts);
      return this.structures.equals(parts)
         ? this
         : new RoadRecord(
            this.id,
            this.owner,
            this.a,
            this.b,
            this.start,
            this.end,
            this.settings,
            this.automatic,
            this.clearance,
            parts,
            this.endCaps,
            this.buildVersion,
            this.assembly,
            this.alignment,
            this.furniturePhase,
            this.junction,
            this.toll,
            this.fragment
         );
   }

   public CompoundTag save() {
      CompoundTag t = new CompoundTag();
      t.putUUID("Id", this.id);
      t.putUUID("Owner", this.owner);
      t.putLong("A", this.a.asLong());
      t.putLong("B", this.b.asLong());
      t.put("Start", writeNode(this.start));
      t.put("End", writeNode(this.end));
      t.put("Settings", writeSettings(this.settings));
      t.putBoolean("Automatic", this.automatic);
      t.putInt("Clearance", this.clearance);
      t.putInt("EndCaps", this.endCaps);
      t.putInt("BuildVersion", this.buildVersion);
      t.putByteArray("StructuresPacked", this.packStructures());
      if (this.furniturePhase != null) {
         t.putDouble("FurnitureOrigin", this.furniturePhase.origin());
         t.putInt("FurnitureDirection", this.furniturePhase.direction());
      }

      if (this.junction != null) {
         t.put("Junction", JunctionCodec.writeRef(this.junction));
      }

      if (this.toll != null) {
         t.put("Toll", TollCodec.write(this.toll));
      }

      if (this.fragment != null) {
         t.put("Slice", RoadSliceCodec.write(this.fragment));
      }

      if (this.assembly != null) {
         t.putUUID("Assembly", this.assembly);
      }

      if (!this.alignment.isEmpty()) {
         t.putByteArray("Alignment", this.packAlignment());
      }

      return t;
   }

   public CompoundTag header() {
      return this.structures(List.of()).save();
   }

   private byte[] packStructures() {
      return packStructures(this.structures);
   }

   public static byte[] packStructures(List<RoadStructures.Part> structures) {
      if (structures.size() > 50000) {
         throw new IllegalArgumentException("设施数量 " + structures.size() + " 超过每路上限 50000");
      } else {
         try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(-32);
            out.writeInt(structures.size());

            for (RoadStructures.Part p : structures) {
               writeVector(out, p.a());
               writeVector(out, p.b());
               out.writeDouble(p.width());
               out.writeDouble(p.height());
               out.writeByte((p.pier() ? 1 : 0) | (p.frameA() != null ? 2 : 0) | (p.frameB() != null ? 4 : 0) | (p.heights() != null ? 8 : 0));
               out.writeByte(p.material().ordinal());
               if (p.frameA() != null) {
                  writeVector(out, p.frameA());
               }

               if (p.frameB() != null) {
                  writeVector(out, p.frameB());
               }

               out.writeUTF(p.model());
               if (p.heights() != null) {
                  for (int i = 0; i < 4; i++) {
                     out.writeDouble(p.heights().get(i));
                  }
               }
            }

            out.flush();
            return bytes.toByteArray();
         } catch (IOException var6) {
            throw new IllegalStateException(var6);
         }
      }
   }

   private static void writeVector(DataOutput out, RoadGeometry.V v) throws IOException {
      out.writeDouble(v.x());
      out.writeDouble(v.y());
      out.writeDouble(v.z());
   }

   private static RoadGeometry.V readVector(DataInput in) throws IOException {
      return new RoadGeometry.V(in.readDouble(), in.readDouble(), in.readDouble());
   }

   public static List<RoadStructures.Part> unpackStructures(byte[] bytes) {
      try {
         DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
         int count = in.readInt();
         boolean corners = count == -32;
         boolean models = corners || count == -31;
         if (models) {
            count = in.readInt();
         }

         if (count >= 0 && count <= 50000 && (long)count * 66L <= (long)(bytes.length - 4)) {
            List<RoadStructures.Part> parts = new ArrayList<>(count);
            RoadStructures.Material[] materials = RoadStructures.Material.values();

            for (int i = 0; i < count; i++) {
               RoadGeometry.V a = readVector(in);
               RoadGeometry.V b = readVector(in);
               double w = in.readDouble();
               double h = in.readDouble();
               int flags = in.readUnsignedByte();
               int material = in.readUnsignedByte();
               if (flags > (corners ? 15 : 7) || material >= materials.length) {
                  throw new IOException("invalid structure type");
               }

               parts.add(
                  new RoadStructures.Part(
                     a,
                     b,
                     w,
                     h,
                     (flags & 1) != 0,
                     materials[material],
                     (flags & 2) != 0 ? readVector(in) : null,
                     (flags & 4) != 0 ? readVector(in) : null,
                     models ? in.readUTF() : "",
                     (flags & 8) != 0 ? new RoadStructures.Heights(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()) : null
                  )
               );
            }

            if (in.available() != 0) {
               throw new IOException("trailing structure data");
            } else {
               return parts;
            }
         } else {
            throw new IOException("invalid structure count");
         }
      } catch (IOException var16) {
         throw new IllegalArgumentException("道路设施数据损坏", var16);
      }
   }

   public static RoadRecord load(CompoundTag t) {
      List<RoadStructures.Part> parts = new ArrayList<>();

      for (Tag tag : t.getList("Structures", 10)) {
         CompoundTag q = (CompoundTag)tag;
         parts.add(
            new RoadStructures.Part(
               readNode(q.getCompound("A")).position(),
               readNode(q.getCompound("B")).position(),
               q.getDouble("Width"),
               q.getDouble("Height"),
               q.getBoolean("Pier"),
               q.contains("Material") ? RoadStructures.Material.valueOf(q.getString("Material")) : RoadStructures.Material.DEFAULT,
               q.contains("FrameA") ? readNode(q.getCompound("FrameA")).position() : null,
               q.contains("FrameB") ? readNode(q.getCompound("FrameB")).position() : null
            )
         );
      }

      if (t.contains("StructuresPacked", 7)) {
         parts = unpackStructures(t.getByteArray("StructuresPacked"));
      }

      return new RoadRecord(
         t.getUUID("Id"),
         t.getUUID("Owner"),
         BlockPos.of(t.getLong("A")),
         BlockPos.of(t.getLong("B")),
         readNode(t.getCompound("Start")),
         readNode(t.getCompound("End")),
         readSettings(t.getCompound("Settings")),
         t.getBoolean("Automatic"),
         Math.max(0, Math.min(t.getCompound("Settings").getString("Style").equals("RAILBED") ? 5 : 4, t.getInt("Clearance"))),
         parts,
         t.getInt("EndCaps"),
         t.getInt("BuildVersion"),
         t.hasUUID("Assembly") ? t.getUUID("Assembly") : null,
         t.contains("Alignment") ? unpackAlignment(t.getByteArray("Alignment")) : List.of(),
         t.contains("FurnitureOrigin") ? new RoadFurniture.Phase(t.getDouble("FurnitureOrigin"), t.getInt("FurnitureDirection")) : null,
         t.contains("Junction") ? JunctionCodec.readRef(t.getCompound("Junction")) : null,
         t.contains("Toll") ? TollCodec.read(t.getCompound("Toll")) : null,
         t.contains("Slice") ? RoadSliceCodec.read(t.getCompound("Slice")) : null
      );
   }

   public static CompoundTag writeNode(RoadGeometry.Node n) {
      CompoundTag t = new CompoundTag();
      t.putDouble("X", n.position().x());
      t.putDouble("Y", n.position().y());
      t.putDouble("Z", n.position().z());
      t.putDouble("Yaw", n.yaw());
      t.putDouble("Grade", n.grade());
      return t;
   }

   public static RoadGeometry.Node readNode(CompoundTag t) {
      return new RoadGeometry.Node(new RoadGeometry.V(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z")), t.getDouble("Yaw"), t.getDouble("Grade"));
   }

   public static CompoundTag writeSettings(RoadGeometry.Settings s) {
      CompoundTag t = new CompoundTag();
      t.putString("Mode", s.mode().name());
      t.putString("Style", s.style().name());
      t.putString("Structure", s.structure().name());
      t.putInt("TaperVersion", s.taperVersion());
      t.putString("RampTurn", s.rampTurn().name());
      t.putDouble("Width", s.width());
      t.putDouble("StartWidth", s.startWidth());
      t.putDouble("EndWidth", s.endWidth());
      t.putDouble("Thickness", s.thickness());
      t.putDouble("Tension", s.tension());
      t.putDouble("Angle", s.arcDegrees());
      RoadProfile.Options o = s.options();
      if (!o.levelCrossings().isEmpty()) {
         t.put("RailwayCrossings", RailwayCrossingCodec.write(o.levelCrossings()));
      }

      if (o.railbed().active()) {
         t.put("RailbedProfile", RailbedCodec.profile(o.railbed()));
      }

      if (o.lanes().explicit()) {
         CompoundTag lanes = new CompoundTag();
         lanes.putInt("Forward", o.lanes().forward());
         lanes.putInt("Reverse", o.lanes().reverse());
         t.put("DirectionalLanes", lanes);
      }

      ListTag lines = new ListTag();

      for (RoadLaneLines.Edit line : o.laneLines()) {
         CompoundTag tag = new CompoundTag();
         tag.putString("Key", line.key());
         tag.putString("Pattern", line.pattern().name());
         tag.putDouble("Width", line.width());
         lines.add(tag);
      }

      t.put("LaneLines", lines);
      t.putBoolean("LeftTraffic", o.leftTraffic());
      t.putBoolean("CycleLane", o.cycle());
      t.putBoolean("CycleAsphalt", o.cycleAsphalt());
      t.putBoolean("CycleRail", o.cycleRail());
      t.putBoolean("Curb", o.curb());
      CompoundTag walk = new CompoundTag();
      walk.putBoolean("Enabled", o.sidewalk().enabled());
      walk.putString("Side", o.sidewalk().side().name());
      walk.putInt("Width", o.sidewalk().width());
      walk.putString("Material", o.sidewalk().material());
      walk.putBoolean("Smooth", o.sidewalk().smooth());
      walk.putBoolean("Tactile", o.sidewalk().tactile());
      t.put("Sidewalk", walk);
      RoadInfrastructure.Config infra = o.infrastructure();
      CompoundTag facilities = new CompoundTag();
      facilities.putString("Bridge", infra.bridge().name());
      facilities.putDouble("Span", infra.span());
      facilities.putString("Tunnel", infra.tunnel().name());
      facilities.putDouble("Headroom", infra.headroom());
      facilities.putString("Gantry", infra.gantry().name());
      facilities.putDouble("Spacing", infra.spacing());
      facilities.putDouble("TunnelDepth", infra.tunnelDepth());
      facilities.putString("TunnelAdjustment", infra.adjustment().name());
      facilities.putInt("DipProfile", infra.efficientDip() ? 1 : 0);
      facilities.putDouble("BridgeRise", infra.bridgeRise());
      facilities.putDouble("MaxGrade", infra.maxGrade());
      facilities.putBoolean("AutoSpan", infra.autoSpan());
      facilities.putBoolean("FixedStations", infra.fixedStations());
      facilities.putString("MedianConversion", infra.medianConversion().name());
      ListTag edits = new ListTag();

      for (RoadGantry.Edit edit : infra.gantryEdits()) {
         CompoundTag e = new CompoundTag();
         e.putInt("Slot", edit.slot());
         e.putDouble("Offset", edit.offset());
         e.putDouble("Clearance", edit.clearance());
         e.putString("Kind", edit.kind().name());
         e.putBoolean("Reverse", edit.reverse());
         e.putBoolean("Independent", edit.independent());
         e.putDouble("Position", edit.position());
         e.putBoolean("Anchored", edit.anchored());
         e.putBoolean("Automatic", edit.automatic());
         edits.add(e);
      }

      facilities.put("GantryEdits", edits);
      t.put("Infrastructure", facilities);
      t.putString("OuterRail", o.outerRail().name());
      t.putBoolean("HideArrows", o.hideArrows());
      t.put("AttachedRoadPoints", AttachedPointCodec.write(o.attachments()));
      t.put("LanePointsV2", LanePointCodec.write(o.lanePoints()));
      CompoundTag decor = new CompoundTag();
      RoadStreetscape.Config c = o.streetscape();
      decor.putString("Separator", c.separator().name());
      decor.putString("Apron", c.apron().name());
      ListTag tunnels = new ListTag();

      for (RoadStreetscape.Span span : c.tunnels()) {
         CompoundTag item = new CompoundTag();
         item.putDouble("From", span.from());
         item.putDouble("To", span.to());
         tunnels.add(item);
      }

      decor.put("Tunnels", tunnels);
      decor.putBoolean("Parking", c.parking());
      decor.putBoolean("Lamps", c.lamps());
      decor.putDouble("LampSpacing", c.lampSpacing());
      decor.putBoolean("WalkLamps", c.walkLamps());
      decor.putDouble("WalkLampSpacing", c.walkLampSpacing());
      decor.putString("Planting", c.planting().name());
      decor.putDouble("PlantingSpacing", c.plantingSpacing());
      RoadPlantings plants = c.plantings();
      decor.putDouble("MedianGreenWidth", plants.greenWidth());
      decor.putDouble("MedianRailGap", plants.railGap());
      decor.putString("MedianTrees", plants.medianTrees().name());
      decor.putDouble("MedianTreeSpacing", plants.medianSpacing());
      decor.putString("SeparatorTrees", plants.separatorTrees().name());
      decor.putDouble("SeparatorTreeSpacing", plants.separatorSpacing());
      ListTag raised = new ListTag();

      for (RoadStreetscape.Span span : c.raisedSpans()) {
         CompoundTag item = new CompoundTag();
         item.putDouble("From", span.from());
         item.putDouble("To", span.to());
         raised.add(item);
      }

      decor.put("Raised", raised);
      ListTag crossings = new ListTag();

      for (RoadsideBypass.Span span : c.crossings()) {
         CompoundTag item = new CompoundTag();
         item.putInt("Side", span.side());
         item.putDouble("Start", span.start());
         item.putDouble("From", span.from());
         item.putDouble("To", span.to());
         item.putDouble("End", span.end());
         item.putDouble("Delta", span.delta());
         crossings.add(item);
      }

      decor.put("CycleCrossings", crossings);
      CompoundTag sideTag = new CompoundTag();

      for (int side : new int[]{-1, 1}) {
         for (boolean cut : new boolean[]{false, true}) {
            ListTag spans = new ListTag();

            for (RoadStreetscape.Span span : cut ? c.sides().cut(side) : c.sides().raised(side)) {
               CompoundTag item = new CompoundTag();
               item.putDouble("From", span.from());
               item.putDouble("To", span.to());
               spans.add(item);
            }

            sideTag.put((side < 0 ? "Negative" : "Positive") + (cut ? "Cut" : "Raised"), spans);
         }
      }

      decor.put("SideStructures", sideTag);
      t.put("Streetscape", decor);
      t.putDouble("LiftPosition", o.liftPosition());
      t.putDouble("LiftHeight", o.liftHeight());
      t.putString("SourceSide", o.ports().source().name());
      t.putString("TargetSide", o.ports().target().name());
      t.putDouble("SourceOutset", o.ports().sourceOutset());
      t.putDouble("TargetOutset", o.ports().targetOutset());
      t.putString("SourceJoin", o.ports().sourceJoin().name());
      t.putString("TargetJoin", o.ports().targetJoin().name());
      ListTag controls = new ListTag();

      for (RoadProfile.Control cx : o.routing().controls()) {
         CompoundTag item = new CompoundTag();
         item.putUUID("Id", cx.id());
         item.put("Position", writeNode(new RoadGeometry.Node(cx.position(), 0.0, 0.0)));
         controls.add(item);
      }

      t.put("CurveControls", controls);
      t.putString("RouteKind", o.routing().kind().name());
      if (o.routing().through()) {
         t.putBoolean("CurveThrough", true);
      }

      t.putDouble("LoopRadius", o.routing().radius());
      t.putDouble("ControlX", o.routing().offsetX());
      t.putDouble("ControlZ", o.routing().offsetZ());
      t.putBoolean("FitEdges", o.routing().fitEdges());
      t.putDouble("Transition", o.routing().transition());
      if (o.routing().fanArms() > 0) {
         t.putInt("FanArms", o.routing().fanArms());
         t.putDouble("FanStart", o.routing().fanStart());
         t.putDouble("FanEnd", o.routing().fanEnd());
      }

      if (o.ends().startHold() != 0.0 || o.ends().endHold() != 0.0) {
         t.putDouble("StartHold", o.ends().startHold());
         t.putDouble("EndHold", o.ends().endHold());
      }

      if (o.ends().paintPhase() != 0.0) {
         t.putDouble("PaintPhase", o.ends().paintPhase());
      }

      if (o.ends().start() != null) {
         t.put("StartSection", writeSection(o.ends().start()));
      }

      if (o.ends().end() != null) {
         t.put("EndSection", writeSection(o.ends().end()));
      }

      t.putBoolean("KeepEndSections", o.ends().persistent());
      if (o.ends().trimmedStart() != 0.0 || o.ends().trimmedEnd() != 0.0) {
         t.putDouble("TrimmedStart", o.ends().trimmedStart());
         t.putDouble("TrimmedEnd", o.ends().trimmedEnd());
      }

      if (o.ends().port() != null) {
         RoadTransitions.Port p = o.ends().port();
         CompoundTag cx = new CompoundTag();
         cx.putDouble("MotorMin", p.motorMin());
         cx.putDouble("MotorMax", p.motorMax());
         cx.putDouble("Median", p.median());
         cx.putDouble("MedianCenter", p.medianCenter());
         cx.putDouble("CurbLeft", p.curbLeft());
         cx.putDouble("CurbRight", p.curbRight());
         ListTag d = new ListTag();
         p.dividers().forEach(v -> d.add(DoubleTag.valueOf(v)));
         cx.put("Dividers", d);
         t.put("JunctionPort", cx);
      }

      return t;
   }

   private static List<RoadProfile.Control> readControls(CompoundTag t) {
      ListTag list = t.getList("CurveControls", 10);
      if (list.size() > 32) {
         throw new IllegalArgumentException("曲线控制点超过32个");
      } else {
         ArrayList<RoadProfile.Control> out = new ArrayList<>();

         for (Tag tag : list) {
            CompoundTag c = (CompoundTag)tag;
            out.add(new RoadProfile.Control(c.getUUID("Id"), readNode(c.getCompound("Position")).position()));
         }

         return List.copyOf(out);
      }
   }

   public static RoadGeometry.Settings readSettings(CompoundTag t) {
      if (!t.contains("Mode")) {
         return RoadGeometry.Settings.defaults();
      } else {
         RoadGeometry.Settings s = new RoadGeometry.Settings(
            RoadGeometry.Mode.valueOf(t.getString("Mode")),
            RoadGeometry.Style.valueOf(t.getString("Style")),
            t.getDouble("Width"),
            t.getDouble("Thickness"),
            t.getDouble("Tension"),
            t.getDouble("Angle"),
            t.contains("StartWidth") ? t.getDouble("StartWidth") : t.getDouble("Width"),
            t.contains("EndWidth") ? t.getDouble("EndWidth") : t.getDouble("Width"),
            t.contains("Structure") ? RoadGeometry.Structure.valueOf(t.getString("Structure")) : RoadGeometry.Structure.AUTO,
            t.contains("TaperVersion") ? t.getInt("TaperVersion") : 1,
            t.contains("RampTurn") ? RoadGeometry.RampTurn.valueOf(t.getString("RampTurn")) : RoadGeometry.RampTurn.LEGACY
         );
         s = s.options(
            new RoadProfile.Options(
               t.getBoolean("LeftTraffic"),
               t.getBoolean("CycleLane"),
               t.getBoolean("CycleRail"),
               t.getBoolean("Curb"),
               t.contains("LiftPosition") ? t.getDouble("LiftPosition") : 0.5,
               t.getDouble("LiftHeight"),
               new RoadProfile.Ports(
                  t.contains("SourceSide") ? RoadProfile.Side.valueOf(t.getString("SourceSide")) : RoadProfile.Side.AUTO,
                  t.contains("TargetSide") ? RoadProfile.Side.valueOf(t.getString("TargetSide")) : RoadProfile.Side.AUTO,
                  t.getDouble("SourceOutset"),
                  t.getDouble("TargetOutset"),
                  t.contains("SourceJoin") ? RoadProfile.JoinMode.valueOf(t.getString("SourceJoin")) : RoadProfile.JoinMode.ADDED,
                  t.contains("TargetJoin") ? RoadProfile.JoinMode.valueOf(t.getString("TargetJoin")) : RoadProfile.JoinMode.ADDED
               ),
               new RoadProfile.Routing(
                  t.contains("RouteKind") ? RoadProfile.RouteKind.valueOf(t.getString("RouteKind")) : RoadProfile.RouteKind.DIRECT,
                  t.contains("LoopRadius") ? t.getDouble("LoopRadius") : 24.0,
                  t.getDouble("ControlX"),
                  t.getDouble("ControlZ"),
                  t.getBoolean("FitEdges"),
                  t.contains("Transition") ? t.getDouble("Transition") : 36.0,
                  t.getInt("FanArms"),
                  t.getDouble("FanStart"),
                  t.getDouble("FanEnd"),
                  readControls(t),
                  t.getBoolean("CurveThrough")
               ),
               t.contains("OuterRail") ? RoadProfile.OuterRail.valueOf(t.getString("OuterRail")) : RoadProfile.OuterRail.AUTO
            )
         );
         s = s.options(
            s.options()
               .ends(
                  new RoadTransitions.Ends(
                     t.contains("StartSection") ? readSection(t.getCompound("StartSection")) : null,
                     t.contains("EndSection") ? readSection(t.getCompound("EndSection")) : null,
                     t.getBoolean("KeepEndSections"),
                     t.getDouble("TrimmedStart"),
                     t.getDouble("TrimmedEnd"),
                     null
                  )
               )
         );
         s = s.options(s.options().ends(s.options().ends().hold(t.getDouble("StartHold"), t.getDouble("EndHold"))));
         if (t.contains("PaintPhase")) {
            s = s.options(s.options().ends(s.options().ends().paintPhase(t.getDouble("PaintPhase"))));
         }

         if (t.contains("JunctionPort")) {
            CompoundTag p = t.getCompound("JunctionPort");
            ArrayList<Double> d = new ArrayList<>();

            for (Tag v : p.getList("Dividers", 6)) {
               d.add(((DoubleTag)v).getAsDouble());
            }

            s = s.options(
               s.options()
                  .ends(
                     s.options()
                        .ends()
                        .port(
                           new RoadTransitions.Port(
                              p.getDouble("MotorMin"),
                              p.getDouble("MotorMax"),
                              p.getDouble("Median"),
                              d,
                              p.getDouble("CurbLeft"),
                              p.getDouble("CurbRight"),
                              p.getDouble("MedianCenter")
                           )
                        )
                  )
            );
         }

         if (t.contains("Sidewalk")) {
            CompoundTag walk = t.getCompound("Sidewalk");
            s = s.options(
               s.options()
                  .sidewalk(
                     new RoadSidewalks.Config(
                        walk.getBoolean("Enabled"),
                        RoadSidewalks.Side.valueOf(walk.getString("Side")),
                        walk.getInt("Width"),
                        walk.getString("Material"),
                        walk.getBoolean("Smooth"),
                        !walk.contains("Tactile") || walk.getBoolean("Tactile")
                     )
                  )
            );
         }

         if (t.contains("Infrastructure")) {
            CompoundTag i = t.getCompound("Infrastructure");
            s = s.options(
               s.options()
                  .infrastructure(
                     new RoadInfrastructure.Config(
                        RoadInfrastructure.Bridge.valueOf(i.getString("Bridge")),
                        i.getDouble("Span"),
                        RoadInfrastructure.Tunnel.valueOf(i.getString("Tunnel")),
                        i.getDouble("Headroom"),
                        RoadInfrastructure.Gantry.valueOf(i.getString("Gantry")),
                        i.getDouble("Spacing"),
                        i.getDouble("TunnelDepth"),
                        i.getList("GantryEdits", 10)
                           .stream()
                           .map(
                              tag -> {
                                 CompoundTag e = (CompoundTag)tag;
                                 return new RoadGantry.Edit(
                                    e.getInt("Slot"),
                                    e.getDouble("Offset"),
                                    e.getDouble("Clearance"),
                                    RoadInfrastructure.Gantry.valueOf(e.getString("Kind")),
                                    e.getBoolean("Reverse"),
                                    e.getBoolean("Independent"),
                                    e.getDouble("Position"),
                                    e.getBoolean("Anchored"),
                                    e.getBoolean("Automatic")
                                 );
                              }
                           )
                           .toList(),
                        i.contains("TunnelAdjustment") ? RoadTunnelFit.Adjustment.valueOf(i.getString("TunnelAdjustment")) : RoadTunnelFit.Adjustment.OFF,
                        i.getInt("DipProfile") >= 1,
                        i.getDouble("BridgeRise"),
                        i.getDouble("MaxGrade"),
                        i.getBoolean("AutoSpan"),
                        i.getBoolean("FixedStations"),
                        i.contains("MedianConversion")
                           ? RoadInfrastructure.MedianConversion.valueOf(i.getString("MedianConversion"))
                           : RoadInfrastructure.MedianConversion.BOTH
                     )
                  )
            );
         } else {
            s = s.options(s.options().infrastructure(s.options().infrastructure().grade(0.0).autoSpan(false)));
         }

         ArrayList<RoadLaneLines.Edit> lines = new ArrayList<>();

         for (Tag tag : t.getList("LaneLines", 10)) {
            CompoundTag e = (CompoundTag)tag;
            lines.add(new RoadLaneLines.Edit(e.getString("Key"), RoadLaneLines.Pattern.valueOf(e.getString("Pattern")), e.getDouble("Width")));
         }

         s = s.options(
            s.options()
               .laneLines(lines)
               .hideArrows(t.getBoolean("HideArrows"))
               .attachments(AttachedPointCodec.read(t.getCompound("AttachedRoadPoints")))
               .lanePoints(LanePointCodec.read(t.getCompound("LanePointsV2")))
         );
         if (t.contains("DirectionalLanes")) {
            CompoundTag lanes = t.getCompound("DirectionalLanes");
            s = s.options(s.options().lanes(new RoadLanes.Counts(lanes.getInt("Forward"), lanes.getInt("Reverse"))));
         }

         s.validate();
         if (s.options().cycle() && t.getBoolean("CycleAsphalt")) {
            s = s.options(s.options().cycleFinish(RoadProfile.Options.CycleFinish.ASPHALT));
         }

         if (t.contains("Streetscape")) {
            CompoundTag c = t.getCompound("Streetscape");
            s = s.options(
               s.options()
                  .streetscape(
                     new RoadStreetscape.Config(
                        RoadStreetscape.Separator.valueOf(c.getString("Separator")),
                        c.getBoolean("Parking"),
                        c.getDouble("LampSpacing"),
                        c.getBoolean("WalkLamps"),
                        c.getDouble("WalkLampSpacing"),
                        RoadStreetscape.Planting.valueOf(c.getString("Planting")),
                        c.getDouble("PlantingSpacing")
                     )
                  )
            );
            ArrayList<RoadStreetscape.Span> spans = new ArrayList<>();

            for (Tag item : c.getList("Raised", 10)) {
               CompoundTag r = (CompoundTag)item;
               spans.add(new RoadStreetscape.Span(r.getDouble("From"), r.getDouble("To")));
            }

            ArrayList<RoadsideBypass.Span> crossings = new ArrayList<>();

            for (Tag item : c.getList("CycleCrossings", 10)) {
               CompoundTag r = (CompoundTag)item;
               crossings.add(
                  new RoadsideBypass.Span(
                     r.getInt("Side"), r.getDouble("Start"), r.getDouble("From"), r.getDouble("To"), r.getDouble("End"), r.getDouble("Delta")
                  )
               );
            }

            s = s.options(
               s.options()
                  .streetscape(
                     s.options()
                        .streetscape()
                        .raisedSpans(spans)
                        .crossings(crossings)
                        .apron(c.contains("Apron") ? RoadStreetscape.Apron.valueOf(c.getString("Apron")) : RoadStreetscape.Apron.ROAD)
                        .tunnels(c.getList("Tunnels", 10).stream().map(itemx -> {
                           CompoundTag span = (CompoundTag)itemx;
                           return new RoadStreetscape.Span(span.getDouble("From"), span.getDouble("To"));
                        }).toList())
                  )
            );
         }

         if (t.getCompound("Streetscape").contains("Lamps")) {
            s = s.options(s.options().streetscape(s.options().streetscape().lamps(t.getCompound("Streetscape").getBoolean("Lamps"))));
         }

         if (t.getCompound("Streetscape").contains("SideStructures")) {
            CompoundTag c = t.getCompound("Streetscape").getCompound("SideStructures");
            Function<String, List<RoadStreetscape.Span>> read = key -> c.getList(key, 10).stream().map(itemx -> {
                  CompoundTag v = (CompoundTag)itemx;
                  return new RoadStreetscape.Span(v.getDouble("From"), v.getDouble("To"));
               }).toList();
            s = s.options(
               s.options()
                  .streetscape(
                     s.options()
                        .streetscape()
                        .sideStructures(
                           new RoadStreetscape.SideStructures(
                              read.apply("NegativeRaised"), read.apply("PositiveRaised"), read.apply("NegativeCut"), read.apply("PositiveCut")
                           )
                        )
                  )
            );
         }

         if (t.contains("Streetscape")) {
            CompoundTag c = t.getCompound("Streetscape");
            RoadPlantings defaults = RoadPlantings.DEFAULT;
            RoadPlantings plants = new RoadPlantings(
               c.contains("MedianGreenWidth") ? c.getDouble("MedianGreenWidth") : defaults.greenWidth(),
               c.contains("MedianRailGap") ? c.getDouble("MedianRailGap") : 0.0,
               c.contains("MedianTrees") ? RoadStreetscape.Planting.valueOf(c.getString("MedianTrees")) : defaults.medianTrees(),
               c.contains("MedianTreeSpacing") ? c.getDouble("MedianTreeSpacing") : 16.0,
               c.contains("SeparatorTrees") ? RoadStreetscape.Planting.valueOf(c.getString("SeparatorTrees")) : defaults.separatorTrees(),
               c.contains("SeparatorTreeSpacing") ? c.getDouble("SeparatorTreeSpacing") : 16.0
            );
            s = s.options(s.options().streetscape(s.options().streetscape().plantings(plants)));
         }

         if (t.contains("RailwayCrossings")) {
            s = s.options(s.options().levelCrossings(RailwayCrossingCodec.read(t.getList("RailwayCrossings", 10))));
         }

         if (t.contains("RailbedProfile")) {
            s = s.options(s.options().railbed(RailbedCodec.profile(t.getCompound("RailbedProfile"))));
         }

         return s;
      }
   }

   private static CompoundTag writeSection(RoadTransitions.Section s) {
      CompoundTag tag = writeSettings(s.settings(false));
      if (s.sidewalk() == null) {
         tag.remove("Sidewalk");
      }

      return tag;
   }

   private static RoadTransitions.Section readSection(CompoundTag t) {
      CompoundTag flat = t.copy();
      flat.remove("StartSection");
      flat.remove("EndSection");
      RoadGeometry.Settings settings = readSettings(flat);
      RoadTransitions.Section section = RoadTransitions.Section.of(settings).port(settings.options().ends().port());
      return t.contains("Sidewalk")
         ? section
         : new RoadTransitions.Section(
            section.style(),
            section.width(),
            section.cycle(),
            section.cycleRail(),
            section.curb(),
            section.outerRail(),
            null,
            section.cycleAsphalt(),
            section.port(),
            section.streetscape(),
            section.lanes()
         );
   }

   public RoadGeometry.Mesh terrainClassificationMesh() {
      return RailwayCrossings.shape(
         this.derivedStreetscape(this.settings.options().streetscape().raisedSpans(List.of()).crossings(List.of()).tunnels(List.of())).rawMesh()
      );
   }

   public RoadRecord withLevelCrossings(List<RailwayCrossings.Surface> value) {
      return this.settings.options().levelCrossings().equals(value)
         ? this
         : new RoadRecord(
            this.id,
            this.owner,
            this.a,
            this.b,
            this.start,
            this.end,
            this.settings.options(this.settings.options().levelCrossings(value)),
            this.automatic,
            this.clearance,
            this.structures,
            this.endCaps,
            this.buildVersion,
            this.assembly,
            this.alignment,
            this.furniturePhase,
            this.junction,
            this.toll,
            this.fragment
         );
   }

   public RoadRecord derivedStreetscape(RoadStreetscape.Config config) {
      return this.settings.options().streetscape().equals(config)
         ? this
         : new RoadRecord(
            this.id,
            this.owner,
            this.a,
            this.b,
            this.start,
            this.end,
            this.settings.options(this.settings.options().streetscape(config)),
            this.automatic,
            this.clearance,
            this.structures,
            this.endCaps,
            this.buildVersion,
            this.assembly,
            this.alignment,
            this.furniturePhase,
            this.junction,
            this.toll,
            this.fragment
         );
   }

   public RoadRecord withLanePoints(LanePoints.Data value) {
      if (this.settings.options().lanePoints().equals(value)) {
         return this;
      } else {
         LanePoints.Data old = this.settings.options().lanePoints();
         RoadSlicing.Fragment nextFragment = this.fragment;
         if (this.fragment != null && (!old.cuts().equals(value.cuts()) || !old.additions().equals(value.additions()) || !old.bands().equals(value.bands()))) {
            nextFragment = this.fragment.geometry(new RoadSlicing.Geometry(List.of()));
         }

         return new RoadRecord(
            this.id,
            this.owner,
            this.a,
            this.b,
            this.start,
            this.end,
            this.settings.options(this.settings.options().lanePoints(value)),
            this.automatic,
            this.clearance,
            this.structures,
            this.endCaps,
            this.buildVersion,
            this.assembly,
            this.alignment,
            this.furniturePhase,
            this.junction,
            this.toll,
            nextFragment
         );
      }
   }

   public RoadRecord withAttachments(RoadAttachments.Data value) {
      return this.settings.options().attachments().equals(value)
         ? this
         : new RoadRecord(
            this.id,
            this.owner,
            this.a,
            this.b,
            this.start,
            this.end,
            this.settings.options(this.settings.options().attachments(value)),
            this.automatic,
            this.clearance,
            this.structures,
            this.endCaps,
            this.buildVersion,
            this.assembly,
            this.alignment,
            this.furniturePhase,
            this.junction,
            this.toll,
            this.fragment
         );
   }

   private static double sectionWidth(RoadTransitions.Section section, double ownWidth) {
      return section == null ? ownWidth : section.width();
   }

   private static boolean authoredWidthChanged(RoadGeometry.Settings before, RoadGeometry.Settings after) {
      return before.width() != after.width()
         || before.style() != after.style()
         || !before.options().lanes().equals(after.options().lanes())
         || before.options().leftTraffic() != after.options().leftTraffic()
         || before.options().cycle() != after.options().cycle()
         || before.options().curb() != after.options().curb();
   }

   private static boolean widthChanged(RoadGeometry.Settings before, RoadGeometry.Settings after) {
      return authoredWidthChanged(before, after)
         || Math.abs(sectionWidth(before.options().ends().start(), before.width()) - sectionWidth(after.options().ends().start(), after.width())) > 1.0E-5
         || Math.abs(sectionWidth(before.options().ends().end(), before.width()) - sectionWidth(after.options().ends().end(), after.width())) > 1.0E-5;
   }

   public boolean sameDerivedLaneEnd(RoadGeometry.Settings proposed, boolean first, RoadGeometry.Settings candidate) {
      if (candidate == null) {
         return false;
      } else {
         RoadGeometry.Mesh before = this.unchangedLaneMesh(proposed);
         return before != null && retainedLaneEnd(before, first, RoadTransitions.Section.of(candidate));
      }
   }

   private RoadGeometry.Mesh unchangedLaneMesh(RoadGeometry.Settings proposed) {
      if (!authoredWidthChanged(this.settings, proposed) && this.junction == null && this.toll == null && this.settings.options().lanePoints().link() == null) {
         LanePoints.Data lanes = this.settings.options().lanePoints();
         return !lanes.cuts().stream().anyMatch(c -> !c.temporary()) && lanes.additions().isEmpty() ? null : this.caps(0).mesh();
      } else {
         return null;
      }
   }

   public static RoadGeometry.Settings joinSections(
      RoadGeometry.Settings proposed, RoadGeometry.Settings start, RoadGeometry.Settings end, boolean fixedStart, boolean fixedEnd, RoadRecord previous
   ) {
      RoadGeometry.Mesh before = previous == null ? null : previous.unchangedLaneMesh(proposed);
      boolean keepStart = before != null && start != null && retainedLaneEnd(before, true, RoadTransitions.Section.of(start));
      boolean keepEnd = before != null && end != null && retainedLaneEnd(before, false, RoadTransitions.Section.of(end));
      RoadGeometry.Settings joined = RoadTransitions.join(proposed, keepStart ? null : start, keepEnd ? null : end);
      if (!proposed.style().ramp() && RoadProfile.modern(proposed.style()) && (fixedStart || fixedEnd)) {
         RoadTransitions.Ends ends = joined.options().ends();
         joined = RoadTransitions.ends(
            joined,
            fixedStart && start != null ? RoadTransitions.Section.of(start) : ends.start(),
            fixedEnd && end != null ? RoadTransitions.Section.of(end) : ends.end()
         );
      }

      return previous == null ? joined : previous.retainAuthoredLaneEnds(joined, keepStart, keepEnd);
   }

   private RoadGeometry.Settings retainAuthoredLaneEnds(RoadGeometry.Settings proposed, boolean keepStart, boolean keepEnd) {
      if (!keepStart && !keepEnd) {
         return proposed;
      } else {
         RoadTransitions.Ends old = this.settings.options().ends();
         RoadTransitions.Ends next = proposed.options().ends();
         return proposed.taper(keepStart ? this.settings.startWidth() : proposed.startWidth(), keepEnd ? this.settings.endWidth() : proposed.endWidth())
            .options(
               proposed.options()
                  .ends(
                     new RoadTransitions.Ends(
                        keepStart ? old.start() : next.start(),
                        keepEnd ? old.end() : next.end(),
                        next.persistent(),
                        next.trimmedStart(),
                        next.trimmedEnd(),
                        next.port(),
                        next.paintPhase(),
                        next.startHold(),
                        next.endHold()
                     )
                  )
            );
      }
   }

   private static boolean retainedLaneEnd(RoadGeometry.Mesh built, boolean first, RoadTransitions.Section candidate) {
      if (candidate != null && RoadEndpointSections.changed(built, first)) {
         RoadGeometry.Settings actual = RoadEndpointSections.section(built, first, false);
         RoadGeometry.Settings proposed = candidate.settings(built.settings().options().leftTraffic());
         if (RoadLanes.counts(actual).equals(RoadLanes.counts(proposed)) && !(Math.abs(actual.width() - candidate.width()) > 1.0E-5)) {
            RoadProfile.Layout a = RoadProfile.layout(actual, actual.width());
            RoadProfile.Layout b = candidate.layout(built.settings().options().leftTraffic());
            if (!(Math.abs(a.motorMin() - b.motorMin()) > 1.0E-5)
               && !(Math.abs(a.motorMax() - b.motorMax()) > 1.0E-5)
               && !(Math.abs(a.median() - b.median()) > 1.0E-5)
               && !(Math.abs(a.medianCenter() - b.medianCenter()) > 1.0E-5)
               && !(Math.abs(a.laneWidth() - b.laneWidth()) > 1.0E-5)
               && a.dividers().size() == b.dividers().size()) {
               for (int i = 0; i < a.dividers().size(); i++) {
                  if (Math.abs(a.dividers().get(i) - b.dividers().get(i)) > 1.0E-5) {
                     return false;
                  }
               }

               return true;
            } else {
               return false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static boolean sameNode(RoadGeometry.Node a, RoadGeometry.Node b) {
      return a.position().distance(b.position()) < 1.0E-7
         && Math.abs(Math.IEEEremainder(a.yaw() - b.yaw(), 360.0)) < 1.0E-6
         && Math.abs(a.grade() - b.grade()) < 1.0E-8;
   }

   public RoadPlanner.Plan retainedPlan(
      RoadGeometry.Settings request, RoadPlanner.Hint a, RoadPlanner.Hint b, RoadGeometry.Node authoredStart, RoadGeometry.Node authoredEnd
   ) {
      if (this.fragment != null && !this.alignment.isEmpty() && sameNode(this.start, authoredStart) && sameNode(this.end, authoredEnd)) {
         boolean auto = this.automatic && request.mode() == RoadGeometry.Mode.AUTO;
         RoadGeometry.Settings wanted = auto ? RoadPlanner.mode(request, this.settings.mode(), request.arcDegrees()) : request;
         RoadRecord probe = new RoadRecord(this.id, this.owner, this.a, this.b, a.node(), b.node(), wanted, this.automatic, this.clearance);
         if (!this.sameSavedEndpoints(probe)) {
            return null;
         } else {
            RoadRecord candidate = new RoadRecord(this.id, this.owner, this.a, this.b, this.start, this.end, wanted, this.automatic, this.clearance);
            RoadRecord kept = this.retainAlignment(candidate);
            if (kept.fragment() == null) {
               return null;
            } else {
               RoadGeometry.Mesh mesh = kept.rawMesh();
               List<RoadGeometry.Sample> samples = mesh.samples().stream().map(p -> {
                  RoadProfile.Layout layout = RoadProfile.layout(mesh, p);
                  return new RoadGeometry.Sample(p.at(layout.catalog().twoWay() ? layout.medianCenter() : 0.0, 0.0), p.left(), p.distance(), p.halfWidth());
               }).toList();
               RoadGeometry.Mesh axis = new RoadGeometry.Mesh(samples, mesh.settings(), mesh.min(), mesh.max(), mesh.length(), mesh.closed());
               return new RoadPlanner.Plan(this.start, this.end, kept.settings(), axis, "保留已保存的道路曲线");
            }
         }
      } else {
         return null;
      }
   }

   public RoadRecord retainAlignment(RoadRecord proposal, RoadGeometry.Settings requested, RoadGeometry.Node authoredStart, RoadGeometry.Node authoredEnd) {
      if (this.fragment != null
         && this.automatic
         && requested.mode() == RoadGeometry.Mode.AUTO
         && sameNode(this.start, authoredStart)
         && sameNode(this.end, authoredEnd)
         && requested.arcDegrees() == this.settings.arcDegrees()
         && this.sameSavedEndpoints(proposal)) {
         RoadGeometry.Settings ps = proposal.settings();
         RoadGeometry.Settings normalized = new RoadGeometry.Settings(
            this.settings.mode(),
            ps.style(),
            ps.width(),
            ps.thickness(),
            requested.tension(),
            this.settings.arcDegrees(),
            ps.startWidth(),
            ps.endWidth(),
            ps.structure(),
            ps.taperVersion(),
            ps.rampTurn(),
            ps.options()
         );
         RoadRecord candidate = new RoadRecord(
            proposal.id(),
            proposal.owner(),
            proposal.a(),
            proposal.b(),
            this.start,
            this.end,
            normalized,
            proposal.automatic(),
            proposal.clearance(),
            proposal.structures(),
            proposal.endCaps(),
            proposal.buildVersion(),
            proposal.assembly(),
            proposal.alignment(),
            proposal.furniturePhase(),
            proposal.junction(),
            proposal.toll(),
            proposal.fragment()
         );
         RoadRecord retained = this.retainAlignment(candidate);
         if (retained.fragment() != null) {
            return retained;
         }
      }

      return this.retainAlignment(proposal);
   }

   private boolean sameSavedEndpoints(RoadRecord proposal) {
      boolean a = this.start.position().distance(proposal.start().position()) < 1.0E-7;
      boolean b = this.end.position().distance(proposal.end().position()) < 1.0E-7;
      if (a && b) {
         return true;
      } else {
         RoadGeometry.Mesh saved = this.caps(0).mesh();
         return (a || RoadMedianAnchor.position(saved, true).distance(proposal.start().position()) < 1.0E-7)
            && (b || RoadMedianAnchor.position(saved, false).distance(proposal.end().position()) < 1.0E-7);
      }
   }

   public RoadRecord retainAlignment(RoadRecord proposal) {
      RoadGeometry.Settings s = proposal.settings();
      RoadProfile.Options a = this.settings.options();
      RoadProfile.Options b = s.options();
      if (!this.alignment.isEmpty()
         && sameNode(this.start, proposal.start())
         && sameNode(this.end, proposal.end())
         && this.settings.mode() == s.mode()
         && this.settings.tension() == s.tension()
         && this.settings.arcDegrees() == s.arcDegrees()
         && this.settings.rampTurn() == s.rampTurn()
         && this.settings.structure() == s.structure()
         && a.routing().equals(b.routing())
         && a.ports().equals(b.ports())
         && a.liftPosition() == b.liftPosition()
         && a.liftHeight() == b.liftHeight()
         && a.infrastructure().tunnelDepth() == b.infrastructure().tunnelDepth()
         && a.infrastructure().bridgeRise() == b.infrastructure().bridgeRise()
         && a.infrastructure().efficientDip() == b.infrastructure().efficientDip()
         && a.infrastructure().maxGrade() == b.infrastructure().maxGrade()
         && a.infrastructure().adjustment() == b.infrastructure().adjustment()) {
         if (this.fragment != null) {
            if (!authoredWidthChanged(this.settings, s)) {
               s = s.taper(this.settings.startWidth(), this.settings.endWidth()).options(s.options().ends(this.settings.options().ends()));
            } else {
               RoadTransitions.Ends ends = s.options().ends();
               RoadTransitions.Ends old = this.settings.options().ends();
               s = s.options(
                  s.options()
                     .ends(
                        new RoadTransitions.Ends(ends.start(), ends.end(), ends.persistent())
                           .port(ends.port())
                           .paintPhase(old.paintPhase() + old.trimmedStart())
                     )
               );
            }
         }

         RoadRecord adjusted = this.settings(s);
         return new RoadRecord(
            proposal.id(),
            proposal.owner(),
            proposal.a(),
            proposal.b(),
            this.start,
            this.end,
            s,
            proposal.automatic(),
            proposal.clearance(),
            List.of(),
            proposal.endCaps(),
            proposal.buildVersion(),
            proposal.assembly(),
            adjusted.alignment(),
            this.furniturePhase,
            proposal.junction(),
            proposal.toll(),
            adjusted.fragment()
         );
      } else {
         return proposal;
      }
   }

   public RoadRecord settings(RoadGeometry.Settings value) {
      LanePoints.Data slots = LaneAdditions.reserveAuthored(value.options().lanePoints(), RoadProfile.catalog(value).lanes());
      if (slots != value.options().lanePoints()) {
         return this.settings(value.options(value.options().lanePoints(slots)));
      } else if (this.settings.equals(value)) {
         return this;
      } else {
         List<RoadGeometry.Sample> adjusted = this.alignment;
         RoadSlicing.Fragment retainedFragment = this.fragment;
         if (!this.alignment.isEmpty() && widthChanged(this.settings, value)) {
            if (this.fragment != null) {
               retainedFragment = this.fragment.geometry(new RoadSlicing.Geometry(List.of()));
            }

            double length = this.alignment.get(this.alignment.size() - 1).distance();
            RoadGeometry.Mesh before = this.fragment == null ? null : RoadSlicing.mesh(this.alignment, this.settings);
            adjusted = this.alignment
               .stream()
               .map(
                  p -> new RoadGeometry.Sample(
                        p.center().add(p.left().mul(this.fragment == null ? 0.0 : this.medianShift(before, p, value, length))),
                        p.left(),
                        p.distance(),
                        RoadTransitions.width(
                              value,
                              p.distance() + value.options().ends().trimmedStart(),
                              length + value.options().ends().trimmedStart() + value.options().ends().trimmedEnd()
                           )
                           / 2.0
                     )
               )
               .toList();
         }

         return new RoadRecord(
            this.id,
            this.owner,
            this.a,
            this.b,
            this.start,
            this.end,
            value,
            this.automatic,
            this.clearance,
            List.of(),
            this.endCaps,
            this.buildVersion,
            this.assembly,
            adjusted,
            this.furniturePhase,
            this.junction,
            this.toll,
            retainedFragment
         );
      }
   }

   private double medianShift(RoadGeometry.Mesh before, RoadGeometry.Sample point, RoadGeometry.Settings next, double length) {
      double width = RoadTransitions.width(
         next, point.distance() + next.options().ends().trimmedStart(), length + next.options().ends().trimmedStart() + next.options().ends().trimmedEnd()
      );
      RoadGeometry.Mesh mesh = new RoadGeometry.Mesh(before.samples(), next, before.min(), before.max(), length, false);
      RoadGeometry.Sample after = new RoadGeometry.Sample(point.center(), point.left(), point.distance(), width / 2.0);
      return RoadProfile.layout(before, point).medianCenter() - RoadProfile.layout(mesh, after).medianCenter();
   }

   private byte[] packAlignment() {
      try {
         ByteArrayOutputStream bytes = new ByteArrayOutputStream();
         DataOutputStream out = new DataOutputStream(bytes);
         out.writeInt(this.fragment == null ? this.alignment.size() : -this.alignment.size());

         for (RoadGeometry.Sample p : this.alignment) {
            writeVector(out, p.center());
            out.writeDouble(p.left().x());
            out.writeDouble(p.left().z());
            out.writeDouble(p.halfWidth());
            if (this.fragment != null) {
               out.writeDouble(p.distance());
            }
         }

         return bytes.toByteArray();
      } catch (IOException var5) {
         throw new IllegalArgumentException("道路路径编码失败", var5);
      }
   }

   private static List<RoadGeometry.Sample> unpackAlignment(byte[] bytes) {
      try {
         DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
         int count = in.readInt();
         boolean stations = count < 0;
         int n = Math.abs(count);
         if (n >= 2 && n <= 16384 && bytes.length == 4 + n * (stations ? 56 : 48)) {
            List<RoadGeometry.Sample> samples = new ArrayList<>();
            double distance = 0.0;
            RoadGeometry.V previous = null;

            for (int i = 0; i < n; i++) {
               RoadGeometry.V p = readVector(in);
               RoadGeometry.V left = new RoadGeometry.V(in.readDouble(), 0.0, in.readDouble());
               double half = in.readDouble();
               if (!RoadGeometry.finite(p.x(), p.y(), p.z(), left.x(), left.z(), half)
                  || half < 0.25
                  || half > 32.0
                  || Math.abs(left.horizontalLength() - 1.0) > 0.001) {
                  throw new IOException("invalid path point");
               }

               if (stations) {
                  double next = in.readDouble();
                  if (!Double.isFinite(next) || next < 0.0 || previous != null && next <= distance) {
                     throw new IOException("invalid path station");
                  }

                  distance = next;
               } else if (previous != null) {
                  distance += p.distance(previous);
               }

               samples.add(new RoadGeometry.Sample(p, left, distance, half));
               previous = p;
            }

            return List.copyOf(samples);
         } else {
            throw new IOException("invalid alignment");
         }
      } catch (IOException var16) {
         throw new IllegalArgumentException("道路路径数据损坏", var16);
      }
   }

   public static void clearMeshCaches() {
      RoadRaster.clearSavedRasters();
      RoadTunnelSpace.clearPreparedCache();
      RAW_MESHES.clear();
      SHAPED_MESHES.clear();
      TOLL_PLANS.clear();
   }

   public static String meshCacheStats() {
      return "raw=" + RAW_MESHES.stats() + ", shaped=" + SHAPED_MESHES.stats();
   }

   public boolean legacyRailway() {
      return this.railway() && !this.settings.options().railbed().active();
   }

   public boolean railway() {
      return this.settings.style() == RoadGeometry.Style.RAILBED;
   }

   public RoadGeometry.Mesh mesh() {
      return this.railway()
         ? this.rawMesh()
         : SHAPED_MESHES.get(
            this,
            r -> {
               RoadGeometry.Mesh mesh = RoadsideBypass.sampled(LaneSections.apply(r.rawMesh()));
               return RailwayCrossings.shape(
                  r.fragment != null && r.fragment.applied().points().equals(r.settings.options().attachments().points())
                     ? RoadSlicing.attach(mesh, r.fragment.geometry())
                     : mesh
               );
            }
         );
   }

   public RoadGeometry.Mesh rawMesh() {
      return RAW_MESHES.get(this, RoadRecord::buildRawMesh);
   }

   private RoadGeometry.Mesh buildRawMesh() {
      if (this.toll != null) {
         return this.tollPlan().mesh();
      } else if (this.junction != null) {
         RoadGeometry.Mesh mesh = this.junction.get().mesh();
         if (this.junction.spec().oneSide() != null) {
            return mesh;
         } else if (this.junction.geometryVersion() >= 47 && this.junction.get().arm() >= 0) {
            RoadGeometry.Settings section = mesh.settings().options(mesh.settings().options().streetscape(this.settings.options().streetscape()));
            return this.junction.geometryVersion() >= 49 && JunctionSlips.enabled(this.junction.spec())
               ? new RoadGeometry.Mesh(mesh.samples(), section, mesh.min(), mesh.max(), mesh.length(), mesh.closed())
               : RoadRibbon.mesh(mesh.samples(), section);
         } else {
            return mesh;
         }
      } else if (!this.alignment.isEmpty()) {
         RoadGeometry.Mesh mesh = this.fragment == null ? RoadRibbon.mesh(this.alignment, this.settings) : RoadSlicing.mesh(this.alignment, this.settings);
         if (this.railway()) {
            return mesh;
         } else if (mesh.first().center().distance(mesh.last().center()) < 1.0E-6) {
            return new RoadGeometry.Mesh(mesh.samples(), mesh.settings(), mesh.min(), mesh.max(), mesh.length(), true, mesh.controlPoint());
         } else {
            return this.fragment == null ? RoadAttachments.deform(mesh, this.settings) : RoadSlicing.deform(mesh, this.settings, this.fragment);
         }
      } else {
         return RoadGeometry.endCaps(
            this.assembly == null
               ? RoadMedianAnchor.apply(RoadGeometry.build(this.start, this.end, this.settings))
               : RoadGeometry.build(this.start, this.end, this.settings),
            this.endCaps,
            this.start.grade(),
            this.end.grade()
         );
      }
   }
}
