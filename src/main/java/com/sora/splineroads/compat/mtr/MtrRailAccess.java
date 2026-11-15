package com.sora.splineroads.compat.mtr;

import com.sora.splineroads.core.RailbedPath;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class MtrRailAccess {
   private static final String REASON = "MTR 联动尚未适配此移植版本（1.21.1 NeoForge）";

   public static long revision(String dimension) {
      return 0L;
   }

   public static String unavailable() {
      return "MTR 联动尚未适配此移植版本（1.21.1 NeoForge）";
   }

   public static CompletableFuture<RailbedPath.Path> read(String dimension, RailbedPath.Node a, RailbedPath.Node b) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("MTR 联动尚未适配此移植版本（1.21.1 NeoForge）"));
   }

   public static boolean trainNode(Object block) {
      return false;
   }

   public static boolean railbedItem(Object item) {
      return false;
   }

   public static Object nodeShape(Object original, Object world, Object position) {
      return original;
   }

   public static CompletableFuture<List<RailbedPath.Path>> nearby(String dimension, RailbedPath.Path base) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("MTR 联动尚未适配此移植版本（1.21.1 NeoForge）"));
   }

   public static void changed(Object simulator) {
   }

   public static void tick(Object simulator) {
   }

   private MtrRailAccess() {
   }
}
