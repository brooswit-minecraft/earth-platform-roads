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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MINECRAFT-586: the dedupe fix for MINECRAFT-584's measured bottleneck ({@code
 * RoadTileSource.readDiskCacheUncached} re-stating disk for every repeated lookup of a still-PENDING
 * cell, confirmed never memoized by {@link RoadTileSource}'s own design). The fix adds
 * {@link RoadNetwork.ScopedLookupCache}, a short-lived, caller-owned cache of
 * {@link RoadTileSource#cachedWaysForCellOrEmpty} reads reused across repeated {@link
 * RoadNetwork#segmentCoveringColumn(MercatorGrid, int, int, RoadNetwork.ScopedLookupCache)} calls
 * within ONE generation pass -- never across passes.
 *
 * <p>Two earlier versions were rejected by review for the same underlying reason -- any
 * snapshot-then-use sequence that is not atomic with a real resolution can be invalidated by a
 * resolution landing in its own gap, no matter how narrow that gap is made (round 1: checked
 * {@code resolutionVersion()} once before the whole 3x3 read; round 2: checked it again before each
 * individual read, still not atomic with the read itself). The actual fix wraps the WHOLE
 * Moore-neighborhood read in an optimistic-read/validate loop (the same pattern {@link
 * java.util.concurrent.locks.StampedLock}'s {@code tryOptimisticRead} offers, applied by hand against
 * {@link RoadTileSource#resolutionVersion()}): read the version before, do the whole read, read the
 * version after; if unchanged, that is a PROOF nothing resolved during the entire read, not merely a
 * narrow-window argument; if changed, discard everything and retry. See
 * {@link RoadNetwork.ScopedLookupCache}'s own doc for the full reasoning.
 *
 * <p>{@link #pendingCellResolvedByARealFetchDuringTheSamePassIsObservedNotMasked} is the regression
 * the self-review required: it resolves the cell via a REAL {@link RoadTileSource#waysForCell} call
 * (the actual production fetch path {@code attemptFetch} runs, exactly what a concurrent prefetch
 * thread would do) IN BETWEEN two lookups through the exact same {@link RoadNetwork.ScopedLookupCache},
 * and proves the second lookup sees the resolution, not a masked PENDING answer.
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
     * MINECRAFT-586 round 2's own explicit ask ("add a deterministic regression that forces
     * resolution after validation and before the cached neighbor is read") cannot literally be
     * satisfied: per {@link RoadNetwork.ScopedLookupCache}'s own updated doc, the remaining gap is
     * between two adjacent, non-blocking memory reads with no safepoint in between -- there is no
     * seam in the production code to deterministically land a resolution inside that exact window
     * without instrumenting internal implementation details purely for a test, which this fix
     * deliberately avoids doing. What IS achievable, and is exercised here: a best-effort stress
     * test hammering many distinct cells with one thread doing hundreds of reader lookups through
     * ONE scoped cache while a second thread concurrently resolves those same cells one at a time,
     * repeated across many trials -- not proof of zero staleness during the race (that would require
     * either the deterministic seam above or a real lock, neither of which this fix adds), but
     * verifies the cache never permanently poisons a result: every cell is correctly resolved by the
     * time both threads finish, every trial.
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
     * MINECRAFT-571 comment 56032 / Wroosbit's review (comment 56040): "show that the corrected code
     * still reduces disk reads rather than just avoiding the regression" -- direct, exact evidence
     * via {@link RoadTileSource#uncachedPendingReadCountForTests()} (not inferred from wall-clock
     * timing), simulating {@code EarthGeneratorRoadMixin.nearestLowerSameRoadDistance}'s own real
     * shape: a {@value RoadSurfaceLayering#MAX_NEIGHBOR_DISTANCE}-ring, {@code 8*distance}-per-ring
     * Chebyshev scan (120 total column lookups), each a real {@code segmentCoveringColumn} call with
     * its own 3x3 Moore-neighborhood read -- for a cell that never resolves (PENDING throughout,
     * exactly the common case MINECRAFT-584 measured as the hottest leaf cost).
     */
    @Test
    void scopedCacheStillSubstantiallyReducesUncachedPendingDiskReadsForARingScan(@TempDir Path tmp) throws Exception {
        MercatorGrid grid = new MercatorGrid(1);
        int centerX = 300_000, centerZ = 300_000;

        Path regionsUncached = Files.createDirectories(tmp.resolve("regions-uncached"));
        RoadNetwork.resetForTests(regionsUncached);
        var tilesUncached = new RoadTileSource(URI.create("http://127.0.0.1:1/"), tmp.resolve("cache-uncached"), false, Duration.ofSeconds(1));
        RoadNetwork.setTileSourceForTests(tilesUncached);
        long beforeUncached = tilesUncached.uncachedPendingReadCountForTests();
        runRingScan(grid, centerX, centerZ, null);
        long uncachedReads = tilesUncached.uncachedPendingReadCountForTests() - beforeUncached;

        Path regionsCached = Files.createDirectories(tmp.resolve("regions-cached"));
        RoadNetwork.resetForTests(regionsCached);
        var tilesCached = new RoadTileSource(URI.create("http://127.0.0.1:1/"), tmp.resolve("cache-cached"), false, Duration.ofSeconds(1));
        RoadNetwork.setTileSourceForTests(tilesCached);
        long beforeCached = tilesCached.uncachedPendingReadCountForTests();
        runRingScan(grid, centerX, centerZ, RoadNetwork.freshScopedLookupCache());
        long cachedReads = tilesCached.uncachedPendingReadCountForTests() - beforeCached;

        assertTrue(uncachedReads > 500,
                "Sanity check on the uncached baseline itself: expected close to the ticket's own up-to-~1089 "
                        + "ceiling for a 120-lookup ring scan, got " + uncachedReads);
        assertTrue(cachedReads <= 9,
                "Expected the scoped cache to collapse this ring scan's up to 1089 uncached PENDING "
                        + "reads down to at most the one real 3x3 neighborhood's worth (9), got " + cachedReads);
        assertTrue(cachedReads < uncachedReads / 10,
                "Expected at least a 10x reduction; uncached=" + uncachedReads + " cached=" + cachedReads);
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

    /**
     * MINECRAFT-586: Tayne's tie-break (Jira comment 56050) on the round-5 dispute -- the
     * production publication sequence IS correct (disk write, then version bump, then {@code
     * memory.put}), but the earlier stress test only raced it non-deterministically. This test
     * pauses the REAL {@code attemptFetch} production path at the two exact boundaries review named,
     * using {@link RoadTileSource#setPublicationHooksForTests}, rather than inferring correctness
     * from timing:
     * <ol>
     *   <li>Prime a genuinely-PENDING entry into ONE {@link RoadNetwork.ScopedLookupCache}, reused
     *       for every check below ("the same scope").</li>
     *   <li>Start a real fetch on a background thread; pause it immediately after the atomic disk
     *       move but before {@code resolutionVersion} bumps. At this pause, the SAME scoped cache
     *       (deterministically, since the version has not moved) still reports no segment -- this is
     *       the documented, bounded within-pass staleness window, not a bug. A DIRECT, uncached check
     *       at this exact pause (bypassing the scoped cache) already finds {@link
     *       RoadTileSource#cellState} reporting {@code READY}, confirming the real invariant: the
     *       disk write always precedes the version bump, so the version counter never runs AHEAD of
     *       the real data -- only the scoped cache's own primed, not-yet-invalidated copy is behind,
     *       and only until the version it's gated on actually moves.</li>
     *   <li>Release; pause again immediately after the version bump but before {@code memory.put}.
     *       At THIS pause, the same scoped cache must already see the real, resolved way -- the
     *       version change forces a fresh read, which falls through to a direct disk check (the
     *       atomic move already happened) rather than waiting on {@code memory.put}.</li>
     *   <li>Release; after the fetch fully completes, the lookup must still see the resolved way.</li>
     * </ol>
     */
    @Test
    void latchDrivenProductionPathRegressionPendingDuringMoveWindowReadyAfterBumpWindow(@TempDir Path tmp) throws Exception {
        MercatorGrid grid = new MercatorGrid(1);
        int x = 750_000, z = 750_000;
        var here = grid.geographic(x + 0.5, z + 0.5);
        long wayId = 850_000_111L;

        String response = """
                {"elements":[{"type":"way","id":%d,"nodes":[1,2],
                  "tags":{"highway":"secondary","name":"Latch Pause Street"},
                  "geometry":[{"lat":%s,"lon":%s},{"lat":%s,"lon":%s}]}]}
                """.formatted(wayId, here.latitude() - 0.0005, here.longitude(), here.latitude() + 0.0005, here.longitude());
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
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

            CountDownLatch atFirstPause = new CountDownLatch(1);
            CountDownLatch releaseFirstPause = new CountDownLatch(1);
            CountDownLatch atSecondPause = new CountDownLatch(1);
            CountDownLatch releaseSecondPause = new CountDownLatch(1);
            tiles.setPublicationHooksForTests(
                    () -> { atFirstPause.countDown(); awaitUninterruptibly(releaseFirstPause); },
                    () -> { atSecondPause.countDown(); awaitUninterruptibly(releaseSecondPause); });

            // Prime a genuinely-pending entry in ONE scoped cache, reused for every check below.
            RoadNetwork.ScopedLookupCache cache = RoadNetwork.freshScopedLookupCache();
            assertNull(RoadNetwork.segmentCoveringColumn(grid, x, z, cache),
                    "Priming read: cell must be genuinely PENDING before any fetch starts");

            Thread resolver = new Thread(() -> tiles.waysForCell(here.latitude(), here.longitude()));
            resolver.setDaemon(true);
            resolver.start();

            // First pause: the atomic disk move has happened; resolutionVersion has NOT bumped yet.
            assertTrue(atFirstPause.await(5, TimeUnit.SECONDS), "Resolver never reached the first pause");
            assertNull(RoadNetwork.segmentCoveringColumn(grid, x, z, cache),
                    "Before the version bump, the PRIMED scoped cache is expected to still report no "
                            + "segment -- the documented, bounded within-pass staleness window, not a bug. "
                            + "Critically this must never be a FINAL answer, proven by the direct cellState "
                            + "check below (a fresh, uncached read) and by the post-bump/post-completion "
                            + "checks that follow.");
            // A DIRECT, uncached check (bypassing the scoped cache entirely) already sees READY here --
            // the atomic disk move happened before this pause, and RoadTileSource's own cellState/
            // cachedWaysForCellOrEmpty always do a fresh disk check on a memory miss, never gated by
            // resolutionVersion. This is the real invariant the whole design leans on: the version
            // counter is a conservative change-detection signal that never runs AHEAD of the real data,
            // so it is safe for a cache to treat "version unchanged" as "nothing changed" -- the exact
            // opposite order (version ahead of data) is what would be unsafe, and is not what happens.
            assertEquals(RoadTileSource.CellState.READY, tiles.cellState(here.latitude(), here.longitude()),
                    "A direct, uncached cellState check must already see READY here -- the disk write "
                            + "happened before this pause -- confirming the real data is never behind the "
                            + "version signal, only the scoped cache's OWN stale copy (primed earlier) is");
            releaseFirstPause.countDown();

            // Second pause: resolutionVersion HAS bumped; memory.put has NOT happened yet.
            assertTrue(atSecondPause.await(5, TimeUnit.SECONDS), "Resolver never reached the second pause");
            var atSecondPauseResult = RoadNetwork.segmentCoveringColumn(grid, x, z, cache);
            assertEquals(wayId, atSecondPauseResult == null ? -1 : atSecondPauseResult.wayId(),
                    "The instant the version bump is observable -- even though memory.put has not run "
                            + "yet -- the SAME scoped cache must already see the real, now-written data "
                            + "(it falls through to a fresh disk read on the memory miss), never a masked "
                            + "PENDING/empty answer");
            releaseSecondPause.countDown();

            resolver.join(5_000);

            var afterComplete = RoadNetwork.segmentCoveringColumn(grid, x, z, cache);
            assertEquals(wayId, afterComplete == null ? -1 : afterComplete.wayId(),
                    "Immediately after the fetch fully completes, the lookup must see the resolved way");
        } finally {
            stub.stop(0);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
