package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import io.github.brooswitminecraft.earthplatformroads.road.RoadSegment.CrossSection;
import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-566 comment 55484 (John's live-playtest styling ask): roads widened to roughly double
 * their previous paved width, a thin yellow-terracotta centerline for classes that opt in, and a
 * narrow gravel shoulder outside the paved edge. Pure geometry -- no Minecraft bootstrap needed, so
 * this runs in a plain (non-modded) JUnit JVM, same as {@code RoadSegmentTest}.
 */
class RoadCrossSectionTest {
    private static final MercatorGrid GRID = new MercatorGrid(1.0 / Math.cos(Math.toRadians(37.724)));

    @Test
    void everyClassIsRoughlyDoubleItsOriginalPavedWidth() {
        // Original (pre-55484) defaults, for comparison: MOTORWAY 5.0, SECONDARY 3.0, SERVICE 1.5.
        assertEquals(10.0, RoadClass.MOTORWAY.defaultPavedHalfWidthBlocks);
        assertEquals(6.0, RoadClass.SECONDARY.defaultPavedHalfWidthBlocks);
        assertEquals(3.0, RoadClass.SERVICE.defaultPavedHalfWidthBlocks);
    }

    @Test
    void serviceRoadsAreTheOneClassWithoutACenterline() {
        assertFalse(RoadClass.SERVICE.hasCenterline, "Expected SERVICE (narrow, path-like) to stay unstriped");
        for (RoadClass roadClass : RoadClass.values()) {
            if (roadClass != RoadClass.SERVICE) {
                assertTrue(roadClass.hasCenterline, "Expected every class other than SERVICE to have a centerline, got false for " + roadClass);
            }
        }
    }

    private static RoadSegment secondaryRoad(GeoPoint a, GeoPoint b) {
        return new RoadSegment(1L, "test road", RoadClass.SECONDARY, new GeoPoint[] {a, b}, new long[0], RoadClass.SECONDARY.defaultPavedHalfWidthBlocks, false, false, 0);
    }

    @Test
    void theCrossSectionGoesCenterlineThenPavedThenShoulderThenOffRoadMovingAwayFromTheCenterline() {
        GeoPoint a = new GeoPoint(37.7208, -119.638), b = new GeoPoint(37.7206, -119.636);
        RoadSegment segment = secondaryRoad(a, b);
        // a's own column center need not land exactly on the line (endpoint `a` can be anywhere
        // within its column, not necessarily its center) -- search a tiny neighborhood for the
        // actual closest column, the same rounding slack RoadSegmentTest's own boundary test uses.
        int ax = (int) Math.round(GRID.blockX(a)), az = (int) Math.round(GRID.blockZ(a));
        int bestX = ax, bestZ = az;
        double bestDistance = segment.distanceBlocks(GRID, ax, az);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                double distance = segment.distanceBlocks(GRID, ax + dx, az + dz);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestX = ax + dx;
                    bestZ = az + dz;
                }
            }
        }
        ax = bestX;
        az = bestZ;
        // Perpendicular-ish probe (same style as RoadSegmentTest's own boundary test): walk due
        // north from the centerline point, recording the zone at each step. This is the exact
        // self-adjusting style RoadGenerationTest's theRoadDoesNotWidenPastItsHalfWidth already
        // uses against real generated data -- here against pure synthetic geometry instead.
        CrossSection previous = segment.crossSectionAt(GRID, ax, az);
        assertEquals(CrossSection.CENTERLINE, previous,
                "Expected the actual closest column (distance " + bestDistance + ") to be CENTERLINE");
        boolean sawPaved = false, sawShoulder = false, sawOffRoad = false;
        for (int dz = 1; dz <= 20; dz++) {
            CrossSection current = segment.crossSectionAt(GRID, ax, az + dz);
            // Monotonic: once off-road, never back to a road zone; once at a wider zone, never back
            // to a narrower one, moving steadily away from the centerline.
            assertTrue(zoneOrder(current) >= zoneOrder(previous),
                    "Expected zones to only widen moving away from the centerline, got " + previous + " then " + current + " at dz=" + dz);
            if (current == CrossSection.PAVED) sawPaved = true;
            if (current == CrossSection.SHOULDER) sawShoulder = true;
            if (current == CrossSection.OFF_ROAD) sawOffRoad = true;
            previous = current;
        }
        assertTrue(sawPaved, "Expected to pass through PAVED moving away from the centerline");
        assertTrue(sawShoulder, "Expected to pass through SHOULDER moving away from the centerline");
        assertTrue(sawOffRoad, "Expected to eventually leave the road (OFF_ROAD) moving away from the centerline");
    }

    private static int zoneOrder(CrossSection zone) {
        return switch (zone) {
            case CENTERLINE -> 0;
            case PAVED -> 1;
            case SHOULDER -> 2;
            case OFF_ROAD -> 3;
        };
    }

    @Test
    void aServiceRoadNeverReturnsCenterlineRegardlessOfDistance() {
        GeoPoint a = new GeoPoint(37.7208, -119.638), b = new GeoPoint(37.7206, -119.636);
        RoadSegment service = new RoadSegment(1L, "driveway", RoadClass.SERVICE, new GeoPoint[] {a, b}, new long[0],
                RoadClass.SERVICE.defaultPavedHalfWidthBlocks, false, false, 0);
        int ax = (int) GRID.blockX(a), az = (int) GRID.blockZ(a);
        assertNotEquals(CrossSection.CENTERLINE, service.crossSectionAt(GRID, ax, az),
                "Expected even the exact centerline column of a SERVICE road to never be CENTERLINE");
        assertEquals(CrossSection.PAVED, service.crossSectionAt(GRID, ax, az), "Expected it to read as ordinary PAVED instead");
    }

    /**
     * "No centerline off-road" (John's named acceptance requirement): the centerline zone must
     * never extend past the paved surface, including right at a sharp bend where the nearest-
     * segment selection changes -- not merely on a straight stretch. Three points with a sharp turn
     * (not collinear), checked at and immediately around the bend's own vertex.
     */
    @Test
    void theCenterlineNeverExtendsPastThePavedSurfaceEvenAtASharpBend() {
        GeoPoint start = new GeoPoint(37.7200, -119.6400);
        GeoPoint bend = new GeoPoint(37.7206, -119.6380);
        GeoPoint end = new GeoPoint(37.7206, -119.6360); // turns ~90 degrees at `bend`
        RoadSegment segment = new RoadSegment(1L, "bent road", RoadClass.SECONDARY,
                new GeoPoint[] {start, bend, end}, new long[0], RoadClass.SECONDARY.defaultPavedHalfWidthBlocks, false, false, 0);
        int bendX = (int) GRID.blockX(bend), bendZ = (int) GRID.blockZ(bend);
        int checked = 0;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                CrossSection zone = segment.crossSectionAt(GRID, bendX + dx, bendZ + dz);
                checked++;
                if (zone == CrossSection.CENTERLINE) {
                    double distance = segment.distanceBlocks(GRID, bendX + dx, bendZ + dz);
                    assertTrue(distance <= segment.pavedHalfWidthBlocks(),
                            "A CENTERLINE column at (" + (bendX + dx) + "," + (bendZ + dz) + ") must stay within the paved surface, got distance " + distance);
                }
            }
        }
        assertTrue(checked > 0);
    }

    @Test
    void layeredGravelHasNoFallingBlockPhysics() {
        // LayeredBlock extends plain Block, not FallingBlock -- confirmed structurally here so a
        // future change to the base class hierarchy would actually be caught, not just asserted in
        // a comment. See LayeredSlopesCompat's own class doc for the full reasoning.
        assertFalse(net.minecraft.world.level.block.FallingBlock.class.isAssignableFrom(
                io.github.brooswitminecraft.layeredslopes.LayeredBlock.class),
                "Expected LayeredBlock (and therefore LAYERED_GRAVEL) to never have falling-block physics");
    }
}
