package com.sora.splineroads.world;

import com.sora.splineroads.core.RailbedOptions;
import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RailbedProfile;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

public final class RailbedCodec {
   public static CompoundTag options(RailbedOptions value) {
      CompoundTag t = new CompoundTag();
      t.putBoolean("LeftRail", value.leftRail());
      t.putBoolean("RightRail", value.rightRail());
      t.putBoolean("Catenary", value.catenary());
      t.putDouble("PoleSpacing", value.poleSpacing());
      t.putDouble("WireHeight", value.wireHeight());
      t.putInt("SingleSide", value.singleSide());
      t.putString("Bridge", value.bridge().name());
      t.putString("Tunnel", value.tunnel().name());
      t.putString("Fence", value.fence().name());
      return t;
   }

   public static RailbedOptions options(CompoundTag tag) {
      return tag.isEmpty()
         ? RailbedOptions.DEFAULT
         : new RailbedOptions(
            tag.getBoolean("LeftRail"),
            tag.getBoolean("RightRail"),
            tag.getBoolean("Catenary"),
            tag.getDouble("PoleSpacing"),
            tag.getDouble("WireHeight"),
            tag.getInt("SingleSide"),
            tag.contains("Bridge") ? RoadInfrastructure.Bridge.valueOf(tag.getString("Bridge")) : RoadInfrastructure.Bridge.STANDARD,
            tag.contains("Tunnel") ? RoadInfrastructure.Tunnel.valueOf(tag.getString("Tunnel")) : RoadInfrastructure.Tunnel.BOX,
            tag.contains("Fence") ? RailbedOptions.Fence.valueOf(tag.getString("Fence")) : RailbedOptions.Fence.SOLID
         );
   }

   public static CompoundTag write(RailbedPath.Path path) {
      CompoundTag out = new CompoundTag();
      ListTag segments = new ListTag();

      for (RailbedPath.Segment s : path.segments()) {
         CompoundTag t = new CompoundTag();
         t.putString("Id", s.id());
         t.putLong("From", pos(s.from()).asLong());
         t.putLong("To", pos(s.to()).asLong());
         t.putBoolean("Reverse", s.reverse());
         t.putDouble("Length", s.length());
         ListTag styles = new ListTag();
         s.styles().forEach(style -> styles.add(StringTag.valueOf(style)));
         t.put("Styles", styles);
         ListTag points = new ListTag();

         for (RailbedPath.Point p : s.points()) {
            CompoundTag q = new CompoundTag();
            q.putDouble("S", p.parameter());
            q.putDouble("X", p.position().x());
            q.putDouble("Y", p.position().y());
            q.putDouble("Z", p.position().z());
            points.add(q);
         }

         t.put("Points", points);
         segments.add(t);
      }

      out.put("Segments", segments);
      return out;
   }

   public static RailbedPath.Path read(CompoundTag tag) {
      ArrayList<RailbedPath.Segment> segments = new ArrayList<>();
      ListTag list = tag.getList("Segments", 10);
      if (list.size() > 64) {
         throw new IllegalArgumentException("铁路快照轨段数量超限");
      } else {
         int count = 0;

         for (Tag raw : list) {
            CompoundTag t = (CompoundTag)raw;
            ListTag source = t.getList("Points", 10);
            count += source.size();
            if (count > 8192) {
               throw new IllegalArgumentException("铁路快照采样数量超限");
            }

            ArrayList<RailbedPath.Point> points = new ArrayList<>();

            for (Tag r : source) {
               CompoundTag p = (CompoundTag)r;
               points.add(new RailbedPath.Point(p.getDouble("S"), new RoadGeometry.V(p.getDouble("X"), p.getDouble("Y"), p.getDouble("Z"))));
            }

            List<String> styles = t.getList("Styles", 8).stream().<String>map(Tag::getAsString).toList();
            segments.add(
               new RailbedPath.Segment(
                  t.getString("Id"),
                  node(BlockPos.of(t.getLong("From"))),
                  node(BlockPos.of(t.getLong("To"))),
                  t.getBoolean("Reverse"),
                  t.getDouble("Length"),
                  styles,
                  points
               )
            );
         }

         return new RailbedPath.Path(segments);
      }
   }

   public static BlockPos pos(RailbedPath.Node p) {
      return new BlockPos(p.x(), p.y(), p.z());
   }

   public static RailbedPath.Node node(BlockPos p) {
      return new RailbedPath.Node(p.getX(), p.getY(), p.getZ());
   }

   public static CompoundTag profile(RailbedProfile value) {
      CompoundTag tag = new CompoundTag();
      ListTag paths = new ListTag();
      value.paths().forEach(p -> paths.add(write(p)));
      tag.put("Paths", paths);
      tag.putDouble("Width", value.requestedWidth());
      tag.put("Fittings", options(value.fittings()));
      return tag;
   }

   public static RailbedProfile profile(CompoundTag tag) {
      return new RailbedProfile(
         tag.getList("Paths", 10).stream().map(t -> read((CompoundTag)t)).toList(), tag.getDouble("Width"), options(tag.getCompound("Fittings"))
      );
   }

   private RailbedCodec() {
   }
}
