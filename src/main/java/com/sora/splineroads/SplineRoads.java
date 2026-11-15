package com.sora.splineroads;

import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.CorridorPlanner;
import com.sora.splineroads.net.RoadNetwork;
import com.sora.splineroads.world.AttachedPointTool;
import com.sora.splineroads.world.ElevatedPointTool;
import com.sora.splineroads.world.GantryCreatorTool;
import com.sora.splineroads.world.GantryTool;
import com.sora.splineroads.world.InterchangeTool;
import com.sora.splineroads.world.JunctionTool;
import com.sora.splineroads.world.LaneLineTool;
import com.sora.splineroads.world.LanePointTool;
import com.sora.splineroads.world.LaneRampTool;
import com.sora.splineroads.world.NodeEntity;
import com.sora.splineroads.world.RailbedTool;
import com.sora.splineroads.world.RoadBlocks;
import com.sora.splineroads.world.RoadMarker;
import com.sora.splineroads.world.RoadMarkerBuilder;
import com.sora.splineroads.world.RoadFillEntity;
import com.sora.splineroads.world.RoadRemover;
import com.sora.splineroads.world.RoadSplitTool;
import com.sora.splineroads.world.RoadTool;
import com.sora.splineroads.world.RoundaboutTool;
import com.sora.splineroads.world.SmartTextTool;
import com.sora.splineroads.world.TollTool;
import com.sora.splineroads.world.TunnelAir;
import com.sora.splineroads.world.YJunctionTool;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityType.Builder;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod("splineroads")
public final class SplineRoads {
   public static final String ID = "splineroads";
   public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, "splineroads");
   public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, "splineroads");
   public static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "splineroads");
   public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "splineroads");
   public static final DeferredHolder<Block, ? extends Block> NODE = BLOCKS.register("road_node", () -> new RoadBlocks.Node());
   public static final DeferredHolder<Block, ? extends Block> ROAD_BLOCK = BLOCKS.register(
      "road_block", () -> new Block(Properties.ofFullCopy(Blocks.STONE).mapColor(MapColor.COLOR_GRAY).strength(1.8F, 6.0F))
   );
   public static final DeferredHolder<Block, ? extends Block> ROAD_SLAB = BLOCKS.register(
      "road_slab", () -> new SlabBlock(Properties.ofFullCopy(Blocks.STONE).mapColor(MapColor.COLOR_GRAY).strength(1.8F, 6.0F))
   );
   public static final DeferredHolder<Block, ? extends Block> TUNNEL_AIR = BLOCKS.register("tunnel_air", TunnelAir::new);
   public static final DeferredHolder<Block, ? extends Block> COLLIDER = BLOCKS.register("road_collision", RoadBlocks.Road::new);
   public static final DeferredHolder<Block, ? extends Block> FILLED_COLLIDER = BLOCKS.register("road_infill", RoadBlocks.FilledRoad::new);
   public static final DeferredHolder<Block, ? extends Block> MARKER = BLOCKS.register("road_marker", () -> new RoadMarker());
   public static final DeferredHolder<BlockEntityType<?>, ? extends BlockEntityType<?>> FILL_ENTITY = ENTITIES.register(
      "road_infill", () -> Builder.of(RoadFillEntity::new, new Block[]{(Block)FILLED_COLLIDER.get()}).build(null)
   );
   public static final DeferredHolder<BlockEntityType<?>, ? extends BlockEntityType<?>> NODE_ENTITY = ENTITIES.register(
      "road_node", () -> Builder.of(NodeEntity::new, new Block[]{(Block)NODE.get()}).build(null)
   );
   public static final DeferredHolder<Item, ? extends Item> NODE_ITEM = ITEMS.register(
      "road_node", () -> new BlockItem((Block)NODE.get(), new net.minecraft.world.item.Item.Properties())
   );
   public static final DeferredHolder<Item, ? extends Item> ROAD_BLOCK_ITEM = ITEMS.register(
      "road_block", () -> new BlockItem((Block)ROAD_BLOCK.get(), new net.minecraft.world.item.Item.Properties())
   );
   public static final DeferredHolder<Item, ? extends Item> ROAD_SLAB_ITEM = ITEMS.register(
      "road_slab", () -> new BlockItem((Block)ROAD_SLAB.get(), new net.minecraft.world.item.Item.Properties())
   );
   public static final DeferredHolder<Item, ? extends Item> CONNECTOR = ITEMS.register("road_connector", RoadTool::new);
   public static final DeferredHolder<Item, ? extends Item> RAILBED_CONNECTOR = ITEMS.register("railbed_connector", RailbedTool::new);
   public static final DeferredHolder<Item, ? extends Item> RAMP_CONNECTOR = ITEMS.register("ramp_connector", LaneRampTool::new);
   public static final DeferredHolder<Item, ? extends Item> JUNCTION = ITEMS.register("junction_connector", JunctionTool::new);
   public static final DeferredHolder<Item, ? extends Item> ROUNDABOUT = ITEMS.register("roundabout_generator", RoundaboutTool::new);
   public static final DeferredHolder<Item, ? extends Item> INTERCHANGE = ITEMS.register("interchange_connector", () -> new InterchangeTool());
   public static final DeferredHolder<Item, ? extends Item> INTERCHANGE_3 = ITEMS.register("interchange_connector_3", () -> new InterchangeTool(3));
   public static final DeferredHolder<Item, ? extends Item> INTERCHANGE_5 = ITEMS.register("interchange_connector_5", () -> new InterchangeTool(5));
   public static final DeferredHolder<Item, ? extends Item> INTERCHANGE_6 = ITEMS.register("interchange_connector_6", () -> new InterchangeTool(6));
   public static final DeferredHolder<Item, ? extends Item> FRONTAGE = ITEMS.register(
      "frontage_generator", () -> new InterchangeTool(2, CorridorPlanner.Kind.FRONTAGE)
   );
   public static final DeferredHolder<Item, ? extends Item> LAYERED = ITEMS.register(
      "layered_connector", () -> new InterchangeTool(4, CorridorPlanner.Kind.LAYERED)
   );
   public static final DeferredHolder<Item, ? extends Item> TOLL = ITEMS.register("toll_plaza_generator", TollTool::new);
   public static final DeferredHolder<Item, ? extends Item> TEXT_EDITOR = ITEMS.register("text_editor", SmartTextTool::new);
   public static final DeferredHolder<Item, ? extends Item> GANTRY_CREATOR = ITEMS.register("gantry_creator", GantryCreatorTool::new);
   public static final DeferredHolder<Item, ? extends Item> GANTRY_EDITOR = ITEMS.register("gantry_editor", GantryTool::new);
   public static final DeferredHolder<Item, ? extends Item> SPLITTER = ITEMS.register("road_splitter", RoadSplitTool::new);
   public static final DeferredHolder<Item, ? extends Item> ELEVATED_POINT = ITEMS.register("elevated_access_creator", ElevatedPointTool::new);
   public static final DeferredHolder<Item, ? extends Item> ATTACHED_POINT = ITEMS.register("endpoint_creator", AttachedPointTool::new);
   public static final DeferredHolder<Item, ? extends Item> LANE_POINT = ITEMS.register("lane_point_tool", LanePointTool::new);
   public static final DeferredHolder<Item, ? extends Item> LANE_LINES = ITEMS.register("lane_line_editor", LaneLineTool::new);
   public static final DeferredHolder<Item, ? extends Item> Y_JUNCTION = ITEMS.register("y_junction_editor", YJunctionTool::new);
   public static final DeferredHolder<Item, ? extends Item> REMOVER = ITEMS.register("road_remover", RoadRemover::new);
   public static final DeferredHolder<CreativeModeTab, ? extends CreativeModeTab> TAB = TABS.register(
      "roads",
      () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.splineroads"))
            .icon(() -> new ItemStack((ItemLike)CONNECTOR.get()))
            .displayItems((p, o) -> {
               o.accept((ItemLike)NODE_ITEM.get());
               o.accept((ItemLike)ROAD_BLOCK_ITEM.get());
               o.accept((ItemLike)ROAD_SLAB_ITEM.get());
               o.accept((ItemLike)ATTACHED_POINT.get());
               o.accept((ItemLike)ELEVATED_POINT.get());
               o.accept((ItemLike)SPLITTER.get());
               o.accept((ItemLike)LANE_POINT.get());
               o.accept((ItemLike)CONNECTOR.get());
               o.accept((ItemLike)RAILBED_CONNECTOR.get());
               o.accept((ItemLike)RAMP_CONNECTOR.get());
               o.accept((ItemLike)JUNCTION.get());
               o.accept((ItemLike)ROUNDABOUT.get());
               o.accept((ItemLike)INTERCHANGE_3.get());
               o.accept((ItemLike)INTERCHANGE.get());
               o.accept((ItemLike)INTERCHANGE_5.get());
               o.accept((ItemLike)INTERCHANGE_6.get());
               o.accept((ItemLike)FRONTAGE.get());
               o.accept((ItemLike)TOLL.get());
               o.accept((ItemLike)LAYERED.get());
               o.accept((ItemLike)GANTRY_CREATOR.get());
               o.accept((ItemLike)GANTRY_EDITOR.get());
               o.accept((ItemLike)TEXT_EDITOR.get());
               o.accept((ItemLike)LANE_LINES.get());
               o.accept((ItemLike)Y_JUNCTION.get());
               o.accept((ItemLike)REMOVER.get());
            })
            .build()
   );

   public SplineRoads(IEventBus bus) {
      BLOCKS.register(bus);
      ITEMS.register(bus);
      ENTITIES.register(bus);
      TABS.register(bus);
      ModLoadingContext.get().getActiveContainer().registerConfig(Type.CLIENT, RoadClientConfig.SPEC);
      bus.addListener(RoadNetwork::register);
      net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(RoadMarkerBuilder::tick);
   }
}
