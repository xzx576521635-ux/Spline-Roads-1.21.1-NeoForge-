package com.sora.splineroads.world;

import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadEndpointSections;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.core.TollPlanner;
import com.sora.splineroads.core.TollPlaza;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class InlineTollRoads {
   private static final Map<RoadData, Map<UUID, InlineTollRoads.Approval>> approvals = new WeakHashMap<>();

   private static RoadRecord source(ServerLevel level, ServerPlayer player, UUID id) {
      RoadIndex.Built built = RoadData.get(level).index.roads.get(id);
      if (built == null) {
         throw new IllegalArgumentException("道路已不存在");
      } else {
         RoadRecord r = built.record;
         if (player != null) {
            RoadData.requireOwner(player, r.owner());
         }

         RoadProfile.Type type = RoadProfile.catalog(r.settings()).type();
         if ((r.assembly() == null || InterchangeConnectors.eligible(RoadData.get(level), r))
            && r.junction() == null
            && r.toll() == null
            && !r.mesh().closed()
            && !r.settings().style().ramp()
            && LaneTopology.metadata(r).link() == null
            && (type == RoadProfile.Type.ORDINARY || type == RoadProfile.Type.HIGHWAY)) {
            return r;
         } else {
            throw new IllegalArgumentException("请选择独立普通/高速道路，或双喇叭的中央连接段完整断面");
         }
      }
   }

   private static RoadGeometry.Settings section(RoadRecord r, double station) {
      return RoadEndpointSections.sectionAt(r.mesh(), RoadSlicing.at(r.mesh(), station), false);
   }

   public static TollPlaza.Config defaults(RoadRecord r, double station) {
      RoadGeometry.Settings port = section(r, station);
      RoadLanes.Counts n = RoadLanes.counts(port);
      RoadProfile.Layout layout = RoadProfile.layout(port, port.width());
      int backward = Math.max(1, n.reverse());
      return new TollPlaza.Config(
         n.twoWay(),
         false,
         port.options().leftTraffic(),
         layout.catalog().type() == RoadProfile.Type.HIGHWAY,
         new TollPlaza.Direction(n.forward(), n.forward(), 0, n.forward()),
         new TollPlaza.Direction(backward, backward, 0, backward),
         layout.laneWidth(),
         4.0,
         6.0,
         port.structure()
      );
   }

   private static InlineTollRoads.Layout layout(RoadRecord r, double station, TollPlaza.Config c) {
      RoadGeometry.Mesh mesh = r.mesh();
      if (Double.isFinite(station) && !(station <= 0.0) && !(station >= mesh.length())) {
         TollPlaza.Config expected = defaults(r, station);
         if (c.twoWay() == expected.twoWay()
            && !c.reverse()
            && c.leftTraffic() == expected.leftTraffic()
            && c.highway() == expected.highway()
            && !(Math.abs(c.laneWidth() - expected.laneWidth()) > 1.0E-6)
            && c.forward().aLanes() == expected.forward().aLanes()
            && c.forward().bLanes() == expected.forward().bLanes()
            && (!c.twoWay() || c.backward().aLanes() == expected.backward().aLanes() && c.backward().bLanes() == expected.backward().bLanes())) {
            RoadGeometry.Settings port = section(r, station);
            double span = 0.0;
            TollPlaza.Spec spec = null;

            for (int i = 0; i < 8; i++) {
               RoadGeometry.Settings a = span == 0.0 ? port : section(r, station - span);
               RoadGeometry.Settings b = span == 0.0 ? port : section(r, station + span);
               TollPlaza.Spec trial = new TollPlaza.Spec(
                  new RoadGeometry.Node(new RoadGeometry.V(0.0, 0.0, 0.0), 0.0, 0.0),
                  new RoadGeometry.Node(new RoadGeometry.V(0.0, 0.0, 1024.0), 0.0, 0.0),
                  c,
                  a,
                  b
               );
               TollPlanner.Lengths lengths = TollPlanner.lengths(trial);
               double next = Math.ceil((Math.max(lengths.a(), lengths.b()) + 14.0) * 2.0) / 2.0;
               span = Math.max(span, next);
               if (station - span < 2.0 || station + span > mesh.length() - 2.0) {
                  throw new IllegalArgumentException(String.format(Locale.ROOT, "当前断面两侧各需 %.1f 格站区；请减少通道数量或选择更长的道路中段", span));
               }

               double from = station - span;
               double to = station + span;
               List<RoadGeometry.Sample> path = RoadSlicing.slice(mesh, from, to, port).mesh().samples();
               spec = new TollPlaza.Spec(RoadSplitGeometry.node(mesh, from), RoadSplitGeometry.node(mesh, to), c, section(r, from), section(r, to), path);
               if (TollPlanner.lengths(spec).deficit() < 1.0E-5) {
                  try {
                     TollPlanner.plan(spec);
                     return new InlineTollRoads.Layout(spec, from, to);
                  } catch (IllegalArgumentException var23) {
                     if (!var23.getMessage().contains("纵坡最多12%")) {
                        throw var23;
                     }

                     if (i == 7) {
                        throw var23;
                     }

                     span = Math.ceil((span + Math.max(4.0, span * 0.15)) * 2.0) / 2.0;
                  }
               }
            }

            throw new IllegalArgumentException("两侧断面变化过大，无法形成收费站过渡");
         } else {
            throw new IllegalArgumentException("道路断面模式、通行方向及接口车道由原道路确定；可调整 ETC、人工通道和站房尺寸");
         }
      } else {
         throw new IllegalArgumentException("请选择道路内部断面");
      }
   }

   public static CompoundTag payload(ServerLevel level, ServerPlayer player, UUID road, double station) {
      RoadRecord source = source(level, player, road);
      InlineTollRoads.Layout layout = layout(source, station, defaults(source, station));
      CompoundTag t = new CompoundTag();
      t.putString("Kind", "toll");
      t.putString("Dimension", level.dimension().location().toString());
      t.putUUID("InlineRoad", road);
      t.putDouble("InlineStation", station);
      t.putInt("InlineSignature", source.header().hashCode());
      t.put("Spec", TollCodec.write(layout.spec()));
      t.putString("Adjust", "NONE");
      return t;
   }

   private static CompoundTag key(CompoundTag t) {
      CompoundTag k = new CompoundTag();

      for (String field : List.of("InlineRoad", "InlineStation", "InlineSignature", "Dimension")) {
         if (t.contains(field)) {
            k.put(field, t.get(field).copy());
         }
      }

      k.put("Config", TollCodec.config(TollCodec.config(t.getCompound("Spec"))));
      return k;
   }

   private static void preserveInterior(RoadData data, RoadRecord r, double from, double to) {
      for (RoadAttachments.Point p : r.settings().options().attachments().points()) {
         double d = RoadQueries.horizontal(r.mesh(), p.position()).sample().distance();
         if (d >= from && d <= to) {
            throw new IllegalArgumentException("站区内有附属点，请移到站外后再生成");
         }
      }

      for (LanePoints.Point px : LaneTopology.metadata(r).points()) {
         double d = RoadQueries.horizontal(r.mesh(), px.position()).sample().distance();
         if (d >= from && d <= to && !px.automatic()) {
            throw new IllegalArgumentException("站区内有手动车道点，请先移动选址以保留原匝道连接");
         }
      }

      for (LaneSections.Cut cut : LaneTopology.metadata(r).cuts()) {
         if (Math.max(cut.begin(), cut.end()) > from && Math.min(cut.begin(), cut.end()) < to) {
            throw new IllegalArgumentException("站区与车道关闭/分离区重叠，请在完整断面处生成");
         }
      }

      for (LanePoints.Opening opening : LaneTopology.metadata(r).openings()) {
         if (opening.centerline().stream().anyMatch(pxx -> {
            double dx = RoadQueries.horizontal(r.mesh(), pxx).sample().distance();
            return dx >= from && dx <= to;
         })) {
            throw new IllegalArgumentException("站区与现有匝道接口重叠");
         }
      }

      for (SmartSigns.State state : data.smartSigns.road(r.id())) {
         double d = RoadGantry.station(r.mesh(), state.key().slot()).distance();
         if (d >= from && d <= to) {
            throw new IllegalArgumentException("站区内有已保存标牌，请在标牌之外选址");
         }
      }

      for (RoadGantry.Edit edit : r.settings().options().infrastructure().gantryEdits()) {
         if (!edit.automatic()) {
            double d = RoadGantry.station(r.mesh(), edit.slot()).distance();
            if (d >= from && d <= to) {
               throw new IllegalArgumentException("站区内有手动龙门架，请在架体外选址");
            }
         }
      }
   }

   public static CompoundTag preview(ServerLevel level, ServerPlayer player, CompoundTag t) {
      RoadData data = RoadData.get(level);
      RoadRecord r = source(level, player, t.getUUID("InlineRoad"));
      if (level.dimension().location().toString().equals(t.getString("Dimension")) && r.header().hashCode() == t.getInt("InlineSignature")) {
         InlineTollRoads.Layout layout = layout(r, t.getDouble("InlineStation"), TollCodec.config(t.getCompound("Spec")));
         preserveInterior(data, r, layout.from(), layout.to());
         boolean connector = InterchangeConnectors.eligible(data, r);
         RoadSplitGeometry.Pair first = RoadSplitGeometry.split(
            connector ? InterchangeConnectors.forTollSplit(r) : r, layout.from(), RoadSlicing.Connection.CONTINUOUS, UUID.randomUUID()
         );
         RoadSplitGeometry.Pair second = RoadSplitGeometry.split(
            first.second(), layout.to() - layout.from(), RoadSlicing.Connection.CONTINUOUS, UUID.randomUUID()
         );
         RoadRecord station = second.first().toll(layout.spec());
         if (connector) {
            station = InterchangeConnectors.group(station, r.assembly());
         }

         LinkedHashMap<UUID, RoadRecord> roads = new LinkedHashMap<>();
         roads.put(first.first().id(), first.first());
         roads.put(station.id(), station);
         roads.put(second.second().id(), second.second());
         HashSet<UUID> rightPoints = new HashSet<>();
         LaneTopology.metadata(second.second()).points().forEach(p -> rightPoints.add(p.id()));

         for (RoadIndex.Built old : data.index.roads.values()) {
            if (!old.record.id().equals(r.id())) {
               RoadRecord next = RoadSplitter.remapLinks(old.record, r.id(), second.second().id(), rightPoints);
               if (next != old.record) {
                  roads.put(next.id(), next);
               }
            }
         }

         LinkedHashMap<UUID, RoadRecord> streets = new LinkedHashMap<>(data.streets);
         RoadRecord logical = streets.remove(r.id());
         if (logical != null) {
            double offset = r.settings().options().ends().trimmedStart() - logical.settings().options().ends().trimmedStart();
            streets.put(
               r.id(),
               RoadSplitGeometry.range(
                  logical, 0.0, layout.from() + offset, r.id(), logical.a(), first.pos(), logical.start(), first.node(), RoadSlicing.Connection.CONTINUOUS
               )
            );
            streets.put(
               second.second().id(),
               RoadSplitGeometry.range(
                  logical,
                  layout.to() + offset,
                  logical.mesh().length(),
                  second.second().id(),
                  second.pos(),
                  logical.b(),
                  second.node(),
                  logical.end(),
                  RoadSlicing.Connection.CONTINUOUS
               )
            );
         }

         streets.replaceAll((id, oldx) -> RoadSplitter.remapLinks(oldx, r.id(), second.second().id(), rightPoints));
         LinkedHashMap<UUID, CompoundTag> junctions = new LinkedHashMap<>();
         data.junctions.forEach((id, v) -> junctions.put(id, RoadSplitter.remapJunction(v, r, second.second().id())));
         LinkedHashMap<UUID, CompoundTag> interchanges = new LinkedHashMap<>();
         data.interchanges
            .forEach(
               (id, v) -> interchanges.put(
                     id,
                     InterchangeConnectors.split(
                        InterchangeConnectors.split(RoadSplitter.remapAssembly(RoadSplitter.remapAssembly(v, r, first), first.second(), second), r, first),
                        first.second(),
                        second
                     )
                  )
            );
         HashMap<Integer, UUID> owners = new HashMap<>();

         for (RoadRecord fragment : List.of(first.first(), second.second())) {
            for (int slot : RoadGantry.slots(fragment.mesh())) {
               owners.put(slot, fragment.id());
            }
         }

         for (SmartSigns.State state : data.smartSigns.road(r.id())) {
            if (!owners.containsKey(state.key().slot())) {
               throw new IllegalArgumentException("原标牌无法完整迁移到站外路段");
            }
         }

         HashSet<UUID> removed = new HashSet<>(roads.keySet());
         removed.retainAll(data.index.roads.keySet());
         HashSet<BlockPos> nodes = new HashSet<>(List.of(r.a(), r.b(), first.pos(), second.pos()));
         List<RoadData.NodeMove> moves = List.of(move(first, r.owner()), move(second, r.owner()));
         UUID token = UUID.randomUUID();
         List<RoadIndex.Built> prepared = data.prepareAssembly(
            level, player, roads.values().stream().map(RoadIndex.Built::new).toList(), removed, nodes, moves, token, Set.of(station.id()), true
         );
         Map<UUID, InlineTollRoads.Approval> table = approvals.computeIfAbsent(data, k -> new LinkedHashMap<>());
         table.entrySet()
            .removeIf(
               e -> System.nanoTime() - e.getValue().created() > 600000000000L
                     || Objects.equals(e.getValue().owner(), player == null ? null : player.getUUID())
            );
         table.put(
            token,
            new InlineTollRoads.Approval(
               player == null ? null : player.getUUID(),
               key(t),
               r,
               RoadMetadataPatch.between(data.streets, streets, v -> v),
               RoadMetadataPatch.between(data.junctions, junctions, CompoundTag::copy),
               RoadMetadataPatch.between(data.interchanges, interchanges, CompoundTag::copy),
               Map.copyOf(owners),
               System.nanoTime()
            )
         );

         while (table.size() > 4) {
            table.remove(table.keySet().iterator().next());
         }

         CompoundTag reply = new CompoundTag();
         reply.putUUID("Token", token);
         reply.put("Spec", TollCodec.write(layout.spec()));
         reply.putInt("Affected", 2);
         ListTag list = new ListTag();
         prepared.forEach(b -> list.add(b.record.save()));
         reply.put("Roads", list);
         return reply;
      } else {
         throw new IllegalArgumentException("所选道路已变化，请重新选择断面");
      }
   }

   private static RoadData.NodeMove move(RoadSplitGeometry.Pair pair, UUID owner) {
      CompoundTag t = new CompoundTag();
      t.putUUID("Owner", owner);
      t.put("Node", RoadRecord.writeNode(pair.node()));
      t.putBoolean("HeadingLocked", true);
      t.putBoolean("GradeLocked", true);
      t.putBoolean("HeightExplicit", true);
      return new RoadData.NodeMove(null, pair.pos(), pair.node(), t);
   }

   public static String build(ServerLevel level, ServerPlayer player, CompoundTag t) {
      RoadData data = RoadData.get(level);
      Map<UUID, InlineTollRoads.Approval> table = approvals.get(data);
      UUID token = t.hasUUID("Token") ? t.getUUID("Token") : null;
      InlineTollRoads.Approval approval = table == null ? null : table.remove(token);
      if (approval != null
         && System.nanoTime() - approval.created() <= 600000000000L
         && Objects.equals(approval.owner(), player == null ? null : player.getUUID())
         && approval.key().equals(key(t))) {
         RoadIndex.Built current = data.index.roads.get(approval.source().id());
         if (current != null && current.record == approval.source()) {
            approval.streets().validate(data.streets);
            approval.junctions().validate(data.junctions);
            approval.interchanges().validate(data.interchanges);
            data.buildPrepared(level, player, token, () -> {
               approval.streets().apply(data.streets);
               approval.junctions().apply(data.junctions);
               approval.interchanges().apply(data.interchanges);
               data.smartSigns.splitKeys(approval.source().id(), approval.signs());
            });
            return "已按当前断面生成收费站，站外原路及其连接已保留";
         } else {
            throw new IllegalArgumentException("原道路已变化，请重新选择断面");
         }
      } else {
         throw new IllegalArgumentException("断面收费站预览已失效，请重新预览");
      }
   }

   private InlineTollRoads() {
   }

   private static record Approval(
      UUID owner,
      CompoundTag key,
      RoadRecord source,
      RoadMetadataPatch<RoadRecord> streets,
      RoadMetadataPatch<CompoundTag> junctions,
      RoadMetadataPatch<CompoundTag> interchanges,
      Map<Integer, UUID> signs,
      long created
   ) {
   }

   private static record Layout(TollPlaza.Spec spec, double from, double to) {
   }
}
