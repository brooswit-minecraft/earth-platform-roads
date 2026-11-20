package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review (comment 55431) correctly flagged {@link YosemiteUnbundledSmokeTest} as using constructed
 * way/node IDs, not real-world data. This test instead serves genuine OpenStreetMap data: a real
 * bounded export from the official OSM API (api.openstreetmap.org/api/0.6/map.json, NOT Overpass --
 * that endpoint was unreachable three times earlier this session) at Southside Drive x Sentinel
 * Drive, ~5km east of the bundled Yosemite extract. Provenance (capture time, attribution, SHA-256
 * of both the raw API response and the derived Overpass-shaped fixture, all 27 converted way IDs)
 * is recorded in {@code src/test/resources/fixtures/sentinel_southside_junction.provenance.json}.
 * The real OSM "nodes" + "ways" response was resolved into the Overpass-shaped {@code "geometry"}
 * array {@code OverpassWayParser} expects by {@code /tmp/convert_osm_to_overpass_shape.py}
 * (not checked in -- a one-off conversion script, not part of this mod).
 *
 * <p>This fixture genuinely contains two real {@code bridge=yes} ways (Sentinel Bridge over the
 * Merced River) -- the first time this mod's bridge-skip behavior is checked against real tagged
 * bridge data, not a synthetic flag.
 *
 * <p>Per the review's own instruction, this fixture is a temporary private QA source for this one
 * verification, not bundled as this mod's default data or evidence of broader live coverage.
 */
class RealOsmFixtureSmokeTest {
    private HttpServer server;

    @AfterEach
    void cleanUp() {
        if (server != null) server.stop(0);
        RoadNetwork.setTileSourceForTests(null);
    }

