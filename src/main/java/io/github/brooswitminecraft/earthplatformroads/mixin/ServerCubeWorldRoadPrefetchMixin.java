package io.github.brooswitminecraft.earthplatformroads.mixin;

import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.github.brooswitminecraft.earthplatformroads.road.RoadPrefetch;
import io.github.brooswitminecraft.earthplatformroads.road.SynchronousLoadGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
 * <p><b>Confirmed on a real server (comments 55522/55524):</b> John independently verified this
 * exact, already-documented and already-tested ({@code SynchronousLoadGuardTest}) exception against
 * a real {@code loadNow} arrival -- it adopts whatever natural/pending terrain the generator already
 * has (never blocking on a fetch), makes zero extra HTTP calls on that thread, and a forced arrival
 * is therefore subject to the SAME premature-adopted-terrain risk comment 55522 found on the
 * ordinary-movement path, via this different, deliberately-unfetched entry point. This is a
 * disclosed, intentional design boundary, not a bug introduced by 55522's own fix below -- changing
 * {@code loadNow}'s own synchronous contract (returning {@code null}, or blocking it) would risk
 * every OTHER forced-load caller (portal ground selection among them), which is explicitly out of
 * scope here; see {@code docs/design.md}'s own note on this known gap.
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
 * <h2>A real stale-cache bug, found and independently reproduced by comments 55486/55487</h2>
 * {@code SurfaceGenerator} (confirmed by reading its real bytecode) permanently memoizes an entire
 * column's worth of {@code Surface} decisions in a private {@code columns} map the first time
 * anything asks for it -- including {@code lowestGround}, which {@code ColumnSurvey}/
 * {@code ServerCubeWorld.plan} calls every tick for flying-surface logic, well before this
 * column's own road data need be ready. Once cached, {@code column(int,int)} never recomputes it
 * on its own: a later, genuinely fresh {@code generate()} call for the exact same column (even
 * after road data becomes ready) hits that same stale entry and never re-invokes {@code surface()}
 * at all -- John independently reproduced this on a real server (the SAME generator instance kept
 * emitting grass after a tile became READY; only a FRESH generator instance, same seed/data,
 * correctly emitted blackstone). This meant the bounded-worker fix above, on its own, could not
 * actually guarantee correctness even when the fetch itself succeeded in time. Fixed by
 * {@link SurfaceGeneratorColumnCacheAccessor} (a new {@code @Accessor} exposing the private
 * {@code columns} map) plus {@link RoadPrefetch#surfaceGeneratorColumnKey} (replicating
 * {@code SurfaceGenerator}'s own private {@code columnKey} exactly, via the same real, public
 * fastutil {@code HashCommon.mix} it uses -- no accessor needed for that part): this injection now
 * evicts exactly the one column about to be generated, unconditionally -- narrow, per-column
 * invalidation, never a global cache clear, and free in the ordinary case (nothing to evict yet,
 * since {@code produce} only runs once per column normally). Applies to BOTH the
 * ordinary-streaming path and {@code loadNow}'s own arrival path (not gated by
 * {@link SynchronousLoadGuard}): the staleness risk is not specific to either one, and evicting a
 * map entry is not HTTP.
 *
 * <h2>A real ordering bug in that fix, found by comment 55492 through pure logical review</h2>
 * The first version evicted BEFORE dispatching/waiting on the fetch, leaving a window (up to
 * {@link #PREFETCH_WAIT_BOUND}) during which some OTHER concurrent caller -- a {@code lowestGround}
 * survey on the server tick thread, for example -- could re-populate this exact column's cache
 * with ANOTHER premature decision, undoing the eviction before {@code generate()} ever benefited
 * from it. Fixed by reordering: the dispatch/wait now runs first, and the eviction is the very
 * LAST statement in this method, immediately before falling through into {@code generate()}.
 * {@code loadNow}'s own arrival path has no wait to race against, so this reordering does not
 * affect it. <b>Known, disclosed residual race:</b> {@code SurfaceGenerator}'s own {@code column()}
 * cache fills via a plain, non-atomic get-then-compute-then-put, not {@code computeIfAbsent} -- a
 * DIFFERENT, already-in-flight concurrent computation for this exact column, started before this
 * eviction ran, can still publish its own (also premature) result AFTER this eviction, winning the
 * race anyway -- no eviction-ordering fix changes who wins that race. See the next section for how
 * this is mitigated (not eliminated).
 *
 * <h2>Bounded verify-and-retry for the residual race, comments 55496/55498/55499</h2>
 * John independently reproduced the residual race above with deliberate test-only latches forcing
 * the exact interleaving, and the real fix for it that actually eliminates the race -- moving the
 * road material decision out of {@code SurfaceGenerator}'s cache entirely, so there is nothing left
 * for a stale concurrent publish to corrupt -- was evaluated and deliberately NOT attempted:
 * {@code surface()}'s {@code Surface} record carries more than the top/under block states this
 * mixin pair already overrides, and comment 55498 named concrete further risks in reimplementing
 * that depth-spread/decoration logic externally (cached {@code Surface} data also drives cave-mouth
 * behavior and {@code StructureHost} fill) -- a blind post-{@code generate()} overwrite could
 * corrupt either, in ordinary play, not just under a deliberately engineered race. That rewrite
 * would touch the single most heavily-tested part of this whole mod and needs its own dedicated,
 * carefully-verified pass, not a same-change patch.
 *
 * <p>Instead, {@code earthplatformroads$generateWithStaleRetry} ({@code @Redirect}, replacing the
 * same {@code generate()} call this class already targeted via {@code @Inject}) calls the real
 * {@code generate()}, then verifies the result with {@link RoadPrefetch#sectionLooksStale} --
 * re-deriving, from fresh {@link io.github.brooswitminecraft.earthplatformroads.road.RoadNetwork}
 * data and {@code EarthGenerator}'s own natural {@code surfaceY}/{@code waterY} (never the
 * decorated output, per comment 55498's explicit guidance), whether every column in this cube that
 * should show a road actually does -- and if not, evicts again and retries, up to
 * {@link #MAX_STALE_RETRY_ATTEMPTS} times. This narrows the race to "recurs on every single retry
 * attempt", not "eliminated": a genuinely adversarial, continuously-racing concurrent caller could
 * still exhaust the retries, left as a known, disclosed, deliberately bounded (not unlimited, to
 * avoid an unbounded generation stall) residual gap, not claimed as fully closed.
 *
 * <p><b>Currently disabled (comments 55505/55507):</b> {@link #MAX_STALE_RETRY_ATTEMPTS} is 0 --
 * see that field's own doc for the confirmed detector false positives that made this unsafe to
 * ship as-is. Because the retry loop's condition is {@code attempt < MAX_STALE_RETRY_ATTEMPTS &&
 * RoadPrefetch.sectionLooksStale(...)}, with the bound at 0 the first half is already false and
 * Java's {@code &&} never evaluates the second -- {@code sectionLooksStale} is never even called,
 * which also satisfies comment 55507's separate ask to not run that check unconditionally on
 * every column/cube.
 *
 * <h2>A real metadata-leak bug in the retry loop, found by comment 55504 in pre-handoff review</h2>
 * The first version of the retry loop called {@code generate()} on every attempt with the SAME
 * mutable {@code blockEntities}/{@code entities} lists -- but {@code generate()}'s own
 * {@code structures.place(...)} APPENDS to those lists as a side effect (confirmed by decompiling
 * {@code SurfaceGenerator.generate} for understanding). An ABANDONED, discarded attempt's own
 * structure/entity tags stayed in those lists even though the FINAL, accepted attempt's section
 * never actually contains the blocks they describe -- a real, not hypothetical, metadata leak.
 * Fixed by giving every attempt its own fresh, empty lists (isolating each attempt's side effects
 * completely) and merging ONLY the accepted attempt's additions into the caller's real lists at the
 * very end -- the same isolation {@code produce()} itself already relies on by always passing
 * fresh empty lists in.
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

    /**
     * MINECRAFT-566 comment 55496's residual race, bounded rather than eliminated (see this
     * class's own "known, disclosed residual race" doc section): how many times
     * {@code earthplatformroads$generateWithStaleRetry} will re-evict and regenerate before giving
     * up and accepting whatever the last attempt produced.
     *
     * <p><b>Deliberately 0 for now (comments 55505/55507):</b> {@link RoadPrefetch#sectionLooksStale}
     * was confirmed to have real false positives -- a compiled worker run found it rejecting an
     * ALREADY-correctly-painted result twice more (burning 2 extra generations for nothing), and a
     * second diagnostic found it specifically at columns whose real block was legitimately dirt,
     * not actually wrong, that the detector nonetheless expected to be gravel/blackstone. This is a
     * real bug in the detector's own logic, not merely a race-timing artifact, and John explicitly
     * held the retry-enabled commit (31c67c5) back from Zippy for exactly this reason, asking for
     * "the smaller verified fallback" (this class's eviction-only behavior, matching {@code c78bf01})
     * while a safer detector (comparing the generation's own input Surface decision, or an explicit
     * stale-snapshot signal, rather than inferring staleness from final decorated blocks) is
     * designed. Set back above 0 only once that detector is actually fixed and re-verified --
     * {@link RoadPrefetch#sectionLooksStale} and the retry loop below are left in place, dormant,
     * rather than deleted, so re-enabling it is a one-line change once it is safe to.
     */
    private static final int MAX_STALE_RETRY_ATTEMPTS = 0;

    /**
     * MINECRAFT-566 comment 55522/55526: "bound deferral of only unsaved road-relevant surface
     * cubes... while geometry is actually PENDING" -- the deeper, deliberately-deferred half of
     * 55522 (the fetch-side priority/cooldown fix itself landed separately, comments 55522/55528).
     * Deliberately SEPARATE from the (currently disabled) stale-retry mechanism above: a different
     * problem (a tile genuinely never having resolved yet, not a stale per-column cache entry), with
     * a different, direct detector ({@link RoadPrefetch#isPendingForColumn}, never the indirect
     * "compare decorated output against fresh geometry" approach that mechanism's own detector was
     * found to have false positives in).
     *
     * <p>Only ever runs for the ordinary-streaming path (never {@code loadNow}'s own arrival --
     * "keep forced loads as an explicit separate control", per comment 55526 explicitly), only ever
     * when {@link RoadPrefetch#hasLiveTileSource} (an explicit gate, per comment 55532 -- there is
     * nothing to defer for if no live source is even configured), only for a cube whose own Y-range
     * actually {@link RoadPrefetch#cubeContainsNaturalSurface} (a road is only ever painted AT a
     * column's own natural height; a cube that cannot possibly contain it has nothing to gain from
     * deferring), and only ever on a cube THIS generation call is producing fresh -- {@code produce}'s
     * own storage-read fast path above this injection point means an already-saved cube never
     * reaches here at all, so this can never retrofit one.
     *
     * <p><b>Comment 55532, a real race found by John's own native reproduction:</b> the first
     * version of this fix checked {@code RoadPrefetch.isPendingForColumn} only AFTER the accepted
     * {@code generate()} call had already run -- but the tile can transition PENDING -&gt; READY
     * literally DURING that one call (observed concretely: the tile became READY barely 91ms after
     * the accepted attempt had already produced grass; the post-hoc pending check then correctly,
     * but uselessly, reported "not pending", so the retry loop's own precondition was already false
     * and it never fired at all -- the stale grass was accepted and saved). Fixed by snapshotting
     * readiness IMMEDIATELY BEFORE the accepted attempt instead of inferring it from decorated
     * blocks afterward: if the snapshot says PENDING, this unconditionally gives the cell one more
     * bounded chance to resolve, regardless of what the state looks like by the time this runs --
     * handling the race window rather than missing it.
     *
     * <p>Bounded two ways at once: a real wall-clock window ({@link #PENDING_DEFER_WINDOW}, the
     * REMAINING portion of which is passed to the re-dispatch -- not a fresh full
     * {@link #PREFETCH_WAIT_BOUND} every time, which comment 55532 also flagged as a real
     * inconsistency with this window's own "total, not per-attempt" intent) and a single retry
     * attempt (once the cell resolves and this regenerates once, there is nothing further to gain
     * from retrying again). Either bound running out, or the wait coming back still pending, means
     * an explicit, deliberate fail-open: accept whatever the last attempt produced, exactly like any
     * other pending/failed cell already does, never an unbounded stall of this generation worker
     * thread.
     *
     * <p><b>Comment 55534, a real gap in the 55532 fix itself:</b> John's own native checks found
     * two more real issues. First, the deadline was computed AFTER the first accepted
     * {@code generate()} call had already returned, so this window only ever bounded the RETRY's
     * own slice, not the method's real total extra time (initial dispatch wait + first
     * {@code generate()} + retry wait) -- observed concretely: a held response failed open at
     * 4.26s against this "3s" window. Fixed by capturing the deadline once, before the INITIAL
     * dispatch, so this window genuinely is the aggregate budget it was always meant to be. Second,
     * the initial dispatch itself (not just this retry) now shares the exact same
     * {@code hasLiveTileSource}/{@code cubeContainsNaturalSurface} gate -- the first version of
     * that gate only ever covered this retry, so a deep/sky cube (Y-irrelevant) still sent its own
     * initial fetch POST, and a cache-only source (no live-fetch capability, but still non-null)
     * still entered this method's wait scheduling despite sending zero POSTs. See
     * {@code RoadPrefetch.hasLiveTileSource}'s own updated doc for the cache-only distinction.
     */
    private static final Duration PENDING_DEFER_WINDOW = Duration.ofSeconds(3);

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

    @Redirect(method = "produce(JIII)Lio/earthplatform/minecraft/cubic/ServerCubeWorld$Ready;",
            at = @At(value = "INVOKE",
                    target = "Lio/earthplatform/minecraft/cubic/gen/CubeGenerator;generate(IIILjava/util/List;Ljava/util/List;)Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection earthplatformroads$generateWithStaleRetry(
            CubeGenerator cubeGenerator, int cubeX, int cubeY, int cubeZ, List<CompoundTag> blockEntities, List<CompoundTag> entities) {
        if (!(cubeGenerator instanceof EarthGenerator earthGenerator)) {
            return cubeGenerator.generate(cubeX, cubeY, cubeZ, blockEntities, entities);
        }
        // MINECRAFT-566 comment 55492, a real bug in the first version of this fix: evicting
        // BEFORE the bounded wait below left a window (up to PREFETCH_WAIT_BOUND) during which
        // some OTHER concurrent caller -- a lowestGround survey on the server tick thread, for
        // example -- could re-populate this exact column's cache with ANOTHER premature decision,
        // undoing the eviction before generate() ever benefited from it. The dispatch/wait must
        // run FIRST; the eviction must be the very LAST thing before calling the real generate().
        // MINECRAFT-566 comment 55476, a confirmed "wrong-tile" bug in the first version of this
        // mixin: produce's x/z are CUBE indices (unpacked from CubeKey -- see loadNow's own
        // block->cube shift, `pos.getX() >> 4`, feeding the exact same parameter), but
        // RoadPrefetch.forColumn (shared with EarthGeneratorPrepareMixin, whose prepare/prepareNear
        // callers supply real BLOCK coordinates) expects block coordinates. Passing a cube index
        // straight through queried entirely the wrong map tile (off by a factor of 16) almost every
        // time. Fixed by converting to this cube's own center block coordinate before dispatching --
        // any point inside the cube is fine for a tile-granularity (CELL_DEGREES) lookup, and the
        // center avoids ever landing exactly on a tile-boundary column by construction. Computed
        // unconditionally (cheap integer math) since the pending-defer retry loop near the end of
        // this method needs the same center point too.
        int centerBlockX = RoadPrefetch.cubeIndexToBlockCenter(cubeX);
        int centerBlockZ = RoadPrefetch.cubeIndexToBlockCenter(cubeZ);
        // MINECRAFT-566 comment 55534: John's own native checks found the INITIAL dispatch below
        // sending its own POST for a deep/sky cube whose Y-range can never contain a road (only the
        // later defer snapshot was Y-gated), and a cache-only source (no live-fetch capability, but
        // still a non-null RoadTileSource) sending zero POSTs yet still entering this method's wait
        // scheduling. Both gates below are the SAME ones the defer snapshot already uses -- computed
        // once, here, and reused for both so the two can never drift apart.
        boolean liveAndRelevant = RoadPrefetch.hasLiveTileSource()
                && RoadPrefetch.cubeContainsNaturalSurface(earthGenerator, cubeX, cubeY, cubeZ);
        // MINECRAFT-566 comment 55534: "the reported 3s is not an aggregate worker bound" -- the
        // prior version computed this deadline AFTER the first accepted generate() call returned,
        // so PENDING_DEFER_WINDOW only ever bounded the retry's OWN slice, not the real total extra
        // time this method can spend (initial dispatch wait + first generate() + retry wait), which
        // is why a held response was observed failing open at 4.26s against a nominal 3s window.
        // Captured here, before the initial dispatch, so the window is a genuine aggregate budget.
        Instant deferDeadline = Instant.now().plus(PENDING_DEFER_WINDOW);
        if (!SynchronousLoadGuard.isActive() && liveAndRelevant) { // loadNow's arrival path: never fetch here
            // MINECRAFT-566 comment 55476's "bounded-worker" ask: forColumnBounded (not the plain
            // forColumn prepare/prepareNear uses) coalesces this column's own-cell fetch across every
            // cube-Y layer and bounds this worker's own wait to PREFETCH_WAIT_BOUND, with the real
            // network wait happening on a dedicated background thread, never this one.
            RoadPrefetch.forColumnBounded(earthGenerator, centerBlockX, centerBlockZ, PREFETCH_WAIT_BOUND);
        }
        // MINECRAFT-566 comment 55486/55487, a real, independently-reproduced staleness bug found
        // in Earth Platform's own code: SurfaceGenerator permanently memoizes this column's whole
        // Surface[] decision the first time ANYTHING asks for it (lowestGround/ColumnSurvey, called
        // every tick for flying logic, well before road data need be ready) -- once cached, this
        // very generate() call about to run would hit that stale entry and never re-invoke
        // surface() at all, no matter how fresh the road data now is. Evict just this one column
        // (never a global clear), unconditionally -- this is NOT gated by SynchronousLoadGuard,
        // since it is not HTTP and the exact same staleness risk applies equally to loadNow's own
        // arrival generate() call. Free in the ordinary case: produce() only ever runs once per
        // column, so there was nothing legitimate to evict yet.
        RoadPrefetch.evictStaleSurfaceDecision(earthGenerator, cubeX, cubeZ);
        // MINECRAFT-566 comment 55504, a real bug found in pre-handoff review: every retry attempt
        // below was calling generate() with the SAME mutable blockEntities/entities lists -- but
        // generate()'s own structures.place(...) APPENDS to those lists as a side effect (confirmed
        // by decompiling SurfaceGenerator.generate for understanding). An ABANDONED (discarded,
        // stale) attempt's structure/entity tags would stay in those lists even though the FINAL,
        // accepted attempt's section never actually contains the blocks they describe -- a real
        // metadata leak, not just a hypothetical one. Fixed by giving EVERY attempt its OWN fresh,
        // empty lists (isolating each attempt's side effects completely) and merging ONLY the
        // accepted attempt's additions into the caller's real lists at the very end -- the same
        // isolation `produce()` itself already relies on by always passing fresh empty lists in.
        // MINECRAFT-566 comment 55532: this snapshot MUST be taken immediately BEFORE the accepted
        // attempt below, not inferred afterward from decorated blocks or re-checked only once that
        // attempt has already run -- the tile can resolve PENDING -> READY literally DURING that
        // one generate() call (John's own native reproduction: the tile became READY barely 91ms
        // after the accepted attempt had already produced grass), and a check made only after the
        // fact would correctly, but uselessly, see "not pending" and never retry at all. See
        // PENDING_DEFER_WINDOW's own doc for the full reasoning.
        boolean wasPendingBeforeAcceptedAttempt = !SynchronousLoadGuard.isActive()
                && liveAndRelevant
                && RoadPrefetch.isPendingForColumn(earthGenerator, centerBlockX, centerBlockZ);
        List<CompoundTag> attemptBlockEntities = new ArrayList<>();
        List<CompoundTag> attemptEntities = new ArrayList<>();
        LevelChunkSection section = cubeGenerator.generate(cubeX, cubeY, cubeZ, attemptBlockEntities, attemptEntities);
        // MINECRAFT-566 comment 55496, a real, independently-reproduced residual race (see this
        // class's own "known, disclosed residual race" doc section): a DIFFERENT, already-in-flight
        // concurrent column() computation, started before the eviction above ran, can still publish
        // its own (also premature) result into the same now-empty slot before generate()'s own read
        // of it -- no eviction-ordering fix changes who wins that race. Rather than reimplement
        // SurfaceGenerator's own cached depth-spread/decoration logic to move the road decision out
        // of its cache entirely (comment 55498: real additional risk there -- cached Surface data
        // also drives cave-mouth behavior and structure fill, which a blind post-generate overwrite
        // could corrupt), this instead verifies the result against what fresh road data says it
        // should contain and retries (re-evict, regenerate) a small, bounded number of times.
        for (int attempt = 0; attempt < MAX_STALE_RETRY_ATTEMPTS
                && RoadPrefetch.sectionLooksStale(earthGenerator, cubeX, cubeY, cubeZ, section); attempt++) {
            RoadPrefetch.evictStaleSurfaceDecision(earthGenerator, cubeX, cubeZ);
            attemptBlockEntities = new ArrayList<>(); // discard the abandoned attempt's own additions entirely
            attemptEntities = new ArrayList<>();
            section = cubeGenerator.generate(cubeX, cubeY, cubeZ, attemptBlockEntities, attemptEntities);
        }
        // MINECRAFT-566 comment 55522/55526/55532's deferred "pending surface-cube" half: fires
        // only when the SNAPSHOT taken above (before the accepted attempt) says this cell was
        // genuinely pending -- never re-derived from the current state here, for exactly the race
        // reason that snapshot's own doc explains. Re-dispatches the SAME cell (coalesced via
        // RoadTileSource's own in-flight map, so this never sends a second real request while the
        // first is still outstanding), passing the REMAINING portion of PENDING_DEFER_WINDOW (never
        // a fresh full PREFETCH_WAIT_BOUND -- this window is a total budget, not per-attempt), and
        // only if that wait actually resolved it, evicts and regenerates once -- an EXPLICIT,
        // deliberate fail-open the moment the window is already out, or the wait comes back still
        // pending: accept whatever the last attempt produced, exactly like any other pending/failed
        // cell already does, never an unbounded stall of this generation worker thread. A single
        // retry attempt is enough: once resolved and regenerated, there is nothing further to gain.
        if (wasPendingBeforeAcceptedAttempt) {
            Instant now = Instant.now();
            if (now.isBefore(deferDeadline)) {
                Duration remaining = Duration.between(now, deferDeadline);
                Duration thisWait = remaining.compareTo(PREFETCH_WAIT_BOUND) < 0 ? remaining : PREFETCH_WAIT_BOUND;
                RoadPrefetch.forColumnBounded(earthGenerator, centerBlockX, centerBlockZ, thisWait);
                if (!RoadPrefetch.isPendingForColumn(earthGenerator, centerBlockX, centerBlockZ)) {
                    RoadPrefetch.evictStaleSurfaceDecision(earthGenerator, cubeX, cubeZ);
                    attemptBlockEntities = new ArrayList<>(); // discard the abandoned (premature) attempt's own additions entirely
                    attemptEntities = new ArrayList<>();
                    section = cubeGenerator.generate(cubeX, cubeY, cubeZ, attemptBlockEntities, attemptEntities);
                } // else: still pending after waiting again -- explicit bounded fail-open, do not spin further
            } // else: window already out before even trying again -- explicit bounded fail-open
        }
        blockEntities.addAll(attemptBlockEntities);
        entities.addAll(attemptEntities);
        return section;
    }
}
