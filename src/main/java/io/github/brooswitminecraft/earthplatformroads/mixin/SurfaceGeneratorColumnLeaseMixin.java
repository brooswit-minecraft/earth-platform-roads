package io.github.brooswitminecraft.earthplatformroads.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.github.brooswitminecraft.earthplatformroads.road.RoadPrefetch;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MINECRAFT-566 comment 55568, John's own independent prototype closing the residual late-publish
 * race {@code ServerCubeWorldRoadPrefetchMixin}'s own class doc already discloses (comment 55496,
 * natively reproduced and confirmed real in comment 55565): a {@code SurfaceGenerator.column()}
 * computation already in flight when an eviction runs can still publish its own stale,
 * pre-resolution result into the now-empty cache slot AFTER the eviction completes, since nothing
 * before this round coordinated the publish with the eviction at all. Unconditional re-eviction on
 * every relevant touch (the current, reverted-back-to behavior in {@code evictStaleSurfaceDecision})
 * only ever NARROWED this to "stale for at most until the very next touch" -- it never eliminated
 * it, and per John's own explicit instruction, that eviction-frequency behavior stays exactly as it
 * is: this mixin is a SEPARATE, additional mechanism layered underneath it, not a replacement for
 * it and not a reason to reduce how often it runs ("keep retouch eviction on relevant workers
 * initially; no global skip").
 *
 * <h2>The real, decompiled {@code column(int,int)} method this targets</h2>
 * Confirmed by reading the real, decompiled {@code combined-73} class directly:
 * <pre>
 * private Column column(int cx, int cz) {
 *     long key = columnKey(cx, cz);
 *     Column cached = this.columns.get(key);
 *     if (cached != null) return cached;
 *     ... (expensive terrain sampling, then this.surface(...) once per column, building `made`) ...
 *     if (this.columns.size() &gt; 8192) this.columns.clear();
 *     this.columns.putIfAbsent(key, made);
 *     return made;
 * }
 * </pre>
 * Both {@code putIfAbsent} AND a real, confirmed bulk {@code columns.clear()} exist, exactly as
 * John's own design named ("coordinate the existing {@code columns.clear}") -- {@code clear()}
 * fires once this generator's own {@code columns} map exceeds 8192 entries, wiping EVERY column's
 * cached decision at once. There is a SECOND, unrelated {@code ConcurrentHashMap.putIfAbsent} call
 * site in this same real class (on a completely different {@code grounds} field, inside a
 * different method entirely) -- Mixin's own {@code method} scoping on each injector below targets
 * {@code column(int,int)} specifically, so that other call site is never touched.
 *
 * <h2>The mechanism</h2>
 * <ul>
 *   <li>{@link #earthplatformroads$captureColumnLease}, a plain {@code @Inject} at {@code HEAD} --
 *       before this method ever consults {@code columns.get(key)} at all, per John's own "before
 *       original column() sees cache" -- captures {@link RoadPrefetch#captureColumnLease}'s current
 *       answer for this exact column, into a {@code @Share}d {@link LocalRef} (a PER-INVOCATION
 *       value, never a global/static field -- comment 55556's own "global hold flag leaks" finding
 *       on a different mixin in this same ticket, not repeated here).</li>
 *   <li>{@link #earthplatformroads$bumpEpochAroundFullClear}, a {@code @WrapOperation} on the real
 *       {@code columns.clear()} call: calls the real clear through unconditionally, THEN bumps this
 *       whole generator's own epoch ({@link RoadPrefetch#bumpGeneratorEpochForFullClear}) --
 *       invalidating every column's own previously-captured lease at once, matching the real
 *       clear's own "every column" scope exactly.</li>
 *   <li>{@link #earthplatformroads$publishOnlyIfLeaseStillMatches}, a {@code @WrapOperation} on the
 *       real {@code columns.putIfAbsent(key, made)} call: re-checks, immediately before publishing,
 *       whether the lease captured at entry is STILL the current one
 *       ({@link RoadPrefetch#publishIfLeaseStillMatches}). If it still matches, the real publish
 *       runs exactly as before. If it does NOT (an eviction -- targeted, or a bulk clear -- ran
 *       between this invocation's own entry and this exact moment), the real publish is SKIPPED
 *       entirely -- "publication after invalidation is rejected" -- returning whatever is already
 *       in the map for this key (or {@code null} if absent), mimicking {@code putIfAbsent}'s own
 *       real "already present, nothing changed" contract rather than fabricating a different
 *       answer. "Publication before invalidation is removed" needs no new mechanism here: a publish
 *       that lands BEFORE an eviction is simply cleaned up by that eviction's own
 *       {@code columns.remove(key)}, exactly as it always has been.</li>
 * </ul>
 *
 * <p>The actual lease bookkeeping (capture, rotation, the per-generator epoch, and the SHORT shared
 * lock guarding only that bookkeeping -- NEVER held around this method's own terrain sampling, any
 * HTTP, or any wait) all lives in {@link RoadPrefetch}, not here -- this class is purely the real
 * {@code column()} method's own three injection points, kept as thin as possible so the actual
 * concurrency reasoning sits in one, directly-testable place.
 *
 * <h2>Known, disclosed limitation of this exact candidate</h2>
 * No test in this repository drives the real, compiled {@code SurfaceGenerator.column()} method
 * itself -- the same disclosed harness gap every other Mixin in this entire ticket already carries
 * (no real {@code ServerLevel}/live NeoForge environment here). {@link RoadPrefetch}'s own lease
 * bookkeeping (capture/rotate/epoch-bump, and the two race orderings John's own design names --
 * "publication before invalidation," "publication after invalidation") is covered directly by a
 * real, compiled, run adversarial test instead. Whether this exact Mixin actually weaves and fires
 * as written against the real, compiled {@code SurfaceGenerator} class -- including whether
 * {@code @Share} correctly threads the captured lease from the {@code @Inject} through to both
 * {@code @WrapOperation}s in one real invocation -- is NOT independently verified in this harness;
 * John's own native infrastructure is what will confirm that, as with every other Mixin fix in this
 * ticket.
 */
@Mixin(SurfaceGenerator.class)
public abstract class SurfaceGeneratorColumnLeaseMixin {

    @Inject(method = "column(II)Lio/earthplatform/minecraft/cubic/gen/SurfaceGenerator$Column;", at = @At("HEAD"))
    private void earthplatformroads$captureColumnLease(int cx, int cz, CallbackInfoReturnable<Object> cir,
            @Share("lease") LocalRef<RoadPrefetch.ColumnLease> lease) {
        Object self = this;
        if (!(self instanceof EarthGenerator generator)) {
            return;
        }
        long key = RoadPrefetch.surfaceGeneratorColumnKey(cx, cz);
        lease.set(RoadPrefetch.captureColumnLease(generator, key));
    }

    @WrapOperation(method = "column(II)Lio/earthplatform/minecraft/cubic/gen/SurfaceGenerator$Column;",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/ConcurrentHashMap;clear()V"))
    private void earthplatformroads$bumpEpochAroundFullClear(ConcurrentHashMap<Long, Object> columns, Operation<Void> operation) {
        operation.call(columns);
        Object self = this;
        if (self instanceof EarthGenerator generator) {
            RoadPrefetch.bumpGeneratorEpochForFullClear(generator);
        }
    }

    @WrapOperation(method = "column(II)Lio/earthplatform/minecraft/cubic/gen/SurfaceGenerator$Column;",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/ConcurrentHashMap;putIfAbsent(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object earthplatformroads$publishOnlyIfLeaseStillMatches(
            ConcurrentHashMap<Object, Object> columns, Object key, Object made, Operation<Object> operation,
            @Share("lease") LocalRef<RoadPrefetch.ColumnLease> lease) {
        Object self = this;
        if (!(self instanceof EarthGenerator generator) || lease.get() == null) {
            return operation.call(columns, key, made); // defensive fallback only; never expected in this mod's own usage
        }
        if (RoadPrefetch.publishIfLeaseStillMatches(generator, (Long) key, lease.get())) {
            return operation.call(columns, key, made);
        }
        return columns.get(key); // publish rejected -- mimic putIfAbsent's own "already present" return contract
    }
}
