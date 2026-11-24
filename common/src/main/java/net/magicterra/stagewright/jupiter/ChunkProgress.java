package net.magicterra.stagewright.jupiter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Whether one plot's wait for its chunks still makes sense.
 *
 * <p>Every plot leased at once shares the server's chunk workers, so when a suite starts all its classes together the
 * last plot in the queue waits behind the others for as long as they take, while generation never stops. A fixed
 * deadline fails exactly those plots, dozens at a time, as if the server had hung. So the wait gives up only on a
 * stall: no chunk of this plot, and none of any other plot waiting in this game, has become ready for
 * {@code stallTicks}.
 */
final class ChunkProgress {

    /** The server tick at which any plot's wait in this game last saw one more of its chunks ready. */
    static final AtomicLong ANY_PROGRESS = new AtomicLong(Long.MIN_VALUE);

    private final AtomicLong anyProgress;

    private final int stallTicks;

    private final long started;

    private int ready = -1;

    private long ownProgress;

    /**
     * A wait starting at server tick {@code started}.
     *
     * @param anyProgress shared by every plot waiting at once, {@link #ANY_PROGRESS} in a game
     */
    ChunkProgress(AtomicLong anyProgress, int stallTicks, long started) {
        this.anyProgress = anyProgress;
        this.stallTicks = stallTicks;
        this.started = started;
        this.ownProgress = started;
    }

    /**
     * Server thread, once a tick: note that {@code nowReady} of the plot's chunks are ready at {@code tick}.
     *
     * @return whether the wait has stalled
     */
    boolean stalled(int nowReady, long tick) {
        if (nowReady > ready) {
            // The first reading is where this wait starts, not progress: those chunks may have been ready before it.
            if (ready >= 0) {
                anyProgress.accumulateAndGet(tick, Math::max);
                ownProgress = tick;
            }
            ready = nowReady;
        }
        return ticksWithoutProgress(tick) >= stallTicks;
    }

    /** Ticks since this plot or any other last had a chunk become ready, counted from no earlier than the start. */
    long ticksWithoutProgress(long tick) {
        return tick - Math.max(ownProgress, anyProgress.get());
    }

    long waited(long tick) {
        return tick - started;
    }

    /** The chunks ready at the last reading; 0 before the first. */
    int ready() {
        return Math.max(ready, 0);
    }
}
