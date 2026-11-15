package com.sora.splineroads.world;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.OneSidedJunction;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadSlicing;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class JunctionCodec {
   public static CompoundTag editCommand(CompoundTag payload) {
      CompoundTag command = payload.copy();
      command.remove("FreshSpec");
      if (command.getBoolean("OneSided")) {
         command.remove("OriginalMain");
         command.remove("OriginalMainB");
         command.remove("BranchNode");
         command.getCompound("Spec").remove("OneSide");
         command.getCompound("PairSpec").remove("OneSide");
      }

      return command;
   }

   public static CompoundTag write(JunctionSpec s) {
      CompoundTag t = new CompoundTag();
      t.putInt("Version", 1);
      t.put("Center", RoadRecord.writeNode(new RoadGeometry.Node(s.center(), 0.0, 0.0)));
      t.putString("Kind", s.kind().name());
      t.putBoolean("LeftTraffic", s.leftTraffic());
      t.putDouble("Corner", s.cornerRadius());
      t.putDouble("Island", s.islandRadius());
      t.putInt("RingLanes", s.ringLanes());
      t.putDouble("RingWidth", s.ringLaneWidth());
      t.putDouble("Thickness", s.thickness());
      t.putString("Control", s.control().name());
      t.putInt("Green", s.greenSeconds());
      t.putInt("Yellow", s.yellowSeconds());
      t.putInt("AllRed", s.allRedSeconds());
      t.putInt("Offset", s.timeOffset());
      t.putBoolean("Guides", s.guides());
      t.putBoolean("GreenIsland", s.greenIsland());
      t.putBoolean("OuterRail", s.outerRail());
      t.putBoolean("ParallelApproaches", s.parallelApproaches());
      ListTag arms = new ListTag();

      for (JunctionSpec.Arm a : s.arms()) {
         CompoundTag p = new CompoundTag();
         p.put("Node", RoadRecord.writeNode(a.endpoint()));
         p.putDouble("DX", a.inward().x());
         p.putDouble("DZ", a.inward().z());
         p.put("External", RoadRecord.writeSettings(a.external()));
         p.putBoolean("Attached", a.attached());
         p.putInt("In", a.incoming());
         p.putInt("Out", a.outgoing());
         p.putDouble("Width", a.width());
         p.putDouble("Median", a.median());
         p.putString("MedianKind", a.medianKind().name());
         p.putDouble("Cycle", a.cycleWidth());
         p.putDouble("Curb", a.curbWidth());
         p.putBoolean("RightSlip", a.rightSlip());
         p.putBoolean("AdvanceSign", a.advanceSign());
         p.putBoolean("NameSign", a.nameSign());
         p.putBoolean("Crosswalk", a.crosswalk());
         p.putDouble("CrossWidth", a.crossingWidth());
         p.putDouble("Setback", a.crossingSetback());
         p.putDouble("StopGap", a.stopGap());
         p.putInt("Phase", a.phase());
         ListTag lanes = new ListTag();

         for (JunctionSpec.Lane l : a.lanes()) {
            CompoundTag q = new CompoundTag();
            q.putInt("Mask", l.mask());
            q.putBoolean("SplitLeft", l.splitLeft());
            q.putBoolean("ControlRight", l.controlRight());
            q.putInt("LeftPhase", l.leftPhase());
            q.putIntArray("Targets", l.targets());
            q.putIntArray("TargetLanes", l.targetLanes());
            lanes.add(q);
         }

         p.put("Lanes", lanes);
         arms.add(p);
      }

      t.put("Arms", arms);
      if (s.oneSide() != null) {
         OneSidedJunction.Source source = s.oneSide();
         RoadGeometry.Mesh mesh = source.road();
         CompoundTag p = new CompoundTag();
         RoadRecord record = new RoadRecord(
               new UUID(0L, 0L),
               new UUID(0L, 0L),
               BlockPos.ZERO,
               BlockPos.ZERO,
               new RoadGeometry.Node(mesh.first().center(), 0.0, 0.0),
               new RoadGeometry.Node(mesh.last().center(), 0.0, 0.0),
               mesh.settings()
            )
            .alignment(null, mesh)
            .structures(source.structures());
         record = record.fragment(
            new RoadSlicing.Fragment(
               new UUID(0L, 0L),
               0.0,
               mesh.length(),
               RoadSlicing.Connection.CONTINUOUS,
               mesh.settings().options().attachments(),
               mesh.slicing() == null ? new RoadSlicing.Geometry(List.of()) : mesh.slicing()
            )
         );
         p.put("Road", record.save());
         p.putDouble("From", source.from());
         p.putDouble("To", source.to());
         p.putInt("Side", source.side());
         t.put("OneSide", p);
         t.putBoolean("MainAdvancePolicy", true);
      }

      return t;
   }

   public static JunctionSpec read(CompoundTag t) {
      if (t.getInt("Version") != 1) {
         throw new IllegalArgumentException("不支持的路口数据版本");
      } else {
         ListTag entries = t.getList("Arms", 10);
         if (t.getString("Kind").equals("ROUNDABOUT") ? entries.size() <= 8 : entries.size() >= 2 && entries.size() <= 6) {
            List<JunctionSpec.Arm> arms = new ArrayList<>();

            for (Tag entry : entries) {
               CompoundTag a = (CompoundTag)entry;
               ArrayList<JunctionSpec.Lane> lanes = new ArrayList<>();
               if (a.getList("Lanes", 10).size() > 7) {
                  throw new IllegalArgumentException("入口车道过多");
               }

               for (Tag item : a.getList("Lanes", 10)) {
                  CompoundTag l = (CompoundTag)item;
                  lanes.add(
                     new JunctionSpec.Lane(
                        l.getInt("Mask"),
                        Arrays.stream(l.getIntArray("Targets")).boxed().toList(),
                        Arrays.stream(l.getIntArray("TargetLanes")).boxed().toList(),
                        l.getBoolean("SplitLeft"),
                        l.contains("LeftPhase") ? l.getInt("LeftPhase") : -1,
                        l.getBoolean("ControlRight")
                     )
                  );
               }

               arms.add(
                  new JunctionSpec.Arm(
                     RoadRecord.readNode(a.getCompound("Node")),
                     new RoadGeometry.V(a.getDouble("DX"), 0.0, a.getDouble("DZ")),
                     RoadRecord.readSettings(a.getCompound("External")),
                     a.getInt("In"),
                     a.getInt("Out"),
                     a.getDouble("Width"),
                     a.getDouble("Median"),
                     RoadProfile.Median.valueOf(a.getString("MedianKind")),
                     a.getDouble("Cycle"),
                     a.getDouble("Curb"),
                     a.getBoolean("Crosswalk"),
                     a.getDouble("CrossWidth"),
                     a.getDouble("Setback"),
                     a.getDouble("StopGap"),
                     a.getInt("Phase"),
                     lanes,
                     a.getBoolean("Attached"),
                     !a.contains("AdvanceSign") || a.getBoolean("AdvanceSign"),
                     !a.contains("NameSign") || a.getBoolean("NameSign"),
                     a.getBoolean("RightSlip")
                  )
               );
            }

            OneSidedJunction.Source source = readSide(t);
            if (source != null && arms.size() == 3) {
               for (int i = 0; i < 3; i++) {
                  JunctionSpec.Arm arm = arms.get(i);
                  boolean enabled = i == source.incoming() && arms.get(2).outgoing() > 0 && (!t.getBoolean("MainAdvancePolicy") || arm.advanceSign());
                  arms.set(i, arm.signs(enabled, arm.nameSign()));
               }
            }

            return new JunctionSpec(
                  RoadRecord.readNode(t.getCompound("Center")).position(),
                  JunctionSpec.Kind.valueOf(t.getString("Kind")),
                  t.getBoolean("LeftTraffic"),
                  t.getDouble("Corner"),
                  t.getDouble("Island"),
                  t.getInt("RingLanes"),
                  t.getDouble("RingWidth"),
                  t.getDouble("Thickness"),
                  JunctionSpec.Control.valueOf(t.getString("Control")),
                  t.getInt("Green"),
                  t.getInt("Yellow"),
                  t.getInt("AllRed"),
                  t.getInt("Offset"),
                  t.getBoolean("Guides"),
                  t.getBoolean("GreenIsland"),
                  t.getBoolean("OuterRail"),
                  arms,
                  t.getBoolean("ParallelApproaches")
               )
               .oneSide(source);
         } else {
            throw new IllegalArgumentException("普通路口支持 2–6 向，环岛支持 0–8 个接入口");
         }
      }
   }

   private static OneSidedJunction.Source readSide(CompoundTag t) {
      if (!t.contains("OneSide")) {
         return null;
      } else {
         CompoundTag p = t.getCompound("OneSide");
         CompoundTag saved = p.getCompound("Road");
         if (!saved.contains("Junction") && !saved.contains("Toll")) {
            RoadRecord r = RoadRecord.load(saved);
            if (r.junction() == null && r.toll() == null) {
               return new OneSidedJunction.Source(r.mesh(), r.structures(), p.getDouble("From"), p.getDouble("To"), p.getInt("Side"));
            } else {
               throw new IllegalArgumentException("单边路口主路快照无效");
            }
         } else {
            throw new IllegalArgumentException("单边路口主路快照无效");
         }
      }
   }

   public static CompoundTag writeRef(JunctionPlanner.Ref ref) {
      CompoundTag t = write(ref.spec());
      t.putInt("Piece", ref.piece());
      t.putInt("GeometryVersion", ref.geometryVersion());
      if (!ref.hostCuts().isEmpty()) {
         ListTag cuts = new ListTag();

         for (OneSidedJunction.Cut cut : ref.hostCuts()) {
            CompoundTag c = new CompoundTag();
            c.putDouble("From", cut.from());
            c.putDouble("To", cut.to());
            c.putInt("Side", cut.side());
            cuts.add(c);
         }

         t.put("HostCuts", cuts);
      }

      return t;
   }

   public static JunctionPlanner.Ref readRef(CompoundTag t) {
      ArrayList<OneSidedJunction.Cut> cuts = new ArrayList<>();

      for (Tag value : t.getList("HostCuts", 10)) {
         CompoundTag c = (CompoundTag)value;
         cuts.add(new OneSidedJunction.Cut(c.getDouble("From"), c.getDouble("To"), c.getInt("Side")));
      }

      return new JunctionPlanner.Ref(read(t), t.getInt("Piece"), t.contains("GeometryVersion") ? t.getInt("GeometryVersion") : 21, cuts);
   }

   private JunctionCodec() {
   }
}
