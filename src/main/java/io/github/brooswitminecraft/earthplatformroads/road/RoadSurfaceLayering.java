package io.github.brooswitminecraft.earthplatformroads.road;

import io.github.brooswitminecraft.layeredslopes.LayerMath;

/**
 * Pure arithmetic mapping a road column's distance to its nearest lower same-road neighbor to a
 * {@link LayerMath} layer count for its top surface, free of Minecraft/Earth Platform types so it
 * is unit-testable without bootstrapping the game.
 *
 * <p>History, for why this is a distance-based decay rather than a plain immediate-neighbor
 * comparison (see MINECRAFT-566's ticket comments for the full back-and-forth this fixes):
 * <ol>
 *   <li>The first version picked a layer count from {@code EarthGenerator}'s own scalar
 *       {@code slope} magnitude. A real single-block step's slope rounded down to "flat", so both
 *       sides painted a full block and the whole 1-block cliff stayed untouched.</li>
 *   <li>The second version compared a column only against its immediate same-road neighbors: the
 *       column right at a step dropped to {@link LayerMath#MIN_LAYERS} (closing that single seam to
 *       1/16 block, correctly), but the very next column over -- whose own immediate neighbor is
 *       the already-lowered step column, not the original lower plateau -- saw no height
 *       difference to ITS immediate neighbor and jumped straight back to a full block, leaving a
 *       new, nearly-as-bad gap one column further out.</li>
 * </ol>
 *
 * <p>This version instead measures the distance, in columns, to the NEAREST same-road neighbor
 * that is actually lower (searching outward up to {@link #MAX_NEIGHBOR_DISTANCE} columns), and
 * decays linearly from {@link LayerMath#MIN_LAYERS} right at a step to {@link LayerMath#MAX_LAYERS}
 * {@value #MAX_NEIGHBOR_DISTANCE} columns away -- spreading the approach to a lower neighbor over
 * several columns instead of concentrating (and then losing) it at just one. A column with a
 * HIGHER same-road neighbor never needs to do anything differently regardless of distance: its own
 * top can reach at best {@code y+1}, which already meets the base of anything higher, so distance
 * to a higher neighbor is irrelevant and only distance to a LOWER one drives this.
 *
 * <p>Still not a complete fix for an arbitrarily long run: a lower feature more than
 * {@value #MAX_NEIGHBOR_DISTANCE} columns away is invisible to this heuristic, and nothing here
 * changes ground height itself (no cut/fill), so a step taller than one block between immediately
 * adjacent columns still leaves a residual, smaller gap at the point closest to it.
 */
public final class RoadSurfaceLayering {
    /** How many columns out this heuristic searches for, and decays its approach to, a lower same-road neighbor. */
    public static final int MAX_NEIGHBOR_DISTANCE = 5;

    private RoadSurfaceLayering() {}

    /**
     * Layer count for the road's top surface, given the distance (1 or more columns) to the
     * nearest same-road neighbor with a lower ground height, or {@code 0} if none was found within
     * {@link #MAX_NEIGHBOR_DISTANCE}.
     */
    public static int layersForNearestLowerNeighbor(int nearestLowerDistanceOrZeroIfNone) {
        if (nearestLowerDistanceOrZeroIfNone <= 0 || MAX_NEIGHBOR_DISTANCE <= 1) {
            return nearestLowerDistanceOrZeroIfNone <= 0 ? LayerMath.MAX_LAYERS : LayerMath.MIN_LAYERS;
        }
        int d = Math.min(nearestLowerDistanceOrZeroIfNone, MAX_NEIGHBOR_DISTANCE);
        int span = LayerMath.MAX_LAYERS - LayerMath.MIN_LAYERS;
        // Reaches exactly MAX_LAYERS at d == MAX_NEIGHBOR_DISTANCE (divide by D-1, not D).
        int layers = LayerMath.MIN_LAYERS + span * (d - 1) / (MAX_NEIGHBOR_DISTANCE - 1);
        return Math.max(LayerMath.MIN_LAYERS, Math.min(LayerMath.MAX_LAYERS, layers));
    }
}
