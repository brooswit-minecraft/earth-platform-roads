package io.github.brooswitminecraft.earthplatformroads.road;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The mod's road network, from two installed-data sources, both using the same versioned region
 * format (see {@link #SUPPORTED_SCHEMA_VERSION}):
 * <ul>
 *   <li><b>Bundled</b> regions baked into the mod jar at
 *       {@code data/earthplatformroads/roads/regions/}, listed by that directory's own
 *       {@code index.json} (classpath resources have no directory listing, so an explicit index is
 *       required here).</li>
 *   <li><b>External</b> regions: every {@code *.json} file found directly under
 *       {@link #externalRegionsDirectory()} (default: {@code config/earthplatformroads/roads/regions/}
 *       in the game's own install -- a real directory on disk, so no index file is needed; every
 *       file there is read). This is what makes "wherever the corresponding source data is
 *       installed" literal: installing mapped coverage for a new destination is dropping a region
 *       file into that folder, not shipping a bigger bundled extract or changing any Java code --
 *       see MINECRAFT-566's milestone update asking for exactly this, tested against an
 *       intentionally unbundled destination in {@code RoadNetworkExternalRegionTest}.</li>
 * </ul>
 * Nothing here makes a network request; every region's extract was fetched and pinned ahead of
 * time (see {@code docs/road-data-provenance.md}).
 *
 * <p>Lookups are indexed, not a linear scan of every way: ways are bucketed into
 * {@value #CELL_DEGREES}-degree latitude/longitude grid cells ({@link #cellKey}) covering their
 * bounding box plus a fixed margin for {@code halfWidthBlocks}, and {@link #coversColumn} only
 * walks the ways in the column's own cell.
 */
public final class RoadNetwork {
    private static final String INDEX_RESOURCE = "/data/earthplatformroads/roads/regions/index.json";
    private static final String REGION_RESOURCE_PREFIX = "/data/earthplatformroads/roads/regions/";

    /** The region JSON format version this build understands; see {@link #checkSchemaVersion}. */
    static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** Test-only override for {@link #externalRegionsDirectory()}; {@code null} means "use the real FML config dir". */
    private static volatile Path externalRegionsDirectoryOverride;

    /** Grid cell size for the spatial index: small enough that a cell's way list stays short, large
     *  enough that even a six-lane way's half-width never crosses more than one extra cell of margin.
     *  Package-visible: {@link RoadTileSource} fetches and caches at this exact same cell granularity. */
    public static final double CELL_DEGREES = 0.01;

    /** Test-only override for the live tile source {@link #candidatesNear} also checks; {@code null} means "use the default production wiring". */
    private static volatile RoadTileSource tileSourceOverride;
    /** Test-only overrides for {@link #buildDefaultTileSource}'s two real inputs, without replacing the production wiring itself. */
    private static volatile URI tileEndpointOverride;
    private static volatile Path tileCacheRootOverride;
    /** The real, lazily-built production tile source; {@code null} until first needed, or if FML paths are unavailable. */
    private static volatile RoadTileSource defaultTileSource;

    /**
     * There is deliberately no hardcoded default live endpoint. Earlier attempts this ticket --
     * three public Overpass mirrors (overloaded), the official OSM editing API (policy-disallowed as
     * an automated game-fetch backend) -- are why: this mod no longer ships ANY live backend as a
     * public default. The one live source currently sanctioned is an opt-in preview, selected by
     * John himself (MINECRAFT-566 comment 55446): a hosted Overpass provider's free/noncommercial
     * evaluation tier (Overpass.ch), gated behind all of:
     * <ul>
     *   <li>{@code -Dearthplatformroads.previewProvider=overpass-ch} -- without this exact value, no
     *       network fetch ever happens, regardless of the other properties below.</li>
     *   <li>{@code -Dearthplatformroads.previewEndpoint=...} -- required, no default URL.</li>
     *   <li>The {@code EARTHPLATFORMROADS_PREVIEW_API_KEY} environment variable -- the credential,
     *       required (comment 55448: "make missing key disable the live provider rather than issue
     *       an anonymous request") -- without it, this method returns a network-disabled source even
     *       if the two properties above are set. Deliberately NOT also readable from a JVM
     *       {@code -D} system property: those appear in process argument listings (e.g. {@code ps}),
     *       unlike an environment variable, so there is exactly one way to supply this credential.
     *       Never logged, never written into this cache's own files, never hardcoded.</li>
     *   <li>{@code -Dearthplatformroads.previewMinRequestIntervalMs=...} -- optional, defaults to
     *       {@value #DEFAULT_PREVIEW_MIN_REQUEST_INTERVAL_MS}ms, a conservative client-side rate
     *       limit appropriate for a free evaluation tier (see {@link RoadTileSource#fetchCell}'s
     *       single-in-flight-request guarantee, which this rate limit sits on top of, and its own
     *       {@code Retry-After} handling for 429/503 responses).</li>
     * </ul>
     * {@link RoadTileSource#CREDENTIAL_BOUND_ENDPOINT} is the ONLY origin+path the credential is
     * ever attached to, regardless of what {@code previewEndpoint} is configured to -- a
     * misconfigured property can disable the preview (wrong endpoint, so Overpass.ch itself rejects
     * the query) but can never cause the credential to be sent elsewhere.
     * This is explicitly a live-data preview, not the pinned/immutable worldwide snapshot the
     * offline SQLite-catalog pilot work is building toward -- the two are separate efforts.
     */
    private static final Duration TILE_FETCH_TIMEOUT = Duration.ofSeconds(20);
    private static final long DEFAULT_PREVIEW_MIN_REQUEST_INTERVAL_MS = 5000;

    private static volatile RoadNetwork instance;

    private final List<RoadSegment> allSegments;
    private final Map<Long, List<RoadSegment>> cellIndex;

    private RoadNetwork(List<RoadSegment> allSegments) {
        this.allSegments = allSegments;
        this.cellIndex = buildIndex(allSegments);
    }

    private static RoadNetwork instance() {
        RoadNetwork cached = instance;
        if (cached != null) return cached;
        synchronized (RoadNetwork.class) {
            if (instance == null) instance = new RoadNetwork(loadAllRegions());
            return instance;
        }
    }

    /**
     * Test-only: force the external regions directory (or {@code null} to use the real FML config
     * dir) and discard the cached singleton so the next access reloads from the new state. Tests
     * that call this with a non-null directory MUST call it again with {@code null} afterward (an
     * {@code @AfterAll}/{@code @AfterEach}), or every other test in the same JVM run will keep
     * seeing that test's external fixture instead of a clean bundled-only network.
     */
    static void resetForTests(Path externalRegionsDirectoryForTests) {
        synchronized (RoadNetwork.class) {
            externalRegionsDirectoryOverride = externalRegionsDirectoryForTests;
            instance = null;
        }
    }

    /** Test-only: install (or clear, with {@code null}) a specific {@link RoadTileSource} instance directly, bypassing the production wiring entirely. */
    static void setTileSourceForTests(RoadTileSource tileSource) {
        tileSourceOverride = tileSource;
    }

    /**
     * Test-only: override the two real inputs {@link #buildDefaultTileSource} uses (endpoint,
     * cache root) without replacing the production wiring itself -- unlike
     * {@link #setTileSourceForTests}, this exercises the actual code path an ordinary install runs,
     * just pointed at a local fixture instead of the real network and a temp directory instead of
     * the real game dir. Pass {@code null} for either to go back to that input's real default.
     */
    static void setDefaultTileSourceInputsForTests(URI endpointOverride, Path cacheRootOverride) {
        synchronized (RoadNetwork.class) {
            tileEndpointOverride = endpointOverride;
            tileCacheRootOverride = cacheRootOverride;
            defaultTileSource = null;
        }
    }

    /**
     * The live tile source {@link #candidatesNear} also checks, alongside the bundled/external
     * regions preloaded into {@link #cellIndex}. This is the actual production wiring: an ordinary
     * install reaches a real, lazily-built {@link RoadTileSource} here (not just in a test), caching
     * to a persistent directory under the game's own install so a later run (including a real
     * restart) re-reads the same cache rather than starting over -- see
     * {@link #buildDefaultTileSource}'s own doc for the one honest limitation this has (install-wide,
     * not per-save-scoped). {@code null} only if FML's paths were never set up in this JVM (a
     * context outside the real modded game) or FML's config dir genuinely cannot be resolved.
     */
    private static RoadTileSource tileSource() {
        if (tileSourceOverride != null) return tileSourceOverride;
        RoadTileSource cached = defaultTileSource;
        if (cached != null) return cached;
        synchronized (RoadNetwork.class) {
            if (defaultTileSource == null) defaultTileSource = buildDefaultTileSource();
            return defaultTileSource;
        }
    }

    /**
     * Builds the real production {@link RoadTileSource}: a persistent cache directory under the
     * game's own install, {@code <gamedir>/earthplatformroads/road_tiles/}.
     *
     * <p>Live network fetch is enabled only when every opt-in condition this class's own field doc
     * describes is met (provider name, endpoint, and the other preview properties); otherwise this
     * returns a read-only cache -- any cell already on disk from a prior fetch still answers, but
     * nothing new is ever auto-fetched, so there is no code path that can reach a live backend
     * without an operator explicitly opting in. {@code tileEndpointOverride} (test-only) also
     * enables network fetch directly, bypassing the opt-in properties, so tests can exercise this
     * wiring without setting system properties.
     *
     * <p><b>Named, honest limitation</b>: this cache is scoped to the whole game install, not to
     * one save -- MINECRAFT-566's review asked for "a world pinning its source snapshot," and a
     * single persistent cache does pin road data to whatever was actually fetched (the same
     * property {@link RoadTileSourceTest} verifies for the cache itself), but two different Earth
     * saves on the same install currently share one cache rather than each getting its own. Scoping
     * this per-save would mean resolving the active save's own directory from inside a mixin on
     * {@code EarthGenerator} (which does not expose one), not attempted here.
     */
    private static RoadTileSource buildDefaultTileSource() {
        try {
            Path cacheRoot = tileCacheRootOverride != null
                    ? tileCacheRootOverride
                    : FMLPaths.GAMEDIR.get().resolve("earthplatformroads").resolve("road_tiles");

            if (tileEndpointOverride != null) {
                return new RoadTileSource(tileEndpointOverride, cacheRoot, true, TILE_FETCH_TIMEOUT);
            }

            boolean optedIntoPreview = "overpass-ch".equals(System.getProperty("earthplatformroads.previewProvider"));
            String previewEndpointProperty = System.getProperty("earthplatformroads.previewEndpoint");
            // Env-var only, deliberately: see this field's own javadoc on RoadTileSource.apiKey for
            // why a -D system property is never accepted as a credential source here.
            String apiKey = System.getenv("EARTHPLATFORMROADS_PREVIEW_API_KEY");
            boolean fullyProvisioned = optedIntoPreview && previewEndpointProperty != null
                    && !previewEndpointProperty.isEmpty() && apiKey != null && !apiKey.isEmpty();
            if (!fullyProvisioned) {
                // Comment 55448: a missing key disables the live provider entirely rather than
                // issuing an anonymous request -- this is the single gate for that, not a special
                // case inside RoadTileSource itself.
                return new RoadTileSource(URI.create("about:blank"), cacheRoot, false, TILE_FETCH_TIMEOUT);
            }
            long minIntervalMs = Long.getLong("earthplatformroads.previewMinRequestIntervalMs", DEFAULT_PREVIEW_MIN_REQUEST_INTERVAL_MS);
            return new RoadTileSource(URI.create(previewEndpointProperty), cacheRoot, true, TILE_FETCH_TIMEOUT,
                    apiKey, Duration.ofMillis(minIntervalMs));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Public so {@code EarthGeneratorPrepareMixin} (a different package) can trigger a prefetch for
     * a point ahead of generation; {@code null} when no live tile source is configured, in which
     * case the mixin does nothing. This is the ONLY sanctioned way to obtain a reference that can
     * call {@link RoadTileSource#waysForCell} (the fetching method) -- {@link #candidatesNear}
     * itself only ever calls the non-fetching {@link RoadTileSource#cachedWaysForCellOrEmpty}.
     */
    public static RoadTileSource liveTileSourceForPrefetch() {
        return tileSource();
    }

    /** Every mapped way across every installed region. */
    public static List<RoadSegment> segments() {
        return instance().allSegments;
    }

    public static boolean coversColumn(MercatorGrid grid, int x, int z) {
        return segmentCoveringColumn(grid, x, z) != null;
    }

    /**
     * The first mapped at-grade way covering this column, or {@code null} if none does. A mapped
     * bridge or tunnel way never matches here -- this mod cannot yet render a grade-separated
     * crossing correctly, and the ticket is explicit that an unsupported gap must be reported, not
     * papered over by drawing an at-grade surface along the riverbed or through solid rock (see
     * {@link #excludedBridgesAndTunnels()} for the explicit list of what that excludes).
     */
    public static RoadSegment segmentCoveringColumn(MercatorGrid grid, int x, int z) {
        GeoPoint here = grid.geographic(x + 0.5, z + 0.5);
        for (RoadSegment segment : candidatesNear(here.latitude(), here.longitude())) {
            if (segment.bridge() || segment.tunnel()) continue;
            if (segment.coversColumn(grid, x, z)) return segment;
        }
        return null;
    }

    /**
     * MINECRAFT-566 comment 55462's requested diagnostic: WHY does this column have no road
     * surface, distinguishing causes {@link #segmentCoveringColumn}/{@link #coversColumn} alone
     * cannot -- "rather than assuming readiness alone fixes this occurrence." Nonsecret: exposes
     * only which case applies, never a request detail or credential. Does not cover a mixin-level
     * terrain skip (e.g. "under water") -- that check lives in {@code EarthGeneratorRoadMixin}, not
     * here; a caller can check the real surface height against the water level itself alongside
     * this diagnosis.
     */
    public enum ColumnDiagnosis {
        /** A real, non-bridge/tunnel mapped way covers this column -- it should render. */
        COVERED,
        /** A mapped way covers this column, but it's a bridge or tunnel -- deliberately excluded (see {@link #excludedBridgesAndTunnels()}). */
        EXCLUDED_BRIDGE_OR_TUNNEL,
        /** No segment covers this column, and this column's own cell has never been successfully fetched (or no live source is configured to ask). */
        PENDING_NO_DATA,
        /**
         * No segment covers this column, this column's own cell is still pending (never
         * successfully fetched) -- AND {@link RoadTileSource#sustainedOutage()} says this instance's
         * last several real fetch attempts, across every cell, have all failed. MINECRAFT-566
         * comment 55474's "distinct diagnostics" for a sustained outage, as opposed to a cell that is
         * merely pending because nobody has asked for it yet (still {@link #PENDING_NO_DATA}).
         */
        PENDING_PROVIDER_OUTAGE,
        /** No segment covers this column, and this column's own cell WAS fetched and genuinely has no mapped road here. */
        CONFIRMED_EMPTY_NO_ROAD,
    }

    public static ColumnDiagnosis diagnoseColumn(MercatorGrid grid, int x, int z) {
        GeoPoint here = grid.geographic(x + 0.5, z + 0.5);
        boolean anyBridgeOrTunnelCovers = false;
        for (RoadSegment segment : candidatesNear(here.latitude(), here.longitude())) {
            if (segment.coversColumn(grid, x, z)) {
                if (segment.bridge() || segment.tunnel()) {
                    anyBridgeOrTunnelCovers = true;
                    continue;
                }
                return ColumnDiagnosis.COVERED;
            }
        }
        if (anyBridgeOrTunnelCovers) return ColumnDiagnosis.EXCLUDED_BRIDGE_OR_TUNNEL;
        RoadTileSource tiles = tileSource();
        if (tiles == null) return ColumnDiagnosis.CONFIRMED_EMPTY_NO_ROAD; // preloaded-only network; nothing found there genuinely means nothing mapped
        if (tiles.cellState(here.latitude(), here.longitude()) != RoadTileSource.CellState.PENDING) return ColumnDiagnosis.CONFIRMED_EMPTY_NO_ROAD;
        return tiles.sustainedOutage() ? ColumnDiagnosis.PENDING_PROVIDER_OUTAGE : ColumnDiagnosis.PENDING_NO_DATA;
    }

    /** Every mapped way this mod deliberately does not render a surface for, because it is a bridge or tunnel. */
    public static List<RoadSegment> excludedBridgesAndTunnels() {
        List<RoadSegment> excluded = new ArrayList<>();
        for (RoadSegment segment : segments()) if (segment.bridge() || segment.tunnel()) excluded.add(segment);
        return excluded;
    }

    /** Every mapped junction: a pair of distinct ways sharing at least one real OSM node ID. */
    public static List<Junction> junctions() {
        List<RoadSegment> all = segments();
        List<Junction> found = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                RoadSegment a = all.get(i), b = all.get(j);
                List<Long> shared = RoadSegment.sharedNodeIds(a, b);
                if (!shared.isEmpty()) found.add(new Junction(a, b, shared));
            }
        }
        return found;
    }

    public record Junction(RoadSegment a, RoadSegment b, List<Long> sharedNodeIds) {}

    /**
     * Candidates from the preloaded bundled+external index, plus (if a live tile source is
     * configured) whatever is already cached on disk for this cell AND its Moore neighborhood (the
     * 8 cells sharing an edge or corner with it) -- all read-only cache checks, never a network
     * fetch; see {@link RoadTileSource#cachedWaysForCellOrEmpty} and the class doc there for why
     * {@link io.github.brooswitminecraft.earthplatformroads.mixin.EarthGeneratorPrepareMixin}, not
     * this lookup, is the only place that may ever fetch.
     *
     * <p><b>Why the neighborhood, and why this carries a seam without extra fetches</b>
     * (MINECRAFT-566 comment 55456, correcting 55454's original fix): a live provider's bbox query
     * for one cell returns the FULL geometry of any way that merely touches that cell's bbox,
     * including whatever portion extends into a neighboring cell -- the same way the real Overpass
     * protocol has always worked here (see {@code OverpassWayParser}). So a crossing way's complete
     * geometry is typically already sitting in whichever single cell actually got fetched; a column
     * in the cell next door can find it by reading that neighbor's cache too, without this mod ever
     * needing to fetch the neighbor itself. This replaces 55454's original fix, which instead issued
     * up to 9 sequential {@code waysForCell} calls from {@code EarthGeneratorPrepareMixin} -- found
     * (55456) to interact badly with the provider's own rate limit: with a realistic multi-second
     * {@code previewMinRequestIntervalMs}, only the first of those 9 calls could actually succeed
     * before the next-eligible deadline rejected the rest, so the fix did not reliably hold in the
     * real production profile, only in a test that (by using the simple, zero-interval constructor)
     * never exercised that deadline. This version needs no additional network calls at all, so it
     * cannot run into that problem.
     */
    private static List<RoadSegment> candidatesNear(double lat, double lon) {
        List<RoadSegment> preloaded = instance().cellIndex.get(cellKey(lat, lon));
        RoadTileSource tiles = tileSource();
        if (tiles == null) return preloaded == null ? List.of() : preloaded;

        List<RoadSegment> fromTiles = new ArrayList<>();
        for (int dLat = -1; dLat <= 1; dLat++) {
            for (int dLon = -1; dLon <= 1; dLon++) {
                fromTiles.addAll(tiles.cachedWaysForCellOrEmpty(lat + dLat * CELL_DEGREES, lon + dLon * CELL_DEGREES));
            }
        }
        if (fromTiles.isEmpty()) return preloaded == null ? List.of() : preloaded;
        if (preloaded == null || preloaded.isEmpty()) return fromTiles;
        List<RoadSegment> merged = new ArrayList<>(preloaded);
        merged.addAll(fromTiles);
        return merged;
    }

    private static long cellKey(double lat, double lon) {
        long cellLat = (long) Math.floor(lat / CELL_DEGREES);
        long cellLon = (long) Math.floor(lon / CELL_DEGREES);
        return (cellLat << 32) ^ (cellLon & 0xFFFFFFFFL);
    }

    private static Map<Long, List<RoadSegment>> buildIndex(List<RoadSegment> segments) {
        Map<Long, List<RoadSegment>> index = new HashMap<>();
        for (RoadSegment segment : segments) {
            // One degree of longitude is never narrower than one degree of latitude in metres, so
            // using the same margin in both axes only ever over-covers (extra candidates checked by
            // exact coversColumn, never a missed one), not under-covers.
            double marginDegrees = CELL_DEGREES; // generous margin: segment half-widths here are a few blocks (metres), cells are ~1.1 km.
            double minLat = segment.minLat() - marginDegrees, maxLat = segment.maxLat() + marginDegrees;
            double minLon = segment.minLon() - marginDegrees, maxLon = segment.maxLon() + marginDegrees;
            for (double lat = minLat; lat <= maxLat; lat += CELL_DEGREES) {
                for (double lon = minLon; lon <= maxLon; lon += CELL_DEGREES) {
                    index.computeIfAbsent(cellKey(lat, lon), k -> new ArrayList<>()).add(segment);
                }
            }
            // Ensure the segment's own max-corner cell is always included even if the loop above
            // under-shot it by floating-point drift.
            index.computeIfAbsent(cellKey(maxLat, maxLon), k -> new ArrayList<>()).add(segment);
        }
        return index;
    }

    private static List<RoadSegment> loadAllRegions() {
        List<RoadSegment> all = new ArrayList<>();
        for (String regionFile : loadIndex()) all.addAll(loadBundledRegion(regionFile));
        all.addAll(loadExternalRegions());
        return all;
    }

    private static List<String> loadIndex() {
        try (InputStream in = RoadNetwork.class.getResourceAsStream(INDEX_RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing bundled resource " + INDEX_RESOURCE);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> files = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("regions")) files.add(e.getAsString());
            return files;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read bundled resource " + INDEX_RESOURCE, e);
        }
    }

    private static List<RoadSegment> loadBundledRegion(String fileName) {
        String resource = REGION_RESOURCE_PREFIX + fileName;
        try (InputStream in = RoadNetwork.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing bundled resource " + resource);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return parseRegionWays(root, resource);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read bundled resource " + resource, e);
        }
    }

    /**
     * Where this install's own, external (not bundled in the jar) region files live: real files a
     * player or server operator drops in, one destination at a time, with no mod rebuild. Every
     * {@code *.json} file directly under this directory is read as a region; there is no index file
     * here (unlike the bundled resources) because a real directory can simply be listed.
     */
    private static Path externalRegionsDirectory() {
        if (externalRegionsDirectoryOverride != null) return externalRegionsDirectoryOverride;
        try {
            return FMLPaths.CONFIGDIR.get().resolve("earthplatformroads").resolve("roads").resolve("regions");
        } catch (RuntimeException e) {
            // FML's paths were never set up in this JVM (a context outside the real modded game) --
            // no external regions are available, bundled-only is still correct.
            return null;
        }
    }

    private static List<RoadSegment> loadExternalRegions() {
        Path directory = externalRegionsDirectory();
        if (directory == null || !Files.isDirectory(directory)) return List.of();
        List<RoadSegment> all = new ArrayList<>();
        List<Path> jsonFiles;
        try (var listing = Files.list(directory)) {
            jsonFiles = listing.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not list external regions directory " + directory, e);
        }
        for (Path path : jsonFiles) {
            try (InputStream in = Files.newInputStream(path)) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                all.addAll(parseRegionWays(root, path.toString()));
            } catch (IOException e) {
                throw new IllegalStateException("Could not read external region file " + path, e);
            }
        }
        return all;
    }

    /** Package-visible so {@link RoadTileSource} can parse a cached tile file through the exact same code. */
    static List<RoadSegment> parseRegionWays(JsonObject root, String source) {
        checkSchemaVersion(root, source);
        List<RoadSegment> ways = new ArrayList<>();
        for (JsonElement wayElement : root.getAsJsonArray("ways")) {
            ways.add(parseWay(wayElement.getAsJsonObject()));
        }
        return ways;
    }

    /**
     * Every region, bundled or external, must declare the schema version it was written against,
     * checked against {@link #SUPPORTED_SCHEMA_VERSION} -- "versioned tile pipeline" per
     * MINECRAFT-566's milestone update: a region written for a format this build does not
     * understand must fail loudly, not silently misparse or partially load.
     */
    private static void checkSchemaVersion(JsonObject root, String source) {
        if (!root.has("schema_version")) {
            throw new IllegalStateException("Region " + source + " is missing the required \"schema_version\" field");
        }
        int version = root.get("schema_version").getAsInt();
        if (version != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("Region " + source + " has schema_version=" + version
                    + ", but this build only supports schema_version=" + SUPPORTED_SCHEMA_VERSION);
        }
    }

    /** Package-private (not private) so {@code RoadNetworkLoaderTest} can exercise it directly against a constructed way, without a bundled resource file. */
    static RoadSegment parseWay(JsonObject way) {
        JsonObject tags = way.getAsJsonObject("way_tags");
        String name = tags.has("name") ? tags.get("name").getAsString() : ("way " + way.get("way_id").getAsLong());
        String highway = tags.get("highway").getAsString();
        RoadClass roadClass = RoadClass.fromOsmHighway(highway);
        if (roadClass == null) {
            throw new IllegalStateException(
                    "Bundled way " + way.get("way_id").getAsLong() + " has non-vehicular highway=" + highway
                            + "; foot trails must not be ingested as roads");
        }
        int lanes = tags.has("lanes") ? parseLanes(tags.get("lanes").getAsString()) : 0;
        double halfWidth = roadClass.halfWidthBlocks(lanes);

        JsonArray centerlineJson = way.getAsJsonArray("centerline");
        GeoPoint[] centerline = new GeoPoint[centerlineJson.size()];
        for (int i = 0; i < centerlineJson.size(); i++) {
            JsonObject obj = centerlineJson.get(i).getAsJsonObject();
            centerline[i] = new GeoPoint(obj.get("lat").getAsDouble(), obj.get("lon").getAsDouble());
        }

        long[] nodeIds = new long[0];
        if (way.has("node_ids")) {
            JsonArray nodeIdsJson = way.getAsJsonArray("node_ids");
            nodeIds = new long[nodeIdsJson.size()];
            for (int i = 0; i < nodeIdsJson.size(); i++) nodeIds[i] = nodeIdsJson.get(i).getAsLong();
        }

        boolean bridge = way.has("bridge") && way.get("bridge").getAsBoolean();
        boolean tunnel = way.has("tunnel") && way.get("tunnel").getAsBoolean();
        int layer = way.has("layer") ? way.get("layer").getAsInt() : 0;

        return new RoadSegment(way.get("way_id").getAsLong(), name, roadClass, centerline, nodeIds, halfWidth, bridge, tunnel, layer);
    }

    /** OSM {@code lanes} is free text in principle; a non-numeric value (e.g. a bad import) falls back to the class default. */
    private static int parseLanes(String lanes) {
        try {
            return Integer.parseInt(lanes.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
