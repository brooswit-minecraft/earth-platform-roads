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

    /**
     * MINECRAFT-566 comment 55518: the same real way, independently parsed from two different
     * tile/cache files, yields two {@code RoadSegment} instances with identical VALUES but distinct
     * backing {@code centerline} arrays -- plain record {@code .equals} wrongly treats them as
     * different roads (array fields compare by reference, not content). {@code isSameRoadAs} must
     * match on the real way's own stable, positive OSM {@code wayId} instead.
     */
    @Test
    void isSameRoadAsMatchesByWayIdEvenWithDistinctBackingArrays() {
        GeoPoint a = new GeoPoint(37.7208, -119.638), b = new GeoPoint(37.7206, -119.636);
        // Two SEPARATE GeoPoint[] array instances with equal content, as two independent tile
        // parses of the same way would each produce -- never the same array object.
        RoadSegment westCopy = new RoadSegment(42L, "same way", RoadClass.SECONDARY,
                new GeoPoint[] {a, b}, new long[0], 6.0, false, false, 0);
        RoadSegment eastCopy = new RoadSegment(42L, "same way", RoadClass.SECONDARY,
                new GeoPoint[] {a, b}, new long[0], 6.0, false, false, 0);
        assertFalse(westCopy.equals(eastCopy), "Sanity: plain record equals must still reject these (the real bug) -- "
                + "if a future JDK or record change makes array fields compare by content, isSameRoadAs's own wayId-based fix is no longer needed, but should stay harmless");
        assertTrue(westCopy.isSameRoadAs(eastCopy), "Expected the same stable positive wayId to be recognized as the same road despite distinct backing arrays");
        assertTrue(eastCopy.isSameRoadAs(westCopy), "Expected isSameRoadAs to be symmetric");
    }

    /** The unrelated-way control John's own comment asked for: a genuinely different wayId must never be merged. */
    @Test
    void isSameRoadAsRejectsADifferentWayIdEvenWithIdenticalGeometry() {
        GeoPoint a = new GeoPoint(37.7208, -119.638), b = new GeoPoint(37.7206, -119.636);
        RoadSegment wayA = new RoadSegment(42L, "way A", RoadClass.SECONDARY, new GeoPoint[] {a, b}, new long[0], 6.0, false, false, 0);
        RoadSegment wayB = new RoadSegment(99L, "way B", RoadClass.SECONDARY, new GeoPoint[] {a, b}, new long[0], 6.0, false, false, 0);
        assertFalse(wayA.isSameRoadAs(wayB), "Expected two genuinely different real way IDs to never be merged, even with identical geometry");
    }

    /**
     * ID-less (wayId &lt;= 0) synthetic/test roads -- the minimal constructor's own convention --
     * must stay DISTINCT from each other even though they all share {@code wayId == 0}; only a real,
     * positive, matching wayId on BOTH sides should merge.
     */
    @Test
    void isSameRoadAsLeavesDistinctIdLessTestRoadsDistinct() {
        GeoPoint a = new GeoPoint(37.7208, -119.638), b = new GeoPoint(37.7206, -119.636);
        RoadSegment testRoadOne = new RoadSegment("test road one", new GeoPoint[] {a, b}, 3.0);
        RoadSegment testRoadTwo = new RoadSegment("test road two", new GeoPoint[] {a, b}, 3.0);
        assertFalse(testRoadOne.isSameRoadAs(testRoadTwo), "Expected two distinct ID-less test roads to never be merged just because both have wayId 0");
        assertTrue(testRoadOne.isSameRoadAs(testRoadOne), "Expected a road to always be the same road as itself");
    }
}
