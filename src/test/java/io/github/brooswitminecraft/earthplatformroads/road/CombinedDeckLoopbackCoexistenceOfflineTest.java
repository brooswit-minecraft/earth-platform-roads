package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * MINECRAFT-567 combined deck+loopback preview (John 55616, Wroosbit 55617): proves the two slices
 * merged onto this branch -- PR #6's level-road deck/leveling logic ({@link LevelRoadPreview}) and
 * this branch's own opt-in, credential-free loopback-proxy client ({@link RoadTileSource} /
 * {@link RoadNetwork#buildDefaultTileSource}) -- actually COEXIST on one head, not just that each
 * passes its own tests in isolation.
 *
 * <p>The fixture below is deliberately the same offline leveling scenario as
 * {@link LevelRoadPreviewOfflineTest} (same synthetic cross-slope DEM, same expected
 * leveled/striped cap), but the way data it levels never comes from a static bundled/external
 * region file: it is fetched through the REAL production tile-source wiring
 * ({@link RoadNetwork#liveTileSourceForPrefetch()}), with the loopback switch on, against a
 * disposable in-process {@link HttpServer} fixture standing in for the still-separately-gated
 * MINECRAFT-570 SQLite proxy -- exactly like {@link RoadNetworkLoopbackProxyTest}'s own fake-proxy
 * fixtures. No live provider, no credentials, no Zippy, no real network host is ever touched.
 *
 * <p>This is gated behind the same {@code earthplatformroads.levelRoadPreview} system property as
 * {@link LevelRoadPreviewOfflineTest} (propagated via {@code JAVA_TOOL_OPTIONS}, not a plain
 * gradle {@code -D}, per PR #6's own {@code level-road-qa.yml} mechanism -- a plain {@code -D} is
 * silently swallowed by the test JVM fork and the test is skipped rather than failed).
 */
@EnabledIfSystemProperty(named = "earthplatformroads.levelRoadPreview", matches = "true")
final class CombinedDeckLoopbackCoexistenceOfflineTest {
    private static final int X = 200_015;
    private static final int Z = 200_016;
    private static final long WAY_ID = 987_654_322L;

    private HttpServer fakeProxy;

    @AfterEach
    void cleanUp() {
        if (fakeProxy != null) fakeProxy.stop(0);
        System.clearProperty("earthplatformroads.loopbackProxyEnabled");
        System.clearProperty("earthplatformroads.loopbackProxyEndpoint");
        RoadNetwork.setDefaultTileSourceInputsForTests(null, null);
        RoadNetwork.resetForTests(null);
    }

    @Test
    void dryCrossSlopeStripeStillLevelsWhenWayDataArrivesThroughTheLoopbackProxyClient(@TempDir Path tmp) throws Exception {
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

        var begin = grid.geographic(X + .5, Z - 64);
        var end = grid.geographic(X + .5, Z + 64);
        var mid = grid.geographic(X + .5, Z + .5);

        // Fake, disposable, in-process loopback proxy -- same pattern as
        // RoadNetworkLoopbackProxyTest's FakeWarmProxyFixture, serving the Overpass "out geom" JSON
        // shape OverpassWayParser actually consumes for a real live fetch.
        AtomicInteger proxyHits = new AtomicInteger();
        String overpassResponse = """
                {"elements":[{"type":"way","id":%d,"nodes":[20001,20002],
                  "tags":{"highway":"secondary","name":"Combined Preview Cross Slope"},
                  "geometry":[{"lat":%s,"lon":%s},{"lat":%s,"lon":%s}]}]}
                """.formatted(WAY_ID, begin.latitude(), begin.longitude(), end.latitude(), end.longitude());
        fakeProxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeProxy.createContext("/", exchange -> {
            proxyHits.incrementAndGet();
            byte[] body = overpassResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fakeProxy.start();

        // Real production wiring, loopback switch ON, pointed at the fake proxy -- not a directly
        // constructed RoadTileSource and not RoadNetwork.setTileSourceForTests, so this exercises
        // the exact branch-selection logic in RoadNetwork.buildDefaultTileSource an ordinary install
        // would run once an operator opts in.
        System.setProperty("earthplatformroads.loopbackProxyEnabled", "true");
        System.setProperty("earthplatformroads.loopbackProxyEndpoint",
                "http://127.0.0.1:" + fakeProxy.getAddress().getPort() + "/");
        RoadNetwork.resetForTests(null); // no static region file at all for this test -- deliberate
        RoadNetwork.setDefaultTileSourceInputsForTests(null, tmp.resolve("road-tiles"));

        RoadTileSource liveLoopbackSource = RoadNetwork.liveTileSourceForPrefetch();
        assertTrue(liveLoopbackSource != null, "Expected a live tile source once the loopback switch is on and configured");

        // The one real HTTP send: fetches the fixture's own way data through the loopback client,
        // exactly as EarthGeneratorPrepareMixin's prefetch would in the real game loop.
        var fetched = liveLoopbackSource.waysForCell(mid.latitude(), mid.longitude());
        assertFalse(fetched.isEmpty(), "Expected the fake proxy's canned response to actually resolve the fixture way");
        assertEquals(1, proxyHits.get(), "Expected exactly one real send to the fake loopback proxy");

        try (var tiles = new TerrariumTileSource(URI.create("https://example.invalid/"),
                dem, false, 128, 2, Duration.ofSeconds(1))) {
            var earth = new EarthGenerator(4747L, biomes, settings, tiles, null, null, null);
            assertEquals(WAY_ID, RoadNetwork.segmentCoveringColumn(earth.grid(), X, Z).wayId(),
                    "Expected the loopback-fetched way (not a static region file) to cover the fixture column");

            Map<CubeKey, LevelChunkSection> sections = new HashMap<>();
            int ceiling = Integer.MIN_VALUE;
            for (int row = Z - 70; row <= Z + 70; row++) {
                for (int x = X - 8; x <= X + 8; x++) ceiling = Math.max(ceiling, earth.surfaceY(x, row));
            }
            int z = 0;
            double previous = Double.NaN, expected = Double.NaN;
            for (int row = Z - 12; row <= Z + 12; row++) {
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
        for (int y = ceiling + 1; y >= natural; y--) {
            var state = read(earth, sections, x, y, z);
            if (state.is(want) && collisionTop(state, y) >= natural + 1.0) return collisionTop(state, y);
        }
        throw new AssertionError("road cap missing or below the no-cut floor at " + x + "," + z);
    }

    /** Same synthetic cross-slope ramp as {@link LevelRoadPreviewOfflineTest}, encoded as real Terrarium PNGs. */
    private static void seedElevation(Path dir, MercatorGrid grid) throws IOException {
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
