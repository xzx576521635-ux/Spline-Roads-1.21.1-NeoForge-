package com.sora.splineroads.world;

import com.sora.splineroads.core.JunctionPlanner;
import com.sora.splineroads.core.JunctionSpec;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadRibbon;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

final class RampJunctions {
   static JunctionSpec spec(RoadData data, UUID id) {
      CompoundTag t = data.junctions.get(id);
      if (t != null && !data.interchanges.containsKey(id)) {
         return JunctionCodec.read(t.getCompound("Spec"));
      } else {
         throw new IllegalArgumentException("所选路口不存在或属于自动立交");
      }
   }

   static RoadGeometry.V mouth(RoadData data, UUID id, RoadGeometry.V source, RoadGeometry.Settings settings) {
      JunctionSpec s = spec(data, id);
      RoadGeometry.V radial = source.sub(s.center());
      if (radial.horizontalLength() < 1.0) {
         throw new IllegalArgumentException("起点过于靠近路口中心");
      } else {
         double angle = Math.atan2(radial.z(), radial.x());
         double distance = Math.max(
            JunctionPlanner.radius(s) + 22.0, s.arms().stream().mapToDouble(a -> a.endpoint().position().sub(s.center()).horizontalLength()).max().orElse(30.0)
         );

         for (double delta : new double[]{0.0, 30.0, -30.0, 60.0, -60.0, 90.0, -90.0, 120.0, -120.0, 180.0}) {
            double t = angle + Math.toRadians(delta);
            RoadGeometry.V p = s.center().add(new RoadGeometry.V(Math.cos(t), 0.0, Math.sin(t)).mul(distance));

            try {
               ArrayList<JunctionSpec.Arm> arms = new ArrayList<>(s.arms());
               arms.add(arm(s, p, settings, arms.size()));
               JunctionPlanner.plan(s.arms(arms));
               return p;
            } catch (IllegalArgumentException var19) {
            }
         }

         throw new IllegalArgumentException("路口没有可用接入口角度或空间；请调整起点或现有支路");
      }
   }

   static JunctionSpec.Arm arm(JunctionSpec s, RoadGeometry.V mouth, RoadGeometry.Settings input, int phase) {
      RoadGeometry.V inward = s.center().sub(mouth).horizontalUnit();
      RoadGeometry.Settings external = new RoadGeometry.Settings(
            RoadGeometry.Mode.STRAIGHT, RoadGeometry.Style.O1_ONE, Math.max(4.0, input.width()), input.thickness(), 0.35, 90.0
         )
         .options(RoadProfile.Options.DEFAULT.traffic(s.leftTraffic()));
      JunctionSpec.Arm a = JunctionSpec.arm(new RoadGeometry.Node(mouth, RoadPlanner.yaw(inward), 0.0), inward, external, true, Math.min(7, phase));
      return new JunctionSpec.Arm(
         a.endpoint(),
         a.inward(),
         a.external(),
         1,
         0,
         a.width(),
         0.0,
         RoadProfile.Median.NONE,
         0.0,
         0.0,
         false,
         4.0,
         3.0,
         1.5,
         a.phase(),
         List.of(JunctionSpec.Lane.AUTO),
         true
      );
   }

   static void reconcile(RoadData data, Map<UUID, RoadRecord> all) {
      reconcile(data, all, null);
   }

