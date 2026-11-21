package io.github.brooswitminecraft.earthplatformroads.road;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fetches mapped road data for a tile (the same {@value RoadNetwork#CELL_DEGREES}-degree grid cell
 * the spatial index already uses) the first time generation needs it, caches it to disk, and
 * answers every later request for that cell from the cache -- modeled directly on
 * {@code io.earthplatform.geography.globe.TerrariumTileSource}, the pattern this mod's own Earth
 * Platform dependency already uses for exactly this generation-time constraint (a synchronous,
 * timeout-bounded network call the first time a tile is touched, a persistent on-disk cache every
 * time after).
 *
 * <p>This exists because a bundled or manually-installed extract (this mod's two earlier attempts
 * at MINECRAFT-566's "coverage wherever he travels" requirement) still requires a player or
 * operator to pick a destination ahead of time. A {@code RoadTileSource} does not: the first cube
 * generated anywhere triggers this cell's own fetch, automatically.
 *
 * <h2>Cache states, and why a failed fetch is not the same as a confirmed-empty one</h2>
 * Each cell on disk is exactly one of:
 * <ul>
 *   <li><b>ready</b> ({@code <cellKey>.json}): the cell was fetched and parsed; its ways are
 *       cached. Every later request for this cell, in this world or a restarted one pointed at the
 *       same cache root, reads this file -- this is what "pins" a world's road data to whatever was
 *       actually fetched, the same way Earth Platform's own elevation cache pins a world's terrain
 *       to whatever DEM tiles it already downloaded.</li>
 *   <li><b>confirmed empty</b> ({@code <cellKey>.empty}): the cell was fetched and the source
 *       genuinely returned zero vehicular ways there. This is a real, permanent answer, not a
 *       failure -- it must stay empty on retry, or every ocean/empty-wilderness cell would be
 *       re-fetched forever.</li>
 *   <li><b>pending</b> (no marker file at all, or a stale {@code <cellKey>.pending}): never
 *       successfully fetched. A network failure or timeout leaves the cell in this state --
 *       deliberately -- so a temporary outage can never be permanently baked in as "no road here."
 *       The column that triggered the failed fetch still generates with no road for that one call
 *       (generation cannot block forever), but the cache itself stays retryable: the very next
 *       access to this cell, including after a server restart, tries the fetch again.</li>
 * </ul>
 */
public final class RoadTileSource {
    private final URI endpoint;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final Duration timeout;
    private final String apiKey;
    private final Duration minRequestInterval;
    private final HttpClient http;
    private final ConcurrentHashMap<Long, List<RoadSegment>> memory = new ConcurrentHashMap<>();
    /**
     * One lock object per cell ever requested from this instance, used only to deduplicate
     * concurrent fetch attempts FOR THE SAME CELL -- deliberately NOT a single global lock (that
     * was MINECRAFT-566 comment 55450's found bug: a global lock around disk reads meant an
     * already-cached, no-network-needed tile could wait behind an unrelated cold cell's provider
     * backoff). A disk-cache hit for cell A never touches cell B's lock, or {@link #networkLock}.
     */
    private final ConcurrentHashMap<Long, Object> cellLocks = new ConcurrentHashMap<>();
    /**
     * Guards only the actual outbound request: one in flight at a time, network-wide across every
     * cell this instance might fetch (the provider's own free-tier parallel=1 limit). Never held
     * while reading or writing this cache's disk files for a DIFFERENT cell -- see {@link #cellLocks}.
     */
    private final Object networkLock = new Object();
    /**
     * The earliest moment the next outbound request may be sent -- combines
     * {@link #minRequestInterval}'s steady cadence and any {@code Retry-After} a 429/503 asked for.
     * A fetch attempted before this deadline is declined immediately as a pending miss (comment
     * 55450: "use a next-eligible deadline rather than sleeping under the global lock") -- this
     * class never calls {@code Thread.sleep} to wait out rate limiting; a caller that hits the
     * deadline just gets no road for that one call, exactly like any other failed fetch, and the
     * cell stays retryable afterward.
     */
    private volatile Instant nextEligibleRequestAt = Instant.EPOCH;
    /**
     * Defaults to the real system clock; overridable only via {@link #setClockForTests} so a test
     * can advance time deterministically (MINECRAFT-566 comment 55466: "add a controlled clock for
     * the actual rate limit") instead of actually sleeping out a multi-second production interval
     * or, worse, using an artificially short one that never really exercises the real cadence.
     */
    private volatile java.time.Clock clock = java.time.Clock.systemUTC();

    /** Test-only: replace the clock {@link #nextEligibleRequestAt} is measured against, so a test can advance time without a real sleep. */
    void setClockForTests(java.time.Clock testClock) {
        this.clock = testClock;
    }

    /**
     * MINECRAFT-566 comment 55474: "bound waiting and fail open deliberately on a sustained
     * provider outage, with distinct diagnostics". A single cell that keeps 504ing is not proof the
     * PROVIDER is down (a dense tile can legitimately be slow/heavy on its own); this instead counts
     * consecutive {@code ATTEMPTED_FAILURE} outcomes ACROSS EVERY cell this instance has tried, reset
     * to zero by any single {@code RESOLVED} fetch (any cell) -- a run of real, dispatched failures
     * with nothing succeeding in between is what actually indicates the provider itself is down, not
     * just one awkward tile. {@code DISPATCH_SUPPRESSED} outcomes (never reached the network at all)
     * do not move this counter either way: they prove nothing about the provider's own health.
     */
    private static final int SUSTAINED_OUTAGE_FAILURE_THRESHOLD = 3;
    private final AtomicInteger consecutiveAttemptedFailures = new AtomicInteger();

    /**
     * Whether this instance's last {@value #SUSTAINED_OUTAGE_FAILURE_THRESHOLD}+ real (dispatched,
     * not suppressed) fetch attempts have ALL failed, with no successful fetch of any cell in
     * between -- {@link RoadNetwork#diagnoseColumn}'s distinct diagnosis for this case, instead of
     * the ordinary "just hasn't been fetched yet" {@code PENDING_NO_DATA}.
     */
    public boolean sustainedOutage() {
        return consecutiveAttemptedFailures.get() >= SUSTAINED_OUTAGE_FAILURE_THRESHOLD;
    }

    // MINECRAFT-566 comments 55535/55544: a `timeUntilNextEligibleRequest()` accessor was added here
    // to let the defer mixin size its own wait against a presumed "real remaining cooldown" -- but
    // John's native reproduction (comment 55544) proved that one-time snapshot fundamentally cannot
    // be trusted: a DIFFERENT, already-in-flight request can still be awaiting its response at the
    // exact moment this would be sampled, and `nextEligibleRequestAt` does not move until that
    // request's own response actually lands -- so the snapshot silently misses the real future
    // cooldown entirely (observed: sampled near-zero while the real eligible slot was still ~5.7s
    // away). Removed entirely rather than patched, since no one-time read of this field can ever be
    // reliable against that race -- see `ServerCubeWorldRoadPrefetchMixin#MAX_DEFER_WAIT`'s own doc
    // for the fix that replaced it (always budget the full hard ceiling for the ordinary case, and
    // let `waysForCellRetryingAcrossCooldown`'s own live re-sampling, each retry iteration, find the
    // real eligible slot once it exists).

    /**
     * MINECRAFT-566 comment 55534: whether this instance can EVER actually reach the network --
     * distinct from merely existing. A cache-only instance ({@code allowNetwork=false}, e.g. the
     * "not opted into the preview provider" default) is a perfectly real, non-null
     * {@code RoadTileSource} that still serves already-cached reads correctly, but every
     * {@code attemptFetch} on it returns {@code DISPATCH_SUPPRESSED} immediately -- nothing waiting
     * on it will ever resolve through a fetch. {@code RoadPrefetch.hasLiveTileSource} uses this
     * (not a plain non-null check) so a cache-only install never enters defer/wait scheduling for a
     * cell that can only ever be resolved by someone else's fetch populating the shared on-disk
     * cache, never its own network call.
     */
    public boolean canFetchLive() {
        return allowNetwork;
    }

    /**
     * Cells enqueued for best-effort, rate-limited catch-up fetching -- MINECRAFT-566 comment
     * 55460: a road continuing as a SECOND, different OSM way ID (sharing only a node with the
     * first) whose geometry lies entirely in a neighbor cell cannot be found by the read-side
     * neighbor check ({@code RoadNetwork.candidatesNear}) the way a single crossing way's full
     * geometry can -- that neighbor genuinely has to be fetched itself at some point. But fetching
     * a whole Moore neighborhood synchronously (c7428bf, reverted in f9931a4) collides with the
     * provider's own rate limit. So instead: {@link #enqueueLookahead} enqueues a point's Moore
     * neighborhood (deduplicated, bounded, skipping anything already cached) and opportunistically
     * drains AT MOST ONE entry per call -- through the exact same {@link #waysForCell} path, so it
     * is subject to the exact same one-in-flight guarantee, {@link #nextEligibleRequestAt} deadline,
     * and three-state (ready/confirmed-empty/pending) cache semantics as every other fetch. A cell
     * that cannot be dequeued yet (not yet eligible) simply stays queued for a later opportunity --
     * this never blocks, sleeps, or bursts multiple requests from one call.
     */
    private static final int LOOKAHEAD_QUEUE_CAPACITY = 64;
    private final java.util.Set<Long> queuedCellKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Deque<long[]> lookaheadQueue = new java.util.concurrent.ConcurrentLinkedDeque<>();

    /**
     * MINECRAFT-566 comment 55476's "bounded-worker" ask: {@code ServerCubeWorld.produce} runs on a
     * background worker-pool thread (see {@code ServerCubeWorldRoadPrefetchMixin}), but that pool is
     * finite and shared across every generating column -- a tall column's many cube-Y layers each
     * independently miss storage and would otherwise each call {@link #waysForCell} for the exact
     * same cell, and whichever one is NOT first would simply block (waiting on {@link #cellLocks})
     * for up to this source's full network timeout. {@link #waysForCellWithBoundedWait} fixes both
     * problems at once: multiple callers for the SAME still-pending cell share (coalesce onto) ONE
     * real fetch via this map, keyed exactly like every other cell-keyed map here, and the actual
     * network wait happens on {@link #ASYNC_FETCH_EXECUTOR} (a single dedicated background thread,
     * never a generation worker), while each CALLER only waits up to its own short, bounded
     * interval before failing open -- the shared fetch keeps running regardless, so a later caller
     * (another Y-level, or simply revisiting the cell) still benefits once it resolves.
     */
    private final ConcurrentHashMap<Long, CompletableFuture<List<RoadSegment>>> inFlightByCell = new ConcurrentHashMap<>();

    /**
     * MINECRAFT-566 comment 55481's "cap pending tiles" ask: an unbounded burst of distinct,
     * simultaneously-cold cells (e.g. a player flying fast across many never-touched columns at
     * once) could otherwise queue an unbounded number of distinct {@link #inFlightByCell} entries
     * on {@link #ASYNC_FETCH_EXECUTOR}'s own unbounded work queue. A NEW cell is admitted only while
     * fewer than this many are already in flight; beyond that, {@link #waysForCellWithBoundedWait}
     * fails open immediately rather than queuing yet another one -- a soft, best-effort cap (the
     * admission check and the actual insert are not atomic together), not a hard guarantee, but
     * enough to keep this bounded rather than growing without limit.
     */
    private static final int MAX_IN_FLIGHT_TILES = 8;

    /** Test-only: the current number of distinct cells with a real fetch in flight, to assert {@link #MAX_IN_FLIGHT_TILES} is actually honored. */
    int inFlightCellCountForTests() {
        return inFlightByCell.size();
    }

    /**
     * MINECRAFT-566 comment 55481's "coalesce lookahead submissions before admission" ask: a tall
     * column's many cube-Y layers would otherwise each call {@link #enqueueLookaheadAsync} for the
     * exact same center cell, each submitting its OWN task to {@link #ASYNC_FETCH_EXECUTOR}'s
     * unbounded queue before any of them actually run -- not bounded by {@link #LOOKAHEAD_QUEUE_CAPACITY}
     * at all, since that cap only bounds the NEIGHBOR cells a single {@link #enqueueLookahead} call
     * enqueues, not how many TIMES {@link #enqueueLookaheadAsync} itself gets invoked. Tracks which
     * center cells currently have an undispatched async lookahead task pending, so a later call for
     * the SAME center cell while one is already queued is simply skipped (free, no task submitted)
     * rather than piling on another.
     */
    private final java.util.Set<Long> pendingLookaheadDispatches = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Test-only: the current number of distinct center cells with an undispatched async lookahead task pending, to assert coalescing actually happened. */
    int pendingLookaheadDispatchCountForTests() {
        return pendingLookaheadDispatches.size();
    }

    /**
     * MINECRAFT-566 comment 55522: an immediate (current-cell, bounded-wait) request and a
     * speculative lookahead request compete for this SAME single thread; a plain FIFO queue let a
     * lookahead job that happened to be queued moments earlier make an immediate job wait behind it
     * for a slot it did not need to wait for. {@code PRIORITY_IMMEDIATE} jobs are always picked
     * ahead of any {@code PRIORITY_LOOKAHEAD} job still WAITING in the queue (ties broken by
     * submission order) -- this cannot preempt a job already RUNNING (the provider's own
     * {@link #networkLock} parallel-1 limit means a genuinely in-flight request must finish
     * regardless of what arrives after it), but it does stop a newly-queued lookahead job from
     * jumping ahead of an immediate job that is, or arrives, still waiting.
     */
    private static final int PRIORITY_IMMEDIATE = 0;
    private static final int PRIORITY_LOOKAHEAD = 1;
    private static final AtomicLong TASK_SEQUENCE = new AtomicLong();

    /** A {@link #ASYNC_FETCH_EXECUTOR} work item ordered by {@link #PRIORITY_IMMEDIATE}/{@link #PRIORITY_LOOKAHEAD} first, submission order second. */
    private static final class PriorityTask implements Runnable, Comparable<PriorityTask> {
        private final int priority;
        private final long sequence;
        private final Runnable delegate;

        PriorityTask(int priority, Runnable delegate) {
            this.priority = priority;
            this.sequence = TASK_SEQUENCE.getAndIncrement();
            this.delegate = delegate;
        }

        @Override
        public void run() {
            delegate.run();
        }

        @Override
        public int compareTo(PriorityTask other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }

    /**
     * One dedicated, shared, daemon background thread that performs every ACTUAL network wait
     * {@link #waysForCellWithBoundedWait} and {@link #enqueueLookaheadAsync} trigger -- never a
     * generation worker thread. A single thread is enough: {@link #networkLock} already permits only
     * one real outbound request across this (or any) instance at a time, so there is never real
     * parallel work for a second thread to pick up here. {@code static} (shared across every
     * {@code RoadTileSource} instance, production included) rather than per-instance, since
     * ordinarily there is exactly one production instance anyway and a per-instance pool would leak
     * one thread per short-lived test instance; daemon so it never blocks JVM/test-runner shutdown.
     * A {@link PriorityBlockingQueue} of {@link PriorityTask} (not the plain FIFO queue
     * {@link java.util.concurrent.Executors#newSingleThreadExecutor} would give) orders WAITING work
     * by priority -- see {@link #PRIORITY_IMMEDIATE}'s own doc. Callers submit {@link PriorityTask}
     * instances directly via {@code execute} (never {@code submit}/{@code supplyAsync}, which would
     * wrap the task in a type {@link PriorityBlockingQueue} cannot compare), so the exact object this
     * queue orders is the one whose priority matters.
     */
    private static final ExecutorService ASYNC_FETCH_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "earthplatformroads-road-tile-fetch");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * {@link #waysForCell} for this cell, but bounded: if the cell is not already resolved
     * (ready/confirmed-empty, checked first, free), the real fetch is dispatched onto
     * {@link #ASYNC_FETCH_EXECUTOR} (coalesced via {@link #inFlightByCell} -- a second caller for
     * the same still-unresolved cell shares the first caller's in-flight future rather than starting
     * a second one) and this call waits at most {@code bound} for it. On timeout, returns an empty
     * list WITHOUT cancelling the fetch -- it keeps running in the background exactly as an ordinary
     * {@link #waysForCell} failure/pending case already would, so nothing is fabricated and the cell
     * stays genuinely retryable; a later access (another Y-level's own call, or simply the next time
     * anything touches this cell) still sees the result once it actually resolves. Never invoked
     * from {@code loadNow}'s synchronous arrival path -- see
     * {@code ServerCubeWorldRoadPrefetchMixin}'s own doc.
     *
     * <p>MINECRAFT-566 comment 55481, a real bug found by pure logical review: the first version
     * registered the {@code inFlightByCell.remove} cleanup callback INSIDE the
     * {@link ConcurrentHashMap#computeIfAbsent} mapping function, before that function had actually
     * returned -- {@code computeIfAbsent} does not publish the new entry into the map until its
     * mapping function returns, so if the dispatched future happened to complete synchronously fast
     * enough that the cleanup callback ran before that return, {@code remove} would find nothing
     * yet to remove (a silent no-op), and {@code computeIfAbsent} would then go ahead and publish
     * the (already-completed) future anyway -- permanently pinning it in the map, so every later
     * caller for that cell would keep reusing that one stale, already-resolved future forever rather
     * than ever dispatching a fresh fetch again. Fixed by registering the cleanup only AFTER
     * {@code computeIfAbsent} returns, when the entry is guaranteed to actually be published.
     */
    public List<RoadSegment> waysForCellWithBoundedWait(double lat, double lon, Duration bound) {
        List<RoadSegment> alreadyResolved = cachedWaysForCellOrEmpty(lat, lon);
        if (!alreadyResolved.isEmpty() || cellState(lat, lon) != CellState.PENDING) return alreadyResolved; // ready, or genuinely confirmed-empty: no wait needed
        long key = cellKeyOf(lat, lon);
        if (!inFlightByCell.containsKey(key) && inFlightByCell.size() >= MAX_IN_FLIGHT_TILES) {
            return List.of(); // at capacity: fail open rather than admit yet another distinct in-flight cell
        }
        Instant deadline = clock.instant().plus(bound);
        CompletableFuture<List<RoadSegment>> future = inFlightByCell.computeIfAbsent(key, k -> {
            CompletableFuture<List<RoadSegment>> submitted = new CompletableFuture<>();
            ASYNC_FETCH_EXECUTOR.execute(new PriorityTask(PRIORITY_IMMEDIATE, () -> {
                try {
                    submitted.complete(waysForCellRetryingAcrossCooldown(lat, lon, deadline));
                } catch (Throwable t) {
                    submitted.completeExceptionally(t);
                }
            }));
            return submitted;
        });
        future.whenComplete((result, error) -> inFlightByCell.remove(key, future));
        try {
            return future.get(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return List.of(); // fail open: the shared future above keeps running regardless
        } catch (ExecutionException e) {
            return List.of(); // waysForCellRetryingAcrossCooldown never throws checked exceptions here, but guard anyway rather than propagate
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /**
     * MINECRAFT-566 comment 55547: a real liveness gap in using
     * {@link #waysForCellWithBoundedWait} with {@link Duration#ZERO} as a "fire-and-forget" dispatch
     * ({@code ServerCubeWorldRoadPrefetchMixin}'s own earlier approach, comment 55545). Passing
     * {@code Duration.ZERO} as {@code bound} does not just skip the CALLER's own wait -- it also
     * becomes {@link #waysForCellRetryingAcrossCooldown}'s own internal {@code deadline}, so a
     * cooldown-suppressed dispatch gives up (and unregisters from {@link #inFlightByCell}) on the
     * very first decline, exactly the pre-55528 bug, reintroduced by this one call site's own choice
     * of bound. Once unregistered, this cell's own "current, high-priority demand" registration is
     * gone entirely: {@code ServerCubeWorld} itself only retains the CUBE key (via its own
     * {@code retryAt}), never this tile's own dispatch priority, so a DIFFERENT, merely speculative
     * lookahead cell can take the next legal request slot ahead of this genuinely still-wanted one --
     * natively observed: repeated lookahead starving a real demand even against a healthy backend.
     *
     * <p>Fixed with a genuine fire-and-forget dispatch: the SAME coalesced, {@code PRIORITY_IMMEDIATE}
     * dispatch and retry-across-cooldown loop {@link #waysForCellWithBoundedWait} uses, but the
     * CALLER never calls {@code future.get(...)} at all (so it truly never waits, unlike passing a
     * zero bound into that method), and the retry loop's own internal {@code deadline} is a generous,
     * fixed {@link #FIRE_AND_FORGET_RETRY_BUDGET} -- long enough to survive an entirely ordinary
     * cooldown without ever prematurely giving up and dropping this cell's own registration, since
     * nothing here blocks on it either way (this background thread occupying itself for that long is
     * the SAME disclosed, pre-existing "one shared thread held during a cooldown sleep" limitation
     * {@link #waysForCellRetryingAcrossCooldown}'s own doc already names, not a new one).
     */
    private static final Duration FIRE_AND_FORGET_RETRY_BUDGET = Duration.ofSeconds(30);

    /**
     * MINECRAFT-566 comment 55547: the fire-and-forget dispatch counterpart to
     * {@link #waysForCellWithBoundedWait} -- see {@link #FIRE_AND_FORGET_RETRY_BUDGET}'s own doc for
     * why a zero-bound call to that method was not actually fire-and-forget in the one way that
     * mattered (it dropped this cell's own in-flight registration on the first cooldown decline).
     * This method returns nothing and never blocks the caller at all; the real result (if any) is
     * picked up later by whichever ordinary caller next asks this cell's own cache state.
     */
    public void dispatchCellFetch(double lat, double lon) {
        if (!cachedWaysForCellOrEmpty(lat, lon).isEmpty() || cellState(lat, lon) != CellState.PENDING) return;
        long key = cellKeyOf(lat, lon);
        if (!inFlightByCell.containsKey(key) && inFlightByCell.size() >= MAX_IN_FLIGHT_TILES) {
            return; // at capacity: drop this speculative dispatch rather than admit yet another distinct in-flight cell
        }
        Instant deadline = clock.instant().plus(FIRE_AND_FORGET_RETRY_BUDGET);
        CompletableFuture<List<RoadSegment>> future = inFlightByCell.computeIfAbsent(key, k -> {
            CompletableFuture<List<RoadSegment>> submitted = new CompletableFuture<>();
            ASYNC_FETCH_EXECUTOR.execute(new PriorityTask(PRIORITY_IMMEDIATE, () -> {
                try {
                    submitted.complete(waysForCellRetryingAcrossCooldown(lat, lon, deadline));
                } catch (Throwable t) {
                    submitted.completeExceptionally(t);
                }
            }));
            return submitted;
        });
        future.whenComplete((result, error) -> inFlightByCell.remove(key, future));
    }

    /**
     * MINECRAFT-566 comment 55522's other half: "keep eligible immediate demand pending across
     * cooldown" rather than {@link #waysForCell}'s own single attempt, which gives up the instant
     * {@link #attemptFetch} is dispatch-suppressed by {@link #nextEligibleRequestAt} -- even though
     * this caller's own {@code deadline} (from {@link #waysForCellWithBoundedWait}'s {@code bound})
     * may still have most of its budget left. Retries ONLY on {@link FetchOutcome#DISPATCH_SUPPRESSED}
     * (checked directly via {@link #waysForCellDetailed}, never inferred from the clock -- comment
     * 55528, a real bug John's independent loopback reproduction found: a genuinely ATTEMPTED
     * failure, e.g. an HTTP 500, advances {@link #nextEligibleRequestAt} by the steady cadence
     * exactly like a success does, so inferring "was this just cooldown?" purely from whether that
     * instant was still ahead of now could not tell the two apart, and ended up retrying -- sending
     * a SECOND real request -- after a real failure too). Sleeps exactly until that cooldown, or
     * this call's own deadline, whichever comes first, never longer, with a 1ms floor so a
     * near-zero remaining gap still advances real wall-clock time instead of a no-op "sleep" that
     * risks burning through {@value #MAX_COOLDOWN_RETRIES} attempts without actually waiting (a
     * second thing comment 55528 flagged as plausible, not yet pinned). Never retries a genuine
     * {@code RESOLVED} or {@code ATTEMPTED_FAILURE} outcome -- a persistently-failing provider
     * cannot be hammered by this loop; {@link #consecutiveAttemptedFailures}/{@link #sustainedOutage}
     * already track that case on their own terms. Runs on {@link #ASYNC_FETCH_EXECUTOR}'s own
     * background thread, never the caller's (generation worker) thread, so sleeping here never
     * blocks generation -- though it DOES occupy that one shared thread for up to the full sleep
     * duration each time (no other queued task, immediate or lookahead, can run until this call's
     * own {@code Runnable.run()} returns); a redesign avoiding that (e.g. scheduling a resubmission
     * rather than sleeping in place) is a real further improvement, not attempted this pass. Bounded
     * to {@value #MAX_COOLDOWN_RETRIES} attempts regardless, so a pathological case (the cooldown
     * window kept being pushed back by a concurrent caller, say) cannot spin indefinitely even if it
     * somehow stayed within the deadline the whole time.
     */
    private static final int MAX_COOLDOWN_RETRIES = 4;

    private List<RoadSegment> waysForCellRetryingAcrossCooldown(double lat, double lon, Instant deadline) {
        for (int attempt = 0; attempt < MAX_COOLDOWN_RETRIES; attempt++) {
            FetchAttemptResult attemptResult = waysForCellDetailed(lat, lon);
            if (attemptResult.outcome() != FetchOutcome.DISPATCH_SUPPRESSED) return attemptResult.ways(); // RESOLVED or a genuine ATTEMPTED_FAILURE -- never retried here
            Instant now = clock.instant();
            Instant cooldownUntil = nextEligibleRequestAt;
            Instant wakeAt = cooldownUntil.isBefore(deadline) ? cooldownUntil : deadline;
            if (!wakeAt.isAfter(now)) return List.of(); // no budget left in our own deadline
            try {
                Thread.sleep(Math.max(1, Duration.between(now, wakeAt).toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
            if (!clock.instant().isBefore(deadline)) return List.of();
        }
        return List.of();
    }

    /**
     * {@link #enqueueLookahead}, dispatched onto {@link #ASYNC_FETCH_EXECUTOR} so the calling
     * (generation worker) thread never waits on it at all -- coalesced via
     * {@link #pendingLookaheadDispatches} so a tall column's many cube-Y-layer calls for the exact
     * same center cell submit at most ONE undispatched task at a time (MINECRAFT-566 comment 55481:
     * the first version submitted a brand new task EVERY call, unbounded).
     */
    public void enqueueLookaheadAsync(double lat, double lon) {
        long key = cellKeyOf(lat, lon);
        if (!pendingLookaheadDispatches.add(key)) return; // already queued for this exact center cell; skip rather than pile on another
        ASYNC_FETCH_EXECUTOR.execute(new PriorityTask(PRIORITY_LOOKAHEAD, () -> {
            try {
                enqueueLookahead(lat, lon);
            } finally {
                pendingLookaheadDispatches.remove(key);
            }
        }));
    }

    private static long cellKeyOf(double lat, double lon) {
        long cellLat = (long) Math.floor(lat / RoadNetwork.CELL_DEGREES);
        long cellLon = (long) Math.floor(lon / RoadNetwork.CELL_DEGREES);
        return (cellLat << 32) ^ (cellLon & 0xFFFFFFFFL);
    }

    /**
     * The only origin+path this class will ever attach {@link #apiKey} to, regardless of what
     * {@link #endpoint} is configured to -- MINECRAFT-566 comment 55448: "bind credential-bearing
     * requests to the documented https://api.overpass.ch/v1/interpreter origin/path." A
     * misconfigured (or, in principle, maliciously redirected) {@code previewEndpoint} must never
     * cause the credential to be sent somewhere else; see {@link #fetchCell}, which checks this
     * before adding the header rather than trusting whatever URI it was constructed with.
     */
    static final URI CREDENTIAL_BOUND_ENDPOINT = URI.create("https://api.overpass.ch/v1/interpreter");

    /** Pulled out as its own pure function so the binding rule itself is unit-testable without a real network call to the real endpoint. */
    static boolean shouldSendApiKeyTo(URI endpoint, String apiKey) {
        return apiKey != null && !apiKey.isEmpty() && CREDENTIAL_BOUND_ENDPOINT.equals(endpoint);
    }

    public RoadTileSource(URI endpoint, Path cacheRoot, boolean allowNetwork, Duration timeout) {
        this(endpoint, cacheRoot, allowNetwork, timeout, null, Duration.ZERO);
    }

    /**
     * @param apiKey Optional credential for a provider that requires one (e.g. a hosted Overpass
     *               instance's free-tier key), sent as a {@code X-API-Key} header -- but ONLY when
     *               {@link #endpoint} exactly equals {@link #CREDENTIAL_BOUND_ENDPOINT}; see
     *               {@link #fetchCell}. Never logged, never hardcoded as a default -- callers must
     *               source it from outside version control, per MINECRAFT-566's explicit
     *               "credentials out of source, Jira, logs, and distributed jars" requirement, and
     *               per comment 55448, from an environment variable only (never a JVM {@code -D}
     *               system property, which is visible in process argument listings). {@code null}
     *               sends no credential header at all and, per 55448, must disable the live provider
     *               entirely rather than send an anonymous request -- enforced by the caller
     *               ({@code RoadNetwork.buildDefaultTileSource}), not by this constructor itself.
     * @param minRequestInterval Minimum real-world time between the start of one outbound request
     *                           and the next, enforced via {@link #nextEligibleRequestAt} (never by
     *                           sleeping -- see that field's own doc) -- a conservative client-side
     *                           rate limit for an opt-in hosted provider's free tier, not a
     *                           default-provider concern. {@code Duration.ZERO} (the single-arg
     *                           constructor's default) means no extra delay beyond the one-in-flight
     *                           serialization {@link #networkLock} already provides.
     */
    public RoadTileSource(URI endpoint, Path cacheRoot, boolean allowNetwork, Duration timeout, String apiKey, Duration minRequestInterval) {
        this.endpoint = endpoint;
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.timeout = timeout;
        this.apiKey = apiKey;
        this.minRequestInterval = minRequestInterval == null ? Duration.ZERO : minRequestInterval;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * MINECRAFT-566 comment 55462: "add nonsecret opt-in diagnostics distinguishing covered by
     * cached way, pending no match, and skipped by terrain/classification, rather than assuming
     * readiness alone fixes this occurrence." This is the piece {@link #cachedWaysForCellOrEmpty}
     * itself cannot answer -- it deliberately returns the same empty list for both a genuinely
     * confirmed-empty cell and a merely pending one, which is correct for generation (both mean
     * "no road to draw right now") but useless for diagnosing WHY. No credential or request detail
     * is ever exposed here, only which of this cell's own three cache states currently applies.
     */
    public enum CellState { READY, CONFIRMED_EMPTY, PENDING }

    /**
     * MINECRAFT-566 comment 55562: this previously stat'd disk directly on EVERY call ({@code
     * Files.isRegularFile} twice), never consulting {@link #memory} at all -- so a caller re-asking
     * about the same already-resolved cell (e.g. {@code RoadPrefetch}'s own per-column scans, up to
     * 256 times for one cube) re-touched the filesystem every single time, even though the cell's
     * state can never change mid-scan. Fixed by delegating to {@link #readDiskCacheIfPresent} (the
     * same validated-load helper {@link #cachedWaysForCellOrEmpty}/{@link #waysForCellDetailed}/
     * {@link #enqueueLookahead} already share): a hit is served from {@link #memory} with zero disk
     * I/O once any caller has validated it once; a genuine miss ({@code null}, meaning PENDING) is
     * deliberately never cached, so a later resolution or a transient failure is never masked -- the
     * on-disk cache's own file-based semantics are completely unchanged, only the in-memory layer on
     * top of them is now actually used here too.
     */
    public CellState cellState(double lat, double lon) {
        long cellLat = (long) Math.floor(lat / RoadNetwork.CELL_DEGREES);
        long cellLon = (long) Math.floor(lon / RoadNetwork.CELL_DEGREES);
        long key = (cellLat << 32) ^ (cellLon & 0xFFFFFFFFL);
        String cellId = cellLat + "_" + cellLon;
        Path readyFile = cacheRoot.resolve(cellId + ".json");
        Path emptyFile = cacheRoot.resolve(cellId + ".empty");
        List<RoadSegment> loaded = readDiskCacheIfPresent(key, readyFile, emptyFile);
        if (loaded == null) return CellState.PENDING;
        return loaded.isEmpty() ? CellState.CONFIRMED_EMPTY : CellState.READY;
    }

    /**
     * Every mapped way already cached for the cell covering {@code lat,lon} -- never fetches, never
     * blocks, never touches the network. This is what {@link RoadNetwork#candidatesNear} calls from
     * the hot generation path ({@code EarthGeneratorRoadMixin}'s {@code surface()} hook): per
     * MINECRAFT-566's review, that path must never wait on HTTP (Earth Platform's own {@code
     * loadNow} calls it synchronously on whatever thread asks, which may be the main server thread).
     * The only place this class ever fetches is {@link #waysForCell}, called only from
     * {@code EarthGeneratorPrepareMixin}'s hooks on {@code prepare}/{@code prepareNear} -- Earth
     * Platform's own "fetch ahead of generation" entry points, already documented and used
     * off-thread for exactly this kind of network-backed data.
     */
    public List<RoadSegment> cachedWaysForCellOrEmpty(double lat, double lon) {
        // MINECRAFT-566 comment 55562: a real, confirmed bug -- the old version here checked ONLY
        // the ready (".json") file's existence, never the confirmed-empty (".empty") marker, and
        // never populated `memory` on a confirmed-empty result at all. Every single call for an
        // already-resolved-but-empty neighbor cell (the ordinary case: most of the world has no
        // roads) re-stat'd disk again, forever -- exactly "rendering near absent/confirmed-empty
        // neighbors can restat all those cells on many block calls." Fixed by delegating to
        // {@link #readDiskCacheIfPresent}, the same validated-load helper {@link #cellState} now
        // also uses: a confirmed-empty hit is memoized (as an empty list, same representation this
        // method already returned) exactly once, then served from memory with zero disk I/O on
        // every later call for that same cell.
        long cellLat = (long) Math.floor(lat / RoadNetwork.CELL_DEGREES);
        long cellLon = (long) Math.floor(lon / RoadNetwork.CELL_DEGREES);
        long key = (cellLat << 32) ^ (cellLon & 0xFFFFFFFFL);
        String cellId = cellLat + "_" + cellLon;
        Path readyFile = cacheRoot.resolve(cellId + ".json");
        Path emptyFile = cacheRoot.resolve(cellId + ".empty");
        List<RoadSegment> loaded = readDiskCacheIfPresent(key, readyFile, emptyFile);
        return loaded != null ? loaded : List.of();
    }

    /**
     * Every mapped way in the cell covering {@code lat,lon}: cached if present, fetched (blocking,
     * with this source's timeout) and cached if not.
     *
     * <p>Disk-cache hits (below) take neither {@link #cellLocks} nor {@link #networkLock} -- they
     * are plain, independent file reads, same as {@link #cachedWaysForCellOrEmpty}, so a cell
     * that's already cached completes promptly no matter what any other cell's fetch is doing.
     * Only a genuine cache miss takes this cell's own lock (to dedupe concurrent callers asking for
     * the SAME cell) and then {@link #networkLock} (to serialize the actual outbound request).
     */
    public List<RoadSegment> waysForCell(double lat, double lon) {
        return waysForCellDetailed(lat, lon).ways();
    }

    /**
     * MINECRAFT-566 comment 55528, a real bug found by John's independent loopback reproduction:
     * {@link #waysForCell} discarded {@link #attemptFetch}'s own {@link FetchOutcome}, and a
     * dispatched request that genuinely FAILED (e.g. an HTTP 500) still advances
     * {@link #nextEligibleRequestAt} by the steady {@link #minRequestInterval} cadence exactly like
     * a successful one does -- so {@link #waysForCellRetryingAcrossCooldown}, inferring "was this
     * declined by cooldown?" purely from whether {@code nextEligibleRequestAt} was ahead of now,
     * could not tell a genuine attempted failure apart from a merely-not-yet-eligible decline, and
     * ended up retrying (sending a SECOND real request) after a real failure too -- exactly the
     * "hammer a failing provider" risk that method's own doc claimed to avoid. This record lets
     * {@link #waysForCellDetailed} report the real outcome so the retry loop can check it directly
     * instead of re-deriving a weaker signal from the clock.
     */
    private record FetchAttemptResult(List<RoadSegment> ways, FetchOutcome outcome) {}

    private FetchAttemptResult waysForCellDetailed(double lat, double lon) {
        long cellLat = (long) Math.floor(lat / RoadNetwork.CELL_DEGREES);
        long cellLon = (long) Math.floor(lon / RoadNetwork.CELL_DEGREES);
        long key = (cellLat << 32) ^ (cellLon & 0xFFFFFFFFL);
        String cellId = cellLat + "_" + cellLon;
        Path readyFile = cacheRoot.resolve(cellId + ".json");
        Path emptyFile = cacheRoot.resolve(cellId + ".empty");

        List<RoadSegment> diskHit = readDiskCacheIfPresent(key, readyFile, emptyFile);
        if (diskHit != null) return new FetchAttemptResult(diskHit, FetchOutcome.RESOLVED);

        synchronized (cellLocks.computeIfAbsent(key, k -> new Object())) {
            // Double-check: another thread may have just finished fetching this exact cell while
            // this one was waiting for the per-cell lock.
            diskHit = readDiskCacheIfPresent(key, readyFile, emptyFile);
            if (diskHit != null) return new FetchAttemptResult(diskHit, FetchOutcome.RESOLVED);

            FetchOutcome outcome = attemptFetch(key, cellLat, cellLon, readyFile, emptyFile);
            List<RoadSegment> afterAttempt = memory.get(key);
            return new FetchAttemptResult(afterAttempt != null ? afterAttempt : List.of(), outcome);
        }
    }

    /**
     * MINECRAFT-566 comment 55468: a cell whose attempt never actually reached the network
     * ("dispatch suppressed" -- declined by {@link #nextEligibleRequestAt}, free, instant) is a
     * fundamentally different outcome from one that genuinely was dispatched and failed (a real
     * timeout or 5xx, costing an actual request) -- conflating them let a persistently-failing cell
     * (e.g. a dense tile that always 504s) sit at the front of {@link #lookaheadQueue} forever,
     * monopolizing every future opportunistic slot and starving its neighbors. Callers (both
     * {@link #waysForCell} and {@link #enqueueLookahead}) must be able to tell these apart.
     */
    private enum FetchOutcome { RESOLVED, DISPATCH_SUPPRESSED, ATTEMPTED_FAILURE }

    /**
     * Attempts to resolve exactly one cell -- MUST be called while already holding that cell's own
     * {@link #cellLocks} entry. Writes the cache and populates {@link #memory} on success; writes
     * nothing on any failure, suppressed or attempted, so the cell stays genuinely pending either
     * way (comment 55450's invariant, unchanged).
     */
    private FetchOutcome attemptFetch(long key, long cellLat, long cellLon, Path readyFile, Path emptyFile) {
        if (!allowNetwork) return FetchOutcome.DISPATCH_SUPPRESSED; // no live source at all: nothing was ever going to be dispatched
        double minLat = cellLat * RoadNetwork.CELL_DEGREES, maxLat = minLat + RoadNetwork.CELL_DEGREES;
        double minLon = cellLon * RoadNetwork.CELL_DEGREES, maxLon = minLon + RoadNetwork.CELL_DEGREES;
        List<RoadSegment> fetched;
        try {
            fetched = fetchCell(minLat, minLon, maxLat, maxLon);
        } catch (NotYetEligibleException e) {
            return FetchOutcome.DISPATCH_SUPPRESSED; // free: no real request was ever sent; does not affect the outage streak
        } catch (IOException | RuntimeException e) {
            consecutiveAttemptedFailures.incrementAndGet();
            return FetchOutcome.ATTEMPTED_FAILURE; // a real request WAS sent and failed (timeout, 5xx, remark, etc.)
        }
        try {
            writeCachedCell(fetched.isEmpty() ? emptyFile : readyFile, fetched, minLat, minLon, maxLat, maxLon);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write road tile cache at " + cacheRoot, e);
        }
        memory.put(key, fetched);
        consecutiveAttemptedFailures.set(0); // a real success anywhere clears the outage streak
        return FetchOutcome.RESOLVED;
    }

    /**
     * Enqueues {@code lat,lon}'s Moore neighborhood (the 8 cells sharing an edge or corner with its
     * own) for best-effort catch-up fetching, skipping anything already cached or already queued,
     * then opportunistically attempts to fetch AT MOST ONE queued cell through the ordinary
     * {@link #waysForCell} path -- same one-in-flight guarantee, same {@link #nextEligibleRequestAt}
     * deadline, same three-state cache semantics. Never blocks beyond what a single ordinary fetch
     * attempt already would; never bursts multiple requests. See {@link #lookaheadQueue}'s own
     * field doc for why this exists (MINECRAFT-566 comment 55460).
     */
    public void enqueueLookahead(double lat, double lon) {
        for (int dLat = -1; dLat <= 1; dLat++) {
            for (int dLon = -1; dLon <= 1; dLon++) {
                double neighborLat = lat + dLat * RoadNetwork.CELL_DEGREES;
                double neighborLon = lon + dLon * RoadNetwork.CELL_DEGREES;
                long cellLat = (long) Math.floor(neighborLat / RoadNetwork.CELL_DEGREES);
                long cellLon = (long) Math.floor(neighborLon / RoadNetwork.CELL_DEGREES);
                long key = (cellLat << 32) ^ (cellLon & 0xFFFFFFFFL);
                if (memory.containsKey(key) || queuedCellKeys.contains(key)) continue;
                String cellId = cellLat + "_" + cellLon;
                if (Files.isRegularFile(cacheRoot.resolve(cellId + ".json")) || Files.isRegularFile(cacheRoot.resolve(cellId + ".empty"))) continue;
                if (lookaheadQueue.size() >= LOOKAHEAD_QUEUE_CAPACITY) break; // bounded: stop enqueuing more from this one call, not an error
                if (queuedCellKeys.add(key)) lookaheadQueue.addLast(new long[] {cellLat, cellLon});
            }
        }

        long[] next = lookaheadQueue.pollFirst();
        if (next == null) return;
        long nextCellLat = next[0], nextCellLon = next[1];
        long nextKey = (nextCellLat << 32) ^ (nextCellLon & 0xFFFFFFFFL);
        String cellId = nextCellLat + "_" + nextCellLon;
        Path readyFile = cacheRoot.resolve(cellId + ".json");
        Path emptyFile = cacheRoot.resolve(cellId + ".empty");

        FetchOutcome outcome;
        synchronized (cellLocks.computeIfAbsent(nextKey, k -> new Object())) {
            // MINECRAFT-566 comment 55470: a queued cell can be resolved by some OTHER path (most
            // commonly: the player's own position later lands exactly in this cell, triggering its
            // ordinary synchronous waysForCell fetch) before this stale queue entry ever gets
            // dequeued. Dispatching anyway would waste a scarce provider slot AND -- worse --
            // overwrite already-resolved, already-correct cached geometry with whatever a second,
            // redundant fetch happens to return. Re-check memory/disk under this SAME per-cell lock
            // before ever attempting a new dispatch; either READY or confirmed EMPTY counts as
            // already resolved, so the stale job is simply popped, not re-fetched.
            List<RoadSegment> alreadyResolved = readDiskCacheIfPresent(nextKey, readyFile, emptyFile);
            outcome = alreadyResolved != null ? FetchOutcome.RESOLVED : attemptFetch(nextKey, nextCellLat, nextCellLon, readyFile, emptyFile);
        }

        // MINECRAFT-566 comment 55466: retain a job that was never actually dispatched (declined
        // by the rate-limit deadline, free, instant) by putting it back at the FRONT -- it costs
        // nothing to try again at the very next opportunity, and this is what "the original job
        // was retained, not dropped and rediscovered later" actually means.
        //
        // Comment 55468's correction: a job that WAS actually dispatched and genuinely failed (a
        // real timeout or 5xx, not just "too soon") must NOT get that same front-of-queue priority
        // -- a persistently-failing cell (e.g. a dense tile that always 504s) would otherwise
        // monopolize every future opportunistic slot forever, since it would keep landing right
        // back at the front after every failed retry, starving every other queued neighbor. Such a
        // cell instead rotates to the BACK: still retried eventually (never dropped, never
        // confirmed-empty, per the same no-fabrication invariant {@link #attemptFetch} already
        // upholds), but only after every other currently-queued neighbor gets its own turn first --
        // a simple, bounded (the queue's own capacity bounds it) form of backoff/rotation.
        switch (outcome) {
            case RESOLVED -> queuedCellKeys.remove(nextKey); // ready or confirmed-empty -- permanently done, never retried
            case DISPATCH_SUPPRESSED -> lookaheadQueue.addFirst(next);
            case ATTEMPTED_FAILURE -> lookaheadQueue.addLast(next);
        }
    }

    /**
     * Memory, then disk ({@code .empty}/{@code .json}) -- {@code null} means a genuine cache miss,
     * not an error. Every successful disk-validated load newly populates {@link #memory} (never a
     * miss).
     */
    private List<RoadSegment> readDiskCacheIfPresent(long key, Path readyFile, Path emptyFile) {
        List<RoadSegment> cached = memory.get(key);
        if (cached != null) return cached;
        try {
            if (Files.isRegularFile(emptyFile)) {
                memory.put(key, List.of());
                return List.of();
            }
            if (Files.isRegularFile(readyFile)) {
                List<RoadSegment> ways = readCachedCell(readyFile);
                memory.put(key, ways);
                return ways;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read road tile cache at " + cacheRoot, e);
        }
        return null;
    }

    /** Thrown by {@link #fetchCell} when {@link #nextEligibleRequestAt} hasn't passed yet; never leaves this class. */
    private static final class NotYetEligibleException extends RuntimeException {}

    /**
     * Fetches this cell via an Overpass QL {@code out geom} query against {@link #endpoint} -- an
     * opt-in hosted Overpass provider (MINECRAFT-566: John selected Overpass.ch's free tier for a
     * private worldwide preview), not a public default. Two earlier live sources were tried and
     * rejected this ticket: three public Overpass mirrors were unreachable (overloaded), and the
     * official OSM editing API was explicitly disallowed as an automated game-fetch backend by
     * policy. A dedicated hosted Overpass provider is neither of those -- but it is still live data,
     * not the pinned/immutable snapshot the production catalog work is for, and it requires a
     * provisioned credential, so this constructor path must stay opt-in (see
     * {@code RoadNetwork.buildDefaultTileSource}'s gating) and rate-limited (see
     * {@link #networkLock}/{@link #nextEligibleRequestAt}).
     *
     * <p>Everything here runs inside {@link #networkLock} -- the eligibility check, the one real
     * outbound call, and updating {@link #nextEligibleRequestAt} from either the steady
     * {@link #minRequestInterval} cadence or a 429/503's {@code Retry-After} -- so there is never
     * more than one outstanding request to the provider from this instance, and (per comment 55450)
     * this never sleeps: a caller that isn't yet eligible gets {@link NotYetEligibleException}
     * immediately, not a blocked wait.
     */
    private List<RoadSegment> fetchCell(double minLat, double minLon, double maxLat, double maxLon)
            throws IOException {
        String body;
        synchronized (networkLock) {
            if (clock.instant().isBefore(nextEligibleRequestAt)) {
                throw new NotYetEligibleException();
            }
            String query = """
                    [out:json][timeout:%d];
                    (
                      way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|service|road)$"](%f,%f,%f,%f);
                    );
                    out geom;
                    """.formatted(Math.max(1, timeout.toSeconds()), minLat, minLon, maxLat, maxLon);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("User-Agent", "earth-platform-roads/0.1 (MINECRAFT-566)")
                    .POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8));
            if (shouldSendApiKeyTo(endpoint, apiKey)) {
                requestBuilder.header("X-API-Key", apiKey); // never logged; see this field's own javadoc
            }
            HttpResponse<String> response;
            try {
                response = http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while fetching road tile", e);
            }
            Instant afterSteadyInterval = clock.instant().plus(minRequestInterval);
            nextEligibleRequestAt = afterSteadyInterval;
            if (response.statusCode() == 429 || response.statusCode() == 503) {
                // overpass.ch docs (https://overpass.ch/docs, "Limits & plans"/"Errors"): both
                // rate_limited/concurrency_limit/quota_exceeded (429) and server_busy (503) carry a
                // Retry-After header naming the real wait. Takes effect for the NEXT fetch attempt
                // from this instance, whichever (the steady interval or this) is later.
                response.headers().firstValue("Retry-After").ifPresent(value -> {
                    try {
                        Instant afterRetryAfter = clock.instant().plusSeconds(Long.parseLong(value.trim()));
                        if (afterRetryAfter.isAfter(nextEligibleRequestAt)) nextEligibleRequestAt = afterRetryAfter;
                    } catch (NumberFormatException ignored) {
                        // Retry-After may also be an HTTP-date per RFC 9110; not parsed here -- the
                        // configured minRequestInterval still applies as a floor either way.
                    }
                });
            }
            if (response.statusCode() != 200) {
                throw new IOException("Overpass provider returned HTTP " + response.statusCode() + " for cell fetch");
            }
            body = response.body();
        }
        checkForErrorRemark(body);
        return OverpassWayParser.parseWays(body);
    }

    /**
     * Overpass can start an HTTP 200 response, hit a runtime error partway through (e.g. its own
     * {@code timeout:} budget expiring mid-query), and still finish the response with a top-level
     * {@code "remark"} string describing that failure -- see Overpass's own {@code
     * Output_JSON::write_footer}, which only ever emits {@code "remark"} when it has an error/warning
     * message to report. A 200 status alone is therefore not proof the body is a complete, trustworthy
     * answer: this must be checked and treated as a failure (same as a non-200 status or a network
     * exception) BEFORE this cell's ways are written to the ready or confirmed-empty cache.
     * Restored (with its two tests) for the Overpass.ch preview provider, having been removed when
     * the short-lived OSM-API-based default made this failure shape unreachable; it is reachable
     * again now that this class speaks Overpass QL to a real Overpass-protocol endpoint.
     */
    private static void checkForErrorRemark(String responseBody) throws IOException {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        if (root.has("remark") && !root.get("remark").getAsString().isEmpty()) {
            throw new IOException("Overpass reported an error remark despite HTTP 200: " + root.get("remark").getAsString());
        }
    }

    private static List<RoadSegment> readCachedCell(Path readyFile) throws IOException {
        try (InputStream in = Files.newInputStream(readyFile)) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return RoadNetwork.parseRegionWays(root, readyFile.toString());
        }
    }

    private void writeCachedCell(Path file, List<RoadSegment> ways, double minLat, double minLon, double maxLat, double maxLon)
            throws IOException {
        Files.createDirectories(cacheRoot);
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", RoadNetwork.SUPPORTED_SCHEMA_VERSION);
        root.addProperty("region_id", "tile_" + minLat + "_" + minLon);
        root.addProperty("fetched_at", Instant.now().toString());
        JsonObject bbox = new JsonObject();
        bbox.addProperty("min_lat", minLat);
        bbox.addProperty("min_lon", minLon);
        bbox.addProperty("max_lat", maxLat);
        bbox.addProperty("max_lon", maxLon);
        root.add("bbox", bbox);
        root.add("ways", RoadSegment.toWaysJsonArray(ways));

        Path tmp = Files.createTempFile(cacheRoot, file.getFileName().toString(), ".tmp");
        Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
