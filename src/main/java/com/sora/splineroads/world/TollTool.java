package com.sora.splineroads.world;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.net.RoadNetwork;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class TollTool extends Item {
   private static final Map<ServerPlayer, TollTool.Preview> PREVIEWS = new WeakHashMap<>();

   public TollTool() {
      super(new Properties().stacksTo(1));
   }

   public static void rememberPreview(ServerPlayer player, ItemStack stack, CompoundTag command, CompoundTag inputs) {
      CompoundTag saved = new CompoundTag();
      saved.putLong("Request", command.getLong("Request"));
      saved.putUUID("Token", inputs.getUUID("Token"));
      ItemNBT.get(stack).put("TollPreview", saved);
      PREVIEWS.put(player, new TollTool.Preview(saved.getLong("Request"), saved.getUUID("Token")));
   }

   public static void cancelPreview(ServerPlayer player, CompoundTag command) {
      TollTool.Preview saved = PREVIEWS.get(player);
      if (saved != null
         && command.contains("Request")
         && saved.request() == command.getLong("Request")
         && (!command.hasUUID("Token") || saved.token().equals(command.getUUID("Token")))) {
         RoadData.forgetPreview(player.getUUID(), saved.token());
         PREVIEWS.remove(player);
      }
   }

   public static void clearPreviews() {
      PREVIEWS.clear();
   }

   private static void clear(ServerPlayer player, ItemStack stack) {
      CompoundTag state = ItemNBT.edit(stack);
      CompoundTag previous = state.getCompound("TollPreview");
      if (previous.hasUUID("Token")) {
         RoadData.forgetPreview(player.getUUID(), previous.getUUID("Token"));
      }

      cancelPreview(player, previous);
      state.remove("Points");
      state.remove("InlineSelection");
      state.remove("TollPreview");
      CompoundTag t = new CompoundTag();
      t.putString("Kind", "tollReset");
      t.putLong("Request", previous.getLong("Request"));
      RoadNetwork.open(player, t);
   }

   static void inlineSelection(ItemStack stack, CompoundTag payload) {
      CompoundTag tag = ItemNBT.edit(stack);
      tag.remove("Points");
      tag.putString("Dimension", payload.getString("Dimension"));
      tag.put("InlineSelection", payload.copy());
   }

   private static void edit(ServerPlayer p, RoadRecord r) {
      RoadNetwork.open(p, TollRoads.payload(p.serverLevel(), p, new long[0], r.id()));
   }

   private static void clearClient() {
      CompoundTag reset = new CompoundTag();
      reset.putString("Kind", "tollReset");
      RoadTool.openClient.accept(reset);
   }

   public InteractionResult useOn(UseOnContext context) {
      if (context.getLevel().isClientSide) {
         if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            clearClient();
         }

         return InteractionResult.SUCCESS;
      } else if (!(context.getPlayer() instanceof ServerPlayer p)) {
         return InteractionResult.PASS;
      } else if (p.isShiftKeyDown()) {
         clear(p, context.getItemInHand());
         return InteractionResult.CONSUME;
      } else {
         try {
            InteractionResult var17;
            try (RoadWorkChunks chunks = RoadWorkChunks.open(p.serverLevel())) {
               ServerLevel level = p.serverLevel();
               BlockPos at = RoadTool.endpointAtHit(context);
               RoadData data = RoadData.get(level);
               RoadRecord station = TollRoads.at(data, at);
               if (station == null) {
                  RoadIndex.Built hit = RoadTool.pick(p);
                  if (!(level.getBlockEntity(at) instanceof NodeEntity)) {
                     if (hit != null && hit.record.toll() != null) {
                        edit(p, hit.record);
                        return InteractionResult.CONSUME;
                     }

                     if (hit != null) {
                        Vec3 location = context.getClickLocation();
                        CompoundTag t = InlineTollRoads.payload(
                           level, p, hit.record.id(), RoadSplitGeometry.station(hit.mesh, new RoadGeometry.V(location.x, location.y, location.z))
                        );
                        inlineSelection(context.getItemInHand(), t);
                        RoadNetwork.open(p, t);
                        return InteractionResult.CONSUME;
                     }

                     throw new IllegalArgumentException("收费站生成器：右键道路断面，或依次选择端点 A、B");
                  }

                  RoadData.requireNode(level, at, p);
                  CompoundTag tag = ItemNBT.edit(context.getItemInHand());
                  String dimension = level.dimension().location().toString();
                  long[] selected = dimension.equals(tag.getString("Dimension")) ? tag.getLongArray("Points") : new long[0];
                  if (selected.length >= 2) {
                     selected = new long[0];
                  }

                  if (selected.length == 1 && selected[0] == at.asLong()) {
                     throw new IllegalArgumentException("请选择另一个端点 B");
                  }

                  long[] next = Arrays.copyOf(selected, selected.length + 1);
                  next[selected.length] = at.asLong();
                  tag.putLongArray("Points", next);
                  tag.putString("Dimension", dimension);
                  tag.remove("InlineSelection");
                  if (next.length == 2) {
                     RoadNetwork.open(p, TollRoads.payload(level, p, next, null));
                  } else {
                     p.displayClientMessage(Component.literal("已选 A；请选择站区另一端 B"), true);
                  }

                  return InteractionResult.CONSUME;
               }

               edit(p, station);
               var17 = InteractionResult.CONSUME;
            }

            return var17;
         } catch (IllegalArgumentException var15) {
            p.displayClientMessage(Component.literal(var15.getMessage()), false);
            return InteractionResult.CONSUME;
         }
      }
   }

   public InteractionResultHolder<ItemStack> use(Level level, Player who, InteractionHand hand) {
      ItemStack stack = who.getItemInHand(hand);
      if (level.isClientSide && who.isShiftKeyDown()) {
         clearClient();
      }

      if (!level.isClientSide && who instanceof ServerPlayer p) {
         if (p.isShiftKeyDown()) {
            clear(p, stack);
         } else {
            try {
               RoadIndex.Built hit = RoadTool.pick(p);
               if (hit != null && hit.record.toll() != null) {
                  edit(p, hit.record);
               } else {
                  CompoundTag t = ItemNBT.edit(stack);
                  if (t.getLongArray("Points").length == 2 && level.dimension().location().toString().equals(t.getString("Dimension"))) {
                     RoadNetwork.open(p, TollRoads.payload(p.serverLevel(), p, t.getLongArray("Points"), null));
                  } else {
                     CompoundTag reply = new CompoundTag();
                     reply.putString("Kind", "tollResume");
                     RoadNetwork.open(p, reply);
                  }
               }
            } catch (IllegalArgumentException var9) {
               p.displayClientMessage(Component.literal(var9.getMessage()), false);
            }
         }
      }

      return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
   }

   public static void selection(ServerPlayer p, ItemStack tool, CompoundTag t) {
      if (t.hasUUID("InlineRoad")) {
         CompoundTag selection = ItemNBT.get(tool).getCompound("InlineSelection");
         if (!selection.hasUUID("InlineRoad")
            || !selection.getUUID("InlineRoad").equals(t.getUUID("InlineRoad"))
            || selection.getDouble("InlineStation") != t.getDouble("InlineStation")
            || selection.getInt("InlineSignature") != t.getInt("InlineSignature")) {
            throw new IllegalArgumentException("收费站断面选择已失效，请重新点击道路");
         }
      } else if (!t.hasUUID("Id")) {
         CompoundTag tag = ItemNBT.edit(tool);
         if (!Arrays.equals(t.getLongArray("Points"), tag.getLongArray("Points"))
            || !p.level().dimension().location().toString().equals(tag.getString("Dimension"))) {
            throw new IllegalArgumentException("收费站选择已失效，请重新选 A/B");
         }
      }
   }

   public void appendHoverText(ItemStack s, TooltipContext l, List<Component> lines, TooltipFlag f) {
      lines.add(Component.literal("右键道路断面原位生成；也可选择端点 A、B 独立生成"));
      lines.add(Component.literal("Shift＋右键清除临时选择；右键收费站编辑"));
   }

   private static record Preview(long request, UUID token) {
   }
}
