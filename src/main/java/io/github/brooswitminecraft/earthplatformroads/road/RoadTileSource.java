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
    private final URI overpassEndpoint;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final Duration timeout;
    private final HttpClient http;
    private final Object fetchLock = new Object();
    private final ConcurrentHashMap<Long, List<RoadSegment>> memory = new ConcurrentHashMap<>();

    public RoadTileSource(URI overpassEndpoint, Path cacheRoot, boolean allowNetwork, Duration timeout) {
        this.overpassEndpoint = overpassEndpoint;
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
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

    /** Every mapped way in the cell covering {@code lat,lon}: cached if present, fetched (blocking, with this source's timeout) and cached if not. */
    public List<RoadSegment> waysForCell(double lat, double lon) {
        long cellLat = (long) Math.floor(lat / RoadNetwork.CELL_DEGREES);
        long cellLon = (long) Math.floor(lon / RoadNetwork.CELL_DEGREES);
        long key = (cellLat << 32) ^ (cellLon & 0xFFFFFFFFL);

        List<RoadSegment> cached = memory.get(key);
        if (cached != null) return cached;

        synchronized (fetchLock) {
            cached = memory.get(key);
            if (cached != null) return cached;

            String cellId = cellLat + "_" + cellLon;
            Path readyFile = cacheRoot.resolve(cellId + ".json");
            Path emptyFile = cacheRoot.resolve(cellId + ".empty");

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

            // Pending (no marker at all): attempt a fetch now. On any failure this cell simply stays
            // pending -- no marker is written -- so the very next access (this world's next cube, or
            // a restarted server reading the same cache root) retries it.
            if (!allowNetwork) return List.of();
            double minLat = cellLat * RoadNetwork.CELL_DEGREES, maxLat = minLat + RoadNetwork.CELL_DEGREES;
            double minLon = cellLon * RoadNetwork.CELL_DEGREES, maxLon = minLon + RoadNetwork.CELL_DEGREES;
            List<RoadSegment> fetched;
            try {
                fetched = fetchCell(minLat, minLon, maxLat, maxLon);
            } catch (IOException | InterruptedException | RuntimeException e) {
                return List.of(); // pending stays pending; this generation call gets no road, not a permanent gap
            }
            try {
                writeCachedCell(fetched.isEmpty() ? emptyFile : readyFile, fetched, minLat, minLon, maxLat, maxLon);
            } catch (IOException e) {
                throw new IllegalStateException("Could not write road tile cache at " + cacheRoot, e);
            }
            memory.put(key, fetched);
            return fetched;
        }
    }

    private List<RoadSegment> fetchCell(double minLat, double minLon, double maxLat, double maxLon)
            throws IOException, InterruptedException {
        String query = """
                [out:json][timeout:%d];
                (
                  way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|service|road)$"](%f,%f,%f,%f);
                );
                out geom;
                """.formatted(Math.max(1, timeout.toSeconds()), minLat, minLon, maxLat, maxLon);
        HttpRequest request = HttpRequest.newBuilder(overpassEndpoint)
                .timeout(timeout)
                .header("User-Agent", "earth-platform-roads/0.1 (MINECRAFT-566)")
                .POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Overpass returned HTTP " + response.statusCode() + " for cell fetch");
        }
        checkForErrorRemark(response.body());
        return OverpassWayParser.parseWays(response.body());
    }

    /**
     * Overpass can start an HTTP 200 response, hit a runtime error partway through (e.g. its own
     * {@code timeout:} budget expiring mid-query), and still finish the response with a top-level
     * {@code "remark"} string describing that failure -- see Overpass's own {@code
     * Output_JSON::write_footer}, which only ever emits {@code "remark"} when it has an error/warning
     * message to report. A 200 status alone is therefore not proof the body is a complete, trustworthy
     * answer: this must be checked and treated as a failure (same as a non-200 status or a network
     * exception) BEFORE this cell's ways are written to the ready or confirmed-empty cache -- missing
     * this would quietly persist an incomplete fetch (zero elements as confirmed-empty forever, or
     * fewer ways than actually exist as a wrongly "complete" ready tile) exactly where review flagged
     * this as a real gap.
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
