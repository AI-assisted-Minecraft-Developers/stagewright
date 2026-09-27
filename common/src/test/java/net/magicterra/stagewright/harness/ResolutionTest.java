package net.magicterra.stagewright.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.magicterra.stagewright.contract.SceneOutcome;
import org.junit.jupiter.api.Test;

class ResolutionTest {

    @Test
    void aFailedCleanupTurnsAPassIntoAFail() {
        var r = new StageWrightHarness.Resolution(SceneOutcome.PASS, "", false).afterCleanups(List.of("boom"));
        assertEquals(SceneOutcome.FAIL, r.outcome());
        assertEquals("cleanup failed: boom", r.reason());
    }

    @Test
    void aSkipWhoseCleanupFailedIsStillASkip() {
        var r = new StageWrightHarness.Resolution(SceneOutcome.PASS, "skipped: no player", true)
                .afterCleanups(List.of("boom"));
        assertEquals(SceneOutcome.FAIL, r.outcome());
        assertTrue(r.skipped(), "the scene tested nothing, and coverage must still see that");
    }

    @Test
    void aFailOrTimeoutKeepsItsOwnReason() {
        var r = new StageWrightHarness.Resolution(SceneOutcome.TIMEOUT, "budget", false)
                .afterCleanups(List.of("boom"));
        assertEquals(new StageWrightHarness.Resolution(SceneOutcome.TIMEOUT, "budget", false), r);
    }
}
