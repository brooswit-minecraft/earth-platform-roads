package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MINECRAFT-567: validates {@link RoadFetchTelemetry}'s six aggregate counters against the exact
 * call sites they instrument in {@link RoadTileSource}, using a temp cache directory and a
 * loopback/in-process {@link HttpServer} stub -- never a live provider call.
 *
 * <p>No test here (or anywhere else in this suite) ever asserts on, logs, or even constructs a
 * coordinate/endpoint/header/query/key string derived from telemetry -- only the plain integer
 * counts {@link RoadFetchTelemetry.Snapshot} exposes.
 */
class RoadTileSourceTelemetryTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private static final String ONE_WAY_RESPONSE = """
            {"elements":[{"type":"way","id":555101,"nodes":[1,2],
              "tags":{"highway":"secondary","name":"Telemetry Street"},
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

    /** A cold cache miss that resolves to a real, non-empty JSON tile: one HTTP send, one JSON payload read, zero of everything else. */
    @Test
    void aColdJsonReadCountsOneHttpSendAndOneJsonPayloadRead(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        var ways = source.waysForCell(40.0005, -105.0005);
        assertEquals(1, ways.size());

        RoadFetchTelemetry.Snapshot snapshot = source.telemetry().snapshot();
        assertEquals(1, snapshot.httpSends(), "Expected exactly one real outbound send for the cold fetch");
        assertEquals(0, snapshot.suppressedRequests());

        // The in-flight fetch itself writes straight into memory (attemptFetch), so reading this
        // cell back from a FRESH instance is what actually exercises the real disk .json payload
        // read -- the first instance never re-reads what it just wrote.
        RoadTileSource restarted = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));
        var fromDisk = restarted.cachedWaysForCellOrEmpty(40.0005, -105.0005);
        assertEquals(1, fromDisk.size());

        RoadFetchTelemetry.Snapshot restartedSnapshot = restarted.telemetry().snapshot();
        assertEquals(1, restartedSnapshot.jsonPayloadReads(), "Expected exactly one actual .json payload read");
        assertEquals(0, restartedSnapshot.emptyMarkerPayloadReads());
        assertEquals(0, restartedSnapshot.metadataZeroByteChecks());
        assertEquals(0, restartedSnapshot.httpSends(), "A fresh instance reading its own cache root cold must not refetch");
    }

    /** A genuinely empty fetch result writes a non-zero-byte .empty marker (real JSON, empty ways) -- reading it back is a real payload read, not a metadata-only check. */
    @Test
    void anEmptyMarkerReadFromARealFetchCountsAsAPayloadReadNotAMetadataCheck(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, EMPTY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));
        assertTrue(source.waysForCell(41.0, -106.0).isEmpty());

        RoadTileSource restarted = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));
        assertTrue(restarted.cachedWaysForCellOrEmpty(41.0, -106.0).isEmpty());

        RoadFetchTelemetry.Snapshot snapshot = restarted.telemetry().snapshot();
        assertEquals(1, snapshot.emptyMarkerPayloadReads(), "Expected the non-zero-byte .empty marker to be read as a real payload");
        assertEquals(0, snapshot.metadataZeroByteChecks());
        assertEquals(0, snapshot.jsonPayloadReads());
    }

    /** A hand-seeded, zero-byte .empty marker short-circuits on a metadata-only size check, with no payload read at all. */
    @Test
    void aZeroByteEmptyMarkerCountsAsAMetadataCheckNotAPayloadRead(@TempDir Path cacheRoot) throws IOException {
        double lat = 60.0005, lon = -170.0005;
        Path emptyMarker = cacheRoot.resolve("6000_-17001.empty");
        Files.createDirectories(cacheRoot);
        Files.writeString(emptyMarker, "");
        RoadTileSource source = new RoadTileSource(URI.create("http://127.0.0.1:1/"), cacheRoot, false, Duration.ofSeconds(5));

        assertTrue(source.cachedWaysForCellOrEmpty(lat, lon).isEmpty());

        RoadFetchTelemetry.Snapshot snapshot = source.telemetry().snapshot();
        assertEquals(1, snapshot.metadataZeroByteChecks(), "Expected the zero-byte marker to be validated via a metadata-only check");
        assertEquals(0, snapshot.emptyMarkerPayloadReads(), "Expected no payload read at all for a zero-byte marker");
        assertEquals(0, snapshot.jsonPayloadReads());
        assertEquals(0, snapshot.httpSends(), "allowNetwork=false here: nothing should ever be dispatched");
    }

    /** A second access to an already-resolved cell, on the same instance, is served from memory with zero further disk I/O. */
    @Test
    void aSecondAccessOnTheSameInstanceCountsAsAMemoryHit(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        var first = source.waysForCell(42.0, -107.0);
        assertEquals(1, first.size());
        assertEquals(0, source.telemetry().snapshot().memoryHits(), "Expected no memory hit yet on the very first (cold-fetch) access");

        var second = source.cachedWaysForCellOrEmpty(42.0, -107.0);
        assertEquals(1, second.size());
        assertEquals(1, requests.get(), "Expected no additional HTTP request for the memory-served second access");

        RoadFetchTelemetry.Snapshot snapshot = source.telemetry().snapshot();
        assertTrue(snapshot.memoryHits() >= 1, "Expected at least one recorded memory hit on the second access");
    }

    /**
     * MINECRAFT-567: "actual outbound HTTP sends (including real retry sends, excluding suppressed
     * requests)". Mirrors RoadTileSourceTest's own minimum-request-interval scenario: a too-soon
     * second attempt for a DIFFERENT cell is declined before ever reaching the network (a
     * suppressed request, never a send); once the interval genuinely elapses, a real retry send for
     * that same cell IS counted.
     */
    @Test
    void aRealRetrySendIsCountedWhileASuppressedRequestIsNot(@TempDir Path cacheRoot) throws IOException, InterruptedException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, EMPTY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5), null, Duration.ofMillis(300));

        source.waysForCell(50.0, -1.0); // first cell: eligible immediately, one real send
        assertEquals(1, requests.get());
        assertEquals(1, source.telemetry().snapshot().httpSends());

        var tooSoon = source.waysForCell(51.0, -2.0); // second, different cell: declined by the cooldown, no send
        assertTrue(tooSoon.isEmpty());
        assertEquals(1, requests.get(), "Expected no second HTTP request while not yet eligible");

        RoadFetchTelemetry.Snapshot afterSuppressed = source.telemetry().snapshot();
        assertEquals(1, afterSuppressed.httpSends(), "Expected the suppressed attempt to NOT be counted as a send");
        assertEquals(1, afterSuppressed.suppressedRequests(), "Expected exactly one suppressed (deduped-by-cooldown) request recorded");

        Thread.sleep(300);
        var afterInterval = source.waysForCell(51.0, -2.0); // the same cell, now genuinely eligible: a real retry send
        assertTrue(afterInterval.isEmpty(), "This stub always returns an empty result");
        assertEquals(2, requests.get(), "Expected the retry after the interval elapsed to actually request");

        RoadFetchTelemetry.Snapshot afterRetry = source.telemetry().snapshot();
        assertEquals(2, afterRetry.httpSends(), "Expected the genuine retry send to be counted");
        assertEquals(1, afterRetry.suppressedRequests(), "Expected the earlier suppressed count to remain unchanged by the real retry");
    }

    /** {@link RoadFetchTelemetry.Snapshot#minus} and {@link RoadFetchTelemetry#deltaSinceLastCall()} compute the expected field-by-field difference. */
    @Test
    void snapshotDeltaReflectsOnlyWhatHappenedSinceTheLastCall(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));

        RoadFetchTelemetry.Snapshot baseline = source.telemetry().deltaSinceLastCall(); // establishes the baseline; itself a delta of zero against the initial state
        assertEquals(0, baseline.httpSends());

        source.waysForCell(43.0, -108.0);
        source.cachedWaysForCellOrEmpty(43.0, -108.0); // a memory hit

        RoadFetchTelemetry.Snapshot delta = source.telemetry().deltaSinceLastCall();
        assertEquals(1, delta.httpSends(), "Expected the delta to reflect exactly the one send that happened since the baseline");
        assertTrue(delta.memoryHits() >= 1, "Expected the delta to reflect the memory hit that happened since the baseline");

        RoadFetchTelemetry.Snapshot secondDelta = source.telemetry().deltaSinceLastCall();
        assertEquals(0, secondDelta.httpSends(), "Expected a delta taken immediately after, with no new activity, to be all zero");
        assertEquals(0, secondDelta.memoryHits());
    }

    /** No telemetry field or log line this suite touches is anything other than a plain aggregate count. */
    @Test
    void theTelemetrySnapshotExposesOnlyAggregateIntegerCounts(@TempDir Path cacheRoot) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer stub = startStub(requests, ONE_WAY_RESPONSE, 200);
        RoadTileSource source = new RoadTileSource(endpointOf(stub), cacheRoot, true, Duration.ofSeconds(5));
        source.waysForCell(44.0, -109.0);

        for (var recordComponent : RoadFetchTelemetry.Snapshot.class.getRecordComponents()) {
            assertEquals(long.class, recordComponent.getType(),
                    "Expected every Snapshot field to be a plain long aggregate count, found: " + recordComponent.getType());
        }

        String snapshotText = source.telemetry().snapshot().toString();
        String stubHostPort = endpointOf(stub).getAuthority();
        assertFalse(snapshotText.contains(stubHostPort), "The Snapshot's own toString must never mention the endpoint host/port");
        assertFalse(snapshotText.contains("44.0") || snapshotText.contains("-109.0"),
                "The Snapshot's own toString must never mention a coordinate");
    }
}
