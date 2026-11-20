package io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes;

import io.github.brooswitminecraft.earthplatformroads.EarthPlatformRoadsMod;
import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import net.minecraft.world.item.BlockItem;
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
 */
public final class LayeredSlopesCompat {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EarthPlatformRoadsMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EarthPlatformRoadsMod.MODID);

    public static final DeferredBlock<LayeredBlock> LAYERED_POLISHED_BLACKSTONE = BLOCKS.registerBlock(
            "layered_polished_blackstone",
            LayeredBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.POLISHED_BLACKSTONE).noOcclusion());

    public static final DeferredItem<BlockItem> LAYERED_POLISHED_BLACKSTONE_ITEM =
            ITEMS.registerSimpleBlockItem(LAYERED_POLISHED_BLACKSTONE);

    private LayeredSlopesCompat() {}

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
