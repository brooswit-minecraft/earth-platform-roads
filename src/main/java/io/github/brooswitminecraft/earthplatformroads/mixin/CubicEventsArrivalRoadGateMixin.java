package io.github.brooswitminecraft.earthplatformroads.mixin;

import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.github.brooswitminecraft.earthplatformroads.road.RoadPrefetch;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MINECRAFT-566 comment 55551, John's own next explicitly separate forced-caller slice: Earth
 * Platform geographic travel (its own {@code /earth travel} arrival queue) only -- never generic
 * {@code loadNow}, portal/respawn, or Sable, all of which stay deliberately out of scope here.
 *
 * <h2>The existing, already-native arrival wait -- confirmed by reading the real, decompiled
 * {@code CubicEvents} class</h2>
 * {@code CubicEvents.tick} calls {@code arrive}, which walks its own static {@code ARRIVALS} queue
 * every server tick. For each still-pending arrival, it computes the landing column's own ground
 * height ({@code max(surfaceY, waterY)}) and calls {@code cubes.readyAround(pos)} -- a cheap,
 * non-blocking check of whether the destination's own 3x3x3 cube neighborhood is ALREADY generated
 * and in memory (never forces generation itself). If not ready, and the arrival has waited fewer
 * than 200 ticks (~10s at 20 TPS, Earth Platform's own {@code ARRIVAL_PATIENCE}), it simply
 * increments {@code waited} and tries again next tick -- the SAME async, never-blocks-a-thread
 * pattern this whole mod's own ordinary-movement mechanism relies on, already built into Earth
 * Platform itself. Only once {@code readyAround} returns true, OR the 200-tick patience is
 * exhausted, does it proceed to {@code standOn}, whose OWN first statement calls
 * {@code cubes.loadAround(pos)} -- Earth Platform's own SYNCHRONOUS, forcing {@code loadNow}-based
 * fallback -- then teleports the player.
 *
 * <p><b>The gap:</b> {@code readyAround} only ever asks whether the destination's TERRAIN exists in
 * memory -- it has no idea whether THIS mod's own road data for that same footprint has resolved.
 * A destination whose terrain happens to generate quickly (common) but whose road tile is still
 * genuinely pending falls through to the 200-tick patience exhausting on an unrelated clock, then
 * {@code loadAround}'s own forced, synchronous generation adopts and PERMANENTLY SAVES whatever
 * (bare) terrain the generator produces at that moment -- the exact same class of bug the
 * ordinary-movement mechanism (comment 55545) fixes for streamed generation, now confirmed natively
 * (comment 55549) to also occur here, for a real geographic-travel teleport.
 *
 * <h2>Comment 55554, two real bugs found by John's own source review of the first version</h2>
 * <b>Bug 1:</b> the first version's redirect returned early (without ever checking road data at
 * all) whenever the REAL {@code readyAround} was {@code false}. But this mod's OWN
 * ordinary-movement mechanism (comment 55545) can be the actual, direct reason the landing cube
 * does not exist yet -- it throws {@code RoadDataNotReadyException} instead of ever generating a
 * cube whose road data is still pending, so that cube is legitimately ABSENT from
 * {@code ServerCubeWorld}'s own map, and {@code readyAround} correctly (but misleadingly) reports
 * "not ready" for a reason that already IS road-pending. The first version's early-return meant
 * road-pending was never even checked for exactly this, the single most common real case -- a
 * landing cube that has simply never been generated at all yet. Fixed by ALWAYS checking road data,
 * regardless of {@code readyAround}'s own raw result -- the final answer is
 * {@code terrainReady && !roadPending}.
 *
 * <p><b>Bug 2, deeper:</b> {@code arrive}'s own condition is
 * {@code !readyAround(pos) && arrival.waited < 200}. Once {@code waited} has ALREADY reached 200
 * (from any earlier tick, for any reason), that right-hand term is {@code false} regardless of what
 * this mixin's own {@code readyAround} redirect returns -- the {@code &&} short-circuits the whole
 * condition to {@code false}, so this class's own {@code List.set}/{@code List.remove} redirects
 * (which gate the "keep waiting" branch, below) are simply never reached that tick, and {@code
 * standOn} runs regardless (John's own described native boundary fixture: seed {@code waited =
 * 199}, tick once to 200, with road data still pending). Fixed by gating the FORCING CALL ITSELF
 * inside {@code standOn} -- {@code cubes.loadAround(pos)} -- rather than trying to stop {@code
 * standOn} from being called at all. {@code standOn}/{@code tellPlace} are {@code CubicEvents}'s
 * OWN private static methods: a {@code @Redirect} on the calls to them (inside {@code arrive})
 * would need to call the REAL implementation itself when not held, which Mixin cannot do safely
 * across Earth Platform's own module boundary for a private member (confirmed by a sibling project
 * hitting exactly this as a real, live {@code IllegalAccessError} under NeoForge's modlauncher, not
 * a theoretical concern) -- so this deliberately never redirects {@code standOn}/{@code tellPlace}
 * themselves. {@code loadAround}, by contrast, is a PUBLIC method of the public
 * {@code ServerCubeWorld} class, safe to redirect normally.
 *
 * <h2>The fix: extend the SAME existing wait, never build a new queue</h2>
 * Rather than any new queue, callback, or token (comment 55552's own warning against that
 * complexity and its lifecycle risks -- an older callback outliving and superseding a newer trip):
 *
 * <ul>
 *   <li>{@link #earthplatformroads$readyAroundConsideringRoadData} redirects
 *       {@code ServerCubeWorld.readyAround(BlockPos)} (inside {@code arrive}): ALWAYS checks
 *       {@link RoadPrefetch#isAnyRelevantCellPendingNearBlock} across the SAME 3x3x3 cube
 *       neighborhood {@code readyAround}/{@code loadAround} both already traverse (confirmed by
 *       John's own source review, comment 55552) -- never a broader, speculative neighborhood --
 *       regardless of the real terrain-readiness result (bug 1's fix). Dispatches
 *       ({@link RoadPrefetch#dispatchRelevantCellFetchesNearBlock}, fire-and-forget, never waits)
 *       so the fetch actually has a chance to happen while this arrival keeps waiting. Records the
 *       combined answer in {@link #earthplatformroads$roadHeld} for every other redirect below,
 *       for this SAME iteration (synchronous, single-threaded -- nothing else runs in between).</li>
 *   <li>{@link #earthplatformroads$suppressWaitedIncrementWhileRoadHeld} /
 *       {@link #earthplatformroads$suppressRemovalWhileRoadHeld} redirect the "keep waiting"
 *       branch's own {@code List.set}/{@code List.remove} calls (inside {@code arrive}) to no-ops
 *       while road-held: {@code waited} never advances, and this exact arrival stays queued --
 *       together, normally enough to prevent {@code waited} from ever reaching 200 for a
 *       road-pending arrival at all.</li>
 *   <li>{@link #earthplatformroads$loadAroundUnlessRoadHeld} redirects {@code standOn}'s OWN first
 *       statement, {@code cubes.loadAround(pos)} (a {@code method="standOn(...)"}-scoped redirect,
 *       NOT inside {@code arrive}): the actual, critical fix for bug 2 -- skips the real forcing
 *       call entirely while road-held (reading the SAME {@link #earthplatformroads$roadHeld},
 *       synchronously fresh from the {@code readyAround} redirect moments earlier in this exact
 *       iteration), so the destination cube is never forced into existence, and never permanently
 *       saved, while its road data is still pending -- REGARDLESS of whether {@code standOn} itself
 *       still gets called (it does; only its own forcing side effect is suppressed).
 *       {@code standOn}'s own subsequent search loop (reading block state, never itself forcing
 *       generation) then runs over whatever is or is not already loaded, same as it always
 *       gracefully handles for any other not-yet-generated area it searches near -- returning SOME
 *       {@code Vec3} regardless, by construction (several unconditional fallback branches), but
 *       never used for anything while road-held (next bullet).</li>
 *   <li>{@link #earthplatformroads$teleportUnlessRoadHeld} redirects
 *       {@code ServerPlayer.teleportTo} (inside {@code arrive}, PUBLIC vanilla API, safe to
 *       redirect normally): skipped entirely while road-held, so whatever {@code Vec3} {@code
 *       standOn} computed is never actually used for anything -- the traveler's own position is
 *       simply left completely untouched, which is exactly "remains at origin" with no special-case
 *       coordinate math needed.</li>
 * </ul>
 *
 * <p>All four redirects are {@code static}, matching {@code arrive}/{@code standOn}'s own
 * staticness, and {@link #earthplatformroads$roadHeld} is a plain static field rather than any
 * per-arrival tracking structure -- this single-tick, stateless design deliberately avoids comment
 * 55552's own token/lifecycle concern: there is no callback or persistent per-arrival object to
 * outlive a tick, supersede a newer trip, or need releasing on disconnect/cancel/world-unload --
 * "release" happens for free every single tick, since nothing survives past this one iteration. An
 * arrival stuck road-held at the 200-tick boundary simply re-enters the SAME "proceed to standOn,
 * but its forcing call and the teleport are both gated" path every subsequent tick --
 * functionally equivalent to "still waiting," without ever touching the {@code waited < 200}
 * comparison itself (the one piece of this mechanism that -- due to the private {@code Arrival}
 * record type -- cannot be reached without far riskier bytecode-local-capture techniques this
 * candidate deliberately avoids). Player disconnect is handled by {@code arrive}'s own, completely
 * unmodified {@code arrival.player.isRemoved()} check (which runs BEFORE any of this mixin's own
 * hooks, each tick).
 *
 * <h2>Known, disclosed limitation of this exact candidate</h2>
 * {@code tellPlace} (the arrival chat message) is NOT suppressed while road-held -- for the same
 * cross-module private-method reason {@code standOn} itself is not redirected in {@code arrive}
 * (see above). It still runs every tick the fallback branch is reached, reporting whatever position
 * {@code standOn} computed (which, with its own forcing call skipped, may reference an area that
 * was never actually generated) even though the player has NOT actually been moved there (the
 * teleport itself is suppressed). This is a real, disclosed cosmetic gap -- a potentially repeated
 * or misleading chat message -- not a data-integrity one: no premature generation, no permanent
 * save, and no actual player movement occur while road-held. No safe way to suppress it without the
 * same cross-module risk was found in this pass. Separately: whether {@code standOn}'s own
 * subsequent search logic (block-state reads, occasional {@code clearLeaves}/{@code
 * setBlockAndUpdate} calls) is itself safe to run over an area {@code loadAround} was never called
 * for is not independently verified here -- Earth Platform's own cube-world API is expected to
 * handle an unloaded read/query gracefully (as it must for any other not-yet-generated area ANY
 * code might query), but this is not proven against a real server in this harness. No test in this
 * repository drives the real, compiled {@code CubicEvents.arrive}/{@code standOn} methods
 * themselves, for the same reason no test drives {@code ServerCubeWorld.produce} directly -- see
 * {@code ServerCubeWorldRoadPrefetchMixin}'s own "known, disclosed test gap" section. The new
 * {@code RoadPrefetch} methods this redirect calls are covered directly instead. John's own
 * described native boundary fixture (seed a real arrival at {@code waited = 199} with a cold
 * loopback tile, instrument {@code standOn} to confirm it does not run at tick 200 while pending,
 * and does run once after the footprint is adopted) is exactly the right test for the fix above and
 * is not reproducible from this harness.
 */
@Mixin(targets = "io.earthplatform.minecraft.cubic.CubicEvents")
public abstract class CubicEventsArrivalRoadGateMixin {
    /**
     * Set by {@link #earthplatformroads$readyAroundConsideringRoadData}, at the very top of each
     * loop iteration of {@code arrive}, and read by every other redirect in this class for that
     * SAME iteration -- see this class's own doc for why that single-tick, stateless handoff is
     * safe here (single-threaded, sequential, always freshly set before being read).
     */
    private static boolean earthplatformroads$roadHeld;

    @Redirect(method = "arrive(Lnet/minecraft/server/level/ServerLevel;Lio/earthplatform/minecraft/cubic/ServerCubeWorld;)V",
            at = @At(value = "INVOKE",
                    target = "Lio/earthplatform/minecraft/cubic/ServerCubeWorld;readyAround(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean earthplatformroads$readyAroundConsideringRoadData(ServerCubeWorld cubes, BlockPos pos) {
        boolean terrainReady = cubes.readyAround(pos);
        CubeGenerator generator = cubes.generator();
        boolean roadPending = generator instanceof EarthGenerator earthGenerator
                && RoadPrefetch.hasLiveTileSource()
                && isRoadPendingAndDispatch(earthGenerator, pos);
        earthplatformroads$roadHeld = roadPending;
        return terrainReady && !roadPending;
    }

    private static boolean isRoadPendingAndDispatch(EarthGenerator earthGenerator, BlockPos pos) {
        RoadPrefetch.dispatchRelevantCellFetchesNearBlock(earthGenerator, pos.getX(), pos.getY(), pos.getZ());
        return RoadPrefetch.isAnyRelevantCellPendingNearBlock(earthGenerator, pos.getX(), pos.getY(), pos.getZ());
    }

    @Redirect(method = "arrive(Lnet/minecraft/server/level/ServerLevel;Lio/earthplatform/minecraft/cubic/ServerCubeWorld;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/List;set(ILjava/lang/Object;)Ljava/lang/Object;"))
    private static Object earthplatformroads$suppressWaitedIncrementWhileRoadHeld(List<Object> arrivals, int index, Object incrementedArrival) {
        if (earthplatformroads$roadHeld) {
            // Deliberate no-op: leave the existing, not-yet-incremented entry in place so Earth
            // Platform's own 200-tick patience never advances while this is the reason for waiting.
            return arrivals.get(index);
        }
        return arrivals.set(index, incrementedArrival);
    }

    @Redirect(method = "arrive(Lnet/minecraft/server/level/ServerLevel;Lio/earthplatform/minecraft/cubic/ServerCubeWorld;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/List;remove(I)Ljava/lang/Object;"))
    private static Object earthplatformroads$suppressRemovalWhileRoadHeld(List<Object> arrivals, int index) {
        if (earthplatformroads$roadHeld) {
            // Deliberate no-op: keep this exact arrival queued for another tick -- comment 55554's
            // "no consumed arrival" requirement, even once Earth Platform's own 200-tick patience
            // would otherwise have forced it into the fallback branch below.
            return arrivals.get(index);
        }
        return arrivals.remove(index);
    }

    @Redirect(method = "standOn(Lio/earthplatform/minecraft/cubic/ServerCubeWorld;II)Lnet/minecraft/world/phys/Vec3;",
            at = @At(value = "INVOKE", target = "Lio/earthplatform/minecraft/cubic/ServerCubeWorld;loadAround(Lnet/minecraft/core/BlockPos;)V"))
    private static void earthplatformroads$loadAroundUnlessRoadHeld(ServerCubeWorld cubes, BlockPos pos) {
        if (earthplatformroads$roadHeld) {
            // Deliberate no-op: the actual fix for comment 55554's bug 2 -- this is the one call
            // that forces (and permanently saves) generation for the destination; skipping it is
            // what actually prevents premature terrain, independent of whether standOn itself still
            // runs (it does; this is its own only forcing side effect, now gated).
            return;
        }
        cubes.loadAround(pos);
    }

    @Redirect(method = "arrive(Lnet/minecraft/server/level/ServerLevel;Lio/earthplatform/minecraft/cubic/ServerCubeWorld;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V"))
    private static void earthplatformroads$teleportUnlessRoadHeld(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        if (earthplatformroads$roadHeld) {
            return; // deliberate no-op: the traveler's own real position is left completely untouched
        }
        player.teleportTo(level, x, y, z, yaw, pitch);
    }
}
