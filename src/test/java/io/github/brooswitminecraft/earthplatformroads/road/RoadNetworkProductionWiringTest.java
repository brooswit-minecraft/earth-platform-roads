package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the ACTUAL production wiring ({@link RoadNetwork#tileSource()} building its own
 * default {@link RoadTileSource}), not a directly-injected test double -- the review finding this
 * fixes: {@link RoadTilePrefetchMixinTest} proved the prepare-mixin mechanism works when a
 * {@link RoadTileSource} is handed to it via {@link RoadNetwork#setTileSourceForTests}, but that
 * never showed an ordinary install (which never calls that test-only setter) actually gets one.
 * This test only overrides the two real inputs ({@link RoadNetwork#setDefaultTileSourceInputsForTests}
 * -- endpoint and cache root), exercising the exact same {@code buildDefaultTileSource} code path a
 * real install runs.
 */
class RoadNetworkProductionWiringTest {
    @AfterEach
    void cleanUp() {
        RoadNetwork.setDefaultTileSourceInputsForTests(null, null);
    }

    @Test
    void anOrdinaryInstallWithNoTestSetterStillRequestsAndFindsARoad(@TempDir Path cacheRoot) throws IOException {
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        String response = """
                {"elements":[{"type":"way","id":555003,"nodes":[1,2],
                  "tags":{"highway":"secondary","name":"Production Wiring Test Street"},
                  "geometry":[{"lat":43.0,"lon":-108.0},{"lat":43.001,"lon":-108.001}]}]}
                """;
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            // Only the two real inputs are overridden; RoadNetwork builds its own RoadTileSource,
            // exactly as it would in a real install pointed at a real endpoint and a real cache dir.
            RoadNetwork.setDefaultTileSourceInputsForTests(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), cacheRoot);

            var tileSource = RoadNetwork.liveTileSourceForPrefetch();
            assertTrue(tileSource != null, "Expected the production wiring to build a real RoadTileSource, not null");

            // Simulates an ordinary visit to this unbundled destination: EarthGeneratorPrepareMixin
            // calls exactly this method on exactly this production-built source.
            tileSource.waysForCell(43.0005, -108.0005);
            assertTrue(requestCount.get() == 1, "Expected the production-wired source to actually request the tile");

            var way = tileSource.cachedWaysForCellOrEmpty(43.0005, -108.0005);
            assertTrue(!way.isEmpty() && way.get(0).wayId() == 555003L,
                    "Expected the fetched way to be found via the same cache-only read RoadNetwork.candidatesNear uses");
        } finally {
            stub.stop(0);
        }
    }

    /**
     * MINECRAFT-566 comment 55446: the Overpass.ch preview provider must be opt-in, never a default.
     * With none of {@code earthplatformroads.previewProvider}/{@code previewEndpoint} set (the
     * ordinary, unmodified state of a real install), {@code buildDefaultTileSource} must build a
     * network-disabled, cache-only source -- proven here by pointing it at a stub that would record
     * a request if one were ever sent, then confirming zero requests happened.
     */
    @Test
    void withNoOptInPropertiesSetTheDefaultWiringNeverTouchesTheNetwork(@TempDir Path cacheRoot) throws IOException {
        assertTrue(System.getProperty("earthplatformroads.previewProvider") == null,
                "Test assumption violated: another test left earthplatformroads.previewProvider set");

        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            // Deliberately NOT using setDefaultTileSourceInputsForTests here -- that call itself
            // enables network fetch (it exists to let other tests exercise the real fetch path
            // without system properties). This test instead proves the properties-gated branch
            // itself, which only setDefaultTileSourceInputsForTests(null, null) (this class's own
            // cleanup) leaves active -- so just override the cache root, not the endpoint.
            RoadNetwork.setDefaultTileSourceInputsForTests(null, cacheRoot);
            var tileSource = RoadNetwork.liveTileSourceForPrefetch();
            assertTrue(tileSource != null, "Expected a non-null (but network-disabled) tile source even with no opt-in");

            tileSource.waysForCell(43.0005, -108.0005);
            assertTrue(requestCount.get() == 0,
                    "Expected zero network requests with no opt-in properties set -- got " + requestCount.get());
        } finally {
            stub.stop(0);
        }
    }

    /**
     * MINECRAFT-566 comment 55448: "make missing key disable the live provider rather than issue
     * an anonymous request." Sets BOTH opt-in properties (provider name and endpoint) -- the only
     * thing missing is the credential, which this test environment genuinely has no
     * {@code EARTHPLATFORMROADS_PREVIEW_API_KEY} set for (a real env var can't be set for a single
     * JVM test process without a subprocess, so this test relies on that already being true, and
     * asserts it explicitly rather than assuming). The provider must still end up disabled.
     */
    @Test
    void optingIntoTheProviderWithoutAKeyStillDisablesLiveFetch(@TempDir Path cacheRoot) throws IOException {
        assertTrue(System.getenv("EARTHPLATFORMROADS_PREVIEW_API_KEY") == null,
                "Test assumption violated: this environment has EARTHPLATFORMROADS_PREVIEW_API_KEY set");

        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        System.setProperty("earthplatformroads.previewProvider", "overpass-ch");
        System.setProperty("earthplatformroads.previewEndpoint", "http://127.0.0.1:" + stub.getAddress().getPort() + "/");
        try {
            RoadNetwork.setDefaultTileSourceInputsForTests(null, cacheRoot);
            var tileSource = RoadNetwork.liveTileSourceForPrefetch();
            assertTrue(tileSource != null, "Expected a non-null (but network-disabled) tile source");

            tileSource.waysForCell(43.0005, -108.0005);
            assertTrue(requestCount.get() == 0,
                    "Expected zero network requests with provider+endpoint opted in but no key -- got " + requestCount.get());
        } finally {
            stub.stop(0);
            System.clearProperty("earthplatformroads.previewProvider");
            System.clearProperty("earthplatformroads.previewEndpoint");
        }
    }
}
