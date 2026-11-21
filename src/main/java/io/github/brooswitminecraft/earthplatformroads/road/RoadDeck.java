package io.github.brooswitminecraft.earthplatformroads.road;

import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * MINECRAFT-566 comment 55485 (flat road deck over a short dip), redirected by comment 55503 away
 * from the "obvious" approach (overriding {@code EarthGenerator.ground()} -- declined, see
 * {@code docs/design.md}'s own "why a flat road deck is a materially bigger change than it looks")
 * toward "local deck/support placement using sampled natural heights and road-local streaming/save
 * extent."
 *
 * <p>Implemented as a pure ADDITIVE overlay at the exact same post-generation seam
 * {@link RoadCanopyClearance} already hooks (after {@code terrain()}/vegetation/structures, before
 * {@code dropStrayBlockEntities}) -- never the natural height input itself, so caves, structures,
 * biome sampling, vegetation eligibility and the water table all still see this column's REAL
 * natural ground height, completely unaffected. This only ever ADDS a pavement block and stone-brick
 * support strictly ABOVE a column's own natural ground, exactly the same way
 * {@link RoadCanopyClearance} only ever REMOVES blocks strictly above it -- two additive/subtractive
 * passes at the same seam, not a rewrite of generation itself.
 *
 * <p>Scope, deliberately narrow for this first pass (per the ticket's own "small playable draft, not
 * an all-or-nothing rewrite"): PAVED/CENTERLINE only (matching {@link RoadCanopyClearance}'s own
 * scope -- never the gravel shoulder, so a filled dip currently shows a drop to the shoulder's own
 * natural height at the paved edge; extending support under the shoulder too is a known, disclosed
 * follow-up, not attempted here). A deck is never built LOWER than a column's own natural ground (no
 * cuttings/embankments-into-hills -- matches this mod's existing, pre-existing "no cuttings" design
 * decision); only short local DIPS get filled.
 */
public final class RoadDeck {
    private RoadDeck() {}

    /**
     * How far along the mapped road to look, in blocks, for a higher nearby road column to flatten
     * this one up to. Deliberately small and conservative for a first pass -- large enough to smooth
     * a short dip (the concrete problem 55485 named), far too small to turn a road climbing a real
     * grade into one artificial, unified plateau. {@code deckY} is a pure function of coordinates and
     * the generator's own natural heights (never of which blocks have actually been painted), so it
     * gives the exact same answer regardless of cube generation order -- the same determinism
     * guarantee {@code EarthGeneratorRoadMixin}'s own slope layering already relies on.
     */
    private static final int WINDOW_RADIUS_BLOCKS = 6;

    /**
     * This column's own natural ground height, raised to the local maximum natural ground height
     * among nearby ROAD columns (never non-road terrain) within {@link #WINDOW_RADIUS_BLOCKS} --
     * never lowered. {@code grid}/{@code generator} queries here are pure natural-height lookups,
     * valid for any coordinate regardless of which cube is actually being generated right now, so
     * this is intentionally NOT confined to the current cube -- a deck computed this way is
     * continuous across a cube boundary by construction, not by coincidence.
     */
    public static int deckY(EarthGenerator generator, MercatorGrid grid, int worldX, int worldZ) {
        int maxY = generator.surfaceY(worldX, worldZ);
        for (int dx = -WINDOW_RADIUS_BLOCKS; dx <= WINDOW_RADIUS_BLOCKS; dx++) {
            for (int dz = -WINDOW_RADIUS_BLOCKS; dz <= WINDOW_RADIUS_BLOCKS; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = worldX + dx, nz = worldZ + dz;
                if (!RoadNetwork.coversColumn(grid, nx, nz)) continue;
                int ny = generator.surfaceY(nx, nz);
                if (ny > maxY) maxY = ny;
            }
        }
        return maxY;
    }

    /**
     * Fills stone-brick support strictly between this column's own natural ground and {@code deckY}
     * (exclusive), then paints {@code deckY} itself with a plain full pavement block for the given
     * zone -- deliberately NOT the layered slope-compat block {@code EarthGeneratorRoadMixin} paints
     * at natural height: that layering exists specifically to smooth a STEP between differently
     * -heighted neighbor columns, and a deck is flat by construction (every column in the same local
     * group shares the same {@code deckY}), so there is no step here to smooth and a plain full block
     * is the materially correct choice, not a simplification. No-op if {@code deckY} is not above
     * this column's own natural height (nothing to add). Confined to this one cube's own Y range --
     * a deck/support column spanning more than one cube is built a cube's slice at a time, same
     * per-cube pattern {@link RoadCanopyClearance} already uses.
     */
    public static void buildSupportAndDeck(
            int cubeY, LevelChunkSection section, int lx, int lz, int naturalY, int deckY, RoadSegment.CrossSection zone) {
        if (deckY <= naturalY) return;
        int baseY = cubeY << 4;
        int from = Math.max(0, naturalY + 1 - baseY);
        int to = Math.min(16, deckY - baseY); // support strictly below the deck
        BlockState support = Blocks.STONE_BRICKS.defaultBlockState();
        for (int localY = from; localY < to; localY++) {
            section.setBlockState(lx, localY, lz, support, false);
        }
        int deckLocalY = deckY - baseY;
        if (deckLocalY >= 0 && deckLocalY <= 15) {
            BlockState pavement = zone == RoadSegment.CrossSection.CENTERLINE
                    ? Blocks.YELLOW_TERRACOTTA.defaultBlockState()
                    : Blocks.POLISHED_BLACKSTONE.defaultBlockState();
            section.setBlockState(lx, deckLocalY, lz, pavement, false);
        }
    }

    /**
     * Scans this one cube's own 16x16 columns and builds support/deck for every PAVED/CENTERLINE
     * column {@link #deckY} raises above its own natural height -- the SAME scope
     * {@link RoadCanopyClearance#clear} uses, called BEFORE it by {@code SurfaceGeneratorRoadClearanceMixin}
     * so the deck/support this builds is standing before clearance computes its own floor (which
     * calls this class's own {@link #deckY} again -- a pure, deterministic function, so both callers
     * agree without needing to pass anything between them).
     */
    public static void buildAll(EarthGenerator generator, int cubeX, int cubeY, int cubeZ, LevelChunkSection section) {
        MercatorGrid grid = generator.grid();
        int baseX = cubeX << 4, baseZ = cubeZ << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldX = baseX + lx, worldZ = baseZ + lz;
                RoadSegment segment = RoadNetwork.segmentCoveringColumn(grid, worldX, worldZ);
                if (segment == null) continue;
                RoadSegment.CrossSection zone = segment.crossSectionAt(grid, worldX, worldZ);
                if (zone != RoadSegment.CrossSection.PAVED && zone != RoadSegment.CrossSection.CENTERLINE) continue;
                int naturalY = generator.surfaceY(worldX, worldZ);
                int waterY = generator.waterY(worldX, worldZ);
                if (waterY != io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.DRY && waterY >= naturalY) continue;
                int deckY = deckY(generator, grid, worldX, worldZ);
                buildSupportAndDeck(cubeY, section, lx, lz, naturalY, deckY, zone);
            }
        }
    }
}
