package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure arithmetic/mapping, no Minecraft bootstrap needed -- same style as {@code RoadSurfaceLayering}'s
 * own test. MINECRAFT-568: "Map uses explicit tags first, class defaults only if missing" and "do not
 * assume a track is dirt."
 */
class RoadSurfaceMaterialTest {
    @Test
    void explicitPavedSurfaceTagsAllMapToPaved() {
        for (String tag : new String[] {"asphalt", "paved", "concrete", "paving_stones", "cobblestone"}) {
            assertEquals(RoadSurfaceMaterial.PAVED, RoadSurfaceMaterial.fromOsmSurfaceTag(tag), "surface=" + tag);
        }
    }

    @Test
    void explicitGravelOrCompactedSurfaceTagsMapToGravelCompacted() {
        for (String tag : new String[] {"gravel", "compacted", "fine_gravel", "pebblestone"}) {
            assertEquals(RoadSurfaceMaterial.GRAVEL_COMPACTED, RoadSurfaceMaterial.fromOsmSurfaceTag(tag), "surface=" + tag);
        }
    }

    @Test
    void explicitDirtOrGroundSurfaceTagsMapToDirtGround() {
        for (String tag : new String[] {"dirt", "ground", "earth", "unpaved"}) {
            assertEquals(RoadSurfaceMaterial.DIRT_GROUND, RoadSurfaceMaterial.fromOsmSurfaceTag(tag), "surface=" + tag);
        }
    }

    @Test
    void explicitSandSurfaceTagMapsToSand() {
        assertEquals(RoadSurfaceMaterial.SAND, RoadSurfaceMaterial.fromOsmSurfaceTag("sand"));
    }

    @Test
    void surfaceTagLookupIsCaseAndWhitespaceInsensitive() {
        assertEquals(RoadSurfaceMaterial.DIRT_GROUND, RoadSurfaceMaterial.fromOsmSurfaceTag("  Dirt  "));
        assertEquals(RoadSurfaceMaterial.SAND, RoadSurfaceMaterial.fromOsmSurfaceTag("SAND"));
    }

    @Test
    void missingSurfaceTagDefaultsToPavedTheExistingBehavior() {
        assertEquals(RoadSurfaceMaterial.PAVED, RoadSurfaceMaterial.fromOsmSurfaceTag(null));
    }

    @Test
    void unrecognizedSurfaceTagDefaultsToPavedRatherThanGuessing() {
        assertEquals(RoadSurfaceMaterial.PAVED, RoadSurfaceMaterial.fromOsmSurfaceTag("totally_invented_value"));
    }

    @Test
    void aMotorTrackWithNoSurfaceTagIsNotAssumedToBeDirt() {
        // "do not assume a track is dirt" -- a track's RoadClass never participates in this mapping
        // at all; absence of a surface tag means PAVED regardless of highway class.
        assertEquals(RoadSurfaceMaterial.PAVED, RoadSurfaceMaterial.fromOsmSurfaceTag(null));
    }

    @Test
    void onlyPavedKeepsTheCenterlineMarking() {
        assertTrue(RoadSurfaceMaterial.PAVED.hasCenterlineMarking());
        assertFalse(RoadSurfaceMaterial.GRAVEL_COMPACTED.hasCenterlineMarking());
        assertFalse(RoadSurfaceMaterial.DIRT_GROUND.hasCenterlineMarking());
        assertFalse(RoadSurfaceMaterial.SAND.hasCenterlineMarking());
    }
}
