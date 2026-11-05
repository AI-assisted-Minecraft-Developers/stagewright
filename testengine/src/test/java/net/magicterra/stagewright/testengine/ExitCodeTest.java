package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** How a game that died says how: its own exit code, or the signal that ended it. */
@DisabledOnOs(OS.WINDOWS)
class ExitCodeTest {

    private static String endedBy(String script) throws Exception {
        return SceneRun.exitCode(new ProcessBuilder("sh", "-c", script).start());
    }

    @Test
    void aGameThatExitsByItselfGivesItsCode() throws Exception {
        assertEquals("; its exit code was 3", endedBy("exit 3"));
    }

    @Test
    void aGameEndedByASignalNamesIt() throws Exception {
        assertEquals("; its exit code was 143 (SIGTERM, 128 + 15)", endedBy("kill -TERM $$"));
        assertEquals("; its exit code was 137 (SIGKILL, 128 + 9)", endedBy("kill -KILL $$"));
    }

    @Test
    void aGameStillRunningSaysNothing() throws Exception {
        Process running = new ProcessBuilder("sleep", "30").start();
        try {
            assertEquals("", SceneRun.exitCode(running));
        } finally {
            running.destroyForcibly();
        }
    }
}
