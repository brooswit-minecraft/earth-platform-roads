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
import java.util.concurrent.ConcurrentHashMap;

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

    /** This cell's own cache state on disk, independent of whatever may or may not be in {@link #memory} -- see {@link CellState}. */
    public CellState cellState(double lat, double lon) {
        long cellLat = (long) Math.floor(lat / RoadNetwork.CELL_DEGREES);
        long cellLon = (long) Math.floor(lon / RoadNetwork.CELL_DEGREES);
        String cellId = cellLat + "_" + cellLon;
        if (Files.isRegularFile(cacheRoot.resolve(cellId + ".json"))) return CellState.READY;
        if (Files.isRegularFile(cacheRoot.resolve(cellId + ".empty"))) return CellState.CONFIRMED_EMPTY;
        return CellState.PENDING;
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
        long cellLat = (long) Math.floor(lat / RoadNetwork.CELL_DEGREES);
        long cellLon = (long) Math.floor(lon / RoadNetwork.CELL_DEGREES);
        long key = (cellLat << 32) ^ (cellLon & 0xFFFFFFFFL);
        List<RoadSegment> cached = memory.get(key);
        if (cached != null) return cached;
        String cellId = cellLat + "_" + cellLon;
        Path readyFile = cacheRoot.resolve(cellId + ".json");
        if (!Files.isRegularFile(readyFile)) return List.of();
        try {
            List<RoadSegment> ways = readCachedCell(readyFile);
            memory.put(key, ways);
            return ways;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read road tile cache file " + readyFile, e);
        }
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
        long cellLat = (long) Math.floor(lat / RoadNetwork.CELL_DEGREES);
        long cellLon = (long) Math.floor(lon / RoadNetwork.CELL_DEGREES);
        long key = (cellLat << 32) ^ (cellLon & 0xFFFFFFFFL);
        String cellId = cellLat + "_" + cellLon;
        Path readyFile = cacheRoot.resolve(cellId + ".json");
        Path emptyFile = cacheRoot.resolve(cellId + ".empty");

        List<RoadSegment> diskHit = readDiskCacheIfPresent(key, readyFile, emptyFile);
        if (diskHit != null) return diskHit;

        synchronized (cellLocks.computeIfAbsent(key, k -> new Object())) {
            // Double-check: another thread may have just finished fetching this exact cell while
            // this one was waiting for the per-cell lock.
            diskHit = readDiskCacheIfPresent(key, readyFile, emptyFile);
            if (diskHit != null) return diskHit;

            attemptFetch(key, cellLat, cellLon, readyFile, emptyFile);
            List<RoadSegment> afterAttempt = memory.get(key);
            return afterAttempt != null ? afterAttempt : List.of();
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
            return FetchOutcome.DISPATCH_SUPPRESSED; // free: no real request was ever sent
        } catch (IOException | RuntimeException e) {
            return FetchOutcome.ATTEMPTED_FAILURE; // a real request WAS sent and failed (timeout, 5xx, remark, etc.)
        }
        try {
            writeCachedCell(fetched.isEmpty() ? emptyFile : readyFile, fetched, minLat, minLon, maxLat, maxLon);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write road tile cache at " + cacheRoot, e);
        }
        memory.put(key, fetched);
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
            outcome = attemptFetch(nextKey, nextCellLat, nextCellLon, readyFile, emptyFile);
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

    /** Memory, then disk ({@code .empty}/{@code .json}) -- {@code null} means a genuine cache miss, not an error. */
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
