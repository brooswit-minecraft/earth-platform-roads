package io.github.brooswitminecraft.earthplatformroads.road;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import java.util.List;

/**
 * One mapped OpenStreetMap way's centerline (or a pinned, bounded run of its consecutive nodes),
 * and the PAVED half-width either side of it. Coordinates are latitude/longitude, as pinned in
 * {@code data/earthplatformroads/roads/regions/*.json}; projecting them to a world's own block grid
 * happens at query time against that world's actual {@link MercatorGrid}, never baked in, so the
 * same pinned geometry is correct for whatever home point and scale the world was made with.
 *
 * <p>{@code nodeIds} carries the way's real OSM node IDs 1:1 with {@code centerline}, in the same
 * order: {@link RoadNetwork} uses a node ID shared by two segments as a mapped junction, not
 * geometric proximity, so two ways that merely cross without a shared node are correctly not a
 * junction. An empty {@code nodeIds} array (the pre-junction-tracking pinned fixture) means this
 * segment opts out of junction detection entirely, not that it has zero junctions.
 *
 * <p><b>Cross-section (MINECRAFT-566's road-styling ask)</b>: {@code pavedHalfWidthBlocks} alone is
 * no longer the whole footprint. A thin yellow-terracotta centerline sits astride the centerline
 * itself (within {@value #CENTERLINE_HALF_WIDTH_BLOCKS} blocks, classes that
 * {@link RoadClass#hasCenterline} only), the paved (blackstone) surface extends out to
 * {@code pavedHalfWidthBlocks}, and a narrow gravel shoulder continues {@value #SHOULDER_WIDTH_BLOCKS}
 * block further out to {@link #totalHalfWidthBlocks()} -- see {@link #crossSectionAt}, the single
 * source of truth for which zone a column falls into, used by both {@link #coversColumn} (the
 * WHOLE footprint, paved and shoulder alike -- a bridge/tunnel's exclusion, or a column simply being
 * "on this road at all", covers the shoulder too) and the road surface mixin. Nesting all three
 * zones against the exact same {@link #distanceBlocks} value, with
 * {@code CENTERLINE_HALF_WIDTH_BLOCKS < pavedHalfWidthBlocks < totalHalfWidthBlocks()} always, is
 * what guarantees the centerline can never land outside the paved surface (a John-named acceptance
 * requirement) regardless of how a curve or junction's nearest-segment geometry behaves -- there is
 * no separate, independently-computed centerline check that could disagree with the paved one.
 */
