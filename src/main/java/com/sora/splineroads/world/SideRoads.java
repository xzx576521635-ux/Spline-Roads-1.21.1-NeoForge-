package com.sora.splineroads.world;

import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadTransitions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;

public final class SideRoads {
   public static CompoundTag payload(CompoundTag junction) {
      CompoundTag t = junction.copy();
      JunctionSpec spec = JunctionCodec.read(t.getCompound("Spec"));
      JunctionSpec.Arm arm = spec.arms().get(2);
      t.putString("Kind", "sideRoad");
      t.putInt("Signature", signature(junction));
      t.put("Settings", RoadRecord.writeSettings(JunctionRoads.editable(arm)));
      t.put("StartNode", RoadRecord.writeNode(arm.endpoint()));
      t.put("EndNode", RoadRecord.writeNode(new RoadGeometry.Node(spec.center(), RoadPlanner.yaw(arm.inward()), 0.0)));
      return t;
   }

   public static int signature(CompoundTag t) {
      return Objects.hash(t.getCompound("Spec"), t.getCompound("PairSpec"), t.getInt("MainSignature"), t.getInt("MainSignatureB"), t.getInt("BranchSignature"));
   }

   public static CompoundTag action(CompoundTag draft) {
      CompoundTag t = new CompoundTag();

      for (String key : List.of(
         "Id",
         "Main",
         "AttachedPoint",
         "Branch",
         "MainFirst",
         "Paired",
         "MainB",
         "AttachedPointB",
         "Fallback",
         "MainSignature",
         "MainSignatureB",
         "BranchSignature",
         "Signature",
         "Settings"
      )) {
         if (draft.contains(key)) {
            t.put(key, draft.get(key).copy());
         }
      }

      t.putString("Action", "junctionSideRoad");
      return t;
   }

   public static RoadGeometry.Settings settings(RoadGeometry.Settings wanted, RoadGeometry.Settings outside, boolean attached) {
      wanted.validate();
      if (RoadProfile.catalog(wanted.style()).type() == RoadProfile.Type.ORDINARY && !wanted.options().leftTraffic()) {
         RoadProfile.Options options = wanted.options()
            .lanePoints(LanePoints.Data.EMPTY)
            .attachments(RoadAttachments.Data.EMPTY)
            .route(outside.options().routing())
            .ends(attached ? outside.options().ends() : RoadTransitions.Ends.NONE);
         return new RoadGeometry.Settings(outside.mode(), wanted.style(), wanted.width(), outside.thickness(), outside.tension(), outside.arcDegrees())
            .structure(outside.structure())
            .options(options)
            .taper(attached ? outside.startWidth() : wanted.width(), wanted.width());
      } else {
         throw new IllegalArgumentException("附属点连接请选择右侧通行的普通道路");
      }
   }

   public static JunctionSpec.Arm change(JunctionSpec.Arm old, RoadGeometry.Settings wanted, boolean incoming) {
      RoadGeometry.Settings settings = settings(wanted, old.external(), old.attached());
      JunctionSpec.Arm fresh = JunctionSpec.arm(old.endpoint(), old.inward(), settings, incoming, old.phase());
      ArrayList<JunctionSpec.Lane> lanes = new ArrayList<>();

      for (int i = 0; i < fresh.incoming(); i++) {
         lanes.add(i < old.lanes().size() ? old.lanes().get(i) : JunctionSpec.Lane.AUTO);
      }

      return new JunctionSpec.Arm(
         old.endpoint(),
         old.inward(),
         settings,
         fresh.incoming(),
         fresh.outgoing(),
         fresh.width(),
         fresh.median(),
         fresh.medianKind(),
         fresh.cycleWidth(),
         fresh.curbWidth(),
         old.crosswalk(),
         old.crossingWidth(),
         old.crossingSetback(),
         old.stopGap(),
         old.phase(),
         lanes,
         old.attached(),
         old.advanceSign(),
         old.nameSign(),
         false
      );
   }

   public static JunctionSpec preview(JunctionSpec spec, RoadGeometry.Settings wanted, boolean incoming) {
      ArrayList<JunctionSpec.Arm> arms = new ArrayList<>(spec.arms());
      arms.set(2, change(arms.get(2), wanted, incoming));
      return spec.arms(arms).supportedCrossings();
   }

   private SideRoads() {
   }
}
