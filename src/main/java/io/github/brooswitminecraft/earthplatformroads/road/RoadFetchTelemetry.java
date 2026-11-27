package io.github.brooswitminecraft.earthplatformroads.road;

import io.github.brooswitminecraft.earthplatformroads.EarthPlatformRoadsMod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MINECRAFT-567: local, in-process AGGREGATE counters for {@link RoadTileSource}'s own real
 * cache/network call sites -- "count actual JSON and empty-marker payload read attempts
 * separately from metadata-only zero-byte checks, memory hits, and actual outbound HTTP sends
 * (including real retry sends, excluding suppressed requests)."
 *
 * <p>Every counter here is a plain aggregate {@code long}; nothing in this class, its
 * {@link Snapshot}, or its own low-frequency reporter EVER records (or logs) a coordinate,
 * endpoint, header, query body, or key -- only the six integer counts below ever appear anywhere
 * this class touches. See {@code RoadTileSourceTelemetryTest} for the test coverage enforcing
 * this.
 *
 * <p>Thread-safe (every counter is a plain {@link AtomicLong}; {@link #snapshot()} reads them in
 * one deterministic order but is not a single atomic transaction across all six -- a caller may
 * rarely observe a snapshot with, e.g., one more HTTP send counted than payload reads it
 * logically preceded, under real concurrent traffic; this is accepted as the cost of counters
 * this cheap, and does not affect correctness of any of the three call sites that read them,
 * none of which make a correctness decision based on the counters at all -- they are pure,
 * read-only instrumentation. Deliberately cheap: a counter increment plus (only when reporting
 * is enabled) a single volatile read to check the reporting cadence, never a lock, never I/O on
 * the hot path.
 *
 * <p>The six categories, matching MINECRAFT-567's own six distinctions:
 * <ul>
 *   <li>{@link #recordJsonPayloadRead()} -- an actual read of a ready ({@code .json}) tile's
 *       payload ({@code RoadTileSource#readDiskCacheUncached}'s {@code readyFile} branch).</li>
 *   <li>{@link #recordEmptyMarkerPayloadRead()} -- an actual read of a NON-zero-byte
 *       {@code .empty} marker's own payload, to validate it genuinely parses as a confirmed-empty
 *       marker ({@code RoadTileSource#isValidEmptyMarker}'s non-zero-size branch).</li>
 *   <li>{@link #recordMetadataZeroByteCheck()} -- a metadata-only check that short-circuited
 *       WITHOUT ever reading a payload: the zero-byte check on a {@code .empty} marker that is
 *       trusted outright because it is exactly zero bytes
 *       ({@code RoadTileSource#isValidEmptyMarker}'s own zero-size branch).</li>
 *   <li>{@link #recordMemoryHit()} -- an already-resolved cell served straight from the
 *       in-memory tile cache, with zero disk I/O at all.</li>
 *   <li>{@link #recordHttpSend()} -- one real outbound HTTP request actually sent to the
 *       configured provider, including a genuine retry-after-cooldown send; never incremented
 *       for a request this instance declined to even dispatch.</li>
 *   <li>{@link #recordSuppressedRequest()} -- a fetch attempt declined before it ever reached
 *       the network (the {@code nextEligibleRequestAt} cooldown gate) -- counted separately so it
 *       is never mistaken for a real send.</li>
 * </ul>
 */
public final class RoadFetchTelemetry {
    private final AtomicLong jsonPayloadReads = new AtomicLong();
    private final AtomicLong emptyMarkerPayloadReads = new AtomicLong();
    private final AtomicLong metadataZeroByteChecks = new AtomicLong();
    private final AtomicLong memoryHits = new AtomicLong();
    private final AtomicLong httpSends = new AtomicLong();
    private final AtomicLong suppressedRequests = new AtomicLong();

    private final boolean reportingEnabled;
    private final Duration minReportInterval;
    private final AtomicReference<Instant> nextReportEligibleAt = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Snapshot> reporterBaseline = new AtomicReference<>(Snapshot.ZERO);
    private final AtomicReference<Snapshot> publicDeltaBaseline = new AtomicReference<>(Snapshot.ZERO);

    /** Test-only: overridable clock the reporting cadence in {@link #maybeReport()} is measured against. */
    private volatile Clock clock = Clock.systemUTC();

    /** Reporting off by default -- MINECRAFT-567: "optional low-frequency local reporting ... opt-in flag, off by default." */
    public RoadFetchTelemetry() {
        this(false, Duration.ofSeconds(300));
    }

    /**
     * @param reportingEnabled Opt-in gate for {@link #maybeReport()}'s log line; {@code false}
     *                         (the default, single-arg constructor) means {@link #maybeReport()}
     *                         is always a no-op, regardless of {@code minReportInterval}.
     * @param minReportInterval The minimum real-world time between one low-frequency report log
     *                          line and the next, regardless of how many events fire in between.
     *                          {@code null} falls back to a conservative 300-second default.
     */
    public RoadFetchTelemetry(boolean reportingEnabled, Duration minReportInterval) {
        this.reportingEnabled = reportingEnabled;
        this.minReportInterval = minReportInterval == null ? Duration.ofSeconds(300) : minReportInterval;
    }

    /** Test-only: replace the clock {@link #maybeReport()}'s cadence is measured against, so a test can advance time without a real sleep. */
    void setClockForTests(Clock testClock) {
        this.clock = testClock;
    }

    /** An immutable, cheap-to-copy value object: one aggregate count per MINECRAFT-567 category, nothing else. */
    public record Snapshot(
            long jsonPayloadReads,
            long emptyMarkerPayloadReads,
            long metadataZeroByteChecks,
            long memoryHits,
            long httpSends,
            long suppressedRequests) {

        static final Snapshot ZERO = new Snapshot(0, 0, 0, 0, 0, 0);

        /** This snapshot minus an earlier one, field by field -- the delta between two points in time. */
        public Snapshot minus(Snapshot earlier) {
            return new Snapshot(
                    jsonPayloadReads - earlier.jsonPayloadReads,
                    emptyMarkerPayloadReads - earlier.emptyMarkerPayloadReads,
                    metadataZeroByteChecks - earlier.metadataZeroByteChecks,
                    memoryHits - earlier.memoryHits,
                    httpSends - earlier.httpSends,
                    suppressedRequests - earlier.suppressedRequests);
        }
    }

    /** A cheap, immutable copy of every counter's current value. Not a single atomic transaction across all six -- see this class's own doc. */
    public Snapshot snapshot() {
        return new Snapshot(
                jsonPayloadReads.get(),
                emptyMarkerPayloadReads.get(),
                metadataZeroByteChecks.get(),
                memoryHits.get(),
                httpSends.get(),
                suppressedRequests.get());
    }

    /**
     * The delta since the LAST call to this exact method on this instance (a dedicated running
     * baseline, separate from {@link #maybeReport()}'s own internal one) -- the cheap
     * "delta-since-last-snapshot" helper MINECRAFT-567 asks for, so a caller never has to hold
     * onto its own previous {@link Snapshot} just to compute one.
     */
    public Snapshot deltaSinceLastCall() {
        Snapshot current = snapshot();
        Snapshot previous = publicDeltaBaseline.getAndSet(current);
        return current.minus(previous);
    }

    public void recordJsonPayloadRead() { jsonPayloadReads.incrementAndGet(); maybeReport(); }
    public void recordEmptyMarkerPayloadRead() { emptyMarkerPayloadReads.incrementAndGet(); maybeReport(); }
    public void recordMetadataZeroByteCheck() { metadataZeroByteChecks.incrementAndGet(); maybeReport(); }
    public void recordMemoryHit() { memoryHits.incrementAndGet(); maybeReport(); }
    public void recordHttpSend() { httpSends.incrementAndGet(); maybeReport(); }
    public void recordSuppressedRequest() { suppressedRequests.incrementAndGet(); maybeReport(); }

    /**
     * MINECRAFT-567's "optional low-frequency local reporting" -- logs ONLY the aggregate
     * snapshot/delta numbers above, at most once per {@link #minReportInterval} regardless of how
     * many events fire in between, and only when {@link #reportingEnabled}. Never logs a
     * coordinate, endpoint, header, query body, or key -- there is nothing else in this object
     * for it to log.
     */
    private void maybeReport() {
        if (!reportingEnabled) return;
        Instant now = clock.instant();
        Instant eligible = nextReportEligibleAt.get();
        if (now.isBefore(eligible)) return;
        if (!nextReportEligibleAt.compareAndSet(eligible, now.plus(minReportInterval))) return; // another thread already won this report slot
        Snapshot total = snapshot();
        Snapshot previous = reporterBaseline.getAndSet(total);
        Snapshot delta = total.minus(previous);
        EarthPlatformRoadsMod.LOGGER.info(
                "Earth Platform Roads fetch telemetry -- total[jsonReads={}, emptyMarkerReads={}, "
                        + "metadataZeroByteChecks={}, memoryHits={}, httpSends={}, suppressed={}] "
                        + "delta[jsonReads={}, emptyMarkerReads={}, metadataZeroByteChecks={}, "
                        + "memoryHits={}, httpSends={}, suppressed={}]",
                total.jsonPayloadReads(), total.emptyMarkerPayloadReads(), total.metadataZeroByteChecks(),
                total.memoryHits(), total.httpSends(), total.suppressedRequests(),
                delta.jsonPayloadReads(), delta.emptyMarkerPayloadReads(), delta.metadataZeroByteChecks(),
                delta.memoryHits(), delta.httpSends(), delta.suppressedRequests());
    }
}
