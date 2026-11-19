package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import org.junit.jupiter.api.Test;

/** Pure geometry: no Minecraft bootstrap, so this runs in a plain (non-modded) JUnit JVM too. */
class RoadSegmentTest {
    private static final MercatorGrid GRID = new MercatorGrid(1.0 / Math.cos(Math.toRadians(37.724)));

    @Test
    void aColumnOnTheCenterlineIsCovered() {
        GeoPoint a = new GeoPoint(37.7208, -119.638), b = new GeoPoint(37.7206, -119.636);
        RoadSegment segment = new RoadSegment("test road", new GeoPoint[] {a, b}, 3.0);
        int x = (int) GRID.blockX(a), z = (int) GRID.blockZ(a);
        assertTrue(segment.coversColumn(GRID, x, z));
    }

    @Test
    void aColumnFarFromTheCenterlineIsNotCovered() {
        GeoPoint a = new GeoPoint(37.7208, -119.638), b = new GeoPoint(37.7206, -119.636);
        RoadSegment segment = new RoadSegment("test road", new GeoPoint[] {a, b}, 3.0);
        int x = (int) GRID.blockX(a) + 500, z = (int) GRID.blockZ(a) + 500;
        assertFalse(segment.coversColumn(GRID, x, z));
    }

    @Test
    void theHalfWidthBoundaryIsExact() {
        GeoPoint a = new GeoPoint(37.7208, -119.638), b = new GeoPoint(37.7206, -119.636);
        RoadSegment segment = new RoadSegment("test road", new GeoPoint[] {a, b}, 3.0);
        double ax = GRID.blockX(a), az = GRID.blockZ(a);
        // Perpendicular offset at the segment's own endpoint, using the segment direction's normal.
        double dx = GRID.blockX(b) - ax, dz = GRID.blockZ(b) - az;
        double length = Math.hypot(dx, dz);
        double nx = -dz / length, nz = dx / length;
        // Offsets leave enough margin either side of the 3.0 boundary to absorb rounding to an
        // integer column and the 0.5-block recentring coversColumn itself applies.
        int justInside = (int) Math.round(ax + nx * 2.0), zi = (int) Math.round(az + nz * 2.0);
        int justOutside = (int) Math.round(ax + nx * 4.0), zo = (int) Math.round(az + nz * 4.0);
        assertTrue(segment.coversColumn(GRID, justInside, zi));
        assertFalse(segment.coversColumn(GRID, justOutside, zo));
    }
}
