package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generates real {@link EarthGenerator} cubes for a brand-new Earth world at Yosemite (no saved
 * state, no hand placement) and reads back actual block states, the way
 * {@code DataRulesBench}/{@code CalibrateBench} in earth-platform's own suite do: a plain JUnit
 * test constructing the real generator directly, run inside the modded test JVM
 * ({@code neoForge.unitTest}) so {@link EarthGeneratorRoadMixin} is actually applied.
 *
 * <p>Falsifiable claim under test: a column on the pinned Southside Drive centerline generates a
 * road-surface block ({@link #isRoadSurface}) at its surface in a cube this test never touched by
 * hand, and a column 40 blocks off that centerline -- same cube column, same generator, same seed
 * -- does not. If the mixin were not applied, or the road geometry were wrong, both assertions
 * below would see the same (non-blackstone) surface block, since {@code EarthGenerator} on its own
 * has no concept of a road.
 *
 * <p>This fixture's real DEM-derived slope at the pinned centerline was not re-measured after the
 * MINECRAFT-566 layering change landed (no build/playtest was run); {@link #isRoadSurface} accepts
 * either the plain full block (flat, slope == 0) or the layered compat block at any layer count, so
 * these assertions stay valid either way without asserting which case actually occurs here.
 */
class RoadGenerationTest {
    private static EarthGenerator generator;
    /** A point on the pinned centerline (data/earthplatformroads/roads/regions/yosemite_valley.json), -119.6367003,37.7206145. */
    private static final double ON_ROAD_LAT = 37.7206145, ON_ROAD_LON = -119.6367003;

    /**
     * Any of this mod's three road-surface materials (MINECRAFT-566 comment 55484's styling ask:
     * paved blackstone, the yellow-terracotta centerline, or the gravel shoulder), full-block or
     * MINECRAFT-566's layered compat block over the same base texture. The tests below check
     * CONTINUITY/COVERAGE generically (a road column vs. natural terrain), not which specific zone
     * applies -- {@code RoadCrossSectionTest} and {@code RoadGenerationTest}'s own width/zone tests
     * cover that distinction directly.
     */
    private static boolean isRoadSurface(BlockState state) {
        return state.is(Blocks.POLISHED_BLACKSTONE) || state.is(LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get())
                || state.is(Blocks.YELLOW_TERRACOTTA) || state.is(LayeredSlopesCompat.LAYERED_YELLOW_TERRACOTTA.get())
                || state.is(Blocks.GRAVEL) || state.is(LayeredSlopesCompat.LAYERED_GRAVEL.get());
    }

    @BeforeAll
    static void bootAndGenerate(@TempDir Path cache) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements()
                .forEach(holder -> biomes.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        for (String name : List.of("glacial_lake")) {
            try (var in = EarthGenerator.class.getResourceAsStream("/data/earthplatform/worldgen/biome/" + name + ".json")) {
                var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in));
                Biome biome = Biome.DIRECT_CODEC.parse(
                        net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, lookup), json).getOrThrow();
                biomes.register(
                        net.minecraft.resources.ResourceKey.create(Registries.BIOME,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("earthplatform", name)),
                        biome, RegistrationInfo.BUILT_IN);
            }
        }
        // Real elevation data (AWS Terrarium DEM), cached locally per run: the same data source
        // EarthGenerator always reads ground height from, fetched here only because this test
        // builds a real generator -- the mod's own road overlay never makes a network request.
        TerrariumTileSource tiles = new TerrariumTileSource(
                TerrariumTileSource.AWS_TERRAIN_TILES, cache.resolve(TerrariumTileSource.DATASET_ID), true, 512, 6, Duration.ofSeconds(20));
        EarthSettings settings = EarthSettings.at(EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE);
        generator = new EarthGenerator(4747L, biomes, settings, tiles, null, null, null);
    }

    private static net.minecraft.world.level.block.state.BlockState surfaceBlockAt(double lat, double lon) {
        int[] block = generator.block(lat, lon);
        return surfaceBlockAtXZ(block[0], block[1]);
    }

    private static net.minecraft.world.level.block.state.BlockState surfaceBlockAtXZ(int x, int z) {
        int ground = generator.surfaceY(x, z);
        int cubeX = x >> 4, cubeY = ground >> 4, cubeZ = z >> 4;
        LevelChunkSection section = generator.generate(cubeX, cubeY, cubeZ);
        return section.getBlockState(x & 15, ground & 15, z & 15);
    }

    @Test
    void columnOnThePinnedCenterlineGeneratesBlackstone() {
        var state = surfaceBlockAt(ON_ROAD_LAT, ON_ROAD_LON);
        assertTrue(isRoadSurface(state), "Expected the pinned Southside Drive centerline column to generate as road surface, got " + state);
    }

    @Test
    void aColumnFortyBlocksOffTheCenterlineIsUnaffected() {
        int[] onRoad = generator.block(ON_ROAD_LAT, ON_ROAD_LON);
        var grid = generator.grid();
        var offRoadPoint = grid.geographic(onRoad[0] + 40, onRoad[1]);
        var state = surfaceBlockAt(offRoadPoint.latitude(), offRoadPoint.longitude());
        assertTrue(!isRoadSurface(state),
                "A column 40 blocks off the pinned centerline must keep its natural surface, got " + state);
    }

    @Test
    void theRoadColumnIsAboveVanillaBuildHeight() {
        // Vanilla's overworld build limit is Y 320. Yosemite Valley's floor sits at real elevation
        // (one block per metre at this latitude, per EarthSettings), which is already well above
        // that -- this asserts the road column actually exercises that regime rather than relying
        // on it being true by accident.
        int[] onRoad = generator.block(ON_ROAD_LAT, ON_ROAD_LON);
        int ground = generator.surfaceY(onRoad[0], onRoad[1]);
        assertTrue(ground > 320, "Expected the road column's ground height to be above vanilla's Y 320 build limit, got " + ground);
        var state = surfaceBlockAt(ON_ROAD_LAT, ON_ROAD_LON);
        assertTrue(isRoadSurface(state), "The road column must still generate as road surface above vanilla's build height, got " + state);
    }

    /**
     * Regression coverage for MINECRAFT-566's whole review back-and-forth, not just the single
     * boundary column: these are real, DEM-generated (not synthetic) columns along one row of the
     * pinned Southside Drive band, spanning a full short incline (a step down, a flat low plateau,
     * a step back up), a separate isolated single-column dip, and a real cube-X boundary. See
     * {@link RoadSurfaceLayering}'s class doc for why a plain immediate-neighbor comparison was not
     * enough (the column just past a step jumped straight back to a full block, per John's review
     * finding) and why this is now a distance-decayed approach instead.
     */
    private static final int MEASURED_ROW_X0 = -10534021, MEASURED_ROW_Z = -3591018;
    private static final int MEASURED_ROW_MIN_DX = -8, MEASURED_ROW_MAX_DX = 9;
    /** The specific extra high-side column John's review asked to add (X=-10534019, i.e. dx=2 here). */
    private static final int EXTRA_HIGH_SIDE_DX = 2;
    /** Real cube-X boundary within this row: dx=4 (cube N) and dx=5 (cube N+1) are the last/first columns of adjacent cubes. */
    private static final int CUBE_BOUNDARY_LOW_DX = 4, CUBE_BOUNDARY_HIGH_DX = 5;

    @Test
    void aGeneratedShortInclineClosesEveryAdjacentStepToASmallSeamNotAWholeBlockCliff() {
        int span = MEASURED_ROW_MAX_DX - MEASURED_ROW_MIN_DX + 1;
        int[] ground = new int[span];
        double[] top = new double[span];
        boolean[] covered = new boolean[span];
        var grid = generator.grid();

        for (int i = 0; i < span; i++) {
            int dx = MEASURED_ROW_MIN_DX + i;
            int x = MEASURED_ROW_X0 + dx;
            covered[i] = RoadNetwork.coversColumn(grid, x, MEASURED_ROW_Z);
            if (!covered[i]) continue;
            ground[i] = generator.surfaceY(x, MEASURED_ROW_Z);
            var state = surfaceBlockAtXZ(x, MEASURED_ROW_Z);
            assertTrue(isRoadSurface(state), "Covered column at dx=" + dx + " must be road surface, got " + state);
            top[i] = ground[i] + absoluteCollisionTopFraction(state);
            assertSolidSupportUnder(x, MEASURED_ROW_Z, ground[i]);
        }

        // Confirm this row actually contains the real features this test exists to exercise, so a
        // silent DEM change can't make the loop below vacuously pass over an all-flat row.
        boolean sawAnyStep = false;
        double maxAdjacentGap = 0;
        int maxGapAtDx = Integer.MIN_VALUE;
        for (int i = 0; i + 1 < span; i++) {
            if (!covered[i] || !covered[i + 1]) continue;
            if (ground[i] != ground[i + 1]) sawAnyStep = true;
            double gap = Math.abs(top[i + 1] - top[i]);
            if (gap > maxAdjacentGap) {
                maxAdjacentGap = gap;
                maxGapAtDx = MEASURED_ROW_MIN_DX + i;
            }
        }
        assertTrue(sawAnyStep, "Expected at least one real ground-height step in this measured row; "
                + "if the real DEM changed, re-measure a replacement row rather than adjusting this test to fit");

        // The actual regression: before the distance-decay fix, the column immediately past a
        // step's single adjusted column jumped back to a full block, leaving close to a full
        // 1-block gap one column further out than the original fix closed. This bounds the WORST
        // adjacent gap across the whole measured incline, not just the one pair originally fixed.
        assertTrue(maxAdjacentGap < 0.5, "Expected every adjacent pair's collision-top gap across this real incline to stay "
                + "well under a full block; worst was " + maxAdjacentGap + " at dx=" + maxGapAtDx);

        // The specific extra high-side column asked for in review (X=-10534019): must still read as
        // road surface and must not itself reintroduce a near-full-block gap to either neighbor.
        int extraIndex = EXTRA_HIGH_SIDE_DX - MEASURED_ROW_MIN_DX;
        assertTrue(covered[extraIndex], "Expected the requested extra high-side column to be covered road surface");
        if (extraIndex > 0 && covered[extraIndex - 1]) {
            assertTrue(Math.abs(top[extraIndex] - top[extraIndex - 1]) < 0.5, "Extra high-side column's gap to its lower-dx neighbor must stay well under a full block");
        }
        if (extraIndex + 1 < span && covered[extraIndex + 1]) {
            assertTrue(Math.abs(top[extraIndex + 1] - top[extraIndex]) < 0.5, "Extra high-side column's gap to its higher-dx neighbor must stay well under a full block");
        }

        // The real cube-X boundary: confirm it actually is one (sanity), then that it is continuous.
        int boundaryX = MEASURED_ROW_X0 + CUBE_BOUNDARY_LOW_DX;
        assertEquals(boundaryX >> 4, ((boundaryX + 1) >> 4) - 1, "Sanity: the chosen boundary columns must actually be in different cubes");
        int lowIdx = CUBE_BOUNDARY_LOW_DX - MEASURED_ROW_MIN_DX, highIdx = CUBE_BOUNDARY_HIGH_DX - MEASURED_ROW_MIN_DX;
        assertTrue(covered[lowIdx] && covered[highIdx], "Expected both sides of the real cube boundary to be covered road surface");
        assertTrue(Math.abs(top[highIdx] - top[lowIdx]) < 0.5, "Collision top across the real cube boundary must stay continuous, not jump by close to a full block");
    }

    /** Absolute height, in blocks above {@code ground}, that this state's collision shape reaches -- 1.0 for a full block. */
    private static double absoluteCollisionTopFraction(BlockState state) {
        return state.getCollisionShape(null, null).max(net.minecraft.core.Direction.Axis.Y);
    }

    private static void assertSolidSupportUnder(int x, int z, int ground) {
        int cubeX = x >> 4, cubeY = ground >> 4, cubeZ = z >> 4;
        LevelChunkSection section = generator.generate(cubeX, cubeY, cubeZ);
        var under = section.getBlockState(x & 15, (ground - 1) & 15, z & 15);
        assertTrue(!under.isAir(), "Expected a solid block directly under the road column at (" + x + "," + z + "), got " + under);
    }

    @Test
    void theRoadIsContinuousAcrossACubeBoundary() {
        // The pinned ~190m centerline necessarily crosses multiple 16-block cube columns. Find two
        // adjacent in-bounds columns that straddle a cubeX boundary (x & 15 == 15 then x+1 & 15 == 0,
        // i.e. the last column of one cube immediately followed by the first column of the next) and
        // confirm both sides of that seam are road surface -- no gap or misalignment at the boundary.
        int[] onRoad = generator.block(ON_ROAD_LAT, ON_ROAD_LON);
        var grid = generator.grid();
        int x = onRoad[0], z = onRoad[1];
        int boundaryX = -1;
        for (int dx = -100; dx <= 100; dx++) {
            int cx = x + dx;
            if ((cx & 15) != 15) continue;
            if (!RoadNetwork.coversColumn(grid, cx, z) || !RoadNetwork.coversColumn(grid, cx + 1, z)) continue;
            boundaryX = cx;
            break;
        }
        assertTrue(boundaryX != -1, "Expected the pinned centerline to cross at least one cubeX boundary while still covered on both sides");
        assertEquals(boundaryX >> 4, (boundaryX + 1 >> 4) - 1, "Sanity: boundaryX and boundaryX+1 must actually be in different cubes");
        var last = grid.geographic(boundaryX, z);
        var first = grid.geographic(boundaryX + 1, z);
        var lastState = surfaceBlockAt(last.latitude(), last.longitude());
        var firstState = surfaceBlockAt(first.latitude(), first.longitude());
        assertTrue(isRoadSurface(lastState), "Last column of one cube at the boundary must be road surface, got " + lastState);
        assertTrue(isRoadSurface(firstState), "First column of the next cube at the boundary must be road surface, with no gap, got " + firstState);
    }

    @Test
    void generationIsDeterministicRegardlessOfRepeatOrNeighborGenerationOrder() {
        int[] onRoad = generator.block(ON_ROAD_LAT, ON_ROAD_LON);
        int x = onRoad[0], z = onRoad[1];
        int ground = generator.surfaceY(x, z);
        int cubeX = x >> 4, cubeY = ground >> 4, cubeZ = z >> 4;

        // Regenerating the same cube must produce the same block, both through the cache and
        // (since generate() is also called directly by far-LOD/pregen paths) independent of whether
        // it was already generated.
        var first = generator.generate(cubeX, cubeY, cubeZ).getBlockState(x & 15, ground & 15, z & 15);
        var second = generator.generate(cubeX, cubeY, cubeZ).getBlockState(x & 15, ground & 15, z & 15);
        assertEquals(first, second, "Regenerating the same cube must be deterministic");
        assertTrue(isRoadSurface(first), "Expected the regenerated column to be road surface, got " + first);

        // Generating a neighbouring cube first (as normal generation visiting cubes in a different
        // order, or pregeneration, would) must not change the result for the road's own cube: the
        // per-column cache (SurfaceGenerator.column) is keyed by cube column, not visit order.
        generator.generate(cubeX + 1, cubeY, cubeZ);
        generator.generate(cubeX, cubeY, cubeZ - 1);
        var afterNeighbors = generator.generate(cubeX, cubeY, cubeZ).getBlockState(x & 15, ground & 15, z & 15);
        assertEquals(first, afterNeighbors, "Generating neighbouring cubes first must not change this cube's road surface");
    }

    @Test
    void theRoadDoesNotWidenPastItsHalfWidth() {
        int[] onRoad = generator.block(ON_ROAD_LAT, ON_ROAD_LON);
        var grid = generator.grid();
        // Walk straight north from the centerline point until RoadNetwork itself (the same
        // geometry the mixin queries) says the column is just past the 3-block half-width, so this
        // probes the actual perpendicular edge regardless of which way the road happens to bear --
        // a fixed east/west offset is not necessarily perpendicular to a diagonal road.
        int x = onRoad[0], z = onRoad[1];
        while (RoadNetwork.coversColumn(grid, x, z)) z--;
        z -= 1; // one block further, past the edge rather than exactly on it
        var justOutside = grid.geographic(x, z);
        var state = surfaceBlockAt(justOutside.latitude(), justOutside.longitude());
        assertTrue(!isRoadSurface(state),
                "A column just past the half-width edge (" + x + "," + z + ") must not be road surface, got " + state);
    }

    /**
     * MINECRAFT-566 comment 55484's styling ask, verified on a NON-private fixture (the bundled
     * Yosemite Southside Drive extract, never John's own location): real generated blocks, not
     * just the geometry check {@code RoadCrossSectionTest} already covers, actually go
     * yellow-terracotta (centerline) -&gt; polished blackstone (paved) -&gt; gravel (shoulder) -&gt;
     * natural terrain, moving away from the pinned centerline -- a distinct palette at each zone,
     * never off-road, exactly the three real materials the mixin paints.
     */
    @Test
    void theRealGeneratedCrossSectionGoesYellowThenBlackstoneThenGravelThenNatural() {
        int[] onRoad = generator.block(ON_ROAD_LAT, ON_ROAD_LON);
        var grid = generator.grid();
        var centerState = surfaceBlockAtXZ(onRoad[0], onRoad[1]);
        assertTrue(centerState.is(net.minecraft.world.level.block.Blocks.YELLOW_TERRACOTTA)
                        || centerState.is(LayeredSlopesCompat.LAYERED_YELLOW_TERRACOTTA.get()),
                "Expected the pinned centerline column to generate the real yellow-terracotta centerline material, got " + centerState);

        boolean sawBlackstone = false, sawGravel = false, sawNatural = false;
        int x = onRoad[0], z = onRoad[1];
        for (int dz = 0; dz <= 30; dz++) {
            int cz = z - dz;
            var segment = RoadNetwork.segmentCoveringColumn(grid, x, cz);
            var state = surfaceBlockAtXZ(x, cz);
            if (segment == null) {
                sawNatural = true;
                break;
            }
            var zone = segment.crossSectionAt(grid, x, cz);
            if (zone == RoadSegment.CrossSection.PAVED) {
                assertTrue(state.is(net.minecraft.world.level.block.Blocks.POLISHED_BLACKSTONE)
                                || state.is(LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get()),
                        "Expected a PAVED column to generate real blackstone, got " + state);
                sawBlackstone = true;
            } else if (zone == RoadSegment.CrossSection.SHOULDER) {
                assertTrue(state.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                                || state.is(LayeredSlopesCompat.LAYERED_GRAVEL.get()),
                        "Expected a SHOULDER column to generate real gravel, got " + state);
                sawGravel = true;
            }
        }
        assertTrue(sawBlackstone, "Expected to actually generate a real blackstone PAVED column moving away from the centerline");
        assertTrue(sawGravel, "Expected to actually generate a real gravel SHOULDER column moving away from the centerline");
        assertTrue(sawNatural, "Expected to eventually leave the road and generate natural terrain");
    }

    /**
     * MINECRAFT-566 comment 55488 (leaf-preserving road-column clearance): calls
     * {@link RoadCanopyClearance#clear} directly (this test covers the CLEARANCE LOGIC against a
     * real generator and real pinned road geometry, not the mixin's own wiring -- nothing short of
     * a real server's own tree/structure decoration actually landing a block over this exact pinned
     * column could exercise the mixin's own {@code @Redirect} end to end, which is not reproducible
     * from this harness) by hand-placing a log and a non-persistent leaf directly above the pinned
     * centerline's own ground height within a REAL generated cube. A log over the road must come
     * back as air; a leaf over the road must stay a leaf but become persistent (so it does not decay
     * once a removed log stops supporting it); the same two placements one column off-road must be
     * left completely untouched, proving the clearance is scoped to the road's own paved/centerline
     * footprint, not applied blindly across the cube.
     *
     * <p>MINECRAFT-566 55485/55503: placements are relative to {@link RoadDeck#deckY}, not the raw
     * natural height -- this pinned column's own deck floor can sit above its natural ground if a
     * nearby road column is higher, and the clearance pass itself now uses that same raised floor.
     */
    @Test
    void canopyClearanceRemovesNonLeafBlocksOverTheRoadButPreservesLeavesAsPersistent() {
        int[] onRoad = generator.block(ON_ROAD_LAT, ON_ROAD_LON);
        int x = onRoad[0], z = onRoad[1];
        // MINECRAFT-566 55485/55503: RoadCanopyClearance's own floor is RoadDeck.deckY, not the raw
        // natural height, now that a nearby higher road column can raise this column's own deck --
        // compute the SAME floor the clearance pass itself will use, rather than assuming it equals
        // natural ground height.
        int floor = RoadDeck.deckY(generator, generator.grid(), x, z);
        int cubeX = x >> 4, cubeY = floor >> 4, cubeZ = z >> 4;
        int localFloorY = floor & 15;
        assertTrue(localFloorY <= 13, "Expected the pinned centerline column to leave room for two blocks above its clearance floor "
                + "within its own cube; if the real DEM changed, re-pin a column with more headroom rather than adjusting this test to fit. Got localFloorY=" + localFloorY);

        int localX = x & 15, localZ = z & 15;
        int offRoadLocalZ = (localZ + 8) & 15; // a different column within the SAME cube, confirmed off-road below
        int offRoadZ = (cubeZ << 4) + offRoadLocalZ;
        assertTrue(!RoadNetwork.coversColumn(generator.grid(), x, offRoadZ),
                "Expected the chosen comparison column to actually be off-road; if the real road geometry changed, re-pin a different offset");

        LevelChunkSection section = generator.generate(cubeX, cubeY, cubeZ);
        var logState = net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState();
        var leafState = net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false);
        section.setBlockState(localX, localFloorY + 1, localZ, logState, false);
        section.setBlockState(localX, localFloorY + 2, localZ, leafState, false);
        section.setBlockState(localX, localFloorY + 1, offRoadLocalZ, logState, false);
        section.setBlockState(localX, localFloorY + 2, offRoadLocalZ, leafState, false);

        RoadCanopyClearance.clear(generator, cubeX, cubeY, cubeZ, section);

        var clearedLog = section.getBlockState(localX, localFloorY + 1, localZ);
        assertTrue(clearedLog.isAir(), "Expected a log directly over the road column to be cleared to air, got " + clearedLog);
        var clearedLeaf = section.getBlockState(localX, localFloorY + 2, localZ);
        assertTrue(clearedLeaf.is(net.minecraft.world.level.block.Blocks.OAK_LEAVES), "Expected leaves over the road column to be preserved, got " + clearedLeaf);
        assertTrue(clearedLeaf.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT), "Expected leaves preserved over the road to be made persistent so they do not decay");

        var untouchedLog = section.getBlockState(localX, localFloorY + 1, offRoadLocalZ);
        assertTrue(untouchedLog.is(net.minecraft.world.level.block.Blocks.OAK_LOG), "Expected a log off the road to be left untouched, got " + untouchedLog);
        var untouchedLeaf = section.getBlockState(localX, localFloorY + 2, offRoadLocalZ);
        assertTrue(!untouchedLeaf.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT),
                "Expected leaves off the road to be left untouched (still non-persistent)");
    }

    /**
     * MINECRAFT-566 comment 55485/55503 (flat road deck over a short dip, local-heights-only, never
     * a global {@code ground()} override): against the SAME real, DEM-generated incline row
     * {@code aGeneratedShortInclineClosesEveryAdjacentStepToASmallSeamNotAWholeBlockCliff} already
     * uses -- {@link RoadDeck#deckY} must never report a height below a column's own natural ground
     * (no cuttings), and at least one real dip in this row (already confirmed to exist by the
     * incline test above) must actually get raised. Deliberately does NOT also assert "the row's own
     * highest column is never raised further": {@code deckY}'s real window is a full 2D box (every
     * nearby road column, not just ones sharing this exact Z row), so a 1D row measurement alone
     * cannot predict what it will find off-row -- an earlier version of this test asserted exactly
     * that from 1D data and was wrong, not {@code RoadDeck} itself (confirmed: the column it flagged
     * really was correctly raised by a real, higher, off-row neighbor the 1D measurement never saw).
     */
    @Test
    void deckNeverLowersAColumnAndRaisesARealDipToItsHighestNearbyRoadNeighbor() {
        var grid = generator.grid();
        int span = MEASURED_ROW_MAX_DX - MEASURED_ROW_MIN_DX + 1;
        int[] natural = new int[span];
        int[] deck = new int[span];
        boolean[] covered = new boolean[span];
        for (int i = 0; i < span; i++) {
            int dx = MEASURED_ROW_MIN_DX + i;
            int x = MEASURED_ROW_X0 + dx;
            covered[i] = RoadNetwork.coversColumn(grid, x, MEASURED_ROW_Z);
            if (!covered[i]) continue;
            natural[i] = generator.surfaceY(x, MEASURED_ROW_Z);
            deck[i] = RoadDeck.deckY(generator, grid, x, MEASURED_ROW_Z);
            assertTrue(deck[i] >= natural[i], "deckY must never be below natural ground at dx=" + dx);
        }

        boolean sawARaise = false;
        for (int i = 0; i < span; i++) {
            if (covered[i] && deck[i] > natural[i]) sawARaise = true;
        }
        assertTrue(sawARaise, "Expected at least one real dip in this row to actually get raised by the deck");
    }

    /**
     * Exercises {@link RoadDeck#buildAll} through a REAL generated cube at the pinned centerline
     * point (already confirmed, by the test above calling {@link RoadDeck#deckY} directly, to be a
     * column this ticket's own dip-raising logic actually raises): the support fill strictly between
     * natural ground and the deck top must be stone bricks, and the deck top itself must be a PLAIN
     * full pavement block (never the layered slope-compat variant -- a flat deck has no step to
     * smooth, so layering does not apply here, see {@link RoadDeck}'s own class doc).
     */
    @Test
    void deckBuildsStoneBrickSupportUnderAPlainFullPavementTop() {
        int[] onRoad = generator.block(ON_ROAD_LAT, ON_ROAD_LON);
        int x = onRoad[0], z = onRoad[1];
        int naturalY = generator.surfaceY(x, z);
        int deckY = RoadDeck.deckY(generator, generator.grid(), x, z);
        assertTrue(deckY > naturalY, "Expected the pinned centerline column to actually be raised by a nearby higher road column; "
                + "if the real DEM/road geometry changed, re-pin a column this logic actually raises rather than adjusting this test to fit");

        int cubeX = x >> 4, cubeY = deckY >> 4, cubeZ = z >> 4;
        LevelChunkSection section = generator.generate(cubeX, cubeY, cubeZ);
        int localX = x & 15, localZ = z & 15, deckLocalY = deckY & 15;

        var deckBlock = section.getBlockState(localX, deckLocalY, localZ);
        assertTrue(deckBlock.is(net.minecraft.world.level.block.Blocks.YELLOW_TERRACOTTA)
                        || deckBlock.is(net.minecraft.world.level.block.Blocks.POLISHED_BLACKSTONE),
                "Expected the deck top to be a plain full pavement block, got " + deckBlock);

        if (deckY - naturalY >= 2) { // at least one real support block strictly between natural ground and the deck top
            int supportLocalY = (naturalY + 1) & 15;
            var supportBlock = section.getBlockState(localX, supportLocalY, localZ);
            assertTrue(supportBlock.is(net.minecraft.world.level.block.Blocks.STONE_BRICKS),
                    "Expected stone-brick support directly above natural ground, got " + supportBlock);
        }
    }
}
