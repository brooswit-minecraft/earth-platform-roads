package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link RoadTileSource} against a local HTTP stub (never the real, flaky public
 * Overpass endpoint -- this must be deterministic) covering exactly the behaviors MINECRAFT-566's
 * review named as the acceptance requirement: automatic fetch/cache, a cell staying "pinned" to
 * whatever was cached (a real restart re-reads the same cache, does not re-fetch), a genuinely
 * empty result staying confirmed-empty, and a failed/slow fetch leaving a cell retryable rather
 * than permanently marked as a roadless gap.
 */
class RoadTileSourceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    // Shaped like the official OSM API's map export (raw "node"/"way" elements, a way listing only
    // its nodes' IDs), not Overpass's "out geom" -- see RoadTileSource.fetchCell's javadoc for why
    // the default production fetch moved off Overpass this pass (three public mirrors were
    // unreachable this session; this endpoint answered immediately every time).
    private static final String ONE_WAY_RESPONSE = """
            {"elements":[
              {"type":"node","id":1,"lat":40.0,"lon":-105.0},
              {"type":"node","id":2,"lat":40.001,"lon":-105.001},
              {"type":"way","id":555001,"nodes":[1,2],
                "tags":{"highway":"secondary","name":"Stub Street"}}]}
            """;
    private static final String EMPTY_RESPONSE = """
            {"elements":[]}
            """;

    private HttpServer startStub(AtomicInteger requestCount, String response, int statusCode) throws IOException {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        this.server = stub;
        return stub;
    }

    private URI endpointOf(HttpServer stub) {
        return URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/");
    }

    @Test
    void aCacheMissFetchesAndCachesThenACacheHitDoesNotFetchAgain(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        var first = source.waysForCell(40.0005, -105.0005);
        assertEquals(1, first.size());
        assertEquals(555001L, first.get(0).wayId());
        assertEquals(1, requests.get(), "Expected exactly one fetch on the first, cache-miss call");

        var second = source.waysForCell(40.0005, -105.0005);
        assertEquals(1, second.size());
        assertEquals(1, requests.get(), "Expected no further fetch once this cell is cached in memory");
    }

    @Test
    void aFreshSourceOverTheSameCacheRootDoesNotRefetch(@TempDir Path cacheRoot) throws IOException {
        // Simulates a server restart: a brand-new RoadTileSource instance, same cache directory on
        // disk. This is what "pins" a world's road data to whatever was actually fetched.
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, ONE_WAY_RESPONSE, 200);
        new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5)).waysForCell(40.0005, -105.0005);
        assertEquals(1, requests.get());

        RoadTileSource restarted = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));
        var ways = restarted.waysForCell(40.0005, -105.0005);
        assertEquals(1, ways.size());
        assertEquals(1, requests.get(), "A fresh instance over the same cache root must read the disk cache, not refetch");
    }

    @Test
    void aGenuinelyEmptyResultStaysConfirmedEmptyOnRetry(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, EMPTY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        assertTrue(source.waysForCell(41.0, -106.0).isEmpty());
        assertEquals(1, requests.get());

        RoadTileSource restarted = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));
        assertTrue(restarted.waysForCell(41.0, -106.0).isEmpty());
        assertEquals(1, requests.get(), "A confirmed-empty cell must not be re-fetched on a later access");
    }

    @Test
    void aFailedFetchLeavesTheCellRetryableNotPermanentlyEmpty(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer failingStub = startStub(requests, "service unavailable", 503);
        RoadTileSource source = new RoadTileSource(endpointOf(failingStub), cacheRoot, true, Duration.ofSeconds(5));

        var duringOutage = source.waysForCell(42.0, -107.0);
        assertTrue(duringOutage.isEmpty(), "A failed fetch must not throw; the caller gets no road for this one call");
        assertEquals(1, requests.get());
        assertTrue(source.cachedWaysForCellOrEmpty(42.0, -107.0).isEmpty(),
                "Nothing is cached yet (neither ready nor confirmed-empty) after a failure");

        failingStub.stop(0);
        AtomicInteger recoveredRequests = new AtomicInteger();
        HttpServer recoveredStub = startStub(recoveredRequests, ONE_WAY_RESPONSE, 200);
        RoadTileSource restartedAfterRecovery = new RoadTileSource(endpointOf(recoveredStub), cacheRoot, true, Duration.ofSeconds(5));
        var afterRecovery = restartedAfterRecovery.waysForCell(42.0, -107.0);
        assertEquals(1, afterRecovery.size(), "A cell left pending by a failure must retry successfully once the source recovers");
        assertEquals(1, recoveredRequests.get());
    }

    // NOTE on a removed protection: c61b2d4 added two tests here for Overpass's "remark" convention
    // (a top-level "remark" string appearing on an otherwise-200 response to report a runtime error
    // mid-query). That protection was specific to Overpass's response shape and its "started a 200,
    // failed partway through" failure mode. Since the default production fetch (RoadTileSource.
    // fetchCell) now targets the official OSM API's map export instead -- a plain REST API that
    // reports real failures as real non-200 statuses, not an embedded "remark" on a 200 -- there is
    // no longer a reachable code path for that specific failure shape, so checkForErrorRemark and
    // its two tests were removed rather than left as dead, untested, misleading code. Disclosed
    // explicitly in the MINECRAFT-566 Jira comment announcing this fetch-source change: anyone who
    // overrides -Dearthplatformroads.osmApiEndpoint back to an Overpass URL loses that protection
    // (and, independently, the request/response shapes no longer match Overpass's "out geom" at
    // all, so such an override would not work correctly regardless).

    @Test
    void cachedWaysForCellOrEmptyNeverFetches(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        assertTrue(source.cachedWaysForCellOrEmpty(40.0005, -105.0005).isEmpty(), "Nothing cached yet; this read-only call must not fetch");
        assertEquals(0, requests.get(), "cachedWaysForCellOrEmpty must never trigger a network request");
    }
}
