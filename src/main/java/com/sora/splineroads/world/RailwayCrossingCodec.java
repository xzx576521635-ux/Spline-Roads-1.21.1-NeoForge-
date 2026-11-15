package com.sora.splineroads.world;

import com.sora.splineroads.core.RailwayCrossings;
import com.sora.splineroads.core.RoadGeometry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

final class RailwayCrossingCodec {
   static ListTag write(List<RailwayCrossings.Surface> values) {
      ListTag out = new ListTag();

      for (RailwayCrossings.Surface p : values) {
         CompoundTag t = new CompoundTag();
         t.putDouble("From", p.from());
         t.putDouble("To", p.to());
         t.putDouble("X", p.origin().x());
         t.putDouble("Y", p.origin().y());
         t.putDouble("Z", p.origin().z());
         t.putDouble("SlopeX", p.slopeX());
         t.putDouble("SlopeZ", p.slopeZ());
         t.putDouble("Transition", p.transition());
         out.add(t);
      }

      return out;
   }

   static List<RailwayCrossings.Surface> read(ListTag tags) {
      if (tags.size() > 64) {
         throw new IllegalArgumentException("Too many railway crossings");
      } else {
         ArrayList<RailwayCrossings.Surface> out = new ArrayList<>();

         for (Tag item : tags) {
            CompoundTag t = (CompoundTag)item;
            out.add(
               new RailwayCrossings.Surface(
                  t.getDouble("From"),
                  t.getDouble("To"),
                  new RoadGeometry.V(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z")),
                  t.getDouble("SlopeX"),
                  t.getDouble("SlopeZ"),
                  t.contains("Transition") ? t.getDouble("Transition") : 4.0
               )
            );
         }

         return List.copyOf(out);
      }
   }

   private RailwayCrossingCodec() {
   }
}
