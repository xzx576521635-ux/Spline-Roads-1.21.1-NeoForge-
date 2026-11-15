package com.sora.splineroads.world;

import com.sora.splineroads.core.TollPlaza;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class TollInputs {
   public static CompoundTag command(CompoundTag payload) {
      CompoundTag out = new CompoundTag();

      for (String key : List.of("Id", "Points", "Dimension", "InlineRoad", "InlineStation", "InlineSignature", "Adjust", "SelectionStamp")) {
         if (payload.contains(key)) {
            out.put(key, payload.get(key).copy());
         }
      }

      out.putBoolean("CompactToll", true);
      out.put("Spec", TollCodec.config(TollCodec.config(payload.getCompound("Spec"))));
      return out;
   }

   static String stamp(CompoundTag payload) {
      CompoundTag context = new CompoundTag();

      for (String key : List.of("Points", "Spec", "SnapshotA", "SnapshotB", "Connections", "Revision", "Id", "Dimension")) {
         if (payload.contains(key)) {
            context.put(key, payload.get(key));
         }
      }

      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");

         try (DataOutputStream out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            canonical(context, out);
         }

         return HexFormat.of().formatHex(digest.digest());
      } catch (NoSuchAlgorithmException | IOException var8) {
         throw new IllegalStateException("收费站选择校验失败", var8);
      }
   }

   private static void canonical(Tag tag, DataOutput out) throws IOException {
      out.writeByte(tag.getId());
      if (tag instanceof CompoundTag compound) {
         out.writeInt(compound.size());

         for (String key : new TreeSet<>(compound.getAllKeys())) {
            out.writeUTF(key);
            canonical(compound.get(key), out);
         }
      } else if (tag instanceof ListTag list) {
         out.writeInt(list.size());

         for (Tag value : list) {
            canonical(value, out);
         }
      } else {
         tag.write(out);
      }
   }

   static CompoundTag expand(ServerLevel level, ServerPlayer player, CompoundTag command) {
      if (!command.getBoolean("CompactToll")) {
         return command;
      } else {
         CompoundTag authoritative = TollRoads.payload(level, player, command.getLongArray("Points"), command.hasUUID("Id") ? command.getUUID("Id") : null);
         if (authoritative.getString("SelectionStamp").equals(command.getString("SelectionStamp"))
            && authoritative.getString("Dimension").equals(command.getString("Dimension"))) {
            TollPlaza.Spec original = TollCodec.read(authoritative.getCompound("Spec"));
            authoritative.put(
               "Spec",
               TollCodec.write(
                  new TollPlaza.Spec(
                     original.a(), original.b(), TollCodec.config(command.getCompound("Spec")), original.portA(), original.portB(), original.path()
                  )
               )
            );
            authoritative.putString("Adjust", command.getString("Adjust"));

            for (String key : List.of("Token", "Request")) {
               if (command.contains(key)) {
                  authoritative.put(key, command.get(key).copy());
               }
            }

            return authoritative;
         } else {
            throw new IllegalArgumentException("端点或连接道路已变化，请重新打开收费站");
         }
      }
   }

   private TollInputs() {
   }
}
