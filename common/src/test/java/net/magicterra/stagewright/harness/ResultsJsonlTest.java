package net.magicterra.stagewright.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import net.magicterra.stagewright.contract.SceneOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a scene record says about whether the scene's body ever ran. */
class ResultsJsonlTest {

    @Test
    void aRecordWrittenBeforeTheBodyRanSaysSo(@TempDir Path dir) throws IOException {
        // A TIMEOUT out of PREP reads like any other TIMEOUT by outcome, and a TIMEOUT counts as
        // an execution; only this field tells coverage that nothing was tested.
        Path file = dir.resolve("results.jsonl");
        new ResultsJsonl(file).writeScene("a", SceneOutcome.TIMEOUT, 0, 10, "starved", Map.of(), false, false);
        assertTrue(Files.readString(file).contains("\"bodyRan\":false"), Files.readString(file));
    }

    @Test
    void aRecordWhoseBodyRanOmitsTheField(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("results.jsonl");
        new ResultsJsonl(file).writeScene("a", SceneOutcome.PASS, 5, 10, "", Map.of(), false, true);
        assertFalse(Files.readString(file).contains("bodyRan"), Files.readString(file));
    }
}
