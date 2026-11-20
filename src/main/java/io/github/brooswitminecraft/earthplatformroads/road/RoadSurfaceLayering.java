package io.github.brooswitminecraft.earthplatformroads.road;

import io.github.brooswitminecraft.layeredslopes.LayerMath;

/**
 * Pure arithmetic mapping a column's local terrain steepness to a {@link LayerMath} layer count for
 * the road's top surface, free of Minecraft/Earth Platform types so it is unit-testable without
 * bootstrapping the game.
 *
 * <p>{@code slope} is the raw per-column value {@code EarthGenerator.surface(int,int,int,int,int)}
 * already receives (see that method's own doc comment: "Summed block steps over two blocks each
 * way, as a rough rise per run" -- {@code steep = slope / 2.4}). On a flat column ({@code slope ==
 * 0}) this returns {@link LayerMath#MAX_LAYERS} (a full block, matching MINECRAFT-564's existing
 * flat-ground behaviour exactly). On an inclined column, layers decrease as slope increases, so the
 * road surface reads as a shallow sub-block step rather than either a full-height block repeated at
 * every differing ground height (a whole-block stair) or a single flat plane that ignores the grade.
 *
 * <p>This is a first implementation of the ticket's "sub-block Layered Slopes height increments"
 * requirement: the exact divisor below is a starting heuristic, not a value verified in-game (no
 * build/playtest was run to confirm the resulting visual/collision seam looks or feels right across
 * a real inclined run) -- see the MINECRAFT-566 ticket comment recording that as an open gap.
 */
public final class RoadSurfaceLayering {
    /** Slope units per one fewer sixteenth-layer; tuned so a slope of ~1 (steep's {@code 2.4} threshold) reaches the midpoint. */
    private static final double SLOPE_UNITS_PER_LAYER = 3.0;

    private RoadSurfaceLayering() {}

    /** Layer count for the road's top surface at this column, clamped to {@link LayerMath}'s valid range. */
    public static int layersForSlope(int slope) {
        if (slope <= 0) return LayerMath.MAX_LAYERS;
        int reduction = (int) Math.round(slope / SLOPE_UNITS_PER_LAYER);
        int layers = LayerMath.MAX_LAYERS - reduction;
        return Math.max(LayerMath.MIN_LAYERS, Math.min(LayerMath.MAX_LAYERS, layers));
    }
}
