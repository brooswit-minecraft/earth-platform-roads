package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.GeoPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure geometry/metadata: no Minecraft bootstrap, so this runs in a plain (non-modded) JUnit JVM too. */
class RoadSegmentJunctionTest {
    private static RoadSegment way(long wayId, long... nodeIds) {
        GeoPoint[] centerline = new GeoPoint[nodeIds.length];
        for (int i = 0; i < nodeIds.length; i++) centerline[i] = new GeoPoint(37.72 + i * 0.0001, -119.64 + i * 0.0001);
        return new RoadSegment(wayId, "way " + wayId, RoadClass.SECONDARY, centerline, nodeIds, 3.0, false, false, 0);
    }

    @Test
    void twoWaysSharingANodeAreAJunction() {
        RoadSegment a = way(1, 10, 11, 12);
        RoadSegment b = way(2, 12, 13, 14);
        assertTrue(a.sharesJunctionWith(b));
        assertEquals(List.of(12L), RoadSegment.sharedNodeIds(a, b));
    }

    @Test
    void twoWaysWithNoSharedNodeAreNotAJunctionEvenIfGeometryCrosses() {
        // Same lat/lon as `way`, but note this test only exercises node-ID sharing, not geometric
        // crossing: a real crossing without a shared node (a bridge/tunnel grade separation, or two
        // unrelated ways that merely overlap on the map) must not be reported as a junction.
        RoadSegment a = way(1, 10, 11, 12);
        RoadSegment b = way(2, 20, 21, 22);
        assertFalse(a.sharesJunctionWith(b));
        assertTrue(RoadSegment.sharedNodeIds(a, b).isEmpty());
    }

    @Test
    void segmentsWithoutTrackedNodeIdsOptOutOfJunctionDetection() {
        RoadSegment legacy = new RoadSegment("legacy", new GeoPoint[] {new GeoPoint(37.72, -119.64), new GeoPoint(37.721, -119.641)}, 3.0);
        RoadSegment tracked = way(2, 1, 2, 3);
        assertFalse(legacy.sharesJunctionWith(tracked));
        assertFalse(tracked.sharesJunctionWith(legacy));
    }

    @Test
    void boundingBoxCoversEveryCenterlinePoint() {
        RoadSegment a = way(1, 1, 2, 3);
        for (GeoPoint p : a.centerline()) {
            assertTrue(p.latitude() >= a.minLat() && p.latitude() <= a.maxLat());
            assertTrue(p.longitude() >= a.minLon() && p.longitude() <= a.maxLon());
        }
    }

    @Test
    void roadClassRejectsNonVehicularHighwayValuesAtTheEnumLevel() {
        assertEquals(null, RoadClass.fromOsmHighway("footway"));
        assertEquals(null, RoadClass.fromOsmHighway("path"));
        assertEquals(null, RoadClass.fromOsmHighway("cycleway"));
        assertEquals(RoadClass.SECONDARY, RoadClass.fromOsmHighway("secondary"));
        assertEquals(RoadClass.SERVICE, RoadClass.fromOsmHighway("service"));
    }

    @Test
    void roadClassAcceptsEveryStandardOsmVehicularHighwayValue() {
        // Every value OSM's own wiki documents as a vehicular "Roads" highway=* value (motorway
        // down through service, plus living_street and the generic/unclassified "road") must be
        // accepted here, not just the handful MINECRAFT-566's first pass happened to list: a real
        // pinned extract can legitimately use any of these, and rejecting one at load time would
        // silently drop a mapped vehicular way as if it were a foot trail.
        for (String highway : List.of(
                "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
                "living_street", "motorway_link", "trunk_link", "primary_link", "secondary_link",
                "tertiary_link", "service", "road")) {
            assertTrue(RoadClass.fromOsmHighway(highway) != null, "Expected highway=" + highway + " to map to a vehicular RoadClass");
        }
    }
}
