package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.globe.MercatorGrid;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MINECRAFT-586: the dedupe fix for MINECRAFT-584's measured bottleneck ({@code
 * RoadTileSource.readDiskCacheUncached} re-stating disk for every repeated lookup of a still-PENDING
 * cell, confirmed never memoized by {@link RoadTileSource}'s own design). The fix adds
 * {@link RoadNetwork.ScopedLookupCache}, a short-lived, caller-owned cache of
 * {@link RoadTileSource#cellRead} reads reused across repeated {@link
 * RoadNetwork#segmentCoveringColumn(MercatorGrid, int, int, RoadNetwork.ScopedLookupCache)} calls
 * within ONE generation pass -- never across passes.
 *
 * <p>Several earlier rounds (a version counter, an optimistic-read/validate retry loop, a
 * ref-counted publication-in-flight tracker) each tried to make it safe to reuse a cached
 * PENDING-derived answer across a window a real resolution could land in, and review kept finding
 * the next-narrower gap in each one. John Winstead's ruling (MINECRAFT-586 / shared Story
 * MINECRAFT-571 comment 56072) cut this off at the root instead: never cache (or reuse) a
 * PENDING-derived answer at all -- only ever a DEFINITIVE one ({@code READY} or {@code
 * CONFIRMED_EMPTY}, both stable facts for the rest of this {@link RoadTileSource} instance's
 * life) -- so there is no window to close, because there is nothing unsafe ever stored. See
 * {@link RoadNetwork.ScopedLookupCache}'s own doc for the full reasoning and the explicitly
 * accepted tradeoff: zero dedupe benefit for a cell that stays pending through an entire pass,
 * full (permanent, for the rest of the pass) benefit for one that resolves.
 *
 * <p>{@link #pendingCellResolvedByARealFetchDuringTheSamePassIsObservedNotMasked} is the core
 * regression: it resolves a cell via a REAL {@link RoadTileSource#waysForCell} call (the actual
 * production fetch path {@code attemptFetch} runs, exactly what a concurrent prefetch thread would
 * do) IN BETWEEN two lookups through the exact same {@link RoadNetwork.ScopedLookupCache}, and
 * proves the second lookup sees the resolution ("late-ready still paints") -- trivially true now
 * that a PENDING answer is never stored in the first place, but still the one regression this
 * whole feature exists to prevent.
 */
class RoadNetworkScopedLookupCacheTest {
    @AfterEach
    void cleanUp() {
        RoadNetwork.setTileSourceForTests(null);
    }

    @Test
    void pendingCellResolvedByARealFetchDuringTheSamePassIsObservedNotMasked(@TempDir Path tmp) throws Exception {
        MercatorGrid grid = new MercatorGrid(1);
        int x = 700_000, z = 700_000;
        var here = grid.geographic(x + 0.5, z + 0.5);

        // The way's geometry must actually cover column (x,z) under this exact grid for
        // segmentCoveringColumn to find it -- same centerline-through-the-point shape every other
        // fixture in this test file and RoadTileSourceTest's own sibling tests use.
        String response = """
                {"elements":[{"type":"way","id":777000111,"nodes":[1,2],
                  "tags":{"highway":"secondary","name":"Mid-Pass Resolution Street"},
                  "geometry":[{"lat":%s,"lon":%s},{"lat":%s,"lon":%s}]}]}
                """.formatted(here.latitude() - 0.0005, here.longitude(), here.latitude() + 0.0005, here.longitude());
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            Path regions = Files.createDirectories(tmp.resolve("regions")); // no bundled fixture: isolate to the live-tile-source path
            RoadNetwork.resetForTests(regions);
            var tiles = new RoadTileSource(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"),
                    tmp.resolve("road-cache"), true, Duration.ofSeconds(5));
            RoadNetwork.setTileSourceForTests(tiles);

            RoadNetwork.ScopedLookupCache cache = RoadNetwork.freshScopedLookupCache();
            assertNull(RoadNetwork.segmentCoveringColumn(grid, x, z, cache),
                    "A genuinely never-fetched cell must report no covering segment");

            // A REAL fetch, exactly the production path EarthGeneratorPrepareMixin's own
            // fetch-ahead-of-generation hook uses -- standing in for that hook running concurrently
            // on its own thread and completing in the middle of this column's own ring scan.
            tiles.waysForCell(here.latitude(), here.longitude());

            var found = RoadNetwork.segmentCoveringColumn(grid, x, z, cache);
            assertEquals(777000111L, found == null ? -1 : found.wayId(),
                    "A real fetch resolving this cell DURING the pass must be observed by the next "
                            + "lookup through the SAME scoped cache, not masked as still-pending/empty");
        } finally {
            stub.stop(0);
        }
    }

    @Test
    void aFreshScopedCacheAlwaysSeesCurrentDiskStateIncludingAnExternalLateWrite(@TempDir Path tmp) throws Exception {
        Path regions = Files.createDirectories(tmp.resolve("regions"));
        Path roadCache = tmp.resolve("road-cache");
        RoadNetwork.resetForTests(regions);
        var tiles = new RoadTileSource(URI.create("http://127.0.0.1:1/"), roadCache, false, Duration.ofSeconds(1));
        RoadNetwork.setTileSourceForTests(tiles);

        MercatorGrid grid = new MercatorGrid(1);
        int x = 500_000, z = 500_000; // far from any bundled region; this cell is genuinely never-fetched
        var here = grid.geographic(x + 0.5, z + 0.5);

        RoadNetwork.ScopedLookupCache cache = RoadNetwork.freshScopedLookupCache();
        assertNull(RoadNetwork.segmentCoveringColumn(grid, x, z, cache),
                "A genuinely never-fetched cell must report no covering segment");

        // Written directly to disk, bypassing RoadTileSource entirely (unlike the test above, this
        // is not a real production resolution path -- nothing else in this one JVM ever writes into
        // this instance's own cache root -- but it is still useful as the strongest possible check
        // that the NEXT pass's own fresh cache reads real, current disk state no matter how it got
        // there).
        long cellLat = (long) Math.floor(here.latitude() / RoadNetwork.CELL_DEGREES);
        long cellLon = (long) Math.floor(here.longitude() / RoadNetwork.CELL_DEGREES);
        String cellId = cellLat + "_" + cellLon;
        Files.createDirectories(roadCache);
        Files.writeString(roadCache.resolve(cellId + ".json"), """
                {"schema_version":1,"region_id":"late_arrival","bbox":{"min_lat":0,"min_lon":0,"max_lat":0,"max_lon":0},
                 "ways":[{"way_id":42424242,"way_tags":{"highway":"secondary","name":"Late Arrival Street"},
                   "centerline":[{"lat":%s,"lon":%s},{"lat":%s,"lon":%s}],"node_ids":[1,2]}]}
                """.formatted(here.latitude() - 0.0005, here.longitude(), here.latitude() + 0.0005, here.longitude()));

        RoadNetwork.ScopedLookupCache freshCache = RoadNetwork.freshScopedLookupCache();
        var found = RoadNetwork.segmentCoveringColumn(grid, x, z, freshCache);
        assertEquals(42424242L, found == null ? -1 : found.wayId(),
                "A FRESH scoped cache (the next generation pass) must see the real, now-resolved disk "
                        + "state -- the whole point of never persisting this cache across passes");

        // The no-cache overload (used by every caller not on this measured hot path) must agree too.
        var foundNoCache = RoadNetwork.segmentCoveringColumn(grid, x, z);
        assertEquals(42424242L, foundNoCache == null ? -1 : foundNoCache.wayId());
    }

    @Test
    void repeatedLookupsThroughTheSameScopedCacheAgreeWithTheUncachedPath(@TempDir Path tmp) throws Exception {
        Path regions = Files.createDirectories(tmp.resolve("regions"));
        Path roadCache = tmp.resolve("road-cache");
        RoadNetwork.resetForTests(regions);
        var tiles = new RoadTileSource(URI.create("http://127.0.0.1:1/"), roadCache, false, Duration.ofSeconds(1));
        RoadNetwork.setTileSourceForTests(tiles);

        MercatorGrid grid = new MercatorGrid(1);
        int x = 600_000, z = 600_000;
        var here = grid.geographic(x + 0.5, z + 0.5);
        long cellLat = (long) Math.floor(here.latitude() / RoadNetwork.CELL_DEGREES);
        long cellLon = (long) Math.floor(here.longitude() / RoadNetwork.CELL_DEGREES);
        Files.createDirectories(roadCache);
        Files.writeString(roadCache.resolve(cellLat + "_" + cellLon + ".json"), """
                {"schema_version":1,"region_id":"already_ready","bbox":{"min_lat":0,"min_lon":0,"max_lat":0,"max_lon":0},
                 "ways":[{"way_id":99999999,"way_tags":{"highway":"secondary","name":"Already Ready Street"},
                   "centerline":[{"lat":%s,"lon":%s},{"lat":%s,"lon":%s}],"node_ids":[1,2]}]}
                """.formatted(here.latitude() - 0.0005, here.longitude(), here.latitude() + 0.0005, here.longitude()));

        RoadNetwork.ScopedLookupCache cache = RoadNetwork.freshScopedLookupCache();
        // Several repeated lookups, as EarthGeneratorRoadMixin's own ring-scan makes for one column,
        // all sharing ONE cache -- every one of them must still agree with the real disk state.
        for (int i = 0; i < 5; i++) {
            var found = RoadNetwork.segmentCoveringColumn(grid, x, z, cache);
            assertEquals(99999999L, found == null ? -1 : found.wayId(),
                    "Repeated lookup #" + i + " through the same scoped cache must still find the already-ready way");
        }
    }

    /**
     * Best-effort stress coverage (not a substitute for the deterministic test above, which proves
     * the exact "resolved mid-pass" scenario on its own): one thread doing hundreds of reader
     * lookups through ONE scoped cache while a second thread concurrently resolves those same cells
     * one at a time, repeated across many distinct cells -- not proof of zero staleness during the
     * race (the reader can still see PENDING for a cell the resolver has not reached yet, which is
     * correct, not a bug), but verifies the cache never PERMANENTLY masks a result: every cell is
     * correctly resolved by the time both threads finish. This remains meaningful after MINECRAFT-586
     * comment 56072's redesign (PENDING answers are never cached at all) as retry/reopen coverage --
     * it is also a safety net against ever reintroducing PENDING caching by accident.
     */
    @Test
    void concurrentResolutionStressNeverPermanentlyMasksAnyCell(@TempDir Path tmp) throws Exception {
        int cellCount = 40;
        String[] responses = new String[cellCount];
        MercatorGrid grid = new MercatorGrid(1);
        int[] xs = new int[cellCount], zs = new int[cellCount];
        double[] lats = new double[cellCount], lons = new double[cellCount];
        for (int i = 0; i < cellCount; i++) {
            xs[i] = 800_000 + i * 2_000; // spaced well apart: distinct cells, no neighborhood overlap between them
            zs[i] = 800_000 + i * 2_000;
            var here = grid.geographic(xs[i] + 0.5, zs[i] + 0.5);
            lats[i] = here.latitude();
            lons[i] = here.longitude();
            responses[i] = """
                    {"elements":[{"type":"way","id":%d,"nodes":[1,2],
                      "tags":{"highway":"secondary","name":"Stress Street %d"},
                      "geometry":[{"lat":%s,"lon":%s},{"lat":%s,"lon":%s}]}]}
                    """.formatted(900_000_000L + i, i, lats[i] - 0.0005, lons[i], lats[i] + 0.0005, lons[i]);
        }

        // The resolver thread below calls tiles.waysForCell sequentially, one blocking HTTP round
        // trip at a time (RoadTileSource.fetchCell serializes every real send under its own
        // networkLock) -- so requests arrive in exactly index order 0..cellCount-1, making a plain
        // request counter a reliable (if fixture-specific) way to answer each one correctly.
        var requestIndex = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            int i = requestIndex.getAndIncrement();
            byte[] body = (i < cellCount ? responses[i] : "{\"elements\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            Path regions = Files.createDirectories(tmp.resolve("regions"));
            RoadNetwork.resetForTests(regions);
            var tiles = new RoadTileSource(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"),
                    tmp.resolve("road-cache"), true, Duration.ofSeconds(5));
            RoadNetwork.setTileSourceForTests(tiles);

            RoadNetwork.ScopedLookupCache cache = RoadNetwork.freshScopedLookupCache();
            AtomicBoolean readerFailed = new AtomicBoolean(false);
            AtomicBoolean keepReading = new AtomicBoolean(true);
            CountDownLatch resolverDone = new CountDownLatch(1);

            Thread reader = new Thread(() -> {
                while (keepReading.get()) {
                    for (int i = 0; i < cellCount; i++) {
                        try {
                            RoadNetwork.segmentCoveringColumn(grid, xs[i], zs[i], cache);
                        } catch (Throwable t) {
                            readerFailed.set(true);
                        }
                    }
                }
            });
            Thread resolver = new Thread(() -> {
                for (int i = 0; i < cellCount; i++) {
                    tiles.waysForCell(lats[i], lons[i]);
                }
                resolverDone.countDown();
            });

            reader.start();
            resolver.start();
            resolverDone.await();
            resolver.join();
            // Give the reader a little more time hammering the now-fully-resolved cells, then stop it.
            Thread.sleep(50);
            keepReading.set(false);
            reader.join(5_000);

            assertTrue(!readerFailed.get(), "Reader thread must never throw while racing the resolver");
            for (int i = 0; i < cellCount; i++) {
                var found = RoadNetwork.segmentCoveringColumn(grid, xs[i], zs[i], cache);
                assertEquals(900_000_000L + i, found == null ? -1 : found.wayId(),
                        "Cell " + i + " must be resolved (never permanently masked) once the resolver has finished");
            }
        } finally {
            stub.stop(0);
        }
    }

    /**
     * MINECRAFT-586 comment 56072 (John Winstead's ruling): "report disk reads separately for
     * resolved and pending cells; do not chase 1089->9 on pending cells at the expense of
     * correctness." Two distinct scenarios, measured with the exact same {@code
     * RoadSurfaceLayering#MAX_NEIGHBOR_DISTANCE}-ring, {@code 8*distance}-per-ring Chebyshev ring
     * scan (120 total column lookups, {@code EarthGeneratorRoadMixin.nearestLowerSameRoadDistance}'s
     * own real shape) MINECRAFT-571 comment 56032 asked be used as the evidence shape:
     * <ul>
     *   <li><b>Pending the whole scan</b> (never resolves): the scoped cache gives NO reduction --
     *       every lookup still pays its own real, uncached disk check, exactly the baseline cost,
     *       because a PENDING answer is never cached at all (56072's explicitly accepted
     *       tradeoff -- see {@link RoadNetwork.ScopedLookupCache}'s own doc).</li>
     *   <li><b>Already resolved before the scan starts</b> (the common case once a region has
     *       actually been fetched -- "most of the world has no roads," MINECRAFT-566 comment
     *       55562): already cheap (one real disk read, ever) thanks to {@code RoadTileSource}'s
     *       own PRE-EXISTING memory layer (MINECRAFT-566, not this ticket) -- there is nothing left
     *       for this ticket's own scoped cache to additionally save here, so this instead proves no
     *       REGRESSION: the real disk cost stays exactly as small whether or not a scoped cache is
     *       used.</li>
     * </ul>
     */
    @Test
    void scopedCacheGivesNoReductionForAPendingCellAndNoRegressionForAnAlreadyResolvedOne(@TempDir Path tmp) throws Exception {
        MercatorGrid grid = new MercatorGrid(1);
        int centerX = 300_000, centerZ = 300_000;

        // -- Pending scenario: no live source at all, so this cell can never resolve during the scan.
        Path regionsPendingUncached = Files.createDirectories(tmp.resolve("regions-pending-uncached"));
        RoadNetwork.resetForTests(regionsPendingUncached);
        var tilesPendingUncached = new RoadTileSource(URI.create("http://127.0.0.1:1/"), tmp.resolve("cache-pending-uncached"), false, Duration.ofSeconds(1));
        RoadNetwork.setTileSourceForTests(tilesPendingUncached);
        long beforePendingUncached = tilesPendingUncached.uncachedPendingReadCountForTests();
        runRingScan(grid, centerX, centerZ, null);
        long pendingUncachedReads = tilesPendingUncached.uncachedPendingReadCountForTests() - beforePendingUncached;

        Path regionsPendingCached = Files.createDirectories(tmp.resolve("regions-pending-cached"));
        RoadNetwork.resetForTests(regionsPendingCached);
        var tilesPendingCached = new RoadTileSource(URI.create("http://127.0.0.1:1/"), tmp.resolve("cache-pending-cached"), false, Duration.ofSeconds(1));
        RoadNetwork.setTileSourceForTests(tilesPendingCached);
        long beforePendingCached = tilesPendingCached.uncachedPendingReadCountForTests();
        runRingScan(grid, centerX, centerZ, RoadNetwork.freshScopedLookupCache());
        long pendingCachedReads = tilesPendingCached.uncachedPendingReadCountForTests() - beforePendingCached;

        assertTrue(pendingUncachedReads > 500,
                "Sanity check on the pending baseline itself: expected close to the ticket's own "
                        + "up-to-~1089 ceiling for a 120-lookup ring scan, got " + pendingUncachedReads);
        assertEquals(pendingUncachedReads, pendingCachedReads,
                "A cell that never resolves must get NO reduction at all -- a PENDING answer is never "
                        + "cached, by design (56072's explicitly accepted tradeoff), so cached and "
                        + "uncached pending disk-read counts must be equal, not merely close");

        // -- Resolved scenario: the center cell's own disk cache is already READY before the scan
        // starts (seeded directly, same shape RoadTileSource itself would have written).
        long centerCellLat = (long) Math.floor(grid.geographic(centerX + 0.5, centerZ + 0.5).latitude() / RoadNetwork.CELL_DEGREES);
        long centerCellLon = (long) Math.floor(grid.geographic(centerX + 0.5, centerZ + 0.5).longitude() / RoadNetwork.CELL_DEGREES);
        String seededReadyJson = """
                {"schema_version":1,"region_id":"ring-scan-ready","bbox":{"min_lat":0,"min_lon":0,"max_lat":0,"max_lon":0},
                 "ways":[{"way_id":888000111,"way_tags":{"highway":"secondary","name":"Ring Scan Ready Street"},
                   "centerline":[{"lat":0.0001,"lon":0.0001},{"lat":0.0002,"lon":0.0002}],"node_ids":[1,2]}]}
                """;

        Path cacheResolvedUncached = tmp.resolve("cache-resolved-uncached");
        Files.createDirectories(cacheResolvedUncached);
        Files.writeString(cacheResolvedUncached.resolve(centerCellLat + "_" + centerCellLon + ".json"), seededReadyJson);
        Path regionsResolvedUncached = Files.createDirectories(tmp.resolve("regions-resolved-uncached"));
        RoadNetwork.resetForTests(regionsResolvedUncached);
        var tilesResolvedUncached = new RoadTileSource(URI.create("http://127.0.0.1:1/"), cacheResolvedUncached, false, Duration.ofSeconds(1));
        RoadNetwork.setTileSourceForTests(tilesResolvedUncached);
        long resolvedUncachedBefore = diskReadsForResolvedCellsForTests(tilesResolvedUncached);
        runRingScan(grid, centerX, centerZ, null);
        long resolvedUncachedReads = diskReadsForResolvedCellsForTests(tilesResolvedUncached) - resolvedUncachedBefore;

        Path cacheResolvedCached = tmp.resolve("cache-resolved-cached");
        Files.createDirectories(cacheResolvedCached);
        Files.writeString(cacheResolvedCached.resolve(centerCellLat + "_" + centerCellLon + ".json"), seededReadyJson);
        Path regionsResolvedCached = Files.createDirectories(tmp.resolve("regions-resolved-cached"));
        RoadNetwork.resetForTests(regionsResolvedCached);
        var tilesResolvedCached = new RoadTileSource(URI.create("http://127.0.0.1:1/"), cacheResolvedCached, false, Duration.ofSeconds(1));
        RoadNetwork.setTileSourceForTests(tilesResolvedCached);
        long resolvedCachedBefore = diskReadsForResolvedCellsForTests(tilesResolvedCached);
        runRingScan(grid, centerX, centerZ, RoadNetwork.freshScopedLookupCache());
        long resolvedCachedReads = diskReadsForResolvedCellsForTests(tilesResolvedCached) - resolvedCachedBefore;

        // MINECRAFT-566's own pre-existing (not this ticket's) memory layer already memoizes a
        // resolved cell's disk read permanently after the first validating read, with or without
        // ScopedLookupCache -- so there is nothing LEFT for this ticket's own cache to additionally
        // save here; the real benefit it adds is entirely for the pending case above. What matters
        // for a resolved cell is that this ticket's own cache causes no REGRESSION: the real disk
        // cost must stay exactly as small, cached or not.
        assertTrue(resolvedUncachedReads <= 9,
                "Sanity check: MINECRAFT-566's own memory layer should already keep this well below "
                        + "the ticket's up-to-1089 PENDING ceiling even with no scoped cache at all, got "
                        + resolvedUncachedReads);
        assertEquals(resolvedUncachedReads, resolvedCachedReads,
                "Expected no regression for an already-resolved cell: its real disk cost is already "
                        + "minimal thanks to RoadTileSource's own pre-existing memory layer, independent "
                        + "of this ticket's scoped cache, so cached and uncached must match exactly");
    }

    /** Every disk-level read {@link RoadFetchTelemetry} attributes to a RESOLVED (ready or confirmed-empty) cell -- the resolved-cell counterpart to {@code uncachedPendingReadCountForTests}. */
    private static long diskReadsForResolvedCellsForTests(RoadTileSource tiles) {
        RoadFetchTelemetry.Snapshot snapshot = tiles.telemetry().snapshot();
        return snapshot.jsonPayloadReads() + snapshot.emptyMarkerPayloadReads() + snapshot.metadataZeroByteChecks();
    }

    /** {@code EarthGeneratorRoadMixin.nearestLowerSameRoadDistance}'s own ring-scan shape, reused here for measurement only. */
    private static void runRingScan(MercatorGrid grid, int x, int z, RoadNetwork.ScopedLookupCache cache) {
        RoadNetwork.segmentCoveringColumn(grid, x, z, cache);
        for (int distance = 1; distance <= RoadSurfaceLayering.MAX_NEIGHBOR_DISTANCE; distance++) {
            for (int ddx = -distance; ddx <= distance; ddx++) {
                for (int ddz = -distance; ddz <= distance; ddz++) {
                    if (Math.max(Math.abs(ddx), Math.abs(ddz)) != distance) continue; // only this ring, not the filled square
                    RoadNetwork.segmentCoveringColumn(grid, x + ddx, z + ddz, cache);
                }
            }
        }
    }
}
