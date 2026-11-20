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

    /** Either the original always-full polished blackstone, or MINECRAFT-566's layered compat block over the same base texture. */
    private static boolean isRoadSurface(BlockState state) {
        return state.is(Blocks.POLISHED_BLACKSTONE) || state.is(LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get());
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
     * Regression test for the exact MINECRAFT-566 review finding: these two adjacent columns were
     * measured (see the ScratchProbeTest exploration this fixed-coordinate test replaces) to be a
     * genuine, real-DEM-generated one-block step within the pinned Southside Drive band -- not a
     * synthetic slope value. {@code HIGH_X} sits one block higher than {@code LOW_X} at the same z.
     */
    private static final int STEP_LOW_X = -10534017, STEP_HIGH_X = -10534018, STEP_Z = -3591018;
    /** One column further from the step on the low side: both this and STEP_LOW_X share the same real ground height, a flat control. */
    private static final int STEP_FLAT_CONTROL_X = -10534016;

    @Test
    void aGeneratedOneBlockStepClosesToAOneSixteenthSeamNotAWholeBlockCliff() {
        int lowGround = generator.surfaceY(STEP_LOW_X, STEP_Z);
        int highGround = generator.surfaceY(STEP_HIGH_X, STEP_Z);
        int flatControlGround = generator.surfaceY(STEP_FLAT_CONTROL_X, STEP_Z);
        assertEquals(lowGround + 1, highGround, "Expected a measured real one-block step between these two fixed columns; "
                + "if the real DEM changed, re-measure replacement coordinates rather than adjusting this assertion to fit");
        assertEquals(lowGround, flatControlGround, "Expected the flat-control column to share the low column's real ground height");

        var lowState = surfaceBlockAtXZ(STEP_LOW_X, STEP_Z);
        var highState = surfaceBlockAtXZ(STEP_HIGH_X, STEP_Z);
        var flatState = surfaceBlockAtXZ(STEP_FLAT_CONTROL_X, STEP_Z);

        assertTrue(isRoadSurface(lowState), "Low side of the step must still be road surface, got " + lowState);
        assertTrue(isRoadSurface(highState), "High side of the step must still be road surface, got " + highState);
        assertTrue(isRoadSurface(flatState), "Flat control column must still be road surface, got " + flatState);

        double lowTop = lowGround + absoluteCollisionTopFraction(lowState);
        double highTop = highGround + absoluteCollisionTopFraction(highState);
        double flatTop = flatControlGround + absoluteCollisionTopFraction(flatState);

        // Flat control: unchanged behaviour, a full block, top exactly at ground+1.
        assertEquals(flatControlGround + 1.0, flatTop, 1e-9, "Flat control column must be a full block");

        // The regression this fixes: before, both sides of a 1-block step rounded to a full block,
        // leaving the full 1-block gap (highTop - lowTop == 1.0) untouched. After, the low side
        // stays full (meeting the step's base) and the high side drops to the minimum layer (sitting
        // just above its own floor), closing the gap to one sixteenth of a block.
        double gap = highTop - lowTop;
        assertTrue(gap < 0.2, "Expected the step to close to roughly a 1/16-block seam, not a 1-block cliff; got gap=" + gap
                + " (lowTop=" + lowTop + ", highTop=" + highTop + ")");
        assertTrue(gap > 0, "The high side must still read as at least slightly higher than the low side, got gap=" + gap);

        // Solid support: the block directly under each road column's top block must be a full,
        // non-air block (never a floating layered block over a gap) -- under() is always a full
        // block per the mixin, this confirms that holds for the real generated columns too.
        assertSolidSupportUnder(STEP_LOW_X, STEP_Z, lowGround);
        assertSolidSupportUnder(STEP_HIGH_X, STEP_Z, highGround);
        assertSolidSupportUnder(STEP_FLAT_CONTROL_X, STEP_Z, flatControlGround);
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
}
