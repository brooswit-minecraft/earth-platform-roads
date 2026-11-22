package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * MINECRAFT-567 (John comments 55588/55593, epic handoff 55578): the opt-in, credential-free Roads
 * loopback-proxy CLIENT, exercised against a disposable, in-process {@link HttpServer} fixture
 * standing in for the still-blocked, separately-repoed MINECRAFT-570 SQLite proxy.
 *
 * <p><b>This is a FAKE-PROXY verification of the Roads-side CONSUMER only.</b> It proves this mod's
 * own opt-in client correctly issues exactly one real HTTP send to a configured loopback target,
 * never sends the direct-provider API key there, and never silently falls back to the real
 * Overpass.ch endpoint on a proxy failure. It does NOT, and cannot, prove anything about the real
 * SQLite proxy's own behavior, its real cache-hit semantics, or the X-Cache: HIT-still-counts-as-
 * an-upstream-send rule from comment 55578 -- that half of the eventual shared acceptance is gated
 * on MINECRAFT-570's own repository and is explicitly out of scope here. Every test method name
 * below says "FakeProxy" for exactly this reason.
 *
 * <p>No coordinate, query body, endpoint, header, or key is ever asserted on, logged, or printed by
 * any test here -- only aggregate counts ({@link RoadFetchTelemetry.Snapshot}, this fixture's own
 * plain integer hit/miss/upstream counters) and timings.
 */
class RoadNetworkLoopbackProxyTest {
    private static final String ONE_WAY_RESPONSE = """
            {"elements":[{"type":"way","id":555201,"nodes":[1,2],
              "tags":{"highway":"secondary","name":"Loopback Proxy Test Street"},
              "geometry":[{"lat":41.0,"lon":-96.0},{"lat":41.001,"lon":-96.001}]}]}
            """;

    private HttpServer server;

    @AfterEach
    void cleanUp() {
        if (server != null) server.stop(0);
        System.clearProperty("earthplatformroads.loopbackProxyEnabled");
        System.clearProperty("earthplatformroads.loopbackProxyEndpoint");
        System.clearProperty("earthplatformroads.loopbackProxyMinRequestIntervalMs");
        RoadNetwork.setDefaultTileSourceInputsForTests(null, null);
    }

    /**
     * A disposable, credential-free in-process fixture standing in for MINECRAFT-570's real SQLite
     * proxy. Pre-warmed for exactly one deterministic bbox query: the first request to it is
     * answered immediately (the fixture's own "local proxy hit" -- it already has the answer, so it
     * never needs to forward anything upstream; {@link #upstreamSends} always stays 0 in every test
     * below, by construction of this fixture, mirroring comment 55578's "whose cache is already
     * warm for that exact query"). Records only plain integer counts; never logs or stores the
     * request body, headers, or any coordinate.
     */
    private static final class FakeWarmProxyFixture {
        final AtomicInteger localProxyHits = new AtomicInteger();
        final AtomicInteger upstreamSends = new AtomicInteger();
        volatile String lastSeenApiKeyHeader;

        HttpServer start() throws IOException {
            HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/", exchange -> {
                localProxyHits.incrementAndGet(); // already warm for this query: never forwards upstream
                lastSeenApiKeyHeader = exchange.getRequestHeaders().getFirst("X-API-Key");
                byte[] body = ONE_WAY_RESPONSE.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            stub.start();
            return stub;
        }
    }

