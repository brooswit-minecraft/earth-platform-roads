package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat;
import io.github.brooswitminecraft.layeredslopes.LayerMath;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review follow-up (Wroosbit comment 56339, CHANGES_REQUESTED on PR #12 @ e906078): {@code
 * RoadSurfaceRenderingTest} only ever calls {@code RoadSurfaceRendering.topSurfaceFor} directly --
 * real block selection, but never through the applied {@code EarthGeneratorRoadMixin}, a real {@code
 * EarthGenerator.generate}, or a real generated {@code LevelChunkSection}. This fixture closes that
 * gap: every test here writes an actual on-disk road-tile cache file (the same disk format {@code
 * RoadTileSource} reads in production, network disabled so no provider request ever happens --
 * same offline pattern {@code RoadTilePrefetchMixinTest} already uses), builds a REAL {@link
 * EarthGenerator} (real DEM-backed ground height, same as {@code RoadGenerationTest}), and reads
 * back the ACTUAL generated {@link BlockState} at the real column the road covers.
 *
 * <p>Every fixture here uses a brand-new, never-bundled real-world coordinate (never the pinned
 * Yosemite Southside Drive extract {@code RoadGenerationTest} already covers), so there is no
 * ambiguity between this test's own road and the bundled one at the same column.
 */
class RoadSurfaceMaterialGenerationTest {
    @AfterEach
    void cleanUp() {
        RoadNetwork.setTileSourceForTests(null);
    }

    private static EarthGenerator bootAndBuildGenerator(Path demCache) throws Exception {
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
        TerrariumTileSource tiles = new TerrariumTileSource(
                TerrariumTileSource.AWS_TERRAIN_TILES, demCache.resolve(TerrariumTileSource.DATASET_ID), true, 512, 6, Duration.ofSeconds(20));
        EarthSettings settings = EarthSettings.at(EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE);
        return new EarthGenerator(4747L, biomes, settings, tiles, null, null, null);
    }

    /** Writes a single-way, network-never-needed tile cache entry for every cell the way's bounding box (plus margin) touches, exactly like a real fetched/pinned region. */
    private static void writeTileCacheWay(Path roadCache, long wayId, String highway, String surfaceTagOrNull,
            GeoPoint a, GeoPoint b) throws Exception {
        Files.createDirectories(roadCache);
        String surfaceJson = surfaceTagOrNull == null ? "" : ",\"surface\":\"" + surfaceTagOrNull + "\"";
        String wayJson = """
                {"way_id":%d,"way_tags":{"highway":"%s","name":"Surface Material Fixture"%s},
                  "centerline":[{"lat":%s,"lon":%s},{"lat":%s,"lon":%s}],
                  "node_ids":[%d,%d],"bridge":false,"tunnel":false}
                """.formatted(wayId, highway, surfaceJson, a.latitude(), a.longitude(), b.latitude(), b.longitude(), wayId * 2, wayId * 2 + 1);
        String regionJson = "{\"schema_version\":1,\"region_id\":\"surface-material-fixture\",\"ways\":[" + wayJson + "]}";

        double minLat = Math.min(a.latitude(), b.latitude()) - RoadNetwork.CELL_DEGREES;
        double maxLat = Math.max(a.latitude(), b.latitude()) + RoadNetwork.CELL_DEGREES;
        double minLon = Math.min(a.longitude(), b.longitude()) - RoadNetwork.CELL_DEGREES;
        double maxLon = Math.max(a.longitude(), b.longitude()) + RoadNetwork.CELL_DEGREES;
        for (double lat = minLat; lat <= maxLat; lat += RoadNetwork.CELL_DEGREES) {
            for (double lon = minLon; lon <= maxLon; lon += RoadNetwork.CELL_DEGREES) {
                long cellLat = (long) Math.floor(lat / RoadNetwork.CELL_DEGREES);
                long cellLon = (long) Math.floor(lon / RoadNetwork.CELL_DEGREES);
                Files.writeString(roadCache.resolve(cellLat + "_" + cellLon + ".json"), regionJson, StandardCharsets.UTF_8);
            }
        }
    }

    private static RoadTileSource offlineTileSource(Path roadCache) {
        return new RoadTileSource(URI.create("http://127.0.0.1:1/"), roadCache, false, Duration.ofSeconds(5));
    }

    private static BlockState topBlockAt(EarthGenerator generator, int x, int z) {
        int ground = generator.surfaceY(x, z);
        LevelChunkSection section = generator.generate(x >> 4, ground >> 4, z >> 4);
        return section.getBlockState(x & 15, ground & 15, z & 15);
    }

