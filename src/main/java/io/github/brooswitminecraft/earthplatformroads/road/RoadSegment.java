package io.github.brooswitminecraft.earthplatformroads.road;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import java.util.List;

/**
 * One mapped OpenStreetMap way's centerline (or a pinned, bounded run of its consecutive nodes),
 * and the half-width either side of it that counts as road surface. Coordinates are
 * latitude/longitude, as pinned in {@code data/earthplatformroads/roads/regions/*.json}; projecting
 * them to a world's own block grid happens at query time against that world's actual
 * {@link MercatorGrid}, never baked in, so the same pinned geometry is correct for whatever home
 * point and scale the world was made with.
 *
 * <p>{@code nodeIds} carries the way's real OSM node IDs 1:1 with {@code centerline}, in the same
 * order: {@link RoadNetwork} uses a node ID shared by two segments as a mapped junction, not
 * geometric proximity, so two ways that merely cross without a shared node are correctly not a
 * junction. An empty {@code nodeIds} array (the pre-junction-tracking pinned fixture) means this
 * segment opts out of junction detection entirely, not that it has zero junctions.
 */
public record RoadSegment(
        long wayId,
        String name,
        RoadClass roadClass,
        GeoPoint[] centerline,
        long[] nodeIds,
        double halfWidthBlocks,
        boolean bridge,
        boolean tunnel,
        int layer) {
    public RoadSegment {
        if (centerline.length < 2) throw new IllegalArgumentException("A road segment needs at least two points");
        if (!(halfWidthBlocks > 0)) throw new IllegalArgumentException("halfWidthBlocks must be positive");
        if (nodeIds.length != 0 && nodeIds.length != centerline.length) {
            throw new IllegalArgumentException("nodeIds must be empty or match centerline length 1:1");
        }
    }

    /** Minimal constructor for geometry that predates class/junction/bridge tracking (MINECRAFT-564's pinned fixture, and tests). */
    public RoadSegment(String name, GeoPoint[] centerline, double halfWidthBlocks) {
        this(0L, name, RoadClass.SECONDARY, centerline, new long[0], halfWidthBlocks, false, false, 0);
    }

    /**
     * The closest distance, in blocks, from the column centre {@code (x + 0.5, z + 0.5)} to this
     * road's centerline, projected into {@code grid}'s block space. {@link Double#MAX_VALUE} is
     * never returned; callers compare against {@link #halfWidthBlocks} themselves.
     */
    public double distanceBlocks(MercatorGrid grid, int x, int z) {
        double px = x + 0.5, pz = z + 0.5;
        double best = Double.MAX_VALUE;
        double prevBx = grid.blockX(centerline[0]), prevBz = grid.blockZ(centerline[0]);
        for (int i = 1; i < centerline.length; i++) {
            double bx = grid.blockX(centerline[i]), bz = grid.blockZ(centerline[i]);
            best = Math.min(best, distanceToSegment(px, pz, prevBx, prevBz, bx, bz));
            prevBx = bx;
            prevBz = bz;
        }
        return best;
    }

    public boolean coversColumn(MercatorGrid grid, int x, int z) {
        return distanceBlocks(grid, x, z) <= halfWidthBlocks;
    }

    /**
     * This way's bounding box in latitude/longitude, used by {@link RoadNetwork}'s spatial index to
     * place the segment in every grid cell it can possibly reach (centerline cells plus one cell of
     * margin for {@code halfWidthBlocks}), so a column's cell lookup is a short local list, not a
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

    public static List<Long> sharedNodeIds(RoadSegment a, RoadSegment b) {
        if (a.nodeIds.length == 0 || b.nodeIds.length == 0) return List.of();
        List<Long> shared = new java.util.ArrayList<>();
        for (long id : a.nodeIds) for (long otherId : b.nodeIds) if (id == otherId) shared.add(id);
        return shared;
    }

    /** Point-to-segment distance; the segment degenerates to point {@code (ax,az)} when it has zero length. */
    private static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
        t = Math.clamp(t, 0, 1);
        double nx = ax + t * dx, nz = az + t * dz;
        return Math.hypot(px - nx, pz - nz);
    }
}
