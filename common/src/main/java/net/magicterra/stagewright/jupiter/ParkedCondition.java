package net.magicterra.stagewright.jupiter;

import java.util.Optional;
import java.util.Set;
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

    /** {@code BlockableEventLoop.managedBlock} and {@code waitForTasks}, by their Mojang and intermediary names. */
    private static final Set<String> LOOP_WAITS =
            Set.of("managedBlock", "waitForTasks", "method_18857", "method_20813");

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
        if (runningTasks(stack)) {
            waitingSamples = 0;
            return Optional.empty();
        }
        // Returned in the time the stack was taken: not parked, only slow.
        return since == started ? Optional.of(stack) : Optional.empty();
    }

    /**
     * Whether the thread waits in {@code BlockableEventLoop.managedBlock}, as the server thread does while a chunk the
     * condition asked for is generated or loaded: it parks for a tenth of a millisecond at a time between the tasks it
     * runs there, so it reads as waiting nearly always, but it does the work it waits for and returns. Judged by the
     * innermost wait alone, the first frame from the top that is not the JDK's: a task run there may wait in turn on a
     * future only this thread completes, which is the wait this is here to catch, and that frame is then above it. By
     * the Mojang names of the loop's wait, {@code managedBlock} and {@code waitForTasks}, which the server overrides,
     * and by their intermediary ones in a Fabric production game.
     */
    static boolean runningTasks(StackTraceElement... stack) {
        return java.util.Arrays.stream(stack)
                .filter(frame -> !isJdk(frame.getClassName()))
                .findFirst()
                .map(frame -> LOOP_WAITS.contains(frame.getMethodName()))
                .orElse(false);
    }

    private static boolean isJdk(String type) {
        return type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun.");
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
