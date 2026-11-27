package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(fourLane.pavedHalfWidthBlocks() > twoLane.pavedHalfWidthBlocks(),
                "A four-lane way must be wider than a two-lane way of the same class");
    }

    @Test
    void bridgeAndTunnelDefaultToFalseWhenAbsent() {
        RoadSegment segment = RoadNetwork.parseWay(way("secondary", null));
        assertFalse(segment.bridge());
        assertFalse(segment.tunnel());
    }

    private static JsonObject wayWithExtraTags(String highway, String extraTagsJson) {
        String json = """
                {
                  "way_id": 1,
                  "way_tags": {"highway": "%s", "name": "test way"%s},
                  "centerline": [{"lat": 37.72, "lon": -119.64}, {"lat": 37.721, "lon": -119.641}]
                }
                """.formatted(highway, extraTagsJson == null ? "" : ", " + extraTagsJson);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** MINECRAFT-568: an explicit {@code surface} tag is carried onto the loaded {@link RoadSegment}, raw and unmapped. */
    @Test
    void explicitSurfaceTagIsCarriedOntoTheLoadedSegment() {
        RoadSegment segment = RoadNetwork.parseWay(wayWithExtraTags("secondary", "\"surface\": \"gravel\""));
        assertEquals("gravel", segment.surface());
    }

    /**
     * MINECRAFT-568: "without changing unknown/missing into a tag" -- a way with no {@code surface}
     * key at all (exactly what every region/tile file written before this ticket looks like) loads
     * with a {@code null} surface, not an invented placeholder value. This is also the legacy
     * cache/reopen round trip: an old cached tile JSON missing the field must still parse.
     */
    @Test
    void missingSurfaceTagLoadsAsNullNotAnInventedValue() {
        RoadSegment segment = RoadNetwork.parseWay(way("secondary", null));
        assertNull(segment.surface(), "A way with no surface tag at all must not have one invented for it");
        assertEquals(RoadSurfaceMaterial.PAVED, RoadSurfaceMaterial.fromOsmSurfaceTag(segment.surface()),
                "Missing surface must keep today's existing (paved) rendering behavior");
    }

    /** MINECRAFT-568: "include eligible motor tracks carefully" -- an ordinary track with no exclusion tag loads as a real vehicular way. */
    @Test
    void anOrdinaryMotorTrackLoadsAsAVehicularWay() {
        RoadSegment segment = RoadNetwork.parseWay(way("track", null));
        assertEquals(RoadClass.TRACK, segment.roadClass());
        assertNull(segment.surface(), "Expected no surface assumed just because this is a track");
    }

    /** "do not assume a track is dirt": no surface tag means PAVED (today's class-independent default), never an inferred unpaved material. */
    @Test
    void aMotorTrackWithNoSurfaceTagDefaultsToPavedNotDirt() {
        RoadSegment segment = RoadNetwork.parseWay(way("track", null));
        assertEquals(RoadSurfaceMaterial.PAVED, RoadSurfaceMaterial.fromOsmSurfaceTag(segment.surface()));
    }

    /** MINECRAFT-568: a track explicitly closed to motor vehicles is excluded entirely, exactly like a foot-only highway value. */
    @Test
    void aTrackExplicitlyClosedToMotorVehiclesIsExcludedEntirely() {
        RoadSegment segment = RoadNetwork.parseWay(wayWithExtraTags("track", "\"motor_vehicle\": \"no\""));
        assertNull(segment, "A motor_vehicle=no track must not be ingested as a vehicular way at all");
    }

    /** A non-"no" motor_vehicle value (e.g. explicit "yes", or anything else) does not exclude the track. */
    @Test
    void aTrackWithMotorVehicleYesIsStillIngested() {
        RoadSegment segment = RoadNetwork.parseWay(wayWithExtraTags("track", "\"motor_vehicle\": \"yes\""));
        assertEquals(RoadClass.TRACK, segment.roadClass());
    }

    /**
     * Round trip through the same JSON shape {@link RoadNetwork#parseRegionWays} reads a whole
     * region/tile file as -- exercises {@code toWayJson}/{@code parseWay} together, the actual
     * on-disk serialization path, not just {@code parseWay} on hand-written JSON.
     */
    @Test
    void explicitSurfaceSurvivesARoundTripThroughRegionJson() {
        RoadSegment original = RoadNetwork.parseWay(wayWithExtraTags("residential", "\"surface\": \"sand\""));
        JsonObject region = new JsonObject();
        region.addProperty("schema_version", RoadNetwork.SUPPORTED_SCHEMA_VERSION);
        com.google.gson.JsonArray ways = new com.google.gson.JsonArray();
        ways.add(original.toWayJson()); // package-private; this test lives in the same package
        region.add("ways", ways);
        java.util.List<RoadSegment> reloaded = RoadNetwork.parseRegionWays(region, "test-region");
        assertEquals(1, reloaded.size());
        assertEquals("sand", reloaded.get(0).surface());
    }

    /**
     * A region file written before MINECRAFT-568 (schema_version unchanged, no "surface" key in
     * way_tags at all) must still load without error and without a cache flush -- the whole point
     * of keeping this an optional field rather than bumping SUPPORTED_SCHEMA_VERSION.
     */
    @Test
    void aPreMinecraft568RegionFileWithNoSurfaceFieldStillLoadsCleanly() {
        String legacyRegionJson = """
                {
                  "schema_version": %d,
                  "ways": [
                    {
                      "way_id": 42,
                      "way_tags": {"highway": "secondary", "name": "legacy way"},
                      "centerline": [{"lat": 37.72, "lon": -119.64}, {"lat": 37.721, "lon": -119.641}]
                    }
                  ]
                }
                """.formatted(RoadNetwork.SUPPORTED_SCHEMA_VERSION);
        JsonObject root = JsonParser.parseString(legacyRegionJson).getAsJsonObject();
        java.util.List<RoadSegment> loaded = RoadNetwork.parseRegionWays(root, "legacy-region");
        assertEquals(1, loaded.size());
        assertNull(loaded.get(0).surface());
    }
}
