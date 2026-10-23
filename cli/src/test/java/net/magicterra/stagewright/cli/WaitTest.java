package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** How a run's wait for its footer ends, and what it says when the pair never met. */
class WaitTest {

    @Test
    void aPairThatStalledIsPointedAtTheClientLog() {
        Path log = Path.of("/c/stagewright/client.log");
        assertNotNull(Main.companionHint(Main.Waited.TIMED_OUT, log));
        assertNotNull(Main.companionHint(Main.Waited.NO_PROGRESS, log));
        assertNull(Main.companionHint(Main.Waited.DONE, log));
    }
}
