package io.github.brooswitminecraft.earthplatformroads.mixin;

import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * MINECRAFT-566 comment 55486/55487: {@code SurfaceGenerator} permanently memoizes an ENTIRE
 * column's worth of {@code Surface} decisions (its private {@code columns} map, confirmed by
 * reading the real class directly) the first time anything asks for it -- including
 * {@code lowestGround}, which {@code ColumnSurvey}/{@code ServerCubeWorld.plan} calls every tick
 * for flying-surface logic, well before this column's own road data is necessarily ready. Once
 * cached, {@code column(int,int)} never recomputes it: a LATER, genuinely fresh
 * {@code produce}/{@code generate} call for the exact same column hits that same stale entry and
 * never re-invokes {@code surface()} at all, regardless of whether road data has since become
 * ready. John independently reproduced this on a real server: the SAME generator instance kept
 * emitting grass after a tile became READY, while a FRESH generator instance (same seed/data)
 * correctly emitted blackstone.
 *
 * <p>This {@code @Accessor} exposes that private map so
 * {@code ServerCubeWorldRoadPrefetchMixin} can evict exactly the one column about to be generated,
 * right before {@code produce}'s own call to {@code generate()} -- narrow, per-column invalidation
 * (comment 55486's own ask: "without globally clearing caches per cube ... narrowly
 * version/invalidate affected road-material decisions"), not a global cache clear. Safe and free
 * in the ordinary case too: {@code produce} is only ever called once per column under normal
 * operation, so evicting an entry nothing else had already cached costs nothing -- {@code column}
 * simply recomputes it fresh either way, which is exactly what is about to happen regardless.
 * {@code Column} itself (the map's value type) is package-private in Earth Platform's own
 * {@code gen} package, so this accessor's return type stays a raw {@code ConcurrentHashMap<Long, Object>}
 * -- Java generics are erased at the bytecode level Mixin actually matches against, so the exact
 * value type parameter here does not need to (and cannot) name the inaccessible real one.
 */
@Mixin(SurfaceGenerator.class)
public interface SurfaceGeneratorColumnCacheAccessor {
    @Accessor("columns")
    ConcurrentHashMap<Long, Object> earthplatformroads$columns();
}
