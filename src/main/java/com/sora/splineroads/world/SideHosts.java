package com.sora.splineroads.world;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.OneSidedJunction;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.net.RoadNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class SideHosts {
   static boolean host(RoadRecord r) {
      return r.junction() != null && OneSidedJunction.retainedPiece(r.junction());
   }

   static boolean attachable(RoadRecord r) {
      return AutoJunctions.ordinary(r) || host(r);
   }

   static RoadIndex.Built hostAt(RoadData data, RoadIndex.Built picked, RoadGeometry.V hit) {
      if (picked != null && !attachable(picked.record) && picked.record.junction() != null && picked.record.junction().spec().oneSide() != null) {
         JunctionSpec spec = picked.record.junction().spec();
         RoadQueries.Projection q = RoadQueries.horizontal(spec.oneSide().road(), hit);
         if (q.horizontalDistance() > q.sample().halfWidth() + 0.1) {
            return picked;
         } else {
            CompoundTag receipt = data.junctions.get(picked.record.assembly());
            if (receipt == null) {
               return picked;
            } else {
               String key = JunctionCodec.read(receipt.getCompound("Spec")).center().equals(spec.center()) ? "OriginalMain" : "OriginalMainB";
               CompoundTag original = receipt.getCompound(key);
               return original.hasUUID("Id") ? data.index.roads.getOrDefault(original.getUUID("Id"), picked) : picked;
            }
         }
      } else {
         return picked;
      }
   }

   static List<SideHosts.Use> uses(RoadData data, UUID road) {
      return uses(data, road, Map.of());
   }

   static List<SideHosts.Use> uses(RoadData data, UUID road, Map<UUID, CompoundTag> overrides) {
      ArrayList<SideHosts.Use> result = new ArrayList<>();

      for (Entry<UUID, CompoundTag> entry : data.junctions.entrySet()) {
         CompoundTag t = overrides.getOrDefault(entry.getKey(), entry.getValue());
         if (t.getBoolean("OneSided")) {
            for (int side = 0; side < (t.getBoolean("Paired") ? 2 : 1); side++) {
               String key = side == 0 ? "OriginalMain" : "OriginalMainB";
               String specKey = side == 0 ? "Spec" : "PairSpec";
               CompoundTag saved = t.getCompound(key);
               if (saved.hasUUID("Id") && saved.getUUID("Id").equals(road)) {
                  result.add(
                     new SideHosts.Use(
                        entry.getKey(),
                        key,
                        specKey,
                        t.getUUID(side == 0 ? "AttachedPoint" : "AttachedPointB"),
                        RoadRecord.load(saved),
                        JunctionCodec.read(t.getCompound(specKey))
                     )
                  );
               }
            }
         }
      }

      result.sort(Comparator.comparing(SideHosts.Use::group));
      return result;
   }

   static RoadRecord original(RoadData data, RoadRecord live) {
      if (!host(live)) {
         return live;
      } else {
         List<SideHosts.Use> uses = uses(data, live.id());
         if (uses.isEmpty()) {
            throw new IllegalArgumentException("附属点主路记录不完整，请重新加载存档");
         } else {
            return uses.get(0).original().withAttachments(live.settings().options().attachments());
         }
      }
   }

   static void available(RoadData data, RoadRecord original, UUID point, JunctionSpec proposed, UUID editing) {
      for (SideHosts.Use use : uses(data, original.id())) {
         if (!use.group().equals(editing)) {
            if (use.point().equals(point) && use.spec().oneSide().side() == proposed.oneSide().side()) {
               throw new IllegalArgumentException("此附属点的这一侧已有连接道路；主路两侧各可连接一条");
            }

            OneSidedJunction.Source a = use.spec().oneSide();
            OneSidedJunction.Source b = proposed.oneSide();
            if (a.side() == b.side() && Math.min(a.to(), b.to()) > Math.max(a.from(), b.from()) + 1.0E-7) {
               throw new IllegalArgumentException("新连接与已有单边路口接入范围重叠；请移动道路端点或另选附属点，原附属点保留");
            }
         }
      }
   }

   static RoadRecord retained(RoadData data, RoadRecord original, UUID replacing, JunctionSpec proposed) {
      ArrayList<SideHosts.Use> all = new ArrayList<>(uses(data, original.id()).stream().filter(u -> !u.group().equals(replacing)).toList());
      if (proposed != null) {
         all.add(new SideHosts.Use(replacing, "", "", null, original, proposed));
      }

      return retained(original, all);
   }

   static RoadRecord rebased(RoadData data, RoadRecord original, Map<UUID, CompoundTag> receipts) {
      return retained(original, new ArrayList<>(uses(data, original.id(), receipts)));
   }

   private static RoadRecord retained(RoadRecord original, List<SideHosts.Use> all) {
      all.sort(Comparator.comparing(SideHosts.Use::group));
      if (all.isEmpty()) {
         return original;
      } else {
         SideHosts.Use owner = all.get(0);
         JunctionSpec spec = owner.spec();
         OneSidedJunction.Source source = spec.oneSide();
         spec = spec.oneSide(new OneSidedJunction.Source(original.mesh(), original.structures(), source.from(), source.to(), source.side()));
         List<OneSidedJunction.Cut> cuts = all.stream().map(u -> u.spec().oneSide()).map(s -> new OneSidedJunction.Cut(s.from(), s.to(), s.side())).toList();
         JunctionPlanner.Plan plan = JunctionPlanner.plan(spec);
         return original.junction(owner.group(), new JunctionPlanner.Ref(spec, plan.pieces().size() - 1, 54, cuts));
      }
   }

   static void remove(ServerLevel level, ServerPlayer player, UUID group) {
      RoadData data = RoadData.get(level);
      CompoundTag t = data.junctions.get(group);
      if (t == null) {
         throw new IllegalArgumentException("连接已不存在");
      } else {
         if (player != null) {
            RoadData.requireOwner(player, t.getUUID("Owner"));
         }

         HashSet<UUID> removed = new HashSet<>();
         HashSet<BlockPos> nodes = new HashSet<>();
         ArrayList<RoadIndex.Built> built = new ArrayList<>();
         data.index.roads.values().stream().filter(r -> group.equals(r.record.assembly())).forEach(r -> removed.add(r.record.id()));

         for (String key : t.getBoolean("Paired") ? List.of("OriginalMain", "OriginalMainB") : List.of("OriginalMain")) {
            RoadRecord saved = RoadRecord.load(t.getCompound(key));
            RoadIndex.Built live = data.index.roads.get(saved.id());
            if (live == null || !host(live.record)) {
               throw new IllegalArgumentException("保留主路已变化，不能覆盖恢复");
            }

            RoadRecord original = original(data, live.record);
            removed.add(original.id());
            nodes.add(original.a());
            nodes.add(original.b());
            built.add(new RoadIndex.Built(retained(data, original, group, null)));
         }

         nodes.add(BlockPos.of(t.getLong("Branch")));
         data.replaceAssembly(level, player, built, removed, nodes);
         data.junctions.remove(group);
         data.setDirty();
      }
   }

   static void updatePoints(ServerLevel level, RoadRecord host, RoadAttachments.Data points) {
      RoadData data = RoadData.get(level);
      List<SideHosts.Use> uses = uses(data, host.id());

      for (SideHosts.Use use : uses) {
         if (points.points().stream().noneMatch(p -> p.id().equals(use.point()))) {
            throw new IllegalArgumentException("此附属点仍有连接道路，请先删除该点的连接");
         }
      }

      RoadRecord original = original(data, host).withAttachments(points);
      HashMap<UUID, CompoundTag> receipts = new HashMap<>();
      HashMap<UUID, RoadRecord> replacements = new HashMap<>();

      for (SideHosts.Use usex : uses) {
         CompoundTag t = receipts.computeIfAbsent(usex.group(), id -> data.junctions.get(id).copy());
         OneSidedJunction.Source source = usex.spec().oneSide();
         JunctionSpec spec = usex.spec()
            .oneSide(new OneSidedJunction.Source(original.mesh(), original.structures(), source.from(), source.to(), source.side()));
         t.put(usex.originalKey(), original.save());
         t.put(usex.specKey(), JunctionCodec.write(spec));
         t.putInt(usex.originalKey().equals("OriginalMain") ? "MainSignature" : "MainSignatureB", original.save().hashCode());

         for (RoadIndex.Built member : data.index.roads.values()) {
            if (usex.group().equals(member.record.assembly())
               && member.record.junction() != null
               && member.record.junction().spec().center().equals(usex.spec().center())
               && !host(member.record)) {
               RoadRecord r = member.record;
               replacements.put(
                  r.id(),
                  r.junction(usex.group(), new JunctionPlanner.Ref(spec, r.junction().piece(), r.junction().geometryVersion())).structures(r.structures())
               );
            }
         }
      }

      RoadRecord next = retained(data, original, null, null).structures(host.structures());
      replacements.put(host.id(), next);
      data.junctions.putAll(receipts);
      replacements.values().forEach(rx -> {
         data.index.put(RoadIndex.Built.loading(rx));
         RoadNetwork.broadcastRoad(level, rx);
      });
      if (data.streets.containsKey(host.id())) {
         data.streets.put(host.id(), data.streets.get(host.id()).withAttachments(points));
      }

      data.setDirty();
   }

   private SideHosts() {
   }

   static record Use(UUID group, String originalKey, String specKey, UUID point, RoadRecord original, JunctionSpec spec) {
   }
}
