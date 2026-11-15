package com.sora.splineroads.world;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.core.CorridorPlanner;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.net.RoadNetwork;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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

public final class InterchangeTool extends Item {
   private final int arms;
   private final CorridorPlanner.Kind corridor;

   public InterchangeTool() {
      this(4);
   }

   public InterchangeTool(int arms) {
      this(arms, null);
   }

   public InterchangeTool(int arms, CorridorPlanner.Kind corridor) {
      super(new Properties().stacksTo(1));
      if (arms >= 2 && arms <= 6 && (arms != 2 || corridor != null)) {
         this.arms = arms;
         this.corridor = corridor;
      } else {
         throw new IllegalArgumentException("invalid generator arms");
      }
   }

   public int arms() {
      return this.arms;
   }

   public boolean available() {
      return this.arms <= 6;
   }

   private String selection() {
      if (this.corridor != null) {
         return this.arms == 2 ? "A/B 主路两端（自动生成同向辅路）" : "A/B 第一层两端＋C/D 第二层两端（平行双层路）";
      } else {
         return this.arms == 3
            ? "A/B 主路两端＋C 支路端点"
            : (this.arms == 4 ? "A/B 主路两端＋C/D 另一条主路两端" : (this.arms == 5 ? "A/B、C/D 两条贯通主路＋E 支路端点" : "A/B、C/D、E/F 三条贯通主路两端"));
      }
   }

   private CompoundTag tagged(CompoundTag payload) {
      payload.putInt("GeneratorArms", this.arms);
      if (this.corridor != null) {
         payload.putString("GeneratorMode", this.corridor.name());
         payload.putString("Kind", payload.getString("Kind").replace("interchange", "corridor"));
         if (payload.contains("Nodes")) {
            Corridors.initialize(payload, this.corridor);
         }
      }

      return payload;
   }

   public void requireDescriptor(CompoundTag t) {
      if (t == null) {
         throw new IllegalArgumentException("道路组合已不存在");
      } else {
         this.requireLayout(t.getLongArray("Points"));
         String mode = t.contains("Corridor") ? t.getCompound("Corridor").getString("Mode") : "";
         if (!mode.equals(this.corridor == null ? "" : this.corridor.name())) {
            throw new IllegalArgumentException("请使用与当前道路组合对应的生成器");
         }
      }
   }

   public void requireLayout(long[] points) {
      if (!this.available()) {
         throw new IllegalArgumentException(this.arms + " 向立交生成器暂未开放建造");
      } else if (points.length != this.arms) {
         throw new IllegalArgumentException(this.corridor != null ? "请使用与此组合对应的主辅路或双层出入口工具" : "请使用 " + points.length + " 向立交生成器编辑此立交");
      }
   }

   private boolean placeholder(Player player) {
      if (this.available()) {
         return false;
      } else {
         if (!player.level().isClientSide) {
            player.displayClientMessage(Component.literal(this.arms + " 向立交生成器：预留物品，暂未开放建造"), true);
         }

         return true;
      }
   }