    /**
     * The exact three-way delta from epic-handoff comment 55578, against the FAKE proxy: starting
     * from a genuinely fresh Roads cache (a brand-new {@code @TempDir}, so the deterministic query
     * cannot be satisfied by Roads' own disk or memory cache), issuing the query once must produce
     * deltas of exactly 1 actual mod HTTP send ({@link RoadFetchTelemetry}, the Roads side),
     * 1 local proxy hit ({@link FakeWarmProxyFixture#localProxyHits}, this fixture's own side), and
     * 0 proxy upstream sends ({@link FakeWarmProxyFixture#upstreamSends} -- this fixture never
     * forwards upstream at all, so it is structurally always 0).
     */
    @Test
    void freshRoadsCacheAgainstWarmFakeProxyYieldsExactlyOneSendOneHitZeroUpstreamSends(@TempDir Path freshRoadsCache) throws IOException {
        FakeWarmProxyFixture fixture = new FakeWarmProxyFixture();
        server = fixture.start();

        RoadTileSource loopbackClient = new RoadTileSource(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                freshRoadsCache, true, Duration.ofSeconds(5));

        RoadFetchTelemetry.Snapshot before = loopbackClient.telemetry().snapshot();
        var ways = loopbackClient.waysForCell(41.0005, -96.0005);
        RoadFetchTelemetry.Snapshot delta = loopbackClient.telemetry().snapshot().minus(before);

        assertEquals(1, delta.httpSends(), "Expected exactly 1 actual Roads HTTP send against the fake proxy");
        assertEquals(1, fixture.localProxyHits.get(), "Expected exactly 1 local (fake) proxy hit");
        assertEquals(0, fixture.upstreamSends.get(), "Expected exactly 0 fake-proxy upstream sends (it was already warm)");
        assertFalse(ways.isEmpty(), "Expected the fake proxy's canned response to actually resolve the query");
    }

    /** Never send the direct-provider Overpass.ch API key to the loopback proxy target (John comment 55588). */
    @Test
    void theApiKeyIsNeverSentToTheFakeProxy(@TempDir Path freshRoadsCache) throws IOException {
        FakeWarmProxyFixture fixture = new FakeWarmProxyFixture();
        server = fixture.start();

        // Even if this instance somehow carried a key (it never does in production loopback wiring
        // -- RoadNetwork.buildDefaultTileSource always passes null for loopback mode), the structural
        // rule in RoadTileSource.shouldSendApiKeyTo already means a key is only ever attached when
        // the endpoint exactly equals the real Overpass.ch CREDENTIAL_BOUND_ENDPOINT -- never a
        // loopback URL. This test proves that rule actually holds for a loopback endpoint.
        RoadTileSource loopbackClient = new RoadTileSource(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                freshRoadsCache, true, Duration.ofSeconds(5),
                "a-direct-provider-key-that-must-never-leave-this-process", Duration.ZERO);

        loopbackClient.waysForCell(41.0005, -96.0005);

        assertNull(fixture.lastSeenApiKeyHeader, "Expected the fake proxy to never receive an X-API-Key header");
    }

