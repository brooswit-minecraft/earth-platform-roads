package io.github.brooswitminecraft.earthplatformroads.mixin;

import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.github.brooswitminecraft.earthplatformroads.road.RoadDataNotReadyException;
import io.github.brooswitminecraft.earthplatformroads.road.RoadPrefetch;
import io.github.brooswitminecraft.earthplatformroads.road.SynchronousLoadGuard;
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
 * a second parallel mechanism -- but bounded (see comment 55476's "bounded-worker" ask, below;
 * comment 55545 later changed what happens once that bound is reached -- see that section) rather
 * than fully synchronous, since this specific call site is a
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
 * dedicated background thread (never a generation worker), and bounds each CALLER's own wait (at
 * the time of this writing, comment 55545 changed this call site's own bound to zero -- see that
 * section for why) -- the shared fetch keeps running regardless, so
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
 * The first version evicted BEFORE dispatching/waiting on the fetch, leaving a window during which
 * some OTHER concurrent caller -- a {@code lowestGround}
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
 *
 * <h2>A direction change superseding the "bounded fail-open to grass" design above (comment 55545)</h2>
 * Comments 55522 through 55535 (above) all shared one premise: once some bound was reached (a
 * fixed window, later a dynamic one), this injection would accept whatever {@code generate()}
 * produced -- including bare/wrong terrain for a column whose real road data simply hadn't
 * resolved yet -- and that result would be saved, permanently, by {@code produce}'s own normal
 * path. John's own explicit direction change: "the mod should keep waiting for needed map data...
 * no timeout/cooldown/outage alone should adopt or permanently save bare terrain." This supersedes
 * every bounded-fail-open behavior described above for this specific mechanism (the UNRELATED
 * stale-{@code SurfaceGenerator}-cache retry loop, {@link #MAX_STALE_RETRY_ATTEMPTS}, is untouched
 * -- a different problem, see its own sections above).
 *
 * <p><b>The mechanism:</b> rather than accept-then-maybe-retry-once, this injection now dispatches
 * a fire-and-forget fetch for every real cell this cube's own relevant footprint intersects
 * ({@link RoadPrefetch#dispatchRelevantCellFetches}; comment 55542's own direction -- the actual
 * intersecting cells, never a speculative halo) and, if ANY of them is still genuinely
 * {@link io.github.brooswitminecraft.earthplatformroads.road.RoadTileSource.CellState#PENDING}
 * ({@link RoadPrefetch#isAnyRelevantCellPending}), throws
 * {@link io.github.brooswitminecraft.earthplatformroads.road.RoadDataNotReadyException} INSTEAD OF
 * ever calling the real {@code generate()} at all. {@code ServerCubeWorld.produce}'s own
 * surrounding {@code try/catch (RuntimeException failure)} (confirmed by reading the real,
 * decompiled class) turns that into a {@code Ready} result with a {@code null} section; its own
 * {@code adoptReady} does NOT create a {@code Cube} and does NOT touch storage at all, and
 * schedules {@code retryAt.put(key, ticks + 100)} (roughly 5 real-world seconds at 20 TPS) -- the
 * NEXT ordinary tick-driven {@code request} pass naturally re-attempts this exact cube later, on
 * whatever worker thread happens to pick it up then. See {@code RoadDataNotReadyException}'s own
 * doc for the full mechanism and why it is Earth Platform's OWN existing, already-native retry
 * machinery, not something this mod built: this mixin never manages retry scheduling, in-flight
 * resource bounds, or discarding no-longer-wanted cubes -- {@code ServerCubeWorld}'s own
 * {@code retryAt}/{@code pending}/{@code wanted} fields already do every one of those, for every
 * OTHER kind of generation failure too.
 *
 * <p>Because this throws BEFORE ever calling {@code generate()} when relevant data is pending, the
 * entire "snapshot readiness before the accepted attempt" race (comments 55532/55534/55537/55539)
 * is now structurally moot for this mechanism: there is no "already-accepted, possibly-premature"
 * attempt to race against, because none is ever produced while relevant data is still pending.
 * There is also no fixed or dynamic defer budget to size (comments 55534/55535/55544's entire
 * "how long should this generation worker wait" question): this call site never waits at all
 * anymore -- the fetch is dispatched and this returns (by throwing) immediately either way.
 *
 * <p><b>Comment 55545 explicitly accepts the consequence:</b> "repeated healthy-domain outage/429/
 * 500 keeps the requested cube absent from durable storage... recovers when valid result returns" --
 * a sustained provider outage no longer causes a fallback to grass at all; the cube simply keeps
 * being retried (by Earth Platform's own scheduler, at its own pace) until either the data resolves
 * or the player no longer wants that cube (Earth Platform's own {@code wanted}/{@code droppedKeys}
 * mechanism, already native, already handles that). {@code RoadTileSource.sustainedOutage()} (used
 * by an earlier, now-superseded version of this mechanism, and still used elsewhere by
 * {@code RoadNetwork.diagnoseColumn}'s own distinct diagnostic) is no
 * longer consulted by this injection at all for that reason -- waiting longer cannot make a
 * genuinely down provider resolve sooner, but per this direction change, giving up and showing
 * wrong terrain is no longer an acceptable alternative either, so there is nothing left for that
 * signal to usefully change here. Once {@code CellState.CONFIRMED_EMPTY} (a genuinely resolved "no
 * road here"), generation proceeds immediately and normally -- that is legitimate natural terrain,
 * not something to keep waiting on.
 *
 * <p><b>{@code loadNow}'s own forced-arrival contract, addressed explicitly rather than left
 * implicit:</b> John's own framing -- "needs an explicit policy before claiming full coverage, not
 * a blanket null or silent save of provisional grass." {@code loadNow} cannot defer or retry at all:
 * its caller (an arrival point, a portal, or similar) needs a {@code Cube} back on this exact call,
 * synchronously, this tick -- there is no "come back next tick" option available to it the way
 * {@code request}'s own worker-pool path has via {@code retryAt}. The deliberate, explicit policy
 * (unchanged in behavior from every earlier comment in this class, now stated as a policy rather
 * than left as an open question): {@link SynchronousLoadGuard} gates this entire mechanism
 * (dispatch, pending-check, throw) out of {@code loadNow} completely -- a forced arrival always
 * adopts whatever natural or already-resolved terrain the generator has for that exact column,
 * right now, never blocking and never throwing, exactly as {@code SynchronousLoadGuardTest} already
 * covers. A forced arrival CAN still show provisional (pending) terrain at the one column it
 * arrives on, by this explicit design choice -- the alternative (blocking an arrival indefinitely,
 * or returning {@code null} and risking every OTHER forced-load caller, portal ground selection
 * among them) was evaluated and rejected as worse, not overlooked. This is the SAME disclosed
 * design boundary documented earlier in this class's own doc (comments 55522/55524) -- restated
 * here as an explicit policy statement per John's own request, not a behavior change.
 *
 * <p><b>Known, disclosed limitation of this exact candidate:</b> no test in this repository drives
 * the real {@code ServerCubeWorld.produce}/{@code adoptReady}/{@code retryAt} pipeline -- the same
 * disclosed harness gap named earlier in this class's own doc. What IS verified directly: the two
 * new {@code RoadPrefetch} methods against real {@code RoadTileSource.CellState}s and real
 * synthetic fixtures, and that {@code RoadDataNotReadyException} is a plain {@code RuntimeException}
 * (confirmed, by reading the decompiled class, to be exactly the type {@code produce}'s own
 * {@code catch} clause is declared to catch). What is NOT independently reproduced here: that
 * Earth Platform's own {@code retryAt}/{@code adoptReady} pipeline actually behaves as the
 * decompiled source describes end to end on a real, running server -- John's own native
 * infrastructure is what will confirm that, as with every other fix in this class.
 */
@Mixin(targets = "io.earthplatform.minecraft.cubic.ServerCubeWorld")
public abstract class ServerCubeWorldRoadPrefetchMixin {
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
        // MINECRAFT-566 comment 55545, superseding every earlier bounded-fail-open version of this
        // check (comments 55522 through 55544 -- see this class's own "direction change" doc
        // section for the full history): never call generate() AT ALL while a real cell this cube's
        // own relevant footprint depends on is still genuinely PENDING -- dispatch (fire-and-forget,
        // never waited on) and throw instead, letting Earth Platform's own native
        // retryAt/adoptReady machinery defer this exact cube to a later tick, on its own schedule,
        // without this worker thread blocking at all. SynchronousLoadGuard still excludes loadNow's
        // own arrival path entirely -- see that section's own explicit policy statement.
        // MINECRAFT-566 comment 55562: the three separate full-cube scans this gate used to run
        // (cubeContainsNaturalSurface, then dispatchRelevantCellFetches, then isAnyRelevantCellPending
        // -- each independently re-deriving generator.surfaceY for all 256 columns, the pending
        // check alone calling RoadTileSource.cellState up to 256 times) are now ONE pass via
        // RoadPrefetch.dispatchAndScanRelevantCells -- see that method's own doc. SynchronousLoadGuard
        // must still exclude ALL dispatch/pending-check/HTTP from loadNow's own arrival path exactly
        // as before, so the cheap, dispatch-free RoadPrefetch.cubeContainsNaturalSurface is used
        // instead whenever the guard is active (or there is no live tile source to dispatch through
        // at all) -- this changes nothing about WHAT ran under those conditions, only unifies how
        // relevance is computed afterward for the eviction gate below.
        boolean naturallyRelevant;
        if (!SynchronousLoadGuard.isActive() && RoadPrefetch.hasLiveTileSource()) {
            RoadPrefetch.RelevantCellScanResult scan = RoadPrefetch.dispatchAndScanRelevantCells(earthGenerator, cubeX, cubeY, cubeZ);
            naturallyRelevant = scan.naturallyRelevant();
            if (scan.anyRelevantCellPending()) {
                throw new RoadDataNotReadyException(
                        "road data still pending for cube (" + cubeX + "," + cubeY + "," + cubeZ + ")");
            }
        } else {
            naturallyRelevant = RoadPrefetch.cubeContainsNaturalSurface(earthGenerator, cubeX, cubeY, cubeZ);
        }
        // MINECRAFT-566 comment 55486/55487, a real, independently-reproduced staleness bug found
        // in Earth Platform's own code: SurfaceGenerator permanently memoizes this column's whole
        // Surface[] decision the first time ANYTHING asks for it (lowestGround/ColumnSurvey, called
        // every tick for flying logic, well before road data need be ready) -- once cached, this
        // very generate() call about to run would hit that stale entry and never re-invoke
        // surface() at all, no matter how fresh the road data now is. Evict just this one column
        // (never a global clear) -- NOT gated by SynchronousLoadGuard (not HTTP; the exact same
        // staleness risk applies equally to loadNow's own arrival generate() call), but now gated by
        // relevance alone per comment 55562 -- see RoadPrefetch.evictStaleSurfaceDecisionIfRelevant's
        // own doc for why an unconditional eviction, on every unsaved Y cube regardless of relevance,
        // was itself the real performance bug this whole slice exists to fix, AND for why that same
        // doc's "skip for an unchanged sibling" follow-on optimization was tried and then reverted
        // (comment 55565: a real correctness regression in the already-disclosed residual
        // publish-race, comment 55496) -- this call always evicts a relevant column, exactly
        // matching the original design's own self-healing freshness guarantee.
        RoadPrefetch.evictStaleSurfaceDecisionIfRelevant(earthGenerator, cubeX, cubeZ, naturallyRelevant);
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
        blockEntities.addAll(attemptBlockEntities);
        entities.addAll(attemptEntities);
        return section;
    }
}
