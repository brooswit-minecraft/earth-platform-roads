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
                Map<CubeKey, LevelChunkSection> sections = new HashMap<>();
                int ceiling = Integer.MIN_VALUE;
                for (int row = Z - 70; row <= Z + 70; row++) {
                    for (int x = X - 8; x <= X + 8; x++) ceiling = Math.max(ceiling, earth.surfaceY(x, row));
                }
                int z = 0;
                double previous = Double.NaN, expected = Double.NaN;
                for (int row = Z - 12; row <= Z + 12; row++) {
                    // Independently discover the actual generated cap, not the planner's output.
                    double leftTop = findActualCap(earth, sections, X - 2, row, ceiling, false);
                    double centerTop = findActualCap(earth, sections, X, row, ceiling, true);
                    double rightTop = findActualCap(earth, sections, X + 2, row, ceiling, false);
                    assertEquals(centerTop, leftTop, 1e-7, "left at row " + row);
                    assertEquals(centerTop, rightTop, 1e-7, "right at row " + row);
                    if (Double.isFinite(previous)) {
                        assertTrue(Math.abs(previous - centerTop) <= .5 + 1e-7,
                                "adjacent grade exceeds half a block at row " + row);
                    }
                    previous = centerTop;
                    if (!Double.isFinite(expected) && centerTop != Math.floor(centerTop)) {
                        z = row;
                        expected = centerTop;
                    }
                }
                assertTrue(Double.isFinite(expected), "fixture must contain a fractional cap");
                int low = earth.surfaceY(X - 2, z);
                int high = earth.surfaceY(X + 2, z);
                assertTrue(high > low + 1, "offline DEM must produce a meaningful cross slope");
                int y = (int) Math.floor(expected);
                assertTrue(y > high && expected > y, "fixture must exercise a raised fractional deck");

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
            LevelRoadPreview.apply(earth, k.x(), k.y(), k.z(), generated);
            return generated;
        });
        return section.getBlockState(x & 15, y & 15, z & 15);
    }

    private static double collisionTop(BlockState state, int blockY) {
        return blockY + state.getCollisionShape(null, null).max(Direction.Axis.Y);
    }

    private static double findActualCap(EarthGenerator earth, Map<CubeKey, LevelChunkSection> sections,
            int x, int z, int ceiling, boolean stripe) {
        var want = stripe ? LayeredSlopesCompat.LAYERED_YELLOW_TERRACOTTA.get()
                : LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get();
        int natural = earth.surfaceY(x, z);
        int water = earth.waterY(x, z);
        assertTrue(water == SurfaceGenerator.DRY || water < natural, "fixture must remain dry");
        // Search high-first. A baseline cap at/below the local natural top does not count.
        for (int y = ceiling + 1; y >= natural; y--) {
            var state = read(earth, sections, x, y, z);
            if (state.is(want) && collisionTop(state, y) >= natural + 1.0) return collisionTop(state, y);
        }
        throw new AssertionError("road cap missing or below the no-cut floor at " + x + "," + z);
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
