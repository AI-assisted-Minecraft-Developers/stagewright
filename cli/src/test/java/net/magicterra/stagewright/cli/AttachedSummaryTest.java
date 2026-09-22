package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The last line an {@code --attached} run prints. It is the line a reader acts on, so it has to name
 * the code the process exits with — DEAD and ENV are not louder REDs, they send you somewhere else.
 */
class AttachedSummaryTest {

    private static final Path FILE = Path.of("stagewright-attached-results.jsonl");
    private static final String RUN = "[stagewright] VERDICT (in-process and attached): ";

    private static String last(List<String> lines) {
        return lines.get(lines.size() - 1);
    }

    @Test
    void aDeadInProcessHalfIsReportedDead() {
        assertEquals(RUN + "DEAD", last(Main.attachedSummary(2, 0, FILE)));
    }

    @Test
    void anInProcessHalfThatNeverArmedIsReportedEnv() {
        assertEquals(RUN + "ENV", last(Main.attachedSummary(3, 1, FILE)));
    }

    @Test
    void aRedAttachedHalfTurnsAGreenSuiteRed() {
        List<String> lines = Main.attachedSummary(0, 1, FILE);
        assertEquals("[stagewright] ATTACHED VERDICT: RED  (" + FILE + ")", lines.get(0));
        assertEquals(RUN + "RED", last(lines));
    }

    @Test
    void bothHalvesGreenIsGreen() {
        assertEquals(RUN + "GREEN", last(Main.attachedSummary(0, 0, FILE)));
    }
}
