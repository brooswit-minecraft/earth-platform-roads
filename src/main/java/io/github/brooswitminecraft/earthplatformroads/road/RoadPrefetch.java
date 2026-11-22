package io.github.brooswitminecraft.earthplatformroads.road;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat;
import io.github.brooswitminecraft.earthplatformroads.mixin.SurfaceGeneratorColumnCacheAccessor;
import java.time.Duration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

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

    /**
     * MINECRAFT-566 comment 55486/55487: the exact key {@code SurfaceGenerator.columnKey(int,int)}
     * (private, confirmed by reading its real bytecode) computes for its own per-column cache --
     * {@code HashCommon.mix} applied to the two column/chunk coordinates packed into one long,
     * {@code (columnX << 32) | (columnZ & 0xFFFFFFFFL)}. {@code HashCommon.mix} is a real, public
     * fastutil method (already an Earth Platform/NeoForge runtime dependency), so this can be
     * replicated exactly without needing a Mixin accessor into the private method itself -- only
     * the {@code columns} map needs one ({@code SurfaceGeneratorColumnCacheAccessor}). Column/chunk
     * coordinates here, NOT block coordinates -- matches {@code ServerCubeWorld.produce}'s own
     * {@code cubeX}/{@code cubeZ} parameters directly, with no conversion needed (unlike {@link #forColumn}'s
     * block-coordinate contract).
     */
    public static long surfaceGeneratorColumnKey(int columnX, int columnZ) {
        return it.unimi.dsi.fastutil.HashCommon.mix(((long) columnX << 32) | (columnZ & 0xFFFFFFFFL));
    }

    /**
     * Evicts this one column's possibly-stale cached {@code Surface} decision (see
     * {@link SurfaceGeneratorColumnCacheAccessor}'s own doc for the real bug this guards against)
     * -- narrow, per-column, never a global cache clear. Column/chunk coordinates, matching
     * {@link #surfaceGeneratorColumnKey}. {@code (Object)}-via-cast is required here, not optional
     * boilerplate: {@code EarthGenerator} is a real {@code final} class (confirmed by reading it),
     * so a direct cast to an interface its own Java source never declares implementing (which this
     * Mixin-woven accessor interface is, from the compiler's perspective at compile time) would not
     * compile without it.
     */
    public static void evictStaleSurfaceDecision(EarthGenerator generator, int columnX, int columnZ) {
        ((SurfaceGeneratorColumnCacheAccessor) (Object) generator).earthplatformroads$columns()
                .remove(surfaceGeneratorColumnKey(columnX, columnZ));
    }

    /**
     * MINECRAFT-566 comment 55496: eviction alone cannot fully close the race -- a DIFFERENT,
     * already-in-flight concurrent {@code column()} computation, started before the eviction ran,
     * can still publish its own (also premature) result into the SAME now-empty slot before
     * {@code generate()}'s own read of it, winning regardless of eviction timing. Rather than
     * reimplement {@code SurfaceGenerator}'s own cached depth-spread/decoration logic to move the
     * road decision out of its cache entirely (comment 55498: real additional risks there --
     * cached {@code Surface} data also drives cave-mouth behavior and structure fill, which a blind
     * post-generate overwrite could corrupt) this instead verifies the ALREADY-GENERATED section
     * against what fresh road data says it should contain, so {@code ServerCubeWorldRoadPrefetchMixin}'s
     * {@code @Redirect} can retry (re-evict, regenerate) a small, bounded number of times rather than
     * accept a result a race may have corrupted. Never touches a block outside what fresh road data
     * says should be road surface -- an unrelated cave mouth, structure, or any off-road column is
     * never inspected here, let alone written to.
     *
     * <p>Scans this cube's own 16x16 columns (not every cube in the world -- bounded, and only ever
     * for cubes {@code ServerCubeWorldRoadPrefetchMixin} is already generating): for each column
     * where fresh {@link RoadNetwork} data says a road should be painted, and whose natural ground
     * height actually falls within THIS cube's own Y range, confirms the generated section's actual
     * block there is one of this mod's three road materials. {@link EarthGenerator#surfaceY}/
     * {@link SurfaceGenerator#waterY} (natural, not derived from the decorated output) are used for
     * height/water, per 55498's own explicit guidance -- the SAME functions
     * {@code EarthGeneratorRoadMixin}'s own (unmodified, still the single source of truth for WHICH
     * material to paint) {@code surface()} hook already relies on.
     */
    public static boolean sectionLooksStale(EarthGenerator generator, int cubeX, int cubeY, int cubeZ, LevelChunkSection section) {
        MercatorGrid grid = generator.grid();
        int baseX = cubeX << 4, baseZ = cubeZ << 4, baseY = cubeY << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldX = baseX + lx, worldZ = baseZ + lz;
                RoadSegment segment = RoadNetwork.segmentCoveringColumn(grid, worldX, worldZ);
                if (segment == null) continue;
                if (segment.crossSectionAt(grid, worldX, worldZ) == RoadSegment.CrossSection.OFF_ROAD) continue;
                int groundY = generator.surfaceY(worldX, worldZ);
                int waterY = generator.waterY(worldX, worldZ);
                if (waterY != SurfaceGenerator.DRY && waterY >= groundY) continue; // underwater: surface() itself never paints here either
                int localY = groundY - baseY;
                if (localY < 0 || localY > 15) continue; // this column's surface is not in THIS cube
                if (!isAnyRoadSurfaceMaterial(section.getBlockState(lx, localY, lz))) return true;
            }
        }
        return false;
    }

    private static boolean isAnyRoadSurfaceMaterial(BlockState state) {
        return state.is(Blocks.POLISHED_BLACKSTONE) || state.is(LayeredSlopesCompat.LAYERED_POLISHED_BLACKSTONE.get())
                || state.is(Blocks.YELLOW_TERRACOTTA) || state.is(LayeredSlopesCompat.LAYERED_YELLOW_TERRACOTTA.get())
                || state.is(Blocks.GRAVEL) || state.is(LayeredSlopesCompat.LAYERED_GRAVEL.get());
    }
}
