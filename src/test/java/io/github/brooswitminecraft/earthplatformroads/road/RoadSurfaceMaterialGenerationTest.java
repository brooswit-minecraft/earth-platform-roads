package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat;
import io.github.brooswitminecraft.layeredslopes.LayerMath;
import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
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
 * Review follow-up (Wroosbit 56339, then 56351 on this file's own first version): {@code
 * RoadSurfaceRenderingTest} only ever calls {@code RoadSurfaceRendering.topSurfaceFor} directly --
 * real block selection, but never through the applied {@code EarthGeneratorRoadMixin}, a real {@code
 * EarthGenerator.generate}, or a real generated {@code LevelChunkSection}. This fixture closes that
 * gap -- and 56351 additionally found the first version's DEM source was NOT offline (real
 * {@code TerrariumTileSource.AWS_TERRAIN_TILES} with {@code allowNetwork=true}), even though the
 * road tile source was. Both sources are offline here: every test writes an actual on-disk road
 * tile-cache file (production format, network disabled, same pattern {@code RoadTilePrefetchMixinTest}
 * already uses) AND seeds a synthetic, network-disabled {@link TerrariumTileSource} cache directly
 * (same technique {@code LevelRoadPreviewOfflineTest} already uses for a fully offline real
 * generator), builds a REAL {@link EarthGenerator}, and reads back the ACTUAL generated {@link
 * BlockState}.
 *
 * <p>Every fixture here uses its own fresh block-coordinate anchor (never the pinned Yosemite
 * Southside Drive extract {@code RoadGenerationTest} covers), so there is no ambiguity between this
 * test's own road and the bundled one at the same column.
 */
class RoadSurfaceMaterialGenerationTest {
    @AfterEach
    void cleanUp() {
        RoadNetwork.setTileSourceForTests(null);
    }

