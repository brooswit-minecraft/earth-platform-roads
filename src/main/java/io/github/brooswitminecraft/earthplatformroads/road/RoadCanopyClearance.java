package io.github.brooswitminecraft.earthplatformroads.road;

import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
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
 * <p>Leaf detection uses {@code state.getBlock() instanceof LeavesBlock} rather than
 * {@code BlockTags.LEAVES}: the plain Java type check needs no datapack/tag registry to be bound
 * (confirmed live -- the tag read back empty in this mod's own {@code neoForge.unitTest} harness,
 * which does not run a real server's datapack reload) and still catches every vanilla leaf variant,
 * since {@code CherryLeavesBlock}/{@code MangroveLeavesBlock} both extend {@code LeavesBlock}
 * (confirmed by reading the real classes).
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
                int groundY = generator.surfaceY(worldX, worldZ);
                int waterY = generator.waterY(worldX, worldZ);
                if (waterY != SurfaceGenerator.DRY && waterY >= groundY) continue; // underwater: no road deck here either, matching EarthGeneratorRoadMixin's own skip
                int firstLocalY = Math.max(0, groundY + 1 - baseY); // strictly ABOVE the deck -- never the deck itself, never below it
                for (int localY = firstLocalY; localY <= 15; localY++) {
                    BlockState state = section.getBlockState(lx, localY, lz);
                    if (state.isAir()) continue;
                    if (state.getBlock() instanceof LeavesBlock) {
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
