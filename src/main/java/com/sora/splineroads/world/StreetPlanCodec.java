package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.StreetPlan;
import com.sora.splineroads.core.StreetTerrain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class StreetPlanCodec {
   public static CompoundTag write(StreetPlan.Input input) {
      CompoundTag t = new CompoundTag();
      t.putInt("Version", 1);
      t.putLongArray("Area", input.selection().stream().mapToLong(StreetPlan.Cell::key).toArray());
      t.putLongArray("Protect", input.protection().stream().mapToLong(StreetPlan.Cell::key).toArray());
      t.putLongArray("Corridor", input.corridor().stream().mapToLong(StreetPlan.Cell::key).toArray());
      t.putBoolean("CorridorConfirmed", input.corridorConfirmed());
      ListTag centers = new ListTag();
      input.centers().forEach(p -> centers.add(point(p)));
      t.put("Centers", centers);
      ListTag entries = new ListTag();

      for (StreetPlan.Entry e : input.entries()) {
         CompoundTag tag = point(e.point());
         tag.putDouble("Y", e.height());
         tag.putDouble("Yaw", e.yaw());
         tag.putBoolean("Left", e.left());
         tag.putBoolean("Existing", e.existing());
         tag.putString("Reference", e.reference());
         entries.add(tag);
      }

      t.put("Entries", entries);
      StreetPlan.Options o = input.options();
      CompoundTag v = new CompoundTag();
      v.putString("Style", o.style().name());
      v.putDouble("Density", o.density());
      v.putDouble("BlockSize", o.blockSize());
      v.putDouble("Direction", o.direction());
      v.putLong("Seed", o.seed());
      v.putDouble("PrimaryWidth", o.primaryWidth());
      v.putDouble("SecondaryWidth", o.secondaryWidth());
      v.putDouble("LocalWidth", o.localWidth());
      v.putString("PrimarySection", o.primarySection().name());
      v.putString("SecondarySection", o.secondarySection().name());
      v.putString("LocalSection", o.localSection().name());
      v.putBoolean("Left", o.left());
      v.putBoolean("Sidewalks", o.sidewalks());
      v.putBoolean("Lighting", o.lighting());
      v.putDouble("Earthwork", o.earthwork());
      t.put("Options", v);
      return t;
   }

   public static StreetPlan.Input read(CompoundTag t) {
      if (t.getInt("Version") != 1) {
         throw new IllegalArgumentException("路网草案版本不同，请重新规划");
      } else {
         long[] area = t.getLongArray("Area");
         long[] protect = t.getLongArray("Protect");
         ListTag centers = t.getList("Centers", 10);
         ListTag entries = t.getList("Entries", 10);
         if (area.length <= 1024 && protect.length <= 1024 && centers.size() <= 8 && entries.size() <= 8) {
            HashSet<StreetPlan.Cell> selection = new HashSet<>();

            for (long p : area) {
               selection.add(StreetPlan.Cell.from(p));
            }

            HashSet<StreetPlan.Cell> protection = new HashSet<>();

            for (long p : protect) {
               protection.add(StreetPlan.Cell.from(p));
            }

            ArrayList<StreetPlan.Point> locations = new ArrayList<>();

            for (Tag tag : centers) {
               locations.add(point((CompoundTag)tag));
            }

            ArrayList<StreetPlan.Entry> interfaces = new ArrayList<>();

            for (Tag tag : entries) {
               CompoundTag e = (CompoundTag)tag;
               interfaces.add(
                  new StreetPlan.Entry(point(e), e.getDouble("Y"), e.getDouble("Yaw"), e.getBoolean("Left"), e.getBoolean("Existing"), e.getString("Reference"))
               );
            }

            CompoundTag v = t.getCompound("Options");
            StreetPlan.Options options = new StreetPlan.Options(
               StreetPlan.Style.valueOf(v.getString("Style")),
               v.getDouble("Density"),
               v.getDouble("BlockSize"),
               v.getDouble("Direction"),
               v.getLong("Seed"),
               v.getDouble("PrimaryWidth"),
               v.getDouble("SecondaryWidth"),
               v.getDouble("LocalWidth"),
               RoadGeometry.Style.valueOf(v.getString("PrimarySection")),
               RoadGeometry.Style.valueOf(v.getString("SecondarySection")),
               RoadGeometry.Style.valueOf(v.getString("LocalSection")),
               v.getBoolean("Left"),
               v.getBoolean("Sidewalks"),
               v.getBoolean("Lighting"),
               v.getDouble("Earthwork")
            );
            HashSet<StreetPlan.Cell> corridor = new HashSet<>();
            long[] extra = t.getLongArray("Corridor");
            if (extra.length > 1024) {
               throw new IllegalArgumentException("走廊数量超限");
            } else {
               for (long key : extra) {
                  corridor.add(StreetPlan.Cell.from(key));
               }

               return new StreetPlan.Input(selection, protection, locations, interfaces, options, corridor, t.getBoolean("CorridorConfirmed"));
            }
         } else {
            throw new IllegalArgumentException("路网参数数量超限");
         }
      }
   }

   public static CompoundTag write(StreetTerrain terrain) {
      CompoundTag result = new CompoundTag();
      ListTag chunks = new ListTag();

      for (Entry<StreetPlan.Cell, StreetTerrain.Chunk> e : terrain.chunks().entrySet()) {
         CompoundTag t = new CompoundTag();
         t.putLong("Cell", e.getKey().key());
         t.putIntArray("Height", e.getValue().heights());
         t.putByteArray("Ground", e.getValue().ground());
         t.putLongArray("Fingerprint", e.getValue().fingerprints());
         chunks.add(t);
      }

      result.put("Chunks", chunks);
      return result;
   }

   public static StreetTerrain terrain(CompoundTag tag) {
      ListTag source = tag.getList("Chunks", 10);
      if (source.size() > 1024) {
         throw new IllegalArgumentException("地形快照过大");
      } else {
         HashMap<StreetPlan.Cell, StreetTerrain.Chunk> chunks = new HashMap<>();

         for (Tag item : source) {
            CompoundTag t = (CompoundTag)item;
            StreetPlan.Cell key = StreetPlan.Cell.from(t.getLong("Cell"));
            if (chunks.put(key, new StreetTerrain.Chunk(t.getIntArray("Height"), t.getByteArray("Ground"), t.getLongArray("Fingerprint"))) != null) {
               throw new IllegalArgumentException("地形快照区块重复");
            }
         }

         return new StreetTerrain(chunks);
      }
   }

   private static CompoundTag point(StreetPlan.Point p) {
      CompoundTag t = new CompoundTag();
      t.putDouble("X", p.x());
      t.putDouble("Z", p.z());
      return t;
   }

   private static StreetPlan.Point point(CompoundTag t) {
      return new StreetPlan.Point(t.getDouble("X"), t.getDouble("Z"));
   }

   private StreetPlanCodec() {
   }
}
