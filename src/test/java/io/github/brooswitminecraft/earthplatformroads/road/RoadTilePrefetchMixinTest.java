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
     * MINECRAFT-566 comment 55460's genuinely different case: a road continuing as a SECOND OSM
     * way ID, sharing only a node with the first, whose own geometry lies entirely in a neighbor
     * cell the first cell's own bbox query never touched -- the read-side trick above cannot carry
     * this, since the second way's data was never returned by any fetch yet. Proves {@code
     * RoadTileSource.enqueueLookahead} (via the real mixin, several simulated prepare calls, the
     * real production-order rate limit -- never zeroed, never slept around) eventually reaches the
     * second way's own cell and finds it, without ever bursting more than one request per call.
     */
    @Test
    void aSuccessorWayInANeverDirectlyFetchedCellIsFoundAfterSeveralPrepareCalls(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        // Way A lives entirely in cell 4100_-10630 (lat in [41.00,41.01)); way B, a DIFFERENT OSM
        // way ID sharing only node 2 with A, lives entirely in the neighbor cell 4101_-10630 (lat
        // in [41.01,41.02)) -- a real Overpass bbox query for cell A's area would never return B at
        // all, since B's geometry never touches A's bbox.
        String responseA = """
                {"elements":[{"type":"way","id":555024,"nodes":[1,2],
                  "tags":{"highway":"secondary","name":"Successor Street A"},
                  "geometry":[{"lat":41.004,"lon":-106.295},{"lat":41.0095,"lon":-106.295}]}]}
                """;
        String responseB = """
                {"elements":[{"type":"way","id":555025,"nodes":[2,3],
                  "tags":{"highway":"secondary","name":"Successor Street B"},
                  "geometry":[{"lat":41.0105,"lon":-106.295},{"lat":41.016,"lon":-106.295}]}]}
                """;
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            int n = requests.incrementAndGet();
            // First real fetch answers for cell A (the point's own cell on the first prepare call);
            // every fetch after that is the lookahead queue catching up neighbors, eventually B's.
            byte[] body = (n == 1 ? responseA : responseB).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            // A short, real (nonzero) interval, so several simulated prepare calls (each advancing
            // real wall-clock time briefly) legitimately become eligible one at a time -- not the
            // zero-interval constructor, and no Thread.sleep used to fake out a real rate limit.
            RoadTileSource tileSource = new RoadTileSource(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(5),
                    null, Duration.ofMillis(50));
            RoadNetwork.setTileSourceForTests(tileSource);

            EarthGenerator generator = bootAndBuildGenerator(demCache);
            int[] ownBlock = generator.block(41.006, -106.295); // cell 4100_-10630, on way A

            // Several real prepare calls, as a player's ordinary travel would trigger repeatedly --
            // each one's own-cell fetch plus at most one opportunistic lookahead fetch.
            for (int i = 0; i < 6; i++) {
                generator.prepareNear(ownBlock[0], ownBlock[1]);
                Thread.sleep(60); // real wall-clock gap between calls, comfortably past the 50ms interval
            }
            assertTrue(requests.get() >= 2, "Expected more than just way A's own single fetch across several prepare calls -- got " + requests.get());

            int[] successorColumn = generator.block(41.013, -106.295); // cell 4101_-10630, on way B, never the "own" point
            var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), successorColumn[0], successorColumn[1]);
            assertTrue(covering != null && covering.wayId() == 555025L,
                    "Expected the successor way B to be found in its own cell after the lookahead queue caught up. Got " + covering);
        } finally {
            stub.stop(0);
        }
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
}