    @Test
    void explicitGravelSurfaceGeneratesRealGravelNotBlackstone(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        GeoPoint a = new GeoPoint(39.1000, -120.0400), b = new GeoPoint(39.1002, -120.0398);
        writeTileCacheWay(roadCache, 900001L, "secondary", "gravel", a, b);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        int[] block = generator.block(a.latitude(), a.longitude());
        assertTrue(RoadNetwork.coversColumn(generator.grid(), block[0], block[1]), "Sanity: fixture way must actually cover its own first endpoint's column");
        BlockState state = topBlockAt(generator, block[0], block[1]);
        assertTrue(state.is(Blocks.GRAVEL) || state.is(LayeredSlopesCompat.LAYERED_GRAVEL.get()),
                "Expected a real generated column on an explicit surface=gravel way to be gravel (full or layered), got " + state);
    }

    @Test
    void explicitDirtSurfaceGeneratesRealDirtReusingLayeredSlopesOwnBlock(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        GeoPoint a = new GeoPoint(39.2000, -120.1400), b = new GeoPoint(39.2002, -120.1398);
        writeTileCacheWay(roadCache, 900002L, "secondary", "dirt", a, b);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        int[] block = generator.block(a.latitude(), a.longitude());
        assertTrue(RoadNetwork.coversColumn(generator.grid(), block[0], block[1]), "Sanity: fixture way must actually cover its own first endpoint's column");
        BlockState state = topBlockAt(generator, block[0], block[1]);
        assertTrue(state.is(Blocks.DIRT) || state.is(LayeredSlopesCompat.layeredDirtFromLayeredSlopes()),
                "Expected a real generated column on an explicit surface=dirt way to be dirt (full or layered, reusing Layered Slopes' own block), got " + state);
    }

    @Test
    void explicitSandSurfaceGeneratesRealSandReusingLayeredSlopesOwnBlock(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        GeoPoint a = new GeoPoint(39.3000, -120.2400), b = new GeoPoint(39.3002, -120.2398);
        writeTileCacheWay(roadCache, 900003L, "secondary", "sand", a, b);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        int[] block = generator.block(a.latitude(), a.longitude());
        assertTrue(RoadNetwork.coversColumn(generator.grid(), block[0], block[1]), "Sanity: fixture way must actually cover its own first endpoint's column");
        BlockState state = topBlockAt(generator, block[0], block[1]);
        assertTrue(state.is(Blocks.SAND) || state.is(LayeredSlopesCompat.layeredSandFromLayeredSlopes()),
                "Expected a real generated column on an explicit surface=sand way to be sand (full or layered, reusing Layered Slopes' own block), got " + state);
    }

    /**
     * The explicit legacy-reopen case the review asked to keep: a tile cache entry written with NO
     * {@code "surface"} key at all (exactly what every cached/pinned region written before this
     * ticket looks like) must still generate the pre-existing plain blackstone, through the real
     * generator/mixin path, not merely at the pure-function level.
     */
    @Test
    void legacyTileCacheWithNoSurfaceFieldReopensAsPlainBlackstoneThroughTheRealGenerator(
            @TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        GeoPoint a = new GeoPoint(39.4000, -120.3400), b = new GeoPoint(39.4002, -120.3398);
        writeTileCacheWay(roadCache, 900004L, "secondary", null, a, b);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        var grid = generator.grid();
        int[] block = generator.block(a.latitude(), a.longitude());
        int x = block[0], z = block[1];
        RoadSegment segment = RoadNetwork.segmentCoveringColumn(grid, x, z);
        assertTrue(segment != null, "Sanity: fixture way must actually cover its own first endpoint's column");
        // The endpoint itself is the centerline (SECONDARY has a centerline stripe) -- walk off it
        // to a genuine PAVED-zone column, exactly where today's existing plain blackstone applies.
        int dz = 0;
        while (segment.crossSectionAt(grid, x, z + dz) != RoadSegment.CrossSection.PAVED && dz < 40) dz++;
        assertTrue(dz < 40, "Expected to actually reach a PAVED column by geometry, off the centerline stripe");
        BlockState state = topBlockAt(generator, x, z + dz);
        assertTrue(state.is(Blocks.POLISHED_BLACKSTONE) || state.is(LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get()),
                "Expected a legacy (no surface tag) cached way's PAVED zone to still reopen as today's existing plain blackstone, got " + state);
    }

