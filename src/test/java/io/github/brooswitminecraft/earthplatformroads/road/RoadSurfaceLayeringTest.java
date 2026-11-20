package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.brooswitminecraft.layeredslopes.LayerMath;
import org.junit.jupiter.api.Test;

/** Pure arithmetic, no Minecraft bootstrap: runs in a plain JUnit JVM. */
class RoadSurfaceLayeringTest {
    @Test
    void noNeighborsAtAllIsAFullBlock() {
        assertEquals(LayerMath.MAX_LAYERS, RoadSurfaceLayering.layersForNeighbors(100, new int[0]));
    }

    @Test
    void allNeighborsAtTheSameHeightIsAFullBlock() {
        assertEquals(LayerMath.MAX_LAYERS, RoadSurfaceLayering.layersForNeighbors(100, new int[] {100, 100, 100, 100}));
    }

    @Test
    void theLowSideOfAOneBlockStepStaysFull() {
        // y=100 with a neighbor at 101: this column's top (y + MAX_LAYERS/16 = 101) must meet the
        // base of the higher neighbor's own cell (also 101) -- this is the regression MINECRAFT-566's
        // first fix attempt got wrong: a slope-magnitude heuristic rounded this exact case to "flat".
        assertEquals(LayerMath.MAX_LAYERS, RoadSurfaceLayering.layersForNeighbors(100, new int[] {100, 101}));
    }

    @Test
    void theHighSideOfAOneBlockStepDropsToTheMinimumLayer() {
        // y=101 with a neighbor at 100: this column's top must sit as low as possible in its own
        // cell (101 + MIN_LAYERS/16) to approach the lower neighbor's top (100 + MAX_LAYERS/16 = 101).
        assertEquals(LayerMath.MIN_LAYERS, RoadSurfaceLayering.layersForNeighbors(101, new int[] {100, 101}));
    }

    @Test
    void aSaddleWithBothAHigherAndALowerNeighborGetsAMidpointCompromise() {
        int layers = RoadSurfaceLayering.layersForNeighbors(100, new int[] {99, 101});
        assertEquals((LayerMath.MAX_LAYERS + LayerMath.MIN_LAYERS) / 2, layers);
    }

    @Test
    void aMultiBlockStepStillPicksTheExtremeOnEachSide() {
        // Not the common case in practice (see RoadSurfaceLayering's class doc), but must not throw
        // or pick something outside LayerMath's valid range.
        assertEquals(LayerMath.MAX_LAYERS, RoadSurfaceLayering.layersForNeighbors(100, new int[] {103}));
        assertEquals(LayerMath.MIN_LAYERS, RoadSurfaceLayering.layersForNeighbors(103, new int[] {100}));
    }
}
