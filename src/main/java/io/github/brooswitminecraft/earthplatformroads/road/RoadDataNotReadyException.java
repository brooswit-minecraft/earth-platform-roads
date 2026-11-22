package io.github.brooswitminecraft.earthplatformroads.road;

/**
 * MINECRAFT-566 comment 55545, a direction change from John superseding the earlier "bounded
 * fail-open to grass" design entirely: for ordinary live movement into fresh, road-relevant
 * terrain, no timeout/cooldown/outage alone should ever adopt or permanently save bare terrain --
 * the mod should keep waiting for needed map data, without tying up the server or a worker thread
 * doing it.
 *
 * <p>Thrown by {@code ServerCubeWorldRoadPrefetchMixin}'s {@code @Redirect} INSTEAD OF calling the
 * real {@code CubeGenerator.generate(...)} at all, whenever a relevant cell is still genuinely
 * {@link RoadTileSource.CellState#PENDING}. This is not an error -- it is a deliberate signal into
 * Earth Platform's OWN existing, already-native retry machinery: {@code ServerCubeWorld.produce}'s
 * surrounding {@code try/catch (RuntimeException failure)} (confirmed by reading the real,
 * decompiled class) turns any {@code RuntimeException} thrown from the {@code generate} call site
 * into a {@code Ready} result with a {@code null} section and the failure attached;
 * {@code adoptReady} then does NOT create a {@code Cube}, does NOT touch storage at all, and
 * schedules {@code retryAt.put(key, ticks + 100)} (roughly 5 real-world seconds at 20 TPS) --
 * {@code request}'s own next tick-driven pass will naturally re-attempt this exact cube later, on
 * whichever worker thread happens to pick it up then, never blocking or occupying THIS worker
 * thread any longer than it takes to throw. No code in this mod manages that retry scheduling,
 * discards no-longer-wanted cubes, or bounds in-flight resources -- {@code ServerCubeWorld}'s own
 * {@code retryAt}/{@code pending}/{@code wanted} machinery already does every one of those, for
 * every other kind of generation failure too; this only needs to plug into it honestly.
 *
 * <p>No stack trace is ever captured ({@code writableStackTrace=false}): this fires routinely while
 * legitimately waiting on real map data, not exceptionally, so paying for a captured stack trace on
 * every retry cycle would be pure waste.
 */
public final class RoadDataNotReadyException extends RuntimeException {
    public RoadDataNotReadyException(String message) {
        super(message, null, false, false);
    }
}
