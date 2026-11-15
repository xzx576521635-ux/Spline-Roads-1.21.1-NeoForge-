package com.sora.splineroads.world;

import com.sora.splineroads.core.LaneExpansion;
import com.sora.splineroads.core.LaneMerge;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.TollPlanner;
import com.sora.splineroads.net.RoadNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class LanePointTool extends Item {
   private static final Map<ServerPlayer, LanePointTool.Pending> OPENING = new WeakHashMap<>();

   public LanePointTool() {
      super(new Properties().stacksTo(1));
   }

   static UUID pendingToken(ServerPlayer p) {
      LanePointTool.Pending v = OPENING.get(p);
      return v == null ? null : v.token();
   }

   public static void acknowledge(ServerPlayer p, UUID token) {
      LanePointTool.Pending v = OPENING.get(p);
      if (v != null && v.token().equals(token)) {
         OPENING.remove(p);
      }
   }

   private static void open(ServerPlayer p, RoadRecord r, LanePoints.Point point) {
      CompoundTag t = payload(RoadData.get(p.serverLevel()), r, point);
      UUID token = UUID.randomUUID();
      OPENING.put(p, new LanePointTool.Pending(token, p.serverLevel().getGameTime() + 100L));
      t.putUUID("Interaction", token);
      RoadNetwork.open(p, t);
   }

   public static LanePointTool.Pick pick(ServerPlayer player) {
      Vec3 eye = player.getEyePosition();
      double stop = player.pick(96.0, 0.0F, false).getLocation().distanceTo(eye) + 0.7;
      return pick(RoadData.get(player.serverLevel()).index, eye, player.getLookAngle(), stop, player.isShiftKeyDown());
   }

   static LanePointTool.Pick pick(RoadIndex index, Vec3 eye, Vec3 direction, double stop, boolean preferAutomatic) {
      Vec3 end = eye.add(direction.scale(96.0));
      ArrayList<LanePointTool.Pick> hits = new ArrayList<>();
      RoadGeometry.V origin = new RoadGeometry.V(eye.x, eye.y, eye.z);
      RoadGeometry.V vector = new RoadGeometry.V(direction.x, direction.y, direction.z);

      for (RoadPointIndex.Marker marker : index.lanePointCandidates(origin, vector, 96.0)) {
         RoadIndex.Built road = index.roads.get(marker.road());
         if (road != null) {
            Optional<Vec3> hit = RoadPointIndex.bounds(marker.point()).clip(eye, end);
            if (hit.isPresent() && hit.get().distanceTo(eye) < stop) {
               hits.add(new LanePointTool.Pick(road.record, marker.point(), hit.get().distanceTo(eye)));
            }
         }
      }

      hits.sort(Comparator.comparingDouble(LanePointTool.Pick::distance));
      if (hits.isEmpty()) {
         return null;
      } else {
         double nearest = hits.get(0).distance();
         return hits.stream()
            .filter(h -> h.distance() < nearest + 0.15)
            .min(Comparator.comparingInt(h -> h.point().automatic() == preferAutomatic ? 0 : 1))
            .orElse(hits.get(0));
      }
   }

   public static LanePoints.Point create(ServerLevel level, ServerPlayer player, UUID id, RoadGeometry.V hit) {
      RoadData data = RoadData.get(level);
      RoadIndex.Built b = data.index.roads.get(id);
      if (b != null && !b.record.railway() && b.record.junction() == null && b.record.toll() == null) {
         if (player != null) {
            RoadData.requireOwner(player, b.record.owner());
         }

         for (RoadIndex.Built other : data.index.roads.values()) {
            if (other.record.toll() != null && TollPlanner.contains(other.mesh, hit, 0.03, other.record.toll().config().clearHeight())) {
               throw new IllegalArgumentException("收费站内部和 A/B 接口不能创建匝道车道点");
            }
         }

         LanePoints.Lane lane = LanePoints.clicked(b.mesh, hit);
         LanePoints.Data md = LaneTopology.metadata(b.record);
         Optional<LanePoints.Point> existing = md.points()
            .stream()
            .filter(px -> !px.automatic() && px.lane() == lane.index() && px.position().distance(lane.position()) < 0.65)
            .findFirst();
         if (existing.isPresent()) {
            return existing.get();
         } else {
            LanePoints.Point p = LanePoints.point(UUID.randomUUID(), LanePoints.Origin.MANUAL, b.mesh, lane.station(), lane.index());
            ArrayList<LanePoints.Point> points = new ArrayList<>(md.points());
            points.add(p);
            update(level, player, b.record, md.points(points), Set.of());
            return p;
         }
      } else {
         throw new IllegalArgumentException("请在独立路段的机动车道上放点");
      }
   }

   private void select(ServerPlayer p, RoadGeometry.V hit) {
      LanePointTool.Pending pending = OPENING.get(p);
      if (pending == null || p.serverLevel().getGameTime() >= pending.expires()) {
         try {
            if (!p.mayBuild() || p.isSpectator()) {
               throw new IllegalArgumentException("没有建设权限");
            }

            LanePointTool.Pick picked = pick(p);
            if (picked != null
               && (
                  !p.isShiftKeyDown()
                     || !picked.point().automatic()
                     || !LaneTopology.metadata(picked.road())
                        .points()
                        .stream()
                        .noneMatch(v -> !v.automatic() && v.position().distance(picked.point().position()) < 0.65)
               )) {
               RoadData.requireOwner(p, picked.road().owner());
               open(p, picked.road(), picked.point());
               return;
            }

            RoadIndex.Built b = RoadTool.pick(p);
            if (b == null || hit == null) {
               throw new IllegalArgumentException("请右键机动车道创建蓝色车道点，或右键已有蓝色点");
            }

            LanePoints.Point point = create(p.serverLevel(), p, b.record.id(), hit);
            open(p, RoadData.get(p.serverLevel()).index.roads.get(b.record.id()).record, point);
         } catch (IllegalArgumentException var7) {
            p.displayClientMessage(Component.literal(var7.getMessage()), false);
         }
      }
   }

   public InteractionResult useOn(UseOnContext c) {
      if (c.getPlayer() instanceof ServerPlayer p) {
         Vec3 h = c.getClickLocation();
         this.select(p, new RoadGeometry.V(h.x, h.y, h.z));
      }

      return InteractionResult.sidedSuccess(c.getLevel().isClientSide);
   }

   public InteractionResultHolder<ItemStack> use(Level l, Player p, InteractionHand hand) {
      if (p instanceof ServerPlayer sp) {
         this.select(sp, null);
      }

      return InteractionResultHolder.sidedSuccess(p.getItemInHand(hand), l.isClientSide);
   }

   public static CompoundTag payload(RoadData data, RoadRecord r, LanePoints.Point p) {
      CompoundTag t = new CompoundTag();
      t.putString("Kind", "lanePoint");
      t.put("Road", r.header());
      t.putUUID("Id", r.id());
      t.putUUID("Point", p.id());
      t.putInt("Signature", r.header().hashCode());
      t.putBoolean("Supported", r.assembly() == null && LanePoints.supported(r.settings()));
      List<UUID> refs = LaneTopology.references(data, LanePoints.Ref.lane(r.id(), p.id()));
      Set<UUID> dependencies = LaneTopology.dependents(data, new HashSet<>(refs));
      TreeSet<UUID> all = new TreeSet<>(refs);
      all.addAll(dependencies);
      ListTag list = new ListTag();
      all.forEach(id -> list.add(StringTag.valueOf(id.toString())));

      for (LaneSections.Cut cut : LaneTopology.metadata(r).cuts()) {
         if (cut.connection().equals(p.id()) && cut.replacement() != null) {
            all.add(cut.replacement());
            all.addAll(LaneTopology.dependents(data, Set.of(cut.replacement())));
         }
      }

      list.clear();
      all.forEach(id -> list.add(StringTag.valueOf(id.toString())));
      t.put("Dependencies", list);
      return t;
   }

   public static String edit(ServerLevel level, ServerPlayer player, CompoundTag t) {
      RoadData data = RoadData.get(level);
      RoadIndex.Built b = data.index.roads.get(t.getUUID("Id"));
      if (b == null) {
         throw new IllegalArgumentException("道路已不存在");
      } else {
         RoadRecord r = b.record;
         if (player != null) {
            RoadData.requireOwner(player, r.owner());
         }

         if (t.getInt("Signature") != r.header().hashCode()) {
            throw new IllegalArgumentException("道路已变化，请重新打开车道点");
         } else {
            LanePoints.Data md = LaneTopology.metadata(r);
            LanePoints.Point p = LaneTopology.point(r, t.getUUID("Point"));
            if (p.automatic()) {
               throw new IllegalArgumentException("自动尽头点不能删除或切换车道");
            } else {
               ArrayList<LanePoints.Point> points = new ArrayList<>(md.points());
               Set<UUID> removed = new HashSet<>();
               if (t.contains("MergeLength")) {
                  if (r.assembly() == null && LanePoints.supported(r.settings())) {
                     double length = t.getDouble("MergeLength");
                     LanePoints.Point next = p.merge(length);
                     if (length > 0.0) {
                        LaneMerge.event(r.rawMesh(), next);
                     } else if (length < 0.0) {
                        LaneExpansion.addition(r.rawMesh(), next, 8);
                     }

                     if (p.mergeLength() > 0.0 && length == 0.0 && md.cuts().stream().anyMatch(c -> c.connection().equals(p.id()) && c.replacement() != null)) {
                        throw new IllegalArgumentException("此空位已有补入匝道，请先删除或改为其他汇入模式，再取消合流缩减");
                     } else {
                        points.set(points.indexOf(p), next);
                        update(level, player, r, md.points(points), Set.of());
                        return length > 0.0 ? "外侧车道已合流缩减，下游空位可补入" : (length < 0.0 ? "已设置外侧扩流，下游新增一条同向车道" : "车道增减已取消，主路已恢复");
                     }
                  } else {
                     throw new IllegalArgumentException("仅独立且支持车道连接的道路可设置合流缩减");
                  }
               } else {
                  if (t.getBoolean("Delete")) {
                     for (LaneSections.Cut cut : md.cuts()) {
                        if (cut.connection().equals(p.id()) && cut.replacement() != null) {
                           removed.add(cut.replacement());
                        }
                     }

                     List<UUID> refs = LaneTopology.references(data, LanePoints.Ref.lane(r.id(), p.id()));
                     removed.addAll(refs);
                     removed.addAll(LaneTopology.dependents(data, removed));
                     LaneDeletes.requireConfirmation(t, removed);
                     if (player != null) {
                        for (UUID id : removed) {
                           RoadData.requireOwner(player, data.index.roads.get(id).record.owner());
                        }
                     }

                     points.remove(p);
                  } else {
                     if (p.mergeLength() != 0.0) {
                        throw new IllegalArgumentException("请先取消车道增减，再切换此车道点");
                     }

                     int lane = t.getInt("Lane");
                     LanePoints.Lane selected = LanePoints.lane(b.mesh, LanePoints.lane(b.mesh, p).station(), lane);
                     points.set(points.indexOf(p), p.at(selected, b.mesh));
                  }

                  update(level, player, r, md.points(points), removed);
                  return t.getBoolean("Delete") ? "手动车道点及确认的依赖匝道已删除" : "车道已切换，依赖匝道已同步重建";
               }
            }
         }
      }
   }

   static void update(ServerLevel level, ServerPlayer player, RoadRecord r, LanePoints.Data md, Set<UUID> deleted) {
      RoadData data = RoadData.get(level);
      boolean referenced = false;

      for (LanePoints.Point p : LaneTopology.metadata(r).points()) {
         LanePoints.Point next = md.points().stream().filter(v -> v.id().equals(p.id())).findFirst().orElse(null);
         if (!p.equals(next) && data.index.laneReferenced(LanePoints.Ref.lane(r.id(), p.id()))) {
            referenced = true;
            break;
         }
      }

      if (!referenced && deleted.isEmpty() && LaneMerge.sameDefinitions(LaneTopology.metadata(r), md)) {
         data.updateLanePointMetadata(level, player, r, md);
      } else {
         HashSet<UUID> removed = new HashSet<>(deleted);
         removed.add(r.id());
         data.replaceAssembly(level, player, List.of(new RoadIndex.Built(r.withLanePoints(md))), removed, Set.of(r.a(), r.b()));
      }
   }

   public void appendHoverText(ItemStack s, TooltipContext l, List<Component> lines, TooltipFlag f) {
      lines.add(Component.literal("右键机动车道吸附到该车道中轴线；右键蓝色点编辑"));
      lines.add(Component.literal("Shift＋右键自动点创建手动点；重合时 Shift 选择自动点"));
   }

   private static record Pending(UUID token, long expires) {
   }

   public static record Pick(RoadRecord road, LanePoints.Point point, double distance) {
   }
}
