package io.github.brooswitminecraft.earthplatformroads.road;

import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * MINECRAFT-566 comment 55488 (John's live-playtest styling ask, refined by comment 55498): clear
 * every NON-LEAF block above the finished paved/centerline road deck, up to build height, so a
 * generated tree/structure does not leave a log or other obstruction sitting over the road --
 * preserving leaf canopy, never touching the shoulder, never touching anything below the deck or
 * off the mapped footprint, and never retrofitting an already-saved column.
 *
 * <p>Hooked by {@code SurfaceGeneratorRoadClearanceMixin} immediately before
 * {@code SurfaceGenerator}'s own {@code dropStrayBlockEntities} call -- "the actual final
 * generation seam after structures and before dropStrayBlockEntities" per comment 55498's own
 * explicit guidance -- so every tree/population pass that could put a log back over the road has
 * ALREADY run by the time this clears it, and clearing BEFORE {@code dropStrayBlockEntities} means
 * its own existing filter (which checks whether a block entity's block state is still valid)
 * correctly drops any stray block-entity tag for a position this clears to air, rather than this
 * class needing to duplicate that bookkeeping itself.
 *
 * <p>Leaf detection checks BOTH {@code BlockTags.LEAVES} and {@code instanceof LeavesBlock} (either
 * one counts): the tag alone read back empty in this mod's own {@code neoForge.unitTest} harness
 * (no real server datapack reload happens there), so {@code instanceof} is needed as a fallback for
 * that environment -- but the tag is still checked FIRST and is the one that correctly covers a
 * modded leaf block that only carries the tag without extending vanilla {@code LeavesBlock} (a real
 * gap the {@code instanceof}-only version shipped in commit 3ce7eb1 had, per John's review comment
 * 55510). {@code hasProperty(LeavesBlock.PERSISTENT)} already guards the one place this reads/writes
 * that specific vanilla property, so a tag-matched block without it is simply left as-is rather than
 * erroring.
 *
 * <p><b>Known limitation (comment 55510):</b> this classifies purely on {@link RoadNetwork}'s own
 * mapped geometry -- it does not check whether {@code EarthGeneratorRoadMixin} actually painted the
 * expected road material at this column (that would need re-deriving it the way
 * {@link RoadPrefetch#sectionLooksStale} does, which is currently disabled -- see that class's own
 * doc). On the rare column where the per-column surface cache is stale (the still-unresolved
 * residual race from comments 55496/55498/55505/55507), this can clear a column's canopy even though
 * the surface under it never actually got painted as road. This is a real, disclosed gap, not a fix
 * for that race -- do not read a clean canopy as proof the road surface underneath is correct.
 *
 * <p>MINECRAFT-566 comment 55485/55503 (flat road deck over a short dip): this loop's own floor is
 * now {@link RoadDeck#deckY}, not the column's raw natural height -- {@code SurfaceGeneratorRoadClearanceMixin}
 * calls {@link RoadDeck#buildSupportAndDeck} first for any column {@code deckY} raises, so by the
 * time this clears everything above that SAME floor, the deck/support it just built is correctly
 * left standing rather than being immediately cleared out from under itself. The underwater skip
 * below still uses the column's own NATURAL height/water, not the raised deck floor, deliberately --
 * whether a column is naturally underwater does not change because a nearby column pulled its deck
 * up.
 */
public final class RoadCanopyClearance {
    private RoadCanopyClearance() {}

    /**
     * Clips this ONE cube's own 16x16 columns -- "prefer clipping existing generation/decoration
     * locally as each cube is produced over loading or editing the full vertical stack" per 55488's
     * own wording. A tree/structure taller than one cube is clipped a cube at a time, each as its
     * own {@code generate()} call clears its own slice -- never a separate full-column pass.
     */
    public static void clear(EarthGenerator generator, int cubeX, int cubeY, int cubeZ, LevelChunkSection section) {
        MercatorGrid grid = generator.grid();
        int baseX = cubeX << 4, baseZ = cubeZ << 4, baseY = cubeY << 4;
        boolean changed = false;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldX = baseX + lx, worldZ = baseZ + lz;
                RoadSegment segment = RoadNetwork.segmentCoveringColumn(grid, worldX, worldZ);
                if (segment == null) continue;
                // "road/center-stripe" only, per 55488's own wording -- never the gravel shoulder,
                // and never a bridge/tunnel's excluded footprint (segmentCoveringColumn already
                // skips those; crossSectionAt's OFF_ROAD here would mean this exact column simply
                // isn't covered at this distance either).
                RoadSegment.CrossSection zone = segment.crossSectionAt(grid, worldX, worldZ);
                if (zone != RoadSegment.CrossSection.PAVED && zone != RoadSegment.CrossSection.CENTERLINE) continue;
                int naturalY = generator.surfaceY(worldX, worldZ);
                int waterY = generator.waterY(worldX, worldZ);
                if (waterY != SurfaceGenerator.DRY && waterY >= naturalY) continue; // underwater: no road deck here either, matching EarthGeneratorRoadMixin's own skip
                int groundY = RoadDeck.deckY(generator, grid, worldX, worldZ); // the raised-dip-fill floor, never below naturalY
                int firstLocalY = Math.max(0, groundY + 1 - baseY); // strictly ABOVE the deck -- never the deck itself, never below it
                for (int localY = firstLocalY; localY <= 15; localY++) {
                    BlockState state = section.getBlockState(lx, localY, lz);
                    if (state.isAir()) continue;
                    if (state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock) {
                        // Keep the canopy, but make sure removing its own log(s) elsewhere in this
                        // same pass never lets it decay naturally afterward -- "leaves meant to be
                        // kept do not simply decay because their logs were intentionally removed."
                        if (state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)) {
                            section.setBlockState(lx, localY, lz, state.setValue(LeavesBlock.PERSISTENT, true), false);
                            changed = true;
                        }
                        continue;
                    }
                    section.setBlockState(lx, localY, lz, Blocks.AIR.defaultBlockState(), false);
                    changed = true;
                }
            }
        }
        if (changed) section.recalcBlockCounts();
    }
}
