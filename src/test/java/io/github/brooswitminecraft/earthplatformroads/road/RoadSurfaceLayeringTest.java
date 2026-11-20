package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.brooswitminecraft.layeredslopes.LayerMath;
import org.junit.jupiter.api.Test;

/** Pure arithmetic, no Minecraft bootstrap: runs in a plain JUnit JVM. */
class RoadSurfaceLayeringTest {
    @Test
    void noLowerNeighborFoundIsAFullBlock() {
        assertEquals(LayerMath.MAX_LAYERS, RoadSurfaceLayering.layersForNearestLowerNeighbor(0));
    }

    @Test
    void negativeDistanceIsTreatedAsNoneFound() {
        assertEquals(LayerMath.MAX_LAYERS, RoadSurfaceLayering.layersForNearestLowerNeighbor(-1));
    }

    @Test
    void rightAtTheStepIsTheMinimumLayer() {
        assertEquals(LayerMath.MIN_LAYERS, RoadSurfaceLayering.layersForNearestLowerNeighbor(1));
    }

    @Test
    void layersIncreaseMonotonicallyWithDistance() {
        int prev = RoadSurfaceLayering.layersForNearestLowerNeighbor(1);
        for (int d = 2; d <= RoadSurfaceLayering.MAX_NEIGHBOR_DISTANCE; d++) {
            int layers = RoadSurfaceLayering.layersForNearestLowerNeighbor(d);
            assertTrue(layers >= prev, "Expected layers to not decrease as distance " + d + " grows from the step");
            prev = layers;
        }
    }

    @Test
    void atOrBeyondTheMaxDistanceIsAFullBlock() {
        assertEquals(LayerMath.MAX_LAYERS, RoadSurfaceLayering.layersForNearestLowerNeighbor(RoadSurfaceLayering.MAX_NEIGHBOR_DISTANCE));
        assertEquals(LayerMath.MAX_LAYERS, RoadSurfaceLayering.layersForNearestLowerNeighbor(RoadSurfaceLayering.MAX_NEIGHBOR_DISTANCE + 5));
    }

    @Test
    void neverGoesOutsideLayerMathsValidRange() {
        for (int d = -5; d <= 50; d++) {
            int layers = RoadSurfaceLayering.layersForNearestLowerNeighbor(d);
            assertTrue(layers >= LayerMath.MIN_LAYERS && layers <= LayerMath.MAX_LAYERS,
                    "layersForNearestLowerNeighbor(" + d + ") = " + layers + " is out of LayerMath's valid range");
        }
    }
}
