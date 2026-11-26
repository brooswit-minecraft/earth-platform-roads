package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.globe.MercatorGrid;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * <p>An earlier version of this cache memoized a PENDING miss unconditionally, which a review
 * (self-review finding on PR #11) correctly rejected: it could mask a cell that resolves DURING the
 * same pass (a concurrent background prefetch, e.g. {@code EarthGeneratorPrepareMixin}'s own
 * fetch-ahead-of-generation path, completing mid-scan), violating the ticket's explicit "no stale
 * paint after data completes" / "never treat pending as confirmed empty" constraints. The fix
 * snapshots {@link RoadTileSource#resolutionVersion()} and self-invalidates the whole cache the
 * moment that counter moves -- see {@link RoadNetwork.ScopedLookupCache}'s own doc.
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
}
