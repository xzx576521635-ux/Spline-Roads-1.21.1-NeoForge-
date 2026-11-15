package com.sora.splineroads.world;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.OneSidedJunction;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadSlicing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

final class ConnectedSideJunctions {
   static Map<UUID, CompoundTag> rebase(ServerLevel level, List<RoadIndex.Built> built, Set<UUID> removed, Set<UUID> preserved) {
      RoadData data = RoadData.get(level);
      HashMap<UUID, CompoundTag> receipts = new HashMap<>();
      Set<UUID> initialRemoved = Set.copyOf(removed);
      HashMap<UUID, RoadIndex.Built> candidates = new HashMap<>();
      built.forEach(r -> candidates.put(r.record.id(), r));
      HashMap<UUID, RoadRecord> hosts = new HashMap<>();
      HashSet<UUID> unchanged = new HashSet<>();
      HashSet<UUID> groups = new HashSet<>();

      for (Entry<UUID, CompoundTag> entry : data.junctions.entrySet()) {
         if (entry.getValue().getBoolean("OneSided")) {
            CompoundTag t = entry.getValue();

            for (String key : t.getBoolean("Paired") ? List.of("OriginalMain", "OriginalMainB") : List.of("OriginalMain")) {
               if (initialRemoved.contains(t.getCompound(key).getUUID("Id"))) {
                  groups.add(entry.getKey());
               }
            }
         }
      }

      for (Entry<UUID, CompoundTag> entryx : data.junctions.entrySet()) {
         CompoundTag saved = entryx.getValue();
         if (saved.getBoolean("OneSided")) {
            List<String> keys = saved.getBoolean("Paired") ? List.of("OriginalMain", "OriginalMainB") : List.of("OriginalMain");
            List<RoadRecord> originals = keys.stream().map(k -> RoadRecord.load(saved.getCompound(k))).toList();
            if (groups.contains(entryx.getKey())) {
               UUID group = entryx.getKey();
               CompoundTag receipt = saved.copy();
               HashMap<RoadGeometry.V, JunctionSpec> specs = new HashMap<>();
               List<RoadIndex.Built> members = data.index.roads.values().stream().filter(r -> group.equals(r.record.assembly())).toList();

               for (int side = 0; side < keys.size(); side++) {
                  RoadRecord old = originals.get(side);
                  if (initialRemoved.contains(old.id())) {
                     RoadIndex.Built candidate = candidates.get(old.id());
                     if (candidate == null) {
                        throw conflict();
                     }

                     if (!AutoJunctions.ordinary(candidate.record)) {
                        throw conflict();
                     }

                     if (AutoJunctions.sameDeck(old.mesh(), candidate.mesh)
                        && old.settings().equals(candidate.record.settings())
                        && old.a().equals(candidate.record.a())
                        && old.b().equals(candidate.record.b())) {
                        unchanged.add(old.id());
                     } else {
                        String keyx = side == 0 ? "Spec" : "PairSpec";
                        JunctionSpec previous = JunctionCodec.read(saved.getCompound(keyx));
                        OneSidedJunction.Source source = previous.oneSide();
                        RoadGeometry.Mesh mesh = candidate.mesh;
                        double from = station(mesh, RoadSlicing.at(source.road(), source.from()).center());
                        double to = station(mesh, RoadSlicing.at(source.road(), source.to()).center());
                        if (from < 2.0 || to > mesh.length() - 2.0 || to <= from || old.settings().style() != candidate.record.settings().style()) {
                           throw conflict();
                        }

                        for (double d = source.from(); d <= source.to() + 1.0E-6; d += 0.5) {
                           RoadGeometry.Sample a = RoadSlicing.at(source.road(), Math.min(d, source.to()));
                           RoadGeometry.Sample b = RoadSlicing.at(mesh, from + d - source.from());
                           if (a.center().distance(b.center()) > 1.0E-5
                              || a.left().distance(b.left()) > 1.0E-5
                              || Math.abs(a.halfWidth() - b.halfWidth()) > 1.0E-5) {
                              throw conflict();
                           }
                        }

                        RoadRecord next = hosts.get(old.id());
                        if (next == null) {
                           next = candidate.record;
                           if (old.furniturePhase() != null) {
                              RoadGeometry.Sample at = RoadQueries.horizontal(old.mesh(), mesh.first().center()).sample();
                              double delta = at.distance() + mesh.first().center().sub(at.center()).dot(at.left().left().mul(-1.0));
                              next = next.furniturePhase(old.furniturePhase().shift(delta));
                           }

                           ArrayList<RoadIndex.Built> planning = new ArrayList<>();

                           for (RoadIndex.Built r : data.index.roads.values()) {
                              if (!initialRemoved.contains(r.record.id()) && !groups.contains(r.record.assembly())) {
                                 planning.add(r);
                              }
                           }

                           for (RoadIndex.Built rx : candidates.values()) {
                              if (!rx.record.id().equals(old.id())) {
                                 planning.add(rx);
                              }
                           }

                           RoadIndex.Built host = new RoadIndex.Built(next);
                           planning.add(host);

                           try (RoadWorkChunks chunks = RoadWorkChunks.open(level)) {
                              chunks.roads(List.of(host));
                              next = data.planSideHost(level, host, planning);
                           }

                           hosts.put(old.id(), next);
                        }

                        JunctionSpec changed = previous.oneSide(new OneSidedJunction.Source(next.mesh(), next.structures(), from, to, source.side()));
                        OneSidedJunction.prepare(changed);
                        specs.put(previous.center(), changed);
                        receipt.put(keys.get(side), next.save());
                        receipt.put(keyx, JunctionCodec.write(changed));
                        receipt.putInt(side == 0 ? "MainSignature" : "MainSignatureB", next.save().hashCode());
                     }
                  }
               }

               if (!specs.isEmpty()) {
                  for (RoadIndex.Built member : members) {
                     if (!SideHosts.host(member.record)) {
                        JunctionPlanner.Ref ref = member.record.junction();
                        JunctionSpec spec = specs.getOrDefault(ref.spec().center(), ref.spec());
                        RoadRecord base = member.record;

                        for (String keyxx : keys) {
                           RoadRecord original = RoadRecord.load(receipt.getCompound(keyxx));
                           if (base.id().equals(original.id())) {
                              base = original;
                           }
                        }

                        built.add(new RoadIndex.Built(base.junction(group, new JunctionPlanner.Ref(spec, ref.piece(), ref.geometryVersion()))));
                        removed.add(base.id());
                     }
                  }

                  receipts.put(group, receipt);
               }
            }
         }
      }

      built.removeIf(rxx -> hosts.containsKey(rxx.record.id()) || unchanged.contains(rxx.record.id()));
      removed.removeAll(unchanged);
      preserved.addAll(unchanged);

      for (RoadRecord host : hosts.values()) {
         built.add(new RoadIndex.Built(SideHosts.rebased(data, host, receipts)));
         removed.add(host.id());
      }

      return receipts;
   }

   private static double station(RoadGeometry.Mesh mesh, RoadGeometry.V at) {
      RoadQueries.Projection q = RoadQueries.horizontal(mesh, at);
      if (q.sample().center().distance(at) > 1.0E-5) {
         throw conflict();
      } else {
         return q.sample().distance();
      }
   }

   private static IllegalArgumentException conflict() {
      return new IllegalArgumentException("本次修改进入已连接附属点的接入范围；请缩小路口范围，或先删除该单边连接");
   }

   private ConnectedSideJunctions() {
   }
}
