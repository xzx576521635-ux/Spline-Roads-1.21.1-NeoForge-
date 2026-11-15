package com.sora.splineroads.world;

import com.sora.splineroads.core.InterchangePlanner;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadSlicing;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

final class ContinuousSections {
   static UUID sourceId(String name) {
      String value = name.startsWith("连续主路:") ? name.substring("连续主路:".length()) : (name.startsWith("共享端点邻路:") ? name.substring("共享端点邻路:".length()) : null);
      return value == null ? null : UUID.fromString(value.split("@")[0]);
   }

   static UUID id(String name) {
      UUID source = sourceId(name);
      return name.contains("@") && ContinuousRoads.donor(name) ? UUID.nameUUIDFromBytes(("sr:continuous:" + name).getBytes(StandardCharsets.UTF_8)) : source;
   }

   static RoadRecord source(CompoundTag tag, String name) {
      RoadRecord source = ContinuousRoads.source(tag, sourceId(name));
      if (!name.contains("@")) {
         return source;
      } else {
         RoadRecord old = source.caps(0);
         String[] range = name.substring(name.indexOf(64) + 1).split(",");
         double from = Double.parseDouble(range[0]);
         double to = Double.parseDouble(range[1]);
         RoadGeometry.Node a = RoadSplitGeometry.node(old.mesh(), from);
         RoadGeometry.Node b = RoadSplitGeometry.node(old.mesh(), to);
         return RoadSplitGeometry.range(
               old,
               from,
               to,
               id(name),
               from == 0.0 ? old.a() : InterchangeJunctions.pos(a.position()),
               to == old.mesh().length() ? old.b() : InterchangeJunctions.pos(b.position()),
               a,
               b,
               RoadSlicing.Connection.CONTINUOUS
            )
            .caps((from == 0.0 ? source.endCaps() & 1 : 0) | (to == old.mesh().length() ? source.endCaps() & 2 : 0));
      }
   }

   static void append(
      List<InterchangePlanner.Leg> out, InterchangePlanner.Leg leg, RoadRecord original, RoadGeometry.Mesh generated, double first, double last, boolean moved
   ) {
      original = original.caps(0);
      double length = moved ? generated.length() : original.mesh().length();
      first = Math.max(0.0, Math.min(length, first));
      last = Math.max(first, Math.min(length, last));
      if (!moved && (!(first < 2.0) || !(length - last < 2.0))) {
         if (first < 2.0) {
            first = 0.0;
         }

         if (length - last < 2.0) {
            last = length;
         }

         String name = "连续主路:" + original.id() + "@" + first + "," + last;
         RoadGeometry.Node a = RoadSplitGeometry.node(original.mesh(), first);
         RoadGeometry.Node b = RoadSplitGeometry.node(original.mesh(), last);
         RoadRecord slice = RoadSplitGeometry.range(
            original,
            first,
            last,
            id(name),
            first == 0.0 ? original.a() : InterchangeJunctions.pos(a.position()),
            last == length ? original.b() : InterchangeJunctions.pos(b.position()),
            a,
            b,
            RoadSlicing.Connection.CONTINUOUS
         );
         RoadProfile.Options o = generated.settings().options();
         RoadProfile.Options saved = slice.settings().options();
         RoadGeometry.Settings settings = generated.settings()
            .options(
               o.attachments(saved.attachments())
                  .lanePoints(saved.lanePoints())
                  .ends(saved.ends())
                  .infrastructure(saved.infrastructure().gantry(o.infrastructure().gantry()))
            );
         out.add(new InterchangePlanner.Leg(name, leg.from(), leg.to(), RoadSlicing.slice(generated, first, last, settings).mesh()));
      } else {
         out.add(new InterchangePlanner.Leg("连续主路:" + original.id(), leg.from(), leg.to(), generated));
      }
   }

   static boolean detached(CompoundTag tag, UUID id) {
      return tag.getList("ContinuousSources", 10)
         .stream()
         .map(t -> (CompoundTag)t)
         .anyMatch(t -> t.getBoolean("Detached") && t.getCompound("Road").getUUID("Id").equals(id));
   }

   static void updateSources(CompoundTag tag, InterchangePlanner.Plan plan) {
      ListTag list = new ListTag();

      for (ContinuousRoads.Source source : ContinuousRoads.sources(tag)) {
         if (!detached(tag, source.road().id())) {
            List<InterchangePlanner.Leg> legs = plan.legs()
               .stream()
               .filter(l -> source.road().id().equals(sourceId(l.name())) && l.name().contains("@"))
               .toList();
            if (legs.isEmpty()) {
               CompoundTag previous = tag.getList("ContinuousSources", 10)
                  .stream()
                  .map(t -> (CompoundTag)t)
                  .filter(t -> t.getCompound("Road").getUUID("Id").equals(source.road().id()))
                  .findFirst()
                  .orElseThrow();
               list.add(previous.copy());
            } else {
               for (InterchangePlanner.Leg leg : legs) {
                  CompoundTag item = new CompoundTag();
                  item.putInt("Axis", source.axis());
                  item.put("Road", source(tag, leg.name()).header());
                  item.putBoolean("Detached", ContinuousRoads.donor(leg.name()));
                  list.add(item);
               }
            }
         }
      }

      tag.put("ContinuousSources", list);
   }

   static Map<UUID, Map<Integer, UUID>> signOwners(InterchangePlanner.Plan plan, List<RoadIndex.Built> records) {
      HashMap<UUID, Map<Integer, UUID>> out = new HashMap<>();

      for (InterchangePlanner.Leg leg : plan.legs()) {
         if (leg.name().contains("@")) {
            Map<Integer, UUID> owners = out.computeIfAbsent(sourceId(leg.name()), k -> new HashMap<>());
            RoadIndex.Built road = records.stream().filter(r -> r.record.id().equals(id(leg.name()))).findFirst().orElseThrow();

            for (int slot : RoadGantry.slots(road.mesh)) {
               owners.put(slot, road.record.id());
            }
         }
      }

      return out;
   }

   static RoadRecord remap(RoadRecord road, Map<UUID, UUID> owners) {
      LanePoints.Data data = road.settings().options().lanePoints();
      LanePoints.Link link = data.link();
      if (link == null) {
         return road;
      } else {
         LanePoints.Ref a = link.from();
         LanePoints.Ref b = link.to();
         UUID na = owners.get(a.point());
         UUID nb = owners.get(b.point());
         if (na != null && !na.equals(a.road())) {
            a = LanePoints.Ref.lane(na, a.point());
         }

         if (nb != null && !nb.equals(b.road())) {
            b = LanePoints.Ref.lane(nb, b.point());
         }

         return a == link.from() && b == link.to()
            ? road
            : road.withLanePoints(
               data.link(new LanePoints.Link(a, b, link.options(), link.junctionMouth(), link.targetOffset(), link.protectedMerge(), link.rectangularClosure()))
            );
      }
   }

   private ContinuousSections() {
   }
}
