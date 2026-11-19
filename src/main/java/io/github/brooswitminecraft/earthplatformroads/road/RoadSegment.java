package io.github.brooswitminecraft.earthplatformroads.road;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;

/**
 * A pinned, bounded OpenStreetMap road centerline (a short run of consecutive way nodes), and the
 * half-width either side of it that counts as road surface. Coordinates are latitude/longitude, as
 * pinned in {@code data/earthplatformroads/roads/*.json}; projecting them to a world's own block
 * grid happens at query time against that world's actual {@link MercatorGrid}, never baked in, so
 * the same pinned geometry is correct for whatever home point and scale the world was made with.
 */
public record RoadSegment(String name, GeoPoint[] centerline, double halfWidthBlocks) {
    public RoadSegment {
        if (centerline.length < 2) throw new IllegalArgumentException("A road segment needs at least two points");
        if (!(halfWidthBlocks > 0)) throw new IllegalArgumentException("halfWidthBlocks must be positive");
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
