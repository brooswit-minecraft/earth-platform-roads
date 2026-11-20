package io.github.brooswitminecraft.earthplatformroads.mixin;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.github.brooswitminecraft.earthplatformroads.road.RoadNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The seam that makes road coverage actually automatic "wherever he travels" (MINECRAFT-566's
 * milestone update), not merely data a player or operator must find and install first: Earth
 * Platform's own {@code EarthGenerator.prepare}/{@code .prepareNear} are its documented "fetching
 * data ahead of generation" entry points (see {@code EarthGenerator}'s own "Reading this file"
 * section) -- called off-thread (e.g. the {@code /earth travel} command wraps its call in {@code
 * CompletableFuture.supplyAsync}, and {@code ChunkyCompat} calls {@code prepare} once per
 * pre-generation cell) specifically so that later, synchronous generation calls never wait on the
 * network themselves.
 *
 * <p>This mixin does the same thing for road data: after Earth Platform's own prepare work for a
 * point runs, it also blocks (here, off the main generation thread, in exactly the contexts Earth
 * Platform itself already uses for this) on {@code RoadTileSource.waysForCell} for that point's
 * tile. {@code EarthGeneratorRoadMixin}'s own {@code surface()} hook never fetches -- it only ever
 * reads whatever this already cached (see {@code RoadTileSource.cachedWaysForCellOrEmpty}) -- so
 * the hot generation path, including Earth Platform's {@code loadNow} (explicitly synchronous on
 * whatever thread calls it, which may be the main server thread), never blocks on HTTP regardless
 * of whether this mixin has run for that area yet.
 *
 * <p><b>Known simplification</b>: Earth Platform's own {@code prepare}/{@code prepareNear} fetch a
 * reach scaled to several kilometres, across multiple zoom levels, because elevation/climate tiles
 * each cover a large area. This mixin only prefetches the single {@value
 * io.github.brooswitminecraft.earthplatformroads.road.RoadNetwork#CELL_DEGREES}-degree (~1.1 km)
 * road-data cell the point itself falls in, not a matching multi-kilometre reach -- a column near
 * the edge of a freshly-prepared area, or one a fast-moving player reaches before this cell's own
 * fetch completes, can still see a transient gap on first visit. Widening this to prefetch
 * neighboring cells too (matching {@code prepareNear}'s own ~3.5 km {@code NEAR_REACH}) is a
 * reasonable next step, not done here.
 */
@Mixin(EarthGenerator.class)
public abstract class EarthGeneratorPrepareMixin {
    @Inject(method = "prepare(II)V", at = @At("TAIL"))
    private void earthplatformroads$prefetchRoadTile(int x, int z, CallbackInfo ci) {
        prefetch(x, z);
    }

    @Inject(method = "prepareNear(II)V", at = @At("TAIL"))
    private void earthplatformroads$prefetchRoadTileNear(int x, int z, CallbackInfo ci) {
        prefetch(x, z);
    }

    private void prefetch(int x, int z) {
        var tiles = RoadNetwork.liveTileSourceForPrefetch();
        if (tiles == null) return;
        EarthGenerator self = (EarthGenerator) (Object) this;
        GeoPoint point = self.grid().geographic(x, z);
        tiles.waysForCell(point.latitude(), point.longitude());
    }
}
