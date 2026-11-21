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
 * <p><b>Cross-slope revision (comment 55513):</b> the first version of {@code deckY} raised a
 * column to the max natural height within an isotropic box of nearby ROAD columns -- correct for a
 * dip ALONG the road, but wrong for a road cut ACROSS a hillside (a cross/side slope): since the box
 * reaches further along the road than across it, it produced a ramped, still-stepped result instead
 * of a single shared, FLAT height across the road's own width at a given point along it. Reworked
 * to use {@link RoadSegment#projectOnto}: for a column's own nearest point on the centerline (its
 * "station"), sample natural heights ACROSS the road's own width (along the perpendicular to the
 * centerline's tangent there, bounded by this segment's own paved half-width) -- restricted to
 * samples that are still THIS segment (never a nearby unrelated road) and still PAVED/CENTERLINE
 * (never the shoulder, so an uphill shoulder never pulls the shared height up). Every column sharing
 * the same station therefore computes the exact same shared height, which is what makes the deck
 * flat ACROSS the road while still following the road's own profile ALONG it (longitudinal grade is
 * preserved: a different station gets its own, independently-computed shared height).
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

    /** Sampling step, in blocks, across the road's own width -- fine enough to not alias a diagonal road's own columns. */
    private static final double SAMPLE_STEP_BLOCKS = 0.5;

    /**
     * This column's own natural ground height, raised to the shared height for its own STATION
     * (nearest point along {@code segment}'s centerline) -- the local maximum natural ground height
     * among samples taken ACROSS the road's own paved width at that exact station, restricted to
     * {@code segment} itself and to PAVED/CENTERLINE samples only. Never lowered (no cuttings).
     * {@code grid}/{@code generator} queries here are pure natural-height lookups, valid for any
     * coordinate regardless of which cube is actually being generated right now, so this is
     * intentionally NOT confined to the current cube -- a deck computed this way is continuous
     * across a cube boundary by construction, not by coincidence. {@code segment} must be the SAME
     * segment {@code RoadNetwork.segmentCoveringColumn(grid, worldX, worldZ)} already found for this
     * column -- passed in rather than re-looked-up, since every caller already has it in hand.
     */
    public static int deckY(EarthGenerator generator, MercatorGrid grid, RoadSegment segment, int worldX, int worldZ) {
        return station(generator, grid, segment, worldX, worldZ).height();
    }

    /**
     * MINECRAFT-566 comment 55516: whether a station's own transverse sample actually found a real
     * cross-slope ({@code minSampledHeight != maxSampledHeight} among qualifying samples) -- NOT
     * just whether THIS column itself needed raising. The natural-high column of a cross-raised
     * station has {@code height() == naturalY} for ITSELF (nothing to raise), but still needs its
     * OWN surface repainted to the same plain flat cap its raised neighbors get; a station with no
     * cross-slope at all (the overwhelmingly common case) must leave {@code surface()}'s own
     * material/layering completely alone. See {@link #buildSupportAndDeck}'s own use of this.
     */
    public record Station(int height, boolean crossSlopeDetected) {}

    public static Station station(EarthGenerator generator, MercatorGrid grid, RoadSegment segment, int worldX, int worldZ) {
        return computeStation(segment, grid, worldX, worldZ, generator::surfaceY,
                (sx, sz) -> segment.equals(RoadNetwork.segmentCoveringColumn(grid, sx, sz)));
    }

    /**
     * The pure geometry/sampling core {@link #deckY}/{@link #station} delegate to, with the
     * natural-height lookup and the "is this sample point still THIS road" check both injected --
     * lets {@code RoadCrossSectionTest} exercise the exact sampling/flattening logic against a fully
     * synthetic, deterministic fixture (comment 55513's own acceptance case) without needing a real
     * {@code EarthGenerator} or a loaded {@code RoadNetwork} index. {@code sameRoad} should still
     * check the SAME segment (never a nearby unrelated road); production's own {@link #station} does,
     * via the real spatial index.
     */
    static Station computeStation(RoadSegment segment, MercatorGrid grid, int worldX, int worldZ,
            java.util.function.BiFunction<Integer, Integer, Integer> naturalHeight,
            java.util.function.BiPredicate<Integer, Integer> sameRoad) {
        int here = naturalHeight.apply(worldX, worldZ);
        int maxY = here, minY = here;
        RoadSegment.Projection projection = segment.projectOnto(grid, worldX, worldZ);
        double perpX = projection.perpendicularX(), perpZ = projection.perpendicularZ();
        double halfWidth = segment.pavedHalfWidthBlocks();
        for (double d = -halfWidth; d <= halfWidth + 1e-9; d += SAMPLE_STEP_BLOCKS) {
            int sx = (int) Math.floor(projection.nearestX() + perpX * d);
            int sz = (int) Math.floor(projection.nearestZ() + perpZ * d);
            if (!sameRoad.test(sx, sz)) continue; // never a nearby, unrelated road
            RoadSegment.CrossSection sampleZone = segment.crossSectionAt(grid, sx, sz);
            if (sampleZone != RoadSegment.CrossSection.PAVED && sampleZone != RoadSegment.CrossSection.CENTERLINE) continue; // never the shoulder
            int sy = naturalHeight.apply(sx, sz);
            if (sy > maxY) maxY = sy;
            if (sy < minY) minY = sy;
        }
        return new Station(maxY, minY != maxY);
    }

    /**
     * Test-only convenience wrapping {@link #computeStation} to just the height, for existing tests
     * written against the simpler shape -- production code should go through {@link #station}/
     * {@link #deckY} so the cross-slope flag is available where it is needed.
     */
    static int sharedHeight(RoadSegment segment, MercatorGrid grid, int worldX, int worldZ,
            java.util.function.BiFunction<Integer, Integer, Integer> naturalHeight,
            java.util.function.BiPredicate<Integer, Integer> sameRoad) {
        return computeStation(segment, grid, worldX, worldZ, naturalHeight, sameRoad).height();
    }

    /**
     * Fills stone-brick support strictly between this column's own natural ground and {@code deckY}
     * (exclusive), then paints {@code deckY} itself with a plain full pavement block for the given
     * zone -- deliberately NOT the layered slope-compat block {@code EarthGeneratorRoadMixin} paints
     * at natural height: that layering exists specifically to smooth a STEP between differently
     * -heighted neighbor columns, and a deck is flat by construction (every column in the same local
     * group shares the same {@code deckY}), so there is no step here to smooth and a plain full block
     * is the materially correct choice, not a simplification.
     *
     * <p>MINECRAFT-566 comment 55516 (independent compiled-build finding, NOT fixed this pass): the
     * natural-high column of a cross-raised station keeps whatever material/layer count
     * {@code surface()}'s own {@code RoadSurfaceLayering} had already painted there -- a VISIBLE
     * mismatch against its raised neighbors' plain full caps at the same Y (reported as
     * {@code [16,16,...,16,1]} layer counts across one station). A first attempt at fixing this
     * (forcing a repaint whenever {@code crossSlopeDetected} is true, even for a column that is not
     * itself being raised) was tried and REVERTED: it broke the existing, already-verified
     * longitudinal slope-layering continuity test
     * ({@code aGeneratedShortInclineClosesEveryAdjacentStepToASmallSeamNotAWholeBlockCliff} started
     * failing with a full-block gap at a real incline column) -- repainting a column to a flat cap
     * just because its OWN cross-section happens to be sloped elsewhere can introduce a NEW
     * longitudinal step against an adjacent, differently-raised station that {@code RoadSurfaceLayering}
     * was correctly smoothing before. Properly reconciling flat-ACROSS with smooth-ALONG needs a
     * real 2D blended height field, not an independent per-column decision; {@code crossSlopeDetected}
     * is kept on {@link Station} (and verified directly, see {@code RoadCrossSectionTest}) as a
     * building block for that future work, but is deliberately NOT used to change what gets painted
     * here yet. This gap is disclosed, not silently dropped -- see {@code docs/design.md}.
     *
     * <p>Confined to this one cube's own Y range -- a deck/support column spanning more than one
     * cube is built a cube's slice at a time, same per-cube pattern {@link RoadCanopyClearance}
     * already uses.
     */
    public static void buildSupportAndDeck(
            int cubeY, LevelChunkSection section, int lx, int lz, int naturalY, int deckY, boolean crossSlopeDetected, RoadSegment.CrossSection zone) {
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
                Station station = station(generator, grid, segment, worldX, worldZ);
                buildSupportAndDeck(cubeY, section, lx, lz, naturalY, station.height(), station.crossSlopeDetected(), zone);
            }
        }
    }
}
