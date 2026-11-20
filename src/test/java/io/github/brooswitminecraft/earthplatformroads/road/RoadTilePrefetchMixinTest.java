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
            // Since comment 55454's fix, this prefetches the point's own cell AND its full Moore
            // neighborhood (9 cells total), not just the single center cell -- see
            // EarthGeneratorPrepareMixin's own class doc for why.
            generator.prepareNear(block[0], block[1]);
            assertEquals(9, requests.get(), "Expected prepareNear to prefetch the point's cell plus its 8 Moore-neighborhood cells");

            // Now a cache-only lookup (what the hot surface() generation path actually uses) must
            // find the prefetched way, with no additional network request.
            var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), block[0], block[1]);
            assertTrue(covering != null && covering.wayId() == 555002L,
                    "Expected the prefetched way to be found by a cache-only lookup after prepareNear");
            assertEquals(9, requests.get(), "The cache-only lookup after prepareNear must not trigger any further fetch");
        } finally {
            stub.stop(0);
        }
    }

    /**
     * MINECRAFT-566 comment 55454, reproduced: John saw a live road abruptly end on bare ground at
     * a tile boundary (not a bridge, not a real OSM way endpoint) -- his own read of the player's
     * tile and its neighbors found 7/8 neighbors with no ready/empty marker at all, only the
     * immediate north neighbor ready. This exercises exactly that mechanism synthetically (per his
     * "reproduce with synthetic adjacent tiles... not the user's destination"): calls {@code
     * RoadTileSource.waysForCell} DIRECTLY for only one cell -- bypassing {@code
     * EarthGeneratorPrepareMixin} entirely, simulating its pre-fix single-cell-only reach -- and
     * confirms a real mapped way whose geometry crosses into the untouched neighbor cell is not
     * found there, purely because that neighbor cell was never fetched.
     */
    @Test
    void aCrossingRoadIsSilentlyOmittedAtAnUnfetchedNeighborsBoundary(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        // A synthetic way crossing the real cell boundary at lat=5.01 (CELL_DEGREES=0.01): its
        // southern point at lat=5.006 is in cell 500_-3001, its northern point at lat=5.016 is in
        // the adjacent cell 501_-3001. A real Overpass-shaped response returns the WHOLE way's
        // geometry for either cell's bbox query, same as the real provider would.
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

            // Only cell 500_-3001 (containing 5.008,-30.004) is fetched -- the pre-fix mixin's own
            // narrow reach, exercised directly here, never through EarthGeneratorPrepareMixin.
            tileSource.waysForCell(5.008, -30.004);

            EarthGenerator generator = bootAndBuildGenerator(demCache);
            int[] northColumn = generator.block(5.014, -30.004); // inside the adjacent, never-fetched cell 501_-3001, on the way's own line

            var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), northColumn[0], northColumn[1]);
            assertTrue(covering == null,
                    "Expected the mapped way to be silently omitted in the unfetched neighbor cell -- reproducing the live defect. Got " + covering);
        } finally {
            stub.stop(0);
        }
    }

    /**
     * The fix for the same comment 55454: {@code EarthGeneratorPrepareMixin} now prefetches the
     * full Moore neighborhood (not just the point's own cell), so the SAME crossing way from the
     * test above is no longer silently omitted once the real mixin (not a direct, bypassing call)
     * has run for a point in either cell.
     */
    @Test
    void theWidenedPrefetchFetchesTheNeighborSoACrossingRoadIsNotOmitted(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
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
            RoadTileSource tileSource = new RoadTileSource(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(10));
            RoadNetwork.setTileSourceForTests(tileSource);

            EarthGenerator generator = bootAndBuildGenerator(demCache);
            int[] southBlock = generator.block(6.008, -31.004); // inside cell 600_-3101

            // The real mixin, through Earth Platform's own prepare-ahead-of-generation entry point
            // -- this is what a real player's travel/pregen calls, not a direct bypassing call.
            generator.prepareNear(southBlock[0], southBlock[1]);
            assertTrue(requests.get() >= 2, "Expected the widened prefetch to fetch more than just the single center cell -- got " + requests.get() + " requests");

            int[] northColumn = generator.block(6.014, -31.004); // the adjacent cell 601_-3101, on the way's own line
            var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), northColumn[0], northColumn[1]);
            assertTrue(covering != null && covering.wayId() == 555021L,
                    "Expected the crossing way to be found in the neighbor cell after the widened prefetch, not silently omitted. Got " + covering);
        } finally {
            stub.stop(0);
        }
    }
}
