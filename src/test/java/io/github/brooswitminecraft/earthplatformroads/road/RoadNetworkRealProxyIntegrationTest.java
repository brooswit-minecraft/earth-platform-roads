package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MINECRAFT-566 comment 55661 (John Winstead, clarifying 55657/55660): a real two-PROCESS pair
 * acceptance -- the actual Python {@code overpass-cache-proxy} as a real subprocess, a real fake
 * Overpass-shaped upstream (a genuine {@link HttpServer}, not a stub asserted on directly), and the
 * real Roads production wiring (the exact "production JVM seam" {@code
 * RoadNetworkLoopbackProxyTest#theOptInSystemPropertySwitchRedirectsTheRealProductionWiring} already
 * uses: {@code RoadNetwork.setDefaultTileSourceInputsForTests} + the
 * {@code earthplatformroads.loopbackProxyEnabled}/{@code loopbackProxyEndpoint} system properties +
 * {@code RoadNetwork.liveTileSourceForPrefetch()}), pointed at that real running proxy's
 * {@code /roads/interpreter} path.
 *
 * <p>This is deliberately NOT a Roads-side fake proxy test (that is what
 * {@code RoadNetworkLoopbackProxyTest} already is, by its own explicit disclosure) and NOT a
 * proxy-side-only offline test (that is what {@code overpass-cache-proxy}'s own {@code
 * test_proxy.py} already is). Both components here are real, running processes.
 *
 * <h2>Honest scope: this test requires external setup this repo cannot itself provide</h2>
 * It needs a real {@code python3} on {@code PATH} and a local checkout of the
 * {@code overpass-cache-proxy} repository (at the exact compatibility-fix commit) whose path is
 * supplied via the {@code earthplatformroads.testProxyRepoPath} system property or the
 * {@code EARTHPLATFORMROADS_TEST_PROXY_REPO_PATH} environment variable -- there is no mechanism in
 * this repo's own Gradle build to vendor or build a second repository's Python package. Missing
 * either prerequisite SKIPS this test via {@link org.junit.jupiter.api.Assumptions} (reported
 * distinctly as skipped, never as a silent pass) rather than fabricating a result.
 */
class RoadNetworkRealProxyIntegrationTest {
    private static final String CANNED_UPSTREAM_RESPONSE = """
            {"elements":[{"type":"way","id":991001,"nodes":[1,2],
              "tags":{"highway":"secondary","name":"Real Two-Process Integration Street"},
              "geometry":[{"lat":41.0,"lon":-96.0},{"lat":41.001,"lon":-96.001}]}]}
            """;

    private HttpServer fakeUpstream;
    private Process proxyProcess;
    private final StringBuilder proxyOutput = new StringBuilder();

    @AfterEach
    void cleanUp() throws InterruptedException {
        if (fakeUpstream != null) fakeUpstream.stop(0);
        if (proxyProcess != null) {
            proxyProcess.destroy();
            if (!proxyProcess.waitFor(5, TimeUnit.SECONDS)) {
                proxyProcess.destroyForcibly();
                proxyProcess.waitFor(5, TimeUnit.SECONDS);
            }
        }
        System.clearProperty("earthplatformroads.loopbackProxyEnabled");
        System.clearProperty("earthplatformroads.loopbackProxyEndpoint");
        RoadNetwork.setDefaultTileSourceInputsForTests(null, null);
    }

