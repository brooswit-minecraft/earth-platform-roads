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
import java.util.List;
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
        // MINECRAFT-566 comment 55450: this class never sleeps to wait out a rate limit (that
        // blocked unrelated cached-tile reads behind it) -- a fetch attempted before the next
        // eligible moment is declined immediately (pending, retryable), not delayed. So the second
        // of two back-to-back fetches, too soon after the first, must come back empty right away
        // (fast, no HTTP sent) rather than wait 300ms and then succeed.
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, EMPTY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5), null, Duration.ofMillis(300));

        source.waysForCell(50.0, -1.0); // first cell: no prior request, eligible immediately
        assertEquals(1, requests.get());

        long start = System.nanoTime();
        var tooSoon = source.waysForCell(51.0, -2.0); // second cell: declined, not delayed
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(tooSoon.isEmpty(), "Expected the too-soon fetch to come back empty (pending), not wait and succeed");
        assertEquals(1, requests.get(), "Expected no second HTTP request while not yet eligible");
        assertTrue(elapsedMillis < 300, "Expected the too-soon fetch to return immediately, not block for the 300ms interval -- took " + elapsedMillis + "ms");

        // After the interval genuinely elapses, the same cell becomes eligible and actually fetches.
        Thread.sleep(300);
        var afterInterval = source.waysForCell(51.0, -2.0);
        assertEquals(2, requests.get(), "Expected the retry after the interval elapsed to actually request");
        assertTrue(afterInterval.isEmpty(), "This stub always returns an empty result");
    }

    /**
     * MINECRAFT-566 comment 55450's core regression: a disk-cached tile must complete promptly even
     * while a DIFFERENT cell is rate-limited/backed off, using a fresh source instance (a real
     * restart) over a cache root seeded with one already-ready tile. Requires: the disk hit returns
     * fast, triggers zero HTTP requests of its own, writes no fabricated empty marker, only one
     * request is ever in flight, and the cold cell's pending miss becomes retryable only after its
     * deadline.
     */
    @Test
    void aDiskCachedTileIsNotBlockedBehindAColdCellsProviderBackoff(@TempDir Path cacheRoot) throws IOException, InterruptedException {
        // Seed a persistent, already-ready tile directly on disk -- exactly the shape RoadTileSource
        // itself would have written, simulating a previous run (before "a new source instance").
        long seededCellLat = (long) Math.floor(10.0 / RoadNetwork.CELL_DEGREES);
        long seededCellLon = (long) Math.floor(20.0 / RoadNetwork.CELL_DEGREES);
        String seededCellId = seededCellLat + "_" + seededCellLon;
        java.nio.file.Files.createDirectories(cacheRoot);
        String seededTileJson = """
                {"schema_version":1,"region_id":"seeded","ways":[{"way_id":777001,
                  "way_tags":{"highway":"secondary","name":"Seeded Street"},
                  "centerline":[{"lat":10.0005,"lon":20.0005},{"lat":10.0006,"lon":20.0006}],
                  "node_ids":[1,2],"bridge":false,"tunnel":false}]}
                """;
        java.nio.file.Files.writeString(cacheRoot.resolve(seededCellId + ".json"), seededTileJson);

        // A cold, different cell whose provider returns 429 with a long Retry-After -- this is the
        // backoff a disk hit must never wait behind.
        AtomicInteger coldRequests = new AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            coldRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "30");
            byte[] body = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        this.server = stub;

        // A fresh instance over the seeded cache root -- "a new source instance", i.e. a restart.
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        var coldMiss = source.waysForCell(30.0, 40.0); // triggers the 429, sets a 30s backoff deadline
        assertTrue(coldMiss.isEmpty(), "Expected no road from the rate-limited cold cell");
        assertEquals(1, coldRequests.get());
        long coldCellLat = (long) Math.floor(30.0 / RoadNetwork.CELL_DEGREES);
        long coldCellLon = (long) Math.floor(40.0 / RoadNetwork.CELL_DEGREES);
        assertTrue(!java.nio.file.Files.exists(cacheRoot.resolve(coldCellLat + "_" + coldCellLon + ".empty")),
                "Expected no fabricated empty marker written for the rate-limited cell");

        long start = System.nanoTime();
        var diskHit = source.waysForCell(10.0005, 20.0005); // the seeded, already-ready, unrelated tile
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, diskHit.size(), "Expected the seeded tile's way to be returned from disk");
        assertEquals(777001L, diskHit.get(0).wayId());
        assertEquals(1, coldRequests.get(), "Expected zero additional HTTP requests for the disk hit");
        assertTrue(elapsedMillis < 1000,
                "Expected the disk-cached tile to complete promptly, not wait behind the cold cell's 30s backoff -- took " + elapsedMillis + "ms");

        // A second attempt at the SAME cold cell, still well before its 30s deadline, must stay
        // pending without issuing another request -- not retried early.
        var stillBackedOff = source.waysForCell(30.0, 40.0);
        assertTrue(stillBackedOff.isEmpty());
        assertEquals(1, coldRequests.get(), "Expected the cold cell to stay pending (no new request) before its backoff deadline");
    }

    @Test
    void cachedWaysForCellOrEmptyNeverFetches(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        assertTrue(source.cachedWaysForCellOrEmpty(40.0005, -105.0005).isEmpty(), "Nothing cached yet; this read-only call must not fetch");
        assertEquals(0, requests.get(), "cachedWaysForCellOrEmpty must never trigger a network request");
    }

    /**
     * MINECRAFT-566 comment 55474's "distinct diagnostics" for a sustained provider outage, as
     * opposed to an ordinary single pending cell. Three DIFFERENT cells (not the same one retried,
     * since a single awkward tile failing repeatedly is not proof the PROVIDER is down) all
     * genuinely dispatched and failed, with no success in between.
     */
    @Test
    void sustainedOutageBecomesTrueOnlyAfterSeveralConsecutiveRealFailuresAcrossDifferentCells(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer failingStub = startStub(requests, "service unavailable", 503);
        RoadTileSource source = new RoadTileSource(endpointOf(failingStub), cacheRoot, true, Duration.ofSeconds(5));

        assertTrue(source.waysForCell(50.0, 10.0).isEmpty());
        assertTrue(!source.sustainedOutage(), "One real failure must not yet read as a sustained outage");
        assertTrue(source.waysForCell(50.02, 10.0).isEmpty());
        assertTrue(!source.sustainedOutage(), "Two real failures must not yet read as a sustained outage");
        assertTrue(source.waysForCell(50.04, 10.0).isEmpty());
        assertTrue(source.sustainedOutage(), "Three consecutive real failures across different cells must read as a sustained outage");
        assertEquals(3, requests.get());
    }

    @Test
    void aSuccessfulFetchResetsTheOutageStreakOnTheSameInstance(@TempDir Path cacheRoot) throws IOException {
        // One stub, same RoadTileSource instance throughout: the 1st and 2nd requests (different
        // cells) fail, the 3rd succeeds, the 4th (yet another cell) fails again -- if the streak
        // were NOT reset by the 3rd call's success, the 4th failure would wrongly read as "3
        // consecutive failures" (1+1+1, never cleared) rather than correctly as just one.
        AtomicInteger requestNumber = new AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            int n = requestNumber.incrementAndGet();
            String response = n == 3 ? ONE_WAY_RESPONSE : "service unavailable";
            int status = n == 3 ? 200 : 503;
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        this.server = stub;
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        source.waysForCell(53.0, 13.0); // fails (request 1)
        source.waysForCell(53.02, 13.0); // fails (request 2)
        assertTrue(!source.sustainedOutage(), "Two failures: not yet a sustained outage");
        source.waysForCell(53.04, 13.0); // succeeds (request 3) -- must reset the streak
        assertTrue(!source.sustainedOutage());
        source.waysForCell(53.06, 13.0); // fails (request 4) -- only ONE failure since the reset
        assertTrue(!source.sustainedOutage(), "A single failure right after a success must not read as a sustained outage");
        assertEquals(4, requestNumber.get());
    }

    /**
     * MINECRAFT-566 comment 55476's "bounded-worker" ask: {@code waysForCellWithBoundedWait}, used
     * by {@code ServerCubeWorldRoadPrefetchMixin} for the ordinary-streaming path.
     */
    private HttpServer startDelayedStub(AtomicInteger requestCount, long delayMillis, String response, int statusCode) throws IOException {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        this.server = stub;
        return stub;
    }

    @Test
    void boundedWaitReturnsFreshDataPromptlyWhenTheFetchResolvesWithinTheBound(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startDelayedStub(requests, 100, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        long start = System.nanoTime();
        var result = source.waysForCellWithBoundedWait(44.0, 14.0, Duration.ofSeconds(2));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, result.size(), "Expected the real fetched way once it resolved within the bound");
        assertTrue(elapsedMillis < 1500, "Expected this call to return promptly once the fetch resolved, not wait out the full bound -- took " + elapsedMillis + "ms");
        assertEquals(1, requests.get());
    }

    @Test
    void boundedWaitFailsOpenOnTimeoutButTheFetchKeepsRunningInTheBackground(@TempDir Path cacheRoot) throws IOException, InterruptedException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startDelayedStub(requests, 500, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        long start = System.nanoTime();
        var result = source.waysForCellWithBoundedWait(45.0, 15.0, Duration.ofMillis(100));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isEmpty(), "Expected to fail open (empty) once the short bound elapsed before the slower fetch resolved");
        assertTrue(elapsedMillis < 450, "Expected this call to return at roughly the bound, not wait out the full 500ms fetch -- took " + elapsedMillis + "ms");

        // The fetch this call gave up waiting on keeps running in the background: poll briefly for
        // the cache to actually fill in, proving nothing was lost or fabricated by the timeout.
        List<RoadSegment> eventual = List.of();
        for (int i = 0; i < 20 && eventual.isEmpty(); i++) {
            Thread.sleep(50);
            eventual = source.cachedWaysForCellOrEmpty(45.0, 15.0);
        }
        assertEquals(1, eventual.size(), "Expected the background fetch to eventually populate the cache after this call gave up waiting");
        assertEquals(1, requests.get(), "Expected only the one real request, not a second one from giving up and retrying");
    }

    @Test
    void concurrentBoundedWaitCallsForTheSameStillPendingCellCoalesceIntoOneRealRequest(@TempDir Path cacheRoot) throws IOException, InterruptedException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startDelayedStub(requests, 300, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        // Models several of a tall column's cube-Y layers all independently missing storage at
        // once: every caller asks for the SAME cell, concurrently, before the first fetch resolves.
        int callers = 5;
        var ready = new java.util.concurrent.CountDownLatch(callers);
        var results = new java.util.concurrent.CopyOnWriteArrayList<List<RoadSegment>>();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                results.add(source.waysForCellWithBoundedWait(46.0, 16.0, Duration.ofSeconds(2)));
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();

        assertEquals(callers, results.size());
        for (var result : results) {
            assertEquals(1, result.size(), "Expected every caller to see the real fetched way once it resolved");
        }
        assertEquals(1, requests.get(), "Expected exactly ONE real request shared across every concurrent caller for the same cell, not one per caller");
    }

    @Test
    void enqueueLookaheadAsyncDoesNotBlockTheCallingThread(@TempDir Path cacheRoot) throws IOException, InterruptedException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startDelayedStub(requests, 300, EMPTY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        long start = System.nanoTime();
        source.enqueueLookaheadAsync(47.0, 17.0);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 100, "Expected enqueueLookaheadAsync to return immediately, not wait on the real (slow) fetch -- took " + elapsedMillis + "ms");

        // The real dispatch still actually happens, just off this thread.
        for (int i = 0; i < 20 && requests.get() == 0; i++) Thread.sleep(50);
        assertEquals(1, requests.get(), "Expected the real lookahead dispatch to have actually happened in the background");
    }

    /**
     * MINECRAFT-566 comment 55481, a real bug: the first version registered
     * {@code inFlightByCell}'s cleanup callback INSIDE {@code computeIfAbsent}'s own mapping
     * function, which can race a synchronously-fast completion and leave a stale, already-resolved
     * future permanently pinned -- every later call for that cell would then keep reusing it
     * forever instead of ever dispatching a fresh fetch. A zero-delay stub maximizes the chance of
     * hitting that exact race; this asserts the FIXED behavior holds regardless: a second call for
     * the same cell, after the first's (failed, still-pending) fetch has settled, genuinely
     * dispatches its own new request rather than silently reusing the first's stale result.
     */
    @Test
    void aSecondCallForTheSameStillPendingCellDispatchesItsOwnFreshRequestNotAStalePinnedOne(@TempDir Path cacheRoot) throws IOException, InterruptedException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startDelayedStub(requests, 0, "service unavailable", 503); // zero delay: maximizes the chance of the race this guards against
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        source.waysForCellWithBoundedWait(48.0, 18.0, Duration.ofSeconds(2));
        // Let the first fetch's cleanup fully settle before the second call.
        for (int i = 0; i < 20 && source.inFlightCellCountForTests() > 0; i++) Thread.sleep(25);
        assertEquals(0, source.inFlightCellCountForTests(), "Expected the first (failed) fetch's entry to have been cleaned up");

        source.waysForCellWithBoundedWait(48.0, 18.0, Duration.ofSeconds(2));
        assertEquals(2, requests.get(), "Expected the second call to dispatch its OWN fresh request, not reuse a stale pinned future from the first");
    }

    /**
     * MINECRAFT-566 comment 55481's "coalesce lookahead submissions before admission" ask: models a
     * tall column's many cube-Y layers all calling {@code enqueueLookaheadAsync} for the exact same
     * center cell in a tight burst, before the first dispatched task has a chance to even start
     * (a slow stub holds it open). The first version submitted a brand-new task to the executor's
     * own unbounded queue on EVERY call; this asserts only one is actually admitted at a time.
     */
    @Test
    void manyRapidLookaheadCallsForTheSameColumnCoalesceRatherThanFloodTheQueue(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startDelayedStub(requests, 300, EMPTY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        for (int i = 0; i < 20; i++) source.enqueueLookaheadAsync(49.0, 19.0);

        assertEquals(1, source.pendingLookaheadDispatchCountForTests(),
                "Expected the 19 later calls for the SAME center cell to be skipped (coalesced), leaving only the first one's task pending");
    }

    /**
     * MINECRAFT-566 comment 55481's "cap pending tiles" ask: a burst of many DISTINCT, simultaneously
     * cold cells must not grow {@code inFlightByCell} without bound. A slow stub keeps every admitted
     * fetch in flight long enough to observe the cap before any of them can resolve and free a slot.
     */
    @Test
    void distinctSimultaneousColdCellsAreCappedRatherThanGrowingWithoutBound(@TempDir Path cacheRoot) throws IOException, InterruptedException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startDelayedStub(requests, 2000, EMPTY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        int callers = 20; // far more than the cap
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < callers; i++) {
            double lat = 50.0 + i * 0.5; // each a genuinely distinct, far-apart cell
            Thread t = new Thread(() -> source.waysForCellWithBoundedWait(lat, 20.0, Duration.ofMillis(300)));
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();

        // A best-effort, not strictly atomic, cap (see MAX_IN_FLIGHT_TILES's own doc) -- asserts a
        // real bound exists (nowhere near all 20 callers), with slack for the inherent admission
        // check-then-insert race, rather than an exact count.
        assertTrue(source.inFlightCellCountForTests() <= 16,
                "Expected the number of simultaneously in-flight distinct cells to stay meaningfully capped, got " + source.inFlightCellCountForTests());
    }

    /**
     * MINECRAFT-566 comment 55522: an immediate (current-cell) request must be picked ahead of a
     * speculative lookahead request still WAITING in the async executor's own queue, even though
     * the lookahead was submitted FIRST -- the real bug: "a speculative tile held its own slot while
     * a distinct current-cell request was already queued... and sent no POST" because a plain FIFO
     * queue let the earlier-queued lookahead job occupy the executor's one thread first. An
     * "occupier" request holds that thread busy inside a slow HTTP call; a lookahead and then an
     * immediate request are queued (in that order) while it is busy; once the occupier finishes,
     * the immediate request's own HTTP request must land before the lookahead's, despite being
     * queued second.
     */
    @Test
    void immediateRequestsArePrioritizedOverQueuedLookaheadRequests(@TempDir Path cacheRoot) throws IOException, InterruptedException {
        List<String> requestOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestOrder.add(body);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = EMPTY_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        stub.start();
        this.server = stub;
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        // Occupier: holds the executor's one thread busy inside the slow HTTP call (lat 60.0, far
        // from the other two cells below so none of their own bboxes can overlap by coincidence).
        Thread occupier = new Thread(() -> source.waysForCellWithBoundedWait(60.0, 60.0, Duration.ofSeconds(10)));
        occupier.start();
        // Generous (ASYNC_FETCH_EXECUTOR is shared process-wide -- see its own class doc -- so this
        // must also tolerate a stray slow task left over from a DIFFERENT test's own background
        // fetch still occupying it when this one starts).
        for (int i = 0; i < 200 && requestOrder.isEmpty(); i++) Thread.sleep(25);
        assertEquals(1, requestOrder.size(), "Sanity: expected the occupier's own request to already be in flight before queuing more");

        // Queue a lookahead (around 61.0,61.0), THEN an immediate request (at 62.0,62.0) -- in that
        // order -- while the executor's one thread is still busy with the occupier.
        source.enqueueLookaheadAsync(61.0, 61.0);
        Thread immediate = new Thread(() -> source.waysForCellWithBoundedWait(62.0, 62.0, Duration.ofSeconds(10)));
        immediate.start();

        occupier.join();
        immediate.join();
        for (int i = 0; i < 200 && requestOrder.size() < 3; i++) Thread.sleep(50); // let the lower-priority lookahead's own request eventually land too

        assertEquals(3, requestOrder.size(), "Expected all three requests (occupier, immediate, lookahead) to eventually land");
        assertTrue(requestOrder.get(1).contains("62.0"),
                "Expected the immediate request (queued SECOND, at 62.0) to be picked ahead of the lookahead request (queued FIRST, around 61.0) "
                        + "once the executor's thread freed up, got second request: " + requestOrder.get(1));
    }

    /**
     * MINECRAFT-566 comment 55522's other half: "keep eligible immediate demand pending across
     * cooldown" -- a bounded-wait call whose first attempt lands exactly during this instance's own
     * steady {@code minRequestInterval} cooldown (from an unrelated, immediately preceding fetch)
     * must retry once that cooldown passes, as long as its own {@code bound} has not yet elapsed,
     * rather than giving up the instant the first attempt is declined. The real bug this fixes:
     * "the queued job immediately dropped unfulfilled in the normal cooldown" when it could have
     * waited the (short) remaining cooldown out and actually succeeded.
     */
    @Test
    void boundedWaitRetriesAcrossASteadyCooldownRatherThanGivingUpOnTheFirstDecline(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5), null, Duration.ofMillis(100));

        source.waysForCell(80.0, 80.0); // first cell (a lat/lon unused by any other test in this class): eligible immediately, starts the 100ms cooldown
        assertEquals(1, requests.get());

        // A different, still-pending cell, requested right away (during the cooldown): the first
        // internal attempt must be declined (not yet eligible), but the bound comfortably outlasts
        // the 100ms cooldown (generous on purpose: ASYNC_FETCH_EXECUTOR is shared process-wide --
        // see its own class doc -- so this must also tolerate a stray slow task left over from a
        // DIFFERENT test's own background fetch still occupying it), so this must retry and
        // actually succeed rather than coming back empty.
        List<RoadSegment> result = List.of();
        for (int i = 0; i < 10 && result.isEmpty(); i++) {
            result = source.waysForCellWithBoundedWait(81.0, 81.0, Duration.ofSeconds(5));
        }
        assertEquals(1, result.size(), "Expected the bounded wait to retry across the short cooldown and actually succeed, not give up on the first decline");
    }

    /**
     * MINECRAFT-566 comment 55547, a real liveness gap found in using
     * {@code waysForCellWithBoundedWait(lat, lon, Duration.ZERO)} as a fire-and-forget dispatch: a
     * zero bound also becomes the retry loop's own internal deadline, so a cooldown-suppressed
     * dispatch gives up (and unregisters from the in-flight map) on the very first decline instead
     * of staying registered as current demand through the cooldown. {@code dispatchCellFetch} fixes
     * this by keeping a generous internal retry budget while never having the CALLER wait at all.
     */
    @Test
    void dispatchCellFetchStaysRegisteredAndActuallyResolvesAcrossASteadyCooldown(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5), null, Duration.ofMillis(100));

        source.waysForCell(82.0, 82.0); // starts the 100ms steady-cadence cooldown
        assertEquals(1, requests.get());

        // A different, still-pending cell: dispatchCellFetch must return immediately (never block
        // this thread at all, unlike waysForCellWithBoundedWait even with a zero bound, which still
        // calls future.get()), yet the cell must still actually resolve once the cooldown clears --
        // proving the dispatch stayed registered as live demand rather than giving up immediately.
        long start = System.nanoTime();
        source.dispatchCellFetch(83.0, 83.0);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 50, "Expected dispatchCellFetch to return near-instantly, not wait at all -- took " + elapsedMillis + "ms");

        List<RoadSegment> resolved = List.of();
        for (int i = 0; i < 40 && resolved.isEmpty(); i++) {
            resolved = source.cachedWaysForCellOrEmpty(83.0, 83.0);
            if (resolved.isEmpty()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        assertEquals(1, resolved.size(), "Expected the fire-and-forget dispatch to actually resolve this cell once the cooldown cleared, not give up on the first decline");
    }

    /**
     * MINECRAFT-566 comment 55528, a real bug John's own independent loopback reproduction found: a
     * genuinely ATTEMPTED failure (a request that was actually sent and failed, e.g. an HTTP 500)
     * advances {@code nextEligibleRequestAt} by the steady cadence exactly like a success does, so
     * the first version of the cooldown-retry loop -- inferring "was this just cooldown?" purely
     * from whether that instant was still ahead of now -- could not tell a real failure apart from a
     * merely-not-yet-eligible decline, and retried (sending a SECOND real request) after a real
     * failure too. This asserts the fixed behavior: a bounded-wait call against a provider that
     * genuinely fails must send exactly ONE real request and come back empty -- never a second one
     * -- even with a generous bound that would easily have covered a cooldown-driven retry.
     */
    @Test
    void boundedWaitNeverRetriesAGenuineAttemptedFailureEvenWithPlentyOfBoundLeft(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, "service unavailable", 500);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5), null, Duration.ofMillis(100));

        var result = source.waysForCellWithBoundedWait(90.0, 90.0, Duration.ofSeconds(2));

        assertTrue(result.isEmpty(), "Expected a genuinely failed fetch to come back empty (pending), not fabricate a result");
        assertEquals(1, requests.get(), "Expected EXACTLY one real request -- a genuine attempted failure must never be retried by the cooldown-retry loop");
        assertEquals(RoadTileSource.CellState.PENDING, source.cellState(90.0, 90.0), "Expected the cell to stay genuinely pending (retryable later), not confirmed-empty");
    }
}