    private static EarthGenerator bootAndBuildGenerator(Path demCache) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements()
                .forEach(holder -> biomes.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        for (String name : List.of("glacial_lake")) {
            try (var in = EarthGenerator.class.getResourceAsStream("/data/earthplatform/worldgen/biome/" + name + ".json")) {
                var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in));
                Biome biome = Biome.DIRECT_CODEC.parse(
                        net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, lookup), json).getOrThrow();
                biomes.register(
                        net.minecraft.resources.ResourceKey.create(Registries.BIOME,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("earthplatform", name)),
                        biome, RegistrationInfo.BUILT_IN);
            }
        }
        TerrariumTileSource tiles = new TerrariumTileSource(
                TerrariumTileSource.AWS_TERRAIN_TILES, demCache.resolve(TerrariumTileSource.DATASET_ID), true, 512, 6, Duration.ofSeconds(20));
        EarthSettings settings = EarthSettings.at(EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE);
        return new EarthGenerator(4747L, biomes, settings, tiles, null, null, null);
    }

    private static boolean isRoadSurface(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.POLISHED_BLACKSTONE)
                || state.is(io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get());
    }

    private static double collisionTopFraction(net.minecraft.world.level.block.state.BlockState state) {
        return state.getCollisionShape(null, null).max(net.minecraft.core.Direction.Axis.Y);
    }

    @Test
    void realOsmDataAtSentinelSouthsideJunctionGeneratesRoadsAndSkipsTheRealBridge(
            @TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        String fixtureJson;
        try (var in = RealOsmFixtureSmokeTest.class.getResourceAsStream("/fixtures/sentinel_southside_junction.overpass_shaped.json")) {
            fixtureJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            byte[] body = fixtureJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        this.server = stub;
        RoadNetwork.setTileSourceForTests(new RoadTileSource(
                URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(15)));

        EarthGenerator generator = bootAndBuildGenerator(demCache);
        var grid = generator.grid();

        // Real Southside Drive x Sentinel Drive junction node 89102319, from the real fixture.
        double junctionLat = 37.7431225, junctionLon = -119.5897878;
        int[] junctionBlock = generator.block(junctionLat, junctionLon);
        generator.prepareNear(junctionBlock[0], junctionBlock[1]); // the real off-thread fetch-ahead path

        // Multiple real mapped ways must now be live. Found gap, recorded rather than worked
        // around: RoadNetwork.segments() (and excludedBridgesAndTunnels(), which calls it) only
        // scans the preloaded bundled+external list -- it does not see live-tile-sourced ways at
        // all, the same limitation YosemiteUnbundledSmokeTest already found for junctions(). So this
        // checks real points along each real way directly via segmentCoveringColumn instead.
        int[] southsideFirstPoint = generator.block(37.7431099, -119.5896107);
        var southside = RoadNetwork.segmentCoveringColumn(grid, southsideFirstPoint[0], southsideFirstPoint[1]);
        assertTrue(southside != null && southside.wayId() == 245548094L, "Expected the real Southside Drive way to load and cover its own first point");

        int[] sentinelFirstPoint = generator.block(37.7436208, -119.5896728);
        var sentinel = RoadNetwork.segmentCoveringColumn(grid, sentinelFirstPoint[0], sentinelFirstPoint[1]);
        assertTrue(sentinel != null && sentinel.wayId() == 40291662L, "Expected the real Sentinel Drive way to load and cover its own first point");

        // The two real bridge=yes ways (Sentinel Bridge, real tag alt_name="Sentinel Crossover")
        // must be tracked but excluded from rendering: no surface drawn at their midpoint at all.
        int[] bridgeMidpoint = generator.block(37.74343, -119.58969);
        var atBridge = RoadNetwork.segmentCoveringColumn(grid, bridgeMidpoint[0], bridgeMidpoint[1]);
        assertTrue(atBridge == null, "Expected no surface to be drawn at the real bridge's own midpoint, got " + atBridge);

        // Scan a real row near the junction for an actual covered adjacent step (found by probing
        // the real DEM beforehand: a genuine 1-block step exists a few columns from the junction).
        int x0 = junctionBlock[0];
        int bestLowZ = Integer.MIN_VALUE, bestHighZ = Integer.MIN_VALUE;
        for (int dz = -15; dz < 15; dz++) {
            int z1 = junctionBlock[1] + dz, z2 = z1 + 1;
            if (!RoadNetwork.coversColumn(grid, x0, z1) || !RoadNetwork.coversColumn(grid, x0, z2)) continue;
            int y1 = generator.surfaceY(x0, z1), y2 = generator.surfaceY(x0, z2);
            if (y2 == y1 + 1) {
                bestLowZ = z1;
                bestHighZ = z2;
                break;
            }
        }
        assertTrue(bestLowZ != Integer.MIN_VALUE,
                "Expected to find a real, road-covered 1-block step near the real junction (re-probe if the live DEM changed)");

        int groundLow = generator.surfaceY(x0, bestLowZ), groundHigh = generator.surfaceY(x0, bestHighZ);
        int cubeXl = x0 >> 4, cubeYl = groundLow >> 4, cubeZl = bestLowZ >> 4;
        LevelChunkSection sectionLow = generator.generate(cubeXl, cubeYl, cubeZl);
        var stateLow = sectionLow.getBlockState(x0 & 15, groundLow & 15, bestLowZ & 15);
        int cubeXh = x0 >> 4, cubeYh = groundHigh >> 4, cubeZh = bestHighZ >> 4;
        LevelChunkSection sectionHigh = generator.generate(cubeXh, cubeYh, cubeZh);
        var stateHigh = sectionHigh.getBlockState(x0 & 15, groundHigh & 15, bestHighZ & 15);

        assertTrue(isRoadSurface(stateLow) && isRoadSurface(stateHigh),
                "Expected both sides of the real incline to generate real road surface, got " + stateLow + " / " + stateHigh);

        double topLow = groundLow + collisionTopFraction(stateLow);
        double topHigh = groundHigh + collisionTopFraction(stateHigh);
        double gap = Math.abs(topHigh - topLow);
        // Measured at this real location: the low side is a full block (POLISHED_BLACKSTONE,
        // topLow = groundLow + 1), the high side is the layered compat block at the minimum layer
        // (LAYERED_POLISHED_BLACKSTONE anchor=floor,layers=1, topHigh = groundHigh + 1/16) -- a
        // real 0.0625-block seam, not the full 1-block cliff the unfixed layering logic produced.
        assertTrue(gap < 0.5, "Expected the real incline's collision gap to stay well under a full block, got " + gap);
        assertTrue(gap > 0.0, "Expected a real, non-zero fractional seam at this real incline, got exactly 0");
    }
}