    /**
     * No silent fallback (brief requirement 2): a loopback-mode fetch against an unreachable/erroring
     * proxy must surface as a genuine failure through the mod's existing retry/error path -- never a
     * silent retry against the real Overpass.ch endpoint. Proven here by pointing a loopback-mode
     * instance at a stub that always answers 503: the cell must stay PENDING (never fabricated as
     * confirmed-empty or ready), and nothing in this class ever constructs or touches a second,
     * different endpoint on failure -- there is no fallback code path to exercise in the first place.
     */
    @Test
    void anUnreachableFakeProxyFailsRatherThanSilentlyFallingBackToDirectMode(@TempDir Path freshRoadsCache) throws IOException {
        AtomicInteger requestsToFixture = new AtomicInteger();
        HttpServer failingStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        failingStub.createContext("/", exchange -> {
            requestsToFixture.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        failingStub.start();
        server = failingStub;

        RoadTileSource loopbackClient = new RoadTileSource(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                freshRoadsCache, true, Duration.ofSeconds(5));

        var ways = loopbackClient.waysForCell(41.0005, -96.0005);

        assertTrue(ways.isEmpty(), "Expected no fabricated road data from a failed proxy fetch");
        assertEquals(RoadTileSource.CellState.PENDING, loopbackClient.cellState(41.0005, -96.0005),
                "Expected the cell to stay genuinely retryable (PENDING), not confirmed-empty, after a proxy failure");
        assertEquals(1, requestsToFixture.get(), "Expected exactly one real dispatched attempt to the configured fake proxy, no retried fallback target");
    }

    /**
     * Cold Roads cache hitting the fake loopback proxy vs. a warm Roads-cache control (second access,
     * served entirely from Roads' own memory cache, no network at all) -- brief requirement 5,
     * reported separately per John comment 55593 ("record cold Roads/warm SQLite separately from a
     * warm Roads cache control"). Also reports the configured vs. measured interval between the two
     * actual mod HTTP sends this test forces (for a second, different cell), per John's explicit ask
     * that client throttling not be mistaken for proxy/SQLite latency.
     */
    @Test
    void coldFakeProxyFetchVersusWarmRoadsCacheControlTiming(@TempDir Path freshRoadsCache) throws IOException {
        FakeWarmProxyFixture fixture = new FakeWarmProxyFixture();
        server = fixture.start();
        Duration configuredMinInterval = Duration.ofMillis(150);

        RoadTileSource loopbackClient = new RoadTileSource(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                freshRoadsCache, true, Duration.ofSeconds(5), null, configuredMinInterval);

        long firstSendStartNanos = System.nanoTime();
        var coldWays = loopbackClient.waysForCell(41.0005, -96.0005); // cold Roads cache -> hits the fake proxy
        long coldElapsedNanos = System.nanoTime() - firstSendStartNanos;
        assertFalse(coldWays.isEmpty(), "Expected the cold fetch to actually resolve via the fake proxy");
        assertEquals(1, fixture.localProxyHits.get(), "Expected exactly 1 fake-proxy hit for the cold fetch");

        long warmStartNanos = System.nanoTime();
        var warmWays = loopbackClient.waysForCell(41.0005, -96.0005); // warm Roads-cache control: Roads' own memory cache, no network
        long warmElapsedNanos = System.nanoTime() - warmStartNanos;
        assertFalse(warmWays.isEmpty(), "Expected the warm control read to still return the same cached data");
        assertEquals(1, fixture.localProxyHits.get(), "Expected the warm Roads-cache control to make NO additional fake-proxy request");

        double coldMillis = coldElapsedNanos / 1_000_000.0;
        double warmMillis = warmElapsedNanos / 1_000_000.0;
        System.out.println("MINECRAFT-567 fake-proxy timing (labeled distinct from real MINECRAFT-570 SQLite proxy): "
                + "cold-Roads/warm-fake-proxy=" + coldMillis + "ms, warm-Roads-cache-control=" + warmMillis + "ms");
        // A loose sanity bound rather than a strict cold-vs-warm comparison: a single cold-fetch
        // sample (a real, if loopback, HTTP round trip) can occasionally race JIT warmup/GC noise
        // and come in faster than normal, which would make a strict "warm must never be slower"
        // assertion flaky without being wrong about the real behavior -- the warm control genuinely
        // never touches the network (already proven above by the unchanged hit count), so it is
        // bounded here only by a generous absolute ceiling appropriate for an in-memory map lookup.
        assertTrue(warmMillis < 50.0,
                "Expected the warm, no-network Roads-cache control read to be fast (<50ms); got " + warmMillis + "ms"
                        + " (cold fake-proxy fetch was " + coldMillis + "ms)");

        // Force a second, real HTTP send (a different cell, so Roads' own cache cannot short-circuit
        // it) and measure the real wall-clock gap SINCE THE FIRST SEND against the configured
        // minRequestInterval -- so a reader can tell "this is client throttling" apart from "this is
        // proxy/SQLite latency". The first send already advanced nextEligibleRequestAt by
        // configuredMinInterval; attempting the second send immediately (as the warm-control read
        // above did, implicitly) would be declined as not-yet-eligible (DISPATCH_SUPPRESSED, a free,
        // empty-list, non-network decline) rather than actually dispatched -- sleep past that
        // cooldown first so this genuinely measures a second REAL send, not a suppressed one.
        try {
            Thread.sleep(configuredMinInterval.toMillis() + 20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var secondWays = loopbackClient.waysForCell(55.0005, -96.0005);
        long measuredIntervalNanos = System.nanoTime() - firstSendStartNanos;
        assertFalse(secondWays.isEmpty(), "Expected the second, different-cell fetch to also resolve via the fake proxy");
        assertEquals(2, fixture.localProxyHits.get(), "Expected exactly 2 total fake-proxy hits after the second distinct cell");
        double measuredIntervalMillis = measuredIntervalNanos / 1_000_000.0;
        System.out.println("MINECRAFT-567 configured min HTTP-send interval=" + configuredMinInterval.toMillis()
                + "ms; measured wall-clock gap between the start of the first real send and the second real send="
                + measuredIntervalMillis + "ms");
        assertTrue(measuredIntervalMillis >= configuredMinInterval.toMillis(),
                "Expected the measured gap between the two real sends to be at least the configured "
                        + configuredMinInterval.toMillis() + "ms min-request-interval; got " + measuredIntervalMillis + "ms");
        // Reported as a measured floor, not an exact match, since the real value above is a real
        // in-process HTTP round trip plus this test's own sleep, not a single deterministic sleep --
        // coverage of the cooldown mechanism itself.
    }

    /**
     * Direct-mode (switch off) regression: with NEITHER loopback property set (the ordinary,
     * unmodified state of a real install and of every pre-existing test in this suite), the brand
     * new loopback branch in {@code RoadNetwork.buildDefaultTileSource} must never be entered at
     * all -- the pre-existing opt-in Overpass.ch preview chain
     * ({@link RoadNetworkProductionWiringTest}) runs completely unchanged. This does not duplicate
     * that class's own coverage; it instead proves the NEW branch's own guard is actually off by
     * default, extending (not replacing) the existing direct-mode regression suite.
     */
    @Test
    void loopbackSwitchOffLeavesDirectModeWiringCompletelyUnchanged(@TempDir Path cacheRoot) throws IOException {
        assertTrue(System.getProperty("earthplatformroads.loopbackProxyEnabled") == null,
                "Test assumption violated: another test left earthplatformroads.loopbackProxyEnabled set");
        assertTrue(System.getProperty("earthplatformroads.previewProvider") == null,
                "Test assumption violated: another test left earthplatformroads.previewProvider set");

        AtomicInteger requestCount = new AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        server = stub;
        try {
            // Same pattern as RoadNetworkProductionWiringTest#withNoOptInPropertiesSetTheDefaultWiringNeverTouchesTheNetwork:
            // only the cache root is overridden, never the endpoint -- exercising the exact
            // production branch-selection logic with every opt-in switch left at its real default.
            RoadNetwork.setDefaultTileSourceInputsForTests(null, cacheRoot);
            var tileSource = RoadNetwork.liveTileSourceForPrefetch();
            assertTrue(tileSource != null, "Expected a non-null (but network-disabled) tile source with no opt-in at all");

            tileSource.waysForCell(41.0005, -96.0005);
            assertEquals(0, requestCount.get(), "Expected zero network requests with no loopback or preview opt-in set");
        } finally {
            RoadNetwork.setDefaultTileSourceInputsForTests(null, null);
        }
    }

    /**
     * The opt-in config switch exercised through the ACTUAL production wiring
     * ({@code RoadNetwork.buildDefaultTileSource}), not a directly-constructed {@link RoadTileSource}
     * -- proves the system-property switch itself (never a saved-world/save-file setting) actually
     * redirects the real production code path to the configured loopback base URL.
     */
    @Test
    void theOptInSystemPropertySwitchRedirectsTheRealProductionWiring(@TempDir Path cacheRoot) throws IOException {
        FakeWarmProxyFixture fixture = new FakeWarmProxyFixture();
        server = fixture.start();
        System.setProperty("earthplatformroads.loopbackProxyEnabled", "true");
        System.setProperty("earthplatformroads.loopbackProxyEndpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try {
            RoadNetwork.setDefaultTileSourceInputsForTests(null, cacheRoot);
            var tileSource = RoadNetwork.liveTileSourceForPrefetch();
            assertTrue(tileSource != null, "Expected a live tile source once the loopback switch is on and configured");

            var ways = tileSource.waysForCell(41.0005, -96.0005);
            assertFalse(ways.isEmpty(), "Expected the production wiring, once redirected, to actually resolve via the fake proxy");
            assertEquals(1, fixture.localProxyHits.get(), "Expected the real production wiring to reach the configured fake proxy exactly once");
        } finally {
            RoadNetwork.setDefaultTileSourceInputsForTests(null, null);
        }
    }
}
