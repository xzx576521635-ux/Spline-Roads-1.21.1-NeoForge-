package com.sora.splineroads.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class ManualPlannerPackets {
   private static final int PAGE_WEIGHT = 1048576;
   private static final int PAGE_ITEMS = 32;

   public static List<CompoundTag> pages(UUID session, UUID snapshot, ListTag roads, ListTag nodes) {
      ArrayList<CompoundTag> result = new ArrayList<>();
      ListTag roadPage = new ListTag();
      ListTag nodePage = new ListTag();
      long weight = 0L;

      for (boolean road : new boolean[]{true, false}) {
         for (Tag entry : road ? roads : nodes) {
            long next = (long)Math.max(0, entry.sizeInBytes()) + 128L;
            if ((!roadPage.isEmpty() || !nodePage.isEmpty()) && (weight + next > 1048576L || roadPage.size() + nodePage.size() >= 32)) {
               result.add(page(session, snapshot, roadPage, nodePage));
               roadPage = new ListTag();
               nodePage = new ListTag();
               weight = 0L;
            }

            (road ? roadPage : nodePage).add(entry);
            weight += next;
         }
      }

      if (!roadPage.isEmpty() || !nodePage.isEmpty() || result.isEmpty()) {
         result.add(page(session, snapshot, roadPage, nodePage));
      }

      for (int i = 0; i < result.size(); i++) {
         result.get(i).putInt("Page", i);
         result.get(i).putInt("Pages", result.size());
      }

      return result;
   }

   private static CompoundTag page(UUID session, UUID snapshot, ListTag roads, ListTag nodes) {
      CompoundTag tag = new CompoundTag();
      tag.putString("Kind", "manualMapPage");
      tag.putUUID("PlannerSession", session);
      tag.putUUID("MapSnapshot", snapshot);
      tag.put("MapRoads", roads);
      tag.put("Nodes", nodes);
      return tag;
   }

   private ManualPlannerPackets() {
   }
}
