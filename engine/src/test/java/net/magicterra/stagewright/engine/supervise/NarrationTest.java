package net.magicterra.stagewright.engine.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class NarrationTest {

    @Test
    void aLeakIsNamedWithTheJarsItsStackRunsThrough() {
        List<String> lines = Narration.leaks(List.of(new Supervisor.Leak("mod-pool-1",
                List.of("java.base@21/java.lang.Thread.sleep(Native Method)", "x.Pool.run(Pool.java:9)"),
                List.of("file:/mods/x.jar"))));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("WARNING:"), lines.toString());
        // The JDK's own frames, with or without their module prefix, say nothing about whose thread it is.
        assertEquals("  mod-pool-1 at x.Pool.run(Pool.java:9)  [file:/mods/x.jar]", lines.get(1));
    }

    @Test
    void aStackOfNothingButTheJdkFallsBackToItsTopFrame() {
        List<String> lines = Narration.leaks(List.of(new Supervisor.Leak("t",
                List.of("java.lang.Thread.sleep(Native Method)"), List.of())));
        assertEquals("  t at java.lang.Thread.sleep(Native Method)", lines.get(1));
    }

    @Test
    void noLeakSaysNothing() {
        assertEquals(List.of(), Narration.leaks(List.of()));
    }

    @Test
    void aStallNamesTheMinutesTheScreenAndWhatTheProcessWasDoing() {
        String said = Narration.noProgress("client", 1, "armed", 0, "the title screen", "it is ASLEEP");
        assertTrue(said.contains("for 1 minute:"), said);
        assertTrue(said.contains("sitting on the title screen"), said);
        assertTrue(said.contains("it is ASLEEP."), said);
        assertTrue(Narration.noProgress("server", 5, "armed", 3, null, null).contains("5 minutes"));
    }
}
