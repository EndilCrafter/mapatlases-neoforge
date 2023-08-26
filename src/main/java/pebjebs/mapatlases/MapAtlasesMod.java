package pebjebs.mapatlases;


import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.common.util.MutableHashedLinkedMap;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pebjebs.mapatlases.client.MapAtlasesClient;
import pebjebs.mapatlases.config.MapAtlasesClientConfig;
import pebjebs.mapatlases.config.MapAtlasesConfig;
import pebjebs.mapatlases.item.DummyFilledMap;
import pebjebs.mapatlases.item.MapAtlasItem;
import pebjebs.mapatlases.lifecycle.MapAtlasesServerEvents;
import pebjebs.mapatlases.networking.MapAtlasNetowrking;
import pebjebs.mapatlases.recipe.MapAtlasCreateRecipe;
import pebjebs.mapatlases.recipe.MapAtlasesAddRecipe;
import pebjebs.mapatlases.recipe.MapAtlasesCutExistingRecipe;
import pebjebs.mapatlases.screen.MapAtlasesAtlasOverviewContainerMenu;

import java.util.function.Supplier;


@Mod(MapAtlasesMod.MOD_ID)
public class MapAtlasesMod {

    public static final String MOD_ID = "map_atlases";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static final Supplier<MapAtlasItem> MAP_ATLAS;
    public static final Supplier<DummyFilledMap> DUMMY_FILLED_MAP;

    private static final DeferredRegister<RecipeSerializer<?>> RECIPES = DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(ForgeRegistries.MENU_TYPES, MOD_ID);
    private static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    public static final Supplier<RecipeSerializer<MapAtlasCreateRecipe>> MAP_ATLAS_CREATE_RECIPE;
    public static final Supplier<RecipeSerializer<MapAtlasesAddRecipe>> MAP_ATLAS_ADD_RECIPE;
    public static final Supplier<RecipeSerializer<MapAtlasesCutExistingRecipe>> MAP_ATLAS_CUT_RECIPE;

    public static final Supplier<MenuType<MapAtlasesAtlasOverviewContainerMenu>> ATLAS_OVERVIEW_HANDLER;

    public static final Supplier<SoundEvent> ATLAS_OPEN_SOUND_EVENT = regSound("atlas_open");
    public static final Supplier<SoundEvent> ATLAS_PAGE_TURN_SOUND_EVENT = regSound("atlas_page_turn");
    public static final Supplier<SoundEvent> ATLAS_CREATE_MAP_SOUND_EVENT = regSound("atlas_create_map");

    public static final TagKey<Item> STICKY_ITEMS = ItemTags.create(res("sticky_crafting_items"));

    public static final boolean CURIOS = ModList.get().isLoaded("curios");

    static {

        if (FMLEnvironment.dist == Dist.CLIENT) {
            MapAtlasesClient.init();
        }

        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        RECIPES.register(bus);
        MENU_TYPES.register(bus);
        ITEMS.register(bus);

        // Register config
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MapAtlasesConfig.spec);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, MapAtlasesClientConfig.spec);

        // Register special recipes
        MAP_ATLAS_CREATE_RECIPE = RECIPES.register("crafting_atlas",
                () -> new SimpleCraftingRecipeSerializer<>(MapAtlasCreateRecipe::new));
        MAP_ATLAS_ADD_RECIPE = RECIPES.register("adding_atlas",
                () -> new SimpleCraftingRecipeSerializer<>(MapAtlasesAddRecipe::new));
        MAP_ATLAS_CUT_RECIPE = RECIPES.register("cutting_atlas",
                () -> new SimpleCraftingRecipeSerializer<>(MapAtlasesCutExistingRecipe::new));

        // Register screen
        ATLAS_OVERVIEW_HANDLER = MENU_TYPES.register("atlas_overview",
                () -> IForgeMenuType.create(MapAtlasesAtlasOverviewContainerMenu::new)
        );

        // Register items
        MAP_ATLAS = ITEMS.register("atlas", () -> new MapAtlasItem(new Item.Properties().stacksTo(16)));
        DUMMY_FILLED_MAP = ITEMS.register("dummy_filled_map", () -> new DummyFilledMap(new Item.Properties()));

        // Register messages
        MapAtlasNetowrking.register();

        MinecraftForge.EVENT_BUS.register(MapAtlasesServerEvents.class);
        bus.addListener(MapAtlasesMod::addItemsToTabs);
    }

    public static void addItemsToTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) {
            MutableHashedLinkedMap<ItemStack, CreativeModeTab.TabVisibility> entries = event.getEntries();
            for (var v : entries) {
                var i = v.getKey();
                if (i.getItem() instanceof MapItem) {
                    entries.putAfter(i, MAP_ATLAS.get().getDefaultInstance(),
                            CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                }
            }
        }
    }

    private static Supplier<SoundEvent> regSound(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(res(name)));
    }

    public static ResourceLocation res(String name) {
        return new ResourceLocation(MOD_ID, name);
    }
}
