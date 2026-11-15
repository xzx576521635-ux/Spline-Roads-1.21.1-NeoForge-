package com.sora.splineroads.world;

import com.sora.splineroads.core.InterchangePlanner;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.TollPlaza;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

final class InterchangeConnectors {
   private static final String ROADS = "TollConnectorRoads";
   private static final String SHAPE = "TollConnectorShape";

   static List<UUID> ids(CompoundTag t) {
      ArrayList<UUID> out = new ArrayList<>();

      for (Tag v : t.getList("TollConnectorRoads", 8)) {
         out.add(UUID.fromString(v.getAsString()));
      }

      return out;
   }

   static boolean eligible(RoadData data, RoadRecord road) {
      CompoundTag t = road.assembly() == null ? null : data.interchanges.get(road.assembly());
      return t != null
         && !t.getBoolean("IntegratedToll")
         && t.getCompound("Options").getString("Preset").equals("DOUBLE_TRUMPET")
         && ids(t).contains(road.id());
   }

   static RoadRecord group(RoadRecord r, UUID group) {
      return new RoadRecord(
         r.id(),
         r.owner(),
         r.a(),
         r.b(),
         r.start(),
         r.end(),
         r.settings(),
         r.automatic(),
         r.clearance(),
         r.structures(),
         r.endCaps(),
         r.buildVersion(),
         group,
         r.alignment(),
         r.furniturePhase(),
         r.junction(),
         r.toll(),
         r.fragment()
      );
   }

   static RoadRecord forTollSplit(RoadRecord road) {
      RoadGeometry.Mesh mesh = road.mesh();
      return road.structures(
         road.structures()
            .stream()
            .filter(
               p -> {
                  if (!RoadSplitGeometry.linear(p)
                     && !p.pier()
                     && p.model().isEmpty()
                     && p.heights() == null
                     && p.frameA() != null
                     && p.frameB() != null
                     && !(p.width() > 0.7)
                     && !(p.height() > 1.1)
                     && Set.of(RoadStructures.Material.CONCRETE, RoadStructures.Material.STEEL, RoadStructures.Material.DARK_STEEL).contains(p.material())) {
                     RoadGeometry.Sample a = RoadQueries.horizontal(mesh, p.a()).sample();
                     RoadGeometry.Sample b = RoadQueries.horizontal(mesh, p.b()).sample();
                     double lateralA = Math.abs(p.a().sub(a.center()).dot(a.left()));
                     double lateralB = Math.abs(p.b().sub(b.center()).dot(b.left()));
                     boolean aboveDeck = p.a().y() >= a.center().y() - 0.05 && p.b().y() >= b.center().y() - 0.05;
                     return !aboveDeck && (lateralA < a.halfWidth() - 0.8 || lateralB < b.halfWidth() - 0.8)
                        || p.a().y() < a.center().y() - mesh.settings().thickness() - 0.05
                        || p.b().y() < b.center().y() - mesh.settings().thickness() - 0.05
                        || Math.abs(p.frameA().horizontalUnit().dot(a.left())) < 0.999
                        || Math.abs(p.frameB().horizontalUnit().dot(b.left())) < 0.999;
                  } else {
                     return true;
                  }
               }
            )
            .toList()
      );
   }

   static void capturePart(CompoundTag t, RoadRecord r) {
      ListTag list = t.getList("TollConnectorRoads", 8);
      list.add(StringTag.valueOf(r.id().toString()));
      t.put("TollConnectorRoads", list);
   }

   static void capture(CompoundTag t, RoadRecord r) {
      ListTag ids = new ListTag();
      ids.add(StringTag.valueOf(r.id().toString()));
      t.put("TollConnectorRoads", ids);
      t.put("TollConnectorShape", r.header());
   }

   static CompoundTag split(CompoundTag before, RoadRecord old, RoadSplitGeometry.Pair pair) {
      List<UUID> ids = ids(before);
      if (!ids.contains(old.id())) {
         return before;
      } else {
         CompoundTag t = before.copy();
         ListTag list = new ListTag();

         for (UUID id : ids) {
            list.add(StringTag.valueOf(id.toString()));
            if (id.equals(old.id())) {
               list.add(StringTag.valueOf(pair.second().id().toString()));
            }
         }

         t.put("TollConnectorRoads", list);
         LinkedHashSet<Long> nodes = new LinkedHashSet<>();

         for (long p : t.getLongArray("TollConnectorNodes")) {
            nodes.add(Long.valueOf(p));
         }

         nodes.add(Long.valueOf(pair.pos().asLong()));
         t.putLongArray("TollConnectorNodes", new ArrayList<>(nodes));
         ListTag topology = new ListTag();

         for (Tag v : before.getList("SignTopology", 10)) {
            CompoundTag e = (CompoundTag)v;
            topology.add(e.copy());
            if (e.getUUID("Road").equals(old.id())) {
               CompoundTag next = e.copy();
               next.putUUID("Road", pair.second().id());
               topology.add(next);
            }
         }

         t.put("SignTopology", topology);
         return t;
      }
   }

