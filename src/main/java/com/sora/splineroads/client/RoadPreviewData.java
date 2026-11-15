package com.sora.splineroads.client;

import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadRibbon;
import com.sora.splineroads.core.RoadSurface;
import com.sora.splineroads.core.TollPlanner;
import com.sora.splineroads.core.TollPlaza;
import com.sora.splineroads.world.LaneTopology;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.world.TollCodec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public record RoadPreviewData(RoadPreviewData.Toll toll, RoadPreviewData.Lane lane, List<RoadGeometry.Mesh> conflicts, Set<UUID> dependencies) {
   public static boolean queued(CompoundTag envelope) {
      String type = envelope.getString("Type");
      CompoundTag payload = type.equals("open") ? envelope.getCompound("Payload") : envelope;
      if (payload.getList("ConflictRoads", 10).isEmpty()) {
         return type.equals("open") && !payload.contains("Error")
            ? Set.of("tollCheck", "laneRampCheck", "corridorCheck").contains(payload.getString("Kind"))
            : false;
      } else {
         return type.equals("open") || type.equals("resultPreview");
      }
   }

   public static RoadPreviewData prepare(CompoundTag envelope) {
      return preparePayload(envelope.getString("Type").equals("open") ? envelope.getCompound("Payload") : envelope);
   }

   public static RoadPreviewData preparePayload(CompoundTag payload) {
      LinkedHashSet<UUID> dependencies = new LinkedHashSet<>();
      ArrayList<RoadGeometry.Mesh> conflicts = new ArrayList<>();

      for (Tag tag : payload.getList("ConflictRoads", 10)) {
         try {
            RoadRecord road = RoadRecord.load((CompoundTag)tag);
            conflicts.add(road.mesh());
            depend(dependencies, road);
         } catch (IllegalArgumentException var13) {
         }
      }

      RoadPreviewData.Toll toll = null;
      RoadPreviewData.Lane lane = null;
      if (!payload.contains("Error") && payload.getString("Kind").equals("tollCheck")) {
         CompoundTag input = payload.getCompound("Inputs");
         TollPlaza.Spec spec = TollCodec.read(input.getCompound("Spec"));
         ArrayList<RoadIndex.Built> roads = new ArrayList<>();

         for (Tag tag : input.getList("Roads", 10)) {
            RoadRecord road = RoadRecord.load((CompoundTag)tag);
            depend(dependencies, road);
            roads.add(new RoadIndex.Built(road));
         }

         RoadIndex.Built station = roads.stream()
            .filter(r -> spec.equals(r.record.toll()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("收费站预览缺少站区"));
         TollPlanner.Plan plan = station.record.tollPlan();
         ArrayList<RoadSurface.Face> faces = new ArrayList<>(plan.paint());

         for (RoadIndex.Built road : roads) {
            road.record.structures().forEach(part -> faces.addAll(part.faces()));
            if (road != station) {
               faces.addAll(RoadSurface.build(road.mesh, List.of(), roads.stream().filter(r -> r != road).map(r -> r.mesh).toList()).markings());
            }
         }

         toll = new RoadPreviewData.Toll(station.mesh, plan, roads.stream().map(r -> r.mesh).toList(), List.copyOf(faces));
      } else if (!payload.contains("Error") && payload.getString("Kind").equals("laneRampCheck")) {
         RoadRecord roadx = RoadRecord.load(payload.getCompound("Road"));
         RoadGeometry.Mesh mesh = roadx.mesh();
         depend(dependencies, roadx);
         ArrayList<RoadGeometry.Mesh> views = new ArrayList<>();

         for (Tag tag : payload.getList("ChangedRoads", 10)) {
            RoadRecord changed = RoadRecord.load((CompoundTag)tag);
            depend(dependencies, changed);
            views.add(changed.mesh());
         }

         lane = new RoadPreviewData.Lane(roadx, mesh, views.isEmpty() ? List.of(mesh) : List.copyOf(views), RoadRibbon.minRadius(mesh));
      }

      return new RoadPreviewData(toll, lane, List.copyOf(conflicts), Set.copyOf(dependencies));
   }

   private static void depend(Set<UUID> ids, RoadRecord road) {
      ids.add(road.id());
      LanePoints.Link link = LaneTopology.metadata(road).link();
      if (link != null) {
         if (link.from().road() != null) {
            ids.add(link.from().road());
         }

         if (link.to().road() != null) {
            ids.add(link.to().road());
         }
      }
   }

   public static CompoundTag failed(CompoundTag envelope, String message) {
      CompoundTag out = envelope.copy();
      CompoundTag payload = out.getString("Type").equals("open") ? out.getCompound("Payload") : out;

      for (String key : List.of("Inputs", "Road", "ChangedRoads", "ConflictRoads")) {
         payload.remove(key);
      }

      if (out.getString("Type").equals("open")) {
         payload.putString("Error", message);
      } else {
         out.putBoolean("Success", false);
         out.putString("Message", message);
      }

      return out;
   }

   public static record Lane(RoadRecord road, RoadGeometry.Mesh mesh, List<RoadGeometry.Mesh> roads, double minimumRadius) {
   }

   public static record Toll(RoadGeometry.Mesh mesh, TollPlanner.Plan plan, List<RoadGeometry.Mesh> roads, List<RoadSurface.Face> faces) {
   }
}
