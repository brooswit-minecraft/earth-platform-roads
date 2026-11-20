package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link RoadNetwork#parseWay} directly against constructed way JSON, independent of the
 * singleton's bundled-resource loading -- a {@code highway=living_street} way (the class
 * MINECRAFT-566's first pass omitted, caught in review before merge) is exactly the kind of mapped
 * vehicular way that must load correctly without needing a real pinned fixture to prove it.
 */
class RoadNetworkLoaderTest {
    private static JsonObject way(String highway, String lanes) {
        String json = """
                {
                  "way_id": 1,
                  "way_tags": {"highway": "%s", "name": "test way"%s},
                  "centerline": [{"lat": 37.72, "lon": -119.64}, {"lat": 37.721, "lon": -119.641}]
                }
                """.formatted(highway, lanes == null ? "" : ", \"lanes\": \"" + lanes + "\"");
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void livingStreetLoadsAsAVehicularWayNotARejectedFootTrail() {
        RoadSegment segment = RoadNetwork.parseWay(way("living_street", null));
        assertEquals(RoadClass.LIVING_STREET, segment.roadClass());
    }

    @Test
    void genericRoadLoadsAsAVehicularWay() {
        RoadSegment segment = RoadNetwork.parseWay(way("road", null));
        assertEquals(RoadClass.ROAD, segment.roadClass());
    }

    @Test
    void footwayIsRejectedAtLoadTime() {
        assertThrows(IllegalStateException.class, () -> RoadNetwork.parseWay(way("footway", null)));
    }

    @Test
    void laneCountDrivesHalfWidthOverTheClassDefault() {
        RoadSegment twoLane = RoadNetwork.parseWay(way("secondary", "2"));
        RoadSegment fourLane = RoadNetwork.parseWay(way("secondary", "4"));
        assertTrue(fourLane.halfWidthBlocks() > twoLane.halfWidthBlocks(),
                "A four-lane way must be wider than a two-lane way of the same class");
    }

    @Test
    void bridgeAndTunnelDefaultToFalseWhenAbsent() {
        RoadSegment segment = RoadNetwork.parseWay(way("secondary", null));
        assertFalse(segment.bridge());
        assertFalse(segment.tunnel());
    }
}
