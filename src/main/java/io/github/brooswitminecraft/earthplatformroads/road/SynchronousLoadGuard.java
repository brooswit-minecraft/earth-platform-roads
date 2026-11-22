package io.github.brooswitminecraft.earthplatformroads.road;

/**
 * Marks "this thread is currently inside Earth Platform's synchronous {@code ServerCubeWorld.loadNow}
 * arrival path" -- MINECRAFT-566 comment 55474's hard constraint that road prefetch must never add
 * HTTP to that path (documented as "places that must exist this tick, such as an arrival point",
 * explicitly possibly the main server thread). The mixin package's
 * {@code ServerCubeWorldRoadPrefetchMixin} wraps {@code loadNow} with {@link #enter()}/
 * {@link #exit()} and checks {@link #isActive()} before ever dispatching a fetch from inside
 * {@code produce} -- the single method both {@code loadNow} and the ordinary background-worker
 * streaming path share, with no parameter of its own to tell them apart.
 *
 * <p>Deliberately NOT in the {@code mixin} package itself: see {@link RoadPrefetch}'s class doc for
 * why a class living there, other than a declared mixin, cannot be class-loaded at all.
 *
 * <p>A plain {@code boolean} field would not work: {@code produce} runs on whichever thread called
 * it, and the background-worker path (the one this fix is FOR) runs on a pooled worker thread
 * completely unrelated to whatever thread (possibly the main server thread) called {@code loadNow}.
 * A {@link ThreadLocal} is exactly "is MY OWN call stack, right now, inside a loadNow call" -- true
 * only on the thread that is actually inside {@code loadNow} (and only while it still is), false on
 * every other thread regardless of what loadNow calls are happening elsewhere at the same moment.
 *
 * <p>A depth counter, not a boolean, because {@code loadNow} is not obviously non-reentrant (e.g.
 * {@code loadAround} calls it in a loop, and nothing rules out some future caller nesting one
 * {@code loadNow} inside another on the same thread) -- {@link #exit()} must only actually deactivate
 * the guard once every matching {@link #enter()} on this thread has been unwound.
 */
public final class SynchronousLoadGuard {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private SynchronousLoadGuard() {}

    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void exit() {
        int depth = DEPTH.get() - 1;
        if (depth <= 0) DEPTH.remove(); // also clears the ThreadLocal's entry rather than leaking a 0 forever on a pooled thread
        else DEPTH.set(depth);
    }

    /** True only on a thread currently between an unmatched {@link #enter()} and its {@link #exit()}. */
    public static boolean isActive() {
        return DEPTH.get() > 0;
    }
}