    /** A real generator over a fully synthetic, network-disabled DEM -- {@code seedElevation} must be called before any height/generate call reaches {@code tiles}. */
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
        // Invalid, never-dialed URI + allowNetwork=false: exactly LevelRoadPreviewOfflineTest's own
        // offline DEM pattern. Any cell not pre-seeded on disk would throw/fail closed, never fetch.
        TerrariumTileSource tiles = new TerrariumTileSource(
                URI.create("https://example.invalid/"), demCache, false, 128, 2, Duration.ofSeconds(1));
        EarthSettings settings = EarthSettings.at(0, 0);
        return new EarthGenerator(4747L, biomes, settings, tiles, null, null, null);
    }

    /**
     * Writes a synthetic Terrarium-encoded elevation tile set covering a small area around
     * {@code (anchorX, anchorZ)} (block coordinates), with a fully deterministic, known elevation at
     * every point: {@code base + slopeXPerBlock*(worldX-anchorX) + slopeZPerBlock*(worldZ-anchorZ)}.
     * Same pixel encoding and tile layout {@code LevelRoadPreviewOfflineTest} already uses (AWS
     * Terrarium: {@code round((32768+elevation)*256)} packed into the RGB channels as one 24-bit
     * integer) -- this is the exact on-disk cache format {@link TerrariumTileSource} reads, so no
     * code under test needs to know this data is synthetic.
     */
    private static void seedElevation(Path demCacheRoot, MercatorGrid grid, int anchorX, int anchorZ,
            double base, double slopeXPerBlock, double slopeZPerBlock) throws Exception {
        for (int zoom = 9; zoom <= TerrariumTileSource.MAX_ZOOM; zoom++) {
            double pixels = 256.0 * (1 << zoom);
            int tx = (int) (grid.pixelX(anchorX, zoom) / 256);
            int tz = (int) (grid.pixelY(anchorZ, zoom) / 256);
            for (int i = tx - 2; i <= tx + 2; i++) {
                for (int j = tz - 2; j <= tz + 2; j++) {
                    var image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
                    for (int px = 0; px < 256; px++) {
                        for (int pz = 0; pz < 256; pz++) {
                            double worldX = ((i * 256 + px + .5) / pixels - .5) * grid.worldWidthBlocks();
                            double worldZ = ((j * 256 + pz + .5) / pixels - .5) * grid.worldWidthBlocks();
                            double elevation = Math.clamp(
                                    base + slopeXPerBlock * (worldX - anchorX) + slopeZPerBlock * (worldZ - anchorZ), 80, 360);
                            image.setRGB(px, pz, (int) Math.round((32768 + elevation) * 256));
                        }
                    }
                    Path tile = demCacheRoot.resolve(zoom + "/" + i + "/" + j + ".png");
                    Files.createDirectories(tile.getParent());
                    assertTrue(ImageIO.write(image, "png", tile.toFile()));
                }
            }
        }
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
        int anchorX = 200_000, anchorZ = 100;
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        seedElevation(demCache, generator.grid(), anchorX, anchorZ, 200, 0, 0); // flat: no partial layering here
        GeoPoint a = generator.grid().geographic(anchorX, anchorZ), b = generator.grid().geographic(anchorX + 2, anchorZ + 2);
        writeTileCacheWay(roadCache, 900001L, "secondary", "gravel", a, b);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));
        assertTrue(RoadNetwork.coversColumn(generator.grid(), anchorX, anchorZ), "Sanity: fixture way must actually cover its own first endpoint's column");
        BlockState state = topBlockAt(generator, anchorX, anchorZ);
        assertTrue(state.is(Blocks.GRAVEL) || state.is(LayeredSlopesCompat.LAYERED_GRAVEL.get()),
                "Expected a real generated column on an explicit surface=gravel way to be gravel (full or layered), got " + state);
    }

    @Test
    void explicitDirtSurfaceGeneratesRealDirtReusingLayeredSlopesOwnBlock(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        int anchorX = 210_000, anchorZ = 100;
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        seedElevation(demCache, generator.grid(), anchorX, anchorZ, 200, 0, 0);
        GeoPoint a = generator.grid().geographic(anchorX, anchorZ), b = generator.grid().geographic(anchorX + 2, anchorZ + 2);
        writeTileCacheWay(roadCache, 900002L, "secondary", "dirt", a, b);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));
        assertTrue(RoadNetwork.coversColumn(generator.grid(), anchorX, anchorZ), "Sanity: fixture way must actually cover its own first endpoint's column");
        BlockState state = topBlockAt(generator, anchorX, anchorZ);
        assertTrue(state.is(Blocks.DIRT) || state.is(LayeredSlopesCompat.layeredDirtFromLayeredSlopes()),
                "Expected a real generated column on an explicit surface=dirt way to be dirt (full or layered, reusing Layered Slopes' own block), got " + state);
    }

    @Test
    void explicitSandSurfaceGeneratesRealSandReusingLayeredSlopesOwnBlock(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        int anchorX = 220_000, anchorZ = 100;
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        seedElevation(demCache, generator.grid(), anchorX, anchorZ, 200, 0, 0);
        GeoPoint a = generator.grid().geographic(anchorX, anchorZ), b = generator.grid().geographic(anchorX + 2, anchorZ + 2);
        writeTileCacheWay(roadCache, 900003L, "secondary", "sand", a, b);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));
        assertTrue(RoadNetwork.coversColumn(generator.grid(), anchorX, anchorZ), "Sanity: fixture way must actually cover its own first endpoint's column");
        BlockState state = topBlockAt(generator, anchorX, anchorZ);
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
        int anchorX = 230_000, anchorZ = 100;
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        seedElevation(demCache, generator.grid(), anchorX, anchorZ, 200, 0, 0);
        var grid = generator.grid();
        GeoPoint a = grid.geographic(anchorX, anchorZ), b = grid.geographic(anchorX + 2, anchorZ + 2);
        writeTileCacheWay(roadCache, 900004L, "secondary", null, a, b);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));
        RoadSegment segment = RoadNetwork.segmentCoveringColumn(grid, anchorX, anchorZ);
        assertTrue(segment != null, "Sanity: fixture way must actually cover its own first endpoint's column");
        // The endpoint itself is the centerline (SECONDARY has a centerline stripe) -- walk off it
        // to a genuine PAVED-zone column, exactly where today's existing plain blackstone applies.
        int dz = 0;
        while (segment.crossSectionAt(grid, anchorX, anchorZ + dz) != RoadSegment.CrossSection.PAVED && dz < 40) dz++;
        assertTrue(dz < 40, "Expected to actually reach a PAVED column by geometry, off the centerline stripe");
        BlockState state = topBlockAt(generator, anchorX, anchorZ + dz);
        assertTrue(state.is(Blocks.POLISHED_BLACKSTONE) || state.is(LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get()),
                "Expected a legacy (no surface tag) cached way's PAVED zone to still reopen as today's existing plain blackstone, got " + state);
    }

    /**
     * "Actual generated material ... in-place geometry" for a PARTIAL layer count, not just a full
     * block: seeds a deterministic, known elevation GRADE along the road (unlike the other tests'
     * flat fixture) so a genuine adjacent-column elevation step is guaranteed by construction, then
     * confirms at least one covered column actually generates the PARTIAL layered dirt block
     * (layers below {@link LayerMath#MAX_LAYERS}), not just a full block.
     */
    @Test
    void explicitDirtSurfaceGeneratesAPartialLayeredBlockAtARealElevationStep(
            @TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        int anchorX = 240_000, anchorZ = 100;
        int scanLength = 40;
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        var grid = generator.grid();
        // .6 blocks of rise per block of run: guarantees a same-or-adjacent-column integer-height
        // step (not a smooth ramp this heuristic's own fractional-layer decay would fully absorb)
        // along the whole stretch, by construction -- no DEM-change risk like a real-world scan.
        seedElevation(demCache, grid, anchorX, anchorZ, 200, 0.6, 0);
        GeoPoint start = grid.geographic(anchorX, anchorZ), end = grid.geographic(anchorX + scanLength, anchorZ);
        writeTileCacheWay(roadCache, 900005L, "secondary", "dirt", start, end);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));

        boolean sawPartialLayeredDirt = false;
        for (int dx = 0; dx <= scanLength; dx++) {
            int x = anchorX + dx, z = anchorZ;
            if (!RoadNetwork.coversColumn(grid, x, z)) continue;
            BlockState state = topBlockAt(generator, x, z);
            if (state.is(LayeredSlopesCompat.layeredDirtFromLayeredSlopes())) {
                int layers = state.getValue(LayeredBlock.LAYERS);
                if (layers < LayerMath.MAX_LAYERS) {
                    sawPartialLayeredDirt = true;
                    break;
                }
            }
        }
        assertTrue(sawPartialLayeredDirt,
                "Expected at least one column along the seeded elevation grade to generate a PARTIAL layered dirt block "
                        + "through the real generator/mixin path");
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
        int anchorX = 250_000, anchorZ = 100;
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        var grid = generator.grid();
        seedElevation(demCache, grid, anchorX, anchorZ, 200, 0, 0);
        GeoPoint a = grid.geographic(anchorX, anchorZ), b = grid.geographic(anchorX + 2, anchorZ + 2);
        writeTileCacheWay(roadCache, 900006L, "secondary", "gravel", a, b);
        RoadNetwork.setTileSourceForTests(offlineTileSource(roadCache));
        RoadSegment segment = RoadNetwork.segmentCoveringColumn(grid, anchorX, anchorZ);
        assertEquals(RoadClass.SECONDARY.defaultPavedHalfWidthBlocks, segment.pavedHalfWidthBlocks(),
                "Expected an explicit surface tag to leave the class's existing default paved half-width unchanged");

        int dz = 0;
        while (segment.crossSectionAt(grid, anchorX, anchorZ + dz) != RoadSegment.CrossSection.SHOULDER && dz < 40) dz++;
        assertTrue(dz < 40, "Expected to actually reach a SHOULDER column by geometry, same as the pre-existing (paved) cross-section shape");
        BlockState shoulderState = topBlockAt(generator, anchorX, anchorZ + dz);
        assertTrue(shoulderState.is(Blocks.GRAVEL) || shoulderState.is(LayeredSlopesCompat.LAYERED_GRAVEL.get()),
                "Expected the shoulder zone to still generate gravel regardless of this way's own gravel surface tag, got " + shoulderState);
    }
}
