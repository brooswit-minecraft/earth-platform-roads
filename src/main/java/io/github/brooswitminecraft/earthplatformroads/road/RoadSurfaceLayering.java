package io.github.brooswitminecraft.earthplatformroads.road;

import io.github.brooswitminecraft.layeredslopes.LayerMath;

/**
 * Pure arithmetic mapping a road column's ground height to a {@link LayerMath} layer count for its
 * top surface, given the ground heights of its same-road neighbors, free of Minecraft/Earth
 * Platform types so it is unit-testable without bootstrapping the game.
 *
 * <p>A column's own single top block can only occupy its own cell ({@code [y, y+1)} in absolute
 * block space); {@code EarthGeneratorRoadMixin} never changes {@code y} itself (no cut/fill), so a
 * single column alone cannot represent a continuous ramp between two different integer ground
 * heights. What it CAN do, using only {@link io.github.brooswitminecraft.layeredslopes.LayeredBlock}'s
 * existing floor-anchored shape, is choose where within its own cell the surface sits:
 * {@link LayerMath#MAX_LAYERS} puts it at the very top of the cell ({@code y+1}, i.e. a full block,
 * unchanged from flat ground); {@link LayerMath#MIN_LAYERS} puts it near the very bottom
 * ({@code y + 1/16}).
 *
 * <p>So for two adjacent columns at {@code y} and {@code y+1}: giving the lower column (at
 * {@code y}) {@link LayerMath#MAX_LAYERS} puts its top at {@code y+1} -- exactly the lower edge of
 * the higher column's own cell. Giving the higher column (at {@code y+1}) {@link
 * LayerMath#MIN_LAYERS} puts ITS top at {@code (y+1) + 1/16} -- a 1/16-block seam, not a full
 * 1-block cliff. This is the fix for the MINECRAFT-566 review finding that the previous
 * slope-magnitude heuristic rounded a single-block step's slope down to zero and painted a full
 * block on both sides of the step, leaving the whole 1-block jump untouched.
 *
 * <p>This only closes a 1-block step between immediately adjacent columns. A column with both a
 * higher and a lower same-road neighbor (a local saddle) gets a midpoint compromise, and a step
 * larger than 1 block between immediately adjacent columns -- not expected from the real DEM's
 * resolution in practice, see {@code docs/design.md}, but not impossible -- still leaves a
 * residual gap this mixin does not attempt to close further without cut/fill.
 */
public final class RoadSurfaceLayering {
    private RoadSurfaceLayering() {}

    /**
     * Layer count for the road's top surface at a column with ground height {@code y}, given the
     * ground heights of its same-road 4-connected neighbors (however many of the 4 directions are
     * themselves covered by the same {@link RoadSegment}; an empty array means "flat", matching the
     * original always-full behaviour).
     */
    public static int layersForNeighbors(int y, int[] sameRoadNeighborGroundYs) {
        boolean hasHigher = false, hasLower = false;
        for (int neighborY : sameRoadNeighborGroundYs) {
            if (neighborY > y) hasHigher = true;
            else if (neighborY < y) hasLower = true;
        }
        if (hasHigher && hasLower) return (LayerMath.MAX_LAYERS + LayerMath.MIN_LAYERS) / 2;
        if (hasLower) return LayerMath.MIN_LAYERS;
        return LayerMath.MAX_LAYERS;
    }
}
