package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.engine.RunDirectory;
import org.junit.jupiter.api.Test;

/**
 * {@code --results} has to reach the writer, not only the reader.
 *
 * <p>It reached only the reader until this existed: the CLI opened the renamed file and the game
 * went on writing the default one, so a run that executed every scene and finished green was judged
 * "ENV — the run wrote no results" against a path nothing was ever going to write.
 */
class ResultsNameTest {

    @Test
    void theDefaultIsTheNameTheHarnessWrites() {
        assertEquals("stagewright-results.jsonl", Main.resultsName(Map.of()));
        assertEquals(RunDirectory.DEFAULT_RESULTS_FILE, Main.resultsName(Map.of()));
    }

    @Test
    void aRenamedRunCarriesTheNameIntoTheGame() {
        String name = Main.resultsName(Map.of("results", "stagewright-server.jsonl"));
        assertEquals("stagewright-server.jsonl", name);
        assertEquals(List.of("-Dstagewright.results=stagewright-server.jsonl"),
                Main.resultsProps(name));
    }

    @Test
    void theDefaultIsPassedTooSoTheCommandLineRecordsIt() {
        assertEquals(List.of("-Dstagewright.results=stagewright-results.jsonl"),
                Main.resultsProps(Main.resultsName(Map.of())));
    }

    /**
     * The property spelling is asserted as a literal on purpose. The game reads it from a module
     * this one shares no classpath with, so the only thing holding the two ends together is the
     * string — and renaming the constant here would otherwise compile, run, and silently go back to
     * being a read-side-only flag.
     */
    @Test
    void thePropertyIsSpeltTheWayTheGameReadsIt() {
        assertEquals("stagewright.results", RunDirectory.RESULTS_PROPERTY);
        assertTrue(Main.resultsProps("x.jsonl").get(0).startsWith("-Dstagewright.results="));
    }

    @Test
    void anEmptyNameIsRefusedRatherThanResolvedToTheDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.resultsName(Map.of("results", "   ")));
    }
}
