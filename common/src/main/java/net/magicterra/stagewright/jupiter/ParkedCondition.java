package net.magicterra.stagewright.jupiter;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Tells a wait condition that has stopped the server thread waiting. A condition runs on the server thread, so one that
 * waits there for work only that thread would do, such as a future the server completes, never returns: the wait only
 * ends at its own timeout, which then reads as the other side not answering. The test thread asks this between its
 * checks on the wait, and fails the test with the server thread's stack once the condition has been waiting long past
 * any tick.
 */
final class ParkedCondition {

    /** Far past a tick's 50 ms, and short of any timeout a future is given. */
    private static final long PARKED_NS = TimeUnit.SECONDS.toNanos(2);

    /**
     * Samples in a row the thread must be found waiting. A server that loads a chunk inside the condition waits in
     * short spells between the tasks it runs; one sample alone could find it in any of them.
     */
    private static final int SAMPLES = 5;

    /** The thread that last ran the condition. */
    private volatile Thread runner;

    /** When the evaluation under way started, by {@link System#nanoTime()}; {@link #IDLE} between them. */
    private volatile long since = IDLE;

    private int waitingSamples;

    private static final long IDLE = Long.MIN_VALUE;

    /** {@code condition}, recording when it starts and ends on the thread that runs it. */
    // The start is read by the test thread in parked(), while the condition runs; PMD sees only this thread.
    @SuppressWarnings("PMD.UnusedAssignment")
    BooleanSupplier watching(BooleanSupplier condition) {
        return () -> {
            runner = Thread.currentThread();
            since = System.nanoTime();
            try {
                return condition.getAsBoolean();
            } finally {
                since = IDLE;
            }
        };
    }

    /** The waiting thread's stack once the condition has kept it waiting long enough to call parked. */
    Optional<StackTraceElement[]> parked() {
        long started = since;
        Thread thread = runner;
        if (started == IDLE || System.nanoTime() - started < PARKED_NS || !waiting(thread)) {
            waitingSamples = 0;
            return Optional.empty();
        }
        waitingSamples++;
        if (waitingSamples < SAMPLES) {
            return Optional.empty();
        }
        StackTraceElement[] stack = thread.getStackTrace();
        // Returned in the time the stack was taken: not parked, only slow.
        return since == started ? Optional.of(stack) : Optional.empty();
    }

    private static boolean waiting(Thread thread) {
        Thread.State state = thread.getState();
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    /** The failure for a condition {@code description} names, carrying the stack it is parked at. */
    static AssertionError failure(StackTraceElement[] stack, String description) {
        AssertionError e = new AssertionError(description + ": its condition has kept the server thread waiting for"
                + " over " + TimeUnit.NANOSECONDS.toSeconds(PARKED_NS) + " s. An await condition runs on the server"
                + " thread, after each tick, so a wait in it for something the server thread completes, such as a"
                + " future its tasks finish, never ends. Read the state there without waiting, or poll from the test"
                + " thread: game.onServer(…) between game.ticks(1). This error's stack is the server thread's.");
        e.setStackTrace(stack);
        return e;
    }
}
