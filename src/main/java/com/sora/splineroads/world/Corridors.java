package com.sora.splineroads.world;

import com.sora.splineroads.core.CorridorPlanner;
import com.sora.splineroads.core.InterchangePlanner;
import com.sora.splineroads.core.RoadGeometry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class Corridors {
   public static CompoundTag write(CorridorPlanner.Config c) {
      CompoundTag t = new CompoundTag();
      t.putString("Mode", c.kind().name());
      t.putString("Sides", c.sides().name());
      t.putString("Access", c.access().name());
      t.putDouble("Gap", c.gap());
      t.putDouble("FrontageY", c.frontageY());
      t.putString("Adjustment", c.adjustment().name());
      return t;
   }

   public static CorridorPlanner.Config read(CompoundTag t) {
      return new CorridorPlanner.Config(
         CorridorPlanner.Kind.valueOf(t.getString("Mode")),
         CorridorPlanner.Sides.valueOf(t.getString("Sides")),
         CorridorPlanner.Access.valueOf(t.getString("Access")),
         t.getDouble("Gap"),
         t.getDouble("FrontageY"),
         t.contains("Adjustment") ? CorridorPlanner.Adjustment.valueOf(t.getString("Adjustment")) : CorridorPlanner.Adjustment.AUTO
      );
   }

   public static CompoundTag initialize(CompoundTag t, CorridorPlanner.Kind kind) {
      t.putString("Kind", "corridor");
      if (!t.contains("Corridor")) {
         RoadGeometry.Node a = RoadRecord.readNode(t.getList("Nodes", 10).getCompound(0));
         t.put(
            "Corridor",
            write(
               new CorridorPlanner.Config(
                  kind,
                  CorridorPlanner.Sides.BOTH,
                  CorridorPlanner.Access.BOTH,
                  kind == CorridorPlanner.Kind.LAYERED ? 0.0 : 12.0,
                  a.position().y(),
                  CorridorPlanner.Adjustment.AUTO
               )
            )
         );
         t.put("Options", Interchanges.write(new InterchangePlanner.Options(InterchangePlanner.Preset.CLOVERLEAF, false, 1, 48.0, 20.0, 5.0, 1).adjust(true)));
         if (kind == CorridorPlanner.Kind.FRONTAGE) {
            t.put(
               "Main2",
               RoadRecord.writeSettings(
                  new RoadGeometry.Settings(RoadGeometry.Mode.STRAIGHT, RoadGeometry.Style.O2_ONE, RoadGeometry.Style.O2_ONE.defaultWidth(), 1.0, 0.4, 90.0)
               )
            );
         }
      }

      HostAxes.normalizeCorridor(t);
      return t;
   }

   public static CorridorPlanner.Config effective(CompoundTag t) {
      CorridorPlanner.Config c = read(t.getCompound("Corridor"));
      return c.adjustment() == CorridorPlanner.Adjustment.AUTO && t.contains("ResolvedFit")
         ? c.adjustment(CorridorPlanner.Adjustment.valueOf(t.getString("ResolvedFit")))
         : c;
   }

   public static CompoundTag preview(ServerLevel level, ServerPlayer player, CompoundTag command) {
      CompoundTag t = Interchanges.payload(level, player, command.getLongArray("Points"), command.hasUUID("Id") ? command.getUUID("Id") : null);

      for (String key : List.of("Main1", "Main2", "Options", "Corridor")) {
         t.put(key, command.getCompound(key).copy());
      }

      if (!t.contains("Corridor")) {
         throw new IllegalArgumentException("缺少道路组合参数");
      } else {
         resolve(level, player, t);
         return t;
      }
   }

   public static InterchangePlanner.Plan resolve(ServerLevel level, ServerPlayer player, CompoundTag t) {
      try (RoadWorkChunks chunks = RoadWorkChunks.open(level)) {
         RoadData data = RoadData.get(level);
         UUID group = t.hasUUID("Id") ? t.getUUID("Id") : null;
         Interchanges.requireNoEndpointJunction(data, group);
         long[] points = t.getLongArray("Points");
         Set<UUID> replaced = new HashSet<>();
         if (group != null) {
            for (RoadIndex.Built road : data.index.roads.values()) {
               if (group.equals(road.record.assembly())) {
                  replaced.add(road.record.id());
               }
            }
         } else {
            for (int i = 0; i + 1 < points.length; i += 2) {
               for (RoadRecord roadx : Interchanges.mainChain(data, BlockPos.of(points[i]), BlockPos.of(points[i + 1]))) {
                  replaced.add(roadx.id());
               }
            }
         }

         HostAxes.normalizeCorridor(t);
         CorridorPlanner.Config c = read(t.getCompound("Corridor"));
         boolean adjust = Interchanges.read(t.getCompound("Options")).adjustEndpoints();
         CorridorPlanner.Adjustment[] candidates = adjust && c.adjustment() == CorridorPlanner.Adjustment.AUTO
            ? new CorridorPlanner.Adjustment[]{CorridorPlanner.Adjustment.BOTH, CorridorPlanner.Adjustment.END, CorridorPlanner.Adjustment.START}
            : new CorridorPlanner.Adjustment[]{c.adjustment() == CorridorPlanner.Adjustment.AUTO ? CorridorPlanner.Adjustment.BOTH : c.adjustment()};
         List<String> reasons = new ArrayList<>();

         for (CorridorPlanner.Adjustment mode : candidates) {
            t.putString("ResolvedFit", mode.name());

            try {
               InterchangePlanner.Plan plan = Interchanges.plan(t);

               for (InterchangePlanner.Leg leg : plan.legs()) {
                  UUID source = ContinuousRoads.sourceId(leg.name());
                  if (source != null) {
                     replaced.add(source);
                  }
               }

               Set<BlockPos> sources = new HashSet<>();

               for (long p : points) {
                  sources.add(BlockPos.of(p));
               }

               for (int i = 0; i < points.length; i++) {
                  BlockPos from = BlockPos.of(points[i]);
                  InterchangeEndpoints.Endpoint endpoint = InterchangeEndpoints.read(level, player, t, i);
                  RoadGeometry.Node target = plan.anchors().get(i);
                  if (!endpoint.node().equals(target) && !ContinuousRoads.virtual(t, i, target)) {
                     if (data.index.atNode(from).stream().anyMatch(id -> !replaced.contains(id))) {
                        SharedRoadEndpoints.validate(data, from, target);
                     }

                     RoadGeometry.V pos = target.position();
                     BlockPos to = BlockPos.containing(pos.x(), pos.y(), pos.z());
                     chunks.node(to);
                     if (level.isOutsideBuildHeight(to) || !level.getWorldBorder().isWithinBounds(to)) {
                        throw new IllegalArgumentException("调整后的端点超出世界高度或边界");
                     }

                     if (player != null && !level.mayInteract(player, to)) {
                        throw new IllegalArgumentException("调整后的端点位于受保护区域");
                     }

                     BlockState state = level.getBlockState(to);
                     BlockEntity entity = level.getBlockEntity(to);
                     boolean source = sources.contains(to) && entity instanceof NodeEntity;
                     if (!source
                        && (
                           entity != null
                              || state.getDestroySpeed(level, to) < 0.0F
                              || RoadBlocks.isCollider(state) && data.index.at(to.asLong()).stream().anyMatch(id -> !replaced.contains(id))
                        )) {
                        throw new IllegalArgumentException("端点目标被占用：" + to.toShortString() + "（" + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "）");
                     }
                  }
               }

               for (InterchangePlanner.Leg legx : plan.legs()) {
                  for (RoadIndex.Built old : data.index.roads.values()) {
                     if (!replaced.contains(old.record.id()) && RoadIndex.overlapXZ(legx.mesh(), old.mesh, 1.0)) {
                        Interchanges.checkExternal(legx.mesh(), old.mesh);
                     }
                  }
               }

               return plan;
            } catch (IllegalArgumentException var28) {
               reasons.add(mode.label + "：" + var28.getMessage());
            }
         }

         t.remove("ResolvedFit");
         throw new IllegalArgumentException(String.join("；", reasons));
      }
   }

   private Corridors() {
   }
}
