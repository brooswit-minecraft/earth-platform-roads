package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real transformed EarthGenerator, real Terrarium decode, real Roads blocks and collision shape.
 * Applies the production overlay directly after generation, independently of ServerCubeWorld.produce
 * weaving or storage. No world, provider, server connection or external DEM download.
 */
@EnabledIfSystemProperty(named = "earthplatformroads.levelRoadPreview", matches = "true")
final class LevelRoadPreviewOfflineTest {
    private static final int X = 100_015; // stripe on final block of cube; right lane in next cube
    private static final int Z = 100_016;
    private static final long WAY_ID = 987_654_321L;

    @Test
    void dryCrossSlopeStripeAndWheelTracksHaveOneActualCapAndSupportedLowSide(@TempDir Path tmp) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements()
                .forEach(holder -> biomes.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        try (var in = EarthGenerator.class.getResourceAsStream("/data/earthplatform/worldgen/biome/glacial_lake.json")) {
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in));
            var biome = Biome.DIRECT_CODEC.parse(
                    net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, lookup),
                    json).getOrThrow();
            biomes.register(net.minecraft.resources.ResourceKey.create(Registries.BIOME,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("earthplatform", "glacial_lake")),
                    biome, RegistrationInfo.BUILT_IN);
        }

        EarthSettings settings = EarthSettings.at(0, 0);
        MercatorGrid grid = new MercatorGrid(1);
        Path dem = tmp.resolve("dem");
        seedElevation(dem, grid);
        Path regions = Files.createDirectories(tmp.resolve("regions"));
        var begin = grid.geographic(X + .5, Z - 64);
        var end = grid.geographic(X + .5, Z + 64);
        Files.writeString(regions.resolve("fixture.json"), """
                {"schema_version":1,"region_id":"dry_cross_slope","ways":[{
                   "way_id":%d,"way_tags":{"highway":"secondary","name":"Offline Cross Slope"},
                   "centerline":[{"lat":%s,"lon":%s},{"lat":%s,"lon":%s}],
                   "node_ids":[10001,10002]}]}
                """.formatted(WAY_ID, begin.latitude(), begin.longitude(), end.latitude(), end.longitude()));

        try (var tiles = new TerrariumTileSource(URI.create("https://example.invalid/"),
                dem, false, 128, 2, Duration.ofSeconds(1))) {
            var earth = new EarthGenerator(4747L, biomes, settings, tiles, null, null, null);
            var cachedOnly = new RoadTileSource(URI.create("http://127.0.0.1:1/"),
                    tmp.resolve("road-cache"), false, Duration.ofSeconds(1));
            RoadNetwork.resetForTests(regions);
            RoadNetwork.setTileSourceForTests(cachedOnly);
            try {
                assertEquals(WAY_ID, RoadNetwork.segmentCoveringColumn(earth.grid(), X, Z).wayId());
                int z = findFractionalStation(earth);
                int low = earth.surfaceY(X - 2, z);
                int high = earth.surfaceY(X + 2, z);
                assertTrue(high > low + 1, "offline DEM must produce a meaningful cross slope");
                double expected = expectedTop(earth, z);
                int y = (int) Math.floor(expected);
                assertTrue(y > high && expected > y, "fixture must exercise a raised fractional deck");

                Map<CubeKey, LevelChunkSection> sections = new HashMap<>();
                // Request the raised cap slices first. Do not prepopulate low-side support.
                var left = read(earth, sections, X - 2, y, z);
                var stripe = read(earth, sections, X, y, z);
                var right = read(earth, sections, X + 2, y, z);
                assertTrue(left.is(LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get()));
                assertTrue(stripe.is(LayeredSlopesCompat.LAYERED_YELLOW_TERRACOTTA.get()));
                assertTrue(right.is(LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get()));
                assertEquals(expected, collisionTop(left, y), 1e-7);
                assertEquals(expected, collisionTop(stripe, y), 1e-7);
                assertEquals(expected, collisionTop(right, y), 1e-7);
                for (int sy = low; sy < y; sy++) {
                    assertTrue(read(earth, sections, X - 2, sy, z).is(Blocks.STONE_BRICKS), "support at " + sy);
                }
            } finally {
                RoadNetwork.setTileSourceForTests(null);
                RoadNetwork.resetForTests(null);
            }
        }
    }

    private record CubeKey(int x, int y, int z) {}

    private static BlockState read(EarthGenerator earth, Map<CubeKey, LevelChunkSection> sections,
            int x, int y, int z) {
        var key = new CubeKey(x >> 4, y >> 4, z >> 4);
        var section = sections.computeIfAbsent(key, k -> {
            var generated = earth.generate(k.x(), k.y(), k.z());
            assertTrue(LevelRoadPreview.apply(earth, k.x(), k.y(), k.z(), generated),
                    "production overlay did not touch expected cube " + k);
            return generated;
        });
        return section.getBlockState(x & 15, y & 15, z & 15);
    }

    private static double collisionTop(BlockState state, int blockY) {
        return blockY + state.getCollisionShape(null, null).max(Direction.Axis.Y);
    }

    private static int findFractionalStation(EarthGenerator earth) {
        for (int z = Z - 24; z < Z + 24; z++) {
            double top = expectedTop(earth, z);
            if (top != Math.floor(top)) return z;
        }
        throw new AssertionError("synthetic grade did not induce any fractional station");
    }

    private static double expectedTop(EarthGenerator earth, int z) {
        int here = pavedMax(earth, z);
        int before = pavedMax(earth, z - 1);
        int after = pavedMax(earth, z + 1);
        return Math.max(here + 1.0, (before + 2.0 * here + after) * .25 + 1.0);
    }

    private static int pavedMax(EarthGenerator earth, int z) {
        var grid = earth.grid();
        int max = Integer.MIN_VALUE;
        for (int x = X - 10; x <= X + 10; x++) {
            var segment = RoadNetwork.segmentCoveringColumn(grid, x, z);
            if (segment == null || segment.wayId() != WAY_ID) continue;
            var zone = segment.crossSectionAt(grid, x, z);
            if (zone != RoadSegment.CrossSection.PAVED && zone != RoadSegment.CrossSection.CENTERLINE) continue;
            int ground = earth.surfaceY(x, z);
            int water = earth.waterY(x, z);
            assertTrue(water == SurfaceGenerator.DRY || water < ground, "fixture must remain dry");
            max = Math.max(max, ground);
        }
        assertTrue(max != Integer.MIN_VALUE, "fixture must have a paved footprint");
        return max;
    }

    /** A local continuous ramp encoded as real Terrarium PNGs at all supported relevant zooms. */
    private static void seedElevation(Path dir, MercatorGrid grid) throws Exception {
        for (int zoom = 9; zoom <= TerrariumTileSource.MAX_ZOOM; zoom++) {
            double pixels = 256.0 * (1 << zoom);
            int tx = (int) (grid.pixelX(X, zoom) / 256);
            int tz = (int) (grid.pixelY(Z, zoom) / 256);
            for (int i = tx - 2; i <= tx + 2; i++) for (int j = tz - 2; j <= tz + 2; j++) {
                var image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
                for (int px = 0; px < 256; px++) for (int pz = 0; pz < 256; pz++) {
                    double worldX = ((i * 256 + px + .5) / pixels - .5) * grid.worldWidthBlocks();
                    double worldZ = ((j * 256 + pz + .5) / pixels - .5) * grid.worldWidthBlocks();
                    double elevation = Math.clamp(220 + .8 * (worldX - X) + .15 * (worldZ - Z), 80, 360);
                    image.setRGB(px, pz, (int) Math.round((32768 + elevation) * 256));
                }
                Path tile = dir.resolve(zoom + "/" + i + "/" + j + ".png");
                Files.createDirectories(tile.getParent());
                assertTrue(ImageIO.write(image, "png", tile.toFile()));
            }
        }
    }
}
