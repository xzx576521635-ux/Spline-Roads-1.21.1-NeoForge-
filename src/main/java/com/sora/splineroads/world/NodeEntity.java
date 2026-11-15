package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadGeometry;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class NodeEntity extends BlockEntity {
   public double yaw;
   public double grade;
   public double offsetX;
   public double offsetY;
   public double offsetZ;
   public boolean headingLocked;
   public boolean gradeLocked;
   public boolean heightExplicit;
   public boolean plannerHeading;
   public UUID owner;

   public NodeEntity(BlockPos pos, BlockState state) {
      super((BlockEntityType)SplineRoads.NODE_ENTITY.get(), pos, state);
   }

   public RoadGeometry.Node node() {
      return new RoadGeometry.Node(
         new RoadGeometry.V(
            (double)this.worldPosition.getX() + 0.5 + this.offsetX,
            (double)this.worldPosition.getY() + this.offsetY,
            (double)this.worldPosition.getZ() + 0.5 + this.offsetZ
         ),
         this.yaw,
         this.grade
      );
   }

   public RoadGeometry.Node constructionNode() {
      RoadGeometry.Node n = this.node();
      return !this.heightExplicit && Math.abs(this.offsetY - 0.25) < 1.0E-8
         ? new RoadGeometry.Node(new RoadGeometry.V(n.position().x(), (double)this.worldPosition.getY(), n.position().z()), n.yaw(), n.grade())
         : n;
   }

   public void apply(RoadGeometry.Node node) {
      this.offsetX = node.position().x() - (double)this.worldPosition.getX() - 0.5;
      this.offsetY = node.position().y() - (double)this.worldPosition.getY();
      this.offsetZ = node.position().z() - (double)this.worldPosition.getZ() - 0.5;
      this.yaw = node.yaw();
      this.grade = node.grade();
      this.setChanged();
      if (this.level != null) {
         this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
      }
   }

   protected void saveAdditional(CompoundTag t, Provider prov) {
      super.saveAdditional(t, prov);
      t.put("Node", RoadRecord.writeNode(this.node()));
      t.putBoolean("HeightExplicit", this.heightExplicit);
      t.putBoolean("PlannerHeading", this.plannerHeading);
      t.putBoolean("HeadingLocked", this.headingLocked);
      t.putBoolean("GradeLocked", this.gradeLocked);
      if (this.owner != null) {
         t.putUUID("Owner", this.owner);
      }
   }

   public void readSnapshot(CompoundTag t, Provider prov) {
      this.loadAdditional(t, prov);
   }

   protected void loadAdditional(CompoundTag t, Provider prov) {
      super.loadAdditional(t, prov);
      if (t.contains("Node")) {
         RoadGeometry.Node n = RoadRecord.readNode(t.getCompound("Node"));
         this.offsetX = n.position().x() - (double)this.worldPosition.getX() - 0.5;
         this.offsetY = n.position().y() - (double)this.worldPosition.getY();
         this.offsetZ = n.position().z() - (double)this.worldPosition.getZ() - 0.5;
         this.yaw = n.yaw();
         this.grade = n.grade();
      }

      if (t.hasUUID("Owner")) {
         this.owner = t.getUUID("Owner");
      }

      this.heightExplicit = t.contains("HeightExplicit")
         ? t.getBoolean("HeightExplicit")
         : Math.abs(this.offsetY) > 1.0E-8 && Math.abs(this.offsetY - 0.25) > 1.0E-8;
      this.plannerHeading = t.getBoolean("PlannerHeading");
      this.headingLocked = t.getBoolean("HeadingLocked");
      this.gradeLocked = t.getBoolean("GradeLocked");
   }

   public CompoundTag getUpdateTag(Provider prov) {
      return this.saveWithoutMetadata(prov);
   }

   public ClientboundBlockEntityDataPacket getUpdatePacket() {
      return ClientboundBlockEntityDataPacket.create(this);
   }
}