   static void reconcile(RoadData data, Map<UUID, RoadRecord> all, Set<UUID> scope) {
      Set<UUID> groups = new HashSet<>();

      for (RoadRecord r : all.values()) {
         LanePoints.Link l = LaneTopology.metadata(r).link();
         if (l != null && l.to().junction() != null) {
            groups.add(l.to().junction());
         }
      }

      for (RoadIndex.Built b : data.index.roads.values()) {
         LanePoints.Link l = LaneTopology.metadata(b.record).link();
         if (l != null && l.to().junction() != null) {
            groups.add(l.to().junction());
         }
      }

      for (UUID group : groups) {
         if (scope != null) {
            boolean touched = false;

            for (UUID id : scope) {
               RoadRecord rx = all.get(id);
               RoadIndex.Built old = data.index.roads.get(id);

               for (RoadRecord candidate : Arrays.asList(rx, old == null ? null : old.record)) {
                  if (candidate != null) {
                     LanePoints.Link link = LaneTopology.metadata(candidate).link();
                     if (group.equals(candidate.assembly()) || link != null && group.equals(link.to().junction())) {
                        touched = true;
                     }
                  }
               }
            }

            if (!touched) {
               continue;
            }
         }

         List<RoadRecord> pieces = all.values().stream().filter(rx -> group.equals(rx.assembly()) && rx.junction() != null).toList();
         List<RoadRecord> links = all.values().stream().filter(rx -> {
            LanePoints.Link l = LaneTopology.metadata(rx).link();
            return l != null && group.equals(l.to().junction());
         }).sorted(Comparator.comparing(RoadRecord::id)).toList();
         if (pieces.isEmpty()) {
            if (!links.isEmpty()) {
               throw new IllegalArgumentException("路口仍被匝道引用，请确认级联删除");
            }
         } else {
            JunctionSpec base = pieces.get(0).junction().spec();
            Set<RoadGeometry.V> previous = new HashSet<>();

            for (RoadIndex.Built bx : data.index.roads.values()) {
               LanePoints.Link l = LaneTopology.metadata(bx.record).link();
               if (l != null && group.equals(l.to().junction())) {
                  previous.add(l.junctionMouth());
               }
            }

            for (RoadRecord rx : links) {
               previous.add(LaneTopology.metadata(rx).link().junctionMouth());
            }

            ArrayList<JunctionSpec.Arm> arms = new ArrayList<>(
               base.arms().stream().filter(a -> previous.stream().noneMatch(px -> px.distance(a.endpoint().position()) < 0.01)).toList()
            );

            for (RoadRecord rx : links) {
               arms.add(arm(base, LaneTopology.metadata(rx).link().junctionMouth(), rx.settings(), arms.size()));
            }

            JunctionSpec next = base.arms(arms);
            if (!next.equals(base)) {
               JunctionPlanner.Plan plan = JunctionPlanner.plan(next);

               for (JunctionPlanner.Piece piece : plan.pieces()) {
                  for (RoadRecord other : all.values()) {
                     if (!group.equals(other.assembly())) {
                        LanePoints.Link connection = LaneTopology.metadata(other).link();
                        if ((connection == null || !group.equals(connection.to().junction())) && RoadIndex.overlapXZ(piece.mesh(), other.mesh(), 1.0)) {
                           Interchanges.checkExternal(piece.mesh(), other.mesh());
                        }
                     }
                  }
               }

               RoadRecord first = pieces.get(0);
               BlockPos center = first.b();
               all.values().removeIf(rx -> group.equals(rx.assembly()) && rx.junction() != null);

               for (int i = 0; i < plan.pieces().size(); i++) {
                  JunctionPlanner.Piece p = plan.pieces().get(i);
                  RoadGeometry.Mesh m = p.mesh();
                  BlockPos start = p.arm() >= 0 ? at(next.arms().get(p.arm()).endpoint().position()) : center;
                  UUID id = UUID.nameUUIDFromBytes((group + ":lane-junction:" + i).getBytes(StandardCharsets.UTF_8));
                  all.put(
                     id,
                     new RoadRecord(id, first.owner(), start, center, RoadRibbon.start(m), RoadRibbon.end(m), m.settings(), false, 4)
                        .junction(group, new JunctionPlanner.Ref(next, i))
                  );
               }
            }
         }
      }
   }

   static BlockPos at(RoadGeometry.V p) {
      return BlockPos.containing(p.x(), p.y(), p.z());
   }

   static void sync(RoadData data) {
      sync(data, data.junctions.keySet());
   }

   static void sync(RoadData data, Collection<UUID> groups) {
      for (UUID group : groups) {
         CompoundTag t = data.junctions.get(group);
         if (t != null) {
            RoadIndex.Built built = data.index
               .roads
               .values()
               .stream()
               .filter(b -> group.equals(b.record.assembly()) && b.record.junction() != null)
               .findFirst()
               .orElse(null);
            if (built != null) {
               JunctionSpec s = built.record.junction().spec();
               t.put("Spec", JunctionCodec.write(s));
               ListTag additions = new ListTag();

               for (RoadIndex.Built b : data.index.roads.values()) {
                  LanePoints.Link l = LaneTopology.metadata(b.record).link();
                  if (l != null && group.equals(l.to().junction())) {
                     CompoundTag e = new CompoundTag();
                     e.putUUID("Road", b.record.id());
                     e.put("Mouth", LanePointCodec.position(l.junctionMouth()));
                     e.put("Settings", RoadRecord.writeSettings(b.record.settings()));
                     additions.add(e);
                  }
               }

               t.put("ConnectorArms", additions);
               if (!t.getBoolean("Auto")) {
                  long[] points = new long[s.arms().size() + 1];
                  points[0] = built.record.b().asLong();

                  for (int i = 0; i < s.arms().size(); i++) {
                     points[i + 1] = at(s.arms().get(i).endpoint().position()).asLong();
                  }

                  t.putLongArray("Points", points);
               }
            }
         }
      }
   }

   static JunctionSpec preserve(CompoundTag config, JunctionSpec base) {
      ArrayList<JunctionSpec.Arm> arms = new ArrayList<>(base.arms());

      for (Tag v : config.getList("ConnectorArms", 10)) {
         CompoundTag t = (CompoundTag)v;
         arms.add(arm(base, LanePointCodec.position(t.getCompound("Mouth")), RoadRecord.readSettings(t.getCompound("Settings")), arms.size()));
      }

      return base.arms(arms);
   }

   private RampJunctions() {
   }
}
