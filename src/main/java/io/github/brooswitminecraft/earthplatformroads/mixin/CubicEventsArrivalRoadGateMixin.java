package io.github.brooswitminecraft.earthplatformroads.mixin;

import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.github.brooswitminecraft.earthplatformroads.road.RoadPrefetch;
import java.util.List;
import net.minecraft.core.BlockPos;
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
 * exhausted, does it proceed to {@code standOn} -- which calls {@code loadAround}, Earth Platform's
 * own SYNCHRONOUS, forcing {@code loadNow}-based fallback -- then teleports the player.
 *
 * <p><b>The gap:</b> {@code readyAround} only ever asks whether the destination's TERRAIN exists in
 * memory -- it has no idea whether THIS mod's own road data for that same footprint has resolved.
 * A destination whose terrain happens to generate quickly (common) but whose road tile is still
 * genuinely pending falls through to the 200-tick patience exhausting on an unrelated clock, then
 * {@code standOn}/{@code loadAround}'s own forced, synchronous generation adopts and PERMANENTLY
 * SAVES whatever (bare) terrain the generator produces at that moment -- the exact same class of
 * bug the ordinary-movement mechanism (comment 55545) fixes for streamed generation, now confirmed
 * natively (comment 55549) to also occur here, for a real geographic-travel teleport.
 *
 * <h2>The fix: extend the SAME existing wait, never build a new one</h2>
 * Rather than any new queue, callback, or token (comment 55552's own warning against that
 * complexity and its lifecycle risks -- an older callback outliving and superseding a newer trip),
 * this hooks the two existing calls {@code arrive} already makes every tick for this exact purpose:
 *
 * <ul>
 *   <li>{@link #earthplatformroads$readyAroundConsideringRoadData} redirects
 *       {@code ServerCubeWorld.readyAround(BlockPos)}: calls the real method first (terrain
 *       readiness, completely unchanged), and if terrain IS ready, additionally checks
 *       {@link RoadPrefetch#isAnyRelevantCellPendingNearBlock} across the SAME 3x3x3 cube
 *       neighborhood {@code readyAround}/{@code loadAround} both already traverse (confirmed by
 *       John's own source review, comment 55552) -- never a broader, speculative neighborhood.
 *       Dispatches ({@link RoadPrefetch#dispatchRelevantCellFetchesNearBlock}, fire-and-forget,
 *       never waits) so the fetch actually has a chance to happen while this arrival keeps waiting.
 *       If road data is pending, reports "not ready" even though terrain is, and remembers that in
 *       {@link #earthplatformroads$roadHeld} for the very next statement this same tick.</li>
 *   <li>{@link #earthplatformroads$suppressWaitedIncrementWhileRoadHeld} redirects the ONE
 *       {@code List.set(int, Object)} call {@code arrive} makes immediately after a "not ready yet"
 *       result -- the exact statement that persists this arrival's own incremented {@code waited}
 *       back into the queue. When {@link #earthplatformroads$roadHeld} was just set, this is a
 *       deliberate no-op (returns the list's own current, unincremented element instead of storing
 *       Earth Platform's freshly-incremented one) -- so {@code waited} never advances while road
 *       data is the reason for waiting, and the 200-tick patience genuinely never exhausts for THAT
 *       reason. An ordinary terrain-not-ready tick (the overwhelmingly common case) is completely
 *       untouched: {@code roadHeld} is {@code false}, and the real {@code List.set} runs exactly as
 *       it always has -- "keep ordinary upstream fallback for non-road unavailability," per John's
 *       own instruction.</li>
 * </ul>
 *
 * <p>Both redirects are {@code static}, matching {@code arrive}'s own staticness, and
 * {@link #earthplatformroads$roadHeld} is a plain static field rather than any per-arrival
 * tracking structure: {@code readyAround} is always called immediately before, and consumed by,
 * exactly one {@code List.set} call within the SAME loop iteration of the SAME single-threaded tick
 * -- confirmed by reading {@code arrive}'s own decompiled bytecode (the two calls are the only ones
 * of their kind in that method, with no other code between them on the "still waiting" branch).
 * This single-tick, stateless design deliberately avoids comment 55552's own token/lifecycle
 * concern: there is no callback or persistent per-arrival object to outlive a tick, supersede a
 * newer trip, or need releasing on disconnect/cancel/world-unload -- "release" happens for free
 * every single tick, since nothing survives past the one {@code List.set} redirect that consumes
 * it. Player disconnect is handled by {@code arrive}'s own, completely unmodified
 * {@code arrival.player.isRemoved()} check, which runs BEFORE this mixin's own hooks are ever
 * reached for that arrival again.
 *
 * <h2>Explicitly out of scope, per John's own instruction</h2>
 * Generic {@code loadNow} (every OTHER forced caller: a forced arrival on the ordinary-streaming
 * path, portal placement, respawn, Sable) is untouched -- {@code SynchronousLoadGuard} and this
 * whole class's own gating remain exactly as documented in
 * {@code ServerCubeWorldRoadPrefetchMixin}'s own class doc. No server-thread HTTP is added here
 * either: both new {@code RoadPrefetch} calls this mixin makes are the same non-blocking,
 * fire-and-forget/cache-read primitives the ordinary-movement mechanism already uses, called from
 * the tick thread exactly the way {@code readyAround} itself already is (a cheap check, never a
 * network call on this thread).
 *
 * <h2>Known, disclosed limitation of this exact candidate</h2>
 * {@link RoadPrefetch#isAnyRelevantCellPendingNearBlock}/{@code dispatchRelevantCellFetchesNearBlock}
 * re-scan the full 3x3x3 cube neighborhood's natural-height footprint fresh on EVERY tick this
 * arrival stays held, rather than resolving and caching that footprint once (John's own "resolve
 * the spot/footprint off-thread, use cheap readiness checks in the tick" framing asks for the
 * latter). Not attempted in this exact candidate, given the very small number of concurrent
 * arrivals this queue ever realistically holds (an ordinary install sees very few simultaneous
 * {@code /earth travel} commands) -- a real, disclosed, not-yet-attempted optimization, not a
 * correctness gap. No test in this repository drives the real, compiled {@code CubicEvents.arrive}
 * method itself, for the same reason no test drives {@code ServerCubeWorld.produce} directly --
 * see {@code ServerCubeWorldRoadPrefetchMixin}'s own "known, disclosed test gap" section. The new
 * {@code RoadPrefetch} methods this redirect calls are covered directly instead.
 */
@Mixin(targets = "io.earthplatform.minecraft.cubic.CubicEvents")
public abstract class CubicEventsArrivalRoadGateMixin {
    /**
     * Set by {@link #earthplatformroads$readyAroundConsideringRoadData} and consumed by the very
     * next {@link #earthplatformroads$suppressWaitedIncrementWhileRoadHeld} call within the SAME
     * tick's SAME loop iteration -- see this class's own doc for why that single-tick, stateless
     * handoff is safe here (single-threaded, sequential, never read before being freshly set).
     */
    private static boolean earthplatformroads$roadHeld;

    @Redirect(method = "arrive(Lnet/minecraft/server/level/ServerLevel;Lio/earthplatform/minecraft/cubic/ServerCubeWorld;)V",
            at = @At(value = "INVOKE",
                    target = "Lio/earthplatform/minecraft/cubic/ServerCubeWorld;readyAround(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean earthplatformroads$readyAroundConsideringRoadData(ServerCubeWorld cubes, BlockPos pos) {
        boolean terrainReady = cubes.readyAround(pos);
        earthplatformroads$roadHeld = false;
        if (!terrainReady) return false; // ordinary terrain-not-ready: completely unchanged
        CubeGenerator generator = cubes.generator();
        if (!(generator instanceof EarthGenerator earthGenerator) || !RoadPrefetch.hasLiveTileSource()) {
            return true; // no live source, or not an Earth generator: nothing for this mod to hold for
        }
        RoadPrefetch.dispatchRelevantCellFetchesNearBlock(earthGenerator, pos.getX(), pos.getY(), pos.getZ());
        if (RoadPrefetch.isAnyRelevantCellPendingNearBlock(earthGenerator, pos.getX(), pos.getY(), pos.getZ())) {
            earthplatformroads$roadHeld = true;
            return false; // terrain is ready, but road data for this exact landing footprint is not
        }
        return true;
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
}
