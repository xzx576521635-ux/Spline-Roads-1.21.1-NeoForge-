package com.sora.splineroads.world;

import com.sora.splineroads.core.LaneDeck;
import com.sora.splineroads.core.RoadSlicing;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

final class RoadSliceCodec {
   static CompoundTag write(RoadSlicing.Fragment s) {
      CompoundTag t = new CompoundTag();
      t.putInt("Version", 1);
      t.putUUID("Source", s.source());
      t.putDouble("Origin", s.origin());
      t.putDouble("Total", s.total());
      t.putString("Connection", s.connection().name());
      t.put("Applied", AttachedPointCodec.write(s.applied()));
      t.putByteArray("Supports", RoadRecord.packStructures(s.supports()));
      ListTag patches = new ListTag();

      for (RoadSlicing.Patch p : s.geometry().patches()) {
         CompoundTag q = new CompoundTag();
         q.putDouble("From", p.from());
         q.putDouble("To", p.to());
         ListTag strips = new ListTag();

         for (LaneDeck.Strip strip : p.strips()) {
            CompoundTag v = new CompoundTag();
            v.put("AL", LanePointCodec.position(strip.al()));
            v.put("AR", LanePointCodec.position(strip.ar()));
            v.put("BL", LanePointCodec.position(strip.bl()));
            v.put("BR", LanePointCodec.position(strip.br()));
            v.putBoolean("Low", strip.lowWall());
            v.putBoolean("High", strip.highWall());
            strips.add(v);
         }

         q.put("Strips", strips);
         patches.add(q);
      }

      t.put("Patches", patches);
      return t;
   }

   static RoadSlicing.Fragment read(CompoundTag t) {
      if (t.getInt("Version") != 1) {
         throw new IllegalArgumentException("道路分割数据版本无效");
      } else {
         ArrayList<RoadSlicing.Patch> patches = new ArrayList<>();
         ListTag list = t.getList("Patches", 10);
         if (list.size() > 4) {
            throw new IllegalArgumentException("道路分割切面数量无效");
         } else {
            for (Tag tag : list) {
               CompoundTag p = (CompoundTag)tag;
               ArrayList<LaneDeck.Strip> strips = new ArrayList<>();
               ListTag values = p.getList("Strips", 10);
               if (values.size() > 16) {
                  throw new IllegalArgumentException("道路分割切面过大");
               }

               for (Tag value : values) {
                  CompoundTag v = (CompoundTag)value;
                  strips.add(
                     new LaneDeck.Strip(
                        LanePointCodec.position(v.getCompound("AL")),
                        LanePointCodec.position(v.getCompound("AR")),
                        LanePointCodec.position(v.getCompound("BL")),
                        LanePointCodec.position(v.getCompound("BR")),
                        v.getBoolean("Low"),
                        v.getBoolean("High")
                     )
                  );
               }

               patches.add(new RoadSlicing.Patch(p.getDouble("From"), p.getDouble("To"), strips));
            }

            return new RoadSlicing.Fragment(
               t.getUUID("Source"),
               t.getDouble("Origin"),
               t.getDouble("Total"),
               RoadSlicing.Connection.valueOf(t.getString("Connection")),
               AttachedPointCodec.read(t.getCompound("Applied")),
               new RoadSlicing.Geometry(patches),
               t.contains("Supports") ? RoadRecord.unpackStructures(t.getByteArray("Supports")) : List.of()
            );
         }
      }
   }

   private RoadSliceCodec() {
   }
}