    /**
     * "Actual generated material ... in-place geometry" for a PARTIAL layer count, not just a full
     * block: scans a real, never-bundled stretch of real DEM for a genuine adjacent-column
     * elevation step (same technique {@code RoadGenerationTest}'s own class doc describes using for
     * its pinned fixture), then writes the fixture way across exactly that stretch with an explicit
     * {@code surface=dirt} tag and confirms at least one covered column actually generates the
     * PARTIAL layered dirt block (layers below {@link LayerMath#MAX_LAYERS}), not just a full block.
     */
    @Test
    void explicitDirtSurfaceGeneratesAPartialLayeredBlockAtARealElevationStep(
            @TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        EarthGenerator probe = bootAndBuildGenerator(demCache);
        var grid = probe.grid();
        int[] origin = probe.block(39.5000, -120.4400); // never-bundled, real high-Sierra terrain
        int scanLength = 80;
        int stepDx = -1, bestGapAbs = 0;
        int previousGround = probe.surfaceY(origin[0], origin[1]);
        for (int dx = 1; dx <= scanLength; dx++) {
            int ground = probe.surfaceY(origin[0] + dx, origin[1]);
            int gap = Math.abs(ground - previousGround);
            if (gap > bestGapAbs) {
                bestGapAbs = gap;
                stepDx = dx;
            }
            previousGround = ground;
        }
        assertTrue(stepDx > 0 && bestGapAbs > 0,
                "Expected to find at least one real adjacent-column elevation step scanning 80 real columns; "
                        + "if the real DEM at this location changed, re-pick a location rather than adjusting this test to fit");

        GeoPoint start = grid.geographic(origin[0], origin[1]);
        GeoPoint end = grid.geographic(origin[0] + scanLength, origin[1]);
        writeTileCacheWay(roadCache, 900005L, "secondary", "dirt", start, end);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));
        EarthGenerator generator = bootAndBuildGenerator(demCache);

        boolean sawPartialLayeredDirt = false;
        for (int dx = Math.max(0, stepDx - 1); dx <= Math.min(scanLength, stepDx + 1); dx++) {
            int x = origin[0] + dx, z = origin[1];
            if (!RoadNetwork.coversColumn(generator.grid(), x, z)) continue;
            BlockState state = topBlockAt(generator, x, z);
            if (state.is(LayeredSlopesCompat.layeredDirtFromLayeredSlopes())) {
                int layers = state.getValue(io.github.brooswitminecraft.layeredslopes.LayeredBlock.LAYERS);
                if (layers < LayerMath.MAX_LAYERS) {
                    sawPartialLayeredDirt = true;
                    break;
                }
            }
        }
        assertTrue(sawPartialLayeredDirt,
                "Expected at least one column near the real elevation step (dx=" + stepDx + ", gap=" + bestGapAbs
                        + ") to generate a PARTIAL layered dirt block through the real generator/mixin path");
    }

    /**
     * In-place geometry, through the real generator: a gravel-tagged way's cross-section footprint
     * (paved width, shoulder) is identical to the pre-existing paved behavior -- only the material
     * painted differs, never the geometry. Confirms a column just past the paved half-width still
     * reads as the gravel SHOULDER (not off-road, and not swallowed into the paved zone), using the
     * exact same real generator/road data this fixture already built.
     */
    @Test
    void explicitSurfaceTagNeverChangesGeometryThroughTheRealGenerator(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        GeoPoint a = new GeoPoint(39.6000, -120.5400), b = new GeoPoint(39.6002, -120.5398);
        writeTileCacheWay(roadCache, 900006L, "secondary", "gravel", a, b);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        var grid = generator.grid();
        int[] block = generator.block(a.latitude(), a.longitude());
        int x = block[0], z = block[1];
        RoadSegment segment = RoadNetwork.segmentCoveringColumn(grid, x, z);
        assertEquals(RoadClass.SECONDARY.defaultPavedHalfWidthBlocks, segment.pavedHalfWidthBlocks(),
                "Expected an explicit surface tag to leave the class's existing default paved half-width unchanged");

        int dz = 0;
        while (segment.crossSectionAt(grid, x, z + dz) != RoadSegment.CrossSection.SHOULDER && dz < 40) dz++;
        assertTrue(dz < 40, "Expected to actually reach a SHOULDER column by geometry, same as the pre-existing (paved) cross-section shape");
        BlockState shoulderState = topBlockAt(generator, x, z + dz);
        assertTrue(shoulderState.is(Blocks.GRAVEL) || shoulderState.is(LayeredSlopesCompat.LAYERED_GRAVEL.get()),
                "Expected the shoulder zone to still generate gravel regardless of this way's own gravel surface tag, got " + shoulderState);
    }
}
