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
 * <h2>Boundary gap, found live (MINECRAFT-566 comment 55454), fixed on the read side (55456)</h2>
 * A mapped way whose geometry crosses from an already-fetched cell into an adjacent, still-pending
 * one was silently omitted at exactly that boundary: not a missing OSM way, not a designed bridge/
 * tunnel omission, just a cell that genuinely had not been fetched yet. Observed live on Zippy (a
 * road abruptly ending on bare ground at a tile seam) and reproduced synthetically in {@code
 * RoadTilePrefetchMixinTest#aCrossingRoadIsSilentlyOmittedAtAnUnfetchedNeighborsBoundary}.
 *
 * <p>The first fix attempt (55454) widened THIS mixin to issue up to 9 sequential {@code
 * waysForCell} calls (the point's cell plus its 8 Moore neighbors) per prepare call. Comment 55456
 * found that broken under the Overpass.ch preview provider's own realistic rate limit: with a
 * multi-second {@code previewMinRequestIntervalMs}, only the first of those 9 calls could actually
 * succeed before {@code RoadTileSource}'s next-eligible deadline rejected the rest -- a problem the
 * original regression test missed entirely by using the zero-interval constructor, never
 * exercising that deadline. So this mixin stays at its original single-cell prefetch (below); the
 * real fix is on the READ side instead: {@code RoadNetwork.candidatesNear} now also checks the
 * Moore neighborhood's cached data (read-only, no fetch) when evaluating a column, since a live
 * provider's bbox response for one cell already carries the FULL geometry of any way merely
 * touching that cell -- including the portion extending into a neighbor -- so the neighbor's own
 * fetch was never actually required to carry the seam. See {@code RoadNetwork.candidatesNear}'s own
 * doc for the full reasoning, and {@code RoadTilePrefetchMixinTest
 * #theWidenedPrefetchFetchesTheNeighborSoACrossingRoadIsNotOmitted} (updated to exercise the
 * read-side fix at a realistic, nonzero request interval) for the regression.
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
