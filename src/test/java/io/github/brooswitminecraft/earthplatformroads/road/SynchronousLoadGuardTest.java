package io.github.brooswitminecraft.earthplatformroads.road;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-566 comment 55474's actual new mechanism: {@code ServerCubeWorldRoadPrefetchMixin} must
 * never dispatch a road fetch from inside {@code ServerCubeWorld.loadNow}'s synchronous arrival
 * path, but MUST from the ordinary background-worker streaming path -- both of which call the exact
 * same {@code produce} method, on different threads, with no parameter telling them apart. This is
 * the one piece of that fix testable without a real {@code ServerCubeWorld} (see that mixin's own
 * "Known, disclosed test gap" doc): {@link SynchronousLoadGuard} itself, exercised directly, the
 * same way the real mixin would drive it (enter at loadNow's HEAD, exit at its RETURN, checked from
 * whatever thread produce() happens to run on).
 */
class SynchronousLoadGuardTest {
    @AfterEach
    void leaveClean() {
        // Defensive: a failed assertion mid-test must not leave this thread's ThreadLocal active for
        // a later test reusing the same JUnit worker thread.
        while (SynchronousLoadGuard.isActive()) SynchronousLoadGuard.exit();
    }

    @Test
    void isInactiveBeforeAnyEnter() {
        assertFalse(SynchronousLoadGuard.isActive());
    }

    @Test
    void becomesActiveOnEnterAndInactiveAfterTheMatchingExit() {
        SynchronousLoadGuard.enter();
        assertTrue(SynchronousLoadGuard.isActive(), "Expected active immediately after enter()");
        SynchronousLoadGuard.exit();
        assertFalse(SynchronousLoadGuard.isActive(), "Expected inactive after the matching exit()");
    }

    @Test
    void nestedEnterCallsStayActiveUntilEveryExitHasUnwound() {
        // Models loadAround() calling loadNow() in a loop, or any other nesting on the same thread:
        // must not go inactive after only the FIRST exit() while an outer enter() is still pending.
        SynchronousLoadGuard.enter();
        SynchronousLoadGuard.enter();
        assertTrue(SynchronousLoadGuard.isActive());
        SynchronousLoadGuard.exit();
        assertTrue(SynchronousLoadGuard.isActive(), "A single exit() must not deactivate a doubly-entered guard");
        SynchronousLoadGuard.exit();
        assertFalse(SynchronousLoadGuard.isActive(), "The second, matching exit() must deactivate it");
    }

    @Test
    void isIsolatedPerThreadNotGlobal() throws InterruptedException {
        // The real bug this guards against: produce() for the ordinary background-worker path runs
        // on a worker-pool thread completely unrelated to whatever thread (possibly the main server
        // thread) is, at that same moment, inside a loadNow() call for an unrelated arrival point. A
        // global (non-thread-local) flag would wrongly suppress -- or wrongly allow -- prefetch based
        // on a completely unrelated thread's state.
        CountDownLatch otherThreadEntered = new CountDownLatch(1);
        CountDownLatch mainThreadMayExit = new CountDownLatch(1);
        AtomicBoolean observedActiveOnMainThreadWhileOtherThreadWasInside = new AtomicBoolean(true);

        Thread otherThread = new Thread(() -> {
            SynchronousLoadGuard.enter();
            otherThreadEntered.countDown();
            try {
                mainThreadMayExit.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            SynchronousLoadGuard.exit();
        }, "earthplatformroads-test-other-thread");
        otherThread.start();

        otherThreadEntered.await();
        // This (the test/"main") thread never called enter() itself, yet the other thread is
        // currently active -- this thread's own view of the guard must still read inactive.
        observedActiveOnMainThreadWhileOtherThreadWasInside.set(SynchronousLoadGuard.isActive());
        mainThreadMayExit.countDown();
        otherThread.join();

        assertFalse(observedActiveOnMainThreadWhileOtherThreadWasInside.get(),
                "This thread must read inactive while only a DIFFERENT thread is inside the guard");
        assertFalse(SynchronousLoadGuard.isActive(), "Sanity: this thread was never actually entered");
    }
}
