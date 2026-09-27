package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** How a run's wait for its footer ends, and what it says when the pair never met. */
class WaitTest {

    @Test
    void aTimeoutTooLargeToAddToTheClockHasNotRunOut() {
        long now = System.nanoTime();
        assertFalse(Main.expired(now, TimeUnit.MINUTES.toNanos(999_999_999), now + 1));
        assertTrue(Main.expired(now, TimeUnit.MINUTES.toNanos(1), now + TimeUnit.MINUTES.toNanos(1)));
    }

    @Test
    void aPairThatStalledIsPointedAtTheClientLog() {
        Path log = Path.of("/c/stagewright/client.log");
        assertNotNull(Main.companionHint(Main.Waited.STALLED, log));
        assertNotNull(Main.companionHint(Main.Waited.TIMED_OUT, log));
        assertNull(Main.companionHint(Main.Waited.DONE, log));
    }
}
