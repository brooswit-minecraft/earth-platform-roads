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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mod's road network: every mapped way bundled in {@code data/earthplatformroads/roads/regions/}
 * (one indexed resource per region, listed by {@code regions/index.json}), loaded once from
 * resources baked into the mod jar. Nothing here makes a network request; every region's extract
 * was fetched and pinned ahead of time (see {@code docs/road-data-provenance.md}). Adding a region
 * means installing another regions/*.json resource (and listing it in index.json) -- no Java change
 * and no hard-coded coordinates, satisfying "wherever the corresponding source data is installed."
 *
 * <p>Lookups are indexed, not a linear scan of every way: ways are bucketed into
 * {@value #CELL_DEGREES}-degree latitude/longitude grid cells ({@link #cellKey}) covering their
 * bounding box plus a fixed margin for {@code halfWidthBlocks}, and {@link #coversColumn} only
 * walks the ways in the column's own cell.
 */
public final class RoadNetwork {
    private static final String INDEX_RESOURCE = "/data/earthplatformroads/roads/regions/index.json";
    private static final String REGION_RESOURCE_PREFIX = "/data/earthplatformroads/roads/regions/";

    /** Grid cell size for the spatial index: small enough that a cell's way list stays short, large
     *  enough that even a six-lane way's half-width never crosses more than one extra cell of margin. */
    private static final double CELL_DEGREES = 0.01;

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

    private static List<RoadSegment> candidatesNear(double lat, double lon) {
        List<RoadSegment> found = instance().cellIndex.get(cellKey(lat, lon));
        return found == null ? List.of() : found;
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
        List<String> regionFiles = loadIndex();
        List<RoadSegment> all = new ArrayList<>();
        for (String regionFile : regionFiles) all.addAll(loadRegion(regionFile));
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

    private static List<RoadSegment> loadRegion(String fileName) {
        String resource = REGION_RESOURCE_PREFIX + fileName;
        try (InputStream in = RoadNetwork.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing bundled resource " + resource);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<RoadSegment> ways = new ArrayList<>();
            for (JsonElement wayElement : root.getAsJsonArray("ways")) {
                ways.add(parseWay(wayElement.getAsJsonObject()));
            }
            return ways;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read bundled resource " + resource, e);
        }
    }

    private static RoadSegment parseWay(JsonObject way) {
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
