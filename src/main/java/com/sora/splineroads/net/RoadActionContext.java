package com.sora.splineroads.net;

import com.sora.splineroads.core.RoadEditRequests;
import net.minecraft.nbt.CompoundTag;

public final class RoadActionContext {
   public static String target(CompoundTag command) {
      if (command.hasUUID("Id")) {
         return "road:" + command.getUUID("Id");
      } else if (command.hasUUID("Token")) {
         return "token:" + command.getUUID("Token");
      } else if (command.contains("Pos")) {
         return "node:" + command.getLong("Pos");
      } else {
         return !command.contains("A") && !command.contains("B") ? "" : "ends:" + command.getLong("A") + ":" + command.getLong("B");
      }
   }

   public static void attach(CompoundTag command, RoadEditRequests.Request request) {
      command.putUUID("ReplyId", request.id());
      command.putLong("ReplyGeneration", request.generation());
   }

   public static void echo(CompoundTag command, CompoundTag reply) {
      if (command.hasUUID("ReplyId")) {
         reply.putUUID("ReplyId", command.getUUID("ReplyId"));
         reply.putLong("ReplyGeneration", command.getLong("ReplyGeneration"));
         reply.putString("ReplyAction", command.getString("Action"));
         reply.putString("ReplyTarget", target(command));
      }
   }

   private RoadActionContext() {
   }
}
