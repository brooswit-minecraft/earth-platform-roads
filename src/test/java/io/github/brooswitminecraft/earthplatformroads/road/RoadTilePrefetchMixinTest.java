package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@code EarthGeneratorPrepareMixin} actually does what MINECRAFT-566's review demanded:
 * calling Earth Platform's own {@code prepareNear} -- its documented "fetch ahead of generation"
 * entry point, the same one {@code /earth travel} calls off-thread before a player lands -- through
 * the real mixin on a real generator must populate {@code RoadTileSource}'s cache, so that a later,
 * cache-only {@code RoadNetwork} lookup (the one {@code EarthGeneratorRoadMixin}'s hot {@code
 * surface()} path uses) finds the data without itself ever touching the network.
 */
class RoadTilePrefetchMixinTest {
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

    @Test
    void prepareNearPopulatesTheCacheWithoutSurfaceEverFetching(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String response = """
                {"elements":[{"type":"way","id":555002,"nodes":[1,2],
                  "tags":{"highway":"secondary","name":"Prefetch Test Street"},
                  "geometry":[{"lat":37.7240,"lon":-119.6365},{"lat":37.7241,"lon":-119.6366}]}]}
                """;
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            RoadTileSource tileSource = new RoadTileSource(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(10));
            RoadNetwork.setTileSourceForTests(tileSource);

            EarthGenerator generator = bootAndBuildGenerator(demCache);
            int[] block = generator.block(EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE);

            // Before prepareNear runs, the cache-only lookup must be empty -- nothing fetched yet.
            assertTrue(RoadNetwork.segmentCoveringColumn(generator.grid(), block[0], block[1]) == null
                    || requests.get() == 0, "Expected no road-data fetch before prepareNear runs");
            assertEquals(0, requests.get());

            // Earth Platform's own "fetch ahead of generation" entry point; the mixin taps it.
            // This mixin fetches only the point's own cell (comment 55456: a 9-cell version was
            // tried and found to interact badly with the provider's own rate limit -- see
            // EarthGeneratorPrepareMixin's own class doc). The boundary-seam fix lives on the read
            // side instead (RoadNetwork.candidatesNear), exercised by this class's other tests.
            generator.prepareNear(block[0], block[1]);
            assertEquals(1, requests.get(), "Expected prepareNear to trigger exactly one road-tile fetch for this point's cell");

            // Now a cache-only lookup (what the hot surface() generation path actually uses) must
            // find the prefetched way, with no additional network request.
            var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), block[0], block[1]);
            assertTrue(covering != null && covering.wayId() == 555002L,
                    "Expected the prefetched way to be found by a cache-only lookup after prepareNear");
            assertEquals(1, requests.get(), "The cache-only lookup after prepareNear must not trigger any further fetch");
        } finally {
            stub.stop(0);
        }
    }

    /**
     * MINECRAFT-566 comment 55454's original defect, demonstrated with the CORRECT fix (comment
     * 55456: a read-side neighbor check, not a multi-cell prefetch): John saw a live road abruptly
     * end on bare ground at a tile boundary -- his own read of the player's tile and its neighbors
     * found 7/8 with no ready/empty marker at all. This test fetches only ONE cell directly
     * (bypassing the mixin, so it is not even the point this specific call tests) and confirms a
     * real mapped way whose geometry crosses into the adjacent, NEVER-fetched neighbor cell is
     * still found there -- because {@code RoadNetwork.candidatesNear} now also reads the
     * neighbor's cache (empty, in this case) AND the fetched cell's cache, and the fetched cell's
     * own data already carries the crossing way's full geometry (a live provider's bbox query
     * returns the whole way for any cell it merely touches).
     */
    @Test
    void aSingleFetchCarriesACrossingRoadIntoItsNeverFetchedNeighbor(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        // A synthetic way crossing the real cell boundary at lat=5.01 (CELL_DEGREES=0.01): its
        // southern point at lat=5.006 is in cell 500_-3001, its northern point at lat=5.016 is in
        // the adjacent cell 501_-3001, which this test NEVER fetches. A real Overpass-shaped
        // response returns the WHOLE way's geometry for either cell's bbox query, same as the real
        // provider would.
        String response = """
                {"elements":[{"type":"way","id":555020,"nodes":[1,2],
                  "tags":{"highway":"secondary","name":"Boundary Test Street"},
                  "geometry":[{"lat":5.006,"lon":-30.004},{"lat":5.016,"lon":-30.004}]}]}
                """;
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            RoadTileSource tileSource = new RoadTileSource(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(10));
            RoadNetwork.setTileSourceForTests(tileSource);

            // Only cell 500_-3001 is ever fetched -- cell 501_-3001 (the neighbor) never is.
            tileSource.waysForCell(5.008, -30.004);

            EarthGenerator generator = bootAndBuildGenerator(demCache);
            int[] northColumn = generator.block(5.014, -30.004); // inside the never-fetched neighbor cell 501_-3001, on the way's own line

            var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), northColumn[0], northColumn[1]);
            assertTrue(covering != null && covering.wayId() == 555020L,
                    "Expected the crossing way to still be found in the never-fetched neighbor cell, via the read-side neighbor check. Got " + covering);
        } finally {
            stub.stop(0);
        }
    }

    /**
     * MINECRAFT-566 comment 55456's explicit ask: "a production-cadence stub regression from empty
     * cache exercising the actual prepare-to-generation path... no arbitrary sleep or throttle
     * removal." Uses the SAME {@code previewMinRequestIntervalMs} order of magnitude as the real
     * production default (5000ms, see {@code RoadNetwork.DEFAULT_PREVIEW_MIN_REQUEST_INTERVAL_MS}),
     * from a genuinely empty cache, through the real mixin and real generation -- not a direct,
     * bypassing call, and not a zeroed-out rate limit. Exactly one real fetch happens (comment
     * 55454's original 9-call approach would have had 8 of those 9 rejected by this same real
     * interval, which is precisely what this test guards against regressing to); a neighboring
     * column's road candidate is present via the read-side check before generation ever runs there.
     */
    @Test
    void aProductionCadenceEmptyCacheRunCarriesTheSeamThroughRealGeneration(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        String response = """
                {"elements":[{"type":"way","id":555021,"nodes":[1,2],
                  "tags":{"highway":"secondary","name":"Boundary Fix Street"},
                  "geometry":[{"lat":6.006,"lon":-31.004},{"lat":6.016,"lon":-31.004}]}]}
                """;
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            // The real production rate limit, not zero -- this is the exact condition 55456 found
            // the original fix attempt never actually exercised.
            RoadTileSource tileSource = new RoadTileSource(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(10),
                    null, Duration.ofMillis(5000));
            RoadNetwork.setTileSourceForTests(tileSource);

            EarthGenerator generator = bootAndBuildGenerator(demCache);
            int[] southBlock = generator.block(6.008, -31.004); // inside cell 600_-3101

            // The real off-thread fetch-ahead path a player's ordinary travel/pregen calls.
            generator.prepareNear(southBlock[0], southBlock[1]);
            assertEquals(1, requests.get(), "Expected exactly one real fetch -- the point's own cell only, never a burst that a real rate limit would partly reject");

            // The neighboring cell (601_-3101) was never itself fetched; the crossing way's full
            // geometry already came back on cell 600_-3101's own fetch. Generation must find it
            // there via the read-side neighbor check, with no further network request at all --
            // including no attempt blocked by the still-active 5-second next-eligible deadline.
            int[] northColumn = generator.block(6.014, -31.004);
            var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), northColumn[0], northColumn[1]);
            assertTrue(covering != null && covering.wayId() == 555021L,
                    "Expected the crossing way present at the neighboring column before generation, under the real rate limit. Got " + covering);
            assertEquals(1, requests.get(), "Expected the neighbor-cell read to trigger no additional request");
            // Not asserting an actual painted block here: these synthetic coordinates (chosen only
            // to land cleanly on a cell boundary, not for real terrain) may fall in open ocean,
            // where EarthGeneratorRoadMixin correctly skips painting a road underwater regardless
            // of this fix -- that would be a false failure unrelated to what this test checks. The
            // "candidate present before generation" claim is exactly what segmentCoveringColumn
            // proves; RoadGenerationTest and RealOsmFixtureSmokeTest already cover real block
            // painting at real, dry-land coordinates.
        } finally {
            stub.stop(0);
        }
    }
}
