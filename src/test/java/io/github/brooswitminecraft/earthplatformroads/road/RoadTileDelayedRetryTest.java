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
 * The exact scenario MINECRAFT-566's review named: hold back a destination's road tile, confirm a
 * cube generated while it is held back is NOT silently saved as a permanent roadless gap, release
 * the tile, and confirm a retry actually produces the layered surface -- not just that
 * {@link RoadTileSource}'s own disk cache answers correctly in isolation (that is
 * {@link RoadTileSourceTest}'s job), but that the real generator, through the real mixins,
 * reflects it.
 *
 * <p><b>Scope, named honestly</b>: this asserts across a FRESH {@link EarthGenerator} instance over
 * the same tile cache directory -- the real retry path a server restart takes before a cube is
 * ever saved to a world's own storage. It does not build a real {@code ServerCubeWorld} (this
 * repo's tests never have -- see {@code RoadGenerationTest}'s own pattern of calling {@code
 * generator.generate(...)} directly), so it does not exercise {@code ServerCubeWorld}'s own
 * cube-persistence/storage layer specifically. Within one running generator instance,
 * {@code SurfaceGenerator}'s own per-column cache means a column already generated there keeps
 * whatever it first computed regardless of later data arriving -- a fresh instance, as a restart
 * provides, is what actually re-evaluates it.
 */
class RoadTileDelayedRetryTest {
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

    @Test
    void aHeldBackTileGeneratesNoRoadThenAFreshInstanceAfterReleaseDoes(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        String response = """
                {"elements":[
                  {"type":"node","id":1,"lat":37.7240,"lon":-119.6365},
                  {"type":"node","id":2,"lat":37.7241,"lon":-119.6366},
                  {"type":"way","id":555004,"nodes":[1,2],
                    "tags":{"highway":"secondary","name":"Delayed Retry Test Street"}}]}
                """;

        // Phase 1: the tile is held back -- every fetch attempt fails.
        HttpServer failingStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        failingStub.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        failingStub.start();
        this.server = failingStub;
        RoadNetwork.setTileSourceForTests(new RoadTileSource(
                URI.create("http://127.0.0.1:" + failingStub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(5)));

        EarthGenerator heldBack = bootAndBuildGenerator(demCache);
        int[] block = heldBack.block(EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE);
        int ground = heldBack.surfaceY(block[0], block[1]);
        int cubeX = block[0] >> 4, cubeY = ground >> 4, cubeZ = block[1] >> 4;
        LevelChunkSection heldBackSection = heldBack.generate(cubeX, cubeY, cubeZ);
        var heldBackState = heldBackSection.getBlockState(block[0] & 15, ground & 15, block[1] & 15);
        assertTrue(!isRoadSurface(heldBackState),
                "Expected no road while the tile is held back (a failed fetch must not fabricate a road), got " + heldBackState);
        // The failure must leave the cell pending, not confirmed-empty -- RoadTileSourceTest already
        // proves this for the source in isolation; this is the same property, through the real mixin.
        assertTrue(RoadNetwork.liveTileSourceForPrefetch().cachedWaysForCellOrEmpty(
                EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE).isEmpty());

        failingStub.stop(0);

        // Phase 2: release the tile (a working endpoint now), and simulate the real retry path --
        // a fresh generator instance (as a restart provides) over the SAME tile cache directory.
        HttpServer workingStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workingStub.createContext("/", exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        workingStub.start();
        this.server = workingStub;
        RoadNetwork.setTileSourceForTests(new RoadTileSource(
                URI.create("http://127.0.0.1:" + workingStub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(5)));

        EarthGenerator retried = bootAndBuildGenerator(demCache);
        retried.prepareNear(block[0], block[1]); // the real off-thread "fetch ahead of generation" path
        LevelChunkSection retriedSection = retried.generate(cubeX, cubeY, cubeZ);
        var retriedState = retriedSection.getBlockState(block[0] & 15, ground & 15, block[1] & 15);
        assertTrue(isRoadSurface(retriedState),
                "Expected the fresh instance, after the tile was released and prepareNear ran, to generate road surface, got " + retriedState);
    }
}
