package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.brooswitminecraft.layeredslopes.LayerMath;
import org.junit.jupiter.api.Test;

/** Pure arithmetic, no Minecraft bootstrap: runs in a plain JUnit JVM. */
class RoadSurfaceLayeringTest {
    @Test
    void flatGroundIsAFullBlock() {
        assertEquals(LayerMath.MAX_LAYERS, RoadSurfaceLayering.layersForSlope(0));
    }

    @Test
    void negativeSlopeIsTreatedAsFlat() {
        // EarthGenerator's slope is a non-negative magnitude in practice, but this must not throw
        // or under/overflow if ever called with a negative value.
        assertEquals(LayerMath.MAX_LAYERS, RoadSurfaceLayering.layersForSlope(-1));
    }

    @Test
    void layersDecreaseAsSlopeIncreases() {
        int flat = RoadSurfaceLayering.layersForSlope(0);
        int gentle = RoadSurfaceLayering.layersForSlope(3);
        int steep = RoadSurfaceLayering.layersForSlope(12);
        assertTrue(gentle < flat, "A gentle slope must use fewer layers than flat ground");
        assertTrue(steep < gentle, "A steeper slope must use fewer layers than a gentler one");
    }

    @Test
    void neverGoesBelowTheMinimumLayer() {
        assertEquals(LayerMath.MIN_LAYERS, RoadSurfaceLayering.layersForSlope(1000));
    }

    @Test
    void neverExceedsTheValidLayerRange() {
        for (int slope = -5; slope <= 200; slope++) {
            int layers = RoadSurfaceLayering.layersForSlope(slope);
            assertTrue(layers >= LayerMath.MIN_LAYERS && layers <= LayerMath.MAX_LAYERS,
                    "layersForSlope(" + slope + ") = " + layers + " is out of LayerMath's valid range");
        }
    }
}
