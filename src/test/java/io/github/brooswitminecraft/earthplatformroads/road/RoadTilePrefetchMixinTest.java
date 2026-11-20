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
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@code EarthGeneratorPrepareMixin} actually does what MINECRAFT-566's review demanded:
 * calling Earth Platform's own {@code prepareNear} -- its documented "fetch ahead of generation"
 * entry point, the same one {@code /earth travel} calls off-thread before a player lands -- through
 * the real mixin on a real generator must populate {@code RoadTileSource}'s cache, so that a later,
 * cache-only {@code RoadNetwork} lookup (the one {@code EarthGeneratorRoadMixin}'s hot {@code
 * surface()} path uses) finds the data without itself ever touching the network.
 */
class RoadTilePrefetchMixinTest {
    @AfterEach
    void cleanUp() {
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

    @Test
    void prepareNearPopulatesTheCacheWithoutSurfaceEverFetching(@TempDir Path demCache, @TempDir Path roadCache) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String response = """
                {"elements":[
                  {"type":"node","id":1,"lat":37.7240,"lon":-119.6365},
                  {"type":"node","id":2,"lat":37.7241,"lon":-119.6366},
                  {"type":"way","id":555002,"nodes":[1,2],
                    "tags":{"highway":"secondary","name":"Prefetch Test Street"}}]}
                """;
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            RoadTileSource tileSource = new RoadTileSource(
                    URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/"), roadCache, true, Duration.ofSeconds(10));
            RoadNetwork.setTileSourceForTests(tileSource);

            EarthGenerator generator = bootAndBuildGenerator(demCache);
            int[] block = generator.block(EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE);

            // Before prepareNear runs, the cache-only lookup must be empty -- nothing fetched yet.
            assertTrue(RoadNetwork.segmentCoveringColumn(generator.grid(), block[0], block[1]) == null
                    || requests.get() == 0, "Expected no road-data fetch before prepareNear runs");
            assertEquals(0, requests.get());

            // Earth Platform's own "fetch ahead of generation" entry point; the mixin taps it.
            generator.prepareNear(block[0], block[1]);
            assertEquals(1, requests.get(), "Expected prepareNear to trigger exactly one road-tile fetch for this point's cell");

            // Now a cache-only lookup (what the hot surface() generation path actually uses) must
            // find the prefetched way, with no additional network request.
            var covering = RoadNetwork.segmentCoveringColumn(generator.grid(), block[0], block[1]);
            assertTrue(covering != null && covering.wayId() == 555002L,
                    "Expected the prefetched way to be found by a cache-only lookup after prepareNear");
            assertEquals(1, requests.get(), "The cache-only lookup after prepareNear must not trigger any further fetch");
        } finally {
            stub.stop(0);
        }
    }
}