public record RoadSegment(
        long wayId,
        String name,
        RoadClass roadClass,
        GeoPoint[] centerline,
        long[] nodeIds,
        double pavedHalfWidthBlocks,
        boolean bridge,
        boolean tunnel,
        int layer) {
    /** Thin stripe astride the centerline itself -- "a centerline, not a whole lane" per John's styling ask. */
    public static final double CENTERLINE_HALF_WIDTH_BLOCKS = 0.5;
    /** Narrow gravel shoulder immediately outside the paved surface, on both edges. */
    public static final double SHOULDER_WIDTH_BLOCKS = 1.0;

    /** Which part of this road's cross-section a column falls into, if any; see {@link #crossSectionAt}. */
    public enum CrossSection { CENTERLINE, PAVED, SHOULDER, OFF_ROAD }

    public RoadSegment {
        if (centerline.length < 2) throw new IllegalArgumentException("A road segment needs at least two points");
        if (!(pavedHalfWidthBlocks > 0)) throw new IllegalArgumentException("pavedHalfWidthBlocks must be positive");
        if (nodeIds.length != 0 && nodeIds.length != centerline.length) {
            throw new IllegalArgumentException("nodeIds must be empty or match centerline length 1:1");
        }
    }

    /** Minimal constructor for geometry that predates class/junction/bridge tracking (MINECRAFT-564's pinned fixture, and tests). */
    public RoadSegment(String name, GeoPoint[] centerline, double pavedHalfWidthBlocks) {
        this(0L, name, RoadClass.SECONDARY, centerline, new long[0], pavedHalfWidthBlocks, false, false, 0);
    }

    /**
     * MINECRAFT-566 comment 55518: {@code RoadDeck}'s own "is this sample point still THIS road"
     * check used plain record {@code .equals}, which compares {@code centerline} as a {@code GeoPoint[]}
     * -- array fields compare by REFERENCE under a record's auto-generated {@code equals}, not by
     * content. The same real way, independently parsed from two different tile/cache files (a real,
     * reproduced scenario: "the first copy drops out of the relative 3x3 lookup halo"), yields two
     * {@code RoadSegment} instances with identical VALUES but distinct backing arrays -- record
     * equality wrongly rejects them as different roads, which silently excluded the OTHER side's
     * samples from a station's cross-width scan right at that halo boundary.
     *
     * <p>Matches on the real way's own stable, positive OSM {@code wayId} when both sides have one
     * (the only identity that survives being independently re-parsed from a different file) --
     * falling back to full record equality when either side's {@code wayId} is not a real one
     * ({@code <= 0}, the pinned/test-fixture convention -- see the minimal constructor's own doc),
     * so distinct synthetic test roads that all happen to share {@code wayId == 0} are never
     * incorrectly merged.
     */
    public boolean isSameRoadAs(RoadSegment other) {
        if (other == null) return false;
        if (wayId > 0 && other.wayId > 0) return wayId == other.wayId;
        return equals(other);
    }

    /** The full footprint's own half-width, paved surface plus the gravel shoulder on each side. */
    public double totalHalfWidthBlocks() {
        return pavedHalfWidthBlocks + SHOULDER_WIDTH_BLOCKS;
    }

    /**
     * {@link CrossSection#OFF_ROAD} beyond {@link #totalHalfWidthBlocks()}; otherwise whichever of
     * {@link CrossSection#CENTERLINE}/{@link CrossSection#PAVED}/{@link CrossSection#SHOULDER} this
     * column's {@link #distanceBlocks} falls into. See this record's own class doc for why nesting
     * all three zones against the SAME distance value (rather than three independent checks) is
     * what guarantees the centerline is always a subset of the paved surface.
     */
    public CrossSection crossSectionAt(MercatorGrid grid, int x, int z) {
        double distance = distanceBlocks(grid, x, z);
        if (distance > totalHalfWidthBlocks()) return CrossSection.OFF_ROAD;
        if (roadClass.hasCenterline && distance <= CENTERLINE_HALF_WIDTH_BLOCKS) return CrossSection.CENTERLINE;
        if (distance <= pavedHalfWidthBlocks) return CrossSection.PAVED;
        return CrossSection.SHOULDER;
    }

    /**
     * The closest distance, in blocks, from the column centre {@code (x + 0.5, z + 0.5)} to this
     * road's centerline, projected into {@code grid}'s block space. {@link Double#MAX_VALUE} is
     * never returned; callers compare against {@link #pavedHalfWidthBlocks}/{@link #totalHalfWidthBlocks()} themselves.
     */
    public double distanceBlocks(MercatorGrid grid, int x, int z) {
        return projectOnto(grid, x, z).distance();
    }

    /**
     * MINECRAFT-566 comment 55513 (cross-slope acceptance for the flat deck): the nearest point on
     * this centerline to column {@code (x,z)}, and the unit tangent direction of whichever polyline
     * leg that nearest point falls on -- what {@link RoadDeck} needs to sample ACROSS the road's own
     * width at a single STATION (a fixed point along its length), rather than an isotropic box that
     * does not know which direction is "along the road" versus "across it".
     */
    public record Projection(double nearestX, double nearestZ, double tangentX, double tangentZ, double distance) {
        /** Unit perpendicular to {@link #tangentX}/{@link #tangentZ} -- "across the road" at this exact station. */
        public double perpendicularX() { return -tangentZ; }
        public double perpendicularZ() { return tangentX; }
    }

    public Projection projectOnto(MercatorGrid grid, int x, int z) {
        double px = x + 0.5, pz = z + 0.5;
        double bestDistSq = Double.MAX_VALUE;
        double bestNx = 0, bestNz = 0, bestTx = 1, bestTz = 0;
        double prevBx = grid.blockX(centerline[0]), prevBz = grid.blockZ(centerline[0]);
        for (int i = 1; i < centerline.length; i++) {
            double bx = grid.blockX(centerline[i]), bz = grid.blockZ(centerline[i]);
            double dx = bx - prevBx, dz = bz - prevBz;
            double lengthSquared = dx * dx + dz * dz;
            double t = lengthSquared == 0 ? 0 : ((px - prevBx) * dx + (pz - prevBz) * dz) / lengthSquared;
            t = Math.clamp(t, 0, 1);
            double nx = prevBx + t * dx, nz = prevBz + t * dz;
            double distSq = (px - nx) * (px - nx) + (pz - nz) * (pz - nz);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestNx = nx;
                bestNz = nz;
                double length = Math.sqrt(lengthSquared);
                if (length > 1e-9) {
                    bestTx = dx / length;
                    bestTz = dz / length;
                } else {
                    bestTx = 1;
                    bestTz = 0;
                }
            }
            prevBx = bx;
            prevBz = bz;
        }
        return new Projection(bestNx, bestNz, bestTx, bestTz, Math.sqrt(bestDistSq));
    }

    /** The WHOLE footprint, paved surface and gravel shoulder alike -- see {@link #crossSectionAt}. */
    public boolean coversColumn(MercatorGrid grid, int x, int z) {
        return distanceBlocks(grid, x, z) <= totalHalfWidthBlocks();
    }

    /**
     * This way's bounding box in latitude/longitude, used by {@link RoadNetwork}'s spatial index to
     * place the segment in every grid cell it can possibly reach (centerline cells plus one cell of
     * margin for {@link #totalHalfWidthBlocks()}), so a column's cell lookup is a short local list, not a
     * scan of every mapped way.
     */
    public double minLat() {
        double min = Double.MAX_VALUE;
        for (GeoPoint p : centerline) min = Math.min(min, p.latitude());
        return min;
    }

    public double maxLat() {
        double max = -Double.MAX_VALUE;
        for (GeoPoint p : centerline) max = Math.max(max, p.latitude());
        return max;
    }

    public double minLon() {
        double min = Double.MAX_VALUE;
        for (GeoPoint p : centerline) min = Math.min(min, p.longitude());
        return min;
    }

    public double maxLon() {
        double max = -Double.MAX_VALUE;
        for (GeoPoint p : centerline) max = Math.max(max, p.longitude());
        return max;
    }

    /** Node IDs this segment shares with {@code other}: a mapped junction between the two ways. */
    public boolean sharesJunctionWith(RoadSegment other) {
        if (nodeIds.length == 0 || other.nodeIds.length == 0) return false;
        for (long id : nodeIds) {
            for (long otherId : other.nodeIds) {
                if (id == otherId) return true;
            }
        }
        return false;
    }

    /** This way as the JSON object the versioned region/tile format uses, round-tripping through {@link RoadNetwork#parseWay}. */
    JsonObject toWayJson() {
        JsonObject json = new JsonObject();
        json.addProperty("way_id", wayId);
        JsonObject tags = new JsonObject();
        tags.addProperty("highway", roadClass.name().toLowerCase(java.util.Locale.ROOT));
        tags.addProperty("name", name);
        json.add("way_tags", tags);
        JsonArray centerlineJson = new JsonArray();
        for (GeoPoint point : centerline) {
            JsonObject p = new JsonObject();
            p.addProperty("lat", point.latitude());
            p.addProperty("lon", point.longitude());
            centerlineJson.add(p);
        }
        json.add("centerline", centerlineJson);
        if (nodeIds.length > 0) {
            JsonArray nodeIdsJson = new JsonArray();
            for (long id : nodeIds) nodeIdsJson.add(id);
            json.add("node_ids", nodeIdsJson);
        }
        json.addProperty("bridge", bridge);
        json.addProperty("tunnel", tunnel);
        json.addProperty("layer", layer);
        return json;
    }

    /** Every segment in {@code ways}, as the {@code "ways"} JSON array a region/tile file stores. */
    static JsonArray toWaysJsonArray(List<RoadSegment> ways) {
        JsonArray array = new JsonArray();
        for (RoadSegment way : ways) array.add(way.toWayJson());
        return array;
    }

    public static List<Long> sharedNodeIds(RoadSegment a, RoadSegment b) {
        if (a.nodeIds.length == 0 || b.nodeIds.length == 0) return List.of();
        List<Long> shared = new java.util.ArrayList<>();
        for (long id : a.nodeIds) for (long otherId : b.nodeIds) if (id == otherId) shared.add(id);
        return shared;
    }

}