   static boolean hasTolls(RoadData data, CompoundTag t) {
      return ids(t).stream().map(data.index.roads::get).filter(Objects::nonNull).anyMatch(b -> b.record.toll() != null);
   }

   static List<BlockPos> obsoleteNodes(RoadData data, CompoundTag t, Collection<RoadIndex.Built> next, Set<UUID> removed) {
      ArrayList<BlockPos> retired = new ArrayList<>();
      ArrayList<Long> retained = new ArrayList<>();

      for (long value : t.getLongArray("TollConnectorNodes")) {
         BlockPos pos = BlockPos.of(value);
         if (next.stream().anyMatch(b -> b.record.a().equals(pos) || b.record.b().equals(pos))) {
            retained.add(value);
         } else {
            if (data.index.atNode(pos).stream().anyMatch(id -> !removed.contains(id))) {
               throw new IllegalArgumentException("中央收费站端点已连接其他道路，请先处理外接道路");
            }

            retired.add(pos);
         }
      }

      t.putLongArray("TollConnectorNodes", retained);
      return List.copyOf(retired);
   }

   static List<RoadRecord> preserve(RoadData data, CompoundTag t, InterchangePlanner.Plan plan) {
      if (!t.getBoolean("IntegratedToll") && !Interchanges.read(t.getCompound("Options")).toll().enabled() && hasTolls(data, t)) {
         InterchangePlanner.Leg middle = plan.legs().stream().filter(l -> l.name().equals("双喇叭中央连接段")).findFirst().orElseThrow(InterchangeConnectors::changed);
         if (!t.contains("TollConnectorShape")) {
            throw changed();
         } else {
            RoadRecord original = RoadRecord.load(t.getCompound("TollConnectorShape"));
            if (original.mesh().samples().equals(middle.mesh().samples()) && original.settings().equals(middle.mesh().settings())) {
               ArrayList<RoadRecord> out = new ArrayList<>();

               for (UUID id : ids(t)) {
                  RoadIndex.Built b = data.index.roads.get(id);
                  if (b == null) {
                     throw changed();
                  }

                  out.add(b.record);
               }

               return List.copyOf(out);
            } else {
               throw changed();
            }
         }
      } else {
         return List.of();
      }
   }

   static RoadRecord restored(RoadData data, RoadRecord station) {
      TollPlaza.Spec spec = station.toll();
      if (spec != null && !spec.path().isEmpty()) {
         RoadGeometry.Settings settings = spec.portA();
         List<RoadGeometry.Sample> path = spec.path();
         CompoundTag saved = station.assembly() == null ? null : data.interchanges.get(station.assembly());
         if (saved != null && saved.contains("TollConnectorShape")) {
            RoadRecord original = RoadRecord.load(saved.getCompound("TollConnectorShape"));
            RoadGeometry.Mesh mesh = original.mesh();
            double from = RoadQueries.horizontal(mesh, path.get(0).center()).sample().distance();
            double to = RoadQueries.horizontal(mesh, path.get(path.size() - 1).center()).sample().distance();
            RoadGeometry.Mesh restored = RoadSlicing.slice(mesh, from, to, original.settings()).mesh();
            if (restored.first().center().distance(path.get(0).center()) > 1.0E-5
               || restored.last().center().distance(path.get(path.size() - 1).center()) > 1.0E-5) {
               throw new IllegalArgumentException("原连接段接口已变化，未删除收费站");
            }

            settings = restored.settings();
         }

         if (settings == null) {
            throw new IllegalArgumentException("缺少收费站原路断面，未删除收费站");
         } else {
            return new RoadRecord(
               station.id(),
               station.owner(),
               station.a(),
               station.b(),
               station.start(),
               station.end(),
               settings,
               false,
               station.clearance(),
               List.of(),
               0,
               41,
               station.assembly() != null && !station.assembly().equals(station.id()) ? station.assembly() : null,
               path,
               station.furniturePhase()
            );
         }
      } else {
         throw new IllegalArgumentException("收费站没有可恢复的原路");
      }
   }

   private static IllegalArgumentException changed() {
      return new IllegalArgumentException("中央连接段已有收费站；请先移除收费站，再改变中央段路径、宽度或高度。原收费站和立交均未修改");
   }

   private InterchangeConnectors() {
   }
}
