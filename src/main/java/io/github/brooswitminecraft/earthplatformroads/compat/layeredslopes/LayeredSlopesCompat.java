package io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes;

import io.github.brooswitminecraft.earthplatformroads.EarthPlatformRoadsMod;
import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * This mod's own {@link LayeredBlock} registration, kept out of Layered Slopes itself per the
 * ticket: "keep any necessary cross-mod integration in dedicated compatibility code, not a patch to
 * either upstream mod." Layered Slopes v0.2.0 only maps {@code dynamicterrain:layered_blackstone}
 * to raw {@link Blocks#BLACKSTONE} (not polished); to keep the road's existing polished appearance
 * on inclines, this registers a second, separate layered block over
 * {@link Blocks#POLISHED_BLACKSTONE} using {@link LayeredBlock}'s own public constructor directly --
 * the same registration pattern Layered Slopes uses for its own blocks, just under this mod's
 * registry namespace instead of {@code dynamicterrain}/{@code layeredslopes}, since this block has
 * no pre-split save/data-pack compatibility history to preserve.
 *
 * <p>MINECRAFT-566's road-styling ask (thin yellow terracotta centerline, gravel shoulders, both
 * needing the same slope-layering continuity as the paved blackstone) adds two more of these, over
 * {@link Blocks#YELLOW_TERRACOTTA} and {@link Blocks#GRAVEL} respectively, by the exact same
 * pattern. {@link LayeredBlock} extends plain {@code Block} (confirmed by reading the class), not
 * {@code net.minecraft.world.level.block.FallingBlock} -- so {@link #LAYERED_GRAVEL}, unlike vanilla
 * gravel, never has falling-block physics at all, regardless of what it rests on. Each has its own
 * copied-and-retextured block/item models and blockstate under
 * {@code assets/earthplatformroads/models}/{@code blockstates} (identical geometry per layer count
 * to the blackstone variant -- only the texture reference differs).
 *
 * <p>MINECRAFT-568 (per John's review comment 56315, "inventory and reuse the compatible Layered
 * Slopes palette first; add only missing materials needed") breaks from that pattern on purpose for
 * its two new road materials: {@code dynamicterrain:layered_dirt} and {@code dynamicterrain:layered_sand}
 * already exist upstream with no texture/property mismatch to work around (unlike blackstone's
 * polished-vs-raw gap above), so {@link #layeredDirtFromLayeredSlopes}/{@link
 * #layeredSandFromLayeredSlopes} resolve those already-registered blocks directly instead of
 * registering new ones -- no new block ids, no new asset files.
 */
public final class LayeredSlopesCompat {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EarthPlatformRoadsMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EarthPlatformRoadsMod.MODID);

    public static final DeferredBlock<LayeredBlock> LAYERED_POLISHED_BLACKSTONE = BLOCKS.registerBlock(
            "layered_polished_blackstone",
            LayeredBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.POLISHED_BLACKSTONE).noOcclusion());

    public static final DeferredBlock<LayeredBlock> LAYERED_YELLOW_TERRACOTTA = BLOCKS.registerBlock(
            "layered_yellow_terracotta",
            LayeredBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.YELLOW_TERRACOTTA).noOcclusion());

    public static final DeferredBlock<LayeredBlock> LAYERED_GRAVEL = BLOCKS.registerBlock(
            "layered_gravel",
            LayeredBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.GRAVEL).noOcclusion());

    public static final DeferredItem<BlockItem> LAYERED_POLISHED_BLACKSTONE_ITEM =
            ITEMS.registerSimpleBlockItem(LAYERED_POLISHED_BLACKSTONE);

    public static final DeferredItem<BlockItem> LAYERED_YELLOW_TERRACOTTA_ITEM =
            ITEMS.registerSimpleBlockItem(LAYERED_YELLOW_TERRACOTTA);

    public static final DeferredItem<BlockItem> LAYERED_GRAVEL_ITEM =
            ITEMS.registerSimpleBlockItem(LAYERED_GRAVEL);

    private static volatile Block layeredDirtFromLayeredSlopes;
    private static volatile Block layeredSandFromLayeredSlopes;

    private LayeredSlopesCompat() {}

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }

    /**
     * MINECRAFT-568's first-slice dirt/ground road surface fallback. John's review (comment 56315)
     * asked to "inventory and reuse the compatible Layered Slopes palette first; add only missing
     * materials needed for actual road recipes" -- unlike {@link #LAYERED_POLISHED_BLACKSTONE} (no
     * polished variant upstream) and the pre-existing {@link #LAYERED_YELLOW_TERRACOTTA}/{@link
     * #LAYERED_GRAVEL}, Layered Slopes v0.2.0 already ships {@code dynamicterrain:layered_dirt} and
     * {@code dynamicterrain:layered_sand} directly (same model/blockstate shape, confirmed by reading
     * its jar), so this ticket adds no new block registrations at all -- it resolves the already-
     * registered block by id instead. Lazily cached (the global block registry is not populated yet
     * at this class's own static-init time) with the same volatile-then-synchronized pattern {@code
     * RoadNetwork#tileSource} already uses for a different lazily-built singleton.
     */
    public static Block layeredDirtFromLayeredSlopes() {
        Block cached = layeredDirtFromLayeredSlopes;
        if (cached != null) return cached;
        synchronized (LayeredSlopesCompat.class) {
            if (layeredDirtFromLayeredSlopes == null) layeredDirtFromLayeredSlopes = resolveDynamicTerrainBlock("layered_dirt");
            return layeredDirtFromLayeredSlopes;
        }
    }

    /** MINECRAFT-568's sand road surface family; see {@link #layeredDirtFromLayeredSlopes} for why this reuses {@code dynamicterrain:layered_sand} rather than registering a new block. */
    public static Block layeredSandFromLayeredSlopes() {
        Block cached = layeredSandFromLayeredSlopes;
        if (cached != null) return cached;
        synchronized (LayeredSlopesCompat.class) {
            if (layeredSandFromLayeredSlopes == null) layeredSandFromLayeredSlopes = resolveDynamicTerrainBlock("layered_sand");
            return layeredSandFromLayeredSlopes;
        }
    }

    private static Block resolveDynamicTerrainBlock(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("dynamicterrain", path);
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == null || block == Blocks.AIR) {
            throw new IllegalStateException("Expected Layered Slopes to already register " + id + "; is the dependency present/up to date?");
        }
        return block;
    }
}
