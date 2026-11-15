package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.net.RoadNetwork;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class SmartTextTool extends Item {
   public SmartTextTool() {
      super(new Properties().stacksTo(1));
   }

   public static SmartTextTool.Hit hit(RoadGeometry.V eye, RoadGeometry.V direction, Collection<SmartSigns.State> states, RoadIndex index) {
      SmartTextTool.Hit best = null;

      for (SmartSigns.State s : states) {
         RoadIndex.Built b = index.roads.get(s.key().road());
         if (b != null && s.target() != null && SmartSigns.valid(b.mesh, s) && !(SmartSigns.anchor(b.mesh, s).distance(eye) > 144.0)) {
            for (SmartSigns.Layout layout : SmartSigns.views(SmartSigns.layout(b.mesh, s), s.mode())) {
               double facing = direction.dot(layout.front());
               if (!(facing >= -1.0E-5)) {
                  double t = layout.origin().add(layout.front().mul(0.332)).sub(eye).dot(layout.front()) / facing;
                  if (!(t < 0.0) && !(t > 96.0) && (best == null || !(t >= best.distance()))) {
                     RoadGeometry.V at = eye.add(direction.mul(t)).sub(layout.origin());
                     double x = at.dot(layout.right());
                     double y = at.y();

                     for (SmartSigns.Board board : layout.boards()) {
                        if (!board.branch().equals("emergency") && Math.abs(x - board.x()) <= board.width() / 2.0 && y >= board.bottom() && y <= board.top()) {
                           SmartSigns.Field field = s.mode() == SmartSigns.Mode.LANE_INDICATOR
                              ? new SmartSigns.Field(
                                 board.branch() + "/status", board.x(), (board.bottom() + board.top()) / 2.0, board.width(), board.height(), 1
                              )
                              : layout.fields()
                                 .stream()
                                 .filter(f -> f.editable() && Math.abs(x - f.x()) <= f.width() / 2.0 && Math.abs(y - f.y()) <= f.height() / 2.0)
                                 .findFirst()
                                 .orElseGet(
                                    () -> layout.fields()
                                          .stream()
                                          .filter(SmartSigns.Field::editable)
                                          .min(Comparator.comparingDouble(f -> Math.hypot(x - f.x(), y - f.y())))
                                          .orElse(null)
                                 );
                           if (field != null) {
                              best = new SmartTextTool.Hit(s, field, t);
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return best;
   }

   private static void open(ServerPlayer p) {
      if (p.mayBuild() && !p.isSpectator()) {
         try {
            CompoundTag t = new CompoundTag();
            if (p.isShiftKeyDown()) {
               t.putString("Kind", "smartTextReset");
               RoadNetwork.open(p, t);
               return;
            }

            RoadData data = RoadData.get(p.serverLevel());
            Vec3 eye = p.getEyePosition();
            Vec3 dir = p.getLookAngle();
            LinkedHashSet<SmartSigns.State> candidates = new LinkedHashSet<>();
            HashSet<UUID> ids = new HashSet<>();
            BlockPos at = p.blockPosition();

            for (int x = (at.getX() >> 4) - 6; x <= (at.getX() >> 4) + 6; x++) {
               for (int z = (at.getZ() >> 4) - 6; z <= (at.getZ() >> 4) + 6; z++) {
                  ids.addAll(data.index.inChunk(ChunkPos.asLong(x, z)));
               }
            }

            for (UUID id : ids) {
               candidates.addAll(data.smartSigns.road(id));
            }

            candidates.addAll(data.smartSigns.nearSupports(new RoadGeometry.V(eye.x, eye.y, eye.z)));
            SmartTextTool.Hit hit = hit(new RoadGeometry.V(eye.x, eye.y, eye.z), new RoadGeometry.V(dir.x, dir.y, dir.z), candidates, data.index);
            if (hit == null) {
               t.putString("Kind", "smartTextResume");
               RoadNetwork.open(p, t);
               p.displayClientMessage(Component.literal("请对准智能标牌或车道指示牌正面右键"), true);
               return;
            }

            RoadIndex.Built b = data.index.roads.get(hit.state().key().road());
            RoadData.requireOwner(p, b.record.owner());
            t.putString("Kind", "smartText");
            t.put("Sign", SmartSignCodec.write(hit.state()));
            t.put("Road", b.record.header());
            t.putString("Field", hit.field().id());
            RoadNetwork.open(p, t);
         } catch (IllegalArgumentException var10) {
            p.displayClientMessage(Component.literal(var10.getMessage()), false);
         }
      }
   }

   public InteractionResult useOn(UseOnContext c) {
      if (c.getPlayer() instanceof ServerPlayer p) {
         open(p);
      }

      return InteractionResult.sidedSuccess(c.getLevel().isClientSide);
   }

   public InteractionResultHolder<ItemStack> use(Level l, Player p, InteractionHand h) {
      if (p instanceof ServerPlayer s) {
         open(s);
      }

      return InteractionResultHolder.sidedSuccess(p.getItemInHand(h), l.isClientSide);
   }

   public void appendHoverText(ItemStack s, TooltipContext l, List<Component> lines, TooltipFlag f) {
      lines.add(Component.literal("右键智能标牌填写文字；右键车道指示牌设置通行／禁行"));
      lines.add(Component.literal("Shift＋右键清除临时选择，不删除已保存文字"));
   }

   public static record Hit(SmartSigns.State state, SmartSigns.Field field, double distance) {
   }
}
