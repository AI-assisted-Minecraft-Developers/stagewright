package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ChunkProgressTest {

    private static final int STALL = 100;

    @Test
    void aPlotWhoseChunksKeepComingNeverStalls() {
        ChunkProgress wait = new ChunkProgress(new AtomicLong(Long.MIN_VALUE), STALL, 1000);
        assertFalse(wait.stalled(0, 1000));
        assertFalse(wait.stalled(1, 1099));
        assertFalse(wait.stalled(2, 1198));
        assertFalse(wait.stalled(3, 1297));
        assertEquals(3, wait.ready());
        assertEquals(297, wait.waited(1297));
    }

    @Test
    void aPlotWithNothingNewForTheStallStalls() {
        ChunkProgress wait = new ChunkProgress(new AtomicLong(Long.MIN_VALUE), STALL, 1000);
        assertFalse(wait.stalled(2, 1000));
        assertFalse(wait.stalled(2, 1099));
        assertTrue(wait.stalled(2, 1100));
    }

    @Test
    void anotherPlotsProgressKeepsAQueuedPlotWaiting() {
        AtomicLong shared = new AtomicLong(Long.MIN_VALUE);
        ChunkProgress queued = new ChunkProgress(shared, STALL, 1000);
        ChunkProgress first = new ChunkProgress(shared, STALL, 1000);
        assertFalse(queued.stalled(0, 1000));
        assertFalse(first.stalled(0, 1000));
        // Only the first plot's chunks come, one every ninety ticks; the queued one sees none of its own.
        for (int i = 1; i <= 5; i++) {
            int tick = 1000 + 90 * i;
            assertFalse(first.stalled(i, tick));
            assertFalse(queued.stalled(0, tick), "at tick " + tick);
        }
        assertTrue(queued.stalled(0, 1450 + STALL));
    }

    @Test
    void chunksReadyBeforeTheWaitAreNotProgress() {
        AtomicLong shared = new AtomicLong(Long.MIN_VALUE);
        ChunkProgress late = new ChunkProgress(shared, STALL, 5000);
        ChunkProgress other = new ChunkProgress(shared, STALL, 5000);
        assertFalse(late.stalled(4, 5000));
        assertFalse(other.stalled(0, 5050));
        assertTrue(other.stalled(0, 5100), "the first reading of 4 ready moved nothing");
    }
}
