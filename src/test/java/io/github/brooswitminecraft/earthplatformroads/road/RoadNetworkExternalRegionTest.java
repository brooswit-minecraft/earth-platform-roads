package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MINECRAFT-566's milestone update: John wants mapped roads to appear automatically wherever he
 * travels, installed as data, not shipped as an ever-bigger bundled extract. This proves the
 * "versioned tile pipeline" actually works for a genuinely unbundled destination -- a region file
 * that exists nowhere in this mod's jar, only in a directory the real game install would use --
 * loading alongside the bundled Yosemite region through the exact same code path, with no Java
 * change. Everything here must reset {@link RoadNetwork}'s external-directory override back to
 * {@code null} afterward ({@link #cleanUp()}), or every other test in this JVM run would keep
 * seeing this test's fixture instead of a clean bundled-only network.
 */
class RoadNetworkExternalRegionTest {
    @AfterEach
    void cleanUp() {
        RoadNetwork.resetForTests(null);
    }

    private static void writeExternalRegion(Path directory, String fileName, String json) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), json);
    }

    /** An arbitrary, real-looking destination nowhere near Yosemite -- deliberately not part of this mod's bundled data. */
    private static final String UNBUNDLED_DESTINATION_JSON = """
            {
              "schema_version": 1,
              "region_id": "unbundled_test_destination",
              "ways": [
                {
                  "way_id": 999001,
                  "way_tags": {"highway": "residential", "name": "Unbundled Test Street"},
                  "centerline": [{"lat": 40.0, "lon": -105.0}, {"lat": 40.001, "lon": -105.001}]
                }
              ]
            }
            """;

    @Test
    void anExternalRegionLoadsAlongsideTheBundledYosemiteRegion(@TempDir Path externalDir) throws IOException {
        writeExternalRegion(externalDir, "unbundled_test_destination.json", UNBUNDLED_DESTINATION_JSON);
        RoadNetwork.resetForTests(externalDir);

        var segments = RoadNetwork.segments();
        boolean sawExternal = segments.stream().anyMatch(s -> s.wayId() == 999001);
        boolean sawBundledYosemite = segments.stream().anyMatch(s -> s.wayId() == 10432929);

        assertTrue(sawExternal, "Expected the external, unbundled destination's way to load through the same pipeline");
        assertTrue(sawBundledYosemite, "Expected the bundled Yosemite region to still load alongside the external one");
    }

    @Test
    void multipleExternalRegionFilesAllLoad(@TempDir Path externalDir) throws IOException {
        writeExternalRegion(externalDir, "destination_one.json", """
                {"schema_version": 1, "region_id": "one", "ways": [
                  {"way_id": 999101, "way_tags": {"highway": "residential", "name": "A"},
                   "centerline": [{"lat": 41.0, "lon": -106.0}, {"lat": 41.001, "lon": -106.001}]}
                ]}
                """);
        writeExternalRegion(externalDir, "destination_two.json", """
                {"schema_version": 1, "region_id": "two", "ways": [
                  {"way_id": 999102, "way_tags": {"highway": "residential", "name": "B"},
                   "centerline": [{"lat": 42.0, "lon": -107.0}, {"lat": 42.001, "lon": -107.001}]}
                ]}
                """);
        RoadNetwork.resetForTests(externalDir);

        var segments = RoadNetwork.segments();
        assertTrue(segments.stream().anyMatch(s -> s.wayId() == 999101), "Expected the first external file's way to load");
        assertTrue(segments.stream().anyMatch(s -> s.wayId() == 999102), "Expected the second external file's way to load");
    }

    @Test
    void anExternalRegionWithAnUnsupportedSchemaVersionFailsLoudly(@TempDir Path externalDir) throws IOException {
        writeExternalRegion(externalDir, "future_format.json", """
                {"schema_version": 99, "region_id": "future", "ways": []}
                """);
        RoadNetwork.resetForTests(externalDir);

        assertThrows(IllegalStateException.class, RoadNetwork::segments,
                "Expected an unsupported schema_version to fail loudly, not silently misparse or partially load");
    }

    @Test
    void aMissingExternalDirectoryIsFineAndLoadsOnlyBundledRegions(@TempDir Path externalDir) throws IOException {
        Path doesNotExist = externalDir.resolve("does-not-exist");
        RoadNetwork.resetForTests(doesNotExist);

        var segments = RoadNetwork.segments();
        assertTrue(segments.stream().anyMatch(s -> s.wayId() == 10432929), "Expected the bundled Yosemite region to still load");
        assertEquals(1, segments.size(), "Expected only the bundled region's one way with no external directory present");
    }
}
