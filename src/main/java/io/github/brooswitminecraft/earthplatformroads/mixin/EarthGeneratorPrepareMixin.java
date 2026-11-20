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
 * <h2>Boundary gap, found live and fixed here (MINECRAFT-566 comment 55454)</h2>
 * This mixin originally prefetched only the single cell the point itself falls in. {@code
 * RoadNetwork.candidatesNear} only ever checks a column's OWN cell's cache, never a neighbor's --
 * so a mapped way whose geometry crosses from an already-fetched cell into an adjacent, still-
 * pending one was silently omitted at exactly that boundary: not a missing OSM way, not a designed
 * bridge/tunnel omission, just a cell that genuinely had not been fetched yet. Observed live on
 * Zippy (a road abruptly ending on bare ground at a tile seam) and reproduced synthetically in
 * {@code RoadTilePrefetchMixinTest#aCrossingRoadIsSilentlyOmittedAtAnUnfetchedNeighborsBoundary}
 * (direct {@code RoadTileSource.waysForCell} calls, bypassing this mixin, to exercise exactly the
 * old narrow behavior). Fixed by widening {@link #prefetch} to also prefetch the full Moore
 * neighborhood (the 8 surrounding cells, not just the point's own) around every prepare call --
 * confirmed in {@code RoadTilePrefetchMixinTest#theWidenedPrefetchFetchesTheNeighborSoACrossingRoadIsNotOmitted}.
 *
 * <p><b>This only helps a FRESH (not yet generated) cube.</b> The widened prefetch runs at
 * Earth Platform's own prepare-ahead-of-generation entry points, before a column's blocks are ever
 * placed -- so for a cube generated AFTER this fix, both cells it could possibly need are already
 * fetched by the time generation reads them. It does nothing for a cube generated BEFORE this fix
 * landed, or one generated while a neighbor's fetch was still failing/pending for some other reason
 * (a genuine network outage, say): once a column's blocks exist, this mod has no retrofit
 * mechanism -- a later-arriving tile does not retroactively repair an already-generated cube. That
 * is a distinct, harder problem (safely revising existing world data, not just prefetching further
 * ahead) and is not attempted here; comment 55454 explicitly separates it from this fix and does
 * not ask for it.
 *
 * <p><b>Still a known simplification, narrower than before but not eliminated</b>: Earth Platform's
 * own {@code prepare}/{@code prepareNear} fetch a reach scaled to several kilometres, across
 * multiple zoom levels. The Moore neighborhood here is still only {@value
 * io.github.brooswitminecraft.earthplatformroads.road.RoadNetwork#CELL_DEGREES}-degree (~1.1 km)
 * cells one deep -- a column two or more cells away from the original prepare point, or one a very
 * fast-moving player reaches before even the widened prefetch completes, can still see a transient
 * gap on first visit. Matching {@code prepareNear}'s own ~3.5 km {@code NEAR_REACH} exactly would
 * need a wider ring than this; not attempted here.
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

    /**
     * Prefetches the point's own road-data cell AND its full Moore neighborhood (the 8 cells
     * sharing an edge or corner with it) -- see this class's own doc for why: a column can sit in
     * any of those 9 cells by the time generation actually reaches it, and a mapped way crossing
     * between any two of them must not be silently omitted just because only the center cell was
     * fetched.
     */
    private void prefetch(int x, int z) {
        var tiles = RoadNetwork.liveTileSourceForPrefetch();
        if (tiles == null) return;
        EarthGenerator self = (EarthGenerator) (Object) this;
        GeoPoint point = self.grid().geographic(x, z);
        double cellDegrees = RoadNetwork.CELL_DEGREES;
        for (int dLat = -1; dLat <= 1; dLat++) {
            for (int dLon = -1; dLon <= 1; dLon++) {
                tiles.waysForCell(point.latitude() + dLat * cellDegrees, point.longitude() + dLon * cellDegrees);
            }
        }
    }
}
