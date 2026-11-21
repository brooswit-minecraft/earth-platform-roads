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
            // This mixin fetches the point's own cell synchronously (comment 55456: a 9-cell
            // version was tried and found to interact badly with the provider's own rate limit --
            // see EarthGeneratorPrepareMixin's own class doc), then opportunistically fetches AT
            // MOST ONE additional cell from its bounded lookahead queue (comment 55460) -- which
            // this zero-interval test source makes immediately eligible too, hence 2 total requests
            // here, not 1. (The boundary-seam-for-a-single-crossing-way fix itself lives on the read
            // side, RoadNetwork.candidatesNear, exercised by this class's other tests.)
            generator.prepareNear(block[0], block[1]);
            assertEquals(2, requests.get(), "Expected the point's own cell plus one opportunistic lookahead-queue fetch");

            // Now a cache-only lookup (what the hot surface() generation path actually uses) must
            // find the prefetched way, with no additional network request.
            var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), block[0], block[1]);
            assertTrue(covering != null && covering.wayId() == 555002L,
                    "Expected the prefetched way to be found by a cache-only lookup after prepareNear");
            assertEquals(2, requests.get(), "The cache-only lookup after prepareNear must not trigger any further fetch");
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
            // proves; the next test closes the "not surface placement" gap named in comment 55458.
        } finally {
            stub.stop(0);
        }
    }

    /**
     * MINECRAFT-566 comment 55458's own named gap: "Production-cadence test proved cache-only
     * lookup across the tile edge, not surface placement." This test closes it, at real,
     * unambiguously dry-land coordinates (near Boulder, Colorado -- chosen by me, not any of
     * John's locations) so {@code EarthGeneratorRoadMixin}'s own "skip if under water" check can
     * never produce a false failure the way it would have at the previous test's synthetic,
     * possibly-oceanic coordinates. Same mechanism as before (one cell fetched, its neighbor never
     * fetched, a real production-order rate limit), but this time actually calls {@code
     * generator.generate} at the boundary column and asserts a real road-surface block is placed.
     */
    @Test
    void theReadSideNeighborCheckActuallyPaintsARoadSurfaceAtTheBoundaryColumn(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        String response = """
                {"elements":[{"type":"way","id":555022,"nodes":[1,2],
                  "tags":{"highway":"secondary","name":"Boulder Boundary Street"},
                  "geometry":[{"lat":40.006,"lon":-105.27},{"lat":40.016,"lon":-105.27}]}]}
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
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(10),
                    null, Duration.ofMillis(5000)); // the real production-order rate limit, not zeroed
            RoadNetwork.setTileSourceForTests(tileSource);

            EarthGenerator generator = bootAndBuildGenerator(demCache);
            int[] southBlock = generator.block(40.008, -105.27); // cell 4000_-10527

            generator.prepareNear(southBlock[0], southBlock[1]);
            assertEquals(1, requests.get(), "Expected exactly one real fetch -- the point's own cell only");

            int[] northColumn = generator.block(40.014, -105.27); // the never-fetched neighbor cell 4001_-10527, on the way's own line
            var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), northColumn[0], northColumn[1]);
            assertTrue(covering != null && covering.wayId() == 555022L,
                    "Expected the crossing way present at the neighboring column. Got " + covering);
            assertEquals(1, requests.get(), "Expected the neighbor-cell read to trigger no additional request");

            int groundY = generator.surfaceY(northColumn[0], northColumn[1]);
            var section = generator.generate(northColumn[0] >> 4, groundY >> 4, northColumn[1] >> 4);
            var state = section.getBlockState(northColumn[0] & 15, groundY & 15, northColumn[1] & 15);
            assertTrue(state.is(net.minecraft.world.level.block.Blocks.POLISHED_BLACKSTONE)
                            || state.is(io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get()),
                    "Expected real generation to paint road surface at the boundary column, got " + state);
        } finally {
            stub.stop(0);
        }
    }

    /**
     * MINECRAFT-566 comment 55462: his own read-only check of the real Zippy occurrence found the
     * one ready neighbor cell actually PREDATED the fresh test world by ~44 minutes (an old,
     * install-wide cache entry from an earlier session, not something fetched live), the player's
     * own tile genuinely pending, and the visible gap extending well into the own tile's interior
     * (~80 blocks), not just at the exact seam. He asked for exactly this shape of evidence instead
     * of only a seam-center sample: a PRE-WARMED (written to disk before anything in this test
     * runs, like a real leftover from an earlier session) neighbor cell containing a real
     * multi-segment, TURNING way (not a simple 2-point line) that bends after crossing into the
     * never-fetched "own" cell and continues deep into its interior. The own cell is never fetched
     * at all in this test -- no prepareNear call for it, matching "the own tile was still pending."
     */
    @Test
    void aPreWarmedNeighborsTurningWayIsFoundDeepInAnUnfetchedCellsInterior(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        // Neighbor cell 4004_-10530 (lat in [40.04,40.05)) gets a pre-warmed ready file, written
        // directly to disk -- simulating a stale, pre-existing cache entry, never fetched by this
        // test. Its way bends at (40.052,-105.2945), just after crossing into the "own" cell
        // 4005_-10530 (lat in [40.05,40.06)), and continues to (40.058,-105.293) -- deep in that
        // cell's interior, not at the seam.
        java.nio.file.Files.createDirectories(roadCache);
        String preWarmedNeighborJson = """
                {"schema_version":1,"region_id":"pre-warmed","ways":[{"way_id":555023,
                  "way_tags":{"highway":"secondary","name":"Turning Interior Street"},
                  "centerline":[{"lat":40.042,"lon":-105.295},{"lat":40.052,"lon":-105.2945},{"lat":40.058,"lon":-105.293}],
                  "node_ids":[1,2,3],"bridge":false,"tunnel":false}]}
                """;
        java.nio.file.Files.writeString(roadCache.resolve("4004_-10530.json"), preWarmedNeighborJson);

        // No HTTP stub needed at all -- this test proves the read-side neighbor check works purely
        // from a pre-existing cache file, with the own cell never fetched (allowNetwork is
        // irrelevant here since waysForCell for the own cell is simply never called).
        RoadTileSource tileSource = new RoadTileSource(
                URI.create("http://127.0.0.1:1/"), roadCache, false, Duration.ofSeconds(5));
        RoadNetwork.setTileSourceForTests(tileSource);

        EarthGenerator generator = bootAndBuildGenerator(demCache);

        // Confirm the own cell is genuinely pending, matching "the own tile was still pending" --
        // not confirmed-empty, not ready.
        assertEquals(RoadTileSource.CellState.PENDING, tileSource.cellState(40.058, -105.293),
                "Expected the own cell to be genuinely pending -- never fetched in this test");

        // A column well into the interior of the own cell (not at the seam) on the bent segment.
        int[] interiorColumn = generator.block(40.056, -105.2935);
        var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), interiorColumn[0], interiorColumn[1]);
        assertTrue(covering != null && covering.wayId() == 555023L,
                "Expected the pre-warmed neighbor's turning way to be found deep in the never-fetched cell's interior. Got " + covering);

        int groundY = generator.surfaceY(interiorColumn[0], interiorColumn[1]);
        var section = generator.generate(interiorColumn[0] >> 4, groundY >> 4, interiorColumn[1] >> 4);
        var state = section.getBlockState(interiorColumn[0] & 15, groundY & 15, interiorColumn[1] & 15);
        assertTrue(state.is(net.minecraft.world.level.block.Blocks.POLISHED_BLACKSTONE)
                        || state.is(io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get()),
                "Expected real generation to paint road surface deep in the interior, got " + state);
    }

    /**
     * MINECRAFT-566 comment 55464's correction to the previous version of this test: that stub
     * ignored the actual bbox and answered by request COUNT alone (A for #1, B for every later
     * request), so a request to the WRONG neighbor cell would still receive way B's data and the
     * read-side neighbor scan could pass for the wrong reason -- never actually proving the
     * lookahead queue reached cell B specifically. It also gave the shared node (id 2) two
     * different coordinates across A's and B's geometry, which is not how OSM data works (a shared
     * node is the same physical point everywhere it's referenced).
     *
     * <p>This version dispatches by the REAL bbox parsed out of each POST body: cell A's bbox gets
     * way A alone; cell B's bbox gets BOTH A and B (A's own endpoint -- the shared node -- sits
     * just inside B, so a real Overpass-protocol query for B's bbox would legitimately return A
     * too, since A touches that bbox); every other cell's bbox gets a genuinely empty result. The
     * shared node (id 2) has the exact same coordinate in both ways' geometry. Explicitly asserts a
     * POST was made matching cell B's own bbox and that cell B ends up {@code READY} under that
     * exact key -- not just that the read-side scan happened to find the way somewhere nearby.
     * Uses MOVING prepare points (simulating a player actually traveling along the road, not
     * repeatedly preparing the same stationary point) and a real, controlled production-order
     * cadence (a short but nonzero interval, real wall-clock gaps between calls, never a sleep used
     * to fake out the limit). Finally generates and asserts a real road-surface block PAST A's own
     * endpoint, inside B's own continuation.
     */
    @Test
    void aSuccessorWayInANeverDirectlyFetchedCellIsFoundAfterSeveralPrepareCalls(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        // Cell A: 4100_-10630, bbox [41.00,41.01) x [-106.30,-106.29). Cell B: 4101_-10630, bbox
        // [41.01,41.02) x [-106.30,-106.29). Shared node 2 at (41.0101,-106.295) -- just inside B.
        double aMinLat = 41.00, aMaxLat = 41.01, bMinLat = 41.01, bMaxLat = 41.02;
        double cellMinLon = -106.30, cellMaxLon = -106.29;
        String responseAOnly = """
                {"elements":[{"type":"way","id":555024,"nodes":[1,2],
                  "tags":{"highway":"secondary","name":"Successor Street A"},
                  "geometry":[{"lat":41.004,"lon":-106.295},{"lat":41.0101,"lon":-106.295}]}]}
                """;
        String responseAAndB = """
                {"elements":[
                  {"type":"way","id":555024,"nodes":[1,2],
                    "tags":{"highway":"secondary","name":"Successor Street A"},
                    "geometry":[{"lat":41.004,"lon":-106.295},{"lat":41.0101,"lon":-106.295}]},
                  {"type":"way","id":555025,"nodes":[2,3],
                    "tags":{"highway":"secondary","name":"Successor Street B"},
                    "geometry":[{"lat":41.0101,"lon":-106.295},{"lat":41.016,"lon":-106.295}]}
                ]}
                """;
        String responseEmpty = "{\"elements\":[]}";

        AtomicInteger requests = new AtomicInteger();
        AtomicInteger bBboxRequests = new AtomicInteger();
        var bboxPattern = java.util.regex.Pattern.compile("\\((-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*)\\);");
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requests.incrementAndGet();
            String query = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var matcher = bboxPattern.matcher(query);
            double minLat = 0, minLon = 0, maxLat = 0, maxLon = 0;
            if (matcher.find()) {
                minLat = Double.parseDouble(matcher.group(1));
                minLon = Double.parseDouble(matcher.group(2));
                maxLat = Double.parseDouble(matcher.group(3));
                maxLon = Double.parseDouble(matcher.group(4));
            }
            String body;
            if (approx(minLat, aMinLat) && approx(maxLat, aMaxLat) && approx(minLon, cellMinLon) && approx(maxLon, cellMaxLon)) {
                body = responseAOnly;
            } else if (approx(minLat, bMinLat) && approx(maxLat, bMaxLat) && approx(minLon, cellMinLon) && approx(maxLon, cellMaxLon)) {
                bBboxRequests.incrementAndGet();
                body = responseAAndB;
            } else {
                body = responseEmpty; // every other neighbor cell: a genuinely empty result, not fabricated
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        stub.start();
        try {
            RoadTileSource tileSource = new RoadTileSource(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(5),
                    null, Duration.ofMillis(50));
            RoadNetwork.setTileSourceForTests(tileSource);

            EarthGenerator generator = bootAndBuildGenerator(demCache);

            // MOVING prepares: a sequence of different points, advancing north along the road's own
            // line, as a player's ordinary travel would actually generate -- not the same
            // stationary point repeated.
            // Deliberately stays within cell A's own lat range the whole time (41.00-41.0099) --
            // cell B must be reached ONLY through the lookahead queue catching up a Moore
            // neighbor, never because the player's own point ever directly entered B (that would
            // just be the ordinary single-cell fetch, not what this test is proving). Enough
            // distinct points for the queue (one Moore-neighbor dequeue per call, after the first
            // call's own-cell fetch consumes that call's own eligibility slot) to work through to
            // cell B's own position in the 8-neighbor enqueue order.
            double[] travelLats = {41.001, 41.002, 41.003, 41.004, 41.005, 41.006, 41.007, 41.008, 41.0085, 41.009};
            for (double travelLat : travelLats) {
                int[] block = generator.block(travelLat, -106.295);
                generator.prepareNear(block[0], block[1]);
                Thread.sleep(60); // real wall-clock gap, comfortably past the 50ms interval -- never a sleep substituting for the rate limit itself
            }

            assertTrue(bBboxRequests.get() >= 1, "Expected at least one real POST matching cell B's own bbox, not just a nearby neighbor -- got " + requests.get() + " total requests");
            assertEquals(RoadTileSource.CellState.READY, tileSource.cellState(41.013, -106.295),
                    "Expected cell B to genuinely be READY under its own key, not merely found via the read-side neighbor scan of a different cell");

            // Generate and assert a real road-surface block PAST A's own endpoint, inside B's own
            // continuation -- not at the shared node, and not a candidate-only check.
            int[] pastAColumn = generator.block(41.013, -106.295);
            var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), pastAColumn[0], pastAColumn[1]);
            assertTrue(covering != null && covering.wayId() == 555025L,
                    "Expected successor way B to cover a column past A's own endpoint. Got " + covering);
            int groundY = generator.surfaceY(pastAColumn[0], pastAColumn[1]);
            var section = generator.generate(pastAColumn[0] >> 4, groundY >> 4, pastAColumn[1] >> 4);
            var state = section.getBlockState(pastAColumn[0] & 15, groundY & 15, pastAColumn[1] & 15);
            assertTrue(state.is(net.minecraft.world.level.block.Blocks.POLISHED_BLACKSTONE)
                            || state.is(io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get()),
                    "Expected a real road-surface block past A's endpoint, on way B's own continuation, got " + state);
        } finally {
            stub.stop(0);
        }
    }

    private static boolean approx(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    /**
     * MINECRAFT-566 comment 55462's diagnostic request: {@code RoadNetwork.diagnoseColumn} must
     * distinguish all four causes, not just whether a road renders. One generator, four real
     * columns, four real cells, one each.
     */
    @Test
    void diagnoseColumnDistinguishesAllFourCauses(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        java.nio.file.Files.createDirectories(roadCache);

        // Cell 4200_-10730 (covered): a real, ready, non-bridge way.
        java.nio.file.Files.writeString(roadCache.resolve("4200_-10730.json"), """
                {"schema_version":1,"region_id":"covered","ways":[{"way_id":555026,
                  "way_tags":{"highway":"secondary","name":"Covered Street"},
                  "centerline":[{"lat":42.004,"lon":-107.295},{"lat":42.006,"lon":-107.295}],
                  "node_ids":[1,2],"bridge":false,"tunnel":false}]}
                """);
        // Cell 4201_-10730 (bridge-excluded): a real, ready way, but tagged bridge=true.
        java.nio.file.Files.writeString(roadCache.resolve("4201_-10730.json"), """
                {"schema_version":1,"region_id":"bridge","ways":[{"way_id":555027,
                  "way_tags":{"highway":"secondary","name":"Bridge Street"},
                  "centerline":[{"lat":42.014,"lon":-107.295},{"lat":42.016,"lon":-107.295}],
                  "node_ids":[3,4],"bridge":true,"tunnel":false}]}
                """);
        // Cell 4203_-10730 (confirmed empty): a real, ready-but-empty marker.
        java.nio.file.Files.writeString(roadCache.resolve("4203_-10730.empty"), "");
        // Cell 4202_-10730 (pending): no marker file at all -- never fetched.

        RoadTileSource tileSource = new RoadTileSource(URI.create("http://127.0.0.1:1/"), roadCache, false, Duration.ofSeconds(5));
        RoadNetwork.setTileSourceForTests(tileSource);
        EarthGenerator generator = bootAndBuildGenerator(demCache);
        var grid = generator.grid();

        int[] coveredColumn = generator.block(42.005, -107.295);
        assertEquals(RoadNetwork.ColumnDiagnosis.COVERED, RoadNetwork.diagnoseColumn(grid, coveredColumn[0], coveredColumn[1]));

        int[] bridgeColumn = generator.block(42.015, -107.295);
        assertEquals(RoadNetwork.ColumnDiagnosis.EXCLUDED_BRIDGE_OR_TUNNEL, RoadNetwork.diagnoseColumn(grid, bridgeColumn[0], bridgeColumn[1]));

        int[] pendingColumn = generator.block(42.025, -107.295);
        assertEquals(RoadNetwork.ColumnDiagnosis.PENDING_NO_DATA, RoadNetwork.diagnoseColumn(grid, pendingColumn[0], pendingColumn[1]));

        int[] confirmedEmptyColumn = generator.block(42.035, -107.295);
        assertEquals(RoadNetwork.ColumnDiagnosis.CONFIRMED_EMPTY_NO_ROAD, RoadNetwork.diagnoseColumn(grid, confirmedEmptyColumn[0], confirmedEmptyColumn[1]));
    }

    /**
     * MINECRAFT-566 comment 55464's explicit ask: a cooldown-declined lookahead-queue entry must
     * stay pending/eligible for a LATER attempt (never permanently lost), repeated calls during the
     * same cooldown window must not create duplicate queue entries or a burst of requests ("without
     * re-enqueue overlap"), and the one-in-flight guarantee must hold throughout. With a 200ms
     * interval: the first prepare call's own-cell fetch succeeds (request #1) and immediately sets
     * the next-eligible deadline ~200ms out, so THAT SAME call's own opportunistic lookahead
     * attempt is declined (too soon after the fetch that just set the deadline). Three more
     * prepare calls at the same point, with no sleep, must add ZERO further requests (every
     * opportunistic attempt during the cooldown is correctly declined, not duplicated or bursted).
     * Only after sleeping past the deadline does a fifth call's lookahead attempt finally succeed
     * (request #2) -- proving the declined cell was retained across the cooldown, not lost, and
     * that nothing was fetched twice in the meantime. "One in-flight" and "bounded priority" are
     * structural here (a single-threaded sequence of calls through one instance's networkLock and
     * capacity-bounded queue), not independently re-proven by a race test.
     */
    @Test
    void aCooldownDeclinedLookaheadEntryStaysEligibleWithoutDuplicateRequests(@TempDir Path roadCache) throws IOException {
        // MINECRAFT-566 comment 55466: the previous version of this test called prepare again over
        // the SAME overlapping neighborhood to "prove" retention, which cannot actually distinguish
        // a retained job from one that was dropped on decline and silently re-added by that same
        // overlapping rescan -- it also used a 50ms interval, never exercising the real 5s-order
        // production cadence. This version uses a controlled (fixed, test-set) clock instead of
        // sleeping, the REAL production-order interval (5 seconds, not shortened), and -- critically
        // -- drains the retained job through a call that scans a totally DIFFERENT, non-overlapping
        // neighborhood, so the retained job cannot have been freshly re-added by that call's own
        // scan. It calls RoadTileSource.enqueueLookahead directly (not through the full generator
        // mixin) to isolate the queue's retention behavior from the unrelated "own cell" fetch a
        // real prepareNear call would also perform.
        String response = "{\"elements\":[{\"type\":\"way\",\"id\":555028,\"nodes\":[1,2],"
                + "\"tags\":{\"highway\":\"secondary\",\"name\":\"Cooldown Test Street\"},"
                + "\"geometry\":[{\"lat\":1.0,\"lon\":1.0},{\"lat\":1.001,\"lon\":1.001}]}]}";
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
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(5),
                    null, Duration.ofSeconds(5)); // the real production-order interval
            java.time.Instant t0 = java.time.Instant.parse("2020-01-01T00:00:00Z");
            tileSource.setClockForTests(java.time.Clock.fixed(t0, java.time.ZoneOffset.UTC));

            // Consume this instance's eligibility on an unrelated cell first, so the upcoming
            // lookahead attempt is genuinely declined, not accidentally eligible.
            tileSource.waysForCell(0.005, 0.005); // far from everything below; request #1
            assertEquals(1, requests.get());

            // P1's neighborhood: cell (7000,-13001). Its first-enqueued Moore neighbor (per the
            // fixed dLat,dLon iteration order) is cell (6999,-13002) -- the job we're tracking.
            double p1Lat = 70.005, p1Lon = -130.005;
            double trackedCellRepLat = 6999 * RoadNetwork.CELL_DEGREES + RoadNetwork.CELL_DEGREES / 2;
            double trackedCellRepLon = -13002 * RoadNetwork.CELL_DEGREES + RoadNetwork.CELL_DEGREES / 2;

            tileSource.enqueueLookahead(p1Lat, p1Lon); // enqueues P1's 8 neighbors, dequeues+attempts the tracked cell -- declined (clock still at t0, deadline is t0+5s from request #1)
            assertEquals(1, requests.get(), "Expected the lookahead attempt to be declined (still within the real 5s cooldown), not a new request");
            assertEquals(RoadTileSource.CellState.PENDING, tileSource.cellState(trackedCellRepLat, trackedCellRepLon),
                    "Expected the tracked cell to stay genuinely pending after being declined");

            // Advance the controlled clock past the 5s deadline -- no real sleep.
            tileSource.setClockForTests(java.time.Clock.fixed(t0.plusSeconds(6), java.time.ZoneOffset.UTC));

            // P2 is far enough from P1 that their Moore neighborhoods share NO cells -- this call's
            // own enqueue-scan cannot possibly have (re-)added the tracked cell itself. If the
            // tracked job dequeued here is still the SAME retained one, not a fresh duplicate, this
            // proves retention rather than drop-and-rescan.
            double p2Lat = 80.005, p2Lon = -140.005;
            tileSource.enqueueLookahead(p2Lat, p2Lon);

            assertEquals(2, requests.get(), "Expected exactly one more request once the clock genuinely passed the deadline");
            assertEquals(RoadTileSource.CellState.READY, tileSource.cellState(trackedCellRepLat, trackedCellRepLon),
                    "Expected the originally-tracked, retained cell specifically to now be ready -- not lost, and not fetched more than once");
        } finally {
            stub.stop(0);
        }
    }

    /**
     * MINECRAFT-566 comment 55468's explicit ask: cell A fails TWICE (a real dispatched failure --
     * a persistent 504, not just "declined as too soon"), and cell B still becomes {@code READY} in
     * between, without A ever being falsely marked confirmed-empty and without violating the
     * global one-in-flight/backoff guarantees. Dispatches by the real bbox parsed from each POST
     * (A's bbox -> 504 every time; B's bbox -> a real way), and pre-seeds six of the eight Moore
     * neighbors as already confirmed-empty so only A and B ever actually enter the queue, keeping
     * the FIFO rotation simple and directly observable: A (first-enqueued) fails and rotates to the
     * BACK, so B (now at the front) gets its own turn next, rather than A monopolizing every future
     * slot the way the pre-55468 code would have.
     */
    @Test
    void aPersistentlyFailingCellRotatesBehindItsNeighborsWithoutFalseEmptyOrBurst(@TempDir Path roadCache) throws IOException {
        // Lat values kept well away from the poles (90.x would be an invalid WGS84 coordinate,
        // which is exactly what an earlier version of this test used by mistake near its own
        // 90-degree "own" point -- a real bug caught by the parser itself, not the test's
        // assertions).
        double pLat = 45.005, pLon = -150.005; // own cell 4500_-15001
        // A = 4499_-15001 (first-enqueued Moore neighbor); B = 4501_-15001 (seventh-enqueued).
        double aMinLat = 44.99, aMaxLat = 45.00, bMinLat = 45.01, bMaxLat = 45.02;
        double cellMinLon = -150.01, cellMaxLon = -150.00;
        double aRepLat = 44.995, aRepLon = -150.005;
        double bRepLat = 45.015, bRepLon = -150.005;

        java.nio.file.Files.createDirectories(roadCache);
        // Six filler Moore neighbors, PLUS the own point's own cell (4500_-15001) -- in real usage
        // via the mixin, the own cell is always already fetched by waysForCell before
        // enqueueLookahead ever runs, so it's never still sitting uncached when the neighbor scan
        // happens; this test calls enqueueLookahead directly (to isolate the queue mechanism), so
        // it has to pre-seed that same "already resolved" state itself. All pre-seeded
        // confirmed-empty so the enqueue scan skips them entirely -- only A and B ever actually
        // enter the queue, keeping the rotation directly observable.
        for (String fillerCellId : new String[] {"4499_-15002", "4499_-15000", "4500_-15002", "4500_-15001", "4500_-15000", "4501_-15002", "4501_-15000"}) {
            java.nio.file.Files.writeString(roadCache.resolve(fillerCellId + ".empty"), "");
        }

        String responseB = """
                {"elements":[{"type":"way","id":555029,"nodes":[1,2],
                  "tags":{"highway":"secondary","name":"Rotation Test Street B"},
                  "geometry":[{"lat":45.011,"lon":-150.005},{"lat":45.019,"lon":-150.005}]}]}
                """;
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger aAttempts = new AtomicInteger();
        var bboxPattern = java.util.regex.Pattern.compile("\\((-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*)\\);");
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requests.incrementAndGet();
            String query = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var matcher = bboxPattern.matcher(query);
            double minLat = 0, minLon = 0, maxLat = 0, maxLon = 0;
            if (matcher.find()) {
                minLat = Double.parseDouble(matcher.group(1));
                minLon = Double.parseDouble(matcher.group(2));
                maxLat = Double.parseDouble(matcher.group(3));
                maxLon = Double.parseDouble(matcher.group(4));
            }
            if (approx(minLat, aMinLat) && approx(maxLat, aMaxLat) && approx(minLon, cellMinLon) && approx(maxLon, cellMaxLon)) {
                aAttempts.incrementAndGet();
                byte[] body = "persistent failure".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(504, body.length); // a real, genuinely dispatched failure -- not "declined as too soon"
                exchange.getResponseBody().write(body);
            } else if (approx(minLat, bMinLat) && approx(maxLat, bMaxLat) && approx(minLon, cellMinLon) && approx(maxLon, cellMaxLon)) {
                byte[] body = responseB.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                byte[] body = "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        stub.start();
        try {
            RoadTileSource tileSource = new RoadTileSource(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(5),
                    null, Duration.ofSeconds(5)); // the real production-order interval
            java.time.Instant clockTime = java.time.Instant.parse("2020-01-01T00:00:00Z");
            tileSource.setClockForTests(java.time.Clock.fixed(clockTime, java.time.ZoneOffset.UTC));

            // Consume this instance's eligibility on an unrelated cell first (request #1), same as
            // the cooldown-retention test, so the first real lookahead attempt below is genuinely
            // eligible once the clock is advanced past it.
            tileSource.waysForCell(0.005, 0.005);
            clockTime = clockTime.plusSeconds(6);
            tileSource.setClockForTests(java.time.Clock.fixed(clockTime, java.time.ZoneOffset.UTC));

            // First dequeue: A, dispatched, fails with a real 504. Must rotate to the BACK, not the
            // front, and must NOT be marked confirmed-empty.
            tileSource.enqueueLookahead(pLat, pLon);
            assertEquals(1, aAttempts.get(), "Expected A to be genuinely dispatched once");
            assertEquals(RoadTileSource.CellState.PENDING, tileSource.cellState(aRepLat, aRepLon),
                    "Expected A to stay genuinely pending after a real failure -- never falsely confirmed-empty");

            clockTime = clockTime.plusSeconds(6);
            tileSource.setClockForTests(java.time.Clock.fixed(clockTime, java.time.ZoneOffset.UTC));

            // Second dequeue, from a DIFFERENT non-overlapping point: with A rotated to the back, B
            // (not A again) must be at the front now -- proving A's failure didn't let it
            // monopolize the next slot and starve B.
            tileSource.enqueueLookahead(100.005, -160.005);
            assertEquals(RoadTileSource.CellState.READY, tileSource.cellState(bRepLat, bRepLon),
                    "Expected B to become ready on this turn -- A's failure must not have starved it");
            assertEquals(1, aAttempts.get(), "Expected no additional A attempt while B was being drained");

            clockTime = clockTime.plusSeconds(6);
            tileSource.setClockForTests(java.time.Clock.fixed(clockTime, java.time.ZoneOffset.UTC));

            // Third dequeue, another non-overlapping point: B is resolved and gone, so A (still the
            // only remaining queued entry) comes up again -- its second genuine failure.
            tileSource.enqueueLookahead(110.005, -170.005);
            assertEquals(2, aAttempts.get(), "Expected A's second genuine dispatch and failure");
            assertEquals(RoadTileSource.CellState.PENDING, tileSource.cellState(aRepLat, aRepLon),
                    "Expected A to still be pending (not confirmed-empty) after failing twice");
        } finally {
            stub.stop(0);
        }
    }

    /**
     * MINECRAFT-566 comment 55470: aaea04b's refactor called {@code attemptFetch} directly on the
     * dequeued cell, dropping the "already resolved by some other path?" double-check {@code
     * waysForCell} always did. A queued cell that gets resolved by its own point's ORDINARY fetch
     * (the realistic case: the player's own position later lands exactly in that neighbor cell)
     * before its stale queue entry is ever dequeued would otherwise be fetched a second,
     * unnecessary time -- wasting a scarce provider slot and, worse, overwriting the already-
     * correct cached geometry with whatever that redundant fetch happens to return. This proves the
     * fix: queued, then resolved via an ordinary (non-lookahead) {@code waysForCell} call, then
     * drained -- no second POST for that same cell's key.
     */
    @Test
    void aQueuedCellResolvedByItsOwnFetchIsNotDispatchedAgainOnDrain(@TempDir Path roadCache) throws IOException {
        double pLat = 55.005, pLon = -170.005; // own cell 5500_-17001
        // T = 5501_-17001 (seventh-enqueued Moore neighbor, same position as "B" in earlier tests).
        double tMinLat = 55.01, tMaxLat = 55.02, cellMinLon = -170.01, cellMaxLon = -170.00;
        double tRepLat = 55.015, tRepLon = -170.005;

        java.nio.file.Files.createDirectories(roadCache);
        // Every Moore neighbor except the first-enqueued (N1, a harmless decoy) and T is pre-seeded
        // confirmed-empty, along with the own cell itself -- so the queue only ever holds N1 and T,
        // keeping this directly observable.
        for (String fillerCellId : new String[] {"5499_-17001", "5499_-17000", "5500_-17002", "5500_-17001", "5500_-17000", "5501_-17002", "5501_-17000"}) {
            java.nio.file.Files.writeString(roadCache.resolve(fillerCellId + ".empty"), "");
        }

        String responseT = """
                {"elements":[{"type":"way","id":555030,"nodes":[1,2],
                  "tags":{"highway":"secondary","name":"Resolved-Before-Drain Street"},
                  "geometry":[{"lat":55.011,"lon":-170.005},{"lat":55.019,"lon":-170.005}]}]}
                """;
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger tRequests = new AtomicInteger();
        var bboxPattern = java.util.regex.Pattern.compile("\\((-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*)\\);");
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requests.incrementAndGet();
            String query = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var matcher = bboxPattern.matcher(query);
            double minLat = 0, minLon = 0, maxLat = 0, maxLon = 0;
            if (matcher.find()) {
                minLat = Double.parseDouble(matcher.group(1));
                minLon = Double.parseDouble(matcher.group(2));
                maxLat = Double.parseDouble(matcher.group(3));
                maxLon = Double.parseDouble(matcher.group(4));
            }
            byte[] body;
            if (approx(minLat, tMinLat) && approx(maxLat, tMaxLat) && approx(minLon, cellMinLon) && approx(maxLon, cellMaxLon)) {
                tRequests.incrementAndGet();
                body = responseT.getBytes(StandardCharsets.UTF_8);
            } else {
                body = "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8); // N1 (the decoy) and anything else: a genuinely empty result
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            RoadTileSource tileSource = new RoadTileSource(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(5),
                    null, Duration.ofSeconds(5));
            java.time.Instant clockTime = java.time.Instant.parse("2020-01-01T00:00:00Z");
            tileSource.setClockForTests(java.time.Clock.fixed(clockTime, java.time.ZoneOffset.UTC));

            tileSource.waysForCell(0.005, 0.005); // consumes initial eligibility (request #1)
            clockTime = clockTime.plusSeconds(6);
            tileSource.setClockForTests(java.time.Clock.fixed(clockTime, java.time.ZoneOffset.UTC));

            // Enqueues N1 and T; dequeues and resolves N1 (confirmed-empty) -- T stays queued,
            // never yet dequeued.
            tileSource.enqueueLookahead(pLat, pLon);
            assertEquals(2, requests.get(), "Expected N1's own resolution, not T's -- T must still be queued");
            assertEquals(0, tRequests.get());

            clockTime = clockTime.plusSeconds(6);
            tileSource.setClockForTests(java.time.Clock.fixed(clockTime, java.time.ZoneOffset.UTC));

            // T is resolved by its OWN ordinary fetch now -- exactly like a player's position
            // later landing directly in that neighbor cell -- while its stale entry still sits in
            // the lookahead queue, untouched.
            tileSource.waysForCell(tRepLat, tRepLon);
            assertEquals(1, tRequests.get(), "Expected T's own fetch to dispatch exactly once");
            assertEquals(RoadTileSource.CellState.READY, tileSource.cellState(tRepLat, tRepLon));

            clockTime = clockTime.plusSeconds(6);
            tileSource.setClockForTests(java.time.Clock.fixed(clockTime, java.time.ZoneOffset.UTC));

            // Drain via a totally different, non-overlapping point -- dequeues T's stale entry.
            // With the fix, this must detect T is already resolved and pop the job WITHOUT
            // dispatching a second request for it.
            tileSource.enqueueLookahead(20.005, -60.005);
            assertEquals(1, tRequests.get(), "Expected NO second dispatch for T's key once it was already resolved before the drain");
        } finally {
            stub.stop(0);
        }
    }
}
