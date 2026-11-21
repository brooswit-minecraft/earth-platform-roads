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

    /**
     * MINECRAFT-566 comment 55513's own deterministic cross-slope acceptance case, run against
     * {@link RoadDeck#sharedHeight} (the pure geometry/sampling core {@code RoadDeck.deckY}
     * delegates to) rather than a real generator -- lets this run in the plain (non-modded) JUnit
     * JVM, same as the rest of this file. A straight 12-block-wide paved road running along Z, with
     * a real cross/side slope (natural height rises with X, constant along Z): the shared height at
     * every station must be the max across the road's OWN paved width (never the shoulder's own
     * higher ground just outside it), giving a perfectly flat transverse deck -- John's own named
     * "twelve 75s," not the ramped, still-stepped result ({@code [70,71,...,76,76,...]}) the earlier
     * isotropic-box version produced (independently reproduced on a real compiled build, comment
     * 55514).
     */
    @Test
    void sharedHeightIsFlatAcrossARealCrossSlopeNotRampedByAnIsotropicWindow() {
        MercatorGrid grid = GRID;
        // A perfectly vertical (along Z) centerline at block X=6.0, via round-tripping through the
        // grid's own inverse projection -- exact in block space regardless of this grid's scale.
        GeoPoint a = grid.geographic(6.0, 0.0), b = grid.geographic(6.0, 100.0);
        // pavedHalfWidthBlocks=5.5 makes the paved footprint exactly columns x=0..11 (centers 0.5..11.5,
        // symmetric around X=6.0, max distance 5.5), and x=-1/x=12 (distance 6.5) exactly the shoulder.
        RoadSegment segment = new RoadSegment(1L, "cross-slope test road", RoadClass.SECONDARY,
                new GeoPoint[] {a, b}, new long[0], 5.5, false, false, 0);

        int worldZ = 50; // well clear of either endpoint, so projection never clamps
        java.util.function.BiFunction<Integer, Integer, Integer> naturalHeight = (x, z) -> 64 + x;
        java.util.function.BiPredicate<Integer, Integer> sameRoad =
                (x, z) -> segment.crossSectionAt(grid, x, z) != CrossSection.OFF_ROAD;

        int[] actual = new int[12];
        for (int x = 0; x <= 11; x++) {
            actual[x] = RoadDeck.sharedHeight(segment, grid, x, worldZ, naturalHeight, sameRoad);
        }
        for (int x = 0; x <= 11; x++) {
            assertEquals(75, actual[x], "Expected a flat shared height of 75 (the paved width's own max, at x=11) "
                    + "at every column across this cross-slope station, got " + java.util.Arrays.toString(actual) + " at x=" + x);
        }

        // The uphill shoulder (x=12, natural height 76) must never pull the shared height up past 75.
        assertEquals(RoadSegment.CrossSection.SHOULDER, segment.crossSectionAt(grid, 12, worldZ), "Sanity: x=12 must actually be the shoulder");
        assertEquals(RoadSegment.CrossSection.SHOULDER, segment.crossSectionAt(grid, -1, worldZ), "Sanity: x=-1 must actually be the shoulder");
    }

    /**
     * MINECRAFT-566 comment 55513's explicit ask ("add... a diagonal... case before calling the
     * deck ready"): the same cross-slope property, on a road that is NOT axis-aligned, where the
     * sampling direction is a genuine diagonal unit vector rather than a clean (1,0)/(0,1). Exact
     * equality is not asserted (diagonal sampling rounds to the nearest block, so two adjacent
     * stations can legitimately land on a slightly different set of integer samples) -- instead this
     * checks the two properties that matter: the shared height is never below natural ground, and it
     * changes gradually along the road's own length (no sudden, un-physical jump), for a real
     * diagonal cross-slope.
     */
    @Test
    void sharedHeightStaysBoundedAndChangesGraduallyAlongADiagonalRoad() {
        MercatorGrid grid = GRID;
        GeoPoint a = grid.geographic(0.0, 0.0), b = grid.geographic(200.0, 200.0); // a genuine 45-degree diagonal
        RoadSegment segment = new RoadSegment(1L, "diagonal test road", RoadClass.SECONDARY,
                new GeoPoint[] {a, b}, new long[0], 4.0, false, false, 0);
        // A cross-slope transverse to this diagonal: (x - z) is constant ALONG the (1,1) direction
        // and varies linearly ACROSS it, the diagonal analogue of the straight test's "height = 64+x".
        java.util.function.BiFunction<Integer, Integer, Integer> naturalHeight = (x, z) -> 64 + Math.floorDiv(x - z, 2);
        java.util.function.BiPredicate<Integer, Integer> sameRoad =
                (x, z) -> segment.crossSectionAt(grid, x, z) != CrossSection.OFF_ROAD;

        Integer previous = null;
        boolean checkedAny = false;
        for (int t = 50; t <= 150; t++) {
            if (segment.crossSectionAt(grid, t, t) == CrossSection.OFF_ROAD) continue;
            checkedAny = true;
            int shared = RoadDeck.sharedHeight(segment, grid, t, t, naturalHeight, sameRoad);
            int natural = naturalHeight.apply(t, t);
            assertTrue(shared >= natural, "Shared height must never be below natural ground on a diagonal road, at t=" + t);
            if (previous != null) {
                assertTrue(Math.abs(shared - previous) <= 1,
                        "Expected the shared height to change gradually along a smooth diagonal grade, got a jump from " + previous + " to " + shared + " at t=" + t);
            }
            previous = shared;
        }
        assertTrue(checkedAny, "Expected at least one covered station along the diagonal centerline itself");
    }

    /**
     * MINECRAFT-566 comment 55513's explicit ask ("add... a... cube-seam case before calling the
     * deck ready"): the SAME straight cross-slope fixture as
     * {@code sharedHeightIsFlatAcrossARealCrossSlopeNotRampedByAnIsotropicWindow}, repositioned so
     * its 12-column paved width straddles a real 16-block cube boundary (columns 10-21, crossing
     * x=15/16) -- {@code RoadDeck.sharedHeight} is a pure function of coordinates with no concept of
     * a cube at all, so this is mostly a structural regression guard confirming that stays true
     * (the flat result must not care which side of x=15/16 a sample happens to fall on).
     */
    @Test
    void sharedHeightStaysFlatAcrossARealCubeBoundary() {
        MercatorGrid grid = GRID;
        GeoPoint a = grid.geographic(16.0, 0.0), b = grid.geographic(16.0, 100.0);
        RoadSegment segment = new RoadSegment(1L, "cube-seam test road", RoadClass.SECONDARY,
                new GeoPoint[] {a, b}, new long[0], 5.5, false, false, 0);
        int worldZ = 50;
        java.util.function.BiFunction<Integer, Integer, Integer> naturalHeight = (x, z) -> 64 + (x - 10);
        java.util.function.BiPredicate<Integer, Integer> sameRoad =
                (x, z) -> segment.crossSectionAt(grid, x, z) != CrossSection.OFF_ROAD;

        boolean sawBothSidesOfTheBoundary = false;
        int expected = 64 + (21 - 10); // the paved width's own max, at x=21
        for (int x = 10; x <= 21; x++) {
            int shared = RoadDeck.sharedHeight(segment, grid, x, worldZ, naturalHeight, sameRoad);
            assertEquals(expected, shared, "Expected a flat shared height at every column straddling the cube boundary, got a mismatch at x=" + x);
            if ((x >> 4) != (10 >> 4)) sawBothSidesOfTheBoundary = true;
        }
        assertTrue(sawBothSidesOfTheBoundary, "Sanity: expected this fixture's own width to actually straddle a cube boundary (x=15/16)");
    }

    /**
     * MINECRAFT-566 comment 55516 (independent compiled-build finding): on John's own cross-slope
     * fixture, the natural-high column (x=11, already AT the shared height -- nothing to raise) must
     * still be flagged {@code crossSlopeDetected}, so {@code RoadDeck.buildSupportAndDeck} knows to
     * repaint its cap too, rather than leaving it as whatever partial layered material
     * {@code surface()} originally painted there (the real bug: raised neighbors got a full cap at
     * the same Y while the natural-high column kept a near-decayed layer, both at Y=75). A station
     * with no actual height variation across its own width must NOT be flagged, so a flat/uniform
     * road is never touched at all.
     */
    @Test
    void stationFlagsCrossSlopeOnlyWhenTheWidthActuallyVaries() {
        MercatorGrid grid = GRID;
        GeoPoint a = grid.geographic(6.0, 0.0), b = grid.geographic(6.0, 100.0);
        RoadSegment slopedRoad = new RoadSegment(1L, "cross-slope test road", RoadClass.SECONDARY,
                new GeoPoint[] {a, b}, new long[0], 5.5, false, false, 0);
        int worldZ = 50;
        java.util.function.BiFunction<Integer, Integer, Integer> slopedHeight = (x, z) -> 64 + x;
        java.util.function.BiPredicate<Integer, Integer> sameRoad =
                (x, z) -> slopedRoad.crossSectionAt(grid, x, z) != CrossSection.OFF_ROAD;

        // x=11 is the natural-high column of this exact fixture -- its OWN height already equals
        // the shared max, yet the station it belongs to is still genuinely cross-sloped.
        var highColumn = RoadDeck.computeStation(slopedRoad, grid, 11, worldZ, slopedHeight, sameRoad);
        assertEquals(75, highColumn.height());
        assertEquals(75, slopedHeight.apply(11, worldZ), "Sanity: x=11 must actually already be at the shared height");
        assertTrue(highColumn.crossSlopeDetected(), "Expected the natural-high column to still be flagged cross-sloped, "
                + "so its cap gets repainted to match its raised neighbors rather than keeping a stale partial layer");

        // A perfectly flat road (same construction, but every sampled column shares one height) must
        // never be flagged -- the common case, which must stay a complete no-op.
        RoadSegment flatRoad = new RoadSegment(1L, "flat test road", RoadClass.SECONDARY,
                new GeoPoint[] {a, b}, new long[0], 5.5, false, false, 0);
        java.util.function.BiFunction<Integer, Integer, Integer> flatHeight = (x, z) -> 70;
        java.util.function.BiPredicate<Integer, Integer> sameFlatRoad =
                (x, z) -> flatRoad.crossSectionAt(grid, x, z) != CrossSection.OFF_ROAD;
        var flatColumn = RoadDeck.computeStation(flatRoad, grid, 5, worldZ, flatHeight, sameFlatRoad);
        assertEquals(70, flatColumn.height());
        assertTrue(!flatColumn.crossSlopeDetected(), "Expected a perfectly flat road to never be flagged cross-sloped");
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
