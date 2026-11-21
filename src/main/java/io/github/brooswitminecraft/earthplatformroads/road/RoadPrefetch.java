package io.github.brooswitminecraft.earthplatformroads.road;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import java.time.Duration;

/**
 * The one real dispatch -- {@code waysForCell} then {@code enqueueLookahead}, through
 * {@link RoadNetwork#liveTileSourceForPrefetch()} -- shared by every mixin that triggers it, so the
 * two actual prefetch entry points ({@code EarthGenerator.prepare}/{@code prepareNear}, and now
 * {@code ServerCubeWorld}'s own ordinary streamed generation; see the mixin package's
 * {@code ServerCubeWorldRoadPrefetchMixin}) call exactly the same code rather than two copies that
 * could drift apart.
 *
 * <p>Deliberately NOT in the {@code mixin} package (where the two mixins that call it live): that
 * package is declared as this mod's Mixin-owned package in {@code earthplatformroads.mixins.json},
 * and SpongePowered Mixin refuses to class-load ANY class sitting directly in an owned package
 * unless it is itself one of that config's declared mixins -- confirmed live (an
 * {@code IllegalClassLoadError} crashing every test that actually triggered a prefetch) when this
 * class was first written inside that package instead.
 */
public final class RoadPrefetch {
    private RoadPrefetch() {}

    /**
     * @param x BLOCK x-coordinate (NOT a cube/chunk index) -- passed straight into
     *          {@code EarthGenerator.grid().geographic(x, z)}, which expects block coordinates.
     *          MINECRAFT-566 comment 55476: {@code ServerCubeWorldRoadPrefetchMixin}'s first version
     *          passed {@code ServerCubeWorld.produce}'s raw CUBE index here instead (confirmed by
     *          reading {@code ServerCubeWorld} itself -- {@code loadNow}'s own block-to-cube shift
     *          feeds that exact parameter), silently querying the wrong map tile on almost every
     *          call. Any caller with a cube/chunk index, not a block coordinate, MUST convert first
     *          (e.g. {@code (cubeX << 4) + 8} for that cube's own center) before calling this.
     * @param z BLOCK z-coordinate -- see {@code x}'s own doc; the same contract applies.
     */
    public static void forColumn(EarthGenerator generator, int x, int z) {
        RoadTileSource tiles = RoadNetwork.liveTileSourceForPrefetch();
        if (tiles == null) return;
        GeoPoint point = generator.grid().geographic(x, z);
        tiles.waysForCell(point.latitude(), point.longitude());
        tiles.enqueueLookahead(point.latitude(), point.longitude());
    }

    /**
     * MINECRAFT-566 comment 55476's "bounded-worker" ask, for the ordinary-streaming path
     * specifically ({@code ServerCubeWorldRoadPrefetchMixin}) -- {@link #forColumn} is left
     * unchanged for {@code EarthGenerator.prepare}/{@code prepareNear} (already async-dispatched by
     * Earth Platform itself; "preserve saved reads/cadence" per that comment). Bounds the own-cell
     * wait to {@code bound} via {@link RoadTileSource#waysForCellWithBoundedWait} (coalesced across
     * every cube-Y layer of the same column, real network wait on a dedicated background thread,
     * never a generation worker) and dispatches the Moore-neighborhood lookahead fully
     * asynchronously via {@link RoadTileSource#enqueueLookaheadAsync} -- the calling worker thread
     * never waits on that at all.
     */
    public static void forColumnBounded(EarthGenerator generator, int x, int z, Duration bound) {
        RoadTileSource tiles = RoadNetwork.liveTileSourceForPrefetch();
        if (tiles == null) return;
        GeoPoint point = generator.grid().geographic(x, z);
        tiles.waysForCellWithBoundedWait(point.latitude(), point.longitude(), bound);
        tiles.enqueueLookaheadAsync(point.latitude(), point.longitude());
    }

    /**
     * A cube/chunk index's own center BLOCK coordinate along one axis -- the conversion
     * {@code ServerCubeWorldRoadPrefetchMixin} needs before calling {@link #forColumn} with
     * {@code ServerCubeWorld.produce}'s cube indices (see comment 55476, and that mixin's own doc).
     * Pulled out as its own named, directly testable function rather than left inline, specifically
     * so a regression test can assert the exact converted coordinate (and bbox it leads to) without
     * needing a real {@code ServerCubeWorld}/{@code ServerLevel} to exercise the mixin itself.
     */
    public static int cubeIndexToBlockCenter(int cubeIndex) {
        return (cubeIndex << 4) + 8;
    }
}