    @Test
    void realPythonProxyAndRealRoadsProductionWiringAgreeOnColdThenWarmCounters(
            @TempDir Path coldCacheRoot, @TempDir Path warmCacheRoot) throws IOException, InterruptedException {
        assumeTrue(isPython3OnPath(), "python3 not found on PATH -- skipping real two-process integration test");
        String proxyRepoPath = System.getProperty(
                "earthplatformroads.testProxyRepoPath",
                System.getenv("EARTHPLATFORMROADS_TEST_PROXY_REPO_PATH"));
        assumeTrue(proxyRepoPath != null && !proxyRepoPath.isBlank(),
                "earthplatformroads.testProxyRepoPath / EARTHPLATFORMROADS_TEST_PROXY_REPO_PATH not set"
                        + " -- skipping (this repo cannot itself vendor the overpass-cache-proxy checkout)");
        Path proxyRepo = Path.of(proxyRepoPath);
        assumeTrue(Files.isDirectory(proxyRepo.resolve("overpass_cache_proxy")),
                "Configured proxy repo path does not look like an overpass-cache-proxy checkout: " + proxyRepo);

        // --- A real fake upstream (plays Overpass.ch from the PROXY's own point of view; the proxy
        // itself, not this test, decides what path/headers/encoding to use against it) ---
        AtomicInteger upstreamHits = new AtomicInteger();
        fakeUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeUpstream.createContext("/", exchange -> {
            upstreamHits.incrementAndGet();
            byte[] body = CANNED_UPSTREAM_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fakeUpstream.start();
        int upstreamPort = fakeUpstream.getAddress().getPort();

        // --- A real Python overpass-cache-proxy subprocess, pointed at that fake upstream ---
        int proxyPort = findFreeLoopbackPort();
        Path proxySqlitePath = Files.createTempFile("minecraft566-real-proxy-cache", ".sqlite3");
        Files.deleteIfExists(proxySqlitePath); // the proxy creates this file itself on first use

        ProcessBuilder pb = new ProcessBuilder("python3", "-m", "overpass_cache_proxy");
        pb.directory(proxyRepo.toFile());
        pb.environment().put("PROXY_BIND_HOST", "127.0.0.1");
        pb.environment().put("PROXY_BIND_PORT", String.valueOf(proxyPort));
        pb.environment().put("PROXY_SQLITE_PATH", proxySqlitePath.toString());
        pb.environment().put("OVERPASS_UPSTREAM_URL", "http://127.0.0.1:" + upstreamPort + "/api/v1/interpreter");
        pb.redirectErrorStream(true);
        proxyProcess = pb.start();
        drainProxyOutputAsync();

        String proxyBase = "http://127.0.0.1:" + proxyPort;
        waitForProxyReady(proxyBase, Duration.ofSeconds(20));

        // --- The real production wiring, pointed at the real running proxy via the exact seam
        // RoadNetworkLoopbackProxyTest#theOptInSystemPropertySwitchRedirectsTheRealProductionWiring
        // already uses (John comment 55661's own pointer) ---
        System.setProperty("earthplatformroads.loopbackProxyEnabled", "true");
        System.setProperty("earthplatformroads.loopbackProxyEndpoint", proxyBase + "/roads/interpreter");

        RoadNetwork.setDefaultTileSourceInputsForTests(null, coldCacheRoot);
        RoadTileSource coldTileSource = RoadNetwork.liveTileSourceForPrefetch();
        assertNotNull(coldTileSource, "Expected a live tile source once pointed at the real running proxy");

        JsonObject before = fetchProxyStats(proxyBase);
        RoadFetchTelemetry.Snapshot coldTelemetryBefore = coldTileSource.telemetry().snapshot();
        List<RoadSegment> coldWays = coldTileSource.waysForCell(41.0005, -96.0005);
        RoadFetchTelemetry.Snapshot coldTelemetryDelta = coldTileSource.telemetry().snapshot().minus(coldTelemetryBefore);
        JsonObject afterCold = fetchProxyStats(proxyBase);

        assertEquals(1, upstreamHits.get(), "Expected exactly 1 real fake-upstream hit for the cold run");
        assertEquals(1, afterCold.get("upstream_sends").getAsInt() - before.get("upstream_sends").getAsInt(),
                "Expected +1 real proxy-side upstream send for the cold run");
        assertEquals(0, afterCold.get("local_hits").getAsInt() - before.get("local_hits").getAsInt(),
                "Expected +0 proxy-side local hits for the cold run");
        // MINECRAFT-566 comment 55663: measure the actual Roads-side send
        // directly from RoadFetchTelemetry (the same snapshot()/minus()/
        // httpSends() accessor RoadNetworkLoopbackProxyTest already uses),
        // not inferred from the proxy's own counters.
        assertEquals(1, coldTelemetryDelta.httpSends(), "Expected exactly 1 real Roads-side HTTP send for the cold run");
        assertFalse(coldWays.isEmpty(), "Expected the real proxy's forwarded response to actually decode into RoadSegments");
        assertEquals(1, coldWays.size(), "Expected exactly the one way the fake upstream's fixture carries");
        RoadSegment decoded = coldWays.get(0);
        assertEquals(991001L, decoded.wayId());
        assertEquals("Real Two-Process Integration Street", decoded.name());
        assertEquals(RoadClass.SECONDARY, decoded.roadClass());

        // --- A genuinely fresh Roads-side instance/cache (new RoadTileSource, new on-disk cache
        // directory, empty memory cache for this cell), but the SAME still-running proxy process --
        // its SQLite cache stays warm from the cold run above. ---
        RoadNetwork.setDefaultTileSourceInputsForTests(null, warmCacheRoot);
        RoadTileSource warmTileSource = RoadNetwork.liveTileSourceForPrefetch();
        assertNotNull(warmTileSource);
        assertNotEquals(coldTileSource, warmTileSource, "Expected setDefaultTileSourceInputsForTests to build a genuinely fresh RoadTileSource");

        RoadFetchTelemetry.Snapshot warmTelemetryBefore = warmTileSource.telemetry().snapshot();
        List<RoadSegment> warmWays = warmTileSource.waysForCell(41.0005, -96.0005); // one real Roads HTTP send to the proxy
        RoadFetchTelemetry.Snapshot warmTelemetryDelta = warmTileSource.telemetry().snapshot().minus(warmTelemetryBefore);
        JsonObject afterWarm = fetchProxyStats(proxyBase);

        assertEquals(1, upstreamHits.get(), "Expected +0 further fake-upstream hits against the warm proxy cache");
        assertEquals(0, afterWarm.get("upstream_sends").getAsInt() - afterCold.get("upstream_sends").getAsInt(),
                "Expected +0 further proxy-side upstream sends for the warm run");
        assertEquals(1, afterWarm.get("local_hits").getAsInt() - afterCold.get("local_hits").getAsInt(),
                "Expected +1 proxy-side SQLite (local) hit for the warm run");
        // The actual Roads-side send still happens on warm -- Roads itself
        // has no way to know its request will be served from the proxy's
        // SQLite cache rather than a real upstream fetch; it always sends
        // once per call. Measured the same way as the cold run above, via
        // RoadFetchTelemetry, not inferred from the proxy's local-hit count.
        assertEquals(1, warmTelemetryDelta.httpSends(), "Expected exactly 1 real Roads-side HTTP send for the warm run too");
        assertFalse(warmWays.isEmpty());
        assertEquals(coldWays.size(), warmWays.size());
        assertEquals(decoded.wayId(), warmWays.get(0).wayId());

        System.out.println("MINECRAFT-566 real two-process pair acceptance -- cold: roadsHttpSends=" + coldTelemetryDelta.httpSends()
                + " upstream_hits=" + upstreamHits.get()
                + " proxy upstream_sends delta=" + (afterCold.get("upstream_sends").getAsInt() - before.get("upstream_sends").getAsInt())
                + " proxy local_hits delta=" + (afterCold.get("local_hits").getAsInt() - before.get("local_hits").getAsInt())
                + " decodedWays=" + coldWays.size()
                + " | warm: roadsHttpSends=" + warmTelemetryDelta.httpSends()
                + " upstream_hits=" + upstreamHits.get()
                + " proxy upstream_sends delta=" + (afterWarm.get("upstream_sends").getAsInt() - afterCold.get("upstream_sends").getAsInt())
                + " proxy local_hits delta=" + (afterWarm.get("local_hits").getAsInt() - afterCold.get("local_hits").getAsInt())
                + " decodedWays=" + warmWays.size());
    }

    private static boolean isPython3OnPath() {
        try {
            Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            return exited && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static int findFreeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void drainProxyOutputAsync() {
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proxyProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (proxyOutput) {
                        proxyOutput.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // Process exited; nothing further to drain.
            }
        }, "minecraft566-proxy-subprocess-output-drain");
        drain.setDaemon(true);
        drain.start();
    }

    private void waitForProxyReady(String proxyBase, Duration timeout) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        Instant deadline = Instant.now().plus(timeout);
        IOException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            if (!proxyProcess.isAlive()) {
                synchronized (proxyOutput) {
                    throw new IllegalStateException(
                            "The real proxy subprocess exited before becoming ready (exit code "
                                    + proxyProcess.exitValue() + "). Captured stdout/stderr:\n" + proxyOutput);
                }
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/_internal/stats"))
                        .timeout(Duration.ofSeconds(1)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return;
            } catch (IOException e) {
                lastFailure = e; // not up yet; keep polling until the deadline
            }
            Thread.sleep(100);
        }
        synchronized (proxyOutput) {
            throw new IllegalStateException(
                    "The real proxy subprocess never became ready within " + timeout
                            + ". Captured stdout/stderr:\n" + proxyOutput, lastFailure);
        }
    }

    private static JsonObject fetchProxyStats(String proxyBase) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/_internal/stats"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
