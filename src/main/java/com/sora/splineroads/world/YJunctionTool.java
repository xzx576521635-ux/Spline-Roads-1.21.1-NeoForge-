package com.sora.splineroads.world;

import com.sora.splineroads.ItemNBT;
import com.sora.splineroads.core.RoadEndpointSections;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadTransitions;
import com.sora.splineroads.core.YJunctionPlanner;
import com.sora.splineroads.net.RoadNetwork;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class YJunctionTool extends Item {
   public YJunctionTool() {
      super(new Properties().stacksTo(1));
   }

   public InteractionResult useOn(UseOnContext c) {
      if (c.getLevel().isClientSide) {
         return InteractionResult.SUCCESS;
      } else {
         if (c.getPlayer() instanceof ServerPlayer p) {
            try {
               InteractionResult var19;
               try (RoadWorkChunks work = RoadWorkChunks.open(p.serverLevel())) {
                  RoadData d = RoadData.get(p.serverLevel());
                  BlockPos pos = RoadTool.endpointAtHit(c);
                  CompoundTag tag = ItemNBT.edit(c.getItemInHand());
                  if (!p.isShiftKeyDown()) {
                     if (p.serverLevel().getBlockEntity(pos) instanceof NodeEntity) {
                        RoadData.requireNode(p.serverLevel(), pos, p);
                        String dim = p.level().dimension().location().toString();
                        long[] old = dim.equals(tag.getString("Dimension")) ? tag.getLongArray("Points") : new long[0];
                        if (old.length >= 3) {
                           old = new long[0];
                        }

                        for (long k : old) {
                           if (k == pos.asLong()) {
                              throw new IllegalArgumentException("请选择不同的端点");
                           }
                        }

                        long[] next = Arrays.copyOf(old, old.length + 1);
                        next[old.length] = pos.asLong();
                        tag.putLongArray("Points", next);
                        tag.putString("Dimension", dim);
                        if (next.length == 3) {
                           RoadNetwork.open(p, payload(p.serverLevel(), p, next, null));
                        } else {
                           p.displayClientMessage(
                              Component.literal("已选 " + (char)(65 + old.length) + "；接着选择 " + (char)(65 + next.length) + "（A 双向，B 出向，C 入向）"), true
                           );
                        }

                        return InteractionResult.CONSUME;
                     } else {
                        RoadIndex.Built r = RoadTool.pick(p);
                        CompoundTag saved = r == null ? null : d.interchanges.get(r.record.assembly());
                        if (saved == null || !saved.getBoolean("YJunction")) {
                           throw new IllegalArgumentException("依次选 A/B/C 端点，或右键已有 Y 字路口");
                        }

                        RoadNetwork.open(p, payload(p.serverLevel(), p, saved.getLongArray("Points"), saved.getUUID("Id")));
                        return InteractionResult.CONSUME;
                     }
                  }

                  tag.remove("Points");
                  p.displayClientMessage(Component.literal("Y 字选点已清空"), true);
                  var19 = InteractionResult.CONSUME;
               }

               return var19;
            } catch (IllegalArgumentException var16) {
               p.displayClientMessage(Component.literal(var16.getMessage()), false);
            }
         }

         return InteractionResult.CONSUME;
      }
   }

   public InteractionResultHolder<ItemStack> use(Level l, Player p, InteractionHand hand) {
      if (p.isShiftKeyDown()) {
         ItemNBT.edit(p.getItemInHand(hand)).remove("Points");
         if (!l.isClientSide) {
            p.displayClientMessage(Component.literal("Y 字选点已清空"), true);
         }
      }

      return InteractionResultHolder.sidedSuccess(p.getItemInHand(hand), l.isClientSide);
   }

   static CompoundTag payload(ServerLevel l, ServerPlayer p, long[] points, UUID id) {
      if (points.length == 3 && Arrays.stream(points).distinct().count() == 3L) {
         RoadData d = RoadData.get(l);
         CompoundTag t = id == null ? new CompoundTag() : Interchanges.descriptor(d, id);
         if (id != null && p != null) {
            RoadData.requireOwner(p, t.getUUID("Owner"));
         }

         t.putString("Kind", "yJunction");
         t.putBoolean("YJunction", true);
         t.putLongArray("Points", points);
         if (!t.contains("Tension")) {
            t.putDouble("Tension", 0.4);
         }

         ListTag nodes = new ListTag();
         int signature = 1;

         for (int i = 0; i < 3; i++) {
            BlockPos pos = BlockPos.of(points[i]);
            NodeEntity entity = RoadData.requireNode(l, pos, p);
            RoadGeometry.Node node = entity.constructionNode();
            List<RoadIndex.Built> hosts = d.index
               .atNode(pos)
               .stream()
               .map(d.index.roads::get)
               .filter(r -> id == null || !id.equals(r.record.assembly()))
               .toList();
            if (hosts.size() > 1) {
               throw new IllegalArgumentException("所选端点已接多条道路，请选道路尽头");
            }

            RoadGeometry.Settings settings = t.contains("Profile" + i)
               ? RoadRecord.readSettings(t.getCompound("Profile" + i))
               : new RoadGeometry.Settings(
                  RoadGeometry.Mode.CURVE,
                  i == 0 ? RoadGeometry.Style.O4_YELLOW : RoadGeometry.Style.O2_ONE,
                  (i == 0 ? RoadGeometry.Style.O4_YELLOW : RoadGeometry.Style.O2_ONE).defaultWidth(),
                  1.0,
                  0.4,
                  90.0
               );
            if (!hosts.isEmpty()) {
               RoadIndex.Built host = hosts.get(0);
               if (p != null) {
                  RoadData.requireOwner(p, host.record.owner());
               }

               settings = RoadData.endpointSection(host, pos);
               if (host.record.assembly() == null) {
                  settings = settings.options(settings.options().sidewalk(host.record.settings().options().sidewalk()));
               }

               RoadGeometry.Mesh actual = host.record.caps(0).mesh();
               RoadGeometry.Sample end = host.record.a().equals(pos) ? actual.first() : actual.last();
               RoadGeometry.V outward = end.left().left().mul(host.record.a().equals(pos) ? 1.0 : -1.0);
               RoadGeometry.V direction = i == 1 ? outward.mul(-1.0) : outward;
               if (i == 0) {
                  direction = outward;
               }

               double grade = (host.record.a().equals(pos) ? host.record.start().grade() : host.record.end().grade())
                  * (double)(host.record.a().equals(pos) ? -1 : 1)
                  * (double)(i == 1 ? -1 : 1);
               RoadGeometry.V endpoint = end.center();
               node = new RoadGeometry.Node(endpoint, YJunctionPlanner.yaw(direction), grade);
               if (direction.left().dot(end.left()) < 0.0) {
                  settings = RoadEndpointSections.orient(settings, true);
               }

               if (i > 0 && (i == 1 && !host.record.a().equals(pos) || i == 2 && !host.record.b().equals(pos))) {
                  throw new IllegalArgumentException((i == 1 ? "B" : "C") + " 单行道方向不符：B 应从端点驶出，C 应驶入端点");
               }

               signature = 31 * signature + GantryTool.signature(host.record);
            } else {
               if (i > 0 && !t.contains("Profile" + i)) {
                  RoadGeometry.Settings main = RoadRecord.readSettings(t.getCompound("Profile0"));
                  RoadProfile.Catalog cp = RoadProfile.catalog(main);
                  RoadLanes.Counts count = new RoadLanes.Counts(i == 1 ? RoadLanes.counts(main).forward() : RoadLanes.counts(main).reverse(), 0);
                  RoadGeometry.Style style = RoadLanes.carrier(cp.type(), count, RoadProfile.Median.NONE);
                  RoadProfile.Options o = main.options().lanes(count).ends(RoadTransitions.Ends.NONE).laneLines(List.of());
                  settings = new RoadGeometry.Settings(
                        RoadGeometry.Mode.CURVE,
                        style,
                        RoadProfile.width(style, o, RoadProfile.layout(main, main.width()).laneWidth()),
                        main.thickness(),
                        0.4,
                        90.0
                     )
                     .structure(main.structure())
                     .options(o);
               }

               if (!entity.headingLocked) {
                  RoadGeometry.V av = RoadData.requireNode(l, BlockPos.of(points[0]), p).constructionNode().position();
                  RoadGeometry.V bv = RoadData.requireNode(l, BlockPos.of(points[1]), p).constructionNode().position();
                  RoadGeometry.V cv = RoadData.requireNode(l, BlockPos.of(points[2]), p).constructionNode().position();
                  RoadGeometry.V directionx = i == 0 ? bv.add(cv).mul(0.5).sub(av) : (i == 1 ? bv.sub(av) : av.sub(cv));
                  node = new RoadGeometry.Node(node.position(), YJunctionPlanner.yaw(directionx), 0.0);
               }
            }

            t.putBoolean("LockedProfile" + i, !hosts.isEmpty());
            nodes.add(RoadRecord.writeNode(node));
            t.put("Profile" + i, RoadRecord.writeSettings(settings));
            signature = 31 * signature + node.hashCode();
         }

         t.put("Nodes", nodes);
         t.putInt("Signature", signature);
         return t;
      } else {
         throw new IllegalArgumentException("需要 A/B/C 三个不同端点");
      }
   }

   public static String build(ServerLevel l, ServerPlayer p, CompoundTag command, ItemStack tool) {
      if (tool.getItem() instanceof YJunctionTool || command.hasUUID("Id") && tool.getItem() instanceof JunctionTool) {
         RoadData d = RoadData.get(l);
         UUID id = command.hasUUID("Id") ? command.getUUID("Id") : null;
         long[] points = command.getLongArray("Points");
         if (id != null
            || Arrays.equals(points, ItemNBT.get(tool).getLongArray("Points"))
               && l.dimension().location().toString().equals(ItemNBT.get(tool).getString("Dimension"))) {
            if (id != null) {
               CompoundTag saved = Interchanges.descriptor(d, id);
               if (!saved.getBoolean("YJunction") || !Arrays.equals(points, saved.getLongArray("Points"))) {
                  throw new IllegalArgumentException("Y 字路口已改变");
               }
            }

            if (!command.getBoolean("Delete")) {
               String var40;
               try (RoadWorkChunks work = RoadWorkChunks.open(l)) {
                  CompoundTag t = payload(l, p, points, id);
                  if (t.getInt("Signature") != command.getInt("Signature")) {
                     throw new IllegalArgumentException("端点或相接道路已改变，请重新选择");
                  }

                  for (int i = 0; i < 3; i++) {
                     if (!t.getBoolean("LockedProfile" + i) && command.contains("Profile" + i)) {
                        RoadGeometry.Settings requested = RoadRecord.readSettings(command.getCompound("Profile" + i));
                        requested.validate();
                        t.put("Profile" + i, RoadRecord.writeSettings(requested));
                     }
                  }

                  double tension = command.getDouble("Tension");
                  t.putDouble("Tension", tension);
                  ListTag n = t.getList("Nodes", 10);
                  YJunctionPlanner.Plan plan = YJunctionPlanner.plan(
                     RoadRecord.readNode(n.getCompound(0)),
                     RoadRecord.readNode(n.getCompound(1)),
                     RoadRecord.readNode(n.getCompound(2)),
                     RoadRecord.readSettings(t.getCompound("Profile0")),
                     RoadRecord.readSettings(t.getCompound("Profile1")),
                     RoadRecord.readSettings(t.getCompound("Profile2")),
                     tension
                  );
                  UUID group = id == null ? UUID.randomUUID() : id;
                  UUID owner = id == null ? (p == null ? new UUID(0L, 0L) : p.getUUID()) : t.getUUID("Owner");
                  t.putUUID("Id", group);
                  t.putUUID("Owner", owner);
                  BlockPos a = BlockPos.of(points[0]);
                  BlockPos b = BlockPos.of(points[1]);
                  BlockPos c = BlockPos.of(points[2]);
                  BlockPos throat = BlockPos.containing(plan.throat().position().x(), plan.throat().position().y(), plan.throat().position().z());
                  ArrayList<RoadIndex.Built> built = new ArrayList<>();
                  List<RoadGeometry.Mesh> meshes = List.of(plan.stem(), plan.outbound(), plan.inbound());
                  List<BlockPos> from = List.of(a, throat, c);
                  List<BlockPos> to = List.of(throat, b, throat);

                  for (int ix = 0; ix < 3; ix++) {
                     RoadGeometry.Mesh m = meshes.get(ix);
                     RoadGeometry.Sample first = m.first();
                     RoadGeometry.Sample last = m.last();
                     RoadRecord r = new RoadRecord(
                           UUID.nameUUIDFromBytes((group + ":" + ix).getBytes(StandardCharsets.UTF_8)),
                           owner,
                           from.get(ix),
                           to.get(ix),
                           new RoadGeometry.Node(
                              first.center(),
                              YJunctionPlanner.yaw(first.left().left().mul(-1.0)),
                              ix == 2 ? RoadRecord.readNode(n.getCompound(2)).grade() : plan.throat().grade()
                           ),
                           new RoadGeometry.Node(
                              last.center(),
                              YJunctionPlanner.yaw(last.left().left().mul(-1.0)),
                              ix == 1 ? RoadRecord.readNode(n.getCompound(1)).grade() : (ix == 2 ? -plan.throat().grade() : plan.throat().grade())
                           ),
                           m.settings(),
                           true,
                           4
                        )
                        .alignment(group, m);
                     built.add(new RoadIndex.Built(r));
                  }

                  Set<UUID> removed = new HashSet<>();
                  if (id != null) {
                     for (RoadIndex.Built r : d.index.roads.values()) {
                        if (id.equals(r.record.assembly())) {
                           removed.add(r.record.id());
                        }
                     }
                  }

                  if (l.getBlockEntity(throat) instanceof NodeEntity && !Set.of(a, b, c).contains(throat)) {
                     throw new IllegalArgumentException("Y 字分流处有其他端点，请调整选点");
                  }

                  Set<BlockPos> ports = Set.of(a, b, c);

                  for (RoadIndex.Built old : d.index.roads.values()) {
                     if (!removed.contains(old.record.id())) {
                        int mask = old.record.endCaps();
                        if (ports.contains(old.record.a())) {
                           mask &= -2;
                        }

                        if (ports.contains(old.record.b())) {
                           mask &= -3;
                        }

                        RoadGeometry.Mesh deck = mask == old.record.endCaps() ? old.mesh : old.record.caps(mask).mesh();

                        for (RoadIndex.Built proposed : built) {
                           if (RoadIndex.overlapXZ(proposed.mesh, deck, 1.0)) {
                              Interchanges.checkExternal(proposed.mesh, deck);
                           }
                        }
                     }
                  }

                  d.replaceAssembly(l, p, built, removed, Set.of(a, b, c));
                  d.interchanges.put(group, t);
                  d.setDirty();
                  ItemNBT.edit(tool).remove("Points");
                  var40 = "Y 字路口已更新：A→B、C→A";
               }

               return var40;
            } else if (id == null) {
               throw new IllegalArgumentException("尚未建造");
            } else {
               Interchanges.remove(l, p, id);
               return "Y 字路口已删除";
            }
         } else {
            throw new IllegalArgumentException("选点已失效，请重新选择 A/B/C");
         }
      } else {
         throw new IllegalArgumentException("请手持 Y 字路口编辑器");
      }
   }
}
