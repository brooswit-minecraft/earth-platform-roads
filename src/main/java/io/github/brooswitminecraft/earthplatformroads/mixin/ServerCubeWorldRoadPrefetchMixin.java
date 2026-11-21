package io.github.brooswitminecraft.earthplatformroads.mixin;

import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.github.brooswitminecraft.earthplatformroads.road.RoadPrefetch;
import io.github.brooswitminecraft.earthplatformroads.road.SynchronousLoadGuard;
import java.time.Duration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MINECRAFT-566 comment 55474: ordinary player movement never calls {@code EarthGenerator.prepare}/
 * {@code prepareNear} -- {@code EarthGeneratorPrepareMixin}'s own (and only) hooks -- at all. The
 * real path for a player simply walking into fresh terrain is:
 * <pre>
 * ServerCubeWorld.tickCubes (server tick)
 *   -&gt; requestAround (server thread: enqueues, never fetches)
 *     -&gt; request -&gt; workers.execute (background worker pool)
 *       -&gt; produce (storage miss)
 *         -&gt; generator.generate(x, y, z, blockEntities, entities)   // no prepare hook anywhere here
 * </pre>
 * confirmed by reading the real {@code ServerCubeWorld} class (the {@code combined-73} build this
 * mod's {@code libs/earthplatform-0.1.0-alpha.73.jar} dependency actually is). Every fix the other
 * mixin's own doc describes only ever helped {@code /earth travel} teleport ({@code prepareNear}) and
 * Chunky pre-generation calling {@code prepare} per cell -- never this path, which is almost
 * certainly the real mechanism behind "a road abruptly ends" during ordinary play (a verified
 * integration gap, not a confirmed explanation for any one specific report).
 *
 * <h2>Where this hooks, and why it is safe there</h2>
 * {@code produce} is called from exactly two places in {@code ServerCubeWorld}:
 * <ul>
 *   <li>{@code request}'s {@code workers.execute} lambda -- a background worker-pool thread, NOT the
 *       server tick thread. This is the ordinary streamed-generation path, and the one this mixin
 *       targets.</li>
 *   <li>{@code loadNow} -- "Synchronous load for places that must exist this tick, such as an arrival
 *       point", documented as running on whatever thread calls it (which may be the main server
 *       thread). Comment 55474 is explicit: never do HTTP here.</li>
 * </ul>
 * Both call sites share the exact same {@code produce} method, with no parameter distinguishing
 * them. Rather than fetch here, this mixin wraps {@code loadNow} with {@link SynchronousLoadGuard}
 * (see that class's own doc for why a ThreadLocal depth counter, not a plain boolean); the
 * {@code produce} injection checks {@link SynchronousLoadGuard#isActive()} and does nothing at all
 * when active, regardless of which thread actually called {@code produce} -- so the arrival path is
 * never touched, and the one remaining caller (the background worker) is exactly the
 * ordinary-movement path this is meant to fix. {@code requestAround} and the server tick itself are
 * never touched by either injection, so this never adds HTTP (or anything else) to the server
 * thread.
 *
 * <p>The injection point inside {@code produce} is immediately BEFORE its call to
 * {@code CubeGenerator.generate(...)} -- after the storage-read fast path has already returned (a
 * saved/edited cube is read and returned above this point without ever reaching here, preserving
 * that fast path exactly as before), and before generation actually runs. This dispatches through
 * {@link RoadPrefetch#forColumnBounded} -- built on the exact same deduplicated, one-in-flight,
 * rate-limited {@code RoadTileSource} machinery {@code EarthGeneratorPrepareMixin} already uses, not
 * a second parallel mechanism -- but bounded (see {@link #PREFETCH_WAIT_BOUND} and comment 55476's
 * "bounded-worker" ask, below) rather than fully synchronous, since this specific call site is a
 * finite, shared generation-worker pool. Because this runs on the background worker thread BEFORE
 * {@code generate()}, a cell that resolves within the bound makes its data available to
 * {@code EarthGeneratorRoadMixin}'s {@code surface()} hook during THIS SAME generation call -- not a
 * retrofit of an already-saved cube, and not a second, later generation pass. A cell that stays
 * pending (not yet eligible, a genuine network failure, or simply still in flight past the bound)
 * generates with no road for this one column, exactly as every other pending/failed cell already
 * does -- never fabricated as confirmed-empty, still retryable the next time anything touches it.
 *
 * <p>{@code pregenerate} (Chunky's own direct-{@code generate()} path, a separately disclosed gap)
 * is untouched by this mixin, as is anything on the server tick thread itself.
 *
 * <h2>A real "wrong tile" bug, found by comment 55476 through pure logical review of both
 * codebases -- no Zippy access needed</h2>
 * The injected method's {@code x}/{@code z} parameters are {@code produce}'s own, which are CUBE
 * indices (confirmed by {@code loadNow}'s own {@code pos.getX() >> 4} block-to-cube shift feeding
 * that exact same parameter), not the BLOCK coordinates {@link RoadPrefetch#forColumn} actually
 * expects (the same contract {@code EarthGeneratorPrepareMixin}'s {@code prepare}/{@code prepareNear}
 * callers already honor). The first version of this mixin passed the raw cube index straight
 * through, querying entirely the wrong map tile (off by a factor of 16) on almost every call. Fixed
 * by converting to the cube's own center block coordinate, {@code (cubeX << 4) + 8} /
 * {@code (cubeZ << 4) + 8}, before dispatching -- see the injection method's own inline comment.
 *
 * <h2>The "bounded-worker" follow-up, also asked for in comment 55476</h2>
 * Two related concerns about this call site specifically (the finite, shared generation-worker
 * pool): a tall column spans many cube-Y layers, each independently missing storage and otherwise
 * each separately dispatching for the exact same cell; and whichever such call is not first would
 * simply BLOCK (on {@code RoadTileSource}'s own per-cell lock) for up to the full network timeout
 * rather than failing open quickly. Both are addressed by routing through
 * {@link RoadPrefetch#forColumnBounded} instead of the plain {@link RoadPrefetch#forColumn}
 * {@code EarthGeneratorPrepareMixin} still uses: {@code RoadTileSource.waysForCellWithBoundedWait}
 * coalesces every caller for the same still-pending cell onto ONE real fetch (keyed by cell, so
 * every cube-Y layer of one column shares it), runs that fetch's actual network wait on one
 * dedicated background thread (never a generation worker), and bounds each CALLER's own wait to
 * {@link #PREFETCH_WAIT_BOUND} before failing open -- the shared fetch keeps running regardless, so
 * a later caller still benefits once it resolves. The Moore-neighborhood lookahead dispatch is fully
 * async too ({@code enqueueLookaheadAsync}): the calling worker thread never waits on it at all.
 *
 * <h2>Known, disclosed test gap</h2>
 * No test in this repository drives the real, compiled {@code ServerCubeWorld.produce}/
 * {@code loadNow} methods themselves: doing so needs a real {@code ServerLevel}, which (unlike
 * {@code EarthGenerator}, which {@code RoadGenerationTest} and Earth Platform's own
 * {@code DataRulesBench}/{@code CalibrateBench} construct directly) this project's
 * {@code neoForge.unitTest} harness has no fixture for, and Earth Platform ships none either. The
 * logic this fix actually adds -- {@link SynchronousLoadGuard}'s thread-local arrival/non-arrival
 * distinction -- is covered directly and realistically ({@code SynchronousLoadGuardTest}, including
 * cross-thread isolation and nested enter/exit); the dispatch it gates ({@link RoadPrefetch#forColumn})
 * is the exact same code {@code RoadTilePrefetchMixinTest} already exercises through
 * {@code prepare}/{@code prepareNear}. What remains genuinely unverified is only that THIS mixin's
 * own injection points (into {@code ServerCubeWorld} itself) actually weave and fire as written --
 * NeoForge's {@code gameTestServer} run (a real dedicated server, the sanctioned mechanism for
 * exactly this kind of test) is the right tool for that and is not attempted here.
 */
@Mixin(targets = "io.earthplatform.minecraft.cubic.ServerCubeWorld")
public abstract class ServerCubeWorldRoadPrefetchMixin {
    /**
     * MINECRAFT-566 comment 55476's "bounded-worker" ask: how long THIS generation worker thread
     * may wait for a fresh fetch before failing open -- short relative to the full HTTP timeout
     * ({@code RoadNetwork.TILE_FETCH_TIMEOUT}, 20s), long enough to usually catch a healthy
     * provider's own response. See {@code RoadTileSource#waysForCellWithBoundedWait}'s own doc for
     * why a timeout here never loses or fabricates anything -- the real fetch keeps running
     * regardless, coalesced across every cube-Y layer of this same column.
     */
    private static final Duration PREFETCH_WAIT_BOUND = Duration.ofSeconds(2);

    @Shadow
    private CubeGenerator generator;

    @Inject(method = "loadNow(III)Lio/earthplatform/minecraft/cubic/Cube;", at = @At("HEAD"))
    private void earthplatformroads$enterSynchronousLoad(int x, int y, int z, CallbackInfoReturnable<?> cir) {
        SynchronousLoadGuard.enter();
    }

    @Inject(method = "loadNow(III)Lio/earthplatform/minecraft/cubic/Cube;", at = @At("RETURN"))
    private void earthplatformroads$exitSynchronousLoad(int x, int y, int z, CallbackInfoReturnable<?> cir) {
        SynchronousLoadGuard.exit();
    }

    @Inject(method = "produce(JIII)Lio/earthplatform/minecraft/cubic/ServerCubeWorld$Ready;",
            at = @At(value = "INVOKE",
                    target = "Lio/earthplatform/minecraft/cubic/gen/CubeGenerator;generate(IIILjava/util/List;Ljava/util/List;)Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private void earthplatformroads$prefetchBeforeOrdinaryGenerate(long key, int cubeX, int y, int cubeZ, CallbackInfoReturnable<?> cir) {
        if (SynchronousLoadGuard.isActive()) return; // loadNow's arrival path: never fetch here
        if (!(generator instanceof EarthGenerator earthGenerator)) return;
        // MINECRAFT-566 comment 55476, a confirmed "wrong-tile" bug in the first version of this
        // mixin: produce's x/z are CUBE indices (unpacked from CubeKey -- see loadNow's own
        // block->cube shift, `pos.getX() >> 4`, feeding the exact same parameter), but
        // RoadPrefetch.forColumn (shared with EarthGeneratorPrepareMixin, whose prepare/prepareNear
        // callers supply real BLOCK coordinates) expects block coordinates. Passing a cube index
        // straight through queried entirely the wrong map tile (off by a factor of 16) almost every
        // time. Fixed by converting to this cube's own center block coordinate before dispatching --
        // any point inside the cube is fine for a tile-granularity (CELL_DEGREES) lookup, and the
        // center avoids ever landing exactly on a tile-boundary column by construction.
        int centerBlockX = RoadPrefetch.cubeIndexToBlockCenter(cubeX);
        int centerBlockZ = RoadPrefetch.cubeIndexToBlockCenter(cubeZ);
        // MINECRAFT-566 comment 55476's "bounded-worker" ask: forColumnBounded (not the plain
        // forColumn prepare/prepareNear uses) coalesces this column's own-cell fetch across every
        // cube-Y layer and bounds this worker's own wait to PREFETCH_WAIT_BOUND, with the real
        // network wait happening on a dedicated background thread, never this one.
        RoadPrefetch.forColumnBounded(earthGenerator, centerBlockX, centerBlockZ, PREFETCH_WAIT_BOUND);
    }
}
