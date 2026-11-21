package io.github.brooswitminecraft.earthplatformroads.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.github.brooswitminecraft.earthplatformroads.road.RoadPrefetch;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * MINECRAFT-566 comment 55551, John's own next explicitly separate forced-caller slice: Earth
 * Platform geographic travel (its own {@code /earth travel} arrival queue) only -- never generic
 * {@code loadNow}, portal/respawn, or Sable, all of which stay deliberately out of scope here.
 *
 * <h2>The existing, already-native arrival wait -- confirmed by reading the real, decompiled
 * {@code CubicEvents} class</h2>
 * {@code CubicEvents.tick} calls {@code arrive}, which walks its own static {@code ARRIVALS} queue
 * every server tick:
 * <pre>
 * if (!cubes.readyAround(pos) &amp;&amp; arrival.waited &lt; 200) {
 *     ARRIVALS.set(i, new Arrival(..., arrival.waited + 1, ...));
 *     continue;
 * }
 * ARRIVALS.remove(i);
 * Vec3 stand = standOn(cubes, arrival.x, arrival.z); // standOn's own first statement: cubes.loadAround(pos)
 * ...teleportTo(...); tellPlace(...);
 * </pre>
 * {@code readyAround} is a cheap, non-blocking check of whether the destination's 3x3x3 cube
 * neighborhood is already generated and in memory (never forces generation). If not ready, and
 * the arrival has waited fewer than 200 ticks (~10s at 20 TPS, {@code ARRIVAL_PATIENCE}), it just
 * increments {@code waited} and tries again next tick -- already an async, never-blocks-a-thread
 * pattern. Only once ready, or that patience exhausts, does {@code standOn} run, whose own first
 * statement, {@code loadAround}, is Earth Platform's own SYNCHRONOUS, forcing generation fallback.
 *
 * <p><b>The gap:</b> {@code readyAround} only ever asks whether the destination's TERRAIN exists in
 * memory -- it has no idea whether this mod's own road data for that same footprint has resolved.
 * A destination whose terrain generates quickly but whose road tile is still pending falls through
 * the unrelated 200-tick clock into the same forced-adopt-and-permanently-save bug comment 55549
 * already confirmed natively for ordinary movement, now for a real geographic-travel teleport.
 *
 * <h2>Comment 55556: John's independent review rejected the first two attempts at this fix</h2>
 * The first version ({@code @Redirect} on {@code readyAround}/{@code List.set} only) missed that
 * {@code arrive}'s own road-pending check never ran at all once {@code waited} had already reached
 * 200 (the {@code &amp;&amp;} short-circuits past it) -- comment 55554's own finding. The SECOND
 * version ({@code @Redirect} gating {@code standOn}'s own {@code loadAround} call directly) still
 * let {@code standOn} run its own subsequent search/mutation logic (block reads, occasional
 * {@code clearLeaves}/{@code setBlockAndUpdate} calls) and {@code tellPlace}'s own arrival message
 * -- comment 55556's own rejection: "do not ship the partial fall-through... leaves block reads,
 * clearLeaves/writes and success/map messages live." It ALSO used a bare global static field for
 * {@code roadHeld} ("the global hold flag also leaks beyond the command invocation") and an
 * UNSCOPED {@code List.remove(int)} redirect that caught Earth Platform's own PRECEDING
 * disconnected-player cleanup too (a real, distinct call to {@code ARRIVALS.remove(i)} earlier in
 * the SAME method, for {@code arrival.player.isRemoved()} -- "the unqualified List.remove hook
 * catches the preceding disconnected-player cleanup").
 *
 * <h2>The actual fix: make the comparison itself stay true, never reach the fallback branch at all</h2>
 * Per John's own exact prescription, using MixinExtras (confirmed registered in his own native
 * fixture, and already present on this project's own classpath transitively via
 * {@code mixinextras-neoforge:0.5.5}):
 *
 * <ul>
 *   <li>{@link #earthplatformroads$readyAroundConsideringRoadData}, a {@code @WrapOperation} on
 *       {@code ServerCubeWorld.readyAround(BlockPos)}: calls the real method via {@code operation}
 *       (never replacing it blindly), then additionally checks
 *       {@link RoadPrefetch#isAnyRelevantCellPendingNearBlock} across the SAME 3x3x3 cube
 *       neighborhood {@code readyAround}/{@code loadAround} both traverse (confirmed by John's own
 *       source review, comment 55552), dispatching
 *       ({@link RoadPrefetch#dispatchRelevantCellFetchesNearBlock}, fire-and-forget) so the fetch
 *       actually gets a chance to happen. Writes the combined "is this arrival road-held" answer
 *       into a {@code @Share}'d {@link LocalBooleanRef} -- a PER-INVOCATION of {@code arrive}
 *       value MixinExtras allocates fresh each call and threads through every injector below that
 *       also declares the same {@code @Share("roadHeld")} parameter, never a global static field
 *       (comment 55556's own "global hold flag leaks" finding, fixed).</li>
 *   <li>{@link #earthplatformroads$clampWaitedComparisonWhileRoadHeld}, a
 *       {@code @ModifyExpressionValue} on the EXACT {@code CubicEvents.Arrival.waited} field read
 *       John's own {@code javap} against the real, exact {@code combined-73} class confirmed is
 *       ordinal 0 (the one feeding the {@code &lt; 200} comparison, distinct from ordinal 1, the
 *       increment): returns {@code 0} instead of the real value while road-held, so
 *       {@code arrival.waited &lt; 200} stays {@code true} REGARDLESS of how large the real counter
 *       has actually grown. Combined with the {@code readyAround} wrap above (which also returns
 *       {@code false} while road-held), the ORIGINAL "keep waiting" branch is taken NATURALLY --
 *       {@code standOn}, {@code loadAround}, {@code teleportTo}, and {@code tellPlace} are never
 *       reached AT ALL while road-held, eliminating every one of comment 55556's "partial
 *       fall-through" side effects at the source, rather than trying to gate each of them
 *       individually afterward. Never references the private {@code CubicEvents.Arrival} type
 *       itself -- only the {@code int} value read from its field, which MixinExtras hands to this
 *       method directly.</li>
 * </ul>
 *
 * <p>The real {@code waited} counter itself is deliberately left to increment normally even while
 * road-held (the {@code List.set} call that persists it is NOT wrapped) -- John's own framing
 * ("use {@code @WrapOperation} for {@code List.set} only... IF NEEDED") leaves this optional, and
 * it genuinely is not needed here: {@code waited} is used nowhere else in {@code arrive} except
 * this one comparison (now permanently clamped while held) and its own increment, so letting the
 * real counter keep growing has no observable effect (an {@code int} overflow at this rate is
 * several million years away). {@code List.remove}/{@code standOn}/{@code loadAround}/
 * {@code teleportTo}/{@code tellPlace} are NOT touched by this mixin at all anymore -- there is
 * nothing left to gate once the comparison itself never lets the method reach them.
 *
 * <h2>Comment 55554's "check actual intersecting map cells, not only nine cube centers"</h2>
 * {@link RoadPrefetch#isAnyRelevantCellPendingNearBlock}/{@code dispatchRelevantCellFetchesNearBlock}
 * no longer sample cube-center columns at all (that reintroduced the exact 55537/55542
 * "outer-strip" bug class -- a cube can straddle a real cell boundary). They now compute the
 * ACTUAL set of map cells the 3x3 cube X/Z neighborhood intersects directly, from just the 4
 * corners of that neighborhood's own block-coordinate extent converted to lat/lon -- see
 * {@code RoadPrefetch.intersectingCells}'s own doc for the full reasoning. Cheap (typically 1 to 4
 * cells, since one real map cell is far larger than this whole 48-block-wide neighborhood) and
 * correct (catches a boundary cutting through the middle of the neighborhood, not just its center).
 *
 * <h2>Explicitly out of scope, per John's own instruction</h2>
 * Generic {@code loadNow} (every OTHER forced caller: a forced arrival on the ordinary-streaming
 * path, portal placement, respawn, Sable) is untouched -- {@code SynchronousLoadGuard} and this
 * whole class's own gating remain exactly as documented in
 * {@code ServerCubeWorldRoadPrefetchMixin}'s own class doc. No server-thread HTTP is added here
 * either: both new {@code RoadPrefetch} calls this mixin makes are the same non-blocking,
 * fire-and-forget/cache-read primitives the ordinary-movement mechanism already uses, called from
 * the tick thread exactly the way {@code readyAround} itself already is.
 *
 * <h2>Known, disclosed limitation of this exact candidate</h2>
 * No test in this repository drives the real, compiled {@code CubicEvents.arrive} method itself,
 * for the same reason no test drives {@code ServerCubeWorld.produce} directly -- see
 * {@code ServerCubeWorldRoadPrefetchMixin}'s own "known, disclosed test gap" section. The new
 * {@code RoadPrefetch} methods this redirect calls are covered directly instead. Whether
 * MixinExtras's {@code @Share}/{@code @WrapOperation}/{@code @ModifyExpressionValue} actually weave
 * and fire as written against the real, compiled {@code CubicEvents} class is not verified in this
 * harness either -- John's own described native boundary fixture (seed a real arrival at
 * {@code waited = 199} with a cold loopback tile, instrument {@code standOn} to confirm it does not
 * run at tick 200 while pending, and does run once after the footprint is adopted, plus removed-
 * player and direct-spawn controls) is exactly the right test for this and needs his own native
 * infrastructure to confirm.
 */
@Mixin(targets = "io.earthplatform.minecraft.cubic.CubicEvents")
public abstract class CubicEventsArrivalRoadGateMixin {
    @WrapOperation(method = "arrive(Lnet/minecraft/server/level/ServerLevel;Lio/earthplatform/minecraft/cubic/ServerCubeWorld;)V",
            at = @At(value = "INVOKE",
                    target = "Lio/earthplatform/minecraft/cubic/ServerCubeWorld;readyAround(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean earthplatformroads$readyAroundConsideringRoadData(
            ServerCubeWorld cubes, BlockPos pos, Operation<Boolean> operation,
            @Share("roadHeld") LocalBooleanRef roadHeld) {
        boolean terrainReady = operation.call(cubes, pos);
        if (terrainReady) {
            // MINECRAFT-566 comment 55558, release blocker 2: if the real readyAround already says
            // every cube in this neighborhood exists, there is no FRESH terrain at stake at all --
            // nothing here is about to be generated-and-saved for the first time, so there is
            // nothing this mod's own defer could protect by holding, and no reason to pay for the
            // road-pending check or dispatch at all. Must return before either.
            roadHeld.set(false);
            return true;
        }
        CubeGenerator generator = cubes.generator();
        boolean roadPending = generator instanceof EarthGenerator earthGenerator
                && RoadPrefetch.hasLiveTileSource()
                && isMissingRelevantRoadPendingAndDispatch(cubes, earthGenerator, pos);
        roadHeld.set(roadPending);
        return false; // terrainReady is already known false here
    }

    /**
     * MINECRAFT-566 comment 55560: John's own independent native verification found a real,
     * confirmed bug in the previous version of this gate -- it qualified relevance against only the
     * CENTER cube, then (if relevant) checked/dispatched road data for the WHOLE 3x3 neighborhood's
     * bounding box regardless of which of the other 26 cubes actually needed anything. His own
     * repro: remove just one upper cube from the neighborhood, prove it has no natural surface, and
     * the command still stays queued and issues one unnecessary POST purely because it shares the
     * neighborhood with the relevant, missing center cube. Fixed by delegating to
     * {@link RoadPrefetch#isAnyMissingRelevantCellPendingNearBlock}/
     * {@code dispatchMissingRelevantCellFetchesNearBlock}, which check all 27 of the real
     * {@code dx,dy,dz} offsets individually against {@code cubes}'s own loaded set (via
     * {@code CubeWorld.cube(long)}/{@code CubeKey.pack}, per his own exact prescription) -- only a
     * cube that is BOTH still missing AND naturally relevant creates any map demand at all. See that
     * method's own doc for the full reasoning, including the per-X/Z-column coalescing he asked for.
     */
    private static boolean isMissingRelevantRoadPendingAndDispatch(ServerCubeWorld cubes, EarthGenerator earthGenerator, BlockPos pos) {
        RoadPrefetch.dispatchMissingRelevantCellFetchesNearBlock(cubes, earthGenerator, pos.getX(), pos.getY(), pos.getZ());
        return RoadPrefetch.isAnyMissingRelevantCellPendingNearBlock(cubes, earthGenerator, pos.getX(), pos.getY(), pos.getZ());
    }

    @ModifyExpressionValue(method = "arrive(Lnet/minecraft/server/level/ServerLevel;Lio/earthplatform/minecraft/cubic/ServerCubeWorld;)V",
            at = @At(value = "FIELD", target = "Lio/earthplatform/minecraft/cubic/CubicEvents$Arrival;waited:I", ordinal = 0))
    private static int earthplatformroads$clampWaitedComparisonWhileRoadHeld(int waited, @Share("roadHeld") LocalBooleanRef roadHeld) {
        // Deliberate: while road-held, the comparison this value feeds (`arrival.waited < 200`)
        // must stay true regardless of how large the real counter has grown, so arrive() takes the
        // "keep waiting" branch naturally -- standOn/loadAround/teleportTo/tellPlace are never
        // reached at all while held, not merely gated after the fact.
        return roadHeld.get() ? 0 : waited;
    }
}
