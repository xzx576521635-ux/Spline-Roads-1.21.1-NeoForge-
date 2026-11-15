package com.sora.splineroads.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RoadConflictIds {
   private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

   public static List<UUID> read(String message) {
      LinkedHashSet<UUID> ids = new LinkedHashSet<>();
      Matcher matcher = ID.matcher(message == null ? "" : message);

      while (matcher.find()) {
         ids.add(UUID.fromString(matcher.group()));
      }

      return List.copyOf(ids);
   }

   public static String display(String message) {
      if (message == null) {
         return "匝道规划失败";
      } else {
         String text = message;
         int n = 0;

         for (UUID id : read(message)) {
            text = text.replaceAll("(?i)" + id, "【红色道路 " + ++n + "】");
         }

         return text;
      }
   }

   private RoadConflictIds() {
   }
}
