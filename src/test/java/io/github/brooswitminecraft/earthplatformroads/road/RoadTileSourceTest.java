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
 * Exercises {@link RoadTileSource} against a local HTTP stub (never a real live service -- this
 * must be deterministic) covering exactly the behaviors MINECRAFT-566's review named as the
 * acceptance requirement: automatic fetch/cache, a cell staying "pinned" to whatever was cached (a
 * real restart re-reads the same cache, does not re-fetch), a genuinely empty result staying
 * confirmed-empty, a failed/slow fetch leaving a cell retryable rather than permanently marked as a
 * roadless gap, Overpass's HTTP-200-with-error-remark failure shape, and the Overpass.ch preview
 * provider's own requirements (an optional credential header, a configurable minimum request
 * interval).
 */
class RoadTileSourceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    // Overpass's own "out geom" shape -- RoadTileSource.fetchCell speaks Overpass QL again as of
    // the Overpass.ch preview provider (MINECRAFT-566 comment 55446).
    private static final String ONE_WAY_RESPONSE = """
            {"elements":[{"type":"way","id":555001,"nodes":[1,2],
              "tags":{"highway":"secondary","name":"Stub Street"},
              "geometry":[{"lat":40.0,"lon":-105.0},{"lat":40.001,"lon":-105.001}]}]}
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

    /** Overpass's own convention: a top-level "remark" appears only when it has an error/warning to report, even inside an HTTP 200 response. */
    private static final String REMARK_ZERO_ELEMENTS_RESPONSE = """
            {"elements":[],"remark":"runtime error: Query timed out in \\"query\\" at line 2"}
            """;
    private static final String REMARK_PARTIAL_ELEMENTS_RESPONSE = """
            {"elements":[{"type":"way","id":555002,"nodes":[1,2],
              "tags":{"highway":"secondary","name":"Partial Street"},
              "geometry":[{"lat":44.0,"lon":-109.0},{"lat":44.001,"lon":-109.001}]}],
             "remark":"runtime error: Query timed out in \\"query\\" at line 2"}
            """;

    @Test
    void anHttp200WithAnErrorRemarkAndZeroElementsStaysPendingNotConfirmedEmpty(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, REMARK_ZERO_ELEMENTS_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        var duringError = source.waysForCell(45.0, -110.0);
        assertTrue(duringError.isEmpty(), "An error remark must not throw; the caller gets no road for this one call");
        assertEquals(1, requests.get());
        assertTrue(source.cachedWaysForCellOrEmpty(45.0, -110.0).isEmpty(),
                "An HTTP 200 with a nonempty error remark and zero elements must NOT write a confirmed-empty artifact");

        AtomicInteger recoveredRequests = new AtomicInteger();
        HttpServer recoveredStub = startStub(recoveredRequests, ONE_WAY_RESPONSE, 200);
        RoadTileSource restartedAfterRecovery = new RoadTileSource(endpointOf(recoveredStub), cacheRoot, true, Duration.ofSeconds(5));
        var afterRecovery = restartedAfterRecovery.waysForCell(45.0, -110.0);
        assertEquals(1, afterRecovery.size(), "The cell left pending by the error remark must retry successfully through a fresh source over the same cache root");
        assertEquals(1, recoveredRequests.get());
    }

    @Test
    void anHttp200WithAnErrorRemarkAndPartialElementsStaysPendingNotReady(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, REMARK_PARTIAL_ELEMENTS_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        var duringError = source.waysForCell(46.0, -111.0);
        assertTrue(duringError.isEmpty(), "An error remark must not throw, and must not surface the partial way either");
        assertEquals(1, requests.get());
        assertTrue(source.cachedWaysForCellOrEmpty(46.0, -111.0).isEmpty(),
                "An HTTP 200 with a nonempty error remark and some elements must NOT be written as a ready (complete) artifact");

        AtomicInteger recoveredRequests = new AtomicInteger();
        HttpServer recoveredStub = startStub(recoveredRequests, ONE_WAY_RESPONSE, 200);
        RoadTileSource restartedAfterRecovery = new RoadTileSource(endpointOf(recoveredStub), cacheRoot, true, Duration.ofSeconds(5));
        var afterRecovery = restartedAfterRecovery.waysForCell(46.0, -111.0);
        assertEquals(1, afterRecovery.size(), "The cell left pending by the partial error-remark response must retry successfully through a fresh source over the same cache root");
        assertEquals(1, recoveredRequests.get());
    }

    /**
     * {@code shouldSendApiKeyTo} is the real, exact rule {@code fetchCell} uses; tested directly
     * (pure function, no network) per MINECRAFT-566 comment 55448's "bind credential-bearing
     * requests to the documented https://api.overpass.ch/v1/interpreter origin/path" -- a dummy key
     * value only, per this same comment's "keep injectable loopback stubs credential-free except
     * dummy-header tests."
     */
    @Test
    void theApiKeyIsOnlySentToTheExactDocumentedOverpassChEndpoint() {
        String dummyKey = "dummy-test-key-not-a-real-credential";
        assertTrue(RoadTileSource.shouldSendApiKeyTo(RoadTileSource.CREDENTIAL_BOUND_ENDPOINT, dummyKey),
                "Expected the key to be sent to the exact documented endpoint");
        assertTrue(!RoadTileSource.shouldSendApiKeyTo(URI.create("http://127.0.0.1:9/"), dummyKey),
                "Expected the key withheld from a local stub -- it is not the bound endpoint");
        assertTrue(!RoadTileSource.shouldSendApiKeyTo(URI.create("https://api.overpass.ch/v1/interpreter/evil"), dummyKey),
                "Expected the key withheld from a similar-looking but different path");
        assertTrue(!RoadTileSource.shouldSendApiKeyTo(URI.create("http://api.overpass.ch/v1/interpreter"), dummyKey),
                "Expected the key withheld over plain HTTP (not HTTPS) even with a matching host/path");
        assertTrue(!RoadTileSource.shouldSendApiKeyTo(RoadTileSource.CREDENTIAL_BOUND_ENDPOINT, null),
                "Expected no key sent (and no NPE) when no key is configured at all");
    }

    /**
     * A real fetch against a non-bound endpoint (a local stub, like every other test in this class)
     * must still work -- the binding rule only withholds the credential, it never blocks the
     * request itself. This is what every other stub-based fetch test already implicitly exercises;
     * this test makes the "and the key was never present" part explicit with a dummy key.
     */
    @Test
    void aFetchAgainstANonBoundEndpointSucceedsWithTheKeyWithheld(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> seenApiKeyHeader = new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requests.incrementAndGet();
            seenApiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] body = ONE_WAY_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        this.server = stub;

        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5), "dummy-test-key-not-a-real-credential", Duration.ZERO);
        var ways = source.waysForCell(40.0005, -105.0005);
        assertEquals(1, requests.get());
        assertEquals(1, ways.size(), "Expected the fetch to still succeed with the key withheld");
        assertTrue(seenApiKeyHeader.get() == null, "Expected no X-API-Key header against a non-bound (local stub) endpoint");
    }

    @Test
    void aMinimumRequestIntervalIsEnforcedBetweenFetches(@TempDir Path cacheRoot) throws IOException, InterruptedException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, EMPTY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5), null, Duration.ofMillis(300));

        long start = System.nanoTime();
        source.waysForCell(50.0, -1.0); // first cell: no prior request, no wait expected
        source.waysForCell(51.0, -2.0); // second cell: must wait out the configured interval first
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(2, requests.get());
        assertTrue(elapsedMillis >= 300, "Expected at least the configured 300ms minimum interval between two fetches, took " + elapsedMillis + "ms");
    }

    @Test
    void cachedWaysForCellOrEmptyNeverFetches(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        assertTrue(source.cachedWaysForCellOrEmpty(40.0005, -105.0005).isEmpty(), "Nothing cached yet; this read-only call must not fetch");
        assertEquals(0, requests.get(), "cachedWaysForCellOrEmpty must never trigger a network request");
    }
}
