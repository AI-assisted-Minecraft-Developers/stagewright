package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import net.magicterra.stagewright.engine.RunDirectory;
import org.junit.jupiter.api.Test;

/**
 * What the ENV verdict says when a run wrote no results.
 *
 * <p>The rule under test is that a crash report is the ONLY thing said when there is one. It was not
 * always: a real run reported the crash, named the report, and then went on to offer the framework
 * jar, the {@code -lwjgl} stub and the renamed results file as explanations — three guesses printed
 * after the truth, and therefore the last three things the reader saw.
 */
class NoResultsCauseTest {

    private static final Path GAME_DIR = Path.of("/packs/magic/run/gamedir");
    private static final Path LOG = Path.of("/packs/magic/run/gamedir/stagewright/stagewright-run.log");
    private static final Path REPORT =
            GAME_DIR.resolve("crash-reports/crash-2026-09-11_01.21.52-client.txt");

    @Test
    void aCrashReportIsTheOnlyThingSaid() {
        List<String> lines = Main.noResultsCause(GAME_DIR, REPORT, true, true, LOG);

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains(REPORT.toString()), lines.get(0));
    }

    @Test
    void aCrashReportSuppressesTheFrameworkGuess() {
        List<String> lines = Main.noResultsCause(GAME_DIR, REPORT, true, true, LOG);

        assertFalse(joined(lines).contains("StageWright"), joined(lines));
        // …and equally when the jar really is missing: the crash still happened, and the report
        // says what it was.
        assertEquals(1, Main.noResultsCause(GAME_DIR, REPORT, false, false, LOG).size());
    }

    @Test
    void aCrashReportSuppressesTheOldJarGuess() {
        List<String> lines = Main.noResultsCause(GAME_DIR, REPORT, false, true, LOG);

        assertEquals(1, lines.size(), lines.toString());
        assertFalse(joined(lines).contains(RunDirectory.RESULTS_PROPERTY), joined(lines));
    }

    @Test
    void withNoReportTheFrameworkIsRuledInOrOut() {
        assertTrue(joined(Main.noResultsCause(GAME_DIR, null, false, false, LOG))
                .contains("no StageWright jar"));
        assertTrue(joined(Main.noResultsCause(GAME_DIR, null, false, true, LOG))
                .contains("A StageWright jar IS"));
        assertTrue(joined(Main.noResultsCause(GAME_DIR, null, true, true, LOG))
                .contains("handed StageWright to the loader"));
    }

    @Test
    void withNoReportThePacksOwnJarAlsoGetsTheSkewWarning() {
        List<String> lines = Main.noResultsCause(GAME_DIR, null, false, true, LOG);

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(joined(lines).contains(RunDirectory.RESULTS_PROPERTY), joined(lines));
        // Where a jar older than the property actually writes: the old name, at the top level.
        assertTrue(joined(lines).contains(GAME_DIR.resolve("stagewright-results.jsonl").toString()),
                joined(lines));
    }

    @Test
    void withNoReportAJarThisCliHandedOverGetsNoSkewWarning() {
        List<String> lines = Main.noResultsCause(GAME_DIR, null, true, true, LOG);

        assertEquals(1, lines.size(), lines.toString());
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }
}
