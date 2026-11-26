package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.earthplatform.geography.globe.MercatorGrid;
import java.net.URI;
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
 * <p>These tests prove the two things that actually matter for safety, not raw disk-stat counts
 * (not practically instrumentable without changing {@link RoadTileSource} itself, which this fix
 * deliberately does not touch): (1) a {@link RoadNetwork.ScopedLookupCache} produces the exact same
 * answer a direct, uncached lookup would, and (2) the bounded staleness window this introduces is
 * exactly what the design doc claims -- reusable only within the SAME cache instance, and never
 * masking a later pass's fresh read once a cell actually resolves (the exact "late-arrival
 * freshness" guarantee the ticket requires: no stale paint after data completes).
 */
class RoadNetworkScopedLookupCacheTest {
    @AfterEach
    void cleanUp() {
        RoadNetwork.setTileSourceForTests(null);
    }

    @Test
    void sameScopedCacheStaysStaleWithinOnePassButAFreshCacheSeesTheRealLateArrival(@TempDir Path tmp) throws Exception {
        Path regions = Files.createDirectories(tmp.resolve("regions")); // no bundled fixture: isolate to the live-tile-source path
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

        // The cell resolves later via some OTHER path entirely (mirrors RoadTileSourceTest's own
        // "pendingIsNeverCachedAndAFreshInstanceStillReadsTheRealDiskStateAfterAnEarlierInstanceSawPending"
        // -- written directly here to isolate this test from whatever mechanism would really do it).
        long cellLat = (long) Math.floor(here.latitude() / RoadNetwork.CELL_DEGREES);
        long cellLon = (long) Math.floor(here.longitude() / RoadNetwork.CELL_DEGREES);
        String cellId = cellLat + "_" + cellLon;
        Files.createDirectories(roadCache);
        Files.writeString(roadCache.resolve(cellId + ".json"), """
                {"schema_version":1,"region_id":"late_arrival","bbox":{"min_lat":0,"min_lon":0,"max_lat":0,"max_lon":0},
                 "ways":[{"way_id":42424242,"way_tags":{"highway":"secondary","name":"Late Arrival Street"},
                   "centerline":[{"lat":%s,"lon":%s},{"lat":%s,"lon":%s}],"node_ids":[1,2]}]}
                """.formatted(here.latitude() - 0.0005, here.longitude(), here.latitude() + 0.0005, here.longitude()));

        assertNull(RoadNetwork.segmentCoveringColumn(grid, x, z, cache),
                "Reusing the SAME scoped cache within one pass must not retroactively see a cell that "
                        + "resolved after this cache was created -- it is a cache of that one pass's own reads, "
                        + "not a live view; this is the documented, bounded staleness window, not a bug");

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
