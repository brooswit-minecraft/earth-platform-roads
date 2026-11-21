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
     * MINECRAFT-566 comment 55526's "narrow unsaved surface-cube defer/retry" ask: whether the real
     * tile/cell covering this column is still genuinely {@link RoadTileSource.CellState#PENDING} --
     * never resolved as either READY or CONFIRMED_EMPTY. Unlike {@link #sectionLooksStale} (which
     * infers staleness indirectly, by comparing the already-decorated GENERATED block against fresh
     * geometry, and was found to have real false positives -- see that method's own doc), this is a
     * direct read of {@link RoadTileSource}'s own authoritative cache state for this exact cell, with
     * no inference involved: a reliable signal to decide whether deferring/retrying generation is
     * even worth attempting, deliberately kept separate from the (currently disabled) stale-retry
     * mechanism rather than reusing or extending it. {@code false} (never "pending") when there is no
     * live tile source at all -- nothing to wait for.
     */
    public static boolean isPendingForColumn(EarthGenerator generator, int x, int z) {
        RoadTileSource tiles = RoadNetwork.liveTileSourceForPrefetch();
        if (tiles == null) return false;
        GeoPoint point = generator.grid().geographic(x, z);
        return tiles.cellState(point.latitude(), point.longitude()) == RoadTileSource.CellState.PENDING;
    }

    /**
     * MINECRAFT-566 comment 55532: whether there is even a live tile source configured at all --
     * an EXPLICIT gate for {@code ServerCubeWorldRoadPrefetchMixin}'s pending-surface-cube defer,
     * rather than relying only on {@link #isPendingForColumn}'s own implicit {@code null}-source
     * check (correct in effect, but John's own review asked this be an explicit, named condition).
     *
     * <p><b>Comment 55534, a real gap in the first version:</b> a plain non-null check also reports
     * {@code true} for a cache-only instance ({@code RoadTileSource.canFetchLive() == false}, e.g.
     * the "not opted into the preview provider" default) -- a real, non-null source that serves
     * already-cached reads correctly but can never resolve a genuinely pending cell through its own
     * fetch, so deferring/waiting for one is pure waste (independently observed: zero POSTs sent,
     * but the defer path still entered its own wait scheduling). Fixed to check actual live-fetch
     * capability, not mere existence -- {@link #isPendingForColumn}'s own cached-read behavior is
     * deliberately untouched by this change, so a cache-only install's already-fetched data still
     * reads normally; only THIS explicit "is it worth deferring for" gate changed.
     */
    public static boolean hasLiveTileSource() {
        RoadTileSource tiles = RoadNetwork.liveTileSourceForPrefetch();
        return tiles != null && tiles.canFetchLive();
    }

    /**
     * MINECRAFT-566 comment 55534, a conservative padding below each sampled column's own natural
     * ground height, applied by {@link #cubeContainsNaturalSurface}: John's own native "upper-first"
     * fixture found a real road footprint column (off-center, e.g. the shoulder) whose OWN natural
     * ground height sits a block or more ABOVE another column's (e.g. the paved centerline) -- the
     * footprint is not flat. Rather than compute the exact material-bearing depth from Earth
     * Platform's own upstream {@code Surface} decoration (deliberately not done -- that is the same
     * private, memoized, already-disqualified-once data source {@code sectionLooksStale} avoids;
     * see this class's own doc), a small fixed conservative margin below ground is used instead.
     */
    private static final int BASE_PAVING_PADDING_BLOCKS = 3;

    /**
     * MINECRAFT-566 comment 55532: "gate by... actual surface relevance (current loop has no Y
     * filter)" -- a road is only ever painted AT a column's own natural ground height
     * ({@code EarthGeneratorRoadMixin}'s {@code surface()} hook never touches any other Y), so a
     * cube whose own Y-range does not even come close to any natural ground height in its own
     * footprint can never show a road regardless of whether the tile is pending -- deferring/
     * retrying generation for such a cube would be pure waste.
     *
     * <p><b>Comment 55534, a real gap in the first version:</b> the first version checked only the
     * CUBE's own single center column's ground height. John's native "upper-first" fixture found a
     * real road footprint is not flat: an off-center column within the same footprint (e.g. the
     * paved edge or shoulder, not the sampled center point) can have its own natural ground height a
     * block or more different from the center's -- so a cube that is Y-irrelevant for the center
     * column alone can still be exactly where a DIFFERENT, still-within-footprint column's own road
     * surface needs to be painted.
     *
     * <p><b>Comment 55537, the fix attempted for that gap, filtered by mapped geometry:</b> the first
     * fix scanned this cube's own 16x16 column grid but required each sampled column to actually be
     * {@code RoadNetwork.segmentCoveringColumn}-covered before its ground height counted at all.
     *
     * <p><b>Comment 55539, a real, more fundamental bug in THAT fix:</b> requiring mapped geometry to
     * exist before this gate will even consider a cube relevant is circular for the exact case this
     * whole mechanism exists to help -- a genuinely cold, never-fetched (PENDING) tile has NO mapped
     * geometry yet at all, by definition, so {@code segmentCoveringColumn} returned {@code null} for
     * EVERY column, making this gate report every cube irrelevant and skip dispatch entirely, even
     * on an entirely ordinary, healthy cold-bootstrap walk into fresh terrain -- confirmed natively:
     * both a flat case and the 55537 upper-first case saved grass with ZERO prefetch calls and zero
     * POSTs, tile left PENDING, while a fresh, explicit, fixture-only fetch correctly painted both.
     * Fixed per John's own direction ("use natural-only support relevance before fetch... reserve
     * mapped geometry for resolved tiles if useful"): this gate is now geometry-INDEPENDENT --
     * every column in the cube's own 16x16 footprint is sampled for its NATURAL ground height alone
     * (never filtered by whether a road is actually mapped there), padded by
     * {@link #BASE_PAVING_PADDING_BLOCKS} below. This is deliberately more permissive than "a road
     * is mapped here" (it cannot know that before a fetch resolves), but still correctly excludes a
     * genuinely irrelevant cube (deep underground below, or high in the sky above, this whole
     * footprint's own natural terrain) -- the only question this gate can safely answer before a
     * fetch resolves. Reusing mapped geometry to narrow this further once a tile IS already resolved
     * is a possible future refinement, not attempted here (not required to fix this bug).
     */
    public static boolean cubeContainsNaturalSurface(EarthGenerator generator, int cubeX, int cubeY, int cubeZ) {
        int baseX = cubeX << 4, baseZ = cubeZ << 4, baseY = cubeY << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int groundY = generator.surfaceY(baseX + lx, baseZ + lz);
                int localY = groundY - baseY;
                if (localY + BASE_PAVING_PADDING_BLOCKS >= 0 && localY <= 15) return true;
            }
        }
        return false;
    }

    /**
     * MINECRAFT-566 comment 55545, John's own direction change: "no timeout/cooldown/outage alone
     * should adopt or permanently save bare terrain... start with actual road-data tiles
     * intersecting the relevant cube footprint (55542), not the speculative halo." Scans this cube's
     * own 16x16 footprint exactly like {@link #cubeContainsNaturalSurface} (the same
     * {@link #BASE_PAVING_PADDING_BLOCKS}-padded natural-height filter -- only a column that could
     * plausibly need THIS cube even counts), but for each such column checks whether the ACTUAL cell
     * it maps to is still genuinely {@link RoadTileSource.CellState#PENDING}, rather than inferring
     * everything from one center sample (comment 55542's own finding: a cube can straddle two real
     * cells, one already confirmed-empty at the center while a different cell covering only an outer
     * strip of the SAME cube is still genuinely pending -- natively reproduced, worker adopted grass
     * for that case). {@code false} when there is no live tile source at all.
     */
    public static boolean isAnyRelevantCellPending(EarthGenerator generator, int cubeX, int cubeY, int cubeZ) {
        RoadTileSource tiles = RoadNetwork.liveTileSourceForPrefetch();
        if (tiles == null) return false;
        MercatorGrid grid = generator.grid();
        int baseX = cubeX << 4, baseZ = cubeZ << 4, baseY = cubeY << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldX = baseX + lx, worldZ = baseZ + lz;
                int localY = generator.surfaceY(worldX, worldZ) - baseY;
                if (localY + BASE_PAVING_PADDING_BLOCKS < 0 || localY > 15) continue; // this column could never need this cube
                GeoPoint point = grid.geographic(worldX, worldZ);
                if (tiles.cellState(point.latitude(), point.longitude()) == RoadTileSource.CellState.PENDING) return true;
            }
        }
        return false;
    }

    /**
     * MINECRAFT-566 comment 55545: the dispatch counterpart to {@link #isAnyRelevantCellPending} --
     * fires off (never waits on) a fetch for every actual cell this cube's own relevant footprint
     * intersects, exactly the same set {@link #isAnyRelevantCellPending} checks, not a broader
     * speculative neighborhood.
     *
     * <p><b>Comment 55547, a real liveness gap in the first version:</b> that version called
     * {@code RoadTileSource.waysForCellWithBoundedWait} with {@link Duration#ZERO} per column,
     * un-deduplicated. {@code Duration.ZERO} turned out not to be genuinely fire-and-forget --
     * see {@code RoadTileSource.dispatchCellFetch}'s own doc for why that specific bound dropped
     * this cell's own current-demand registration on the very first cooldown decline, letting a
     * merely speculative lookahead cell take the next legal request slot ahead of it (natively
     * observed starving a real demand even against a healthy backend). Fixed by switching to the
     * new {@link RoadTileSource#dispatchCellFetch}, a genuinely fire-and-forget dispatch that stays
     * registered as current, high-priority demand through an entire ordinary cooldown. Also
     * deduplicates by actual cell before dispatching (the ordinary case: one real map cell is far
     * larger than one cube, so most of this cube's own 256 columns map to the very same one or two
     * cells) -- "deduplicate relevant footprint tile keys before dispatch," per John's own
     * instruction, rather than redundantly re-checking/re-dispatching the same cell up to 256 times.
     */
    public static void dispatchRelevantCellFetches(EarthGenerator generator, int cubeX, int cubeY, int cubeZ) {
        RoadTileSource tiles = RoadNetwork.liveTileSourceForPrefetch();
        if (tiles == null) return;
        MercatorGrid grid = generator.grid();
        int baseX = cubeX << 4, baseZ = cubeZ << 4, baseY = cubeY << 4;
        java.util.Set<Long> dispatchedCells = new java.util.HashSet<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldX = baseX + lx, worldZ = baseZ + lz;
                int localY = generator.surfaceY(worldX, worldZ) - baseY;
                if (localY + BASE_PAVING_PADDING_BLOCKS < 0 || localY > 15) continue;
                GeoPoint point = grid.geographic(worldX, worldZ);
                double lat = point.latitude(), lon = point.longitude();
                long cellLat = (long) Math.floor(lat / RoadNetwork.CELL_DEGREES);
                long cellLon = (long) Math.floor(lon / RoadNetwork.CELL_DEGREES);
                long cellKey = (cellLat << 32) ^ (cellLon & 0xFFFFFFFFL);
                if (!dispatchedCells.add(cellKey)) continue; // already dispatched for this exact cell from an earlier column in this same cube
                tiles.dispatchCellFetch(lat, lon);
                tiles.enqueueLookaheadAsync(lat, lon);
            }
        }
    }

    /**
     * MINECRAFT-566 comment 55562, John's own source audit: {@code ServerCubeWorldRoadPrefetchMixin}'s
     * gate previously called {@link #cubeContainsNaturalSurface}, then (if relevant)
     * {@link #dispatchRelevantCellFetches}, then {@link #isAnyRelevantCellPending} -- THREE
     * separate full 256-column scans of the exact same cube, each independently re-deriving
     * {@code generator.surfaceY} from scratch for every column ("the ready gate also rescans
     * natural heights"), and the pending check alone calling {@code RoadTileSource.cellState} once
     * per column (up to 256 times, even though most columns in one 16-block cube map to the very
     * same one or two ~1.1km map cells -- "256 repeated filesystem stats for the same already-cached
     * tile per pass"). This replaces all three with ONE pass: {@code generator.surfaceY} is read
     * exactly once per column, and {@code cellState}/dispatch are each invoked at most once per
     * UNIQUE cell this cube's relevant footprint intersects, not once per column -- "start with a
     * low-risk one-pass, unique tile state check," per John's own exact phrasing.
     *
     * <p>Deliberately NOT a replacement for {@link #cubeContainsNaturalSurface}/
     * {@link #dispatchRelevantCellFetches}/{@link #isAnyRelevantCellPending} themselves: those stay
     * exactly as they were (and exactly as tested), since other code may still call them
     * individually; this is purely a new, additional entry point for the one real hot call site
     * that was paying for all three separately. Never weakens the correctness-waiting behavior this
     * mixin relies on: the only difference from calling the three originals in sequence is how many
     * times the SAME underlying facts get recomputed, never which cells get dispatched for or which
     * answer "pending" resolves to.
     */
    public record RelevantCellScanResult(boolean naturallyRelevant, boolean anyRelevantCellPending) {}

    public static RelevantCellScanResult dispatchAndScanRelevantCells(EarthGenerator generator, int cubeX, int cubeY, int cubeZ) {
        RoadTileSource tiles = RoadNetwork.liveTileSourceForPrefetch();
        MercatorGrid grid = generator.grid();
        int baseX = cubeX << 4, baseZ = cubeZ << 4, baseY = cubeY << 4;
        boolean naturallyRelevant = false;
        boolean anyPending = false;
        java.util.Set<Long> seenCells = tiles == null ? null : new java.util.HashSet<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldX = baseX + lx, worldZ = baseZ + lz;
                int localY = generator.surfaceY(worldX, worldZ) - baseY; // the ONLY surfaceY call per column, not three
                if (localY + BASE_PAVING_PADDING_BLOCKS < 0 || localY > 15) continue;
                naturallyRelevant = true;
                if (tiles == null) continue;
                GeoPoint point = grid.geographic(worldX, worldZ);
                double lat = point.latitude(), lon = point.longitude();
                long cellLat = (long) Math.floor(lat / RoadNetwork.CELL_DEGREES);
                long cellLon = (long) Math.floor(lon / RoadNetwork.CELL_DEGREES);
                long cellKey = (cellLat << 32) ^ (cellLon & 0xFFFFFFFFL);
                if (!seenCells.add(cellKey)) continue; // already handled this exact cell from an earlier column in this same cube
                tiles.dispatchCellFetch(lat, lon);
                tiles.enqueueLookaheadAsync(lat, lon);
                if (tiles.cellState(lat, lon) == RoadTileSource.CellState.PENDING) anyPending = true;
            }
        }
        return new RelevantCellScanResult(naturallyRelevant, anyPending);
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
     * MINECRAFT-566 comment 55562, John's own source audit: {@code ServerCubeWorldRoadPrefetchMixin}
     * called {@link #evictStaleSurfaceDecision} UNCONDITIONALLY for every single {@code produce()}
     * call -- including every unsaved cube at ANY Y, sky and deep underground included -- so a tall
     * column's many cube-Y layers each evicted the SAME shared X/Z {@code SurfaceGenerator.Column}
     * cache the others had just populated, forcing every sibling to re-run the raw+road-aware survey
     * from scratch. Fixed to evict only when BOTH: (1) {@code naturallyRelevant} (this exact cube's
     * own natural-height relevance, now computed once per {@link #dispatchAndScanRelevantCells} or
     * the cheap {@link #cubeContainsNaturalSurface} -- an irrelevant sibling has nothing a road
     * decision could even apply to, so evicting for it is pure waste regardless of staleness), AND
     * (2) something has actually changed since the last time THIS column was evicted -- "keep
     * freshness on real data transition, not each sibling." Tracked via {@link RoadTileSource#resolvedGeneration()}:
     * a coarse, global counter bumped whenever any cell anywhere newly resolves. Deliberately coarse
     * rather than per-cell-precise -- an unrelated cell resolving can cause one harmless EXTRA
     * eviction for this column, but the counter can never fail to advance when THIS column's own
     * relevant cell actually resolves, which is the one direction that must never be wrong (under-
     * eviction would regress the already-proven stale-column repaint from comments 55486/55487/
     * 55492/55496 -- see {@code ServerCubeWorldRoadPrefetchMixin}'s own class doc for that history).
     * The very FIRST call for a given column always evicts (no prior recorded generation to compare
     * against) -- the same "free in the ordinary case, nothing legitimate to evict yet" guarantee
     * the original unconditional eviction always provided for a column's first {@code produce()}
     * call. {@code generator} must be a {@code null} tile source's own generation (no live source at
     * all, e.g. cache-only or disabled): {@link RoadTileSource#resolvedGeneration()} is then always
     * {@code 0}, so after the first eviction every later sibling at the same relevance correctly
     * skips -- there is nothing that could ever "newly resolve" to justify re-evicting.
     *
     * <p><b>MINECRAFT-566 comment 55564, two real bugs found by John's own independent native
     * QA/review of this exact method:</b>
     *
     * <p><b>Bug 1, cross-generator isolation:</b> the first version keyed this tracking by packed
     * X/Z alone, in a single {@code static} map shared by EVERY {@code EarthGenerator} instance.
     * Two separate generators at the same X/Z (two different worlds, or a fresh generator instance
     * after a world reload -- the same "stale generator instance" scenario comments 55486/55487
     * already established is real) could suppress EACH OTHER's first eviction: generator A records
     * generation N for column (3,5); generator B, an entirely different instance/world that happens
     * to also touch column (3,5) at its own generation N, would see "already evicted this exact
     * generation" and wrongly skip -- even though B's OWN {@code SurfaceGenerator} cache was never
     * touched by A's eviction at all (evicting is always scoped to the generator instance passed
     * in). Fixed by scoping the tracking PER GENERATOR INSTANCE: a {@code WeakHashMap<EarthGenerator,
     * ...>} outer map (identity-keyed -- confirmed by reading it, {@code EarthGenerator} is a real
     * {@code final} class that never overrides {@code equals}/{@code hashCode}, so default reference
     * identity is exactly right here, and is also why a discarded generator's own entry can be
     * reclaimed once nothing else references it, bounding growth across world reloads -- "the map
     * also grows as new columns are visited" is answered at the per-generator level this way, though
     * a single long-lived generator's own per-column inner map still grows with the columns it
     * actually visits, same as the original design intentionally accepted).
     *
     * <p><b>Bug 2, record-before-evict ordering:</b> the first version recorded the new generation
     * BEFORE calling {@link #evictStaleSurfaceDecision}, leaving a window in which a competing,
     * already-in-flight {@code column()} computation (the same residual race comment 55496's own
     * doc section already discloses, at a different point in this pipeline) could still publish a
     * stale, pre-resolution result into the now-recorded-fresh slot -- and because the generation
     * was already recorded, every LATER call at that same generation would trust the stale cache
     * without ever re-evicting to catch it. Fixed by reordering to evict first, record second --
     * narrowing (not eliminating; this is the SAME residual race class, not a new one) the window,
     * matching the identical reordering precedent comment 55492 already established for the OTHER
     * eviction call site ("the eviction is the very LAST statement... immediately before falling
     * through").
     *
     * <p>"Preserve scoped warm reuse" (John's own explicit ask): the inner per-generator map is
     * untouched in shape or semantics by either fix -- a single generator's own sibling cube-Y calls
     * at an unchanged resolution generation still skip re-eviction exactly as before; only which
     * generator instance's own map gets consulted, and the record/evict order, changed.
     *
     * @return whether an eviction actually ran, for tests to assert the gating itself, not just its
     *         downstream effect.
     */
    private static final java.util.Map<EarthGenerator, java.util.concurrent.ConcurrentHashMap<Long, Long>> lastEvictedGenerationByGeneratorAndColumn =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static boolean evictStaleSurfaceDecisionIfRelevantAndChanged(EarthGenerator generator, int columnX, int columnZ, boolean naturallyRelevant) {
        if (!naturallyRelevant) return false;
        RoadTileSource tiles = RoadNetwork.liveTileSourceForPrefetch();
        long currentGeneration = tiles == null ? 0L : tiles.resolvedGeneration();
        long columnKey = ((long) columnX << 32) ^ (columnZ & 0xFFFFFFFFL);
        java.util.concurrent.ConcurrentHashMap<Long, Long> columnsForThisGenerator;
        synchronized (lastEvictedGenerationByGeneratorAndColumn) {
            columnsForThisGenerator = lastEvictedGenerationByGeneratorAndColumn.computeIfAbsent(
                    generator, ignored -> new java.util.concurrent.ConcurrentHashMap<>());
        }
        Long previousGeneration = columnsForThisGenerator.get(columnKey);
        if (previousGeneration != null && previousGeneration.longValue() == currentGeneration) {
            return false; // nothing resolved anywhere since we last evicted this exact generator's column -- nothing to refresh
        }
        evictStaleSurfaceDecision(generator, columnX, columnZ);
        columnsForThisGenerator.put(columnKey, currentGeneration); // recorded AFTER eviction -- narrows comment 55564's "bug 2" window
        return true;
    }

    /** Test-only: clears {@link #lastEvictedGenerationByGeneratorAndColumn} so each test starts from a known, empty state. */
    static void clearEvictionTrackingForTests() {
        lastEvictedGenerationByGeneratorAndColumn.clear();
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