   public InteractionResult useOn(UseOnContext context) {
      if (context.getLevel().isClientSide) {
         return InteractionResult.SUCCESS;
      } else if (!(context.getPlayer() instanceof ServerPlayer player)) {
         return InteractionResult.PASS;
      } else if (this.placeholder(player)) {
         return InteractionResult.CONSUME;
      } else {
         try (RoadWorkChunks workChunks = RoadWorkChunks.open(player.serverLevel())) {
            ServerLevel level = player.serverLevel();
            CompoundTag tool = ItemNBT.edit(context.getItemInHand());
            RoadIndex.Built hitRoad = RoadTool.pick(player);
            Vec3 hit = context.getClickLocation();
            RoadAttachments.Point attached = hitRoad == null ? null : AttachedPointTool.nearest(hitRoad, new RoadGeometry.V(hit.x, hit.y, hit.z));
            if (attached != null) {
               if (hitRoad.record.assembly() != null) {
                  throw new IllegalArgumentException("立交主路附属点仅用于标线分段，不能用来选择整路");
               }

               RoadData.requireOwner(player, hitRoad.record.owner());
               String dimension = level.dimension().location().toString();
               long[] selected = dimension.equals(tool.getString("Dimension")) ? tool.getLongArray("Points") : new long[0];
               if (selected.length >= this.arms) {
                  selected = new long[0];
               }

               if (selected.length % 2 == 0 && selected.length + 2 <= this.arms) {
                  long a = hitRoad.record.a().asLong();
                  long b = hitRoad.record.b().asLong();

                  for (long old : selected) {
                     if (old == a || old == b) {
                        throw new IllegalArgumentException("该道路与已选端点重复");
                     }
                  }

                  long[] next = Arrays.copyOf(selected, selected.length + 2);
                  next[selected.length] = a;
                  next[selected.length + 1] = b;
                  tool.putLongArray("Points", next);
                  tool.putString("Dimension", dimension);
                  if (next.length == this.arms) {
                     RoadNetwork.open(player, this.tagged(Interchanges.payload(level, player, next, null)));
                  } else {
                     player.displayClientMessage(Component.literal("已通过附属点选中完整道路；继续选择另一条道路或支路端点"), true);
                  }

                  return InteractionResult.CONSUME;
               }

               throw new IllegalArgumentException("附属点一次选择完整道路；请先完成或清除当前单端点选择");
            }

            BlockPos p = RoadTool.endpointAtHit(context);
            if (level.getBlockEntity(p) instanceof NodeEntity) {
               RoadData.requireNode(level, p, player);
               String dimensionx = level.dimension().location().toString();
               long[] selectedx = dimensionx.equals(tool.getString("Dimension")) ? tool.getLongArray("Points") : new long[0];
               if (selectedx.length >= this.arms) {
                  selectedx = new long[0];
               }

               for (long point : selectedx) {
                  if (point == p.asLong()) {
                     throw new IllegalArgumentException("请选择另一个端点");
                  }
               }

               long[] next = Arrays.copyOf(selectedx, selectedx.length + 1);
               next[selectedx.length] = p.asLong();
               tool.putLongArray("Points", next);
               tool.putString("Dimension", dimensionx);
               if (next.length == this.arms) {
                  RoadNetwork.open(player, this.tagged(Interchanges.payload(level, player, next, null)));
               } else {
                  player.displayClientMessage(
                     Component.literal("已选 " + (char)(65 + selectedx.length) + "；接着选择 " + (char)(65 + next.length) + "（" + this.selection() + "）"), true
                  );
               }
            } else {
               this.edit(player);
               return InteractionResult.CONSUME;
            }
         } catch (IllegalArgumentException var22) {
            player.displayClientMessage(Component.literal(var22.getMessage()), false);
         }

         return InteractionResult.CONSUME;
      }
   }

   private void edit(ServerPlayer player) {
      RoadIndex.Built hit = RoadTool.pick(player);
      if (hit != null && hit.record.assembly() != null) {
         RoadData data = RoadData.get(player.serverLevel());
         UUID group = InterchangeJunctions.parent(data, hit.record.assembly());
         CompoundTag saved = data.interchanges.get(group);
         if (saved == null) {
            throw new IllegalArgumentException("立交描述已缺失，请删除残留路段后重建");
         } else {
            this.requireDescriptor(saved);
            RoadNetwork.open(player, this.tagged(Interchanges.payload(player.serverLevel(), player, saved.getLongArray("Points"), group)));
         }
      } else {
         throw new IllegalArgumentException("选择 " + this.selection() + "；或右键已有立交");
      }
   }

   public InteractionResultHolder<ItemStack> use(Level level, Player who, InteractionHand hand) {
      ItemStack stack = who.getItemInHand(hand);
      if (this.placeholder(who)) {
         return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
      } else {
         if (who.isShiftKeyDown()) {
            if (!level.isClientSide) {
               ItemNBT.edit(stack).remove("Points");
               CompoundTag t = new CompoundTag();
               t.putString("Kind", "interchangeReset");
               RoadNetwork.open((ServerPlayer)who, this.tagged(t));
            }
         } else if (level.isClientSide) {
            CompoundTag t = new CompoundTag();
            t.putString("Kind", "interchangeResume");
            RoadTool.openClient.accept(this.tagged(t));
         } else {
            try {
               ServerPlayer player = (ServerPlayer)who;
               RoadIndex.Built hit = RoadTool.pick(player);
               if (hit != null && hit.record.assembly() != null) {
                  this.edit(player);
               } else {
                  long[] points = ItemNBT.get(stack).getLongArray("Points");
                  if (points.length == this.arms && level.dimension().location().toString().equals(ItemNBT.get(stack).getString("Dimension"))) {
                     RoadNetwork.open(player, this.tagged(Interchanges.payload(player.serverLevel(), player, points, null)));
                  }
               }
            } catch (IllegalArgumentException var8) {
               who.displayClientMessage(Component.literal(var8.getMessage()), false);
            }
         }

         return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
      }
   }

   public void appendHoverText(ItemStack s, TooltipContext l, List<Component> lines, TooltipFlag f) {
      if (!this.available()) {
         lines.add(Component.literal("预留物品 · 暂未开放建造"));
         lines.add(Component.literal(this.arms == 5 ? "规划：A/B＋C/D＋E 端点" : "规划：A/B＋C/D＋E/F"));
      } else {
         lines.add(Component.literal("选择 " + this.selection() + " 后打开设置"));
         lines.add(Component.literal("Shift＋右键空气重新选点；右键已有立交可整体编辑"));
      }
   }
}
